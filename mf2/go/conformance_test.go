package mf2

import (
	"encoding/json"
	"go/ast"
	goparser "go/parser"
	"go/token"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
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

func TestPublicRuntimeAPIDoesNotExportInflectionRuntime(t *testing.T) {
	var exported []string
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	fileSet := token.NewFileSet()
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".go") || strings.HasSuffix(entry.Name(), "_test.go") {
			continue
		}
		file, err := goparser.ParseFile(fileSet, entry.Name(), nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		for _, declaration := range file.Decls {
			switch typed := declaration.(type) {
			case *ast.FuncDecl:
				if ast.IsExported(typed.Name.Name) {
					exported = append(exported, typed.Name.Name)
				}
			case *ast.GenDecl:
				for _, spec := range typed.Specs {
					switch typedSpec := spec.(type) {
					case *ast.TypeSpec:
						if ast.IsExported(typedSpec.Name.Name) {
							exported = append(exported, typedSpec.Name.Name)
						}
					case *ast.ValueSpec:
						for _, name := range typedSpec.Names {
							if ast.IsExported(name.Name) {
								exported = append(exported, name.Name)
							}
						}
					}
				}
			}
		}
	}

	sort.Strings(exported)
	for _, name := range exported {
		normalized := normalizePublicName(name)
		if strings.Contains(normalized, "inflection") ||
			strings.Contains(normalized, "m2if") ||
			strings.Contains(normalized, "compiledtermpack") ||
			strings.Contains(normalized, "termpack") {
			t.Fatalf("inflection runtime export %q must stay out of the Go package until a product API is approved; exported names: %v", name, exported)
		}
	}
}

func normalizePublicName(name string) string {
	var builder strings.Builder
	for _, character := range strings.ToLower(name) {
		if character >= 'a' && character <= 'z' || character >= '0' && character <= '9' {
			builder.WriteRune(character)
		}
	}
	return builder.String()
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
	if checked == 0 {
		t.Fatal("locale-key fixture did not contain any cases")
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
