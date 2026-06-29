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

Inflection is intentionally not a public Go runtime surface yet. The package
does not export compiled term-pack, M2IF, or inflection APIs; the conformance
test scans exported Go names to keep that boundary pinned until a concrete Go
caller justifies a reviewed product API. Keep Go on its existing package tests;
validate inflection release artifacts through the repo-level shared gate rather
than adding Go inflection exports or a package-local wrapper without that
caller.

Run:

```sh
go test ./...
go run ./cmd/demo
go test -run '^$' -bench BenchmarkFormatSharedFixtures -benchtime 100000x -count=1
go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime 100000x -count=1
```
