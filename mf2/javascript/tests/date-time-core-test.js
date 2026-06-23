import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import { FunctionRegistry, MF2Error, formatMessage, parseToModel } from "@mojito-mf2/core";
import {
  DateTimeCoreError,
  createDateTimeCoreFunctionRegistry,
  formatDateCore,
  formatDateTimeCore,
  formatTimeCore,
} from "@mojito-mf2/core/date-time-core";

const fixture = JSON.parse(
  await readFile(new URL("../../conformance/fixtures/date-time-core/cases.json", import.meta.url), "utf8"),
);

for (const item of fixture.formatCases) {
  assert.equal(formatCore(item), item.expected, item.name);
}

for (const item of fixture.numericTimestampCases ?? []) {
  assert.equal(formatCore(item), item.expected, item.name);
}

for (const item of fixture.intlReferenceCases) {
  assert.equal(formatCore(item), formatIntl(item), `Intl reference ${item.locale} ${JSON.stringify(item.options)}`);
}

for (const item of fixture.semanticStyleReferenceCases ?? []) {
  assert.equal(formatCore(item), formatIntl(referenceItem(item)), `semantic style reference ${item.name}`);
}

for (const item of fixture.errorCases) {
  assert.throws(
    () => formatCore(item),
    (error) => error instanceof DateTimeCoreError && error.code === item.expectedError,
    item.name,
  );
}

const functions = createDateTimeCoreFunctionRegistry(FunctionRegistry);
for (const item of fixture.registryFormatCases ?? []) {
  const parsed = parseToModel(item.source);
  assert.deepEqual(parsed.diagnostics, [], item.name);
  const result = formatMessage(parsed.model, item.arguments ?? {}, { locale: item.locale, functions });
  assert.equal(result.value, item.expected, item.name);
  assert.deepEqual(result.errors, [], item.name);
}
for (const item of fixture.registryErrorCases ?? []) {
  const parsed = parseToModel(item.source);
  assert.deepEqual(parsed.diagnostics, [], item.name);
  const result = formatMessage(parsed.model, item.arguments ?? {}, { locale: item.locale, functions });
  assert.deepEqual(
    result.errors.map((error) => error.code),
    item.expectedErrors,
    item.name,
  );
}
const dateMessage = parseToModel("At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}");
assert.equal(
  formatMessage(dateMessage.model, { instant: "2026-05-21T14:30:15Z" }, { locale: "de-DE", functions }).value,
  "At Donnerstag, 21. Mai 2026 um 14:30:15",
);
const localDateMessage = parseToModel(
  ".local $alias = {$instant}\n{{direct={$instant :datetime dateStyle=short timeStyle=short timeZone=UTC}; alias={$alias :datetime dateStyle=short timeStyle=short timeZone=UTC}}}",
);
const localDate = new Date("2026-05-21T14:30:00Z");
const localDateResult = formatMessage(localDateMessage.model, { instant: localDate }, { locale: "en-US", functions });
const localDateMatch = /^direct=(.*); alias=(.*)$/.exec(localDateResult.value);
assert.notEqual(localDateMatch, null);
assert.equal(localDateMatch[1], localDateMatch[2]);
assert.equal(
  localDateMatch[1],
  formatDateTimeCore(localDate, { locale: "en-US", dateStyle: "short", timeStyle: "short", timeZone: "UTC" }),
);
assert.deepEqual(localDateResult.errors, []);
const stringMessage = parseToModel("Hello {$name :string}");
assert.equal(formatMessage(stringMessage.model, { name: "Mojito" }, { functions }).value, "Hello Mojito");

assert.throws(
  () => formatDateTimeCore({ valueOf: () => 0 }),
  (error) => error instanceof DateTimeCoreError && error.code === "bad-operand",
  "rejects arbitrary date-time object valueOf coercion",
);
assert.throws(
  () => formatDateTimeCore(Object.create(null)),
  (error) => error instanceof DateTimeCoreError && error.code === "bad-operand",
  "rejects arbitrary null-prototype date-time object",
);
const objectMessage = parseToModel("At {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}");
const objectResult = formatMessage(objectMessage.model, { instant: { valueOf: () => 0 } }, { locale: "en-US", functions });
assert.deepEqual(
  objectResult.errors.map((error) => error.code),
  ["bad-operand"],
  "registry rejects arbitrary date-time object values",
);
const throwingOption = {
  toString() {
    throw new Error("time zone coercion failed");
  },
};
assert.throws(
  () => formatDateTimeCore("2026-05-21T14:30:15Z", { timeZone: throwingOption }),
  (error) => error instanceof DateTimeCoreError && error.code === "bad-option",
  "rejects non-coercible direct time zone options",
);
assert.throws(
  () => formatDateTimeCore("2026-05-21T14:30:15Z", { locale: throwingOption, timeZone: "UTC" }),
  (error) => error instanceof DateTimeCoreError && error.code === "bad-option",
  "rejects non-coercible direct locale options",
);
assert.throws(
  () => formatDateTimeCore("2026-05-21T14:30:15Z", { locale: "en-US", skeleton: throwingOption, timeZone: "UTC" }),
  (error) => error instanceof DateTimeCoreError && error.code === "bad-option",
  "rejects non-coercible direct skeleton options",
);
const optionMessage = parseToModel("At {$instant :datetime timeZone=$tz}");
const optionResult = formatMessage(
  optionMessage.model,
  { instant: "2026-05-21T14:30:15Z", tz: throwingOption },
  { locale: "en-US", functions },
);
assert.deepEqual(
  optionResult.errors.map((error) => error.code),
  ["bad-option"],
  "registry wraps non-coercible variable-valued options as MF2 bad-option errors",
);
assert.equal(optionResult.errors[0] instanceof MF2Error, true);

function formatCore(item) {
  const options = { locale: item.locale, ...item.options };
  if (item.kind === "date") return formatDateCore(item.value, options);
  if (item.kind === "time") return formatTimeCore(item.value, options);
  if (item.kind === "datetime") return formatDateTimeCore(item.value, options);
  throw new Error(`Unsupported date/time core fixture kind: ${item.kind}`);
}

function formatIntl(item) {
  return new Intl.DateTimeFormat(item.locale, { timeZone: "UTC", ...item.options }).format(new Date(item.value));
}

function referenceItem(item) {
  return { ...item, options: item.referenceOptions };
}

console.log(
  `Date/time core test passed ${fixture.formatCases.length} format cases, ` +
    `${fixture.numericTimestampCases?.length ?? 0} numeric timestamp cases, ` +
    `${fixture.intlReferenceCases.length} Intl reference cases, ` +
    `${fixture.semanticStyleReferenceCases?.length ?? 0} semantic style reference cases, ` +
    `${fixture.errorCases.length} error cases, ` +
    `${fixture.registryFormatCases?.length ?? 0} registry format cases, ` +
    `${fixture.registryErrorCases?.length ?? 0} registry error cases, and registry coverage.`,
);
