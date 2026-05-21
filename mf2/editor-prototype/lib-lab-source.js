import { basicSetup, EditorView } from "codemirror";
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
  updatingFromCode: false,
};

const elements = {
  arguments: document.querySelector("#arguments"),
  codemirror: document.querySelector("#codemirror"),
  diagnostics: document.querySelector("#diagnostics"),
  insertPlaceholder: document.querySelector("#insertPlaceholder"),
  locale: document.querySelector("#locale"),
  model: document.querySelector("#model"),
  prosemirror: document.querySelector("#prosemirror"),
  rendered: document.querySelector("#rendered"),
  status: document.querySelector("#status"),
  variantTabs: document.querySelector("#variantTabs"),
};

let cmView;
let pmView;

initialize();

function initialize() {
  cmView = new EditorView({
    parent: elements.codemirror,
    state: EditorState.create({
      doc: samples.plural,
      extensions: [
        basicSetup,
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
      replaceCode(samples[button.dataset.sample]);
      state.activeVariant = 0;
      void refreshFromSource(samples[button.dataset.sample]);
    });
  });
  elements.locale.addEventListener("change", () => void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true }));
  elements.insertPlaceholder.addEventListener("click", insertFirstPlaceholder);
  void refreshFromSource(samples.plural);
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
      void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true });
    });
    elements.arguments.append(label);
  }
}

function renderPreview(response) {
  elements.rendered.value = response.output ?? "";
  const errors = [...(response.diagnostics ?? []), ...(response.formatErrors ?? [])];
  elements.status.textContent = errors.length ? `${errors.length} issue(s)` : "Valid - Rust parser/runtime";
}

function renderDiagnostics(response) {
  const diagnostics = [...(response.diagnostics ?? []), ...(response.formatErrors ?? [])];
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
  return selectors.map((selector, index) => `${selector}: ${keys[index] === "*" ? "any" : keys[index]}`).join(", ");
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
