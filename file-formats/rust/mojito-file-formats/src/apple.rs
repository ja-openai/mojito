use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use crate::placeholders;
use crate::xml::{self, XmlElement, XmlNode};
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::sync::OnceLock;

const NEXTSTEP_UNICODE: [u16; 128] = [
    0x00A0, 0x00C0, 0x00C1, 0x00C2, 0x00C3, 0x00C4, 0x00C5, 0x00C7, 0x00C8, 0x00C9, 0x00CA, 0x00CB,
    0x00CC, 0x00CD, 0x00CE, 0x00CF, 0x00D0, 0x00D1, 0x00D2, 0x00D3, 0x00D4, 0x00D5, 0x00D6, 0x00D9,
    0x00DA, 0x00DB, 0x00DC, 0x00DD, 0x00DE, 0x00B5, 0x00D7, 0x00F7, 0x00A9, 0x00A1, 0x00A2, 0x00A3,
    0x2044, 0x00A5, 0x0192, 0x00A7, 0x00A4, 0x2019, 0x201C, 0x00AB, 0x2039, 0x203A, 0xFB01, 0xFB02,
    0x00AE, 0x2013, 0x2020, 0x2021, 0x00B7, 0x00A6, 0x00B6, 0x2022, 0x201A, 0x201E, 0x201D, 0x00BB,
    0x2026, 0x2030, 0x00AC, 0x00BF, 0x00B9, 0x02CB, 0x00B4, 0x02C6, 0x02DC, 0x00AF, 0x02D8, 0x02D9,
    0x00A8, 0x00B2, 0x02DA, 0x00B8, 0x00B3, 0x02DD, 0x02DB, 0x02C7, 0x2014, 0x00B1, 0x00BC, 0x00BD,
    0x00BE, 0x00E0, 0x00E1, 0x00E2, 0x00E3, 0x00E4, 0x00E5, 0x00E7, 0x00E8, 0x00E9, 0x00EA, 0x00EB,
    0x00EC, 0x00C6, 0x00ED, 0x00AA, 0x00EE, 0x00EF, 0x00F0, 0x00F1, 0x0141, 0x00D8, 0x0152, 0x00BA,
    0x00F2, 0x00F3, 0x00F4, 0x00F5, 0x00F6, 0x00E6, 0x00F9, 0x00FA, 0x00FB, 0x0131, 0x00FC, 0x00FD,
    0x0142, 0x00F8, 0x0153, 0x00DF, 0x00FE, 0x00FF, 0xFFFD, 0xFFFD,
];

pub(crate) fn parse_strings(source: &str) -> Result<Catalog, ParseError> {
    if source.trim_start().starts_with('<') {
        return parse_strings_plist(source);
    }
    StringsParser {
        source,
        index: 0,
        comments: Vec::new(),
        catalog: Catalog::new(FileFormat::AppleStrings),
    }
    .parse()
}

pub(crate) fn decode_source_token(source: &str) -> Result<String, ParseError> {
    let mut parser = StringsParser {
        source,
        index: 0,
        comments: Vec::new(),
        catalog: Catalog::new(FileFormat::AppleStrings),
    };
    let result = parser.token()?;
    if parser.index != source.len() {
        return Err(ParseError::new(
            "INVALID_SKELETON",
            "Invalid Apple source token",
        ));
    }
    Ok(result)
}

struct StringsParser<'a> {
    source: &'a str,
    index: usize,
    comments: Vec<String>,
    catalog: Catalog,
}

impl StringsParser<'_> {
    fn parse(mut self) -> Result<Catalog, ParseError> {
        self.trivia(true)?;
        let wrapped = self.peek() == Some('{');
        if wrapped {
            self.bump();
            self.comments.clear();
        }
        loop {
            self.trivia(true)?;
            if self.index == self.source.len() {
                if wrapped {
                    return Err(self.invalid("Unclosed OpenStep strings dictionary"));
                }
                return Ok(self.catalog);
            }
            if wrapped && self.peek() == Some('}') {
                self.bump();
                self.trivia(false)?;
                if self.index != self.source.len() {
                    return Err(
                        self.invalid("Unexpected content after OpenStep strings dictionary")
                    );
                }
                return Ok(self.catalog);
            }
            let key = self.token()?;
            self.trivia(false)?;
            let value = if self.peek() == Some(';') {
                key.clone()
            } else {
                self.require('=')?;
                self.trivia(false)?;
                let value = self.token()?;
                self.trivia(false)?;
                value
            };
            self.require(';')?;
            self.catalog.insert(
                key,
                apple_string_message(&value, Some(self.comments.join(" "))),
            )?;
            self.comments.clear();
        }
    }

    fn trivia(&mut self, collect_comments: bool) -> Result<(), ParseError> {
        loop {
            self.whitespace();
            if self.remaining().starts_with("/*") {
                let start = self.index + 2;
                let end = self.source[start..]
                    .find("*/")
                    .map(|offset| start + offset)
                    .ok_or_else(|| self.invalid("Unclosed Apple block comment"))?;
                if collect_comments {
                    self.comments.push(
                        self.source[start..end]
                            .split_whitespace()
                            .collect::<Vec<_>>()
                            .join(" "),
                    );
                }
                self.index = end + 2;
            } else if self.remaining().starts_with("//") {
                self.index += 2;
                let start = self.index;
                while let Some(character) = self.peek() {
                    if matches!(character, '\n' | '\r' | '\u{2028}' | '\u{2029}') {
                        break;
                    }
                    self.bump();
                }
                if collect_comments {
                    self.comments
                        .push(self.source[start..self.index].trim().to_owned());
                }
            } else {
                return Ok(());
            }
        }
    }

    fn token(&mut self) -> Result<String, ParseError> {
        if !matches!(self.peek(), Some('"' | '\'')) {
            let start = self.index;
            while let Some(character) = self.peek() {
                if !is_unquoted_character(character) {
                    break;
                }
                self.bump();
            }
            if start == self.index {
                return Err(self.invalid("Expected Apple strings token"));
            }
            return Ok(self.source[start..self.index].to_owned());
        }
        let delimiter = self.bump().expect("quoted Apple strings delimiter");
        let mut output = String::new();
        while let Some(character) = self.bump() {
            if character == delimiter {
                return Ok(output);
            }
            if character != '\\' {
                output.push(character);
                continue;
            }
            let escaped = self
                .bump()
                .ok_or_else(|| self.invalid("Trailing Apple strings escape"))?;
            match escaped {
                'n' => output.push('\n'),
                'r' => output.push('\r'),
                't' => output.push('\t'),
                'a' => output.push('\u{0007}'),
                'b' => output.push('\u{0008}'),
                'f' => output.push('\u{000c}'),
                'v' => output.push('\u{000b}'),
                '\n' => output.push('\n'),
                '\r' => output.push('\r'),
                'U' => self.unicode(&mut output)?,
                '0'..='7' => {
                    let mut value = escaped.to_digit(8).expect("octal digit");
                    for _ in 0..2 {
                        match self.peek() {
                            Some(digit @ '0'..='7') => {
                                self.bump();
                                value = value * 8 + digit.to_digit(8).expect("octal digit");
                            }
                            _ => break,
                        }
                    }
                    let byte = (value & 0xff) as usize;
                    let scalar = if byte < 0x80 {
                        byte as u16
                    } else {
                        NEXTSTEP_UNICODE[byte - 0x80]
                    };
                    output.push(if scalar == 0xfffd {
                        '\0'
                    } else {
                        char::from_u32(u32::from(scalar)).expect("NextStep scalar")
                    });
                }
                other => output.push(other),
            }
        }
        Err(self.invalid("Unclosed Apple strings quoted value"))
    }

    fn unicode(&mut self, output: &mut String) -> Result<(), ParseError> {
        let first = self.unicode_unit()?;
        if (0xd800..=0xdbff).contains(&first) {
            if !self.remaining().starts_with("\\U") {
                return Err(self.invalid("Missing low Apple Unicode surrogate"));
            }
            self.index += 2;
            let second = self.unicode_unit()?;
            let character = char::decode_utf16([first, second])
                .next()
                .expect("surrogate pair")
                .map_err(|_| self.invalid("Invalid Apple Unicode surrogate"))?;
            output.push(character);
        } else {
            output.push(
                char::from_u32(first as u32)
                    .ok_or_else(|| self.invalid("Invalid Apple Unicode scalar"))?,
            );
        }
        Ok(())
    }

    fn unicode_unit(&mut self) -> Result<u16, ParseError> {
        let mut value = 0;
        for _ in 0..4 {
            let Some(digit) = self.peek().and_then(ascii_hex_digit) else {
                break;
            };
            self.bump();
            value = value * 16 + digit;
        }
        Ok(value as u16)
    }

    fn whitespace(&mut self) {
        while self.peek().is_some_and(is_foundation_whitespace) {
            self.bump();
        }
    }

    fn require(&mut self, expected: char) -> Result<(), ParseError> {
        if self.bump() != Some(expected) {
            return Err(self.invalid(&format!("Expected '{expected}'")));
        }
        Ok(())
    }

    fn remaining(&self) -> &str {
        &self.source[self.index..]
    }

    fn peek(&self) -> Option<char> {
        self.remaining().chars().next()
    }

    fn bump(&mut self) -> Option<char> {
        let character = self.peek()?;
        self.index += character.len_utf8();
        Some(character)
    }

    fn invalid(&self, message: &str) -> ParseError {
        ParseError::new(
            "INVALID_APPLE_STRINGS",
            format!("{message} at offset {}", self.index),
        )
    }
}

fn parse_strings_plist(source: &str) -> Result<Catalog, ParseError> {
    let root = xml::parse_apple_plist(source)?;
    let dictionary = match root.name.as_str() {
        "dict" => &root,
        "plist" => only_plist_element(&root)?
            .filter(|element| element.name == "dict")
            .ok_or_else(|| invalid_strings("Apple strings property list requires a dictionary"))?,
        _ => {
            return Err(invalid_strings(
                "Apple strings property list requires a plist root",
            ))
        }
    };
    if dictionary.cdata {
        return Err(invalid_strings(
            "CDATA is not allowed between Apple property-list dictionary entries",
        ));
    }
    let mut catalog = Catalog::new(FileFormat::AppleStrings);
    let mut comments = Vec::new();
    let mut key = None;
    for child in &dictionary.children {
        match child {
            XmlNode::Comment(value) => {
                if key.is_none() {
                    comments.push(value.split_whitespace().collect::<Vec<_>>().join(" "));
                }
            }
            XmlNode::Text(value) => {
                if !is_xml_whitespace(value) {
                    return Err(invalid_strings(
                        "Unexpected text inside Apple plist dictionary",
                    ));
                }
            }
            XmlNode::Element(element) if key.is_none() => {
                if element.name != "key" || has_nested_plist_content(element) {
                    return Err(invalid_strings("Expected a plain Apple property-list key"));
                }
                key = Some(element.text());
            }
            XmlNode::Element(element) => {
                if element.name != "string" || has_nested_plist_content(element) {
                    return Err(invalid_strings(
                        "Apple property-list values must be strings",
                    ));
                }
                let value = element.text();
                catalog.insert(
                    key.take().expect("pending Apple plist key"),
                    apple_string_message(&value, Some(comments.join(" "))),
                )?;
                comments.clear();
            }
        }
    }
    if key.is_some() {
        return Err(invalid_strings("Unpaired Apple property-list key"));
    }
    Ok(catalog)
}

fn only_plist_element(parent: &XmlElement) -> Result<Option<&XmlElement>, ParseError> {
    if parent.cdata {
        return Err(invalid_strings(
            "CDATA is not allowed between Apple property-list root values",
        ));
    }
    let mut result = None;
    for child in &parent.children {
        match child {
            XmlNode::Element(element) => {
                if result.replace(element).is_some() {
                    return Err(invalid_strings(
                        "Apple property list has multiple root values",
                    ));
                }
            }
            XmlNode::Text(value) if !is_xml_whitespace(value) => {
                return Err(invalid_strings(
                    "Unexpected text inside Apple property list",
                ));
            }
            XmlNode::Text(_) | XmlNode::Comment(_) => {}
        }
    }
    Ok(result)
}

fn is_xml_whitespace(value: &str) -> bool {
    value
        .chars()
        .all(|character| matches!(character, ' ' | '\t' | '\n' | '\r'))
}

fn has_nested_plist_content(element: &XmlElement) -> bool {
    element.processing_instruction
        || element
            .children
            .iter()
            .any(|child| matches!(child, XmlNode::Element(_) | XmlNode::Comment(_)))
}

fn invalid_strings(message: &str) -> ParseError {
    ParseError::new("INVALID_APPLE_STRINGS", message)
}

pub(crate) fn apple_string_message(value: &str, description: Option<String>) -> Message {
    let mut placeholders = Vec::new();
    let mut message = placeholders::normalize_foundation(value, &mut placeholders);
    let mut metadata = Map::new();
    let conversions = placeholders::foundation_printf_line_separators(value);
    if !conversions.is_empty() {
        let (visible, disabled) = without_disabled_foundation_conversions(&message, &conversions);
        message = visible;
        metadata.insert("appleDisabledPrintfConversions".into(), json!(disabled));
    }
    if message.contains('<') {
        message = message.replace('\'', "''").replace('<', "'<'");
        metadata.insert("appleMarkupEscaping".into(), json!("icu-quoted-angle"));
    }
    Message::new(message, description, None, placeholders, metadata)
}

pub(crate) fn without_disabled_printf_conversions(
    message: &str,
    conversions: &[(usize, String)],
) -> (String, Vec<Value>) {
    let mut visible = String::with_capacity(message.len());
    let mut disabled = Vec::with_capacity(conversions.len());
    let mut conversion = 0;
    let mut visible_position = 0;
    for (position, character) in message.chars().enumerate() {
        if conversions
            .get(conversion)
            .is_some_and(|(index, _)| *index == position)
        {
            disabled.push(json!({
                "position": visible_position,
                "source": conversions[conversion].1
            }));
            conversion += 1;
        } else {
            visible.push(character);
            visible_position += 1;
        }
    }
    (visible, disabled)
}

pub(crate) fn without_disabled_foundation_conversions(
    message: &str,
    conversions: &[(usize, String, Option<usize>)],
) -> (String, Vec<Value>) {
    let plain = conversions
        .iter()
        .map(|(position, source, _)| (*position, source.clone()))
        .collect::<Vec<_>>();
    let (visible, mut disabled) = without_disabled_printf_conversions(message, &plain);
    for (occurrence, (_, _, argument_position)) in disabled.iter_mut().zip(conversions) {
        if let Some(position) = argument_position {
            occurrence
                .as_object_mut()
                .expect("disabled Foundation conversion")
                .insert("argumentPosition".into(), json!(position));
        }
    }
    (visible, disabled)
}

fn is_foundation_whitespace(character: char) -> bool {
    matches!(character, '\t'..='\r' | ' ' | '\u{2028}' | '\u{2029}')
}

fn is_unquoted_character(character: char) -> bool {
    character.is_ascii_alphanumeric() || matches!(character, '_' | '$' | '/' | ':' | '.' | '-')
}

fn ascii_hex_digit(character: char) -> Option<u32> {
    match character {
        '0'..='9' => Some(character as u32 - '0' as u32),
        'a'..='f' => Some(character as u32 - 'a' as u32 + 10),
        'A'..='F' => Some(character as u32 - 'A' as u32 + 10),
        _ => None,
    }
}

pub(crate) fn parse_stringsdict(source: &str) -> Result<Catalog, ParseError> {
    let root = xml::parse_apple_plist(source)?;
    if root.name != "plist" {
        return Err(invalid_dict("Expected Apple plist root"));
    }
    validate_plist_container(&root)?;
    let dictionary = root
        .elements()
        .next()
        .filter(|element| element.name == "dict")
        .ok_or_else(|| invalid_dict("Expected Apple plist dictionary"))?;
    parse_stringsdict_values(dictionary_values(dictionary)?)
}

pub(crate) fn parse_stringsdict_values(
    entries: Vec<(String, PlistValue)>,
) -> Result<Catalog, ParseError> {
    let mut catalog = Catalog::new(FileFormat::AppleStringsdict);
    for (id, value) in entries {
        let PlistValue::Dictionary(message) = value else {
            return Err(invalid_dict("Apple stringsdict entry must be a dictionary"));
        };
        parse_dictionary_message(&mut catalog, id, message)?;
    }
    Ok(catalog)
}

fn parse_dictionary_message(
    catalog: &mut Catalog,
    id: String,
    message: Vec<(String, PlistValue)>,
) -> Result<(), ParseError> {
    if let Some(devices) = device_plural_rules(&message)? {
        let selected = default_device_name(devices.keys().map(String::as_str))
            .ok_or_else(|| invalid_dict("Apple device rule must not be empty"))?;
        let mut nested = Catalog::new(FileFormat::AppleStringsdict);
        let branch = match devices.get(selected).expect("selected Foundation device") {
            PlistValue::Dictionary(values) => values.clone(),
            PlistValue::String(value) => vec![(
                "NSStringDeviceSpecificRuleType".into(),
                PlistValue::Dictionary(vec![(
                    selected.to_owned(),
                    PlistValue::String(value.clone()),
                )]),
            )],
            _ => unreachable!("validated Foundation device branch"),
        };
        parse_dictionary_message(&mut nested, id.clone(), branch)?;
        let mut parsed = nested
            .messages
            .remove(&id)
            .ok_or_else(|| invalid_dict("Missing selected Apple device plural"))?;
        let mixed = devices
            .values()
            .map(|branch| match branch {
                PlistValue::Dictionary(values)
                    if values
                        .iter()
                        .any(|(key, _)| key == "NSStringVariableWidthRuleType") =>
                {
                    "width"
                }
                PlistValue::Dictionary(_) => "plural",
                PlistValue::String(_) => "scalar",
                _ => unreachable!("validated Foundation device branch"),
            })
            .collect::<std::collections::BTreeSet<_>>()
            .len()
            > 1;
        let variation = if mixed {
            "deviceMixedVariants"
        } else if parsed.variants.is_some() {
            "devicePluralVariants"
        } else if parsed
            .metadata
            .as_ref()
            .is_some_and(|metadata| metadata.contains_key("widthVariants"))
        {
            "deviceWidthVariants"
        } else {
            return Err(invalid_dict(
                "Apple device-specific dictionary must contain plural or width rules",
            ));
        };
        let metadata = parsed.metadata.get_or_insert_with(Map::new);
        metadata.insert("defaultDevice".into(), json!(selected));
        metadata.insert(
            variation.into(),
            Value::Object(
                devices
                    .iter()
                    .map(|(name, branch)| (name.clone(), branch.to_json()))
                    .collect(),
            ),
        );
        return catalog.insert(id, parsed);
    }
    let widths = variation_values(&message, "NSStringVariableWidthRuleType", true)?;
    let devices = variation_values(&message, "NSStringDeviceSpecificRuleType", false)?;
    if message
        .iter()
        .find(|(key, _)| key == "NSStringLocalizedFormatKey")
        .is_some_and(|(_, value)| value.as_string().is_none())
    {
        return Err(invalid_dict("Apple localized format must be a string"));
    }
    let mut metadata = Map::new();
    if !widths.is_empty() {
        let widest_key = widest_width_key(&widths);
        metadata.insert("widthVariants".into(), json!(widths));
        metadata.insert("defaultWidth".into(), json!(widest_width(&widths)));
        if widest_key != widest_width(&widths).to_string() {
            metadata.insert("defaultWidthKey".into(), json!(widest_key));
        }
    }
    if !devices.is_empty() {
        metadata.insert("deviceVariants".into(), json!(devices));
        metadata.insert("defaultDevice".into(), json!(default_device(&devices)));
    }
    let mut pattern = if let Some(value) = message
        .iter()
        .find(|(key, _)| key == "NSStringLocalizedFormatKey")
        .and_then(|(_, value)| value.as_string())
    {
        metadata.insert("appleLocalizedFormat".into(), json!(value));
        value.to_owned()
    } else if !devices.is_empty() {
        devices
            .get(default_device(&devices))
            .expect("known default device")
            .clone()
    } else if !widths.is_empty() {
        widths
            .get(widest_width_key(&widths))
            .expect("known widest width")
            .clone()
    } else {
        return Err(invalid_dict("Strings dictionary has no variation rules"));
    };
    let mut variables = Vec::new();
    let mut placeholders = Vec::new();
    let mut single_variants = None;
    let mut value_type = None;
    let mut plural_rules = Map::new();
    let mut disabled_conversions = Map::new();
    let positions = plural_positions(&pattern, &message)?;
    let mut expansions = BTreeMap::new();
    for (name, value) in &message {
        let PlistValue::Dictionary(definition) = value else {
            continue;
        };
        let field = |key: &str| {
            definition
                .iter()
                .find(|(entry, _)| entry == key)
                .and_then(|(_, value)| value.as_string())
        };
        if field("NSStringFormatSpecTypeKey") != Some("NSStringPluralRuleType") {
            continue;
        }
        let Some(position) = positions.get(name).copied() else {
            return Err(invalid_dict(&format!(
                "Apple stringsdict plural is not referenced: {name}"
            )));
        };
        variables.push(name.clone());
        let declared_type = field("NSStringFormatValueTypeKey");
        if definition
            .iter()
            .find(|(key, _)| key == "NSStringFormatValueTypeKey")
            .is_some_and(|(_, value)| value.as_string().is_none())
        {
            return Err(invalid_dict(
                "Apple stringsdict plural value type must be a string",
            ));
        }
        if let Some(declared_type) = declared_type {
            let mut typed = Vec::new();
            placeholders::normalize(&format!("%{declared_type}"), &mut typed, Some(name));
            if typed.len() != 1 || !is_numeric(&typed[0]) {
                return Err(invalid_dict(&format!(
                    "Apple stringsdict plural requires a numeric value type: {name}"
                )));
            }
        }
        let mut rule_extras = Map::new();
        for (category, value) in definition {
            if !matches!(
                category.as_str(),
                "NSStringFormatSpecTypeKey"
                    | "NSStringFormatValueTypeKey"
                    | "zero"
                    | "one"
                    | "two"
                    | "few"
                    | "many"
                    | "other"
            ) {
                if matches!(value, PlistValue::String(_)) && !category.starts_with("NSString") {
                    return Err(ParseError::new(
                        "INVALID_PLURAL_CATEGORY",
                        format!("Unsupported Apple plural category: {category}"),
                    ));
                }
                rule_extras.insert(category.clone(), value.to_json());
            } else if matches!(
                category.as_str(),
                "zero" | "one" | "two" | "few" | "many" | "other"
            ) && value.as_string().is_none()
            {
                return Err(invalid_dict(
                    "Apple stringsdict plural category must be a string",
                ));
            }
        }
        let previous_placeholders = placeholders.len();
        let mut variants = BTreeMap::new();
        let mut ordered = Vec::new();
        let mut source_variants = Map::new();
        for category in ["zero", "one", "two", "few", "many", "other"] {
            if let Some(text) = field(category) {
                let mut normalized = placeholders::normalize_foundation_plural(
                    text,
                    &mut placeholders,
                    name,
                    position,
                );
                let conversions =
                    placeholders::foundation_plural_printf_line_separators(text, name, position);
                if !conversions.is_empty() {
                    let (visible, disabled) =
                        without_disabled_foundation_conversions(&normalized, &conversions);
                    normalized = visible;
                    disabled_conversions
                        .entry(name.to_owned())
                        .or_insert_with(|| Value::Object(Map::new()))
                        .as_object_mut()
                        .expect("disabled conversion selector is a dictionary")
                        .insert(category.to_owned(), Value::Array(disabled));
                }
                variants.insert(category.to_owned(), normalized.clone());
                ordered.push((category.to_owned(), normalized));
                source_variants.insert(category.to_owned(), json!(text));
            }
        }
        if !variants.contains_key("other") {
            return Err(ParseError::new(
                "MISSING_OTHER_VARIANT",
                "Missing Apple other",
            ));
        }
        if !placeholders[previous_placeholders..]
            .iter()
            .any(|placeholder| placeholder.name == *name && is_numeric(placeholder))
        {
            let Some(declared_type) = declared_type else {
                return Err(invalid_dict(&format!(
                    "Apple stringsdict plural requires a numeric format argument: {name}"
                )));
            };
            let mut typed = Vec::new();
            placeholders::normalize_plural(
                &format!("%{declared_type}"),
                &mut typed,
                name,
                position,
            );
            let selector = typed.pop().expect("validated numeric plural value type");
            if !placeholders.contains(&selector) {
                placeholders.push(selector);
            }
        }
        let plural = placeholders::plural(
            name,
            ordered
                .iter()
                .map(|(category, value)| (category.as_str(), value.as_str())),
        );
        expansions.insert(name.to_owned(), plural);
        let mut rule = Map::new();
        if let Some(value_type) = declared_type {
            rule.insert("valueType".into(), json!(value_type));
        }
        rule.insert("variants".into(), Value::Object(source_variants));
        if !rule_extras.is_empty() {
            rule.insert("applePlistExtras".into(), Value::Object(rule_extras));
        }
        plural_rules.insert(name.to_owned(), Value::Object(rule));
        if variables.len() == 1 {
            single_variants = Some(variants);
            value_type = declared_type.map(str::to_owned);
        }
    }
    if variables.is_empty() && widths.is_empty() && devices.is_empty() {
        return Err(invalid_dict("Strings dictionary has no plural variables"));
    }
    if variables.len() == 1 {
        metadata.insert("applePluralRules".into(), Value::Object(plural_rules));
        metadata.insert("pluralVariable".into(), json!(variables[0]));
        if let Some(value_type) = value_type {
            metadata.insert("valueType".into(), json!(value_type));
        }
    } else if variables.len() > 1 {
        single_variants = None;
        metadata.insert("applePluralRules".into(), Value::Object(plural_rules));
        metadata.insert("pluralVariables".into(), json!(variables));
    }
    if !disabled_conversions.is_empty() {
        metadata.insert(
            "applePluralDisabledPrintfConversions".into(),
            Value::Object(disabled_conversions),
        );
    }
    let message_extras = message
        .iter()
        .filter(|(key, _)| {
            !matches!(
                key.as_str(),
                "NSStringLocalizedFormatKey"
                    | "NSStringVariableWidthRuleType"
                    | "NSStringDeviceSpecificRuleType"
            ) && !variables.contains(key)
        })
        .map(|(key, value)| (key.clone(), value.to_json()))
        .collect::<Map<String, Value>>();
    if !message_extras.is_empty() {
        metadata.insert("applePlistExtras".into(), Value::Object(message_extras));
    }
    if variables.is_empty() {
        let original = pattern.clone();
        pattern = placeholders::normalize_foundation(&pattern, &mut placeholders);
        let conversions = placeholders::foundation_printf_line_separators(&original);
        if !conversions.is_empty() {
            let (visible, disabled) =
                without_disabled_foundation_conversions(&pattern, &conversions);
            pattern = visible;
            metadata.insert(
                "appleDisabledPrintfConversions".into(),
                Value::Array(disabled),
            );
        }
    } else {
        let masked = plural_marker_pattern()
            .replace_all(&pattern, |captures: &regex::Captures<'_>| {
                format!("\u{1}{}\u{1}", &captures[2])
            })
            .into_owned();
        let mut outer = Vec::new();
        let normalized = placeholders::normalize(&masked, &mut outer, None);
        pattern = if outer.is_empty() { masked } else { normalized };
        for placeholder in outer {
            if !placeholders.contains(&placeholder) {
                placeholders.push(placeholder);
            }
        }
        for (name, expansion) in expansions {
            pattern = pattern.replace(&format!("\u{1}{name}\u{1}"), &expansion);
        }
    }
    catalog.insert(
        id,
        Message::new(pattern, None, single_variants, placeholders, metadata),
    )
}

fn device_plural_rules(
    message: &[(String, PlistValue)],
) -> Result<Option<BTreeMap<String, PlistValue>>, ParseError> {
    let Some((_, PlistValue::Dictionary(devices))) = message
        .iter()
        .find(|(name, _)| name == "NSStringDeviceSpecificRuleType")
    else {
        return Ok(None);
    };
    let mut branches = BTreeMap::new();
    let mut dictionaries = false;
    for (name, value) in devices {
        match value {
            PlistValue::Dictionary(_) => {
                branches.insert(name.clone(), value.clone());
                dictionaries = true;
            }
            PlistValue::String(_) => {
                branches.insert(name.clone(), value.clone());
            }
            _ => {
                return Err(invalid_dict(
                    "Apple device rule must contain strings or plural dictionaries",
                ));
            }
        }
    }
    Ok(dictionaries.then_some(branches))
}

fn plural_positions(
    pattern: &str,
    message: &[(String, PlistValue)],
) -> Result<BTreeMap<String, Option<usize>>, ParseError> {
    let mut positions = BTreeMap::new();
    for captures in plural_marker_pattern().captures_iter(pattern) {
        let name = captures.get(2).expect("plural marker name").as_str();
        if !plural_name_pattern().is_match(name) {
            return Err(ParseError::new(
                "INVALID_PLACEHOLDER",
                "Apple stringsdict plural name is not safe for Foundation",
            ));
        }
        let definition = message
            .iter()
            .find(|(key, _)| key == name)
            .and_then(|(_, value)| match value {
                PlistValue::Dictionary(values) => Some(values),
                _ => None,
            });
        if definition
            .and_then(|entries| {
                entries
                    .iter()
                    .find(|(key, _)| key == "NSStringFormatSpecTypeKey")
            })
            .and_then(|(_, value)| value.as_string())
            != Some("NSStringPluralRuleType")
        {
            return Err(invalid_dict(&format!(
                "Apple stringsdict plural has no matching definition: {name}"
            )));
        }
        let position = captures
            .get(1)
            .map(|value| {
                value
                    .as_str()
                    .parse::<usize>()
                    .ok()
                    .filter(|position| *position > 0 && *position <= i32::MAX as usize)
                    .ok_or_else(|| {
                        ParseError::new(
                            "INVALID_PLACEHOLDER",
                            "Apple stringsdict plural position must be positive",
                        )
                    })
            })
            .transpose()?;
        if positions.contains_key(name) && positions.get(name).copied() != Some(position) {
            return Err(ParseError::new(
                "INVALID_PLACEHOLDER",
                format!("Apple stringsdict plural has conflicting positions: {name}"),
            ));
        }
        positions.insert(name.to_owned(), position);
    }
    Ok(positions)
}

fn plural_marker_pattern() -> &'static Regex {
    static MARKER: OnceLock<Regex> = OnceLock::new();
    MARKER.get_or_init(|| Regex::new(r"%(?:(\d+)\$)?#@([^@]*)@").expect("valid plural marker"))
}

fn plural_name_pattern() -> &'static Regex {
    static NAME: OnceLock<Regex> = OnceLock::new();
    NAME.get_or_init(|| Regex::new(r"^[A-Za-z0-9_]+$").expect("valid plural argument name"))
}

fn is_numeric(placeholder: &Placeholder) -> bool {
    matches!(placeholder.kind, "integer" | "number")
}

fn variation_values(
    message: &[(String, PlistValue)],
    rule: &str,
    validate_widths: bool,
) -> Result<BTreeMap<String, String>, ParseError> {
    let Some((_, value)) = message.iter().find(|(name, _)| name == rule) else {
        return Ok(BTreeMap::new());
    };
    let PlistValue::Dictionary(values) = value else {
        return Err(invalid_dict("Apple variation rule must be a dictionary"));
    };
    if values.is_empty() {
        return Err(invalid_dict("Apple variation rule must not be empty"));
    }
    let mut result = BTreeMap::new();
    for (name, value) in values {
        let PlistValue::String(text) = value else {
            return Err(invalid_dict("Apple variation must contain string values"));
        };
        if validate_widths
            && name
                .parse::<usize>()
                .ok()
                .filter(|value| *value > 0)
                .is_none()
        {
            return Err(invalid_dict(
                "Apple presentation widths must be positive numbers",
            ));
        }
        result.insert(name.clone(), text.clone());
    }
    Ok(result)
}

fn widest_width(widths: &BTreeMap<String, String>) -> usize {
    widths
        .keys()
        .map(|value| value.parse::<usize>().expect("validated Apple width"))
        .max()
        .expect("nonempty Apple widths")
}

fn widest_width_key(widths: &BTreeMap<String, String>) -> &str {
    widths
        .keys()
        .max_by_key(|value| {
            (
                value.parse::<usize>().expect("validated Apple width"),
                value.as_str(),
            )
        })
        .map(String::as_str)
        .expect("nonempty Apple widths")
}

fn default_device(devices: &BTreeMap<String, String>) -> &str {
    default_device_name(devices.keys().map(String::as_str)).expect("nonempty Apple devices")
}

fn default_device_name<'a>(devices: impl Iterator<Item = &'a str>) -> Option<&'a str> {
    let values = devices.collect::<Vec<_>>();
    [
        "iphone",
        "ipad",
        "mac",
        "applewatch",
        "applevision",
        "appletv",
        "ipod",
    ]
    .into_iter()
    .find(|device| values.contains(device))
    .or_else(|| values.into_iter().min())
}

#[derive(Clone, Debug)]
pub(crate) enum PlistValue {
    String(String),
    Dictionary(Vec<(String, PlistValue)>),
    Array(Vec<PlistValue>),
    Integer(i128),
    Boolean(bool),
    Data(Vec<u8>),
    Date(String),
    Real(f64),
}

impl PlistValue {
    fn as_string(&self) -> Option<&str> {
        match self {
            Self::String(value) => Some(value),
            _ => None,
        }
    }

    fn to_json(&self) -> Value {
        match self {
            Self::String(value) => Value::String(value.clone()),
            Self::Dictionary(entries) => {
                let values = entries
                    .iter()
                    .map(|(key, value)| (key.clone(), value.to_json()))
                    .collect::<Map<String, Value>>();
                if values.contains_key("$applePlistType") {
                    json!({
                        "$applePlistType": "dictionary",
                        "entries": values
                            .into_iter()
                            .map(|(key, value)| json!({ "key": key, "value": value }))
                            .collect::<Vec<_>>()
                    })
                } else {
                    Value::Object(values)
                }
            }
            Self::Array(values) => Value::Array(values.iter().map(Self::to_json).collect()),
            Self::Integer(value) => {
                if *value < 0 {
                    json!(i64::try_from(*value).expect("validated signed plist integer"))
                } else {
                    json!(u64::try_from(*value).expect("validated unsigned plist integer"))
                }
            }
            Self::Boolean(value) => Value::Bool(*value),
            Self::Data(value) => {
                json!({ "$applePlistType": "data", "base64": base64_encode(value) })
            }
            Self::Date(value) => json!({ "$applePlistType": "date", "value": value }),
            Self::Real(value) => {
                json!({ "$applePlistType": "real", "bits": format!("{:016x}", value.to_bits()) })
            }
        }
    }
}

fn dictionary_values(dictionary: &XmlElement) -> Result<Vec<(String, PlistValue)>, ParseError> {
    validate_plist_container(dictionary)?;
    let children: Vec<_> = dictionary.elements().collect();
    if children.len() % 2 != 0 {
        return Err(invalid_dict("Unpaired Apple plist key"));
    }
    let mut result = Vec::new();
    for pair in children.chunks_exact(2) {
        if pair[0].name != "key" {
            return Err(invalid_dict("Expected Apple plist key"));
        }
        validate_plist_scalar(pair[0], true, false)?;
        let key = pair[0].text();
        if result.iter().any(|(existing, _)| existing == &key) {
            return Err(ParseError::new(
                "DUPLICATE_MESSAGE_ID",
                "Duplicate Apple plist key",
            ));
        }
        let value = plist_value(pair[1])?;
        result.push((key, value));
    }
    Ok(result)
}

fn plist_value(element: &XmlElement) -> Result<PlistValue, ParseError> {
    if element.name == "data" && element.self_closing {
        return Err(invalid_dict(
            "Apple plist data requires an explicit closing tag",
        ));
    }
    if !matches!(element.name.as_str(), "dict" | "array") {
        validate_plist_scalar(
            element,
            matches!(element.name.as_str(), "string" | "real"),
            matches!(element.name.as_str(), "true" | "false"),
        )?;
    }
    Ok(match element.name.as_str() {
        "dict" => PlistValue::Dictionary(dictionary_values(element)?),
        "array" => {
            validate_plist_container(element)?;
            PlistValue::Array(
                element
                    .elements()
                    .map(plist_value)
                    .collect::<Result<Vec<_>, _>>()?,
            )
        }
        "string" => PlistValue::String(element.text()),
        "integer" => PlistValue::Integer(plist_integer(&element.text())?),
        "real" => PlistValue::Real(plist_real(&element.text())?),
        "data" => PlistValue::Data(base64_decode(&element.text())?),
        "date" => PlistValue::Date(plist_date(&element.text())?),
        "true" => PlistValue::Boolean(true),
        "false" => PlistValue::Boolean(false),
        _ => return Err(invalid_dict("Unsupported Apple plist value")),
    })
}

fn validate_plist_container(element: &XmlElement) -> Result<(), ParseError> {
    if element.cdata {
        return Err(invalid_dict(
            "CDATA is not allowed between Apple plist container values",
        ));
    }
    if element
        .children
        .iter()
        .any(|child| matches!(child, XmlNode::Text(value) if !is_xml_whitespace(value)))
    {
        return Err(invalid_dict("Unexpected text inside Apple plist container"));
    }
    Ok(())
}

fn validate_plist_scalar(
    element: &XmlElement,
    allow_cdata: bool,
    empty: bool,
) -> Result<(), ParseError> {
    if element.processing_instruction
        || element.cdata && !allow_cdata
        || empty && !element.children.is_empty()
        || element
            .children
            .iter()
            .any(|child| matches!(child, XmlNode::Element(_) | XmlNode::Comment(_)))
    {
        return Err(invalid_dict("Unexpected content inside Apple plist scalar"));
    }
    Ok(())
}

pub(crate) fn plist_real(source: &str) -> Result<f64, ParseError> {
    let lower = source.to_ascii_lowercase();
    match lower.as_str() {
        "nan" => Ok(f64::NAN),
        "inf" | "+inf" | "infinity" | "+infinity" => Ok(f64::INFINITY),
        "-inf" | "-infinity" => Ok(f64::NEG_INFINITY),
        _ => {
            static REAL: OnceLock<Regex> = OnceLock::new();
            let syntax = REAL.get_or_init(|| {
                Regex::new(r"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
                    .expect("valid property-list real expression")
            });
            if !syntax.is_match(source) {
                return Err(invalid_dict("Invalid Apple property-list real value"));
            }
            source
                .parse()
                .map_err(|_| invalid_dict("Invalid Apple property-list real value"))
        }
    }
}

pub(crate) fn plist_date(source: &str) -> Result<String, ParseError> {
    let bytes = source.as_bytes();
    if bytes.len() != 20
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || bytes[19] != b'Z'
        || bytes.iter().enumerate().any(|(index, value)| {
            !matches!(index, 4 | 7 | 10 | 13 | 16 | 19) && !value.is_ascii_digit()
        })
    {
        return Err(invalid_dict("Invalid Apple property-list UTC date"));
    }
    let year: i32 = source[0..4].parse().expect("validated year digits");
    let month: u32 = source[5..7].parse().expect("validated month digits");
    let day: u32 = source[8..10].parse().expect("validated day digits");
    let hour: u32 = source[11..13].parse().expect("validated hour digits");
    let minute: u32 = source[14..16].parse().expect("validated minute digits");
    let second: u32 = source[17..19].parse().expect("validated second digits");
    if year == 0 {
        return Err(invalid_dict("Invalid Apple property-list UTC date"));
    }
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    const DAYS_BEFORE_MONTH: [i64; 14] = [
        0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334, 365,
    ];
    let month_offset = DAYS_BEFORE_MONTH
        .get(month as usize)
        .copied()
        .unwrap_or_default()
        + i64::from(month > 2 && month <= 13 && leap);
    let previous_year = i64::from(year) - 1;
    let days_from_year = previous_year * 365 + previous_year / 4 - previous_year / 100
        + previous_year / 400
        - 719_162;
    let unix = (days_from_year + month_offset + i64::from(day) - 1) * 86_400
        + i64::from(hour) * 3600
        + i64::from(minute) * 60
        + i64::from(second);
    date_from_unix(unix)
}

pub(crate) fn binary_date(seconds: f64) -> Result<String, ParseError> {
    if !seconds.is_finite() || seconds.fract() != 0.0 {
        return Err(ParseError::new(
            "UNSUPPORTED_APPLE_PLIST_DATE_PRECISION",
            "Binary Apple date has fractional seconds",
        ));
    }
    let unix = (seconds as i64)
        .checked_add(978_307_200)
        .ok_or_else(|| invalid_dict("Invalid binary Apple property-list date"))?;
    date_from_unix(unix)
}

fn date_from_unix(unix: i64) -> Result<String, ParseError> {
    let days = unix.div_euclid(86_400);
    let time = unix.rem_euclid(86_400);
    let adjusted = days + 719_468;
    let era = if adjusted >= 0 {
        adjusted
    } else {
        adjusted - 146_096
    } / 146_097;
    let day_of_era = adjusted - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let mut year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_position = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_position + 2) / 5 + 1;
    let month = month_position + if month_position < 10 { 3 } else { -9 };
    year += i64::from(month <= 2);
    let value = format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        time / 3600,
        time / 60 % 60,
        time % 60
    );
    if !(1..=9999).contains(&year) {
        return Err(invalid_dict("Invalid Apple property-list UTC date"));
    }
    Ok(value)
}

pub(crate) fn base64_encode(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let first = chunk[0];
        let second = chunk.get(1).copied().unwrap_or_default();
        let third = chunk.get(2).copied().unwrap_or_default();
        output.push(ALPHABET[usize::from(first >> 2)] as char);
        output.push(ALPHABET[usize::from((first & 3) << 4 | second >> 4)] as char);
        output.push(if chunk.len() > 1 {
            ALPHABET[usize::from((second & 15) << 2 | third >> 6)] as char
        } else {
            '='
        });
        output.push(if chunk.len() > 2 {
            ALPHABET[usize::from(third & 63)] as char
        } else {
            '='
        });
    }
    output
}

pub(crate) fn base64_decode(source: &str) -> Result<Vec<u8>, ParseError> {
    let mut output = Vec::with_capacity(source.len() / 4 * 3);
    let mut accumulator = 0_u32;
    let mut count = 0_usize;
    let mut padding = 0_usize;
    for byte in source.bytes() {
        if !byte.is_ascii() {
            return Err(invalid_dict("Invalid Apple property-list base64 data"));
        }
        if byte == b'=' {
            padding += 1;
        } else if !byte.is_ascii_whitespace() {
            padding = 0;
        }
        let decoded = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => 0,
            _ => continue,
        };
        accumulator = accumulator.wrapping_shl(6) + u32::from(decoded);
        count += 1;
        if count % 4 == 0 {
            output.push((accumulator >> 16) as u8);
            if padding < 2 {
                output.push((accumulator >> 8) as u8);
            }
            if padding < 1 {
                output.push(accumulator as u8);
            }
        }
    }
    if output.len() > 1_000_000 {
        return Err(invalid_dict(
            "Apple property-list data exceeds its maximum size",
        ));
    }
    Ok(output)
}

pub(crate) fn plist_integer(source: &str) -> Result<i128, ParseError> {
    let (negative, digits) = match source.strip_prefix('-') {
        Some(value) => (true, value),
        None => (false, source.strip_prefix('+').unwrap_or(source)),
    };
    let (radix, digits) = match digits
        .strip_prefix("0x")
        .or_else(|| digits.strip_prefix("0X"))
    {
        Some(value) => (16, value),
        None => (10, digits),
    };
    if digits.is_empty()
        || !digits.chars().all(|character| {
            character.is_ascii_digit() || radix == 16 && character.is_ascii_hexdigit()
        })
    {
        return Err(invalid_dict("Invalid Apple property-list integer"));
    }
    let unsigned = u128::from_str_radix(digits, radix)
        .map_err(|_| invalid_dict("Apple property-list integer is outside its 64-bit range"))?;
    if negative {
        if unsigned > 1_u128 << 63 {
            return Err(invalid_dict(
                "Apple property-list integer is outside its 64-bit range",
            ));
        }
        Ok(-(unsigned as i128))
    } else if unsigned > u128::from(u64::MAX) {
        Err(invalid_dict(
            "Apple property-list integer is outside its 64-bit range",
        ))
    } else {
        Ok(unsigned as i128)
    }
}

fn invalid_dict(message: &str) -> ParseError {
    ParseError::new("INVALID_APPLE_STRINGSDICT", message)
}

pub(crate) fn parse_xcstrings(source: &str) -> Result<Catalog, ParseError> {
    let root: Value = serde_json::from_str(source)
        .map_err(|error| ParseError::new("INVALID_XCSTRINGS", error.to_string()))?;
    let source_identifier = root
        .get("sourceLanguage")
        .and_then(Value::as_str)
        .filter(|language| !language.is_empty())
        .ok_or_else(|| invalid_xcstrings("Missing sourceLanguage"))?;
    let language = source_identifier.replace('_', "-");
    let version = root
        .get("version")
        .filter(|version| version.is_string() || version.is_number())
        .ok_or_else(|| invalid_xcstrings("Missing version"))?;
    let catalog_metadata = root
        .as_object()
        .into_iter()
        .flat_map(Map::iter)
        .filter(|(key, _)| !matches!(key.as_str(), "sourceLanguage" | "strings" | "version"))
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect::<Map<String, Value>>();
    let entries = root
        .get("strings")
        .and_then(Value::as_object)
        .ok_or_else(|| invalid_xcstrings("Missing strings object"))?;
    validate_xcstring_descriptors(entries)?;
    validate_xcstring_units(&root)?;
    let mut catalog = Catalog::new(FileFormat::AppleXcstrings);
    catalog.locale = Some(language.clone());
    for (id, descriptor) in entries {
        let descriptor = descriptor
            .as_object()
            .ok_or_else(|| invalid_xcstrings("Catalog descriptor must be an object"))?;
        if descriptor.get("shouldTranslate").and_then(Value::as_bool) == Some(false) {
            continue;
        }
        let localizations = descriptor.get("localizations").and_then(Value::as_object);
        let source_locale = localizations
            .map(|values| xcstrings_source_locale(values, source_identifier))
            .unwrap_or(source_identifier);
        let source = localizations
            .and_then(|values| values.get(source_locale))
            .filter(|localization| !localization.is_null());
        let devices = source
            .and_then(|value| value.get("variations"))
            .and_then(|value| value.get("device"))
            .and_then(Value::as_object);
        let selected_device = devices.map(xcstrings_default_device).transpose()?;
        let effective_source = if let (Some(source), Some(device)) = (source, selected_device) {
            if source
                .get("variations")
                .and_then(|value| value.get("plural"))
                .and_then(Value::as_object)
                .is_some()
            {
                Some(source)
            } else {
                devices.and_then(|entries| entries.get(device))
            }
        } else {
            source
        };
        let mut placeholders = Vec::new();
        let mut variants = None;
        let mut disabled = Vec::new();
        let mut disabled_variants = Map::new();
        let mut disabled_substitutions = Map::new();
        let message = if let Some(plurals) = effective_source
            .and_then(|value| value.get("variations"))
            .and_then(|value| value.get("plural"))
            .and_then(Value::as_object)
        {
            let mut values = BTreeMap::new();
            let mut ordered = Vec::new();
            for (category, descriptor) in plurals {
                let value = descriptor
                    .get("stringUnit")
                    .and_then(|unit| unit.get("value"))
                    .and_then(Value::as_str)
                    .ok_or_else(|| invalid_xcstrings("Plural variant is missing a string value"))?;
                let mut normalized = placeholders::normalize_foundation_plural(
                    value,
                    &mut placeholders,
                    "count",
                    None,
                );
                let conversions =
                    placeholders::foundation_plural_printf_line_separators(value, "count", None);
                if !conversions.is_empty() {
                    let (visible, owned) =
                        without_disabled_foundation_conversions(&normalized, &conversions);
                    normalized = visible;
                    disabled_variants.insert(category.clone(), Value::Array(owned));
                }
                values.insert(category.clone(), normalized.clone());
                ordered.push((category.clone(), normalized));
            }
            if !values.contains_key("other") {
                return Err(ParseError::new(
                    "MISSING_OTHER_VARIANT",
                    "Missing Xcode other",
                ));
            }
            if !placeholders
                .iter()
                .any(|placeholder| matches!(placeholder.kind, "integer" | "number"))
            {
                return Err(invalid_xcstrings(
                    "Xcode plural variation requires a numeric format argument",
                ));
            }
            let message = placeholders::plural(
                "count",
                ordered
                    .iter()
                    .map(|(category, value)| (category.as_str(), value.as_str())),
            );
            variants = Some(values);
            message
        } else {
            let value = effective_source
                .and_then(|entry| entry.get("stringUnit"))
                .and_then(|unit| unit.get("value"))
                .and_then(Value::as_str)
                .unwrap_or(id);
            let mut normalized = normalize_xcstrings_source_with_disabled(
                value,
                effective_source
                    .and_then(|entry| entry.get("substitutions"))
                    .or_else(|| source.and_then(|entry| entry.get("substitutions"))),
                &mut placeholders,
                &mut disabled_substitutions,
            )?;
            let conversions = placeholders::foundation_printf_line_separators(value);
            if !conversions.is_empty() {
                (normalized, disabled) =
                    without_disabled_foundation_conversions(&normalized, &conversions);
            }
            normalized
        };
        let mut metadata = Map::new();
        if !disabled.is_empty() {
            metadata.insert(
                "appleDisabledPrintfConversions".into(),
                Value::Array(disabled),
            );
        }
        if !disabled_variants.is_empty() {
            disabled_substitutions.insert("count".into(), Value::Object(disabled_variants));
        }
        if !disabled_substitutions.is_empty() {
            metadata.insert(
                "applePluralDisabledPrintfConversions".into(),
                Value::Object(disabled_substitutions),
            );
        }
        if source_identifier != language {
            metadata.insert("appleSourceLanguage".into(), json!(source_identifier));
        }
        if source_locale != source_identifier && source.is_some() {
            metadata.insert(
                "appleSourceLocalizationIdentifier".into(),
                json!(source_locale),
            );
        }
        if version.as_str() != Some("1.0") {
            metadata.insert("appleCatalogVersion".into(), version.clone());
        }
        if !catalog_metadata.is_empty() {
            metadata.insert(
                "appleCatalogMetadata".into(),
                Value::Object(catalog_metadata.clone()),
            );
        }
        let descriptor_metadata = descriptor
            .iter()
            .filter(|(key, _)| {
                !matches!(
                    key.as_str(),
                    "comment" | "extractionState" | "localizations" | "shouldTranslate"
                )
            })
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect::<Map<String, Value>>();
        if !descriptor_metadata.is_empty() {
            metadata.insert(
                "appleDescriptorMetadata".into(),
                Value::Object(descriptor_metadata),
            );
        }
        if let Some(state) = descriptor.get("extractionState").and_then(Value::as_str) {
            metadata.insert("extractionState".into(), json!(state));
        }
        if let Some(variations) = source
            .and_then(|value| value.get("variations"))
            .and_then(Value::as_object)
        {
            let axes: Map<String, Value> = variations
                .iter()
                .filter(|(name, _)| name.as_str() != "plural")
                .map(|(name, value)| (name.clone(), value.clone()))
                .collect();
            if !axes.is_empty() {
                metadata.insert("sourceVariationAxes".into(), Value::Object(axes));
            }
        }
        if let Some(device) = selected_device {
            metadata.insert("defaultDevice".into(), json!(device));
        }
        if let Some(substitutions) = source
            .and_then(|value| value.get("substitutions"))
            .and_then(Value::as_object)
        {
            metadata.insert(
                "sourceSubstitutions".into(),
                Value::Object(substitutions.clone()),
            );
        }
        if let Some(source) = source {
            metadata.insert("appleSourceLocalization".into(), source.clone());
            if let Some(state) = effective_source
                .and_then(|value| value.get("stringUnit"))
                .and_then(|unit| unit.get("state"))
                .and_then(Value::as_str)
            {
                metadata.insert("sourceState".into(), json!(state));
            }
            if let Some(plurals) = effective_source
                .and_then(|value| value.get("variations"))
                .and_then(|value| value.get("plural"))
                .and_then(Value::as_object)
            {
                let states = plurals
                    .iter()
                    .filter_map(|(category, descriptor)| {
                        descriptor
                            .get("stringUnit")
                            .and_then(|unit| unit.get("state"))
                            .and_then(Value::as_str)
                            .map(|state| (category.clone(), json!(state)))
                    })
                    .collect::<Map<String, Value>>();
                if !states.is_empty() {
                    metadata.insert("sourcePluralStates".into(), Value::Object(states));
                }
            }
        }
        if let Some(localizations) = localizations {
            let mut translated = Map::new();
            let mut localization_sources = Map::new();
            let mut localization_identifiers = Map::new();
            for (locale, localization) in localizations {
                let normalized = locale.replace('_', "-");
                if locale != source_locale && !localization.is_null() {
                    let identity = if locale.contains('_') && translated.contains_key(&normalized) {
                        locale.clone()
                    } else {
                        normalized
                    };
                    if translated
                        .insert(
                            identity.clone(),
                            Value::Object(xcstrings_translation_metadata(localization)),
                        )
                        .is_some()
                    {
                        return Err(ParseError::new(
                            "DUPLICATE_LOCALE",
                            "Duplicate normalized Xcode localization",
                        ));
                    }
                    localization_sources.insert(identity.clone(), localization.clone());
                    if identity != *locale {
                        localization_identifiers.insert(identity, json!(locale));
                    }
                }
            }
            if !translated.is_empty() {
                metadata.insert("localizations".into(), Value::Object(translated));
                metadata.insert(
                    "appleLocalizationSources".into(),
                    Value::Object(localization_sources),
                );
            }
            if !localization_identifiers.is_empty() {
                metadata.insert(
                    "appleLocalizationIdentifiers".into(),
                    Value::Object(localization_identifiers),
                );
            }
        }
        catalog.insert(
            id.clone(),
            Message::new(
                message,
                descriptor
                    .get("comment")
                    .and_then(Value::as_str)
                    .map(str::to_owned),
                variants,
                placeholders,
                metadata,
            ),
        )?;
    }
    Ok(catalog)
}

fn xcstrings_default_device(devices: &Map<String, Value>) -> Result<&str, ParseError> {
    [
        "iphone",
        "ipad",
        "mac",
        "applewatch",
        "applevision",
        "appletv",
        "ipod",
        "other",
    ]
    .into_iter()
    .find(|device| devices.contains_key(*device))
    .or_else(|| devices.keys().min().map(String::as_str))
    .ok_or_else(|| invalid_xcstrings("Xcode device variation must contain at least one device"))
}

fn xcstrings_translation_metadata(localization: &Value) -> Map<String, Value> {
    let mut metadata = Map::new();
    if let Some(unit) = localization.get("stringUnit").and_then(Value::as_object) {
        if let Some(value) = unit.get("value").and_then(Value::as_str) {
            metadata.insert("value".into(), json!(value));
            if let Some(state) = unit.get("state").and_then(Value::as_str) {
                metadata.insert("state".into(), json!(state));
            }
        }
    }
    if let Some(variations) = localization.get("variations").and_then(Value::as_object) {
        if let Some(plurals) = variations.get("plural").and_then(Value::as_object) {
            let mut values = Map::new();
            let mut states = Map::new();
            for (category, descriptor) in plurals {
                if let Some(value) = descriptor
                    .get("stringUnit")
                    .and_then(|unit| unit.get("value"))
                    .and_then(Value::as_str)
                {
                    values.insert(category.clone(), json!(value));
                }
                if let Some(state) = descriptor
                    .get("stringUnit")
                    .and_then(|unit| unit.get("state"))
                    .and_then(Value::as_str)
                {
                    states.insert(category.clone(), json!(state));
                }
            }
            metadata.insert("variants".into(), Value::Object(values));
            if !states.is_empty() {
                metadata.insert("variantStates".into(), Value::Object(states));
            }
        }
        let axes: Map<String, Value> = variations
            .iter()
            .filter(|(name, _)| name.as_str() != "plural")
            .map(|(name, value)| (name.clone(), value.clone()))
            .collect();
        if !axes.is_empty() {
            metadata.insert("variationAxes".into(), Value::Object(axes));
        }
    }
    metadata
}

fn validate_xcstring_descriptors(entries: &Map<String, Value>) -> Result<(), ParseError> {
    for descriptor in entries.values() {
        let descriptor = descriptor
            .as_object()
            .ok_or_else(|| invalid_xcstrings("Catalog descriptor must be an object"))?;
        for field in ["comment", "extractionState"] {
            if descriptor
                .get(field)
                .is_some_and(|value| !value.is_null() && !value.is_string())
            {
                return Err(invalid_xcstrings(
                    "Xcode descriptor field must be text or null",
                ));
            }
        }
        if descriptor
            .get("shouldTranslate")
            .is_some_and(|value| !value.is_null() && !value.is_boolean())
        {
            return Err(invalid_xcstrings(
                "Xcode shouldTranslate must be a boolean or null",
            ));
        }
        let localizations = descriptor
            .get("localizations")
            .and_then(Value::as_object)
            .ok_or_else(|| invalid_xcstrings("Xcode localizations must be an object"))?;
        let mut active_localization = false;
        let mut native_locales = BTreeMap::new();
        for (locale, localization) in localizations {
            let native_locale = xcstrings_native_bundle_locale(locale);
            if let Some(previous) = native_locales.insert(native_locale.clone(), locale) {
                return Err(ParseError::new(
                    "DUPLICATE_LOCALE",
                    format!(
                        "Xcode localizations {previous} and {locale} share native bundle {native_locale}"
                    ),
                ));
            }
            if localization.is_null() {
                continue;
            }
            active_localization = true;
            let localization = localization
                .as_object()
                .ok_or_else(|| invalid_xcstrings("Xcode localization must be an object or null"))?;
            if !localization.contains_key("stringUnit") && !localization.contains_key("variations")
            {
                return Err(invalid_xcstrings(
                    "Xcode localization requires a stringUnit or variations",
                ));
            }
            validate_xcstring_plural_substitution_references(localization.get("variations"))?;
        }
        if !active_localization {
            return Err(invalid_xcstrings(
                "Xcode descriptor requires an active localization",
            ));
        }
    }
    Ok(())
}

pub(crate) fn xcstrings_native_bundle_locale(locale: &str) -> String {
    match locale.to_ascii_lowercase().replace('_', "-").as_str() {
        "i-ami" => return "ami".to_owned(),
        "i-bnn" => return "bnn".to_owned(),
        "i-hak" => return "hak".to_owned(),
        "i-klingon" => return "tlh".to_owned(),
        "i-lux" => return "lb".to_owned(),
        "i-navajo" => return "nv".to_owned(),
        "i-pwn" => return "pwn".to_owned(),
        "i-tao" => return "tao".to_owned(),
        "i-tay" => return "tay".to_owned(),
        "i-tsu" => return "tsu".to_owned(),
        "sgn-be-fr" => return "sfb".to_owned(),
        "sgn-be-nl" => return "vgt".to_owned(),
        "sgn-ch-de" => return "sgg".to_owned(),
        "art-lojban" => return "jbo".to_owned(),
        "zh-min-nan" => return "nan".to_owned(),
        _ => {}
    }
    let mut separators = locale
        .chars()
        .filter(|character| matches!(character, '-' | '_'))
        .collect::<Vec<_>>();
    let raw_components = locale.split(['-', '_']).collect::<Vec<_>>();
    let rewritten = match raw_components.as_slice() {
        ["i", legacy, rest @ ..] if legacy.eq_ignore_ascii_case("klingon") => {
            Some((["tlh"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        ["no", legacy, rest @ ..] if legacy.eq_ignore_ascii_case("bok") => {
            Some((["nb"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        ["no", legacy, rest @ ..] if legacy.eq_ignore_ascii_case("nyn") => {
            Some((["nn"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        ["zh", extlang, rest @ ..]
            if extlang.eq_ignore_ascii_case("cmn") || extlang.eq_ignore_ascii_case("guoyu") =>
        {
            Some((["zh"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        ["zh", extlang, rest @ ..] if extlang.eq_ignore_ascii_case("hakka") => {
            Some((["hak"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        ["zh", extlang, rest @ ..] if extlang.eq_ignore_ascii_case("xiang") => {
            Some((["hsn"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        ["zh", extlang, rest @ ..] if extlang.eq_ignore_ascii_case("yue") => {
            Some((["yue"].into_iter().chain(rest.iter().copied())).collect::<Vec<_>>())
        }
        _ => None,
    };
    if let Some(rewritten) = rewritten {
        return xcstrings_native_bundle_locale(&rewritten.join("-"));
    }
    let mut components = raw_components
        .into_iter()
        .enumerate()
        .map(|(index, component)| match (index, component.len()) {
            (0, _) => match component.to_ascii_lowercase().as_str() {
                "iw" => "he".to_owned(),
                "in" => "id".to_owned(),
                "ji" => "yi".to_owned(),
                "no" if locale
                    .split(['-', '_'])
                    .nth(1)
                    .is_none_or(|next| next.len() == 2) =>
                {
                    "nb".to_owned()
                }
                "tl" => "fil".to_owned(),
                "jw" => "jv".to_owned(),
                "cmn" => "zh".to_owned(),
                "hbs" => "sr".to_owned(),
                "mol" => "mo".to_owned(),
                _ => component.to_ascii_lowercase(),
            },
            (_, 4) => {
                let mut characters = component.chars();
                let first = characters.next().unwrap().to_ascii_uppercase();
                format!("{first}{}", characters.as_str().to_ascii_lowercase())
            }
            (_, 2) => component.to_ascii_uppercase(),
            _ => component.to_ascii_lowercase(),
        })
        .collect::<Vec<_>>();
    if locale
        .split(['-', '_'])
        .next()
        .is_some_and(|language| language.eq_ignore_ascii_case("hbs"))
    {
        components.insert(1, "Latn".to_owned());
        separators.insert(0, '-');
    }
    if components.len() > 1 && components[1].len() == 2 {
        if components[0] == "en" && components[1] == "UK" {
            components[1] = "GB".to_owned();
        } else if components[0] == "cs" && components[1] == "CS" {
            components[1] = "CZ".to_owned();
        } else if components[0] == "sh" {
            components[0] = "sr".to_owned();
        }
    } else if components.len() > 2 && components[0] == "sh" && components[1] == "Latn" {
        components[0] = "sr".to_owned();
    }
    if components.len() > 4
        && components[0] == "en"
        && components[2].eq_ignore_ascii_case("u")
        && components[3].eq_ignore_ascii_case("nu")
        && components[4].eq_ignore_ascii_case("Latn")
    {
        components.truncate(4);
        separators.truncate(3);
    }
    if components.len() > 3
        && components[0] == "en"
        && components[2].eq_ignore_ascii_case("u")
        && components[3].eq_ignore_ascii_case("nu")
    {
        components[3] = "nu".to_owned();
    }
    if components.len() > 1 && components[1].len() == 4 {
        let remove_script = (matches!(components[0].as_str(), "sr" | "mn" | "kk")
            && components[1] == "Cyrl")
            || (matches!(
                components[0].as_str(),
                "az" | "uz" | "bs" | "hr" | "ha" | "nb"
            ) && components[1] == "Latn")
            || (components[0] == "pa" && components[1] == "Guru")
            || (components[0] == "zh" && components.len() > 2 && separators[1] == '_');
        if remove_script {
            components.remove(1);
            if components.len() > 1 {
                separators.remove(0);
            }
        } else {
            separators[0] = '-';
        }
    }
    let mut result = components[0].clone();
    for (separator, component) in separators.iter().zip(components.iter().skip(1)) {
        result.push(*separator);
        result.push_str(component);
    }
    result
}

pub(crate) fn xcstrings_source_locale<'a>(
    localizations: &'a Map<String, Value>,
    source_language: &'a str,
) -> &'a str {
    if localizations.contains_key(source_language) {
        return source_language;
    }
    let alternate = if source_language.contains('_') {
        source_language.replace('_', "-")
    } else {
        source_language.replace('-', "_")
    };
    if let Some((locale, _)) = localizations.get_key_value(&alternate) {
        return locale;
    }
    let source_bundle = xcstrings_native_bundle_locale(source_language);
    localizations
        .keys()
        .find(|locale| xcstrings_native_bundle_locale(locale) == source_bundle)
        .map_or(source_language, String::as_str)
}

fn validate_xcstring_plural_substitution_references(
    variations: Option<&Value>,
) -> Result<(), ParseError> {
    let Some(variations) = variations.and_then(Value::as_object) else {
        return Ok(());
    };
    if let Some(plural) = variations.get("plural").and_then(Value::as_object) {
        for category in plural.values() {
            if category
                .get("stringUnit")
                .and_then(|unit| unit.get("value"))
                .and_then(Value::as_str)
                .is_some_and(|value| xcstrings_substitution_pattern().is_match(value))
            {
                return Err(invalid_xcstrings(
                    "Xcode plural variants cannot reference substitution definitions",
                ));
            }
        }
    }
    if let Some(devices) = variations.get("device").and_then(Value::as_object) {
        for device in devices.values() {
            validate_xcstring_plural_substitution_references(device.get("variations"))?;
        }
    }
    Ok(())
}

pub(crate) fn normalize_xcstrings_source(
    source: &str,
    substitutions: Option<&Value>,
    placeholders: &mut Vec<Placeholder>,
) -> Result<String, ParseError> {
    normalize_xcstrings_source_with_disabled(source, substitutions, placeholders, &mut Map::new())
}

fn normalize_xcstrings_source_with_disabled(
    source: &str,
    substitutions: Option<&Value>,
    placeholders: &mut Vec<Placeholder>,
    disabled_substitutions: &mut Map<String, Value>,
) -> Result<String, ParseError> {
    let markers = xcstrings_substitution_pattern();
    if !markers.is_match(source) {
        return Ok(placeholders::normalize_foundation(source, placeholders));
    }
    let definitions = substitutions
        .and_then(Value::as_object)
        .filter(|definitions| !definitions.is_empty())
        .ok_or_else(|| {
            invalid_xcstrings("Xcode plural substitution must reference an active definition")
        })?;
    let mut masked = String::new();
    let mut previous = 0;
    let mut implicit_position = 0;
    let mut expansions = BTreeMap::new();
    for captures in markers.captures_iter(source) {
        let marker = captures.get(0).expect("substitution marker");
        let identifier = captures.get(2).expect("substitution identifier").as_str();
        if !xcstrings_substitution_name().is_match(identifier) {
            return Err(ParseError::new(
                "INVALID_PLACEHOLDER",
                "Xcode substitution name is not a valid ICU argument",
            ));
        }
        let definition = definitions
            .get(identifier)
            .and_then(Value::as_object)
            .ok_or_else(|| {
                invalid_xcstrings("Xcode plural substitution has no matching definition")
            })?;
        let declared_position = definition
            .get("argNum")
            .map(|value| {
                value
                    .as_u64()
                    .and_then(|position| usize::try_from(position).ok())
                    .filter(|position| *position > 0)
                    .ok_or_else(|| {
                        ParseError::new(
                            "INVALID_PLACEHOLDER",
                            "Xcode substitution argument position must be positive",
                        )
                    })
            })
            .transpose()?;
        let position = captures
            .get(1)
            .and_then(|position| position.as_str().parse::<usize>().ok())
            .or(declared_position)
            .unwrap_or_else(|| {
                implicit_position += 1;
                implicit_position
            });
        if position == 0 {
            return Err(ParseError::new(
                "INVALID_PLACEHOLDER",
                "Xcode substitution argument position must be positive",
            ));
        }
        if !expansions.contains_key(identifier) {
            expansions.insert(
                identifier.to_owned(),
                expand_xcstrings_substitution(
                    identifier,
                    position,
                    definition,
                    placeholders,
                    disabled_substitutions,
                )?,
            );
        }
        masked.push_str(&source[previous..marker.start()]);
        masked.push('\u{1}');
        masked.push_str(identifier);
        masked.push('\u{1}');
        previous = marker.end();
    }
    masked.push_str(&source[previous..]);
    let mut normalized = placeholders::normalize(&masked, placeholders, None);
    let mut positions = BTreeMap::new();
    for placeholder in &*placeholders {
        if let Some(position) = placeholder.position {
            if let Some(previous) = positions.insert(position, placeholder.kind) {
                if previous != placeholder.kind {
                    return Err(ParseError::new(
                        "INVALID_PLACEHOLDER",
                        "Xcode substitution argument has incompatible native types",
                    ));
                }
            }
        }
    }
    for (identifier, expansion) in expansions {
        normalized = normalized.replace(&format!("\u{1}{identifier}\u{1}"), &expansion);
    }
    Ok(normalized)
}

fn expand_xcstrings_substitution(
    identifier: &str,
    position: usize,
    definition: &Map<String, Value>,
    placeholders: &mut Vec<Placeholder>,
    disabled_substitutions: &mut Map<String, Value>,
) -> Result<String, ParseError> {
    let specifier = definition
        .get("formatSpecifier")
        .map(|value| {
            value.as_str().ok_or_else(|| {
                invalid_xcstrings("Xcode substitution format specifier must be text")
            })
        })
        .transpose()?;
    if let Some(specifier) = specifier {
        let mut typed = Vec::new();
        placeholders::normalize(&format!("%{specifier}"), &mut typed, Some(identifier));
        if !typed
            .first()
            .is_some_and(|placeholder| matches!(placeholder.kind, "integer" | "number"))
        {
            return Err(invalid_xcstrings(
                "Xcode plural substitution requires a numeric format specifier",
            ));
        }
    }
    let categories = definition
        .get("variations")
        .and_then(|variations| variations.get("plural"))
        .and_then(Value::as_object)
        .filter(|categories| !categories.is_empty())
        .ok_or_else(|| invalid_xcstrings("Xcode substitution must contain plural variations"))?;
    let mut variants = BTreeMap::new();
    let mut numeric = false;
    for (category, descriptor) in categories {
        let source = descriptor
            .get("stringUnit")
            .and_then(|unit| unit.get("value"))
            .and_then(Value::as_str)
            .ok_or_else(|| {
                invalid_xcstrings("Xcode substitution plural branch requires a string value")
            })?;
        let mut branch = Vec::new();
        let mut normalized = placeholders::normalize_foundation_substitution(
            source,
            &mut branch,
            identifier,
            position,
        );
        let conversions = placeholders::foundation_substitution_printf_line_separators(
            source, identifier, position,
        );
        if !conversions.is_empty() {
            let (visible, disabled) =
                without_disabled_foundation_conversions(&normalized, &conversions);
            normalized = visible;
            disabled_substitutions
                .entry(identifier.to_owned())
                .or_insert_with(|| Value::Object(Map::new()))
                .as_object_mut()
                .expect("Xcode selector owns a conversion dictionary")
                .insert(category.clone(), Value::Array(disabled));
        }
        for placeholder in branch {
            let selector = placeholder.position == Some(position);
            if selector && !matches!(placeholder.kind, "integer" | "number") {
                return Err(ParseError::new(
                    "INVALID_PLACEHOLDER",
                    "Xcode plural selector must reference a numeric argument",
                ));
            }
            let corrected = if selector {
                Placeholder {
                    name: identifier.to_owned(),
                    source: placeholder.source,
                    kind: placeholder.kind,
                    position: Some(position),
                    example: None,
                }
            } else {
                placeholder
            };
            if !placeholders.contains(&corrected) {
                placeholders.push(corrected);
            }
            numeric |= selector;
        }
        variants.insert(category.as_str(), normalized);
    }
    if !variants.contains_key("other") {
        return Err(ParseError::new(
            "MISSING_OTHER_VARIANT",
            "Xcode substitution plural is missing other",
        ));
    }
    if !numeric {
        let Some(specifier) = specifier else {
            return Err(invalid_xcstrings(
                "Xcode plural substitution requires a numeric format argument",
            ));
        };
        let mut typed = Vec::new();
        placeholders::normalize(&format!("%{specifier}"), &mut typed, Some(identifier));
        let placeholder = typed.into_iter().next().expect("validated numeric type");
        placeholders.push(Placeholder {
            name: identifier.to_owned(),
            source: placeholder.source,
            kind: placeholder.kind,
            position: Some(position),
            example: None,
        });
    }
    Ok(placeholders::plural(
        identifier,
        variants
            .iter()
            .map(|(category, value)| (*category, value.as_str())),
    ))
}

fn xcstrings_substitution_pattern() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new(r"%(?:(\d+)\$)?#@([^@]+)@").expect("valid Xcode substitution pattern")
    })
}

fn xcstrings_substitution_name() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new(r"^[\p{L}\p{N}\p{M}\p{So}_]+$").expect("valid Xcode substitution identifier")
    })
}

fn validate_xcstring_units(value: &Value) -> Result<(), ParseError> {
    match value {
        Value::Object(fields) => {
            for (key, entry) in fields {
                if key == "stringUnit"
                    && (!entry.is_object()
                        || entry.get("state").and_then(Value::as_str).is_none()
                        || entry.get("value").and_then(Value::as_str).is_none())
                {
                    return Err(invalid_xcstrings(
                        "Xcode stringUnit requires a state and value",
                    ));
                }
                if key == "variations" {
                    let axes = entry
                        .as_object()
                        .ok_or_else(|| invalid_xcstrings("Xcode variations must be an object"))?;
                    if axes.is_empty() {
                        return Err(invalid_xcstrings(
                            "Xcode variations must contain an active axis",
                        ));
                    }
                    if axes.contains_key("plural") && axes.len() > 1 {
                        return Err(ParseError::new(
                            "AMBIGUOUS_XCSTRINGS_VARIATIONS",
                            "Sibling Xcode plural and device axes compile nondeterministically",
                        ));
                    }
                    for (axis, variants) in axes {
                        let variants = variants.as_object().ok_or_else(|| {
                            invalid_xcstrings("Xcode variation axis must be an object")
                        })?;
                        if axis == "device"
                            && variants
                                .get("other")
                                .and_then(|fallback| fallback.get("variations"))
                                .and_then(Value::as_object)
                                .is_some()
                        {
                            return Err(invalid_xcstrings(
                                "Xcode fallback device value cannot be further varied",
                            ));
                        }
                        if axis == "device"
                            && variants.values().any(|device| {
                                device
                                    .get("substitutions")
                                    .and_then(Value::as_object)
                                    .is_some_and(|substitutions| !substitutions.is_empty())
                            })
                        {
                            return Err(invalid_xcstrings(
                                "Xcode substitution definitions belong to the localization root",
                            ));
                        }
                        if axis == "plural" {
                            for category in variants.keys() {
                                if !matches!(
                                    category.as_str(),
                                    "zero" | "one" | "two" | "few" | "many" | "other"
                                ) {
                                    return Err(ParseError::new(
                                        "INVALID_PLURAL_CATEGORY",
                                        format!("Unsupported Xcode plural category {category}"),
                                    ));
                                }
                            }
                        }
                    }
                }
                if key == "substitutions" && !entry.is_null() && !entry.is_object() {
                    return Err(invalid_xcstrings(
                        "Xcode substitutions must be an object or null",
                    ));
                }
                if key == "substitutions" {
                    if let Some(substitutions) = entry.as_object() {
                        for definition in substitutions.values() {
                            let definition = definition.as_object().ok_or_else(|| {
                                invalid_xcstrings("Xcode substitution definition must be an object")
                            })?;
                            if let Some(position) = definition.get("argNum") {
                                if position
                                    .as_u64()
                                    .and_then(|value| usize::try_from(value).ok())
                                    .filter(|value| *value > 0)
                                    .is_none()
                                {
                                    return Err(ParseError::new(
                                        "INVALID_PLACEHOLDER",
                                        "Xcode substitution argument position must be positive",
                                    ));
                                }
                            }
                            if definition
                                .get("formatSpecifier")
                                .is_some_and(|value| !value.is_string())
                            {
                                return Err(invalid_xcstrings(
                                    "Xcode substitution format specifier must be text",
                                ));
                            }
                        }
                    }
                }
                validate_xcstring_units(entry)?;
            }
        }
        Value::Array(values) => {
            for item in values {
                validate_xcstring_units(item)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn invalid_xcstrings(message: &str) -> ParseError {
    ParseError::new("INVALID_XCSTRINGS", message)
}
