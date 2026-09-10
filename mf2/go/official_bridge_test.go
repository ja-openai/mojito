package mf2

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"testing"
)

// A compiled test binary runs the bridge without exposing test functions in the library.
func TestMain(m *testing.M) {
	if os.Getenv("MF2_OFFICIAL_BRIDGE") != "1" {
		os.Exit(m.Run())
	}
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 4096), 4*1024*1024)
	encoder := json.NewEncoder(os.Stdout)
	registry := PortableFunctionRegistry().WithFunction("test:function", officialGoTestFunction).WithFunction("test:select", officialGoTestSelectResolver).WithFunction("test:format", officialGoTestFormatResolver).WithSelector("test:function", officialGoTestSelector).WithSelector("test:select", officialGoTestSelector).WithSelector("test:format", officialGoTestFormatSelector)
	for scanner.Scan() {
		var request struct {
			Source        string         `json:"source"`
			Arguments     map[string]any `json:"arguments"`
			Locale        string         `json:"locale"`
			BidiIsolation string         `json:"bidiIsolation"`
			Registry      string         `json:"registry"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		parsed := ParseToModel(request.Source)
		response := map[string]any{"diagnostics": []string{}, "value": "", "errors": []string{}, "parts": []Part{}}
		if parsed.HasDiagnostics {
			codes := []string{}
			for _, d := range parsed.Diagnostics {
				codes = append(codes, d.Code)
			}
			response["diagnostics"] = codes
		} else {
			// Go's production default is the portable registry; there is no native adapter.
			options := Options{Locale: request.Locale, BidiIsolation: request.BidiIsolation, Functions: registry}
			result := FormatMessage(parsed.Model, request.Arguments, options)
			parts := FormatMessageToParts(parsed.Model, request.Arguments, options)
			response["value"] = result.Value
			response["parts"] = parts.Parts
			codes := []string{}
			for _, err := range result.Errors {
				codes = append(codes, err.Code)
			}
			response["errors"] = codes
			codes = []string{}
			for _, err := range parts.Errors {
				codes = append(codes, err.Code)
			}
			response["partsErrors"] = codes
		}
		if err := encoder.Encode(response); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	if err := scanner.Err(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	os.Exit(0)
}
