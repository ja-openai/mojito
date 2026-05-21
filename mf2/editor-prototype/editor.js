export const samples = {
  simple: "Welcome, {$name}!",
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

const sampleReferences = {
  simple: "Welcome, {name}!",
  plural: "You have {count} files",
  gender: "{assignee} reviewed {count} files",
  markup: "Tap profile. {name}",
};

const defaultArgs = {
  count: "2",
  gender: "unknown",
  name: "Mojito",
  url: "/people/mojito",
};

export function createSourceHistory(initialValue = "") {
  return {
    entries: [initialValue],
    index: 0,
    push(value) {
      if (this.entries[this.index] === value) return;
      this.entries = this.entries.slice(0, this.index + 1);
      this.entries.push(value);
      if (this.entries.length > 100) {
        this.entries.shift();
      } else {
        this.index++;
      }
    },
    undo() {
      if (this.index === 0) return this.entries[this.index];
      this.index--;
      return this.entries[this.index];
    },
    redo() {
      if (this.index >= this.entries.length - 1) return this.entries[this.index];
      this.index++;
      return this.entries[this.index];
    },
  };
}

export function parseSource(source) {
  const diagnostics = basicDiagnostics(source);
  const trimmed = source.trim();
  if (!trimmed.includes(".match")) {
    return {
      type: "message",
      source,
      declarations: [],
      pattern: source,
      variables: variablesInPattern(source),
      diagnostics,
    };
  }

  const match = trimmed.match(/\.match\s+([^\n]+)\n?/u);
  if (!match) {
    diagnostics.push(error("missing-match-selector", "A .match message needs at least one selector."));
    return emptySelect(source, diagnostics);
  }

  const declarations = parseDeclarations(trimmed);
  const selectors = Array.from(match[1].matchAll(/\$([^\s{}]+)/gu), (item) => item[1]);
  if (selectors.length === 0) {
    diagnostics.push(error("missing-match-selector", "A .match message needs at least one selector variable."));
  }

  const body = trimmed.slice(match.index + match[0].length);
  const variants = parseVariants(body, selectors.length, diagnostics);
  if (!variants.some((variant) => variant.keys.every((key) => key === "*"))) {
    diagnostics.push(error("missing-fallback-variant", "Add a variant whose keys are all *."));
  }

  for (const selector of selectors) {
    if (!declarations.some((declaration) => declaration.name === selector && declaration.function)) {
      diagnostics.push(error("missing-selector-annotation", `$${selector} needs an .input or .local function annotation.`));
    }
  }

  const variables = new Set(selectors);
  for (const declaration of declarations) {
    variables.add(declaration.name);
  }
  for (const variant of variants) {
    for (const name of variablesInPattern(variant.value)) {
      variables.add(name);
    }
  }

  return {
    type: "select",
    source,
    declarations,
    selectors,
    variants,
    variables: Array.from(variables),
    diagnostics,
  };
}

export function printModel(model) {
  if (model.type !== "select") {
    return model.pattern ?? model.source ?? "";
  }
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

export function addPluralTemplate(source, variableName = "count") {
  const simple = source.trim() || "You have {$count} files";
  const one = simple.includes(`{$${variableName}}`) ? simple : `${simple} {$${variableName}}`;
  return `.input {$${variableName} :number}
.match $${variableName}
one {{${one}}}
* {{${simple}}}`;
}

async function parseForWorkbench(source, args, locale) {
  try {
    const response = await fetch("/api/format", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        source,
        locale,
        arguments: coerceArguments(args),
        bidiIsolation: "default",
      }),
    });
    if (!response.ok) {
      throw new Error(`Rust parser returned ${response.status}`);
    }
    const payload = await response.json();
    const diagnostics = [
      ...(payload.diagnostics ?? []).map(rustDiagnostic),
      ...(payload.formatErrors ?? []).map(rustDiagnostic),
    ];
    return {
      backend: "Rust parser/runtime",
      model: editorModelFromRust(payload.model, source, diagnostics),
      output: payload.output ?? null,
      parts: payload.parts ?? null,
    };
  } catch (error) {
    const model = parseSource(source);
    model.diagnostics.unshift({
      severity: "warning",
      code: "rust-parser-unavailable",
      message: `Run node mf2/editor-prototype/server.mjs for the real Rust parser. Fallback is only for UI smoke testing. ${error.message}`,
    });
    return {
      backend: "UI fallback parser",
      model,
      output: null,
      parts: null,
    };
  }
}

function coerceArguments(args) {
  const result = {};
  for (const [name, value] of Object.entries(args)) {
    const text = String(value);
    if (text.trim() !== "" && /^-?\d+(?:\.\d+)?$/u.test(text.trim())) {
      result[name] = Number(text);
    } else if (text === "true" || text === "false") {
      result[name] = text === "true";
    } else {
      result[name] = text;
    }
  }
  return result;
}

function rustDiagnostic(diagnostic) {
  return {
    severity: "error",
    code: diagnostic.code,
    message: diagnostic.message,
    start: diagnostic.start,
    end: diagnostic.end,
  };
}

function editorModelFromRust(rustModel, source, diagnostics) {
  if (!rustModel) {
    return { ...emptySelect(source, diagnostics), diagnostics };
  }
  if (rustModel.type === "select") {
    const declarations = (rustModel.declarations ?? []).map(declarationFromRust);
    const variants = (rustModel.variants ?? []).map((variant) => ({
      keys: (variant.keys ?? []).map(keyFromRust),
      value: patternToSource(variant.value ?? []),
    }));
    const variables = variablesFromRustModel(rustModel);
    return {
      type: "select",
      source,
      declarations,
      selectors: (rustModel.selectors ?? []).map((selector) => selector.name),
      variants,
      variables,
      diagnostics,
      rustModel,
    };
  }
  const pattern = patternToSource(rustModel.pattern ?? []);
  return {
    type: "message",
    source,
    declarations: (rustModel.declarations ?? []).map(declarationFromRust),
    pattern,
    variables: variablesFromRustModel(rustModel),
    diagnostics,
    rustModel,
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

function keyFromRust(key) {
  return key.type === "*" ? "*" : key.value;
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
  const entries = Object.entries(options);
  if (!entries.length) return "";
  return entries.map(([name, value]) => `${name}=${argToSource(value)}`).join(" ");
}

function variablesFromRustModel(model) {
  const variables = new Set();
  for (const declaration of model.declarations ?? []) {
    variables.add(declaration.name);
    collectVariablesFromExpression(declaration.value, variables);
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
    if (typeof part !== "string") collectVariablesFromExpression(part, variables);
  }
}

function collectVariablesFromExpression(expression, variables) {
  if (!expression) return;
  if (expression.arg?.type === "variable") variables.add(expression.arg.name);
  for (const option of Object.values(expression.function?.options ?? {})) {
    if (option.type === "variable") variables.add(option.name);
  }
}

export function formatMessage(model, args, locale = "en") {
  if (model.type !== "select") {
    return renderPattern(model.pattern, args);
  }
  const selectorValues = model.selectors.map((selector) => {
    const declaration = model.declarations.find((item) => item.name === selector);
    const raw = args[selector] ?? "";
    return selectorKey(raw, declaration, locale);
  });

  const selected = model.variants.find((variant) => variantMatches(variant, selectorValues))
    ?? model.variants.find((variant) => variant.keys.every((key) => key === "*"));
  return selected ? renderPattern(selected.value, args) : "";
}

export function partsForPattern(pattern, args = {}) {
  const parts = [];
  let index = 0;
  const token = /\{([^{}]+)\}/gu;
  for (const match of pattern.matchAll(token)) {
    if (match.index > index) {
      parts.push({ type: "text", value: pattern.slice(index, match.index) });
    }
    const body = match[1].trim();
    if (body.startsWith("#") || body.startsWith("/")) {
      parts.push(markupPart(body));
    } else if (body.startsWith("$")) {
      const name = body.slice(1).split(/\s+/u)[0];
      parts.push({ type: "expression", name, value: String(args[name] ?? "") });
    } else {
      parts.push({ type: "expression", value: body.replace(/^\|?|\|?$/gu, "") });
    }
    index = match.index + match[0].length;
  }
  if (index < pattern.length) {
    parts.push({ type: "text", value: pattern.slice(index) });
  }
  return parts;
}

function basicDiagnostics(source) {
  const diagnostics = [];
  let balance = 0;
  for (let index = 0; index < source.length; index++) {
    const ch = source[index];
    if (ch === "\\" && index + 1 < source.length) {
      index++;
      continue;
    }
    if (ch === "{") balance++;
    if (ch === "}") balance--;
    if (balance < 0) {
      diagnostics.push(error("unmatched-close", "There is a closing brace without a matching opening brace."));
      balance = 0;
    }
  }
  if (balance > 0) {
    diagnostics.push(error("unclosed-placeholder", "There is an opening brace without a matching closing brace."));
  }
  return diagnostics;
}

function parseDeclarations(source) {
  const declarations = [];
  const inputPattern = /\.input\s*\{\s*\$([^\s{}:]+)\s*:([^\s{}]+)([^}]*)\}/gu;
  for (const match of source.matchAll(inputPattern)) {
    declarations.push({
      type: "input",
      name: match[1],
      function: match[2],
      optionText: match[3].trim(),
    });
  }
  return declarations;
}

function parseVariants(body, selectorCount, diagnostics) {
  const variants = [];
  let index = 0;
  while (index < body.length) {
    while (/\s/u.test(body[index] ?? "")) index++;
    if (index >= body.length) break;

    const open = body.indexOf("{{", index);
    if (open < 0) {
      diagnostics.push(error("missing-variant-pattern", "A variant is missing a {{pattern}} body."));
      break;
    }
    const keyText = body.slice(index, open).trim();
    const close = findVariantClose(body, open + 2);
    if (close < 0) {
      diagnostics.push(error("unclosed-variant-pattern", "A variant pattern is missing }}."));
      break;
    }
    const keys = keyText ? keyText.split(/\s+/u) : [];
    if (selectorCount > 0 && keys.length !== selectorCount) {
      diagnostics.push(error("variant-key-count-mismatch", `Expected ${selectorCount} key(s), got ${keys.length}.`));
    }
    variants.push({ keys, value: body.slice(open + 2, close) });
    index = close + 2;
  }
  return variants;
}

function findVariantClose(source, start) {
  let depth = 0;
  for (let index = start; index < source.length; index++) {
    const ch = source[index];
    const next = source[index + 1];
    if (ch === "\\" && index + 1 < source.length) {
      index++;
      continue;
    }
    if (ch === "{" && next !== "{") {
      depth++;
      continue;
    }
    if (ch === "}" && depth > 0) {
      depth--;
      continue;
    }
    if (ch === "}" && next === "}" && depth === 0) {
      return index;
    }
  }
  return -1;
}

function variablesInPattern(pattern) {
  return Array.from(new Set(Array.from(pattern.matchAll(/\{\s*\$([^\s{}:]+)(?:\s|[:}])/gu), (item) => item[1])));
}

function renderPattern(pattern, args) {
  return pattern.replace(/\{\s*\$([^\s{}:]+)(?:\s*:[^}]*)?\}/gu, (_match, name) => String(args[name] ?? ""));
}

function selectorKey(raw, declaration, locale) {
  if (!declaration) return String(raw);
  if (declaration.function === "string") return String(raw);
  if (declaration.function === "number" || declaration.function === "integer") {
    if (declaration.optionText.includes("select=exact")) return String(raw);
    if (declaration.optionText.includes("select=ordinal")) return ordinalCategory(Number(raw), locale);
    return pluralCategory(Number(raw), locale);
  }
  return String(raw);
}

function variantMatches(variant, selectorValues) {
  if (variant.keys.length !== selectorValues.length) return false;
  return variant.keys.every((key, index) => key === "*" || key === selectorValues[index] || key === String(selectorValues[index]));
}

function pluralCategory(value, locale) {
  if (!Number.isFinite(value)) return "other";
  const lang = locale.toLowerCase().split(/[-_]/u)[0];
  if (lang === "ja") return "other";
  if (lang === "fr") return value >= 0 && value < 2 ? "one" : "other";
  if (lang === "ru") {
    const mod10 = Math.abs(value) % 10;
    const mod100 = Math.abs(value) % 100;
    if (mod10 === 1 && mod100 !== 11) return "one";
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "few";
    if (mod10 === 0 || (mod10 >= 5 && mod10 <= 9) || (mod100 >= 11 && mod100 <= 14)) return "many";
    return "other";
  }
  if (lang === "ar") {
    if (value === 0) return "zero";
    if (value === 1) return "one";
    if (value === 2) return "two";
    const mod100 = Math.abs(value) % 100;
    if (mod100 >= 3 && mod100 <= 10) return "few";
    if (mod100 >= 11 && mod100 <= 99) return "many";
    return "other";
  }
  return value === 1 ? "one" : "other";
}

function ordinalCategory(value, locale) {
  const lang = locale.toLowerCase().split(/[-_]/u)[0];
  if (lang !== "en") return "other";
  const mod10 = Math.abs(value) % 10;
  const mod100 = Math.abs(value) % 100;
  if (mod10 === 1 && mod100 !== 11) return "one";
  if (mod10 === 2 && mod100 !== 12) return "two";
  if (mod10 === 3 && mod100 !== 13) return "few";
  return "other";
}

function markupPart(body) {
  const close = body.startsWith("/");
  const standalone = body.endsWith("/");
  const name = body.replace(/^#|^\//u, "").replace(/\/$/u, "").split(/\s+/u)[0];
  return {
    type: "markup",
    kind: close ? "close" : standalone ? "standalone" : "open",
    name,
  };
}

function emptySelect(source, diagnostics) {
  return {
    type: "select",
    source,
    declarations: [],
    selectors: [],
    variants: [],
    variables: [],
    diagnostics,
  };
}

function error(code, message) {
  return { severity: "error", code, message };
}

function variantLabel(selectors, keys) {
  if (!selectors.length) return "Fallback";
  const parts = selectors.map((selector, index) => {
    const key = keys[index] ?? "*";
    return key === "*" ? `${selector}: any` : `${selector}: ${key}`;
  });
  return `When ${parts.join(", ")}`;
}

function initialize() {
  const source = document.querySelector("#source");
  const locale = document.querySelector("#locale");
  const structure = document.querySelector("#structure");
  const argsContainer = document.querySelector("#arguments");
  const rendered = document.querySelector("#rendered");
  const diagnostics = document.querySelector("#diagnostics");
  const parts = document.querySelector("#parts");
  const status = document.querySelector("#status");
  const sourceReference = document.querySelector("#sourceReference");
  const state = {
    args: { ...defaultArgs },
    backend: "Starting",
    history: createSourceHistory(samples.plural),
    model: parseSource(samples.plural),
    output: null,
    parts: null,
    refreshId: 0,
    suppressSourceInput: false,
  };

  source.value = samples.plural;

  async function refresh(options = {}) {
    const refreshId = ++state.refreshId;
    const skipStructure = options.skipStructure === true;
    const parsed = await parseForWorkbench(source.value, state.args, locale.value);
    if (refreshId !== state.refreshId) return;
    state.backend = parsed.backend;
    state.model = parsed.model;
    state.output = parsed.output;
    state.parts = parsed.parts;
    if (!skipStructure) {
      renderStructure();
    }
    renderArguments();
    const output = state.output ?? formatMessage(state.model, state.args, locale.value);
    rendered.value = output;
    const previewPattern = state.model.type === "select"
      ? (selectedVariant(state.model, state.args, locale.value)?.value ?? "")
      : state.model.pattern;
    parts.textContent = JSON.stringify(state.parts ?? partsForPattern(previewPattern, state.args), null, 2);
    renderDiagnostics();
    status.textContent = state.model.diagnostics.length
      ? `${state.model.diagnostics.length} diagnostic(s) - ${state.backend}`
      : `Valid - ${state.backend}`;
  }

  function setSourceText(value, options = {}) {
    if (options.record !== false) {
      state.history.push(value);
    }
    state.suppressSourceInput = true;
    source.value = value;
    state.suppressSourceInput = false;
  }

  function setSourceAndRefresh(value, options = {}) {
    setSourceText(value, options);
    void refresh();
  }

  function syncSourceFromModel(options = {}) {
    const nextSource = printModel(state.model);
    setSourceText(nextSource, { record: options.record !== false });
    void refresh({
      skipStructure: options.rebuildStructure !== true,
    });
  }

  function restoreSource(value) {
    setSourceText(value, { record: false });
    void refresh();
  }

  function handleHistoryShortcut(event) {
    const key = event.key.toLowerCase();
    const modifier = event.metaKey || event.ctrlKey;
    const undo = modifier && key === "z" && !event.altKey && !event.shiftKey;
    const redo = modifier && ((key === "z" && event.shiftKey) || key === "y") && !event.altKey;
    if (!undo && !redo) return;
    if (!event.target?.closest?.(".workspace")) return;
    event.preventDefault();
    restoreSource(undo ? state.history.undo() : state.history.redo());
  }

  function renderStructure() {
    if (state.model.type !== "select") {
      structure.innerHTML = `
        <label class="field">
          <span>Target message</span>
          <textarea data-simple>${escapeHtml(state.model.pattern ?? state.model.source ?? "")}</textarea>
        </label>
        <div class="empty-state">Use Add plural when the translation needs number-sensitive wording.</div>
      `;
      structure.querySelector("[data-simple]").addEventListener("input", (event) => {
        setSourceText(event.target.value);
        void refresh({ skipStructure: true });
      });
      return;
    }
    structure.innerHTML = "";
    const fields = document.createElement("div");
    fields.className = "field-grid";
    fields.innerHTML = `
      <label class="field">
        <span>Message selects on</span>
        <input data-role="selectors" value="${escapeHtml(state.model.selectors.join(" "))}" />
      </label>
    `;
    structure.append(fields);
    fields.querySelector("[data-role='selectors']").addEventListener("input", (event) => {
      state.model.selectors = event.target.value.trim().split(/\s+/u).filter(Boolean);
      normalizeDeclarations();
      syncSourceFromModel({ rebuildStructure: true });
    });

    const list = document.createElement("div");
    list.className = "variant-list";
    state.model.variants.forEach((variant, variantIndex) => {
      const row = document.createElement("section");
      row.className = "variant";
      row.innerHTML = `
        <div class="variant-header">
          <strong>${variantLabel(state.model.selectors, variant.keys)}</strong>
          <button type="button" data-remove="${variantIndex}">Remove</button>
        </div>
        <div class="key-list">
          ${state.model.selectors.map((_selector, keyIndex) => `
            <input aria-label="Variant key ${keyIndex + 1}" data-key="${keyIndex}" value="${escapeHtml(variant.keys[keyIndex] ?? "*")}" />
          `).join("")}
        </div>
        <label class="field">
          <span>Text</span>
          <textarea data-value>${escapeHtml(variant.value)}</textarea>
        </label>
        <div class="button-row">
          ${state.model.variables.map((name) => `<button type="button" data-insert="${escapeHtml(name)}">{$${escapeHtml(name)}}</button>`).join("")}
        </div>
      `;
      row.querySelectorAll("[data-key]").forEach((input) => {
        input.addEventListener("input", () => {
          variant.keys[Number(input.dataset.key)] = input.value || "*";
          syncSourceFromModel();
        });
      });
      row.querySelector("[data-value]").addEventListener("input", (event) => {
        variant.value = event.target.value;
        syncSourceFromModel();
      });
      row.querySelector("[data-remove]").addEventListener("click", () => {
        state.model.variants.splice(variantIndex, 1);
        syncSourceFromModel({ rebuildStructure: true });
      });
      row.querySelectorAll("[data-insert]").forEach((button) => {
        button.addEventListener("click", () => {
          const textarea = row.querySelector("[data-value]");
          const token = `{$${button.dataset.insert}}`;
          const start = textarea.selectionStart;
          const end = textarea.selectionEnd;
          textarea.value = textarea.value.slice(0, start) + token + textarea.value.slice(end);
          variant.value = textarea.value;
          syncSourceFromModel();
          textarea.focus();
          textarea.setSelectionRange(start + token.length, start + token.length);
        });
      });
      list.append(row);
    });
    structure.append(list);
  }

  function renderArguments() {
    const variables = state.model.variables.length ? state.model.variables : variablesInPattern(source.value);
    argsContainer.innerHTML = "";
    for (const name of variables) {
      if (!(name in state.args)) state.args[name] = defaultArgs[name] ?? "";
      const label = document.createElement("label");
      label.className = "field";
      label.innerHTML = `<span>$${escapeHtml(name)}</span><input value="${escapeHtml(String(state.args[name]))}" />`;
      label.querySelector("input").addEventListener("input", (event) => {
        state.args[name] = event.target.value;
        void refresh({ skipStructure: true });
      });
      argsContainer.append(label);
    }
  }

  function renderDiagnostics() {
    diagnostics.innerHTML = "";
    if (!state.model.diagnostics.length) {
      diagnostics.innerHTML = "<li class=\"ok\">No parser or runtime issues.</li>";
      return;
    }
    for (const diagnostic of state.model.diagnostics) {
      const item = document.createElement("li");
      item.className = diagnostic.severity;
      item.textContent = `${diagnostic.code}: ${diagnostic.message}`;
      diagnostics.append(item);
    }
  }

  function normalizeDeclarations() {
    for (const selector of state.model.selectors) {
      if (!state.model.declarations.some((item) => item.name === selector)) {
        state.model.declarations.push({ type: "input", name: selector, function: selector === "gender" ? "string" : "number", optionText: "" });
      }
    }
  }

  document.querySelectorAll("[data-sample]").forEach((button) => {
    button.addEventListener("click", () => {
      sourceReference.textContent = sampleReferences[button.dataset.sample] ?? "";
      setSourceAndRefresh(samples[button.dataset.sample]);
    });
  });
  document.querySelector("#addPlural").addEventListener("click", () => {
    setSourceAndRefresh(addPluralTemplate(source.value));
  });
  document.querySelector("#formatSource").addEventListener("click", () => {
    state.model = parseSource(source.value);
    setSourceAndRefresh(printModel(state.model));
  });
  document.querySelector("#addVariant").addEventListener("click", () => {
    if (state.model.type !== "select") {
      setSourceAndRefresh(addPluralTemplate(source.value));
      return;
    }
    state.model.variants.push({ keys: state.model.selectors.map(() => "*"), value: "" });
    syncSourceFromModel({ rebuildStructure: true });
  });
  source.addEventListener("input", () => {
    if (!state.suppressSourceInput) {
      state.history.push(source.value);
    }
    void refresh();
  });
  document.addEventListener("keydown", handleHistoryShortcut);
  locale.addEventListener("change", () => void refresh({ skipStructure: true }));
  void refresh();
}

function selectedVariant(model, args, locale) {
  if (model.type !== "select") return null;
  const selectorValues = model.selectors.map((selector) => {
    const declaration = model.declarations.find((item) => item.name === selector);
    return selectorKey(args[selector] ?? "", declaration, locale);
  });
  return model.variants.find((variant) => variantMatches(variant, selectorValues))
    ?? model.variants.find((variant) => variant.keys.every((key) => key === "*"))
    ?? null;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

if (typeof document !== "undefined") {
  initialize();
}
