# MessageFormat2 Java

Zero-runtime-dependency Java implementation for the MF2 foundation.

Current target:

- consume the official MF2 Interchange Data Model from Java objects loaded from
  JSON by test/demo tooling
- parse MF2 source into the official MF2 Interchange Data Model for the current
  supported fixture slice
- format the supported shared conformance fixture slice
- use generated CLDR cardinal and ordinal plural rules
- keep locale-key canonicalization/fallback string-only
- keep ICU4J as a reference comparison target, not a runtime dependency

`pom.xml` runs the shared CLDR plural generator during Maven's
`generate-sources` phase and compiles the generated Java source from
`target/generated-sources/mf2-cldr/java`. The checked-in
`../cldr/generated/all` tree remains generator/reference output, not a Java
source root. The project has no runtime dependencies; Maven plugins are
build-time only. Override the generated locale set with
`-Dmf2.cldr.locales=en,fr` for custom build experiments.

The runtime is parser-free: production callers can pass compiled message models
or catalog resources without shipping a source parser. `JsonParser` is included
only to keep the local conformance/demo runner dependency-free.

Run:

```sh
mvn compile
sh run.sh conformance
sh run.sh demo
sh run.sh bench ../conformance/fixtures/source-to-model 100000 10000
```
