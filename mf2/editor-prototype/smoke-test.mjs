import assert from "node:assert/strict";

import {
  addPluralTemplate,
  createSourceHistory,
  formatMessage,
  parseSource,
  partsForPattern,
  printModel,
  samples,
  withSourceContractDiagnostics,
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

const sourcePlural = parseSource(samples.plural);
const targetWithNewPlaceholder = parseSource(`.input {$count :number}
.match $count
one {{You have {$count} file from {$name}}}
* {{You have {$count} files from {$name}}}`);
const validatedTarget = withSourceContractDiagnostics(targetWithNewPlaceholder, sourcePlural);
assert.equal(validatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), true);

const targetMissingPlaceholder = parseSource(`.input {$count :number}
.match $count
one {{One file}}
* {{Many files}}`);
const missingValidatedTarget = withSourceContractDiagnostics(targetMissingPlaceholder, sourcePlural);
assert.equal(missingValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), true);
assert.equal(missingValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "variant-missing-placeholder"), true);

const sourceMarkupOptionModel = {
  type: "message",
  diagnostics: [],
  rustModel: {
    type: "message",
    pattern: [{ type: "markup", kind: "open", name: "link", options: { href: { type: "variable", name: "url" } } }, "profile"],
  },
};
const targetMarkupOptionModel = {
  type: "message",
  diagnostics: [],
  rustModel: {
    type: "message",
    pattern: [{ type: "markup", kind: "open", name: "link", options: { href: { type: "variable", name: "trackingUrl" } } }, "profile"],
  },
};
const markupOptionValidatedTarget = withSourceContractDiagnostics(targetMarkupOptionModel, sourceMarkupOptionModel);
assert.equal(markupOptionValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "new-placeholder"), true);
assert.equal(markupOptionValidatedTarget.diagnostics.some((diagnostic) => diagnostic.code === "missing-source-placeholder"), true);

const parts = partsForPattern("Tap {#link href=$url}profile{/link}. {$name}", {
  name: "Jean",
  url: "/people/jean",
});
assert.deepEqual(parts.map((part) => part.type), ["text", "markup", "text", "markup", "text", "expression"]);

const broken = parseSource(".input {$count :number}\n.match $count\none {{One");
assert.equal(broken.diagnostics.some((diagnostic) => diagnostic.code === "unclosed-placeholder"), true);

const history = createSourceHistory("one");
history.push("two");
history.push("three");
assert.equal(history.undo(), "two");
assert.equal(history.undo(), "one");
assert.equal(history.redo(), "two");
history.push("four");
assert.equal(history.redo(), "four");

console.log("MF2 editor prototype smoke test passed");
