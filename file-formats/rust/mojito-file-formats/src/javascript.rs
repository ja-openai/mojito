use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::source_skeleton::{Encoding, SourceSkeleton, SourceSlot};
use serde_json::{Map, Value};
use std::collections::{BTreeMap, BTreeSet, HashMap};

struct Entry<'a> {
    id: &'a str,
    value: &'a str,
    description: Option<String>,
    template: bool,
    start: usize,
    end: usize,
}

pub(crate) fn parse(format: FileFormat, source: &str) -> Result<Catalog, ParseError> {
    let mut catalog = Catalog::new(format);
    for entry in entries(source)? {
        catalog.insert(entry.id.to_owned(), message(&entry))?;
    }
    Ok(catalog)
}

pub(crate) fn extract(format: FileFormat, bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    let encoding = Encoding::detect(bytes);
    let source = crate::decode(bytes, None)?;
    let mut catalog = Catalog::new(format);
    let mut slots = Vec::new();
    for entry in entries(&source)? {
        catalog.insert(entry.id.to_owned(), message(&entry))?;
        slots.push(SourceSlot {
            id: entry.id.to_owned(),
            selector: None,
            variant: None,
            start: encoding.offset(&source, entry.start),
            end: encoding.offset(&source, entry.end),
            apple_object_index: None,
        });
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: format.id(),
        encoding: encoding.name().to_owned(),
        source,
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
    if skeleton.schema_version != 1 {
        return Err(invalid_skeleton(
            "Unsupported JavaScript source skeleton version",
        ));
    }
    let format = FileFormat::from_id(skeleton.source_format)
        .filter(|format| matches!(format, FileFormat::JavaScript | FileFormat::TypeScript))
        .ok_or_else(|| invalid_skeleton("Unsupported JavaScript source skeleton format"))?;
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    if extract(format, &original)?.slots != skeleton.slots {
        return Err(invalid_skeleton(
            "JavaScript source slots do not own their original values",
        ));
    }
    let entries = entries(&skeleton.source)?
        .into_iter()
        .map(|entry| (entry.id, entry))
        .collect::<HashMap<_, _>>();
    if translations
        .keys()
        .any(|id| !entries.contains_key(id.as_str()))
    {
        return Err(ParseError::new(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no JavaScript source slot",
        ));
    }
    let mut output = Vec::with_capacity(original.len());
    let mut copied = 0;
    for slot in &skeleton.slots {
        output.extend_from_slice(&original[copied..slot.start]);
        if let Some(translation) = translations.get(&slot.id) {
            output.extend(
                encoding
                    .encode_without_bom(&escape(translation, entries[slot.id.as_str()].template)),
            );
        } else {
            output.extend_from_slice(&original[slot.start..slot.end]);
        }
        copied = slot.end;
    }
    output.extend_from_slice(&original[copied..]);
    Ok(output)
}

pub(crate) fn remove_entries(
    skeleton: &SourceSkeleton,
    removed: &BTreeSet<String>,
) -> Result<Vec<u8>, ParseError> {
    let format = FileFormat::from_id(skeleton.source_format)
        .filter(|format| matches!(format, FileFormat::JavaScript | FileFormat::TypeScript))
        .ok_or_else(|| invalid_skeleton("Unsupported JavaScript source skeleton format"))?;
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    if extract(format, &original)?.slots != skeleton.slots {
        return Err(invalid_skeleton(
            "JavaScript source slots do not own their original values",
        ));
    }
    let mut ranges = Vec::new();
    for entry in entries(&skeleton.source)? {
        if !removed.contains(entry.id) {
            continue;
        }
        let start = line_start(&skeleton.source, entry.start);
        let end = next_line(&skeleton.source, line_end(&skeleton.source, entry.end));
        ranges.push((start, end));
    }
    let mut result = String::with_capacity(skeleton.source.len());
    let mut previous = 0;
    for (start, end) in ranges {
        result.push_str(&skeleton.source[previous..start]);
        previous = end;
    }
    result.push_str(&skeleton.source[previous..]);
    Ok(encoding.encode(&result))
}

fn entries(source: &str) -> Result<Vec<Entry<'_>>, ParseError> {
    let mut result = Vec::new();
    let mut description = None;
    let mut position = 0;
    while position < source.len() {
        let end_of_line = line_end(source, position);
        let mut first = position;
        while first < end_of_line {
            let character = source[first..].chars().next().expect("line character");
            if !matches!(character, ' ' | '\t' | '\u{000c}') {
                break;
            }
            first += character.len_utf8();
        }
        if source[first..end_of_line].starts_with("//") {
            description = Some(
                source[first + 2..end_of_line]
                    .trim_matches(|character| character as u32 <= 0x20)
                    .to_owned(),
            );
            position = next_line(source, end_of_line);
            continue;
        }
        if first == end_of_line || source.as_bytes()[first] != b'"' {
            position = next_line(source, end_of_line);
            continue;
        }
        let end_of_key = closing(source, first + 1, end_of_line, b'"')
            .ok_or_else(|| invalid("Unterminated JavaScript message key"))?;
        let id = &source[first + 1..end_of_key];
        let delimiter = source[end_of_key + 1..end_of_line]
            .bytes()
            .position(|value| matches!(value, b'"' | b'`'))
            .map(|relative| end_of_key + 1 + relative)
            .ok_or_else(|| invalid("Missing JavaScript message value"))?;
        let template = source.as_bytes()[delimiter] == b'`';
        let value_end = closing(
            source,
            delimiter + 1,
            if template { source.len() } else { end_of_line },
            source.as_bytes()[delimiter],
        )
        .ok_or_else(|| invalid("Unterminated JavaScript message value"))?;
        result.push(Entry {
            id,
            value: &source[delimiter + 1..value_end],
            description: description.take(),
            template,
            start: delimiter + 1,
            end: value_end,
        });
        position = next_line(source, line_end(source, value_end));
    }
    Ok(result)
}

fn line_end(source: &str, start: usize) -> usize {
    source[start..]
        .bytes()
        .position(|character| matches!(character, b'\r' | b'\n'))
        .map_or(source.len(), |offset| start + offset)
}

fn line_start(source: &str, position: usize) -> usize {
    source[..position]
        .rfind(['\r', '\n'])
        .map_or(0, |index| index + 1)
}

fn next_line(source: &str, end: usize) -> usize {
    if end == source.len() {
        end
    } else if source[end..].starts_with("\r\n") {
        end + 2
    } else {
        end + 1
    }
}

fn closing(source: &str, start: usize, limit: usize, delimiter: u8) -> Option<usize> {
    (start..limit).find(|index| {
        source.as_bytes()[*index] == delimiter && source.as_bytes()[index - 1] != b'\\'
    })
}

fn unescape(value: &str, template: bool) -> String {
    let result = value
        .replace("\\r", "\r")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\'", "'");
    if template {
        result.replace("\\`", "`")
    } else {
        result
    }
}

fn message(entry: &Entry<'_>) -> Message {
    let mut metadata = Map::new();
    if entry.template {
        metadata.insert("javascriptTemplate".to_owned(), Value::Bool(true));
    }
    Message::new(
        unescape(entry.value, entry.template),
        entry.description.clone(),
        None,
        vec![],
        metadata,
    )
}

fn escape(value: &str, template: bool) -> String {
    let mut result = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '\n' if template => result.push('\n'),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '"' => result.push_str("\\\""),
            '`' if template => result.push_str("\\`"),
            _ => result.push(character),
        }
    }
    result
}

fn invalid(message: &str) -> ParseError {
    ParseError::new("INVALID_JAVASCRIPT", message)
}

fn invalid_skeleton(message: &str) -> ParseError {
    ParseError::new("INVALID_SKELETON", message)
}
