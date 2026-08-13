use serde::Serialize;
use serde_json::{Map, Value};
use std::collections::BTreeMap;

/// Native resource formats sharing the canonical FormatJS-compatible catalog.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FileFormat {
    Android,
    AppleStrings,
    AppleStringsdict,
    AppleXcstrings,
    GettextPo,
    JavaProperties,
    FormatJsJson,
    Yaml,
    JavaScript,
    TypeScript,
}

impl FileFormat {
    pub fn from_id(id: &str) -> Option<Self> {
        Some(match id {
            "android" => Self::Android,
            "apple_strings" => Self::AppleStrings,
            "apple_stringsdict" => Self::AppleStringsdict,
            "apple_xcstrings" => Self::AppleXcstrings,
            "gettext_po" => Self::GettextPo,
            "java_properties" => Self::JavaProperties,
            "formatjs_json" => Self::FormatJsJson,
            "yaml" => Self::Yaml,
            "javascript" => Self::JavaScript,
            "typescript" => Self::TypeScript,
            _ => return None,
        })
    }

    pub fn id(self) -> &'static str {
        match self {
            Self::Android => "android",
            Self::AppleStrings => "apple_strings",
            Self::AppleStringsdict => "apple_stringsdict",
            Self::AppleXcstrings => "apple_xcstrings",
            Self::GettextPo => "gettext_po",
            Self::JavaProperties => "java_properties",
            Self::FormatJsJson => "formatjs_json",
            Self::Yaml => "yaml",
            Self::JavaScript => "javascript",
            Self::TypeScript => "typescript",
        }
    }
}

/// Stable parser failure shared with Java and the implementation-neutral fixtures.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ParseError {
    pub code: &'static str,
    pub message: String,
}

impl ParseError {
    pub(crate) fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl std::fmt::Display for ParseError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for ParseError {}

/// Version-one canonical catalog containing FormatJS-compatible descriptors.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Catalog {
    pub schema_version: u8,
    pub source_format: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub locale: Option<String>,
    pub messages: BTreeMap<String, Message>,
}

impl Catalog {
    pub(crate) fn new(format: FileFormat) -> Self {
        Self {
            schema_version: 1,
            source_format: format.id(),
            locale: None,
            messages: BTreeMap::new(),
        }
    }

    pub(crate) fn insert(&mut self, id: String, message: Message) -> Result<(), ParseError> {
        if id.chars().all(java_whitespace) {
            return Err(ParseError::new("INVALID_MESSAGE_ID", "Empty message ID"));
        }
        if self.messages.insert(id.clone(), message).is_some() {
            return Err(ParseError::new(
                "DUPLICATE_MESSAGE_ID",
                format!("Duplicate message ID: {id}"),
            ));
        }
        Ok(())
    }
}

/// FormatJS descriptor plus source-format information needed for future writers.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub default_message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub variants: Option<BTreeMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub placeholders: Option<Vec<Placeholder>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<Map<String, Value>>,
}

impl Message {
    pub(crate) fn new(
        default_message: String,
        description: Option<String>,
        variants: Option<BTreeMap<String, String>>,
        placeholders: Vec<Placeholder>,
        metadata: Map<String, Value>,
    ) -> Self {
        Self {
            default_message,
            description: description
                .filter(|value| value.chars().any(|character| !java_whitespace(character))),
            variants: variants.filter(|values| !values.is_empty()),
            placeholders: (!placeholders.is_empty()).then_some(placeholders),
            metadata: (!metadata.is_empty()).then_some(metadata),
        }
    }
}

pub(crate) fn java_whitespace(character: char) -> bool {
    matches!(
        character,
        '\u{0009}'..='\u{000d}'
            | '\u{001c}'..='\u{0020}'
            | '\u{1680}'
            | '\u{2000}'..='\u{2006}'
            | '\u{2008}'..='\u{200a}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{205f}'
            | '\u{3000}'
    )
}

/// Original native printf spelling retained alongside the normalized argument.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct Placeholder {
    pub name: String,
    pub source: String,
    pub kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub example: Option<String>,
}
