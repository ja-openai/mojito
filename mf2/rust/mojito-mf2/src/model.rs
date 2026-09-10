use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum MessageModel {
    #[serde(rename = "message")]
    Message {
        declarations: Vec<Declaration>,
        pattern: Pattern,
    },
    #[serde(rename = "select")]
    Select {
        declarations: Vec<Declaration>,
        selectors: Vec<VariableRef>,
        variants: Vec<Variant>,
    },
}

impl MessageModel {
    pub(crate) fn declarations(&self) -> &[Declaration] {
        match self {
            MessageModel::Message { declarations, .. } => declarations,
            MessageModel::Select { declarations, .. } => declarations,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Declaration {
    #[serde(rename = "input")]
    Input { name: String, value: Expression },
    #[serde(rename = "local")]
    Local { name: String, value: Expression },
}

pub type Pattern = Vec<PatternPart>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum PatternPart {
    Text(String),
    Expression(Expression),
    Markup(Markup),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Expression {
    #[serde(rename = "type")]
    expression_type: ExpressionType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub arg: Option<ExpressionArg>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub function: Option<FunctionRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub attributes: Option<BTreeMap<String, AttributeValue>>,
}

impl Expression {
    pub fn variable(name: impl Into<String>) -> Self {
        Self {
            expression_type: ExpressionType::Expression,
            arg: Some(ExpressionArg::Variable { name: name.into() }),
            function: None,
            attributes: None,
        }
    }

    pub fn literal(value: impl Into<String>) -> Self {
        Self {
            expression_type: ExpressionType::Expression,
            arg: Some(ExpressionArg::Literal {
                value: value.into(),
            }),
            function: None,
            attributes: None,
        }
    }

    pub fn function(function: FunctionRef) -> Self {
        Self::function_only().with_function(function)
    }

    pub(crate) fn function_only() -> Self {
        Self {
            expression_type: ExpressionType::Expression,
            arg: None,
            function: None,
            attributes: None,
        }
    }

    pub fn with_function(mut self, function: FunctionRef) -> Self {
        self.function = Some(function);
        self
    }

    pub fn with_attributes(mut self, attributes: BTreeMap<String, AttributeValue>) -> Self {
        self.attributes = if attributes.is_empty() {
            None
        } else {
            Some(attributes)
        };
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum ExpressionType {
    Expression,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ExpressionArg {
    #[serde(rename = "literal")]
    Literal { value: String },
    #[serde(rename = "variable")]
    Variable { name: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VariableRef {
    #[serde(rename = "type")]
    variable_type: VariableType,
    pub name: String,
}

impl VariableRef {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            variable_type: VariableType::Variable,
            name: name.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum VariableType {
    Variable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FunctionRef {
    #[serde(rename = "type")]
    function_type: FunctionType,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub options: Option<BTreeMap<String, ExpressionArg>>,
}

impl FunctionRef {
    pub fn new(name: impl Into<String>, options: BTreeMap<String, ExpressionArg>) -> Self {
        Self {
            function_type: FunctionType::Function,
            name: name.into(),
            options: if options.is_empty() {
                None
            } else {
                Some(options)
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum FunctionType {
    Function,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Markup {
    #[serde(rename = "type")]
    markup_type: MarkupType,
    pub kind: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub options: Option<BTreeMap<String, ExpressionArg>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub attributes: Option<BTreeMap<String, AttributeValue>>,
}

impl Markup {
    pub fn new(kind: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            markup_type: MarkupType::Markup,
            kind: kind.into(),
            name: name.into(),
            options: None,
            attributes: None,
        }
    }

    pub fn with_options(mut self, options: BTreeMap<String, ExpressionArg>) -> Self {
        self.options = if options.is_empty() {
            None
        } else {
            Some(options)
        };
        self
    }

    pub fn with_attributes(mut self, attributes: BTreeMap<String, AttributeValue>) -> Self {
        self.attributes = if attributes.is_empty() {
            None
        } else {
            Some(attributes)
        };
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum MarkupType {
    Markup,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum AttributeValue {
    Literal(ExpressionArg),
    Present(bool),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Variant {
    pub keys: Vec<VariantKey>,
    pub value: Pattern,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum VariantKey {
    #[serde(rename = "literal")]
    Literal { value: String },
    #[serde(rename = "*")]
    CatchAll,
}

// Keep the source model spelling intact while resolving canonically equivalent
// variable names together. The common already-normalized model stays borrowed.
impl MessageModel {
    pub(crate) fn normalized_variable_names(&self) -> std::borrow::Cow<'_, Self> {
        use unicode_normalization::UnicodeNormalization;
        fn needs(name: &str) -> bool {
            !name.is_ascii() && !name.nfc().eq(name.chars())
        }
        fn arg_needs(arg: &ExpressionArg) -> bool {
            matches!(arg, ExpressionArg::Variable { name } if needs(name))
        }
        fn expression_needs(value: &Expression) -> bool {
            value.arg.as_ref().is_some_and(arg_needs)
                || value
                    .function
                    .as_ref()
                    .and_then(|f| f.options.as_ref())
                    .is_some_and(|options| options.values().any(arg_needs))
        }
        fn pattern_needs(pattern: &Pattern) -> bool {
            pattern.iter().any(|part| match part {
                PatternPart::Expression(value) => expression_needs(value),
                PatternPart::Markup(value) => value
                    .options
                    .as_ref()
                    .is_some_and(|options| options.values().any(arg_needs)),
                _ => false,
            })
        }
        let needed = self
            .declarations()
            .iter()
            .any(|declaration| match declaration {
                Declaration::Input { name, value } | Declaration::Local { name, value } => {
                    needs(name) || expression_needs(value)
                }
            })
            || match self {
                Self::Message { pattern, .. } => pattern_needs(pattern),
                Self::Select {
                    selectors,
                    variants,
                    ..
                } => {
                    selectors.iter().any(|selector| needs(&selector.name))
                        || variants.iter().any(|variant| pattern_needs(&variant.value))
                }
            };
        if !needed {
            return std::borrow::Cow::Borrowed(self);
        }
        fn normalize(name: &mut String) {
            *name = name.nfc().collect();
        }
        fn normalize_arg(arg: &mut ExpressionArg) {
            if let ExpressionArg::Variable { name } = arg {
                normalize(name);
            }
        }
        fn normalize_expression(value: &mut Expression) {
            if let Some(arg) = value.arg.as_mut() {
                normalize_arg(arg);
            }
            if let Some(options) = value.function.as_mut().and_then(|f| f.options.as_mut()) {
                for arg in options.values_mut() {
                    normalize_arg(arg);
                }
            }
        }
        fn normalize_pattern(pattern: &mut Pattern) {
            for part in pattern {
                match part {
                    PatternPart::Expression(value) => normalize_expression(value),
                    PatternPart::Markup(value) => {
                        if let Some(options) = value.options.as_mut() {
                            for arg in options.values_mut() {
                                normalize_arg(arg);
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
        let mut normalized = self.clone();
        let (Self::Message { declarations, .. } | Self::Select { declarations, .. }) =
            &mut normalized;
        for declaration in declarations {
            match declaration {
                Declaration::Input { name, value } | Declaration::Local { name, value } => {
                    normalize(name);
                    normalize_expression(value);
                }
            }
        }
        match &mut normalized {
            Self::Message { pattern, .. } => normalize_pattern(pattern),
            Self::Select {
                selectors,
                variants,
                ..
            } => {
                for selector in selectors {
                    normalize(&mut selector.name);
                }
                for variant in variants {
                    normalize_pattern(&mut variant.value);
                }
            }
        }
        std::borrow::Cow::Owned(normalized)
    }
}
