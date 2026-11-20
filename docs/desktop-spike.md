# Spike: Post-its als Desktop-Fenster (Schritt 9a)

Umsetzung: randlose `ComposeWindow`s; EWMH-Hinweise per JNA/Xlib **vor** dem Mappen gesetzt (`_NET_WM_STATE`, `_NET_WM_DESKTOP`, `WM_CLASS`/`WM_WINDOW_ROLE` = `notes-sticky`), Ebenen-Wechsel zur Laufzeit per Client-Message an das Root-Fenster.

## Ergebnis Cinnamon (Muffin 6.6, X11)

Getestet mit `muffin --x11` (gleiche Version wie in nixpkgs) in einem virtuellen X-Display, ausgewertet per `xprop`/`xdotool`.

| Funktion | Ergebnis |
|---|---|
| Position/Größe setzen und nach Neustart wiederherstellen | geht direkt |
| Ebene „immer oben“ | geht direkt (`_NET_WM_STATE_ABOVE`) |
| Ebene „auf dem Desktop (unten)“ | geht; Muffin übernimmt `BELOW` aber nur per Client-Message nach dem Mappen, nicht aus der Property davor → wird nach dem Anzeigen gesetzt |
| Kein Taskleisten-Eintrag | geht direkt (Skip-Taskbar/Pager) |
| Auf allen Arbeitsflächen | geht direkt (`_NET_WM_STATE_STICKY`, `_NET_WM_DESKTOP = 0xFFFFFFFF`) |
| Fensterklasse `notes-sticky` | gesetzt |

## KDE Plasma 6 (Wayland, Notes über XWayland)

**Nicht getestet** – in dieser Umgebung gab es kein KWin. KWin respektiert dieselben EWMH-Hinweise bei XWayland-Fenstern in der Regel. Was nicht greift, erzwingt die mitgelieferte KWin-Regel `docs/kwin/notes.kwinrule` (Klasse `notes-sticky`: keine Taskleiste/Pager/Switcher, alle Arbeitsflächen, randlos). Für einzelne Post-it-Positionen gibt es keine KWin-Regel (alle teilen eine Klasse); falls KWin die gespeicherten Positionen ignoriert, bitte melden.

Bitte auf KDE prüfen: Position nach Neustart, Ebene „unten“, kein Eintrag in der Taskleiste, alle Arbeitsflächen.

## Nicht möglich

„Unter allen Fenstern wie ein Widget“ im Sinne von `layer-shell` (natives Wayland) – AWT/Compose können das nicht. „Unten“ ist die normale EWMH-Ebene unter allen regulären Fenstern.
