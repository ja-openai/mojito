package mf2

import (
	"math"
	"math/big"
	"regexp"
	"strconv"
	"strings"
)

const maxFractionDigits = 100
const maxDecimalOperandLength = 256
const maxDecimalOutputChars = 1000
const maxOffsetIntegerText = "1000000000000000000000"
const maxOffsetIntegerDigits = len(maxOffsetIntegerText) - 1

var maxOffsetInteger = new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(maxOffsetIntegerDigits)), nil)

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
	name := stringField(functionRef, "name")
	return isNumericFunction(functionRef) || name == "currency" || name == "relativeTime"
}

func formatUnlocalizedNumber(call FunctionCall) (string, error) {
	message := "Number function requires a numeric operand."
	value, err := parseCallDecimalOperand(call, message)
	if err != nil {
		return "", err
	}
	minimum, err := minimumFractionDigits(call)
	if err != nil {
		return "", err
	}
	maximum, err := maximumFractionDigits(call)
	if err != nil {
		return "", err
	}
	if err := validateFractionDigits(minimum, maximum); err != nil {
		return "", err
	}
	rounded := value.roundToMaximumFractionDigits(maximum)
	if err := ensureDecimalOutputBounded(rounded, minimum, message); err != nil {
		return "", err
	}
	return formatUnlocalizedDecimalOperand(rounded, signDisplayAlways(call.Function), minimum), nil
}

func selectNumber(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Number selector cannot match this operand.")
	}
	value, err := parseMatchDecimalOperand(match, "Number selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseDecimalOperand(match.Key)
	if ok && value.equal(key) {
		rank := 2
		return &rank, nil
	}
	return nil, nil
}

func formatUnlocalizedPercent(call FunctionCall) (string, error) {
	message := "Percent function requires a numeric operand."
	value, err := parseCallDecimalOperand(call, message)
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
	if err := validateFractionDigits(minimum, maximum); err != nil {
		return "", err
	}
	percentValue := value.shift(2).roundToMaximumFractionDigits(maximum)
	if err := ensureDecimalOutputBounded(percentValue, minimum, message); err != nil {
		return "", err
	}
	formatted := formatUnlocalizedDecimalOperand(percentValue, false, 0)
	if signDisplayAlways(call.Function) && !value.negative {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimum) + "%", nil
}

func selectPercent(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Percent selector cannot match this operand.")
	}
	value, err := parseMatchDecimalOperand(match, "Percent selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseDecimalOperand(match.Key)
	if ok && value.shift(2).equal(key) {
		rank := 2
		return &rank, nil
	}
	return nil, nil
}

func formatUnlocalizedInteger(call FunctionCall) (string, error) {
	message := "Integer function requires a numeric operand."
	value, err := parseCallDecimalOperand(call, message)
	if err != nil {
		return "", err
	}
	integer := value.truncateToInteger()
	if err := ensureDecimalOutputBounded(integer, 0, message); err != nil {
		return "", err
	}
	formatted := formatUnlocalizedDecimalOperand(integer, false, 0)
	if signDisplayAlways(call.Function) && !integer.negative {
		return "+" + formatted, nil
	}
	return formatted, nil
}

func selectInteger(match FunctionMatch) (*int, error) {
	if invalidNumericSelector(match.Function, match.InheritedSource) {
		return nil, badSelector("Integer selector cannot match this operand.")
	}
	value, err := parseMatchDecimalOperand(match, "Integer selector requires a numeric operand.")
	if err != nil {
		return nil, err
	}
	key, ok := parseIntegerOperand(match.Key)
	if ok && value.truncateToInteger().equal(key) {
		rank := 2
		return &rank, nil
	}
	return nil, nil
}

func formatOffset(call FunctionCall) (string, error) {
	value, ok := parseOffsetInteger(call.Value)
	if !ok {
		return "", badOperand("Offset function requires a numeric operand.")
	}
	delta, err := offsetDelta(call)
	if err != nil {
		return "", err
	}
	result := new(big.Int).Add(value, delta)
	if !offsetIntegerInRange(result) {
		return "", badOperand("Offset result is outside the supported integer range.")
	}
	if inheritedSignDisplayAlways(call.InheritedSource) && result.Sign() >= 0 {
		return "+" + result.String(), nil
	}
	return result.String(), nil
}

func selectOffset(match FunctionMatch) (*int, error) {
	value, ok := parseOffsetInteger(match.Value)
	if !ok {
		return nil, badSelector("Offset selector requires a numeric operand.")
	}
	key, ok := parseOffsetInteger(match.Key)
	if ok && value.Cmp(key) == 0 {
		rank := 2
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

func parseCallDecimalOperand(call FunctionCall, message string) (decimalOperand, error) {
	if parsed, ok := parseDecimalOperand(call.Value); ok {
		return parsed, nil
	}
	if parsed, ok := parseSourceDecimalOperand(call.InheritedSource); ok {
		return parsed, nil
	}
	return decimalOperand{}, badOperand(message)
}

func parseMatchDecimalOperand(match FunctionMatch, message string) (decimalOperand, error) {
	if parsed, ok := parseSourceDecimalOperand(match.InheritedSource); ok {
		return parsed, nil
	}
	if parsed, ok := parseDecimalOperand(match.Value); ok {
		return parsed, nil
	}
	return decimalOperand{}, badSelector(message)
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

func parseSourceDecimalOperand(source *FunctionSource) (decimalOperand, bool) {
	if source == nil {
		return decimalOperand{}, false
	}
	if isDecimalSourceFunction(source.Function) {
		return parseDecimalOperand(source.Value)
	}
	return parseSourceDecimalOperand(source.Inherited)
}

func parseDecimalNumber(value string) (float64, bool) {
	if len(value) > maxDecimalOperandLength {
		return 0, false
	}
	if !decimalRe.MatchString(value) {
		return 0, false
	}
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsInf(parsed, 0) || math.IsNaN(parsed) {
		return 0, false
	}
	return parsed, true
}

type decimalOperand struct {
	negative bool
	digits   string
	scale    int
}

func parseDecimalOperand(value string) (decimalOperand, bool) {
	if len(value) > maxDecimalOperandLength {
		return decimalOperand{}, false
	}
	matches := decimalRe.FindStringSubmatch(value)
	if matches == nil {
		return decimalOperand{}, false
	}
	exponent, ok := parseBoundedDecimalExponent(matches[3])
	if !ok {
		return decimalOperand{}, false
	}
	negative := strings.HasPrefix(value, "-")
	body := value
	if negative {
		body = body[1:]
	}
	significand := strings.SplitN(body, "e", 2)[0]
	significand = strings.SplitN(significand, "E", 2)[0]
	pieces := strings.SplitN(significand, ".", 2)
	fraction := ""
	if len(pieces) == 2 {
		fraction = pieces[1]
	}
	return normalizeDecimalOperand(negative, pieces[0]+fraction, len(fraction)-exponent), true
}

func parseIntegerOperand(value string) (decimalOperand, bool) {
	if len(value) > maxDecimalOperandLength {
		return decimalOperand{}, false
	}
	if !integerRe.MatchString(value) {
		return decimalOperand{}, false
	}
	negative := strings.HasPrefix(value, "-")
	digits := value
	if negative || strings.HasPrefix(value, "+") {
		digits = value[1:]
	}
	return normalizeDecimalOperand(negative, digits, 0), true
}

func parseBoundedDecimalExponent(value string) (int, bool) {
	if value == "" {
		return 0, true
	}
	text := value[1:]
	negative := strings.HasPrefix(text, "-")
	if negative || strings.HasPrefix(text, "+") {
		text = text[1:]
	}
	text = strings.TrimLeft(text, "0")
	if text == "" {
		return 0, true
	}
	if len(text) > 7 {
		return 0, false
	}
	parsed, err := strconv.Atoi(text)
	if err != nil || parsed > 1000000 {
		return 0, false
	}
	if negative {
		return -parsed, true
	}
	return parsed, true
}

func normalizeDecimalOperand(negative bool, digits string, scale int) decimalOperand {
	digits = strings.TrimLeft(digits, "0")
	if digits == "" {
		return decimalOperand{digits: "0"}
	}
	for strings.HasSuffix(digits, "0") {
		digits = strings.TrimSuffix(digits, "0")
		scale--
	}
	return decimalOperand{negative: negative, digits: digits, scale: scale}
}

func (operand decimalOperand) equal(other decimalOperand) bool {
	return operand.negative == other.negative && operand.digits == other.digits && operand.scale == other.scale
}

func (operand decimalOperand) shift(places int) decimalOperand {
	return normalizeDecimalOperand(operand.negative, operand.digits, operand.scale-places)
}

func (operand decimalOperand) truncateToInteger() decimalOperand {
	if operand.scale <= 0 {
		return operand
	}
	keep := len(operand.digits) - operand.scale
	if keep <= 0 {
		return normalizeDecimalOperand(false, "0", 0)
	}
	return normalizeDecimalOperand(operand.negative, operand.digits[:keep], 0)
}

func (operand decimalOperand) roundToMaximumFractionDigits(maximumFractionDigits *int) decimalOperand {
	if maximumFractionDigits == nil || operand.scale <= *maximumFractionDigits {
		return operand
	}
	drop := operand.scale - *maximumFractionDigits
	keep := len(operand.digits) - drop
	kept := "0"
	remainder := operand.digits
	if keep > 0 {
		kept = operand.digits[:keep]
		remainder = operand.digits[keep:]
	}
	rounded := strings.TrimLeft(kept, "0")
	if rounded == "" {
		rounded = "0"
	}
	comparison := compareDecimalRemainderToHalf(remainder, drop)
	if comparison >= 0 {
		rounded = incrementDecimalString(rounded)
	}
	return normalizeDecimalOperand(operand.negative, rounded, *maximumFractionDigits)
}

func formatUnlocalizedDecimalOperand(operand decimalOperand, signAlways bool, minimumFractionDigits int) string {
	formatted := decimalOperandToString(operand)
	if signAlways && !operand.negative {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimumFractionDigits)
}

func formatUnlocalizedDecimal(value float64, signAlways bool, minimumFractionDigits int) (string, error) {
	formatted := strconv.FormatFloat(value, 'f', -1, 64)
	if signAlways && value >= 0 {
		formatted = "+" + formatted
	}
	return appendMinimumFractionDigits(formatted, minimumFractionDigits), nil
}

func decimalOperandToString(operand decimalOperand) string {
	sign := ""
	if operand.negative {
		sign = "-"
	}
	if operand.scale <= 0 {
		return sign + operand.digits + strings.Repeat("0", -operand.scale)
	}
	if operand.scale >= len(operand.digits) {
		return sign + "0." + strings.Repeat("0", operand.scale-len(operand.digits)) + operand.digits
	}
	integerDigits := len(operand.digits) - operand.scale
	return sign + operand.digits[:integerDigits] + "." + operand.digits[integerDigits:]
}

func compareDecimalRemainderToHalf(remainder string, droppedDigits int) int {
	if !strings.ContainsAny(remainder, "123456789") {
		return -1
	}
	if len(remainder) < droppedDigits {
		return -1
	}
	if remainder[0] < '5' {
		return -1
	}
	if remainder[0] > '5' {
		return 1
	}
	if strings.ContainsAny(remainder[1:], "123456789") {
		return 1
	}
	return 0
}

func incrementDecimalString(value string) string {
	digits := []byte(value)
	for index := len(digits) - 1; index >= 0; index-- {
		if digits[index] != '9' {
			digits[index]++
			return string(digits)
		}
		digits[index] = '0'
	}
	return "1" + string(digits)
}

func ensureDecimalOutputBounded(operand decimalOperand, minimumFractionDigits int, message string) error {
	if estimatedDecimalOutputChars(operand, minimumFractionDigits) > maxDecimalOutputChars {
		return badOperand(message)
	}
	return nil
}

func estimatedDecimalOutputChars(operand decimalOperand, minimumFractionDigits int) int {
	sign := 0
	if operand.negative {
		sign = 1
	}
	if operand.scale <= 0 {
		return sign + len(operand.digits) - operand.scale
	}
	integerDigits := len(operand.digits) - operand.scale
	if integerDigits < 1 {
		integerDigits = 1
	}
	fractionDigits := operand.scale
	if minimumFractionDigits > fractionDigits {
		fractionDigits = minimumFractionDigits
	}
	if fractionDigits > 0 {
		return sign + integerDigits + 1 + fractionDigits
	}
	return sign + integerDigits
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
	value, err := call.OptionValue("minimumFractionDigits", "")
	if err != nil {
		return 0, err
	}
	if value == "" {
		return 0, nil
	}
	return parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.")
}

func maximumFractionDigits(call FunctionCall) (*int, error) {
	value, err := call.OptionValue("maximumFractionDigits", "")
	if err != nil {
		return nil, err
	}
	if value == "" {
		return nil, nil
	}
	parsed, err := parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.")
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

func validateFractionDigits(minimum int, maximum *int) error {
	if maximum != nil && minimum > *maximum {
		return badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.")
	}
	return nil
}

func parseNonNegativeOption(value, message string) (int, error) {
	if value == "" || len(value) > maxDecimalOperandLength {
		return 0, badOption(message)
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return 0, badOption(message)
		}
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed > maxFractionDigits {
		return 0, badOption(message)
	}
	return parsed, nil
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

func offsetDelta(call FunctionCall) (*big.Int, error) {
	add, err := call.OptionValue("add", "")
	if err != nil {
		return nil, err
	}
	subtract, err := call.OptionValue("subtract", "")
	if err != nil {
		return nil, err
	}
	if (add == "" && subtract == "") || (add != "" && subtract != "") {
		return nil, badOption("Offset function requires exactly one of add or subtract.")
	}
	value, ok := parseOffsetInteger(add)
	if subtract != "" {
		value, ok = parseOffsetInteger(subtract)
	}
	if !ok {
		if add != "" {
			return nil, badOption("Offset add option must be an integer.")
		}
		return nil, badOption("Offset subtract option must be an integer.")
	}
	if subtract != "" {
		return new(big.Int).Neg(value), nil
	}
	return value, nil
}

func parseOffsetInteger(value string) (*big.Int, bool) {
	if !integerRe.MatchString(value) {
		return nil, false
	}
	negative := strings.HasPrefix(value, "-")
	digits := value
	if negative || strings.HasPrefix(value, "+") {
		digits = value[1:]
	}
	digits = strings.TrimLeft(digits, "0")
	if digits == "" {
		return big.NewInt(0), true
	}
	if len(digits) > len(maxOffsetIntegerText) || (len(digits) == len(maxOffsetIntegerText) && digits >= maxOffsetIntegerText) {
		return nil, false
	}
	text := digits
	if negative {
		text = "-" + digits
	}
	parsed, ok := new(big.Int).SetString(text, 10)
	return parsed, ok
}

func offsetIntegerInRange(value *big.Int) bool {
	return new(big.Int).Abs(value).Cmp(maxOffsetInteger) < 0
}
