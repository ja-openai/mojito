# mf2-prototype

Clean-room Rust prototype for Mojito's MF2 foundation.

This is not a production formatter yet. It is a deliberately narrow first slice
that proves the repo shape:

- parse simple MF2 source into the official Interchange Data Model shape
- format the model with string arguments
- run shared conformance fixtures
- report a scoreboard against the vendored Unicode MessageFormat WG official
  tests, guarded by `../../conformance/unicode-official-baseline.json`
- emit stable diagnostic codes

## Shape

The crate mirrors the intended production packages as modules:

- `parser`: source-to-model parser for the supported MF2 slice
- `model`: official Unicode MF2 Interchange Data Model structs
- `runtime`: parser-free model formatter and selector matching
- `cldr`: runtime wrapper around generated CLDR plural-rule code
- `locale_key`: string-only catalog lookup helpers; plural rules do not depend on a
  locale object
- `diagnostic`: stable parser diagnostic type and codes

The runtime module does not depend on parser internals. That keeps the embedded
runtime path available even if parser, compiler, LSP, and Wasm tooling grow much
larger around it.

Supported now:

- literal text
- escaped `\{`, `\}`, and `\\`
- variable placeholders of the form `{$name}`
- quoted and unquoted literal placeholders such as `{|Mojito|}` and `{42}`
- Unicode text, argument values, and literal placeholders
- Unicode MF2 variable names, edge bidi controls around names, and namespaced
  identifiers
- quoted pattern bodies for complex messages
- `.input` declarations
- `.local` declarations
- function annotations such as `{$count :number}`
- literal/variable function options in the data model, including quoted values
  containing spaces
- expression and markup attributes, including quoted values containing spaces
- markup placeholders such as `{#link}`, `{/link}`, and `{#br/}`
- `format_model_to_parts_with_locale` for text, expression, and markup boundary
  output, preserving expression/markup attributes for UI renderers
- fallback formatting APIs for spec-style output plus collected runtime errors,
  including fallback parts with source metadata
- opt-in `BidiIsolation::Default` string output around expression values
- structural model validation for duplicate declarations, select variant key
  arity, duplicate variants, and missing fallback variants
- basic `.match` selectors with exact literal keys and `*` fallback, including
  `:number select=exact`
- function registry selector hooks for custom `.match` functions, including
  ranked best-match selection and resolved-value metadata propagation through
  inputs/locals used by the Unicode official test harness
- validation-only `:date`, `:time`, and `:datetime` built-ins for simple ISO
  date/datetime operands; locale-sensitive presentation is still deferred
- `:number` formatting for strict decimal operands, `minimumFractionDigits`,
  malformed-operand fallback, and exact selection semantics
- `:percent` formatting for strict decimal operands, basic fraction-digit
  options, malformed-operand fallback, and plural selection on percent values
- validation-backed `:currency` formatting for numeric operands with a required
  or inherited currency option, plus non-selector errors
- `:offset` for integer add/subtract formatting and exact selection, including
  inherited `signDisplay=always` preservation
- `:integer` formatting that truncates numeric operands, reports invalid
  selector options, and supports exact selection of the formatted value
- cardinal and ordinal plural category matching for number inputs in every
  generated CLDR plural locale

Unsupported for this first slice:

- locale-sensitive number/date/time formatting
- full MF2 Unicode `name` grammar for variables, functions, markup, and options

Run:

```sh
cargo test
cargo run --example translate_demo
cargo run --example inline_translate_demo
cargo run -- conformance ../../conformance/fixtures/source-to-model
cargo run -- unicode-tests
cargo run -- compile ../../conformance/fixtures/source-to-model/variable-basic.json
cargo run -- format-first-case ../../conformance/fixtures/source-to-model/match-string.json
```
