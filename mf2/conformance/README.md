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

Inflection release artifacts are validated through
`validate_inflection_release_fixture.py`. It materializes the checked Java
fixture resources into a temporary release-style bundle, then runs the same
manifest-backed validation contract used by the Java/common and native release
gates. The current bundle must contain 35 passing artifacts: compiled JSON
packs, binary M2IF packs, and the Hindi pronoun-agreement sidecar. The wrapper
also pins the manifest/report schema, artifact count, artifact ID/kind/path
pinning, source fixture filename mapping, report summary/status, JSON
object/array shapes, artifact presence, and path containment so malformed,
renamed, missing, swapped, or escaping fixtures cannot pass by preserving the
count, including symlink paths that would resolve outside the bundle.
Bundle source kind/path/source drift diagnostics name the artifact ID and
include expected and actual values before manifest/report validation.
Bundle source artifact specs must contain exactly `artifact_id`, `kind`,
`source`, and `path`, and each spec must be object-shaped.
The manifest top-level keys must be exactly `schema` and `artifacts`; the
report top-level keys must be exactly `schema`, `summary`, and `artifacts`; the
report summary keys must be exactly `artifacts`, `passed`, and `failed`.
Manifest/report object and array shape diagnostics name the release fixture
file.
Manifest/report artifact count and artifact ID/order drift diagnostics name the
release fixture file.
Manifest/report schema, artifact kind/path, materialization, and report status
diagnostics name the release fixture file.
Bundle source and manifest/report required-text diagnostics name the source
inventory row or release fixture artifact row.
Bundle source and manifest/report row-level unexpected-key diagnostics name the
source inventory row or release fixture artifact row when an artifact ID is
available.
Manifest artifact rows must contain exactly `artifactId`, `kind`, and `path`;
passed report rows must contain exactly `artifactId`, `kind`, and `status`.
Artifact-row failure codes are limited to `invalid-release-artifact-path`,
`unreadable-release-artifact`, `invalid-compiled-term-pack-json`,
`invalid-compiled-term-pack-m2if`, and
`invalid-hindi-pronoun-agreement-pack-json`. Malformed manifest/report JSON,
invalid manifest/report UTF-8, manifest schema, manifest shape, duplicate
artifact ID, and report invariant failures are pre-row validation errors, not
additional artifact-row codes.
The generated `release-validation-report.json` is an all-pass fixture contract:
every artifact row must have `status: "passed"`, and passed rows must not
include stale `code` or `message` fields.
The 35 artifacts are a fixed release-fixture inventory drawn from selected V0
grammar slices: 17 compiled JSON packs, 17 M2IF packs, and the Hindi sidecar.
Passing this gate does not mean every locale has runtime inflection coverage or
that standalone runtime packages expose public inflection APIs; it is not
complete locale or grammar coverage.
The selected release artifact locales are `ar`, `da`, `de`, `es`, `he`, `hi`,
`it`, `ml`, `pt`, `ru`, `sr`, `sv`, and `tr`. Metadata/profile-only or
unavailable locales such as `en`, `id`, `ja`, `ko`, `ms`, `nb`, `nl`, `pl`,
`th`, `vi`, `yue`, and `zh` are intentionally excluded from release artifacts
until a product-backed runtime promotion changes the V0 scope.

This is a release artifact contract, not a core MF2 source-to-model fixture, so
it stays out of `check_all_languages.sh`. It is wired into the top-level
`../check.sh`, the Python package gate, and the JavaScript package check:

```sh
python3 validate_inflection_release_fixture.py
(cd ../python && sh run.sh inflection-release)
(cd ../javascript && npm run inflection-release)
```

This shared command remains the default release-fixture surface. The only
package-local `inflection-release` wrappers today are Python
(`sh run.sh inflection-release`) and JavaScript (`npm run inflection-release`).
Those entries are wrapper command surfaces only: they are included in the first
review slice for release-fixture validation, delegate back to this script
instead of package-local runtime code, and are not package-local runtime APIs.
Java/common exposes release validation as API only, with no published CLI/main
entry point. Standalone Java/Kotlin, Swift, Rust, Go, and PHP should keep their
existing conformance or native package commands until a real product caller
needs package-local term rendering. This release check is intentionally about
artifact shape, not a public inflection API.

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
