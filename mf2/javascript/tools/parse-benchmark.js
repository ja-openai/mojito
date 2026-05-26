import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";

import { parseToModel } from "../src/index.js";

const fixtureDir = process.argv[2] ?? "../conformance/fixtures/source-to-model";
const iterations = Number(process.argv[3] ?? 20_000);
const warmupIterations = Number(process.argv[4] ?? 2_000);

const sources = [];
for (const file of await readdir(fixtureDir)) {
  if (!file.endsWith(".json")) continue;
  const fixture = JSON.parse(await readFile(join(fixtureDir, file), "utf8"));
  sources.push(fixture.source);
}

if (sources.length === 0) throw new Error("No source fixtures found.");

const memoryBefore = process.memoryUsage().rss;
for (let index = 0; index < warmupIterations; index += 1) {
  parseToModel(sources[index % sources.length]);
}
const cpuBefore = process.cpuUsage();
const timeBefore = process.hrtime.bigint();
let checksum = 0;
for (let index = 0; index < iterations; index += 1) {
  const result = parseToModel(sources[index % sources.length]);
  checksum += result.model == null ? result.diagnostics.length : JSON.stringify(result.model).length;
}
const elapsedNs = Number(process.hrtime.bigint() - timeBefore);
const cpu = process.cpuUsage(cpuBefore);
const memoryAfter = process.memoryUsage().rss;
const seconds = elapsedNs / 1_000_000_000;
console.log(
  `javascript parse iterations=${iterations} warmup=${warmupIterations} sources=${sources.length} ` +
    `seconds=${seconds.toFixed(6)} ops_per_second=${Math.round(iterations / seconds)} ns_per_op=${(elapsedNs / iterations).toFixed(1)} ` +
    `cpu_ms=${((cpu.user + cpu.system) / 1000).toFixed(1)} ` +
    `rss_delta_kb=${Math.round((memoryAfter - memoryBefore) / 1024)} checksum=${checksum}`,
);
