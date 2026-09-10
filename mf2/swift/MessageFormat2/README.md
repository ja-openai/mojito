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

### Exact numbers and model interchange

The portable number, integer, percent, and offset functions retain decimal
coefficients as text. They accept up to 4096 expanded decimal digits and at most
1000 requested fraction digits; a minimum greater than the maximum is a
`bad-option` error. Maximum fraction digits use half-even rounding. Integer
formatting truncates toward zero, without a machine-integer range restriction.
Use `.number("9007199254740993")` or a string argument for exact decimal values.
JSON decoding preserves native `Int` and `UInt64` values, and otherwise accepts
binary64 values within its exact integer range; larger JSON numbers must be
supplied as strings. Unsupported JSON values throw `DecodingError`.

Foundation number formatting and generated CLDR plural selection are limited to
absolute values at most 9007199254740991. CLDR fractional operands must also fit
signed 64-bit integers. Foundation also rejects nonzero decimals that underflow
to binary64 zero, and relative week counts that overflow their day conversion.
Unsupported formatting/selection returns `bad-operand`
or `bad-selector`; exact-key selection (`select=exact`) and portable formatting
retain larger values.

The model types have public initializers and support `Codable`. Encoding preserves
the supported semantic model, including attributes and markup. Unknown extension
properties are ignored when decoding and are not retained when encoding. Formatting
validates imported and directly constructed models before invoking functions.
Canonically equivalent Unicode variable names resolve to the same binding.

Expression parts include an optional `direction` associated value, defaulting to
`nil`. Existing `.expression(value, attributes: ...)` construction remains valid;
pattern matches should bind or ignore the third value. Explicit `u:dir` values
control string isolation and are retained in expression parts.

The conformance executable includes public-API regression checks and an
`official-bridge` JSON-lines mode for the shared upstream assertion runner. Both
portable and Foundation modes use production functions; only the upstream
`test:*` namespace is implemented by test helpers.

Default bidirectional isolation uses pinned CLDR locale direction and private
production-formatter metadata. Numeric output in a known left-to-right locale
needs no extra isolation unless its resolved value requires it. Plain variable
aliases preserve isolation; a new function annotation defaults to `inherit` and
retains direction without forcing isolation. Replacing a
numeric formatter with `withFunction` removes that guarantee; custom formatters
and unknown locale directions keep isolation. This metadata is private and does
not alter the public parts JSON contract.

`u:dir` accepts literal or variable `auto`, `ltr`, `rtl`, and `inherit` values.
Invalid values report `bad-option` and leave the formatted value intact. Function
handlers do not receive `u:dir` through resolved option access; the source model
remains available for inspection.

Long declaration chains share source history, release it iteratively, and
propagate selector annotations in declaration order. Numeric history and inherited
options are memoized only within a format call, for histories with literal options.
Histories with variable options remain uncached.
