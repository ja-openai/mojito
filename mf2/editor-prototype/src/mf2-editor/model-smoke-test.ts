import { parseToModel } from "@mojito-mf2/core";

import { diagnosticsForForm, diagnosticsNearActiveEditor } from "./diagnostics";
import { formNavigationIntent, formRowActivationIntent, type EditorKeyEvent } from "./keyboard";
import {
  addLocalePluralRows,
  bestVariantForKeys,
  diagnosticsFor,
  filterPlaceholderNames,
  invalidLocalePluralRows,
  localePluralRowSuggestions,
  missingPlaceholderNamesForActiveSource,
  missingPlaceholderNamesForPattern,
  parseMf2,
  patternWithRestoredPlaceholders,
  placeholderCompletionConsumesClosingBrace,
  placeholderCompletionContext,
  placeholderCompletionReplacement,
  placeholderCompletionToken,
  placeholderExpressionsInPattern,
  placeholderInsertionNames,
  placeholderInsertionNamesForActiveSource,
  placeholderNames,
  placeholderNamesInPattern,
  placeholderSourceByName,
  placeholderSourcesInPattern,
  printModel,
  removeInvalidLocalePluralRows,
  sourceInputContractItems,
  sourceFormLabelForTargetKeys,
  sourceFormLabelForTargetModel,
  sourceVariantForTargetKeys,
  sourceVariantForTargetModel,
} from "./model";

assertDeepEqual(formNavigationIntent(keyEvent("ArrowDown", { shiftKey: true })), {
  direction: 1,
  reason: "shift-arrow",
});
assertDeepEqual(formNavigationIntent(keyEvent("ArrowUp", { shiftKey: true })), {
  direction: -1,
  reason: "shift-arrow",
});
assertDeepEqual(formNavigationIntent(keyEvent("Enter", { metaKey: true })), {
  direction: 1,
  reason: "command-enter",
});
assertDeepEqual(formNavigationIntent(keyEvent("Enter", { ctrlKey: true })), {
  direction: 1,
  reason: "command-enter",
});
assertEqual(formNavigationIntent(keyEvent("ArrowDown", { metaKey: true, shiftKey: true })), null);
assertEqual(formNavigationIntent(keyEvent("ArrowUp", { altKey: true, shiftKey: true })), null);
assertEqual(formNavigationIntent(keyEvent("Enter", { metaKey: true, shiftKey: true })), null);
assertEqual(formNavigationIntent(keyEvent("Tab", { shiftKey: true })), null);
assertEqual(formRowActivationIntent(keyEvent("Enter")), true);
assertEqual(formRowActivationIntent(keyEvent(" ")), true);
assertEqual(formRowActivationIntent(keyEvent("Enter", { metaKey: true })), false);
assertEqual(formRowActivationIntent(keyEvent("Enter", { ctrlKey: true })), false);
assertEqual(formRowActivationIntent(keyEvent(" ", { shiftKey: true })), false);
assertDeepEqual(
  diagnosticsNearActiveEditor([
    {
      code: "missing-locale-plural-variant",
      message: "Target locale ar has CLDR cardinal category few for $count, but the target has no count: few form; fallback may still cover it.",
      severity: "warning",
    },
    {
      code: "runtime-error",
      message: "Parser diagnostic without form metadata",
      severity: "error",
    },
    {
      code: "target-only-variant",
      formLabel: "count: one",
      message: "Target adds form count: one.",
      severity: "warning",
    },
  ], "count: one", "rich").map((diagnostic) => diagnostic.code),
  ["target-only-variant", "runtime-error"],
);
assertDeepEqual(
  diagnosticsNearActiveEditor([
    {
      code: "missing-locale-plural-variant",
      message: "Target locale ar has CLDR cardinal category few for $count, but the target has no count: few form; fallback may still cover it.",
      severity: "warning",
    },
    {
      code: "runtime-error",
      message: "Parser diagnostic without form metadata",
      severity: "error",
    },
    {
      code: "target-only-variant",
      formLabel: "count: one",
      message: "Target adds form count: one.",
      severity: "warning",
    },
  ], "count: one", "raw").map((diagnostic) => diagnostic.code),
  ["runtime-error", "target-only-variant"],
);
assertDeepEqual(
  diagnosticsForForm([
    {
      code: "target-only-variant",
      message: "Target adds form count: one.",
      severity: "warning",
    },
    {
      code: "target-only-variant",
      formLabel: "count: one",
      message: "Localized diagnostic text can change.",
      severity: "warning",
    },
    {
      code: "selector-priority-overlap",
      formLabels: ["count: one", "count: fallback"],
      message: "Localized overlap text can change.",
      severity: "warning",
    },
  ], "count: one").map((diagnostic) => diagnostic.message),
  ["Localized diagnostic text can change.", "Localized overlap text can change."],
);

const pluralSource = `.input {$count :number}
.match $count
one {{You have {$count} file}}
* {{You have {$count} files}}`;
const pluralModel = parseMf2(pluralSource, { count: 2 }, "ar").model;
const arabicSuggestions = localePluralRowSuggestions(pluralModel, "ar");
assertDeepEqual(arabicSuggestions.map((suggestion) => suggestion.category), ["zero", "two", "few", "many"]);
assertEqual(arabicSuggestions.some((suggestion) => suggestion.category === "other"), false);

const pluralInsert = addLocalePluralRows(pluralModel, arabicSuggestions, pluralModel);
if (!pluralInsert) throw new Error("Expected locale plural rows to be inserted.");
assertEqual(pluralInsert.activeIndex, 1);
assertEqual(pluralInsert.model.variants.find((variant) => variant.keys[0] === "zero")?.value, "");
assertEqual(pluralInsert.model.variants.find((variant) => variant.keys[0] === "many")?.value, "");
assertEqual(printModel(pluralInsert.model).includes("other {{}}"), false);
const staleSuggestionInsert = addLocalePluralRows(pluralModel, [
  { ...arabicSuggestions[0], category: "one", keys: ["one"], label: "count: one" },
  arabicSuggestions[0],
], pluralModel);
if (!staleSuggestionInsert) throw new Error("Expected stale duplicate suggestions to still insert new rows.");
assertEqual(staleSuggestionInsert.activeIndex, 1);
assertDeepEqual(staleSuggestionInsert.model.variants.map((variant) => variant.keys[0]), ["one", "zero", "*"]);
assertEqual(addLocalePluralRows(pluralModel, [
  { ...arabicSuggestions[0], category: "one", keys: ["one"], label: "count: one" },
], pluralModel), null);

const invalidEnglishRows = invalidLocalePluralRows(pluralInsert.model, "en");
assertDeepEqual(invalidEnglishRows.map((row) => row.category), ["zero", "two", "few", "many"]);
assertEqual(invalidEnglishRows.every((row) => row.removable), true);
const cleanedEnglishRows = removeInvalidLocalePluralRows(pluralInsert.model, invalidEnglishRows);
if (!cleanedEnglishRows) throw new Error("Expected invalid blank rows to be removable.");
assertDeepEqual(cleanedEnglishRows.model.variants.map((variant) => variant.keys[0]), ["one", "*"]);
assertEqual(invalidLocalePluralRows(cleanedEnglishRows.model, "en").length, 0);
const welshSuggestions = localePluralRowSuggestions(pluralModel, "cy");
assertEqual(welshSuggestions.some((suggestion) => suggestion.category === "many" && suggestion.sample === 6), true);
assertEqual(
  invalidLocalePluralRows(parseMf2(`.input {$count :number}
.match $count
many {{Welsh many}}
* {{Other}}`, { count: 6 }, "cy").model, "cy").some((row) => row.category === "many"),
  false,
);
const fallbackOnlyPluralModel = parseMf2(`.input {$count :number}
.match $count
* {{Files}}`, { count: 2 }, "en").model;
const translatedFallbackPluralModel = parseMf2(`.input {$count :number}
.match $count
* {{Fichiers}}`, { count: 2 }, "fr").model;
const translatedFallbackArabicInsert = addLocalePluralRows(
  translatedFallbackPluralModel,
  localePluralRowSuggestions(translatedFallbackPluralModel, "ar"),
  fallbackOnlyPluralModel,
);
if (!translatedFallbackArabicInsert) throw new Error("Expected translated fallback locale plural rows to be inserted.");
assertEqual(translatedFallbackArabicInsert.model.variants.find((variant) => variant.keys[0] === "zero")?.value, "Fichiers");
const ptBrUnderscoreCategories = localePluralRowSuggestions(fallbackOnlyPluralModel, "pt_BR")
  .map((suggestion) => suggestion.category);
assertEqual(ptBrUnderscoreCategories.includes("one"), true);
assertDeepEqual(
  ptBrUnderscoreCategories,
  localePluralRowSuggestions(fallbackOnlyPluralModel, "pt-BR").map((suggestion) => suggestion.category),
);
assertEqual(diagnosticsFor(fallbackOnlyPluralModel, fallbackOnlyPluralModel, [], "pt_BR")
  .some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"), true);
assertEqual(localePluralRowSuggestions(fallbackOnlyPluralModel, "not_a_locale").length >= 0, true);
const noFallbackPluralModel = parseMf2(`.input {$count :number}
.match $count
one {{One file}}`, { count: 1 }, "en").model;
assertDeepEqual(localePluralRowSuggestions(noFallbackPluralModel, "en").map((suggestion) => suggestion.category), ["other"]);
assertEqual(
  diagnosticsFor(fallbackOnlyPluralModel, noFallbackPluralModel, [], "en")
    .some((diagnostic) => (
      diagnostic.code === "missing-locale-plural-variant"
      && diagnostic.message.includes("category other")
      && diagnostic.message.includes("add this form or a fallback row")
    )),
  true,
);
assertEqual(
  diagnosticsFor(fallbackOnlyPluralModel, fallbackOnlyPluralModel, [], "en")
    .some((diagnostic) => (
      diagnostic.code === "missing-locale-plural-variant"
      && diagnostic.message.includes("fallback may still cover it")
    )),
  true,
);

const sourceTargetDiagnostics = diagnosticsFor(
  parseMf2("Hello {$name}", { name: "Mina" }, "en").model,
  parseMf2("Bonjour {$newName}", { newName: "Mina" }, "fr").model,
  [],
);
assertEqual(sourceTargetDiagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), true);
assertEqual(sourceTargetDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), true);
assertEqual(
  sourceTargetDiagnostics.find((diagnostic) => diagnostic.code === "missing-source-placeholder")?.severity,
  "warning",
);
assertEqual(sourceTargetDiagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), true);

const localPlaceholderDiagnostics = diagnosticsFor(
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model,
  parseMf2("Prix", { digits: 2, price: 12 }, "fr").model,
  [],
);
assertDeepEqual(
  localPlaceholderDiagnostics
    .filter((diagnostic) => diagnostic.code === "missing-source-placeholder")
    .map((diagnostic) => diagnostic.message),
  ["Target omits source placeholder {$priceLabel}; keep it omitted only when intentional."],
);
assertDeepEqual(
  localPlaceholderDiagnostics
    .filter((diagnostic) => diagnostic.code === "variant-missing-placeholder")
    .map((diagnostic) => diagnostic.message),
  ["Target form message omits source placeholder {$priceLabel}; keep it omitted only when intentional."],
);
const localPlaceholderTargetDiagnostics = parseMf2("Prix {$priceLabel}", { digits: 2, price: 12 }, "fr");
assertDeepEqual(
  diagnosticsFor(
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model,
    localPlaceholderTargetDiagnostics.model,
    localPlaceholderTargetDiagnostics.diagnostics,
  )
    .filter((diagnostic) => diagnostic.code === "missing-source-placeholder")
    .map((diagnostic) => diagnostic.message),
  [],
);
assertDeepEqual(
  diagnosticsFor(
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model,
    parseMf2("Prix {$price} {$digits}", { digits: 2, price: 12 }, "fr").model,
    [],
  )
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  [
    "Target uses {$price}, which is not in the source.",
    "Target uses {$digits}, which is not in the source.",
  ],
);
assertDeepEqual(
  diagnosticsFor(
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model,
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Prix {$priceLabel}}}`, { digits: 2, price: 12 }, "fr").model,
    [],
  )
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  [],
);
assertDeepEqual(
  diagnosticsFor(
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model,
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Prix {$priceLabel} {$price}}}`, { digits: 2, price: 12 }, "fr").model,
    [],
  )
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  ["Target uses {$price}, which is not in the source."],
);
const copiedLocalOmissionDiagnostics = diagnosticsFor(
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model,
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Prix}}`, { digits: 2, price: 12 }, "fr").model,
  [],
);
assertDeepEqual(
  copiedLocalOmissionDiagnostics
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  [],
);
assertDeepEqual(
  copiedLocalOmissionDiagnostics
    .filter((diagnostic) => diagnostic.code === "missing-source-placeholder")
    .map((diagnostic) => diagnostic.message),
  ["Target omits source placeholder {$priceLabel}; keep it omitted only when intentional."],
);
assertDeepEqual(
  diagnosticsFor(
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
.local $displayPrice = {$priceLabel}
{{Price {$displayPrice}}}`, { digits: 2, price: 12 }, "en").model,
    parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
.local $displayPrice = {$priceLabel}
{{Prix {$displayPrice}}}`, { digits: 2, price: 12 }, "fr").model,
    [],
  )
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  [],
);

const variantPlaceholderDiagnostics = diagnosticsFor(
  parseMf2(pluralSource, { count: 2 }, "en").model,
  parseMf2(`.input {$count :number}
.match $count
one {{You have {$count} file}}
* {{You have files}}`, { count: 2 }, "en").model,
  [],
);
assertEqual(
  variantPlaceholderDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"),
  false,
);
assertEqual(
  variantPlaceholderDiagnostics.some((diagnostic) => (
    diagnostic.code === "variant-missing-placeholder"
      && diagnostic.message.includes("count: fallback")
      && diagnostic.message.includes("{$count}")
  )),
  true,
);
assertEqual(
  variantPlaceholderDiagnostics.find((diagnostic) => diagnostic.code === "variant-missing-placeholder")?.formLabel,
  "count: fallback",
);

const changedSelectorDiagnostics = diagnosticsFor(
  parseMf2(pluralSource, { count: 2 }, "en").model,
  parseMf2(`.input {$total :number}
.match $total
one {{You have {$total} file}}
* {{You have {$total} files}}`, { total: 2 }, "en").model,
  [],
);
assertEqual(changedSelectorDiagnostics.some((diagnostic) => diagnostic.code === "new-selector"), true);
assertEqual(changedSelectorDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-selector"), true);
assertEqual(changedSelectorDiagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), false);
assertEqual(changedSelectorDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), false);

const sourceTwoSelectors = `.input {$count :number}
.input {$status :string}
.match $count $status
* * {{Status {$status} for {$count} file(s)}}`;
const reorderedSelectorDiagnostics = diagnosticsFor(
  parseMf2(sourceTwoSelectors, { count: 2, status: "open" }, "en").model,
  parseMf2(`.input {$count :number}
.input {$status :string}
.match $status $count
* * {{Status {$status} for {$count} file(s)}}`, { count: 2, status: "open" }, "en").model,
  [],
);
assertEqual(reorderedSelectorDiagnostics.some((diagnostic) => diagnostic.code === "selector-order-mismatch"), true);
assertEqual(reorderedSelectorDiagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), false);

const selectorBodyPlaceholderDiagnostics = diagnosticsFor(
  parseMf2(`.input {$status :string}
.match $status
open {{Status {$status}}}
* {{Fallback {$status}}}`, { status: "open" }, "en").model,
  parseMf2(`.input {$status :string}
.match $status
open {{Status}}
* {{Fallback}}`, { status: "open" }, "en").model,
  [],
);
assertEqual(selectorBodyPlaceholderDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), false);
assertEqual(selectorBodyPlaceholderDiagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), true);

const declarationOnlyVariantDiagnostics = diagnosticsFor(
  parseMf2(`.input {$kind :string}
.input {$name :string @title=|Name|}
.match $kind
a {{A}}
b {{B}}
* {{Fallback}}`, { kind: "a", name: "Mina" }, "en").model,
  parseMf2(`.input {$kind :string}
.input {$name :string @title=|Name|}
.match $kind
a {{A}}
b {{B {$name}}}
* {{Fallback}}`, { kind: "a", name: "Mina" }, "fr").model,
  [],
);
assertEqual(declarationOnlyVariantDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), false);
assertEqual(
  declarationOnlyVariantDiagnostics.some((diagnostic) => (
    diagnostic.code === "variant-missing-placeholder"
      && diagnostic.formLabel === "kind: a"
      && diagnostic.message.includes("{$name}")
  )),
  true,
);

const selectorOptionPlaceholderDiagnostics = diagnosticsFor(
  parseMf2(`.input {$status :string}
.input {$price :number}
.match $status
open {{Value {$price :number unit=$status}}}
* {{Fallback {$price :number unit=$status}}}`, { price: 1, status: "open" }, "en").model,
  parseMf2(`.input {$status :string}
.input {$price :number}
.match $status
open {{Value {$price :number}}}
* {{Fallback {$price :number}}}`, { price: 1, status: "open" }, "en").model,
  [],
);
assertEqual(
  selectorOptionPlaceholderDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"),
  true,
);
const targetAddedOptionOwnerDiagnostics = diagnosticsFor(
  parseMf2(`.input {$price :number}
{{Price {$price}}}`, { digits: 2, price: 12 }, "en").model,
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
{{Prix {$price}}}`, { digits: 2, price: 12 }, "fr").model,
  [],
);
assertEqual(
  targetAddedOptionOwnerDiagnostics.some((diagnostic) => (
    diagnostic.code === "new-placeholder"
    && diagnostic.message.includes("{$digits}")
  )),
  true,
);
assertDeepEqual(
  diagnosticsFor(
    parseMf2("Price", { digits: 2, price: 12 }, "en").model,
    parseMf2("Prix {$price :number minimumFractionDigits=$digits}", { digits: 2, price: 12 }, "fr").model,
    [],
  )
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  ["Target uses {$price}, which is not in the source."],
);
assertDeepEqual(
  diagnosticsFor(
    parseMf2("Price", { digits: 2, price: 12 }, "en").model,
    parseMf2("Prix {$price :number minimumFractionDigits=$digits} {$digits}", { digits: 2, price: 12 }, "fr").model,
    [],
  )
    .filter((diagnostic) => diagnostic.code === "new-placeholder")
    .map((diagnostic) => diagnostic.message),
  [
    "Target uses {$price}, which is not in the source.",
    "Target uses {$digits}, which is not in the source.",
  ],
);
const omittedTargetAddedOptionOwnerDiagnostics = diagnosticsFor(
  parseMf2(`.input {$price :number}
{{Price {$price}}}`, { digits: 2, price: 12 }, "en").model,
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
{{Prix}}`, { digits: 2, price: 12 }, "fr").model,
  [],
);
assertEqual(
  omittedTargetAddedOptionOwnerDiagnostics.some((diagnostic) => (
    diagnostic.code === "new-placeholder"
    && diagnostic.message.includes("{$digits}")
  )),
  false,
);
const omittedOptionOwnerDiagnostics = diagnosticsFor(
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
{{Price {$price}}}`, { digits: 2, price: 12 }, "en").model,
  parseMf2(`.input {$price :number minimumFractionDigits=$digits}
{{Prix}}`, { digits: 2, price: 12 }, "fr").model,
  [],
);
assertDeepEqual(
  omittedOptionOwnerDiagnostics
    .filter((diagnostic) => diagnostic.code === "missing-source-placeholder")
    .map((diagnostic) => diagnostic.message),
  [],
);
assertEqual(omittedOptionOwnerDiagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), true);
const omittedSelectorOptionOwnerDiagnostics = diagnosticsFor(
  parseMf2(`.input {$status :string}
.input {$price :number}
.match $status
open {{Value {$price :number unit=$status}}}
* {{Fallback {$price :number unit=$status}}}`, { price: 1, status: "open" }, "en").model,
  parseMf2(`.input {$status :string}
.input {$price :number}
.match $status
open {{Value}}
* {{Fallback}}`, { price: 1, status: "open" }, "en").model,
  [],
);
assertEqual(
  omittedSelectorOptionOwnerDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"),
  false,
);
assertEqual(
  omittedSelectorOptionOwnerDiagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"),
  true,
);

const specificPluralRowModel = parseMf2(`.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He has one file}}
* * {{They have files}}`, { count: 1, gender: "male" }, "en").model;
const specificPluralSuggestions = localePluralRowSuggestions(specificPluralRowModel, "en");
assertDeepEqual(specificPluralSuggestions.map((suggestion) => suggestion.keys), [["*", "one"]]);
assertEqual(
  diagnosticsFor(specificPluralRowModel, specificPluralRowModel, [], "en")
    .some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"),
  true,
);
const specificExactPluralRowModel = parseMf2(`.input {$gender :string}
.input {$count :number}
.match $gender $count
male 1 {{He has exactly one file}}
* * {{They have files}}`, { count: 1, gender: "male" }, "en").model;
assertDeepEqual(
  localePluralRowSuggestions(specificExactPluralRowModel, "en").map((suggestion) => suggestion.keys),
  [["*", "one"]],
);
assertEqual(
  diagnosticsFor(specificExactPluralRowModel, specificExactPluralRowModel, [], "en")
    .some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"),
  true,
);
const genericExactPluralRowModel = parseMf2(`.input {$gender :string}
.input {$count :number}
.match $gender $count
* 1 {{Exactly one file}}
* * {{They have files}}`, { count: 1, gender: "male" }, "en").model;
assertEqual(localePluralRowSuggestions(genericExactPluralRowModel, "en").length, 0);
assertEqual(
  diagnosticsFor(genericExactPluralRowModel, genericExactPluralRowModel, [], "en")
    .some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"),
  false,
);

const annotationDiagnostics = diagnosticsFor(
  parseMf2(pluralSource, { count: 2 }, "en").model,
  parseMf2(`.input {$count :string}
.match $count
one {{You have {$count} file}}
* {{You have {$count} files}}`, { count: 2 }, "en").model,
  [],
);
assertEqual(annotationDiagnostics.some((diagnostic) => diagnostic.code === "selector-annotation-mismatch"), true);
assertEqual(annotationDiagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), false);

const nonBlankInvalidModel = {
  ...pluralInsert.model,
  variants: pluralInsert.model.variants.map((variant) => (
    variant.keys[0] === "zero" ? { ...variant, value: "Zero files" } : { ...variant }
  )),
};
const mixedInvalidRows = invalidLocalePluralRows(nonBlankInvalidModel, "en");
assertEqual(mixedInvalidRows.find((row) => row.category === "zero")?.removable, false);
const partiallyCleanedRows = removeInvalidLocalePluralRows(nonBlankInvalidModel, mixedInvalidRows);
if (!partiallyCleanedRows) throw new Error("Expected blank invalid rows to be removable.");
assertEqual(partiallyCleanedRows.model.variants.find((variant) => variant.keys[0] === "zero")?.value, "Zero files");
assertDeepEqual(invalidLocalePluralRows(partiallyCleanedRows.model, "en").map((row) => row.category), ["zero"]);

const wildcardMiddleSource = `.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare * singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
* * * {{Schild}}`;
const wildcardMiddleModel = parseMf2(
  wildcardMiddleSource,
  { case: "dative", number: "singular", usage: "bare" },
  "de",
).model;

const inlineMatchSelectorSource = `.match {$usage :select} {$case :select} {$number :select}
* * * {{Schild}}`;
const inlineMatchSelectorDiagnostics = parseToModel(inlineMatchSelectorSource).diagnostics ?? [];
assertEqual(inlineMatchSelectorDiagnostics[0]?.code, "unsupported-match-selector-expression");
assertEqual(inlineMatchSelectorDiagnostics[0]?.start, 7);
assertEqual(inlineMatchSelectorDiagnostics[0]?.end, 23);
const inlineMatchSelectorEditorDiagnostics = parseMf2(inlineMatchSelectorSource, {}, "de").diagnostics;
assertEqual(inlineMatchSelectorEditorDiagnostics[0]?.code, "unsupported-match-selector-expression");
assertEqual(inlineMatchSelectorEditorDiagnostics[0]?.start, 7);
assertEqual(inlineMatchSelectorEditorDiagnostics[0]?.end, 23);

assertDeepEqual(bestVariantForKeys(wildcardMiddleModel, ["bare", "dative", "singular"])?.keys, [
  "bare",
  "*",
  "singular",
]);
assertDeepEqual(bestVariantForKeys(wildcardMiddleModel, ["definite", "dative", "singular"])?.keys, [
  "definite",
  "dative",
  "singular",
]);
assertEqual(sourceVariantForTargetKeys(wildcardMiddleModel, ["bare", "dative", "singular"])?.value, "Schild");
assertEqual(sourceVariantForTargetModel(wildcardMiddleModel, wildcardMiddleModel, ["bare", "dative", "singular"])?.value, "Schild");
assertEqual(
  sourceFormLabelForTargetKeys(wildcardMiddleModel, ["bare", "dative", "singular"]),
  "usage: bare / case: fallback / number: singular",
);
assertEqual(
  sourceFormLabelForTargetModel(wildcardMiddleModel, wildcardMiddleModel, ["bare", "dative", "singular"]),
  "usage: bare / case: fallback / number: singular",
);
assertEqual(
  sourceFormLabelForTargetKeys(wildcardMiddleModel, ["definite", "dative", "singular"]),
  "usage: definite / case: dative / number: singular",
);

const wildcardSelectorPrioritySource = `.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
* dative singular {{case-priority row}}
bare * singular {{usage-priority row}}
* * * {{fallback}}`;
const wildcardSelectorPriorityModel = parseMf2(
  wildcardSelectorPrioritySource,
  { case: "dative", number: "singular", usage: "bare" },
  "de",
).model;
assertDeepEqual(bestVariantForKeys(wildcardSelectorPriorityModel, ["bare", "dative", "singular"])?.keys, [
  "bare",
  "*",
  "singular",
]);
const noFallbackSourceMatchModel = parseMf2(`.input {$kind :string}
.input {$name :string}
.match $kind
a {{A {$name}}}`, { kind: "b", name: "Mina" }, "en").model;
if (!noFallbackSourceMatchModel) throw new Error("Expected no-fallback source match model.");
assertEqual(bestVariantForKeys(noFallbackSourceMatchModel, ["b"]), null);
assertEqual(sourceVariantForTargetKeys(noFallbackSourceMatchModel, ["b"]), null);
assertEqual(sourceFormLabelForTargetKeys(noFallbackSourceMatchModel, ["b"]), "No matching source form");
const renamedSelectorTargetModel = parseMf2(`.input {$total :number}
.match $total
one {{Target one}}
* {{Target fallback}}`, { total: 1 }, "en").model;
if (!renamedSelectorTargetModel) throw new Error("Expected renamed-selector target model.");
assertEqual(sourceVariantForTargetKeys(pluralModel, ["one"])?.value, "You have {$count} file");
assertEqual(sourceVariantForTargetModel(pluralModel, renamedSelectorTargetModel, ["one"]), null);
assertEqual(sourceFormLabelForTargetModel(pluralModel, renamedSelectorTargetModel, ["one"]), "No matching source form");
const annotationChangedTargetModel = parseMf2(`.input {$count :string}
.match $count
one {{Target one}}
* {{Target fallback}}`, { count: "one" }, "en").model;
if (!annotationChangedTargetModel) throw new Error("Expected annotation-changed target model.");
assertEqual(sourceVariantForTargetModel(pluralModel, annotationChangedTargetModel, ["one"]), null);
assertEqual(sourceFormLabelForTargetModel(pluralModel, annotationChangedTargetModel, ["one"]), "No matching source form");
const reorderedSelectorTargetModel = parseMf2(`.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $case $usage $number
dative bare singular {{Target row}}
* * * {{Target fallback}}`, { case: "dative", number: "singular", usage: "bare" }, "de").model;
if (!reorderedSelectorTargetModel) throw new Error("Expected reordered-selector target model.");
assertEqual(
  sourceVariantForTargetModel(wildcardMiddleModel, reorderedSelectorTargetModel, ["dative", "bare", "singular"]),
  null,
);
assertEqual(
  sourceFormLabelForTargetModel(wildcardMiddleModel, reorderedSelectorTargetModel, ["dative", "bare", "singular"]),
  "No matching source form",
);

const missingSourceVariantDiagnostics = diagnosticsFor(
  parseMf2(`.input {$count :number}
.match $count
0 {{No files}}
one {{One file}}
* {{Many files}}`, { count: 0 }, "en").model,
  parseMf2(`.input {$count :number}
.match $count
* {{Files}}`, { count: 0 }, "en").model,
  [],
);
assertEqual(missingSourceVariantDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-variant"), true);
assertEqual(
  missingSourceVariantDiagnostics.find((diagnostic) => diagnostic.code === "missing-source-variant")?.formLabel,
  "count: 0",
);

const targetOnlyVariantDiagnostics = diagnosticsFor(
  parseMf2(pluralSource, { count: 2 }, "en").model,
  parseMf2(`.input {$count :number}
.match $count
0 {{No files}}
one {{One file}}
* {{Many files}}`, { count: 0 }, "en").model,
  [],
);
assertEqual(targetOnlyVariantDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), true);
assertEqual(
  targetOnlyVariantDiagnostics.find((diagnostic) => diagnostic.code === "target-only-variant")?.formLabel,
  "count: 0",
);

const reorderedVariantRowDiagnostics = diagnosticsFor(
  parseMf2(`.input {$kind :string}
.match $kind
error {{The operation failed: {$kind}}}
* {{The operation completed: {$kind}}}`, { kind: "ok" }, "en").model,
  parseMf2(`.input {$kind :string}
.match $kind
* {{Operation terminee: {$kind}}}
error {{Operation echouee: {$kind}}}`, { kind: "ok" }, "en").model,
  [],
);
assertEqual(reorderedVariantRowDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-variant"), false);
assertEqual(reorderedVariantRowDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);

const wildcardMiddleRefinementDiagnostics = diagnosticsFor(
  parseMf2(wildcardMiddleSource, { case: "dative", number: "singular", usage: "bare" }, "de").model,
  parseMf2(`.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare dative singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
* * * {{Schild}}`, { case: "dative", number: "singular", usage: "bare" }, "de").model,
  [],
  "de",
);
assertEqual(wildcardMiddleRefinementDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);

const arabicLocaleVariantDiagnostics = diagnosticsFor(
  parseMf2(`.input {$count :number}
.match $count
* {{Files}}`, { count: 2 }, "en").model,
  parseMf2(`.input {$count :number}
.match $count
two {{ملفان}}
* {{ملفات}}`, { count: 2 }, "ar").model,
  [],
  "ar",
);
assertEqual(arabicLocaleVariantDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
assertEqual(arabicLocaleVariantDiagnostics.some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"), true);
const redundantOtherDiagnostics = diagnosticsFor(
  parseMf2(`.input {$count :number}
.match $count
* {{Files}}`, { count: 2 }, "en").model,
  parseMf2(`.input {$count :number}
.match $count
other {{Other files}}
* {{Files}}`, { count: 2 }, "en").model,
  [],
  "en",
);
assertEqual(
  redundantOtherDiagnostics.some((diagnostic) => (
    diagnostic.code === "target-only-variant"
    && diagnostic.formLabel === "count: other"
    && diagnostic.message.includes("fallback already covers")
  )),
  true,
);

const overlappingNumericDiagnostics = diagnosticsFor(
  parseMf2(`.input {$count :integer}
.match $count
* {{fallback}}
1 {{exact one}}
one {{plural one}}`, { count: 1 }, "en").model,
  parseMf2(`.input {$count :integer}
.match $count
* {{fallback}}
1 {{exact one}}
one {{plural one}}`, { count: 1 }, "en").model,
  [],
);
assertEqual(overlappingNumericDiagnostics.some((diagnostic) => diagnostic.code === "overlapping-numeric-variant"), true);
assertDeepEqual(
  overlappingNumericDiagnostics.find((diagnostic) => diagnostic.code === "overlapping-numeric-variant")?.formLabels,
  ["count: 1", "count: one"],
);

const selectorPriorityDiagnostics = diagnosticsFor(
  parseMf2(`.input {$first :string}
.input {$second :string}
.match $first $second
* * {{fallback}}
* exact {{second exact}}
exact * {{first exact}}`, { first: "exact", second: "exact" }, "en").model,
  parseMf2(`.input {$first :string}
.input {$second :string}
.match $first $second
* * {{fallback}}
* exact {{second exact}}
exact * {{first exact}}`, { first: "exact", second: "exact" }, "en").model,
  [],
);
assertEqual(selectorPriorityDiagnostics.some((diagnostic) => diagnostic.code === "selector-priority-overlap"), true);
assertEqual(
  selectorPriorityDiagnostics
    .find((diagnostic) => diagnostic.code === "selector-priority-overlap")
    ?.message.includes("first: exact / second: fallback wins"),
  true,
);
assertDeepEqual(
  selectorPriorityDiagnostics.find((diagnostic) => diagnostic.code === "selector-priority-overlap")?.formLabels,
  ["first: fallback / second: exact", "first: exact / second: fallback"],
);

const markupSource = "Tap {#link href=|/profile| @title=|Profile|}profile{/link}.";
const markupModel = parseMf2(markupSource, {}, "en").model;
if (!markupModel || markupModel.type !== "message") throw new Error("Expected markup source to parse.");
assertEqual(markupModel.pattern.includes("href=|/profile|"), true);
assertEqual(markupModel.pattern.includes("@title=|Profile|"), true);
assertEqual(printModel(markupModel), markupSource);
const standaloneMarkupModel = parseMf2("Saved {#icon name=|check| @label=|Done|/}.", {}, "en").model;
if (!standaloneMarkupModel || standaloneMarkupModel.type !== "message") {
  throw new Error("Expected standalone markup source to parse.");
}
assertEqual(printModel(standaloneMarkupModel), "Saved {#icon name=|check| @label=|Done|/}.");
const escapedTextPatternSource = "Use \\{ opening, \\} closing, and \\\\ slash with {$name}.";
const escapedTextPatternModel = parseMf2(escapedTextPatternSource, { name: "Mina" }, "en").model;
if (!escapedTextPatternModel || escapedTextPatternModel.type !== "message") {
  throw new Error("Expected escaped text pattern source to parse.");
}
assertEqual(printModel(escapedTextPatternModel), escapedTextPatternSource);
const escapedPlaceholderLiteralSource = "Literal \\{$name\\} and {$real}.";
const escapedPlaceholderLiteralModel = parseMf2(escapedPlaceholderLiteralSource, { real: "R" }, "en").model;
if (!escapedPlaceholderLiteralModel || escapedPlaceholderLiteralModel.type !== "message") {
  throw new Error("Expected escaped placeholder-looking literal source to parse.");
}
assertEqual(printModel(escapedPlaceholderLiteralModel), escapedPlaceholderLiteralSource);
assertDeepEqual(placeholderNamesInPattern(escapedPlaceholderLiteralModel.pattern), ["real"]);
const annotatedExpressionModel = parseMf2("Due {$due_date :string @title=|Date|}.", { due_date: "Friday" }, "en").model;
if (!annotatedExpressionModel || annotatedExpressionModel.type !== "message") {
  throw new Error("Expected annotated expression source to parse.");
}
assertEqual(printModel(annotatedExpressionModel), "Due {$due_date :string @title=|Date|}.");
const optionExpressionModel = parseMf2(
  "Price {$price :number minimumFractionDigits=|2| @title=|Price|}.",
  { price: 12 },
  "en",
).model;
if (!optionExpressionModel || optionExpressionModel.type !== "message") {
  throw new Error("Expected option expression source to parse.");
}
assertEqual(printModel(optionExpressionModel), "Price {$price :number minimumFractionDigits=|2| @title=|Price|}.");
const escapedLiteralOptionSource = "Tap {#link title=|A \\| B \\\\ C \\{D\\}|/}.";
const escapedLiteralOptionModel = parseMf2(escapedLiteralOptionSource, {}, "en").model;
if (!escapedLiteralOptionModel || escapedLiteralOptionModel.type !== "message") {
  throw new Error("Expected escaped literal option source to parse.");
}
assertEqual(printModel(escapedLiteralOptionModel), escapedLiteralOptionSource);
const declarationAttributeSource = `.input {$count :number select=|ordinal| @title=|Count|}
.match $count
one {{Rank {$count}}}
* {{Rank {$count}}}`;
const declarationAttributeModel = parseMf2(declarationAttributeSource, { count: 1 }, "en").model;
if (!declarationAttributeModel || declarationAttributeModel.type !== "select") {
  throw new Error("Expected declaration attribute source to parse.");
}
assertEqual(printModel(declarationAttributeModel), declarationAttributeSource);
const quotedVariantKeySource = `.input {$status :string}
.match $status
|needs review| {{Needs review}}
done {{Done}}
* {{Fallback}}`;
const quotedVariantKeyModel = parseMf2(quotedVariantKeySource, { status: "needs review" }, "en").model;
if (!quotedVariantKeyModel || quotedVariantKeyModel.type !== "select") {
  throw new Error("Expected quoted variant key source to parse.");
}
assertEqual(printModel(quotedVariantKeyModel), quotedVariantKeySource);
const escapedVariantPatternSource = `.input {$status :string}
.match $status
* {{Use \\{ opening, \\} closing, and \\\\ slash with {$status}}}`;
const escapedVariantPatternModel = parseMf2(escapedVariantPatternSource, { status: "open" }, "en").model;
if (!escapedVariantPatternModel || escapedVariantPatternModel.type !== "select") {
  throw new Error("Expected escaped variant pattern source to parse.");
}
assertEqual(printModel(escapedVariantPatternModel), escapedVariantPatternSource);
const localAttributeSource = `.input {$price :number}
.local $priceLabel = {$price :number minimumFractionDigits=|2| @title=|Price|}
{{Price {$priceLabel}}}`;
const localAttributeModel = parseMf2(localAttributeSource, { price: 12 }, "en").model;
if (!localAttributeModel || localAttributeModel.type !== "message") {
  throw new Error("Expected local attribute source to parse.");
}
assertEqual(printModel(localAttributeModel), localAttributeSource);
const markupNameDiagnostics = diagnosticsFor(
  parseMf2(markupSource, {}, "en").model,
  parseMf2("Tap {#strong}profile{/strong}.", {}, "en").model,
  [],
);
assertEqual(markupNameDiagnostics.some((diagnostic) => diagnostic.code === "new-markup"), true);
assertEqual(markupNameDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup"), true);
const targetOnlyMarkupVariableDiagnostics = diagnosticsFor(
  parseMf2("Tap profile.", { url: "/profile" }, "en").model,
  parseMf2("Tap {#link href=$url}profile{/link}.", { url: "/profile" }, "fr").model,
  [],
);
assertEqual(targetOnlyMarkupVariableDiagnostics.some((diagnostic) => diagnostic.code === "new-markup"), true);
assertEqual(targetOnlyMarkupVariableDiagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), false);
const omittedMarkupVariableDiagnostics = diagnosticsFor(
  parseMf2("Tap {#link href=$url}profile{/link}.", { url: "/profile" }, "en").model,
  parseMf2("Tap profile.", { url: "/profile" }, "fr").model,
  [],
);
assertEqual(omittedMarkupVariableDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup"), true);
assertEqual(omittedMarkupVariableDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), false);
assertEqual(
  diagnosticsFor(
    parseMf2("Tap {#link href=|/profile|}profile{/link}.", { url: "/profile" }, "en").model,
    parseMf2("Tap {#link href=$url}profile{/link}.", { url: "/profile" }, "fr").model,
    [],
  ).some((diagnostic) => diagnostic.code === "new-placeholder" && diagnostic.message.includes("{$url}")),
  true,
);
assertEqual(
  diagnosticsFor(
    parseMf2("Tap {#link href=$url}profile{/link}.", { url: "/profile" }, "en").model,
    parseMf2("Tap {#link href=|/profile|}profile{/link}.", { url: "/profile" }, "fr").model,
    [],
  ).some((diagnostic) => diagnostic.code === "missing-source-placeholder" && diagnostic.message.includes("{$url}")),
  true,
);

const markupPropDiagnostics = diagnosticsFor(
  parseMf2(markupSource, {}, "en").model,
  parseMf2("Tap {#link target=|/profile| @label=|Profile|}profile{/link}.", {}, "en").model,
  [],
);
assertEqual(markupPropDiagnostics.some((diagnostic) => diagnostic.code === "new-markup-option"), true);
assertEqual(markupPropDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup-option"), true);
assertEqual(markupPropDiagnostics.some((diagnostic) => diagnostic.code === "new-markup-attribute"), true);
assertEqual(markupPropDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup-attribute"), true);

const markupShapeDiagnostics = diagnosticsFor(
  parseMf2(markupSource, {}, "en").model,
  parseMf2("Tap {#link/}profile.", {}, "en").model,
  [],
);
assertEqual(markupShapeDiagnostics.some((diagnostic) => diagnostic.code === "markup-shape-mismatch"), true);

assertDeepEqual(
  filterPlaceholderNames(["account", "count", "count2", "country", "customer"], "co"),
  ["count", "count2", "country"],
);
assertDeepEqual(
  filterPlaceholderNames(["count", "count2", "country", "customer"], "COUNT2"),
  ["count2"],
);
assertDeepEqual(filterPlaceholderNames(["count", "count2"], "ount"), []);

assertDeepEqual(placeholderNamesInPattern("{ $reviewer } reviewed {$count } file"), ["reviewer", "count"]);
assertDeepEqual(placeholderNamesInPattern("Saved {$user.name :string} as {$+label}"), ["user.name", "+label"]);
assertDeepEqual(
  placeholderNamesInPattern("Unicode {$\u30E6\u30FC\u30B6\u30FC} {$cafe\u0301} {$\u20AC} {$\u{1F600}}"),
  ["\u30E6\u30FC\u30B6\u30FC", "cafe\u0301", "\u20AC", "\u{1F600}"],
);
assertDeepEqual(placeholderNamesInPattern("Bidi {$\u2066\u0627\u0633\u0645\u2069} {$\u200Efoo\u200F}"), [
  "\u0627\u0633\u0645",
  "foo",
]);
assertDeepEqual(placeholderNamesInPattern("Bad {$foo\u200Ebar}"), []);
assertDeepEqual(placeholderNamesInPattern("\\{$reviewer\\} reviewed {$count } file"), ["count"]);
assertDeepEqual(missingPlaceholderNamesForPattern("{$count} of {$count} reviewed by {$reviewer}", ""), [
  "count",
  "count",
  "reviewer",
]);
assertDeepEqual(missingPlaceholderNamesForPattern("{$count} of {$count} reviewed by {$reviewer}", "{$count}"), [
  "count",
  "reviewer",
]);
assertDeepEqual(
  missingPlaceholderNamesForPattern("{$count} of {$count} reviewed by {$reviewer}", "{$count} {$count} {$reviewer}"),
  [],
);
const declarationOnlySourceModelForRestore = parseMf2(`.input {$name :string @title=|Name|}
{{Hello}}`, { name: "Mina" }, "en").model;
if (!declarationOnlySourceModelForRestore) throw new Error("Expected declaration-only source model.");
assertDeepEqual(placeholderInsertionNames(declarationOnlySourceModelForRestore), ["name"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    declarationOnlySourceModelForRestore,
    parseMf2("Bonjour", { name: "Mina" }, "fr").model,
    "Hello",
    "Bonjour",
  ),
  ["name"],
);
assertDeepEqual(placeholderInsertionNamesForActiveSource(declarationOnlySourceModelForRestore, "Hello"), ["name"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    declarationOnlySourceModelForRestore,
    parseMf2("Bonjour {$name}", { name: "Mina" }, "fr").model,
    "Hello",
    "Bonjour {$name}",
  ),
  [],
);
const targetOtherFormDeclarationOnlySourceModelForRestore = parseMf2(`.input {$kind :string}
.input {$name :string @title=|Name|}
.match $kind
a {{A}}
b {{B}}
* {{Fallback}}`, { kind: "a", name: "Mina" }, "en").model;
const targetOtherFormDeclarationOnlyTargetModelForRestore = parseMf2(`.input {$kind :string}
.input {$name :string @title=|Name|}
.match $kind
a {{A}}
b {{B {$name}}}
* {{Fallback}}`, { kind: "a", name: "Mina" }, "fr").model;
if (!targetOtherFormDeclarationOnlySourceModelForRestore || !targetOtherFormDeclarationOnlyTargetModelForRestore) {
  throw new Error("Expected target other-form declaration-only restore models.");
}
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    targetOtherFormDeclarationOnlySourceModelForRestore,
    targetOtherFormDeclarationOnlyTargetModelForRestore,
    "A",
    "A",
  ),
  ["name"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    targetOtherFormDeclarationOnlySourceModelForRestore,
    targetOtherFormDeclarationOnlyTargetModelForRestore,
    "B",
    "B {$name}",
  ),
  [],
);
const selectorOnlySourceModelForRestore = parseMf2(`.input {$rank :number select=|ordinal|}
.match $rank
one {{First}}
* {{Ranked}}`, { rank: 1 }, "en").model;
if (!selectorOnlySourceModelForRestore) throw new Error("Expected selector-only source model.");
assertDeepEqual(placeholderInsertionNamesForActiveSource(selectorOnlySourceModelForRestore, "First"), []);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    selectorOnlySourceModelForRestore,
    parseMf2("Premier", { rank: 1 }, "fr").model,
    "First",
    "Premier",
  ),
  [],
);
const otherFormPlaceholderSourceModelForRestore = parseMf2(`.input {$kind :string}
.match $kind
a {{A {$first}}}
b {{B {$second}}}
* {{Fallback}}`, { first: "one", kind: "a", second: "two" }, "en").model;
if (!otherFormPlaceholderSourceModelForRestore) throw new Error("Expected other-form placeholder source model.");
assertDeepEqual(placeholderInsertionNamesForActiveSource(otherFormPlaceholderSourceModelForRestore, "A {$first}"), ["first"]);
assertDeepEqual(placeholderInsertionNamesForActiveSource(otherFormPlaceholderSourceModelForRestore, "Fallback"), []);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    otherFormPlaceholderSourceModelForRestore,
    parseMf2("A", { first: "one", kind: "a", second: "two" }, "fr").model,
    "A {$first}",
    "A",
  ),
  ["first"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    otherFormPlaceholderSourceModelForRestore,
    parseMf2("Repli", { first: "one", kind: "a", second: "two" }, "fr").model,
    "Fallback",
    "Repli",
  ),
  [],
);
const optionVariableSourceModelForRestore = parseMf2(`.input {$price :number minimumFractionDigits=$digits}
{{Price {$price}}}`, { digits: 2, price: 12 }, "en").model;
if (!optionVariableSourceModelForRestore) throw new Error("Expected option-variable source model.");
assertDeepEqual(placeholderInsertionNamesForActiveSource(optionVariableSourceModelForRestore, "Price {$price}"), ["digits", "price"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    optionVariableSourceModelForRestore,
    parseMf2("Prix {$price}", { digits: 2, price: 12 }, "fr").model,
    "Price {$price}",
    "Prix {$price}",
  ),
  ["digits"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    optionVariableSourceModelForRestore,
    parseMf2("Prix {$price :number minimumFractionDigits=$digits}", { digits: 2, price: 12 }, "fr").model,
    "Price {$price}",
    "Prix {$price :number minimumFractionDigits=$digits}",
  ),
  [],
);
const otherFormOptionVariableSourceModelForRestore = parseMf2(`.input {$kind :string}
.input {$price :number minimumFractionDigits=$digits}
.match $kind
a {{A {$price}}}
* {{Fallback}}`, { digits: 2, kind: "a", price: 12 }, "en").model;
if (!otherFormOptionVariableSourceModelForRestore) throw new Error("Expected other-form option-variable source model.");
assertDeepEqual(placeholderInsertionNamesForActiveSource(otherFormOptionVariableSourceModelForRestore, "A {$price}"), ["digits", "price"]);
assertDeepEqual(placeholderInsertionNamesForActiveSource(otherFormOptionVariableSourceModelForRestore, "Fallback"), []);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    otherFormOptionVariableSourceModelForRestore,
    parseMf2("A {$price}", { digits: 2, kind: "a", price: 12 }, "fr").model,
    "A {$price}",
    "A {$price}",
  ),
  ["digits"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    otherFormOptionVariableSourceModelForRestore,
    parseMf2("Repli", { digits: 2, kind: "a", price: 12 }, "fr").model,
    "Fallback",
    "Repli",
  ),
  [],
);
const markupOptionVariableSourceModelForRestore = parseMf2(
  "Tap {#link href=$url}profile{/link}.",
  { url: "/profile" },
  "en",
).model;
if (!markupOptionVariableSourceModelForRestore) throw new Error("Expected markup option-variable source model.");
assertDeepEqual(
  placeholderInsertionNamesForActiveSource(
    markupOptionVariableSourceModelForRestore,
    "Tap {#link href=$url}profile{/link}.",
  ),
  ["url"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    markupOptionVariableSourceModelForRestore,
    parseMf2("Tap profile.", { url: "/profile" }, "fr").model,
    "Tap {#link href=$url}profile{/link}.",
    "Tap profile.",
  ),
  [],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    markupOptionVariableSourceModelForRestore,
    parseMf2("Tap {#link href=|/profile|}profile{/link}.", { url: "/profile" }, "fr").model,
    "Tap {#link href=$url}profile{/link}.",
    "Tap {#link href=|/profile|}profile{/link}.",
  ),
  ["url"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    markupOptionVariableSourceModelForRestore,
    parseMf2("Tap {#link href=$url}profile{/link}.", { url: "/profile" }, "fr").model,
    "Tap {#link href=$url}profile{/link}.",
    "Tap {#link href=$url}profile{/link}.",
  ),
  [],
);
const localVariableSourceModelForRestore = parseMf2(`.input {$price :number}
.input {$digits :integer}
.local $priceLabel = {$price :number minimumFractionDigits=$digits}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model;
if (!localVariableSourceModelForRestore) throw new Error("Expected local-variable source model.");
assertDeepEqual(placeholderInsertionNames(localVariableSourceModelForRestore), ["priceLabel"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    localVariableSourceModelForRestore,
    parseMf2("Prix", { digits: 2, price: 12 }, "fr").model,
    "Price {$priceLabel}",
    "Prix",
  ),
  ["priceLabel"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    localVariableSourceModelForRestore,
    parseMf2("Prix {$priceLabel}", { digits: 2, price: 12 }, "fr").model,
    "Price {$priceLabel}",
    "Prix {$priceLabel}",
  ),
  [],
);
const localInputOptionSourceModelForRestore = parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price}
{{Price {$priceLabel}}}`, { digits: 2, price: 12 }, "en").model;
if (!localInputOptionSourceModelForRestore) throw new Error("Expected local input-option source model.");
assertDeepEqual(placeholderInsertionNames(localInputOptionSourceModelForRestore), ["priceLabel"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    localInputOptionSourceModelForRestore,
    parseMf2("Prix", { digits: 2, price: 12 }, "fr").model,
    "Price {$priceLabel}",
    "Prix",
  ),
  ["priceLabel"],
);
const localAndDirectOptionSourceModelForRestore = parseMf2(`.input {$price :number minimumFractionDigits=$digits}
.local $priceLabel = {$price :number minimumFractionDigits=$digits}
{{Price {$priceLabel} ({$price :number minimumFractionDigits=$digits})}}`, { digits: 2, price: 12 }, "en").model;
if (!localAndDirectOptionSourceModelForRestore) throw new Error("Expected local/direct option source model.");
assertDeepEqual(placeholderInsertionNames(localAndDirectOptionSourceModelForRestore), ["digits", "price", "priceLabel"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    localAndDirectOptionSourceModelForRestore,
    parseMf2("Prix {$priceLabel} ({$price})", { digits: 2, price: 12 }, "fr").model,
    "Price {$priceLabel} ({$price :number minimumFractionDigits=$digits})",
    "Prix {$priceLabel} ({$price})",
  ),
  ["digits"],
);
const selectorOptionSourceModelForRestore = parseMf2(`.input {$rank :number select=|ordinal|}
.input {$score :number minimumFractionDigits=$rank}
.match $rank
one {{First {$score}}}
* {{Ranked {$score}}}`, { rank: 1, score: 12 }, "en").model;
if (!selectorOptionSourceModelForRestore) throw new Error("Expected selector-option source model.");
assertDeepEqual(placeholderInsertionNames(selectorOptionSourceModelForRestore), ["rank", "score"]);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    selectorOptionSourceModelForRestore,
    parseMf2(`.input {$rank :number select=|ordinal|}
.match $rank
one {{Premier {$score}}}
* {{Classe {$score}}}`, { rank: 1, score: 12 }, "fr").model,
    "First {$score}",
    "Premier {$score}",
  ),
  ["rank"],
);
assertDeepEqual(
  missingPlaceholderNamesForActiveSource(
    selectorOptionSourceModelForRestore,
    parseMf2(`.input {$rank :number select=|ordinal|}
.match $rank
one {{Premier {$score :number minimumFractionDigits=$rank}}}
* {{Classe {$score}}}`, { rank: 1, score: 12 }, "fr").model,
    "First {$score}",
    "Premier {$score :number minimumFractionDigits=$rank}",
  ),
  [],
);
assertEqual(
  patternWithRestoredPlaceholders("Reviewed", ["count", "reviewer"], (name) => (name === "count" ? "{$count :number}" : `{$${name}}`)),
  "Reviewed {$count :number} {$reviewer}",
);
assertEqual(patternWithRestoredPlaceholders("Reviewed ", ["count"], (name) => `{$${name}}`), "Reviewed {$count}");
assertEqual(patternWithRestoredPlaceholders("", ["count"], (name) => `{$${name}}`), "{$count}");
assertEqual(patternWithRestoredPlaceholders("Reviewed", [], (name) => `{$${name}}`), "Reviewed");
assertDeepEqual(placeholderExpressionsInPattern("Due {$due_date :string @title=|Date|}.").map((expression) => ({
  name: expression.name,
  source: expression.source,
})), [{ name: "due_date", source: "{$due_date :string @title=|Date|}" }]);
assertDeepEqual(placeholderExpressionsInPattern("User {$user.name :string @title=|User|}.").map((expression) => ({
  name: expression.name,
  source: expression.source,
})), [{ name: "user.name", source: "{$user.name :string @title=|User|}" }]);
assertDeepEqual(
  placeholderExpressionsInPattern("Price {$price :number minimumFractionDigits=|2| @title=|Price|}.")
    .map((expression) => expression.source),
  ["{$price :number minimumFractionDigits=|2| @title=|Price|}"],
);
assertDeepEqual(
  placeholderExpressionsInPattern("Path {$path :string @title=|Ends with \\\\|}.")
    .map((expression) => expression.source),
  ["{$path :string @title=|Ends with \\\\|}"],
);
assertDeepEqual(
  placeholderExpressionsInPattern("Path {$path :string @title=|A \\| B|}.")
    .map((expression) => expression.source),
  ["{$path :string @title=|A \\| B|}"],
);
assertDeepEqual(placeholderSourceByName(parseMf2("Due {$due_date :string @title=|Date|}.", {}, "en").model), {
  due_date: "{$due_date :string @title=|Date|}",
});
assertDeepEqual(placeholderSourcesInPattern("{$price} / {$price :number minimumFractionDigits=|2|}"), {
  price: "{$price :number minimumFractionDigits=|2|}",
});
assertDeepEqual(placeholderSourceByName(parseMf2("{$price} / {$price :number minimumFractionDigits=|2|}", {}, "en").model), {
  price: "{$price :number minimumFractionDigits=|2|}",
});
assertDeepEqual(placeholderSourcesInPattern("{$name} / {$\u200Ename\u200F}"), {
  name: "{$\u200Ename\u200F}",
});
assertDeepEqual(placeholderSourceByName(parseMf2(`.input {$price :number}
{{Price {$price :number minimumFractionDigits=|2|}.}}`, { price: 12 }, "en").model), {
  price: "{$price :number minimumFractionDigits=|2|}",
});
assertDeepEqual(placeholderSourceByName(parseMf2(`.input {$rank :number select=|ordinal| @title=|Rank|}
.match $rank
one {{First}}
* {{Ranked}}`, { rank: 1 }, "en").model), {
  rank: "{$rank :number select=|ordinal| @title=|Rank|}",
});
const bidiDeclarationModel = parseMf2(`.input {$\u2066\u0627\u0633\u0645\u2069 :string @title=|\u0627\u0633\u0645|}
{{Hello}}`, { "\u0627\u0633\u0645": "Mina" }, "ar").model;
assertDeepEqual(placeholderSourceByName(bidiDeclarationModel), {
  "\u0627\u0633\u0645": "{$\u2066\u0627\u0633\u0645\u2069 :string @title=|\u0627\u0633\u0645|}",
});
assertDeepEqual(sourceInputContractItems(bidiDeclarationModel), [
  ".input {$\u2066\u0627\u0633\u0645\u2069 :string @title=|\u0627\u0633\u0645|}",
]);
const escapedDeclarationLiteralModel = parseMf2(`.input {$path :string @title=|Ends with \\\\|}
{{Path}}`, { path: "/tmp/" }, "en").model;
assertDeepEqual(placeholderSourceByName(escapedDeclarationLiteralModel), {
  path: "{$path :string @title=|Ends with \\\\|}",
});
assertDeepEqual(sourceInputContractItems(escapedDeclarationLiteralModel), [
  ".input {$path :string @title=|Ends with \\\\|}",
]);
const twoInputDeclarationModel = parseMf2(`.input {$first :string @title=|First|}
.input {$second :string @title=|Second|}
{{{$first} {$second}}}`, { first: "A", second: "B" }, "en").model;
if (!twoInputDeclarationModel) throw new Error("Expected two-input declaration model.");
const reversedDeclarationModel = {
  ...twoInputDeclarationModel,
  declarations: [...twoInputDeclarationModel.declarations].reverse(),
};
assertDeepEqual(sourceInputContractItems(reversedDeclarationModel), [
  ".input {$second :string @title=|Second|}",
  ".input {$first :string @title=|First|}",
]);
assertDeepEqual(placeholderSourceByName(parseMf2(`.input {$price :number}
.local $priceLabel = {$price :number minimumFractionDigits=|2| @title=|Price|}
{{Price {$priceLabel}}}`, { price: 12 }, "en").model), {
  priceLabel: "{$priceLabel}",
  price: "{$price :number}",
});
const literalLocalModel = parseMf2(`.local $label = {|Save| :string @title=|CTA|}
{{{$label}}}`, {}, "en").model;
assertEqual(printModel(literalLocalModel), `.local $label = {|Save| :string @title=|CTA|}
{{{$label}}}`);
const optionVariableModel = parseMf2(`.input {$price :number minimumFractionDigits=$digits}
{{Price {$price}}}`, { digits: 2, price: 12 }, "en").model;
assertDeepEqual(placeholderNames(optionVariableModel), ["digits", "price"]);
assertDeepEqual(placeholderInsertionNames(optionVariableModel), ["digits", "price"]);
assertDeepEqual(sourceInputContractItems(optionVariableModel), [
  ".input {$price :number minimumFractionDigits=$digits}",
  "{$digits}",
]);
const markupOptionVariableModel = parseMf2("Tap {#link href=$url}profile{/link}.", { url: "/profile" }, "en").model;
assertDeepEqual(placeholderNames(markupOptionVariableModel), ["url"]);
assertDeepEqual(sourceInputContractItems(markupOptionVariableModel), ["{$url}"]);
const selectorOnlyVariableModel = parseMf2(`.match $status
open {{Open}}
* {{Fallback}}`, { status: "open" }, "en").model;
assertDeepEqual(placeholderNames(selectorOnlyVariableModel), ["status"]);
assertDeepEqual(placeholderInsertionNames(selectorOnlyVariableModel), []);
assertDeepEqual(sourceInputContractItems(selectorOnlyVariableModel), ["{$status}"]);
const renderedSelectorVariableModel = parseMf2(`.match $status
open {{Status {$status}}}
* {{Fallback}}`, { status: "open" }, "en").model;
assertDeepEqual(placeholderInsertionNames(renderedSelectorVariableModel), ["status"]);
assertDeepEqual(placeholderCompletionToken("prefix{co"), { from: 6, query: "co", text: "{co" });
assertDeepEqual(placeholderCompletionToken("prefix{$count2"), { from: 6, query: "count2", text: "{$count2" });
assertDeepEqual(placeholderCompletionToken("prefix{ $count2"), { from: 6, query: "count2", text: "{ $count2" });
assertDeepEqual(placeholderCompletionToken("prefix{  count2"), { from: 6, query: "count2", text: "{  count2" });
assertDeepEqual(placeholderCompletionToken("prefix{$user.na"), { from: 6, query: "user.na", text: "{$user.na" });
assertDeepEqual(placeholderCompletionToken("prefix{$+lab"), { from: 6, query: "+lab", text: "{$+lab" });
assertDeepEqual(placeholderCompletionToken("prefix{$\u30E6\u30FC"), {
  from: 6,
  query: "\u30E6\u30FC",
  text: "{$\u30E6\u30FC",
});
assertDeepEqual(placeholderCompletionToken("prefix{$cafe\u0301"), {
  from: 6,
  query: "cafe\u0301",
  text: "{$cafe\u0301",
});
assertDeepEqual(placeholderCompletionToken("prefix{$\u20AC"), { from: 6, query: "\u20AC", text: "{$\u20AC" });
assertDeepEqual(placeholderCompletionToken("prefix{$\u{1F600}"), {
  from: 6,
  query: "\u{1F600}",
  text: "{$\u{1F600}",
});
assertDeepEqual(placeholderCompletionToken("prefix{$\u2066\u0627\u0633"), {
  from: 6,
  query: "\u0627\u0633",
  text: "{$\u2066\u0627\u0633",
});
assertDeepEqual(placeholderCompletionToken("prefix{$\u200Efoo\u200F"), {
  from: 6,
  query: "foo",
  text: "{$\u200Efoo\u200F",
});
const longPlaceholderName = `placeholder_${"x".repeat(96)}`;
const longPlaceholderSource = `Intro {$${longPlaceholderName}`;
assertDeepEqual(placeholderCompletionContext(longPlaceholderSource, longPlaceholderSource.length), {
  from: 6,
  query: longPlaceholderName,
});
assertDeepEqual(placeholderCompletionToken("total $\u200Efo"), {
  from: 6,
  query: "fo",
  text: "$\u200Efo",
});
assertEqual(placeholderCompletionToken("prefix${co"), null);
assertEqual(placeholderCompletionToken("prefix{{co"), null);
assertDeepEqual(placeholderCompletionToken("prefix{{{co"), { from: 8, query: "co", text: "{co" });
assertDeepEqual(placeholderCompletionToken("total $cou"), { from: 6, query: "cou", text: "$cou" });
assertEqual(placeholderCompletionToken("prefix\\{co"), null);
assertEqual(placeholderCompletionToken("prefix\\{ $co"), null);
assertEqual(placeholderCompletionToken("total \\$cou"), null);
assertDeepEqual(placeholderCompletionToken("prefix\\\\{co"), { from: 8, query: "co", text: "{co" });
assertEqual(placeholderCompletionToken("total {count}"), null);
assertDeepEqual(placeholderCompletionReplacement("prefix{co}", 6, 9, "count"), {
  cursor: 14,
  from: 6,
  insert: "{$count}",
  to: 10,
});
assertDeepEqual(placeholderCompletionReplacement("prefix{$co}", 6, 10, "count"), {
  cursor: 14,
  from: 6,
  insert: "{$count}",
  to: 11,
});
assertDeepEqual(placeholderCompletionReplacement("prefix{ $co}", 6, 11, "count"), {
  cursor: 14,
  from: 6,
  insert: "{$count}",
  to: 12,
});
assertEqual(placeholderCompletionConsumesClosingBrace("{cou", "}"), true);
assertEqual(placeholderCompletionConsumesClosingBrace("{$cou", "}"), true);
assertEqual(placeholderCompletionConsumesClosingBrace("$cou", "}"), true);
assertEqual(placeholderCompletionConsumesClosingBrace("cou", "}"), false);
assertEqual(placeholderCompletionConsumesClosingBrace("$cou", " "), false);
assertDeepEqual(placeholderCompletionReplacement("total $cou suffix", 6, 10, "count"), {
  cursor: 14,
  from: 6,
  insert: "{$count}",
  to: 10,
});
assertDeepEqual(placeholderCompletionReplacement("total $cou} suffix", 6, 10, "count"), {
  cursor: 14,
  from: 6,
  insert: "{$count}",
  to: 11,
});
assertDeepEqual(
  placeholderCompletionReplacement("Due {due}.", 4, 8, "due_date", "{$due_date :string @title=|Date|}"),
  {
    cursor: 37,
    from: 4,
    insert: "{$due_date :string @title=|Date|}",
    to: 9,
  },
);

function assertEqual<T>(actual: T, expected: T) {
  if (actual !== expected) {
    throw new Error(`Expected ${String(expected)}, got ${String(actual)}.`);
  }
}

function assertDeepEqual(actual: unknown, expected: unknown) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`Expected ${expectedJson}, got ${actualJson}.`);
  }
}

function keyEvent(key: string, init: Partial<EditorKeyEvent> = {}): EditorKeyEvent {
  return {
    altKey: false,
    ctrlKey: false,
    key,
    metaKey: false,
    shiftKey: false,
    ...init,
  };
}
