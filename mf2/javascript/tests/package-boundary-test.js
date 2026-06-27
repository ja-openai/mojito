import assert from "node:assert/strict";

import * as core from "@mojito-mf2/core";
import {
  FunctionRegistry,
  MF2Error,
  formatMessage,
  formatMessageToParts as formatMessageToPartsFromRoot,
  parseToModel,
} from "@mojito-mf2/core";
import { formatMessage as formatMessageFromFormatter } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
import { decodeDateTimeDataResource, decodeNumberDataResource } from "@mojito-mf2/core/cldr-packed";
import {
  createDateTimeCoreFunctionRegistry,
  formatDateCore,
  formatDateCoreToParts,
  formatDateTimeCore,
  formatDateTimeCoreToParts,
  formatTimeCore,
  formatTimeCoreToParts,
} from "@mojito-mf2/core/date-time-core";
import { createNumberCoreFunctionRegistry, formatNumberCore, formatNumberCoreToParts } from "@mojito-mf2/core/number-core";
import { parseToModel as parseToModelFromParser } from "@mojito-mf2/core/parser";
import { createPortableFunctionRegistry } from "@mojito-mf2/core/portable";
import { formatRelativeTimeCore, formatRelativeTimeCoreToParts } from "@mojito-mf2/core/relative-time-core";

const parsed = parseToModel("Hello {$name}");
assert.equal(parsed.diagnostics.length, 0);
assert.equal(formatMessage(parsed.model, { name: "Mojito" }).value, "Hello Mojito");
assert.equal(formatMessage(parsed.model, { name: "Safe" }).value, "Hello Safe");
assert.deepEqual(formatMessageToPartsFromRoot(parsed.model, { name: "Safe Parts" }).parts, [
  { type: "text", value: "Hello " },
  { type: "expression", value: "Safe Parts" },
]);
assert.deepEqual(formatMessageToPartsFromRoot(parsed.model, { name: "Root Safe Parts" }).parts, [
  { type: "text", value: "Hello " },
  { type: "expression", value: "Root Safe Parts" },
]);
const emptyMissing = formatMessage(parsed.model, {}, { onMissingArgument: () => "" });
assert.equal(emptyMissing.value, "Hello ");
assert.deepEqual(emptyMissing.errors.map((error) => error.code), ["unresolved-variable"]);
assert.deepEqual(formatMessageToPartsFromRoot(parsed.model, {}, { onMissingArgument: () => "" }).parts, [
  { type: "text", value: "Hello " },
  { type: "fallback", source: "$name", value: "" },
]);
const declinedMissing = formatMessage(parsed.model, {}, { onMissingArgument: () => null });
assert.equal(declinedMissing.value, "Hello {$name}");
assert.deepEqual(formatMessageToPartsFromRoot(parsed.model, {}, { onMissingArgument: () => null }).parts, [
  { type: "text", value: "Hello " },
  { type: "fallback", source: "$name" },
]);
const badInteger = parseToModel("Hello {$name :integer}");
const emptyFormatError = formatMessage(badInteger.model, { name: "abc" }, { onFormatError: () => "" });
assert.equal(emptyFormatError.value, "Hello ");
assert.deepEqual(emptyFormatError.errors.map((error) => error.code), ["bad-operand"]);
assert.deepEqual(formatMessageToPartsFromRoot(badInteger.model, { name: "abc" }, { onFormatError: () => "" }).parts, [
  { type: "text", value: "Hello " },
  { type: "fallback", source: "$name", value: "" },
]);
const nativeErrorMessage = parseToModel("Hello {$name :nativeError}");
const nativeErrorRegistry = FunctionRegistry.portable().withFunction("nativeError", () => {
  throw new Error("custom formatter failed");
});
const nativeErrorResult = formatMessage(nativeErrorMessage.model, { name: "Mojito" }, { functions: nativeErrorRegistry });
assert.deepEqual(nativeErrorResult.errors.map((error) => error.code), ["error"]);
assert.equal(nativeErrorResult.errors[0] instanceof MF2Error, true);
const hostileValue = {
  toString() {
    throw new Error("value rendering failed");
  },
};
const hostilePlaceholder = parseToModel("Hello {$name}");
const hostilePlaceholderResult = formatMessage(hostilePlaceholder.model, { name: hostileValue });
assert.equal(hostilePlaceholderResult.value, "Hello {$name}");
assert.deepEqual(hostilePlaceholderResult.errors.map((error) => error.code), ["bad-operand"]);
assert.equal(hostilePlaceholderResult.errors[0] instanceof MF2Error, true);
const hostileString = parseToModel("Hello {$name :string}");
const hostileStringResult = formatMessage(hostileString.model, { name: hostileValue });
assert.equal(hostileStringResult.value, "Hello {$name}");
assert.deepEqual(hostileStringResult.errors.map((error) => error.code), ["bad-operand"]);
const hostileSelector = parseToModel(".input {$name :string}\n.match $name\nok {{selected}}\n* {{fallback}}");
const hostileSelectorResult = formatMessage(hostileSelector.model, { name: hostileValue });
assert.equal(hostileSelectorResult.value, "fallback");
assert.deepEqual(hostileSelectorResult.errors.map((error) => error.code), ["bad-operand"]);
assert.equal(FunctionRegistry.defaults().hasFormatter({ name: "string" }), true);
assert.equal(FunctionRegistry.portable().hasFormatter({ name: "number" }), true);
assert.equal(createPortableFunctionRegistry(FunctionRegistry).hasFormatter({ name: "number" }), true);
assert.equal(formatNumberCore(1234.5, { locale: "fr-FR" }), "1 234,5");
assert.equal(formatDateTimeCore("2026-05-21T14:30:15Z", { locale: "fr-FR", dateStyle: "short", timeStyle: "short" }), "21/05/2026 14:30");
assert.deepEqual(formatNumberCoreToParts(1234.5, { locale: "fr-FR" }), [
  { type: "text", value: formatNumberCore(1234.5, { locale: "fr-FR" }) },
]);
assert.deepEqual(formatDateCoreToParts("2026-05-21T14:30:15Z", { locale: "fr-FR", dateStyle: "short" }), [
  { type: "text", value: formatDateCore("2026-05-21T14:30:15Z", { locale: "fr-FR", dateStyle: "short" }) },
]);
assert.deepEqual(formatTimeCoreToParts("2026-05-21T14:30:15Z", { locale: "fr-FR", timeStyle: "short" }), [
  { type: "text", value: formatTimeCore("2026-05-21T14:30:15Z", { locale: "fr-FR", timeStyle: "short" }) },
]);
assert.deepEqual(
  formatDateTimeCoreToParts("2026-05-21T14:30:15Z", { locale: "fr-FR", dateStyle: "short", timeStyle: "short" }),
  [
    {
      type: "text",
      value: formatDateTimeCore("2026-05-21T14:30:15Z", { locale: "fr-FR", dateStyle: "short", timeStyle: "short" }),
    },
  ],
);
assert.equal(createNumberCoreFunctionRegistry(FunctionRegistry).hasFormatter({ name: "string" }), true);
assert.equal(typeof decodeNumberDataResource, "function");
assert.equal(typeof decodeDateTimeDataResource, "function");
assert.equal(typeof formatRelativeTimeCore, "function");
assert.equal(typeof formatRelativeTimeCoreToParts, "function");
assert.equal(createDateTimeCoreFunctionRegistry(FunctionRegistry).hasFormatter({ name: "datetime" }), true);
const currency = parseToModel("Total: {$amount :currency currency=USD}");
const formattedCurrency = formatMessage(currency.model, { amount: 42 });
assert.equal(formattedCurrency.value, "Total: {$amount}");
assert.deepEqual(formattedCurrency.errors.map((error) => error.code), ["unknown-function"]);
const intlRegistry = createIntlFunctionRegistry(FunctionRegistry);
assert.equal(intlRegistry.hasFormatter({ name: "currency" }), true);
const inheritedIntlCurrency = parseToModelFromParser(".local $price = {$amount :currency currency=$currency}\n{{{$price :currency}}}");
const inheritedIntlCurrencyResult = formatMessageFromFormatter(
  inheritedIntlCurrency.model,
  { amount: 12.3, currency: "EUR" },
  { locale: "en-US", functions: intlRegistry, bidiIsolation: "none" },
);
assert.equal(
  inheritedIntlCurrencyResult.value,
  new Intl.NumberFormat("en-US", { style: "currency", currency: "EUR" }).format(12.3),
);
assert.deepEqual(inheritedIntlCurrencyResult.errors.map((error) => error.code), []);
const invalidCurrentIntlCurrency = parseToModelFromParser(".local $price = {$amount :currency currency=USD}\n{{{$price :currency currency=||}}}");
const invalidCurrentIntlCurrencyResult = formatMessageFromFormatter(
  invalidCurrentIntlCurrency.model,
  { amount: 12.3 },
  { locale: "en-US", functions: intlRegistry, bidiIsolation: "none" },
);
assert.deepEqual(invalidCurrentIntlCurrencyResult.errors.map((error) => error.code), ["bad-option"]);
const relative = parseToModelFromParser("Due {$delta :relativeTime unit=day}");
for (const locale of ["en", "fr", "ja", "ar"]) {
  assert.equal(
    formatMessageFromFormatter(relative.model, { delta: -1 }, { locale, functions: intlRegistry }).value,
    `Due ${new Intl.RelativeTimeFormat(locale, { numeric: "always", style: "long" }).format(-1, "day")}`,
  );
}
const intlDate = parseToModelFromParser("At {$instant :datetime dateStyle=full timeStyle=short timeZone=UTC}");
assert.equal(
  formatMessageFromFormatter(intlDate.model, { instant: "2026-05-21T14:30:15Z" }, { locale: "ja-JP", functions: intlRegistry }).value,
  `At ${new Intl.DateTimeFormat("ja-JP", { dateStyle: "full", timeStyle: "short", timeZone: "UTC" }).format(new Date("2026-05-21T14:30:15Z"))}`,
);
const intlLegacyDate = parseToModelFromParser("At {$instant :datetime dateLength=full timePrecision=short timeZone=UTC}");
assert.equal(
  formatMessageFromFormatter(intlLegacyDate.model, { instant: "2026-05-21T14:30:15Z" }, { locale: "fr-FR", functions: intlRegistry }).value,
  `At ${new Intl.DateTimeFormat("fr-FR", { dateStyle: "full", timeStyle: "short", timeZone: "UTC" }).format(new Date("2026-05-21T14:30:15Z"))}`,
);
const inheritedIntlDate = parseToModelFromParser(".local $date = {$instant :date dateStyle=full timeZone=UTC}\n{{{$date :date dateStyle=short timeZone=UTC}}}");
const inheritedIntlDateResult = formatMessageFromFormatter(
  inheritedIntlDate.model,
  { instant: "2026-05-21T14:30:15Z" },
  { locale: "fr-FR", functions: intlRegistry, bidiIsolation: "none" },
);
assert.equal(
  inheritedIntlDateResult.value,
  new Intl.DateTimeFormat("fr-FR", { dateStyle: "short", timeZone: "UTC" }).format(new Date("2026-05-21T14:30:15Z")),
);
assert.deepEqual(inheritedIntlDateResult.errors.map((error) => error.code), []);
function assertIntlDateBadOperand(label, source, instant) {
  const parsed = parseToModelFromParser(source);
  const result = formatMessageFromFormatter(parsed.model, { instant }, { locale: "en-US", functions: intlRegistry, bidiIsolation: "none" });
  assert.deepEqual(result.errors.map((error) => error.code), ["bad-operand"], label);
}
assertIntlDateBadOperand(
  "Intl adapter rejects unpadded date strings",
  "At {$instant :date dateStyle=medium timeZone=UTC}",
  "2020-1-2",
);
assertIntlDateBadOperand(
  "Intl adapter rejects impossible dates",
  "At {$instant :date dateStyle=medium timeZone=UTC}",
  "2020-02-30",
);
assertIntlDateBadOperand(
  "Intl adapter rejects impossible datetime dates",
  "At {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
  "2020-02-30T03:04:05Z",
);
assertIntlDateBadOperand(
  "Intl adapter rejects out-of-range datetime offsets",
  "At {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
  "2020-01-02T03:04:05+18:01",
);
assert.equal(new MF2Error("test", "test").code, "test");
assert.equal("partsToString" in core, false);
assert.equal("formatMessageStrict" in core, false);
assert.equal("formatMessageToPartsStrict" in core, false);
assert.equal("selectPluralCategory" in core, false);
assert.equal("valueToString" in core, false);
assert.equal("canonicalLocaleKey" in core, false);
assert.equal("selectCardinal" in core, false);
assert.equal("localeLookupChain" in core, false);
assert.equal("createIntlFunctionRegistry" in core, false);
assert.equal("formatNumberCore" in core, false);
assert.equal("formatNumberCoreToParts" in core, false);
assert.equal("formatDateCore" in core, false);
assert.equal("formatDateCoreToParts" in core, false);
assert.equal("formatTimeCore" in core, false);
assert.equal("formatTimeCoreToParts" in core, false);
assert.equal("formatDateTimeCore" in core, false);
assert.equal("formatDateTimeCoreToParts" in core, false);
assert.equal("formatRelativeTimeCore" in core, false);
assert.equal("formatRelativeTimeCoreToParts" in core, false);

console.log("MF2 JavaScript package boundary test passed");
