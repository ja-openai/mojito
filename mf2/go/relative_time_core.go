package mf2

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

const defaultRelativeTimeCoreLocale = "en"
const maxRelativeTimeCoreOptionLength = 256
const maxRelativeTimeCoreOperandLength = 256
const maxRelativeTimeCoreQuantity = 1_000_000_000

var relativeTimeCoreDecimalNumberPattern = regexp.MustCompile(`^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$`)

const (
	RelativeTimeCoreStyleLong   = "long"
	RelativeTimeCoreStyleShort  = "short"
	RelativeTimeCoreStyleNarrow = "narrow"
)

const (
	RelativeTimeCoreNumericAlways = "always"
	RelativeTimeCoreNumericAuto   = "auto"
)

const (
	RelativeTimeCorePolicyPrecise = "precise"
	RelativeTimeCorePolicyCompact = "compact"
	RelativeTimeCorePolicyChat    = "chat"
)

const (
	RelativeTimeCoreUnitAuto    = "auto"
	RelativeTimeCoreUnitSecond  = "second"
	RelativeTimeCoreUnitMinute  = "minute"
	RelativeTimeCoreUnitHour    = "hour"
	RelativeTimeCoreUnitDay     = "day"
	RelativeTimeCoreUnitWeek    = "week"
	RelativeTimeCoreUnitMonth   = "month"
	RelativeTimeCoreUnitQuarter = "quarter"
	RelativeTimeCoreUnitYear    = "year"
)

type RelativeTimeCoreOptions struct {
	Locale  string
	Style   string
	Numeric string
	Policy  string
	Unit    string
}

type RelativeTimeCoreFormatter struct {
	data relativeTimeCorePreparedData
}

type RelativeTimeCoreData struct {
	LocaleMap   map[string]string            `json:"localeMap"`
	PatternSets []RelativeTimeCorePatternSet `json:"patternSets"`
}

type RelativeTimeCorePatternSet struct {
	ID   string                                         `json:"id"`
	Data map[string]map[string]RelativeTimeCoreUnitData `json:"data"`
}

type RelativeTimeCoreUnitData struct {
	Future   map[string]string `json:"future"`
	Past     map[string]string `json:"past"`
	Relative map[string]string `json:"relative"`
}

type relativeTimeCorePreparedData struct {
	localeMap   map[string]string
	patternSets map[string]map[string]map[string]RelativeTimeCoreUnitData
}

type relativeTimeCorePolicyStep struct {
	upper float64
	unit  string
}

var relativeTimeCoreStyleValues = []string{
	RelativeTimeCoreStyleLong,
	RelativeTimeCoreStyleShort,
	RelativeTimeCoreStyleNarrow,
}

var relativeTimeCoreNumericValues = []string{
	RelativeTimeCoreNumericAlways,
	RelativeTimeCoreNumericAuto,
}

var relativeTimeCorePolicyValues = []string{
	RelativeTimeCorePolicyPrecise,
	RelativeTimeCorePolicyCompact,
	RelativeTimeCorePolicyChat,
}

var relativeTimeCoreUnitValues = []string{
	RelativeTimeCoreUnitAuto,
	RelativeTimeCoreUnitSecond,
	RelativeTimeCoreUnitMinute,
	RelativeTimeCoreUnitHour,
	RelativeTimeCoreUnitDay,
	RelativeTimeCoreUnitWeek,
	RelativeTimeCoreUnitMonth,
	RelativeTimeCoreUnitQuarter,
	RelativeTimeCoreUnitYear,
}

var relativeTimeCoreUnitSeconds = map[string]float64{
	RelativeTimeCoreUnitSecond:  1,
	RelativeTimeCoreUnitMinute:  60,
	RelativeTimeCoreUnitHour:    3600,
	RelativeTimeCoreUnitDay:     86400,
	RelativeTimeCoreUnitWeek:    604800,
	RelativeTimeCoreUnitMonth:   2592000,
	RelativeTimeCoreUnitQuarter: 7776000,
	RelativeTimeCoreUnitYear:    31536000,
}

var relativeTimeCorePolicies = map[string][]relativeTimeCorePolicyStep{
	RelativeTimeCorePolicyPrecise: {
		{upper: 60, unit: RelativeTimeCoreUnitSecond},
		{upper: 3600, unit: RelativeTimeCoreUnitMinute},
		{upper: 86400, unit: RelativeTimeCoreUnitHour},
		{upper: 604800, unit: RelativeTimeCoreUnitDay},
		{upper: 2592000, unit: RelativeTimeCoreUnitWeek},
		{upper: 31536000, unit: RelativeTimeCoreUnitMonth},
		{upper: math.Inf(1), unit: RelativeTimeCoreUnitYear},
	},
	RelativeTimeCorePolicyCompact: {
		{upper: 60, unit: RelativeTimeCoreUnitSecond},
		{upper: 3600, unit: RelativeTimeCoreUnitMinute},
		{upper: 86400, unit: RelativeTimeCoreUnitHour},
		{upper: math.Inf(1), unit: RelativeTimeCoreUnitDay},
	},
	RelativeTimeCorePolicyChat: {
		{upper: 45, unit: RelativeTimeCoreUnitSecond},
		{upper: 2700, unit: RelativeTimeCoreUnitMinute},
		{upper: 79200, unit: RelativeTimeCoreUnitHour},
		{upper: 604800, unit: RelativeTimeCoreUnitDay},
		{upper: math.Inf(1), unit: RelativeTimeCoreUnitWeek},
	},
}

func RelativeTimeCoreFunctionRegistry(data RelativeTimeCoreData) FunctionRegistry {
	formatter, prepareErr := NewRelativeTimeCoreFormatter(data)
	if prepareErr == nil {
		return formatter.FunctionRegistry()
	}
	return PortableFunctionRegistry().WithFunction("relativeTime", func(call FunctionCall) (string, error) {
		return "", prepareErr
	})
}

func NewRelativeTimeCoreFormatter(data RelativeTimeCoreData) (RelativeTimeCoreFormatter, error) {
	prepared, err := prepareRelativeTimeCoreData(data)
	if err != nil {
		return RelativeTimeCoreFormatter{}, err
	}
	return RelativeTimeCoreFormatter{data: prepared}, nil
}

func FormatRelativeTimeCore(value any, data RelativeTimeCoreData, options RelativeTimeCoreOptions) (string, error) {
	formatter, err := NewRelativeTimeCoreFormatter(data)
	if err != nil {
		return "", err
	}
	return formatter.Format(value, options)
}

func FormatRelativeTimeCoreToParts(value any, data RelativeTimeCoreData, options RelativeTimeCoreOptions) ([]Part, error) {
	formatter, err := NewRelativeTimeCoreFormatter(data)
	if err != nil {
		return nil, err
	}
	return formatter.FormatToParts(value, options)
}

func (formatter RelativeTimeCoreFormatter) Format(value any, options RelativeTimeCoreOptions) (string, error) {
	return formatRelativeTimeCorePrepared(value, formatter.data, options)
}

func (formatter RelativeTimeCoreFormatter) FormatToParts(value any, options RelativeTimeCoreOptions) ([]Part, error) {
	formatted, err := formatter.Format(value, options)
	if err != nil {
		return nil, err
	}
	return []Part{{"type": "text", "value": formatted}}, nil
}

func (formatter RelativeTimeCoreFormatter) FunctionRegistry() FunctionRegistry {
	return PortableFunctionRegistry().WithFunction("relativeTime", func(call FunctionCall) (string, error) {
		value := call.RawValue
		if value == nil {
			value = call.Value
		}
		style, err := callStringOption(call, "style", RelativeTimeCoreStyleShort)
		if err != nil {
			return "", err
		}
		numeric, err := callStringOption(call, "numeric", RelativeTimeCoreNumericAlways)
		if err != nil {
			return "", err
		}
		policy, err := callStringOption(call, "policy", RelativeTimeCorePolicyPrecise)
		if err != nil {
			return "", err
		}
		unit, err := callStringOption(call, "unit", RelativeTimeCoreUnitAuto)
		if err != nil {
			return "", err
		}
		options := RelativeTimeCoreOptions{
			Locale:  call.Locale,
			Style:   style,
			Numeric: numeric,
			Policy:  policy,
			Unit:    unit,
		}
		formatted, err := formatter.Format(value, options)
		if err == nil {
			return formatted, nil
		}
		code, ok := err.(Error)
		sourceValue, hasSourceValue := relativeTimeCoreSourceValue(call.InheritedSource)
		if !ok || code.Code != "bad-operand" || !hasSourceValue {
			return "", err
		}
		return formatter.Format(sourceValue, options)
	})
}

func relativeTimeCoreSourceValue(source *FunctionSource) (string, bool) {
	for current := source; current != nil; current = current.Inherited {
		if isDecimalSourceFunction(current.Function) {
			return current.Value, true
		}
	}
	return "", false
}

func formatRelativeTimeCorePrepared(value any, data relativeTimeCorePreparedData, options RelativeTimeCoreOptions) (string, error) {
	style, err := relativeTimeCoreOptionOneOf(defaultString(options.Style, RelativeTimeCoreStyleShort), "style", relativeTimeCoreStyleValues)
	if err != nil {
		return "", err
	}
	numeric, err := relativeTimeCoreOptionOneOf(defaultString(options.Numeric, RelativeTimeCoreNumericAlways), "numeric", relativeTimeCoreNumericValues)
	if err != nil {
		return "", err
	}
	policy, err := relativeTimeCoreOptionOneOf(defaultString(options.Policy, RelativeTimeCorePolicyPrecise), "policy", relativeTimeCorePolicyValues)
	if err != nil {
		return "", err
	}
	unit, err := relativeTimeCoreOptionOneOf(defaultString(options.Unit, RelativeTimeCoreUnitAuto), "unit", relativeTimeCoreUnitValues)
	if err != nil {
		return "", err
	}
	seconds, err := parseRelativeTimeCoreFinite(value)
	if err != nil {
		return "", err
	}
	selectedUnit := unit
	if unit == RelativeTimeCoreUnitAuto {
		selectedUnit = selectRelativeTimeCoreUnit(seconds, policy)
	}
	quantity, err := relativeTimeCoreQuantity(seconds, selectedUnit)
	if err != nil {
		return "", err
	}
	locale, err := localeOption(options.Locale, defaultRelativeTimeCoreLocale)
	if err != nil {
		return "", err
	}

	if useRelativeTimeCoreZero(policy, numeric, seconds) {
		if relative, ok, err := relativeTimeCoreTerm(data, locale, style, selectedUnit, "0"); err != nil {
			return "", err
		} else if ok {
			return relative, nil
		}
	}
	if numeric == RelativeTimeCoreNumericAuto {
		if offset, ok := relativeTimeCoreOffset(seconds, selectedUnit, quantity); ok {
			if relative, ok, err := relativeTimeCoreTerm(data, locale, style, selectedUnit, offset); err != nil {
				return "", err
			} else if ok {
				return relative, nil
			}
		}
	}

	direction := "future"
	if isNegativeRelativeTimeCore(seconds) {
		direction = "past"
	}
	category := selectCardinal(locale, quantity)
	pattern, err := relativeTimeCorePattern(data, locale, style, selectedUnit, direction, category)
	if err != nil {
		return "", err
	}
	return strings.ReplaceAll(pattern, "{0}", strconv.Itoa(quantity)), nil
}

func prepareRelativeTimeCoreData(data RelativeTimeCoreData) (relativeTimeCorePreparedData, error) {
	if len(data.LocaleMap) == 0 || len(data.PatternSets) == 0 {
		return relativeTimeCorePreparedData{}, mf2Error("missing-locale-data", "Relative-time core data has an unsupported shape.")
	}
	patternSets := map[string]map[string]map[string]RelativeTimeCoreUnitData{}
	for _, item := range data.PatternSets {
		if item.ID != "" && len(item.Data) > 0 {
			patternSets[item.ID] = item.Data
		}
	}
	if len(patternSets) == 0 {
		return relativeTimeCorePreparedData{}, mf2Error("missing-locale-data", "Relative-time core data has an unsupported shape.")
	}
	return relativeTimeCorePreparedData{localeMap: data.LocaleMap, patternSets: patternSets}, nil
}

func parseRelativeTimeCoreFinite(value any) (float64, error) {
	if value == nil {
		return 0, badOperand("Relative-time core requires a finite numeric value.")
	}
	if _, ok := value.(bool); ok {
		return 0, badOperand("Relative-time core requires a finite numeric value.")
	}
	text := strings.TrimSpace(fmt.Sprint(value))
	if text == "" {
		return 0, badOperand("Relative-time core requires a finite numeric value.")
	}
	if runeCountExceeds(text, maxRelativeTimeCoreOperandLength) {
		return 0, badOperand("Relative-time core requires a finite numeric value.")
	}
	if !relativeTimeCoreDecimalNumberPattern.MatchString(text) {
		return 0, badOperand("Relative-time core requires a finite numeric value.")
	}
	parsed, err := strconv.ParseFloat(text, 64)
	if err != nil || math.IsInf(parsed, 0) || math.IsNaN(parsed) {
		return 0, badOperand("Relative-time core requires a finite numeric value.")
	}
	return parsed, nil
}

func relativeTimeCoreOptionOneOf(value, name string, allowed []string) (string, error) {
	if runeCountExceeds(value, maxRelativeTimeCoreOptionLength) {
		return "", badOption(name + " must not exceed 256 characters.")
	}
	for _, candidate := range allowed {
		if value == candidate {
			return value, nil
		}
	}
	return "", badOption(name + " must be one of " + strings.Join(allowed, ", ") + ".")
}

func selectRelativeTimeCoreUnit(seconds float64, policy string) string {
	absolute := math.Abs(seconds)
	for _, step := range relativeTimeCorePolicies[policy] {
		if absolute < step.upper {
			return step.unit
		}
	}
	return RelativeTimeCoreUnitYear
}

func relativeTimeCoreQuantity(seconds float64, unit string) (int, error) {
	absolute := math.Abs(seconds)
	if absolute == 0 {
		return 0, nil
	}
	quantity := math.Max(1, math.Floor(absolute/relativeTimeCoreUnitSeconds[unit]+0.5))
	if quantity > maxRelativeTimeCoreQuantity {
		return 0, badOperand("Relative-time core quantity is outside the supported range.")
	}
	return int(quantity), nil
}

func useRelativeTimeCoreZero(policy, numeric string, seconds float64) bool {
	return policy == RelativeTimeCorePolicyChat && numeric == RelativeTimeCoreNumericAuto && math.Abs(seconds) < 45
}

func relativeTimeCoreOffset(seconds float64, unit string, quantity int) (string, bool) {
	if quantity == 0 {
		return "0", true
	}
	if math.Abs(seconds) != float64(quantity)*relativeTimeCoreUnitSeconds[unit] {
		return "", false
	}
	if isNegativeRelativeTimeCore(seconds) {
		return "-" + strconv.Itoa(quantity), true
	}
	return strconv.Itoa(quantity), true
}

func isNegativeRelativeTimeCore(seconds float64) bool {
	return math.Signbit(seconds)
}

func relativeTimeCoreTerm(
	data relativeTimeCorePreparedData,
	locale string,
	style string,
	unit string,
	offset string,
) (string, bool, error) {
	unitData, err := relativeTimeCoreUnitData(data, locale, style, unit)
	if err != nil {
		return "", false, err
	}
	relative, ok := unitData.Relative[offset]
	return relative, ok, nil
}

func relativeTimeCorePattern(
	data relativeTimeCorePreparedData,
	locale string,
	style string,
	unit string,
	direction string,
	category string,
) (string, error) {
	unitData, err := relativeTimeCoreUnitData(data, locale, style, unit)
	if err != nil {
		return "", err
	}
	patterns := unitData.Future
	if direction == "past" {
		patterns = unitData.Past
	}
	if pattern, ok := patterns[category]; ok {
		return pattern, nil
	}
	if pattern, ok := patterns["other"]; ok {
		return pattern, nil
	}
	return "", mf2Error("missing-locale-data", "Missing relative-time pattern for "+locale+"/"+style+"/"+unit+"/"+direction+".")
}

func relativeTimeCoreUnitData(
	data relativeTimeCorePreparedData,
	locale string,
	style string,
	unit string,
) (RelativeTimeCoreUnitData, error) {
	patternSet, err := relativeTimeCorePatternSetFor(data, locale)
	if err != nil {
		return RelativeTimeCoreUnitData{}, err
	}
	styleData, ok := patternSet[style]
	if !ok {
		return RelativeTimeCoreUnitData{}, mf2Error("missing-locale-data", "Missing relative-time unit data for "+locale+"/"+style+"/"+unit+".")
	}
	unitData, ok := styleData[unit]
	if !ok {
		return RelativeTimeCoreUnitData{}, mf2Error("missing-locale-data", "Missing relative-time unit data for "+locale+"/"+style+"/"+unit+".")
	}
	return unitData, nil
}

func relativeTimeCorePatternSetFor(
	data relativeTimeCorePreparedData,
	locale string,
) (map[string]map[string]RelativeTimeCoreUnitData, error) {
	for _, candidate := range localeLookupChain(locale) {
		setID, ok := data.localeMap[candidate]
		if !ok {
			continue
		}
		patternSet, ok := data.patternSets[setID]
		if !ok {
			return nil, mf2Error("missing-locale-data", "Missing relative-time pattern set "+setID+".")
		}
		return patternSet, nil
	}
	return nil, mf2Error("missing-locale-data", "Missing relative-time locale data for "+locale+".")
}
