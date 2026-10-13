import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFile, readdir, writeFile } from "node:fs/promises";
import { relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { brotliCompressSync, constants, gzipSync } from "node:zlib";
import { build, transform, version as esbuildVersion } from "esbuild";
import { parse } from "@formatjs/icu-messageformat-parser";
import { IntlMessageFormat } from "intl-messageformat";
import { FunctionRegistry, formatMessage } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
import { compileCatalog, generateTypes } from "../../tooling/src/index.js";
import { acquireLock } from "./helpers.mjs";
import { locales, makeCatalog, resourceNames, splitResources } from "../fixtures/bundle-catalogs.mjs";

const cwd = fileURLToPath(new URL("../", import.meta.url));
const repository = resolve(cwd, "../..");
const argv = process.argv.slice(2);
const outputIndex = argv.indexOf("--output");
assert(outputIndex < 0 || argv[outputIndex + 1], "--output requires a filename");
const output = outputIndex < 0 ? undefined : resolve(argv[outputIndex + 1]);
const sha256 = value => createHash("sha256").update(value).digest("hex");
function sizes(value) {
  const bytes = typeof value === "string" ? Buffer.from(value) : value;
  return {
    bytes: bytes.length,
    gzip: gzipSync(bytes, { level: 9 }).length,
    brotli: brotliCompressSync(bytes, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length,
    sha256: sha256(bytes),
  };
}
const jsonSizes = value => sizes(JSON.stringify(value));
const sumSizes = entries => Object.fromEntries(["bytes", "gzip", "brotli"].map(key => [key, entries.reduce((sum, item) => sum + item[key], 0)]));
const delta = (larger, smaller) => Object.fromEntries(["bytes", "gzip", "brotli"].map(key => [key, larger[key] - smaller[key]]));
const packageVersion = async name => JSON.parse(await readFile(resolve(cwd, "node_modules", name, "package.json"), "utf8")).version;
const intlFormats = { date: { long: { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" } } };
function verifyCatalog(locale, mf2, formatjs) {
  const functions = createIntlFunctionRegistry(FunctionRegistry, { timeZone: "UTC" });
  let checks = 0;
  for (const [name, model] of Object.entries(mf2)) {
    const formatter = new IntlMessageFormat(formatjs[name], locale, intlFormats);
    for (const count of [0, 1, 2]) {
      const args = { count, name: "Reader", amount: 1234.56, date: new Date(Date.UTC(2025, 0, 15)), status: count === 1 ? "active" : "paused" };
      const result = formatMessage(model, args, { locale, functions, bidiIsolation: "none" });
      assert.equal(result.hasErrors, false, `${locale}/${name}: ${JSON.stringify(result.errors)}`);
      const expected = formatter.format({ ...args, link: children => children.join(""), strong: children => children.join("") });
      assert.equal(result.value, expected, `${locale}/${name}/${count} outputs differ`);
      checks++;
    }
  }
  return checks;
}

const settings = {
  absWorkingDir: cwd,
  bundle: true,
  minify: true,
  treeShaking: true,
  platform: "browser",
  format: "esm",
  target: "es2022",
  charset: "utf8",
  legalComments: "none",
  sourcemap: false,
  define: { "process.env.NODE_ENV": '"production"' },
  external: ["react", "react/*", "react-dom", "react-dom/*"],
  alias: { "@formatjs/icu-messageformat-parser": "@formatjs/icu-messageformat-parser/no-parser.js" },
  metafile: true,
  write: false,
  logLevel: "silent",
};
const artifacts = [];
function inputName(path) {
  return path.startsWith("bench:") ? path : relative(repository, resolve(cwd, path));
}
async function bundle(name, source, modules = {}, splitting = false, includeReact = false) {
  const result = await build({
    ...settings,
    external: includeReact ? [] : settings.external,
    // Local linked packages may have their own development React installs.
    // Use the same production React instance when measuring complete payloads.
    alias: includeReact ? { ...settings.alias, react: resolve(cwd, "node_modules/react") } : settings.alias,
    stdin: { contents: source, resolveDir: cwd, sourcefile: "entry.js", loader: "js" },
    outdir: resolve(cwd, "results", "bundle"),
    entryNames: "entry",
    chunkNames: "chunks/[name]-[hash]",
    splitting,
    plugins: [{
      name: "synthetic-benchmark-modules",
      setup(builder) {
        builder.onResolve({ filter: /^bench:/ }, args => ({ path: args.path.slice(6), namespace: "bench" }));
        builder.onLoad({ filter: /.*/, namespace: "bench" }, args => {
          assert(Object.hasOwn(modules, args.path), `Missing benchmark module: ${args.path}`);
          return { contents: modules[args.path], loader: args.path.endsWith(".json") ? "json" : args.path.endsWith(".ts") ? "ts" : "js", resolveDir: cwd };
        });
      },
    }],
  });
  const retained = new Map();
  const files = [];
  for (const [path, info] of Object.entries(result.metafile.outputs)) {
    const emitted = result.outputFiles.find(file => file.path === resolve(cwd, path));
    assert(emitted, `Missing output for ${path}`);
    for (const [input, contribution] of Object.entries(info.inputs)) {
      if (contribution.bytesInOutput) retained.set(inputName(input), (retained.get(inputName(input)) ?? 0) + contribution.bytesInOutput);
    }
    files.push({
      name: relative(resolve(cwd, "results", "bundle"), resolve(cwd, path)),
      ...sizes(emitted.contents),
      entry: info.entryPoint === "entry.js",
      imports: info.imports.map(item => ({ path: item.external ? item.path : relative(resolve(cwd, "results", "bundle"), resolve(cwd, item.path)), kind: item.kind, external: Boolean(item.external) })),
    });
  }
  const entry = files.find(file => file.entry) ?? files.find(file => !file.name.startsWith("chunks/"));
  assert(entry, "An entry bundle must be emitted");
  const initial = new Set();
  function include(file) {
    if (initial.has(file.name)) return;
    initial.add(file.name);
    for (const item of file.imports) {
      if (!item.external && item.kind !== "dynamic-import") include(files.find(candidate => candidate.name === item.path));
    }
  }
  include(entry);
  const graph = [...retained].map(([path, bytesInOutput]) => ({ path, bytesInOutput })).sort((a, b) => b.bytesInOutput - a.bytesInOutput);
  const forbidden = graph.filter(({ path }) => /mf2\/(javascript\/src\/parser\.js|react\/src\/parser\.js|tooling\/)/u.test(path) || /node_modules\/@formatjs\/(icu-messageformat-parser\/index\.js|icu-skeleton-parser\/)/u.test(path));
  assert.deepEqual(forbidden, [], `${name} retained a parser/tooling module`);
  assert(!files.some(file => file.imports.some(item => item.external && item.path.startsWith("node:"))), `${name} imports a Node runtime module`);
  const row = {
    name,
    reactBundled: includeReact,
    files,
    total: sumSizes(files),
    initial: sumSizes(files.filter(file => initial.has(file.name))),
    deferred: sumSizes(files.filter(file => !initial.has(file.name))),
    initialFiles: [...initial],
    graph,
    graphChecks: { parserToolingAbsent: true, nodeBuiltinsAbsent: true },
  };
  artifacts.push(row);
  return row;
}

const coreSources = {
  mf2: `import { FunctionRegistry, formatMessage } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
export function createFormatter(model, locale) {
  const functions = createIntlFunctionRegistry(FunctionRegistry, { timeZone: "UTC" });
  return args => formatMessage(model, args, { locale, functions, bidiIsolation: "none" }).value;
}`,
  formatjs: `import { IntlMessageFormat } from "intl-messageformat";
export function createFormatter(model, locale) {
  const formatter = new IntlMessageFormat(model, locale, { date: { long: { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" } } });
  return args => formatter.format(args);
}`,
};
function reactSource(engine, rich, typed = false) {
  const shared = 'import React from "react";\n';
  if (engine === "mf2") return shared + `import { MF2Provider, ${typed ? "useMF2" : "MF2, useMF2"} } from "@mojito-mf2/react";
${typed ? 'import { MF2 } from "bench:messages.ts";' : ""}
function Content({ name, args }) { return React.createElement("p", null, ${rich
    ? `React.createElement(MF2, { ${typed ? "" : 'resource: "benchmark",'} name, args, markupRenderers: { link: children => React.createElement("a", { href: "/terms" }, children), strong: children => React.createElement("strong", null, children) } })`
    : 'useMF2().format("benchmark", name, args)'}); }
export function App({ messages, locale, name, args }) {
  return React.createElement(MF2Provider, { resources: { [locale]: { benchmark: messages } }, locale, sourceLocale: locale, timeZone: "UTC", bidiIsolation: "none" }, React.createElement(Content, { name, args }));
}`;
  return shared + `import { IntlProvider, FormattedMessage, useIntl } from "react-intl";
const formats = { date: { long: { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" } } };
function Content({ name, args }) { return React.createElement("p", null, ${rich
    ? 'React.createElement(FormattedMessage, { id: name, values: { ...args, link: children => React.createElement("a", { href: "/terms" }, children), strong: children => React.createElement("strong", null, children) } })'
    : 'useIntl().formatMessage({ id: name }, args)'}); }
export function App({ messages, locale, name, args }) {
  return React.createElement(IntlProvider, { messages, locale, defaultLocale: locale, formats }, React.createElement(Content, { name, args }));
}`;
}

async function fingerprint() {
  const files = [resolve(cwd, "package-lock.json"), fileURLToPath(import.meta.url), resolve(cwd, "fixtures/bundle-catalogs.mjs")];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.name.endsWith(".js")) files.push(path);
    }
  }
  for (const name of ["javascript", "react", "tooling"]) await visit(resolve(cwd, "..", name, "src"));
  const hashes = {};
  for (const path of files.sort()) hashes[relative(repository, path)] = sha256(await readFile(path));
  return hashes;
}

const releaseLock = await acquireLock();
let report;
try {
  const sourceHashes = await fingerprint();
  const comparisons = [];
  for (const [name, source] of Object.entries(coreSources)) await bundle(`core-string/${name}`, source);
  for (const rich of [false, true]) {
    const workload = rich ? "react-rich" : "react-string";
    for (const engine of ["mf2", "formatjs"]) await bundle(`${workload}/${engine}`, reactSource(engine, rich));
  }
  for (const engine of ["mf2", "formatjs"]) await bundle(`react-rich-with-react/${engine}`, reactSource(engine, true), {}, false, true);
  const core = artifacts.find(row => row.name === "core-string/mf2");
  const rootImport = await bundle("tree-shaking/core-root-import", coreSources.mf2.replace('"@mojito-mf2/core/formatter"', '"@mojito-mf2/core"'));
  // Import traversal may rename minified symbols without retaining more code.
  // The graph assertion above is the parser-exclusion check, not byte identity.
  comparisons.push({ name: "core-root-unused-parser-export", parserAbsent: true, identicalOutput: core.files[0].sha256 === rootImport.files[0].sha256, delta: delta(rootImport.total, core.total) });
  const rich = artifacts.find(row => row.name === "react-rich/mf2");
  const unused = await bundle("tree-shaking/unused-react-exports", reactSource("mf2", true).replace("MF2Provider, MF2, useMF2", "MF2Provider, MF2, useMF2, createMF2, renderMF2Parts"));
  assert.deepEqual(rich.graph.map(row => row.path).sort(), unused.graph.map(row => row.path).sort(), "Unused named imports must not retain additional modules");
  const all = await bundle("tree-shaking/all-react-exports", 'export * from "@mojito-mf2/react"; export * from "@mojito-mf2/react/bindings";');
  const removed = await bundle("tree-shaking/entire-unused-entrypoints", 'import { MF2 } from "@mojito-mf2/react"; import { createMessages } from "@mojito-mf2/react/bindings"; import { parseMessage } from "@mojito-mf2/react/parser"; export const ready = true;');
  assert.deepEqual(removed.graph.map(row => row.path), ["mf2/benchmarks/entry.js"], "Entire unused runtime/parser/binding entrypoints must disappear");
  comparisons.push({ name: "unused-react-named-imports", sameRetainedModules: true, identicalOutput: rich.files[0].sha256 === unused.files[0].sha256, delta: delta(unused.total, rich.total), note: "Minifier identifier assignment can differ after changing imports." });
  comparisons.push({ name: "entire-unused-entrypoints", retainedDependencyBytes: 0, total: removed.total });
  comparisons.push({ name: "all-react-exports-versus-rich-app", delta: delta(all.total, rich.total), note: "Different wrapper code; this is retained API surface cost, not an exact dead-code byte count." });

  const catalogRows = [], fullCatalogs = {}, typedRows = [];
  let equivalentCatalogOutputs = 0;
  for (const count of [100, 1000]) {
    for (const locale of locales) {
      const source = makeCatalog(locale, count);
      const compiled = compileCatalog(source.mf2);
      assert(compiled.ok, JSON.stringify(compiled.diagnostics));
      const formatjs = Object.fromEntries(Object.entries(source.formatjs).map(([name, text]) => [name, parse(text)]));
      equivalentCatalogOutputs += verifyCatalog(locale, compiled.artifact.messages, formatjs);
      catalogRows.push({
        locale, messages: count,
        mf2: { source: jsonSizes(source.mf2), ast: jsonSizes(compiled.artifact.messages), artifact: jsonSizes(compiled.artifact) },
        formatjs: { source: jsonSizes(source.formatjs), ast: jsonSizes(formatjs) },
      });
      if (count === 1000) fullCatalogs[locale] = { mf2: splitResources(compiled.artifact.messages), formatjs: splitResources(formatjs) };
      if (locale === "en") {
        const generated = generateTypes(source.mf2, { resource: "benchmark" });
        assert(generated.ok, JSON.stringify(generated.diagnostics));
        const erased = await transform(generated.code, { loader: "ts", format: "esm", target: "es2022" });
        const typed = await bundle(`typed-bindings/${count}-messages`, reactSource("mf2", true, true), { "messages.ts": generated.code });
        typedRows.push({ messages: count, typescriptSource: sizes(generated.code), erasedJavascript: sizes(erased.code), runtime: typed.total, deltaFromUntyped: delta(typed.total, rich.total), outputHash: typed.files[0].sha256 });
      }
    }
  }
  assert.equal(typedRows[0].erasedJavascript.sha256, typedRows[1].erasedJavascript.sha256, "Adding message types must add zero runtime code or catalogs");

  const splitting = [];
  for (const engine of ["mf2", "formatjs"]) {
    const modules = { "runtime.js": reactSource(engine, true) };
    const loaders = [];
    for (const locale of locales) for (const resource of resourceNames) {
      const key = `${locale}/${resource}`;
      modules[`${key}.json`] = JSON.stringify(fullCatalogs[locale][engine][resource]);
      loaders.push(`${JSON.stringify(key)}: () => import(${JSON.stringify(`bench:${key}.json`)}).then(module => module.default)`);
    }
    const lazy = await bundle(`catalog-loading/lazy/${engine}`, `export { App } from "bench:runtime.js";
const loaders = { ${loaders.join(",")} };
export function load(locale, resource) { return loaders[locale + "/" + resource](); }`, modules, true);
    modules["all.json"] = JSON.stringify(Object.fromEntries(locales.map(locale => [locale, fullCatalogs[locale][engine]])));
    const eager = await bundle(`catalog-loading/eager/${engine}`, `export { App } from "bench:runtime.js";
import catalogs from "bench:all.json";
export async function load(locale, resource) { return catalogs[locale][resource]; }`, modules, true);
    const chunks = lazy.files.filter(file => !lazy.initialFiles.includes(file.name));
    assert.equal(chunks.length, locales.length * resourceNames.length, "Each locale/resource must be independently loadable");
    assert(!lazy.graph.some(row => row.path.endsWith("all.json")), "Lazy build must not retain a monolithic catalog");
    const jsonChunks = locales.flatMap(locale => resourceNames.map(resource => ({ locale, resource, ...jsonSizes(fullCatalogs[locale][engine][resource]) })));
    splitting.push({
      engine, locales: locales.length, resourcesPerLocale: resourceNames.length, messagesPerResource: 100,
      eager: eager.total, lazyInitial: lazy.initial, lazyAll: lazy.total, lazyDeferred: lazy.deferred,
      chunks: chunks.length, smallestChunk: Math.min(...chunks.map(file => file.gzip)), largestChunk: Math.max(...chunks.map(file => file.gzip)), compressedChunkUnit: "gzip bytes",
      staticJson: { files: jsonChunks, total: sumSizes(jsonChunks), oneLocale: sumSizes(jsonChunks.filter(file => file.locale === "en")) },
      note: "Sizes sum separately compressed files. Initial excludes deferred catalogs; one current resource and any source-locale fallback must also be fetched. Static JSON costs exclude application fetch/validation code; dynamic-import chunks include esbuild JSON module exports.",
    });
  }
  const finalSourceHashes = await fingerprint();
  const sourceChangedDuringMeasurement = JSON.stringify(sourceHashes) !== JSON.stringify(finalSourceHashes);
  report = {
    schemaVersion: 1,
    revision: execFileSync("git", ["rev-parse", "HEAD"], { cwd: repository, encoding: "utf8" }).trim(),
    sourceHashes, finalSourceHashes, sourceChangedDuringMeasurement,
    environment: { node: process.version, platform: process.platform, arch: process.arch, zlib: process.versions.zlib, brotli: process.versions.brotli },
    versions: { esbuild: esbuildVersion, react: await packageVersion("react"), reactIntl: await packageVersion("react-intl"), intlMessageformat: await packageVersion("intl-messageformat"), formatjsParser: await packageVersion("@formatjs/icu-messageformat-parser") },
    settings: Object.fromEntries(Object.entries(settings).filter(([key]) => key !== "absWorkingDir")),
    compression: { gzipLevel: 9, brotliQuality: 11, unit: "bytes", licenseCommentsExcludedForBoth: true },
    references: ["https://formatjs.github.io/docs/guides/advanced-usage/"],
    artifacts, comparisons, catalogRows, typedRows, splitting,
    verification: { equivalentCatalogOutputs, flattenMarkupForOutputComparison: true, bidiIsolation: "none", argumentCases: [0, 1, 2] },
    limitations: [
      "Results measure production ESM delivery sizes, not browser parse/execute time, network latency, memory, or complete semantic parity.",
      "React and React DOM are external for both systems in the primary comparisons. Supplemental react-rich-with-react rows bundle one deduplicated React18.3.1 instance; React DOM is unused by these render components. Native Intl and polyfills are never bundled.",
      "FormatJS receives precompiled ASTs and aliases its parser to no-parser.js. MF2 receives precompiled models through its formatter/React entrypoints.",
      "Core-string rows compare the general core formatter plus Intl adapter with intl-messageformat; provider rows include each library's actual retained provider surface.",
      "Synthetic catalogs contain eight message shapes and repeated product phrases. Compression and AST overhead depend heavily on real content, locale, and message mix; these sizes are not production-corpus claims.",
      "Arabic fixture plural branches are deliberately limited to one/other for paired syntax-size comparison; this is not a completeness or translation-quality test.",
      "Catalog splitting uses app-owned dynamic import maps; it is not a built-in network loader. Importing a runtime API does not prune keys in a dynamically indexed catalog.",
      "Type declarations are erased. Generated binding facades have a small fixed runtime cost independent of catalog size; message data is measured separately.",
      "Direct TypeScript builds can produce different minified identifiers and compressed bytes even when type erasure produces identical JavaScript. Compare erased code and retained graphs as well as hashes.",
      "Legal comments are removed for a consistent payload comparison. Distributions still need to satisfy their dependencies' license-notice requirements.",
    ],
  };
  if (sourceChangedDuringMeasurement) process.exitCode = 1;
} finally {
  await releaseLock();
}
const serialized = JSON.stringify(report, null, 2) + "\n";
if (output) await writeFile(output, serialized);
else process.stdout.write(serialized);
for (const row of report.artifacts.filter(row => !row.name.startsWith("catalog-loading/"))) console.error(`${row.name}: ${row.total.bytes} minified, ${row.total.gzip} gzip, ${row.total.brotli} Brotli bytes`);
if (report.sourceChangedDuringMeasurement) console.error("Source changed during measurement; rerun on a stable revision.");
