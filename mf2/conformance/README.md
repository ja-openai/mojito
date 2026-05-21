# MF2 Conformance

This directory owns the shared MF2 test corpus for Mojito and the runtime
libraries.

The official Unicode MessageFormat WG test suite is vendored separately under
`../third_party/message-format-wg/test`. The Rust parser runner reads the
upstream test shape directly via `cargo run -- unicode-tests`, currently wiring
syntax success/error, bidi syntax, data-model error, `:string` function,
`:number`, `:percent`, `:currency`, date/time/datetime validation, `:offset`,
`:integer`, `u:` options, fallback, and pattern-selection checks. Its checked-in baseline lives in
`unicode-official-baseline.json`; update it in the same commit when official
pass/skip/not-wired counts intentionally change. All currently vendored
official tests are wired by the Rust runner.

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

The fixture schema is intentionally simple so Swift, Python, Rust, Java, and
TypeScript can all consume it without bespoke tooling.

Function proposal fixtures live under `fixtures/functions`. They are
machine-readable contracts for registry functions that are not part of the MF2
core grammar. `relative-time-duration-v0.json` is currently a draft fixture for
the experimental `:relativeTime` function described in
`../spec/functions/relative-time.md`; `validate_relative_time_fixture.py`
executes the draft unit-selection and CLDR-pattern substitution algorithm
against the generated CLDR data, but the normal runtime conformance runners do
not consume it until the function is implemented.

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
  matching,
  `:number select=exact`, `:integer select=exact`, and primitive `:string`
  selector values, plus quoted literal variant keys distinct from catch-all `*`;
  `:string` selection normalizes comparison keys to NFC internally without
  mutating the parsed model or formatted output
- fallback formatting for unresolved variables and unresolved select selectors,
  including fallback parts with `source` metadata and collected
  `unresolved-variable` errors
- direct and simple indirect selector annotations for `.match`
- cardinal plural category selection for English, French, Russian, Arabic, and
  Japanese fixtures, including `:integer` selection for English
- ordinal plural category selection for English fixtures, including `:integer`
  selection

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
