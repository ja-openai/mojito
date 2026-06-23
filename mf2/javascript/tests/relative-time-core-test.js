import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { FunctionRegistry, formatMessage, parseToModel } from "@mojito-mf2/core";
import {
  RelativeTimeCoreError,
  createRelativeTimeCoreFunctionRegistry,
  formatRelativeTimeCore,
  formatRelativeTimeCoreToParts,
} from "@mojito-mf2/core/relative-time-core";

const data = readJson("../../cldr/generated/relative-time/all/relative_time.json");
const fixture = readJson("../../conformance/fixtures/functions/relative-time-duration-v0.json");
const registry = createRelativeTimeCoreFunctionRegistry(FunctionRegistry, data);

for (const testCase of fixture.cases) {
  const parsed = parseToModel(testCase.source);
  assert.equal(parsed.diagnostics.length, 0, testCase.label);
  const result = formatMessage(parsed.model, testCase.arguments, {
    locale: testCase.locale,
    functions: registry,
  });
  assert.equal(result.value, testCase.expected, testCase.label);
  assert.deepEqual(result.errors, [], testCase.label);
}

for (const testCase of fixture.errorCases) {
  const parsed = parseToModel(testCase.source);
  assert.equal(parsed.diagnostics.length, 0, testCase.label);
  const result = formatMessage(parsed.model, testCase.arguments, {
    locale: testCase.locale,
    functions: registry,
  });
  assert.deepEqual(
    result.errors.map((error) => error.code),
    [testCase.expectedError.code],
    testCase.label,
  );
}

assert.equal(
  formatRelativeTimeCore(3_600, {
    locale: "en",
    style: "narrow",
    numeric: "always",
    policy: "precise",
    unit: "auto",
    data,
  }),
  "in 1h",
);
assert.equal(
  formatRelativeTimeCore(-0, {
    locale: "en",
    style: "long",
    numeric: "always",
    policy: "precise",
    unit: "second",
    data,
  }),
  new Intl.RelativeTimeFormat("en", { style: "long", numeric: "always" }).format(-0, "second"),
);
assert.equal(
  formatRelativeTimeCore(172_800, {
    locale: "fr",
    style: "long",
    numeric: "auto",
    policy: "precise",
    unit: "day",
    data,
  }),
  new Intl.RelativeTimeFormat("fr", { style: "long", numeric: "auto" }).format(2, "day"),
);
assert.deepEqual(
  formatRelativeTimeCoreToParts(-86_400, {
    locale: "en",
    style: "long",
    numeric: "auto",
    unit: "day",
    data,
  }),
  [{ type: "text", value: "yesterday" }],
);
assert.throws(
  () =>
    formatRelativeTimeCore("1e30", {
      locale: "en",
      style: "narrow",
      numeric: "always",
      policy: "precise",
      unit: "auto",
      data,
    }),
  { code: "bad-operand" },
);
const throwingRelativeTimeOption = {
  toString() {
    throw new Error("relative-time option coercion failed");
  },
};
assert.throws(
  () =>
    formatRelativeTimeCore(throwingRelativeTimeOption, {
      locale: "en",
      data,
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "bad-operand",
  "wraps non-coercible direct relative-time operands",
);
assert.throws(
  () =>
    formatRelativeTimeCore(1, {
      locale: throwingRelativeTimeOption,
      data,
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "bad-option",
  "wraps non-coercible direct relative-time locale options",
);
assert.throws(
  () =>
    formatRelativeTimeCore(1, {
      data: { localeMap: { en: "missing" }, patternSets: [] },
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "missing-locale-data",
  "rejects empty relative-time pattern data during direct formatting",
);
assert.throws(
  () =>
    createRelativeTimeCoreFunctionRegistry(FunctionRegistry, {
      localeMap: { en: "missing" },
      patternSets: [],
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "missing-locale-data",
  "rejects empty relative-time pattern data during registry creation",
);
assert.throws(
  () =>
    formatRelativeTimeCore(1, {
      data: {
        localeMap: {},
        patternSets: [
          {
            id: "rt",
            data: { short: { second: { future: { other: "in {0} sec." } } } },
          },
        ],
      },
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "missing-locale-data",
  "rejects empty relative-time locale map during direct formatting",
);
assert.throws(
  () =>
    createRelativeTimeCoreFunctionRegistry(FunctionRegistry, {
      localeMap: { en: "rt" },
      patternSets: [{ id: "rt", data: {} }],
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "missing-locale-data",
  "rejects empty relative-time pattern-set data during registry creation",
);
assert.throws(
  () =>
    createRelativeTimeCoreFunctionRegistry(FunctionRegistry, {
      localeMap: { en: "rt" },
      patternSets: [
        {
          id: "",
          data: { short: { second: { future: { other: "in {0} sec." } } } },
        },
      ],
    }),
  (error) => error instanceof RelativeTimeCoreError && error.code === "missing-locale-data",
  "rejects empty relative-time pattern-set ids during registry creation",
);

function readJson(path) {
  return JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));
}

console.log(
  `Relative-time core test passed ${fixture.cases.length} format cases and ${fixture.errorCases.length} error cases.`,
);
