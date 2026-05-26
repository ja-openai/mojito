package mf2

import (
	"math"
	"regexp"
	"strconv"
	"strings"
)

func PortableFunctionRegistry() FunctionRegistry {
	registry := FunctionRegistry{
		formatters: map[string]Formatter{},
		selectors:  map[string]Selector{},
	}
	registry.formatters["string"] = func(call FunctionCall) (string, error) { return call.Value, nil }
	registry.formatters["number"] = formatUnlocalizedNumber
	registry.selectors["number"] = selectNumber
	registry.formatters["percent"] = formatUnlocalizedPercent
	registry.selectors["percent"] = selectPercent
	registry.formatters["integer"] = formatUnlocalizedInteger
	registry.selectors["integer"] = selectInteger
	registry.formatters["offset"] = formatOffset
	registry.selectors["offset"] = selectOffset
	return registry
}

func functionOptionLiteral(functionRef map[string]any, name, fallback string) string {
	option := asObject(asObject(functionRef["options"])[name])
	if stringField(option, "type") == "literal" {
		return stringField(option, "value")
	}
	return fallback
}

func sourceOptionValue(source *FunctionSource, name, fallback string) (string, error) {
	if source == nil {
		return fallback, nil
	}
	if source.OptionValue != nil {
		return source.OptionValue(name, fallback)
	}
	return functionOptionLiteral(source.Function, name, fallback), nil
}

func isNumericFunction(functionRef map[string]any) bool {
	name := stringField(functionRef, "name")
	return name == "number" || name == "integer" || name == "percent" || name == "offset"
}

func numericSelectUsesVariable(functionRef map[string]any) bool {
	return stringField(asObject(asObject(functionRef["options"])["select"]), "type") == "variable"
}

func inheritedExactNumericSource(source *FunctionSource) bool {
	if source == nil {
		return false
	}
	if isNumericFunction(source.Function) {
		value, err := sourceOptionValue(source, "select", "")
		if err == nil && value == "exact" {
			return true
		}
	}
	return inheritedExactNumericSource(source.Inherited)
}

func invalidNumericSelector(functionRef map[string]any, source *FunctionSource) bool {
	selectValue := functionOptionLiteral(functionRef, "select", "")
	return numericSelectUsesVariable(functionRef) || (selectValue != "exact" && inheritedExactNumericSource(source))
}

func isDecimalSourceFunction(functionRef map[string]any) bool {
	return isNumericFunction(functionRef) || stringField(functionRef, "name") == "currency"
}

func formatUnlocalizedNumber(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Number function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	minimum, err := minimumFractionDigits(call)
	if err != nil {
		return "", err
	}
	return formatUnlocalizedDecimal(value, signDisplayAlways(call.Function), minimum)
}

func selectNumber(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Number selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Number selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseDecimalNumber(match.Key)
	if ok && value == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatUnlocalizedPercent(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Percent function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	maximum, err := maximumFractionDigits(call)
	if err != nil {
		return "", err
	}
	minimum, err := minimumFractionDigits(call)
	if err != nil {
		return "", err
	}
	formatted := formatUnlocalizedDecimalWithMaximumFractionDigits(value*100, maximum)
	if signDisplayAlways(call.Function) && value >= 0 {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimum) + "%", nil
}

func selectPercent(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Percent selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Percent selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseDecimalNumber(match.Key)
	if ok && value*100 == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatUnlocalizedInteger(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Integer function requires a numeric operand.")
	if err != nil {
		return "", err
	}
	integer := math.Trunc(value)
	if signDisplayAlways(call.Function) && integer >= 0 {
		return "+" + strconv.FormatInt(int64(integer), 10), nil
	}
	return strconv.FormatInt(int64(integer), 10), nil
}

func selectInteger(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Integer selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Integer selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseInteger(match.Key)
	if ok && int64(math.Trunc(value)) == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func formatOffset(call FunctionCall) (string, error) {
	value, ok := parseInteger(call.Value)
	if !ok {
		return "", badOperand("Offset function requires a numeric operand.")
	}
	delta, err := offsetDelta(call)
	if err != nil {
		return "", err
	}
	result := value + delta
	if inheritedSignDisplayAlways(call.InheritedSource) && result >= 0 {
		return "+" + strconv.FormatInt(result, 10), nil
	}
	return strconv.FormatInt(result, 10), nil
}

func selectOffset(match FunctionMatch) (*int, error) {
	value, ok := parseInteger(match.Value)
	if !ok {
		return nil, badSelector("Offset selector requires a numeric operand.")
	}
	key, ok := parseInteger(match.Key)
	if ok && value == key {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

var decimalRe = regexp.MustCompile(`^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$`)
var integerRe = regexp.MustCompile(`^[+-]?\d+$`)

func parseCallDecimal(call FunctionCall, message string) (float64, error) {
	if parsed, ok := parseDecimalNumber(call.Value); ok {
		return parsed, nil
	}
	if parsed, ok := parseSourceDecimal(call.InheritedSource); ok {
		return parsed, nil
	}
	return 0, badOperand(message)
}

func parseMatchDecimal(match FunctionMatch, message string) (float64, error) {
	if parsed, ok := parseDecimalNumber(match.Value); ok {
		return parsed, nil
	}
	if parsed, ok := parseSourceDecimal(match.InheritedSource); ok {
		return parsed, nil
	}
	return 0, badSelector(message)
}

func parseSourceDecimal(source *FunctionSource) (float64, bool) {
	if source == nil {
		return 0, false
	}
	if isDecimalSourceFunction(source.Function) {
		return parseDecimalNumber(source.Value)
	}
	return parseSourceDecimal(source.Inherited)
}

func parseDecimalNumber(value string) (float64, bool) {
	if !decimalRe.MatchString(value) {
		return 0, false
	}
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsInf(parsed, 0) || math.IsNaN(parsed) {
		return 0, false
	}
	return parsed, true
}

func formatUnlocalizedDecimal(value float64, signAlways bool, minimumFractionDigits int) (string, error) {
	formatted := strconv.FormatFloat(value, 'f', -1, 64)
	if signAlways && value >= 0 {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimumFractionDigits), nil
}

func formatUnlocalizedDecimalWithMaximumFractionDigits(value float64, digits *int) string {
	if digits == nil {
		formatted, _ := formatUnlocalizedDecimal(value, false, 0)
		return formatted
	}
	formatted := strconv.FormatFloat(value, 'f', *digits, 64)
	for strings.Contains(formatted, ".") && strings.HasSuffix(formatted, "0") {
		formatted = strings.TrimSuffix(formatted, "0")
	}
	return strings.TrimSuffix(formatted, ".")
}

func appendMinimumFractionDigits(formatted string, minimumFractionDigits int) string {
	if minimumFractionDigits == 0 {
		return formatted
	}
	dot := strings.Index(formatted, ".")
	fractionDigits := 0
	if dot >= 0 {
		fractionDigits = len(formatted) - dot - 1
	} else {
		formatted += "."
	}
	for index := fractionDigits; index < minimumFractionDigits; index++ {
		formatted += "0"
	}
	return formatted
}

func minimumFractionDigits(call FunctionCall) (int, error) {
	value, _ := call.OptionValue("minimumFractionDigits", "")
	if value == "" {
		return 0, nil
	}
	return parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.")
}

func maximumFractionDigits(call FunctionCall) (*int, error) {
	value, _ := call.OptionValue("maximumFractionDigits", "")
	if value == "" {
		return nil, nil
	}
	parsed, err := parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.")
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

func parseNonNegativeOption(value, message string) (int, error) {
	if value == "" {
		return 0, badOption(message)
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return 0, badOption(message)
		}
	}
	return strconv.Atoi(value)
}

func signDisplayAlways(functionRef map[string]any) bool {
	return functionOptionLiteral(functionRef, "signDisplay", "") == "always"
}

func inheritedSignDisplayAlways(source *FunctionSource) bool {
	if source == nil {
		return false
	}
	name := stringField(source.Function, "name")
	if name == "number" || name == "integer" {
		if value, err := sourceOptionValue(source, "signDisplay", ""); err == nil && value == "always" {
			return true
		}
	}
	return inheritedSignDisplayAlways(source.Inherited)
}

func offsetDelta(call FunctionCall) (int64, error) {
	add, _ := call.OptionValue("add", "")
	subtract, _ := call.OptionValue("subtract", "")
	if (add == "" && subtract == "") || (add != "" && subtract != "") {
		return 0, badOption("Offset function requires exactly one of add or subtract.")
	}
	value, ok := parseInteger(add)
	if subtract != "" {
		value, ok = parseInteger(subtract)
	}
	if !ok {
		if add != "" {
			return 0, badOption("Offset add option must be an integer.")
		}
		return 0, badOption("Offset subtract option must be an integer.")
	}
	if subtract != "" {
		return -value, nil
	}
	return value, nil
}

func parseInteger(value string) (int64, bool) {
	if !integerRe.MatchString(value) {
		return 0, false
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	return parsed, err == nil
}
