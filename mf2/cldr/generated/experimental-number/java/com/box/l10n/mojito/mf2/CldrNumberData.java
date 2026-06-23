package com.box.l10n.mojito.mf2;

import static java.util.Map.entry;

import java.util.Map;

// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.
final class CldrNumberData {
    private CldrNumberData() {}

    record CurrencyFraction(int digits, int rounding, String source) {}

    record CurrencyData(String symbol, String narrowSymbol, String displayName) {}

    record CurrencySpacing(String beforeCurrency, String afterCurrency) {}

    record LocaleData(
            String requestedLocale,
            String numbersSourceLocale,
            String currenciesSourceLocale,
            String numberingSystem,
            String numberingSystemDigits,
            int minimumGroupingDigits,
            Map<String, String> symbols,
            String decimalPattern,
            String percentPattern,
            String currencyPattern,
            CurrencySpacing currencySpacing,
            Map<String, CurrencyData> currencies) {}

    static final Map<String, CurrencyFraction> CURRENCY_FRACTIONS = Map.ofEntries(
            entry("DEFAULT", new CurrencyFraction(2, 0, "DEFAULT")),
            entry("USD", new CurrencyFraction(2, 0, "DEFAULT")),
            entry("EUR", new CurrencyFraction(2, 0, "DEFAULT")),
            entry("JPY", new CurrencyFraction(0, 0, "JPY")),
            entry("GBP", new CurrencyFraction(2, 0, "DEFAULT"))
    );

    static final Map<String, LocaleData> LOCALES = Map.ofEntries(
            entry("en-US", new LocaleData("en-US", "en", "en", "latn", "0123456789", 1, Map.ofEntries(entry("decimal", "."), entry("group", ","), entry("plusSign", "+"), entry("minusSign", "-"), entry("percentSign", "%"), entry("perMille", "\u2030"), entry("approximatelySign", "~")), "#,##0.###", "#,##0%", "\u00a4#,##0.00", new CurrencySpacing("\u00a0", "\u00a0"), Map.ofEntries(entry("USD", new CurrencyData("$", "$", "US Dollar")), entry("EUR", new CurrencyData("\u20ac", "\u20ac", "Euro")), entry("JPY", new CurrencyData("\u00a5", "\u00a5", "Japanese Yen")), entry("GBP", new CurrencyData("\u00a3", "\u00a3", "British Pound"))))),
            entry("fr-FR", new LocaleData("fr-FR", "fr", "fr", "latn", "0123456789", 1, Map.ofEntries(entry("decimal", ","), entry("group", "\u202f"), entry("plusSign", "+"), entry("minusSign", "-"), entry("percentSign", "%"), entry("perMille", "\u2030"), entry("approximatelySign", "\u2243")), "#,##0.###", "#,##0\u00a0%", "#,##0.00\u00a0\u00a4", new CurrencySpacing("\u00a0", "\u00a0"), Map.ofEntries(entry("USD", new CurrencyData("$US", "$", "dollar des \u00c9tats-Unis")), entry("EUR", new CurrencyData("\u20ac", "\u20ac", "euro")), entry("JPY", new CurrencyData("JPY", "\u00a5", "yen japonais")), entry("GBP", new CurrencyData("\u00a3GB", "\u00a3", "livre sterling"))))),
            entry("de-DE", new LocaleData("de-DE", "de", "de", "latn", "0123456789", 1, Map.ofEntries(entry("decimal", ","), entry("group", "."), entry("plusSign", "+"), entry("minusSign", "-"), entry("percentSign", "%"), entry("perMille", "\u2030"), entry("approximatelySign", "\u2248")), "#,##0.###", "#,##0\u00a0%", "#,##0.00\u00a0\u00a4", new CurrencySpacing("\u00a0", "\u00a0"), Map.ofEntries(entry("USD", new CurrencyData("$", "$", "US-Dollar")), entry("EUR", new CurrencyData("\u20ac", "\u20ac", "Euro")), entry("JPY", new CurrencyData("\u00a5", "\u00a5", "Japanischer Yen")), entry("GBP", new CurrencyData("\u00a3", "\u00a3", "Britisches Pfund"))))),
            entry("ja-JP", new LocaleData("ja-JP", "ja", "ja", "latn", "0123456789", 1, Map.ofEntries(entry("decimal", "."), entry("group", ","), entry("plusSign", "+"), entry("minusSign", "-"), entry("percentSign", "%"), entry("perMille", "\u2030"), entry("approximatelySign", "\u7d04")), "#,##0.###", "#,##0%", "\u00a4#,##0.00", new CurrencySpacing("\u00a0", "\u00a0"), Map.ofEntries(entry("USD", new CurrencyData("$", "$", "\u7c73\u30c9\u30eb")), entry("EUR", new CurrencyData("\u20ac", "\u20ac", "\u30e6\u30fc\u30ed")), entry("JPY", new CurrencyData("\uffe5", "\uffe5", "\u65e5\u672c\u5186")), entry("GBP", new CurrencyData("\u00a3", "\u00a3", "\u82f1\u56fd\u30dd\u30f3\u30c9"))))),
            entry("ar-EG", new LocaleData("ar-EG", "ar-EG", "ar-EG", "arab", "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669", 1, Map.ofEntries(entry("decimal", "\u066b"), entry("group", "\u066c"), entry("plusSign", "\u061c+"), entry("minusSign", "\u061c-"), entry("percentSign", "\u066a\u061c"), entry("perMille", "\u0609"), entry("approximatelySign", "~")), "#,##0.###", "#,##0%", "\u200f#,##0.00\u00a0\u00a4", new CurrencySpacing("\u00a0", "\u00a0"), Map.ofEntries(entry("USD", new CurrencyData("US$", "US$", "\u062f\u0648\u0644\u0627\u0631 \u0623\u0645\u0631\u064a\u0643\u064a")), entry("EUR", new CurrencyData("\u20ac", "\u20ac", "\u064a\u0648\u0631\u0648")), entry("JPY", new CurrencyData("JP\u00a5", "JP\u00a5", "\u064a\u0646 \u064a\u0627\u0628\u0627\u0646\u064a")), entry("GBP", new CurrencyData("UK\u00a3", "UK\u00a3", "\u062c\u0646\u064a\u0647 \u0625\u0633\u062a\u0631\u0644\u064a\u0646\u064a")))))
    );
}
