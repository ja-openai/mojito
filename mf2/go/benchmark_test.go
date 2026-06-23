package mf2

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func BenchmarkFormatSharedFixtures(b *testing.B) {
	var cases []struct {
		model     Model
		arguments map[string]any
		locale    string
	}
	for _, path := range benchmarkFixturePaths(b, "../conformance/fixtures/source-to-model") {
		fixture := readBenchmarkFixture(path)
		parse := ParseToModel(fixture["source"].(string))
		if parse.HasDiagnostics {
			continue
		}
		for _, rawCase := range arrayValue(fixture["formatCases"]) {
			item := asObject(rawCase)
			cases = append(cases, struct {
				model     Model
				arguments map[string]any
				locale    string
			}{parse.Model, mapValue(item["arguments"]), stringValue(item["locale"])})
		}
	}
	if len(cases) == 0 {
		b.Fatal("no format benchmark cases")
	}
	b.ReportAllocs()
	checksum := 0
	for index := 0; index < b.N; index++ {
		item := cases[index%len(cases)]
		output := FormatMessage(item.model, item.arguments, Options{Locale: item.locale})
		if output.HasErrors() {
			b.Fatal(output.Errors)
		}
		checksum += len(output.Value)
	}
	if checksum == 0 {
		b.Fatal("empty checksum")
	}
}

func BenchmarkParseSharedFixtures(b *testing.B) {
	var sources []string
	for _, path := range benchmarkFixturePaths(b, "../conformance/fixtures/source-to-model") {
		fixture := readBenchmarkFixture(path)
		sources = append(sources, fixture["source"].(string))
	}
	if len(sources) == 0 {
		b.Fatal("no parse benchmark sources")
	}
	b.ReportAllocs()
	checksum := 0
	for index := 0; index < b.N; index++ {
		result := ParseToModel(sources[index%len(sources)])
		if result.HasDiagnostics {
			b.Fatal(result.Diagnostics)
		}
		checksum += len(arrayField(map[string]any(result.Model), "declarations"))
	}
	if checksum < 0 {
		b.Fatal("unreachable")
	}
}

func BenchmarkNumberCoreFixtures(b *testing.B) {
	fixture := readBenchmarkFixture("../conformance/fixtures/number-core/cases.json")
	var cases []struct {
		value   any
		options NumberCoreOptions
	}
	for _, rawCase := range arrayValue(fixture["formatCases"]) {
		item := asObject(rawCase)
		cases = append(cases, struct {
			value   any
			options NumberCoreOptions
		}{
			value:   item["value"],
			options: numberCoreOptionsFromFixture(stringValue(item["locale"]), item["options"]),
		})
	}
	if len(cases) == 0 {
		b.Fatal("no number-core benchmark cases")
	}
	b.ReportAllocs()
	checksum := 0
	for index := 0; index < b.N; index++ {
		item := cases[index%len(cases)]
		output, err := FormatNumberCore(item.value, item.options)
		if err != nil {
			b.Fatal(err)
		}
		checksum += len(output)
	}
	if checksum == 0 {
		b.Fatal("empty checksum")
	}
}

func BenchmarkDateTimeCoreFixtures(b *testing.B) {
	fixture := readBenchmarkFixture("../conformance/fixtures/date-time-core/cases.json")
	cases := make([]map[string]any, 0, len(arrayValue(fixture["formatCases"])))
	for _, rawCase := range arrayValue(fixture["formatCases"]) {
		cases = append(cases, asObject(rawCase))
	}
	if len(cases) == 0 {
		b.Fatal("no date-time-core benchmark cases")
	}
	b.ReportAllocs()
	checksum := 0
	for index := 0; index < b.N; index++ {
		output, err := formatDateTimeCoreFixtureItem(cases[index%len(cases)])
		if err != nil {
			b.Fatal(err)
		}
		checksum += len(output)
	}
	if checksum == 0 {
		b.Fatal("empty checksum")
	}
}

func BenchmarkRelativeTimeCoreFixtures(b *testing.B) {
	data := readBenchmarkRelativeTimeData()
	fixture := readBenchmarkFixture("../conformance/fixtures/functions/relative-time-duration-v0.json")
	registry := RelativeTimeCoreFunctionRegistry(data)
	var cases []struct {
		model     Model
		arguments map[string]any
		locale    string
	}
	for _, rawCase := range arrayValue(fixture["cases"]) {
		item := asObject(rawCase)
		parse := ParseToModel(stringValue(item["source"]))
		if parse.HasDiagnostics {
			continue
		}
		cases = append(cases, struct {
			model     Model
			arguments map[string]any
			locale    string
		}{
			model:     parse.Model,
			arguments: mapValue(item["arguments"]),
			locale:    stringValue(item["locale"]),
		})
	}
	if len(cases) == 0 {
		b.Fatal("no relative-time-core benchmark cases")
	}
	b.ReportAllocs()
	b.ResetTimer()
	checksum := 0
	for index := 0; index < b.N; index++ {
		item := cases[index%len(cases)]
		output := FormatMessage(item.model, item.arguments, Options{
			Locale:    item.locale,
			Functions: registry,
		})
		if output.HasErrors() {
			b.Fatal(output.Errors)
		}
		checksum += len(output.Value)
	}
	if checksum == 0 {
		b.Fatal("empty checksum")
	}
}

func BenchmarkRelativeTimeCoreDirectFixtures(b *testing.B) {
	data := readBenchmarkRelativeTimeData()
	formatter, err := NewRelativeTimeCoreFormatter(data)
	if err != nil {
		b.Fatal(err)
	}
	fixture := readBenchmarkFixture("../conformance/fixtures/functions/relative-time-duration-v0.json")
	var cases []struct {
		value   any
		options RelativeTimeCoreOptions
	}
	for _, rawCase := range arrayValue(fixture["cases"]) {
		item := asObject(rawCase)
		arguments := mapValue(item["arguments"])
		cases = append(cases, struct {
			value   any
			options RelativeTimeCoreOptions
		}{
			value:   arguments["delta"],
			options: relativeTimeCoreOptionsFromFixtureSource(stringValue(item["locale"]), stringValue(item["source"])),
		})
	}
	if len(cases) == 0 {
		b.Fatal("no relative-time-core direct benchmark cases")
	}
	b.ReportAllocs()
	b.ResetTimer()
	checksum := 0
	for index := 0; index < b.N; index++ {
		item := cases[index%len(cases)]
		output, err := formatter.Format(item.value, item.options)
		if err != nil {
			b.Fatal(err)
		}
		checksum += len(output)
	}
	if checksum == 0 {
		b.Fatal("empty checksum")
	}
}

func benchmarkFixturePaths(b *testing.B, root string) []string {
	b.Helper()
	entries, err := os.ReadDir(root)
	if err != nil {
		b.Fatal(err)
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

func readBenchmarkFixture(path string) map[string]any {
	data, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	var fixture map[string]any
	if err := json.Unmarshal(data, &fixture); err != nil {
		panic(err)
	}
	return fixture
}

func readBenchmarkRelativeTimeData() RelativeTimeCoreData {
	payload, err := os.ReadFile("../cldr/generated/relative-time/all/relative_time.json")
	if err != nil {
		panic(err)
	}
	var decoded RelativeTimeCoreData
	if err := json.Unmarshal(payload, &decoded); err != nil {
		panic(err)
	}
	return decoded
}

func relativeTimeCoreOptionsFromFixtureSource(locale, source string) RelativeTimeCoreOptions {
	return RelativeTimeCoreOptions{
		Locale:  locale,
		Style:   defaultString(relativeTimeCoreSourceOption(source, "style"), RelativeTimeCoreStyleShort),
		Numeric: defaultString(relativeTimeCoreSourceOption(source, "numeric"), RelativeTimeCoreNumericAlways),
		Policy:  defaultString(relativeTimeCoreSourceOption(source, "policy"), RelativeTimeCorePolicyPrecise),
		Unit:    defaultString(relativeTimeCoreSourceOption(source, "unit"), RelativeTimeCoreUnitAuto),
	}
}

func relativeTimeCoreSourceOption(source, name string) string {
	marker := name + "="
	index := strings.Index(source, marker)
	if index < 0 {
		return ""
	}
	start := index + len(marker)
	end := start
	for end < len(source) {
		ch := source[end]
		if ch == ' ' || ch == '}' {
			break
		}
		end++
	}
	return source[start:end]
}
