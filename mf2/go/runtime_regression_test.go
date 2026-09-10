package mf2

import (
	"math"
	"strings"
	"testing"
)

func TestIntegerDecimalTruncationDoesNotNarrowToInt64(t *testing.T) {
	for operand, want := range map[string]string{
		"1e19": "10000000000000000000", "-1e19": "-10000000000000000000",
		"9223372036854775807": "9223372036854775807", "9223372036854775808": "9223372036854775808",
		"-9223372036854775808": "-9223372036854775808", "-9223372036854775809": "-9223372036854775809",
		"9007199254740993.9": "9007199254740993", "-9007199254740993.9": "-9007199254740993",
	} {
		model := ParseToModel("{" + operand + " :integer}").Model
		result := FormatMessage(model, nil, Options{})
		if result.HasErrors() || result.Value != want {
			t.Fatal(operand, result)
		}
		model = ParseToModel(".local $n = {" + operand + " :integer select=exact}\n.match $n\n" + want + " {{exact}}\n* {{other}}").Model
		if result = FormatMessage(model, nil, Options{}); result.HasErrors() || result.Value != "exact" {
			t.Fatal("integer exact selection", operand, result)
		}
	}
	for _, operand := range []float64{1e19, -1e19, math.Ldexp(1, 63), math.Nextafter(-math.Ldexp(1, 63), math.Inf(-1))} {
		if valueToString(operand) == "9223372036854775807" || valueToString(operand) == "-9223372036854775808" {
			t.Fatal("native number conversion narrowed", operand)
		}
	}
	result := FormatMessage(ParseToModel("{$x}").Model, map[string]any{"x": 1e19}, Options{})
	if result.HasErrors() || result.Value != "10000000000000000000" {
		t.Fatal(result)
	}
	result = FormatMessage(ParseToModel("{1e-5000 :integer}").Model, nil, Options{})
	if !result.HasErrors() || result.Errors[0].Code != "bad-operand" {
		t.Fatal("integer decimal expansion bound", result)
	}
}

func TestNumericResourceBoundaries(t *testing.T) {
	for _, source := range []string{"{1 :number minimumFractionDigits=1001}", "{1 :number maximumFractionDigits=1001}", "{1 :number minimumFractionDigits=3 maximumFractionDigits=2}", "{1e-100000 :offset add=1}"} {
		parsed := ParseToModel(source)
		if parsed.HasDiagnostics {
			t.Fatal(parsed.Diagnostics)
		}
		result := FormatMessage(parsed.Model, nil, Options{})
		if !result.HasErrors() {
			t.Fatalf("accepted out of bounds option: %s", source)
		}
	}
	parsed := ParseToModel("{1 :number minimumFractionDigits=1000}")
	result := FormatMessage(parsed.Model, nil, Options{})
	if result.HasErrors() || result.Value != "1."+strings.Repeat("0", 1000) {
		t.Fatal("valid fraction boundary rejected", result)
	}
}

func TestPartsDoNotMutateCatalog(t *testing.T) {
	parsed := ParseToModel("{#a url=$x @role=|old|}Hello{/a}")
	first := FormatMessageToParts(parsed.Model, map[string]any{"x": "url"}, Options{})
	asObject(asObject(first.Parts[0]["attributes"])["role"])["value"] = "new"
	asObject(asObject(first.Parts[0]["options"])["url"])["name"] = "changed"
	second := FormatMessageToParts(parsed.Model, map[string]any{"x": "url"}, Options{})
	if asObject(asObject(second.Parts[0]["attributes"])["role"])["value"] != "old" || asObject(asObject(second.Parts[0]["options"])["url"])["name"] != "x" {
		t.Fatal("parts mutation changed catalog")
	}
}

func TestNumericIsolationMetadata(t *testing.T) {
	registry := PortableFunctionRegistry()
	probe := registry.WithFunction("probe", func(call FunctionCall) (string, error) { return call.OptionValue("u:dir", "removed") })
	result := FormatMessage(ParseToModel("{:probe u:dir=$direction}").Model, map[string]any{"direction": "rtl"}, Options{Functions: probe})
	if result.Value != "removed" || result.HasErrors() {
		t.Fatal("u:dir reached callback", result)
	}
	model := ParseToModel("{1 :number}").Model
	for _, locale := range []string{"en", "ar", "ar-Latn", "en-Arab", "en-Qaaa", "zz", "und", "az-IR", "sd-IN", "en-u-nu-arab"} {
		ltr := locale == "en" || locale == "ar-Latn" || locale == "sd-IN" || locale == "en-u-nu-arab"
		want := "\u20681\u2069"
		if ltr {
			want = "1"
		}
		result := FormatMessage(model, nil, Options{Locale: locale, BidiIsolation: "default", Functions: registry})
		if result.Value != want {
			t.Fatalf("numeric direction %s: %q", locale, result.Value)
		}
	}
	custom := registry.WithFunction("number", func(FunctionCall) (string, error) { return "custom", nil })
	if got := FormatMessage(model, nil, Options{BidiIsolation: "default", Functions: custom}); got.Value != "\u2068custom\u2069" {
		t.Fatal(got)
	}
	selector := registry.WithSelector("number", func(FunctionMatch) (*int, error) { return nil, nil })
	if got := FormatMessage(model, nil, Options{BidiIsolation: "default", Functions: selector}); got.Value != "1" {
		t.Fatal(got)
	}
	directed := ParseToModel(".local $n = {1 :number u:dir=rtl}\n{{{$n}}}").Model
	if got := FormatMessage(directed, nil, Options{BidiIsolation: "default", Functions: registry}); got.Value != "\u20671\u2069" {
		t.Fatal(got)
	}
}

func TestLiteralSourceCacheDoesNotCrossCopiedSourcesOrFormats(t *testing.T) {
	functions := PortableFunctionRegistry().WithFunction("number", func(call FunctionCall) (string, error) {
		if call.InheritedSource == nil {
			return call.Value, nil
		}
		numericSourceOperandText(call.InheritedSource)
		copied := *call.InheritedSource
		copied.Value, copied.Inherited = "2", nil
		value, ok := numericSourceOperandText(&copied)
		if !ok {
			t.Fatal("copied source lost numeric value")
		}
		return value, nil
	})
	model := ParseToModel(".local $n = {1 :number}\n{{{$n :number}}}").Model
	if result := FormatMessage(model, nil, Options{Functions: functions}); result.HasErrors() || result.Value != "2" {
		t.Fatal(result)
	}
	dynamic := ParseToModel(".local $n = {$x :number}\n{{{$n :number}}}").Model
	for _, value := range []int{1, 2, 3} {
		result := FormatMessage(dynamic, map[string]any{"x": value}, Options{})
		if result.HasErrors() || result.Value != valueToString(value) {
			t.Fatal(result)
		}
	}
}

func TestCallbackMetadataCannotMutateModelOrCachedSources(t *testing.T) {
	mutate := func(function map[string]any, source *FunctionSource) {
		function["name"] = "changed"
		asObject(asObject(source.Function["options"])["maximumFractionDigits"])["value"] = "2"
		source.Value = "99"
		source.Inherited = nil
	}
	registry := PortableFunctionRegistry().WithFunction("probe", func(call FunctionCall) (string, error) {
		mutate(call.Function, call.InheritedSource)
		return call.Value, nil
	})
	model := ParseToModel(".local $n = {1.29 :number maximumFractionDigits=0}\n.local $m = {$n :number}\n{{{$n :probe}|{$m :number}|{$n :number}}}").Model
	for range 2 {
		result := FormatMessage(model, nil, Options{Functions: registry})
		if result.HasErrors() || result.Value != "1|1|1" {
			t.Fatal("formatter mutation escaped callback", result)
		}
	}
	registry = PortableFunctionRegistry().WithSelector("number", func(match FunctionMatch) (*int, error) {
		mutate(match.Function, match.InheritedSource)
		rank := 1
		return &rank, nil
	})
	model = ParseToModel(".local $n = {1.29 :number maximumFractionDigits=0}\n.match $n\none {{{$n :number}}}\n* {{other}}").Model
	for range 2 {
		result := FormatMessage(model, nil, Options{Functions: registry})
		if result.HasErrors() || result.Value != "1" {
			t.Fatal("selector mutation escaped callback", result)
		}
	}
}
