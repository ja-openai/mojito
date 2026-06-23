# MF2 CLDR Data Generation

This subproject owns generated locale data used by MF2 runtimes. It starts with
plural rules because plural data is small enough to ship selectively and is
updated independently from parser/runtime code.

See `../../dev-docs/design/020-unicode-micro-runtime-roadmap.md` for the
broader plan to evolve these generators into small Unicode/CLDR micro-runtime
modules without turning MF2 into a general ICU replacement.

Regenerate the vendored plural rule implementations for every runtime package:

```sh
sh update_generated.sh
```

Generate a custom locale subset for an embedded or product-specific build:

```sh
python3 generator/generate_plural_rules.py \
  --locales en,fr,ru,ar,ja \
  --targets java \
  --java-source-root \
  --out /tmp/mf2-plurals-custom
```

The checked-in runtime data is:

- `generated/all`: every locale present in CLDR supplemental plural data. This
  is the default runtime data used by Rust, Swift, Python, Java, Kotlin,
  JavaScript, Go, and PHP.

Current size smoke results from CLDR `main` on 2026-05-19:

- all CLDR plural locales: JSON ~125 KB, Python ~116 KB, Rust ~62 KB, Swift
  ~81 KB, Java ~71 KB, Kotlin ~64 KB, JavaScript ~53 KB, PHP ~69 KB

Locale filtering remains a generator capability, not a first-class checked-in
artifact. For embedded clients, generate a product locale allowlist in the
client build and validate it against the same conformance/ICU comparison tools.

The generator emits:

- `plural_rules.json`: compact shared rule data and metadata
- `python/cldr_plural_rules.py`: Python evaluator and generated data
- `rust/cldr_plural_rules.rs`: Rust evaluator and generated data
- `swift/CldrPluralRules.swift`: Swift evaluator and generated data
- `java/com/box/l10n/mojito/mf2/CldrPluralRules.java`: Java evaluator
  and generated data
- `kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt`: Kotlin
  evaluator and generated data
- `javascript/cldr_plural_rules.js`: JavaScript evaluator and generated data
- `go/cldr_plural_rules.go`: Go evaluator and generated data
- `php/CldrPluralRules.php`: PHP evaluator and generated data

Use `--targets` to emit only the files a language build needs. For example,
`--java-package` controls the generated package name and `--java-source-root`
targets a Java source tree directly.

The generated evaluators intentionally depend on each runtime's tiny
`LocaleKey` helper for canonicalization and structural lookup. The generated
part owns plural rule data and plural-specific parent maps; `LocaleKey` owns the
shared string algorithm and remains independent of parser/runtime formatting.

Locale IDs in generated data are canonical BCP47-style keys such as `pt-PT`,
not CLDR underscore keys. The generated evaluators accept underscore input for
compatibility, strip extensions such as `u-nu-latn` for plural lookup, and walk
the structural fallback chain. The `parents` maps are generated only from
plural-specific parent locale data. CLDR's general resource `parentLocales`
rules are intentionally not applied to plural selection; ICU4J comparison probes
cover cases like `pt-AO`, `sr-Latn`, `az-Arab`, and Unicode extensions.

Runtime packages vendor the all-locale generated file into their package source
trees so they remain installable without reaching outside the package. Use
`check_generated.sh` in CI to fail when vendored generated files drift from the
shared generator.

Validate generated all-locale cardinal and ordinal category selection against
ICU4J `PluralRules`:

```sh
sh validate_plural_rules.sh
```

This compares category keywords only, not formatted message output. Number
formatting and localized decimal separators belong in later number-formatting
tests.

Check generated plural package-size gates and a compact frontend-style locale
subset smoke artifact:

```sh
sh check_size_gates.sh
```

The size gate intentionally measures both raw and gzip-compressed bytes. It uses
the checked-in compact JSON to smoke-check a small locale allowlist without
fetching CLDR over the network; real product subset builds should still use
`generate_plural_rules.py --locales ...` so native runtime sources are generated
from the current CLDR input.

## Experimental Number Data

`generated/experimental-number/number_data.json` is an experimental,
drop-without-migration probe for generated number/currency data. It should not
be treated as a stable data contract. The current artifact keeps a deliberately
tiny set of probe locales and currencies so we can compare shape, size, and ICU
behavior before deciding how broadly generated number formatting should ship.

The generator also emits `generated/experimental-number/javascript/number_data.js`
and can vendor the same generated module into the JavaScript runtime package via
`--javascript-runtime-out`. That module is an object literal, not runtime JSON,
so frontend consumers do not pay a JSON parse cost when importing number-core.
It also emits
`generated/experimental-number/javascript/number_data_packed.js`, a validated
string-table resource prototype for frontend/mobile bundles that want a more
compact representation than the debuggable generated object module. JavaScript
exposes an opt-in `@mojito-mf2/core/cldr-packed` decoder for this resource
shape. The generator also emits one-locale packed chunks under
`generated/experimental-number/javascript/packed-locales/`, so product bundles
can lazy-load only the number-core data for the requested locale. The validator
checks that each chunk decodes to the same locale payload as the full generated
resource, and size gates cap the per-locale chunk sizes.
It also emits `generated/experimental-number/python/mojito_mf2/_cldr_number_data.py`
and can vendor it into the Python package via `--python-runtime-out`, keeping
Python generated-data consumers on native module import instead of runtime JSON.
The same generator emits `generated/experimental-number/rust/cldr_number_data.rs`
and can vendor it into the Rust crate via `--rust-runtime-out`. It also emits
`generated/experimental-number/java/com/box/l10n/mojito/mf2/CldrNumberData.java`
and can vendor it into the Java package via `--java-runtime-out`, exercising the
native generated-table approach outside JavaScript. It also emits
`generated/experimental-number/kotlin/com/box/l10n/mojito/mf2/CldrNumberData.kt`
and can vendor it into the Kotlin/JVM package via `--kotlin-runtime-out`.
Finally, it emits `generated/experimental-number/swift/CldrNumberData.swift`
and can vendor it into the SwiftPM target via `--swift-runtime-out`.
It also emits `generated/experimental-number/go/cldr_number_data.go` and can
vendor it into the Go package via `--go-runtime-out`. It emits
`generated/experimental-number/php/CldrNumberData.php` and can vendor it into
the PHP package via `--php-runtime-out`.

Regenerate the experimental data:

```sh
python3 generator/generate_number_data.py --out generated/experimental-number --clean
```

Regenerate and vendor the JavaScript runtime data module:

```sh
python3 generator/generate_number_data.py \
  --out generated/experimental-number \
  --javascript-runtime-out ../javascript/src/cldr_number_data.js \
  --clean
```

Regenerate and vendor JavaScript, Python, Rust, Java, Kotlin, Swift, Go, and PHP
runtime data modules:

```sh
python3 generator/generate_number_data.py \
  --out generated/experimental-number \
  --javascript-runtime-out ../javascript/src/cldr_number_data.js \
  --python-runtime-out ../python/src/mojito_mf2/_cldr_number_data.py \
  --rust-runtime-out ../rust/mojito-mf2/src/cldr_number_data.rs \
  --java-runtime-out ../java/src/main/java/com/box/l10n/mojito/mf2/CldrNumberData.java \
  --kotlin-runtime-out ../kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrNumberData.kt \
  --swift-runtime-out ../swift/MessageFormat2/Sources/MessageFormat2/CldrNumberData.swift \
  --go-runtime-out ../go/cldr_number_data.go \
  --php-runtime-out ../php/src/CldrNumberData.php \
  --java-package com.box.l10n.mojito.mf2 \
  --kotlin-package com.box.l10n.mojito.mf2 \
  --clean
```

Generate a custom probe set:

```sh
python3 generator/generate_number_data.py \
  --locales en-US,fr-FR,ja-JP,ar-EG \
  --currencies USD,EUR,JPY \
  --out /tmp/mf2-number-probe
```

Validate the checked-in experimental artifact:

```sh
sh validate_number_data.sh
```

The validator checks the data contract, compares decimal, grouping, and percent
symbols against `Intl.NumberFormat.formatToParts` for the probe locales, and
verifies that the compact JavaScript packed resource and each one-locale packed
chunk are regenerated from the same source data.
Runtime package tests then use the shared `number-core` fixture to compare
formatter output against JavaScript `Intl`, Python/Rust/Go/PHP-via-Node
`Intl`, JDK `NumberFormat`, and Swift Foundation `NumberFormatter` witnesses.

Keep full date/calendar data out of this subproject until needed. Plural rules
are small enough to ship broadly; number/currency data needs this separate
experimental track before it becomes a runtime dependency.

The generator currently sets compact decimal operands `c`/`e` to zero. That is
correct for ordinary numeric arguments but not enough for compact-decimal
selection semantics; wire runtime number formatting into operands before relying
on compact exponent rules.

## Experimental Date/Time Data

`generated/experimental-datetime/date_time_data.json` is an experimental,
drop-without-migration probe for Gregorian date, time, and datetime presentation
data. It intentionally stores CLDR display data only: date/time style patterns,
date-time join patterns, `availableFormats` semantic skeleton patterns, quarter
names, month names, weekday names, eras, day periods, flexible day-period rules,
week metadata, append-item overrides, field names needed by append templates,
default numbering systems, decimal numbering-system digits, and the locale
decimal separator needed by fractional-second skeletons. The runtime accepts
both `gregorian` and the Unicode/ECMA `gregory` calendar alias for this data,
including locale `u-ca-gregory` extensions when no explicit calendar option
overrides them. Unsupported calendar extensions are rejected rather than
silently formatted with Gregorian data. Locale `u-nu` numbering-system
extensions reuse digit strings already present in the generated locale payload;
unsupported numbering systems are rejected instead of pulling in the full CLDR
numbering-system table. Timezone conversion, timezone databases, date
arithmetic, and non-Gregorian calendars stay out of this artifact until a product
requirement justifies them. The first skeleton data slice filters out
CLDR skeleton families whose pattern fields are not implemented consistently in
the cross-language pattern engines yet, including cyclic-year formats. Week
year/week-of-year/week-of-month skeletons use supplemental CLDR first-day and
minimum-days week data while date arithmetic stays in the tiny runtime helpers.
Extended-year `u` and Gregorian related-year `r` skeletons reuse generated `y`
availableFormats through `y`/`u`/`r` field-family matching and rewrite the
pattern field at runtime; cyclic-year names and non-Gregorian related-year
calendar behavior remain out of scope. The shared error fixture pins cyclic-year
`U` skeletons as unsupported until that data is intentionally added. Bare era
`G` skeletons and semantic `era` field skeletons reuse the generated era-name
payload already needed by compound era date patterns, so they add coverage
without increasing CLDR data size.
`generated/experimental-datetime/skeleton_coverage.json` records the raw CLDR
`availableFormats` rows seen for each probe locale, how many canonical rows are
admitted into the runtime data, duplicate canonical candidates, and any
unsupported skeleton/pattern fields filtered out by the current cross-language
pattern engines. The checked-in validator runs in strict mode for the current
probe and fails if any unsupported skeleton or pattern field is filtered out.
This is a generated validation artifact, not runtime data.
Standalone/local weekday skeletons include CLDR `c`/`e` pattern fields so
locale data such as German `ccc` weekday abbreviations can use stand-alone
weekday names, and runtime best-fit matching shares text-width requests across
the `E`/`e`/`c` weekday field family while preserving the requested context.
Numeric local-day `e`/`ee`/`c`/`cc` skeletons are synthesized from the same
weekday pattern candidates, preserving first-day-of-week metadata and localized
digits instead of silently mapping to text weekday names. Semantic `weekOfYear`
and `weekOfMonth` field lowering reuses the generated CLDR week metadata plus
the existing `yw` and `MMMMW` skeleton patterns, including locale-specific
week-year boundary behavior, without adding more CLDR payload. Stand-alone
month `L` skeletons reuse the existing month pattern candidates through `M`/`L`
field-family matching, and skeleton-only `l` requests normalize to `L` because
`l` is not a CLDR pattern field. This preserves the requested context without
adding more CLDR payload. Day-period skeletons similarly match across `a`/`b`/`B`;
bare day-period requests use a tiny synthetic pattern, while time skeletons
reuse generated flexible day-period patterns and rewrite the pattern field back
to the requested period semantics. Semantic `dayPeriod` field lowering reuses
the same tiny synthetic pattern and generated flexible day-period rule payload,
so standalone, date-only, and time-composed semantic period requests add
coverage without increasing generated data size. Fractional-second `S`
skeletons are
synthesized from second-bearing time patterns by inserting the CLDR pattern
field after the seconds run with the locale decimal separator, preserving
localized digits without pulling in the full number-symbol table. Quarter
skeletons are included with a compact quarter-name payload for the widths used
by the supported generated `availableFormats`. Stand-alone
quarter overrides are generated only when they differ from format quarter data,
and runtime best-fit matching lets `q` skeletons reuse the existing `Q`
availableFormats while preserving stand-alone context. Flexible
day-period skeletons use compact supplemental CLDR rules for selecting periods
such as morning, afternoon, evening, and night. UTC and fixed-offset timezone
skeleton/style fields use a tiny CLDR `timeZoneNames` payload for localized
`z` names and localized GMT-zero/fixed-offset `v`/`O` labels. Timezone parsing
accepts common UTC/GMT aliases plus IANA `Etc/GMT±N` fixed-offset aliases and
then routes them through the same offset arithmetic path. Runtime
best-fit matching shares requests across the `z`/`Z`/`O`/`v`/`V`/`X`/`x`
timezone field family and rewrites generated `v` patterns back to the requested
zone presentation, with bare timezone skeletons using tiny synthetic patterns;
named timezone databases remain out of scope.
Shared fixtures also cover composed date+time skeletons with UTC/fixed-offset
timezone fields so rollover, localized GMT labels, and date/time join behavior
stay aligned across runtimes. Direct lowercase ISO `x` offsets and `V`
location-style labels pin `X`/`x` zero-offset behavior and fixed-offset
location presentation explicitly.
Locale `u-hc` Unicode extensions are honored for semantic `j`/`J`/`C` hour
skeletons when no explicit `hourCycle` option is supplied; explicit options
take precedence, and invalid extension values are rejected consistently.
Shared direct skeleton fixtures also pin TR35 `K` zero-based 12-hour and `k`
one-based 24-hour behavior, including date+time composition.
Runtime `J` skeleton handling strips implicit day-period markers from matched
12-hour patterns, while `C` traverses generated supplemental CLDR
`timeData.allowed` hour-format order and picks the first supported token such
as `h`, `H`, `K`, `hb`, or `hB`. This keeps `C` data-driven without shipping the
full supplemental time-data table at runtime.
Component-level semantic `hour`/`minute`/`second`/`fractionalSecond` fields
lower to the same existing time skeleton engine, so
`semantic:fields=hour,minute,second,fractionalSecond;fractionalSecond=3` and
date+component combinations add API coverage without adding CLDR payload; bare
semantic minute/second and second+fractionalSecond fields reuse the same tiny
synthetic numeric paths as direct `m`/`s`/`sS` skeletons. Explicit
`hourStyle`, `minuteStyle`, and `secondStyle` options lower to numeric or
two-digit widths for those component fields without adding data; the parsers
accept `2-digit`, `2Digit`, and `twoDigit` spellings for those width values.
Common semantic aliases canonicalize before duplicate checking:
`date` and `yearMonthDay` expand to `year`/`month`/`day`, `eraDate` and
`eraYearMonthDay` expand to
`era`/`year`/`month`/`day`, `eraDateWeekday`, `weekdayEraDate`,
`eraYearMonthDayWeekday`, and `weekdayEraYearMonthDay` expand to
`era`/`year`/`month`/`day` plus `weekday`, `dateWeekday`, `weekdayDate`,
`yearMonthDayWeekday`, and `weekdayYearMonthDay` expand to
`year`/`month`/`day` plus `weekday`, `dateTime` and `yearMonthDayTime` expand to
`year`/`month`/`day` plus `time`, `dateTimeWeekday`, `weekdayDateTime`,
`yearMonthDayTimeWeekday`, and `weekdayYearMonthDayTime` expand to
`year`/`month`/`day` plus `weekday` and `time`, `eraDateTime` and
`eraYearMonthDayTime` expand to
`era`/`year`/`month`/`day` plus `time`, `eraDateTimeWeekday`,
`weekdayEraDateTime`, `eraYearMonthDayTimeWeekday`, and
`weekdayEraYearMonthDayTime` expand to `era`/`year`/`month`/`day` plus `weekday` and
`time`, `zonedDateTime`, `dateTimeZone`, `yearMonthDayTimeZone`, and
`zonedYearMonthDayTime` expand to `year`/`month`/`day` plus `time` and `zone`,
`dateTimeWeekdayZone`/`weekdayDateTimeZone`/`zonedDateTimeWeekday`/`zonedWeekdayDateTime`/
`yearMonthDayTimeWeekdayZone`/`weekdayYearMonthDayTimeZone`/`zonedYearMonthDayTimeWeekday`/
`zonedWeekdayYearMonthDayTime` expand to `year`/`month`/`day` plus `weekday`, `time`, and `zone`,
`eraDateTimeZone`, `zonedEraDateTime`, `eraYearMonthDayTimeZone`, and
`zonedEraYearMonthDayTime` expand to `era`/`year`/`month`/`day` plus `time`
and `zone`, `eraDateTimeWeekdayZone`/`weekdayEraDateTimeZone`/
`zonedEraDateTimeWeekday`/`zonedWeekdayEraDateTime`/`eraYearMonthDayTimeWeekdayZone`/
`weekdayEraYearMonthDayTimeZone`/`zonedEraYearMonthDayTimeWeekday`/
`zonedWeekdayEraYearMonthDayTime` expand to `era`/`year`/`month`/`day` plus `weekday`, `time`, and `zone`,
`eraYear` expands to `era`/`year`, `yearMonth` expands to `year`/`month`,
`yearQuarter` expands to `year`/`quarter`, `yearWeek` expands to
`year`/`weekOfYear`, and `monthDay` expands to `month`/`day`,
`yearOfEra`/`dayOfMonth`/`monthOfYear`/`quarterOfYear` lower to
`year`/`day`/`month`/`quarter`, `hourOfDay`/`minuteOfHour`/`secondOfMinute` lower to
`hour`/`minute`/`second`, `timeZoneName` lowers to `zone`, `style`, `dateStyle`, and
`dateLength` lower to `length`, `precision` lowers to
`timePrecision`, `timeStyle=short`/`medium` lowers to `minute`/`second`
`timePrecision`, `hour12=true`/`false` lowers to `clock12`/`clock24`
`hourCycle`, `timeStyle=long`/`full` requires an explicit `zone` field and
lowers to second precision plus short/long specific-zone presentation,
`zone`, `timeZoneName`, and `timeZoneStyle` option keys lower to `zoneStyle`,
Intl-style `timeZoneName` values `short`/`long` lower to `specific`,
`shortOffset`/`longOffset` lower to `offset`, and
`shortGeneric`/`longGeneric` lower to `generic`, and
Intl-style field option keys such as `year`, `month`, `day`, `weekday`,
`dayPeriod`, `hour`, `minute`, and `second` lower to their matching `*Style`
options. The existing
`fractionalSecondDigits` is accepted as both the field and option spelling for
`fractionalSecond`. This keeps the semantic API close to familiar Intl/ICU
option names without shipping alternate data rows.
When `fields` is omitted, explicit `style`/`dateStyle`/`dateLength` aliases
infer `date`, `timeStyle` infers `time`, and `timeStyle=long`/`full` also
infers `zone`; bare `length` remains invalid because it does not identify a
date or time field set.
Explicit semantic field-style options also stay data-free:
Semantic `length` accepts `full`, `long`, `medium`, and `short` using the
generated CLDR style-pattern widths where those fields are present.
`yearStyle` lowers to full, numeric, two-digit, or era-bearing years;
`eraStyle`, `weekdayStyle`, and `dayPeriodStyle` lower to short, long, or
narrow text widths; `monthStyle` and `quarterStyle` lower to numeric,
two-digit, short, long, or narrow CLDR skeleton widths; `dayStyle` lowers
to numeric or two-digit day-of-month widths; and `hourStyle`/`minuteStyle`/`secondStyle`
lower to numeric or two-digit time-component widths. The `auto` default
keeps the existing semantic length behavior, so style overrides add API
coverage without changing the generated CLDR payload.
Single-field numeric skeletons for day-of-year `D`,
day-of-week-in-month `F`, modified Julian day `g`, bare minute `m`, bare second
`s`, and milliseconds-in-day `A` use tiny synthetic patterns as well, so
localized digits and padding work without adding locale-specific
`availableFormats` rows when CLDR omits those forms. The generator admits
`D`/`F`/`g`/`A` patterns when CLDR provides them, but the current probe set
relies on runtime synthesis for these portable numeric fields. Standalone
semantic `dayOfYear`, `dayOfWeekInMonth`, `modifiedJulianDay`, and
`millisecondsInDay` fields lower to those same `D`/`F`/`g`/`A` synthetic paths,
with column alignment mapped to fixed-width `DDD`/`FF`/`gggggg`/`AAAAAAAA`
requests.
When no exact or same-field-set `availableFormats` row exists, runtime skeleton
matching can append missing semantic fields using CLDR `appendItems`. Common
append templates live as tiny runtime defaults; generated locale data stores
only overrides, plus localized field names for templates that use CLDR's `{2}`
field-name placeholder. The append fallback quotes field names as pattern
literals and still routes mixed date+time skeletons through the existing
date/time join path.

The generator also emits
`generated/experimental-datetime/javascript/date_time_data.js` and can vendor
the same generated module into the JavaScript runtime package via
`--javascript-runtime-out`. That module is an object literal, not runtime JSON,
so frontend consumers do not pay a JSON parse cost when importing
date-time-core. It also emits
`generated/experimental-datetime/javascript/date_time_data_packed.js`, a
validated string-table resource prototype for compact frontend/mobile
date-time-core bundles. JavaScript exposes an opt-in
`@mojito-mf2/core/cldr-packed` decoder for this resource shape. It also emits
one-locale packed chunks under
`generated/experimental-datetime/javascript/packed-locales/`, so product
bundles can lazy-load only the date-time skeleton data for the requested
locale. The validator checks that each chunk decodes to the same locale payload
as the full generated resource, and size gates cap the per-locale chunk sizes.
The same
generator emits
`generated/experimental-datetime/java/com/box/l10n/mojito/mf2/CldrDateTimeData.java`
and can vendor it into the Java package via `--java-runtime-out`, exercising the
native generated-table approach for date-time-core outside JavaScript. It also
emits
`generated/experimental-datetime/python/mojito_mf2/_cldr_date_time_data.py`
and can vendor it into the Python package via `--python-runtime-out`, keeping
the Python generated-data module importable without parsing runtime JSON. It
also emits `generated/experimental-datetime/rust/cldr_date_time_data.rs` and
can vendor it into the Rust crate via `--rust-runtime-out`. It emits
`generated/experimental-datetime/kotlin/com/box/l10n/mojito/mf2/CldrDateTimeData.kt`
and can vendor it into the Kotlin/JVM package via `--kotlin-runtime-out`.
Finally, it emits `generated/experimental-datetime/swift/CldrDateTimeData.swift`
and can vendor it into the SwiftPM target via `--swift-runtime-out`.
It also emits `generated/experimental-datetime/go/cldr_date_time_data.go` and
can vendor it into the Go package via `--go-runtime-out`. It emits
`generated/experimental-datetime/php/CldrDateTimeData.php` and can vendor it
into the PHP package via `--php-runtime-out`.

Regenerate the experimental data:

```sh
python3 generator/generate_datetime_data.py \
  --out generated/experimental-datetime \
  --clean
```

Regenerate and vendor the JavaScript runtime data module:

```sh
python3 generator/generate_datetime_data.py \
  --out generated/experimental-datetime \
  --javascript-runtime-out ../javascript/src/cldr_date_time_data.js \
  --clean
```

Regenerate and vendor JavaScript, Python, Rust, Java, Kotlin, Swift, Go, and PHP
runtime data modules:

```sh
python3 generator/generate_datetime_data.py \
  --out generated/experimental-datetime \
  --javascript-runtime-out ../javascript/src/cldr_date_time_data.js \
  --python-runtime-out ../python/src/mojito_mf2/_cldr_date_time_data.py \
  --rust-runtime-out ../rust/mojito-mf2/src/cldr_date_time_data.rs \
  --java-runtime-out ../java/src/main/java/com/box/l10n/mojito/mf2/CldrDateTimeData.java \
  --kotlin-runtime-out ../kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrDateTimeData.kt \
  --swift-runtime-out ../swift/MessageFormat2/Sources/MessageFormat2/CldrDateTimeData.swift \
  --go-runtime-out ../go/cldr_date_time_data.go \
  --php-runtime-out ../php/src/CldrDateTimeData.php \
  --java-package com.box.l10n.mojito.mf2 \
  --kotlin-package com.box.l10n.mojito.mf2 \
  --clean
```

Generate a custom probe set:

```sh
python3 generator/generate_datetime_data.py \
  --locales en-US,fr-FR,ja-JP,ar-EG \
  --out /tmp/mf2-datetime-probe \
  --clean
```

Validate the checked-in experimental artifact:

```sh
sh validate_datetime_data.sh
```

The validator checks the data contract, requires the probe skeletons used by
the shared fixtures, verifies the generated skeleton coverage report against
the admitted `availableFormats` rows, requires zero unsupported CLDR
skeleton/pattern fields in the generated coverage report, and compares localized
month, weekday, and AM/PM names against `Intl.DateTimeFormat.formatToParts` with
`timeZone=UTC`.
It also compares the shared `date-time-core` Intl reference subset, including
date-style Gregorian calendar alias/override cases and a fixed-offset rollover
case, against the JavaScript generated-data runtime for style-pattern output,
and verifies that the compact JavaScript packed resource and each one-locale
packed chunk are regenerated from the same source data.
Runtime package tests then use the shared `date-time-core` fixture to compare
formatter output against JavaScript `Intl.DateTimeFormat`,
Python/Rust/Go/PHP-via-Node `Intl.DateTimeFormat`, Java/Kotlin JDK `DateTimeFormatter`, and Swift
Foundation `DateFormatter` witnesses where the checked-in CLDR data and host
ICU/JDK/Foundation data agree byte-for-byte. CLDR semantic skeletons are tested
as static generated-data expectations across the probe locale set because
ECMA-402 `Intl.DateTimeFormat` does not expose skeletons directly. The ICU4J
reference harness additionally compares all shared style cases against ICU4J
`DateFormat` and all current direct/semantic skeleton cases against ICU4J best-pattern
formatting, `SimpleDateFormat`, and narrow synthetic helpers for
micro-runtime-only fields. The strict ICU4J commands fail on mismatches or
unsupported fixture cases, so reference coverage stays explicit without
pretending ICU4J and the tiny runtime have the same implementation boundary.
Shared semantic fixtures pin the full supported `hourCycle` option surface,
including the `hour12` boolean alias, `h11`/`h24`, and the TR35
`clock12`/`clock24` aliases.
They also pin all supported `zoneStyle` values (`auto`, `generic`, `specific`,
`location`, and `offset`) against the tiny runtime's UTC/fixed-offset timezone
boundary.

## Relative-Time Data

`generated/relative-time/all/relative_time.json` is a generated CLDR
relative-time data artifact for a future optional `:relativeTime` function
package. It is not part of the tiny MF2 core runtime yet. The data comes from
CLDR `cldr-dates-full` `dateFields` and keeps localized numeric patterns such
as `{0}m ago`, `in {0} days`, and locale-specific forms that deliberately omit
`{0}` for categories like Arabic `one`/`two`.

Regenerate all-locale relative-time data:

```sh
python3 generator/generate_relative_time_data.py \
  --out generated/relative-time/all \
  --clean
```

Generate an embedded/client-side subset:

```sh
python3 generator/generate_relative_time_data.py \
  --locales en,fr,ja \
  --styles narrow,short \
  --units second,minute,hour,day \
  --numeric-only \
  --out /tmp/mf2-relative-time-custom \
  --clean
```

Validate the checked-in relative-time artifact:

```sh
sh validate_relative_time_data.sh
```

The validator reads known CLDR samples from
`fixtures/relative-time-patterns.json` so future generator changes keep the data
contract visible instead of burying sample expectations in Python code.

Current size smoke results from CLDR `main` on 2026-05-21:

- all locales, long/short/narrow, numeric patterns plus natural relative terms:
  JSON ~2.9 MB, gzip ~123 KB, deduplicated to 269 pattern sets for 766 locales
- all locales, long/short/narrow, numeric-only: JSON ~1.5 MB
- all locales, narrow-only, numeric-only: JSON ~518 KB

MF2 does not currently standardize a `:relativeTime` function. Treat this data
as the generated-locale-data foundation for Mojito/UMF registry functions, not
as a core MessageFormat 2 grammar feature. JavaScript exposes an experimental
data-explicit `@mojito-mf2/core/relative-time-core` formatter and registry
helper that consumes this resource without bundling it into the package by
default.

Do not silently ship the all-locale artifact in tiny runtime cores. Java/Kotlin
relative-time support should be an explicit CLDR/ICU adapter with a resource
packaging decision: full all-locale server data, generated locale subsets for
embedded clients, or platform ICU where the host provides a supported API.
