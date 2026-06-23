import type {
  MF2Arguments,
  MF2BidiIsolation,
  MF2FormatOptions,
  MF2PartsResult,
  MF2Formatter,
  MF2FunctionRef,
  MF2Message,
  MF2Part,
  MF2PluralCategory,
  MF2Selector,
  MF2FormatResult,
  MF2RecoveryContext,
  MF2Value,
} from "./types.d.ts";

export type {
  MF2Arguments,
  MF2BidiDirection,
  MF2BidiIsolation,
  MF2FallbackPart,
  MF2PartsResult,
  MF2FormatOptions,
  MF2RecoveryContext,
  MF2FormatSource,
  MF2Formatter,
  MF2FormatterCall,
  MF2FunctionRef,
  MF2MarkupPart,
  MF2Part,
  MF2PluralCategory,
  MF2Selector,
  MF2SelectorMatch,
  MF2FormatResult,
  MF2TextPart,
  MF2Value,
} from "./types.d.ts";

export class FunctionRegistry {
  constructor(
    formatters?: Map<string, MF2Formatter> | Iterable<readonly [string, MF2Formatter]>,
    selectors?: Map<string, MF2Selector> | Iterable<readonly [string, MF2Selector]>,
  );

  static defaults(): FunctionRegistry;
  static portable(): FunctionRegistry;

  withFunction(name: string, formatter: MF2Formatter): FunctionRegistry;
  withSelector(name: string, selector: MF2Selector): FunctionRegistry;
  withRegistry(other: FunctionRegistry): FunctionRegistry;
  hasFormatter(functionRef: MF2FunctionRef): boolean;
  hasSelector(functionRef: MF2FunctionRef): boolean;
  format(call: Parameters<MF2Formatter>[0]): string;
  select(match: Parameters<MF2Selector>[0]): number | null;
}

export function formatMessage(
  model: MF2Message,
  arguments_?: MF2Arguments,
  options?: MF2FormatOptions,
): MF2FormatResult;

export function formatMessageToParts(
  model: MF2Message,
  arguments_?: MF2Arguments,
  options?: MF2FormatOptions,
): MF2PartsResult;

export function partsToString(parts: MF2Part[], bidiIsolation?: MF2BidiIsolation): string;

export function selectPluralCategory(locale: string, value: string | number, select?: "plural" | "ordinal" | "exact"): MF2PluralCategory | null;

export function valueToString(value: MF2Value): string;
