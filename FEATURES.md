# Feature overview

Everything the CPI Groovy Tester does, sorted by area. For how-to
instructions see [README.md](README.md), for the security model
[SECURITY.md](SECURITY.md).

*[Deutsche Version](FEATURES.de.md)*

## Core idea

- Runs real SAP Cloud Integration Groovy scripts **unchanged** — including the
  original import (`com.sap.gateway.ip.core.customdev.util.Message` or
  `com.sap.it.script.v2.api.Message`). No rewriting, no stubbing things out by
  hand.
- **Both CPI script generations** are supported and detected automatically:
  Script Version 1.x (Groovy 2.4) and Script Version 2.x (Groovy 4). The runner
  reads the signature of `processData(..)` and picks the matching Message class
  — no manual switching.
- File-based input; file-based and/or UI-based output.
- Runs entirely locally: no network calls, no telemetry.

## Emulated CPI APIs

| Class | Provides |
|---|---|
| `com.sap.gateway.ip.core.customdev.util.Message` (v1) | body, headers, properties, attachments, `getBody(Class)` for String/byte[]/InputStream/Reader |
| `com.sap.it.script.v2.api.Message` (v2) | identical implementation, shared base `MessageSupport` |
| `MessageLog` / `messageLogFactory` | attachments, custom header properties, typed properties |
| `ITApiFactory` + `SecureStoreService` | credentials from the `credentials` section of the test configuration |

Also on the classpath, as in real CPI: `groovy-json`, `groovy-xml`,
`groovy-sql`, `groovy-templates`, `json-lib` (`net.sf.json.*`),
`commons-lang3`, `commons-io`.

All of these are a **clean-room reimplementation** of the publicly observable
API surface — no SAP source code.

## Groovy runtime

- Chosen at build time: Groovy 4 (default, matching Script Version 2.x) or
  Groovy 3 via the Maven profile `-Pgroovy3` (closer to the old 2.4
  generation, since 2.4 itself no longer runs on Java 17).
- No fat/uber JAR: a slim JAR plus a `lib/` folder via the manifest classpath,
  so that the separate `ExtensionModule` files of `groovy-dateutil`,
  `groovy-xml` and `groovy-sql` don't overwrite each other (which would break
  `Date.parse(..)`, among others).
- The detected combination (Groovy version + script generation) is shown after
  every run, in the UI header and in the CLI output.

## Command line (`run.sh` / `run.bat`)

- `run <cmd>` executes a script once and writes the result to file(s).
- Options: `--script/-s`, `--body/-b`, `--config/-c`, `--out/-o`,
  `--outdir/-d`, `--property/-p` (repeatable), `--header/-H` (repeatable),
  `--entry` (entry method), `--encoding`, `--no-messagelog`, `--quiet/-q`.
- Files produced in the output folder: `output.xml`/`.json`/`.txt` (extension
  detected from the content), `properties.json`, `headers.json`,
  `console.log`, `property_<name>.log` for long multi-line properties (debug
  logs, typically), `attachments/NN_*.txt`, plus `error.log` on failure.
- Exit codes: `0` ok, `1` script error, `2` usage error — scriptable and
  CI-friendly.

## Code inspector (checks while you type)

Checks the script in the editor **without running it** and reports problems
with line and column — as a dot in the gutter, a dotted underline in the code
and a list below the editor. Clicking an entry jumps to the spot.

- **Compiler level:** Groovy compiles up to the `CANONICALIZATION` phase — that
  covers syntax errors, unresolvable imports and classes, and the compiler
  warnings, i.e. everything a real run would trip over too. No bytecode is
  produced.
- **CPI rules** on the AST — the traps a plain Groovy compiler cannot know
  about:

  | Rule | Reports |
  |---|---|
  | `entryMissing` | no `processData(Message)` method — the runner has nothing to call |
  | `entryReturnsVoid` | entry method declared `void` instead of returning the Message |
  | `entryParamType` | entry method does not take a Message |
  | `apiVersionMix` | both Message generations imported at once |
  | `bodyReadTwice` | untyped body read more than once — in CPI an `InputStream`, empty from the second read on |
  | `bodyUntyped` | `getBody()` without a type instead of `getBody(java.lang.String)` |
  | `setBodyMissing` | the script never sets a body |
  | `messageLogNullCheck` | MessageLog used without a null check — `getMessageLog(..)` returns `null` when tenant logging is off |
  | `unknownProperty` / `unknownHeader` | name is read but not set in the current test case |
  | `hardcodedSecret` | password/token as a literal in the code instead of the secure store |
  | `forbiddenSleep` / `forbiddenExit` / `fileAccess` | `Thread.sleep`, `System.exit`, `java.io.File` — not allowed or pointless on the tenant |
  | `emptyCatch` | empty `catch` block, the error vanishes without a trace |
  | `printlnUsage` | `println` reaches nobody on the tenant |

- The rules know the **current test case**: a property the script reads that is
  neither listed under Properties in the UI nor set by the script itself is
  flagged as a hint — and disappears the moment you add it.
- Three levels: error (red), warning (orange), hint (blue), with a counter in
  the script bar. Switchable off by button; the choice is remembered.
- Deliberately conservative: better to miss a trap than to cry wolf constantly.
  A linter nobody believes gets switched off.
- Messages are bilingual; the Groovy compiler's own texts stay as they are.

## Trace (a recorded run instead of debug output)

The **⏱ Trace** button runs the script and records every step along the way.
Afterwards the run can be scrubbed back and forth in the Trace tab — you see
what the script is doing at each point, without having to add `println` or a
debug log to find out.

- Captured per executed statement: the **line number**, the **local variables
  visible there** with their values, the **changes to the message state**
  (property/header/body, each as before → after) and the **console output of
  that step**.
- The current line is highlighted and scrolled to in the editor.
- Filter **"only steps that change something"** — inside a loop the rest is
  mostly repetition you would otherwise have to click past.
- On failure the recording ends where it blew up, with the variable values from
  just before. That is usually where the answer is.
- **Changes are recorded, not the full state per step** — otherwise the log
  would be several times the size of the actual data.
- Step limit (5000 by default) against recordings from large loops; when it is
  reached, the display says how many steps really ran and from where on
  something is missing.

**How it works:** a `CompilationCustomizer` (`TraceTransformer`) injects a call
to `TraceRecorder` in front of every statement, during the compiler's
`CONVERSION` phase. At that point the AST is parsed but variables are not yet
resolved — the injected code then goes through the same phases as hand-written
code and needs no special treatment. Which variables are in scope at a given
point is tracked by the transformer itself (a variable declared further down
must not appear, or the generated code runs into a
`MissingPropertyException`).

That makes real variable inspection possible **without a second process and
without JDWP/JDI**. Closure bodies (including the implicit `it`), loop
variables and catch variables are covered too. Statements are only ever
instrumented *before*, never after — otherwise the last statement of a method
would no longer be last, and Groovy's implicit return value would change.

Without ⏱ Trace nothing is instrumented: a normal run is byte-for-byte what it
was before.

## Web UI (`ui.sh` / `ui.bat`)

- **A real code editor** (CodeMirror, bundled in the JAR — no CDN, works
  offline): Groovy syntax highlighting, line numbers, bracket matching and
  closing, active line highlighted, following the light/dark scheme of the rest
  of the UI.
- Script editor with load/save straight from and into the project folder.
- Separate tabs for Input, Headers, Properties (key/value editor with adding
  and removing individual rows).
- Result tabs: Output, Console, result properties, Attachments, Trace — each
  with a count badge.
- **Resizable panels**: draggable dividers between script and the right-hand
  column and between input and output, double-click resets, the layout is
  remembered.
- **Pretty-print** for XML and JSON in the Output tab (auto-detected). Display
  only — Save always writes the unchanged body. XML is formatted on the raw
  text rather than re-serialized through a DOM parser, so entities like
  `&amp;` survive exactly as they were.
- **Copy** button copies the content of the active result tab (including
  pretty formatting) to the clipboard, with a `document.execCommand` fallback
  for access without a "secure context" (e.g. `http://hostname.local`).
- **Bilingual** (German/English), auto-detected from the browser language,
  switchable manually, choice remembered. Extensible to further languages by
  adding an entry to the `I18N` dictionary.
- **Built-in help** (the `?` button): quickstart, config reference, keyboard
  shortcuts, security notes, about/credits — bilingual as well.
- Keyboard: `⌘`/`Ctrl`+`Enter` runs from anywhere on the page, `Tab` in the
  editors indents instead of leaving the field.
- The editing state (script, input, headers, properties, layout, language)
  persists in the browser via `localStorage`.
- Valcoba logo in the header and the About tab.

## Test case configuration (`config.json`)

- Bundles script path, input file, entry method, headers, properties and mock
  credentials for one test case; paths are relative to the config file.
- `messageLogEnabled: false` makes `getMessageLog(msg)` return `null` —
  simulating a CPI log level below "Info".

## Security

- The server binds to `127.0.0.1` only by default; exposing it to the network
  via `--bind lan`/`--bind <address>` forces an access token (generated
  automatically unless `--token`/`--no-token` is given).
- The token has to be passed as `?t=...` or the `X-Tester-Token` header;
  invalid or missing ones get a 403.
- State-changing endpoints (`/api/run`, `/api/save`, `/api/check`) additionally
  require a fixed `X-Tester-Csrf` header — active even in the default
  loopback-only mode, protecting against code execution triggered by another
  website open in the same browser ("localhost CSRF").
- `/api/check` (the code inspector) deliberately sits behind the same
  protection as `/api/run`: compiling up to `CANONICALIZATION` runs AST
  transformations, and those are arbitrary code. "Only checking" is not the
  same as "harmless" here.
- File load/save is strictly confined to the project directory (no escaping
  via path components).
- Request body limit (25 MB) against memory exhaustion.
- Full threat model and vulnerability reporting path in
  [SECURITY.md](SECURITY.md).

## Platform support

- macOS, Linux, Windows — all three tested for real.
- `build.sh`/`run.sh`/`ui.sh` (Unix) and `build.bat`/`run.bat`/`ui.bat`
  (Windows) with identical arguments.
- The Windows scripts check up front whether `java`/`mvn` are on the PATH and
  point to installation instructions in the README where needed, rather than
  passing through a cryptic system error.
- Forward-slash paths work on Windows too thanks to JVM normalization; the
  Windows examples use backslashes only by convention.

## Bundled examples

- **`scripts/OrderToXml.groovy`** (plus a `_v2` variant): a minimal,
  industry-neutral default example — JSON order to XML, showing
  body/header/property/MessageLog usage.
- **`examples/PerPersonToXml.groovy`** (plus a `_v2` variant): a larger, more
  realistic example (a SuccessFactors `PerPerson` export) with time-slice
  selection logic, OData array normalization and nested picklist lookups —
  four test fixtures for different selection cases, all data synthetic.

## Smoke test (`smoke.sh` / `smoke.bat`)

- Runs **every bundled example against every test input** through the CLI — ten
  cases, both script generations, four PerPerson fixtures.
- Compares `output.*`, `properties.json` and `headers.json` against the
  expected files under `testdata/expected/` (about 160 KB). That catches two
  classes of failure: an example that stops running at all, and one whose
  output changed silently. On a mismatch the diff is printed directly.
- `--profiles` builds Groovy 4 and Groovy 3 and checks both. The outputs are
  identical across the two profiles, so one set of expected files covers both.
- `--update` rewrites the expected files after an intended change.
- Not compared: attachments (their filenames contain a timestamp from the run)
  and `console.log` (may contain paths of the executing machine).
- Exit code `0`/`1` — usable directly in CI.

Born from a concrete incident: `MessageLogFactory.getMessageLog(..)` was tied
to the Version 1.x Message class, so every Script Version 2.x script failed —
including the bundled default example. It went unnoticed only because nothing
ran the examples automatically.

## Project quality / open source

- License: GPL-3.0.
- The only third-party component bundled in the frontend is CodeMirror 5 (MIT),
  locally under `src/main/resources/ui/assets/codemirror/` with its license
  text — deliberately no CDN, so the UI works fully without an internet
  connection and nothing phones home.
- Bilingual documentation: `README.md` / `FEATURES.md` (EN),
  `README.de.md` / `FEATURES.de.md` (DE).
- `SECURITY.md`, `CONTRIBUTING.md`, `examples/README.md`.
- **CI** (`.github/workflows/ci.yml`): builds on every push and pull request
  and runs the smoke test — Linux across both Groovy profiles, Windows as its
  own job, so the `.bat` scripts are actually executed rather than assumed to
  work.
- Clear trademark notice: no affiliation with or endorsement by SAP, the mocks
  contain no SAP source code.
- `.gitignore` hardened against build artifacts, IDE files, `.env`/key
  material and local configs with real credentials (`*.local.json`).
