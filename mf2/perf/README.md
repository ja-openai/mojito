# MF2 Performance

Performance work has two different jobs:

- benchmark hot formatter loops after warmup
- profile CPU and memory to explain bottlenecks before optimizing

Use `compare.sh` for comparable hot-loop throughput:

```sh
sh perf/compare.sh conformance/fixtures/source-to-model 100000 10000
```

Use `compare_parse.sh` for source parser throughput:

```sh
sh perf/compare_parse.sh conformance/fixtures/source-to-model conformance/fixtures/invalid-source 100000 10000
```

Use `compare_cldr_data.sh` for frontend-oriented generated CLDR data smoke
benchmarks:

```sh
sh perf/compare_cldr_data.sh 100000 10000
```

This reports raw/gzip sizes, JSON parse cost for the experimental number and
date/time artifacts, generated-data lookup loops, JavaScript generated number
and date/time formatter hot loops, and cached `Intl.NumberFormat` /
`Intl.DateTimeFormat` reference loops. It also includes generated number
and date-time formatter hot loops for Python, Rust, Swift, Java, Kotlin, Go, and PHP
against available cached Foundation / JDK reference formatters.

The third argument is untimed warmup iterations. This matters for JVM and other
managed runtimes, but we use it for every language so the comparison shape stays
consistent.

`compare.sh` includes Mojito's Rust, Swift, Python, JavaScript, Java, and Kotlin
runtime starters plus the ICU4J and optional ICU4C++ reference harnesses. Reference
harnesses compile `MessageFormatter` instances before the timed loop and skip
unsupported fixture cases with an explicit count.

Use `profile.sh rss` for wall-clock process measurements and max resident set
size for format benchmarks:

```sh
sh perf/profile.sh rss conformance/fixtures/source-to-model 100000 10000
```

Use `profile.sh rss-parse` for parser RSS measurements:

```sh
sh perf/profile.sh rss-parse conformance/fixtures/source-to-model 100000 10000
```

On macOS this uses `/usr/bin/time -l`, which reports `maximum resident set size`
for each process. That number includes runtime/process overhead, so it should be
reported separately from in-process hot-loop throughput.

The ICU4J classpath, ICU4C++ binary, and Java benchmark classpath are prepared
before `/usr/bin/time` runs, so Maven startup, dependency resolution, and C++
compile time are not counted in the RSS measurements.

## Current Local Smoke Results

Run on 2026-05-19 with 1,000,000 timed iterations and 100,000 warmup
iterations:

- Java runtime: 45 cases, about 5.5M ops/sec
- Rust runtime: 45 cases, about 2.4M ops/sec
- Swift runtime: 45 cases, about 1.0M ops/sec
- Python runtime: 45 cases, about 0.24M ops/sec
- ICU4J 78.3: 41 supported cases, about 0.25M ops/sec
- ICU4C++ 77.1: 41 supported cases, about 0.04M ops/sec

RSS smoke run with 10,000 timed iterations and 2,000 warmup iterations:

- Rust process: about 2.5 MB max RSS
- Swift process: about 8.0 MB max RSS
- Python process: about 22 MB max RSS
- ICU4J process: about 266 MB max RSS
- ICU4C++ process: about 6.1 MB max RSS

These are development-machine smoke numbers, not release benchmarks. They are
useful for trend detection and obvious bottlenecks only.

`compare_parse.sh` now includes Rust, Swift, Python, JavaScript, Java, and Kotlin for
both valid and invalid source fixtures; `profile.sh rss-parse` adds RSS smoke
coverage for Rust, Swift, Python, JavaScript, Java, Kotlin, Go, and PHP parsers. Parser
smoke run with 1,000,000 timed iterations and 100,000 warmup iterations before
Swift/Python were wired:

- Valid source fixtures: Rust about 1.77M parses/sec, Java about 2.16M parses/sec
- Invalid source fixtures: Rust about 6.15M parses/sec, Java about 8.06M parses/sec

After the JavaScript starter landed, a 5,000-iteration smoke showed JavaScript
at about 317K format ops/sec, 208K valid-source parses/sec, and 411K
invalid-source parses/sec on the then-current 52-fixture source corpus. The JS
plural micro-smoke showed generated CLDR rules at about 424 ns/op and cached
platform `Intl.PluralRules` at about 299 ns/op for the sampled locales.

The latest CLDR data and formatter smoke runs after quarter/week/weekday,
flexible day-period, localized UTC timezone-name, best-fit skeleton width
matching, fixed-offset timezone support, semantic skeleton `hourCycle`
overrides, and append-item fallback landed showed:

- number data: 8.8 KB raw, 1.2 KB gzip
- generated JavaScript number data module: about 5.9 KB raw, 1.2 KB gzip
- compact JavaScript number resource prototype: about 2.0 KB raw, 0.9 KB gzip
- generated Rust number data module: about 7.6 KB raw, 1.3 KB gzip
- generated PHP number data module: about 7.2 KB raw, 1.3 KB gzip
- date/time data with filtered CLDR skeleton `availableFormats`: 56.6 KB raw, 5.2 KB gzip
- compact JavaScript date/time resource prototype: about 25.3 KB raw, 6.1 KB gzip
- generated Rust date/time data module: about 43.8 KB raw, 5.1 KB gzip
- generated PHP date/time data module: about 42.1 KB raw, 5.1 KB gzip
- generated number symbol lookup: about 20 ns/op
- cached `Intl.NumberFormat.formatToParts`: about 3.1 us/op
- JavaScript generated number-core formatting: about 1.3 us/op
- cached `Intl.NumberFormat.format`: about 0.6 us/op
- generated date/time name lookup: about 26 ns/op
- JavaScript generated date-time-core formatting: about 18.3 us/op
- cached `Intl.DateTimeFormat.format`: about 0.9 us/op
- cached `Intl.DateTimeFormat.formatToParts`: about 3.0 us/op
- Python generated number-core formatting: about 4.4 us/op
- Python generated date-time-core formatting: about 58.1 us/op
- Rust generated number-core formatting: about 4.0 us/op
- Rust generated date-time-core formatting: about 34.7 us/op
- Swift generated number-core formatting: about 13.9 us/op
- cached Foundation `NumberFormatter.string`: about 0.6 us/op
- Swift generated date-time-core formatting: about 470 us/op
- cached Foundation `DateFormatter.string`: about 0.8 us/op
- Java generated number-core formatting: about 3.7 us/op
- cached JDK `NumberFormat.format`: about 0.6 us/op
- Java generated date-time-core formatting: about 17.4 us/op
- cached JDK `DateTimeFormatter.format`: about 0.8 us/op
- Kotlin generated number-core formatting: about 2.4 us/op
- cached Kotlin/JDK `NumberFormat.format`: about 0.4 us/op
- Kotlin generated date-time-core formatting: about 14.2 us/op
- cached Kotlin/JDK `DateTimeFormatter.format`: about 0.9 us/op
- Go generated number-core formatting: about 0.9 us/op
- Go generated date-time-core formatting: about 12.7 us/op
- PHP generated number-core formatting: about 2.8 us/op
- PHP generated date-time-core formatting: about 88.5 us/op

These are harness smoke numbers only. They exist to catch order-of-magnitude
regressions in generated resource shape and lookup cost while the real
generated formatter data and API ergonomics continue to settle. The Swift
date-time-core number is from the debug SwiftPM smoke path and is intentionally
tracked as a follow-up optimization target rather than a release-mode claim.

Java JFR smoke profiles point at ordinary string/parser work after warmup:
quoted-pattern scanning, name splitting, immutable model list construction, and
benchmark byte counting. Treat those as optimization targets only after the
grammar and fixture coverage harden.

Use `profile.sh python-cpu` to find Python hotspots:

```sh
sh perf/profile.sh python-cpu conformance/fixtures/source-to-model 100000 10000
```

The Python CPU profile starts after fixture loading and warmup, so import time
does not dominate the hotspot table.

## Rules For Fair Comparisons

- Keep startup, build time, model loading, and hot formatting as separate
  measurements.
- Run reference implementations in a warmed process where possible; do not
  compare JVM startup to native hot loops.
- Use the same shared fixture cases and argument values for every runtime.
- Report unsupported fixture cases instead of silently dropping them.
- Treat first-run package compilation as setup, not formatter performance.
