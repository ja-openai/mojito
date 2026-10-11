// Explicit build/preview entrypoint. Never imported by the production renderer.
import { parseToModel } from "@mojito-mf2/core/parser";
import { validateMessageMarkup } from "./markup.js";

export class MF2ParseError extends SyntaxError {
  constructor(diagnostics) {
    const details = diagnostics
      .map(
        ({ code, message, start, end }) =>
          `${code} at ${start}-${end}: ${message}`,
      )
      .join("; ");
    super(details ? `Invalid MF2 message: ${details}` : "Invalid MF2 message");
    this.name = "MF2ParseError";
    this.diagnostics = diagnostics;
  }
}

export function parseMessage(source, { allowedMarkup = [] } = {}) {
  const result = parseToModel(source);
  if (result.hasDiagnostics || !result.model) {
    throw new MF2ParseError(result.diagnostics);
  }
  validateMessageMarkup(result.model, allowedMarkup);
  return result.model;
}
