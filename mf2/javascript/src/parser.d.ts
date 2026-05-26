import type { MF2ParseResult } from "./types.d.ts";

export type {
  MF2AttributeValue,
  MF2Attributes,
  MF2Declaration,
  MF2Expression,
  MF2FunctionRef,
  MF2InputDeclaration,
  MF2Literal,
  MF2LiteralOrVariable,
  MF2LocalDeclaration,
  MF2Markup,
  MF2MarkupKind,
  MF2Message,
  MF2Options,
  MF2ParseDiagnostic,
  MF2ParseResult,
  MF2PatternMessage,
  MF2Pattern,
  MF2PatternPart,
  MF2SelectMessage,
  MF2Variable,
  MF2Variant,
  MF2VariantKey,
} from "./types.d.ts";

export function parseToModel(source: unknown): MF2ParseResult;
