# MF2 Conformance

This directory owns the shared MF2 test corpus for Mojito and the runtime
libraries.

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
- variables
- Unicode text, argument values, and quoted literals
- Unicode variable names, bidi controls around names, and namespaced identifiers
- escaped braces and backslash
- function annotations and options, including quoted option values containing spaces
- `.input`
- `.local`
- markup open/close/standalone placeholders
- parts output for expression and markup attributes
- exact-match `.match` selectors and catch-all fallback, including
  `:number select=exact`
- cardinal plural category selection for English, French, Russian, Arabic, and
  Japanese fixtures
- ordinal plural category selection for English fixtures

Variable, function, markup, option, and attribute identifiers now have fixture
coverage for Unicode names, edge bidi controls, namespaces, and basic invalid
identifier diagnostics. Deeper identifier edge cases and canonical-equivalence
coverage remain open.

The invalid-source fixtures currently cover:

- dangling escapes
- unsupported placeholders
- unclosed placeholders
- unclosed quoted patterns
- unclosed quoted values in expression tails
- missing match selectors
- invalid variable, function, option, and attribute names

The format-error fixtures currently cover:

- selector/variant key arity mismatches
- missing catch-all fallback variants
- duplicate select variants
- duplicate declarations
