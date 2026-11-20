# Neues Modul hinzufügen

Checkliste am Beispiel eines Moduls `todos` mit dem Entitätstyp `todos.todo`. Referenz ist immer das Modul Post-its (`stickies`). Ein Modul hängt nur vom Core ab, nie von einem anderen Modul.

Vorher lesen: Kompatibilitätsregeln in der Spec, Abschnitt 5.2 (nur additiv, unbekannte Felder erhalten).

## Server (`server/`)

1. **Projekt** `Notes.Modules.Todos` (Razor Class Library, `Microsoft.NET.Sdk.Razor`) mit Referenz auf `Notes.Core`; in `Notes.slnx` und als Referenz in `Notes.Server.csproj` aufnehmen.
   Vorlage: `Notes.Modules.Stickies/Notes.Modules.Stickies.csproj`.
2. **Entität** `Todo : ContextScopedEntity` mit `[SyncEntityType("todos.todo")]`; Aufzählungswerte als `string` speichern.
   Vorlage: `Sticky.cs`.
3. **Tabelle** mit Präfix (`todos_todo`) per `IEntityTypeConfiguration<Todo>`.
   Vorlage: `StickyConfiguration`.
4. **Sync-Handler** `TodoSyncHandler : SyncHandler<Todo>`: `Fields`, `Write`, `Parse` (erst alles validieren, dann zuweisen), optional `NeedsConflictCopy`/`PrepareConflictCopy`, `Export` (→ `CaptureContent`), `DataSchema`.
   Vorlage: `StickySyncHandler.cs`.
5. **Capture-Ziel** (falls aus Text etwas entstehen kann): `ICaptureTarget`. Ab dem zweiten Ziel erscheint „Umwandeln in …“ in Web und Clients automatisch.
   Vorlage: `StickyCaptureTarget.cs`.
6. **Service** für die Web-UI; alle Schreibzugriffe als Push über `SyncService.PushAsync(…, PushOrigin.Local)`.
   Vorlage: `StickyService.cs`.
7. **`IModule`** implementieren: `Key`, `DisplayName`, `Navigation` (Seite des Moduls), `ConfigureServices` (Handler, Capture-Ziel, Service), `ConfigureModel`.
   Vorlage: `StickiesModule.cs`.
8. **Komposition**: in `Notes.Server/NotesComposition.Modules()` eintragen.
9. **Web-UI**: Seite mit `@page "/todos"` im Modulprojekt; `ActiveContext` für den Kontext, `EventBus` für Live-Updates, `MoveToContextDialog` für „In Kontext verschieben …“.
   Vorlage: `StickiesPage.razor`.
10. **OpenAPI**: Data-Record (`TodoData`) anlegen und in `Notes.Server/Api/OpenApiSetup.DataSchemas` eintragen; Server bauen, `api/openapi.json` committen (nur additive Änderungen).
11. **Migration**: `dotnet ef migrations add AddTodos -p Notes.Server -o Data/Migrations`.
12. **Fixtures**: `api/fixtures/todos.todo/v1-basic.json` (+ Tombstone). Veröffentlichte Fixtures nie ändern.
13. **Tests**: Handler-Konflikte, Kontext-Filter (laufen über `ListInContextAsync` automatisch mit), Fixture-Roundtrip läuft automatisch.

## Clients (`clients/`)

1. **Gradle-Modul** `feature/todos` (Plugin `notes.kmp.compose`), in `settings.gradle.kts` aufnehmen.
   Vorlage: `feature/stickies/build.gradle.kts`.
2. **Room**: `TodoEntity` (Tabelle `todos_todo`, Spalten als Projektion plus `rawData`), `TodoDao` (u. a. `idsInContext`, `deleteAllExcept`).
   Vorlage: `feature/stickies/.../data/Sticky.kt`.
3. **`LocalEntityHandler`** `TodoHandler`: `put` liest Felder tolerant aus dem Roh-JSON und speichert es vollständig; `export` für „Umwandeln in …“.
4. **Repository**: alle Schreibzugriffe über `LocalStore.create/change/delete` bzw. `batch { }` (JSON-Patches, Outbox).
   Vorlage: `StickyRepository.kt`.
5. **Capture-Ziel** `CaptureTarget` (optional).
6. **UI**: `ClientFeature` (Key, Titel, Icon, Start-Route, `routes`) und Screens; ViewModels lesen den aktiven Kontext aus `ContextRepository.active`.
   Vorlage: `StickiesFeature.kt`, `ui/StickiesScreen.kt`. Ab zwei Features zeigt die Shell eine Navigationsleiste.
7. **Koin-Modul** `todosModule`: Handler (`bind LocalEntityHandler`), Repository, Capture-Ziel (`bind CaptureTarget`), `ClientFeature`, ViewModels.
   Vorlage: `stickiesModule`.
8. **Datenbank** (`app/.../NotesDatabase.kt`): `TodoEntity` in `entities`, DAO-Accessor, **`VERSION` erhöhen** und eine `Migration` (bzw. `AutoMigration`) ergänzen; das exportierte Schema unter `app/schemas/` committen. Ein älterer Client erkennt die neuere DB und meldet sie.
9. **Komposition**: DAO in `databaseModule`, `todosModule` in `notesModules(...)`; Modul-Abhängigkeit in `app/build.gradle.kts`.
10. **Sync**: nichts weiter zu tun. Nach dem Update kennt der Client einen Typ mehr und macht automatisch einen vollständigen Resync, damit vorher übersprungene `todos.todo`-Einträge ankommen.
11. **Android-Widgets** (optional) in `feature/todos/src/androidMain`, Receiver im Modul-Manifest, Updater als `SyncObserver` in einem Android-Koin-Modul (Vorlage: `stickiesAndroidModule`), in `NotesApplication.extraModules` registrieren.
12. **Tests**: `app/src/desktopTest` – Fixtures für den neuen Typ laufen über `ContractFixtureTest` automatisch mit.
13. **Nix-Lock** aktualisieren, falls neue Bibliotheken dazukommen: `nix build .#notes-desktop.mitmCache.updateScript -o update-deps && ./update-deps`.

## Modulübergreifend

- Verweise zwischen Modulen nur über `EntityRef` bzw. später den Core-Typ `core.link`, nie direkt.
- Neue Felder an bestehenden Entitäten: optional, mit Default, im Handler tolerant lesen; neue Fixture unter `api/fixtures/` ergänzen.
