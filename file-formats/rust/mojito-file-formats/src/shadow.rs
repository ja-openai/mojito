use crate::model::{Catalog, FileFormat, Message};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::{BTreeMap, BTreeSet};

const CATEGORIES: [&str; 6] = ["zero", "one", "two", "few", "many", "other"];

/// Native legacy extraction unit supplied by a caller; Rust never invokes or links Okapi.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyTextUnit {
    pub name: String,
    pub source: String,
    #[serde(default)]
    pub comments: Option<String>,
    #[serde(default)]
    pub plural_form: Option<String>,
    #[serde(default)]
    pub plural_form_other: Option<String>,
    #[serde(default)]
    pub usages: Vec<String>,
}

/// Stable comparison category and ID; actual source text never enters the report.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShadowDifference {
    pub category: String,
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub canonical_ids: Option<Vec<String>>,
}

/// Portable migration comparison shared byte-for-byte with the integrated Java implementation.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShadowReport {
    pub source_format: &'static str,
    pub canonical_units: usize,
    pub legacy_units: usize,
    pub outcome: &'static str,
    pub differences: Vec<ShadowDifference>,
}

#[derive(Clone)]
struct Unit {
    name: String,
    source: String,
    comments: Option<String>,
    plural_form: Option<String>,
    plural_form_other: Option<String>,
    usages: Vec<String>,
    canonical_id: Option<String>,
}

pub fn compare(catalog: &Catalog, extracted: &[LegacyTextUnit]) -> ShadowReport {
    let canonical = project(catalog);
    let expected = group(&canonical);
    let legacy: Vec<_> = extracted
        .iter()
        .map(|unit| {
            let mut usages = unit.usages.clone();
            usages.sort();
            Unit {
                name: unit.name.clone(),
                source: unit.source.clone(),
                comments: unit.comments.clone(),
                plural_form: unit.plural_form.clone(),
                plural_form_other: unit.plural_form_other.clone(),
                usages,
                canonical_id: None,
            }
        })
        .collect();
    let observed = group(&legacy);
    let ids: BTreeSet<_> = expected.keys().chain(observed.keys()).collect();
    let mut differences = Vec::new();
    for id in ids {
        let current = expected.get(id).map(Vec::as_slice).unwrap_or_default();
        let previous = observed.get(id).map(Vec::as_slice).unwrap_or_default();
        if current.len() > 1 {
            let mut canonical_ids: Vec<_> = current
                .iter()
                .map(|unit| unit.canonical_id.clone().expect("canonical unit identity"))
                .collect();
            canonical_ids.sort();
            differences.push(ShadowDifference {
                category: "legacy_projection_collision".into(),
                id: (*id).clone(),
                count: Some(current.len()),
                canonical_ids: Some(canonical_ids),
            });
        }
        if previous.len() > 1 {
            differences.push(difference("duplicate_legacy", id, Some(previous.len())));
        }
        if current.is_empty() {
            differences.push(difference("unexpected_legacy", id, Some(previous.len())));
        } else if previous.is_empty() {
            differences.push(difference("missing_legacy", id, Some(current.len())));
        } else if current.len() == 1 && previous.len() == 1 {
            let present = current[0];
            let actual = previous[0];
            if present.source != actual.source {
                differences.push(difference("source_mismatch", id, None));
            }
            if present.comments != actual.comments {
                differences.push(difference("comment_mismatch", id, None));
            }
            if present.plural_form != actual.plural_form
                || present.plural_form_other != actual.plural_form_other
            {
                differences.push(difference("plural_mismatch", id, None));
            }
            if present.usages != actual.usages {
                differences.push(difference("usage_mismatch", id, None));
            }
        }
    }
    differences.sort_by(|left, right| {
        left.category
            .cmp(&right.category)
            .then_with(|| left.id.cmp(&right.id))
    });
    ShadowReport {
        source_format: catalog.source_format,
        canonical_units: canonical.len(),
        legacy_units: legacy.len(),
        outcome: if differences.is_empty() {
            "match"
        } else {
            "mismatch"
        },
        differences,
    }
}

fn difference(category: &str, id: &str, count: Option<usize>) -> ShadowDifference {
    ShadowDifference {
        category: category.to_owned(),
        id: id.to_owned(),
        count,
        canonical_ids: None,
    }
}

fn group(units: &[Unit]) -> BTreeMap<String, Vec<&Unit>> {
    let mut grouped = BTreeMap::new();
    for unit in units {
        grouped
            .entry(unit.name.clone())
            .or_insert_with(Vec::new)
            .push(unit);
    }
    grouped
}

fn project(catalog: &Catalog) -> Vec<Unit> {
    let mut projected = Vec::new();
    for (id, message) in &catalog.messages {
        let empty = Map::new();
        let metadata = message.metadata.as_ref().unwrap_or(&empty);
        let usages = metadata
            .get("references")
            .and_then(Value::as_array)
            .map(|references| {
                let mut usages: Vec<_> = references
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_owned)
                    .collect();
                usages.sort();
                usages
            })
            .unwrap_or_default();
        if catalog.source_format == FileFormat::AppleStringsdict.id() {
            if let (Some(variables), Some(rules)) = (
                metadata.get("pluralVariables").and_then(Value::as_array),
                metadata.get("applePluralRules").and_then(Value::as_object),
            ) {
                for selector in variables.iter().filter_map(Value::as_str) {
                    let Some(variants) = rules
                        .get(selector)
                        .and_then(Value::as_object)
                        .and_then(|rule| rule.get("variants"))
                        .and_then(Value::as_object)
                    else {
                        continue;
                    };
                    let Some(fallback) = variants.get("other").and_then(Value::as_str) else {
                        continue;
                    };
                    let base = format!("{id}_{selector}_");
                    for category in CATEGORIES {
                        let source = variants
                            .get(category)
                            .and_then(Value::as_str)
                            .unwrap_or(fallback);
                        projected.push(Unit {
                            name: format!("{base}{category}"),
                            source: restore(source, message, catalog.source_format, metadata),
                            comments: message.description.clone(),
                            plural_form: Some(category.to_owned()),
                            plural_form_other: Some(format!("{base}other")),
                            usages: usages.clone(),
                            canonical_id: Some(format!("{id}#{selector}#{category}")),
                        });
                    }
                }
                continue;
            }
        }
        if let Some(variants) = &message.variants {
            let base = if catalog.source_format == FileFormat::GettextPo.id() {
                format!(
                    "{} _",
                    gettext_id(
                        metadata
                            .get("sourceMessage")
                            .and_then(Value::as_str)
                            .unwrap_or(id),
                        metadata
                    )
                )
            } else if catalog.source_format == FileFormat::AppleStringsdict.id() {
                format!(
                    "{}_{}_",
                    id,
                    metadata
                        .get("pluralVariable")
                        .and_then(Value::as_str)
                        .unwrap_or("count")
                )
            } else {
                format!(
                    "{}_",
                    id.split("@product=")
                        .next()
                        .unwrap_or(id)
                        .split("@flag=")
                        .next()
                        .unwrap_or(id)
                )
            };
            let fallback = variants.get("other").expect("valid canonical plural");
            for category in CATEGORIES {
                let source = if catalog.source_format == FileFormat::GettextPo.id() {
                    metadata
                        .get(if category == "one" {
                            "sourceMessage"
                        } else {
                            "sourcePlural"
                        })
                        .and_then(Value::as_str)
                        .unwrap_or(fallback)
                        .to_owned()
                } else {
                    restore(
                        variants.get(category).unwrap_or(fallback),
                        message,
                        catalog.source_format,
                        metadata,
                    )
                };
                projected.push(Unit {
                    name: format!("{base}{category}"),
                    source,
                    comments: message.description.clone(),
                    plural_form: Some(category.to_owned()),
                    plural_form_other: Some(format!("{base}other")),
                    usages: usages.clone(),
                    canonical_id: Some(format!("{id}#{category}")),
                });
            }
            continue;
        }

        let mut name = id.clone();
        if catalog.source_format == FileFormat::Android.id() {
            if let Some(product) = name.find("@product=") {
                let start = product + "@product=".len();
                let suffix = name[start..].find(['@', '[']).map(|index| start + index);
                name = format!(
                    "{}{}",
                    &name[..product],
                    suffix.map_or("", |index| &name[index..])
                );
            }
            if let Some(flag) = name.find("@flag=") {
                let suffix = name[flag..].find('[').map(|index| flag + index);
                name = format!(
                    "{}{}",
                    &name[..flag],
                    suffix.map_or("", |index| &name[index..])
                );
            }
            if let Some((prefix, rest)) = name.rsplit_once('[') {
                if let Some(index) = rest.strip_suffix(']') {
                    if index.bytes().all(|byte| byte.is_ascii_digit()) {
                        name = format!("{prefix}_{index}");
                    }
                }
            }
        }
        let source = if catalog.source_format == FileFormat::GettextPo.id() {
            metadata
                .get("sourceMessage")
                .and_then(Value::as_str)
                .unwrap_or(&name)
                .to_owned()
        } else {
            restore(
                &message.default_message,
                message,
                catalog.source_format,
                metadata,
            )
        };
        if catalog.source_format == FileFormat::GettextPo.id() {
            name = gettext_id(&source, metadata);
        }
        projected.push(Unit {
            name,
            source,
            comments: message.description.clone(),
            plural_form: None,
            plural_form_other: None,
            usages,
            canonical_id: Some(id.clone()),
        });
    }
    projected
}

fn gettext_id(source: &str, metadata: &Map<String, Value>) -> String {
    metadata.get("context").and_then(Value::as_str).map_or_else(
        || source.to_owned(),
        |context| format!("{source} --- {context}"),
    )
}

fn restore(
    canonical: &str,
    message: &Message,
    format: &str,
    metadata: &Map<String, Value>,
) -> String {
    let mut source = if metadata
        .get("androidMarkupEscaping")
        .or_else(|| metadata.get("appleMarkupEscaping"))
        .and_then(Value::as_str)
        == Some("icu-quoted-angle")
    {
        canonical.replace("'<'", "<").replace("''", "'")
    } else {
        canonical.to_owned()
    };
    if format == FileFormat::Android.id()
        && metadata.get("formatted").and_then(Value::as_bool) != Some(false)
    {
        source = source.replace('%', "%%");
    }
    let Some(placeholders) = &message.placeholders else {
        return source;
    };
    let mut positions: BTreeMap<&str, usize> = BTreeMap::new();
    let mut result = String::new();
    let bytes = source.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] != b'{' {
            let character = source[index..].chars().next().expect("character boundary");
            result.push(character);
            index += character.len_utf8();
            continue;
        }
        let Some(end) = source[index + 1..].find('}') else {
            result.push('{');
            index += 1;
            continue;
        };
        let end = index + 1 + end;
        let name = &source[index + 1..end];
        let options: Vec<_> = placeholders
            .iter()
            .filter(|placeholder| placeholder.name == name)
            .collect();
        if options.is_empty() {
            result.push('{');
            index += 1;
            continue;
        }
        let position = positions.entry(name).or_default();
        result.push_str(options[(*position).min(options.len() - 1)].source.as_str());
        *position += 1;
        index = end + 1;
    }
    result
}
