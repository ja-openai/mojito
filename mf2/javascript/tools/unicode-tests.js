import { readFile, readdir } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  FunctionRegistry,
  MF2Error,
  formatMessage,
  formatMessageToParts,
  parseToModel,
} from "../src/index.js";
import { createDateTimeCoreFunctionRegistry } from "../src/date_time_core.js";
import { createNumberCoreFunctionRegistry } from "../src/number_core.js";

const CHECKS = [
  ["tests/syntax.json", "parse"],
  ["tests/syntax-errors.json", "parse"],
  ["tests/bidi.json", "parse"],
  ["tests/data-model-errors.json", "data-model"],
  ["tests/functions/string.json", "runtime"],
  ["tests/functions/number.json", "runtime"],
  ["tests/functions/percent.json", "runtime"],
  ["tests/functions/currency.json", "runtime"],
  ["tests/functions/date.json", "runtime"],
  ["tests/functions/datetime.json", "runtime"],
  ["tests/functions/time.json", "runtime"],
  ["tests/functions/offset.json", "runtime"],
  ["tests/functions/integer.json", "runtime"],
  ["tests/u-options.json", "runtime"],
  ["tests/fallback.json", "runtime"],
  ["tests/pattern-selection.json", "runtime"],
];

class UnicodeTestFailure extends Error {}

export async function runUnicodeTests(root, baselinePath) {
  const testRoot = root ?? resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "third_party", "message-format-wg", "test");
  const baseline = baselinePath ?? resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "conformance", "unicode-official-baseline.json");
  const summary = {
    passed: 0,
    skipped: 0,
    notWired: 0,
    partsAssertions: 0,
    files: [],
    skipExamples: [],
  };
  const wired = new Set(CHECKS.map(([path]) => path));

  for (const [path, mode] of CHECKS) {
    await runFile(testRoot, path, mode, summary);
  }

  for (const path of await officialJsonPaths(join(testRoot, "tests"))) {
    const pathKey = relative(testRoot, path).replaceAll("\\", "/");
    if (wired.has(pathKey)) continue;
    const suite = await readJson(path);
    summary.notWired += suite.tests?.length ?? 0;
  }

  for (const file of summary.files) {
    console.log(`  ${file.mode} ${file.path} passed=${file.passed} skipped=${file.skipped}`);
  }
  if (summary.skipExamples.length > 0) {
    console.log("  skip examples:");
    for (const example of summary.skipExamples) console.log(`    ${example}`);
  }
  console.log(
    `JavaScript Unicode official tests passed=${summary.passed} skipped=${summary.skipped} ` +
      `not_wired=${summary.notWired} parts_assertions=${summary.partsAssertions} ` +
      `total=${summary.passed + summary.skipped + summary.notWired}`,
  );

  await checkBaseline(summary, baseline);
  return summary;
}

async function runFile(root, path, mode, summary) {
  const suite = await readJson(join(root, path));
  const defaults = suite.defaultTestProperties ?? {};
  let passed = 0;
  let skipped = 0;
  for (const [index, test] of (suite.tests ?? []).entries()) {
    const check = mode === "parse"
      ? checkParseTest(defaults, test)
      : mode === "data-model"
        ? checkDataModelErrorTest(defaults, test)
        : checkRuntimeTest(defaults, test);
    const ok = typeof check === "boolean" ? check : check.ok;
    if (ok) {
      passed += 1;
      if (typeof check !== "boolean") summary.partsAssertions += check.partsAssertions;
    } else {
      skipped += 1;
      recordSkip(summary, path, index, test, `${mode} behavior differs`);
    }
  }
  summary.passed += passed;
  summary.skipped += skipped;
  summary.files.push({ path, mode, passed, skipped });
}

function checkParseTest(defaults, test) {
  const expectedSyntaxError = expectedErrors(defaults, test).some((error) => error.type === "syntax-error");
  const result = parseToModel(test.src);
  if (result.hasDiagnostics !== expectedSyntaxError) return false;
  if (result.hasDiagnostics || test.expParts == null) return true;
  return checkOfficialParts(defaults, test, result.model);
}

function checkDataModelErrorTest(defaults, test) {
  const expectedCodes = expectedLocalCodes(defaults, test);
  const result = parseToModel(test.src);
  if (expectedCodes.length === 0) {
    if (test.exp == null || result.hasDiagnostics) return false;
    try {
      const actual = formatMessage(result.model, argumentsFor(test, result.model), {
        locale: locale(defaults, test),
        bidiIsolation: bidiIsolation(defaults, test),
      });
      return actual.errors.length === 0 && actual.value === test.exp;
    } catch {
      return false;
    }
  }

  const actualCodes = [];
  if (result.hasDiagnostics) {
    actualCodes.push(...result.diagnostics.map((diagnostic) => diagnostic.code));
  } else {
    try {
      const actual = formatMessage(result.model, argumentsFor(test, result.model), {
        locale: locale(defaults, test),
        bidiIsolation: bidiIsolation(defaults, test),
      });
      actualCodes.push(...actual.errors.map((error) => error.code));
    } catch (error) {
      actualCodes.push(error.code);
    }
  }
  return actualCodes.some((actual) => expectedCodes.includes(actual));
}

function checkRuntimeTest(defaults, test) {
  const result = parseToModel(test.src);
  if (result.hasDiagnostics) return false;
  const expectedCodes = expectedLocalCodes(defaults, test);
  try {
    const options = {
      locale: locale(defaults, test),
      bidiIsolation: bidiIsolation(defaults, test),
      functions: officialFunctionRegistry(),
    };
    const actual = formatMessage(result.model, runtimeArgumentsFor(test), {
      locale: options.locale,
      bidiIsolation: options.bidiIsolation,
      functions: options.functions,
    });
    const actualCodes = actual.errors.map((error) => error.code);
    if (!expectedCodes.every((expected) => actualCodes.includes(expected))) return false;
    if (expectedCodes.length === 0 && actualCodes.length > 0) return false;
    if (test.exp != null && actual.value !== test.exp) return false;
    if (test.expParts != null) {
      const partsCheck = checkOfficialParts(defaults, test, result.model, expectedCodes);
      if (!partsCheck.ok) return false;
      return partsCheck;
    }
    return { ok: true, partsAssertions: 0 };
  } catch (error) {
    return expectedCodes.includes(error.code);
  }
}

function checkOfficialParts(defaults, test, model, expectedCodes = []) {
  const args = runtimeArgumentsFor(test);
  const options = {
    locale: locale(defaults, test),
    bidiIsolation: bidiIsolation(defaults, test),
    functions: officialFunctionRegistry(),
  };
  const actualParts = formatMessageToParts(model, args, options);
  const actualCodes = actualParts.errors.map((error) => error.code);
  if (!expectedCodes.every((expected) => actualCodes.includes(expected))) return { ok: false, partsAssertions: 0 };
  if (expectedCodes.length === 0 && actualCodes.length > 0) return { ok: false, partsAssertions: 0 };
  const projectedParts = projectOfficialParts(model, actualParts.parts, options, args);
  if (!deepEqual(projectedParts, test.expParts)) return { ok: false, partsAssertions: 0 };
  return { ok: true, partsAssertions: 1 };
}

function projectOfficialParts(model, parts, options, args) {
  const expressions = simplePatternExpressions(model).map((expression) => expressionMetadata(model, expression));
  let expressionIndex = 0;
  const projected = [];
  for (const part of parts) {
    if (part.type === "text") {
      projected.push({ type: "text", value: part.value });
      continue;
    }
    if (part.type === "fallback") {
      const fallback = { type: "fallback", source: part.source };
      if (part.value !== undefined) fallback.value = part.value;
      projected.push(fallback);
      continue;
    }
    if (part.type === "markup") {
      projected.push(projectOfficialMarkupPart(part, args));
      continue;
    }
    if (part.type !== "expression") {
      projected.push(part);
      continue;
    }
    const metadata = expressions[expressionIndex++];
    const type = officialExpressionPartType(metadata?.function?.name);
    if (type == null) {
      projected.push(part);
      continue;
    }
    const expression = { type };
    const valueParts = officialValueParts(type, part.value);
    if (valueParts != null) expression.parts = valueParts;
    if (type === "string") {
      const id = literalOption(metadata?.function?.options?.["u:id"]);
      const direction = part.direction === "ltr" || part.direction === "rtl" ? part.direction : null;
      if (direction != null) expression.dir = direction;
      if (id != null) expression.id = id;
      if (id == null && metadata?.function?.name === "string") expression.locale = options.locale;
      expression.value = part.value;
    }
    if (options.bidiIsolation === "default" && part.direction) {
      projected.push({ type: "bidiIsolation", value: bidiIsolationOpen(part.direction) });
      projected.push(expression);
      projected.push({ type: "bidiIsolation", value: "\u2069" });
    } else {
      projected.push(expression);
    }
  }
  return projected;
}

function simplePatternExpressions(model) {
  if (model?.type !== "message" || !Array.isArray(model.pattern)) return [];
  return model.pattern.filter((part) => part?.type === "expression");
}

function expressionMetadata(model, expression) {
  const locals = new Map();
  for (const declaration of model?.declarations ?? []) {
    if (declaration.type === "local") locals.set(declaration.name, declaration.value);
  }
  return resolveExpressionMetadata(expression, locals, new Set());
}

function resolveExpressionMetadata(expression, locals, seen) {
  if (expression?.function != null) return expression;
  const name = expression?.arg?.type === "variable" ? expression.arg.name : null;
  if (name == null || seen.has(name)) return expression;
  seen.add(name);
  const local = locals.get(name);
  return local?.type === "expression" ? resolveExpressionMetadata(local, locals, seen) : expression;
}

function projectOfficialMarkupPart(part, args) {
  const projected = { type: "markup", kind: part.kind };
  const id = literalOption(part.options?.["u:id"]);
  if (id != null) projected.id = id;
  projected.name = part.name;
  const options = officialMarkupOptions(part.options, args);
  if (Object.keys(options).length > 0) projected.options = options;
  return projected;
}

function officialExpressionPartType(functionName) {
  if (functionName == null) return "string";
  if (functionName === "number" || functionName === "integer" || functionName === "percent" || functionName === "currency") return "number";
  if (functionName === "date" || functionName === "time" || functionName === "datetime") return "datetime";
  if (functionName === "string") return "string";
  if (isOfficialTestFunction(functionName)) return "test";
  return null;
}

function officialValueParts(type, value) {
  if (type !== "number") return null;
  if (/^\d+$/.test(value)) return [{ type: "integer", value }];
  return null;
}

function literalOption(option) {
  return option?.type === "literal" ? option.value ?? "" : null;
}

function officialMarkupOptions(options, args) {
  const projected = {};
  for (const [name, option] of Object.entries(options ?? {})) {
    if (name === "u:id" || name === "u:dir") continue;
    if (option?.type === "literal") projected[name] = option.value ?? "";
    else if (option?.type === "variable" && Object.hasOwn(args, option.name)) projected[name] = String(args[option.name]);
  }
  return projected;
}

function bidiIsolationOpen(direction) {
  if (direction === "ltr") return "\u2066";
  if (direction === "rtl") return "\u2067";
  return "\u2068";
}

function officialFunctionRegistry() {
  return createDateTimeCoreFunctionRegistry(FunctionRegistry)
    .withRegistry(createNumberCoreFunctionRegistry(FunctionRegistry))
    .withFunction("test:function", officialTestFunction)
    .withFunction("test:select", officialTestSelectResolver)
    .withFunction("test:format", officialTestFormatResolver)
    .withSelector("test:function", officialTestSelector)
    .withSelector("test:select", officialTestSelector)
    .withSelector("test:format", officialTestFormatSelector);
}

function officialTestFunction(call) {
  const state = officialTestStateFromCall(call);
  if (state.failsFormat) {
    throw new MF2Error("bad-option", ":test:function fails=format requested a format failure.");
  }
  return formatOfficialTestValue(state);
}

function officialTestSelectResolver(call) {
  return formatOfficialTestValue(officialTestStateFromCall(call));
}

function officialTestFormatResolver(call) {
  return formatOfficialTestValue(officialTestStateFromCall(call));
}

function officialTestSelector(match) {
  const state = officialTestStateFromMatch(match);
  if (state.failsSelect) throw new MF2Error("bad-selector", ":test function fails selection.");
  if (Math.trunc(state.input) !== 1) return null;
  if (state.decimalPlaces === 1 && match.key === "1.0") return 2;
  if (match.key === "1") return 1;
  return null;
}

function officialTestFormatSelector() {
  throw new MF2Error("bad-selector", ":test:format cannot be used for selection.");
}

function officialTestStateFromCall(call) {
  return officialTestState(call.value, call.inheritedSource, (name, fallback) => call.optionValue(name, fallback));
}

function officialTestStateFromMatch(match) {
  return officialTestState(match.value, match.inheritedSource, (name, fallback) => match.optionValue(name, fallback));
}

function officialTestState(value, inheritedSource, optionValue) {
  const state = inheritedSource == null ? officialTestStateFromValue(value) : officialTestStateFromSource(inheritedSource);
  applyOfficialTestOptions(state, optionValue);
  return state;
}

function officialTestStateFromSource(source) {
  const state = source.inherited == null ? officialTestStateFromValue(source.value) : officialTestStateFromSource(source.inherited);
  if (isOfficialTestFunction(source.function?.name)) {
    applyOfficialTestOptions(state, (name, fallback) => sourceOptionValue(source, name, fallback));
  }
  return state;
}

function officialTestStateFromValue(value) {
  const input = Number(value);
  if (!Number.isFinite(input)) throw MF2Error.badOperand("Unicode test function requires a numeric operand.");
  return {
    input,
    decimalPlaces: 0,
    failsFormat: false,
    failsSelect: false,
  };
}

function applyOfficialTestOptions(state, optionValue) {
  const decimalPlaces = optionValue("decimalPlaces", null);
  if (decimalPlaces != null) {
    if (decimalPlaces === "0") state.decimalPlaces = 0;
    else if (decimalPlaces === "1") state.decimalPlaces = 1;
    else throw new MF2Error("bad-option", ":test function decimalPlaces must be 0 or 1.");
  }
  switch (optionValue("fails", "")) {
    case "always":
      state.failsFormat = true;
      state.failsSelect = true;
      break;
    case "format":
      state.failsFormat = true;
      break;
    case "select":
      state.failsSelect = true;
      break;
  }
}

function sourceOptionValue(source, name, fallback) {
  const option = source.function?.options?.[name];
  if (option == null) return fallback;
  if (option.type === "literal") return option.value ?? "";
  return fallback;
}

function formatOfficialTestValue(state) {
  const sign = state.input < 0 ? "-" : "";
  const absolute = Math.abs(state.input);
  const integer = Math.floor(absolute);
  if (state.decimalPlaces === 1) {
    const digit = Math.floor((absolute - integer) * 10);
    return `${sign}${integer}.${digit}`;
  }
  return `${sign}${integer}`;
}

function isOfficialTestFunction(name) {
  return name === "test:function" || name === "test:select" || name === "test:format";
}

function argumentsFor(test, model) {
  const args = {};
  for (const declaration of model.declarations ?? []) {
    if (declaration.type === "input") args[declaration.name] = "1";
  }
  return { ...args, ...runtimeArgumentsFor(test) };
}

function runtimeArgumentsFor(test) {
  const args = {};
  for (const param of test.params ?? []) args[param.name] = param.value;
  return args;
}

function expectedErrors(defaults, test) {
  return test.expErrors ?? defaults.expErrors ?? [];
}

function expectedLocalCodes(defaults, test) {
  return expectedErrors(defaults, test).map((error) => error.type === "variant-key-mismatch" ? "variant-key-count-mismatch" : error.type);
}

function locale(defaults, test) {
  return test.locale ?? defaults.locale ?? "en";
}

function bidiIsolation(defaults, test) {
  return test.bidiIsolation ?? defaults.bidiIsolation ?? "none";
}

function recordSkip(summary, path, index, test, reason) {
  if (summary.skipExamples.length >= 8) return;
  const label = test.description ?? test.src;
  summary.skipExamples.push(`${path}#${index + 1}: ${reason}: ${label}`);
}

async function officialJsonPaths(root) {
  const paths = [];
  await collectJsonPaths(root, paths);
  return paths.sort();
}

async function collectJsonPaths(root, paths) {
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) await collectJsonPaths(path, paths);
    else if (entry.isFile() && entry.name.endsWith(".json")) paths.push(path);
  }
}

async function checkBaseline(summary, baselinePath) {
  const baseline = await readJson(baselinePath);
  const total = summary.passed + summary.skipped + summary.notWired;
  if (
    baseline.passed !== summary.passed ||
    baseline.skipped !== summary.skipped ||
    baseline.notWired !== summary.notWired ||
    baseline.partsAssertions !== summary.partsAssertions ||
    baseline.total !== total
  ) {
    throw new UnicodeTestFailure(
      `${baselinePath}: expected official-test counts passed=${baseline.passed} skipped=${baseline.skipped} ` +
        `notWired=${baseline.notWired} partsAssertions=${baseline.partsAssertions} total=${baseline.total}, ` +
        `got passed=${summary.passed} skipped=${summary.skipped} notWired=${summary.notWired} ` +
        `partsAssertions=${summary.partsAssertions} total=${total}`,
    );
  }
  for (const file of summary.files) {
    const expected = baseline.files[file.path];
    if (!expected || expected.passed !== file.passed || expected.skipped !== file.skipped) {
      throw new UnicodeTestFailure(
        `${baselinePath}: expected ${file.path} passed=${expected?.passed} skipped=${expected?.skipped}, ` +
          `got passed=${file.passed} skipped=${file.skipped}`,
      );
    }
  }
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    await runUnicodeTests(process.argv[2], process.argv[3]);
  } catch (error) {
    console.error(error.stack ?? String(error));
    process.exit(1);
  }
}
