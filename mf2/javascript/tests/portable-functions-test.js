import assert from "node:assert/strict";

import { formatMessage, parseToModel } from "@mojito-mf2/core";
import { NumberOperands } from "../src/cldr_plural_rules.js";

const offsetModel = parseToModel("Value: {$n :offset add=0}").model;

const exactBigInt = formatMessage(offsetModel, { n: 9007199254740993n }, { locale: "en", bidiIsolation: "none" });
assert.equal(exactBigInt.value, "Value: 9007199254740993", "BigInt offset operands remain exact");
assert.deepEqual(exactBigInt.errors.map((error) => error.code), []);

const unsafeNumber = formatMessage(offsetModel, { n: Number.MAX_SAFE_INTEGER + 2 }, { locale: "en", bidiIsolation: "none" });
assert.equal(unsafeNumber.value, "Value: {$n}", "unsafe JS number offset operands fall back");
assert.deepEqual(unsafeNumber.errors.map((error) => error.code), ["bad-operand"]);

assert.throws(
  () => new NumberOperands("1".repeat(257)),
  (error) => error instanceof RangeError && error.message === "Unsupported plural operand value",
  "oversized plural operands fail without echoing the operand",
);

const largePortableNumber = parseToModel(
  "Values: {9007199254740993 :number}; {999999999999999999999 :number}; {100000000000000000001 :integer}; {9007199254740993 :percent}",
).model;
const largePortableResult = formatMessage(largePortableNumber, {}, { locale: "en", bidiIsolation: "none" });
assert.equal(
  largePortableResult.value,
  "Values: 9007199254740993; 999999999999999999999; 100000000000000000001; 900719925474099300%",
  "portable numeric formatters preserve source literal precision",
);
assert.deepEqual(largePortableResult.errors.map((error) => error.code), []);

const fractionBoundModel = parseToModel("Values: {1.234 :number maximumFractionDigits=2}; {1 :number maximumFractionDigits=10000}; {1.234 :percent minimumFractionDigits=2 maximumFractionDigits=1}").model;
const fractionBoundResult = formatMessage(fractionBoundModel, {}, { locale: "en", bidiIsolation: "none" });
assert.equal(
  fractionBoundResult.value,
  "Values: 1.23; {|1|}; {|1.234|}",
  "portable numeric formatters honor maximumFractionDigits and reject inconsistent bounds",
);
assert.deepEqual(fractionBoundResult.errors.map((error) => error.code), ["bad-option", "bad-option"]);

const halfExpandModel = parseToModel(
  "Values: {1.005 :number maximumFractionDigits=2}; {2.225 :number maximumFractionDigits=2}; {-1.005 :number maximumFractionDigits=2}; {0.005 :percent maximumFractionDigits=0}",
).model;
const halfExpandResult = formatMessage(halfExpandModel, {}, { locale: "en", bidiIsolation: "none" });
assert.equal(
  halfExpandResult.value,
  "Values: 1.01; 2.23; -1.01; 1%",
  "portable maximumFractionDigits uses ICU/Intl-style half-expand rounding",
);
assert.deepEqual(halfExpandResult.errors.map((error) => error.code), []);

console.log("Portable functions test passed offset, large decimal precision, fraction bound, and half-expand rounding checks.");
