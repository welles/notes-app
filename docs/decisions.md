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

## Schritt 4 – Docker & CI

- **Healthcheck ohne curl im Image**: `dotnet Notes.Server.dll --healthcheck` ruft `/healthz` lokal auf. Hält das Runtime-Image klein und ohne Zusatzpakete.
- **Backup** gleich mitgebaut: `--backup <datei>` macht `VACUUM INTO` im laufenden Betrieb (eigentlich Schritt 10).
- **Image-Tags**: `server-vX.Y.Z` → `X.Y.Z` + `latest`. Bei normalen Pushes wird nur gebaut (kein Push), damit das Dockerfile nicht unbemerkt kaputtgeht.
- **Kompatibilitäts-Check** als Skript (`scripts/check-api-compat.sh`), lokal und in CI gleich: `oasdiff breaking --fail-on ERR` gegen den letzten `server-v*`-Tag (oasdiff via Docker-Image), plus „Fixtures nur hinzufügen“.
- **Owner im Image-Namen** kommt in CI aus `github.repository_owner`; README/Compose nennen `ghcr.io/welles/notes`.

## Schritt 5 – Clients (KMP)

- **Versionen (Nov. 2026):** Kotlin 2.4, AGP 9.5, Compose Multiplatform 1.12, Room 2.8, Ktor 3.6, Koin 4.2, Gradle 9.9. Gemeinsame Module nutzen das AGP-9-Plugin `com.android.kotlin.multiplatform.library`; `androidApp` ist ein eigenes Modul (AGP 9 erlaubt keine App + KMP in einem Modul).
- **compileSdk 37** (Compose 1.12 verlangt es), minSdk 29, targetSdk 37.
- **Zusätzliches Modul `:app`** (gemeinsame Komposition): Die Room-`@Database` muss alle Entities aller Module kennen und gehört deshalb in ein Modul, das alle Features sieht. Dort liegen auch Koin-Komposition und Root-Composable; `androidApp`/`desktopApp` sind nur noch Einstiegspunkte.
- **DAOs der Features** bekommen die Module per Koin aus der Datenbank; Features kennen `NotesDatabase` nicht.
- **Jede lokale Änderung ist ein JSON-Patch** auf das Roh-JSON (`rawData`) der Entität (`LocalStore`). Spalten in Room sind nur eine Projektion fürs Abfragen/Anzeigen. So überleben Felder neuerer Versionen jede Bearbeitung, und „In Kontext verschieben“ funktioniert generisch für jeden Typ.
- **Outbox** pro Entität eine Zeile (zusammengefasst), `baseVersion` der ersten ungesyncten Änderung bleibt erhalten; `batchId` hält zusammengehörige Änderungen in einem Push.
- **Standardkontext lokal** mit fester UUID und Version 0 angelegt, aber nicht in die Outbox geschrieben; der Pull überschreibt ihn.
- **Aktiver Kontext** in der lokalen Tabelle `core_local_setting` (nie synchronisiert). Ist er gelöscht, Fallback auf den Standardkontext plus Hinweis.
- **Eigene Mini-Navigation** (Back-Stack aus Routen-Strings + `BackHandler`) statt navigation-compose – Module registrieren ihre Screens über `FeatureRoutes`.
- **Neuere lokale DB** wird vor dem Öffnen per `PRAGMA user_version` erkannt; die App zeigt dann einen Hinweis statt abzustürzen oder Daten zu verwerfen.
- **Share-Target** legt das Post-it direkt an (Toast), ohne die App zu öffnen; `EXTRA_SUBJECT` wird zum Titel.
- **Sortieren auf den Clients** per Long-Press-Drag innerhalb der Abschnitte „Angepinnt“/„Weitere“ (`sh.calvin.reorderable`); Anpinnen über den Pin-Button.
- **Desktop-Dark-Mode**: Compose erkennt ihn unter Linux nicht selbst; Notes liest `GTK_THEME`, `gsettings` (GNOME/Cinnamon) bzw. `kdeglobals` (KDE). `NOTES_DARK=1` erzwingt ihn.
- **Backup (Android)**: Datenbank darf ins Backup, Zugangsdaten nicht (Ausschluss in `backup_rules.xml`/`data_extraction_rules.xml`).

## Schritt 6 – Client-Sync

- **Hand-geschriebene DTOs** in `core/model` (statt Generator, OpenAPI 3.1). `ApiContractTest` prüft Feldnamen und Enum-Werte gegen `api/openapi.json`.
- **Engine-Tests laufen im `desktopTest` von `:app`** (JVM), nicht in `commonTest`: Sie brauchen die echte Room-Datenbank in-memory, und die gibt es ohne Android-Context nur auf JVM/Native. Die getestete Logik ist trotzdem der gemeinsame Code. Fixtures werden direkt aus `api/fixtures` gelesen.
- **Push-Reihenfolge**: Batches mit Kontexten zuerst, dann nach Entstehungsreihenfolge; Batches werden nie über mehrere Pushes verteilt (max. 200 Einträge pro Push).
- **Typen, die der Server laut `meta` nicht kennt**, werden gar nicht erst gesendet, sondern direkt als „wartet auf Server-Update“ markiert.
- **Während eines Pushs lokal geändert** → Outbox-Eintrag bleibt und wird auf die neue Server-Version „rebased“, damit der nächste Push nicht mit sich selbst kollidiert.
- **Full Resync nach Client-Update**: Die Engine merkt sich die beim letzten Sync bekannten Typen; kennt das neue Update mehr, wird der Cursor auf 0 gesetzt.
- **Weiterleitungen werden nicht verfolgt**: Cloudflares Login-Redirect, 401/403 oder eine HTML-Antwort werden als „Zugangsdaten prüfen“ angezeigt, nicht als Parse-Fehler.
- **Offline** wird dezent (graues Wolken-Icon) angezeigt, echte Fehler rot.
- **QR-Code**: Google Code Scanner (Play-Dienste) statt CameraX + ML Kit – keine Kamera-Berechtigung, wenig Code. Fallback: JSON einfügen.
- **Desktop-Zugangsdaten über `secret-tool`** (libsecret-CLI) statt JNA-Bindings an libsecret: deutlich weniger Code; das Nix-Paket bringt `secret-tool` mit. Fallback-Datei 0600.
- **Debug-Builds** erlauben Klartext-HTTP nur zu `10.0.2.2`/`localhost` (eigenes Manifest-Overlay unter `src/debug`).
- **Erprobt**: Emulator offline bearbeitet + Web gleichzeitig geändert → nach Reconnect gewinnt die neuere Änderung, die andere bleibt als „Konflikt: …“ erhalten; Desktop ↔ Handy synchronisieren in beide Richtungen.

## Schritt 7 – Android-Widgets

- **Glance im `androidMain` von `feature/stickies`** (Receiver, Manifest-Einträge und `appwidget-provider`-XML liegen im Modul; Android-Ressourcen im KMP-Modul per `androidResources.enable`).
- **Widget-Daten als Flows innerhalb von `provideContent`**: Glance hält nach dem Platzieren eine Session offen; vorher gelesene Daten würden bei `update()` nicht neu geladen.
- **Aktualisierung**: (1) ein Beobachter auf die Post-it-Tabelle und den aktiven Kontext (gedrosselt, 500 ms), (2) zusätzlich ein `SyncObserver`, der innerhalb des Syncs aktualisiert – auch im WorkManager-Job, bevor der Prozess beendet werden kann.
- **Einzel-Widget-Konfiguration** (welches Post-it) liegt gerätelokal in `core_local_setting` (`widget.sticky.<appWidgetId>`).
- **Widget öffnet die App über den Launch-Intent** mit Extras (`AppIntents`), damit das Feature-Modul `MainActivity` nicht kennen muss.
- **Erprobt**: Web-Änderung → periodischer WorkManager-Sync → Widget zeigt neue Farbe und neuen Text, ohne dass die App geöffnet war.

## Schritt 8 – Android-Release

- **`versionCode` aus dem Tag** (`major·1 000 000 + minor·1 000 + patch`) statt aus der Run-Nummer: deterministisch und mit der Version steigend, auch wenn ein Release neu gebaut wird.
- **Signing nur über Umgebungsvariablen** (`NOTES_KEYSTORE_FILE`, `…_PASSWORD`, `NOTES_KEY_ALIAS`, `NOTES_KEY_PASSWORD`); ohne sie bleibt der Release-Build unsigniert.
- **APK-Name `notes-<version>.apk`** für Obtainiums Filter; Release-Titel enthält „Android“, damit Server-Tags nicht stören.
- **Erprobt**: R8-Release 0.9.0 installiert, Daten angelegt, 0.9.1 als Update drüber installiert → Daten erhalten; Sync unter R8 funktioniert.

## Schritt 9 – Desktop

- **Spike-Ergebnis** in `docs/desktop-spike.md`: Unter Cinnamon (Muffin, X11) funktionieren Position/Größe, „immer oben“, „unten“, keine Taskleiste und alle Arbeitsflächen. KDE/Wayland konnte hier nicht getestet werden; dafür gibt es die KWin-Regel `docs/kwin/notes.kwinrule`. Da die Spike-Ergebnisse positiv waren, ist 9b im vollen Umfang umgesetzt.
- **Post-it-Fenster sind rohe `ComposeWindow`s** (nicht der `Window`-Composable), damit die EWMH-Hinweise per Xlib **vor** dem Mappen gesetzt werden können; die Ebene wird nach dem Mappen zusätzlich per Client-Message gesetzt (Muffin ignoriert `BELOW` aus der Property).
- **Hauptfenster bleibt in der Komposition** und wird beim Schließen nur versteckt – so läuft Notes im Hintergrund weiter. Beenden: Tray, `notes --quit`.
- **Fensterzustand pro Kontext** in `core_local_setting` (`desktop.stickyWindows.<contextId>`); beim Kontextwechsel werden die Fenster geschlossen bzw. wiederhergestellt.
- **Single-Instance** über einen Unix-Domain-Socket (Java 16+, keine Zusatzbibliothek), Argumente Base64-kodiert.
- **`--capture-text` ohne laufende Instanz** schreibt direkt in die Datenbank, versucht einen kurzen Sync (max. 15 s) und beendet sich.
- **Tray** nur, wenn AWT einen unterstützt (XEmbed, z. B. Cinnamon). KDE Plasma 6 kennt nur StatusNotifierItem → dort kein Tray-Icon; Notes ist dann über den Starter (öffnet das Hauptfenster der laufenden Instanz) bzw. `notes --quit` bedienbar.
- **WM_CLASS** des Hauptfensters per Reflection auf `notes` gesetzt (`--add-opens java.desktop/sun.awt.X11`), passend zu `StartupWMClass` der `.desktop`-Datei.
- **Nix-Paket aus dem Quellcode**: `-Pnotes.desktopOnly=true` baut ohne Android-Module/-SDK; `nixDistribution` sammelt alle Laufzeit-JARs (gleichnamige JARs verschiedener Gruppen werden mit Gruppen-Präfix abgelegt); Start über das JDK aus nixpkgs mit `makeWrapper`. Der Abhängigkeits-Lock wird mit dem echten Build aufgezeichnet (`gradleUpdateTask`), weil KSP seinen Prozessor erst zur Ausführungszeit auflöst; ohne Parallelität, da Maven Central bei vielen parallelen Downloads über den Proxy abbricht.
- **Nicht getestet**: KDE Plasma 6 / KWin; das Tray-Icon auf echtem Cinnamon (im Test-X-Server ohne Panel nicht prüfbar).

## UI-Texte

- **Keine Erklärsätze an UI-Elementen** (Wunsch des Nutzers): nur knappe Labels, Platzhalter, Titel und echte Status-/Fehlermeldungen. Wo etwas ohne Erklärung nicht verständlich ist, wird das Design verbessert statt ein Hinweis ergänzt.
