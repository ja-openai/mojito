import assert from "node:assert/strict";

import {
  addPluralTemplate,
  formatMessage,
  parseSource,
  partsForPattern,
  printModel,
  samples,
} from "./editor.js";

const plural = parseSource(samples.plural);
assert.equal(plural.type, "select");
assert.deepEqual(plural.selectors, ["count"]);
assert.equal(formatMessage(plural, { count: 1 }, "en"), "You have 1 file");
assert.equal(formatMessage(plural, { count: 2 }, "en"), "You have 2 files");

const gender = parseSource(samples.gender);
assert.equal(formatMessage(gender, { gender: "female", count: 1 }, "en"), "She reviewed 1 file");
assert.equal(formatMessage(gender, { gender: "unknown", count: 5 }, "en"), "They reviewed 5 files");

const generated = parseSource(addPluralTemplate("Files: {$count}"));
assert.equal(generated.diagnostics.length, 0);
assert.equal(printModel(generated).includes(".match $count"), true);

const parts = partsForPattern("Tap {#link href=$url}profile{/link}. {$name}", {
  name: "Jean",
  url: "/people/jean",
});
assert.deepEqual(parts.map((part) => part.type), ["text", "markup", "text", "markup", "text", "expression"]);

const broken = parseSource(".input {$count :number}\n.match $count\none {{One");
assert.equal(broken.diagnostics.some((diagnostic) => diagnostic.code === "unclosed-placeholder"), true);

console.log("MF2 editor prototype smoke test passed");

