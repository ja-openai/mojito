import { readFile, readdir } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  MF2Error,
  formatMessageToParts,
  formatMessage,
  parseToModel,
} from "../src/index.js";
import { canonicalLocaleKey, localeLookupChain } from "../src/locale-key.js";

class ConformanceFailure extends Error {}

export async function runConformance(fixtureDir) {
  const sourceDir =
    fixtureDir ??
    resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "conformance", "fixtures", "source-to-model");
  let checkedModels = 0;
  let checkedCases = 0;
  let checkedPartsCases = 0;
  let checkedFallbackCases = 0;
  let checkedFallbackPartsCases = 0;

  for (const fixturePath of await jsonFiles(sourceDir)) {
    const fixture = await readJson(fixturePath);
    const parseResult = parseToModel(fixture.source);
    if (parseResult.hasDiagnostics) {
      throw new ConformanceFailure(`${basename(fixturePath)}: expected parse success, got diagnostics ${JSON.stringify(parseResult.diagnostics)}`);
    }
    assertDeepEqual(fixture.expectedModel, parseResult.model, `${basename(fixturePath)}: parsed model did not match expected model`);
    checkedModels += 1;

    for (const formatCase of fixture.formatCases ?? []) {
      const actual = formatMessage(parseResult.model, formatCase.arguments ?? {}, {
        locale: formatCase.locale ?? "en",
        bidiIsolation: formatCase.bidiIsolation ?? "none",
      });
      if (actual.value !== formatCase.expected || actual.errors.length > 0) {
        throw new ConformanceFailure(`${basename(fixturePath)}: expected ${JSON.stringify(formatCase.expected)}, got ${JSON.stringify(actual.value)} errors=${JSON.stringify(actual.errors)}`);
      }
      checkedCases += 1;
    }

    for (const partsCase of fixture.partsCases ?? []) {
      const actual = formatMessageToParts(parseResult.model, partsCase.arguments ?? {}, {
        locale: partsCase.locale ?? "en",
      });
      assertDeepEqual(partsCase.expected, actual.parts, `${basename(fixturePath)}: parts did not match`);
      if (actual.errors.length > 0) throw new ConformanceFailure(`${basename(fixturePath)}: expected no parts errors, got ${JSON.stringify(actual.errors)}`);
      checkedPartsCases += 1;
    }

    for (const fallbackCase of fixture.fallbackCases ?? []) {
      const actual = formatMessage(parseResult.model, fallbackCase.arguments ?? {}, {
        locale: fallbackCase.locale ?? "en",
        bidiIsolation: fallbackCase.bidiIsolation ?? "none",
      });
      if (actual.value !== fallbackCase.expected) {
        throw new ConformanceFailure(`${basename(fixturePath)}: expected fallback ${JSON.stringify(fallbackCase.expected)}, got ${JSON.stringify(actual.value)}`);
      }
      assertErrorCodes(fixturePath, "fallback errors", actual.errors, fallbackCase);
      checkedFallbackCases += 1;
    }

    for (const partsCase of fixture.fallbackPartsCases ?? []) {
      const actual = formatMessageToParts(parseResult.model, partsCase.arguments ?? {}, {
        locale: partsCase.locale ?? "en",
      });
      assertDeepEqual(partsCase.expected, actual.parts, `${basename(fixturePath)}: fallback parts did not match`);
      assertErrorCodes(fixturePath, "fallback parts errors", actual.errors, partsCase);
      checkedFallbackPartsCases += 1;
    }
  }

  const fixtureRoot = dirname(sourceDir);
  return {
    checkedModels,
    checkedCases,
    checkedPartsCases,
    checkedFallbackCases,
    checkedFallbackPartsCases,
    checkedInvalidSources: await checkInvalidSourceFixtures(fixtureRoot),
    checkedErrorCases: await checkFormatErrorFixtures(fixtureRoot),
    checkedLocaleKeyCases: await checkLocaleKeyFixtures(fixtureRoot),
  };
}

async function checkInvalidSourceFixtures(fixtureRoot) {
  const fixtureDir = join(fixtureRoot, "invalid-source");
  let checkedCases = 0;
  for (const fixturePath of await jsonFiles(fixtureDir)) {
    const fixture = await readJson(fixturePath);
    const parseResult = parseToModel(fixture.source);
    const actualCodes = (parseResult.diagnostics ?? []).map((diagnostic) => diagnostic.code);
    const expectedCodes = (fixture.expectedDiagnostics ?? []).map((diagnostic) => diagnostic.code);
    assertDeepEqual(expectedCodes, actualCodes, `${basename(fixturePath)}: diagnostics did not match`);
    checkedCases += 1;
  }
  return checkedCases;
}

async function checkFormatErrorFixtures(fixtureRoot) {
  const fixtureDir = join(fixtureRoot, "format-errors");
  let checkedCases = 0;
  for (const fixturePath of await jsonFiles(fixtureDir)) {
    const fixture = await readJson(fixturePath);
    try {
      const actual = formatMessage(fixture.model, fixture.arguments ?? {}, {
        locale: fixture.locale ?? "en",
      });
      const expectedCode = fixture.expectedError.code;
      if (!actual.errors.some((error) => error.code === expectedCode)) {
        throw new ConformanceFailure(`${basename(fixturePath)}: expected error ${expectedCode}, got ${JSON.stringify(actual.errors)}`);
      }
    } catch (error) {
      if (error instanceof ConformanceFailure) throw error;
      if (!(error instanceof MF2Error)) throw error;
      const expectedCode = fixture.expectedError.code;
      if (error.code !== expectedCode) {
        throw new ConformanceFailure(`${basename(fixturePath)}: expected error ${expectedCode}, got ${error.code}`);
      }
    }
    checkedCases += 1;
  }
  return checkedCases;
}

async function checkLocaleKeyFixtures(fixtureRoot) {
  const fixture = await readJson(join(fixtureRoot, "locale-key", "cases.json"));
  let checkedCases = 0;
  for (const item of fixture.canonical ?? []) {
    const actual = canonicalLocaleKey(item.source);
    if (actual !== item.expected) throw new ConformanceFailure(`locale-key: expected canonical ${item.expected}, got ${actual}`);
    checkedCases += 1;
  }
  for (const item of fixture.lookupChains ?? []) {
    const actual = localeLookupChain(item.source);
    assertDeepEqual(item.expected, actual, "locale-key: lookup chain did not match");
    checkedCases += 1;
  }
  return checkedCases;
}

function assertErrorCodes(fixturePath, label, actualErrors, item) {
  const actualCodes = actualErrors.map((error) => error.code);
  const expectedCodes = (item.expectedErrors ?? []).map((error) => error.code);
  assertDeepEqual(expectedCodes, actualCodes, `${basename(fixturePath)}: ${label} did not match`);
}

async function jsonFiles(dir) {
  try {
    return (await readdir(dir, { withFileTypes: true }))
      .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
      .map((entry) => join(dir, entry.name))
      .sort((left, right) => basename(left).localeCompare(basename(right)));
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function assertDeepEqual(expected, actual, message) {
  const expectedJson = stableStringify(expected);
  const actualJson = stableStringify(actual);
  if (expectedJson !== actualJson) {
    throw new ConformanceFailure(`${message}\nexpected: ${expectedJson}\nactual:   ${actualJson}`);
  }
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const result = await runConformance(process.argv[2]);
    console.log(
      "JavaScript MF2 conformance runner passed " +
        `${result.checkedModels} source models, ${result.checkedCases} format cases, ` +
        `${result.checkedPartsCases} parts cases, ${result.checkedFallbackCases} fallback cases, ` +
        `${result.checkedFallbackPartsCases} fallback parts cases, ${result.checkedInvalidSources} invalid source cases, ` +
        `${result.checkedErrorCases} format error cases, and ${result.checkedLocaleKeyCases} locale key cases.`,
    );
  } catch (error) {
    console.error(error.stack ?? String(error));
    process.exit(1);
  }
}
