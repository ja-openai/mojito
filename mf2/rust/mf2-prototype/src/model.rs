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
    pub(crate) fn variable(name: impl Into<String>) -> Self {
        Self {
            expression_type: ExpressionType::Expression,
            arg: Some(ExpressionArg::Variable { name: name.into() }),
            function: None,
            attributes: None,
        }
    }

    pub(crate) fn literal(value: impl Into<String>) -> Self {
        Self {
            expression_type: ExpressionType::Expression,
            arg: Some(ExpressionArg::Literal {
                value: value.into(),
            }),
            function: None,
            attributes: None,
        }
    }

    pub(crate) fn function_only() -> Self {
        Self {
            expression_type: ExpressionType::Expression,
            arg: None,
            function: None,
            attributes: None,
        }
    }

    pub(crate) fn with_function(mut self, function: FunctionRef) -> Self {
        self.function = Some(function);
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
    pub(crate) fn new(name: impl Into<String>) -> Self {
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
    pub(crate) fn new(name: impl Into<String>, options: BTreeMap<String, ExpressionArg>) -> Self {
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
    pub(crate) fn new(kind: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            markup_type: MarkupType::Markup,
            kind: kind.into(),
            name: name.into(),
            options: None,
            attributes: None,
        }
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
