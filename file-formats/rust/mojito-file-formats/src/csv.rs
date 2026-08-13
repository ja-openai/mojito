use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::source_skeleton::{Encoding, SourceSkeleton, SourceSlot};
use serde_json::Map;
use std::collections::{BTreeMap, HashSet};

pub(crate) fn parse(format: FileFormat, source: &str) -> Result<Catalog, ParseError> {
    let mut catalog = Catalog::new(format);
    for row in rows(source)? {
        let Some(message) = row.fields.get(source_column(format)) else {
            continue;
        };
        let id = raw(source, &row.fields[0]);
        if id.is_empty() {
            continue;
        }
        let description = if format == FileFormat::Csv {
            row.fields.get(3).map(|field| raw(source, field).to_owned())
        } else {
            None
        };
        catalog.insert(
            id.to_owned(),
            Message::new(message.value.clone(), description, None, vec![], Map::new()),
        )?;
    }
    Ok(catalog)
}

pub(crate) fn parse_import(format: FileFormat, source: &str) -> Result<Catalog, ParseError> {
    let mut catalog = Catalog::new(format);
    for row in rows(source)? {
        let Some(target) = row.fields.get(target_column(format)) else {
            continue;
        };
        let id = raw(source, &row.fields[0]);
        if id.is_empty() || target.value.is_empty() {
            continue;
        }
        let description = if format == FileFormat::Csv {
            row.fields.get(3).map(|field| raw(source, field).to_owned())
        } else {
            None
        };
        catalog.insert(
            id.to_owned(),
            Message::new(target.value.clone(), description, None, vec![], Map::new()),
        )?;
    }
    Ok(catalog)
}

pub(crate) fn write(format: FileFormat, catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != format.id() {
        return Err(error("INVALID_SOURCE_FORMAT", "Mismatched CSV catalog"));
    }
    let mut output = String::new();
    for (id, message) in &catalog.messages {
        if format == FileFormat::Csv {
            output.push_str(&raw_field(id));
            output.push(',');
            output.push_str(&source_field(&message.default_message));
            output.push(',');
            output.push_str(&source_field(&message.default_message));
            output.push(',');
            output.push_str(&raw_field(message.description.as_deref().unwrap_or("")));
        } else {
            output.push_str(&source_field(&message.default_message));
            output.push(',');
            output.push_str(&source_field(&message.default_message));
        }
        output.push('\n');
    }
    Ok(output)
}

pub(crate) fn extract(format: FileFormat, bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    let encoding_name = encoding_name(bytes);
    let source = crate::decode(bytes, None)?;
    let catalog = parse(format, &source)?;
    let mut slots = Vec::new();
    for row in rows(&source)? {
        if row.fields.len() <= source_column(format) {
            continue;
        }
        let id = raw(&source, &row.fields[0]);
        if !catalog.messages.contains_key(id) {
            continue;
        }
        let target = row.fields.get(target_column(format));
        let start = target.map_or(row.end, |field| field.start);
        let end = target.map_or(start, |field| field.end);
        slots.push(SourceSlot {
            id: id.to_owned(),
            selector: None,
            variant: None,
            start: byte_offset(&source, start, encoding_name),
            end: byte_offset(&source, end, encoding_name),
            apple_object_index: None,
        });
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: format.id(),
        encoding: encoding_name.to_owned(),
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
        return Err(error("INVALID_SKELETON", "Unsupported CSV source skeleton"));
    }
    let format = FileFormat::from_id(skeleton.source_format)
        .ok_or_else(|| error("INVALID_SKELETON", "Unknown CSV source format"))?;
    if !matches!(format, FileFormat::Csv | FileFormat::CsvAdobeMagento) {
        return Err(error("INVALID_SKELETON", "Invalid CSV source format"));
    }
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let catalog = parse(format, &skeleton.source)?;
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if slot.variant.is_some() || slot.selector.is_some() || !known.insert(slot.id.as_str()) {
            return Err(error("INVALID_SKELETON", "Invalid CSV source slot"));
        }
    }
    if translations.keys().any(|id| !known.contains(id.as_str())) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no original CSV source slot",
        ));
    }
    let mut output = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error("INVALID_SKELETON", "Invalid CSV source-slot range"));
        }
        output.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.id) {
            if !catalog.messages.contains_key(&slot.id) {
                return Err(error("INVALID_SKELETON", "Missing CSV source descriptor"));
            }
            let previous_value = encoding.decode(&original[slot.start..slot.end])?;
            let mut value = String::new();
            if slot.start == slot.end {
                value.push(',');
            }
            value.push_str(&escape(translation, previous_value.starts_with('"')));
            let encoded = encoding.encode(&value);
            output.extend_from_slice(&encoded[encoding.bom_length()..]);
        } else {
            output.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    output.extend_from_slice(&original[previous..]);
    Ok(output)
}

pub(crate) fn localize(
    format: FileFormat,
    source: &[u8],
    translations: &BTreeMap<String, String>,
    remove_untranslated: bool,
) -> Result<Vec<u8>, ParseError> {
    let skeleton = extract(format, source)?;
    let known = skeleton
        .slots
        .iter()
        .map(|slot| slot.id.as_str())
        .collect::<HashSet<_>>();
    if translations.keys().any(|id| !known.contains(id.as_str())) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no original CSV source slot",
        ));
    }
    if !remove_untranslated {
        return render(&skeleton, translations);
    }
    let mut retained = String::with_capacity(skeleton.source.len());
    let mut previous = 0;
    for row in rows(&skeleton.source)? {
        let keep = row.fields.len() <= source_column(format)
            || translations.contains_key(raw(&skeleton.source, &row.fields[0]));
        if keep {
            retained.push_str(&skeleton.source[previous..row.terminator_end]);
        } else {
            retained.push_str(&skeleton.source[previous..row.start]);
        }
        previous = row.terminator_end;
    }
    retained.push_str(&skeleton.source[previous..]);
    let encoding = Encoding::named(&skeleton.encoding)?;
    let filtered = encoding.encode(&retained);
    let filtered_skeleton = extract(format, &filtered)?;
    render(&filtered_skeleton, translations)
}

fn source_column(format: FileFormat) -> usize {
    usize::from(format == FileFormat::Csv)
}

fn target_column(format: FileFormat) -> usize {
    source_column(format) + 1
}

fn raw<'a>(source: &'a str, field: &Field) -> &'a str {
    source[field.start..field.end].trim_matches(|character| character <= '\u{0020}')
}

fn raw_field(value: &str) -> String {
    if value.len() >= 2 && value.starts_with('"') && value.ends_with('"') {
        value.to_owned()
    } else {
        escape(value, false)
    }
}

fn source_field(value: &str) -> String {
    if !value.contains('"') {
        return escape(value, false);
    }
    let mut result = String::with_capacity(value.len() + 2);
    result.push('"');
    let mut characters = value.chars().peekable();
    while let Some(character) = characters.next() {
        result.push(character);
        if character == '"' {
            if characters.peek() == Some(&'"') {
                result.push(characters.next().expect("paired native qualifier"));
            } else {
                result.push('"');
            }
        }
    }
    result.push('"');
    result
}

fn escape(value: &str, preserve_quotes: bool) -> String {
    if preserve_quotes || value.contains([',', '"', '\r', '\n']) {
        format!("\"{}\"", value.replace('"', "\"\""))
    } else {
        value.to_owned()
    }
}

fn encoding_name(bytes: &[u8]) -> &'static str {
    if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
        "UTF-8-BOM"
    } else if bytes.starts_with(&[0xff, 0xfe]) {
        "UTF-16LE-BOM"
    } else if bytes.starts_with(&[0xfe, 0xff]) {
        "UTF-16BE-BOM"
    } else {
        "UTF-8"
    }
}

fn byte_offset(source: &str, index: usize, encoding: &str) -> usize {
    match encoding {
        "UTF-8" => index,
        "UTF-8-BOM" => index + 3,
        "UTF-16LE-BOM" | "UTF-16BE-BOM" => 2 + source[..index].encode_utf16().count() * 2,
        _ => unreachable!("CSV source encodings are bounded"),
    }
}

fn rows(source: &str) -> Result<Vec<Row>, ParseError> {
    let bytes = source.as_bytes();
    let mut rows = Vec::new();
    let mut position = 0;
    while position < bytes.len() {
        let start = position;
        let mut fields = Vec::new();
        loop {
            let field_start = position;
            let quoted = bytes.get(position) == Some(&b'"');
            let mut value = String::new();
            if quoted {
                position += 1;
                let mut closed = false;
                while position < bytes.len() {
                    if bytes[position] != b'"' {
                        let character = source[position..]
                            .chars()
                            .next()
                            .expect("valid UTF-8 character");
                        value.push(character);
                        position += character.len_utf8();
                    } else if bytes.get(position + 1) == Some(&b'"') {
                        value.push_str("\"\"");
                        position += 2;
                    } else {
                        position += 1;
                        closed = true;
                        break;
                    }
                }
                if !closed {
                    return Err(error("INVALID_CSV", "Unterminated quoted CSV field"));
                }
                if bytes
                    .get(position)
                    .is_some_and(|value| !matches!(value, b',' | b'\r' | b'\n'))
                {
                    return Err(error(
                        "INVALID_CSV",
                        "Unexpected character after quoted CSV field",
                    ));
                }
            } else {
                while position < bytes.len() && !matches!(bytes[position], b',' | b'\r' | b'\n') {
                    if bytes[position] == b'"' {
                        return Err(error("INVALID_CSV", "Quote inside unquoted CSV field"));
                    }
                    let character = source[position..]
                        .chars()
                        .next()
                        .expect("valid UTF-8 character");
                    value.push(character);
                    position += character.len_utf8();
                }
                value = value
                    .trim_matches(|character| character <= '\u{0020}')
                    .to_owned();
            }
            fields.push(Field {
                value,
                start: field_start,
                end: position,
            });
            if bytes.get(position) != Some(&b',') {
                break;
            }
            position += 1;
        }
        let end = position;
        if bytes.get(position) == Some(&b'\r') {
            position += 1;
        }
        if bytes.get(position) == Some(&b'\n') {
            position += 1;
        }
        rows.push(Row {
            start,
            end,
            terminator_end: position,
            fields,
        });
    }
    Ok(rows)
}

struct Field {
    value: String,
    start: usize,
    end: usize,
}

struct Row {
    start: usize,
    end: usize,
    terminator_end: usize,
    fields: Vec<Field>,
}

fn error(code: &'static str, message: &str) -> ParseError {
    ParseError::new(code, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalized_writers_preserve_customized_quoted_ids_sources_and_comments() {
        for (format, source) in [
            (
                FileFormat::Csv,
                "entry,\"Say \"\"hello\"\"\",unused,\"Translator note\"\n",
            ),
            (
                FileFormat::CsvAdobeMagento,
                "\"Say \"\"hello\"\"\",unused\n",
            ),
        ] {
            let catalog = parse(format, source).unwrap();
            let normalized = write(format, &catalog).unwrap();
            assert_eq!(parse(format, &normalized).unwrap(), catalog);
        }
    }

    #[test]
    fn imports_target_columns_using_source_owned_identity() {
        let standard = parse_import(
            FileFormat::Csv,
            "id,Original,\"Bonjour, ami\",Translator note\nmissing,Untranslated\nempty,Original,,Ignored\n",
        )
        .unwrap();
        assert_eq!(standard.messages.len(), 1);
        assert_eq!(standard.messages["id"].default_message, "Bonjour, ami");
        assert_eq!(
            standard.messages["id"].description.as_deref(),
            Some("Translator note")
        );

        let magento = parse_import(
            FileFormat::CsvAdobeMagento,
            "\"Hello, friend\",\"Bonjour, ami\"\n",
        )
        .unwrap();
        assert_eq!(
            magento.messages["\"Hello, friend\""].default_message,
            "Bonjour, ami"
        );
    }
}
