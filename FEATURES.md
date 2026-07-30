# Funktionsübersicht

Sammlung aller Funktionen des CPI Groovy Testers, nach Bereich sortiert.
Für Anleitungen siehe [README.md](README.md) / [README.de.md](README.de.md),
für Details zum Sicherheitsmodell [SECURITY.md](SECURITY.md).

## Kernidee

- Führt echte SAP-Cloud-Integration-Groovy-Scripte **unverändert** aus —
  inklusive Original-Import (`com.sap.gateway.ip.core.customdev.util.Message`
  bzw. `com.sap.it.script.v2.api.Message`). Kein Umschreiben, kein Stubben
  von Hand.
- **Beide CPI-Script-Generationen** werden unterstützt und automatisch
  erkannt: Script Version 1.x (Groovy 2.4) und Script Version 2.x (Groovy 4).
  Der Runner liest die Signatur von `processData(..)` und wählt die passende
  Message-Klasse — kein manuelles Umschalten nötig.
- Dateibasierter Input, dateibasierter und/oder UI-basierter Output.
- Läuft komplett lokal: keine Netzwerkaufrufe, keine Telemetrie.

## Nachgebaute CPI-APIs

| Klasse | Leistet |
|---|---|
| `com.sap.gateway.ip.core.customdev.util.Message` (v1) | Body, Header, Properties, Attachments, `getBody(Class)` für String/byte[]/InputStream/Reader |
| `com.sap.it.script.v2.api.Message` (v2) | identische Implementierung, geteilte Basis `MessageSupport` |
| `MessageLog` / `messageLogFactory` | Attachments, Custom-Header-Properties, typisierte Properties |
| `ITApiFactory` + `SecureStoreService` | Credentials aus dem `credentials`-Abschnitt der Testkonfiguration |

Zusätzlich im Klassenpfad wie in echtem CPI: `groovy-json`, `groovy-xml`,
`groovy-sql`, `groovy-templates`, `json-lib` (`net.sf.json.*`),
`commons-lang3`, `commons-io`.

Alle Nachbauten sind ein **Clean-Room-Nachbau** der öffentlich beobachtbaren
API-Oberfläche — kein SAP-Quellcode.

## Groovy-Laufzeit

- Wählbar beim Bauen: Groovy 4 (Default, passend zu Script Version 2.x) oder
  Groovy 3 via Maven-Profil `-Pgroovy3` (näher an der alten 2.4-Generation,
  da 2.4 selbst auf Java 17 nicht mehr läuft).
- Kein Fat-/Uber-JAR: schlankes JAR + `lib/`-Ordner über Manifest-Classpath,
  damit sich die separaten `ExtensionModule`-Dateien von `groovy-dateutil`,
  `groovy-xml` und `groovy-sql` nicht gegenseitig überschreiben (sonst z.B.
  `Date.parse(..)` kaputt).
- Erkannte Kombination (Groovy-Version + Script-Generation) wird nach jedem
  Lauf angezeigt, in UI-Kopfzeile und CLI-Ausgabe.

## Kommandozeile (`run.sh` / `run.bat`)

- `run <cmd>` führt ein Script einmal aus, Ergebnis als Datei(en).
- Optionen: `--script/-s`, `--body/-b`, `--config/-c`, `--out/-o`,
  `--outdir/-d`, `--property/-p` (mehrfach), `--header/-H` (mehrfach),
  `--entry` (Einstiegsmethode), `--encoding`, `--no-messagelog`,
  `--quiet/-q`.
- Erzeugte Dateien im Ausgabeordner: `output.xml`/`.json`/`.txt` (Endung aus
  Inhalt erkannt), `properties.json`, `headers.json`, `console.log`,
  `property_<name>.log` für lange mehrzeilige Properties (z.B. Debug-Logs),
  `attachments/NN_*.txt`, bei Fehlern zusätzlich `error.log`.
- Exit-Codes: `0` ok, `1` Script-Fehler, `2` Aufruffehler — scriptbar/CI-tauglich.

## Weboberfläche (`ui.sh` / `ui.bat`)

- Script-Editor mit Laden/Speichern direkt aus/in den Projektordner.
- Getrennte Tabs für Input, Header, Properties (Key/Value-Editor mit
  Hinzufügen/Entfernen einzelner Zeilen).
- Ergebnis-Tabs: Output, Console, Ergebnis-Properties, Attachments — je mit
  Zähler-Badge.
- **Größenverstellbare Bereiche**: ziehbare Trenner zwischen Script/rechter
  Spalte und zwischen Input/Output, Doppelklick setzt zurück, Aufteilung
  wird gemerkt.
- **Pretty-Print** für XML und JSON im Output-Tab (automatische Erkennung).
  Wirkt nur auf die Anzeige — Speichern schreibt immer den unveränderten
  Body. XML wird auf dem Rohtext formatiert, nicht per DOM-Parser neu
  serialisiert, damit Entities wie `&amp;` exakt erhalten bleiben.
- **Kopieren**-Button kopiert den Inhalt des aktiven Ergebnis-Tabs
  (inklusive Pretty-Formatierung) in die Zwischenablage, mit Fallback über
  `document.execCommand` für Zugriffe ohne „Secure Context" (z.B.
  `http://rechnername.local`).
- **Zweisprachig** (Deutsch/Englisch), automatische Erkennung über die
  Browsersprache, manuell umschaltbar, Wahl wird gemerkt. Erweiterbar auf
  weitere Sprachen durch einen neuen Eintrag im `I18N`-Wörterbuch.
- **Eingebaute Hilfe** (`?`-Button): Schnellstart, Config-Referenz,
  Tastenkürzel, Sicherheitshinweise, Über/Credits — ebenfalls zweisprachig.
- Tastenkürzel: `⌘`/`Strg`+`Enter` führt von überall auf der Seite aus,
  `Tab` in den Editoren rückt ein statt das Feld zu verlassen.
- Bearbeitungsstand (Script, Input, Header, Properties, Layout, Sprache)
  bleibt im Browser über `localStorage` erhalten.
- Valcoba-Logo in Kopfzeile und Über-Tab.

## Testfall-Konfiguration (`config.json`)

- Bündelt Scriptpfad, Eingabedatei, Einstiegsmethode, Header, Properties und
  Mock-Credentials für einen Testfall; Pfade relativ zur Config-Datei.
- `messageLogEnabled: false` lässt `getMessageLog(msg)` `null` liefern —
  simuliert ein CPI-Log-Level unterhalb „Info".

## Sicherheit

- Server bindet standardmäßig nur auf `127.0.0.1`; Netzwerkfreigabe über
  `--bind lan`/`--bind <adresse>` erzwingt ein Zugriffstoken (automatisch
  erzeugt, außer `--token`/`--no-token` gesetzt).
- Token muss als `?t=...` oder Header `X-Tester-Token` mitgeschickt werden;
  ungültige/fehlende Anfragen bekommen 403.
- Zustandsverändernde Endpunkte (`/api/run`, `/api/save`) verlangen
  zusätzlich einen festen `X-Tester-Csrf`-Header — greift auch im
  Standard-Loopback-Modus, schützt vor Codeausführung durch eine andere im
  selben Browser offene Webseite ("localhost CSRF").
- Datei-Laden/-Speichern ist strikt auf das Projektverzeichnis beschränkt
  (kein Verlassen über Pfadangaben).
- Request-Body-Limit (25 MB) gegen Speicher-Erschöpfung.
- Vollständiges Bedrohungsmodell und Meldeweg für Schwachstellen in
  [SECURITY.md](SECURITY.md).

## Plattformunterstützung

- macOS, Linux, Windows — alle drei real getestet.
- `build.sh`/`run.sh`/`ui.sh` (Unix) und `build.bat`/`run.bat`/`ui.bat`
  (Windows) mit identischen Argumenten.
- Windows-Skripte prüfen proaktiv, ob `java`/`mvn` im PATH liegen, und
  verweisen bei Bedarf auf eine Installationsanleitung im README statt eine
  kryptische Systemfehlermeldung durchzureichen.
- Pfade mit Schrägstrich funktionieren dank JVM-Normalisierung auch unter
  Windows; die Windows-Beispiele nutzen Backslashes nur aus Konvention.

## Mitgelieferte Beispiele

- **`scripts/OrderToXml.groovy`** (+ `_v2`-Variante): minimales,
  branchenneutrales Standardbeispiel — JSON-Bestellung zu XML, zeigt
  Body/Header/Property/MessageLog-Nutzung.
- **`examples/PerPersonToXml.groovy`** (+ `_v2`-Variante): größeres,
  realistischeres Beispiel (SuccessFactors-`PerPerson`-Export) mit
  Zeitscheiben-Auswahllogik, OData-Array-Normalisierung, verschachtelten
  Picklist-Lookups — vier Testfixtures für unterschiedliche Auswahlfälle,
  alle Daten synthetisch.

## Projektqualität / Open Source

- Lizenz: GPL-3.0.
- Zweisprachige Doku: `README.md` (EN) / `README.de.md` (DE).
- `SECURITY.md`, `CONTRIBUTING.md`, `examples/README.md`.
- Klarer Markenrechte-Hinweis: keine Zugehörigkeit zu/Unterstützung durch
  SAP, Nachbauten enthalten keinen SAP-Quellcode.
- `.gitignore` gehärtet gegen Build-Artefakte, IDE-Dateien, `.env`/Schlüssel-
  material, lokale Configs mit echten Credentials (`*.local.json`).
