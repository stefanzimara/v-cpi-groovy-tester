# Contributing

Thanks for considering a contribution. This is a small, focused project — the
bar for contributing is low, but a few things help keep it maintainable.

## Before you start

For anything beyond a small fix, open an issue first to discuss the approach.
That avoids spending time on a pull request that doesn't fit the project's
scope (a local test harness for CPI Groovy scripts — not a general-purpose
integration platform, not a hosted service).

## Development setup

**macOS/Linux**
```bash
git clone <your fork>
cd cpi-groovy-tester
./build.sh              # Groovy 4 (default)
./build.sh -Pgroovy3     # Groovy 3, for testing Script Version 1.x behaviour
./run.sh run --config testdata/config.json --outdir out
./ui.sh
```

**Windows**
```bat
git clone <your fork>
cd cpi-groovy-tester
build.bat               :: Groovy 4 (default)
build.bat -Pgroovy3     :: Groovy 3, for testing Script Version 1.x behaviour
run.bat run --config testdata\config.json --outdir out
ui.bat
```

Requires JDK 17+ and Maven 3.8+. No other local dependencies.

## Adding to `com.sap.it.script.v2.api.Message` or the v1 equivalent

Both mock classes share their implementation in
`de.cpitester.MessageSupport`. If a script fails with a
`MissingMethodException` for a method that genuinely exists on the real CPI
API, add it there (not separately in both wrapper classes) so both script
generations stay in sync. Please note in the pull request description what
CPI documentation or observed behavior the addition is based on — these
classes are a clean-room reimplementation, not derived from SAP source, and
should stay that way.

## Adding a language to the UI

The UI's translations live in the `I18N` object in
`src/main/resources/ui/index.html`. Add a language code to `LANGUAGES` and a
matching object to `I18N` with the same keys as `en` — nothing else needs to
change; the picker, detection and fallback all read from these two.

## Tests

`smoke.sh` (`smoke.bat` on Windows) runs every bundled example against every
test fixture and compares the output against the expected files checked in
under `testdata/expected/`. It catches both an example that stops running at
all and one whose output silently changed.

```bash
./smoke.sh              # check against the expected files
./smoke.sh --profiles   # build Groovy 4 and Groovy 3 and check both
./smoke.sh --update     # rewrite the expected files after an intended change
```

Before submitting a change:

- `./build.sh` succeeds
- `./smoke.sh` is green — `--profiles` if you touched anything runtime-related
- If you touched the UI, click through the affected feature in a browser

If a change is *supposed* to alter the output, run `./smoke.sh --update` and
include the updated files in the pull request. Review that diff carefully:
an unintended change looks exactly like an intended one there.

There are no unit tests yet (contributions welcome). The most useful place to
start would be `de.cpitester.CodeInspector` — its rules are pure functions
(source in, findings out) and are the part most likely to quietly stop
working.

## Code style

- Match the existing style in the file you're editing rather than
  introducing a new one.
- Comments explain *why*, not *what* — skip comments that just restate the
  code.
- No new external dependencies for the UI. `index.html` stays one
  self-contained file, and anything it loads is bundled under
  `src/main/resources/ui/assets/` — never from a CDN, so the tool keeps
  working without an internet connection.

## License

By contributing, you agree your contribution is licensed under the project's
GPL-3.0 license (see `LICENSE`).
