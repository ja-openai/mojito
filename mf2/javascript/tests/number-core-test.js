import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import { FunctionRegistry, formatMessage, parseToModel } from "@mojito-mf2/core";
import { NumberCoreError, createNumberCoreFunctionRegistry, formatNumberCore } from "@mojito-mf2/core/number-core";

const fixture = JSON.parse(
  await readFile(new URL("../../conformance/fixtures/number-core/cases.json", import.meta.url), "utf8"),
);

for (const item of fixture.formatCases) {
  assert.equal(
    formatNumberCore(item.value, { locale: item.locale, ...item.options }),
    item.expected,
    item.name,
  );
}

for (const item of fixture.intlReferenceCases) {
  const actual = formatNumberCore(item.value, { locale: item.locale, ...item.options });
  const expected = new Intl.NumberFormat(item.locale, intlOptions(item.options)).format(item.value);
  assert.equal(actual, expected, `Intl reference ${item.locale} ${JSON.stringify(item.options)}`);
}

const decimalRoundingReferenceCases = [
  {
    locale: "en-US",
    value: "0.015",
    options: { style: "currency", currency: "USD" },
  },
  {
    locale: "en-US",
    value: "1.005",
    options: { style: "number", minimumFractionDigits: 2, maximumFractionDigits: 2 },
  },
];
for (const item of decimalRoundingReferenceCases) {
  const actual = formatNumberCore(item.value, { locale: item.locale, ...item.options });
  const expected = new Intl.NumberFormat(item.locale, intlOptions(item.options)).format(item.value);
  assert.equal(actual, expected, `Intl decimal rounding ${item.locale} ${JSON.stringify(item.options)}`);
}

const signedZeroReferenceCases = [
  { locale: "en-US", value: -0, options: { style: "number" } },
  { locale: "en-US", value: -0, options: { style: "percent" } },
  { locale: "en-US", value: -0, options: { style: "currency", currency: "USD" } },
  { locale: "en-US", value: 0, options: { style: "number", signDisplay: "always" } },
  { locale: "en-US", value: 0, options: { style: "currency", currency: "USD", signDisplay: "always" } },
];
for (const item of signedZeroReferenceCases) {
  const actual = formatNumberCore(item.value, { locale: item.locale, ...item.options });
  const expected = new Intl.NumberFormat(item.locale, intlOptions(item.options)).format(item.value);
  assert.equal(actual, expected, `Intl signed zero ${item.locale} ${JSON.stringify(item.options)}`);
}

const currencyCodeReferenceCases = [
  { locale: "en-US", value: 1234.56, options: { style: "currency", currency: "USD", currencyDisplay: "code" } },
  { locale: "ja-JP", value: 1234.56, options: { style: "currency", currency: "USD", currencyDisplay: "code" } },
  { locale: "fr-FR", value: 1234.56, options: { style: "currency", currency: "USD", currencyDisplay: "code" } },
  { locale: "ar-EG", value: 1234.56, options: { style: "currency", currency: "USD", currencyDisplay: "code" } },
];
for (const item of currencyCodeReferenceCases) {
  const actual = formatNumberCore(item.value, { locale: item.locale, ...item.options });
  const expected = new Intl.NumberFormat(item.locale, intlOptions(item.options)).format(item.value);
  assert.equal(actual, expected, `Intl currency code ${item.locale} ${JSON.stringify(item.options)}`);
}

for (const item of fixture.errorCases) {
  assert.throws(
    () => formatNumberCore(item.value, { locale: item.locale, ...item.options }),
    (error) => error instanceof NumberCoreError && error.code === item.expectedError,
    item.name,
  );
}

const numberCoreRegistry = FunctionRegistry.portable().withRegistry(createNumberCoreFunctionRegistry(FunctionRegistry));
for (const item of fixture.registryCases ?? []) {
  const parsed = parseToModel(item.source);
  assert.equal(parsed.hasDiagnostics, false, `${item.name}: parse diagnostics ${JSON.stringify(parsed.diagnostics)}`);
  const actual = formatMessage(parsed.model, item.arguments ?? {}, {
    locale: item.locale,
    functions: numberCoreRegistry,
    bidiIsolation: "none",
  });
  assert.equal(actual.value, item.expected, item.name);
  assert.deepEqual(actual.errors.map((error) => error.code), [], item.name);
}

for (const item of fixture.registryErrorCases ?? []) {
  const parsed = parseToModel(item.source);
  assert.equal(parsed.hasDiagnostics, false, `${item.name}: parse diagnostics ${JSON.stringify(parsed.diagnostics)}`);
  const actual = formatMessage(parsed.model, item.arguments ?? {}, {
    locale: item.locale,
    functions: numberCoreRegistry,
    bidiIsolation: "none",
  });
  assert.deepEqual(actual.errors.map((error) => error.code), item.expectedErrors, item.name);
}

assert.throws(
  () => formatNumberCore(10n ** 1000n, { locale: "en-US", style: "number" }),
  (error) => error instanceof NumberCoreError && error.code === "bad-operand",
  "oversized BigInt operand",
);
const throwingNumberOption = {
  toString() {
    throw new Error("number option coercion failed");
  },
};
assert.throws(
  () => formatNumberCore(throwingNumberOption, { locale: "en-US" }),
  (error) => error instanceof NumberCoreError && error.code === "bad-operand",
  "wraps non-coercible direct number operands",
);
assert.throws(
  () => formatNumberCore(1, { locale: throwingNumberOption }),
  (error) => error instanceof NumberCoreError && error.code === "bad-option",
  "wraps non-coercible direct number locale options",
);
assert.throws(
  () => formatNumberCore(1, { locale: "en-US", minimumFractionDigits: throwingNumberOption }),
  (error) => error instanceof NumberCoreError && error.code === "bad-option",
  "wraps non-coercible direct number minimumFractionDigits options",
);
assert.throws(
  () => formatNumberCore(1, { locale: "en-US", maximumFractionDigits: throwingNumberOption }),
  (error) => error instanceof NumberCoreError && error.code === "bad-option",
  "wraps non-coercible direct number maximumFractionDigits options",
);

const primitiveBigIntModel = parseToModel("{$n}").model;
assert.equal(
  formatMessage(primitiveBigIntModel, { n: 1234n }, { locale: "fr-FR" }).value,
  new Intl.NumberFormat("fr-FR").format(1234n),
  "primitive BigInt uses localized number-core rendering",
);
const unsafePrimitiveBigInt = formatMessage(primitiveBigIntModel, { n: 9007199254740992n }, { locale: "en-US" });
assert.equal(unsafePrimitiveBigInt.value, "{$n}", "unsafe primitive BigInt falls back");
assert.deepEqual(unsafePrimitiveBigInt.errors.map((error) => error.code), ["bad-operand"]);

function intlOptions(options) {
  if (options.style === "integer") throw new Error("Unsupported Intl reference style: integer");
  const output = { ...options };
  if (output.style === "number") delete output.style;
  if (output.style === "currency" && output.currency == null) throw new Error("Currency reference requires currency");
  if (output.style === "percent" || output.style === "currency" || output.style == null) return output;
  throw new Error(`Unsupported Intl reference style: ${options.style}`);
}

console.log(
  `Number core test passed ${fixture.formatCases.length} format cases, ` +
    `${fixture.intlReferenceCases.length} Intl reference cases, ` +
    `${decimalRoundingReferenceCases.length} Intl decimal rounding cases, ` +
    `${signedZeroReferenceCases.length} Intl signed-zero cases, ` +
    `${currencyCodeReferenceCases.length} Intl currency-code cases, ` +
    `${fixture.errorCases.length} error cases, ` +
    `${fixture.registryCases?.length ?? 0} registry cases, ` +
    `${fixture.registryErrorCases?.length ?? 0} registry error cases, and primitive BigInt checks.`,
);
