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
- `@mojito-mf2/core/number-core`: generated-data number formatter for the
  Unicode micro-runtime number-core probe. It supports decimal, integer,
  percent, and simple currency formatting for the generated probe locale set
  without depending on `Intl` or parsing runtime JSON.
- `@mojito-mf2/core/date-time-core`: generated-data Gregorian date/time
  formatter for the Unicode micro-runtime date-time-core probe. It supports
  `dateStyle`, `timeStyle`, CLDR pattern-letter `skeleton` values, Mojito
  `semantic:` skeleton strings, `style`, legacy aliases, explicit `hourCycle`,
  locale `u-hc` hour-cycle extensions, and
  UTC/fixed-offset `timeZone` values for the generated probe locale set without
  depending on `Intl` or parsing runtime JSON.
- `@mojito-mf2/core/relative-time-core`: experimental generated-data
  relative-time formatter. It ships no CLDR data by default; callers pass an
  explicit generated relative-time resource.
- `@mojito-mf2/core/cldr-packed`: decoder helpers for opt-in compact CLDR
  resource bundles. The current public helpers reconstruct number-core and
  date-time-core locale data from the generator's string-table packed resource
  formats.

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

The Intl date/time adapter uses `dateStyle`, `timeStyle`, and `timeZone` as the
canonical option names. Legacy `length`, `precision`, `dateLength`,
`timePrecision`, and shared `style` aliases are accepted for parity with the
other runtimes.

Use `relative-time-core` when evaluating generated CLDR relative-time data
without relying on platform `Intl.RelativeTimeFormat`:

```js
import { FunctionRegistry } from "@mojito-mf2/core";
import {
  createRelativeTimeCoreFunctionRegistry,
  formatRelativeTimeCore,
} from "@mojito-mf2/core/relative-time-core";

formatRelativeTimeCore(-86_400, {
  locale: "en",
  style: "long",
  numeric: "auto",
  unit: "day",
  data: generatedRelativeTimeData,
}); // "yesterday"

const functions = createRelativeTimeCoreFunctionRegistry(
  FunctionRegistry,
  generatedRelativeTimeData,
);
```

Use `number-core` when evaluating the generated-data Unicode micro-runtime
path:

```js
import { formatNumberCore } from "@mojito-mf2/core/number-core";

formatNumberCore(1234.5, { locale: "fr-FR" }); // "1 234,5"
formatNumberCore(1234.5, { locale: "en-US", style: "currency", currency: "USD" });
```

`number-core` is intentionally smaller than the `Intl` adapter. It does not
attempt compact notation, unit formatting, spellout, currency display names, or
full locale negotiation.

Use `date-time-core` when evaluating the generated-data Unicode micro-runtime
path for Gregorian product strings:

```js
import { formatDateTimeCore } from "@mojito-mf2/core/date-time-core";

formatDateTimeCore("2026-05-21T14:30:15Z", {
  locale: "fr-FR",
  dateStyle: "short",
  timeStyle: "short",
  timeZone: "UTC",
}); // "21/05/2026 14:30"
```

`date-time-core` intentionally owns presentation only. It uses host `Date`
parsing, formats UTC and fixed-offset values from generated Gregorian patterns,
accepts the Unicode/ECMA `gregory` alias as an explicit calendar option or
locale `u-ca-gregory` extension, and rejects named time zones plus
non-Gregorian calendar options/extensions until the data model is expanded.
Locale `u-hc` extensions are honored for semantic hour skeletons unless an
explicit `hourCycle` option is supplied. Locale `u-nu` numbering-system
extensions are honored when the generated date-time payload already includes the
requested decimal digits. Semantic `J` hour skeletons suppress implicit
day-period markers, and semantic `C` hour skeletons follow the generated CLDR
`timeData.allowed` hour-format order.

The `semantic:` form is a compact field-set syntax over the same generated CLDR
pattern engine. A skeleton such as
`semantic:fields=year,month,day,time;length=medium;timePrecision=minute` maps to
the locale's matching date and time skeletons. Supported semantic options are
`length`, `alignment`, `yearStyle`, `timePrecision`, `fractionalSecond`,
`hourCycle`, and `zoneStyle`. Enum values accept either hyphenated syntax, such
as `minute-optional`, or the camel-case TR35 spelling, such as
`MinuteOptional`. Supported field sets cover the TR35-preview date,
calendar-period, time, zone, and legal composite categories.

Use `cldr-packed` when evaluating generated compact resource bundles for
frontend or mobile packaging:

```js
import {
  decodeDateTimeDataResource,
  decodeNumberDataResource,
} from "@mojito-mf2/core/cldr-packed";

const numberData = decodeNumberDataResource(generatedPackedNumberData);
const dateTimeData = decodeDateTimeDataResource(generatedPackedDateTimeData);
```

The packed decoder is opt-in. The default `date-time-core` import still uses
the generated object-literal data module so existing formatter imports do not
pay a decode step.
The number and date-time generators also emit one-locale packed chunks for lazy
loading:

```js
const { NUMBER_DATA_PACKED_LOCALE } = await import(
  "../cldr/generated/experimental-number/javascript/packed-locales/en-US.js"
);
const { DATE_TIME_DATA_PACKED_LOCALE } = await import(
  "../cldr/generated/experimental-datetime/javascript/packed-locales/en-US.js"
);
const numberData = decodeNumberDataResource(NUMBER_DATA_PACKED_LOCALE);
const dateTimeData = decodeDateTimeDataResource(DATE_TIME_DATA_PACKED_LOCALE);
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
npm run bench:cldr-data
npm run bench:parse
npm run bench:plural
```
