package co.traspasoseguro.app;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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

import org.json.JSONObject;

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
    private static final int ELEGIR_CUENTA = 8;

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

    /** Ejecuta una función de la página (siempre desde el hilo principal). */
    private void avisarPagina(final String funcion, final String json) {
        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript("window." + funcion + " && window." + funcion + "(" + json + ")", null);
            }
        });
    }

    @Override
    protected void onActivityResult(int codigo, int resultado, Intent datos) {
        super.onActivityResult(codigo, resultado, datos);
        if (codigo != ELEGIR_CUENTA) return;
        if (resultado == RESULT_OK && datos != null) {
            String nombre = datos.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            String tipo = datos.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
            avisarPagina("alElegirCuenta", "{\"nombre\":" + JSONObject.quote(nombre == null ? "" : nombre)
                    + ",\"tipo\":" + JSONObject.quote(tipo == null ? "" : tipo) + "}");
        } else {
            avisarPagina("alElegirCuenta", "null");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Al volver de Ajustes, la página vuelve a comprobar las cuentas.
        avisarPagina("alVolver", "null");
    }

    /** Datos que la página puede pedir a Android a través de window.TraspasoNativo. */
    private class Puente {
        @JavascriptInterface
        public String bateria() {
            return Sistema.bateria(MainActivity.this);
        }

        @JavascriptInterface
        public String integridad(String reto) {
            return Sistema.integridad(reto == null ? "" : reto);
        }

        @JavascriptInterface
        public String firmar(String datos) {
            return Sistema.firmar(datos == null ? "" : datos);
        }

        @JavascriptInterface
        public String cuentasVisibles() {
            return Sistema.cuentasVisibles(MainActivity.this);
        }

        /** Abre el selector de cuentas del sistema: muestra las de Google, Samsung y Xiaomi que haya. */
        @JavascriptInterface
        public void elegirCuenta() {
            runOnUiThread(() -> {
                try {
                    Intent i = AccountManager.newChooseAccountIntent(null, null, Sistema.TIPOS_CUENTA,
                            null, null, null, null);
                    startActivityForResult(i, ELEGIR_CUENTA);
                } catch (Exception e) {
                    avisarPagina("alElegirCuenta", "null");
                }
            });
        }

        /** Abre una pantalla de Ajustes: "cuentas" o "general". */
        @JavascriptInterface
        public void abrirAjustes(String cual) {
            runOnUiThread(() -> {
                String accion = "cuentas".equals(cual) ? Settings.ACTION_SYNC_SETTINGS : Settings.ACTION_SETTINGS;
                try {
                    startActivity(new Intent(accion));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return "android-" + Build.VERSION.SDK_INT;
        }
    }
}
