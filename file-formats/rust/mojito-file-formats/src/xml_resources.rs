use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use crate::source_skeleton::{SourceSkeleton, SourceSlot};
use crate::xml::{self, XmlElement, XmlNode};
use quick_xml::escape::unescape;
use regex::Regex;
use serde_json::Map;
use std::collections::{BTreeMap, HashSet};
use std::sync::OnceLock;

pub(crate) fn parse(format: FileFormat, source: &str) -> Result<Catalog, ParseError> {
    let safe;
    let input = if format == FileFormat::Xtb {
        static DOCTYPE: OnceLock<Regex> = OnceLock::new();
        let declaration = DOCTYPE.get_or_init(|| {
            Regex::new(r"(?s)^(\s*(?:<\?xml\b.*?\?>\s*)?)<!DOCTYPE\s+translationbundle\s*>(\s*<translationbundle\b)")
                .expect("valid safe XTB doctype expression")
        });
        safe = declaration.replace(source, "$1$2").into_owned();
        safe.as_str()
    } else {
        source
    };
    let root = xml::parse(input)?;
    if root.name
        != if format == FileFormat::Xtb {
            "translationbundle"
        } else {
            "root"
        }
    {
        return Err(ParseError::new(
            "INVALID_XML_ROOT",
            "XML resources require their format-owned root element",
        ));
    }
    let mut catalog = Catalog::new(format);
    if format == FileFormat::Xtb {
        catalog.locale = root
            .attribute("lang")
            .filter(|locale| !locale.trim().is_empty())
            .map(str::to_owned);
        add_xtb_entries(&root, &mut catalog)?;
    } else {
        add_resx_entries(&root, &mut catalog)?;
    }
    Ok(catalog)
}

fn add_xtb_entries(element: &XmlElement, catalog: &mut Catalog) -> Result<(), ParseError> {
    if element.name == "translation" && has_text(element) {
        let mut value = String::new();
        let mut placeholders = Vec::new();
        for child in &element.children {
            match child {
                XmlNode::Text(text) => value.push_str(text),
                XmlNode::Element(code) if code.name == "ph" => {
                    let name = code
                        .attribute("name")
                        .filter(|name| !name.trim().is_empty())
                        .ok_or_else(|| {
                            error(
                                "INVALID_XTB_PLACEHOLDER",
                                "XTB placeholder names must not be empty",
                            )
                        })?;
                    value.push('{');
                    value.push_str(name);
                    value.push('}');
                    let placeholder = Placeholder {
                        name: name.to_owned(),
                        source: format!("<ph name=\"{}\"/>", escape_attribute(name)),
                        kind: "value",
                        position: None,
                        example: code.attribute("example").map(str::to_owned),
                    };
                    if !placeholders.contains(&placeholder) {
                        placeholders.push(placeholder);
                    }
                }
                XmlNode::Element(_) => {
                    return Err(error(
                        "INVALID_XTB_PLACEHOLDER",
                        "Unsupported XTB inline element",
                    ))
                }
                XmlNode::Comment(_) => {}
            }
        }
        catalog.insert(
            element.attribute("key").unwrap_or_default().to_owned(),
            Message::new(
                value,
                element.attribute("desc").map(str::to_owned),
                None,
                placeholders,
                Map::new(),
            ),
        )?;
    }
    for child in element.elements() {
        if child.name != "ph" {
            add_xtb_entries(child, catalog)?;
        }
    }
    Ok(())
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
            "Catalog does not contain the expected XML resources",
        ));
    }
    let xtb = format == FileFormat::Xtb;
    let mut result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".to_owned();
    if xtb {
        result.push_str("<translationbundle");
        if let Some(locale) = &catalog.locale {
            result.push_str(" lang=\"");
            result.push_str(&escape_attribute(locale));
            result.push('"');
        }
        result.push_str(">\n");
    } else {
        result.push_str("<root>\n");
    }
    for (id, descriptor) in &catalog.messages {
        if xtb {
            result.push_str("  <translation key=\"");
            result.push_str(&escape_attribute(id));
            result.push('"');
            if let Some(description) = &descriptor.description {
                result.push_str(" desc=\"");
                result.push_str(&escape_attribute(description));
                result.push('"');
            }
            result.push('>');
            result.push_str(&render_xtb(descriptor, &descriptor.default_message, None)?);
            result.push_str("</translation>\n");
        } else {
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
    }
    result.push_str(if xtb {
        "</translationbundle>\n"
    } else {
        "</root>\n"
    });
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
        if token.starts_with('!')
            && !(format == FileFormat::Xtb && safe_xtb_doctype().is_match(token))
        {
            return Err(error(
                "UNSUPPORTED_SKELETON_MARKUP",
                "Unsupported XML resource declaration",
            ));
        }
        if token.starts_with('!') {
            position = end + 1;
            continue;
        }
        if token.starts_with('/') {
            let current = elements
                .pop()
                .ok_or_else(|| error("INVALID_SKELETON", "Unbalanced XML resource elements"))?;
            if format == FileFormat::Resx && current.name == "value"
                || format == FileFormat::Xtb && current.name == "translation"
            {
                if let Some(parent) = elements.last() {
                    if format == FileFormat::Xtb || parent.name == "data" {
                        let identity = if format == FileFormat::Xtb {
                            current.identity.as_ref()
                        } else {
                            parent.identity.as_ref()
                        };
                        if let Some(identity) = identity {
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
            let identity = if format == FileFormat::Resx && name == "data" {
                attribute(&token[name.len()..], "name")?
            } else if format == FileFormat::Xtb && name == "translation" {
                attribute(&token[name.len()..], "key")?
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
            let descriptor = catalog.messages.get(&slot.id).ok_or_else(|| {
                error("INVALID_SKELETON", "Missing XML resource source descriptor")
            })?;
            let body = if format == FileFormat::Xtb {
                decode_without_bom(&original[slot.start..slot.end], &skeleton.encoding)?
            } else {
                String::new()
            };
            let rendered = if format == FileFormat::Xtb {
                render_xtb(descriptor, translation, Some(&body))?
            } else {
                escape_text(translation)
            };
            output.extend(encode_without_bom(&rendered, &skeleton.encoding)?);
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

fn decode_without_bom(source: &[u8], encoding: &str) -> Result<String, ParseError> {
    match encoding {
        "UTF-8" | "UTF-8-BOM" | "US-ASCII" => std::str::from_utf8(source)
            .map(str::to_owned)
            .map_err(|_| error("INVALID_SKELETON", "Invalid XTB source encoding")),
        "ISO-8859-1" => Ok(source.iter().map(|value| char::from(*value)).collect()),
        "UTF-16LE" | "UTF-16LE-BOM" | "UTF-16BE" | "UTF-16BE-BOM" => {
            let mut pairs = source.chunks_exact(2);
            let values = pairs
                .by_ref()
                .map(|pair| {
                    if encoding.contains("LE") {
                        u16::from_le_bytes([pair[0], pair[1]])
                    } else {
                        u16::from_be_bytes([pair[0], pair[1]])
                    }
                })
                .collect::<Vec<_>>();
            if !pairs.remainder().is_empty() {
                return Err(error("INVALID_SKELETON", "Invalid XTB source encoding"));
            }
            String::from_utf16(&values)
                .map_err(|_| error("INVALID_SKELETON", "Invalid XTB source encoding"))
        }
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

fn render_xtb(
    message: &Message,
    translation: &str,
    source: Option<&str>,
) -> Result<String, ParseError> {
    let mut result = escape_text(translation);
    for placeholder in message.placeholders.as_deref().unwrap_or_default() {
        let marker = format!("{{{}}}", placeholder.name);
        if !result.contains(&marker) {
            return Err(error("INVALID_SKELETON_MARKUP", "Missing XTB placeholder"));
        }
        let native = if let Some(original) = source {
            let pattern = Regex::new(&format!(
                r#"<ph\b[^>]*\bname\s*=\s*(?:\"{}\"|'{}')[^>]*/>"#,
                regex::escape(&placeholder.name),
                regex::escape(&placeholder.name)
            ))
            .expect("valid XTB source placeholder expression");
            pattern
                .find(original)
                .map(|matched| matched.as_str().to_owned())
                .ok_or_else(|| {
                    error(
                        "INVALID_SKELETON_MARKUP",
                        "Missing source-owned XTB placeholder",
                    )
                })?
        } else if let Some(example) = &placeholder.example {
            format!(
                "{} example=\"{}\"/>",
                placeholder.source.trim_end_matches("/>"),
                escape_attribute(example)
            )
        } else {
            placeholder.source.clone()
        };
        result = result.replace(&marker, &native);
    }
    Ok(result)
}

fn error(code: &'static str, message: impl Into<String>) -> ParseError {
    ParseError::new(code, message)
}

fn safe_xtb_doctype() -> &'static Regex {
    static DOCTYPE: OnceLock<Regex> = OnceLock::new();
    DOCTYPE.get_or_init(|| {
        Regex::new(r"^!DOCTYPE\s+translationbundle\s*$").expect("valid XTB doctype")
    })
}

struct OpenElement {
    name: String,
    identity: Option<String>,
    body_start: usize,
}
