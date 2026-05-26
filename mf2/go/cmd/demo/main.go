package main

import (
	"fmt"

	mf2 "github.com/box/mojito/mf2/go"
)

func main() {
	examples := []struct {
		id     string
		source string
		args   map[string]any
		locale string
	}{
		{
			id:     "welcome",
			source: "Welcome, {$name}!",
			args:   map[string]any{"name": "Mojito"},
			locale: "en",
		},
		{
			id:     "cart.items",
			source: ".input {$count :number}\n.match $count\none {{{$count} item}}\n* {{{$count} items}}",
			args:   map[string]any{"count": 2},
			locale: "en",
		},
		{
			id:     "cart.items.ru",
			source: ".input {$count :number}\n.match $count\none {{{$count} предмет}}\nfew {{{$count} предмета}}\n* {{{$count} предметов}}",
			args:   map[string]any{"count": 5},
			locale: "ru",
		},
		{
			id:     "file.saved",
			source: "File {$name :string u:dir=rtl} saved.",
			args:   map[string]any{"name": "שלום.txt"},
			locale: "en",
		},
	}
	for _, example := range examples {
		parsed := mf2.ParseToModel(example.source)
		if parsed.HasDiagnostics {
			fmt.Printf("%s diagnostics=%v\n", example.id, parsed.Diagnostics)
			continue
		}
		output := mf2.FormatMessage(parsed.Model, example.args, mf2.Options{
			Locale:        example.locale,
			BidiIsolation: "default",
		})
		if output.HasErrors() {
			fmt.Printf("%s error=%v\n", example.id, output.Errors)
			continue
		}
		fmt.Printf("%s[%s] -> %q\n", example.id, example.locale, output.Value)
	}
	parsed := mf2.ParseToModel("Hello {$name}")
	recovered := mf2.FormatMessage(parsed.Model, nil, mf2.Options{
		OnMissingArgument: func(context mf2.RecoveryContext) (string, bool) {
			return "[missing " + context.VariableName + "]", true
		},
	})
	if recovered.Value != "Hello [missing name]" || len(recovered.Errors) != 1 {
		panic(fmt.Sprintf("unexpected recovery result: %#v", recovered))
	}
	fmt.Printf("recovery[en] -> %q\n", recovered.Value)
}
