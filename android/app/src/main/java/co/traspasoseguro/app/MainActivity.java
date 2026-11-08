package co.traspasoseguro.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Traspaso seguro: muestra la app web empaquetada en el APK y le da lo que un
 * navegador no puede: permisos de cámara y micrófono gestionados por Android y
 * los ciclos de carga de la batería (Android 14 o superior).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String INICIO = "https://" + HOST + "/assets/index.html";
    private static final int PEDIR_PERMISOS = 7;

    private WebView web;
    private PermissionRequest pendiente;

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0E14"));
        setContentView(web);

        // Android 15 dibuja la app bajo las barras del sistema: se deja margen para que nada quede tapado.
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        ajustes.setMediaPlaybackRequiresUserGesture(false);
        ajustes.setAllowFileAccess(false);
        ajustes.setAllowContentAccess(false);

        // Sirve los archivos del APK desde una dirección https para que la cámara y el micrófono funcionen.
        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return cargador.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Enlaces externos (por ejemplo, la consulta de IMEI) se abren en el navegador.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignorada) {
                    // No hay navegador: no se hace nada.
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest solicitud) {
                runOnUiThread(() -> atender(solicitud));
            }
        });

        web.addJavascriptInterface(new Puente(), "TraspasoNativo");

        if (guardado != null) {
            web.restoreState(guardado);
        } else {
            web.loadUrl(INICIO);
        }
    }

    /** La página pide cámara o micrófono: se piden a Android si hace falta y luego se conceden. */
    private void atender(PermissionRequest solicitud) {
        List<String> faltan = new ArrayList<>();
        for (String recurso : solicitud.getResources()) {
            String permiso = permisoDe(recurso);
            if (permiso != null && checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) {
                faltan.add(permiso);
            }
        }
        if (faltan.isEmpty()) {
            solicitud.grant(solicitud.getResources());
        } else {
            if (pendiente != null) pendiente.deny();
            pendiente = solicitud;
            requestPermissions(faltan.toArray(new String[0]), PEDIR_PERMISOS);
        }
    }

    private static String permisoDe(String recurso) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(recurso)) return Manifest.permission.CAMERA;
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(recurso)) return Manifest.permission.RECORD_AUDIO;
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo != PEDIR_PERMISOS || pendiente == null) return;
        List<String> concedidos = new ArrayList<>();
        for (String recurso : pendiente.getResources()) {
            String permiso = permisoDe(recurso);
            if (permiso == null || checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED) {
                concedidos.add(recurso);
            }
        }
        if (concedidos.isEmpty()) {
            pendiente.deny();
        } else {
            pendiente.grant(concedidos.toArray(new String[0]));
        }
        pendiente = null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        if (web != null) web.saveState(estado);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Datos que la página puede pedir a Android a través de window.TraspasoNativo. */
    private class Puente {
        @JavascriptInterface
        public String bateria() {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "{\"nivel\":-1,\"cargando\":false,\"ciclos\":-1}";
            int nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int estado = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean cargando = estado == BatteryManager.BATTERY_STATUS_CHARGING
                    || estado == BatteryManager.BATTERY_STATUS_FULL;
            int ciclos = -1;
            if (Build.VERSION.SDK_INT >= 34) {
                ciclos = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
            }
            int porcentaje = (nivel >= 0 && escala > 0) ? Math.round(nivel * 100f / escala) : -1;
            return "{\"nivel\":" + porcentaje + ",\"cargando\":" + cargando + ",\"ciclos\":" + ciclos + "}";
        }

        @JavascriptInterface
        public String version() {
            return "android-" + Build.VERSION.SDK_INT;
        }
    }
}
