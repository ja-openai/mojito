package mf2_test

import (
	mf2 "github.com/box/mojito/mf2/go"
	"testing"
)

func TestFunctionSourceSupportedConstruction(t *testing.T) {
	function := map[string]any{"type": "function", "name": "number"}
	keyed := mf2.FunctionSource{Value: "1", Function: function}
	built := mf2.NewFunctionSource("2", function, func(name, fallback string) (string, error) { return fallback, nil }, &keyed)
	if built.Value != "2" || built.Inherited != &keyed {
		t.Fatal("caller source contract changed")
	}
	value, err := built.OptionValue("missing", "default")
	if err != nil || value != "default" {
		t.Fatal(value, err)
	}
}
