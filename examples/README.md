# Examples

The default example bundled with the tool (`scripts/OrderToXml.groovy` +
`testdata/order.json` + `testdata/config.json`) is deliberately minimal and
generic — good for a first look at the tool itself, not for CPI Groovy
patterns beyond the basics.

This folder holds a second, larger example that's actually representative of
real CPI work: a SuccessFactors `PerPerson` OData response transformed into a
flat XML export, picking the currently valid employment out of several
overlapping time slices. It demonstrates patterns a minimal example can't:

- selecting the right record out of several overlapping date ranges, by
  priority (current employment > upcoming hire > recent termination)
- normalizing OData's "sometimes an object, sometimes an array" shape
  (`convertToArrayList` / `homogenifyArrayList`)
- picklist-style external-code lookups nested several levels deep
- optional-chain-style null handling (`?.`) through a deeply nested payload
- both script generations side by side: `PerPersonToXml.groovy` (Script
  Version 1.x) and `PerPersonToXml_v2.groovy` (Script Version 2.x, a full
  rewrite in a more idiomatic modern-Groovy style — worth comparing the two
  if you're migrating scripts between generations)

**All data here is synthetic** — fabricated names, fabricated identifiers,
fabricated organizational structure. None of it corresponds to a real person
or a real company.

## Running it

```bash
./run.sh run --config examples/config.json --outdir out
```

Or point the web UI at the files directly (Script: `examples/PerPersonToXml.groovy`,
Input: `examples/PerPerson_sample.json`).

## The four test fixtures

| File | Exercises |
|---|---|
| `PerPerson_sample.json` | the normal case: one current + one past + one future job entry, priority selection picks the current one |
| `PerPerson_kein_treffer.json` | no time slice matches "now" — falls back to the default empty response |
| `PerPerson_ahv_zeitscheiben.json` | two overlapping identifier time slices — checks that the one matching the selected job's date range wins, not just the first one |
| `PerPerson_hiring_not_completed.json` | an employment flagged `hiringNotCompleted` — must be skipped entirely |
