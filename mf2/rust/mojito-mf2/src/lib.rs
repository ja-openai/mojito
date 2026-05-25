mod cldr;
#[allow(dead_code)]
mod cldr_plural_rules;
mod diagnostic;
mod locale_key;
mod model;
mod parser;
mod formatter;

pub use diagnostic::Diagnostic;
pub use model::{
    AttributeValue, Declaration, Expression, ExpressionArg, FunctionRef, Markup, MessageModel,
    Pattern, PatternPart, VariableRef, Variant, VariantKey,
};
pub use parser::{parse_to_model, ParseResult};
pub use formatter::{
    format_message, format_message_to_parts, format_message_to_parts_with_options,
    format_message_with_options, ArgumentValue, Arguments, BidiIsolation, FormatOptions,
    FormatResult, FormattedPart, FunctionCall, FunctionMatch, FunctionRegistry, FunctionSourceRef,
    PartsResult, RecoveryContext, RecoveryHandler,
};

pub type Mf2ParseResult = ParseResult;
