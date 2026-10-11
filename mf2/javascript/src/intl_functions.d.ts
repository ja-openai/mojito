import type { FunctionRegistry } from "./formatter.d.ts";

export function createIntlFunctionRegistry(FunctionRegistryClass: typeof FunctionRegistry, options?: { timeZone?: string }): FunctionRegistry;
