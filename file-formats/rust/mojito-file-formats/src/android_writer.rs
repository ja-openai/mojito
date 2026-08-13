use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use regex::Regex;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::OnceLock;

const XLIFF_NAMESPACE: &str = "urn:oasis:names:tc:xliff:document:1.2";
const ANDROID_NAMESPACE: &str = "http://schemas.android.com/apk/res/android";
const TOOLS_NAMESPACE: &str = "http://schemas.android.com/tools";
const PLURAL_ORDER: [&str; 6] = ["zero", "one", "two", "few", "many", "other"];

pub(crate) fn write(catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != FileFormat::Android.id() {
        return Err(ParseError::new(
            "INVALID_SOURCE_FORMAT",
            "Android writer requires an Android canonical catalog",
        ));
    }
    validate_path_feature_flags(catalog)?;
    let mut output = String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources");
    if catalog
        .messages
        .values()
        .try_fold(false, |needed, message| {
            Ok::<_, ParseError>(
                needed
                    || feature_flag(message)?.is_some()
                    || message
                        .metadata
                        .as_ref()
                        .is_some_and(|metadata| metadata.contains_key("androidArrayFeatureFlags")),
            )
        })?
    {
        output.push_str(" xmlns:android=\"");
        output.push_str(ANDROID_NAMESPACE);
        output.push('"');
    }
    if catalog.messages.values().any(|message| {
        message
            .placeholders
            .iter()
            .flatten()
            .any(protected_placeholder)
            || message.metadata.as_ref().is_some_and(|metadata| {
                metadata.contains_key("androidProtectedPlaceholderOccurrences")
                    || metadata.contains_key("androidPluralProtectedPlaceholderOccurrences")
            })
    }) {
        output.push_str(" xmlns:xliff=\"");
        output.push_str(XLIFF_NAMESPACE);
        output.push('"');
    }
    if let Some(locale) = &catalog.locale {
        output.push_str(" xmlns:tools=\"");
        output.push_str(TOOLS_NAMESPACE);
        output.push_str("\" tools:locale=\"");
        output.push_str(&escape_attribute(locale));
        output.push('"');
    }
    output.push_str(">\n");
    crate::android_attributes::write(&mut output, catalog)?;

    let mut handled = HashSet::new();
    for (id, message) in &catalog.messages {
        if !handled.insert(id.clone()) {
            continue;
        }
        validate_runtime_identity(id, message)?;
        if let Some(array) = string_metadata(message, "arrayName") {
            write_array(
                &mut output,
                &catalog.messages,
                &mut handled,
                array,
                string_metadata(message, "androidProduct"),
                runtime_feature_flag(message),
            )?;
        } else if message.variants.is_some() {
            write_plural(&mut output, &resource_name(id, message)?, message)?;
        } else {
            write_string(&mut output, &resource_name(id, message)?, message)?;
        }
    }
    output.push_str("</resources>\n");
    Ok(output)
}

fn write_array(
    output: &mut String,
    messages: &BTreeMap<String, Message>,
    handled: &mut HashSet<String>,
    name: &str,
    product: Option<&str>,
    runtime_flag: Option<&str>,
) -> Result<(), ParseError> {
    let mut entries = BTreeMap::new();
    let mut references = BTreeMap::new();
    let mut primitives = BTreeMap::new();
    let mut item_flags = BTreeMap::new();
    let mut declared_references = false;
    let mut generic_array = None;
    let mut array_format = None;
    let mut bag_type = None;
    let mut array_feature_flag = None;
    for (id, message) in messages {
        if string_metadata(message, "arrayName") != Some(name)
            || string_metadata(message, "androidProduct") != product
            || runtime_feature_flag(message) != runtime_flag
        {
            continue;
        }
        let index = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("arrayIndex"))
            .and_then(serde_json::Value::as_u64)
            .and_then(|index| usize::try_from(index).ok())
            .ok_or_else(|| {
                ParseError::new(
                    "INVALID_ANDROID_ARRAY",
                    "Android array indexes must be nonnegative integers",
                )
            })?;
        if entries.insert(index, message).is_some() {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array indexes must be unique",
            ));
        }
        let current_generic = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("androidGenericArray"))
            .and_then(serde_json::Value::as_bool)
            == Some(true);
        let current_format = string_metadata(message, "androidArrayFormat");
        let current_bag_type = string_metadata(message, "androidBagType");
        let current_feature_flag = feature_flag(message)?;
        if current_bag_type.is_some_and(|kind| {
            !matches!(kind, "array" | "string-array") || current_generic != (kind == "array")
        }) || generic_array.is_some() && bag_type != current_bag_type
        {
            return Err(ParseError::new(
                "INVALID_ANDROID_BAG",
                "Android array entries must agree on their native bag type",
            ));
        }
        if current_format.is_some_and(|format| !current_generic || format != "string")
            || generic_array.is_some_and(|previous| {
                previous != current_generic || array_format != current_format
            })
        {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array entries must agree on their native type",
            ));
        }
        if generic_array.is_some() && array_feature_flag != current_feature_flag {
            return Err(ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Android array entries must agree on their resource flag",
            ));
        }
        generic_array = Some(current_generic);
        array_format = current_format;
        bag_type = current_bag_type;
        array_feature_flag = current_feature_flag;
        let declared = reference_metadata(message, "androidArrayReferences")?;
        let mut current = BTreeMap::new();
        for (key, value) in declared {
            let position = key.parse::<usize>().map_err(|_| {
                ParseError::new(
                    "INVALID_ANDROID_ARRAY",
                    "Android array reference positions must be nonnegative integers",
                )
            })?;
            if current.insert(position, value).is_some() {
                return Err(ParseError::new(
                    "INVALID_ANDROID_ARRAY",
                    "Android array reference positions must be unique",
                ));
            }
        }
        if !declared_references {
            references = current;
        } else if references != current {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array entries must agree on preserved references",
            ));
        }
        let current_primitives = primitive_metadata(message)?;
        let current_item_flags = array_feature_flags(message)?;
        if !current_primitives.is_empty() && !current_generic {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Only generic Android arrays can preserve primitives",
            ));
        }
        if !declared_references {
            primitives = current_primitives;
            item_flags.clone_from(&current_item_flags);
            declared_references = true;
        } else if primitives != current_primitives {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array entries must agree on preserved primitives",
            ));
        }
        if item_flags != current_item_flags {
            return Err(ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Android array entries must agree on item feature flags",
            ));
        }
        handled.insert(id.clone());
    }
    if let Some(kind) = bag_type {
        output.push_str("  <bag type=\"");
        output.push_str(kind);
        output.push_str("\" name=\"");
    } else {
        output.push_str(if generic_array == Some(true) {
            "  <array name=\""
        } else {
            "  <string-array name=\""
        });
    }
    output.push_str(&escape_attribute(name));
    output.push('"');
    append_feature_flag(output, array_feature_flag);
    if let Some(format) = array_format {
        output.push_str(" format=\"");
        output.push_str(format);
        output.push('"');
    }
    append_product(output, product);
    output.push_str(">\n");
    if references
        .keys()
        .any(|position| entries.contains_key(position) || primitives.contains_key(position))
    {
        return Err(ParseError::new(
            "INVALID_ANDROID_ARRAY",
            "Android array reference collides with a message",
        ));
    }
    if primitives
        .keys()
        .any(|position| entries.contains_key(position))
    {
        return Err(ParseError::new(
            "INVALID_ANDROID_ARRAY",
            "Android array primitive collides with a message",
        ));
    }
    let length = entries.len() + references.len() + primitives.len();
    if item_flags.keys().any(|index| *index >= length) {
        return Err(ParseError::new(
            "INVALID_ANDROID_FEATURE_FLAG",
            "Android item feature flags require real array positions",
        ));
    }
    for index in 0..length {
        let message = entries.get(&index);
        let reference = references.get(&index);
        let primitive = primitives.get(&index);
        if message.is_none() && reference.is_none() && primitive.is_none() {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array indexes must be contiguous",
            ));
        }
        if let Some(reference) = reference {
            output.push_str("    <item");
            append_feature_flag(output, item_flags.get(&index).map(String::as_str));
            output.push('>');
            output.push_str(&escape_xml_text(reference));
            output.push_str("</item>\n");
        } else if let Some(primitive) = primitive {
            output.push_str("    <item");
            append_feature_flag(output, item_flags.get(&index).map(String::as_str));
            output.push('>');
            output.push_str(&escape_xml_text(primitive));
            output.push_str("</item>\n");
        } else if let Some(message) = message {
            output.push_str("    <item");
            append_feature_flag(output, item_flags.get(&index).map(String::as_str));
            append_description(output, message);
            append_formatted(output, message);
            output.push('>');
            output.push_str(&render(message, &message.default_message)?);
            output.push_str("</item>\n");
        }
    }
    if bag_type.is_some() {
        output.push_str("  </bag>\n");
    } else {
        output.push_str(if generic_array == Some(true) {
            "  </array>\n"
        } else {
            "  </string-array>\n"
        });
    }
    Ok(())
}

fn primitive_metadata(message: &Message) -> Result<BTreeMap<usize, String>, ParseError> {
    let Some(values) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("androidArrayPrimitives"))
    else {
        return Ok(BTreeMap::new());
    };
    let values = values.as_object().ok_or_else(|| {
        ParseError::new(
            "INVALID_ANDROID_ARRAY",
            "Android array primitives must be an object",
        )
    })?;
    let mut result = BTreeMap::new();
    for (key, value) in values {
        let value = value.as_str().ok_or_else(|| {
            ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array primitive values must be strings",
            )
        })?;
        if !crate::android::is_native_primitive(value, "") {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Invalid Android array primitive metadata",
            ));
        }
        let index = key.parse::<usize>().map_err(|_| {
            ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Android array primitive positions must be nonnegative integers",
            )
        })?;
        if result.insert(index, value.to_owned()).is_some() {
            return Err(ParseError::new(
                "INVALID_ANDROID_ARRAY",
                "Duplicate Android array primitive position",
            ));
        }
    }
    Ok(result)
}

fn array_feature_flags(message: &Message) -> Result<BTreeMap<usize, String>, ParseError> {
    let Some(values) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("androidArrayFeatureFlags"))
    else {
        return Ok(BTreeMap::new());
    };
    let values = values.as_object().ok_or_else(|| {
        ParseError::new(
            "INVALID_ANDROID_FEATURE_FLAG",
            "Android array feature flags must be an object",
        )
    })?;
    let mut result = BTreeMap::new();
    for (position, value) in values {
        let flag = value
            .as_str()
            .filter(|flag| valid_feature_flag(flag))
            .ok_or_else(|| {
                ParseError::new(
                    "INVALID_ANDROID_FEATURE_FLAG",
                    "Invalid Android array feature-flag metadata",
                )
            })?;
        let index = position.parse::<usize>().map_err(|_| {
            ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Android feature-flag positions must be nonnegative integers",
            )
        })?;
        if result.insert(index, flag.to_owned()).is_some() {
            return Err(ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Duplicate Android array feature-flag position",
            ));
        }
    }
    Ok(result)
}

fn write_string(output: &mut String, name: &str, message: &Message) -> Result<(), ParseError> {
    if string_metadata(message, "androidBagType").is_some() {
        return Err(ParseError::new(
            "INVALID_ANDROID_BAG",
            "Scalar Android strings cannot carry bag metadata",
        ));
    }
    let generic = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("androidGenericString"))
        .and_then(serde_json::Value::as_bool)
        == Some(true);
    output.push_str(if generic {
        "  <item type=\"string\" name=\""
    } else {
        "  <string name=\""
    });
    output.push_str(&escape_attribute(name));
    output.push('"');
    append_feature_flag(output, feature_flag(message)?);
    if let Some(format) = string_metadata(message, "androidGenericFormat") {
        if !generic || format != "string" {
            return Err(ParseError::new(
                "INVALID_ANDROID_FORMAT",
                "Android generic format metadata must be string",
            ));
        }
        output.push_str(" format=\"");
        output.push_str(format);
        output.push('"');
    }
    append_product(output, string_metadata(message, "androidProduct"));
    append_description(output, message);
    append_formatted(output, message);
    output.push('>');
    output.push_str(&render(message, &message.default_message)?);
    output.push_str(if generic { "</item>\n" } else { "</string>\n" });
    Ok(())
}

fn write_plural(output: &mut String, name: &str, message: &Message) -> Result<(), ParseError> {
    let bag_type = string_metadata(message, "androidBagType");
    if bag_type.is_some_and(|kind| kind != "plurals") {
        return Err(ParseError::new(
            "INVALID_ANDROID_BAG",
            "Android plural messages require a plural bag type",
        ));
    }
    output.push_str(if bag_type.is_some() {
        "  <bag type=\"plurals\" name=\""
    } else {
        "  <plurals name=\""
    });
    output.push_str(&escape_attribute(name));
    output.push('"');
    append_feature_flag(output, feature_flag(message)?);
    append_product(output, string_metadata(message, "androidProduct"));
    append_description(output, message);
    output.push_str(">\n");
    let references = reference_metadata(message, "androidPluralReferences")?;
    let variants = message.variants.as_ref().expect("plural variants");
    if references.keys().any(|category| {
        !PLURAL_ORDER.contains(&category.as_str()) || variants.contains_key(category)
    }) {
        return Err(ParseError::new(
            "INVALID_ANDROID_REFERENCE",
            "Invalid or duplicate Android plural reference",
        ));
    }
    if references.contains_key("other") || !variants.contains_key("other") {
        return Err(ParseError::new(
            "MISSING_OTHER_VARIANT",
            "Android plural writer requires a translatable other",
        ));
    }
    for quantity in PLURAL_ORDER {
        let value = variants.get(quantity);
        let reference = references.get(quantity);
        if value.is_some() || reference.is_some() {
            output.push_str("    <item quantity=\"");
            output.push_str(quantity);
            output.push_str("\">");
            if let Some(reference) = reference {
                output.push_str(&escape_xml_text(reference));
            } else if let Some(value) = value {
                output.push_str(&render_variant(message, value, Some(quantity))?);
            }
            output.push_str("</item>\n");
        }
    }
    output.push_str(if bag_type.is_some() {
        "  </bag>\n"
    } else {
        "  </plurals>\n"
    });
    Ok(())
}

fn reference_metadata(
    message: &Message,
    name: &str,
) -> Result<BTreeMap<String, String>, ParseError> {
    let Some(metadata) = message
        .metadata
        .as_ref()
        .and_then(|values| values.get(name))
    else {
        return Ok(BTreeMap::new());
    };
    let values = metadata.as_object().ok_or_else(|| {
        ParseError::new(
            "INVALID_ANDROID_REFERENCE",
            "Android reference metadata must be an object",
        )
    })?;
    let mut references = BTreeMap::new();
    for (key, value) in values {
        let reference = value.as_str().ok_or_else(|| {
            ParseError::new(
                "INVALID_ANDROID_REFERENCE",
                "Android resource references must be strings",
            )
        })?;
        if !is_reference(reference) {
            return Err(ParseError::new(
                "INVALID_ANDROID_REFERENCE",
                "Invalid Android resource reference metadata",
            ));
        }
        references.insert(key.clone(), reference.to_owned());
    }
    Ok(references)
}

fn is_reference(value: &str) -> bool {
    crate::android_reference::is_reference(value)
}

fn render(message: &Message, canonical: &str) -> Result<String, ParseError> {
    render_variant(message, canonical, None)
}

pub(crate) fn render_variant(
    message: &Message,
    canonical: &str,
    quantity: Option<&str>,
) -> Result<String, ParseError> {
    let metadata = message.metadata.as_ref();
    let source = if metadata.and_then(|values| values.get("androidMarkupEscaping"))
        == Some(&serde_json::Value::String("icu-quoted-angle".into()))
    {
        canonical.replace("'<'", "<").replace("''", "'")
    } else {
        canonical.to_owned()
    };
    crate::android_annotation::validate(
        message,
        &canonical.replace("'<'", "<").replace("''", "'"),
        quantity,
    )?;
    crate::android_annotation::validate_styles(
        message,
        &canonical.replace("'<'", "<").replace("''", "'"),
        quantity,
    )?;
    crate::android_annotation::validate_paragraphs(
        message,
        &canonical.replace("'<'", "<").replace("''", "'"),
        quantity,
    )?;
    let literal_markup = metadata.and_then(|values| values.get("androidLiteralMarkup"))
        == Some(&serde_json::Value::Bool(true));
    let formatted = metadata.and_then(|values| values.get("formatted"))
        != Some(&serde_json::Value::Bool(false));
    let mut line_separators = line_separator_spelling(message, canonical, quantity, formatted)?;
    let mut percent = percent_spelling(message, &source, quantity, formatted)?;
    let mut placeholders: HashMap<String, Vec<&Placeholder>> = HashMap::new();
    for placeholder in message.placeholders.iter().flatten() {
        placeholders
            .entry(placeholder.name.clone())
            .or_default()
            .push(placeholder);
    }
    apply_plural_placeholder_examples(&mut placeholders, metadata, quantity, message)?;
    let protected_occurrences = apply_protected_placeholder_occurrences(
        &mut placeholders,
        metadata,
        quantity,
        message,
        &source,
    )?;
    let mut occurrences = HashMap::new();
    let mut output = String::new();
    let mut index = 0;
    let mut previous = 0;
    while index < source.len() {
        if !literal_markup && is_markup_start(&source, index) {
            if let Some(close) = markup_end(&source, index + 1) {
                append_text(
                    &mut output,
                    &source[previous..index],
                    &placeholders,
                    &mut occurrences,
                    &protected_occurrences,
                    formatted,
                    &mut (&mut line_separators, &mut percent),
                );
                output.push_str(&substitute_tag(
                    &source[index..=close],
                    &placeholders,
                    &mut occurrences,
                    &protected_occurrences,
                )?);
                index = close + 1;
                previous = index;
                continue;
            }
        }
        index += source[index..]
            .chars()
            .next()
            .expect("character")
            .len_utf8();
    }
    append_text(
        &mut output,
        &source[previous..],
        &placeholders,
        &mut occurrences,
        &protected_occurrences,
        formatted,
        &mut (&mut line_separators, &mut percent),
    );
    if output.is_empty() {
        output.push_str("\"\"");
    }
    Ok(output)
}

fn apply_plural_placeholder_examples(
    placeholders: &mut HashMap<String, Vec<&Placeholder>>,
    metadata: Option<&serde_json::Map<String, serde_json::Value>>,
    quantity: Option<&str>,
    message: &Message,
) -> Result<(), ParseError> {
    let Some(scoped) = metadata.and_then(|values| values.get("androidPluralPlaceholderExamples"))
    else {
        return Ok(());
    };
    let categories = scoped.as_object().ok_or_else(invalid_plural_placeholders)?;
    let quantity = quantity.ok_or_else(invalid_plural_placeholders)?;
    let variants = message
        .variants
        .as_ref()
        .ok_or_else(invalid_plural_placeholders)?;
    if categories.is_empty()
        || categories
            .keys()
            .any(|category| !variants.contains_key(category))
    {
        return Err(invalid_plural_placeholders());
    }
    let Some(value) = categories.get(quantity) else {
        return Ok(());
    };
    let names = value.as_object().ok_or_else(invalid_plural_placeholders)?;
    if names.is_empty() {
        return Err(invalid_plural_placeholders());
    }
    for (name, values) in names {
        let examples = values.as_array().ok_or_else(invalid_plural_placeholders)?;
        if examples.is_empty() {
            return Err(invalid_plural_placeholders());
        }
        let available = placeholders
            .get(name)
            .ok_or_else(invalid_plural_placeholders)?;
        let mut selected = Vec::with_capacity(examples.len());
        for value in examples {
            let example = if value.is_null() {
                None
            } else {
                Some(value.as_str().ok_or_else(invalid_plural_placeholders)?)
            };
            let placeholder = available
                .iter()
                .find(|placeholder| placeholder.example.as_deref() == example)
                .ok_or_else(invalid_plural_placeholders)?;
            selected.push(*placeholder);
        }
        placeholders.insert(name.clone(), selected);
    }
    Ok(())
}

fn invalid_plural_placeholders() -> ParseError {
    ParseError::new(
        "INVALID_PLACEHOLDER",
        "Invalid category-owned Android protected placeholder examples",
    )
}

fn apply_protected_placeholder_occurrences(
    placeholders: &mut HashMap<String, Vec<&Placeholder>>,
    metadata: Option<&serde_json::Map<String, serde_json::Value>>,
    quantity: Option<&str>,
    message: &Message,
    canonical: &str,
) -> Result<HashMap<String, Vec<serde_json::Value>>, ParseError> {
    let scalar = metadata.and_then(|values| values.get("androidProtectedPlaceholderOccurrences"));
    let plural =
        metadata.and_then(|values| values.get("androidPluralProtectedPlaceholderOccurrences"));
    if scalar.is_none() && plural.is_none() {
        return Ok(HashMap::new());
    }
    if scalar.is_some() && plural.is_some() || quantity.is_none() != plural.is_none() {
        return Err(invalid_protected_occurrences());
    }
    let scoped = if let Some(value) = plural {
        let categories = value
            .as_object()
            .ok_or_else(invalid_protected_occurrences)?;
        let variants = message
            .variants
            .as_ref()
            .ok_or_else(invalid_protected_occurrences)?;
        if categories.is_empty()
            || categories
                .keys()
                .any(|category| !variants.contains_key(category))
        {
            return Err(invalid_protected_occurrences());
        }
        let Some(value) = categories.get(quantity.expect("plural quantity")) else {
            return Ok(HashMap::new());
        };
        value
    } else {
        scalar.expect("scalar ownership")
    };
    let names = scoped
        .as_object()
        .ok_or_else(invalid_protected_occurrences)?;
    if names.is_empty() {
        return Err(invalid_protected_occurrences());
    }
    let mut result = HashMap::new();
    for (name, value) in names {
        let ownership = value.as_array().ok_or_else(invalid_protected_occurrences)?;
        let count = argument_pattern()
            .captures_iter(canonical)
            .filter(|capture| {
                capture
                    .get(1)
                    .is_some_and(|argument| argument.as_str() == name)
            })
            .count();
        let available = placeholders
            .get(name)
            .ok_or_else(invalid_protected_occurrences)?;
        if ownership.is_empty() || ownership.len() != count {
            return Err(invalid_protected_occurrences());
        }
        let mut selected = Vec::with_capacity(ownership.len());
        let mut protected = false;
        for occurrence in ownership {
            let example = if occurrence.is_null() {
                None
            } else {
                let section = occurrence
                    .as_object()
                    .ok_or_else(invalid_protected_occurrences)?;
                if section.keys().any(|key| key != "example") {
                    return Err(invalid_protected_occurrences());
                }
                protected = true;
                section
                    .get("example")
                    .map(|value| value.as_str().ok_or_else(invalid_protected_occurrences))
                    .transpose()?
            };
            let placeholder = available
                .iter()
                .find(|placeholder| placeholder.example.as_deref() == example)
                .ok_or_else(invalid_protected_occurrences)?;
            if placeholder
                .position
                .is_none_or(|position| name != &format!("arg{}", position - 1))
            {
                return Err(invalid_protected_occurrences());
            }
            selected.push(*placeholder);
        }
        if !protected {
            return Err(invalid_protected_occurrences());
        }
        placeholders.insert(name.clone(), selected);
        result.insert(name.clone(), ownership.clone());
    }
    Ok(result)
}

fn invalid_protected_occurrences() -> ParseError {
    ParseError::new(
        "INVALID_PLACEHOLDER",
        "Invalid Android protected placeholder occurrence ownership",
    )
}

fn append_text(
    output: &mut String,
    source: &str,
    placeholders: &HashMap<String, Vec<&Placeholder>>,
    occurrences: &mut HashMap<String, usize>,
    protected_occurrences: &HashMap<String, Vec<serde_json::Value>>,
    formatted: bool,
    spelling: &mut (&mut LineSeparatorSpelling, &mut PercentSpelling),
) {
    if source.is_empty() {
        return;
    }
    let mut previous = 0;
    let mut segment = String::new();
    for capture in argument_pattern().captures_iter(source) {
        let matched = capture.get(0).expect("argument match");
        let name = capture.get(1).expect("argument name").as_str();
        let Some(placeholder) = next_placeholder(placeholders, occurrences, name) else {
            continue;
        };
        append_escaped_text(
            &mut segment,
            &source[previous..matched.start()],
            formatted,
            spelling.0,
            spelling.1,
        );
        if protected_placeholder_occurrence(placeholder, name, occurrences, protected_occurrences) {
            append_quoted(output, &mut segment);
            output.push_str("<xliff:g id=\"");
            output.push_str(&escape_attribute(&placeholder.name));
            output.push('"');
            if let Some(example) = &placeholder.example {
                output.push_str(" example=\"");
                output.push_str(&escape_attribute(example));
                output.push('"');
            }
            output.push('>');
            output.push_str(&escape_xml_text(&placeholder.source));
            output.push_str("</xliff:g>");
        } else {
            segment.push_str(&escape_xml_text(&placeholder.source));
        }
        previous = matched.end();
    }
    append_escaped_text(
        &mut segment,
        &source[previous..],
        formatted,
        spelling.0,
        spelling.1,
    );
    append_quoted(output, &mut segment);
}

fn substitute_tag(
    tag: &str,
    placeholders: &HashMap<String, Vec<&Placeholder>>,
    occurrences: &mut HashMap<String, usize>,
    protected_occurrences: &HashMap<String, Vec<serde_json::Value>>,
) -> Result<String, ParseError> {
    let mut output = String::new();
    let mut previous = 0;
    for capture in argument_pattern().captures_iter(tag) {
        let matched = capture.get(0).expect("argument match");
        let name = capture.get(1).expect("argument name").as_str();
        if let Some(placeholder) = next_placeholder(placeholders, occurrences, name) {
            if protected_placeholder_occurrence(
                placeholder,
                name,
                occurrences,
                protected_occurrences,
            ) {
                return Err(ParseError::new(
                    "INVALID_ANDROID_MARKUP",
                    "Named XLIFF placeholders cannot appear in style attributes",
                ));
            }
            append_tag_text(&mut output, &tag[previous..matched.start()]);
            output.push_str(&escape_attribute(&placeholder.source));
            previous = matched.end();
        }
    }
    append_tag_text(&mut output, &tag[previous..]);
    Ok(output)
}

fn append_tag_text(output: &mut String, source: &str) {
    for character in source.chars() {
        match character {
            '\n' => output.push_str("&#10;"),
            '\r' => output.push_str("&#13;"),
            '\t' => output.push_str("&#9;"),
            _ => output.push(character),
        }
    }
}

fn next_placeholder<'a>(
    placeholders: &'a HashMap<String, Vec<&'a Placeholder>>,
    occurrences: &mut HashMap<String, usize>,
    name: &str,
) -> Option<&'a Placeholder> {
    let choices = placeholders.get(name)?;
    let occurrence = occurrences.entry(name.to_owned()).or_default();
    let selected = choices[(*occurrence).min(choices.len() - 1)];
    *occurrence += 1;
    Some(selected)
}

fn protected_placeholder(placeholder: &Placeholder) -> bool {
    placeholder.example.is_some()
        || placeholder
            .position
            .is_none_or(|position| placeholder.name != format!("arg{}", position - 1))
}

fn protected_placeholder_occurrence(
    placeholder: &Placeholder,
    name: &str,
    occurrences: &HashMap<String, usize>,
    protected_occurrences: &HashMap<String, Vec<serde_json::Value>>,
) -> bool {
    protected_occurrences.get(name).map_or_else(
        || protected_placeholder(placeholder),
        |ownership| !ownership[occurrences[name] - 1].is_null(),
    )
}

fn append_escaped_text(
    output: &mut String,
    value: &str,
    formatted: bool,
    line_separators: &mut LineSeparatorSpelling,
    percent: &mut PercentSpelling,
) {
    for character in value.chars() {
        match character {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\n' => output.push_str(if line_separators.next() { "%n" } else { "\\n" }),
            '\r' => output.push_str("\\u000D"),
            '\t' => output.push_str("\\t"),
            '%' if formatted => output.push_str(percent.next()),
            control if control < ' ' => {
                use std::fmt::Write;
                write!(output, "\\u{:04X}", control as u32).expect("write Android control");
            }
            _ => output.push(character),
        }
    }
}

fn line_separator_spelling(
    message: &Message,
    canonical: &str,
    quantity: Option<&str>,
    formatted: bool,
) -> Result<LineSeparatorSpelling, ParseError> {
    let metadata = message.metadata.as_ref();
    let enabled = metadata.and_then(|values| values.get("androidPrintfLineSeparator"));
    let singular = metadata.and_then(|values| values.get("androidPrintfLineSeparators"));
    let plural = metadata.and_then(|values| values.get("androidPluralPrintfLineSeparators"));
    if enabled.is_some_and(|value| value != &serde_json::Value::Bool(true))
        || !formatted && (enabled.is_some() || singular.is_some() || plural.is_some())
        || (singular.is_some() || plural.is_some())
            && enabled != Some(&serde_json::Value::Bool(true))
        || quantity.is_none() && plural.is_some()
        || quantity.is_some() && singular.is_some()
    {
        return Err(invalid_line_separator());
    }
    let mut selected = singular;
    let mut explicit = singular.is_some();
    if let Some(quantity) = quantity {
        if let Some(plural) = plural {
            let variants = plural.as_object().ok_or_else(invalid_line_separator)?;
            if variants.is_empty() {
                return Err(invalid_line_separator());
            }
            for (category, positions) in variants {
                let variant = message
                    .variants
                    .as_ref()
                    .and_then(|variants| variants.get(category))
                    .ok_or_else(invalid_line_separator)?;
                validate_line_separator_positions(positions, variant)?;
            }
            selected = variants.get(quantity);
            explicit = true;
        }
    }
    Ok(LineSeparatorSpelling {
        enabled: enabled == Some(&serde_json::Value::Bool(true)),
        explicit,
        selected: selected
            .map(|positions| validate_line_separator_positions(positions, canonical))
            .transpose()?
            .unwrap_or_default(),
        occurrence: 0,
    })
}

fn validate_line_separator_positions(
    value: &serde_json::Value,
    canonical: &str,
) -> Result<Vec<usize>, ParseError> {
    let values = value.as_array().ok_or_else(invalid_line_separator)?;
    if values.is_empty() {
        return Err(invalid_line_separator());
    }
    let count = visible_character_count(canonical, '\n');
    let mut selected = Vec::new();
    for value in values {
        let position = value
            .as_u64()
            .and_then(|number| usize::try_from(number).ok())
            .ok_or_else(invalid_line_separator)?;
        if position >= count
            || selected
                .last()
                .is_some_and(|previous| *previous >= position)
        {
            return Err(invalid_line_separator());
        }
        selected.push(position);
    }
    Ok(selected)
}

fn invalid_line_separator() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_LINE_SEPARATOR",
        "Invalid or unsafe Android line-separator metadata",
    )
}

struct LineSeparatorSpelling {
    enabled: bool,
    explicit: bool,
    selected: Vec<usize>,
    occurrence: usize,
}

impl LineSeparatorSpelling {
    fn next(&mut self) -> bool {
        if self.explicit {
            let occurrence = self.occurrence;
            self.occurrence += 1;
            self.selected.contains(&occurrence)
        } else {
            self.enabled
        }
    }
}

fn percent_spelling(
    message: &Message,
    canonical: &str,
    quantity: Option<&str>,
    formatted: bool,
) -> Result<PercentSpelling, ParseError> {
    let metadata = message.metadata.as_ref();
    let singular = metadata.and_then(|values| values.get("androidRawPercentOccurrences"));
    let plural = metadata.and_then(|values| values.get("androidPluralRawPercentOccurrences"));
    if !formatted && (singular.is_some() || plural.is_some())
        || quantity.is_none() && plural.is_some()
        || quantity.is_some() && singular.is_some()
    {
        return Err(invalid_percent());
    }
    let mut selected = singular;
    if let Some(quantity) = quantity {
        if let Some(plural) = plural {
            let values = plural.as_object().ok_or_else(invalid_percent)?;
            if values.is_empty() {
                return Err(invalid_percent());
            }
            for (category, positions) in values {
                let variant = message
                    .variants
                    .as_ref()
                    .and_then(|variants| variants.get(category))
                    .ok_or_else(invalid_percent)?;
                validate_percent_positions(positions, variant)?;
            }
            selected = values.get(quantity);
        }
    }
    Ok(PercentSpelling {
        raw: selected
            .map(|values| validate_percent_positions(values, canonical))
            .transpose()?
            .unwrap_or_default(),
        occurrence: 0,
        formatted,
    })
}

fn validate_percent_positions(
    value: &serde_json::Value,
    canonical: &str,
) -> Result<Vec<usize>, ParseError> {
    let values = value.as_array().ok_or_else(invalid_percent)?;
    if values.is_empty() {
        return Err(invalid_percent());
    }
    let count = visible_character_count(canonical, '%');
    let mut positions = Vec::new();
    for value in values {
        let position = value
            .as_u64()
            .and_then(|number| usize::try_from(number).ok())
            .ok_or_else(invalid_percent)?;
        if position >= count
            || positions
                .last()
                .is_some_and(|previous| *previous >= position)
        {
            return Err(invalid_percent());
        }
        positions.push(position);
    }
    Ok(positions)
}

fn visible_character_count(source: &str, selected: char) -> usize {
    let source = source.replace("'<'", "<").replace("''", "'");
    let mut count = 0;
    let mut index = 0;
    while index < source.len() {
        if is_markup_start(&source, index) {
            if let Some(close) = markup_end(&source, index + 1) {
                index = close + 1;
                continue;
            }
        }
        let character = source[index..].chars().next().expect("character");
        if character == selected {
            count += 1;
        }
        index += character.len_utf8();
    }
    count
}

fn invalid_percent() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_PERCENT",
        "Invalid or unsafe Android literal percent metadata",
    )
}

struct PercentSpelling {
    raw: Vec<usize>,
    occurrence: usize,
    formatted: bool,
}

impl PercentSpelling {
    fn next(&mut self) -> &'static str {
        if !self.formatted {
            "%"
        } else {
            let current = self.occurrence;
            self.occurrence += 1;
            if self.raw.contains(&current) {
                "%"
            } else {
                "%%"
            }
        }
    }
}

fn append_quoted(output: &mut String, segment: &mut String) {
    if !segment.is_empty() {
        output.push('"');
        output.push_str(segment);
        output.push('"');
        segment.clear();
    }
}

fn is_markup_start(input: &str, index: usize) -> bool {
    let bytes = input.as_bytes();
    bytes[index] == b'<'
        && bytes
            .get(index + 1)
            .is_some_and(|next| next.is_ascii_alphabetic() || *next == b'/')
}

fn markup_end(input: &str, start: usize) -> Option<usize> {
    let mut quoted = false;
    for (offset, character) in input[start..].char_indices() {
        match character {
            '"' => quoted = !quoted,
            '>' if !quoted => return Some(start + offset),
            _ => {}
        }
    }
    None
}

fn append_description(output: &mut String, message: &Message) {
    if let Some(description) = &message.description {
        output.push_str(" description=\"");
        output.push_str(&escape_attribute(description));
        output.push('"');
    }
}

fn append_formatted(output: &mut String, message: &Message) {
    if message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("formatted"))
        == Some(&serde_json::Value::Bool(false))
    {
        output.push_str(" formatted=\"false\"");
    }
}

fn append_product(output: &mut String, product: Option<&str>) {
    if let Some(product) = product {
        output.push_str(" product=\"");
        output.push_str(&escape_attribute(product));
        output.push('"');
    }
}

fn append_feature_flag(output: &mut String, flag: Option<&str>) {
    if let Some(flag) = flag {
        output.push_str(" android:featureFlag=\"");
        output.push_str(&escape_attribute(flag));
        output.push('"');
    }
}

fn feature_flag(message: &Message) -> Result<Option<&str>, ParseError> {
    let Some(value) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("androidFeatureFlag"))
    else {
        return Ok(None);
    };
    value
        .as_str()
        .filter(|flag| valid_feature_flag(flag))
        .map(Some)
        .ok_or_else(|| {
            ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Invalid Android resource feature-flag metadata",
            )
        })
}

fn validate_path_feature_flags(catalog: &Catalog) -> Result<(), ParseError> {
    let mut expected = None;
    for message in catalog.messages.values() {
        let Some(metadata) = message.metadata.as_ref() else {
            continue;
        };
        let Some(value) = metadata.get("androidPathFeatureFlag") else {
            continue;
        };
        let Some(flag) = value.as_str().filter(|flag| valid_feature_flag(flag)) else {
            return Err(invalid_path_feature_flag());
        };
        let Some(path) = metadata
            .get("androidResourcePath")
            .and_then(serde_json::Value::as_str)
        else {
            return Err(invalid_path_feature_flag());
        };
        if !path.split('/').any(|part| part == format!("flag({flag})")) {
            return Err(invalid_path_feature_flag());
        }
        if metadata.contains_key("androidFeatureFlag")
            || expected.is_some_and(|previous| previous != flag)
        {
            return Err(ParseError::new(
                "CONFLICTING_ANDROID_FEATURE_FLAG",
                "Android feature flags are not allowed in both the resource path and file",
            ));
        }
        expected = Some(flag);
    }
    Ok(())
}

fn invalid_path_feature_flag() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_PATH_FEATURE_FLAG",
        "Invalid Android path feature-flag metadata",
    )
}

fn valid_feature_flag(value: &str) -> bool {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN
        .get_or_init(|| Regex::new(r"^!?[A-Za-z_][A-Za-z0-9_.-]*$").expect("valid feature flag"))
        .is_match(value)
}

fn resource_name(id: &str, message: &Message) -> Result<String, ParseError> {
    let id = match runtime_feature_flag(message) {
        Some(flag) => id.strip_suffix(&format!("@flag={flag}")).ok_or_else(|| {
            ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Android runtime flag must match its canonical ID",
            )
        })?,
        None => id,
    };
    Ok(match string_metadata(message, "androidProduct") {
        Some(product) if product != "default" => id
            .strip_suffix(&format!("@product={product}"))
            .unwrap_or(id)
            .to_owned(),
        _ => id.to_owned(),
    })
}

fn validate_runtime_identity(id: &str, message: &Message) -> Result<(), ParseError> {
    let Some(flag) = runtime_feature_flag(message) else {
        return Ok(());
    };
    let suffix = match message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("arrayName"))
    {
        Some(_) => format!(
            "@flag={flag}[{}]",
            message
                .metadata
                .as_ref()
                .and_then(|metadata| metadata.get("arrayIndex"))
                .map_or_else(String::new, serde_json::Value::to_string)
        ),
        None => format!("@flag={flag}"),
    };
    if !id.ends_with(&suffix) {
        return Err(ParseError::new(
            "INVALID_ANDROID_FEATURE_FLAG",
            "Android runtime flag must match its canonical ID",
        ));
    }
    Ok(())
}

fn runtime_feature_flag(message: &Message) -> Option<&str> {
    if string_metadata(message, "androidFeatureFlagMode") == Some("read_write") {
        string_metadata(message, "androidFeatureFlag")
    } else if string_metadata(message, "androidPathFeatureFlagMode") == Some("read_write") {
        string_metadata(message, "androidPathFeatureFlag")
    } else {
        None
    }
}

fn string_metadata<'a>(message: &'a Message, field: &str) -> Option<&'a str> {
    message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get(field))
        .and_then(serde_json::Value::as_str)
}

fn escape_attribute(value: &str) -> String {
    escape_xml_text(value)
        .replace('"', "&quot;")
        .replace('\t', "&#9;")
        .replace('\n', "&#10;")
}

fn escape_xml_text(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('\r', "&#13;")
}

fn argument_pattern() -> &'static Regex {
    static ARGUMENT: OnceLock<Regex> = OnceLock::new();
    ARGUMENT.get_or_init(|| Regex::new(r"\{([\p{L}\p{N}\p{M}\p{So}_]+)\}").expect("valid argument"))
}
