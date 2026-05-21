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
- expose `formatWithFallback` for spec-style fallback output plus collected
  runtime errors without changing the strict `format()` contract
- support opt-in `Mf2BidiIsolation.DEFAULT` string output around expression
  values
- pass raw host values through the Java function boundary so demo functions can
  distinguish typed values such as `LocalDate`, `Instant`, and legacy `Date`
  from their `toString()` output
- reject invalid model structure for duplicate declarations, select variant key
  arity, duplicate variants, and missing fallback variants
- use generated CLDR cardinal and ordinal plural rules
- keep locale-key canonicalization/fallback string-only
- keep ICU4J as a reference comparison target, not a runtime dependency

`src/main/java` is the library artifact. Local conformance runners, demos,
benchmarks, fixture JSON loading, and demo-only functions live under
`src/test/java` so they can use Maven's standard tool/test classpath without
shipping in the production jar.

`pom.xml` runs the shared CLDR plural generator during Maven's
`generate-sources` phase and compiles the generated Java source from
`target/generated-sources/java`. The checked-in
`../cldr/generated/all` tree remains generator/reference output, not a Java
source root. The project has no runtime dependencies; Maven plugins are
build-time only. Override the generated locale set with
`-Dmf2.cldr.locales=en,fr` for custom build experiments. Java builds pass
`--targets java --java-source-root --java-package ...` to the generator so Maven
does not emit unused Python, Rust, or Swift files and the generated source root
contains only normal Java package directories.

The runtime is parser-free: production callers can pass compiled message models
or catalog resources without shipping a source parser. `JsonParser` is included
only to keep the local conformance/demo runner dependency-free.

Run:

```sh
mvn compile
mvn test-compile
sh run.sh conformance
sh run.sh demo
sh run.sh inline-demo
sh run.sh datetime-demo
sh run.sh bench ../conformance/fixtures/source-to-model 100000 10000
```
