use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use regex::Regex;
use serde_json::{Map, Value};
use std::collections::{BTreeMap, HashMap};
use std::sync::OnceLock;

const CATEGORIES: [&str; 6] = ["zero", "one", "two", "few", "many", "other"];

pub(crate) fn write(catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != FileFormat::AppleStringsdict.id() {
        return Err(invalid(
            "INVALID_SOURCE_FORMAT",
            "Apple stringsdict writer requires a stringsdict catalog",
        ));
    }
    let mut output = String::from(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<dict>\n",
    );
    for (key, message) in &catalog.messages {
        if message.description.is_some() {
            return Err(invalid(
                "UNSUPPORTED_APPLE_STRINGSDICT_DESCRIPTION",
                "Apple stringsdict cannot preserve translator descriptions",
            ));
        }
        let metadata = message.metadata.as_ref();
        line(&mut output, 1, "key", key)?;
        open(&mut output, 1, "dict");
        if let Some(branches) = metadata.and_then(|metadata| {
            metadata
                .get("deviceMixedVariants")
                .or_else(|| metadata.get("devicePluralVariants"))
                .or_else(|| metadata.get("deviceWidthVariants"))
        }) {
            if !branches.is_object() {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Apple device plural variants must be a dictionary",
                ));
            }
            line(&mut output, 2, "key", "NSStringDeviceSpecificRuleType")?;
            plist_value(&mut output, 2, branches)?;
            extras(
                &mut output,
                metadata.and_then(|metadata| metadata.get("applePlistExtras")),
                2,
                false,
                &[],
            )?;
            close(&mut output, 1, "dict");
            continue;
        }
        if let Some(format) = metadata
            .and_then(|metadata| metadata.get("appleLocalizedFormat"))
            .and_then(Value::as_str)
        {
            line(&mut output, 2, "key", "NSStringLocalizedFormatKey")?;
            line(&mut output, 2, "string", format)?;
        }
        if let Some(rules) = metadata
            .and_then(|metadata| metadata.get("applePluralRules"))
            .and_then(Value::as_object)
        {
            let variables = variables(metadata);
            if variables.is_empty() {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Missing Apple plural variables",
                ));
            }
            let canonical = canonical_variants(message, &variables)?;
            for variable in variables {
                let rule = rules
                    .get(variable)
                    .and_then(Value::as_object)
                    .ok_or_else(|| {
                        invalid(
                            "INVALID_APPLE_STRINGSDICT_METADATA",
                            "Missing Apple plural definition",
                        )
                    })?;
                let source_variants =
                    rule.get("variants")
                        .and_then(Value::as_object)
                        .ok_or_else(|| {
                            invalid(
                                "INVALID_APPLE_STRINGSDICT_METADATA",
                                "Missing Apple source variants",
                            )
                        })?;
                let variants = canonical.get(variable).ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Missing canonical Apple plural",
                    )
                })?;
                if !variants.contains_key("other") {
                    return Err(invalid(
                        "MISSING_OTHER_VARIANT",
                        "Apple plural is missing other",
                    ));
                }
                line(&mut output, 2, "key", variable)?;
                open(&mut output, 2, "dict");
                line(&mut output, 3, "key", "NSStringFormatSpecTypeKey")?;
                line(&mut output, 3, "string", "NSStringPluralRuleType")?;
                if let Some(value_type) = rule.get("valueType").and_then(Value::as_str) {
                    line(&mut output, 3, "key", "NSStringFormatValueTypeKey")?;
                    line(&mut output, 3, "string", value_type)?;
                }
                for category in CATEGORIES {
                    let Some(translated) = variants.get(category) else {
                        continue;
                    };
                    let source = source_variants.get(category).and_then(Value::as_str);
                    let native = source
                        .filter(|source| normalized(source, variable) == *translated)
                        .map(str::to_owned)
                        .unwrap_or_else(|| restore_scoped(translated, message, variable, category));
                    line(&mut output, 3, "key", category)?;
                    line(&mut output, 3, "string", &native)?;
                }
                extras(&mut output, rule.get("applePlistExtras"), 3, true, &[])?;
                close(&mut output, 2, "dict");
            }
        } else if message.variants.is_some() {
            return Err(invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA",
                "Missing Apple plural definitions",
            ));
        }
        variation(
            &mut output,
            metadata,
            "widthVariants",
            "NSStringVariableWidthRuleType",
            true,
        )?;
        variation(
            &mut output,
            metadata,
            "deviceVariants",
            "NSStringDeviceSpecificRuleType",
            false,
        )?;
        extras(
            &mut output,
            metadata.and_then(|metadata| metadata.get("applePlistExtras")),
            2,
            false,
            &variables(metadata),
        )?;
        if metadata
            .map(|metadata| {
                ["applePluralRules", "widthVariants", "deviceVariants"]
                    .iter()
                    .all(|field| !metadata.contains_key(*field))
            })
            .unwrap_or(true)
        {
            return Err(invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA",
                "Apple stringsdict requires a plural, width, or device rule",
            ));
        }
        close(&mut output, 1, "dict");
    }
    output.push_str("</dict>\n</plist>\n");
    Ok(output)
}

fn extras(
    output: &mut String,
    value: Option<&Value>,
    depth: usize,
    plural_rule: bool,
    variables: &[&str],
) -> Result<(), ParseError> {
    let Some(value) = value else {
        return Ok(());
    };
    let fields = value.as_object().ok_or_else(|| {
        invalid(
            "INVALID_APPLE_STRINGSDICT_METADATA",
            "Apple plist extras must be dictionaries",
        )
    })?;
    for (key, field) in fields {
        let reserved = if plural_rule {
            matches!(
                key.as_str(),
                "NSStringFormatSpecTypeKey"
                    | "NSStringFormatValueTypeKey"
                    | "zero"
                    | "one"
                    | "two"
                    | "few"
                    | "many"
                    | "other"
            )
        } else if depth == 2 {
            matches!(
                key.as_str(),
                "NSStringLocalizedFormatKey"
                    | "NSStringVariableWidthRuleType"
                    | "NSStringDeviceSpecificRuleType"
            ) || variables.contains(&key.as_str())
        } else {
            false
        };
        if reserved || plural_rule && field.is_string() && !key.starts_with("NSString") {
            return Err(invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA",
                "Invalid Apple plist metadata key",
            ));
        }
        line(output, depth, "key", key)?;
        plist_value(output, depth, field)?;
    }
    Ok(())
}

fn plist_value(output: &mut String, depth: usize, value: &Value) -> Result<(), ParseError> {
    match value {
        Value::String(value) => line(output, depth, "string", value),
        Value::Bool(value) => {
            output.push_str(&"  ".repeat(depth));
            output.push_str(if *value { "<true/>\n" } else { "<false/>\n" });
            Ok(())
        }
        Value::Number(value) if value.as_i64().is_some() || value.as_u64().is_some() => {
            line(output, depth, "integer", &value.to_string())
        }
        Value::Array(values) => {
            open(output, depth, "array");
            for value in values {
                plist_value(output, depth + 1, value)?;
            }
            close(output, depth, "array");
            Ok(())
        }
        Value::Object(fields) if fields.contains_key("$applePlistType") => {
            tagged_value(output, depth, fields)
        }
        Value::Object(_) => {
            open(output, depth, "dict");
            extras(output, Some(value), depth + 1, false, &[])?;
            close(output, depth, "dict");
            Ok(())
        }
        _ => Err(invalid(
            "INVALID_APPLE_STRINGSDICT_METADATA",
            "Unsupported Apple plist value",
        )),
    }
}

fn tagged_value(
    output: &mut String,
    depth: usize,
    value: &Map<String, Value>,
) -> Result<(), ParseError> {
    let kind = value
        .get("$applePlistType")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA",
                "Invalid tagged Apple plist value",
            )
        })?;
    match kind {
        "data" => {
            let encoded = value
                .get("base64")
                .and_then(Value::as_str)
                .filter(|_| value.len() == 2)
                .ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Invalid Apple plist data metadata",
                    )
                })?;
            let bytes = crate::apple::base64_decode(encoded).map_err(|_| {
                invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Invalid Apple plist data metadata",
                )
            })?;
            if crate::apple::base64_encode(&bytes) != encoded {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Noncanonical Apple plist data",
                ));
            }
            line(output, depth, "data", encoded)
        }
        "date" => {
            let date = value
                .get("value")
                .and_then(Value::as_str)
                .filter(|_| value.len() == 2)
                .ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Invalid Apple plist date metadata",
                    )
                })?;
            let normalized = crate::apple::plist_date(date).map_err(|_| {
                invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Invalid Apple plist date metadata",
                )
            })?;
            if normalized != date {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Noncanonical Apple plist date",
                ));
            }
            line(output, depth, "date", date)
        }
        "real" => {
            let bits = value
                .get("bits")
                .and_then(Value::as_str)
                .filter(|bits| {
                    value.len() == 2
                        && bits.len() == 16
                        && bits
                            .bytes()
                            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
                })
                .ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Invalid Apple plist real metadata",
                    )
                })?;
            let number = f64::from_bits(u64::from_str_radix(bits, 16).expect("validated bits"));
            if number.is_nan() && number.to_bits() != f64::NAN.to_bits() {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Noncanonical Apple plist NaN",
                ));
            }
            let formatted = if number.is_nan() {
                "nan".to_owned()
            } else if number == f64::INFINITY {
                "infinity".to_owned()
            } else if number == f64::NEG_INFINITY {
                "-infinity".to_owned()
            } else {
                let scientific = format!("{number:.17e}");
                let (mantissa, exponent) = scientific.split_once('e').expect("scientific real");
                format!(
                    "{mantissa}e{:+}",
                    exponent.parse::<i32>().expect("scientific exponent")
                )
            };
            line(output, depth, "real", &formatted)
        }
        "dictionary" => {
            let entries = value
                .get("entries")
                .and_then(Value::as_array)
                .filter(|_| value.len() == 2)
                .ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Invalid escaped Apple plist dictionary",
                    )
                })?;
            let mut fields = Map::new();
            for entry in entries {
                let pair = entry
                    .as_object()
                    .filter(|pair| pair.len() == 2)
                    .ok_or_else(|| {
                        invalid(
                            "INVALID_APPLE_STRINGSDICT_METADATA",
                            "Invalid escaped Apple plist dictionary",
                        )
                    })?;
                let key = pair.get("key").and_then(Value::as_str).ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Invalid escaped Apple plist dictionary",
                    )
                })?;
                let entry_value = pair.get("value").ok_or_else(|| {
                    invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Invalid escaped Apple plist dictionary",
                    )
                })?;
                if fields.insert(key.to_owned(), entry_value.clone()).is_some() {
                    return Err(invalid(
                        "INVALID_APPLE_STRINGSDICT_METADATA",
                        "Duplicate escaped Apple plist dictionary key",
                    ));
                }
            }
            open(output, depth, "dict");
            extras(output, Some(&Value::Object(fields)), depth + 1, false, &[])?;
            close(output, depth, "dict");
            Ok(())
        }
        _ => Err(invalid(
            "INVALID_APPLE_STRINGSDICT_METADATA",
            "Unknown Apple plist metadata tag",
        )),
    }
}

fn variables(metadata: Option<&Map<String, Value>>) -> Vec<&str> {
    if let Some(variable) = metadata
        .and_then(|metadata| metadata.get("pluralVariable"))
        .and_then(Value::as_str)
    {
        return vec![variable];
    }
    metadata
        .and_then(|metadata| metadata.get("pluralVariables"))
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect()
}

fn canonical_variants(
    message: &Message,
    variables: &[&str],
) -> Result<BTreeMap<String, BTreeMap<String, String>>, ParseError> {
    if variables.len() == 1 {
        if let Some(variants) = &message.variants {
            return Ok(BTreeMap::from([(
                variables[0].to_owned(),
                variants.clone(),
            )]));
        }
    }
    let mut result = BTreeMap::new();
    for variable in variables {
        let marker = format!("{{{variable}, plural,");
        let start = message.default_message.find(&marker).ok_or_else(|| {
            invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA",
                "Plural is missing from ICU pattern",
            )
        })?;
        let mut cursor = start + marker.len();
        let bytes = message.default_message.as_bytes();
        let mut values = BTreeMap::new();
        while cursor < bytes.len() {
            while bytes.get(cursor).is_some_and(u8::is_ascii_whitespace) {
                cursor += 1;
            }
            if bytes.get(cursor).is_none() || bytes.get(cursor) == Some(&b'}') {
                break;
            }
            let selector_start = cursor;
            while bytes
                .get(cursor)
                .is_some_and(|value| !value.is_ascii_whitespace())
            {
                cursor += 1;
            }
            let selector = &message.default_message[selector_start..cursor];
            while bytes.get(cursor).is_some_and(u8::is_ascii_whitespace) {
                cursor += 1;
            }
            if bytes.get(cursor) != Some(&b'{') {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Invalid ICU plural branch",
                ));
            }
            cursor += 1;
            let content_start = cursor;
            let mut depth = 1;
            while cursor < bytes.len() && depth > 0 {
                match bytes[cursor] {
                    b'{' => depth += 1,
                    b'}' => depth -= 1,
                    _ => {}
                }
                cursor += 1;
            }
            if depth != 0 {
                return Err(invalid(
                    "INVALID_APPLE_STRINGSDICT_METADATA",
                    "Unclosed ICU plural branch",
                ));
            }
            values.insert(
                selector.to_owned(),
                message.default_message[content_start..cursor - 1].to_owned(),
            );
        }
        result.insert((*variable).to_owned(), values);
    }
    Ok(result)
}

fn normalized(source: &str, name: &str) -> String {
    let value = crate::placeholders::normalize(source, &mut Vec::new(), Some(name));
    let conversions = crate::placeholders::named_printf_line_separators(source, name);
    if conversions.is_empty() {
        value
    } else {
        crate::apple::without_disabled_printf_conversions(&value, &conversions).0
    }
}

pub(crate) fn restore_scoped(
    value: &str,
    message: &Message,
    selector: &str,
    category: &str,
) -> String {
    let original = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("applePluralRules"))
        .and_then(|rules| rules.get(selector))
        .and_then(|definition| definition.get("variants"))
        .and_then(|variants| variants.get(category))
        .and_then(Value::as_str)
        .expect("disabled plural conversion owns an original native variant");
    let mut metadata = Map::new();
    if let Some(conversions) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("applePluralDisabledPrintfConversions"))
        .and_then(|rules| rules.get(selector))
        .and_then(|categories| categories.get(category))
        .and_then(Value::as_array)
    {
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            Value::Array(conversions.clone()),
        );
    }
    let scoped = Message::new(
        normalized(original, selector),
        None,
        None,
        message.placeholders.clone().unwrap_or_default(),
        metadata,
    );
    crate::apple_writer::native_value(&scoped, value)
}

pub(crate) fn restore(value: &str, message: &Message) -> String {
    if let Some(conversions) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleDisabledPrintfConversions"))
        .and_then(Value::as_array)
    {
        let source_length = message.default_message.chars().count();
        let target_length = value.chars().count();
        let translated_conversions = conversions
            .iter()
            .map(|conversion| {
                let mut occurrence = conversion
                    .as_object()
                    .expect("validated disabled Foundation conversion")
                    .clone();
                let original_position = occurrence
                    .get("position")
                    .and_then(Value::as_u64)
                    .expect("validated disabled Foundation position")
                    as usize;
                let mut translated_position = if source_length == 0 {
                    0
                } else {
                    (original_position * target_length + source_length / 2) / source_length
                };
                let mut closest_anchor = usize::MAX;
                for placeholder in message.placeholders.iter().flatten() {
                    let argument = format!("{{{}}}", placeholder.name);
                    let source_argument = message
                        .default_message
                        .find(&argument)
                        .map(|index| message.default_message[..index].chars().count());
                    let target_argument = value
                        .find(&argument)
                        .map(|index| value[..index].chars().count());
                    if let (Some(source_start), Some(target_start)) =
                        (source_argument, target_argument)
                    {
                        let argument_length = argument.chars().count();
                        let source_end = source_start + argument_length;
                        let (distance, candidate) = if original_position >= source_end {
                            let distance = original_position - source_end;
                            (distance, target_start + argument_length + distance)
                        } else if original_position <= source_start {
                            let distance = source_start - original_position;
                            (distance, target_start.saturating_sub(distance))
                        } else {
                            continue;
                        };
                        if distance < closest_anchor {
                            closest_anchor = distance;
                            translated_position = candidate.min(target_length);
                            if distance == 0 {
                                break;
                            }
                        }
                    }
                }
                occurrence.insert("position".into(), Value::from(translated_position));
                Value::Object(occurrence)
            })
            .collect();
        let mut metadata = Map::new();
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            Value::Array(translated_conversions),
        );
        let scoped = Message::new(
            value.to_owned(),
            None,
            None,
            message.placeholders.clone().unwrap_or_default(),
            metadata,
        );
        return crate::apple_writer::native_value(&scoped, value);
    }
    if value.contains('%') {
        return crate::apple_writer::native_value(message, value);
    }
    let mut placeholders: HashMap<&str, Vec<&Placeholder>> = HashMap::new();
    for placeholder in message.placeholders.iter().flatten() {
        placeholders
            .entry(&placeholder.name)
            .or_default()
            .push(placeholder);
    }
    let mut occurrences = HashMap::new();
    let mut output = String::new();
    let mut previous = 0;
    for captures in argument_pattern().captures_iter(value) {
        let matched = captures.get(0).expect("argument match");
        let name = captures.get(1).expect("argument name").as_str();
        let Some(choices) = placeholders.get(name) else {
            continue;
        };
        output.push_str(&value[previous..matched.start()]);
        let occurrence = occurrences.entry(name).or_insert(0_usize);
        output.push_str(&choices[(*occurrence).min(choices.len() - 1)].source);
        *occurrence += 1;
        previous = matched.end();
    }
    output.push_str(&value[previous..]);
    output
}

fn variation(
    output: &mut String,
    metadata: Option<&Map<String, Value>>,
    field: &str,
    rule: &str,
    widths: bool,
) -> Result<(), ParseError> {
    let Some(values) = metadata
        .and_then(|metadata| metadata.get(field))
        .and_then(Value::as_object)
    else {
        return Ok(());
    };
    let mut entries = values.iter().collect::<Vec<_>>();
    if widths {
        entries.sort_by_key(|(key, _)| (key.parse::<usize>().unwrap_or_default(), key.as_str()));
    } else {
        entries.sort_by_key(|(key, _)| key.as_str());
    }
    line(output, 2, "key", rule)?;
    open(output, 2, "dict");
    for (key, value) in entries {
        let value = value.as_str().ok_or_else(|| {
            invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA",
                "Invalid Apple variation value",
            )
        })?;
        line(output, 3, "key", key)?;
        line(output, 3, "string", value)?;
    }
    close(output, 2, "dict");
    Ok(())
}

fn open(output: &mut String, depth: usize, tag: &str) {
    output.push_str(&"  ".repeat(depth));
    output.push('<');
    output.push_str(tag);
    output.push_str(">\n");
}

fn close(output: &mut String, depth: usize, tag: &str) {
    output.push_str(&"  ".repeat(depth));
    output.push_str("</");
    output.push_str(tag);
    output.push_str(">\n");
}

fn line(output: &mut String, depth: usize, tag: &str, value: &str) -> Result<(), ParseError> {
    output.push_str(&"  ".repeat(depth));
    output.push('<');
    output.push_str(tag);
    output.push('>');
    output.push_str(&escape(value)?);
    output.push_str("</");
    output.push_str(tag);
    output.push_str(">\n");
    Ok(())
}

fn escape(value: &str) -> Result<String, ParseError> {
    let mut output = String::new();
    for character in value.chars() {
        match character {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '>' => output.push_str("&gt;"),
            '\r' => output.push_str("&#xD;"),
            value
                if (value < ' ' && !matches!(value, '\n' | '\t'))
                    || matches!(u32::from(value) & 0xffff, 0xfffe | 0xffff) =>
            {
                return Err(invalid(
                    "INVALID_APPLE_PLIST_TEXT",
                    "Character is forbidden in XML property lists",
                ))
            }
            _ => output.push(character),
        }
    }
    Ok(output)
}

fn argument_pattern() -> &'static Regex {
    static ARGUMENT: OnceLock<Regex> = OnceLock::new();
    ARGUMENT.get_or_init(|| Regex::new(r"\{([\p{L}\p{N}\p{M}\p{So}_]+)\}").expect("valid argument"))
}

fn invalid(code: &'static str, message: &str) -> ParseError {
    ParseError::new(code, message)
}
