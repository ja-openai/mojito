import { MF2Error } from "./errors.js";
import {
  addIntegerOffset,
  isNumericFunction,
  numericSourceOperand,
  parseInteger,
  sourceOptionValue,
} from "./function_support.js";

export function formatOffset(call) {
  const operand = numericSourceOperand(call.inheritedSource) ?? call.rawValue;
  const result = addIntegerOffset(operand, offsetDelta(call));
  if (result == null) throw MF2Error.badOperand("Offset function requires a numeric operand.");
  return signDisplayAlways(call) && !result.startsWith("-") ? `+${result}` : result;
}

function signDisplayAlways(call) {
  const direct = call.optionValue("signDisplay", null);
  return direct == null
    ? inheritedSignDisplayAlways(call.inheritedSource)
    : direct === "always";
}

function inheritedSignDisplayAlways(source) {
  let current = source;
  while (current != null) {
    if (!isNumericFunction(current.function)) return false;
    const value = sourceOptionValue(current, "signDisplay", null);
    if (value != null) return value === "always";
    current = current.inherited;
  }
  return false;
}

function offsetDelta(call) {
  const add = call.optionValue("add", null);
  const subtract = call.optionValue("subtract", null);
  if ((add == null && subtract == null) || (add != null && subtract != null)) throw MF2Error.badOption("Offset function requires exactly one of add or subtract.");
  const value = parseInteger(add ?? subtract);
  if (value == null) throw MF2Error.badOption(add != null ? "Offset add option must be an integer." : "Offset subtract option must be an integer.");
  return add != null ? value : -value;
}

function parseRequiredInteger(value, message) {
  const parsed = parseInteger(value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}
