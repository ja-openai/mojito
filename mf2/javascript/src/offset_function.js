import { MF2Error } from "./errors.js";
import { offsetIntegerInRange, parseOffsetInteger, sourceOptionValue } from "./function_support.js";

export function formatOffset(call) {
  const value = parseRequiredInteger(call.rawValue ?? call.value, "Offset function requires a numeric operand.");
  const result = value + offsetDelta(call);
  if (!offsetIntegerInRange(result)) throw MF2Error.badOperand("Offset result is outside the supported integer range.");
  return inheritedSignDisplayAlways(call.inheritedSource) && result >= 0n ? `+${result}` : result.toString();
}

function inheritedSignDisplayAlways(source) {
  if (source == null) return false;
  if ((source.function?.name === "number" || source.function?.name === "integer") && sourceOptionValue(source, "signDisplay", null) === "always") return true;
  return inheritedSignDisplayAlways(source.inherited);
}

function offsetDelta(call) {
  const add = call.optionValue("add", null);
  const subtract = call.optionValue("subtract", null);
  if ((add == null && subtract == null) || (add != null && subtract != null)) throw MF2Error.badOption("Offset function requires exactly one of add or subtract.");
  const value = parseOffsetInteger(add ?? subtract);
  if (value == null) throw MF2Error.badOption(add != null ? "Offset add option must be an integer." : "Offset subtract option must be an integer.");
  return add != null ? value : -value;
}

function parseRequiredInteger(value, message) {
  const parsed = parseOffsetInteger(value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}
