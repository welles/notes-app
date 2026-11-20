# Contract-Fixtures

Beispiel-Envelopes (Pull-Format) je Entitätstyp. **Veröffentlichte Fixtures werden nie geändert, nur ergänzt** (CI prüft das).
Server-Tests (`Notes.Server.Tests/ContractFixtureTests`) und Client-Tests (`commonTest`) lesen alle Dateien hier.

Format:

```json
{
  "description": "…",
  "expect": "roundtrip | rejected_unknown_type",
  "envelope": { "entityType": "…", "id": "…", "version": 1, "deleted": false, "data": { … } }
}
```

- `roundtrip`: Nach Push (als neue Entität) und Pull muss `data` – abgesehen von `version` – unverändert zurückkommen, inklusive unbekannter Felder und Enum-Werte.
- `rejected_unknown_type`: Ein Server, der den Typ nicht kennt, lehnt den Eintrag mit `unknown_type` ab; Clients überspringen ihn beim Pull.
- `_future/`: Daten einer gedachten späteren Version (neue Felder, Enum-Werte, Typen).
