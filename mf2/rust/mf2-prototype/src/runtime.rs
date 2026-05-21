use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

use crate::cldr::{
    select_cardinal_plural_category, select_ordinal_plural_category, NumberOperands,
};
use crate::diagnostic::Diagnostic;
use crate::model::{
    AttributeValue, Declaration, Expression, ExpressionArg, FunctionRef, Markup, MessageModel,
    PatternPart, VariableRef, Variant, VariantKey,
};
use serde::{Deserialize, Serialize};
use unicode_normalization::UnicodeNormalization;

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
        options: Option<BTreeMap<String, ExpressionArg>>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attributes: Option<BTreeMap<String, AttributeValue>>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BidiIsolation {
    None,
    Default,
}

impl BidiIsolation {
    pub fn from_name(value: Option<&str>) -> Self {
        match value {
            Some("default") => Self::Default,
            _ => Self::None,
        }
    }
}

pub type FunctionFormatter = for<'a> fn(FunctionCall<'a>) -> Result<String, Diagnostic>;

#[derive(Clone)]
pub struct FunctionRegistry {
    formatters: BTreeMap<String, FunctionFormatter>,
}

impl FunctionRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn empty() -> Self {
        Self {
            formatters: BTreeMap::new(),
        }
    }

    pub fn with_function(mut self, name: impl Into<String>, formatter: FunctionFormatter) -> Self {
        self.formatters.insert(name.into(), formatter);
        self
    }

    pub fn register(&mut self, name: impl Into<String>, formatter: FunctionFormatter) {
        self.formatters.insert(name.into(), formatter);
    }

    fn format(
        &self,
        value: &str,
        function: &FunctionRef,
        locale: &str,
        values: &BTreeMap<String, serde_json::Value>,
    ) -> Result<String, Diagnostic> {
        let Some(formatter) = self.formatters.get(&function.name) else {
            return Err(Diagnostic::new(
                "unsupported-function",
                format!(
                    "Function :{} is not supported by this formatter registry.",
                    function.name
                ),
                0,
                0,
            ));
        };
        formatter(FunctionCall {
            value,
            function,
            locale,
            values,
        })
    }
}

impl Default for FunctionRegistry {
    fn default() -> Self {
        let mut registry = Self::empty();
        for name in ["string", "number", "integer", "datetime", "date", "time"] {
            registry.register(name, passthrough_function);
        }
        registry
    }
}

pub struct FunctionCall<'a> {
    value: &'a str,
    function: &'a FunctionRef,
    locale: &'a str,
    values: &'a BTreeMap<String, serde_json::Value>,
}

impl<'a> FunctionCall<'a> {
    pub fn value(&self) -> &'a str {
        self.value
    }

    pub fn function(&self) -> &'a FunctionRef {
        self.function
    }

    pub fn locale(&self) -> &'a str {
        self.locale
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        let Some(option) = self
            .function
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
}

fn passthrough_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    Ok(call.value().to_string())
}

fn default_function_registry() -> &'static FunctionRegistry {
    static DEFAULT_FUNCTIONS: OnceLock<FunctionRegistry> = OnceLock::new();
    DEFAULT_FUNCTIONS.get_or_init(FunctionRegistry::default)
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
    format_model_with_locale_and_functions(model, arguments, locale, default_function_registry())
}

pub fn format_model_with_locale_and_functions(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<String, Diagnostic> {
    format_model_with_locale_and_functions_and_bidi(
        model,
        arguments,
        locale,
        functions,
        BidiIsolation::None,
    )
}

pub fn format_model_with_locale_and_bidi(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    bidi_isolation: BidiIsolation,
) -> Result<String, Diagnostic> {
    format_model_with_locale_and_functions_and_bidi(
        model,
        arguments,
        locale,
        default_function_registry(),
        bidi_isolation,
    )
}

pub fn format_model_with_locale_and_functions_and_bidi(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
    bidi_isolation: BidiIsolation,
) -> Result<String, Diagnostic> {
    Ok(parts_to_string(
        &format_model_to_parts_with_locale_and_functions(model, arguments, locale, functions)?,
        bidi_isolation,
    ))
}

pub fn format_model_to_parts_with_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    format_model_to_parts_with_locale_and_functions(
        model,
        arguments,
        locale,
        default_function_registry(),
    )
}

pub fn format_model_to_parts_with_locale_and_functions(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    validate_model(model)?;
    let mut context = FormatContext::new(arguments, locale, functions);
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
    validate_local_references(declarations)?;
    Ok(())
}

fn validate_local_references(declarations: &[Declaration]) -> Result<(), Diagnostic> {
    let mut forbidden = BTreeSet::new();
    for declaration in declarations.iter().rev() {
        let Declaration::Local { name, value } = declaration else {
            continue;
        };
        forbidden.insert(name.as_str());
        if expression_references_any(value, &forbidden) {
            return Err(model_error(
                "duplicate-declaration",
                format!(
                    "Local declaration ${name} must not reference itself or later local declarations."
                ),
            ));
        }
    }
    Ok(())
}

fn expression_references_any(expression: &Expression, names: &BTreeSet<&str>) -> bool {
    expression
        .arg
        .as_ref()
        .is_some_and(|arg| expression_arg_references_any(arg, names))
        || expression
            .function
            .as_ref()
            .is_some_and(|function| function_references_any(function, names))
}

fn function_references_any(function: &FunctionRef, names: &BTreeSet<&str>) -> bool {
    function.options.as_ref().is_some_and(|options| {
        options
            .values()
            .any(|arg| expression_arg_references_any(arg, names))
    })
}

fn expression_arg_references_any(arg: &ExpressionArg, names: &BTreeSet<&str>) -> bool {
    matches!(arg, ExpressionArg::Variable { name } if names.contains(name.as_str()))
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

struct FormatContext<'a> {
    values: BTreeMap<String, serde_json::Value>,
    selector_annotations: BTreeMap<String, SelectorAnnotation>,
    locale: String,
    functions: &'a FunctionRegistry,
}

impl<'a> FormatContext<'a> {
    fn new(
        arguments: &BTreeMap<String, serde_json::Value>,
        locale: &str,
        functions: &'a FunctionRegistry,
    ) -> Self {
        Self {
            values: arguments.clone(),
            selector_annotations: BTreeMap::new(),
            locale: locale.to_string(),
            functions,
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
                let rendered = value_to_string(value);
                let string_select = self.string_select_for_selector(&selector.name);
                Ok(SelectorValue {
                    normalized_rendered: string_select.then(|| normalize_string_key(&rendered)),
                    rendered,
                    exact_match: self.exact_match_for_selector(&selector.name),
                    selection_key: self.selection_key_for_selector(&selector.name, value),
                })
            })
            .collect::<Result<Vec<_>, _>>()?;

        let mut signatures = BTreeSet::new();
        let mut fallback = None;
        let mut selected = None;
        for variant in variants {
            validate_variant(variant, &selector_values, &mut signatures)?;
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

        match expression.function.as_ref() {
            None => Ok(value),
            Some(function) => self
                .functions
                .format(&value, function, &self.locale, &self.values),
        }
    }

    fn exact_match_for_selector(&self, selector_name: &str) -> bool {
        self.selector_annotations
            .get(selector_name)
            .map(|annotation| annotation.exact_match())
            .unwrap_or(true)
    }

    fn string_select_for_selector(&self, selector_name: &str) -> bool {
        self.selector_annotations
            .get(selector_name)
            .is_some_and(|annotation| annotation.is_string())
    }

    fn selection_key_for_selector(
        &self,
        selector_name: &str,
        value: &serde_json::Value,
    ) -> Option<String> {
        let annotation = self.selector_annotations.get(selector_name)?;
        if !annotation.is_numeric() {
            return None;
        }
        let operands = NumberOperands::from_json(value)?;
        annotation.selection_key(&self.locale, operands)
    }
}

#[derive(Debug, Clone)]
struct SelectorValue {
    rendered: String,
    normalized_rendered: Option<String>,
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
            || (self.is_numeric() && self.number_select == NumberSelect::Exact)
    }

    fn is_string(&self) -> bool {
        self.function == "string"
    }

    fn selection_key(&self, locale: &str, operands: NumberOperands) -> Option<String> {
        if !self.is_numeric() {
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

    fn is_numeric(&self) -> bool {
        self.function == "number" || self.function == "integer"
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
                (selector.exact_match && literal_key_matches(value, selector))
                    || selector
                        .selection_key
                        .as_ref()
                        .is_some_and(|category| category == value)
            }
        })
}

fn validate_variant<'a>(
    variant: &'a Variant,
    selector_values: &[SelectorValue],
    signatures: &mut BTreeSet<Vec<VariantKeySignature>>,
) -> Result<(), Diagnostic> {
    if variant.keys.len() != selector_values.len() {
        return Err(model_error(
            "variant-key-count-mismatch",
            "Variant key count must match selector count.",
        ));
    }
    if !signatures.insert(variant_key_signature(&variant.keys, selector_values)) {
        return Err(model_error(
            "duplicate-variant",
            "Select variants must have unique key tuples.",
        ));
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
enum VariantKeySignature {
    CatchAll,
    Literal(String),
}

fn variant_key_signature(
    keys: &[VariantKey],
    selector_values: &[SelectorValue],
) -> Vec<VariantKeySignature> {
    keys.iter()
        .zip(selector_values)
        .map(|(key, selector)| match key {
            VariantKey::CatchAll => VariantKeySignature::CatchAll,
            VariantKey::Literal { value } => {
                let value = if selector.normalized_rendered.is_some() {
                    normalize_string_key(value)
                } else {
                    value.clone()
                };
                VariantKeySignature::Literal(value)
            }
        })
        .collect()
}

fn literal_key_matches(value: &str, selector: &SelectorValue) -> bool {
    if let Some(normalized_rendered) = &selector.normalized_rendered {
        normalize_string_key(value) == *normalized_rendered
    } else {
        value == selector.rendered
    }
}

fn normalize_string_key(value: &str) -> String {
    value.nfc().collect()
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
        options: markup.options.clone(),
        attributes: markup.attributes.clone(),
    }
}

fn parts_to_string(parts: &[FormattedPart], bidi_isolation: BidiIsolation) -> String {
    let mut output = String::new();
    for part in parts {
        match part {
            FormattedPart::Text { value } => output.push_str(value),
            FormattedPart::Expression { value, .. } => {
                push_expression(&mut output, value, bidi_isolation);
            }
            FormattedPart::Markup { .. } => {}
        }
    }
    output
}

fn push_expression(output: &mut String, value: &str, bidi_isolation: BidiIsolation) {
    match bidi_isolation {
        BidiIsolation::None => output.push_str(value),
        BidiIsolation::Default => {
            output.push('\u{2068}');
            output.push_str(value);
            output.push('\u{2069}');
        }
    }
}
