# Notes

Persönlicher Ort für alles, was ich mir im Laufe des Tages aufschreibe. Läuft nur für mich auf meinem Server (`https://notes.welles.app`) und meinen Geräten.

v1 enthält das Modul **Post-its** plus Core (Kontexte, generischer Sync, Schnellerfassung). Weitere Module (To-dos, Wissensdatenbank, KI) kommen später und docken an denselben Core an.

| Teil | Technik | Status |
|---|---|---|
| Server + Web | .NET 10, ASP.NET Core, Blazor, EF Core + SQLite | Core & Post-its (Service-Schicht) |
| Android + Desktop | Kotlin Multiplatform, Compose Multiplatform, Room, Ktor, Koin | `clients/` |

## Struktur

```
server/
  Notes.Core/              Sync, Modul-Registrierung, Capture & Aktionen, Kontexte, Event-Bus
  Notes.Modules.Stickies/  Modul Post-its (Entität, Service, Sync-Handler, Capture-Ziel)
  Notes.Server/            Host, Komposition der Module, EF-Migrationen
  Notes.Server.Tests/      xUnit v3
clients/                   Gradle-KMP-Projekt (Android + Desktop/JVM)
  core/model/              DTOs (API-Vertrag), IDs, Zeit
  core/data/               Room-Entities/DAOs für Outbox, Sync-Status, lokale Einstellungen, Kontexte; LocalStore, Capture, Aktionen
  core/sync/               Sync-Engine, API-Client, Scheduler
  core/ui/                 Theme, Shell (Navigation, Kontextwechsel, Schnellerfassung), Einstellungen
  feature/stickies/        Modul Post-its (Room, Repository, ViewModels, Screens; androidMain: Widgets)
  app/                     gemeinsame Komposition: Room-Datenbank (alle Entities), Koin-Module, Root-Composable
  androidApp/, desktopApp/ Einstiegspunkte
docs/decisions.md          Entscheidungslog
flake.nix                  Dev-Shell (später auch Desktop-Paket)
```

## Lokal entwickeln

Voraussetzung: Nix mit Flakes – oder .NET SDK 10 direkt.

```bash
nix develop
```

Tests:

```bash
cd server && dotnet test
```

Server lokal starten (Datenbank `server/Notes.Server/notes-dev.db`, Port 5080):

```bash
cd server && dotnet run --project Notes.Server
```

Health-Check: `curl http://localhost:5080/healthz`

Neue EF-Migration nach Modelländerungen:

```bash
cd server && dotnet tool restore && dotnet ef migrations add <Name> -p Notes.Server -o Data/Migrations
```

Ein Test (`MigrationTests`) schlägt fehl, wenn das Modell geändert wurde, ohne eine Migration anzulegen.

Lokal ist die Cloudflare-Prüfung über das Launch-Profil abgeschaltet (`CloudflareAccess__Enabled=false`, Warnung im Log).
Web-UI: <http://localhost:5080>, OpenAPI: <http://localhost:5080/api/openapi.json>.

## Clients (Android + Desktop)

Dev-Shell mit Android-SDK (einmalig ~1 GB):

```bash
nix develop .#android
cd clients
```

Desktop starten (Daten unter `$XDG_DATA_HOME/notes/`):

```bash
./gradlew :desktopApp:run
```

Android-Debug-APK bauen und installieren:

```bash
./gradlew :androidApp:assembleDebug && adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Tests (gemeinsamer Code läuft auf JVM; Room in-memory):

```bash
./gradlew :core:model:desktopTest :app:desktopTest
```

Room-Schemas liegen committed unter `clients/app/schemas/` (Grundlage für Migrationstests).

### Verbindung einrichten (Android/Desktop)

In der App: Einstellungen → Synchronisation. Server-URL (vorbelegt `https://notes.welles.app`), CF-Access-Client-ID und -Secret eines Service-Tokens (am besten einer pro Gerät, siehe „Cloudflare Zero Trust einrichten“). Leer gelassene Token-Felder = keine Cloudflare-Header (lokale Entwicklung).

Bequemer per QR-Code (Android) bzw. JSON einfügen (Desktop). Am PC erzeugen:

```bash
read -rsp "Client-Secret: " SECRET; echo
qrencode -t ansiutf8 "{\"url\": \"https://notes.welles.app\", \"clientId\": \"<id>.access\", \"clientSecret\": \"$SECRET\"}"
```

Am Handy: „QR-Code scannen“ (Google Code Scanner, braucht Google-Play-Dienste). Am Desktop dasselbe JSON in „JSON einfügen“ kopieren.

Die Zugangsdaten liegen verschlüsselt: Android über Tink mit Schlüssel im Android Keystore (nicht im Backup), Desktop im Secret Service (gnome-keyring, KWallet, KeePassXC …) über `secret-tool`; ohne Secret Service als Datei `$XDG_CONFIG_HOME/notes/credentials.json` mit Rechten 0600 (Warnung im Log).

**Live-Sync:** Solange die App im Vordergrund ist (Desktop: solange Notes läuft), hält sie eine Server-Sent-Events-Verbindung zu `/api/sync/events`. Der Server meldet jede Änderung sofort, der Client holt sie über den normalen Pull. Gemessen lokal: ~40 ms Client↔Client, ~80–180 ms Server→App; nach einem Server-Neustart verbinden sich die Clients innerhalb weniger Sekunden neu. Über Cloudflare kommt die Netzwerk-Latenz dazu; ein Heartbeat alle 25 s hält die Verbindung durch den Tunnel offen. Ältere Server ohne Fähigkeit `sync.events` werden wie bisher nur abgefragt.

Sync läuft außerdem: Android beim Start/Vordergrund, ca. 2 s nach lokalen Änderungen, per Pull-to-Refresh und alle 15 min im Hintergrund (WorkManager, nur mit Netz); Desktop beim Start, bei Fensterfokus, nach lokalen Änderungen und alle 5 min. Fehler werden mit Backoff wiederholt.

Lokal gegen einen Dev-Server testen: Debug-Builds erlauben Klartext-HTTP zu `10.0.2.2` (Emulator) und `localhost` (`adb reverse tcp:5080 tcp:5080`).

## Desktop (NixOS)

### Installieren

Flake-Input in der NixOS-/Home-Manager-Konfiguration:

```nix
inputs.notes.url = "github:welles/notes-app";
```

NixOS:

```nix
environment.systemPackages = [ inputs.notes.packages.x86_64-linux.default ];
```

Home-Manager, inklusive Autostart im Hintergrund (Post-its erscheinen nach dem Login, Hauptfenster bleibt zu):

```nix
home.packages = [ inputs.notes.packages.x86_64-linux.default ];
xdg.configFile."autostart/notes.desktop".text = ''
  [Desktop Entry]
  Type=Application
  Name=Notes
  Exec=notes --background
'';
```

Ausprobieren ohne Installation:

```bash
nix run github:welles/notes-app
```

Gebaut wird aus dem Quellcode (Gradle-Abhängigkeiten gepinnt in `nix/notes-desktop-deps.json`). Nach Änderungen an Abhängigkeiten:

```bash
nix build .#notes-desktop.mitmCache.updateScript -o update-deps && ./update-deps
```

Notes läuft unter Wayland über XWayland (AWT/X11). Zugangsdaten landen über `secret-tool` im Secret Service.

### Befehle

```
notes                      Hauptfenster (startet Notes, falls nötig)
notes --capture            Schnellerfassung
notes --capture-text TEXT  Post-it ohne Fenster
notes --background         ohne Hauptfenster starten
notes --quit               beenden
```

Notes läuft nur einmal: Ein zweiter Aufruf reicht seine Argumente über `$XDG_RUNTIME_DIR/notes.sock` an die laufende Instanz weiter. Schließen des Hauptfensters beendet Notes nicht.

### Tastenkürzel für die Schnellerfassung

- **Cinnamon:** Systemeinstellungen → Tastatur → Tastenkürzel → Benutzerdefinierte Tastenkürzel → *Benutzerdefiniertes Tastenkürzel hinzufügen*: Name „Notes“, Befehl `notes --capture`, Kürzel z. B. `Super+N`.
- **KDE Plasma:** Systemeinstellungen → Tastatur → Kurzbefehle → *Neu hinzufügen* → *Befehl oder Skript*: `notes --capture`, Kürzel zuweisen.

### Post-its auf dem Desktop

Im Hauptfenster am Post-it ⋮ → *Auf den Desktop legen*. Beim Überfahren erscheint oben eine Leiste: Farbe, Ebene (normal → immer oben → unten), im Hauptfenster öffnen, vom Desktop nehmen. Fenster lassen sich an der oberen Kante verschieben und an der rechten unteren Ecke vergrößern. Position, Größe und Ebene werden pro Kontext gerätelokal gespeichert.

Fensterklasse der Post-its: `notes-sticky`. Für KDE Plasma liegt eine importierbare KWin-Regel bei: `docs/kwin/notes.kwinrule` (Systemeinstellungen → Fensterverwaltung → Fensterregeln → *Importieren*). Ergebnisse des Fenster-Spikes: `docs/desktop-spike.md`.

## Android-Release & Updates (Obtainium)

### Keystore einmalig erzeugen und sichern

```bash
keytool -genkeypair -v -keystore notes-release.jks -alias notes -keyalg RSA -keysize 4096 -validity 36500
```

Den Keystore und die Passwörter **sicher aufbewahren** (z. B. im Passwortmanager), aber nie ins Repo legen. Geht der Keystore verloren, lassen sich keine Updates mehr über die installierte App spielen.

GitHub-Secrets setzen:

```bash
gh secret set NOTES_KEYSTORE_BASE64 < <(base64 -w0 notes-release.jks)
```

```bash
gh secret set NOTES_KEYSTORE_PASSWORD
```

```bash
gh secret set NOTES_KEY_ALIAS --body notes
```

```bash
gh secret set NOTES_KEY_PASSWORD
```

### Release bauen

```bash
git tag clients-v1.0.0 && git push origin clients-v1.0.0
```

Die Action baut eine signierte Release-APK (R8), `versionName` kommt aus dem Tag, `versionCode` = `major·1 000 000 + minor·1 000 + patch`, und hängt `notes-<version>.apk` an ein GitHub Release.

### In Obtainium hinzufügen

1. Obtainium → *App hinzufügen* → URL `https://github.com/welles/notes-app`.
2. Unter *Filter für APK-Dateien (Regex)*: `notes-.*\.apk`.
3. Da im Repo auch Server-Releases liegen können: *Release-Titel filtern* mit `Android` bzw. Tag-Filter `^clients-v`.
4. Installieren. Spätere Tags erscheinen als Update; die Daten bleiben erhalten (gleicher Schlüssel, steigender `versionCode`).

## Konfiguration (Server)

| Variable | Default | Bedeutung |
|---|---|---|
| `ConnectionStrings__Default` | `Data Source=/data/notes.db` | SQLite-Datei |
| `CloudflareAccess__Enabled` | `true` | JWT-Prüfung von Cloudflare Access (nur lokal abschalten) |
| `CloudflareAccess__TeamDomain` | – | z. B. `meinteam.cloudflareaccess.com` |
| `CloudflareAccess__Audience` | – | AUD-Tag der Access-Application |
| `Notes__DataProtectionKeysPath` | `/data/keys` | Data-Protection-Schlüssel (überleben Neustarts) |
| `Notes__MinClientVersion__android` / `__desktop` | – | ältere Clients bekommen den Hinweis „Update empfohlen“ |
| `ASPNETCORE_HTTP_PORTS` | `8080` (Image) | Port im Container |

EF-Migrationen laufen beim Start automatisch. Health-Check: `GET /healthz` (ohne JWT).

## Betrieb mit Docker

Das Image liegt unter `ghcr.io/welles/notes` (Tags `latest` und Versionsnummer, gebaut bei Git-Tags `server-v*`).
Es läuft als Non-Root-User, Daten liegen im Volume `/data`.

```bash
cp docker-compose.example.yml docker-compose.yml   # .env mit TUNNEL_TOKEN, CF_TEAM_DOMAIN, CF_AUDIENCE anlegen
docker compose up -d
```

Notes veröffentlicht **keinen Port**; erreichbar ist es nur über `cloudflared` im selben Docker-Netzwerk.

Lokaler Test ohne Cloudflare:

```bash
docker build -t notes:local .
docker run --rm -p 127.0.0.1:8080:8080 -e CloudflareAccess__Enabled=false -v notes-data:/data notes:local
```

### Backup

SQLite im laufenden Betrieb sichern (konsistent, ohne Stopp):

```bash
docker compose exec notes sh -c 'dotnet Notes.Server.dll --backup /data/backup-$(date +%F).db'
```

`--backup` nutzt `VACUUM INTO` und schreibt eine vollständige Kopie, während der Server weiterläuft. Ohne Docker: `scripts/backup.sh <ziel.db>` (braucht `sqlite3`).
Wiederherstellen: Container stoppen, Datei als `/data/notes.db` zurückkopieren (alte `-wal`/`-shm` löschen), starten.

## Cloudflare Zero Trust einrichten

Notes hat keine eigenen Benutzerkonten. Cloudflare Access lässt nur mich (Browser, Google-Login) und meine Geräte (Service-Tokens) durch; der Server prüft zusätzlich das JWT, das Cloudflare an jeden Request hängt.

1. **Google als Identity Provider**: Zero Trust → *Settings → Authentication → Login methods → Add new → Google*. Client-ID/Secret aus einem Google-Cloud-OAuth-Client (Typ „Web“, Redirect-URI `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`).
2. **Tunnel**: *Networks → Tunnels → Create a tunnel* (cloudflared). Token kopieren (→ `TUNNEL_TOKEN`). Public Hostname: `notes.welles.app` → Service `http://notes:8080`.
3. **Access-Application**: *Access → Applications → Add an application → Self-hosted*, Domain `notes.welles.app`.
4. **Policy „Allow“**: Action *Allow*, Include → *Emails* → meine Google-Adresse; Login-Methode Google.
5. **Service-Token** (am besten einer pro Gerät): *Access → Service Auth → Service Tokens → Create*. Client-ID und Client-Secret notieren – das Secret wird nur einmal angezeigt.
6. **Policy „Service Auth“** in derselben Application: Action *Service Auth*, Include → *Service Token* → die angelegten Tokens.
7. **AUD-Tag**: in der Application unter *Overview* („Application Audience (AUD) Tag“) → `CF_AUDIENCE`.
8. **Team-Domain**: *Settings → Custom Pages* bzw. oben im Dashboard, Form `<team>.cloudflareaccess.com` → `CF_TEAM_DOMAIN`.

Widerrufen oder Rotieren eines Geräts: Service-Token im Dashboard löschen bzw. erneuern.

## Export

Alle Daten aller Module als JSON: Download-Symbol in der Web-App bzw. `GET /api/export`.

## Neues Modul

Checkliste: [`docs/adding-a-module.md`](docs/adding-a-module.md).

## CI

- `.github/workflows/server.yml`: Build + Tests bei jedem Push; prüft, dass `api/openapi.json` aktuell ist; `scripts/check-api-compat.sh` vergleicht die Spec mit dem letzten `server-v*`-Tag (`oasdiff breaking`) und stellt sicher, dass veröffentlichte Fixtures nicht verändert wurden. Bei Tags `server-v*` wird das Image nach GHCR gepusht.

- `.github/workflows/clients.yml`: Client-Tests, Android-/Desktop-Build, Nix-Paket (`nix build .#notes-desktop`); bei Tags `clients-v*` signierte Release-APK.
- `.github/workflows/compat.yml`: echte Client-Sync-Engine gegen echten Server über Versionsgrenzen (`scripts/compat-e2e.sh`): aktuell↔aktuell, letzter `clients-v*`-Client gegen aktuellen Server, aktueller Client gegen letzten `server-v*`-Server. Lokal:

```bash
nix develop .#android --command scripts/compat-e2e.sh current current
```

Release des Servers:

```bash
git tag server-v1.0.0 && git push origin server-v1.0.0
```
