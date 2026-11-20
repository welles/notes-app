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

## Konfiguration (Server)

| Variable | Default | Bedeutung |
|---|---|---|
| `ConnectionStrings__Default` | `Data Source=/data/notes.db` | SQLite-Datei |
| `ASPNETCORE_URLS` | – | Listen-Adresse |

EF-Migrationen laufen beim Start automatisch. Weitere Variablen (Cloudflare Access) kommen mit Schritt 2.
