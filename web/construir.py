"""Une la app con la librería de QR en un solo archivo.

Crea web/dist/index.html (para GitHub Pages) y copia el mismo archivo a
android/app/src/main/assets/index.html (para la app Android).
"""
from pathlib import Path

web = Path(__file__).resolve().parent
html = (web / "src/app.html").read_text(encoding="utf-8")
qr = (web / "src/qrcode.js").read_text(encoding="utf-8")
cabecera = "/* qrcode-generator 1.4.4 · (c) Kazuhiko Arase · licencia MIT · https://github.com/kazuhikoarase/qrcode-generator */\n"
final = html.replace("/*QRLIB*/", cabecera + qr)

destinos = [web / "dist/index.html", web.parent / "android/app/src/main/assets/index.html"]
for d in destinos:
    d.parent.mkdir(parents=True, exist_ok=True)
    d.write_text(final, encoding="utf-8")
    print("Creado", d.relative_to(web.parent), f"({d.stat().st_size // 1024} KB)")
