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
  UI suits interactive editing — syntax-highlighting editor, resizable panels,
  pretty-printing, bilingual (EN/DE, extensible) and with inline help.
- **[Catches problems while you type](#code-inspector)** — a code inspector
  compiles the script without running it and applies CPI-specific rules on top:
  a body read twice, a MessageLog used without a null check, a property your
  script reads that the test case never sets, a hard-coded password.
- **[Records a run step by step](#trace-stepping-through-a-run)** — scrub back
  and forth through the execution afterwards and see the variables at each
  point, instead of littering the script with `println` to find out.
- **No network calls, no telemetry.** Everything runs on your machine —
  including the bundled editor, so there is nothing to load from a CDN.

## Table of contents

- [Requirements](#requirements)
- [Build](#build)
- [Option 1 — command line](#option-1--command-line)
- [Option 2 — web UI](#option-2--web-ui)
- [Code inspector](#code-inspector)
- [Trace: stepping through a run](#trace-stepping-through-a-run)
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
- macOS, Linux, or Windows

The Java application itself is fully cross-platform. Every command below is
shown twice: once for macOS/Linux (`build.sh`/`run.sh`/`ui.sh`) and once for
Windows (`build.bat`/`run.bat`/`ui.bat`, same arguments). One detail that
trips people up less than you'd expect: forward slashes in paths — as used
in the macOS/Linux examples — work fine on Windows too, since the JVM
normalizes them. The Windows examples below use backslashes only because
that's the native convention, not because it's required.

<details>
<summary>Windows: installing Java and Maven</summary>

If `java` or `mvn` aren't recognized in a fresh terminal:

1. Install a JDK 17+ if `java -version` fails — e.g.
   [Eclipse Temurin](https://adoptium.net) or the
   [Microsoft Build of OpenJDK](https://learn.microsoft.com/java/openjdk/download).
   The installer usually offers to set `JAVA_HOME` and add itself to `PATH`;
   accept that.
2. Install Maven if `mvn -version` fails:
   - `winget install Apache.Maven` (Windows 10/11 with winget), or
   - download the binary zip from
     [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi),
     extract it, and add its `bin` folder to your `PATH` environment variable.
3. Open a **new** terminal window — `PATH` changes don't apply to windows
   that were already open — and confirm with `java -version` and
   `mvn -version`.

`build.bat`/`run.bat`/`ui.bat` check for both and print a pointer back here
if either is missing, instead of a bare "not recognized" error.

</details>

## Build

**macOS/Linux**
```bash
./build.sh
```

**Windows**
```bat
build.bat
```

Both are equivalent to `mvn clean package`. Produces
`target/cpi-groovy-tester.jar` plus `target/lib/` with its dependencies
(deliberately not a fat/uber JAR — see
[Differences from the real CPI runtime](#differences-from-the-real-cpi-runtime)
for why).

## Option 1 — command line

**macOS/Linux**
```bash
./run.sh run --config testdata/config.json --outdir out
```

**Windows**
```bat
run.bat run --config testdata\config.json --outdir out
```

or without a config file at all:

**macOS/Linux**
```bash
./run.sh run \
  --script scripts/OrderToXml.groovy \
  --body   testdata/order.json \
  --property debugLoggingEnabled=true \
  --property currency=USD \
  --out    out/result.xml
```

**Windows**
```bat
run.bat run ^
  --script scripts\OrderToXml.groovy ^
  --body   testdata\order.json ^
  --property debugLoggingEnabled=true ^
  --property currency=USD ^
  --out    out\result.xml
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
Full option list: `./run.sh --help` (macOS/Linux) or `run.bat --help` (Windows).

## Option 2 — web UI

**macOS/Linux**
```bash
./ui.sh                 # http://localhost:8899, opens a browser tab
./ui.sh --port 9000 --no-open
```

**Windows**
```bat
ui.bat                  :: http://localhost:8899, opens a browser tab
ui.bat --port 9000 --no-open
```

Script on the left; Input/Headers/Properties top right; Output/Console/Result
props/Attachments bottom right. `⌘`/`Ctrl`+`Enter` runs the script from
anywhere on the page.

- **Code editor** with Groovy syntax highlighting, line numbers, bracket
  matching and a live [code inspector](#code-inspector). CodeMirror is
  bundled inside the JAR rather than pulled from a CDN, so the UI works with
  no internet connection and nothing phones home.
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

## Code inspector

While you type, the UI checks the script **without running it** and reports
problems with line and column — as a dot in the gutter, a dotted underline in
the code, and a list below the editor. Clicking an entry jumps to the spot.
The `◉` button in the script bar turns it off; the choice is remembered.

Two sources feed it:

**The Groovy compiler itself**, up to the `CANONICALIZATION` phase. That
covers syntax errors, unresolvable imports and classes, and the compiler
warnings — everything a real run would trip over as well. No bytecode is
produced.

**CPI-specific rules** on the AST, i.e. the traps a plain Groovy compiler
cannot know about:

| Rule | Reports |
|---|---|
| `entryMissing` | no `processData(Message)` method — the runner has nothing to call |
| `entryReturnsVoid` | entry method declared `void` instead of returning the Message |
| `entryParamType` | entry method does not take a Message |
| `apiVersionMix` | both Message generations imported at once |
| `bodyReadTwice` | untyped body read more than once — in CPI it is an `InputStream` and is empty from the second read on |
| `bodyUntyped` | `getBody()` without a type instead of `getBody(java.lang.String)` |
| `setBodyMissing` | the script never sets a body |
| `messageLogNullCheck` | MessageLog used without a null check — `getMessageLog(..)` returns `null` when tenant logging is off |
| `unknownProperty` / `unknownHeader` | name is read but not set in the current test case |
| `hardcodedSecret` | password/token as a literal in the code instead of the secure store |
| `forbiddenSleep` / `forbiddenExit` / `fileAccess` | `Thread.sleep`, `System.exit`, `java.io.File` — not allowed or pointless on the tenant |
| `emptyCatch` | empty `catch` block, the error vanishes without a trace |
| `printlnUsage` | `println` reaches nobody on the tenant |

The rules know about the **current test case**: a property the script reads
that is neither listed under Properties nor set by the script itself is
flagged as a hint — and disappears the moment you add it.

The rules are deliberately conservative: better to miss a trap than to cry
wolf constantly, because a linter nobody believes gets switched off.

> The check compiles up to `CANONICALIZATION`, which runs AST transformations
> — arbitrary code. `/api/check` therefore sits behind the same protection as
> `/api/run`; "only checking" is not the same as "harmless" here.

## Trace: stepping through a run

The **⏱ Trace** button runs the script and records every step along the way.
Afterwards you scrub back and forth through the run in the Trace tab and see
what the script was doing at each point — without adding a single `println`
or debug log to find out.

Recorded per executed statement:

- the **line number**, highlighted and scrolled to in the editor
- the **local variables visible at that point**, with their values
- the **changes to the message** — property, header or body, each as before → after
- the **console output produced by that step**

A **"only steps that change something"** filter skips the repetitions, which
in a loop are the bulk of it. When the run blows up, the recording ends
exactly where it stopped, with the variable values from just before — which
is usually where the answer is.

Only changes are stored, not the full state per step, and a step limit
(default 5000) keeps a big loop from producing a recording many times the
size of the actual data. If the limit is hit, the display says how many
steps really ran and from where on something is missing.

**How it works.** A `CompilationCustomizer` (`TraceTransformer`) injects a
call to `TraceRecorder` in front of every statement, during the compiler's
`CONVERSION` phase. At that point the AST is parsed but variables are not yet
resolved, so the injected code goes through the same phases as
hand-written code and needs no special treatment. Which variables are in
scope at a given point is tracked by the transformer itself — a variable
declared further down must not appear, or the generated code would fail with
a `MissingPropertyException`.

That gives real variable inspection **without a second process and without
JDWP/JDI**. Closure bodies (including the implicit `it`), loop variables and
catch variables are covered too. Statements are only ever instrumented
*before*, never after — otherwise the last statement of a method would no
longer be last, and Groovy's implicit return value would change.

Without ⏱ Trace nothing is instrumented: a normal run is byte-for-byte what
it was before.

## Network access

The server binds to `127.0.0.1` only by default and restricts file
load/save to the project directory. To reach it from another machine:

**macOS/Linux**
```bash
./ui.sh --bind lan          # binds 0.0.0.0
```

**Windows**
```bat
ui.bat --bind lan          :: binds 0.0.0.0
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
one-time prompt asking whether `java` may accept incoming connections. On
Windows with Defender Firewall enabled, expect the same kind of one-time
prompt for `java.exe` (or `javaw.exe`); allow it only on private/trusted
networks.

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

**macOS/Linux**
```bash
./build.sh              # Groovy 4  (default, matches Script Version 2.x)
./build.sh -Pgroovy3    # Groovy 3  (closer to the old generation's 2.4)
```

**Windows**
```bat
build.bat              :: Groovy 4  (default, matches Script Version 2.x)
build.bat -Pgroovy3    :: Groovy 3  (closer to the old generation's 2.4)
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
build.sh / run.sh / ui.sh   build / CLI / web UI entry points (macOS/Linux)
build.bat / run.bat / ui.bat  same, for Windows
smoke.sh / smoke.bat        runs every bundled example and compares the output
scripts/                    Groovy scripts under test
testdata/                   input files and test case configs
  expected/                 expected output for the smoke test
examples/                   a larger, more realistic example (see its own README)
out/                        results (created on demand)
src/main/java/com/sap/...   CPI API mocks (clean-room reimplementation)
src/main/java/de/cpitester/ runner, code inspector, CLI, web server
src/main/resources/ui/      web UI (one self-contained HTML file)
  assets/codemirror/        bundled editor, MIT-licensed (no CDN, works offline)
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

### Third-party components

| Component | License | Where |
|---|---|---|
| [CodeMirror 5](https://codemirror.net/5/) — the editor in the web UI | MIT | committed under `src/main/resources/ui/assets/codemirror/`, license text next to it |
| Apache Groovy (`groovy`, `-json`, `-xml`, `-sql`, `-templates`, `-dateutil`) | Apache-2.0 | Maven dependency |
| Apache Commons Lang3, Commons IO | Apache-2.0 | Maven dependency |
| json-lib, and what it pulls in (ezmorph, Commons BeanUtils/Collections/Lang/Logging) | Apache-2.0 | Maven dependency |

CodeMirror is committed to this repository rather than loaded from a CDN, so
the UI keeps working without an internet connection and nothing about your
scripts leaves your machine. Its license text sits next to the files.

Everything above is permissively licensed and compatible with this project's
GPL-3.0. `mvn dependency:list` shows the resolved set at any time.

---

<img src="src/main/resources/ui/assets/valcoba-logo.png" alt="Valcoba" height="28">

Originally built for internal use at [Valcoba](https://www.valcoba.com),
your CSV and Quality partner, and shared as open source.
