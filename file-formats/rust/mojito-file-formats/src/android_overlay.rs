use crate::android_configuration::Configuration;
use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::xml::{XmlElement, XmlNode};
use crate::{decode, xml, AndroidResourceInput};
use serde_json::json;
use std::collections::BTreeMap;

fn decode_source(input: &AndroidResourceInput<'_>) -> Result<String, ParseError> {
    decode(
        input.source,
        crate::xml_encoding(FileFormat::Android, input.source)?,
    )
}

struct Layer<'a> {
    source_set: &'a str,
    priority: u8,
    catalog: Catalog,
    resources: Vec<ResourceIdentity>,
}

struct Winner<'a> {
    priority: u8,
    source_set: &'a str,
    messages: Vec<(String, Message)>,
}

struct MacroWinner {
    priority: u8,
    element: XmlElement,
}

#[derive(Clone, Eq, Ord, PartialEq, PartialOrd)]
struct ResourceIdentity {
    kind: String,
    name: String,
    product: String,
    runtime_flag: Option<String>,
}

pub(crate) fn parse(
    inputs: &[AndroidResourceInput<'_>],
    feature_flags: &BTreeMap<String, bool>,
    application_package: Option<&str>,
) -> Result<Catalog, ParseError> {
    if inputs.is_empty() {
        return Err(ParseError::new(
            "EMPTY_ANDROID_OVERLAY",
            "An Android overlay requires at least one resource source",
        ));
    }

    let macros = macros(inputs)?;
    let attributes = attributes(inputs)?;
    let styleables = styleables(inputs)?;
    let mut layers = Vec::with_capacity(inputs.len());
    let mut expected_configuration = None;
    let mut locale = None;
    for input in inputs {
        let priority = priority(input.source_set)?;
        let configuration = Configuration::parse(input.resource_path)?;
        let key = configuration.effective_key();
        let catalog = crate::android::parse_with_macros(
            &decode_source(input)?,
            Some(input.resource_path),
            feature_flags,
            &macros,
            &attributes,
            &styleables,
            application_package,
        )?;
        match &expected_configuration {
            None => {
                expected_configuration = Some(key);
                locale.clone_from(&catalog.locale);
            }
            Some(expected) if expected == &key && locale == catalog.locale => {}
            Some(_) => {
                return Err(ParseError::new(
                    "ANDROID_OVERLAY_CONFIGURATION_MISMATCH",
                    "Android overlays must share one effective resource configuration",
                ));
            }
        }
        let root = xml::parse(&decode_source(input)?)?;
        layers.push(Layer {
            source_set: input.source_set,
            priority,
            catalog,
            resources: declarations(
                &root,
                feature_flags,
                configuration.path_feature_flag.as_deref(),
            )?,
        });
    }
    layers.sort_by_key(|layer| layer.priority);

    let mut winners: BTreeMap<ResourceIdentity, Winner<'_>> = BTreeMap::new();
    for layer in &layers {
        for resource in &layer.resources {
            if winners
                .get(resource)
                .is_some_and(|previous| previous.priority == layer.priority)
            {
                if resource.runtime_flag.is_some() {
                    continue;
                }
                return Err(ParseError::new(
                    "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
                    format!("Same-priority Android resource conflict: {}", resource.name),
                ));
            }
            let messages = layer
                .catalog
                .messages
                .iter()
                .filter(|(id, message)| resource.contains(id, message))
                .map(|(id, message)| (id.clone(), message.clone()))
                .collect();
            winners.insert(
                resource.clone(),
                Winner {
                    priority: layer.priority,
                    source_set: layer.source_set,
                    messages,
                },
            );
        }
    }

    let mut merged = Catalog::new(FileFormat::Android);
    merged.locale = locale;
    for winner in winners.into_values() {
        for (id, mut message) in winner.messages {
            message
                .metadata
                .get_or_insert_default()
                .insert("androidOverlaySourceSet".into(), json!(winner.source_set));
            merged.insert(id, message)?;
        }
    }
    Ok(merged)
}

fn macros(inputs: &[AndroidResourceInput<'_>]) -> Result<BTreeMap<String, XmlElement>, ParseError> {
    let mut winners: BTreeMap<String, MacroWinner> = BTreeMap::new();
    for input in inputs {
        let priority = priority(input.source_set)?;
        let root = xml::parse(&decode_source(input)?)?;
        for child in &root.children {
            let XmlNode::Element(element) = child else {
                continue;
            };
            if element.namespace.is_some() || !crate::android::is_macro_declaration(element) {
                continue;
            }
            let name = crate::android::resource_name(element)?;
            if winners
                .get(name)
                .is_some_and(|previous| previous.priority == priority)
            {
                return Err(ParseError::new(
                    "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
                    format!("Same-priority Android resource conflict: {name}"),
                ));
            }
            if winners
                .get(name)
                .is_none_or(|previous| previous.priority < priority)
            {
                winners.insert(
                    name.to_owned(),
                    MacroWinner {
                        priority,
                        element: element.clone(),
                    },
                );
            }
        }
    }
    Ok(winners
        .into_iter()
        .map(|(name, winner)| (name, winner.element))
        .collect())
}

pub(crate) fn macro_owners(
    inputs: &[AndroidResourceInput<'_>],
) -> Result<BTreeMap<String, crate::AndroidOverlayMacroOwner>, ParseError> {
    let mut winners: BTreeMap<String, (u8, crate::AndroidOverlayMacroOwner)> = BTreeMap::new();
    for input in inputs {
        let priority = priority(input.source_set)?;
        let root = xml::parse(&decode_source(input)?)?;
        for element in root.elements().filter(|element| {
            element.namespace.is_none() && crate::android::is_macro_declaration(element)
        }) {
            let name = crate::android::resource_name(element)?;
            if winners
                .get(name)
                .is_none_or(|(previous, _)| *previous < priority)
            {
                winners.insert(
                    name.to_owned(),
                    (
                        priority,
                        crate::AndroidOverlayMacroOwner {
                            source_set: input.source_set.to_owned(),
                            resource_path: input.resource_path.to_owned(),
                        },
                    ),
                );
            }
        }
    }
    Ok(winners
        .into_iter()
        .map(|(name, (_, owner))| (name, owner))
        .collect())
}

fn attributes(
    inputs: &[AndroidResourceInput<'_>],
) -> Result<BTreeMap<String, XmlElement>, ParseError> {
    let mut winners: BTreeMap<String, MacroWinner> = BTreeMap::new();
    for input in inputs {
        let priority = priority(input.source_set)?;
        let root = xml::parse(&decode_source(input)?)?;
        for element in root.elements().filter(|element| {
            element.namespace.is_none() && crate::android_attributes::is_declaration(element)
        }) {
            let name = crate::android::resource_name(element)?;
            if winners
                .get(name)
                .is_some_and(|previous| previous.priority == priority)
            {
                return Err(ParseError::new(
                    "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
                    format!("Same-priority Android resource conflict: {name}"),
                ));
            }
            if winners
                .get(name)
                .is_none_or(|previous| previous.priority < priority)
            {
                winners.insert(
                    name.to_owned(),
                    MacroWinner {
                        priority,
                        element: element.clone(),
                    },
                );
            }
        }
    }
    Ok(winners
        .into_iter()
        .map(|(name, winner)| (name, winner.element))
        .collect())
}

fn styleables(
    inputs: &[AndroidResourceInput<'_>],
) -> Result<BTreeMap<String, XmlElement>, ParseError> {
    let mut winners: BTreeMap<String, MacroWinner> = BTreeMap::new();
    for input in inputs {
        let priority = priority(input.source_set)?;
        let root = xml::parse(&decode_source(input)?)?;
        for element in root.elements().filter(|element| {
            element.namespace.is_none() && crate::android_attributes::is_styleable(element)
        }) {
            let name = crate::android::resource_name(element)?;
            if winners
                .get(name)
                .is_some_and(|previous| previous.priority == priority)
            {
                return Err(ParseError::new(
                    "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
                    format!("Same-priority Android resource conflict: {name}"),
                ));
            }
            if winners
                .get(name)
                .is_none_or(|previous| previous.priority < priority)
            {
                winners.insert(
                    name.to_owned(),
                    MacroWinner {
                        priority,
                        element: element.clone(),
                    },
                );
            }
        }
    }
    Ok(winners
        .into_iter()
        .map(|(name, winner)| (name, winner.element))
        .collect())
}

fn priority(source_set: &str) -> Result<u8, ParseError> {
    match source_set {
        "library" => Ok(0),
        "main" => Ok(1),
        "product_flavor" => Ok(2),
        "build_type" => Ok(3),
        "build_variant" => Ok(4),
        _ => Err(ParseError::new(
            "INVALID_ANDROID_OVERLAY_SOURCE_SET",
            "Unsupported Android overlay source-set priority",
        )),
    }
}

fn declarations(
    root: &xml::XmlElement,
    feature_flags: &BTreeMap<String, bool>,
    path_feature_flag: Option<&str>,
) -> Result<Vec<ResourceIdentity>, ParseError> {
    root.elements()
        .filter(|element| element.namespace.is_none())
        .filter_map(|element| {
            match crate::android::feature_enabled_with_path(
                element,
                feature_flags,
                path_feature_flag,
            ) {
                Ok(false) => return None,
                Err(error) => return Some(Err(error)),
                Ok(true) => {}
            }
            let declaration = if element.local_name() == "bag" {
                element
                    .attribute("type")
                    .unwrap_or_default()
                    .trim_matches(|value: char| value.is_ascii_whitespace())
            } else {
                element.local_name()
            };
            let kind = match declaration {
                "string" => "string",
                "item"
                    if element.attribute("type").map(|value| {
                        value.trim_matches(|character: char| character.is_ascii_whitespace())
                    }) == Some("string") =>
                {
                    "string"
                }
                "array" | "integer-array" | "string-array" => "array",
                "plurals" => "plurals",
                _ => return None,
            };
            let product = match crate::android::resource_product(element) {
                "" | "default" => "default",
                value => value,
            };
            Some(Ok(ResourceIdentity {
                kind: kind.to_owned(),
                name: match crate::android::resource_name(element) {
                    Ok(name) => name.to_owned(),
                    Err(error) => return Some(Err(error)),
                },
                product: product.to_owned(),
                runtime_flag: match crate::android::runtime_feature_flag_with_path(
                    element,
                    feature_flags,
                    path_feature_flag,
                ) {
                    Ok(flag) => flag,
                    Err(error) => return Some(Err(error)),
                },
            }))
        })
        .collect()
}

impl ResourceIdentity {
    fn contains(&self, id: &str, message: &Message) -> bool {
        let base = if self.product == "default" {
            self.name.clone()
        } else {
            format!("{}@product={}", self.name, self.product)
        };
        let base = self
            .runtime_flag
            .as_ref()
            .map_or(base.clone(), |flag| format!("{base}@flag={flag}"));
        if self.kind != "array" {
            return base == id;
        }
        id.starts_with(&format!("{base}["))
            && message
                .metadata
                .as_ref()
                .and_then(|metadata| metadata.get("arrayName"))
                .and_then(serde_json::Value::as_str)
                == Some(self.name.as_str())
    }
}
