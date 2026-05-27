import { MessageFormat, parseMessage } from "messageformat";

import { formatMessage, parseToModel } from "@mojito-mf2/core";

const iterations = Number(process.argv[2] ?? 200_000);
const warmupIterations = Number(process.argv[3] ?? 20_000);

const cases = [
  {
    name: "placeholder",
    source: "Hello {$name}",
    arguments: { name: "Jean" },
  },
  {
    name: "plural-en",
    source: `.input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}`,
    arguments: { count: 2 },
  },
  {
    name: "markup-string",
    source: "Tap {#link href=$url}profile{/link}. {$name}",
    arguments: { name: "Jean", url: "/people/jean" },
  },
  {
    name: "multi-selector",
    source: `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
female one {{She reviewed {$count} file}}
male * {{He reviewed {$count} files}}
female * {{She reviewed {$count} files}}
* * {{They reviewed {$count} files}}`,
    arguments: { gender: "female", count: 3 },
  },
];

const ours = cases.map((item) => ({
  ...item,
  model: parseToModel(item.source).model,
}));
const reference = cases.map((item) => ({
  ...item,
  formatter: new MessageFormat("en", item.source, { bidiIsolation: "none" }),
}));

for (let index = 0; index < cases.length; index += 1) {
  const ourOutput = formatMessage(ours[index].model, ours[index].arguments, {
    locale: "en",
    bidiIsolation: "none",
  });
  const referenceOutput = reference[index].formatter.format(reference[index].arguments);
  console.log(`${cases[index].name}: ours=${JSON.stringify(ourOutput)} messageformat=${JSON.stringify(referenceOutput)}`);
}

for (const item of ours) {
  console.log(formatResult(`ours:${item.name}`, bench(() => formatMessage(item.model, item.arguments, { locale: "en", bidiIsolation: "none" }))));
  const referenceItem = reference.find((candidate) => candidate.name === item.name);
  console.log(formatResult(`messageformat:${item.name}`, bench(() => referenceItem.formatter.format(referenceItem.arguments))));
}

console.log(formatResult("ours:corpus", bench((index) => {
  const item = ours[index % ours.length];
  return formatMessage(item.model, item.arguments, { locale: "en", bidiIsolation: "none" });
})));
console.log(formatResult("messageformat:corpus", bench((index) => {
  const item = reference[index % reference.length];
  return item.formatter.format(item.arguments);
})));

console.log(formatResult("ours:parse", bench((index) => parseToModel(cases[index % cases.length].source).model?.type, 50_000, 5_000)));
console.log(formatResult("messageformat:parse", bench((index) => parseMessage(cases[index % cases.length].source).type, 50_000, 5_000)));

function bench(fn, iterationCount = iterations, warmupCount = warmupIterations) {
  for (let index = 0; index < warmupCount; index += 1) fn(index);
  globalThis.gc?.();
  const memoryBefore = process.memoryUsage();
  const cpuBefore = process.cpuUsage();
  const started = process.hrtime.bigint();
  let checksum = 0;
  for (let index = 0; index < iterationCount; index += 1) {
    checksum += String(fn(index)).length;
  }
  const elapsedNs = Number(process.hrtime.bigint() - started);
  const cpu = process.cpuUsage(cpuBefore);
  globalThis.gc?.();
  const memoryAfter = process.memoryUsage();
  return {
    iterations: iterationCount,
    nsPerOp: elapsedNs / iterationCount,
    opsPerSecond: iterationCount / (elapsedNs / 1_000_000_000),
    cpuMs: (cpu.user + cpu.system) / 1000,
    heapDeltaKb: Math.round((memoryAfter.heapUsed - memoryBefore.heapUsed) / 1024),
    rssDeltaKb: Math.round((memoryAfter.rss - memoryBefore.rss) / 1024),
    checksum,
  };
}

function formatResult(label, result) {
  return (
    `${label} iterations=${result.iterations} ` +
    `ops_per_second=${Math.round(result.opsPerSecond)} ` +
    `ns_per_op=${result.nsPerOp.toFixed(1)} ` +
    `cpu_ms=${result.cpuMs.toFixed(1)} ` +
    `heap_delta_kb=${result.heapDeltaKb} ` +
    `rss_delta_kb=${result.rssDeltaKb} checksum=${result.checksum}`
  );
}
