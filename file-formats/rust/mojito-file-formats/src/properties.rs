use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::placeholders;
use serde_json::{json, Map};

pub(crate) fn parse(source: &str) -> Result<Catalog, ParseError> {
    parse_properties(source, false)
}

pub(crate) fn parse_for_mojito(source: &str) -> Result<Catalog, ParseError> {
    parse_properties(source, true)
}

fn parse_properties(
    source: &str,
    preserve_comment_whitespace: bool,
) -> Result<Catalog, ParseError> {
    let mut catalog = Catalog::new(FileFormat::JavaProperties);
    let mut comments = Vec::new();
    for logical in logical_lines(source) {
        let line = logical.trim_start_matches(property_whitespace);
        if line.is_empty() {
            comments.clear();
            continue;
        }
        if line.starts_with('#') || line.starts_with('!') {
            let comment = &line[1..];
            comments.push(if preserve_comment_whitespace {
                comment.to_owned()
            } else {
                comment
                    .trim_matches(crate::model::java_whitespace)
                    .to_owned()
            });
            continue;
        }
        let mut key_end = 0;
        let mut escaped = false;
        for (index, character) in line.char_indices() {
            if !escaped && (matches!(character, '=' | ':') || property_whitespace(character)) {
                break;
            }
            escaped = character == '\\' && !escaped;
            key_end = index + character.len_utf8();
        }
        let mut remainder = &line[key_end..];
        remainder = remainder.trim_start_matches(property_whitespace);
        if remainder.starts_with('=') || remainder.starts_with(':') {
            remainder = &remainder[1..];
        }
        remainder = remainder.trim_start_matches(property_whitespace);
        let key = unescape(&line[..key_end])?;
        let value = unescape(remainder)?;
        let mut placeholders = Vec::new();
        let message = placeholders::normalize(&value, &mut placeholders, None);
        let mut metadata = Map::new();
        let escaped_percents = placeholders::escaped_percent_positions(&value);
        if !escaped_percents.is_empty() {
            metadata.insert(
                "javaPropertiesEscapedPercents".into(),
                json!(escaped_percents),
            );
        }
        let line_separators = placeholders::printf_line_separators(&value);
        if !line_separators.is_empty() {
            metadata.insert("javaPropertiesPrintfLineSeparator".into(), json!(true));
            metadata.insert(
                "javaPropertiesPrintfLineSeparators".into(),
                json!(line_separators
                    .into_iter()
                    .map(|(position, source)| json!({ "position": position, "source": source }))
                    .collect::<Vec<_>>()),
            );
        }
        let descriptor = Message::new(
            message,
            Some(comments.join(" ")),
            None,
            placeholders,
            metadata,
        );
        if catalog.messages.get(&key) != Some(&descriptor) {
            catalog.insert(key, descriptor)?;
        }
        comments.clear();
    }
    Ok(catalog)
}

fn logical_lines(source: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut continuing = false;
    let mut continued = false;
    let natural = physical_lines(source);
    for (index, physical) in natural.iter().enumerate() {
        let leading = physical.trim_start_matches(property_whitespace);
        if !continuing && (leading.starts_with('#') || leading.starts_with('!')) {
            result.push((*physical).to_owned());
            continue;
        }
        if continuing {
            current.push_str(physical.trim_start_matches(property_whitespace));
        } else {
            current.push_str(physical);
        }
        let slashes = current
            .chars()
            .rev()
            .take_while(|value| *value == '\\')
            .count();
        continuing = index + 1 < natural.len() && slashes % 2 == 1;
        if continuing {
            current.pop();
            continued = true;
        } else {
            result.push(
                if continued && current.trim_matches(property_whitespace).is_empty() {
                    "\\".to_owned()
                } else {
                    std::mem::take(&mut current)
                },
            );
            current.clear();
            continued = false;
        }
    }
    if !current.is_empty() {
        result.push(current);
    }
    result
}

fn physical_lines(source: &str) -> Vec<&str> {
    let mut result = Vec::new();
    let mut start = 0;
    let mut index = 0;
    let bytes = source.as_bytes();
    while index < bytes.len() {
        if matches!(bytes[index], b'\n' | b'\r') {
            result.push(&source[start..index]);
            if bytes[index] == b'\r' && bytes.get(index + 1) == Some(&b'\n') {
                index += 1;
            }
            start = index + 1;
        }
        index += 1;
    }
    result.push(&source[start..]);
    result
}

pub(crate) fn unescape(input: &str) -> Result<String, ParseError> {
    let mut result = String::new();
    let mut characters = input.chars().peekable();
    while let Some(character) = characters.next() {
        if character != '\\' {
            result.push(character);
            continue;
        }
        let Some(escaped) = characters.next() else {
            break;
        };
        match escaped {
            't' => result.push('\t'),
            'r' => result.push('\r'),
            'n' => result.push('\n'),
            'f' => result.push('\u{000c}'),
            'u' => {
                let first = unicode_unit(&mut characters)?;
                if (0xd800..=0xdbff).contains(&first) {
                    if characters.next() != Some('\\') || characters.next() != Some('u') {
                        return Err(ParseError::new(
                            "INVALID_UNICODE_ESCAPE",
                            "Missing low surrogate",
                        ));
                    }
                    let second = unicode_unit(&mut characters)?;
                    let character = char::decode_utf16([first, second])
                        .next()
                        .unwrap()
                        .map_err(|_| {
                            ParseError::new("INVALID_UNICODE_ESCAPE", "Invalid surrogate")
                        })?;
                    result.push(character);
                } else {
                    result.push(char::from_u32(first as u32).ok_or_else(|| {
                        ParseError::new("INVALID_UNICODE_ESCAPE", "Invalid scalar")
                    })?);
                }
            }
            other => result.push(other),
        }
    }
    Ok(result)
}

fn unicode_unit(
    characters: &mut std::iter::Peekable<std::str::Chars<'_>>,
) -> Result<u16, ParseError> {
    let digits: String = characters.take(4).collect();
    if digits.len() != 4 {
        return Err(ParseError::new(
            "INVALID_UNICODE_ESCAPE",
            "Short Unicode escape",
        ));
    }
    u16::from_str_radix(&digits, 16)
        .map_err(|_| ParseError::new("INVALID_UNICODE_ESCAPE", "Invalid Unicode escape"))
}

fn property_whitespace(character: char) -> bool {
    matches!(character, ' ' | '\t' | '\u{000c}')
}
