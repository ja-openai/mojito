use crate::model::{ParseError, Placeholder};
use regex::Regex;
use std::sync::OnceLock;

pub(crate) fn normalize(
    input: &str,
    placeholders: &mut Vec<Placeholder>,
    forced_name: Option<&str>,
) -> String {
    normalize_at_position(input, placeholders, forced_name, None, None, false)
}

pub(crate) fn normalize_foundation(input: &str, placeholders: &mut Vec<Placeholder>) -> String {
    normalize_at_position(input, placeholders, None, None, None, true)
}

pub(crate) fn normalize_foundation_plural(
    input: &str,
    placeholders: &mut Vec<Placeholder>,
    name: &str,
    position: Option<usize>,
) -> String {
    normalize_at_position(input, placeholders, Some(name), position, None, true)
}

pub(crate) fn normalize_foundation_substitution(
    input: &str,
    placeholders: &mut Vec<Placeholder>,
    name: &str,
    position: usize,
) -> String {
    normalize_at_position(
        input,
        placeholders,
        Some(name),
        Some(position),
        Some(position),
        true,
    )
}

pub(crate) fn normalize_plural(
    input: &str,
    placeholders: &mut Vec<Placeholder>,
    name: &str,
    position: Option<usize>,
) -> String {
    normalize_at_position(input, placeholders, Some(name), position, None, false)
}

fn normalize_at_position(
    input: &str,
    placeholders: &mut Vec<Placeholder>,
    forced_name: Option<&str>,
    plural_position: Option<usize>,
    implicit_start: Option<usize>,
    foundation: bool,
) -> String {
    let pattern = printf_pattern();
    let mut result = String::new();
    let mut previous = 0;
    let mut implicit_position = implicit_start.map_or(0, |position| position - 1);
    let mut previous_position = None;
    let mut previous_name: Option<String> = None;
    for captures in pattern.captures_iter(input) {
        let matched = captures.get(0).expect("whole match");
        result.push_str(&input[previous..matched.start()]);
        let named = captures.get(1).map(|capture| capture.as_str());
        let conversion = captures
            .get(if named.is_some() { 2 } else { 4 })
            .expect("conversion")
            .as_str();
        if conversion == "%" {
            result.push('%');
        } else if conversion == "n" && named.is_none() {
            result.push('\n');
            if foundation && captures.get(3).is_none() {
                implicit_position += 1;
            }
        } else {
            let reuse_previous = named.is_none() && matched.as_str().contains('<');
            if reuse_previous && previous_name.is_none() {
                // Java's Formatter rejects relative arguments before an initial argument.
                // Stable diagnostics are added at the parser boundary in the next parity slice.
                result.push_str(matched.as_str());
                previous = matched.end();
                continue;
            }
            let kind = if matched.as_str().as_bytes().get(matched.as_str().len() - 2) == Some(&b't')
            {
                "value"
            } else {
                match conversion {
                    "@" | "s" | "S" => "string",
                    "d" | "i" | "u" | "o" | "x" | "X" => "integer",
                    "f" | "F" | "e" | "E" | "g" | "G" | "a" | "A" => "number",
                    "c" | "C" => "character",
                    _ => "value",
                }
            };
            let numeric = matches!(kind, "integer" | "number");
            if foundation
                && captures.get(3).is_none()
                && !reuse_previous
                && plural_position.is_some()
                && numeric
                && implicit_start.is_none()
            {
                implicit_position += 1;
            }
            let position = if named.is_some() {
                None
            } else if reuse_previous {
                previous_position
            } else {
                Some(
                    captures
                        .get(3)
                        .map(|value| value.as_str().parse().expect("numeric printf position"))
                        .unwrap_or_else(|| {
                            if numeric && implicit_start.is_none() {
                                if let Some(position) = plural_position {
                                    return position;
                                }
                            }
                            implicit_position += 1;
                            implicit_position
                        }),
                )
            };
            let name = forced_name
                .filter(|_| {
                    !foundation && plural_position.is_none()
                        || numeric && (plural_position.is_none() || position == plural_position)
                })
                .map(str::to_owned)
                .or_else(|| named.map(str::to_owned))
                .or_else(|| reuse_previous.then(|| previous_name.clone()).flatten())
                .unwrap_or_else(|| format!("arg{}", position.unwrap() - 1));
            let placeholder = Placeholder {
                name: name.clone(),
                source: matched.as_str().to_owned(),
                kind,
                position,
                example: None,
            };
            if !placeholders.contains(&placeholder) {
                placeholders.push(placeholder);
            }
            result.push('{');
            result.push_str(&name);
            result.push('}');
            previous_position = position;
            previous_name = Some(name);
        }
        previous = matched.end();
    }
    result.push_str(&input[previous..]);
    result
}

pub(crate) fn validate_android(input: &str) -> Result<(), ParseError> {
    let mut substitutions = 0;
    let mut implicit = false;
    let bytes = input.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' && index + 1 < bytes.len() {
            index += 1;
            if matches!(bytes[index], b'%' | b'n') {
                index += 1;
                continue;
            }
            substitutions += 1;
            let first_digit = index;
            while index < bytes.len() && bytes[index].is_ascii_digit() {
                index += 1;
            }
            if index > first_digit {
                if index < bytes.len() && bytes[index] != b'$' {
                    implicit = true;
                }
            } else if bytes[index] == b'<' {
                implicit = true;
                index += 1;
                if index < bytes.len() && bytes[index] == b'$' {
                    index += 1;
                }
            } else {
                implicit = true;
            }
            while index < bytes.len()
                && (matches!(bytes[index], b'-' | b'#' | b'+' | b' ' | b',' | b'(')
                    || bytes[index].is_ascii_digit())
            {
                index += 1;
            }
            if index < bytes.len()
                && matches!(
                    bytes[index],
                    b'D' | b'F' | b'K' | b'M' | b'W' | b'Z' | b'k' | b'm' | b'w' | b'y' | b'z'
                )
            {
                return Ok(());
            }
        }
        if index < bytes.len() {
            index += 1;
        }
    }
    if substitutions > 1 && implicit {
        return Err(ParseError::new(
            "INVALID_PLACEHOLDER",
            "Android requires explicit positions for multiple substitutions",
        ));
    }
    Ok(())
}

pub(crate) fn escaped_percent_positions(input: &str) -> Vec<usize> {
    printf_pattern()
        .find_iter(input)
        .filter(|matched| matched.as_str() == "%%")
        .map(|matched| {
            normalize(&input[..matched.start()], &mut Vec::new(), None)
                .chars()
                .count()
        })
        .collect()
}

pub(crate) fn raw_percent_occurrences(input: &str) -> Vec<usize> {
    let escaped = escaped_percent_positions(input);
    let normalized = normalize(input, &mut Vec::new(), None);
    let mut occurrence = 0;
    let mut raw = Vec::new();
    for (position, character) in normalized.chars().enumerate() {
        if character == '%' {
            if !escaped.contains(&position) {
                raw.push(occurrence);
            }
            occurrence += 1;
        }
    }
    raw
}

pub(crate) fn printf_line_separators(input: &str) -> Vec<(usize, String)> {
    printf_line_separators_at_position(input, None, None)
}

pub(crate) fn foundation_printf_line_separators(
    input: &str,
) -> Vec<(usize, String, Option<usize>)> {
    foundation_line_separators(input, None, None, None)
}

pub(crate) fn foundation_plural_printf_line_separators(
    input: &str,
    name: &str,
    position: Option<usize>,
) -> Vec<(usize, String, Option<usize>)> {
    foundation_line_separators(input, Some(name), position, None)
}

pub(crate) fn foundation_substitution_printf_line_separators(
    input: &str,
    name: &str,
    position: usize,
) -> Vec<(usize, String, Option<usize>)> {
    foundation_line_separators(input, Some(name), Some(position), Some(position))
}

fn foundation_line_separators(
    input: &str,
    forced_name: Option<&str>,
    plural_position: Option<usize>,
    implicit_start: Option<usize>,
) -> Vec<(usize, String, Option<usize>)> {
    let mut separators = Vec::new();
    let mut implicit_position = implicit_start.map_or(0, |position| position - 1);
    let mut visible_after_disabled = false;
    for captures in printf_pattern().captures_iter(input) {
        let matched = captures.get(0).expect("whole printf match");
        let named = captures.get(1).is_some();
        let conversion = captures
            .get(if named { 2 } else { 4 })
            .expect("printf conversion")
            .as_str();
        if conversion == "%" {
            continue;
        }
        let numeric = matches!(
            conversion,
            "d" | "i" | "u" | "o" | "x" | "X" | "f" | "F" | "e" | "E" | "g" | "G" | "a" | "A"
        );
        let position = if named || matched.as_str().contains('<') {
            None
        } else if let Some(explicit) = captures.get(3) {
            Some(explicit.as_str().parse().expect("numeric printf position"))
        } else {
            implicit_position += 1;
            Some(
                plural_position
                    .filter(|_| numeric && implicit_start.is_none())
                    .unwrap_or(implicit_position),
            )
        };
        if conversion == "n" && !named {
            let prefix = normalize_at_position(
                &input[..matched.start()],
                &mut Vec::new(),
                forced_name,
                plural_position,
                implicit_start,
                true,
            );
            separators.push((
                prefix.chars().count(),
                matched.as_str().to_owned(),
                position,
            ));
        } else {
            visible_after_disabled |= !separators.is_empty();
        }
    }
    if !visible_after_disabled {
        for (_, _, argument_position) in &mut separators {
            *argument_position = None;
        }
    }
    separators
}

pub(crate) fn named_printf_line_separators(input: &str, name: &str) -> Vec<(usize, String)> {
    printf_line_separators_at_position(input, Some(name), None)
}

fn printf_line_separators_at_position(
    input: &str,
    name: Option<&str>,
    position: Option<usize>,
) -> Vec<(usize, String)> {
    printf_pattern()
        .captures_iter(input)
        .filter(|captures| {
            captures.get(1).is_none()
                && captures.get(4).map(|conversion| conversion.as_str()) == Some("n")
        })
        .map(|captures| {
            let matched = captures.get(0).expect("whole printf match");
            (
                normalize_at_position(
                    &input[..matched.start()],
                    &mut Vec::new(),
                    name,
                    position,
                    None,
                    false,
                )
                .chars()
                .count(),
                matched.as_str().to_owned(),
            )
        })
        .collect()
}

pub(crate) fn printf_line_separator_occurrences(input: &str) -> Vec<usize> {
    let positions = printf_line_separators(input)
        .into_iter()
        .map(|(position, _)| position)
        .collect::<Vec<_>>();
    let normalized = normalize(input, &mut Vec::new(), None);
    let mut occurrence = 0;
    let mut selected = Vec::new();
    for (position, character) in normalized.chars().enumerate() {
        if character == '\n' {
            if positions.contains(&position) {
                selected.push(occurrence);
            }
            occurrence += 1;
        }
    }
    selected
}

fn printf_pattern() -> &'static Regex {
    static PRINTF: OnceLock<Regex> = OnceLock::new();
    PRINTF.get_or_init(|| {
        Regex::new(r"%\(([^)]+)\)([a-zA-Z])|%(?:(\d+)\$)?[-+#0 ,(<]*(?:\d+)?(?:\.\d+)?(?:hh|ll|h|l|L|z|j|t)?([a-zA-Z@%])")
            .expect("valid printf pattern")
    })
}

pub(crate) fn plural<'a>(
    selector: &str,
    variants: impl IntoIterator<Item = (&'a str, &'a str)>,
) -> String {
    let mut message = format!("{{{selector}, plural,");
    for (category, value) in variants {
        message.push(' ');
        message.push_str(category);
        message.push_str(" {");
        let mut quoting_hashes = false;
        for character in value.chars() {
            if character == '#' {
                if !quoting_hashes {
                    message.push('\'');
                    quoting_hashes = true;
                }
            } else if quoting_hashes {
                message.push('\'');
                quoting_hashes = false;
            }
            message.push(character);
        }
        if quoting_hashes {
            message.push('\'');
        }
        message.push('}');
    }
    message.push('}');
    message
}
