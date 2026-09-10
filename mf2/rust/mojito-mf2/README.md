# mojito-mf2

Clean-room Rust parser/formatter crate for Mojito's MF2 foundation.

The crate is intentionally narrow, but it is packaged as a normal Rust library
with examples, integration tests, and a local CLI runner:

- parse simple MF2 source into the official Interchange Data Model shape
- format the model with string arguments
- run shared conformance fixtures
- report a scoreboard against the vendored Unicode MessageFormat WG official
  tests, guarded by `../../conformance/unicode-official-baseline-core.json`
- emit stable diagnostic codes

## Shape

The crate mirrors the intended production boundary:

- `parser`: source-to-model parser for the supported MF2 slice
- `model`: official Unicode MF2 Interchange Data Model structs
- `formatter`: parser-free model formatter and selector matching
- `diagnostic`: stable parser diagnostic type and codes

Generated CLDR plural rules and string-only locale lookup helpers are formatter
internals, matching the Java package-private helper boundary.

The formatter module does not depend on parser internals. That keeps the
embedded formatter path available even if parser, compiler, LSP, and Wasm
tooling grow much larger around it.

The crate root exposes short app-facing aliases that mirror the other language
packages: `parse_to_model`, `format_message`, `format_message_to_parts`, and
`cardinal_plural_categories`. The plural-category lookup reuses the existing
generated rules and returns `None` for unsupported locales.
Use `FormatOptions` with `format_message_with_options` or
`format_message_to_parts_with_options` for locale, function-registry,
bidi-isolation, and recovery-callback control. Public diagnostics are exposed as
`Diagnostic`. `FunctionRegistry::defaults()` is the normal Rust app registry
and currently matches `FunctionRegistry::portable()`: dependency-free handlers
for `:string`, `:offset`, unlocalized numeric formatting for `:number`,
`:integer`, and `:percent`, plus numeric selectors and CLDR plural matching.
Unsupported functions recover with visible MF2 fallback output and collected
diagnostics.

For locale-pretty formatting, enable the optional ICU4X adapter:

```toml
mojito-mf2 = { version = "0.1.0", features = ["icu4x"] }
```

```rust
let registry = mojito_mf2::FunctionRegistry::icu4x();
let options = mojito_mf2::FormatOptions::new("fr").with_functions(&registry);
```

The ICU4X registry starts from `portable()` and overrides `:number`,
`:integer`, `:date`, `:time`, and `:datetime` with ICU4X-backed formatting.
It intentionally does not register `:currency` or `:relativeTime`: current
ICU4X crates do not provide stable production APIs for those functions. It also
does not override `:percent` yet because `icu_decimal` formats decimal numbers
but not locale-native percent/unit patterns. The portable unlocalized percent
handler remains available. Date/time `full` style is accepted for API parity
and currently maps to ICU4X `long`, because the stable ICU4X static field sets
expose long/medium/short lengths. Date/time formatting accepts `dateStyle`,
`timeStyle`, and `timeZone=UTC`; legacy `length`, `precision`, `dateLength`,
`timePrecision`, and shared `style` aliases are retained. Non-UTC `timeZone`
values return a bad-option diagnostic until the adapter has real time-zone
support.

The public formatter surface is intentionally small:

```rust
let parsed = mojito_mf2::parse_to_model("Hello {$name}");
let model = parsed.model.expect("valid MF2 source");

let result = mojito_mf2::format_message(&model, [("name", "Mojito")])?;
assert_eq!(result.value, "Hello Mojito");
assert!(result.is_ok());
```

For non-default locale, custom functions, bidi isolation, or recovery callbacks,
pass explicit options:

```rust
let options = mojito_mf2::FormatOptions::new("fr")
    .with_functions(&functions)
    .with_bidi_isolation(mojito_mf2::BidiIsolation::Default);
let arguments = mojito_mf2::Arguments::new()
    .with("name", "Mojito")
    .with("count", 2);
let result = mojito_mf2::format_message_with_options(&model, &arguments, &options)?;
```

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
- `format_message_to_parts` for text, expression, and markup boundary output,
  preserving expression/markup attributes for UI renderers
- result-based formatting APIs for spec-style fallback output plus collected
  formatting errors, including fallback parts with source metadata
- opt-in `BidiIsolation::Default` string output around expression values, with
  `u:dir` selecting LRI/RLI/FSI when present
- structural model validation for duplicate declarations, select variant key
  arity, duplicate variants, and missing fallback variants
- basic `.match` selectors with exact literal keys and `*` fallback, including
  `:number select=exact`
- function registry selector hooks for custom `.match` functions, including
  ranked best-match selection and resolved-value metadata propagation through
  inputs/locals used by the Unicode official test harness
- function callbacks receive both the rendered string value and the typed
  `ArgumentValue` operand so app functions can distinguish numbers, booleans,
  nulls, and strings without reparsing display text
- unlocalized `:number` formatting for strict decimal operands,
  `minimumFractionDigits`, malformed-operand fallback, and exact selection
  semantics
- unlocalized `:percent` formatting for strict decimal operands, basic
  fraction-digit options, malformed-operand fallback, and plural selection on
  percent values
- `:offset` for integer add/subtract formatting and exact selection, including
  inherited `signDisplay=always` preservation
- unlocalized `:integer` formatting that truncates numeric operands, reports
  invalid selector options, and supports exact selection of the formatted value
- cardinal and ordinal plural category matching for number inputs in every
  generated CLDR plural locale
- optional ICU4X-backed `:number`, `:integer`, `:date`, `:time`, and
  `:datetime` formatting behind the `icu4x` feature and explicit
  `FunctionRegistry::icu4x()`

Unsupported for this first slice:

- locale-sensitive formatting in the default dependency-free registry
- ICU4X-backed `:percent`, `:currency`, and `:relativeTime` formatting
- full MF2 Unicode `name` grammar for variables, functions, markup, and options

Run:

```sh
cargo test
cargo test --features icu4x
cargo run --example translate_demo
cargo run --example inline_translate_demo
cargo run --features icu4x --example icu4x_demo
cargo run -- conformance ../../conformance/fixtures/source-to-model
cargo run -- unicode-tests
cargo run -- compile ../../conformance/fixtures/source-to-model/variable-basic.json
cargo run -- format-first-case ../../conformance/fixtures/source-to-model/match-string.json
```

### Exact numbers and model interchange

The portable number, integer, percent, and offset functions retain decimal
coefficients as text, with at most 4096 expanded digits and 1000 requested
fraction digits. A minimum greater than the maximum is `bad-option`. Maximum
fraction digits use half-even rounding; integer formatting truncates toward zero
without saturating to a machine integer. JSON numeric lexemes are preserved by
Serde's `arbitrary_precision` feature. ICU4X formatting uses the same operand and
fraction-digit bounds.

Generated CLDR plural selection accepts absolute values at most 9007199254740991
and fractional operands that fit signed 64-bit integers. Unsupported plural
operands report `bad-selector`; portable formatting and `select=exact` preserve
larger exact numbers.

Expressions, functions, variable references, and markup have public constructors.
Serde round-trips preserve the supported semantic model; unknown extension
properties are ignored and are not re-emitted. Formatting validates imported and
directly constructed models before calling functions. Variable bindings compare
canonically equivalent Unicode names. Expression parts continue to serialize
`dir` and also accept `direction` when deserializing.

The `official-bridge` CLI reads JSON-lines requests for the shared upstream
assertion runner. `registry=portable` selects the production portable registry;
`registry=platform` requires the `icu4x` feature and selects production ICU4X.
Only the upstream `test:*` namespace uses test helpers.

Default bidirectional isolation uses pinned CLDR locale direction and private
production-formatter metadata. Numeric output in a known left-to-right locale
needs no extra isolation unless its resolved value requires it. Plain variable
aliases preserve isolation; a new function annotation defaults to `inherit` and
retains direction without forcing isolation. Replacing a
numeric formatter removes that guarantee; custom formatters and unknown locale
directions keep isolation. Public parts JSON remains unchanged.

`u:dir` accepts literal or variable `auto`, `ltr`, `rtl`, and `inherit` values.
Invalid values report `bad-option` and leave the formatted value intact. Function
handlers do not receive `u:dir` through resolved option access; the source model
remains available for inspection.

Long declaration chains share source history, release it iteratively, and
propagate selector annotations in declaration order. Numeric history and inherited
options are memoized only within a format call, for histories with literal options.
Histories with variable options remain uncached.
