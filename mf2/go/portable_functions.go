package mf2

import (
	"math"
	"math/big"
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

func inheritedExactNumericSource(source *FunctionSource, targetFunction string) bool {
	value, err := inheritedNumericOptionValue(targetFunction, source, "select", "")
	return err == nil && value == "exact"
}

func invalidNumericSelector(functionRef map[string]any, source *FunctionSource) bool {
	selectValue := functionOptionLiteral(functionRef, "select", "")
	return numericSelectUsesVariable(functionRef) || (selectValue != "exact" && inheritedExactNumericSource(source, stringField(functionRef, "name")))
}

func isDecimalSourceFunction(functionRef map[string]any) bool {
	return isNumericFunction(functionRef) || stringField(functionRef, "name") == "currency"
}

func formatUnlocalizedNumber(call FunctionCall) (string, error) {
	value, err := parseCallDecimal(call, "Number function requires a numeric operand.")
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
	signAlways, err := signDisplayAlways(call)
	if err != nil {
		return "", err
	}
	formatted := formatUnlocalizedDecimalWithMaximumFractionDigits(value, maximum)
	if signAlways && value >= 0 {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimum), nil
}

func selectNumber(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Number selector cannot match this operand.")
	}
	value, err := parseMatchDecimal(match, "Number selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	if err := validateNumericVariantKey(match.Key); err != nil {
		return nil, err
	}
	matches, err := exactDecimalKeyMatches(match, value)
	if err != nil {
		return nil, err
	}
	if matches {
		rank := 2
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
	signAlways, err := signDisplayAlways(call)
	if err != nil {
		return "", err
	}
	formatted := formatUnlocalizedDecimalWithMaximumFractionDigits(value*100, maximum)
	if signAlways && value >= 0 {
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
	if err := validateNumericVariantKey(match.Key); err != nil {
		return nil, err
	}
	matches, err := exactDecimalKeyMatches(match, value*100)
	if err != nil {
		return nil, err
	}
	if matches {
		rank := 2
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
	signAlways, err := signDisplayAlways(call)
	if err != nil {
		return "", err
	}
	if signAlways && integer >= 0 {
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
	if err := validateNumericVariantKey(match.Key); err != nil {
		return nil, err
	}
	if match.Key == strconv.FormatInt(int64(math.Trunc(value)), 10) {
		rank := 2
		return &rank, nil
	}
	return nil, nil
}

func formatOffset(call FunctionCall) (string, error) {
	value, ok := numericSourceOperandText(call.InheritedSource)
	if !ok {
		if _, directOK := parseDecimalNumber(call.Value); directOK {
			value, ok = call.Value, true
		}
	}
	if !ok {
		return "", badOperand("Offset function requires a numeric operand.")
	}
	delta, err := offsetDelta(call)
	if err != nil {
		return "", err
	}
	result, ok := addIntegerOffsetDecimal(value, delta)
	if !ok {
		return "", badOperand("Offset result is outside the supported numeric range.")
	}
	signAlways, err := signDisplayAlways(call)
	if err != nil {
		return "", err
	}
	if signAlways && !strings.HasPrefix(result, "-") {
		return "+" + result, nil
	}
	return result, nil
}

func selectOffset(match FunctionMatch) (*int, error) {
	value, err := parseMatchDecimal(match, "Offset selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	if err := validateNumericVariantKey(match.Key); err != nil {
		return nil, err
	}
	matches, err := exactDecimalKeyMatches(match, value)
	if err != nil {
		return nil, err
	}
	if matches {
		rank := 2
		return &rank, nil
	}
	return nil, nil
}

var pluralCategoryKeys = map[string]bool{
	"zero":  true,
	"one":   true,
	"two":   true,
	"few":   true,
	"many":  true,
	"other": true,
}

func validateNumericVariantKey(key string) error {
	if pluralCategoryKeys[key] || decimalRe.MatchString(key) {
		return nil
	}
	return badVariantKey("Numeric selector keys must be number literals or plural keywords.")
}

func exactDecimalKeyMatches(match FunctionMatch, value float64) (bool, error) {
	canonical, err := usesCanonicalIntegerSerialization(match)
	if err != nil {
		return false, err
	}
	if canonical && math.Trunc(value) == value {
		serialized := strconv.FormatFloat(value, 'f', 0, 64)
		if value == 0 {
			serialized = "0"
		}
		return match.Key == serialized, nil
	}
	key, ok := parseDecimalNumber(match.Key)
	return ok && value == key, nil
}

func usesCanonicalIntegerSerialization(match FunctionMatch) (bool, error) {
	const missing = "\x00mojito-mf2-missing-option\x00"
	for _, name := range []string{
		"minimumFractionDigits",
		"minimumIntegerDigits",
		"minimumSignificantDigits",
		"maximumSignificantDigits",
	} {
		value, err := match.OptionValue(name, missing)
		if err != nil {
			return false, err
		}
		if value != missing {
			return false, nil
		}
	}
	return true, nil
}

var decimalRe = regexp.MustCompile(`^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$`)
var integerRe = regexp.MustCompile(`^[+-]?\d+$`)

func parseCallDecimal(call FunctionCall, message string) (float64, error) {
	if parsed, ok := parseSourceDecimal(call.InheritedSource); ok {
		return parsed, nil
	}
	if parsed, ok := parseDecimalNumber(call.Value); ok {
		return parsed, nil
	}
	return 0, badOperand(message)
}

func parseMatchDecimal(match FunctionMatch, message string) (float64, error) {
	if parsed, ok := parseSourceDecimal(match.InheritedSource); ok {
		return parsed, nil
	}
	if parsed, ok := parseDecimalNumber(match.Value); ok {
		return parsed, nil
	}
	return 0, badSelector(message)
}

func parseSourceDecimal(source *FunctionSource) (float64, bool) {
	if source == nil || !isDecimalSourceFunction(source.Function) {
		return 0, false
	}
	return numericSourceOperand(source)
}

func numericSourceOperand(source *FunctionSource) (float64, bool) {
	value, ok := numericSourceOperandText(source)
	if !ok {
		return 0, false
	}
	return parseDecimalNumber(value)
}

func numericSourceOperandText(source *FunctionSource) (string, bool) {
	if source == nil {
		return "", false
	}
	operand, ok := numericSourceOperandText(source.Inherited)
	if !ok {
		if _, parsed := parseDecimalNumber(source.Value); parsed {
			operand, ok = source.Value, true
		}
	}
	if !isDecimalSourceFunction(source.Function) || !ok {
		return operand, ok
	}
	switch stringField(source.Function, "name") {
	case "integer":
		return truncateDecimal(operand)
	case "offset":
		add, addErr := sourceOptionValue(source, "add", "")
		subtract, subtractErr := sourceOptionValue(source, "subtract", "")
		if addErr != nil || subtractErr != nil || (add == "") == (subtract == "") {
			return "", false
		}
		deltaText := add
		if deltaText == "" {
			deltaText = subtract
		}
		delta, deltaOK := parseInteger(deltaText)
		if !deltaOK {
			return "", false
		}
		if subtract != "" {
			delta = -delta
		}
		return addIntegerOffsetDecimal(operand, delta)
	default:
		return operand, true
	}
}

const maxExpandedDecimalDigits = 4096

func truncateDecimal(value string) (string, bool) {
	rational, _, ok := parseDecimalRational(value)
	if !ok {
		return "", false
	}
	return new(big.Int).Quo(rational.Num(), rational.Denom()).String(), true
}

func addIntegerOffsetDecimal(value string, delta int64) (string, bool) {
	rational, scale, ok := parseDecimalRational(value)
	if !ok {
		return "", false
	}
	rational.Add(rational, big.NewRat(delta, 1))
	formatted := rational.FloatString(scale)
	if strings.Contains(formatted, ".") {
		formatted = strings.TrimRight(formatted, "0")
		formatted = strings.TrimSuffix(formatted, ".")
	}
	if formatted == "-0" {
		formatted = "0"
	}
	return formatted, true
}

func parseDecimalRational(value string) (*big.Rat, int, bool) {
	if _, ok := parseDecimalNumber(value); !ok || len(value) > maxExpandedDecimalDigits {
		return nil, 0, false
	}
	rational, ok := new(big.Rat).SetString(value)
	if !ok {
		return nil, 0, false
	}
	scale, ok := decimalScale(value)
	if !ok {
		return nil, 0, false
	}
	return rational, scale, true
}

func decimalScale(value string) (int, bool) {
	unsigned := strings.TrimPrefix(strings.TrimPrefix(value, "-"), "+")
	mantissa := unsigned
	exponent := 0
	if index := strings.IndexAny(unsigned, "eE"); index >= 0 {
		mantissa = unsigned[:index]
		parsed, err := strconv.Atoi(unsigned[index+1:])
		if err != nil {
			return 0, false
		}
		if parsed > maxExpandedDecimalDigits || parsed < -maxExpandedDecimalDigits {
			return 0, false
		}
		exponent = parsed
	}
	fractionDigits := 0
	if dot := strings.IndexByte(mantissa, '.'); dot >= 0 {
		fractionDigits = len(mantissa) - dot - 1
	}
	scale := fractionDigits - exponent
	if scale < 0 {
		scale = 0
	}
	return scale, scale <= maxExpandedDecimalDigits
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

func signDisplayAlways(call FunctionCall) (bool, error) {
	value, err := call.OptionValue("signDisplay", "")
	return value == "always", err
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
