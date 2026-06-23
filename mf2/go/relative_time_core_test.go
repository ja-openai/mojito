package mf2

import (
	"encoding/json"
	"math"
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestRelativeTimeCoreFixtures(t *testing.T) {
	data := readRelativeTimeCoreData(t)
	fixture := readFixture(t, "../conformance/fixtures/functions/relative-time-duration-v0.json")
	registry := RelativeTimeCoreFunctionRegistry(data)

	for _, raw := range arrayValue(fixture["cases"]) {
		item := asObject(raw)
		parse := ParseToModel(stringValue(item["source"]))
		if parse.HasDiagnostics {
			t.Fatalf("%s: parse diagnostics: %v", stringValue(item["label"]), parse.Diagnostics)
		}
		actual := FormatMessage(parse.Model, mapValue(item["arguments"]), Options{
			Locale:    stringValue(item["locale"]),
			Functions: registry,
		})
		if actual.Value != stringValue(item["expected"]) || actual.HasErrors() {
			t.Fatalf("%s: expected %q, got %q errors=%v", stringValue(item["label"]), stringValue(item["expected"]), actual.Value, actual.Errors)
		}
	}

	for _, raw := range arrayValue(fixture["errorCases"]) {
		item := asObject(raw)
		parse := ParseToModel(stringValue(item["source"]))
		if parse.HasDiagnostics {
			t.Fatalf("%s: parse diagnostics: %v", stringValue(item["label"]), parse.Diagnostics)
		}
		actual := FormatMessage(parse.Model, mapValue(item["arguments"]), Options{
			Locale:    stringValue(item["locale"]),
			Functions: registry,
		})
		expected := stringValue(asObject(item["expectedError"])["code"])
		assertErrorCodesExact(t, stringValue(item["label"])+": errors", actual.Errors, []string{expected})
	}

	for _, optionName := range []string{"style", "numeric", "policy", "unit"} {
		parse := ParseToModel("x {$r :relativeTime " + optionName + "=$missing}")
		if parse.HasDiagnostics {
			t.Fatalf("relative-time missing %s option parse diagnostics: %v", optionName, parse.Diagnostics)
		}
		actual := FormatMessage(parse.Model, map[string]any{"r": "1"}, Options{
			Locale:    "en",
			Functions: registry,
		})
		if actual.Value != "x {$r}" {
			t.Fatalf("relative-time missing %s option: expected fallback, got %q", optionName, actual.Value)
		}
		assertErrorCodesExact(t, "relative-time missing "+optionName+" option", actual.Errors, []string{"missing-argument"})
	}
}

func TestRelativeTimeCoreDirectAPI(t *testing.T) {
	data := readRelativeTimeCoreData(t)
	formatted, err := FormatRelativeTimeCore(3600, data, RelativeTimeCoreOptions{
		Locale:  "en",
		Style:   RelativeTimeCoreStyleNarrow,
		Numeric: RelativeTimeCoreNumericAlways,
		Policy:  RelativeTimeCorePolicyPrecise,
		Unit:    RelativeTimeCoreUnitAuto,
	})
	if err != nil {
		t.Fatal(err)
	}
	if formatted != "in 1h" {
		t.Fatalf("expected in 1h, got %q", formatted)
	}
	emptyLocale, err := FormatRelativeTimeCore(3600, data, RelativeTimeCoreOptions{
		Locale:  "",
		Style:   RelativeTimeCoreStyleNarrow,
		Numeric: RelativeTimeCoreNumericAlways,
		Policy:  RelativeTimeCorePolicyPrecise,
		Unit:    RelativeTimeCoreUnitAuto,
	})
	if err != nil {
		t.Fatal(err)
	}
	if emptyLocale != "in 1h" {
		t.Fatalf("empty locale expected in 1h, got %q", emptyLocale)
	}
	negativeZero, err := FormatRelativeTimeCore(math.Copysign(0, -1), data, RelativeTimeCoreOptions{
		Locale:  "en",
		Style:   RelativeTimeCoreStyleLong,
		Numeric: RelativeTimeCoreNumericAlways,
		Policy:  RelativeTimeCorePolicyPrecise,
		Unit:    RelativeTimeCoreUnitSecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	if negativeZero != "0 seconds ago" {
		t.Fatalf("expected 0 seconds ago, got %q", negativeZero)
	}
	afterTomorrow, err := FormatRelativeTimeCore(172800, data, RelativeTimeCoreOptions{
		Locale:  "fr",
		Style:   RelativeTimeCoreStyleLong,
		Numeric: RelativeTimeCoreNumericAuto,
		Policy:  RelativeTimeCorePolicyPrecise,
		Unit:    RelativeTimeCoreUnitDay,
	})
	if err != nil {
		t.Fatal(err)
	}
	if afterTomorrow != "après-demain" {
		t.Fatalf("expected après-demain, got %q", afterTomorrow)
	}

	formatter, err := NewRelativeTimeCoreFormatter(data)
	if err != nil {
		t.Fatal(err)
	}
	instanceFormatted, err := formatter.Format(60, RelativeTimeCoreOptions{})
	if err != nil {
		t.Fatal(err)
	}
	staticFormatted, err := FormatRelativeTimeCore(60, data, RelativeTimeCoreOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if instanceFormatted != staticFormatted {
		t.Fatalf("relative-time instance format: expected %q, got %q", staticFormatted, instanceFormatted)
	}
	instanceParts, err := formatter.FormatToParts(60, RelativeTimeCoreOptions{})
	if err != nil {
		t.Fatal(err)
	}
	staticParts, err := FormatRelativeTimeCoreToParts(60, data, RelativeTimeCoreOptions{})
	if err != nil {
		t.Fatal(err)
	}
	assertJSONEqual(t, "relative-time instance parts", staticParts, instanceParts)

	parts, err := FormatRelativeTimeCoreToParts(-86400, data, RelativeTimeCoreOptions{
		Locale:  "en",
		Style:   RelativeTimeCoreStyleLong,
		Numeric: RelativeTimeCoreNumericAuto,
		Unit:    RelativeTimeCoreUnitDay,
	})
	if err != nil {
		t.Fatal(err)
	}
	assertJSONEqual(t, "relative-time parts", []Part{{"type": "text", "value": "yesterday"}}, parts)

	_, err = NewRelativeTimeCoreFormatter(RelativeTimeCoreData{
		LocaleMap: map[string]string{"en": "rt"},
		PatternSets: []RelativeTimeCorePatternSet{{
			ID:   "rt",
			Data: map[string]map[string]RelativeTimeCoreUnitData{},
		}},
	})
	if code, ok := err.(Error); !ok || code.Code != "missing-locale-data" {
		t.Fatalf("empty relative-time pattern-set data: expected missing-locale-data, got %v", err)
	}
	_, err = NewRelativeTimeCoreFormatter(RelativeTimeCoreData{
		LocaleMap: map[string]string{"en": "rt"},
		PatternSets: []RelativeTimeCorePatternSet{{
			ID: "",
			Data: map[string]map[string]RelativeTimeCoreUnitData{
				"short": {"second": RelativeTimeCoreUnitData{Future: map[string]string{"other": "in {0} sec."}}},
			},
		}},
	})
	if code, ok := err.(Error); !ok || code.Code != "missing-locale-data" {
		t.Fatalf("empty relative-time pattern-set id: expected missing-locale-data, got %v", err)
	}
}

func TestRelativeTimeCoreIntlReferences(t *testing.T) {
	data := readRelativeTimeCoreData(t)
	referenceCases := []any{
		map[string]any{"locale": "en", "style": "long", "numeric": "auto", "unit": "day", "value": -1.0, "seconds": -86400.0},
		map[string]any{"locale": "en", "style": "long", "numeric": "always", "unit": "day", "value": 1.0, "seconds": 86400.0},
		map[string]any{"locale": "ja", "style": "narrow", "numeric": "always", "unit": "minute", "value": 3.0, "seconds": 180.0},
		map[string]any{"locale": "en", "style": "narrow", "numeric": "always", "unit": "minute", "value": -1.0, "seconds": -60.0},
		map[string]any{"locale": "en", "style": "long", "numeric": "always", "unit": "second", "value": math.Copysign(0, -1), "seconds": math.Copysign(0, -1)},
		map[string]any{"locale": "fr", "style": "long", "numeric": "auto", "unit": "day", "value": 2.0, "seconds": 172800.0},
	}
	references := nodeIntlRelativeTimeOutputs(t, referenceCases)
	for index, raw := range referenceCases {
		item := asObject(raw)
		actual, err := FormatRelativeTimeCore(item["seconds"], data, RelativeTimeCoreOptions{
			Locale:  stringValue(item["locale"]),
			Style:   stringValue(item["style"]),
			Numeric: stringValue(item["numeric"]),
			Policy:  RelativeTimeCorePolicyPrecise,
			Unit:    stringValue(item["unit"]),
		})
		if err != nil {
			t.Fatalf("Intl relative-time reference %d: unexpected error: %v", index, err)
		}
		if actual != references[index] {
			t.Fatalf("Intl relative-time reference %d: expected %q, got %q", index, references[index], actual)
		}
	}
}

func readRelativeTimeCoreData(t *testing.T) RelativeTimeCoreData {
	t.Helper()
	payload, err := os.ReadFile("../cldr/generated/relative-time/all/relative_time.json")
	if err != nil {
		t.Fatal(err)
	}
	var data RelativeTimeCoreData
	if err := json.Unmarshal(payload, &data); err != nil {
		t.Fatal(err)
	}
	return data
}

func nodeIntlRelativeTimeOutputs(t *testing.T, cases []any) []string {
	t.Helper()
	script := `
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.RelativeTimeFormat(item.locale, { style: item.style, numeric: item.numeric }).format(item.value, item.unit)
)));
`
	payload, err := json.Marshal(cases)
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command("node", "-e", script)
	command.Stdin = strings.NewReader(string(payload))
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("node Intl relative-time reference failed: %v\n%s", err, output)
	}
	var values []string
	if err := json.Unmarshal(output, &values); err != nil {
		t.Fatalf("node Intl relative-time reference returned invalid JSON: %v\n%s", err, output)
	}
	return values
}
