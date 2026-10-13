import assert from "node:assert/strict";
import { test } from "node:test";
import { FunctionRegistry, formatMessage, parseToModel } from "../src/index.js";
import { createIntlFunctionRegistry } from "../src/intl_functions.js";

function render(source, args, functions, locale = "en") {
  const parsed = parseToModel(source);
  assert.deepEqual(parsed.diagnostics, []);
  return formatMessage(parsed.model, args, { locale, functions });
}

test("registry cache reuses option configurations, evicts least-recently-used entries, and isolates registries", (t) => {
  const NativeNumberFormat = Intl.NumberFormat;
  let constructed = 0;
  t.mock.method(Intl, "NumberFormat", function (...args) {
    constructed++;
    return new NativeNumberFormat(...args);
  });
  const registry = createIntlFunctionRegistry(FunctionRegistry, { formatterCacheSize: 2 });
  const source = "{$amount :number maximumFractionDigits=$digits}";
  const format = (digits, locale = "en") => {
    const result = render(source, { amount: 1234.567, digits }, registry, locale);
    assert.deepEqual(result.errors, []);
    return result.value;
  };
  assert.equal(format(0), "1,235");
  assert.equal(format(1), "1,234.6");
  assert.equal(constructed, 2);
  format(0); // Refresh the older entry before the next insertion.
  format(2);
  assert.equal(constructed, 3);
  format(1); // The previous digits=1 entry was evicted.
  assert.equal(constructed, 4);
  format(1, "fr");
  assert.equal(constructed, 5);
  const separate = createIntlFunctionRegistry(FunctionRegistry, { formatterCacheSize: 2 });
  render(source, { amount: 3, digits: 1 }, separate, "fr");
  assert.equal(constructed, 6);
});

test("cache is optional and never caches failed constructors", (t) => {
  const NativeNumberFormat = Intl.NumberFormat;
  let constructed = 0;
  t.mock.method(Intl, "NumberFormat", function (...args) {
    constructed++;
    return new NativeNumberFormat(...args);
  });
  const disabled = createIntlFunctionRegistry(FunctionRegistry, { formatterCacheSize: 0 });
  for (let index = 0; index < 3; index++) render("{$n :number}", { n: index }, disabled);
  assert.equal(constructed, 3);
  const enabled = createIntlFunctionRegistry(FunctionRegistry);
  for (let index = 0; index < 2; index++) {
    const result = render("{$n :number minimumFractionDigits=5 maximumFractionDigits=1}", { n: 1 }, enabled);
    assert.deepEqual(result.errors.map(({ code }) => code), ["bad-option"]);
  }
  assert.equal(constructed, 5);
  for (const formatterCacheSize of [-1, 0.5, 1025, Infinity, NaN, "64"]) {
    assert.throws(() => createIntlFunctionRegistry(FunctionRegistry, { formatterCacheSize }), RangeError);
  }
});

test("cached and uncached number, relative time, and temporal formatting agree across values and zones", () => {
  const cached = createIntlFunctionRegistry(FunctionRegistry, { formatterCacheSize: 3 });
  const uncached = createIntlFunctionRegistry(FunctionRegistry, { formatterCacheSize: 0 });
  const cases = [
    ["{$value :number maximumFractionDigits=$digits}", { value: 12.345, digits: 1 }],
    ["{$value :number maximumFractionDigits=$digits}", { value: -987.65, digits: 2 }],
    ["{$value :percent}", { value: 0.42 }],
    ["{$value :currency currency=$currency}", { value: 19.5, currency: "EUR" }],
    ["{$value :relativeTime unit=day numeric=auto}", { value: -1 }],
    ["{$value :relativeTime unit=day numeric=auto}", { value: 2 }],
    ["{$value :datetime timeZone=$zone}", { value: "2024-03-10T02:30:00", zone: "America/New_York" }],
    ["{$value :datetime timeZone=$zone}", { value: "2024-11-03T01:30:00", zone: "America/New_York" }],
    ["{$value :date timeZone=$zone}", { value: "2024-06-01", zone: "Asia/Tokyo" }],
    ["{$value :time timeZone=$zone}", { value: new Date("2024-06-01T00:00:00Z"), zone: "UTC" }],
  ];
  for (let pass = 0; pass < 2; pass++) {
    for (const locale of ["en", "fr", "ar"]) {
      for (const [source, args] of cases) {
        const actual = render(source, args, cached, locale);
        const expected = render(source, args, uncached, locale);
        assert.deepEqual(actual.errors, []);
        assert.deepEqual(expected.errors, []);
        assert.equal(actual.value, expected.value, `${locale}: ${source}`);
      }
    }
  }
});
