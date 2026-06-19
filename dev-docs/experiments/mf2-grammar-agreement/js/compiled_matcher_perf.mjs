#!/usr/bin/env node
import fs from "node:fs";
import { performance } from "node:perf_hooks";

const INPUT_RE = /\.input\s+\{\$(?<name>[A-Za-z_][\w.-]*)\s+:[^}]+\}/g;
const MATCH_RE = /\.match\s+(?<selectors>(?:\$[A-Za-z_][\w.-]*\s*)+)/;
const PLACEHOLDER_RE = /\{\$(?<name>[A-Za-z_][\w.-]*)\}/g;
const VARIANT_RE = /(?<keys>(?:\S+\s+)*?\S+)\s+\{\{(?<body>.*?)\}\}/g;

const fixture =
  process.argv[2] ?? "dev-docs/experiments/mf2-grammar-agreement/fixtures/de/case-articles.json";
const iterations = Number(process.argv[3] ?? "1000000");

function compileMatcher(pattern) {
  const selectorMatch = pattern.match(MATCH_RE);
  if (!selectorMatch) throw new Error("missing .match");
  const selectors = selectorMatch.groups.selectors.trim().split(/\s+/).map((selector) => selector.slice(1));
  const declared = new Set([...pattern.matchAll(INPUT_RE)].map((match) => match.groups.name));
  const missing = selectors.filter((selector) => !declared.has(selector));
  if (missing.length) throw new Error(`missing input(s): ${missing.join(", ")}`);
  const variants = [...pattern.slice(selectorMatch.index + selectorMatch[0].length).matchAll(VARIANT_RE)].map(
    (variant) => ({
      keys: variant.groups.keys.trim().split(/\s+/),
      body: variant.groups.body,
      placeholders: [...variant.groups.body.matchAll(PLACEHOLDER_RE)].map((match) => match.groups.name),
    }),
  );
  return { selectors, variants };
}

function keyMatches(key, value) {
  return key === "*" || String(value) === key;
}

function renderBody(variant, context) {
  if (variant.placeholders.length === 0) return variant.body;
  return variant.body.replace(PLACEHOLDER_RE, (...args) => String(context[args.at(-1).name] ?? ""));
}

function renderCompiled(compiled, context) {
  let fallback;
  for (const variant of compiled.variants) {
    if (variant.keys.every((key) => key === "*")) fallback = variant;
    if (variant.keys.every((key, index) => keyMatches(key, context[compiled.selectors[index]] ?? "*"))) {
      return renderBody(variant, context);
    }
  }
  return fallback ? renderBody(fallback, context) : "";
}

const bundle = JSON.parse(fs.readFileSync(fixture, "utf8"));
const pattern = bundle.terms["item.shield"].forms.default;
const compiled = compileMatcher(pattern);
const context = { usage: "definite", case: "accusative", number: "singular" };

let checksum = 0;
for (let i = 0; i < 10000; i += 1) {
  checksum += renderCompiled(compiled, context).length;
}

const start = performance.now();
for (let i = 0; i < iterations; i += 1) {
  checksum += renderCompiled(compiled, context).length;
}
const elapsedMs = performance.now() - start;

console.log(
  JSON.stringify(
    {
      runtime: "javascript-compiled-sparse-term-matcher",
      fixture,
      iterations,
      elapsedMs: Number(elapsedMs.toFixed(3)),
      perOpUs: Number(((elapsedMs * 1000) / iterations).toFixed(3)),
      checksum,
    },
    null,
    2,
  ),
);
