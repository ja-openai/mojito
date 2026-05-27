import React from "react";

import { formatMessage } from "@mojito-mf2/core";
import { selectCardinal, selectOrdinal } from "@mojito-mf2/core/plural-rules";

export function messageContractFromModel(model) {
  if (model == null) return null;
  const declarations = [];
  const selectors = [];
  const placeholders = new Set();
  const variables = new Set();
  const functions = new Set();
  const markup = new Map();
  const patterns = [];
  for (const declaration of model.declarations ?? []) {
    if (declaration.type === "input") declarations.push(`${declaration.name}${functionLabel(declaration.value?.function)}`);
    if (declaration.type === "local") declarations.push(`${declaration.name} local`);
    collectExpression(declaration.value, variables, functions);
  }
  for (const selector of model.selectors ?? []) {
    const name = selectorName(selector);
    selectors.push({ name, annotation: selectorAnnotation(model, name) });
    variables.add(name);
  }
  if (model.type === "message") patterns.push(model.pattern ?? []);
  for (const variant of model.variants ?? []) patterns.push(variant.value ?? []);
  for (const pattern of patterns) {
    for (const part of pattern) collectPart(part, placeholders, variables, functions, markup);
  }
  return {
    declarations,
    selectors,
    placeholders: [...placeholders].sort(),
    variables: [...variables].sort(),
    functions: [...functions].sort(),
    markup: [...markup.values()].map(markupContract).sort((left, right) => left.name.localeCompare(right.name)),
    variants: variantContracts(model),
  };
}

export function compareMessageContracts(sourceModel, targetModel, { locale = "en" } = {}) {
  const source = messageContractFromModel(sourceModel);
  const target = messageContractFromModel(targetModel);
  if (source == null || target == null) return [];
  const diagnostics = [
    ...compareSet("placeholder", source.placeholders, target.placeholders),
    ...compareVariantPlaceholders(sourceModel, targetModel),
    ...compareSelectors(source.selectors, target.selectors),
    ...compareMarkup(source.markup, target.markup),
    ...compareVariants(sourceModel, targetModel, locale),
  ];
  return addDiagnosticFixes(diagnostics, source);
}

export function MessageContractPanel({ model, contract = messageContractFromModel(model) }) {
  if (contract == null) return null;
  return React.createElement(
    "div",
    { className: "contract-panel" },
    React.createElement("h2", null, "Message contract"),
    React.createElement(
      "div",
      { className: "contract-list" },
      React.createElement("span", null, `declarations: ${contract.declarations.length === 0 ? "none" : contract.declarations.join(", ")}`),
      React.createElement("span", null, `selectors: ${contract.selectors.length === 0 ? "none" : contract.selectors.map((selector) => selector.name).join(", ")}`),
      React.createElement("span", null, `placeholders: ${contract.placeholders.length === 0 ? "none" : contract.placeholders.join(", ")}`),
      React.createElement("span", null, `variables: ${contract.variables.length === 0 ? "none" : contract.variables.join(", ")}`),
      React.createElement("span", null, `functions: ${contract.functions.length === 0 ? "none" : contract.functions.join(", ")}`),
      React.createElement("span", null, `markup: ${contract.markup.length === 0 ? "none" : contract.markup.map((item) => item.name).join(", ")}`),
    ),
  );
}

export function targetInsertionActionsFromContract(contract, options = {}) {
  if (contract == null) return [];
  const targetContract = options?.targetContract ?? null;
  const actions = [
    ...contract.placeholders.map((name) => ({
      kind: "placeholder",
      subject: name,
      label: `$${name}`,
      snippet: `{$${name}}`,
      description: `Insert source placeholder $${name}.`,
    })),
    ...contract.markup.flatMap(markupInsertionActions),
  ];
  return actions.map((action) => annotateTargetInsertionAction(action, targetContract));
}

export function TargetInsertionPanel({
  model,
  contract = messageContractFromModel(model),
  targetModel,
  targetContract = messageContractFromModel(targetModel),
  onInsert,
  prioritizeMissing = true,
  visibleLimit = 8,
}) {
  const actions = targetInsertionActionsFromContract(contract, { targetContract });
  const [selectedOverflowAction, setSelectedOverflowAction] = React.useState("");
  if (actions.length === 0) {
    return React.createElement("div", { className: "status-line" }, "No source placeholders or markup to insert.");
  }
  const visibleCount = Math.max(0, visibleLimit);
  const orderedActions = prioritizeMissing ? orderTargetInsertionActions(actions) : actions;
  const visibleActions = orderedActions.slice(0, visibleCount);
  const overflowActions = orderedActions.slice(visibleCount);
  const selectedAction =
    overflowActions.find((action) => actionKey(action) === selectedOverflowAction) ?? overflowActions[0] ?? null;
  return React.createElement(
    "div",
    { className: "target-insertions" },
    React.createElement("h2", null, "Insert source tokens"),
    targetContract == null ? null : React.createElement(TargetInsertionSummary, { actions }),
    React.createElement(
      "div",
      { className: "target-insertion-actions" },
      visibleActions.map((action) =>
        React.createElement(
          "button",
          {
            className: `target-token-${action.targetStatus}`,
            key: actionKey(action),
            type: "button",
            title: action.description,
            onClick: () => onInsert?.(action.snippet),
          },
          React.createElement("span", null, action.label),
          targetStatusLabel(action) ? React.createElement("small", null, targetStatusLabel(action)) : null,
        ),
      ),
      overflowActions.length > 0
        ? React.createElement(
            "div",
            { className: "target-insertion-overflow" },
            React.createElement("span", null, `${overflowActions.length} more`),
            React.createElement(
              "select",
              {
                "aria-label": "More source tokens",
                value: selectedAction ? actionKey(selectedAction) : "",
                onChange: (event) => setSelectedOverflowAction(event.target.value),
              },
              overflowActions.map((action) =>
                React.createElement(
                  "option",
                  { key: actionKey(action), value: actionKey(action) },
                  targetStatusLabel(action) ? `${action.label} (${targetStatusLabel(action)})` : action.label,
                ),
              ),
            ),
            React.createElement(
              "button",
              {
                type: "button",
                disabled: selectedAction == null,
                title: selectedAction?.description ?? "Select a source token to insert.",
                onClick: () => selectedAction && onInsert?.(selectedAction.snippet),
              },
              "Insert",
            ),
          )
        : null,
    ),
  );
}

function TargetInsertionSummary({ actions }) {
  const missing = actions.filter((action) => action.targetStatus === "missing").length;
  const present = actions.filter((action) => action.targetStatus === "present").length;
  return React.createElement(
    "div",
    { className: "target-insertion-summary" },
    React.createElement("span", null, React.createElement("strong", null, actions.length), " source token(s)"),
    React.createElement("span", null, React.createElement("strong", null, missing), " missing"),
    React.createElement("span", null, React.createElement("strong", null, present), " in target"),
  );
}

function orderTargetInsertionActions(actions) {
  return [...actions].sort((left, right) => targetStatusRank(left) - targetStatusRank(right));
}

function targetStatusRank(action) {
  if (action.targetStatus === "missing") return 0;
  if (action.targetStatus === "present") return 2;
  return 1;
}

function actionKey(action) {
  return `${action.kind}-${action.label}-${action.snippet}`;
}

function annotateTargetInsertionAction(action, targetContract) {
  const targetStatus = targetContract == null ? "unknown" : targetContractHasInsertionAction(targetContract, action) ? "present" : "missing";
  return {
    ...action,
    targetStatus,
    description:
      targetStatus === "unknown"
        ? action.description
        : `${action.description} ${targetStatus === "present" ? "Already present in the target." : "Missing from the target."}`,
  };
}

function targetContractHasInsertionAction(contract, action) {
  if (action.kind === "placeholder") return contract.placeholders.includes(action.subject);
  if (action.kind === "markup") {
    return contract.markup.some((item) => item.name === action.subject && targetMarkupKindMatchesAction(item, action));
  }
  return false;
}

function targetMarkupKindMatchesAction(item, action) {
  if (action.markupKind === "standalone") return item.kinds.includes("standalone");
  if (action.markupKind === "span") return item.kinds.includes("open") || item.kinds.includes("close");
  return true;
}

function targetStatusLabel(action) {
  if (action.targetStatus === "present") return "in target";
  if (action.targetStatus === "missing") return "missing";
  return "";
}

export function insertVariantRowSource(source, row, selectorCount = 1) {
  const prefix = String(source ?? "").endsWith("\n") ? "" : "\n";
  const fallbackIndex = fallbackVariantLineIndex(source, selectorCount);
  if (fallbackIndex < 0) return `${source}${prefix}${row}`;
  const lines = String(source).split("\n");
  lines.splice(fallbackIndex, 0, row);
  return lines.join("\n");
}

export function removeVariantRowSource(source, keys, selectorCount = keys?.length ?? 1) {
  const values = keys ?? [];
  if (!values.length || values.every((key) => key === "*")) return String(source ?? "");
  const lines = String(source ?? "").split("\n");
  const pattern = variantLinePattern(values, selectorCount);
  const matchIndex = lines.findIndex((line) => line.trimStart().startsWith(".match"));
  const start = matchIndex >= 0 ? matchIndex + 1 : 0;
  const index = lines.findIndex((line, lineIndex) => lineIndex >= start && pattern.test(line));
  if (index < 0) return String(source ?? "");
  lines.splice(index, 1);
  return lines.join("\n");
}

export function variantRowActionsFromModels(sourceModel, targetModel, { locale = "en", values = {}, functions } = {}) {
  if (sourceModel?.type !== "select" || targetModel?.type !== "select") return [];
  if (selectorSequence(sourceModel).join("\u0000") !== selectorSequence(targetModel).join("\u0000")) return [];
  const selectors = selectorSequence(sourceModel).map((name, index) => selectorMetadata(sourceModel, name, index));
  const targetVariants = new Set(variantContracts(targetModel));
  return (sourceModel.variants ?? [])
    .filter((variant) => !targetVariants.has(variantSignature(variant)))
    .map((variant) => {
      const keys = variant.keys ?? [];
      const signature = keys.map(variantKeyValue).join(" ");
      const placeholders = patternPlaceholderNames(variant.value ?? []);
      const body = variantBodySnippet(placeholders);
      const keyDetails = keys.map((key, index) => variantKeyDetail(key, selectors[index], locale));
      const sampleValues = variantSampleValues(keyDetails, values);
      const fallbackPreview = renderTargetPreview(targetModel, sampleValues, { locale, functions });
      const rowKind = variantRowActionKind(keyDetails);
      return {
        kind: "variant-row",
        rowKind,
        subject: signature,
        label: `${variantRowActionVerb(rowKind)} ${signature}`,
        snippet: `${keys.map(variantKeySnippet).join(" ")} {{${body}}}`,
        description: `Append ${variantRowActionDescription(rowKind)} for source variant ${signature}.`,
        keyDetails,
        placeholders,
        sampleValues,
        fallbackPreview,
      };
    });
}

export function targetLocaleVariantRowActionsFromModels(sourceModel, targetModel, { locale = "en", values = {}, functions } = {}) {
  if (targetModel?.type !== "select") return [];
  const scenarios = sourceTargetScenarioRowsFromModels(sourceModel, targetModel, { locale, values, functions });
  const fallbackPlaceholders = patternPlaceholderNames(targetFallbackVariant(targetModel)?.value ?? []);
  return scenarios
    .filter((scenario) => scenario.status === "locale-missing")
    .flatMap((scenario) => [
      targetLocaleVariantRowAction(scenario, fallbackPlaceholders, targetModel, { locale, functions }),
      ...targetLocaleSpecificRowActions(scenario, fallbackPlaceholders, targetModel, { locale, functions }),
    ]);
}

export function targetSpecificVariantRowActionsFromModels(
  sourceModel,
  targetModel,
  { locale = "en", values = {}, functions } = {},
) {
  if (targetModel?.type !== "select") return [];
  return sourceTargetScenarioRowsFromModels(sourceModel, targetModel, { locale, values, functions })
    .filter((scenario) => scenario.status === "target-only")
    .map((scenario) => ({
      kind: "variant-row-removal",
      operation: "remove",
      rowKind: "target-specific",
      subject: `target-specific:${scenario.signature}`,
      label: `Remove target-specific row ${scenario.signature}`,
      description:
        `Remove target row ${scenario.signature}; ` +
        "it is not in the source and is not recommended by target-locale CLDR rules.",
      keys: scenario.keys.map(variantKeyValue),
      keyDetails: scenario.details.map(localeScenarioActionDetail),
      placeholders: [],
      sampleValues: scenario.values,
      fallbackPreview:
        scenario.target ?? renderTargetPreview(targetModel, scenario.values, { locale, functions }),
    }));
}

export function VariantRowPanel({
  sourceModel,
  targetModel,
  locale = "en",
  values = {},
  functions,
  onAppendRow,
  onRemoveRow,
}) {
  const actions = [
    ...variantRowActionsFromModels(sourceModel, targetModel, { locale, values, functions }),
    ...targetLocaleVariantRowActionsFromModels(sourceModel, targetModel, { locale, values, functions }),
    ...targetSpecificVariantRowActionsFromModels(sourceModel, targetModel, {
      locale,
      values,
      functions,
    }),
  ];
  if (actions.length === 0) return null;
  return React.createElement(
    "div",
    { className: "variant-row-actions" },
    React.createElement("h2", null, "Missing source rows, locale rows, and cleanup"),
    React.createElement(
      "div",
      { className: "variant-row-action-list" },
      actions.map((action) =>
        React.createElement(
          "div",
          { className: "variant-row-action", key: action.subject },
          React.createElement(
            "button",
            {
              type: "button",
              title: action.description,
              onClick: () => {
                if (action.operation === "remove") {
                  onRemoveRow?.(action.keys);
                } else {
                  onAppendRow?.(action.snippet);
                }
              },
            },
            action.label,
          ),
          React.createElement(
            "span",
            null,
            action.keyDetails.map((detail) => detail.label).join("; "),
            action.placeholders.length === 0 ? "" : `; keeps ${action.placeholders.map((name) => `$${name}`).join(", ")}`,
          ),
          React.createElement(
            "code",
            null,
            action.snippet ?? action.keys?.join(" "),
          ),
          React.createElement(
            "small",
            null,
            `samples ${JSON.stringify(action.sampleValues)}; current fallback `,
            action.fallbackPreview.error ?? JSON.stringify(action.fallbackPreview.value),
          ),
        ),
      ),
    ),
  );
}

export function sourceTargetScenarioRowsFromModels(sourceModel, targetModel, { locale = "en", values = {}, functions } = {}) {
  const sourceSelect = sourceModel?.type === "select" ? sourceModel : null;
  const targetSelect = targetModel?.type === "select" ? targetModel : null;
  const selectorModel = sourceSelect ?? targetSelect;
  if (selectorModel == null || values == null || typeof values !== "object" || Array.isArray(values)) return [];
  if (sourceSelect && targetSelect && selectorSequence(sourceSelect).join("\u0000") !== selectorSequence(targetSelect).join("\u0000")) return [];

  const selectors = selectorSequence(selectorModel).map((name, index) => scenarioSelectorMetadata(sourceSelect, targetSelect, name, index));
  const variants = new Map();
  addScenarioVariants(variants, sourceSelect, "source");
  addScenarioVariants(variants, targetSelect, "target");
  addLocalePluralScenarioVariants(variants, selectorModel, selectors, locale);
  const overlapNotes = scenarioOverlapNotesFromModels(sourceSelect, targetSelect, selectors, locale);
  const allowedSelectorValues = selectorValueAllowlistFromModels(sourceSelect);

  return [...variants.values()].map((scenario) => {
    const scenarioValues = { ...values };
    const sampledScenario = scenarioValuesForKeys(selectorModel, selectors, scenario.keys, scenarioValues, locale);
    const details = [];
    const notes = [];
    scenario.keys.forEach((key, index) => {
      const selector = selectors[index];
      const sample = sampledScenario.samples[index] ?? scenarioSampleValue(key, selector, sampledScenario.values[selector.name], locale);
      details.push({
        selector: selector.name,
        key: variantKeyValue(key),
        kind: sample.kind,
        sample: sample.value,
      });
      if (sample.note) notes.push(`${selector.name}: ${sample.note}`);
    });
    const rowKind = scenarioRowKind(scenario, details);
    notes.push(...(overlapNotes.get(variantSignature({ keys: scenario.keys })) ?? []));

    const source = sourceSelect ? renderTargetPreview(sourceSelect, sampledScenario.values, { locale, functions }) : null;
    const target = targetSelect ? renderTargetPreview(targetSelect, sampledScenario.values, { locale, functions }) : null;
    return {
      signature: scenario.keys.map(variantKeyValue).join(" "),
      keys: scenario.keys,
      origins: [...scenario.origins].sort(),
      status: scenarioStatus(scenario, selectors, locale, source, target, allowedSelectorValues),
      values: sampledScenario.values,
      details,
      rowKind,
      notes,
      localePlural: scenario.localePlural,
      source,
      target,
    };
  });
}

export function scenarioOrderSummary(scenarios) {
  const notes = new Set();
  const rowOrderNotes = new Set();
  const selectorPriorityNotes = new Set();
  let rowCount = 0;
  let rowOrderRowCount = 0;
  let selectorPriorityRowCount = 0;
  for (const scenario of scenarios ?? []) {
    if (!scenario?.notes?.length) continue;
    rowCount++;
    let hasRowOrderNote = false;
    let hasSelectorPriorityNote = false;
    for (const note of scenario.notes) {
      notes.add(note);
      if (scenarioNoteKind(note) === "selector-priority") {
        selectorPriorityNotes.add(note);
        hasSelectorPriorityNote = true;
      } else {
        rowOrderNotes.add(note);
        hasRowOrderNote = true;
      }
    }
    if (hasRowOrderNote) rowOrderRowCount++;
    if (hasSelectorPriorityNote) selectorPriorityRowCount++;
  }
  return {
    rowCount,
    noteCount: notes.size,
    notes: [...notes],
    rowOrderRowCount,
    rowOrderNoteCount: rowOrderNotes.size,
    rowOrderNotes: [...rowOrderNotes],
    selectorPriorityRowCount,
    selectorPriorityNoteCount: selectorPriorityNotes.size,
    selectorPriorityNotes: [...selectorPriorityNotes],
  };
}

export function localePluralCoverageFromModels(sourceModel, targetModel, { locale = "en" } = {}) {
  const sourceSelect = sourceModel?.type === "select" ? sourceModel : null;
  const targetSelect = targetModel?.type === "select" ? targetModel : null;
  const selectorModel = targetSelect ?? sourceSelect;
  if (selectorModel == null) return [];
  if (sourceSelect && targetSelect && selectorSequence(sourceSelect).join("\u0000") !== selectorSequence(targetSelect).join("\u0000")) return [];

  const selectors = selectorSequence(selectorModel).map((name, index) => scenarioSelectorMetadata(sourceSelect, targetSelect, name, index));
  return selectors.flatMap((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return [];
    const targetKeys = new Set(
      (targetSelect?.variants ?? [])
        .map((variant) => variantKeyValue((variant.keys ?? [])[selectorIndex] ?? { type: "*" })),
    );
    const categories = [...pluralCategorySetForLocale(locale, kind)];
    const exactKeys = [...scenarioExactNumericKeys(targetSelect, selectorIndex)];
    const exactKeySet = new Set(exactKeys);
    return [{
      selector: selector.name,
      kind,
      locale,
      exactKeys,
      categories: categories.map((category) => {
        const uncoveredSample = sampleForPluralCategory(locale, category, exactKeySet, kind);
        const sample = uncoveredSample ?? sampleForPluralCategory(locale, category, new Set(), kind);
        return {
          category,
          sample,
          state: pluralCoverageCategoryState(category, targetKeys, uncoveredSample, sample, exactKeySet),
        };
      }),
    }];
  });
}

const SCENARIO_FILTERS = [
  { id: "all", label: "All rows" },
  { id: "attention", label: "Needs review" },
  { id: "source-only", label: "Needs target row" },
  { id: "target-only", label: "Target-specific" },
  { id: "locale", label: "Target-locale" },
  { id: "render-error", label: "Render errors" },
];

export function SourceTargetScenarioMatrix({
  sourceModel,
  targetModel,
  locale = "en",
  values = {},
  functions,
  filter = "all",
  onFilterChange,
}) {
  const scenarios = sourceTargetScenarioRowsFromModels(sourceModel, targetModel, { locale, values, functions });
  const pluralCoverage = localePluralCoverageFromModels(sourceModel, targetModel, { locale });
  const orderSummary = scenarioOrderSummary(scenarios);
  const activeFilter = SCENARIO_FILTERS.some((item) => item.id === filter) ? filter : "all";
  const filteredScenarios = scenarios.filter((scenario) => scenarioMatchesFilter(scenario, activeFilter));
  const statusSummary = scenarioStatusSummary(scenarios, filteredScenarios.length);
  if (scenarios.length === 0) {
    return React.createElement(
      "section",
      { className: "scenario-matrix" },
      React.createElement(
        "div",
        { className: "scenario-header" },
        React.createElement("h2", null, "Source / target scenarios"),
        React.createElement("span", null, "Select messages show source/target coverage here."),
      ),
    );
  }
  return React.createElement(
    "section",
    { className: "scenario-matrix" },
    React.createElement(
      "div",
      { className: "scenario-header" },
      React.createElement("h2", null, "Source / target scenarios"),
      React.createElement("span", null, "Generated from source and target variants with CLDR plural samples."),
    ),
    pluralCoverage.length > 0 ? React.createElement(LocalePluralCoveragePanel, { coverage: pluralCoverage }) : null,
    orderSummary.rowCount > 0 ? React.createElement(ScenarioOrderSummary, { summary: orderSummary }) : null,
    React.createElement(ScenarioStatusSummary, {
      filter: activeFilter,
      onFilterChange,
      summary: statusSummary,
    }),
    React.createElement(
      "div",
      { className: "scenario-table" },
      React.createElement(
        "div",
        { className: "scenario-row scenario-title" },
        React.createElement("span", null, "Variant"),
        React.createElement("span", null, "Selector samples"),
        React.createElement("span", null, "Values"),
        React.createElement("span", null, "Status"),
        React.createElement("span", null, "Source"),
        React.createElement("span", null, "Target"),
      ),
      filteredScenarios.length === 0
        ? React.createElement(
            "div",
            { className: "scenario-row scenario-empty" },
            React.createElement("span", null, "No scenarios match this filter."),
          )
        : null,
      filteredScenarios.map((scenario, index) =>
        React.createElement(
          "div",
          { className: `scenario-row scenario-${scenario.status}`, key: `${scenario.signature}-${index}` },
          React.createElement(
            "span",
            null,
            React.createElement("strong", null, scenario.signature),
            React.createElement("span", { className: `scenario-row-kind scenario-row-kind-${scenario.rowKind}` }, scenarioRowKindLabel(scenario.rowKind)),
            scenario.notes.length > 0 ? React.createElement("small", null, scenario.notes.join("; ")) : null,
            scenario.localePlural?.suggestions?.length > 0
              ? React.createElement(
                  "small",
                  { className: "scenario-suggestions" },
                  "specific rows: ",
                  scenario.localePlural.suggestions.map((item) => item.label ?? item.keys.join(" ")).join(", "),
                  scenario.localePlural.suggestionsTruncated ? ", and more" : "",
                )
              : null,
          ),
          React.createElement(
            "div",
            { className: "scenario-detail-list" },
            scenario.details.map((detail) =>
              React.createElement(
                "span",
                { className: `scenario-detail scenario-detail-${detail.kind}`, key: `${scenario.signature}-${detail.selector}-${detail.key}` },
                React.createElement("strong", null, `$${detail.selector}`),
                React.createElement("code", null, detail.key),
                React.createElement("em", null, scenarioDetailKindLabel(detail)),
                React.createElement("small", null, scenarioDetailSampleLabel(detail)),
              ),
            ),
          ),
          React.createElement("code", null, JSON.stringify(scenario.values)),
          React.createElement("span", { className: "scenario-status-pill" }, scenarioStatusLabel(scenario.status)),
          React.createElement("span", { className: scenario.source?.error ? "scenario-error" : "" }, scenarioPreviewText(scenario.source)),
          React.createElement("span", { className: scenario.target?.error ? "scenario-error" : "" }, scenarioPreviewText(scenario.target)),
        ),
      ),
    ),
  );
}

function ScenarioStatusSummary({ summary, filter, onFilterChange }) {
  return React.createElement(
    "div",
    { className: "scenario-status-summary" },
    React.createElement(
      "div",
      { className: "scenario-summary-chips", "aria-label": "Scenario status summary" },
      React.createElement("span", null, React.createElement("strong", null, summary.total), " total"),
      React.createElement("span", null, React.createElement("strong", null, summary.covered), " covered"),
      React.createElement(
        "span",
        { className: summary.attention > 0 ? "scenario-summary-attention" : undefined },
        React.createElement("strong", null, summary.attention),
        " needs review",
      ),
      React.createElement("span", null, React.createElement("strong", null, summary.locale), " target-locale"),
      React.createElement("span", null, "Showing ", React.createElement("strong", null, summary.filtered), " row(s)"),
    ),
    React.createElement(
      "div",
      { className: "scenario-filter-row", role: "group", "aria-label": "Scenario filter" },
      SCENARIO_FILTERS.map((item) =>
        React.createElement(
          "button",
          {
            "aria-pressed": filter === item.id ? "true" : "false",
            key: item.id,
            onClick: () => onFilterChange?.(item.id),
            type: "button",
          },
          item.label,
          React.createElement("span", null, scenarioFilterCount(summary, item.id)),
        ),
      ),
    ),
  );
}

function scenarioStatusSummary(scenarios, filtered) {
  const summary = {
    attention: 0,
    covered: 0,
    filtered,
    locale: 0,
    renderError: 0,
    sourceOnly: 0,
    targetOnly: 0,
    total: scenarios.length,
  };
  for (const scenario of scenarios) {
    if (scenario.status === "covered") summary.covered += 1;
    if (scenario.status === "source-only") summary.sourceOnly += 1;
    if (scenario.status === "target-only") summary.targetOnly += 1;
    if (scenario.status === "render-error") summary.renderError += 1;
    if (scenario.status === "locale-missing" || scenario.status === "locale-recommended") summary.locale += 1;
    if (scenarioNeedsReview(scenario)) summary.attention += 1;
  }
  return summary;
}

function scenarioFilterCount(summary, filter) {
  if (filter === "attention") return summary.attention;
  if (filter === "source-only") return summary.sourceOnly;
  if (filter === "target-only") return summary.targetOnly;
  if (filter === "locale") return summary.locale;
  if (filter === "render-error") return summary.renderError;
  return summary.total;
}

function scenarioMatchesFilter(scenario, filter) {
  if (filter === "attention") return scenarioNeedsReview(scenario);
  if (filter === "source-only" || filter === "target-only" || filter === "render-error") return scenario.status === filter;
  if (filter === "locale") return scenario.status === "locale-missing" || scenario.status === "locale-recommended";
  return true;
}

function scenarioNeedsReview(scenario) {
  return (
    scenario.status === "source-only" ||
    scenario.status === "target-only" ||
    scenario.status === "locale-missing" ||
    scenario.status === "render-error"
  );
}

function ScenarioOrderSummary({ summary }) {
  return React.createElement(
    "div",
    { className: "scenario-order-summary", role: "note" },
    summary.rowOrderRowCount > 0
      ? React.createElement(
          React.Fragment,
          null,
          React.createElement("strong", null, `Row order affects ${summary.rowOrderRowCount} scenario row(s).`),
          " MF2 tries variant rows in source order; when exact values, CLDR categories, or wildcards all match the same selector tuple, the first matching row wins.",
          renderScenarioSummaryNotes(summary.rowOrderNotes, "row-order"),
        )
      : null,
    summary.selectorPriorityRowCount > 0
      ? React.createElement(
          React.Fragment,
          null,
          React.createElement("strong", null, `Selector priority affects ${summary.selectorPriorityRowCount} scenario row(s).`),
          " MF2 compares selector matches from left to right; when two rows match through different selectors, the first differing selector decides the winner independent of variant row order.",
          renderScenarioSummaryNotes(summary.selectorPriorityNotes, "selector-priority"),
        )
      : null,
  );
}

function scenarioNoteKind(note) {
  return String(note).includes("first differing selector") ? "selector-priority" : "row-order";
}

function renderScenarioSummaryNotes(notes = [], label) {
  if (!notes.length) return null;
  return React.createElement(
    "ul",
    null,
    notes.slice(0, 4).map((note) => React.createElement("li", { key: note }, note)),
    notes.length > 4 ? React.createElement("li", null, `${notes.length - 4} more ${label} note(s) in rows below.`) : null,
  );
}

function LocalePluralCoveragePanel({ coverage }) {
  return React.createElement(
    "div",
    { className: "scenario-plural-coverage" },
    React.createElement("h3", null, "Target-locale plural coverage"),
    coverage.map((item) =>
      React.createElement(
        "div",
        { className: "scenario-plural-coverage-row", key: item.selector },
        React.createElement(
          "div",
          { className: "scenario-plural-coverage-heading" },
          React.createElement("strong", null, `$${item.selector}`),
          React.createElement("span", null, `${item.locale} ${item.kind}`),
          item.exactKeys.length
            ? React.createElement("small", null, `exact rows: ${item.exactKeys.join(", ")}`)
            : null,
        ),
        React.createElement(
          "div",
          { className: "scenario-plural-coverage-pills" },
          item.categories.map((category) =>
            React.createElement(
              "span",
              { className: `scenario-coverage-pill coverage-${category.state}`, key: category.category },
              React.createElement("strong", null, category.category),
              category.sample == null ? null : React.createElement("small", null, String(category.sample)),
              React.createElement("em", null, pluralCoverageStateLabel(category.state)),
            ),
          ),
        ),
      ),
    ),
  );
}

export function SourceTargetDiagnostics({ sourceModel, targetModel, locale = "en", onInsertFix }) {
  const diagnostics = compareMessageContracts(sourceModel, targetModel, { locale });
  if (diagnostics.length === 0) {
    return React.createElement("div", { className: "status-line" }, "Source/target contract: no issues");
  }
  return React.createElement(
    "div",
    { className: "contract-diagnostics" },
    React.createElement("h2", null, "Source/target contract"),
    diagnostics.map((diagnostic, index) =>
      React.createElement(
        "div",
        { className: "contract-diagnostic", key: `${diagnostic.code}-${diagnostic.subject}-${index}` },
        React.createElement("strong", null, diagnostic.code),
        React.createElement("span", null, diagnostic.message),
        diagnostic.fix && onInsertFix
          ? React.createElement(
              "button",
              {
                className: "contract-diagnostic-fix",
                type: "button",
                title: diagnostic.fix.description,
                onClick: () => onInsertFix(diagnostic.fix.snippet),
              },
              diagnostic.fix.label,
            )
          : null,
      ),
    ),
  );
}

function collectPart(part, placeholders, variables, functions, markup) {
  if (typeof part === "string") return;
  if (part.type === "expression") collectExpression(part, variables, functions, placeholders);
  if (part.type === "markup") addMarkup(markup, part, placeholders, variables);
}

function collectExpression(expression, variables, functions, placeholders = null) {
  if (expression == null) return;
  collectOperand(expression.arg, variables);
  collectOperand(expression.arg, placeholders);
  if (expression.function?.name) {
    functions.add(`:${expression.function.name}`);
    collectPayloadVariables(expression.function.options, variables);
    collectPayloadVariables(expression.function.options, placeholders);
  }
  collectPayloadVariables(expression.attributes, variables);
  collectPayloadVariables(expression.attributes, placeholders);
}

function collectOperand(operand, variables) {
  if (variables == null) return;
  if (operand?.type === "variable") variables.add(operand.name);
}

function collectPayloadVariables(payload, variables) {
  for (const value of Object.values(payload ?? {})) collectOperand(value, variables);
}

function addMarkup(markup, part, placeholders, variables) {
  const item = markup.get(part.name) ?? {
    name: part.name,
    kinds: new Set(),
    options: new Set(),
    attributes: new Set(),
    optionPayloads: new Map(),
    attributePayloads: new Map(),
  };
  item.kinds.add(part.kind);
  for (const [name, value] of Object.entries(part.options ?? {})) {
    item.options.add(name);
    item.optionPayloads.set(name, payloadSnippet(value));
    collectOperand(value, variables);
    collectOperand(value, placeholders);
  }
  for (const [name, value] of Object.entries(part.attributes ?? {})) {
    item.attributes.add(name);
    item.attributePayloads.set(name, payloadSnippet(value));
    collectOperand(value, variables);
    collectOperand(value, placeholders);
  }
  markup.set(part.name, item);
}

function markupContract(item) {
  return {
    name: item.name,
    kinds: [...item.kinds].sort(),
    options: [...item.options].sort(),
    attributes: [...item.attributes].sort(),
    optionPayloads: Object.fromEntries([...item.optionPayloads.entries()].sort(([left], [right]) => left.localeCompare(right))),
    attributePayloads: Object.fromEntries([...item.attributePayloads.entries()].sort(([left], [right]) => left.localeCompare(right))),
  };
}

function markupInsertionActions(item) {
  const head = `{#${item.name}${markupPayloadSnippet(item)}`;
  const actions = [];
  if (item.kinds.includes("standalone")) {
    actions.push({
      kind: "markup",
      markupKind: "standalone",
      subject: item.name,
      label: `#${item.name}/`,
      snippet: `${head}/}`,
      description: `Insert source standalone markup #${item.name}.`,
    });
  }
  if (item.kinds.includes("open") || item.kinds.includes("close")) {
    actions.push({
      kind: "markup",
      markupKind: "span",
      subject: item.name,
      label: `#${item.name}`,
      snippet: `${head}}text{/${item.name}}`,
      description: `Insert source markup span #${item.name}.`,
    });
  }
  return actions;
}

function markupPayloadSnippet(item) {
  const options = item.options.map((name) => ` ${name}=${item.optionPayloads[name] ?? quotedLiteralSnippet(name)}`);
  const attributes = item.attributes.map((name) => ` @${name}=${item.attributePayloads[name] ?? quotedLiteralSnippet(name)}`);
  return [...options, ...attributes].join("");
}

function payloadSnippet(value) {
  if (value?.type === "variable") return `$${value.name}`;
  if (value?.type === "literal") return quotedLiteralSnippet(value.value);
  return quotedLiteralSnippet("");
}

function quotedLiteralSnippet(value) {
  return `|${String(value ?? "").replace(/[\\|]/gu, "\\$&")}|`;
}

function addDiagnosticFixes(diagnostics, sourceContract) {
  const actions = targetInsertionActionsFromContract(sourceContract);
  const placeholderActions = new Map(actions.filter((action) => action.kind === "placeholder").map((action) => [action.subject, action]));
  const markupActions = new Map();
  const fallbackMarkupActions = new Map();
  for (const action of actions.filter((item) => item.kind === "markup")) {
    markupActions.set(`${action.subject}:${action.markupKind ?? ""}`, action);
    if (!fallbackMarkupActions.has(action.subject) || action.label.endsWith("/")) fallbackMarkupActions.set(action.subject, action);
  }
  return diagnostics.map((item) => {
    const action = diagnosticFixAction(item, placeholderActions, markupActions, fallbackMarkupActions, sourceContract);
    return action ? { ...item, fix: insertionFix(action) } : item;
  });
}

function diagnosticFixAction(diagnostic, placeholderActions, markupActions, fallbackMarkupActions, sourceContract) {
  if (diagnostic.code === "missing-source-placeholder") return placeholderActions.get(diagnostic.subject);
  if (diagnostic.code === "variant-missing-placeholder") return placeholderActions.get(diagnostic.subject);
  if (diagnostic.code === "missing-source-markup" || diagnostic.code === "markup-shape-mismatch") {
    return sourceMarkupFixAction(diagnostic.subject, markupActions, fallbackMarkupActions, sourceContract);
  }
  return null;
}

function sourceMarkupFixAction(subject, markupActions, fallbackMarkupActions, sourceContract) {
  const source = sourceContract?.markup?.find((item) => item.name === subject);
  if (source?.kinds?.includes("open") || source?.kinds?.includes("close")) return markupActions.get(`${subject}:span`);
  if (source?.kinds?.includes("standalone")) return markupActions.get(`${subject}:standalone`);
  return fallbackMarkupActions.get(subject);
}

function insertionFix(action) {
  return {
    label: `Insert ${action.label}`,
    snippet: action.snippet,
    description: action.description,
  };
}

function fallbackVariantLineIndex(source, selectorCount) {
  const lines = String(source ?? "").split("\n");
  const fallbackPattern = fallbackVariantLinePattern(selectorCount);
  const matchIndex = lines.findIndex((line) => line.trimStart().startsWith(".match"));
  const start = matchIndex >= 0 ? matchIndex + 1 : 0;
  for (let index = start; index < lines.length; index++) {
    if (fallbackPattern.test(lines[index])) return index;
  }
  return -1;
}

function fallbackVariantLinePattern(selectorCount) {
  const keys = Array.from({ length: Math.max(1, selectorCount) }, () => "\\*").join("\\s+");
  return new RegExp(`^\\s*${keys}\\s+\\{\\{`, "u");
}

function variantLinePattern(keys, selectorCount) {
  const padded = [
    ...keys,
    ...Array.from({ length: Math.max(0, selectorCount - keys.length) }, () => "*"),
  ];
  const keyPattern = padded
    .map((key) => escapeRegExp(variantKeySnippet(variantKeyFromValue(key))))
    .join("\\s+");
  return new RegExp(`^\\s*${keyPattern}\\s+\\{\\{`, "u");
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

function selectorAnnotation(model, name) {
  const declaration = (model.declarations ?? []).find((item) => item.type === "input" && item.name === name);
  const functionRef = declaration?.value?.function;
  if (!functionRef) return "";
  const options = Object.keys(functionRef.options ?? {}).sort().join(",");
  return options ? `:${functionRef.name} ${options}` : `:${functionRef.name}`;
}

function selectorName(selector) {
  return typeof selector === "string" ? selector : selector.name;
}

function selectorSequence(model) {
  return (model.selectors ?? []).map(selectorName);
}

function selectorMetadata(model, name, index) {
  const declaration = (model.declarations ?? []).find((item) => item.name === name);
  const functionName = declaration?.value?.function?.name ?? null;
  const select = selectorSelectOption(declaration?.value?.function);
  return {
    name,
    index,
    functionName,
    select,
    isPlural: isPluralSelectorFunction(functionName),
    offset: offsetDependencyFromDeclaration(declaration),
  };
}

function selectorSelectOption(functionRef) {
  const option = functionRef?.options?.select;
  if (option?.type !== "literal") return "plural";
  if (option.value === "ordinal") return "ordinal";
  if (option.value === "exact") return "exact";
  return "plural";
}

function isPluralSelectorFunction(functionName) {
  return ["number", "integer", "percent", "offset"].includes(functionName);
}

function scenarioSelectorMetadata(sourceModel, targetModel, name, index) {
  const source = sourceModel ? selectorMetadata(sourceModel, name, index) : null;
  const target = targetModel ? selectorMetadata(targetModel, name, index) : null;
  return {
    name,
    isPlural: Boolean(source?.isPlural || target?.isPlural),
    functionName: source?.functionName ?? target?.functionName ?? null,
    select: source?.select ?? target?.select ?? "plural",
    offset: source?.offset ?? target?.offset ?? null,
    exactKeys: new Set([...scenarioExactNumericKeys(sourceModel, index), ...scenarioExactNumericKeys(targetModel, index)]),
    categoryKeys: new Set([...scenarioCategoryKeys(sourceModel, index), ...scenarioCategoryKeys(targetModel, index)]),
  };
}

function offsetDependencyFromDeclaration(declaration) {
  if (declaration?.type !== "local") return null;
  const functionRef = declaration.value?.function;
  if (functionRef?.name !== "offset" || declaration.value?.arg?.type !== "variable") return null;
  const delta = offsetDeltaFromOptions(functionRef.options ?? {});
  return delta == null ? null : { argName: declaration.value.arg.name, ...delta };
}

function offsetDeltaFromOptions(options) {
  const add = offsetOptionValue(options.add);
  const subtract = offsetOptionValue(options.subtract);
  if ((add == null && subtract == null) || (add != null && subtract != null)) return null;
  const option = add ?? subtract;
  const sign = add != null ? 1 : -1;
  if (option.kind === "literal") return { delta: sign * option.value };
  return { deltaVariable: option.name, deltaSign: sign };
}

function offsetOptionValue(option) {
  if (option?.type === "literal") {
    const value = Number(option.value);
    return Number.isInteger(value) ? { kind: "literal", value } : null;
  }
  if (option?.type === "variable" && option.name) return { kind: "variable", name: option.name };
  return null;
}

function variantContracts(model) {
  if (model.type !== "select") return [];
  return (model.variants ?? []).map(variantSignature).sort();
}

function variantSignature(variant) {
  return (variant.keys ?? []).map(variantKeyValue).join("\u0000");
}

function variantKeyValue(key) {
  return key?.type === "*" ? "*" : String(key?.value ?? "");
}

function variantKeySnippet(key) {
  if (key?.type === "*") return "*";
  const value = String(key?.value ?? "");
  if (isUnquotedLiteral(value)) return value;
  return quotedLiteralSnippet(value);
}

function isUnquotedLiteral(value) {
  return /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(value) || /^[A-Za-z_][A-Za-z0-9_]*$/u.test(value);
}

function variantKeyDetail(key, selector, locale) {
  const value = variantKeyValue(key);
  if (key?.type === "*") return { selector: selector?.name, key: value, sample: null, kind: "fallback", label: `$${selector?.name ?? "?"}: fallback` };
  if (selector?.isPlural && PLURAL_CATEGORIES.includes(value)) {
    const sample = sampleForPluralCategory(locale, value);
    const sampleText = sample == null ? "sample unavailable" : `sample ${sample}`;
    return { selector: selector.name, key: value, sample, kind: "category", label: `$${selector.name}: CLDR category ${value} (${sampleText})` };
  }
  if (selector?.isPlural && isNumericKey(value)) return { selector: selector.name, key: value, sample: Number(value), kind: "exact", label: `$${selector.name}: fixed value ${value}` };
  return { selector: selector?.name, key: value, sample: value, kind: "value", label: `$${selector?.name ?? "?"}: context value ${value}` };
}

function variantRowActionKind(keyDetails) {
  if (keyDetails.some((detail) => detail.kind === "exact")) return "fixed";
  if (keyDetails.some((detail) => detail.kind === "category")) return "category";
  if (keyDetails.some((detail) => detail.kind === "value")) return "context";
  return "fallback";
}

function variantRowActionVerb(kind) {
  if (kind === "fixed") return "Add fixed row";
  if (kind === "category") return "Add CLDR row";
  if (kind === "context") return "Add context row";
  return "Add fallback row";
}

function variantRowActionDescription(kind) {
  if (kind === "fixed") return "target row for a fixed selector value";
  if (kind === "category") return "target row for a CLDR plural category";
  if (kind === "context") return "target row for a source context value";
  return "target fallback row";
}

function patternPlaceholderNames(pattern) {
  return [...patternPlaceholderCounts(pattern).keys()].sort();
}

function patternPlaceholderCounts(pattern) {
  const counts = new Map();
  for (const part of pattern ?? []) countPartPlaceholders(part, counts);
  return counts;
}

function countPartPlaceholders(part, counts) {
  if (!part || typeof part === "string") return;
  if (part.type === "expression") {
    countOperandPlaceholder(part.arg, counts);
    countPayloadPlaceholders(part.function?.options, counts);
    countPayloadPlaceholders(part.attributes, counts);
  }
  if (part.type === "markup") {
    countPayloadPlaceholders(part.options, counts);
    countPayloadPlaceholders(part.attributes, counts);
  }
}

function countPayloadPlaceholders(payload, counts) {
  for (const value of Object.values(payload ?? {})) countOperandPlaceholder(value, counts);
}

function countOperandPlaceholder(operand, counts) {
  if (operand?.type !== "variable") return;
  counts.set(operand.name, (counts.get(operand.name) ?? 0) + 1);
}

function variantBodySnippet(placeholders) {
  const placeholderText = placeholders.map((name) => `{$${name}}`).join(" ");
  return placeholderText ? `TODO ${placeholderText}` : "TODO";
}

function targetLocaleVariantRowAction(scenario, fallbackPlaceholders, targetModel, { locale, functions }) {
  const body = variantBodySnippet(fallbackPlaceholders);
  const keyDetails = scenario.details.map(localeScenarioActionDetail);
  const localePlural = scenario.localePlural;
  return {
    kind: "variant-row",
    rowKind: "target-locale",
    subject: `target-locale:${scenario.signature}`,
    label: `Add target-locale CLDR row ${scenario.signature}`,
    snippet: `${scenario.keys.map(variantKeySnippet).join(" ")} {{${body}}}`,
    description: localePlural
      ? `Append target-locale ${localePlural.kind} category ${localePlural.category} row for $${localePlural.selector}.`
      : `Append target-locale CLDR row ${scenario.signature}.`,
    keyDetails,
    placeholders: fallbackPlaceholders,
    sampleValues: scenario.values,
    fallbackPreview: scenario.target ?? renderTargetPreview(targetModel, scenario.values, { locale, functions }),
    localePlural,
  };
}

function targetLocaleSpecificRowActions(scenario, fallbackPlaceholders, targetModel, { locale, functions }) {
  const suggestions = scenario.localePlural?.suggestions ?? [];
  const body = variantBodySnippet(fallbackPlaceholders);
  return suggestions.map((suggestion) => {
    const keyDetails = localeSuggestionActionDetails(scenario, suggestion);
    const sampleValues = variantSampleValues(keyDetails, scenario.values);
    const signature = suggestion.keys.join(" ");
    return {
      kind: "variant-row",
      rowKind: "target-locale",
      subject: `target-locale-specific:${signature}`,
      label: `Add specific target-locale row ${signature}`,
      snippet: `${suggestion.keys.map((key) => variantKeySnippet(variantKeyFromValue(key))).join(" ")} {{${body}}}`,
      description: `Append target-locale specific row ${suggestion.label ?? signature}.`,
      keyDetails,
      placeholders: fallbackPlaceholders,
      sampleValues,
      fallbackPreview: renderTargetPreview(targetModel, sampleValues, { locale, functions }),
      localePlural: scenario.localePlural,
    };
  });
}

function localeSuggestionActionDetails(scenario, suggestion) {
  return suggestion.keys.map((key, index) => {
    const base = scenario.details[index] ?? {};
    if (key === base.key) return localeScenarioActionDetail(base);
    if (key === "*") return localeScenarioActionDetail({ ...base, key, kind: "fallback", sample: null });
    return localeScenarioActionDetail({ ...base, key, kind: "value", sample: key });
  });
}

function variantKeyFromValue(value) {
  return value === "*" ? { type: "*" } : { type: "literal", value };
}

function targetFallbackVariant(model) {
  return (model?.variants ?? []).find((variant) => (variant.keys ?? []).every((key) => key?.type === "*"));
}

function localeScenarioActionDetail(detail) {
  const sampleText = detail.sample == null ? "sample unavailable" : `sample ${detail.sample}`;
  if (detail.kind === "exact") return { ...detail, label: `$${detail.selector}: fixed value ${detail.key}` };
  if (detail.kind === "category") return { ...detail, label: `$${detail.selector}: CLDR category ${detail.key} (${sampleText})` };
  if (detail.kind === "fallback") return { ...detail, label: `$${detail.selector}: fallback` };
  return { ...detail, label: `$${detail.selector}: context value ${detail.key}` };
}

function variantSampleValues(keyDetails, values) {
  const sampleValues = { ...(values && typeof values === "object" && !Array.isArray(values) ? values : {}) };
  for (const detail of keyDetails) {
    if (!detail.selector || detail.sample == null) continue;
    sampleValues[detail.selector] = detail.sample;
  }
  return sampleValues;
}

function renderTargetPreview(model, values, { locale, functions }) {
  try {
    return { value: formatMessage(model, values, { locale, functions, bidiIsolation: "default" }) };
  } catch (error) {
    return { error: error.message };
  }
}

function addScenarioVariants(variants, model, origin) {
  if (model == null) return;
  for (const variant of model.variants ?? []) {
    const signature = variantSignature(variant);
    const scenario = variants.get(signature) ?? {
      keys: variant.keys ?? [],
      origins: new Set(),
    };
    scenario.origins.add(origin);
    variants.set(signature, scenario);
  }
}

function addLocalePluralScenarioVariants(variants, selectorModel, selectors, locale) {
  if (selectorModel?.type !== "select") return;
  selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return;
    for (const category of pluralCategorySetForLocale(locale, kind)) {
      if (category === "other") continue;
      if (!categoryNeedsLocalePluralRow(locale, category, kind, selector.exactKeys)) continue;
      const keys = selectors.map(() => ({ type: "*" }));
      keys[selectorIndex] = { type: "literal", value: category };
      const signature = variantSignature({ keys });
      if (variants.has(signature)) continue;
      const combinationHints = localePluralCombinationSuggestions(selectorModel, selectorIndex, category, variants);
      variants.set(signature, {
        keys,
        origins: new Set(["locale"]),
        localePlural: {
          selector: selector.name,
          category,
          kind,
          locale,
          suggestions: combinationHints.suggestions,
          suggestionsTruncated: combinationHints.truncated,
        },
      });
    }
  });
}

function localePluralCombinationSuggestions(model, pluralIndex, category, existingVariants, limit = 6) {
  const selectors = selectorSequence(model);
  const dimensions = selectors.map((_selector, index) => {
    if (index === pluralIndex) return [category];
    const values = nonFallbackSelectorValuesFromModel(model, index);
    return values.length ? values : ["*"];
  });
  if (dimensions.every((values, index) => index === pluralIndex || values.length === 1 && values[0] === "*")) {
    return { suggestions: [], truncated: false };
  }
  const wildcardSignature = variantSignatureFromValues(selectors.map((_selector, index) => index === pluralIndex ? category : "*"));
  const suggestions = cartesian(dimensions)
    .filter((keys) => {
      const signature = variantSignatureFromValues(keys);
      return signature !== wildcardSignature && !existingVariants.has(signature);
    })
    .map((keys) => ({ keys, label: scenarioSuggestionLabel(selectors, keys) }));
  return {
    suggestions: suggestions.slice(0, limit),
    truncated: suggestions.length > limit,
  };
}

function scenarioSuggestionLabel(selectors, keys) {
  return selectors.map((selector, index) => {
    const key = keys[index] ?? "*";
    return `$${selector}: ${key === "*" ? "fallback" : key}`;
  }).join(", ");
}

function nonFallbackSelectorValuesFromModel(model, selectorIndex) {
  const values = [];
  for (const variant of model?.variants ?? []) {
    const key = variantKeyValue(variant.keys?.[selectorIndex]);
    if (!key || key === "*" || values.includes(key)) continue;
    values.push(key);
  }
  return values;
}

function variantSignatureFromValues(keys) {
  return keys.join("\u0000");
}

function cartesian(dimensions) {
  return dimensions.reduce(
    (rows, values) => rows.flatMap((row) => values.map((value) => [...row, value])),
    [[]],
  );
}

function scenarioValuesForKeys(_model, selectors, keys, values, locale) {
  const scenarioValues = { ...(values && typeof values === "object" && !Array.isArray(values) ? values : {}) };
  const samples = [];
  (keys ?? []).forEach((key, index) => {
    const selector = selectors[index];
    if (!selector || selector.offset) return;
    const sample = scenarioSampleValue(key, selector, scenarioValues[selector.name], locale);
    scenarioValues[selector.name] = sample.value;
    samples[index] = sample;
  });
  (keys ?? []).forEach((key, index) => {
    const selector = selectors[index];
    if (!selector?.offset || !isNumericKey(variantKeyValue(key))) return;
    const exact = Number(variantKeyValue(key));
    const delta = offsetDeltaForScenario(selector.offset, scenarioValues);
    scenarioValues[selector.offset.argName] = exact - delta;
    scenarioValues[selector.name] = exact;
    samples[index] = { value: exact, kind: "exact" };
  });
  (keys ?? []).forEach((key, index) => {
    const selector = selectors[index];
    const value = variantKeyValue(key);
    if (!selector?.offset || value === "*" || isNumericKey(value) || !PLURAL_CATEGORIES.includes(value)) return;
    const sample = sampleForPluralCategory(locale, value, selector.exactKeys);
    if (sample == null) return;
    const delta = offsetDeltaForScenario(selector.offset, scenarioValues);
    scenarioValues[selector.offset.argName] = sample - delta;
    scenarioValues[selector.name] = sample;
    samples[index] = { value: sample, kind: "category" };
  });
  (keys ?? []).forEach((key, index) => {
    const selector = selectors[index];
    if (variantKeyValue(key) !== "*" || !selector?.offset) return;
    const dependencyIndex = selectors.findIndex((item) => item.name === selector.offset.argName);
    if (variantKeyValue((keys ?? [])[dependencyIndex] ?? { type: "*" }) !== "*") {
      scenarioValues[selector.name] = offsetValueFromScenario(selector, scenarioValues);
      samples[index] = { value: scenarioValues[selector.name], kind: "fallback" };
      return;
    }
    scenarioValues[selector.offset.argName] = sampleForOffsetFallback(selectors, index, selector.offset, scenarioValues, scenarioValues[selector.offset.argName], locale);
    scenarioValues[selector.name] = offsetValueFromScenario(selector, scenarioValues);
    samples[index] = { value: scenarioValues[selector.name], kind: "fallback" };
  });
  return { values: scenarioValues, samples };
}

function offsetValueFromScenario(selector, values) {
  const base = Number(values[selector.offset.argName]);
  const delta = offsetDeltaForScenario(selector.offset, values);
  return Number.isFinite(base) ? base + delta : values[selector.name];
}

function offsetDeltaForScenario(offset, values) {
  if (offset.delta != null) return offset.delta;
  const value = Number(values[offset.deltaVariable]);
  const magnitude = Number.isInteger(value) ? value : 1;
  values[offset.deltaVariable] = magnitude;
  return offset.deltaSign * magnitude;
}

function sampleForOffsetFallback(selectors, selectorIndex, offset, values, currentValue, locale) {
  const delta = offsetDeltaForScenario(offset, values);
  const dependency = selectors.find((selector) => selector.name === offset.argName);
  const dependencyExactKeys = dependency?.exactKeys ?? new Set();
  const offsetSelector = selectors[selectorIndex];
  const offsetExactKeys = offsetSelector?.exactKeys ?? new Set();
  const offsetCategoryKeys = offsetSelector?.categoryKeys ?? new Set();
  const kind = selectorPluralKind(offsetSelector) ?? "cardinal";
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    const shifted = candidate + delta;
    if (dependencyExactKeys.has(canonicalNumericKey(candidate))) continue;
    if (shifted < 0) continue;
    if (offsetExactKeys.has(canonicalNumericKey(shifted))) continue;
    if (offsetCategoryKeys.has(selectPluralCategory(locale, shifted, kind))) continue;
    return candidate;
  }
  return currentValue ?? 2;
}

function scenarioSampleValue(key, selector, currentValue, locale) {
  if (key?.type === "literal" && isNumericKey(key.value)) return { value: Number(key.value), kind: "exact" };
  const kind = selectorPluralKind(selector) ?? "cardinal";
  if (key?.type === "*") {
    if (!selector.isPlural) return { value: currentValue ?? "other", kind: "fallback" };
    return { value: sampleForPluralCategory(locale, "other", selector.exactKeys, kind) ?? currentValue ?? 2, kind: "fallback" };
  }
  const value = String(key?.value ?? "");
  if (!selector.isPlural || !PLURAL_CATEGORIES.includes(value)) return { value, kind: "value" };
  const sample = sampleForPluralCategory(locale, value, selector.exactKeys, kind);
  if (sample == null) {
    return {
      value: currentValue ?? 2,
      kind: "category",
      note: `no ${value} sample for ${locale}; variant may be unreachable`,
    };
  }
  return { value: sample, kind: "category" };
}

function scenarioExactNumericKeys(model, selectorIndex) {
  if (model == null || selectorIndex < 0) return new Set();
  return new Set(
    (model.variants ?? [])
      .map((variant) => variant.keys?.[selectorIndex]?.value)
      .filter(isNumericKey)
      .map(canonicalNumericKey),
  );
}

function scenarioCategoryKeys(model, selectorIndex) {
  if (model == null || selectorIndex < 0) return new Set();
  return new Set(
    (model.variants ?? [])
      .map((variant) => variant.keys?.[selectorIndex]?.value)
      .filter((value) => PLURAL_CATEGORIES.includes(value)),
  );
}

function scenarioStatus(scenario, selectors, locale, source, target, allowedSelectorValues = new Map()) {
  const origins = scenario.origins;
  if (origins.has("locale")) return "locale-missing";
  if (origins.has("target") && !origins.has("source")) {
    const assessment = targetOnlyVariantAssessment(scenario, selectors, locale, allowedSelectorValues);
    return assessment.status === "locale-recommended" ? "locale-recommended" : "target-only";
  }
  if (!origins.has("source")) return "target-only";
  if (!origins.has("target")) return "source-only";
  if (source?.error || target?.error) return "render-error";
  return "covered";
}

function scenarioStatusLabel(status) {
  if (status === "source-only") return "source row missing";
  if (status === "target-only") return "target-specific row";
  if (status === "locale-missing") return "target-locale CLDR row missing";
  if (status === "locale-recommended") return "target-locale CLDR row present";
  if (status === "render-error") return "render error";
  return "covered";
}

function pluralCoverageCategoryState(category, targetKeys, uncoveredSample = null, sample = null, exactKeys = new Set()) {
  if (targetKeys.has(category)) return "explicit";
  if (uncoveredSample == null && sample != null && exactKeys.has(canonicalNumericKey(sample))) return "exact";
  if (targetKeys.has("*")) return category === "other" ? "fallback-other" : "fallback";
  return "missing";
}

function scenarioDetailKindLabel(detail) {
  if (detail.kind === "exact") return "fixed value";
  if (detail.kind === "category") return "CLDR category";
  if (detail.kind === "fallback") return "fallback";
  return "context value";
}

function scenarioDetailSampleLabel(detail) {
  return detail.sample == null ? "no sample" : `sample ${detail.sample}`;
}

function scenarioRowKind(scenario, details) {
  if (scenario.localePlural) return "target-locale";
  if (details.some((detail) => detail.kind === "exact")) return "fixed";
  if (details.some((detail) => detail.kind === "category")) return "category";
  if (details.some((detail) => detail.kind === "value")) return "context";
  return "fallback";
}

function scenarioRowKindLabel(kind) {
  if (kind === "target-locale") return "target-locale CLDR row";
  if (kind === "fixed") return "fixed row";
  if (kind === "category") return "CLDR row";
  if (kind === "context") return "context row";
  return "fallback row";
}

function pluralCoverageStateLabel(state) {
  if (state === "explicit") return "explicit row";
  if (state === "fallback-other") return "fallback";
  if (state === "fallback") return "fallback covers";
  return "missing";
}

function scenarioPreviewText(preview) {
  if (preview == null) return "not a select message";
  return preview.error ?? preview.value;
}

function sampleForPluralCategory(locale, category, excluded = new Set(), kind = "cardinal") {
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    if (excluded.has(canonicalNumericKey(candidate))) continue;
    if (selectPluralCategory(locale, candidate, kind) === category) return candidate;
  }
  return null;
}

function categoryNeedsLocalePluralRow(locale, category, kind, exactKeys = new Set()) {
  return sampleForPluralCategory(locale, category, exactKeys, kind) != null;
}

function isNumericKey(value) {
  return /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(value);
}

function canonicalNumericKey(value) {
  return String(Number(value));
}

const PLURAL_CATEGORIES = ["zero", "one", "two", "few", "many", "other"];
const PLURAL_SAMPLE_CANDIDATES = [
  0, 1, 2, 3, 4, 5, 10, 11, 12, 20, 21, 22, 100, 101,
  1_000, 10_000, 100_000, 1_000_000, 2_000_000,
  1.1, 1.5, 2.1, 5.5,
];

function selectPluralCategory(locale, value, kind = "cardinal") {
  return kind === "ordinal" ? selectOrdinal(locale, value) : selectCardinal(locale, value);
}

function pluralCategorySetForLocale(locale, kind = "cardinal") {
  const categories = new Set();
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    try {
      categories.add(selectPluralCategory(locale, candidate, kind));
    } catch {
      categories.add("other");
    }
  }
  return new Set(PLURAL_CATEGORIES.filter((category) => categories.has(category)));
}

function compareSet(kind, sourceItems, targetItems) {
  const source = new Set(sourceItems);
  const target = new Set(targetItems);
  return [
    ...[...target].filter((item) => !source.has(item)).map((item) => diagnostic(`new-${kind}`, item, `Target adds ${kind} ${item}.`)),
    ...[...source].filter((item) => !target.has(item)).map((item) => diagnostic(`missing-source-${kind}`, item, `Target is missing source ${kind} ${item}.`)),
  ];
}

function compareSelectors(sourceSelectors, targetSelectors) {
  const diagnostics = [];
  const sourceNames = sourceSelectors.map((selector) => selector.name);
  const targetNames = targetSelectors.map((selector) => selector.name);
  diagnostics.push(...compareSet("selector", sourceNames, targetNames));
  if (sourceNames.join("\u0000") !== targetNames.join("\u0000")) {
    diagnostics.push(diagnostic("selector-order-mismatch", targetNames.join(","), "Target selector order differs from source."));
  }
  const sourceByName = new Map(sourceSelectors.map((selector) => [selector.name, selector]));
  for (const target of targetSelectors) {
    const source = sourceByName.get(target.name);
    if (source && source.annotation !== target.annotation) {
      diagnostics.push(diagnostic("selector-annotation-mismatch", target.name, `Target selector $${target.name} annotation differs from source.`));
    }
  }
  return diagnostics;
}

function compareMarkup(sourceMarkup, targetMarkup) {
  const diagnostics = [];
  const sourceByName = new Map(sourceMarkup.map((item) => [item.name, item]));
  const targetByName = new Map(targetMarkup.map((item) => [item.name, item]));
  diagnostics.push(...compareSet("markup", [...sourceByName.keys()], [...targetByName.keys()]));
  for (const [name, target] of targetByName.entries()) {
    const source = sourceByName.get(name);
    if (!source) continue;
    diagnostics.push(...compareSet(`markup-option`, source.options, target.options).map((item) => ({ ...item, subject: `${name}.${item.subject}` })));
    diagnostics.push(...compareSet(`markup-attribute`, source.attributes, target.attributes).map((item) => ({ ...item, subject: `${name}.${item.subject}` })));
    if (setSignature(source.kinds) !== setSignature(target.kinds)) {
      diagnostics.push(diagnostic("markup-shape-mismatch", name, `Target markup #${name} open/close/standalone shape differs from source.`));
    }
  }
  return diagnostics;
}

function compareVariantPlaceholders(sourceModel, targetModel) {
  const targetBySignature = new Map(variantPlaceholderContracts(targetModel).map((item) => [item.signature, item]));
  const diagnostics = [];
  for (const source of variantPlaceholderContracts(sourceModel)) {
    const target = targetBySignature.get(source.signature);
    if (!target) continue;
    for (const missing of missingPlaceholderCounts(source.placeholders, target.placeholders)) {
      diagnostics.push(diagnostic(
        "variant-missing-placeholder",
        missing.name,
        `Target variant ${source.label} omits source placeholder {$${missing.name}}; this is valid if intentional.`,
      ));
    }
  }
  return diagnostics;
}

function variantPlaceholderContracts(model) {
  if (model?.type === "select") {
    return (model.variants ?? []).map((variant) => {
      const label = (variant.keys ?? []).map(variantKeyValue).join(" ") || "fallback";
      return {
        signature: variantSignature(variant),
        label,
        placeholders: patternPlaceholderCounts(variant.value ?? []),
      };
    });
  }
  if (model?.type === "message") {
    return [{ signature: "message", label: "message", placeholders: patternPlaceholderCounts(model.pattern ?? []) }];
  }
  return [];
}

function missingPlaceholderCounts(sourceCounts, targetCounts) {
  const missing = [];
  for (const [name, count] of sourceCounts.entries()) {
    const delta = count - (targetCounts.get(name) ?? 0);
    if (delta > 0) missing.push({ name, count: delta });
  }
  return missing;
}

function compareVariants(sourceModel, targetModel, locale) {
  const sourceVariants = variantContracts(sourceModel);
  const targetVariants = variantContracts(targetModel);
  if (sourceVariants.length === 0 && targetVariants.length === 0) return [];
  const diagnostics = [];
  const source = new Set(sourceVariants);
  const target = new Set(targetVariants);
  const allowedSelectorValues = selectorValueAllowlistFromModels(sourceModel);
  for (const signature of source) {
    if (!target.has(signature)) diagnostics.push(diagnostic("missing-source-variant", signature, `Target is missing source variant ${signature}.`));
  }
  const selectors = selectorSequence(targetModel).map((name, index) => selectorMetadata(targetModel, name, index));
  for (const variant of targetModel.variants ?? []) {
    const signature = variantSignature(variant);
    if (source.has(signature)) continue;
    const assessment = targetOnlyVariantAssessment(variant, selectors, locale, allowedSelectorValues);
    if (assessment.status === "locale-recommended") continue;
    diagnostics.push(diagnostic("target-only-variant", signature, assessment.message));
  }
  diagnostics.push(...missingLocalePluralDiagnostics(targetModel, selectors, locale));
  diagnostics.push(...numericVariantOverlaps(targetModel, selectors, locale).map((overlap) =>
    diagnostic(
      "overlapping-numeric-variant",
      overlapSignature(overlap),
      `Target has both exact $${overlap.selector.name}: ${overlap.exact} and CLDR ${overlap.kind} category ${overlap.category} rows with the same surrounding keys; if both match, the earlier row wins.`,
    ),
  ));
  diagnostics.push(...selectorPriorityVariantOverlaps(targetModel, selectors, locale).map((overlap) =>
    diagnostic(
      "selector-priority-overlap",
      selectorPriorityOverlapSignature(overlap),
      `Target rows ${variantSignature(overlap.left).replaceAll("\u0000", " ")} and ${variantSignature(overlap.right).replaceAll("\u0000", " ")} can both match; $${overlap.prioritySelector.name} is the first differing selector, so ${variantSignature(overlap.winner).replaceAll("\u0000", " ")} wins.`,
    ),
  ));
  return diagnostics;
}

function scenarioOverlapNotesFromModels(sourceModel, targetModel, selectors, locale) {
  const notes = new Map();
  for (const [model, origin] of [[sourceModel, "source"], [targetModel, "target"]]) {
    if (model?.type !== "select") continue;
    for (const overlap of numericVariantOverlaps(model, selectors, locale)) {
      const winner = variantSignature(overlap.winner).replaceAll("\u0000", " ");
      const note = `${origin}: exact $${overlap.selector.name}: ${overlap.exact} overlaps ${overlap.kind} category ${overlap.category}; ${winner} row appears first and wins.`;
      addScenarioOverlapNote(notes, overlap.left, note);
      addScenarioOverlapNote(notes, overlap.right, note);
    }
    for (const overlap of selectorPriorityVariantOverlaps(model, selectors, locale)) {
      const left = variantSignature(overlap.left).replaceAll("\u0000", " ");
      const right = variantSignature(overlap.right).replaceAll("\u0000", " ");
      const winner = variantSignature(overlap.winner).replaceAll("\u0000", " ");
      const note = `${origin}: rows ${left} and ${right} can both match; $${overlap.prioritySelector.name} is the first differing selector, so ${winner} wins.`;
      addScenarioOverlapNote(notes, overlap.left, note);
      addScenarioOverlapNote(notes, overlap.right, note);
    }
  }
  return notes;
}

function addScenarioOverlapNote(notes, variant, note) {
  const signature = variantSignature(variant);
  notes.set(signature, [...new Set([...(notes.get(signature) ?? []), note])]);
}

function targetOnlyVariantAssessment(variant, selectors, locale, allowedSelectorValues = new Map()) {
  const signature = variantSignature(variant);
  const categoryDetails = (variant.keys ?? [])
    .map((key, index) => targetOnlyPluralCategoryDetail(key, selectors[index], locale))
    .filter(Boolean);
  const customKeys = (variant.keys ?? [])
    .filter((key, index) => {
      const value = variantKeyValue(key);
      const selector = selectors[index];
      return value !== "*"
        && !targetOnlyPluralCategoryDetail(key, selector, locale)
        && !isAllowedSelectorContextValue(value, selector, allowedSelectorValues);
    });
  if (customKeys.length) {
    return {
      status: "review",
      message: `Target adds variant ${signature}; fixed or custom selector values are not inferred from CLDR plural rules, so confirm this row is intentional.`,
    };
  }
  if (!categoryDetails.length) {
    return {
      status: "review",
      message: `Target adds variant ${signature}; confirm this row is intentional.`,
    };
  }
  const unsupported = categoryDetails.filter((detail) => !detail.supported);
  if (!unsupported.length) {
    const details = categoryDetails
      .map((detail) => `$${detail.selector}: ${detail.category}`)
      .join(", ");
    return {
      status: "locale-recommended",
      message: `Target adds locale-recommended CLDR row ${signature} for ${locale} (${details}).`,
    };
  }
  const detailText = unsupported
    .map((detail) => {
      const categories = detail.categories.length ? detail.categories.join(", ") : "other";
      return `$${detail.selector}: ${detail.category} is not a ${detail.kind} category for ${locale}; expected ${categories}`;
    })
    .join("; ");
  return {
    status: "review",
    message: `Target adds variant ${signature}, but ${detailText}.`,
  };
}

function isAllowedSelectorContextValue(value, selector, allowedSelectorValues) {
  if (!selector || selectorPluralKind(selector)) return false;
  return allowedSelectorValues.get(selector.name)?.has(value) ?? false;
}

function selectorValueAllowlistFromModels(...models) {
  const values = new Map();
  for (const model of models) {
    if (model?.type !== "select") continue;
    selectorSequence(model).forEach((name, selectorIndex) => {
      if (!name) return;
      const set = values.get(name) ?? new Set();
      for (const variant of model.variants ?? []) {
        const key = variantKeyValue((variant.keys ?? [])[selectorIndex] ?? { type: "*" });
        if (key !== "*") set.add(key);
      }
      values.set(name, set);
    });
  }
  return values;
}

function targetOnlyPluralCategoryDetail(key, selector, locale) {
  const category = variantKeyValue(key);
  const kind = selectorPluralKind(selector);
  if (!kind || !PLURAL_CATEGORIES.includes(category)) return null;
  const categories = [...pluralCategorySetForLocale(locale, kind)];
  return {
    selector: selector.name,
    category,
    kind,
    categories,
    supported: categories.includes(category),
  };
}

function selectorPluralKind(selector) {
  if (!selector?.isPlural || selector.select === "exact") return null;
  return selector.select === "ordinal" ? "ordinal" : "cardinal";
}

function missingLocalePluralDiagnostics(targetModel, selectors, locale) {
  if (targetModel?.type !== "select") return [];
  const diagnostics = [];
  selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return;
    const existing = new Set(
      (targetModel.variants ?? [])
        .map((variant) => variantKeyValue((variant.keys ?? [])[selectorIndex] ?? { type: "*" })),
    );
    const exactKeys = scenarioExactNumericKeys(targetModel, selectorIndex);
    const missing = [...pluralCategorySetForLocale(locale, kind)]
      .filter((category) =>
        category !== "other"
        && !existing.has(category)
        && categoryNeedsLocalePluralRow(locale, category, kind, exactKeys),
      );
    for (const category of missing) {
      diagnostics.push(diagnostic(
        "missing-locale-plural-variant",
        `${selector.name}:${category}`,
        `Target locale ${locale} has CLDR ${kind} category ${category} for $${selector.name}, but the target has no ${selector.name}: ${category} variant; fallback may still cover it.`,
      ));
    }
  });
  return diagnostics;
}

function numericVariantOverlaps(model, selectors, locale) {
  const overlaps = [];
  for (let leftIndex = 0; leftIndex < (model.variants ?? []).length; leftIndex++) {
    for (let rightIndex = leftIndex + 1; rightIndex < (model.variants ?? []).length; rightIndex++) {
      const left = model.variants[leftIndex];
      const right = model.variants[rightIndex];
      for (let selectorIndex = 0; selectorIndex < selectors.length; selectorIndex++) {
        const overlap = numericCategoryOverlap(left.keys?.[selectorIndex], right.keys?.[selectorIndex], selectors[selectorIndex], locale);
        if (!overlap || !sameSurroundingVariantKeys(left.keys, right.keys, selectorIndex)) continue;
        overlaps.push({
          ...overlap,
          selector: selectors[selectorIndex],
          left,
          right,
          winner: left,
        });
      }
    }
  }
  return overlaps;
}

function selectorPriorityVariantOverlaps(model, selectors, locale) {
  const overlaps = [];
  for (let leftIndex = 0; leftIndex < (model?.variants ?? []).length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < (model.variants ?? []).length; rightIndex += 1) {
      const left = model.variants[leftIndex];
      const right = model.variants[rightIndex];
      if (!variantKeysCanOverlap(left.keys ?? [], right.keys ?? [], selectors, locale)) continue;
      const leftRank = variantSpecificityRank(left.keys ?? []);
      const rightRank = variantSpecificityRank(right.keys ?? []);
      if (!hasMixedSpecificity(leftRank, rightRank)) continue;
      const comparison = compareRank(leftRank, rightRank);
      if (comparison === 0) continue;
      const priorityIndex = firstDifferingIndex(leftRank, rightRank);
      overlaps.push({
        left,
        right,
        winner: comparison > 0 ? left : right,
        prioritySelector: selectors[priorityIndex],
      });
    }
  }
  return overlaps;
}

function variantKeysCanOverlap(leftKeys, rightKeys, selectors, locale) {
  const length = Math.max(leftKeys.length, rightKeys.length, selectors.length);
  for (let index = 0; index < length; index += 1) {
    const left = variantKeyValue(leftKeys[index] ?? { type: "*" });
    const right = variantKeyValue(rightKeys[index] ?? { type: "*" });
    if (left === "*" || right === "*" || left === right) continue;
    if (numericCategoryOverlap(leftKeys[index], rightKeys[index], selectors[index], locale)) continue;
    return false;
  }
  return true;
}

function variantSpecificityRank(keys) {
  return (keys ?? []).map((key) => variantKeyValue(key) === "*" ? 0 : 1);
}

function hasMixedSpecificity(leftRank, rightRank) {
  let leftMoreSpecific = false;
  let rightMoreSpecific = false;
  const length = Math.max(leftRank.length, rightRank.length);
  for (let index = 0; index < length; index += 1) {
    const delta = (leftRank[index] ?? 0) - (rightRank[index] ?? 0);
    if (delta > 0) leftMoreSpecific = true;
    if (delta < 0) rightMoreSpecific = true;
  }
  return leftMoreSpecific && rightMoreSpecific;
}

function firstDifferingIndex(leftRank, rightRank) {
  const length = Math.max(leftRank.length, rightRank.length);
  for (let index = 0; index < length; index += 1) {
    if ((leftRank[index] ?? 0) !== (rightRank[index] ?? 0)) return index;
  }
  return 0;
}

function compareRank(left, right) {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return left.length - right.length;
}

function overlapSignature(overlap) {
  return [
    overlap.selector.name,
    overlap.exact,
    overlap.category,
    variantSignature(overlap.left),
    variantSignature(overlap.right),
  ].join("\u0000");
}

function selectorPriorityOverlapSignature(overlap) {
  return [
    variantSignature(overlap.left),
    variantSignature(overlap.right),
    overlap.prioritySelector?.name ?? "",
  ].join("\u0000");
}

function numericCategoryOverlap(leftKey, rightKey, selector, locale) {
  const left = variantKeyValue(leftKey);
  const right = variantKeyValue(rightKey);
  const leftCategory = categoryForExactNumericKey(left, selector, locale);
  if (leftCategory && leftCategory.category === right) return { exact: left, ...leftCategory };
  const rightCategory = categoryForExactNumericKey(right, selector, locale);
  if (rightCategory && rightCategory.category === left) return { exact: right, ...rightCategory };
  return null;
}

function categoryForExactNumericKey(key, selector, locale) {
  const kind = selectorPluralKind(selector);
  if (!kind || !isNumericKey(key)) return null;
  try {
    return { category: selectPluralCategory(locale, Number(key), kind), kind };
  } catch {
    return null;
  }
}

function sameSurroundingVariantKeys(leftKeys = [], rightKeys = [], exceptIndex) {
  const length = Math.max(leftKeys.length, rightKeys.length);
  for (let index = 0; index < length; index++) {
    if (index === exceptIndex) continue;
    if (variantKeyValue(leftKeys[index]) !== variantKeyValue(rightKeys[index])) return false;
  }
  return true;
}

function functionLabel(functionRef) {
  return functionRef?.name ? ` :${functionRef.name}` : "";
}

function setSignature(values) {
  return [...values].sort().join("\u0000");
}

function diagnostic(code, subject, message) {
  return { code, subject, message, severity: "warning" };
}
