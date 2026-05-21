# MF2 Foundation

This directory is the working area for Mojito's MessageFormat 2 foundation.

It is intentionally split into conformance data and implementation prototypes:

- `conformance/`: shared fixtures and schema that every implementation must pass
- `rust/mf2-prototype/`: first clean-room Rust parser/formatter/compiler slice
- `swift/MessageFormat2/`: native zero-dependency Swift runtime starter
- `python/`: zero-dependency Python runtime starter
- `java/`: zero-dependency Java runtime starter for JVM/Kotlin interop
- `reference/`: ICU reference comparison harnesses
- `cldr/`: generated CLDR plural-rule data and generators
- `examples/`: shared parser-free catalog demo used by all starter runtimes
- `third_party/message-format-wg/test/`: vendored Unicode MessageFormat WG
  official tests, consumed directly by the Rust `unicode-tests` scoreboard
  runner

The contract is:

```text
MF2 source
  -> parser-specific AST
  -> official MF2 Interchange Data Model
  -> formatter / parts output / compiled runtime model
```

The official data model is the cross-language shape. Language-specific ASTs are
allowed to differ.

## Package Boundaries

The implementation work is deliberately kept dry and separable:

- `mf2-syntax`: source parsing, recovery, diagnostics, spans, and pretty-printing
- `mf2-model`: the official Unicode MF2 Interchange Data Model
- `mf2-runtime`: formatting from the model or a compiled runtime form, with no
  dependency on source parsing
- `mf2-cldr`: generated plural rules and plural-specific locale parent maps
- `mf2-locale-core`: tiny BCP47-first locale-key string helpers and structural lookup;
  richer aliases and locale negotiation stay outside the tiny runtime
- `mf2-compiler`: catalog/source to model or compact runtime output
- `mf2-reference`: comparisons against ICU and other reference implementations
- `mf2-perf`: warm runtime benchmarks, package-size checks, and profiling
- `mojito-mf2`: Mojito editor, preview, migration, and workflow integration

The starter implementations mirror those boundaries even before they become
separate published packages. Rust has `parser`, `model`, `runtime`, `cldr`, and
`diagnostic` modules, with plural selection wired through generated CLDR code.
Python has `formatter`, `plural`, generated plural rules, `locale_key`, `errors`,
and a compatibility `model` facade. Swift has `Model`, `Formatter`, generated
plural rules, `PluralRules`, `Locale`, and `Errors` files inside the runtime
target. Java has a typed model facade, formatter, generated plural rules, and
dependency-free JSON conformance/demo tooling. The locale-key helpers are
string-only; generated plural rules keep string APIs and do not depend on a rich
locale object.

## Current Slice

The current conformance slice covers:

- literal text
- escaped `\{`, `\}`, and `\\`
- variable and literal placeholders such as `{$name}`, `{|ready|}`, escaped
  quoted literals like `{|a\\\{\|\}|}`, and unquoted literals like `{42}`
- Unicode text, argument values, literal placeholders, and preservation of
  canonically equivalent but byte-distinct names
- Unicode MF2 names for variables, including combining marks, plus namespaced
  identifiers for functions, options, attributes, and markup in the Rust/Java
  source parsers
- bidi controls as syntax padding around complex-message declarations, bodies,
  expression operands, markup placeholders, and option/attribute assignments
  in the Rust/Java source parsers
- basic invalid-source diagnostics for malformed variable, function, option,
  and attribute identifiers, malformed match variants, plus duplicate function
  option and attribute names
- function annotations and literal/variable options in the data model, including
  quoted option values containing spaces, variable-valued options, and optional
  whitespace around `=`
- formatter function registries in Rust/Swift/Python/Java; default runtimes
  pass through the current standard-function slice, while demos register a
  narrow dependency-free `:currency` function outside the core formatter
- expression and markup attributes in the data model, including quoted attribute
  values containing spaces
- `.input` and `.local` declarations, including multiple inputs and chained locals
- markup placeholders, stripped from string output for now
- `format_to_parts` / `formatToParts` output for text, expression output, and
  markup boundaries, preserving expression attributes plus markup options and
  attributes for UI renderers
- opt-in `default` bidi isolation for string formatting, wrapping expression
  output in Unicode FSI/PDI while keeping parts output raw
- exact-match `.match` selectors with catch-all fallback, including
  multi-selector matching,
  `:number select=exact`, `:integer select=exact`, and quoted literal variant
  keys such as `|*|` that remain distinct from catch-all `*`
- direct and simple indirect selector annotation validation for `.match`
- cardinal and ordinal plural category selection for `:number` and `:integer`
  selectors across the generated all-CLDR locale set
- BCP47-first locale-key canonicalization and structural lookup, including
  underscore compatibility and extension stripping for plural and catalog lookup
- structural model validation for duplicate declarations, select variant key
  arity, duplicate variants, required catch-all fallback variants, and missing
  selector annotations, input declaration variable binding, local declaration
  dependency order, and non-empty pattern text parts, plus valid markup kind
  values and missing runtime argument errors

Rust and Java currently parse MF2 source into the official data model for this
slice. Swift and Python currently consume the expected official data model from
fixtures and format it. They do not parse MF2 source yet.

## V0 Target

The immediate milestone is placeholders plus CLDR plural selection:

- named placeholders
- `.input` selector annotations
- `:number` and `:integer` cardinal/ordinal plural selection
- `:string` exact selection
- parts output for UI-owned markup/link rendering
- expression and markup attributes preserved in parsed/decoded models
- generated CLDR plural rules for every CLDR plural locale by default
- Rust/Swift/Python/Java formatting from the Unicode MF2 model

This is the smallest useful runtime that can already beat gettext for many
product strings while staying small enough for embedded clients.

Run all current checks:

```sh
sh check.sh
```

Run the tiny app-facing catalog demos directly:

```sh
(cd rust/mf2-prototype && cargo run --example translate_demo)
(cd rust/mf2-prototype && cargo run --example inline_translate_demo)
(cd swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd python && python3 examples/translate_demo.py)
(cd java && sh run.sh demo)
(cd java && sh run.sh inline-demo)
```

Run a simple cross-language format benchmark:

```sh
sh perf/compare.sh
```

Run process-level RSS and CPU-time profiling:

```sh
sh perf/profile.sh rss
```

The benchmark compares warmed runtime formatting only. It does not include
parser, JSON/model loading, process startup, or package build time.

Run isolated all-locale plural-rule validation against ICU4J:

```sh
(cd cldr && sh validate_plural_rules.sh)
```

Run ICU reference comparison:

```sh
sh reference/icu4j/run.sh compare ../../conformance/fixtures/source-to-model
sh reference/icu4cxx/run.sh compare ../../conformance/fixtures/source-to-model
```

The reference comparison intentionally reports unsupported cases and mismatches
instead of treating ICU as automatically canonical for every behavior.
