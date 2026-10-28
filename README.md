# Netefeliks für Fire TV

## Einmalig: APK bauen lassen (ca. 10 Minuten)
1. Kostenloses Konto auf github.com anlegen (falls noch nicht vorhanden).
2. Oben rechts „+“ → **New repository** → Name z. B. `netefeliks` → **Public** → „Create repository“.
   (Public, damit der Fire TV Updates ohne Login laden kann. Im Code stehen keine Passwörter – deine IPTV-Zugangsdaten gibst du erst in der App ein.)
3. „uploading an existing file“ anklicken → den **gesamten Inhalt** dieses Ordners hineinziehen (auch den Ordner `.github`) → „Commit changes“.
   Tipp: Ordner, die mit einem Punkt beginnen, sind am Mac/PC oft unsichtbar – am besten den ganzen Ordner per Drag & Drop ins Browserfenster ziehen.
4. Reiter **Actions** → „Fire-TV-APK bauen“ startet automatisch. Nach ca. 5 Minuten grüner Haken.

## Einmalig: auf den Fire TV
1. Fire TV: Einstellungen → Mein Fire TV → Entwickleroptionen → **Apps unbekannter Herkunft** für **Downloader** erlauben.
2. In Downloader eingeben (DEINNAME = dein GitHub-Name):
   `https://github.com/DEINNAME/netefeliks/releases/latest/download/Netefeliks.apk`
3. Installieren – fertig. Downloader brauchst du danach nicht mehr.

## Updates – ab jetzt automatisch
- Du lädst die neuen Dateien von Claude ins Repository hoch (alte werden ersetzt). GitHub baut automatisch die neue Version.
- Die App auf dem Fire TV prüft beim Start und alle 6 Stunden auf Updates (oder: Einstellungen → „Nach Updates suchen“)
  und zeigt **„Update verfügbar – Jetzt updaten“**.
- Normale Updates laden in Sekunden und die App startet neu.
- Größere Updates (selten, wenn sich `native-version.txt` erhöht) laden die neue APK und öffnen den Installations-Dialog:
  einmal **Installieren** drücken. Beim allerersten Mal fragt der Fire TV, ob Netefeliks Apps installieren darf → erlauben.
- Profile, Verlauf, Listen und Zugangsdaten bleiben immer erhalten.
- `app/netefeliks.keystore` nicht löschen – damit sind alle Versionen gleich signiert.
