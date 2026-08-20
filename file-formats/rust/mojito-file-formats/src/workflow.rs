use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::xml::{self, XmlElement, XmlNode};
use regex::Regex;
use serde_json::{Map, Value};
use std::borrow::Cow;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

const UNTRANSLATED: &str = "@#$untranslated$#@";
const DO_NOT_TRANSLATE: &str = "DO NOT TRANSLATE";
const PLURAL_CATEGORIES: [&str; 6] = ["zero", "one", "two", "few", "many", "other"];

/// Validated, format-owned legacy Mojito extraction and localized-output options.
#[derive(Clone, Debug)]
pub struct FilterOptions {
    format: FileFormat,
    values: BTreeMap<String, String>,
    patterns: BTreeMap<String, Regex>,
    inline_patterns: Vec<Regex>,
}

impl FilterOptions {
    pub fn parse(format: FileFormat, options: &[String]) -> Result<Self, ParseError> {
        let mut values = BTreeMap::new();
        for option in options {
            let Some((key, value)) = option.split_once('=') else {
                return Err(invalid_option("Filter options require an equals sign"));
            };
            if key.is_empty() {
                return Err(invalid_option("Filter option names cannot be empty"));
            }
            if !supported(format, key) {
                if format == FileFormat::GettextPo {
                    continue;
                }
                return Err(ParseError::new(
                    "UNSUPPORTED_FILTER_OPTION",
                    format!("Unsupported {} filter option: {key}", format.id()),
                ));
            }
            values.insert(key.to_owned(), value.to_owned());
        }
        let mut patterns = BTreeMap::new();
        let mut inline_patterns = Vec::new();
        for (key, value) in &values {
            if key.ends_with("Pattern") || key == "exceptions" {
                let pattern = Regex::new(value)
                    .map_err(|_| invalid_option(format!("Invalid {key} regular expression")))?;
                patterns.insert(key.clone(), pattern);
            }
        }
        if values
            .get("convertToHtmlCodes")
            .is_some_and(|value| value.eq_ignore_ascii_case("true"))
        {
            let encoded = values.get("codeFinderData").ok_or_else(|| {
                invalid_option("JSON inline-code finder requires a version-one rule configuration")
            })?;
            if !encoded.starts_with("#v1\n") {
                return Err(invalid_option(
                    "JSON inline-code finder requires a version-one rule configuration",
                ));
            }
            let mut expected = None;
            let mut rules = BTreeSet::new();
            for line in encoded.lines() {
                let Some((key, value)) = line.split_once('=') else {
                    continue;
                };
                if key == "count.i" {
                    expected = Some(value.parse::<usize>().map_err(|_| {
                        invalid_option("JSON inline-code finder has an invalid rule count")
                    })?);
                } else if key.strip_prefix("rule").is_some_and(|number| {
                    !number.is_empty() && number.chars().all(|value| value.is_ascii_digit())
                }) {
                    let pattern = Regex::new(value).map_err(|_| {
                        invalid_option("Invalid JSON inline-code regular expression")
                    })?;
                    patterns.insert(key.to_owned(), pattern);
                    rules.insert(key.to_owned());
                }
            }
            if rules.is_empty() || expected != Some(rules.len()) {
                return Err(invalid_option(
                    "JSON inline-code finder rule count does not match its rules",
                ));
            }
            for index in 0..rules.len() {
                let pattern = patterns.get(&format!("rule{index}")).ok_or_else(|| {
                    invalid_option("JSON inline-code finder requires consecutively numbered rules")
                })?;
                inline_patterns.push(pattern.clone());
            }
        }
        let result = Self {
            format,
            values,
            patterns,
            inline_patterns,
        };
        result.validate()?;
        Ok(result)
    }

    pub(crate) fn contains(&self, key: &str) -> bool {
        self.values.contains_key(key)
    }

    pub(crate) fn enabled(&self, key: &str) -> bool {
        self.values
            .get(key)
            .is_some_and(|value| value.eq_ignore_ascii_case("true"))
    }

    pub(crate) fn pattern(&self, key: &str) -> Option<&Regex> {
        self.patterns.get(key)
    }

    fn inline_patterns(&self) -> &[Regex] {
        &self.inline_patterns
    }

    fn indentation(&self) -> usize {
        self.values
            .get("postProcessIndent")
            .map_or(2, |value| value.parse().expect("validated indentation"))
    }

    fn changes_android_output(&self) -> bool {
        [
            "removeDescription",
            "postProcessIndent",
            "postRemoveTranslatableFalse",
            "postEmptyResourcesToEmptyFile",
        ]
        .iter()
        .any(|option| self.contains(option))
    }

    fn validate(&self) -> Result<(), ParseError> {
        for (key, value) in &self.values {
            if matches!(
                key.as_str(),
                "oldEscaping"
                    | "removeDescription"
                    | "postRemoveTranslatableFalse"
                    | "postEmptyResourcesToEmptyFile"
                    | "removeComment"
                    | "useFullKeyPath"
                    | "extractAllPairs"
                    | "noteKeepOrReplace"
                    | "usagesKeepOrReplace"
                    | "convertToHtmlCodes"
                    | "processImageUrls"
                    | "emptyAndNbspNotTranslatable"
            ) && !value.eq_ignore_ascii_case("true")
                && !value.eq_ignore_ascii_case("false")
            {
                return Err(invalid_option(format!(
                    "Boolean filter option {key} requires true or false"
                )));
            }
            if key == "postProcessIndent" && !value.parse::<usize>().is_ok_and(|size| size <= 32) {
                return Err(invalid_option(
                    "Android post-processing indentation must be between 0 and 32",
                ));
            }
        }
        if self.enabled("oldEscaping") {
            return Err(ParseError::new(
                "UNSUPPORTED_FILTER_OPTION",
                "Legacy oldEscaping=true cannot safely replace compiler-correct Android escaping",
            ));
        }
        if self
            .values
            .get("codeFinderData")
            .is_some_and(|value| !value.is_empty())
            && !self.enabled("convertToHtmlCodes")
        {
            return Err(ParseError::new(
                "UNSUPPORTED_FILTER_OPTION",
                "JSON inline-code matching requires convertToHtmlCodes=true",
            ));
        }
        Ok(())
    }
}

fn supported(format: FileFormat, key: &str) -> bool {
    match format {
        FileFormat::Android => matches!(
            key,
            "oldEscaping"
                | "removeDescription"
                | "postProcessIndent"
                | "postRemoveTranslatableFalse"
                | "postEmptyResourcesToEmptyFile"
        ),
        FileFormat::FormatJsJson => matches!(
            key,
            "useFullKeyPath"
                | "extractAllPairs"
                | "exceptions"
                | "codeFinderData"
                | "noteKeyPattern"
                | "usagesKeyPattern"
                | "filePositionPathKeyPattern"
                | "filePositionLineKeyPattern"
                | "filePositionColKeyPattern"
                | "noteKeepOrReplace"
                | "usagesKeepOrReplace"
                | "removeKeySuffix"
                | "convertToHtmlCodes"
        ),
        FileFormat::AppleStrings => key == "removeComment",
        FileFormat::Yaml => matches!(key, "useFullKeyPath" | "extractAllPairs" | "exceptions"),
        FileFormat::Html => matches!(key, "processImageUrls" | "emptyAndNbspNotTranslatable"),
        _ => false,
    }
}

pub(crate) fn parse(
    format: FileFormat,
    source: &[u8],
    options: &[String],
) -> Result<Catalog, ParseError> {
    let options = FilterOptions::parse(format, options)?;
    if format == FileFormat::Html {
        let catalog = crate::html::parse(
            &crate::decode(source, None)?,
            options.enabled("processImageUrls"),
            !options.contains("emptyAndNbspNotTranslatable")
                || options.enabled("emptyAndNbspNotTranslatable"),
        )?;
        return apply_extraction(catalog, source, &options);
    }
    let catalog = if format == FileFormat::Yaml {
        crate::yaml::parse_configured_bytes(source, &options)?
    } else if format == FileFormat::JavaProperties {
        crate::properties::parse_for_mojito(&crate::decode(source, None)?)?
    } else if format == FileFormat::FormatJsJson
        && (!options.values.is_empty() || json_without_comments(source)?.is_some())
    {
        parse_configured_json(source, &options)?
    } else {
        crate::parse(format, source)?
    };
    apply_extraction(catalog, source, &options)
}

pub(crate) fn parse_import(
    format: FileFormat,
    source: &[u8],
    options: &[String],
    target_locale: &str,
    copy_forms: bool,
) -> Result<Catalog, ParseError> {
    let mut extraction_options = Vec::new();
    let mut target_comment = None;
    for option in options {
        if let Some(comment) = option.strip_prefix("targetComment=") {
            target_comment = Some(comment.to_owned());
        } else {
            extraction_options.push(option.clone());
        }
    }
    let mut catalog = if matches!(format, FileFormat::Csv | FileFormat::CsvAdobeMagento) {
        FilterOptions::parse(format, &extraction_options)?;
        crate::csv::parse_import(format, &crate::decode(source, None)?)?
    } else {
        let localized = if format == FileFormat::GettextPo {
            gettext_import_locale(source, target_locale)
        } else {
            Cow::Borrowed(source)
        };
        parse(format, localized.as_ref(), &extraction_options)?
    };
    if !copy_forms && target_comment.is_none() {
        return Ok(catalog);
    }
    if copy_forms
        && !matches!(
            format,
            FileFormat::Android | FileFormat::AppleStringsdict | FileFormat::GettextPo
        )
    {
        return Err(ParseError::new(
            "UNSUPPORTED_IMPORT_POLICY",
            format!("Plural copying is unsupported for {}", format.id()),
        ));
    }
    let categories = if copy_forms {
        let categories = mojito_plural_categories(target_locale);
        if categories.is_empty() {
            return Err(ParseError::new(
                "INVALID_IMPORT_LOCALE",
                "Import requires a supported target locale",
            ));
        }
        categories
    } else {
        std::collections::HashSet::new()
    };
    for message in catalog.messages.values_mut() {
        if let Some(comment) = &target_comment {
            message
                .metadata
                .get_or_insert_default()
                .insert("mojitoTargetComment".into(), Value::String(comment.clone()));
        }
        if !copy_forms {
            continue;
        }
        let metadata = message.metadata.get_or_insert_default();
        if format == FileFormat::AppleStringsdict
            && message.variants.is_none()
            && metadata.get("pluralVariables").is_some()
        {
            let variables = metadata
                .get("pluralVariables")
                .and_then(Value::as_array)
                .ok_or_else(|| {
                    ParseError::new("INVALID_IMPORT_PLURAL", "Invalid Apple import variables")
                })?
                .iter()
                .map(|variable| {
                    variable.as_str().map(str::to_owned).ok_or_else(|| {
                        ParseError::new("INVALID_IMPORT_PLURAL", "Invalid Apple import variable")
                    })
                })
                .collect::<Result<Vec<_>, _>>()?;
            for variable in variables {
                let position = message
                    .placeholders
                    .as_ref()
                    .and_then(|placeholders| {
                        placeholders
                            .iter()
                            .find(|placeholder| placeholder.name == variable)
                    })
                    .and_then(|placeholder| placeholder.position);
                let original = metadata
                    .get("applePluralRules")
                    .and_then(Value::as_object)
                    .and_then(|rules| rules.get(&variable))
                    .and_then(Value::as_object)
                    .and_then(|rule| rule.get("variants"))
                    .and_then(Value::as_object)
                    .ok_or_else(|| {
                        ParseError::new(
                            "INVALID_IMPORT_PLURAL",
                            "Invalid Apple import plural definition",
                        )
                    })?
                    .iter()
                    .map(|(category, value)| {
                        value.as_str().map_or_else(
                            || {
                                Err(ParseError::new(
                                    "INVALID_IMPORT_PLURAL",
                                    "Invalid Apple import plural branch",
                                ))
                            },
                            |source| {
                                let normalized = crate::placeholders::normalize_foundation_plural(
                                    source,
                                    &mut Vec::new(),
                                    &variable,
                                    position,
                                );
                                let conversions =
                                    crate::placeholders::foundation_plural_printf_line_separators(
                                        source, &variable, position,
                                    );
                                Ok((
                                    category.clone(),
                                    crate::apple::without_disabled_foundation_conversions(
                                        &normalized,
                                        &conversions,
                                    )
                                    .0,
                                ))
                            },
                        )
                    })
                    .collect::<Result<BTreeMap<_, _>, _>>()?;
                let completed =
                    complete_import_variants(&original, &categories, target_locale, format)?;
                message.default_message =
                    replace_plural(&message.default_message, &variable, &completed)?;
                copy_apple_import_rule(metadata, &variable, &completed);
            }
            continue;
        }
        let Some(original) = message.variants.as_ref() else {
            continue;
        };
        let gettext_categories = (format == FileFormat::GettextPo)
            .then(|| gettext_import_categories(metadata, original, target_locale));
        let required = gettext_categories.as_ref().unwrap_or(&categories);
        let completed = complete_import_variants(original, required, target_locale, format)?;
        let selector = plural_selector(&message.default_message, metadata)?;
        message.default_message = replace_plural(&message.default_message, &selector, &completed)?;
        copy_import_metadata(metadata, original, &completed, format);
        message.variants = Some(completed);
    }
    Ok(catalog)
}

fn gettext_import_categories(
    metadata: &Map<String, Value>,
    variants: &BTreeMap<String, String>,
    locale: &str,
) -> std::collections::HashSet<String> {
    let mut categories = metadata
        .get("gettextPluralIndexes")
        .and_then(Value::as_object)
        .into_iter()
        .flat_map(|indexes| indexes.values())
        .filter_map(Value::as_str)
        .map(str::to_owned)
        .collect::<std::collections::HashSet<_>>();
    categories.extend(variants.keys().cloned());
    match import_language(locale).as_str() {
        "cs" | "sk" | "lt" => {
            categories.insert("many".into());
        }
        "ru" | "uk" | "be" | "pl" | "sl" => {
            categories.insert("other".into());
        }
        "ga" => {
            categories.insert("many".into());
            categories.insert("other".into());
        }
        _ => {}
    }
    if categories.is_empty() {
        categories.insert("other".into());
    }
    categories
}

fn gettext_import_locale<'a>(source: &'a [u8], target_locale: &str) -> Cow<'a, [u8]> {
    const EMPTY_LANGUAGE: &[u8] = b"\"Language: \\n\"";
    if let Some(start) = source
        .windows(EMPTY_LANGUAGE.len())
        .position(|candidate| candidate == EMPTY_LANGUAGE)
    {
        let mut localized = Vec::with_capacity(source.len() + target_locale.len());
        localized.extend_from_slice(&source[..start]);
        localized.extend_from_slice(b"\"Language: ");
        localized.extend_from_slice(target_locale.as_bytes());
        localized.extend_from_slice(b"\\n\"");
        localized.extend_from_slice(&source[start + EMPTY_LANGUAGE.len()..]);
        return Cow::Owned(localized);
    }
    const LANGUAGE: &[u8] = b"\"Language: ";
    if source
        .windows(LANGUAGE.len())
        .any(|entry| entry == LANGUAGE)
    {
        return Cow::Borrowed(source);
    }
    const HEADER: &[u8] = b"msgid \"\"";
    const TRANSLATION: &[u8] = b"msgstr \"\"";
    let Some(header) = source
        .windows(HEADER.len())
        .position(|value| value == HEADER)
    else {
        return Cow::Borrowed(source);
    };
    let Some(translation) = source[header..]
        .windows(TRANSLATION.len())
        .position(|value| value == TRANSLATION)
        .map(|position| header + position)
    else {
        return Cow::Borrowed(source);
    };
    let Some(next) = source[translation..]
        .iter()
        .position(|value| *value == b'\n')
        .map(|position| translation + position)
    else {
        return Cow::Borrowed(source);
    };
    let newline = if next > 0 && source[next - 1] == b'\r' {
        b"\r\n".as_slice()
    } else {
        b"\n".as_slice()
    };
    let mut localized = Vec::with_capacity(source.len() + target_locale.len() + 16);
    localized.extend_from_slice(&source[..=next]);
    localized.extend_from_slice(LANGUAGE);
    localized.extend_from_slice(target_locale.as_bytes());
    localized.extend_from_slice(b"\\n\"");
    localized.extend_from_slice(newline);
    localized.extend_from_slice(&source[next + 1..]);
    Cow::Owned(localized)
}

fn complete_import_variants(
    original: &BTreeMap<String, String>,
    required: &std::collections::HashSet<String>,
    locale: &str,
    format: FileFormat,
) -> Result<BTreeMap<String, String>, ParseError> {
    let mut completed = BTreeMap::new();
    let language = import_language(locale);
    for category in PLURAL_CATEGORIES {
        if !required.contains(category) {
            continue;
        }
        let source = original
            .get(category)
            .or_else(|| {
                (format == FileFormat::GettextPo && category == "many" && language == "ga")
                    .then(|| original.get("few"))
                    .flatten()
            })
            .or_else(|| {
                (format == FileFormat::GettextPo
                    && category == "other"
                    && matches!(language.as_str(), "ru" | "uk" | "be" | "pl" | "sl"))
                .then(|| original.get("many"))
                .flatten()
            })
            .or_else(|| original.get("other"))
            .ok_or_else(|| {
                ParseError::new(
                    "INVALID_IMPORT_PLURAL",
                    format!("Cannot synthesize import plural category: {category}"),
                )
            })?;
        completed.insert(category.to_owned(), source.clone());
    }
    Ok(completed)
}

fn import_language(locale: &str) -> String {
    locale
        .split(['-', '_'])
        .next()
        .unwrap_or(locale)
        .to_ascii_lowercase()
}

fn plural_selector(message: &str, metadata: &Map<String, Value>) -> Result<String, ParseError> {
    if let Some(variable) = metadata.get("pluralVariable").and_then(Value::as_str) {
        return Ok(variable.to_owned());
    }
    static SELECTOR: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    SELECTOR
        .get_or_init(|| Regex::new(r"\{([^,{}]+),\s*plural,").expect("valid plural selector"))
        .captures(message)
        .and_then(|captures| captures.get(1))
        .map(|value| value.as_str().to_owned())
        .ok_or_else(|| ParseError::new("INVALID_IMPORT_PLURAL", "Missing import plural selector"))
}

fn replace_plural(
    message: &str,
    selector: &str,
    variants: &BTreeMap<String, String>,
) -> Result<String, ParseError> {
    let marker = format!("{{{selector}, plural,");
    let mut start = message
        .find(&marker)
        .ok_or_else(|| ParseError::new("INVALID_IMPORT_PLURAL", "Missing import plural branch"))?;
    let plural = crate::placeholders::plural(
        selector,
        PLURAL_CATEGORIES.iter().filter_map(|category| {
            variants
                .get(*category)
                .map(|value| (*category, value.as_str()))
        }),
    );
    let mut result = String::with_capacity(message.len() + plural.len());
    let mut copied = 0;
    loop {
        let mut depth = 0;
        let mut end = None;
        for (offset, character) in message[start..].char_indices() {
            if character == '{' {
                depth += 1;
            } else if character == '}' {
                depth -= 1;
                if depth == 0 {
                    end = Some(start + offset + character.len_utf8());
                    break;
                }
            }
        }
        let end = end.ok_or_else(|| {
            ParseError::new("INVALID_IMPORT_PLURAL", "Unclosed import plural branch")
        })?;
        result.push_str(&message[copied..start]);
        result.push_str(&plural);
        copied = end;
        let Some(next) = message[copied..].find(&marker) else {
            result.push_str(&message[copied..]);
            return Ok(result);
        };
        start = copied + next;
    }
}

fn copy_import_metadata(
    metadata: &mut Map<String, Value>,
    original: &BTreeMap<String, String>,
    completed: &BTreeMap<String, String>,
    format: FileFormat,
) {
    for (key, value) in metadata.iter_mut() {
        if !key.starts_with("androidPlural") {
            continue;
        }
        let Some(values) = value.as_object_mut() else {
            continue;
        };
        let previous = std::mem::take(values);
        for category in completed.keys() {
            if let Some(value) = previous.get(category).or_else(|| {
                (!original.contains_key(category))
                    .then(|| previous.get("other"))
                    .flatten()
            }) {
                values.insert(category.clone(), value.clone());
            }
        }
    }
    if format == FileFormat::AppleStringsdict {
        let Some(variable) = metadata
            .get("pluralVariable")
            .and_then(Value::as_str)
            .map(str::to_owned)
        else {
            return;
        };
        copy_apple_import_rule(metadata, &variable, completed);
    }
}

fn copy_apple_import_rule(
    metadata: &mut Map<String, Value>,
    variable: &str,
    completed: &BTreeMap<String, String>,
) {
    let Some(source_variants) = metadata
        .get_mut("applePluralRules")
        .and_then(Value::as_object_mut)
        .and_then(|rules| rules.get_mut(variable))
        .and_then(Value::as_object_mut)
        .and_then(|rule| rule.get_mut("variants"))
        .and_then(Value::as_object_mut)
    else {
        return;
    };
    let previous = std::mem::take(source_variants);
    for category in completed.keys() {
        if let Some(value) = previous.get(category).or_else(|| previous.get("other")) {
            source_variants.insert(category.clone(), value.clone());
        }
    }
    for key in ["devicePluralVariants", "deviceMixedVariants"] {
        let Some(devices) = metadata.get_mut(key).and_then(Value::as_object_mut) else {
            continue;
        };
        for branch in devices.values_mut() {
            let Some(rule) = branch
                .as_object_mut()
                .and_then(|branch| branch.get_mut(variable))
                .and_then(Value::as_object_mut)
            else {
                continue;
            };
            if rule
                .get("NSStringFormatSpecTypeKey")
                .and_then(Value::as_str)
                != Some("NSStringPluralRuleType")
            {
                continue;
            }
            let Some(other) = rule.get("other").cloned() else {
                continue;
            };
            for category in completed.keys() {
                rule.entry(category.clone())
                    .or_insert_with(|| other.clone());
            }
        }
    }
    let Some(conversions) = metadata
        .get_mut("applePluralDisabledPrintfConversions")
        .and_then(Value::as_object_mut)
        .and_then(|variables| variables.get_mut(variable))
        .and_then(Value::as_object_mut)
    else {
        return;
    };
    let previous = std::mem::take(conversions);
    for category in completed.keys() {
        if let Some(value) = previous.get(category).or_else(|| previous.get("other")) {
            conversions.insert(category.clone(), value.clone());
        }
    }
}

pub(crate) fn localize(
    format: FileFormat,
    source: &[u8],
    translations: &BTreeMap<String, String>,
    options: &[String],
    remove_untranslated: bool,
    target_locale: Option<&str>,
) -> Result<Vec<u8>, ParseError> {
    let options = FilterOptions::parse(format, options)?;
    if format == FileFormat::FormatJsJson {
        return localize_json(source, translations, &options, remove_untranslated);
    }
    if matches!(format, FileFormat::Csv | FileFormat::CsvAdobeMagento) {
        return crate::csv::localize(format, source, translations, remove_untranslated);
    }
    if format == FileFormat::Html {
        let skeleton = crate::html::extract(
            source,
            options.enabled("processImageUrls"),
            !options.contains("emptyAndNbspNotTranslatable")
                || options.enabled("emptyAndNbspNotTranslatable"),
        )?;
        return crate::html::render_for_mojito(&skeleton, translations, remove_untranslated);
    }
    let catalog = apply_extraction(
        if format == FileFormat::Yaml {
            crate::yaml::parse_configured_bytes(source, &options)?
        } else {
            crate::parse(format, source)?
        },
        source,
        &options,
    )?;
    let target_categories = if matches!(format, FileFormat::Android | FileFormat::AppleStringsdict)
    {
        target_locale
            .map(mojito_plural_categories)
            .unwrap_or_default()
    } else {
        std::collections::HashSet::new()
    };
    let expanded_source = if format == FileFormat::Android
        && has_additional_android_plural_translations(&catalog, translations, &target_categories)
    {
        Some(crate::source_skeleton::retain_android_plural_categories(
            source,
            &target_categories,
        )?)
    } else if format == FileFormat::AppleStringsdict && !target_categories.is_empty() {
        // Rendering must see target-only slots so distinct translations do not fall back to `other`.
        Some(retain_apple_plural_categories(
            source,
            target_locale.expect("plural categories require a target locale"),
        )?)
    } else {
        None
    };
    let mut skeleton = if format == FileFormat::Yaml {
        crate::yaml::extract_configured(expanded_source.as_deref().unwrap_or(source), &options)?
    } else {
        crate::extract_skeleton(format, expanded_source.as_deref().unwrap_or(source))?
    };
    let mut selected = BTreeMap::new();
    let mut untranslated_keys = BTreeSet::new();
    let mut untranslated_marker = UNTRANSLATED.to_owned();
    if remove_untranslated && matches!(format, FileFormat::GettextPo | FileFormat::Android) {
        while translations
            .values()
            .any(|value| value == &untranslated_marker)
        {
            untranslated_marker.push('#');
        }
        if format == FileFormat::Android {
            while skeleton.source.contains(&untranslated_marker) {
                untranslated_marker.push('#');
            }
        }
    }
    for slot in &skeleton.slots {
        if !catalog.messages.contains_key(&slot.id) {
            continue;
        }
        let key = slot.key();
        if let Some(value) = translations.get(&key) {
            selected.insert(key, value.clone());
        } else if format == FileFormat::Android
            && slot.variant.as_ref().is_some_and(|category| {
                !catalog.messages[&slot.id]
                    .variants
                    .as_ref()
                    .is_some_and(|variants| variants.contains_key(category))
            })
            && translations.contains_key(&format!("{}#other", slot.id))
        {
            selected.insert(key, translations[&format!("{}#other", slot.id)].clone());
        } else if format == FileFormat::AppleStringsdict
            && slot.variant.is_some()
            && !has_apple_plural_category(&catalog.messages[&slot.id], slot)
            && translations.contains_key(&apple_other_key(slot))
        {
            selected.insert(key, translations[&apple_other_key(slot)].clone());
        } else if remove_untranslated {
            if matches!(
                format,
                FileFormat::Android | FileFormat::AppleStrings | FileFormat::GettextPo
            ) {
                selected.insert(key.clone(), untranslated_marker.clone());
            }
            untranslated_keys.insert(key);
        }
    }
    for key in translations.keys() {
        if !selected.contains_key(key) {
            return Err(ParseError::new(
                "UNKNOWN_SKELETON_SLOT",
                format!("Translation has no translatable source slot: {key}"),
            ));
        }
    }
    if format == FileFormat::AppleStringsdict && remove_untranslated {
        let mut missing_messages = catalog.messages.keys().cloned().collect::<BTreeSet<_>>();
        let mut missing_required_other = BTreeSet::new();
        for slot in &skeleton.slots {
            if translations.contains_key(&slot.key()) {
                missing_messages.remove(&slot.id);
            } else if slot.variant.as_deref() == Some("other") {
                missing_required_other.insert(slot.id.clone());
            }
        }
        missing_messages.extend(missing_required_other);
        if !missing_messages.is_empty() {
            let removed_slots = skeleton
                .slots
                .iter()
                .filter(|slot| missing_messages.contains(&slot.id))
                .map(|slot| slot.key())
                .collect::<BTreeSet<_>>();
            let retained =
                crate::source_skeleton::remove_stringsdict_messages(&skeleton, &missing_messages)?;
            skeleton = crate::extract_skeleton(format, &retained)?;
            selected.retain(|key, _| !removed_slots.contains(key));
        }
        let retained =
            crate::source_skeleton::remove_stringsdict_entries(&skeleton, &untranslated_keys)?;
        skeleton = crate::extract_skeleton(format, &retained)?;
        selected.retain(|key, _| !untranslated_keys.contains(key));
    }
    if remove_untranslated && matches!(format, FileFormat::Resx | FileFormat::Xtb) {
        let retained = crate::xml_resources::remove_entries(&skeleton, &untranslated_keys)?;
        skeleton = crate::extract_skeleton(format, &retained)?;
        selected.retain(|key, _| !untranslated_keys.contains(key));
    } else if remove_untranslated && format == FileFormat::Yaml {
        let retained = crate::yaml::remove_entries(&skeleton, &untranslated_keys, &options)?;
        selected.retain(|key, _| !untranslated_keys.contains(key));
        if selected.is_empty() {
            return Ok(retained);
        }
        skeleton = crate::yaml::extract_configured(&retained, &options)?;
    } else if remove_untranslated
        && matches!(format, FileFormat::JavaScript | FileFormat::TypeScript)
    {
        let retained = crate::javascript::remove_entries(&skeleton, &untranslated_keys)?;
        skeleton = crate::extract_skeleton(format, &retained)?;
        selected.retain(|key, _| !untranslated_keys.contains(key));
    } else if remove_untranslated && format == FileFormat::AppleXcstrings {
        let retained =
            crate::source_skeleton::remove_xcstrings_entries(&skeleton, &untranslated_keys)?;
        skeleton = crate::extract_skeleton(format, &retained)?;
        let retained_keys = skeleton
            .slots
            .iter()
            .map(crate::source_skeleton::SourceSlot::key)
            .collect::<BTreeSet<_>>();
        selected.retain(|key, _| retained_keys.contains(key));
    }
    let mut localized = crate::render_skeleton(&skeleton, &selected)?;
    if format == FileFormat::Android {
        if let Some(locale) = target_locale {
            localized = crate::source_skeleton::retain_android_plural_categories(
                &localized,
                &mojito_plural_categories(locale),
            )?;
        }
    }
    if format == FileFormat::Android && (remove_untranslated || options.changes_android_output()) {
        return android_output(
            &localized,
            &skeleton.encoding,
            &options,
            remove_untranslated,
            &untranslated_marker,
        );
    }
    let encoding = crate::source_skeleton::Encoding::named(&skeleton.encoding)?;
    let mut text = encoding.decode(&localized[encoding.bom_length()..])?;
    if format == FileFormat::AppleStrings {
        text = apple_output(
            &text,
            options.enabled("removeComment"),
            remove_untranslated,
            &untranslated_keys,
        )?;
    } else if format == FileFormat::JavaProperties && remove_untranslated {
        text = crate::source_skeleton::remove_property_entries(&text, &untranslated_keys)?;
    } else if format == FileFormat::GettextPo && remove_untranslated {
        text = gettext_output(&text, &untranslated_marker)?;
    }
    Ok(encoding.encode(&text))
}

fn mojito_plural_categories(locale: &str) -> std::collections::HashSet<String> {
    let categories = crate::source_skeleton::cardinal_categories(locale);
    if categories.is_empty() {
        return categories;
    }
    let language = locale
        .split(['-', '_'])
        .next()
        .unwrap_or_default()
        .to_ascii_lowercase();
    let overrides: &[&str] = match language.as_str() {
        "ca" | "es" | "fr" | "it" | "pt" | "scn" => &["one", "other"],
        "mt" => &["few", "many", "one", "other"],
        "he" | "iw" => &["many", "one", "two", "other"],
        _ => return categories,
    };
    overrides
        .iter()
        .map(|category| (*category).to_owned())
        .collect()
}

fn has_additional_android_plural_translations(
    catalog: &crate::model::Catalog,
    translations: &BTreeMap<String, String>,
    categories: &std::collections::HashSet<String>,
) -> bool {
    translations.keys().any(|key| {
        key.rsplit_once('#').is_some_and(|(id, category)| {
            categories.contains(category)
                && catalog
                    .messages
                    .get(id)
                    .and_then(|message| message.variants.as_ref())
                    .is_some_and(|variants| !variants.contains_key(category))
        })
    })
}

fn has_apple_plural_category(
    message: &crate::model::Message,
    slot: &crate::source_skeleton::SourceSlot,
) -> bool {
    let Some(category) = slot.variant.as_deref() else {
        return false;
    };
    let Some(selector) = slot.selector.as_deref() else {
        return message
            .variants
            .as_ref()
            .is_some_and(|variants| variants.contains_key(category));
    };
    message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("applePluralRules"))
        .and_then(serde_json::Value::as_object)
        .and_then(|rules| rules.get(selector))
        .and_then(serde_json::Value::as_object)
        .and_then(|rule| rule.get("variants"))
        .and_then(serde_json::Value::as_object)
        .is_some_and(|variants| variants.contains_key(category))
}

fn apple_other_key(slot: &crate::source_skeleton::SourceSlot) -> String {
    slot.selector.as_ref().map_or_else(
        || format!("{}#other", slot.id),
        |selector| format!("{}#{selector}#other", slot.id),
    )
}

fn retain_apple_plural_categories(source: &[u8], locale: &str) -> Result<Vec<u8>, ParseError> {
    let categories = mojito_plural_categories(locale);
    if categories.is_empty() || source.starts_with(b"bplist") {
        return Ok(source.to_vec());
    }
    let completed = complete_apple_plural_categories(source, &categories)?;
    let source = completed.as_slice();
    let skeleton = crate::extract_skeleton_with_apple_variations(source)?;
    let encoding = crate::source_skeleton::Encoding::named(&skeleton.encoding)?;
    let mut text = skeleton.source;
    for slot in skeleton.slots.iter().rev() {
        let Some(category) = slot.variant.as_deref() else {
            continue;
        };
        if !PLURAL_CATEGORIES.contains(&category)
            || categories.contains(category)
            || matches!(slot.selector.as_deref(), Some("@width" | "@device"))
        {
            continue;
        }
        let value_start = encoding
            .decode(&source[encoding.bom_length()..slot.start])?
            .len();
        let value_end = encoding
            .decode(&source[encoding.bom_length()..slot.end])?
            .len();
        let value_opening = text[..value_start]
            .rfind('<')
            .ok_or_else(|| invalid_plural_output("Foundation plural has no opening string tag"))?;
        let key_closing = text[..value_opening]
            .rfind("</key>")
            .ok_or_else(|| invalid_plural_output("Foundation plural value has no source key"))?;
        let key_opening = text[..key_closing]
            .rfind("<key")
            .ok_or_else(|| invalid_plural_output("Foundation plural source key is incomplete"))?;
        let key_body = key_opening
            + text[key_opening..]
                .find('>')
                .ok_or_else(|| invalid_plural_output("Foundation plural key is not closed"))?
            + 1;
        if text[key_body..key_closing].trim() != category
            || !text[key_closing + "</key>".len()..value_opening]
                .trim()
                .is_empty()
        {
            return Err(invalid_plural_output(
                "Foundation plural key does not own its source value",
            ));
        }
        let mut end = if text[value_end..].starts_with("</string>") {
            value_end + "</string>".len()
        } else if text[value_start..].starts_with('/') && text[..value_end].ends_with('>') {
            value_end
        } else {
            return Err(invalid_plural_output(
                "Foundation plural value has no closing string tag",
            ));
        };
        let line_start = text[..key_opening]
            .rfind(['\n', '\r'])
            .map_or(0, |index| index + 1);
        let start = if text[line_start..key_opening].trim().is_empty() {
            line_start
        } else {
            key_opening
        };
        let mut next = end;
        while matches!(text.as_bytes().get(next), Some(b' ' | b'\t')) {
            next += 1;
        }
        if text.as_bytes().get(next) == Some(&b'\r') {
            next += 1;
        }
        if text.as_bytes().get(next) == Some(&b'\n') {
            end = next + 1;
        }
        text.replace_range(start..end, "");
    }
    Ok(encoding.encode(&text))
}

fn complete_apple_plural_categories(
    source: &[u8],
    categories: &std::collections::HashSet<String>,
) -> Result<Vec<u8>, ParseError> {
    let skeleton = crate::extract_skeleton_with_apple_variations(source)?;
    let encoding = crate::source_skeleton::Encoding::named(&skeleton.encoding)?;
    let text = skeleton.source;
    let mut groups = BTreeMap::<(String, String), Vec<ApplePluralSourceValue>>::new();
    for slot in &skeleton.slots {
        let Some(category) = slot.variant.as_deref() else {
            continue;
        };
        if !PLURAL_CATEGORIES.contains(&category)
            || matches!(slot.selector.as_deref(), Some("@width" | "@device"))
        {
            continue;
        }
        let value_start = encoding
            .decode(&source[encoding.bom_length()..slot.start])?
            .len();
        let value_end = encoding
            .decode(&source[encoding.bom_length()..slot.end])?
            .len();
        let value_opening = text[..value_start]
            .rfind('<')
            .ok_or_else(|| invalid_plural_output("Foundation plural has no opening string tag"))?;
        let key_end = text[..value_opening]
            .rfind("</key>")
            .ok_or_else(|| invalid_plural_output("Foundation plural value has no source key"))?;
        let key_opening = text[..key_end]
            .rfind("<key")
            .ok_or_else(|| invalid_plural_output("Foundation plural source key is incomplete"))?;
        let key_start = key_opening
            + text[key_opening..]
                .find('>')
                .ok_or_else(|| invalid_plural_output("Foundation plural key is not closed"))?
            + 1;
        if text[key_start..key_end].trim() != category {
            return Err(invalid_plural_output(
                "Foundation plural key does not own its source value",
            ));
        }
        let mut end = if text[value_end..].starts_with("</string>") {
            value_end + "</string>".len()
        } else if text[value_start..].starts_with('/') && text[..value_end].ends_with('>') {
            value_end
        } else {
            return Err(invalid_plural_output(
                "Foundation plural value has no closing string tag",
            ));
        };
        let line_start = text[..key_opening]
            .rfind(['\n', '\r'])
            .map_or(0, |index| index + 1);
        let start = if text[line_start..key_opening].trim().is_empty() {
            line_start
        } else {
            key_opening
        };
        while matches!(text.as_bytes().get(end), Some(b' ' | b'\t')) {
            end += 1;
        }
        if text.as_bytes().get(end) == Some(&b'\r') {
            end += 1;
        }
        if text.as_bytes().get(end) == Some(&b'\n') {
            end += 1;
        }
        groups
            .entry((slot.id.clone(), slot.selector.clone().unwrap_or_default()))
            .or_default()
            .push(ApplePluralSourceValue {
                category: category.to_owned(),
                start,
                end,
                key_start,
                key_end,
            });
    }

    let mut insertions = BTreeMap::<usize, String>::new();
    for group in groups.values() {
        let Some(fallback) = group.iter().find(|value| value.category == "other") else {
            continue;
        };
        for (rank, category) in PLURAL_CATEGORIES.iter().enumerate() {
            if !categories.contains(*category)
                || group.iter().any(|value| value.category == *category)
            {
                continue;
            }
            let position = group
                .iter()
                .find(|value| {
                    PLURAL_CATEGORIES
                        .iter()
                        .position(|candidate| *candidate == value.category)
                        .is_some_and(|candidate| candidate > rank)
                })
                .map_or(group.last().expect("nonempty plural group").end, |next| {
                    next.start
                });
            let template = &text[fallback.start..fallback.end];
            let key_start = fallback.key_start - fallback.start;
            let key_end = fallback.key_end - fallback.start;
            let cloned = format!(
                "{}{}{}",
                &template[..key_start],
                category,
                &template[key_end..]
            );
            insertions.entry(position).or_default().push_str(&cloned);
        }
    }
    if insertions.is_empty() {
        return Ok(source.to_vec());
    }
    let mut completed = text;
    for (position, values) in insertions.into_iter().rev() {
        completed.insert_str(position, &values);
    }
    Ok(encoding.encode(&completed))
}

struct ApplePluralSourceValue {
    category: String,
    start: usize,
    end: usize,
    key_start: usize,
    key_end: usize,
}

fn invalid_plural_output(message: impl Into<String>) -> ParseError {
    ParseError::new("INVALID_SKELETON", message)
}

fn apply_extraction(
    original: Catalog,
    source: &[u8],
    options: &FilterOptions,
) -> Result<Catalog, ParseError> {
    let android_notes = if options.format == FileFormat::Android {
        let declared = crate::xml_encoding(FileFormat::Android, source)?;
        android_notes(&crate::decode(source, declared)?)?
    } else {
        BTreeMap::new()
    };
    let apple_source = if matches!(
        options.format,
        FileFormat::AppleStrings | FileFormat::AppleStringsdict
    ) {
        Some(crate::decode(
            source,
            crate::xml_encoding(options.format, source)?,
        )?)
    } else {
        None
    };
    let apple_notes = match options.format {
        FileFormat::AppleStrings => apple_notes(apple_source.as_deref().unwrap())?,
        FileFormat::AppleStringsdict => apple_stringsdict_notes(apple_source.as_deref().unwrap())?,
        _ => BTreeMap::new(),
    };
    let apple_legacy_names = if options.format == FileFormat::AppleStrings {
        apple_legacy_names(apple_source.as_deref().unwrap())?
    } else {
        BTreeMap::new()
    };
    let mut result = Catalog::new(options.format);
    result.locale = original.locale;
    static LOCATIONS: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let locations = LOCATIONS.get_or_init(|| {
        Regex::new(r"(?s)\s*<locations>\s*(.*?)\s*</locations>").expect("valid Apple usage pattern")
    });
    for (id, mut message) in original.messages {
        if options.format == FileFormat::Android
            && message.default_message.is_empty()
            && message
                .metadata
                .as_ref()
                .is_some_and(|metadata| metadata.contains_key("arrayIndex"))
        {
            continue;
        }
        if let Some(note) = android_notes.get(resource_identity(&id)) {
            message.description = Some(note.clone());
        }
        if let Some(note) = apple_notes.get(&id) {
            message.description = Some(note.clone());
        }
        if options.format == FileFormat::AppleStrings {
            if let Some(name) = apple_legacy_names.get(&id) {
                if name != &id {
                    message
                        .metadata
                        .get_or_insert_with(Map::new)
                        .insert("appleLegacyName".into(), Value::String(name.clone()));
                }
            }
        }
        if matches!(
            options.format,
            FileFormat::AppleStrings | FileFormat::AppleStringsdict
        ) {
            if let Some(description) = message.description.clone() {
                if description.trim() == "No comment provided by engineer." {
                    message.description = None;
                } else if let Some(captures) = locations.captures(&description) {
                    let usages = captures[1]
                        .lines()
                        .map(str::trim)
                        .filter(|value| !value.is_empty())
                        .collect::<BTreeSet<_>>()
                        .into_iter()
                        .map(|value| Value::String(value.to_owned()))
                        .collect::<Vec<_>>();
                    if !usages.is_empty() {
                        message
                            .metadata
                            .get_or_insert_with(Map::new)
                            .insert("references".into(), Value::Array(usages));
                    }
                    let stripped = locations.replace(&description, "").into_owned();
                    message.description = (!stripped.trim().is_empty()).then_some(stripped);
                }
            }
        }
        if message
            .description
            .as_ref()
            .is_some_and(|note| note.contains(DO_NOT_TRANSLATE))
        {
            continue;
        }
        result.insert(id, message)?;
    }
    Ok(result)
}

fn resource_identity(id: &str) -> &str {
    id.split(['@', '[']).next().unwrap_or(id)
}

fn apple_notes(source: &str) -> Result<BTreeMap<String, String>, ParseError> {
    static PATTERN: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let pattern = PATTERN.get_or_init(|| {
        Regex::new(r#"(?s)/\*(.*?)\*/\s*(\"(?:\\.|[^\"\\])*\")\s*="#)
            .expect("valid Apple comment ownership pattern")
    });
    let mut result = BTreeMap::new();
    for captured in pattern.captures_iter(source) {
        result.insert(
            crate::apple::decode_source_token(&captured[2])?,
            captured[1].to_owned(),
        );
    }
    Ok(result)
}

fn apple_legacy_names(source: &str) -> Result<BTreeMap<String, String>, ParseError> {
    static PATTERN: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let pattern = PATTERN.get_or_init(|| {
        Regex::new(r#"(\"((?:\\.|[^\"\\])*)\")\s*="#)
            .expect("valid Apple raw-key ownership pattern")
    });
    let mut result = BTreeMap::new();
    for captured in pattern.captures_iter(source) {
        result.insert(
            crate::apple::decode_source_token(&captured[1])?,
            captured[2].to_owned(),
        );
    }
    Ok(result)
}

fn apple_stringsdict_notes(source: &str) -> Result<BTreeMap<String, String>, ParseError> {
    let root = xml::parse_apple_plist(source)?;
    let Some(dictionary) = root.elements().find(|element| element.name == "dict") else {
        return Ok(BTreeMap::new());
    };
    let mut notes = BTreeMap::new();
    let mut id = None;
    for child in &dictionary.children {
        match child {
            XmlNode::Element(element) if element.name == "key" => id = Some(element.text()),
            XmlNode::Element(element) if element.name == "dict" => {
                if let Some(name) = id.take() {
                    for field in &element.children {
                        match field {
                            XmlNode::Comment(comment) if !comment.trim().is_empty() => {
                                notes.insert(name.clone(), comment.trim().to_owned());
                            }
                            XmlNode::Element(_) => break,
                            _ => {}
                        }
                    }
                }
            }
            _ => {}
        }
    }
    Ok(notes)
}

fn android_notes(source: &str) -> Result<BTreeMap<String, String>, ParseError> {
    let root = xml::parse(source)?;
    let mut result = BTreeMap::new();
    let mut comments = Vec::new();
    for child in &root.children {
        match child {
            XmlNode::Comment(value) => comments.push(value.trim().to_owned()),
            XmlNode::Element(element) if element.namespace.is_none() => {
                if matches!(element.local_name(), "skip" | "eat-comment") {
                    comments.clear();
                    continue;
                }
                let note = element
                    .attribute("description")
                    .map(str::to_owned)
                    .unwrap_or_else(|| comments.join(" "));
                if !note.trim().is_empty() {
                    if let Some(name) = element.attribute("name") {
                        result.insert(name.to_owned(), note);
                    }
                }
                comments.clear();
            }
            _ => {}
        }
    }
    Ok(result)
}

#[derive(Clone, Default)]
struct JsonContext {
    note: Option<String>,
    usages: Vec<String>,
}

fn parse_configured_json(source: &[u8], options: &FilterOptions) -> Result<Catalog, ParseError> {
    let cleaned = json_without_comments(source)?;
    let root: Value = serde_json::from_slice(cleaned.as_deref().unwrap_or(source))
        .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?;
    if !root.is_object() {
        return Err(ParseError::new(
            "INVALID_FORMATJS",
            "Expected a JSON object",
        ));
    }
    let mut catalog = Catalog::new(FileFormat::FormatJsJson);
    let comments = json_comments(source)?;
    collect_json(
        &root,
        "",
        &JsonContext::default(),
        options,
        &mut catalog,
        &comments,
    )?;
    Ok(catalog)
}

fn json_comments(source: &[u8]) -> Result<BTreeMap<String, String>, ParseError> {
    static LINE_COMMENTS: OnceLock<Regex> = OnceLock::new();
    static BLOCK_COMMENTS: OnceLock<Regex> = OnceLock::new();
    let text = std::str::from_utf8(source)
        .map_err(|_| ParseError::new("INVALID_FORMATJS", "JSON source must be UTF-8"))?;
    let line = LINE_COMMENTS.get_or_init(|| {
        Regex::new(r#"(?m)//([^\r\n]*)\r?\n[ \t]*\"((?:\\.|[^\"\\])*)\"[ \t]*:"#)
            .expect("valid JSON line-comment pattern")
    });
    let block = BLOCK_COMMENTS.get_or_init(|| {
        Regex::new(r#"(?s)/\*(.*?)\*/[ \t\r\n]*\"((?:\\.|[^\"\\])*)\"[ \t]*:"#)
            .expect("valid JSON block-comment pattern")
    });
    let mut comments = BTreeMap::new();
    for pattern in [&line, &block] {
        for capture in pattern.captures_iter(text) {
            let key: String = serde_json::from_str(&format!("\"{}\"", &capture[2]))
                .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?;
            comments.insert(key, capture[1].trim().to_owned());
        }
    }
    Ok(comments)
}

fn collect_json(
    object: &Value,
    parent: &str,
    inherited: &JsonContext,
    options: &FilterOptions,
    catalog: &mut Catalog,
    comments: &BTreeMap<String, String>,
) -> Result<(), ParseError> {
    let Some(entries) = object.as_object() else {
        return Ok(());
    };
    let context = json_context(entries, parent, inherited, options);
    for (key, value) in entries {
        let path = if parent.is_empty() {
            key.clone()
        } else {
            format!("{parent}/{key}")
        };
        if value.is_object() {
            collect_json(value, &path, &context, options, catalog, comments)?;
        } else if let Some(values) = value.as_array() {
            for (index, entry) in values.iter().enumerate() {
                if entry.is_object() {
                    collect_json(
                        entry,
                        &format!("{path}/{index}"),
                        &context,
                        options,
                        catalog,
                        comments,
                    )?;
                }
            }
        } else if let Some(message) = value.as_str() {
            if !selected(&path, key, options) {
                continue;
            }
            let id = json_identity(&path, key, options);
            let mut metadata = Map::new();
            if !context.usages.is_empty() {
                metadata.insert("references".into(), serde_json::json!(context.usages));
            }
            let mut message = message.to_owned();
            if options.enabled("convertToHtmlCodes") {
                let (protected, codes) = protect_inline_codes(&message, options.inline_patterns());
                message = protected;
                if !codes.is_empty() {
                    metadata.insert("mojitoInlineCodes".into(), Value::Array(codes));
                }
            }
            catalog.insert(
                id,
                Message::new(
                    message,
                    context.note.clone().or_else(|| comments.get(key).cloned()),
                    None,
                    vec![],
                    metadata,
                ),
            )?;
        }
    }
    Ok(())
}

fn json_context(
    object: &Map<String, Value>,
    parent: &str,
    inherited: &JsonContext,
    options: &FilterOptions,
) -> JsonContext {
    let mut context = JsonContext {
        note: options
            .enabled("noteKeepOrReplace")
            .then(|| inherited.note.clone())
            .flatten(),
        usages: if options.enabled("usagesKeepOrReplace") {
            inherited.usages.clone()
        } else {
            Vec::new()
        },
    };
    let mut found_usage = false;
    let mut position_path = None;
    let mut line = None;
    let mut column = None;
    for (key, value) in object {
        let path = if parent.is_empty() {
            key.clone()
        } else {
            format!("{parent}/{key}")
        };
        if let Some(value) = value.as_str() {
            if matches_pattern(options.pattern("noteKeyPattern"), &path, key) {
                context.note = Some(value.to_owned());
            }
            if matches_pattern(options.pattern("usagesKeyPattern"), &path, key) {
                if options.enabled("usagesKeepOrReplace") || !found_usage {
                    context.usages.clear();
                }
                context.usages.push(value.to_owned());
                found_usage = true;
            }
            if matches_pattern(options.pattern("filePositionPathKeyPattern"), &path, key) {
                position_path = Some(value.to_owned());
            }
        }
        let number = value
            .as_i64()
            .and_then(|number| i32::try_from(number).ok())
            .or_else(|| {
                value
                    .as_str()
                    .filter(|text| !text.starts_with('+'))
                    .and_then(|text| text.parse::<i32>().ok())
            });
        if matches_pattern(options.pattern("filePositionLineKeyPattern"), &path, key) {
            line = number;
        }
        if matches_pattern(options.pattern("filePositionColKeyPattern"), &path, key) {
            column = number;
        }
    }
    if let Some(path) = position_path {
        let mut location = path;
        if let Some(value) = line {
            location.push_str(&format!(":{value}"));
            if let Some(value) = column {
                location.push_str(&format!(":{value}"));
            }
        }
        context.usages = vec![location];
    }
    context.usages.sort();
    context.usages.dedup();
    context
}

fn matches_pattern(pattern: Option<&Regex>, path: &str, key: &str) -> bool {
    pattern.is_some_and(|matcher| full_match(matcher, path) || full_match(matcher, key))
}

fn full_match(pattern: &Regex, value: &str) -> bool {
    pattern
        .find(value)
        .is_some_and(|matched| matched.start() == 0 && matched.end() == value.len())
}

fn selected(path: &str, key: &str, options: &FilterOptions) -> bool {
    let pattern = options.pattern("exceptions");
    let matches = matches_pattern(pattern, path, key)
        || pattern.is_some_and(|expression| expression.is_match(path));
    let all = !options.contains("extractAllPairs") || options.enabled("extractAllPairs");
    if all {
        pattern.is_none() || !matches
    } else {
        matches
    }
}

fn json_identity(path: &str, key: &str, options: &FilterOptions) -> String {
    let id = if options.contains("useFullKeyPath") && !options.enabled("useFullKeyPath") {
        key
    } else {
        path
    };
    options
        .values
        .get("removeKeySuffix")
        .and_then(|suffix| id.strip_suffix(suffix))
        .unwrap_or(id)
        .to_owned()
}

fn localize_json(
    source: &[u8],
    translations: &BTreeMap<String, String>,
    options: &FilterOptions,
    remove_untranslated: bool,
) -> Result<Vec<u8>, ParseError> {
    let cleaned = json_without_comments(source)?;
    let mut root: Value = serde_json::from_slice(cleaned.as_deref().unwrap_or(source))
        .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?;
    let catalog = apply_extraction(parse_configured_json(source, options)?, source, options)?;
    for key in translations.keys() {
        if !catalog.messages.contains_key(key) {
            return Err(ParseError::new(
                "UNKNOWN_SKELETON_SLOT",
                format!("Unknown JSON message: {key}"),
            ));
        }
    }
    let changed = update_json(
        &mut root,
        "",
        &catalog,
        translations,
        options,
        remove_untranslated,
    )?;
    if !changed {
        return Ok(source.to_vec());
    }
    if !remove_untranslated {
        return render_json_template(source, translations, &catalog, options);
    }
    if remove_untranslated {
        remove_json_untranslated(&mut root, "", &catalog, translations, options);
    }
    let mut output = serde_json::to_string_pretty(&root)
        .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?;
    if source.last() == Some(&b'\n') {
        output.push('\n');
    }
    Ok(output.into_bytes())
}

fn json_without_comments(source: &[u8]) -> Result<Option<Vec<u8>>, ParseError> {
    let mut cleaned = None;
    let mut quoted = false;
    let mut escaped = false;
    let mut index = 0;
    while index < source.len() {
        let current = source[index];
        if quoted {
            if current == b'"' && !escaped {
                quoted = false;
            }
            escaped = current == b'\\' && !escaped;
            index += 1;
            continue;
        }
        if current == b'"' {
            quoted = true;
            index += 1;
            continue;
        }
        if current != b'/' || index + 1 >= source.len() {
            index += 1;
            continue;
        }
        let marker = source[index + 1];
        if marker != b'/' && marker != b'*' {
            index += 1;
            continue;
        }
        let result = cleaned.get_or_insert_with(|| source.to_vec());
        result[index] = b' ';
        result[index + 1] = b' ';
        index += 2;
        if marker == b'/' {
            while index < source.len() && !matches!(source[index], b'\n' | b'\r') {
                result[index] = b' ';
                index += 1;
            }
        } else {
            let mut closed = false;
            while index + 1 < source.len() {
                if source[index] == b'*' && source[index + 1] == b'/' {
                    result[index] = b' ';
                    result[index + 1] = b' ';
                    index += 2;
                    closed = true;
                    break;
                }
                if !matches!(source[index], b'\n' | b'\r') {
                    result[index] = b' ';
                }
                index += 1;
            }
            if !closed {
                return Err(ParseError::new("INVALID_FORMATJS", "Unclosed JSON comment"));
            }
        }
    }
    Ok(cleaned)
}

fn render_json_template(
    source: &[u8],
    translations: &BTreeMap<String, String>,
    catalog: &Catalog,
    options: &FilterOptions,
) -> Result<Vec<u8>, ParseError> {
    let text = std::str::from_utf8(source)
        .map_err(|_| ParseError::new("INVALID_FORMATJS", "JSON source must be UTF-8"))?;
    let mut cursor = JsonTemplateCursor {
        source: text,
        index: 0,
    };
    let mut patches = Vec::new();
    cursor.object("", translations, catalog, options, &mut patches)?;
    cursor.whitespace();
    if cursor.index != text.len() {
        return Err(ParseError::new(
            "INVALID_FORMATJS",
            "Unexpected trailing JSON",
        ));
    }
    let mut output = Vec::with_capacity(source.len());
    let mut previous = 0;
    for (start, end, value) in patches {
        output.extend_from_slice(&source[previous..start]);
        output.extend_from_slice(
            serde_json::to_string(&value)
                .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?
                .as_bytes(),
        );
        previous = end;
    }
    output.extend_from_slice(&source[previous..]);
    Ok(output)
}

struct JsonTemplateCursor<'a> {
    source: &'a str,
    index: usize,
}

impl JsonTemplateCursor<'_> {
    fn whitespace(&mut self) {
        loop {
            while self.index < self.source.len()
                && self.source.as_bytes()[self.index].is_ascii_whitespace()
            {
                self.index += 1;
            }
            if self.source.as_bytes().get(self.index..self.index + 2) == Some(b"//") {
                self.index += 2;
                while self.index < self.source.len()
                    && !matches!(self.source.as_bytes()[self.index], b'\n' | b'\r')
                {
                    self.index += 1;
                }
            } else if self.source.as_bytes().get(self.index..self.index + 2) == Some(b"/*") {
                self.index += 2;
                while self.source.as_bytes().get(self.index..self.index + 2) != Some(b"*/") {
                    if self.index >= self.source.len() {
                        return;
                    }
                    self.index += 1;
                }
                self.index += 2;
            } else {
                return;
            }
        }
    }

    fn consume(&mut self, expected: u8) -> Result<(), ParseError> {
        self.whitespace();
        if self.source.as_bytes().get(self.index) != Some(&expected) {
            return Err(ParseError::new("INVALID_FORMATJS", "Invalid JSON template"));
        }
        self.index += 1;
        Ok(())
    }

    fn string(&mut self) -> Result<(usize, usize, String), ParseError> {
        self.whitespace();
        let start = self.index;
        self.consume(b'"')?;
        let mut escaped = false;
        while self.index < self.source.len() {
            let byte = self.source.as_bytes()[self.index];
            self.index += 1;
            if byte == b'"' && !escaped {
                let end = self.index;
                let value = serde_json::from_str(&self.source[start..end])
                    .map_err(|error| ParseError::new("INVALID_FORMATJS", error.to_string()))?;
                return Ok((start, end, value));
            }
            escaped = byte == b'\\' && !escaped;
        }
        Err(ParseError::new("INVALID_FORMATJS", "Unclosed JSON string"))
    }

    fn object(
        &mut self,
        parent: &str,
        translations: &BTreeMap<String, String>,
        catalog: &Catalog,
        options: &FilterOptions,
        patches: &mut Vec<(usize, usize, String)>,
    ) -> Result<(), ParseError> {
        self.consume(b'{')?;
        loop {
            self.whitespace();
            if self.source.as_bytes().get(self.index) == Some(&b'}') {
                self.index += 1;
                return Ok(());
            }
            let (_, _, key) = self.string()?;
            self.consume(b':')?;
            let path = if parent.is_empty() {
                key.clone()
            } else {
                format!("{parent}/{key}")
            };
            self.value(&path, &key, translations, catalog, options, patches)?;
            self.whitespace();
            if self.source.as_bytes().get(self.index) == Some(&b',') {
                self.index += 1;
            }
        }
    }

    fn value(
        &mut self,
        path: &str,
        key: &str,
        translations: &BTreeMap<String, String>,
        catalog: &Catalog,
        options: &FilterOptions,
        patches: &mut Vec<(usize, usize, String)>,
    ) -> Result<(), ParseError> {
        self.whitespace();
        match self.source.as_bytes().get(self.index) {
            Some(b'{') => self.object(path, translations, catalog, options, patches),
            Some(b'[') => {
                self.index += 1;
                let mut index = 0;
                loop {
                    self.whitespace();
                    if self.source.as_bytes().get(self.index) == Some(&b']') {
                        self.index += 1;
                        return Ok(());
                    }
                    self.value(
                        &format!("{path}/{index}"),
                        key,
                        translations,
                        catalog,
                        options,
                        patches,
                    )?;
                    self.whitespace();
                    if self.source.as_bytes().get(self.index) == Some(&b',') {
                        self.index += 1;
                    }
                    index += 1;
                }
            }
            Some(b'"') => {
                let (start, end, original) = self.string()?;
                let id = json_identity(path, key, options);
                if selected(path, key, options) {
                    if let Some(translation) = translations.get(&id) {
                        let restored = if options.enabled("convertToHtmlCodes") {
                            restore_inline_codes(translation, &catalog.messages[&id])?
                        } else {
                            translation.clone()
                        };
                        if original != restored {
                            patches.push((start, end, restored));
                        }
                    }
                }
                Ok(())
            }
            Some(_) => {
                while self.index < self.source.len()
                    && !matches!(self.source.as_bytes()[self.index], b',' | b'}' | b']')
                {
                    self.index += 1;
                }
                Ok(())
            }
            None => Err(ParseError::new("INVALID_FORMATJS", "Missing JSON value")),
        }
    }
}

fn update_json(
    node: &mut Value,
    parent: &str,
    catalog: &Catalog,
    translations: &BTreeMap<String, String>,
    options: &FilterOptions,
    remove_untranslated: bool,
) -> Result<bool, ParseError> {
    let Some(object) = node.as_object_mut() else {
        return Ok(false);
    };
    let keys = object.keys().cloned().collect::<Vec<_>>();
    let mut changed = false;
    for key in keys {
        let path = if parent.is_empty() {
            key.clone()
        } else {
            format!("{parent}/{key}")
        };
        if object[&key].is_object() {
            let nested = update_json(
                object.get_mut(&key).expect("existing JSON child"),
                &path,
                catalog,
                translations,
                options,
                remove_untranslated,
            )?;
            changed |= nested;
        } else if let Some(array) = object.get_mut(&key).and_then(Value::as_array_mut) {
            for (index, value) in array.iter_mut().enumerate() {
                if value.is_object() {
                    changed |= update_json(
                        value,
                        &format!("{path}/{index}"),
                        catalog,
                        translations,
                        options,
                        remove_untranslated,
                    )?;
                }
            }
        } else if object[&key].is_string() && selected(&path, &key, options) {
            let id = json_identity(&path, &key, options);
            if !catalog.messages.contains_key(&id) {
                continue;
            }
            if let Some(value) = translations.get(&id) {
                let restored = if options.enabled("convertToHtmlCodes") {
                    restore_inline_codes(value, &catalog.messages[&id])?
                } else {
                    value.clone()
                };
                if object[&key].as_str() != Some(&restored) {
                    object.insert(key, Value::String(restored));
                    changed = true;
                }
            } else if remove_untranslated {
                object.insert(key, Value::String(UNTRANSLATED.to_owned()));
                changed = true;
            }
        }
    }
    Ok(changed)
}

fn protect_inline_codes(source: &str, patterns: &[Regex]) -> (String, Vec<Value>) {
    let mut output = String::new();
    let mut codes = Vec::new();
    let mut index = 0;
    while index < source.len() {
        let selected = patterns
            .iter()
            .filter_map(|pattern| pattern.find_at(source, index))
            .filter(|matched| matched.end() > matched.start())
            .min_by_key(regex::Match::start);
        let Some(matched) = selected else {
            output.push_str(&source[index..]);
            break;
        };
        output.push_str(&source[index..matched.start()]);
        let id = format!("p{}", codes.len() + 1);
        output.push_str(&format!("<br id='{id}'/>"));
        codes.push(serde_json::json!({"id": id, "source": matched.as_str()}));
        index = matched.end();
    }
    (output, codes)
}

fn restore_inline_codes(translation: &str, message: &Message) -> Result<String, ParseError> {
    let Some(codes) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("mojitoInlineCodes"))
        .and_then(Value::as_array)
    else {
        return Ok(translation.to_owned());
    };
    let originals = codes
        .iter()
        .map(|code| {
            (
                code["id"].as_str().expect("owned code id"),
                code["source"].as_str().expect("owned code source"),
            )
        })
        .collect::<BTreeMap<_, _>>();
    static PATTERN: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let pattern = PATTERN.get_or_init(|| {
        Regex::new(r"<br id='(p[1-9][0-9]*)'/>").expect("valid protected-code marker")
    });
    let mut used = BTreeSet::new();
    let mut output = String::new();
    let mut previous = 0;
    for captured in pattern.captures_iter(translation) {
        let matched = captured.get(0).expect("matched code marker");
        let id = captured.get(1).expect("matched code id").as_str();
        let Some(original) = originals.get(id) else {
            return Err(ParseError::new(
                "INVALID_INLINE_CODE",
                "Unknown protected code",
            ));
        };
        if !used.insert(id) {
            return Err(ParseError::new(
                "INVALID_INLINE_CODE",
                "Repeated protected code",
            ));
        }
        output.push_str(&translation[previous..matched.start()]);
        output.push_str(original);
        previous = matched.end();
    }
    output.push_str(&translation[previous..]);
    if used.len() != originals.len() {
        return Err(ParseError::new(
            "INVALID_INLINE_CODE",
            "Missing protected code",
        ));
    }
    Ok(output)
}

fn contains_untranslated_json_value(
    object: &Value,
    parent: &str,
    catalog: &Catalog,
    translations: &BTreeMap<String, String>,
    options: &FilterOptions,
) -> bool {
    object.as_object().is_some_and(|fields| {
        fields.iter().any(|(key, value)| {
            if value.as_str() != Some(UNTRANSLATED) {
                return false;
            }
            let path = if parent.is_empty() {
                key.clone()
            } else {
                format!("{parent}/{key}")
            };
            if !selected(&path, key, options) {
                return false;
            }
            let id = json_identity(&path, key, options);
            catalog.messages.contains_key(&id) && !translations.contains_key(&id)
        })
    })
}

fn remove_json_untranslated(
    value: &mut Value,
    parent: &str,
    catalog: &Catalog,
    translations: &BTreeMap<String, String>,
    options: &FilterOptions,
) {
    if let Some(object) = value.as_object_mut() {
        object.retain(|key, child| {
            let path = if parent.is_empty() {
                key.clone()
            } else {
                format!("{parent}/{key}")
            };
            if child.is_object() {
                return !contains_untranslated_json_value(
                    child,
                    &path,
                    catalog,
                    translations,
                    options,
                );
            }
            child.as_str() != Some(UNTRANSLATED)
                || !selected(&path, key, options)
                || translations.contains_key(&json_identity(&path, key, options))
        });
        for (key, child) in object.iter_mut() {
            let path = if parent.is_empty() {
                key.clone()
            } else {
                format!("{parent}/{key}")
            };
            remove_json_untranslated(child, &path, catalog, translations, options);
        }
    } else if let Some(array) = value.as_array_mut() {
        for index in (0..array.len()).rev() {
            let path = format!("{parent}/{index}");
            if array[index].is_object()
                && contains_untranslated_json_value(
                    &array[index],
                    &path,
                    catalog,
                    translations,
                    options,
                )
            {
                array.remove(index);
            } else {
                remove_json_untranslated(&mut array[index], &path, catalog, translations, options);
            }
        }
    }
}

fn android_output(
    source: &[u8],
    encoding_name: &str,
    options: &FilterOptions,
    remove_untranslated: bool,
    untranslated_marker: &str,
) -> Result<Vec<u8>, ParseError> {
    let encoding = crate::source_skeleton::Encoding::named(encoding_name)?;
    let original = encoding.decode(&source[encoding.bom_length()..])?;
    let mut root = xml::parse(&original)?;
    clean_android(&mut root, options, remove_untranslated, untranslated_marker);
    if options.enabled("postEmptyResourcesToEmptyFile")
        && !root.elements().any(|element| {
            matches!(
                element.local_name(),
                "string" | "plurals" | "string-array" | "array" | "item" | "bag"
            )
        })
    {
        return Ok(Vec::new());
    }
    let mut output = String::new();
    if original.trim_start().starts_with("<?xml") {
        let start = original.find("<?xml").expect("XML declaration");
        let end = original.find("?>").expect("closed XML declaration") + 2;
        output.push_str(&original[start..end]);
        output.push('\n');
    }
    let opening = original
        .find(&format!("<{}", root.name))
        .ok_or_else(|| ParseError::new("INVALID_XML", "Android root has no source opening tag"))?;
    let prefix = &original[..opening];
    let mut position = 0;
    while let Some(offset) = prefix[position..].find('<') {
        position += offset;
        if prefix[position..].starts_with("<!--") {
            let end = prefix[position..]
                .find("-->")
                .ok_or_else(|| ParseError::new("INVALID_XML", "Unterminated Android comment"))?
                + position
                + 3;
            output.push_str(&prefix[position..end]);
            position = end;
        } else if prefix[position..].starts_with("<?") {
            let end = prefix[position..].find("?>").ok_or_else(|| {
                ParseError::new("INVALID_XML", "Unterminated Android instruction")
            })? + position
                + 2;
            if !prefix[position..].starts_with("<?xml") {
                output.push_str(&prefix[position..end]);
            }
            position = end;
        } else {
            position += 1;
        }
    }
    append_xml(&root, &mut output, 0, options.indentation());
    output.push('\n');
    Ok(encoding.encode(&output))
}

fn clean_android(
    element: &mut XmlElement,
    options: &FilterOptions,
    remove_untranslated: bool,
    untranslated_marker: &str,
) {
    if options.enabled("removeDescription") {
        element.attributes.retain(|(name, _)| name != "description");
    }
    element
        .attributes
        .sort_unstable_by(|(left, _), (right, _)| left.cmp(right));
    let mut children = Vec::new();
    for child in std::mem::take(&mut element.children) {
        match child {
            XmlNode::Element(mut child) => {
                if options.enabled("postRemoveTranslatableFalse")
                    && child
                        .attribute("translatable")
                        .map(str::trim)
                        .is_some_and(|value| matches!(value, "false" | "False" | "FALSE"))
                {
                    continue;
                }
                let suppressed = child
                    .attribute("description")
                    .is_some_and(|value| value.contains(DO_NOT_TRANSLATE));
                clean_android(
                    &mut child,
                    options,
                    remove_untranslated,
                    untranslated_marker,
                );
                if remove_untranslated
                    && !child
                        .attribute("translatable")
                        .map(str::trim)
                        .is_some_and(|value| matches!(value, "false" | "False" | "FALSE"))
                    && !suppressed
                {
                    let value = child.text();
                    if value == untranslated_marker
                        || value
                            .strip_prefix('"')
                            .and_then(|value| value.strip_suffix('"'))
                            == Some(untranslated_marker)
                    {
                        continue;
                    }
                }
                if remove_untranslated
                    && child.local_name() == "plurals"
                    && !child.elements().any(|item| {
                        item.local_name() == "item"
                            && item.attribute("quantity").is_some_and(|quantity| {
                                quantity.trim_matches(|value: char| value.is_ascii_whitespace())
                                    == "other"
                            })
                    })
                {
                    continue;
                }
                children.push(XmlNode::Element(child));
            }
            child => children.push(child),
        }
    }
    element.children = children;
}

fn append_xml(element: &XmlElement, output: &mut String, level: usize, indent: usize) {
    output.push_str(&" ".repeat(level * indent));
    output.push('<');
    output.push_str(&element.name);
    for (name, value) in &element.attributes {
        output.push(' ');
        output.push_str(name);
        output.push_str("=\"");
        output.push_str(&escape_xml(value, true));
        output.push('"');
    }
    let nested = element
        .children
        .iter()
        .any(|child| matches!(child, XmlNode::Element(_) | XmlNode::Comment(_)));
    let text = element.children.iter().any(|child| match child {
        XmlNode::Text(value) => !value.trim().is_empty(),
        _ => false,
    });
    if element.local_name() == "resources" && !nested && !text {
        output.push_str("/>");
        return;
    }
    output.push('>');
    for child in &element.children {
        match child {
            XmlNode::Element(child) => {
                if nested && !text {
                    output.push('\n');
                }
                append_xml(
                    child,
                    output,
                    if nested && !text { level + 1 } else { 0 },
                    indent,
                );
            }
            XmlNode::Comment(value) => {
                if nested && !text {
                    output.push('\n');
                    output.push_str(&" ".repeat((level + 1) * indent));
                }
                output.push_str("<!--");
                output.push_str(value);
                output.push_str("-->");
            }
            XmlNode::Text(value) if text || !value.trim().is_empty() => {
                output.push_str(&escape_xml(value, false));
            }
            _ => {}
        }
    }
    if nested && !text {
        output.push('\n');
        output.push_str(&" ".repeat(level * indent));
    }
    output.push_str("</");
    output.push_str(&element.name);
    output.push('>');
}

fn escape_xml(value: &str, attribute: bool) -> String {
    let result = value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;");
    if attribute {
        result
            .replace('"', "&quot;")
            .replace('\t', "&#9;")
            .replace('\r', "&#13;")
            .replace('\n', "&#10;")
    } else {
        result
    }
}

fn apple_output(
    source: &str,
    remove_comments: bool,
    remove_untranslated: bool,
    untranslated_keys: &BTreeSet<String>,
) -> Result<String, ParseError> {
    if source.trim_start().starts_with('<') {
        let output = if remove_untranslated {
            remove_untranslated_apple_xml_entries(source, untranslated_keys)?
        } else {
            source.to_owned()
        };
        return Ok(if remove_comments {
            remove_apple_xml_comments(&output)?
        } else {
            output
        });
    }
    let mut output = source.to_owned();
    if remove_untranslated {
        static ENTRY: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
        let entry = ENTRY.get_or_init(|| {
            Regex::new(
                r#"(?s)((?:\s*(?:/\*.*?\*/|//[^\r\n\u2028\u2029]*))+\s*)?((\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[A-Za-z0-9_$/:.-]+)\s*=\s*(\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[A-Za-z0-9_$/:.-]+)\s*;)"#,
            )
            .expect("valid Apple localized-entry pattern")
        });
        let mut filtered = String::with_capacity(source.len());
        let mut previous = 0;
        let mut scanned = 0;
        let mut quote = None;
        for capture in entry.captures_iter(source) {
            let whole = capture.get(0).expect("Apple entry");
            filtered.push_str(&source[previous..whole.start()]);
            let declaration = capture.get(2).expect("Apple declaration");
            quote = apple_quote_state(source, scanned, declaration.start(), quote);
            if quote.is_some() {
                filtered.push_str(whole.as_str());
            } else {
                let key = crate::apple::decode_source_token(
                    capture.get(3).expect("Apple source key").as_str(),
                )?;
                let value = capture.get(4).expect("Apple value").as_str();
                if !untranslated_keys.contains(&key)
                    || (value != "\"@#$untranslated$#@\"" && value != "'@#$untranslated$#@'")
                {
                    filtered.push_str(capture.get(1).map_or("", |value| value.as_str()));
                    filtered.push_str(declaration.as_str());
                }
            }
            quote = apple_quote_state(source, declaration.start(), whole.end(), quote);
            scanned = whole.end();
            previous = whole.end();
        }
        filtered.push_str(&source[previous..]);
        output = filtered;
    }
    Ok(if remove_comments {
        remove_apple_comments(&output)
    } else {
        output
    })
}

fn apple_quote_state(
    source: &str,
    start: usize,
    end: usize,
    mut quote: Option<char>,
) -> Option<char> {
    let mut index = start;
    while index < end {
        let current = source[index..].chars().next().expect("Apple character");
        index += current.len_utf8();
        if let Some(delimiter) = quote {
            if current == '\\' && index < end {
                let escaped = source[index..].chars().next().expect("escaped character");
                index += escaped.len_utf8();
            } else if current == delimiter {
                quote = None;
            }
        } else if matches!(current, '"' | '\'') {
            quote = Some(current);
        } else if current == '/' && index < end {
            if source[index..].starts_with('*') {
                index = source[index + 1..]
                    .find("*/")
                    .map_or(end, |offset| index + 1 + offset + 2)
                    .min(end);
            } else if source[index..].starts_with('/') {
                index += 1;
                while index < end {
                    let next = source[index..].chars().next().expect("comment character");
                    if matches!(next, '\n' | '\r' | '\u{2028}' | '\u{2029}') {
                        break;
                    }
                    index += next.len_utf8();
                }
            }
        }
    }
    quote
}

fn remove_untranslated_apple_xml_entries(
    source: &str,
    untranslated_keys: &BTreeSet<String>,
) -> Result<String, ParseError> {
    let root = xml::parse_apple_plist(source)?;
    let dictionary = if root.name == "dict" {
        &root
    } else {
        root.elements()
            .find(|element| element.name == "dict")
            .ok_or_else(|| {
                ParseError::new("INVALID_XML", "Missing Apple property-list dictionary")
            })?
    };
    let mut key = None;
    let untranslated = dictionary
        .elements()
        .filter_map(|element| match element.name.as_str() {
            "key" => {
                key = Some(element.text());
                None
            }
            "string" => Some(
                element.text() == UNTRANSLATED
                    && key
                        .take()
                        .is_some_and(|key| untranslated_keys.contains(&key)),
            ),
            _ => None,
        })
        .collect::<Vec<_>>();

    let mut output = String::with_capacity(source.len());
    let mut elements = Vec::new();
    let mut previous = 0;
    let mut key_start = None;
    let mut value_index = 0;
    let mut index = 0;
    while index < source.len() {
        if source.as_bytes()[index] != b'<' {
            index += 1;
            continue;
        }
        let delimiter = if source[index..].starts_with("<!--") {
            Some("-->")
        } else if source[index..].starts_with("<![CDATA[") {
            Some("]]>")
        } else if source[index..].starts_with("<?") {
            Some("?>")
        } else {
            None
        };
        if let Some(delimiter) = delimiter {
            index = source[index..]
                .find(delimiter)
                .map(|offset| index + offset + delimiter.len())
                .ok_or_else(|| ParseError::new("INVALID_XML", "Unterminated Apple XML section"))?;
            continue;
        }
        let mut quote = None;
        let mut tag_end = None;
        for (offset, value) in source[index + 1..].char_indices() {
            if let Some(delimiter) = quote {
                if value == delimiter {
                    quote = None;
                }
            } else if matches!(value, '\'' | '"') {
                quote = Some(value);
            } else if value == '>' {
                tag_end = Some(index + 1 + offset);
                break;
            }
        }
        let end = tag_end
            .ok_or_else(|| ParseError::new("INVALID_XML", "Unterminated Apple XML element"))?;
        let mut tag = source[index + 1..end].trim();
        if tag.starts_with('!') {
            index = end + 1;
            continue;
        }
        if tag.starts_with('/') {
            let current = elements.pop().ok_or_else(|| {
                ParseError::new("INVALID_XML", "Unexpected closing Apple XML element")
            })?;
            if elements.last() == Some(&"dict") && current == "string" {
                if untranslated.get(value_index) == Some(&true) {
                    if let Some(start) = key_start {
                        output.push_str(&source[previous..start]);
                        previous = end + 1;
                    }
                }
                value_index += 1;
                key_start = None;
            }
        } else {
            let empty = tag.ends_with('/');
            if empty {
                tag = tag[..tag.len() - 1].trim();
            }
            let name = tag
                .split_whitespace()
                .next()
                .ok_or_else(|| ParseError::new("INVALID_XML", "Missing Apple XML element name"))?;
            if elements.last() == Some(&"dict") && name == "key" {
                key_start = Some(index);
            }
            if empty {
                if elements.last() == Some(&"dict") && name == "string" {
                    value_index += 1;
                    key_start = None;
                }
            } else {
                elements.push(name);
            }
        }
        index = end + 1;
    }
    output.push_str(&source[previous..]);
    Ok(output)
}

fn remove_apple_xml_comments(source: &str) -> Result<String, ParseError> {
    let mut output = String::with_capacity(source.len());
    let mut index = 0;
    while index < source.len() {
        let (delimiter, comment) = if source[index..].starts_with("<!--") {
            ("-->", true)
        } else if source[index..].starts_with("<![CDATA[") {
            ("]]>", false)
        } else if source[index..].starts_with("<?") {
            ("?>", false)
        } else {
            let character = source[index..].chars().next().expect("XML character");
            output.push(character);
            index += character.len_utf8();
            continue;
        };
        let end = source[index..]
            .find(delimiter)
            .map(|offset| index + offset + delimiter.len())
            .ok_or_else(|| ParseError::new("INVALID_XML", "Unterminated Apple XML section"))?;
        if !comment {
            output.push_str(&source[index..end]);
        }
        index = end;
    }
    Ok(output)
}

fn remove_apple_comments(source: &str) -> String {
    let mut output = String::with_capacity(source.len());
    let mut characters = source.char_indices().peekable();
    let mut quote = None;
    while let Some((_, current)) = characters.next() {
        if let Some(delimiter) = quote {
            output.push(current);
            if current == '\\' {
                if let Some((_, escaped)) = characters.next() {
                    output.push(escaped);
                }
            } else if current == delimiter {
                quote = None;
            }
        } else if matches!(current, '"' | '\'') {
            quote = Some(current);
            output.push(current);
        } else if current == '/' && characters.peek().is_some_and(|(_, next)| *next == '*') {
            characters.next();
            while let Some((_, next)) = characters.next() {
                if next == '*' && characters.peek().is_some_and(|(_, end)| *end == '/') {
                    characters.next();
                    break;
                }
            }
        } else if current == '/' && characters.peek().is_some_and(|(_, next)| *next == '/') {
            characters.next();
            while let Some((_, next)) = characters.peek() {
                if matches!(*next, '\n' | '\r' | '\u{2028}' | '\u{2029}') {
                    break;
                }
                characters.next();
            }
        } else {
            output.push(current);
        }
    }
    output
}

fn gettext_output(source: &str, untranslated_marker: &str) -> Result<String, ParseError> {
    static BLOCK: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    static UNTRANSLATED_VALUE: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    static QUOTED_SEGMENT: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let block = BLOCK.get_or_init(|| {
        Regex::new(concat!(
            r#"(?mR)^(?:#.*\r?\n)*"#,
            r#"(?:msgctxt[ \t\x0b\f]+\"(?:[^\"\\]|\\.)*\""#,
            r#"(?:[ \t\x0b\f]*\r?\n[ \t\x0b\f]*\"(?:[^\"\\]|\\.)*\")*"#,
            r#"[ \t\x0b\f]*\r?\n)?msgid "#
        ))
        .expect("valid gettext localized-entry pattern")
    });
    let directives = UNTRANSLATED_VALUE.get_or_init(|| {
        Regex::new(
            r#"(?mR)^msgstr(?:\[[0-9]+\])?[ \t\x0b\f]+\"((?:[^\"\\]|\\.)*)\"((?:[ \t\x0b\f]*\r?\n[ \t\x0b\f]*\"(?:[^\"\\]|\\.)*\")*)[ \t\x0b\f]*$"#,
        )
        .expect("valid untranslated gettext value pattern")
    });
    let quoted = QUOTED_SEGMENT.get_or_init(|| {
        Regex::new(r#"\"((?:[^\"\\]|\\.)*)\""#).expect("valid gettext continuation pattern")
    });
    let mut starts = block
        .find_iter(source)
        .map(|item| item.start())
        .collect::<Vec<_>>();
    if starts.first().copied() != Some(0) {
        starts.insert(0, 0);
    }
    starts.push(source.len());
    let mut result = String::new();
    for window in starts.windows(2) {
        let entry = &source[window[0]..window[1]];
        let untranslated = directives.captures_iter(entry).any(|directive| {
            let mut value = directive.get(1).expect("gettext value").as_str().to_owned();
            for continuation in
                quoted.captures_iter(directive.get(2).expect("gettext continuations").as_str())
            {
                value.push_str(continuation.get(1).expect("gettext segment").as_str());
            }
            value == untranslated_marker
        });
        if !untranslated {
            result.push_str(entry);
        }
    }
    Ok(result)
}

fn invalid_option(message: impl Into<String>) -> ParseError {
    ParseError::new("INVALID_FILTER_OPTION", message)
}
