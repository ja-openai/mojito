import { selectCardinal, selectOrdinal } from "./generated_plural_rules.js";

const iterations = Number(process.argv[2] ?? 500_000);
const warmupIterations = Number(process.argv[3] ?? 50_000);
const samples = [
  ["en", "cardinal", 0],
  ["en", "cardinal", 1],
  ["en", "ordinal", 22],
  ["fr-CA", "cardinal", 1.5],
  ["pt-AO", "cardinal", 0],
  ["pt-PT", "cardinal", 0],
  ["ru", "cardinal", 1],
  ["ru", "cardinal", 2],
  ["ru", "cardinal", 1.5],
  ["ar", "cardinal", 0],
  ["ar", "cardinal", 3],
  ["ar", "cardinal", 11],
  ["ja", "cardinal", 5],
];

const intlCache = new Map();

for (const sample of samples) {
  const generated = generatedSelect(...sample);
  const intl = intlSelect(...sample);
  if (generated !== intl) {
    console.log(`plural mismatch ${sample.join("/")} generated=${generated} intl=${intl}`);
  }
}

runBench("generated", generatedSelect);
runBench("intl-cached", intlSelect);

function runBench(label, select) {
  for (let index = 0; index < warmupIterations; index += 1) {
    const sample = samples[index % samples.length];
    select(...sample);
  }
  globalThis.gc?.();
  const memoryBefore = process.memoryUsage();
  const cpuBefore = process.cpuUsage();
  const timeBefore = process.hrtime.bigint();
  let checksum = 0;
  for (let index = 0; index < iterations; index += 1) {
    const sample = samples[index % samples.length];
    checksum += select(...sample).length;
  }
  const elapsedNs = Number(process.hrtime.bigint() - timeBefore);
  const cpu = process.cpuUsage(cpuBefore);
  globalThis.gc?.();
  const memoryAfter = process.memoryUsage();
  console.log(
    `${label}: iterations=${iterations} warmup=${warmupIterations} ns/op=${(elapsedNs / iterations).toFixed(1)} ` +
      `cpu_ms=${((cpu.user + cpu.system) / 1000).toFixed(1)} ` +
      `rss_delta_kb=${Math.round((memoryAfter.rss - memoryBefore.rss) / 1024)} ` +
      `heap_delta_kb=${Math.round((memoryAfter.heapUsed - memoryBefore.heapUsed) / 1024)} checksum=${checksum}`,
  );
}

function generatedSelect(locale, type, value) {
  return type === "ordinal" ? selectOrdinal(locale, value) : selectCardinal(locale, value);
}

function intlSelect(locale, type, value) {
  const key = `${locale}/${type}`;
  let rules = intlCache.get(key);
  if (!rules) {
    rules = new Intl.PluralRules(locale.replaceAll("_", "-"), {
      type: type === "ordinal" ? "ordinal" : "cardinal",
    });
    intlCache.set(key, rules);
  }
  return rules.select(Number(value));
}
