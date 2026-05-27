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
diagnostics.

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
