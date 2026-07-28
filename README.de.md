# CPI Groovy Tester

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](#voraussetzungen)

*[English version](README.md)*

Lokale Testumgebung für SAP-Cloud-Integration-(CPI)-Groovy-Scripte. Ein
Script läuft **vollkommen unverändert** — inklusive seines ursprünglichen
`import com.sap.gateway.ip.core.customdev.util.Message` (bzw. dem Äquivalent
aus Script Version 2.x) — gegen dateibasierten Input, das Ergebnis landet in
Dateien und/oder in einer kleinen mitgelieferten Weboberfläche. Kein
Copy-Paste in eine Wegwerfklasse, kein manuelles Herauskürzen von
CPI-spezifischen Aufrufen.

- **Beide CPI-Script-Generationen** — Script Version 1.x (Groovy 2.4,
  `com.sap.gateway.ip.core.customdev.util.Message`) und Script Version 2.x
  (Groovy 4, `com.sap.it.script.v2.api.Message`) — werden pro Script
  automatisch erkannt, kein manuelles Umschalten.
- **CLI und Weboberfläche**, dieselbe Engine darunter. Die CLI eignet sich
  fürs Skripten/CI, die UI fürs interaktive Arbeiten mit anpassbaren
  Bereichen, Pretty-Print, zweisprachiger (DE/EN, erweiterbarer) Oberfläche
  und eingebauter Hilfe.
- **Keine Netzwerkaufrufe, keine Telemetrie.** Alles läuft auf dem eigenen Rechner.

## Inhalt

- [Voraussetzungen](#voraussetzungen)
- [Bauen](#bauen)
- [Variante 1 — Kommandozeile](#variante-1--kommandozeile)
- [Variante 2 — Weboberfläche](#variante-2--weboberfläche)
- [Zugriff über das Netzwerk](#zugriff-über-das-netzwerk)
- [Testfall-Konfiguration (`config.json`)](#testfall-konfiguration-configjson)
- [Ein größeres Beispiel](#ein-größeres-beispiel)
- [Was nachgebaut ist](#was-nachgebaut-ist)
- [Script-Generationen und Groovy-Versionen](#script-generationen-und-groovy-versionen)
- [Unterschiede zur echten CPI-Laufzeit](#unterschiede-zur-echten-cpi-laufzeit)
- [Projektstruktur](#projektstruktur)
- [Mitwirken](#mitwirken)
- [Sicherheit](#sicherheit)
- [Lizenz und Markenrechte](#lizenz-und-markenrechte)

## Voraussetzungen

- JDK 17 oder neuer
- Maven 3.8+
- macOS, Linux oder Windows

Die Java-Anwendung selbst ist vollständig plattformneutral. Jeder Befehl
unten steht zweimal da: einmal für macOS/Linux (`build.sh`/`run.sh`/`ui.sh`)
und einmal für Windows (`build.bat`/`run.bat`/`ui.bat`, gleiche Argumente).
Ein Detail, das weniger oft stört als man denkt: Schrägstriche in Pfaden —
wie in den macOS/Linux-Beispielen verwendet — funktionieren auch unter
Windows, da die JVM sie normalisiert. Die Windows-Beispiele unten nutzen
Backslashes nur, weil das die native Konvention ist, nicht weil es nötig wäre.

## Bauen

**macOS/Linux**
```bash
./build.sh
```

**Windows**
```bat
build.bat
```

Beide entsprechen `mvn clean package`. Ergebnis: `target/cpi-groovy-tester.jar`
plus `target/lib/` mit den Abhängigkeiten (bewusst kein Fat-/Uber-JAR — Grund
siehe [Unterschiede zur echten CPI-Laufzeit](#unterschiede-zur-echten-cpi-laufzeit)).

## Variante 1 — Kommandozeile

**macOS/Linux**
```bash
./run.sh run --config testdata/config.json --outdir out
```

**Windows**
```bat
run.bat run --config testdata\config.json --outdir out
```

oder ganz ohne Konfigurationsdatei:

**macOS/Linux**
```bash
./run.sh run \
  --script scripts/OrderToXml.groovy \
  --body   testdata/order.json \
  --property debugLoggingEnabled=true \
  --property currency=USD \
  --out    out/ergebnis.xml
```

**Windows**
```bat
run.bat run ^
  --script scripts\OrderToXml.groovy ^
  --body   testdata\order.json ^
  --property debugLoggingEnabled=true ^
  --property currency=USD ^
  --out    out\ergebnis.xml
```

Im Ausgabeordner landen:

| Datei | Inhalt |
|---|---|
| `output.xml` / `.json` / `.txt` | Message-Body nach dem Lauf (Endung wird aus dem Inhalt geraten) |
| `properties.json` | alle Exchange-Properties nach dem Lauf |
| `headers.json` | alle Header nach dem Lauf |
| `console.log` | alles, was das Script per `println` ausgegeben hat |
| `property_processDebugLog.log` | lange, mehrzeilige Properties zusätzlich als Klartext |
| `attachments/NN_*.txt` | alles aus `messageLog.addAttachmentAsString(...)` |
| `error.log` | nur bei Abbruch: Meldung + Stacktrace |

Exit-Code: `0` = ok, `1` = Script-Fehler, `2` = Aufruffehler.
Alle Optionen: `./run.sh --help` (macOS/Linux) bzw. `run.bat --help` (Windows).

## Variante 2 — Weboberfläche

**macOS/Linux**
```bash
./ui.sh                 # http://localhost:8899, öffnet den Browser
./ui.sh --port 9000 --no-open
```

**Windows**
```bat
ui.bat                  :: http://localhost:8899, oeffnet den Browser
ui.bat --port 9000 --no-open
```

Links das Script, rechts oben Input/Header/Properties, rechts unten
Output/Console/Ergebnis-Properties/Attachments. `⌘`/`Strg`+`Enter` führt das
Script von überall auf der Seite aus.

- **Größenverstellbare Bereiche** — an den Trennern zwischen den Panels
  ziehen, Doppelklick setzt einen Trenner zurück. Die Aufteilung wird gemerkt.
- **Pretty-Print** für den Output-Tab (XML und JSON, automatisch erkannt).
  Betrifft nur die Anzeige — Speichern schreibt immer den unveränderten
  Body, damit Vergleiche mit der echten CPI-Ausgabe aussagekräftig bleiben.
  XML wird auf dem Rohtext formatiert statt über einen DOM-Parser neu
  serialisiert, sodass Entities wie `&amp;` unangetastet bleiben.
- **Kopieren** kopiert genau das, was der aktive Ergebnis-Tab zeigt
  (inklusive Pretty-Formatierung). Fällt auf `document.execCommand` zurück,
  wenn `navigator.clipboard` nicht verfügbar ist (z.B. bei Zugriff über
  `http://rechnername.local`, kein „Secure Context“).
- **Deutsch und Englisch**, automatisch anhand des Browsers erkannt,
  umschaltbar und gemerkt. Eine weitere Sprache hinzuzufügen bedeutet, ein
  Objekt im `I18N`-Wörterbuch in `index.html` zu ergänzen — siehe
  [CONTRIBUTING.md](CONTRIBUTING.md).
- **Eingebaute Hilfe** (der `?`-Button) — Schnellstart, Config-Referenz,
  Tastenkürzel, Sicherheitshinweise, Über/Credits.
- Script und Input lassen sich direkt aus dem Projektordner laden und wieder
  dorthin speichern; der Bearbeitungsstand bleibt im Browser erhalten.

### Zugriff über das Netzwerk

Der Server bindet standardmäßig nur auf `127.0.0.1` und beschränkt
Datei-Laden/-Speichern auf das Projektverzeichnis. Für den Zugriff von einem
anderen Rechner:

**macOS/Linux**
```bash
./ui.sh --bind lan          # bindet 0.0.0.0
```

**Windows**
```bat
ui.bat --bind lan          :: bindet 0.0.0.0
```

> **Die UI führt beliebiges Groovy aus und schreibt Dateien im
> Projektordner.** Wer die Adresse und das Token hat, kann Code unter deinem
> Benutzer ausführen. Nur in vollständig vertrauenswürdigen Netzen einsetzen.
> Vollständiges Bedrohungsmodell in [SECURITY.md](SECURITY.md).

Sobald an eine Nicht-Loopback-Adresse gebunden wird, ist ein Zugriffstoken
**Pflicht** — automatisch erzeugt, sofern nicht per `--token` gesetzt. Es
muss als `?t=...` in der URL stehen (die Oberfläche hängt es automatisch an
jede Anfrage an) oder als Header `X-Tester-Token` mitgeschickt werden.
Anfragen ohne gültiges Token bekommen 403. `POST /api/run` und
`POST /api/save` verlangen zusätzlich einen festen Header `X-Tester-Csrf` —
das gilt auch im Standard-Loopback-Modus — damit eine andere im selben
Browser offene Webseite nicht heimlich Code auf dem Tool auslösen kann,
während es läuft.

| Option | Wirkung |
|---|---|
| `--bind <adresse>` | `127.0.0.1` (Default), `lan`/`all`/`0.0.0.0` oder eine konkrete Adresse |
| `--token <wert>` | festes Token statt eines zufälligen |
| `--no-token` | Token abschalten — nur bei Loopback-Bindung sinnvoll |

Ist die macOS-Firewall aktiv, fragt beim ersten Zugriff von außen ein Dialog,
ob `java` eingehende Verbindungen annehmen darf. Das muss einmalig erlaubt
werden. Unter Windows mit aktivierter Defender-Firewall erscheint derselbe
Dialogtyp für `java.exe`/`javaw.exe` — nur in privaten/vertrauenswürdigen
Netzen erlauben.

## Testfall-Konfiguration (`config.json`)

```jsonc
{
  "script":      "../scripts/MeinScript.groovy",  // relativ zur config.json
  "body":        "input.json",
  "entryMethod": "processData",
  "messageLogEnabled": true,          // false => getMessageLog(msg) liefert null

  "headers":    { "SapAuthenticatedUserName": "S0001234567" },
  "properties": { "debugLoggingEnabled": "true", "processDebugLog": "" },
  "credentials":{ "MeinAlias": { "user": "u", "password": "p" } }
}
```

Ein neues Script testen = Script nach `scripts/` legen, Input nach
`testdata/`, eine eigene `config.json` daneben — sonst ändert sich nichts.
Echte Credentials nicht in versionierten Config-Dateien ablegen; ein
Dateiname auf `*.local.json` wird von git ignoriert (siehe `.gitignore`).

## Ein größeres Beispiel

Das mitgelieferte `OrderToXml`-Beispiel oben ist bewusst minimal — gut für
den ersten Blick aufs Werkzeug, nicht für CPI-Groovy-Muster jenseits der
Grundlagen. Im Ordner [`examples/`](examples/) liegt ein zweites,
realistischeres Beispiel: eine SuccessFactors-`PerPerson`-OData-Antwort,
abgebildet auf einen flachen XML-Export, der aus mehreren sich
überschneidenden Anstellungs-Zeitscheiben den richtigen Satz auswählt. Gute
Referenz für Zeitraum-Auswahllogik, ODatas mal-Objekt-mal-Array-Eigenheit und
verschachtelte Picklist-Lookups — alles mit erfundenen Daten. Was jede
Testdatei zeigt, steht in [`examples/README.md`](examples/README.md)
(Englisch).

## Was nachgebaut ist

| CPI-Klasse | Verhalten hier |
|---|---|
| `com.sap.gateway.ip.core.customdev.util.Message` | Script Version 1.x: Body, Header, Properties, Attachments, `getBody(Class)` für String/byte[]/InputStream/Reader |
| `com.sap.it.script.v2.api.Message` | Script Version 2.x, gleiche Implementierung (gemeinsame Basis `de.cpitester.MessageSupport`) |
| `com.sap.it.api.msglog.MessageLog` | zeichnet Attachments, Custom-Header-Properties und typisierte Properties auf |
| `com.sap.it.api.msglog.factory.MessageLogFactory` | Binding-Variable `messageLogFactory` |
| `com.sap.it.api.ITApiFactory` + `SecureStoreService` | liefert Credentials aus dem Abschnitt `credentials` der Konfiguration |

Zusätzlich im Klassenpfad, wie in CPI: `groovy-json`, `groovy-xml`,
`groovy-sql`, `groovy-templates`, `json-lib` (`net.sf.json.*`),
`commons-lang3`, `commons-io`.

Diese Nachbauten sind ein **Clean-Room-Nachbau** der öffentlich beobachtbaren
Script-API-Oberfläche (Methodennamen, Parameterformen und Verhalten,
abgeleitet aus echten Scripts und öffentlicher Dokumentation) — nicht aus
SAP-Quellcode abgeleitet und enthalten keinen solchen.

## Script-Generationen und Groovy-Versionen

CPI hat zwei Script-Generationen mit unterschiedlicher Laufzeit:

| Flowstep | Groovy | Message-Klasse |
|---|---|---|
| Script Version 1.x | 2.4 | `com.sap.gateway.ip.core.customdev.util.Message` |
| Script Version 2.x | 4 | `com.sap.it.script.v2.api.Message` |

**Beide Message-Klassen sind immer vorhanden.** Der Runner liest die
Signatur von `processData(..)` und baut die passende Instanz — Scripte
beider Generationen laufen also ohne Umschalten. Was erkannt wurde, steht
nach dem Lauf in der Kopfzeile der UI bzw. in der CLI-Ausgabe:

```
Laufzeit: Groovy 4.0.24 | Script Version 2.x (com.sap.it.script.v2.api.Message)
```

Die Groovy-Laufzeit wird beim Bauen gewählt:

**macOS/Linux**
```bash
./build.sh              # Groovy 4  (Default, passend zu Script Version 2.x)
./build.sh -Pgroovy3    # Groovy 3  (näher an der 2.4 der alten Generation)
```

**Windows**
```bat
build.bat              :: Groovy 4  (Default, passend zu Script Version 2.x)
build.bat -Pgroovy3    :: Groovy 3  (naeher an der 2.4 der alten Generation)
```

Groovy 2.4 selbst läuft auf Java 17 nicht mehr — daher 3.0.x als
nächstliegende Variante für alte Scripte.

## Unterschiede zur echten CPI-Laufzeit

- **Groovy 3 statt 2.4** (nur im Profil `groovy3`): `JsonSlurper` liefert
  `LinkedHashMap` statt `groovy.json.internal.LazyMap`. Code, der auf diesen
  Klassennamen prüft, läuft hier in den `else`-Zweig — im Regelfall mit
  gleichem Ergebnis, aber es lohnt sich, dort genau hinzuschauen.
- **Der v2-Message-Nachbau** hat denselben Methodenumfang wie der v1-Nachbau.
  Er ist aus der v1-API und den tatsächlich genutzten Aufrufen abgeleitet,
  nicht aus einer vollständigen SAP-Referenz. Ruft ein Script eine Methode
  auf, die es hier nicht gibt, meldet Groovy eine `MissingMethodException` —
  dann in `src/main/java/com/sap/it/script/v2/api/Message.java` ergänzen
  (besser noch in der gemeinsamen Basis `de.cpitester.MessageSupport`, siehe
  [CONTRIBUTING.md](CONTRIBUTING.md)).
- **Kein Uber-JAR:** `groovy-dateutil`, `groovy-xml` und `groovy-sql`
  bringen jeweils eine eigene `META-INF/groovy/...ExtensionModule` mit. Beim
  Verschmelzen in ein JAR überlebt nur eine davon, und dann fehlt z.B.
  `Date.parse(..)` klammheimlich. Deshalb schlankes JAR + `lib/`-Ordner über
  den Manifest-Classpath.
- Kein Camel-Exchange, kein Adapter-, Partner- oder Datastore-Kontext.

## Projektstruktur

```
pom.xml                     Maven-Build (Default-Profil groovy4, Alternative groovy3)
build.sh / run.sh / ui.sh   Bauen / CLI / Weboberflaeche (macOS/Linux)
build.bat / run.bat / ui.bat  dasselbe, fuer Windows
scripts/                    zu testende Groovy-Scripte
testdata/                   Eingabedateien und Testfall-Konfigurationen
examples/                   ein groesseres, realistischeres Beispiel (eigenes README)
out/                        Ergebnisse (wird bei Bedarf angelegt)
src/main/java/com/sap/...   Nachbau der CPI-APIs (Clean-Room-Reimplementierung)
src/main/java/de/cpitester/ Runner, CLI, Webserver
src/main/resources/ui/      Weboberfläche (eine Datei, keine externen Abhängigkeiten)
```

## Mitwirken

Issues und Pull Requests sind willkommen — siehe
[CONTRIBUTING.md](CONTRIBUTING.md) für das Entwicklungs-Setup, wie die
v1-/v2-Message-Nachbauten zusammenhängen und wie eine UI-Sprache ergänzt wird.

## Sicherheit

Dieses Werkzeug führt konzeptionell beliebigen Code aus. Vor jedem Einsatz
über `localhost` hinaus [SECURITY.md](SECURITY.md) lesen; dort steht auch,
wie eine Schwachstelle gemeldet wird.

## Lizenz und Markenrechte

Lizenziert unter [GPL-3.0](LICENSE).

Dieses Projekt steht in keiner Verbindung zu SAP, wird nicht von SAP
gesponsert oder unterstützt. „SAP“, „SAP Cloud Integration“ und verwandte
Bezeichnungen sind Marken der SAP SE. Die mitgelieferten Laufzeit-Nachbauten
sind ein Clean-Room-Nachbau, ausschließlich für lokale Tests geschrieben, auf
Basis der öffentlich beobachtbaren Script-API-Oberfläche — sie enthalten
keinen SAP-Quellcode.

---

<img src="src/main/resources/ui/assets/valcoba-logo.png" alt="Valcoba" height="28">

Ursprünglich für den internen Einsatz bei [Valcoba](https://www.valcoba.com),
Your CSV and Quality Partner, entstanden und als Open Source veröffentlicht.
