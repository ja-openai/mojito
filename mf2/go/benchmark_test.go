package mf2

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
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
