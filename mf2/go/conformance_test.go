package mf2

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"
)

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
			actual, err := FormatMessage(model, mapValue(item["arguments"]), Options{
				Locale:        stringValue(item["locale"]),
				BidiIsolation: stringValue(item["bidiIsolation"]),
			})
			if err != nil {
				t.Fatalf("%s: format failed: %v", filepath.Base(path), err)
			}
			if actual != stringValue(item["expected"]) {
				t.Fatalf("%s: expected format %q, got %q", filepath.Base(path), stringValue(item["expected"]), actual)
			}
		}
		for _, rawCase := range arrayValue(fixture["partsCases"]) {
			item := asObject(rawCase)
			actual, err := FormatMessageToParts(model, mapValue(item["arguments"]), Options{
				Locale:        stringValue(item["locale"]),
				BidiIsolation: stringValue(item["bidiIsolation"]),
			})
			if err != nil {
				t.Fatalf("%s: parts failed: %v", filepath.Base(path), err)
			}
			assertJSONEqual(t, filepath.Base(path)+": parts", item["expected"], actual)
		}
		for _, rawCase := range arrayValue(fixture["fallbackCases"]) {
			item := asObject(rawCase)
			actual := FormatMessageWithFallback(model, mapValue(item["arguments"]), Options{
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
			actual := FormatMessageToPartsWithFallback(model, mapValue(item["arguments"]), Options{Locale: stringValue(item["locale"])})
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
		_, err := FormatMessage(Model(mapValue(fixture["model"])), mapValue(fixture["arguments"]), Options{Locale: stringValue(fixture["locale"])})
		if err == nil {
			t.Fatalf("%s: expected format error", filepath.Base(path))
		}
		expected := stringValue(asObject(fixture["expectedError"])["code"])
		if errorCode(err) != expected {
			t.Fatalf("%s: expected error %s, got %s (%v)", filepath.Base(path), expected, errorCode(err), err)
		}
	}
}

func TestLocaleKeyFixtures(t *testing.T) {
	fixture := readFixture(t, "../conformance/fixtures/locale-key/cases.json")
	for _, raw := range arrayValue(fixture["canonicalCases"]) {
		item := asObject(raw)
		actual := CanonicalLocaleKey(stringValue(item["source"]))
		if actual != stringValue(item["expected"]) {
			t.Fatalf("canonical locale: expected %s, got %s", stringValue(item["expected"]), actual)
		}
	}
	for _, raw := range arrayValue(fixture["lookupCases"]) {
		item := asObject(raw)
		assertJSONEqual(t, "lookup chain", item["expected"], LocaleLookupChain(stringValue(item["source"])))
	}
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
	return ValueToString(value)
}
