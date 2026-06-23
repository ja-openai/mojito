# ICU4J Reference Harness

Compares shared MF2 source fixtures against ICU4J's MessageFormat 2 technical
preview.

Run correctness comparison:

```sh
sh reference/icu4j/run.sh compare ../../conformance/fixtures/source-to-model
```

Run warmed formatter throughput:

```sh
sh reference/icu4j/run.sh bench ../../conformance/fixtures/source-to-model 100000 10000
```

Emit isolated plural category oracle rows for generated CLDR data:

```sh
sh reference/icu4j/run.sh plural-categories ../../cldr/generated/all/plural_rules.json
```

Compare the shared experimental relative-time fixture against ICU4J
`RelativeDateTimeFormatter`:

```sh
sh reference/icu4j/run.sh relative-time ../../conformance/fixtures/functions/relative-time-duration-v0.json
```

Compare CLDR date/time style cases against ICU4J `DateFormat`:

```sh
sh reference/icu4j/run.sh datetime-styles-strict ../../conformance/fixtures/date-time-core/cases.json
```

The style harness covers fixture cases without skeletons, including
`dateStyle`, `timeStyle`, date+time joins, UTC/default timezone output,
fixed-offset timezone rollover and labels, Gregorian calendar override
precedence, and locale numbering-system extensions. The current checked-in
date-time style fixture has no unsupported ICU4J witness cases.

Compare direct CLDR date/time and selected semantic skeleton cases against ICU4J
`DateTimePatternGenerator`, `DateFormat`, and `SimpleDateFormat`:

```sh
sh reference/icu4j/run.sh datetime-skeletons-strict ../../conformance/fixtures/date-time-core/cases.json
```

The skeleton harness compares fixture cases whose skeletons map directly to
ICU4J best-pattern formatting, including simple date+time skeletons without
append-only fields. It also lowers semantic date/calendar-period and semantic
date+time skeletons using ICU style date-pattern field widths, including
semantic `full`/`long`/`medium`/`short` length handling, semantic era and
quarter calendar-period skeletons, explicit semantic
`h12`/`h23`/`clock12`/`clock24` hour-cycle time skeletons plus semantic
`hour12` boolean aliases, non-column semantic
auto hour-cycle time skeletons through ICU `j`,
semantic `date`/`eraDate`/`eraDateWeekday`/`weekdayEraDate`/`dateWeekday`/`weekdayDate`/`dateTime`/`dateTimeWeekday`/`weekdayDateTime`/`dateTimeWeekdayZone`/`weekdayDateTimeZone`/`zonedDateTimeWeekday`/`zonedWeekdayDateTime`/`eraDateTimeWeekday`/`weekdayEraDateTime`/`eraDateTimeWeekdayZone`/`weekdayEraDateTimeZone`/`zonedEraDateTimeWeekday`/`zonedWeekdayEraDateTime`/`eraDateTime`/`zonedDateTime`/`dateTimeZone`/`eraDateTimeZone`/`zonedEraDateTime`/`eraYear`/`yearMonth`/`yearQuarter`/`yearWeek`/`monthDay` field-set aliases,
semantic `yearOfEra`, `dayOfMonth`,
`monthOfYear`, `quarterOfYear`, `dayOfWeek`, `hourOfDay`, `minuteOfHour`,
`secondOfMinute`, `timeZone`, `timeZoneName`, and
`fractionalSecondDigits` aliases, semantic `style`/`dateStyle`/`dateLength`,
inferred style-only date/time fields including full-date weekday inference,
`precision`, `timeStyle` (`short`/`medium`, plus explicit- or inferred-zone `long`/`full`),
and `hour12` option aliases,
semantic `zone`/`timeZoneName`/`timeZoneStyle` option
aliases, semantic Intl-style `timeZoneName` value aliases including
offset/generic aliases, semantic Intl-style
field option-key aliases to the matching `*Style` options, semantic
`yearStyle`/`eraStyle`/`monthStyle`/`quarterStyle`/`dayStyle`/`weekdayStyle`/`dayPeriodStyle`
lowering including explicit month/quarter/weekday/day-period width overrides, and
component-level semantic
`hour`/`minute`/`second`/`fractionalSecond` fields where ICU best-pattern
formatting can witness them, semantic no-hour minute/second and fractional-second
combinations that lower to ordinary ICU skeletons, selected standalone semantic `dayOfYear` and
`dayOfWeekInMonth` fields, standalone/date-only
semantic `dayPeriod` fields, and
style-aware semantic date-time join selection including composed weekday
date-time joins,
direct extended-year and related-Gregorian-year skeletons, direct bare
era/quarter/day-of-year/day-of-week-in-month, modified-Julian-day, and
milliseconds-in-day
skeletons, bare skeleton-only `l` standalone-month skeletons, appended week-date
skeletons, direct and semantic week skeletons through harness CLDR week
metadata and week patterns, direct era/quarter date-time joins composed from separate ICU
date/time patterns plus generated CLDR join templates, direct `K`/`k` zero/one-based
hour skeletons, `J`/`C` locale-preferred/flexible hour skeletons, locale `u-hc` hour-cycle extensions,
`h11`/`h12`/`h23`/`h24` hour-cycle overrides, direct local/standalone weekday
skeletons including padded local weekday numbers and CLDR short local-weekday
labels, direct flexible day-period
skeletons, exact `b` day-period skeletons, standalone/time-composed AM/PM
skeletons, semantic `dayPeriod`+time skeletons,
bare and padded `m`/`s` minute/second skeletons, and
direct/semantic fixed-offset timezone skeletons when they map to ordinary ICU
skeletons, including lowercase ISO `x`, location-style `V`, and zero-offset
`ZZZZ`/`O`/`v`/`V` label compaction through the harness CLDR zero-label
adjustment, and compound skeleton-only `l`
stand-alone month normalization through an explicit pattern rewrite. Semantic
`modifiedJulianDay` and `millisecondsInDay` fields compare through the harness
synthetic numeric formatter. The current checked-in date-time skeleton fixture
has no unsupported ICU4J witness cases; future cases that depend on unavailable
reference data should report an explicit unsupported reason. Use
`datetime-skeletons` instead of `datetime-skeletons-strict` when intentionally
exploring such unsupported reference cases.

The benchmark compiles one ICU4J `MessageFormatter` per fixture format case
before warmup. Timed iterations measure repeated `formatToString` calls only.
Unsupported fixtures are reported and skipped rather than silently hidden.
