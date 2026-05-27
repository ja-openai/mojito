# ICU4C++ Reference Harness

Optional comparison harness for ICU4C++ MessageFormat 2 technical preview.

The harness expects an ICU4C++ install that includes
`include/unicode/messageformat2.h`. On macOS with Homebrew, the script currently
auto-detects `icu4c@77`. Override with `ICU4C_PREFIX` when needed:

```sh
ICU4C_PREFIX=/path/to/icu sh reference/icu4cxx/run.sh compare ../../conformance/fixtures/source-to-model
```

Run correctness comparison:

```sh
sh reference/icu4cxx/run.sh compare ../../conformance/fixtures/source-to-model
```

Run warmed formatter throughput:

```sh
sh reference/icu4cxx/run.sh bench ../../conformance/fixtures/source-to-model 100000 10000
```

Fixture JSON is converted into a generated C++ header before compile. The timed
benchmark loop only measures repeated `MessageFormatter::formatToString` calls.
Unsupported fixture cases are reported and skipped.
