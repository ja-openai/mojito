use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use regex::Regex;
use std::collections::HashMap;
use std::fmt::Write;
use std::sync::OnceLock;

pub(crate) fn write(catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != FileFormat::AppleStrings.id() {
        return Err(ParseError::new(
            "INVALID_SOURCE_FORMAT",
            "Apple strings writer requires an Apple strings catalog",
        ));
    }
    if catalog.messages.is_empty() {
        return Ok("// Empty localization catalog.\n".to_owned());
    }
    let mut output = String::new();
    for (key, message) in &catalog.messages {
        validate_disabled_conversions(message)?;
        if message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("appleMarkupEscaping"))
            .is_some_and(|value| value.as_str() != Some("icu-quoted-angle"))
        {
            return Err(ParseError::new(
                "INVALID_APPLE_MARKUP",
                "Unsupported Apple strings markup escaping",
            ));
        }
        if message.variants.is_some() {
            return Err(ParseError::new(
                "UNSUPPORTED_APPLE_VARIANTS",
                "Apple strings files cannot represent plural variants",
            ));
        }
        if let Some(description) = &message.description {
            if description.contains(['\n', '\r', '\u{2028}', '\u{2029}']) {
                return Err(ParseError::new(
                    "INVALID_APPLE_COMMENT",
                    "Apple strings comments must fit on one physical line",
                ));
            }
            output.push_str("// ");
            output.push_str(description);
            output.push('\n');
        }
        output.push('"');
        output.push_str(&escape(key, false, false));
        output.push_str("\" = \"");
        output.push_str(&render(message));
        output.push_str("\";\n");
    }
    Ok(output)
}

pub(crate) fn validate_disabled_conversions(message: &Message) -> Result<(), ParseError> {
    let Some(raw) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleDisabledPrintfConversions"))
    else {
        return Ok(());
    };
    let invalid = || {
        ParseError::new(
            "INVALID_APPLE_PRINTF_CONVERSION",
            "Invalid disabled Foundation printf conversion",
        )
    };
    let values = raw
        .as_array()
        .filter(|values| !values.is_empty())
        .ok_or_else(invalid)?;
    let original_length = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleMarkupEscaping"))
        .and_then(serde_json::Value::as_str)
        .filter(|escaping| *escaping == "icu-quoted-angle")
        .map(|_| {
            message
                .default_message
                .replace("'<'", "<")
                .replace("''", "'")
        })
        .unwrap_or_else(|| message.default_message.clone())
        .chars()
        .count();
    let mut previous = 0;
    for value in values {
        let object = value.as_object().ok_or_else(invalid)?;
        let position = object
            .get("position")
            .and_then(serde_json::Value::as_u64)
            .ok_or_else(invalid)? as usize;
        let source = object
            .get("source")
            .and_then(serde_json::Value::as_str)
            .ok_or_else(invalid)?;
        let middle = source
            .strip_prefix('%')
            .and_then(|value| value.strip_suffix('n'))
            .ok_or_else(invalid)?;
        let valid_position = middle.is_empty()
            || middle.strip_suffix('$').is_some_and(|digits| {
                !digits.is_empty()
                    && !digits.starts_with('0')
                    && digits.chars().all(|digit| digit.is_ascii_digit())
            });
        let valid_argument = object.get("argumentPosition").is_none_or(|value| {
            value.as_u64().is_some_and(|position| {
                position > 0
                    && middle
                        .strip_suffix('$')
                        .is_none_or(|digits| digits.parse::<u64>().ok() == Some(position))
            })
        });
        if !(2..=3).contains(&object.len())
            || position < previous
            || position > original_length
            || !valid_position
            || !valid_argument
        {
            return Err(invalid());
        }
        previous = position;
    }
    Ok(())
}

fn render(message: &Message) -> String {
    render_source(message, &message.default_message)
}

pub(crate) fn render_source(message: &Message, canonical: &str) -> String {
    render_value(message, canonical, false)
}

pub(crate) fn native_value(message: &Message, canonical: &str) -> String {
    render_value(message, canonical, true)
}

fn render_value(message: &Message, canonical: &str, native_xml: bool) -> String {
    let mut placeholders: HashMap<&str, Vec<&Placeholder>> = HashMap::new();
    for placeholder in message.placeholders.iter().flatten() {
        placeholders
            .entry(&placeholder.name)
            .or_default()
            .push(placeholder);
    }
    let escaping = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleMarkupEscaping"))
        .and_then(serde_json::Value::as_str);
    let source = if escaping == Some("icu-quoted-angle") {
        canonical.replace("'<'", "<").replace("''", "'")
    } else {
        canonical.to_owned()
    };
    let original = if escaping == Some("icu-quoted-angle") {
        message
            .default_message
            .replace("'<'", "<")
            .replace("''", "'")
    } else {
        message.default_message.clone()
    };
    let mut conversions = DisabledConversions::new(message, &original, &source);
    let mut occurrences = HashMap::new();
    let mut output = String::new();
    let mut previous = 0;
    for captures in argument_pattern().captures_iter(&source) {
        let matched = captures.get(0).expect("argument match");
        let name = captures.get(1).expect("argument name").as_str();
        let Some(choices) = placeholders.get(name) else {
            continue;
        };
        let literal = &source[previous..matched.start()];
        conversions.append_literal(&mut output, literal, native_xml);
        conversions.before_placeholder(&mut output, matched.as_str());
        let occurrence = occurrences.entry(name).or_insert(0_usize);
        let placeholder = &choices[(*occurrence).min(choices.len() - 1)].source;
        output.push_str(&if native_xml {
            placeholder.to_owned()
        } else {
            escape(placeholder, false, false)
        });
        *occurrence += 1;
        previous = matched.end();
    }
    let remaining = &source[previous..];
    conversions.append_literal(&mut output, remaining, native_xml);
    conversions.finish(&mut output);
    output
}

struct DisabledConversions {
    positions: Vec<usize>,
    sources: Vec<String>,
    conversion: usize,
    position: usize,
}

impl DisabledConversions {
    fn new(message: &Message, original: &str, translated: &str) -> Self {
        let mut result = Self {
            positions: Vec::new(),
            sources: Vec::new(),
            conversion: 0,
            position: 0,
        };
        let Some(values) = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("appleDisabledPrintfConversions"))
            .and_then(serde_json::Value::as_array)
        else {
            return result;
        };
        let original_length = original.chars().count();
        let translated_length = translated.chars().count();
        for value in values {
            let position = value
                .get("position")
                .and_then(serde_json::Value::as_u64)
                .expect("valid disabled Foundation conversion") as usize;
            let spelling = value
                .get("source")
                .and_then(serde_json::Value::as_str)
                .expect("valid disabled Foundation conversion");
            result.positions.push(if original_length == 0 {
                0
            } else {
                (position * translated_length + original_length / 2) / original_length
            });
            result.sources.push(spelling.to_owned());
        }
        result
    }

    fn append_literal(&mut self, output: &mut String, value: &str, native_xml: bool) {
        for character in value.chars() {
            self.flush(output, self.position);
            let text = character.to_string();
            output.push_str(&if native_xml {
                native_text(&text, true, false)
            } else {
                escape(&text, true, false)
            });
            self.position += 1;
        }
    }

    fn before_placeholder(&mut self, output: &mut String, placeholder: &str) {
        let end = self.position + placeholder.chars().count();
        self.flush(output, end - 1);
        self.position = end;
    }

    fn finish(&mut self, output: &mut String) {
        self.flush(output, usize::MAX);
    }

    fn flush(&mut self, output: &mut String, limit: usize) {
        while self
            .positions
            .get(self.conversion)
            .is_some_and(|position| *position <= limit)
        {
            output.push_str(&self.sources[self.conversion]);
            self.conversion += 1;
        }
    }
}

fn native_text(value: &str, percent: bool, printf_line_separator: bool) -> String {
    let mut output = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '%' if percent => output.push_str("%%"),
            '\n' if printf_line_separator => output.push_str("%n"),
            _ => output.push(character),
        }
    }
    output
}

fn escape(value: &str, percent: bool, printf_line_separator: bool) -> String {
    let mut output = String::new();
    for character in value.chars() {
        match character {
            '\\' => output.push_str("\\\\"),
            '"' => output.push_str("\\\""),
            '\n' if printf_line_separator => output.push_str("%n"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            '\u{0008}' => output.push_str("\\b"),
            '\u{000c}' => output.push_str("\\f"),
            '%' if percent => output.push_str("%%"),
            value if value < ' ' || value == '\u{007f}' => {
                write!(&mut output, "\\U{:04X}", value as u32).expect("writing to String")
            }
            _ => output.push(character),
        }
    }
    output
}

fn argument_pattern() -> &'static Regex {
    static ARGUMENT: OnceLock<Regex> = OnceLock::new();
    ARGUMENT.get_or_init(|| Regex::new(r"\{([A-Za-z_][A-Za-z0-9_.-]*)\}").expect("valid argument"))
}
