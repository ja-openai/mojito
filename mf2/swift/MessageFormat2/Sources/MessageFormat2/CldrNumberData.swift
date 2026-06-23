// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.
import Foundation

enum CldrNumberData {
    struct CurrencyFraction {
        let digits: Int
        let rounding: Int
        let source: String
    }

    struct CurrencyData {
        let symbol: String?
        let narrowSymbol: String?
        let displayName: String?
    }

    struct CurrencySpacing {
        let beforeCurrency: String
        let afterCurrency: String
    }

    struct LocaleData {
        let requestedLocale: String
        let numbersSourceLocale: String
        let currenciesSourceLocale: String
        let numberingSystem: String
        let numberingSystemDigits: String?
        let minimumGroupingDigits: Int
        let symbols: [String: String]
        let decimalPattern: String
        let percentPattern: String
        let currencyPattern: String
        let currencySpacing: CurrencySpacing
        let currencies: [String: CurrencyData]
    }

    static let currencyFractions: [String: CurrencyFraction] = [
        "DEFAULT": CurrencyFraction(digits: 2, rounding: 0, source: "DEFAULT"),
        "USD": CurrencyFraction(digits: 2, rounding: 0, source: "DEFAULT"),
        "EUR": CurrencyFraction(digits: 2, rounding: 0, source: "DEFAULT"),
        "JPY": CurrencyFraction(digits: 0, rounding: 0, source: "JPY"),
        "GBP": CurrencyFraction(digits: 2, rounding: 0, source: "DEFAULT")
    ]

    static let locales: [String: LocaleData] = [
        "en-US": LocaleData(
            requestedLocale: "en-US",
            numbersSourceLocale: "en",
            currenciesSourceLocale: "en",
            numberingSystem: "latn",
            numberingSystemDigits: "0123456789",
            minimumGroupingDigits: 1,
            symbols: ["decimal": ".", "group": ",", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u{2030}", "approximatelySign": "~"],
            decimalPattern: "#,##0.###",
            percentPattern: "#,##0%",
            currencyPattern: "\u{a4}#,##0.00",
            currencySpacing: CurrencySpacing(beforeCurrency: "\u{a0}", afterCurrency: "\u{a0}"),
            currencies: ["USD": CurrencyData(symbol: "$", narrowSymbol: "$", displayName: "US Dollar"), "EUR": CurrencyData(symbol: "\u{20ac}", narrowSymbol: "\u{20ac}", displayName: "Euro"), "JPY": CurrencyData(symbol: "\u{a5}", narrowSymbol: "\u{a5}", displayName: "Japanese Yen"), "GBP": CurrencyData(symbol: "\u{a3}", narrowSymbol: "\u{a3}", displayName: "British Pound")]
        ),
        "fr-FR": LocaleData(
            requestedLocale: "fr-FR",
            numbersSourceLocale: "fr",
            currenciesSourceLocale: "fr",
            numberingSystem: "latn",
            numberingSystemDigits: "0123456789",
            minimumGroupingDigits: 1,
            symbols: ["decimal": ",", "group": "\u{202f}", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u{2030}", "approximatelySign": "\u{2243}"],
            decimalPattern: "#,##0.###",
            percentPattern: "#,##0\u{a0}%",
            currencyPattern: "#,##0.00\u{a0}\u{a4}",
            currencySpacing: CurrencySpacing(beforeCurrency: "\u{a0}", afterCurrency: "\u{a0}"),
            currencies: ["USD": CurrencyData(symbol: "$US", narrowSymbol: "$", displayName: "dollar des \u{c9}tats-Unis"), "EUR": CurrencyData(symbol: "\u{20ac}", narrowSymbol: "\u{20ac}", displayName: "euro"), "JPY": CurrencyData(symbol: "JPY", narrowSymbol: "\u{a5}", displayName: "yen japonais"), "GBP": CurrencyData(symbol: "\u{a3}GB", narrowSymbol: "\u{a3}", displayName: "livre sterling")]
        ),
        "de-DE": LocaleData(
            requestedLocale: "de-DE",
            numbersSourceLocale: "de",
            currenciesSourceLocale: "de",
            numberingSystem: "latn",
            numberingSystemDigits: "0123456789",
            minimumGroupingDigits: 1,
            symbols: ["decimal": ",", "group": ".", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u{2030}", "approximatelySign": "\u{2248}"],
            decimalPattern: "#,##0.###",
            percentPattern: "#,##0\u{a0}%",
            currencyPattern: "#,##0.00\u{a0}\u{a4}",
            currencySpacing: CurrencySpacing(beforeCurrency: "\u{a0}", afterCurrency: "\u{a0}"),
            currencies: ["USD": CurrencyData(symbol: "$", narrowSymbol: "$", displayName: "US-Dollar"), "EUR": CurrencyData(symbol: "\u{20ac}", narrowSymbol: "\u{20ac}", displayName: "Euro"), "JPY": CurrencyData(symbol: "\u{a5}", narrowSymbol: "\u{a5}", displayName: "Japanischer Yen"), "GBP": CurrencyData(symbol: "\u{a3}", narrowSymbol: "\u{a3}", displayName: "Britisches Pfund")]
        ),
        "ja-JP": LocaleData(
            requestedLocale: "ja-JP",
            numbersSourceLocale: "ja",
            currenciesSourceLocale: "ja",
            numberingSystem: "latn",
            numberingSystemDigits: "0123456789",
            minimumGroupingDigits: 1,
            symbols: ["decimal": ".", "group": ",", "plusSign": "+", "minusSign": "-", "percentSign": "%", "perMille": "\u{2030}", "approximatelySign": "\u{7d04}"],
            decimalPattern: "#,##0.###",
            percentPattern: "#,##0%",
            currencyPattern: "\u{a4}#,##0.00",
            currencySpacing: CurrencySpacing(beforeCurrency: "\u{a0}", afterCurrency: "\u{a0}"),
            currencies: ["USD": CurrencyData(symbol: "$", narrowSymbol: "$", displayName: "\u{7c73}\u{30c9}\u{30eb}"), "EUR": CurrencyData(symbol: "\u{20ac}", narrowSymbol: "\u{20ac}", displayName: "\u{30e6}\u{30fc}\u{30ed}"), "JPY": CurrencyData(symbol: "\u{ffe5}", narrowSymbol: "\u{ffe5}", displayName: "\u{65e5}\u{672c}\u{5186}"), "GBP": CurrencyData(symbol: "\u{a3}", narrowSymbol: "\u{a3}", displayName: "\u{82f1}\u{56fd}\u{30dd}\u{30f3}\u{30c9}")]
        ),
        "ar-EG": LocaleData(
            requestedLocale: "ar-EG",
            numbersSourceLocale: "ar-EG",
            currenciesSourceLocale: "ar-EG",
            numberingSystem: "arab",
            numberingSystemDigits: "\u{660}\u{661}\u{662}\u{663}\u{664}\u{665}\u{666}\u{667}\u{668}\u{669}",
            minimumGroupingDigits: 1,
            symbols: ["decimal": "\u{66b}", "group": "\u{66c}", "plusSign": "\u{61c}+", "minusSign": "\u{61c}-", "percentSign": "\u{66a}\u{61c}", "perMille": "\u{609}", "approximatelySign": "~"],
            decimalPattern: "#,##0.###",
            percentPattern: "#,##0%",
            currencyPattern: "\u{200f}#,##0.00\u{a0}\u{a4}",
            currencySpacing: CurrencySpacing(beforeCurrency: "\u{a0}", afterCurrency: "\u{a0}"),
            currencies: ["USD": CurrencyData(symbol: "US$", narrowSymbol: "US$", displayName: "\u{62f}\u{648}\u{644}\u{627}\u{631} \u{623}\u{645}\u{631}\u{64a}\u{643}\u{64a}"), "EUR": CurrencyData(symbol: "\u{20ac}", narrowSymbol: "\u{20ac}", displayName: "\u{64a}\u{648}\u{631}\u{648}"), "JPY": CurrencyData(symbol: "JP\u{a5}", narrowSymbol: "JP\u{a5}", displayName: "\u{64a}\u{646} \u{64a}\u{627}\u{628}\u{627}\u{646}\u{64a}"), "GBP": CurrencyData(symbol: "UK\u{a3}", narrowSymbol: "UK\u{a3}", displayName: "\u{62c}\u{646}\u{64a}\u{647} \u{625}\u{633}\u{62a}\u{631}\u{644}\u{64a}\u{646}\u{64a}")]
        )
    ]
}
