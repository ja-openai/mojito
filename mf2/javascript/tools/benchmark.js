import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";

import { formatMessage, parseToModel } from "../src/index.js";

const fixtureDir = process.argv[2] ?? "../conformance/fixtures/source-to-model";
const iterations = Number(process.argv[3] ?? 100_000);
const warmupIterations = Number(process.argv[4] ?? 10_000);
if (!Number.isSafeInteger(iterations) || iterations <= 0 || !Number.isSafeInteger(warmupIterations) || warmupIterations < 0) throw new Error("Iterations must be positive and warmup must be non-negative integers.");

const cases = [];
for (const file of (await readdir(fixtureDir)).sort()) {
  if (!file.endsWith(".json")) continue;
  const fixture = JSON.parse(await readFile(join(fixtureDir, file), "utf8"));
  const parsed = parseToModel(fixture.source);
  if (parsed.hasDiagnostics) throw new Error(`${file}: parser diagnostics ${JSON.stringify(parsed.diagnostics)}`);
  for (const formatCase of fixture.formatCases ?? []) {
    const actual = formatMessage(parsed.model, formatCase.arguments ?? {}, { locale: formatCase.locale ?? "en", bidiIsolation: formatCase.bidiIsolation ?? "none" });
    if (actual.value !== formatCase.expected || actual.errors.length) throw new Error(`${file}: unexpected format result in benchmark preflight`);
    cases.push({
      model: parsed.model,
      arguments: formatCase.arguments ?? {},
      locale: formatCase.locale ?? "en",
      bidiIsolation: formatCase.bidiIsolation ?? "none",
    });
  }
}

if (cases.length === 0) throw new Error("No format cases found.");

for (let index = 0; index < warmupIterations; index += 1) {
  const item = cases[index % cases.length];
  formatMessage(item.model, item.arguments, {
    locale: item.locale,
    bidiIsolation: item.bidiIsolation,
  });
}
const memoryBefore = process.memoryUsage().rss;
const cpuBefore = process.cpuUsage();
const timeBefore = process.hrtime.bigint();
let checksum = 0;
for (let index = 0; index < iterations; index += 1) {
  const item = cases[index % cases.length];
  checksum += Buffer.byteLength(formatMessage(item.model, item.arguments, {
    locale: item.locale,
    bidiIsolation: item.bidiIsolation,
  }).value, "utf8");
}
const elapsedNs = Number(process.hrtime.bigint() - timeBefore);
const cpu = process.cpuUsage(cpuBefore);
const memoryAfter = process.memoryUsage().rss;
const seconds = elapsedNs / 1_000_000_000;
console.log(
  `javascript format iterations=${iterations} warmup=${warmupIterations} cases=${cases.length} ` +
    `seconds=${seconds.toFixed(6)} ops_per_second=${Math.round(iterations / seconds)} ns_per_op=${(elapsedNs / iterations).toFixed(1)} ` +
    `cpu_ms=${((cpu.user + cpu.system) / 1000).toFixed(1)} ` +
    `rss_delta_kb=${Math.round((memoryAfter - memoryBefore) / 1024)} checksum=${checksum}`,
);
