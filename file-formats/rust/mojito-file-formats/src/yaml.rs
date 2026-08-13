use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::source_skeleton::{SourceSkeleton, SourceSlot};
use crate::workflow::FilterOptions;
use serde_json::Map;
use std::collections::BTreeMap;
use yaml_rust2::parser::{Event, Parser};
use yaml_rust2::scanner::TScalarStyle;

#[derive(Debug)]
struct Scalar {
    path: String,
    value: String,
    start: usize,
    end: usize,
}

#[derive(Debug)]
struct Frame {
    path: String,
    key: Option<String>,
    sequence: bool,
    count: usize,
}

pub(crate) fn parse(source: &str) -> Result<Catalog, ParseError> {
    let options = FilterOptions::parse(FileFormat::Yaml, &[])?;
    parse_configured(source, &options)
}

pub(crate) fn parse_configured_bytes(
    source: &[u8],
    options: &FilterOptions,
) -> Result<Catalog, ParseError> {
    let bytes = source.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(source);
    let text = std::str::from_utf8(bytes)
        .map_err(|_| error("INVALID_ENCODING", "Invalid UTF-8 YAML source"))?;
    parse_configured(text, options)
}

pub(crate) fn parse_configured(
    source: &str,
    options: &FilterOptions,
) -> Result<Catalog, ParseError> {
    let mut result = Catalog::new(FileFormat::Yaml);
    for scalar in scalars(source)? {
        let exception = options
            .pattern("exceptions")
            .is_some_and(|pattern| pattern.is_match(&scalar.path));
        let all = !options.contains("extractAllPairs") || options.enabled("extractAllPairs");
        if all != exception {
            let id = if options.contains("useFullKeyPath") && !options.enabled("useFullKeyPath") {
                scalar.path.rsplit('/').next().unwrap().to_owned()
            } else {
                scalar.path
            };
            result.insert(
                id,
                Message::new(scalar.value, None, None, vec![], Map::new()),
            )?;
        }
    }
    Ok(result)
}

pub(crate) fn extract(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    let (bom, source) = if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
        (3, std::str::from_utf8(&bytes[3..]))
    } else {
        (0, std::str::from_utf8(bytes))
    };
    let source = source.map_err(|_| error("INVALID_ENCODING", "Invalid UTF-8 YAML source"))?;
    let mut slots = Vec::new();
    for scalar in scalars(source)? {
        slots.push(SourceSlot {
            id: scalar.path,
            selector: None,
            variant: None,
            start: scalar.start + bom,
            end: scalar.end + bom,
            apple_object_index: None,
        });
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::Yaml.id(),
        encoding: if bom == 0 { "UTF-8" } else { "UTF-8-BOM" }.to_owned(),
        source: source.to_owned(),
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: None,
        slots,
    })
}

pub(crate) fn render(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    if translations
        .keys()
        .any(|key| !skeleton.slots.iter().any(|slot| slot.id == *key))
    {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no YAML source slot",
        ));
    }
    let bom = if skeleton.encoding == "UTF-8-BOM" {
        3
    } else {
        0
    };
    let mut original = if bom == 0 {
        Vec::new()
    } else {
        vec![0xef, 0xbb, 0xbf]
    };
    original.extend_from_slice(skeleton.source.as_bytes());
    let mut result = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error("INVALID_SKELETON", "Invalid YAML source-slot range"));
        }
        result.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.id) {
            let source = std::str::from_utf8(&original[slot.start..slot.end])
                .map_err(|_| error("INVALID_SKELETON", "Invalid UTF-8 YAML source slot"))?;
            result.extend_from_slice(encode_scalar(source, translation).as_bytes());
        } else {
            result.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    result.extend_from_slice(&original[previous..]);
    Ok(result)
}

fn encode_scalar(source: &str, translated: &str) -> String {
    if source.starts_with('\'') {
        format!("'{}'", translated.replace('\'', "''"))
    } else if source.starts_with('"') {
        format!(
            "\"{}\"",
            translated
                .replace('\\', "\\\\")
                .replace('"', "\\\"")
                .replace('\n', "\\n")
                .replace('\r', "\\r")
                .replace('\t', "\\t")
        )
    } else if source.starts_with('|') || source.starts_with('>') {
        let newline = source.find('\n').unwrap_or(source.len());
        let body = &source[newline.saturating_add(1)..];
        let indentation = body.len() - body.trim_start_matches(' ').len();
        let indent = " ".repeat(indentation);
        let separator = if source.contains("\r\n") {
            "\r\n"
        } else {
            "\n"
        };
        format!(
            "{}{}{}",
            &source[..newline.saturating_add(1)],
            indent,
            translated.replace('\n', &format!("{separator}{indent}"))
        )
    } else if translated.contains('\n') || translated.contains(": ") || translated.contains(" #") {
        format!("'{}'", translated.replace('\'', "''"))
    } else {
        translated.to_owned()
    }
}

fn scalars(source: &str) -> Result<Vec<Scalar>, ParseError> {
    let mut parser = Parser::new_from_str(source);
    let mut stack = Vec::<Frame>::new();
    let mut result = Vec::new();
    loop {
        let (event, marker) = parser
            .next_token()
            .map_err(|_| error("INVALID_YAML", "Invalid YAML localization source"))?;
        match event {
            Event::StreamEnd => break,
            Event::MappingStart(_, _) => {
                let path = next_path(&mut stack)?;
                stack.push(Frame {
                    path,
                    key: None,
                    sequence: false,
                    count: 0,
                });
            }
            Event::MappingEnd | Event::SequenceEnd => {
                stack.pop();
            }
            Event::SequenceStart(_, _) => {
                let path = next_path(&mut stack)?;
                stack.push(Frame {
                    path,
                    key: None,
                    sequence: true,
                    count: 0,
                });
            }
            Event::Scalar(value, style, _, _) => {
                let current = stack
                    .last_mut()
                    .ok_or_else(|| error("INVALID_YAML", "YAML source must contain a mapping"))?;
                if !current.sequence && current.key.is_none() {
                    current.key = Some(value);
                    continue;
                }
                let path = if current.sequence {
                    current.count += 1;
                    if current.count > 1 {
                        return Err(error(
                            "UNSUPPORTED_YAML_SEQUENCE",
                            "Repeated YAML sequence keys are ambiguous",
                        ));
                    }
                    current.path.clone()
                } else {
                    let key = current.key.take().unwrap();
                    if current.path.is_empty() {
                        key
                    } else {
                        format!("{}/{}", current.path, key)
                    }
                };
                let marked = source
                    .char_indices()
                    .nth(marker.index())
                    .map_or(source.len(), |(index, _)| index);
                let start = if matches!(style, TScalarStyle::Literal | TScalarStyle::Folded) {
                    source[..marked]
                        .rfind(['|', '>'])
                        .filter(|index| source[*index..marked].contains('\n'))
                        .unwrap_or(marked)
                } else {
                    marked
                };
                let end = scalar_end(source, start, style)?;
                result.push(Scalar {
                    path,
                    value,
                    start,
                    end,
                });
            }
            Event::Alias(_) => {
                return Err(error(
                    "UNSUPPORTED_YAML_ALIAS",
                    "YAML aliases are unsupported",
                ));
            }
            _ => {}
        }
    }
    if result.is_empty() {
        return Err(error(
            "INVALID_YAML",
            "YAML localization source must contain scalar values",
        ));
    }
    Ok(result)
}

fn next_path(stack: &mut [Frame]) -> Result<String, ParseError> {
    let Some(parent) = stack.last_mut() else {
        return Ok(String::new());
    };
    if parent.sequence {
        parent.count += 1;
        if parent.count > 1 {
            return Err(error(
                "UNSUPPORTED_YAML_SEQUENCE",
                "Repeated YAML sequence keys are ambiguous",
            ));
        }
        Ok(parent.path.clone())
    } else if let Some(key) = parent.key.take() {
        Ok(if parent.path.is_empty() {
            key
        } else {
            format!("{}/{}", parent.path, key)
        })
    } else {
        Err(error(
            "INVALID_YAML",
            "YAML collection requires a mapping key",
        ))
    }
}

fn scalar_end(source: &str, start: usize, style: TScalarStyle) -> Result<usize, ParseError> {
    let bytes = source.as_bytes();
    match style {
        TScalarStyle::SingleQuoted => {
            let mut index = start + 1;
            while index < bytes.len() {
                if bytes[index] == b'\'' {
                    if bytes.get(index + 1) == Some(&b'\'') {
                        index += 2;
                    } else {
                        return Ok(index + 1);
                    }
                } else {
                    index += 1;
                }
            }
        }
        TScalarStyle::DoubleQuoted => {
            let mut index = start + 1;
            let mut escaped = false;
            while index < bytes.len() {
                if bytes[index] == b'"' && !escaped {
                    return Ok(index + 1);
                }
                escaped = bytes[index] == b'\\' && !escaped;
                index += 1;
            }
        }
        TScalarStyle::Plain => {
            let tail = &source[start..];
            let line = tail.find(['\r', '\n']).unwrap_or(tail.len());
            let comment = tail[..line].find(" #").unwrap_or(line);
            return Ok(start + tail[..comment].trim_end().len());
        }
        TScalarStyle::Literal | TScalarStyle::Folded => {
            let first_newline = source[start..]
                .find('\n')
                .ok_or_else(|| error("INVALID_YAML", "Invalid YAML block scalar"))?
                + start;
            let body_start = first_newline + 1;
            let indentation = source[body_start..]
                .chars()
                .take_while(|value| *value == ' ')
                .count();
            let mut previous = body_start;
            let mut current = body_start;
            while current < source.len() {
                let end = source[current..]
                    .find('\n')
                    .map_or(source.len(), |offset| current + offset + 1);
                let line = source[current..end].trim_end_matches(['\r', '\n']);
                let spaces = line.chars().take_while(|value| *value == ' ').count();
                if !line.is_empty() && spaces < indentation {
                    break;
                }
                previous = end;
                current = end;
            }
            while previous > body_start && matches!(bytes[previous - 1], b'\r' | b'\n') {
                previous -= 1;
            }
            return Ok(previous);
        }
    }
    Err(error("INVALID_YAML", "Unterminated YAML scalar"))
}

fn error(code: &'static str, message: &str) -> ParseError {
    ParseError::new(code, message)
}
