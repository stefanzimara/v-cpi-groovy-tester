# Funktionsübersicht

Sammlung aller Funktionen des CPI Groovy Testers, nach Bereich sortiert.
Für Anleitungen siehe [README.de.md](README.de.md), für Details zum
Sicherheitsmodell [SECURITY.md](SECURITY.md) (englisch).

*[English version](FEATURES.md)*

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

## Code Inspector (Live-Prüfung beim Tippen)

Prüft das Script im Editor, **ohne es auszuführen**, und meldet Probleme mit
Zeile und Spalte — als Punkt im Gutter, gepunktete Unterstreichung im Code und
Liste unterhalb des Editors. Ein Klick auf einen Eintrag springt zur Stelle.

- **Compiler-Ebene:** Groovy kompiliert bis Phase `CANONICALIZATION` — das
  deckt Syntaxfehler, nicht auflösbare Imports/Klassen und die
  Compiler-Warnungen ab, also alles, woran auch der echte Lauf scheitern
  würde. Erzeugt wird dabei kein Bytecode.
- **CPI-Regeln** auf dem AST — Stolperfallen, die ein normaler
  Groovy-Compiler nicht kennen kann:

  | Regel | Meldet |
  |---|---|
  | `entryMissing` | keine Methode `processData(Message)` — der Runner hat nichts zum Aufrufen |
  | `entryReturnsVoid` | Einstiegsmethode als `void` deklariert statt die Message zurückzugeben |
  | `entryParamType` | Einstiegsmethode nimmt keine Message entgegen |
  | `apiVersionMix` | beide Message-Generationen gleichzeitig importiert |
  | `bodyReadTwice` | ungetypter Body mehrfach gelesen — in CPI ein `InputStream`, ab dem zweiten Lesen leer |
  | `bodyUntyped` | `getBody()` ohne Typ statt `getBody(java.lang.String)` |
  | `setBodyMissing` | Script setzt den Body nie |
  | `messageLogNullCheck` | MessageLog ohne Null-Prüfung benutzt — `getMessageLog(..)` liefert `null`, wenn das Tenant-Logging aus ist |
  | `unknownProperty` / `unknownHeader` | Name wird gelesen, ist im aktuellen Testfall aber nicht gesetzt |
  | `hardcodedSecret` | Passwort/Token als Literal im Code statt aus dem Secure Store |
  | `forbiddenSleep` / `forbiddenExit` / `fileAccess` | `Thread.sleep`, `System.exit`, `java.io.File` — auf dem Tenant unzulässig bzw. wirkungslos |
  | `emptyCatch` | leerer `catch`-Block, Fehler verschwindet spurlos |
  | `printlnUsage` | `println` erreicht auf dem Tenant niemanden |

- Die Regeln kennen den **aktuellen Testfall**: eine Property, die das Script
  liest, die aber weder in den Properties der UI steht noch vom Script selbst
  gesetzt wird, wird als Hinweis gemeldet — und verschwindet, sobald man sie
  anlegt.
- Drei Stufen: Fehler (rot), Warnung (orange), Hinweis (blau), mit Zähler in
  der Script-Leiste. Per Knopf abschaltbar, die Wahl wird gemerkt.
- Bewusst konservativ ausgelegt: lieber eine Falle nicht melden als ständig
  falschen Alarm schlagen. Ein Linter, dem man nicht glaubt, wird abgeschaltet.
- Meldungen sind zweisprachig; die Texte des Groovy-Compilers bleiben im
  Original.

## Trace (aufgezeichneter Ablauf statt Debug-Ausgaben)

Der Knopf **⏱ Trace** führt das Script aus und schreibt dabei jeden Schritt
mit. Danach lässt sich der Ablauf im Trace-Tab vor- und zurückspulen — man
sieht an jeder Stelle, was das Script gerade tut, ohne dafür `println` oder
ein Debug-Log einbauen zu müssen.

- Pro ausgeführtem Statement festgehalten: **Zeilennummer**, die dort
  **sichtbaren lokalen Variablen** mit ihren Werten, die **Änderungen am
  Message-Zustand** (Property/Header/Body, jeweils vorher → nachher) und die
  **Konsolenausgabe dieses Schritts**.
- Die aktuelle Zeile wird im Editor hervorgehoben und angesprungen.
- Filter **„nur Schritte, die etwas ändern"** — in einer Schleife sind das
  sonst überwiegend Wiederholungen, durch die man sich durchklicken müsste.
- Bei einem Abbruch endet die Aufzeichnung an der Stelle, an der es geknallt
  ist, samt Variablenständen unmittelbar davor. Genau dort steht meist die
  Antwort, warum es geknallt hat.
- Aufgezeichnet werden **Änderungen, nicht der volle Zustand pro Schritt** —
  sonst wäre das Protokoll ein Vielfaches der Nutzdaten.
- Schrittlimit (Standard 5000) gegen Aufzeichnungen aus großen Schleifen;
  wird es erreicht, sagt die Anzeige, wie viele Schritte tatsächlich liefen
  und ab wo etwas fehlt.

**Wie es funktioniert:** Ein `CompilationCustomizer` (`TraceTransformer`)
hängt in der Compiler-Phase `CONVERSION` vor jedes Statement einen Aufruf des
`TraceRecorder`. Zu diesem Zeitpunkt ist der AST geparst, aber die Variablen
sind noch nicht aufgelöst — der eingefügte Code durchläuft danach dieselben
Phasen wie handgeschriebener und braucht keine Sonderbehandlung. Welche
Variablen an einer Stelle sichtbar sind, führt der Transformer selbst Buch
(eine erst weiter unten deklarierte Variable darf nicht auftauchen, sonst
läuft der erzeugte Code in eine `MissingPropertyException`).

Damit ist echte Variableninspektion möglich **ohne zweiten Prozess und ohne
JDWP/JDI**. Erfasst werden auch Closure-Rümpfe (samt implizitem `it`),
Schleifenvariablen und Catch-Variablen. Eingefügt wird immer *vor* einem
Statement, nie danach — sonst wäre das letzte Statement einer Methode nicht
mehr das letzte und Groovys impliziter Rückgabewert ein anderer.

Ohne `⏱ Trace` wird nichts instrumentiert: ein normaler Lauf ist byte-gleich
zu vorher.

## Weboberfläche (`ui.sh` / `ui.bat`)

- **Vollwertiger Code-Editor** (CodeMirror, lokal im JAR — kein CDN, läuft
  offline): Groovy-Syntaxhervorhebung, Zeilennummern, Klammer-Matching und
  -Vervollständigung, aktive Zeile hervorgehoben, folgt dem Hell-/Dunkel-Schema
  der übrigen Oberfläche.
- Script-Editor mit Laden/Speichern direkt aus/in den Projektordner.
- Getrennte Tabs für Input, Header, Properties (Key/Value-Editor mit
  Hinzufügen/Entfernen einzelner Zeilen).
- Ergebnis-Tabs: Output, Console, Ergebnis-Properties, Attachments, Trace —
  je mit Zähler-Badge.
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
- Zustandsverändernde Endpunkte (`/api/run`, `/api/save`, `/api/check`)
  verlangen zusätzlich einen festen `X-Tester-Csrf`-Header — greift auch im
  Standard-Loopback-Modus, schützt vor Codeausführung durch eine andere im
  selben Browser offene Webseite ("localhost CSRF").
- `/api/check` (Code Inspector) steht bewusst hinter demselben Schutz wie
  `/api/run`: Kompilieren bis `CANONICALIZATION` führt AST-Transformationen
  aus, und die sind beliebiger Code. „Nur prüfen" ist hier nicht
  gleichbedeutend mit „harmlos".
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

## Rauchtest (`smoke.sh` / `smoke.bat`)

- Fährt **jedes mitgelieferte Beispiel gegen jede Testeingabe** über die CLI —
  zehn Fälle, beide Script-Generationen, vier PerPerson-Fixtures.
- Vergleicht `output.*`, `properties.json` und `headers.json` mit den
  Sollständen unter `testdata/expected/` (rund 160 KB). Fängt damit zwei
  Fehlerklassen: ein Beispiel läuft gar nicht mehr, oder seine Ausgabe hat
  sich still verändert. Bei Abweichung wird der Diff direkt angezeigt.
- `--profiles` baut Groovy 4 und Groovy 3 und prüft beide. Die Ausgaben sind
  über beide Profile hinweg identisch, deshalb genügt ein Satz Sollstände.
- `--update` schreibt die Sollstände nach beabsichtigten Änderungen neu.
- Nicht verglichen werden Attachments (ihre Dateinamen enthalten einen
  Zeitstempel aus dem Laufzeitpunkt) und `console.log` (kann Pfade des
  ausführenden Rechners enthalten).
- Exit-Code `0`/`1` — direkt in CI verwendbar.

Entstanden aus einem konkreten Anlass: `MessageLogFactory.getMessageLog(..)`
war auf die Message-Klasse der Version 1.x festgelegt, wodurch jedes Script
der Version 2.x scheiterte — inklusive des mitgelieferten Standardbeispiels.
Unentdeckt blieb das nur, weil nichts die Beispiele automatisch ausführte.

## Projektqualität / Open Source

- Lizenz: GPL-3.0.
- Einzige mitgelieferte Fremdkomponente im Frontend: CodeMirror 5 (MIT),
  lokal unter `src/main/resources/ui/assets/codemirror/` samt Lizenztext —
  bewusst kein CDN, damit die Oberfläche ohne Internetzugang vollständig
  funktioniert und nichts nach außen telefoniert.
- Zweisprachige Doku: `README.md` / `FEATURES.md` (EN),
  `README.de.md` / `FEATURES.de.md` (DE).
- `SECURITY.md`, `CONTRIBUTING.md`, `examples/README.md`.
- **CI** (`.github/workflows/ci.yml`): baut bei jedem Push und Pull Request
  und fährt den Rauchtest — Linux über beide Groovy-Profile, Windows als
  eigener Job, damit die `.bat`-Skripte tatsächlich ausgeführt und nicht nur
  angenommen werden.
- Klarer Markenrechte-Hinweis: keine Zugehörigkeit zu/Unterstützung durch
  SAP, Nachbauten enthalten keinen SAP-Quellcode.
- `.gitignore` gehärtet gegen Build-Artefakte, IDE-Dateien, `.env`/Schlüssel-
  material, lokale Configs mit echten Credentials (`*.local.json`).
