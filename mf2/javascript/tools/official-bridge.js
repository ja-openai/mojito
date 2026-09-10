import { createInterface } from "node:readline";
import { FunctionRegistry, MF2Error, formatMessage, formatMessageToParts, parseToModel } from "../src/index.js";
import { createIntlFunctionRegistry } from "../src/intl_functions.js";
import { officialFunctionRegistry } from "./unicode-tests.js";

// A JSON-lines transport for the shared assertion runner. Only test:* functions
// are injected; standard functions always come from a production registry.
for await (const line of createInterface({ input: process.stdin, crlfDelay: Infinity })) {
  try {
    const request = JSON.parse(line);
    const parsed = parseToModel(request.source);
    if (parsed.hasDiagnostics) {
      console.log(JSON.stringify({ diagnostics: parsed.diagnostics.map(({ code }) => code) }));
      continue;
    }
    const base = request.registry === "platform" ? createIntlFunctionRegistry(FunctionRegistry) : FunctionRegistry.portable();
    const options = { locale: request.locale, bidiIsolation: request.bidiIsolation, functions: officialFunctionRegistry(base) };
    const result = formatMessage(parsed.model, request.arguments, options);
    const parts = formatMessageToParts(parsed.model, request.arguments, options);
    console.log(JSON.stringify({ diagnostics: [], value: result.value, errors: result.errors.map(({ code }) => code), parts: parts.parts, partsErrors: parts.errors.map(({ code }) => code) }));
  } catch (error) {
    console.log(JSON.stringify(error instanceof MF2Error ? { diagnostics: [], errors: [error.code] } : { transportError: String(error.stack ?? error) }));
  }
}
