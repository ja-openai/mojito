import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createMF2 } from "@mojito-mf2/react";
import { parseToModel } from "@mojito-mf2/core/parser";
import {
  analyzeMessage,
  analyzeModel,
  checkMarkupPatterns,
  checkTranslation,
  checkCatalog,
  compileCatalog,
} from "../src/index.js";

const ok = (result) =>
  assert.equal(result.ok, true, JSON.stringify(result.diagnostics));
const codes = (result) => result.diagnostics.map((d) => d.code);

function render(source, args = { n: 2 }) {
  const result = compileCatalog({ sample: source });
  ok(result);
  const errors = [];
  const runtime = createMF2({
    locale: "en",
    resources: { en: { app: result.artifact.messages } },
    bidiIsolation: "none",
    onError: (error) => errors.push(error),
  });
  const output = runtime.format("app", "sample", args);
  assert.deepEqual(errors, [], JSON.stringify(errors));
  return output;
}

test("function contracts preserve branch association and allow phrase reordering", () => {
  const prefix = ".input {$kind :string}\n.match $kind\n";
  const source =
    prefix +
    "a {{Value {$n :number minimumFractionDigits=1}}}\n* {{Value {$n :number minimumFractionDigits=2}}}";
  const swapped =
    prefix +
    "a {{Valeur {$n :number minimumFractionDigits=2}}}\n* {{Valeur {$n :number minimumFractionDigits=1}}}";
  assert.ok(
    codes(checkTranslation(source, swapped)).includes(
      "branch-function-contract",
    ),
  );
  ok(
    checkTranslation(
      "From {$a :number} to {$b :number}",
      "{$b :number} après {$a :number}",
    ),
  );
  const plural =
    ".input {$n :number}\n.match $n\none {{One {$value :number minimumFractionDigits=1}}}\n* {{Many {$value :number minimumFractionDigits=2}}}";
  ok(
    checkTranslation(
      plural,
      plural.replace(
        "* {{",
        "few {{Few {$value :number minimumFractionDigits=2}}}\n* {{",
      ),
      { targetLocale: "pl" },
    ),
  );
  assert.ok(
    codes(
      checkTranslation(
        plural,
        plural.replace(
          "* {{",
          "few {{Few {$value :number minimumFractionDigits=1}}}\n* {{",
        ),
        { targetLocale: "pl" },
      ),
    ).includes("branch-function-contract"),
  );
});

test("selector capability checks follow inherited options and valid resets", () => {
  const select = (parent, child) =>
    `.input {$n :number ${parent}}\n.local $x = {$n :${child}}\n.match $x\none {{One}}\n* {{Other}}`;
  for (const parent of [
    "notation=compact",
    "minimumSignificantDigits=2",
    "maximumSignificantDigits=3",
    "style=percent",
    "notation=$notation",
  ])
    assert.ok(
      codes(analyzeMessage(select(parent, "number"))).includes(
        "unsupported-selector-options",
      ),
      parent,
    );
  assert.equal(
    render(select("notation=compact", "number notation=standard"), { n: 1 }),
    "One",
  );
  assert.equal(
    render(select("minimumSignificantDigits=2", "integer"), { n: 1 }),
    "One",
  );
  assert.equal(render(select("style=percent", "integer"), { n: 1 }), "One");
  assert.equal(render(select("select=exact", "percent"), { n: 0.01 }), "One");
  assert.equal(render(select("select=ordinal", "number"), { n: 2 }), "Other");
  assert.equal(analyzeMessage(select("select=exact", "number")).ok, false);
  assert.equal(
    analyzeMessage(select("select=exact", "number select=exact")).ok,
    false,
  );
  assert.equal(analyzeMessage(select("select=$mode", "number")).ok, false);
});

test("static numeric ranges, inherited ranges, offset requirements and currency provenance", () => {
  for (const source of [
    "{$n :number maximumFractionDigits=-1}",
    "{$n :number maximumFractionDigits=101}",
    "{$n :number minimumFractionDigits=3 maximumFractionDigits=1}",
    "{$n :number minimumIntegerDigits=22}",
    "{$n :number minimumSignificantDigits=0}",
    "{$n :number minimumSignificantDigits=4 maximumSignificantDigits=2}",
    "{$n :number maximumFractionDigits=1.5}",
    "{$n :currency}",
    "{$n :currency currency=US}",
    "{$n :currency currency=123}",
    ".local $amount = {$n :currency currency=USD}\n{{{$amount :currency currency=EUR}}}",
    ".input {$n :number minimumFractionDigits=4}\n{{{$n :number maximumFractionDigits=2}}}",
    ".input {$n :number maximumSignificantDigits=2}\n{{{$n :number minimumSignificantDigits=4}}}",
    "{$n :offset}",
    "{$n :offset add=1 subtract=2}",
    "{$n :offset add=1.2}",
    "{$n :integer minimumFractionDigits=1}",
  ])
    assert.equal(analyzeMessage(source).ok, false, source);
  assert.equal(
    render(
      ".local $money = {$n :currency currency=USD}\n.local $amount = {$money}\n{{{$amount :currency}}}",
    ),
    "$2.00",
  );
  assert.equal(
    render(".input {$n :number minimumFractionDigits=4}\n{{{$n :integer}}}"),
    "2",
  );
  assert.equal(render("{$n :offset add=1}"), "3");
  assert.equal(
    render("{$n :number minimumFractionDigits=2 maximumFractionDigits=2}"),
    "2.00",
  );
  ok(analyzeMessage("{$n :currency currency=$currency}"));
  ok(analyzeMessage("{$n :number maximumFractionDigits=$digits}"));
});

test("NFC function, option, attribute and signature identifiers preserve literals and caller models", () => {
  const options = {
    profile: "model",
    functions: {
      café: { input: ["number"], options: { résumé: { types: ["string"] } } },
    },
  };
  const source =
    "cafe\u0301 {$n :cafe\u0301 re\u0301sume\u0301=|cafe\u0301| @re\u0301sume\u0301=|cafe\u0301|}";
  const model = parseToModel(source).model;
  const before = structuredClone(model);
  Object.freeze(model.pattern[1].function);
  const result = analyzeModel(model, options);
  ok(result);
  assert.deepEqual(model, before);
  assert.equal(result.model.pattern[0], "cafe\u0301 ");
  assert.equal(result.model.pattern[1].function.name, "café");
  assert.equal(
    result.model.pattern[1].function.options.résumé.value,
    "cafe\u0301",
  );
  assert.equal(result.model.pattern[1].attributes.résumé.value, "cafe\u0301");
  ok(
    analyzeMessage("{$n :café résumé=x}", {
      profile: "model",
      functions: {
        "cafe\u0301": {
          input: ["number"],
          options: { "re\u0301sume\u0301": { types: ["string"] } },
        },
      },
    }),
  );
  assert.ok(
    codes(
      analyzeMessage("{$n :café}", {
        functions: {
          café: { input: ["number"] },
          "cafe\u0301": { input: ["number"] },
        },
      }),
    ).includes("duplicate-normalized-name"),
  );
  assert.ok(
    codes(
      analyzeMessage("{$n :café}", {
        functions: {
          café: {
            input: ["number"],
            options: {
              résumé: { types: ["string"] },
              "re\u0301sume\u0301": { types: ["string"] },
            },
          },
        },
      }),
    ).includes("duplicate-normalized-name"),
  );
  const duplicate = structuredClone(before);
  duplicate.pattern[1].function.options.résumé = {
    type: "literal",
    value: "other",
  };
  assert.ok(
    codes(analyzeModel(duplicate, options)).includes(
      "duplicate-normalized-name",
    ),
  );
  for (const signature of [
    null,
    { input: [] },
    { input: ["number"], options: { x: null } },
  ])
    assert.equal(
      analyzeMessage("{$n :custom}", { functions: { custom: signature } }).ok,
      false,
    );
  const canonical = analyzeMessage("Hello {$name}").model;
  assert.equal(
    analyzeModel(canonical).model,
    canonical,
    "already normalized models should not be copied",
  );
});

test("diagnostic and model metadata budgets fail closed", () => {
  const bad = "{$n :number wrong=1}".repeat(20);
  for (const result of [
    analyzeMessage(bad, { limits: { diagnostics: 3 } }),
    compileCatalog(
      Object.fromEntries(
        Array.from({ length: 20 }, (_, i) => [`message${i}`, bad]),
      ),
      { limits: { diagnostics: 3 } },
    ),
    checkCatalog(
      Object.fromEntries(
        Array.from({ length: 20 }, (_, i) => [`message${i}`, "Hello"]),
      ),
      {},
      { limits: { diagnostics: 3 } },
    ),
  ]) {
    assert.equal(result.ok, false);
    assert.equal(result.diagnostics.length, 4);
    assert.equal(result.diagnostics.at(-1).code, "diagnostic-limit");
  }
  const model = parseToModel("{#x /}").model;
  model.pattern[0].attributes = { ["a".repeat(1000)]: true };
  assert.equal(
    analyzeModel(model, { limits: { sourceLength: 100 } }).ok,
    false,
  );
});

test("shared model markup fixtures and independent pattern-only checks", async () => {
  const fixtures = JSON.parse(
    await readFile(
      new URL(
        "../../conformance/fixtures/markup-model-contract.json",
        import.meta.url,
      ),
      "utf8",
    ),
  );
  for (const fixture of fixtures) {
    const options = { profile: "model", targetLocale: fixture.locale };
    assert.equal(
      checkTranslation(fixture.source, fixture.target, options).ok,
      fixture.valid,
      fixture.name,
    );
    assert.equal(
      checkMarkupPatterns(
        parseToModel(fixture.source).model.pattern,
        parseToModel(fixture.target).model.pattern,
        options,
      ).ok,
      fixture.valid,
      fixture.name,
    );
  }
  ok(
    checkMarkupPatterns(
      parseToModel("{#b}X {$n :unknown}{/b}").model.pattern,
      parseToModel("{#b}Y {$n :other}{/b}").model.pattern,
    ),
  );
  assert.equal(checkMarkupPatterns(null, []).ok, false);
  assert.equal(
    checkMarkupPatterns(parseToModel("{#b}{#b}X{/b}{/b}").model.pattern, [], {
      limits: { markupDepth: 1 },
    }).ok,
    false,
  );
});

test("model and markup APIs reject accessors, exotic objects and sparse arrays without execution", () => {
  let executed = false;
  const part = { kind: "standalone", name: "x" };
  Object.defineProperty(part, "type", {
    get() {
      executed = true;
      return "markup";
    },
  });
  assert.equal(checkMarkupPatterns([part], []).ok, false);
  assert.equal(
    analyzeModel({ type: "message", declarations: [], pattern: [part] }).ok,
    false,
  );
  const inherited = Object.create({
    get type() {
      executed = true;
      return "message";
    },
  });
  assert.equal(analyzeModel(inherited).ok, false);
  assert.equal(
    analyzeModel({
      type: "message",
      declarations: [],
      pattern: new Array(1000000000),
    }).ok,
    false,
  );
  assert.equal(executed, false);
});

test("static local option values are checked without inventing external arguments", () => {
  for (const source of [
    ".local $digits = {-1}\n{{{$n :number maximumFractionDigits=$digits}}}",
    ".local $currency = {|US|}\n{{{$n :currency currency=$currency}}}",
    ".local $amount = {1.5}\n{{{$n :offset add=$amount}}}",
  ])
    assert.equal(analyzeMessage(source).ok, false, source);
  assert.equal(
    render(
      ".local $digits = {2}\n{{{$n :number minimumFractionDigits=$digits}}}",
    ),
    "2.00",
  );
});

test("plural category contraction may remove branch-only functions and markup", () => {
  const source =
    ".input {$n :number}\n.match $n\none {{{#strong}{$n :number minimumFractionDigits=1}{/strong}}}\n* {{{$n :number minimumFractionDigits=2}}}";
  const target =
    ".input {$n :number}\n.match $n\n* {{{$n :number minimumFractionDigits=2}}}";
  ok(checkTranslation(source, target, { targetLocale: "ja" }));
});

test("literal numeric syntax and precision agree with the runtime's representable domain", () => {
  for (const source of [
    "{9007199254740993 :number}",
    "{1e-999 :number}",
    "{|+1| :number}",
    "{$n :number maximumFractionDigits=2.0}",
    "{$n :number minimumIntegerDigits=1e1}",
    "{1.5 :offset add=1}",
    ".input {$n :number}\n.match $n\n1e999 {{Too large}}\n* {{Other}}",
  ])
    assert.equal(analyzeMessage(source).ok, false, source);
  assert.equal(render("{0e-999 :number}"), "0");
  assert.equal(render("{9007199254740991 :number}"), "9,007,199,254,740,991");
});

test("formatter-only input declarations work in compiled React catalogs", () => {
  assert.equal(render(".input {$n :currency currency=USD}\n{{{$n}}}"), "$2.00");
  assert.equal(
    render(
      ".input {$n :currency currency=USD}\n.local $amount = {$n}\n{{{$amount :currency}}}",
    ),
    "$2.00",
  );
  assert.equal(
    render(".input {$date :date timeZone=UTC length=long}\n{{{$date}}}", {
      date: "2030-04-05",
    }),
    "April 5, 2030",
  );
  assert.equal(
    render(".input {$n :relativeTime unit=day numeric=auto}\n{{{$n}}}", {
      n: 1,
    }),
    "tomorrow",
  );
});

test("explicit invalid built-in signatures cannot bypass numeric capabilities", () => {
  for (const signature of [null, undefined]) {
    for (const [name, source] of [
      ["number", "{$n :number maximumFractionDigits=-1}"],
      ["currency", "{$n :currency}"],
      [
        "number",
        ".input {$n :number notation=compact}\n.match $n\none {{One}}\n* {{Other}}",
      ],
    ]) {
      const result = compileCatalog(
        { sample: source },
        { functions: { [name]: signature } },
      );
      assert.equal(result.ok, false, `${name}: ${signature}`);
      assert.equal(result.artifact, null);
      assert.ok(codes(result).includes("invalid-function-signature"));
    }
  }
  ok(analyzeMessage("{$n :number}"));
});
