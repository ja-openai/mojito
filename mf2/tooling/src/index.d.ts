import type { MF2Message } from "@mojito-mf2/core";
export type ValueType =
  | "string"
  | "number"
  | "boolean"
  | "bigint"
  | "date"
  | "null"
  | "undefined";
export type OptionSignature = {
  types: ValueType[];
  values?: string[];
  required?: boolean;
};
export type FunctionSignature = {
  input: ValueType[];
  options?: Record<string, OptionSignature>;
  selector?: "string" | "plural" | "exact";
};
export type Limits = {
  sourceLength: number;
  declarations: number;
  selectors: number;
  variants: number;
  parts: number;
  markupDepth: number;
  catalogMessages: number;
  catalogLength: number;
  diagnostics: number;
};
export type Options = {
  profile?: "react" | "model";
  functions?: Record<string, FunctionSignature>;
  limits?: Partial<Limits>;
  targetLocale?: string;
};
export type Diagnostic = {
  code: string;
  severity: "error" | "warning";
  message: string;
  key?: string;
  side?: "source" | "target";
  location?: string;
  start?: number;
  end?: number;
};
export type MessageContract = {
  version: 1;
  profile: "react" | "model";
  inputs: Record<string, ValueType[]>;
  bindings: Record<string, Array<"open" | "close" | "standalone">>;
  declarations: MF2Message["declarations"];
  functions: string[];
  selectors: Array<{
    name: string;
    function: object | null;
    mode: string | null;
  }>;
  branches: Array<{
    keys: Array<string | null>;
    markup: string[];
    hierarchy: string[] | null;
    functions: string[];
  }>;
};
export type AnalysisResult = {
  ok: boolean;
  model: MF2Message | null;
  contract: MessageContract | null;
  diagnostics: Diagnostic[];
};
export const contractVersion: 1;
export const defaultLimits: Readonly<Limits>;
export function analyzeMessage(
  source: unknown,
  options?: Options,
): AnalysisResult;
export function analyzeModel(model: unknown, options?: Options): AnalysisResult;
export function checkContracts(
  source: MessageContract,
  target: MessageContract,
  options?: Options,
): { ok: boolean; diagnostics: Diagnostic[] };
export function checkTranslation(
  source: unknown,
  target: unknown,
  options?: Options,
): {
  ok: boolean;
  diagnostics: Diagnostic[];
  source: AnalysisResult;
  target: AnalysisResult;
};
export function checkCatalog(
  source: unknown,
  target: unknown,
  options?: Options,
): {
  ok: boolean;
  diagnostics: Diagnostic[];
};
export type CompiledCatalog = {
  format: "mojito-mf2-catalog";
  version: 1;
  profile: "react" | "model";
  messages: Record<string, MF2Message>;
};
export function compileCatalog(
  catalog: unknown,
  options?: Options,
): {
  ok: boolean;
  diagnostics: Diagnostic[];
  artifact: CompiledCatalog | null;
  contracts: Record<string, MessageContract> | null;
};
export function generateTypes(
  catalog: unknown,
  options: Options & { resource: string },
): {
  ok: boolean;
  diagnostics: Diagnostic[];
  code: string | null;
};

export function checkMarkupPatterns(
  source: unknown,
  target: unknown,
  options?: Options,
): { ok: boolean; diagnostics: Diagnostic[] };
