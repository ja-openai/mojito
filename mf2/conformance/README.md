# MF2 Conformance

This directory owns the shared MF2 test corpus for Mojito and the runtime
libraries.

The official Unicode MessageFormat WG test suite is vendored separately under
`../third_party/message-format-wg/test`. The Rust, Java, JavaScript, Go, and PHP
scoreboard runners read the upstream test shape directly, currently wiring
syntax success/error, bidi syntax, data-model error, `:string`, `:number`,
`:percent`, `:currency`, date/time/datetime validation, `:offset`, `:integer`,
`u:` options, fallback, and pattern-selection checks. Their shared checked-in
baseline lives in `unicode-official-baseline.json`; update it in the same commit
when official pass/skip/not-wired counts intentionally change. All currently
vendored official tests are wired by those scoreboard runners.

## Contract

Fixtures should test these paths:

- `source -> official data model`
- `official data model + args -> output`
- `official data model + args -> fallback output + runtime errors`
- `official data model + args -> runtime error`
- `source -> diagnostics`
- `source -> parts`
- `source -> fallback parts`
- `source -> print -> source/model stability`

The official Unicode MF2 Interchange Data Model schema is stored in
`schema/message.schema.json`.

## Fixture Shape

Source-to-model fixtures use:

```json
{
  "name": "variable-basic",
  "source": "Hello, {$name}!",
  "expectedModel": {
    "type": "message",
    "declarations": [],
    "pattern": [
      "Hello, ",
      {
        "type": "expression",
        "arg": { "type": "variable", "name": "name" }
      },
      "!"
    ]
  },
  "formatCases": [
    {
      "locale": "en",
      "arguments": { "name": "Alex" },
      "expected": "Hello, Alex!"
    }
  ]
}
```

Invalid-source fixtures use stable diagnostic codes:

```json
{
  "name": "unclosed-placeholder",
  "source": "Hello, {$name",
  "expectedDiagnostics": [
    { "code": "unclosed-placeholder" }
  ]
}
```

Format-error fixtures use the official model directly and expect a stable
runtime error code:

```json
{
  "name": "variant-key-count-mismatch",
  "model": {
    "type": "select",
    "declarations": [],
    "selectors": [
      { "type": "variable", "name": "status" },
      { "type": "variable", "name": "count" }
    ],
    "variants": [
      {
        "keys": [{ "type": "literal", "value": "active" }],
        "value": ["Active"]
      }
    ]
  },
  "arguments": { "status": "active", "count": 1 },
  "expectedError": { "code": "missing-select-variant" }
}
```

The fixture schema is intentionally simple so Swift, Python, Rust, Java, Kotlin,
JavaScript, Go, and PHP can all consume it without bespoke tooling.

Function proposal fixtures live under `fixtures/functions`. They are
machine-readable contracts for registry functions that are not part of the MF2
core grammar. `relative-time-duration-v0.json` is currently a draft fixture for
the experimental `:relativeTime` function described in
`../spec/functions/relative-time.md`; `validate_relative_time_fixture.py`
executes the draft unit-selection and CLDR-pattern substitution algorithm
against the generated CLDR data, but the normal runtime conformance runners do
not consume it until the function is implemented.

Unicode micro-runtime fixtures live beside the MF2 message fixtures when they
test reusable CLDR behavior outside the MF2 grammar. `fixtures/number-core`
currently covers the JavaScript, Python, Rust, Java, Kotlin, Swift, Go, and PHP generated-data
number formatters for decimal, integer, percent, and simple currency
formatting, including static expected outputs, dynamic `Intl.NumberFormat` /
JDK `NumberFormat` reference cases, Python/Rust/Go/PHP Node/Intl witnesses, Swift
Foundation `NumberFormatter` reference cases with explicit known difference
reporting, registry integration, and error cases. `fixtures/date-time-core`
currently covers the JavaScript, Python, Rust, Java, Kotlin, Swift, Go, and PHP generated-data
Gregorian date/time formatters for the probe locale set, including static
expected outputs, CLDR semantic skeleton lookup/composition cases across the
probe locale set including the legal date, calendar-period, time, zone, and
composite semantic field-set matrix, explicit `yearMonthDay`/date-time/zone/era/calendar-period
field-set aliases including duplicate-canonicalization errors, semantic column-alignment outputs, semantic
style inference for long/full time zones, option field requirements and bounds, cross-locale hour-cycle and fixed-offset
semantic outputs,
field-style value aliases such as `2Digit`/`twoDigit`/`abbreviated` plus
explicit month/quarter/weekday/day-period width overrides,
era, extended-year, and related Gregorian year skeletons, quarter, stand-alone
quarter, and stand-alone month skeletons including skeleton-only `l`,
week-year/week-of-year/week-of-month skeletons, standalone/local weekday
skeletons including wide/narrow/short width best-fit plus numeric local-day
pattern synthesis, flexible/exact day-period skeletons, single-field numeric
day-of-year/day-of-week-in-month/modified-Julian-day/minute/second/milliseconds-in-day
skeleton synthesis, fractional-second skeleton synthesis, timezone field-family skeleton
matching, and localized UTC
zone presentation cases, lowercase ISO and location-style fixed-offset timezone
cases, hour-cycle override cases, CLDR append-item skeleton fallback cases, plus best-fit field-width skeleton cases, dynamic
`Intl.DateTimeFormat`, Python/Rust/Go/PHP Node/Intl witnesses, JDK
`DateTimeFormatter`, and Swift Foundation `DateFormatter` reference cases where
the checked-in CLDR data and host data agree byte-for-byte, including date-only
Gregorian calendar alias/override, locale-extension, fixed-offset rollover
references, and semantic style alias witnesses against equivalent top-level
`dateStyle`/`timeStyle` options for exact date-only, time-only, and date+time
host matches, registry
integration, explicit known Foundation punctuation/spacing differences, and
unsupported calendar/named-timezone/hour-cycle/skeleton error cases. Skeleton cases stay in
static expected outputs because ECMA-402 does not expose CLDR skeletons
directly.

`generate_plural_category_fixtures.py` uses the ICU4J reference harness and the
generated all-locale CLDR plural data to refresh the `plural-*-cldr-*.json`
source-to-model fixtures. These fixtures are ordinary conformance data: every
runtime formatter must parse the MF2 source and produce the expected CLDR
category through its public format path.

Run every current language conformance runner against the shared source fixtures:

```sh
sh check_all_languages.sh
```

## Current Coverage

The source-to-model fixtures currently cover:

- simple text
- variables, quoted literal placeholders, escaped quoted literal placeholders,
  and unquoted literal placeholders
- Unicode text, argument values, literal placeholders, and preservation of
  canonically equivalent but byte-distinct names
- Unicode variable names, bidi controls around names, and namespaced identifiers
- bidi controls as syntax padding around complex-message declarations, bodies,
  expression operands, markup placeholders, and option/attribute assignments
- escaped braces and backslash
- function annotations and options, including `:number`, `:integer`, `:string`,
  and `:currency`, quoted option values containing spaces, variable-valued
  options, and optional whitespace around `=`
- unregistered custom functions rejected by default formatters
- `.input`, including multiple declarations before a quoted pattern
- `.local`, including chained locals
- markup open/close/standalone placeholders
- parts output for expression attributes and markup options/attributes
- opt-in `bidiIsolation: "default"` string formatting around expression output,
  with `u:dir` overriding isolation direction and parts output preserving raw
  values for UI renderers
- exact-match `.match` selectors and catch-all fallback, including multi-selector
  matching, selector-order priority independent of variant row order,
  `:number select=exact`, `:integer select=exact`, and primitive `:string`
  selector values, exact numeric/category ties where row order decides the
  winner, plus quoted literal variant keys distinct from catch-all `*`;
  `:string` selection normalizes comparison keys to NFC internally without
  mutating the parsed model or formatted output
- fixed numeric variant keys for `:offset` locals, including the common
  `$count` plus `$count - 1` selector shape where the offset selector matches
  fixed keys such as `1`, plural categories such as `one`, and a
  selector-priority case where the raw count row wins over an earlier offset
  row; variable-valued offset options are also fixture-backed so shifted plural
  category selection is not limited to hardcoded deltas
- fallback formatting for unresolved variables and unresolved select selectors,
  including fallback parts with `source` metadata and collected
  `unresolved-variable` errors
- direct and simple indirect selector annotations for `.match`
- cardinal and ordinal plural category selection across every generated CLDR
  plural locale, including generated category fixtures checked by Rust, Swift,
  Python, Java, Kotlin, JavaScript, Go, and PHP

Variable, function, markup, option, and attribute identifiers now have fixture
coverage for Unicode names, edge bidi controls, namespaces, combining marks in
names, canonical-equivalence preservation, and basic invalid identifier
diagnostics. Deeper identifier edge cases remain open.

The invalid-source fixtures currently cover:

- dangling escapes
- unsupported placeholders
- unclosed placeholders
- unclosed quoted patterns
- unclosed quoted values in expression tails and variant keys
- missing match selectors
- missing match variants and variant quoted patterns
- invalid `.match` selector and variant-key adjacency
- source-level variant key count mismatches
- invalid simple-message start and reserved unquoted-literal characters
- invalid variable, function, option, and attribute names
- invalid variable-valued attributes
- duplicate function option names
- duplicate expression and markup attribute names

The format-error fixtures currently cover:

- selector/variant key arity mismatches
- missing catch-all fallback variants
- missing selector annotations
- invalid input declaration variable binding
- empty pattern text
- invalid markup kind
- duplicate select variants
- duplicate declarations
- invalid local declaration dependency order, including self references and
  later-local references through function options
- missing runtime arguments in expressions, locals, and selectors
