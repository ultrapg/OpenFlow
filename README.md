# OpenFlow

Willkommen bei **OpenFlow**, einer vollkommen offline funktionierenden, hochpräzisen Speech-to-Text (Sprache-zu-Text) App für Android. 

Diese App wurde entwickelt, um Sprache extrem schnell, präzise und datenschutzfreundlich direkt auf deinem Smartphone zu transkribieren.

## 🚀 Features
- **100% Offline:** Deine Audiodaten verlassen niemals dein Gerät. Alles wird lokal über C++ (Sherpa-ONNX) berechnet.
- **Hohe Genauigkeit:** Nutzt das fortschrittliche NVIDIA Canary 180M Modell für perfekte Satzzeichen, Groß-/Kleinschreibung und Denglisch-Support.
- **Auto-Spracherkennung (LID):** Ein winziges Whisper-Modell (int8) lauscht kurz, erkennt automatisch, ob du Deutsch oder Englisch sprichst, und steuert das Hauptmodell entsprechend.
- **Überall diktieren:** Fügt den gesprochenen Text über einen Accessibility-Service automatisch in jedes beliebige Textfeld auf deinem Handy ein.
- **Floating Bubble UI:** Eine kleine schwebende Blase bleibt immer griffbereit am Bildschirmrand.

## 📦 Installation

Du findest die fertige APK direkt hier im Repository im Ordner `release`. Lade sie einfach herunter und installiere sie auf deinem Android-Gerät.

### WICHTIG: Modelle & Bibliotheken herunterladen (Nur für Entwickler)
Da die KI-Modelle und C++ Bibliotheken zu groß für GitHub sind, liegen sie nicht in diesem Repository. Wenn du den Code klonst und selbst kompilieren möchtest, führe einfach dieses Skript im Hauptverzeichnis aus:

```bash
./setup_project.sh
```

Das Skript lädt automatisch die C++ Bibliotheken (`.aar`) sowie die stark komprimierten (INT8) Versionen von **NVIDIA Canary** und **Whisper Tiny** herunter und platziert alles exakt im richtigen Ordner. Danach kannst du die App fehlerfrei in Android Studio bauen!

## 🛠️ Gebaut mit
- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)
- Android Jetpack Compose
- Kotlin Coroutines

## 📜 Lizenz
Dieses Projekt ist unter der **GNU General Public License v3.0 (GPL-3.0)** lizenziert. Weitere Informationen findest du in der `LICENSE`-Datei.

---
Entwickelt von [@ultrapg](https://github.com/ultrapg)
