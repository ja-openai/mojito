import assert from "node:assert/strict";

import {
  addPluralTemplate,
  appendScenarioRowsToSelectModel,
  createSourceHistory,
  formatMessage,
  fixedExactVariantPreview,
  parseSource,
  localePluralCoverageRows,
  partsForPattern,
  printModel,
  removeScenarioRowsFromSelectModel,
  samples,
  scenarioOrderDetails,
  scenarioOrderSummary,
  scenarioRowsFromModels,
  withSourceContractDiagnostics,
} from "./editor.js";
import {
  countsToRequirements,
  missingRequiredPlaceholders,
  placeholderCountsFromSource,
  placeholderRequirementLabel,
  sourceVariantPlaceholdersForSignature,
  splitPatternTokens,
  tokenPaletteItems,
} from "./lib-lab-placeholders.js";
import {
  mf2PlaceholderCompletionContext,
  mf2PlaceholderCompletionNames,
  mf2PlaceholderCompletionReplacement,
  mf2PlaceholderNamesFromText,
} from "./mf2-placeholder-completion.js";

const plural = parseSource(samples.plural);
assert.equal(plural.type, "select");
assert.deepEqual(plural.selectors, ["count"]);
assert.equal(formatMessage(plural, { count: 1 }, "en"), "You have 1 file");
assert.equal(formatMessage(plural, { count: 2 }, "en"), "You have 2 files");

const gender = parseSource(samples.gender);
assert.equal(formatMessage(gender, { gender: "female", count: 1 }, "en"), "She reviewed 1 file");
assert.equal(formatMessage(gender, { gender: "unknown", count: 5 }, "en"), "They reviewed 5 files");

const generated = parseSource(addPluralTemplate("Files: {$count}"));
assert.equal(generated.diagnostics.length, 0);
assert.equal(printModel(generated).includes(".match $count"), true);

const sourcePlural = parseSource(samples.plural);
const targetWithNewPlaceholder = parseSource(`.input {$count :number}
.match $count
one {{You have {$count} file from {$name}}}
* {{You have {$count} files from {$name}}}`);
const validatedTarget = withSourceContractDiagnostics(targetWithNewPlaceholder, sourcePlural);
assert.equal(validatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), true);

const targetMissingPlaceholder = parseSource(`.input {$count :number}
.match $count
one {{One file}}
* {{Many files}}`);
const missingValidatedTarget = withSourceContractDiagnostics(targetMissingPlaceholder, sourcePlural);
assert.equal(missingValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), true);
assert.equal(missingValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), true);
assert.equal(missingValidatedTarget.diagnostics.find((diagnostic) => diagnostic.code === "variant-missing-placeholder")?.severity, "warning");

const requiredCountPlaceholder = countsToRequirements(placeholderCountsFromSource("You have {$count} files"));
assert.deepEqual(requiredCountPlaceholder, [{ name: "count", count: 1 }]);
assert.deepEqual(missingRequiredPlaceholders(requiredCountPlaceholder, placeholderCountsFromSource("You have {$count} files")), []);
assert.deepEqual(missingRequiredPlaceholders(requiredCountPlaceholder, placeholderCountsFromSource("You have files")), [{ name: "count", count: 1 }]);
assert.deepEqual(missingRequiredPlaceholders([{ name: "count", count: 2 }], placeholderCountsFromSource("You have {$count} files")), [
  { name: "count", count: 1 },
]);
assert.equal(placeholderRequirementLabel([{ name: "count", count: 2 }, { name: "name", count: 1 }]), "{$count} x2, {$name}");
assert.deepEqual(
  sourceVariantPlaceholdersForSignature([{ signature: "one", requirements: requiredCountPlaceholder }], "one", "fallback {$name}", true),
  requiredCountPlaceholder,
);
assert.deepEqual(
  sourceVariantPlaceholdersForSignature([{ signature: "one", requirements: requiredCountPlaceholder }], "0", "No {$count}", true),
  [],
);
assert.deepEqual(
  sourceVariantPlaceholdersForSignature([], "message", "Inline {$name}", false),
  [{ name: "name", count: 1 }],
);
assert.deepEqual(tokenPaletteItems(["name", "count", "count", "url"], 2), {
  visible: ["count", "name"],
  overflow: 1,
});
assert.deepEqual(mf2PlaceholderCompletionContext("You have {co", "You have {co".length), { from: 9, query: "co" });
assert.deepEqual(mf2PlaceholderCompletionContext("You have {$count", "You have {$count".length), { from: 9, query: "count" });
assert.deepEqual(mf2PlaceholderCompletionContext("You have $co", "You have $co".length), { from: 9, query: "co" });
assert.equal(mf2PlaceholderCompletionContext("You have count", "You have count".length), null);
assert.deepEqual(mf2PlaceholderCompletionNames(["count", "name", "count"], "na"), ["name"]);
assert.deepEqual(mf2PlaceholderNamesFromText(".input {$count :number}\n{{{$name} has {$count} files}}"), ["count", "name"]);
assert.deepEqual(
  mf2PlaceholderCompletionReplacement("You have {count}", 9, "You have {count".length, "count"),
  { from: 9, to: "You have {count}".length, insert: "{$count}", cursor: 17 },
);
assert.deepEqual(
  splitPatternTokens("Tap {#link href=$url}profile{/link}. {$name}"),
  [
    { type: "text", value: "Tap " },
    { type: "markup", name: "link", kind: "open", source: "{#link href=$url}" },
    { type: "text", value: "profile" },
    { type: "markup", name: "link", kind: "close", source: "{/link}" },
    { type: "text", value: ". " },
    { type: "placeholder", name: "name" },
  ],
);

const sourceWithExactPlural = parseSource(`.input {$count :number}
.match $count
0 {{You have no files}}
one {{You have one file}}
* {{You have {$count} files}}`);
assert.equal(formatMessage(sourceWithExactPlural, { count: 0 }, "en"), "You have no files");
assert.equal(formatMessage(sourceWithExactPlural, { count: 1 }, "en"), "You have one file");
const targetWithExactZeroOmission = parseSource(`.input {$count :number}
.match $count
0 {{Aucun fichier}}
* {{Vous avez {$count} fichiers}}`);
const exactZeroOmissionValidatedTarget = withSourceContractDiagnostics(targetWithExactZeroOmission, sourceWithExactPlural);
assert.equal(
  exactZeroOmissionValidatedTarget.diagnostics.some((diagnostic) =>
    diagnostic.code === "variant-missing-placeholder" && diagnostic.message.includes("count: 0"),
  ),
  false,
);
const targetFallbackOnly = parseSource(`.input {$count :number}
.match $count
* {{Vous avez {$count} fichiers}}`);
const scenarioRows = scenarioRowsFromModels(sourceWithExactPlural, targetFallbackOnly, { count: 0 }, "en");
const exactZeroScenario = scenarioRows.find((row) => row.label === "When count: 0");
const fallbackScenario = scenarioRows.find((row) => row.label === "When count: fallback");
assert.deepEqual(exactZeroScenario.origins, ["source"]);
assert.equal(exactZeroScenario.rowKind, "fixed");
assert.deepEqual(exactZeroScenario.keys, ["0"]);
assert.deepEqual(fallbackScenario.origins, ["source", "target"]);
assert.equal(fallbackScenario.arguments.count, 2);
const missingVariantValidatedTarget = withSourceContractDiagnostics(targetFallbackOnly, sourceWithExactPlural);
assert.equal(missingVariantValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-variant"), true);

const fixedAfterFallback = parseSource(`.input {$count :integer}
.match $count
* {{fallback}}
1 {{exact one}}
one {{plural one}}`);
assert.equal(formatMessage(fixedAfterFallback, { count: 1.2 }, "en"), "exact one");
assert.equal(formatMessage(fixedAfterFallback, { count: 2 }, "en"), "fallback");
const fixedAfterFallbackValidatedTarget = withSourceContractDiagnostics(fixedAfterFallback, fixedAfterFallback, { locale: "en" });
assert.equal(fixedAfterFallbackValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "overlapping-numeric-variant"), true);
const fixedOverlapScenarioRows = scenarioRowsFromModels(fixedAfterFallback, fixedAfterFallback, { count: 0 }, "en");
assert.equal(
  fixedOverlapScenarioRows.some((row) => (row.overlapNotes ?? []).some((note) => note.includes("appears first and wins"))),
  true,
);
assert.deepEqual(scenarioOrderSummary(fixedOverlapScenarioRows), {
  rowCount: 1,
  noteCount: 2,
  rowOrderRowCount: 1,
  rowOrderNoteCount: 2,
  selectorPriorityRowCount: 0,
  selectorPriorityNoteCount: 0,
});
assert.equal(scenarioOrderDetails(fixedOverlapScenarioRows).notes.some((note) => note.includes("count: 1") && note.includes("wins")), true);

const targetWithExtraVariant = parseSource(`.input {$count :number}
.match $count
0 {{No files}}
one {{One file}}
* {{Many files}}`);
const extraVariantValidatedTarget = withSourceContractDiagnostics(targetWithExtraVariant, sourcePlural);
assert.equal(extraVariantValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), true);
const targetOnlyScenarioRows = scenarioRowsFromModels(sourcePlural, targetWithExtraVariant, { count: 0 }, "en")
  .filter((row) => row.origins.includes("target") && !row.origins.includes("source"));
assert.deepEqual(removeScenarioRowsFromSelectModel(targetWithExtraVariant, targetOnlyScenarioRows), [["0"]]);
assert.equal(targetWithExtraVariant.variants.some((variant) => variant.keys.includes("0")), false);
const fallbackOnlyForRemoval = parseSource(`.input {$count :number}
.match $count
* {{Files}}`);
assert.deepEqual(removeScenarioRowsFromSelectModel(fallbackOnlyForRemoval, [{ keys: ["*"] }]), []);

const sourceFallbackOnlyPlural = parseSource(`.input {$count :number}
.match $count
* {{Files}}`);
const targetArabicRecommendedPlural = parseSource(`.input {$count :number}
.match $count
two {{ملفان}}
* {{ملفات}}`);
const arabicRecommendedValidatedTarget = withSourceContractDiagnostics(targetArabicRecommendedPlural, sourceFallbackOnlyPlural, { locale: "ar" });
assert.equal(arabicRecommendedValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
assert.equal(arabicRecommendedValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"), true);
assert.equal(
  arabicRecommendedValidatedTarget.diagnostics.some((diagnostic) =>
    diagnostic.message.includes("Target locale ar has CLDR cardinal category few"),
  ),
  true,
);
const arabicScenarioRows = scenarioRowsFromModels(sourceFallbackOnlyPlural, targetArabicRecommendedPlural, { count: 0 }, "ar");
assert.deepEqual(arabicScenarioRows.find((row) => row.label === "When count: two")?.origins, ["target"]);
assert.deepEqual(arabicScenarioRows.find((row) => row.label === "When count: few")?.origins, ["locale"]);
assert.equal(arabicScenarioRows.find((row) => row.label === "When count: few")?.rowKind, "target-locale");
assert.equal(arabicScenarioRows.find((row) => row.label === "When count: few")?.arguments.count, 3);
const targetArabicExactZeroPlural = parseSource(`.input {$count :number}
.match $count
0 {{لا ملفات}}
* {{ملفات}}`);
const arabicExactZeroValidatedTarget = withSourceContractDiagnostics(targetArabicExactZeroPlural, sourceFallbackOnlyPlural, { locale: "ar" });
assert.equal(
  arabicExactZeroValidatedTarget.diagnostics.some((diagnostic) => diagnostic.message.includes("category zero")),
  false,
);
assert.equal(
  arabicExactZeroValidatedTarget.diagnostics.some((diagnostic) => diagnostic.message.includes("category few")),
  true,
);
assert.equal(scenarioRowsFromModels(sourceFallbackOnlyPlural, targetArabicExactZeroPlural, { count: 0 }, "ar")
  .some((row) => row.label === "When count: zero" && row.origins.includes("locale")), false);
const arabicExactZeroCoverage = localePluralCoverageRows(targetArabicExactZeroPlural, "ar")[0];
assert.equal(arabicExactZeroCoverage.categories.find((category) => category.category === "zero")?.state, "exact");
assert.equal(arabicExactZeroCoverage.categories.find((category) => category.category === "zero")?.sample, 0);
const targetArabicFallbackOnly = parseSource(`.input {$count :number}
.match $count
* {{ملفات}}`);
const arabicLocaleRowsToAdd = scenarioRowsFromModels(sourceFallbackOnlyPlural, targetArabicFallbackOnly, { count: 0 }, "ar")
  .filter((row) => row.origins.includes("locale"));
const addedArabicLocaleRows = appendScenarioRowsToSelectModel(targetArabicFallbackOnly, arabicLocaleRowsToAdd);
assert.equal(addedArabicLocaleRows.some((keys) => keys.includes("few")), true);
assert.equal(
  targetArabicFallbackOnly.variants.findIndex((variant) => variant.keys.includes("few"))
    < targetArabicFallbackOnly.variants.findIndex((variant) => variant.keys.includes("*")),
  true,
);
assert.deepEqual(appendScenarioRowsToSelectModel(targetArabicFallbackOnly, arabicLocaleRowsToAdd), []);
const sourceOrdinalFallback = parseSource(`.input {$rank :number select=ordinal}
.match $rank
* {{rank {$rank}}}`);
const targetOrdinalExactOne = parseSource(`.input {$rank :number select=ordinal}
.match $rank
1 {{first}}
* {{rank {$rank}}}`);
const ordinalScenarioRows = scenarioRowsFromModels(sourceOrdinalFallback, targetOrdinalExactOne, { rank: 1 }, "en");
const ordinalOneRow = ordinalScenarioRows.find((row) => row.label === "When rank: one");
assert.deepEqual(ordinalOneRow?.origins, ["locale"]);
assert.equal(ordinalOneRow?.arguments.rank, 21);
const ordinalValidatedTarget = withSourceContractDiagnostics(targetOrdinalExactOne, sourceOrdinalFallback, { locale: "en" });
assert.equal(ordinalValidatedTarget.diagnostics.some((diagnostic) => diagnostic.message.includes("CLDR ordinal category one")), true);
const englishUnrecommendedValidatedTarget = withSourceContractDiagnostics(targetArabicRecommendedPlural, sourceFallbackOnlyPlural, { locale: "en" });
assert.equal(englishUnrecommendedValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), true);
assert.equal(
  englishUnrecommendedValidatedTarget.diagnostics.find((diagnostic) => diagnostic.code === "target-only-variant")?.message.includes("not a cardinal category for en"),
  true,
);
assert.equal(englishUnrecommendedValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"), true);

const sourceMultiSelector = parseSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He has one file}}
female one {{She has one file}}
* * {{They have {$count} files}}`);
const fixedPreview = fixedExactVariantPreview(sourceMultiSelector, "0");
assert.deepEqual(fixedPreview.keys, ["*", "0"]);
assert.equal(fixedPreview.label, "Will add: When gender: fallback, count: 0. Other selectors use fallback.");
assert.deepEqual(fixedPreview.suggestions.map((suggestion) => suggestion.keys), [["male", "0"], ["female", "0"]]);
const targetMultiSelectorFallback = parseSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
* * {{Ils ont {$count} fichiers}}`);
const arabicMultiScenarioRows = scenarioRowsFromModels(sourceMultiSelector, targetMultiSelectorFallback, { gender: "unknown", count: 0 }, "ar");
assert.deepEqual(
  arabicMultiScenarioRows.find((row) => row.label === "When gender: fallback, count: few")?.localePlural?.suggestions.map((item) => item.keys),
  [["male", "few"], ["female", "few"]],
);
assert.deepEqual(
  arabicMultiScenarioRows.find((row) => row.label === "When gender: fallback, count: few")?.localePlural?.suggestions.map((item) => item.label),
  ["gender: male, count: few", "gender: female, count: few"],
);
const targetMultiSelectorForSpecificInsert = parseSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
* * {{Ils ont {$count} fichiers}}`);
const addedSpecificLocaleRows = appendScenarioRowsToSelectModel(
  targetMultiSelectorForSpecificInsert,
  arabicMultiScenarioRows.find((row) => row.label === "When gender: fallback, count: few")?.localePlural?.suggestions ?? [],
);
assert.deepEqual(addedSpecificLocaleRows, [["male", "few"], ["female", "few"]]);
assert.equal(
  targetMultiSelectorForSpecificInsert.variants.findIndex((variant) => variant.keys.join(" ") === "male few")
    < targetMultiSelectorForSpecificInsert.variants.findIndex((variant) => variant.keys.join(" ") === "* *"),
  true,
);
const targetMultiSelectorArabicSpecific = parseSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
male few {{Il a quelques fichiers}}
* * {{Ils ont {$count} fichiers}}`);
const arabicSpecificValidatedTarget = withSourceContractDiagnostics(targetMultiSelectorArabicSpecific, sourceMultiSelector, { locale: "ar" });
assert.equal(arabicSpecificValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
const targetMultiSelectorUnsupportedContext = parseSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
robot few {{Robot a quelques fichiers}}
* * {{Ils ont {$count} fichiers}}`);
const unsupportedContextValidatedTarget = withSourceContractDiagnostics(targetMultiSelectorUnsupportedContext, sourceMultiSelector, { locale: "ar" });
assert.equal(unsupportedContextValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), true);

const sourceOffsetLikes = parseSource(samples.offsetLikes);
assert.equal(sourceOffsetLikes.diagnostics.length, 0);
assert.equal(printModel(sourceOffsetLikes).includes(".local $others_count = {$like_count :offset subtract=1}"), true);
const offsetScenarioRows = scenarioRowsFromModels(sourceOffsetLikes, sourceOffsetLikes, { like_count: 0, name: "Alex" }, "en");
assert.equal(offsetScenarioRows.some((row) => row.label === "When like_count: one, others_count: fallback"), false);
assert.equal(offsetScenarioRows.find((row) => row.label === "When like_count: fallback, others_count: 1")?.arguments.like_count, 2);
assert.equal(offsetScenarioRows.find((row) => row.label === "When like_count: fallback, others_count: 1")?.arguments.others_count, 1);
assert.equal(offsetScenarioRows.find((row) => row.label === "When like_count: fallback, others_count: fallback")?.arguments.like_count, 3);
assert.equal(offsetScenarioRows.find((row) => row.label === "When like_count: fallback, others_count: fallback")?.arguments.others_count, 2);
const sourceOffsetPlural = parseSource(`.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* one {{{$name} and {$others_count} other user liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`);
const targetOffsetFallbackOnly = parseSource(`.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`);
const offsetPluralRows = scenarioRowsFromModels(sourceOffsetPlural, targetOffsetFallbackOnly, { like_count: 3, name: "Alex" }, "en");
const offsetPluralOneRow = offsetPluralRows.find((row) => row.label === "When like_count: fallback, others_count: one");
assert.deepEqual(offsetPluralOneRow?.origins, ["source"]);
assert.equal(offsetPluralOneRow?.rowKind, "category");
assert.equal(offsetPluralOneRow?.arguments.like_count, 2);
assert.equal(offsetPluralOneRow?.arguments.others_count, 1);
assert.equal(offsetPluralOneRow?.details.find((detail) => detail.selector === "others_count")?.kind, "category");
const sourceVariableOffset = parseSource(samples.offsetVariable);
const variableOffsetRows = scenarioRowsFromModels(sourceVariableOffset, sourceVariableOffset, { like_count: 5, hidden_count: 2, name: "Alex" }, "en");
const variableOffsetOneRow = variableOffsetRows.find((row) => row.label === "When like_count: fallback, visible_count: one");
assert.equal(printModel(sourceVariableOffset).includes("subtract=$hidden_count"), true);
assert.equal(variableOffsetOneRow?.arguments.like_count, 3);
assert.equal(variableOffsetOneRow?.arguments.hidden_count, 2);
assert.equal(variableOffsetOneRow?.arguments.visible_count, 1);
assert.equal(formatMessage(sourceVariableOffset, variableOffsetOneRow?.arguments ?? {}, "en"), "Alex and 1 other visible user liked your post.");
const targetSelectorChange = parseSource(`.input {$gender :string}
.input {$total :number}
.match $gender $total
male one {{Il a un fichier}}
* * {{Ils ont {$total} fichiers}}`);
const selectorChangeValidatedTarget = withSourceContractDiagnostics(targetSelectorChange, sourceMultiSelector);
assert.equal(selectorChangeValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-selector"), true);
assert.equal(selectorChangeValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-selector"), true);

const targetReorderedSelectors = parseSource(`.input {$gender :string}
.input {$count :number}
.match $count $gender
one male {{Il a un fichier}}
* * {{Ils ont {$count} fichiers}}`);
const reorderedSelectorValidatedTarget = withSourceContractDiagnostics(targetReorderedSelectors, sourceMultiSelector);
assert.equal(reorderedSelectorValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "selector-order-mismatch"), true);

const sourceVariantRows = parseSource(`.input {$kind :string}
.match $kind
error {{The operation failed: {$kind}}}
* {{The operation completed: {$kind}}}`);
const targetReorderedVariantRows = parseSource(`.input {$kind :string}
.match $kind
* {{Operation terminee: {$kind}}}
error {{Operation echouee: {$kind}}}`);
const reorderedVariantRowsValidatedTarget = withSourceContractDiagnostics(targetReorderedVariantRows, sourceVariantRows);
assert.equal(reorderedVariantRowsValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-variant"), false);
assert.equal(reorderedVariantRowsValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
const reorderedVariantScenarioRows = scenarioRowsFromModels(sourceVariantRows, targetReorderedVariantRows, { kind: "ok" }, "en");
assert.equal(reorderedVariantScenarioRows.length, 2);
assert.equal(reorderedVariantScenarioRows.every((row) => row.origins.includes("source") && row.origins.includes("target")), true);

const selectorPriorityRows = parseSource(`.input {$first :string}
.input {$second :string}
.match $first $second
* * {{fallback}}
* exact {{second exact}}
exact * {{first exact}}`);
const selectorPriorityValidated = withSourceContractDiagnostics(selectorPriorityRows, selectorPriorityRows);
assert.equal(selectorPriorityValidated.diagnostics.some((diagnostic) => diagnostic.code === "selector-priority-overlap"), true);
const selectorPriorityScenarioRows = scenarioRowsFromModels(selectorPriorityRows, selectorPriorityRows, { first: "other", second: "other" }, "en");
assert.equal(
  selectorPriorityScenarioRows.some((row) => row.overlapNotes.some((note) => note.includes("$first") && note.includes("wins"))),
  true,
);

const sourceWildcardMiddle = parseSource(`.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare * singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
* * * {{Schild}}`);
assert.equal(formatMessage(sourceWildcardMiddle, { usage: "bare", case: "dative", number: "singular" }, "de"), "Schild");
assert.equal(formatMessage(sourceWildcardMiddle, { usage: "bare", case: "genitive", number: "plural" }, "de"), "Schilde");
const targetWildcardMiddleRefinement = parseSource(`.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare dative singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
* * * {{Schild}}`);
const wildcardMiddleValidated = withSourceContractDiagnostics(targetWildcardMiddleRefinement, sourceWildcardMiddle);
assert.equal(wildcardMiddleValidated.diagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
assert.equal(
  scenarioOrderDetails(selectorPriorityScenarioRows).notes.some((note) => note.includes("$first") && note.includes("wins")),
  true,
);
const selectorPriorityDetails = scenarioOrderDetails(selectorPriorityScenarioRows);
assert.equal(selectorPriorityDetails.selectorPriorityRowCount > 0, true);
assert.equal(selectorPriorityDetails.rowOrderRowCount, 0);

const targetChangedSelectorAnnotation = parseSource(`.input {$gender :string}
.input {$count :string}
.match $gender $count
male one {{Il a un fichier}}
* * {{Ils ont {$count} fichiers}}`);
const annotationValidatedTarget = withSourceContractDiagnostics(targetChangedSelectorAnnotation, sourceMultiSelector);
assert.equal(annotationValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "selector-annotation-mismatch"), true);

const sourceMarkupOptionModel = {
  type: "message",
  diagnostics: [],
  rustModel: {
    type: "message",
    pattern: [{ type: "markup", kind: "open", name: "link", options: { href: { type: "variable", name: "url" } } }, "profile"],
  },
};
const targetMarkupOptionModel = {
  type: "message",
  diagnostics: [],
  rustModel: {
    type: "message",
    pattern: [{ type: "markup", kind: "open", name: "link", options: { href: { type: "variable", name: "trackingUrl" } } }, "profile"],
  },
};
const markupOptionValidatedTarget = withSourceContractDiagnostics(targetMarkupOptionModel, sourceMarkupOptionModel);
assert.equal(markupOptionValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), true);
assert.equal(markupOptionValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), true);

const sourceMarkupPropModel = {
  type: "message",
  diagnostics: [],
  rustModel: {
    type: "message",
    pattern: [{
      type: "markup",
      kind: "open",
      name: "link",
      options: { href: { type: "variable", name: "url" } },
      attributes: { title: { type: "literal", value: "Profile" } },
    }, "profile", { type: "markup", kind: "close", name: "link" }],
  },
};
const targetMarkupPropModel = {
  type: "message",
  diagnostics: [],
  rustModel: {
    type: "message",
    pattern: [{
      type: "markup",
      kind: "open",
      name: "link",
      options: { target: { type: "variable", name: "url" } },
      attributes: { label: { type: "literal", value: "Profile" } },
    }, "profile", { type: "markup", kind: "close", name: "link" }],
  },
};
const markupPropValidatedTarget = withSourceContractDiagnostics(targetMarkupPropModel, sourceMarkupPropModel);
assert.equal(markupPropValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-markup-option"), true);
assert.equal(markupPropValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup-option"), true);
assert.equal(markupPropValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-markup-attribute"), true);
assert.equal(markupPropValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup-attribute"), true);

const targetWithNewMarkup = parseSource("Tap {#button}profile{/button}. {$name}");
const markupContractTarget = withSourceContractDiagnostics(targetWithNewMarkup, parseSource(samples.markup));
assert.equal(markupContractTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-markup"), true);
assert.equal(markupContractTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup"), true);

const targetWithChangedMarkupShape = parseSource("Tap {#link/}. {$name}");
const markupShapeTarget = withSourceContractDiagnostics(targetWithChangedMarkupShape, parseSource(samples.markup));
assert.equal(markupShapeTarget.diagnostics.some((diagnostic) => diagnostic.code === "markup-shape-mismatch"), true);

const parts = partsForPattern("Tap {#link href=$url}profile{/link}. {$name}", {
  name: "Jean",
  url: "/people/jean",
});
assert.deepEqual(parts.map((part) => part.type), ["text", "markup", "text", "markup", "text", "expression"]);

const broken = parseSource(".input {$count :number}\n.match $count\none {{One");
assert.equal(broken.diagnostics.some((diagnostic) => diagnostic.code === "unclosed-placeholder"), true);

const history = createSourceHistory("one");
history.push("two");
history.push("three");
assert.equal(history.undo(), "two");
assert.equal(history.undo(), "one");
assert.equal(history.redo(), "two");
history.push("four");
assert.equal(history.redo(), "four");

console.log("MF2 editor prototype smoke test passed");
