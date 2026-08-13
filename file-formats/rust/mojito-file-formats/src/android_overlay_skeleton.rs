use crate::{
    parse_android_overlay_with_feature_flag_definitions_and_package, AndroidFeatureFlag,
    AndroidResourceInput, Catalog, FileFormat, ParseError, SourceSkeleton,
};
use serde::Serialize;
use std::collections::{BTreeMap, HashSet};

/// Ordered original Android sources whose translation slots belong only to overlay winners.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidOverlaySourceSkeleton {
    pub schema_version: u8,
    pub source_format: &'static str,
    pub sources: Vec<AndroidOverlaySourceFile>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_selected_products: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_runtime_slot_owners: Option<BTreeMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_application_package: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_macro_owners: Option<BTreeMap<String, AndroidOverlayMacroOwner>>,
}

/// Winning source-set declaration of an AAPT2 build-only macro resource.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidOverlayMacroOwner {
    pub source_set: String,
    pub resource_path: String,
}

/// Exact original Gradle source-set identity and its independently reversible source bytes.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidOverlaySourceFile {
    pub source_set: String,
    pub resource_path: String,
    pub skeleton: SourceSkeleton,
}

/// Original source identity and localized bytes returned in unchanged source-file order.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AndroidOverlayLocalizedResource {
    pub source_set: String,
    pub resource_path: String,
    pub source: Vec<u8>,
}

pub(crate) fn extract(
    inputs: &[AndroidResourceInput<'_>],
    feature_flags: &[AndroidFeatureFlag],
    selected_products: Option<&[String]>,
    application_package: Option<&str>,
) -> Result<AndroidOverlaySourceSkeleton, ParseError> {
    let original_winners = parse_android_overlay_with_feature_flag_definitions_and_package(
        inputs,
        feature_flags,
        None,
        application_package,
    )?;
    let runtime_winners = selected_products
        .map(|products| {
            parse_android_overlay_with_feature_flag_definitions_and_package(
                inputs,
                feature_flags,
                Some(products),
                application_package,
            )
        })
        .transpose()?;
    let mut sources = Vec::with_capacity(inputs.len());
    let mut source_identities = HashSet::new();
    let mut slot_identities = HashSet::new();
    let mut runtime_slot_owners = BTreeMap::new();
    let macro_owners = crate::android_overlay::macro_owners(inputs)?;
    for input in inputs {
        if !source_identities.insert((input.source_set, input.resource_path)) {
            return Err(invalid(format!(
                "Duplicate Android overlay source identity: {}",
                input.resource_path
            )));
        }
        let source_catalog =
            owned_catalog(&original_winners, input.source_set, input.resource_path)?;
        let mut skeleton = crate::source_skeleton::extract_android_with_catalog(
            input.source,
            Some(input.resource_path),
            feature_flags,
            Some(&source_catalog),
        )?;
        skeleton.slots.retain(|slot| {
            let Some(winner) = original_winners.messages.get(&slot.id) else {
                return false;
            };
            let Some(metadata) = &winner.metadata else {
                return false;
            };
            let original_owner = metadata
                .get("androidOverlaySourceSet")
                .and_then(serde_json::Value::as_str)
                == Some(input.source_set)
                && metadata
                    .get("androidResourcePath")
                    .and_then(serde_json::Value::as_str)
                    == Some(input.resource_path)
                && slot.variant.as_ref().is_none_or(|variant| {
                    winner
                        .variants
                        .as_ref()
                        .is_some_and(|variants| variants.contains_key(variant))
                });
            if !original_owner {
                return false;
            }
            if let Some(runtime) = &runtime_winners {
                let runtime_id = selected_runtime_id(&slot.id, winner);
                let Some(winner) = runtime.messages.get(&runtime_id) else {
                    return false;
                };
                let Some(metadata) = &winner.metadata else {
                    return false;
                };
                return metadata
                    .get("androidOverlaySourceSet")
                    .and_then(serde_json::Value::as_str)
                    == Some(input.source_set)
                    && metadata
                        .get("androidResourcePath")
                        .and_then(serde_json::Value::as_str)
                        == Some(input.resource_path)
                    && slot.variant.as_ref().is_none_or(|variant| {
                        winner
                            .variants
                            .as_ref()
                            .is_some_and(|variants| variants.contains_key(variant))
                    })
                    && selected_product_owner(
                        &original_winners,
                        original_winners.messages.get(&slot.id).unwrap(),
                        &runtime_id,
                        selected_products.unwrap(),
                    );
            }
            true
        });
        for slot in &skeleton.slots {
            if !slot_identities.insert(slot.key()) {
                return Err(invalid(format!(
                    "Duplicate winning Android overlay slot: {}",
                    slot.key()
                )));
            }
            if runtime_winners.is_some() {
                let runtime_id =
                    selected_runtime_id(&slot.id, original_winners.messages.get(&slot.id).unwrap());
                let runtime_key = slot.variant.as_ref().map_or(runtime_id.clone(), |variant| {
                    format!("{runtime_id}#{variant}")
                });
                if runtime_slot_owners
                    .insert(runtime_key.clone(), slot.key())
                    .is_some()
                {
                    return Err(invalid(format!(
                        "Duplicate selected Android runtime slot: {runtime_key}"
                    )));
                }
            }
        }
        sources.push(AndroidOverlaySourceFile {
            source_set: input.source_set.to_owned(),
            resource_path: input.resource_path.to_owned(),
            skeleton,
        });
    }
    Ok(AndroidOverlaySourceSkeleton {
        schema_version: 1,
        source_format: "android",
        sources,
        android_selected_products: selected_products.map(<[String]>::to_vec),
        android_runtime_slot_owners: selected_products.map(|_| runtime_slot_owners),
        android_application_package: application_package.map(str::to_owned),
        android_macro_owners: (!macro_owners.is_empty()).then_some(macro_owners),
    })
}

pub(crate) fn render(
    overlay: &AndroidOverlaySourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<AndroidOverlayLocalizedResource>, ParseError> {
    if overlay.schema_version != 1 || overlay.source_format != "android" {
        return Err(invalid("Unsupported Android overlay source skeleton"));
    }
    let mut source_identities = HashSet::new();
    let mut slot_identities = HashSet::new();
    for source in &overlay.sources {
        if !source_identities.insert((&source.source_set, &source.resource_path))
            || source.skeleton.android_resource_path.as_deref()
                != Some(source.resource_path.as_str())
        {
            return Err(invalid(format!(
                "Invalid Android overlay source identity: {}",
                source.resource_path
            )));
        }
        for slot in &source.skeleton.slots {
            if !slot_identities.insert(slot.key()) {
                return Err(invalid(format!(
                    "Duplicate winning Android overlay slot: {}",
                    slot.key()
                )));
            }
        }
    }
    if overlay.android_selected_products.is_some() != overlay.android_runtime_slot_owners.is_some()
    {
        return Err(invalid(
            "Selected Android products require complete runtime slot ownership",
        ));
    }
    if let Some(runtime_slots) = &overlay.android_runtime_slot_owners {
        let unique_sources: HashSet<_> = runtime_slots.values().cloned().collect();
        if runtime_slots.len() != slot_identities.len() || unique_sources != slot_identities {
            return Err(invalid(
                "Selected Android runtime slots must own every source exactly once",
            ));
        }
    }
    for key in translations.keys() {
        let known = overlay.android_runtime_slot_owners.as_ref().map_or_else(
            || slot_identities.contains(key),
            |slots| slots.contains_key(key),
        );
        if !known {
            return Err(ParseError::new(
                "UNKNOWN_OVERLAY_SKELETON_SLOT",
                format!("Unknown winning Android overlay slot: {key}"),
            ));
        }
    }
    let original_bytes: Vec<Vec<u8>> = overlay
        .sources
        .iter()
        .map(|source| crate::source_skeleton::original_source_bytes(&source.skeleton))
        .collect::<Result<_, _>>()?;
    let originals: Vec<AndroidResourceInput<'_>> = overlay
        .sources
        .iter()
        .zip(&original_bytes)
        .map(|(source, bytes)| AndroidResourceInput {
            source_set: &source.source_set,
            resource_path: &source.resource_path,
            source: bytes,
        })
        .collect();
    let flags = overlay
        .sources
        .iter()
        .find_map(|source| source.skeleton.android_feature_flags.as_deref())
        .unwrap_or_default();
    let original_catalog = parse_android_overlay_with_feature_flag_definitions_and_package(
        &originals,
        flags,
        None,
        overlay.android_application_package.as_deref(),
    )?;
    let actual_macros = crate::android_overlay::macro_owners(&originals)?;
    if overlay.android_macro_owners.as_ref()
        != (!actual_macros.is_empty()).then_some(&actual_macros)
    {
        return Err(invalid(
            "Android macro definitions do not match their original source ownership",
        ));
    }
    let source_to_runtime: BTreeMap<_, _> = overlay
        .android_runtime_slot_owners
        .iter()
        .flat_map(|slots| slots.iter().map(|(runtime, source)| (source, runtime)))
        .collect();
    let mut result = Vec::with_capacity(overlay.sources.len());
    for source in &overlay.sources {
        let owned = source
            .skeleton
            .slots
            .iter()
            .filter_map(|slot| {
                let key = slot.key();
                let runtime = source_to_runtime
                    .get(&key)
                    .map_or(key.as_str(), |value| value.as_str());
                translations.get(runtime).map(|value| (key, value.clone()))
            })
            .collect();
        result.push(AndroidOverlayLocalizedResource {
            source_set: source.source_set.clone(),
            resource_path: source.resource_path.clone(),
            source: crate::source_skeleton::render_android_with_catalog(
                &source.skeleton,
                &owned,
                Some(&owned_catalog(
                    &original_catalog,
                    &source.source_set,
                    &source.resource_path,
                )?),
            )?,
        });
    }
    Ok(result)
}

fn owned_catalog(
    catalog: &Catalog,
    source_set: &str,
    resource_path: &str,
) -> Result<Catalog, ParseError> {
    let mut result = Catalog::new(FileFormat::Android);
    result.locale.clone_from(&catalog.locale);
    for (id, message) in &catalog.messages {
        let Some(metadata) = &message.metadata else {
            continue;
        };
        if metadata
            .get("androidOverlaySourceSet")
            .and_then(serde_json::Value::as_str)
            == Some(source_set)
            && metadata
                .get("androidResourcePath")
                .and_then(serde_json::Value::as_str)
                == Some(resource_path)
        {
            result.insert(id.clone(), message.clone())?;
        }
    }
    Ok(result)
}

fn selected_runtime_id(id: &str, winner: &crate::Message) -> String {
    let product = winner
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("androidProduct"))
        .and_then(serde_json::Value::as_str);
    match product {
        Some(value) if value != "default" => id.replace(&format!("@product={value}"), ""),
        _ => id.to_owned(),
    }
}

fn selected_product_owner(
    catalog: &Catalog,
    source: &crate::Message,
    runtime_id: &str,
    products: &[String],
) -> bool {
    let metadata = source.metadata.as_ref().unwrap();
    if metadata
        .get("androidFeatureFlagMode")
        .or_else(|| metadata.get("androidPathFeatureFlagMode"))
        .and_then(serde_json::Value::as_str)
        == Some("read_write")
    {
        return true;
    }
    let requested: HashSet<&str> = products
        .iter()
        .map(String::as_str)
        .filter(|product| products.len() == 1 || *product != "default")
        .collect();
    let product = metadata
        .get("androidProduct")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("default");
    if product != "default" {
        return requested.contains(product);
    }
    !catalog.messages.iter().any(|(id, alternative)| {
        let Some(candidate) = alternative
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("androidProduct"))
            .and_then(serde_json::Value::as_str)
        else {
            return false;
        };
        candidate != "default"
            && requested.contains(candidate)
            && id.replace(&format!("@product={candidate}"), "") == runtime_id
    })
}

fn invalid(message: impl Into<String>) -> ParseError {
    ParseError::new("INVALID_ANDROID_OVERLAY_SKELETON", message)
}
