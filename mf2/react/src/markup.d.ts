import type { MF2Message } from "@mojito-mf2/core";
import type { MF2Part } from "@mojito-mf2/core/formatter";
export function validateMarkupParts(
  parts: MF2Part[],
  allowedNames: string[],
): void;
export function validateMessageMarkup(
  model: MF2Message,
  allowedNames?: string[],
): void;
