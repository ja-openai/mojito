import type { MF2Formatter } from "./types.d.ts";

export type DateTimeCoreStyle = "full" | "long" | "medium" | "short";
export type DateTimeCorePrecision = DateTimeCoreStyle | "second";
export type DateTimeCoreHourCycle = "h11" | "h12" | "h23" | "h24";

export interface DateTimeCoreOptions {
  locale?: string;
  style?: DateTimeCoreStyle;
  dateStyle?: DateTimeCoreStyle;
  timeStyle?: DateTimeCoreStyle;
  length?: DateTimeCoreStyle;
  precision?: DateTimeCorePrecision;
  dateLength?: DateTimeCoreStyle;
  timePrecision?: DateTimeCorePrecision;
  skeleton?: string | null;
  hourCycle?: DateTimeCoreHourCycle | null;
  timeZone?: string | null;
  calendar?: string | null;
}

export class DateTimeCoreError extends Error {
  readonly code: string;
  constructor(code: string, message: string);
}

export function formatDateCore(value: unknown, options?: DateTimeCoreOptions): string;

export function formatTimeCore(value: unknown, options?: DateTimeCoreOptions): string;

export function formatDateTimeCore(value: unknown, options?: DateTimeCoreOptions): string;

export function formatDateCoreToParts(
  value: unknown,
  options?: DateTimeCoreOptions,
): Array<{ type: "text"; value: string }>;

export function formatTimeCoreToParts(
  value: unknown,
  options?: DateTimeCoreOptions,
): Array<{ type: "text"; value: string }>;

export function formatDateTimeCoreToParts(
  value: unknown,
  options?: DateTimeCoreOptions,
): Array<{ type: "text"; value: string }>;

export function createDateTimeCoreFunctionRegistry<
  T extends { withFunction(name: string, formatter: MF2Formatter): T },
>(FunctionRegistry: { portable(): T }): T;
