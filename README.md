# Netefeliks für Fire TV

## Einmalig: APK bauen lassen (ca. 10 Minuten)
1. Kostenloses Konto auf github.com anlegen (falls noch nicht vorhanden).
2. Oben rechts „+“ → **New repository** → Name z. B. `netefeliks` → **Public** → „Create repository“.
   (Public, damit der Fire TV die APK ohne Login laden kann. Im Code stehen keine Passwörter – deine IPTV-Zugangsdaten gibst du erst in der App ein.)
3. „uploading an existing file“ anklicken → den **gesamten Inhalt** dieses Ordners hineinziehen (auch den Ordner `.github`) → „Commit changes“.
   Tipp: Ordner, die mit einem Punkt beginnen, sind am Mac/PC oft unsichtbar. Am einfachsten den Ordner im Browser-Fenster per Drag & Drop ablegen.
4. Reiter **Actions** → der Lauf „Fire-TV-APK bauen“ startet automatisch. Nach ca. 5 Minuten grüner Haken.

## Auf den Fire TV
1. Fire TV: Einstellungen → Mein Fire TV → Entwickleroptionen → **Apps unbekannter Herkunft** für die App **Downloader** erlauben.
2. In Downloader diese Adresse eingeben (DEINNAME = dein GitHub-Name):
   `https://github.com/DEINNAME/netefeliks/releases/latest/download/Netefeliks.apk`
3. Installieren → Netefeliks erscheint bei deinen Apps.

## Updates
1. Neue Dateien von Claude ins Repository hochladen (gleiche Stelle, alte Dateien werden ersetzt) → GitHub baut automatisch die neue APK.
2. Auf dem Fire TV in Downloader **dieselbe Adresse** wie oben aufrufen → installieren.
Profile, Verlauf, Listen und Zugangsdaten bleiben erhalten (gleicher Signaturschlüssel `app/netefeliks.keystore` – nicht löschen).
