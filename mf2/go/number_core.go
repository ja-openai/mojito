package mf2

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

const defaultNumberCoreLocale = "en-US"
const maxNumberCoreOptionLength = 256
const maxNumberCoreOperandLength = 256

const (
	NumberCoreStyleNumber   = "number"
	NumberCoreStyleInteger  = "integer"
	NumberCoreStylePercent  = "percent"
	NumberCoreStyleCurrency = "currency"
)

const (
	NumberCoreCurrencyDisplaySymbol       = "symbol"
	NumberCoreCurrencyDisplayNarrowSymbol = "narrowSymbol"
	NumberCoreCurrencyDisplayCode         = "code"
)

const (
	NumberCoreSignDisplayAuto   = "auto"
	NumberCoreSignDisplayAlways = "always"
	NumberCoreSignDisplayNever  = "never"
)

type NumberCoreOptions struct {
	Locale                string
	Style                 string
	Currency              string
	CurrencyDisplay       string
	UseGrouping           *bool
	MinimumFractionDigits *int
	MaximumFractionDigits *int
	SignDisplay           string
}

var numberCoreDecimalRe = regexp.MustCompile(`^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$`)
var numberCoreCurrencyRe = regexp.MustCompile(`^[A-Za-z]{3}$`)
var numberCorePatternRe = regexp.MustCompile(`[#0,.]+`)

func NumberCoreFunctionRegistry() FunctionRegistry {
	return PortableFunctionRegistry().
		WithFunction("number", func(call FunctionCall) (string, error) {
			return formatNumberCoreCall(call, NumberCoreStyleNumber)
		}).
		WithFunction("integer", func(call FunctionCall) (string, error) {
			return formatNumberCoreCall(call, NumberCoreStyleInteger)
		}).
		WithFunction("percent", func(call FunctionCall) (string, error) {
			return formatNumberCoreCall(call, NumberCoreStylePercent)
		}).
		WithFunction("currency", func(call FunctionCall) (string, error) {
			return formatNumberCoreCall(call, NumberCoreStyleCurrency)
		})
}

func FormatNumberCore(value any, options NumberCoreOptions) (string, error) {
	style, err := numberCoreOptionOneOf(defaultString(options.Style, NumberCoreStyleNumber), "style", []string{
		NumberCoreStyleNumber,
		NumberCoreStyleInteger,
		NumberCoreStylePercent,
		NumberCoreStyleCurrency,
	})
	if err != nil {
		return "", err
	}
	signDisplay, err := numberCoreOptionOneOf(defaultString(options.SignDisplay, NumberCoreSignDisplayAuto), "signDisplay", []string{
		NumberCoreSignDisplayAuto,
		NumberCoreSignDisplayAlways,
		NumberCoreSignDisplayNever,
	})
	if err != nil {
		return "", err
	}
	locale, err := localeOption(options.Locale, defaultNumberCoreLocale)
	if err != nil {
		return "", err
	}
	localeData := resolveNumberCoreLocaleData(locale)
	parsed, ok := parseNumberCoreFinite(value)
	if !ok {
		return "", badOperand("Number core requires a finite numeric value.")
	}

	currency := ""
	if style == NumberCoreStyleCurrency {
		currency, err = parseNumberCoreCurrency(options.Currency)
		if err != nil {
			return "", err
		}
	}
	pattern := numberCorePatternForStyle(localeData, style)
	fraction, err := numberCoreFractionOptions(style, currency, options, pattern)
	if err != nil {
		return "", err
	}
	normalized := parsed
	if style == NumberCoreStyleInteger {
		normalized = parsed.truncateToInteger()
	}
	scaled := normalized
	if style == NumberCoreStylePercent {
		scaled = scaled.shift(2)
	}
	if !isSupportedNumberCoreMagnitude(scaled) {
		return "", badOperand("Number core numeric value is outside the supported magnitude.")
	}
	formatted := formatNumberCoreDecimal(scaled.absOperand(), localeData, pattern, fraction, numberCoreUseGrouping(options))

	if style == NumberCoreStylePercent {
		return applySignedNumberCorePattern(pattern, formatted, scaled.isNegative(), localeData.symbols, signDisplay, localeData.symbols["percentSign"], ""), nil
	}
	if style == NumberCoreStyleCurrency {
		display, err := numberCoreCurrencyDisplay(localeData, currency, defaultString(options.CurrencyDisplay, NumberCoreCurrencyDisplaySymbol))
		if err != nil {
			return "", err
		}
		return applySignedNumberCorePattern(pattern, formatted, scaled.isNegative(), localeData.symbols, signDisplay, "", display), nil
	}
	return applyNumberCoreSign(formatted, scaled.isNegative(), localeData.symbols, signDisplay), nil
}

func FormatNumberCoreToParts(value any, options NumberCoreOptions) ([]Part, error) {
	formatted, err := FormatNumberCore(value, options)
	if err != nil {
		return nil, err
	}
	return []Part{{"type": "text", "value": formatted}}, nil
}

func formatNumberCoreCall(call FunctionCall, style string) (string, error) {
	currency, err := call.OptionValue("currency", "")
	if err != nil {
		return "", err
	}
	currencyDisplay, err := call.OptionValue("currencyDisplay", NumberCoreCurrencyDisplaySymbol)
	if err != nil {
		return "", err
	}
	minimum, err := numberCoreIntegerOptionFromCall(call, "minimumFractionDigits")
	if err != nil {
		return "", err
	}
	maximum, err := numberCoreIntegerOptionFromCall(call, "maximumFractionDigits")
	if err != nil {
		return "", err
	}
	signDisplay, err := call.OptionValue("signDisplay", NumberCoreSignDisplayAuto)
	if err != nil {
		return "", err
	}
	useGroupingText, err := call.OptionValue("useGrouping", "true")
	if err != nil {
		return "", err
	}
	useGrouping, err := numberCoreBooleanOption(useGroupingText, "useGrouping")
	if err != nil {
		return "", err
	}
	return FormatNumberCore(numberCoreCallValue(call, style), NumberCoreOptions{
		Locale:                call.Locale,
		Style:                 style,
		Currency:              currency,
		CurrencyDisplay:       currencyDisplay,
		UseGrouping:           &useGrouping,
		MinimumFractionDigits: minimum,
		MaximumFractionDigits: maximum,
		SignDisplay:           signDisplay,
	})
}

func numberCoreCallValue(call FunctionCall, style string) any {
	source := call.InheritedSource
	if source != nil {
		if style == NumberCoreStyleNumber && stringField(source.Function, "name") == NumberCoreStyleInteger {
			if parsed, ok := parseNumberCoreDecimalText(source.Value); ok {
				return parsed.truncateToInteger().sourceString()
			}
		}
		return source.Value
	}
	if call.RawValue != nil {
		return call.RawValue
	}
	return call.Value
}

func resolveNumberCoreLocaleData(locale string) cldrNumberLocaleData {
	for _, candidate := range localeLookupChain(locale) {
		if data, ok := cldrNumberLocales[candidate]; ok {
			return data
		}
		for _, data := range cldrNumberLocales {
			if data.numbersSourceLocale == candidate {
				return data
			}
		}
	}
	return cldrNumberLocales[defaultNumberCoreLocale]
}

func numberCorePatternForStyle(localeData cldrNumberLocaleData, style string) string {
	switch style {
	case NumberCoreStylePercent:
		return localeData.percentPattern
	case NumberCoreStyleCurrency:
		return localeData.currencyPattern
	default:
		return localeData.decimalPattern
	}
}

type numberCoreFraction struct {
	minimum int
	maximum int
}

type numberCoreValue struct {
	operand      decimalOperand
	negativeZero bool
}

func (value numberCoreValue) isNegative() bool {
	return value.negativeZero || value.operand.negative
}

func (value numberCoreValue) truncateToInteger() numberCoreValue {
	truncated := value.operand.truncateToInteger()
	return numberCoreValue{
		operand:      truncated,
		negativeZero: value.isNegative() && truncated.digits == "0",
	}
}

func (value numberCoreValue) shift(places int) numberCoreValue {
	return numberCoreValue{
		operand:      value.operand.shift(places),
		negativeZero: value.negativeZero,
	}
}

func (value numberCoreValue) absOperand() decimalOperand {
	operand := value.operand
	operand.negative = false
	return operand
}

func (value numberCoreValue) sourceString() string {
	if value.negativeZero {
		return "-0"
	}
	return decimalOperandToString(value.operand)
}

func numberCoreFractionOptions(style, currency string, options NumberCoreOptions, pattern string) (numberCoreFraction, error) {
	defaults := numberCoreFractionDefaultsFromPattern(pattern)
	if style == NumberCoreStyleInteger {
		defaults = numberCoreFraction{}
	}
	if style == NumberCoreStyleCurrency {
		currencyDefaults, ok := cldrNumberCurrencyFractions[currency]
		if !ok {
			currencyDefaults = cldrNumberCurrencyFractions["DEFAULT"]
		}
		defaults = numberCoreFraction{minimum: currencyDefaults.digits, maximum: currencyDefaults.digits}
	}
	minimum := defaults.minimum
	maximum := defaults.maximum
	if options.MinimumFractionDigits != nil {
		if *options.MinimumFractionDigits < 0 || *options.MinimumFractionDigits > maxFractionDigits {
			return numberCoreFraction{}, badOption("minimumFractionDigits must be a non-negative integer.")
		}
		minimum = *options.MinimumFractionDigits
	}
	if options.MaximumFractionDigits != nil {
		if *options.MaximumFractionDigits < 0 || *options.MaximumFractionDigits > maxFractionDigits {
			return numberCoreFraction{}, badOption("maximumFractionDigits must be a non-negative integer.")
		}
		maximum = *options.MaximumFractionDigits
	}
	if options.MinimumFractionDigits != nil && options.MaximumFractionDigits == nil && maximum < minimum {
		maximum = minimum
	}
	if options.MaximumFractionDigits != nil && options.MinimumFractionDigits == nil && maximum < minimum {
		minimum = maximum
	}
	if maximum < minimum {
		return numberCoreFraction{}, badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.")
	}
	return numberCoreFraction{minimum: minimum, maximum: maximum}, nil
}

func numberCoreFractionDefaultsFromPattern(pattern string) numberCoreFraction {
	numberPattern := numberCoreNumberPattern(pattern)
	dot := strings.Index(numberPattern, ".")
	if dot < 0 {
		return numberCoreFraction{}
	}
	fraction := numberPattern[dot+1:]
	return numberCoreFraction{minimum: strings.Count(fraction, "0"), maximum: len(fraction)}
}

func formatNumberCoreDecimal(value decimalOperand, localeData cldrNumberLocaleData, pattern string, fraction numberCoreFraction, useGrouping bool) string {
	maximum := fraction.maximum
	rounded := decimalOperandToString(value.roundToMaximumFractionDigits(&maximum))
	integer, decimal, _ := strings.Cut(rounded, ".")
	for len(decimal) > fraction.minimum && strings.HasSuffix(decimal, "0") {
		decimal = decimal[:len(decimal)-1]
	}
	for len(decimal) < fraction.minimum {
		decimal += "0"
	}
	grouping := numberCoreGroupingInfo(pattern)
	if useGrouping && shouldNumberCoreGroup(integer, grouping, localeData.minimumGroupingDigits) {
		integer = groupNumberCoreInteger(integer, grouping, localeData.symbols["group"])
	}
	integer = localizeNumberCoreDigits(integer, localeData.numberingSystemDigits)
	if decimal != "" {
		return integer + localeData.symbols["decimal"] + localizeNumberCoreDigits(decimal, localeData.numberingSystemDigits)
	}
	return integer
}

type numberCoreGrouping struct {
	primary   int
	secondary int
}

func numberCoreGroupingInfo(pattern string) numberCoreGrouping {
	integerPattern, _, _ := strings.Cut(numberCoreNumberPattern(pattern), ".")
	groups := strings.Split(integerPattern, ",")
	if len(groups) == 1 {
		return numberCoreGrouping{}
	}
	primary := numberCorePlaceholderCount(groups[len(groups)-1])
	secondary := primary
	if len(groups) > 2 {
		secondary = numberCorePlaceholderCount(groups[len(groups)-2])
	}
	return numberCoreGrouping{primary: primary, secondary: secondary}
}

func shouldNumberCoreGroup(integer string, grouping numberCoreGrouping, minimumGroupingDigits int) bool {
	return grouping.primary > 0 && len(integer) >= grouping.primary+minimumGroupingDigits
}

func groupNumberCoreInteger(integer string, grouping numberCoreGrouping, separator string) string {
	var groups []string
	end := len(integer)
	size := grouping.primary
	for end > 0 {
		start := end - size
		if start < 0 {
			start = 0
		}
		groups = append([]string{integer[start:end]}, groups...)
		end = start
		if grouping.secondary != 0 {
			size = grouping.secondary
		}
	}
	return strings.Join(groups, separator)
}

func applyNumberCoreSign(formatted string, negative bool, symbols map[string]string, signDisplay string) string {
	if signDisplay == NumberCoreSignDisplayNever {
		return formatted
	}
	if negative {
		return symbols["minusSign"] + formatted
	}
	if signDisplay == NumberCoreSignDisplayAlways {
		return symbols["plusSign"] + formatted
	}
	return formatted
}

func applyNumberCorePattern(pattern, formatted, percentSign, currency string) string {
	output := pattern
	if match := numberCorePatternRe.FindStringIndex(pattern); match != nil {
		output = pattern[:match[0]] + formatted + pattern[match[1]:]
	}
	if percentSign != "" {
		output = strings.ReplaceAll(output, "%", percentSign)
	}
	if currency != "" {
		output = strings.ReplaceAll(output, "¤", currency)
	}
	return output
}

func applySignedNumberCorePattern(pattern, formatted string, negative bool, symbols map[string]string, signDisplay, percentSign, currency string) string {
	positivePattern, negativePattern, hasNegativePattern := strings.Cut(pattern, ";")
	if negative && signDisplay != NumberCoreSignDisplayNever {
		if hasNegativePattern {
			return applyNumberCorePattern(negativePattern, formatted, percentSign, currency)
		}
		return symbols["minusSign"] + applyNumberCorePattern(positivePattern, formatted, percentSign, currency)
	}
	output := applyNumberCorePattern(positivePattern, formatted, percentSign, currency)
	if signDisplay == NumberCoreSignDisplayAlways {
		return symbols["plusSign"] + output
	}
	return output
}

func numberCoreCurrencyDisplay(localeData cldrNumberLocaleData, currency, display string) (string, error) {
	display, err := numberCoreOptionOneOf(display, "currencyDisplay", []string{
		NumberCoreCurrencyDisplaySymbol,
		NumberCoreCurrencyDisplayNarrowSymbol,
		NumberCoreCurrencyDisplayCode,
	})
	if err != nil {
		return "", err
	}
	if display == NumberCoreCurrencyDisplayCode {
		return numberCoreCurrencyCodeDisplay(localeData, currency), nil
	}
	data, ok := localeData.currencies[currency]
	if !ok {
		return currency, nil
	}
	if display == NumberCoreCurrencyDisplayNarrowSymbol && data.narrowSymbol != "" {
		return data.narrowSymbol, nil
	}
	if data.symbol != "" {
		return data.symbol, nil
	}
	return currency, nil
}

func numberCoreCurrencyCodeDisplay(localeData cldrNumberLocaleData, currency string) string {
	positivePattern, _, _ := strings.Cut(localeData.currencyPattern, ";")
	before := ""
	if strings.Contains(positivePattern, "#\u00a4") || strings.Contains(positivePattern, "0\u00a4") {
		before = defaultString(localeData.currencySpacing.beforeCurrency, "\u00a0")
	}
	after := ""
	if strings.Contains(positivePattern, "\u00a4#") || strings.Contains(positivePattern, "\u00a40") {
		after = defaultString(localeData.currencySpacing.afterCurrency, "\u00a0")
	}
	return before + currency + after
}

func parseNumberCoreFinite(value any) (numberCoreValue, bool) {
	if value == nil {
		return numberCoreValue{}, false
	}
	switch typed := value.(type) {
	case bool:
		return numberCoreValue{}, false
	case int:
		return parseNumberCoreDecimalText(strconv.FormatInt(int64(typed), 10))
	case int8:
		return parseNumberCoreDecimalText(strconv.FormatInt(int64(typed), 10))
	case int16:
		return parseNumberCoreDecimalText(strconv.FormatInt(int64(typed), 10))
	case int32:
		return parseNumberCoreDecimalText(strconv.FormatInt(int64(typed), 10))
	case int64:
		return parseNumberCoreDecimalText(strconv.FormatInt(typed, 10))
	case uint:
		return parseNumberCoreDecimalText(strconv.FormatUint(uint64(typed), 10))
	case uint8:
		return parseNumberCoreDecimalText(strconv.FormatUint(uint64(typed), 10))
	case uint16:
		return parseNumberCoreDecimalText(strconv.FormatUint(uint64(typed), 10))
	case uint32:
		return parseNumberCoreDecimalText(strconv.FormatUint(uint64(typed), 10))
	case uint64:
		return parseNumberCoreDecimalText(strconv.FormatUint(typed, 10))
	case float64:
		if math.IsInf(typed, 0) || math.IsNaN(typed) {
			return numberCoreValue{}, false
		}
		parsed, ok := parseNumberCoreDecimalText(strconv.FormatFloat(typed, 'g', -1, 64))
		if ok && typed == 0 && math.Signbit(typed) {
			parsed.negativeZero = true
		}
		return parsed, ok
	case float32:
		value := float64(typed)
		if math.IsInf(value, 0) || math.IsNaN(value) {
			return numberCoreValue{}, false
		}
		parsed, ok := parseNumberCoreDecimalText(strconv.FormatFloat(value, 'g', -1, 32))
		if ok && value == 0 && math.Signbit(value) {
			parsed.negativeZero = true
		}
		return parsed, ok
	case string:
		return parseNumberCoreDecimalText(typed)
	default:
		return parseNumberCoreDecimalText(fmt.Sprint(value))
	}
}

func parseNumberCoreDecimalText(value string) (numberCoreValue, bool) {
	text := strings.TrimSpace(value)
	if len([]rune(text)) > maxNumberCoreOperandLength {
		return numberCoreValue{}, false
	}
	if !numberCoreDecimalRe.MatchString(text) {
		return numberCoreValue{}, false
	}
	operand, ok := parseDecimalOperand(text)
	if !ok {
		return numberCoreValue{}, false
	}
	return numberCoreValue{
		operand:      operand,
		negativeZero: strings.HasPrefix(text, "-") && operand.digits == "0",
	}, true
}

func isSupportedNumberCoreMagnitude(value numberCoreValue) bool {
	operand := value.operand
	if operand.digits == "0" {
		return true
	}
	integerDigits := len(operand.digits) - operand.scale
	if integerDigits < 22 {
		return true
	}
	if integerDigits > 22 {
		return false
	}
	integer := operand.digits
	switch {
	case operand.scale < 0:
		integer += strings.Repeat("0", -operand.scale)
	case operand.scale > 0:
		integer = integer[:integerDigits]
	}
	return integer < "1000000000000000000000"
}

func parseNumberCoreCurrency(value string) (string, error) {
	if len([]rune(value)) > maxNumberCoreOptionLength {
		return "", badOption("currency must not exceed 256 characters.")
	}
	if !numberCoreCurrencyRe.MatchString(value) {
		return "", badOption("currency must be a three-letter ISO 4217 code.")
	}
	return strings.ToUpper(value), nil
}

func numberCoreIntegerOptionFromCall(call FunctionCall, name string) (*int, error) {
	value, err := call.OptionValue(name, "")
	if err != nil {
		return nil, err
	}
	if value == "" {
		return nil, nil
	}
	parsed, err := parseNonNegativeOption(value, name+" must be a non-negative integer.")
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

func numberCoreOptionOneOf(value, name string, allowed []string) (string, error) {
	if len([]rune(value)) > maxNumberCoreOptionLength {
		return "", badOption(name + " must not exceed 256 characters.")
	}
	for _, candidate := range allowed {
		if value == candidate {
			return value, nil
		}
	}
	return "", badOption(name + " must be one of " + strings.Join(allowed, ", ") + ".")
}

func numberCoreBooleanOption(value, name string) (bool, error) {
	if len([]rune(value)) > maxNumberCoreOptionLength {
		return false, badOption(name + " must not exceed 256 characters.")
	}
	switch value {
	case "true":
		return true, nil
	case "false":
		return false, nil
	default:
		return false, badOption(name + " must be true or false.")
	}
}

func numberCoreUseGrouping(options NumberCoreOptions) bool {
	return options.UseGrouping == nil || *options.UseGrouping
}

func numberCoreNumberPattern(pattern string) string {
	return numberCorePatternRe.FindString(pattern)
}

func numberCorePlaceholderCount(pattern string) int {
	return strings.Count(pattern, "#") + strings.Count(pattern, "0")
}

func localizeNumberCoreDigits(value, digits string) string {
	if digits == "" || digits == "0123456789" {
		return value
	}
	digitRunes := []rune(digits)
	var output strings.Builder
	for _, ch := range value {
		if ch >= '0' && ch <= '9' {
			output.WriteRune(digitRunes[ch-'0'])
		} else {
			output.WriteRune(ch)
		}
	}
	return output.String()
}

func defaultString(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
