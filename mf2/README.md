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
- `mf2-cldr`: generated plural rules and locale data used by runtimes
- `mf2-locale-core`: tiny BCP47-first locale identifiers and structural lookup;
  richer aliases and locale negotiation stay outside the tiny runtime
- `mf2-compiler`: catalog/source to model or compact runtime output
- `mf2-reference`: comparisons against ICU and other reference implementations
- `mf2-perf`: warm runtime benchmarks, package-size checks, and profiling
- `mojito-mf2`: Mojito editor, preview, migration, and workflow integration

The starter implementations mirror those boundaries even before they become
separate published packages. Rust has `parser`, `model`, `runtime`, `cldr`, and
`diagnostic` modules, with plural selection wired through generated CLDR code.
Python has `formatter`, `plural`, generated plural rules, `locale`, `errors`,
and a compatibility `model` facade. Swift has `Model`, `Formatter`, generated
plural rules, `PluralRules`, `Locale`, and `Errors` files inside the runtime
target. Java has a typed model facade, formatter, generated plural rules, and
dependency-free JSON conformance/demo tooling.

## Current Slice

The current conformance slice covers:

- literal text
- escaped `\{`, `\}`, and `\\`
- variable placeholders such as `{$name}`
- Unicode text, argument values, and quoted literals
- function annotations and literal/variable options in the data model
- `.input` and `.local` declarations
- markup placeholders, stripped from string output for now
- exact-match `.match` selectors with catch-all fallback, including
  `:number select=exact`
- cardinal plural category selection for the initial locale set: `ar`, `en`,
  `fr`, `ja`, `ru` and ordinal selection for the generated locale set
- BCP47-first locale canonicalization and structural lookup, including
  underscore compatibility and extension stripping for plural lookup

Rust currently parses MF2 source into the official data model for this slice.
Swift, Python, and Java currently consume the expected official data model from
fixtures and format it. They do not parse MF2 source yet.

## V0 Target

The immediate milestone is placeholders plus CLDR plural selection:

- named placeholders
- `.input` selector annotations
- `:number` cardinal/ordinal plural selection
- `:string` exact selection
- generated CLDR plural rules for selected locales
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
