import { parseToModel } from "@mojito-mf2/core/parser";
import { createCompiledMessageCatalog } from "./runtime.js";

export * from "./runtime.js";
export * from "./workbench.js";

export function compileMessageCatalog(sources) {
  const entries = {};
  for (const [id, source] of Object.entries(sources ?? {})) {
    entries[id] = compileMessageEntry(source);
  }
  return entries;
}

export function createMessageCatalog(sources) {
  return createCompiledMessageCatalog(compileMessageCatalog(sources));
}

function compileMessageEntry(source) {
  if (typeof source !== "string") {
    return source;
  }
  const parsed = parseToModel(source);
  return {
    source,
    model: parsed.model,
    diagnostics: parsed.diagnostics,
  };
}
