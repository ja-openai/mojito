# React MF2 comparison benchmarks

This development-only package compares the current MF2 source with pinned public
FormatJS packages. It is not part of either production runtime. The lockfile uses
`registry.npmjs.org`; fixtures contain only synthetic messages and values.

## Run

Use Node 24.15 or newer. From the repository root:

```sh
source webapp/use_local_npm.sh
npm --prefix mf2/benchmarks ci --ignore-scripts
mkdir -p /tmp/mf2-benchmark-results
npm --prefix mf2/benchmarks run runtime -- --output /tmp/mf2-benchmark-results/runtime.json
npm --prefix mf2/benchmarks run memory -- --output /tmp/mf2-benchmark-results/memory.json
npm --prefix mf2/benchmarks run bundle -- --output /tmp/mf2-benchmark-results/bundle.json
```

Do not run unrelated performance workloads concurrently. Scripts share the
`/tmp/mojito-mf2-benchmark.lock` directory (override with `MF2_BENCHMARK_LOCK`) and
wait their turn. An exited owner can be recovered automatically. An interrupted
owner may leave a directory without `owner.json`; remove that directory manually
only after confirming no benchmark process is running.

React and React DOM are pinned to 18.3.1; React Intl is 12.1.3 and its parser is
3.5.20. The Node resolution hook ensures the local source packages and benchmark
share one React installation even when the source packages have their own
development dependencies. Use the npm commands so that hook and production React
are selected. Results include runtime/package versions and machine information
without hostnames or local source paths.

## Runtime methodology

Both systems receive precompiled ASTs. Parser work, imports, JSON decoding and
network transfers are outside timed loops. Warm rows reuse a formatter and its
cache per locale; cold rows create new instances and caches but exclude JavaScript
engine startup. The default run uses 21 alternating-order samples, 5,000 formatting
iterations per sample, and up to 2,000 warmup iterations. More expensive React and
cold rows use fewer iterations, recorded in each result.

```sh
npm --prefix mf2/benchmarks run runtime -- --iterations 10000 --samples 31 --warmup 5000 --output /tmp/mf2-runtime.json
```

The suite covers arguments, plural/select branches, number/currency/date formats,
nested React markup, static rendering, argument updates, and locale switching in
`en`, `fr`, and `ar`. The literal prose stays the same across locales to isolate
formatting behavior; this is not a language-quality test. Inputs are allocated
before timing and cycled through 128 values. Outputs are consumed so the workload
cannot be discarded. React update rows use the synchronous production test
renderer, not a browser; they include reconciliation but exclude DOM/layout work.

MF2 bidi isolation is disabled for equivalent-output comparisons. Its normal
isolation output is also recorded explicitly. Every fixture/locale combination
is checked against the other implementation. Mismatches are retained in JSON,
excluded from matching warm-format rows, and cause a nonzero exit status. React
may emit adjacent text children differently; raw tree differences are retained,
and their rendered HTML must match exactly.

The FormatJS configuration puts UTC on the named date format. Setting a global
provider timezone makes current React Intl merge date/time formats on every
message call, including plain-text calls. Avoiding that overhead keeps the
comparison with an optimized setup fair while preserving identical date output.
MF2 uses the same UTC timezone through its provider.

Median and p95 are statistics of **batch-average nanoseconds per operation**,
not individual-request tail latency. Raw sample means are included. Prefer several
complete runs over interpreting small differences from one process. No benchmark
asserts a hardware-dependent timing threshold.

## Memory and delivery

The memory lane uses explicit GC and records retained heap, catalog/cache scaling,
released owners, and repeated React/catalog transitions. These are sampled retained
heap measurements, not cumulative allocation or operating-system RSS. MF2's
bounded formatter cache and FormatJS's caller-owned shared cache have different
retention policies; retained live cache entries are not evidence of a leak.
Inspect reported limitations and failures alongside the numbers.

The bundle lane compares production bundles and catalog costs separately. Code
that tree-shakes away and parser exclusion are distinct from message-level catalog
pruning; JSON keys are not automatically removed by importing a smaller API.

It builds minified browser ESM with esbuild, an ES2022 target, and production
conditions. React and React DOM are external for **both** systems in the primary
comparisons. Supplemental `react-rich-with-react` rows include one deduplicated
production React 18.3.1 instance; React DOM is unused by these components.
The core rows
compare the MF2 formatter plus Intl registry with `intl-messageformat`; the React
rows compare string formatting and nested markup through each actual provider.
All message data is excluded from runtime-only rows. FormatJS receives ASTs and
uses its documented
[`no-parser.js` alias](https://formatjs.github.io/docs/guides/advanced-usage/).
Native Intl and browser polyfills are outside both measured bundles.

Each emitted file records minified, gzip level 9, and Brotli quality 11 bytes plus
a SHA-256 hash. Dependency contributions come from esbuild's output graph; the
script rejects retained parsers, tooling modules, and Node imports.
Unused runtime/parser/binding entrypoints must contribute zero dependency
bytes. Generated bindings for 100 and 1,000 messages must erase to identical
JavaScript. Direct TS compilation may change minifier identifier choices and
compressed size despite identical erased code, so whole-bundle byte identity is
not the type-erasure assertion. Legal comments are removed from both payloads;
normal distribution notice obligations still apply.

Catalog rows separately measure compact source JSON, AST JSON, and the MF2
versioned artifact envelope. Synthetic English, French, and Arabic catalogs use
eight message shapes at 100 and 1,000 messages per locale. All 9,900 generated
message/argument combinations must produce matching text; markup is flattened
and bidi isolation is disabled for this check. These repetitive product-copy
fixtures are reproducible size examples, not a representative production corpus
or translation-quality test. In particular, the Arabic plural fixtures use only
`one` and `other` for paired syntax comparisons.

Delivery rows contrast eager catalogs with 30 deferred locale/resource chunks
(three locales, ten resources, 100 messages per resource). Initial bytes include
the runtime and import map but exclude deferred catalogs; the current resource
and any fallback resource must also be downloaded. The report includes separate
static JSON resource costs for application-owned fetch loading. Dynamic JSON
imports include esbuild module-export overhead; static JSON rows exclude the
application's fetch/validation code. Summed compressed chunk sizes can exceed a
single compressed bundle even while initial transfer becomes much smaller.

Source hashes before and after the run detect concurrent source changes and
make a drifting run fail. There are no hardware-dependent size or timing gates;
size differences and the largest retained modules should guide investigation.

The initial pinned comparison measured the rich React runtime at 18,103 gzip
bytes for MF2 and 7,754 for optimized React Intl, with React external in both:
a 10,349-byte gap. The largest retained MF2 module was generated CLDR plural
rules (31,646 minified bytes). The core's portable fallback remains reachable
even when the application configures the Intl registry. This is a delivery-size
limitation, not a parser-exclusion failure. Replacing that path with native Intl
solely to reduce bytes would require a separately defined capability profile and
differential conformance checks, especially for exact numeric/plural semantics.
Rerun against the current source before treating these snapshot numbers as current.

Keep generated results outside the repository or under ignored `results/`.
Performance reports describe the measured revision, runtime, configuration, and
workload; they do not establish universal superiority or full feature parity.
