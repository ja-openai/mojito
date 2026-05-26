import { registerNumericSelectors } from "./numeric_selectors.js";
import { formatOffset } from "./offset_function.js";
import { registerUnlocalizedNumericFormatters } from "./unlocalized_numeric_functions.js";

// Portable here means dependency-free and intentionally not locale-pretty.
// Locale-native formatting belongs in explicit platform adapters such as Intl.
export function createPortableFunctionRegistry(FunctionRegistry) {
  const formatters = new Map();
  const selectors = new Map();
  formatters.set("string", (call) => call.value);
  registerUnlocalizedNumericFormatters(formatters);
  formatters.set("offset", formatOffset);
  registerNumericSelectors(selectors);
  return new FunctionRegistry(formatters, selectors);
}
