import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { FormattedMessage, MessageProvider, createMessageCatalog, useMessage } from "./index.js";

const sources = {
  welcome: "Welcome, {$name}!",
  cart: `.input {$count :number}
.match $count
one {{{$count} item in your cart}}
* {{{$count} items in your cart}}`,
  review: `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
female one {{She reviewed {$count} file}}
male * {{He reviewed {$count} files}}
female * {{She reviewed {$count} files}}
* * {{They reviewed {$count} files}}`,
  profile: "Tap {#link href=$url @title=|Profile page|}profile{/link}. {$name :string @kind=person}",
};

const iterations = Number(process.argv[2] ?? 20_000);
const warmupIterations = Number(process.argv[3] ?? 2_000);
const catalog = createMessageCatalog(sources);
const components = {
  link: ({ href, title, children }) => React.createElement("a", { href, title }, children),
};
const samples = [
  { locale: "en", count: 1, gender: "male" },
  { locale: "en", count: 7, gender: "female" },
  { locale: "ru", count: 2, gender: "unknown" },
  { locale: "ar", count: 3, gender: "unknown" },
];

function App({ sample }) {
  return React.createElement(
    MessageProvider,
    { catalog, locale: sample.locale, components },
    React.createElement(MessageSet, { sample }),
  );
}

function MessageSet({ sample }) {
  const welcome = useMessage("welcome", { name: "Mojito" });
  return React.createElement(
    React.Fragment,
    null,
    React.createElement("p", null, welcome),
    React.createElement("p", null, React.createElement(FormattedMessage, { id: "cart", values: { count: sample.count } })),
    React.createElement("p", null, React.createElement(FormattedMessage, { id: "review", values: { gender: sample.gender, count: sample.count } })),
    React.createElement("p", null, React.createElement(FormattedMessage, { id: "profile", values: { name: "Jean", url: "/people/jean" } })),
  );
}

for (let index = 0; index < warmupIterations; index += 1) {
  renderToStaticMarkup(React.createElement(App, { sample: samples[index % samples.length] }));
}

globalThis.gc?.();
const memoryBefore = process.memoryUsage();
const cpuBefore = process.cpuUsage();
const timeBefore = process.hrtime.bigint();
let checksum = 0;
for (let index = 0; index < iterations; index += 1) {
  checksum += renderToStaticMarkup(React.createElement(App, { sample: samples[index % samples.length] })).length;
}
const elapsedNs = Number(process.hrtime.bigint() - timeBefore);
const cpu = process.cpuUsage(cpuBefore);
globalThis.gc?.();
const memoryAfter = process.memoryUsage();
const seconds = elapsedNs / 1_000_000_000;
console.log(
  `react render iterations=${iterations} warmup=${warmupIterations} messages_per_render=4 ` +
    `seconds=${seconds.toFixed(6)} ops_per_second=${Math.round(iterations / seconds)} ns_per_op=${(elapsedNs / iterations).toFixed(1)} ` +
    `cpu_ms=${((cpu.user + cpu.system) / 1000).toFixed(1)} ` +
    `rss_delta_kb=${Math.round((memoryAfter.rss - memoryBefore.rss) / 1024)} ` +
    `heap_delta_kb=${Math.round((memoryAfter.heapUsed - memoryBefore.heapUsed) / 1024)} checksum=${checksum}`,
);
