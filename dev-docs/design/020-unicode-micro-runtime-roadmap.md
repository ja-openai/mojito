# Unicode Micro-Runtime Roadmap

## Purpose

Build the Unicode and CLDR pieces that Mojito and the MF2 runtimes actually
need as small, native, cross-language modules instead of requiring every client
to depend on a full ICU stack.

The goal is not to replace ICU. ICU and ICU4X remain reference points, data
sources, and optional platform adapters. The goal is to make common product
localization behavior available in Python, Java, Swift, Rust, JavaScript, Go,
Kotlin, and PHP with a package shape that works for frontend bundles, mobile
apps, CLIs, and servers.

## Problem

ICU is excellent, but it is a large monolithic dependency for many products.
ICU4X has the right modular direction, but Rust cannot be the only production
answer for every runtime we support. Host platform APIs are also uneven:
JavaScript `Intl`, JDK formatters, Swift Foundation, Babel, PHP Intl, ICU4J,
ICU4X, and Go packages expose different surfaces and different data versions.

MF2 plural rules proved a useful pattern:

- keep the public runtime small
- generate native code from CLDR instead of parsing runtime JSON
- share conformance across languages
- compare against ICU reference behavior
- allow locale subset builds for embedded clients

This roadmap extends that pattern beyond MF2 plural selection without falling
into the trap of reimplementing all of ICU.

## Non-Goals

- Do not build a general ICU clone.
- Do not ship all-locale, all-feature CLDR data in tiny runtime cores.
- Do not fake parity by adding incomplete date, time, currency, collation, or
  segmentation shims.
- Do not make Rust, Wasm, ICU4J, Babel, or platform `Intl` mandatory for every
  language runtime.
- Do not let MF2 grammar work and CLDR data work become inseparable. MF2 should
  consume these modules, not own every Unicode concern forever.

## Principles

- **Module first.** Each feature must be independently importable or linkable.
  A product that only needs plural rules should not pay for relative time,
  number symbols, dates, calendars, or display names.
- **Native by default.** Every supported language gets an idiomatic package
  surface and generated native data where that keeps size and startup small.
- **Generated code before runtime JSON.** Use code generation for algorithmic or
  compact data such as plural rules, plural parent maps, small aliases, and
  currency metadata.
- **Resource packs when data is string-heavy.** Use explicit generated resource
  packs for larger localized pattern/string data such as relative time. The core
  package must not hide that cost.
- **Locale subsets are first-class.** Generators must support product locale
  allowlists and package-size gates.
- **Platform adapters are honest.** If a host library can provide high-quality
  formatting, expose it as an adapter. If it cannot, leave the feature deferred
  instead of shipping a misleading default.
- **Reference comparison is required.** ICU4J, ICU4C++, JavaScript `Intl`,
  ICU4X, Babel, Foundation, and PHP Intl can all be references where they have
  relevant behavior, but none of them is automatically the product contract.
- **Conformance owns behavior.** A feature is not production behavior until it
  has cross-language fixtures, data-version metadata, reference comparison, and
  unsupported-case reporting.

## Package Shape

Use language-native package boundaries, but keep the same conceptual modules:

- `locale-core`: tiny locale key parsing, canonical separator/case handling,
  extension stripping where feature lookup requires it, and structural fallback.
  Full locale negotiation, display names, and catalog fallback policy stay out.
- `plural-rules`: generated CLDR cardinal and ordinal category selection,
  including plural-specific parent maps. This is the proven first module.
- `number-core`: decimal, integer, percent, and narrowly scoped currency
  formatting data and algorithms.
- `date-time-core`: narrowly scoped date, time, and datetime formatting for
  Gregorian product strings, with host date/time arithmetic and explicit
  timezone boundaries.
- `relative-time`: optional generated CLDR pattern data and formatter helpers.
  This is a good quick win because generator work already exists, but it is not
  the critical path.
- `platform-adapters`: host-backed formatters such as JavaScript `Intl`, JDK,
  ICU4J, Swift Foundation, Python Babel, PHP Intl, and ICU4X.
- `conformance`: shared fixtures and reference runners for every production
  module.
- `generator`: CLDR ingestion and native code/resource generation.

Naming can be decided later. The important contract is that MF2 runtimes consume
these boundaries in the same way, even while the source lives under `mf2/cldr`.

## Language Expectations

- JavaScript: ESM-first, tree-shakeable entry points, no all-locale data in the
  default import, explicit `Intl` adapters, and generated locale subset outputs
  for frontend bundles.
- Swift: SwiftPM targets with generated Swift data, no parser or JSON CLDR data
  in app runtime targets unless requested, and Foundation adapters where host
  support is real.
- Java: small zero-dependency artifacts for core generated modules, optional
  ICU4J adapters, and no hidden ICU4J dependency in Mojito/server defaults.
- Kotlin: Kotlin-native APIs over the same generated data strategy as Java,
  with optional ICU4J adapter artifacts.
- Python: stdlib-only core packages with optional Babel extras for platform
  formatting.
- Rust: native crates and optional ICU4X features, but no assumption that Rust
  is the universal implementation language for other runtimes.
- Go: generated core packages first; defer platform formatting until a complete
  and honest adapter surface exists.
- PHP: Composer packages with generated PHP data and explicit PHP Intl adapters.

## Data Strategy

Classify each Unicode feature before implementation:

| Data type | Default strategy | Examples |
| --- | --- | --- |
| Small algorithmic rules | Generated native code | plural rules, plural parents |
| Small stable metadata | Generated native tables | currency fraction digits, numbering-system digits |
| Medium formatting data | Generated native tables or packed resources | number symbols, grouping patterns, date/time style patterns |
| Repeated localized patterns | Deduplicated resource packs | relative-time patterns, list patterns |
| Large formatting systems | Platform adapter first | full calendars, timezone names, currency display names |
| Complex text algorithms | Defer until demanded | segmentation, collation, transliteration |

Generated native code is preferred when it avoids runtime parsing and remains
small after minification or compilation. Resource packs are preferred when the
data is naturally localized strings and locale subsetting is the main win.
Runtime JSON is acceptable only as an explicit resource artifact; it should not
become the default package boundary for frontend or mobile runtimes.

Known current size signals from the MF2 CLDR work:

- all-locale plural rules are small enough for default generated runtime data
  in every supported language
- all-locale relative-time data is too large to hide in tiny core packages, but
  gzip and deduplication make it plausible as an explicit server or subset
  resource package
- number and currency data is still experimental, but generated native tables,
  shared fixtures, reference comparisons, size gates, registry integration, and
  formatter benchmarks now exist for JavaScript, Python, Java, Kotlin, Swift,
  Rust, Go, and PHP, making the probe set language-complete for the current
  supported runtime list. The JavaScript generator also emits a validated
  compact string-table resource prototype plus one-locale lazy chunks for
  frontend/mobile packaging, and JavaScript has an opt-in
  `@mojito-mf2/core/cldr-packed` decoder tested against the generated full
  runtime payload plus the one-locale chunks.
- date/time formatting should own CLDR presentation data only; timezone
  database behavior should stay with host date/time libraries unless a product
  requirement justifies something heavier. The Gregorian UTC/fixed-offset probe,
  including common UTC/GMT aliases and IANA `Etc/GMT±N` fixed-offset aliases,
  has generated native tables for style patterns and CLDR
  `availableFormats` semantic skeletons, Mojito `semantic:` field-set skeleton
  parsing with hyphenated and TR35 camel-case enum value aliases plus
  `date`/`yearMonthDay`/`eraDate`/`eraYearMonthDay`/`eraDateWeekday`/`weekdayEraDate`/`eraYearMonthDayWeekday`/`weekdayEraYearMonthDay`/`dateWeekday`/`weekdayDate`/`yearMonthDayWeekday`/`weekdayYearMonthDay`/`dateTime`/`yearMonthDayTime`/`dateTimeWeekday`/`weekdayDateTime`/`yearMonthDayTimeWeekday`/`weekdayYearMonthDayTime`/`dateTimeWeekdayZone`/`weekdayDateTimeZone`/`zonedDateTimeWeekday`/`zonedWeekdayDateTime`/`yearMonthDayTimeWeekdayZone`/`weekdayYearMonthDayTimeZone`/`zonedYearMonthDayTimeWeekday`/`zonedWeekdayYearMonthDayTime`/`eraDateTimeWeekday`/`weekdayEraDateTime`/`eraYearMonthDayTimeWeekday`/`weekdayEraYearMonthDayTime`/`eraDateTimeWeekdayZone`/`weekdayEraDateTimeZone`/`zonedEraDateTimeWeekday`/`zonedWeekdayEraDateTime`/`eraYearMonthDayTimeWeekdayZone`/`weekdayEraYearMonthDayTimeZone`/`zonedEraYearMonthDayTimeWeekday`/`zonedWeekdayEraYearMonthDayTime`/`eraDateTime`/`eraYearMonthDayTime`/`zonedDateTime`/`dateTimeZone`/`yearMonthDayTimeZone`/`zonedYearMonthDayTime`/`eraDateTimeZone`/`zonedEraDateTime`/`eraYearMonthDayTimeZone`/`zonedEraYearMonthDayTime`/`eraYear`/`eraYearMonth`/`yearMonth`/`eraYearQuarter`/`yearQuarter`/`eraYearWeek`/`yearWeek`/`monthWeek`/`yearMonthWeek`/`eraYearMonthWeek`/`monthDay` field-set aliases,
  `yearOfEra`/`dayOfMonth`/`monthOfYear`/`quarterOfYear`/`dayOfWeek`/`timeZone`/`timeZoneName`
  field spelling aliases plus `hourOfDay`/`minuteOfHour`/`secondOfMinute`
  time-component aliases, `style`/`dateStyle`/`dateLength`, inferred style-only date/time fields including full-date weekday and long/full time-zone inference, direct CLDR style-pattern formatting for exact `dateStyle`/`timeStyle` semantic aliases, `precision`, `timeStyle`
  (`short`/`medium`, plus `long`/`full` with an explicit or inferred `zone` field), and `hour12` option
  aliases, `zone`/`timeZoneName`/`timeZoneStyle` option aliases and
  Intl-style `timeZoneName` value aliases,
  Intl-style field option aliases (`era`/`year`/`month`/`quarter`/`day`/
  `weekday`/`dayPeriod`/`hour`/`minute`/`second` to the matching `*Style`
  option) with `2-digit`/`2Digit`/`twoDigit` value aliases, and
  `fractionalSecondDigits` option/field aliases,
  shared fixtures, reference
  comparisons, size gates, registry integration, and formatter benchmarks for
  JavaScript, Python, Java, Kotlin, Swift, Rust, Go, and PHP. Generated
  date/time data validation compares localized name data plus shared
  style-pattern, Gregorian calendar alias/override, and fixed-offset rollover
  reference outputs against JavaScript `Intl.DateTimeFormat`,
  and the ICU4J reference harness strictly witnesses shared style-pattern cases.
  The JavaScript
  generator also emits a validated compact string-table resource prototype for
  date/time presentation data. The checked-in skeleton probe supports exact
  lookup, deterministic same-field-set best-fit matching, hour-cycle-compatible
  fallback matching, CLDR append-item composition for missing semantic fields
  using compact runtime default templates plus generated locale overrides, and pattern field
  width adjustment for year, extended-year, related Gregorian year, quarter,
  month, day, day-of-year, day-of-week-in-month, modified Julian day, weekday,
  day-period, hour, minute, second, fractional second, localized UTC zone
  names, localized GMT-zero/fixed-offset zone presentation fields, and common
  UTC/GMT plus IANA `Etc/GMT±N` fixed-offset timezone aliases. It also supports CLDR
  week-year, week-of-year, and week-of-month pattern fields with generated
  supplemental first-day/minimum-days week data, stand-alone quarter `q`
  skeleton matching with generated narrow quarter names and stand-alone
  overrides when CLDR differs, stand-alone month `L` and skeleton-only `l`
  matching across the `M`/`L` field family, extended-year `u` and related
  Gregorian year `r`
  skeleton matching across the `y`/`u`/`r` field family, day-period skeleton
  matching across the
  `a`/`b`/`B` field family with bare period synthesis, fractional-second `S`
  skeleton synthesis from second-bearing CLDR time patterns with localized
  decimal separators, milliseconds-in-day `A` skeleton synthesis, `J` hour
  skeletons without implicit day-period
  markers, `C` hour skeletons driven by generated CLDR `timeData.allowed`
  hour-format order,
  timezone skeleton
  matching across the `z`/`Z`/`O`/`v`/`V`/`X`/`x` field family with bare
  timezone pattern synthesis, single-field numeric skeleton synthesis for
  day-of-year `D`, day-of-week-in-month `F`, modified Julian day `g`,
  bare minute `m`, bare second `s`, and milliseconds-in-day `A`, plus explicit semantic
  `hour`/`minute`/`second`/`fractionalSecond` field lowering and standalone semantic
  `dayOfYear`/`dayOfWeekInMonth`/`modifiedJulianDay`/`millisecondsInDay`
  field lowering without additional CLDR data, plus
  `semantic:fields=...` lowering for TR35-style field sets and options
  (`length` with `full`/`long`/`medium`/`short`, `alignment`, `yearStyle`, `eraStyle`, `monthStyle`, `quarterStyle`,
  `dayStyle`, `weekdayStyle`, `dayPeriodStyle`, `hourStyle`, `minuteStyle`,
  `secondStyle`, `timePrecision`, `fractionalSecond`,
  `hourCycle`, and `zoneStyle`) with hyphenated and TR35 camel-case enum value
  aliases plus `date`/`yearMonthDay`/`eraDate`/`eraYearMonthDay`/`eraDateWeekday`/`weekdayEraDate`/`eraYearMonthDayWeekday`/`weekdayEraYearMonthDay`/`dateWeekday`/`weekdayDate`/`yearMonthDayWeekday`/`weekdayYearMonthDay`/`dateTime`/`yearMonthDayTime`/`dateTimeWeekday`/`weekdayDateTime`/`yearMonthDayTimeWeekday`/`weekdayYearMonthDayTime`/`dateTimeWeekdayZone`/`weekdayDateTimeZone`/`zonedDateTimeWeekday`/`zonedWeekdayDateTime`/`yearMonthDayTimeWeekdayZone`/`weekdayYearMonthDayTimeZone`/`zonedYearMonthDayTimeWeekday`/`zonedWeekdayYearMonthDayTime`/`eraDateTimeWeekday`/`weekdayEraDateTime`/`eraYearMonthDayTimeWeekday`/`weekdayEraYearMonthDayTime`/`eraDateTimeWeekdayZone`/`weekdayEraDateTimeZone`/`zonedEraDateTimeWeekday`/`zonedWeekdayEraDateTime`/`eraYearMonthDayTimeWeekdayZone`/`weekdayEraYearMonthDayTimeZone`/`zonedEraYearMonthDayTimeWeekday`/`zonedWeekdayEraYearMonthDayTime`/`eraDateTime`/`eraYearMonthDayTime`/`zonedDateTime`/`dateTimeZone`/`yearMonthDayTimeZone`/`zonedYearMonthDayTime`/`eraDateTimeZone`/`zonedEraDateTime`/`eraYearMonthDayTimeZone`/`zonedEraYearMonthDayTime`/`eraYear`/`eraYearMonth`/`yearMonth`/`eraYearQuarter`/`yearQuarter`/`eraYearWeek`/`yearWeek`/`monthWeek`/`yearMonthWeek`/`eraYearMonthWeek`/`monthDay` field-set aliases,
  `yearOfEra`/`dayOfMonth`/`monthOfYear`/`quarterOfYear`/`dayOfWeek`/`timeZone`/`timeZoneName`
  field aliases plus `hourOfDay`/`minuteOfHour`/`secondOfMinute`
  time-component aliases, `style`/`dateStyle`/`dateLength`, inferred style-only date/time fields including full-date weekday and long/full time-zone inference, direct CLDR style-pattern formatting for exact `dateStyle`/`timeStyle` semantic aliases, `precision`, `timeStyle`
  (`short`/`medium`, plus `long`/`full` with an explicit or inferred `zone` field), and `hour12` option aliases,
  `zone`/`timeZoneName`/`timeZoneStyle` option aliases, Intl-style
  `timeZoneName` value aliases including offset/generic forms, Intl-style field option
  aliases to the matching `*Style` options, and `fractionalSecondDigits` option/field aliases, explicit `era` and `dayPeriod` fields, quarter calendar-period
  fields, and `weekOfYear`/`weekOfMonth` calendar-period
  fields without adding new runtime data. Explicit semantic field-style options
  lower year, era, month, quarter, day, weekday, day-period, and explicit
  hour/minute/second requests to existing CLDR skeleton widths without adding data rows, including
  `2-digit`/`2Digit`/`twoDigit` width aliases. The runtime also supports
  local/stand-alone weekday `c`/`e` pattern fields, text-width best-fit
  matching/width adjustment across the `E`/`e`/`c` field family, and numeric
  local-day `e`/`ee`/`c`/`cc` skeleton pattern synthesis using generated
  first-day-of-week metadata and localized digits. Shared fixtures cover the
  legal semantic date, calendar-period, time, zone, and composite field-set
  matrix plus cross-locale semantic date/era/quarter/day-period/week/time-component/time/hour-cycle/fixed-offset outputs,
  the full semantic `hourCycle` option surface including `hour12` boolean aliases,
  `h11`/`h12`/`h23`/`h24`, and `clock12`/`clock24`, the full semantic `zoneStyle` option surface for the
  UTC/fixed-offset boundary including `Etc/GMT±N` aliases, semantic column-alignment outputs, semantic option
  field requirements and bounds, semantic field-style outputs including explicit
  year, era, month, quarter, day, weekday, day-period, and time-component
  widths, semantic alias canonicalization including duplicate-alias rejection, era,
  extended-year, related Gregorian year, quarter, stand-alone quarter,
  stand-alone month including skeleton-only `l`, direct and semantic week, standalone weekday
  width, numeric local weekday,
  day-of-year and standalone semantic ordinal fields,
  day-of-week-in-month, modified Julian day, bare minute/second,
  milliseconds-in-day, flexible day-period, exact day-period, semantic day-period, fractional-second,
  timezone, composed date+time timezone, hour-cycle, `J` no-marker hour,
  `C` allowed-hour-format order, append-item fallback,
  and best-fit skeleton families across all generated-data
  runtimes. It still
  filters out CLDR skeleton families that the current cross-language pattern
  engines cannot render yet, such as cyclic-year patterns, and shared error
  fixtures pin cyclic-year `U` as unsupported; the checked-in
  `skeleton_coverage.json` report now validates admitted rows, duplicate
  canonical candidates, filtered field accounting, and a zero-unsupported-field
  contract against the generated runtime data, and the ICU4J reference harness now compares direct skeleton
  cases including bare and compound era skeletons, simple date+time skeletons, semantic date/calendar-period
  including full length, era, quarter periods, explicit year/era/month/quarter/day/weekday/day-period
  field styles, standalone/date-only dayPeriod fields, and exact semantic
  `dateStyle`/`timeStyle` aliases for date-only, time-only, and non-weekday
  date+time style patterns, and semantic date+time skeletons using ICU style date widths plus style-aware date-time joins including weekday
  date-time joins composed from separate ICU date/time patterns, explicit
  semantic `h11`/`h12`/`h23`/`h24`/`clock12`/`clock24` hour-cycle time skeletons plus `hour12` boolean aliases,
  semantic auto hour-cycle time skeletons including column alignment through generated CLDR hour-format data, semantic
  explicit hour/minute/second/fractionalSecond component skeletons including
  no-hour minute/second and fractional-second combinations, selected explicit
  time-component style width overrides, selected direct and semantic
  day-of-year/day-of-week-in-month ordinal fields, direct extended-year and
  related-Gregorian-year fields, direct unpadded
  milliseconds-in-day fields, bare skeleton-only `l` standalone-month fields,
  direct local/standalone weekday skeletons where ICU
  preserves the same weekday width and local-day behavior, direct flexible
  day-period skeletons, exact `b` day-period skeletons,
  standalone/time-composed AM/PM skeletons, ordinary semantic `dayPeriod`+time
  skeletons, bare and padded `m`/`s` minute/second
  skeletons, appended week-date skeletons, direct/semantic modified-Julian-day
  fields, padded/semantic milliseconds-in-day fields, and direct era/quarter
  date-time joins composed from separate ICU date/time patterns plus generated
  CLDR join templates where ICU best-pattern formatting or the harness
  synthetic numeric formatter can witness them, while direct
  `K`/`k` zero/one-based hour skeletons, direct `J`/`C` locale-preferred/flexible hour skeletons, locale `u-hc` extensions,
  `h11`/`h12`/`h23`/`h24` hour-cycle overrides, plus direct and semantic timezone skeletons
  for RFC/ISO/lowercase-ISO/localized-GMT/location/specific/generic fixed-offset forms with ICU
  date/time formatting plus harness CLDR week metadata for standalone and
  semantic week fields; semantic style alias witnesses now compare against
  equivalent top-level `dateStyle`/`timeStyle` host formatter options for
  date-only, time-only, and date+time cases where the host and checked-in CLDR
  data match byte-for-byte; zero-offset
  `ZZZZ`/`O`/`v`/`V` label compaction now compares through ICU4J plus the
  harness CLDR zero-label adjustment, and padded/short local weekday
  normalization plus compound skeleton-only `l` normalization now compare
  through narrow harness adjustments; `mf2/check.sh` runs strict ICU4J
  datetime style and skeleton witnesses so mismatches or unsupported fixture cases fail the
  aggregate check. With
  quarter/week/weekday skeleton
  support, compact CLDR flexible day-period rules, and a tiny UTC timezone-name
  payload, the probe is about 56.6 KB raw / 5.2 KB gzip JSON and the packed
  JavaScript prototype is about 25.3 KB raw / 6.1 KB gzip, and generated
  one-locale JavaScript packed chunks are capped at 8.5 KB raw / 2.3 KB gzip
  for lazy frontend/mobile loading. Number-core one-locale JavaScript chunks
  are capped at 1.2 KB raw / 700 B gzip. The same
  `@mojito-mf2/core/cldr-packed` package subpath reconstructs date-time-core
  locale data from the compact string-table resource and is tested against the
  generated full runtime payload plus the one-locale chunks.

## Critical Feature List

The minimum useful ICU-adjacent program is:

1. `locale-core`
   - canonical feature lookup keys such as `en-US`, `pt-BR`, and `sr-Latn`
   - underscore input compatibility
   - Unicode extension stripping for feature lookup when needed
   - structural fallback plus feature-specific parent maps
   - no full locale negotiation or display-name APIs
2. `plural-rules`
   - cardinal and ordinal category selection
   - integer and decimal operands needed by CLDR plural rules
   - generated all-locale data and locale subset builds
3. `number-core`
   - `:number` and `:integer` decimal formatting
   - `:percent` with explicit multiply-by-100 semantics
   - `:currency` with required ISO currency code, currency fraction defaults,
     and a narrow initial display contract of symbol or code
   - locale decimal/group separators, grouping patterns, sign patterns, percent
     signs, and decimal numbering-system digits
   - options for grouping on/off, minimum/maximum fraction digits, and
     minimum/maximum significant digits before adding broader option surfaces
4. `date-time-core`
   - `:date`, `:time`, and `:datetime`
   - public options `dateStyle`, `timeStyle`, and `timeZone`
   - Gregorian calendar first, accepting both `gregorian` and Unicode/ECMA
     `gregory` spellings, locale `u-ca-gregory` extensions, explicit
     `calendar` option precedence, and unsupported calendar-extension rejection
   - locale `u-nu` numbering-system extensions for digit payloads already
     present in the generated date-time data, with unsupported numbering systems
     rejected rather than silently falling back
   - strict input contract: host date/time values or ISO strings converted by
     host libraries, not a new date parser with surprising behavior
   - UTC and fixed-offset time zones as the portable baseline, including
     common UTC/GMT aliases and IANA `Etc/GMT±N` fixed-offset aliases; named
     time zones only where the host library can honor them
   - localized style patterns, quarter names, month names, weekday names, eras,
     day periods, flexible day-period rules, week metadata, UTC timezone names,
     localized GMT-zero/fixed-offset labels, allowed hour-format order, and
     date/time join patterns from generated CLDR data
   - CLDR semantic skeleton lookup for the generated probe set, including
     canonicalization with hyphenated and TR35 camel-case enum value aliases
     plus `date`/`yearMonthDay`/`eraDate`/`eraYearMonthDay`/`eraDateWeekday`/`weekdayEraDate`/`eraYearMonthDayWeekday`/`weekdayEraYearMonthDay`/`dateWeekday`/`weekdayDate`/`yearMonthDayWeekday`/`weekdayYearMonthDay`/`dateTime`/`yearMonthDayTime`/`dateTimeWeekday`/`weekdayDateTime`/`yearMonthDayTimeWeekday`/`weekdayYearMonthDayTime`/`dateTimeWeekdayZone`/`weekdayDateTimeZone`/`zonedDateTimeWeekday`/`zonedWeekdayDateTime`/`yearMonthDayTimeWeekdayZone`/`weekdayYearMonthDayTimeZone`/`zonedYearMonthDayTimeWeekday`/`zonedWeekdayYearMonthDayTime`/`eraDateTimeWeekday`/`weekdayEraDateTime`/`eraYearMonthDayTimeWeekday`/`weekdayEraYearMonthDayTime`/`eraDateTimeWeekdayZone`/`weekdayEraDateTimeZone`/`zonedEraDateTimeWeekday`/`zonedWeekdayEraDateTime`/`eraYearMonthDayTimeWeekdayZone`/`weekdayEraYearMonthDayTimeZone`/`zonedEraYearMonthDayTimeWeekday`/`zonedWeekdayEraYearMonthDayTime`/`eraDateTime`/`eraYearMonthDayTime`/`zonedDateTime`/`dateTimeZone`/`yearMonthDayTimeZone`/`zonedYearMonthDayTime`/`eraDateTimeZone`/`zonedEraDateTime`/`eraYearMonthDayTimeZone`/`zonedEraYearMonthDayTime`/`eraYear`/`eraYearMonth`/`yearMonth`/`eraYearQuarter`/`yearQuarter`/`eraYearWeek`/`yearWeek`/`monthWeek`/`yearMonthWeek`/`eraYearMonthWeek`/`monthDay` field-set aliases,
     `yearOfEra`/`dayOfMonth`/`monthOfYear`/`quarterOfYear`/`dayOfWeek`/`timeZone`/`timeZoneName`
     field aliases plus `hourOfDay`/`minuteOfHour`/`secondOfMinute`
     time-component aliases, `style`/`dateStyle`/`dateLength`, inferred style-only date/time fields including full-date weekday and long/full time-zone inference, direct CLDR style-pattern formatting for exact `dateStyle`/`timeStyle` semantic aliases, `precision`, `timeStyle`
     (`short`/`medium`, plus `long`/`full` with an explicit or inferred `zone` field), and `hour12` option
     aliases, `zone`/`timeZoneName`/`timeZoneStyle` option aliases,
     Intl-style `timeZoneName` value aliases, and
     Intl-style field option aliases to the matching `*Style` options, plus
     `fractionalSecondDigits` option/field aliases,
     semantic `length` values `full`/`long`/`medium`/`short`, explicit semantic `yearStyle`/`eraStyle`/`monthStyle`/`quarterStyle`/`dayStyle`/`weekdayStyle`/`dayPeriodStyle`/`hourStyle`/`minuteStyle`/`secondStyle`
     field-width lowering, with ICU4J witness coverage for date-side style
     lowering and selected time-component width overrides,
     era/extended-year/related-Gregorian-year/quarter/week/standalone-weekday/date/time skeleton coverage,
     including semantic era, quarter calendar-period, weekOfYear/weekOfMonth, and dayPeriod field lowering,
     deterministic best-fit field-width matching,
     day-of-year/day-of-week-in-month/modified-Julian-day and bare
     minute/second skeleton synthesis,
     milliseconds-in-day skeleton synthesis,
     standalone semantic dayOfYear/dayOfWeekInMonth/modifiedJulianDay/millisecondsInDay
     field lowering,
     fractional-second skeleton synthesis from second-bearing time patterns with
     localized decimal separators,
     CLDR append-item fallback composition for missing semantic fields,
     locale-preferred `j` hour-cycle expansion, `J` hour skeletons without
     implicit day-period markers, `C` hour skeletons using CLDR
     `timeData.allowed` order,
     locale `u-hc` extension support, explicit `hourCycle` and `hour12` overrides for `j`/`J`/`C` skeletons,
     and simple date+time skeleton
     composition
5. `conformance`
   - one fixture shape for number/date/time output across every runtime
   - explicit unsupported-case reporting
   - reference comparison against available platform libraries
   - package-size and startup-cost checks for frontend subsets

Everything else should justify itself as either a quick win or a product-driven
extension.

## Initial Scope

### V0: Extract The Pattern

Treat the existing MF2 plural-rule generator as the model for the broader
program:

- keep generated all-locale plural rules as the stable first module
- add package-size gates for generated outputs
- document locale subset generation as a product build contract
- make the locale-core boundary explicit enough that MF2, relative time, and
  future number work do not each invent fallback rules
- keep ICU4J comparison in CI for plural category behavior

### V1: Number-Core Decision And Prototype

Start with the product-critical number subset, not full ICU number formatting:

- generate decimal symbols, grouping patterns, percent patterns, sign patterns,
  numbering-system digits, and currency fraction metadata
- support decimal, integer, percent, and simple currency formatting
- compare against `Intl.NumberFormat`, ICU4J, JDK, Babel, Foundation, PHP Intl,
  and ICU4X where available
- measure all-locale and frontend locale-subset package size before expanding
  options
- deliberately defer compact notation, unit formatting, spellout, and currency
  display names

### V2: Date-Time-Core Decision And Prototype

Date and time formatting is critical, but the native module should not become a
timezone database or calendar engine:

- generate Gregorian date/time style patterns, date-time join patterns, month
  names, weekday names, eras, day periods, and `availableFormats` skeleton
  patterns
- accept host date/time values or strict ISO strings and use host libraries for
  calendar arithmetic and timezone conversion
- support `dateStyle`, `timeStyle`, CLDR pattern-letter `skeleton`, Mojito
  `semantic:` field-set skeletons, `hourCycle`, and `timeZone` with UTC,
  common GMT aliases, and fixed offsets as the portable baseline
- compare against `Intl.DateTimeFormat`, ICU4J, JDK, Babel, Foundation, PHP
  Intl, and ICU4X where available
- decide whether each language should use generated date/time data by default
  or prefer a host adapter for the first release

### V3: Quick-Win Optional Modules

After number/date/time boundaries are clear, add small adjacent modules where
the data and API surface are contained:

- relative time: reuse the existing generator, support locale/style/unit subsets,
  and keep it as an explicit opt-in package
- list formatting: conjunction/disjunction/unit list patterns are often useful
  and much smaller than full number/date formatting
- currency metadata helper: expose fraction digits and cash rounding even when
  full currency display formatting is delegated to a host adapter
- compact-number probe: only after `number-core` is stable; start with a locale
  allowlist and compare against platform compact notation
- pseudolocalization helpers for Mojito/frontend QA; not ICU-compatible, but a
  cheap high-value localization tool

For relative time specifically:

- generate all-locale data for server/reference use
- generate locale/style/unit/numeric-only subsets for frontend and mobile
- compare behavior against `Intl.RelativeTimeFormat`, Babel, ICU4J, and
  Foundation where available
- decide whether the runtime artifact should be JSON, generated native tables,
  a compact packed array format, or language-specific resources
- keep the module out of MF2 core defaults unless the application explicitly
  opts into it

### Deferred

Keep these outside the initial program unless a product use case forces them:

- full non-Gregorian calendars and bundled timezone databases
- collation and search
- segmentation and line breaking
- transliteration
- locale display names
- measurement unit formatting beyond narrow product-specific needs
- spellout/rule-based number formatting
- date interval formatting
- full locale negotiation and likely-subtag maximization

## Frontend Requirements

Frontend packages need stricter rules than server packages:

- root imports must stay tiny and tree-shakeable
- no all-locale resource data in default bundles
- generated subset builds must be reproducible from a locale allowlist
- bundle checks must measure raw, minified, and gzip sizes where relevant
- runtime startup should not include large JSON parsing for common flows
- resource loading must support lazy loading by locale or feature

The default frontend success case is: an app imports locale core, plural rules,
and a locale-subset number/date-time formatter without pulling all-locale data,
then optionally lazy-loads relative time, list formatting, or compact-number
data for the current product locales.

## Conformance And References

Each module needs:

- shared fixtures with expected behavior and unsupported cases
- generated data version metadata
- reference runners for each available host implementation
- cross-language package-boundary tests
- size checks for all-locale and subset artifacts
- hot-loop benchmarks for runtime selection/formatting
- startup or parse-cost measurements for resource-heavy modules

Reference libraries are witnesses, not automatic truth. When ICU4J, ICU4X,
`Intl`, Babel, Foundation, and PHP Intl disagree because of CLDR versions or API
semantics, the fixture should record the difference and the product contract
should choose deliberately.

## Open Questions

- Should this eventually move from `mf2/cldr` into a top-level `unicode/` or
  `cldr/` workspace once more than MF2 consumes it?
- What is the package naming scheme across Maven, npm, PyPI, SwiftPM, Cargo, Go,
  Composer, and Kotlin artifacts?
- Should the JavaScript packed resource prototype become a runtime decoder,
  stay as a size-gate artifact, or split further into lazy locale chunks?
- How often should generated data track CLDR releases, and what compatibility
  policy is needed when CLDR changes observable categories or patterns?
- What minimum product locale allowlist should be used for frontend size gates?
- Should Mojito publish generated data artifacts, or only keep the generators
  and native runtimes in this repo until external consumers appear?

## Immediate Work Items

The first foundation slice is implemented:

- generated plural, experimental number, and experimental date/time size gates
- frontend-style plural subset smoke check
- recursive feature-parent locale lookup conformance across all MF2 runtimes
- experimental number data for decimal/group/percent/currency probe fields
- JavaScript number-core data emitted as a generated module instead of runtime
  JSON and vendored behind an explicit package subpath
- first JavaScript number-core formatter module under `@mojito-mf2/core/number-core`,
  with static fixtures and `Intl.NumberFormat` reference comparisons for the
  probe locales
- Python `number_core` formatter module with generated native CLDR number data,
  shared `number-core` fixtures, Node/Intl reference comparisons, explicit
  registry integration, and formatter benchmark coverage
- Java `Mf2NumberCore` formatter module with generated native CLDR number data,
  shared `number-core` fixtures, JDK `NumberFormat` reference comparisons, and
  explicit registry integration
- Kotlin `Mf2NumberCore` formatter module with generated native CLDR number
  data, shared fixtures, JDK `NumberFormat` reference comparisons, explicit
  registry integration, and formatter benchmark coverage
- Swift `MF2NumberCore` formatter module with generated native CLDR number data,
  shared fixtures, Foundation `NumberFormatter` reference comparisons with
  explicit known-difference reporting, registry integration, and formatter
  benchmark coverage
- Go `NumberCore` formatter module with generated native CLDR number data,
  shared fixtures, Node/Intl reference comparisons, explicit registry
  integration, and formatter benchmark coverage
- Rust `number_core` formatter APIs with generated native CLDR number data,
  shared fixtures, Node/Intl reference comparisons, registry integration, and
  formatter benchmark coverage
- PHP `NumberCore` formatter APIs with generated native CLDR number data,
  shared fixtures, Node/Intl reference comparisons, registry integration, and
  formatter benchmark coverage
- compact JavaScript packed resource prototype for number-core data, with
  deterministic validation, raw/gzip size gates, and an opt-in package decoder
  tested against the generated full runtime payload
- experimental Gregorian date/time presentation data, including generated
  JavaScript runtime data emitted as an object-literal module
- first JavaScript date-time-core formatter module under
  `@mojito-mf2/core/date-time-core`, with static fixtures,
  `Intl.DateTimeFormat` reference comparisons for matching host ICU outputs,
  explicit UTC/calendar alias/error cases, registry integration, and formatter
  benchmark coverage
- Python `date_time_core` formatter module with generated native CLDR date/time
  data, shared `date-time-core` fixtures, Node/Intl reference comparisons,
  explicit registry integration, and formatter benchmark coverage
- Java `Mf2DateTimeCore` formatter module with generated native CLDR date/time
  data, shared `date-time-core` fixtures, JDK `DateTimeFormatter` reference
  comparisons, explicit registry integration, and formatter benchmark coverage
- Kotlin `Mf2DateTimeCore` formatter module with generated native CLDR date/time
  data, shared `date-time-core` fixtures, JDK `DateTimeFormatter` reference
  comparisons, explicit registry integration, and formatter benchmark coverage
- Swift `MF2DateTimeCore` formatter module with generated native CLDR date/time
  data, shared `date-time-core` fixtures, Foundation `DateFormatter` reference
  comparisons with explicit known-difference reporting, registry integration,
  and formatter benchmark coverage
- Go `DateTimeCore` formatter module with generated native CLDR date/time data,
  shared `date-time-core` fixtures, Node/Intl reference comparisons, explicit
  registry integration, and formatter benchmark coverage
- Rust `date_time_core` formatter APIs with generated native CLDR date/time
  data, shared fixtures, Node/Intl reference comparisons, registry integration,
  and formatter benchmark coverage
- PHP `DateTimeCore` formatter APIs with generated native CLDR date/time data,
  shared fixtures, Node/Intl reference comparisons, registry integration, and
  formatter benchmark coverage
- compact JavaScript packed resource prototype for date-time-core data, with
  deterministic validation, raw/gzip size gates, and an opt-in package decoder
  tested against the generated full runtime payload
- `Intl.NumberFormat` and `Intl.DateTimeFormat` reference comparisons
- frontend-oriented CLDR data parse/lookup benchmark
- first JavaScript `relative-time-core` formatter under
  `@mojito-mf2/core/relative-time-core`, using explicit generated CLDR data,
  draft shared relative-time fixtures, generated plural rules, registry
  integration, and no default bundled relative-time payload
- first Python `relative_time_core` formatter under
  `mojito_mf2.relative_time_core`, using explicit generated CLDR data, the same
  draft shared relative-time fixtures, generated plural rules, registry
  integration, and no default bundled relative-time payload
- first Go `RelativeTimeCore` formatter API, using explicit generated CLDR data,
  the same draft shared relative-time fixtures, generated plural rules,
  `Intl.RelativeTimeFormat` reference witnesses, registry integration, and
  benchmark coverage
- first PHP `RelativeTimeCore` formatter API, using explicit generated CLDR
  data, the same draft shared relative-time fixtures, generated plural rules,
  `Intl.RelativeTimeFormat` reference witnesses, registry integration, and
  benchmark coverage
- first Rust `relative_time_core` formatter APIs, using explicit generated CLDR
  data, the same draft shared relative-time fixtures, generated plural rules,
  `Intl.RelativeTimeFormat` reference witnesses, registry integration with
  captured formatter callbacks, and benchmark coverage
- first Java `Mf2RelativeTimeCore` formatter APIs, using explicit generated
  CLDR data, the same draft shared relative-time fixtures, generated plural
  rules, `Intl.RelativeTimeFormat` reference witnesses, opt-in registry
  integration, and benchmark coverage
- first Kotlin `Mf2RelativeTimeCore` formatter APIs, using explicit generated
  CLDR data, the same draft shared relative-time fixtures, generated plural
  rules, `Intl.RelativeTimeFormat` reference witnesses, opt-in registry
  integration, and benchmark coverage
- first Swift `MF2RelativeTimeCore` formatter APIs, using explicit generated
  CLDR data, the same draft shared relative-time fixtures, generated plural
  rules, `Intl.RelativeTimeFormat` reference witnesses, opt-in registry
  integration, and benchmark coverage
- optional Python/Babel reference witnesses for selected numeric
  `relative-time-core` outputs, skipped when the optional Babel dependency is
  not installed
- ICU4J `RelativeDateTimeFormatter` reference comparison for all shared
  experimental relative-time fixture format cases

Next work:

1. Decide whether compact JavaScript resources should become default
   formatter data or remain opt-in decoded resources once a frontend bundle
   consumes the generated lazy locale chunks.
2. Extend number/date-time reference coverage beyond JavaScript `Intl` to ICU4J,
   JDK, Babel, Foundation, PHP Intl, and ICU4X where available.
3. Extend `relative-time-core` validation beyond draft fixture checks,
   JavaScript `Intl.RelativeTimeFormat` witnesses, and Python/Babel numeric
   witnesses plus ICU4J fixture comparison to Foundation, PHP Intl, and ICU4X
   reference comparisons where available.
4. Decide whether list formatting or currency metadata is the first quick-win
   optional module after number/date-time formatter APIs are real.
