import {
  analyzeMessage,
  canonical,
  contractVersion,
  isCategory,
  limitsFor,
} from "./analyze.js";
export { analyzeMessage, analyzeModel, contractVersion } from "./analyze.js";
export { defaultLimits } from "./signatures.js";
export { checkMarkupPatterns } from "./markup.js";
import { reporter } from "./diagnostics.js";

const diagnostic = (code, message, extra = {}) => ({
  code,
  severity: "error",
  message,
  ...extra,
});
const finished = (diagnostics, extra = {}) => ({
  ok: !diagnostics.some((d) => d.severity === "error"),
  diagnostics,
  ...extra,
});
const pluralBranch = (key, selector) =>
  selector.mode === "plural" && key !== null && isCategory(key);

export function checkContracts(source, target, options = {}) {
  const diagnostics = [];
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  if (
    source.version !== contractVersion ||
    target.version !== contractVersion ||
    source.profile !== target.profile
  )
    return finished([
      diagnostic(
        "contract-version-profile",
        "Contracts require matching supported versions and capability profiles.",
      ),
    ]);
  const compare = (field, code, message) => {
    if (canonical(source[field]) !== canonical(target[field]))
      report(diagnostic(code, message));
  };
  compare(
    "inputs",
    "argument-contract",
    "Translation changes external argument names or their function constraints.",
  );
  compare(
    "declarations",
    "declaration-contract",
    "Translation changes input/local declarations; preserve their behavior and options.",
  );
  compare(
    "selectors",
    "selector-contract",
    "Translation changes selector identity, order, function or selection mode.",
  );
  if (diagnostics.some((d) => d.code === "selector-contract"))
    return finished(diagnostics);
  // Plural categories may disappear in a target locale. Per-branch checks
  // below protect uses without requiring vanished branches' global inventory.
  if (!source.selectors.some((selector) => selector.mode === "plural")) {
    compare(
      "bindings",
      "markup-bindings",
      "Translation changes named markup bindings or their open/close/standalone forms.",
    );
    compare(
      "functions",
      "function-contract",
      "Translation changes function uses, operands, attributes or protected options.",
    );
  }
  for (let index = 0; index < source.selectors.length; index++) {
    const selector = source.selectors[index];
    const fixedKeys = (contract) =>
      [
        ...new Set(
          contract.branches
            .map((branch) => branch.keys[index])
            .filter((key) => key !== null && !pluralBranch(key, selector)),
        ),
      ].sort();
    if (canonical(fixedKeys(source)) !== canonical(fixedKeys(target)))
      report(
        diagnostic(
          "selector-keys",
          `Translation changes exact keys for selector $${selector.name}.`,
        ),
      );
    if (selector.mode !== "plural") continue;
    if (!options.targetLocale) {
      report({
        code: "plural-locale-unchecked",
        severity: "warning",
        message: `Pass targetLocale to check plural categories for $${selector.name}.`,
      });
      continue;
    }
    try {
      const categories = new Set(
        new Intl.PluralRules(options.targetLocale, {
          type:
            selector.function.options?.select?.value === "ordinal"
              ? "ordinal"
              : "cardinal",
        }).resolvedOptions().pluralCategories,
      );
      for (const key of new Set(
        target.branches.map((branch) => branch.keys[index]),
      )) {
        if (pluralBranch(key, selector) && !categories.has(key))
          report({
            code: "unused-plural-category",
            severity: "warning",
            message: `${key} is not a plural category for ${options.targetLocale}.`,
          });
      }
    } catch {
      report(
        diagnostic(
          "invalid-locale",
          "targetLocale is not a valid locale identifier.",
        ),
      );
    }
  }
  // Match one authored branch at a time. This does not enumerate the Cartesian
  // product of selector values. New plural categories inherit wildcard markup.
  const sourceBranches = new Map(
    source.branches.map((branch) => [canonical(branch.keys), branch]),
  );
  const targetBranches = new Set(
    target.branches.map((branch) => canonical(branch.keys)),
  );
  for (const [index, targetBranch] of target.branches.entries()) {
    let sourceBranch = sourceBranches.get(canonical(targetBranch.keys));
    if (!sourceBranch) {
      const candidates = source.branches.filter((branch) =>
        branch.keys.every(
          (key, axis) =>
            key === targetBranch.keys[axis] ||
            (key === null &&
              pluralBranch(targetBranch.keys[axis], source.selectors[axis])),
        ),
      );
      candidates.sort((a, b) => {
        for (let axis = 0; axis < a.keys.length; axis++) {
          const difference =
            Number(b.keys[axis] !== null) - Number(a.keys[axis] !== null);
          if (difference) return difference;
        }
        return 0;
      });
      sourceBranch = candidates[0];
    }
    if (!sourceBranch)
      report(
        diagnostic(
          "variant-contract",
          `Target variant ${index} has no compatible source branch.`,
        ),
      );
    else if (
      canonical(sourceBranch.markup) !== canonical(targetBranch.markup) ||
      canonical(sourceBranch.hierarchy) !== canonical(targetBranch.hierarchy)
    )
      report(
        diagnostic(
          "markup-contract",
          `Target variant ${index} changes markup nesting, multiplicity or protected options.`,
        ),
      );
    if (
      sourceBranch &&
      canonical(sourceBranch.functions) !== canonical(targetBranch.functions)
    )
      report(
        diagnostic(
          "branch-function-contract",
          `Target variant ${index} changes expression operands, functions or protected options.`,
        ),
      );
  }
  // Removing a non-plural branch changes selection behavior even if each axis
  // still contains the same keys elsewhere in a multi-selector message.
  for (const branch of source.branches) {
    if (
      branch.keys.some((key, axis) => pluralBranch(key, source.selectors[axis]))
    )
      continue;
    if (!targetBranches.has(canonical(branch.keys)))
      report(
        diagnostic(
          "missing-variant",
          "Translation removes an exact or wildcard source variant.",
        ),
      );
  }
  return finished(diagnostics);
}

export function checkTranslation(source, target, options = {}) {
  const sourceResult = analyzeMessage(source, options);
  const targetResult = analyzeMessage(target, options);
  const diagnostics = [];
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  report(
    ...sourceResult.diagnostics.map((d) => ({ ...d, side: "source" })),
    ...targetResult.diagnostics.map((d) => ({ ...d, side: "target" })),
  );
  if (sourceResult.ok && targetResult.ok)
    report(
      ...checkContracts(sourceResult.contract, targetResult.contract, options)
        .diagnostics,
    );
  return finished(diagnostics, { source: sourceResult, target: targetResult });
}

function catalogEntries(catalog, options, diagnostics) {
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  if (
    !catalog ||
    typeof catalog !== "object" ||
    Array.isArray(catalog) ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(catalog))
  ) {
    report(
      diagnostic(
        "catalog-type",
        "Catalog must be a flat object of message IDs to source strings.",
      ),
    );
    return [];
  }
  const limits = limitsFor(options);
  const keys = Object.keys(catalog).sort();
  if (keys.length > limits.catalogMessages) {
    report(diagnostic("catalog-limit", "Catalog contains too many messages."));
    return [];
  }
  let length = 0;
  const entries = [];
  for (const key of keys) {
    const property = Object.getOwnPropertyDescriptor(catalog, key);
    if (
      !property ||
      !Object.hasOwn(property, "value") ||
      typeof property.value !== "string"
    ) {
      report(
        diagnostic(
          "catalog-entry-type",
          "Catalog values must be source strings, not accessors or nested objects.",
          { key },
        ),
      );
      continue;
    }
    length += key.length + property.value.length;
    if (length > limits.catalogLength) {
      report(
        diagnostic(
          "catalog-limit",
          "Catalog exceeds its aggregate source budget.",
        ),
      );
      return [];
    }
    entries.push([key, property.value]);
  }
  return entries;
}

export function checkCatalog(source, target, options = {}) {
  const diagnostics = [];
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  const sourceDiagnostics = [];
  const targetDiagnostics = [];
  const left = new Map(catalogEntries(source, options, sourceDiagnostics));
  const right = new Map(catalogEntries(target, options, targetDiagnostics));
  report(
    ...sourceDiagnostics.map((d) => ({ ...d, side: "source" })),
    ...targetDiagnostics.map((d) => ({ ...d, side: "target" })),
  );
  for (const [key, value] of left) {
    if (diagnostics.length > limitsFor(options).diagnostics) break;
    if (!right.has(key)) {
      report(
        diagnostic(
          "missing-message",
          "Target catalog is missing a source message.",
          { key },
        ),
      );
      report(
        ...analyzeMessage(value, options).diagnostics.map((d) => ({
          ...d,
          key,
          side: "source",
        })),
      );
    } else
      report(
        ...checkTranslation(value, right.get(key), options).diagnostics.map(
          (d) => ({ ...d, key }),
        ),
      );
  }
  for (const [key, value] of right) {
    if (diagnostics.length > limitsFor(options).diagnostics) break;
    if (!left.has(key)) {
      report(
        diagnostic(
          "extra-message",
          "Target catalog contains a message absent from source.",
          { key },
        ),
      );
      report(
        ...analyzeMessage(value, options).diagnostics.map((d) => ({
          ...d,
          key,
          side: "target",
        })),
      );
    }
  }
  return finished(diagnostics);
}

export function compileCatalog(catalog, options = {}) {
  const diagnostics = [];
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  const entries = catalogEntries(catalog, options, diagnostics);
  const models = [];
  const contracts = [];
  for (const [key, source] of entries) {
    if (diagnostics.length > limitsFor(options).diagnostics) break;
    const result = analyzeMessage(source, options);
    report(...result.diagnostics.map((d) => ({ ...d, key })));
    if (result.ok) {
      models.push([key, result.model]);
      contracts.push([key, result.contract]);
    }
  }
  const result = finished(diagnostics);
  return {
    ...result,
    artifact: result.ok
      ? {
          format: "mojito-mf2-catalog",
          version: contractVersion,
          profile: options.profile ?? "react",
          messages: Object.fromEntries(models),
        }
      : null,
    contracts: result.ok ? Object.fromEntries(contracts) : null,
  };
}

const typescriptType = (types) =>
  types.map((type) => (type === "date" ? "Date" : type)).join(" | ") || "never";
const quote = (value) =>
  JSON.stringify(value)
    .replaceAll("\u2028", "\\u2028")
    .replaceAll("\u2029", "\\u2029");

export function generateTypes(catalog, { resource, ...options } = {}) {
  if (typeof resource !== "string" || !resource)
    throw new TypeError("Type generation requires a resource string.");
  const result = compileCatalog(catalog, options);
  if (!result.ok)
    return { ok: false, diagnostics: result.diagnostics, code: null };
  if ((options.profile ?? "react") !== "react")
    return {
      ok: false,
      code: null,
      diagnostics: [
        diagnostic(
          "typed-react-profile",
          "Generated React bindings require the React capability profile.",
        ),
      ],
    };
  const messages = Object.entries(result.contracts)
    .map(([key, contract]) => {
      const args = Object.entries(contract.inputs)
        .map(
          ([name, types]) => `      ${quote(name)}: ${typescriptType(types)};`,
        )
        .join("\n");
      const markupRenderers = Object.keys(contract.bindings)
        .map(
          (name) => `      ${quote(name)}: (children: ReactNode) => ReactNode;`,
        )
        .join("\n");
      return `  ${quote(key)}: {\n    args: ${args ? `{\n${args}\n    }` : "{}"};\n    markupRenderers: ${markupRenderers ? `{\n${markupRenderers}\n    }` : "{}"};\n  };`;
    })
    .join("\n");
  const reactImport = Object.values(result.contracts).some(
    (contract) => Object.keys(contract.bindings).length,
  )
    ? 'import type { ReactNode } from "react";\n'
    : "";
  const code = `// Generated by @mojito-mf2/tooling. Contract version ${contractVersion}.\n${reactImport}import { createMessages } from "@mojito-mf2/react/bindings";\n\nexport type Messages = {\n${messages}\n};\n\nexport const { MF2, useMF2, bind } = createMessages<Messages>(${quote(resource)});\n`;
  return { ok: true, diagnostics: result.diagnostics, code };
}
