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

The third argument is untimed warmup iterations. This matters for JVM and other
managed runtimes, but we use it for every language so the comparison shape stays
consistent.

`compare.sh` includes Mojito's Rust, Swift, Python, and Java runtime starters
plus the ICU4J and optional ICU4C++ reference harnesses. Reference harnesses compile
`MessageFormatter` instances before the timed loop and skip unsupported fixture
cases with an explicit count.

Use `profile.sh rss` for wall-clock process measurements and max resident set
size:

```sh
sh perf/profile.sh rss conformance/fixtures/source-to-model 100000 10000
```

On macOS this uses `/usr/bin/time -l`, which reports `maximum resident set size`
for each process. That number includes runtime/process overhead, so it should be
reported separately from in-process hot-loop throughput.

The ICU4J classpath and ICU4C++ binary are prepared before `/usr/bin/time` runs,
so Maven startup, dependency resolution, and C++ compile time are not counted in
the RSS measurements.

## Current Local Smoke Results

Run on 2026-05-19 with 1,000,000 timed iterations and 100,000 warmup
iterations:

- Java runtime: 45 cases, about 5.5M ops/sec
- Rust prototype: 45 cases, about 2.4M ops/sec
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

Parser smoke run with 1,000,000 timed iterations and 100,000 warmup iterations:

- Valid source fixtures: Rust about 1.77M parses/sec, Java about 2.16M parses/sec
- Invalid source fixtures: Rust about 6.15M parses/sec, Java about 8.06M parses/sec

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
