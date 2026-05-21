#[allow(dead_code)]
#[path = "../../../cldr/generated/all/rust/plural_rules.rs"]
mod generated_plural_rules;

pub use generated_plural_rules::NumberOperands;

pub fn select_cardinal_plural_category(locale: &str, operands: NumberOperands) -> &'static str {
    generated_plural_rules::select_cardinal(locale, operands)
}

pub fn select_ordinal_plural_category(locale: &str, operands: NumberOperands) -> &'static str {
    generated_plural_rules::select_ordinal(locale, operands)
}
