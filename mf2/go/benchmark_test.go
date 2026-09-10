package mf2

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"testing"
)

var benchmarkChecksum int

func BenchmarkFormatSharedFixtures(b *testing.B) {
	b.StopTimer()
	type formatCase struct {
		model     Model
		arguments map[string]any
		options   Options
		expected  string
	}
	var cases []formatCase
	for _, path := range benchmarkFixturePaths(b) {
		fixture := readBenchmarkFixture(path)
		parse := ParseToModel(fixture["source"].(string))
		if parse.HasDiagnostics {
			b.Fatalf("%s: unexpected parse diagnostics: %v", path, parse.Diagnostics)
		}
		for _, raw := range arrayValue(fixture["formatCases"]) {
			item := asObject(raw)
			c := formatCase{parse.Model, mapValue(item["arguments"]), Options{Locale: stringValue(item["locale"]), BidiIsolation: stringValue(item["bidiIsolation"]), Functions: PortableFunctionRegistry()}, stringValue(item["expected"])}
			result := FormatMessage(c.model, c.arguments, c.options)
			if result.HasErrors() || result.Value != c.expected {
				b.Fatalf("%s: preflight expected %q, got %q / %v", path, c.expected, result.Value, result.Errors)
			}
			cases = append(cases, c)
		}
	}
	if len(cases) == 0 {
		b.Fatal("no format benchmark cases")
	}
	for index := 0; index < benchmarkWarmup(b); index++ {
		c := cases[index%len(cases)]
		result := FormatMessage(c.model, c.arguments, c.options)
		if result.HasErrors() {
			b.Fatal(result.Errors)
		}
	}
	b.ReportAllocs()
	b.ResetTimer()
	b.StartTimer()
	checksum := 0
	for index := 0; index < b.N; index++ {
		c := cases[index%len(cases)]
		result := FormatMessage(c.model, c.arguments, c.options)
		if result.HasErrors() {
			b.Fatal(result.Errors)
		}
		checksum += len(result.Value)
	}
	b.StopTimer()
	benchmarkChecksum = checksum
	b.ReportMetric(float64(checksum), "checksum")
}

func BenchmarkParseSharedFixtures(b *testing.B) {
	b.StopTimer()
	var sources []string
	for _, path := range benchmarkFixturePaths(b) {
		fixture := readBenchmarkFixture(path)
		source := fixture["source"].(string)
		result := ParseToModel(source)
		expected := expectedCodes(fixture["expectedDiagnostics"])
		if code := stringValue(asObject(fixture["expectedError"])["code"]); code != "" {
			expected = append(expected, code)
		}
		actual := make([]string, 0, len(result.Diagnostics))
		for _, diagnostic := range result.Diagnostics {
			actual = append(actual, diagnostic.Code)
		}
		if result.HasDiagnostics != (len(expected) > 0) || !containsAll(actual, expected) {
			b.Fatalf("%s: expected parse diagnostics %v, got %v", path, expected, actual)
		}
		sources = append(sources, source)
	}
	if len(sources) == 0 {
		b.Fatal("no parse benchmark sources")
	}
	for index := 0; index < benchmarkWarmup(b); index++ {
		ParseToModel(sources[index%len(sources)])
	}
	b.ReportAllocs()
	b.ResetTimer()
	b.StartTimer()
	parsed, diagnostics, bytes := 0, 0, 0
	for index := 0; index < b.N; index++ {
		source := sources[index%len(sources)]
		result := ParseToModel(source)
		if !result.HasDiagnostics {
			parsed++
		}
		diagnostics += len(result.Diagnostics)
		bytes += len(source)
	}
	b.StopTimer()
	benchmarkChecksum = parsed + diagnostics + bytes
	b.ReportMetric(float64(parsed), "parsed")
	b.ReportMetric(float64(diagnostics), "diagnostics")
	b.ReportMetric(float64(bytes), "source-bytes")
}

func benchmarkWarmup(b *testing.B) int {
	b.Helper()
	raw := os.Getenv("MF2_BENCH_WARMUP")
	if raw == "" {
		return 10000
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 {
		b.Fatal("MF2_BENCH_WARMUP must be a nonnegative integer")
	}
	return value
}
func benchmarkFixturePaths(b *testing.B) []string {
	b.Helper()
	root := os.Getenv("MF2_BENCH_FIXTURES")
	if root == "" {
		root = "../conformance/fixtures/source-to-model"
	}
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
