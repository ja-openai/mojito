import assert from "node:assert/strict";

import * as core from "@mojito-mf2/core";
import {
  FunctionRegistry,
  MF2Error,
  formatMessage,
  formatMessageToParts as formatMessageToPartsFromRoot,
  parseToModel,
} from "@mojito-mf2/core";

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
assert.equal(FunctionRegistry.defaults().hasFormatter({ name: "string" }), true);
assert.equal(FunctionRegistry.portable().hasFormatter({ name: "number" }), true);
const currency = parseToModel("Total: {$amount :currency currency=USD}");
const formattedCurrency = formatMessage(currency.model, { amount: 42 });
assert.equal(formattedCurrency.value, "Total: {$amount}");
assert.deepEqual(formattedCurrency.errors.map((error) => error.code), ["unknown-function"]);
assert.equal(new MF2Error("test", "test").code, "test");
assert.equal("partsToString" in core, false);
assert.equal("formatMessageStrict" in core, false);
assert.equal("formatMessageToPartsStrict" in core, false);
assert.equal("selectPluralCategory" in core, false);
assert.equal("valueToString" in core, false);
assert.equal("canonicalLocaleKey" in core, false);
assert.equal("selectCardinal" in core, false);
assert.equal("localeLookupChain" in core, false);

console.log("MF2 JavaScript package boundary test passed");
