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

There is no automated test suite yet (contributions welcome). At minimum,
before submitting a change:

- `./build.sh` succeeds
- `./run.sh run --config testdata/config.json --outdir /tmp/check` produces
  the expected output for the bundled example script
- If you touched the UI, click through the affected feature in a browser

(On Windows, substitute `build.bat` / `run.bat` for the two commands above.)

## Code style

- Match the existing style in the file you're editing rather than
  introducing a new one.
- Comments explain *why*, not *what* — skip comments that just restate the
  code.
- No new external dependencies for the UI (`src/main/resources/ui/index.html`
  stays a single self-contained file, no CDN scripts).

## License

By contributing, you agree your contribution is licensed under the project's
GPL-3.0 license (see `LICENSE`).
