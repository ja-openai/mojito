package com.box.l10n.mojito.mf2

// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.
internal object CldrNumberData {
    data class CurrencyFraction(val digits: Int, val rounding: Int, val source: String)

    data class CurrencyData(val symbol: String?, val narrowSymbol: String?, val displayName: String?)

    data class CurrencySpacing(val beforeCurrency: String, val afterCurrency: String)

    data class LocaleData(
        val requestedLocale: String,
        val numbersSourceLocale: String,
        val currenciesSourceLocale: String,
        val numberingSystem: String,
        val numberingSystemDigits: String?,
        val minimumGroupingDigits: Int,
        val symbols: Map<String, String>,
        val decimalPattern: String,
        val percentPattern: String,
        val currencyPattern: String,
        val currencySpacing: CurrencySpacing,
        val currencies: Map<String, CurrencyData>,
    )

    val currencyFractions: Map<String, CurrencyFraction> = mapOf(
        "DEFAULT" to CurrencyFraction(2, 0, "DEFAULT"),
        "USD" to CurrencyFraction(2, 0, "DEFAULT"),
        "EUR" to CurrencyFraction(2, 0, "DEFAULT"),
        "JPY" to CurrencyFraction(0, 0, "JPY"),
        "GBP" to CurrencyFraction(2, 0, "DEFAULT")
    )

    val locales: Map<String, LocaleData> = mapOf(
        "en-US" to LocaleData(
            requestedLocale = "en-US",
            numbersSourceLocale = "en",
            currenciesSourceLocale = "en",
            numberingSystem = "latn",
            numberingSystemDigits = "0123456789",
            minimumGroupingDigits = 1,
            symbols = mapOf("decimal" to ".", "group" to ",", "plusSign" to "+", "minusSign" to "-", "percentSign" to "%", "perMille" to "\u2030", "approximatelySign" to "~"),
            decimalPattern = "#,##0.###",
            percentPattern = "#,##0%",
            currencyPattern = "\u00a4#,##0.00",
            currencySpacing = CurrencySpacing("\u00a0", "\u00a0"),
            currencies = mapOf("USD" to CurrencyData("\$", "\$", "US Dollar"), "EUR" to CurrencyData("\u20ac", "\u20ac", "Euro"), "JPY" to CurrencyData("\u00a5", "\u00a5", "Japanese Yen"), "GBP" to CurrencyData("\u00a3", "\u00a3", "British Pound")),
        ),
        "fr-FR" to LocaleData(
            requestedLocale = "fr-FR",
            numbersSourceLocale = "fr",
            currenciesSourceLocale = "fr",
            numberingSystem = "latn",
            numberingSystemDigits = "0123456789",
            minimumGroupingDigits = 1,
            symbols = mapOf("decimal" to ",", "group" to "\u202f", "plusSign" to "+", "minusSign" to "-", "percentSign" to "%", "perMille" to "\u2030", "approximatelySign" to "\u2243"),
            decimalPattern = "#,##0.###",
            percentPattern = "#,##0\u00a0%",
            currencyPattern = "#,##0.00\u00a0\u00a4",
            currencySpacing = CurrencySpacing("\u00a0", "\u00a0"),
            currencies = mapOf("USD" to CurrencyData("\$US", "\$", "dollar des \u00c9tats-Unis"), "EUR" to CurrencyData("\u20ac", "\u20ac", "euro"), "JPY" to CurrencyData("JPY", "\u00a5", "yen japonais"), "GBP" to CurrencyData("\u00a3GB", "\u00a3", "livre sterling")),
        ),
        "de-DE" to LocaleData(
            requestedLocale = "de-DE",
            numbersSourceLocale = "de",
            currenciesSourceLocale = "de",
            numberingSystem = "latn",
            numberingSystemDigits = "0123456789",
            minimumGroupingDigits = 1,
            symbols = mapOf("decimal" to ",", "group" to ".", "plusSign" to "+", "minusSign" to "-", "percentSign" to "%", "perMille" to "\u2030", "approximatelySign" to "\u2248"),
            decimalPattern = "#,##0.###",
            percentPattern = "#,##0\u00a0%",
            currencyPattern = "#,##0.00\u00a0\u00a4",
            currencySpacing = CurrencySpacing("\u00a0", "\u00a0"),
            currencies = mapOf("USD" to CurrencyData("\$", "\$", "US-Dollar"), "EUR" to CurrencyData("\u20ac", "\u20ac", "Euro"), "JPY" to CurrencyData("\u00a5", "\u00a5", "Japanischer Yen"), "GBP" to CurrencyData("\u00a3", "\u00a3", "Britisches Pfund")),
        ),
        "ja-JP" to LocaleData(
            requestedLocale = "ja-JP",
            numbersSourceLocale = "ja",
            currenciesSourceLocale = "ja",
            numberingSystem = "latn",
            numberingSystemDigits = "0123456789",
            minimumGroupingDigits = 1,
            symbols = mapOf("decimal" to ".", "group" to ",", "plusSign" to "+", "minusSign" to "-", "percentSign" to "%", "perMille" to "\u2030", "approximatelySign" to "\u7d04"),
            decimalPattern = "#,##0.###",
            percentPattern = "#,##0%",
            currencyPattern = "\u00a4#,##0.00",
            currencySpacing = CurrencySpacing("\u00a0", "\u00a0"),
            currencies = mapOf("USD" to CurrencyData("\$", "\$", "\u7c73\u30c9\u30eb"), "EUR" to CurrencyData("\u20ac", "\u20ac", "\u30e6\u30fc\u30ed"), "JPY" to CurrencyData("\uffe5", "\uffe5", "\u65e5\u672c\u5186"), "GBP" to CurrencyData("\u00a3", "\u00a3", "\u82f1\u56fd\u30dd\u30f3\u30c9")),
        ),
        "ar-EG" to LocaleData(
            requestedLocale = "ar-EG",
            numbersSourceLocale = "ar-EG",
            currenciesSourceLocale = "ar-EG",
            numberingSystem = "arab",
            numberingSystemDigits = "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669",
            minimumGroupingDigits = 1,
            symbols = mapOf("decimal" to "\u066b", "group" to "\u066c", "plusSign" to "\u061c+", "minusSign" to "\u061c-", "percentSign" to "\u066a\u061c", "perMille" to "\u0609", "approximatelySign" to "~"),
            decimalPattern = "#,##0.###",
            percentPattern = "#,##0%",
            currencyPattern = "\u200f#,##0.00\u00a0\u00a4",
            currencySpacing = CurrencySpacing("\u00a0", "\u00a0"),
            currencies = mapOf("USD" to CurrencyData("US\$", "US\$", "\u062f\u0648\u0644\u0627\u0631 \u0623\u0645\u0631\u064a\u0643\u064a"), "EUR" to CurrencyData("\u20ac", "\u20ac", "\u064a\u0648\u0631\u0648"), "JPY" to CurrencyData("JP\u00a5", "JP\u00a5", "\u064a\u0646 \u064a\u0627\u0628\u0627\u0646\u064a"), "GBP" to CurrencyData("UK\u00a3", "UK\u00a3", "\u062c\u0646\u064a\u0647 \u0625\u0633\u062a\u0631\u0644\u064a\u0646\u064a")),
        )
    )
}
