package mf2

import (
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

type unicodeFileCheck struct {
	path string
	mode string
}

var unicodeChecks = []unicodeFileCheck{
	{"tests/syntax.json", "parse"},
	{"tests/syntax-errors.json", "parse"},
	{"tests/bidi.json", "parse"},
	{"tests/data-model-errors.json", "data-model"},
	{"tests/functions/string.json", "runtime"},
	{"tests/functions/number.json", "runtime"},
	{"tests/functions/percent.json", "runtime"},
	{"tests/functions/currency.json", "runtime"},
	{"tests/functions/date.json", "runtime"},
	{"tests/functions/datetime.json", "runtime"},
	{"tests/functions/time.json", "runtime"},
	{"tests/functions/offset.json", "runtime"},
	{"tests/functions/integer.json", "runtime"},
	{"tests/u-options.json", "runtime"},
	{"tests/fallback.json", "runtime"},
	{"tests/pattern-selection.json", "runtime"},
}

func TestUnicodeOfficialSuite(t *testing.T) {
	root := "../third_party/message-format-wg/test"
	baseline := readFixture(t, "../conformance/unicode-official-baseline.json")
	summary := map[string]map[string]int{}
	totalPassed := 0
	totalSkipped := 0
	for _, check := range unicodeChecks {
		suite := readFixture(t, filepath.Join(root, check.path))
		defaults := asObject(suite["defaultTestProperties"])
		passed := 0
		skipped := 0
		for _, rawTest := range arrayValue(suite["tests"]) {
			test := asObject(rawTest)
			ok := false
			switch check.mode {
			case "parse":
				ok = checkUnicodeParseTest(defaults, test)
			case "data-model":
				ok = checkUnicodeDataModelTest(defaults, test)
			case "runtime":
				ok = checkUnicodeRuntimeTest(defaults, test)
			}
			if ok {
				passed++
			} else {
				t.Logf("skip %s: %s diagnostics=%#v", check.path, stringValue(test["src"]), ParseToModel(stringValue(test["src"])).Diagnostics)
				skipped++
			}
		}
		t.Logf("%s %s passed=%d skipped=%d", check.mode, check.path, passed, skipped)
		summary[check.path] = map[string]int{"passed": passed, "skipped": skipped}
		totalPassed += passed
		totalSkipped += skipped
	}
	notWired := countNotWiredOfficialTests(t, root)
	if intValue(baseline["passed"]) != totalPassed || intValue(baseline["skipped"]) != totalSkipped || intValue(baseline["notWired"]) != notWired {
		t.Fatalf("expected Unicode official passed=%d skipped=%d not_wired=%d, got passed=%d skipped=%d not_wired=%d",
			intValue(baseline["passed"]), intValue(baseline["skipped"]), intValue(baseline["notWired"]),
			totalPassed, totalSkipped, notWired)
	}
	for path, actual := range summary {
		expected := asObject(asObject(baseline["files"])[path])
		if intValue(expected["passed"]) != actual["passed"] || intValue(expected["skipped"]) != actual["skipped"] {
			t.Fatalf("%s: expected passed=%d skipped=%d, got passed=%d skipped=%d",
				path, intValue(expected["passed"]), intValue(expected["skipped"]), actual["passed"], actual["skipped"])
		}
	}
}

func checkUnicodeParseTest(defaults map[string]any, test map[string]any) bool {
	expectedSyntaxError := false
	for _, err := range expectedUnicodeErrors(defaults, test) {
		if stringValue(asObject(err)["type"]) == "syntax-error" {
			expectedSyntaxError = true
			break
		}
	}
	result := ParseToModel(stringValue(test["src"]))
	return result.HasDiagnostics == expectedSyntaxError
}

func checkUnicodeDataModelTest(defaults map[string]any, test map[string]any) bool {
	expectedCodes := expectedUnicodeLocalCodes(defaults, test)
	result := ParseToModel(stringValue(test["src"]))
	if len(expectedCodes) == 0 {
		if test["exp"] == nil || result.HasDiagnostics {
			return false
		}
		actual, err := FormatMessage(result.Model, unicodeArgumentsFor(test, result.Model), Options{
			Locale:        unicodeLocale(defaults, test),
			BidiIsolation: unicodeBidiIsolation(defaults, test),
		})
		return err == nil && actual == stringValue(test["exp"])
	}
	var actualCodes []string
	if result.HasDiagnostics {
		for _, diagnostic := range result.Diagnostics {
			actualCodes = append(actualCodes, diagnostic.Code)
		}
	} else if _, err := FormatMessage(result.Model, unicodeArgumentsFor(test, result.Model), Options{
		Locale:        unicodeLocale(defaults, test),
		BidiIsolation: unicodeBidiIsolation(defaults, test),
	}); err != nil {
		actualCodes = append(actualCodes, errorCode(err))
	}
	return containsAny(actualCodes, expectedCodes)
}

func checkUnicodeRuntimeTest(defaults map[string]any, test map[string]any) bool {
	result := ParseToModel(stringValue(test["src"]))
	if result.HasDiagnostics {
		return false
	}
	expectedCodes := expectedUnicodeLocalCodes(defaults, test)
	actual := FormatMessageWithFallback(result.Model, unicodeRuntimeArgumentsFor(test), Options{
		Locale:        unicodeLocale(defaults, test),
		BidiIsolation: unicodeBidiIsolation(defaults, test),
		Functions:     officialGoFunctionRegistry(),
	})
	var actualCodes []string
	for _, err := range actual.Errors {
		actualCodes = append(actualCodes, err.Code)
	}
	if !containsAll(actualCodes, expectedCodes) {
		return false
	}
	if len(expectedCodes) == 0 && len(actualCodes) > 0 {
		return false
	}
	return test["exp"] == nil || actual.Value == stringValue(test["exp"])
}

func officialGoFunctionRegistry() FunctionRegistry {
	return DefaultFunctionRegistry().
		WithFunction("test:function", officialGoTestFunction).
		WithFunction("test:select", officialGoTestSelectResolver).
		WithFunction("test:format", officialGoTestFormatResolver).
		WithSelector("test:function", officialGoTestSelector).
		WithSelector("test:select", officialGoTestSelector).
		WithSelector("test:format", officialGoTestFormatSelector)
}

func officialGoTestFunction(call FunctionCall) (string, error) {
	state, err := officialGoTestStateFromCall(call)
	if err != nil {
		return "", err
	}
	if state.failsFormat {
		return "", badOption(":test:function fails=format requested a format failure.")
	}
	return state.formatValue(), nil
}

func officialGoTestSelectResolver(call FunctionCall) (string, error) {
	state, err := officialGoTestStateFromCall(call)
	if err != nil {
		return "", err
	}
	return state.formatValue(), nil
}

func officialGoTestFormatResolver(call FunctionCall) (string, error) {
	state, err := officialGoTestStateFromCall(call)
	if err != nil {
		return "", err
	}
	return state.formatValue(), nil
}

func officialGoTestSelector(match FunctionMatch) (*int, error) {
	state, err := officialGoTestStateFromMatch(match)
	if err != nil {
		return nil, err
	}
	if state.failsSelect {
		return nil, badSelector(":test function fails selection.")
	}
	if int64(math.Trunc(state.input)) != 1 {
		return nil, nil
	}
	if state.decimalPlaces == 1 && match.Key == "1.0" {
		rank := 2
		return &rank, nil
	}
	if match.Key == "1" {
		rank := 1
		return &rank, nil
	}
	return nil, nil
}

func officialGoTestFormatSelector(FunctionMatch) (*int, error) {
	return nil, badSelector(":test:format cannot be used for selection.")
}

type officialGoTestFunctionState struct {
	input         float64
	decimalPlaces int
	failsFormat   bool
	failsSelect   bool
}

func officialGoTestStateFromCall(call FunctionCall) (officialGoTestFunctionState, error) {
	return officialGoTestState(call.Value, call.InheritedSource, call.OptionValue)
}

func officialGoTestStateFromMatch(match FunctionMatch) (officialGoTestFunctionState, error) {
	return officialGoTestState(match.Value, match.InheritedSource, match.OptionValue)
}

func officialGoTestState(value string, inheritedSource *FunctionSource, optionValue func(string, string) (string, error)) (officialGoTestFunctionState, error) {
	var state officialGoTestFunctionState
	var err error
	if inheritedSource == nil {
		state, err = officialGoTestStateFromValue(value)
	} else {
		state, err = officialGoTestStateFromSource(inheritedSource)
	}
	if err != nil {
		return state, err
	}
	return state, applyOfficialGoTestOptions(&state, optionValue)
}

func officialGoTestStateFromSource(source *FunctionSource) (officialGoTestFunctionState, error) {
	var state officialGoTestFunctionState
	var err error
	if source.Inherited == nil {
		state, err = officialGoTestStateFromValue(source.Value)
	} else {
		state, err = officialGoTestStateFromSource(source.Inherited)
	}
	if err != nil {
		return state, err
	}
	if isOfficialGoTestFunction(stringField(source.Function, "name")) {
		err = applyOfficialGoTestOptions(&state, source.OptionValue)
	}
	return state, err
}

func officialGoTestStateFromValue(value string) (officialGoTestFunctionState, error) {
	input, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsInf(input, 0) || math.IsNaN(input) {
		return officialGoTestFunctionState{}, badOperand("Unicode test function requires a numeric operand.")
	}
	return officialGoTestFunctionState{input: input}, nil
}

func applyOfficialGoTestOptions(state *officialGoTestFunctionState, optionValue func(string, string) (string, error)) error {
	decimalPlaces, err := optionValue("decimalPlaces", "")
	if err != nil {
		return err
	}
	switch decimalPlaces {
	case "":
	case "0":
		state.decimalPlaces = 0
	case "1":
		state.decimalPlaces = 1
	default:
		return badOption(":test function decimalPlaces must be 0 or 1.")
	}
	fails, err := optionValue("fails", "")
	if err != nil {
		return err
	}
	switch fails {
	case "always":
		state.failsFormat = true
		state.failsSelect = true
	case "format":
		state.failsFormat = true
	case "select":
		state.failsSelect = true
	}
	return nil
}

func (s officialGoTestFunctionState) formatValue() string {
	sign := ""
	if s.input < 0 {
		sign = "-"
	}
	absolute := math.Abs(s.input)
	integer := math.Floor(absolute)
	if s.decimalPlaces == 1 {
		digit := math.Floor((absolute - integer) * 10)
		return sign + strconv.FormatInt(int64(integer), 10) + "." + strconv.FormatInt(int64(digit), 10)
	}
	return sign + strconv.FormatInt(int64(integer), 10)
}

func isOfficialGoTestFunction(name string) bool {
	return name == "test:function" || name == "test:select" || name == "test:format"
}

func unicodeArgumentsFor(test map[string]any, model Model) map[string]any {
	args := map[string]any{}
	for _, raw := range arrayField(map[string]any(model), "declarations") {
		declaration := asObject(raw)
		if stringField(declaration, "type") == "input" {
			args[stringField(declaration, "name")] = "1"
		}
	}
	for key, value := range unicodeRuntimeArgumentsFor(test) {
		args[key] = value
	}
	return args
}

func unicodeRuntimeArgumentsFor(test map[string]any) map[string]any {
	args := map[string]any{}
	for _, raw := range arrayValue(test["params"]) {
		param := asObject(raw)
		args[stringValue(param["name"])] = param["value"]
	}
	return args
}

func expectedUnicodeErrors(defaults map[string]any, test map[string]any) []any {
	if errors := arrayValue(test["expErrors"]); errors != nil {
		return errors
	}
	return arrayValue(defaults["expErrors"])
}

func expectedUnicodeLocalCodes(defaults map[string]any, test map[string]any) []string {
	var codes []string
	for _, raw := range expectedUnicodeErrors(defaults, test) {
		code := stringValue(asObject(raw)["type"])
		if code == "variant-key-mismatch" {
			code = "variant-key-count-mismatch"
		}
		codes = append(codes, code)
	}
	return codes
}

func unicodeLocale(defaults map[string]any, test map[string]any) string {
	if value := stringValue(test["locale"]); value != "" {
		return value
	}
	if value := stringValue(defaults["locale"]); value != "" {
		return value
	}
	return "en"
}

func unicodeBidiIsolation(defaults map[string]any, test map[string]any) string {
	if value := stringValue(test["bidiIsolation"]); value != "" {
		return value
	}
	if value := stringValue(defaults["bidiIsolation"]); value != "" {
		return value
	}
	return "none"
}

func countNotWiredOfficialTests(t *testing.T, root string) int {
	t.Helper()
	wired := map[string]bool{}
	for _, check := range unicodeChecks {
		wired[check.path] = true
	}
	count := 0
	err := filepath.WalkDir(filepath.Join(root, "tests"), func(path string, entry os.DirEntry, err error) error {
		if err != nil || entry.IsDir() || filepath.Ext(path) != ".json" {
			return err
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		relative = filepath.ToSlash(relative)
		if wired[relative] {
			return nil
		}
		suite := readFixture(t, path)
		count += len(arrayValue(suite["tests"]))
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return count
}

func containsAny(actual []string, expected []string) bool {
	for _, item := range actual {
		for _, want := range expected {
			if item == want {
				return true
			}
		}
	}
	return false
}

func intValue(value any) int {
	switch typed := value.(type) {
	case int:
		return typed
	case float64:
		return int(typed)
	default:
		parsed, _ := strconv.Atoi(strings.TrimSpace(ValueToString(value)))
		return parsed
	}
}
