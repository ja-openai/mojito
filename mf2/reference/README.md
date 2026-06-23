# MF2 Reference Comparison

Reference harnesses compare Mojito's shared MF2 fixtures against upstream or
widely used implementations. They are intentionally separate from the runtime
libraries so experimental dependencies do not leak into production packages.

Current harnesses:

- `icu4j/`: ICU4J MessageFormat 2 technical preview
- `icu4cxx/`: optional ICU4C++ MessageFormat 2 technical preview harness
- `messageformat-js/`: npm `messageformat` v4 parser/runtime benchmark against
  the native JavaScript core
- `fixtures/currency-simple-vs-icu4j.json`: diagnostic fixture showing where a
  dependency-free sample `:currency` formatter matches or diverges from ICU4J
  currency formatting

Reference results are compatibility signals, not a production API commitment.
ICU's MF2 APIs and syntax support are still marked technical preview.

## Current Local Smoke Results

Run on 2026-05-19 against 35 shared format cases:

- ICU4J 78.3: 29 passed, 2 mismatched, 4 unsupported
- ICU4C++ 77.1: 30 passed, 1 mismatched, 4 unsupported

Known observations:

- Both ICU harnesses reject the unannotated string selector fixtures because MF2
  requires selectors to be tied to a declaration with a function.
- Both ICU harnesses localize Russian numeric output, so `1.5` formats as
  `1,5`.
- ICU4J 78.3 normalized the decomposed accent in the Unicode literal fixture;
  ICU4C++ 77.1 preserved it in the same fixture.

## Date/Time Style And Skeleton Witnesses

The ICU4J harness also compares CLDR date/time style fixture cases against ICU4J
`DateFormat`, including UTC/default timezone output, fixed-offset rollover and
labels, Gregorian calendar override precedence, and locale numbering-system
extensions:

```sh
sh reference/icu4j/run.sh datetime-styles-strict ../../conformance/fixtures/date-time-core/cases.json
```

Run on 2026-06-08 against 27 shared date-time style cases:

- ICU4J 78.3 style witness: 27 passed, 0 failed, 0 unsupported

Direct and selected semantic CLDR date/time skeleton fixture cases compare
against ICU4J date/time formatting as a separate witness:

```sh
sh reference/icu4j/run.sh datetime-skeletons-strict ../../conformance/fixtures/date-time-core/cases.json
```

Run on 2026-06-08 against 373 shared date-time skeleton cases:

- ICU4J 78.3 direct/semantic skeleton witness: 373 passed, 0 failed, 0 unsupported

The current checked-in fixture has no unsupported ICU4J style or skeleton
witness cases. The strict commands fail on either mismatches or unsupported
cases so this zero-unsupported contract stays protected; use the non-strict
commands for exploratory diagnostic runs where unsupported reference cases are
expected.
Micro-runtime-specific boundaries compare through narrow harness adjustments:
standalone and semantic week fields use generated CLDR week metadata and week
patterns, and direct and semantic fractional-second skeletons compare against ICU4J with
localized CLDR decimal symbols, direct extended-year and related-Gregorian-year
skeletons, direct bare era/quarter/day-of-year/day-of-week-in-month and
modified-Julian-day/milliseconds-in-day skeletons with localized digits and
padding, bare skeleton-only `l` standalone-month
skeletons, compound skeleton-only `l` standalone-month skeletons through a
stand-alone month pattern rewrite, direct local/standalone weekday skeletons
including padded numeric local weekdays and CLDR short local-weekday labels,
bare and padded `m`/`s` minute/second skeletons, direct flexible
day-period skeletons, exact `b` day-period skeletons, standalone/time-composed
AM/PM skeletons, appended week-date skeletons, direct era/quarter date-time
skeletons composed from separate ICU date/time patterns plus generated
CLDR join templates, semantic
era/quarter calendar-period skeletons, standalone/date-only semantic
`dayPeriod` fields, semantic `full`/`long`/`medium`/`short` length handling,
and semantic `date`/`eraDate`/`eraDateWeekday`/`weekdayEraDate`/`dateWeekday`/`weekdayDate`/`dateTime`/`dateTimeWeekday`/`weekdayDateTime`/`dateTimeWeekdayZone`/`weekdayDateTimeZone`/`zonedDateTimeWeekday`/`zonedWeekdayDateTime`/`eraDateTimeWeekday`/`weekdayEraDateTime`/`eraDateTimeWeekdayZone`/`weekdayEraDateTimeZone`/`zonedEraDateTimeWeekday`/`zonedWeekdayEraDateTime`/`eraDateTime`/`zonedDateTime`/`dateTimeZone`/`eraDateTimeZone`/`zonedEraDateTime`/`eraYear`/`yearMonth`/`yearQuarter`/`yearWeek`/`monthDay` field-set aliases,
`yearOfEra`/`dayOfMonth`/`monthOfYear`/`quarterOfYear`/`dayOfWeek` field aliases plus
`hourOfDay`/`minuteOfHour`/`secondOfMinute` time-component aliases,
`style`/`dateStyle`/`dateLength`, inferred style-only date/time fields including
full-date weekday inference,
`precision`, `timeStyle` (`short`/`medium`), and
`hour12` option aliases plus Intl-style `timeZoneName` value aliases and explicit
year/era/month/quarter/day/weekday/day-period style options and their
Intl-style field option-key aliases compare
against ICU4J through the same lowering path. Semantic date+time skeletons
compare directly, including ordinary `dayPeriod`+time combinations, semantic
style-aware date-time join selection, and weekday date-time joins composed from
ICU date/time patterns plus the generated CLDR join templates. Semantic auto
hour-cycle time skeletons, including column-aligned cases, compare through ICU4J
after mirroring generated CLDR hour-format data. Semantic `hour12` boolean
aliases compare through the same `clock12`/`clock24` lowering path. Direct
`J`/`C` skeletons, locale `u-hc` extensions, and direct/semantic `h11`/`h24`
hour-cycle boundary cases compare through ICU4J with generated hour-cycle
lowering and attributed hour-run adjustment for the micro-runtime's `K`/`k`
midnight range semantics, and semantic
`timeStyle=short`/`medium`
compare through the same minute/second precision lowering path. Semantic
`timeStyle=long`/`full` compares for exact style aliases and for lowered
semantic skeletons where ICU4J's specific-zone labels match the micro-runtime
fixed-offset boundary.
Component-level semantic `hour`/`minute`/`second`/`fractionalSecond` fields compare when ICU4J
best-pattern formatting can witness the lowered skeleton, including no-hour
minute/second and second-only fractional-second combinations that lower to
ordinary ICU skeletons. Standalone semantic `dayOfYear`,
`dayOfWeekInMonth`, `modifiedJulianDay`, and `millisecondsInDay` fields also
compare where ICU4J's best-pattern formatting or the harness synthetic numeric
formatter matches the micro-runtime lowering.
Semantic `fractionalSecondDigits` aliases are canonicalized before lowering and
compare through the same ICU4J witness path.
Semantic zoneStyle labels including `timeZone`/`timeZoneName` field aliases,
common GMT timezone aliases, IANA `Etc/GMT±N` fixed-offset aliases, and
zero-offset `ZZZZ`/`O`/`v`/`V` label compaction compare through ICU4J plus the
harness CLDR zero-label adjustment. Semantic `weekOfYear` and
`weekOfMonth` fields compare through the harness CLDR week metadata and
week-boundary helpers.
Padded numeric local weekdays and short
`eee` local weekday text compare through the harness localized-padding and CLDR
short-label adjustment. Compound skeleton-only `l` forms compare through the
harness stand-alone month pattern rewrite.
Padded bare `mm`/`ss` minute/second fields compare through explicit-pattern
ICU4J formatting, avoiding best-pattern width loss for standalone fields.
