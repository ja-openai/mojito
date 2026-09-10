import assert from "node:assert/strict";
import { test } from "node:test";
import { FunctionRegistry, formatMessage, parseToModel } from "../src/index.js";
import { createIntlFunctionRegistry } from "../src/intl_functions.js";

const intl = createIntlFunctionRegistry(FunctionRegistry);
function format(source, args = {}, locale = "en", functions = intl) {
  const parsed = parseToModel(source);
  assert.deepEqual(parsed.diagnostics, []);
  return formatMessage(parsed.model, args, { locale, functions });
}
function output(source, args, locale, functions) {
  const result = format(source, args, locale, functions);
  assert.deepEqual(result.errors, []);
  return result.value;
}
function select(option, declaration = "number", tail = "") {
  return `.input {$n :${declaration} ${option}}\n${tail}.match $n\none {{one}}\nother {{other}}\n1 {{exact}}\n* {{fallback}}`;
}

test("advertised number options match native Intl for English, French, and Arabic", () => {
  const cases = [
    ["notation=scientific minimumSignificantDigits=5", { notation: "scientific", minimumSignificantDigits: 5 }],
    ["notation=engineering", { notation: "engineering" }],
    ["notation=compact", { notation: "compact" }],
    ["style=percent", { style: "percent" }],
    ["minimumIntegerDigits=4 minimumFractionDigits=4", { minimumIntegerDigits: 4, minimumFractionDigits: 4 }],
    ["maximumSignificantDigits=6", { maximumSignificantDigits: 6 }],
    ["minimumSignificantDigits=4 maximumSignificantDigits=6 maximumFractionDigits=1", { minimumSignificantDigits: 4, maximumSignificantDigits: 6, maximumFractionDigits: 1 }],
  ];
  for (const locale of ["en", "fr", "ar"]) {
    for (const [options, native] of cases) {
      for (const n of [3.1415927, 0, -12345.678]) {
        assert.equal(output(`{$n :number ${options}}`, { n }, locale), new Intl.NumberFormat(locale, native).format(n));
      }
    }
  }
});

test("digit and enum options fail with bounded bad-option diagnostics", () => {
  for (const option of [
    "minimumIntegerDigits=0", "minimumIntegerDigits=22", "minimumIntegerDigits=1.2",
    "minimumSignificantDigits=0", "maximumSignificantDigits=22", "maximumSignificantDigits=-1",
    "minimumSignificantDigits=4 maximumSignificantDigits=2", "notation=tiny", "style=currency",
    `maximumSignificantDigits=|${"9".repeat(1000)}|`,
  ]) {
    assert.deepEqual(format(`{$n :number ${option}}`, { n: 1 }).errors.map(e => e.code), ["bad-option"], option);
  }
});

test("fixed numeric functions keep their styles and currency identity", () => {
  assert.equal(output("{$n :percent maximumSignificantDigits=2}", { n: 1.234 }), new Intl.NumberFormat("en", { style: "percent", maximumSignificantDigits: 2 }).format(1.234));
  assert.equal(output("{$n :integer minimumIntegerDigits=4}", { n: 3.9 }), "0,003");
  assert.equal(output("{$n :currency currency=USD maximumSignificantDigits=3}", { n: 12.345 }), new Intl.NumberFormat("en", { style: "currency", currency: "USD", maximumSignificantDigits: 3 }).format(12.345));
  for (const expression of ["percent style=decimal", "integer style=percent", "currency currency=USD style=percent"]) {
    assert.deepEqual(format(`{$n :${expression}}`, { n: 1 }).errors.map(e => e.code), ["bad-option"]);
  }
});

test("number locals inherit options with explicit overrides and type barriers", () => {
  assert.equal(output(".input {$n :number notation=scientific minimumSignificantDigits=5}\n.local $a = {$n}\n{{{$a :number maximumSignificantDigits=6}}}", { n: 3.1415927 }), "3.14159E0");
  assert.equal(output(".input {$n :number style=percent}\n{{{$n :number} / {$n :number style=decimal}}}", { n: 0.25 }), "25% / 0.25");
  assert.equal(output(".input {$n :number style=percent}\n.local $i = {$n :integer}\n{{{$i :number}}}", { n: 3.9 }), "3");
  assert.equal(output(".input {$n :number minimumSignificantDigits=4}\n.local $i = {$n :integer}\n{{{$i}}}", { n: 3.9 }), "3");
});

test("formatting option guard prevents false plural and exact branches", () => {
  for (const option of ["maximumSignificantDigits=1", "minimumSignificantDigits=4", "notation=scientific", "notation=compact", "style=percent", "select=exact maximumSignificantDigits=1"]) {
    for (const n of [1, 1.2, 0.01]) {
      const result = format(select(option), { n });
      assert.equal(result.value, "fallback", option);
      assert.deepEqual(result.errors.map(e => e.code), ["bad-option", "bad-selector"]);
    }
  }
  const wildcardOnly = format(".input {$n :number maximumSignificantDigits=1}\n.match $n\n* {{fallback}}", { n: 1.2 });
  assert.deepEqual(wildcardOnly.errors.map(e => e.code), ["bad-option", "bad-selector"]);
});

test("selection guard sees inherited and variable options while allowing represented options", () => {
  const inherited = ".input {$x :number maximumSignificantDigits=$digits}\n.local $n = {$x :number}\n.match $n\none {{one}}\n* {{fallback}}";
  assert.equal(format(inherited, { x: 1.2, digits: 1 }).value, "fallback");
  assert.deepEqual(format(inherited, { x: 1.2, digits: 1 }).errors.map(e => e.code), ["bad-option", "bad-selector"]);
  assert.equal(output(select("minimumIntegerDigits=4"), { n: 1 }), "exact");
  assert.equal(output(select("maximumFractionDigits=0"), { n: 1.2 }), "exact");
  assert.equal(output(select("", "percent"), { n: 0.01 }), "exact");
  const discarded = ".input {$x :number minimumSignificantDigits=4 style=percent}\n.local $n = {$x :integer}\n.match $n\n1 {{exact}}\n* {{fallback}}";
  assert.equal(output(discarded, { x: 1.2 }), "exact");
});

test("registry copies preserve unrelated guards and transfer overridden function responsibility", () => {
  const source = select("maximumSignificantDigits=1");
  assert.equal(format(source, { n: 1.2 }, "en", intl.withFunction("custom", c => c.value)).value, "fallback");
  const replaced = intl.withFunction("number", () => "custom");
  assert.deepEqual(format(source, { n: 1.2 }, "en", replaced).errors, []);
  const customSelector = intl.withSelector("number", ({ key }) => key === "custom" ? 2 : null);
  const customSource = ".input {$n :number maximumSignificantDigits=1}\n.match $n\ncustom {{custom}}\n* {{fallback}}";
  assert.equal(output(customSource, { n: 1.2 }, "en", customSelector), "custom");
  assert.deepEqual(format(source, { n: 1.2 }).errors.map(e => e.code), ["bad-option", "bad-selector"]);
});

test("independent calls resolve variable digits afresh", () => {
  const parsed = parseToModel(".input {$n :number maximumSignificantDigits=$digits}\n.local $a = {$n}\n{{{$a :number}}}");
  for (const digits of [2, 5, 3, 2]) {
    const result = formatMessage(parsed.model, { n: 3.1415927, digits }, { functions: intl, locale: "en" });
    assert.deepEqual(result.errors, []);
    assert.equal(result.value, new Intl.NumberFormat("en", { maximumSignificantDigits: digits }).format(3.1415927));
  }
});
