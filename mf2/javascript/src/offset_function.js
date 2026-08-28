import { MF2Error } from "./errors.js";
import {
  addIntegerOffset,
  isNumericFunction,
  numericSourceOperand,
  parseDecimalNumber,
  parseInteger,
  sourceOptionValue,
} from "./function_support.js";

export function formatOffset(call) {
  const operand = numericSourceOperand(call.inheritedSource) ?? String(call.value);
  if (parseDecimalNumber(operand) == null && parseInteger(operand) == null) {
    throw MF2Error.badOperand("Offset function requires a numeric operand.");
  }
  const result = addIntegerOffset(operand, offsetDelta(call));
  if (result == null) throw MF2Error.badOperand("Offset function requires a numeric operand.");
  const numericResult = parseDecimalNumber(result);
  return signDisplayAlways(call) && numericResult >= 0 ? `+${result}` : result;
}

function signDisplayAlways(call) {
  const direct = call.optionValue("signDisplay", null);
  return direct == null
    ? inheritedSignDisplayAlways(call.inheritedSource)
    : direct === "always";
}

function inheritedSignDisplayAlways(source) {
  if (source == null) return false;
  if (!isNumericFunction(source.function)) return false;
  const value = sourceOptionValue(source, "signDisplay", null);
  if (value != null) return value === "always";
  return inheritedSignDisplayAlways(source.inherited);
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
