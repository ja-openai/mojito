import assert from "node:assert/strict";

import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

import {
  BidiIsolate,
  FormattedMessage,
  FormattedMessageBlock,
  FunctionRegistry,
  MessageParts,
  MessageProvider,
  SourceTargetDiagnostics,
  SourceTargetScenarioMatrix,
  TargetInsertionPanel,
  VariantRowPanel,
  compareMessageContracts,
  compileMessageCatalog,
  createMessageCatalog,
  insertVariantRowSource,
  isolateText,
  localePluralCoverageFromModels,
  messageContractFromModel,
  removeVariantRowSource,
  scenarioOrderSummary,
  sourceTargetScenarioRowsFromModels,
  targetInsertionActionsFromContract,
  targetLocaleVariantRowActionsFromModels,
  targetSpecificVariantRowActionsFromModels,
  variantRowActionsFromModels,
  useMessage,
  useMessageDiagnostics,
  useMessageEntry,
  useMessageFormatter,
  useMessageIds,
  useMessageParts,
} from "./index.js";
import {
  FormattedMessage as RuntimeFormattedMessage,
  MessageProvider as RuntimeMessageProvider,
  createCompiledMessageCatalog,
} from "./runtime.js";

function stripBidiControls(value) {
  return String(value).replace(/[\u2066-\u2069]/gu, "");
}

const catalog = createMessageCatalog({
  cart: `.input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}`,
  profile: "Tap {#link href=$url @title=|Profile page|}profile{/link}. {$name :string @kind=person}",
  standalone: "Line{#br/}Break {#badge label=|Beta| @title=|Preview feature|/}",
  custom: "Custom {$name :upper}",
  broken: `.input {$count :number}
.match $count
one {{bad`,
});

const functions = FunctionRegistry.defaults().withFunction("upper", (call) => call.value.toLocaleUpperCase(call.locale));
const compiledFromSource = compileMessageCatalog({
  compiledSource: "Compiled {$name}",
  brokenSource: `.input {$count :number}
.match $count
one {{bad`,
});
assert.equal(compiledFromSource.compiledSource.model.type, "message");
assert.equal(compiledFromSource.compiledSource.diagnostics.length, 0);
assert.equal(compiledFromSource.brokenSource.model, null);
assert.equal(compiledFromSource.brokenSource.diagnostics[0].code, "unclosed-quoted-pattern");

const sourceContractEntry = compileMessageCatalog({
  source: `.input {$count :number}
.match $count
one {{One {$count} file}}
* {{Many {$count} files}}`,
}).source;
const attributePayloadContract = messageContractFromModel({
  type: "message",
  declarations: [],
  pattern: [{
    type: "expression",
    arg: { type: "variable", name: "name" },
    function: { name: "string", options: {} },
    attributes: { kind: { type: "variable", name: "personKind" } },
  }],
});
assert.deepEqual(attributePayloadContract.placeholders, ["name", "personKind"]);
const targetContractEntry = compileMessageCatalog({
  target: `.input {$count :number}
.input {$name :string}
.match $count $name
* * {{Fichiers pour {$name}}}`,
}).target;
const contractDiagnostics = compareMessageContracts(sourceContractEntry.model, targetContractEntry.model);
assert.equal(contractDiagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), true);
assert.equal(contractDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), true);
assert.equal(contractDiagnostics.some((diagnostic) => diagnostic.code === "new-selector"), true);
assert.equal(contractDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-variant"), true);

const sourceVariantPlaceholderEntry = compileMessageCatalog({
  source: `.input {$count :number}
.match $count
0 {{No files}}
one {{One {$count} file}}
* {{Many {$count} files}}`,
}).source;
const targetVariantPlaceholderEntry = compileMessageCatalog({
  target: `.input {$count :number}
.match $count
0 {{Aucun fichier}}
one {{Un fichier}}
* {{Beaucoup de {$count} fichiers}}`,
}).target;
const variantPlaceholderDiagnostics = compareMessageContracts(
  sourceVariantPlaceholderEntry.model,
  targetVariantPlaceholderEntry.model,
);
assert.equal(variantPlaceholderDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), false);
assert.equal(
  variantPlaceholderDiagnostics.some((diagnostic) =>
    diagnostic.code === "variant-missing-placeholder" && diagnostic.message.includes("one"),
  ),
  true,
);
assert.equal(
  variantPlaceholderDiagnostics.some((diagnostic) =>
    diagnostic.code === "variant-missing-placeholder" && diagnostic.message.includes("0"),
  ),
  false,
);

const sourceFallbackOnlyPlural = compileMessageCatalog({
  source: `.input {$count :number}
.match $count
* {{Files}}`,
}).source;
const targetArabicRecommendedPlural = compileMessageCatalog({
  target: `.input {$count :number}
.match $count
two {{ملفان}}
* {{ملفات}}`,
}).target;
assert.equal(
  compareMessageContracts(sourceFallbackOnlyPlural.model, targetArabicRecommendedPlural.model, { locale: "ar" })
    .some((diagnostic) => diagnostic.code === "target-only-variant"),
  false,
);
assert.equal(
  compareMessageContracts(sourceFallbackOnlyPlural.model, targetArabicRecommendedPlural.model, { locale: "ar" })
    .some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"),
  true,
);
assert.equal(
  compareMessageContracts(sourceFallbackOnlyPlural.model, targetArabicRecommendedPlural.model, { locale: "en" })
    .some((diagnostic) => diagnostic.code === "target-only-variant"),
  true,
);
assert.equal(
  compareMessageContracts(sourceFallbackOnlyPlural.model, targetArabicRecommendedPlural.model, { locale: "en" })
    .some((diagnostic) => diagnostic.code === "missing-locale-plural-variant"),
  true,
);

const actionContractEntry = compileMessageCatalog({
  source: "Tap {#link href=$url @title=|Profile page|}profile{/link}. {$name}",
}).source;
const insertionActions = targetInsertionActionsFromContract(messageContractFromModel(actionContractEntry.model));
assert.equal(insertionActions.some((action) => action.snippet === "{$name}"), true);
assert.equal(
  insertionActions.some((action) => action.snippet === "{#link href=$url @title=|Profile page|}text{/link}"),
  true,
);
const manyInsertionActionsEntry = compileMessageCatalog({
  source: "{$alpha} {$beta} {$gamma} {$delta}",
}).source;
const manyInsertionActions = targetInsertionActionsFromContract(messageContractFromModel(manyInsertionActionsEntry.model));
assert.equal(manyInsertionActions.length, 4);
const actionTargetEntry = compileMessageCatalog({ target: "Tap profile." }).target;
const partialActionTargetEntry = compileMessageCatalog({ target: "Tap {$name}." }).target;
const standaloneLinkTargetEntry = compileMessageCatalog({ target: "Tap {#link href=$url/}. {$name}" }).target;
const fixDiagnostics = compareMessageContracts(actionContractEntry.model, actionTargetEntry.model);
assert.equal(
  fixDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder" && diagnostic.fix?.snippet === "{$name}"),
  true,
);
const insertionStatusActions = targetInsertionActionsFromContract(messageContractFromModel(actionContractEntry.model), {
  targetContract: messageContractFromModel(partialActionTargetEntry.model),
});
assert.equal(insertionStatusActions.find((action) => action.label === "$name")?.targetStatus, "present");
assert.equal(insertionStatusActions.find((action) => action.label === "#link")?.targetStatus, "missing");
const markupKindStatusActions = targetInsertionActionsFromContract(messageContractFromModel(actionContractEntry.model), {
  targetContract: messageContractFromModel(standaloneLinkTargetEntry.model),
});
assert.equal(markupKindStatusActions.find((action) => action.label === "$name")?.targetStatus, "present");
assert.equal(markupKindStatusActions.find((action) => action.label === "$url")?.targetStatus, "present");
assert.equal(markupKindStatusActions.find((action) => action.label === "#link")?.targetStatus, "missing");
const markupShapeDiagnostics = compareMessageContracts(actionContractEntry.model, standaloneLinkTargetEntry.model);
const markupShapeDiagnostic = markupShapeDiagnostics.find((diagnostic) => diagnostic.code === "markup-shape-mismatch");
assert.equal(markupShapeDiagnostic?.fix?.snippet, "{#link href=$url @title=|Profile page|}text{/link}");

const variantSourceEntry = compileMessageCatalog({
  source: `.input {$count :number}
.match $count
one {{One {$count} file}}
* {{Many {$count} files}}`,
}).source;
const variantTargetEntry = compileMessageCatalog({
  target: `.input {$count :number}
.match $count
* {{Beaucoup de fichiers}}`,
}).target;
const rowActions = variantRowActionsFromModels(variantSourceEntry.model, variantTargetEntry.model, { values: { count: 5 } });
assert.equal(rowActions.length, 1);
assert.equal(rowActions[0].snippet, "one {{TODO {$count}}}");
assert.equal(rowActions[0].label, "Add CLDR row one");
assert.equal(rowActions[0].rowKind, "category");
assert.equal(rowActions[0].keyDetails[0].label, "$count: CLDR category one (sample 1)");
assert.deepEqual(rowActions[0].placeholders, ["count"]);
assert.deepEqual(rowActions[0].sampleValues, { count: 1 });
assert.equal(rowActions[0].fallbackPreview.value, "Beaucoup de fichiers");
const fixedVariantSourceEntry = compileMessageCatalog({
  source: `.input {$count :number}
.match $count
0 {{No files}}
* {{Files}}`,
}).source;
const fixedRowActions = variantRowActionsFromModels(fixedVariantSourceEntry.model, variantTargetEntry.model, { values: { count: 5 } });
assert.equal(fixedRowActions[0].label, "Add fixed row 0");
assert.equal(fixedRowActions[0].rowKind, "fixed");
assert.equal(fixedRowActions[0].keyDetails[0].label, "$count: fixed value 0");
assert.deepEqual(fixedRowActions[0].sampleValues, { count: 0 });
assert.equal(
  insertVariantRowSource(`.input {$count :number}
.match $count
* {{Files}}`, "one {{One file}}", 1),
  `.input {$count :number}
.match $count
one {{One file}}
* {{Files}}`,
);
assert.equal(
  insertVariantRowSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
* * {{Files}}`, "female one {{She has one file}}", 2),
  `.input {$gender :string}
.input {$count :number}
.match $gender $count
female one {{She has one file}}
* * {{Files}}`,
);
const fixedScenarioRows = sourceTargetScenarioRowsFromModels(fixedVariantSourceEntry.model, variantTargetEntry.model, { values: { count: 5 }, locale: "en" });
assert.equal(fixedScenarioRows.find((scenario) => scenario.signature === "0")?.rowKind, "fixed");
const scenarioRows = sourceTargetScenarioRowsFromModels(variantSourceEntry.model, variantTargetEntry.model, { values: { count: 5 }, locale: "en" });
assert.equal(scenarioRows.length, 2);
const missingOneScenario = scenarioRows.find((scenario) => scenario.signature === "one");
assert.equal(missingOneScenario.status, "source-only");
assert.equal(missingOneScenario.rowKind, "category");
assert.deepEqual(missingOneScenario.values, { count: 1 });
assert.equal(missingOneScenario.target.value, "Beaucoup de fichiers");
const arabicTargetPluralEntry = compileMessageCatalog({
  target: `.input {$count :number}
.match $count
two {{ملفان}}
* {{ملفات}}`,
}).target;
const arabicScenarioRows = sourceTargetScenarioRowsFromModels(
  variantTargetEntry.model,
  arabicTargetPluralEntry.model,
  { values: { count: 5 }, locale: "ar" },
);
assert.equal(arabicScenarioRows.find((scenario) => scenario.signature === "two")?.status, "locale-recommended");
assert.equal(arabicScenarioRows.find((scenario) => scenario.signature === "few")?.status, "locale-missing");
assert.equal(arabicScenarioRows.find((scenario) => scenario.signature === "few")?.rowKind, "target-locale");
assert.deepEqual(arabicScenarioRows.find((scenario) => scenario.signature === "few")?.values, { count: 3 });
const arabicTargetLocaleActions = targetLocaleVariantRowActionsFromModels(
  variantTargetEntry.model,
  arabicTargetPluralEntry.model,
  { values: { count: 5 }, locale: "ar" },
);
const arabicFewAction = arabicTargetLocaleActions.find((action) => action.subject === "target-locale:few");
assert.equal(arabicFewAction?.label, "Add target-locale CLDR row few");
assert.equal(arabicFewAction?.rowKind, "target-locale");
assert.equal(arabicFewAction?.snippet, "few {{TODO}}");
assert.deepEqual(arabicFewAction?.sampleValues, { count: 3 });
const arabicScenarioHtml = renderToStaticMarkup(
  React.createElement(SourceTargetScenarioMatrix, {
    sourceModel: variantTargetEntry.model,
    targetModel: arabicTargetPluralEntry.model,
    locale: "ar",
    values: { count: 5 },
  }),
);
assert.match(arabicScenarioHtml, /target-locale CLDR row present/u);
assert.match(arabicScenarioHtml, /target-locale CLDR row missing/u);
const arabicPluralCoverage = localePluralCoverageFromModels(variantTargetEntry.model, arabicTargetPluralEntry.model, { locale: "ar" });
assert.equal(arabicPluralCoverage[0].selector, "count");
assert.equal(arabicPluralCoverage[0].categories.find((category) => category.category === "two")?.state, "explicit");
assert.equal(arabicPluralCoverage[0].categories.find((category) => category.category === "few")?.state, "fallback");
assert.equal(arabicPluralCoverage[0].categories.find((category) => category.category === "other")?.state, "fallback-other");
const arabicExactZeroEntry = compileMessageCatalog({
  target: `.input {$count :number}
.match $count
0 {{لا ملفات}}
* {{ملفات}}`,
}).target;
const arabicExactZeroDiagnostics = compareMessageContracts(variantTargetEntry.model, arabicExactZeroEntry.model, { locale: "ar" });
assert.equal(arabicExactZeroDiagnostics.some((diagnostic) => diagnostic.message.includes("category zero")), false);
assert.equal(arabicExactZeroDiagnostics.some((diagnostic) => diagnostic.message.includes("category few")), true);
assert.equal(
  sourceTargetScenarioRowsFromModels(variantTargetEntry.model, arabicExactZeroEntry.model, { values: { count: 0 }, locale: "ar" })
    .some((scenario) => scenario.signature === "zero" && scenario.origins.includes("locale")),
  false,
);
const arabicExactZeroCoverage = localePluralCoverageFromModels(variantTargetEntry.model, arabicExactZeroEntry.model, { locale: "ar" });
assert.equal(arabicExactZeroCoverage[0].categories.find((category) => category.category === "zero")?.state, "exact");
assert.equal(arabicExactZeroCoverage[0].categories.find((category) => category.category === "zero")?.sample, 0);
const ordinalSourceEntry = compileMessageCatalog({
  source: `.input {$rank :number select=ordinal}
.match $rank
* {{rank {$rank}}}`,
}).source;
const ordinalTargetEntry = compileMessageCatalog({
  target: `.input {$rank :number select=ordinal}
.match $rank
1 {{first}}
* {{rank {$rank}}}`,
}).target;
const ordinalScenarioRows = sourceTargetScenarioRowsFromModels(ordinalSourceEntry.model, ordinalTargetEntry.model, { values: { rank: 1 }, locale: "en" });
assert.equal(ordinalScenarioRows.find((scenario) => scenario.signature === "one")?.status, "locale-missing");
assert.deepEqual(ordinalScenarioRows.find((scenario) => scenario.signature === "one")?.values, { rank: 21 });
const ordinalDiagnostics = compareMessageContracts(ordinalSourceEntry.model, ordinalTargetEntry.model, { locale: "en" });
assert.equal(ordinalDiagnostics.some((diagnostic) => diagnostic.message.includes("CLDR ordinal category one")), true);

const multiVariantSourceEntry = compileMessageCatalog({
  source: `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
* * {{They reviewed {$count} files}}`,
}).source;
const multiVariantTargetEntry = compileMessageCatalog({
  target: `.input {$gender :string}
.input {$count :number}
.match $gender $count
* * {{La personne a relu {$count} fichiers}}`,
}).target;
const multiRowActions = variantRowActionsFromModels(multiVariantSourceEntry.model, multiVariantTargetEntry.model, { locale: "en" });
assert.equal(multiRowActions.length, 1);
assert.equal(multiRowActions[0].snippet, "male one {{TODO {$count}}}");
assert.deepEqual(
  multiRowActions[0].keyDetails.map((detail) => detail.label),
  ["$gender: context value male", "$count: CLDR category one (sample 1)"],
);
const multiArabicScenarioRows = sourceTargetScenarioRowsFromModels(
  multiVariantSourceEntry.model,
  multiVariantTargetEntry.model,
  { values: { gender: "unknown", count: 5 }, locale: "ar" },
);
assert.deepEqual(
  multiArabicScenarioRows.find((scenario) => scenario.signature === "* few")?.localePlural?.suggestions.map((item) => item.keys),
  [["male", "few"]],
);
assert.deepEqual(
  multiArabicScenarioRows.find((scenario) => scenario.signature === "* few")?.localePlural?.suggestions.map((item) => item.label),
  ["$gender: male, $count: few"],
);
const multiArabicLocaleActions = targetLocaleVariantRowActionsFromModels(
  multiVariantSourceEntry.model,
  multiVariantTargetEntry.model,
  { values: { gender: "unknown", count: 5 }, locale: "ar" },
);
const multiArabicSpecificAction = multiArabicLocaleActions.find((action) => action.subject === "target-locale-specific:male few");
assert.equal(multiArabicSpecificAction?.label, "Add specific target-locale row male few");
assert.equal(multiArabicSpecificAction?.snippet, "male few {{TODO {$count}}}");
assert.deepEqual(multiArabicSpecificAction?.sampleValues, { gender: "male", count: 3 });
assert.deepEqual(
  multiArabicSpecificAction?.keyDetails.map((detail) => detail.label),
  ["$gender: context value male", "$count: CLDR category few (sample 3)"],
);
const targetMultiArabicSpecificEntry = compileMessageCatalog({
  target: `.input {$gender :string}
.input {$count :number}
.match $gender $count
male few {{Il a quelques fichiers}}
* * {{Ils ont {$count} fichiers}}`,
}).target;
const arabicSpecificDiagnostics = compareMessageContracts(
  multiVariantSourceEntry.model,
  targetMultiArabicSpecificEntry.model,
  { locale: "ar" },
);
assert.equal(arabicSpecificDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
const targetMultiUnsupportedContextEntry = compileMessageCatalog({
  target: `.input {$gender :string}
.input {$count :number}
.match $gender $count
robot few {{Robot a quelques fichiers}}
* * {{Ils ont {$count} fichiers}}`,
}).target;
const unsupportedContextDiagnostics = compareMessageContracts(
  multiVariantSourceEntry.model,
  targetMultiUnsupportedContextEntry.model,
  { locale: "ar" },
);
assert.equal(unsupportedContextDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), true);
const unsupportedContextActions = targetSpecificVariantRowActionsFromModels(
  multiVariantSourceEntry.model,
  targetMultiUnsupportedContextEntry.model,
  { locale: "ar", values: { gender: "robot", count: 3 } },
);
assert.equal(unsupportedContextActions[0]?.label, "Remove target-specific row robot few");
assert.deepEqual(unsupportedContextActions[0]?.keys, ["robot", "few"]);
assert.equal(
  removeVariantRowSource(`.input {$gender :string}
.input {$count :number}
.match $gender $count
robot few {{Robot a quelques fichiers}}
* * {{Ils ont {$count} fichiers}}`, unsupportedContextActions[0]?.keys, 2),
  `.input {$gender :string}
.input {$count :number}
.match $gender $count
* * {{Ils ont {$count} fichiers}}`,
);

const variantOrderSourceEntry = compileMessageCatalog({
  source: `.input {$kind :string}
.match $kind
error {{The operation failed: {$kind}}}
* {{The operation completed: {$kind}}}`,
}).source;
const variantOrderTargetEntry = compileMessageCatalog({
  target: `.input {$kind :string}
.match $kind
* {{Operation terminee: {$kind}}}
error {{Operation echouee: {$kind}}}`,
}).target;
const variantOrderDiagnostics = compareMessageContracts(variantOrderSourceEntry.model, variantOrderTargetEntry.model);
assert.equal(variantOrderDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-variant"), false);
assert.equal(variantOrderDiagnostics.some((diagnostic) => diagnostic.code === "target-only-variant"), false);
const variantOrderScenarios = sourceTargetScenarioRowsFromModels(
  variantOrderSourceEntry.model,
  variantOrderTargetEntry.model,
  { values: { kind: "ok" }, locale: "en" },
);
assert.equal(variantOrderScenarios.length, 2);
assert.equal(variantOrderScenarios.every((scenario) => scenario.origins.includes("source") && scenario.origins.includes("target")), true);

const selectorPriorityEntry = compileMessageCatalog({
  target: `.input {$first :string}
.input {$second :string}
.match $first $second
* * {{fallback}}
* exact {{second exact}}
exact * {{first exact}}`,
}).target;
const selectorPriorityDiagnostics = compareMessageContracts(selectorPriorityEntry.model, selectorPriorityEntry.model);
assert.equal(selectorPriorityDiagnostics.some((diagnostic) => diagnostic.code === "selector-priority-overlap"), true);
const selectorPriorityScenarios = sourceTargetScenarioRowsFromModels(
  selectorPriorityEntry.model,
  selectorPriorityEntry.model,
  { values: { first: "exact", second: "exact" }, locale: "en" },
);
assert.equal(
  selectorPriorityScenarios.some((scenario) => scenario.notes.some((note) => note.includes("$first") && note.includes("wins"))),
  true,
);
const selectorPrioritySummary = scenarioOrderSummary(selectorPriorityScenarios);
assert.equal(selectorPrioritySummary.selectorPriorityRowCount > 0, true);
assert.equal(selectorPrioritySummary.rowOrderRowCount, 0);

const offsetLikesEntry = compileMessageCatalog({
  source: `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* 1 {{{$name} and {$others_count} other user liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`,
}).source;
const offsetScenarioRows = sourceTargetScenarioRowsFromModels(
  offsetLikesEntry.model,
  offsetLikesEntry.model,
  { values: { like_count: 0, name: "Alex" }, locale: "en" },
);
assert.equal(offsetScenarioRows.some((scenario) => scenario.signature === "one *"), false);
assert.equal(offsetScenarioRows.find((scenario) => scenario.signature === "* 1")?.values.like_count, 2);
assert.equal(offsetScenarioRows.find((scenario) => scenario.signature === "* 1")?.values.others_count, 1);
assert.equal(offsetScenarioRows.find((scenario) => scenario.signature === "* *")?.values.like_count, 3);
assert.equal(offsetScenarioRows.find((scenario) => scenario.signature === "* *")?.values.others_count, 2);
assert.equal(offsetScenarioRows.find((scenario) => scenario.signature === "* *")?.source.value.includes("2") ?? false, true);
const variableOffsetEntry = compileMessageCatalog({
  source: `.input {$like_count :integer}
.input {$hidden_count :integer}
.local $visible_count = {$like_count :offset subtract=$hidden_count}
.match $like_count $visible_count
0 * {{Nobody liked your post.}}
* one {{{$name} and {$visible_count} other visible user liked your post.}}
* * {{{$name} and {$visible_count} other visible users liked your post.}}`,
}).source;
const variableOffsetScenarioRows = sourceTargetScenarioRowsFromModels(
  variableOffsetEntry.model,
  variableOffsetEntry.model,
  { values: { like_count: 5, hidden_count: 2, name: "Alex" }, locale: "en" },
);
const variableOffsetOneScenario = variableOffsetScenarioRows.find((scenario) => scenario.signature === "* one");
assert.deepEqual(variableOffsetOneScenario?.values, { like_count: 3, hidden_count: 2, name: "Alex", visible_count: 1 });
assert.equal(stripBidiControls(variableOffsetOneScenario?.source.value), "Alex and 1 other visible user liked your post.");
const offsetPluralEntry = compileMessageCatalog({
  source: `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* one {{{$name} and {$others_count} other user liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`,
}).source;
const offsetFallbackTargetEntry = compileMessageCatalog({
  target: `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`,
}).target;
const offsetPluralScenarioRows = sourceTargetScenarioRowsFromModels(
  offsetPluralEntry.model,
  offsetFallbackTargetEntry.model,
  { values: { like_count: 3, name: "Alex" }, locale: "en" },
);
const offsetOneScenario = offsetPluralScenarioRows.find((scenario) => scenario.signature === "* one");
assert.equal(offsetOneScenario?.status, "source-only");
assert.equal(offsetOneScenario?.rowKind, "category");
assert.deepEqual(offsetOneScenario?.values, { like_count: 2, name: "Alex", others_count: 1 });
assert.equal(stripBidiControls(offsetOneScenario?.source.value), "Alex and 1 other user liked your post.");
assert.equal(stripBidiControls(offsetOneScenario?.target.value), "Alex and 1 other users liked your post.");
const offsetPluralCoverage = localePluralCoverageFromModels(offsetPluralEntry.model, offsetFallbackTargetEntry.model, { locale: "en" });
assert.equal(offsetPluralCoverage.find((item) => item.selector === "others_count")?.categories.find((category) => category.category === "one")?.state, "fallback");

const overlappingNumericEntry = compileMessageCatalog({
  target: `.input {$count :integer}
.match $count
* {{fallback}}
1 {{exact one}}
one {{plural one}}`,
}).target;
const overlappingNumericDiagnostics = compareMessageContracts(overlappingNumericEntry.model, overlappingNumericEntry.model);
assert.equal(overlappingNumericDiagnostics.some((diagnostic) => diagnostic.code === "overlapping-numeric-variant"), true);
const overlappingNumericScenarios = sourceTargetScenarioRowsFromModels(
  overlappingNumericEntry.model,
  overlappingNumericEntry.model,
  { values: { count: 0 }, locale: "en" },
);
assert.equal(
  overlappingNumericScenarios.some((scenario) => scenario.notes.some((note) => note.includes("appears first and wins"))),
  true,
);
assert.equal(scenarioOrderSummary(overlappingNumericScenarios).rowCount > 0, true);
assert.equal(scenarioOrderSummary(overlappingNumericScenarios).noteCount > 0, true);
assert.equal(
  fixDiagnostics.some((diagnostic) => diagnostic.code === "missing-source-markup" && diagnostic.fix?.snippet === "{#link href=$url @title=|Profile page|}text{/link}"),
  true,
);

const compiledCatalog = createCompiledMessageCatalog({
  compiled: compiledFromSource.compiledSource,
  brokenCompiled: compiledFromSource.brokenSource,
});
assert.throws(
  () => createCompiledMessageCatalog({ bad: "Hello {$name}" }),
  /Source strings require createMessageCatalog/u,
);

const components = {
  badge: ({ label, title }) => React.createElement("span", { className: "badge", title }, label),
  br: () => React.createElement("br"),
  link: ({ href, title, children }) => React.createElement("a", { href, title }, children),
};

function Probe() {
  const text = useMessage("cart", { count: 1 });
  const custom = useMessage("custom", { name: "mojito" });
  const parts = useMessageParts("profile", { name: "Jean", url: "/people/jean" });
  const diagnostics = useMessageDiagnostics("broken");
  const entry = useMessageEntry("profile");
  const formatter = useMessageFormatter({ bidiIsolation: "default" });
  const ids = useMessageIds();

  return React.createElement(
    "output",
    {
      "data-diagnostics": diagnostics.map((diagnostic) => diagnostic.code).join(","),
      "data-entry": entry.id,
      "data-custom": custom,
      "data-format-alias": formatter.format("cart", { count: 2 }),
      "data-format": formatter.formatMessage("cart", { count: 2 }),
      "data-format-custom": formatter.formatMessage("custom", { name: "mojito" }),
      "data-format-parts": String(formatter.formatMessageToParts("profile", { name: "Jean", url: "/people/jean" }).length),
      "data-format-to-parts": String(formatter.formatToParts("profile", { name: "Jean", url: "/people/jean" }).length),
      "data-ids": ids.join(","),
      "data-parts": String(parts.length),
    },
    text,
  );
}

const hookHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components, functions },
    React.createElement(Probe),
  ),
);
assert.match(hookHtml, /data-ids="cart,profile,standalone,custom,broken"/u);
assert.match(hookHtml, /data-entry="profile"/u);
assert.match(hookHtml, /data-diagnostics="unclosed-quoted-pattern"/u);
assert.match(hookHtml, /data-custom="Custom MOJITO"/u);
assert.match(hookHtml, /data-format-alias="⁨2⁩ items"/u);
assert.match(hookHtml, /data-format="⁨2⁩ items"/u);
assert.match(hookHtml, /data-format-custom="Custom ⁨MOJITO⁩"/u);
assert.match(hookHtml, /data-format-parts="6"/u);
assert.match(hookHtml, /data-format-to-parts="6"/u);
assert.match(hookHtml, /data-parts="6"/u);
assert.match(hookHtml, />1 item</u);

const richHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components, functions },
    React.createElement(FormattedMessage, {
      id: "profile",
      values: { name: "Jean", url: "/people/jean" },
    }),
  ),
);
assert.match(richHtml, /<a href="\/people\/jean" title="Profile page">profile<\/a>/u);
assert.match(richHtml, /Jean/u);

const renderPropHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components, functions },
    React.createElement(
      FormattedMessage,
      {
        id: "profile",
        values: { name: "Jean", url: "/people/jean" },
      },
      (chunks, meta) =>
        React.createElement("strong", { "data-locale": meta.locale, "data-parts": String(meta.parts.length) }, chunks),
    ),
  ),
);
assert.match(renderPropHtml, /^<strong data-locale="en" data-parts="6">/u);
assert.match(renderPropHtml, /<a href="\/people\/jean" title="Profile page">profile<\/a>/u);

const standaloneHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components, functions },
    React.createElement(FormattedMessage, {
      id: "standalone",
    }),
  ),
);
assert.match(standaloneHtml, /Line<br\/>Break/u);
assert.match(standaloneHtml, /<span class="badge" title="Preview feature">Beta<\/span>/u);

const mergedComponentHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components: { br: components.br }, functions },
    React.createElement(FormattedMessage, {
      id: "standalone",
      components: { badge: components.badge },
    }),
  ),
);
assert.match(mergedComponentHtml, /Line<br\/>Break/u);
assert.match(mergedComponentHtml, /<span class="badge" title="Preview feature">Beta<\/span>/u);

const nestedProviderHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components: { br: components.br }, functions },
    React.createElement(
      MessageProvider,
      { components: { badge: components.badge } },
      React.createElement(FormattedMessage, { id: "standalone" }),
    ),
  ),
);
assert.match(nestedProviderHtml, /Line<br\/>Break/u);
assert.match(nestedProviderHtml, /<span class="badge" title="Preview feature">Beta<\/span>/u);

const partsHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "en", components, functions },
    React.createElement(MessageParts, {
      id: "profile",
      values: { name: "Jean", url: "/people/jean" },
      children: ({ entry, diagnostics, parts, locale }) =>
        React.createElement("output", {
          "data-diagnostics": String(diagnostics.length),
          "data-entry": entry.id,
          "data-locale": locale,
          "data-markup": parts.filter((part) => part.type === "markup").map((part) => `${part.kind}:${part.name}`).join(","),
          "data-parts": String(parts.length),
        }),
    }),
  ),
);
assert.match(partsHtml, /data-entry="profile"/u);
assert.match(partsHtml, /data-diagnostics="0"/u);
assert.match(partsHtml, /data-locale="en"/u);
assert.match(partsHtml, /data-markup="open:link,close:link"/u);
assert.match(partsHtml, /data-parts="6"/u);

const contractHtml = renderToStaticMarkup(
  React.createElement(SourceTargetDiagnostics, {
    sourceModel: sourceContractEntry.model,
    targetModel: targetContractEntry.model,
  }),
);
assert.match(contractHtml, /new-placeholder/u);
assert.match(contractHtml, /missing-source-placeholder/u);
assert.match(contractHtml, /missing-source-variant/u);

const targetInsertionHtml = renderToStaticMarkup(
  React.createElement(TargetInsertionPanel, {
    contract: messageContractFromModel(actionContractEntry.model),
  }),
);
assert.match(targetInsertionHtml, /Insert source tokens/u);
assert.match(targetInsertionHtml, /\$name/u);
assert.match(targetInsertionHtml, /#link/u);
const targetInsertionOverflowHtml = renderToStaticMarkup(
  React.createElement(TargetInsertionPanel, {
    contract: messageContractFromModel(manyInsertionActionsEntry.model),
    visibleLimit: 2,
  }),
);
assert.match(targetInsertionOverflowHtml, /2 more/u);
assert.match(targetInsertionOverflowHtml, /More source tokens/u);
assert.match(targetInsertionOverflowHtml, /\$gamma/u);
const targetInsertionStatusHtml = renderToStaticMarkup(
  React.createElement(TargetInsertionPanel, {
    contract: messageContractFromModel(actionContractEntry.model),
    targetContract: messageContractFromModel(partialActionTargetEntry.model),
  }),
);
assert.match(targetInsertionStatusHtml, /<strong>3<\/strong> source token\(s\)/u);
assert.match(targetInsertionStatusHtml, /<strong>2<\/strong> missing/u);
assert.match(targetInsertionStatusHtml, /<strong>1<\/strong> in target/u);
assert.match(targetInsertionStatusHtml, /target-token-present/u);
assert.match(targetInsertionStatusHtml, /target-token-missing/u);
const targetInsertionMissingFirstHtml = renderToStaticMarkup(
  React.createElement(TargetInsertionPanel, {
    contract: messageContractFromModel(actionContractEntry.model),
    targetContract: messageContractFromModel(partialActionTargetEntry.model),
    visibleLimit: 1,
  }),
);
assert.equal(targetInsertionMissingFirstHtml.indexOf("$url") < targetInsertionMissingFirstHtml.indexOf("$name"), true);
assert.match(targetInsertionMissingFirstHtml, /2 more/u);

const fixHtml = renderToStaticMarkup(
  React.createElement(SourceTargetDiagnostics, {
    sourceModel: actionContractEntry.model,
    targetModel: actionTargetEntry.model,
    onInsertFix: () => {},
  }),
);
assert.match(fixHtml, /Insert \$name/u);
assert.match(fixHtml, /Insert #link/u);

const rowHtml = renderToStaticMarkup(
  React.createElement(VariantRowPanel, {
    sourceModel: variantSourceEntry.model,
    targetModel: variantTargetEntry.model,
    locale: "en",
  }),
);
assert.match(rowHtml, /Missing source rows/u);
assert.match(rowHtml, /Add CLDR row one/u);
assert.match(rowHtml, /sample 1/u);
assert.match(rowHtml, /keeps \$count/u);
assert.match(rowHtml, /one \{\{TODO \{\$count\}\}\}/u);
assert.match(rowHtml, /current fallback/u);
const localeRowHtml = renderToStaticMarkup(
  React.createElement(VariantRowPanel, {
    sourceModel: variantTargetEntry.model,
    targetModel: arabicTargetPluralEntry.model,
    locale: "ar",
    values: { count: 5 },
  }),
);
assert.match(localeRowHtml, /locale rows/u);
assert.match(localeRowHtml, /Add target-locale CLDR row few/u);
assert.match(localeRowHtml, /few \{\{TODO\}\}/u);
const cleanupRowHtml = renderToStaticMarkup(
  React.createElement(VariantRowPanel, {
    sourceModel: multiVariantSourceEntry.model,
    targetModel: targetMultiUnsupportedContextEntry.model,
    locale: "ar",
    values: { gender: "robot", count: 3 },
  }),
);
assert.match(cleanupRowHtml, /cleanup/u);
assert.match(cleanupRowHtml, /Remove target-specific row robot few/u);

const scenarioHtml = renderToStaticMarkup(
  React.createElement(SourceTargetScenarioMatrix, {
    sourceModel: variantSourceEntry.model,
    targetModel: variantTargetEntry.model,
    locale: "en",
    values: { count: 5 },
  }),
);
assert.match(scenarioHtml, /Source \/ target scenarios/u);
assert.match(scenarioHtml, /Target-locale plural coverage/u);
assert.match(scenarioHtml, /source row missing/u);
assert.match(scenarioHtml, /CLDR row/u);
assert.match(scenarioHtml, /CLDR category/u);
assert.match(scenarioHtml, /Beaucoup de fichiers/u);
assert.match(scenarioHtml, /Scenario status summary/u);
assert.match(scenarioHtml, /Needs review/u);
const filteredScenarioHtml = renderToStaticMarkup(
  React.createElement(SourceTargetScenarioMatrix, {
    sourceModel: variantSourceEntry.model,
    targetModel: variantTargetEntry.model,
    locale: "en",
    values: { count: 5 },
    filter: "source-only",
  }),
);
assert.match(filteredScenarioHtml, /aria-pressed="true" type="button">Needs target row/u);
assert.match(filteredScenarioHtml, /Showing <strong>1<\/strong> row\(s\)/u);
assert.match(filteredScenarioHtml, /source row missing/u);
const fixedScenarioHtml = renderToStaticMarkup(
  React.createElement(SourceTargetScenarioMatrix, {
    sourceModel: fixedVariantSourceEntry.model,
    targetModel: variantTargetEntry.model,
    locale: "en",
    values: { count: 5 },
  }),
);
assert.match(fixedScenarioHtml, /fixed row/u);
const orderScenarioHtml = renderToStaticMarkup(
  React.createElement(SourceTargetScenarioMatrix, {
    sourceModel: overlappingNumericEntry.model,
    targetModel: overlappingNumericEntry.model,
    locale: "en",
    values: { count: 0 },
  }),
);
assert.match(orderScenarioHtml, /Row order affects/u);
assert.match(orderScenarioHtml, /first matching row wins/u);
assert.match(orderScenarioHtml, /appears first and wins/u);
const selectorPriorityHtml = renderToStaticMarkup(
  React.createElement(SourceTargetScenarioMatrix, {
    sourceModel: selectorPriorityEntry.model,
    targetModel: selectorPriorityEntry.model,
    locale: "en",
    values: { first: "exact", second: "exact" },
  }),
);
assert.match(selectorPriorityHtml, /Selector priority affects/u);
assert.match(selectorPriorityHtml, /first differing selector/u);

const compiledHtml = renderToStaticMarkup(
  React.createElement(
    RuntimeMessageProvider,
    { catalog: compiledCatalog, locale: "en" },
    React.createElement(RuntimeFormattedMessage, {
      id: "compiled",
      values: { name: "Mojito" },
    }),
  ),
);
assert.equal(compiledHtml, "Compiled Mojito");

const compiledDiagnosticsHtml = renderToStaticMarkup(
  React.createElement(
    RuntimeMessageProvider,
    { catalog: compiledCatalog, locale: "en" },
    React.createElement(RuntimeFormattedMessage, {
      id: "brokenCompiled",
      values: { count: 1 },
    }),
  ),
);
assert.equal(compiledDiagnosticsHtml, '<span data-mf2-error="unclosed-quoted-pattern">{brokenCompiled}</span>');

const blockHtml = renderToStaticMarkup(
  React.createElement(
    MessageProvider,
    { catalog, locale: "ar", components, functions },
    React.createElement(FormattedMessageBlock, {
      id: "cart",
      values: { count: 2 },
      dir: "locale",
    }),
  ),
);
assert.match(blockHtml, /^<div dir="rtl">/u);

assert.equal(isolateText("abc", "ltr"), "\u2066abc\u2069");
assert.equal(
  renderToStaticMarkup(React.createElement(BidiIsolate, { direction: "rtl" }, "abc")),
  '<bdi dir="rtl">abc</bdi>',
);

console.log("MF2 React wrapper smoke test passed");
