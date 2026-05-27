# MF2 Foundation

This directory is the working area for Mojito's MessageFormat 2 foundation.

It is intentionally split into conformance data and implementation prototypes:

- `conformance/`: shared fixtures and schema that every implementation must pass
- `rust/mf2-prototype/`: first clean-room Rust parser/formatter/compiler slice
- `swift/MessageFormat2/`: native zero-dependency Swift runtime starter
- `python/`: zero-dependency Python runtime starter
- `java/`: zero-dependency Java runtime starter for JVM/Kotlin interop
- `javascript/`: zero-dependency JavaScript parser/runtime starter
- `react/`: FormatJS-style React wrapper and Vite demo over the JavaScript core
- `go/`: native Go parser/runtime starter
- `php/`: native PHP parser/runtime starter using the built-in `intl` extension
  for Unicode NFC selector keys
- `reference/`: ICU reference comparison harnesses
- `cldr/`: generated CLDR plural-rule data, experimental number data, and
  relative-time data generators
- `examples/`: shared parser-free catalog demo used by all starter runtimes
- `editor-prototype/`: HTML translator workbench prototype backed by the
  JavaScript parser/runtime in the browser, with optional Rust comparison before
  the Mojito React/LSP surface is built
- `spec/`: project-level drafts for registry functions that are not part of
  the MF2 core grammar
- `third_party/message-format-wg/test/`: vendored Unicode MessageFormat WG
  official tests, consumed directly by the Rust, Java, JavaScript, Go, and PHP
  `unicode-tests` scoreboard runners

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
and a compatibility `model` facade. JavaScript has source parsing, generated
CLDR plural rules, runtime formatting, parts output, and locale-key helpers as
ES modules. Go has source parsing, generated CLDR plural rules, runtime
formatting, parts output, locale-key helpers, and uses `golang.org/x/text` for
spec-compliant NFC string selection while a generated zero-dependency normalizer
remains an open packaging decision. PHP has source parsing, generated CLDR
plural rules, runtime formatting, parts output, and locale-key helpers; it
relies on PHP `intl` for spec-compliant NFC string selection. The React wrapper
stays thin: catalog parsing, string hooks, parts hooks, component rendering,
HTML `<bdi>` isolation for rich output, independent preview direction, parsed
contract display, and demo scenario rendering all delegate to the JavaScript
core. Swift has `Model`, `Formatter`, generated plural rules, `PluralRules`,
`Locale`, and `Errors` files inside the runtime target. Java keeps the
production artifact under `src/main/java`; conformance runners, demos,
benchmarks, fixture JSON loading, and demo-only functions live
under `src/test/java` so they do not ship in the library jar. The locale-key
helpers are string-only; generated plural rules keep string APIs and do not
depend on a rich locale object.

## Runtime Optimization Notes

Correctness and simple architecture come first, but MF2 runtimes should make
common product strings cheap. Small compiled fast paths are acceptable when they
are isolated from the generic model semantics and measured with both speed and
memory smoke tests.

Current guidance:

- Prevalidate or compile once where the host language can keep a message object.
- Keep `format()` string-only paths separate from `formatToParts` when that
  avoids avoidable part allocations.
- Start with simple patterns: literal text, markup stripped from string output,
  and unannotated literal/variable expressions.
- Use weak/owned caches so compiled helpers do not outlive the parsed model or
  catalog entry.
- Do not duplicate behavior by special-casing fixtures; unsupported complex
  messages must fall back to the generic runtime path.
- Track memory alongside throughput. The JavaScript simple-message fast path is
  intentionally a small `WeakMap` cache, measured at roughly hundreds of bytes
  per retained model in a stress smoke, with near-zero per-call heap growth.

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
- formatter function registries in Rust/Swift/Python/Java; callbacks receive a
  rendered string plus the raw runtime value (`serde_json::Value`, `MF2Value`,
  Python object, or Java `Object`) so demos and app-specific functions can avoid
  reparsing display text
- Rust validation-only `:date`, `:time`, and `:datetime` built-ins that enforce
  simple ISO date/datetime operand shape without claiming locale formatting
- Rust `:number` built-in formatting for strict decimal operands, fallback
  errors for malformed operands, `minimumFractionDigits`, and exact numeric
  selection semantics used by the official tests
- Rust `:percent` built-in formatting for strict decimal operands, basic
  fraction-digit options, malformed-operand fallback, and percent plural
  selection semantics used by the official tests
- Rust validation-backed `:currency` built-in formatting for numeric operands
  with a required or inherited currency option, plus non-selector errors used
  by the official tests
- Rust `:offset` built-in for integer add/subtract formatting and exact
  selection, including inherited `signDisplay=always` preservation
- Rust `:integer` built-in formatting that truncates numeric operands, reports
  invalid selector options, and supports exact selection of the formatted value
- expression and markup attributes in the data model, including quoted attribute
  values containing spaces
- `.input` and `.local` declarations, including multiple inputs and chained locals
- markup placeholders, stripped from string output for now
- `format_to_parts` / `formatToParts` output for text, expression output, and
  markup boundaries, preserving expression attributes plus markup options and
  attributes for UI renderers
- fallback formatting APIs that return output plus collected runtime errors for
  unresolved variables/selectors, including fallback parts with source metadata
- opt-in `default` bidi isolation for string formatting, wrapping expression
  output in Unicode FSI/PDI, with `u:dir` selecting LRI/RLI/FSI when present
  while keeping parts output raw
- exact-match `.match` selectors with catch-all fallback, including
  multi-selector matching,
  `:number select=exact`, `:integer select=exact`, and quoted literal variant
  keys such as `|*|` that remain distinct from catch-all `*`
- Rust selector function hooks for custom `.match` functions; the official
  Unicode pattern-selection runner uses test-only selector functions and
  best-match ranking plus resolved-value metadata propagation through inputs
  and locals without changing default production functions
- `:string` selector comparison and duplicate-variant validation use NFC
  internally while preserving model literals and formatted strings byte-for-byte
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

Rust, Java, JavaScript, Go, and PHP currently parse MF2 source into the official data
model for this slice and run the vendored Unicode MessageFormat WG suite at
461 passed, 0 skipped, 0 not wired. Swift and Python currently consume the
expected official data model from fixtures and format it. They do not parse MF2
source yet.

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
- fallback formatting from the Unicode MF2 model without changing the strict
  `format()` error contract

This is the smallest useful runtime that can already beat gettext for many
product strings while staying small enough for embedded clients.

Run all current checks:

```sh
sh check.sh
```

Run the vendored Unicode MessageFormat WG suite directly:

```sh
(cd rust/mf2-prototype && cargo run -- unicode-tests)
(cd java && sh run.sh unicode-tests)
(cd javascript && npm run unicode-tests)
(cd go && go test ./...)
(cd php && php tests/unicode_tests.php)
```

Run the first MF2 editor prototype:

```sh
node editor-prototype/server.mjs
```

Open `http://127.0.0.1:8788/`.

Run the tiny app-facing catalog demos directly:

```sh
(cd rust/mf2-prototype && cargo run --example translate_demo)
(cd rust/mf2-prototype && cargo run --example inline_translate_demo)
(cd swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd python && python3 examples/translate_demo.py)
(cd go && go run ./cmd/demo)
(cd php && php examples/demo.php)
(cd javascript && npm run demo)
(cd react && npm install && npm run dev)
(cd java && sh run.sh demo)
(cd java && sh run.sh inline-demo)
(cd java && sh run.sh showcase)
```

Run a simple cross-language format benchmark:

```sh
sh perf/compare.sh
```

Run JavaScript-specific speed and memory smoke checks:

```sh
(cd javascript && npm run bench:format)
(cd javascript && npm run bench:parse)
(cd javascript && npm run bench:plural)
(cd react && npm run bench:render)
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

Validate generated CLDR relative-time data for a future optional
`:relativeTime` registry function:

```sh
(cd cldr && sh validate_relative_time_data.sh)
(cd conformance && python3 validate_relative_time_fixture.py)
```

Run ICU reference comparison:

```sh
sh reference/icu4j/run.sh compare ../../conformance/fixtures/source-to-model
sh reference/icu4cxx/run.sh compare ../../conformance/fixtures/source-to-model
(cd reference/messageformat-js && npm install && npm run bench)
```

The reference comparison intentionally reports unsupported cases and mismatches
instead of treating ICU as automatically canonical for every behavior.
