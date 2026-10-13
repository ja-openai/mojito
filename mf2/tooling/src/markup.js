import { canonical, limitsFor } from "./analyze.js";
import { normalizeModel } from "./normalize.js";
import { reporter } from "./diagnostics.js";
import { validateModelData } from "./model-data.js";

// Shared by the full message analyzer and TMS pattern-only comparisons. Text
// and expressions do not affect the protected markup structure.
export function markupPatternContract(pattern, options, error) {
  const limits = limitsFor(options);
  const profile = options.profile ?? "model";
  if (!Array.isArray(pattern) || pattern.length > limits.parts) {
    error(
      "markup-pattern",
      "Markup pattern must be an array within the part budget.",
    );
    return { hierarchy: null, markup: [] };
  }
  const tree = [{ children: [] }];
  const markup = [];
  let balanced = true;
  let characters = 0;
  let metadata = 0;
  for (const part of pattern) {
    if (typeof part === "string" || part?.type === "expression") continue;
    const location = "markup";
    const record = (value) =>
      value && typeof value === "object" && !Array.isArray(value);
    const arg = (value) =>
      record(value) &&
      ((value.type === "variable" && typeof value.name === "string") ||
        (value.type === "literal" && typeof value.value === "string"));
    const validMetadata = (value, attributes) =>
      value === undefined ||
      (record(value) &&
        Object.entries(value).every(([key, value]) => {
          metadata++;
          characters +=
            key.length +
            (value === true ? 0 : (value?.name ?? value?.value ?? "").length);
          return (
            (attributes && value === true) ||
            (arg(value) && (!attributes || value.type === "literal"))
          );
        }));
    if (
      !record(part) ||
      part.type !== "markup" ||
      typeof part.name !== "string" ||
      !["open", "close", "standalone"].includes(part.kind) ||
      !validMetadata(part.options, false) ||
      !validMetadata(part.attributes, true)
    ) {
      error("markup-pattern", "Pattern contains malformed markup.", location);
      continue;
    }
    characters += part.name.length;
    if (metadata > limits.parts || characters > limits.sourceLength * 8) {
      error(
        "markup-limit",
        "Markup metadata exceeds the part or text budget.",
        location,
      );
      break;
    }
    // Pick model fields before normalization; unrelated application metadata is
    // never traversed, executed or interpreted as markup.
    const item = normalizeModel(
      {
        type: "markup",
        kind: part.kind,
        name: part.name,
        ...(part.options === undefined ? {} : { options: part.options }),
        ...(part.attributes === undefined
          ? {}
          : { attributes: part.attributes }),
      },
      error,
    );
    if (
      profile === "react" &&
      (Object.keys(item.options ?? {}).length ||
        Object.keys(item.attributes ?? {}).length)
    )
      error(
        "unsupported-react-markup-metadata",
        `React markup ${item.name} supports names only; keep properties in application callbacks.`,
        location,
      );
    if (item.kind === "open") {
      if (tree.length > limits.markupDepth) {
        error("markup-depth", "Markup nesting exceeds the budget.", location);
        return { hierarchy: null, markup };
      }
      const node = { open: item, children: [] };
      tree.at(-1).children.push(node);
      tree.push(node);
    } else if (item.kind === "close") {
      if (tree.length === 1 || tree.at(-1).open.name !== item.name) {
        balanced = false;
        if (profile === "react")
          error(
            "markup-nesting",
            `Unmatched closing markup ${item.name}.`,
            location,
          );
      } else {
        tree.at(-1).close = item;
        tree.pop();
      }
    } else tree.at(-1).children.push({ standalone: item });
    markup.push(canonical(item));
  }
  if (tree.length > 1) {
    balanced = false;
    if (profile === "react")
      error("markup-nesting", `Unclosed markup ${tree.at(-1).open.name}.`);
  }
  function hierarchy(nodes) {
    return nodes
      .map((node) =>
        node.standalone
          ? canonical(node)
          : `{"open":${canonical(node.open)},"close":${canonical(node.close)},"children":[${hierarchy(node.children).join(",")}]}`,
      )
      .sort();
  }
  return {
    hierarchy: balanced ? hierarchy(tree[0].children) : null,
    markup: balanced ? markup.sort() : markup,
  };
}

export function checkMarkupPatterns(source, target, options = {}) {
  const diagnostics = [];
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  const error = (code, message, location) =>
    report({
      code,
      severity: "error",
      message,
      ...(location ? { location } : {}),
    });
  try {
    validateModelData(source, limitsFor(options));
    validateModelData(target, limitsFor(options));
  } catch (cause) {
    error("markup-pattern", cause.message);
    return { ok: false, diagnostics };
  }
  const left = markupPatternContract(source, options, error);
  const right = markupPatternContract(target, options, error);
  if (canonical(left) !== canonical(right))
    error(
      "markup-contract",
      "Translation changes markup nesting, multiplicity, sequence of unpaired markers or protected metadata.",
    );
  return { ok: !diagnostics.length, diagnostics };
}
