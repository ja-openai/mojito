use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::sync::OnceLock;

pub(crate) fn write(catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != FileFormat::AppleXcstrings.id() {
        return Err(invalid(
            "INVALID_SOURCE_FORMAT",
            "Xcode writer requires an Xcode String Catalog",
        ));
    }
    let Some(locale) = catalog.locale.as_ref().filter(|value| !value.is_empty()) else {
        return Err(invalid(
            "INVALID_XCSTRINGS_METADATA",
            "Xcode String Catalog requires a source locale",
        ));
    };
    let mut root = Map::new();
    let mut entries = Map::new();
    let mut source_language = locale.to_owned();
    let mut version = json!("1.0");
    let mut declared_version: Option<Value> = None;
    let mut root_metadata: Option<Map<String, Value>> = None;
    for (key, message) in &catalog.messages {
        crate::apple_writer::validate_disabled_conversions(message)?;
        let metadata = message.metadata.as_ref();
        if let Some(language) = metadata
            .and_then(|metadata| metadata.get("appleSourceLanguage"))
            .and_then(Value::as_str)
        {
            source_language = language.to_owned();
        }
        if let Some(value) = metadata.and_then(|metadata| metadata.get("appleCatalogVersion")) {
            if !value.is_string() && !value.is_number() {
                return Err(invalid(
                    "INVALID_XCSTRINGS_METADATA",
                    "Xcode catalog version must be a string or number",
                ));
            }
            if declared_version
                .as_ref()
                .is_some_and(|previous| previous != value)
            {
                return Err(invalid(
                    "INVALID_XCSTRINGS_METADATA",
                    "Xcode catalog versions must match across descriptors",
                ));
            }
            declared_version = Some(value.clone());
            version = value.clone();
        }
        if let Some(current) = metadata
            .and_then(|metadata| metadata.get("appleCatalogMetadata"))
            .and_then(Value::as_object)
        {
            if root_metadata
                .as_ref()
                .is_some_and(|previous| previous != current)
            {
                return Err(invalid(
                    "INVALID_XCSTRINGS_METADATA",
                    "Xcode root metadata must match across descriptors",
                ));
            }
            root_metadata = Some(current.clone());
        }
        entries.insert(
            key.clone(),
            Value::Object(descriptor(&source_language, message)?),
        );
    }
    if let Some(metadata) = root_metadata {
        root.extend(metadata);
    }
    root.insert("sourceLanguage".into(), json!(source_language));
    root.insert("strings".into(), Value::Object(entries));
    root.insert("version".into(), version);
    serde_json::to_string_pretty(&Value::Object(root))
        .map(|output| output + "\n")
        .map_err(|error| invalid("INVALID_XCSTRINGS_METADATA", &error.to_string()))
}

fn descriptor(source_language: &str, message: &Message) -> Result<Map<String, Value>, ParseError> {
    let metadata = message.metadata.as_ref();
    let mut descriptor = metadata
        .and_then(|metadata| metadata.get("appleDescriptorMetadata"))
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    if let Some(description) = &message.description {
        descriptor.insert("comment".into(), json!(description));
    }
    if let Some(state) = metadata
        .and_then(|metadata| metadata.get("extractionState"))
        .and_then(Value::as_str)
    {
        descriptor.insert("extractionState".into(), json!(state));
    }
    let mut localizations = Map::new();
    if let Some(original) = metadata
        .and_then(|metadata| metadata.get("appleSourceLocalization"))
        .and_then(Value::as_object)
    {
        let mut source = original.clone();
        apply_source(&mut source, message)?;
        let source_identifier = metadata
            .and_then(|metadata| metadata.get("appleSourceLocalizationIdentifier"))
            .and_then(Value::as_str)
            .unwrap_or(source_language);
        localizations.insert(source_identifier.to_owned(), Value::Object(source));
    }
    if let Some(translations) = metadata
        .and_then(|metadata| metadata.get("localizations"))
        .and_then(Value::as_object)
    {
        let sources = metadata
            .and_then(|metadata| metadata.get("appleLocalizationSources"))
            .and_then(Value::as_object);
        let identifiers = metadata
            .and_then(|metadata| metadata.get("appleLocalizationIdentifiers"))
            .and_then(Value::as_object);
        for (locale, translated) in translations {
            let descriptor = translated.as_object().ok_or_else(|| {
                invalid(
                    "INVALID_XCSTRINGS_METADATA",
                    "Invalid Xcode localization descriptor",
                )
            })?;
            let mut localization = sources
                .and_then(|sources| sources.get(locale))
                .and_then(Value::as_object)
                .cloned()
                .unwrap_or_default();
            apply_translation(&mut localization, descriptor)?;
            let identifier = identifiers
                .and_then(|identifiers| identifiers.get(locale))
                .and_then(Value::as_str)
                .unwrap_or(locale);
            localizations.insert(identifier.to_owned(), Value::Object(localization));
        }
    }
    if !localizations.is_empty() {
        descriptor.insert("localizations".into(), Value::Object(localizations));
    }
    Ok(descriptor)
}

fn apply_source(source: &mut Map<String, Value>, message: &Message) -> Result<(), ParseError> {
    let metadata = message.metadata.as_ref();
    let top_level_plural = source
        .get("variations")
        .and_then(Value::as_object)
        .and_then(|axes| axes.get("plural"))
        .and_then(Value::as_object)
        .is_some();
    let device = metadata
        .and_then(|metadata| metadata.get("defaultDevice"))
        .and_then(Value::as_str);
    let effective = if !top_level_plural {
        if let Some(device) = device {
            source
                .get_mut("variations")
                .and_then(Value::as_object_mut)
                .and_then(|axes| axes.get_mut("device"))
                .and_then(Value::as_object_mut)
                .and_then(|devices| devices.get_mut(device))
                .and_then(Value::as_object_mut)
                .ok_or_else(|| {
                    invalid(
                        "INVALID_XCSTRINGS_METADATA",
                        "Missing Xcode default-device source",
                    )
                })?
        } else {
            source
        }
    } else {
        source
    };
    if let Some(variants) = &message.variants {
        if !variants.contains_key("other") {
            return Err(invalid(
                "MISSING_OTHER_VARIANT",
                "Xcode plural is missing other",
            ));
        }
        let branches = ensure_dictionary(ensure_dictionary(effective, "variations")?, "plural")?;
        for (category, translated) in variants {
            let unit = ensure_dictionary(ensure_dictionary(branches, category)?, "stringUnit")?;
            let original = unit.get("value").and_then(Value::as_str);
            if let Some(conversions) = metadata
                .and_then(|metadata| metadata.get("applePluralDisabledPrintfConversions"))
                .and_then(|rules| rules.get("count"))
                .and_then(|categories| categories.get(category))
            {
                let mut scoped_metadata = Map::new();
                scoped_metadata
                    .insert("appleDisabledPrintfConversions".into(), conversions.clone());
                let scoped = Message::new(
                    translated.clone(),
                    None,
                    None,
                    message.placeholders.clone().unwrap_or_default(),
                    scoped_metadata,
                );
                crate::apple_writer::validate_disabled_conversions(&scoped)?;
            }
            let native = original
                .filter(|source| normalized_variant(source) == *translated)
                .map(str::to_owned)
                .unwrap_or_else(|| restore_variant(translated, message, category));
            unit.insert("value".into(), json!(native));
            if !unit.get("state").is_some_and(Value::is_string) {
                let state = metadata
                    .and_then(|metadata| metadata.get("sourcePluralStates"))
                    .and_then(Value::as_object)
                    .and_then(|states| states.get(category))
                    .and_then(Value::as_str)
                    .unwrap_or("translated");
                unit.insert("state".into(), json!(state));
            }
        }
    } else {
        let substitutions = effective.get("substitutions").cloned().or_else(|| {
            metadata
                .and_then(|values| values.get("sourceSubstitutions"))
                .cloned()
        });
        let unit = ensure_dictionary(effective, "stringUnit")?;
        let original = unit.get("value").and_then(Value::as_str);
        let native = if let Some(original) = original {
            let normalized = normalized_source(original, substitutions.as_ref())?;
            if normalized == message.default_message {
                original.to_owned()
            } else {
                restore(&message.default_message, message)
            }
        } else {
            restore(&message.default_message, message)
        };
        unit.insert("value".into(), json!(native));
        if !unit.get("state").is_some_and(Value::is_string) {
            let state = metadata
                .and_then(|metadata| metadata.get("sourceState"))
                .and_then(Value::as_str)
                .unwrap_or("translated");
            unit.insert("state".into(), json!(state));
        }
    }
    Ok(())
}

fn apply_translation(
    localization: &mut Map<String, Value>,
    metadata: &Map<String, Value>,
) -> Result<(), ParseError> {
    if let Some(value) = metadata.get("value").and_then(Value::as_str) {
        let unit = ensure_dictionary(localization, "stringUnit")?;
        unit.insert("value".into(), json!(value));
        unit.insert(
            "state".into(),
            json!(metadata
                .get("state")
                .and_then(Value::as_str)
                .unwrap_or("translated")),
        );
    }
    if let Some(variants) = metadata.get("variants").and_then(Value::as_object) {
        let states = metadata.get("variantStates").and_then(Value::as_object);
        let branches = ensure_dictionary(ensure_dictionary(localization, "variations")?, "plural")?;
        for (category, value) in variants {
            let value = value.as_str().ok_or_else(|| {
                invalid(
                    "INVALID_XCSTRINGS_METADATA",
                    "Invalid Xcode translated plural",
                )
            })?;
            let unit = ensure_dictionary(ensure_dictionary(branches, category)?, "stringUnit")?;
            unit.insert("value".into(), json!(value));
            unit.insert(
                "state".into(),
                json!(states
                    .and_then(|states| states.get(category))
                    .and_then(Value::as_str)
                    .unwrap_or("translated")),
            );
        }
    }
    if let Some(axes) = metadata.get("variationAxes").and_then(Value::as_object) {
        ensure_dictionary(localization, "variations")?.extend(axes.clone());
    }
    Ok(())
}

fn ensure_dictionary<'a>(
    parent: &'a mut Map<String, Value>,
    field: &str,
) -> Result<&'a mut Map<String, Value>, ParseError> {
    let value = parent
        .entry(field)
        .or_insert_with(|| Value::Object(Map::new()));
    value.as_object_mut().ok_or_else(|| {
        invalid(
            "INVALID_XCSTRINGS_METADATA",
            "Xcode catalog object field is not a dictionary",
        )
    })
}

pub(crate) fn restore(value: &str, message: &Message) -> String {
    if message
        .metadata
        .as_ref()
        .is_some_and(|metadata| metadata.contains_key("appleDisabledPrintfConversions"))
    {
        return crate::apple_writer::native_value(message, value);
    }
    if value.contains('%') && !substitution_marker_pattern().is_match(value) {
        crate::apple_writer::native_value(message, value)
    } else {
        restore_arguments(value, message)
    }
}

pub(crate) fn restore_variant(value: &str, message: &Message, category: &str) -> String {
    let Some(conversions) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("applePluralDisabledPrintfConversions"))
        .and_then(|rules| rules.get("count"))
        .and_then(|categories| categories.get(category))
        .and_then(Value::as_array)
    else {
        return if value.contains('%') && !substitution_marker_pattern().is_match(value) {
            crate::apple_writer::native_value(message, value)
        } else {
            restore_arguments(value, message)
        };
    };
    let original = message
        .variants
        .as_ref()
        .and_then(|variants| variants.get(category))
        .expect("disabled Xcode conversion owns a canonical plural category");
    let mut metadata = Map::new();
    metadata.insert(
        "appleDisabledPrintfConversions".into(),
        Value::Array(conversions.clone()),
    );
    let scoped = Message::new(
        original.clone(),
        None,
        None,
        message.placeholders.clone().unwrap_or_default(),
        metadata,
    );
    crate::apple_writer::native_value(&scoped, value)
}

pub(crate) fn restore_substitution_variant(
    value: &str,
    message: &Message,
    selector: &str,
    category: &str,
) -> String {
    let Some(definition) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("sourceSubstitutions"))
        .and_then(|substitutions| substitutions.get(selector))
        .and_then(Value::as_object)
    else {
        return restore(value, message);
    };
    let Some(original) = definition
        .get("variations")
        .and_then(|variations| variations.get("plural"))
        .and_then(|branches| branches.get(category))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(Value::as_str)
    else {
        return restore(value, message);
    };
    let Some(disabled_conversions) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("applePluralDisabledPrintfConversions"))
        .and_then(|rules| rules.get(selector))
        .and_then(|categories| categories.get(category))
        .and_then(Value::as_array)
    else {
        return restore(value, message);
    };
    let position = definition
        .get("argNum")
        .and_then(Value::as_u64)
        .map(|position| position as usize)
        .or_else(|| {
            message
                .placeholders
                .iter()
                .flatten()
                .find_map(|placeholder| {
                    (placeholder.name == selector)
                        .then_some(placeholder.position)
                        .flatten()
                })
        })
        .unwrap_or(1);
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation_substitution(
        original,
        &mut placeholders,
        selector,
        position,
    );
    let conversions = crate::placeholders::foundation_substitution_printf_line_separators(
        original, selector, position,
    );
    if !conversions.is_empty() {
        normalized =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions).0;
    }
    let source_length = normalized.chars().count();
    let target_length = value.chars().count();
    let argument = format!("{{{selector}}}");
    let source_argument = normalized
        .find(&argument)
        .map(|index| normalized[..index].chars().count());
    let target_argument = value
        .find(&argument)
        .map(|index| value[..index].chars().count());
    let argument_length = argument.chars().count();
    let translated_conversions = disabled_conversions
        .iter()
        .map(|conversion| {
            let mut occurrence = conversion
                .as_object()
                .expect("validated disabled Xcode substitution conversion")
                .clone();
            let original_position = occurrence
                .get("position")
                .and_then(Value::as_u64)
                .expect("validated disabled Xcode substitution position")
                as usize;
            let mut translated_position = if source_length == 0 {
                0
            } else {
                (original_position * target_length + source_length / 2) / source_length
            };
            if let (Some(source_start), Some(target_start)) = (source_argument, target_argument) {
                if original_position >= source_start + argument_length {
                    translated_position = translated_position.max(target_start + argument_length);
                } else if original_position <= source_start {
                    translated_position = translated_position.min(target_start);
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
        placeholders
            .into_iter()
            .map(|mut placeholder| {
                if placeholder.position == Some(position) {
                    placeholder.name = selector.to_owned();
                }
                placeholder
            })
            .collect(),
        metadata,
    );
    crate::apple_writer::native_value(&scoped, value)
}

fn normalized_source(source: &str, substitutions: Option<&Value>) -> Result<String, ParseError> {
    let normalized =
        crate::apple::normalize_xcstrings_source(source, substitutions, &mut Vec::new())?;
    let conversions = crate::placeholders::printf_line_separators(source);
    Ok(if conversions.is_empty() {
        normalized
    } else {
        crate::apple::without_disabled_printf_conversions(&normalized, &conversions).0
    })
}

fn normalized_variant(source: &str) -> String {
    let normalized = crate::placeholders::normalize(source, &mut Vec::new(), Some("count"));
    let conversions = crate::placeholders::named_printf_line_separators(source, "count");
    if conversions.is_empty() {
        normalized
    } else {
        crate::apple::without_disabled_printf_conversions(&normalized, &conversions).0
    }
}

fn restore_arguments(value: &str, message: &Message) -> String {
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

fn argument_pattern() -> &'static Regex {
    static ARGUMENT: OnceLock<Regex> = OnceLock::new();
    ARGUMENT
        .get_or_init(|| Regex::new(r"\{([\p{L}\p{N}\p{M}\p{So}_.-]+)\}").expect("valid argument"))
}

fn substitution_marker_pattern() -> &'static Regex {
    static SUBSTITUTION_MARKER: OnceLock<Regex> = OnceLock::new();
    SUBSTITUTION_MARKER
        .get_or_init(|| Regex::new(r"%(?:[1-9][0-9]*\$)?#@").expect("valid substitution marker"))
}

fn invalid(code: &'static str, message: &str) -> ParseError {
    ParseError::new(code, message)
}
