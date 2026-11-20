# Notes

Persönlicher Ort für alles, was ich mir im Laufe des Tages aufschreibe. Läuft nur für mich auf meinem Server (`https://notes.welles.app`) und meinen Geräten.

v1 enthält das Modul **Post-its** plus Core (Kontexte, generischer Sync, Schnellerfassung). Weitere Module (To-dos, Wissensdatenbank, KI) kommen später und docken an denselben Core an.

| Teil | Technik | Status |
|---|---|---|
| Server + Web | .NET 10, ASP.NET Core, Blazor, EF Core + SQLite | Core & Post-its (Service-Schicht) |
| Android + Desktop | Kotlin Multiplatform, Compose Multiplatform | folgt (Schritt 5 ff.) |

## Struktur

```
server/
  Notes.Core/              Sync, Modul-Registrierung, Capture & Aktionen, Kontexte, Event-Bus
  Notes.Modules.Stickies/  Modul Post-its (Entität, Service, Sync-Handler, Capture-Ziel)
  Notes.Server/            Host, Komposition der Module, EF-Migrationen
  Notes.Server.Tests/      xUnit v3
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

## CI

- `.github/workflows/server.yml`: Build + Tests bei jedem Push; prüft, dass `api/openapi.json` aktuell ist; `scripts/check-api-compat.sh` vergleicht die Spec mit dem letzten `server-v*`-Tag (`oasdiff breaking`) und stellt sicher, dass veröffentlichte Fixtures nicht verändert wurden. Bei Tags `server-v*` wird das Image nach GHCR gepusht.

Release des Servers:

```bash
git tag server-v1.0.0 && git push origin server-v1.0.0
```
