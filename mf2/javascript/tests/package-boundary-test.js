import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

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

const packageJson = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const packageReadme = readFileSync(new URL("../README.md", import.meta.url), "utf8").replace(/\s+/g, " ");
const forbiddenInflectionExportFragments = ["compiled", "inflection", "m2if"];

assert.equal(
  packageJson.scripts["inflection-release"],
  "python3 ../conformance/validate_inflection_release_fixture.py",
);
assert.match(packageJson.scripts.check, /npm run inflection-release/);
assert.match(packageReadme, /does not add inflection exports to `@mojito-mf2\/core`/);
assert.match(packageReadme, /selected V0 release-fixture artifacts only/);
assert.match(packageReadme, /not complete locale or grammar coverage/);
assert.match(packageReadme, /rather than package-local runtime code/);
assert.deepEqual(
  Object.keys(packageJson.exports).filter((subpath) =>
    forbiddenInflectionExportFragments.some((fragment) => subpath.toLowerCase().includes(fragment)),
  ),
  [],
);

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
assert.equal(createPortableFunctionRegistry(FunctionRegistry).hasFormatter({ name: "number" }), true);
const currency = parseToModel("Total: {$amount :currency currency=USD}");
const formattedCurrency = formatMessage(currency.model, { amount: 42 });
assert.equal(formattedCurrency.value, "Total: {$amount}");
assert.deepEqual(formattedCurrency.errors.map((error) => error.code), ["unknown-function"]);
const intlRegistry = createIntlFunctionRegistry(FunctionRegistry);
assert.equal(intlRegistry.hasFormatter({ name: "currency" }), true);
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
assert.equal(
  Object.keys(core).some((name) =>
    forbiddenInflectionExportFragments.some((fragment) => name.toLowerCase().includes(fragment)),
  ),
  false,
);
for (const subpath of ["compiled-term-pack", "inflection", "m2if"]) {
  await assert.rejects(() => import(`@mojito-mf2/core/${subpath}`));
}

console.log("MF2 JavaScript package boundary test passed");
