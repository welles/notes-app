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
