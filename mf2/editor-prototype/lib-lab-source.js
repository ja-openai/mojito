import { basicSetup, EditorView } from "codemirror";
import { forceLinting, linter, lintGutter } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { history, redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { baseKeymap, toggleMark } from "prosemirror-commands";
import { Schema } from "prosemirror-model";
import { EditorState as ProseMirrorState, Plugin } from "prosemirror-state";
import { EditorView as ProseMirrorView } from "prosemirror-view";

const samples = {
  plural: `.input {$count :number}
.match $count
one {{You have {$count} file}}
* {{You have {$count} files}}`,
  gender: `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
female one {{She reviewed {$count} file}}
male * {{He reviewed {$count} files}}
female * {{She reviewed {$count} files}}
* * {{They reviewed {$count} files}}`,
  markup: "Tap {#link href=$url @title=|Profile|}profile{/link}. {$name :string @kind=person}",
};

const defaultArgs = {
  count: 2,
  gender: "unknown",
  name: "Mojito",
  url: "/people/mojito",
};

const schema = new Schema({
  nodes: {
    doc: { content: "block+" },
    paragraph: {
      content: "inline*",
      group: "block",
      parseDOM: [{ tag: "p" }],
      toDOM: () => ["p", 0],
    },
    text: { group: "inline" },
    placeholder: {
      atom: true,
      group: "inline",
      inline: true,
      attrs: { name: {} },
      selectable: true,
      parseDOM: [
        {
          tag: "span[data-placeholder]",
          getAttrs: (node) => ({ name: node.getAttribute("data-placeholder") }),
        },
      ],
      toDOM: (node) => [
        "span",
        {
          class: "placeholder-chip",
          "data-placeholder": node.attrs.name,
          contenteditable: "false",
        },
        `{$${node.attrs.name}}`,
      ],
    },
  },
  marks: {
    strong: {
      parseDOM: [{ tag: "strong" }, { tag: "b" }],
      toDOM: () => ["strong", 0],
    },
  },
});

const state = {
  args: { ...defaultArgs },
  editorModel: null,
  activeVariant: 0,
  pluralMetadata: null,
  sourceContract: null,
  updatingFromCode: false,
};

const elements = {
  arguments: document.querySelector("#arguments"),
  codemirror: document.querySelector("#codemirror"),
  diagnostics: document.querySelector("#diagnostics"),
  insertPlaceholder: document.querySelector("#insertPlaceholder"),
  locale: document.querySelector("#locale"),
  model: document.querySelector("#model"),
  pluralTools: document.querySelector("#pluralTools"),
  prosemirror: document.querySelector("#prosemirror"),
  rendered: document.querySelector("#rendered"),
  sourceCodemirror: document.querySelector("#sourceCodemirror"),
  status: document.querySelector("#status"),
  variantTabs: document.querySelector("#variantTabs"),
};

let cmView;
let pmView;
let sourceCmView;

initialize();

function initialize() {
  sourceCmView = new EditorView({
    parent: elements.sourceCodemirror,
    state: EditorState.create({
      doc: samples.plural,
      extensions: [
        basicSetup,
        lintGutter(),
        sourceMf2Linter(),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            void refreshSourceContract(sourceCmView.state.doc.toString());
          }
        }),
      ],
    }),
  });

  cmView = new EditorView({
    parent: elements.codemirror,
    state: EditorState.create({
      doc: samples.plural,
      extensions: [
        basicSetup,
        lintGutter(),
        rustMf2Linter(),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !state.updatingFromCode) {
            void refreshFromSource(cmView.state.doc.toString());
          }
        }),
      ],
    }),
  });

  document.querySelectorAll("[data-sample]").forEach((button) => {
    button.addEventListener("click", () => {
      replaceSourceCode(samples[button.dataset.sample]);
      replaceCode(samples[button.dataset.sample]);
      state.activeVariant = 0;
      void refreshSourceContract(samples[button.dataset.sample]);
      void refreshFromSource(samples[button.dataset.sample]);
    });
  });
  elements.locale.addEventListener("change", () => {
    void refreshPluralMetadata();
    forceLinting(cmView);
    void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true });
  });
  elements.insertPlaceholder.addEventListener("click", insertFirstPlaceholder);
  void refreshSourceContract(samples.plural);
  void refreshPluralMetadata();
  void refreshFromSource(samples.plural);
  globalThis.mf2Lab = {
    setSource: async (source) => {
      replaceSourceCode(source);
      await refreshSourceContract(source);
    },
    setTarget: async (source) => {
      replaceCode(source);
      await refreshFromSource(source);
    },
  };
}

function rustMf2Linter() {
  return linter(
    async (view) => {
      const response = await parseWithRust(view.state.doc.toString());
      return codeMirrorDiagnostics(response, view.state.doc.toString());
    },
    { delay: 250 },
  );
}

function sourceMf2Linter() {
  return linter(
    async (view) => {
      const response = await parseWithRust(view.state.doc.toString());
      return [...(response.diagnostics ?? []), ...(response.formatErrors ?? [])].map((diagnostic) => {
        const docLength = view.state.doc.length;
        const from = clampOffset(diagnostic.start, docLength);
        const fallbackEnd = docLength > from ? from + 1 : from;
        const to = clampOffset(diagnostic.end ?? fallbackEnd, docLength);
        return {
          from,
          to: Math.max(from, to),
          severity: diagnostic.severity === "warning" ? "warning" : "error",
          source: "Rust MF2 source",
          message: `${diagnostic.code}: ${diagnostic.message}`,
        };
      });
    },
    { delay: 250 },
  );
}

function codeMirrorDiagnostics(response, source) {
  return validationDiagnostics(response, source).map((diagnostic) => {
    const docLength = source.length;
    const from = clampOffset(diagnostic.start, docLength);
    const fallbackEnd = docLength > from ? from + 1 : from;
    const to = clampOffset(diagnostic.end ?? fallbackEnd, docLength);
    return {
      from,
      to: Math.max(from, to),
      severity: diagnostic.severity === "warning" ? "warning" : "error",
      source: "Rust MF2",
      message: `${diagnostic.code}: ${diagnostic.message}`,
    };
  });
}

function clampOffset(value, docLength) {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(docLength, Number(value)));
}

async function refreshFromSource(source, options = {}) {
  const response = await parseWithRust(source);
  state.editorModel = editorModelFromRust(response.model, source);
  renderArguments();
  renderPreview(response);
  renderDiagnostics(response);
  elements.model.textContent = JSON.stringify(response.model ?? null, null, 2);
  if (!options.keepVariantEditor) {
    renderVariantTabs();
    renderProseMirrorVariant();
  }
  renderPluralTools();
}

async function refreshSourceContract(source) {
  const response = await parseWithRust(source);
  state.sourceContract = {
    source,
    model: response.model,
    placeholders: placeholderCountsFromRustModel(response.model),
    requirements: placeholderRequirementsFromRustModel(response.model),
  };
  forceLinting(cmView);
  void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true });
}

async function parseWithRust(source) {
  const response = await fetch("/api/format", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      source,
      locale: elements.locale.value,
      bidiIsolation: "default",
      arguments: coerceArguments(state.args),
    }),
  });
  if (!response.ok) {
    return {
      model: null,
      diagnostics: [{ code: "server-error", message: `Rust endpoint returned ${response.status}` }],
      formatErrors: [],
      output: "",
      parts: [],
    };
  }
  return response.json();
}

async function refreshPluralMetadata() {
  const response = await fetch(`/api/plurals?locale=${encodeURIComponent(elements.locale.value)}`);
  state.pluralMetadata = response.ok ? await response.json() : null;
  renderPluralTools();
}

function renderArguments() {
  const names = state.editorModel?.variables?.length ? state.editorModel.variables : Object.keys(state.args);
  elements.arguments.innerHTML = "";
  for (const name of names) {
    if (!(name in state.args)) state.args[name] = defaultArgs[name] ?? "";
    const label = document.createElement("label");
    label.className = "field";
    label.innerHTML = `<span>$${escapeHtml(name)}</span><input value="${escapeHtml(String(state.args[name]))}" />`;
    label.querySelector("input").addEventListener("input", (event) => {
      state.args[name] = event.target.value;
      forceLinting(cmView);
      void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true });
    });
    elements.arguments.append(label);
  }
}

function renderPreview(response) {
  elements.rendered.value = response.output ?? "";
  const errors = validationDiagnostics(response, cmView.state.doc.toString());
  elements.status.textContent = errors.length ? `${errors.length} issue(s)` : "Valid - Rust parser/runtime";
}

function renderDiagnostics(response) {
  const diagnostics = validationDiagnostics(response, cmView.state.doc.toString());
  elements.diagnostics.innerHTML = "";
  if (!diagnostics.length) {
    elements.diagnostics.innerHTML = '<li class="ok">No parser or runtime issues.</li>';
    return;
  }
  for (const diagnostic of diagnostics) {
    const item = document.createElement("li");
    item.className = "error";
    item.textContent = `${diagnostic.code}: ${diagnostic.message}`;
    elements.diagnostics.append(item);
  }
}

function validationDiagnostics(response, source) {
  return [
    ...(response.diagnostics ?? []),
    ...(response.formatErrors ?? []),
    ...placeholderDiagnostics(response.model, source),
  ];
}

function placeholderDiagnostics(rustModel, source) {
  if (!rustModel || !state.sourceContract) return [];
  return [
    ...extraPlaceholderDiagnostics(rustModel, source),
    ...missingPlaceholderDiagnostics(rustModel, source),
  ];
}

function extraPlaceholderDiagnostics(rustModel, source) {
  const allowed = state.sourceContract.placeholders;
  const current = placeholderCountsFromRustModel(rustModel);
  const diagnostics = [];
  for (const name of current.keys()) {
    if (allowed.has(name)) continue;
    diagnostics.push({
      severity: "error",
      code: "new-placeholder",
      message: `Target uses {$${name}}, but that placeholder does not exist in the source message.`,
      start: placeholderOffset(source, name),
      end: placeholderOffset(source, name) + name.length + 3,
    });
  }
  return diagnostics;
}

function missingPlaceholderDiagnostics(rustModel, source) {
  if (!state.sourceContract.requirements.length) return [];
  const current = placeholderRequirementsFromRustModel(rustModel);
  const currentBySignature = new Map(current.map((item) => [item.signature, item.requirements]));
  const diagnostics = [];
  for (const baseline of state.sourceContract.requirements) {
    const currentRequirements = currentBySignature.get(baseline.signature);
    if (!currentRequirements) continue;
    const currentCounts = new Map(currentRequirements.map((item) => [item.name, item.count]));
    const missing = missingRequiredPlaceholders(baseline.requirements, currentCounts);
    for (const item of missing) {
      diagnostics.push({
        severity: "error",
        code: "missing-placeholder",
        message: `Variant ${baseline.label} is missing required placeholder {$${item.name}}.`,
        start: 0,
        end: Math.min(source.length, 1),
      });
    }
  }
  return diagnostics;
}

function placeholderOffset(source, name) {
  const offset = source.indexOf(`{$${name}`);
  return offset >= 0 ? offset : 0;
}

function renderVariantTabs() {
  elements.variantTabs.innerHTML = "";
  if (state.editorModel?.type !== "select") {
    elements.variantTabs.innerHTML = '<span class="hint">Simple message</span>';
    return;
  }
  state.editorModel.variants.forEach((variant, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = variantLabel(state.editorModel.selectors, variant.keys);
    button.setAttribute("aria-selected", String(index === state.activeVariant));
    button.addEventListener("click", () => {
      state.activeVariant = index;
      renderVariantTabs();
      renderProseMirrorVariant();
    });
    elements.variantTabs.append(button);
  });
}

function renderPluralTools() {
  if (!elements.pluralTools) return;
  const selector = primaryPluralSelector();
  if (!selector || state.editorModel?.type !== "select" || !state.pluralMetadata?.categories?.length) {
    elements.pluralTools.innerHTML = "";
    return;
  }

  const selectorIndex = state.editorModel.selectors.indexOf(selector);
  const existingGenericKeys = new Set(
    state.editorModel.variants
      .filter((variant) => variant.keys.every((key, index) => index === selectorIndex || key === "*"))
      .map((variant) => variant.keys[selectorIndex]),
  );
  const categories = state.pluralMetadata.categories;
  const plan = pluralCombinationPlan(selector);
  elements.pluralTools.innerHTML = `
    <header>
      <h3>CLDR plural forms for $${escapeHtml(selector)} (${escapeHtml(state.pluralMetadata.locale)})</h3>
      <span class="eyebrow">CLDR categories; * is the MF2 fallback</span>
    </header>
    ${plan.missing.length ? `
      <div class="plural-bulk">
        <div>
          <strong>${escapeHtml(plan.label)}</strong>
          <span>${plan.missing.length} missing row(s); ${plan.total} possible combination(s)</span>
        </div>
        <button type="button" data-generate-combinations>Generate missing combinations</button>
      </div>
    ` : ""}
    <div class="plural-category-list">
      ${categories.map((category) => `
        <div class="plural-category">
          <strong>${escapeHtml(category.category)}</strong>
          <span>${escapeHtml((category.examples ?? []).join(", "))}</span>
          <button type="button" data-add-plural="${escapeHtml(category.category)}"${existingGenericKeys.has(category.category) ? " disabled" : ""}>
            ${existingGenericKeys.has(category.category) ? "Added" : "Add row"}
          </button>
        </div>
      `).join("")}
    </div>
  `;
  elements.pluralTools.querySelector("[data-generate-combinations]")?.addEventListener("click", () => generatePluralCombinations(selector));
  elements.pluralTools.querySelectorAll("[data-add-plural]").forEach((button) => {
    button.addEventListener("click", () => addPluralVariant(selector, button.dataset.addPlural));
  });
}

function primaryPluralSelector() {
  if (state.editorModel?.type !== "select") return null;
  for (const selector of state.editorModel.selectors) {
    const declaration = state.editorModel.declarations.find((item) => item.name === selector);
    if (declaration?.function === "number" || declaration?.function === "integer") {
      return selector;
    }
  }
  return null;
}

function addPluralVariant(selector, category) {
  if (state.editorModel?.type !== "select") return;
  const selectorIndex = state.editorModel.selectors.indexOf(selector);
  const fallback = state.editorModel.variants.find((variant) => variant.keys.every((key) => key === "*"));
  const keys = state.editorModel.selectors.map((_name, index) => (index === selectorIndex ? category : "*"));
  if (variantExists(keys)) return;
  const value = fallbackValue(fallback, selector);
  const insertIndex = fallbackIndex();
  state.editorModel.variants.splice(insertIndex, 0, { keys, value });
  state.activeVariant = insertIndex;
  const source = printModel(state.editorModel);
  replaceCode(source);
  void refreshFromSource(source);
}

function generatePluralCombinations(selector) {
  if (state.editorModel?.type !== "select") return;
  const plan = pluralCombinationPlan(selector);
  if (!plan.missing.length) return;
  const fallback = state.editorModel.variants.find((variant) => variant.keys.every((key) => key === "*"));
  const value = fallbackValue(fallback, selector);
  let insertIndex = fallbackIndex();
  for (const keys of plan.missing) {
    state.editorModel.variants.splice(insertIndex, 0, { keys, value });
    insertIndex++;
  }
  state.activeVariant = Math.max(0, insertIndex - plan.missing.length);
  const source = printModel(state.editorModel);
  replaceCode(source);
  void refreshFromSource(source);
}

function pluralCombinationPlan(selector) {
  if (state.editorModel?.type !== "select" || !state.pluralMetadata?.categories?.length) {
    return { label: "No plural selector", total: 0, missing: [] };
  }
  const pluralIndex = state.editorModel.selectors.indexOf(selector);
  const dimensions = state.editorModel.selectors.map((name, index) => {
    if (index === pluralIndex) {
      return state.pluralMetadata.categories.map((category) => category.category);
    }
    const values = selectorValues(index);
    return values.length ? values : ["*"];
  });
  const combinations = cartesian(dimensions);
  const missing = combinations.filter((keys) => !variantExists(keys));
  const nonPluralLabels = state.editorModel.selectors
    .filter((name, index) => index !== pluralIndex && selectorValues(index).length)
    .join(" x ");
  return {
    label: nonPluralLabels ? `Generate ${nonPluralLabels} x ${selector} rows` : `Generate ${selector} plural rows`,
    total: combinations.length,
    missing,
  };
}

function selectorValues(selectorIndex) {
  const values = [];
  for (const variant of state.editorModel?.variants ?? []) {
    const key = variant.keys[selectorIndex];
    if (key && key !== "*" && !values.includes(key)) values.push(key);
  }
  return values;
}

function cartesian(dimensions) {
  return dimensions.reduce(
    (rows, values) => rows.flatMap((row) => values.map((value) => [...row, value])),
    [[]],
  );
}

function variantExists(keys) {
  return (state.editorModel?.variants ?? []).some((variant) => variantSignature(variant.keys) === variantSignature(keys));
}

function fallbackIndex() {
  const index = (state.editorModel?.variants ?? []).findIndex((variant) => variant.keys.every((key) => key === "*"));
  return index >= 0 ? index : state.editorModel.variants.length;
}

function fallbackValue(fallback, selector) {
  return fallback?.value ?? `{$${selector}}`;
}

function renderProseMirrorVariant() {
  const pattern = selectedPattern();
  const pmState = ProseMirrorState.create({
    doc: docFromPattern(pattern),
    plugins: [
      history(),
      keymap({
        "Mod-z": undo,
        "Mod-y": redo,
        "Mod-Shift-z": redo,
        "Mod-b": toggleMark(schema.marks.strong),
      }),
      keymap(baseKeymap),
      protectedPlaceholderPlugin(requiredPlaceholdersForSelectedVariant(pattern)),
      new Plugin({
        view: () => ({
          update: (view, previousState) => {
            if (previousState.doc.eq(view.state.doc)) return;
            updateSelectedPattern(patternFromDoc(view.state.doc));
          },
        }),
      }),
    ],
  });
  if (pmView) {
    pmView.updateState(pmState);
  } else {
    pmView = new ProseMirrorView(elements.prosemirror, { state: pmState });
  }
}

function protectedPlaceholderPlugin(requiredPlaceholders) {
  return new Plugin({
    filterTransaction(transaction) {
      if (!transaction.docChanged || !requiredPlaceholders.length) return true;
      const nextPattern = patternFromDoc(transaction.doc);
      const nextCounts = placeholderCountsFromSource(nextPattern);
      const missing = missingRequiredPlaceholders(requiredPlaceholders, nextCounts);
      if (!missing.length) return true;
      showProtectedPlaceholderNotice(missing);
      return false;
    },
  });
}

function requiredPlaceholdersForSelectedVariant(fallbackPattern) {
  const signature = selectedVariantSignature();
  const baseline = state.sourceContract?.requirements.find((item) => item.signature === signature);
  return baseline?.requirements ?? countsToRequirements(placeholderCountsFromSource(fallbackPattern));
}

function selectedVariantSignature() {
  if (state.editorModel?.type === "select") {
    const variant = state.editorModel.variants[state.activeVariant];
    return variantSignature(variant?.keys ?? []);
  }
  return "message";
}

function showProtectedPlaceholderNotice(missing) {
  elements.status.textContent = `Protected placeholder: ${missing.map((item) => `{$${item.name}}`).join(", ")}`;
}

function selectedPattern() {
  if (state.editorModel?.type === "select") {
    return state.editorModel.variants[state.activeVariant]?.value ?? "";
  }
  return state.editorModel?.pattern ?? cmView.state.doc.toString();
}

function updateSelectedPattern(pattern) {
  if (!state.editorModel) return;
  if (state.editorModel.type === "select") {
    const variant = state.editorModel.variants[state.activeVariant];
    if (!variant || variant.value === pattern) return;
    variant.value = pattern;
  } else {
    if (state.editorModel.pattern === pattern) return;
    state.editorModel.pattern = pattern;
  }
  const source = printModel(state.editorModel);
  replaceCode(source);
  void refreshFromSource(source, { keepVariantEditor: true });
}

function insertFirstPlaceholder() {
  if (!pmView) return;
  const name = state.editorModel?.variables?.[0] ?? "count";
  const node = schema.nodes.placeholder.create({ name });
  const transaction = pmView.state.tr.replaceSelectionWith(node).scrollIntoView();
  pmView.dispatch(transaction);
  pmView.focus();
}

function replaceCode(source) {
  state.updatingFromCode = true;
  cmView.dispatch({
    changes: { from: 0, to: cmView.state.doc.length, insert: source },
  });
  state.updatingFromCode = false;
}

function replaceSourceCode(source) {
  sourceCmView.dispatch({
    changes: { from: 0, to: sourceCmView.state.doc.length, insert: source },
  });
}

function editorModelFromRust(rustModel, source) {
  if (!rustModel) return null;
  if (rustModel.type === "select") {
    return {
      type: "select",
      source,
      declarations: (rustModel.declarations ?? []).map(declarationFromRust),
      selectors: (rustModel.selectors ?? []).map((selector) => selector.name),
      variants: (rustModel.variants ?? []).map((variant) => ({
        keys: (variant.keys ?? []).map((key) => (key.type === "*" ? "*" : key.value)),
        value: patternToSource(variant.value ?? []),
      })),
      variables: variablesFromRustModel(rustModel),
    };
  }
  return {
    type: "message",
    source,
    declarations: (rustModel.declarations ?? []).map(declarationFromRust),
    pattern: patternToSource(rustModel.pattern ?? []),
    variables: variablesFromRustModel(rustModel),
  };
}

function placeholderRequirementsFromRustModel(rustModel) {
  if (!rustModel) return [];
  if (rustModel.type === "select") {
    return (rustModel.variants ?? []).map((variant) => {
      const keys = (variant.keys ?? []).map((key) => (key.type === "*" ? "*" : key.value));
      return {
        signature: variantSignature(keys),
        label: keys.join(" "),
        requirements: countsToRequirements(placeholderCountsFromRustPattern(variant.value ?? [])),
      };
    });
  }
  return [
    {
      signature: "message",
      label: "message",
      requirements: countsToRequirements(placeholderCountsFromRustPattern(rustModel.pattern ?? [])),
    },
  ];
}

function placeholderCountsFromRustModel(rustModel) {
  const counts = new Map();
  if (!rustModel) return counts;
  const patterns = [];
  if (rustModel.type === "select") {
    for (const variant of rustModel.variants ?? []) patterns.push(variant.value ?? []);
  } else {
    patterns.push(rustModel.pattern ?? []);
  }
  for (const pattern of patterns) {
    mergeCounts(counts, placeholderCountsFromRustPattern(pattern));
  }
  return counts;
}

function placeholderCountsFromRustPattern(pattern) {
  const counts = new Map();
  for (const part of pattern ?? []) {
    collectVariablesFromRustPart(part, counts);
  }
  return counts;
}

function collectVariablesFromRustPart(part, counts) {
  if (!part || typeof part === "string") return;
  if (part.type === "expression") {
    collectVariableFromRustArg(part.arg, counts);
    collectVariablesFromRustOptions(part.function?.options, counts);
    collectVariablesFromRustAttributes(part.attributes, counts);
    return;
  }
  if (part.type === "markup") {
    collectVariablesFromRustOptions(part.options, counts);
    collectVariablesFromRustAttributes(part.attributes, counts);
  }
}

function collectVariablesFromRustOptions(options, counts) {
  for (const value of Object.values(options ?? {})) {
    collectVariableFromRustArg(value, counts);
  }
}

function collectVariablesFromRustAttributes(attributes, counts) {
  for (const value of Object.values(attributes ?? {})) {
    collectVariableFromRustArg(value, counts);
  }
}

function collectVariableFromRustArg(arg, counts) {
  if (arg?.type !== "variable") return;
  counts.set(arg.name, (counts.get(arg.name) ?? 0) + 1);
}

function declarationFromRust(declaration) {
  const expression = declaration.value ?? {};
  const functionRef = expression.function ?? {};
  return {
    type: declaration.type,
    name: declaration.name,
    function: functionRef.name ?? "",
    optionText: optionsToSource(functionRef.options),
  };
}

function printModel(model) {
  if (model.type !== "select") return model.pattern ?? model.source ?? "";
  const lines = [];
  for (const declaration of model.declarations) {
    lines.push(`.input {$${declaration.name} :${declaration.function}${declaration.optionText ? ` ${declaration.optionText}` : ""}}`);
  }
  lines.push(`.match ${model.selectors.map((name) => `$${name}`).join(" ")}`);
  for (const variant of model.variants) {
    lines.push(`${variant.keys.join(" ")} {{${variant.value}}}`);
  }
  return lines.join("\n");
}

function docFromPattern(pattern) {
  const nodes = [];
  for (const part of splitPlaceholders(pattern)) {
    if (part.type === "placeholder") {
      nodes.push(schema.nodes.placeholder.create({ name: part.name }));
    } else if (part.value) {
      nodes.push(schema.text(part.value));
    }
  }
  return schema.nodes.doc.create(null, [schema.nodes.paragraph.create(null, nodes)]);
}

function patternFromDoc(doc) {
  const chunks = [];
  doc.descendants((node) => {
    if (node.isText) chunks.push(node.text);
    if (node.type.name === "placeholder") chunks.push(`{$${node.attrs.name}}`);
  });
  return chunks.join("");
}

function placeholderCountsFromSource(pattern) {
  const counts = new Map();
  const expressionPattern = /\{\s*\$([^\s{}:]+)(?:\s|[:}])/gu;
  for (const match of pattern.matchAll(expressionPattern)) {
    counts.set(match[1], (counts.get(match[1]) ?? 0) + 1);
  }
  return counts;
}

function countsToRequirements(counts) {
  return Array.from(counts, ([name, count]) => ({ name, count }));
}

function mergeCounts(target, source) {
  for (const [name, count] of source.entries()) {
    target.set(name, (target.get(name) ?? 0) + count);
  }
}

function missingRequiredPlaceholders(required, currentCounts) {
  return required.filter((item) => (currentCounts.get(item.name) ?? 0) < item.count);
}

function splitPlaceholders(pattern) {
  const parts = [];
  let index = 0;
  const variablePattern = /\{\$([A-Za-z_][\w.-]*)\}/gu;
  for (const match of pattern.matchAll(variablePattern)) {
    if (match.index > index) parts.push({ type: "text", value: pattern.slice(index, match.index) });
    parts.push({ type: "placeholder", name: match[1] });
    index = match.index + match[0].length;
  }
  if (index < pattern.length) parts.push({ type: "text", value: pattern.slice(index) });
  return parts;
}

function patternToSource(pattern) {
  return pattern.map(partToSource).join("");
}

function partToSource(part) {
  if (typeof part === "string") return part;
  if (part.type === "markup") {
    const prefix = part.kind === "close" ? "/" : "#";
    const suffix = part.kind === "standalone" ? "/" : "";
    return `{${prefix}${part.name}${suffix}}`;
  }
  if (part.type === "expression") {
    const arg = argToSource(part.arg);
    const optionsText = optionsToSource(part.function?.options);
    const functionText = part.function?.name ? ` :${part.function.name}${optionsText ? ` ${optionsText}` : ""}` : "";
    return `{${arg}${functionText}}`;
  }
  return "";
}

function argToSource(arg) {
  if (!arg) return "";
  if (arg.type === "variable") return `$${arg.name}`;
  return `|${arg.value ?? ""}|`;
}

function optionsToSource(options) {
  if (!options) return "";
  return Object.entries(options)
    .map(([name, value]) => `${name}=${argToSource(value)}`)
    .join(" ");
}

function variablesFromRustModel(model) {
  const variables = new Set();
  for (const declaration of model.declarations ?? []) {
    variables.add(declaration.name);
  }
  if (model.type === "select") {
    for (const selector of model.selectors ?? []) variables.add(selector.name);
    for (const variant of model.variants ?? []) collectVariablesFromPattern(variant.value ?? [], variables);
  } else {
    collectVariablesFromPattern(model.pattern ?? [], variables);
  }
  return Array.from(variables);
}

function collectVariablesFromPattern(pattern, variables) {
  for (const part of pattern) {
    if (part?.type === "expression" && part.arg?.type === "variable") {
      variables.add(part.arg.name);
    }
  }
}

function variantLabel(selectors, keys) {
  if (!selectors.length) return "Message";
  return selectors.map((selector, index) => `${selector}: ${keys[index] === "*" ? "fallback" : keys[index]}`).join(", ");
}

function variantSignature(keys) {
  return keys.join("\u001f");
}

function coerceArguments(args) {
  const result = {};
  for (const [name, value] of Object.entries(args)) {
    const text = String(value);
    if (text.trim() !== "" && /^-?\d+(?:\.\d+)?$/u.test(text.trim())) {
      result[name] = Number(text);
    } else {
      result[name] = text;
    }
  }
  return result;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}
