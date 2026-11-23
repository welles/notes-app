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
- **Image-Tags**: Release-Tag `vN` → `N` + `latest` (siehe „Versionen“ unten). Bei normalen Pushes wird nur gebaut (kein Push), damit das Dockerfile nicht unbemerkt kaputtgeht.
- **Kompatibilitäts-Check** als Skript (`scripts/check-api-compat.sh`), lokal und in CI gleich: `oasdiff breaking --fail-on ERR` gegen den letzten Release-Tag `v*` (oasdiff via Docker-Image), plus „Fixtures nur hinzufügen“.
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
- **Hintergrund pro Widget** (`widget.background.<appWidgetId>`, ebenfalls gerätelokal): deckend, transparent mit heller oder transparent mit dunkler Schrift. Zwei Varianten statt einer, weil Glance keinen Textschatten kann und die Helligkeit des Hintergrundbilds nichts mit dem Dark Mode zu tun hat. Beim Übersichts-Widget verschwindet nur der Rahmen, die Post-its behalten ihre Papierfarbe; beim Einzel-Widget steht der Text direkt auf dem Hintergrundbild, ein schmaler Streifen in der Post-it-Farbe bleibt.
- **Konfiguration**: Das Einzel-Widget zeigt beim Bearbeiten das aktuelle Post-it markiert und „Fertig“ (nur Hintergrund ändern). Beim Übersichts-Widget ist sie optional (`configuration_optional`, ab Android 12); darunter startet der Launcher sie beim Platzieren immer, daher setzt sie sofort `RESULT_OK`, sonst entfernte „Zurück“ das Widget. Beide Activities sind exportiert und prüfen, dass die übergebene Widget-ID zu ihrem Provider gehört.
- **Aufräumen**: Beim Entfernen eines Widgets werden seine Einstellungen gelöscht (`GlanceAppWidget.onDelete`), beim Start zusätzlich alle `widget.*`-Einstellungen ohne existierendes Widget – verpasste Löschungen und aus dem Backup eines anderen Geräts übernommene IDs, die sonst für fremde neue Widgets gälten.
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

## Schritt 10 – Feinschliff

- **Export** über `GET /api/export` (alle Typen über den generischen Pull, nur nicht gelöschte Entitäten, gruppiert nach Typ) – kein modulspezifischer Code; Download-Symbol in der App-Bar.
- **Kompatibilität über Versionen**: `RealServerSyncTest` (echte Sync-Engine, echter Server, nur mit `-Pnotes.e2eServer`) + `scripts/compat-e2e.sh` für beliebige Ref-Paare (Git-Worktrees). Ältere Client-Tags ohne diesen Test werden übersprungen.

## Live-Sync (nach Rücksprache ergänzt)

- **Server-Sent Events statt WebSocket**: nur Server→Client nötig, läuft als normales HTTP durch Cloudflare, einfach in Ktor und ASP.NET Core.
- **Der Stream trägt keine Daten**, nur „neueste Version = N“ (beim Verbinden sofort, danach bei jeder Änderung). Der Client synchronisiert über den normalen Pull, wenn N über seinem Cursor liegt – damit gelten dieselben Regeln, und eigene Pushes lösen keinen zweiten Sync aus.
- **Heartbeat alle 25 s** (SSE-Kommentar), weil Cloudflare inaktive Verbindungen nach ~100 s trennt; der Client erkennt tote Verbindungen über einen Socket-Timeout von 90 s.
- **Beim Herunterfahren beendet der Server die Streams sofort** (`ApplicationStopping`); sonst hielt Kestrel sie bis zum Shutdown-Timeout offen und die Clients hingen am alten Prozess.
- **Wiederverbinden mit Backoff 1 s → max. 10 s.** Gemessen: erste Änderung nach ~11 s Serverausfall nach ~4 s zugestellt; mit 60 s Maximum waren es 12–16 s.
- **Sync-Anstöße werden zusammengefasst** (während ein Sync läuft, höchstens einer in der Warteschlange).
- **Android nur im Vordergrund** (ProcessLifecycleOwner start/stop), Hintergrund weiter über WorkManager. **Desktop immer**, weil die Post-it-Fenster ständig sichtbar sind.
- **Fähigkeit `sync.events` in `meta`**: Clients verbinden sich nur, wenn der Server sie meldet – ältere Server bleiben beim Polling.
- **Nicht gemessen**: Latenz und Verhalten über den echten Cloudflare-Tunnel (würde eine öffentliche Test-URL erfordern).

## Versionen

- **Eine Version für alles, fortlaufend gezählt**: Server, Android und Desktop aus demselben Commit tragen dieselbe Nummer N (`VERSION`, Tag `vN`), statt getrennter `server-v*`/`clients-v*`-Tags mit Semantic Versioning (Abweichung von der Spec). Kompatibilität hängt an Protokoll und Fähigkeiten, nicht an der Nummer; jedes Release enthält beide Seiten, auch wenn sich nur eine geändert hat.
- **Wo drei Stellen nötig sind**: .NET-Assembly-Version und jpackage bekommen `N.0.0`; Android nimmt N direkt als `versionCode`.
- **Nur Releases werden installiert**: Image-Tags nur bei `vN`, APKs nur als GitHub Release, die Desktop-Flake über den Branch `release`, den nur die Release-Action vorschiebt.
- **Release per Workflow** (`release.yml`, manuell gestartet) statt Tag-Push oder lokalem Skript: Tests, signierte APK und Image-Build laufen vorher; Tag, Image-Push, GitHub Release und Branch `release` entstehen erst, wenn alles grün ist.

## Post-its: kein Titel, Markdown, Post-it-Look (Issues #1–#3)

- **Kein Titel mehr, das Feld `title` bleibt im Protokoll**: Aktuelle Versionen schreiben `null`; ein vorhandener Titel (alte Daten, ältere Clients) wird überall als `# Titel` über dem Text angezeigt und beim nächsten Bearbeiten des Texts in `content` übernommen (`StickyText` auf Server und Client). Keine Datenmigration: Sie hätte Versionen hochzählen und alle Geräte neu laden lassen müssen, und ältere Clients schreiben weiter Titel.
- **Schnellerfassung/Teilen mit Titel** (z. B. `EXTRA_SUBJECT`) wird zur Überschrift im Text. **Umwandeln** gibt eine führende `# `-Überschrift wieder als Titel an das Zielmodul.
- **Konfliktkopie**: Ohne Titel landet die Markierung in der ersten Überschrift (`# Konflikt: …`, sonst wird `# Konflikt` vorangestellt). Post-its mit altem Titel behalten das bisherige Verhalten (`Konflikt: <Titel>` im Titel), damit ältere Clients es sehen.
- **Eigener, kleiner Markdown-Parser statt Bibliothek**, je einer in C# (`Notes.Core/Web/Markdown.cs`) und Kotlin (`core/model/Markdown.kt`), mit gemeinsamen Testfällen in `api/markdown-cases.json`. Grund: Web, Compose, Glance-Widgets und der Live-Editor sollen exakt dieselbe Teilmenge sehen, und der Editor braucht Quellpositionen. Umfang: Überschriften, Listen, Aufgaben `- [ ]`, Trennlinie, fett/kursiv/durchgestrichen, Code, Links (nur http/https/mailto). Blöcke sind einzelne Zeilen – Zeilenumbrüche bleiben wie getippt, wie bisher beim Klartext. HTML wird nie durchgereicht.
- **Editor zeigt Markdown-Quelltext mit Live-Hervorhebung** (Zeichen bleiben sichtbar, aber blass; Überschriften größer), damit Cursor und Text nie springen. Glance kann nur Strings: Widgets zeigen eine führende Überschrift fett, den Rest als Klartext.
- **Anlegen und Bearbeiten im selben Post-it-Dialog** (Clients): Der „+“-Knopf öffnet bei Modulen mit eigenem `createRoute` dessen Dialog statt der generischen Schnellerfassung, ebenso das „+“ des Widgets. Der Dialog liegt über der Übersicht; aus der Übersicht geöffnet als lokaler Zustand (Scroll-Position bleibt), von außen (Widget, Desktop-Fenster) über die Route `stickies/edit`. Schließen (Zurück, daneben tippen, ✓) speichert.
- **Post-it-Look** auf Web und Clients gleich: fast eckiges Papier, angedeuteter Klebestreifen oben, leichter Lichtverlauf, abgeknickte Ecke unten rechts (Umriss als Form, damit der Schatten der Ecke folgt). Keine Drehung, damit das Raster ruhig bleibt. Desktop-Fenster sind rechteckig und bekommen nur die Papier-Schattierung.

## Update aus der App (Android)

- **Quelle sind die GitHub Releases** (`releases/latest`, öffentliches Repo, ohne Token): Release `vN` mit Asset `notes-N.apk`; neuer als installiert, wenn N > `versionCode`. Kein eigener Update-Endpunkt am Server, weil die APK ohnehin nur dort liegt. Entwürfe und Pre-Releases zählen nicht.
- **Prüfungen vor der Installation**: Größe, SHA-256 aus dem `digest` des Assets (falls vorhanden) und Paketname der heruntergeladenen APK. Die Signatur prüft Android selbst – ein Update mit anderem Schlüssel wird abgelehnt und als „Signatur passt nicht“ angezeigt.
- **`PackageInstaller`-Session statt `ACTION_VIEW` + FileProvider**: kein Provider nötig, Ergebnis kommt als Status zurück, und ab Android 12 kann `USER_ACTION_NOT_REQUIRED` die Bestätigung sparen, wenn Notes selbst Installer der laufenden Version ist (beim ersten Update aus Obtainium/adb fragt Android trotzdem).
- **Prüfen automatisch, Installieren auf Knopfdruck**: Die Suche läuft beim Wechsel in den Vordergrund (höchstens stündlich, GitHub erlaubt 60 Anfragen/h ohne Token); der Download startet erst nach „Aktualisieren“, die Installation dann sofort. Ein Installationsdialog mitten beim Schreiben wäre störend, und bestätigen muss man ohnehin.
- **Berechtigung „Unbekannte Apps installieren“**: Fehlt sie, öffnet „Aktualisieren“ die Systemeinstellung; beim Zurückkehren geht es weiter, wenn sie erteilt wurde – sonst nicht, auch nicht später von selbst.
- **Ohne Bestätigung ab Android 12** braucht es zusätzlich `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (normale Berechtigung) und `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`; dann beendet Android die App nach dem Update einfach.
- **Bestätigung, die nicht erscheint** (Download endete im Hintergrund, Android blockiert dann den Start des Dialogs; oder Dialog mit Home verlassen): Die Session bleibt offen, der Dialog wird beim nächsten Wechsel in den Vordergrund erneut gezeigt, und „Fortsetzen“ zeigt ihn von Hand bzw. gibt eine Session auf, die verschwunden ist oder 2 min nach dem Übergeben weder Dialog noch Ergebnis geliefert hat (vorher könnte sie noch ohne Bestätigung installieren). Status und Dialoge werden über die Session-ID zugeordnet, Nachzügler einer aufgegebenen Session ignoriert.
- **„Update nötig“** (Datenbank einer neueren Version) zeigt den Update-Abschnitt, und die Suche läuft unabhängig von der Datenbank – genau dann muss man aktualisieren.
- **Fehlgeschlagene Suche** (z. B. offline) zählt nicht für das Stunden-Intervall, behält ein bekanntes Update und zeigt nur bei der Suche von Hand einen Fehler.
- **Nur Release-Builds**: Debug-Builds sind anders signiert, ein Update würde immer an der Signatur scheitern. Desktop aktualisiert über die Flake (Branch `release`).
- **Nicht auf einem Gerät erprobt** (kein Emulator in der Entwicklungsumgebung): geprüft sind Abfrage/Parsing gegen die echte API, Kompilierung, R8-Release-Build und Lint.
