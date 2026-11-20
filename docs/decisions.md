# Entscheidungslog

Kurz und knapp, neueste unten.

## Schritt 1 – Core & Post-its (Server)

- **Alle Schreibzugriffe laufen über `SyncService.PushAsync`**, auch die des Webs (`PushOrigin.Local`). Damit gibt es genau einen Pfad für Versionierung, Konfliktlogik, Kontext-Regeln und Live-Events.
- **Versionsvergabe zentral in `NotesDbContext.SaveChangesAsync`**: jede geänderte `SyncEntity` bekommt eine neue Version aus `sync_state.LastVersion` (Block-Reservierung per `UPDATE … RETURNING`), `sync_changes` wird in derselben Transaktion upserted. Kein Modul muss daran denken.
- **SQLite-Schreibzugriffe werden prozessintern serialisiert** (Semaphore im `SyncService`). SQLite hat ohnehin nur einen Writer; so gibt es kein `SQLITE_BUSY` und die Versionen werden in Commit-Reihenfolge sichtbar (wichtig für den Pull-Cursor).
- **Idempotenz über `sync_push_log`** (entityType, id, modifiedAt → Ergebnis). Ein wiederholter Push liefert das gespeicherte Ergebnis plus aktuellen Serverzustand, ohne erneut anzuwenden – auch keine zweite Konfliktkopie. Log-Einträge werden wie Tombstones nach 90 Tagen gelöscht. Abgelehnte Einträge werden nicht geloggt (sie sollen später erneut versucht werden können).
- **Lokale (Web-)Änderungen sind nicht idempotent, sondern streng monoton**: `modifiedAt` wird bei Bedarf auf „letzte Änderung + 1 ms“ angehoben, damit schnelle Edits in derselben Millisekunde nicht als Duplikat gelten.
- **Zeitstempel**: UTC, auf Millisekunden gekürzt, als ISO-8601 (`…T12:00:00.000Z`) im JSON.
- **Konflikt-Gleichstand** (`modifiedAt` identisch): Server gewinnt (deterministisch).
- **Konfliktkopie nur bei inhaltlichem Unterschied**: Post-its nur, wenn Titel oder Text abweichen; Farbe/Pin/Position sind reines LWW. Ohne Titel heißt die Kopie „Konflikt“, sonst „Konflikt: <Titel>“. Kontexte: nie Kopie (Spec).
- **Unbekannte Felder bleiben auch auf dem Server erhalten** (`ExtraJson` pro Entität, wird beim Pull wieder eingemischt). Sonst würde ein älterer Server Felder eines neueren Clients beim Zurückspielen verlieren. Lernt ein späterer Server ein solches Feld, holt seine Migration den Wert aus `ExtraJson`.
- **Enum-artige Felder (Farben) werden als String gespeichert**, damit unbekannte Werte unverändert durchlaufen; nur die Anzeige fällt auf den Standard zurück.
- **Push-Reihenfolge**: Core-Typen (Anlegen/Ändern) → Modul-Typen → Core-Löschungen. So klappen „Kontext offline anlegen + Inhalt“ und „alles verschieben + Kontext löschen“ jeweils in einem Push.
- **Kontext löschen** per Push, obwohl nicht leer (oder Standardkontext): Ergebnis `conflict` mit Grund `context_not_empty` / `default_context`, der Kontext bleibt bestehen.
- **Atomare Core-Aktionen** (Capture, Umwandeln, Verschieben) nutzen `PushAsync(atomic: true)`: Wird ein Eintrag nicht übernommen, wird alles zurückgerollt.
- **Standardkontext „Privat“** hat die feste UUID `019a0000-0000-7000-8000-000000000001` (per EF-Seed in der ersten Migration, Version 1).
- **Position**: aufsteigend sortiert, neue Post-its oben (`min − 1`). Beim Umsortieren Mittelwert der Nachbarn; ist die Lücke < 1e-6, werden alle Positionen des Kontexts in einem Push neu durchnummeriert.
- **DbContext per `IDbContextFactory`** (kurzlebig pro Operation), Services sind Singletons – passt zu Blazor-Server-Circuits.
- **Tests: xUnit v3 auf Microsoft.Testing.Platform** (`server/global.json`), da VSTest mit .NET 10 SDK + xUnit v3 nicht mehr unterstützt wird.
- **Repo-Struktur**: `clients/` und `api/` werden erst in den jeweiligen Schritten angelegt (keine leeren Platzhalter).

## Schritt 2 – Sync-API & Cloudflare Access

- **„full resync required“ ist ein Flag in der Pull-Antwort** (`fullResyncRequired: true`, HTTP 200), kein eigener Statuscode – einfacher für die Clients.
- **Push-Body ist `{ "changes": [...] }`** statt eines nackten Arrays, damit später weitere Felder additiv dazukommen können.
- **JSON-Konventionen:** camelCase, Enums kleingeschrieben (`accepted`, `conflict`, `rejected`, `client`, `server`), Zahlen strikt (keine Zahlen als Strings) – damit ist auch das OpenAPI-Schema sauber.
- **`data` bleibt im Schema ein freies Objekt**; die Form je Typ ist als eigene Komponente beschrieben (`ContextData`, `StickyData`). Die Liste steht in `OpenApiSetup.DataSchemas` und muss bei einem neuen Modul ergänzt werden.
- **`Notes-Protocol` fehlt → erlaubt** (Browser, curl). Nur ein gesetzter, nicht unterstützter Wert führt zu 426.
- **`capabilities`**: `sync.v1`, `contexts`, je Modul dessen Key, `convert` erst bei mehr als einem Capture-Ziel.
- **`minClientVersion`** kommt aus der Konfiguration `Notes__MinClientVersion__android` / `__desktop`.
- **JWKS von Cloudflare** wird 1 h gecacht; bei unbekannter `kid` (Schlüsselrotation) wird höchstens einmal pro Minute neu geladen.
- **Build-Zeit-OpenAPI** (`Microsoft.Extensions.ApiDescription.Server`) führt `Program.cs` aus; Konfigurationsprüfung und Migration werden dabei übersprungen. Ein Test prüft, dass `api/openapi.json` zum laufenden Server passt.
- **Fixtures** liegen unter `api/fixtures/<entityType>/` bzw. `_future/`; Format siehe `api/fixtures/README.md`.

## Schritt 3 – Web

- **Aktiver Kontext pro Browser als Cookie `notes-context`**: so kennt schon das Prerendering den Kontext. Fehlt das Cookie, fragt ein Dialog einmalig.
- **`ActiveContext` (scoped pro Blazor-Circuit), `NoteColors` und `MoveToContextDialog` liegen in `Notes.Core`** (Razor-SDK), damit Module sie nutzen können, ohne einander zu kennen.
- **`IModule.Navigation`** liefert den Navigationseintrag; ein Drawer erscheint erst ab zwei Modulen. `/` leitet auf das erste Modul um.
- **Schnellerfassung**: Enter wird im Browser abgefangen (Text lesen + Feld sofort leeren), damit schnelles Tippen nie zwei Notizen vermischt. `N` fokussiert das Feld.
- **Board** mit zwei Drop-Zonen „Angepinnt“/„Weitere“: Ziehen zwischen den Zonen pinnt/löst, innerhalb wird per Mittelwert sortiert.
- **Post-it-Farben** sind Pastelltöne mit immer dunklem Text, auch im Dark Mode. Die Akzentfarbe des Kontexts erscheint dezent als Linie unter der App-Bar.
- **Keine externen Fonts/CDNs** (System-Schrift), alles kommt vom eigenen Server.
