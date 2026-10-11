import type { MF2Message, MF2ParseDiagnostic } from "@mojito-mf2/core/parser";

export class MF2ParseError extends SyntaxError {
  readonly diagnostics: readonly MF2ParseDiagnostic[];
  constructor(diagnostics: readonly MF2ParseDiagnostic[]);
}

export function parseMessage(
  source: string,
  options?: { allowedMarkup?: string[] },
): MF2Message;
