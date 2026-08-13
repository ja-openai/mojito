mod android;
mod android_annotation;
mod android_attributes;
mod android_configuration;
mod android_overlay;
mod android_overlay_skeleton;
mod android_products;
mod android_reference;
mod android_writer;
mod apple;
mod apple_binary;
mod apple_binary_skeleton;
mod apple_stringsdict_writer;
mod apple_writer;
mod apple_xcstrings_writer;
mod gettext;
mod gettext_plural;
mod gettext_writer;
mod javascript;
mod model;
mod placeholders;
mod properties;
mod properties_writer;
mod shadow;
mod source_skeleton;
mod workflow;
mod xml;
mod xml_name;
mod xml_resources;
mod yaml;

pub use android_overlay_skeleton::{
    AndroidOverlayLocalizedResource, AndroidOverlayMacroOwner, AndroidOverlaySourceFile,
    AndroidOverlaySourceSkeleton,
};
pub use model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use serde_json::Map;
pub use shadow::{compare as compare_shadow, LegacyTextUnit, ShadowDifference, ShadowReport};
pub use source_skeleton::{SourceSkeleton, SourceSlot};
use std::collections::BTreeMap;
pub use workflow::FilterOptions;

const ANDROID_RUNTIME_FLAG: &str = "\0runtime:";
const ANDROID_UNSET_FLAG: &str = "\0unset:";

/// One ordered AAPT2 feature-flag declaration, including mutable and unset flag values.
#[derive(Clone, Debug, Eq, PartialEq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidFeatureFlag {
    pub name: String,
    pub read_only: bool,
    pub value: Option<bool>,
}

fn android_feature_flag_values(
    definitions: &[AndroidFeatureFlag],
) -> Result<BTreeMap<String, bool>, ParseError> {
    let mut result = BTreeMap::new();
    for definition in definitions {
        let mut characters = definition.name.chars();
        if !characters
            .next()
            .is_some_and(|value| value.is_ascii_alphabetic() || value == '_')
            || !characters.all(|value| value.is_ascii_alphanumeric() || "_.-".contains(value))
        {
            return Err(ParseError::new(
                "INVALID_ANDROID_FEATURE_FLAG",
                "Android feature flags require valid names and modes",
            ));
        }
        result.remove(&format!("{ANDROID_RUNTIME_FLAG}{}", definition.name));
        result.remove(&format!("{ANDROID_UNSET_FLAG}{}", definition.name));
        if definition.read_only {
            if let Some(value) = definition.value {
                result.insert(definition.name.clone(), value);
            } else {
                result.remove(&definition.name);
                result.insert(format!("{ANDROID_UNSET_FLAG}{}", definition.name), true);
            }
        } else {
            result.insert(definition.name.clone(), true);
            result.insert(format!("{ANDROID_RUNTIME_FLAG}{}", definition.name), true);
        }
    }
    Ok(result)
}

pub(crate) fn android_runtime_feature_flag(flags: &BTreeMap<String, bool>, name: &str) -> bool {
    flags.contains_key(&format!("{ANDROID_RUNTIME_FLAG}{name}"))
}

pub(crate) fn android_unset_feature_flag(flags: &BTreeMap<String, bool>, name: &str) -> bool {
    flags.contains_key(&format!("{ANDROID_UNSET_FLAG}{name}"))
}

/// One original Android resource file and its explicit Gradle source-set priority.
#[derive(Clone, Copy, Debug)]
pub struct AndroidResourceInput<'a> {
    pub source_set: &'a str,
    pub resource_path: &'a str,
    pub source: &'a [u8],
}

/// Merge one resource configuration using explicit Android Gradle source-set priorities.
pub fn parse_android_overlay(inputs: &[AndroidResourceInput<'_>]) -> Result<Catalog, ParseError> {
    parse_android_overlay_with_feature_flags(inputs, &BTreeMap::new())
}

/// Merge Android source sets using explicit read-only build feature-flag values.
pub fn parse_android_overlay_with_feature_flags(
    inputs: &[AndroidResourceInput<'_>],
    android_feature_flags: &BTreeMap<String, bool>,
) -> Result<Catalog, ParseError> {
    parse_android_overlay_with_context(inputs, android_feature_flags, None, None)
}

/// Merge source sets and select the exact AAPT2 products before conditional stripping.
pub fn parse_android_overlay_with_build(
    inputs: &[AndroidResourceInput<'_>],
    android_feature_flags: &BTreeMap<String, bool>,
    selected_products: &[String],
) -> Result<Catalog, ParseError> {
    parse_android_overlay_with_context(inputs, android_feature_flags, Some(selected_products), None)
}

/// Merge resource overlays and resolve package-qualified local build-time macro references.
pub fn parse_android_overlay_with_context(
    inputs: &[AndroidResourceInput<'_>],
    android_feature_flags: &BTreeMap<String, bool>,
    selected_products: Option<&[String]>,
    application_package: Option<&str>,
) -> Result<Catalog, ParseError> {
    let catalog = android_overlay::parse(inputs, android_feature_flags, application_package)?;
    if let Some(products) = selected_products {
        android_products::select_overlay(inputs, catalog, android_feature_flags, products)
    } else {
        Ok(catalog)
    }
}

/// Merge Android overlays using ordered read-only/read-write AAPT2 flag declarations.
pub fn parse_android_overlay_with_feature_flag_definitions(
    inputs: &[AndroidResourceInput<'_>],
    android_feature_flags: &[AndroidFeatureFlag],
    selected_products: Option<&[String]>,
) -> Result<Catalog, ParseError> {
    parse_android_overlay_with_feature_flag_definitions_and_package(
        inputs,
        android_feature_flags,
        selected_products,
        None,
    )
}

/// Merge ordered flag definitions and package-qualified local Android macro references.
pub fn parse_android_overlay_with_feature_flag_definitions_and_package(
    inputs: &[AndroidResourceInput<'_>],
    android_feature_flags: &[AndroidFeatureFlag],
    selected_products: Option<&[String]>,
    application_package: Option<&str>,
) -> Result<Catalog, ParseError> {
    let values = android_feature_flag_values(android_feature_flags)?;
    parse_android_overlay_with_context(inputs, &values, selected_products, application_package)
}

/// Regenerate deterministic, compiler-valid platform resources from a canonical catalog.
pub fn write(format: FileFormat, catalog: &Catalog) -> Result<String, ParseError> {
    match format {
        FileFormat::Android => android_writer::write(catalog),
        FileFormat::AppleStrings => apple_writer::write(catalog),
        FileFormat::AppleStringsdict => apple_stringsdict_writer::write(catalog),
        FileFormat::AppleXcstrings => apple_xcstrings_writer::write(catalog),
        FileFormat::GettextPo => gettext_writer::write(catalog),
        FileFormat::JavaProperties => properties_writer::write(catalog),
        FileFormat::Resx | FileFormat::Xtb => xml_resources::write(format, catalog),
        _ => Err(ParseError::new(
            "UNSUPPORTED_OUTPUT_FORMAT",
            format!("Normalized writing is not available for {}", format.id()),
        )),
    }
}

/// Extract reversible source bytes and precise translation slots beside the semantic catalog.
pub fn extract_skeleton(format: FileFormat, source: &[u8]) -> Result<SourceSkeleton, ParseError> {
    extract_skeleton_with_encoding(format, source, None)
}

/// Preserve explicit legacy ISO-8859-1 byte ownership for Java properties sources.
pub fn extract_skeleton_with_encoding(
    format: FileFormat,
    source: &[u8],
    properties_encoding: Option<&str>,
) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract(format, source, properties_encoding)
}

/// Independently expose every standalone Foundation device and presentation-width branch.
pub fn extract_skeleton_with_apple_variations(source: &[u8]) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract_stringsdict_variations(source)
}

/// Independently expose every source-language Xcode String Catalog device branch.
pub fn extract_skeleton_with_xcode_devices(source: &[u8]) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract_xcstrings_devices(source)
}

/// Materialize missing or explicit-null Xcode source locales without changing default behavior.
pub fn extract_skeleton_with_xcode_source_insertion(
    source: &[u8],
) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract_xcstrings_source_insertion(source)
}

/// Preserve source values while inserting or updating one normalized Xcode target locale.
pub fn extract_skeleton_with_xcode_target_insertion(
    source: &[u8],
    target_locale: &str,
) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract_xcstrings_target_insertion(source, target_locale)
}

/// Retain ordered AAPT2 read-only/read-write flag context beside Android source ownership.
pub fn extract_skeleton_with_android_feature_flags(
    source: &[u8],
    feature_flags: &[AndroidFeatureFlag],
) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract_android_with_context(source, None, feature_flags)
}

/// Preserve the exact Android values-directory configuration and ordered feature declarations.
pub fn extract_skeleton_with_android_context(
    source: &[u8],
    resource_path: &str,
    feature_flags: &[AndroidFeatureFlag],
) -> Result<SourceSkeleton, ParseError> {
    source_skeleton::extract_android_with_context(source, Some(resource_path), feature_flags)
}

/// Preserve every original source file while assigning translation slots to overlay winners.
pub fn extract_android_overlay_skeleton(
    inputs: &[AndroidResourceInput<'_>],
) -> Result<AndroidOverlaySourceSkeleton, ParseError> {
    extract_android_overlay_skeleton_with_context(inputs, &[], None, None)
}

/// Retain ordered build flags, selected products, and application-package ownership context.
pub fn extract_android_overlay_skeleton_with_context(
    inputs: &[AndroidResourceInput<'_>],
    feature_flags: &[AndroidFeatureFlag],
    selected_products: Option<&[String]>,
    application_package: Option<&str>,
) -> Result<AndroidOverlaySourceSkeleton, ParseError> {
    android_overlay_skeleton::extract(
        inputs,
        feature_flags,
        selected_products,
        application_package,
    )
}

/// Replace winning declarations only and leave every overridden Android source byte unchanged.
pub fn render_android_overlay_skeleton(
    skeleton: &AndroidOverlaySourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<AndroidOverlayLocalizedResource>, ParseError> {
    android_overlay_skeleton::render(skeleton, translations)
}

/// Replace only selected translation slots and preserve all untouched original file bytes.
pub fn render_skeleton(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    source_skeleton::render(skeleton, translations)
}

/// Parse a UTF-8 resource, automatically honoring UTF-8 and UTF-16 byte-order marks.
pub fn parse(format: FileFormat, bytes: &[u8]) -> Result<Catalog, ParseError> {
    parse_with_encoding(format, bytes, None)
}

/// Apply explicit Mojito filter options and intentional translator-workflow extraction policy.
pub fn parse_for_mojito(
    format: FileFormat,
    bytes: &[u8],
    filter_options: &[String],
) -> Result<Catalog, ParseError> {
    workflow::parse(format, bytes, filter_options)
}

/// Apply explicit translation-import notes and locale-owned legacy plural completion.
pub fn parse_for_mojito_import(
    format: FileFormat,
    bytes: &[u8],
    filter_options: &[String],
    target_locale: &str,
    copy_forms_on_import: bool,
) -> Result<Catalog, ParseError> {
    workflow::parse_import(
        format,
        bytes,
        filter_options,
        target_locale,
        copy_forms_on_import,
    )
}

/// Render localized resources with legacy inheritance and format-owned output policies.
pub fn localize_for_mojito(
    format: FileFormat,
    source: &[u8],
    translations: &BTreeMap<String, String>,
    filter_options: &[String],
    remove_untranslated: bool,
) -> Result<Vec<u8>, ParseError> {
    localize_for_mojito_locale(
        format,
        source,
        translations,
        filter_options,
        remove_untranslated,
        None,
    )
}

/// Apply target-locale-specific Mojito plural output policy without changing native parsing.
pub fn localize_for_mojito_locale(
    format: FileFormat,
    source: &[u8],
    translations: &BTreeMap<String, String>,
    filter_options: &[String],
    remove_untranslated: bool,
    target_locale: Option<&str>,
) -> Result<Vec<u8>, ParseError> {
    workflow::localize(
        format,
        source,
        translations,
        filter_options,
        remove_untranslated,
        target_locale,
    )
}

/// Parse Android resources in their original values-directory configuration.
pub fn parse_with_path(
    format: FileFormat,
    bytes: &[u8],
    android_resource_path: &str,
) -> Result<Catalog, ParseError> {
    parse_with_options(format, bytes, None, Some(android_resource_path))
}

/// Parse a resource with an explicit properties encoding (`ISO-8859-1` or UTF-8).
pub fn parse_with_encoding(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
) -> Result<Catalog, ParseError> {
    parse_with_options(format, bytes, properties_encoding, None)
}

/// Parse a resource with optional properties encoding and its Android values-directory path.
pub fn parse_with_options(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
    android_resource_path: Option<&str>,
) -> Result<Catalog, ParseError> {
    parse_with_feature_flags(
        format,
        bytes,
        properties_encoding,
        android_resource_path,
        &BTreeMap::new(),
    )
}

/// Parse resources using explicit read-only Android build feature-flag values.
pub fn parse_with_feature_flags(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
    android_resource_path: Option<&str>,
    android_feature_flags: &BTreeMap<String, bool>,
) -> Result<Catalog, ParseError> {
    if format == FileFormat::AppleStringsdict && apple_binary::matches(bytes) {
        return apple_binary::parse_stringsdict(bytes);
    }
    if format == FileFormat::AppleStrings && apple_binary::matches(bytes) {
        if !bytes.contains(&0) {
            if let Ok(source) = std::str::from_utf8(bytes) {
                if let Ok(catalog) = apple::parse_strings(source) {
                    return Ok(catalog);
                }
            }
        }
        return apple_binary::parse(bytes);
    }
    let gettext_encoding = if format == FileFormat::GettextPo {
        Some(gettext_charset(bytes)?)
    } else {
        None
    };
    let xml_encoding = xml_encoding(format, bytes)?;
    let selected_encoding = if format == FileFormat::JavaProperties {
        properties_encoding
    } else {
        gettext_encoding.or(xml_encoding)
    };
    let source = decode(bytes, selected_encoding).map_err(|error| {
        if format == FileFormat::GettextPo {
            ParseError::new("INVALID_GETTEXT_ENCODING", error.message)
        } else {
            error
        }
    })?;
    match format {
        FileFormat::Android => {
            android::parse(&source, android_resource_path, android_feature_flags)
        }
        FileFormat::AppleStrings => apple::parse_strings(&source),
        FileFormat::AppleStringsdict => apple::parse_stringsdict(&source),
        FileFormat::AppleXcstrings => apple::parse_xcstrings(&source),
        FileFormat::GettextPo => gettext::parse(&source, gettext_encoding.unwrap_or("UTF-8")),
        FileFormat::JavaProperties => properties::parse(&source),
        FileFormat::FormatJsJson => parse_formatjs(&source),
        FileFormat::Yaml => yaml::parse(&source),
        FileFormat::JavaScript | FileFormat::TypeScript => javascript::parse(format, &source),
        FileFormat::Resx => xml_resources::parse(format, &source),
        FileFormat::Resx | FileFormat::Xtb => xml_resources::parse(format, &source),
    }
}

pub(crate) fn xml_encoding(
    format: FileFormat,
    bytes: &[u8],
) -> Result<Option<&'static str>, ParseError> {
    if !matches!(
        format,
        FileFormat::Android
            | FileFormat::AppleStrings
            | FileFormat::AppleStringsdict
            | FileFormat::Resx
            | FileFormat::Xtb
    ) {
        return Ok(None);
    }

    let utf16_little_endian_bom = bytes.starts_with(&[0xff, 0xfe]);
    let utf16_big_endian_bom = bytes.starts_with(&[0xfe, 0xff]);
    let utf16_little_endian =
        utf16_little_endian_bom || format == FileFormat::Android && bomless_utf16(bytes, true);
    let utf16_big_endian =
        utf16_big_endian_bom || format == FileFormat::Android && bomless_utf16(bytes, false);
    let utf8_bom = bytes.starts_with(&[0xef, 0xbb, 0xbf]);
    let prefix = if utf16_little_endian || utf16_big_endian {
        decode(
            bytes,
            Some(if utf16_little_endian {
                "UTF-16LE"
            } else {
                "UTF-16BE"
            }),
        )?
    } else {
        let declaration = &bytes[usize::from(utf8_bom) * 3..];
        let length = declaration
            .windows(2)
            .position(|pair| pair == b"?>")
            .map_or(declaration.len(), |index| index + 2);
        declaration[..length]
            .iter()
            .map(|byte| char::from(*byte))
            .collect()
    };
    static DECLARATION: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r#"^<\?xml\b[^?]*\bencoding\s*=\s*(?:\"([^\"]+)\"|'([^']+)')"#)
            .expect("valid XML encoding declaration expression")
    });
    let Some(declaration) = DECLARATION.captures(&prefix) else {
        return Ok(if utf16_little_endian && !utf16_little_endian_bom {
            Some("UTF-16LE")
        } else if utf16_big_endian && !utf16_big_endian_bom {
            Some("UTF-16BE")
        } else {
            None
        });
    };
    let declared = declaration
        .get(1)
        .or_else(|| declaration.get(2))
        .expect("XML encoding declaration value")
        .as_str()
        .to_ascii_uppercase();
    let apple = format != FileFormat::Android;
    if apple && (utf16_little_endian || utf16_big_endian || utf8_bom) {
        return Ok(Some(if utf16_little_endian {
            "UTF-16LE"
        } else if utf16_big_endian {
            "UTF-16BE"
        } else {
            "UTF-8"
        }));
    }
    let invalid = || {
        ParseError::new(
            "INVALID_XML",
            format!("Unsupported or contradictory XML encoding declaration: {declared}"),
        )
    };
    let encoding = match declared.as_str() {
        "UTF-8" => "UTF-8",
        "UTF8" | "UTF_8" if apple => "UTF-8",
        "UTF-16" if utf16_little_endian => "UTF-16LE",
        "UTF-16" if utf16_big_endian => "UTF-16BE",
        "UTF-16LE" if utf16_little_endian => "UTF-16LE",
        "UTF-16BE" if utf16_big_endian => "UTF-16BE",
        "ISO-8859-1" => "ISO-8859-1",
        "LATIN1" if apple => "ISO-8859-1",
        "US-ASCII" => "US-ASCII",
        "ASCII" if apple => "US-ASCII",
        _ => return Err(invalid()),
    };
    if (utf16_little_endian || utf16_big_endian) && !matches!(encoding, "UTF-16LE" | "UTF-16BE")
        || !(utf16_little_endian || utf16_big_endian) && matches!(encoding, "UTF-16LE" | "UTF-16BE")
        || utf8_bom && encoding != "UTF-8"
    {
        return Err(invalid());
    }
    Ok(Some(encoding))
}

fn bomless_utf16(bytes: &[u8], little_endian: bool) -> bool {
    if bytes.len() < 4 {
        return false;
    }
    let (first, second, zero_one, zero_two) = if little_endian {
        (bytes[0], bytes[2], bytes[1], bytes[3])
    } else {
        (bytes[1], bytes[3], bytes[0], bytes[2])
    };
    zero_one == 0
        && zero_two == 0
        && matches!(first, b'<' | b' ' | b'\t' | b'\n' | b'\r')
        && second.is_ascii()
}

/// Parse the actual Android build selected by AAPT2 products and read-only feature flags.
pub fn parse_with_android_build(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
    android_resource_path: Option<&str>,
    android_feature_flags: &BTreeMap<String, bool>,
    selected_products: &[String],
) -> Result<Catalog, ParseError> {
    parse_with_android_context(
        format,
        bytes,
        properties_encoding,
        android_resource_path,
        android_feature_flags,
        Some(selected_products),
        None,
    )
}

/// Parse Android resources with explicit package ownership, products, and build feature flags.
pub fn parse_with_android_context(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
    android_resource_path: Option<&str>,
    android_feature_flags: &BTreeMap<String, bool>,
    selected_products: Option<&[String]>,
    application_package: Option<&str>,
) -> Result<Catalog, ParseError> {
    if format != FileFormat::Android {
        return parse_with_feature_flags(
            format,
            bytes,
            properties_encoding,
            android_resource_path,
            android_feature_flags,
        );
    }
    let source = decode(bytes, xml_encoding(format, bytes)?)?;
    let catalog = android::parse_with_macros(
        &source,
        android_resource_path,
        android_feature_flags,
        &BTreeMap::new(),
        &BTreeMap::new(),
        &BTreeMap::new(),
        application_package,
    )?;
    if let Some(products) = selected_products {
        android_products::select(
            &source,
            catalog,
            android_feature_flags,
            products,
            android_resource_path,
        )
    } else {
        Ok(catalog)
    }
}

/// Parse resources with ordered fixed or runtime AAPT2 feature-flag declarations.
pub fn parse_with_android_feature_flag_definitions(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
    android_resource_path: Option<&str>,
    android_feature_flags: &[AndroidFeatureFlag],
    selected_products: Option<&[String]>,
) -> Result<Catalog, ParseError> {
    parse_with_android_feature_flag_definitions_and_package(
        format,
        bytes,
        properties_encoding,
        android_resource_path,
        android_feature_flags,
        selected_products,
        None,
    )
}

/// Parse ordered Android build flags, selected products, and qualified local macro ownership.
pub fn parse_with_android_feature_flag_definitions_and_package(
    format: FileFormat,
    bytes: &[u8],
    properties_encoding: Option<&str>,
    android_resource_path: Option<&str>,
    android_feature_flags: &[AndroidFeatureFlag],
    selected_products: Option<&[String]>,
    application_package: Option<&str>,
) -> Result<Catalog, ParseError> {
    let values = android_feature_flag_values(android_feature_flags)?;
    parse_with_android_context(
        format,
        bytes,
        properties_encoding,
        android_resource_path,
        &values,
        selected_products,
        application_package,
    )
}

fn decode(bytes: &[u8], encoding: Option<&str>) -> Result<String, ParseError> {
    if bytes.starts_with(&[0xff, 0xfe])
        || bytes.starts_with(&[0xfe, 0xff])
        || matches!(encoding, Some("UTF-16LE" | "UTF-16BE"))
    {
        let marked = bytes.starts_with(&[0xff, 0xfe]) || bytes.starts_with(&[0xfe, 0xff]);
        let little_endian = if marked {
            bytes[0] == 0xff
        } else {
            encoding == Some("UTF-16LE")
        };
        let data = &bytes[usize::from(marked) * 2..];
        if data.len() % 2 != 0 {
            return Err(ParseError::new("INVALID_ENCODING", "Odd UTF-16 byte count"));
        }
        let units: Vec<u16> = data
            .chunks_exact(2)
            .map(|pair| {
                if little_endian {
                    u16::from_le_bytes([pair[0], pair[1]])
                } else {
                    u16::from_be_bytes([pair[0], pair[1]])
                }
            })
            .collect();
        return String::from_utf16(&units)
            .map_err(|error| ParseError::new("INVALID_ENCODING", error.to_string()));
    }
    if encoding == Some("ISO-8859-1") {
        return Ok(bytes.iter().map(|byte| char::from(*byte)).collect());
    }
    if encoding == Some("CP1252") {
        return bytes
            .iter()
            .copied()
            .map(cp1252_character)
            .collect::<Result<String, _>>();
    }
    if encoding == Some("US-ASCII") && bytes.iter().any(|byte| !byte.is_ascii()) {
        return Err(ParseError::new(
            "INVALID_ENCODING",
            "Non-ASCII byte in declared ASCII resource",
        ));
    }
    let input = bytes.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(bytes);
    String::from_utf8(input.to_vec())
        .map_err(|error| ParseError::new("INVALID_ENCODING", error.to_string()))
}

fn gettext_charset(bytes: &[u8]) -> Result<&'static str, ParseError> {
    if bytes.starts_with(&[0xef, 0xbb, 0xbf])
        || bytes.starts_with(&[0xff, 0xfe])
        || bytes.starts_with(&[0xfe, 0xff])
    {
        return Err(ParseError::new(
            "INVALID_GETTEXT_ENCODING",
            "GNU gettext PO files do not accept Unicode byte-order marks",
        ));
    }
    let source: String = bytes.iter().map(|byte| char::from(*byte)).collect();
    let pattern = regex::Regex::new(r#"(?i)Content-Type:[^\r\n]*?charset=([^\s;"\\]+)"#)
        .expect("valid gettext charset pattern");
    let Some(declared) = pattern
        .captures(&source)
        .and_then(|captures| captures.get(1))
    else {
        return if source.to_ascii_lowercase().contains("content-type:") {
            Err(ParseError::new(
                "INVALID_GETTEXT_ENCODING",
                "Gettext Content-Type declares no usable charset",
            ))
        } else {
            Ok("UTF-8")
        };
    };
    match declared.as_str().to_ascii_uppercase().as_str() {
        "UTF-8" => Ok("UTF-8"),
        "ISO-8859-1" | "ISO_8859-1" => Ok("ISO-8859-1"),
        "CP1252" => Ok("CP1252"),
        "ASCII" | "US-ASCII" => Ok("US-ASCII"),
        _ => Err(ParseError::new(
            "INVALID_GETTEXT_ENCODING",
            "Unsupported or nonportable gettext charset",
        )),
    }
}

pub(crate) fn decode_gettext_bytes(bytes: &[u8], encoding: &str) -> Result<String, ParseError> {
    decode(bytes, Some(encoding))
        .map_err(|error| ParseError::new("INVALID_GETTEXT_ENCODING", error.message))
}

pub(crate) fn encode_gettext_character(
    character: char,
    encoding: &str,
) -> Result<Vec<u8>, ParseError> {
    if encoding == "UTF-8" {
        let mut bytes = [0; 4];
        return Ok(character.encode_utf8(&mut bytes).as_bytes().to_vec());
    }
    if encoding == "US-ASCII" {
        return u8::try_from(character as u32)
            .ok()
            .filter(u8::is_ascii)
            .map(|byte| vec![byte])
            .ok_or_else(|| ParseError::new("INVALID_GETTEXT_ENCODING", "Non-ASCII gettext value"));
    }
    if encoding == "ISO-8859-1" {
        return u8::try_from(character as u32)
            .map(|byte| vec![byte])
            .map_err(|_| ParseError::new("INVALID_GETTEXT_ENCODING", "Non-Latin-1 gettext value"));
    }
    cp1252_byte(character)
        .map(|byte| vec![byte])
        .ok_or_else(|| {
            ParseError::new(
                "INVALID_GETTEXT_ENCODING",
                "Unmappable CP1252 gettext value",
            )
        })
}

fn cp1252_character(byte: u8) -> Result<char, ParseError> {
    let character = match byte {
        0x80 => '€',
        0x81 | 0x8d | 0x8f | 0x90 | 0x9d => {
            return Err(ParseError::new(
                "INVALID_ENCODING",
                "Undefined Windows CP1252 character",
            ))
        }
        0x82 => '‚',
        0x83 => 'ƒ',
        0x84 => '„',
        0x85 => '…',
        0x86 => '†',
        0x87 => '‡',
        0x88 => 'ˆ',
        0x89 => '‰',
        0x8a => 'Š',
        0x8b => '‹',
        0x8c => 'Œ',
        0x8e => 'Ž',
        0x91 => '‘',
        0x92 => '’',
        0x93 => '“',
        0x94 => '”',
        0x95 => '•',
        0x96 => '–',
        0x97 => '—',
        0x98 => '˜',
        0x99 => '™',
        0x9a => 'š',
        0x9b => '›',
        0x9c => 'œ',
        0x9e => 'ž',
        0x9f => 'Ÿ',
        _ => char::from(byte),
    };
    Ok(character)
}

fn cp1252_byte(character: char) -> Option<u8> {
    if character as u32 <= 0x7f || (0xa0..=0xff).contains(&(character as u32)) {
        return Some(character as u8);
    }
    (0x80..=0x9f).find(|byte| cp1252_character(*byte).ok() == Some(character))
}

fn parse_formatjs(source: &str) -> Result<Catalog, ParseError> {
    let root: serde_json::Value = serde_json::from_str(source)
        .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?;
    let wrapper = root
        .as_object()
        .ok_or_else(|| ParseError::new("INVALID_FORMATJS", "Expected a message descriptor map"))?;
    let mut catalog = Catalog::new(FileFormat::FormatJsJson);
    let entries = if wrapper.contains_key("schemaVersion") {
        if wrapper
            .get("schemaVersion")
            .and_then(serde_json::Value::as_u64)
            != Some(1)
        {
            return Err(ParseError::new(
                "INVALID_FORMATJS",
                "Unsupported catalog version",
            ));
        }
        catalog.locale = wrapper
            .get("locale")
            .and_then(serde_json::Value::as_str)
            .map(str::to_owned);
        wrapper
            .get("messages")
            .and_then(serde_json::Value::as_object)
            .ok_or_else(|| ParseError::new("INVALID_FORMATJS", "Missing wrapped messages"))?
    } else {
        wrapper
    };
    for (id, descriptor) in entries {
        let (message, description, variants, placeholders, metadata) = match descriptor {
            serde_json::Value::String(message) => (message.clone(), None, None, vec![], Map::new()),
            serde_json::Value::Object(descriptor) => {
                let message = descriptor
                    .get("defaultMessage")
                    .and_then(serde_json::Value::as_str)
                    .ok_or_else(|| {
                        ParseError::new("INVALID_FORMATJS", "Missing string defaultMessage")
                    })?
                    .to_owned();
                let description = descriptor
                    .get("description")
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_owned);
                let variants = descriptor
                    .get("variants")
                    .map(|variants| {
                        let values = variants.as_object().ok_or_else(|| {
                            ParseError::new("INVALID_FORMATJS", "Plural variants must be an object")
                        })?;
                        values
                            .iter()
                            .map(|(category, value)| {
                                Ok((
                                    category.clone(),
                                    value
                                        .as_str()
                                        .ok_or_else(|| {
                                            ParseError::new(
                                                "INVALID_FORMATJS",
                                                "Plural variants must be strings",
                                            )
                                        })?
                                        .to_owned(),
                                ))
                            })
                            .collect::<Result<std::collections::BTreeMap<_, _>, ParseError>>()
                    })
                    .transpose()?;
                let placeholders = descriptor
                    .get("placeholders")
                    .map(|values| {
                        values
                            .as_array()
                            .ok_or_else(|| {
                                ParseError::new("INVALID_FORMATJS", "Placeholders must be an array")
                            })?
                            .iter()
                            .map(|value| {
                                let name = value
                                    .get("name")
                                    .and_then(serde_json::Value::as_str)
                                    .ok_or_else(|| {
                                        ParseError::new(
                                            "INVALID_FORMATJS",
                                            "Missing placeholder name",
                                        )
                                    })?;
                                let source = value
                                    .get("source")
                                    .and_then(serde_json::Value::as_str)
                                    .ok_or_else(|| {
                                        ParseError::new(
                                            "INVALID_FORMATJS",
                                            "Missing placeholder source",
                                        )
                                    })?;
                                let kind =
                                    match value.get("kind").and_then(serde_json::Value::as_str) {
                                        Some("string") => "string",
                                        Some("integer") => "integer",
                                        Some("number") => "number",
                                        Some("character") => "character",
                                        Some("value") => "value",
                                        _ => {
                                            return Err(ParseError::new(
                                                "INVALID_FORMATJS",
                                                "Unknown placeholder kind",
                                            ))
                                        }
                                    };
                                let position = value
                                    .get("position")
                                    .and_then(serde_json::Value::as_u64)
                                    .map(|value| value as usize);
                                Ok(Placeholder {
                                    name: name.to_owned(),
                                    source: source.to_owned(),
                                    kind,
                                    position,
                                    example: value
                                        .get("example")
                                        .and_then(serde_json::Value::as_str)
                                        .map(str::to_owned),
                                })
                            })
                            .collect::<Result<Vec<_>, ParseError>>()
                    })
                    .transpose()?
                    .unwrap_or_default();
                let mut metadata = descriptor
                    .get("metadata")
                    .and_then(serde_json::Value::as_object)
                    .cloned()
                    .unwrap_or_default();
                let extra: Map<String, serde_json::Value> = descriptor
                    .iter()
                    .filter(|(field, _)| {
                        !matches!(
                            field.as_str(),
                            "defaultMessage"
                                | "description"
                                | "variants"
                                | "placeholders"
                                | "metadata"
                        )
                    })
                    .map(|(field, value)| (field.clone(), value.clone()))
                    .collect();
                if !extra.is_empty() {
                    metadata.insert("formatjs".into(), serde_json::Value::Object(extra));
                }
                (message, description, variants, placeholders, metadata)
            }
            _ => {
                return Err(ParseError::new(
                    "INVALID_FORMATJS",
                    "Invalid message descriptor",
                ))
            }
        };
        catalog.insert(
            id.clone(),
            Message::new(message, description, variants, placeholders, metadata),
        )?;
    }
    Ok(catalog)
}
