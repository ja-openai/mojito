import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";

import { parseToModel } from "../src/index.js";

const fixtureDir = process.argv[2] ?? "../conformance/fixtures/source-to-model";
const iterations = Number(process.argv[3] ?? 20_000);
const warmupIterations = Number(process.argv[4] ?? 2_000);
if (!Number.isSafeInteger(iterations) || iterations <= 0 || !Number.isSafeInteger(warmupIterations) || warmupIterations < 0) throw new Error("Iterations must be positive and warmup must be non-negative integers.");

const sources = [];
for (const file of (await readdir(fixtureDir)).sort()) {
  if (!file.endsWith(".json")) continue;
  const fixture = JSON.parse(await readFile(join(fixtureDir, file), "utf8"));
  const parsed = parseToModel(fixture.source);
  if (parsed.hasDiagnostics !== Boolean(fixture.expectedDiagnostics?.length)) throw new Error(`${file}: unexpected parse result in benchmark preflight`);
  sources.push(fixture.source);
}

if (sources.length === 0) throw new Error("No source fixtures found.");

for (let index = 0; index < warmupIterations; index += 1) {
  parseToModel(sources[index % sources.length]);
}
const memoryBefore = process.memoryUsage().rss;
const cpuBefore = process.cpuUsage();
const timeBefore = process.hrtime.bigint();
let parsedCount = 0;
let diagnosticCount = 0;
let byteCount = 0;
for (let index = 0; index < iterations; index += 1) {
  const source = sources[index % sources.length];
  const result = parseToModel(source);
  parsedCount += result.model == null ? 0 : 1;
  diagnosticCount += result.diagnostics.length;
  byteCount += Buffer.byteLength(source, "utf8");
}
const elapsedNs = Number(process.hrtime.bigint() - timeBefore);
const cpu = process.cpuUsage(cpuBefore);
const memoryAfter = process.memoryUsage().rss;
const seconds = elapsedNs / 1_000_000_000;
console.log(
  `javascript parse iterations=${iterations} warmup=${warmupIterations} sources=${sources.length} ` +
    `seconds=${seconds.toFixed(6)} ops_per_second=${Math.round(iterations / seconds)} ns_per_op=${(elapsedNs / iterations).toFixed(1)} ` +
    `cpu_ms=${((cpu.user + cpu.system) / 1000).toFixed(1)} ` +
    `rss_delta_kb=${Math.round((memoryAfter - memoryBefore) / 1024)} parsed=${parsedCount} diagnostics=${diagnosticCount} bytes=${byteCount}`,
);
