use crate::{Message, ParseError};
use regex::Regex;
use serde_json::{json, Map, Value};
use std::sync::OnceLock;

const DECIMAL_ZERO: [u32; 37] = [
    0x0030, 0x0660, 0x06f0, 0x07c0, 0x0966, 0x09e6, 0x0a66, 0x0ae6, 0x0b66, 0x0be6, 0x0c66, 0x0ce6,
    0x0d66, 0x0de6, 0x0e50, 0x0ed0, 0x0f20, 0x1040, 0x1090, 0x17e0, 0x1810, 0x1946, 0x19d0, 0x1a80,
    0x1a90, 0x1b50, 0x1bb0, 0x1c40, 0x1c50, 0xa620, 0xa8d0, 0xa900, 0xa9d0, 0xa9f0, 0xaa50, 0xabf0,
    0xff10,
];

pub(crate) fn spans(source: &str) -> Vec<Value> {
    let mut result = Vec::new();
    let mut span = 0;
    let mut index = 0;
    while index < source.len() {
        let character = source[index..].chars().next().expect("character");
        if character != '<'
            || !source[index + character.len_utf8()..]
                .chars()
                .next()
                .is_some_and(|next| next.is_ascii_alphabetic())
        {
            index += character.len_utf8();
            continue;
        }
        let Some(end) = tag_end(source, index + 1) else {
            index += character.len_utf8();
            continue;
        };
        let tag = &source[index + 1..end];
        let name_end = tag
            .char_indices()
            .find_map(|(offset, character)| character.is_whitespace().then_some(offset))
            .unwrap_or(tag.len());
        let name = &tag[..name_end];
        if name == "annotation" {
            let mut original = Vec::new();
            let mut encoded = name.to_owned();
            for attribute in attribute_pattern().captures_iter(&tag[name_end..]) {
                let key = attribute.get(1).expect("attribute name").as_str();
                let value = decode_attribute(attribute.get(2).expect("attribute value").as_str());
                original.push(json!({"key": key, "value": value}));
                encoded.push(';');
                encoded.push_str(key);
                encoded.push('=');
                encoded.push_str(&value);
            }
            let runtime = decode_annotations(&encoded);
            if runtime != original {
                result.push(json!({"span": span, "annotations": runtime}));
            }
        }
        span += 1;
        index = end + 1;
    }
    result
}

pub(crate) fn styles(source: &str) -> Result<Vec<Value>, ParseError> {
    let mut result = Vec::new();
    let mut span = 0;
    let mut index = 0;
    while index < source.len() {
        let character = source[index..].chars().next().expect("character");
        if character != '<'
            || !source[index + character.len_utf8()..]
                .chars()
                .next()
                .is_some_and(|next| next.is_ascii_alphabetic())
        {
            index += character.len_utf8();
            continue;
        }
        let Some(end) = tag_end(source, index + 1) else {
            index += character.len_utf8();
            continue;
        };
        let tag = &source[index + 1..end];
        let name_end = tag
            .char_indices()
            .find_map(|(offset, character)| character.is_whitespace().then_some(offset))
            .unwrap_or(tag.len());
        let name = &tag[..name_end];
        if name == "font" || name == "a" {
            let mut attributes = Vec::new();
            let mut encoded = name.to_owned();
            for attribute in attribute_pattern().captures_iter(&tag[name_end..]) {
                let key = attribute.get(1).expect("attribute name").as_str();
                let value = decode_attribute(attribute.get(2).expect("attribute value").as_str());
                attributes.push((key.to_owned(), value.clone()));
                encoded.push(';');
                encoded.push_str(key);
                encoded.push('=');
                encoded.push_str(&value);
            }
            let runtime = style_effects(name, &encoded, &attributes, true)?;
            let original = style_effects(name, &encoded, &attributes, false)?;
            if runtime != original || runtime.iter().any(|effect| effect.get("color").is_some()) {
                result.push(json!({"span": span, "effects": runtime}));
            }
        }
        span += 1;
        index = end + 1;
    }
    Ok(result)
}

pub(crate) fn paragraphs(source: &str) -> Vec<Value> {
    let mut candidates = Vec::new();
    let mut open = Vec::new();
    let mut visible = Vec::new();
    let mut span = 0;
    let mut index = 0;
    while index < source.len() {
        let character = source[index..].chars().next().expect("character");
        let next = &source[index + character.len_utf8()..];
        if character != '<'
            || !next
                .chars()
                .next()
                .is_some_and(|character| character == '/' || character.is_ascii_alphabetic())
        {
            let mut encoded = [0_u16; 2];
            visible.extend_from_slice(character.encode_utf16(&mut encoded));
            index += character.len_utf8();
            continue;
        }
        let Some(end) = tag_end(source, index + 1) else {
            let mut encoded = [0_u16; 2];
            visible.extend_from_slice(character.encode_utf16(&mut encoded));
            index += character.len_utf8();
            continue;
        };
        let closing = next.starts_with('/');
        let tag = &source[index + if closing { 2 } else { 1 }..end];
        let name_end = tag
            .char_indices()
            .find_map(|(index, character)| {
                (character.is_whitespace() || character == '/').then_some(index)
            })
            .unwrap_or(tag.len());
        let name = &tag[..name_end];
        if closing {
            while let Some(candidate) = open.pop() {
                let candidate: OpenParagraph = candidate;
                if candidate.name == name {
                    if let Some(kind) = candidate.kind {
                        candidates.push(ParagraphRange {
                            span: candidate.span,
                            kind,
                            start: candidate.start,
                            end: visible.len(),
                        });
                    }
                    break;
                }
            }
        } else {
            let mut kind = None;
            if name == "li" && tag.trim() == "li" {
                kind = Some("bullet");
            } else if name == "font" {
                let mut encoded = name.to_owned();
                for attribute in attribute_pattern().captures_iter(&tag[name_end..]) {
                    encoded.push(';');
                    encoded.push_str(attribute.get(1).expect("attribute key").as_str());
                    encoded.push('=');
                    encoded.push_str(&decode_attribute(
                        attribute.get(2).expect("attribute value").as_str(),
                    ));
                }
                if subtag(&encoded, "height").is_some() {
                    kind = Some("height");
                }
            }
            let candidate = OpenParagraph {
                name: name.to_owned(),
                span,
                kind,
                start: visible.len(),
            };
            span += 1;
            if tag.ends_with('/') {
                if let Some(kind) = candidate.kind {
                    candidates.push(ParagraphRange {
                        span: candidate.span,
                        kind,
                        start: visible.len(),
                        end: visible.len(),
                    });
                }
            } else {
                open.push(candidate);
            }
        }
        index = end + 1;
    }
    while let Some(candidate) = open.pop() {
        if let Some(kind) = candidate.kind {
            candidates.push(ParagraphRange {
                span: candidate.span,
                kind,
                start: candidate.start,
                end: visible.len(),
            });
        }
    }
    candidates.sort_by_key(|candidate| candidate.span);
    let mut result = Vec::new();
    for candidate in candidates {
        let mut start = candidate.start;
        let mut end = candidate.end;
        if start != 0 && start != visible.len() && visible[start - 1] != b'\n' as u16 {
            start -= 1;
            while start > 0 && visible[start - 1] != b'\n' as u16 {
                start -= 1;
            }
        }
        if end != 0 && end != visible.len() && visible[end - 1] != b'\n' as u16 {
            end += 1;
            while end < visible.len() && visible[end - 1] != b'\n' as u16 {
                end += 1;
            }
        }
        if start != candidate.start || end != candidate.end {
            result.push(json!({
                "span":candidate.span,
                "kind":candidate.kind,
                "sourceStart":candidate.start,
                "sourceEnd":candidate.end,
                "start":start,
                "end":end
            }));
        }
    }
    result
}

pub(crate) fn validate(
    message: &Message,
    source: &str,
    quantity: Option<&str>,
) -> Result<(), ParseError> {
    let metadata = message.metadata.as_ref();
    let singular = metadata.and_then(|values| values.get("androidRuntimeAnnotations"));
    let plural = metadata.and_then(|values| values.get("androidPluralRuntimeAnnotations"));
    if quantity.is_none() && plural.is_some() || quantity.is_some() && singular.is_some() {
        return Err(invalid());
    }
    let Some(quantity) = quantity else {
        return validate_annotations(singular, &spans(source));
    };
    let Some(plural) = plural else {
        return if spans(source).is_empty() {
            Ok(())
        } else {
            Err(invalid())
        };
    };
    let variants = plural.as_object().ok_or_else(invalid)?;
    if variants.is_empty() {
        return Err(invalid());
    }
    for (category, annotations) in variants {
        let source = message
            .variants
            .as_ref()
            .and_then(|variants| variants.get(category))
            .ok_or_else(invalid)?
            .replace("'<'", "<")
            .replace("''", "'");
        validate_annotations(Some(annotations), &spans(&source))?;
    }
    validate_annotations(variants.get(quantity), &spans(source))
}

pub(crate) fn validate_styles(
    message: &Message,
    source: &str,
    quantity: Option<&str>,
) -> Result<(), ParseError> {
    let metadata = message.metadata.as_ref();
    let singular = metadata.and_then(|values| values.get("androidRuntimeStyles"));
    let plural = metadata.and_then(|values| values.get("androidPluralRuntimeStyles"));
    if quantity.is_none() && plural.is_some() || quantity.is_some() && singular.is_some() {
        return Err(invalid_style());
    }
    let Some(quantity) = quantity else {
        return validate_style_spans(singular, &styles(source)?);
    };
    let Some(plural) = plural else {
        return if styles(source)?.is_empty() {
            Ok(())
        } else {
            Err(invalid_style())
        };
    };
    let variants = plural.as_object().ok_or_else(invalid_style)?;
    if variants.is_empty() {
        return Err(invalid_style());
    }
    for (category, effects) in variants {
        let source = message
            .variants
            .as_ref()
            .and_then(|variants| variants.get(category))
            .ok_or_else(invalid_style)?
            .replace("'<'", "<")
            .replace("''", "'");
        validate_style_spans(Some(effects), &styles(&source)?)?;
    }
    validate_style_spans(variants.get(quantity), &styles(source)?)
}

pub(crate) fn validate_paragraphs(
    message: &Message,
    source: &str,
    quantity: Option<&str>,
) -> Result<(), ParseError> {
    let metadata = message.metadata.as_ref();
    let singular = metadata.and_then(|values| values.get("androidRuntimeParagraphSpans"));
    let plural = metadata.and_then(|values| values.get("androidPluralRuntimeParagraphSpans"));
    if quantity.is_none() && plural.is_some() || quantity.is_some() && singular.is_some() {
        return Err(invalid_paragraph());
    }
    let Some(quantity) = quantity else {
        return validate_paragraph_spans(singular, &paragraphs(source));
    };
    let Some(plural) = plural else {
        return if paragraphs(source).is_empty() {
            Ok(())
        } else {
            Err(invalid_paragraph())
        };
    };
    let variants = plural.as_object().ok_or_else(invalid_paragraph)?;
    if variants.is_empty() {
        return Err(invalid_paragraph());
    }
    for (category, ranges) in variants {
        let source = message
            .variants
            .as_ref()
            .and_then(|variants| variants.get(category))
            .ok_or_else(invalid_paragraph)?
            .replace("'<'", "<")
            .replace("''", "'");
        validate_paragraph_spans(Some(ranges), &paragraphs(&source))?;
    }
    validate_paragraph_spans(variants.get(quantity), &paragraphs(source))
}

fn validate_annotations(actual: Option<&Value>, expected: &[Value]) -> Result<(), ParseError> {
    if expected.is_empty() && actual.is_none() {
        return Ok(());
    }
    if actual
        .and_then(Value::as_array)
        .is_some_and(|values| !values.is_empty() && values.as_slice() == expected)
    {
        return Ok(());
    }
    Err(invalid())
}

fn validate_style_spans(actual: Option<&Value>, expected: &[Value]) -> Result<(), ParseError> {
    if expected.is_empty() && actual.is_none() {
        return Ok(());
    }
    if actual
        .and_then(Value::as_array)
        .is_some_and(|values| !values.is_empty() && values.as_slice() == expected)
    {
        return Ok(());
    }
    Err(invalid_style())
}

fn validate_paragraph_spans(actual: Option<&Value>, expected: &[Value]) -> Result<(), ParseError> {
    if expected.is_empty() && actual.is_none() {
        return Ok(());
    }
    if actual
        .and_then(Value::as_array)
        .is_some_and(|values| !values.is_empty() && values.as_slice() == expected)
    {
        return Ok(());
    }
    Err(invalid_paragraph())
}

fn style_effects(
    name: &str,
    encoded: &str,
    attributes: &[(String, String)],
    runtime: bool,
) -> Result<Vec<Value>, ParseError> {
    if name == "a" {
        let value = if runtime {
            subtag(encoded, "href")
        } else {
            attribute(attributes, "href")
        };
        return Ok(value
            .map(|value| vec![json!({"kind":"link","attribute":"href","value":value})])
            .unwrap_or_default());
    }
    let mut effects = Vec::new();
    for (kind, key) in [
        ("height", "height"),
        ("size", "size"),
        ("foreground", "fgcolor"),
        ("foreground", "color"),
        ("background", "bgcolor"),
        ("face", "face"),
    ] {
        let value = if runtime {
            subtag(encoded, key)
        } else {
            attribute(attributes, key)
        };
        let Some(value) = value else {
            continue;
        };
        if runtime && matches!(key, "height" | "size") && !valid_font_integer(value) {
            return Err(invalid_style());
        }
        let mut effect = json!({"kind":kind,"attribute":key,"value":value});
        if matches!(kind, "foreground" | "background") {
            effect["color"] = color(value, kind == "foreground");
        }
        effects.push(effect);
    }
    Ok(effects)
}

fn attribute<'a>(attributes: &'a [(String, String)], key: &str) -> Option<&'a str> {
    attributes
        .iter()
        .find_map(|(name, value)| (name == key).then_some(value.as_str()))
}

fn subtag<'a>(encoded: &'a str, key: &str) -> Option<&'a str> {
    let marker = format!(";{key}=");
    let start = encoded.find(&marker)? + marker.len();
    let value = &encoded[start..];
    Some(value.split(';').next().expect("subtag value"))
}

fn valid_font_integer(source: &str) -> bool {
    let (negative, digits) = match source.as_bytes().first() {
        Some(b'-') => (true, &source[1..]),
        Some(b'+') => (false, &source[1..]),
        _ => (false, source),
    };
    if digits.is_empty() {
        return false;
    }
    let limit = if negative {
        i32::MAX as u64 + 1
    } else {
        i32::MAX as u64
    };
    let mut value = 0_u64;
    for character in digits.chars() {
        let Some(digit) = digit(character, 10) else {
            return false;
        };
        let Some(next) = value
            .checked_mul(10)
            .and_then(|value| value.checked_add(u64::from(digit)))
        else {
            return false;
        };
        if next > limit {
            return false;
        }
        value = next;
    }
    true
}

fn digit(character: char, radix: u32) -> Option<u32> {
    let code = character as u32;
    let decimal = DECIMAL_ZERO
        .iter()
        .find_map(|zero| code.checked_sub(*zero).filter(|digit| *digit < 10));
    let alphabetic = match code {
        0x0041..=0x0046 => Some(code - 0x0041 + 10),
        0x0061..=0x0066 => Some(code - 0x0061 + 10),
        0xff21..=0xff26 => Some(code - 0xff21 + 10),
        0xff41..=0xff46 => Some(code - 0xff41 + 10),
        _ => None,
    };
    decimal.or(alphabetic).filter(|digit| *digit < radix)
}

fn color(value: &str, foreground: bool) -> Value {
    if let Some(reference) = value.strip_prefix('@') {
        if reference
            .split_once(':')
            .is_some_and(|(package, _)| package != "android")
        {
            return json!({"mode":"fallback","argb":"#ff000000"});
        }
        return json!({
            "mode": "system",
            "reference": value,
            "fallbackArgb": "#ff000000",
            "stateful": foreground
        });
    }
    let parsed = if let Some(hexadecimal) = value.strip_prefix('#') {
        parse_color_hex(hexadecimal, value.chars().count())
    } else {
        named_color(&value.to_lowercase())
    };
    match parsed {
        Some(color) => json!({"mode":"literal","argb":format!("#{color:08x}")}),
        None => json!({"mode":"fallback","argb":"#ff000000"}),
    }
}

fn parse_color_hex(source: &str, length: usize) -> Option<u32> {
    if !matches!(length, 7 | 9) {
        return None;
    }
    let (negative, digits) = match source.as_bytes().first() {
        Some(b'-') => (true, &source[1..]),
        Some(b'+') => (false, &source[1..]),
        _ => (false, source),
    };
    if digits.is_empty() {
        return None;
    }
    let mut value = 0_i64;
    for character in digits.chars() {
        value = value
            .checked_mul(16)?
            .checked_add(i64::from(digit(character, 16)?))?;
    }
    if negative {
        value = -value;
    }
    if length == 7 {
        value |= 0xff000000;
    }
    Some(value as u32)
}

fn named_color(value: &str) -> Option<u32> {
    Some(match value {
        "black" => 0xff000000,
        "darkgray" | "darkgrey" => 0xff444444,
        "gray" | "grey" => 0xff888888,
        "lightgray" | "lightgrey" => 0xffcccccc,
        "white" => 0xffffffff,
        "red" => 0xffff0000,
        "green" | "lime" => 0xff00ff00,
        "blue" => 0xff0000ff,
        "yellow" => 0xffffff00,
        "cyan" | "aqua" => 0xff00ffff,
        "magenta" | "fuchsia" => 0xffff00ff,
        "maroon" => 0xff800000,
        "navy" => 0xff000080,
        "olive" => 0xff808000,
        "purple" => 0xff800080,
        "silver" => 0xffc0c0c0,
        "teal" => 0xff008080,
        _ => return None,
    })
}

fn decode_annotations(encoded: &str) -> Vec<Value> {
    let mut result = Vec::new();
    let mut position = encoded.find(';');
    while let Some(start) = position {
        if start >= encoded.len() {
            break;
        }
        let Some(equals) = encoded[start..].find('=').map(|offset| start + offset) else {
            break;
        };
        let next = encoded[equals..]
            .find(';')
            .map(|offset| equals + offset)
            .unwrap_or(encoded.len());
        result.push(json!({
            "key": &encoded[start + 1..equals],
            "value": &encoded[equals + 1..next]
        }));
        position = (next < encoded.len()).then_some(next);
    }
    result
}

fn decode_attribute(source: &str) -> String {
    source
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

fn tag_end(source: &str, start: usize) -> Option<usize> {
    let mut quoted = false;
    for (offset, character) in source[start..].char_indices() {
        if character == '"' {
            quoted = !quoted;
        } else if character == '>' && !quoted {
            return Some(start + offset);
        }
    }
    None
}

fn attribute_pattern() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new(r#"([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*\"([^\"]*)\""#)
            .expect("valid Android attribute pattern")
    })
}

fn invalid() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_ANNOTATION",
        "Invalid or inconsistent Android runtime annotation metadata",
    )
}

fn invalid_style() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_STYLE",
        "Invalid or inconsistent Android runtime style metadata",
    )
}

fn invalid_paragraph() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_PARAGRAPH",
        "Invalid or inconsistent Android paragraph-span metadata",
    )
}

struct OpenParagraph {
    name: String,
    span: usize,
    kind: Option<&'static str>,
    start: usize,
}

struct ParagraphRange {
    span: usize,
    kind: &'static str,
    start: usize,
    end: usize,
}

pub(crate) fn metadata(value: Vec<Value>) -> Value {
    Value::Array(value)
}

pub(crate) fn plural_metadata(value: Map<String, Value>) -> Value {
    Value::Object(value)
}
