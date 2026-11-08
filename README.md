# Traspaso seguro

Guía a vendedor y comprador de un celular usado para que el teléfono cambie de dueño **sin quedar bloqueado** por la cuenta de Google (FRP) y **sin un IMEI reportado**. Al final, el comprador recibe un certificado en su propio celular escaneando un código QR.

- **App Android (APK):** en [Releases](../../releases/latest). Se compila sola con cada cambio.
- **Versión web:** <https://maryangeld2026-hash.github.io/Traspaso-seguro-propuesta/> (los QR del certificado apuntan aquí, así el comprador no necesita instalar nada).

## Cómo funciona

| Paso | Quién | Qué se hace |
|---|---|---|
| 1 · Preparar | Vendedor, en casa | Copia de seguridad, cerrar la cuenta de la marca y la de Google, desvincular reloj y bancos, sacar la SIM. Instrucciones para Samsung, Xiaomi, Motorola y otras. |
| 2 · Probar | Juntos | Pantalla táctil, colores, cámaras, micrófono, altavoz, vibración, sensor de movimiento, carga y revisión manual. |
| 3 · IMEI | Juntos | Valida el formato del IMEI (dígito de control) y enlaza a la consulta de IMEI Colombia. Si está reportado, no deja continuar. |
| 4 · Certificado | Juntos | Genera el QR. El comprador lo escanea y sigue la **prueba final**: el vendedor restablece desde Ajustes y el comprador configura el celular. Si no pide la cuenta anterior, está libre. |

Nada se envía a ningún servidor: el certificado viaja dentro del propio QR.

### Qué añade la app Android frente a la web: «Revisión del sistema»

Un paso más (5 en lugar de 4) con lo que solo el propio celular puede saber:

| Revisión | Cómo lo hace |
|---|---|
| **Integridad del equipo** | Crea una clave en el chip de seguridad y lee su certificado de atestación: el hardware declara si el arranque está verificado, si el bootloader está bloqueado, el nivel de seguridad (TEE o StrongBox), el parche y el modelo. |
| **Cuentas que bloquean el celular** | El vendedor muestra sus cuentas de Google, Samsung y Xiaomi con el selector del sistema; la app comprueba sola que ya no están después de quitarlas en Ajustes. |
| **Salud de la batería** | Estado según Android, ciclos de carga (Android 14+), capacidad actual estimada y de fábrica (si el equipo la expone) y salud aproximada. |
| **Certificado firmado** | Se firma con la clave del chip de seguridad. El comprador comprueba la firma en su navegador y compara la **huella** del equipo con la que muestra el celular del vendedor. Si alguien edita el certificado, la firma deja de ser válida. |

Lo que una app **no** puede hacer, y por eso sigue siendo manual: cerrar cuentas ajenas, leer el estado del bloqueo FRP o el IMEI, ni restablecer el teléfono. Un restablecimiento hecho por una app activa el bloqueo de Google igual que un borrado remoto; por eso se hace siempre desde Ajustes, delante del comprador.

## Instalar el APK

1. En el celular, abre [la última versión](../../releases/latest) y descarga `TraspasoSeguro-v1.0.N.apk`.
2. Ábrelo. Si Android lo pide, permite **Instalar apps desconocidas** para el navegador.
3. Google Play Protect puede avisar de que la app es de un desarrollador desconocido: es normal en apps que no vienen de Google Play.

## Firma del APK (opcional, recomendado)

Sin configuración, cada compilación se firma con una clave temporal distinta, así que para instalar una versión nueva hay que **desinstalar la anterior**. Para firmar siempre con la misma clave:

1. En el repositorio, ve a **Settings › Secrets and variables › Actions › New repository secret**.
2. Crea `KEYSTORE_B64` con el contenido del archivo `traspaso.jks` en base64.
3. Crea `KS_PASS` con la contraseña de la clave.
4. Vuelve a lanzar **Actions › Compilar APK › Run workflow**.

Guarda la clave y la contraseña en un lugar seguro: si se pierden, no se podrán publicar actualizaciones con la misma firma (tampoco en Google Play).

## Publicar la web en GitHub Pages

Si el flujo «Publicar la web» falla la primera vez, activa Pages a mano: **Settings › Pages › Source: GitHub Actions**, y vuelve a lanzarlo desde **Actions**.

## Estructura

```
web/src/app.html          la app (HTML, CSS y JavaScript)
web/src/qrcode.js         librería de QR (qrcode-generator, licencia MIT)
web/construir.py          une ambos en web/dist/index.html y lo copia al proyecto Android
android/                  proyecto Android (Java + WebView)
.github/workflows/        compilación del APK y publicación de la web
```

Para trabajar en local: `python3 web/construir.py` y abre `android/` con Android Studio.

## Límites conocidos

- Desde Android 10 ninguna app puede leer el IMEI ni listar las cuentas del teléfono: se escriben o se confirman a mano. La prueba final es la que garantiza que el celular queda libre.
- El enlace al portal IMEI Colombia viene de fuentes de 2015: comprueba que siga activo.
- Las rutas de los menús de Ajustes son orientativas; cambian según la marca y la versión.
- El certificado lo genera el celular del vendedor: su valor está en que el comprador ve las pruebas en persona.
