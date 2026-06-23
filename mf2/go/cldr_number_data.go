package mf2

// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.
type cldrNumberCurrencyFraction struct {
	digits   int
	rounding int
	source   string
}

type cldrNumberCurrencyData struct {
	symbol       string
	narrowSymbol string
	displayName  string
}

type cldrNumberCurrencySpacing struct {
	beforeCurrency string
	afterCurrency  string
}

type cldrNumberLocaleData struct {
	requestedLocale        string
	numbersSourceLocale    string
	currenciesSourceLocale string
	numberingSystem        string
	numberingSystemDigits  string
	minimumGroupingDigits  int
	symbols                map[string]string
	decimalPattern         string
	percentPattern         string
	currencyPattern        string
	currencySpacing        cldrNumberCurrencySpacing
	currencies             map[string]cldrNumberCurrencyData
}

var cldrNumberCurrencyFractions = map[string]cldrNumberCurrencyFraction{
	"DEFAULT": {digits: 2, rounding: 0, source: "DEFAULT"},
	"USD":     {digits: 2, rounding: 0, source: "DEFAULT"},
	"EUR":     {digits: 2, rounding: 0, source: "DEFAULT"},
	"JPY":     {digits: 0, rounding: 0, source: "JPY"},
	"GBP":     {digits: 2, rounding: 0, source: "DEFAULT"},
}

var cldrNumberLocales = map[string]cldrNumberLocaleData{
	"en-US": {
		requestedLocale:        "en-US",
		numbersSourceLocale:    "en",
		currenciesSourceLocale: "en",
		numberingSystem:        "latn",
		numberingSystemDigits:  "0123456789",
		minimumGroupingDigits:  1,
		symbols:                map[string]string{"decimal": ".", "group": ",", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u2030", "approximatelySign": "~"},
		decimalPattern:         "#,##0.###",
		percentPattern:         "#,##0%",
		currencyPattern:        "\u00a4#,##0.00",
		currencySpacing:        cldrNumberCurrencySpacing{beforeCurrency: "\u00a0", afterCurrency: "\u00a0"},
		currencies:             map[string]cldrNumberCurrencyData{"USD": {symbol: "$", narrowSymbol: "$", displayName: "US Dollar"}, "EUR": {symbol: "\u20ac", narrowSymbol: "\u20ac", displayName: "Euro"}, "JPY": {symbol: "\u00a5", narrowSymbol: "\u00a5", displayName: "Japanese Yen"}, "GBP": {symbol: "\u00a3", narrowSymbol: "\u00a3", displayName: "British Pound"}},
	},
	"fr-FR": {
		requestedLocale:        "fr-FR",
		numbersSourceLocale:    "fr",
		currenciesSourceLocale: "fr",
		numberingSystem:        "latn",
		numberingSystemDigits:  "0123456789",
		minimumGroupingDigits:  1,
		symbols:                map[string]string{"decimal": ",", "group": "\u202f", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u2030", "approximatelySign": "\u2243"},
		decimalPattern:         "#,##0.###",
		percentPattern:         "#,##0\u00a0%",
		currencyPattern:        "#,##0.00\u00a0\u00a4",
		currencySpacing:        cldrNumberCurrencySpacing{beforeCurrency: "\u00a0", afterCurrency: "\u00a0"},
		currencies:             map[string]cldrNumberCurrencyData{"USD": {symbol: "$US", narrowSymbol: "$", displayName: "dollar des \u00c9tats-Unis"}, "EUR": {symbol: "\u20ac", narrowSymbol: "\u20ac", displayName: "euro"}, "JPY": {symbol: "JPY", narrowSymbol: "\u00a5", displayName: "yen japonais"}, "GBP": {symbol: "\u00a3GB", narrowSymbol: "\u00a3", displayName: "livre sterling"}},
	},
	"de-DE": {
		requestedLocale:        "de-DE",
		numbersSourceLocale:    "de",
		currenciesSourceLocale: "de",
		numberingSystem:        "latn",
		numberingSystemDigits:  "0123456789",
		minimumGroupingDigits:  1,
		symbols:                map[string]string{"decimal": ",", "group": ".", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u2030", "approximatelySign": "\u2248"},
		decimalPattern:         "#,##0.###",
		percentPattern:         "#,##0\u00a0%",
		currencyPattern:        "#,##0.00\u00a0\u00a4",
		currencySpacing:        cldrNumberCurrencySpacing{beforeCurrency: "\u00a0", afterCurrency: "\u00a0"},
		currencies:             map[string]cldrNumberCurrencyData{"USD": {symbol: "$", narrowSymbol: "$", displayName: "US-Dollar"}, "EUR": {symbol: "\u20ac", narrowSymbol: "\u20ac", displayName: "Euro"}, "JPY": {symbol: "\u00a5", narrowSymbol: "\u00a5", displayName: "Japanischer Yen"}, "GBP": {symbol: "\u00a3", narrowSymbol: "\u00a3", displayName: "Britisches Pfund"}},
	},
	"ja-JP": {
		requestedLocale:        "ja-JP",
		numbersSourceLocale:    "ja",
		currenciesSourceLocale: "ja",
		numberingSystem:        "latn",
		numberingSystemDigits:  "0123456789",
		minimumGroupingDigits:  1,
		symbols:                map[string]string{"decimal": ".", "group": ",", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u2030", "approximatelySign": "\u7d04"},
		decimalPattern:         "#,##0.###",
		percentPattern:         "#,##0%",
		currencyPattern:        "\u00a4#,##0.00",
		currencySpacing:        cldrNumberCurrencySpacing{beforeCurrency: "\u00a0", afterCurrency: "\u00a0"},
		currencies:             map[string]cldrNumberCurrencyData{"USD": {symbol: "$", narrowSymbol: "$", displayName: "\u7c73\u30c9\u30eb"}, "EUR": {symbol: "\u20ac", narrowSymbol: "\u20ac", displayName: "\u30e6\u30fc\u30ed"}, "JPY": {symbol: "\uffe5", narrowSymbol: "\uffe5", displayName: "\u65e5\u672c\u5186"}, "GBP": {symbol: "\u00a3", narrowSymbol: "\u00a3", displayName: "\u82f1\u56fd\u30dd\u30f3\u30c9"}},
	},
	"ar-EG": {
		requestedLocale:        "ar-EG",
		numbersSourceLocale:    "ar-EG",
		currenciesSourceLocale: "ar-EG",
		numberingSystem:        "arab",
		numberingSystemDigits:  "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669",
		minimumGroupingDigits:  1,
		symbols:                map[string]string{"decimal": "\u066b", "group": "\u066c", "plusSign": "\u061c+", "minusSign": "\u061c-", "percentSign": "\u066a\u061c", "perMille": "\u0609", "approximatelySign": "~"},
		decimalPattern:         "#,##0.###",
		percentPattern:         "#,##0%",
		currencyPattern:        "\u200f#,##0.00\u00a0\u00a4",
		currencySpacing:        cldrNumberCurrencySpacing{beforeCurrency: "\u00a0", afterCurrency: "\u00a0"},
		currencies:             map[string]cldrNumberCurrencyData{"USD": {symbol: "US$", narrowSymbol: "US$", displayName: "\u062f\u0648\u0644\u0627\u0631 \u0623\u0645\u0631\u064a\u0643\u064a"}, "EUR": {symbol: "\u20ac", narrowSymbol: "\u20ac", displayName: "\u064a\u0648\u0631\u0648"}, "JPY": {symbol: "JP\u00a5", narrowSymbol: "JP\u00a5", displayName: "\u064a\u0646 \u064a\u0627\u0628\u0627\u0646\u064a"}, "GBP": {symbol: "UK\u00a3", narrowSymbol: "UK\u00a3", displayName: "\u062c\u0646\u064a\u0647 \u0625\u0633\u062a\u0631\u0644\u064a\u0646\u064a"}},
	},
}
