package mf2

import (
	"fmt"
	"math"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"
)

const defaultDateTimeCoreLocale = "en-US"
const dateTimeCoreUTC = "UTC"
const dateTimeCoreMinTimestampMillis = int64(-62_135_596_800_000)
const dateTimeCoreMaxTimestampMillis = int64(253_402_300_799_999)
const dateTimeCoreMaxOptionLength = 256
const dateTimeCoreMaxOperandLength = 256
const dateTimeCoreMaxSkeletonFieldWidth = 32
const dateTimeCoreMaxSkeletonLength = 256
const dateTimeCoreSemanticSkeletonPrefix = "semantic:"
const dateTimeCoreSkeletonFieldOrder = "GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx"
const dateTimeCoreSkeletonTimeFields = "abBhHkKJmsSAzZOvVXx"
const dateTimeCoreSkeletonHourFields = "hHkK"

const (
	DateTimeCoreStyleFull   = "full"
	DateTimeCoreStyleLong   = "long"
	DateTimeCoreStyleMedium = "medium"
	DateTimeCoreStyleShort  = "short"
)

type DateTimeCoreOptions struct {
	Locale        string
	Style         string
	DateStyle     string
	TimeStyle     string
	Length        string
	Precision     string
	DateLength    string
	TimePrecision string
	Skeleton      string
	HourCycle     string
	TimeZone      string
	Calendar      string
}

type dateTimeCoreQuotedPattern struct {
	value     string
	nextIndex int
}

var dateTimeCoreWeekdayKeys = []string{"sun", "mon", "tue", "wed", "thu", "fri", "sat"}
var dateTimeCoreSemanticFieldOrder = []string{"era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear", "dayperiod", "hour", "minute", "second", "fractionalsecond", "millisecondsinday", "time", "zone"}
var dateTimeCoreSemanticDateFieldOrder = []string{"era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear"}
var dateTimeCoreSemanticTimeFieldOrder = []string{"hour", "minute", "second", "fractionalsecond", "millisecondsinday"}
var dateTimeCoreSemanticOptionKeys = []string{"fields", "length", "alignment", "yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle", "timeprecision", "timestyle", "fractionalsecond", "hourcycle", "zonestyle"}
var dateTimeCoreSemanticDirectStyleOptionKeys = []string{"fields", "length", "timestyle"}
var dateTimeCoreSemanticStyleOptionKeys = []string{"yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle"}
var dateTimeCoreSemanticDateStyleValues = []string{"auto", "numeric", "2-digit", "short", "long", "narrow"}
var dateTimeCoreSemanticNumericStyleValues = []string{"auto", "numeric", "2-digit"}
var dateTimeCoreSemanticTextStyleValues = []string{"auto", "short", "long", "narrow"}
var dateTimeCoreSemanticDateFieldSets = []string{"day", "weekday", "day,weekday", "month,day", "month,day,weekday", "era,year,month,day", "era,year,month,day,weekday", "year,month,day", "year,month,day,weekday"}
var dateTimeCoreSemanticCalendarPeriodFieldSets = []string{"era", "year", "quarter", "month", "era,year", "era,year,quarter", "era,year,month", "era,year,weekofyear", "era,year,month,weekofmonth", "year,quarter", "year,month", "year,weekofyear", "month,weekofmonth", "year,month,weekofmonth", "dayofyear", "dayofweekinmonth", "modifiedjulianday"}
var dateTimeCoreSemanticTimeFieldSets = []string{"hour", "minute", "second", "millisecondsinday", "hour,minute", "hour,minute,second", "hour,minute,second,fractionalsecond", "minute,second", "minute,second,fractionalsecond", "second,fractionalsecond"}

func DateTimeCoreFunctionRegistry() FunctionRegistry {
	return PortableFunctionRegistry().
		WithFunction("date", formatDateTimeCoreCallDate).
		WithFunction("time", formatDateTimeCoreCallTime).
		WithFunction("datetime", formatDateTimeCoreCallDateTime)
}

func FormatDateCore(value any, options DateTimeCoreOptions) (string, error) {
	locale, err := localeOption(options.Locale, defaultDateTimeCoreLocale)
	if err != nil {
		return "", err
	}
	localeData := resolveDateTimeCoreLocaleData(locale)
	if err := validateDateTimeCoreOptions(options, locale); err != nil {
		return "", err
	}
	if err := resolveDateTimeCoreNumberingSystemData(&localeData, locale); err != nil {
		return "", err
	}
	preserveSameFamilyHourCycle := options.HourCycle != ""
	hourCycle, err := validateDateTimeCoreHourCycle(firstNonEmptyString(options.HourCycle, dateTimeCoreLocaleUnicodeExtension(locale, "hc")))
	if err != nil {
		return "", err
	}
	offsetMinutes, err := parseDateTimeCoreTimeZone(options.TimeZone)
	if err != nil {
		return "", err
	}
	date, err := parseDateTimeCore(value)
	if err != nil {
		return "", err
	}
	date = date.In(time.FixedZone(dateTimeCoreOffsetZoneName(offsetMinutes), offsetMinutes*60))
	if options.Skeleton != "" {
		return formatDateTimeCoreSkeleton(options.Skeleton, date, localeData, hourCycle, preserveSameFamilyHourCycle)
	}
	style, err := dateTimeCoreStyleOption(firstString(options.DateStyle, options.Length, options.Style, DateTimeCoreStyleMedium), "dateStyle")
	if err != nil {
		return "", err
	}
	return formatDateTimeCorePattern(localeData.dateFormats[style], date, localeData)
}

func FormatTimeCore(value any, options DateTimeCoreOptions) (string, error) {
	locale, err := localeOption(options.Locale, defaultDateTimeCoreLocale)
	if err != nil {
		return "", err
	}
	localeData := resolveDateTimeCoreLocaleData(locale)
	if err := validateDateTimeCoreOptions(options, locale); err != nil {
		return "", err
	}
	if err := resolveDateTimeCoreNumberingSystemData(&localeData, locale); err != nil {
		return "", err
	}
	preserveSameFamilyHourCycle := options.HourCycle != ""
	hourCycle, err := validateDateTimeCoreHourCycle(firstNonEmptyString(options.HourCycle, dateTimeCoreLocaleUnicodeExtension(locale, "hc")))
	if err != nil {
		return "", err
	}
	offsetMinutes, err := parseDateTimeCoreTimeZone(options.TimeZone)
	if err != nil {
		return "", err
	}
	date, err := parseDateTimeCore(value)
	if err != nil {
		return "", err
	}
	date = date.In(time.FixedZone(dateTimeCoreOffsetZoneName(offsetMinutes), offsetMinutes*60))
	if options.Skeleton != "" {
		return formatDateTimeCoreSkeleton(options.Skeleton, date, localeData, hourCycle, preserveSameFamilyHourCycle)
	}
	style, err := dateTimeCoreTimeStyleOption(options.TimeStyle, options.TimePrecision, options.Precision, options.Style, DateTimeCoreStyleMedium)
	if err != nil {
		return "", err
	}
	return formatDateTimeCoreTimeStylePattern(localeData.timeFormats[style], date, localeData, hourCycle, preserveSameFamilyHourCycle)
}

func FormatDateTimeCore(value any, options DateTimeCoreOptions) (string, error) {
	locale, err := localeOption(options.Locale, defaultDateTimeCoreLocale)
	if err != nil {
		return "", err
	}
	localeData := resolveDateTimeCoreLocaleData(locale)
	if err := validateDateTimeCoreOptions(options, locale); err != nil {
		return "", err
	}
	if err := resolveDateTimeCoreNumberingSystemData(&localeData, locale); err != nil {
		return "", err
	}
	preserveSameFamilyHourCycle := options.HourCycle != ""
	hourCycle, err := validateDateTimeCoreHourCycle(firstNonEmptyString(options.HourCycle, dateTimeCoreLocaleUnicodeExtension(locale, "hc")))
	if err != nil {
		return "", err
	}
	offsetMinutes, err := parseDateTimeCoreTimeZone(options.TimeZone)
	if err != nil {
		return "", err
	}
	date, err := parseDateTimeCore(value)
	if err != nil {
		return "", err
	}
	date = date.In(time.FixedZone(dateTimeCoreOffsetZoneName(offsetMinutes), offsetMinutes*60))
	if options.Skeleton != "" {
		return formatDateTimeCoreSkeleton(options.Skeleton, date, localeData, hourCycle, preserveSameFamilyHourCycle)
	}
	dateStyle, err := dateTimeCoreStyleOption(firstString(options.DateStyle, options.DateLength, options.Length, options.Style, DateTimeCoreStyleMedium), "dateStyle")
	if err != nil {
		return "", err
	}
	timeStyle, err := dateTimeCoreTimeStyleOption(options.TimeStyle, options.TimePrecision, options.Precision, options.Style, DateTimeCoreStyleMedium)
	if err != nil {
		return "", err
	}
	datePart, err := formatDateTimeCorePattern(localeData.dateFormats[dateStyle], date, localeData)
	if err != nil {
		return "", err
	}
	timePart, err := formatDateTimeCoreTimeStylePattern(localeData.timeFormats[timeStyle], date, localeData, hourCycle, preserveSameFamilyHourCycle)
	if err != nil {
		return "", err
	}
	return strings.NewReplacer("{1}", datePart, "{0}", timePart).Replace(dateTimeCoreStyleJoinPattern(localeData, dateStyle)), nil
}

func formatDateTimeCoreCallDate(call FunctionCall) (string, error) {
	dateStyle, err := dateTimeCoreCallStyle(call, "dateStyle", "length", DateTimeCoreStyleMedium, false)
	if err != nil {
		return "", err
	}
	timeZone, err := dateTimeCoreCallOption(call, "timeZone", dateTimeCoreUTC)
	if err != nil {
		return "", err
	}
	calendar, err := dateTimeCoreCallOption(call, "calendar", "")
	if err != nil {
		return "", err
	}
	skeleton, err := dateTimeCoreCallOption(call, "skeleton", "")
	if err != nil {
		return "", err
	}
	hourCycle, err := dateTimeCoreCallOption(call, "hourCycle", "")
	if err != nil {
		return "", err
	}
	return FormatDateCore(dateTimeCoreCallValue(call), DateTimeCoreOptions{
		Locale:    call.Locale,
		DateStyle: dateStyle,
		Skeleton:  skeleton,
		HourCycle: hourCycle,
		TimeZone:  timeZone,
		Calendar:  calendar,
	})
}

func formatDateTimeCoreCallTime(call FunctionCall) (string, error) {
	timeStyle, err := dateTimeCoreCallStyle(call, "timeStyle", "precision", DateTimeCoreStyleMedium, true)
	if err != nil {
		return "", err
	}
	timeZone, err := dateTimeCoreCallOption(call, "timeZone", dateTimeCoreUTC)
	if err != nil {
		return "", err
	}
	calendar, err := dateTimeCoreCallOption(call, "calendar", "")
	if err != nil {
		return "", err
	}
	skeleton, err := dateTimeCoreCallOption(call, "skeleton", "")
	if err != nil {
		return "", err
	}
	hourCycle, err := dateTimeCoreCallOption(call, "hourCycle", "")
	if err != nil {
		return "", err
	}
	return FormatTimeCore(dateTimeCoreCallValue(call), DateTimeCoreOptions{
		Locale:    call.Locale,
		TimeStyle: timeStyle,
		Skeleton:  skeleton,
		HourCycle: hourCycle,
		TimeZone:  timeZone,
		Calendar:  calendar,
	})
}

func formatDateTimeCoreCallDateTime(call FunctionCall) (string, error) {
	dateStyle, err := dateTimeCoreCallStyle(call, "dateStyle", "dateLength", DateTimeCoreStyleMedium, false)
	if err != nil {
		return "", err
	}
	timeStyle, err := dateTimeCoreCallStyle(call, "timeStyle", "timePrecision", DateTimeCoreStyleMedium, true)
	if err != nil {
		return "", err
	}
	timeZone, err := dateTimeCoreCallOption(call, "timeZone", dateTimeCoreUTC)
	if err != nil {
		return "", err
	}
	calendar, err := dateTimeCoreCallOption(call, "calendar", "")
	if err != nil {
		return "", err
	}
	skeleton, err := dateTimeCoreCallOption(call, "skeleton", "")
	if err != nil {
		return "", err
	}
	hourCycle, err := dateTimeCoreCallOption(call, "hourCycle", "")
	if err != nil {
		return "", err
	}
	return FormatDateTimeCore(dateTimeCoreCallValue(call), DateTimeCoreOptions{
		Locale:    call.Locale,
		DateStyle: dateStyle,
		TimeStyle: timeStyle,
		Skeleton:  skeleton,
		HourCycle: hourCycle,
		TimeZone:  timeZone,
		Calendar:  calendar,
	})
}

func dateTimeCoreCallValue(call FunctionCall) any {
	if call.InheritedSource != nil {
		return call.InheritedSource.Value
	}
	if call.RawValue != nil {
		return call.RawValue
	}
	return call.Value
}

func resolveDateTimeCoreLocaleData(locale string) cldrDateTimeLocaleData {
	for _, candidate := range localeLookupChain(locale) {
		if data, ok := cldrDateTimeLocales[candidate]; ok {
			return data
		}
		for _, data := range cldrDateTimeLocales {
			if data.sourceLocale == candidate || data.numbersSourceLocale == candidate {
				return data
			}
		}
	}
	return cldrDateTimeLocales[defaultDateTimeCoreLocale]
}

func dateTimeCoreLocaleUnicodeExtension(locale, key string) string {
	parts := []string{}
	for _, part := range strings.Split(strings.ReplaceAll(strings.TrimSpace(locale), "_", "-"), "-") {
		if part != "" {
			parts = append(parts, strings.ToLower(part))
		}
	}
	index := -1
	for candidateIndex, part := range parts {
		if part == "u" {
			index = candidateIndex + 1
			break
		}
	}
	for index >= 0 && index < len(parts) {
		part := parts[index]
		if len(part) == 1 {
			return ""
		}
		if len(part) != 2 {
			index++
			continue
		}
		end := index + 1
		for end < len(parts) && len(parts[end]) > 2 {
			end++
		}
		if part == key && end > index+1 {
			return parts[index+1]
		}
		index = end
	}
	return ""
}

func firstNonEmptyString(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func resolveDateTimeCoreNumberingSystemData(localeData *cldrDateTimeLocaleData, locale string) error {
	numberingSystem := dateTimeCoreLocaleUnicodeExtension(locale, "nu")
	if numberingSystem == "" {
		return nil
	}
	digits, ok := dateTimeCoreNumberingSystemDigits(numberingSystem)
	if !ok {
		return badOption("Date/time core does not include data for the requested numbering system.")
	}
	localeData.numberingSystemDigits = digits
	return nil
}

func dateTimeCoreNumberingSystemDigits(numberingSystem string) (string, bool) {
	if numberingSystem == "latn" {
		return "0123456789", true
	}
	for _, localeData := range cldrDateTimeLocales {
		if localeData.numberingSystem == numberingSystem && localeData.numberingSystemDigits != "" {
			return localeData.numberingSystemDigits, true
		}
	}
	return "", false
}

func validateDateTimeCoreOptions(options DateTimeCoreOptions, locale string) error {
	calendar := firstNonEmptyString(options.Calendar, dateTimeCoreLocaleUnicodeExtension(locale, "ca"))
	if len([]rune(calendar)) > dateTimeCoreMaxOptionLength {
		return badOption("calendar must not exceed 256 characters.")
	}
	if calendar != "" && calendar != "gregorian" && calendar != "gregory" {
		return badOption("Date/time core currently supports only the gregorian/gregory calendar.")
	}
	return nil
}

func validateDateTimeCoreHourCycle(value string) (string, error) {
	if value == "" {
		return "", nil
	}
	if len([]rune(value)) > dateTimeCoreMaxOptionLength {
		return "", badOption("hourCycle must not exceed 256 characters.")
	}
	if value == "h11" || value == "h12" || value == "h23" || value == "h24" {
		return value, nil
	}
	return "", badOption("hourCycle must be one of h11, h12, h23, h24.")
}

func parseDateTimeCoreTimeZone(value string) (int, error) {
	if value != "" && len([]rune(value)) > dateTimeCoreMaxOptionLength {
		return 0, badOption("timeZone must not exceed 256 characters.")
	}
	text := strings.TrimSpace(defaultString(value, dateTimeCoreUTC))
	if text == "" || text == dateTimeCoreUTC || text == "Etc/UTC" || text == "Z" || text == "GMT" || text == "Etc/GMT" {
		return 0, nil
	}
	if offsetMinutes, ok := parseDateTimeCoreEtcGmtOffsetMinutes(text); ok {
		return offsetMinutes, nil
	}
	offsetText := text
	if strings.HasPrefix(text, "UTC+") || strings.HasPrefix(text, "UTC-") || strings.HasPrefix(text, "GMT+") || strings.HasPrefix(text, "GMT-") {
		offsetText = text[3:]
	}
	offsetMinutes, ok := parseDateTimeCoreOffsetMinutes(offsetText)
	if !ok {
		return 0, badOption("Date/time core supports only UTC or fixed-offset time zones.")
	}
	return offsetMinutes, nil
}

func parseDateTimeCoreEtcGmtOffsetMinutes(value string) (int, bool) {
	const prefix = "Etc/GMT"
	if !strings.HasPrefix(value, prefix) || len(value) <= len(prefix) {
		return 0, false
	}
	sign := value[len(prefix)]
	if sign != '+' && sign != '-' {
		return 0, false
	}
	hourText := value[len(prefix)+1:]
	if hourText == "" || len(hourText) > 2 {
		return 0, false
	}
	hours, err := strconv.Atoi(hourText)
	if err != nil || hours > 14 {
		return 0, false
	}
	offset := hours * 60
	if sign == '+' {
		offset = -offset
	}
	return offset, true
}

func parseDateTimeCoreOffsetMinutes(value string) (int, bool) {
	if len(value) < 2 {
		return 0, false
	}
	sign := value[0]
	if sign != '+' && sign != '-' {
		return 0, false
	}
	body := value[1:]
	hourText := body
	minuteText := "00"
	if before, after, ok := strings.Cut(body, ":"); ok {
		hourText = before
		minuteText = after
	} else if len(body) > 2 {
		hourText = body[:len(body)-2]
		minuteText = body[len(body)-2:]
	}
	if len(hourText) == 0 || len(hourText) > 2 || len(minuteText) != 2 {
		return 0, false
	}
	hours, err := strconv.Atoi(hourText)
	if err != nil {
		return 0, false
	}
	minutes, err := strconv.Atoi(minuteText)
	if err != nil {
		return 0, false
	}
	if hours > 18 || minutes > 59 || (hours == 18 && minutes != 0) {
		return 0, false
	}
	total := hours*60 + minutes
	if sign == '-' {
		total = -total
	}
	return total, true
}

func dateTimeCoreOffsetZoneName(offsetMinutes int) string {
	if offsetMinutes == 0 {
		return dateTimeCoreUTC
	}
	return dateTimeCoreExtendedOffset(offsetMinutes, true)
}

func parseDateTimeCore(value any) (time.Time, error) {
	switch typed := value.(type) {
	case time.Time:
		return typed.UTC(), nil
	case int:
		return parseDateTimeCoreTimestampMillis(int64(typed))
	case int64:
		return parseDateTimeCoreTimestampMillis(typed)
	case float64:
		if math.IsNaN(typed) || typed < float64(dateTimeCoreMinTimestampMillis) || typed > float64(dateTimeCoreMaxTimestampMillis) {
			return time.Time{}, badOperand("Date/time core requires a valid host date/time value or ISO date string.")
		}
		return parseDateTimeCoreTimestampMillis(int64(typed))
	case string:
		return parseDateTimeCoreText(typed)
	default:
		return parseDateTimeCoreText(fmt.Sprint(value))
	}
}

func parseDateTimeCoreTimestampMillis(value int64) (time.Time, error) {
	if value < dateTimeCoreMinTimestampMillis || value > dateTimeCoreMaxTimestampMillis {
		return time.Time{}, badOperand("Date/time core requires a valid host date/time value or ISO date string.")
	}
	return time.Unix(value/int64(time.Second/time.Millisecond), (value%int64(time.Second/time.Millisecond))*int64(time.Millisecond)).UTC(), nil
}

func parseDateTimeCoreText(value string) (time.Time, error) {
	text := strings.TrimSpace(value)
	if len([]rune(text)) > dateTimeCoreMaxOperandLength {
		return time.Time{}, badOperand("Date/time core requires a valid host date/time value or ISO date string.")
	}
	if err := validateDateTimeCoreTextOffset(text); err != nil {
		return time.Time{}, err
	}
	for _, layout := range []string{
		time.RFC3339Nano,
		"2006-01-02T15:04:05",
		"2006-01-02T15:04",
		"2006-01-02",
	} {
		parsed, err := time.Parse(layout, text)
		if err == nil {
			return parsed.UTC(), nil
		}
	}
	return time.Time{}, badOperand("Date/time core requires a valid host date/time value or ISO date string.")
}

func validateDateTimeCoreTextOffset(text string) error {
	timeIndex := strings.IndexByte(text, 'T')
	if timeIndex < 0 || strings.HasSuffix(text, "Z") {
		return nil
	}
	for index := len(text) - 1; index > timeIndex; index-- {
		if text[index] != '+' && text[index] != '-' {
			continue
		}
		if _, ok := parseDateTimeCoreOffsetMinutes(text[index:]); !ok {
			return badOperand("Date/time core requires a valid host date/time value or ISO date string.")
		}
		return nil
	}
	return nil
}

func formatDateTimeCorePattern(pattern string, value time.Time, localeData cldrDateTimeLocaleData) (string, error) {
	runes := []rune(pattern)
	var output strings.Builder
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			quoted := readDateTimeCoreQuotedPattern(runes, index)
			output.WriteString(quoted.value)
			index = quoted.nextIndex
		} else if isDateTimeCoreASCIILetter(ch) {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			formatted, err := formatDateTimeCoreField(ch, end-index, value, localeData)
			if err != nil {
				return "", err
			}
			output.WriteString(formatted)
			index = end
		} else {
			output.WriteRune(ch)
			index++
		}
	}
	return output.String(), nil
}

func readDateTimeCoreQuotedPattern(pattern []rune, start int) dateTimeCoreQuotedPattern {
	if start+1 < len(pattern) && pattern[start+1] == '\'' {
		return dateTimeCoreQuotedPattern{value: "'", nextIndex: start + 2}
	}
	var output strings.Builder
	index := start + 1
	for index < len(pattern) {
		if pattern[index] == '\'' {
			if index+1 < len(pattern) && pattern[index+1] == '\'' {
				output.WriteRune('\'')
				index += 2
			} else {
				return dateTimeCoreQuotedPattern{value: output.String(), nextIndex: index + 1}
			}
		} else {
			output.WriteRune(pattern[index])
			index++
		}
	}
	return dateTimeCoreQuotedPattern{value: output.String(), nextIndex: index}
}

func formatDateTimeCoreSkeleton(skeleton string, value time.Time, localeData cldrDateTimeLocaleData, hourCycle string, preserveSameFamilyHourCycle bool) (string, error) {
	if len([]rune(skeleton)) > dateTimeCoreMaxSkeletonLength {
		return "", badOption("Date/time skeleton is too large.")
	}
	if formatted, ok, err := formatDateTimeCoreSemanticStyleSkeleton(skeleton, value, localeData, hourCycle, preserveSameFamilyHourCycle); ok || err != nil {
		return formatted, err
	}
	canonical, err := canonicalDateTimeCoreSkeleton(skeleton, localeData, hourCycle, value)
	if err != nil {
		return "", err
	}
	suppressDayPeriod := shouldSuppressDateTimeCoreDayPeriod(skeleton)
	dateTimeJoinStyle, err := dateTimeCoreSkeletonDateTimeJoinStyle(skeleton)
	if err != nil {
		return "", err
	}
	if pattern, ok := dateTimeCoreSkeletonPattern(canonical, localeData); ok {
		if suppressDayPeriod {
			pattern = stripDateTimeCoreDayPeriodPatternFields(pattern)
		}
		return formatDateTimeCorePattern(pattern, value, localeData)
	}
	return formatDateTimeCoreComposedSkeleton(skeleton, canonical, value, localeData, suppressDayPeriod, dateTimeJoinStyle)
}

func dateTimeCoreSkeletonDateTimeJoinStyle(skeleton string) (string, error) {
	if !strings.HasPrefix(skeleton, dateTimeCoreSemanticSkeletonPrefix) {
		return DateTimeCoreStyleMedium, nil
	}
	options, err := parseDateTimeCoreSemanticSkeletonOptions(strings.TrimPrefix(skeleton, dateTimeCoreSemanticSkeletonPrefix))
	if err != nil {
		return "", err
	}
	return dateTimeCoreSemanticOption(options, "length", DateTimeCoreStyleMedium, []string{DateTimeCoreStyleFull, DateTimeCoreStyleLong, DateTimeCoreStyleMedium, DateTimeCoreStyleShort})
}

func formatDateTimeCoreSemanticStyleSkeleton(skeleton string, value time.Time, localeData cldrDateTimeLocaleData, hourCycle string, preserveSameFamilyHourCycle bool) (string, bool, error) {
	if !strings.HasPrefix(skeleton, dateTimeCoreSemanticSkeletonPrefix) {
		return "", false, nil
	}
	options, err := parseDateTimeCoreSemanticSkeletonOptions(strings.TrimPrefix(skeleton, dateTimeCoreSemanticSkeletonPrefix))
	if err != nil {
		return "", false, err
	}
	fields, err := parseDateTimeCoreSemanticSkeletonFields(options)
	if err != nil {
		return "", false, err
	}
	if err := validateDateTimeCoreSemanticSkeleton(fields, options); err != nil {
		return "", false, err
	}
	for key := range options {
		if !slices.Contains(dateTimeCoreSemanticDirectStyleOptionKeys, key) {
			return "", false, nil
		}
	}

	length, err := dateTimeCoreSemanticOption(options, "length", DateTimeCoreStyleMedium, []string{DateTimeCoreStyleFull, DateTimeCoreStyleLong, DateTimeCoreStyleMedium, DateTimeCoreStyleShort})
	if err != nil {
		return "", false, err
	}
	timeStyle, err := dateTimeCoreSemanticOption(options, "timestyle", "auto", []string{"auto", DateTimeCoreStyleShort, DateTimeCoreStyleMedium, DateTimeCoreStyleLong, DateTimeCoreStyleFull})
	if err != nil {
		return "", false, err
	}
	dateKey := dateTimeCoreSemanticFieldSetKey(fields, dateTimeCoreSemanticDateFieldOrder)
	expectedDateKey := "year,month,day"
	if length == DateTimeCoreStyleFull {
		expectedDateKey = "year,month,day,weekday"
	}
	hasDate := dateKey != ""
	hasTime := slices.Contains(fields, "time")
	hasZone := slices.Contains(fields, "zone")
	if dateTimeCoreSemanticFieldSetKey(fields, dateTimeCoreSemanticTimeFieldOrder) != "" {
		return "", false, nil
	}
	if hasDate && dateKey != expectedDateKey {
		return "", false, nil
	}
	if hasTime && options["timestyle"] == "" {
		return "", false, nil
	}
	if !hasTime && (hasZone || timeStyle != "auto") {
		return "", false, nil
	}
	if hasTime && hasZone != dateTimeCoreSemanticTimeStyleHasZone(timeStyle) {
		return "", false, nil
	}
	expectedFieldCount := 0
	if hasDate {
		expectedFieldCount += len(strings.Split(expectedDateKey, ","))
	}
	if hasTime {
		expectedFieldCount++
	}
	if hasZone {
		expectedFieldCount++
	}
	if len(fields) != expectedFieldCount {
		return "", false, nil
	}

	if hasDate && hasTime {
		datePart, err := formatDateTimeCorePattern(localeData.dateFormats[length], value, localeData)
		if err != nil {
			return "", false, err
		}
		timePart, err := formatDateTimeCoreTimeStylePattern(localeData.timeFormats[timeStyle], value, localeData, hourCycle, preserveSameFamilyHourCycle)
		if err != nil {
			return "", false, err
		}
		template := dateTimeCoreStyleJoinPattern(localeData, length)
		return strings.NewReplacer("{1}", datePart, "{0}", timePart).Replace(template), true, nil
	}
	if hasDate {
		formatted, err := formatDateTimeCorePattern(localeData.dateFormats[length], value, localeData)
		return formatted, true, err
	}
	if hasTime {
		formatted, err := formatDateTimeCoreTimeStylePattern(localeData.timeFormats[timeStyle], value, localeData, hourCycle, preserveSameFamilyHourCycle)
		return formatted, true, err
	}
	return "", false, nil
}

func formatDateTimeCoreTimeStylePattern(pattern string, value time.Time, localeData cldrDateTimeLocaleData, hourCycle string, preserveSameFamilyHourCycle bool) (string, error) {
	if hourCycle == "" {
		return formatDateTimeCorePattern(pattern, value, localeData)
	}
	hourSymbol := preferredDateTimeCoreHourSymbol(localeData, hourCycle)
	if patternHourSymbol, ok := dateTimeCoreTimeStylePatternHourSymbol(pattern); preserveSameFamilyHourCycle && ok && dateTimeCoreIsHour12Field(patternHourSymbol) == dateTimeCoreIsHour12Field(hourSymbol) {
		return formatDateTimeCorePattern(replaceDateTimeCoreTimeStylePatternHourSymbol(pattern, hourSymbol), value, localeData)
	}
	skeleton, ok := dateTimeCoreTimeStylePatternSkeleton(pattern, localeData, hourCycle)
	if !ok {
		return formatDateTimeCorePattern(pattern, value, localeData)
	}
	canonical, err := canonicalDateTimeCoreStandardSkeleton(skeleton, localeData, "")
	if err != nil {
		return "", err
	}
	if matched, ok := dateTimeCoreSkeletonPattern(canonical, localeData); ok {
		return formatDateTimeCorePattern(matched, value, localeData)
	}
	return formatDateTimeCorePattern(pattern, value, localeData)
}

func dateTimeCoreStyleJoinPattern(localeData cldrDateTimeLocaleData, style string) string {
	if template := localeData.dateTimeStyleJoinFormats[style]; template != "" {
		return template
	}
	if template := localeData.dateTimeFormats[style]; template != "" {
		return template
	}
	if template := localeData.dateTimeFormats[DateTimeCoreStyleMedium]; template != "" {
		return template
	}
	return "{1} {0}"
}

func dateTimeCoreTimeStylePatternHourSymbol(pattern string) (rune, bool) {
	runes := []rune(pattern)
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			index = readDateTimeCoreQuotedPattern(runes, index).nextIndex
		} else if isDateTimeCoreASCIILetter(ch) {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			if isDateTimeCoreHourField(ch) {
				return ch, true
			}
			index = end
		} else {
			index++
		}
	}
	return 0, false
}

func replaceDateTimeCoreTimeStylePatternHourSymbol(pattern string, hourSymbol rune) string {
	runes := []rune(pattern)
	var output strings.Builder
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			quoted := readDateTimeCoreQuotedPattern(runes, index)
			output.WriteString(string(runes[index:quoted.nextIndex]))
			index = quoted.nextIndex
		} else if isDateTimeCoreASCIILetter(ch) {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			if isDateTimeCoreHourField(ch) {
				output.WriteString(strings.Repeat(string(hourSymbol), end-index))
			} else {
				output.WriteString(string(runes[index:end]))
			}
			index = end
		} else {
			output.WriteRune(ch)
			index++
		}
	}
	return output.String()
}

func dateTimeCoreTimeStylePatternSkeleton(pattern string, localeData cldrDateTimeLocaleData, hourCycle string) (string, bool) {
	runes := []rune(pattern)
	widths := map[rune]int{}
	hourSymbol := preferredDateTimeCoreHourSymbol(localeData, hourCycle)
	hasHour := false
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			index = readDateTimeCoreQuotedPattern(runes, index).nextIndex
		} else if isDateTimeCoreASCIILetter(ch) {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			if isDateTimeCoreHourField(ch) {
				dateTimeCoreSetSkeletonWidth(widths, hourSymbol, end-index)
				hasHour = true
			} else if !isDateTimeCoreDayPeriodField(ch) && strings.ContainsRune(dateTimeCoreSkeletonTimeFields, ch) {
				dateTimeCoreSetSkeletonWidth(widths, ch, end-index)
			}
			index = end
		} else {
			index++
		}
	}
	if !hasHour {
		return "", false
	}
	var skeleton strings.Builder
	for _, symbol := range dateTimeCoreSkeletonFieldOrder {
		if width, ok := widths[symbol]; ok {
			skeleton.WriteString(strings.Repeat(string(symbol), width))
		}
	}
	return skeleton.String(), true
}

func dateTimeCoreSkeletonPattern(canonical string, localeData cldrDateTimeLocaleData) (string, bool) {
	if pattern, ok := dateTimeCoreSkeletonPatternWithoutAppend(canonical, localeData); ok {
		return pattern, true
	}
	if hasDateTimeCoreDateAndTimeFields(canonical) {
		return "", false
	}
	return dateTimeCoreAppendedSkeletonPattern(canonical, localeData)
}

func dateTimeCoreSkeletonPatternWithoutAppend(canonical string, localeData cldrDateTimeLocaleData) (string, bool) {
	if pattern, ok := localeData.availableFormats[canonical]; ok {
		return pattern, true
	}
	requestedFields := dateTimeCoreSkeletonFieldSet(canonical)
	bestCandidate := ""
	bestPattern := ""
	bestDistance := 0
	for candidate, pattern := range localeData.availableFormats {
		if dateTimeCoreSkeletonFieldSet(candidate) != requestedFields {
			continue
		}
		distance := dateTimeCoreSkeletonDistance(canonical, candidate)
		if bestPattern == "" || distance < bestDistance || (distance == bestDistance && candidate < bestCandidate) {
			bestCandidate = candidate
			bestPattern = pattern
			bestDistance = distance
		}
	}
	if bestPattern == "" {
		if pattern, ok := dateTimeCoreSyntheticSkeletonPattern(canonical, localeData); ok {
			return pattern, true
		}
		return "", false
	}
	return dateTimeCoreAdjustPatternWidths(bestPattern, canonical, bestCandidate), true
}

func dateTimeCoreAppendedSkeletonPattern(canonical string, localeData cldrDateTimeLocaleData) (string, bool) {
	requestedFields := dateTimeCoreSkeletonFieldSet(canonical)
	bestCandidate := ""
	bestPattern := ""
	bestFieldCount := -1
	bestDistance := 0
	for candidate, pattern := range localeData.availableFormats {
		candidateFields := dateTimeCoreSkeletonFieldSet(candidate)
		if candidateFields == "" || candidateFields == requestedFields {
			continue
		}
		if !dateTimeCoreFieldSetContains(requestedFields, candidateFields) {
			continue
		}
		fieldCount := len(candidateFields)
		distance := dateTimeCoreSkeletonDistance(canonical, candidate)
		if fieldCount > bestFieldCount || (fieldCount == bestFieldCount && (bestPattern == "" || distance < bestDistance || (distance == bestDistance && candidate < bestCandidate))) {
			bestCandidate = candidate
			bestPattern = pattern
			bestFieldCount = fieldCount
			bestDistance = distance
		}
	}
	if bestPattern == "" {
		return "", false
	}
	output := dateTimeCoreAdjustPatternWidths(bestPattern, canonical, bestCandidate)
	currentFields := map[rune]bool{}
	for _, symbol := range bestCandidate {
		currentFields[dateTimeCoreFieldSetSymbol(symbol)] = true
	}
	requestedWidths := dateTimeCoreSkeletonWidths(canonical)
	for _, symbol := range dateTimeCoreSkeletonFieldOrder {
		width, ok := requestedWidths[symbol]
		if !ok {
			continue
		}
		field := dateTimeCoreFieldSetSymbol(symbol)
		if currentFields[field] {
			continue
		}
		key, ok := dateTimeCoreAppendItemKey(symbol)
		if !ok {
			return "", false
		}
		fieldSkeleton := strings.Repeat(string(symbol), width)
		fieldPattern, ok := dateTimeCoreSkeletonPatternWithoutAppend(fieldSkeleton, localeData)
		if !ok {
			fieldPattern = fieldSkeleton
		}
		template := dateTimeCoreAppendItemTemplate(localeData, key)
		fieldName := localeData.fieldNames[key]
		if fieldName == "" {
			fieldName = key
		}
		output = dateTimeCoreApplyAppendItemPattern(template, output, fieldPattern, fieldName)
		currentFields[field] = true
	}
	return output, true
}

func dateTimeCoreFieldSetContains(container, subset string) bool {
	for _, field := range subset {
		if !strings.ContainsRune(container, field) {
			return false
		}
	}
	return true
}

func dateTimeCoreApplyAppendItemPattern(template, basePattern, fieldPattern, fieldName string) string {
	return strings.NewReplacer("{0}", basePattern, "{1}", fieldPattern, "{2}", dateTimeCoreQuotePatternLiteral(fieldName)).Replace(template)
}

func dateTimeCoreQuotePatternLiteral(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

func dateTimeCoreAppendItemTemplate(localeData cldrDateTimeLocaleData, key string) string {
	if template := localeData.appendItems[key]; template != "" {
		return template
	}
	return dateTimeCoreDefaultAppendItemTemplate(key)
}

func dateTimeCoreDefaultAppendItemTemplate(key string) string {
	switch key {
	case "Quarter", "Month", "Week", "Day", "Hour", "Minute", "Second":
		return "{0} ({2}: {1})"
	default:
		return "{0} {1}"
	}
}

func hasDateTimeCoreDateAndTimeFields(canonical string) bool {
	dateSkeleton, timeSkeleton := splitDateTimeCoreSkeleton(canonical)
	return dateSkeleton != "" && timeSkeleton != ""
}

func dateTimeCoreAppendItemKey(symbol rune) (string, bool) {
	if symbol == 'G' {
		return "Era", true
	}
	if isDateTimeCoreYearField(symbol) {
		return "Year", true
	}
	if isDateTimeCoreQuarterField(symbol) {
		return "Quarter", true
	}
	if isDateTimeCoreMonthField(symbol) {
		return "Month", true
	}
	if symbol == 'w' || symbol == 'W' {
		return "Week", true
	}
	if symbol == 'd' || symbol == 'D' || symbol == 'F' || symbol == 'g' {
		return "Day", true
	}
	if isDateTimeCoreWeekdayField(symbol) {
		return "Day-Of-Week", true
	}
	if isDateTimeCoreHourField(symbol) {
		return "Hour", true
	}
	if symbol == 'm' {
		return "Minute", true
	}
	if symbol == 's' || symbol == 'S' || symbol == 'A' {
		return "Second", true
	}
	if isDateTimeCoreTimeZoneField(symbol) {
		return "Timezone", true
	}
	return "", false
}

func dateTimeCoreSyntheticSkeletonPattern(canonical string, localeData cldrDateTimeLocaleData) (string, bool) {
	widths := dateTimeCoreSkeletonWidths(canonical)
	if len(widths) == 1 {
		for symbol, width := range widths {
			if symbol == 'G' {
				return strings.Repeat(string(symbol), width), true
			}
			if isDateTimeCoreDayPeriodField(symbol) {
				return strings.Repeat(string(symbol), width), true
			}
			if isDateTimeCoreQuarterField(symbol) {
				return strings.Repeat(string(symbol), width), true
			}
			if isDateTimeCoreSyntheticNumericField(symbol) {
				return strings.Repeat(string(symbol), width), true
			}
			if symbol == 'S' {
				return strings.Repeat(string(symbol), width), true
			}
			if isDateTimeCoreTimeZoneField(symbol) {
				return strings.Repeat(string(symbol), width), true
			}
		}
	}
	if pattern, ok := dateTimeCoreSyntheticFractionalSecondPattern(canonical, localeData, widths); ok {
		return pattern, true
	}
	return "", false
}

func dateTimeCoreSyntheticFractionalSecondPattern(canonical string, localeData cldrDateTimeLocaleData, widths map[rune]int) (string, bool) {
	fractionWidth, hasFraction := widths['S']
	if !hasFraction {
		return "", false
	}
	if _, hasSecond := widths['s']; !hasSecond {
		return "", false
	}
	baseSkeleton := dateTimeCoreSkeletonWithoutField(canonical, 'S')
	basePattern, ok := dateTimeCoreSkeletonPattern(baseSkeleton, localeData)
	if !ok {
		basePattern, ok = dateTimeCoreSyntheticSecondsPattern(baseSkeleton)
	}
	if !ok {
		return "", false
	}
	return dateTimeCoreInsertFractionalSecond(basePattern, fractionWidth, localeData.decimalSeparator)
}

func dateTimeCoreSyntheticSecondsPattern(canonical string) (string, bool) {
	widths := dateTimeCoreSkeletonWidths(canonical)
	if len(widths) == 1 {
		if width, ok := widths['s']; ok {
			return strings.Repeat("s", width), true
		}
	}
	return "", false
}

func dateTimeCoreSkeletonWithoutField(skeleton string, removedSymbol rune) string {
	runes := []rune(skeleton)
	var output strings.Builder
	for index := 0; index < len(runes); {
		symbol := runes[index]
		end := index + 1
		for end < len(runes) && runes[end] == symbol {
			end++
		}
		if symbol != removedSymbol {
			output.WriteString(string(runes[index:end]))
		}
		index = end
	}
	return output.String()
}

func dateTimeCoreInsertFractionalSecond(pattern string, width int, decimalSeparator string) (string, bool) {
	runes := []rune(pattern)
	var output strings.Builder
	inQuote := false
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			output.WriteRune(ch)
			if index+1 < len(runes) && runes[index+1] == '\'' {
				output.WriteRune('\'')
				index += 2
			} else {
				inQuote = !inQuote
				index++
			}
		} else if !inQuote && ch == 's' {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			output.WriteString(string(runes[index:end]))
			output.WriteString(decimalSeparator)
			output.WriteString(strings.Repeat("S", width))
			output.WriteString(string(runes[end:]))
			return output.String(), true
		} else {
			output.WriteRune(ch)
			index++
		}
	}
	return "", false
}

func formatDateTimeCoreComposedSkeleton(rawSkeleton, canonical string, value time.Time, localeData cldrDateTimeLocaleData, suppressDayPeriod bool, dateTimeJoinStyle string) (string, error) {
	dateSkeleton, timeSkeleton := splitDateTimeCoreSkeleton(canonical)
	if dateSkeleton == "" || timeSkeleton == "" {
		return "", unsupportedDateTimeCoreSkeleton(rawSkeleton)
	}
	datePattern, ok := dateTimeCoreSkeletonPattern(dateSkeleton, localeData)
	if !ok {
		return "", unsupportedDateTimeCoreSkeleton(rawSkeleton)
	}
	timePattern, ok := dateTimeCoreSkeletonPattern(timeSkeleton, localeData)
	if !ok {
		return "", unsupportedDateTimeCoreSkeleton(rawSkeleton)
	}
	if suppressDayPeriod {
		timePattern = stripDateTimeCoreDayPeriodPatternFields(timePattern)
	}
	datePart, err := formatDateTimeCorePattern(datePattern, value, localeData)
	if err != nil {
		return "", err
	}
	timePart, err := formatDateTimeCorePattern(timePattern, value, localeData)
	if err != nil {
		return "", err
	}
	template := localeData.dateTimeFormats[dateTimeJoinStyle]
	if template == "" {
		template = localeData.dateTimeFormats[DateTimeCoreStyleMedium]
	}
	if template == "" {
		template = "{1} {0}"
	}
	return strings.NewReplacer("{1}", datePart, "{0}", timePart).Replace(template), nil
}

func unsupportedDateTimeCoreSkeleton(skeleton string) error {
	return badOption("Unsupported CLDR date/time skeleton: " + skeleton + ".")
}

func canonicalDateTimeCoreSkeleton(skeleton string, localeData cldrDateTimeLocaleData, hourCycle string, value time.Time) (string, error) {
	if strings.HasPrefix(skeleton, dateTimeCoreSemanticSkeletonPrefix) {
		standard, err := dateTimeCoreSemanticSkeletonToStandard(strings.TrimPrefix(skeleton, dateTimeCoreSemanticSkeletonPrefix), localeData, value)
		if err != nil {
			return "", err
		}
		return canonicalDateTimeCoreStandardSkeleton(standard, localeData, hourCycle)
	}
	return canonicalDateTimeCoreStandardSkeleton(skeleton, localeData, hourCycle)
}

func canonicalDateTimeCoreStandardSkeleton(skeleton string, localeData cldrDateTimeLocaleData, hourCycle string) (string, error) {
	widths := map[rune]int{}
	runes := []rune(skeleton)
	for index := 0; index < len(runes); {
		symbol := runes[index]
		if !isDateTimeCoreASCIILetter(symbol) {
			return "", badOption("Date/time skeleton must contain only ASCII pattern letters.")
		}
		end := index + 1
		for end < len(runes) && runes[end] == symbol {
			end++
		}
		width := end - index
		if width > dateTimeCoreMaxSkeletonFieldWidth {
			return "", badOption("Date/time skeleton field width is too large.")
		}
		if symbol == 'C' {
			applyDateTimeCoreCHourFormat(widths, localeData, hourCycle, width)
		} else {
			normalized := normalizeDateTimeCoreSkeletonSymbol(symbol, localeData, hourCycle)
			dateTimeCoreSetSkeletonWidth(widths, normalized, width)
		}
		index = end
	}
	var output strings.Builder
	for _, symbol := range dateTimeCoreSkeletonFieldOrder {
		for count := 0; count < widths[symbol]; count++ {
			output.WriteRune(symbol)
		}
	}
	if output.Len() == 0 {
		return "", badOption("Date/time skeleton must not be empty.")
	}
	return output.String(), nil
}

func dateTimeCoreSemanticSkeletonToStandard(body string, localeData cldrDateTimeLocaleData, value time.Time) (string, error) {
	options, err := parseDateTimeCoreSemanticSkeletonOptions(body)
	if err != nil {
		return "", err
	}
	fields, err := parseDateTimeCoreSemanticSkeletonFields(options)
	if err != nil {
		return "", err
	}
	if err := validateDateTimeCoreSemanticSkeleton(fields, options); err != nil {
		return "", err
	}
	length, err := dateTimeCoreSemanticOption(options, "length", "medium", []string{"full", "long", "medium", "short"})
	if err != nil {
		return "", err
	}
	alignment, err := dateTimeCoreSemanticOption(options, "alignment", "inline", []string{"inline", "column"})
	if err != nil {
		return "", err
	}
	yearStyle, err := dateTimeCoreSemanticOption(options, "yearstyle", "auto", []string{"auto", "full", "with-era", "numeric", "2-digit"})
	if err != nil {
		return "", err
	}
	eraStyle, err := dateTimeCoreSemanticOption(options, "erastyle", "auto", dateTimeCoreSemanticTextStyleValues)
	if err != nil {
		return "", err
	}
	monthStyle, err := dateTimeCoreSemanticOption(options, "monthstyle", "auto", dateTimeCoreSemanticDateStyleValues)
	if err != nil {
		return "", err
	}
	quarterStyle, err := dateTimeCoreSemanticOption(options, "quarterstyle", "auto", dateTimeCoreSemanticDateStyleValues)
	if err != nil {
		return "", err
	}
	dayStyle, err := dateTimeCoreSemanticOption(options, "daystyle", "auto", dateTimeCoreSemanticNumericStyleValues)
	if err != nil {
		return "", err
	}
	weekdayStyle, err := dateTimeCoreSemanticOption(options, "weekdaystyle", "auto", dateTimeCoreSemanticTextStyleValues)
	if err != nil {
		return "", err
	}
	dayPeriodStyle, err := dateTimeCoreSemanticOption(options, "dayperiodstyle", "auto", dateTimeCoreSemanticTextStyleValues)
	if err != nil {
		return "", err
	}
	if _, err := dateTimeCoreSemanticOption(options, "hourstyle", "auto", dateTimeCoreSemanticNumericStyleValues); err != nil {
		return "", err
	}
	if _, err := dateTimeCoreSemanticOption(options, "minutestyle", "auto", dateTimeCoreSemanticNumericStyleValues); err != nil {
		return "", err
	}
	if _, err := dateTimeCoreSemanticOption(options, "secondstyle", "auto", dateTimeCoreSemanticNumericStyleValues); err != nil {
		return "", err
	}
	timePrecision, err := dateTimeCoreSemanticOption(options, "timeprecision", "second", []string{"hour", "minute", "minute-optional", "second", "fractional-second"})
	if err != nil {
		return "", err
	}
	timeStyle, err := dateTimeCoreSemanticOption(options, "timestyle", "auto", []string{"auto", "short", "medium", "long", "full"})
	if err != nil {
		return "", err
	}
	effectiveTimePrecision := dateTimeCoreSemanticTimeStylePrecision(timeStyle, timePrecision)
	hourCycle, err := dateTimeCoreSemanticOption(options, "hourcycle", "auto", []string{"auto", "h11", "h12", "h23", "h24", "clock12", "clock24"})
	if err != nil {
		return "", err
	}
	zoneStyle, err := dateTimeCoreSemanticOption(options, "zonestyle", "auto", []string{"auto", "generic", "specific", "location", "offset"})
	if err != nil {
		return "", err
	}
	effectiveZoneStyle := dateTimeCoreSemanticTimeStyleZoneStyle(timeStyle, zoneStyle)
	effectiveZoneStandalone := len(fields) == 1 || timeStyle == "full"
	effectiveZoneLength := length
	if dateTimeCoreSemanticTimeStyleHasZone(timeStyle) {
		effectiveZoneLength = timeStyle
	}
	dateWidths := dateTimeCoreSemanticDateFieldWidths(localeData, length)
	var standard strings.Builder
	if dateTimeCoreStringSliceContains(fields, "era") {
		standard.WriteString(dateTimeCoreSemanticEraSkeleton(dateWidths, length, eraStyle))
	}
	if dateTimeCoreStringSliceContains(fields, "year") {
		standard.WriteString(dateTimeCoreSemanticYearSkeleton(dateWidths, yearStyle, !dateTimeCoreStringSliceContains(fields, "era")))
	}
	if dateTimeCoreStringSliceContains(fields, "quarter") {
		standard.WriteString(dateTimeCoreSemanticQuarterSkeleton(fields, length, alignment, quarterStyle))
	}
	if dateTimeCoreStringSliceContains(fields, "month") {
		standard.WriteString(dateTimeCoreSemanticMonthSkeleton(fields, dateWidths, length, alignment, monthStyle))
	}
	if dateTimeCoreStringSliceContains(fields, "weekofmonth") {
		standard.WriteByte('W')
	}
	if dateTimeCoreStringSliceContains(fields, "day") {
		standard.WriteString(dateTimeCoreSemanticDaySkeleton(dateWidths, alignment, dayStyle))
	}
	if dateTimeCoreStringSliceContains(fields, "dayofyear") {
		width := 1
		if alignment == "column" {
			width = 3
		}
		standard.WriteString(strings.Repeat("D", width))
	}
	if dateTimeCoreStringSliceContains(fields, "dayofweekinmonth") {
		width := 1
		if alignment == "column" {
			width = 2
		}
		standard.WriteString(strings.Repeat("F", width))
	}
	if dateTimeCoreStringSliceContains(fields, "modifiedjulianday") {
		width := 1
		if alignment == "column" {
			width = 6
		}
		standard.WriteString(strings.Repeat("g", width))
	}
	if dateTimeCoreStringSliceContains(fields, "weekday") {
		standard.WriteString(dateTimeCoreSemanticWeekdaySkeleton(fields, length, weekdayStyle))
	}
	if dateTimeCoreStringSliceContains(fields, "weekofyear") {
		if alignment == "column" {
			standard.WriteString("ww")
		} else {
			standard.WriteByte('w')
		}
	}
	if dateTimeCoreStringSliceContains(fields, "dayperiod") {
		standard.WriteString(dateTimeCoreSemanticDayPeriodSkeleton(length, dayPeriodStyle))
	}
	if hasDateTimeCoreSemanticTimeComponents(fields) {
		timeSkeleton, err := dateTimeCoreSemanticExplicitTimeSkeleton(fields, hourCycle, alignment, options)
		if err != nil {
			return "", err
		}
		standard.WriteString(timeSkeleton)
	}
	if dateTimeCoreStringSliceContains(fields, "time") {
		timeSkeleton, err := dateTimeCoreSemanticTimeSkeleton(effectiveTimePrecision, hourCycle, alignment, value, options)
		if err != nil {
			return "", err
		}
		standard.WriteString(timeSkeleton)
	}
	if dateTimeCoreStringSliceContains(fields, "zone") {
		standard.WriteString(dateTimeCoreSemanticZoneSkeleton(effectiveZoneStyle, effectiveZoneStandalone, effectiveZoneLength))
	}
	if standard.Len() == 0 {
		return "", badOption("Date/time semantic skeleton must include at least one field.")
	}
	return standard.String(), nil
}

func parseDateTimeCoreSemanticSkeletonOptions(body string) (map[string]string, error) {
	options := map[string]string{}
	seenParts := 0
	implicitDateStyle := ""
	implicitTimeFields := false
	for _, rawPart := range strings.Split(body, ";") {
		part := strings.TrimSpace(rawPart)
		if part == "" {
			continue
		}
		equals := strings.Index(part, "=")
		rawKey := ""
		rawValue := part
		if equals < 0 {
			if seenParts == 0 {
				rawKey = "fields"
			}
		} else {
			rawKey = part[:equals]
			rawValue = part[equals+1:]
		}
		rawKeyAlias := dateTimeCoreSemanticNormalize(rawKey)
		key := dateTimeCoreSemanticNormalizeOptionKey(rawKey)
		value := dateTimeCoreSemanticNormalizeOptionValue(key, rawValue)
		if key == "" || value == "" || !dateTimeCoreStringSliceContains(dateTimeCoreSemanticOptionKeys, key) {
			return nil, badOption("Invalid date/time semantic skeleton option.")
		}
		if _, ok := options[key]; ok {
			return nil, badOption("Invalid date/time semantic skeleton option.")
		}
		if rawKeyAlias == "style" || rawKeyAlias == "datestyle" || rawKeyAlias == "datelength" {
			implicitDateStyle = value
		}
		if rawKeyAlias == "timestyle" {
			implicitTimeFields = true
		}
		options[key] = value
		seenParts++
	}
	if seenParts == 0 {
		return nil, badOption("Date/time semantic skeleton must include fields.")
	}
	if _, ok := options["fields"]; !ok {
		fields := dateTimeCoreImplicitSemanticFields(implicitDateStyle, implicitTimeFields, options["timestyle"])
		if fields != "" {
			options["fields"] = fields
		}
	}
	return options, nil
}

func dateTimeCoreImplicitSemanticFields(dateStyle string, hasTimeStyle bool, timeStyle string) string {
	dateFields := "date"
	if dateStyle == "full" {
		dateFields = "date,weekday"
	}
	if dateStyle != "" && hasTimeStyle {
		if timeStyle == "long" || timeStyle == "full" {
			return dateFields + ",time,zone"
		}
		return dateFields + ",time"
	}
	if dateStyle != "" {
		return dateFields
	}
	if hasTimeStyle {
		if timeStyle == "long" || timeStyle == "full" {
			return "time,zone"
		}
		return "time"
	}
	return ""
}

func dateTimeCoreSemanticNormalizeOptionKey(value string) string {
	normalized := dateTimeCoreSemanticNormalize(value)
	if normalized == "style" || normalized == "datestyle" || normalized == "datelength" {
		return "length"
	}
	if normalized == "precision" {
		return "timeprecision"
	}
	if normalized == "timestyle" {
		return "timestyle"
	}
	if normalized == "hour12" {
		return "hourcycle"
	}
	if normalized == "zone" || normalized == "timezonename" || normalized == "timezonestyle" {
		return "zonestyle"
	}
	if normalized == "fractionalseconddigits" {
		return "fractionalsecond"
	}
	switch normalized {
	case "era":
		return "erastyle"
	case "year":
		return "yearstyle"
	case "month":
		return "monthstyle"
	case "quarter":
		return "quarterstyle"
	case "day":
		return "daystyle"
	case "weekday":
		return "weekdaystyle"
	case "dayperiod":
		return "dayperiodstyle"
	case "hour":
		return "hourstyle"
	case "minute":
		return "minutestyle"
	case "second":
		return "secondstyle"
	}
	return normalized
}

func dateTimeCoreSemanticNormalizeOptionValue(key, value string) string {
	if key == "fields" {
		return strings.ToLower(strings.TrimSpace(value))
	}
	normalized := dateTimeCoreSemanticNormalize(value)
	if key == "yearstyle" && normalized == "withera" {
		return "with-era"
	}
	if dateTimeCoreStringSliceContains(dateTimeCoreSemanticStyleOptionKeys, key) && (normalized == "2digit" || normalized == "twodigit") {
		return "2-digit"
	}
	if dateTimeCoreStringSliceContains(dateTimeCoreSemanticStyleOptionKeys, key) && normalized == "wide" {
		return "long"
	}
	if dateTimeCoreStringSliceContains(dateTimeCoreSemanticStyleOptionKeys, key) && normalized == "abbreviated" {
		return "short"
	}
	if key == "timeprecision" && normalized == "short" {
		return "minute"
	}
	if key == "timeprecision" && normalized == "medium" {
		return "second"
	}
	if key == "timeprecision" && normalized == "minuteoptional" {
		return "minute-optional"
	}
	if key == "timeprecision" && normalized == "fractionalsecond" {
		return "fractional-second"
	}
	if key == "zonestyle" && (normalized == "shortoffset" || normalized == "longoffset") {
		return "offset"
	}
	if key == "zonestyle" && (normalized == "shortgeneric" || normalized == "longgeneric") {
		return "generic"
	}
	if key == "zonestyle" && (normalized == "short" || normalized == "long") {
		return "specific"
	}
	if key == "hourcycle" && normalized == "true" {
		return "clock12"
	}
	if key == "hourcycle" && normalized == "false" {
		return "clock24"
	}
	return normalized
}

func parseDateTimeCoreSemanticSkeletonFields(options map[string]string) ([]string, error) {
	fieldsText := options["fields"]
	if fieldsText == "" {
		return nil, badOption("Date/time semantic skeleton must include fields.")
	}
	fields := []string{}
	for _, field := range strings.Split(fieldsText, ",") {
		normalized := dateTimeCoreSemanticNormalizeField(field)
		canonicalFields := []string{normalized}
		if normalized == "date" || normalized == "yearmonthday" {
			canonicalFields = []string{"year", "month", "day"}
		} else if normalized == "eradate" || normalized == "erayearmonthday" {
			canonicalFields = []string{"era", "year", "month", "day"}
		} else if normalized == "eradateweekday" || normalized == "weekdayeradate" || normalized == "erayearmonthdayweekday" || normalized == "weekdayerayearmonthday" {
			canonicalFields = []string{"era", "year", "month", "day", "weekday"}
		} else if normalized == "eradatetime" || normalized == "erayearmonthdaytime" {
			canonicalFields = []string{"era", "year", "month", "day", "time"}
		} else if normalized == "eradatetimeweekday" || normalized == "weekdayeradatetime" || normalized == "erayearmonthdaytimeweekday" || normalized == "weekdayerayearmonthdaytime" {
			canonicalFields = []string{"era", "year", "month", "day", "weekday", "time"}
		} else if normalized == "datetime" || normalized == "yearmonthdaytime" {
			canonicalFields = []string{"year", "month", "day", "time"}
		} else if normalized == "datetimeweekday" || normalized == "weekdaydatetime" || normalized == "yearmonthdaytimeweekday" || normalized == "weekdayyearmonthdaytime" {
			canonicalFields = []string{"year", "month", "day", "weekday", "time"}
		} else if normalized == "datetimeweekdayzone" || normalized == "weekdaydatetimezone" || normalized == "zoneddatetimeweekday" || normalized == "zonedweekdaydatetime" || normalized == "yearmonthdaytimeweekdayzone" || normalized == "weekdayyearmonthdaytimezone" || normalized == "zonedyearmonthdaytimeweekday" || normalized == "zonedweekdayyearmonthdaytime" {
			canonicalFields = []string{"year", "month", "day", "weekday", "time", "zone"}
		} else if normalized == "eradatetimezone" || normalized == "zonederadatetime" || normalized == "erayearmonthdaytimezone" || normalized == "zonederayearmonthdaytime" {
			canonicalFields = []string{"era", "year", "month", "day", "time", "zone"}
		} else if normalized == "eradatetimeweekdayzone" || normalized == "weekdayeradatetimezone" || normalized == "zonederadatetimeweekday" || normalized == "zonedweekdayeradatetime" || normalized == "erayearmonthdaytimeweekdayzone" || normalized == "weekdayerayearmonthdaytimezone" || normalized == "zonederayearmonthdaytimeweekday" || normalized == "zonedweekdayerayearmonthdaytime" {
			canonicalFields = []string{"era", "year", "month", "day", "weekday", "time", "zone"}
		} else if normalized == "dateweekday" || normalized == "weekdaydate" || normalized == "yearmonthdayweekday" || normalized == "weekdayyearmonthday" {
			canonicalFields = []string{"year", "month", "day", "weekday"}
		} else if normalized == "datetimezone" || normalized == "zoneddatetime" || normalized == "yearmonthdaytimezone" || normalized == "zonedyearmonthdaytime" {
			canonicalFields = []string{"year", "month", "day", "time", "zone"}
		} else if normalized == "yearmonth" {
			canonicalFields = []string{"year", "month"}
		} else if normalized == "erayearmonth" {
			canonicalFields = []string{"era", "year", "month"}
		} else if normalized == "yearquarter" {
			canonicalFields = []string{"year", "quarter"}
		} else if normalized == "erayearquarter" {
			canonicalFields = []string{"era", "year", "quarter"}
		} else if normalized == "yearweek" {
			canonicalFields = []string{"year", "weekofyear"}
		} else if normalized == "erayearweek" {
			canonicalFields = []string{"era", "year", "weekofyear"}
		} else if normalized == "erayear" {
			canonicalFields = []string{"era", "year"}
		} else if normalized == "monthweek" {
			canonicalFields = []string{"month", "weekofmonth"}
		} else if normalized == "yearmonthweek" {
			canonicalFields = []string{"year", "month", "weekofmonth"}
		} else if normalized == "erayearmonthweek" {
			canonicalFields = []string{"era", "year", "month", "weekofmonth"}
		} else if normalized == "monthday" {
			canonicalFields = []string{"month", "day"}
		}
		for _, canonical := range canonicalFields {
			if !dateTimeCoreStringSliceContains(dateTimeCoreSemanticFieldOrder, canonical) || dateTimeCoreStringSliceContains(fields, canonical) {
				return nil, badOption("Invalid date/time semantic skeleton field.")
			}
			fields = append(fields, canonical)
		}
	}
	if len(fields) == 0 {
		return nil, badOption("Date/time semantic skeleton must include fields.")
	}
	return fields, nil
}

func dateTimeCoreSemanticNormalizeField(value string) string {
	normalized := dateTimeCoreSemanticNormalize(value)
	if normalized == "dayofmonth" {
		return "day"
	}
	if normalized == "dayofweek" {
		return "weekday"
	}
	if normalized == "monthofyear" {
		return "month"
	}
	if normalized == "quarterofyear" {
		return "quarter"
	}
	if normalized == "yearofera" {
		return "year"
	}
	if normalized == "week" {
		return "weekofyear"
	}
	if normalized == "weekofyear" {
		return "weekofyear"
	}
	if normalized == "weekofmonth" {
		return "weekofmonth"
	}
	if normalized == "dayofyear" {
		return "dayofyear"
	}
	if normalized == "dayofweekinmonth" {
		return "dayofweekinmonth"
	}
	if normalized == "modifiedjulianday" {
		return "modifiedjulianday"
	}
	if normalized == "millisecondsinday" {
		return "millisecondsinday"
	}
	if normalized == "fractionalseconddigits" {
		return "fractionalsecond"
	}
	if normalized == "dayperiod" {
		return "dayperiod"
	}
	if normalized == "hourofday" {
		return "hour"
	}
	if normalized == "minuteofhour" {
		return "minute"
	}
	if normalized == "secondofminute" {
		return "second"
	}
	if normalized == "timezonename" {
		return "zone"
	}
	if normalized == "timezone" {
		return "zone"
	}
	return normalized
}

func validateDateTimeCoreSemanticSkeleton(fields []string, options map[string]string) error {
	dateKey := dateTimeCoreSemanticFieldSetKey(fields, dateTimeCoreSemanticDateFieldOrder)
	timeKey := dateTimeCoreSemanticFieldSetKey(fields, dateTimeCoreSemanticTimeFieldOrder)
	hasDateFields := dateKey != ""
	hasExplicitTime := timeKey != ""
	hasTime := dateTimeCoreStringSliceContains(fields, "time") || hasExplicitTime
	hasZone := dateTimeCoreStringSliceContains(fields, "zone")
	hasDayPeriod := dateTimeCoreStringSliceContains(fields, "dayperiod")
	validDateFields := false
	if hasTime || hasZone {
		validDateFields = !hasDateFields || dateTimeCoreStringSliceContains(dateTimeCoreSemanticDateFieldSets, dateKey)
	} else {
		validDateFields = !hasDateFields || dateTimeCoreStringSliceContains(dateTimeCoreSemanticDateFieldSets, dateKey) || dateTimeCoreStringSliceContains(dateTimeCoreSemanticCalendarPeriodFieldSets, dateKey)
	}
	validFieldSet := false
	if hasDayPeriod {
		validFieldSet = validDateFields && (hasTime || !hasZone)
	} else if hasTime || hasZone {
		validFieldSet = !hasDateFields || dateTimeCoreStringSliceContains(dateTimeCoreSemanticDateFieldSets, dateKey)
	} else {
		validFieldSet = dateTimeCoreStringSliceContains(dateTimeCoreSemanticDateFieldSets, dateKey) || dateTimeCoreStringSliceContains(dateTimeCoreSemanticCalendarPeriodFieldSets, dateKey)
	}
	if !validFieldSet {
		return badOption("Invalid date/time semantic skeleton field set.")
	}
	if dateTimeCoreStringSliceContains(fields, "time") && hasExplicitTime {
		return badOption("time field cannot be combined with explicit time component fields.")
	}
	if options["timestyle"] != "" && options["timeprecision"] != "" {
		return badOption("timeStyle cannot be combined with timePrecision.")
	}
	timeStyle := options["timestyle"]
	if options["timestyle"] != "" && !dateTimeCoreStringSliceContains(fields, "time") {
		return badOption("timeStyle requires the time field.")
	}
	if dateTimeCoreSemanticTimeStyleHasZone(timeStyle) && !hasZone {
		return badOption("timeStyle=long/full requires the zone field.")
	}
	if dateTimeCoreSemanticTimeStyleHasZone(timeStyle) && options["zonestyle"] != "" {
		return badOption("timeStyle=long/full cannot be combined with zoneStyle.")
	}
	if hasExplicitTime && !dateTimeCoreStringSliceContains(dateTimeCoreSemanticTimeFieldSets, timeKey) {
		return badOption("Invalid date/time semantic skeleton time field set.")
	}
	if hasExplicitTime && options["timeprecision"] != "" {
		return badOption("timePrecision requires the time field.")
	}
	if hasExplicitTime && options["fractionalsecond"] != "" && !dateTimeCoreStringSliceContains(fields, "fractionalsecond") {
		return badOption("fractionalSecond requires the fractionalSecond field.")
	}
	if dateTimeCoreStringSliceContains(fields, "fractionalsecond") {
		if _, err := dateTimeCoreSemanticFractionalSecondWidth(options); err != nil {
			return err
		}
	}
	if hasExplicitTime && !dateTimeCoreStringSliceContains(fields, "hour") && (options["hourcycle"] != "" || hasDayPeriod) {
		return badOption("hourCycle and dayPeriod require the hour field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "hour") && options["hourstyle"] != "" {
		return badOption("hourStyle requires the hour field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "minute") && options["minutestyle"] != "" {
		return badOption("minuteStyle requires the minute field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "second") && options["secondstyle"] != "" {
		return badOption("secondStyle requires the second field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "year") && options["yearstyle"] != "" {
		return badOption("yearStyle requires the year field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "era") && options["erastyle"] != "" {
		return badOption("eraStyle requires the era field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "month") && options["monthstyle"] != "" {
		return badOption("monthStyle requires the month field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "quarter") && options["quarterstyle"] != "" {
		return badOption("quarterStyle requires the quarter field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "day") && options["daystyle"] != "" {
		return badOption("dayStyle requires the day field.")
	}
	if !dateTimeCoreStringSliceContains(fields, "weekday") && options["weekdaystyle"] != "" {
		return badOption("weekdayStyle requires the weekday field.")
	}
	if !hasDayPeriod && options["dayperiodstyle"] != "" {
		return badOption("dayPeriodStyle requires the dayPeriod field.")
	}
	if !hasTime && (options["timeprecision"] != "" || options["timestyle"] != "" || options["fractionalsecond"] != "" || options["hourcycle"] != "") {
		return badOption("timePrecision and hourCycle require the time field.")
	}
	if !hasZone && options["zonestyle"] != "" {
		return badOption("zoneStyle requires the zone field.")
	}
	if !(dateTimeCoreStringSliceContains(fields, "year") || dateTimeCoreStringSliceContains(fields, "quarter") || dateTimeCoreStringSliceContains(fields, "month") || dateTimeCoreStringSliceContains(fields, "day") || dateTimeCoreStringSliceContains(fields, "dayofyear") || dateTimeCoreStringSliceContains(fields, "dayofweekinmonth") || dateTimeCoreStringSliceContains(fields, "modifiedjulianday") || hasTime) && options["alignment"] != "" {
		return badOption("alignment requires a date or time field.")
	}
	return nil
}

func dateTimeCoreSemanticOption(options map[string]string, key, fallback string, allowedValues []string) (string, error) {
	value := options[key]
	if value == "" {
		value = fallback
	}
	if !dateTimeCoreStringSliceContains(allowedValues, value) {
		return "", badOption("Date/time semantic skeleton " + key + " must be one of " + strings.Join(allowedValues, ", ") + ".")
	}
	return value, nil
}

func dateTimeCoreSemanticNormalize(value string) string {
	value = strings.TrimSpace(value)
	value = strings.ReplaceAll(value, "-", "")
	value = strings.ReplaceAll(value, "_", "")
	return strings.ToLower(value)
}

func dateTimeCoreStringSliceContains(values []string, value string) bool {
	for _, candidate := range values {
		if candidate == value {
			return true
		}
	}
	return false
}

func dateTimeCoreSemanticFieldSetKey(fields []string, order []string) string {
	output := []string{}
	for _, field := range order {
		if dateTimeCoreStringSliceContains(fields, field) {
			output = append(output, field)
		}
	}
	return strings.Join(output, ",")
}

func dateTimeCoreSemanticDateFieldWidths(localeData cldrDateTimeLocaleData, length string) map[rune]int {
	widths := map[rune]int{}
	for symbol, width := range dateTimeCorePatternFieldRuns(localeData.dateFormats[length]) {
		if symbol == 'G' || isDateTimeCoreYearField(symbol) || isDateTimeCoreMonthField(symbol) || symbol == 'd' {
			dateTimeCoreSetSkeletonWidth(widths, symbol, width)
		}
	}
	if !dateTimeCoreWidthKeysContain(widths, isDateTimeCoreYearField) {
		width := 1
		if length == "short" {
			width = 2
		}
		dateTimeCoreSetSkeletonWidth(widths, 'y', width)
	}
	if !dateTimeCoreWidthKeysContain(widths, isDateTimeCoreMonthField) {
		width := 1
		if dateTimeCoreIsWideLength(length) {
			width = 4
		} else if length == "medium" {
			width = 3
		}
		dateTimeCoreSetSkeletonWidth(widths, 'M', width)
	}
	if widths['d'] == 0 {
		widths['d'] = 1
	}
	return widths
}

func dateTimeCorePatternFieldRuns(pattern string) map[rune]int {
	fields := map[rune]int{}
	runes := []rune(pattern)
	inQuote := false
	for index := 0; index < len(runes); {
		symbol := runes[index]
		if symbol == '\'' {
			if index+1 < len(runes) && runes[index+1] == '\'' {
				index += 2
			} else {
				inQuote = !inQuote
				index++
			}
		} else if !inQuote && isDateTimeCoreASCIILetter(symbol) {
			end := index + 1
			for end < len(runes) && runes[end] == symbol {
				end++
			}
			dateTimeCoreSetSkeletonWidth(fields, symbol, end-index)
			index = end
		} else {
			index++
		}
	}
	return fields
}

func dateTimeCoreWidthKeysContain(widths map[rune]int, predicate func(rune) bool) bool {
	for symbol := range widths {
		if predicate(symbol) {
			return true
		}
	}
	return false
}

func dateTimeCoreSemanticEraSkeleton(dateWidths map[rune]int, length, eraStyle string) string {
	width := 1
	if eraStyle == "auto" {
		width = dateWidths['G']
		if width == 0 {
			if dateTimeCoreIsWideLength(length) {
				width = 4
			} else {
				width = 1
			}
		}
	} else {
		width = dateTimeCoreEraStyleWidth(eraStyle)
	}
	return strings.Repeat("G", width)
}

func dateTimeCoreEraStyleWidth(style string) int {
	if style == "long" {
		return 4
	}
	if style == "narrow" {
		return 5
	}
	return 1
}

func dateTimeCoreSemanticYearSkeleton(dateWidths map[rune]int, yearStyle string, includeEra bool) string {
	yearSymbol := 'y'
	if dateWidths['y'] == 0 {
		if dateWidths['u'] != 0 {
			yearSymbol = 'u'
		} else if dateWidths['r'] != 0 {
			yearSymbol = 'r'
		}
	}
	sourceWidth := dateWidths[yearSymbol]
	if sourceWidth == 0 {
		sourceWidth = 1
	}
	yearWidth := dateTimeCoreSemanticYearWidth(sourceWidth, yearStyle)
	var output strings.Builder
	if includeEra && dateWidths['G'] != 0 {
		output.WriteString(strings.Repeat("G", dateWidths['G']))
	}
	if includeEra && yearStyle == "with-era" && dateWidths['G'] == 0 {
		output.WriteRune('G')
	}
	output.WriteString(strings.Repeat(string(yearSymbol), yearWidth))
	return output.String()
}

func dateTimeCoreSemanticYearWidth(sourceWidth int, yearStyle string) int {
	switch yearStyle {
	case "auto":
		return sourceWidth
	case "2-digit":
		return 2
	case "numeric":
		return 1
	default:
		if sourceWidth == 2 {
			return 1
		}
		return sourceWidth
	}
}

func dateTimeCoreSemanticQuarterSkeleton(fields []string, length, alignment, quarterStyle string) string {
	symbol := 'Q'
	if len(fields) == 1 {
		symbol = 'q'
	}
	width := dateTimeCoreLengthStyleWidth(length)
	if quarterStyle != "auto" {
		width = dateTimeCoreDateFieldStyleWidth(quarterStyle)
	}
	if alignment == "column" && width < 3 {
		width = 2
	}
	return strings.Repeat(string(symbol), width)
}

func dateTimeCoreSemanticMonthSkeleton(fields []string, dateWidths map[rune]int, length, alignment, monthStyle string) string {
	symbol := 'M'
	width := dateTimeCoreLengthStyleWidth(length)
	if len(fields) == 1 {
		symbol = 'L'
		if monthStyle != "auto" {
			width = dateTimeCoreDateFieldStyleWidth(monthStyle)
		}
	} else {
		if dateWidths['M'] != 0 {
			symbol = 'M'
		} else if dateWidths['L'] != 0 {
			symbol = 'L'
		}
		if monthStyle == "auto" {
			width = dateWidths[symbol]
			if width == 0 {
				width = dateTimeCoreLengthStyleWidth(length)
			}
		} else {
			width = dateTimeCoreDateFieldStyleWidth(monthStyle)
		}
	}
	if alignment == "column" && width < 3 {
		width = 2
	}
	return strings.Repeat(string(symbol), width)
}

func dateTimeCoreLengthStyleWidth(length string) int {
	if dateTimeCoreIsWideLength(length) {
		return 4
	}
	if length == "medium" {
		return 3
	}
	return 1
}

func dateTimeCoreIsWideLength(length string) bool {
	return length == "full" || length == "long"
}

func dateTimeCoreDateFieldStyleWidth(style string) int {
	if style == "numeric" {
		return 1
	}
	if style == "2-digit" {
		return 2
	}
	if style == "short" {
		return 3
	}
	if style == "long" {
		return 4
	}
	return 5
}

func dateTimeCoreSemanticDaySkeleton(dateWidths map[rune]int, alignment, dayStyle string) string {
	width := 1
	if dayStyle == "auto" {
		width = dateWidths['d']
		if width == 0 {
			width = 1
		}
	} else {
		width = dateTimeCoreDateFieldStyleWidth(dayStyle)
	}
	if alignment == "column" && width < 3 {
		if width < 2 {
			width = 2
		}
	}
	return strings.Repeat("d", width)
}

func dateTimeCoreSemanticWeekdaySkeleton(fields []string, length, weekdayStyle string) string {
	if weekdayStyle == "short" {
		return "EEE"
	}
	if weekdayStyle == "long" {
		return "EEEE"
	}
	if weekdayStyle == "narrow" {
		return "EEEEE"
	}
	if len(fields) == 1 && length == "short" {
		return "EEEEE"
	}
	if dateTimeCoreIsWideLength(length) {
		return "EEEE"
	}
	return "EEE"
}

func dateTimeCoreSemanticDayPeriodSkeleton(length, dayPeriodStyle string) string {
	style := length
	if dayPeriodStyle != "auto" {
		style = dayPeriodStyle
	}
	width := 1
	if dateTimeCoreIsWideLength(style) {
		width = 4
	} else if style == "narrow" || (dayPeriodStyle == "auto" && length == "short") {
		width = 5
	}
	return strings.Repeat("B", width)
}

func hasDateTimeCoreSemanticTimeComponents(fields []string) bool {
	return dateTimeCoreStringSliceContains(fields, "hour") || dateTimeCoreStringSliceContains(fields, "minute") || dateTimeCoreStringSliceContains(fields, "second") || dateTimeCoreStringSliceContains(fields, "fractionalsecond") || dateTimeCoreStringSliceContains(fields, "millisecondsinday")
}

func dateTimeCoreSemanticExplicitTimeSkeleton(fields []string, hourCycle, alignment string, options map[string]string) (string, error) {
	hasHour := dateTimeCoreStringSliceContains(fields, "hour")
	hasMinute := dateTimeCoreStringSliceContains(fields, "minute")
	hasSecond := dateTimeCoreStringSliceContains(fields, "second")
	hasFractionalSecond := dateTimeCoreStringSliceContains(fields, "fractionalsecond")
	hasMillisecondsInDay := dateTimeCoreStringSliceContains(fields, "millisecondsinday")
	var output strings.Builder
	if hasHour {
		fallbackWidth := 1
		if alignment == "column" {
			fallbackWidth = 2
		}
		hourWidth := dateTimeCoreSemanticNumericFieldWidth(options, "hourstyle", fallbackWidth)
		output.WriteString(strings.Repeat(string(dateTimeCoreSemanticHourSymbol(hourCycle)), hourWidth))
	}
	if hasMinute {
		fallbackWidth := 1
		if !hasHour && !hasSecond && alignment == "column" {
			fallbackWidth = 2
		}
		width := dateTimeCoreSemanticNumericFieldWidth(options, "minutestyle", fallbackWidth)
		output.WriteString(strings.Repeat("m", width))
	}
	if hasSecond {
		fallbackWidth := 1
		if !hasHour && !hasMinute && alignment == "column" {
			fallbackWidth = 2
		}
		width := dateTimeCoreSemanticNumericFieldWidth(options, "secondstyle", fallbackWidth)
		output.WriteString(strings.Repeat("s", width))
	}
	if hasFractionalSecond {
		width, err := dateTimeCoreSemanticFractionalSecondWidth(options)
		if err != nil {
			return "", err
		}
		output.WriteString(strings.Repeat("S", width))
	}
	if hasMillisecondsInDay {
		width := 1
		if alignment == "column" {
			width = 8
		}
		output.WriteString(strings.Repeat("A", width))
	}
	return output.String(), nil
}

func dateTimeCoreSemanticNumericFieldWidth(options map[string]string, key string, fallbackWidth int) int {
	switch options[key] {
	case "", "auto":
		return fallbackWidth
	case "2-digit":
		return 2
	default:
		return 1
	}
}

func dateTimeCoreSemanticFractionalSecondWidth(options map[string]string) (int, error) {
	width, err := strconv.Atoi(options["fractionalsecond"])
	if err != nil || width < 1 || width > 9 {
		return 0, badOption("Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.")
	}
	return width, nil
}

func dateTimeCoreSemanticTimeSkeleton(timePrecision, hourCycle, alignment string, value time.Time, options map[string]string) (string, error) {
	var output strings.Builder
	hourWidth := 1
	if alignment == "column" {
		hourWidth = 2
	}
	output.WriteString(strings.Repeat(string(dateTimeCoreSemanticHourSymbol(hourCycle)), hourWidth))
	if timePrecision == "minute" || timePrecision == "second" || timePrecision == "fractional-second" {
		output.WriteRune('m')
	}
	if timePrecision == "minute-optional" && value.Minute() != 0 {
		output.WriteRune('m')
	}
	if timePrecision == "second" || timePrecision == "fractional-second" {
		output.WriteRune('s')
	}
	if timePrecision == "fractional-second" {
		width, err := dateTimeCoreSemanticFractionalSecondWidth(options)
		if err != nil {
			return "", err
		}
		output.WriteString(strings.Repeat("S", width))
	} else if options["fractionalsecond"] != "" {
		return "", badOption("fractionalSecond requires timePrecision=fractional-second.")
	}
	return output.String(), nil
}

func dateTimeCoreSemanticTimeStylePrecision(timeStyle, timePrecision string) string {
	switch timeStyle {
	case "short":
		return "minute"
	case "medium", "long", "full":
		return "second"
	default:
		return timePrecision
	}
}

func dateTimeCoreSemanticTimeStyleZoneStyle(timeStyle, zoneStyle string) string {
	if dateTimeCoreSemanticTimeStyleHasZone(timeStyle) {
		return "specific"
	}
	return zoneStyle
}

func dateTimeCoreSemanticTimeStyleHasZone(timeStyle string) bool {
	return timeStyle == "long" || timeStyle == "full"
}

func dateTimeCoreSemanticHourSymbol(hourCycle string) rune {
	switch hourCycle {
	case "h11":
		return 'K'
	case "h12", "clock12":
		return 'h'
	case "h23", "clock24":
		return 'H'
	case "h24":
		return 'k'
	default:
		return 'C'
	}
}

func dateTimeCoreSemanticZoneSkeleton(zoneStyle string, standalone bool, length string) string {
	style := zoneStyle
	if style == "auto" {
		style = "generic"
	}
	switch style {
	case "specific":
		if standalone && length != "short" {
			return "zzzz"
		}
		return "z"
	case "location":
		return "VVVV"
	case "offset":
		return "O"
	default:
		if standalone && length != "short" {
			return "vvvv"
		}
		return "v"
	}
}

func applyDateTimeCoreCHourFormat(widths map[rune]int, localeData cldrDateTimeLocaleData, hourCycle string, width int) {
	if hourCycle != "" {
		hourSymbol := preferredDateTimeCoreHourSymbol(localeData, hourCycle)
		dateTimeCoreSetSkeletonWidth(widths, hourSymbol, dateTimeCoreCHourWidth(width))
		if dateTimeCoreIsHour12Field(hourSymbol) {
			dateTimeCoreSetSkeletonWidth(widths, 'B', dateTimeCoreCDayPeriodWidth(width))
		}
		return
	}
	for _, token := range strings.Fields(localeData.allowedHourFormats) {
		runes := []rune(token)
		if !dateTimeCoreIsCHourFormatToken(runes) {
			continue
		}
		dateTimeCoreSetSkeletonWidth(widths, runes[0], dateTimeCoreCHourWidth(width))
		if len(runes) > 1 {
			dateTimeCoreSetSkeletonWidth(widths, runes[1], dateTimeCoreCDayPeriodWidth(width))
		}
		return
	}
	dateTimeCoreSetSkeletonWidth(widths, preferredDateTimeCoreHourSymbol(localeData, hourCycle), dateTimeCoreCHourWidth(width))
}

func dateTimeCoreIsCHourFormatToken(token []rune) bool {
	if len(token) < 1 || len(token) > 2 {
		return false
	}
	if token[0] != 'h' && token[0] != 'H' && token[0] != 'k' && token[0] != 'K' {
		return false
	}
	return len(token) == 1 || token[1] == 'b' || token[1] == 'B'
}

func dateTimeCoreSetSkeletonWidth(widths map[rune]int, symbol rune, width int) {
	if width > widths[symbol] {
		widths[symbol] = width
	}
}

func normalizeDateTimeCoreSkeletonSymbol(symbol rune, localeData cldrDateTimeLocaleData, hourCycle string) rune {
	if symbol == 'l' {
		return 'L'
	}
	if symbol == 'j' || symbol == 'J' {
		return preferredDateTimeCoreHourSymbol(localeData, hourCycle)
	}
	return symbol
}

func dateTimeCoreCHourWidth(width int) int {
	if width%2 == 0 {
		return 2
	}
	return 1
}

func dateTimeCoreCDayPeriodWidth(width int) int {
	if width >= 5 {
		return 5
	}
	if width >= 3 {
		return 4
	}
	return 1
}

func shouldSuppressDateTimeCoreDayPeriod(skeleton string) bool {
	return strings.ContainsRune(skeleton, 'J') &&
		!strings.ContainsRune(skeleton, 'a') &&
		!strings.ContainsRune(skeleton, 'b') &&
		!strings.ContainsRune(skeleton, 'B') &&
		!strings.ContainsRune(skeleton, 'C')
}

func stripDateTimeCoreDayPeriodPatternFields(pattern string) string {
	runes := []rune(pattern)
	var output strings.Builder
	var pendingWhitespace strings.Builder
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			quoted := readDateTimeCoreQuotedPattern(runes, index)
			output.WriteString(pendingWhitespace.String())
			output.WriteString(string(runes[index:quoted.nextIndex]))
			pendingWhitespace.Reset()
			index = quoted.nextIndex
		} else if isDateTimeCoreASCIILetter(ch) {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			if isDateTimeCoreDayPeriodField(ch) {
				pendingWhitespace.Reset()
			} else {
				output.WriteString(pendingWhitespace.String())
				output.WriteString(string(runes[index:end]))
				pendingWhitespace.Reset()
			}
			index = end
		} else if dateTimeCoreIsPatternWhitespace(ch) {
			pendingWhitespace.WriteRune(ch)
			index++
		} else {
			output.WriteString(pendingWhitespace.String())
			output.WriteRune(ch)
			pendingWhitespace.Reset()
			index++
		}
	}
	output.WriteString(pendingWhitespace.String())
	return strings.TrimFunc(output.String(), dateTimeCoreIsPatternWhitespace)
}

func dateTimeCoreIsPatternWhitespace(value rune) bool {
	return value == ' ' || value == '\u00A0' || value == '\u202F' || unicode.IsSpace(value)
}

func preferredDateTimeCoreHourSymbol(localeData cldrDateTimeLocaleData, hourCycle string) rune {
	if hourCycle == "h11" {
		return 'K'
	}
	if hourCycle == "h12" {
		return 'h'
	}
	if hourCycle == "h23" {
		return 'H'
	}
	if hourCycle == "h24" {
		return 'k'
	}
	shortTime := localeData.timeFormats[DateTimeCoreStyleShort]
	if strings.ContainsRune(shortTime, 'H') {
		return 'H'
	}
	if strings.ContainsRune(shortTime, 'k') {
		return 'k'
	}
	if strings.ContainsRune(shortTime, 'K') {
		return 'K'
	}
	return 'h'
}

func dateTimeCoreSkeletonFieldSet(skeleton string) string {
	seen := map[rune]bool{}
	for symbol := range dateTimeCoreSkeletonWidths(skeleton) {
		seen[dateTimeCoreFieldSetSymbol(symbol)] = true
	}
	var output strings.Builder
	for _, symbol := range dateTimeCoreSkeletonFieldOrder {
		if seen[symbol] {
			output.WriteRune(symbol)
		}
	}
	return output.String()
}

func dateTimeCoreFieldSetSymbol(symbol rune) rune {
	if isDateTimeCoreYearField(symbol) {
		return 'y'
	}
	if isDateTimeCoreHourField(symbol) {
		return 'J'
	}
	if isDateTimeCoreMonthField(symbol) {
		return 'M'
	}
	if isDateTimeCoreQuarterField(symbol) {
		return 'Q'
	}
	if isDateTimeCoreDayPeriodField(symbol) {
		return 'B'
	}
	if isDateTimeCoreWeekdayField(symbol) {
		return 'E'
	}
	if isDateTimeCoreTimeZoneField(symbol) {
		return 'v'
	}
	return symbol
}

func dateTimeCoreSkeletonDistance(requested, candidate string) int {
	requestedWidths := dateTimeCoreSkeletonWidths(requested)
	candidateWidths := dateTimeCoreSkeletonWidths(candidate)
	distance := 0
	for symbol, requestedWidth := range requestedWidths {
		candidateSymbol, hasCandidate := dateTimeCoreCandidateSymbolForRequested(symbol, candidateWidths)
		candidateWidth := 0
		if hasCandidate {
			candidateWidth = candidateWidths[candidateSymbol]
		}
		if requestedWidth > candidateWidth {
			distance += requestedWidth - candidateWidth
		} else {
			distance += candidateWidth - requestedWidth
		}
		if dateTimeCoreIsTextWidth(requestedWidth) != dateTimeCoreIsTextWidth(candidateWidth) {
			distance += 8
		}
		distance += dateTimeCoreHourFieldDistance(symbol, candidateSymbol, hasCandidate)
	}
	return distance
}

func dateTimeCoreSkeletonWidths(skeleton string) map[rune]int {
	widths := map[rune]int{}
	runes := []rune(skeleton)
	for index := 0; index < len(runes); {
		symbol := runes[index]
		end := index + 1
		for end < len(runes) && runes[end] == symbol {
			end++
		}
		if end-index > widths[symbol] {
			widths[symbol] = end - index
		}
		index = end
	}
	return widths
}

func dateTimeCoreIsTextWidth(width int) bool {
	return width >= 3
}

func isDateTimeCoreHourField(symbol rune) bool {
	return strings.ContainsRune(dateTimeCoreSkeletonHourFields, symbol)
}

func isDateTimeCoreYearField(symbol rune) bool {
	return symbol == 'y' || symbol == 'u' || symbol == 'r'
}

func isDateTimeCoreWeekdayField(symbol rune) bool {
	return symbol == 'E' || symbol == 'e' || symbol == 'c'
}

func isDateTimeCoreMonthField(symbol rune) bool {
	return symbol == 'M' || symbol == 'L'
}

func isDateTimeCoreQuarterField(symbol rune) bool {
	return symbol == 'Q' || symbol == 'q'
}

func isDateTimeCoreDayPeriodField(symbol rune) bool {
	return symbol == 'a' || symbol == 'b' || symbol == 'B'
}

func isDateTimeCoreSyntheticNumericField(symbol rune) bool {
	return symbol == 'D' || symbol == 'F' || symbol == 'g' || symbol == 'm' || symbol == 's' || symbol == 'A'
}

func isDateTimeCoreTimeZoneField(symbol rune) bool {
	return strings.ContainsRune("zZOvVXx", symbol)
}

func dateTimeCoreCandidateSymbolForRequested(symbol rune, candidateWidths map[rune]int) (rune, bool) {
	if _, ok := candidateWidths[symbol]; ok {
		return symbol, true
	}
	if isDateTimeCoreYearField(symbol) {
		for _, yearSymbol := range []rune{'y', 'u', 'r'} {
			if _, ok := candidateWidths[yearSymbol]; ok {
				return yearSymbol, true
			}
		}
	}
	if isDateTimeCoreHourField(symbol) {
		for _, hourSymbol := range dateTimeCoreSkeletonHourFields {
			if _, ok := candidateWidths[hourSymbol]; ok {
				return hourSymbol, true
			}
		}
		return 0, false
	}
	if isDateTimeCoreQuarterField(symbol) {
		for _, quarterSymbol := range []rune{'Q', 'q'} {
			if _, ok := candidateWidths[quarterSymbol]; ok {
				return quarterSymbol, true
			}
		}
	}
	if isDateTimeCoreMonthField(symbol) {
		for _, monthSymbol := range []rune{'M', 'L'} {
			if _, ok := candidateWidths[monthSymbol]; ok {
				return monthSymbol, true
			}
		}
	}
	if isDateTimeCoreDayPeriodField(symbol) {
		for _, dayPeriodSymbol := range []rune{'B', 'b', 'a'} {
			if _, ok := candidateWidths[dayPeriodSymbol]; ok {
				return dayPeriodSymbol, true
			}
		}
	}
	if isDateTimeCoreWeekdayField(symbol) {
		for _, weekdaySymbol := range []rune{'E', 'e', 'c'} {
			if _, ok := candidateWidths[weekdaySymbol]; ok {
				return weekdaySymbol, true
			}
		}
	}
	if isDateTimeCoreTimeZoneField(symbol) {
		for _, timeZoneSymbol := range []rune{'v', 'z', 'O', 'Z', 'X', 'x', 'V'} {
			if _, ok := candidateWidths[timeZoneSymbol]; ok {
				return timeZoneSymbol, true
			}
		}
	}
	return 0, false
}

func dateTimeCoreHourFieldDistance(requestedSymbol, candidateSymbol rune, hasCandidate bool) int {
	if !hasCandidate || requestedSymbol == candidateSymbol || !isDateTimeCoreHourField(requestedSymbol) || !isDateTimeCoreHourField(candidateSymbol) {
		return 0
	}
	if dateTimeCoreIsHour12Field(requestedSymbol) == dateTimeCoreIsHour12Field(candidateSymbol) {
		return 1
	}
	return 4
}

func dateTimeCoreIsHour12Field(symbol rune) bool {
	return symbol == 'h' || symbol == 'K'
}

func dateTimeCoreRequestedSymbolForPattern(symbol rune, requestedWidths map[rune]int, candidateWidths map[rune]int) rune {
	if isDateTimeCoreYearField(symbol) {
		if _, ok := dateTimeCoreCandidateSymbolForRequested(symbol, candidateWidths); ok {
			if requestedSymbol, ok := dateTimeCoreCandidateSymbolForRequested(symbol, requestedWidths); ok {
				return requestedSymbol
			}
		}
		return symbol
	}
	if isDateTimeCoreWeekdayField(symbol) {
		if _, ok := dateTimeCoreCandidateSymbolForRequested(symbol, candidateWidths); ok {
			return dateTimeCoreRequestedWeekdaySymbolForPattern(symbol, requestedWidths)
		}
		return symbol
	}
	if isDateTimeCoreDayPeriodField(symbol) {
		if _, ok := dateTimeCoreCandidateSymbolForRequested(symbol, candidateWidths); ok {
			return dateTimeCoreRequestedDayPeriodSymbolForPattern(symbol, requestedWidths)
		}
		return symbol
	}
	if isDateTimeCoreTimeZoneField(symbol) {
		if _, ok := dateTimeCoreCandidateSymbolForRequested(symbol, candidateWidths); ok {
			return dateTimeCoreRequestedTimeZoneSymbolForPattern(symbol, requestedWidths)
		}
		return symbol
	}
	if !isDateTimeCoreYearField(symbol) && !isDateTimeCoreHourField(symbol) && !isDateTimeCoreMonthField(symbol) && !isDateTimeCoreQuarterField(symbol) && !isDateTimeCoreDayPeriodField(symbol) && !isDateTimeCoreTimeZoneField(symbol) {
		return symbol
	}
	if _, ok := dateTimeCoreCandidateSymbolForRequested(symbol, candidateWidths); !ok {
		return symbol
	}
	if requestedSymbol, ok := dateTimeCoreCandidateSymbolForRequested(symbol, requestedWidths); ok {
		return requestedSymbol
	}
	return symbol
}

func dateTimeCoreRequestedWeekdaySymbolForPattern(symbol rune, requestedWidths map[rune]int) rune {
	if _, ok := requestedWidths['c']; ok {
		return 'c'
	}
	if _, ok := requestedWidths['e']; ok {
		return 'e'
	}
	if _, ok := requestedWidths['E']; ok {
		return 'E'
	}
	return symbol
}

func dateTimeCoreRequestedDayPeriodSymbolForPattern(symbol rune, requestedWidths map[rune]int) rune {
	if _, ok := requestedWidths['a']; ok {
		return 'a'
	}
	if _, ok := requestedWidths['b']; ok {
		return 'b'
	}
	if _, ok := requestedWidths['B']; ok {
		return 'B'
	}
	return symbol
}

func dateTimeCoreRequestedTimeZoneSymbolForPattern(symbol rune, requestedWidths map[rune]int) rune {
	for _, timeZoneSymbol := range []rune{'z', 'Z', 'O', 'v', 'V', 'X', 'x'} {
		if _, ok := requestedWidths[timeZoneSymbol]; ok {
			return timeZoneSymbol
		}
	}
	return symbol
}

func dateTimeCoreWidthForPatternSymbol(symbol rune, widths map[rune]int) (int, bool) {
	if width, ok := widths[symbol]; ok {
		return width, true
	}
	if isDateTimeCoreYearField(symbol) {
		for _, yearSymbol := range []rune{'y', 'u', 'r'} {
			if width, ok := widths[yearSymbol]; ok {
				return width, true
			}
		}
	}
	if isDateTimeCoreWeekdayField(symbol) {
		for _, weekdaySymbol := range []rune{'E', 'e', 'c'} {
			if width, ok := widths[weekdaySymbol]; ok {
				return width, true
			}
		}
	}
	if isDateTimeCoreMonthField(symbol) {
		for _, monthSymbol := range []rune{'M', 'L'} {
			if width, ok := widths[monthSymbol]; ok {
				return width, true
			}
		}
	}
	if isDateTimeCoreDayPeriodField(symbol) {
		for _, dayPeriodSymbol := range []rune{'B', 'b', 'a'} {
			if width, ok := widths[dayPeriodSymbol]; ok {
				return width, true
			}
		}
	}
	if isDateTimeCoreQuarterField(symbol) {
		for _, quarterSymbol := range []rune{'Q', 'q'} {
			if width, ok := widths[quarterSymbol]; ok {
				return width, true
			}
		}
	}
	if isDateTimeCoreTimeZoneField(symbol) {
		for _, timeZoneSymbol := range []rune{'z', 'Z', 'O', 'v', 'V', 'X', 'x'} {
			if width, ok := widths[timeZoneSymbol]; ok {
				return width, true
			}
		}
	}
	return 0, false
}

func dateTimeCoreAdjustPatternWidths(pattern, requestedSkeleton, candidateSkeleton string) string {
	requestedWidths := dateTimeCoreSkeletonWidths(requestedSkeleton)
	candidateWidths := dateTimeCoreSkeletonWidths(candidateSkeleton)
	runes := []rune(pattern)
	var output strings.Builder
	inQuote := false
	for index := 0; index < len(runes); {
		ch := runes[index]
		if ch == '\'' {
			output.WriteRune(ch)
			if index+1 < len(runes) && runes[index+1] == '\'' {
				output.WriteRune('\'')
				index += 2
			} else {
				inQuote = !inQuote
				index++
			}
		} else if !inQuote && isDateTimeCoreASCIILetter(ch) {
			end := index + 1
			for end < len(runes) && runes[end] == ch {
				end++
			}
			patternWidth := end - index
			width := patternWidth
			requestedSymbol := dateTimeCoreRequestedSymbolForPattern(ch, requestedWidths, candidateWidths)
			if requestedWidth, hasRequested := dateTimeCoreWidthForPatternSymbol(ch, requestedWidths); hasRequested {
				if candidateWidth, hasCandidate := dateTimeCoreWidthForPatternSymbol(ch, candidateWidths); hasCandidate && dateTimeCoreShouldAdjustPatternWidth(requestedSymbol, requestedWidth, candidateWidth, patternWidth) {
					width = requestedWidth
				}
			}
			for count := 0; count < width; count++ {
				output.WriteRune(requestedSymbol)
			}
			index = end
		} else {
			output.WriteRune(ch)
			index++
		}
	}
	return output.String()
}

func dateTimeCoreShouldAdjustPatternWidth(symbol rune, requestedWidth, candidateWidth, patternWidth int) bool {
	if (symbol == 'e' || symbol == 'c') && patternWidth >= 3 && requestedWidth <= 2 {
		return true
	}
	if isDateTimeCoreWeekdayField(symbol) && patternWidth >= 3 && requestedWidth >= 4 {
		return true
	}
	return patternWidth == candidateWidth
}

func splitDateTimeCoreSkeleton(skeleton string) (string, string) {
	var dateSkeleton strings.Builder
	var timeSkeleton strings.Builder
	for _, symbol := range skeleton {
		if strings.ContainsRune(dateTimeCoreSkeletonTimeFields, symbol) {
			timeSkeleton.WriteRune(symbol)
		} else {
			dateSkeleton.WriteRune(symbol)
		}
	}
	return dateSkeleton.String(), timeSkeleton.String()
}

func formatDateTimeCoreField(symbol rune, count int, value time.Time, localeData cldrDateTimeLocaleData) (string, error) {
	switch symbol {
	case 'G':
		return dateTimeCoreEraName(value, localeData, count), nil
	case 'y':
		return dateTimeCoreYearValue(value, localeData, count), nil
	case 'u':
		return dateTimeCoreExtendedYearValue(value, localeData, count), nil
	case 'r':
		return dateTimeCoreExtendedYearValue(value, localeData, count), nil
	case 'Y':
		return dateTimeCoreWeekYearValue(value, localeData, count), nil
	case 'Q', 'q':
		return dateTimeCoreQuarterValue(value, localeData, count, symbol == 'q'), nil
	case 'M', 'L':
		return dateTimeCoreMonthValue(value, localeData, count, symbol == 'L'), nil
	case 'd':
		return dateTimeCoreIntegerValue(value.Day(), localeData, count), nil
	case 'D':
		return dateTimeCoreIntegerValue(value.YearDay(), localeData, count), nil
	case 'F':
		return dateTimeCoreIntegerValue(dateTimeCoreDayOfWeekInMonth(value), localeData, count), nil
	case 'g':
		return dateTimeCoreIntegerValue(dateTimeCoreModifiedJulianDay(value), localeData, count), nil
	case 'w':
		return dateTimeCoreIntegerValue(dateTimeCoreWeekOfYear(value, localeData), localeData, count), nil
	case 'W':
		return dateTimeCoreIntegerValue(dateTimeCoreWeekOfMonth(value, localeData), localeData, count), nil
	case 'E':
		return dateTimeCoreWeekdayName(value, localeData, count), nil
	case 'e':
		return dateTimeCoreLocalWeekdayValue(value, localeData, count, false), nil
	case 'c':
		return dateTimeCoreLocalWeekdayValue(value, localeData, count, true), nil
	case 'a', 'b', 'B':
		return dateTimeCoreDayPeriodName(value, localeData, count, symbol), nil
	case 'H':
		return dateTimeCoreIntegerValue(value.Hour(), localeData, count), nil
	case 'k':
		if value.Hour() == 0 {
			return dateTimeCoreIntegerValue(24, localeData, count), nil
		}
		return dateTimeCoreIntegerValue(value.Hour(), localeData, count), nil
	case 'h':
		return dateTimeCoreIntegerValue(dateTimeCoreHour12(value), localeData, count), nil
	case 'K':
		return dateTimeCoreIntegerValue(value.Hour()%12, localeData, count), nil
	case 'm':
		return dateTimeCoreIntegerValue(value.Minute(), localeData, count), nil
	case 's':
		return dateTimeCoreIntegerValue(value.Second(), localeData, count), nil
	case 'S':
		return dateTimeCoreFractionValue(value, localeData, count), nil
	case 'A':
		return dateTimeCoreIntegerValue(dateTimeCoreMillisecondsInDay(value), localeData, count), nil
	case 'z', 'Z', 'O', 'v', 'V', 'X', 'x':
		return dateTimeCoreTimeZoneValue(symbol, count, value, localeData), nil
	default:
		return "", badOption("Unsupported CLDR date/time pattern field: " + string(symbol) + ".")
	}
}

func dateTimeCoreTimeZoneValue(symbol rune, count int, value time.Time, localeData cldrDateTimeLocaleData) string {
	_, offsetSeconds := value.Zone()
	offsetMinutes := offsetSeconds / 60
	if offsetMinutes != 0 {
		switch symbol {
		case 'X':
			return dateTimeCoreIsoOffset(offsetMinutes, count, true)
		case 'x':
			return dateTimeCoreIsoOffset(offsetMinutes, count, false)
		case 'V':
			if count == 1 {
				return "unk"
			}
			if count == 2 {
				return dateTimeCoreFixedOffsetGmtID(localeData, offsetMinutes)
			}
			if count == 3 {
				return "Unknown Location"
			}
		case 'Z':
			if count <= 3 {
				return dateTimeCoreBasicOffset(offsetMinutes)
			}
			if count == 5 {
				return dateTimeCoreIsoOffset(offsetMinutes, 3, true)
			}
		}
		return dateTimeCoreLocalizedGmtOffset(localeData, offsetMinutes, count)
	}
	switch symbol {
	case 'z':
		if count >= 4 {
			if value := localeData.timeZoneNames["utcLong"]; value != "" {
				return value
			}
			if value := localeData.timeZoneNames["utcShort"]; value != "" {
				return value
			}
			return dateTimeCoreUTC
		}
		if value := localeData.timeZoneNames["utcShort"]; value != "" {
			return value
		}
		return dateTimeCoreUTC
	case 'O', 'v':
		return dateTimeCoreLocalizedGmtZero(localeData)
	case 'V':
		return dateTimeCoreLocalizedGmtZero(localeData)
	case 'Z':
		if count <= 3 {
			return "+0000"
		}
		if count == 5 {
			return "Z"
		}
		return dateTimeCoreLocalizedGmtZero(localeData)
	case 'X':
		return "Z"
	case 'x':
		if count == 1 {
			return "+00"
		}
		if count == 2 || count == 4 {
			return "+0000"
		}
		return "+00:00"
	default:
		return dateTimeCoreUTC
	}
}

func dateTimeCoreLocalizedGmtZero(localeData cldrDateTimeLocaleData) string {
	if value := localeData.timeZoneNames["gmtZeroFormat"]; value != "" {
		return value
	}
	format := localeData.timeZoneNames["gmtFormat"]
	if format == "" {
		format = "GMT{0}"
	}
	return strings.ReplaceAll(format, "{0}", "")
}

func dateTimeCoreLocalizedGmtOffset(localeData cldrDateTimeLocaleData, offsetMinutes int, count int) string {
	offset := dateTimeCoreShortOffset(offsetMinutes)
	if count >= 4 {
		offset = dateTimeCoreExtendedOffset(offsetMinutes, true)
	}
	format := localeData.timeZoneNames["gmtFormat"]
	if format == "" {
		format = "GMT{0}"
	}
	return strings.ReplaceAll(format, "{0}", localizeDateTimeCoreDigits(offset, localeData.numberingSystemDigits))
}

func dateTimeCoreFixedOffsetGmtID(localeData cldrDateTimeLocaleData, offsetMinutes int) string {
	return "GMT" + localizeDateTimeCoreDigits(dateTimeCoreExtendedOffset(offsetMinutes, true), localeData.numberingSystemDigits)
}

func dateTimeCoreIsoOffset(offsetMinutes int, count int, useZeroZ bool) string {
	if offsetMinutes == 0 && useZeroZ {
		return "Z"
	}
	if count == 1 {
		return dateTimeCoreShortIsoOffset(offsetMinutes)
	}
	if count == 2 || count == 4 {
		return dateTimeCoreBasicOffset(offsetMinutes)
	}
	return dateTimeCoreExtendedOffset(offsetMinutes, true)
}

func dateTimeCoreShortIsoOffset(offsetMinutes int) string {
	sign, hours, minutes := dateTimeCoreOffsetParts(offsetMinutes)
	if minutes == 0 {
		return fmt.Sprintf("%s%02d", sign, hours)
	}
	return fmt.Sprintf("%s%02d%02d", sign, hours, minutes)
}

func dateTimeCoreShortOffset(offsetMinutes int) string {
	sign, hours, minutes := dateTimeCoreOffsetParts(offsetMinutes)
	if minutes == 0 {
		return fmt.Sprintf("%s%d", sign, hours)
	}
	return fmt.Sprintf("%s%d:%02d", sign, hours, minutes)
}

func dateTimeCoreBasicOffset(offsetMinutes int) string {
	sign, hours, minutes := dateTimeCoreOffsetParts(offsetMinutes)
	return fmt.Sprintf("%s%02d%02d", sign, hours, minutes)
}

func dateTimeCoreExtendedOffset(offsetMinutes int, paddedHour bool) string {
	sign, hours, minutes := dateTimeCoreOffsetParts(offsetMinutes)
	if paddedHour {
		return fmt.Sprintf("%s%02d:%02d", sign, hours, minutes)
	}
	return fmt.Sprintf("%s%d:%02d", sign, hours, minutes)
}

func dateTimeCoreOffsetParts(offsetMinutes int) (string, int, int) {
	sign := "+"
	if offsetMinutes < 0 {
		sign = "-"
		offsetMinutes = -offsetMinutes
	}
	return sign, offsetMinutes / 60, offsetMinutes % 60
}

func dateTimeCoreEraName(value time.Time, localeData cldrDateTimeLocaleData, count int) string {
	era := "1"
	if value.Year() <= 0 {
		era = "0"
	}
	return dateTimeCoreNameByWidth(localeData.eras, dateTimeCoreWidthForText(count), era)
}

func dateTimeCoreYearValue(value time.Time, localeData cldrDateTimeLocaleData, count int) string {
	year := value.Year()
	if year <= 0 {
		year = 1 - year
	}
	if count == 2 {
		return dateTimeCoreIntegerText(year%100, localeData, 2)
	}
	return localizeDateTimeCoreDigits(strconv.Itoa(year), localeData.numberingSystemDigits)
}

func dateTimeCoreExtendedYearValue(value time.Time, localeData cldrDateTimeLocaleData, count int) string {
	return dateTimeCoreIntegerValue(value.Year(), localeData, count)
}

func dateTimeCoreWeekYearValue(value time.Time, localeData cldrDateTimeLocaleData, count int) string {
	year, _ := dateTimeCoreWeekYearInfo(value, localeData)
	if count == 2 {
		return dateTimeCoreIntegerText(year%100, localeData, 2)
	}
	return localizeDateTimeCoreDigits(strconv.Itoa(year), localeData.numberingSystemDigits)
}

func dateTimeCoreMonthValue(value time.Time, localeData cldrDateTimeLocaleData, count int, standAlone bool) string {
	month := int(value.Month())
	if count <= 2 {
		return dateTimeCoreIntegerValue(month, localeData, count)
	}
	context := "format"
	if standAlone {
		context = "stand-alone"
	}
	return dateTimeCoreContextualName(localeData.months, context, dateTimeCoreWidthForText(count), strconv.Itoa(month))
}

func dateTimeCoreQuarterValue(value time.Time, localeData cldrDateTimeLocaleData, count int, standAlone bool) string {
	quarter := (int(value.Month())-1)/3 + 1
	if count <= 2 {
		return dateTimeCoreIntegerValue(quarter, localeData, count)
	}
	context := "format"
	if standAlone {
		context = "stand-alone"
	}
	return dateTimeCoreContextualName(localeData.quarters, context, dateTimeCoreWidthForText(count), strconv.Itoa(quarter))
}

func dateTimeCoreWeekdayName(value time.Time, localeData cldrDateTimeLocaleData, count int) string {
	return dateTimeCoreContextualName(
		localeData.weekdays,
		"format",
		dateTimeCoreWidthForWeekday(count),
		dateTimeCoreWeekdayKeys[int(value.Weekday())],
	)
}

func dateTimeCoreLocalWeekdayValue(value time.Time, localeData cldrDateTimeLocaleData, count int, standAlone bool) string {
	day := int(value.Weekday())
	if count <= 2 {
		return dateTimeCoreIntegerValue(dateTimeCoreModulo(day-localeData.firstDayOfWeek, 7)+1, localeData, count)
	}
	context := "format"
	if standAlone {
		context = "stand-alone"
	}
	return dateTimeCoreContextualName(localeData.weekdays, context, dateTimeCoreWidthForWeekday(count), dateTimeCoreWeekdayKeys[day])
}

func dateTimeCoreDayOfWeekInMonth(value time.Time) int {
	return ((value.Day() - 1) / 7) + 1
}

func dateTimeCoreMillisecondsInDay(value time.Time) int {
	return ((value.Hour()*60+value.Minute())*60+value.Second())*1000 + value.Nanosecond()/1_000_000
}

func dateTimeCoreModifiedJulianDay(value time.Time) int {
	return dateTimeCoreOrdinalDay(value.Year(), int(value.Month()), value.Day()) - dateTimeCoreOrdinalDay(1858, 11, 17)
}

func dateTimeCoreWeekOfYear(value time.Time, localeData cldrDateTimeLocaleData) int {
	_, week := dateTimeCoreWeekYearInfo(value, localeData)
	return week
}

func dateTimeCoreWeekYearInfo(value time.Time, localeData cldrDateTimeLocaleData) (int, int) {
	year := value.Year()
	ordinal := dateTimeCoreOrdinalDay(year, int(value.Month()), value.Day())
	weekStart := dateTimeCoreStartOfWeek(ordinal, localeData.firstDayOfWeek)
	currentStart := dateTimeCoreFirstWeekStartOfYear(year, localeData)
	nextStart := dateTimeCoreFirstWeekStartOfYear(year+1, localeData)
	if weekStart >= nextStart {
		return year + 1, 1
	}
	if weekStart < currentStart {
		previousYear := year - 1
		previousStart := dateTimeCoreFirstWeekStartOfYear(previousYear, localeData)
		return previousYear, (weekStart-previousStart)/7 + 1
	}
	return year, (weekStart-currentStart)/7 + 1
}

func dateTimeCoreWeekOfMonth(value time.Time, localeData cldrDateTimeLocaleData) int {
	ordinal := dateTimeCoreOrdinalDay(value.Year(), int(value.Month()), value.Day())
	weekStart := dateTimeCoreStartOfWeek(ordinal, localeData.firstDayOfWeek)
	firstStart := dateTimeCoreFirstWeekStart(dateTimeCoreOrdinalDay(value.Year(), int(value.Month()), 1), localeData)
	return (weekStart-firstStart)/7 + 1
}

func dateTimeCoreFirstWeekStartOfYear(year int, localeData cldrDateTimeLocaleData) int {
	return dateTimeCoreFirstWeekStart(dateTimeCoreOrdinalDay(year, 1, 1), localeData)
}

func dateTimeCoreFirstWeekStart(periodStart int, localeData cldrDateTimeLocaleData) int {
	weekStart := dateTimeCoreStartOfWeek(periodStart, localeData.firstDayOfWeek)
	daysInPeriod := weekStart + 7 - periodStart
	if daysInPeriod >= localeData.minDaysInFirstWeek {
		return weekStart
	}
	return weekStart + 7
}

func dateTimeCoreStartOfWeek(ordinal, firstDay int) int {
	return ordinal - dateTimeCoreModulo(dateTimeCoreDayOfWeek(ordinal)-firstDay, 7)
}

func dateTimeCoreDayOfWeek(ordinal int) int {
	return dateTimeCoreModulo(ordinal, 7)
}

func dateTimeCoreOrdinalDay(year, month, day int) int {
	return dateTimeCoreDaysBeforeYear(year) + dateTimeCoreDaysBeforeMonth(year, month) + day
}

func dateTimeCoreDaysBeforeYear(year int) int {
	previous := year - 1
	return 365*previous + previous/4 - previous/100 + previous/400
}

func dateTimeCoreDaysBeforeMonth(year, month int) int {
	lengths := []int{0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334}
	if dateTimeCoreIsLeapYear(year) {
		lengths = []int{0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335}
	}
	if month < 1 || month > len(lengths) {
		return 0
	}
	return lengths[month-1]
}

func dateTimeCoreIsLeapYear(year int) bool {
	return year%4 == 0 && (year%100 != 0 || year%400 == 0)
}

func dateTimeCoreModulo(value, divisor int) int {
	result := value % divisor
	if result < 0 {
		return result + divisor
	}
	return result
}

func dateTimeCoreDayPeriodName(value time.Time, localeData cldrDateTimeLocaleData, count int, symbol rune) string {
	period := dateTimeCoreDayPeriodKey(value, localeData, symbol)
	return dateTimeCoreContextualName(localeData.dayPeriods, "format", dateTimeCoreWidthForDayPeriod(count), period)
}

func dateTimeCoreDayPeriodKey(value time.Time, localeData cldrDateTimeLocaleData, symbol rune) string {
	fallback := "am"
	if value.Hour() >= 12 {
		fallback = "pm"
	}
	if symbol == 'a' {
		return fallback
	}
	if symbol == 'b' {
		if period, ok := selectDateTimeCoreDayPeriodRule(value, localeData.dayPeriodRules, true); ok {
			return period
		}
		return fallback
	}
	if period, ok := selectDateTimeCoreDayPeriodRule(value, localeData.dayPeriodRules, false); ok {
		return period
	}
	return fallback
}

func selectDateTimeCoreDayPeriodRule(value time.Time, encodedRules string, exactOnly bool) (string, bool) {
	if encodedRules == "" {
		return "", false
	}
	minute := value.Hour()*60 + value.Minute()
	exactMinute := -1
	if value.Second() == 0 && value.Nanosecond() == 0 {
		exactMinute = minute
	}
	rangeMatch := ""
	for _, rawRule := range strings.Split(encodedRules, ";") {
		period, span, ok := strings.Cut(rawRule, "=")
		if !ok {
			continue
		}
		if startText, endText, ok := strings.Cut(span, "-"); ok {
			if !exactOnly {
				start, _ := strconv.Atoi(startText)
				end, _ := strconv.Atoi(endText)
				if rangeMatch == "" && minuteInDateTimeCoreDayPeriodRange(minute, start, end) {
					rangeMatch = period
				}
			}
			continue
		}
		at, _ := strconv.Atoi(span)
		if at == exactMinute {
			return period, true
		}
	}
	if exactOnly || rangeMatch == "" {
		return "", false
	}
	return rangeMatch, true
}

func minuteInDateTimeCoreDayPeriodRange(minute, start, end int) bool {
	if start <= end {
		return minute >= start && minute < end
	}
	return minute >= start || minute < end
}

func dateTimeCoreHour12(value time.Time) int {
	hour := value.Hour() % 12
	if hour == 0 {
		return 12
	}
	return hour
}

func dateTimeCoreFractionValue(value time.Time, localeData cldrDateTimeLocaleData, count int) string {
	milliseconds := fmt.Sprintf("%03d", value.Nanosecond()/1_000_000)
	return localizeDateTimeCoreDigits((milliseconds + "000000000")[:count], localeData.numberingSystemDigits)
}

func dateTimeCoreIntegerValue(value int, localeData cldrDateTimeLocaleData, count int) string {
	minimum := 0
	if count >= 2 {
		minimum = count
	}
	return dateTimeCoreIntegerText(value, localeData, minimum)
}

func dateTimeCoreIntegerText(value int, localeData cldrDateTimeLocaleData, minimumDigits int) string {
	text := strconv.Itoa(value)
	if value < 0 {
		text = strconv.Itoa(-value)
	}
	for len(text) < minimumDigits {
		text = "0" + text
	}
	if value < 0 {
		text = "-" + text
	}
	return localizeDateTimeCoreDigits(text, localeData.numberingSystemDigits)
}

func dateTimeCoreContextualName(source map[string]map[string]map[string]string, context, width, key string) string {
	contextData := source[context]
	if contextData == nil {
		contextData = source["format"]
	}
	if contextData == nil {
		contextData = source["stand-alone"]
	}
	if contextData == nil {
		return key
	}
	return dateTimeCoreNameByWidth(contextData, width, key)
}

func dateTimeCoreNameByWidth(source map[string]map[string]string, width, key string) string {
	if value := source[width][key]; value != "" {
		return value
	}
	for _, fallback := range []string{"abbreviated", "wide", "short", "narrow"} {
		if value := source[fallback][key]; value != "" {
			return value
		}
	}
	return key
}

func dateTimeCoreWidthForText(count int) string {
	if count == 4 {
		return "wide"
	}
	if count == 5 {
		return "narrow"
	}
	return "abbreviated"
}

func dateTimeCoreWidthForWeekday(count int) string {
	if count == 4 {
		return "wide"
	}
	if count == 5 {
		return "narrow"
	}
	if count >= 6 {
		return "short"
	}
	return "abbreviated"
}

func dateTimeCoreWidthForDayPeriod(count int) string {
	if count == 4 {
		return "wide"
	}
	if count >= 5 {
		return "narrow"
	}
	return "abbreviated"
}

func isDateTimeCoreASCIILetter(value rune) bool {
	return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
}

func dateTimeCoreCallStyle(call FunctionCall, optionName, legacyOptionName, fallback string, legacyTimePrecision bool) (string, error) {
	const absent = "\x00mf2-absent-date-time-style"
	shared, err := call.OptionValue("style", absent)
	if err != nil {
		return "", err
	}
	legacy, err := call.OptionValue(legacyOptionName, absent)
	if err != nil {
		return "", err
	}
	value, err := call.OptionValue(optionName, absent)
	if err != nil {
		return "", err
	}
	if value != absent {
		return dateTimeCoreStyleOption(value, optionName)
	}
	if legacy != absent {
		if legacyTimePrecision {
			return dateTimeCoreTimePrecisionStyleOption(legacy, legacyOptionName)
		}
		return dateTimeCoreStyleOption(legacy, legacyOptionName)
	}
	if shared != absent {
		return dateTimeCoreStyleOption(shared, "style")
	}
	value = fallback
	return dateTimeCoreStyleOption(value, optionName)
}

func callStringOption(call FunctionCall, optionName, fallback string) (string, error) {
	return call.OptionValue(optionName, fallback)
}

func dateTimeCoreCallOption(call FunctionCall, optionName, fallback string) (string, error) {
	const absent = "\x00mf2-absent-date-time-option"
	value, err := call.OptionValue(optionName, absent)
	if err != nil {
		return "", err
	}
	if value == absent {
		return fallback, nil
	}
	if value == "" {
		return "", badOption(optionName + " must not be empty.")
	}
	return value, nil
}

func dateTimeCoreStyleOption(value, name string) (string, error) {
	if len([]rune(value)) > dateTimeCoreMaxOptionLength {
		return "", badOption(name + " must not exceed 256 characters.")
	}
	switch value {
	case DateTimeCoreStyleFull, DateTimeCoreStyleLong, DateTimeCoreStyleMedium, DateTimeCoreStyleShort:
		return value, nil
	default:
		return "", badOption(name + " must be one of full, long, medium, short.")
	}
}

func dateTimeCoreTimeStyleOption(timeStyle, timePrecision, precision, style, fallback string) (string, error) {
	if timeStyle != "" {
		return dateTimeCoreStyleOption(timeStyle, "timeStyle")
	}
	if timePrecision != "" {
		return dateTimeCoreTimePrecisionStyleOption(timePrecision, "timePrecision")
	}
	if precision != "" {
		return dateTimeCoreTimePrecisionStyleOption(precision, "precision")
	}
	return dateTimeCoreStyleOption(defaultString(style, fallback), "timeStyle")
}

func dateTimeCoreTimePrecisionStyleOption(value, name string) (string, error) {
	if value == "second" {
		return DateTimeCoreStyleMedium, nil
	}
	return dateTimeCoreStyleOption(value, name)
}

func localizeDateTimeCoreDigits(value, digits string) string {
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

func firstString(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}
