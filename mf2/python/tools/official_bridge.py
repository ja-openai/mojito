"""Public-API JSON-lines transport for the shared Unicode assertion runner."""

from decimal import Decimal, InvalidOperation, ROUND_DOWN
import json
import sys
import traceback

from mojito_mf2 import FunctionRegistry, MF2Error, format_message, format_message_to_parts, parse_to_model


def state(call):
    chain = []
    source = call.inherited_source
    while source is not None:
        chain.append(source)
        source = source.inherited_source
    try:
        number = Decimal(chain[-1].value if chain else call.value)
        if not number.is_finite():
            raise InvalidOperation
    except InvalidOperation as error:
        raise MF2Error("bad-operand", "Test function requires a numeric operand.") from error
    result = {"number": number, "digits": 0, "format": False, "select": False}
    for item in [*reversed(chain), call]:
        if item.function.get("name") not in {"test:function", "test:format", "test:select"}:
            continue
        digits = item.option_value("decimalPlaces")
        if digits is not None:
            if digits not in {"0", "1"}:
                raise MF2Error("bad-option", "Test decimalPlaces must be 0 or 1.")
            result["digits"] = int(digits)
        fails = item.option_value("fails")
        if fails is not None:
            if fails not in {"always", "format", "select", "never"}:
                raise MF2Error("bad-option", "Invalid test failure mode.")
            result["format"] = fails in {"always", "format"}
            result["select"] = fails in {"always", "select"}
    return result


def render_test(call):
    resolved = state(call)
    if call.function["name"] == "test:function" and resolved["format"]:
        raise MF2Error("bad-option", "Requested test formatting failure.")
    return format(resolved["number"].quantize(Decimal(1).scaleb(-resolved["digits"]), rounding=ROUND_DOWN), "f")


def select_test(call):
    resolved = state(call)
    if call.function["name"] == "test:format" or resolved["select"]:
        raise MF2Error("bad-selector", "Requested test selection failure.")
    if int(resolved["number"]) != 1:
        return None
    if resolved["digits"] == 1 and call.key == "1.0":
        return 2
    return 1 if call.key == "1" else None


def registry(kind):
    if kind == "platform":
        from mojito_mf2.babel import babel_function_registry
        result = babel_function_registry()
    else:
        result = FunctionRegistry.portable()
    for name in ["test:function", "test:select", "test:format"]:
        result = result.with_function(name, render_test).with_selector(name, select_test)
    return result


def handle(request):
    parsed = parse_to_model(request["source"])
    if parsed.has_diagnostics:
        return {"diagnostics": [item.code for item in parsed.diagnostics]}
    functions = registry(request["registry"])
    result = format_message(parsed.model, request["arguments"], locale=request["locale"], functions=functions, bidi_isolation=request["bidiIsolation"])
    parts = format_message_to_parts(parsed.model, request["arguments"], locale=request["locale"], functions=functions)
    return {"diagnostics": [], "value": result.value, "errors": [item.code for item in result.errors], "parts": parts.parts, "partsErrors": [item.code for item in parts.errors]}


for line in sys.stdin:
    try:
        response = handle(json.loads(line))
    except MF2Error as error:
        response = {"diagnostics": [], "errors": [error.code]}
    except Exception:
        response = {"transportError": traceback.format_exc()}
    print(json.dumps(response, ensure_ascii=True), flush=True)
