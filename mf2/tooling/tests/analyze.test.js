import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  analyzeMessage,
  analyzeModel,
  checkContracts,
  checkTranslation,
  compileCatalog,
  checkCatalog,
  generateTypes,
} from "../src/index.js";

const ok = (result) =>
  assert.equal(result.ok, true, JSON.stringify(result.diagnostics));
const codes = (result) => result.diagnostics.map((d) => d.code);

test("external contract follows declarations, local aliases, options and every branch", () => {
  const result = analyzeMessage(
    ".input {$count :number maximumFractionDigits=$digits}\n.local $alias = {$name}\n.match $count\none {{Hello {$alias}}}\n* {{Hello {$alias}; {$other :string}}}",
  );
  ok(result);
  assert.deepEqual(Object.keys(result.contract.inputs), [
    "count",
    "digits",
    "name",
    "other",
  ]);
  assert.deepEqual(result.contract.inputs.count, ["number"]);
  assert.deepEqual(result.contract.inputs.digits, ["number"]);
  assert.deepEqual(result.contract.inputs.other, ["string"]);
  assert.ok(!Object.hasOwn(result.contract.inputs, "alias"));
});

test("NFC bindings and prototype-like keys are safe", () => {
  const result = analyzeMessage(
    "{$cafe\u0301} {$café} {$__proto__} {$constructor}",
  );
  ok(result);
  assert.deepEqual(Object.keys(result.contract.inputs), [
    "__proto__",
    "café",
    "constructor",
  ]);
  assert.equal(Object.getPrototypeOf(result.contract.inputs), Object.prototype);
  assert.ok(Array.isArray(result.contract.inputs.__proto__));
  const catalog = JSON.parse(
    '{"__proto__":"Hello {$name}","constructor":"World"}',
  );
  const compiled = compileCatalog(catalog);
  ok(compiled);
  assert.equal(compiled.artifact.messages.__proto__.type, "message");
});

test("unknown functions require an explicit application capability", () => {
  assert.ok(
    codes(analyzeMessage("{$x :app:custom}")).includes("unknown-function"),
  );
  const functions = {
    "app:custom": {
      input: ["number"],
      options: { style: { types: ["string"] } },
    },
  };
  ok(
    analyzeMessage("{$x :app:custom style=$style}", {
      profile: "model",
      functions,
    }),
  );
  ok(analyzeMessage("{$x :app:custom}", { functions }));
  assert.ok(
    codes(analyzeMessage("{$x :constructor}")).includes("unknown-function"),
  );
});

test("malformed source, declarations, selectors and static profile violations have diagnostics", () => {
  for (const source of [
    "Hello {",
    ".local $a = {$a}\n{{{$a}}}",
    ".local $a = {$b}\n.local $b = {1}\n{{{$a}}}",
    ".input {$n :number}\n.input {$n :number}\n{{{$n}}}",
    ".match $n\n* {{hello}}",
    ".input {$n :date}\n.match $n\n* {{hello}}",
    ".input {$n :number}\n.match $n\nbanana {{hi}}\n* {{hello}}",
    "{#x}Unclosed",
    "{#x}{#y}Crossed{/x}{/y}",
    "{$x :number unknown=1}",
    "{$x :number select=bad}",
  ]) {
    assert.equal(analyzeMessage(source).ok, false, source);
  }
});

test("model profile supports unmatched markup and protects option variables", () => {
  const source = "Read {#link href=$destination}help";
  const result = analyzeMessage(source, { profile: "model" });
  ok(result);
  assert.ok(Object.hasOwn(result.contract.inputs, "destination"));
  ok(
    checkTranslation(source, "Lire {#link href=$destination}aide", {
      profile: "model",
    }),
  );
  assert.equal(
    checkTranslation(source, "Lire {#link href=|https://example.com|}aide", {
      profile: "model",
    }).ok,
    false,
  );
  assert.ok(
    codes(analyzeMessage(source)).includes("unsupported-react-markup-metadata"),
  );
});

test("cross-language markup fixtures preserve hierarchy and allow sibling movement", async () => {
  const fixtures = JSON.parse(
    await readFile(
      new URL(
        "../../conformance/fixtures/markup-contract.json",
        import.meta.url,
      ),
      "utf8",
    ),
  );
  for (const fixture of fixtures) {
    const result = checkTranslation(fixture.source, fixture.target, {
      targetLocale: fixture.locale,
    });
    assert.equal(
      result.ok,
      fixture.valid,
      `${fixture.name}: ${JSON.stringify(result.diagnostics)}`,
    );
  }
});

test("translation cannot change inputs, functions, exact branches or options", () => {
  for (const [source, target, code] of [
    ["Hello {$name}", "Hello {$other}", "argument-contract"],
    ["{$n :number}", "{$n :string}", "function-contract"],
    [
      "{$n :number maximumFractionDigits=2}",
      "{$n :number maximumFractionDigits=3}",
      "function-contract",
    ],
    [
      ".input {$state :string}\n.match $state\nopen {{Open}}\n* {{Other}}",
      ".input {$state :string}\n.match $state\nclosed {{Closed}}\n* {{Other}}",
      "selector-keys",
    ],
    [
      ".input {$n :number}\n.match $n\n1 {{One}}\n* {{Other}}",
      ".input {$n :number}\n.match $n\n* {{Other}}",
      "selector-keys",
    ],
  ])
    assert.ok(
      codes(checkTranslation(source, target, { targetLocale: "fr" })).includes(
        code,
      ),
      code,
    );
});

test("string selectors stay open; target plural categories can expand or contract", () => {
  const string = analyzeMessage(
    ".input {$state :string}\n.match $state\nopen {{Open}}\n* {{Other}}",
  );
  ok(string);
  assert.deepEqual(string.contract.inputs.state, ["string"]);
  const source = ".input {$n :number}\n.match $n\none {{One}}\n* {{Other}}";
  const target =
    ".input {$n :number}\n.match $n\none {{One}}\nfew {{Few}}\nmany {{Many}}\n* {{Other}}";
  ok(checkTranslation(source, target, { targetLocale: "pl" }));
  ok(
    checkTranslation(source, ".input {$n :number}\n.match $n\n* {{Other}}", {
      targetLocale: "ja",
    }),
  );
  assert.ok(
    codes(checkTranslation(source, source)).includes("plural-locale-unchecked"),
  );
});

test("multi-selector exact branch removals are diagnosed without cross-product enumeration", () => {
  const prefix = ".input {$a :string}\n.input {$b :string}\n.match $a $b\n";
  const source = prefix + "x y {{A}}\nx * {{B}}\n* y {{C}}\n* * {{D}}";
  const target = prefix + "x * {{B}}\n* y {{C}}\n* * {{D}}";
  assert.ok(
    codes(checkTranslation(source, target)).includes("missing-variant"),
  );
});

test("catalog diagnostics distinguish missing keys, sides and parser failures; compile is all-or-nothing", () => {
  const result = checkCatalog(
    { missing: "Source", bad: "{", other: "Hello {$n}" },
    { extra: "Extra", bad: "}", other: "Hello {$x}" },
  );
  assert.ok(codes(result).includes("missing-message"));
  assert.ok(codes(result).includes("extra-message"));
  assert.ok(
    result.diagnostics.some((d) => d.key === "bad" && d.side === "source"),
  );
  assert.ok(
    result.diagnostics.some((d) => d.key === "bad" && d.side === "target"),
  );
  const compiled = compileCatalog({ a: "Good", b: "{" });
  assert.equal(compiled.artifact, null);
  assert.equal(compiled.contracts, null);
  assert.deepEqual(
    compileCatalog({ z: "Z", a: "A" }),
    compileCatalog({ a: "A", z: "Z" }),
  );
  let invoked = false;
  assert.equal(
    compileCatalog({
      get a() {
        invoked = true;
        return "X";
      },
    }).ok,
    false,
  );
  assert.equal(invoked, false);
});

test("analysis budgets bound catalogs, message size, nesting and declarations", () => {
  assert.ok(
    codes(analyzeMessage("Long", { limits: { sourceLength: 2 } })).includes(
      "source-limit",
    ),
  );
  assert.ok(
    codes(
      compileCatalog({ a: "1", b: "2" }, { limits: { catalogMessages: 1 } }),
    ).includes("catalog-limit"),
  );
  assert.ok(
    codes(
      compileCatalog({ a: "1234" }, { limits: { catalogLength: 2 } }),
    ).includes("catalog-limit"),
  );
  assert.ok(
    codes(
      analyzeMessage("{#x}".repeat(65) + "Text" + "{/x}".repeat(65)),
    ).includes("markup-depth"),
  );
  ok(analyzeMessage("{#x}".repeat(64) + "Text" + "{/x}".repeat(64)));
  assert.throws(
    () => analyzeMessage("Hi", { limits: { sourceLength: Infinity } }),
    /limit/,
  );
  const many =
    Array.from({ length: 129 }, (_, index) => `.local $x${index} = {|a|}`).join(
      "\n",
    ) + "\n{{X}}";
  assert.ok(codes(analyzeMessage(many)).includes("model-limit"));
});

test("generated code quotes keys and values instead of interpolating source syntax", () => {
  const catalog = {
    'x";\nexport const injected = true;//': "Hello {$user-name}",
    __proto__: "Unused",
  };
  const result = generateTypes(catalog, {
    resource: 'x";\nthrow new Error(); //',
  });
  ok(result);
  assert.ok(result.code.includes('"user-name"'));
  assert.ok(result.code.includes("\\nexport const injected"));
  assert.equal(
    generateTypes({ a: "A" }, { resource: "x", profile: "model" }).ok,
    false,
  );
});

test("parsed model analysis reuses structure without mutating the caller and compares cached contracts", () => {
  const source = analyzeMessage("Hello {$cafe\u0301}");
  const model = structuredClone(source.model);
  model.pattern[1].arg.name = "cafe\u0301";
  Object.freeze(model.pattern[1].arg);
  const result = analyzeModel(model);
  ok(result);
  assert.equal(model.pattern[1].arg.name, "cafe\u0301");
  assert.equal(result.model.pattern[1].arg.name, "café");
  ok(checkContracts(source.contract, result.contract));
  assert.equal(
    checkContracts(source.contract, { ...result.contract, version: 999 }).ok,
    false,
  );
  assert.equal(
    analyzeModel({
      type: "message",
      declarations: [],
      pattern: [{ type: "expression", arg: {} }],
    }).ok,
    false,
  );
  assert.equal(
    analyzeModel({
      type: "message",
      declarations: [],
      pattern: [
        {
          type: "markup",
          name: "x",
          kind: "standalone",
          attributes: { x: "bad" },
        },
      ],
    }).ok,
    false,
  );
  const cyclic = {};
  cyclic.self = cyclic;
  assert.equal(analyzeModel(cyclic).ok, false);
});
