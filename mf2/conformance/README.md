# MF2 Conformance

This directory owns the shared MF2 test corpus for Mojito and the runtime
libraries.

The official Unicode MessageFormat WG test suite is vendored separately under
`../third_party/message-format-wg/test`. The Rust parser runner reads the
upstream test shape directly via `cargo run -- unicode-tests`, currently wiring
syntax success/error, bidi syntax, and data-model error checks. Its checked-in
baseline lives in `unicode-official-baseline.json`; update it in the same commit
when official pass/skip/not-wired counts intentionally change. Function,
fallback, pattern-selection, parts, and draft `u:` option official tests are
counted as not wired until the corresponding runtime semantics are implemented.

## Contract

Fixtures should test these paths:

- `source -> official data model`
- `official data model + args -> output`
- `official data model + args -> runtime error`
- `source -> diagnostics`
- `source -> parts`
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

## Current Coverage

The source-to-model fixtures currently cover:

- simple text
- variables, quoted literal placeholders, escaped quoted literal placeholders,
  and unquoted literal placeholders
- Unicode text, argument values, literal placeholders, and preservation of
  canonically equivalent but byte-distinct names
- Unicode variable names, bidi controls around names, and namespaced identifiers
- escaped braces and backslash
- function annotations and options, including `:number`, `:integer`, `:string`,
  quoted option values containing spaces, variable-valued options, and optional
  whitespace around `=`
- `:currency` parsing as a custom function annotation, with unregistered custom
  functions rejected by default formatters
- `.input`, including multiple declarations before a quoted pattern
- `.local`, including chained locals
- markup open/close/standalone placeholders
- parts output for expression attributes and markup options/attributes
- opt-in `bidiIsolation: "default"` string formatting around expression output,
  with parts output preserving raw values for UI renderers
- exact-match `.match` selectors and catch-all fallback, including multi-selector
  matching,
  `:number select=exact`, `:integer select=exact`, and primitive `:string`
  selector values
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
- unclosed quoted values in expression tails
- missing match selectors
- missing match variants and variant quoted patterns
- invalid `.match` selector and variant-key adjacency
- source-level variant key count mismatches
- invalid variable, function, option, and attribute names
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
- missing runtime arguments in expressions, locals, and selectors
