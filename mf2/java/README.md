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
- keep locale-key canonicalization/fallback string-only
- keep ICU4J as a reference comparison target, not a runtime dependency

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
formatting. The JDK does not expose an ICU-style localized relative-time
formatter, so `:relativeTime` is intentionally not part of the Java default
registry. The sample catalog has a test-only CLDR-data implementation; a
production relative-time formatter should land as an explicit ICU/CLDR adapter,
not as portable or JDK behavior.

The conformance and sample demos use test-only fixture/sample registries for
stable dependency-free output. Those shims live under `src/test/java` and are
not part of the library jar.

CLDR plural rules are vendored under `src/main/java` and compiled as normal
package source. Regenerate them from the shared CLDR generator with
`sh ../cldr/update_generated.sh`; CI should run `sh ../cldr/check_generated.sh`
to fail when vendored generated sources are stale. The project has no runtime
dependencies; Maven plugins are build-time only.

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
sh run.sh datetime-demo
sh run.sh bench ../conformance/fixtures/source-to-model 100000 10000
```
