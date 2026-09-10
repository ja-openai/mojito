# Mojito MF2 Go

Native Go parser/formatter package for Mojito's MF2 foundation.

The package follows normal Go module shape:

- `go.mod`: module boundary at `github.com/box/mojito/mf2/go`
- `*.go`: production package sources in the module root
- `*_test.go`: conformance, Unicode, and benchmark harnesses beside the package
- `cmd/demo`: local runnable demo, kept outside the importable library package

Go packages conventionally keep tests beside the code they exercise. The demo is
under `cmd/demo` so it can be run with `go run ./cmd/demo` without becoming part
of the library API.

The stable public API uses Go naming while matching the other runtimes:
`ParseToModel`, `FormatMessage`, `FormatMessageToParts`,
`DefaultFunctionRegistry`, `PortableFunctionRegistry`, `ParseResult`,
`FormatResult`, `PartsResult`, and `RecoveryContext`. `FormatResult` and
`PartsResult` expose `Ok()` and `HasErrors()` helpers around collected formatting diagnostics. Formatting uses
Unicode MF2 visible fallback values by default; `Options.OnMissingArgument` and
`Options.OnFormatError` can return `(replacement, true)` to replace local
recoverable values or `("", false)` to decline and use the visible fallback.

`DefaultFunctionRegistry` is the normal Go app registry and currently matches
`PortableFunctionRegistry`: dependency-free handlers for `:string`, `:offset`,
unlocalized numeric formatting for `:number`, `:integer`, and `:percent`, plus
numeric selectors and CLDR plural matching. Unsupported functions recover with
visible MF2 fallback output and collected diagnostics.

Go platform formatting is intentionally deferred. `golang.org/x/text/message`
is useful for localized numeric printing, but it does not yet provide the clean
date, time, currency, and relative-time formatter surface needed for an honest
MF2 platform registry. Keep future locale-data or ICU adapters explicit rather
than adding partial behavior to the portable registry.

Run:

```sh
go test ./...
go run ./cmd/demo
go test -run '^$' -bench BenchmarkFormatSharedFixtures -benchtime 100000x -count=1
go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime 100000x -count=1
```

## Runtime bounds and model ownership

Portable numeric fraction options accept integers from 0 through 1000. A minimum
larger than an explicit maximum returns `bad-option`. Offset decimal expansion is
limited to 4096 digits and out-of-range operands return `bad-operand`. Plural
category selection rejects operands outside the supported integer/fraction range;
exact-key selection does not require a CLDR category.

Compiled models are checked for required semantic fields, discriminator types,
and literal-or-present attributes before formatting. Unknown extension fields are
ignored by formatting. Returned parts do not share mutable semantic attributes or
options with the input model. Markup options retain model references for callers
that resolve them in their rendering layer.

The package includes `LICENSE`, `NOTICE`, and `UNICODE-LICENSE.txt`; JVM JARs place
these notices in `META-INF`.

Benchmarks accept `MF2_BENCH_FIXTURES` and `MF2_BENCH_WARMUP` (default 10000).
Fixture loading, output preflight checks, and warmup run outside the benchmark
timer. Format cases preserve locale and bidi isolation. The parse benchmark
reports parsed count, diagnostic count, and source bytes; formatting reports an
output-byte checksum.

`u:dir` accepts `ltr`, `rtl`, `auto`, and `inherit`, including resolved string variables. Invalid
values report `bad-option` and preserve the formatted value. The option is hidden from formatter
callback option resolution. A plain alias retains isolation; reannotation defaults to `inherit`.
Literal-only numeric histories use a per-format source cache capped at 64 inherited options per
source. Histories depending on variable options remain uncached, preserving their resolver behavior;
benchmark those large generated catalogs separately from typical short messages.

Pre-1.0 compatibility note: `FunctionSource` now includes private cache state. External callers must
use keyed literals (`FunctionSource{Value: value, Function: function}`) or `NewFunctionSource(...)`;
previous unkeyed positional struct literals no longer compile. Copies never share active cache state.

Custom formatters and selectors receive detached semantic annotation maps and source chains.
Changing these callback snapshots cannot change the catalog or another expression's cached state.
Option resolvers continue to resolve the original annotation. Snapshot cost grows with the inherited
chain length for custom callbacks; the built-in numeric path reuses its private per-format sources.
Unknown extension payloads and caller-supplied raw argument objects are outside this semantic copy.

`:integer` formatting and exact selection truncate decimal text without a signed-64-bit cast,
using the existing 4096-digit decimal expansion limit and finite numeric operand check. This
also preserves integer literals above the binary floating-point exact-integer range. Native
argument-to-text conversion does not narrow integral floating-point values to `int64`.
