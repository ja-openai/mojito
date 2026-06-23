# mojito-mf2

Clean-room Rust parser/formatter crate for Mojito's MF2 foundation.

The crate is intentionally narrow, but it is packaged as a normal Rust library
with examples, integration tests, and a local CLI runner:

- parse simple MF2 source into the official Interchange Data Model shape
- format the model with string arguments
- run shared conformance fixtures
- report a scoreboard against the vendored Unicode MessageFormat WG official
  tests, guarded by `../../conformance/unicode-official-baseline.json`
- emit stable diagnostic codes

## Shape

The crate mirrors the intended production boundary:

- `parser`: source-to-model parser for the supported MF2 slice
- `model`: official Unicode MF2 Interchange Data Model structs
- `formatter`: parser-free model formatter and selector matching
- `diagnostic`: stable parser diagnostic type and codes

Generated CLDR plural rules, experimental number/date-time data, and
string-only locale lookup helpers are formatter internals, matching the Java
package-private helper boundary.

The formatter module does not depend on parser internals. That keeps the
embedded formatter path available even if parser, compiler, LSP, and Wasm
tooling grow much larger around it.

The crate root exposes short app-facing aliases that mirror the other language
packages: `parse_to_model`, `format_message`, and `format_message_to_parts`.
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

For generated-data formatting without ICU4X, use `number_core_function_registry()`
or `date_time_core_function_registry()`. These experimental registries use
native Rust tables generated from the shared CLDR probe data instead of runtime
JSON. Number core covers decimal, integer, percent, and simple currency
formatting for the probe locale set. Date/time core covers Gregorian
`dateStyle`/`timeStyle` formatting and semantic CLDR skeleton lookup with
`timeZone=UTC`. `RelativeTimeCoreFormatter::new(data)` is the explicit CLDR
adapter for `:relativeTime`; callers decode the generated relative-time JSON
payload they choose to ship and pass it to the prepared formatter or
`relative_time_core_function_registry(data)`.

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
- explicit generated-data number, date/time, and relative-time core helpers,
  including matching direct parts helpers such as
  `format_number_core_to_parts`, `format_date_time_core_to_parts`, and
  `format_relative_time_core_to_parts`
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
- explicit generated-data `number_core`, `date_time_core`, and
  data-explicit `relative_time_core` helpers, with shared fixtures, Node/Intl
  reference checks, registry integration, and CLI benchmarks
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
cargo run -- number-core-bench ../../conformance/fixtures/number-core/cases.json
cargo run -- date-time-core-bench ../../conformance/fixtures/date-time-core/cases.json
cargo run -- relative-time-core-bench ../../conformance/fixtures/functions/relative-time-duration-v0.json
cargo run -- unicode-tests
cargo run -- compile ../../conformance/fixtures/source-to-model/variable-basic.json
cargo run -- format-first-case ../../conformance/fixtures/source-to-model/match-string.json
```
