use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use regex::Regex;
use std::collections::{HashMap, HashSet};
use std::fmt::Write;
use std::sync::OnceLock;

pub(crate) fn write(catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != FileFormat::JavaProperties.id() {
        return Err(ParseError::new(
            "INVALID_SOURCE_FORMAT",
            "Java properties writer requires a Java properties catalog",
        ));
    }
    let mut output = String::new();
    for (key, message) in &catalog.messages {
        if message.variants.is_some() {
            return Err(ParseError::new(
                "UNSUPPORTED_PROPERTIES_VARIANTS",
                "Java properties cannot represent plural variants",
            ));
        }
        if let Some(description) = &message.description {
            if description.contains(['\n', '\r']) {
                return Err(ParseError::new(
                    "INVALID_PROPERTIES_COMMENT",
                    "Java properties comments must fit on one line",
                ));
            }
            output.push_str("# ");
            output.push_str(description);
            output.push('\n');
        }
        output.push_str(&escape(key, true));
        output.push('=');
        output.push_str(&escape(&restore(message), false));
        output.push('\n');
    }
    Ok(output)
}

fn restore(message: &Message) -> String {
    restore_canonical(message, &message.default_message)
}

pub(crate) fn render_source(message: &Message, canonical: &str) -> String {
    escape_value(&restore_canonical(message, canonical), false, false)
}

fn restore_canonical(message: &Message, canonical: &str) -> String {
    let mut placeholders: HashMap<&str, Vec<&Placeholder>> = HashMap::new();
    for placeholder in message.placeholders.iter().flatten() {
        placeholders
            .entry(&placeholder.name)
            .or_default()
            .push(placeholder);
    }
    let escaped_percents: HashSet<usize> = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("javaPropertiesEscapedPercents"))
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(serde_json::Value::as_u64)
        .filter_map(|offset| usize::try_from(offset).ok())
        .collect();
    let printf_line_separator = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("javaPropertiesPrintfLineSeparator"))
        == Some(&serde_json::Value::Bool(true));
    let line_separators: HashMap<usize, &str> = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("javaPropertiesPrintfLineSeparators"))
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|separator| {
            Some((
                usize::try_from(separator.get("position")?.as_u64()?).ok()?,
                separator.get("source")?.as_str()?,
            ))
        })
        .collect();
    let mut occurrences = HashMap::new();
    let mut output = String::new();
    let mut previous = 0;
    let mut scalar_offset = 0;
    for captures in argument_pattern().captures_iter(canonical) {
        let matched = captures.get(0).expect("argument match");
        let name = captures.get(1).expect("argument name").as_str();
        let Some(choices) = placeholders.get(name) else {
            continue;
        };
        scalar_offset = append_literal(
            &mut output,
            &canonical[previous..matched.start()],
            scalar_offset,
            &escaped_percents,
            printf_line_separator,
            &line_separators,
        );
        let occurrence = occurrences.entry(name).or_insert(0_usize);
        output.push_str(&choices[(*occurrence).min(choices.len() - 1)].source);
        *occurrence += 1;
        scalar_offset += matched.as_str().chars().count();
        previous = matched.end();
    }
    append_literal(
        &mut output,
        &canonical[previous..],
        scalar_offset,
        &escaped_percents,
        printf_line_separator,
        &line_separators,
    );
    output
}

fn append_literal(
    output: &mut String,
    value: &str,
    mut scalar_offset: usize,
    escaped_percents: &HashSet<usize>,
    printf_line_separator: bool,
    line_separators: &HashMap<usize, &str>,
) -> usize {
    for character in value.chars() {
        if character == '%' && escaped_percents.contains(&scalar_offset) {
            output.push_str("%%");
        } else if character == '\n' {
            if let Some(source) = line_separators.get(&scalar_offset) {
                output.push_str(source);
            } else if printf_line_separator && line_separators.is_empty() {
                output.push_str("%n");
            } else {
                output.push(character);
            }
        } else {
            output.push(character);
        }
        scalar_offset += 1;
    }
    scalar_offset
}

fn escape(value: &str, key: bool) -> String {
    escape_value(value, key, true)
}

fn escape_value(value: &str, key: bool, normalized: bool) -> String {
    let mut output = String::new();
    let mut leading = true;
    for character in value.chars() {
        match character {
            ' ' => {
                if key || leading {
                    output.push('\\');
                }
                output.push(' ');
            }
            '\t' => output.push_str("\\t"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\u{000c}' => output.push_str("\\f"),
            '\\' => output.push_str("\\\\"),
            '#' | '!' | '=' | ':' => {
                if key || normalized {
                    output.push('\\');
                }
                output.push(character);
            }
            value if value < ' ' || value == '\u{007f}' => {
                write!(&mut output, "\\u{:04X}", value as u32).expect("writing to String")
            }
            _ => output.push(character),
        }
        if character != ' ' {
            leading = false;
        }
    }
    output
}

fn argument_pattern() -> &'static Regex {
    static ARGUMENT: OnceLock<Regex> = OnceLock::new();
    ARGUMENT.get_or_init(|| Regex::new(r"\{([A-Za-z_][A-Za-z0-9_.-]*)\}").expect("valid argument"))
}
