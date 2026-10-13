import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { cpus } from "node:os";
import { performance } from "node:perf_hooks";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import TestRenderer from "react-test-renderer";
import { createIntl, createIntlCache, FormattedMessage, IntlProvider } from "react-intl";
import { parse } from "@formatjs/icu-messageformat-parser";
import { createMF2, MF2, MF2Provider } from "@mojito-mf2/react";
import { parseMessage } from "@mojito-mf2/react/parser";
import { acquireLock, distribution } from "./helpers.mjs";

assert.equal(process.env.NODE_ENV, "production", "Run through npm run runtime to select production React");
const argv = process.argv.slice(2);
function option(name, fallback) {
  const index = argv.indexOf(`--${name}`);
  return index < 0 ? fallback : argv[index + 1];
}
function positiveInteger(name, fallback) {
  const value = Number(option(name, fallback));
  assert(Number.isSafeInteger(value) && value > 0, `${name} must be a positive integer`);
  return value;
}
const iterations = positiveInteger("iterations", 5000);
const samples = positiveInteger("samples", 21);
const warmup = positiveInteger("warmup", 2000);
const locales = ["en", "fr", "ar"];
const h = React.createElement;
const resource = "benchmark";
const markupRenderers = {
  link: children => h("a", { href: "/terms" }, children),
  strong: children => h("strong", null, children),
};
const fixtures = [
  {
    name: "arguments",
    mf2: "Hello {$name}, your reference is {$reference}.",
    formatjs: "Hello {name}, your reference is {reference}.",
    args: i => ({ name: i % 2 ? "Reader" : "Visitor", reference: String(i % 97) }),
  },
  {
    name: "plural",
    mf2: ".input {$count :number}\n.match $count\none {{You have {$count} item.}}\n* {{You have {$count} items.}}",
    formatjs: "{count, plural, one {You have # item.} other {You have # items.}}",
    args: i => ({ count: i % 17 }),
  },
  {
    name: "select",
    mf2: ".input {$status :string}\n.match $status\nactive {{Account active: {$name}}}\n* {{Account paused: {$name}}}",
    formatjs: "{status, select, active {Account active: {name}} other {Account paused: {name}}}",
    args: i => ({ status: i % 2 ? "active" : "paused", name: "Reader" }),
  },
  {
    name: "number",
    mf2: "Total: {$amount :number maximumFractionDigits=2}",
    formatjs: "Total: {amount, number, ::.##}",
    args: i => ({ amount: 1234.567 + i % 19 }),
  },
  {
    name: "currency",
    mf2: "Price: {$amount :currency currency=USD}",
    formatjs: "Price: {amount, number, ::currency/USD}",
    args: i => ({ amount: 1234.567 + i % 19 }),
  },
  {
    name: "date",
    mf2: "Due: {$date :date length=long}",
    formatjs: "Due: {date, date, long}",
    args: i => ({ date: new Date(Date.UTC(2025, 0, 1 + i % 28)) }),
  },
  {
    name: "rich",
    mf2: "Read {#link}the {#strong}terms{/strong}{/link}, {$name}.",
    formatjs: "Read <link>the <strong>terms</strong></link>, {name}.",
    args: i => ({ name: i % 2 ? "Reader" : "Visitor" }),
  },
];
const mf2Messages = Object.fromEntries(fixtures.map(f => [f.name, parseMessage(f.mf2, { allowedMarkup: Object.keys(markupRenderers) })]));
const formatjsMessages = Object.fromEntries(fixtures.map(f => [f.name, parse(f.formatjs)]));
const resources = Object.fromEntries(locales.map(locale => [locale, { [resource]: mf2Messages }]));
const descriptors = Object.fromEntries(fixtures.map(f => [f.name, { id: f.name }]));
const fail = error => { throw error; };
function mf2Config(locale, bidiIsolation = "none") {
  return { resources, locale, sourceLocale: locale, timeZone: "UTC", bidiIsolation, onError: fail };
}
// Put deterministic timezone on the named date format. A provider-wide
// timeZone makes current React Intl rebuild/merge all date formats on every
// message call, even for text-only messages; avoid that preventable overhead.
const intlFormats = { date: { long: { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" } } };
function intlConfig(locale) {
  return { locale, defaultLocale: locale, messages: formatjsMessages, formats: intlFormats, onError: fail };
}
const pairs = Object.fromEntries(locales.map(locale => [locale, {
  mf2: createMF2(mf2Config(locale)),
  formatjs: createIntl(intlConfig(locale), createIntlCache()),
}]));
// Allocate all changing argument inputs outside the timed formatter loops.
const inputs = Object.fromEntries(fixtures.map(f => [f.name, Array.from({ length: 128 }, (_, i) => f.args(i))]));
const richInputs = inputs.rich.map(args => ({ ...args, ...markupRenderers }));
const mismatches = [];
const reactTreeDifferences = [];
function verifyReactTrees(mf2Tree, intlTree, name) {
  const mf2 = mf2Tree.toJSON();
  const formatjs = intlTree.toJSON();
  if (JSON.stringify(mf2) !== JSON.stringify(formatjs)) {
    // A formatter may emit adjacent text children separately. Keep the raw
    // difference visible, and compare actual static HTML as a second assertion.
    reactTreeDifferences.push({ name, mf2, formatjs });
  }
  function element(node) {
    if (typeof node === "string" || node === null) return node;
    return h(node.type, node.props, ...(node.children ?? []).map(element));
  }
  assert.equal(renderToStaticMarkup(element(mf2)), renderToStaticMarkup(element(formatjs)));
}
for (const locale of locales) {
  for (const fixture of fixtures) {
    for (let i = 0; i < 128; i++) {
      const args = inputs[fixture.name][i];
      const a = fixture.name === "rich"
        ? renderToStaticMarkup(h("p", null, pairs[locale].mf2.render(resource, fixture.name, args, markupRenderers)))
        : pairs[locale].mf2.format(resource, fixture.name, args);
      const b = fixture.name === "rich"
        ? renderToStaticMarkup(h("p", null, pairs[locale].formatjs.formatMessage(descriptors.rich, richInputs[i])))
        : pairs[locale].formatjs.formatMessage(descriptors[fixture.name], args);
      if (a !== b) {
        mismatches.push({ locale, fixture: fixture.name, input: args, mf2: a, formatjs: b });
        break;
      }
    }
  }
}
const results = [];
let checksum = 0;
function consume(value) {
  checksum = (checksum + (typeof value === "string" ? value.length : value?.length ?? 1)) >>> 0;
}
function timed(fn, count) {
  const start = performance.now();
  for (let i = 0; i < count; i++) consume(fn(i));
  return (performance.now() - start) * 1e6 / count;
}
function compare(name, locale, mf2, formatjs, count = iterations) {
  // Alternate order every sample to reduce systematic first/second bias.
  timed(mf2, Math.min(warmup, count));
  timed(formatjs, Math.min(warmup, count));
  const a = [], b = [];
  for (let sample = 0; sample < samples; sample++) {
    if (sample % 2) {
      b.push(timed(formatjs, count));
      a.push(timed(mf2, count));
    } else {
      a.push(timed(mf2, count));
      b.push(timed(formatjs, count));
    }
  }
  const row = { name, locale, iterationsPerSample: count, samples, unit: "ns/op", mf2: distribution(a), formatjs: distribution(b), raw: { mf2: a, formatjs: b } };
  row.medianRatio = row.mf2.median / row.formatjs.median;
  results.push(row);
  console.error(`${locale} ${name}: MF2 ${row.mf2.median.toFixed(0)} ns, FormatJS ${row.formatjs.median.toFixed(0)} ns (${row.medianRatio.toFixed(2)}x)`);
}
function reactTree(kind, locale, i) {
  const args = inputs.rich[i % 128];
  return kind === "mf2"
    ? h(MF2Provider, mf2Config(locale), h("p", null, h(MF2, { resource, name: "rich", args, markupRenderers })))
    : h(IntlProvider, intlConfig(locale), h("p", null, h(FormattedMessage, { id: "rich", values: richInputs[i % 128] })));
}
async function fingerprintSources() {
  const hashes = {};
  for (const relative of [
    "../javascript/src/formatter.js",
    "../javascript/src/intl_functions.js",
    "../javascript/src/intl_formatter_cache.js",
    "../react/src/index.js",
    "./scripts/runtime.mjs",
    "./package-lock.json",
  ]) {
    const bytes = await readFile(new URL(`../${relative}`, import.meta.url));
    hashes[relative] = createHash("sha256").update(bytes).digest("hex");
  }
  return hashes;
}
const sourceHashes = await fingerprintSources();
const releaseLock = await acquireLock();
try {
  for (const locale of locales) {
    for (const fixture of fixtures.filter(f => f.name !== "rich")) {
      if (mismatches.some(m => m.locale === locale && m.fixture === fixture.name)) continue;
      compare(`warm-format/${fixture.name}`, locale,
        i => pairs[locale].mf2.format(resource, fixture.name, inputs[fixture.name][i % 128]),
        i => pairs[locale].formatjs.formatMessage(descriptors[fixture.name], inputs[fixture.name][i % 128]));
    }
    compare("cold-instance", locale,
      () => createMF2(mf2Config(locale)),
      () => createIntl(intlConfig(locale), createIntlCache()), Math.max(25, Math.floor(iterations / 50)));
    compare("cold-instance-and-first-number", locale,
      i => createMF2(mf2Config(locale)).format(resource, "number", inputs.number[i % 128]),
      i => createIntl(intlConfig(locale), createIntlCache()).formatMessage(descriptors.number, inputs.number[i % 128]), Math.max(25, Math.floor(iterations / 50)));
    if (!mismatches.some(m => m.locale === locale && m.fixture === "rich")) {
      compare("ssr/warm-rich", locale,
        i => renderToStaticMarkup(h("p", null, pairs[locale].mf2.render(resource, "rich", inputs.rich[i % 128], markupRenderers))),
        i => renderToStaticMarkup(h("p", null, pairs[locale].formatjs.formatMessage(descriptors.rich, richInputs[i % 128]))), Math.max(25, Math.floor(iterations / 5)));
      assert.equal(renderToStaticMarkup(reactTree("mf2", locale, 1)), renderToStaticMarkup(reactTree("formatjs", locale, 1)));
      compare("ssr/cold-provider-rich", locale,
        i => renderToStaticMarkup(reactTree("mf2", locale, i)),
        i => renderToStaticMarkup(reactTree("formatjs", locale, i)), Math.max(25, Math.floor(iterations / 50)));
      const mf2Tree = TestRenderer.create(reactTree("mf2", locale, 0));
      const intlTree = TestRenderer.create(reactTree("formatjs", locale, 0));
      try {
        compare("react/update-rich-arguments", locale,
          i => mf2Tree.update(reactTree("mf2", locale, i)),
          i => intlTree.update(reactTree("formatjs", locale, i)), Math.max(25, Math.floor(iterations / 5)));
        verifyReactTrees(mf2Tree, intlTree, `rich/${locale}`);
      } finally {
        mf2Tree.unmount();
        intlTree.unmount();
      }
    }
  }
  const mf2Tree = TestRenderer.create(reactTree("mf2", "en", 0));
  const intlTree = TestRenderer.create(reactTree("formatjs", "en", 0));
  try {
    compare("react/switch-locale-rich", "en/fr/ar",
      i => mf2Tree.update(reactTree("mf2", locales[i % locales.length], i)),
      i => intlTree.update(reactTree("formatjs", locales[i % locales.length], i)), Math.max(25, Math.floor(iterations / 50)));
    verifyReactTrees(mf2Tree, intlTree, "switch-locale-rich");
  } finally {
    mf2Tree.unmount();
    intlTree.unmount();
  }
} finally {
  await releaseLock();
}
const bidiSamples = locales.map(locale => {
  const formatter = createMF2(mf2Config(locale, "default"));
  return { locale, mf2Default: formatter.format(resource, "arguments", inputs.arguments[0]), formatjs: pairs[locale].formatjs.formatMessage(descriptors.arguments, inputs.arguments[0]) };
});
async function packageVersion(name) {
  const path = new URL(`../node_modules/${name}/package.json`, import.meta.url);
  return JSON.parse(await readFile(path, "utf8")).version;
}
const finalSourceHashes = await fingerprintSources();
const sourceChangedDuringMeasurement = JSON.stringify(sourceHashes) !== JSON.stringify(finalSourceHashes);
const report = {
  schemaVersion: 1,
  sourceHashes,
  finalSourceHashes,
  sourceChangedDuringMeasurement,
  environment: { node: process.version, v8: process.versions.v8, icu: process.versions.icu, platform: process.platform, arch: process.arch, cpu: cpus()[0]?.model, cpuCount: cpus().length, nodeEnv: process.env.NODE_ENV ?? "unset" },
  versions: { react: React.version, reactIntl: await packageVersion("react-intl"), formatjsParser: await packageVersion("@formatjs/icu-messageformat-parser") },
  configuration: { iterations, samples, warmup, locales, timeZone: "UTC", bidiIsolation: "none" },
  methodology: [
    "FormatJS puts UTC on its named date format, avoiding provider-wide timezone merging on every call. MF2 uses provider UTC. All tested date outputs match.",
    "Both catalogs are compiled before timing; AST parsing, source loading and package imports are excluded.",
    "Warm formatting reuses one formatter/cache per locale. Cold rows create fresh instances and caches; engine/Intl process startup is excluded.",
    "Arguments are preallocated and cycle through 128 samples. Each result is consumed. Engine order alternates per sample.",
    "Median/p95 summarize per-batch mean ns/op, not individual request-tail latency. Raw batch means are included.",
    "React SSR and synchronous test-renderer update rows include React work, with production builds and identical output assertions. No browser DOM/layout is measured.",
    "MF2 bidi isolation is disabled only for equivalent-output timings. Default isolation outputs are recorded separately without normalization.",
    "Any output-mismatching locale/fixture pair is reported and omitted from matched warm-format comparisons.",
    "A shared lock serializes benchmark lanes; unrelated machine activity is not controlled.",
  ],
  mismatches, reactTreeDifferences, bidiSamples, results, checksum,
};
const json = `${JSON.stringify(report, null, 2)}\n`;
const output = option("output");
if (output) await writeFile(output, json);
else process.stdout.write(json);
if (mismatches.length || sourceChangedDuringMeasurement) process.exitCode = 1;
