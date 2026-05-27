import {
  formatMessageToPartsWithFallback,
  parseToModel,
  partsToString,
} from "@mojito-mf2/core";

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

const sampleReferences = {
  simple: "Welcome, {name}!",
  plural: "You have {count} files",
  gender: "{assignee} reviewed {count} files",
  markup: "Tap profile. {name}",
  offsetLikes: "{name} and {others_count} other users liked your post",
  offsetVariable: "{name} and {visible_count} other visible users liked your post",
};

const defaultArgs = {
  count: "2",
  gender: "unknown",
  hidden_count: "2",
  like_count: "3",
  name: "Mojito",
  url: "/people/mojito",
};
const RTL_LANGUAGES = new Set(["ar", "dv", "fa", "he", "iw", "ks", "ku", "ps", "sd", "ug", "ur", "yi"]);
const RTL_SCRIPTS = new Set(["Adlm", "Arab", "Hebr", "Mand", "Nkoo", "Rohg", "Samr", "Syrc", "Thaa"]);
const MARKUP_KINDS = ["open", "close", "standalone"];

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

export function addPluralTemplate(source, variableName = "count") {
  const simple = source.trim() || "You have {$count} files";
  const one = simple.includes(`{$${variableName}}`) ? simple : `${simple} {$${variableName}}`;
  return `.input {$${variableName} :number}
.match $${variableName}
one {{${one}}}
* {{${simple}}}`;
}

async function parseForWorkbench(source, args, locale, options = {}) {
  const parsed = parseToModel(source);
  const diagnostics = [...(parsed.diagnostics ?? [])].map(editorDiagnostic);
  let output = null;
  let parts = null;
  if (parsed.model) {
    try {
      const result = formatMessageToPartsWithFallback(parsed.model, coerceArguments(args), {
        locale,
        bidiIsolation: "default",
      });
      parts = result.parts;
      output = partsToString(parts, "default");
      diagnostics.push(...(result.errors ?? []).map(editorDiagnostic));
    } catch (error) {
      diagnostics.push(editorDiagnostic(error));
    }
  }
  if (options.compareRust) {
    diagnostics.push(...await rustComparisonDiagnostics(source, args, locale, output));
  }
  return {
    backend: options.compareRust ? "JavaScript core + Rust compare" : "JavaScript core",
    model: editorModelFromRust(parsed.model, source, diagnostics),
    output,
    parts,
  };
}

async function rustComparisonDiagnostics(source, args, locale, javascriptOutput) {
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
    if ((payload.output ?? "") !== (javascriptOutput ?? "")) {
      diagnostics.push({
        severity: "warning",
        code: "rust-output-mismatch",
        message: `Rust output differs from JavaScript core output: ${JSON.stringify(payload.output ?? "")}`,
      });
    }
    return diagnostics.map((diagnostic) => ({
      ...diagnostic,
      code: `rust-${diagnostic.code}`,
      message: diagnostic.message,
    }));
  } catch (error) {
    return [{
      severity: "warning",
      code: "rust-compare-unavailable",
      message: `Rust comparison unavailable: ${error.message}`,
    }];
  }
}

async function formatForWorkbench(source, args, locale) {
  const parsed = parseToModel(source);
  if (!parsed.model) return { output: "", errors: parsed.diagnostics ?? [] };
  try {
    const result = formatMessageToPartsWithFallback(parsed.model, coerceArguments(args), {
      locale,
      bidiIsolation: "default",
    });
    return { output: partsToString(result.parts, "default"), errors: result.errors ?? [] };
  } catch (error) {
    return { output: "", errors: [editorDiagnostic(error)] };
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

function editorDiagnostic(diagnostic) {
  return {
    severity: diagnostic.severity === "warning" ? "warning" : "error",
    code: diagnostic.code ?? diagnostic.name ?? "runtime-error",
    message: diagnostic.message ?? String(diagnostic),
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
    argName: expression.arg?.type === "variable" ? expression.arg.name : "",
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
  const entries = Object.entries(options);
  if (!entries.length) return "";
  return entries.map(([name, value]) => `${name}=${argToSource(value)}`).join(" ");
}

function attributesToSource(attributes) {
  const entries = Object.entries(attributes ?? {});
  if (!entries.length) return "";
  return entries.map(([name, value]) => (value === true ? `@${name}` : `@${name}=${argToSource(value)}`)).join(" ");
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
  collectVariableFromArg(expression.arg, variables);
  collectVariablesFromOptions(expression.function?.options, variables);
  collectVariablesFromOptions(expression.options, variables);
  collectVariablesFromAttributes(expression.attributes, variables);
}

function collectVariablesFromOptions(options, variables) {
  for (const value of Object.values(options ?? {})) {
    collectVariableFromArg(value, variables);
  }
}

function collectVariablesFromAttributes(attributes, variables) {
  for (const value of Object.values(attributes ?? {})) {
    collectVariableFromArg(value, variables);
  }
}

function collectVariableFromArg(arg, variables) {
  if (arg?.type === "variable") variables.add(arg.name);
}

export function formatMessage(model, args, locale = "en") {
  if (model.type !== "select") {
    return renderPattern(model.pattern, args);
  }
  const selectorValues = model.selectors.map((selector) => {
    const declaration = model.declarations.find((item) => item.name === selector);
    const raw = args[selector] ?? "";
    return selectorValue(raw, declaration, locale);
  });

  const selected = selectVariant(model.variants, selectorValues);
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
  const localPattern = /\.local\s+\$([^\s{}=]+)\s*=\s*\{\s*\$([^\s{}:]+)\s*:([^\s{}]+)([^}]*)\}/gu;
  for (const match of source.matchAll(localPattern)) {
    declarations.push({
      type: "local",
      name: match[1],
      argName: match[2],
      function: match[3],
      optionText: match[4].trim(),
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

function selectorValue(raw, declaration, locale) {
  const rendered = String(raw);
  if (!declaration) return { rendered, exact: rendered, category: null };
  if (declaration.function === "string") return { rendered, exact: rendered, category: null };
  if (isPluralSelectorFunction(declaration.function)) {
    const number = Number(raw);
    const exact = Number.isFinite(number)
      ? ["integer", "offset"].includes(declaration.function) ? String(Math.trunc(number)) : canonicalNumericKey(number)
      : rendered;
    if (declaration.optionText.includes("select=exact")) return { rendered, exact, category: null };
    const category = declaration.optionText.includes("select=ordinal") ? ordinalCategory(number, locale) : pluralCategory(number, locale);
    return { rendered, exact, category };
  }
  return { rendered, exact: rendered, category: null };
}

function selectVariant(variants, selectorValues) {
  let selected = null;
  let selectedRank = null;
  let fallback = null;
  for (const variant of variants ?? []) {
    if (variant.keys.every((key) => key === "*") && fallback == null) fallback = variant;
    const rank = variantMatchRank(variant, selectorValues);
    if (rank != null && (selectedRank == null || compareRank(rank, selectedRank) > 0)) {
      selected = variant;
      selectedRank = rank;
    }
  }
  return selected ?? fallback;
}

function variantMatchRank(variant, selectorValues) {
  if (variant.keys.length !== selectorValues.length) return null;
  const rank = [];
  for (let index = 0; index < variant.keys.length; index++) {
    const itemRank = keyMatchRank(variant.keys[index], selectorValues[index]);
    if (itemRank == null) return null;
    rank.push(itemRank);
  }
  return rank;
}

function keyMatchRank(key, selector) {
  if (key === "*") return 0;
  if (selector?.exact === key || selector?.rendered === key || selector?.category === key) return 1;
  return null;
}

function compareRank(left, right) {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index++) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return left.length - right.length;
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
    return key === "*" ? `${selector}: fallback` : `${selector}: ${key}`;
  });
  return `When ${parts.join(", ")}`;
}

function variantKeyLabel(selectors, keys) {
  if (!selectors.length) return "fallback";
  return selectors.map((selector, index) => {
    const key = keys[index] ?? "*";
    return key === "*" ? `${selector}: fallback` : `${selector}: ${key}`;
  }).join(", ");
}

export function withSourceContractDiagnostics(targetModel, sourceModel, { locale = "en" } = {}) {
  return {
    ...targetModel,
    diagnostics: [
      ...(targetModel.diagnostics ?? []),
      ...sourceDiagnostics(sourceModel),
      ...selectorContractDiagnostics(targetModel, sourceModel),
      ...variantContractDiagnostics(targetModel, sourceModel, { locale }),
      ...overlappingNumericVariantDiagnostics(targetModel, { locale }),
      ...selectorPriorityVariantDiagnostics(targetModel, { locale }),
      ...placeholderContractDiagnostics(targetModel, sourceModel),
      ...markupContractDiagnostics(targetModel, sourceModel),
    ],
  };
}

function sourceDiagnostics(sourceModel) {
  return (sourceModel.diagnostics ?? []).map((diagnostic) => ({
    ...diagnostic,
    code: `source-${diagnostic.code}`,
    message: `Source MF2: ${diagnostic.message}`,
  }));
}

function selectorContractDiagnostics(targetModel, sourceModel) {
  if (!targetModel || !sourceModel || sourceHasBlockingDiagnostics(sourceModel)) return [];
  const sourceSelectors = selectorContractFromModel(sourceModel);
  const targetSelectors = selectorContractFromModel(targetModel);
  const sourceByName = new Map(sourceSelectors.map((selector) => [selector.name, selector]));
  const targetByName = new Map(targetSelectors.map((selector) => [selector.name, selector]));
  const diagnostics = [];

  for (const selector of targetSelectors) {
    if (sourceByName.has(selector.name)) continue;
    diagnostics.push({
      severity: "error",
      code: "new-selector",
      message: `Target matches on $${selector.name}, but that selector does not exist in the source MF2.`,
    });
  }

  for (const selector of sourceSelectors) {
    if (targetByName.has(selector.name)) continue;
    diagnostics.push({
      severity: "error",
      code: "missing-source-selector",
      message: `Target no longer matches on source selector $${selector.name}.`,
    });
  }

  const sameSelectorSet = sourceSelectors.length === targetSelectors.length
    && sourceSelectors.every((selector) => targetByName.has(selector.name));
  if (sameSelectorSet && selectorSignature(sourceSelectors) !== selectorSignature(targetSelectors)) {
    diagnostics.push({
      severity: "error",
      code: "selector-order-mismatch",
      message: `Target selector order is ${selectorListLabel(targetSelectors)}, but source order is ${selectorListLabel(sourceSelectors)}.`,
    });
  }

  for (const sourceSelector of sourceSelectors) {
    const targetSelector = targetByName.get(sourceSelector.name);
    if (!targetSelector || selectorAnnotationKey(sourceSelector) === selectorAnnotationKey(targetSelector)) continue;
    diagnostics.push({
      severity: "error",
      code: "selector-annotation-mismatch",
      message: `Target selector $${sourceSelector.name} uses ${selectorAnnotationLabel(targetSelector)}, but source uses ${selectorAnnotationLabel(sourceSelector)}.`,
    });
  }

  return diagnostics;
}

function selectorContractFromModel(model) {
  if (model?.rustModel) return selectorContractFromRuntimeModel(model.rustModel);
  if (model?.type !== "select") return [];
  return selectorContractFromParts(model.selectors ?? [], model.declarations ?? []);
}

function selectorContractFromRuntimeModel(model) {
  if (model?.type !== "select") return [];
  const declarations = (model.declarations ?? []).map(declarationFromRust);
  return selectorContractFromParts(model.selectors ?? [], declarations);
}

function selectorContractFromParts(selectors, declarations) {
  return selectors.map((selector) => {
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

function variantContractDiagnostics(targetModel, sourceModel, { locale = "en" } = {}) {
  if (!targetModel || !sourceModel || sourceHasBlockingDiagnostics(sourceModel)) return [];
  if (sourceModel.type !== "select" || targetModel.type !== "select" || !sameSelectors(sourceModel, targetModel)) return [];
  const targetSelectors = selectorContractFromModel(targetModel);
  const allowedSelectorValues = selectorValueAllowlistFromModels(sourceModel);
  const sourceVariants = variantContractsFromModel(sourceModel);
  const targetVariants = variantContractsFromModel(targetModel);
  const sourceBySignature = new Map(sourceVariants.map((variant) => [variant.signature, variant]));
  const targetBySignature = new Map(targetVariants.map((variant) => [variant.signature, variant]));
  const diagnostics = [];

  for (const variant of sourceVariants) {
    if (targetBySignature.has(variant.signature) || nonFallbackVariantCovers(targetVariants, variant.keys)) continue;
    diagnostics.push({
      severity: "warning",
      code: "missing-source-variant",
      message: `Target is missing source variant ${variant.label}; fallback may still cover it.`,
    });
  }

  for (const variant of targetVariants) {
    if (sourceBySignature.has(variant.signature) || nonFallbackVariantCovers(sourceVariants, variant.keys)) continue;
    const assessment = targetOnlyVariantAssessment(variant, targetSelectors, locale, allowedSelectorValues);
    if (assessment.status === "locale-recommended") continue;
    diagnostics.push({
      severity: "warning",
      code: "target-only-variant",
      message: assessment.message,
    });
  }
  diagnostics.push(...missingLocalePluralDiagnostics(targetModel, targetSelectors, locale));

  return diagnostics;
}

function variantContractsFromModel(model) {
  return (model.variants ?? []).map((variant) => {
    const keys = [...(variant.keys ?? [])];
    return {
      keys,
      signature: variantSignature(keys),
      label: variantLabel(model.selectors ?? [], keys),
    };
  });
}

function nonFallbackVariantCovers(variants, keys) {
  return variants.some((variant) => !isAllWildcard(variant.keys) && variantKeysCover(variant.keys, keys));
}

function variantKeysCover(coveringKeys = [], keys = []) {
  if (coveringKeys.length !== keys.length) return false;
  return coveringKeys.every((key, index) => key === "*" || key === keys[index]);
}

function isAllWildcard(keys = []) {
  return keys.length > 0 && keys.every((key) => key === "*");
}

function targetOnlyVariantAssessment(variant, selectors, locale, allowedSelectorValues = new Map()) {
  const selectorNames = selectors.map((selector) => selector.name);
  const label = variant.label ?? variantLabel(selectorNames, variant.keys ?? []);
  const categoryDetails = (variant.keys ?? [])
    .map((key, index) => targetOnlyPluralCategoryDetail(key, selectors[index], locale))
    .filter(Boolean);
  const customKeys = (variant.keys ?? [])
    .filter((key, index) =>
      key !== "*"
      && !targetOnlyPluralCategoryDetail(key, selectors[index], locale)
      && !isAllowedSelectorContextValue(key, selectors[index], allowedSelectorValues),
    );
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
  return allowedSelectorValues.get(selector.name)?.has(String(value)) ?? false;
}

function selectorValueAllowlistFromModels(...models) {
  const values = new Map();
  for (const model of models) {
    if (model?.type !== "select") continue;
    (model.selectors ?? []).forEach((name, selectorIndex) => {
      if (!name) return;
      const set = values.get(name) ?? new Set();
      for (const variant of model.variants ?? []) {
        const key = String(variant.keys?.[selectorIndex] ?? "*");
        if (key !== "*") set.add(key);
      }
      values.set(name, set);
    });
  }
  return values;
}

function targetOnlyPluralCategoryDetail(key, selector, locale) {
  const category = String(key ?? "*");
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
  if (!selector || !isPluralSelectorFunction(selector.function)) return null;
  const optionText = selector.optionText ?? "";
  if (optionText.includes("select=exact")) return null;
  return optionText.includes("select=ordinal") ? "ordinal" : "cardinal";
}

function missingLocalePluralDiagnostics(targetModel, selectors, locale) {
  if (targetModel?.type !== "select") return [];
  const diagnostics = [];
  selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return;
    const existing = new Set(
      (targetModel.variants ?? [])
        .map((variant) => String(variant.keys?.[selectorIndex] ?? "*")),
    );
    const exactKeys = exactNumericKeysForSelectorIndex(targetModel, selectorIndex);
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
      });
    }
  });
  return diagnostics;
}

function categoryNeedsLocalePluralRow(category, locale, kind, exactKeys = new Set()) {
  return sampleForPluralCategory(category, locale, exactKeys, kind) != null;
}

function overlappingNumericVariantDiagnostics(model, { locale = "en" } = {}) {
  if (model?.type !== "select") return [];
  const selectors = selectorContractFromModel(model);
  const diagnostics = [];
  const seen = new Set();
  for (const overlap of numericVariantOverlaps(model, selectors, locale)) {
    const signature = [
      overlap.selector.name,
      overlap.exact,
      overlap.category,
      variantSignature(overlap.left.keys),
      variantSignature(overlap.right.keys),
    ].join("\u0000");
    if (seen.has(signature)) continue;
    seen.add(signature);
    diagnostics.push({
      severity: "warning",
      code: "overlapping-numeric-variant",
      message: `Target has both exact $${overlap.selector.name}: ${overlap.exact} and CLDR ${overlap.kind} category ${overlap.category} rows with the same surrounding keys; if both match, the earlier row wins.`,
    });
  }
  return diagnostics;
}

function selectorPriorityVariantDiagnostics(model, { locale = "en" } = {}) {
  if (model?.type !== "select") return [];
  const selectors = selectorContractFromModel(model);
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
    const left = String(leftKeys[index] ?? "*");
    const right = String(rightKeys[index] ?? "*");
    if (left === "*" || right === "*" || left === right) continue;
    if (numericCategoryOverlap(leftKeys[index], rightKeys[index], selectors[index], locale)) continue;
    return false;
  }
  return true;
}

function variantSpecificityRank(keys) {
  return (keys ?? []).map((key) => String(key ?? "*") === "*" ? 0 : 1);
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

function numericCategoryOverlap(leftKey, rightKey, selector, locale) {
  const left = String(leftKey ?? "*");
  const right = String(rightKey ?? "*");
  const leftCategory = categoryForExactNumericKey(left, selector, locale);
  if (leftCategory && leftCategory.category === right) return { exact: left, ...leftCategory };
  const rightCategory = categoryForExactNumericKey(right, selector, locale);
  if (rightCategory && rightCategory.category === left) return { exact: right, ...rightCategory };
  return null;
}

function categoryForExactNumericKey(key, selector, locale) {
  const kind = selectorPluralKind(selector);
  if (!kind || !isNumericKey(key)) return null;
  const value = Number(key);
  if (!Number.isFinite(value)) return null;
  const category = kind === "ordinal" ? ordinalCategory(value, locale) : pluralCategory(value, locale);
  return { category, kind };
}

function sameSurroundingVariantKeys(leftKeys = [], rightKeys = [], exceptIndex) {
  const length = Math.max(leftKeys.length, rightKeys.length);
  for (let index = 0; index < length; index++) {
    if (index === exceptIndex) continue;
    if (String(leftKeys[index] ?? "*") !== String(rightKeys[index] ?? "*")) return false;
  }
  return true;
}

function placeholderContractDiagnostics(targetModel, sourceModel) {
  if (!targetModel || !sourceModel || sourceHasBlockingDiagnostics(sourceModel)) return [];
  const sourceCounts = placeholderCountsFromModel(sourceModel);
  const targetCounts = placeholderCountsFromModel(targetModel);
  const diagnostics = [];

  for (const name of targetCounts.keys()) {
    if (sourceCounts.has(name)) continue;
    diagnostics.push({
      severity: "error",
      code: "new-placeholder",
      message: `Target uses {$${name}}, but that placeholder does not exist in the source MF2.`,
    });
  }

  for (const name of sourceCounts.keys()) {
    if (targetCounts.has(name)) continue;
    diagnostics.push({
      severity: "warning",
      code: "missing-source-placeholder",
      message: `Target no longer uses source placeholder {$${name}}.`,
    });
  }

  const targetRequirementsBySignature = new Map(
    placeholderRequirementsFromModel(targetModel).map((item) => [item.signature, item.requirements]),
  );
  for (const sourceRequirement of placeholderRequirementsFromModel(sourceModel)) {
    const targetRequirements = targetRequirementsBySignature.get(sourceRequirement.signature);
    if (!targetRequirements) continue;
    const targetRequirementCounts = new Map(targetRequirements.map((item) => [item.name, item.count]));
    for (const missing of missingRequiredPlaceholders(sourceRequirement.requirements, targetRequirementCounts)) {
      diagnostics.push({
        severity: "warning",
        code: "variant-missing-placeholder",
        message: `Target variant ${sourceRequirement.label} omits source placeholder {$${missing.name}}; this is fine if intentional.`,
      });
    }
  }

  return diagnostics;
}

function markupContractDiagnostics(targetModel, sourceModel) {
  if (!targetModel || !sourceModel || sourceHasBlockingDiagnostics(sourceModel)) return [];
  const sourceMarkup = markupShapesFromModel(sourceModel);
  const targetMarkup = markupShapesFromModel(targetModel);
  const diagnostics = [];
  for (const name of targetMarkup.keys()) {
    if (sourceMarkup.has(name)) continue;
    diagnostics.push({
      severity: "error",
      code: "new-markup",
      message: `Target uses {#${name}}, but that markup does not exist in the source MF2.`,
    });
  }
  for (const name of sourceMarkup.keys()) {
    if (targetMarkup.has(name)) continue;
    diagnostics.push({
      severity: "warning",
      code: "missing-source-markup",
      message: `Target no longer uses source markup {#${name}}.`,
    });
  }
  for (const [name, sourceShape] of sourceMarkup.entries()) {
    const targetShape = targetMarkup.get(name);
    if (!targetShape || markupShapesEqual(sourceShape, targetShape)) continue;
    diagnostics.push({
      severity: "error",
      code: "markup-shape-mismatch",
      message: `Target markup {#${name}} has ${markupShapeLabel(targetShape)}, but source has ${markupShapeLabel(sourceShape)}.`,
    });
  }
  diagnostics.push(...markupPropDiagnostics(sourceMarkup, targetMarkup));
  return diagnostics;
}

function markupPropDiagnostics(sourceMarkup, targetMarkup) {
  const diagnostics = [];
  for (const [name, sourceShape] of sourceMarkup.entries()) {
    const targetShape = targetMarkup.get(name);
    if (!targetShape) continue;
    diagnostics.push(
      ...markupPropKindDiagnostics(name, "option", sourceShape.options, targetShape.options),
      ...markupPropKindDiagnostics(name, "attribute", sourceShape.attributes, targetShape.attributes),
    );
  }
  return diagnostics;
}

function markupPropKindDiagnostics(markupName, propKind, sourceNames, targetNames) {
  const diagnostics = [];
  const displayKind = propKind === "attribute" ? "attribute" : "option";
  for (const name of targetNames) {
    if (sourceNames.has(name)) continue;
    diagnostics.push({
      severity: "error",
      code: `new-markup-${propKind}`,
      message: `Target markup {#${markupName}} adds ${displayKind} ${markupPropLabel(propKind, name)} that is not in the source MF2.`,
    });
  }
  for (const name of sourceNames) {
    if (targetNames.has(name)) continue;
    diagnostics.push({
      severity: propKind === "attribute" ? "warning" : "error",
      code: `missing-source-markup-${propKind}`,
      message: `Target markup {#${markupName}} no longer has source ${displayKind} ${markupPropLabel(propKind, name)}.`,
    });
  }
  return diagnostics;
}

function sourceHasBlockingDiagnostics(sourceModel) {
  return (sourceModel.diagnostics ?? []).some((diagnostic) => diagnostic.severity === "error");
}

function placeholderCountsFromModel(model) {
  if (model?.rustModel) {
    return placeholderCountsFromRustModel(model.rustModel);
  }
  const counts = new Map();
  for (const pattern of modelPatterns(model)) {
    mergeCounts(counts, placeholderCountsFromPattern(pattern));
  }
  return counts;
}

function placeholderCountsFromRustModel(rustModel) {
  const counts = new Map();
  const patterns = rustModel.type === "select"
    ? (rustModel.variants ?? []).map((variant) => variant.value ?? [])
    : [rustModel.pattern ?? []];
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

function placeholderRequirementsFromModel(model) {
  if (model?.rustModel) {
    return placeholderRequirementsFromRustModel(model.rustModel);
  }
  if (model?.type === "select") {
    return (model.variants ?? []).map((variant) => ({
      signature: variantSignature(variant.keys),
      label: variant.keys.join(" "),
      requirements: countsToRequirements(placeholderCountsFromPattern(variant.value ?? "")),
    }));
  }
  return [
    {
      signature: "message",
      label: "message",
      requirements: countsToRequirements(placeholderCountsFromPattern(model?.pattern ?? model?.source ?? "")),
    },
  ];
}

function placeholderRequirementsFromRustModel(rustModel) {
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

function modelPatterns(model) {
  if (!model) return [];
  if (model.type === "select") {
    return (model.variants ?? []).map((variant) => variant.value ?? "");
  }
  return [model.pattern ?? model.source ?? ""];
}

function markupShapesFromModel(model) {
  if (model?.rustModel) return markupShapesFromRustModel(model.rustModel);
  const shapes = new Map();
  for (const pattern of modelPatterns(model)) {
    for (const part of partsForPattern(pattern)) {
      if (part.type === "markup") addMarkupShape(shapes, part.name, part.kind);
    }
  }
  return shapes;
}

function markupShapesFromRustModel(rustModel) {
  const shapes = new Map();
  const patterns = rustModel.type === "select"
    ? (rustModel.variants ?? []).map((variant) => variant.value ?? [])
    : [rustModel.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (part?.type === "markup") addMarkupShapePart(shapes, part);
    }
  }
  return shapes;
}

function addMarkupShape(shapes, name, kind) {
  return addMarkupShapePart(shapes, { name, kind });
}

function addMarkupShapePart(shapes, part) {
  const shape = shapes.get(part.name) ?? {
    open: 0,
    close: 0,
    standalone: 0,
    options: new Set(),
    attributes: new Set(),
  };
  const kind = part.kind;
  if (kind === "open") shape.open++;
  else if (kind === "close") shape.close++;
  else if (kind === "standalone") shape.standalone++;
  for (const name of Object.keys(part.options ?? {})) shape.options.add(name);
  for (const name of Object.keys(part.attributes ?? {})) shape.attributes.add(name);
  shapes.set(part.name, shape);
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

function markupPropLabel(propKind, name) {
  return propKind === "attribute" ? `@${name}` : name;
}

function placeholderCountsFromPattern(pattern) {
  const counts = new Map();
  for (const match of String(pattern).matchAll(/\{\s*\$([^\s{}:]+)(?:\s|[:}])/gu)) {
    counts.set(match[1], (counts.get(match[1]) ?? 0) + 1);
  }
  return counts;
}

function mergeCounts(target, source) {
  for (const [name, count] of source.entries()) {
    target.set(name, (target.get(name) ?? 0) + count);
  }
}

function countsToRequirements(counts) {
  return Array.from(counts, ([name, count]) => ({ name, count }));
}

function missingRequiredPlaceholders(required, currentCounts) {
  return required.filter((item) => (currentCounts.get(item.name) ?? 0) < item.count);
}

function variantSignature(keys) {
  return (keys ?? []).join("\u001f");
}

function variablesFromModels(...models) {
  const variables = new Set();
  for (const model of models) {
    for (const name of model?.variables ?? []) variables.add(name);
    for (const name of placeholderCountsFromModel(model).keys()) variables.add(name);
  }
  return Array.from(variables);
}

export function scenarioRowsFromModels(sourceModel, targetModel, args, locale = "en") {
  const model = scenarioCoverageModel(sourceModel, targetModel, locale);
  if (!model) return [{ label: "Current values", arguments: { ...args } }];
  const rows = [];
  const seen = new Set();
  for (const variant of model.variants ?? []) {
    const scenarioArgs = scenarioArgumentsForVariant(model, variant.keys ?? [], args, locale);
    const signature = JSON.stringify(scenarioArgs);
    if (seen.has(signature)) continue;
    seen.add(signature);
    const details = variantScenarioDetails(model, variant.keys, scenarioArgs);
    rows.push({
      label: variantLabel(model.selectors, variant.keys),
      keys: [...(variant.keys ?? [])],
      origins: variant.origins ?? [],
      localePlural: variant.localePlural,
      overlapNotes: variant.overlapNotes ?? [],
      details,
      rowKind: scenarioRowKind(variant, details),
      arguments: scenarioArgs,
    });
  }
  return rows.slice(0, 18);
}

export function appendScenarioRowsToSelectModel(model, rows) {
  if (model?.type !== "select") return [];
  const fallbackValue = fallbackValueForScenarioAppend(model);
  let insertIndex = fallbackIndexForScenarioAppend(model);
  const added = [];
  for (const item of rows ?? []) {
    const keys = item?.row?.keys ?? item?.keys ?? [];
    if (!keys.length || scenarioVariantExists(model, keys)) continue;
    model.variants.splice(insertIndex, 0, {
      keys: [...keys],
      value: fallbackValue,
    });
    added.push([...keys]);
    insertIndex++;
  }
  return added;
}

export function removeScenarioRowsFromSelectModel(model, rows) {
  if (model?.type !== "select") return [];
  const signatures = new Set(
    (rows ?? [])
      .map((item) => item?.row?.keys ?? item?.keys ?? [])
      .filter((keys) => keys.length)
      .map(variantSignature),
  );
  if (!signatures.size) return [];
  const removed = [];
  for (let index = model.variants.length - 1; index >= 0; index--) {
    const variant = model.variants[index];
    if (!signatures.has(variantSignature(variant.keys)) || !canRemoveScenarioVariant(model, index)) continue;
    removed.push([...variant.keys]);
    model.variants.splice(index, 1);
  }
  return removed.reverse();
}

function canRemoveScenarioVariant(model, index) {
  const variants = model?.variants ?? [];
  if (variants.length <= 1) return false;
  const variant = variants[index];
  const isFallback = variant?.keys?.every((key) => key === "*");
  if (!isFallback) return true;
  return variants.some((item, itemIndex) => itemIndex !== index && item.keys.every((key) => key === "*"));
}

function fallbackIndexForScenarioAppend(model) {
  const index = (model.variants ?? []).findIndex((variant) => variant.keys.every((key) => key === "*"));
  return index >= 0 ? index : model.variants.length;
}

function fallbackValueForScenarioAppend(model) {
  return (model.variants ?? []).find((variant) => variant.keys.every((key) => key === "*"))?.value ?? "";
}

function scenarioVariantExists(model, keys) {
  return (model.variants ?? []).some((variant) => variantSignature(variant.keys) === variantSignature(keys));
}

export function scenarioOrderDetails(rows) {
  const notes = new Set();
  const rowOrderNotes = new Set();
  const selectorPriorityNotes = new Set();
  let rowCount = 0;
  let rowOrderRowCount = 0;
  let selectorPriorityRowCount = 0;
  for (const row of rows) {
    const rowNotes = row.overlapNotes ?? row.scenario?.overlapNotes ?? [];
    if (!rowNotes.length) continue;
    rowCount++;
    let hasRowOrderNote = false;
    let hasSelectorPriorityNote = false;
    for (const note of rowNotes) {
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

export function scenarioOrderSummary(rows) {
  const details = scenarioOrderDetails(rows);
  return {
    rowCount: details.rowCount,
    noteCount: details.noteCount,
    rowOrderRowCount: details.rowOrderRowCount,
    rowOrderNoteCount: details.rowOrderNoteCount,
    selectorPriorityRowCount: details.selectorPriorityRowCount,
    selectorPriorityNoteCount: details.selectorPriorityNoteCount,
  };
}

function scenarioNoteKind(note) {
  return String(note).includes("first differing selector") ? "selector-priority" : "row-order";
}

function scenarioCoverageModel(sourceModel, targetModel, locale) {
  const base = sourceModel?.type === "select" ? sourceModel : targetModel?.type === "select" ? targetModel : null;
  if (!base) return null;
  const variants = [];
  const bySignature = new Map();
  for (const [model, origin] of [[sourceModel, "source"], [targetModel, "target"]]) {
    if (model?.type !== "select" || !sameSelectors(model, base)) continue;
    for (const variant of model.variants ?? []) {
      const signature = variantSignature(variant.keys ?? []);
      const existing = bySignature.get(signature);
      if (existing) {
        existing.origins.push(origin);
        continue;
      }
      const next = { ...variant, keys: [...(variant.keys ?? [])], origins: [origin] };
      bySignature.set(signature, next);
      variants.push(next);
    }
  }
  addLocalePluralScenarioVariants(base, variants, bySignature, locale);
  annotateNumericOverlapScenarioNotes(bySignature, sourceModel, targetModel, locale);
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
    const exactKeys = exactNumericKeysForSelector(base, selector.name);
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
    .map((keys) => ({ keys, label: variantKeyLabel(selectors, keys) }));
  return {
    suggestions: suggestions.slice(0, limit),
    truncated: suggestions.length > limit,
  };
}

function scenarioArgumentsForVariant(model, keys, args, locale) {
  const scenarioArgs = { ...args };
  keys.forEach((key, index) => {
    const selector = model.selectors[index];
    if (!selector || offsetDependencyForSelector(model, selector)) return;
    scenarioArgs[selector] = scenarioValue(model, selector, key, scenarioArgs[selector], locale);
  });
  keys.forEach((key, index) => {
    const selector = model.selectors[index];
    const offset = offsetDependencyForSelector(model, selector);
    if (!offset || !isNumericKey(key)) return;
    const exact = Number(key);
    const delta = offsetDeltaForScenario(offset, scenarioArgs);
    scenarioArgs[offset.argName] = exact - delta;
    scenarioArgs[selector] = exact;
  });
  keys.forEach((key, index) => {
    const selector = model.selectors[index];
    const offset = offsetDependencyForSelector(model, selector);
    if (!offset || key === "*" || isNumericKey(key) || !PLURAL_CATEGORIES.includes(key)) return;
    const declaration = model.declarations?.find((item) => item.name === selector);
    const kind = selectorPluralKind(declaration) ?? "cardinal";
    const sample = sampleForPluralCategory(key, locale, exactNumericKeysForSelectorIndex(model, index), kind);
    if (sample == null) return;
    const delta = offsetDeltaForScenario(offset, scenarioArgs);
    scenarioArgs[offset.argName] = sample - delta;
    scenarioArgs[selector] = sample;
  });
  keys.forEach((key, index) => {
    if (key !== "*") return;
    const selector = model.selectors[index];
    const offset = offsetDependencyForSelector(model, selector);
    if (!offset) return;
    const dependencyIndex = model.selectors.indexOf(offset.argName);
    if ((keys[dependencyIndex] ?? "*") !== "*") return;
    scenarioArgs[offset.argName] = sampleForOffsetFallback(model, index, offset, scenarioArgs, scenarioArgs[offset.argName], locale);
    scenarioArgs[selector] = scenarioArgs[offset.argName] + offsetDeltaForScenario(offset, scenarioArgs);
  });
  return scenarioArgs;
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
  const dependencyExactKeys = exactNumericKeysForSelectorIndex(model, dependencyIndex);
  const offsetExactKeys = exactNumericKeysForSelectorIndex(model, selectorIndex);
  const offsetCategoryKeys = categoryKeysForSelectorIndex(model, selectorIndex);
  const declaration = model.declarations?.find((item) => item.name === model.selectors?.[selectorIndex]);
  const kind = selectorPluralKind(declaration) ?? "cardinal";
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    const shifted = candidate + delta;
    if (dependencyExactKeys.has(canonicalNumericKey(candidate))) continue;
    if (shifted < 0) continue;
    if (offsetExactKeys.has(canonicalNumericKey(shifted))) continue;
    if (offsetCategoryKeys.has(kind === "ordinal" ? ordinalCategory(shifted, locale) : pluralCategory(shifted, locale))) continue;
    return candidate;
  }
  return currentValue ?? 2;
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

function cartesian(dimensions) {
  return dimensions.reduce(
    (rows, values) => rows.flatMap((row) => values.map((value) => [...row, value])),
    [[]],
  );
}

function sameSelectors(left, right) {
  return variantSignature(left.selectors ?? []) === variantSignature(right.selectors ?? []);
}

function variantScenarioDetails(model, keys, args) {
  return (model.selectors ?? []).map((selector, index) => {
    const key = keys?.[index] ?? "*";
    return {
      selector,
      key,
      sample: args[selector],
      kind: scenarioKeyKind(model, selector, key),
    };
  });
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

function scenarioKeyKind(model, selector, key) {
  const declaration = model.declarations?.find((item) => item.name === selector);
  const isPlural = isPluralSelectorFunction(declaration?.function);
  if (key === "*") return "fallback";
  if (isPlural && isNumericKey(key)) return "exact";
  if (isPlural) return "category";
  return "value";
}

function scenarioValue(model, selector, key, currentValue, locale) {
  const declaration = model.declarations?.find((item) => item.name === selector);
  const isPlural = isPluralSelectorFunction(declaration?.function);
  if (!isPlural) return key === "*" ? (currentValue ?? defaultArgs[selector] ?? "unknown") : key;
  const kind = selectorPluralKind(declaration) ?? "cardinal";
  if (isNumericKey(key)) return Number(key);
  const excluded = exactNumericKeysForSelector(model, selector);
  if (key === "*") return sampleForPluralCategory("other", locale, excluded, kind) ?? currentValue ?? 2;
  return sampleForPluralCategory(key, locale, excluded, kind) ?? currentValue ?? 2;
}

function sampleForPluralCategory(category, locale, excluded = new Set(), kind = "cardinal") {
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    if (excluded.has(canonicalNumericKey(candidate))) continue;
    const candidateCategory = kind === "ordinal" ? ordinalCategory(candidate, locale) : pluralCategory(candidate, locale);
    if (candidateCategory === category) return candidate;
  }
  return null;
}

const PLURAL_CATEGORIES = ["zero", "one", "two", "few", "many", "other"];
const PLURAL_SAMPLE_CANDIDATES = [
  0, 1, 2, 3, 4, 5, 10, 11, 12, 20, 21, 22, 100, 101,
  1_000, 10_000, 100_000, 1_000_000, 2_000_000,
  1.1, 1.5, 2.1, 5.5,
];

function pluralCategorySetForLocale(locale, kind = "cardinal") {
  const categories = new Set();
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    categories.add(kind === "ordinal" ? ordinalCategory(candidate, locale) : pluralCategory(candidate, locale));
  }
  return new Set(PLURAL_CATEGORIES.filter((category) => categories.has(category)));
}

function exactNumericKeysForSelector(model, selector) {
  const index = model.selectors?.indexOf(selector) ?? -1;
  return exactNumericKeysForSelectorIndex(model, index);
}

function exactNumericKeysForSelectorIndex(model, index) {
  if (index < 0) return new Set();
  return new Set(
    (model.variants ?? [])
      .map((variant) => variant.keys?.[index])
      .filter(isNumericKey)
      .map(canonicalNumericKey),
  );
}

function categoryKeysForSelectorIndex(model, index) {
  if (index < 0) return new Set();
  return new Set(
    (model.variants ?? [])
      .map((variant) => variant.keys?.[index])
      .filter((key) => PLURAL_CATEGORIES.includes(key)),
  );
}

function scenarioRenderedStatus(item) {
  const origins = item.row.origins ?? [];
  if (origins.includes("locale")) return "locale-missing";
  if (origins.includes("source") && !origins.includes("target")) return "source-only";
  if (origins.includes("target") && !origins.includes("source")) {
    const assessment = targetOnlyVariantAssessment(
      item.row,
      selectorContractFromModel(item.model),
      item.locale ?? "en",
      selectorValueAllowlistFromModels(item.sourceModel),
    );
    return assessment.status === "locale-recommended" ? "locale-recommended" : "target-only";
  }
  if (item.source !== item.target) return "render-diff";
  return "aligned";
}

function scenarioStatusLabel(status) {
  if (status === "source-only") return "needs target row";
  if (status === "target-only") return "target-specific row";
  if (status === "locale-missing") return "plural row missing";
  if (status === "locale-recommended") return "target-locale row";
  if (status === "render-diff") return "translation differs";
  return "aligned";
}

export function localePluralCoverageRows(model, locale) {
  if (model?.type !== "select") return [];
  const selectors = selectorContractFromModel(model);
  return selectors.flatMap((selector, selectorIndex) => {
    const kind = selectorPluralKind(selector);
    if (!kind) return [];
    const targetKeys = new Set((model.variants ?? []).map((variant) => variant.keys?.[selectorIndex] ?? "*"));
    const exactKeys = [...exactNumericKeysForSelectorIndex(model, selectorIndex)];
    const exactKeySet = new Set(exactKeys);
    return [{
      selector: selector.name,
      kind,
      locale,
      exactKeys,
      categories: [...pluralCategorySetForLocale(locale, kind)].map((category) => {
        const uncoveredSample = sampleForPluralCategory(category, locale, exactKeySet, kind);
        const sample = uncoveredSample ?? sampleForPluralCategory(category, locale, new Set(), kind);
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

function scenarioReviewReason(item) {
  if (item.status === "source-only") {
    return "Source has this variant, but the target falls back. Add a target row if the fallback wording is not intentional.";
  }
  if (item.status === "locale-missing") {
    const detail = item.row.localePlural;
    if (detail) {
      return `${detail.locale} uses CLDR ${detail.kind} category ${detail.category} for $${detail.selector}. Add a target row if fallback wording is not enough.`;
    }
    return "The target locale has a CLDR plural category that currently reaches fallback wording.";
  }
  if (item.status === "target-only") {
    return "Target adds a variant that source does not have; keep it only if the target locale or product wording needs it.";
  }
  if (item.status === "locale-recommended") {
    return "Target adds a locale-recommended CLDR plural row, which is expected when the target locale needs a finer distinction than the source.";
  }
  if (item.status === "render-diff") {
    return "Both messages cover this case; text differs as expected for translation.";
  }
  return "Both messages cover this case with the same rendered text.";
}

function primaryPluralSelector(model) {
  if (model?.type !== "select") return null;
  return (model.selectors ?? []).find((selector) => {
    const declaration = model.declarations?.find((item) => item.name === selector);
    return isPluralSelectorFunction(declaration?.function);
  }) ?? null;
}

export function fixedExactVariantPreview(model, value) {
  if (model?.type !== "select") {
    return { label: "Add plural before adding fixed exact rows.", keys: [] };
  }
  const selector = primaryPluralSelector(model);
  if (!selector) {
    return { label: "Fixed exact rows require a :number, :integer, :percent, or :offset selector.", keys: [] };
  }
  if (!isNumericKey(value)) {
    return { label: "Enter a numeric fixed exact value.", keys: [] };
  }
  const selectorIndex = model.selectors.indexOf(selector);
  const keys = model.selectors.map((_name, index) => (index === selectorIndex ? canonicalNumericKey(value) : "*"));
  const suffix = model.selectors.length > 1 ? " Other selectors use fallback." : "";
  return {
    label: `Will add: ${variantLabel(model.selectors, keys)}.${suffix}`,
    keys,
    selector,
    suggestions: fixedExactVariantSuggestions(model, selector, keys),
  };
}

function fixedExactVariantSuggestions(model, selector, wildcardKeys) {
  if (model?.type !== "select" || model.selectors.length <= 1) return [];
  const selectorIndex = model.selectors.indexOf(selector);
  return (model.selectors ?? []).flatMap((name, index) => {
    if (index === selectorIndex || isPluralSelectorName(model, name)) return [];
    return selectorContextValues(model, index).map((value) => {
      const keys = [...wildcardKeys];
      keys[index] = value;
      return { keys, label: variantLabel(model.selectors, keys) };
    });
  });
}

function selectorContextValues(model, selectorIndex) {
  const values = [];
  for (const variant of model?.variants ?? []) {
    const key = variant.keys?.[selectorIndex];
    if (key && key !== "*" && !values.includes(key)) values.push(key);
  }
  return values;
}

function isPluralSelectorName(model, name) {
  const declaration = model?.declarations?.find((item) => item.name === name);
  return isPluralSelectorFunction(declaration?.function);
}

function isPluralSelectorFunction(functionName) {
  return ["number", "integer", "percent", "offset"].includes(functionName);
}

function isNumericKey(value) {
  return /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(String(value ?? "").trim());
}

function canonicalNumericKey(value) {
  return String(Number(value));
}

function initialize() {
  const sourceMf2 = document.querySelector("#sourceMf2");
  const targetMf2 = document.querySelector("#targetMf2");
  const locale = document.querySelector("#locale");
  const previewDirection = document.querySelector("#previewDirection");
  const compareRust = document.querySelector("#compareRust");
  const structure = document.querySelector("#structure");
  const argsContainer = document.querySelector("#arguments");
  const rendered = document.querySelector("#rendered");
  const diagnostics = document.querySelector("#diagnostics");
  const exactVariantValue = document.querySelector("#exactVariantValue");
  const exactVariantPreview = document.querySelector("#exactVariantPreview");
  const parts = document.querySelector("#parts");
  const scenarioMatrix = document.querySelector("#scenarioMatrix");
  const status = document.querySelector("#status");
  const sourceReference = document.querySelector("#sourceReference");
  const targetDirectionLabel = document.querySelector("#targetDirectionLabel");
  const state = {
    args: { ...defaultArgs },
    backend: "Starting",
    history: createSourceHistory(samples.plural),
    model: parseSource(samples.plural),
    sourceModel: parseSource(samples.plural),
    output: null,
    parts: null,
    refreshId: 0,
    suppressTargetInput: false,
  };

  sourceMf2.value = samples.plural;
  targetMf2.value = samples.plural;

  async function refresh(options = {}) {
    const refreshId = ++state.refreshId;
    const skipStructure = options.skipStructure === true;
    const parseOptions = { compareRust: compareRust.checked };
    const [sourceParsed, targetParsed] = await Promise.all([
      parseForWorkbench(sourceMf2.value, state.args, locale.value, parseOptions),
      parseForWorkbench(targetMf2.value, state.args, locale.value, parseOptions),
    ]);
    if (refreshId !== state.refreshId) return;
    state.backend = targetParsed.backend;
    state.sourceModel = sourceParsed.model;
    state.model = withSourceContractDiagnostics(targetParsed.model, sourceParsed.model, { locale: locale.value });
    state.output = targetParsed.output;
    state.parts = targetParsed.parts;
    if (!skipStructure) {
      renderStructure();
    }
    if (!options.skipArguments) {
      renderArguments();
    }
    renderExactVariantPreview();
    applyTargetDirection();
    const output = state.output ?? formatMessage(state.model, state.args, locale.value);
    rendered.value = output;
    sourceReference.textContent = sourceParsed.output ?? formatMessage(state.sourceModel, state.args, locale.value);
    const previewPattern = state.model.type === "select"
      ? (selectedVariant(state.model, state.args, locale.value)?.value ?? "")
      : state.model.pattern;
    parts.textContent = JSON.stringify(state.parts ?? partsForPattern(previewPattern, state.args), null, 2);
    renderDiagnostics();
    await renderScenarioMatrix(refreshId);
    status.textContent = state.model.diagnostics.length
      ? `${state.model.diagnostics.length} diagnostic(s) - ${state.backend}`
      : `Valid - ${state.backend}`;
  }

  function setTargetText(value, options = {}) {
    if (options.record !== false) {
      state.history.push(value);
    }
    state.suppressTargetInput = true;
    targetMf2.value = value;
    state.suppressTargetInput = false;
    applyTargetDirection();
  }

  function setTargetAndRefresh(value, options = {}) {
    setTargetText(value, options);
    void refresh();
  }

  function syncTargetFromModel(options = {}) {
    const nextTarget = printModel(state.model);
    setTargetText(nextTarget, { record: options.record !== false });
    void refresh({
      skipStructure: options.rebuildStructure !== true,
    });
  }

  function restoreTarget(value) {
    setTargetText(value, { record: false });
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
    restoreTarget(undo ? state.history.undo() : state.history.redo());
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
        setTargetText(event.target.value);
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
      syncTargetFromModel({ rebuildStructure: true });
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
          syncTargetFromModel();
        });
      });
      row.querySelector("[data-value]").addEventListener("input", (event) => {
        variant.value = event.target.value;
        syncTargetFromModel();
      });
      row.querySelector("[data-remove]").addEventListener("click", () => {
        state.model.variants.splice(variantIndex, 1);
        syncTargetFromModel({ rebuildStructure: true });
      });
      row.querySelectorAll("[data-insert]").forEach((button) => {
        button.addEventListener("click", () => {
          const textarea = row.querySelector("[data-value]");
          const token = `{$${button.dataset.insert}}`;
          const start = textarea.selectionStart;
          const end = textarea.selectionEnd;
          textarea.value = textarea.value.slice(0, start) + token + textarea.value.slice(end);
          variant.value = textarea.value;
          syncTargetFromModel();
          textarea.focus();
          textarea.setSelectionRange(start + token.length, start + token.length);
        });
      });
      list.append(row);
    });
    structure.append(list);
  }

  function renderArguments() {
    const variables = variablesFromModels(state.sourceModel, state.model);
    argsContainer.innerHTML = "";
    for (const name of variables) {
      if (!(name in state.args)) state.args[name] = defaultArgs[name] ?? "";
      const label = document.createElement("label");
      label.className = "field";
      label.innerHTML = `<span>$${escapeHtml(name)}</span><input value="${escapeHtml(String(state.args[name]))}" />`;
      label.querySelector("input").addEventListener("input", (event) => {
        state.args[name] = event.target.value;
        void refresh({ skipStructure: true, skipArguments: true });
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

  async function renderScenarioMatrix(refreshId) {
    if (!scenarioMatrix) return;
    const targetDirection = previewTextDirection();
    const rows = scenarioRowsFromModels(state.sourceModel, state.model, state.args, locale.value);
    if (rows.length <= 1 && state.sourceModel?.type !== "select" && state.model?.type !== "select") {
      scenarioMatrix.innerHTML = '<p class="hint-inline">Simple messages render from the current preview values.</p>';
      return;
    }
    scenarioMatrix.innerHTML = '<p class="hint-inline">Rendering scenario coverage...</p>';
    const renderedRows = [];
    for (const row of rows) {
      const [sourceResult, targetResult] = await Promise.all([
        formatForWorkbench(sourceMf2.value, row.arguments, locale.value),
        formatForWorkbench(targetMf2.value, row.arguments, locale.value),
      ]);
      if (refreshId !== state.refreshId) return;
      const item = { row, source: sourceResult.output, target: targetResult.output, model: state.model, sourceModel: state.sourceModel, locale: locale.value };
      item.status = scenarioRenderedStatus(item);
      item.reason = scenarioReviewReason(item);
      renderedRows.push(item);
    }
    const missingSourceRows = renderedRows.filter((item) => item.status === "source-only");
    const localeMissingRows = renderedRows.filter((item) => item.status === "locale-missing");
    const targetOnlyRows = renderedRows.filter((item) => item.status === "target-only");
    const orderSummary = scenarioOrderDetails(renderedRows.map((item) => item.row));
    scenarioMatrix.innerHTML = `
      ${missingSourceRows.length || localeMissingRows.length || targetOnlyRows.length ? `
        <div class="scenario-review-header">
          ${missingSourceRows.length ? `
            <button type="button" data-add-missing-source-rows>
              Add ${missingSourceRows.length} source-defined target row(s)
            </button>
          ` : ""}
          ${localeMissingRows.length ? `
            <button type="button" data-add-missing-locale-rows>
              Add ${localeMissingRows.length} target-locale CLDR row(s)
            </button>
          ` : ""}
          ${targetOnlyRows.length ? `
            <button type="button" data-remove-target-only-rows>
              Remove ${targetOnlyRows.length} target-specific row(s)
            </button>
          ` : ""}
        </div>
      ` : ""}
      ${renderLocalePluralCoverage(localePluralCoverageRows(state.model, locale.value))}
      ${renderScenarioOrderSummary(orderSummary)}
      <table>
        <thead>
          <tr>
            <th>Case</th>
            <th>Status</th>
            <th>Why</th>
            <th>Args</th>
            <th>Source</th>
            <th>Target</th>
          </tr>
        </thead>
        <tbody>
          ${renderedRows.map((item) => `
            <tr class="${scenarioStatusClass(item.status)}">
              <td>${renderScenarioCase(item.row)}</td>
              <td><span class="scenario-status-pill">${escapeHtml(scenarioStatusLabel(item.status))}</span></td>
              <td><span class="scenario-reason">${escapeHtml(item.reason)}</span></td>
              <td><code>${escapeHtml(JSON.stringify(item.row.arguments))}</code></td>
              <td dir="ltr">${escapeHtml(item.source)}</td>
              <td dir="${targetDirection}">${escapeHtml(item.target)}</td>
            </tr>
          `).join("")}
        </tbody>
      </table>
    `;
    scenarioMatrix.querySelector("[data-add-missing-source-rows]")?.addEventListener("click", () => {
      addScenarioRowsFromItems(missingSourceRows, "source-defined target", "No source-defined target rows to add.");
    });
    scenarioMatrix.querySelector("[data-add-missing-locale-rows]")?.addEventListener("click", () => {
      addScenarioRowsFromItems(localeMissingRows, "target-locale CLDR", "No target-locale CLDR rows to add.");
    });
    scenarioMatrix.querySelector("[data-remove-target-only-rows]")?.addEventListener("click", () => {
      removeScenarioRowsFromItems(targetOnlyRows, "target-specific", "No target-specific rows to remove.");
    });
    scenarioMatrix.querySelectorAll("[data-add-locale-suggestion]").forEach((button) => {
      button.addEventListener("click", () => {
        const keys = JSON.parse(decodeURIComponent(button.dataset.addLocaleSuggestion));
        addScenarioRowsFromItems([{ keys }], "specific target-locale", "No specific target-locale row to add.");
      });
    });
  }

  function renderScenarioOrderSummary(summary) {
    if (!summary.rowCount) return "";
    return `
      <div class="scenario-order-summary" role="note">
        ${summary.rowOrderRowCount ? `
          <strong>Row order affects ${summary.rowOrderRowCount} scenario row(s).</strong>
          MF2 tries variant rows in source order; when exact values, CLDR categories,
          or wildcards all match the same selector tuple, the first matching row wins.
          ${renderScenarioOrderSummaryNotes(summary.rowOrderNotes, "row-order")}
        ` : ""}
        ${summary.selectorPriorityRowCount ? `
          <strong>Selector priority affects ${summary.selectorPriorityRowCount} scenario row(s).</strong>
          MF2 compares selector matches from left to right; when two rows match through
          different selectors, the first differing selector decides the winner independent
          of variant row order.
          ${renderScenarioOrderSummaryNotes(summary.selectorPriorityNotes, "selector-priority")}
        ` : ""}
      </div>
    `;
  }

  function renderScenarioOrderSummaryNotes(notes = [], label = "order") {
    if (!notes.length) return "";
    return `
      <ul>
        ${notes.slice(0, 4).map((note) => `<li>${escapeHtml(note)}</li>`).join("")}
        ${notes.length > 4 ? `<li>${escapeHtml(`${notes.length - 4} more ${label} note(s) in rows below.`)}</li>` : ""}
      </ul>
    `;
  }

  function renderScenarioCase(row) {
    return `
      <div class="scenario-case">
        <div class="scenario-case-heading">
          <strong>${escapeHtml(row.label)}</strong>
          <span class="scenario-origin">${escapeHtml(scenarioOriginLabel(row.origins))}</span>
          <span class="scenario-row-kind scenario-row-kind-${escapeHtml(row.rowKind ?? "fallback")}">${escapeHtml(scenarioRowKindLabel(row.rowKind))}</span>
        </div>
        ${(row.details ?? []).map((detail) => `
          <span class="scenario-detail scenario-detail-${escapeHtml(detail.kind)}">
            <code>${escapeHtml(detail.selector)}</code>
            <strong>${escapeHtml(scenarioKindLabel(detail))}</strong>
            <code>${escapeHtml(detail.key)}</code>
            ${detail.sample !== undefined ? `<em>${escapeHtml(scenarioSampleLabel(detail))}</em>` : ""}
          </span>
        `).join("")}
        ${renderLocalePluralSuggestions(row.localePlural)}
        ${renderScenarioOverlapNotes(row.overlapNotes)}
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
        add specific rows:
        ${suggestions.map((item) => `
          <button type="button" data-add-locale-suggestion="${escapeHtml(encodeURIComponent(JSON.stringify(item.keys)))}">
            ${escapeHtml(item.label ?? item.keys.join(" "))}
          </button>
        `).join("")}
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

  function applyTargetDirection() {
    const direction = localeTextDirection(locale.value);
    const previewDirectionValue = previewTextDirection();
    targetDirectionLabel.textContent = `${direction.toUpperCase()} target`;
    targetMf2.dir = direction;
    rendered.dir = previewDirectionValue;
    structure.dir = direction;
    scenarioMatrix.dataset.targetDirection = previewDirectionValue;
    structure.querySelectorAll("textarea[data-simple], textarea[data-value]").forEach((textarea) => {
      textarea.dir = direction;
    });
  }

  function previewTextDirection() {
    const value = previewDirection?.value ?? "target";
    return value === "target" ? localeTextDirection(locale.value) : value;
  }

  function addScenarioRowsFromItems(items, label, emptyMessage) {
    const added = appendScenarioRowsToSelectModel(state.model, items);
    if (!added.length) {
      status.textContent = emptyMessage;
      return;
    }
    status.textContent = `Added ${added.length} ${label} row(s).`;
    syncTargetFromModel({ rebuildStructure: true });
  }

  function removeScenarioRowsFromItems(items, label, emptyMessage) {
    const removed = removeScenarioRowsFromSelectModel(state.model, items);
    if (!removed.length) {
      status.textContent = emptyMessage;
      return;
    }
    status.textContent = `Removed ${removed.length} ${label} row(s).`;
    syncTargetFromModel({ rebuildStructure: true });
  }

  function renderExactVariantPreview() {
    if (!exactVariantPreview) return;
    const preview = fixedExactVariantPreview(state.model, exactVariantValue.value.trim());
    exactVariantPreview.textContent = preview.label;
    exactVariantPreview.classList.toggle("invalid", !preview.keys.length);
  }

  function normalizeDeclarations() {
    for (const selector of state.model.selectors) {
      if (!state.model.declarations.some((item) => item.name === selector)) {
        state.model.declarations.push({ type: "input", name: selector, function: selector === "gender" ? "string" : "number", optionText: "" });
      }
    }
  }

  function addExactVariant() {
    if (state.model.type !== "select") {
      status.textContent = "Add plural before adding fixed numeric rows.";
      return;
    }
    const selector = primaryPluralSelector(state.model);
    if (!selector) {
      status.textContent = "Fixed rows require a :number or :integer selector.";
      return;
    }
    const value = exactVariantValue.value.trim();
    if (!isNumericKey(value)) {
      status.textContent = "Fixed row value must be numeric.";
      return;
    }
    const selectorIndex = state.model.selectors.indexOf(selector);
    const keys = state.model.selectors.map((_name, index) => (index === selectorIndex ? canonicalNumericKey(value) : "*"));
    if (state.model.variants.some((variant) => variantSignature(variant.keys) === variantSignature(keys))) {
      status.textContent = `Variant ${keys.join(" ")} already exists.`;
      return;
    }
    const fallback = state.model.variants.find((variant) => variant.keys.every((key) => key === "*"));
    const insertIndex = fallback ? state.model.variants.indexOf(fallback) : state.model.variants.length;
    state.model.variants.splice(insertIndex, 0, {
      keys,
      value: fallback?.value ?? `{$${selector}}`,
    });
    status.textContent = `Added fixed exact row ${keys.join(" ")} before fallback; row order matters if it overlaps a CLDR category row.`;
    syncTargetFromModel({ rebuildStructure: true });
  }

  document.querySelectorAll("[data-sample]").forEach((button) => {
    button.addEventListener("click", () => {
      sourceReference.textContent = sampleReferences[button.dataset.sample] ?? "";
      sourceMf2.value = samples[button.dataset.sample];
      setTargetAndRefresh(samples[button.dataset.sample]);
    });
  });
  document.querySelector("#addPlural").addEventListener("click", () => {
    setTargetAndRefresh(addPluralTemplate(targetMf2.value));
  });
  document.querySelector("#formatSource").addEventListener("click", () => {
    state.model = parseSource(targetMf2.value);
    setTargetAndRefresh(printModel(state.model));
  });
  document.querySelector("#addVariant").addEventListener("click", () => {
    if (state.model.type !== "select") {
      setTargetAndRefresh(addPluralTemplate(targetMf2.value));
      return;
    }
    state.model.variants.push({ keys: state.model.selectors.map(() => "*"), value: "" });
    syncTargetFromModel({ rebuildStructure: true });
  });
  document.querySelector("#addExactVariant").addEventListener("click", addExactVariant);
  exactVariantValue.addEventListener("input", renderExactVariantPreview);
  sourceMf2.addEventListener("input", () => void refresh({ skipStructure: true }));
  targetMf2.addEventListener("input", () => {
    if (!state.suppressTargetInput) {
      state.history.push(targetMf2.value);
    }
    void refresh();
  });
  document.addEventListener("keydown", handleHistoryShortcut);
  locale.addEventListener("change", () => void refresh({ skipStructure: true }));
  previewDirection.addEventListener("change", () => {
    applyTargetDirection();
    void renderScenarioMatrix(state.refreshId);
  });
  compareRust.addEventListener("change", () => void refresh({ skipStructure: true }));
  globalThis.mf2Workbench = {
    setSource: async (value) => {
      sourceMf2.value = value;
      await refresh({ skipStructure: true });
    },
    setTarget: async (value) => {
      setTargetText(value);
      await refresh();
    },
    diagnostics: () => state.model.diagnostics.map((diagnostic) => ({ ...diagnostic })),
  };
  void refresh();
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

function selectedVariant(model, args, locale) {
  if (model.type !== "select") return null;
  const selectorValues = model.selectors.map((selector) => {
    const declaration = model.declarations.find((item) => item.name === selector);
    return selectorValue(args[selector] ?? "", declaration, locale);
  });
  return selectVariant(model.variants, selectorValues) ?? null;
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
