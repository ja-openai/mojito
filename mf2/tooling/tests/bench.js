import { performance } from "node:perf_hooks";
import { gzipSync } from "node:zlib";
import { readFileSync } from "node:fs";
import {
  analyzeMessage,
  checkContracts,
  checkCatalog,
  compileCatalog,
  generateTypes,
} from "../src/index.js";

const message =
  ".input {$count :number}\n.match $count\none {{Read {#link}one item{/link}.}}\n* {{Read {#link}{$count} items{/link}.}}";
const translated =
  ".input {$count :number}\n.match $count\none {{Lire {#link}un article{/link}.}}\n* {{Lire {#link}{$count} articles{/link}.}}";
const source = Object.fromEntries(
  Array.from({ length: 1000 }, (_, index) => [`message.${index}`, message]),
);
const target = Object.fromEntries(
  Array.from({ length: 1000 }, (_, index) => [`message.${index}`, translated]),
);
function median(operation) {
  operation();
  const samples = [];
  for (let repeat = 0; repeat < 7; repeat++) {
    const start = performance.now();
    const result = operation();
    if (!result.ok) throw new Error(JSON.stringify(result.diagnostics));
    samples.push(performance.now() - start);
  }
  return Number(samples.sort((a, b) => a - b)[3].toFixed(2));
}
const sourceContract = analyzeMessage(message).contract;
const targetContract = analyzeMessage(translated).contract;
const compiled = compileCatalog(source).artifact;
const sourceJSON = JSON.stringify(source);
const astJSON = JSON.stringify(compiled);
const before = global.gc ? (global.gc(), process.memoryUsage().heapUsed) : null;
for (let cycle = 0; cycle < 50; cycle++) compileCatalog(source);
const after = global.gc ? (global.gc(), process.memoryUsage().heapUsed) : null;
const binding = readFileSync(
  new URL("../../react/src/bindings.js", import.meta.url),
);
console.log(
  JSON.stringify(
    {
      node: process.version,
      messages: 1000,
      median_ms: {
        compile: median(() => compileCatalog(source)),
        check_translation: median(() =>
          checkCatalog(source, target, { targetLocale: "fr" }),
        ),
        generate_types: median(() =>
          generateTypes(source, { resource: "messages" }),
        ),
        compare_cached_contracts_1000: median(() => {
          let result;
          for (let index = 0; index < 1000; index++)
            result = checkContracts(sourceContract, targetContract, {
              targetLocale: "fr",
            });
          return result;
        }),
      },
      bytes: {
        source: Buffer.byteLength(sourceJSON),
        ast: Buffer.byteLength(astJSON),
        source_gzip: gzipSync(sourceJSON).length,
        ast_gzip: gzipSync(astJSON).length,
        binding_source: binding.length,
        binding_source_gzip: gzipSync(binding).length,
      },
      heap_retained_after_50_compile_cycles_bytes:
        before === null ? null : after - before,
      caveats:
        "Single-process synthetic workload; GC deltas are noisy, not a leak proof. Binding source size is not a bundled-size claim. No retained caches exist in this package.",
    },
    null,
    2,
  ),
);
