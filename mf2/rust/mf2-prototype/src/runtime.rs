use std::collections::BTreeMap;

use crate::cldr::{select_cardinal_plural_category, NumberOperands};
use crate::diagnostic::Diagnostic;
use crate::model::{
    Declaration, Expression, ExpressionArg, MessageModel, PatternPart, VariableRef, Variant,
    VariantKey,
};

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
    let mut context = FormatContext::new(arguments, locale);
    context.apply_declarations(model.declarations())?;
    match model {
        MessageModel::Message { pattern, .. } => context.format_pattern(pattern),
        MessageModel::Select {
            selectors,
            variants,
            ..
        } => context.format_select(selectors, variants),
    }
}

struct FormatContext {
    values: BTreeMap<String, serde_json::Value>,
    input_functions: BTreeMap<String, String>,
    locale: String,
}

impl FormatContext {
    fn new(arguments: &BTreeMap<String, serde_json::Value>, locale: &str) -> Self {
        Self {
            values: arguments.clone(),
            input_functions: BTreeMap::new(),
            locale: locale.to_string(),
        }
    }

    fn apply_declarations(&mut self, declarations: &[Declaration]) -> Result<(), Diagnostic> {
        for declaration in declarations {
            match declaration {
                Declaration::Input { name, value } => {
                    if let Some(function) = &value.function {
                        self.input_functions
                            .insert(name.clone(), function.name.clone());
                    }
                }
                Declaration::Local { name, value } => {
                    let rendered = self.format_expression(value)?;
                    self.values
                        .insert(name.clone(), serde_json::Value::String(rendered));
                }
            }
        }
        Ok(())
    }

    fn format_select(
        &self,
        selectors: &[VariableRef],
        variants: &[Variant],
    ) -> Result<String, Diagnostic> {
        let selector_values = selectors
            .iter()
            .map(|selector| {
                let value = self
                    .values
                    .get(&selector.name)
                    .ok_or_else(|| missing_argument(&selector.name))?;
                Ok(SelectorValue {
                    rendered: value_to_string(value),
                    plural_category: self.plural_category_for_selector(&selector.name, value),
                })
            })
            .collect::<Result<Vec<_>, _>>()?;

        let fallback = variants.iter().find(|variant| {
            variant
                .keys
                .iter()
                .all(|key| matches!(key, VariantKey::CatchAll))
        });
        let selected = variants
            .iter()
            .find(|variant| variant_matches(variant, &selector_values))
            .or(fallback)
            .ok_or_else(|| {
                Diagnostic::new(
                    "missing-select-variant",
                    "No select variant matched and no catch-all variant is present.",
                    0,
                    0,
                )
            })?;

        self.format_pattern(&selected.value)
    }

    fn format_pattern(&self, pattern: &[PatternPart]) -> Result<String, Diagnostic> {
        let mut output = String::new();
        for part in pattern {
            match part {
                PatternPart::Text(text) => output.push_str(text),
                PatternPart::Expression(expression) => {
                    output.push_str(&self.format_expression(expression)?);
                }
                PatternPart::Markup(_) => {}
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

        match expression
            .function
            .as_ref()
            .map(|function| function.name.as_str())
        {
            None | Some("string") | Some("number") | Some("datetime") | Some("date")
            | Some("time") => Ok(value),
            Some(name) => Err(Diagnostic::new(
                "unsupported-function-format",
                format!("Function :{name} is not supported by this prototype formatter."),
                0,
                0,
            )),
        }
    }

    fn plural_category_for_selector(
        &self,
        selector_name: &str,
        value: &serde_json::Value,
    ) -> Option<String> {
        let function = self.input_functions.get(selector_name)?;
        if function != "number" {
            return None;
        }
        let operands = NumberOperands::from_json(value)?;
        Some(select_cardinal_plural_category(&self.locale, operands).to_string())
    }
}

#[derive(Debug, Clone)]
struct SelectorValue {
    rendered: String,
    plural_category: Option<String>,
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
                value == &selector.rendered
                    || selector
                        .plural_category
                        .as_ref()
                        .is_some_and(|category| category == value)
            }
        })
}

fn missing_argument(name: &str) -> Diagnostic {
    Diagnostic::new(
        "missing-argument",
        format!("Missing argument ${name}."),
        0,
        0,
    )
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
