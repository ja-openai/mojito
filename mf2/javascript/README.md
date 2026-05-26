# JavaScript MF2 Core

Zero-dependency ESM package for Mojito's native JavaScript MessageFormat 2 core.

This package mirrors the Rust/Java package boundaries:

- `parser.js`: MF2 source to the Unicode MF2 Interchange Data Model
- `formatter.js`: parser-free formatting from the model
- `formatMessageToParts`: first-class structured output for React/HTML/native UI
  renderers
- `tools/conformance.js`: shared fixture runner outside the published package
  files

Package modules and `.d.ts` files live under `src`. Conformance, Unicode test,
benchmark, package-boundary, and demo entry points live under `tools`, `tests`,
and `examples`; `package.json` publishes only the package files via `files`.

The package root exports the stable app-facing parser/formatter API:

- `parseToModel`
- `formatMessage`
- `formatMessageToParts`
- `FunctionRegistry`
- `MF2Error`

`formatMessage` returns `{ value, errors, ok, hasErrors }`.
`formatMessageToParts` returns `{ parts, errors, ok, hasErrors }`.
By default, formatting recovers with Unicode MF2 visible fallback values such
as `{$name}` while preserving diagnostics in `errors`. Applications can pass
`onMissingArgument` and `onFormatError` callbacks to replace the local fallback
value while still collecting the diagnostic.

`FunctionRegistry.defaults()` is the normal JavaScript app registry and
currently matches `FunctionRegistry.portable()`: dependency-free handlers for
`:string`, `:offset`, unlocalized numeric formatting for `:number`, `:integer`,
and `:percent`, plus numeric selectors and CLDR plural matching. Unsupported
functions recover with visible MF2 fallback output and collected diagnostics.

Browser-sensitive consumers can use explicit subpath imports:

- `@mojito-mf2/core/parser`: parser only
- `@mojito-mf2/core/formatter`: formatter only, no parser import
- `@mojito-mf2/core/portable`: dependency-free portable registry factory
- `@mojito-mf2/core/intl`: `Intl`-backed registry factory for locale-pretty
  number, percent, integer, currency, date, time, datetime, and relative time

The root import remains the stable compatibility API. Locale lookup,
CLDR plural-rule helpers, `partsToString`, `selectPluralCategory`, and
`valueToString` are formatter internals rather than public root exports,
matching the Java package-private helper boundary.

Use the `Intl` registry explicitly when browser or Node platform formatting is
wanted:

```js
import { FunctionRegistry, formatMessage, parseToModel } from "@mojito-mf2/core";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";

const model = parseToModel("Due {$delta :relativeTime unit=day}").model;
const functions = createIntlFunctionRegistry(FunctionRegistry);
const result = formatMessage(model, { delta: -1 }, { locale: "fr", functions });
```

TypeScript uses the same JavaScript package. The public `.d.ts` files
live beside the ESM modules and are exposed through the package root `types`
condition. This keeps TypeScript as a typed consumer surface for
`@mojito-mf2/core`, not a separate implementation to keep in sync.

Plural selection uses generated CLDR cardinal/ordinal rules shared with the
other native runtimes.

Run:

```sh
npm run generate:plurals
npm run check
npm run check:types
npm run demo
npm run demo:intl
npm run bench:format
npm run bench:parse
npm run bench:plural
```
