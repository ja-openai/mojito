package mf2

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

type dateTimeCoreStringerOperand struct{}

func (dateTimeCoreStringerOperand) String() string {
	return "2026-05-21T14:30:15Z"
}

func TestSourceToModelFixtures(t *testing.T) {
	for _, path := range fixturePaths(t, "../conformance/fixtures/source-to-model") {
		fixture := readFixture(t, path)
		source := fixture["source"].(string)
		parse := ParseToModel(source)
		if parse.HasDiagnostics {
			t.Fatalf("%s: unexpected diagnostics: %#v", filepath.Base(path), parse.Diagnostics)
		}
		assertJSONEqual(t, filepath.Base(path)+": model", fixture["expectedModel"], parse.Model)

		model := parse.Model
		for _, rawCase := range arrayValue(fixture["formatCases"]) {
			item := asObject(rawCase)
			actual := FormatMessage(model, mapValue(item["arguments"]), Options{
				Locale:        stringValue(item["locale"]),
				BidiIsolation: stringValue(item["bidiIsolation"]),
			})
			if len(actual.Errors) > 0 {
				t.Fatalf("%s: format errors: %v", filepath.Base(path), actual.Errors)
			}
			if actual.Value != stringValue(item["expected"]) {
				t.Fatalf("%s: expected format %q, got %q", filepath.Base(path), stringValue(item["expected"]), actual.Value)
			}
		}
		for _, rawCase := range arrayValue(fixture["partsCases"]) {
			item := asObject(rawCase)
			actual := FormatMessageToParts(model, mapValue(item["arguments"]), Options{
				Locale:        stringValue(item["locale"]),
				BidiIsolation: stringValue(item["bidiIsolation"]),
			})
			if len(actual.Errors) > 0 {
				t.Fatalf("%s: parts errors: %v", filepath.Base(path), actual.Errors)
			}
			assertJSONEqual(t, filepath.Base(path)+": parts", item["expected"], actual.Parts)
		}
		for _, rawCase := range arrayValue(fixture["fallbackCases"]) {
			item := asObject(rawCase)
			actual := FormatMessage(model, mapValue(item["arguments"]), Options{
				Locale:        stringValue(item["locale"]),
				BidiIsolation: stringValue(item["bidiIsolation"]),
			})
			if actual.Value != stringValue(item["expected"]) {
				t.Fatalf("%s: expected fallback %q, got %q", filepath.Base(path), stringValue(item["expected"]), actual.Value)
			}
			assertErrorCodes(t, filepath.Base(path)+": fallback errors", actual.Errors, item)
		}
		for _, rawCase := range arrayValue(fixture["fallbackPartsCases"]) {
			item := asObject(rawCase)
			actual := FormatMessageToParts(model, mapValue(item["arguments"]), Options{Locale: stringValue(item["locale"])})
			assertJSONEqual(t, filepath.Base(path)+": fallback parts", item["expected"], actual.Parts)
			assertErrorCodes(t, filepath.Base(path)+": fallback parts errors", actual.Errors, item)
		}
	}
}

func TestInvalidSourceFixtures(t *testing.T) {
	for _, path := range fixturePaths(t, "../conformance/fixtures/invalid-source") {
		fixture := readFixture(t, path)
		parse := ParseToModel(fixture["source"].(string))
		if !parse.HasDiagnostics {
			t.Fatalf("%s: expected diagnostics", filepath.Base(path))
		}
		expected := expectedCodes(fixture["expectedDiagnostics"])
		actual := make([]string, 0, len(parse.Diagnostics))
		for _, diagnostic := range parse.Diagnostics {
			actual = append(actual, diagnostic.Code)
		}
		if !containsAll(actual, expected) {
			t.Fatalf("%s: expected diagnostics %v, got %v", filepath.Base(path), expected, actual)
		}
	}
}

func TestFormatErrorFixtures(t *testing.T) {
	for _, path := range fixturePaths(t, "../conformance/fixtures/format-errors") {
		fixture := readFixture(t, path)
		expected := stringValue(asObject(fixture["expectedError"])["code"])
		actual := FormatMessage(Model(mapValue(fixture["model"])), mapValue(fixture["arguments"]), Options{Locale: stringValue(fixture["locale"])})
		if !hasErrorCode(actual.Errors, expected) {
			t.Fatalf("%s: expected error %s, got %v", filepath.Base(path), expected, actual.Errors)
		}
	}
}

func TestUnsupportedDefaultFunctionRecoversWithDiagnostic(t *testing.T) {
	parse := ParseToModel("Total: {$amount :currency currency=USD}")
	if parse.HasDiagnostics {
		t.Fatalf("unexpected diagnostics: %#v", parse.Diagnostics)
	}
	actual := FormatMessage(parse.Model, map[string]any{"amount": 42}, Options{})
	if actual.Value != "Total: {$amount}" {
		t.Fatalf("expected fallback output, got %q", actual.Value)
	}
	if !hasErrorCode(actual.Errors, "unknown-function") {
		t.Fatalf("expected default registry to reject :currency, got %v", actual.Errors)
	}
}

func TestRecoveryCallbacksHandleEmptyAndDeclinedValues(t *testing.T) {
	parse := ParseToModel("Hello {$name}")
	if parse.HasDiagnostics {
		t.Fatalf("unexpected diagnostics: %#v", parse.Diagnostics)
	}
	emptyRecovery := func(context RecoveryContext) (string, bool) {
		return "", true
	}
	emptyFormatted := FormatMessage(parse.Model, nil, Options{OnMissingArgument: emptyRecovery})
	if emptyFormatted.Value != "Hello " {
		t.Fatalf("expected empty replacement, got %q", emptyFormatted.Value)
	}
	assertErrorCodesExact(t, "empty missing errors", emptyFormatted.Errors, []string{"unresolved-variable"})

	emptyParts := FormatMessageToParts(parse.Model, nil, Options{OnMissingArgument: emptyRecovery})
	assertJSONEqual(t, "empty missing parts", []Part{
		{"type": "text", "value": "Hello "},
		{"type": "fallback", "source": "$name", "value": ""},
	}, emptyParts.Parts)

	declinedRecovery := func(context RecoveryContext) (string, bool) {
		return "", false
	}
	declinedFormatted := FormatMessage(parse.Model, nil, Options{OnMissingArgument: declinedRecovery})
	if declinedFormatted.Value != "Hello {$name}" {
		t.Fatalf("expected visible fallback after declined recovery, got %q", declinedFormatted.Value)
	}

	integer := ParseToModel("Hello {$name :integer}")
	if integer.HasDiagnostics {
		t.Fatalf("unexpected integer diagnostics: %#v", integer.Diagnostics)
	}
	emptyFormatError := FormatMessage(
		integer.Model,
		map[string]any{"name": "abc"},
		Options{OnFormatError: emptyRecovery},
	)
	if emptyFormatError.Value != "Hello " {
		t.Fatalf("expected empty format-error replacement, got %q", emptyFormatError.Value)
	}
	assertErrorCodesExact(t, "empty format-error errors", emptyFormatError.Errors, []string{"bad-operand"})

	emptyFormatParts := FormatMessageToParts(
		integer.Model,
		map[string]any{"name": "abc"},
		Options{OnFormatError: emptyRecovery},
	)
	assertJSONEqual(t, "empty format-error parts", []Part{
		{"type": "text", "value": "Hello "},
		{"type": "fallback", "source": "$name", "value": ""},
	}, emptyFormatParts.Parts)
}

func TestLocaleKeyFixtures(t *testing.T) {
	fixture := readFixture(t, "../conformance/fixtures/locale-key/cases.json")
	checked := 0
	for _, raw := range arrayValue(fixture["canonical"]) {
		item := asObject(raw)
		actual := canonicalLocaleKey(stringValue(item["source"]))
		if actual != stringValue(item["expected"]) {
			t.Fatalf("canonical locale: expected %s, got %s", stringValue(item["expected"]), actual)
		}
		checked++
	}
	for _, raw := range arrayValue(fixture["lookupChains"]) {
		item := asObject(raw)
		assertJSONEqual(t, "lookup chain", item["expected"], localeLookupChain(stringValue(item["source"])))
		checked++
	}
	for _, raw := range arrayValue(fixture["featureLookupChains"]) {
		item := asObject(raw)
		parents := map[string]string{}
		for key, value := range asObject(item["parents"]) {
			parents[key] = stringValue(value)
		}
		assertJSONEqual(t, "feature lookup chain", item["expected"], featureLookupChain(stringValue(item["source"]), parents))
		checked++
	}
	if checked == 0 {
		t.Fatal("locale-key fixture did not contain any cases")
	}
}

func TestNumberCoreFixtures(t *testing.T) {
	fixture := readFixture(t, "../conformance/fixtures/number-core/cases.json")
	for _, raw := range arrayValue(fixture["formatCases"]) {
		item := asObject(raw)
		actual, err := FormatNumberCore(
			item["value"],
			numberCoreOptionsFromFixture(stringValue(item["locale"]), item["options"]),
		)
		if err != nil {
			t.Fatalf("%s: unexpected error: %v", stringValue(item["name"]), err)
		}
		if actual != stringValue(item["expected"]) {
			t.Fatalf("%s: expected %q, got %q", stringValue(item["name"]), stringValue(item["expected"]), actual)
		}
	}

	referenceCases := arrayValue(fixture["intlReferenceCases"])
	references := nodeIntlNumberOutputs(t, referenceCases)
	for index, raw := range referenceCases {
		item := asObject(raw)
		actual, err := FormatNumberCore(
			item["value"],
			numberCoreOptionsFromFixture(stringValue(item["locale"]), item["options"]),
		)
		if err != nil {
			t.Fatalf("Intl number reference %d: unexpected error: %v", index, err)
		}
		if actual != references[index] {
			t.Fatalf("Intl number reference %d: expected %q, got %q", index, references[index], actual)
		}
	}

	for _, raw := range arrayValue(fixture["errorCases"]) {
		item := asObject(raw)
		_, err := FormatNumberCore(
			item["value"],
			numberCoreOptionsFromFixture(stringValue(item["locale"]), item["options"]),
		)
		if err == nil {
			t.Fatalf("%s: expected error", stringValue(item["name"]))
		}
		if asMF2Error(err).Code != stringValue(item["expectedError"]) {
			t.Fatalf("%s: expected error %q, got %q", stringValue(item["name"]), stringValue(item["expectedError"]), asMF2Error(err).Code)
		}
	}

	useGrouping := false
	for _, item := range []struct {
		name     string
		value    any
		expected string
	}{
		{"direct int64 over safe integer", int64(9007199254740993), "9007199254740993"},
		{"direct string over safe integer", "9007199254740993", "9007199254740993"},
		{"direct max int64", int64(9223372036854775807), "9223372036854775807"},
		{"direct max int64 string", "9223372036854775807", "9223372036854775807"},
		{"direct max uint64", uint64(18446744073709551615), "18446744073709551615"},
	} {
		actual, err := FormatNumberCore(item.value, NumberCoreOptions{
			Locale:      "en-US",
			UseGrouping: &useGrouping,
		})
		if err != nil {
			t.Fatalf("%s: unexpected error: %v", item.name, err)
		}
		if actual != item.expected {
			t.Fatalf("%s: expected %q, got %q", item.name, item.expected, actual)
		}
	}

	formattedNumber, err := FormatNumberCore(1234.5, NumberCoreOptions{Locale: "en-US"})
	if err != nil {
		t.Fatalf("number-core direct parts format: unexpected error: %v", err)
	}
	numberParts, err := FormatNumberCoreToParts(1234.5, NumberCoreOptions{Locale: "en-US"})
	if err != nil {
		t.Fatalf("number-core direct parts: unexpected error: %v", err)
	}
	if expected := []Part{{"type": "text", "value": formattedNumber}}; !reflect.DeepEqual(numberParts, expected) {
		t.Fatalf("number-core direct parts: expected %#v, got %#v", expected, numberParts)
	}

	numberCoreRegistry := NumberCoreFunctionRegistry()
	currency := ParseToModel("Total: {$amount :currency currency=USD}")
	if currency.HasDiagnostics {
		t.Fatalf("number-core registry parse diagnostics: %v", currency.Diagnostics)
	}
	currencyResult := FormatMessage(currency.Model, map[string]any{"amount": 1234.5}, Options{
		Locale:    "en-US",
		Functions: numberCoreRegistry,
	})
	if currencyResult.Value != "Total: $1,234.50" || currencyResult.HasErrors() {
		t.Fatalf("number-core registry currency: got %q errors=%v", currencyResult.Value, currencyResult.Errors)
	}
	selector := ParseToModel(".input {$count :number}\n.match $count\none {{one}}\n* {{other}}")
	if selector.HasDiagnostics {
		t.Fatalf("number-core selector parse diagnostics: %v", selector.Diagnostics)
	}
	selectorResult := FormatMessage(selector.Model, map[string]any{"count": 1}, Options{
		Locale:    "en",
		Functions: numberCoreRegistry,
	})
	if selectorResult.Value != "one" || selectorResult.HasErrors() {
		t.Fatalf("number-core registry selector: got %q errors=%v", selectorResult.Value, selectorResult.Errors)
	}

	for _, raw := range arrayValue(fixture["registryCases"]) {
		item := asObject(raw)
		parsed := ParseToModel(stringValue(item["source"]))
		if parsed.HasDiagnostics {
			t.Fatalf("%s: parse diagnostics: %v", stringValue(item["name"]), parsed.Diagnostics)
		}
		result := FormatMessage(parsed.Model, asObject(item["arguments"]), Options{
			Locale:    stringValue(item["locale"]),
			Functions: numberCoreRegistry,
		})
		if result.Value != stringValue(item["expected"]) || result.HasErrors() {
			t.Fatalf("%s: expected %q, got %q errors=%v", stringValue(item["name"]), stringValue(item["expected"]), result.Value, result.Errors)
		}
	}
	for _, raw := range arrayValue(fixture["registryErrorCases"]) {
		item := asObject(raw)
		parsed := ParseToModel(stringValue(item["source"]))
		if parsed.HasDiagnostics {
			t.Fatalf("%s: parse diagnostics: %v", stringValue(item["name"]), parsed.Diagnostics)
		}
		result := FormatMessage(parsed.Model, asObject(item["arguments"]), Options{
			Locale:    stringValue(item["locale"]),
			Functions: numberCoreRegistry,
		})
		assertErrorCodesExact(t, stringValue(item["name"]), result.Errors, stringArray(item["expectedErrors"]))
	}
}

func TestDateTimeCoreFixtures(t *testing.T) {
	fixture := readFixture(t, "../conformance/fixtures/date-time-core/cases.json")
	for _, raw := range arrayValue(fixture["formatCases"]) {
		item := asObject(raw)
		actual, err := formatDateTimeCoreFixtureItem(item)
		if err != nil {
			t.Fatalf("%s: unexpected error: %v", stringValue(item["name"]), err)
		}
		if actual != stringValue(item["expected"]) {
			t.Fatalf("%s: expected %q, got %q", stringValue(item["name"]), stringValue(item["expected"]), actual)
		}
	}
	for _, raw := range arrayValue(fixture["numericTimestampCases"]) {
		item := asObject(raw)
		actual, err := formatDateTimeCoreFixtureItem(item)
		if err != nil {
			t.Fatalf("%s: unexpected error: %v", stringValue(item["name"]), err)
		}
		if actual != stringValue(item["expected"]) {
			t.Fatalf("%s: expected %q, got %q", stringValue(item["name"]), stringValue(item["expected"]), actual)
		}
	}

	referenceCases := arrayValue(fixture["intlReferenceCases"])
	references := nodeIntlDateTimeOutputs(t, referenceCases)
	for index, raw := range referenceCases {
		item := asObject(raw)
		actual, err := formatDateTimeCoreFixtureItem(item)
		if err != nil {
			t.Fatalf("Intl date/time reference %d: unexpected error: %v", index, err)
		}
		if actual != references[index] {
			t.Fatalf("Intl date/time reference %d: expected %q, got %q", index, references[index], actual)
		}
	}

	semanticReferenceCases := arrayValue(fixture["semanticStyleReferenceCases"])
	semanticReferences := nodeIntlDateTimeOutputs(t, dateTimeCoreReferenceItems(semanticReferenceCases))
	for index, raw := range semanticReferenceCases {
		item := asObject(raw)
		actual, err := formatDateTimeCoreFixtureItem(item)
		if err != nil {
			t.Fatalf("semantic style reference %d: unexpected error: %v", index, err)
		}
		if actual != semanticReferences[index] {
			t.Fatalf("%s: expected %q, got %q", stringValue(item["name"]), semanticReferences[index], actual)
		}
	}

	for _, raw := range arrayValue(fixture["errorCases"]) {
		item := asObject(raw)
		_, err := formatDateTimeCoreFixtureItem(item)
		if err == nil {
			t.Fatalf("%s: expected error", stringValue(item["name"]))
		}
		if asMF2Error(err).Code != stringValue(item["expectedError"]) {
			t.Fatalf("%s: expected error %q, got %q", stringValue(item["name"]), stringValue(item["expectedError"]), asMF2Error(err).Code)
		}
	}

	value := "2026-05-21T14:30:15Z"
	for _, item := range []struct {
		name   string
		format func(any, DateTimeCoreOptions) (string, error)
		parts  func(any, DateTimeCoreOptions) ([]Part, error)
	}{
		{"date", FormatDateCore, FormatDateCoreToParts},
		{"time", FormatTimeCore, FormatTimeCoreToParts},
		{"datetime", FormatDateTimeCore, FormatDateTimeCoreToParts},
	} {
		formatted, err := item.format(value, DateTimeCoreOptions{Locale: "en-US"})
		if err != nil {
			t.Fatalf("date-time-core %s direct parts format: unexpected error: %v", item.name, err)
		}
		parts, err := item.parts(value, DateTimeCoreOptions{Locale: "en-US"})
		if err != nil {
			t.Fatalf("date-time-core %s direct parts: unexpected error: %v", item.name, err)
		}
		if expected := []Part{{"type": "text", "value": formatted}}; !reflect.DeepEqual(parts, expected) {
			t.Fatalf("date-time-core %s direct parts: expected %#v, got %#v", item.name, expected, parts)
		}
	}
	if _, err := FormatDateTimeCore(dateTimeCoreStringerOperand{}, DateTimeCoreOptions{}); asMF2Error(err).Code != "bad-operand" {
		t.Fatalf("date-time-core direct Stringer operand: expected bad-operand, got %v", err)
	}
	timestampOptions := DateTimeCoreOptions{Locale: "en-US", DateStyle: DateTimeCoreStyleMedium, TimeStyle: DateTimeCoreStyleMedium}
	expectedTimestamp, err := FormatDateTimeCore(int(0), timestampOptions)
	if err != nil {
		t.Fatalf("date-time-core direct timestamp baseline: unexpected error: %v", err)
	}
	for _, item := range []struct {
		name  string
		value any
	}{
		{"int8", int8(0)},
		{"int16", int16(0)},
		{"int32", int32(0)},
		{"uint", uint(0)},
		{"uint8", uint8(0)},
		{"uint16", uint16(0)},
		{"uint32", uint32(0)},
		{"uint64", uint64(0)},
		{"float32", float32(0)},
	} {
		actual, err := FormatDateTimeCore(item.value, timestampOptions)
		if err != nil {
			t.Fatalf("date-time-core direct %s timestamp: unexpected error: %v", item.name, err)
		}
		if actual != expectedTimestamp {
			t.Fatalf("date-time-core direct %s timestamp: expected %q, got %q", item.name, expectedTimestamp, actual)
		}
	}
	if _, err := FormatDateTimeCore(uint64(dateTimeCoreMaxTimestampMillis)+1, timestampOptions); asMF2Error(err).Code != "bad-operand" {
		t.Fatalf("date-time-core direct uint64 timestamp bound: expected bad-operand, got %v", err)
	}

	registry := DateTimeCoreFunctionRegistry()
	for _, raw := range arrayValue(fixture["registryFormatCases"]) {
		item := asObject(raw)
		parsed := ParseToModel(stringValue(item["source"]))
		if parsed.HasDiagnostics {
			t.Fatalf("%s: date-time-core registry parse diagnostics: %v", stringValue(item["name"]), parsed.Diagnostics)
		}
		result := FormatMessage(parsed.Model, mapValue(item["arguments"]), Options{
			Locale:    stringValue(item["locale"]),
			Functions: registry,
		})
		if result.Value != stringValue(item["expected"]) || result.HasErrors() {
			t.Fatalf("%s: expected %q, got %q errors=%v", stringValue(item["name"]), stringValue(item["expected"]), result.Value, result.Errors)
		}
	}
	objectResult := FormatMessage(ParseToModel("At {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}").Model, map[string]any{
		"instant": dateTimeCoreStringerOperand{},
	}, Options{Locale: "en-US", Functions: registry})
	assertErrorCodesExact(t, "date-time-core registry Stringer operand errors", objectResult.Errors, []string{"bad-operand"})
	for _, raw := range arrayValue(fixture["registryErrorCases"]) {
		item := asObject(raw)
		parsed := ParseToModel(stringValue(item["source"]))
		if parsed.HasDiagnostics {
			t.Fatalf("%s: date-time-core registry error parse diagnostics: %v", stringValue(item["name"]), parsed.Diagnostics)
		}
		result := FormatMessage(parsed.Model, mapValue(item["arguments"]), Options{
			Locale:    stringValue(item["locale"]),
			Functions: registry,
		})
		assertErrorCodesExact(t, stringValue(item["name"]), result.Errors, stringArray(item["expectedErrors"]))
	}
	dateMessage := ParseToModel("At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}")
	if dateMessage.HasDiagnostics {
		t.Fatalf("date-time-core registry parse diagnostics: %v", dateMessage.Diagnostics)
	}
	dateResult := FormatMessage(dateMessage.Model, map[string]any{"instant": "2026-05-21T14:30:15Z"}, Options{
		Locale:    "de-DE",
		Functions: registry,
	})
	if dateResult.Value != "At Donnerstag, 21. Mai 2026 um 14:30:15" || dateResult.HasErrors() {
		t.Fatalf("date-time-core registry datetime: got %q errors=%v", dateResult.Value, dateResult.Errors)
	}
	stringMessage := ParseToModel("Hello {$name :string}")
	if stringMessage.HasDiagnostics {
		t.Fatalf("date-time-core string parse diagnostics: %v", stringMessage.Diagnostics)
	}
	stringResult := FormatMessage(stringMessage.Model, map[string]any{"name": "Mojito"}, Options{Functions: registry})
	if stringResult.Value != "Hello Mojito" || stringResult.HasErrors() {
		t.Fatalf("date-time-core registry string: got %q errors=%v", stringResult.Value, stringResult.Errors)
	}
	for _, optionName := range []string{"style", "dateStyle", "timeStyle", "timeZone", "calendar", "skeleton", "hourCycle"} {
		message := ParseToModel("At {$instant :datetime " + optionName + "=$missing}")
		if message.HasDiagnostics {
			t.Fatalf("date-time-core missing %s option parse diagnostics: %v", optionName, message.Diagnostics)
		}
		result := FormatMessage(message.Model, map[string]any{"instant": "2026-05-21T14:30:15Z"}, Options{
			Locale:    "en-US",
			Functions: registry,
		})
		if result.Value != "At {$instant}" {
			t.Fatalf("date-time-core missing %s option: expected fallback, got %q", optionName, result.Value)
		}
		assertErrorCodesExact(t, "date-time-core missing "+optionName+" option", result.Errors, []string{"missing-argument"})
	}
}

func assertErrorCodesExact(t *testing.T, label string, actualErrors []Error, expected []string) {
	t.Helper()
	actual := make([]string, 0, len(actualErrors))
	for _, err := range actualErrors {
		actual = append(actual, err.Code)
	}
	if !reflect.DeepEqual(actual, expected) {
		t.Fatalf("%s: expected %v, got %v", label, expected, actual)
	}
}

func numberCoreOptionsFromFixture(locale string, raw any) NumberCoreOptions {
	options := asObject(raw)
	result := NumberCoreOptions{
		Locale:          locale,
		Style:           stringValue(options["style"]),
		Currency:        stringValue(options["currency"]),
		CurrencyDisplay: stringValue(options["currencyDisplay"]),
		SignDisplay:     stringValue(options["signDisplay"]),
	}
	if value, ok := options["useGrouping"].(bool); ok {
		result.UseGrouping = &value
	}
	result.MinimumFractionDigits = intPointerFromFixture(options["minimumFractionDigits"])
	result.MaximumFractionDigits = intPointerFromFixture(options["maximumFractionDigits"])
	return result
}

func dateTimeCoreOptionsFromFixture(locale string, raw any) DateTimeCoreOptions {
	options := asObject(raw)
	return DateTimeCoreOptions{
		Locale:        locale,
		Style:         stringValue(options["style"]),
		DateStyle:     stringValue(options["dateStyle"]),
		TimeStyle:     stringValue(options["timeStyle"]),
		Length:        stringValue(options["length"]),
		Precision:     stringValue(options["precision"]),
		DateLength:    stringValue(options["dateLength"]),
		TimePrecision: stringValue(options["timePrecision"]),
		Skeleton:      stringValue(options["skeleton"]),
		HourCycle:     stringValue(options["hourCycle"]),
		TimeZone:      stringValue(options["timeZone"]),
		Calendar:      stringValue(options["calendar"]),
	}
}

func formatDateTimeCoreFixtureItem(item map[string]any) (string, error) {
	options := dateTimeCoreOptionsFromFixture(stringValue(item["locale"]), item["options"])
	switch stringValue(item["kind"]) {
	case "date":
		return FormatDateCore(item["value"], options)
	case "time":
		return FormatTimeCore(item["value"], options)
	case "datetime":
		return FormatDateTimeCore(item["value"], options)
	default:
		return "", mf2Error("bad-option", "Unsupported date/time core fixture kind.")
	}
}

func dateTimeCoreReferenceItems(cases []any) []any {
	references := make([]any, 0, len(cases))
	for _, raw := range cases {
		item := asObject(raw)
		reference := map[string]any{
			"kind":    item["kind"],
			"locale":  item["locale"],
			"value":   item["value"],
			"options": item["referenceOptions"],
		}
		references = append(references, reference)
	}
	return references
}

func intPointerFromFixture(value any) *int {
	if value == nil {
		return nil
	}
	switch typed := value.(type) {
	case float64:
		integer := int(typed)
		return &integer
	case int:
		integer := typed
		return &integer
	default:
		return nil
	}
}

func nodeIntlNumberOutputs(t *testing.T, cases []any) []string {
	t.Helper()
	script := `
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
function intlOptions(options) {
  if (options.style === "number") return {};
  if (options.style === "percent") return { style: "percent" };
  if (options.style === "currency") return { style: "currency", currency: options.currency };
  throw new Error("Unsupported Intl reference style: " + options.style);
}
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.NumberFormat(item.locale, intlOptions(item.options || {})).format(item.value)
)));
`
	return nodeJSONOutputs(t, cases, script)
}

func nodeIntlDateTimeOutputs(t *testing.T, cases []any) []string {
	t.Helper()
	script := `
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.DateTimeFormat(item.locale, { timeZone: "UTC", ...(item.options || {}) }).format(new Date(item.value))
)));
`
	return nodeJSONOutputs(t, cases, script)
}

func nodeJSONOutputs(t *testing.T, cases []any, script string) []string {
	t.Helper()
	payload, err := json.Marshal(cases)
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command("node", "-e", script)
	command.Stdin = strings.NewReader(string(payload))
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("node Intl reference failed: %v\n%s", err, output)
	}
	var values []string
	if err := json.Unmarshal(output, &values); err != nil {
		t.Fatalf("node Intl reference returned invalid JSON: %v\n%s", err, output)
	}
	return values
}

func fixturePaths(t *testing.T, root string) []string {
	t.Helper()
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	var paths []string
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			paths = append(paths, filepath.Join(root, entry.Name()))
		}
	}
	sort.Strings(paths)
	return paths
}

func readFixture(t *testing.T, path string) map[string]any {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var fixture map[string]any
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func assertJSONEqual(t *testing.T, label string, expected any, actual any) {
	t.Helper()
	expectedNormalized := normalizeJSON(t, expected)
	actualNormalized := normalizeJSON(t, actual)
	if !reflect.DeepEqual(expectedNormalized, actualNormalized) {
		expectedJSON, _ := json.MarshalIndent(expectedNormalized, "", "  ")
		actualJSON, _ := json.MarshalIndent(actualNormalized, "", "  ")
		t.Fatalf("%s mismatch\nexpected: %s\nactual: %s", label, expectedJSON, actualJSON)
	}
}

func normalizeJSON(t *testing.T, value any) any {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var normalized any
	if err := json.Unmarshal(data, &normalized); err != nil {
		t.Fatal(err)
	}
	return normalized
}

func assertErrorCodes(t *testing.T, label string, actual []Error, item map[string]any) {
	t.Helper()
	expected := expectedCodes(item["expectedErrors"])
	actualCodes := make([]string, 0, len(actual))
	for _, err := range actual {
		actualCodes = append(actualCodes, err.Code)
	}
	if !containsAll(actualCodes, expected) {
		t.Fatalf("%s: expected error codes %v, got %v", label, expected, actualCodes)
	}
}

func expectedCodes(raw any) []string {
	var codes []string
	for _, item := range arrayValue(raw) {
		code := stringValue(asObject(item)["code"])
		if code == "" {
			code = stringValue(asObject(item)["type"])
		}
		codes = append(codes, code)
	}
	return codes
}

func stringArray(raw any) []string {
	var values []string
	for _, item := range arrayValue(raw) {
		values = append(values, stringValue(item))
	}
	return values
}

func containsAll(actual []string, expected []string) bool {
	seen := map[string]int{}
	for _, code := range actual {
		seen[code]++
	}
	for _, code := range expected {
		if seen[code] == 0 {
			return false
		}
		seen[code]--
	}
	return true
}

func hasErrorCode(actual []Error, expected string) bool {
	for _, err := range actual {
		if err.Code == expected {
			return true
		}
	}
	return false
}

func arrayValue(value any) []any {
	if value == nil {
		return nil
	}
	if array, ok := value.([]any); ok {
		return array
	}
	return nil
}

func mapValue(value any) map[string]any {
	if value == nil {
		return nil
	}
	if object, ok := value.(map[string]any); ok {
		return object
	}
	return nil
}

func stringValue(value any) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	return valueToString(value)
}
