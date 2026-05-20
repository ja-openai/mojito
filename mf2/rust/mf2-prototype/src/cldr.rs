#[allow(dead_code)]
#[path = "../../../cldr/generated/all/rust/plural_rules.rs"]
mod generated_plural_rules;

pub(crate) use generated_plural_rules::NumberOperands;

impl NumberOperands {
    pub(crate) fn from_json(value: &serde_json::Value) -> Option<Self> {
        match value {
            serde_json::Value::Number(number) => Self::from_str(&number.to_string()),
            serde_json::Value::String(value) => Self::from_str(value),
            _ => None,
        }
    }
}

pub(crate) fn select_cardinal_plural_category(
    locale: &str,
    operands: NumberOperands,
) -> &'static str {
    generated_plural_rules::select_cardinal(locale, operands)
}

pub(crate) fn select_ordinal_plural_category(
    locale: &str,
    operands: NumberOperands,
) -> &'static str {
    generated_plural_rules::select_ordinal(locale, operands)
}
