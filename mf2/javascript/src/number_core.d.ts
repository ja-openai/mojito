import type { MF2Formatter } from "./types.d.ts";

export interface NumberCoreOptions {
  locale?: string;
  style?: "number" | "integer" | "percent" | "currency";
  currency?: string | null;
  currencyDisplay?: "symbol" | "narrowSymbol" | "code";
  useGrouping?: boolean;
  minimumFractionDigits?: number | string;
  maximumFractionDigits?: number | string;
  signDisplay?: "auto" | "always" | "never";
}

export class NumberCoreError extends Error {
  readonly code: string;
  constructor(code: string, message: string);
}

export function formatNumberCore(value: unknown, options?: NumberCoreOptions): string;

export function formatNumberCoreToParts(
  value: unknown,
  options?: NumberCoreOptions,
): Array<{ type: "text"; value: string }>;

export function createNumberCoreFunctionRegistry<
  T extends { withFunction(name: string, formatter: MF2Formatter): T },
>(FunctionRegistry: { portable(): T }): T;
