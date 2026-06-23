import { readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";

import { DATE_TIME_DATA } from "../src/cldr_date_time_data.js";
import { NUMBER_DATA } from "../src/cldr_number_data.js";
import { formatDateCore, formatDateTimeCore, formatTimeCore } from "../src/date_time_core.js";
import { formatNumberCore } from "../src/number_core.js";

const iterations = Number(process.argv[2] ?? 100_000);
const warmupIterations = Number(process.argv[3] ?? 10_000);

const numberBytes = readFileSync(new URL("../../cldr/generated/experimental-number/number_data.json", import.meta.url));
const dateTimeBytes = readFileSync(new URL("../../cldr/generated/experimental-datetime/date_time_data.json", import.meta.url));
const numberText = numberBytes.toString("utf8");
const dateTimeText = dateTimeBytes.toString("utf8");
const numberCoreFixture = JSON.parse(
  readFileSync(new URL("../../conformance/fixtures/number-core/cases.json", import.meta.url), "utf8"),
);
const dateTimeCoreFixture = JSON.parse(
  readFileSync(new URL("../../conformance/fixtures/date-time-core/cases.json", import.meta.url), "utf8"),
);
const dateTimeData = DATE_TIME_DATA;
const numberData = NUMBER_DATA;
const locales = Object.keys(numberData.locales);
const dateTimeLocales = Object.keys(dateTimeData.locales);
const decimalFormatters = new Map();
const percentFormatters = new Map();
const numberCoreReferenceFormatters = new Map();
const dateTimeCoreReferenceFormatters = new Map();
const monthFormatters = new Map();
const weekdayFormatters = new Map();
const hourFormatters = new Map();
const DATES = {
  january: new Date(Date.UTC(2020, 0, 1, 0, 0, 0)),
  sunday: new Date(Date.UTC(2020, 0, 5, 0, 0, 0)),
  am: new Date(Date.UTC(2020, 0, 1, 1, 0, 0)),
  pm: new Date(Date.UTC(2020, 0, 1, 13, 0, 0)),
};

console.log(
  `number-data-size raw=${numberBytes.length} gzip=${gzipSync(numberBytes, { level: 9 }).length} locales=${locales.length}`,
);
console.log(
  `datetime-data-size raw=${dateTimeBytes.length} gzip=${gzipSync(dateTimeBytes, { level: 9 }).length} locales=${Object.keys(dateTimeData.locales).length}`,
);

runBench("number-json-parse", () => {
  const parsed = JSON.parse(numberText);
  return Object.keys(parsed.locales).length;
});
runBench("datetime-json-parse", () => {
  const parsed = JSON.parse(dateTimeText);
  return Object.keys(parsed.locales).length;
});
runBench("number-data-symbol-lookup", (index) => {
  const locale = locales[index % locales.length];
  const symbols = numberData.locales[locale].symbols;
  return symbols.decimal.length + symbols.group.length + symbols.percentSign.length;
});
runBench("intl-number-formatToParts", (index) => {
  const locale = locales[index % locales.length];
  return (
    decimalFormatter(locale).formatToParts(1234.5).length +
    percentFormatter(locale).formatToParts(0.12).length
  );
});
runBench("number-core-format", (index) => {
  const item = numberCoreFixture.formatCases[index % numberCoreFixture.formatCases.length];
  return formatNumberCore(item.value, { locale: item.locale, ...item.options }).length;
});
runBench("intl-number-format", (index) => {
  const item = numberCoreFixture.intlReferenceCases[index % numberCoreFixture.intlReferenceCases.length];
  return intlNumberFormatter(item).format(item.value).length;
});
runBench("datetime-data-name-lookup", (index) => {
  const locale = dateTimeLocales[index % dateTimeLocales.length];
  const data = dateTimeData.locales[locale];
  return (
    data.months.format.wide["1"].length +
    data.weekdays.format.wide.sun.length +
    data.dayPeriods.format.wide.am.length +
    data.dayPeriods.format.wide.pm.length
  );
});
runBench("datetime-core-format", (index) => {
  const item = dateTimeCoreFixture.formatCases[index % dateTimeCoreFixture.formatCases.length];
  return formatDateTimeCoreItem(item).length;
});
runBench("intl-datetime-format", (index) => {
  const item = dateTimeCoreFixture.intlReferenceCases[index % dateTimeCoreFixture.intlReferenceCases.length];
  return intlDateTimeFormatter(item).format(new Date(item.value)).length;
});
runBench("intl-datetime-formatToParts", (index) => {
  const locale = dateTimeLocales[index % dateTimeLocales.length];
  return (
    monthFormatter(locale).formatToParts(DATES.january).length +
    weekdayFormatter(locale).formatToParts(DATES.sunday).length +
    hourFormatter(locale).formatToParts(DATES.am).length +
    hourFormatter(locale).formatToParts(DATES.pm).length
  );
});

function decimalFormatter(locale) {
  return cached(decimalFormatters, locale, () =>
    new Intl.NumberFormat(locale, {
      useGrouping: true,
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    }),
  );
}

function percentFormatter(locale) {
  return cached(percentFormatters, locale, () => new Intl.NumberFormat(locale, { style: "percent" }));
}

function monthFormatter(locale) {
  return cached(
    monthFormatters,
    locale,
    () => new Intl.DateTimeFormat(locale, { timeZone: "UTC", month: "long", day: "numeric" }),
  );
}

function weekdayFormatter(locale) {
  return cached(
    weekdayFormatters,
    locale,
    () => new Intl.DateTimeFormat(locale, { timeZone: "UTC", weekday: "long" }),
  );
}

function hourFormatter(locale) {
  return cached(
    hourFormatters,
    locale,
    () => new Intl.DateTimeFormat(locale, { timeZone: "UTC", hour: "numeric", hour12: true }),
  );
}

function intlNumberFormatter(item) {
  const key = JSON.stringify([item.locale, item.options]);
  return cached(numberCoreReferenceFormatters, key, () => new Intl.NumberFormat(item.locale, intlNumberOptions(item.options)));
}

function formatDateTimeCoreItem(item) {
  const options = { locale: item.locale, ...item.options };
  if (item.kind === "date") return formatDateCore(item.value, options);
  if (item.kind === "time") return formatTimeCore(item.value, options);
  if (item.kind === "datetime") return formatDateTimeCore(item.value, options);
  throw new Error(`Unsupported date/time core fixture kind: ${item.kind}`);
}

function intlDateTimeFormatter(item) {
  const key = JSON.stringify([item.locale, item.options]);
  return cached(dateTimeCoreReferenceFormatters, key, () =>
    new Intl.DateTimeFormat(item.locale, { timeZone: "UTC", ...item.options }),
  );
}

function intlNumberOptions(options) {
  if (options.style === "number") return {};
  if (options.style === "percent") return { style: "percent" };
  if (options.style === "currency") return { style: "currency", currency: options.currency };
  throw new Error(`Unsupported Intl reference style: ${options.style}`);
}

function cached(cache, locale, create) {
  let formatter = cache.get(locale);
  if (formatter == null) {
    formatter = create();
    cache.set(locale, formatter);
  }
  return formatter;
}

function runBench(label, fn) {
  for (let index = 0; index < warmupIterations; index += 1) {
    fn(index);
  }
  globalThis.gc?.();
  const memoryBefore = process.memoryUsage();
  const cpuBefore = process.cpuUsage();
  const timeBefore = process.hrtime.bigint();
  let checksum = 0;
  for (let index = 0; index < iterations; index += 1) {
    checksum += fn(index);
  }
  const elapsedNs = Number(process.hrtime.bigint() - timeBefore);
  const cpu = process.cpuUsage(cpuBefore);
  globalThis.gc?.();
  const memoryAfter = process.memoryUsage();
  console.log(
    `${label}: iterations=${iterations} warmup=${warmupIterations} ns/op=${(elapsedNs / iterations).toFixed(1)} ` +
      `cpu_ms=${((cpu.user + cpu.system) / 1000).toFixed(1)} ` +
      `rss_delta_kb=${Math.round((memoryAfter.rss - memoryBefore.rss) / 1024)} ` +
      `heap_delta_kb=${Math.round((memoryAfter.heapUsed - memoryBefore.heapUsed) / 1024)} checksum=${checksum}`,
  );
}
