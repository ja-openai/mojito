declare module "@mojito-mf2/core" {
  export function formatMessageToPartsWithFallback(
    model: unknown,
    args: Record<string, unknown>,
    options?: { bidiIsolation?: string; locale?: string },
  ): { errors?: Array<unknown>; parts?: Array<unknown> };

  export function parseToModel(source: string): {
    diagnostics?: Array<Record<string, unknown>>;
    model?: unknown;
  };

  export function partsToString(parts: Array<unknown>, bidiIsolation?: string): string;
}
