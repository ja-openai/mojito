# mf2-prototype

Clean-room Rust prototype for Mojito's MF2 foundation.

This is not a production formatter yet. It is a deliberately narrow first slice
that proves the repo shape:

- parse simple MF2 source into the official Interchange Data Model shape
- format the model with string arguments
- run shared conformance fixtures
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
- Unicode text, argument values, and quoted literals
- quoted pattern bodies for complex messages
- `.input` declarations
- `.local` declarations
- quoted literal expressions such as `{|Mojito|}`
- function annotations such as `{$count :number}`
- literal/variable function options in the data model
- markup placeholders such as `{#link}`, `{/link}`, and `{#br/}`
- basic `.match` selectors with exact literal keys and `*` fallback, including
  `:number select=exact`
- cardinal and ordinal plural category matching for number inputs in the initial
  locale set

Unsupported for this first slice:

- locale-sensitive number/date/time formatting
- `format_to_parts`
- attributes
- nested/selectable function semantics beyond pass-through string output
- full MF2 Unicode `name` grammar for variables, functions, markup, and options

Run:

```sh
cargo test
cargo run --example translate_demo
cargo run --example inline_translate_demo
cargo run -- compile ../../conformance/fixtures/source-to-model/variable-basic.json
cargo run -- format-first-case ../../conformance/fixtures/source-to-model/match-string.json
```
