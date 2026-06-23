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
MF2 platform registry. Keep future platform or ICU adapters explicit rather
than adding partial behavior to the portable registry.

The package also exposes experimental generated-data formatters for the CLDR
micro-runtime probe locale set:

- `FormatNumberCore` and `NumberCoreFunctionRegistry` for localized decimal,
  integer, percent, and simple currency formatting.
- `FormatDateCore`, `FormatTimeCore`, `FormatDateTimeCore`, and
  `DateTimeCoreFunctionRegistry` for Gregorian UTC/fixed-offset date/time
  formatting with semantic CLDR skeleton lookup and `hourCycle` overrides.
- `FormatRelativeTimeCore`, `FormatRelativeTimeCoreToParts`,
  `NewRelativeTimeCoreFormatter`, and `RelativeTimeCoreFunctionRegistry` for
  experimental CLDR relative-time formatting from explicitly supplied generated
  data.

Number/date-time modules use generated Go source maps (`cldr_number_data.go`
and `cldr_date_time_data.go`), not runtime JSON. Relative-time intentionally
does not hide the all-locale CLDR payload inside the package; callers decode
the generated relative-time data they choose to ship and pass it to the direct
API or registry. Shared fixtures compare static outputs, semantic skeleton
outputs, Node/Intl reference witnesses, error cases, registry integration, and
benchmark coverage.

Run:

```sh
go test ./...
go run ./cmd/demo
go test -run '^$' -bench BenchmarkFormatSharedFixtures -benchtime 100000x -count=1
go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime 100000x -count=1
go test -run '^$' -bench 'Benchmark(NumberCore|DateTimeCore|RelativeTimeCore)Fixtures' -benchtime 100000x -count=1
```
