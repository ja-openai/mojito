#!/usr/bin/env node
import { performance } from "node:perf_hooks";
import { GrammarBundle } from "./grammar_fixture_runner.mjs";

const fixture =
  process.argv[2] ?? "dev-docs/experiments/mf2-grammar-agreement/fixtures/de/case-articles.json";
const iterations = Number(process.argv[3] ?? "100000");

const bundle = new GrammarBundle(fixture);
const messageId = "inventory.pickup";
const args = { item: "item.shield" };

let checksum = 0;
for (let i = 0; i < 1000; i += 1) {
  checksum += bundle.format(messageId, args).length;
}

const start = performance.now();
for (let i = 0; i < iterations; i += 1) {
  checksum += bundle.format(messageId, args).length;
}
const elapsedMs = performance.now() - start;

console.log(
  JSON.stringify(
    {
      runtime: "javascript-prototype-raw-mf2-term-matcher",
      fixture,
      messageId,
      iterations,
      elapsedMs: Number(elapsedMs.toFixed(3)),
      perOpUs: Number(((elapsedMs * 1000) / iterations).toFixed(3)),
      checksum,
    },
    null,
    2,
  ),
);
