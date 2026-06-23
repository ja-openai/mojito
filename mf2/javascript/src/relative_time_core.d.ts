import type { MF2Formatter } from "./types.d.ts";

export type RelativeTimeCoreStyle = "long" | "short" | "narrow";
export type RelativeTimeCoreNumeric = "always" | "auto";
export type RelativeTimeCorePolicy = "precise" | "compact" | "chat";
export type RelativeTimeCoreUnit =
  | "auto"
  | "second"
  | "minute"
  | "hour"
  | "day"
  | "week"
  | "month"
  | "quarter"
  | "year";

export interface RelativeTimePatternSet {
  id: string;
  data: Record<string, Record<string, Record<string, unknown>>>;
}

export interface RelativeTimeDataResource {
  localeMap: Record<string, string>;
  patternSets: RelativeTimePatternSet[];
}

export interface RelativeTimeCoreOptions {
  data: RelativeTimeDataResource;
  locale?: string;
  style?: RelativeTimeCoreStyle;
  numeric?: RelativeTimeCoreNumeric;
  policy?: RelativeTimeCorePolicy;
  unit?: RelativeTimeCoreUnit;
}

export class RelativeTimeCoreError extends Error {
  readonly code: string;
  constructor(code: string, message: string);
}

export function formatRelativeTimeCore(value: unknown, options: RelativeTimeCoreOptions): string;

export function formatRelativeTimeCoreToParts(
  value: unknown,
  options: RelativeTimeCoreOptions,
): Array<{ type: "text"; value: string }>;

export function createRelativeTimeCoreFunctionRegistry<
  T extends { withFunction(name: string, formatter: MF2Formatter): T },
>(FunctionRegistry: { portable(): T }, data: RelativeTimeDataResource): T;
