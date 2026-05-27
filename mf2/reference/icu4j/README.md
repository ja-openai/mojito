# ICU4J Reference Harness

Compares shared MF2 source fixtures against ICU4J's MessageFormat 2 technical
preview.

Run correctness comparison:

```sh
sh reference/icu4j/run.sh compare ../../conformance/fixtures/source-to-model
```

Run warmed formatter throughput:

```sh
sh reference/icu4j/run.sh bench ../../conformance/fixtures/source-to-model 100000 10000
```

Emit isolated plural category oracle rows for generated CLDR data:

```sh
sh reference/icu4j/run.sh plural-categories ../../cldr/generated/all/plural_rules.json
```

The benchmark compiles one ICU4J `MessageFormatter` per fixture format case
before warmup. Timed iterations measure repeated `formatToString` calls only.
Unsupported fixtures are reported and skipped rather than silently hidden.
