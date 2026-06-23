// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.
#[derive(Debug, Clone, Copy)]
pub(crate) struct CldrNumberCurrencyFraction {
    pub(crate) digits: usize,
    pub(crate) rounding: usize,
    pub(crate) source: &'static str,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct CldrNumberCurrencyData {
    pub(crate) symbol: &'static str,
    pub(crate) narrow_symbol: Option<&'static str>,
    pub(crate) display_name: Option<&'static str>,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct CldrNumberCurrencySpacing {
    pub(crate) before_currency: &'static str,
    pub(crate) after_currency: &'static str,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct CldrNumberLocaleData {
    pub(crate) requested_locale: &'static str,
    pub(crate) numbers_source_locale: &'static str,
    pub(crate) currencies_source_locale: &'static str,
    pub(crate) numbering_system: &'static str,
    pub(crate) numbering_system_digits: Option<&'static str>,
    pub(crate) minimum_grouping_digits: usize,
    pub(crate) symbols: &'static [(&'static str, &'static str)],
    pub(crate) decimal_pattern: &'static str,
    pub(crate) percent_pattern: &'static str,
    pub(crate) currency_pattern: &'static str,
    pub(crate) currency_spacing: CldrNumberCurrencySpacing,
    pub(crate) currencies: &'static [(&'static str, CldrNumberCurrencyData)],
}

pub(crate) const CLDR_NUMBER_DEFAULT_CURRENCY_SPACING: CldrNumberCurrencySpacing =
    CldrNumberCurrencySpacing {
        before_currency: "\u{a0}",
        after_currency: "\u{a0}",
    };

pub(crate) static CLDR_NUMBER_CURRENCY_FRACTIONS: &[(&str, CldrNumberCurrencyFraction)] = &[
    (
        "DEFAULT",
        CldrNumberCurrencyFraction {
            digits: 2,
            rounding: 0,
            source: "DEFAULT",
        },
    ),
    (
        "USD",
        CldrNumberCurrencyFraction {
            digits: 2,
            rounding: 0,
            source: "DEFAULT",
        },
    ),
    (
        "EUR",
        CldrNumberCurrencyFraction {
            digits: 2,
            rounding: 0,
            source: "DEFAULT",
        },
    ),
    (
        "JPY",
        CldrNumberCurrencyFraction {
            digits: 0,
            rounding: 0,
            source: "JPY",
        },
    ),
    (
        "GBP",
        CldrNumberCurrencyFraction {
            digits: 2,
            rounding: 0,
            source: "DEFAULT",
        },
    ),
];

pub(crate) static CLDR_NUMBER_LOCALES: &[(&str, CldrNumberLocaleData)] = &[
    ("en-US", CldrNumberLocaleData {
        requested_locale: "en-US",
        numbers_source_locale: "en",
        currencies_source_locale: "en",
        numbering_system: "latn",
        numbering_system_digits: Some("0123456789"),
        minimum_grouping_digits: 1,
        symbols: &[("decimal", "."), ("group", ","), ("plusSign", "+"), ("minusSign", "-"), ("percentSign", "%"), ("perMille", "\u{2030}"), ("approximatelySign", "~")],
        decimal_pattern: "#,##0.###",
        percent_pattern: "#,##0%",
        currency_pattern: "\u{a4}#,##0.00",
        currency_spacing: CLDR_NUMBER_DEFAULT_CURRENCY_SPACING,
        currencies: &[("USD", CldrNumberCurrencyData { symbol: "$", narrow_symbol: Some("$"), display_name: Some("US Dollar") }), ("EUR", CldrNumberCurrencyData { symbol: "\u{20ac}", narrow_symbol: Some("\u{20ac}"), display_name: Some("Euro") }), ("JPY", CldrNumberCurrencyData { symbol: "\u{a5}", narrow_symbol: Some("\u{a5}"), display_name: Some("Japanese Yen") }), ("GBP", CldrNumberCurrencyData { symbol: "\u{a3}", narrow_symbol: Some("\u{a3}"), display_name: Some("British Pound") })],
    }),
    ("fr-FR", CldrNumberLocaleData {
        requested_locale: "fr-FR",
        numbers_source_locale: "fr",
        currencies_source_locale: "fr",
        numbering_system: "latn",
        numbering_system_digits: Some("0123456789"),
        minimum_grouping_digits: 1,
        symbols: &[("decimal", ","), ("group", "\u{202f}"), ("plusSign", "+"), ("minusSign", "-"), ("percentSign", "%"), ("perMille", "\u{2030}"), ("approximatelySign", "\u{2243}")],
        decimal_pattern: "#,##0.###",
        percent_pattern: "#,##0\u{a0}%",
        currency_pattern: "#,##0.00\u{a0}\u{a4}",
        currency_spacing: CLDR_NUMBER_DEFAULT_CURRENCY_SPACING,
        currencies: &[("USD", CldrNumberCurrencyData { symbol: "$US", narrow_symbol: Some("$"), display_name: Some("dollar des \u{c9}tats-Unis") }), ("EUR", CldrNumberCurrencyData { symbol: "\u{20ac}", narrow_symbol: Some("\u{20ac}"), display_name: Some("euro") }), ("JPY", CldrNumberCurrencyData { symbol: "JPY", narrow_symbol: Some("\u{a5}"), display_name: Some("yen japonais") }), ("GBP", CldrNumberCurrencyData { symbol: "\u{a3}GB", narrow_symbol: Some("\u{a3}"), display_name: Some("livre sterling") })],
    }),
    ("de-DE", CldrNumberLocaleData {
        requested_locale: "de-DE",
        numbers_source_locale: "de",
        currencies_source_locale: "de",
        numbering_system: "latn",
        numbering_system_digits: Some("0123456789"),
        minimum_grouping_digits: 1,
        symbols: &[("decimal", ","), ("group", "."), ("plusSign", "+"), ("minusSign", "-"), ("percentSign", "%"), ("perMille", "\u{2030}"), ("approximatelySign", "\u{2248}")],
        decimal_pattern: "#,##0.###",
        percent_pattern: "#,##0\u{a0}%",
        currency_pattern: "#,##0.00\u{a0}\u{a4}",
        currency_spacing: CLDR_NUMBER_DEFAULT_CURRENCY_SPACING,
        currencies: &[("USD", CldrNumberCurrencyData { symbol: "$", narrow_symbol: Some("$"), display_name: Some("US-Dollar") }), ("EUR", CldrNumberCurrencyData { symbol: "\u{20ac}", narrow_symbol: Some("\u{20ac}"), display_name: Some("Euro") }), ("JPY", CldrNumberCurrencyData { symbol: "\u{a5}", narrow_symbol: Some("\u{a5}"), display_name: Some("Japanischer Yen") }), ("GBP", CldrNumberCurrencyData { symbol: "\u{a3}", narrow_symbol: Some("\u{a3}"), display_name: Some("Britisches Pfund") })],
    }),
    ("ja-JP", CldrNumberLocaleData {
        requested_locale: "ja-JP",
        numbers_source_locale: "ja",
        currencies_source_locale: "ja",
        numbering_system: "latn",
        numbering_system_digits: Some("0123456789"),
        minimum_grouping_digits: 1,
        symbols: &[("decimal", "."), ("group", ","), ("plusSign", "+"), ("minusSign", "-"), ("percentSign", "%"), ("perMille", "\u{2030}"), ("approximatelySign", "\u{7d04}")],
        decimal_pattern: "#,##0.###",
        percent_pattern: "#,##0%",
        currency_pattern: "\u{a4}#,##0.00",
        currency_spacing: CLDR_NUMBER_DEFAULT_CURRENCY_SPACING,
        currencies: &[("USD", CldrNumberCurrencyData { symbol: "$", narrow_symbol: Some("$"), display_name: Some("\u{7c73}\u{30c9}\u{30eb}") }), ("EUR", CldrNumberCurrencyData { symbol: "\u{20ac}", narrow_symbol: Some("\u{20ac}"), display_name: Some("\u{30e6}\u{30fc}\u{30ed}") }), ("JPY", CldrNumberCurrencyData { symbol: "\u{ffe5}", narrow_symbol: Some("\u{ffe5}"), display_name: Some("\u{65e5}\u{672c}\u{5186}") }), ("GBP", CldrNumberCurrencyData { symbol: "\u{a3}", narrow_symbol: Some("\u{a3}"), display_name: Some("\u{82f1}\u{56fd}\u{30dd}\u{30f3}\u{30c9}") })],
    }),
    ("ar-EG", CldrNumberLocaleData {
        requested_locale: "ar-EG",
        numbers_source_locale: "ar-EG",
        currencies_source_locale: "ar-EG",
        numbering_system: "arab",
        numbering_system_digits: Some("\u{660}\u{661}\u{662}\u{663}\u{664}\u{665}\u{666}\u{667}\u{668}\u{669}"),
        minimum_grouping_digits: 1,
        symbols: &[("decimal", "\u{66b}"), ("group", "\u{66c}"), ("plusSign", "\u{61c}+"), ("minusSign", "\u{61c}-"), ("percentSign", "\u{66a}\u{61c}"), ("perMille", "\u{609}"), ("approximatelySign", "~")],
        decimal_pattern: "#,##0.###",
        percent_pattern: "#,##0%",
        currency_pattern: "\u{200f}#,##0.00\u{a0}\u{a4}",
        currency_spacing: CLDR_NUMBER_DEFAULT_CURRENCY_SPACING,
        currencies: &[("USD", CldrNumberCurrencyData { symbol: "US$", narrow_symbol: Some("US$"), display_name: Some("\u{62f}\u{648}\u{644}\u{627}\u{631} \u{623}\u{645}\u{631}\u{64a}\u{643}\u{64a}") }), ("EUR", CldrNumberCurrencyData { symbol: "\u{20ac}", narrow_symbol: Some("\u{20ac}"), display_name: Some("\u{64a}\u{648}\u{631}\u{648}") }), ("JPY", CldrNumberCurrencyData { symbol: "JP\u{a5}", narrow_symbol: Some("JP\u{a5}"), display_name: Some("\u{64a}\u{646} \u{64a}\u{627}\u{628}\u{627}\u{646}\u{64a}") }), ("GBP", CldrNumberCurrencyData { symbol: "UK\u{a3}", narrow_symbol: Some("UK\u{a3}"), display_name: Some("\u{62c}\u{646}\u{64a}\u{647} \u{625}\u{633}\u{62a}\u{631}\u{644}\u{64a}\u{646}\u{64a}") })],
    }),
];
