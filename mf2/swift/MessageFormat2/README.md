# MessageFormat2 Swift

Native zero-runtime-dependency Swift package for the MF2 foundation.

Current target:

- `MessageFormat2Runtime`: consumes the official MF2 Interchange Data Model and
  formats the supported fixture slice, including exact, cardinal, and ordinal
  selector matching, text/expression/markup/fallback parts output with
  attributes, spec-style fallback formatting with collected runtime errors,
  opt-in `MF2BidiIsolation.default` string output, and structural model
  validation.
- `MessageFormat2Conformance`: executable correctness and benchmark runner.
- `MessageFormat2TranslateDemo`: tiny `translate(id, locale, args)` catalog
  demo.

`MessageFormat2Runtime` is split by responsibility:

- `Model.swift`: official Unicode MF2 Interchange Data Model types
- `Formatter.swift`: parser-free formatting and selector matching
- `GeneratedPluralRules.swift`: generated CLDR cardinal/ordinal rules
- `PluralRules.swift`: small runtime wrapper for MF2 values
- `Locale.swift`: tiny BCP47-first locale-key string helpers and structural lookup
- `Errors.swift`: public runtime errors

The runtime decodes expression and markup attributes into the model, preserving
them for tooling and future parts/rendering workflows.

The target remains parser-free so an app can ship only compiled messages and
runtime formatting code. Dynamic source parsing should live in a future optional
target.

Planned targets:

- `MessageFormat2Parser`: optional source parser and diagnostics for dynamic
  messages and tools.
- `MessageFormat2CompilerPlugin`: SwiftPM build-time compiler from `.mf2`
  catalogs to compiled resources or generated Swift.

Apps should be able to ship only the runtime target when messages are compiled
at build time.

Run:

```sh
swift run MessageFormat2Conformance
swift run MessageFormat2TranslateDemo
swift run -c release MessageFormat2Conformance --bench ../../conformance/fixtures/source-to-model
```
