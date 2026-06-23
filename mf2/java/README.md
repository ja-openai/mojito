# MessageFormat2 Java

Zero-runtime-dependency Java implementation for the MF2 foundation.

Current target:

- consume the official MF2 Interchange Data Model from Java objects loaded from
  JSON by test/demo tooling
- parse MF2 source into the official MF2 Interchange Data Model for the current
  supported fixture slice
- parse quoted and unquoted literal placeholders such as `{|ready|}` and `{42}`
- support Unicode MF2 variable names, edge bidi controls around names, and
  namespaced identifiers in the source parser
- preserve expression and markup attributes in parsed/decoded models, including
  quoted option/attribute values containing spaces
- format the supported shared conformance fixture slice
- expose `formatToParts` for text, expression, and markup boundary output,
  preserving expression/markup attributes for UI renderers
- expose default `format`/`formatMessage` result APIs for spec-style fallback
  output plus collected runtime errors
- support opt-in `Mf2BidiIsolation.DEFAULT` string output around expression
  values
- pass raw host values through the Java function boundary so example functions can
  distinguish typed values such as `LocalDate`, `Instant`, and legacy `Date`
  from their `toString()` output
- reject invalid model structure for duplicate declarations, select variant key
  arity, duplicate variants, and missing fallback variants
- use generated CLDR cardinal and ordinal plural rules
- expose `Mf2NumberCore` as an experimental generated-data number formatter for
  decimal, integer, percent, and simple currency formatting without depending on
  JDK locale data at runtime
- expose `Mf2DateTimeCore` as an experimental generated-data Gregorian
  date/time formatter for UTC product strings without depending on JDK locale
  data at runtime
- expose `Mf2RelativeTimeCore` as an experimental data-explicit relative-time
  formatter; callers decode the generated CLDR relative-time payload they choose
  to ship and pass it to the formatter or opt-in registry
- keep locale-key canonicalization/fallback string-only
- keep the core jar zero-runtime-dependency; ICU4J support lives in the
  separate `mf2/java-icu4j` adapter artifact

`src/main/java` is the library artifact. Local conformance runners, demos,
benchmarks, fixture JSON loading, and example-only functions live under
`src/test/java` so they can use Maven's standard tool/test classpath without
shipping in the production jar.

The stable public API uses the `Mf2*` namespace consistently:

- `Mf2Parser.parseToModel`
- `Mf2Formatter.formatMessage`
- `Mf2Formatter.formatMessageToParts`
- `Mf2FormatOptions.builder()`
- `Mf2FunctionRegistry.defaults()` and `.portable()`
- `Mf2FormatResult`, `Mf2PartsResult`, `Mf2FormattedPart`
- `Mf2ParseResult`, `Mf2ParseDiagnostic`, `Mf2RecoveryContext`, and `Mf2Exception`

`Mf2Message` contains the parsed MF2 model and small convenience methods that
delegate to `Mf2Formatter`. Formatting output lives in top-level result types:
`Mf2Formatter.formatMessage` returns `Mf2FormatResult` with `value()`,
`errors()`, `ok()`, and `hasErrors()`;
`Mf2Formatter.formatMessageToParts` returns `Mf2PartsResult` with `parts()`,
`errors()`, `ok()`, and `hasErrors()`. Individual parts use
`Mf2FormattedPart.Text`, `.Expression`, `.Fallback`, and `.Markup`.
Formatting uses `Mf2FormatOptions`; the builder defaults to locale `en`, and
production integrations should set the active app locale explicitly:

```java
Mf2FormatOptions options = Mf2FormatOptions.builder().locale(userLocale).build();
Mf2FormatResult result = Mf2Formatter.formatMessage(message, arguments, options);
```

By default, formatting recovers with Unicode MF2 visible fallback values such as
`{$name}` while preserving diagnostics in `errors`. Applications can configure
`Mf2FormatOptions.builder().onMissingArgument(...)` and `.onFormatError(...)`
to replace the local fallback value while still collecting the diagnostic.

`Mf2Message` keeps result-oriented convenience methods such as `format` and
`formatToParts`, also using `Mf2FormatOptions` for locale, functions, bidi
isolation, and recovery callbacks.

`Mf2FunctionRegistry.portable()` is the deterministic dependency-free registry
for behavior that is implemented as real shared runtime semantics: string
formatting, offset formatting, CLDR plural matching, numeric selector matching,
and unlocalized `number`, `integer`, and `percent` formatting. The numeric
formatters are production-safe fallbacks, but they are intentionally not
locale-pretty: they do not localize digits, grouping, separators, or unit/currency
display.
`Mf2FunctionRegistry.defaults()` is the public default entry point for the Java
JDK registry. It starts from portable behavior and overrides number,
percent, integer, currency, and date/time formatters with Java JDK-backed
formatting. Date/time formatting accepts `dateStyle`, `timeStyle`, and
`timeZone`, with legacy `length`, `precision`, `dateLength`, `timePrecision`,
and shared `style` aliases retained. The JDK does not expose an ICU-style
localized relative-time formatter, so `:relativeTime` is intentionally not part
of the Java default registry. Use `Mf2RelativeTimeCore.create(data)` or
`Mf2RelativeTimeCore.registry(data)` when a caller wants the generated CLDR
relative-time implementation explicitly, or use the separate
`messageformat2-java-icu4j` adapter when an application wants ICU4J-backed
relative-time plus richer ICU locale formatting.

The conformance and sample demos use test-only fixture/sample registries for
stable dependency-free output. Those shims live under `src/test/java` and are
not part of the library jar.

CLDR plural rules are vendored under `src/main/java` and compiled as normal
package source. Regenerate them from the shared CLDR generator with
`sh ../cldr/update_generated.sh`; CI should run `sh ../cldr/check_generated.sh`
to fail when vendored generated sources are stale. The project has no runtime
dependencies; Maven plugins are build-time only.

`Mf2NumberCore` is the first experimental generated-data number formatter in the
Java package. It uses the tiny `CldrNumberData` table generated by
`../cldr/generator/generate_number_data.py` and supports decimal, integer,
percent, and simple currency formatting for the current probe locale/currency
set. Use `Mf2NumberCore.registry()` when a caller wants the generated-data
number handlers explicitly; `Mf2FunctionRegistry.defaults()` continues to mean
JDK-backed formatting. Direct Java callers can use `format(value)` for default
options or `format(value, options)` with the builder returned by
`Mf2NumberCore.options()`. The shared `number-core` fixture checks static
outputs, error codes, registry integration, and `NumberFormat` reference
behavior.

`Mf2DateTimeCore` is the first experimental generated-data date/time formatter
in the Java package. It uses the tiny `CldrDateTimeData` table generated by
`../cldr/generator/generate_datetime_data.py` and supports Gregorian
`dateStyle`, `timeStyle`, semantic CLDR `skeleton`, shared `style`, legacy
aliases, localized digits, `hourCycle`, and UTC/fixed-offset `timeZone` values
for the current probe locale set. Use `Mf2DateTimeCore.registry()` when a caller
wants the generated-data date/time
handlers explicitly; `Mf2FunctionRegistry.defaults()` continues to mean
JDK-backed formatting. Direct Java callers can use `formatDate(value)`,
`formatTime(value)`, or `formatDateTime(value)` for default options, or the
corresponding `(value, options)` overloads with `Mf2DateTimeCore.options()`.
The shared `date-time-core` fixture checks static outputs, semantic skeleton
outputs, error codes, registry integration, and `DateTimeFormatter` reference
behavior.

`Mf2RelativeTimeCore` is the first experimental data-explicit relative-time
formatter in the Java package. It consumes the generated
`../cldr/generated/relative-time/all/relative_time.json` shape through
`Mf2RelativeTimeCore.Data` or `Mf2RelativeTimeCore.dataFromJson(...)`, uses the
vendored CLDR plural rules for category selection, and formats `:relativeTime`
through `Mf2RelativeTimeCore.registry(data)` without adding runtime
dependencies or bundling all-locale data into the jar. The shared
`relative-time-duration-v0` fixture checks static outputs, error codes, registry
integration, direct parts output, and Node `Intl.RelativeTimeFormat` witnesses.
Direct Java callers can use instance `format(value)` and `formatToParts(value)`
overloads for default options after `Mf2RelativeTimeCore.create(data)`, or the
corresponding `(value, options)` overloads with
`Mf2RelativeTimeCore.Options.builder()`.

The runtime is parser-free: production callers can pass compiled message models
or catalog resources without shipping a source parser. Fixture JSON parsing and
object-map model decoding live under `src/test/java` to keep the production jar
focused on the runtime API.

Run:

```sh
mvn compile
mvn test-compile
sh run.sh conformance
sh run.sh unicode-tests
sh run.sh demo
sh run.sh inline-demo
sh run.sh public-api-demo
sh run.sh jdk-demo
sh run.sh jdk-check
sh run.sh number-core-check
sh run.sh number-core-bench ../conformance/fixtures/number-core/cases.json 100000 10000
sh run.sh date-time-core-check
sh run.sh date-time-core-bench ../conformance/fixtures/date-time-core/cases.json 100000 10000
sh run.sh relative-time-core-check
sh run.sh relative-time-core-bench ../conformance/fixtures/functions/relative-time-duration-v0.json 100000 10000
sh run.sh datetime-demo
sh run.sh bench ../conformance/fixtures/source-to-model 100000 10000
```
