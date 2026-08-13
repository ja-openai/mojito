use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::source_skeleton::{SourceSkeleton, SourceSlot};
use crate::xml::{self, XmlElement, XmlNode};
use quick_xml::escape::unescape;
use regex::Regex;
use serde_json::Map;
use std::collections::{BTreeMap, HashSet};
use std::sync::OnceLock;

pub(crate) fn parse(format: FileFormat, source: &str) -> Result<Catalog, ParseError> {
    let root = xml::parse(source)?;
    if root.name != "root" {
        return Err(ParseError::new(
            "INVALID_XML_ROOT",
            "RESX resources require a root element",
        ));
    }
    let mut catalog = Catalog::new(format);
    add_resx_entries(&root, &mut catalog)?;
    Ok(catalog)
}

fn add_resx_entries(element: &XmlElement, catalog: &mut Catalog) -> Result<(), ParseError> {
    if element.name == "data" {
        let name = element.attribute("name").unwrap_or_default();
        if element.attribute("type").is_none()
            && element.attribute("mimetype").is_none()
            && !name.starts_with('>')
            && !name.ends_with(".Name")
        {
            if let Some(value) = element
                .elements()
                .find(|value| value.name == "value" && has_text(value))
            {
                let description = (!name.starts_with('$'))
                    .then(|| {
                        element
                            .elements()
                            .find(|comment| comment.name == "comment")
                            .map(XmlElement::text)
                    })
                    .flatten();
                catalog.insert(
                    name.to_owned(),
                    Message::new(value.text(), description, None, Vec::new(), Map::new()),
                )?;
            }
        }
    }
    for child in element.elements() {
        add_resx_entries(child, catalog)?;
    }
    Ok(())
}

fn has_text(value: &XmlElement) -> bool {
    value
        .children
        .iter()
        .any(|child| matches!(child, XmlNode::Text(_)))
}

pub(crate) fn write(format: FileFormat, catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != format.id() {
        return Err(ParseError::new(
            "INVALID_SOURCE_FORMAT",
            "Catalog does not contain RESX resources",
        ));
    }
    let mut result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n".to_owned();
    for (id, descriptor) in &catalog.messages {
        result.push_str("  <data name=\"");
        result.push_str(&escape_attribute(id));
        result.push_str("\" xml:space=\"preserve\">\n    <value>");
        result.push_str(&escape_text(&descriptor.default_message));
        result.push_str("</value>");
        if let Some(description) = &descriptor.description {
            result.push_str("\n    <comment>");
            result.push_str(&escape_text(description));
            result.push_str("</comment>");
        }
        result.push_str("\n  </data>\n");
    }
    result.push_str("</root>\n");
    Ok(result)
}

pub(crate) fn extract_skeleton(
    format: FileFormat,
    bytes: &[u8],
) -> Result<SourceSkeleton, ParseError> {
    let declared = crate::xml_encoding(format, bytes)?;
    let source = crate::decode(bytes, declared)?;
    let encoding = encoding_name(bytes, declared);
    let catalog = crate::parse(format, bytes)?;
    let mut slots = Vec::new();
    let mut assigned = HashSet::new();
    let mut elements: Vec<OpenElement> = Vec::new();
    let mut position = 0;
    while position < source.len() {
        if source.as_bytes()[position] != b'<' {
            position += source[position..].chars().next().unwrap().len_utf8();
            continue;
        }
        if source[position..].starts_with("<!--") {
            position = skip(&source, position, "-->")?;
            continue;
        }
        if source[position..].starts_with("<![CDATA[") {
            position = skip(&source, position, "]]>")?;
            continue;
        }
        if source[position..].starts_with("<?") {
            position = skip(&source, position, "?>")?;
            continue;
        }
        let end = tag_end(&source, position)?;
        let token = source[position + 1..end].trim();
        if token.starts_with('!') {
            return Err(error(
                "UNSUPPORTED_SKELETON_MARKUP",
                "Unsupported XML resource declaration",
            ));
        }
        if token.starts_with('/') {
            let current = elements
                .pop()
                .ok_or_else(|| error("INVALID_SKELETON", "Unbalanced XML resource elements"))?;
            if current.name == "value" {
                if let Some(parent) = elements.last() {
                    if parent.name == "data" {
                        if let Some(identity) = &parent.identity {
                            if catalog.messages.contains_key(identity) {
                                if !assigned.insert(identity.clone()) {
                                    return Err(error(
                                        "INVALID_SKELETON",
                                        "Duplicate XML resource source slot",
                                    ));
                                }
                                slots.push(SourceSlot {
                                    id: identity.clone(),
                                    selector: None,
                                    variant: None,
                                    start: byte_offset(&source, current.body_start, encoding)?,
                                    end: byte_offset(&source, position, encoding)?,
                                    apple_object_index: None,
                                });
                            }
                        }
                    }
                }
            }
        } else if !token.ends_with('/') {
            let name = token.split_whitespace().next().unwrap();
            let identity = if name == "data" {
                attribute(&token[name.len()..], "name")?
            } else {
                None
            };
            elements.push(OpenElement {
                name: name.to_owned(),
                identity,
                body_start: end + 1,
            });
        }
        position = end + 1;
    }
    if !elements.is_empty() || assigned.len() != catalog.messages.len() {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "XML resources have unowned translatable values",
        ));
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: format.id(),
        encoding: encoding.to_owned(),
        source,
        android_resource_path: None,
        android_feature_flags: None,
        apple_target_locale: None,
        slots,
    })
}

pub(crate) fn render_skeleton(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
) -> Result<Vec<u8>, ParseError> {
    let format = FileFormat::from_id(skeleton.source_format)
        .ok_or_else(|| error("INVALID_SKELETON", "Unsupported XML resource format"))?;
    let original = encode_source(&skeleton.source, &skeleton.encoding)?;
    let catalog = crate::parse(format, &original)?;
    let mut identities = HashSet::new();
    for slot in &skeleton.slots {
        if slot.variant.is_some() || slot.selector.is_some() || !identities.insert(slot.id.as_str())
        {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid XML resource source slot",
            ));
        }
    }
    if translations
        .keys()
        .any(|identity| !identities.contains(identity.as_str()))
    {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no original XML resource slot",
        ));
    }
    let mut output = Vec::with_capacity(original.len());
    let mut previous = 0;
    for slot in &skeleton.slots {
        if slot.start < previous || slot.end < slot.start || slot.end > original.len() {
            return Err(error(
                "INVALID_SKELETON",
                "Invalid XML resource source slot range",
            ));
        }
        output.extend_from_slice(&original[previous..slot.start]);
        if let Some(translation) = translations.get(&slot.id) {
            if !catalog.messages.contains_key(&slot.id) {
                return Err(error(
                    "INVALID_SKELETON",
                    "Missing XML resource source descriptor",
                ));
            }
            output.extend(encode_without_bom(
                &escape_text(translation),
                &skeleton.encoding,
            )?);
        } else {
            output.extend_from_slice(&original[slot.start..slot.end]);
        }
        previous = slot.end;
    }
    output.extend_from_slice(&original[previous..]);
    Ok(output)
}

fn encoding_name(bytes: &[u8], declared: Option<&str>) -> &'static str {
    if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
        "UTF-8-BOM"
    } else if bytes.starts_with(&[0xff, 0xfe]) {
        "UTF-16LE-BOM"
    } else if bytes.starts_with(&[0xfe, 0xff]) {
        "UTF-16BE-BOM"
    } else {
        match declared {
            Some("UTF-16LE") => "UTF-16LE",
            Some("UTF-16BE") => "UTF-16BE",
            Some("ISO-8859-1") => "ISO-8859-1",
            Some("US-ASCII") => "US-ASCII",
            _ => "UTF-8",
        }
    }
}

fn byte_offset(source: &str, index: usize, encoding: &str) -> Result<usize, ParseError> {
    let prefix = &source[..index];
    Ok(match encoding {
        "UTF-8" => index,
        "UTF-8-BOM" => index + 3,
        "UTF-16LE" | "UTF-16BE" => prefix.encode_utf16().count() * 2,
        "UTF-16LE-BOM" | "UTF-16BE-BOM" => prefix.encode_utf16().count() * 2 + 2,
        "ISO-8859-1" | "US-ASCII" => prefix.chars().count(),
        _ => {
            return Err(error(
                "INVALID_SKELETON",
                "Unsupported XML resource encoding",
            ))
        }
    })
}

fn encode_source(source: &str, encoding: &str) -> Result<Vec<u8>, ParseError> {
    let mut result = match encoding {
        "UTF-8-BOM" => vec![0xef, 0xbb, 0xbf],
        "UTF-16LE-BOM" => vec![0xff, 0xfe],
        "UTF-16BE-BOM" => vec![0xfe, 0xff],
        _ => Vec::new(),
    };
    result.extend(encode_without_bom(source, encoding)?);
    Ok(result)
}

fn encode_without_bom(source: &str, encoding: &str) -> Result<Vec<u8>, ParseError> {
    match encoding {
        "UTF-8" | "UTF-8-BOM" => Ok(source.as_bytes().to_vec()),
        "UTF-16LE" | "UTF-16LE-BOM" => {
            Ok(source.encode_utf16().flat_map(u16::to_le_bytes).collect())
        }
        "UTF-16BE" | "UTF-16BE-BOM" => {
            Ok(source.encode_utf16().flat_map(u16::to_be_bytes).collect())
        }
        "ISO-8859-1" | "US-ASCII" => source
            .chars()
            .map(|character| {
                let value = character as u32;
                if value <= if encoding == "US-ASCII" { 0x7f } else { 0xff } {
                    Ok(value as u8)
                } else {
                    Err(error(
                        "INVALID_ENCODING",
                        "Unrepresentable XML resource text",
                    ))
                }
            })
            .collect(),
        _ => Err(error(
            "INVALID_SKELETON",
            "Unsupported XML resource encoding",
        )),
    }
}

fn attribute(source: &str, expected: &str) -> Result<Option<String>, ParseError> {
    static ATTRIBUTE: OnceLock<Regex> = OnceLock::new();
    let matcher = ATTRIBUTE.get_or_init(|| {
        Regex::new(r#"([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(?:\"([^\"]*)\"|'([^']*)')"#)
            .expect("valid XML resource attribute expression")
    });
    for captured in matcher.captures_iter(source) {
        if &captured[1] == expected {
            return unescape(
                captured
                    .get(2)
                    .or_else(|| captured.get(3))
                    .expect("quoted XML resource attribute")
                    .as_str(),
            )
            .map(|value| Some(value.into_owned()))
            .map_err(|failure| error("INVALID_XML", failure.to_string()));
        }
    }
    Ok(None)
}

fn skip(source: &str, start: usize, closing: &str) -> Result<usize, ParseError> {
    source[start..]
        .find(closing)
        .map(|index| start + index + closing.len())
        .ok_or_else(|| error("INVALID_SKELETON", "Unclosed XML resource declaration"))
}

fn tag_end(source: &str, start: usize) -> Result<usize, ParseError> {
    let mut quoted = None;
    for (offset, current) in source[start + 1..].char_indices() {
        if quoted == Some(current) {
            quoted = None;
        } else if quoted.is_none() && matches!(current, '\'' | '"') {
            quoted = Some(current);
        } else if quoted.is_none() && current == '>' {
            return Ok(start + offset + 1);
        }
    }
    Err(error("INVALID_SKELETON", "Unclosed XML resource element"))
}

fn escape_text(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn escape_attribute(value: &str) -> String {
    escape_text(value).replace('"', "&quot;")
}

fn error(code: &'static str, message: impl Into<String>) -> ParseError {
    ParseError::new(code, message)
}

struct OpenElement {
    name: String,
    identity: Option<String>,
    body_start: usize,
}
