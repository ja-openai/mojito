# MessageFormat2 Kotlin

This is a native Kotlin/JVM parser/formatter package for Mojito's MF2 foundation.

It uses the same official MF2 data-model maps as the other runtimes,
with a Kotlin parser, formatter, generated all-locale CLDR plural rules, parts
output, fallback formatting, conformance runner, demo, and format/parse perf
harnesses. It does not depend on the Java MF2 package.
Locale-key and CLDR plural-rule helpers are internal implementation details,
matching the Java package-private helper boundary.

The stable public API uses idiomatic `Mf2*` names:

- `Mf2Parser.parseToModel`
- `Mf2Formatter.formatMessage`
- `Mf2Formatter.formatMessageToParts`
- `Mf2FunctionRegistry.defaults()` and `Mf2FunctionRegistry.portable()`
- `Mf2FormatResult`, `Mf2PartsResult`, `Mf2ParseResult`, `Mf2ParseDiagnostic`,
  `Mf2RecoveryContext`, and `Mf2Error`

`Mf2FormatResult` and `Mf2PartsResult` expose `ok` and `hasErrors` properties
around collected formatting diagnostics. Formatting recovers with visible
Unicode MF2 fallback values by default. Kotlin callers can override local
recoverable values with named recovery callbacks:

```kotlin
Mf2Formatter.formatMessage(
    model = message,
    arguments = args,
    locale = "fr",
    onMissingArgument = { context -> "[missing ${context.variableName}]" },
    onFormatError = { context -> context.fallbackValue },
)
```

`Mf2FunctionRegistry.defaults()` is the normal Kotlin/JVM app registry. It uses
JDK-backed formatting for `:number`, `:integer`, `:percent`, `:currency`,
`:date`, `:time`, and `:datetime`, plus portable `:string`, `:offset`, numeric
selectors, and CLDR plural matching. `Mf2FunctionRegistry.portable()` remains
dependency-free and unlocalized for size-sensitive or platform-owned hosts.
Unsupported functions recover with visible MF2 fallback output and collected
diagnostics. Date/time formatting accepts `dateStyle`, `timeStyle`, and
`timeZone`, with legacy `length`, `precision`, `dateLength`, `timePrecision`,
and shared `style` aliases retained. The JDK does not expose an ICU-style localized relative-time
formatter, so `:relativeTime` is intentionally not part of the Kotlin default
registry. Use the separate `messageformat2-kotlin-icu4j` adapter when an
application wants ICU4J-backed `:relativeTime` plus richer ICU locale
formatting.

Local conformance, demo, and benchmark entry points stay under
`src/test/kotlin` so they do not ship in the production jar.

No global `kotlinc` install is required. `run.sh` uses Maven and
`kotlin-maven-plugin` to download the Kotlin compiler and standard library into
the Maven cache.

Run the shared fixture conformance check:

```sh
sh run.sh conformance
```

Run the catalog demo:

```sh
sh run.sh demo
```

Run the JDK-backed platform registry demo:

```sh
sh run.sh jdk-demo
```

Run speed smoke checks:

```sh
sh run.sh bench
sh run.sh bench-parse
```

If you want the Maven cache to stay outside your home directory during local
experiments:

```sh
MAVEN_REPO_LOCAL=/private/tmp/mojito-mf2-m2 sh run.sh conformance
```

## Runtime bounds and model ownership

Portable numeric fraction options accept integers from 0 through 1000. A minimum
larger than an explicit maximum returns `bad-option`. Offset decimal expansion is
limited to 4096 digits and out-of-range operands return `bad-operand`. Plural
category selection rejects operands outside the supported integer/fraction range;
exact-key selection does not require a CLDR category.

Compiled models are checked for required semantic fields, discriminator types,
and literal-or-present attributes before formatting. Unknown extension fields are
ignored by formatting. Returned parts do not share mutable semantic attributes or
options with the input model. Markup options retain model references for callers
that resolve them in their rendering layer.

The package includes `LICENSE`, `NOTICE`, and `UNICODE-LICENSE.txt`; JVM JARs place
these notices in `META-INF`.

Long declaration histories use iterative source traversal and a per-format cache for literal-only
numeric/option histories (at most 64 inherited option entries per source). Variable-dependent
histories retain their resolver behavior and are not cached; no process-wide cache retains catalogs.
`u:dir` accepts `ltr`, `rtl`, `auto`, and `inherit`, including resolved string variables. Invalid
values report `bad-option` and preserve the formatted value. The option is hidden from formatter
callback option resolution; a plain alias retains its operand's isolation, while reannotation uses
the default `inherit` unless explicitly overridden.

Function callbacks receive detached, read-only semantic annotation maps, including the annotations
in inherited sources. Mutating them through a `MutableMap` cast throws `UnsupportedOperationException`.
This preserves catalog ownership and cached literal options. Unknown extension payloads and
caller-supplied raw argument objects are outside this semantic copy.

The portable, JDK and ICU4J `:integer` formatters truncate a finite binary numeric operand only
within `-2^63 <= value < 2^63`. Outside that converted numeric range they report `bad-operand`
instead of clamping; integer selectors report `bad-selector`. This is the current JVM integer
conversion contract, not arbitrary-precision integer formatting. Other numeric formatting and
plain argument conversion do not narrow integral values to signed 64-bit integers.
