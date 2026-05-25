pub use crate::cldr_plural_rules::NumberOperands;

pub fn select_cardinal_plural_category(locale: &str, operands: NumberOperands) -> &'static str {
    crate::cldr_plural_rules::select_cardinal(locale, operands)
}

pub fn select_ordinal_plural_category(locale: &str, operands: NumberOperands) -> &'static str {
    crate::cldr_plural_rules::select_ordinal(locale, operands)
}
