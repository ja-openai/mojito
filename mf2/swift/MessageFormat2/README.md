# MessageFormat2 Swift

Native zero-runtime-dependency Swift package for the MF2 foundation.

Current package shape:

- `MessageFormat2`: consumes the official MF2 Interchange Data Model,
  parses source messages into that model, and formats the supported fixture
  slice, including exact, cardinal, and ordinal selector matching, ranked
  multi-selector matching, `:offset` locals, fixed numeric selector keys,
  text/expression/markup/fallback parts output with attributes, spec-style
  fallback formatting with collected formatting errors, opt-in
  `MF2BidiIsolation.default` string output, function callbacks with both
  rendered string values and raw `MF2Value` operands, custom selector callbacks,
  and structural model validation.

`MessageFormat2` is the only package product. `MessageFormat2Conformance`
and `MessageFormat2TranslateDemo` remain local executable targets for repository
checks, benchmarks, and demos, but they are not client-facing products.

`MessageFormat2` is split by responsibility:

- `Model.swift`: official Unicode MF2 Interchange Data Model types
- `Parser.swift`: source-to-model parser and diagnostics for the supported slice
- `Formatter.swift`: parser-free formatting and selector matching
- `FoundationFunctions.swift`: opt-in Foundation-backed platform registry
- `Errors.swift`: public formatter errors

Generated CLDR plural rules and locale-key lookup helpers are formatter internals,
matching the Java package-private helper boundary.

The stable public API uses Swift-native `MF2*` types plus top-level wrappers:
`parseToModel`, `formatMessage`, `formatMessageToParts`,
`MF2FunctionRegistry.defaults`, `MF2FunctionRegistry.portable`,
`MF2FunctionRegistry.foundation`,
`MF2FunctionCall`, `MF2FunctionMatch`,
`MF2ParseResult`, `MF2ParseDiagnostic`, `MF2RecoveryContext`, and `MF2Error`.

`MF2FormatResult` and `MF2PartsResult` collect recoverable formatting errors
while returning Unicode MF2 visible fallback values by default. Apps can replace
local recoverable values with `onMissingArgument` and `onFormatError` callbacks:

```swift
let result = try formatMessage(
    message,
    locale: "fr",
    onMissingArgument: { context in "[missing \(context.variableName ?? "value")]" },
    onFormatError: { context in context.fallbackValue }
)
```

`MF2FunctionRegistry.defaults` is the normal Swift app registry and currently
matches `MF2FunctionRegistry.portable`: dependency-free handlers for `:string`,
`:offset`, unlocalized numeric formatting for `:number`, `:integer`, and
`:percent`, plus numeric selectors and CLDR plural matching. Unsupported
functions recover with visible MF2 fallback output and collected diagnostics.

`MF2FunctionRegistry.foundation` is the explicit Foundation-backed platform
registry. It keeps portable selectors and `:offset`, then overrides formatters
for `:number`, `:integer`, `:percent`, `:currency`, `:date`, `:time`, and
`:datetime` with Foundation `NumberFormatter` and `DateFormatter` behavior.
On Apple platforms it also registers `:relativeTime` with
`RelativeDateTimeFormatter`; non-Apple Swift keeps relative time deferred rather
than shipping a fake implementation. The Foundation registry is opt-in so
embedded clients can keep the dependency-free portable behavior when they need
the smallest predictable surface. Date/time formatting accepts `dateStyle`,
`timeStyle`, and `timeZone`, with legacy `length`, `precision`, `dateLength`,
`timePrecision`, and shared `style` aliases retained.

The formatter decodes expression and markup attributes into the model, preserving
them for tooling and future parts/rendering workflows.

Apps that already compile messages can still use decoded `MF2Message` models
without invoking the parser. A future package split should move `Parser.swift`
behind an optional target so embedded apps can ship only formatting code.

Planned target:

- `MessageFormat2CompilerPlugin`: SwiftPM build-time compiler from `.mf2`
  catalogs to compiled resources or generated Swift.

Apps should be able to ship only the formatter target when messages are compiled
at build time.

Run:

```sh
swift run MessageFormat2Conformance
swift run MessageFormat2TranslateDemo
swift run MessageFormat2FoundationDemo
swift run -c release MessageFormat2Conformance --bench ../../conformance/fixtures/source-to-model
swift run -c release MessageFormat2Conformance --bench-parse ../../conformance/fixtures/source-to-model
```
