use crate::android_configuration::Configuration;
use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use crate::placeholders;
use crate::xml::{self, XmlElement, XmlNode};
use serde_json::{json, Map};
use std::collections::{BTreeMap, HashSet};
use std::sync::OnceLock;

const XLIFF_NAMESPACE: &str = "urn:oasis:names:tc:xliff:document:1.2";
const ANDROID_NAMESPACE: &str = "http://schemas.android.com/apk/res/android";
const TOOLS_NAMESPACE: &str = "http://schemas.android.com/tools";

pub(crate) fn parse(
    source: &str,
    resource_path: Option<&str>,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<Catalog, ParseError> {
    parse_with_macros(
        source,
        resource_path,
        feature_flags,
        &BTreeMap::new(),
        &BTreeMap::new(),
        &BTreeMap::new(),
        None,
    )
}

pub(crate) fn parse_with_macros(
    source: &str,
    resource_path: Option<&str>,
    feature_flags: &BTreeMap<String, bool>,
    external_macros: &BTreeMap<String, XmlElement>,
    external_attributes: &BTreeMap<String, XmlElement>,
    external_styleables: &BTreeMap<String, XmlElement>,
    application_package: Option<&str>,
) -> Result<Catalog, ParseError> {
    let configuration = resource_path.map(Configuration::parse).transpose()?;
    let path_feature = configuration
        .as_ref()
        .and_then(|configuration| configuration.path_feature_flag.as_deref())
        .map(|expression| resolve_feature_condition(expression, feature_flags))
        .transpose()?;
    let file_translatable = resource_path
        .and_then(|path| path.rsplit('/').next())
        .is_none_or(|filename| !filename.starts_with("donottranslate"));
    let mut root = xml::parse(source)?;
    if root.local_name() != "resources" || root.namespace.is_some() {
        return Err(ParseError::new(
            "INVALID_XML",
            "Expected Android resources root",
        ));
    }
    expand_macros(
        &mut root,
        configuration.as_ref(),
        path_feature,
        feature_flags,
        external_macros,
        application_package,
    )?;
    let attribute_dependencies = crate::android_attributes::collect(
        &root,
        external_attributes,
        external_styleables,
        application_package,
    )?;
    let mut catalog = Catalog::new(FileFormat::Android);
    catalog.locale = root
        .namespaced_attribute(TOOLS_NAMESPACE, "locale")
        .map(|locale| locale.replace('_', "-"));
    if let Some(locale) = configuration
        .as_ref()
        .and_then(|configuration| configuration.locale.as_ref())
    {
        catalog.locale = Some(locale.clone());
    }
    let mut comment = String::new();
    for child in &root.children {
        match child {
            XmlNode::Comment(value) => comment = trim_ascii(value).to_owned(),
            XmlNode::Element(element) => {
                if element.namespace.is_some() {
                    continue;
                }
                if matches!(element.local_name(), "skip" | "eat-comment") {
                    comment.clear();
                    continue;
                }
                if is_macro_declaration(element) {
                    comment.clear();
                    continue;
                }
                let mut condition = feature_condition(element, feature_flags)?;
                if path_feature.is_some() && condition.is_some() {
                    return Err(conflicting_feature_flag());
                }
                if path_feature.is_some() {
                    if element.local_name() == "style"
                        || element.local_name() == "bag"
                            && element
                                .attribute("type")
                                .is_some_and(|kind| kind.trim() == "style")
                    {
                        for item in element
                            .elements()
                            .filter(|item| item.namespace.is_none() && item.local_name() == "item")
                        {
                            if feature_condition(item, feature_flags)?.is_some() {
                                return Err(conflicting_feature_flag());
                            }
                        }
                    }
                    condition = path_feature;
                }
                let mut excluded = Catalog::new(FileFormat::Android);
                let destination = if condition.is_none_or(|condition| condition.enabled) {
                    &mut catalog
                } else {
                    &mut excluded
                };
                let description = std::mem::take(&mut comment);
                match element.local_name() {
                    "string" => {
                        resource_name(element)?;
                        boolean_attribute(element, "formatted", true)?;
                        if boolean_attribute(element, "translatable", file_translatable)? {
                            add_string(destination, element, &description, None, feature_flags)?;
                        } else {
                            validate_protected_string(element)?;
                        }
                    }
                    "item" => {
                        let kind = trim_ascii(element.attribute("type").unwrap_or_default());
                        if !crate::android_reference::is_resource_type(kind) {
                            return Err(invalid_structure(
                                "Android generic items require a known resource type",
                            ));
                        }
                        if kind == "string" {
                            resource_name(element)?;
                            let format = generic_format(element)?;
                            if format == "string" {
                                boolean_attribute(element, "formatted", true)?;
                                if !boolean_attribute(element, "translatable", file_translatable)? {
                                    validate_protected_string(element)?;
                                    continue;
                                }
                            }
                            add_string(destination, element, &description, None, feature_flags)?;
                        }
                    }
                    "array" | "integer-array" | "string-array" => {
                        resource_name(element)?;
                        validate_array_feature_flags(
                            element,
                            feature_flags,
                            configuration.as_ref(),
                        )?;
                        let format = match element.local_name() {
                            "array" => generic_format(element)?,
                            "integer-array" => "integer",
                            _ => "string",
                        };
                        if boolean_attribute(element, "translatable", file_translatable)? {
                            add_array(
                                destination,
                                element,
                                &description,
                                format,
                                None,
                                feature_flags,
                            )?;
                        } else {
                            validate_protected_items(element)?;
                        }
                    }
                    "plurals" => {
                        resource_name(element)?;
                        if !element
                            .attribute("translatable")
                            .is_some_and(|value| android_false(trim_ascii(value)))
                        {
                            add_plural(destination, element, &description, None, feature_flags)?;
                        } else {
                            validate_protected_items(element)?;
                        }
                    }
                    "bag" => {
                        let kind = trim_ascii(element.attribute("type").unwrap_or_default());
                        if !is_bag_type(kind) {
                            return Err(invalid_structure(
                                "Android generic bags require a known bag resource type",
                            ));
                        }
                        match kind {
                            "array" | "integer-array" | "string-array" => {
                                resource_name(element)?;
                                validate_array_feature_flags(
                                    element,
                                    feature_flags,
                                    configuration.as_ref(),
                                )?;
                                let format = match kind {
                                    "array" => generic_format(element)?,
                                    "integer-array" => "integer",
                                    _ => "string",
                                };
                                if boolean_attribute(element, "translatable", file_translatable)? {
                                    add_array(
                                        destination,
                                        element,
                                        &description,
                                        format,
                                        Some(kind),
                                        feature_flags,
                                    )?;
                                } else {
                                    validate_protected_items(element)?;
                                }
                            }
                            "plurals" => {
                                resource_name(element)?;
                                if !element
                                    .attribute("translatable")
                                    .is_some_and(|value| android_false(trim_ascii(value)))
                                {
                                    add_plural(
                                        destination,
                                        element,
                                        &description,
                                        Some(kind),
                                        feature_flags,
                                    )?;
                                } else {
                                    validate_protected_items(element)?;
                                }
                            }
                            _ => {}
                        }
                    }
                    kind => {
                        if !crate::android_reference::is_resource_type(kind) && !is_bag_type(kind) {
                            return Err(invalid_structure(
                                "Unknown Android top-level resource type",
                            ));
                        }
                    }
                }
            }
            XmlNode::Text(value) => {
                if !trim_ascii(value).is_empty() {
                    return Err(ParseError::new(
                        "INVALID_ANDROID_STRUCTURE",
                        "Plain text is not allowed under Android resources",
                    ));
                }
            }
        }
    }
    if !file_translatable {
        catalog.messages.clear();
    } else {
        crate::android_attributes::attach(
            &mut catalog,
            &attribute_dependencies,
            application_package,
        );
    }
    if let Some(configuration) = &configuration {
        let runtime_path = path_feature
            .filter(|condition| condition.runtime)
            .map(|condition| condition.expression);
        if let Some(expression) = runtime_path {
            let messages = std::mem::take(&mut catalog.messages);
            for (id, message) in messages {
                let renamed = if let Some(index) = id.rfind('[') {
                    format!("{}@flag={expression}{}", &id[..index], &id[index..])
                } else {
                    format!("{id}@flag={expression}")
                };
                catalog.insert(renamed, message)?;
            }
        }
        for message in catalog.messages.values_mut() {
            let metadata = message.metadata.get_or_insert_with(Map::new);
            metadata.insert("androidResourcePath".into(), json!(configuration.path));
            metadata.insert(
                "androidResourceQualifiers".into(),
                json!(configuration.qualifiers),
            );
            if let Some(flag) = &configuration.path_feature_flag {
                metadata.insert("androidPathFeatureFlag".into(), json!(flag));
                if path_feature.is_some_and(|condition| condition.runtime) {
                    metadata.insert("androidPathFeatureFlagMode".into(), json!("read_write"));
                }
            }
        }
    }
    Ok(catalog)
}

fn expand_macros(
    root: &mut XmlElement,
    configuration: Option<&Configuration>,
    path_feature: Option<FeatureCondition<'_>>,
    feature_flags: &BTreeMap<String, bool>,
    external_macros: &BTreeMap<String, XmlElement>,
    application_package: Option<&str>,
) -> Result<(), ParseError> {
    let mut definitions = external_macros.clone();
    let mut local_identities = HashSet::new();
    let mut unsupported_product = false;
    for child in &root.children {
        let XmlNode::Element(element) = child else {
            continue;
        };
        if element.namespace.is_some() || !is_macro_declaration(element) {
            continue;
        }
        if configuration.is_some_and(|value| !value.effective_key().is_empty()) {
            return Err(ParseError::new(
                "INVALID_ANDROID_MACRO_CONFIGURATION",
                "Android macros can only be declared in the default resource configuration",
            ));
        }
        if path_feature.is_some() {
            return Err(ParseError::new(
                "UNSAFE_ANDROID_MACRO_PATH_FLAG",
                "Android path-gated macros cannot be linked safely",
            ));
        }
        feature_condition(element, feature_flags)?;
        let product = resource_product(element);
        let name = resource_name(element)?;
        let normalized_product = if product.is_empty() {
            "default"
        } else {
            product
        };
        if !local_identities.insert((name.to_owned(), normalized_product.to_owned())) {
            return Err(ParseError::new(
                "DUPLICATE_ANDROID_MACRO",
                format!("Duplicate Android macro declaration: {name}"),
            ));
        }
        unsupported_product |= !product.is_empty() && product != "default";
        definitions
            .entry(name.to_owned())
            .or_insert_with(|| element.clone());
        unescape(&render(
            element,
            &mut Vec::new(),
            false,
            &mut false,
            &mut Vec::new(),
        )?)?;
    }
    if unsupported_product {
        return Err(ParseError::new(
            "INVALID_ANDROID_MACRO_PRODUCT",
            "Android macro product variants abort AAPT2 before product selection",
        ));
    }
    if definitions.is_empty() {
        return Ok(());
    }
    for child in &mut root.children {
        let XmlNode::Element(element) = child else {
            continue;
        };
        if element.namespace.is_some() || is_macro_declaration(element) {
            continue;
        }
        let runtime = feature_condition(element, feature_flags)?
            .is_some_and(|condition| condition.runtime)
            || path_feature.is_some_and(|condition| condition.runtime);
        match element.local_name() {
            "string" | "item" => {
                expand_macro_reference(
                    element,
                    &definitions,
                    &mut HashSet::new(),
                    runtime,
                    application_package,
                )?;
            }
            "plurals" | "array" | "integer-array" | "string-array" | "bag" => {
                for child in &mut element.children {
                    let XmlNode::Element(item) = child else {
                        continue;
                    };
                    if item.namespace.is_none() && item.local_name() == "item" {
                        let item_runtime = feature_condition(item, feature_flags)?
                            .is_some_and(|condition| condition.runtime);
                        expand_macro_reference(
                            item,
                            &definitions,
                            &mut HashSet::new(),
                            runtime || item_runtime,
                            application_package,
                        )?;
                    }
                }
            }
            _ => {}
        }
    }
    Ok(())
}

pub(crate) fn is_macro_declaration(element: &XmlElement) -> bool {
    element.local_name() == "macro"
        || element.local_name() == "item"
            && element
                .attribute("type")
                .is_some_and(|kind| trim_ascii(kind) == "macro")
}

fn expand_macro_reference(
    target: &mut XmlElement,
    macros: &BTreeMap<String, XmlElement>,
    resolving: &mut HashSet<String>,
    runtime: bool,
    application_package: Option<&str>,
) -> Result<(), ParseError> {
    if target
        .children
        .iter()
        .any(|node| matches!(node, XmlNode::Element(_)))
    {
        return Ok(());
    }
    let text = target.text();
    let reference = trim_ascii(&text);
    let Some(name) = macro_reference(target, reference, application_package)? else {
        return Ok(());
    };
    let Some(source) = macros.get(name) else {
        return Err(unresolved_macro(reference));
    };
    if runtime {
        return Err(ParseError::new(
            "UNSAFE_ANDROID_RUNTIME_MACRO",
            "Android runtime-conditional macro references cannot be linked safely",
        ));
    }
    if !resolving.insert(name.to_owned()) {
        return Err(ParseError::new(
            "UNSAFE_ANDROID_MACRO_CYCLE",
            "Android macro references cannot contain cycles",
        ));
    }
    let mut expansion = source.clone();
    expand_macro_reference(
        &mut expansion,
        macros,
        resolving,
        false,
        application_package,
    )?;
    target.children = normalized_macro_resource_reference(&expansion, application_package)
        .map_or(expansion.children, |reference| {
            vec![XmlNode::Text(reference)]
        });
    resolving.remove(name);
    Ok(())
}

fn normalized_macro_resource_reference(
    scope: &XmlElement,
    application_package: Option<&str>,
) -> Option<String> {
    if scope
        .children
        .iter()
        .any(|node| matches!(node, XmlNode::Element(_)))
    {
        return None;
    }
    let value = scope.text();
    let value = crate::android_reference::normalize(&value);
    if !crate::android_reference::is_reference(value) {
        return None;
    }
    let sigil = value.chars().next()?;
    let mut reference = &value[sigil.len_utf8()..];
    let create = reference.starts_with('+');
    if create {
        reference = &reference[1..];
    }
    let mut private = reference.starts_with('*');
    if private {
        reference = &reference[1..];
    }
    let (qualified_type, entry) = reference
        .split_once('/')
        .map_or((reference, None), |(kind, entry)| (kind, Some(entry)));
    let (alias, kind) = qualified_type.split_once(':')?;
    let namespace = scope.namespace(alias)?;
    let mut local = namespace == "http://schemas.android.com/apk/res-auto";
    let package = if local {
        None
    } else if let Some(package) = namespace.strip_prefix("http://schemas.android.com/apk/res/") {
        local = Some(package) == application_package;
        Some(package)
    } else if let Some(package) = namespace.strip_prefix("http://schemas.android.com/apk/prv/res/")
    {
        private = true;
        local = Some(package) == application_package;
        Some(package)
    } else {
        return None;
    };
    let mut normalized = String::new();
    normalized.push(sigil);
    if create {
        normalized.push('+');
    }
    if !local && private {
        normalized.push('*');
    }
    if !local {
        normalized.push_str(package?);
        normalized.push(':');
    }
    normalized.push_str(kind);
    if let Some(entry) = entry {
        normalized.push('/');
        normalized.push_str(entry);
    }
    Some(normalized)
}

fn macro_reference<'a>(
    scope: &XmlElement,
    reference: &'a str,
    application_package: Option<&str>,
) -> Result<Option<&'a str>, ParseError> {
    let reference = crate::android_reference::normalize(reference);
    let Some(mut reference) = reference.strip_prefix('@') else {
        return Ok(None);
    };
    reference = reference.strip_prefix('*').unwrap_or(reference);
    let Some((kind, name)) = reference.split_once('/') else {
        return Ok(None);
    };
    let Some((prefix, kind)) = kind
        .rsplit_once(':')
        .map_or(Some((None, kind)), |(prefix, kind)| {
            (!prefix.is_empty()).then_some((Some(prefix), kind))
        })
    else {
        return Ok(None);
    };
    if kind != "macro" {
        return Ok(None);
    }
    let Some(prefix) = prefix else {
        return Ok(Some(name));
    };
    let namespace = scope.namespace(prefix);
    if namespace == Some("http://schemas.android.com/apk/res-auto") {
        return Ok(Some(name));
    }
    let package = namespace
        .and_then(|namespace| {
            namespace
                .strip_prefix("http://schemas.android.com/apk/res/")
                .or_else(|| namespace.strip_prefix("http://schemas.android.com/apk/prv/res/"))
        })
        .unwrap_or(prefix);
    let Some(application_package) = application_package else {
        return Err(ParseError::new(
            "MISSING_ANDROID_APPLICATION_PACKAGE",
            "Package-qualified Android macros require the Android application package",
        ));
    };
    if package != application_package {
        return Err(unresolved_macro(reference));
    }
    Ok(Some(name))
}

fn unresolved_macro(reference: &str) -> ParseError {
    ParseError::new(
        "UNRESOLVED_ANDROID_MACRO_REFERENCE",
        format!("Android macro reference has no matching local definition: {reference}"),
    )
}

#[derive(Clone, Copy)]
struct FeatureCondition<'a> {
    expression: &'a str,
    enabled: bool,
    runtime: bool,
}

pub(crate) fn feature_enabled_with_path(
    element: &XmlElement,
    feature_flags: &BTreeMap<String, bool>,
    path_flag: Option<&str>,
) -> Result<bool, ParseError> {
    let condition = feature_condition(element, feature_flags)?;
    if let Some(path_flag) = path_flag {
        if condition.is_some() {
            return Err(conflicting_feature_flag());
        }
        return Ok(resolve_feature_condition(path_flag, feature_flags)?.enabled);
    }
    Ok(condition.is_none_or(|condition| condition.enabled))
}

pub(crate) fn runtime_feature_flag_with_path(
    element: &XmlElement,
    feature_flags: &BTreeMap<String, bool>,
    path_flag: Option<&str>,
) -> Result<Option<String>, ParseError> {
    let condition = path_flag.map_or_else(
        || feature_condition(element, feature_flags),
        |expression| resolve_feature_condition(expression, feature_flags).map(Some),
    )?;
    Ok(condition
        .filter(|condition| condition.runtime)
        .map(|condition| condition.expression.to_owned()))
}

fn feature_condition<'a>(
    element: &'a XmlElement,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<Option<FeatureCondition<'a>>, ParseError> {
    let Some(expression) = element
        .namespaced_attribute(ANDROID_NAMESPACE, "featureFlag")
        .map(trim_ascii)
        .filter(|expression| !expression.is_empty())
    else {
        return Ok(None);
    };
    resolve_feature_condition(expression, feature_flags).map(Some)
}

fn resolve_feature_condition<'a>(
    expression: &'a str,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<FeatureCondition<'a>, ParseError> {
    let (negated, name) = expression
        .strip_prefix('!')
        .map_or((false, expression), |name| (true, name));
    if crate::android_unset_feature_flag(feature_flags, name) {
        return Err(ParseError::new(
            "MISSING_ANDROID_FEATURE_FLAG_VALUE",
            "Android read-only feature flag has no value",
        ));
    }
    if crate::android_runtime_feature_flag(feature_flags, name) {
        return Ok(FeatureCondition {
            expression,
            enabled: true,
            runtime: true,
        });
    }
    let enabled = feature_flags.get(name).copied().ok_or_else(|| {
        ParseError::new(
            "UNRESOLVED_ANDROID_FEATURE_FLAG",
            "Android resource feature flag has no build value",
        )
    })?;
    Ok(FeatureCondition {
        expression,
        enabled: if negated { !enabled } else { enabled },
        runtime: false,
    })
}

fn conflicting_feature_flag() -> ParseError {
    ParseError::new(
        "CONFLICTING_ANDROID_FEATURE_FLAG",
        "Android feature flags are not allowed in both the resource path and file",
    )
}

fn validate_array_feature_flags(
    array: &XmlElement,
    feature_flags: &BTreeMap<String, bool>,
    configuration: Option<&Configuration>,
) -> Result<(), ParseError> {
    for item in array.elements() {
        if item.namespace.is_some() || item.local_name() != "item" {
            continue;
        }
        if feature_condition(item, feature_flags)?.is_some_and(|condition| condition.runtime)
            && !configuration.is_some_and(|configuration| {
                configuration.qualifiers.iter().any(|qualifier| {
                    qualifier
                        .strip_prefix('v')
                        .or_else(|| qualifier.strip_prefix('V'))
                        .and_then(|version| version.parse::<u32>().ok())
                        .is_some_and(|version| version >= 10_000)
                })
            })
        {
            return Err(ParseError::new(
                "UNSUPPORTED_ANDROID_RUNTIME_ARRAY_FLAG",
                "Android runtime-flagged array items require resource configuration SDK 10000",
            ));
        }
    }
    Ok(())
}

fn add_array(
    catalog: &mut Catalog,
    element: &XmlElement,
    description: &str,
    format: &str,
    bag_type: Option<&str>,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<(), ParseError> {
    let name = resource_name(element)?;
    let generic = element.local_name() == "array" || bag_type == Some("array");
    let qualified = resource_id(name, element, feature_flags)?;
    let items = bag_items(element)?;
    let mut retained = Vec::new();
    let mut references = Map::new();
    let mut primitives = Map::new();
    let mut item_flags = Map::new();
    let mut item_flag_modes = Map::new();
    for item in items {
        let condition = feature_condition(item, feature_flags)?;
        let raw = render(item, &mut Vec::new(), false, &mut false, &mut Vec::new())?;
        let reference = is_reference(&raw);
        let primitive = format != "string" && is_native_primitive(&raw, format);
        if !reference && !primitive && !format.is_empty() && format != "string" {
            return Err(ParseError::new(
                "INVALID_ANDROID_VALUE",
                "Android array item does not match its native format",
            ));
        }
        if condition.is_some_and(|condition| !condition.enabled) {
            continue;
        }
        let index = retained.len();
        retained.push(item);
        if let Some(condition) = condition {
            item_flags.insert(index.to_string(), json!(condition.expression));
            if condition.runtime {
                item_flag_modes.insert(index.to_string(), json!("read_write"));
            }
        }
        if reference {
            references.insert(index.to_string(), json!(trim_ascii(&raw)));
        } else if primitive {
            primitives.insert(index.to_string(), json!(trim_ascii(&raw)));
        }
    }
    for (index, item) in retained.into_iter().enumerate() {
        if references.contains_key(&index.to_string())
            || primitives.contains_key(&index.to_string())
        {
            continue;
        }
        let mut metadata = Map::new();
        metadata.insert("arrayIndex".into(), json!(index));
        metadata.insert("arrayName".into(), json!(name));
        if let Some(bag_type) = bag_type {
            metadata.insert("androidBagType".into(), json!(bag_type));
        }
        if !references.is_empty() {
            metadata.insert(
                "androidArrayReferences".into(),
                serde_json::Value::Object(references.clone()),
            );
        }
        if !primitives.is_empty() {
            metadata.insert(
                "androidArrayPrimitives".into(),
                serde_json::Value::Object(primitives.clone()),
            );
        }
        if !item_flags.is_empty() {
            metadata.insert(
                "androidArrayFeatureFlags".into(),
                serde_json::Value::Object(item_flags.clone()),
            );
        }
        if !item_flag_modes.is_empty() {
            metadata.insert(
                "androidArrayFeatureFlagModes".into(),
                serde_json::Value::Object(item_flag_modes.clone()),
            );
        }
        if let Some(condition) = feature_condition(element, feature_flags)? {
            metadata.insert("androidFeatureFlag".into(), json!(condition.expression));
            if condition.runtime {
                metadata.insert("androidFeatureFlagMode".into(), json!("read_write"));
            }
        }
        if generic {
            metadata.insert("androidGenericArray".into(), json!(true));
            if !format.is_empty() {
                metadata.insert("androidArrayFormat".into(), json!(format));
            }
        }
        add_product(element, &mut metadata);
        add_string(
            catalog,
            item,
            description,
            Some((format!("{qualified}[{index}]"), metadata)),
            feature_flags,
        )?;
    }
    Ok(())
}

fn add_string(
    catalog: &mut Catalog,
    element: &XmlElement,
    comment: &str,
    array: Option<(String, Map<String, serde_json::Value>)>,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<(), ParseError> {
    let generic_resource = array.is_none() && element.local_name() == "item";
    let generic_format = if generic_resource {
        generic_format(element)?
    } else {
        ""
    };
    let string_resource =
        array.is_none() && (element.local_name() == "string" || generic_format == "string");
    let scalar = array.is_none();
    let (id, mut metadata) = match array {
        Some(entry) => entry,
        None => (
            resource_id(resource_name(element)?, element, feature_flags)?,
            Map::new(),
        ),
    };
    if scalar {
        if let Some(condition) = feature_condition(element, feature_flags)? {
            metadata.insert("androidFeatureFlag".into(), json!(condition.expression));
            if condition.runtime {
                metadata.insert("androidFeatureFlagMode".into(), json!("read_write"));
            }
        }
    }
    add_product(element, &mut metadata);
    let description = element.attribute("description").unwrap_or(comment);
    let formatted = !string_resource || boolean_attribute(element, "formatted", true)?;
    let mut placeholders = Vec::new();
    let mut literal_markup = false;
    let mut protected_sections = Vec::new();
    let raw = render(
        element,
        &mut placeholders,
        false,
        &mut literal_markup,
        &mut protected_sections,
    )?;
    if is_reference(&raw) {
        return Ok(());
    }
    if generic_resource && generic_format != "string" {
        if generic_format.is_empty() && is_native_primitive(&raw, "") {
            return Ok(());
        }
        if !generic_format.is_empty() {
            if generic_format != "reference" && is_native_primitive(&raw, generic_format) {
                return Ok(());
            }
            return Err(ParseError::new(
                "INVALID_ANDROID_VALUE",
                "Android generic resource does not match its format",
            ));
        }
    }
    let mut message = unescape(&raw)?;
    let runtime_annotations = crate::android_annotation::spans(&message);
    let runtime_styles = crate::android_annotation::styles(&message)?;
    let runtime_paragraphs = crate::android_annotation::paragraphs(&message);
    let protected_attributes = StyleAttributeText::protect(&message)?;
    let raw_percent_occurrences = if formatted {
        placeholders::raw_percent_occurrences(&protected_attributes.value)
    } else {
        Vec::new()
    };
    let printf_line_separators = if formatted {
        placeholders::printf_line_separator_occurrences(&protected_attributes.value)
    } else {
        Vec::new()
    };
    if formatted {
        if string_resource && !has_styled_markup(element) {
            placeholders::validate_android(&protected_attributes.value)?;
        }
        message = placeholders::normalize(&protected_attributes.value, &mut placeholders, None);
    } else {
        metadata.insert("formatted".into(), json!(false));
    }
    let visible_newline_count = message
        .chars()
        .filter(|character| *character == '\n')
        .count();
    message = protected_attributes.restore(&message);
    let protected_occurrences =
        protected_placeholder_occurrences(&raw, &message, &protected_sections, formatted)?;
    if !protected_occurrences.is_empty() {
        metadata.insert(
            "androidProtectedPlaceholderOccurrences".into(),
            serde_json::Value::Object(protected_occurrences),
        );
    }
    validate_placeholder_identity(&placeholders)?;
    let quoted_markup = quote_attributed_markup(&message);
    if quoted_markup != message {
        metadata.insert("androidMarkupEscaping".into(), json!("icu-quoted-angle"));
    }
    if !printf_line_separators.is_empty() {
        metadata.insert("androidPrintfLineSeparator".into(), json!(true));
        if printf_line_separators.len() < visible_newline_count {
            metadata.insert(
                "androidPrintfLineSeparators".into(),
                json!(printf_line_separators),
            );
        }
    }
    if !runtime_annotations.is_empty() {
        metadata.insert(
            "androidRuntimeAnnotations".into(),
            crate::android_annotation::metadata(runtime_annotations),
        );
    }
    if !runtime_styles.is_empty() {
        metadata.insert(
            "androidRuntimeStyles".into(),
            crate::android_annotation::metadata(runtime_styles),
        );
    }
    if !runtime_paragraphs.is_empty() {
        metadata.insert(
            "androidRuntimeParagraphSpans".into(),
            crate::android_annotation::metadata(runtime_paragraphs),
        );
    }
    if !raw_percent_occurrences.is_empty() {
        metadata.insert(
            "androidRawPercentOccurrences".into(),
            json!(raw_percent_occurrences),
        );
    }
    if literal_markup {
        metadata.insert("androidLiteralMarkup".into(), json!(true));
    }
    if generic_resource {
        metadata.insert("androidGenericString".into(), json!(true));
        if !generic_format.is_empty() {
            metadata.insert("androidGenericFormat".into(), json!(generic_format));
        }
    }
    catalog.insert(
        id,
        Message::new(
            quoted_markup,
            Some(description.to_owned()),
            None,
            placeholders,
            metadata,
        ),
    )
}

fn add_plural(
    catalog: &mut Catalog,
    element: &XmlElement,
    comment: &str,
    bag_type: Option<&str>,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<(), ParseError> {
    let description = element.attribute("description").unwrap_or(comment);
    let mut placeholders = Vec::new();
    let mut ordered = Vec::new();
    let mut variants = BTreeMap::new();
    let mut references = Map::new();
    let mut raw_percent_occurrences = Map::new();
    let mut printf_line_separators = Map::new();
    let mut runtime_annotations = Map::new();
    let mut runtime_styles = Map::new();
    let mut runtime_paragraphs = Map::new();
    let mut plural_placeholder_examples = BTreeMap::new();
    let mut plural_protected_occurrences = Map::new();
    let mut distinct_placeholder_examples: BTreeMap<String, HashSet<Option<String>>> =
        BTreeMap::new();
    let mut quantities = std::collections::HashSet::new();
    let mut quoted_markup = false;
    let mut literal_markup = false;
    let mut newline_count = 0;
    for item in bag_items(element)? {
        let category = trim_ascii(item.attribute("quantity").unwrap_or_default()).to_owned();
        if !matches!(
            category.as_str(),
            "zero" | "one" | "two" | "few" | "many" | "other"
        ) {
            return Err(ParseError::new("INVALID_PLURAL_CATEGORY", category));
        }
        if !quantities.insert(category.clone()) {
            return Err(ParseError::new("DUPLICATE_PLURAL_CATEGORY", category));
        }
        let previous_placeholders = placeholders.len();
        let mut protected_sections = Vec::new();
        let raw = render(
            item,
            &mut placeholders,
            false,
            &mut literal_markup,
            &mut protected_sections,
        )?;
        if is_reference(&raw) {
            references.insert(category, json!(trim_ascii(&raw)));
            continue;
        }
        let source = unescape(&raw)?;
        let annotations = crate::android_annotation::spans(&source);
        if !annotations.is_empty() {
            runtime_annotations.insert(
                category.clone(),
                crate::android_annotation::metadata(annotations),
            );
        }
        let styles = crate::android_annotation::styles(&source)?;
        if !styles.is_empty() {
            runtime_styles.insert(
                category.clone(),
                crate::android_annotation::metadata(styles),
            );
        }
        let paragraphs = crate::android_annotation::paragraphs(&source);
        if !paragraphs.is_empty() {
            runtime_paragraphs.insert(
                category.clone(),
                crate::android_annotation::metadata(paragraphs),
            );
        }
        let protected_attributes = StyleAttributeText::protect(&source)?;
        let raw_occurrences = placeholders::raw_percent_occurrences(&protected_attributes.value);
        if !raw_occurrences.is_empty() {
            raw_percent_occurrences.insert(category.clone(), json!(raw_occurrences));
        }
        let line_separators =
            placeholders::printf_line_separator_occurrences(&protected_attributes.value);
        if !line_separators.is_empty() {
            printf_line_separators.insert(category.clone(), json!(line_separators));
        }
        let normalized =
            placeholders::normalize(&protected_attributes.value, &mut placeholders, None);
        let protected_occurrences =
            protected_placeholder_occurrences(&raw, &normalized, &protected_sections, true)?;
        if !protected_occurrences.is_empty() {
            plural_protected_occurrences.insert(
                category.to_owned(),
                serde_json::Value::Object(protected_occurrences.clone()),
            );
        }
        let mut category_examples: BTreeMap<String, Vec<Option<String>>> = BTreeMap::new();
        for placeholder in &placeholders[previous_placeholders..] {
            if placeholder.example.is_some()
                || placeholder
                    .position
                    .is_none_or(|position| placeholder.name != format!("arg{}", position - 1))
                || protected_occurrences.contains_key(&placeholder.name)
            {
                category_examples
                    .entry(placeholder.name.clone())
                    .or_default()
                    .push(placeholder.example.clone());
                distinct_placeholder_examples
                    .entry(placeholder.name.clone())
                    .or_default()
                    .insert(placeholder.example.clone());
            }
        }
        if !category_examples.is_empty() {
            plural_placeholder_examples.insert(category.clone(), category_examples);
        }
        newline_count += normalized
            .chars()
            .filter(|character| *character == '\n')
            .count();
        let normalized = protected_attributes.restore(&normalized);
        let value = quote_attributed_markup(&normalized);
        quoted_markup |= value != normalized;
        variants.insert(category.clone(), value.clone());
        ordered.push((category, value));
    }
    if !variants.contains_key("other") {
        if references.contains_key("other") {
            return Err(ParseError::new(
                "UNRESOLVED_ANDROID_PLURAL_REFERENCE",
                "Plural other references another resource and has no translatable fallback",
            ));
        }
        return Err(ParseError::new(
            "MISSING_OTHER_VARIANT",
            "Missing Android other",
        ));
    }
    validate_placeholder_identity(&placeholders)?;
    let selector = placeholders
        .iter()
        .find(|placeholder| placeholder.kind == "integer")
        .map(|placeholder| placeholder.name.as_str())
        .unwrap_or("count");
    let message = placeholders::plural(
        selector,
        ordered
            .iter()
            .map(|(category, value)| (category.as_str(), value.as_str())),
    );
    let mut metadata = Map::new();
    if let Some(bag_type) = bag_type {
        metadata.insert("androidBagType".into(), json!(bag_type));
    }
    if let Some(condition) = feature_condition(element, feature_flags)? {
        metadata.insert("androidFeatureFlag".into(), json!(condition.expression));
        if condition.runtime {
            metadata.insert("androidFeatureFlagMode".into(), json!("read_write"));
        }
    }
    add_product(element, &mut metadata);
    if !references.is_empty() {
        metadata.insert(
            "androidPluralReferences".into(),
            serde_json::Value::Object(references),
        );
    }
    if quoted_markup {
        metadata.insert("androidMarkupEscaping".into(), json!("icu-quoted-angle"));
    }
    if !runtime_annotations.is_empty() {
        metadata.insert(
            "androidPluralRuntimeAnnotations".into(),
            crate::android_annotation::plural_metadata(runtime_annotations),
        );
    }
    if !runtime_styles.is_empty() {
        metadata.insert(
            "androidPluralRuntimeStyles".into(),
            crate::android_annotation::plural_metadata(runtime_styles),
        );
    }
    if !runtime_paragraphs.is_empty() {
        metadata.insert(
            "androidPluralRuntimeParagraphSpans".into(),
            crate::android_annotation::plural_metadata(runtime_paragraphs),
        );
    }
    if distinct_placeholder_examples
        .values()
        .any(|examples| examples.len() > 1)
    {
        metadata.insert(
            "androidPluralPlaceholderExamples".into(),
            json!(plural_placeholder_examples),
        );
    }
    if !plural_protected_occurrences.is_empty() {
        metadata.insert(
            "androidPluralProtectedPlaceholderOccurrences".into(),
            serde_json::Value::Object(plural_protected_occurrences),
        );
    }
    if !printf_line_separators.is_empty() {
        metadata.insert("androidPrintfLineSeparator".into(), json!(true));
        let printf_count = printf_line_separators
            .values()
            .map(|positions| positions.as_array().expect("separator positions").len())
            .sum::<usize>();
        if printf_count < newline_count {
            metadata.insert(
                "androidPluralPrintfLineSeparators".into(),
                serde_json::Value::Object(printf_line_separators),
            );
        }
    }
    if !raw_percent_occurrences.is_empty() {
        metadata.insert(
            "androidPluralRawPercentOccurrences".into(),
            serde_json::Value::Object(raw_percent_occurrences),
        );
    }
    if literal_markup {
        metadata.insert("androidLiteralMarkup".into(), json!(true));
    }
    catalog.insert(
        resource_id(resource_name(element)?, element, feature_flags)?,
        Message::new(
            message,
            Some(description.to_owned()),
            Some(variants),
            placeholders,
            metadata,
        ),
    )
}

fn render(
    element: &XmlElement,
    placeholders: &mut Vec<Placeholder>,
    inside_xliff: bool,
    literal_markup: &mut bool,
    protected_sections: &mut Vec<ProtectedPlaceholderSection>,
) -> Result<String, ParseError> {
    let mut output = String::new();
    for child in &element.children {
        match child {
            XmlNode::Text(value) => {
                *literal_markup |= literal_markup_pattern().is_match(value);
                output.push_str(value);
            }
            XmlNode::Comment(_) => {}
            XmlNode::Element(child)
                if child.local_name() == "g"
                    && child.namespace.as_deref() == Some(XLIFF_NAMESPACE) =>
            {
                if inside_xliff {
                    return Err(ParseError::new(
                        "INVALID_ANDROID_MARKUP",
                        "Nested Android xliff:g sections are not allowed",
                    ));
                }
                if has_styled_markup(child) {
                    return Err(ParseError::new(
                        "INVALID_ANDROID_MARKUP",
                        "Styled markup inside xliff:g cannot be regenerated safely",
                    ));
                }
                let identifier = match child.attribute("id") {
                    None | Some("") => format!("_xliff{}", placeholders.len()),
                    Some(value) if xliff_identifier().is_match(value) => value.to_owned(),
                    Some(_) => {
                        return Err(ParseError::new(
                            "INVALID_PLACEHOLDER",
                            "XLIFF placeholder ID is not a valid ICU argument",
                        ))
                    }
                };
                let raw = render(
                    child,
                    placeholders,
                    true,
                    literal_markup,
                    protected_sections,
                )?;
                let previous_placeholders = placeholders.len();
                let mut normalized = placeholders::normalize(&raw, placeholders, Some(&identifier));
                if normalized == raw || !normalized.contains(&format!("{{{identifier}}}")) {
                    let placeholder = Placeholder {
                        name: identifier.clone(),
                        source: raw,
                        kind: "string",
                        position: None,
                        example: child.attribute("example").map(str::to_owned),
                    };
                    if !placeholders.contains(&placeholder) {
                        placeholders.push(placeholder);
                    }
                    normalized = format!("{{{identifier}}}");
                }
                if placeholders.len() > previous_placeholders {
                    if let Some(example) = child.attribute("example") {
                        if let Some(placeholder) = placeholders.last_mut() {
                            placeholder.example = Some(example.to_owned());
                        }
                    }
                } else if let Some(placeholder) = placeholders
                    .iter()
                    .rev()
                    .find(|placeholder| placeholder.name == identifier)
                {
                    let mut selected = placeholder.clone();
                    selected.example = child.attribute("example").map(str::to_owned);
                    if !placeholders.contains(&selected) {
                        placeholders.push(selected);
                    }
                }
                if placeholders.iter().any(|placeholder| {
                    placeholder.name == identifier
                        && placeholder
                            .position
                            .is_some_and(|position| identifier == format!("arg{}", position - 1))
                }) {
                    protected_sections.push(ProtectedPlaceholderSection {
                        name: identifier,
                        offset: output.len(),
                        example: child.attribute("example").map(str::to_owned),
                    });
                }
                output.push_str(&normalized);
            }
            XmlNode::Element(child) if child.namespace.is_some() => {
                let previous = protected_sections.len();
                let nested = render(
                    child,
                    placeholders,
                    inside_xliff,
                    literal_markup,
                    protected_sections,
                )?;
                for section in &mut protected_sections[previous..] {
                    section.offset += output.len();
                }
                output.push_str(&nested);
            }
            XmlNode::Element(child) => {
                output.push('<');
                output.push_str(&child.name);
                let mut attributes: Vec<_> = child
                    .attributes
                    .iter()
                    .filter(|(key, _)| !key.starts_with("xmlns"))
                    .collect();
                attributes.sort_by_key(|(key, _)| style_attribute_name(key));
                let mut names = HashSet::new();
                for (key, value) in attributes {
                    let name = style_attribute_name(key);
                    if !names.insert(name) {
                        return Err(ParseError::new(
                            "INVALID_ANDROID_MARKUP",
                            "Android style attributes have ambiguous native local names",
                        ));
                    }
                    output.push(' ');
                    output.push_str(name);
                    output.push_str("=\"");
                    output.push_str(
                        &value
                            .replace('&', "&amp;")
                            .replace('<', "&lt;")
                            .replace('"', "&quot;"),
                    );
                    output.push('"');
                }
                output.push('>');
                let previous = protected_sections.len();
                let nested = render(
                    child,
                    placeholders,
                    inside_xliff,
                    literal_markup,
                    protected_sections,
                )?;
                for section in &mut protected_sections[previous..] {
                    section.offset += output.len();
                }
                output.push_str(&nested);
                output.push_str("</");
                output.push_str(&child.name);
                output.push('>');
            }
        }
    }
    Ok(output)
}

fn style_attribute_name(key: &str) -> &str {
    key.rsplit(':').next().unwrap_or(key)
}

#[derive(Debug)]
struct ProtectedPlaceholderSection {
    name: String,
    offset: usize,
    example: Option<String>,
}

fn protected_placeholder_occurrences(
    raw: &str,
    canonical: &str,
    sections: &[ProtectedPlaceholderSection],
    formatted: bool,
) -> Result<Map<String, serde_json::Value>, ParseError> {
    let mut occurrences = Map::new();
    for section in sections {
        let prefix = unescape(&raw[..section.offset])?;
        let protected = StyleAttributeText::protect(&prefix)?;
        let normalized = if formatted {
            placeholders::normalize(&protected.value, &mut Vec::new(), None)
        } else {
            protected.value
        };
        let token = format!("{{{}}}", section.name);
        let occurrence = normalized.matches(&token).count();
        let total = canonical.matches(&token).count();
        let values = occurrences
            .entry(section.name.clone())
            .or_insert_with(|| serde_json::Value::Array(vec![serde_json::Value::Null; total]))
            .as_array_mut()
            .expect("protected occurrence list");
        let mut protected = Map::new();
        if let Some(example) = &section.example {
            protected.insert("example".into(), json!(example));
        }
        values[occurrence] = serde_json::Value::Object(protected);
    }
    occurrences.retain(|_, values| {
        let values = values.as_array().expect("protected occurrence list");
        values.iter().any(serde_json::Value::is_null)
            || values.iter().any(|value| {
                !value
                    .as_object()
                    .expect("protected occurrence")
                    .contains_key("example")
            })
    });
    Ok(occurrences)
}

fn literal_markup_pattern() -> &'static regex::Regex {
    static PATTERN: OnceLock<regex::Regex> = OnceLock::new();
    PATTERN.get_or_init(|| regex::Regex::new(r"</?[A-Za-z][^>]*>").expect("valid literal markup"))
}

struct StyleAttributeText {
    value: String,
    percent: char,
    newline: char,
    carriage_return: char,
    tab: char,
}

impl StyleAttributeText {
    fn protect(source: &str) -> Result<Self, ParseError> {
        let mut markers = Vec::with_capacity(4);
        let mut candidate = 0xe000;
        for _ in 0..4 {
            while candidate <= 0xf8ff
                && source.contains(char::from_u32(candidate).expect("private-use character"))
            {
                candidate += 1;
            }
            if candidate > 0xf8ff {
                return Err(ParseError::new(
                    "INVALID_ANDROID_MARKUP",
                    "Android style attributes cannot be normalized safely",
                ));
            }
            markers.push(char::from_u32(candidate).expect("private-use character"));
            candidate += 1;
        }
        let mut protected = String::with_capacity(source.len());
        let mut inside_tag = false;
        let mut inside_attribute = false;
        for (index, character) in source.char_indices() {
            if !inside_tag
                && character == '<'
                && source[index + character.len_utf8()..]
                    .chars()
                    .next()
                    .is_some_and(|next| next.is_ascii_alphabetic() || next == '/')
            {
                inside_tag = true;
            }
            if inside_tag && character == '"' {
                inside_attribute = !inside_attribute;
            } else if inside_tag && !inside_attribute && character == '>' {
                inside_tag = false;
            }
            protected.push(if inside_tag && inside_attribute {
                match character {
                    '%' => markers[0],
                    '\n' => markers[1],
                    '\r' => markers[2],
                    '\t' => markers[3],
                    _ => character,
                }
            } else {
                character
            });
        }
        Ok(Self {
            value: protected,
            percent: markers[0],
            newline: markers[1],
            carriage_return: markers[2],
            tab: markers[3],
        })
    }

    fn restore(&self, source: &str) -> String {
        source
            .replace(self.percent, "%")
            .replace(self.newline, "\n")
            .replace(self.carriage_return, "\r")
            .replace(self.tab, "\t")
    }
}

fn xliff_identifier() -> &'static regex::Regex {
    static PATTERN: OnceLock<regex::Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        regex::Regex::new(r"^[\p{L}\p{N}\p{M}\p{So}_]+$")
            .expect("valid Unicode XLIFF identifier pattern")
    })
}

fn validate_placeholder_identity(placeholders: &[Placeholder]) -> Result<(), ParseError> {
    let mut previous: std::collections::HashMap<&str, &Placeholder> =
        std::collections::HashMap::new();
    for placeholder in placeholders {
        if let Some(existing) = previous.insert(&placeholder.name, placeholder) {
            if existing.position != placeholder.position
                || (existing.position.is_none() && existing.source != placeholder.source)
            {
                return Err(ParseError::new(
                    "INVALID_PLACEHOLDER",
                    format!(
                        "Conflicting Android placeholder identity: {}",
                        placeholder.name
                    ),
                ));
            }
        }
    }
    Ok(())
}

fn bag_items(element: &XmlElement) -> Result<Vec<&XmlElement>, ParseError> {
    let mut items = Vec::new();
    for child in element.elements() {
        if child.namespace.is_none() && child.local_name() == "item" {
            items.push(child);
        } else if child.namespace.is_some() || !matches!(child.local_name(), "skip" | "eat-comment")
        {
            return Err(ParseError::new(
                "INVALID_ANDROID_STRUCTURE",
                format!("Unexpected Android {} child element", element.local_name()),
            ));
        }
    }
    Ok(items)
}

fn is_bag_type(value: &str) -> bool {
    matches!(
        value,
        "add-resource"
            | "array"
            | "attr"
            | "configVarying"
            | "declare-styleable"
            | "integer-array"
            | "java-symbol"
            | "overlayable"
            | "plurals"
            | "public"
            | "public-group"
            | "staging-public-group"
            | "staging-public-group-final"
            | "string-array"
            | "style"
            | "symbol"
    )
}

fn invalid_structure(message: impl Into<String>) -> ParseError {
    ParseError::new("INVALID_ANDROID_STRUCTURE", message)
}

pub(crate) fn resource_name(element: &XmlElement) -> Result<&str, ParseError> {
    static PATTERN: OnceLock<regex::Regex> = OnceLock::new();
    let pattern = PATTERN.get_or_init(|| {
        regex::Regex::new(r"^(?:_|\p{XID_Start})[\p{XID_Continue}.\-]*$")
            .expect("valid Android Unicode resource identifier pattern")
    });
    let name = trim_ascii(element.attribute("name").unwrap_or_default());
    if pattern.is_match(name)
        && name
            .chars()
            .all(|value| value <= '\u{ffff}' && !matches!(value, '\u{200c}' | '\u{200d}'))
    {
        Ok(name)
    } else {
        Err(ParseError::new(
            "INVALID_ANDROID_RESOURCE_NAME",
            "Android resource declarations require a valid entry name",
        ))
    }
}

pub(crate) fn resource_product(element: &XmlElement) -> &str {
    trim_ascii(element.attribute("product").unwrap_or_default())
}

fn resource_id(
    name: &str,
    element: &XmlElement,
    feature_flags: &BTreeMap<String, bool>,
) -> Result<String, ParseError> {
    let product = resource_product(element);
    let id = if product.is_empty() || product == "default" {
        name.to_owned()
    } else {
        format!("{name}@product={product}")
    };
    if let Some(condition) = feature_condition(element, feature_flags)? {
        if condition.runtime {
            return Ok(format!("{id}@flag={}", condition.expression));
        }
    }
    Ok(id)
}

fn add_product(element: &XmlElement, metadata: &mut Map<String, serde_json::Value>) {
    let product = resource_product(element);
    if !product.is_empty() {
        metadata.insert("androidProduct".into(), json!(product));
    }
}

fn quote_attributed_markup(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut result = String::new();
    let mut tags: Vec<(&str, bool)> = Vec::new();
    let mut index = 0;
    let mut converted = false;
    while index < input.len() {
        if bytes[index] != b'<'
            || bytes
                .get(index + 1)
                .is_none_or(|next| !next.is_ascii_alphabetic() && *next != b'/')
        {
            let character = input[index..]
                .chars()
                .next()
                .expect("valid character boundary");
            push_icu_literal(&mut result, &input[index..index + character.len_utf8()]);
            index += character.len_utf8();
            continue;
        }
        let closing = bytes[index + 1] == b'/';
        let start = index + if closing { 2 } else { 1 };
        let mut name_end = start;
        while bytes.get(name_end).is_some_and(|character| {
            character.is_ascii_alphanumeric() || matches!(character, b'_' | b'-' | b'.')
        }) {
            name_end += 1;
        }
        let Some(end) = find_markup_end(bytes, name_end) else {
            push_icu_literal(&mut result, "<");
            index += 1;
            continue;
        };
        if name_end == start {
            push_icu_literal(&mut result, "<");
            index += 1;
            continue;
        }
        let name = &input[start..name_end];
        let quoted = if closing {
            if tags.last().is_some_and(|(previous, _)| *previous == name) {
                tags.pop().expect("matching markup tag").1
            } else {
                false
            }
        } else {
            let attributed = !input[name_end..end].trim().is_empty();
            tags.push((name, attributed));
            attributed
        };
        if quoted {
            converted = true;
            result.push_str("'<'");
            push_icu_literal(&mut result, &input[index + 1..=end]);
        } else {
            push_icu_literal(&mut result, &input[index..=end]);
        }
        index = end + 1;
    }
    if converted {
        result
    } else {
        input.to_owned()
    }
}

fn push_icu_literal(output: &mut String, value: &str) {
    output.push_str(&value.replace('\'', "''"));
}

fn find_markup_end(input: &[u8], start: usize) -> Option<usize> {
    let mut quoted = false;
    for (offset, character) in input.iter().enumerate().skip(start) {
        if *character == b'"' {
            quoted = !quoted;
        } else if *character == b'>' && !quoted {
            return Some(offset);
        }
    }
    None
}

fn is_reference(input: &str) -> bool {
    crate::android_reference::is_reference(input)
}

fn generic_format(element: &XmlElement) -> Result<&str, ParseError> {
    let format = trim_ascii(element.attribute("format").unwrap_or_default());
    if !format.is_empty()
        && !matches!(
            format,
            "reference"
                | "string"
                | "integer"
                | "boolean"
                | "color"
                | "float"
                | "dimension"
                | "fraction"
        )
    {
        return Err(ParseError::new(
            "INVALID_ANDROID_FORMAT",
            format!("Unsupported Android generic resource format: {format}"),
        ));
    }
    Ok(format)
}

pub(crate) fn is_native_primitive(raw: &str, format: &str) -> bool {
    static INTEGER: OnceLock<regex::Regex> = OnceLock::new();
    static NUMBER: OnceLock<regex::Regex> = OnceLock::new();
    static COLOR: OnceLock<regex::Regex> = OnceLock::new();
    let integer =
        INTEGER.get_or_init(|| regex::Regex::new(r"^[+-]?(?:0[xX][0-9A-Fa-f]+|[0-9]+)$").unwrap());
    let number = NUMBER.get_or_init(|| {
        regex::Regex::new(r"^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)$")
            .unwrap()
    });
    let color = COLOR.get_or_init(|| {
        regex::Regex::new(r"^#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
            .unwrap()
    });
    let value = trim_ascii(raw);
    if (format.is_empty() || format == "boolean")
        && matches!(
            value,
            "true" | "True" | "TRUE" | "false" | "False" | "FALSE"
        )
    {
        return true;
    }
    if (format.is_empty() || format == "integer") && integer.is_match(value) {
        return true;
    }
    if (format.is_empty() || format == "color") && color.is_match(value) {
        return true;
    }
    if (format.is_empty() || format == "float") && number.is_match(value) {
        return true;
    }
    if format.is_empty() || format == "dimension" {
        for suffix in ["px", "dp", "dip", "sp", "pt", "in", "mm"] {
            if value
                .strip_suffix(suffix)
                .is_some_and(|number_value| number.is_match(number_value))
            {
                return true;
            }
        }
    }
    if format.is_empty() || format == "fraction" {
        for suffix in ["%p", "%"] {
            if value
                .strip_suffix(suffix)
                .is_some_and(|number_value| number.is_match(number_value))
            {
                return true;
            }
        }
    }
    false
}

fn boolean_attribute(element: &XmlElement, name: &str, fallback: bool) -> Result<bool, ParseError> {
    let Some(value) = element.attribute(name) else {
        return Ok(fallback);
    };
    match trim_ascii(value) {
        "true" | "True" | "TRUE" => Ok(true),
        "false" | "False" | "FALSE" => Ok(false),
        _ => Err(ParseError::new(
            "INVALID_ANDROID_BOOLEAN",
            format!("Invalid Android boolean attribute: {name}"),
        )),
    }
}

fn validate_protected_items(resource: &XmlElement) -> Result<(), ParseError> {
    for item in resource
        .elements()
        .filter(|child| child.namespace.is_none() && child.local_name() == "item")
    {
        validate_protected_string(item)?;
    }
    Ok(())
}

fn validate_protected_string(resource: &XmlElement) -> Result<(), ParseError> {
    let mut content = String::new();
    append_protected_text(resource, &mut content, false)?;
    unescape(&content)?;
    Ok(())
}

fn append_protected_text(
    resource: &XmlElement,
    content: &mut String,
    xliff: bool,
) -> Result<(), ParseError> {
    for node in &resource.children {
        match node {
            XmlNode::Text(value) => content.push_str(value),
            XmlNode::Comment(_) => {}
            XmlNode::Element(element) => {
                let protected = element.local_name() == "g"
                    && element.namespace.as_deref() == Some(XLIFF_NAMESPACE);
                let styled = element.namespace.is_none();
                if protected && xliff {
                    return Err(ParseError::new(
                        "INVALID_ANDROID_MARKUP",
                        "Nested Android xliff:g sections are not allowed",
                    ));
                }
                if styled {
                    content.push('<');
                    content.push_str(element.local_name());
                    content.push('>');
                }
                append_protected_text(element, content, xliff || protected)?;
                if styled {
                    content.push_str("</");
                    content.push_str(element.local_name());
                    content.push('>');
                }
            }
        }
    }
    Ok(())
}

fn android_false(value: &str) -> bool {
    matches!(value, "false" | "False" | "FALSE")
}

fn has_styled_markup(element: &XmlElement) -> bool {
    element
        .elements()
        .any(|child| child.namespace.is_none() || has_styled_markup(child))
}

fn trim_ascii(input: &str) -> &str {
    input.trim_matches(|character: char| character.is_ascii_whitespace())
}

fn unescape(input: &str) -> Result<String, ParseError> {
    let mut output = String::new();
    let mut characters = input.chars().peekable();
    let mut quoted = false;
    let mut pending_whitespace = false;
    while let Some(character) = characters.next() {
        if character == '<'
            && characters
                .peek()
                .is_some_and(|next| next.is_ascii_alphabetic() || *next == '/')
            && {
                let mut attributes = false;
                characters.clone().any(|value| {
                    if value == '"' {
                        attributes = !attributes;
                    }
                    value == '>' && !attributes
                })
            }
        {
            flush_whitespace(&mut output, &mut pending_whitespace);
            quoted = false;
            output.push(character);
            let mut attribute = false;
            for value in characters.by_ref() {
                output.push(value);
                if value == '"' {
                    attribute = !attribute;
                } else if value == '>' && !attribute {
                    break;
                }
            }
            continue;
        }
        if character == '"' {
            if !quoted {
                flush_whitespace(&mut output, &mut pending_whitespace);
            }
            pending_whitespace = false;
            quoted = !quoted;
            continue;
        }
        if !quoted && character == '\'' {
            return Err(ParseError::new(
                "UNESCAPED_APOSTROPHE",
                "Android apostrophes must be escaped or quoted",
            ));
        }
        if !quoted && is_android_whitespace(character) {
            pending_whitespace = true;
            continue;
        }
        flush_whitespace(&mut output, &mut pending_whitespace);
        if character != '\\' {
            output.push(character);
            continue;
        }
        if characters.peek().is_none() {
            break;
        }
        if is_markup_boundary(&characters) {
            continue;
        }
        let escaped = characters.next().expect("checked Android escape");
        match escaped {
            'n' => output.push('\n'),
            't' => output.push('\t'),
            'u' => {
                let mut code = 0;
                for _ in 0..4 {
                    if characters.peek().is_none() || is_markup_boundary(&characters) {
                        break;
                    }
                    let digit = characters.next().expect("checked Unicode escape");
                    if !digit.is_ascii_hexdigit() {
                        return Err(ParseError::new(
                            "INVALID_UNICODE_ESCAPE",
                            "Invalid Unicode escape",
                        ));
                    }
                    code = (code << 4) | digit.to_digit(16).expect("checked hexadecimal digit");
                }
                if let Some(character) = char::from_u32(code) {
                    output.push(character);
                }
            }
            other => output.push(other),
        }
    }
    Ok(output)
}

fn is_markup_boundary(characters: &std::iter::Peekable<std::str::Chars<'_>>) -> bool {
    let mut next = characters.clone();
    next.next() == Some('<')
        && next
            .next()
            .is_some_and(|character| character.is_ascii_alphabetic() || character == '/')
}

fn flush_whitespace(output: &mut String, pending_whitespace: &mut bool) {
    if *pending_whitespace && !output.is_empty() {
        output.push(' ');
    }
    *pending_whitespace = false;
}

fn is_android_whitespace(value: char) -> bool {
    // Current AAPT2 preserves Unicode separators and no-break spaces despite contrary docs.
    matches!(value, ' ' | '\n' | '\r' | '\t' | '\u{000c}' | '\u{000b}')
}
