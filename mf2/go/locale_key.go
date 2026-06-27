package mf2

import "strings"

const maxLocaleOptionLength = 256

func localeOption(locale, fallback string) (string, error) {
	value := defaultString(locale, fallback)
	if runeCountExceeds(value, maxLocaleOptionLength) {
		return "", badOption("locale must not exceed 256 characters.")
	}
	return value, nil
}

func canonicalLocaleKey(locale string) string {
	if runeCountExceeds(locale, maxLocaleOptionLength) {
		return ""
	}
	rawParts := strings.FieldsFunc(strings.TrimSpace(locale), func(r rune) bool {
		return r == '-' || r == '_'
	})
	parts := make([]string, 0, len(rawParts))
	for index, part := range rawParts {
		if part == "" {
			continue
		}
		if runeCountEqualsOne(part) {
			break
		}
		parts = append(parts, canonicalSubtag(index, part))
	}
	return strings.Join(parts, "-")
}

func localeLookupChain(locale string) []string {
	key := canonicalLocaleKey(locale)
	if key == "" {
		return []string{}
	}
	parts := strings.Split(key, "-")
	chain := make([]string, 0, len(parts))
	for length := len(parts); length > 0; length-- {
		chain = append(chain, strings.Join(parts[:length], "-"))
	}
	return chain
}

func pluralLookupChain(locale string, parents map[string]string) []string {
	return featureLookupChain(locale, parents)
}

func featureLookupChain(locale string, parents map[string]string) []string {
	output := []string{}
	appendFeatureLookupChain(canonicalLocaleKey(locale), parents, &output)
	return output
}

func appendFeatureLookupChain(locale string, parents map[string]string, output *[]string) {
	current := locale
	for current != "" {
		if runeCountExceeds(current, maxLocaleOptionLength) {
			return
		}
		if containsString(*output, current) {
			return
		}
		*output = append(*output, current)
		if parent := parents[current]; parent != "" {
			appendFeatureLookupChain(parent, parents, output)
		}
		current = structuralParent(current)
	}
}

func runeCountExceeds(value string, max int) bool {
	if len(value) <= max {
		return false
	}
	count := 0
	for range value {
		count++
		if count > max {
			return true
		}
	}
	return false
}

func runeCountEqualsOne(value string) bool {
	count := 0
	for range value {
		count++
		if count > 1 {
			return false
		}
	}
	return count == 1
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

func structuralParent(locale string) string {
	if index := strings.LastIndex(locale, "-"); index >= 0 {
		return locale[:index]
	}
	return ""
}

func containsString(values []string, needle string) bool {
	for _, value := range values {
		if value == needle {
			return true
		}
	}
	return false
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
