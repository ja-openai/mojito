// This is a capability profile for the current JavaScript Intl adapter, not a
// claim that every MF2 implementation provides these functions or options.
const text = { types: ["string"] };
const number = { types: ["number"] };
const enumeration = (...values) => ({ types: ["string"], values });
const common = {
  "u:dir": enumeration("auto", "ltr", "rtl", "inherit"),
  "u:id": text,
};
const numeric = {
  ...common,
  select: enumeration("plural", "ordinal", "exact"),
  minimumFractionDigits: number,
  maximumFractionDigits: number,
  minimumIntegerDigits: number,
  minimumSignificantDigits: number,
  maximumSignificantDigits: number,
  notation: enumeration("standard", "scientific", "engineering", "compact"),
  signDisplay: enumeration("auto", "always", "exceptZero", "negative", "never"),
  style: enumeration("decimal", "percent", "currency"),
};
const temporal = {
  ...common,
  timeZone: text,
  dateStyle: enumeration("full", "long", "medium", "short"),
  timeStyle: enumeration("full", "long", "medium", "short"),
  length: enumeration("full", "long", "medium", "short"),
  dateLength: enumeration("full", "long", "medium", "short"),
  precision: enumeration(
    "hour",
    "minute",
    "second",
    "full",
    "long",
    "medium",
    "short",
  ),
  timePrecision: enumeration(
    "hour",
    "minute",
    "second",
    "full",
    "long",
    "medium",
    "short",
  ),
};
export const intlSignatures = Object.freeze({
  string: { input: ["string"], options: common, selector: "string" },
  number: {
    input: ["number"],
    options: { ...numeric, style: enumeration("decimal", "percent") },
    selector: "plural",
  },
  integer: {
    input: ["number"],
    options: { ...numeric, style: enumeration("decimal") },
    selector: "plural",
  },
  percent: {
    input: ["number"],
    options: { ...numeric, style: enumeration("percent") },
    selector: "plural",
  },
  currency: {
    input: ["number"],
    options: { ...numeric, currency: text, style: enumeration("currency") },
  },
  offset: {
    input: ["number"],
    options: { ...numeric, add: number, subtract: number },
    selector: "plural",
  },
  date: { input: ["date", "string"], options: temporal },
  time: { input: ["date", "string"], options: temporal },
  datetime: { input: ["date", "string"], options: temporal },
  relativeTime: {
    input: ["number"],
    options: {
      ...common,
      unit: {
        ...enumeration(
          "second",
          "minute",
          "hour",
          "day",
          "week",
          "month",
          "quarter",
          "year",
        ),
        required: true,
      },
      numeric: enumeration("always", "auto"),
      style: enumeration("long", "short", "narrow"),
    },
  },
});
export const valueTypes = Object.freeze([
  "string",
  "number",
  "boolean",
  "bigint",
  "date",
  "null",
  "undefined",
]);
export const defaultLimits = Object.freeze({
  sourceLength: 100_000,
  declarations: 128,
  selectors: 8,
  variants: 256,
  parts: 10_000,
  markupDepth: 64,
  catalogMessages: 10_000,
  catalogLength: 10_000_000,
  diagnostics: 100,
});
