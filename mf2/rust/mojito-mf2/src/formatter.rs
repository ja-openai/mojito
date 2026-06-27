use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::sync::Arc;
use std::sync::OnceLock;

use crate::cldr::{
    select_cardinal_plural_category, select_ordinal_plural_category, NumberOperands,
};
use crate::diagnostic::Diagnostic;
use crate::model::{
    AttributeValue, Declaration, Expression, ExpressionArg, FunctionRef, Markup, MessageModel,
    PatternPart, VariableRef, Variant, VariantKey,
};
use crate::number_core::{format_number_core, NumberCoreOptions};
use serde::{Deserialize, Serialize};
use unicode_normalization::UnicodeNormalization;

#[cfg(feature = "icu4x")]
mod icu4x_functions;
mod portable_functions;

use portable_functions::{
    inherited_exact_numeric_source, is_decimal_operand, is_numeric_function,
    numeric_select_uses_variable, parse_decimal_number,
};

const MAX_SOURCE_DECIMAL_EXPONENT: i64 = 1_000_000;
const MAX_SOURCE_DECIMAL_KEY_LENGTH: usize = 4096;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum FormattedPart {
    #[serde(rename = "text")]
    Text { value: String },
    #[serde(rename = "fallback")]
    Fallback {
        source: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<String>,
    },
    #[serde(rename = "expression")]
    Expression {
        value: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        dir: Option<String>,
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArgumentValue {
    String(String),
    Number(String),
    Bool(bool),
    Null,
}

impl ArgumentValue {
    pub fn number(value: impl fmt::Display) -> Self {
        Self::Number(value.to_string())
    }

    pub fn kind(&self) -> &'static str {
        match self {
            Self::String(_) => "string",
            Self::Number(_) => "number",
            Self::Bool(_) => "bool",
            Self::Null => "null",
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Self::String(value) => Some(value),
            _ => None,
        }
    }

    pub fn as_number_str(&self) -> Option<&str> {
        match self {
            Self::Number(value) => Some(value),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Bool(value) => Some(*value),
            _ => None,
        }
    }

    pub fn is_null(&self) -> bool {
        matches!(self, Self::Null)
    }

    fn rendered(&self) -> String {
        match self {
            Self::String(value) | Self::Number(value) => value.clone(),
            Self::Bool(value) => value.to_string(),
            Self::Null => String::new(),
        }
    }
}

impl From<&str> for ArgumentValue {
    fn from(value: &str) -> Self {
        Self::String(value.to_string())
    }
}

impl From<String> for ArgumentValue {
    fn from(value: String) -> Self {
        Self::String(value)
    }
}

impl From<bool> for ArgumentValue {
    fn from(value: bool) -> Self {
        Self::Bool(value)
    }
}

macro_rules! impl_integer_argument_value {
    ($($type:ty),+ $(,)?) => {
        $(
            impl From<$type> for ArgumentValue {
                fn from(value: $type) -> Self {
                    Self::Number(value.to_string())
                }
            }
        )+
    };
}

impl_integer_argument_value!(i8, i16, i32, i64, i128, isize, u8, u16, u32, u64, u128, usize);

impl From<f32> for ArgumentValue {
    fn from(value: f32) -> Self {
        ArgumentValue::from(value as f64)
    }
}

impl From<f64> for ArgumentValue {
    fn from(value: f64) -> Self {
        if value.is_finite() {
            Self::Number(value.to_string())
        } else {
            Self::String(value.to_string())
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Arguments {
    values: BTreeMap<String, ArgumentValue>,
}

impl Arguments {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with(mut self, name: impl Into<String>, value: impl Into<ArgumentValue>) -> Self {
        self.insert(name, value);
        self
    }

    pub fn insert(
        &mut self,
        name: impl Into<String>,
        value: impl Into<ArgumentValue>,
    ) -> Option<ArgumentValue> {
        self.values.insert(name.into(), value.into())
    }

    pub fn get(&self, name: &str) -> Option<&ArgumentValue> {
        self.values.get(name)
    }

    pub fn len(&self) -> usize {
        self.values.len()
    }

    pub fn is_empty(&self) -> bool {
        self.values.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &ArgumentValue)> {
        self.values
            .iter()
            .map(|(name, value)| (name.as_str(), value))
    }
}

impl From<&Arguments> for Arguments {
    fn from(arguments: &Arguments) -> Self {
        arguments.clone()
    }
}

impl<K, V> FromIterator<(K, V)> for Arguments
where
    K: Into<String>,
    V: Into<ArgumentValue>,
{
    fn from_iter<T: IntoIterator<Item = (K, V)>>(iter: T) -> Self {
        let mut arguments = Arguments::new();
        for (name, value) in iter {
            arguments.insert(name, value);
        }
        arguments
    }
}

impl<K, V, const N: usize> From<[(K, V); N]> for Arguments
where
    K: Into<String>,
    V: Into<ArgumentValue>,
{
    fn from(values: [(K, V); N]) -> Self {
        values.into_iter().collect()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FormatResult {
    pub value: String,
    pub errors: Vec<Diagnostic>,
}

impl FormatResult {
    pub fn is_ok(&self) -> bool {
        self.errors.is_empty()
    }

    pub fn has_errors(&self) -> bool {
        !self.is_ok()
    }

    pub fn into_value(self) -> String {
        self.value
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PartsResult {
    pub parts: Vec<FormattedPart>,
    pub errors: Vec<Diagnostic>,
}

impl PartsResult {
    pub fn is_ok(&self) -> bool {
        self.errors.is_empty()
    }

    pub fn has_errors(&self) -> bool {
        !self.is_ok()
    }

    pub fn into_parts(self) -> Vec<FormattedPart> {
        self.parts
    }
}

pub struct RecoveryContext<'a> {
    pub code: &'a str,
    pub message: &'a str,
    pub locale: &'a str,
    pub variable_name: Option<&'a str>,
    pub function_name: Option<&'a str>,
    pub source_expression: String,
    pub fallback_value: String,
    pub error: &'a Diagnostic,
}

pub type RecoveryHandler = dyn for<'a> Fn(RecoveryContext<'a>) -> Option<String>;

pub struct FormatOptions<'a> {
    pub locale: &'a str,
    pub functions: &'a FunctionRegistry,
    pub bidi_isolation: BidiIsolation,
    pub on_missing_argument: Option<&'a RecoveryHandler>,
    pub on_format_error: Option<&'a RecoveryHandler>,
}

impl<'a> FormatOptions<'a> {
    pub fn new(locale: &'a str) -> Self {
        Self {
            locale,
            functions: default_function_registry(),
            bidi_isolation: BidiIsolation::None,
            on_missing_argument: None,
            on_format_error: None,
        }
    }

    pub fn with_functions(mut self, functions: &'a FunctionRegistry) -> Self {
        self.functions = functions;
        self
    }

    pub fn with_bidi_isolation(mut self, bidi_isolation: BidiIsolation) -> Self {
        self.bidi_isolation = bidi_isolation;
        self
    }

    pub fn on_missing_argument(mut self, handler: &'a RecoveryHandler) -> Self {
        self.on_missing_argument = Some(handler);
        self
    }

    pub fn on_format_error(mut self, handler: &'a RecoveryHandler) -> Self {
        self.on_format_error = Some(handler);
        self
    }
}

impl Default for FormatOptions<'static> {
    fn default() -> Self {
        Self::new("en")
    }
}

fn normalized_formatter_locale(locale: &str) -> &str {
    if locale.trim().is_empty() {
        "en"
    } else {
        locale
    }
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BidiDirection {
    Auto,
    Ltr,
    Rtl,
}

impl BidiDirection {
    fn marker(self) -> char {
        match self {
            Self::Auto => '\u{2068}',
            Self::Ltr => '\u{2066}',
            Self::Rtl => '\u{2067}',
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Ltr => "ltr",
            Self::Rtl => "rtl",
        }
    }
}

pub type FunctionFormatter =
    dyn for<'a> Fn(FunctionCall<'a>) -> Result<String, Diagnostic> + Send + Sync;
pub type FunctionSelector = for<'a> fn(FunctionMatch<'a>) -> Result<Option<i32>, Diagnostic>;

#[derive(Clone)]
pub struct FunctionRegistry {
    formatters: BTreeMap<String, Arc<FunctionFormatter>>,
    selectors: BTreeMap<String, FunctionSelector>,
}

impl FunctionRegistry {
    pub fn defaults() -> Self {
        Self::default()
    }

    pub fn portable() -> Self {
        let mut registry = Self::empty_registry();
        portable_functions::register(&mut registry);
        registry
    }

    #[cfg(feature = "icu4x")]
    pub fn icu4x() -> Self {
        let mut registry = Self::portable();
        icu4x_functions::register(&mut registry);
        registry
    }

    fn empty_registry() -> Self {
        Self {
            formatters: BTreeMap::new(),
            selectors: BTreeMap::new(),
        }
    }

    pub fn with_function<F>(mut self, name: impl Into<String>, formatter: F) -> Self
    where
        F: for<'a> Fn(FunctionCall<'a>) -> Result<String, Diagnostic> + Send + Sync + 'static,
    {
        self.formatters.insert(name.into(), Arc::new(formatter));
        self
    }

    pub fn with_selector(mut self, name: impl Into<String>, selector: FunctionSelector) -> Self {
        self.selectors.insert(name.into(), selector);
        self
    }

    pub fn with_registry(mut self, other: &FunctionRegistry) -> Self {
        for (name, formatter) in &other.formatters {
            self.formatters.insert(name.clone(), Arc::clone(formatter));
        }
        for (name, selector) in &other.selectors {
            self.selectors.insert(name.clone(), *selector);
        }
        self
    }

    pub fn register_formatter<F>(&mut self, name: impl Into<String>, formatter: F)
    where
        F: for<'a> Fn(FunctionCall<'a>) -> Result<String, Diagnostic> + Send + Sync + 'static,
    {
        self.formatters.insert(name.into(), Arc::new(formatter));
    }

    pub fn register_selector(&mut self, name: impl Into<String>, selector: FunctionSelector) {
        self.selectors.insert(name.into(), selector);
    }

    fn has_selector(&self, function: &FunctionRef) -> bool {
        self.selectors.contains_key(&function.name)
    }

    fn has_formatter(&self, function: &FunctionRef) -> bool {
        self.formatters.contains_key(&function.name)
    }

    fn format(
        &self,
        value: &str,
        raw_value: &ArgumentValue,
        function: &FunctionRef,
        locale: &str,
        values: &BTreeMap<String, ResolvedValue>,
        source: Option<&ResolvedFunctionSource>,
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
            raw_value,
            function,
            locale,
            values,
            source: source.map(|source| FunctionSourceRef { source, values }),
        })
    }

    fn select(
        &self,
        value: &str,
        raw_value: &ArgumentValue,
        function: &FunctionRef,
        key: &str,
        locale: &str,
        values: &BTreeMap<String, ResolvedValue>,
        source: Option<&ResolvedFunctionSource>,
    ) -> Result<Option<i32>, Diagnostic> {
        let Some(selector) = self.selectors.get(&function.name) else {
            return Ok(None);
        };
        selector(FunctionMatch {
            value,
            raw_value,
            function,
            key,
            locale,
            values,
            source: source.map(|source| FunctionSourceRef { source, values }),
        })
    }
}

impl Default for FunctionRegistry {
    fn default() -> Self {
        Self::portable()
    }
}

pub struct FunctionCall<'a> {
    value: &'a str,
    raw_value: &'a ArgumentValue,
    function: &'a FunctionRef,
    locale: &'a str,
    values: &'a BTreeMap<String, ResolvedValue>,
    source: Option<FunctionSourceRef<'a>>,
}

pub struct FunctionMatch<'a> {
    value: &'a str,
    raw_value: &'a ArgumentValue,
    function: &'a FunctionRef,
    key: &'a str,
    locale: &'a str,
    values: &'a BTreeMap<String, ResolvedValue>,
    source: Option<FunctionSourceRef<'a>>,
}

#[derive(Clone, Copy)]
pub struct FunctionSourceRef<'a> {
    source: &'a ResolvedFunctionSource,
    values: &'a BTreeMap<String, ResolvedValue>,
}

impl<'a> FunctionCall<'a> {
    pub fn value(&self) -> &'a str {
        self.value
    }

    pub fn raw_value(&self) -> &'a ArgumentValue {
        self.raw_value
    }

    pub fn function(&self) -> &'a FunctionRef {
        self.function
    }

    pub fn locale(&self) -> &'a str {
        self.locale
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        option_value(self.function, self.values, name)
    }

    pub fn inherited_source(&self) -> Option<FunctionSourceRef<'a>> {
        self.source
    }
}

impl<'a> FunctionMatch<'a> {
    pub fn value(&self) -> &'a str {
        self.value
    }

    pub fn raw_value(&self) -> &'a ArgumentValue {
        self.raw_value
    }

    pub fn function(&self) -> &'a FunctionRef {
        self.function
    }

    pub fn key(&self) -> &'a str {
        self.key
    }

    pub fn locale(&self) -> &'a str {
        self.locale
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        option_value(self.function, self.values, name)
    }

    pub fn inherited_source(&self) -> Option<FunctionSourceRef<'a>> {
        self.source
    }
}

impl<'a> FunctionSourceRef<'a> {
    pub fn value(&self) -> &'a str {
        &self.source.value
    }

    pub fn function(&self) -> &'a FunctionRef {
        &self.source.function
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        option_value(&self.source.function, self.values, name)
    }

    pub fn inherited_source(&self) -> Option<FunctionSourceRef<'a>> {
        self.source
            .inherited
            .as_deref()
            .map(|source| FunctionSourceRef {
                source,
                values: self.values,
            })
    }
}

fn option_value(
    function: &FunctionRef,
    values: &BTreeMap<String, ResolvedValue>,
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
        ExpressionArg::Variable { name } => values
            .get(name)
            .map(ResolvedValue::rendered)
            .map(Some)
            .ok_or_else(|| missing_argument(name)),
    }
}

fn function_option_literal<'a>(function: &'a FunctionRef, name: &str) -> Option<&'a str> {
    let Some(ExpressionArg::Literal { value }) = function
        .options
        .as_ref()
        .and_then(|options| options.get(name))
    else {
        return None;
    };
    Some(value)
}

fn bidi_direction_for_function(
    function: &FunctionRef,
    source: Option<&ResolvedFunctionSource>,
) -> Result<Option<BidiDirection>, Diagnostic> {
    if let Some(value) = function_option_literal(function, "u:dir") {
        return parse_bidi_direction(value).map(Some);
    }
    bidi_direction_from_source(source)
}

fn bidi_direction_from_source(
    source: Option<&ResolvedFunctionSource>,
) -> Result<Option<BidiDirection>, Diagnostic> {
    let Some(source) = source else {
        return Ok(None);
    };
    if let Some(value) = function_option_literal(&source.function, "u:dir") {
        return parse_bidi_direction(value).map(Some);
    }
    bidi_direction_from_source(source.inherited.as_deref())
}

fn parse_bidi_direction(value: &str) -> Result<BidiDirection, Diagnostic> {
    match value {
        "auto" => Ok(BidiDirection::Auto),
        "ltr" => Ok(BidiDirection::Ltr),
        "rtl" => Ok(BidiDirection::Rtl),
        _ => Err(bad_option("u:dir option must be auto, ltr, or rtl.")),
    }
}

fn default_function_registry() -> &'static FunctionRegistry {
    static DEFAULT_FUNCTIONS: OnceLock<FunctionRegistry> = OnceLock::new();
    DEFAULT_FUNCTIONS.get_or_init(FunctionRegistry::default)
}

pub fn format_message(
    model: &MessageModel,
    arguments: impl Into<Arguments>,
) -> Result<FormatResult, Diagnostic> {
    let options = FormatOptions::default();
    format_message_with_options(model, arguments, &options)
}

pub fn format_message_with_options(
    model: &MessageModel,
    arguments: impl Into<Arguments>,
    options: &FormatOptions<'_>,
) -> Result<FormatResult, Diagnostic> {
    let arguments = arguments.into();
    format_result_with_options(model, &arguments, options)
}

pub fn format_message_to_parts(
    model: &MessageModel,
    arguments: impl Into<Arguments>,
) -> Result<PartsResult, Diagnostic> {
    let options = FormatOptions::default();
    format_message_to_parts_with_options(model, arguments, &options)
}

pub fn format_message_to_parts_with_options(
    model: &MessageModel,
    arguments: impl Into<Arguments>,
    options: &FormatOptions<'_>,
) -> Result<PartsResult, Diagnostic> {
    let arguments = arguments.into();
    format_to_parts_with_options(model, &arguments, options)
}

fn format_result_with_options(
    model: &MessageModel,
    arguments: &Arguments,
    options: &FormatOptions<'_>,
) -> Result<FormatResult, Diagnostic> {
    let result = format_to_parts_with_options(model, arguments, options)?;
    Ok(FormatResult {
        value: parts_to_string(&result.parts, options.bidi_isolation),
        errors: result.errors,
    })
}

fn format_to_parts_with_options(
    model: &MessageModel,
    arguments: &Arguments,
    options: &FormatOptions<'_>,
) -> Result<PartsResult, Diagnostic> {
    validate_model(model)?;
    let mut context = FormatContext::new(arguments, options.locale, options.functions)
        .with_fallback()
        .with_recovery(options.on_missing_argument, options.on_format_error);
    context.apply_declarations(model.declarations())?;
    let parts = match model {
        MessageModel::Message { pattern, .. } => context.format_pattern_to_parts(pattern)?,
        MessageModel::Select {
            selectors,
            variants,
            ..
        } => context.format_select_to_parts(selectors, variants)?,
    };
    Ok(PartsResult {
        parts,
        errors: context.errors,
    })
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
    values: BTreeMap<String, ResolvedValue>,
    selector_annotations: BTreeMap<String, SelectorAnnotation>,
    failed_values: BTreeSet<String>,
    errors: Vec<Diagnostic>,
    locale: String,
    functions: &'a FunctionRegistry,
    fallback: bool,
    on_missing_argument: Option<&'a RecoveryHandler>,
    on_format_error: Option<&'a RecoveryHandler>,
}

impl<'a> FormatContext<'a> {
    fn new(arguments: &Arguments, locale: &str, functions: &'a FunctionRegistry) -> Self {
        Self {
            values: arguments
                .values
                .iter()
                .map(|(name, value)| (name.clone(), ResolvedValue::from_argument(value.clone())))
                .collect(),
            selector_annotations: BTreeMap::new(),
            failed_values: BTreeSet::new(),
            errors: Vec::new(),
            locale: normalized_formatter_locale(locale).to_string(),
            functions,
            fallback: false,
            on_missing_argument: None,
            on_format_error: None,
        }
    }

    fn with_fallback(mut self) -> Self {
        self.fallback = true;
        self
    }

    fn with_recovery(
        mut self,
        on_missing_argument: Option<&'a RecoveryHandler>,
        on_format_error: Option<&'a RecoveryHandler>,
    ) -> Self {
        self.on_missing_argument = on_missing_argument;
        self.on_format_error = on_format_error;
        self
    }

    fn apply_declarations(&mut self, declarations: &[Declaration]) -> Result<(), Diagnostic> {
        self.selector_annotations = selector_annotations(declarations);
        for declaration in declarations {
            match declaration {
                Declaration::Input { name, value } => {
                    self.apply_input_declaration(name, value)?;
                }
                Declaration::Local { name, value } => {
                    let rendered = self.format_expression_output(value)?;
                    if rendered.had_error {
                        self.failed_values.insert(name.clone());
                        self.values.remove(name);
                    } else {
                        self.values.insert(
                            name.clone(),
                            ResolvedValue::new(rendered.raw_value, rendered.source),
                        );
                    }
                }
            }
        }
        Ok(())
    }

    fn apply_input_declaration(
        &mut self,
        name: &str,
        expression: &Expression,
    ) -> Result<(), Diagnostic> {
        let Some(function) = expression.function.as_ref() else {
            return Ok(());
        };
        if !self.functions.has_selector(function) || !self.functions.has_formatter(function) {
            return Ok(());
        }
        let Some(input) = self.values.get(name).cloned() else {
            if self.fallback {
                self.failed_values.insert(name.to_string());
                self.errors.push(unresolved_variable(name));
                self.errors
                    .push(bad_operand("Function operand is not available."));
                return Ok(());
            }
            return Err(missing_argument(name));
        };
        let value = input.rendered();
        self.record_function_resolution_errors(function, input.source.as_ref())?;
        match self.functions.format(
            &value,
            &input.value,
            function,
            &self.locale,
            &self.values,
            input.source.as_ref(),
        ) {
            Ok(formatted) => {
                if let Some(slot) = self.values.get_mut(name) {
                    slot.value = ArgumentValue::String(formatted);
                    slot.source = Some(ResolvedFunctionSource::new(
                        input.source_value(&value),
                        function.clone(),
                        input.source,
                    ));
                }
                Ok(())
            }
            Err(error) if self.fallback => {
                self.errors.push(fallback_error(error));
                self.failed_values.insert(name.to_string());
                self.values.remove(name);
                Ok(())
            }
            Err(error) => Err(error),
        }
    }

    fn format_select_to_parts(
        &mut self,
        selectors: &[VariableRef],
        variants: &[Variant],
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        let selector_values = selectors
            .iter()
            .map(|selector| {
                let annotation = self.selector_annotations.get(&selector.name).cloned();
                let Some(value) = self.values.get(&selector.name) else {
                    if self.fallback {
                        let failed_value = self.failed_values.contains(&selector.name);
                        if !failed_value {
                            self.errors.push(unresolved_variable(&selector.name));
                        }
                        if annotation.as_ref().is_some_and(|annotation| {
                            failed_value || self.functions.has_selector(&annotation.function)
                        }) {
                            if !failed_value {
                                self.errors
                                    .push(bad_operand("Selector operand is not available."));
                            }
                            self.errors
                                .push(bad_selector("Selector operand is not available."));
                        }
                        let string_select = annotation
                            .as_ref()
                            .is_some_and(|annotation| annotation.is_string());
                        return Ok(SelectorValue {
                            normalized_rendered: string_select.then(|| normalize_string_key("")),
                            rendered: String::new(),
                            raw_value: ArgumentValue::String(String::new()),
                            exact_match: false,
                            selection_key: None,
                            function: annotation.map(|annotation| annotation.function),
                            source: None,
                        });
                    }
                    return Err(missing_argument(&selector.name));
                };
                let rendered = value.rendered();
                let string_select = annotation
                    .as_ref()
                    .is_some_and(|annotation| annotation.is_string());
                let exact_match = annotation
                    .as_ref()
                    .is_none_or(|annotation| annotation.exact_match());
                let selection_key = self.selection_key_for_selector(annotation.as_ref(), value);
                let raw_value = value.value.clone();
                let source = value.source.clone();
                self.record_selector_resolution_errors(annotation.as_ref())?;
                Ok(SelectorValue {
                    normalized_rendered: string_select.then(|| normalize_string_key(&rendered)),
                    rendered,
                    raw_value,
                    exact_match,
                    selection_key,
                    function: annotation.map(|annotation| annotation.function),
                    source,
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
            let Some(rank) = self.variant_match_rank(variant, &selector_values)? else {
                continue;
            };
            if selected
                .as_ref()
                .is_none_or(|(_, selected_rank): &(&Variant, Vec<i32>)| rank > *selected_rank)
            {
                selected = Some((variant, rank));
            }
        }
        let fallback = fallback.ok_or_else(|| {
            model_error(
                "missing-fallback-variant",
                "Select messages must include a catch-all fallback variant.",
            )
        })?;
        let selected = selected
            .map(|(variant, _)| variant)
            .or(Some(fallback))
            .ok_or_else(|| {
                Diagnostic::new(
                    "missing-select-variant",
                    "No select variant matched and no catch-all variant is present.",
                    0,
                    0,
                )
            })?;

        self.format_pattern_to_parts(&selected.value)
    }

    fn variant_match_rank(
        &mut self,
        variant: &Variant,
        selector_values: &[SelectorValue],
    ) -> Result<Option<Vec<i32>>, Diagnostic> {
        if variant.keys.len() != selector_values.len() {
            return Ok(None);
        }
        let mut ranks = Vec::with_capacity(variant.keys.len());
        for (key, selector) in variant.keys.iter().zip(selector_values) {
            let Some(rank) = self.key_match_rank(key, selector)? else {
                return Ok(None);
            };
            ranks.push(rank);
        }
        Ok(Some(ranks))
    }

    fn key_match_rank(
        &mut self,
        key: &VariantKey,
        selector: &SelectorValue,
    ) -> Result<Option<i32>, Diagnostic> {
        match key {
            VariantKey::CatchAll => Ok(Some(0)),
            VariantKey::Literal { value } => {
                let numeric_selector = selector.function.as_ref().is_some_and(is_numeric_function);
                if selector.exact_match && numeric_literal_key_matches_source(value, selector) {
                    return Ok(Some(3));
                }
                if selector.exact_match && !numeric_selector && literal_key_matches(value, selector)
                {
                    return Ok(Some(2));
                }
                if selector
                    .selection_key
                    .as_ref()
                    .is_some_and(|category| category == value)
                {
                    return Ok(Some(1));
                }
                let Some(function) = selector.function.as_ref() else {
                    return Ok(None);
                };
                match self.functions.select(
                    &selector.rendered,
                    &selector.raw_value,
                    function,
                    value,
                    &self.locale,
                    &self.values,
                    selector.source.as_ref(),
                ) {
                    Ok(rank) => Ok(rank),
                    Err(error) if self.fallback => {
                        self.errors.push(fallback_error(error));
                        self.errors.push(bad_selector("Selector failed to match."));
                        Ok(None)
                    }
                    Err(error) => Err(error),
                }
            }
        }
    }

    fn format_pattern_to_parts(
        &mut self,
        pattern: &[PatternPart],
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        let mut output = Vec::new();
        for part in pattern {
            match part {
                PatternPart::Text(text) => output.push(FormattedPart::Text {
                    value: text.clone(),
                }),
                PatternPart::Expression(expression) => {
                    let rendered = self.format_expression_output(expression)?;
                    if rendered.had_error {
                        let source = rendered
                            .fallback_source
                            .unwrap_or_else(|| fallback_source(expression));
                        let value =
                            (rendered.value != fallback_value(&source)).then_some(rendered.value);
                        output.push(FormattedPart::Fallback { source, value });
                        continue;
                    }
                    output.push(FormattedPart::Expression {
                        value: rendered.value,
                        dir: rendered.bidi_direction.map(|dir| dir.name().to_string()),
                        attributes: expression.attributes.clone(),
                    });
                }
                PatternPart::Markup(markup) => {
                    self.record_markup_resolution_errors(markup)?;
                    output.push(markup_to_part(markup));
                }
            }
        }
        Ok(output)
    }

    fn format_expression_output(
        &mut self,
        expression: &Expression,
    ) -> Result<ExpressionOutput, Diagnostic> {
        let mut had_error = false;
        let (value, raw_value, source) = match &expression.arg {
            Some(ExpressionArg::Variable { name }) => {
                if let Some(value) = self.values.get(name) {
                    (value.rendered(), value.value.clone(), value.source.clone())
                } else if self.fallback {
                    had_error = true;
                    let error = unresolved_variable(name);
                    if !self.failed_values.contains(name) {
                        self.errors.push(error.clone());
                    }
                    if expression.function.is_some() {
                        self.errors
                            .push(bad_operand("Function operand is not available."));
                    }
                    let source = fallback_source(expression);
                    let recovered =
                        self.recover_missing_argument(expression, name, &source, &error);
                    (recovered.clone(), ArgumentValue::String(recovered), None)
                } else {
                    return Err(missing_argument(name));
                }
            }
            Some(ExpressionArg::Literal { value }) => {
                (value.clone(), ArgumentValue::String(value.clone()), None)
            }
            None => (String::new(), ArgumentValue::String(String::new()), None),
        };

        if had_error {
            return Ok(ExpressionOutput {
                value,
                raw_value: ArgumentValue::String(String::new()),
                had_error: true,
                source: None,
                bidi_direction: None,
                fallback_source: Some(fallback_source(expression)),
            });
        }

        let Some(function) = expression.function.as_ref() else {
            let value = match self.primitive_value_to_string(&raw_value, &value) {
                Ok(value) => value,
                Err(error) if self.fallback => {
                    let recoverable = fallback_error(error);
                    self.errors.push(recoverable.clone());
                    let source = fallback_source(expression);
                    return Ok(ExpressionOutput {
                        value: self.recover_format_error(expression, &source, &recoverable),
                        raw_value: raw_value.clone(),
                        had_error: true,
                        source: None,
                        bidi_direction: None,
                        fallback_source: Some(source),
                    });
                }
                Err(error) => return Err(error),
            };
            let bidi_direction = match bidi_direction_from_source(source.as_ref()) {
                Ok(direction) => direction,
                Err(error) if self.fallback => {
                    return Ok(self.recover_bidi_direction_error(expression, error));
                }
                Err(error) => return Err(error),
            };
            return Ok(ExpressionOutput {
                value,
                raw_value,
                had_error,
                source,
                bidi_direction,
                fallback_source: None,
            });
        };
        self.record_function_resolution_errors(function, source.as_ref())?;
        let bidi_direction = match bidi_direction_for_function(function, source.as_ref()) {
            Ok(direction) => direction,
            Err(error) if self.fallback => {
                return Ok(self.recover_bidi_direction_error(expression, error));
            }
            Err(error) => return Err(error),
        };

        let source_value = source
            .as_ref()
            .map(|source| source.value.clone())
            .unwrap_or_else(|| value.clone());
        match self.functions.format(
            &value,
            &raw_value,
            function,
            &self.locale,
            &self.values,
            source.as_ref(),
        ) {
            Ok(formatted) => Ok(ExpressionOutput {
                raw_value: ArgumentValue::String(formatted.clone()),
                value: formatted,
                had_error: false,
                source: Some(ResolvedFunctionSource::new(
                    source_value,
                    function.clone(),
                    source,
                )),
                bidi_direction,
                fallback_source: None,
            }),
            Err(error) if self.fallback => {
                let recoverable = fallback_error(error);
                self.errors.push(recoverable.clone());
                let source = fallback_source(expression);
                Ok(ExpressionOutput {
                    value: self.recover_format_error(expression, &source, &recoverable),
                    raw_value: ArgumentValue::String(String::new()),
                    had_error: true,
                    source: None,
                    bidi_direction: None,
                    fallback_source: Some(source),
                })
            }
            Err(error) => Err(error),
        }
    }

    fn primitive_value_to_string(
        &self,
        raw_value: &ArgumentValue,
        fallback_value: &str,
    ) -> Result<String, Diagnostic> {
        if matches!(raw_value, ArgumentValue::Number(_)) {
            return format_number_core(
                raw_value.clone(),
                &NumberCoreOptions {
                    locale: self.locale.clone(),
                    ..NumberCoreOptions::default()
                },
            );
        }
        Ok(fallback_value.to_string())
    }

    fn recover_missing_argument(
        &self,
        expression: &Expression,
        variable_name: &str,
        source: &str,
        error: &Diagnostic,
    ) -> String {
        recover_value(
            self.on_missing_argument,
            RecoveryContext {
                code: &error.code,
                message: &error.message,
                locale: &self.locale,
                variable_name: Some(variable_name),
                function_name: expression
                    .function
                    .as_ref()
                    .map(|function| function.name.as_str()),
                source_expression: expression_source(expression),
                fallback_value: fallback_value(source),
                error,
            },
        )
    }

    fn recover_format_error(
        &self,
        expression: &Expression,
        source: &str,
        error: &Diagnostic,
    ) -> String {
        recover_value(
            self.on_format_error,
            RecoveryContext {
                code: &error.code,
                message: &error.message,
                locale: &self.locale,
                variable_name: expression_variable_name(expression),
                function_name: expression
                    .function
                    .as_ref()
                    .map(|function| function.name.as_str()),
                source_expression: expression_source(expression),
                fallback_value: fallback_value(source),
                error,
            },
        )
    }

    fn recover_bidi_direction_error(
        &mut self,
        expression: &Expression,
        error: Diagnostic,
    ) -> ExpressionOutput {
        let recoverable = fallback_error(error);
        self.errors.push(recoverable.clone());
        let source = fallback_source(expression);
        ExpressionOutput {
            value: self.recover_format_error(expression, &source, &recoverable),
            raw_value: ArgumentValue::String(String::new()),
            had_error: true,
            source: None,
            bidi_direction: None,
            fallback_source: Some(source),
        }
    }

    fn record_function_resolution_errors(
        &mut self,
        function: &FunctionRef,
        source: Option<&ResolvedFunctionSource>,
    ) -> Result<(), Diagnostic> {
        if !is_numeric_function(function) {
            return Ok(());
        }
        if numeric_select_uses_variable(function)
            || inherited_exact_numeric_source(source.map(|source| FunctionSourceRef {
                source,
                values: &self.values,
            }))?
        {
            let error = bad_option("Numeric select option is not valid in this context.");
            if self.fallback {
                self.errors.push(error);
                Ok(())
            } else {
                Err(error)
            }
        } else {
            Ok(())
        }
    }

    fn record_selector_resolution_errors(
        &mut self,
        annotation: Option<&SelectorAnnotation>,
    ) -> Result<(), Diagnostic> {
        if !annotation.is_some_and(|annotation| annotation.function.name == "currency") {
            return Ok(());
        }
        let error = bad_selector("Currency selector is not supported.");
        if self.fallback {
            self.errors.push(error);
            Ok(())
        } else {
            Err(error)
        }
    }

    fn record_markup_resolution_errors(&mut self, markup: &Markup) -> Result<(), Diagnostic> {
        if !markup
            .options
            .as_ref()
            .is_some_and(|options| options.contains_key("u:dir"))
        {
            return Ok(());
        }
        let error = bad_option("u:dir is not valid on markup.");
        if self.fallback {
            self.errors.push(error);
            Ok(())
        } else {
            Err(error)
        }
    }

    fn selection_key_for_selector(
        &self,
        annotation: Option<&SelectorAnnotation>,
        value: &ResolvedValue,
    ) -> Option<String> {
        let annotation = annotation?;
        annotation.selection_key(&self.locale, value)
    }
}

#[derive(Debug, Clone)]
struct ResolvedValue {
    value: ArgumentValue,
    source: Option<ResolvedFunctionSource>,
}

impl ResolvedValue {
    fn new(value: ArgumentValue, source: Option<ResolvedFunctionSource>) -> Self {
        Self { value, source }
    }

    fn from_argument(value: ArgumentValue) -> Self {
        Self {
            value,
            source: None,
        }
    }

    fn rendered(&self) -> String {
        value_to_string(&self.value)
    }

    fn source_value(&self, fallback: &str) -> String {
        self.source
            .as_ref()
            .map(|source| source.value.clone())
            .unwrap_or_else(|| fallback.to_string())
    }
}

#[derive(Debug, Clone)]
struct ResolvedFunctionSource {
    value: String,
    function: FunctionRef,
    inherited: Option<Box<ResolvedFunctionSource>>,
}

impl ResolvedFunctionSource {
    fn new(
        value: String,
        function: FunctionRef,
        inherited: Option<ResolvedFunctionSource>,
    ) -> Self {
        Self {
            value,
            function,
            inherited: inherited.map(Box::new),
        }
    }
}

#[derive(Debug, Clone)]
struct ExpressionOutput {
    value: String,
    raw_value: ArgumentValue,
    had_error: bool,
    source: Option<ResolvedFunctionSource>,
    bidi_direction: Option<BidiDirection>,
    fallback_source: Option<String>,
}

#[derive(Debug, Clone)]
struct SelectorValue {
    rendered: String,
    raw_value: ArgumentValue,
    normalized_rendered: Option<String>,
    exact_match: bool,
    selection_key: Option<String>,
    function: Option<FunctionRef>,
    source: Option<ResolvedFunctionSource>,
}

#[derive(Debug, Clone)]
struct SelectorAnnotation {
    function: FunctionRef,
    number_select: NumberSelect,
}

impl SelectorAnnotation {
    fn from_function(function: &crate::model::FunctionRef) -> Self {
        Self {
            function: function.clone(),
            number_select: NumberSelect::from_options(function.options.as_ref()),
        }
    }

    fn exact_match(&self) -> bool {
        self.function.name == "string"
            || (self.is_numeric() && self.number_select == NumberSelect::Exact)
    }

    fn is_string(&self) -> bool {
        self.function.name == "string"
    }

    fn selection_key(&self, locale: &str, value: &ResolvedValue) -> Option<String> {
        if !self.is_numeric() {
            return None;
        }
        let operands = NumberOperands::from_str(&self.operand_for_selection(value)?)?;
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
        is_numeric_function(&self.function)
    }

    fn operand_for_selection(&self, value: &ResolvedValue) -> Option<String> {
        let rendered = value.rendered();
        if self.function.name == "percent" {
            if let Some(percent) = rendered.strip_suffix('%') {
                return Some(percent.to_string());
            }
            let value = parse_decimal_number(value.source_value(&rendered).as_str()).ok()?;
            return Some((value * 100.0).to_string());
        }
        Some(rendered)
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

fn numeric_literal_key_matches_source(value: &str, selector: &SelectorValue) -> bool {
    preferred_numeric_source_key(selector)
        .is_some_and(|source_key| value == source_key && is_decimal_operand(value))
}

fn preferred_numeric_source_key(selector: &SelectorValue) -> Option<String> {
    let function_name = selector.function.as_ref()?.name.as_str();
    if function_name != "number" && function_name != "percent" {
        return None;
    }
    let source_value = numeric_source_value(selector.source.as_ref(), function_name)?;
    let operand = parse_source_decimal(source_value)?;
    if function_name == "percent" {
        return render_source_decimal(
            SourceDecimal {
                scale: operand.scale - 2,
                ..operand
            },
            false,
        );
    }
    if operand.has_exponent {
        render_source_decimal(operand, true)
    } else {
        Some(source_value.to_string())
    }
}

fn numeric_source_value<'a>(
    source: Option<&'a ResolvedFunctionSource>,
    function_name: &str,
) -> Option<&'a str> {
    let mut current = source;
    while let Some(source) = current {
        if source.function.name == function_name {
            return Some(&source.value);
        }
        current = source.inherited.as_deref();
    }
    None
}

struct SourceDecimal {
    negative: bool,
    digits: String,
    scale: i64,
    has_exponent: bool,
}

fn parse_source_decimal(value: &str) -> Option<SourceDecimal> {
    let negative = value.starts_with('-');
    let unsigned = if negative { &value[1..] } else { value };
    if unsigned.is_empty() {
        return None;
    }
    let (significand, exponent_text, has_exponent) =
        if let Some(index) = unsigned.find(|character| character == 'e' || character == 'E') {
            let exponent_text = &unsigned[index + 1..];
            if exponent_text.is_empty()
                || unsigned[index + 1..].contains(|character| character == 'e' || character == 'E')
            {
                return None;
            }
            (&unsigned[..index], exponent_text, true)
        } else {
            (unsigned, "", false)
        };
    let exponent = parse_source_exponent(exponent_text)?;
    let mut parts = significand.split('.');
    let integer = parts.next()?;
    let fraction = parts.next().unwrap_or("");
    if parts.next().is_some()
        || integer.is_empty()
        || (integer.len() > 1 && integer.starts_with('0'))
        || !integer.bytes().all(|byte| byte.is_ascii_digit())
        || (!fraction.is_empty() && !fraction.bytes().all(|byte| byte.is_ascii_digit()))
        || (significand.contains('.') && fraction.is_empty())
    {
        return None;
    }
    let digits = format!("{integer}{fraction}");
    let digits = digits.trim_start_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits }.to_string();
    Some(SourceDecimal {
        negative: negative && digits != "0",
        digits,
        scale: fraction.len() as i64 - exponent,
        has_exponent,
    })
}

fn parse_source_exponent(value: &str) -> Option<i64> {
    if value.is_empty() {
        return Some(0);
    }
    let negative = value.starts_with('-');
    let unsigned = if negative || value.starts_with('+') {
        &value[1..]
    } else {
        value
    };
    if unsigned.is_empty() || !unsigned.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let digits = unsigned.trim_start_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits };
    if digits.len() > 7 {
        return None;
    }
    let parsed = digits.parse::<i64>().ok()?;
    if parsed > MAX_SOURCE_DECIMAL_EXPONENT {
        return None;
    }
    Some(if negative { -parsed } else { parsed })
}

fn render_source_decimal(operand: SourceDecimal, trim_fraction_zeros: bool) -> Option<String> {
    let digit_len = operand.digits.len() as i64;
    let extra_length = if operand.scale > digit_len {
        operand.scale - digit_len
    } else {
        (-operand.scale).max(0)
    };
    if operand.digits.len() + extra_length as usize + 2 > MAX_SOURCE_DECIMAL_KEY_LENGTH {
        return None;
    }
    let mut text = if operand.scale <= 0 {
        format!(
            "{}{}",
            operand.digits,
            "0".repeat((-operand.scale) as usize)
        )
    } else if operand.scale >= digit_len {
        format!(
            "0.{}{}",
            "0".repeat((operand.scale - digit_len) as usize),
            operand.digits
        )
    } else {
        let split = operand.digits.len() - operand.scale as usize;
        format!("{}.{}", &operand.digits[..split], &operand.digits[split..])
    };
    if trim_fraction_zeros && text.contains('.') {
        while text.ends_with('0') {
            text.pop();
        }
        if text.ends_with('.') {
            text.pop();
        }
    }
    if operand.negative {
        text.insert(0, '-');
    }
    Some(text)
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

fn unresolved_variable(name: &str) -> Diagnostic {
    Diagnostic::new(
        "unresolved-variable",
        format!("Variable ${name} could not be resolved."),
        0,
        0,
    )
}

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message, 0, 0)
}

fn bad_selector(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-selector", message, 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message, 0, 0)
}

fn fallback_error(error: Diagnostic) -> Diagnostic {
    if error.code == "unsupported-function" {
        Diagnostic::new("unknown-function", error.message, error.start, error.end)
    } else {
        error
    }
}

fn model_error(code: impl Into<String>, message: impl Into<String>) -> Diagnostic {
    Diagnostic::new(code, message, 0, 0)
}

fn fallback_source(expression: &Expression) -> String {
    match (&expression.arg, &expression.function) {
        (Some(arg), _) => expression_arg_source(arg),
        (None, Some(function)) => function_name_source(function),
        (None, None) => String::new(),
    }
}

fn fallback_value(source: &str) -> String {
    format!("{{{source}}}")
}

fn recover_value(handler: Option<&RecoveryHandler>, context: RecoveryContext<'_>) -> String {
    let fallback_value = context.fallback_value.clone();
    handler
        .and_then(|handler| handler(context))
        .unwrap_or(fallback_value)
}

fn expression_source(expression: &Expression) -> String {
    let mut items = Vec::new();
    if let Some(arg) = &expression.arg {
        items.push(expression_arg_source(arg));
    }
    if let Some(function) = &expression.function {
        items.push(function_source(function));
    }
    format!("{{{}}}", items.join(" "))
}

fn expression_variable_name(expression: &Expression) -> Option<&str> {
    match &expression.arg {
        Some(ExpressionArg::Variable { name }) => Some(name),
        _ => None,
    }
}

fn expression_arg_source(arg: &ExpressionArg) -> String {
    match arg {
        ExpressionArg::Literal { value } => quote_literal_source(value),
        ExpressionArg::Variable { name } => format!("${name}"),
    }
}

fn function_source(function: &FunctionRef) -> String {
    let mut source = format!(":{}", function.name);
    if let Some(options) = &function.options {
        for (name, value) in options {
            source.push(' ');
            source.push_str(name);
            source.push('=');
            source.push_str(&expression_arg_source(value));
        }
    }
    source
}

fn function_name_source(function: &FunctionRef) -> String {
    format!(":{}", function.name)
}

fn quote_literal_source(value: &str) -> String {
    let mut source = String::from("|");
    for ch in value.chars() {
        if ch == '\\' || ch == '|' {
            source.push('\\');
        }
        source.push(ch);
    }
    source.push('|');
    source
}

fn value_to_string(value: &ArgumentValue) -> String {
    value.rendered()
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
            FormattedPart::Fallback { source, value } => {
                if let Some(value) = value {
                    output.push_str(value);
                } else {
                    output.push_str(&fallback_value(source));
                }
            }
            FormattedPart::Expression { value, dir, .. } => {
                push_expression(
                    &mut output,
                    value,
                    bidi_isolation,
                    dir.as_deref().and_then(bidi_direction_from_name),
                );
            }
            FormattedPart::Markup { .. } => {}
        }
    }
    output
}

fn push_expression(
    output: &mut String,
    value: &str,
    bidi_isolation: BidiIsolation,
    direction: Option<BidiDirection>,
) {
    match bidi_isolation {
        BidiIsolation::None => output.push_str(value),
        BidiIsolation::Default => {
            output.push(direction.unwrap_or(BidiDirection::Auto).marker());
            output.push_str(value);
            output.push('\u{2069}');
        }
    }
}

fn bidi_direction_from_name(value: &str) -> Option<BidiDirection> {
    match value {
        "auto" => Some(BidiDirection::Auto),
        "ltr" => Some(BidiDirection::Ltr),
        "rtl" => Some(BidiDirection::Rtl),
        _ => None,
    }
}
