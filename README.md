# CPI Groovy Tester

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](#requirements)

*[Deutsche Version](README.de.md)*

A local test harness for SAP Cloud Integration (CPI) Groovy scripts. A script
runs **completely unchanged** — including its original
`import com.sap.gateway.ip.core.customdev.util.Message` (or the Script
Version 2.x equivalent) — against a file-based input, with the result written
to files and/or shown in a small bundled web UI. No copy-pasting into a
throwaway class, no stubbing out CPI-specific calls by hand.

- **Runs both CPI script generations** — Script Version 1.x (Groovy 2.4,
  `com.sap.gateway.ip.core.customdev.util.Message`) and Script Version 2.x
  (Groovy 4, `com.sap.it.script.v2.api.Message`) — auto-detected per script,
  no manual switching.
- **CLI and web UI**, same engine underneath. The CLI suits scripting/CI; the
  UI suits interactive editing with a resizable, pretty-printing, bilingual
  (EN/DE, extensible) interface and inline help.
- **No network calls, no telemetry.** Everything runs on your machine.

## Table of contents

- [Requirements](#requirements)
- [Build](#build)
- [Option 1 — command line](#option-1--command-line)
- [Option 2 — web UI](#option-2--web-ui)
- [Network access](#network-access)
- [Test case configuration (`config.json`)](#test-case-configuration-configjson)
- [A larger example](#a-larger-example)
- [What is emulated](#what-is-emulated)
- [Script generations and Groovy versions](#script-generations-and-groovy-versions)
- [Differences from the real CPI runtime](#differences-from-the-real-cpi-runtime)
- [Project structure](#project-structure)
- [Contributing](#contributing)
- [Security](#security)
- [License and trademarks](#license-and-trademarks)

## Requirements

- JDK 17 or newer
- Maven 3.8+

## Build

```bash
./build.sh          # same as: mvn clean package
```

Produces `target/cpi-groovy-tester.jar` plus `target/lib/` with its
dependencies (deliberately not a fat/uber JAR — see
[Differences from the real CPI runtime](#differences-from-the-real-cpi-runtime)
for why).

## Option 1 — command line

```bash
./run.sh run --config testdata/config.json --outdir out
```

or without a config file at all:

```bash
./run.sh run \
  --script scripts/OrderToXml.groovy \
  --body   testdata/order.json \
  --property debugLoggingEnabled=true \
  --property currency=USD \
  --out    out/result.xml
```

Files written to the output directory:

| File | Content |
|---|---|
| `output.xml` / `.json` / `.txt` | Message body after the run (extension guessed from content) |
| `properties.json` | all exchange properties after the run |
| `headers.json` | all headers after the run |
| `console.log` | anything the script printed via `println` |
| `property_processDebugLog.log` | long, multi-line properties, additionally as plain text |
| `attachments/NN_*.txt` | everything from `messageLog.addAttachmentAsString(...)` |
| `error.log` | only on failure: message + stack trace |

Exit code: `0` = ok, `1` = script error, `2` = invocation error.
Full option list: `./run.sh --help`

## Option 2 — web UI

```bash
./ui.sh                 # http://localhost:8899, opens a browser tab
./ui.sh --port 9000 --no-open
```

Script on the left; Input/Headers/Properties top right; Output/Console/Result
props/Attachments bottom right. `⌘`/`Ctrl`+`Enter` runs the script from
anywhere on the page.

- **Resizable panels** — drag the dividers between panels; double-click a
  divider to reset it. The layout is remembered.
- **Pretty-print** for the Output tab (XML and JSON, auto-detected). Display
  only — Save always writes the unchanged body, so comparisons against the
  real CPI output stay meaningful. XML is formatted on the raw text rather
  than re-serialized through a DOM parser, so entities like `&amp;` survive
  untouched.
- **Copy** copies exactly what the active result tab shows (including Pretty
  formatting). Falls back to `document.execCommand` when
  `navigator.clipboard` isn't available (e.g. accessed via
  `http://hostname.local`, which isn't a "secure context").
- **English and German**, auto-detected from the browser, switchable, and
  remembered. Adding another language means adding one object to the `I18N`
  dictionary in `index.html` — see [CONTRIBUTING.md](CONTRIBUTING.md).
- **Built-in help** (the `?` button) — quickstart, config reference,
  keyboard shortcuts, security notes, about/credits.
- Script and input can be loaded from and saved back to the project directory
  directly from the UI; the editing state persists in the browser between
  runs.

### Network access

The server binds to `127.0.0.1` only by default and restricts file
load/save to the project directory. To reach it from another machine:

```bash
./ui.sh --bind lan          # binds 0.0.0.0
```

> **The UI executes arbitrary Groovy and writes files in the project
> directory.** Anyone with the address and token can run code as your user.
> Only use this on networks you fully trust. Full threat model in
> [SECURITY.md](SECURITY.md).

As soon as it's bound to a non-loopback address, an access token is
**required** — auto-generated unless `--token` is given. It must be present
as `?t=...` in the URL (the UI attaches it to every request automatically)
or as an `X-Tester-Token` header. Requests without a valid token get 403.
`POST /api/run` and `POST /api/save` additionally require a fixed
`X-Tester-Csrf` header — enforced even in the default loopback mode — so
that another website open in the same browser can't silently trigger code
execution while the tool is running.

| Option | Effect |
|---|---|
| `--bind <address>` | `127.0.0.1` (default), `lan`/`all`/`0.0.0.0`, or a specific address |
| `--token <value>` | fixed token instead of a random one |
| `--no-token` | disables the token — only sensible with loopback binding |

On macOS with the firewall enabled, the first external connection triggers a
one-time prompt asking whether `java` may accept incoming connections.

## Test case configuration (`config.json`)

```jsonc
{
  "script":      "../scripts/MyScript.groovy",  // relative to config.json
  "body":        "input.json",
  "entryMethod": "processData",
  "messageLogEnabled": true,          // false => getMessageLog(msg) returns null

  "headers":    { "SapAuthenticatedUserName": "S0001234567" },
  "properties": { "debugLoggingEnabled": "true", "processDebugLog": "" },
  "credentials":{ "MyAlias": { "user": "u", "password": "p" } }
}
```

Testing a new script = drop it into `scripts/`, the input into `testdata/`,
and a `config.json` next to it — nothing else changes. Keep real credentials
out of tracked config files; a `*.local.json` filename is ignored by git (see
`.gitignore`).

## A larger example

The bundled `OrderToXml` example above is intentionally minimal — good for a
first look at the tool, not for CPI Groovy patterns beyond the basics. The
[`examples/`](examples/) directory has a second, more realistic one: a
SuccessFactors `PerPerson` OData response mapped to a flat XML export,
picking the right record out of several overlapping employment time slices.
It's a good reference for date-range selection logic, OData's
sometimes-object-sometimes-array shape, and nested picklist lookups — all
with fabricated data. See [`examples/README.md`](examples/README.md) for
what each test fixture demonstrates.

## What is emulated

| CPI class | Behavior here |
|---|---|
| `com.sap.gateway.ip.core.customdev.util.Message` | Script Version 1.x: body, headers, properties, attachments, `getBody(Class)` for String/byte[]/InputStream/Reader |
| `com.sap.it.script.v2.api.Message` | Script Version 2.x, same implementation (shared base `de.cpitester.MessageSupport`) |
| `com.sap.it.api.msglog.MessageLog` | records attachments, custom header properties, and typed properties |
| `com.sap.it.api.msglog.factory.MessageLogFactory` | binding variable `messageLogFactory` |
| `com.sap.it.api.ITApiFactory` + `SecureStoreService` | credentials come from the config's `credentials` section |

Also on the classpath, matching CPI: `groovy-json`, `groovy-xml`,
`groovy-sql`, `groovy-templates`, `json-lib` (`net.sf.json.*`),
`commons-lang3`, `commons-io`.

These mocks are a **clean-room reimplementation** of the publicly observable
Script API surface (method names, parameter shapes, and behavior inferred
from real scripts and public documentation) — not derived from, and
containing none of, SAP's actual source code.

## Script generations and Groovy versions

CPI has two script generations with different runtimes:

| Flow step | Groovy | Message class |
|---|---|---|
| Script Version 1.x | 2.4 | `com.sap.gateway.ip.core.customdev.util.Message` |
| Script Version 2.x | 4 | `com.sap.it.script.v2.api.Message` |

**Both Message classes are always present.** The runner reads the signature
of `processData(..)` and builds the matching instance — scripts from either
generation run without switching anything. What was detected shows up after
the run in the UI's header, and in the CLI output:

```
Runtime: Groovy 4.0.24 | Script Version 2.x (com.sap.it.script.v2.api.Message)
```

The Groovy runtime itself is chosen at build time:

```bash
./build.sh              # Groovy 4  (default, matches Script Version 2.x)
./build.sh -Pgroovy3    # Groovy 3  (closer to the old generation's 2.4)
```

Groovy 2.4 itself no longer runs on Java 17, hence 3.0.x as the closest
practical match for old scripts.

## Differences from the real CPI runtime

- **Groovy 3 instead of 2.4** (only relevant with the `groovy3` profile):
  `JsonSlurper` returns `LinkedHashMap` instead of
  `groovy.json.internal.LazyMap`. Code that checks for that exact class name
  falls into the `else` branch here — usually with the same end result, but
  worth double-checking.
- **The Script Version 2.x mock** has the same method surface as the v1
  mock. It is derived from the v1 API plus the calls scripts actually make,
  not from a complete SAP reference. If a script calls a method that isn't
  here, Groovy raises a `MissingMethodException` — add it to
  `src/main/java/com/sap/it/script/v2/api/Message.java` (or better, to the
  shared `de.cpitester.MessageSupport`, see
  [CONTRIBUTING.md](CONTRIBUTING.md)).
- **No uber JAR:** `groovy-dateutil`, `groovy-xml`, and `groovy-sql` each
  ship their own `META-INF/groovy/...ExtensionModule`. Merging them into one
  JAR keeps only one, silently breaking things like `Date.parse(..)`. Hence
  a slim JAR plus a `lib/` folder via the manifest classpath.
- No Camel exchange, no adapter/partner/datastore context.

## Project structure

```
pom.xml                     Maven build (groovy4 default profile, groovy3 alternative)
build.sh / run.sh / ui.sh   build / CLI / web UI entry points
scripts/                    Groovy scripts under test
testdata/                   input files and test case configs
examples/                   a larger, more realistic example (see its own README)
out/                        results (created on demand)
src/main/java/com/sap/...   CPI API mocks (clean-room reimplementation)
src/main/java/de/cpitester/ runner, CLI, web server
src/main/resources/ui/      web UI (single self-contained file, no external dependencies)
```

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md)
for the development setup, how the v1/v2 Message mocks relate, and how to
add a UI language.

## Security

This tool runs arbitrary code by design; read
[SECURITY.md](SECURITY.md) before exposing it beyond `localhost`, and use it
to report a vulnerability.

## License and trademarks

Licensed under [GPL-3.0](LICENSE).

This project is not affiliated with, sponsored by, or endorsed by SAP. "SAP",
"SAP Cloud Integration", and related marks are trademarks of SAP SE. The
bundled runtime mocks are a clean-room reimplementation written for local
testing only, based on the publicly observable Script API surface — they
contain no SAP source code.

---

<img src="src/main/resources/ui/assets/valcoba-logo.png" alt="Valcoba" height="28">

Originally built for internal use at [Valcoba](https://www.valcoba.com),
your CSV and Quality partner, and shared as open source.
