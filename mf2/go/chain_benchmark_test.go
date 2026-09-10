package mf2

import (
	"fmt"
	"strings"
	"testing"
)

func BenchmarkLiteralDeclarationChain(b *testing.B) {
	for _, count := range []int{1000, 2000, 4000, 8000} {
		b.Run(fmt.Sprint(count), func(b *testing.B) {
			var source strings.Builder
			source.WriteString(".local $v0 = {1 :number}\n")
			for index := 1; index < count; index++ {
				fmt.Fprintf(&source, ".local $v%d = {$v%d :number}\n", index, index-1)
			}
			fmt.Fprintf(&source, "{{{$v%d}}}", count-1)
			parsed := ParseToModel(source.String())
			if parsed.HasDiagnostics {
				b.Fatal(parsed.Diagnostics)
			}
			options := Options{Functions: PortableFunctionRegistry()}
			preflight := FormatMessage(parsed.Model, nil, options)
			if preflight.HasErrors() || preflight.Value != "1" {
				b.Fatal(preflight)
			}
			b.ReportAllocs()
			b.ResetTimer()
			for index := 0; index < b.N; index++ {
				result := FormatMessage(parsed.Model, nil, options)
				if result.HasErrors() || result.Value != "1" {
					b.Fatal(result)
				}
			}
		})
	}
}
