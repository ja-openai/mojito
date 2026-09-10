package mf2

import (
	"regexp"
	"strings"
)

var directionLanguage = regexp.MustCompile(`^[a-z]{2,8}$`)
var directionSubtag = regexp.MustCompile(`^[A-Za-z0-9]{2,8}$`)
var directionScript = regexp.MustCompile(`^[A-Za-z]{4}$`)
var directionRegion = regexp.MustCompile(`^([A-Za-z]{2}|[0-9]{3})$`)

func localeIsLtr(locale string) bool {
	subtags := strings.Split(strings.ReplaceAll(locale, "_", "-"), "-")
	language := strings.ToLower(subtags[0])
	if !directionLanguage.MatchString(language) {
		return false
	}
	script, region := "", ""
	for _, subtag := range subtags[1:] {
		if len(subtag) == 1 {
			break
		}
		if !directionSubtag.MatchString(subtag) {
			return false
		}
		if script == "" && directionScript.MatchString(subtag) {
			script = strings.ToUpper(subtag[:1]) + strings.ToLower(subtag[1:])
		} else if region == "" && directionRegion.MatchString(subtag) {
			region = strings.ToUpper(subtag)
		}
	}
	contains := func(table, value string) bool { return strings.Contains(table, " "+value+" ") }
	if script != "" {
		return contains(localeDirectionLTR_SCRIPTS, script)
	}
	if region != "" {
		key := language + "-" + region
		if contains(localeDirectionRTL_REGION_OVERRIDES, key) {
			return false
		}
		if contains(localeDirectionLTR_REGION_OVERRIDES, key) {
			return true
		}
	}
	return !(language == "und" && region == "") && contains(localeDirectionLTR_LANGUAGES, language)
}
