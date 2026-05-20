use std::collections::{BTreeMap, BTreeSet};

use crate::cldr::{
    select_cardinal_plural_category, select_ordinal_plural_category, NumberOperands,
};
use crate::diagnostic::Diagnostic;
use crate::model::{
    AttributeValue, Declaration, Expression, ExpressionArg, FunctionRef, Markup, MessageModel,
    PatternPart, VariableRef, Variant, VariantKey,
};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum FormattedPart {
    #[serde(rename = "text")]
    Text { value: String },
    #[serde(rename = "expression")]
    Expression {
        value: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attributes: Option<BTreeMap<String, AttributeValue>>,
    },
    #[serde(rename = "markup")]
    Markup {
        kind: String,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attributes: Option<BTreeMap<String, AttributeValue>>,
    },
}

impl FormattedPart {
    fn string_value(&self) -> Option<&str> {
        match self {
            Self::Text { value } | Self::Expression { value, .. } => Some(value),
            Self::Markup { .. } => None,
        }
    }
}

pub fn format_model(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
) -> Result<String, Diagnostic> {
    format_model_with_locale(model, arguments, "en")
}

pub fn format_model_with_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
) -> Result<String, Diagnostic> {
    Ok(parts_to_string(&format_model_to_parts_with_locale(
        model, arguments, locale,
    )?))
}

pub fn format_model_to_parts_with_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    validate_model(model)?;
    let mut context = FormatContext::new(arguments, locale);
    context.apply_declarations(model.declarations())?;
    match model {
        MessageModel::Message { pattern, .. } => context.format_pattern_to_parts(pattern),
        MessageModel::Select {
            selectors,
            variants,
            ..
        } => context.format_select_to_parts(selectors, variants),
    }
}

fn validate_model(model: &MessageModel) -> Result<(), Diagnostic> {
    validate_declarations(model.declarations())?;
    match model {
        MessageModel::Message { pattern, .. } => validate_pattern(pattern)?,
        MessageModel::Select {
            declarations,
            selectors,
            variants,
        } => {
            validate_selector_annotations(declarations, selectors)?;
            for variant in variants {
                validate_pattern(&variant.value)?;
            }
        }
    }
    Ok(())
}

fn validate_declarations(declarations: &[Declaration]) -> Result<(), Diagnostic> {
    let mut names = BTreeSet::new();
    for declaration in declarations {
        let name = match declaration {
            Declaration::Input { name, value } => {
                validate_input_declaration(name, value)?;
                name
            }
            Declaration::Local { name, .. } => name,
        };
        if !names.insert(name) {
            return Err(model_error(
                "duplicate-declaration",
                format!("Declaration ${name} is defined more than once."),
            ));
        }
    }
    Ok(())
}

fn validate_input_declaration(name: &str, value: &Expression) -> Result<(), Diagnostic> {
    match &value.arg {
        Some(ExpressionArg::Variable {
            name: variable_name,
        }) if variable_name == name => Ok(()),
        _ => Err(model_error(
            "invalid-input-declaration",
            format!("Input declaration ${name} must bind the same variable name."),
        )),
    }
}

fn validate_pattern(pattern: &[PatternPart]) -> Result<(), Diagnostic> {
    for part in pattern {
        match part {
            PatternPart::Text(text) if text.is_empty() => {
                return Err(model_error(
                    "invalid-pattern-text",
                    "Pattern text parts must be non-empty.",
                ));
            }
            PatternPart::Markup(markup) => validate_markup(markup)?,
            _ => {}
        }
    }
    Ok(())
}

fn validate_markup(markup: &Markup) -> Result<(), Diagnostic> {
    match markup.kind.as_str() {
        "open" | "standalone" | "close" => Ok(()),
        _ => Err(model_error(
            "invalid-markup-kind",
            "Markup kind must be open, standalone, or close.",
        )),
    }
}

fn validate_selector_annotations(
    declarations: &[Declaration],
    selectors: &[VariableRef],
) -> Result<(), Diagnostic> {
    let annotations = selector_annotations(declarations);
    for selector in selectors {
        if !annotations.contains_key(&selector.name) {
            return Err(model_error(
                "missing-selector-annotation",
                format!(
                    "Selector ${} must reference a declaration with a function.",
                    selector.name
                ),
            ));
        }
    }
    Ok(())
}

fn selector_annotations(declarations: &[Declaration]) -> BTreeMap<String, SelectorAnnotation> {
    let mut annotations = BTreeMap::new();
    for declaration in declarations {
        let (name, value) = declaration_name_value(declaration);
        if let Some(function) = &value.function {
            annotations.insert(
                name.to_string(),
                SelectorAnnotation::from_function(function),
            );
        }
    }

    let mut changed = true;
    while changed {
        changed = false;
        for declaration in declarations {
            let (name, value) = declaration_name_value(declaration);
            if annotations.contains_key(name) {
                continue;
            }
            let Some(ExpressionArg::Variable { name: source }) = &value.arg else {
                continue;
            };
            let Some(annotation) = annotations.get(source).cloned() else {
                continue;
            };
            annotations.insert(name.to_string(), annotation);
            changed = true;
        }
    }

    annotations
}

fn declaration_name_value(declaration: &Declaration) -> (&str, &Expression) {
    match declaration {
        Declaration::Input { name, value } | Declaration::Local { name, value } => (name, value),
    }
}

struct FormatContext {
    values: BTreeMap<String, serde_json::Value>,
    selector_annotations: BTreeMap<String, SelectorAnnotation>,
    locale: String,
}

impl FormatContext {
    fn new(arguments: &BTreeMap<String, serde_json::Value>, locale: &str) -> Self {
        Self {
            values: arguments.clone(),
            selector_annotations: BTreeMap::new(),
            locale: locale.to_string(),
        }
    }

    fn apply_declarations(&mut self, declarations: &[Declaration]) -> Result<(), Diagnostic> {
        self.selector_annotations = selector_annotations(declarations);
        for declaration in declarations {
            match declaration {
                Declaration::Input { .. } => {}
                Declaration::Local { name, value } => {
                    let rendered = self.format_expression(value)?;
                    self.values
                        .insert(name.clone(), serde_json::Value::String(rendered));
                }
            }
        }
        Ok(())
    }

    fn format_select_to_parts(
        &self,
        selectors: &[VariableRef],
        variants: &[Variant],
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        let selector_values = selectors
            .iter()
            .map(|selector| {
                let value = self
                    .values
                    .get(&selector.name)
                    .ok_or_else(|| missing_argument(&selector.name))?;
                Ok(SelectorValue {
                    rendered: value_to_string(value),
                    exact_match: self.exact_match_for_selector(&selector.name),
                    selection_key: self.selection_key_for_selector(&selector.name, value),
                })
            })
            .collect::<Result<Vec<_>, _>>()?;

        let mut signatures = BTreeSet::new();
        let mut fallback = None;
        let mut selected = None;
        for variant in variants {
            validate_variant(variant, selector_values.len(), &mut signatures)?;
            if fallback.is_none() && is_fallback_variant(variant) {
                fallback = Some(variant);
            }
            if selected.is_none() && variant_matches(variant, &selector_values) {
                selected = Some(variant);
            }
        }
        let fallback = fallback.ok_or_else(|| {
            model_error(
                "missing-fallback-variant",
                "Select messages must include a catch-all fallback variant.",
            )
        })?;
        let selected = selected.or(Some(fallback)).ok_or_else(|| {
            Diagnostic::new(
                "missing-select-variant",
                "No select variant matched and no catch-all variant is present.",
                0,
                0,
            )
        })?;

        self.format_pattern_to_parts(&selected.value)
    }

    fn format_pattern_to_parts(
        &self,
        pattern: &[PatternPart],
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        let mut output = Vec::new();
        for part in pattern {
            match part {
                PatternPart::Text(text) => output.push(FormattedPart::Text {
                    value: text.clone(),
                }),
                PatternPart::Expression(expression) => {
                    output.push(FormattedPart::Expression {
                        value: self.format_expression(expression)?,
                        attributes: expression.attributes.clone(),
                    });
                }
                PatternPart::Markup(markup) => output.push(markup_to_part(markup)),
            }
        }
        Ok(output)
    }

    fn format_expression(&self, expression: &Expression) -> Result<String, Diagnostic> {
        let value = match &expression.arg {
            Some(ExpressionArg::Variable { name }) => self
                .values
                .get(name)
                .map(value_to_string)
                .ok_or_else(|| missing_argument(name))?,
            Some(ExpressionArg::Literal { value }) => value.clone(),
            None => String::new(),
        };

        let function = expression.function.as_ref();
        match function.map(|function| function.name.as_str()) {
            None | Some("string") | Some("number") | Some("datetime") | Some("date")
            | Some("time") => Ok(value),
            Some("currency") => self.format_currency(&value, function.expect("function exists")),
            Some(name) => Err(Diagnostic::new(
                "unsupported-function-format",
                format!("Function :{name} is not supported by this prototype formatter."),
                0,
                0,
            )),
        }
    }

    fn format_currency(&self, value: &str, function: &FunctionRef) -> Result<String, Diagnostic> {
        let currency = self
            .option_value(function, "currency")?
            .unwrap_or_else(|| "USD".to_string());
        format_currency_value(value, &currency, &self.locale)
    }

    fn option_value(
        &self,
        function: &FunctionRef,
        name: &str,
    ) -> Result<Option<String>, Diagnostic> {
        let Some(option) = function
            .options
            .as_ref()
            .and_then(|options| options.get(name))
        else {
            return Ok(None);
        };
        match option {
            ExpressionArg::Literal { value } => Ok(Some(value.clone())),
            ExpressionArg::Variable { name } => self
                .values
                .get(name)
                .map(value_to_string)
                .map(Some)
                .ok_or_else(|| missing_argument(name)),
        }
    }

    fn exact_match_for_selector(&self, selector_name: &str) -> bool {
        self.selector_annotations
            .get(selector_name)
            .map(|annotation| annotation.exact_match())
            .unwrap_or(true)
    }

    fn selection_key_for_selector(
        &self,
        selector_name: &str,
        value: &serde_json::Value,
    ) -> Option<String> {
        let annotation = self.selector_annotations.get(selector_name)?;
        if annotation.function != "number" {
            return None;
        }
        let operands = NumberOperands::from_json(value)?;
        annotation.selection_key(&self.locale, operands)
    }
}

#[derive(Debug, Clone)]
struct SelectorValue {
    rendered: String,
    exact_match: bool,
    selection_key: Option<String>,
}

#[derive(Debug, Clone)]
struct SelectorAnnotation {
    function: String,
    number_select: NumberSelect,
}

impl SelectorAnnotation {
    fn from_function(function: &crate::model::FunctionRef) -> Self {
        Self {
            function: function.name.clone(),
            number_select: NumberSelect::from_options(function.options.as_ref()),
        }
    }

    fn exact_match(&self) -> bool {
        self.function == "string"
            || (self.function == "number" && self.number_select == NumberSelect::Exact)
    }

    fn selection_key(&self, locale: &str, operands: NumberOperands) -> Option<String> {
        if self.function != "number" {
            return None;
        }
        match self.number_select {
            NumberSelect::Plural => {
                Some(select_cardinal_plural_category(locale, operands).to_string())
            }
            NumberSelect::Ordinal => {
                Some(select_ordinal_plural_category(locale, operands).to_string())
            }
            NumberSelect::Exact => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum NumberSelect {
    Plural,
    Ordinal,
    Exact,
}

impl NumberSelect {
    fn from_options(options: Option<&BTreeMap<String, ExpressionArg>>) -> Self {
        let Some(ExpressionArg::Literal { value }) =
            options.and_then(|options| options.get("select"))
        else {
            return Self::Plural;
        };
        match value.as_str() {
            "ordinal" => Self::Ordinal,
            "exact" => Self::Exact,
            _ => Self::Plural,
        }
    }
}

fn variant_matches(variant: &Variant, selector_values: &[SelectorValue]) -> bool {
    if variant.keys.len() != selector_values.len() {
        return false;
    }
    variant
        .keys
        .iter()
        .zip(selector_values)
        .all(|(key, selector)| match key {
            VariantKey::CatchAll => true,
            VariantKey::Literal { value } => {
                (selector.exact_match && value == &selector.rendered)
                    || selector
                        .selection_key
                        .as_ref()
                        .is_some_and(|category| category == value)
            }
        })
}

fn validate_variant<'a>(
    variant: &'a Variant,
    selector_count: usize,
    signatures: &mut BTreeSet<Vec<VariantKeySignature<'a>>>,
) -> Result<(), Diagnostic> {
    if variant.keys.len() != selector_count {
        return Err(model_error(
            "variant-key-count-mismatch",
            "Variant key count must match selector count.",
        ));
    }
    if !signatures.insert(variant_key_signature(&variant.keys)) {
        return Err(model_error(
            "duplicate-variant",
            "Select variants must have unique key tuples.",
        ));
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum VariantKeySignature<'a> {
    CatchAll,
    Literal(&'a str),
}

fn variant_key_signature(keys: &[VariantKey]) -> Vec<VariantKeySignature<'_>> {
    keys.iter()
        .map(|key| match key {
            VariantKey::CatchAll => VariantKeySignature::CatchAll,
            VariantKey::Literal { value } => VariantKeySignature::Literal(value),
        })
        .collect()
}

fn is_fallback_variant(variant: &Variant) -> bool {
    variant
        .keys
        .iter()
        .all(|key| matches!(key, VariantKey::CatchAll))
}

fn missing_argument(name: &str) -> Diagnostic {
    Diagnostic::new(
        "missing-argument",
        format!("Missing argument ${name}."),
        0,
        0,
    )
}

fn model_error(code: impl Into<String>, message: impl Into<String>) -> Diagnostic {
    Diagnostic::new(code, message, 0, 0)
}

fn value_to_string(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::String(value) => value.clone(),
        serde_json::Value::Number(value) => value.to_string(),
        serde_json::Value::Bool(value) => value.to_string(),
        serde_json::Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn markup_to_part(markup: &Markup) -> FormattedPart {
    FormattedPart::Markup {
        kind: markup.kind.clone(),
        name: markup.name.clone(),
        attributes: markup.attributes.clone(),
    }
}

fn parts_to_string(parts: &[FormattedPart]) -> String {
    parts
        .iter()
        .filter_map(FormattedPart::string_value)
        .collect()
}

fn format_currency_value(value: &str, currency: &str, locale: &str) -> Result<String, Diagnostic> {
    let amount = value.parse::<f64>().map_err(|_| {
        Diagnostic::new(
            "bad-operand",
            format!("Currency value must be numeric, got {value}."),
            0,
            0,
        )
    })?;
    if !amount.is_finite() {
        return Err(Diagnostic::new(
            "bad-operand",
            "Currency value must be finite.",
            0,
            0,
        ));
    }

    let currency = currency.to_ascii_uppercase();
    let fraction_digits = currency_fraction_digits(&currency);
    let scale = 10_i64.pow(fraction_digits);
    let rounded = (amount.abs() * scale as f64).round() as i64;
    let major = rounded / scale;
    let fraction = rounded % scale;
    let is_french = canonical_locale_prefix(locale) == "fr";
    let grouped = group_digits(&major.to_string(), if is_french { "\u{202f}" } else { "," });
    let number = if fraction_digits == 0 {
        grouped
    } else {
        format!(
            "{grouped}{}{fraction:0width$}",
            if is_french { "," } else { "." },
            width = fraction_digits as usize
        )
    };
    let symbol = currency_symbol(&currency, is_french);
    let negative = amount.is_sign_negative();

    if is_french {
        Ok(format!(
            "{}{} {}",
            if negative { "-" } else { "" },
            number,
            symbol
        ))
    } else if symbol.len() == 3 {
        Ok(format!(
            "{}{} {}",
            if negative { "-" } else { "" },
            symbol,
            number
        ))
    } else {
        Ok(format!(
            "{}{}{}",
            if negative { "-" } else { "" },
            symbol,
            number
        ))
    }
}

fn currency_fraction_digits(currency: &str) -> u32 {
    match currency {
        "JPY" | "KRW" => 0,
        _ => 2,
    }
}

fn currency_symbol(currency: &str, french: bool) -> String {
    match currency {
        "USD" if french => "$US".to_string(),
        "USD" => "$".to_string(),
        "EUR" => "€".to_string(),
        "JPY" => "¥".to_string(),
        "GBP" => "£".to_string(),
        _ => currency.to_string(),
    }
}

fn canonical_locale_prefix(locale: &str) -> String {
    locale
        .split(['-', '_'])
        .next()
        .unwrap_or("en")
        .to_ascii_lowercase()
}

fn group_digits(digits: &str, separator: &str) -> String {
    let mut output = String::new();
    for (index, ch) in digits.chars().rev().enumerate() {
        if index > 0 && index % 3 == 0 {
            output.push_str(separator);
        }
        output.push(ch);
    }
    output.chars().rev().collect()
}
