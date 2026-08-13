use crate::model::{Catalog, FileFormat, ParseError};
use crate::xml::XmlElement;
use crate::{android, decode, xml, AndroidResourceInput};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Eq, Ord, PartialEq, PartialOrd)]
struct ResourceIdentity {
    kind: String,
    name: String,
}

type ProductGroups = BTreeMap<ResourceIdentity, BTreeMap<String, bool>>;

pub(crate) fn select(
    source: &str,
    catalog: Catalog,
    feature_flags: &BTreeMap<String, bool>,
    products: &[String],
    resource_path: Option<&str>,
) -> Result<Catalog, ParseError> {
    let requested = requested(products)?;
    let mut groups = ProductGroups::new();
    let configuration = resource_path
        .map(crate::android_configuration::Configuration::parse)
        .transpose()?;
    collect(
        &xml::parse(source)?,
        feature_flags,
        &mut groups,
        configuration
            .as_ref()
            .and_then(|configuration| configuration.path_feature_flag.as_deref()),
    )?;
    filter(catalog, groups, &requested)
}

pub(crate) fn select_overlay(
    inputs: &[AndroidResourceInput<'_>],
    catalog: Catalog,
    feature_flags: &BTreeMap<String, bool>,
    products: &[String],
) -> Result<Catalog, ParseError> {
    let requested = requested(products)?;
    let mut ordered: Vec<_> = inputs.iter().collect();
    ordered.sort_by_key(|input| priority(input.source_set));
    let mut groups = ProductGroups::new();
    for input in ordered {
        collect(
            &xml::parse(&decode(
                input.source,
                crate::xml_encoding(FileFormat::Android, input.source)?,
            )?)?,
            feature_flags,
            &mut groups,
            crate::android_configuration::Configuration::parse(input.resource_path)?
                .path_feature_flag
                .as_deref(),
        )?;
    }
    filter(catalog, groups, &requested)
}

fn requested(products: &[String]) -> Result<BTreeSet<&str>, ParseError> {
    if products.is_empty() {
        return Err(invalid_product());
    }
    let mut result = BTreeSet::new();
    for product in products {
        if product.is_empty()
            || product != product.trim_matches(|character: char| character <= '\u{0020}')
            || product.contains(',')
            || !result.insert(product.as_str())
        {
            return Err(invalid_product());
        }
    }
    if result.len() > 1 {
        result.remove("default");
    }
    Ok(result)
}

fn invalid_product() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_PRODUCT",
        "Android build products must be distinct nonempty names",
    )
}

fn collect(
    root: &XmlElement,
    feature_flags: &BTreeMap<String, bool>,
    groups: &mut ProductGroups,
    path_feature_flag: Option<&str>,
) -> Result<(), ParseError> {
    for element in root
        .elements()
        .filter(|element| element.namespace.is_none())
    {
        let Some(kind) = declaration_kind(element) else {
            continue;
        };
        let identity = ResourceIdentity {
            kind: kind.to_owned(),
            name: android::resource_name(element)?.to_owned(),
        };
        let product = match android::resource_product(element) {
            "" | "default" => "default",
            value => value,
        };
        let enabled =
            android::feature_enabled_with_path(element, feature_flags, path_feature_flag)?;
        let variants = groups.entry(identity).or_default();
        if enabled || !variants.contains_key(product) {
            variants.insert(product.to_owned(), enabled);
        }
    }
    Ok(())
}

fn declaration_kind(element: &XmlElement) -> Option<&str> {
    let kind = if element.local_name() == "bag" {
        element.attribute("type").unwrap_or_default().trim()
    } else {
        element.local_name()
    };
    match kind {
        "string" => Some("string"),
        "item" if element.attribute("type").unwrap_or_default().trim() == "string" => {
            Some("string")
        }
        "array" | "integer-array" | "string-array" => Some("array"),
        "plurals" => Some("plurals"),
        _ => None,
    }
}

fn filter(
    catalog: Catalog,
    groups: ProductGroups,
    requested: &BTreeSet<&str>,
) -> Result<Catalog, ParseError> {
    let mut selected = BTreeMap::new();
    for (identity, variants) in groups {
        if !variants.contains_key("default") {
            return Err(ParseError::new(
                "MISSING_ANDROID_PRODUCT_DEFAULT",
                format!("No default product defined for resource {}", identity.name),
            ));
        }
        let choices: Vec<_> = variants
            .keys()
            .filter(|product| requested.contains(product.as_str()))
            .collect();
        if choices.len() > 1 {
            return Err(ParseError::new(
                "AMBIGUOUS_ANDROID_PRODUCT",
                format!(
                    "Multiple selected products match resource {}",
                    identity.name
                ),
            ));
        }
        let product = choices.first().map_or("default", |value| value.as_str());
        if variants.get(product) == Some(&true) {
            selected.insert(identity, product.to_owned());
        }
    }

    let mut result = Catalog::new(FileFormat::Android);
    result.locale = catalog.locale;
    for (id, mut message) in catalog.messages {
        let original_product = message
            .metadata
            .as_mut()
            .and_then(|metadata| metadata.remove("androidProduct"))
            .and_then(|value| value.as_str().map(str::to_owned))
            .filter(|product| !product.is_empty() && product != "default")
            .unwrap_or_else(|| "default".to_owned());
        let array_name = message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("arrayName"))
            .and_then(serde_json::Value::as_str);
        let runtime_suffix = message
            .metadata
            .as_ref()
            .and_then(|metadata| {
                if metadata
                    .get("androidFeatureFlagMode")
                    .and_then(serde_json::Value::as_str)
                    == Some("read_write")
                {
                    metadata.get("androidFeatureFlag")
                } else if metadata
                    .get("androidPathFeatureFlagMode")
                    .and_then(serde_json::Value::as_str)
                    == Some("read_write")
                {
                    metadata.get("androidPathFeatureFlag")
                } else {
                    None
                }
            })
            .and_then(serde_json::Value::as_str)
            .map(|expression| format!("@flag={expression}"));
        let base = array_name.map_or_else(
            || {
                let native_id = runtime_suffix
                    .as_ref()
                    .and_then(|suffix| id.strip_suffix(suffix))
                    .unwrap_or(&id);
                if original_product == "default" {
                    native_id.to_owned()
                } else {
                    native_id
                        .strip_suffix(&format!("@product={original_product}"))
                        .unwrap_or(native_id)
                        .to_owned()
                }
            },
            str::to_owned,
        );
        let kind = if array_name.is_some() {
            "array"
        } else if message.variants.is_some() {
            "plurals"
        } else {
            "string"
        };
        let identity = ResourceIdentity {
            kind: kind.to_owned(),
            name: base,
        };
        if runtime_suffix.is_none() && selected.get(&identity) != Some(&original_product) {
            continue;
        }
        if message
            .metadata
            .as_ref()
            .is_some_and(|metadata| metadata.is_empty())
        {
            message.metadata = None;
        }
        let canonical_id = if original_product == "default" {
            id
        } else {
            id.replace(&format!("@product={original_product}"), "")
        };
        result.insert(canonical_id, message)?;
    }
    Ok(result)
}

fn priority(source_set: &str) -> u8 {
    match source_set {
        "library" => 0,
        "main" => 1,
        "product_flavor" => 2,
        "build_type" => 3,
        "build_variant" => 4,
        _ => u8::MAX,
    }
}
