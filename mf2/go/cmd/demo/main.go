package main

import (
	"fmt"

	mf2 "mojito-mf2-go"
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
		output, err := mf2.FormatMessage(parsed.Model, example.args, mf2.Options{
			Locale:        example.locale,
			BidiIsolation: "default",
		})
		if err != nil {
			fmt.Printf("%s error=%v\n", example.id, err)
			continue
		}
		fmt.Printf("%s[%s] -> %q\n", example.id, example.locale, output)
	}
}
