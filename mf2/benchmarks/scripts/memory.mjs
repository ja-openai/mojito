import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir, writeFile } from "node:fs/promises";
import { setImmediate as nextTurn } from "node:timers/promises";
import React from "react";
import TestRenderer from "react-test-renderer";
import { createIntl, createIntlCache } from "react-intl";
import { FunctionRegistry, formatMessage } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
import { parseToModel } from "@mojito-mf2/core/parser";
import { createMF2, MF2Provider } from "@mojito-mf2/react";
import { createMessages } from "@mojito-mf2/react/bindings";
import { checkCatalog, compileCatalog } from "../../tooling/src/index.js";
import { acquireLock } from "./helpers.mjs";

// Run via `npm run memory -- --output /path/to/results.json`. The package's
// module-resolution hook selects one React installation and current MF2 source.
// This is a bounded, synthetic retention probe, not an automated leak verdict.
if (typeof globalThis.gc !== "function")
  throw new Error("Run this benchmark with --expose-gc.");

const outputIndex = process.argv.indexOf("--output");
const outputPath = outputIndex < 0 ? null : process.argv[outputIndex + 1];
if (outputIndex >= 0 && (!outputPath || outputPath.startsWith("--")))
  throw new Error("--output requires a filename.");

const fields = ["heapUsed", "heapTotal", "external", "arrayBuffers", "rss"];
const snapshot = () => {
  const usage = process.memoryUsage();
  return Object.fromEntries(fields.map((field) => [field, usage[field]]));
};
const delta = (before, after) =>
  Object.fromEntries(
    fields.map((field) => [field, after[field] - before[field]]),
  );
const alive = (refs) => refs.filter((ref) => ref.deref() !== undefined).length;
const locales = [
  "en",
  "fr",
  "de",
  "es",
  "ja",
  "ar",
  "hi",
  "ko",
  "it",
  "pt",
  "nl",
  "sv",
];

async function collect() {
  // WeakRef targets are kept alive through the current job after construction
  // or deref(). Cross event-loop turns before collecting/counting them again.
  for (let index = 0; index < 4; index++) {
    await nextTurn();
    globalThis.gc();
  }
  return snapshot();
}

function model(source) {
  const result = parseToModel(source);
  assert.deepEqual(result.diagnostics, []);
  return result.model;
}

function sourceCatalog(count, revision = 0) {
  return Object.fromEntries(
    Array.from({ length: count }, (_, index) => [
      `message.${index}`,
      `.input {$count :number}\n.match $count\none {{Read {#link}one item ${revision}-${index}{/link}.}}\n* {{Read {#link}{$count} items ${revision}-${index}{/link}.}}`,
    ]),
  );
}

function compiledCatalog(count, revision = 0) {
  const result = compileCatalog(sourceCatalog(count, revision));
  assert.equal(result.ok, true, JSON.stringify(result.diagnostics));
  return result.artifact.messages;
}

function resourcesFor(count, revision = 0) {
  const messages = compiledCatalog(count, revision);
  return { en: { guide: messages }, fr: { guide: messages } };
}

function trackNumberFormats(operation) {
  const NativeNumberFormat = Intl.NumberFormat;
  const refs = [];
  function TrackedNumberFormat(...args) {
    const instance = new NativeNumberFormat(...args);
    refs.push(new WeakRef(instance));
    return instance;
  }
  Object.setPrototypeOf(TrackedNumberFormat, NativeNumberFormat);
  TrackedNumberFormat.prototype = NativeNumberFormat.prototype;
  Intl.NumberFormat = TrackedNumberFormat;
  try {
    operation();
  } finally {
    Intl.NumberFormat = NativeNumberFormat;
  }
  return refs;
}

async function cacheRetention(capacity) {
  let registry = createIntlFunctionRegistry(FunctionRegistry, {
    formatterCacheSize: capacity,
  });
  const message = model("{$value :number maximumFractionDigits=$digits}");
  const refs = trackNumberFormats(() => {
    for (const locale of locales) {
      for (let digits = 0; digits < 16; digits++) {
        const result = formatMessage(
          message,
          { value: 1234.5678, digits },
          {
            locale,
            functions: registry,
          },
        );
        assert.equal(result.hasErrors, false);
      }
    }
  });
  const whileRegistryHeld = await collect();
  const heldCount = alive(refs);
  // Keep the owner observably live through collection even under JIT liveness
  // analysis. Reuse the most recent configuration without growing the cache.
  assert.equal(
    formatMessage(
      message,
      { value: 2, digits: 15 },
      {
        locale: locales.at(-1),
        functions: registry,
      },
    ).hasErrors,
    false,
  );
  registry = null;
  const afterRegistryReleased = await collect();
  return {
    capacity,
    uniqueConfigurations: locales.length * 16,
    constructed: refs.length,
    liveWhileRegistryHeld: heldCount,
    expectedMaximumWhileHeld: capacity,
    liveAfterRegistryReleased: alive(refs),
    whileRegistryHeld,
    afterRegistryReleased,
  };
}

async function formatjsCacheRetention() {
  let cache = createIntlCache();
  const refs = trackNumberFormats(() => {
    for (const locale of locales) {
      const intl = createIntl(
        {
          locale,
          defaultLocale: "en",
          messages: {},
          onError: (error) => {
            throw error;
          },
        },
        cache,
      );
      for (let digits = 0; digits < 16; digits++) {
        assert.equal(
          typeof intl.formatNumber(1234.5678, {
            maximumFractionDigits: digits,
          }),
          "string",
        );
      }
    }
  });
  const whileCacheHeld = await collect();
  const heldCount = alive(refs);
  // This public cache is intentionally shared across locales, matching MF2's
  // registry probe. Cache policies differ; do not call this a leak comparison.
  const cacheEntries = Object.keys(cache.number).length;
  cache = null;
  const afterCacheReleased = await collect();
  return {
    uniqueConfigurations: locales.length * 16,
    constructed: refs.length,
    cacheEntries,
    liveWhileCacheHeld: heldCount,
    liveAfterCacheReleased: alive(refs),
    whileCacheHeld,
    afterCacheReleased,
    note: "React Intl default shared createIntlCache retains each distinct configuration until the cache is discarded. MF2 uses a configurable per-registry LRU; eviction trades memory for future constructor work.",
  };
}

async function registryInputRetention() {
  const registry = createIntlFunctionRegistry(FunctionRegistry);
  const refs = { models: [], arguments: [], dates: [] };
  function formatTransient(index) {
    const message = model(`Sample ${index}: {$value :number}`);
    const date = new Date("2024-06-01T00:00:00Z");
    const args = { value: index + 0.5, unusedDate: date };
    refs.models.push(new WeakRef(message));
    refs.arguments.push(new WeakRef(args));
    refs.dates.push(new WeakRef(date));
    assert.equal(
      formatMessage(message, args, {
        locale: "en",
        functions: registry,
      }).hasErrors,
      false,
    );
  }
  for (let index = 0; index < 256; index++) formatTransient(index);
  await collect();
  const live = Object.fromEntries(
    Object.entries(refs).map(([key, values]) => [key, alive(values)]),
  );
  // A use after collection ensures the intended owner really remains reachable.
  assert.equal(
    formatMessage(
      model("{$n :number}"),
      { n: 1 },
      {
        locale: "en",
        functions: registry,
      },
    ).hasErrors,
    false,
  );
  return { operations: 256, liveWithRegistryHeld: live };
}

async function typedFacadeRetention() {
  const refs = {};
  let formatter;
  function create() {
    const resources = resourcesFor(1000);
    refs.resources = new WeakRef(resources);
    refs.catalog = new WeakRef(resources.en.guide);
    formatter = createMF2({ resources, locale: "en", bidiIsolation: "none" });
    return createMessages("guide").bind(formatter);
  }
  let bound = create();
  formatter = null;
  const whileBoundHeld = await collect();
  assert.equal(typeof bound.format("message.0", { count: 2 }), "string");
  const held = Object.fromEntries(
    Object.entries(refs).map(([key, value]) => [key, alive([value])]),
  );
  bound = null;
  const afterBoundReleased = await collect();
  return {
    messages: 1000,
    liveWhileBoundHeld: held,
    liveAfterBoundReleased: Object.fromEntries(
      Object.entries(refs).map(([key, value]) => [key, alive([value])]),
    ),
    releasedBytes: delta(whileBoundHeld, afterBoundReleased),
    note: "A retained formatting facade intentionally owns its formatter and catalogs. Release old facades when discarding a locale/catalog.",
  };
}

async function providerRetention() {
  const typed = createMessages("guide");
  const refs = { resources: [], catalogs: [], typedHooks: [] };
  let renderer = null;
  function View({ count }) {
    const formatter = typed.useMF2();
    refs.typedHooks.push(new WeakRef(formatter));
    return React.createElement(
      "span",
      null,
      formatter.format("message.0", { count }),
    );
  }
  function swap(revision) {
    const resources = resourcesFor(64, revision);
    refs.resources.push(new WeakRef(resources));
    refs.catalogs.push(new WeakRef(resources.en.guide));
    const tree = React.createElement(
      MF2Provider,
      {
        resources,
        locale: revision % 2 ? "fr" : "en",
        bidiIsolation: "none",
      },
      React.createElement(View, { count: revision + 1 }),
    );
    if (renderer) renderer.update(tree);
    else renderer = TestRenderer.create(tree);
    assert.equal(renderer.toJSON().type, "span");
  }
  function live() {
    return Object.fromEntries(
      Object.entries(refs).map(([key, values]) => [key, alive(values)]),
    );
  }
  for (let index = 0; index < 32; index++) swap(index);
  const baseline = await collect();
  const samples = [];
  for (let batch = 0; batch < 5; batch++) {
    for (let index = 0; index < 32; index++) swap(32 + batch * 32 + index);
    const memory = await collect();
    samples.push({
      totalSwaps: 64 + batch * 32,
      memory,
      changeFromWarmBaseline: delta(baseline, memory),
      live: live(),
    });
  }
  renderer.unmount();
  renderer = null;
  const afterUnmount = await collect();
  const liveAfterUnmount = live();
  // Also exercise new provider instances, not only updates of one mounted tree.
  const mountBaseline = await collect();
  for (let index = 0; index < 128; index++) {
    swap(1000 + index);
    renderer.unmount();
    renderer = null;
  }
  const afterMountCycles = await collect();
  return {
    messagesPerCatalog: 64,
    baseline,
    samples,
    afterUnmount,
    liveAfterUnmount,
    mountUnmountCycles: 128,
    afterMountCycles,
    mountCycleChange: delta(mountBaseline, afterMountCycles),
    liveAfterMountCycles: live(),
    note: "React may retain a current and alternate fiber while mounted. Unmount and a later event-loop turn allow passive cleanup. Test renderer is not a browser DOM.",
  };
}

async function compileRetention() {
  const source = sourceCatalog(1000);
  const refs = [];
  function compile() {
    const result = compileCatalog(source);
    assert.equal(result.ok, true, JSON.stringify(result.diagnostics));
    refs.push(new WeakRef(result.artifact), new WeakRef(result.contracts));
  }
  for (let index = 0; index < 5; index++) compile();
  const baseline = await collect();
  const samples = [];
  for (let batch = 0; batch < 5; batch++) {
    for (let index = 0; index < 10; index++) compile();
    const memory = await collect();
    samples.push({
      compilesAfterWarmup: (batch + 1) * 10,
      memory,
      changeFromWarmBaseline: delta(baseline, memory),
      liveArtifactsAndContracts: alive(refs),
    });
  }
  return { messagesPerCatalog: 1000, warmupCompiles: 5, baseline, samples };
}

async function catalogScaling(count) {
  const baseline = await collect();
  let source = sourceCatalog(count);
  const sourceBytes = Buffer.byteLength(JSON.stringify(source));
  const withSource = await collect();
  let result = compileCatalog(source);
  assert.equal(result.ok, true, JSON.stringify(result.diagnostics));
  const refs = [
    new WeakRef(source),
    new WeakRef(result.artifact),
    new WeakRef(result.contracts),
  ];
  const compiledBytes = Buffer.byteLength(JSON.stringify(result.artifact));
  const contractBytes = Buffer.byteLength(JSON.stringify(result.contracts));
  const withCompiledModelsAndContracts = await collect();
  const validation = checkCatalog(source, source, { targetLocale: "en" });
  assert.equal(validation.ok, true, JSON.stringify(validation.diagnostics));
  const afterValidation = await collect();
  let runtimeMessages = result.artifact.messages;
  result = null;
  const withRuntimeMessages = await collect();
  assert.equal(Object.keys(runtimeMessages).length, count);
  source = null;
  runtimeMessages = null;
  const afterRelease = await collect();
  return {
    messages: count,
    serializedBytes: {
      source: sourceBytes,
      compiled: compiledBytes,
      contracts: contractBytes,
    },
    baseline,
    withSource,
    withCompiledModelsAndContracts,
    afterValidation,
    withRuntimeMessages,
    afterRelease,
    retainedModelsAndContracts: delta(
      withSource,
      withCompiledModelsAndContracts,
    ),
    retainedRuntimeMessages: delta(withSource, withRuntimeMessages),
    changeAfterRelease: delta(baseline, afterRelease),
    liveAfterRelease: alive(refs),
  };
}

async function fingerprints() {
  const files = [
    "benchmarks/scripts/memory.mjs",
    "benchmarks/scripts/helpers.mjs",
    "benchmarks/scripts/register-dependencies.mjs",
  ];
  for (const directory of ["javascript/src", "react/src", "tooling/src"]) {
    for (const file of await readdir(
      new URL(`../../${directory}`, import.meta.url),
    )) {
      if (file.endsWith(".js")) files.push(`${directory}/${file}`);
    }
  }
  files.sort();
  return Object.fromEntries(
    await Promise.all(
      files.map(async (file) => [
        file,
        createHash("sha256")
          .update(await readFile(new URL(`../../${file}`, import.meta.url)))
          .digest("hex"),
      ]),
    ),
  );
}

const releaseLock = await acquireLock();
try {
  const sourceHashesBefore = await fingerprints();
  // Warm imports, ICU data, parser, contract analysis, and React's renderer before
  // comparing retained heaps. Each scenario also has its own warm baseline.
  for (let index = 0; index < 100; index++) {
    const formatter = createMF2({
      resources: resourcesFor(8, index),
      locale: "en",
    });
    formatter.format("guide", "message.0", { count: index });
  }
  const warmRenderer = TestRenderer.create(
    React.createElement("span", null, "Warmup"),
  );
  warmRenderer.unmount();
  await collect();
  const cache = [];
  for (const capacity of [0, 16, 64])
    cache.push(await cacheRetention(capacity));
  const formatjsCache = await formatjsCacheRetention();
  const registryInputs = await registryInputRetention();
  const typedFacade = await typedFacadeRetention();
  const provider = await providerRetention();
  const discardedCompiles = await compileRetention();
  const catalogs = [];
  for (const count of [100, 1000, 5000])
    catalogs.push(await catalogScaling(count));
  const sourceHashesAfter = await fingerprints();
  const report = {
    schemaVersion: 1,
    node: process.version,
    v8: process.versions.v8,
    icu: process.versions.icu,
    platform: process.platform,
    architecture: process.arch,
    reactMode: process.env.NODE_ENV ?? "development",
    sourceHashes: sourceHashesAfter,
    sourceUnchangedDuringRun:
      JSON.stringify(sourceHashesBefore) === JSON.stringify(sourceHashesAfter),
    units: "bytes, except counts",
    cache,
    formatjsCache,
    registryInputs,
    typedFacade,
    provider,
    discardedCompiles,
    catalogs,
    caveats: [
      "Synthetic single-process Node workload, with forced GC across event-loop turns. It cannot prove absence of leaks in a long-lived browser application.",
      "WeakRef live counts are observations, not a language-level guarantee about collection timing. Reference arrays themselves use a small, growing amount of heap.",
      "heapUsed reports V8 heap; external and arrayBuffers are separate counters (arrayBuffers is included in external). RSS includes native ICU, allocator arenas and executable pages, which need not shrink after GC.",
      "Retained-heap deltas exclude transient peak allocations. Small changes and negative deltas can be GC/JIT noise; the report gives raw samples rather than a leak/no-leak threshold.",
      "The 64-entry Intl cache is per registry. Many simultaneously retained registries multiply this budget; sharing a registry intentionally shares the cache.",
      "Compiled artifacts and translation contracts are measured together at build time. Only compiled messages are required for runtime delivery.",
    ],
  };
  const json = `${JSON.stringify(report, null, 2)}\n`;
  if (outputPath) await writeFile(outputPath, json);
  process.stdout.write(json);
} finally {
  await releaseLock();
}
