#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const EXPRESSION_RE = /\{\$(?<arg>[A-Za-z_][\w.-]*)(?:\s+:(?<fn>[A-Za-z_][\w.-]*)(?<opts>[^{}]*))?\}/g;
const OPTION_RE = /(?<key>[A-Za-z_][\w.-]*)=(?:\$(?<ref>[A-Za-z_][\w.-]*)|(?<value>[^\s]+))/g;
const INPUT_RE = /\.input\s+\{\$(?<name>[A-Za-z_][\w.-]*)\s+:[^}]+\}/g;
const MATCH_RE = /\.match\s+(?<selectors>(?:\$[A-Za-z_][\w.-]*\s*)+)/;
const PLACEHOLDER_RE = /\{\$(?<name>[A-Za-z_][\w.-]*)\}/g;
const VARIANT_RE = /(?<keys>(?:\S+\s+)*?\S+)\s+\{\{(?<body>.*?)\}\}/g;

class DiagnosticError extends Error {
  constructor(code, fields = {}) {
    super(code);
    this.code = code;
    this.fields = fields;
  }

  matches(expected) {
    if (expected.code !== this.code) return false;
    return Object.entries(expected)
      .filter(([key]) => key !== "code")
      .every(([key, value]) => String(this.fields[key]) === String(value));
  }

  toJSON() {
    return { code: this.code, ...this.fields };
  }
}

class GrammarBundle {
  constructor(filePath) {
    this.filePath = filePath;
    this.data = JSON.parse(fs.readFileSync(filePath, "utf8"));
    this.locale = this.data.locale;
    this.messages = this.data.messages;
    this.terms = this.data.terms ?? {};
    this.people = this.data.people ?? {};
    this.adapter = adapterFor(this.locale);
  }

  resolve(id) {
    if (this.terms[id]) return { id, kind: "term", data: this.terms[id] };
    if (this.people[id]) return { id, kind: "person", data: this.people[id] };
    throw new Error(`Unknown bundle value: ${id}`);
  }

  runtimeValue(argName, argValue) {
    if (typeof argValue === "string" && argValue.startsWith("raw:")) {
      return { id: argName, kind: "literal", data: { text: argValue.slice(4) } };
    }
    if (typeof argValue === "string" && argValue.includes(".")) return this.resolve(argValue);
    return { id: argName, kind: "literal", data: { text: String(argValue) } };
  }

  format(messageId, args) {
    const pattern = this.messages[messageId].value;
    return pattern.replace(EXPRESSION_RE, (...replaceArgs) => {
      const groups = replaceArgs.at(-1);
      const value = this.runtimeValue(groups.arg, args[groups.arg]);
      if (!groups.fn) return textOf(value);
      return this.adapter.call(groups.fn, value, parseOptions(groups.opts ?? ""), args, this);
    });
  }

  validateExamples() {
    const failures = [];
    for (const [messageId, message] of Object.entries(this.messages)) {
      for (const [index, example] of (message.examples ?? []).entries()) {
        if (example.error) {
          try {
            this.format(messageId, example.args);
            failures.push(`${this.filePath}:${messageId} example ${index + 1}: expected error ${JSON.stringify(example.error)}, got success`);
          } catch (error) {
            if (!(error instanceof DiagnosticError) || !error.matches(example.error)) {
              const actual = error instanceof DiagnosticError ? error.toJSON() : { code: "unexpected-error", message: String(error) };
              failures.push(`${this.filePath}:${messageId} example ${index + 1}: expected error ${JSON.stringify(example.error)}, got ${JSON.stringify(actual)}`);
            }
          }
          continue;
        }

        const actual = this.format(messageId, example.args);
        if (actual !== example.output) {
          failures.push(`${this.filePath}:${messageId} example ${index + 1}: expected ${JSON.stringify(example.output)}, got ${JSON.stringify(actual)}`);
        }
      }
    }
    return failures;
  }
}

function parseOptions(input) {
  const options = {};
  for (const match of input.matchAll(OPTION_RE)) {
    options[match.groups.key] = match.groups.ref ?? match.groups.value;
  }
  return options;
}

function resolveScalarOptions(options, args) {
  const resolved = {};
  for (const [key, value] of Object.entries(options)) {
    if (!["with", "of"].includes(key) && Object.hasOwn(args, value) && !isResourceId(args[value])) {
      resolved[key] = String(args[value]);
    } else {
      resolved[key] = value;
    }
  }
  return resolved;
}

function isResourceId(value) {
  return typeof value === "string" && value.includes(".");
}

function textOf(value) {
  if (value.kind === "person") return value.data.displayName;
  return value.data.text;
}

function morphologyOf(value) {
  return value.data.morphology ?? {};
}

function formsOf(value) {
  return value.data.forms ?? {};
}

function optionContext(options, value) {
  const morphology = value ? morphologyOf(value) : {};
  const article = options.article ?? options.definiteness;
  return {
    usage: options.usage ?? (["definite", "indefinite", "partitive"].includes(article) ? article : "bare"),
    case: options.case ?? "*",
    number: options.number ?? morphology.number ?? "singular",
    count: options.count ?? "*",
    gender: morphology.gender ?? "*",
    animacy: morphology.animacy ?? "*",
  };
}

function pluralKeyMatches(key, value) {
  if (key === "*") return true;
  if (/^\d+$/.test(key)) return String(value) === key;
  const count = Number(value);
  if (Number.isNaN(count)) return String(value) === key;
  if (key === "one") return count === 1;
  if (key === "other") return count !== 1;
  return false;
}

function keyMatches(key, selector, value) {
  if (key === "*") return true;
  if (selector === "count") return pluralKeyMatches(key, value);
  return String(value) === key;
}

function renderPatternBody(body, context) {
  return body.replace(PLACEHOLDER_RE, (...replaceArgs) => String(context[replaceArgs.at(-1).name] ?? ""));
}

function formatMf2Match(pattern, context) {
  const selectorMatch = pattern.match(MATCH_RE);
  if (!selectorMatch) return renderPatternBody(pattern.replace(/^\{+|\}+$/g, "").trim(), context);
  const selectors = selectorMatch.groups.selectors.trim().split(/\s+/).map((selector) => selector.slice(1));
  const declaredInputs = new Set([...pattern.matchAll(INPUT_RE)].map((match) => match.groups.name));
  const missing = selectors.filter((selector) => !declaredInputs.has(selector));
  if (missing.length) throw new DiagnosticError("invalid-term-pattern", { feature: "input", value: missing.join(",") });
  const variantsSource = pattern.slice(selectorMatch.index + selectorMatch[0].length);
  let fallback = null;
  for (const variant of variantsSource.matchAll(VARIANT_RE)) {
    const keys = variant.groups.keys.trim().split(/\s+/);
    if (keys.length !== selectors.length) {
      throw new DiagnosticError("invalid-term-pattern", { feature: "variant-key-count", value: keys.join(" ") });
    }
    if (keys.every((key) => key === "*")) fallback = variant.groups.body;
    if (keys.every((key, index) => keyMatches(key, selectors[index], context[selectors[index]] ?? "*"))) {
      return renderPatternBody(variant.groups.body, context);
    }
  }
  if (fallback !== null) return renderPatternBody(fallback, context);
  throw new DiagnosticError("missing-term-form", { feature: "forms.default" });
}

class LocaleAdapter {
  call(functionName, value, options, args, bundle) {
    const resolvedOptions = resolveScalarOptions(options, args);
    if (["term", "article"].includes(functionName) && value.kind !== "term") {
      throw new DiagnosticError("invalid-argument-type", { argument: value.id, expected: "term" });
    }
    if (functionName === "term") return this.term(value, resolvedOptions);
    if (functionName === "person") {
      if (value.kind !== "person") throw new DiagnosticError("invalid-argument-type", { argument: value.id, expected: "person" });
      return textOf(value);
    }
    if (functionName === "article") return this.article(value, resolvedOptions);
    if (functionName === "agree") return this.agree(value, bundle.resolve(args[resolvedOptions.with]), resolvedOptions);
    if (functionName === "verb") {
      const target = resolvedOptions.with ? bundle.resolve(args[resolvedOptions.with]) : value;
      return this.verb(value, target, resolvedOptions);
    }
    if (functionName === "count") return this.count(value, resolvedOptions, args, bundle);
    throw new Error(`Unsupported function :${functionName}`);
  }

  term(value, options = {}) {
    if (formsOf(value).default) return formatMf2Match(formsOf(value).default, optionContext(options, value));
    return textOf(value);
  }
  article() { throw new Error("article not implemented"); }
  agree() { throw new Error("agree not implemented"); }
  verb() { throw new Error("verb not implemented"); }
  count(value) { return textOf(value); }
}

class FrenchAdapter extends LocaleAdapter {
  term(value, options) {
    if (formsOf(value).default) return formatMf2Match(formsOf(value).default, optionContext(options, value));
    const text = options.number === "plural"
      ? morphologyOf(value).forms?.plural ?? `${textOf(value)}s`
      : textOf(value);
    if (options.article === "definite") return this.article(value, { type: "definite", number: options.number, fallback: options.fallback }) + text;
    if (options.article === "indefinite") return this.article(value, { type: "indefinite", number: options.number, fallback: options.fallback }) + text;
    return text;
  }

  article(value, options) {
    const morphology = morphologyOf(value);
    const number = options.number ?? morphology.number;
    if (number === "plural") return options.type === "definite" ? "les " : "des ";
    if (options.type === "indefinite") return morphology.gender === "feminine" ? "une " : "un ";
    if (options.fallback === "error" && !Object.hasOwn(morphology.phonology ?? {}, "elides")) {
      throw new DiagnosticError("missing-morphology", { feature: "phonology.elides" });
    }
    if (morphology.phonology?.elides) return "l'";
    return morphology.gender === "feminine" ? "la " : "le ";
  }

  agree(value, target, options) {
    const targetMorphology = morphologyOf(target);
    const key = `${targetMorphology.gender}.${options.number ?? targetMorphology.number}`;
    return morphologyOf(value).forms?.[key] ?? textOf(value);
  }

  count(value, options, args, bundle) {
    const count = Number(textOf(value));
    const target = bundle.resolve(args[options.of]);
    const number = count === 1 ? "singular" : "plural";
    if (formsOf(target).default) return this.term(target, { usage: "count", number, count: String(count) });
    return `${count} ${this.term(target, { number })}`;
  }
}

class GermanAdapter extends LocaleAdapter {
  static DEFINITE_ARTICLES = {
    "masculine:nominative": "der",
    "masculine:accusative": "den",
    "masculine:dative": "dem",
    "feminine:nominative": "die",
    "feminine:accusative": "die",
    "feminine:dative": "der",
    "neuter:nominative": "das",
    "neuter:accusative": "das",
    "neuter:dative": "dem",
  };

  term(value, options) {
    if (formsOf(value).default) return formatMf2Match(formsOf(value).default, optionContext(options, value));
    if (options.article === "definite") return `${this.article(value, options)} ${textOf(value)}`;
    return textOf(value);
  }

  article(value, options) {
    const grammarCase = options.case ?? "nominative";
    const article = GermanAdapter.DEFINITE_ARTICLES[`${morphologyOf(value).gender}:${grammarCase}`];
    if (!article) throw new DiagnosticError("unsupported-option-value", { option: "case", value: grammarCase });
    return article;
  }
}

class RussianAdapter extends LocaleAdapter {
  term(value, options) {
    if (formsOf(value).default) return formatMf2Match(formsOf(value).default, optionContext(options, value));
    return options.case ? morphologyOf(value).forms?.cases?.[options.case] ?? textOf(value) : textOf(value);
  }
}

class ArabicAdapter extends LocaleAdapter {
  verb(value, target, options) {
    const targetMorphology = morphologyOf(target);
    const key = [options.tense ?? "present", targetMorphology.person ?? "third", targetMorphology.gender ?? "unknown", targetMorphology.number ?? "singular"].join(".");
    return morphologyOf(value).forms?.[key] ?? textOf(value);
  }
}

class SwahiliAdapter extends LocaleAdapter {
  agree(value, target) {
    return morphologyOf(value).forms?.[`agreement.${morphologyOf(target).agreementClass}`] ?? textOf(value);
  }
}

class JapaneseAdapter extends LocaleAdapter {
  count(value, options, args, bundle) {
    return `${textOf(value)}${morphologyOf(bundle.resolve(args[options.of])).classifier ?? ""}`;
  }
}

class KoreanAdapter extends LocaleAdapter {
  verb(value, _target, options) {
    return morphologyOf(value).forms?.[`politeness.${options.politeness}`] ?? textOf(value);
  }
}

class WelshAdapter extends LocaleAdapter {
  term(value, options) {
    if (formsOf(value).default) return formatMf2Match(formsOf(value).default, optionContext(options, value));
    return options.mutation ? morphologyOf(value).forms?.mutations?.[options.mutation] ?? textOf(value) : textOf(value);
  }
}

function adapterFor(locale) {
  const adapters = {
    fr: FrenchAdapter,
    de: GermanAdapter,
    ru: RussianAdapter,
    ar: ArabicAdapter,
    sw: SwahiliAdapter,
    ja: JapaneseAdapter,
    ko: KoreanAdapter,
    cy: WelshAdapter,
  };
  if (!adapters[locale]) throw new Error(`Unsupported locale: ${locale}`);
  return new adapters[locale]();
}

function fixturePaths(root) {
  const output = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const fullPath = path.join(root, entry.name);
    if (entry.isDirectory()) output.push(...fixturePaths(fullPath));
    if (entry.isFile() && entry.name.endsWith(".json")) output.push(fullPath);
  }
  return output.sort();
}

export { GrammarBundle };

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const root = process.argv[2] ?? "dev-docs/experiments/mf2-grammar-agreement/fixtures";
  const failures = [];
  for (const fixturePath of fixturePaths(root)) {
    const bundle = new GrammarBundle(fixturePath);
    const bundleFailures = bundle.validateExamples();
    if (bundleFailures.length) failures.push(...bundleFailures);
    else console.log(`ok ${fixturePath}`);
  }

  if (failures.length) {
    console.error(failures.join("\n"));
    process.exit(1);
  }
}
