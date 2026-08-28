import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";

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
import { parseToModel as parseToModelFromParser } from "@mojito-mf2/core/parser";
import { createPortableFunctionRegistry } from "@mojito-mf2/core/portable";

const parsed = parseToModel("Hello {$name}");
assert.equal(parsed.diagnostics.length, 0);
assert.equal(formatMessage(parsed.model, { name: "Mojito" }).value, "Hello Mojito");
assert.equal(formatMessage(parsed.model, { name: "Safe" }).value, "Hello Safe");
const prototypeNamedOptions = parseToModel(
  "{1 :number __proto__=option @__proto__=attribute} " +
    "{#tag __proto__=markup-option @__proto__=markup-attribute /}",
);
assert.deepEqual(prototypeNamedOptions.diagnostics, []);
assert.deepEqual(prototypeNamedOptions.model.pattern[0].function.options.__proto__, {
  type: "literal",
  value: "option",
});
assert.deepEqual(prototypeNamedOptions.model.pattern[0].attributes.__proto__, {
  type: "literal",
  value: "attribute",
});
assert.deepEqual(prototypeNamedOptions.model.pattern[2].options.__proto__, {
  type: "literal",
  value: "markup-option",
});
assert.deepEqual(prototypeNamedOptions.model.pattern[2].attributes.__proto__, {
  type: "literal",
  value: "markup-attribute",
});
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
const badBidiDirection = parseToModel("Value {42 :number u:dir=sideways}");
const badBidiDirectionResult = formatMessage(badBidiDirection.model);
assert.equal(badBidiDirectionResult.value, "Value {|42|}");
assert.deepEqual(badBidiDirectionResult.errors.map((error) => error.code), ["bad-option"]);
assert.equal(FunctionRegistry.defaults().hasFormatter({ name: "string" }), true);
assert.equal(FunctionRegistry.portable().hasFormatter({ name: "number" }), true);
assert.equal(createPortableFunctionRegistry(FunctionRegistry).hasFormatter({ name: "number" }), true);
const currency = parseToModel("Total: {$amount :currency currency=USD}");
const formattedCurrency = formatMessage(currency.model, { amount: 42 });
assert.equal(formattedCurrency.value, "Total: {$amount}");
assert.deepEqual(formattedCurrency.errors.map((error) => error.code), ["unknown-function"]);
const intlRegistry = createIntlFunctionRegistry(FunctionRegistry);
assert.equal(intlRegistry.hasFormatter({ name: "currency" }), true);
const numericRegistries = [FunctionRegistry.portable(), intlRegistry];
const exactBeforeCategory = parseToModelFromParser(
  ".input {$count :integer}\n.match $count\none {{category}}\n1 {{exact}}\n* {{fallback}}",
);
assert.equal(
  formatMessageFromFormatter(exactBeforeCategory.model, { count: 1 }, { functions: intlRegistry }).value,
  "exact",
);
const canonicalExactNumber = parseToModelFromParser(
  ".input {$value :number}\n.match $value\n1.0 {{decimal spelling}}\n1 {{integer spelling}}\n* {{fallback}}",
);
assert.equal(
  formatMessageFromFormatter(canonicalExactNumber.model, { value: 1 }, { functions: intlRegistry }).value,
  "integer spelling",
);
const badNumericVariantKey = parseToModelFromParser(
  ".input {$value :number}\n.match $value\nhorse {{horse}}\n* {{fallback}}",
);
const badNumericVariantKeyResult = formatMessageFromFormatter(
  badNumericVariantKey.model,
  { value: 1 },
  { functions: intlRegistry },
);
assert.equal(badNumericVariantKeyResult.value, "fallback");
assert.deepEqual(badNumericVariantKeyResult.errors.map((error) => error.code), ["bad-variant-key"]);
const unsafeIntegerValues = [
  "9007199254740993",
  9007199254740993n,
  "18446744073709551615",
  18446744073709551615n,
  Number.MAX_SAFE_INTEGER + 1,
];
for (const functionName of ["number", "integer", "percent"]) {
  const unsafeInteger = parseToModelFromParser(`Value {$value :${functionName}}`);
  const unsafeExact = parseToModelFromParser(
    `.input {$value :${functionName} select=exact}\n` +
      ".match $value\n" +
      "9007199254740993 {{unsafe}}\n" +
      "18446744073709551615 {{u64}}\n" +
      "* {{fallback}}",
  );
  for (const functions of numericRegistries) {
    for (const value of unsafeIntegerValues) {
      for (const [model, expected, expectedErrors] of [
        [unsafeInteger.model, "Value {$value}", ["bad-operand"]],
        [unsafeExact.model, "fallback", ["bad-operand", "bad-selector"]],
      ]) {
        const result = formatMessageFromFormatter(model, { value }, { functions });
        assert.equal(result.value, expected);
        assert.deepEqual(result.errors.map((error) => error.code), expectedErrors);
        assert.equal(result.errors[0] instanceof MF2Error, true);
      }
    }
  }
}
const largeOffset = parseToModelFromParser("Value {$value :offset add=1}");
const largeOffsetSelection = parseToModelFromParser(
  ".input {$value :offset add=1}\n" +
    ".match $value\n" +
    "9007199254740994 {{unsafe}}\n" +
    "18446744073709551616 {{u64}}\n" +
    "* {{fallback}}",
);
const largeExactOffsetSelection = parseToModelFromParser(
  ".input {$value :offset add=1 select=exact}\n" +
    ".match $value\n" +
    "9007199254740994 {{unsafe}}\n" +
    "18446744073709551616 {{u64}}\n" +
    "* {{fallback}}",
);
for (const functions of numericRegistries) {
  for (const [value, expected, exactSelection] of [
    ["9007199254740993", "9007199254740994", "unsafe"],
    [9007199254740993n, "9007199254740994", "unsafe"],
    ["18446744073709551615", "18446744073709551616", "u64"],
    [18446744073709551615n, "18446744073709551616", "u64"],
  ]) {
    const display = formatMessageFromFormatter(largeOffset.model, { value }, { functions });
    assert.equal(display.value, `Value ${expected}`);
    assert.deepEqual(display.errors, []);
    const selection = formatMessageFromFormatter(
      largeOffsetSelection.model,
      { value },
      { functions },
    );
    assert.equal(selection.value, "fallback");
    assert.deepEqual(selection.errors.map((error) => error.code), ["bad-selector"]);
    assert.equal(selection.errors[0] instanceof MF2Error, true);
    const exact = formatMessageFromFormatter(
      largeExactOffsetSelection.model,
      { value },
      { functions },
    );
    assert.equal(exact.value, exactSelection);
    assert.deepEqual(exact.errors, []);
  }
  const unsafeHostNumber = formatMessageFromFormatter(
    largeOffset.model,
    { value: Number.MAX_SAFE_INTEGER + 1 },
    { functions },
  );
  assert.equal(unsafeHostNumber.value, "Value {$value}");
  assert.deepEqual(unsafeHostNumber.errors.map((error) => error.code), ["bad-operand"]);
}
for (const functionName of ["number", "percent"]) {
  for (const optionName of ["minimumFractionDigits", "maximumFractionDigits"]) {
    const selectorOptionFailure = parseToModelFromParser(
      `.input {$value :${functionName} ${optionName}=101}\n` +
        ".match $value\n" +
        "1 {{one}}\n" +
        "2 {{two}}\n" +
        "3 {{three}}\n" +
        "* {{fallback}}",
    );
    for (const baseFunctions of numericRegistries) {
      const functions = baseFunctions.withFunction(functionName, (call) => String(call.value));
      const result = formatMessageFromFormatter(
        selectorOptionFailure.model,
        { value: 1 },
        { functions },
      );
      assert.equal(result.value, "fallback");
      assert.deepEqual(result.errors.map((error) => error.code), [
        "bad-option",
        "bad-selector",
      ]);
      assert.equal(result.errors.every((error) => error instanceof MF2Error), true);
    }
  }
}
const localizedNumericChain = parseToModelFromParser(
  ".local $value = {1000000 :number} {{Value: {$value :number maximumFractionDigits=0}}}",
);
assert.equal(
  formatMessageFromFormatter(localizedNumericChain.model, {}, { locale: "fr", functions: intlRegistry }).value,
  `Value: ${new Intl.NumberFormat("fr", { maximumFractionDigits: 0 }).format(1000000)}`,
);
const transformedNumericChain = parseToModelFromParser(
  ".local $value = {1000000.9 :integer} {{Value: {$value :number}}}",
);
assert.equal(
  formatMessageFromFormatter(transformedNumericChain.model, {}, { locale: "fr", functions: intlRegistry }).value,
  `Value: ${new Intl.NumberFormat("fr").format(1000000)}`,
);
const semanticNumericReannotation = parseToModelFromParser(
  ".local $value = {1.29 :number maximumFractionDigits=1} " +
    "{{Value {$value :number maximumFractionDigits=2}}}",
);
for (const functions of [FunctionRegistry.portable(), intlRegistry]) {
  const result = formatMessageFromFormatter(semanticNumericReannotation.model, {}, { functions });
  assert.equal(result.value, "Value 1.29");
  assert.deepEqual(result.errors, []);
}
for (const suffix of [
  "{{{$offset}}}",
  ".local $copy = {$offset :number maximumFractionDigits=1}\n{{{$copy}}}",
]) {
  const semanticDecimalOffset = parseToModelFromParser(
    ".local $number = {-1.9 :number maximumFractionDigits=0}\n" +
      ".local $offset = {$number :offset add=1}\n" +
      suffix,
  );
  for (const functions of [FunctionRegistry.portable(), intlRegistry]) {
    const result = formatMessageFromFormatter(semanticDecimalOffset.model, {}, { functions });
    assert.equal(result.value, "-0.9");
    assert.deepEqual(result.errors, []);
  }
}
for (const source of [
  ".input {$value :number maximumFractionDigits=1}\n" +
    ".match $value\n1000.3 {{rounded}}\n* {{fallback}}",
  ".input {$value :number maximumFractionDigits=1}\n" +
    ".local $copy = {$value :number}\n" +
    ".match $copy\n1000.3 {{rounded}}\n* {{fallback}}",
  ".input {$value :number maximumFractionDigits=1}\n" +
    ".local $copy = {$value :number maximumFractionDigits=2}\n" +
    ".match $copy\n1000.29 {{rounded}}\n* {{fallback}}",
]) {
  const roundedExactSelection = parseToModelFromParser(source);
  for (const functions of [FunctionRegistry.portable(), intlRegistry]) {
    const result = formatMessageFromFormatter(
      roundedExactSelection.model,
      { value: 1000.29 },
      { locale: "en", functions },
    );
    assert.equal(result.value, "rounded");
    assert.deepEqual(result.errors, []);
  }
}
for (const functions of numericRegistries) {
  for (const optionName of ["minimumFractionDigits", "maximumFractionDigits"]) {
    for (const optionValue of [100, 101, 4097, "9".repeat(512)]) {
      const boundedFraction = parseToModelFromParser(
        `Value {1 :number ${optionName}=${optionValue}}`,
      );
      const result = formatMessageFromFormatter(boundedFraction.model, {}, { functions });
      if (optionValue === 100) {
        const expected = optionName === "minimumFractionDigits"
          ? `Value 1.${"0".repeat(100)}`
          : "Value 1";
        assert.equal(result.value, expected);
        assert.deepEqual(result.errors, []);
      } else {
        assert.equal(result.value, "Value {|1|}");
        assert.deepEqual(result.errors.map((error) => error.code), ["bad-option"]);
        assert.equal(result.errors[0] instanceof MF2Error, true);
      }
    }
  }
}
const deepNumericDeclarations = [".local $value0 = {1 :number}"];
for (let index = 1; index < 7000; index += 1) {
  deepNumericDeclarations.push(`.local $value${index} = {$value${index - 1} :number}`);
}
deepNumericDeclarations.push("{{{$value6999 :number}}}");
const deepNumericChain = parseToModelFromParser(deepNumericDeclarations.join("\n"));
assert.deepEqual(deepNumericChain.diagnostics, []);
for (const functions of numericRegistries) {
  const result = formatMessageFromFormatter(deepNumericChain.model, {}, { functions });
  assert.equal(result.value, "1");
  assert.deepEqual(result.errors, []);
}
const nativeRangeError = parseToModelFromParser("Value {1 :nativeRangeError}");
const nativeRangeErrorRegistry = FunctionRegistry.portable().withFunction(
  "nativeRangeError",
  () => { throw new RangeError("host range failure"); },
);
const normalizedRangeError = formatMessageFromFormatter(
  nativeRangeError.model,
  {},
  { functions: nativeRangeErrorRegistry },
);
assert.equal(normalizedRangeError.value, "Value {|1|}");
assert.deepEqual(normalizedRangeError.errors.map((error) => error.code), ["error"]);
assert.equal(normalizedRangeError.errors[0] instanceof MF2Error, true);
const inheritedNumberOptions = parseToModelFromParser(
  ".input {$value :number minimumFractionDigits=2 signDisplay=always}\n{{{$value :number minimumFractionDigits=1}}}",
);
assert.equal(
  formatMessageFromFormatter(inheritedNumberOptions.model, { value: 1 }, { functions: intlRegistry }).value,
  "+1.0",
);
const inheritedSelectionOptions = parseToModelFromParser(
  ".input {$value :number minimumFractionDigits=1}\n" +
    ".local $copy = {$value :number}\n" +
    ".match $copy\none {{one}}\nother {{other}}\n* {{fallback}}",
);
assert.equal(
  formatMessageFromFormatter(inheritedSelectionOptions.model, { value: 1 }, { functions: intlRegistry }).value,
  "other",
);
const inheritedCurrency = parseToModelFromParser(
  ".local $value = {42 :currency currency=EUR} {{{$value :currency}}}",
);
assert.equal(
  formatMessageFromFormatter(inheritedCurrency.model, {}, { locale: "en-US", functions: intlRegistry }).value,
  new Intl.NumberFormat("en-US", { style: "currency", currency: "EUR" }).format(42),
);
const overriddenCurrency = parseToModelFromParser(
  ".local $value = {42 :currency currency=USD} {{{$value :currency currency=EUR}}}",
);
const overriddenCurrencyResult = formatMessageFromFormatter(
  overriddenCurrency.model,
  {},
  { locale: "en-US", functions: intlRegistry },
);
assert.deepEqual(overriddenCurrencyResult.errors.map((error) => error.code), ["bad-option"]);
const semanticTimePrecision = parseToModelFromParser(
  "At {|2006-01-02T15:04:06Z| :datetime timePrecision=second timeZone=UTC}",
);
assert.equal(
  formatMessageFromFormatter(semanticTimePrecision.model, {}, { locale: "en-US", functions: intlRegistry }).value,
  `At ${new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
    timeZone: "UTC",
  }).format(new Date("2006-01-02T15:04:06Z"))}`,
);
const inheritedDateOperand = parseToModelFromParser(
  ".local $value = {|2006-01-02T01:04:06Z| :datetime timeZone=UTC} " +
    "{{Date: {$value :date timeZone=|America/Los_Angeles|}}}",
);
assert.equal(
  formatMessageFromFormatter(inheritedDateOperand.model, {}, { locale: "en-US", functions: intlRegistry }).value,
  `Date: ${new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeZone: "America/Los_Angeles",
  }).format(new Date("2006-01-02T01:04:06Z"))}`,
);
const floatingDate = parseToModelFromParser(
  "Date: {|2006-01-02| :date timeZone=|America/Los_Angeles|}",
);
assert.equal(
  formatMessageFromFormatter(floatingDate.model, {}, { locale: "en-US", functions: intlRegistry }).value,
  `Date: ${new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeZone: "America/Los_Angeles",
  }).format(new Date("2006-01-02T08:00:00Z"))}`,
);
const previousProcessTimeZone = process.env.TZ;
try {
  process.env.TZ = "UTC";
  const floatingDateTime = parseToModelFromParser(
    "At {|2006-01-02T01:04:06| :datetime " +
      "dateStyle=medium timePrecision=second timeZone=|America/Los_Angeles|}",
  );
  assert.equal(
    formatMessageFromFormatter(floatingDateTime.model, {}, { locale: "en-US", functions: intlRegistry }).value,
    `At ${new Intl.DateTimeFormat("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
      timeZone: "America/Los_Angeles",
    }).format(new Date("2006-01-02T09:04:06Z"))}`,
  );
  const floatingTimeWithZoneName = parseToModelFromParser(
    "At {|2006-01-02T01:04:06| :time timeStyle=long timeZone=|America/Los_Angeles|}",
  );
  assert.equal(
    formatMessageFromFormatter(
      floatingTimeWithZoneName.model,
      {},
      { locale: "en-US", functions: intlRegistry },
    ).value,
    `At ${new Intl.DateTimeFormat("en-US", {
      timeStyle: "long",
      timeZone: "America/Los_Angeles",
    }).format(new Date("2006-01-02T09:04:06Z"))}`,
  );
  for (const [literal, expectedInstant] of [
    ["2024-03-10T02:30:00", "2024-03-10T10:30:00Z"],
    ["2024-11-03T01:30:00", "2024-11-03T08:30:00Z"],
  ]) {
    const daylightSavingTransition = parseToModelFromParser(
      `At {|${literal}| :time timeStyle=long timeZone=|America/Los_Angeles|}`,
    );
    assert.equal(
      formatMessageFromFormatter(
        daylightSavingTransition.model,
        {},
        { locale: "en-US", functions: intlRegistry },
      ).value,
      `At ${new Intl.DateTimeFormat("en-US", {
        timeStyle: "long",
        timeZone: "America/Los_Angeles",
      }).format(new Date(expectedInstant))}`,
    );
  }
} finally {
  if (previousProcessTimeZone == null) delete process.env.TZ;
  else process.env.TZ = previousProcessTimeZone;
}
for (const [literal, expectedInstant] of [
  ["2006-01-02T01:04:06Z", "2006-01-02T01:04:06Z"],
  ["2006-01-02T01:04:06+02:00", "2006-01-01T23:04:06Z"],
]) {
  const instantDateTime = parseToModelFromParser(
    `At {|${literal}| :datetime dateStyle=medium timePrecision=second timeZone=UTC}`,
  );
  assert.equal(
    formatMessageFromFormatter(instantDateTime.model, {}, { locale: "en-US", functions: intlRegistry }).value,
    `At ${new Intl.DateTimeFormat("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
      timeZone: "UTC",
    }).format(new Date(expectedInstant))}`,
  );
}
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
const selectionFixtureRoot = new URL("../../reference/fixtures/selection-operands/", import.meta.url);
const resolvedValueFixtureRoot = new URL("../../reference/fixtures/resolved-values/", import.meta.url);
const adapterFixtureGroups = [
  ...["common", "icu4j", "adapters"].map((group) => new URL(`${group}/`, selectionFixtureRoot)),
  new URL("adapters/", resolvedValueFixtureRoot),
];
let checkedSelectionCases = 0;
for (const groupUrl of adapterFixtureGroups) {
  for (const filename of readdirSync(groupUrl).filter((name) => name.endsWith(".json")).sort()) {
    const fixture = JSON.parse(readFileSync(new URL(filename, groupUrl), "utf8"));
    const selection = parseToModelFromParser(fixture.source);
    assert.deepEqual(selection.diagnostics, [], fixture.name);
    for (const formatCase of fixture.formatCases) {
      const actual = formatMessageFromFormatter(selection.model, formatCase.arguments, {
        locale: formatCase.locale,
        functions: intlRegistry,
      });
      assert.equal(actual.value, formatCase.expected, `${fixture.name}/${formatCase.name}`);
      assert.deepEqual(actual.errors, [], `${fixture.name}/${formatCase.name}`);
      checkedSelectionCases += 1;
    }
  }
}
assert.equal(checkedSelectionCases, 47);
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

console.log("MF2 JavaScript package boundary test passed");
