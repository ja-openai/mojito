use crate::model::{Catalog, ParseError};
use crate::xml::{XmlElement, XmlNode};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

const METADATA: &str = "androidAttributeDependencies";
const STYLEABLE_METADATA: &str = "androidStyleableDependencies";
const AUTO_NAMESPACE: &str = "http://schemas.android.com/apk/res-auto";
const PUBLIC_NAMESPACE: &str = "http://schemas.android.com/apk/res/";
const PRIVATE_NAMESPACE: &str = "http://schemas.android.com/apk/prv/res/";
const FORMATS: [&str; 10] = [
    "reference",
    "string",
    "integer",
    "boolean",
    "color",
    "float",
    "dimension",
    "fraction",
    "enum",
    "flags",
];

pub(crate) struct Collected {
    attributes: BTreeMap<String, Value>,
    styleables: BTreeMap<String, Value>,
}

pub(crate) fn is_declaration(element: &XmlElement) -> bool {
    element.local_name() == "attr"
        || element.local_name() == "bag"
            && element
                .attribute("type")
                .is_some_and(|kind| trim_ascii(kind) == "attr")
}

pub(crate) fn is_styleable(element: &XmlElement) -> bool {
    element.local_name() == "declare-styleable"
        || element.local_name() == "bag"
            && element
                .attribute("type")
                .is_some_and(|kind| trim_ascii(kind) == "declare-styleable")
}

pub(crate) fn collect(
    root: &XmlElement,
    external: &BTreeMap<String, XmlElement>,
    external_styleables: &BTreeMap<String, XmlElement>,
    application_package: Option<&str>,
) -> Result<Collected, ParseError> {
    let mut definitions = BTreeMap::new();
    for (name, element) in external {
        definitions.insert(name.clone(), parse(element)?);
    }
    let mut styleables = BTreeMap::new();
    for (name, element) in external_styleables {
        styleables.insert(
            name.clone(),
            parse_styleable(element, &mut definitions, application_package)?,
        );
    }
    let mut local = BTreeSet::new();
    let mut local_styleables = BTreeSet::new();
    for element in root
        .elements()
        .filter(|element| element.namespace.is_none())
    {
        if is_styleable(element) {
            let name = crate::android::resource_name(element)?;
            if !local_styleables.insert(name.to_owned()) {
                return Err(error(
                    "DUPLICATE_ANDROID_STYLEABLE",
                    "Duplicate Android styleable declaration",
                ));
            }
            let group = parse_styleable(element, &mut definitions, application_package)?;
            if !external_styleables.contains_key(name) {
                styleables.insert(name.to_owned(), group);
            }
            continue;
        }
        if !is_declaration(element) {
            continue;
        }
        let definition = parse(element)?;
        let name = definition["name"].as_str().expect("attribute name");
        if !local.insert(name.to_owned()) {
            return Err(error(
                "DUPLICATE_ANDROID_ATTRIBUTE",
                "Duplicate Android attribute declaration",
            ));
        }
        if definitions.get(name).is_some_and(|previous| {
            !compatible(previous, &definition)
                && (!external.contains_key(name)
                    || previous.get("weak").and_then(Value::as_bool) == Some(true))
        }) {
            return Err(error(
                "DUPLICATE_ANDROID_ATTRIBUTE",
                "Conflicting Android attribute declaration",
            ));
        }
        if !external.contains_key(name)
            || definitions
                .get(name)
                .and_then(|previous| previous.get("weak"))
                .and_then(Value::as_bool)
                == Some(true)
        {
            definitions.insert(name.to_owned(), definition);
        }
    }
    Ok(Collected {
        attributes: definitions,
        styleables,
    })
}

fn parse(element: &XmlElement) -> Result<Value, ParseError> {
    parse_named(element, crate::android::resource_name(element)?)
}

fn parse_named(element: &XmlElement, name: &str) -> Result<Value, ParseError> {
    let mut definition = Map::new();
    definition.insert("name".into(), json!(name));
    if element.local_name() == "bag" {
        definition.insert("generic".into(), json!(true));
    }

    let mut formats = BTreeSet::new();
    if let Some(source) = element.attribute("format") {
        for part in source.split('|') {
            let value = trim_ascii(part);
            if !FORMATS.contains(&value) {
                return Err(error(
                    "INVALID_ANDROID_ATTRIBUTE_FORMAT",
                    "Invalid Android attribute format",
                ));
            }
            formats.insert(value.to_owned());
        }
    }

    for name in ["min", "max"] {
        if let Some(source) = element.attribute(name) {
            if !formats.contains("integer") {
                return Err(error(
                    "INVALID_ANDROID_ATTRIBUTE_BOUNDS",
                    "Android attribute bounds require the integer format",
                ));
            }
            definition.insert(name.into(), json!(integer(source, true)?));
        }
    }

    let mut symbols = BTreeMap::new();
    for node in &element.children {
        let XmlNode::Element(child) = node else {
            continue;
        };
        let kind = child.local_name();
        if child.namespace.is_some() || !matches!(kind, "enum" | "flag") {
            if child.namespace.is_none() && matches!(kind, "skip" | "eat-comment") {
                continue;
            }
            return Err(error(
                "INVALID_ANDROID_ATTRIBUTE_SYMBOL",
                "Invalid Android attribute child",
            ));
        }
        let opposite = if kind == "enum" { "flags" } else { "enum" };
        if formats.contains(opposite) {
            return Err(error(
                "INVALID_ANDROID_ATTRIBUTE_SYMBOL",
                "Android enum and flag symbols cannot mix",
            ));
        }
        formats.insert(if kind == "enum" { "enum" } else { "flags" }.into());
        let name = crate::android::resource_name(child)?;
        let value = child.attribute("value").ok_or_else(|| {
            error(
                "INVALID_ANDROID_ATTRIBUTE_SYMBOL",
                "Android attribute symbols require values",
            )
        })?;
        if symbols
            .insert(
                name.to_owned(),
                json!({"kind": kind, "name": name, "value": integer(value, false)?}),
            )
            .is_some()
        {
            return Err(error(
                "INVALID_ANDROID_ATTRIBUTE_SYMBOL",
                "Duplicate Android attribute symbol",
            ));
        }
    }
    if !formats.is_empty() {
        definition.insert(
            "format".into(),
            json!(FORMATS
                .into_iter()
                .filter(|format| formats.contains(*format))
                .collect::<Vec<_>>()
                .join("|")),
        );
    }
    if !symbols.is_empty() {
        definition.insert(
            "symbols".into(),
            json!(symbols.into_values().collect::<Vec<_>>()),
        );
    }
    Ok(Value::Object(definition))
}

fn parse_styleable(
    element: &XmlElement,
    definitions: &mut BTreeMap<String, Value>,
    application_package: Option<&str>,
) -> Result<Value, ParseError> {
    let mut group = Map::new();
    group.insert(
        "name".into(),
        json!(crate::android::resource_name(element)?),
    );
    if element.local_name() == "bag" {
        group.insert("generic".into(), json!(true));
    }
    let mut attributes = Vec::new();
    for child in element.elements() {
        if child.namespace.is_some() || child.local_name() != "attr" {
            if child.namespace.is_none() && matches!(child.local_name(), "skip" | "eat-comment") {
                continue;
            }
            return Err(error(
                "INVALID_ANDROID_STYLEABLE",
                "Invalid child in Android styleable declaration",
            ));
        }
        let raw = child.attribute("name").map(trim_ascii).unwrap_or_default();
        let name = styleable_attribute_name(child, raw, application_package)?;
        let definition = parse_named(child, &name)?;
        if !name.contains(':') && definition.get("format").is_some() {
            let mut weak = definition.as_object().expect("attribute object").clone();
            weak.insert("weak".into(), json!(true));
            let weak = Value::Object(weak);
            if let Some(previous) = definitions.get(&name) {
                if !compatible(previous, &weak) {
                    return Err(error(
                        "DUPLICATE_ANDROID_ATTRIBUTE",
                        "Conflicting Android attribute declaration",
                    ));
                }
            } else {
                definitions.insert(name, weak);
            }
        }
        attributes.push(definition);
    }
    if !attributes.is_empty() {
        group.insert("attributes".into(), json!(attributes));
    }
    Ok(Value::Object(group))
}

fn styleable_attribute_name(
    element: &XmlElement,
    name: &str,
    application_package: Option<&str>,
) -> Result<String, ParseError> {
    let name = name
        .strip_prefix('*')
        .filter(|name| name.contains(':'))
        .unwrap_or(name);
    let Some((prefix, entry)) = name.split_once(':') else {
        return valid_name(name)
            .then(|| name.to_owned())
            .ok_or_else(invalid_resource_name);
    };
    if prefix.is_empty() || !valid_name(entry) || entry.contains(':') {
        return Err(invalid_resource_name());
    }
    let namespace = element.namespace(prefix);
    let local = namespace == Some(AUTO_NAMESPACE)
        || application_package.is_some_and(|package| {
            prefix == package
                || namespace == Some(&format!("{PUBLIC_NAMESPACE}{package}"))
                || namespace == Some(&format!("{PRIVATE_NAMESPACE}{package}"))
        });
    Ok(if local {
        entry.to_owned()
    } else {
        format!("{prefix}:{entry}")
    })
}

fn compatible(left: &Value, right: &Value) -> bool {
    let Some(mut first) = left.as_object().cloned() else {
        return false;
    };
    let Some(mut second) = right.as_object().cloned() else {
        return false;
    };
    first.remove("weak");
    second.remove("weak");
    first.remove("generic");
    second.remove("generic");
    first == second
}

fn invalid_resource_name() -> ParseError {
    error(
        "INVALID_ANDROID_RESOURCE_NAME",
        "Android styleable attributes require valid resource names",
    )
}

fn integer(source: &str, bound: bool) -> Result<i32, ParseError> {
    let value = trim_ascii(source);
    let parsed = if let Some(hexadecimal) = value.strip_prefix("0x") {
        if hexadecimal.is_empty() || !hexadecimal.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            None
        } else {
            let significant = hexadecimal.trim_start_matches('0');
            (significant.len() <= 8)
                .then(|| {
                    u32::from_str_radix(
                        if significant.is_empty() {
                            "0"
                        } else {
                            significant
                        },
                        16,
                    )
                    .ok()
                })
                .flatten()
                .map(|number| number as i32)
        }
    } else {
        let (negative, digits) = value
            .strip_prefix('-')
            .map_or((false, value), |digits| (true, digits));
        if digits.is_empty() || !digits.bytes().all(|byte| byte.is_ascii_digit()) {
            None
        } else {
            let significant = digits.trim_start_matches('0');
            (significant.len() <= 10)
                .then(|| {
                    significant
                        .parse::<i64>()
                        .ok()
                        .or_else(|| significant.is_empty().then_some(0))
                })
                .flatten()
                .filter(|number| {
                    *number
                        <= if negative {
                            2_147_483_648
                        } else {
                            2_147_483_647
                        }
                })
                .map(|number| if negative { -number } else { number } as i32)
        }
    };
    parsed.ok_or_else(|| {
        error(
            if bound {
                "INVALID_ANDROID_ATTRIBUTE_BOUNDS"
            } else {
                "INVALID_ANDROID_ATTRIBUTE_SYMBOL"
            },
            "Android attribute values must be valid 32-bit integers",
        )
    })
}

pub(crate) fn attach(
    catalog: &mut Catalog,
    collected: &Collected,
    application_package: Option<&str>,
) {
    let definitions = &collected.attributes;
    if definitions.is_empty() {
        return;
    }
    for message in catalog.messages.values_mut() {
        let Some(metadata) = message.metadata.as_mut() else {
            continue;
        };
        let mut used = BTreeMap::new();
        for field in ["androidArrayReferences", "androidPluralReferences"] {
            let Some(references) = metadata.get(field).and_then(Value::as_object) else {
                continue;
            };
            for reference in references.values().filter_map(Value::as_str) {
                if let Some(name) = attribute_name(reference, application_package) {
                    if let Some(definition) = definitions.get(name) {
                        used.insert(name, definition.clone());
                    }
                }
            }
        }
        if !used.is_empty() {
            let groups = collected
                .styleables
                .values()
                .filter(|group| {
                    group
                        .get("attributes")
                        .and_then(Value::as_array)
                        .is_some_and(|attributes| {
                            attributes.iter().any(|attribute| {
                                attribute
                                    .get("name")
                                    .and_then(Value::as_str)
                                    .is_some_and(|name| used.contains_key(name))
                            })
                        })
                })
                .cloned()
                .collect::<Vec<_>>();
            metadata.insert(
                METADATA.into(),
                json!(used.into_values().collect::<Vec<_>>()),
            );
            if !groups.is_empty() {
                metadata.insert(STYLEABLE_METADATA.into(), json!(groups));
            }
        }
    }
}

fn attribute_name<'a>(source: &'a str, application_package: Option<&str>) -> Option<&'a str> {
    let source = trim_ascii(source);
    let theme = source.starts_with('?');
    let reference = source
        .strip_prefix('?')
        .or_else(|| source.strip_prefix('@'))?;
    let reference = reference.strip_prefix('*').unwrap_or(reference);
    let Some((kind, name)) = reference.split_once('/') else {
        return (theme && !reference.contains(':')).then_some(reference);
    };
    let kind = if let Some((package, kind)) = kind.split_once(':') {
        if application_package != Some(package) {
            return None;
        }
        kind
    } else {
        kind
    };
    (kind == "attr").then_some(name)
}

pub(crate) fn write(output: &mut String, catalog: &Catalog) -> Result<(), ParseError> {
    let mut definitions = BTreeMap::new();
    let mut styleables = BTreeMap::new();
    for message in catalog.messages.values() {
        let Some(metadata) = message.metadata.as_ref() else {
            continue;
        };
        if let Some(value) = metadata.get(METADATA) {
            let dependencies = value
                .as_array()
                .filter(|items| !items.is_empty())
                .ok_or_else(invalid)?;
            for dependency in dependencies {
                let normalized = validate(dependency, false)?;
                let name = normalized["name"]
                    .as_str()
                    .expect("validated name")
                    .to_owned();
                if let Some(previous) = definitions.insert(name, normalized.clone()) {
                    if previous != normalized {
                        return Err(invalid());
                    }
                }
            }
        }
        if let Some(value) = metadata.get(STYLEABLE_METADATA) {
            if !metadata.contains_key(METADATA) {
                return Err(invalid());
            }
            let groups = value
                .as_array()
                .filter(|groups| !groups.is_empty())
                .ok_or_else(invalid)?;
            for group in groups {
                let normalized = validate_styleable(group)?;
                let name = normalized["name"]
                    .as_str()
                    .expect("validated styleable name")
                    .to_owned();
                if let Some(previous) = styleables.insert(name, normalized.clone()) {
                    if previous != normalized {
                        return Err(invalid());
                    }
                }
            }
        }
    }

    for definition in definitions.into_values() {
        if definition.get("weak").and_then(Value::as_bool) == Some(true) {
            if !styleables.values().any(|group| {
                group["attributes"]
                    .as_array()
                    .expect("validated styleable attributes")
                    .iter()
                    .any(|attribute| attribute["name"] == definition["name"])
            }) {
                return Err(invalid());
            }
        } else {
            append_attribute(output, &definition, "  ");
        }
    }
    for group in styleables.into_values() {
        let generic = group.get("generic").and_then(Value::as_bool) == Some(true);
        output.push_str(if generic {
            "  <bag type=\"declare-styleable\" name=\""
        } else {
            "  <declare-styleable name=\""
        });
        output.push_str(&escape(group["name"].as_str().expect("styleable name")));
        output.push_str("\">\n");
        for attribute in group["attributes"]
            .as_array()
            .expect("validated styleable attributes")
        {
            append_attribute(output, attribute, "    ");
        }
        output.push_str(if generic {
            "  </bag>\n"
        } else {
            "  </declare-styleable>\n"
        });
    }
    Ok(())
}

fn append_attribute(output: &mut String, definition: &Value, indentation: &str) {
    let object = definition.as_object().expect("validated dependency");
    let generic = object.get("generic").and_then(Value::as_bool) == Some(true);
    output.push_str(indentation);
    output.push_str(if generic {
        "<bag type=\"attr\" name=\""
    } else {
        "<attr name=\""
    });
    output.push_str(&escape(object["name"].as_str().expect("attribute name")));
    output.push('"');
    for field in ["format", "min", "max"] {
        if let Some(value) = object.get(field) {
            output.push(' ');
            output.push_str(field);
            output.push_str("=\"");
            let value = value
                .as_str()
                .map_or_else(|| value.to_string(), str::to_owned);
            output.push_str(&escape(&value));
            output.push('"');
        }
    }
    let symbols = object.get("symbols").and_then(Value::as_array);
    if let Some(symbols) = symbols {
        output.push_str(">\n");
        for symbol in symbols {
            output.push_str(indentation);
            output.push_str("  <");
            output.push_str(symbol["kind"].as_str().expect("symbol kind"));
            output.push_str(" name=\"");
            output.push_str(&escape(symbol["name"].as_str().expect("symbol name")));
            output.push_str("\" value=\"");
            output.push_str(&symbol["value"].to_string());
            output.push_str("\" />\n");
        }
        output.push_str(indentation);
        output.push_str(if generic { "</bag>\n" } else { "</attr>\n" });
    } else {
        output.push_str(" />\n");
    }
}

fn validate_styleable(value: &Value) -> Result<Value, ParseError> {
    let object = value.as_object().ok_or_else(invalid)?;
    if !object
        .keys()
        .all(|key| matches!(key.as_str(), "name" | "generic" | "attributes"))
        || !object
            .get("name")
            .and_then(Value::as_str)
            .is_some_and(valid_name)
        || object.contains_key("generic") && object.get("generic") != Some(&json!(true))
    {
        return Err(invalid());
    }
    let attributes = object
        .get("attributes")
        .and_then(Value::as_array)
        .filter(|attributes| !attributes.is_empty())
        .ok_or_else(invalid)?;
    let mut normalized = Map::new();
    normalized.insert("name".into(), object["name"].clone());
    if object.contains_key("generic") {
        normalized.insert("generic".into(), json!(true));
    }
    let attributes = attributes
        .iter()
        .map(|attribute| {
            if attribute.get("weak").is_some() || attribute.get("generic").is_some() {
                Err(invalid())
            } else {
                validate(attribute, true)
            }
        })
        .collect::<Result<Vec<_>, _>>()?;
    normalized.insert("attributes".into(), json!(attributes));
    Ok(Value::Object(normalized))
}

fn validate(value: &Value, qualified_name: bool) -> Result<Value, ParseError> {
    let object = value.as_object().ok_or_else(invalid)?;
    if !object.keys().all(|key| {
        matches!(
            key.as_str(),
            "name" | "format" | "min" | "max" | "symbols" | "generic" | "weak"
        )
    }) || !object
        .get("name")
        .and_then(Value::as_str)
        .is_some_and(|name| {
            if qualified_name {
                valid_styleable_name(name)
            } else {
                valid_name(name)
            }
        })
        || object.contains_key("generic") && object.get("generic") != Some(&json!(true))
        || object.contains_key("weak")
            && (object.get("weak") != Some(&json!(true)) || object.contains_key("generic"))
    {
        return Err(invalid());
    }
    let mut normalized = Map::new();
    normalized.insert("name".into(), object["name"].clone());
    if object.contains_key("generic") {
        normalized.insert("generic".into(), json!(true));
    }
    if object.contains_key("weak") {
        normalized.insert("weak".into(), json!(true));
    }
    let mut formats = BTreeSet::new();
    if let Some(format) = object.get("format") {
        let source = format.as_str().ok_or_else(invalid)?;
        for item in source.split('|') {
            if !FORMATS.contains(&item) || !formats.insert(item) {
                return Err(invalid());
            }
        }
        let canonical = FORMATS
            .into_iter()
            .filter(|format| formats.contains(format))
            .collect::<Vec<_>>()
            .join("|");
        if canonical != source {
            return Err(invalid());
        }
        normalized.insert("format".into(), json!(source));
    }
    for bound in ["min", "max"] {
        if let Some(value) = object.get(bound) {
            let value = value
                .as_i64()
                .and_then(|value| i32::try_from(value).ok())
                .ok_or_else(invalid)?;
            if !formats.contains("integer") {
                return Err(invalid());
            }
            normalized.insert(bound.into(), json!(value));
        }
    }
    if let Some(values) = object.get("symbols") {
        let symbols = values
            .as_array()
            .filter(|values| !values.is_empty())
            .ok_or_else(invalid)?;
        let mut ordered = BTreeMap::new();
        let mut previous_kind = None;
        for symbol in symbols {
            let value = symbol.as_object().ok_or_else(invalid)?;
            if value.len() != 3
                || !value
                    .keys()
                    .all(|name| matches!(name.as_str(), "kind" | "name" | "value"))
            {
                return Err(invalid());
            }
            let kind = value
                .get("kind")
                .and_then(Value::as_str)
                .filter(|kind| matches!(*kind, "enum" | "flag"))
                .ok_or_else(invalid)?;
            let name = value
                .get("name")
                .and_then(Value::as_str)
                .filter(|name| valid_name(name))
                .ok_or_else(invalid)?;
            let number = value
                .get("value")
                .and_then(Value::as_i64)
                .and_then(|value| i32::try_from(value).ok())
                .ok_or_else(invalid)?;
            if previous_kind.is_some_and(|previous| previous != kind)
                || !formats.contains(if kind == "enum" { "enum" } else { "flags" })
                || ordered
                    .insert(name, json!({"kind": kind, "name": name, "value": number}))
                    .is_some()
            {
                return Err(invalid());
            }
            previous_kind = Some(kind);
        }
        normalized.insert(
            "symbols".into(),
            json!(ordered.into_values().collect::<Vec<_>>()),
        );
    }
    Ok(Value::Object(normalized))
}

fn valid_styleable_name(name: &str) -> bool {
    let Some((prefix, entry)) = name.split_once(':') else {
        return valid_name(name);
    };
    !entry.contains(':')
        && valid_name(entry)
        && !prefix.is_empty()
        && prefix.chars().enumerate().all(|(index, value)| {
            if index == 0 {
                value.is_ascii_alphabetic() || value == '_'
            } else {
                value.is_ascii_alphanumeric() || "_.-".contains(value)
            }
        })
}

fn valid_name(name: &str) -> bool {
    static PATTERN: OnceLock<regex::Regex> = OnceLock::new();
    let pattern = PATTERN.get_or_init(|| {
        regex::Regex::new(r"^(?:_|\p{XID_Start})[\p{XID_Continue}.\-]*$")
            .expect("valid Android attribute name pattern")
    });
    pattern.is_match(name)
        && name
            .chars()
            .all(|value| value <= '\u{ffff}' && !matches!(value, '\u{200c}' | '\u{200d}'))
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn invalid() -> ParseError {
    error(
        "INVALID_ANDROID_ATTRIBUTE_DEPENDENCY",
        "Invalid Android attribute dependency",
    )
}

fn error(code: &'static str, message: impl Into<String>) -> ParseError {
    ParseError::new(code, message)
}

fn trim_ascii(value: &str) -> &str {
    value.trim_matches(|character: char| character.is_ascii_whitespace())
}
