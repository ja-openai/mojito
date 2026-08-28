package mf2

import (
	"reflect"
	"testing"
)

func TestNumericReferenceRegressions(t *testing.T) {
	tests := []struct {
		name           string
		source         string
		arguments      map[string]any
		expected       string
		expectedErrors []string
	}{
		{
			name:      "exact numeric key outranks plural category",
			source:    ".input {$count :integer}\n.match $count\none {{plural one}}\n1 {{exact one}}\n* {{fallback}}",
			arguments: map[string]any{"count": 1},
			expected:  "exact one",
		},
		{
			name:      "integer numeric operand uses canonical exact serialization",
			source:    ".input {$value :number}\n.match $value\n1.0 {{decimal spelling}}\n1 {{integer spelling}}\n* {{fallback}}",
			arguments: map[string]any{"value": 1},
			expected:  "integer spelling",
		},
		{
			name:           "invalid numeric key does not prevent a valid exact match",
			source:         ".input {$value :number}\n.match $value\nhorse {{invalid}}\n1 {{valid exact}}\n* {{fallback}}",
			arguments:      map[string]any{"value": 1},
			expected:       "valid exact",
			expectedErrors: []string{"bad-variant-key"},
		},
		{
			name:      "maximum fraction digits rounds a non-tie display value",
			source:    ".input {$value :number maximumFractionDigits=1} {{Value {$value}}}",
			arguments: map[string]any{"value": 1.29},
			expected:  "Value 1.3",
		},
		{
			name:     "numeric reannotation inherits minimum fraction digits",
			source:   ".local $n = {1.2 :number minimumFractionDigits=2}\n{{Value {$n :number}}}",
			expected: "Value 1.20",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			parsed := ParseToModel(test.source)
			if parsed.HasDiagnostics {
				t.Fatalf("unexpected parse diagnostics: %#v", parsed.Diagnostics)
			}
			actual := FormatMessage(parsed.Model, test.arguments, Options{Locale: "en"})
			if actual.Value != test.expected {
				t.Fatalf("expected %q, got %q (errors: %v)", test.expected, actual.Value, actual.Errors)
			}
			var actualErrors []string
			for _, err := range actual.Errors {
				actualErrors = append(actualErrors, err.Code)
			}
			if !reflect.DeepEqual(actualErrors, test.expectedErrors) {
				t.Fatalf("expected errors %v, got %v", test.expectedErrors, actualErrors)
			}
		})
	}
}
