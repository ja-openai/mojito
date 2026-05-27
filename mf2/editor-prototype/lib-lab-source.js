import { basicSetup, EditorView } from "codemirror";
import { autocompletion, completionStatus, startCompletion } from "@codemirror/autocomplete";
import { forceLinting, linter, lintGutter } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { history, redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { baseKeymap, toggleMark } from "prosemirror-commands";
import { Schema } from "prosemirror-model";
import { EditorState as ProseMirrorState, NodeSelection, Plugin, TextSelection } from "prosemirror-state";
import { EditorView as ProseMirrorView } from "prosemirror-view";
import {
  formatMessageToPartsWithFallback,
  parseToModel,
  partsToString,
} from "@mojito-mf2/core";
import { selectCardinal, selectOrdinal } from "@mojito-mf2/core/plural-rules";
import {
  countsToRequirements,
  missingRequiredPlaceholders,
  placeholderCountsFromSource,
  placeholderRequirementCounts,
  placeholderRequirementLabel,
  sourceVariantPlaceholdersForSignature,
  splitPatternTokens,
} from "./lib-lab-placeholders.js";
import {
  mf2PlaceholderCompletionContext,
  mf2PlaceholderCompletionNames,
  mf2PlaceholderCompletionReplacement,
  mf2PlaceholderNamesFromText,
} from "./mf2-placeholder-completion.js";

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
  placeholders: `.input {$actor :string}
.input {$project :string}
.input {$file_count :number}
.input {$reviewer :string}
.input {$due_date :string}
{{{$actor} assigned {$file_count} files from {$project} to {$reviewer} before {$due_date}.}}`,
  completion: `.input {$count :number}
.input {$count2 :number}
.input {$country :string}
.input {$coupon :string}
.input {$customer :string}
.input {$due_date :string}
{{{$customer} has {$count} item(s), {$count2} backup item(s), a {$coupon} coupon for {$country}, due {$due_date}.}}`,
  offsetLikes: `.input {$like_count :integer}
.local $others_count = {$like_count :offset subtract=1}
.match $like_count $others_count
0 * {{Your post has no likes.}}
1 * {{{$name} liked your post.}}
* 1 {{{$name} and {$others_count} other user liked your post.}}
* * {{{$name} and {$others_count} other users liked your post.}}`,
  offsetVariable: `.input {$like_count :integer}
.input {$hidden_count :integer}
.local $visible_count = {$like_count :offset subtract=$hidden_count}
.match $like_count $visible_count
0 * {{Nobody liked your post.}}
* one {{{$name} and {$visible_count} other visible user liked your post.}}
* * {{{$name} and {$visible_count} other visible users liked your post.}}`,
};

const defaultArgs = {
  actor: "Sam",
  count: 2,
  due_date: "Friday",
  file_count: 5,
  gender: "unknown",
  hidden_count: 2,
  like_count: 3,
  name: "Mojito",
  project: "Billing",
  reviewer: "Alex",
  url: "/people/mojito",
};

const PLURAL_CATEGORY_ORDER = ["zero", "one", "two", "few", "many", "other"];
const PLURAL_SAMPLE_CANDIDATES = [
  0, 1, 2, 3, 4, 5, 10, 11, 12, 20, 21, 22, 100, 101,
  1_000, 10_000, 100_000, 1_000_000, 2_000_000,
  1.1, 1.5, 2.1, 5.5,
];
const MARKUP_KINDS = ["open", "close", "standalone"];
const BIDI_SAMPLES = [
  { label: "a", value: "a" },
  { label: "ש", value: "ש" },
  { label: "م", value: "م" },
  { label: "1", value: "1" },
  { label: ".", value: "." },
  { label: "abc", value: "abc" },
  { label: "A1", value: "A1" },
  { label: "file.txt", value: "file.txt" },
  { label: "file-שלום.txt", value: "file-שלום.txt" },
  { label: "FSI file-שלום.txt", value: "\u2068file-שלום.txt\u2069", description: "Filename wrapped in FSI/PDI: file-שלום.txt." },
  { label: "RTL-start filename", value: "שלום-file.txt", description: "Mixed filename starts with Hebrew: שלום-file.txt." },
  { label: "FSI RTL-start filename", value: "\u2068שלום-file.txt\u2069", description: "RTL-start mixed filename wrapped in FSI/PDI: שלום-file.txt." },
  { label: "en raw: Hebrew token", value: "what does this mean טעא?" },
  { label: "en isolated Hebrew token", value: "what does this mean \u2067טעא\u2069?" },
  { label: "en: brands", value: "ChatGPT was created by OpenAI.", description: "English: ChatGPT was created by OpenAI. Logical order: ChatGPT first, OpenAI second." },
  { label: "ar raw: brands", value: "تم إنشاء ChatGPT بواسطة OpenAI.", description: "Arabic meaning: ChatGPT was created by OpenAI. Target sentence order starts with \"was created\", then ChatGPT, then \"by\", then OpenAI." },
  { label: "ar isolated: brands", value: "تم إنشاء \u2066ChatGPT\u2069 بواسطة \u2066OpenAI\u2069.", description: "Arabic with LRI/PDI around the LTR brands. Same order: \"was created\", ChatGPT, \"by\", OpenAI." },
  { label: "he raw: brands", value: "ChatGPT נוצר על ידי OpenAI.", description: "Hebrew: ChatGPT was created by OpenAI. Logical order: ChatGPT first, OpenAI second." },
  { label: "he isolated: brands", value: "\u2066ChatGPT\u2069 נוצר על ידי \u2066OpenAI\u2069.", description: "Hebrew with LRI/PDI around the LTR brands: ChatGPT was created by OpenAI." },
  { label: "שלום", value: "שלום" },
  { label: "مرحبا", value: "مرحبا" },
  { label: " / ", value: " / " },
];
const BIDI_PRIMARY_MARKERS = [
  { label: "LRM", value: "\u200E", name: "left-to-right mark" },
  { label: "RLM", value: "\u200F", name: "right-to-left mark" },
  { label: "ALM", value: "\u061C", name: "Arabic letter mark" },
  { label: "LRI", value: "\u2066", name: "left-to-right isolate" },
  { label: "RLI", value: "\u2067", name: "right-to-left isolate" },
  { label: "FSI", value: "\u2068", name: "first-strong isolate" },
  { label: "PDI", value: "\u2069", name: "pop directional isolate" },
];
const BIDI_ISOLATE_WRAPS = {
  ltr: { open: "\u2066", close: "\u2069", label: "LRI/PDI" },
  rtl: { open: "\u2067", close: "\u2069", label: "RLI/PDI" },
  auto: { open: "\u2068", close: "\u2069", label: "FSI/PDI" },
};
const BIDI_LEGACY_MARKERS = [
  { label: "LRE", value: "\u202A", name: "left-to-right embedding" },
  { label: "RLE", value: "\u202B", name: "right-to-left embedding" },
  { label: "PDF", value: "\u202C", name: "pop directional formatting" },
  { label: "LRO", value: "\u202D", name: "left-to-right override" },
  { label: "RLO", value: "\u202E", name: "right-to-left override" },
];
const BIDI_MARKERS = [...BIDI_PRIMARY_MARKERS, ...BIDI_LEGACY_MARKERS];
const TEXT_TOOLS = [
  { label: "No-break space", code: "NBSP", title: "Insert a space that keeps adjacent words together", text: "\u00a0" },
  { label: "Narrow no-break", code: "NNBSP", title: "Insert a narrow non-breaking space", text: "\u202f" },
  { label: "LTR mark", code: "LRM", title: "Insert a left-to-right mark for nearby punctuation", text: "\u200e" },
  { label: "RTL mark", code: "RLM", title: "Insert a right-to-left mark for nearby punctuation", text: "\u200f" },
  { label: "Keep LTR phrase", code: "LRI/PDI", title: "Wrap the selection as an isolated left-to-right phrase", wrap: ["\u2066", "\u2069"] },
  { label: "Keep RTL phrase", code: "RLI/PDI", title: "Wrap the selection as an isolated right-to-left phrase", wrap: ["\u2067", "\u2069"] },
  { label: "Auto-direction phrase", code: "FSI/PDI", title: "Wrap the selection and let its first strong character choose direction", wrap: ["\u2068", "\u2069"] },
  {
    label: "Curly apostrophe",
    code: "’",
    shortcut: "Mac US: ⌥⇧]",
    title: "Insert a typographic apostrophe. OS shortcuts depend on keyboard layout and IME: Mac US is Option+Shift+]; Windows often uses Alt+0146; Linux often uses Ctrl+Shift+U 2019.",
    text: "\u2019",
  },
];
const SCENARIO_FILTERS = [
  { id: "all", label: "All rows" },
  { id: "attention", label: "Needs review" },
  { id: "source-only", label: "Needs target row" },
  { id: "target-only", label: "Target-specific" },
  { id: "locale-missing", label: "Target-locale plural" },
];
const RTL_LANGUAGES = new Set(["ar", "dv", "fa", "he", "iw", "ks", "ku", "ps", "sd", "ug", "ur", "yi"]);
const RTL_SCRIPTS = new Set(["Adlm", "Arab", "Hebr", "Mand", "Nkoo", "Rohg", "Samr", "Syrc", "Thaa"]);

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
      attrs: { name: {}, source: { default: false } },
      selectable: true,
      parseDOM: [
        {
          tag: "span[data-placeholder]",
          getAttrs: (node) => ({
            name: node.getAttribute("data-placeholder"),
            source: node.getAttribute("data-placeholder-source") === "true",
          }),
        },
      ],
      toDOM: (node) => [
        "span",
        {
          class: "placeholder-chip",
          "data-placeholder": node.attrs.name,
          "data-placeholder-source": String(Boolean(node.attrs.source)),
          title: "Source-approved placeholder. Delete it only if this target row intentionally omits it.",
          contenteditable: "false",
        },
        `{$${node.attrs.name}}`,
      ],
    },
    markupBoundary: {
      atom: true,
      group: "inline",
      inline: true,
      attrs: { name: {}, kind: {}, source: {} },
      selectable: true,
      parseDOM: [
        {
          tag: "span[data-markup-boundary]",
          getAttrs: (node) => ({
            name: node.getAttribute("data-markup-name"),
            kind: node.getAttribute("data-markup-kind"),
            source: node.getAttribute("data-markup-source"),
          }),
        },
      ],
      toDOM: (node) => [
        "span",
        {
          class: `markup-chip markup-${node.attrs.kind}`,
          "data-markup-boundary": "true",
          "data-markup-kind": node.attrs.kind,
          "data-markup-name": node.attrs.name,
          "data-markup-source": node.attrs.source,
          contenteditable: "false",
        },
        markupBoundaryLabel(node.attrs),
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
  scenarioFilter: "all",
  updatingFromCode: false,
};

const elements = {
  arguments: document.querySelector("#arguments"),
  bidiCodepoints: document.querySelector("#bidiCodepoints"),
  bidiDirectionSummary: document.querySelector("#bidiDirectionSummary"),
  bidiEscaped: document.querySelector("#bidiEscaped"),
  bidiInput: document.querySelector("#bidiInput"),
  bidiIsolationRecipes: document.querySelector("#bidiIsolationRecipes"),
  bidiLegacyMarkerButtons: document.querySelector("#bidiLegacyMarkerButtons"),
  bidiLogicalOrder: document.querySelector("#bidiLogicalOrder"),
  bidiMarkerDiagnostics: document.querySelector("#bidiMarkerDiagnostics"),
  bidiMarkerButtons: document.querySelector("#bidiMarkerButtons"),
  bidiPlayLogical: document.querySelector("#bidiPlayLogical"),
  bidiPlayVisual: document.querySelector("#bidiPlayVisual"),
  bidiRendered: document.querySelector("#bidiRendered"),
  bidiSampleNote: document.querySelector("#bidiSampleNote"),
  bidiSampleButtons: document.querySelector("#bidiSampleButtons"),
  bidiStopOrder: document.querySelector("#bidiStopOrder"),
  bidiVisualOrder: document.querySelector("#bidiVisualOrder"),
  codemirror: document.querySelector("#codemirror"),
  compareRust: document.querySelector("#compareRust"),
  debugModeToggle: document.querySelector("#debugModeToggle"),
  diagnostics: document.querySelector("#diagnostics"),
  escapedRendered: document.querySelector("#escapedRendered"),
  htmlPartsPreview: document.querySelector("#htmlPartsPreview"),
  insertPlaceholder: document.querySelector("#insertPlaceholder"),
  inlineDiagnostics: document.querySelector("#inlineDiagnostics"),
  locale: document.querySelector("#locale"),
  markupSelect: document.querySelector("#markupSelect"),
  model: document.querySelector("#model"),
  nativeRawCompletion: document.querySelector("#nativeRawCompletion"),
  nativeRawDiagnostics: document.querySelector("#nativeRawDiagnostics"),
  nativeRawEditor: document.querySelector("#nativeRawEditor"),
  nativeRawHighlight: document.querySelector("#nativeRawHighlight"),
  nativeRawShortcuts: document.querySelector("#nativeRawShortcuts"),
  placeholderAutocomplete: document.querySelector("#placeholderAutocomplete"),
  placeholderSelect: document.querySelector("#placeholderSelect"),
  partsPreview: document.querySelector("#partsPreview"),
  previewDirection: document.querySelector("#previewDirection"),
  pluralTools: document.querySelector("#pluralTools"),
  prosemirror: document.querySelector("#prosemirror"),
  rendered: document.querySelector("#rendered"),
  rowHelperStrip: document.querySelector("#rowHelperStrip"),
  rowToolsDrawer: document.querySelector("#rowToolsDrawer"),
  sampleSelect: document.querySelector("#sampleSelect"),
  scenarioMatrix: document.querySelector("#scenarioMatrix"),
  sourceContractSummary: document.querySelector("#sourceContractSummary"),
  sourceCodemirror: document.querySelector("#sourceCodemirror"),
  sourceProsePreview: document.querySelector("#sourceProsePreview"),
  sourceRawDrawer: document.querySelector("#sourceRawDrawer"),
  status: document.querySelector("#status"),
  targetDirectionLabel: document.querySelector("#targetDirectionLabel"),
  targetLanguage: document.querySelector("#targetLanguage"),
  variantKeyEditor: document.querySelector("#variantKeyEditor"),
  variantTabs: document.querySelector("#variantTabs"),
  wrapMarkup: document.querySelector("#wrapMarkup"),
  clearBidiText: document.querySelector("#clearBidiText"),
};

elements.prosemirrorHint = document.createElement("div");
elements.prosemirrorHint.id = "prosemirrorHint";
elements.prosemirrorHint.className = "shortcut-strip";

let cmView;
let pmView;
let sourceCmView;
let scenarioRenderToken = 0;
let bidiAnimationTimer = null;
let placeholderCompletionIndex = 0;
let placeholderCompletionQuery = "";
let placeholderCompletionRange = null;
let nativeRawCompletionIndex = 0;
let nativeRawCompletionQuery = "";
let nativeRawCompletionRange = null;
let nativeRawRefreshTimer = null;
let suppressProseMirrorSync = false;

initialize();

function initialize() {
  sourceCmView = new EditorView({
    parent: elements.sourceCodemirror,
    state: EditorState.create({
      doc: samples.plural,
      extensions: [
        basicSetup,
        mf2PlaceholderAutocompletion(() => placeholderNamesFromRawSource(sourceCmView?.state.doc.toString() ?? "")),
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
        mf2PlaceholderAutocompletion(sourcePlaceholderNames),
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
  syncNativeRawEditor(samples.plural);
  bindNativeRawEditor();

  document.addEventListener("keydown", handleDocumentFormShortcut, true);
  document.querySelectorAll("[data-sample]").forEach((button) => {
    button.addEventListener("click", () => applySample(button.dataset.sample));
  });
  elements.sampleSelect?.addEventListener("change", (event) => applySample(event.target.value));
  document.querySelectorAll("[data-editor-mode]").forEach((button) => {
    button.addEventListener("click", () => {
      setEditorMode(button.dataset.editorMode ?? "wysiwyg");
    });
  });
  elements.sourceRawDrawer?.addEventListener("toggle", () => {
    sourceCmView?.requestMeasure();
  });
  elements.locale.addEventListener("change", async () => {
    if (elements.targetLanguage && elements.targetLanguage.value !== elements.locale.value) {
      elements.targetLanguage.value = elements.locale.value;
    }
    await refreshPluralMetadata();
    applyTargetDirection();
    forceLinting(cmView);
    forceLinting(sourceCmView);
    void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true });
  });
  elements.targetLanguage?.addEventListener("change", () => {
    if (elements.locale.value !== elements.targetLanguage.value) {
      elements.locale.value = elements.targetLanguage.value;
      elements.locale.dispatchEvent(new Event("change"));
    }
  });
  elements.previewDirection.addEventListener("change", async () => {
    applyTargetDirection();
    const source = cmView.state.doc.toString();
    const response = await parseForLab(source);
    void renderScenarioMatrix(source, response);
  });
  elements.compareRust.addEventListener("change", () => {
    forceLinting(cmView);
    forceLinting(sourceCmView);
    void refreshSourceContract(sourceCmView.state.doc.toString());
  });
  elements.debugModeToggle?.addEventListener("click", () => {
    setDebugMode(!document.body.classList.contains("debug-mode-on"));
  });
  elements.insertPlaceholder.addEventListener("click", insertSelectedPlaceholder);
  elements.wrapMarkup.addEventListener("click", wrapSelectedMarkup);
  initializeBidiLab();
  setDebugMode(false);
  setEditorMode("wysiwyg");
  applyTargetDirection();
  void refreshSourceContract(samples.plural);
  void refreshPluralMetadata();
  void refreshFromSource(samples.plural);
  const labApi = {
    setSource: async (source) => {
      replaceSourceCode(source);
      await refreshSourceContract(source);
    },
    setTarget: async (source) => {
      replaceCode(source);
      await refreshFromSource(source);
    },
  };
  if (typeof window !== "undefined") window.mf2Lab = labApi;
  if (typeof globalThis !== "undefined") globalThis.mf2Lab = labApi;
}

function applySample(sampleKey) {
  const source = samples[sampleKey] ?? samples.plural;
  replaceSourceCode(source);
  replaceCode(source);
  state.activeVariant = 0;
  if (elements.sampleSelect && elements.sampleSelect.value !== sampleKey) elements.sampleSelect.value = sampleKey;
  void refreshSourceContract(source);
  void refreshFromSource(source);
}

function setDebugMode(enabled) {
  document.body.classList.toggle("debug-mode-on", Boolean(enabled));
  if (!elements.debugModeToggle) return;
  elements.debugModeToggle.setAttribute("aria-pressed", String(Boolean(enabled)));
  elements.debugModeToggle.textContent = enabled ? "Hide debug" : "Debug";
}

function applyTextTool(tool) {
  if (!tool || !pmView) return;
  if (tool.wrap) {
    wrapProseMirrorSelection(tool.wrap[0], tool.wrap[1]);
    return;
  }
  insertTextInProseMirror(tool.text ?? "");
}

function insertTextInProseMirror(text) {
  if (!pmView || !text) return;
  const { from, to } = pmView.state.selection;
  pmView.dispatch(pmView.state.tr.insertText(text, from, to).scrollIntoView());
  pmView.focus();
}

function wrapProseMirrorSelection(prefix, suffix) {
  if (!pmView) return;
  const { from, to } = pmView.state.selection;
  let transaction = pmView.state.tr.insertText(suffix, to, to);
  transaction = transaction.insertText(prefix, from, from);
  pmView.dispatch(transaction.scrollIntoView());
  pmView.focus();
}

function bindNativeRawEditor() {
  const textarea = elements.nativeRawEditor;
  if (!textarea) return;
  textarea.addEventListener("input", () => {
    const source = textarea.value;
    renderNativeRawHighlight(source);
    syncNativeRawHighlightScroll();
    replaceCode(source, { skipNative: true });
    scheduleNativeRawRefresh(source);
    refreshNativeRawCompletion();
  });
  textarea.addEventListener("click", refreshNativeRawCompletion);
  textarea.addEventListener("keydown", handleNativeRawKeydown);
  textarea.addEventListener("keyup", refreshNativeRawCompletion);
  textarea.addEventListener("scroll", syncNativeRawHighlightScroll);
  renderNativeRawShortcuts();
}

function scheduleNativeRawRefresh(source) {
  if (nativeRawRefreshTimer != null) window.clearTimeout(nativeRawRefreshTimer);
  nativeRawRefreshTimer = window.setTimeout(() => {
    nativeRawRefreshTimer = null;
    void refreshFromSource(source);
  }, 120);
}

function handleNativeRawKeydown(event) {
  if (nativeRawCompletionOpen()) {
    const completionArrow = (event.key === "ArrowDown" || event.key === "ArrowUp")
      && !event.shiftKey
      && !event.metaKey
      && !event.ctrlKey
      && !event.altKey;
    if (completionArrow) {
      event.preventDefault();
      moveNativeRawCompletion(event.key === "ArrowDown" ? 1 : -1);
      return;
    }
    if (event.key === "Enter" && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey) {
      event.preventDefault();
      commitNativeRawCompletion();
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      closeNativeRawCompletion();
      return;
    }
  }
  if (isVariantSwitchShortcut(event)) {
    event.preventDefault();
    switchActiveVariant(event.key === "ArrowDown" ? 1 : -1);
    elements.nativeRawEditor?.focus();
    return;
  }
  if (isNextVariantShortcut(event)) {
    event.preventDefault();
    switchActiveVariant(1);
    elements.nativeRawEditor?.focus();
  }
}

function refreshNativeRawCompletion() {
  const textarea = elements.nativeRawEditor;
  if (!textarea || textarea.selectionStart !== textarea.selectionEnd) {
    closeNativeRawCompletion();
    return false;
  }
  const context = mf2PlaceholderCompletionContext(textarea.value, textarea.selectionStart);
  if (!context) {
    closeNativeRawCompletion();
    return false;
  }
  nativeRawCompletionRange = { from: context.from, to: textarea.selectionStart };
  nativeRawCompletionQuery = context.query ?? "";
  const names = nativeRawCompletionNames();
  if (nativeRawCompletionIndex >= names.length) nativeRawCompletionIndex = 0;
  renderNativeRawCompletion(names, textarea.value.slice(context.from, textarea.selectionStart));
  return true;
}

function nativeRawCompletionNames() {
  return mf2PlaceholderCompletionNames(sourcePlaceholderNames(), nativeRawCompletionQuery);
}

function nativeRawCompletionOpen() {
  return Boolean(elements.nativeRawCompletion && !elements.nativeRawCompletion.hidden);
}

function moveNativeRawCompletion(delta) {
  const names = nativeRawCompletionNames();
  if (!names.length) return;
  nativeRawCompletionIndex = (nativeRawCompletionIndex + delta + names.length) % names.length;
  renderNativeRawCompletion(names, nativeRawCompletionTypedText());
}

function commitNativeRawCompletion(name = nativeRawCompletionNames()[nativeRawCompletionIndex]) {
  const textarea = elements.nativeRawEditor;
  const range = nativeRawCompletionRange;
  if (!textarea || !range || !name) return;
  const replacement = mf2PlaceholderCompletionReplacement(textarea.value, range.from, range.to, name);
  closeNativeRawCompletion();
  replaceNativeRawRange(replacement.from, replacement.to, replacement.insert, replacement.cursor);
}

function nativeRawCompletionTypedText() {
  const textarea = elements.nativeRawEditor;
  const range = nativeRawCompletionRange;
  return textarea && range ? textarea.value.slice(range.from, range.to) : "{";
}

function renderNativeRawCompletion(names, typedText) {
  const host = elements.nativeRawCompletion;
  if (!host) return;
  host.hidden = false;
  host.innerHTML = `
    <span>${escapeHtml(typedText || "{")} -> placeholders <small>Enter inserts; arrows move; Esc closes</small></span>
    ${names.length
      ? names.map((name, index) => `
        <button type="button" data-native-placeholder-complete="${escapeHtml(name)}" aria-selected="${index === nativeRawCompletionIndex ? "true" : "false"}">{$${escapeHtml(name)}}</button>
      `).join("")
      : '<em>No matching placeholder</em>'}
  `;
  host.querySelectorAll("[data-native-placeholder-complete]").forEach((button, index) => {
    button.addEventListener("mousedown", (event) => event.preventDefault());
    button.addEventListener("click", () => {
      nativeRawCompletionIndex = index;
      commitNativeRawCompletion(button.dataset.nativePlaceholderComplete);
    });
  });
}

function closeNativeRawCompletion() {
  const host = elements.nativeRawCompletion;
  if (!host) return;
  host.hidden = true;
  host.innerHTML = "";
  nativeRawCompletionIndex = 0;
  nativeRawCompletionQuery = "";
  nativeRawCompletionRange = null;
}

function replaceNativeRawRange(from, to, text, cursor = from + text.length) {
  const textarea = elements.nativeRawEditor;
  if (!textarea) return;
  textarea.focus();
  textarea.setSelectionRange(from, to);
  textarea.setRangeText(text, from, to, "end");
  textarea.setSelectionRange(cursor, cursor);
  textarea.dispatchEvent(new InputEvent("input", { bubbles: true, data: text, inputType: "insertText" }));
}

function applyNativeRawTextTool(tool) {
  const textarea = elements.nativeRawEditor;
  if (!textarea || !tool) return;
  const start = textarea.selectionStart ?? textarea.value.length;
  const end = textarea.selectionEnd ?? start;
  const selected = textarea.value.slice(start, end);
  const insert = tool.wrap ? `${tool.wrap[0]}${selected}${tool.wrap[1]}` : tool.text ?? "";
  if (!insert) return;
  const cursor = tool.wrap && start === end ? start + tool.wrap[0].length : start + insert.length;
  replaceNativeRawRange(start, end, insert, cursor);
}

function restoreNativeRawMissingPlaceholders() {
  const pattern = selectedPattern();
  const sourcePlaceholders = sourcePlaceholdersForSelectedVariant(pattern);
  const missing = missingRequiredPlaceholders(sourcePlaceholders, placeholderCountsFromSource(pattern));
  if (!missing.length) {
    elements.status.textContent = "All source placeholders are already present.";
    return;
  }
  const restored = [];
  for (const item of missing) {
    for (let index = 0; index < item.count; index++) restored.push(`{$${item.name}}`);
  }
  const separator = !pattern || /\s$/u.test(pattern) ? "" : " ";
  updateSelectedPattern(`${pattern}${separator}${restored.join(" ")}`);
  elements.status.textContent = `Native raw restored ${placeholderRequirementLabel(missing)}.`;
}

function renderNativeRawShortcuts() {
  const host = elements.nativeRawShortcuts;
  if (!host) return;
  host.innerHTML = `
    ${shortcutKey("{ or $", "placeholder menu")}
    ${shortcutKey("Shift+↓", "next form")}
    ${shortcutKey("Shift+↑", "previous form")}
    ${shortcutKey(platformMetaKeyLabel(), "next form")}
    ${renderNativeRawMissingPlaceholderAction()}
    <details class="shortcut-special">
      <summary>Insert special</summary>
      <div class="text-tool-grid">
        ${TEXT_TOOLS.map((tool, index) => `
          <button type="button" data-native-text-tool="${index}" title="${escapeHtml(tool.title)}">
            <span>${escapeHtml(tool.label)}</span>
            <small>${escapeHtml(tool.shortcut ? `${tool.code} · ${tool.shortcut}` : tool.code)}</small>
          </button>
        `).join("")}
      </div>
    </details>
  `;
  host.querySelector("[data-native-restore-placeholders]")?.addEventListener("click", restoreNativeRawMissingPlaceholders);
  host.querySelectorAll("[data-native-text-tool]").forEach((button) => {
    button.addEventListener("click", () => applyNativeRawTextTool(TEXT_TOOLS[Number(button.dataset.nativeTextTool)]));
  });
}

function renderNativeRawMissingPlaceholderAction() {
  const pattern = selectedPattern();
  const sourcePlaceholders = sourcePlaceholdersForSelectedVariant(pattern);
  const missing = missingRequiredPlaceholders(sourcePlaceholders, placeholderCountsFromSource(pattern));
  if (!missing.length) return "";
  return `
    <button type="button" class="shortcut-action" data-native-restore-placeholders
      title="Reinsert source placeholder(s) into the active form through the native raw prototype.">
      Restore ${escapeHtml(placeholderNameListLabel(missing))}
    </button>
  `;
}

function syncNativeRawEditor(source) {
  const textarea = elements.nativeRawEditor;
  if (!textarea) return;
  if (textarea.value === source) {
    renderNativeRawHighlight(source);
    syncNativeRawHighlightScroll();
    return;
  }
  const selectionStart = Math.min(textarea.selectionStart ?? source.length, source.length);
  const selectionEnd = Math.min(textarea.selectionEnd ?? selectionStart, source.length);
  textarea.value = source;
  textarea.setSelectionRange(selectionStart, selectionEnd);
  renderNativeRawHighlight(source);
  syncNativeRawHighlightScroll();
}

function renderNativeRawDiagnostics(response, source) {
  const host = elements.nativeRawDiagnostics;
  if (!host) return;
  const diagnostics = validationDiagnostics(response, source);
  if (!diagnostics.length) {
    host.innerHTML = '<span class="native-raw-diagnostic ok">No parser or contract issues.</span>';
    return;
  }
  host.innerHTML = diagnostics.slice(0, 4).map((diagnostic) => `
    <span class="native-raw-diagnostic ${diagnostic.severity === "warning" ? "warning" : "error"}">
      <strong>${escapeHtml(diagnostic.code)}</strong>${escapeHtml(diagnostic.message)}
    </span>
  `).join("");
  if (diagnostics.length > 4) {
    host.insertAdjacentHTML("beforeend", `<span class="native-raw-diagnostic warning">${diagnostics.length - 4} more issue(s)</span>`);
  }
}

function renderNativeRawHighlight(source = elements.nativeRawEditor?.value ?? "") {
  const host = elements.nativeRawHighlight;
  if (!host) return;
  host.innerHTML = highlightNativeRawSource(source);
}

function syncNativeRawHighlightScroll() {
  const textarea = elements.nativeRawEditor;
  const highlight = elements.nativeRawHighlight;
  if (!textarea || !highlight) return;
  highlight.scrollTop = textarea.scrollTop;
  highlight.scrollLeft = textarea.scrollLeft;
}

function highlightNativeRawSource(source) {
  const tokenPattern = /(\.(?:input|local|match)\b)|(\{[#/][^}\n]*\/?\})|(\{\$[^}\n]*\})|(\$[\p{L}_][\p{L}\p{N}_-]*)|(\{\{|\}\})|(\*)/gu;
  let html = "";
  let cursor = 0;
  for (const match of source.matchAll(tokenPattern)) {
    const index = match.index ?? 0;
    html += escapeHtml(source.slice(cursor, index));
    const token = match[0];
    const className = nativeRawHighlightClass(match);
    html += `<span class="${className}">${escapeHtml(token)}</span>`;
    cursor = index + token.length;
  }
  html += escapeHtml(source.slice(cursor));
  return html || " ";
}

function nativeRawHighlightClass(match) {
  if (match[1]) return "native-raw-token native-raw-token-keyword";
  if (match[2]) return "native-raw-token native-raw-token-markup";
  if (match[3]) return "native-raw-token native-raw-token-expression";
  if (match[4]) return "native-raw-token native-raw-token-variable";
  if (match[5]) return "native-raw-token native-raw-token-brace";
  return "native-raw-token native-raw-token-key";
}

function setEditorMode(mode) {
  const nextMode = mode === "raw" ? "raw" : "wysiwyg";
  document.body.classList.toggle("editor-mode-raw", nextMode === "raw");
  document.body.classList.toggle("editor-mode-wysiwyg", nextMode !== "raw");
  document.querySelectorAll("[data-editor-mode]").forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.editorMode === nextMode));
  });
  if (nextMode === "raw") {
    cmView?.requestMeasure();
    cmView?.focus();
  } else {
    pmView?.focus();
  }
}

function initializeBidiLab() {
  if (!elements.bidiInput) return;
  elements.bidiSampleButtons.innerHTML = BIDI_SAMPLES.map((item) =>
    `<button type="button" data-bidi-insert="${escapeHtml(item.value)}" data-bidi-note="${escapeHtml(item.description ?? item.label)}" title="${escapeHtml(item.description ?? item.label)}">${escapeHtml(item.label)}</button>`
  ).join("");
  elements.bidiMarkerButtons.innerHTML = BIDI_PRIMARY_MARKERS.map((item) =>
    `<button type="button" data-bidi-insert="${escapeHtml(item.value)}" title="${escapeHtml(item.name)}">${escapeHtml(item.label)}</button>`
  ).join("");
  elements.bidiLegacyMarkerButtons.innerHTML = BIDI_LEGACY_MARKERS.map((item) =>
    `<button type="button" data-bidi-insert="${escapeHtml(item.value)}" title="${escapeHtml(item.name)}">${escapeHtml(item.label)}</button>`
  ).join("");
  document.querySelectorAll("[data-bidi-direction]").forEach((button) => {
    button.addEventListener("click", () => {
      const direction = button.dataset.bidiDirection;
      elements.bidiInput.dir = direction;
      document.querySelectorAll("[data-bidi-direction]").forEach((item) => {
        item.setAttribute("aria-pressed", String(item === button));
      });
      renderBidiLab();
    });
  });
  document.querySelectorAll("[data-bidi-render-direction]").forEach((button) => {
    button.addEventListener("click", () => {
      elements.bidiRendered.dir = button.dataset.bidiRenderDirection;
      document.querySelectorAll("[data-bidi-render-direction]").forEach((item) => {
        item.setAttribute("aria-pressed", String(item === button));
      });
      renderBidiLab();
    });
  });
  document.querySelectorAll("[data-bidi-insert]").forEach((button) => {
    button.addEventListener("click", () => {
      insertIntoTextarea(elements.bidiInput, button.dataset.bidiInsert ?? "");
      showBidiSampleNote(button.dataset.bidiNote ?? "");
      renderBidiLab();
    });
  });
  document.querySelectorAll("[data-bidi-wrap]").forEach((button) => {
    button.addEventListener("click", () => {
      const wrap = BIDI_ISOLATE_WRAPS[button.dataset.bidiWrap];
      if (!wrap) return;
      wrapTextareaSelection(elements.bidiInput, wrap.open, wrap.close);
      showBidiSampleNote(`Wrapped selected text with ${wrap.label}. If nothing was selected, the whole textarea was wrapped.`);
      renderBidiLab();
    });
  });
  elements.clearBidiText.addEventListener("click", () => {
    elements.bidiInput.value = "";
    elements.bidiInput.focus();
    renderBidiLab();
  });
  elements.bidiPlayLogical.addEventListener("click", () => playBidiOrder("logical"));
  elements.bidiPlayVisual.addEventListener("click", () => playBidiOrder("visual"));
  elements.bidiStopOrder.addEventListener("click", stopBidiOrder);
  elements.bidiInput.addEventListener("input", renderBidiLab);
  renderBidiLab();
}

function showBidiSampleNote(note) {
  if (!elements.bidiSampleNote) return;
  elements.bidiSampleNote.textContent = note || "Select a sample to see its intended logical sentence.";
}

function insertIntoTextarea(textarea, value) {
  const start = textarea.selectionStart ?? textarea.value.length;
  const end = textarea.selectionEnd ?? start;
  textarea.value = `${textarea.value.slice(0, start)}${value}${textarea.value.slice(end)}`;
  const cursor = start + value.length;
  textarea.setSelectionRange(cursor, cursor);
  textarea.focus();
}

function wrapTextareaSelection(textarea, open, close) {
  const hasSelection = (textarea.selectionStart ?? 0) !== (textarea.selectionEnd ?? 0);
  const start = hasSelection ? textarea.selectionStart : 0;
  const end = hasSelection ? textarea.selectionEnd : textarea.value.length;
  const selected = textarea.value.slice(start, end);
  textarea.value = `${textarea.value.slice(0, start)}${open}${selected}${close}${textarea.value.slice(end)}`;
  const selectionStart = start + open.length;
  const selectionEnd = selectionStart + selected.length;
  textarea.setSelectionRange(selectionStart, selectionEnd);
  textarea.focus();
}

function renderBidiLab() {
  if (!elements.bidiInput) return;
  stopBidiOrder();
  const value = elements.bidiInput.value;
  const items = bidiCodepointItems(value);
  elements.bidiRendered.textContent = value || " ";
  renderBidiDirectionSummary();
  elements.bidiEscaped.textContent = escapedBidiText(value);
  renderBidiIsolationRecipes(value);
  renderBidiMarkerDiagnostics(items);
  renderBidiOrderStrip(elements.bidiLogicalOrder, items, "logical");
  renderBidiOrderStrip(elements.bidiVisualOrder, visualBidiOrder(items), "visual");
  elements.bidiCodepoints.innerHTML = items.map((item) => {
    return `
      <span class="${item.marker ? "bidi-control-codepoint" : ""}">
        <code>${escapeHtml(item.code)}</code>
        <strong>${escapeHtml(item.label)}</strong>
        ${item.marker ? `<em>${escapeHtml(item.marker.name)}</em>` : ""}
      </span>
    `;
  }).join("") || '<span class="hint-inline">No characters yet.</span>';
}

function renderBidiDirectionSummary() {
  if (!elements.bidiDirectionSummary) return;
  const inputDir = elements.bidiInput.getAttribute("dir") || "auto";
  const previewDir = elements.bidiRendered.getAttribute("dir") || "auto";
  const inputResolved = getComputedStyle(elements.bidiInput).direction;
  const previewResolved = getComputedStyle(elements.bidiRendered).direction;
  elements.bidiDirectionSummary.innerHTML = `
    <span>Textarea <strong>${escapeHtml(directionSummaryLabel(inputDir, inputResolved))}</strong></span>
    <span>Rendered preview <strong>${escapeHtml(directionSummaryLabel(previewDir, previewResolved))}</strong></span>
  `;
}

function renderBidiIsolationRecipes(value) {
  if (!elements.bidiIsolationRecipes) return;
  const text = value || "text";
  const rows = [
    {
      label: "Plain auto",
      note: "Unknown-direction text in a plain string.",
      value: escapedBidiText(`${BIDI_ISOLATE_WRAPS.auto.open}${text}${BIDI_ISOLATE_WRAPS.auto.close}`),
    },
    {
      label: "Plain LTR",
      note: "Known LTR value inside RTL text.",
      value: escapedBidiText(`${BIDI_ISOLATE_WRAPS.ltr.open}${text}${BIDI_ISOLATE_WRAPS.ltr.close}`),
    },
    {
      label: "Plain RTL",
      note: "Known RTL value inside LTR text.",
      value: escapedBidiText(`${BIDI_ISOLATE_WRAPS.rtl.open}${text}${BIDI_ISOLATE_WRAPS.rtl.close}`),
    },
    {
      label: "HTML auto",
      note: "Browser equivalent for unknown-direction inline content.",
      value: bdiHtmlSnippet(text, "auto"),
    },
    {
      label: "HTML LTR",
      note: "Browser equivalent for a known LTR span.",
      value: bdiHtmlSnippet(text, "ltr"),
    },
    {
      label: "HTML RTL",
      note: "Browser equivalent for a known RTL span.",
      value: bdiHtmlSnippet(text, "rtl"),
    },
  ];
  elements.bidiIsolationRecipes.innerHTML = rows.map((row) => `
    <div>
      <strong>${escapeHtml(row.label)}</strong>
      <code>${escapeHtml(row.value)}</code>
      <span>${escapeHtml(row.note)}</span>
    </div>
  `).join("");
}

function bdiHtmlSnippet(value, dir) {
  return `<bdi dir="${dir}">${escapeHtmlContent(value)}</bdi>`;
}

function directionSummaryLabel(dir, resolved) {
  const normalized = dir.toLowerCase();
  const resolvedUpper = resolved.toUpperCase();
  if (normalized === resolved) return resolvedUpper;
  return `${normalized.toUpperCase()} -> ${resolvedUpper}`;
}

function bidiCodepointItems(value) {
  const items = [];
  let offset = 0;
  for (const [index, char] of Array.from(value).entries()) {
    const codepoint = char.codePointAt(0);
    const marker = BIDI_MARKERS.find((item) => item.value === char);
    items.push({
      char,
      code: `U+${codepoint.toString(16).toUpperCase().padStart(4, "0")}`,
      index,
      label: marker?.label ?? visibleCharLabel(char),
      marker,
      start: offset,
      end: offset + char.length,
    });
    offset += char.length;
  }
  return items;
}

function renderBidiMarkerDiagnostics(items) {
  const diagnostics = bidiMarkerDiagnostics(items);
  if (!diagnostics.length) {
    elements.bidiMarkerDiagnostics.innerHTML = `
      <div class="bidi-marker-diagnostic ok">
        <strong>Modern isolates balanced.</strong>
        <span>LRM/RLM/ALM are point marks; they do not need closing.</span>
      </div>
    `;
    return;
  }
  elements.bidiMarkerDiagnostics.innerHTML = diagnostics.map((diagnostic) => `
    <div class="bidi-marker-diagnostic error">
      <strong>${escapeHtml(diagnostic.title)}</strong>
      <span>${escapeHtml(diagnostic.message)}</span>
    </div>
  `).join("");
}

function bidiMarkerDiagnostics(items) {
  const stack = [];
  const diagnostics = [];
  for (const item of items) {
    const label = item.marker?.label;
    if (label === "LRI" || label === "RLI" || label === "FSI") {
      stack.push(item);
    } else if (label === "PDI") {
      if (stack.length) {
        stack.pop();
      } else {
        diagnostics.push({
          title: "Stray PDI",
          message: `PDI at codepoint ${item.index + 1} has no open isolate to close.`,
        });
      }
    }
  }
  for (const item of stack.reverse()) {
    diagnostics.push({
      title: `Unclosed ${item.marker.label}`,
      message: `${item.marker.label} at codepoint ${item.index + 1} needs a later PDI.`,
    });
  }
  return diagnostics;
}

function visualBidiOrder(items) {
  const textNode = elements.bidiRendered.firstChild;
  if (!textNode || !items.length) return items;
  const direction = getComputedStyle(elements.bidiRendered).direction;
  const range = document.createRange();
  const measured = items.map((item) => {
    range.setStart(textNode, item.start);
    range.setEnd(textNode, item.end);
    const rect = range.getClientRects()[0] ?? range.getBoundingClientRect();
    return {
      ...item,
      visualLeft: Number.isFinite(rect.left) ? rect.left : item.index,
      visualRight: Number.isFinite(rect.right) ? rect.right : item.index,
      visualTop: Number.isFinite(rect.top) ? rect.top : 0,
    };
  });
  range.detach();
  return measured.sort((left, right) => {
    const lineDelta = Math.round(left.visualTop) - Math.round(right.visualTop);
    if (Math.abs(lineDelta) > 2) return lineDelta;
    if (direction === "rtl") return right.visualRight - left.visualRight || left.index - right.index;
    return left.visualLeft - right.visualLeft || left.index - right.index;
  });
}

function renderBidiOrderStrip(host, items, orderName) {
  host.innerHTML = items.map((item, orderIndex) => `
    <span class="${item.marker ? "bidi-order-control" : ""}" data-bidi-order="${orderName}" data-bidi-index="${item.index}">
      <em>${orderIndex + 1}</em>
      <strong>${escapeHtml(item.label)}</strong>
      <code>${escapeHtml(item.code)}</code>
    </span>
  `).join("") || '<span class="hint-inline">No characters yet.</span>';
}

function playBidiOrder(orderName) {
  stopBidiOrder();
  const cells = Array.from(document.querySelectorAll(`[data-bidi-order="${orderName}"]`));
  if (!cells.length) return;
  let index = 0;
  const step = () => {
    cells.forEach((cell) => cell.classList.remove("is-active"));
    if (index >= cells.length) {
      stopBidiOrder();
      return;
    }
    cells[index].classList.add("is-active");
    index++;
  };
  step();
  bidiAnimationTimer = window.setInterval(step, 450);
}

function stopBidiOrder() {
  if (bidiAnimationTimer != null) {
    window.clearInterval(bidiAnimationTimer);
    bidiAnimationTimer = null;
  }
  document.querySelectorAll("[data-bidi-order].is-active").forEach((cell) => cell.classList.remove("is-active"));
}

function escapedBidiText(value) {
  return Array.from(value).map((char) => {
    const marker = BIDI_MARKERS.find((item) => item.value === char);
    if (marker) return `<${marker.label}>`;
    if (char === "\n") return "\\n";
    if (char === "\t") return "\\t";
    return char;
  }).join("");
}

function visibleCharLabel(char) {
  if (char === " ") return "space";
  if (char === "\n") return "newline";
  if (char === "\t") return "tab";
  return char;
}

function applyTargetDirection() {
  const targetDirection = localeTextDirection(elements.locale.value);
  const previewDirection = previewTextDirection();
  elements.targetDirectionLabel.textContent = `${targetDirection.toUpperCase()} target editors`;
  elements.codemirror.dir = targetDirection;
  if (elements.nativeRawEditor) elements.nativeRawEditor.dir = targetDirection;
  elements.rendered.dir = previewDirection;
  elements.htmlPartsPreview.dir = previewDirection;
  elements.prosemirror.dir = targetDirection;
  elements.scenarioMatrix.dataset.targetDirection = previewDirection;
  if (cmView?.contentDOM) cmView.contentDOM.dir = targetDirection;
  if (pmView?.dom) pmView.dom.dir = targetDirection;
}

function previewTextDirection() {
  const value = elements.previewDirection?.value ?? "target";
  return value === "target" ? localeTextDirection(elements.locale.value) : value;
}

function localeTextDirection(locale) {
  try {
    const maximized = new Intl.Locale(locale).maximize();
    const direction = maximized.textInfo?.direction;
    if (direction === "rtl" || direction === "ltr") return direction;
    if (RTL_SCRIPTS.has(maximized.script)) return "rtl";
  } catch {
    // Fall back to compact locale-prefix checks below.
  }
  const parts = String(locale ?? "").split(/[-_]/u);
  if (RTL_SCRIPTS.has(parts[1])) return "rtl";
  return RTL_LANGUAGES.has(parts[0]?.toLowerCase()) ? "rtl" : "ltr";
}

function rustMf2Linter() {
  return linter(
    async (view) => {
      const response = await parseForLab(view.state.doc.toString());
      return codeMirrorDiagnostics(response, view.state.doc.toString());
    },
    { delay: 250 },
  );
}

function sourceMf2Linter() {
  return linter(
    async (view) => {
      const response = await parseForLab(view.state.doc.toString());
      return [...(response.diagnostics ?? []), ...(response.formatErrors ?? [])].map((diagnostic) => {
        const severity = diagnostic.severity === "warning" ? "warning" : "error";
        return {
          ...codeMirrorDiagnosticRange(diagnostic, view.state.doc.length),
          severity,
          source: "JavaScript MF2 source",
          message: `${diagnostic.code}: ${diagnostic.message}`,
          markClass: `mf2-cm-diagnostic mf2-cm-diagnostic-${severity}`,
        };
      });
    },
    { delay: 250 },
  );
}

function codeMirrorDiagnostics(response, source) {
  return validationDiagnostics(response, source).map((diagnostic) => {
    const severity = diagnostic.severity === "warning" ? "warning" : "error";
    return {
      ...codeMirrorDiagnosticRange(diagnostic, source.length),
      severity,
      source: "JavaScript MF2",
      message: `${diagnostic.code}: ${diagnostic.message}`,
      markClass: `mf2-cm-diagnostic mf2-cm-diagnostic-${severity}`,
    };
  });
}

function codeMirrorDiagnosticRange(diagnostic, docLength) {
  const from = clampOffset(diagnostic.start, docLength);
  const fallbackEnd = docLength > from ? from + 1 : from;
  const rawEnd = diagnostic.end == null || diagnostic.end === diagnostic.start ? fallbackEnd : diagnostic.end;
  const to = clampOffset(rawEnd, docLength);
  return { from, to: Math.max(from, to) };
}

function clampOffset(value, docLength) {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(docLength, Number(value)));
}

async function refreshFromSource(source, options = {}) {
  const response = await parseForLab(source);
  state.editorModel = editorModelFromRust(response.model, source);
  if (!options.skipArguments) {
    renderArguments();
  }
  renderPreview(response);
  renderPartsPreview(response);
  renderDiagnostics(response);
  renderInlineDiagnostics(response);
  renderNativeRawDiagnostics(response, source);
  renderSourceProsePreview();
  void renderScenarioMatrix(source, response);
  elements.model.textContent = JSON.stringify(response.model ?? null, null, 2);
  if (!options.keepVariantEditor) {
    renderVariantTabs();
    renderProseMirrorVariant();
  }
  renderVariantKeyEditor();
  renderPluralTools();
  renderRowHelperStrip();
  renderPlaceholderOptions();
  renderMarkupOptions();
  renderNativeRawShortcuts();
}

async function renderScenarioMatrix(targetSource, targetResponse) {
  if (!elements.scenarioMatrix) return;
  const token = ++scenarioRenderToken;
  const scenarios = scenarioRows();
  const sourceModel = editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? "");
  const targetModel = editorModelFromRust(targetResponse.model, targetSource);
  if (!sourceModel || !targetModel || scenarios.length <= 1) {
    renderDiagnostics(targetResponse);
    elements.scenarioMatrix.innerHTML = '<p class="hint-inline">Select messages show source/target coverage here.</p>';
    return;
  }
  elements.scenarioMatrix.innerHTML = '<p class="hint-inline">Checking coverage...</p>';
  const rows = [];
  for (const scenario of scenarios) {
    if (token !== scenarioRenderToken) return;
    const row = {
      scenario,
      source: scenarioPatternForModel(sourceModel, scenario),
      target: scenarioPatternForModel(targetModel, scenario),
    };
    row.status = scenarioRowStatus(row);
    row.reason = scenarioReviewReason(row);
    row.index = rows.length;
    rows.push(row);
  }
  if (token !== scenarioRenderToken) return;
  renderDiagnostics(targetResponse);
  const targetDirection = previewTextDirection();
  const summary = scenarioSummary(rows);
  const orderSummary = scenarioOrderSummary(rows);
  const missingRows = rows.filter((row) => row.status === "source-only");
  const targetOnlyRows = rows.filter((row) => row.status === "target-only");
  const localeMissingRows = rows.filter((row) => row.status === "locale-missing");
  const filteredRows = rows.filter((row) => scenarioRowMatchesFilter(row, state.scenarioFilter));
  elements.scenarioMatrix.innerHTML = `
    <div class="scenario-review-header">
      <div class="scenario-summary">
        ${renderScenarioSummary(summary)}
      </div>
      <div class="scenario-controls">
        ${missingRows.length ? `
          <button type="button" class="scenario-action" data-scenario-bulk-action="add-missing-source-rows">
            Add ${missingRows.length} source-defined target row(s)
          </button>
        ` : ""}
        ${localeMissingRows.length ? `
          <button type="button" class="scenario-action" data-scenario-bulk-action="add-missing-locale-rows">
            Add ${localeMissingRows.length} target-locale wildcard CLDR row(s)
          </button>
        ` : ""}
        ${targetOnlyRows.length ? `
          <button type="button" class="scenario-action" data-scenario-bulk-action="remove-target-only-rows">
            Remove ${targetOnlyRows.length} target-specific row(s)
          </button>
        ` : ""}
        <div class="button-row scenario-filter-row" role="group" aria-label="Scenario filter">
          ${SCENARIO_FILTERS.map((filter) => `
            <button type="button" data-scenario-filter="${filter.id}" aria-pressed="${state.scenarioFilter === filter.id}">
              ${escapeHtml(filter.label)}
            </button>
          `).join("")}
        </div>
      </div>
    </div>
    ${renderLocalePluralCoverage(localePluralCoverageRows())}
    ${renderScenarioOrderSummary(orderSummary)}
    <table>
      <thead>
        <tr>
          <th>Case</th>
          <th>Status</th>
          <th>Why</th>
          <th>Action</th>
          <th>Args</th>
          <th>Source form</th>
          <th>Target form</th>
        </tr>
      </thead>
      <tbody>
        ${filteredRows.map((row) => `
          <tr class="${scenarioStatusClass(row.status)}">
            <td>${renderScenarioCase(row.scenario)}</td>
            <td><span class="scenario-status-pill">${escapeHtml(scenarioStatusLabel(row.status))}</span></td>
            <td><span class="scenario-reason">${escapeHtml(row.reason)}</span></td>
            <td>${renderScenarioAction(row)}</td>
            <td><code>${escapeHtml(JSON.stringify(row.scenario.arguments))}</code></td>
            <td class="scenario-form-cell" dir="ltr">${renderScenarioPattern(row.source)}</td>
            <td class="scenario-form-cell" dir="${targetDirection}">${renderScenarioPattern(row.target)}</td>
          </tr>
        `).join("") || `
          <tr>
            <td colspan="7"><p class="hint-inline">No scenario rows match this filter.</p></td>
          </tr>
        `}
      </tbody>
    </table>
  `;
  elements.scenarioMatrix.querySelectorAll("[data-scenario-filter]").forEach((button) => {
    button.addEventListener("click", () => {
      state.scenarioFilter = button.dataset.scenarioFilter ?? "all";
      void renderScenarioMatrix(targetSource, targetResponse);
    });
  });
  elements.scenarioMatrix.querySelector("[data-scenario-bulk-action='add-missing-source-rows']")?.addEventListener("click", () => {
    addMissingTargetVariantsFromRows(missingRows, "source-defined target");
  });
  elements.scenarioMatrix.querySelector("[data-scenario-bulk-action='add-missing-locale-rows']")?.addEventListener("click", () => {
    addMissingTargetVariantsFromRows(localeMissingRows, "target-locale wildcard CLDR");
  });
  elements.scenarioMatrix.querySelector("[data-scenario-bulk-action='remove-target-only-rows']")?.addEventListener("click", () => {
    removeTargetVariantsFromRows(targetOnlyRows);
  });
  elements.scenarioMatrix.querySelectorAll("[data-scenario-action]").forEach((button) => {
    button.addEventListener("click", () => {
      const row = rows[Number(button.dataset.scenarioIndex)];
      if (!row) return;
      if (button.dataset.scenarioAction === "add-target-row") addTargetVariantFromScenario(row);
      if (button.dataset.scenarioAction === "add-specific-target-row") {
        addSpecificTargetVariantFromScenario(row, Number(button.dataset.scenarioSpecificIndex));
      }
      if (button.dataset.scenarioAction === "select-target-row") selectTargetVariantFromScenario(row);
      if (button.dataset.scenarioAction === "remove-target-row") removeTargetVariantsFromRows([row]);
    });
  });
}

function scenarioPatternForModel(model, scenario) {
  if (!model) return "";
  if (model.type !== "select") return model.pattern ?? "";
  const variant = firstMatchingVariant(model, scenario.keys ?? []);
  return variant?.value ?? "";
}

function firstMatchingVariant(model, keys) {
  for (const variant of model.variants ?? []) {
    if (variantMatchesScenario(variant.keys ?? [], keys)) return variant;
  }
  return null;
}

function variantMatchesScenario(variantKeys, scenarioKeys) {
  if (variantKeys.length !== scenarioKeys.length) return false;
  return variantKeys.every((key, index) => {
    const value = variantKeyValue(key);
    return value === "*" || value === variantKeyValue(scenarioKeys[index]);
  });
}

function renderScenarioPattern(pattern) {
  return pattern
    ? renderPatternAsProse(pattern)
    : '<span class="hint-inline">(empty)</span>';
}

function scenarioRows() {
  const model = scenarioCoverageModel();
  if (!model) {
    return [{ label: "Current", arguments: { ...state.args } }];
  }
  const rows = [];
  const seen = new Set();
  for (const variant of model.variants) {
    const args = scenarioArgumentsForVariant(model, variant.keys, state.args, elements.locale.value);
    const signature = JSON.stringify(args);
    if (seen.has(signature)) continue;
    seen.add(signature);
    const details = variantScenarioDetails(model, variant.keys, args);
    rows.push({
      label: variantLabel(model.selectors, variant.keys),
      keys: [...variant.keys],
      origins: variant.origins ?? [],
      localePlural: variant.localePlural,
      overlapNotes: variant.overlapNotes ?? [],
      details,
      rowKind: scenarioRowKind(variant, details),
      arguments: args,
    });
  }
  return rows.slice(0, 18);
}

function scenarioCoverageModel() {
  const sourceModel = editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? "");
  const targetModel = state.editorModel;
  const base = sourceModel?.type === "select" ? sourceModel : targetModel?.type === "select" ? targetModel : null;
  if (!base) return null;
  const variants = [];
  const bySignature = new Map();
  for (const [model, origin] of [[sourceModel, "source"], [targetModel, "target"]]) {
    if (model?.type !== "select" || !sameSelectors(model, base)) continue;
    for (const variant of model.variants ?? []) {
      const signature = variantSignature(variant.keys);
      const existing = bySignature.get(signature);
      if (existing) {
        existing.origins.push(origin);
        continue;
      }
      const next = { ...variant, keys: [...variant.keys], origins: [origin] };
      bySignature.set(signature, next);
      variants.push(next);
    }
  }
  addLocalePluralScenarioVariants(base, variants, bySignature, elements.locale.value);
  annotateNumericOverlapScenarioNotes(bySignature, sourceModel, targetModel, elements.locale.value);
  return { ...base, variants };
}

function annotateNumericOverlapScenarioNotes(bySignature, sourceModel, targetModel, locale) {
  for (const [model, origin] of [[sourceModel, "source"], [targetModel, "target"]]) {
    if (model?.type !== "select") continue;
    const selectors = selectorContractFromModel(model);
    for (const overlap of numericVariantOverlaps(model, selectors, locale)) {
      const note = `${origin}: exact $${overlap.selector.name}: ${overlap.exact} overlaps ${overlap.kind} category ${overlap.category}; ${variantLabel(model.selectors ?? [], overlap.winner.keys ?? [])} appears first and wins.`;
      addScenarioOverlapNote(bySignature, overlap.left.keys, note);
      addScenarioOverlapNote(bySignature, overlap.right.keys, note);
    }
    for (const overlap of selectorPriorityVariantOverlaps(model, selectors, locale)) {
      const note = `${origin}: rows ${variantLabel(model.selectors ?? [], overlap.left.keys ?? [])} and ${variantLabel(model.selectors ?? [], overlap.right.keys ?? [])} can both match; $${overlap.prioritySelector.name} is the first differing selector, so ${variantLabel(model.selectors ?? [], overlap.winner.keys ?? [])} wins.`;
      addScenarioOverlapNote(bySignature, overlap.left.keys, note);
      addScenarioOverlapNote(bySignature, overlap.right.keys, note);
    }
  }
}

function addScenarioOverlapNote(bySignature, keys, note) {
  const variant = bySignature.get(variantSignature(keys ?? []));
  if (!variant) return;
  variant.overlapNotes = Array.from(new Set([...(variant.overlapNotes ?? []), note]));
}

function addLocalePluralScenarioVariants(base, variants, bySignature, locale) {
  const selectors = selectorContractFromModel(base);
  selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return;
    const exactKeys = exactNumericKeysForSelector(selector.name, base);
    for (const category of pluralCategorySetForLocale(locale, kind)) {
      if (category === "other") continue;
      if (!categoryNeedsLocalePluralRow(category, locale, kind, exactKeys)) continue;
      const keys = (base.selectors ?? []).map(() => "*");
      keys[selectorIndex] = category;
      const signature = variantSignature(keys);
      if (bySignature.has(signature)) continue;
      const combinationHints = localePluralCombinationSuggestions(base, selectorIndex, category, bySignature);
      const variant = {
        keys,
        value: "",
        origins: ["locale"],
        localePlural: {
          selector: selector.name,
          category,
          kind,
          locale,
          suggestions: combinationHints.suggestions,
          suggestionsTruncated: combinationHints.truncated,
        },
      };
      bySignature.set(signature, variant);
      variants.push(variant);
    }
  });
}

function scenarioArgumentsForVariant(model, keys, baseArgs, locale) {
  const args = { ...baseArgs };
  (keys ?? []).forEach((key, index) => {
    const selector = model.selectors[index];
    if (!selector || offsetDependencyForSelector(model, selector)) return;
    args[selector] = scenarioValue(model, selector, key, args[selector], locale);
  });
  (keys ?? []).forEach((key, index) => {
    const selector = model.selectors[index];
    const offset = offsetDependencyForSelector(model, selector);
    if (!offset || !isNumericKey(key)) return;
    const exact = Number(key);
    const delta = offsetDeltaForScenario(offset, args);
    args[offset.argName] = exact - delta;
    args[selector] = exact;
  });
  (keys ?? []).forEach((key, index) => {
    const selector = model.selectors[index];
    const offset = offsetDependencyForSelector(model, selector);
    if (!offset || key === "*" || isNumericKey(key) || !PLURAL_CATEGORY_ORDER.includes(key)) return;
    const declaration = model.declarations?.find((item) => item.name === selector);
    const kind = selectorPluralKind(declaration) ?? "cardinal";
    const sample = pluralSampleForCategory(key, locale, exactNumericKeysForSelectorIndex(index, model), kind);
    if (sample == null) return;
    const delta = offsetDeltaForScenario(offset, args);
    args[offset.argName] = sample - delta;
    args[selector] = sample;
  });
  (keys ?? []).forEach((key, index) => {
    if (key !== "*") return;
    const selector = model.selectors[index];
    const offset = offsetDependencyForSelector(model, selector);
    if (!offset) return;
    const dependencyIndex = model.selectors.indexOf(offset.argName);
    if ((keys?.[dependencyIndex] ?? "*") !== "*") return;
    args[offset.argName] = sampleForOffsetFallback(model, index, offset, args, args[offset.argName], locale);
    args[selector] = args[offset.argName] + offsetDeltaForScenario(offset, args);
  });
  return args;
}

function offsetDependencyForSelector(model, selector) {
  const declaration = model.declarations?.find((item) => item.type === "local" && item.name === selector);
  if (!declaration || declaration.function !== "offset" || !declaration.argName) return null;
  const delta = offsetDeltaFromOptionText(declaration.optionText);
  return delta == null ? null : { argName: declaration.argName, ...delta };
}

function offsetDeltaFromOptionText(optionText = "") {
  const add = offsetOptionFromText(optionText, "add");
  const subtract = offsetOptionFromText(optionText, "subtract");
  if ((add == null && subtract == null) || (add != null && subtract != null)) return null;
  const option = add ?? subtract;
  const sign = add != null ? 1 : -1;
  if (option.kind === "literal") return { delta: sign * option.value };
  return { deltaVariable: option.name, deltaSign: sign };
}

function offsetOptionFromText(optionText, name) {
  const token = optionText.match(new RegExp(`(?:^|\\s)${name}=([^\\s]+)(?:\\s|$)`, "u"))?.[1];
  if (token == null) return null;
  if (/^-?\d+$/u.test(token)) return { kind: "literal", value: Number(token) };
  if (/^\$[^\s{}]+$/u.test(token)) return { kind: "variable", name: token.slice(1) };
  return null;
}

function offsetDeltaForScenario(offset, args) {
  if (offset.delta != null) return offset.delta;
  const value = Number(args[offset.deltaVariable]);
  const magnitude = Number.isInteger(value) ? value : 1;
  args[offset.deltaVariable] = magnitude;
  return offset.deltaSign * magnitude;
}

function sampleForOffsetFallback(model, selectorIndex, offset, args, currentValue, locale) {
  const delta = offsetDeltaForScenario(offset, args);
  const dependencyIndex = model.selectors.indexOf(offset.argName);
  const dependencyExactKeys = exactNumericKeysForSelectorIndex(dependencyIndex, model);
  const offsetExactKeys = exactNumericKeysForSelectorIndex(selectorIndex, model);
  const offsetCategoryKeys = categoryKeysForSelectorIndex(selectorIndex, model);
  const declaration = model.declarations?.find((item) => item.name === model.selectors?.[selectorIndex]);
  const kind = selectorPluralKind(declaration) ?? "cardinal";
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    const shifted = candidate + delta;
    if (dependencyExactKeys.has(canonicalNumericKey(candidate))) continue;
    if (shifted < 0) continue;
    if (offsetExactKeys.has(canonicalNumericKey(shifted))) continue;
    if (offsetCategoryKeys.has(pluralCategoryForLocale(shifted, locale, kind))) continue;
    return candidate;
  }
  return currentValue ?? 2;
}

function categoryKeysForSelectorIndex(index, model = state.editorModel) {
  if (index < 0) return new Set();
  return new Set(
    (model?.variants ?? [])
      .map((variant) => variant.keys?.[index])
      .filter((key) => PLURAL_CATEGORY_ORDER.includes(key)),
  );
}

function localePluralCombinationSuggestions(model, pluralIndex, category, existingSignatures, limit = 6) {
  const selectors = model.selectors ?? [];
  const dimensions = selectors.map((_selector, index) => {
    if (index === pluralIndex) return [category];
    const values = nonFallbackSelectorValuesFromModel(model, index);
    return values.length ? values : ["*"];
  });
  if (dimensions.every((values, index) => index === pluralIndex || values.length === 1 && values[0] === "*")) {
    return { suggestions: [], truncated: false };
  }
  const wildcardSignature = variantSignature(selectors.map((_selector, index) => index === pluralIndex ? category : "*"));
  const suggestions = cartesian(dimensions)
    .filter((keys) => {
      const signature = variantSignature(keys);
      return signature !== wildcardSignature && !existingSignatures.has(signature);
    })
    .map((keys) => ({ keys, label: variantLabel(selectors, keys) }));
  return {
    suggestions: suggestions.slice(0, limit),
    truncated: suggestions.length > limit,
  };
}

function nonFallbackSelectorValuesFromModel(model, selectorIndex) {
  const values = [];
  for (const variant of model?.variants ?? []) {
    const key = variant.keys?.[selectorIndex];
    if (!key || key === "*" || values.includes(key)) continue;
    values.push(key);
  }
  return values;
}

function sameSelectors(left, right) {
  return variantSignature(left.selectors ?? []) === variantSignature(right.selectors ?? []);
}

function scenarioRowStatus(row) {
  const origins = row.scenario.origins ?? [];
  if (origins.includes("locale")) return "locale-missing";
  if (origins.includes("source") && !origins.includes("target")) return "source-only";
  if (origins.includes("target") && !origins.includes("source")) {
    const assessment = targetOnlyVariantAssessment(
      row.scenario,
      selectorContractFromModel(state.editorModel),
      elements.locale.value,
      selectorValueAllowlistForCurrentModels(),
    );
    return assessment.status === "locale-recommended" ? "locale-recommended" : "target-only";
  }
  if (row.source !== row.target) return "render-diff";
  return "aligned";
}

function scenarioSummary(rows) {
  const counts = {
    total: rows.length,
    aligned: 0,
    "render-diff": 0,
    "source-only": 0,
    "target-only": 0,
    "locale-missing": 0,
    "locale-recommended": 0,
  };
  for (const row of rows) {
    counts[row.status] = (counts[row.status] ?? 0) + 1;
  }
  counts.attention = counts["source-only"] + counts["target-only"] + counts["locale-missing"];
  return counts;
}

function scenarioOrderSummary(rows) {
  const notes = new Set();
  let rowCount = 0;
  for (const row of rows) {
    const rowNotes = row.overlapNotes ?? row.scenario?.overlapNotes ?? [];
    if (!rowNotes.length) continue;
    rowCount++;
    for (const note of rowNotes) notes.add(note);
  }
  return { rowCount, noteCount: notes.size, notes: [...notes] };
}

function renderScenarioOrderSummary(summary) {
  if (!summary.rowCount) return "";
  return `
    <div class="scenario-order-summary" role="note">
      <strong>Row order affects ${summary.rowCount} scenario row(s).</strong>
      MF2 tries variant rows in source order; if fixed values, CLDR categories,
      or wildcards can all match, the first matching row wins.
      ${renderScenarioOrderSummaryNotes(summary.notes)}
    </div>
  `;
}

function renderScenarioOrderSummaryNotes(notes = []) {
  if (!notes.length) return "";
  return `
    <ul>
      ${notes.slice(0, 4).map((note) => `<li>${escapeHtml(note)}</li>`).join("")}
      ${notes.length > 4 ? `<li>${escapeHtml(`${notes.length - 4} more order note(s) in rows below.`)}</li>` : ""}
    </ul>
  `;
}

function renderScenarioSummary(summary) {
  return [
    ["total", "total"],
    ["aligned", "aligned"],
    ["render-diff", "text differs"],
    ["attention", "needs review"],
    ["source-only", "source rows missing"],
    ["target-only", "target-specific"],
    ["locale-missing", "target-locale gaps"],
    ["locale-recommended", "target-locale rows"],
  ].map(([key, label]) => `
    <span class="scenario-summary-chip ${summary[key] > 0 && ["render-diff", "attention", "source-only", "target-only", "locale-missing"].includes(key) ? "needs-review" : ""}">
      <strong>${summary[key] ?? 0}</strong> ${label}
    </span>
  `).join("");
}

function scenarioRowMatchesFilter(row, filter) {
  if (filter === "attention") return row.status === "source-only" || row.status === "target-only" || row.status === "locale-missing";
  if (filter === "source-only") return row.status === "source-only";
  if (filter === "target-only") return row.status === "target-only";
  if (filter === "locale-missing") return row.status === "locale-missing" || row.status === "locale-recommended";
  return true;
}

function scenarioStatusLabel(status) {
  if (status === "source-only") return "source row missing";
  if (status === "target-only") return "target-specific row";
  if (status === "locale-missing") return "target-locale CLDR row missing";
  if (status === "locale-recommended") return "target-locale CLDR row present";
  if (status === "render-diff") return "translation differs";
  return "aligned";
}

function localePluralCoverageRows(model = state.editorModel, locale = elements.locale.value) {
  if (model?.type !== "select") return [];
  const selectors = selectorContractFromModel(model);
  return selectors.flatMap((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return [];
    const targetKeys = new Set(
      (model.variants ?? []).map((variant) => variantKeyValue((variant.keys ?? [])[selectorIndex] ?? "*")),
    );
    const exactKeys = [...exactNumericKeysForSelectorIndex(selectorIndex, model)];
    const exactKeySet = new Set(exactKeys);
    return [{
      selector: selector.name,
      kind,
      locale,
      exactKeys,
      categories: [...pluralCategorySetForLocale(locale, kind)].map((category) => {
        const uncoveredSample = pluralSampleForCategory(category, locale, exactKeySet, kind);
        const sample = uncoveredSample ?? pluralSampleForCategory(category, locale, new Set(), kind);
        return {
          category,
          sample,
          state: pluralCoverageCategoryState(category, targetKeys, uncoveredSample, sample, exactKeySet),
        };
      }),
    }];
  });
}

function pluralCoverageCategoryState(category, targetKeys, uncoveredSample = null, sample = null, exactKeys = new Set()) {
  if (targetKeys.has(category)) return "explicit";
  if (uncoveredSample == null && sample != null && exactKeys.has(canonicalNumericKey(sample))) return "exact";
  if (targetKeys.has("*")) return category === "other" ? "fallback-other" : "fallback";
  return "missing";
}

function pluralCoverageStateLabel(state) {
  if (state === "explicit") return "explicit row";
  if (state === "exact") return "fixed row";
  if (state === "fallback-other") return "fallback";
  if (state === "fallback") return "fallback covers";
  return "missing";
}

function renderLocalePluralCoverage(rows) {
  if (!rows.length) return "";
  return `
    <div class="scenario-plural-coverage">
      <h3>Target-locale plural coverage</h3>
      ${rows.map((row) => `
        <div class="scenario-plural-coverage-row">
          <div class="scenario-plural-coverage-heading">
            <strong>$${escapeHtml(row.selector)}</strong>
            <span>${escapeHtml(row.locale)} ${escapeHtml(row.kind)}</span>
            ${row.exactKeys.length ? `<small>exact rows: ${escapeHtml(row.exactKeys.join(", "))}</small>` : ""}
          </div>
          <div class="scenario-plural-coverage-pills">
            ${row.categories.map((category) => `
              <span class="scenario-coverage-pill coverage-${escapeHtml(category.state)}">
                <strong>${escapeHtml(category.category)}</strong>
                ${category.sample == null ? "" : `<small>${escapeHtml(String(category.sample))}</small>`}
                <em>${escapeHtml(pluralCoverageStateLabel(category.state))}</em>
              </span>
            `).join("")}
          </div>
        </div>
      `).join("")}
    </div>
  `;
}

function scenarioStatusClass(status) {
  return ["source-only", "target-only", "locale-missing", "locale-recommended"].includes(status) ? `scenario-${status}` : "";
}

function scenarioReviewReason(row) {
  if (row.status === "source-only") {
    return `Source defines ${row.scenario.label}; the target falls back. Add the source row only if fallback wording is not intentional.`;
  }
  if (row.status === "locale-missing") {
    const detail = row.scenario.localePlural;
    if (detail) {
      return `Target locale ${detail.locale} uses CLDR ${detail.kind} category ${detail.category} for $${detail.selector}. Add a target-locale row only if fallback wording is not enough.`;
    }
    return "The target locale has a CLDR plural category that currently reaches fallback wording.";
  }
  if (row.status === "target-only") {
    return targetOnlyVariantAssessment(
      row.scenario,
      selectorContractFromModel(state.editorModel),
      elements.locale.value,
      selectorValueAllowlistForCurrentModels(),
    ).message;
  }
  if (row.status === "locale-recommended") {
    const assessment = targetOnlyVariantAssessment(
      row.scenario,
      selectorContractFromModel(state.editorModel),
      elements.locale.value,
      selectorValueAllowlistForCurrentModels(),
    );
    return `${assessment.message} This is expected when the target locale needs a finer plural distinction than the source.`;
  }
  if (row.status === "render-diff") {
    return "Target covers the same variant; text differs, which is normal for translation.";
  }
  return "Source and target cover this variant with the current samples.";
}

function renderScenarioAction(row) {
  if (row.status === "locale-missing") {
    const suggestions = row.scenario.localePlural?.suggestions ?? [];
    return `
      <div class="scenario-row-actions">
        <button type="button" class="scenario-action" data-scenario-action="add-target-row" data-scenario-index="${row.index}">
          ${escapeHtml(scenarioAddActionLabel(row))}
        </button>
        ${suggestions.map((suggestion, index) => `
          <button
            type="button"
            class="scenario-action"
            data-scenario-action="add-specific-target-row"
            data-scenario-index="${row.index}"
            data-scenario-specific-index="${index}"
            title="Add a target row for ${escapeHtml(suggestion.label ?? variantLabel(state.editorModel?.selectors ?? [], suggestion.keys))}"
          >
            Add specific row ${escapeHtml(suggestion.label ?? suggestion.keys.join(" "))}
          </button>
        `).join("")}
      </div>
    `;
  }
  if (row.status === "source-only") {
    return `
      <button type="button" class="scenario-action" data-scenario-action="add-target-row" data-scenario-index="${row.index}">
        ${escapeHtml(scenarioAddActionLabel(row))}
      </button>
    `;
  }
  if (row.status === "target-only" || row.status === "locale-recommended") {
    return `
      <div class="scenario-row-actions">
        <button type="button" class="scenario-action" data-scenario-action="select-target-row" data-scenario-index="${row.index}">
          Select row
        </button>
        ${row.status === "target-only" ? `
          <button type="button" class="scenario-action" data-scenario-action="remove-target-row" data-scenario-index="${row.index}">
            Remove row
          </button>
        ` : ""}
      </div>
    `;
  }
  return '<span class="hint-inline">No action needed</span>';
}

function scenarioAddActionLabel(row) {
  if (row.status === "locale-missing") return "Add target-locale CLDR row";
  const details = row.scenario?.details ?? [];
  if (details.some((detail) => detail.kind === "exact")) return "Add source-defined fixed row";
  if (details.some((detail) => detail.kind === "category")) return "Add source-defined CLDR row";
  if (details.some((detail) => detail.kind === "value")) return "Add source-defined context row";
  return "Add source-defined fallback row";
}

function addTargetVariantFromScenario(row) {
  if (state.editorModel?.type !== "select") return;
  const added = addScenarioVariants([row]);
  if (!added.length) {
    const keys = row.scenario.keys ?? [];
    elements.status.textContent = `Variant ${keys.join(" ")} already exists.`;
    return;
  }
  state.activeVariant = added[0].index;
  state.scenarioFilter = "all";
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Added target variant ${added[0].keys.join(" ")}.`;
  void refreshFromSource(source);
}

function addSpecificTargetVariantFromScenario(row, suggestionIndex) {
  if (state.editorModel?.type !== "select") return;
  const suggestion = row.scenario.localePlural?.suggestions?.[suggestionIndex];
  if (!suggestion?.keys?.length) {
    elements.status.textContent = "No specific row suggestion was found.";
    return;
  }
  const specificRow = {
    ...row,
    scenario: {
      ...row.scenario,
      keys: [...suggestion.keys],
    },
  };
  const added = addScenarioVariants([specificRow]);
  if (!added.length) {
    elements.status.textContent = `Variant ${suggestion.keys.join(" ")} already exists.`;
    return;
  }
  state.activeVariant = added[0].index;
  state.scenarioFilter = "all";
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Added specific target variant ${suggestion.label ?? added[0].keys.join(" ")}.`;
  void refreshFromSource(source);
}

function addMissingTargetVariantsFromRows(rows, label) {
  if (state.editorModel?.type !== "select") return;
  const added = addScenarioVariants(rows.filter((row) => row.status === "source-only" || row.status === "locale-missing"));
  if (!added.length) {
    elements.status.textContent = `No ${label} rows to add.`;
    return;
  }
  state.activeVariant = added[0].index;
  state.scenarioFilter = "all";
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Added ${added.length} ${label} row(s).`;
  void refreshFromSource(source);
}

function addScenarioVariants(rows) {
  if (state.editorModel?.type !== "select") return [];
  let insertIndex = fallbackIndex();
  const added = [];
  for (const row of rows) {
    const keys = row.scenario.keys ?? [];
    if (!keys.length || variantExists(keys)) continue;
    const variant = {
      keys: [...keys],
      value: templateValueForKeys(keys, -1),
    };
    state.editorModel.variants.splice(insertIndex, 0, {
      ...variant,
    });
    const insertedVariant = state.editorModel.variants[insertIndex];
    added.push({ index: insertIndex, keys: [...keys], variant: insertedVariant });
    insertIndex++;
  }
  if (added.length) {
    normalizeExactNumericRowOrder(primaryPluralSelector(), added[0].variant);
    added.forEach((item) => {
      item.index = state.editorModel.variants.indexOf(item.variant);
    });
  }
  return added;
}

function removeTargetVariantsFromRows(rows) {
  if (state.editorModel?.type !== "select") return;
  const signatures = new Set(
    rows
      .filter((row) => row.status === "target-only")
      .map((row) => variantSignature(row.scenario.keys ?? [])),
  );
  if (!signatures.size) {
    elements.status.textContent = "No target-specific rows to remove.";
    return;
  }
  const removed = [];
  for (let index = state.editorModel.variants.length - 1; index >= 0; index--) {
    const variant = state.editorModel.variants[index];
    const signature = variantSignature(variant.keys);
    if (!signatures.has(signature)) continue;
    if (!canRemoveTargetVariant(index)) continue;
    removed.push({ index, keys: [...variant.keys] });
    state.editorModel.variants.splice(index, 1);
  }
  if (!removed.length) {
    elements.status.textContent = "No removable target-specific rows found.";
    return;
  }
  state.activeVariant = Math.max(0, Math.min(state.activeVariant, state.editorModel.variants.length - 1));
  state.scenarioFilter = "all";
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Removed ${removed.length} target-specific row(s).`;
  void refreshFromSource(source);
}

function canRemoveTargetVariant(index) {
  const variants = state.editorModel?.variants ?? [];
  if (variants.length <= 1) return false;
  const variant = variants[index];
  const isFallback = variant?.keys?.every((key) => key === "*");
  if (!isFallback) return true;
  return variants.some((item, itemIndex) => itemIndex !== index && item.keys.every((key) => key === "*"));
}

function selectTargetVariantFromScenario(row) {
  if (state.editorModel?.type !== "select") return;
  const signature = variantSignature(row.scenario.keys ?? []);
  const index = state.editorModel.variants.findIndex((variant) => variantSignature(variant.keys) === signature);
  if (index < 0) {
    elements.status.textContent = `Target variant ${row.scenario.label} was not found.`;
    return;
  }
  state.activeVariant = index;
  renderVariantTabs();
  renderVariantKeyEditor();
  renderProseMirrorVariant();
  elements.status.textContent = `Selected target variant ${row.scenario.label}.`;
}

function renderScenarioCase(scenario) {
  return `
    <div class="scenario-case">
      <div class="scenario-case-heading">
        <strong>${escapeHtml(scenario.label)}</strong>
        <span class="scenario-origin">${escapeHtml(scenarioOriginLabel(scenario.origins))}</span>
        <span class="scenario-row-kind scenario-row-kind-${escapeHtml(scenario.rowKind ?? "fallback")}">${escapeHtml(scenarioRowKindLabel(scenario.rowKind))}</span>
      </div>
      ${(scenario.details ?? []).map((detail) => `
        <span class="scenario-detail scenario-detail-${escapeHtml(detail.kind)}">
          <code>${escapeHtml(detail.selector)}</code>
          <strong>${escapeHtml(scenarioKindLabel(detail))}</strong>
          <code>${escapeHtml(detail.key)}</code>
          ${detail.sample !== undefined ? `<em>${escapeHtml(scenarioSampleLabel(detail))}</em>` : ""}
        </span>
      `).join("")}
      ${renderLocalePluralSuggestions(scenario.localePlural)}
      ${renderScenarioOverlapNotes(scenario.overlapNotes)}
    </div>
  `;
}

function renderScenarioOverlapNotes(notes = []) {
  if (!notes.length) return "";
  return `
    <span class="scenario-suggestions">
      order note:
      ${notes.map((note) => `<em>${escapeHtml(note)}</em>`).join("")}
    </span>
  `;
}

function renderLocalePluralSuggestions(localePlural) {
  const suggestions = localePlural?.suggestions ?? [];
  if (!suggestions.length) return "";
  return `
    <span class="scenario-suggestions">
      specific rows to consider:
      ${suggestions.map((item) => `<code>${escapeHtml(item.label ?? item.keys.join(" "))}</code>`).join("")}
      ${localePlural.suggestionsTruncated ? "<em>and more</em>" : ""}
    </span>
  `;
}

function scenarioOriginLabel(origins = []) {
  if (origins.includes("source") && origins.includes("target")) return "source + target";
  if (origins.includes("source")) return "source";
  if (origins.includes("target")) return "target";
  if (origins.includes("locale")) return "target locale recommendation";
  return "coverage";
}

function scenarioSampleLabel(detail) {
  return `sample ${detail.sample}`;
}

function scenarioKindLabel(detail) {
  if (detail.kind === "exact") return "fixed value";
  if (detail.kind === "category") return "CLDR category";
  if (detail.kind === "fallback") return "fallback";
  return "context value";
}

function scenarioRowKind(variant, details) {
  if (variant.localePlural) return "target-locale";
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

function variantScenarioDetails(model, keys, args) {
  return (model.selectors ?? []).map((selector, index) => {
    const key = keys[index] ?? "*";
    return {
      selector,
      key,
      sample: args[selector],
      kind: scenarioKeyKind(model, selector, key),
    };
  });
}

function scenarioKeyKind(model, selector, key) {
  const declaration = model.declarations?.find((item) => item.name === selector);
  const isPlural = isPluralSelectorFunction(declaration?.function);
  if (key === "*") return "fallback";
  if (isPlural && isNumericKey(key)) return "exact";
  if (isPlural) return "category";
  return "value";
}

function scenarioValue(model, selector, key, currentValue, locale = elements.locale.value) {
  const declaration = model.declarations?.find((item) => item.name === selector);
  const isPlural = isPluralSelectorFunction(declaration?.function);
  if (isPlural) {
    const kind = selectorPluralKind(declaration) ?? "cardinal";
    if (isNumericKey(key)) return Number(key);
    const excluded = exactNumericKeysForSelector(selector, model);
    if (key !== "*") {
      return pluralExampleValue(key, excluded, locale, kind);
    }
    return pluralExampleValue("other", excluded, locale, kind);
  }
  if (key !== "*") return key;
  return currentValue ?? state.args[selector] ?? defaultArgs[selector] ?? "unknown";
}

function pluralExampleValue(category, excluded = new Set(), locale = elements.locale.value, kind = "cardinal") {
  if (kind === "cardinal") {
    const metadata = state.pluralMetadata?.categories?.find((item) => item.category === category);
    for (const example of metadata?.examples ?? []) {
      const numeric = String(example).match(/-?\d+(?:\.\d+)?/u)?.[0];
      if (numeric != null && !excluded.has(canonicalNumericKey(numeric))) return Number(numeric);
    }
  }
  const sample = pluralSampleForCategory(category, locale, excluded, kind);
  if (sample != null) return sample;
  const preferred = { zero: 0, one: 1, two: 2, few: 3, many: 5, other: 2 }[category];
  if (preferred != null && !excluded.has(canonicalNumericKey(preferred))) return preferred;
  return PLURAL_SAMPLE_CANDIDATES.find((candidate) => !excluded.has(canonicalNumericKey(candidate))) ?? 2;
}

function pluralSampleForCategory(category, locale, excluded = new Set(), kind = "cardinal") {
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    if (excluded.has(canonicalNumericKey(candidate))) continue;
    if (category === pluralCategoryForLocale(candidate, locale, kind)) return candidate;
  }
  return null;
}

function pluralCategoryForCurrentLocale(value) {
  return pluralCategoryForLocale(value, elements.locale.value);
}

function pluralCategoryForLocale(value, locale, kind = "cardinal") {
  try {
    return selectPluralCategory(locale, Number(value), kind);
  } catch {
    return null;
  }
}

function exactNumericKeysForSelector(selector, model = state.editorModel) {
  const index = model?.selectors?.indexOf(selector) ?? -1;
  return exactNumericKeysForSelectorIndex(index, model);
}

function exactNumericKeysForSelectorIndex(index, model = state.editorModel) {
  if (index < 0) return new Set();
  return new Set(
    (model?.variants ?? [])
      .map((variant) => variant.keys[index])
      .filter(isNumericKey)
      .map(canonicalNumericKey),
  );
}

async function refreshSourceContract(source) {
  const response = await parseForLab(source);
  state.sourceContract = {
    source,
    model: response.model,
    diagnostics: [...(response.diagnostics ?? []), ...(response.formatErrors ?? [])],
    selectors: selectorContractFromModel(response.model),
    markup: markupFromRustModel(response.model),
    placeholders: placeholderCountsFromRustModel(response.model),
    requirements: placeholderRequirementsFromRustModel(response.model),
  };
  renderSourceContractSummary();
  renderSourceProsePreview();
  renderPlaceholderOptions();
  renderMarkupOptions();
  forceLinting(cmView);
  void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true });
}

async function parseForLab(source) {
  const response = parseWithCore(source, state.args, elements.locale.value);
  if (elements.compareRust?.checked) {
    response.diagnostics.push(...await rustComparisonDiagnostics(source, response.output));
  }
  return response;
}

function parseWithCore(source, args, locale) {
  const parsed = parseToModel(source);
  const diagnostics = [...(parsed.diagnostics ?? [])].map(editorDiagnostic);
  let output = "";
  let parts = [];
  let formatErrors = [];
  if (parsed.model) {
    const formatted = formatWithCoreModel(parsed.model, args, locale);
    output = formatted.output;
    parts = formatted.parts;
    formatErrors = formatted.errors;
  }
  return {
    model: parsed.model,
    diagnostics,
    formatErrors,
    output,
    parts,
    backend: "JavaScript core",
  };
}

function editorDiagnostic(diagnostic) {
  return {
    severity: diagnostic.severity === "warning" ? "warning" : "error",
    code: diagnostic.code ?? diagnostic.name ?? "runtime-error",
    message: diagnostic.message ?? String(diagnostic),
    start: diagnostic.start,
    end: diagnostic.end,
  };
}

function formatWithCore(source, args, locale) {
  const parsed = parseToModel(source);
  if (!parsed.model) return { output: "", errors: parsed.diagnostics ?? [] };
  return formatWithCoreModel(parsed.model, args, locale);
}

function formatWithCoreModel(model, args, locale) {
  try {
    const result = formatMessageToPartsWithFallback(model, coerceArguments(args), {
      locale,
      bidiIsolation: "default",
    });
    return {
      output: partsToString(result.parts, "default"),
      parts: result.parts,
      errors: (result.errors ?? []).map(editorDiagnostic),
    };
  } catch (error) {
    return {
      output: "",
      parts: [],
      errors: [editorDiagnostic(error)],
    };
  }
}

async function rustComparisonDiagnostics(source, javascriptOutput) {
  try {
    const response = await parseWithRust(source);
    const diagnostics = [...(response.diagnostics ?? []), ...(response.formatErrors ?? [])].map((diagnostic) => ({
      ...diagnostic,
      code: `rust-${diagnostic.code}`,
    }));
    if ((response.output ?? "") !== (javascriptOutput ?? "")) {
      diagnostics.push({
        severity: "warning",
        code: "rust-output-mismatch",
        message: `Rust output differs from JavaScript core output: ${JSON.stringify(response.output ?? "")}`,
      });
    }
    return diagnostics;
  } catch (error) {
    return [{
      severity: "warning",
      code: "rust-compare-unavailable",
      message: `Rust comparison unavailable: ${error.message ?? String(error)}`,
    }];
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

async function refreshPluralMetadata() {
  state.pluralMetadata = pluralMetadataForLocale(elements.locale.value);
  renderPluralTools();
  renderRowHelperStrip();
}

function pluralMetadataForLocale(locale) {
  const examplesByCategory = new Map();
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    let category;
    try {
      category = selectPluralCategory(locale, candidate, "cardinal");
    } catch {
      category = "other";
    }
    const examples = examplesByCategory.get(category) ?? [];
    if (examples.length < 5) examples.push(String(candidate));
    examplesByCategory.set(category, examples);
  }
  const categories = PLURAL_CATEGORY_ORDER
    .filter((category) => examplesByCategory.has(category))
    .map((category) => ({ category, examples: examplesByCategory.get(category) }));
  return { locale, categories };
}

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
  return new Set(PLURAL_CATEGORY_ORDER.filter((category) => categories.has(category)));
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
      void refreshFromSource(cmView.state.doc.toString(), { keepVariantEditor: true, skipArguments: true });
    });
    elements.arguments.append(label);
  }
}

function renderPreview(response) {
  elements.rendered.value = response.output ?? "";
  const errors = validationDiagnostics(response, cmView.state.doc.toString());
  elements.status.textContent = errors.length ? `${errors.length} issue(s) - ${response.backend}` : `Valid - ${response.backend}`;
}

function renderPartsPreview(response) {
  if (!elements.partsPreview) return;
  elements.escapedRendered.textContent = visibleControlText(response.output ?? "");
  const parts = response.parts ?? [];
  if (!parts.length) {
    renderHtmlPartsPreview([]);
    elements.partsPreview.innerHTML = '<p class="hint-inline">No formatted parts.</p>';
    return;
  }
  renderHtmlPartsPreview(parts);
  elements.partsPreview.innerHTML = parts.map((part, index) => `
    <div class="part-row">
      <span class="part-index">${index + 1}</span>
      <span class="part-type">${escapeHtml(part.type ?? "unknown")}</span>
      <span class="part-value">${escapeHtml(visibleControlText(partDisplayValue(part)))}</span>
      <code>${escapeHtml(partMetadata(part))}</code>
    </div>
  `).join("");
}

function renderHtmlPartsPreview(parts) {
  if (!elements.htmlPartsPreview) return;
  if (!parts.length) {
    elements.htmlPartsPreview.innerHTML = '<span class="hint-inline">No formatted parts.</span>';
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const part of parts) {
    if (part.type === "text") {
      fragment.append(document.createTextNode(part.value ?? ""));
    } else if (part.type === "expression") {
      const bdi = document.createElement("bdi");
      bdi.dir = htmlDirection(part.direction);
      bdi.textContent = part.value ?? "";
      fragment.append(bdi);
    } else if (part.type === "fallback") {
      fragment.append(document.createTextNode(`{${part.source ?? ""}}`));
    } else if (part.type === "markup") {
      const chip = document.createElement("span");
      chip.className = `html-markup-chip html-markup-${part.kind ?? "unknown"}`;
      chip.textContent = markupChipLabel(part);
      fragment.append(chip);
    }
  }
  elements.htmlPartsPreview.replaceChildren(fragment);
}

function htmlDirection(direction) {
  return direction === "ltr" || direction === "rtl" ? direction : "auto";
}

function markupChipLabel(part) {
  if (part.kind === "close") return `{/${part.name ?? ""}}`;
  if (part.kind === "standalone") return `{#${part.name ?? ""}/}`;
  return `{#${part.name ?? ""}}`;
}

function partDisplayValue(part) {
  if (part.type === "markup") {
    const prefix = part.kind === "close" ? "/" : part.kind === "standalone" ? "#" : "#";
    return `${part.kind ?? "markup"} ${prefix}${part.name ?? ""}`;
  }
  if (part.type === "fallback") return part.source ?? "";
  return part.value ?? "";
}

function partMetadata(part) {
  const metadata = { ...part };
  delete metadata.type;
  delete metadata.value;
  delete metadata.source;
  if (Object.keys(metadata).length === 0) return "";
  return JSON.stringify(metadata);
}

function visibleControlText(value) {
  let output = "";
  for (const char of String(value)) {
    output += visibleControlToken(char.codePointAt(0)) ?? char;
  }
  return output;
}

function visibleControlToken(codePoint) {
  const labels = {
    0x200e: "<LRM>",
    0x200f: "<RLM>",
    0x202a: "<LRE>",
    0x202b: "<RLE>",
    0x202c: "<PDF>",
    0x202d: "<LRO>",
    0x202e: "<RLO>",
    0x2066: "<LRI>",
    0x2067: "<RLI>",
    0x2068: "<FSI>",
    0x2069: "<PDI>",
  };
  return labels[codePoint] ?? null;
}

function renderDiagnostics(response) {
  const diagnostics = targetDiagnostics(response, cmView.state.doc.toString());
  elements.diagnostics.innerHTML = "";
  if (!diagnostics.length) {
    elements.diagnostics.innerHTML = '<li class="ok">No parser or runtime issues.</li>';
    return;
  }
  for (const diagnostic of diagnostics) {
    const item = document.createElement("li");
    item.className = diagnostic.severity === "warning" ? "warning" : "error";
    item.textContent = `${diagnostic.code}: ${diagnostic.message}`;
    elements.diagnostics.append(item);
  }
}

function renderInlineDiagnostics(response) {
  if (!elements.inlineDiagnostics) return;
  const diagnostics = targetDiagnostics(response, cmView.state.doc.toString());
  if (!diagnostics.length) {
    elements.inlineDiagnostics.innerHTML = '<span class="inline-diagnostic-ok">No parser/runtime issues.</span>';
    return;
  }
  const visible = diagnostics.slice(0, 3);
  elements.inlineDiagnostics.innerHTML = `
    ${visible.map((diagnostic) => `
      <span class="inline-diagnostic ${diagnostic.severity === "warning" ? "warning" : "error"}">
        <strong>${escapeHtml(diagnostic.code)}</strong>
        ${escapeHtml(diagnostic.message)}
      </span>
    `).join("")}
    ${diagnostics.length > visible.length ? `<span class="inline-diagnostic-more">${diagnostics.length - visible.length} more</span>` : ""}
  `;
}

function renderSourceContractSummary() {
  if (!elements.sourceContractSummary) return;
  const contract = state.sourceContract;
  if (!contract?.model) {
    const diagnostics = contract?.diagnostics ?? [];
    elements.sourceContractSummary.innerHTML = diagnostics.length
      ? `<span class="contract-error">${escapeHtml(diagnostics[0].code)}: ${escapeHtml(diagnostics[0].message)}</span>`
      : '<span class="hint-inline">Source contract unavailable.</span>';
    return;
  }
  const placeholders = [...contract.placeholders.entries()].sort(([left], [right]) => left.localeCompare(right));
  const selectors = contract.selectors ?? [];
  const markup = [...contract.markup.values()].sort((left, right) => left.name.localeCompare(right.name));
  const variants = contract.requirements;
  elements.sourceContractSummary.innerHTML = `
    <div class="contract-row contract-row-wide">
      <strong>Source inputs</strong>
      <div class="contract-inputs">
        ${renderSourceInputContract(contract)}
      </div>
    </div>
    <div class="contract-row">
      <strong>Allowed selectors</strong>
      <div class="contract-chips">
        ${selectors.length ? selectors.map((selector) => `
          <span class="contract-chip">$${escapeHtml(selector.name)} <small>${escapeHtml(selectorAnnotationLabel(selector))}</small></span>
        `).join("") : '<span class="hint-inline">none</span>'}
      </div>
    </div>
    <div class="contract-row debug-only">
      <strong>Allowed placeholders</strong>
      <div class="contract-chips">
        ${placeholders.length ? placeholders.map(([name, count]) => `
          <span class="contract-chip">{$${escapeHtml(name)}}${count > 1 ? ` x${count}` : ""}</span>
        `).join("") : '<span class="hint-inline">none</span>'}
      </div>
    </div>
    <div class="contract-row">
      <strong>Allowed markup</strong>
      <div class="contract-chips">
        ${markup.length ? markup.map((item) => `
          <span class="contract-chip">{#${escapeHtml(item.name)}} <small>${escapeHtml(markupContractLabel(item))}</small></span>
        `).join("") : '<span class="hint-inline">none</span>'}
      </div>
    </div>
    <div class="contract-row debug-only">
      <strong>Variant requirements</strong>
      <div class="contract-variants">
        ${variants.map((variant) => `
          <span><b>${escapeHtml(variant.label)}</b>: ${escapeHtml(requirementLabel(variant.requirements))}</span>
        `).join("")}
      </div>
    </div>
  `;
}

function renderSourceInputContract(contract) {
  const rows = sourceInputContractItems(contract).map((item) => `<code>${escapeHtml(item)}</code>`);
  return rows.join("") || '<span class="hint-inline">none</span>';
}

function sourceInputContractItems(contract = state.sourceContract) {
  const model = editorModelFromRust(contract?.model, contract?.source ?? "");
  const declared = new Set();
  const rows = (model?.declarations ?? []).map((declaration) => {
    declared.add(declaration.name);
    return sourceDeclarationLabel(declaration);
  });
  for (const [name] of [...(contract?.placeholders ?? new Map()).entries()].sort(([left], [right]) => left.localeCompare(right))) {
    if (declared.has(name)) continue;
    rows.push(`{$${name}}`);
  }
  return rows;
}

function sourceDeclarationLabel(declaration) {
  const annotation = declaration.function
    ? ` :${declaration.function}${declaration.optionText ? ` ${declaration.optionText}` : ""}`
    : "";
  if (declaration.type === "local") {
    const arg = declaration.argName ? `$${declaration.argName}` : "";
    return `.local $${declaration.name} = {${arg}${annotation}}`;
  }
  return `.input {$${declaration.name}${annotation}}`;
}

function renderSourceProsePreview() {
  if (!elements.sourceProsePreview) return;
  const sourceModel = editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? "");
  if (!sourceModel) {
    const diagnostic = state.sourceContract?.diagnostics?.[0];
    elements.sourceProsePreview.innerHTML = diagnostic
      ? `<span class="contract-error">${escapeHtml(diagnostic.code)}: ${escapeHtml(diagnostic.message)}</span>`
      : '<span class="hint-inline">Source prose unavailable.</span>';
    return;
  }
  const selected = selectedSourceProsePattern(sourceModel);
  elements.sourceProsePreview.innerHTML = `
    <div class="source-prose-row">
      <div class="source-prose-heading">
        <strong>Source</strong>
        <span>${escapeHtml(selected.label)}</span>
      </div>
      <div class="source-prose-line" dir="auto">${renderPatternAsProse(selected.pattern)}</div>
    </div>
  `;
}

function selectedSourceProsePattern(sourceModel) {
  if (sourceModel.type !== "select") {
    return { label: "Message", pattern: sourceModel.pattern ?? "" };
  }
  const activeTarget = state.editorModel?.type === "select"
    ? state.editorModel.variants[state.activeVariant]
    : null;
  if (activeTarget) {
    const signature = variantSignature(activeTarget.keys ?? []);
    const exact = sourceModel.variants.find((variant) => variantSignature(variant.keys ?? []) === signature);
    if (exact) {
      return {
        label: variantLabel(sourceModel.selectors ?? [], exact.keys ?? []),
        pattern: exact.value ?? "",
      };
    }
  }
  const fallback = sourceModel.variants.find((variant) => (variant.keys ?? []).every((key) => key === "*"));
  if (fallback) {
    return {
      label: `${variantLabel(sourceModel.selectors ?? [], fallback.keys ?? [])} source fallback`,
      pattern: fallback.value ?? "",
    };
  }
  const first = sourceModel.variants[0];
  return {
    label: first ? variantLabel(sourceModel.selectors ?? [], first.keys ?? []) : "No source form",
    pattern: first?.value ?? "",
  };
}

function renderPatternAsProse(pattern) {
  return splitPatternTokens(pattern)
    .map((part) => {
      if (part.type === "placeholder") {
        return `<span class="placeholder-chip source-prose-chip" title="Source placeholder">{$${escapeHtml(part.name)}}</span>`;
      }
      if (part.type === "markup") {
        return `<span class="markup-chip markup-${escapeHtml(part.kind)}" title="Source markup">${escapeHtml(part.source)}</span>`;
      }
      return escapeHtml(part.value ?? "");
    })
    .join("");
}

function requirementLabel(requirements) {
  if (!requirements.length) return "no placeholders";
  return requirements.map((item) => `{$${item.name}}${item.count > 1 ? ` x${item.count}` : ""}`).join(", ");
}

function renderPlaceholderOptions() {
  if (!elements.placeholderSelect) return;
  const current = elements.placeholderSelect.value;
  const names = sourcePlaceholderNames();
  elements.placeholderSelect.innerHTML = names.map((name) => `<option value="${escapeHtml(name)}">{$${escapeHtml(name)}}</option>`).join("");
  if (names.includes(current)) elements.placeholderSelect.value = current;
  elements.placeholderSelect.disabled = names.length === 0;
  elements.insertPlaceholder.disabled = names.length === 0;
}

function renderMarkupOptions() {
  if (!elements.markupSelect) return;
  const current = elements.markupSelect.value;
  const names = sourceMarkupNames();
  elements.markupSelect.innerHTML = names.map((name) => `<option value="${escapeHtml(name)}">{#${escapeHtml(name)}}</option>`).join("");
  if (names.includes(current)) elements.markupSelect.value = current;
  elements.markupSelect.disabled = names.length === 0;
  elements.wrapMarkup.disabled = names.length === 0;
}

function sourcePlaceholderNames() {
  const names = state.sourceContract?.placeholders?.size
    ? [...state.sourceContract.placeholders.keys()]
    : state.editorModel?.variables ?? [];
  return [...new Set(names)].sort();
}

function placeholderNamesFromRawSource(source) {
  const parsed = parseToModel(source);
  return [...new Set([
    ...(parsed.model ? variablesFromRustModel(parsed.model) : []),
    ...mf2PlaceholderNamesFromText(source),
  ])].sort();
}

function mf2PlaceholderAutocompletion(namesProvider) {
  return [
    autocompletion({
      activateOnTyping: true,
      override: [
        (context) => mf2PlaceholderCompletionSource(context, namesProvider),
      ],
    }),
    EditorView.updateListener.of((update) => {
      if (!update.docChanged || !update.state.selection.main.empty) return;
      const pos = update.state.selection.main.head;
      if (!mf2PlaceholderCompletionContext(update.state.doc.toString(), pos)) return;
      const status = completionStatus(update.state);
      if (status !== "active" && status !== "pending") startCompletion(update.view);
    }),
  ];
}

function mf2PlaceholderCompletionSource(context, namesProvider) {
  const completion = mf2PlaceholderCompletionContext(context.state.doc.toString(), context.pos);
  if (!completion && !context.explicit) return null;
  const names = mf2PlaceholderCompletionNames(namesProvider(), completion?.query);
  if (!names.length || !completion) return null;
  const options = names
    .map((name) => ({
      label: `{$${name}}`,
      detail: "placeholder",
      type: "variable",
      apply: (view, _completion, from, to) => {
        const replacement = mf2PlaceholderCompletionReplacement(view.state.doc.toString(), from, to, name);
        view.dispatch({
          changes: { from: replacement.from, to: replacement.to, insert: replacement.insert },
          selection: { anchor: replacement.cursor },
          userEvent: "input.complete",
        });
      },
    }));
  return {
    from: completion.from,
    to: context.pos,
    options,
    filter: false,
  };
}

function sourceMarkupNames() {
  return [...(state.sourceContract?.markup?.keys() ?? [])].sort();
}

function validationDiagnostics(response, source) {
  return [
    ...(response.diagnostics ?? []),
    ...(response.formatErrors ?? []),
    ...selectorDiagnostics(response.model, source),
    ...variantDiagnostics(response.model, source),
    ...placeholderDiagnostics(response.model, source),
    ...markupDiagnostics(response.model, source),
  ];
}

function targetDiagnostics(response, source) {
  return validationDiagnostics(response, source);
}

function selectorDiagnostics(rustModel, source) {
  if (!rustModel || !state.sourceContract) return [];
  const allowed = state.sourceContract.selectors ?? [];
  const current = selectorContractFromModel(rustModel);
  const allowedByName = new Map(allowed.map((selector) => [selector.name, selector]));
  const currentByName = new Map(current.map((selector) => [selector.name, selector]));
  const diagnostics = [];

  for (const selector of current) {
    if (allowedByName.has(selector.name)) continue;
    diagnostics.push({
      severity: "error",
      code: "new-selector",
      message: `Target matches on $${selector.name}, but that selector does not exist in the source message.`,
      start: selectorOffset(source, selector.name),
      end: selectorOffset(source, selector.name) + selector.name.length + 1,
    });
  }

  for (const selector of allowed) {
    if (currentByName.has(selector.name)) continue;
    diagnostics.push({
      severity: "error",
      code: "missing-source-selector",
      message: `Target no longer matches on source selector $${selector.name}.`,
      start: 0,
      end: Math.min(source.length, 1),
    });
  }

  const sameSelectorSet = allowed.length === current.length
    && allowed.every((selector) => currentByName.has(selector.name));
  if (sameSelectorSet && selectorSignature(allowed) !== selectorSignature(current)) {
    diagnostics.push({
      severity: "error",
      code: "selector-order-mismatch",
      message: `Target selector order is ${selectorListLabel(current)}, but source order is ${selectorListLabel(allowed)}.`,
      start: matchOffset(source),
      end: Math.min(source.length, matchOffset(source) + 6),
    });
  }

  for (const sourceSelector of allowed) {
    const targetSelector = currentByName.get(sourceSelector.name);
    if (!targetSelector || selectorAnnotationKey(sourceSelector) === selectorAnnotationKey(targetSelector)) continue;
    diagnostics.push({
      severity: "error",
      code: "selector-annotation-mismatch",
      message: `Target selector $${sourceSelector.name} uses ${selectorAnnotationLabel(targetSelector)}, but source uses ${selectorAnnotationLabel(sourceSelector)}.`,
      start: selectorOffset(source, sourceSelector.name),
      end: selectorOffset(source, sourceSelector.name) + sourceSelector.name.length + 1,
    });
  }

  return diagnostics;
}

function variantDiagnostics(rustModel, source) {
  const sourceModel = state.sourceContract?.model;
  if (!rustModel || !sourceModel || sourceModel.type !== "select" || rustModel.type !== "select") return [];
  const allowedSelectors = state.sourceContract.selectors ?? selectorContractFromModel(sourceModel);
  const currentSelectors = selectorContractFromModel(rustModel);
  if (selectorSignature(allowedSelectors) !== selectorSignature(currentSelectors)) return [];
  const allowed = variantContractsFromModel(sourceModel, allowedSelectors);
  const current = variantContractsFromModel(rustModel, currentSelectors);
  const allowedBySignature = new Map(allowed.map((variant) => [variant.signature, variant]));
  const currentBySignature = new Map(current.map((variant) => [variant.signature, variant]));
  const diagnostics = [];

  for (const variant of allowed) {
    if (currentBySignature.has(variant.signature)) continue;
    diagnostics.push({
      severity: "warning",
      code: "missing-source-variant",
      message: `Target is missing source variant ${variant.label}; fallback may still cover it.`,
      start: matchOffset(source),
      end: Math.min(source.length, matchOffset(source) + 6),
    });
  }

  for (const variant of current) {
    if (allowedBySignature.has(variant.signature)) continue;
    const assessment = targetOnlyVariantAssessment(
      variant,
      currentSelectors,
      elements.locale.value,
      selectorValueAllowlistFromModels(sourceModel),
    );
    if (assessment.status === "locale-recommended") continue;
    diagnostics.push({
      severity: "warning",
      code: "target-only-variant",
      message: assessment.message,
      start: matchOffset(source),
      end: Math.min(source.length, matchOffset(source) + 6),
    });
  }
  diagnostics.push(...missingLocalePluralDiagnostics(rustModel, currentSelectors, elements.locale.value, source));
  diagnostics.push(...selectorPriorityVariantDiagnostics(rustModel, currentSelectors, elements.locale.value, source));

  return diagnostics;
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
        severity: "warning",
        code: "missing-placeholder",
        message: `Variant ${baseline.label} omits source placeholder {$${item.name}}; this is fine if intentional.`,
        start: 0,
        end: Math.min(source.length, 1),
      });
    }
  }
  return diagnostics;
}

function markupDiagnostics(rustModel, source) {
  if (!rustModel || !state.sourceContract) return [];
  const allowed = state.sourceContract.markup;
  const current = markupFromRustModel(rustModel);
  const diagnostics = [];
  for (const name of current.keys()) {
    if (allowed.has(name)) continue;
    diagnostics.push({
      severity: "error",
      code: "new-markup",
      message: `Target uses {#${name}}, but that markup does not exist in the source message.`,
      start: markupOffset(source, name),
      end: markupOffset(source, name) + name.length + 3,
    });
  }
  for (const name of allowed.keys()) {
    if (current.has(name)) continue;
    diagnostics.push({
      severity: "warning",
      code: "missing-source-markup",
      message: `Target no longer uses source markup {#${name}}.`,
      start: 0,
      end: Math.min(source.length, 1),
    });
  }
  for (const [name, sourceShape] of allowed.entries()) {
    const targetShape = current.get(name);
    if (!targetShape || markupShapesEqual(sourceShape, targetShape)) continue;
    diagnostics.push({
      severity: "error",
      code: "markup-shape-mismatch",
      message: `Target markup {#${name}} has ${markupShapeLabel(targetShape)}, but source has ${markupShapeLabel(sourceShape)}.`,
      start: markupOffset(source, name),
      end: markupOffset(source, name) + name.length + 3,
    });
  }
  diagnostics.push(...markupPropDiagnostics(allowed, current, source));
  return diagnostics;
}

function markupPropDiagnostics(allowed, current, source) {
  const diagnostics = [];
  for (const [name, sourceShape] of allowed.entries()) {
    const targetShape = current.get(name);
    if (!targetShape) continue;
    diagnostics.push(
      ...markupPropKindDiagnostics(name, "option", sourceShape.options, targetShape.options, source),
      ...markupPropKindDiagnostics(name, "attribute", sourceShape.attributes, targetShape.attributes, source),
    );
  }
  return diagnostics;
}

function markupPropKindDiagnostics(markupName, propKind, sourceNames, targetNames, source) {
  const diagnostics = [];
  const displayKind = propKind === "attribute" ? "attribute" : "option";
  for (const name of targetNames) {
    if (sourceNames.has(name)) continue;
    diagnostics.push({
      severity: "error",
      code: `new-markup-${propKind}`,
      message: `Target markup {#${markupName}} adds ${displayKind} ${markupPropLabel(propKind, name)} that is not in the source message.`,
      start: markupOffset(source, markupName),
      end: markupOffset(source, markupName) + markupName.length + 3,
    });
  }
  for (const name of sourceNames) {
    if (targetNames.has(name)) continue;
    diagnostics.push({
      severity: propKind === "attribute" ? "warning" : "error",
      code: `missing-source-markup-${propKind}`,
      message: `Target markup {#${markupName}} no longer has source ${displayKind} ${markupPropLabel(propKind, name)}.`,
      start: markupOffset(source, markupName),
      end: markupOffset(source, markupName) + markupName.length + 3,
    });
  }
  return diagnostics;
}

function markupOffset(source, name) {
  const open = source.indexOf(`{#${name}`);
  if (open >= 0) return open;
  const close = source.indexOf(`{/${name}`);
  return close >= 0 ? close : 0;
}

function placeholderOffset(source, name) {
  const offset = source.indexOf(`{$${name}`);
  return offset >= 0 ? offset : 0;
}

function selectorOffset(source, name) {
  const start = matchOffset(source);
  const offset = source.indexOf(`$${name}`, start);
  return offset >= 0 ? offset : start;
}

function matchOffset(source) {
  const offset = source.indexOf(".match");
  return offset >= 0 ? offset : 0;
}

function renderVariantTabs() {
  elements.variantTabs.innerHTML = "";
  elements.variantTabs.append(renderVariantMetaRow());
  if (state.editorModel?.type !== "select") {
    elements.variantTabs.insertAdjacentHTML("beforeend", `
      <div class="variant-form-row is-single-form">
        <span class="variant-form-key">Message body</span>
        <div class="variant-form-editor-slot" data-inline-editor-slot></div>
      </div>
    `);
    moveProseMirrorIntoInlineSlot();
    renderVariantKeyEditor();
    return;
  }
  state.editorModel.variants.forEach((variant, index) => {
    const row = document.createElement("div");
    row.role = "button";
    row.tabIndex = 0;
    row.className = "variant-form-row";
    row.innerHTML = `
      <span class="variant-form-key">${escapeHtml(variantLabel(state.editorModel.selectors, variant.keys))}</span>
      <span class="variant-form-preview">${escapeHtml(patternPreviewText(variant.value))}</span>
      ${index === state.activeVariant ? '<span class="variant-form-editor-slot" data-inline-editor-slot></span>' : ""}
    `;
    row.setAttribute("aria-selected", String(index === state.activeVariant));
    row.title = `Edit ${variantLabel(state.editorModel.selectors, variant.keys)}`;
    row.addEventListener("keydown", (event) => handleVariantRowKeydown(event));
    row.addEventListener("click", () => {
      if (index !== state.activeVariant) {
        state.activeVariant = index;
        renderVariantTabs();
        renderVariantKeyEditor();
        renderProseMirrorVariant();
      }
    });
    elements.variantTabs.append(row);
  });
  moveProseMirrorIntoInlineSlot();
}

function renderVariantMetaRow() {
  const row = document.createElement("div");
  row.className = "variant-form-meta-row";
  row.innerHTML = `
    <div class="variant-form-contract">
      <strong>Source contract</strong>
      <code>${escapeHtml(sourceInputContractLine())}</code>
    </div>
    <div class="variant-form-shortcuts" data-shortcut-strip-slot></div>
  `;
  return row;
}

function sourceInputContractLine() {
  const items = sourceInputContractItems();
  return items.length ? items.join(" ") : "No source placeholders";
}

function handleVariantRowKeydown(event) {
  if (state.editorModel?.type !== "select") return;
  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
  if (!event.shiftKey || event.metaKey || event.ctrlKey || event.altKey) return;
  event.preventDefault();
  const delta = event.key === "ArrowDown" ? 1 : -1;
  switchActiveVariant(delta, { focusEditor: true, caretEnd: true });
}

function handleDocumentFormShortcut(event) {
  if (!isVariantSwitchShortcut(event)) return;
  const target = event.target instanceof Element ? event.target : event.target?.parentElement;
  if (!target?.closest(".ProseMirror, .variant-form-row")) return;
  event.preventDefault();
  event.stopPropagation();
  switchActiveVariant(event.key === "ArrowDown" ? 1 : -1, { focusEditor: true, caretEnd: true });
}

function switchActiveVariant(delta, { focusEditor = false, caretEnd = false } = {}) {
  if (state.editorModel?.type !== "select" || !state.editorModel.variants.length) return false;
  state.activeVariant = (state.activeVariant + delta + state.editorModel.variants.length) % state.editorModel.variants.length;
  renderVariantTabs();
  renderVariantKeyEditor();
  renderPluralTools();
  renderRowHelperStrip();
  renderProseMirrorVariant();
  if (focusEditor) queueMicrotask(() => {
    if (caretEnd) {
      focusProseMirrorAtEnd();
    } else {
      pmView?.focus();
    }
  });
  return true;
}

function focusProseMirrorAtEnd() {
  if (!pmView) return;
  pmView.dispatch(pmView.state.tr.setSelection(TextSelection.atEnd(pmView.state.doc)).scrollIntoView());
  pmView.focus();
}

function patternPreviewText(pattern) {
  const preview = splitPatternTokens(pattern)
    .map((part) => {
      if (part.type === "placeholder") return `{$${part.name}}`;
      if (part.type === "markup") return part.source;
      return part.value ?? "";
    })
    .join("")
    .replace(/\s+/gu, " ")
    .trim();
  if (!preview) return "(empty)";
  return preview.length > 96 ? `${preview.slice(0, 95)}...` : preview;
}

function renderVariantKeyEditor() {
  if (!elements.variantKeyEditor) return;
  if (state.editorModel?.type !== "select") {
    elements.variantKeyEditor.innerHTML = "";
    return;
  }
  const variant = state.editorModel.variants[state.activeVariant];
  if (!variant) {
    elements.variantKeyEditor.innerHTML = "";
    return;
  }
  elements.variantKeyEditor.innerHTML = `
    <header>
      <h3>Active variant keys</h3>
      <span class="eyebrow">Change selector keys without editing raw MF2</span>
    </header>
    <div class="variant-key-grid">
      ${state.editorModel.selectors.map((selector, index) => `
        <label>
          <span>$${escapeHtml(selector)}</span>
          <select data-variant-key-index="${index}">
            ${selectorKeyOptions(selector, index, variant.keys[index]).map((key) => `
              <option value="${escapeHtml(key)}"${key === variant.keys[index] ? " selected" : ""}>${escapeHtml(keyLabel(key))}</option>
            `).join("")}
          </select>
        </label>
      `).join("")}
    </div>
    ${nonPluralSelectorIndexes().length ? `
      <div class="selector-value-grid">
        ${nonPluralSelectorIndexes().map((index) => `
          <div class="selector-value-control">
            <label>
              <span>Add $${escapeHtml(state.editorModel.selectors[index])} value</span>
              <input data-new-selector-value="${index}" placeholder="newValue" />
            </label>
            <button type="button" data-add-selector-value="${index}">Add rows</button>
          </div>
          <div class="selector-value-preview" data-selector-value-preview="${index}">
            <span class="hint-inline">Enter a value to preview generated rows.</span>
          </div>
        `).join("")}
      </div>
    ` : ""}
  `;
  elements.variantKeyEditor.querySelectorAll("[data-variant-key-index]").forEach((select) => {
    select.addEventListener("change", () => updateActiveVariantKey(Number(select.dataset.variantKeyIndex), select.value));
  });
  elements.variantKeyEditor.querySelectorAll("[data-add-selector-value]").forEach((button) => {
    button.addEventListener("click", () => {
      const index = Number(button.dataset.addSelectorValue);
      const input = elements.variantKeyEditor.querySelector(`[data-new-selector-value="${index}"]`);
      addSelectorValue(index, input?.value ?? "");
    });
  });
  elements.variantKeyEditor.querySelectorAll("[data-new-selector-value]").forEach((input) => {
    const index = Number(input.dataset.newSelectorValue);
    input.addEventListener("input", () => renderSelectorValuePreview(index, input.value));
  });
}

function selectorKeyOptions(selector, selectorIndex, currentKey) {
  const keys = new Set(["*"]);
  if (isPluralSelector(selector)) {
    for (const category of state.pluralMetadata?.categories ?? []) keys.add(category.category);
    for (const key of exactNumericKeysForSelector(selector)) keys.add(key);
  } else {
    for (const value of selectorValues(selectorIndex)) keys.add(value);
  }
  if (currentKey) keys.add(currentKey);
  return [...keys];
}

function nonPluralSelectorIndexes() {
  if (state.editorModel?.type !== "select") return [];
  return state.editorModel.selectors
    .map((selector, index) => ({ selector, index }))
    .filter((item) => !isPluralSelector(item.selector))
    .map((item) => item.index);
}

function isPluralSelector(selector) {
  const declaration = state.editorModel?.declarations?.find((item) => item.name === selector);
  return isPluralSelectorFunction(declaration?.function);
}

function isPluralSelectorFunction(functionName) {
  return ["number", "integer", "percent", "offset"].includes(functionName);
}

function keyLabel(key) {
  return key === "*" ? "fallback (*)" : key;
}

function updateActiveVariantKey(selectorIndex, nextKey) {
  if (state.editorModel?.type !== "select") return;
  const variant = state.editorModel.variants[state.activeVariant];
  if (!variant || variant.keys[selectorIndex] === nextKey) return;
  const nextKeys = [...variant.keys];
  nextKeys[selectorIndex] = nextKey;
  if (variantExistsExcept(nextKeys, state.activeVariant)) {
    elements.status.textContent = `Variant ${nextKeys.join(" ")} already exists.`;
    renderVariantKeyEditor();
    return;
  }
  variant.keys = nextKeys;
  const source = printModel(state.editorModel);
  replaceCode(source);
  renderVariantTabs();
  renderVariantKeyEditor();
  void refreshFromSource(source, { keepVariantEditor: true });
}

function renderSelectorValuePreview(selectorIndex, rawValue) {
  const preview = elements.variantKeyEditor?.querySelector(`[data-selector-value-preview="${selectorIndex}"]`);
  if (!preview) return;
  if (!rawValue.trim()) {
    preview.innerHTML = '<span class="hint-inline">Enter a value to preview generated rows.</span>';
    return;
  }
  const plan = selectorValuePlan(selectorIndex, rawValue);
  if (plan.error) {
    preview.innerHTML = `<span class="contract-error">${escapeHtml(plan.error)}</span>`;
    return;
  }
  if (!plan.rows.length) {
    preview.innerHTML = `<span class="hint-inline">Rows for $${escapeHtml(plan.selector)}=${escapeHtml(plan.value)} already exist.</span>`;
    return;
  }
  preview.innerHTML = `
    <strong>Will add ${plan.rows.length} row(s)</strong>
    <ul>
      ${plan.rows.map((row) => `
        <li>
          <code>${escapeHtml(variantLabel(state.editorModel.selectors, row.keys))}</code>
          <span>${escapeHtml(row.value)}</span>
        </li>
      `).join("")}
    </ul>
  `;
}

function addSelectorValue(selectorIndex, rawValue) {
  if (state.editorModel?.type !== "select") return;
  const plan = selectorValuePlan(selectorIndex, rawValue);
  if (plan.error) {
    elements.status.textContent = plan.error;
    return;
  }
  if (!plan.rows.length) {
    elements.status.textContent = `Rows for $${plan.selector}=${plan.value} already exist.`;
    return;
  }
  let insertIndex = fallbackIndex();
  for (const row of plan.rows) {
    state.editorModel.variants.splice(insertIndex, 0, {
      keys: row.keys,
      value: row.value,
    });
    insertIndex++;
  }
  state.activeVariant = Math.max(0, insertIndex - plan.rows.length);
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Added ${plan.rows.length} row(s) for $${plan.selector}=${plan.value}.`;
  void refreshFromSource(source);
}

function selectorValuePlan(selectorIndex, rawValue) {
  if (state.editorModel?.type !== "select") return { selector: "", value: "", rows: [], error: "No matcher is active." };
  const value = rawValue.trim();
  const selector = state.editorModel.selectors[selectorIndex] ?? "";
  if (!isSimpleKey(value) || value === "*") {
    return { selector, value, rows: [], error: "New selector values must be simple unquoted keys." };
  }
  const rows = cartesian(selectorValueDimensions(selectorIndex, value))
    .filter((keys) => !variantExists(keys))
    .map((keys) => ({ keys, value: templateValueForKeys(keys, selectorIndex) }));
  return { selector, value, rows, error: null };
}

function selectorValueDimensions(selectorIndex, value) {
  return state.editorModel.selectors.map((name, index) => {
    if (index === selectorIndex) return [value];
    if (isPluralSelector(name)) return pluralKeysForSelector(name);
    const values = selectorValues(index);
    return values.length ? values : ["*"];
  });
}

function pluralKeysForSelector(selector) {
  const keys = new Set();
  for (const category of state.pluralMetadata?.categories ?? []) keys.add(category.category);
  const selectorIndex = state.editorModel?.selectors?.indexOf(selector) ?? -1;
  for (const key of selectorValues(selectorIndex)) {
    if (!isNumericKey(key)) keys.add(key);
  }
  return keys.size ? [...keys] : ["*"];
}

function templateValueForKeys(keys, changedSelectorIndex) {
  let best = null;
  for (const variant of state.editorModel?.variants ?? []) {
    const score = variantTemplateScore(variant.keys, keys, changedSelectorIndex);
    if (score < 0) continue;
    if (!best || score > best.score) best = { score, value: variant.value };
  }
  const fallback = state.editorModel?.variants?.find((variant) => variant.keys.every((key) => key === "*"));
  return best?.value ?? fallback?.value ?? "";
}

function variantTemplateScore(existingKeys, targetKeys, changedSelectorIndex) {
  let score = 0;
  for (let index = 0; index < targetKeys.length; index++) {
    if (index === changedSelectorIndex) continue;
    const existing = existingKeys[index] ?? "*";
    const target = targetKeys[index] ?? "*";
    if (existing === target) {
      score += 2;
    } else if (existing === "*") {
      score += 1;
    } else {
      return -1;
    }
  }
  return score;
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
  const exactKeys = exactNumericKeysForSelector(selector);
  const exactSuggestion = suggestExactPluralValue(selector);
  const fallbackCoversOther = existingGenericKeys.has("*");
  elements.pluralTools.innerHTML = `
    <header>
      <h3>CLDR plural forms for $${escapeHtml(selector)} (${escapeHtml(state.pluralMetadata.locale)})</h3>
      <span class="eyebrow">CLDR categories show non-exact samples; * is the MF2 fallback</span>
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
    <div class="plural-exact">
      <label>
        <span>Fixed exact value</span>
        <input data-exact-plural-value value="${escapeHtml(exactSuggestion)}" inputmode="decimal" />
        <small>Numeric key such as 0; checked before fallback.</small>
        <small class="fixed-row-preview" data-exact-plural-preview>${escapeHtml(fixedExactPluralPreviewLabel(selector, exactSuggestion))}</small>
      </label>
      <button type="button" data-add-exact-plural>Add fixed exact row</button>
      <div class="fixed-row-suggestions" data-exact-plural-suggestions>
        ${renderFixedExactContextSuggestions(selector, exactSuggestion)}
      </div>
    </div>
    ${exactKeys.size ? `
      <div class="plural-fixed-summary">
        <strong>Fixed rows</strong>
        <span>${escapeHtml(numericKeyListLabel(exactKeys))}</span>
        <small>CLDR examples below skip fixed values.</small>
      </div>
    ` : ""}
    <div class="plural-category-list">
      ${categories.map((category) => {
        const examples = pluralExamplesForCategory(category.category, exactKeys);
        const coveredByFallback = category.category === "other" && fallbackCoversOther;
        const disabled = existingGenericKeys.has(category.category) || coveredByFallback;
        return `
        <div class="plural-category">
          <strong>${escapeHtml(category.category)}</strong>
          <span>${escapeHtml(examples.length ? examples.join(", ") : "covered by exact rows")}</span>
          <button type="button" data-add-plural="${escapeHtml(category.category)}"${disabled ? " disabled" : ""}>
            ${coveredByFallback ? "Fallback covers" : existingGenericKeys.has(category.category) ? "Added" : "Add row"}
          </button>
        </div>
      `;
      }).join("")}
    </div>
  `;
  elements.pluralTools.querySelector("[data-generate-combinations]")?.addEventListener("click", () => generatePluralCombinations(selector));
  const exactInput = elements.pluralTools.querySelector("[data-exact-plural-value]");
  const exactPreview = elements.pluralTools.querySelector("[data-exact-plural-preview]");
  const exactSuggestions = elements.pluralTools.querySelector("[data-exact-plural-suggestions]");
  exactInput?.addEventListener("input", () => {
    if (!exactPreview) return;
    const value = exactInput.value.trim();
    exactPreview.textContent = fixedExactPluralPreviewLabel(selector, value);
    exactPreview.classList.toggle("invalid", !isNumericKey(value));
    if (exactSuggestions) exactSuggestions.innerHTML = renderFixedExactContextSuggestions(selector, value);
  });
  exactSuggestions?.addEventListener("click", (event) => {
    const button = event.target.closest("[data-add-exact-context-row]");
    if (!button) return;
    addFixedExactPluralKeys(selector, fixedExactPluralKeysFromToken(button.dataset.addExactContextRow));
  });
  elements.pluralTools.querySelector("[data-add-exact-plural]")?.addEventListener("click", () => {
    const value = exactInput?.value?.trim() ?? "";
    addExactPluralVariant(selector, value);
  });
  elements.pluralTools.querySelectorAll("[data-add-plural]").forEach((button) => {
    button.addEventListener("click", () => addPluralVariant(selector, button.dataset.addPlural));
  });
}

function renderRowHelperStrip() {
  if (!elements.rowHelperStrip) return;
  if (state.editorModel?.type !== "select") {
    elements.rowHelperStrip.innerHTML = "";
    elements.rowHelperStrip.hidden = true;
    return;
  }
  const selector = primaryPluralSelector();
  const plan = selector ? pluralCombinationPlan(selector) : { missing: [] };
  const fixPlan = localeFormFixPlan();
  const targetLanguage = elements.targetLanguage?.selectedOptions?.[0]?.textContent ?? elements.locale.value;
  elements.rowHelperStrip.hidden = false;
  elements.rowHelperStrip.innerHTML = `
    <div>
      <strong>Row helper</strong>
      <span>${escapeHtml(rowHelperSummary(selector, plan, fixPlan))}</span>
    </div>
    <div class="button-row">
      ${selector && plan.missing.length ? `
        <button type="button" class="primary" data-add-locale-forms>
          Add ${escapeHtml(targetLanguage)} form rows (${plan.missing.length})
        </button>
      ` : ""}
      ${fixPlan.removable.length ? `
        <button type="button" class="primary" data-fix-locale-forms>
          Fix ${fixPlan.removable.length} invalid empty form${fixPlan.removable.length === 1 ? "" : "s"}
        </button>
      ` : ""}
      <button type="button" data-open-row-tools>More row tools</button>
    </div>
  `;
  elements.rowHelperStrip.querySelector("[data-add-locale-forms]")?.addEventListener("click", () => {
    if (selector) generatePluralCombinations(selector);
  });
  elements.rowHelperStrip.querySelector("[data-fix-locale-forms]")?.addEventListener("click", fixLocaleFormRows);
  elements.rowHelperStrip.querySelector("[data-open-row-tools]")?.addEventListener("click", () => {
    if (!elements.rowToolsDrawer) return;
    elements.rowToolsDrawer.open = true;
    elements.rowToolsDrawer.scrollIntoView({ block: "nearest" });
  });
}

function rowHelperSummary(selector, plan, fixPlan = { removable: [], review: [] }) {
  if (!selector) return "Open tools to edit selector keys or add non-plural rows.";
  if (fixPlan.removable.length) {
    return `$${selector} has ${fixPlan.removable.length} empty form row(s) that are not CLDR categories for ${elements.locale.value}.`;
  }
  if (fixPlan.review.length) {
    return `$${selector} has ${fixPlan.review.length} non-empty row(s) that are not CLDR categories for ${elements.locale.value}; review before changing them.`;
  }
  if (plan.missing.length) {
    return `$${selector} is missing target-locale CLDR rows for ${elements.locale.value}.`;
  }
  return `$${selector} has no missing target-locale CLDR rows.`;
}

function localeFormFixPlan(model = state.editorModel, locale = elements.locale.value) {
  if (model?.type !== "select") return { removable: [], review: [] };
  const selectors = selectorContractFromModel(model);
  const sourceSignatures = sourceVariantSignaturesForCurrentTarget(model);
  const removable = [];
  const review = [];
  (model.variants ?? []).forEach((variant, index) => {
    if (sourceSignatures.has(variantSignature(variant.keys ?? []))) return;
    const unsupported = unsupportedLocalePluralDetails(variant, selectors, locale);
    if (!unsupported.length) return;
    const row = {
      index,
      keys: [...(variant.keys ?? [])],
      label: variantLabel(model.selectors ?? [], variant.keys ?? []),
      unsupported,
      value: variant.value ?? "",
    };
    if (isBlankPattern(variant.value)) {
      removable.push(row);
    } else {
      review.push(row);
    }
  });
  return { removable, review };
}

function sourceVariantSignaturesForCurrentTarget(targetModel = state.editorModel) {
  const sourceModel = editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? "");
  if (sourceModel?.type !== "select" || targetModel?.type !== "select") return new Set();
  if (variantSignature(sourceModel.selectors ?? []) !== variantSignature(targetModel.selectors ?? [])) return new Set();
  return new Set((sourceModel.variants ?? []).map((variant) => variantSignature(variant.keys ?? [])));
}

function unsupportedLocalePluralDetails(variant, selectors, locale) {
  return (variant.keys ?? [])
    .map((key, index) => targetOnlyPluralCategoryDetail(key, selectors[index], locale))
    .filter((detail) => detail && !detail.supported);
}

function isBlankPattern(pattern) {
  return !String(pattern ?? "").trim();
}

function fixLocaleFormRows() {
  if (state.editorModel?.type !== "select") return;
  const plan = localeFormFixPlan();
  if (!plan.removable.length) {
    elements.status.textContent = plan.review.length
      ? `${plan.review.length} invalid locale row(s) have target text; review them manually.`
      : "No invalid empty locale form rows to fix.";
    return;
  }
  const removedIndexes = new Set(plan.removable.map((row) => row.index));
  const firstRemovedIndex = Math.min(...removedIndexes);
  state.editorModel.variants = state.editorModel.variants.filter((_variant, index) => !removedIndexes.has(index));
  state.activeVariant = Math.min(firstRemovedIndex, Math.max(0, state.editorModel.variants.length - 1));
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Removed ${plan.removable.length} empty invalid locale form row(s).`;
  void refreshFromSource(source);
}

function numericKeyListLabel(keys) {
  return [...keys].sort((left, right) => Number(left) - Number(right) || left.localeCompare(right)).join(", ");
}

function pluralExamplesForCategory(category, excluded = new Set()) {
  const examples = [];
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    if (examples.length >= 5) break;
    if (excluded.has(canonicalNumericKey(candidate))) continue;
    if (pluralCategoryForCurrentLocale(candidate) === category) {
      examples.push(String(candidate));
    }
  }
  return examples;
}

function primaryPluralSelector(model = state.editorModel) {
  if (model?.type !== "select") return null;
  for (const selector of model.selectors) {
    const declaration = model.declarations.find((item) => item.name === selector);
    if (isPluralSelectorFunction(declaration?.function)) {
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
  const value = generatedLocaleRowValue(fallback, selector);
  const insertIndex = fallbackIndex();
  state.editorModel.variants.splice(insertIndex, 0, { keys, value });
  state.activeVariant = insertIndex;
  const source = printModel(state.editorModel);
  replaceCode(source);
  void refreshFromSource(source);
}

function addExactPluralVariant(selector, value) {
  if (state.editorModel?.type !== "select") return;
  if (!isNumericKey(value)) {
    elements.status.textContent = "Fixed plural keys must be numeric for :number selectors.";
    return;
  }
  const keys = fixedExactPluralKeys(selector, value);
  addFixedExactPluralKeys(selector, keys);
}

function addFixedExactPluralKeys(selector, keys) {
  if (state.editorModel?.type !== "select" || keys.length !== state.editorModel.selectors.length) {
    elements.status.textContent = "Fixed exact row keys are invalid.";
    return;
  }
  if (variantExists(keys)) {
    elements.status.textContent = `Variant ${keys.join(" ")} already exists.`;
    return;
  }
  const fallback = state.editorModel.variants.find((variant) => variant.keys.every((key) => key === "*"));
  const insertIndex = fallbackIndex();
  const variant = { keys, value: generatedLocaleRowValue(fallback, selector) };
  state.editorModel.variants.splice(insertIndex, 0, variant);
  normalizeExactNumericRowOrder(selector, variant);
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Added fixed exact row ${variantLabel(state.editorModel.selectors, keys)} in numeric order before CLDR and fallback rows.`;
  void refreshFromSource(source);
}

function renderFixedExactContextSuggestions(selector, value, model = state.editorModel) {
  if (model?.type !== "select" || !isNumericKey(value)) return "";
  const suggestions = fixedExactContextSuggestions(selector, value, model);
  if (!suggestions.length) return "";
  return `
    <span>Also add with source context:</span>
    ${suggestions.map((suggestion) => `
      <button type="button" data-add-exact-context-row="${escapeHtml(encodeURIComponent(JSON.stringify(suggestion.keys)))}">
        ${escapeHtml(suggestion.label)}
      </button>
    `).join("")}
  `;
}

function fixedExactContextSuggestions(selector, value, model = state.editorModel) {
  if (model?.type !== "select" || model.selectors.length <= 1) return [];
  const selectorIndex = model.selectors.indexOf(selector);
  const canonical = canonicalNumericKey(value);
  return model.selectors.flatMap((name, index) => {
    if (index === selectorIndex || isPluralSelector(name)) return [];
    return sourceContextValuesForSelector(name, index).map((contextValue) => {
      const keys = model.selectors.map((_selector, keyIndex) => {
        if (keyIndex === selectorIndex) return canonical;
        if (keyIndex === index) return contextValue;
        return "*";
      });
      return { keys, label: variantLabel(model.selectors, keys) };
    });
  }).filter((suggestion) => !variantExists(suggestion.keys)).slice(0, 8);
}

function sourceContextValuesForSelector(selector, selectorIndex) {
  const values = new Set(selectorValueAllowlistForCurrentModels().get(selector) ?? []);
  for (const value of selectorValues(selectorIndex)) values.add(value);
  return [...values].filter((value) => value !== "*").sort();
}

function fixedExactPluralKeysFromToken(token) {
  try {
    const parsed = JSON.parse(decodeURIComponent(token ?? ""));
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

function fixedExactPluralPreviewLabel(selector, value, model = state.editorModel) {
  if (model?.type !== "select") return "Add plural before adding fixed exact rows.";
  if (!isNumericKey(value)) return "Enter a numeric fixed exact value.";
  const keys = fixedExactPluralKeys(selector, value, model);
  const suffix = model.selectors.length > 1 ? " Other selectors use fallback." : "";
  return `Will add: ${variantLabel(model.selectors, keys)}.${suffix}`;
}

function fixedExactPluralKeys(selector, value, model = state.editorModel) {
  const selectorIndex = model.selectors.indexOf(selector);
  return model.selectors.map((_name, index) => (index === selectorIndex ? canonicalNumericKey(value) : "*"));
}

function suggestExactPluralValue(selector) {
  const existing = exactNumericKeysForSelector(selector);
  for (const candidate of [0, 1, 2, 3, 5, 10, 100]) {
    if (!existing.has(canonicalNumericKey(candidate))) return String(candidate);
  }
  return "";
}

function generatePluralCombinations(selector) {
  if (state.editorModel?.type !== "select") return;
  const plan = pluralCombinationPlan(selector);
  if (!plan.missing.length) return;
  const fallback = state.editorModel.variants.find((variant) => variant.keys.every((key) => key === "*"));
  const value = generatedLocaleRowValue(fallback, selector);
  let insertIndex = fallbackIndex();
  for (const keys of plan.missing) {
    state.editorModel.variants.splice(insertIndex, 0, { keys, value });
    insertIndex++;
  }
  state.activeVariant = Math.max(0, insertIndex - plan.missing.length);
  const source = printModel(state.editorModel);
  replaceCode(source);
  elements.status.textContent = `Added ${plan.missing.length} ${elements.locale.value} plural row(s).`;
  void refreshFromSource(source);
}

function pluralCombinationPlan(selector) {
  if (state.editorModel?.type !== "select" || !state.pluralMetadata?.categories?.length) {
    return { label: "No plural selector", total: 0, missing: [] };
  }
  const pluralIndex = state.editorModel.selectors.indexOf(selector);
  const fallbackCoversOther = state.editorModel.variants.some((variant) => variant.keys.every((key) => key === "*"));
  const dimensions = state.editorModel.selectors.map((name, index) => {
    if (index === pluralIndex) {
      return state.pluralMetadata.categories
        .map((category) => category.category)
        .filter((category) => !(category === "other" && fallbackCoversOther));
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
  return variantExistsExcept(keys, -1);
}

function variantExistsExcept(keys, exceptIndex) {
  return (state.editorModel?.variants ?? []).some((variant, index) =>
    index !== exceptIndex && variantSignature(variant.keys) === variantSignature(keys),
  );
}

function fallbackIndex() {
  const index = (state.editorModel?.variants ?? []).findIndex((variant) => variant.keys.every((key) => key === "*"));
  return index >= 0 ? index : state.editorModel.variants.length;
}

function normalizeExactNumericRowOrder(selector, activeVariant = state.editorModel?.variants[state.activeVariant]) {
  if (state.editorModel?.type !== "select" || !selector) return;
  const selectorIndex = state.editorModel.selectors.indexOf(selector);
  if (selectorIndex < 0) return;
  const decorated = state.editorModel.variants.map((variant, index) => ({
    exact: exactNumericVariantValue(variant, selectorIndex),
    index,
    variant,
  }));
  if (!decorated.some((item) => item.exact != null)) return;
  decorated.sort((left, right) => {
    if (left.exact != null && right.exact != null) {
      return left.exact - right.exact || left.index - right.index;
    }
    if (left.exact != null) return -1;
    if (right.exact != null) return 1;
    return left.index - right.index;
  });
  state.editorModel.variants = decorated.map((item) => item.variant);
  const activeIndex = state.editorModel.variants.indexOf(activeVariant);
  state.activeVariant = activeIndex >= 0 ? activeIndex : Math.min(state.activeVariant, state.editorModel.variants.length - 1);
}

function exactNumericVariantValue(variant, selectorIndex) {
  const key = variantKeyValue(variant.keys?.[selectorIndex] ?? "*");
  return isNumericKey(key) ? Number(canonicalNumericKey(key)) : null;
}

function fallbackValue(fallback, selector) {
  return fallback?.value ?? `{$${selector}}`;
}

function generatedLocaleRowValue(fallback, selector) {
  const value = fallbackValue(fallback, selector);
  return targetValueLooksSourceDerived(value) ? "" : value;
}

function targetValueLooksSourceDerived(value) {
  if (value === "") return false;
  const sourceModel = editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? "");
  if (!sourceModel) return false;
  if (sourceModel.type === "select") {
    return sourceModel.variants.some((variant) => (variant.value ?? "") === value);
  }
  return (sourceModel.pattern ?? "") === value;
}

function renderProseMirrorVariant() {
  const pattern = selectedPattern();
  const sourcePlaceholders = sourcePlaceholdersForSelectedVariant(pattern);
  applyProseMirrorLineFloor(pattern);
  const pmState = ProseMirrorState.create({
    doc: docFromPattern(pattern, sourcePlaceholders),
    plugins: [
      history(),
      keymap({
        "Mod-z": undo,
        "Mod-y": redo,
        "Mod-Shift-z": redo,
        "Mod-b": toggleMark(schema.marks.strong),
      }),
      new Plugin({
        props: {
          handleKeyDown: (view, event) => {
            if ((event.key === "Backspace" || event.key === "Delete") && deletePlaceholderAtSelection(view, event.key)) {
              event.preventDefault();
              closePlaceholderCompletion();
              return true;
            }
            if (isVariantSwitchShortcut(event)) {
              event.preventDefault();
              closePlaceholderCompletion();
              return switchActiveVariant(event.key === "ArrowDown" ? 1 : -1, { focusEditor: true });
            }
            if (isNextVariantShortcut(event)) {
              event.preventDefault();
              closePlaceholderCompletion();
              return switchActiveVariant(1, { focusEditor: true, caretEnd: true });
            }
            if (placeholderCompletionOpen()) {
              if (event.key === "ArrowDown") {
                event.preventDefault();
                movePlaceholderCompletion(1);
                return true;
              }
              if (event.key === "ArrowUp") {
                event.preventDefault();
                movePlaceholderCompletion(-1);
                return true;
              }
              if (event.key === "Enter") {
                event.preventDefault();
                commitPlaceholderCompletion();
                return true;
              }
              if (event.key === "Escape") {
                event.preventDefault();
                cancelPlaceholderCompletion(view, sourcePlaceholders);
                return true;
              }
              if (isPlaceholderCompletionTextKey(event)) {
                queueMicrotask(() => refreshPlaceholderCompletion(view));
              }
            }
            if (!event.metaKey && !event.ctrlKey && !event.altKey && event.key === "{") {
              event.preventDefault();
              beginPlaceholderCompletion(view);
              return true;
            }
            return false;
          },
          handleDOMEvents: {
            compositionend: (view) => {
              queueMicrotask(() => syncProseMirrorToModel(view, sourcePlaceholders));
              return false;
            },
          },
        },
        view: () => ({
          update: (view, previousState) => {
            if (previousState.doc.eq(view.state.doc)) return;
            if (view.composing) return;
            if (suppressProseMirrorSync) return;
            if (placeholderCompletionOpen() && refreshPlaceholderCompletion(view)) return;
            if (openPlaceholderCompletionFromCurrentText(view)) return;
            syncProseMirrorToModel(view, sourcePlaceholders);
          },
        }),
      }),
      keymap(baseKeymap),
    ],
  });
  if (pmView) {
    pmView.updateState(pmState);
  } else {
    pmView = new ProseMirrorView(elements.prosemirror, { state: pmState });
  }
  moveProseMirrorIntoInlineSlot();
  renderProseMirrorProtectionState(sourcePlaceholders);
  renderSourceProsePreview();
  applyTargetDirection();
}

function applyProseMirrorLineFloor(targetPattern) {
  const sourceModel = editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? "");
  const sourcePattern = sourceModel ? selectedSourceProsePattern(sourceModel).pattern : "";
  const lines = Math.max(1, lineCount(targetPattern), lineCount(sourcePattern));
  elements.prosemirror.style.setProperty("--prose-min-lines", String(Math.min(lines, 8)));
}

function lineCount(value) {
  return String(value ?? "").split(/\r\n|\r|\n/u).length;
}

function moveProseMirrorIntoInlineSlot() {
  const slot = elements.variantTabs.querySelector("[data-inline-editor-slot]");
  if (!slot || !elements.prosemirror) return;
  if (elements.prosemirror.parentElement !== slot) {
    slot.replaceChildren(elements.prosemirror);
  }
  const shortcutSlot = elements.variantTabs.querySelector("[data-shortcut-strip-slot]");
  if (shortcutSlot && elements.prosemirrorHint.parentElement !== shortcutSlot) {
    shortcutSlot.replaceChildren(elements.prosemirrorHint);
  }
}

function syncProseMirrorToModel(view, sourcePlaceholders) {
  updateSelectedPattern(patternFromDoc(view.state.doc));
  renderProseMirrorProtectionState(sourcePlaceholders);
  closePlaceholderCompletion();
}

function deletePlaceholderAtSelection(view, key) {
  const { state } = view;
  const { selection } = state;
  if (selection instanceof NodeSelection && selection.node.type.name === "placeholder") {
    dispatchPlaceholderDelete(view, selection.from, selection.to, selection.from);
    return true;
  }
  if (!selection.empty) return false;
  const adjacentNode = key === "Backspace" ? selection.$from.nodeBefore : selection.$from.nodeAfter;
  if (adjacentNode?.type.name !== "placeholder") return false;
  const from = key === "Backspace" ? selection.from - adjacentNode.nodeSize : selection.from;
  const to = key === "Backspace" ? selection.from : selection.from + adjacentNode.nodeSize;
  dispatchPlaceholderDelete(view, from, to, from);
  return true;
}

function dispatchPlaceholderDelete(view, from, to, cursor) {
  const transaction = view.state.tr.delete(from, to);
  const position = Math.max(0, Math.min(cursor, transaction.doc.content.size));
  transaction.setSelection(TextSelection.near(transaction.doc.resolve(position), -1));
  view.dispatch(transaction);
}

function placeholderCompletionOpen() {
  return Boolean(elements.placeholderAutocomplete && !elements.placeholderAutocomplete.hidden);
}

function isVariantSwitchShortcut(event) {
  return event.shiftKey && !event.metaKey && !event.ctrlKey && !event.altKey
    && (event.key === "ArrowDown" || event.key === "ArrowUp")
    && state.editorModel?.type === "select";
}

function isNextVariantShortcut(event) {
  return (event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey
    && event.key === "Enter"
    && state.editorModel?.type === "select";
}

function beginPlaceholderCompletion(view = pmView) {
  const names = sourcePlaceholderNames();
  if (!elements.placeholderAutocomplete || !view || !names.length) {
    elements.status.textContent = "No placeholders are available for this message.";
    return;
  }
  const { from, to } = view.state.selection;
  suppressProseMirrorSync = true;
  view.dispatch(view.state.tr.insertText("{", from, to).scrollIntoView());
  suppressProseMirrorSync = false;
  const cursor = view.state.selection.from;
  placeholderCompletionRange = { from, to: cursor };
  placeholderCompletionQuery = "";
  placeholderCompletionIndex = 0;
  renderPlaceholderCompletion(view, names, "{");
}

function openPlaceholderCompletionFromCurrentText(view = pmView) {
  const range = placeholderCompletionRangeFromSelection(view);
  if (!range) return false;
  const text = view.state.doc.textBetween(range.from, range.to, "\n", "\n");
  const match = text.match(/^(?:\{\$?|\$)([\p{L}\p{N}_-]*)$/u);
  if (!match) return false;
  placeholderCompletionRange = range;
  placeholderCompletionQuery = match[1] ?? "";
  placeholderCompletionIndex = 0;
  renderPlaceholderCompletion(view, filteredPlaceholderNames(), text);
  return true;
}

function placeholderCompletionRangeFromSelection(view = pmView) {
  if (!view || !view.state.selection.empty) return null;
  const { selection } = view.state;
  const parentStart = selection.$from.start();
  const beforeCursor = view.state.doc.textBetween(parentStart, selection.from, "\n", "\n");
  const match = beforeCursor.match(/(?:^|[\s\u200B])((?:\{\$?|\$)[\p{L}\p{N}_-]*)$/u);
  if (!match) return null;
  const triggerText = match[1];
  return { from: selection.from - triggerText.length, to: selection.from };
}

function refreshPlaceholderCompletion(view = pmView) {
  if (!view || !placeholderCompletionRange) return false;
  const cursor = view.state.selection.from;
  if (!view.state.selection.empty || cursor < placeholderCompletionRange.from) {
    closePlaceholderCompletion();
    return false;
  }
  const text = view.state.doc.textBetween(placeholderCompletionRange.from, cursor, "\n", "\n");
  const match = text.match(/^\{\$?([\p{L}\p{N}_-]*)$/u);
  if (!match) {
    closePlaceholderCompletion();
    return false;
  }
  placeholderCompletionRange.to = cursor;
  placeholderCompletionQuery = match[1] ?? "";
  const names = filteredPlaceholderNames();
  if (!names.length) {
    renderPlaceholderCompletion(view, [], text);
    return true;
  }
  if (placeholderCompletionIndex >= names.length) placeholderCompletionIndex = 0;
  renderPlaceholderCompletion(view, names, text);
  return true;
}

function filteredPlaceholderNames() {
  const query = placeholderCompletionQuery.trim().toLocaleLowerCase();
  const names = sourcePlaceholderNames();
  if (!query) return names;
  return names.filter((name) => name.toLocaleLowerCase().includes(query));
}

function isPlaceholderCompletionTextKey(event) {
  return event.key.length === 1
    && !event.metaKey
    && !event.ctrlKey
    && !event.altKey;
}

function movePlaceholderCompletion(delta) {
  const names = filteredPlaceholderNames();
  if (!names.length) return;
  placeholderCompletionIndex = (placeholderCompletionIndex + delta + names.length) % names.length;
  renderPlaceholderCompletion(pmView, names, placeholderCompletionTypedText());
}

function commitPlaceholderCompletion() {
  const names = filteredPlaceholderNames();
  const name = names[placeholderCompletionIndex];
  if (!name) return;
  const range = placeholderCompletionRange;
  closePlaceholderCompletion();
  insertPlaceholder(name, range);
}

function cancelPlaceholderCompletion(view = pmView, sourcePlaceholders = sourcePlaceholdersForSelectedVariant(selectedPattern())) {
  closePlaceholderCompletion();
  if (view) syncProseMirrorToModel(view, sourcePlaceholders);
  view?.focus();
}

function placeholderCompletionTypedText() {
  if (!placeholderCompletionRange || !pmView) return "{";
  return pmView.state.doc.textBetween(placeholderCompletionRange.from, placeholderCompletionRange.to, "\n", "\n");
}

function closePlaceholderCompletion() {
  if (!elements.placeholderAutocomplete) return;
  elements.placeholderAutocomplete.hidden = true;
  elements.placeholderAutocomplete.innerHTML = "";
  elements.placeholderAutocomplete.style.left = "";
  elements.placeholderAutocomplete.style.top = "";
  placeholderCompletionQuery = "";
  placeholderCompletionRange = null;
  placeholderCompletionIndex = 0;
}

function renderPlaceholderCompletion(view, names, typedText) {
  elements.placeholderAutocomplete.hidden = false;
  positionPlaceholderCompletion(view);
  elements.placeholderAutocomplete.innerHTML = `
    <span>${escapeHtml(typedText || "{")} -> placeholders <small>Enter chooses highlighted placeholder; arrows move; Esc closes</small></span>
    ${names.length
      ? names.map((name, index) => `
        <button type="button" data-placeholder-complete="${escapeHtml(name)}" aria-selected="${index === placeholderCompletionIndex ? "true" : "false"}">{$${escapeHtml(name)}}</button>
      `).join("")
      : '<em>No matching placeholder</em>'}
  `;
  elements.placeholderAutocomplete.querySelectorAll("[data-placeholder-complete]").forEach((button, index) => {
    button.addEventListener("mousedown", (event) => event.preventDefault());
    button.addEventListener("click", () => {
      placeholderCompletionIndex = index;
      commitPlaceholderCompletion();
    });
  });
}

function positionPlaceholderCompletion(view) {
  if (!view || !placeholderCompletionRange) return;
  const coords = view.coordsAtPos(placeholderCompletionRange.to);
  const hostRect = elements.prosemirror.getBoundingClientRect();
  elements.placeholderAutocomplete.style.left = `${Math.max(6, coords.left - hostRect.left)}px`;
  elements.placeholderAutocomplete.style.top = `${Math.max(6, coords.bottom - hostRect.top + 4)}px`;
}

function sourcePlaceholdersForSelectedVariant(fallbackPattern) {
  const signature = selectedVariantSignature();
  return sourceVariantPlaceholdersForSignature(
    state.sourceContract?.requirements,
    signature,
    fallbackPattern,
    Boolean(state.sourceContract?.model),
  );
}

function selectedVariantSignature() {
  if (state.editorModel?.type === "select") {
    const variant = state.editorModel.variants[state.activeVariant];
    return variantSignature(variant?.keys ?? []);
  }
  return "message";
}

function renderProseMirrorProtectionState() {
  renderProseMirrorHint();
}

function renderProseMirrorHint() {
  if (!elements.prosemirrorHint) return;
  elements.prosemirrorHint.innerHTML = renderShortcutStrip();
  bindShortcutStripActions();
}

function renderShortcutStrip() {
  return `
    ${shortcutKey("{", "placeholder menu")}
    ${shortcutKey("Shift+↓", "next form")}
    ${shortcutKey("Shift+↑", "previous form")}
    ${shortcutKey(platformMetaKeyLabel(), "next form")}
    ${renderMissingPlaceholderAction()}
    ${renderTextToolsInline()}
  `;
}

function shortcutKey(keys, label) {
  return `<span class="shortcut-chip"><span class="shortcut-keys">${shortcutKeysHtml(keys)}</span><span class="shortcut-label">${escapeHtml(label)}</span></span>`;
}

function shortcutKeysHtml(keys) {
  const text = String(keys);
  if (text.includes(" or ")) {
    return text
      .split(" or ")
      .map((key) => `<kbd>${escapeHtml(key)}</kbd>`)
      .join('<span class="shortcut-separator">or</span>');
  }
  return text
    .split("+")
    .map((key) => `<kbd>${escapeHtml(key)}</kbd>`)
    .join('<span class="shortcut-plus">+</span>');
}

function placeholderNameListLabel(requirements) {
  return requirements
    .map((item) => item.count > 1 ? `${item.name} x${item.count}` : item.name)
    .join(", ");
}

function platformMetaKeyLabel() {
  return /Mac|iPhone|iPad|iPod/u.test(navigator.platform) ? "Cmd+Enter" : "Ctrl+Enter";
}

function renderMissingPlaceholderAction() {
  if (!pmView) return "";
  const sourcePlaceholders = sourcePlaceholdersForSelectedVariant(patternFromDoc(pmView.state.doc));
  const missing = missingRequiredPlaceholders(sourcePlaceholders, placeholderCountsFromSource(patternFromDoc(pmView.state.doc)));
  if (!missing.length) return "";
  return `
    <button type="button" class="shortcut-action" data-restore-placeholders
      title="Reinsert source placeholder(s) if the omission was accidental.">
      Restore ${escapeHtml(placeholderNameListLabel(missing))}
    </button>
  `;
}

function renderTextToolsInline() {
  return `
    <details class="shortcut-special">
      <summary>Insert special</summary>
      <div class="text-tool-grid">
        ${TEXT_TOOLS.map((tool, index) => `
          <button type="button" data-text-tool="${index}" title="${escapeHtml(tool.title)}">
            <span>${escapeHtml(tool.label)}</span>
            <small>${escapeHtml(tool.shortcut ? `${tool.code} · ${tool.shortcut}` : tool.code)}</small>
          </button>
        `).join("")}
      </div>
    </details>
  `;
}

function bindShortcutStripActions() {
  elements.prosemirrorHint.querySelector("[data-restore-placeholders]")?.addEventListener("click", restoreRequiredPlaceholders);
  elements.prosemirrorHint.querySelectorAll("[data-text-tool]").forEach((button) => {
    button.addEventListener("click", () => applyTextTool(TEXT_TOOLS[Number(button.dataset.textTool)]));
  });
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

function insertSelectedPlaceholder() {
  if (!pmView) return;
  const name = elements.placeholderSelect.value || sourcePlaceholderNames()[0] || "count";
  insertPlaceholder(name);
}

function insertPlaceholder(name, range = null) {
  if (!pmView || !name) return;
  const node = schema.nodes.placeholder.create({ name });
  let transaction = pmView.state.tr;
  if (range) {
    transaction = transaction.replaceWith(range.from, range.to, node);
  } else {
    transaction = transaction.replaceSelectionWith(node);
  }
  transaction = transaction.scrollIntoView();
  pmView.dispatch(transaction);
  pmView.focus();
}

function restoreRequiredPlaceholders() {
  if (!pmView) return;
  const sourcePlaceholders = sourcePlaceholdersForSelectedVariant(selectedPattern());
  const missing = missingRequiredPlaceholders(sourcePlaceholders, placeholderCountsFromSource(patternFromDoc(pmView.state.doc)));
  if (!missing.length) {
    elements.status.textContent = "All source placeholders are already present.";
    return;
  }
  const nodes = [];
  for (const item of missing) {
    for (let index = 0; index < item.count; index++) {
      if (nodes.length) nodes.push(schema.text(" "));
      nodes.push(schema.nodes.placeholder.create({ name: item.name, source: true }));
    }
  }
  const { from, to } = pmView.state.selection;
  const transaction = pmView.state.tr.replaceWith(from, to, nodes).scrollIntoView();
  pmView.dispatch(transaction);
  pmView.focus();
  elements.status.textContent = `Restored ${placeholderRequirementLabel(missing)}.`;
  renderProseMirrorProtectionState(sourcePlaceholders);
}

function wrapSelectedMarkup() {
  if (!pmView) return;
  const name = elements.markupSelect.value || sourceMarkupNames()[0];
  if (!name) return;
  const sourceMarkup = state.sourceContract?.markup?.get(name);
  if (sourceMarkup?.standalone > 0 && sourceMarkup.open === 0) {
    const source = sourceMarkup.standaloneSource ?? sourceMarkup.openSource ?? `{#${name}/}`;
    const node = schema.nodes.markupBoundary.create({ name, kind: "standalone", source });
    const transaction = pmView.state.tr.replaceSelectionWith(node).scrollIntoView();
    pmView.dispatch(transaction);
    pmView.focus();
    return;
  }
  const openSource = sourceMarkup?.openSource ?? `{#${name}}`;
  const closeSource = `{/${name}}`;
  const open = schema.nodes.markupBoundary.create({ name, kind: "open", source: openSource });
  const close = schema.nodes.markupBoundary.create({ name, kind: "close", source: closeSource });
  const { from, to, empty } = pmView.state.selection;
  let transaction = pmView.state.tr;
  if (empty) {
    transaction = transaction.insert(from, [open, schema.text("text"), close]);
  } else {
    transaction = transaction.insert(to, close).insert(from, open);
  }
  pmView.dispatch(transaction.scrollIntoView());
  pmView.focus();
}

function replaceCode(source, options = {}) {
  state.updatingFromCode = true;
  cmView.dispatch({
    changes: { from: 0, to: cmView.state.doc.length, insert: source },
  });
  state.updatingFromCode = false;
  if (!options.skipNative) syncNativeRawEditor(source);
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
  if (declaration?.function !== undefined || declaration?.optionText !== undefined) {
    return {
      type: declaration.type,
      name: declaration.name,
      argName: declaration.argName ?? "",
      function: declaration.function ?? "",
      optionText: declaration.optionText ?? "",
    };
  }
  const expression = declaration.value ?? {};
  const functionRef = expression.function ?? {};
  return {
    type: declaration.type,
    name: declaration.name,
    argName: expression.arg?.type === "variable" ? expression.arg.name : "",
    function: functionRef.name ?? "",
    optionText: optionsToSource(functionRef.options),
  };
}

function selectorContractFromModel(model) {
  if (model?.type !== "select") return [];
  const declarations = (model.declarations ?? []).map(declarationFromRust);
  return (model.selectors ?? []).map((selector) => {
    const name = typeof selector === "string" ? selector : selector.name;
    const declaration = declarations.find((item) => item.name === name);
    return {
      name,
      function: declaration?.function ?? "",
      optionText: declaration?.optionText ?? "",
    };
  }).filter((selector) => selector.name);
}

function selectorSignature(selectors) {
  return selectors.map((selector) => selector.name).join("\u0000");
}

function selectorListLabel(selectors) {
  return selectors.map((selector) => `$${selector.name}`).join(", ") || "none";
}

function selectorAnnotationKey(selector) {
  return `${selector.function}\u0000${selector.optionText}`;
}

function selectorAnnotationLabel(selector) {
  const annotation = selector.function ? `:${selector.function}` : "no annotation";
  return selector.optionText ? `${annotation} ${selector.optionText}` : annotation;
}

function variantContractsFromModel(model, selectors) {
  const selectorNames = selectors.map((selector) => selector.name);
  return (model.variants ?? []).map((variant) => {
    const keys = (variant.keys ?? []).map(variantKeyValue);
    return {
      keys,
      signature: variantSignature(keys),
      label: variantLabel(selectorNames, keys),
    };
  });
}

function targetOnlyVariantAssessment(variant, selectors, locale, allowedSelectorValues = new Map()) {
  const selectorNames = selectors.map((selector) => selector.name);
  const label = variant.label ?? variantLabel(selectorNames, variant.keys ?? []);
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
      message: `Target adds variant ${label}; fixed or custom selector values are not inferred from CLDR plural rules, so confirm this row is intentional.`,
    };
  }
  if (!categoryDetails.length) {
    return {
      status: "review",
      message: `Target adds variant ${label}; confirm this row is intentional.`,
    };
  }
  const unsupported = categoryDetails.filter((detail) => !detail.supported);
  if (!unsupported.length) {
    const details = categoryDetails
      .map((detail) => `$${detail.selector}: ${detail.category}`)
      .join(", ");
    return {
      status: "locale-recommended",
      message: `Target adds locale-recommended CLDR row ${label} for ${locale} (${details}).`,
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
    message: `Target adds variant ${label}, but ${detailText}.`,
  };
}

function isAllowedSelectorContextValue(value, selector, allowedSelectorValues) {
  if (!selector || selectorPluralKind(selector)) return false;
  return allowedSelectorValues.get(selector.name)?.has(value) ?? false;
}

function selectorValueAllowlistForCurrentModels() {
  return selectorValueAllowlistFromModels(editorModelFromRust(state.sourceContract?.model, state.sourceContract?.source ?? ""));
}

function selectorValueAllowlistFromModels(...models) {
  const values = new Map();
  for (const model of models) {
    if (model?.type !== "select") continue;
    (model.selectors ?? []).forEach((selector, selectorIndex) => {
      const name = typeof selector === "string" ? selector : selector.name;
      if (!name) return;
      const set = values.get(name) ?? new Set();
      for (const variant of model.variants ?? []) {
        const key = variantKeyValue((variant.keys ?? [])[selectorIndex] ?? "*");
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
  if (!kind || !PLURAL_CATEGORY_ORDER.includes(category)) return null;
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
  if (!selector || !isPluralSelectorFunction(selector.function)) return null;
  const optionText = selector.optionText ?? "";
  if (optionText.includes("select=exact")) return null;
  return optionText.includes("select=ordinal") ? "ordinal" : "cardinal";
}

function missingLocalePluralDiagnostics(targetModel, selectors, locale, source) {
  if (targetModel?.type !== "select") return [];
  const diagnostics = [];
  selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return;
    const existing = new Set(
      (targetModel.variants ?? [])
        .map((variant) => variantKeyValue((variant.keys ?? [])[selectorIndex] ?? "*")),
    );
    const exactKeys = exactNumericKeysForSelectorIndex(selectorIndex, targetModel);
    const missing = [...pluralCategorySetForLocale(locale, kind)]
      .filter((category) =>
        category !== "other"
        && !existing.has(category)
        && categoryNeedsLocalePluralRow(category, locale, kind, exactKeys),
      );
    for (const category of missing) {
      diagnostics.push({
        severity: "warning",
        code: "missing-locale-plural-variant",
        message: `Target locale ${locale} has CLDR ${kind} category ${category} for $${selector.name}, but the target has no ${selector.name}: ${category} variant; fallback may still cover it.`,
        start: matchOffset(source),
        end: Math.min(source.length, matchOffset(source) + 6),
      });
    }
  });
  return diagnostics;
}

function categoryNeedsLocalePluralRow(category, locale, kind, exactKeys = new Set()) {
  return pluralSampleForCategory(category, locale, exactKeys, kind) != null;
}

function selectorPriorityVariantDiagnostics(model, selectors, locale, source) {
  if (model?.type !== "select") return [];
  const diagnostics = [];
  const seen = new Set();
  for (const overlap of selectorPriorityVariantOverlaps(model, selectors, locale)) {
    const signature = [
      variantSignature(overlap.left.keys),
      variantSignature(overlap.right.keys),
      overlap.prioritySelector.name,
    ].join("\u0000");
    if (seen.has(signature)) continue;
    seen.add(signature);
    diagnostics.push({
      severity: "warning",
      code: "selector-priority-overlap",
      message: `Target rows ${variantLabel(model.selectors ?? [], overlap.left.keys ?? [])} and ${variantLabel(model.selectors ?? [], overlap.right.keys ?? [])} can both match; $${overlap.prioritySelector.name} is the first differing selector, so ${variantLabel(model.selectors ?? [], overlap.winner.keys ?? [])} wins.`,
      start: matchOffset(source),
      end: Math.min(source.length, matchOffset(source) + 6),
    });
  }
  return diagnostics;
}

function numericVariantOverlaps(model, selectors, locale) {
  const overlaps = [];
  for (let leftIndex = 0; leftIndex < (model?.variants ?? []).length; leftIndex++) {
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
  for (let leftIndex = 0; leftIndex < (model?.variants ?? []).length; leftIndex++) {
    for (let rightIndex = leftIndex + 1; rightIndex < (model.variants ?? []).length; rightIndex++) {
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
  for (let index = 0; index < length; index++) {
    const left = variantKeyValue(leftKeys[index] ?? "*");
    const right = variantKeyValue(rightKeys[index] ?? "*");
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
  for (let index = 0; index < length; index++) {
    const delta = (leftRank[index] ?? 0) - (rightRank[index] ?? 0);
    if (delta > 0) leftMoreSpecific = true;
    if (delta < 0) rightMoreSpecific = true;
  }
  return leftMoreSpecific && rightMoreSpecific;
}

function firstDifferingIndex(leftRank, rightRank) {
  const length = Math.max(leftRank.length, rightRank.length);
  for (let index = 0; index < length; index++) {
    if ((leftRank[index] ?? 0) !== (rightRank[index] ?? 0)) return index;
  }
  return 0;
}

function compareRank(left, right) {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index++) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return left.length - right.length;
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

function variantKeyValue(key) {
  if (typeof key === "string") return key;
  if (key?.type === "*") return "*";
  return String(key?.value ?? "*");
}

function printModel(model) {
  if (model.type !== "select") return model.pattern ?? model.source ?? "";
  const lines = [];
  for (const declaration of model.declarations) {
    lines.push(declarationToSource(declaration));
  }
  lines.push(`.match ${model.selectors.map((name) => `$${name}`).join(" ")}`);
  for (const variant of model.variants) {
    lines.push(`${variant.keys.join(" ")} {{${variant.value}}}`);
  }
  return lines.join("\n");
}

function declarationToSource(declaration) {
  const options = declaration.optionText ? ` ${declaration.optionText}` : "";
  if (declaration.type === "local") {
    const arg = declaration.argName ? `$${declaration.argName}` : "";
    return `.local $${declaration.name} = {${arg} :${declaration.function}${options}}`;
  }
  return `.input {$${declaration.name} :${declaration.function}${options}}`;
}

function docFromPattern(pattern, sourcePlaceholders = []) {
  const nodes = [];
  const remainingSource = placeholderRequirementCounts(sourcePlaceholders);
  for (const part of splitPatternTokens(pattern)) {
    if (part.type === "placeholder") {
      const remaining = remainingSource.get(part.name) ?? 0;
      const source = remaining > 0;
      if (source) remainingSource.set(part.name, remaining - 1);
      nodes.push(schema.nodes.placeholder.create({ name: part.name, source }));
    } else if (part.type === "markup") {
      nodes.push(schema.nodes.markupBoundary.create({
        name: part.name,
        kind: part.kind,
        source: part.source,
      }));
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
    if (node.type.name === "markupBoundary") chunks.push(node.attrs.source);
  });
  return chunks.join("");
}

function mergeCounts(target, source) {
  for (const [name, count] of source.entries()) {
    target.set(name, (target.get(name) ?? 0) + count);
  }
}

function patternToSource(pattern) {
  return pattern.map(partToSource).join("");
}

function partToSource(part) {
  if (typeof part === "string") return part;
  if (part.type === "markup") {
    const prefix = part.kind === "close" ? "/" : "#";
    const suffix = part.kind === "standalone" ? "/" : "";
    const tail = markupTailToSource(part);
    return `{${prefix}${part.name}${tail ? ` ${tail}` : ""}${suffix}}`;
  }
  if (part.type === "expression") {
    const arg = argToSource(part.arg);
    const optionsText = optionsToSource(part.function?.options);
    const functionText = part.function?.name ? ` :${part.function.name}${optionsText ? ` ${optionsText}` : ""}` : "";
    return `{${arg}${functionText}}`;
  }
  return "";
}

function markupTailToSource(part) {
  return [optionsToSource(part.options), attributesToSource(part.attributes)].filter(Boolean).join(" ");
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

function attributesToSource(attributes) {
  return Object.entries(attributes ?? {})
    .map(([name, value]) => (value === true ? `@${name}` : `@${name}=${argToSource(value)}`))
    .join(" ");
}

function markupFromRustModel(rustModel) {
  const markup = new Map();
  if (!rustModel) return markup;
  const patterns = rustModel.type === "select"
    ? (rustModel.variants ?? []).map((variant) => variant.value ?? [])
    : [rustModel.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (part?.type === "markup") addMarkupShape(markup, part);
    }
  }
  return markup;
}

function addMarkupShape(markup, part) {
  const item = markup.get(part.name) ?? {
    name: part.name,
    open: 0,
    close: 0,
    standalone: 0,
    options: new Set(),
    attributes: new Set(),
    openSource: null,
    standaloneSource: null,
  };
  if (part.kind === "open") {
    item.open++;
    item.openSource ??= partToSource(part);
  } else if (part.kind === "close") {
    item.close++;
  } else if (part.kind === "standalone") {
    item.standalone++;
    item.standaloneSource ??= partToSource(part);
    item.openSource ??= item.standaloneSource;
  }
  for (const name of Object.keys(part.options ?? {})) item.options.add(name);
  for (const name of Object.keys(part.attributes ?? {})) item.attributes.add(name);
  markup.set(part.name, item);
}

function markupShapesEqual(left, right) {
  return MARKUP_KINDS.every((kind) => (left[kind] ?? 0) === (right[kind] ?? 0));
}

function markupShapeLabel(shape) {
  const parts = MARKUP_KINDS
    .filter((kind) => (shape[kind] ?? 0) > 0)
    .map((kind) => `${kind} x${shape[kind]}`);
  return parts.length ? parts.join(", ") : "no markers";
}

function markupContractLabel(shape) {
  return [
    markupShapeLabel(shape),
    propSetLabel("options", shape.options),
    propSetLabel("attrs", shape.attributes),
  ].filter(Boolean).join("; ");
}

function propSetLabel(label, names) {
  return names?.size ? `${label}: ${[...names].sort().join(", ")}` : "";
}

function markupPropLabel(propKind, name) {
  return propKind === "attribute" ? `@${name}` : name;
}

function markupBoundaryLabel(attrs) {
  if (attrs.kind === "close") return `{/${attrs.name}}`;
  if (attrs.kind === "standalone") return `{#${attrs.name}/}`;
  return `{#${attrs.name}}`;
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
  return selectors
    .map((selector, index) => {
      const name = selector?.name ?? selector;
      const key = variantKeyValue(keys[index] ?? "*");
      return `${name}: ${key === "*" ? "fallback" : key}`;
    })
    .join(", ");
}

function variantSignature(keys) {
  return keys.join("\u001f");
}

function isNumericKey(value) {
  return /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(String(value ?? "").trim());
}

function isSimpleKey(value) {
  return /^[A-Za-z_][\w.-]*$/u.test(String(value ?? "").trim());
}

function canonicalNumericKey(value) {
  return String(Number(value));
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

function escapeHtmlContent(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}
