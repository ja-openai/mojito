#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const conformanceRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(conformanceRoot, "../..");
const manifest = readJson(path.join(conformanceRoot, "manifest.json"));
const expectations = readJson(
  path.join(conformanceRoot, "formatjs_parser_expectations.json"),
);
if (expectations.schemaVersion !== 1) {
  throw new Error(
    `Unsupported FormatJS expectation schema ${String(expectations.schemaVersion)}`,
  );
}
const EXPECTED_PACKAGE = expectations.parser.package;
const EXPECTED_VERSION = expectations.parser.version;
const PARSER_OPTIONS = Object.freeze(expectations.options);
const EXPECTED_ERROR_KIND_BY_CASE_SIDE = Object.freeze(
  expectations.errorKindByCaseSide,
);
const EXPECTED_POLICY_DIFFERENCE_BY_CASE_SIDE = Object.freeze(
  expectations.policyDifferenceByCaseSide,
);
const packageLock = readJson(
  path.join(repositoryRoot, "webapp/frontend/package-lock.json"),
);
const lockedVersion =
  packageLock.packages?.[`node_modules/${EXPECTED_PACKAGE}`]?.version;

if (lockedVersion !== EXPECTED_VERSION) {
  throw new Error(
    `${EXPECTED_PACKAGE} must be locked to ${EXPECTED_VERSION}; found ${String(lockedVersion)}`,
  );
}

const moduleRoot = findModuleRoot(process.argv[2]);
const parserPackageRoot = path.join(
  moduleRoot,
  "@formatjs/icu-messageformat-parser",
);
const installedPackage = readJson(path.join(parserPackageRoot, "package.json"));
if (installedPackage.version !== EXPECTED_VERSION) {
  throw new Error(
    `${EXPECTED_PACKAGE} ${EXPECTED_VERSION} is required; installed version is ` +
      `${String(installedPackage.version)}`,
  );
}
for (const [dependencyPackage, dependencyVersion] of Object.entries(
  expectations.parser.dependencies,
)) {
  const declaredVersion = installedPackage.dependencies?.[dependencyPackage];
  const lockedDependencyVersion =
    packageLock.packages?.[`node_modules/${dependencyPackage}`]?.version;
  const installedDependency = readJson(
    path.join(moduleRoot, dependencyPackage, "package.json"),
  );
  if (
    declaredVersion !== dependencyVersion ||
    lockedDependencyVersion !== dependencyVersion ||
    installedDependency.version !== dependencyVersion
  ) {
    throw new Error(
      `${dependencyPackage} must be declared, locked, and installed at ` +
        `${dependencyVersion}; found declaration ${String(declaredVersion)}, ` +
        `lock ${String(lockedDependencyVersion)}, and install ` +
        `${String(installedDependency.version)}`,
    );
  }
}

const { parse } = await import(
  pathToFileURL(path.join(parserPackageRoot, "index.js"))
);
const formatJsCases = manifest.cases
  .filter((testCase) => testCase.profile === "formatjs")
  .toSorted((left, right) => compareUnicode(left.id, right.id));

const results = [];
for (const testCase of formatJsCases) {
  const sourceSyntaxDiagnostic = findSyntaxDiagnostic(testCase, "source");
  for (const side of ["source", "target"]) {
    const caseSide = `${testCase.id}/${side}`;
    const expected = expectedSyntax(testCase, side, sourceSyntaxDiagnostic);
    const actual = parseMessage(
      testCase[side].text,
      EXPECTED_POLICY_DIFFERENCE_BY_CASE_SIDE[caseSide] ?? null,
    );
    results.push({
      caseId: testCase.id,
      side,
      expected,
      actual,
      comparison: compareResult(caseSide, expected, actual),
    });
  }
}

const mismatches = results.filter((result) => result.comparison === "mismatch");
const observedErrorKinds = collectErrorKindsByReason(results);
const corpusErrorKindCaseSides = results
  .filter((result) => result.expected.outcome === "invalid")
  .map((result) => `${result.caseId}/${result.side}`)
  .toSorted(compareUnicode);
const expectedErrorKindCaseSides = Object.keys(
  EXPECTED_ERROR_KIND_BY_CASE_SIDE,
).toSorted(compareUnicode);
const errorKindCoverageMatches =
  JSON.stringify(corpusErrorKindCaseSides) ===
  JSON.stringify(expectedErrorKindCaseSides);
const corpusPolicyDifferenceCaseSides = results
  .filter((result) => result.expected.outcome === "policy-invalid")
  .map((result) => `${result.caseId}/${result.side}`)
  .toSorted(compareUnicode);
const expectedPolicyDifferenceCaseSides = Object.keys(
  EXPECTED_POLICY_DIFFERENCE_BY_CASE_SIDE,
).toSorted(compareUnicode);
const maxDepthPolicyCoverageMatches =
  JSON.stringify(corpusPolicyDifferenceCaseSides) ===
  JSON.stringify(expectedPolicyDifferenceCaseSides);
const report = {
  oracle: {
    package: EXPECTED_PACKAGE,
    version: EXPECTED_VERSION,
    dependencies: expectations.parser.dependencies,
    options: PARSER_OPTIONS,
    scope: "parser-compatibility",
    parsesUnassertedMessages: true,
  },
  corpus: {
    schemaVersion: manifest.schemaVersion,
    corpusVersion: manifest.corpusVersion,
  },
  summary: summarize(
    results,
    mismatches,
    errorKindCoverageMatches,
    maxDepthPolicyCoverageMatches,
  ),
  expectedErrorKindByCaseSide: EXPECTED_ERROR_KIND_BY_CASE_SIDE,
  observedErrorKindsByNormalizedReason: observedErrorKinds,
  results,
};

process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
if (
  mismatches.length > 0 ||
  !errorKindCoverageMatches ||
  !maxDepthPolicyCoverageMatches
) {
  process.exitCode = 1;
}

function readJson(filename) {
  return JSON.parse(readFileSync(filename, "utf8"));
}

function findModuleRoot(explicitModuleRoot) {
  if (explicitModuleRoot) {
    return findInstalledModuleRoot([path.resolve(explicitModuleRoot)]);
  }

  const candidates = [];
  candidates.push(path.join(repositoryRoot, "webapp/frontend/node_modules"));

  const commonDirectory = spawnSync(
    "git",
    ["rev-parse", "--path-format=absolute", "--git-common-dir"],
    { cwd: repositoryRoot, encoding: "utf8" },
  );
  if (commonDirectory.status === 0) {
    candidates.push(
      path.join(
        path.dirname(commonDirectory.stdout.trim()),
        "webapp/frontend/node_modules",
      ),
    );
  }

  return findInstalledModuleRoot(candidates);
}

function findInstalledModuleRoot(candidates) {
  const uniqueCandidates = [...new Set(candidates)];
  for (const candidate of uniqueCandidates) {
    try {
      const installed = readJson(
        path.join(candidate, "@formatjs/icu-messageformat-parser/package.json"),
      );
      if (installed.version === EXPECTED_VERSION) {
        return candidate;
      }
    } catch {
      // Try the next existing dependency installation.
    }
  }

  throw new Error(
    `Could not find ${EXPECTED_PACKAGE} ${EXPECTED_VERSION} in: ` +
      uniqueCandidates.join(", "),
  );
}

function findSyntaxDiagnostic(testCase, side) {
  const code = `${side}-format-invalid`;
  return (
    testCase.expected.diagnostics.find(
      (diagnostic) => diagnostic.code === code,
    ) ?? null
  );
}

function expectedSyntax(testCase, side, sourceSyntaxDiagnostic) {
  const caseSide = `${testCase.id}/${side}`;
  if (!testCase.rules.includes("message-syntax")) {
    return {
      outcome: "not-declared",
      normalizedReason: null,
      maxNestingDepth: null,
      range: null,
    };
  }

  if (side === "target" && sourceSyntaxDiagnostic !== null) {
    return {
      outcome: "source-dominated",
      normalizedReason: null,
      maxNestingDepth: null,
      range: null,
    };
  }

  const diagnostic = findSyntaxDiagnostic(testCase, side);
  if (diagnostic === null) {
    return {
      outcome: "valid",
      normalizedReason: null,
      maxNestingDepth: null,
      range: null,
    };
  }

  const normalizedReason = diagnostic.details.reason;
  const maxNestingDepth = testCase.policy?.maxNestingDepth ?? null;
  if (normalizedReason === "maximum-nesting-depth") {
    if (!Number.isInteger(maxNestingDepth)) {
      throw new Error(
        `${testCase.id}/${side}: maximum-nesting-depth requires maxNestingDepth policy`,
      );
    }
    if (
      EXPECTED_POLICY_DIFFERENCE_BY_CASE_SIDE[caseSide]?.maxNestingDepth !==
      maxNestingDepth
    ) {
      throw new Error(
        `${caseSide}: unexpected maximum-nesting-depth policy ${maxNestingDepth}`,
      );
    }
    return {
      outcome: "policy-invalid",
      normalizedReason,
      maxNestingDepth,
      range: diagnostic.range ?? null,
    };
  }

  return {
    outcome: "invalid",
    normalizedReason,
    maxNestingDepth: null,
    range: diagnostic.range ?? null,
  };
}

function parseMessage(message, policyExpectation) {
  try {
    const elements = parse(message, PARSER_OPTIONS);
    return {
      accepted: true,
      errorName: null,
      errorKind: null,
      utf16Range: null,
      range: null,
      policyNestingDepth:
        policyExpectation === null
          ? null
          : measurePolicyNestingDepth(elements, policyExpectation.measurement),
    };
  } catch (error) {
    const utf16Range = getUtf16Range(error);
    return {
      accepted: false,
      errorName: error instanceof Error ? error.name : typeof error,
      errorKind: error instanceof Error ? error.message : String(error),
      utf16Range,
      range:
        utf16Range === null
          ? null
          : {
              start: codePointOffset(message, utf16Range.start),
              end: codePointOffset(message, utf16Range.end),
            },
      policyNestingDepth: null,
    };
  }
}

function measurePolicyNestingDepth(elements, measurement) {
  switch (measurement) {
    case "select-elements":
      return maxSelectNestingDepth(elements);
    case "simple-style-braces":
      return maxSimpleStyleNestingDepth(elements);
    default:
      throw new Error(`Unknown policy depth measurement: ${measurement}`);
  }
}

function maxSelectNestingDepth(elements) {
  let maximum = 0;
  for (const element of elements) {
    let nested = 0;
    if (element.options !== undefined) {
      for (const option of Object.values(element.options)) {
        nested = Math.max(nested, maxSelectNestingDepth(option.value));
      }
    }
    maximum = Math.max(maximum, nested + (element.type === 5 ? 1 : 0));
  }
  return maximum;
}

function maxSimpleStyleNestingDepth(elements) {
  let maximum = 0;
  for (const element of elements) {
    if ([2, 3, 4].includes(element.type) && typeof element.style === "string") {
      // FormatJS keeps nested opening braces in a simple style, while their
      // closing braces terminate the argument and subsequent literal text.
      const nestedBraces = [...element.style].filter(
        (character) => character === "{",
      ).length;
      maximum = Math.max(maximum, 1 + nestedBraces);
    }
    if (element.options !== undefined) {
      for (const option of Object.values(element.options)) {
        maximum = Math.max(maximum, maxSimpleStyleNestingDepth(option.value));
      }
    }
  }
  return maximum;
}

function getUtf16Range(error) {
  const start = error?.location?.start?.offset;
  const end = error?.location?.end?.offset;
  return Number.isInteger(start) && Number.isInteger(end)
    ? { start, end }
    : null;
}

function codePointOffset(value, utf16Offset) {
  if (utf16Offset < 0 || utf16Offset > value.length) {
    throw new Error(`Invalid UTF-16 offset ${utf16Offset}`);
  }
  if (
    utf16Offset > 0 &&
    utf16Offset < value.length &&
    /[\uD800-\uDBFF]/u.test(value[utf16Offset - 1]) &&
    /[\uDC00-\uDFFF]/u.test(value[utf16Offset])
  ) {
    throw new Error(`UTF-16 offset ${utf16Offset} splits a surrogate pair`);
  }
  return Array.from(value.slice(0, utf16Offset)).length;
}

function compareResult(caseSide, expected, actual) {
  // Every message is parsed, including unasserted target sides. Parser crashes
  // are oracle failures; ordinary SyntaxErrors remain observable results.
  if (!actual.accepted && actual.errorName !== "SyntaxError") {
    return "mismatch";
  }
  switch (expected.outcome) {
    case "valid":
      return actual.accepted ? "match" : "mismatch";
    case "invalid":
      return !actual.accepted &&
        EXPECTED_ERROR_KIND_BY_CASE_SIDE[caseSide] === actual.errorKind &&
        (expected.range === null ||
          JSON.stringify(expected.range) === JSON.stringify(actual.range))
        ? "match"
        : "mismatch";
    case "policy-invalid":
      return actual.accepted &&
        EXPECTED_POLICY_DIFFERENCE_BY_CASE_SIDE[caseSide]?.maxNestingDepth ===
          expected.maxNestingDepth &&
        actual.policyNestingDepth ===
          EXPECTED_POLICY_DIFFERENCE_BY_CASE_SIDE[caseSide]?.fixtureNestingDepth
        ? "intentional-policy-difference"
        : "mismatch";
    case "not-declared":
      return "not-compared-rule-not-declared";
    case "source-dominated":
      return "not-compared-source-dominance";
    default:
      throw new Error(`Unknown expected outcome: ${String(expected.outcome)}`);
  }
}

function summarize(
  allResults,
  mismatches,
  errorKindCoverageMatches,
  maxDepthPolicyCoverageMatches,
) {
  const comparisonCounts = countValues(
    allResults.map((result) => result.comparison),
  );
  const notCompared = allResults.filter((result) =>
    result.comparison.startsWith("not-compared-"),
  );
  return {
    formatJsCases: formatJsCases.length,
    parsedMessages: allResults.length,
    directMatches: comparisonCounts.match ?? 0,
    intentionalMaxDepthPolicyDifferences:
      comparisonCounts["intentional-policy-difference"] ?? 0,
    notComparedRuleNotDeclared:
      comparisonCounts["not-compared-rule-not-declared"] ?? 0,
    notComparedSourceDominance:
      comparisonCounts["not-compared-source-dominance"] ?? 0,
    notComparedAccepted: notCompared.filter((result) => result.actual.accepted)
      .length,
    notComparedRejected: notCompared.filter((result) => !result.actual.accepted)
      .length,
    errorKindCoverageMatches,
    maxDepthPolicyCoverageMatches,
    unexpectedMismatches: mismatches.length,
  };
}

function collectErrorKindsByReason(allResults) {
  const kindsByReason = new Map();
  for (const result of allResults) {
    if (
      result.expected.normalizedReason === null ||
      result.actual.errorKind === null
    ) {
      continue;
    }
    const kinds =
      kindsByReason.get(result.expected.normalizedReason) ?? new Set();
    kinds.add(result.actual.errorKind);
    kindsByReason.set(result.expected.normalizedReason, kinds);
  }

  return Object.fromEntries(
    [...kindsByReason.entries()]
      .toSorted(([left], [right]) => compareUnicode(left, right))
      .map(([reason, kinds]) => [reason, [...kinds].toSorted(compareUnicode)]),
  );
}

function countValues(values) {
  const counts = {};
  for (const value of values) {
    counts[value] = (counts[value] ?? 0) + 1;
  }
  return counts;
}

function compareUnicode(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}
