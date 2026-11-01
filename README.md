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

---

# Tivinator für LG-Fernseher (webOS)

GitHub baut bei jedem Update automatisch auch die LG-Version **Tivinator.ipk** (gleiche Oberfläche wie auf dem Fire TV).
Alles ist kostenlos. Du brauchst einmalig einen Mac oder PC im selben WLAN wie der Fernseher.

## Einmalig: Entwicklermodus am LG einschalten
1. Am Mac auf **webostv.developer.lge.com** ein kostenloses LG-Entwicklerkonto anlegen (oben rechts „Sign In“ → „Create Account“).
2. Am Fernseher den **LG Content Store** öffnen, nach **„Developer Mode“** suchen und installieren.
3. Die App „Developer Mode“ öffnen, mit dem Entwicklerkonto anmelden, **Dev Mode Status** einschalten → der Fernseher startet neu.
4. „Developer Mode“ erneut öffnen und **Key Server** einschalten. Die angezeigte **IP-Adresse** und **Passphrase** brauchst du gleich.

## Einmalig: Tivinator installieren (Mac)
1. **webOS Dev Manager** herunterladen: github.com/webosbrew/dev-manager-desktop → „Releases“ → die `.dmg`-Datei für macOS.
   Beim ersten Öffnen blockiert macOS evtl. die App: Rechtsklick auf die App → **Öffnen** → **Öffnen**.
2. Im Dev Manager **Gerät hinzufügen**: den Fernseher wählen (oder IP-Adresse eingeben) und die **Passphrase** eintragen.
3. Tivinator herunterladen: `https://github.com/priestxjee/netefeliks/releases/latest/download/Tivinator.ipk`
4. Im Dev Manager unter **Apps** auf **Installieren** (bzw. „+“) klicken und die Datei `Tivinator.ipk` wählen.
5. Fertig – Tivinator erscheint in der App-Leiste des Fernsehers.

## Wichtig: Entwicklermodus alle 50 Stunden verlängern
- Der Entwicklermodus läuft nach **50 Stunden** ab. Verlängern: am Fernseher die App „Developer Mode“ öffnen → **Extend**,
  oder im Dev Manager auf **Verlängern/Renew** klicken. Am einfachsten alle 1–2 Tage kurz erledigen.
- Läuft er doch ab, verschwindet Tivinator vom Fernseher → einfach Schritt 3–4 oben wiederholen.
  Login und Profile müssen dann neu eingerichtet werden.

## Updates
- Die Oberfläche aktualisiert sich automatisch: Tivinator prüft beim Start und alle 6 Stunden auf GitHub,
  lädt Updates im Hintergrund und nutzt sie beim nächsten Öffnen. Neu installieren musst du dafür nichts.

## Technik
- Der Browser des Fernsehers sperrt direkte Verbindungen zu fremden Servern. Deshalb installiert Tivinator.ipk einen
  kleinen Hintergrunddienst mit, der die Anfragen an deinen IPTV-Server weiterleitet (nur im Fernseher, über 127.0.0.1).

## Unterschiede zur Fire-TV-Version
- Video läuft über den Player des Fernsehers. Tonspuren und Untertitel sind je nach Stream eingeschränkt.
- Offline-Downloads, „Daten sichern“ und das Fire-TV-Spezial-Tonmischen gibt es auf dem LG nicht.
