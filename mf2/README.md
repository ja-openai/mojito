# MF2 Foundation

This directory is the working area for Mojito's MessageFormat 2 foundation.

It is intentionally split into conformance data, runtime packages, reference
tools, and shared examples:

- `conformance/`: shared fixtures and schema that every implementation must pass
- `rust/mojito-mf2/`: clean-room Rust parser/runtime crate
- `swift/MessageFormat2/`: native zero-dependency Swift runtime package
- `python/`: zero-dependency Python parser/runtime package
- `java/`: zero-dependency Java runtime package
- `java-icu4j/`: opt-in ICU4J formatter adapter for the Java runtime package
- `kotlin/`: native Kotlin/JVM parser/runtime package
- `javascript/`: zero-dependency JavaScript parser/runtime package
- `go/`: native Go parser/runtime package
- `php/`: native PHP parser/runtime package using the built-in `intl` extension
  for Unicode NFC selector keys
- `reference/`: ICU reference comparison harnesses
- `cldr/`: generated CLDR plural-rule data, experimental number data, and
  relative-time data generators
- `examples/`: shared parser-free catalog demo used by all runtimes
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
- `mojito-mf2`: future Mojito editor, preview, migration, and workflow
  integration

The current implementations mirror those boundaries even before they become
separate published packages. Rust exposes the parser, model, runtime, and
diagnostic surface while keeping CLDR plural rules and locale-key helpers as
crate internals.
Python uses a `pyproject.toml`/`src/mojito_mf2` package layout with source
parsing, formatting, errors, and a compatibility `model` facade; internal
`_locale_key`, `_plural`, and `_cldr_plural_rules` modules back runtime behavior
and conformance tooling but stay outside the stable root API.
JavaScript has a publish-shaped ESM package with source parsing, generated CLDR
plural rules, runtime formatting, parts output, internal locale-key helpers,
public `.d.ts` exports, a small root API, and tools/tests outside the published
runtime files. Go has source parsing, generated CLDR plural rules, runtime
formatting, parts output, internal locale-key helpers, and a real
`github.com/box/mojito/mf2/go` module path; it
uses `golang.org/x/text` for spec-compliant NFC string selection while a
generated zero-dependency normalizer remains an open packaging decision. PHP has
source parsing, generated CLDR plural rules, runtime formatting, parts output,
Composer metadata, and `Mojito\MessageFormat2` autoloading; locale-key and CLDR
helpers plus parser/runtime implementation functions sit under the `Internal`
namespace. It relies on PHP `intl` for spec-compliant NFC string selection. Kotlin keeps
locale-key and plural-rule helpers internal while exposing idiomatic `Mf2*`
parser, formatter, function registry, model, result, and error types under a
Maven-managed package boundary. Swift keeps locale-key and plural-rule helpers
inside the runtime target while exposing Swift-native `MF2*` parser, formatter,
function registry, model, result, and error types. Java keeps the production artifact under `src/main/java`;
conformance runners, demos, benchmarks, fixture JSON loading, and demo-only
functions live under `src/test/java` so they do not ship in the library jar. The locale-key
helpers are string-only; generated plural rules keep string APIs and do not
depend on a rich locale object.

Across app-facing package roots, the stable API vocabulary is parse, format
result, parts result, function registry defaults, recovery callbacks, and
MF2-prefixed public diagnostics. Formatting APIs recover locally with Unicode
MF2 visible fallback values by default and return collected errors to the caller;
application/catalog layers own logging, metrics, dev-mode throwing, and
whole-message fallback policy. Lower-level helpers remain available from
language-appropriate submodules or package-internal utilities.

## Runtime Function Registry Layers

Use the same registry split in every runtime. The names may follow local
language conventions, but the meaning must stay stable:

- `portable`: production runtime behavior that is both genuinely useful and
  implementable consistently across languages without platform formatter
  dependencies. Today that means `:string`, `:offset`, CLDR plural/category
  selection, exact numeric selector matching, unlocalized `:number`, `:integer`,
  and `:percent` formatting, and related shared validation. The numeric
  formatters are production-safe, but they are intentionally not locale-pretty:
  they do not localize digits, grouping, separators, or unit/currency display.
  Do not add pass-through, no-op, or incomplete date/time/currency behavior here.
- `defaults`: the normal app-facing registry. It should choose the best
  production behavior available on that platform. In Java this means starting
  from portable behavior and adding JDK-backed number, percent, integer,
  currency, date, time, and datetime formatting. In runtimes that do not yet
  have a real platform adapter, do not describe dependency-free shims as
  platform-backed behavior.
- Platform adapters: optional modules/classes that add host-backed formatting
  such as JDK/Android, JavaScript `Intl`, Swift Foundation, Python Babel, ICU4X,
  Go locale packages, or PHP Intl. These can override portable handlers, but
  they must be explicit about their dependency and formatting contract.
- Fixture or sample registries: conformance, demo, benchmark, and catalog
  examples may need simplified deterministic handlers so shared fixtures can run
  without external dependencies. Keep those under test/example tooling and name
  them as fixture/sample/stub helpers. They must not ship as production
  `portable` behavior or silently become `defaults`.
- Unlocalized numeric formatters are production runtime code because they make
  common placeholder and plural messages usable without a platform dependency.
  Other dependency-free shims belong under test/demo tooling unless they are
  genuinely complete cross-language behavior.

Current platform adapter status:

- JavaScript has an explicit `Intl` registry subpath for locale-pretty number,
  percent, integer, currency, date, time, datetime, and relative-time formatting.
- Python keeps core stdlib-only and exposes an optional `mojito_mf2.babel`
  registry for Babel-backed number, percent, integer, currency, date, time,
  datetime, and relative-time formatting.
- Swift exposes an explicit `MF2FunctionRegistry.foundation` registry for
  Foundation-backed number, percent, integer, currency, date, time, and datetime
  formatting. On Apple platforms it also supports relative time via
  `RelativeDateTimeFormatter`; non-Apple Swift keeps relative time deferred
  unless Foundation support is validated there.
- Java and Kotlin defaults use JDK-backed number, percent, integer, currency,
  date, time, and datetime formatting. The JDK does not provide a public
  ICU-style relative-time formatter, so production relative time stays out of
  those defaults. Java also has an explicit `java-icu4j` adapter artifact for
  ICU4J-backed number, percent, integer, currency, date, time, datetime, and
  relative-time formatting.
- Rust exposes an optional `icu4x` feature and `FunctionRegistry::icu4x()`
  registry for ICU4X-backed number, integer, date, time, and datetime
  formatting. ICU4X currency, percent/unit patterns, and relative-time support
  are not stable enough in the currently used crates, so those functions are
  not faked in the adapter.
- Go and PHP currently keep relative time out of production registries unless
  an explicit platform adapter is added.

Java/Kotlin relative time should not be added to the core jar by copying the
sample catalog function into `defaults`. The generated all-locale CLDR
relative-time artifact is about 2.9 MB raw, or about 123 KB gzip-compressed,
which is reasonable for an explicit adapter/resource artifact but too large to
hide in the tiny core runtime. The clean production path is a separate opt-in
CLDR/ICU adapter with a clear resource strategy: full server data, generated
locale subsets for embedded clients, or host ICU where available.

Unicode MF2 uses the registry concept for both formatters and selectors, so
registration APIs should make the two paths explicit where the language allows
it. A formatter-only function such as `:currency` should not register a fake
selector just to suppress an error; selector diagnostics belong in the
formatter/selection path.

Runtime audit checklist:

- Production package exports `portable` and `defaults` only when those names
  match the contracts above.
- Production `portable` code contains no fake platform formatting, no
  pass-through date/time/currency shims, and no fixture-only handlers. Its
  numeric formatting is explicitly unlocalized.
- Production `defaults` is either a real platform-backed registry or is
  documented honestly as the current production registry without claiming
  platform formatting it does not perform.
- Languages with real platform formatting do not ship duplicate minimal
  formatter boilerplate in runtime code; fixture shims stay under test/demo
  paths.
- Fixture, conformance, benchmark, and demo helpers are outside the production
  artifact/package boundary where the language supports that split.
- Public docs and examples say which registry is used and why.
- Tests cover portable selection and unlocalized numeric behavior, platform
  formatting where implemented, and fixture/demo helpers only through test/demo
  entry points.

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
- safe default formatting APIs that return output plus collected runtime errors
  for unresolved variables/selectors, including fallback parts with source
  metadata
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
  selectors across the generated all-CLDR locale set in Rust, Swift, Python,
  Java, Kotlin, JavaScript, Go, and PHP, including shared CLDR category fixtures
  generated from the ICU4J reference harness
- BCP47-first locale-key canonicalization and structural lookup, including
  underscore compatibility and extension stripping for plural and catalog lookup
- structural model validation for duplicate declarations, select variant key
  arity, duplicate variants, required catch-all fallback variants, and missing
  selector annotations, input declaration variable binding, local declaration
  dependency order, and non-empty pattern text parts, plus valid markup kind
  values and missing runtime argument errors

Rust, Swift, Python, Java, Kotlin, JavaScript, Go, and PHP currently parse MF2 source
into the official data model for this slice and run the shared source-to-model,
formatting, parts, fallback, invalid-source, model-validation, and locale-key
fixtures. Rust, Java, JavaScript, Go, and PHP also run the vendored Unicode
MessageFormat WG suite at 461 passed, 0 skipped, 0 not wired.

## V0 Target

The immediate milestone is placeholders plus CLDR plural selection:

- named placeholders
- `.input` selector annotations
- `:number` and `:integer` cardinal/ordinal plural selection
- `:string` exact selection
- parts output for UI-owned markup/link rendering
- expression and markup attributes preserved in parsed/decoded models
- generated CLDR plural rules for every CLDR plural locale by default in the
  production-targeted runtimes
- Rust/Swift/Python/Java/Kotlin formatting from the Unicode MF2 model
- result-oriented formatting from the Unicode MF2 model with visible fallback
  output and collected diagnostics

This is the smallest useful runtime that can already beat gettext for many
product strings while staying small enough for embedded clients.

Run all current checks:

```sh
sh check.sh
```

Run only compile, type, format, lint, and syntax checks:

```sh
sh static_check.sh
```

Run only the shared conformance suite across all current language runtimes:

```sh
sh conformance/check_all_languages.sh
```

Run the vendored Unicode MessageFormat WG suite directly:

```sh
(cd rust/mojito-mf2 && cargo run -- unicode-tests)
(cd java && sh run.sh unicode-tests)
(cd javascript && npm run unicode-tests)
(cd go && go test ./...)
(cd php && php tests/unicode_tests.php)
```

Run the tiny app-facing catalog demos directly:

```sh
(cd rust/mojito-mf2 && cargo run --example translate_demo)
(cd rust/mojito-mf2 && cargo run --example inline_translate_demo)
(cd swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd python && sh run.sh demo)
(cd kotlin && sh run.sh demo)
(cd go && go run ./cmd/demo)
(cd php && php examples/demo.php)
(cd javascript && npm run demo)
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
(cd kotlin && sh run.sh bench)
(cd kotlin && sh run.sh bench-parse)
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

Regenerate the shared CLDR plural category fixtures consumed by every runtime
conformance runner:

```sh
python3 conformance/generate_plural_category_fixtures.py
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
