package mf2

import "strings"

func canonicalLocaleKey(locale string) string {
	rawParts := strings.FieldsFunc(strings.TrimSpace(locale), func(r rune) bool {
		return r == '-' || r == '_'
	})
	parts := make([]string, 0, len(rawParts))
	for index, part := range rawParts {
		if part == "" {
			continue
		}
		if len([]rune(part)) == 1 {
			break
		}
		parts = append(parts, canonicalSubtag(index, part))
	}
	return strings.Join(parts, "-")
}

func localeLookupChain(locale string) []string {
	key := canonicalLocaleKey(locale)
	if key == "" {
		return nil
	}
	parts := strings.Split(key, "-")
	chain := make([]string, 0, len(parts))
	for length := len(parts); length > 0; length-- {
		chain = append(chain, strings.Join(parts[:length], "-"))
	}
	return chain
}

func pluralLookupChain(locale string, parents map[string]string) []string {
	chain := localeLookupChain(locale)
	output := make([]string, 0, len(chain)+1)
	seen := map[string]bool{}
	for _, candidate := range chain {
		if candidate == "" || seen[candidate] {
			continue
		}
		output = append(output, candidate)
		seen[candidate] = true
		if parent := parents[candidate]; parent != "" && !seen[parent] {
			output = append(output, parent)
			seen[parent] = true
		}
	}
	return output
}

func canonicalSubtag(index int, part string) string {
	runes := []rune(part)
	if index == 0 {
		return strings.ToLower(part)
	}
	if len(runes) == 4 && allLetters(part) {
		return strings.ToUpper(string(runes[0])) + strings.ToLower(string(runes[1:]))
	}
	if (len(runes) == 2 && allLetters(part)) || (len(runes) == 3 && allDigits(part)) {
		return strings.ToUpper(part)
	}
	return strings.ToLower(part)
}

func allLetters(value string) bool {
	for _, r := range value {
		if (r < 'A' || r > 'Z') && (r < 'a' || r > 'z') {
			return false
		}
	}
	return value != ""
}

func allDigits(value string) bool {
	for _, r := range value {
		if r < '0' || r > '9' {
			return false
		}
	}
	return value != ""
}
