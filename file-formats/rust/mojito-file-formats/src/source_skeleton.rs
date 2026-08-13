use crate::model::{Catalog, FileFormat, Message, ParseError};
use regex::Regex;
use serde::Serialize;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::fmt::Write;
use std::sync::OnceLock;

/// Versioned source-preserving sidecar; the canonical FormatJS catalog remains independent.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceSkeleton {
    pub schema_version: u8,
    pub source_format: &'static str,
    pub encoding: String,
    pub source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_resource_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_feature_flags: Option<Vec<crate::AndroidFeatureFlag>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub apple_target_locale: Option<String>,
    pub slots: Vec<SourceSlot>,
}

/// Half-open original-byte ownership of one value, variant, or explicit-null Xcode locale.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct SourceSlot {
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub selector: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub variant: Option<String>,
    pub start: usize,
    pub end: usize,
    #[serde(skip_serializing_if = "Option::is_none", rename = "appleObjectIndex")]
    pub apple_object_index: Option<usize>,
}

impl SourceSlot {
    pub(crate) fn key(&self) -> String {
        if let (Some(selector), Some(variant)) = (&self.selector, &self.variant) {
            return format!("{}#{selector}#{variant}", self.id);
        }
        self.variant.as_ref().map_or_else(
            || self.id.clone(),
            |variant| format!("{}#{variant}", self.id),
        )
    }
}

pub(crate) fn extract(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
) -> Result<SourceSkeleton, ParseError> {
    match format {
        FileFormat::Android => extract_android(bytes),
        FileFormat::AppleStrings | FileFormat::AppleStringsdict
            if crate::apple_binary::matches(bytes) =>
        {
            crate::apple_binary_skeleton::extract(format, bytes)
        }
        FileFormat::AppleStrings => extract_apple(bytes),
        FileFormat::AppleStringsdict => extract_stringsdict(bytes),
        FileFormat::AppleXcstrings => extract_xcstrings(bytes),
        FileFormat::GettextPo => extract_gettext(bytes),
        FileFormat::JavaProperties => extract_properties(bytes, properties_encoding),
        FileFormat::Yaml => crate::yaml::extract(bytes),
        FileFormat::JavaScript | FileFormat::TypeScript => {
            crate::javascript::extract(format, bytes)
        }
        _ => Err(error(
            "UNSUPPORTED_SKELETON_FORMAT",
            "Source skeletons are not available for this resource format",
        )),
    }
}

fn extract_android(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    extract_android_with_context(bytes, None, &[])
}

pub(crate) fn extract_android_with_context(
    bytes: &[u8],
    resource_path: Option<&str>,
    feature_flags: &[crate::AndroidFeatureFlag],
) -> Result<SourceSkeleton, ParseError> {
    extract_android_with_catalog(bytes, resource_path, feature_flags, None)
}

pub(crate) fn extract_android_with_catalog(
    bytes: &[u8],
    resource_path: Option<&str>,
    feature_flags: &[crate::AndroidFeatureFlag],
    resolved_catalog: Option<&Catalog>,
) -> Result<SourceSkeleton, ParseError> {
    let declared = crate::xml_encoding(FileFormat::Android, bytes)?;
    let encoding = Encoding::detect_declared(bytes, declared);
    let source = crate::decode(bytes, declared)?;
    let catalog = resolved_catalog.cloned().map_or_else(
        || {
            if feature_flags.is_empty() && resource_path.is_none() {
                crate::parse(FileFormat::Android, bytes)
            } else {
                crate::parse_with_android_feature_flag_definitions(
                    FileFormat::Android,
                    bytes,
                    None,
                    resource_path,
                    feature_flags,
                    None,
                )
            }
        },
        Ok,
    )?;
    let path_runtime_flag = resource_path
        .map(crate::android_configuration::Configuration::parse)
        .transpose()?
        .and_then(|configuration| configuration.path_feature_flag)
        .and_then(|expression| {
            let name = expression.strip_prefix('!').unwrap_or(&expression);
            feature_flags
                .iter()
                .find(|flag| flag.name == name && !flag.read_only)
                .map(|_| expression)
        });
    let mut stack: Vec<Element> = Vec::new();
    let mut slots = Vec::new();
    let mut assigned = HashSet::new();
    let mut position = 0;
    while position < source.len() {
        if source.as_bytes()[position] != b'<' {
            position += source[position..].chars().next().unwrap().len_utf8();
            continue;
        }
        if source[position..].starts_with("<!--") {
            position = skip(&source, position, "-->")?;
            continue;
        }
        if source[position..].starts_with("<![CDATA[") {
            position = skip(&source, position, "]]>")?;
            continue;
        }
        if source[position..].starts_with("<?") {
            position = skip(&source, position, "?>")?;
            continue;
        }
        let end = tag_end(&source, position)?;
        let mut token = source[position + 1..end].trim();
        if token.starts_with('!') {
            return Err(error(
                "UNSUPPORTED_SKELETON_MARKUP",
                "Unsupported Android XML declaration",
            ));
        }
        if token.starts_with('/') {
            let current = stack
                .pop()
                .ok_or_else(|| error("INVALID_SKELETON", "Unbalanced Android XML source"))?;
            add_slot(
                &catalog,
                &current,
                stack.last(),
                position,
                &source,
                encoding,
                &mut slots,
                &mut assigned,
            )?;
        } else {
            let empty = token.ends_with('/');
            if empty {
                token = token[..token.len() - 1].trim();
            }
            let name = token.split_whitespace().next().unwrap();
            let attributes = attributes(&token[name.len()..]);
            let mut parent = stack.last_mut();
            let array_item = parent
                .as_ref()
                .is_some_and(|parent| is_array(parent) && name == "item");
            let mut namespaces = parent
                .as_ref()
                .map_or_else(HashMap::new, |parent| parent.namespaces.clone());
            for (attribute, value) in &attributes {
                if let Some(prefix) = attribute.strip_prefix("xmlns:") {
                    namespaces.insert(prefix.to_owned(), value.clone());
                }
            }
            let source_condition = name == local_name(name)
                && parent.as_ref().is_some_and(|parent| {
                    parent.name == "resources" || is_array(parent) && name == "item"
                });
            let (mut runtime_flag, enabled) = if source_condition {
                android_feature_state(&attributes, &namespaces, feature_flags)?
            } else {
                (None, true)
            };
            if runtime_flag.is_none()
                && parent
                    .as_ref()
                    .is_some_and(|parent| parent.name == "resources")
            {
                runtime_flag.clone_from(&path_runtime_flag);
            }
            let array_index = if array_item {
                let parent = parent.as_mut().unwrap();
                let result = parent.next_index;
                if enabled {
                    parent.next_index += 1;
                }
                Some(result)
            } else {
                None
            };
            let current = Element {
                name: name.to_owned(),
                attributes,
                body_start: end + 1,
                array_index,
                namespaces,
                runtime_flag,
                enabled,
                next_index: 0,
            };
            if empty {
                if identity(&current, stack.last())
                    .as_ref()
                    .is_some_and(|id| catalog.messages.contains_key(id))
                {
                    add_empty_slot(
                        &catalog,
                        &current,
                        stack.last(),
                        position,
                        end,
                        &source,
                        encoding,
                        &mut slots,
                        &mut assigned,
                    )?;
                }
            } else {
                stack.push(current);
            }
        }
        position = end + 1;
    }
    for (id, descriptor) in &catalog.messages {
        if let Some(variants) = &descriptor.variants {
            for variant in variants.keys() {
                if !assigned.contains(&format!("{id}#{variant}")) {
                    return Err(error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Missing plural Android source slot",
                    ));
                }
            }
        } else if !assigned.contains(id) {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Missing scalar Android source slot",
            ));
        }
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::Android.id(),
        encoding: encoding.name().to_owned(),
        source,
        android_resource_path: resource_path.map(str::to_owned),
        android_feature_flags: (!feature_flags.is_empty()).then(|| feature_flags.to_vec()),
        apple_target_locale: None,
        slots,
    })
}

pub(crate) fn render(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    if skeleton.schema_version != 1 {
        return Err(error("INVALID_SKELETON", "Unsupported source skeleton"));
    }
    if skeleton.encoding == "BINARY_PLIST" {
        return crate::apple_binary_skeleton::render(skeleton, translations);
    }
    match skeleton.source_format {
        "android" => render_android(skeleton, translations),
        "apple_strings" => render_apple(skeleton, translations),
        "apple_stringsdict" => render_stringsdict(skeleton, translations),
        "apple_xcstrings" => render_xcstrings(skeleton, translations),
        "gettext_po" => render_gettext(skeleton, translations),
        "java_properties" => render_properties(skeleton, translations),
        "yaml" => crate::yaml::render(skeleton, translations),
        "javascript" | "typescript" => crate::javascript::render(skeleton, translations),
        _ => Err(error(
            "UNSUPPORTED_SKELETON_FORMAT",
            "Unsupported source-preserving skeleton format",
        )),
    }
}

fn render_android(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    render_android_with_catalog(skeleton, translations, None)
}

pub(crate) fn render_android_with_catalog(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
    resolved_catalog: Option<&Catalog>,
) -> Result<Vec<u8>, ParseError> {
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let catalog = resolved_catalog.cloned().map_or_else(
        || {
            if skeleton.android_resource_path.is_some() || skeleton.android_feature_flags.is_some()
            {
                crate::parse_with_android_feature_flag_definitions(
                    FileFormat::Android,
                    &original,
                    None,
                    skeleton.android_resource_path.as_deref(),
                    skeleton
                        .android_feature_flags
                        .as_deref()
                        .unwrap_or_default(),
                    None,
                )
            } else {
                crate::parse(FileFormat::Android, &original)
            }
        },
        Ok,
    )?;
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if !known.insert(slot.key()) {
            return Err(error("INVALID_SKELETON", "Duplicate source slot"));
        }
    }
    if translations.keys().any(|key| !known.contains(key)) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no original source slot",
        ));
    }
    let mut result = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error("INVALID_SKELETON", "Invalid source slot range"));
        }
        result.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.key()) {
            let descriptor = catalog
                .messages
                .get(&slot.id)
                .ok_or_else(|| error("INVALID_SKELETON", "Missing canonical slot descriptor"))?;
            if slot.variant.as_ref().is_some_and(|variant| {
                !descriptor
                    .variants
                    .as_ref()
                    .is_some_and(|variants| variants.contains_key(variant))
            }) {
                return Err(error(
                    "INVALID_SKELETON",
                    "Missing canonical plural category",
                ));
            }
            let source = encoding.decode(&original[slot.start..slot.end])?;
            let translated = crate::android_writer::render_variant(
                descriptor,
                translation,
                slot.variant.as_deref(),
            )?;
            let preserved = if source.starts_with('/') && source.ends_with('>') {
                let prefix = encoding.decode(&original[encoding.bom_length()..slot.start])?;
                let start = prefix.rfind('<').ok_or_else(|| {
                    error(
                        "INVALID_SKELETON",
                        "Self-closing Android slot has no opening element",
                    )
                })?;
                let name = prefix[start + 1..]
                    .split_whitespace()
                    .next()
                    .ok_or_else(|| error("INVALID_SKELETON", "Missing Android element name"))?;
                Ok(format!(">{translated}</{name}>"))
            } else if resolved_catalog.is_some() && macro_reference(&source) {
                let original_value = slot
                    .variant
                    .as_ref()
                    .map_or(descriptor.default_message.as_str(), |variant| {
                        &descriptor.variants.as_ref().unwrap()[variant]
                    });
                let expanded = crate::android_writer::render_variant(
                    descriptor,
                    original_value,
                    slot.variant.as_deref(),
                )?;
                preserve_markup(&expanded, &translated)
            } else if source.contains("<!--")
                || source.contains("<![CDATA[")
                || source.contains("<?")
            {
                if tags_with_decorations(&source, true)?.is_empty() {
                    preserve_decorations(&source, &translated)
                } else {
                    preserve_markup_with_decorations(&source, &translated, true)
                }
            } else {
                preserve_markup(&source, &translated)
            }
            .map_err(|failure| {
                ParseError::new(failure.code, format!("{}: {}", slot.key(), failure.message))
            })?;
            result.extend(encoding.encode_without_bom(&preserved));
        } else {
            result.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    result.extend_from_slice(&original[previous..]);
    Ok(result)
}

pub(crate) fn original_source_bytes(skeleton: &SourceSkeleton) -> Result<Vec<u8>, ParseError> {
    Ok(Encoding::named(&skeleton.encoding)?.encode(&skeleton.source))
}

fn macro_reference(source: &str) -> bool {
    let value = source.trim();
    value.starts_with('@')
        && !value.starts_with("@@@")
        && value.find("macro/").is_some_and(|index| index > 0)
        && !value.contains('<')
}

fn extract_apple(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    if crate::apple_binary::matches(bytes) {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Binary Apple property lists require binary slots",
        ));
    }
    let declared = crate::xml_encoding(FileFormat::AppleStrings, bytes)?;
    let source = crate::decode(bytes, declared)?;
    let encoding = Encoding::detect_declared(bytes, declared);
    let catalog = crate::parse(FileFormat::AppleStrings, bytes)?;
    let mut scanner = AppleScanner {
        source: &source,
        encoding,
        catalog: &catalog,
        index: 0,
        slots: Vec::new(),
    };
    if source.trim_start().starts_with('<') {
        scanner.scan_xml()?;
    } else {
        scanner.scan()?;
    }
    if scanner.slots.len() != catalog.messages.len() {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Missing Foundation source-value slot",
        ));
    }
    let slots = scanner.slots;
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::AppleStrings.id(),
        encoding: encoding.name().to_owned(),
        source,
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: None,
        slots,
    })
}

fn render_apple(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let catalog = crate::parse(FileFormat::AppleStrings, &original)?;
    let xml = skeleton.source.trim_start().starts_with('<');
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if slot.variant.is_some() || !known.insert(slot.id.as_str()) {
            return Err(error("INVALID_SKELETON", "Invalid Apple source slot"));
        }
    }
    if translations.keys().any(|id| !known.contains(id.as_str())) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no original Apple source slot",
        ));
    }
    let mut result = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error("INVALID_SKELETON", "Invalid Apple source slot range"));
        }
        result.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.id) {
            let descriptor = catalog
                .messages
                .get(&slot.id)
                .ok_or_else(|| error("INVALID_SKELETON", "Missing Apple slot descriptor"))?;
            let value = if xml {
                let native = crate::apple_writer::native_value(descriptor, translation);
                let body = encoding.decode(&original[slot.start..slot.end])?;
                if body.starts_with('/') && body.ends_with('>') {
                    let prefix = encoding.decode(&original[encoding.bom_length()..slot.start])?;
                    let opening = prefix.rfind('<').ok_or_else(|| {
                        error(
                            "INVALID_SKELETON",
                            "Self-closing Apple string has no opening tag",
                        )
                    })?;
                    let name = prefix[opening + 1..]
                        .split_whitespace()
                        .next()
                        .ok_or_else(|| error("INVALID_SKELETON", "Missing Apple XML tag name"))?;
                    format!(">{}</{name}>", apple_xml_text(&native))
                } else if body.contains("<![CDATA[") {
                    preserve_apple_cdata(&body, &native)?
                } else {
                    apple_xml_text(&native)
                }
            } else {
                let rendered = crate::apple_writer::render_source(descriptor, translation);
                let before = encoding.decode(&original[encoding.bom_length()..slot.start])?;
                let after = encoding.decode(&original[slot.end..])?;
                let opening = before.chars().next_back();
                let closing = after.chars().next();
                if matches!(opening, Some('\'' | '"')) && opening == closing {
                    if opening == Some('\'') {
                        single_quoted(&rendered)
                    } else {
                        rendered
                    }
                } else if slot.start == slot.end {
                    format!(" = \"{rendered}\"")
                } else {
                    format!("\"{rendered}\"")
                }
            };
            result.extend(encoding.encode_without_bom(&value));
        } else {
            result.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    result.extend_from_slice(&original[previous..]);
    Ok(result)
}

fn extract_stringsdict(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    extract_stringsdict_with_variations(bytes, false)
}

pub(crate) fn extract_stringsdict_variations(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    extract_stringsdict_with_variations(bytes, true)
}

fn extract_stringsdict_with_variations(
    bytes: &[u8],
    all_variations: bool,
) -> Result<SourceSkeleton, ParseError> {
    if crate::apple_binary::matches(bytes) {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Binary strings dictionaries require binary value slots",
        ));
    }
    let declared = crate::xml_encoding(FileFormat::AppleStringsdict, bytes)?;
    let encoding = Encoding::detect_declared(bytes, declared);
    let source = crate::decode(bytes, declared)?;
    let catalog = crate::parse(FileFormat::AppleStringsdict, bytes)?;
    let expected = stringsdict_expected_paths_with_variations(&catalog, all_variations)?;
    let mut scanner = StringsdictScanner {
        source: &source,
        encoding,
        expected: &expected,
        slots: Vec::new(),
    };
    scanner.scan()?;
    if scanner.slots.len() != expected.len() {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Missing owned Foundation stringsdict value",
        ));
    }
    let slots = scanner.slots;
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::AppleStringsdict.id(),
        encoding: encoding.name().to_owned(),
        source,
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: None,
        slots,
    })
}

fn render_stringsdict(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let catalog = crate::parse(FileFormat::AppleStringsdict, &original)?;
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if !known.insert(slot.key()) {
            return Err(error(
                "INVALID_SKELETON",
                "Duplicated Foundation stringsdict value slot",
            ));
        }
    }
    if translations.keys().any(|key| !known.contains(key)) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no Foundation stringsdict value",
        ));
    }
    let mut result = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error(
                "INVALID_SKELETON",
                "Overlapping Foundation stringsdict value slots",
            ));
        }
        result.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.key()) {
            let message = catalog.messages.get(&slot.id).ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Missing canonical Foundation stringsdict descriptor",
                )
            })?;
            let known_category = if let Some(selector) = &slot.selector {
                if let Some(device) = selector.strip_prefix("@device=") {
                    slot.variant.as_ref().is_some_and(|variant| {
                        if let Some(widths) =
                            stringsdict_device_branches(message, "deviceWidthVariants")
                                .and_then(|devices| devices.get(device))
                                .and_then(|branch| branch.get("NSStringVariableWidthRuleType"))
                                .and_then(serde_json::Value::as_object)
                        {
                            widths
                                .get(variant)
                                .is_some_and(serde_json::Value::is_string)
                        } else {
                            stringsdict_device_branches(message, "devicePluralVariants")
                                .and_then(|devices| devices.get(device))
                                .and_then(serde_json::Value::as_object)
                                .is_some_and(|branch| {
                                    branch.values().any(|rule| {
                                        rule.get("NSStringFormatSpecTypeKey")
                                            .and_then(serde_json::Value::as_str)
                                            == Some("NSStringPluralRuleType")
                                            && rule
                                                .get(variant)
                                                .is_some_and(serde_json::Value::is_string)
                                    })
                                })
                        }
                    })
                } else if matches!(selector.as_str(), "@device" | "@width") {
                    let kind = if selector == "@device" {
                        "deviceVariants"
                    } else {
                        "widthVariants"
                    };
                    slot.variant.as_ref().is_some_and(|variant| {
                        stringsdict_device_branches(message, kind)
                            .and_then(serde_json::Value::as_object)
                            .is_some_and(|branches| {
                                branches
                                    .get(variant)
                                    .is_some_and(serde_json::Value::is_string)
                            })
                    })
                } else {
                    slot.variant.as_ref().is_some_and(|variant| {
                        message
                            .metadata
                            .as_ref()
                            .and_then(|metadata| metadata.get("applePluralRules"))
                            .and_then(|rules| rules.get(selector))
                            .and_then(|definition| definition.get("variants"))
                            .and_then(serde_json::Value::as_object)
                            .is_some_and(|categories| categories.contains_key(variant))
                    })
                }
            } else {
                slot.variant.as_ref().is_none_or(|variant| {
                    message
                        .variants
                        .as_ref()
                        .is_some_and(|variants| variants.contains_key(variant))
                })
            };
            if !known_category {
                return Err(error(
                    "INVALID_SKELETON",
                    "Missing Foundation stringsdict plural category",
                ));
            }
            let selector = slot.selector.as_deref().or_else(|| {
                message
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.get("pluralVariable"))
                    .and_then(serde_json::Value::as_str)
            });
            let native = match (selector, slot.variant.as_deref()) {
                (Some(selector), Some(category)) if selector.starts_with("@device=") => {
                    let device = selector.trim_start_matches("@device=");
                    let scoped = if stringsdict_device_is_width(message, device) {
                        stringsdict_device_width_message(message, device, category)?
                    } else {
                        stringsdict_device_plural_message(message, device, category)?
                    };
                    crate::apple_stringsdict_writer::restore(translation, &scoped)
                }
                (Some(kind @ ("@device" | "@width")), Some(branch)) => {
                    let scoped = stringsdict_variation_message(message, kind, branch)?;
                    crate::apple_stringsdict_writer::restore(translation, &scoped)
                }
                (Some(selector), Some(category)) => {
                    crate::apple_stringsdict_writer::restore_scoped(
                        translation,
                        message,
                        selector,
                        category,
                    )
                }
                _ => crate::apple_stringsdict_writer::restore(translation, message),
            };
            let body = encoding.decode(&original[slot.start..slot.end])?;
            let value = if body.starts_with('/') && body.ends_with('>') {
                let prefix = encoding.decode(&original[encoding.bom_length()..slot.start])?;
                let opening = prefix.rfind('<').ok_or_else(|| {
                    error(
                        "INVALID_SKELETON",
                        "Self-closing stringsdict has no opening tag",
                    )
                })?;
                let name = prefix[opening + 1..]
                    .split_whitespace()
                    .next()
                    .ok_or_else(|| {
                        error("INVALID_SKELETON", "Missing Foundation stringsdict tag")
                    })?;
                format!(">{}</{name}>", apple_xml_text(&native))
            } else if body.contains("<![CDATA[") {
                preserve_apple_cdata(&body, &native)?
            } else {
                apple_xml_text(&native)
            };
            result.extend(encoding.encode_without_bom(&value));
        } else {
            result.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    result.extend_from_slice(&original[previous..]);
    Ok(result)
}

fn stringsdict_device_plural_message(
    message: &Message,
    device: &str,
    category: &str,
) -> Result<Message, ParseError> {
    let branch = stringsdict_device_branches(message, "devicePluralVariants")
        .and_then(|devices| devices.get(device))
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing Foundation device plural branch",
            )
        })?;
    let (variable, original) = branch
        .iter()
        .find_map(|(name, rule)| {
            (rule
                .get("NSStringFormatSpecTypeKey")
                .and_then(serde_json::Value::as_str)
                == Some("NSStringPluralRuleType"))
            .then(|| rule.get(category).and_then(serde_json::Value::as_str))
            .flatten()
            .map(|source| (name.as_str(), source))
        })
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing Foundation device plural category",
            )
        })?;
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation_plural(
        original,
        &mut placeholders,
        variable,
        None,
    );
    let conversions =
        crate::placeholders::foundation_plural_printf_line_separators(original, variable, None);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(disabled),
        );
    }
    Ok(Message::new(normalized, None, None, placeholders, metadata))
}

fn stringsdict_device_width_message(
    message: &Message,
    device: &str,
    width: &str,
) -> Result<Message, ParseError> {
    let original = stringsdict_device_branches(message, "deviceWidthVariants")
        .and_then(|devices| devices.get(device))
        .and_then(|branch| branch.get("NSStringVariableWidthRuleType"))
        .and_then(|widths| widths.get(width))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing Foundation device width branch"))?;
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation(original, &mut placeholders);
    let conversions = crate::placeholders::foundation_printf_line_separators(original);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(disabled),
        );
    }
    Ok(Message::new(normalized, None, None, placeholders, metadata))
}

fn stringsdict_variation_message(
    message: &Message,
    selector: &str,
    branch: &str,
) -> Result<Message, ParseError> {
    let kind = if selector == "@device" {
        "deviceVariants"
    } else {
        "widthVariants"
    };
    let source = stringsdict_device_branches(message, kind)
        .and_then(|branches| branches.get(branch))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing Foundation variation branch"))?;
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation(source, &mut placeholders);
    let conversions = crate::placeholders::foundation_printf_line_separators(source);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(disabled),
        );
    }
    Ok(Message::new(normalized, None, None, placeholders, metadata))
}

fn stringsdict_device_branches<'a>(
    message: &'a Message,
    kind: &str,
) -> Option<&'a serde_json::Value> {
    let metadata = message.metadata.as_ref()?;
    if kind.starts_with("device") {
        metadata
            .get("deviceMixedVariants")
            .or_else(|| metadata.get(kind))
    } else {
        metadata.get(kind)
    }
}

fn stringsdict_device_is_width(message: &Message, device: &str) -> bool {
    stringsdict_device_branches(message, "deviceWidthVariants")
        .and_then(|devices| devices.get(device))
        .and_then(|branch| branch.get("NSStringVariableWidthRuleType"))
        .is_some_and(serde_json::Value::is_object)
}

pub(crate) fn stringsdict_expected_paths(
    catalog: &Catalog,
) -> Result<HashMap<Vec<String>, StringsdictIdentity>, ParseError> {
    stringsdict_expected_paths_with_variations(catalog, false)
}

fn stringsdict_expected_paths_with_variations(
    catalog: &Catalog,
    all_variations: bool,
) -> Result<HashMap<Vec<String>, StringsdictIdentity>, ParseError> {
    let mut expected = HashMap::new();
    for (id, message) in &catalog.messages {
        let metadata = message.metadata.as_ref();
        if let Some(devices) = metadata
            .and_then(|metadata| metadata.get("deviceMixedVariants"))
            .and_then(serde_json::Value::as_object)
        {
            let selected = metadata
                .and_then(|metadata| metadata.get("defaultDevice"))
                .and_then(serde_json::Value::as_str)
                .ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Missing selected Foundation device variation",
                    )
                })?;
            for (device, branch) in devices {
                if !all_variations && device != selected {
                    continue;
                }
                if branch.is_string() {
                    expected.insert(
                        vec![
                            id.clone(),
                            "NSStringDeviceSpecificRuleType".into(),
                            device.clone(),
                        ],
                        StringsdictIdentity {
                            id: id.clone(),
                            selector: all_variations.then_some("@device".to_owned()),
                            variant: all_variations.then_some(device.clone()),
                        },
                    );
                } else if let Some(widths) = branch
                    .get("NSStringVariableWidthRuleType")
                    .and_then(serde_json::Value::as_object)
                {
                    for (width, value) in widths {
                        if !value.is_string() {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Invalid Foundation device width value",
                            ));
                        }
                        expected.insert(
                            vec![
                                id.clone(),
                                "NSStringDeviceSpecificRuleType".into(),
                                device.clone(),
                                "NSStringVariableWidthRuleType".into(),
                                width.clone(),
                            ],
                            StringsdictIdentity {
                                id: id.clone(),
                                selector: Some(format!("@device={device}")),
                                variant: Some(width.clone()),
                            },
                        );
                    }
                } else if let Some(rules) = branch.as_object() {
                    for (variable, rule) in rules {
                        if rule
                            .get("NSStringFormatSpecTypeKey")
                            .and_then(serde_json::Value::as_str)
                            != Some("NSStringPluralRuleType")
                        {
                            continue;
                        }
                        for category in ["zero", "one", "two", "few", "many", "other"] {
                            if rule.get(category).is_some_and(serde_json::Value::is_string) {
                                expected.insert(
                                    vec![
                                        id.clone(),
                                        "NSStringDeviceSpecificRuleType".into(),
                                        device.clone(),
                                        variable.clone(),
                                        category.into(),
                                    ],
                                    StringsdictIdentity {
                                        id: id.clone(),
                                        selector: Some(format!("@device={device}")),
                                        variant: Some(category.into()),
                                    },
                                );
                            }
                        }
                    }
                } else {
                    return Err(error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Invalid Foundation device variation",
                    ));
                }
            }
        } else if let Some(devices) = metadata
            .and_then(|metadata| metadata.get("deviceWidthVariants"))
            .and_then(serde_json::Value::as_object)
        {
            let selected = metadata
                .and_then(|metadata| metadata.get("defaultDevice"))
                .and_then(serde_json::Value::as_str)
                .ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Missing selected Foundation device width",
                    )
                })?;
            for (device, branch) in devices {
                if !all_variations && device != selected {
                    continue;
                }
                let widths = branch
                    .get("NSStringVariableWidthRuleType")
                    .and_then(serde_json::Value::as_object)
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Invalid Foundation device width",
                        )
                    })?;
                for (width, value) in widths {
                    if !value.is_string() {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Invalid Foundation device width value",
                        ));
                    }
                    expected.insert(
                        vec![
                            id.clone(),
                            "NSStringDeviceSpecificRuleType".into(),
                            device.clone(),
                            "NSStringVariableWidthRuleType".into(),
                            width.clone(),
                        ],
                        StringsdictIdentity {
                            id: id.clone(),
                            selector: Some(format!("@device={device}")),
                            variant: Some(width.clone()),
                        },
                    );
                }
            }
        } else if let Some(devices) = metadata
            .and_then(|metadata| metadata.get("devicePluralVariants"))
            .and_then(serde_json::Value::as_object)
        {
            let selected = metadata
                .and_then(|metadata| metadata.get("defaultDevice"))
                .and_then(serde_json::Value::as_str)
                .ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Missing selected Foundation device plural",
                    )
                })?;
            for (device, branch) in devices {
                if !all_variations && device != selected {
                    continue;
                }
                let branch = branch.as_object().ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Invalid Foundation device plural",
                    )
                })?;
                for (variable, rule) in branch {
                    if rule
                        .get("NSStringFormatSpecTypeKey")
                        .and_then(serde_json::Value::as_str)
                        != Some("NSStringPluralRuleType")
                    {
                        continue;
                    }
                    for category in ["zero", "one", "two", "few", "many", "other"] {
                        if rule.get(category).is_some_and(serde_json::Value::is_string) {
                            expected.insert(
                                vec![
                                    id.clone(),
                                    "NSStringDeviceSpecificRuleType".into(),
                                    device.clone(),
                                    variable.clone(),
                                    category.into(),
                                ],
                                StringsdictIdentity {
                                    id: id.clone(),
                                    selector: Some(format!("@device={device}")),
                                    variant: Some(category.into()),
                                },
                            );
                        }
                    }
                }
            }
        } else if let Some(variables) = metadata
            .and_then(|metadata| metadata.get("pluralVariables"))
            .and_then(serde_json::Value::as_array)
        {
            let rules = metadata
                .and_then(|metadata| metadata.get("applePluralRules"))
                .and_then(serde_json::Value::as_object)
                .ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Missing Foundation plural definitions",
                    )
                })?;
            for item in variables {
                let variable = item.as_str().ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Invalid Foundation plural variable",
                    )
                })?;
                let categories = rules
                    .get(variable)
                    .and_then(|definition| definition.get("variants"))
                    .and_then(serde_json::Value::as_object)
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Missing Foundation plural categories",
                        )
                    })?;
                for category in categories.keys() {
                    expected.insert(
                        vec![id.clone(), variable.to_owned(), category.clone()],
                        StringsdictIdentity {
                            id: id.clone(),
                            selector: Some(variable.to_owned()),
                            variant: Some(category.clone()),
                        },
                    );
                }
            }
        } else if let Some(variable) = metadata
            .and_then(|metadata| metadata.get("pluralVariable"))
            .and_then(serde_json::Value::as_str)
        {
            let variants = message.variants.as_ref().ok_or_else(|| {
                error(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Missing Foundation plural categories",
                )
            })?;
            for category in variants.keys() {
                expected.insert(
                    vec![id.clone(), variable.to_owned(), category.clone()],
                    StringsdictIdentity {
                        id: id.clone(),
                        selector: None,
                        variant: Some(category.clone()),
                    },
                );
            }
        } else if let Some(device) = metadata
            .and_then(|metadata| metadata.get("defaultDevice"))
            .and_then(serde_json::Value::as_str)
        {
            if all_variations {
                let branches = metadata
                    .and_then(|metadata| metadata.get("deviceVariants"))
                    .and_then(serde_json::Value::as_object)
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Missing Foundation device branches",
                        )
                    })?;
                for branch in branches.keys() {
                    expected.insert(
                        vec![
                            id.clone(),
                            "NSStringDeviceSpecificRuleType".into(),
                            branch.to_owned(),
                        ],
                        StringsdictIdentity {
                            id: id.clone(),
                            selector: Some("@device".into()),
                            variant: Some(branch.to_owned()),
                        },
                    );
                }
            } else {
                expected.insert(
                    vec![
                        id.clone(),
                        "NSStringDeviceSpecificRuleType".into(),
                        device.to_owned(),
                    ],
                    StringsdictIdentity {
                        id: id.clone(),
                        selector: None,
                        variant: None,
                    },
                );
            }
        } else if let Some(width) = metadata
            .and_then(|metadata| metadata.get("defaultWidth"))
            .and_then(serde_json::Value::as_u64)
        {
            if all_variations {
                let branches = metadata
                    .and_then(|metadata| metadata.get("widthVariants"))
                    .and_then(serde_json::Value::as_object)
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Missing Foundation width branches",
                        )
                    })?;
                for branch in branches.keys() {
                    expected.insert(
                        vec![
                            id.clone(),
                            "NSStringVariableWidthRuleType".into(),
                            branch.to_owned(),
                        ],
                        StringsdictIdentity {
                            id: id.clone(),
                            selector: Some("@width".into()),
                            variant: Some(branch.to_owned()),
                        },
                    );
                }
            } else {
                let identifier = metadata
                    .and_then(|metadata| metadata.get("defaultWidthKey"))
                    .and_then(serde_json::Value::as_str)
                    .map_or_else(|| width.to_string(), str::to_owned);
                expected.insert(
                    vec![
                        id.clone(),
                        "NSStringVariableWidthRuleType".into(),
                        identifier,
                    ],
                    StringsdictIdentity {
                        id: id.clone(),
                        selector: None,
                        variant: None,
                    },
                );
            }
        } else {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Unsupported Foundation stringsdict rule",
            ));
        }
    }
    Ok(expected)
}

#[derive(Clone)]
pub(crate) struct StringsdictIdentity {
    pub(crate) id: String,
    pub(crate) selector: Option<String>,
    pub(crate) variant: Option<String>,
}

struct StringsdictScanner<'a> {
    source: &'a str,
    encoding: Encoding,
    expected: &'a HashMap<Vec<String>, StringsdictIdentity>,
    slots: Vec<SourceSlot>,
}

impl StringsdictScanner<'_> {
    fn scan(&mut self) -> Result<(), ParseError> {
        let mut stack: Vec<StringsdictXmlElement<'_>> = Vec::new();
        let mut position = 0;
        while position < self.source.len() {
            if self.source.as_bytes()[position] != b'<' {
                position += self.source[position..].chars().next().unwrap().len_utf8();
                continue;
            }
            if self.source[position..].starts_with("<!--") {
                position = skip(self.source, position, "-->")?;
                continue;
            }
            if self.source[position..].starts_with("<![CDATA[") {
                position = skip(self.source, position, "]]>")?;
                continue;
            }
            if self.source[position..].starts_with("<?") {
                position = skip(self.source, position, "?>")?;
                continue;
            }
            let end = tag_end(self.source, position)?;
            let mut token = self.source[position + 1..end].trim();
            if token.starts_with('!') {
                position = end + 1;
                continue;
            }
            if token.starts_with('/') {
                let current = stack.pop().ok_or_else(|| {
                    error("INVALID_SKELETON", "Unbalanced Foundation stringsdict XML")
                })?;
                if current.name == "key" && stack.last().is_some_and(|parent| parent.name == "dict")
                {
                    let fragment = format!(
                        "<dict><key>{}</key><string/></dict>",
                        &self.source[current.body_start..position]
                    );
                    let catalog = crate::apple::parse_strings(&fragment)?;
                    stack.last_mut().unwrap().pending_key = catalog.messages.keys().next().cloned();
                } else if current.name == "string" {
                    self.add(&current.path, current.body_start, position);
                }
            } else {
                let empty = token.ends_with('/');
                if empty {
                    token = token[..token.len() - 1].trim();
                }
                let name = token.split_whitespace().next().ok_or_else(|| {
                    error("INVALID_SKELETON", "Missing Foundation stringsdict XML tag")
                })?;
                let path = if let Some(parent) = stack.last_mut() {
                    if parent.name == "dict" && name != "key" {
                        let key = parent.pending_key.take().ok_or_else(|| {
                            error(
                                "INVALID_SKELETON",
                                "Foundation dictionary value is missing its key",
                            )
                        })?;
                        let mut path = parent.path.clone();
                        path.push(key);
                        path
                    } else {
                        parent.path.clone()
                    }
                } else {
                    Vec::new()
                };
                if empty {
                    if name == "string" {
                        let slash = self.source[position..=end]
                            .rfind('/')
                            .map(|index| position + index)
                            .ok_or_else(|| {
                                error(
                                    "INVALID_SKELETON",
                                    "Self-closing Foundation string has no slash",
                                )
                            })?;
                        self.add(&path, slash, end + 1);
                    }
                } else {
                    stack.push(StringsdictXmlElement {
                        name,
                        body_start: end + 1,
                        path,
                        pending_key: None,
                    });
                }
            }
            position = end + 1;
        }
        Ok(())
    }

    fn add(&mut self, path: &[String], start: usize, end: usize) {
        if let Some(identity) = self.expected.get(path) {
            self.slots.push(SourceSlot {
                id: identity.id.clone(),
                selector: identity.selector.clone(),
                variant: identity.variant.clone(),
                start: self.encoding.offset(self.source, start),
                end: self.encoding.offset(self.source, end),
                apple_object_index: None,
            });
        }
    }
}

struct StringsdictXmlElement<'a> {
    name: &'a str,
    body_start: usize,
    path: Vec<String>,
    pending_key: Option<String>,
}

fn extract_xcstrings(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    extract_xcstrings_with_options(bytes, false, false, None)
}

pub(crate) fn extract_xcstrings_devices(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    extract_xcstrings_with_options(bytes, true, false, None)
}

pub(crate) fn extract_xcstrings_source_insertion(
    bytes: &[u8],
) -> Result<SourceSkeleton, ParseError> {
    extract_xcstrings_with_options(bytes, false, true, None)
}

pub(crate) fn extract_xcstrings_target_insertion(
    bytes: &[u8],
    target_locale: &str,
) -> Result<SourceSkeleton, ParseError> {
    extract_xcstrings_with_options(bytes, false, false, Some(target_locale))
}

fn extract_xcstrings_with_options(
    bytes: &[u8],
    all_devices: bool,
    insert_source_locales: bool,
    target_locale: Option<&str>,
) -> Result<SourceSkeleton, ParseError> {
    let encoding = Encoding::detect(bytes);
    let source = crate::decode(bytes, None)?;
    let catalog = crate::parse(FileFormat::AppleXcstrings, bytes)?;
    let root: serde_json::Value = serde_json::from_str(&source)
        .map_err(|_| error("INVALID_SKELETON", "Invalid Xcode JSON source"))?;
    let target_locale = target_locale
        .map(|requested| xcstrings_target_locale(&root, requested))
        .transpose()?;
    let expected = xcstrings_expected_paths(
        &root,
        &catalog,
        all_devices,
        insert_source_locales,
        target_locale.as_deref(),
    )?;
    let mut scanner = XcodeScanner {
        source: &source,
        encoding,
        expected: &expected,
        slots: Vec::new(),
        index: 0,
    };
    scanner.value(&mut Vec::new())?;
    if scanner.slots.len() != expected.len() {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Missing Xcode source-locale value slot",
        ));
    }
    let slots = scanner.slots;
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::AppleXcstrings.id(),
        encoding: encoding.name().to_owned(),
        source,
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: target_locale,
        slots,
    })
}

fn render_xcstrings(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let catalog = crate::parse(FileFormat::AppleXcstrings, &original)?;
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if !known.insert(slot.key()) {
            return Err(error("INVALID_SKELETON", "Duplicated Xcode source value"));
        }
    }
    if translations.keys().any(|key| !known.contains(key)) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no Xcode source value",
        ));
    }
    let mut result = Vec::with_capacity(original.len());
    let mut insertion_owners: Option<Vec<SourceSlot>> = None;
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error("INVALID_SKELETON", "Invalid Xcode JSON value range"));
        }
        result.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.key()) {
            let message = catalog.messages.get(&slot.id).ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Missing canonical Xcode source descriptor",
                )
            })?;
            let owned = if skeleton.apple_target_locale.is_some() {
                true
            } else if let Some(selector) = &slot.selector {
                if selector == "@device" {
                    slot.variant.as_ref().is_some_and(|variant| {
                        message
                            .metadata
                            .as_ref()
                            .and_then(|metadata| metadata.get("sourceVariationAxes"))
                            .and_then(|axes| axes.get("device"))
                            .and_then(|devices| devices.get(variant))
                            .and_then(|branch| branch.get("stringUnit"))
                            .and_then(|unit| unit.get("value"))
                            .is_some_and(serde_json::Value::is_string)
                    })
                } else if let Some(device) = selector.strip_prefix("@device=") {
                    slot.variant.as_ref().is_some_and(|category| {
                        message
                            .metadata
                            .as_ref()
                            .and_then(|metadata| metadata.get("sourceVariationAxes"))
                            .and_then(|axes| axes.get("device"))
                            .and_then(|devices| devices.get(device))
                            .and_then(|branch| branch.get("variations"))
                            .and_then(|variations| variations.get("plural"))
                            .and_then(|plural| plural.get(category))
                            .and_then(|branch| branch.get("stringUnit"))
                            .and_then(|unit| unit.get("value"))
                            .is_some_and(serde_json::Value::is_string)
                    })
                } else {
                    slot.variant.as_ref().is_some_and(|variant| {
                        xcstrings_substitutions(message)
                            .and_then(|substitutions| substitutions.get(selector))
                            .and_then(|definition| definition.get("variations"))
                            .and_then(|variations| variations.get("plural"))
                            .and_then(|plural| plural.get(variant))
                            .and_then(|branch| branch.get("stringUnit"))
                            .and_then(|unit| unit.get("value"))
                            .is_some_and(serde_json::Value::is_string)
                    })
                }
            } else {
                slot.variant.as_ref().is_none_or(|variant| {
                    message
                        .variants
                        .as_ref()
                        .is_some_and(|variants| variants.contains_key(variant))
                })
            };
            if !owned {
                return Err(error("INVALID_SKELETON", "Missing Xcode plural category"));
            }
            if let Some(locale) = skeleton.apple_target_locale.as_deref() {
                if insertion_owners.is_none() {
                    insertion_owners =
                        Some(extract_xcstrings_target_insertion(&original, locale)?.slots);
                }
                if !insertion_owners
                    .as_ref()
                    .expect("Xcode target-locale ownership")
                    .contains(slot)
                {
                    return Err(error(
                        "INVALID_SKELETON",
                        "Xcode target slot does not own its requested locale",
                    ));
                }
            }
            let target_device_insertion = skeleton.apple_target_locale.is_some()
                && message
                    .metadata
                    .as_ref()
                    .is_some_and(|metadata| metadata.contains_key("sourceVariationAxes"))
                && xcstrings_substitutions(message).is_none()
                && slot.selector.is_none()
                && slot.variant.is_none();
            let target_plural_insertion = skeleton.apple_target_locale.is_some()
                && message.variants.is_some()
                && !target_device_insertion
                && slot.selector.is_none()
                && slot.variant.is_none();
            let target_substitution_insertion = skeleton.apple_target_locale.is_some()
                && xcstrings_substitutions(message).is_some()
                && slot.selector.is_none()
                && slot.variant.is_none()
                && (slot.start == slot.end
                    || original[slot.start..slot.end] == encoding.encode_without_bom("null"));
            let native = if target_device_insertion
                || target_plural_insertion
                || target_substitution_insertion
            {
                None
            } else if skeleton.apple_target_locale.is_some()
                && xcstrings_substitutions(message).is_some()
                && (slot.selector.is_none() || slot.selector.as_deref() == Some("@device"))
            {
                Some(restore_xcstrings_target_substitution_root(
                    translation,
                    message,
                    skeleton
                        .apple_target_locale
                        .as_deref()
                        .expect("target substitution locale"),
                    if slot.selector.as_deref() == Some("@device") {
                        slot.variant.as_deref()
                    } else {
                        None
                    },
                )?)
            } else if skeleton.apple_target_locale.is_some()
                && xcstrings_substitutions(message).is_some()
                && slot.selector.is_some()
            {
                Some(restore_xcstrings_target_substitution_category(
                    translation,
                    message,
                    skeleton
                        .apple_target_locale
                        .as_deref()
                        .expect("target substitution locale"),
                    slot.selector
                        .as_deref()
                        .expect("target substitution selector"),
                    slot.variant
                        .as_deref()
                        .expect("target substitution category"),
                )?)
            } else if let (Some(locale), Some("@device"), Some(device)) = (
                skeleton.apple_target_locale.as_deref(),
                slot.selector.as_deref(),
                slot.variant.as_deref(),
            ) {
                Some(restore_xcstrings_target_device_root(
                    translation,
                    message,
                    locale,
                    device,
                )?)
            } else if let (Some(locale), Some(device), Some(category)) = (
                skeleton.apple_target_locale.as_deref(),
                slot.selector
                    .as_deref()
                    .and_then(|selector| selector.strip_prefix("@device=")),
                slot.variant.as_deref(),
            ) {
                Some(restore_xcstrings_target_device_plural(
                    translation,
                    message,
                    locale,
                    device,
                    category,
                )?)
            } else if let (Some(locale), None, Some(category)) = (
                skeleton.apple_target_locale.as_deref(),
                slot.selector.as_deref(),
                slot.variant.as_deref(),
            ) {
                Some(restore_xcstrings_target_plural(
                    translation,
                    message,
                    locale,
                    category,
                )?)
            } else if slot.selector.as_deref() == Some("@device") {
                Some(restore_xcstrings_device_root(
                    translation,
                    message,
                    slot.variant.as_deref().expect("validated Xcode device"),
                )?)
            } else if let Some(device) = slot
                .selector
                .as_deref()
                .and_then(|selector| selector.strip_prefix("@device="))
            {
                Some(restore_xcstrings_device_plural(
                    translation,
                    message,
                    device,
                    slot.variant.as_deref().expect("validated Xcode plural"),
                )?)
            } else if slot.selector.is_none() && xcstrings_substitutions(message).is_some() {
                Some(restore_xcstrings_substitution_root(translation, message)?)
            } else if slot.selector.is_none() {
                if let Some(variant) = slot.variant.as_deref() {
                    Some(crate::apple_xcstrings_writer::restore_variant(
                        translation,
                        message,
                        variant,
                    ))
                } else {
                    Some(crate::apple_xcstrings_writer::restore(translation, message))
                }
            } else if let (Some(selector), Some(variant)) =
                (slot.selector.as_deref(), slot.variant.as_deref())
            {
                Some(crate::apple_xcstrings_writer::restore_substitution_variant(
                    translation,
                    message,
                    selector,
                    variant,
                ))
            } else {
                Some(crate::apple_xcstrings_writer::restore(translation, message))
            };
            let original_value = &original[slot.start..slot.end];
            let replacement = if original_value.is_empty()
                || original_value == encoding.encode_without_bom("null")
            {
                if insertion_owners.is_none() {
                    insertion_owners = Some(extract_xcstrings_source_insertion(&original)?.slots);
                }
                if slot.selector.is_some()
                    || slot.variant.is_some()
                    || skeleton.apple_target_locale.is_none()
                        && message.metadata.as_ref().is_some_and(|metadata| {
                            metadata.contains_key("appleSourceLocalization")
                        })
                    || !insertion_owners
                        .as_ref()
                        .expect("missing or nullable Xcode source ownership")
                        .contains(slot)
                {
                    return Err(error(
                        "INVALID_SKELETON",
                        "Xcode insertion slot does not own a source locale",
                    ));
                }
                let inserted_value = if target_substitution_insertion {
                    inserted_xcstrings_target_substitution(
                        translation,
                        message,
                        &catalog,
                        skeleton
                            .apple_target_locale
                            .as_deref()
                            .expect("target substitution locale"),
                    )?
                } else if target_device_insertion {
                    inserted_xcstrings_target_device(
                        translation,
                        message,
                        &catalog,
                        skeleton
                            .apple_target_locale
                            .as_deref()
                            .expect("target device locale"),
                    )?
                } else if target_plural_insertion {
                    inserted_xcstrings_target_plural(
                        translation,
                        message,
                        &catalog,
                        skeleton
                            .apple_target_locale
                            .as_deref()
                            .expect("target plural locale"),
                    )?
                } else {
                    serde_json::json!({
                        "stringUnit": {"state": "translated", "value": native}
                    })
                };
                let inserted = serde_json::to_string(&inserted_value).map_err(|_| {
                    error("INVALID_SKELETON", "Cannot serialize Xcode source locale")
                })?;
                if original_value.is_empty() {
                    let language = skeleton
                        .apple_target_locale
                        .as_deref()
                        .or_else(|| {
                            message
                                .metadata
                                .as_ref()
                                .and_then(|metadata| metadata.get("appleSourceLanguage"))
                                .and_then(serde_json::Value::as_str)
                        })
                        .or(catalog.locale.as_deref())
                        .ok_or_else(|| {
                            error("INVALID_SKELETON", "Missing Xcode source language")
                        })?;
                    let language = serde_json::to_string(language).map_err(|_| {
                        error("INVALID_SKELETON", "Cannot serialize Xcode source language")
                    })?;
                    format!(",{language}:{inserted}")
                } else {
                    inserted
                }
            } else {
                let quoted = serde_json::to_string(
                    native
                        .as_deref()
                        .expect("existing Xcode source slots own scalar values"),
                )
                .map_err(|_| error("INVALID_SKELETON", "Cannot serialize Xcode JSON value"))?;
                quoted[1..quoted.len() - 1].to_owned()
            };
            result.extend(encoding.encode_without_bom(&replacement));
        } else {
            result.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    result.extend_from_slice(&original[previous..]);
    Ok(result)
}

fn xcstrings_expected_paths(
    root: &serde_json::Value,
    catalog: &Catalog,
    all_devices: bool,
    insert_source_locales: bool,
    target_locale: Option<&str>,
) -> Result<HashMap<Vec<String>, XcodeIdentity>, ParseError> {
    let mut expected = HashMap::new();
    for (id, message) in &catalog.messages {
        let localizations = root["strings"][id]["localizations"]
            .as_object()
            .expect("validated Xcode localization map");
        if let Some(target) = target_locale {
            let mut prefix = vec!["strings".to_owned(), id.clone(), "localizations".to_owned()];
            if let Some(source_substitutions) = xcstrings_substitutions(message) {
                if localizations
                    .get(target)
                    .is_none_or(serde_json::Value::is_null)
                {
                    for selector in source_substitutions.keys() {
                        if xcstrings_target_substitution_evidence(
                            catalog, target, selector, message,
                        )
                        .is_none()
                            && xcstrings_target_plural_categories(catalog, target).is_empty()
                        {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target substitution insertion requires native category evidence",
                            ));
                        }
                    }
                    if localizations.contains_key(target) {
                        prefix.push(target.to_owned());
                    }
                    expected.insert(
                        prefix,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: None,
                        },
                    );
                    continue;
                }
                let localization = localizations.get(target).ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Xcode target requires matching substitutions",
                    )
                })?;
                let substitutions = localization
                    .get("substitutions")
                    .and_then(serde_json::Value::as_object)
                    .filter(|substitutions| substitutions.len() == source_substitutions.len())
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target requires matching substitutions",
                        )
                    })?;
                for selector in source_substitutions.keys() {
                    let plural = substitutions
                        .get(selector)
                        .and_then(|definition| definition.get("variations"))
                        .and_then(|variations| variations.get("plural"))
                        .and_then(serde_json::Value::as_object)
                        .ok_or_else(|| {
                            error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target substitution requires plural values",
                            )
                        })?;
                    if !plural.contains_key("other") {
                        return Err(error(
                            "MISSING_OTHER_VARIANT",
                            "Xcode target substitution plural requires other",
                        ));
                    }
                    for (category, branch) in plural {
                        if !branch["stringUnit"]["value"].is_string() {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target substitution requires string-valued branches",
                            ));
                        }
                        let mut path = prefix.clone();
                        path.extend([
                            target.to_owned(),
                            "substitutions".to_owned(),
                            selector.clone(),
                            "variations".to_owned(),
                            "plural".to_owned(),
                            category.clone(),
                            "stringUnit".to_owned(),
                            "value".to_owned(),
                        ]);
                        expected.insert(
                            path,
                            XcodeIdentity {
                                id: id.clone(),
                                selector: Some(selector.clone()),
                                variant: Some(category.clone()),
                            },
                        );
                    }
                }
                let source_localizations = root["strings"][id]["localizations"]
                    .as_object()
                    .expect("validated Xcode localization map");
                let source_identifier = crate::apple::xcstrings_source_locale(
                    source_localizations,
                    root["sourceLanguage"]
                        .as_str()
                        .expect("validated source locale"),
                );
                let source_devices =
                    source_localizations[source_identifier]["variations"]["device"].as_object();
                if localization["stringUnit"]["value"].is_string() {
                    if source_devices.is_some() {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target substitution requires matching device roots",
                        ));
                    }
                    let mut path = prefix.clone();
                    path.extend([
                        target.to_owned(),
                        "stringUnit".to_owned(),
                        "value".to_owned(),
                    ]);
                    expected.insert(
                        path,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: None,
                        },
                    );
                } else {
                    let devices = localization
                        .get("variations")
                        .and_then(|variations| variations.get("device"))
                        .and_then(serde_json::Value::as_object)
                        .filter(|devices| {
                            source_devices.is_some_and(|source| {
                                !devices.is_empty()
                                    && devices.len() == source.len()
                                    && devices.keys().all(|device| source.contains_key(device))
                            })
                        })
                        .ok_or_else(|| {
                            error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target substitution requires matching scalar or device roots",
                            )
                        })?;
                    for (device, branch) in devices {
                        if !branch["stringUnit"]["value"].is_string() {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target substitution device requires a scalar root",
                            ));
                        }
                        let mut path = prefix.clone();
                        path.extend([
                            target.to_owned(),
                            "variations".to_owned(),
                            "device".to_owned(),
                            device.clone(),
                            "stringUnit".to_owned(),
                            "value".to_owned(),
                        ]);
                        expected.insert(
                            path,
                            XcodeIdentity {
                                id: id.clone(),
                                selector: Some("@device".to_owned()),
                                variant: Some(device.clone()),
                            },
                        );
                    }
                }
                continue;
            }
            if message
                .metadata
                .as_ref()
                .is_some_and(|metadata| metadata.contains_key("sourceVariationAxes"))
            {
                if localizations
                    .get(target)
                    .is_none_or(serde_json::Value::is_null)
                {
                    if message.variants.is_some()
                        && xcstrings_target_plural_categories(catalog, target).is_empty()
                    {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target device plural insertion requires native category evidence",
                        ));
                    }
                    if localizations.contains_key(target) {
                        prefix.push(target.to_owned());
                    }
                    expected.insert(
                        prefix,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: None,
                        },
                    );
                    continue;
                }
                let devices = localizations
                    .get(target)
                    .and_then(|localization| localization.get("variations"))
                    .and_then(|variations| variations.get("device"))
                    .and_then(serde_json::Value::as_object)
                    .filter(|devices| !devices.is_empty())
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target locale requires device branches",
                        )
                    })?;
                for (device, branch) in devices {
                    if branch["stringUnit"]["value"].is_string() && message.variants.is_none() {
                        let mut path = prefix.clone();
                        path.extend([
                            target.to_owned(),
                            "variations".to_owned(),
                            "device".to_owned(),
                            device.clone(),
                            "stringUnit".to_owned(),
                            "value".to_owned(),
                        ]);
                        expected.insert(
                            path,
                            XcodeIdentity {
                                id: id.clone(),
                                selector: Some("@device".to_owned()),
                                variant: Some(device.clone()),
                            },
                        );
                        continue;
                    }
                    let plural = branch
                        .get("variations")
                        .and_then(|variations| variations.get("plural"))
                        .and_then(serde_json::Value::as_object)
                        .filter(|_| message.variants.is_some())
                        .ok_or_else(|| {
                            error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target device requires matching scalar or plural branches",
                            )
                        })?;
                    if !plural.contains_key("other") {
                        return Err(error(
                            "MISSING_OTHER_VARIANT",
                            "Xcode target device plural requires other",
                        ));
                    }
                    for (category, variation) in plural {
                        if !variation["stringUnit"]["value"].is_string() {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Xcode target device plural requires string-valued branches",
                            ));
                        }
                        let mut path = prefix.clone();
                        path.extend([
                            target.to_owned(),
                            "variations".to_owned(),
                            "device".to_owned(),
                            device.clone(),
                            "variations".to_owned(),
                            "plural".to_owned(),
                            category.clone(),
                            "stringUnit".to_owned(),
                            "value".to_owned(),
                        ]);
                        expected.insert(
                            path,
                            XcodeIdentity {
                                id: id.clone(),
                                selector: Some(format!("@device={device}")),
                                variant: Some(category.clone()),
                            },
                        );
                    }
                }
                continue;
            }
            if message.variants.is_some() {
                let plural = localizations
                    .get(target)
                    .and_then(|localization| localization.get("variations"))
                    .and_then(|variations| variations.get("plural"))
                    .and_then(serde_json::Value::as_object);
                let Some(plural) = plural else {
                    if localizations
                        .get(target)
                        .is_some_and(|localization| !localization.is_null())
                    {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target locale requires plural branches",
                        ));
                    }
                    if xcstrings_target_plural_categories(catalog, target).is_empty() {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target plural insertion requires native category evidence",
                        ));
                    }
                    if localizations.contains_key(target) {
                        prefix.push(target.to_owned());
                    }
                    expected.insert(
                        prefix,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: None,
                        },
                    );
                    continue;
                };
                if !plural.contains_key("other") {
                    return Err(error(
                        "MISSING_OTHER_VARIANT",
                        "Xcode target plural requires other",
                    ));
                }
                for (category, branch) in plural {
                    if !branch["stringUnit"]["value"].is_string() {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Xcode target plural requires string-valued branches",
                        ));
                    }
                    let mut path = prefix.clone();
                    path.extend([
                        target.to_owned(),
                        "variations".to_owned(),
                        "plural".to_owned(),
                        category.clone(),
                        "stringUnit".to_owned(),
                        "value".to_owned(),
                    ]);
                    expected.insert(
                        path,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: Some(category.clone()),
                        },
                    );
                }
                continue;
            }
            match localizations.get(target) {
                None => {}
                Some(localization) if localization.is_null() => {
                    prefix.push(target.to_owned());
                }
                Some(localization) if localization["stringUnit"]["value"].is_string() => {
                    prefix.extend([
                        target.to_owned(),
                        "stringUnit".to_owned(),
                        "value".to_owned(),
                    ]);
                }
                Some(_) => {
                    return Err(error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Xcode target locale requires a scalar string unit",
                    ));
                }
            }
            expected.insert(
                prefix,
                XcodeIdentity {
                    id: id.clone(),
                    selector: None,
                    variant: None,
                },
            );
            continue;
        }
        let identifier = crate::apple::xcstrings_source_locale(
            localizations,
            root["sourceLanguage"]
                .as_str()
                .expect("semantic Xcode source language"),
        )
        .to_owned();
        let mut prefix = vec![
            "strings".to_owned(),
            id.clone(),
            "localizations".to_owned(),
            identifier.clone(),
        ];
        let Some(mut localization) = localizations
            .get(&identifier)
            .filter(|source| source.is_object())
        else {
            if insert_source_locales {
                if localizations
                    .get(&identifier)
                    .is_some_and(serde_json::Value::is_null)
                {
                    expected.insert(
                        prefix,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: None,
                        },
                    );
                    continue;
                }
                if !localizations.contains_key(&identifier) {
                    prefix.pop();
                    expected.insert(
                        prefix,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: None,
                        },
                    );
                    continue;
                }
            }
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Xcode fallback keys have no source-locale value",
            ));
        };
        let localization_prefix = prefix.clone();
        let top_level_plural = localization
            .get("variations")
            .and_then(|value| value.get("plural"))
            .is_some_and(serde_json::Value::is_object);
        if let Some(device) = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("defaultDevice"))
            .and_then(serde_json::Value::as_str)
            .filter(|_| !top_level_plural)
        {
            if all_devices {
                let devices = localization["variations"]["device"]
                    .as_object()
                    .expect("validated Xcode device map");
                for (name, branch) in devices {
                    if message.variants.is_none() {
                        if !branch["stringUnit"]["value"].is_string() {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Missing Xcode device source value",
                            ));
                        }
                        let mut path = prefix.clone();
                        path.extend([
                            "variations".into(),
                            "device".into(),
                            name.clone(),
                            "stringUnit".into(),
                            "value".into(),
                        ]);
                        expected.insert(
                            path,
                            XcodeIdentity {
                                id: id.clone(),
                                selector: Some("@device".into()),
                                variant: Some(name.clone()),
                            },
                        );
                    } else {
                        let categories =
                            branch["variations"]["plural"].as_object().ok_or_else(|| {
                                error(
                                    "UNSUPPORTED_SKELETON_SOURCE",
                                    "Missing Xcode device plural axis",
                                )
                            })?;
                        for (category, plural) in categories {
                            if !plural["stringUnit"]["value"].is_string() {
                                return Err(error(
                                    "UNSUPPORTED_SKELETON_SOURCE",
                                    "Missing Xcode device plural source value",
                                ));
                            }
                            let mut path = prefix.clone();
                            path.extend([
                                "variations".into(),
                                "device".into(),
                                name.clone(),
                                "variations".into(),
                                "plural".into(),
                                category.clone(),
                                "stringUnit".into(),
                                "value".into(),
                            ]);
                            expected.insert(
                                path,
                                XcodeIdentity {
                                    id: id.clone(),
                                    selector: Some(format!("@device={name}")),
                                    variant: Some(category.clone()),
                                },
                            );
                        }
                    }
                }
            }
            prefix.extend(["variations".into(), "device".into(), device.to_owned()]);
            localization = &localization["variations"]["device"][device];
        }
        if let Some(variants) = &message.variants {
            if xcstrings_substitutions(message).is_some() {
                return Err(error(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Xcode top-level plurals with substitutions require nested-axis ownership",
                ));
            }
            let plural = localization["variations"]["plural"]
                .as_object()
                .ok_or_else(|| {
                    error(
                        "UNSUPPORTED_SKELETON_SOURCE",
                        "Missing source-owned Xcode plural axis",
                    )
                })?;
            if !all_devices
                || message
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.get("defaultDevice"))
                    .is_none()
            {
                for category in variants.keys() {
                    if !plural[category]["stringUnit"]["value"].is_string() {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Missing source-owned Xcode plural value",
                        ));
                    }
                    let mut path = prefix.clone();
                    path.extend([
                        "variations".into(),
                        "plural".into(),
                        category.clone(),
                        "stringUnit".into(),
                        "value".into(),
                    ]);
                    expected.insert(
                        path,
                        XcodeIdentity {
                            id: id.clone(),
                            selector: None,
                            variant: Some(category.clone()),
                        },
                    );
                }
            }
        } else {
            if !localization["stringUnit"]["value"].is_string() {
                return Err(error(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Missing source-owned Xcode scalar value",
                ));
            }
            if !all_devices
                || message
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.get("defaultDevice"))
                    .is_none()
            {
                prefix.extend(["stringUnit".into(), "value".into()]);
                expected.insert(
                    prefix,
                    XcodeIdentity {
                        id: id.clone(),
                        selector: None,
                        variant: None,
                    },
                );
            }
            if let Some(substitutions) = xcstrings_substitutions(message) {
                let native = root["strings"][id]["localizations"][&identifier]["substitutions"]
                    .as_object()
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Missing source-owned Xcode substitutions",
                        )
                    })?;
                for selector in substitutions.keys() {
                    let categories = native
                        .get(selector)
                        .and_then(|definition| definition.get("variations"))
                        .and_then(|variations| variations.get("plural"))
                        .and_then(serde_json::Value::as_object)
                        .ok_or_else(|| {
                            error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Missing Xcode substitution plural axis",
                            )
                        })?;
                    for (category, branch) in categories {
                        if !branch["stringUnit"]["value"].is_string() {
                            return Err(error(
                                "UNSUPPORTED_SKELETON_SOURCE",
                                "Missing Xcode substitution plural value",
                            ));
                        }
                        let mut path = localization_prefix.clone();
                        path.extend([
                            "substitutions".into(),
                            selector.clone(),
                            "variations".into(),
                            "plural".into(),
                            category.clone(),
                            "stringUnit".into(),
                            "value".into(),
                        ]);
                        expected.insert(
                            path,
                            XcodeIdentity {
                                id: id.clone(),
                                selector: Some(selector.clone()),
                                variant: Some(category.clone()),
                            },
                        );
                    }
                }
            }
        }
    }
    Ok(expected)
}

fn xcstrings_target_locale(
    root: &serde_json::Value,
    requested: &str,
) -> Result<String, ParseError> {
    let normalized = requested.replace('_', "-");
    let native_locale = crate::apple::xcstrings_native_bundle_locale(requested);
    let mut pieces = normalized.split('-');
    let language = pieces.next().unwrap_or_default();
    if !(2..=8).contains(&language.len())
        || !language.bytes().all(|byte| byte.is_ascii_alphabetic())
        || pieces.any(|part| {
            !(1..=8).contains(&part.len()) || !part.bytes().all(|byte| byte.is_ascii_alphanumeric())
        })
        || native_locale
            == crate::apple::xcstrings_native_bundle_locale(
                root["sourceLanguage"]
                    .as_str()
                    .expect("semantic Xcode source language"),
            )
    {
        return Err(error(
            "INVALID_XCSTRINGS_LOCALE",
            "Invalid or source-owned Xcode target locale",
        ));
    }
    let mut existing: Option<&str> = None;
    let mut fallback: Option<&str> = None;
    for descriptor in root["strings"]
        .as_object()
        .expect("semantic Xcode string catalog")
        .values()
    {
        for locale in descriptor["localizations"]
            .as_object()
            .expect("semantic Xcode localization map")
            .keys()
        {
            if crate::apple::xcstrings_native_bundle_locale(locale) == native_locale {
                if existing.is_some_and(|previous| previous != locale) {
                    return Err(error(
                        "INVALID_XCSTRINGS_LOCALE",
                        "Ambiguous normalized Xcode target locale",
                    ));
                }
                existing = Some(locale);
            } else if locale.replace('_', "-") == normalized {
                if fallback.is_some_and(|previous| previous != locale) {
                    return Err(error(
                        "INVALID_XCSTRINGS_LOCALE",
                        "Ambiguous normalized Xcode target locale",
                    ));
                }
                fallback = Some(locale);
            }
        }
    }
    Ok(existing
        .or(fallback)
        .map_or_else(|| requested.to_owned(), str::to_owned))
}

#[derive(Clone)]
struct XcodeIdentity {
    id: String,
    selector: Option<String>,
    variant: Option<String>,
}

fn xcstrings_substitutions(
    message: &Message,
) -> Option<&serde_json::Map<String, serde_json::Value>> {
    message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("sourceSubstitutions"))
        .and_then(serde_json::Value::as_object)
        .filter(|substitutions| !substitutions.is_empty())
}

fn restore_xcstrings_substitution_root(
    translated: &str,
    message: &Message,
) -> Result<String, ParseError> {
    restore_xcstrings_substitution_root_for_device(translated, message, None)
}

fn restore_xcstrings_device_root(
    translated: &str,
    message: &Message,
    device: &str,
) -> Result<String, ParseError> {
    let original = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("sourceVariationAxes"))
        .and_then(|axes| axes.get("device"))
        .and_then(|devices| devices.get(device))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing source-owned Xcode device branch",
            )
        })?;
    if xcstrings_substitutions(message).is_some() {
        return restore_xcstrings_substitution_root_for_device(translated, message, Some(device));
    }
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize(original, &mut placeholders, None);
    let conversions = crate::placeholders::printf_line_separators(original);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_printf_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(disabled),
        );
    }
    let scoped = Message::new(normalized, None, None, placeholders, metadata);
    Ok(crate::apple_xcstrings_writer::restore(translated, &scoped))
}

fn restore_xcstrings_device_plural(
    translated: &str,
    message: &Message,
    device: &str,
    category: &str,
) -> Result<String, ParseError> {
    let original = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("sourceVariationAxes"))
        .and_then(|axes| axes.get("device"))
        .and_then(|devices| devices.get(device))
        .and_then(|device| device.get("variations"))
        .and_then(|variations| variations.get("plural"))
        .and_then(|plural| plural.get(category))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing source-owned Xcode device plural",
            )
        })?;
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation_plural(
        original,
        &mut placeholders,
        "count",
        None,
    );
    let conversions =
        crate::placeholders::foundation_plural_printf_line_separators(original, "count", None);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(disabled),
        );
    }
    let scoped = Message::new(normalized, None, None, placeholders, metadata);
    Ok(crate::apple_xcstrings_writer::restore(translated, &scoped))
}

fn restore_xcstrings_target_plural(
    translated: &str,
    message: &Message,
    locale: &str,
    category: &str,
) -> Result<String, ParseError> {
    let normalized_locale = locale.replace('_', "-");
    let original = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleLocalizationSources"))
        .and_then(|localizations| localizations.get(&normalized_locale))
        .and_then(|localization| localization.get("variations"))
        .and_then(|variations| variations.get("plural"))
        .and_then(|plural| plural.get(category))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing target-owned Xcode plural"))?;
    restore_xcstrings_target_plural_value(translated, original)
}

fn restore_xcstrings_target_device_root(
    translated: &str,
    message: &Message,
    locale: &str,
    device: &str,
) -> Result<String, ParseError> {
    let normalized_locale = locale.replace('_', "-");
    let original = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleLocalizationSources"))
        .and_then(|localizations| localizations.get(&normalized_locale))
        .and_then(|localization| localization.get("variations"))
        .and_then(|variations| variations.get("device"))
        .and_then(|devices| devices.get(device))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing target-owned Xcode device"))?;
    restore_xcstrings_target_device_root_value(translated, original)
}

fn restore_xcstrings_target_device_root_value(
    translated: &str,
    original: &str,
) -> Result<String, ParseError> {
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation(original, &mut placeholders);
    let conversions = crate::placeholders::foundation_printf_line_separators(original);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(xcstrings_target_disabled_conversions(
                &normalized,
                translated,
                disabled,
            )),
        );
        normalized = translated.to_owned();
    }
    let scoped = Message::new(normalized, None, None, placeholders, metadata);
    Ok(crate::apple_xcstrings_writer::restore(translated, &scoped))
}

fn restore_xcstrings_target_device_plural(
    translated: &str,
    message: &Message,
    locale: &str,
    device: &str,
    category: &str,
) -> Result<String, ParseError> {
    let normalized_locale = locale.replace('_', "-");
    let original = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleLocalizationSources"))
        .and_then(|localizations| localizations.get(&normalized_locale))
        .and_then(|localization| localization.get("variations"))
        .and_then(|variations| variations.get("device"))
        .and_then(|devices| devices.get(device))
        .and_then(|branch| branch.get("variations"))
        .and_then(|variations| variations.get("plural"))
        .and_then(|plural| plural.get(category))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing target-owned Xcode device plural",
            )
        })?;
    restore_xcstrings_target_plural_value(translated, original)
}

fn restore_xcstrings_target_substitution_root(
    translated: &str,
    message: &Message,
    locale: &str,
    device: Option<&str>,
) -> Result<String, ParseError> {
    let normalized_locale = locale.replace('_', "-");
    let target = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleLocalizationSources"))
        .and_then(|localizations| localizations.get(&normalized_locale))
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing target-owned Xcode substitutions",
            )
        })?;
    let original = if let Some(device) = device {
        target
            .get("variations")
            .and_then(|variations| variations.get("device"))
            .and_then(|devices| devices.get(device))
            .and_then(|branch| branch.get("stringUnit"))
            .and_then(|unit| unit.get("value"))
    } else {
        target.get("stringUnit").and_then(|unit| unit.get("value"))
    }
    .and_then(serde_json::Value::as_str)
    .ok_or_else(|| {
        error(
            "INVALID_SKELETON",
            "Missing target-owned Xcode substitution root",
        )
    })?;
    let substitutions = xcstrings_substitutions(message).expect("Xcode substitution metadata");
    let mut owned: HashMap<&str, VecDeque<&str>> = HashMap::new();
    for marker in xcstrings_skeleton_substitution_pattern().captures_iter(original) {
        owned
            .entry(marker.get(1).expect("target substitution name").as_str())
            .or_default()
            .push_back(marker.get(0).expect("target substitution marker").as_str());
    }
    let mut restored = String::new();
    let mut previous = 0;
    for argument in xcstrings_skeleton_argument_pattern().captures_iter(translated) {
        let selector = argument.get(1).expect("translated selector").as_str();
        if !substitutions.contains_key(selector) {
            continue;
        }
        let marker = owned
            .get_mut(selector)
            .and_then(VecDeque::pop_front)
            .ok_or_else(|| {
                error(
                    "INVALID_SKELETON_SUBSTITUTION",
                    "Duplicated Xcode target substitution",
                )
            })?;
        let token = argument.get(0).expect("target selector token");
        restored.push_str(&translated[previous..token.start()]);
        restored.push_str(marker);
        previous = token.end();
    }
    restored.push_str(&translated[previous..]);
    if owned.values().any(|markers| !markers.is_empty()) {
        return Err(error(
            "INVALID_SKELETON_SUBSTITUTION",
            "Missing Xcode target substitution",
        ));
    }
    Ok(crate::apple_xcstrings_writer::restore(&restored, message))
}

fn restore_xcstrings_target_substitution_category(
    translated: &str,
    message: &Message,
    locale: &str,
    selector: &str,
    category: &str,
) -> Result<String, ParseError> {
    let normalized_locale = locale.replace('_', "-");
    let definition = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleLocalizationSources"))
        .and_then(|localizations| localizations.get(&normalized_locale))
        .and_then(|target| target.get("substitutions"))
        .and_then(|substitutions| substitutions.get(selector))
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing target-owned Xcode substitution",
            )
        })?;
    restore_xcstrings_target_substitution_definition(translated, definition, selector, category)
}

fn restore_xcstrings_target_substitution_definition(
    translated: &str,
    definition: &serde_json::Value,
    selector: &str,
    category: &str,
) -> Result<String, ParseError> {
    let original = definition
        .get("variations")
        .and_then(|variations| variations.get("plural"))
        .and_then(|plural| plural.get(category))
        .and_then(|branch| branch.get("stringUnit"))
        .and_then(|unit| unit.get("value"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing target-owned Xcode substitution category",
            )
        })?;
    let position = definition
        .get("argNum")
        .and_then(serde_json::Value::as_u64)
        .filter(|position| *position > 0)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing target substitution argument"))?
        as usize;
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
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(xcstrings_target_disabled_conversions(
                &normalized,
                translated,
                disabled,
            )),
        );
        normalized = translated.to_owned();
    }
    let scoped = Message::new(normalized, None, None, placeholders, metadata);
    Ok(crate::apple_xcstrings_writer::restore(translated, &scoped))
}

fn restore_xcstrings_target_plural_value(
    translated: &str,
    original: &str,
) -> Result<String, ParseError> {
    let mut placeholders = Vec::new();
    let mut normalized = crate::placeholders::normalize_foundation_plural(
        original,
        &mut placeholders,
        "count",
        None,
    );
    let conversions =
        crate::placeholders::foundation_plural_printf_line_separators(original, "count", None);
    let mut metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_foundation_conversions(&normalized, &conversions);
        normalized = visible;
        metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(xcstrings_target_disabled_conversions(
                &normalized,
                translated,
                disabled,
            )),
        );
        normalized = translated.to_owned();
    }
    let scoped = Message::new(normalized, None, None, placeholders, metadata);
    Ok(crate::apple_xcstrings_writer::restore(translated, &scoped))
}

fn xcstrings_target_plural_categories(catalog: &Catalog, locale: &str) -> HashSet<String> {
    let normalized_locale = locale.replace('_', "-");
    let evidence: HashSet<_> = catalog
        .messages
        .values()
        .filter(|message| message.variants.is_some())
        .flat_map(|message| {
            let localization = message
                .metadata
                .as_ref()
                .and_then(|metadata| metadata.get("appleLocalizationSources"))
                .and_then(|localizations| localizations.get(&normalized_locale))
                .and_then(|localization| localization.get("variations"));
            let mut result = Vec::new();
            if let Some(plural) = localization
                .and_then(|variations| variations.get("plural"))
                .and_then(serde_json::Value::as_object)
            {
                result.extend(plural.keys().cloned());
            }
            if let Some(devices) = localization
                .and_then(|variations| variations.get("device"))
                .and_then(serde_json::Value::as_object)
            {
                for device in devices.values() {
                    if let Some(plural) = device
                        .get("variations")
                        .and_then(|variations| variations.get("plural"))
                        .and_then(serde_json::Value::as_object)
                    {
                        result.extend(plural.keys().cloned());
                    }
                }
            }
            result
        })
        .collect();
    if !evidence.is_empty() {
        return evidence;
    }
    if catalog.messages.values().any(|message| {
        message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("appleLocalizationSources"))
            .and_then(|localizations| localizations.get(&normalized_locale))
            .is_some_and(serde_json::Value::is_object)
    }) {
        return HashSet::new();
    }
    cardinal_categories(locale)
}

pub(crate) fn cardinal_categories(locale: &str) -> HashSet<String> {
    let normalized = locale.replace('_', "-");
    let (language, remaining) = normalized.split_once('-').unwrap_or((&normalized, ""));
    let canonical_language = match language.to_ascii_lowercase().as_str() {
        "iw" => "he",
        "in" => "id",
        "ji" => "yi",
        "und" => return HashSet::new(),
        _ => language,
    };
    let canonical = if remaining.is_empty() {
        canonical_language.to_owned()
    } else {
        format!("{canonical_language}-{remaining}")
    };
    mojito_mf2::cardinal_plural_categories(&canonical)
        .unwrap_or_default()
        .iter()
        .map(|category| (*category).to_owned())
        .collect()
}

fn inserted_xcstrings_target_device(
    translation: &str,
    message: &Message,
    catalog: &Catalog,
    locale: &str,
) -> Result<serde_json::Value, ParseError> {
    let branches = xcstrings_target_device_branches(translation)?;
    let devices = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("sourceVariationAxes"))
        .and_then(|variations| variations.get("device"))
        .and_then(serde_json::Value::as_object)
        .filter(|devices| !devices.is_empty())
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing source-owned Xcode device templates",
            )
        })?;
    let expected: HashSet<_> = devices
        .keys()
        .map(String::as_str)
        .chain(std::iter::once("other"))
        .collect();
    let actual: HashSet<_> = branches.keys().map(String::as_str).collect();
    if actual != expected {
        return Err(error(
            "INVALID_SKELETON",
            "Xcode target devices differ from source-owned templates",
        ));
    }
    let fallback = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("defaultDevice"))
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing Xcode default device"))?;
    let explicit_fallback = devices.contains_key("other");
    if !devices.contains_key(fallback)
        || !explicit_fallback && branches.get("other") != branches.get(fallback)
    {
        return Err(error(
            "INVALID_SKELETON",
            "Xcode device fallback must match its default branch",
        ));
    }
    let placeholders: HashSet<_> = message
        .placeholders
        .iter()
        .flatten()
        .map(|placeholder| placeholder.name.as_str())
        .collect();
    let mut inserted = serde_json::Map::new();
    for (device, value) in branches {
        if device == "other" && !explicit_fallback {
            continue;
        }
        let source = devices
            .get(&device)
            .expect("target device matches source-owned branches");
        if message.variants.is_none() {
            validate_xcstrings_target_device_arguments(&value, &placeholders)?;
            let original = source
                .get("stringUnit")
                .and_then(|unit| unit.get("value"))
                .and_then(serde_json::Value::as_str)
                .ok_or_else(|| {
                    error(
                        "INVALID_SKELETON",
                        "Missing source-owned Xcode device scalar template",
                    )
                })?;
            inserted.insert(
                device,
                serde_json::json!({
                    "stringUnit": {
                        "state": "translated",
                        "value": restore_xcstrings_target_device_root_value(&value, original)?
                    }
                }),
            );
            continue;
        }
        let categories = xcstrings_target_plural_branches(&value)?;
        if !categories.iter().any(|(category, _)| category == "other") {
            return Err(error(
                "MISSING_OTHER_VARIANT",
                "Xcode target device plural requires other",
            ));
        }
        let actual: HashSet<_> = categories
            .iter()
            .map(|(category, _)| category.clone())
            .collect();
        if actual != xcstrings_target_plural_categories(catalog, locale) {
            return Err(error(
                "INVALID_SKELETON",
                "Xcode target device plural lacks native categories",
            ));
        }
        let originals = source
            .get("variations")
            .and_then(|variations| variations.get("plural"))
            .and_then(serde_json::Value::as_object)
            .ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Missing source-owned Xcode device plural template",
                )
            })?;
        let mut plural = serde_json::Map::new();
        for (category, value) in categories {
            validate_xcstrings_target_device_arguments(&value, &placeholders)?;
            let source_category = if message
                .variants
                .as_ref()
                .expect("source-owned device plural branches")
                .contains_key(&category)
            {
                category.as_str()
            } else {
                "other"
            };
            let original = originals
                .get(source_category)
                .and_then(|branch| branch.get("stringUnit"))
                .and_then(|unit| unit.get("value"))
                .and_then(serde_json::Value::as_str)
                .ok_or_else(|| {
                    error(
                        "INVALID_SKELETON",
                        "Missing source-owned Xcode device plural template",
                    )
                })?;
            plural.insert(
                category,
                serde_json::json!({
                    "stringUnit": {
                        "state": "translated",
                        "value": restore_xcstrings_target_plural_value(&value, original)?
                    }
                }),
            );
        }
        inserted.insert(
            device,
            serde_json::json!({"variations": {"plural": plural}}),
        );
    }
    Ok(serde_json::json!({"variations": {"device": inserted}}))
}

fn validate_xcstrings_target_device_arguments(
    value: &str,
    placeholders: &HashSet<&str>,
) -> Result<(), ParseError> {
    for argument in xcstrings_skeleton_argument_pattern().captures_iter(value) {
        if !placeholders.contains(argument.get(1).expect("device argument").as_str()) {
            return Err(error(
                "INVALID_PLACEHOLDER",
                "Unknown Xcode target device argument",
            ));
        }
    }
    let stripped = xcstrings_skeleton_argument_pattern().replace_all(value, "");
    if stripped.contains(['{', '}']) {
        return Err(error(
            "INVALID_SKELETON",
            "Unsupported nested Xcode target device argument",
        ));
    }
    Ok(())
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct XcstringsSubstitutionTranslation {
    root: String,
    categories: BTreeMap<String, BTreeMap<String, String>>,
}

fn inserted_xcstrings_target_substitution(
    translation: &str,
    message: &Message,
    catalog: &Catalog,
    locale: &str,
) -> Result<serde_json::Value, ParseError> {
    let source = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleSourceLocalization"))
        .ok_or_else(|| error("INVALID_SKELETON", "Missing Xcode substitution source"))?;
    let source_devices = source
        .get("variations")
        .and_then(|variations| variations.get("device"))
        .and_then(serde_json::Value::as_object);
    let mut branches = BTreeMap::new();
    if let Some(devices) = source_devices {
        let translated = xcstrings_target_device_branches(translation)?;
        let required: HashSet<_> = devices
            .keys()
            .map(String::as_str)
            .chain(std::iter::once("other"))
            .collect();
        let actual: HashSet<_> = translated.keys().map(String::as_str).collect();
        let fallback = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("defaultDevice"))
            .and_then(serde_json::Value::as_str);
        if actual != required
            || fallback.is_none_or(|device| translated.get("other") != translated.get(device))
        {
            return Err(error(
                "INVALID_SKELETON",
                "Xcode target substitutions require all source devices",
            ));
        }
        for (device, branch) in translated {
            if device != "other" {
                branches.insert(
                    device,
                    xcstrings_target_substitution_branches(&branch, message)?,
                );
            }
        }
    } else {
        branches.insert(
            String::new(),
            xcstrings_target_substitution_branches(translation, message)?,
        );
    }
    let first = branches.values().next().expect("Xcode substitution roots");
    if branches
        .values()
        .any(|branch| branch.categories != first.categories)
    {
        return Err(error(
            "INVALID_SKELETON",
            "Device roots require identical shared substitution rules",
        ));
    }
    let placeholders: HashSet<_> = message
        .placeholders
        .iter()
        .flatten()
        .map(|placeholder| placeholder.name.as_str())
        .collect();
    let mut target = serde_json::Map::new();
    if source_devices.is_none() {
        validate_xcstrings_target_device_arguments(&first.root, &placeholders)?;
        target.insert(
            "stringUnit".to_owned(),
            serde_json::json!({
                "state": "translated",
                "value": restore_xcstrings_substitution_root_for_device(
                    &first.root, message, None
                )?
            }),
        );
    }

    let mut substitutions = serde_json::Map::new();
    let source_substitutions =
        xcstrings_substitutions(message).expect("source-owned Xcode substitution definitions");
    let selectors: BTreeMap<_, _> = source_substitutions.iter().collect();
    for (selector, original) in selectors {
        let evidence = xcstrings_target_substitution_evidence(catalog, locale, selector, message);
        let required: HashSet<String> = evidence
            .and_then(|definition| definition["variations"]["plural"].as_object())
            .map(|plural| plural.keys().cloned().collect())
            .unwrap_or_else(|| xcstrings_target_plural_categories(catalog, locale));
        if required.is_empty() {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Missing target substitution category evidence",
            ));
        }
        let categories = first.categories.get(selector).ok_or_else(|| {
            error(
                "INVALID_SKELETON_SUBSTITUTION",
                "Missing Xcode target substitution selector",
            )
        })?;
        if !categories.contains_key("other") {
            return Err(error(
                "MISSING_OTHER_VARIANT",
                "Xcode target substitution requires other",
            ));
        }
        let actual: HashSet<_> = categories.keys().cloned().collect();
        if actual != required {
            return Err(error(
                "INVALID_SKELETON",
                "Xcode target substitution categories differ from evidence",
            ));
        }
        let mut definition = original
            .as_object()
            .ok_or_else(|| error("INVALID_SKELETON", "Missing source substitution definition"))?
            .clone();
        let mut plural = serde_json::Map::new();
        for (category, value) in categories {
            validate_xcstrings_target_device_arguments(value, &placeholders)?;
            let source_category =
                if evidence.is_none() && original["variations"]["plural"].get(category).is_none() {
                    "other"
                } else {
                    category.as_str()
                };
            plural.insert(
                category.clone(),
                serde_json::json!({
                    "stringUnit": {
                        "state": "translated",
                        "value": restore_xcstrings_target_substitution_definition(
                            value,
                            evidence.unwrap_or(original),
                            selector,
                            source_category
                        )?
                    }
                }),
            );
        }
        definition
            .get_mut("variations")
            .and_then(serde_json::Value::as_object_mut)
            .ok_or_else(|| error("INVALID_SKELETON", "Missing source substitution plural"))?
            .insert("plural".to_owned(), serde_json::Value::Object(plural));
        substitutions.insert(selector.clone(), serde_json::Value::Object(definition));
    }
    target.insert(
        "substitutions".to_owned(),
        serde_json::Value::Object(substitutions),
    );

    if source_devices.is_some() {
        let mut devices = serde_json::Map::new();
        for (device, branch) in branches {
            validate_xcstrings_target_device_arguments(&branch.root, &placeholders)?;
            devices.insert(
                device.clone(),
                serde_json::json!({
                    "stringUnit": {
                        "state": "translated",
                        "value": restore_xcstrings_substitution_root_for_device(
                            &branch.root, message, Some(&device)
                        )?
                    }
                }),
            );
        }
        target.insert(
            "variations".to_owned(),
            serde_json::json!({"device": devices}),
        );
    }
    Ok(serde_json::Value::Object(target))
}

fn xcstrings_target_substitution_branches(
    translation: &str,
    message: &Message,
) -> Result<XcstringsSubstitutionTranslation, ParseError> {
    let selectors =
        xcstrings_substitutions(message).expect("source-owned Xcode substitution definitions");
    let mut categories = BTreeMap::new();
    let mut root = String::new();
    let mut cursor = 0;
    while let Some(relative) = translation[cursor..].find('{') {
        let opening = cursor + relative;
        root.push_str(&translation[cursor..opening]);
        let comma = translation[opening + 1..]
            .find(',')
            .map(|offset| opening + 1 + offset);
        let simple = translation[opening + 1..]
            .find('}')
            .map(|offset| opening + 1 + offset);
        if comma.is_none() || simple.is_some_and(|end| end < comma.expect("plural comma")) {
            let end = simple.ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Unclosed Xcode target substitution argument",
                )
            })?;
            root.push_str(&translation[opening..=end]);
            cursor = end + 1;
            continue;
        }
        let comma = comma.expect("Xcode target plural comma");
        let selector = translation[opening + 1..comma].trim();
        let prefix = format!("{{{selector}, plural,");
        if !translation[opening..].starts_with(&prefix) || !selectors.contains_key(selector) {
            return Err(error(
                "INVALID_SKELETON_SUBSTITUTION",
                "Unknown Xcode target substitution selector",
            ));
        }
        let mut end = opening + prefix.len();
        let mut depth = 1;
        while end < translation.len() && depth > 0 {
            match translation.as_bytes()[end] {
                b'{' => depth += 1,
                b'}' => depth -= 1,
                _ => {}
            }
            end += 1;
        }
        if depth != 0 {
            return Err(error(
                "INVALID_SKELETON",
                "Unclosed Xcode target substitution plural",
            ));
        }
        let plural = format!(
            "{{count, plural,{}",
            &translation[opening + prefix.len()..end]
        );
        let variants: BTreeMap<_, _> = xcstrings_target_plural_branches(&plural)?
            .into_iter()
            .collect();
        if let Some(previous) = categories.insert(selector.to_owned(), variants.clone()) {
            if previous != variants {
                return Err(error(
                    "INVALID_SKELETON",
                    "Repeated Xcode target substitution has different rules",
                ));
            }
        }
        root.push('{');
        root.push_str(selector);
        root.push('}');
        cursor = end;
    }
    root.push_str(&translation[cursor..]);
    let actual: HashSet<_> = categories.keys().map(String::as_str).collect();
    let required: HashSet<_> = selectors.keys().map(String::as_str).collect();
    if actual != required {
        return Err(error(
            "INVALID_SKELETON_SUBSTITUTION",
            "Missing Xcode target substitution selector",
        ));
    }
    Ok(XcstringsSubstitutionTranslation { root, categories })
}

fn xcstrings_target_substitution_evidence<'a>(
    catalog: &'a Catalog,
    locale: &str,
    selector: &str,
    source: &Message,
) -> Option<&'a serde_json::Value> {
    let required = xcstrings_substitutions(source)?.get(selector)?;
    for message in catalog.messages.values() {
        let Some(definition) = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("appleLocalizationSources"))
            .and_then(|localizations| localizations.get(locale.replace('_', "-")))
            .and_then(|localization| localization.get("substitutions"))
            .and_then(|substitutions| substitutions.get(selector))
        else {
            continue;
        };
        if definition.get("argNum") == required.get("argNum")
            && definition.get("formatSpecifier") == required.get("formatSpecifier")
            && definition
                .get("variations")
                .and_then(|variations| variations.get("plural"))
                .and_then(serde_json::Value::as_object)
                .is_some_and(|plural| plural.contains_key("other"))
        {
            return Some(definition);
        }
    }
    None
}

fn inserted_xcstrings_target_plural(
    translation: &str,
    message: &Message,
    catalog: &Catalog,
    locale: &str,
) -> Result<serde_json::Value, ParseError> {
    let branches = xcstrings_target_plural_branches(translation)?;
    if !branches.iter().any(|(category, _)| category == "other") {
        return Err(error(
            "MISSING_OTHER_VARIANT",
            "Xcode target plural insertion requires other",
        ));
    }
    let actual: HashSet<_> = branches
        .iter()
        .map(|(category, _)| category.clone())
        .collect();
    if actual != xcstrings_target_plural_categories(catalog, locale) {
        return Err(error(
            "INVALID_SKELETON",
            "Xcode target plural categories differ from native evidence",
        ));
    }
    let placeholders: HashSet<_> = message
        .placeholders
        .iter()
        .flatten()
        .map(|placeholder| placeholder.name.as_str())
        .collect();
    let originals = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("appleSourceLocalization"))
        .and_then(|source| source.get("variations"))
        .and_then(|variations| variations.get("plural"))
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Missing source-owned target plural template",
            )
        })?;
    let mut plural = serde_json::Map::new();
    for (category, value) in branches {
        for argument in xcstrings_skeleton_argument_pattern().captures_iter(&value) {
            if !placeholders.contains(argument.get(1).expect("plural argument").as_str()) {
                return Err(error(
                    "INVALID_PLACEHOLDER",
                    "Unknown Xcode target plural argument",
                ));
            }
        }
        let stripped = xcstrings_skeleton_argument_pattern().replace_all(&value, "");
        if stripped.contains(['{', '}']) {
            return Err(error(
                "INVALID_SKELETON",
                "Unsupported nested Xcode target plural argument",
            ));
        }
        let source_category = if message
            .variants
            .as_ref()
            .expect("source-owned plural branches")
            .contains_key(&category)
        {
            category.as_str()
        } else {
            "other"
        };
        let original = originals
            .get(source_category)
            .and_then(|branch| branch.get("stringUnit"))
            .and_then(|unit| unit.get("value"))
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Missing source-owned target plural template",
                )
            })?;
        plural.insert(
            category,
            serde_json::json!({
                "stringUnit": {
                    "state": "translated",
                    "value": restore_xcstrings_target_plural_value(&value, original)?
                }
            }),
        );
    }
    Ok(serde_json::json!({"variations": {"plural": plural}}))
}

fn xcstrings_target_plural_branches(source: &str) -> Result<Vec<(String, String)>, ParseError> {
    let prefix = "{count, plural,";
    let Some(mut cursor) = source.strip_prefix(prefix).map(|_| prefix.len()) else {
        return Err(error(
            "INVALID_SKELETON",
            "Xcode target insertion requires a complete ICU plural",
        ));
    };
    let bytes = source.as_bytes();
    let mut result = Vec::new();
    let mut seen = HashSet::new();
    let mut closed = false;
    while cursor < bytes.len() {
        while bytes.get(cursor).is_some_and(u8::is_ascii_whitespace) {
            cursor += 1;
        }
        if bytes.get(cursor) == Some(&b'}') {
            cursor += 1;
            closed = true;
            break;
        }
        let beginning = cursor;
        while bytes.get(cursor).is_some_and(u8::is_ascii_alphabetic) {
            cursor += 1;
        }
        let category = &source[beginning..cursor];
        if !matches!(category, "zero" | "one" | "two" | "few" | "many" | "other") {
            return Err(error(
                "INVALID_PLURAL_CATEGORY",
                "Unsupported Xcode target plural category",
            ));
        }
        while bytes.get(cursor).is_some_and(u8::is_ascii_whitespace) {
            cursor += 1;
        }
        if bytes.get(cursor) != Some(&b'{') {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid Xcode target plural branch",
            ));
        }
        cursor += 1;
        let start = cursor;
        let mut depth = 1;
        while cursor < bytes.len() && depth > 0 {
            match bytes[cursor] {
                b'{' => depth += 1,
                b'}' => depth -= 1,
                _ => {}
            }
            cursor += 1;
        }
        if depth != 0 || !seen.insert(category.to_owned()) {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid or duplicated Xcode target plural branch",
            ));
        }
        result.push((category.to_owned(), source[start..cursor - 1].to_owned()));
    }
    if !closed || cursor != bytes.len() {
        return Err(error(
            "INVALID_SKELETON",
            "Trailing Xcode target plural content",
        ));
    }
    Ok(result)
}

fn xcstrings_target_device_branches(
    source: &str,
) -> Result<std::collections::BTreeMap<String, String>, ParseError> {
    let prefix = "{device, select,";
    let Some(mut cursor) = source.strip_prefix(prefix).map(|_| prefix.len()) else {
        return Err(error(
            "INVALID_SKELETON",
            "Xcode target device insertion requires a complete ICU select",
        ));
    };
    let bytes = source.as_bytes();
    let mut result = std::collections::BTreeMap::new();
    let mut closed = false;
    while cursor < bytes.len() {
        while bytes.get(cursor).is_some_and(u8::is_ascii_whitespace) {
            cursor += 1;
        }
        if bytes.get(cursor) == Some(&b'}') {
            cursor += 1;
            closed = true;
            break;
        }
        let remaining = &source[cursor..];
        let Some(identifier) = xcstrings_skeleton_device_identity().find(remaining) else {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid Xcode target device identity",
            ));
        };
        let device = identifier.as_str();
        cursor += device.len();
        while bytes.get(cursor).is_some_and(u8::is_ascii_whitespace) {
            cursor += 1;
        }
        if bytes.get(cursor) != Some(&b'{') {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid Xcode target device branch",
            ));
        }
        cursor += 1;
        let start = cursor;
        let mut depth = 1;
        while cursor < bytes.len() && depth > 0 {
            match bytes[cursor] {
                b'{' => depth += 1,
                b'}' => depth -= 1,
                _ => {}
            }
            cursor += 1;
        }
        if depth != 0
            || result
                .insert(device.to_owned(), source[start..cursor - 1].to_owned())
                .is_some()
        {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid or duplicated Xcode target device branch",
            ));
        }
    }
    if !closed || cursor != bytes.len() {
        return Err(error(
            "INVALID_SKELETON",
            "Trailing Xcode target device-select content",
        ));
    }
    Ok(result)
}

fn xcstrings_target_disabled_conversions(
    original: &str,
    translated: &str,
    disabled: Vec<serde_json::Value>,
) -> Vec<serde_json::Value> {
    let source_length = original.chars().count();
    let target_length = translated.chars().count();
    disabled
        .into_iter()
        .map(|value| {
            let mut occurrence = value
                .as_object()
                .expect("target-owned disabled Foundation conversion")
                .clone();
            let position = occurrence["position"]
                .as_u64()
                .expect("target-owned disabled Foundation position")
                as usize;
            let mut target_position = if source_length == 0 {
                0
            } else {
                (position * target_length + source_length / 2) / source_length
            };
            let mut previous = None;
            for captures in xcstrings_skeleton_argument_pattern().captures_iter(original) {
                let matched = captures.get(0).expect("target-owned argument match");
                let end = original[..matched.end()].chars().count();
                if end > position {
                    break;
                }
                previous = Some((
                    captures
                        .get(1)
                        .expect("target-owned argument identity")
                        .as_str(),
                    end,
                ));
            }
            if let Some((argument, end)) = previous {
                for captures in xcstrings_skeleton_argument_pattern().captures_iter(translated) {
                    if captures
                        .get(1)
                        .expect("translated argument identity")
                        .as_str()
                        == argument
                    {
                        let matched = captures.get(0).expect("translated argument match");
                        target_position =
                            translated[..matched.end()].chars().count() + position - end;
                        break;
                    }
                }
            }
            occurrence.insert(
                "position".into(),
                serde_json::Value::from(target_position.min(target_length)),
            );
            serde_json::Value::Object(occurrence)
        })
        .collect()
}

fn restore_xcstrings_substitution_root_for_device(
    translated: &str,
    message: &Message,
    device_override: Option<&str>,
) -> Result<String, ParseError> {
    let metadata = message.metadata.as_ref().ok_or_else(|| {
        error(
            "INVALID_SKELETON",
            "Missing Xcode substitution source metadata",
        )
    })?;
    let mut localization = metadata.get("appleSourceLocalization").ok_or_else(|| {
        error(
            "INVALID_SKELETON",
            "Missing Xcode substitution source localization",
        )
    })?;
    if !localization["stringUnit"]["value"].is_string() {
        if let Some(device) = device_override.or_else(|| {
            metadata
                .get("defaultDevice")
                .and_then(serde_json::Value::as_str)
        }) {
            localization = &localization["variations"]["device"][device];
        }
    }
    let original = localization["stringUnit"]["value"]
        .as_str()
        .ok_or_else(|| error("INVALID_SKELETON", "Missing Xcode substitution root value"))?;
    let substitutions = xcstrings_substitutions(message).expect("Xcode substitution metadata");
    let mut owned: HashMap<&str, VecDeque<&str>> = HashMap::new();
    for marker in xcstrings_skeleton_substitution_pattern().captures_iter(original) {
        owned
            .entry(marker.get(1).expect("Xcode substitution name").as_str())
            .or_default()
            .push_back(marker.get(0).expect("Xcode substitution marker").as_str());
    }

    let mut restored = String::new();
    let mut previous = 0;
    for argument in xcstrings_skeleton_argument_pattern().captures_iter(translated) {
        let identity = argument.get(1).expect("Xcode selector name").as_str();
        if !substitutions.contains_key(identity) {
            continue;
        }
        let marker = owned
            .get_mut(identity)
            .and_then(VecDeque::pop_front)
            .ok_or_else(|| {
                error(
                    "INVALID_SKELETON_SUBSTITUTION",
                    "Duplicated Xcode substitution marker",
                )
            })?;
        let token = argument.get(0).expect("Xcode selector token");
        restored.push_str(&translated[previous..token.start()]);
        restored.push_str(marker);
        previous = token.end();
    }
    restored.push_str(&translated[previous..]);
    if owned.values().any(|markers| !markers.is_empty()) {
        return Err(error(
            "INVALID_SKELETON_SUBSTITUTION",
            "Missing Xcode substitution marker",
        ));
    }
    if device_override.is_none() {
        return Ok(crate::apple_xcstrings_writer::restore(&restored, message));
    }
    let mut placeholders = Vec::new();
    crate::apple::normalize_xcstrings_source(
        original,
        metadata.get("sourceSubstitutions"),
        &mut placeholders,
    )?;
    let mut normalized = crate::placeholders::normalize(original, &mut Vec::new(), None);
    let conversions = crate::placeholders::printf_line_separators(original);
    let mut scoped_metadata = serde_json::Map::new();
    if !conversions.is_empty() {
        let (visible, disabled) =
            crate::apple::without_disabled_printf_conversions(&normalized, &conversions);
        normalized = visible;
        scoped_metadata.insert(
            "appleDisabledPrintfConversions".into(),
            serde_json::Value::Array(disabled),
        );
    }
    let scoped = Message::new(normalized, None, None, placeholders, scoped_metadata);
    Ok(crate::apple_xcstrings_writer::restore(&restored, &scoped))
}

fn xcstrings_skeleton_substitution_pattern() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| Regex::new(r"%(?:\d+\$)?#@([^@]+)@").expect("valid Xcode marker"))
}

fn xcstrings_skeleton_argument_pattern() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new(r"\{([\p{L}\p{N}\p{M}\p{So}_]+)\}").expect("valid Xcode selector")
    })
}

fn xcstrings_skeleton_device_identity() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new(r"^[^\p{White_Space}\p{Pattern_Syntax}]+").expect("valid Xcode device identity")
    })
}

struct XcodeScanner<'a> {
    source: &'a str,
    encoding: Encoding,
    expected: &'a HashMap<Vec<String>, XcodeIdentity>,
    slots: Vec<SourceSlot>,
    index: usize,
}

impl XcodeScanner<'_> {
    fn value(&mut self, path: &mut Vec<String>) -> Result<(), ParseError> {
        self.whitespace();
        match self.source.as_bytes().get(self.index) {
            Some(b'{') => {
                self.index += 1;
                self.whitespace();
                if self.consume(b'}') {
                    return Ok(());
                }
                loop {
                    self.whitespace();
                    let (start, end) = self.string()?;
                    let key: String = serde_json::from_str(&self.source[start..=end])
                        .map_err(|_| error("INVALID_SKELETON", "Invalid Xcode JSON field name"))?;
                    self.whitespace();
                    if !self.consume(b':') {
                        return Err(error(
                            "INVALID_SKELETON",
                            "Missing Xcode JSON field separator",
                        ));
                    }
                    path.push(key);
                    self.value(path)?;
                    path.pop();
                    let insertion = self.index;
                    self.whitespace();
                    if self.consume(b'}') {
                        if let Some(identity) = self.expected.get(path) {
                            let offset = self.encoding.offset(self.source, insertion);
                            self.slots.push(SourceSlot {
                                id: identity.id.clone(),
                                selector: identity.selector.clone(),
                                variant: identity.variant.clone(),
                                start: offset,
                                end: offset,
                                apple_object_index: None,
                            });
                        }
                        break;
                    }
                    if !self.consume(b',') {
                        return Err(error(
                            "INVALID_SKELETON",
                            "Missing Xcode JSON field boundary",
                        ));
                    }
                }
            }
            Some(b'[') => {
                self.index += 1;
                self.whitespace();
                if self.consume(b']') {
                    return Ok(());
                }
                let mut position = 0;
                loop {
                    path.push(position.to_string());
                    self.value(path)?;
                    path.pop();
                    self.whitespace();
                    if self.consume(b']') {
                        break;
                    }
                    if !self.consume(b',') {
                        return Err(error(
                            "INVALID_SKELETON",
                            "Missing Xcode JSON array boundary",
                        ));
                    }
                    position += 1;
                }
            }
            Some(b'"') => {
                let (start, end) = self.string()?;
                if let Some(identity) = self.expected.get(path) {
                    self.slots.push(SourceSlot {
                        id: identity.id.clone(),
                        selector: identity.selector.clone(),
                        variant: identity.variant.clone(),
                        start: self.encoding.offset(self.source, start + 1),
                        end: self.encoding.offset(self.source, end),
                        apple_object_index: None,
                    });
                }
            }
            Some(_) => {
                let start = self.index;
                while self.index < self.source.len()
                    && !matches!(
                        self.source.as_bytes()[self.index],
                        b',' | b'}' | b']' | b' ' | b'\t' | b'\r' | b'\n'
                    )
                {
                    self.index += 1;
                }
                if &self.source[start..self.index] == "null" {
                    if let Some(identity) = self.expected.get(path) {
                        self.slots.push(SourceSlot {
                            id: identity.id.clone(),
                            selector: identity.selector.clone(),
                            variant: identity.variant.clone(),
                            start: self.encoding.offset(self.source, start),
                            end: self.encoding.offset(self.source, self.index),
                            apple_object_index: None,
                        });
                    }
                }
            }
            None => return Err(error("INVALID_SKELETON", "Unexpected end of Xcode JSON")),
        }
        Ok(())
    }

    fn string(&mut self) -> Result<(usize, usize), ParseError> {
        if !self.consume(b'"') {
            return Err(error("INVALID_SKELETON", "Missing Xcode JSON string"));
        }
        let start = self.index - 1;
        let mut escaped = false;
        while self.index < self.source.len() {
            let current = self.source.as_bytes()[self.index];
            self.index += 1;
            if escaped {
                escaped = false;
            } else if current == b'\\' {
                escaped = true;
            } else if current == b'"' {
                return Ok((start, self.index - 1));
            }
        }
        Err(error("INVALID_SKELETON", "Unterminated Xcode JSON string"))
    }

    fn whitespace(&mut self) {
        while self.index < self.source.len()
            && matches!(
                self.source.as_bytes()[self.index],
                b' ' | b'\t' | b'\r' | b'\n'
            )
        {
            self.index += 1;
        }
    }

    fn consume(&mut self, expected: u8) -> bool {
        if self.source.as_bytes().get(self.index) == Some(&expected) {
            self.index += 1;
            true
        } else {
            false
        }
    }
}

fn extract_gettext(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    let charset = crate::gettext_charset(bytes)?;
    let encoding = match charset {
        "UTF-8" => Encoding::Utf8,
        "ISO-8859-1" => Encoding::Latin1,
        "CP1252" => Encoding::Cp1252,
        "US-ASCII" => Encoding::Ascii,
        _ => {
            return Err(error(
                "UNSUPPORTED_SKELETON_ENCODING",
                "Unsupported native gettext source encoding",
            ));
        }
    };
    let source = crate::decode(bytes, Some(charset))?;
    let catalog = crate::parse(FileFormat::GettextPo, bytes)?;
    let slots = GettextScanner::new(&source, encoding, charset, &catalog).scan()?;
    let expected: usize = catalog
        .messages
        .values()
        .map(|message| {
            if message.variants.is_none() {
                1
            } else {
                message
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.get("gettextPluralIndexes"))
                    .and_then(serde_json::Value::as_object)
                    .map_or(0, serde_json::Map::len)
            }
        })
        .sum();
    if slots.len() != expected {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Missing native gettext translation slot",
        ));
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::GettextPo.id(),
        encoding: encoding.name().to_owned(),
        source,
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: None,
        slots,
    })
}

fn render_gettext(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let catalog = crate::parse(FileFormat::GettextPo, &original)?;
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if !known.insert(slot.key()) {
            return Err(error(
                "INVALID_SKELETON",
                "Duplicate native gettext source slot",
            ));
        }
    }
    if translations.keys().any(|key| !known.contains(key)) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no native gettext source slot",
        ));
    }
    let mut output = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid native gettext source range",
            ));
        }
        output.extend_from_slice(&original[previous..slot.start]);
        if let Some(translated) = translations.get(&slot.key()) {
            let message = catalog.messages.get(&slot.id).ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Missing native gettext source descriptor",
                )
            })?;
            let plural_index = gettext_plural_index(message, slot.variant.as_deref())?;
            let native = crate::gettext_writer::restore(message, translated, plural_index);
            let quoted = crate::gettext_writer::quote(&native)?;
            if matches!(
                encoding,
                Encoding::Latin1 | Encoding::Cp1252 | Encoding::Ascii
            ) && quoted.chars().any(|character| {
                crate::encode_gettext_character(character, encoding.name()).is_err()
            }) {
                return Err(error(
                    "INVALID_GETTEXT_ENCODING",
                    "Translation cannot use the original PO charset",
                ));
            }
            let source = encoding.decode(&original[slot.start..slot.end])?;
            let replacement = preserve_gettext_quotes(&source, &quoted)?;
            output.extend(encoding.encode_without_bom(&replacement));
        } else {
            output.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    output.extend_from_slice(&original[previous..]);
    Ok(output)
}

fn gettext_plural_index(
    message: &crate::model::Message,
    variant: Option<&str>,
) -> Result<Option<usize>, ParseError> {
    let Some(variant) = variant else {
        if message.variants.is_some() {
            return Err(error(
                "INVALID_SKELETON",
                "Plural gettext source slot has no native variant",
            ));
        }
        return Ok(None);
    };
    let indexes = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("gettextPluralIndexes"))
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing native gettext plural indexes"))?;
    indexes
        .iter()
        .find(|(_, category)| category.as_str() == Some(variant))
        .and_then(|(index, _)| index.parse().ok())
        .map(Some)
        .ok_or_else(|| error("INVALID_SKELETON", "Missing native gettext plural index"))
}

fn preserve_gettext_quotes(source: &str, quoted: &str) -> Result<String, ParseError> {
    let mut ranges = Vec::new();
    let mut index = 0;
    while index < source.len() {
        if source.as_bytes()[index] != b'"' {
            index += source[index..].chars().next().unwrap().len_utf8();
            continue;
        }
        let end = gettext_quote_end(source, index)?;
        ranges.push((index, end));
        index = end;
    }
    if ranges.is_empty() {
        return Err(error(
            "INVALID_SKELETON",
            "Native gettext source slot has no quoted C string",
        ));
    }
    let content = &quoted[1..quoted.len() - 1];
    let mut output = String::new();
    let mut previous = 0;
    let mut position = 0;
    for (index, (start, end)) in ranges.iter().enumerate() {
        output.push_str(&source[previous..*start]);
        output.push('"');
        let mut next = if index + 1 == ranges.len() {
            content.len()
        } else {
            let width = source[start + 1..end - 1].chars().count();
            content[position..]
                .char_indices()
                .nth(width)
                .map_or(content.len(), |(offset, _)| position + offset)
        };
        while next < content.len()
            && content[position..next]
                .chars()
                .rev()
                .take_while(|value| *value == '\\')
                .count()
                % 2
                == 1
        {
            next += content[next..].chars().next().unwrap().len_utf8();
        }
        output.push_str(&content[position..next]);
        output.push('"');
        position = next;
        previous = *end;
    }
    output.push_str(&source[previous..]);
    Ok(output)
}

fn gettext_quote_end(source: &str, start: usize) -> Result<usize, ParseError> {
    let bytes = source.as_bytes();
    let mut index = start + 1;
    while index < bytes.len() {
        if bytes[index] == b'\\' {
            index += 1;
        } else if bytes[index] == b'"' {
            return Ok(index + 1);
        }
        index += 1;
    }
    Err(error(
        "INVALID_SKELETON",
        "Unterminated native gettext C string",
    ))
}

struct GettextScanner<'a> {
    source: &'a str,
    encoding: Encoding,
    charset: &'a str,
    catalog: &'a Catalog,
    domain: Option<String>,
    context: Option<String>,
    id: Option<String>,
    active: Option<GettextDirective>,
    slots: Vec<SourceSlot>,
}

impl<'a> GettextScanner<'a> {
    fn new(source: &'a str, encoding: Encoding, charset: &'a str, catalog: &'a Catalog) -> Self {
        Self {
            source,
            encoding,
            charset,
            catalog,
            domain: None,
            context: None,
            id: None,
            active: None,
            slots: Vec::new(),
        }
    }

    fn scan(mut self) -> Result<Vec<SourceSlot>, ParseError> {
        let mut index = 0;
        let mut line_start = true;
        while index < self.source.len() {
            let character = self.source[index..].chars().next().unwrap();
            if matches!(character, '\n' | '\r') {
                index += character.len_utf8();
                line_start = true;
                continue;
            }
            if gettext_horizontal(character) {
                index += character.len_utf8();
                continue;
            }
            if character == '#' && line_start {
                self.finish()?;
                if self.id.is_some() {
                    self.id = None;
                    self.context = None;
                }
                index = self.source[index..]
                    .find(['\r', '\n'])
                    .map_or(self.source.len(), |offset| index + offset);
                continue;
            }
            line_start = false;
            if character == '"' {
                let end = gettext_quote_end(self.source, index)?;
                self.active
                    .as_mut()
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Unowned gettext continuation",
                        )
                    })?
                    .append(self.source, index, end, self.charset)?;
                index = end;
                continue;
            }
            self.finish()?;
            let start = index;
            while self.source[index..]
                .chars()
                .next()
                .is_some_and(|value| value.is_ascii_lowercase() || value == '_')
            {
                index += 1;
            }
            let mut keyword = self.source[start..index].to_owned();
            while self.source[index..]
                .chars()
                .next()
                .is_some_and(gettext_horizontal)
            {
                index += 1;
            }
            if keyword == "msgstr" && self.source[index..].starts_with('[') {
                index += 1;
                let begin = index;
                while index < self.source.len() && self.source.as_bytes()[index] != b']' {
                    index += 1;
                }
                if index == self.source.len() {
                    return Err(error(
                        "INVALID_SKELETON",
                        "Unterminated native gettext plural index",
                    ));
                }
                keyword.push('[');
                keyword.push_str(self.source[begin..index].trim());
                keyword.push(']');
                index += 1;
                while self.source[index..]
                    .chars()
                    .next()
                    .is_some_and(gettext_horizontal)
                {
                    index += 1;
                }
            }
            if !self.source[index..].starts_with('"') {
                return Err(error(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Native gettext directive has no C string",
                ));
            }
            if matches!(keyword.as_str(), "msgctxt" | "msgid") && self.id.is_some() {
                self.id = None;
                self.context = None;
            }
            let end = gettext_quote_end(self.source, index)?;
            let mut directive = GettextDirective::new(keyword);
            directive.append(self.source, index, end, self.charset)?;
            self.active = Some(directive);
            index = end;
        }
        self.finish()?;
        Ok(self.slots)
    }

    fn finish(&mut self) -> Result<(), ParseError> {
        let Some(directive) = self.active.take() else {
            return Ok(());
        };
        match directive.keyword.as_str() {
            "domain" => {
                self.domain = Some(directive.value);
                self.id = None;
                self.context = None;
            }
            "msgctxt" => self.context = Some(directive.value),
            "msgid" => self.id = Some(directive.value),
            "msgstr" => self.add(directive, None)?,
            keyword if keyword.starts_with("msgstr[") => {
                let index = keyword[7..keyword.len() - 1]
                    .parse()
                    .map_err(|_| error("INVALID_SKELETON", "Invalid native plural index"))?;
                self.add(directive, Some(index))?;
            }
            _ => {}
        }
        Ok(())
    }

    fn add(&mut self, directive: GettextDirective, index: Option<usize>) -> Result<(), ParseError> {
        if self.id.is_none()
            || self.id.as_ref().is_some_and(String::is_empty) && self.context.is_none()
        {
            return Ok(());
        }
        let identity = self.resolve()?;
        let descriptor = &self.catalog.messages[&identity];
        let variant = if let Some(index) = index {
            Some(
                descriptor
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.get("gettextPluralIndexes"))
                    .and_then(serde_json::Value::as_object)
                    .and_then(|indexes| indexes.get(&index.to_string()))
                    .and_then(serde_json::Value::as_str)
                    .ok_or_else(|| {
                        error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Unmapped native gettext plural index",
                        )
                    })?
                    .to_owned(),
            )
        } else {
            None
        };
        self.slots.push(SourceSlot {
            id: identity,
            selector: None,
            variant,
            start: self.encoding.offset(self.source, directive.start),
            end: self.encoding.offset(self.source, directive.end),
            apple_object_index: None,
        });
        Ok(())
    }

    fn resolve(&self) -> Result<String, ParseError> {
        let identity = self.context.as_deref().unwrap_or_else(|| {
            self.id
                .as_deref()
                .expect("gettext source slot has an identity")
        });
        for (id, message) in &self.catalog.messages {
            let metadata = message.metadata.as_ref();
            let original = metadata
                .and_then(|values| values.get("gettextOriginalId"))
                .and_then(serde_json::Value::as_str)
                .unwrap_or(id);
            let domain = metadata
                .and_then(|values| values.get("gettextDomain"))
                .and_then(serde_json::Value::as_str)
                .unwrap_or("messages");
            if original == identity && self.domain.as_deref().unwrap_or("messages") == domain {
                return Ok(id.clone());
            }
        }
        Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Unmapped native gettext source identity",
        ))
    }
}

struct GettextDirective {
    keyword: String,
    value: String,
    start: usize,
    end: usize,
}

impl GettextDirective {
    fn new(keyword: String) -> Self {
        Self {
            keyword,
            value: String::new(),
            start: usize::MAX,
            end: 0,
        }
    }

    fn append(
        &mut self,
        source: &str,
        start: usize,
        end: usize,
        charset: &str,
    ) -> Result<(), ParseError> {
        if self.start == usize::MAX {
            self.start = start;
        }
        self.end = end;
        self.value
            .push_str(&crate::gettext::quoted(&source[start..end], charset)?);
        Ok(())
    }
}

fn gettext_horizontal(character: char) -> bool {
    matches!(character, ' ' | '\t' | '\u{000b}' | '\u{000c}')
}

struct AppleScanner<'a> {
    source: &'a str,
    encoding: Encoding,
    catalog: &'a Catalog,
    index: usize,
    slots: Vec<SourceSlot>,
}

impl AppleScanner<'_> {
    fn scan(&mut self) -> Result<(), ParseError> {
        self.trivia()?;
        let wrapped = self.remaining().starts_with('{');
        if wrapped {
            self.index += 1;
        }
        loop {
            self.trivia()?;
            if self.index == self.source.len() || wrapped && self.remaining().starts_with('}') {
                return Ok(());
            }
            let key_start = self.index;
            let key_end = self.token()?;
            let id = crate::apple::decode_source_token(&self.source[key_start..key_end])?;
            self.trivia()?;
            let (start, end) = if self.remaining().starts_with(';') {
                (self.index, self.index)
            } else {
                self.require('=')?;
                self.trivia()?;
                let start = self.index;
                let end = self.token()?;
                let value = self.source[start..].chars().next().unwrap();
                self.trivia()?;
                if matches!(value, '\'' | '"') {
                    (start + value.len_utf8(), end - value.len_utf8())
                } else {
                    (start, end)
                }
            };
            self.require(';')?;
            if !self.catalog.messages.contains_key(&id) {
                return Err(error(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Unmapped Foundation source key",
                ));
            }
            self.slots.push(SourceSlot {
                id,
                selector: None,
                variant: None,
                start: self.encoding.offset(self.source, start),
                end: self.encoding.offset(self.source, end),
                apple_object_index: None,
            });
        }
    }

    fn scan_xml(&mut self) -> Result<(), ParseError> {
        let mut stack: Vec<AppleXmlElement<'_>> = Vec::new();
        let mut key = None;
        let mut position = 0;
        while position < self.source.len() {
            if self.source.as_bytes()[position] != b'<' {
                position += self.source[position..].chars().next().unwrap().len_utf8();
                continue;
            }
            if self.source[position..].starts_with("<!--") {
                position = skip(self.source, position, "-->")?;
                continue;
            }
            if self.source[position..].starts_with("<![CDATA[") {
                position = skip(self.source, position, "]]>")?;
                continue;
            }
            if self.source[position..].starts_with("<?") {
                position = skip(self.source, position, "?>")?;
                continue;
            }
            let end = tag_end(self.source, position)?;
            let mut token = self.source[position + 1..end].trim();
            if token.starts_with('!') {
                position = end + 1;
                continue;
            }
            if token.starts_with('/') {
                let current = stack.pop().ok_or_else(|| {
                    error("INVALID_SKELETON", "Unbalanced Apple property-list source")
                })?;
                if stack.last().is_some_and(|parent| parent.name == "dict") {
                    if current.name == "key" {
                        let fragment = format!(
                            "<dict><key>{}</key><string/></dict>",
                            &self.source[current.body_start..position]
                        );
                        let catalog = crate::apple::parse_strings(&fragment)?;
                        key = catalog.messages.keys().next().cloned();
                    } else if current.name == "string" {
                        self.add_xml_slot(key.take(), current.body_start, position)?;
                    }
                }
            } else {
                let empty = token.ends_with('/');
                if empty {
                    token = token[..token.len() - 1].trim();
                }
                let name = token.split_whitespace().next().ok_or_else(|| {
                    error("INVALID_SKELETON", "Missing Apple property-list XML tag")
                })?;
                if empty {
                    if name == "string" && stack.last().is_some_and(|parent| parent.name == "dict")
                    {
                        let slash = self.source[position..=end]
                            .rfind('/')
                            .map(|offset| position + offset)
                            .ok_or_else(|| {
                                error("INVALID_SKELETON", "Missing self-closing Apple XML slash")
                            })?;
                        self.add_xml_slot(key.take(), slash, end + 1)?;
                    }
                } else {
                    stack.push(AppleXmlElement {
                        name,
                        body_start: end + 1,
                    });
                }
            }
            position = end + 1;
        }
        Ok(())
    }

    fn add_xml_slot(
        &mut self,
        key: Option<String>,
        start: usize,
        end: usize,
    ) -> Result<(), ParseError> {
        let id = key.ok_or_else(|| {
            error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Missing Apple property-list source key",
            )
        })?;
        if !self.catalog.messages.contains_key(&id) {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Unmapped Apple property-list source key",
            ));
        }
        self.slots.push(SourceSlot {
            id,
            selector: None,
            variant: None,
            start: self.encoding.offset(self.source, start),
            end: self.encoding.offset(self.source, end),
            apple_object_index: None,
        });
        Ok(())
    }

    fn token(&mut self) -> Result<usize, ParseError> {
        let delimiter = self
            .remaining()
            .chars()
            .next()
            .ok_or_else(|| error("INVALID_SKELETON", "Missing Apple source token"))?;
        if matches!(delimiter, '\'' | '"') {
            self.index += delimiter.len_utf8();
            while let Some(value) = self.remaining().chars().next() {
                self.index += value.len_utf8();
                if value == '\\' {
                    let escaped = self.remaining().chars().next().ok_or_else(|| {
                        error("INVALID_SKELETON", "Unterminated Apple source escape")
                    })?;
                    self.index += escaped.len_utf8();
                } else if value == delimiter {
                    return Ok(self.index);
                }
            }
            return Err(error("INVALID_SKELETON", "Unterminated Apple source token"));
        }
        let start = self.index;
        while let Some(value) = self.remaining().chars().next() {
            if !apple_unquoted(value) {
                break;
            }
            self.index += value.len_utf8();
        }
        if self.index == start {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid unquoted Apple source token",
            ));
        }
        Ok(self.index)
    }

    fn trivia(&mut self) -> Result<(), ParseError> {
        loop {
            let Some(value) = self.remaining().chars().next() else {
                return Ok(());
            };
            if matches!(value, '\t'..='\r' | ' ' | '\u{2028}' | '\u{2029}') {
                self.index += value.len_utf8();
            } else if self.remaining().starts_with("/*") {
                let end = self.remaining()[2..]
                    .find("*/")
                    .ok_or_else(|| error("INVALID_SKELETON", "Unterminated Foundation comment"))?;
                self.index += end + 4;
            } else if self.remaining().starts_with("//") {
                self.index += 2;
                while let Some(value) = self.remaining().chars().next() {
                    if matches!(value, '\n' | '\r' | '\u{2028}' | '\u{2029}') {
                        break;
                    }
                    self.index += value.len_utf8();
                }
            } else {
                return Ok(());
            }
        }
    }

    fn require(&mut self, expected: char) -> Result<(), ParseError> {
        if !self.remaining().starts_with(expected) {
            return Err(error(
                "INVALID_SKELETON",
                "Unexpected Apple source separator",
            ));
        }
        self.index += expected.len_utf8();
        Ok(())
    }

    fn remaining(&self) -> &str {
        &self.source[self.index..]
    }
}

struct AppleXmlElement<'a> {
    name: &'a str,
    body_start: usize,
}

struct AppleXmlPart<'a> {
    cdata: bool,
    source: &'a str,
}

fn preserve_apple_cdata(source: &str, translated: &str) -> Result<String, ParseError> {
    let mut parts = Vec::new();
    let mut position = 0;
    while position < source.len() {
        let Some(offset) = source[position..].find("<![CDATA[") else {
            parts.push(AppleXmlPart {
                cdata: false,
                source: &source[position..],
            });
            break;
        };
        let begin = position + offset;
        if begin > position {
            parts.push(AppleXmlPart {
                cdata: false,
                source: &source[position..begin],
            });
        }
        let content = begin + "<![CDATA[".len();
        let end = source[content..]
            .find("]]>")
            .map(|offset| content + offset)
            .ok_or_else(|| error("INVALID_SKELETON", "Unterminated Apple property-list CDATA"))?;
        parts.push(AppleXmlPart {
            cdata: true,
            source: &source[content..end],
        });
        position = end + 3;
    }

    let mut output = String::with_capacity(translated.len());
    let mut current = 0;
    let mut remaining = translated.chars().count();
    for (index, part) in parts.iter().enumerate() {
        let count = if index + 1 == parts.len() {
            remaining
        } else {
            apple_xml_length(part.source, part.cdata)?.min(remaining)
        };
        let end = translated[current..]
            .char_indices()
            .nth(count)
            .map_or(translated.len(), |(offset, _)| current + offset);
        let content = &translated[current..end];
        if part.cdata {
            output.push_str("<![CDATA[");
            output.push_str(&content.replace("]]>", "]]]]><![CDATA[>"));
            output.push_str("]]>");
        } else {
            output.push_str(&apple_xml_text(content));
        }
        current = end;
        remaining -= count;
    }
    Ok(output)
}

fn apple_xml_length(source: &str, cdata: bool) -> Result<usize, ParseError> {
    if cdata {
        return Ok(source.chars().count());
    }
    let mut count = 0;
    let mut position = 0;
    while position < source.len() {
        if source.as_bytes()[position] == b'&' {
            position += source[position..]
                .find(';')
                .ok_or_else(|| error("INVALID_SKELETON", "Unterminated Apple XML entity"))?
                + 1;
        } else {
            position += source[position..].chars().next().unwrap().len_utf8();
        }
        count += 1;
    }
    Ok(count)
}

fn apple_xml_text(source: &str) -> String {
    let mut output = String::with_capacity(source.len());
    for value in source.chars() {
        match value {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '>' => output.push_str("&gt;"),
            '\n' => output.push_str("&#10;"),
            '\r' => output.push_str("&#13;"),
            '\t' => output.push_str("&#9;"),
            _ => output.push(value),
        }
    }
    output
}

fn apple_unquoted(value: char) -> bool {
    value.is_ascii_alphanumeric() || matches!(value, '_' | '$' | '/' | ':' | '.' | '-')
}

fn single_quoted(source: &str) -> String {
    let mut output = String::with_capacity(source.len());
    let mut characters = source.chars().peekable();
    while let Some(value) = characters.next() {
        if value == '\\' && characters.peek() == Some(&'"') {
            output.push('"');
            characters.next();
        } else if value == '\'' {
            output.push_str("\\'");
        } else {
            output.push(value);
        }
    }
    output
}

fn extract_properties(
    bytes: &[u8],
    declared_encoding: Option<&str>,
) -> Result<SourceSkeleton, ParseError> {
    let encoding = if declared_encoding == Some("ISO-8859-1") {
        Encoding::Latin1
    } else {
        Encoding::detect(bytes)
    };
    let source = crate::decode(bytes, declared_encoding)?;
    let catalog = crate::parse_with_encoding(FileFormat::JavaProperties, bytes, declared_encoding)?;
    let mut slots = Vec::new();
    for line in property_lines(&source) {
        let mut leading = 0;
        while line.text[leading..]
            .chars()
            .next()
            .is_some_and(property_whitespace)
        {
            leading += line.text[leading..].chars().next().unwrap().len_utf8();
        }
        if leading == line.text.len() || line.text[leading..].starts_with(['#', '!']) {
            continue;
        }
        let mut key_end = leading;
        let mut escaped = false;
        for (relative, character) in line.text[leading..].char_indices() {
            if !escaped && (matches!(character, '=' | ':') || property_whitespace(character)) {
                break;
            }
            escaped = character == '\\' && !escaped;
            key_end = leading + relative + character.len_utf8();
        }
        let mut value_start = key_end;
        while line.text[value_start..]
            .chars()
            .next()
            .is_some_and(property_whitespace)
        {
            value_start += line.text[value_start..].chars().next().unwrap().len_utf8();
        }
        if line.text[value_start..].starts_with(['=', ':']) {
            value_start += 1;
        }
        while line.text[value_start..]
            .chars()
            .next()
            .is_some_and(property_whitespace)
        {
            value_start += line.text[value_start..].chars().next().unwrap().len_utf8();
        }
        let id = crate::properties::unescape(&line.text[leading..key_end])?;
        if !catalog.messages.contains_key(&id) {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Unmapped Java properties source key",
            ));
        }
        let start = if value_start == line.text.len() {
            if line.end == source.len()
                && line
                    .text
                    .chars()
                    .rev()
                    .take_while(|character| *character == '\\')
                    .count()
                    % 2
                    == 1
            {
                line.positions[line.text.len() - 1]
            } else {
                line.end
            }
        } else {
            line.positions[value_start]
        };
        slots.push(SourceSlot {
            id,
            selector: None,
            variant: None,
            start: encoding.offset(&source, start),
            end: encoding.offset(&source, line.end),
            apple_object_index: None,
        });
    }
    if slots.len() < catalog.messages.len() {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Missing Java properties source value",
        ));
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::JavaProperties.id(),
        encoding: encoding.name().to_owned(),
        source,
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: None,
        slots,
    })
}

fn render_properties(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let declared = matches!(encoding, Encoding::Latin1).then_some("ISO-8859-1");
    let catalog = crate::parse_with_encoding(FileFormat::JavaProperties, &original, declared)?;
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if slot.variant.is_some() {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid Java properties source slot",
            ));
        }
        known.insert(slot.id.as_str());
    }
    if translations.keys().any(|id| !known.contains(id.as_str())) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no Java properties source slot",
        ));
    }
    let separatorless = property_separatorless_declarations(&skeleton.source)?;
    let mut result = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid Java properties source range",
            ));
        }
        result.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.id) {
            let descriptor = catalog
                .messages
                .get(&slot.id)
                .ok_or_else(|| error("INVALID_SKELETON", "Missing Java properties descriptor"))?;
            let mut value = crate::properties_writer::render_source(descriptor, translation);
            if matches!(encoding, Encoding::Latin1) {
                value = latin1_escaped(&value);
            }
            let raw = encoding.decode(&original[slot.start..slot.end])?;
            value = preserve_property_continuations(&raw, &value);
            if (slot.start == slot.end || raw == "\\") && separatorless.contains(&slot.id) {
                value.insert(0, '=');
            }
            result.extend(encoding.encode_without_bom(&value));
        } else {
            result.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    result.extend_from_slice(&original[previous..]);
    Ok(result)
}

fn property_separatorless_declarations(source: &str) -> Result<HashSet<String>, ParseError> {
    let mut result = HashSet::new();
    for line in property_lines(source) {
        let mut leading = 0;
        while line.text[leading..]
            .chars()
            .next()
            .is_some_and(property_whitespace)
        {
            leading += line.text[leading..].chars().next().unwrap().len_utf8();
        }
        if leading == line.text.len() || line.text[leading..].starts_with(['#', '!']) {
            continue;
        }
        let mut key_end = leading;
        let mut escaped = false;
        for (relative, character) in line.text[leading..].char_indices() {
            if !escaped && (matches!(character, '=' | ':') || property_whitespace(character)) {
                break;
            }
            escaped = character == '\\' && !escaped;
            key_end = leading + relative + character.len_utf8();
        }
        if key_end == line.text.len() {
            result.insert(crate::properties::unescape(&line.text[leading..key_end])?);
        }
    }
    Ok(result)
}

fn property_lines(source: &str) -> Vec<PropertyLine> {
    let mut result = Vec::new();
    let mut logical = String::new();
    let mut positions = Vec::new();
    let mut continuing = false;
    let mut physical = 0;
    while physical < source.len() {
        let end = source[physical..]
            .find(['\r', '\n'])
            .map_or(source.len(), |offset| physical + offset);
        let mut next = end;
        if next < source.len() {
            if source.as_bytes()[next] == b'\r'
                && next + 1 < source.len()
                && source.as_bytes()[next + 1] == b'\n'
            {
                next += 2;
            } else {
                next += 1;
            }
        }
        let mut start = physical;
        if continuing {
            while source[start..end]
                .chars()
                .next()
                .is_some_and(property_whitespace)
            {
                start += source[start..].chars().next().unwrap().len_utf8();
            }
        }
        if !continuing {
            let first = source[start..end].trim_start_matches(property_whitespace);
            if first.starts_with(['#', '!']) {
                physical = next;
                continue;
            }
        }
        for (relative, character) in source[start..end].char_indices() {
            logical.push(character);
            positions.extend((0..character.len_utf8()).map(|offset| start + relative + offset));
        }
        let slash_count = logical
            .chars()
            .rev()
            .take_while(|value| *value == '\\')
            .count();
        continuing = end < source.len() && slash_count % 2 == 1;
        if continuing {
            logical.pop();
            positions.pop();
        } else {
            result.push(PropertyLine {
                text: std::mem::take(&mut logical),
                positions: std::mem::take(&mut positions),
                end,
            });
        }
        physical = next;
    }
    if !logical.is_empty() {
        result.push(PropertyLine {
            text: logical,
            positions,
            end: source.len(),
        });
    }
    result
}

fn preserve_property_continuations(source: &str, translated: &str) -> String {
    let mut markers = Vec::new();
    let mut segment = 0;
    let mut index = 0;
    while index < source.len() {
        if !matches!(source.as_bytes()[index], b'\n' | b'\r') {
            index += source[index..].chars().next().unwrap().len_utf8();
            continue;
        }
        let mut slash = index;
        while slash > segment && source.as_bytes()[slash - 1] == b'\\' {
            slash -= 1;
        }
        if (index - slash) % 2 == 0 {
            index += 1;
            continue;
        }
        let start = index - 1;
        let mut end = index + 1;
        if source.as_bytes()[index] == b'\r'
            && end < source.len()
            && source.as_bytes()[end] == b'\n'
        {
            end += 1;
        }
        while source[end..]
            .chars()
            .next()
            .is_some_and(property_whitespace)
        {
            end += source[end..].chars().next().unwrap().len_utf8();
        }
        markers.push((&source[start..end], source[segment..start].chars().count()));
        segment = end;
        index = end;
    }
    if markers.is_empty() {
        return translated.to_owned();
    }
    let mut output = String::new();
    let mut position = 0;
    for (marker, width) in markers {
        let count = width.min(translated[position..].chars().count());
        let mut next = translated[position..]
            .char_indices()
            .nth(count)
            .map_or(translated.len(), |(offset, _)| position + offset);
        while translated[next..]
            .chars()
            .next()
            .is_some_and(property_whitespace)
        {
            next += translated[next..].chars().next().unwrap().len_utf8();
        }
        while next < translated.len()
            && translated[position..next]
                .chars()
                .rev()
                .take_while(|value| *value == '\\')
                .count()
                % 2
                == 1
        {
            next += translated[next..].chars().next().unwrap().len_utf8();
        }
        output.push_str(&translated[position..next]);
        output.push_str(marker);
        position = next;
    }
    output.push_str(&translated[position..]);
    output
}

fn latin1_escaped(source: &str) -> String {
    let mut output = String::new();
    for character in source.chars() {
        if (character as u32) <= 0xff {
            output.push(character);
        } else {
            let mut units = [0_u16; 2];
            for value in character.encode_utf16(&mut units) {
                write!(&mut output, "\\u{value:04X}").expect("writing to string");
            }
        }
    }
    output
}

fn property_whitespace(value: char) -> bool {
    matches!(value, ' ' | '\t' | '\u{000c}')
}

struct PropertyLine {
    text: String,
    positions: Vec<usize>,
    end: usize,
}

#[allow(clippy::too_many_arguments)]
fn add_slot(
    catalog: &Catalog,
    current: &Element,
    parent: Option<&Element>,
    body_end: usize,
    source: &str,
    encoding: Encoding,
    slots: &mut Vec<SourceSlot>,
    assigned: &mut HashSet<String>,
) -> Result<(), ParseError> {
    let Some(id) = identity(current, parent) else {
        return Ok(());
    };
    let Some(descriptor) = catalog.messages.get(&id) else {
        return Ok(());
    };
    let variant = parent
        .filter(|parent| is_plural(parent))
        .and_then(|_| current.attributes.get("quantity"))
        .map(|quantity| {
            quantity
                .trim_matches(|value: char| value.is_ascii_whitespace())
                .to_owned()
        });
    if variant.as_ref().is_some_and(|variant| {
        !descriptor
            .variants
            .as_ref()
            .is_some_and(|variants| variants.contains_key(variant))
    }) {
        return Ok(());
    }
    let slot = SourceSlot {
        id,
        selector: None,
        variant,
        start: encoding.offset(source, current.body_start),
        end: encoding.offset(source, body_end),
        apple_object_index: None,
    };
    if !assigned.insert(slot.key()) {
        return Err(error("INVALID_SKELETON", "Duplicate source slot"));
    }
    slots.push(slot);
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn add_empty_slot(
    catalog: &Catalog,
    current: &Element,
    parent: Option<&Element>,
    tag_start: usize,
    tag_end: usize,
    source: &str,
    encoding: Encoding,
    slots: &mut Vec<SourceSlot>,
    assigned: &mut HashSet<String>,
) -> Result<(), ParseError> {
    let Some(id) = identity(current, parent) else {
        return Ok(());
    };
    let Some(descriptor) = catalog.messages.get(&id) else {
        return Ok(());
    };
    let variant = parent
        .filter(|parent| is_plural(parent))
        .and_then(|_| current.attributes.get("quantity"))
        .map(|quantity| {
            quantity
                .trim_matches(|value: char| value.is_ascii_whitespace())
                .to_owned()
        });
    if variant.as_ref().is_some_and(|value| {
        !descriptor
            .variants
            .as_ref()
            .is_some_and(|variants| variants.contains_key(value))
    }) {
        return Ok(());
    }
    let slash = source[tag_start..=tag_end]
        .rfind('/')
        .map(|index| tag_start + index)
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON",
                "Self-closing Android source has no closing slash",
            )
        })?;
    let slot = SourceSlot {
        id,
        selector: None,
        variant,
        start: encoding.offset(source, slash),
        end: encoding.offset(source, tag_end + 1),
        apple_object_index: None,
    };
    if !assigned.insert(slot.key()) {
        return Err(error(
            "INVALID_SKELETON",
            "Duplicate self-closing Android source slot",
        ));
    }
    slots.push(slot);
    Ok(())
}

fn identity(current: &Element, parent: Option<&Element>) -> Option<String> {
    let parent = parent?;
    let name = local_name(&current.name);
    if !current.enabled
        || !parent.enabled
        || current.name != name
        || parent.name != local_name(&parent.name)
    {
        return None;
    }
    if local_name(&parent.name) == "resources"
        && (name == "string"
            || (name == "item"
                && current.attributes.get("type").map(String::as_str) == Some("string")))
    {
        return product(&current.attributes, current.runtime_flag.as_deref());
    }
    if name != "item" {
        return None;
    }
    if is_plural(parent) {
        return product(&parent.attributes, parent.runtime_flag.as_deref());
    }
    if is_array(parent) {
        return Some(format!(
            "{}[{}]",
            product(&parent.attributes, parent.runtime_flag.as_deref())?,
            current.array_index?
        ));
    }
    None
}

fn product(attributes: &HashMap<String, String>, runtime_flag: Option<&str>) -> Option<String> {
    let name = attributes.get("name")?.trim_ascii();
    let product = attributes
        .get("product")
        .map_or("", |product| product.trim_ascii());
    let mut identity = if product.is_empty() || product == "default" {
        name.to_owned()
    } else {
        format!("{name}@product={product}")
    };
    if let Some(flag) = runtime_flag {
        write!(identity, "@flag={flag}").expect("String formatting");
    }
    Some(identity)
}

fn android_feature_state(
    attributes: &HashMap<String, String>,
    namespaces: &HashMap<String, String>,
    flags: &[crate::AndroidFeatureFlag],
) -> Result<(Option<String>, bool), ParseError> {
    let expression = attributes.iter().find_map(|(name, value)| {
        let (prefix, local) = name.split_once(':')?;
        (local == "featureFlag"
            && namespaces.get(prefix).map(String::as_str)
                == Some("http://schemas.android.com/apk/res/android"))
        .then(|| value.trim_ascii())
    });
    let Some(expression) = expression.filter(|value| !value.is_empty()) else {
        return Ok((None, true));
    };
    let (name, negated) = expression
        .strip_prefix('!')
        .map_or((expression, false), |name| (name, true));
    let definition = flags.iter().find(|flag| flag.name == name).ok_or_else(|| {
        error(
            "UNRESOLVED_ANDROID_FEATURE_FLAG",
            "Missing Android source feature flag",
        )
    })?;
    if definition.read_only {
        Ok((None, definition.value.is_some_and(|value| value != negated)))
    } else {
        Ok((Some(expression.to_owned()), true))
    }
}

fn is_array(element: &Element) -> bool {
    let name = local_name(&element.name);
    matches!(name, "array" | "string-array" | "integer-array")
        || (name == "bag"
            && element.attributes.get("type").is_some_and(|kind| {
                matches!(kind.as_str(), "array" | "string-array" | "integer-array")
            }))
}

fn is_plural(element: &Element) -> bool {
    local_name(&element.name) == "plurals"
        || (local_name(&element.name) == "bag"
            && element.attributes.get("type").map(String::as_str) == Some("plurals"))
}

fn attributes(source: &str) -> HashMap<String, String> {
    static ATTRIBUTES: OnceLock<Regex> = OnceLock::new();
    let expression = ATTRIBUTES.get_or_init(|| {
        Regex::new(r#"([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')"#).unwrap()
    });
    expression
        .captures_iter(source)
        .map(|capture| {
            (
                capture[1].to_owned(),
                quick_xml::escape::unescape(
                    capture.get(2).or_else(|| capture.get(3)).unwrap().as_str(),
                )
                .unwrap()
                .into_owned(),
            )
        })
        .collect()
}

fn markup_attributes(source: &str) -> Result<HashMap<String, String>, ParseError> {
    let mut result = HashMap::new();
    for (name, value) in attributes(source) {
        if name == "xmlns" || name.starts_with("xmlns:") {
            continue;
        }
        if result.insert(local_name(&name).to_owned(), value).is_some() {
            return Err(error(
                "INVALID_SKELETON_MARKUP",
                "Android inline attributes have ambiguous native local names",
            ));
        }
    }
    Ok(result)
}

fn preserve_markup(source: &str, translated: &str) -> Result<String, ParseError> {
    preserve_markup_with_decorations(source, translated, false)
}

fn preserve_markup_with_decorations(
    source: &str,
    translated: &str,
    decorated: bool,
) -> Result<String, ParseError> {
    let original = tags_with_decorations(source, decorated)?;
    let target = tags(translated)?;
    if original.len() != target.len() {
        return Err(error(
            "INVALID_SKELETON_MARKUP",
            "Translated Android markup changed structure",
        ));
    }
    let mut parents = Vec::with_capacity(original.len());
    let mut closings = vec![None; original.len()];
    let mut opened: Vec<usize> = Vec::new();
    for (index, tag) in original.iter().enumerate() {
        parents.push(opened.last().copied());
        if tag.closing {
            let Some(parent) = opened.pop() else {
                return Err(error(
                    "INVALID_SKELETON_MARKUP",
                    "Original Android markup is unbalanced",
                ));
            };
            if local_name(&original[parent].name) != local_name(&tag.name) {
                return Err(error(
                    "INVALID_SKELETON_MARKUP",
                    "Original Android markup is unbalanced",
                ));
            }
            closings[parent] = Some(index);
        } else if !tag.self_closing {
            opened.push(index);
        }
    }
    if !opened.is_empty() {
        return Err(error(
            "INVALID_SKELETON_MARKUP",
            "Original Android markup is unbalanced",
        ));
    }
    let mut sections: HashMap<Option<usize>, Vec<&str>> = HashMap::new();
    if decorated {
        let mut previous = 0;
        for (index, tag) in original.iter().enumerate() {
            sections
                .entry(parents[index])
                .or_default()
                .push(&source[previous..tag.start]);
            previous = tag.end;
        }
        sections.entry(None).or_default().push(&source[previous..]);
    }

    let mut output = String::with_capacity(translated.len());
    let mut assigned = HashSet::new();
    let mut target_parents: Vec<usize> = Vec::new();
    let mut section_offsets: HashMap<Option<usize>, usize> = HashMap::new();
    let mut previous = 0;
    for target_tag in target {
        let section_parent = target_parents.last().copied();
        let selected = if target_tag.closing {
            let parent = target_parents.pop().ok_or_else(|| {
                error(
                    "INVALID_SKELETON_MARKUP",
                    "Translated Android markup is unbalanced",
                )
            })?;
            if local_name(&original[parent].name) != local_name(&target_tag.name) {
                return Err(error(
                    "INVALID_SKELETON_MARKUP",
                    "Translated Android markup changed nesting",
                ));
            }
            closings[parent]
        } else {
            let target_attributes =
                markup_attributes(&translated[target_tag.start..target_tag.end])?;
            let parent = target_parents.last().copied();
            let mut selected = None;
            for (index, candidate) in original.iter().enumerate() {
                if candidate.closing
                    || candidate.self_closing != target_tag.self_closing
                    || assigned.contains(&index)
                    || parents[index] != parent
                    || local_name(&candidate.name) != local_name(&target_tag.name)
                    || markup_attributes(&source[candidate.start..candidate.end])?
                        != target_attributes
                {
                    continue;
                }
                if selected.replace(index).is_some() {
                    return Err(error(
                        "INVALID_SKELETON_MARKUP",
                        "Translated Android markup is ambiguous",
                    ));
                }
            }
            if let Some(index) = selected {
                if !target_tag.self_closing {
                    target_parents.push(index);
                }
            }
            selected
        }
        .filter(|index| assigned.insert(*index))
        .ok_or_else(|| {
            error(
                "INVALID_SKELETON_MARKUP",
                "Translated Android markup changed identity",
            )
        })?;
        let source_tag = &original[selected];
        let text = &translated[previous..target_tag.start];
        if decorated {
            let offset = section_offsets.entry(section_parent).or_default();
            let segment = sections
                .get(&section_parent)
                .and_then(|segments| segments.get(*offset))
                .ok_or_else(|| {
                    error(
                        "INVALID_SKELETON_MARKUP",
                        "Translated Android text changed nesting",
                    )
                })?;
            output.push_str(&preserve_decorations(segment, text)?);
            *offset += 1;
        } else {
            output.push_str(text);
        }
        output.push_str(&source[source_tag.start..source_tag.end]);
        previous = target_tag.end;
    }
    if !target_parents.is_empty() || assigned.len() != original.len() {
        return Err(error(
            "INVALID_SKELETON_MARKUP",
            "Translated Android markup is unbalanced",
        ));
    }
    if decorated {
        let offset = section_offsets.entry(None).or_default();
        let segment = sections
            .get(&None)
            .and_then(|segments| segments.get(*offset))
            .ok_or_else(|| {
                error(
                    "INVALID_SKELETON_MARKUP",
                    "Translated Android root text changed nesting",
                )
            })?;
        output.push_str(&preserve_decorations(segment, &translated[previous..])?);
        *offset += 1;
        if sections
            .iter()
            .any(|(parent, segments)| section_offsets.get(parent) != Some(&segments.len()))
        {
            return Err(error(
                "INVALID_SKELETON_MARKUP",
                "Translated Android decorations lost ownership",
            ));
        }
    } else {
        output.push_str(&translated[previous..]);
    }
    Ok(output)
}

fn preserve_decorations(source: &str, translated: &str) -> Result<String, ParseError> {
    let mut parts = Vec::new();
    let mut position = 0;
    while position < source.len() {
        let comment = source[position..]
            .find("<!--")
            .map(|index| position + index);
        let cdata = source[position..]
            .find("<![CDATA[")
            .map(|index| position + index);
        let instruction = source[position..].find("<?").map(|index| position + index);
        let next = [comment, cdata, instruction].into_iter().flatten().min();
        let Some(next) = next else {
            parts.push(DecoratedPart {
                kind: Decoration::Text,
                source: &source[position..],
            });
            break;
        };
        if next > position {
            parts.push(DecoratedPart {
                kind: Decoration::Text,
                source: &source[position..next],
            });
        }
        if comment == Some(next) {
            let end = skip(source, next, "-->")?;
            parts.push(DecoratedPart {
                kind: Decoration::Comment,
                source: &source[next..end],
            });
            position = end;
        } else if instruction == Some(next) {
            let end = skip(source, next, "?>")?;
            parts.push(DecoratedPart {
                kind: Decoration::Instruction,
                source: &source[next..end],
            });
            position = end;
        } else {
            let end = skip(source, next, "]]>")?;
            parts.push(DecoratedPart {
                kind: Decoration::Cdata,
                source: &source[next + "<![CDATA[".len()..end - 3],
            });
            position = end;
        }
    }
    if parts
        .iter()
        .any(|part| matches!(part.kind, Decoration::Text) && part.source.contains('<'))
    {
        return Err(error(
            "UNSUPPORTED_SKELETON_MARKUP",
            "Mixed Android style tags and decorations need token-level ownership",
        ));
    }
    if parts.iter().all(|part| !part.kind.visible()) {
        parts.push(DecoratedPart {
            kind: Decoration::Text,
            source: "",
        });
    }
    let atoms = xml_atoms(translated);
    let mut remaining = parts.iter().filter(|part| part.kind.visible()).count();
    let mut current = 0;
    let mut output = String::new();
    for part in parts {
        if !part.kind.visible() {
            output.push_str(part.source);
            continue;
        }
        remaining -= 1;
        let count = if remaining == 0 {
            atoms.len() - current
        } else {
            xml_atoms(part.source).len().min(atoms.len() - current)
        };
        if matches!(part.kind, Decoration::Cdata) {
            output.push_str("<![CDATA[");
        }
        let mut cdata = matches!(part.kind, Decoration::Cdata).then(String::new);
        for atom in &atoms[current..current + count] {
            if let Some(value) = cdata.as_mut() {
                value.push_str(&atom.decoded);
            } else {
                output.push_str(atom.lexical);
            }
        }
        current += count;
        if let Some(value) = cdata {
            output.push_str(&value.replace("]]>", "]]]]><![CDATA[>"));
            output.push_str("]]>");
        }
    }
    Ok(output)
}

fn xml_atoms(source: &str) -> Vec<XmlAtom<'_>> {
    let mut atoms = Vec::new();
    let mut index = 0;
    while index < source.len() {
        if source.as_bytes()[index] == b'&' {
            if let Some(end) = source[index + 1..].find(';') {
                let end = index + 1 + end;
                let lexical = &source[index..=end];
                let decoded = match lexical {
                    "&amp;" => Some("&"),
                    "&lt;" => Some("<"),
                    "&gt;" => Some(">"),
                    "&quot;" => Some("\""),
                    "&apos;" => Some("'"),
                    _ => None,
                };
                if let Some(decoded) = decoded {
                    atoms.push(XmlAtom {
                        lexical,
                        decoded: decoded.to_owned(),
                    });
                    index = end + 1;
                    continue;
                }
            }
        }
        let character = source[index..].chars().next().unwrap();
        let end = index + character.len_utf8();
        atoms.push(XmlAtom {
            lexical: &source[index..end],
            decoded: character.to_string(),
        });
        index = end;
    }
    atoms
}

struct XmlAtom<'a> {
    lexical: &'a str,
    decoded: String,
}

struct DecoratedPart<'a> {
    kind: Decoration,
    source: &'a str,
}

enum Decoration {
    Text,
    Comment,
    Instruction,
    Cdata,
}

impl Decoration {
    fn visible(&self) -> bool {
        !matches!(self, Self::Comment | Self::Instruction)
    }
}

fn tags(source: &str) -> Result<Vec<Tag>, ParseError> {
    tags_with_decorations(source, false)
}

fn tags_with_decorations(source: &str, decorations: bool) -> Result<Vec<Tag>, ParseError> {
    let mut result = Vec::new();
    let mut index = 0;
    while index < source.len() {
        if source.as_bytes()[index] != b'<' {
            index += source[index..].chars().next().unwrap().len_utf8();
            continue;
        }
        if source[index..].starts_with("<!--")
            || source[index..].starts_with("<![CDATA[")
            || source[index..].starts_with("<?")
        {
            if decorations {
                index = skip(
                    source,
                    index,
                    if source[index..].starts_with("<!--") {
                        "-->"
                    } else if source[index..].starts_with("<?") {
                        "?>"
                    } else {
                        "]]>"
                    },
                )?;
                continue;
            }
            return Err(error(
                "UNSUPPORTED_SKELETON_MARKUP",
                "Unsupported Android inline XML content",
            ));
        }
        let end = tag_end(source, index)?;
        let mut token = source[index + 1..end].trim();
        let closing = token.starts_with('/');
        if closing {
            token = token[1..].trim();
        }
        let self_closing = !closing && token.ends_with('/');
        let mut name = token.split_whitespace().next().unwrap();
        if self_closing {
            name = name.strip_suffix('/').unwrap_or(name);
        }
        result.push(Tag {
            name: name.to_owned(),
            closing,
            self_closing,
            start: index,
            end: end + 1,
        });
        index = end + 1;
    }
    Ok(result)
}

fn tag_end(source: &str, start: usize) -> Result<usize, ParseError> {
    let mut quote = None;
    for (offset, value) in source[start + 1..].char_indices() {
        match (quote, value) {
            (None, '\'' | '"') => quote = Some(value),
            (Some(open), current) if current == open => quote = None,
            (None, '>') => return Ok(start + 1 + offset),
            _ => {}
        }
    }
    Err(error("INVALID_SKELETON", "Unterminated Android XML tag"))
}

fn skip(source: &str, start: usize, delimiter: &str) -> Result<usize, ParseError> {
    source[start..]
        .find(delimiter)
        .map(|offset| start + offset + delimiter.len())
        .ok_or_else(|| error("INVALID_SKELETON", "Unterminated Android XML section"))
}

fn local_name(name: &str) -> &str {
    name.rsplit(':').next().unwrap_or(name)
}

fn error(code: &'static str, message: &'static str) -> ParseError {
    ParseError::new(code, message)
}

#[derive(Debug)]
struct Element {
    name: String,
    attributes: HashMap<String, String>,
    body_start: usize,
    array_index: Option<usize>,
    namespaces: HashMap<String, String>,
    runtime_flag: Option<String>,
    enabled: bool,
    next_index: usize,
}

#[derive(Debug)]
struct Tag {
    name: String,
    closing: bool,
    self_closing: bool,
    start: usize,
    end: usize,
}

#[derive(Clone, Copy)]
pub(crate) enum Encoding {
    Utf8,
    Utf8Bom,
    Utf16Le,
    Utf16Be,
    Utf16LeBare,
    Utf16BeBare,
    Latin1,
    Cp1252,
    Ascii,
}

impl Encoding {
    pub(crate) fn detect(bytes: &[u8]) -> Self {
        Self::detect_declared(bytes, None)
    }

    fn detect_declared(bytes: &[u8], declared: Option<&str>) -> Self {
        if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
            Self::Utf8Bom
        } else if bytes.starts_with(&[0xff, 0xfe]) {
            Self::Utf16Le
        } else if bytes.starts_with(&[0xfe, 0xff]) {
            Self::Utf16Be
        } else if declared == Some("UTF-16LE") {
            Self::Utf16LeBare
        } else if declared == Some("UTF-16BE") {
            Self::Utf16BeBare
        } else if declared == Some("ISO-8859-1") {
            Self::Latin1
        } else if declared == Some("US-ASCII") {
            Self::Ascii
        } else {
            Self::Utf8
        }
    }

    pub(crate) fn named(name: &str) -> Result<Self, ParseError> {
        match name {
            "UTF-8" => Ok(Self::Utf8),
            "UTF-8-BOM" => Ok(Self::Utf8Bom),
            "UTF-16LE-BOM" => Ok(Self::Utf16Le),
            "UTF-16BE-BOM" => Ok(Self::Utf16Be),
            "UTF-16LE" => Ok(Self::Utf16LeBare),
            "UTF-16BE" => Ok(Self::Utf16BeBare),
            "ISO-8859-1" => Ok(Self::Latin1),
            "CP1252" => Ok(Self::Cp1252),
            "US-ASCII" => Ok(Self::Ascii),
            _ => Err(error("INVALID_SKELETON", "Unsupported source encoding")),
        }
    }

    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::Utf8 => "UTF-8",
            Self::Utf8Bom => "UTF-8-BOM",
            Self::Utf16Le => "UTF-16LE-BOM",
            Self::Utf16Be => "UTF-16BE-BOM",
            Self::Utf16LeBare => "UTF-16LE",
            Self::Utf16BeBare => "UTF-16BE",
            Self::Latin1 => "ISO-8859-1",
            Self::Cp1252 => "CP1252",
            Self::Ascii => "US-ASCII",
        }
    }

    pub(crate) fn offset(self, value: &str, index: usize) -> usize {
        match self {
            Self::Utf8 => index,
            Self::Utf8Bom => index + 3,
            Self::Utf16Le | Self::Utf16Be => 2 + value[..index].encode_utf16().count() * 2,
            Self::Utf16LeBare | Self::Utf16BeBare => value[..index].encode_utf16().count() * 2,
            Self::Latin1 | Self::Cp1252 | Self::Ascii => value[..index].chars().count(),
        }
    }

    pub(crate) fn bom_length(self) -> usize {
        match self {
            Self::Utf8 | Self::Utf16LeBare | Self::Utf16BeBare => 0,
            Self::Utf8Bom => 3,
            Self::Utf16Le | Self::Utf16Be => 2,
            Self::Latin1 | Self::Cp1252 | Self::Ascii => 0,
        }
    }

    pub(crate) fn encode(self, value: &str) -> Vec<u8> {
        let mut output = match self {
            Self::Utf8 => vec![],
            Self::Utf8Bom => vec![0xef, 0xbb, 0xbf],
            Self::Utf16Le => vec![0xff, 0xfe],
            Self::Utf16Be => vec![0xfe, 0xff],
            Self::Utf16LeBare | Self::Utf16BeBare => vec![],
            Self::Latin1 | Self::Cp1252 | Self::Ascii => vec![],
        };
        output.extend(self.encode_without_bom(value));
        output
    }

    pub(crate) fn encode_without_bom(self, value: &str) -> Vec<u8> {
        match self {
            Self::Utf8 | Self::Utf8Bom => value.as_bytes().to_vec(),
            Self::Utf16Le | Self::Utf16LeBare => {
                value.encode_utf16().flat_map(u16::to_le_bytes).collect()
            }
            Self::Utf16Be | Self::Utf16BeBare => {
                value.encode_utf16().flat_map(u16::to_be_bytes).collect()
            }
            Self::Latin1 => value
                .chars()
                .map(|character| u8::try_from(character as u32).expect("Latin-1 character"))
                .collect(),
            Self::Cp1252 | Self::Ascii => value
                .chars()
                .flat_map(|character| {
                    crate::encode_gettext_character(character, self.name())
                        .expect("representable gettext source character")
                })
                .collect(),
        }
    }

    pub(crate) fn decode(self, source: &[u8]) -> Result<String, ParseError> {
        match self {
            Self::Utf8 | Self::Utf8Bom => String::from_utf8(source.to_vec())
                .map_err(|_| error("INVALID_SKELETON", "Invalid UTF-8 source slot")),
            Self::Latin1 => Ok(source.iter().map(|byte| char::from(*byte)).collect()),
            Self::Cp1252 | Self::Ascii => crate::decode_gettext_bytes(source, self.name()),
            Self::Utf16Le | Self::Utf16Be | Self::Utf16LeBare | Self::Utf16BeBare => {
                if source.len() % 2 != 0 {
                    return Err(error("INVALID_SKELETON", "Odd UTF-16 source slot"));
                }
                let units: Vec<u16> = source
                    .chunks_exact(2)
                    .map(|pair| {
                        if matches!(self, Self::Utf16Le | Self::Utf16LeBare) {
                            u16::from_le_bytes([pair[0], pair[1]])
                        } else {
                            u16::from_be_bytes([pair[0], pair[1]])
                        }
                    })
                    .collect();
                String::from_utf16(&units)
                    .map_err(|_| error("INVALID_SKELETON", "Invalid UTF-16 source slot"))
            }
        }
    }
}
