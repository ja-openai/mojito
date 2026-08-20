#!/usr/bin/env node

import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const manifest = JSON.parse(readFileSync(path.join(root, "manifest.json"), "utf8"));
let checked = 0;

for (const fixture of manifest.workflowCases) {
  const policy = fixture.javascriptRuntime;
  if (!policy) {
    continue;
  }
  for (const [name, value] of Object.entries(policy.globals)) {
    globalThis[name] = value;
  }
  const resource = pathToFileURL(path.join(root, fixture.localized));
  resource.searchParams.set("case", fixture.id);
  const module = await import(resource);
  const exported = module[policy.export];
  for (const [name, expected] of Object.entries(policy.expected)) {
    if (exported[name] !== expected) {
      throw new Error(
        `${fixture.id}/${name}: expected ${JSON.stringify(expected)}, ` +
          `got ${JSON.stringify(exported[name])}`,
      );
    }
    checked++;
  }
  for (const [name, expected] of Object.entries(policy.globalsAfter)) {
    if (globalThis[name] !== expected) {
      throw new Error(
        `${fixture.id}: translated template executed ${name}; ` +
          `expected ${JSON.stringify(expected)}, got ${JSON.stringify(globalThis[name])}`,
      );
    }
  }
}

if (checked === 0) {
  throw new Error("No JavaScript runtime cases were declared");
}
console.log(`Node verified ${checked} localized JavaScript runtime values.`);
