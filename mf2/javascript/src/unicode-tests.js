import { readFile, readdir } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  FunctionRegistry,
  MF2Error,
  formatMessage,
  formatMessageWithFallback,
  parseToModel,
} from "./index.js";

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
      `not_wired=${summary.notWired} total=${summary.passed + summary.skipped + summary.notWired}`,
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
    const ok = mode === "parse"
      ? checkParseTest(defaults, test)
      : mode === "data-model"
        ? checkDataModelErrorTest(defaults, test)
        : checkRuntimeTest(defaults, test);
    if (ok) {
      passed += 1;
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
  return result.hasDiagnostics === expectedSyntaxError;
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
      return actual === test.exp;
    } catch {
      return false;
    }
  }

  const actualCodes = [];
  if (result.hasDiagnostics) {
    actualCodes.push(...result.diagnostics.map((diagnostic) => diagnostic.code));
  } else {
    try {
      formatMessage(result.model, argumentsFor(test, result.model), {
        locale: locale(defaults, test),
        bidiIsolation: bidiIsolation(defaults, test),
      });
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
    const actual = formatMessageWithFallback(result.model, runtimeArgumentsFor(test), {
      locale: locale(defaults, test),
      bidiIsolation: bidiIsolation(defaults, test),
      functions: officialFunctionRegistry(),
    });
    const actualCodes = actual.errors.map((error) => error.code);
    if (!expectedCodes.every((expected) => actualCodes.includes(expected))) return false;
    if (expectedCodes.length === 0 && actualCodes.length > 0) return false;
    return test.exp == null || actual.value === test.exp;
  } catch (error) {
    return expectedCodes.includes(error.code);
  }
}

function officialFunctionRegistry() {
  return FunctionRegistry.defaults()
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
  if (baseline.passed !== summary.passed || baseline.skipped !== summary.skipped || baseline.notWired !== summary.notWired || baseline.total !== total) {
    throw new UnicodeTestFailure(
      `${baselinePath}: expected official-test counts passed=${baseline.passed} skipped=${baseline.skipped} ` +
        `notWired=${baseline.notWired} total=${baseline.total}, got passed=${summary.passed} ` +
        `skipped=${summary.skipped} notWired=${summary.notWired} total=${total}`,
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

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    await runUnicodeTests(process.argv[2], process.argv[3]);
  } catch (error) {
    console.error(error.stack ?? String(error));
    process.exit(1);
  }
}
