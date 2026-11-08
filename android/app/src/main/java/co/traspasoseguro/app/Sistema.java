package co.traspasoseguro.app;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lo que la app puede saber del sistema sin permisos especiales:
 * integridad del equipo (atestación de claves), cuentas que el usuario muestra,
 * batería y firma del certificado con una clave guardada en el chip de seguridad.
 */
final class Sistema {

    /** Tipos de cuenta que activan bloqueos tras un restablecimiento: Google, Samsung y Xiaomi. */
    static final String[] TIPOS_CUENTA = {"com.google", "com.osp.app.signin", "com.xiaomi"};
    private static final String ALIAS = "traspaso-certificado";
    private static final String OID_ATESTACION = "1.3.6.1.4.1.11129.2.1.17";

    private Sistema() {}

    // ------------------------------------------------------------------ cuentas

    /** Cuentas que la app puede ver: solo las que el usuario eligió antes en el selector de cuentas. */
    static String cuentasVisibles(Context ctx) {
        JSONArray lista = new JSONArray();
        AccountManager am = AccountManager.get(ctx);
        for (String tipo : TIPOS_CUENTA) {
            for (Account a : am.getAccountsByType(tipo)) {
                JSONObject o = new JSONObject();
                try {
                    o.put("nombre", a.name);
                    o.put("tipo", a.type);
                } catch (JSONException ignorada) {
                    // no ocurre con cadenas
                }
                lista.put(o);
            }
        }
        return lista.toString();
    }

    // ------------------------------------------------------------------ batería

    static String bateria(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int nivel = -1, ciclos = -1, salud = -1;
            boolean cargando = false;
            String estado = "desconocido";
            if (i != null) {
                int bruto = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                nivel = (bruto >= 0 && escala > 0) ? Math.round(bruto * 100f / escala) : -1;
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                cargando = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                if (Build.VERSION.SDK_INT >= 34) ciclos = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
                switch (i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                    case BatteryManager.BATTERY_HEALTH_GOOD: estado = "buena"; break;
                    case BatteryManager.BATTERY_HEALTH_OVERHEAT: estado = "sobrecalentada"; break;
                    case BatteryManager.BATTERY_HEALTH_DEAD: estado = "agotada"; break;
                    case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: estado = "sobretensión"; break;
                    case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: estado = "falla"; break;
                    case BatteryManager.BATTERY_HEALTH_COLD: estado = "fría"; break;
                    default: estado = "desconocido";
                }
            }
            // Capacidad actual estimada = carga que queda (µAh) / porcentaje de carga.
            int capacidad = -1;
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null && nivel >= 15) {
                int contador = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (contador > 0 && contador != Integer.MIN_VALUE) {
                    capacidad = Math.round(contador / 1000f / (nivel / 100f));
                }
            }
            int diseno = capacidadDeFabrica(ctx);
            if (capacidad > 0 && diseno > 0) {
                salud = Math.min(100, Math.round(capacidad * 100f / diseno));
            }
            o.put("nivel", nivel);
            o.put("cargando", cargando);
            o.put("ciclos", ciclos);
            o.put("estado", estado);
            o.put("capacidad", capacidad);
            o.put("diseno", diseno);
            o.put("salud", salud);
        } catch (Exception e) {
            try { o.put("error", e.toString()); } catch (JSONException ignorada) { /* nada */ }
        }
        return o.toString();
    }

    /** Capacidad de fábrica en mAh. Android no la publica: se intenta leer del perfil de energía; -1 si no se puede. */
    private static int capacidadDeFabrica(Context ctx) {
        try {
            Class<?> c = Class.forName("com.android.internal.os.PowerProfile");
            Object perfil = c.getConstructor(Context.class).newInstance(ctx);
            double mah = (double) c.getMethod("getBatteryCapacity").invoke(perfil);
            return mah > 500 ? (int) Math.round(mah) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ integridad

    /**
     * Crea una clave en el chip de seguridad con un reto y lee el certificado de atestación:
     * el propio hardware declara si el arranque está verificado y el bootloader bloqueado.
     */
    static String integridad(String reto) {
        JSONObject o = new JSONObject();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
            byte[] bytesReto = reto.getBytes(StandardCharsets.UTF_8);
            try {
                crearClave(bytesReto, true);
            } catch (Exception sinPropiedades) {
                // Algunos equipos no permiten atestar marca y modelo: se repite sin ellos.
                if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
                crearClave(bytesReto, false);
            }
            Certificate[] cadena = ks.getCertificateChain(ALIAS);
            if (cadena == null || cadena.length == 0) throw new IllegalStateException("sin certificados");

            boolean cadenaValida = true;
            for (int i = 0; i < cadena.length - 1; i++) {
                try {
                    cadena[i].verify(cadena[i + 1].getPublicKey());
                } catch (Exception e) {
                    cadenaValida = false;
                }
            }
            X509Certificate hoja = (X509Certificate) cadena[0];
            X509Certificate raiz = (X509Certificate) cadena[cadena.length - 1];
            String sujetoRaiz = raiz.getSubjectX500Principal().getName();
            o.put("cadena", cadenaValida);
            o.put("eslabones", cadena.length);
            o.put("raizSoftware", sujetoRaiz.contains("Software Attestation"));
            o.put("huella", huella(hoja.getPublicKey().getEncoded()));

            byte[] ext = hoja.getExtensionValue(OID_ATESTACION);
            if (ext == null) {
                o.put("atestacion", false);
                return o.toString();
            }
            o.put("atestacion", true);
            List<Tlv> desc = leer(leer(leer(ext).get(0).valor).get(0).valor);
            int nivel = (int) entero(desc.get(1).valor);
            o.put("nivel", nivel == 2 ? "StrongBox" : nivel == 1 ? "TEE" : "Software");
            o.put("retoValido", Arrays.equals(desc.get(4).valor, bytesReto));

            List<Tlv> hardware = leer(desc.get(7).valor);
            List<Tlv> software = leer(desc.get(6).valor);
            Tlv raizConfianza = buscar(hardware, software, 704);
            if (raizConfianza != null) {
                List<Tlv> rot = leer(leer(raizConfianza.valor).get(0).valor);
                o.put("bloqueado", rot.get(1).valor.length > 0 && rot.get(1).valor[0] != 0);
                int arranque = (int) entero(rot.get(2).valor);
                o.put("arranque", arranque == 0 ? "verificado" : arranque == 1 ? "autofirmado"
                        : arranque == 2 ? "sin verificar" : "fallido");
            }
            Tlv parche = buscar(hardware, software, 706);
            if (parche != null) o.put("parche", String.valueOf(entero(leer(parche.valor).get(0).valor)));
            Tlv modelo = buscar(hardware, software, 717);
            if (modelo != null) o.put("modelo", new String(leer(modelo.valor).get(0).valor, StandardCharsets.UTF_8));
            Tlv marca = buscar(hardware, software, 710);
            if (marca != null) o.put("marca", new String(leer(marca.valor).get(0).valor, StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (JSONException ignorada) { /* nada */ }
        }
        return o.toString();
    }

    private static void crearClave(byte[] reto, boolean conPropiedades) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256);
        if (reto != null) b.setAttestationChallenge(reto);
        if (conPropiedades && reto != null && Build.VERSION.SDK_INT >= 31) {
            b.setDevicePropertiesAttestationIncluded(true);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(b.build());
        g.generateKeyPair();
    }

    // ------------------------------------------------------------------ firma

    /** Firma el certificado con la clave del chip de seguridad. Devuelve la firma y la clave pública. */
    static String firmar(String datos) {
        JSONObject o = new JSONObject();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) crearClave(null, false);
            PrivateKey k = (PrivateKey) ks.getKey(ALIAS, null);
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(k);
            s.update(datos.getBytes(StandardCharsets.UTF_8));
            byte[] publica = ks.getCertificate(ALIAS).getPublicKey().getEncoded();
            o.put("firma", b64(s.sign()));
            o.put("clave", b64(publica));
            o.put("huella", huella(publica));
        } catch (Exception e) {
            try { o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (JSONException ignorada) { /* nada */ }
        }
        return o.toString();
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** Primeros 4 bytes del SHA-256 de la clave pública, en hexadecimal: fácil de comparar a simple vista. */
    static String huella(byte[] clavePublica) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(clavePublica);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(String.format("%02X", h[i] & 0xff));
        return sb.substring(0, 4) + "-" + sb.substring(4);
    }

    // ------------------------------------------------------------------ lector DER mínimo

    static final class Tlv {
        int clase;
        int etiqueta;
        byte[] valor;
    }

    static List<Tlv> leer(byte[] b) {
        List<Tlv> r = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            Tlv t = new Tlv();
            int x = b[i++] & 0xff;
            t.clase = x >> 6;
            int n = x & 0x1f;
            if (n == 0x1f) {
                n = 0;
                int y;
                do {
                    y = b[i++] & 0xff;
                    n = (n << 7) | (y & 0x7f);
                } while ((y & 0x80) != 0);
            }
            t.etiqueta = n;
            int largo = b[i++] & 0xff;
            if ((largo & 0x80) != 0) {
                int k = largo & 0x7f;
                largo = 0;
                for (int j = 0; j < k; j++) largo = (largo << 8) | (b[i++] & 0xff);
            }
            t.valor = Arrays.copyOfRange(b, i, i + largo);
            i += largo;
            r.add(t);
        }
        return r;
    }

    private static long entero(byte[] v) {
        long n = 0;
        for (byte x : v) n = (n << 8) | (x & 0xff);
        return n;
    }

    /** Busca una etiqueta de contexto primero en la lista del hardware y después en la del software. */
    private static Tlv buscar(List<Tlv> hardware, List<Tlv> software, int etiqueta) {
        for (Tlv t : hardware) if (t.clase == 2 && t.etiqueta == etiqueta) return t;
        for (Tlv t : software) if (t.clase == 2 && t.etiqueta == etiqueta) return t;
        return null;
    }
}
