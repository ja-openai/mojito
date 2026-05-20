# MessageFormat2 Java

Zero-runtime-dependency Java implementation for the MF2 foundation.

Current target:

- consume the official MF2 Interchange Data Model from Java objects loaded from
  JSON by test/demo tooling
- format the supported shared conformance fixture slice
- use generated CLDR cardinal and ordinal plural rules
- keep ICU4J as a reference comparison target, not a runtime dependency

The runtime is parser-free: production callers can pass compiled message models
or catalog resources without shipping a source parser. `JsonParser` is included
only to keep the local conformance/demo runner dependency-free.

Run:

```sh
sh run.sh conformance
sh run.sh demo
sh run.sh bench ../conformance/fixtures/source-to-model 100000 10000
```
