use crate::model::ParseError;
use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesStart, Event};
use quick_xml::{Reader, XmlVersion};
use regex::Regex;
use std::collections::{BTreeMap, HashSet};
use std::sync::OnceLock;

#[derive(Clone, Debug)]
pub(crate) enum XmlNode {
    Element(XmlElement),
    Text(String),
    Comment(String),
}

#[derive(Clone, Debug)]
pub(crate) struct XmlElement {
    pub name: String,
    pub namespace: Option<String>,
    pub attributes: Vec<(String, String)>,
    pub children: Vec<XmlNode>,
    pub processing_instruction: bool,
    pub cdata: bool,
    pub self_closing: bool,
    namespaces: BTreeMap<String, String>,
}

impl XmlElement {
    pub fn local_name(&self) -> &str {
        self.name.rsplit(':').next().unwrap_or(&self.name)
    }

    pub fn attribute(&self, name: &str) -> Option<&str> {
        self.attributes
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }

    pub fn namespaced_attribute(&self, namespace: &str, local_name: &str) -> Option<&str> {
        self.attributes.iter().find_map(|(name, value)| {
            let (prefix, local) = name.split_once(':')?;
            (local == local_name
                && self.namespaces.get(prefix).map(String::as_str) == Some(namespace))
            .then_some(value.as_str())
        })
    }

    pub fn namespace(&self, prefix: &str) -> Option<&str> {
        self.namespaces.get(prefix).map(String::as_str)
    }

    pub fn elements(&self) -> impl Iterator<Item = &XmlElement> {
        self.children.iter().filter_map(|node| match node {
            XmlNode::Element(element) => Some(element),
            _ => None,
        })
    }

    pub fn text(&self) -> String {
        let mut output = String::new();
        for child in &self.children {
            match child {
                XmlNode::Text(value) => output.push_str(value),
                XmlNode::Element(element) => output.push_str(&element.text()),
                XmlNode::Comment(_) => {}
            }
        }
        output
    }
}

pub(crate) fn parse(source: &str) -> Result<XmlElement, ParseError> {
    parse_with_reference_policy(source, false)
}

fn parse_with_reference_policy(source: &str, apple_plist: bool) -> Result<XmlElement, ParseError> {
    if !source.chars().all(xml_character) {
        return Err(ParseError::new(
            "INVALID_XML",
            "XML content contains a character forbidden by XML 1.0",
        ));
    }
    let mut reader = Reader::from_str(source);
    reader.config_mut().trim_text(false);
    let mut stack: Vec<XmlElement> = Vec::new();
    let mut root = None;
    let mut declaration_allowed = true;
    loop {
        let event = reader
            .read_event()
            .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
        match event {
            Event::Start(start) => {
                declaration_allowed = false;
                stack.push(element(&reader, &start, stack.last())?);
            }
            Event::Empty(start) => {
                declaration_allowed = false;
                let mut child = element(&reader, &start, stack.last())?;
                child.self_closing = true;
                attach(&mut stack, &mut root, child)?;
            }
            Event::End(_) => {
                declaration_allowed = false;
                let finished = stack
                    .pop()
                    .ok_or_else(|| ParseError::new("INVALID_XML", "Unexpected closing XML tag"))?;
                attach(&mut stack, &mut root, finished)?;
            }
            Event::Text(text) => {
                declaration_allowed = false;
                let value = text
                    .xml10_content()
                    .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
                    .into_owned();
                if stack.is_empty() {
                    if !value
                        .chars()
                        .all(|character| matches!(character, ' ' | '\t' | '\n' | '\r'))
                    {
                        return Err(ParseError::new(
                            "INVALID_XML",
                            "Character content is not allowed outside the XML document element",
                        ));
                    }
                } else {
                    push(&mut stack, XmlNode::Text(value));
                }
            }
            Event::CData(text) => {
                declaration_allowed = false;
                if stack.is_empty() {
                    return Err(ParseError::new(
                        "INVALID_XML",
                        "CDATA sections are not allowed outside the XML document element",
                    ));
                }
                if let Some(element) = stack.last_mut() {
                    element.cdata = true;
                }
                push(
                    &mut stack,
                    XmlNode::Text(
                        text.decode()
                            .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
                            .into_owned(),
                    ),
                );
            }
            Event::Comment(comment) => {
                declaration_allowed = false;
                push(
                    &mut stack,
                    XmlNode::Comment(
                        comment
                            .decode()
                            .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
                            .into_owned(),
                    ),
                );
            }
            Event::GeneralRef(reference) => {
                declaration_allowed = false;
                if stack.is_empty() {
                    return Err(ParseError::new(
                        "INVALID_XML",
                        "Entity references are not allowed outside the XML document element",
                    ));
                }
                if apple_plist {
                    if stack.last().is_some_and(|element| {
                        matches!(element.name.as_str(), "integer" | "date" | "data")
                    }) {
                        return Err(ParseError::new(
                            "INVALID_XML",
                            "Apple typed plist values cannot contain XML references",
                        ));
                    }
                    let raw = reference.as_ref();
                    if raw.first() == Some(&b'#') {
                        let digits = usize::from(raw.get(1) == Some(&b'x')) + 1;
                        if raw.len().saturating_sub(digits) > 8 {
                            return Err(ParseError::new(
                                "INVALID_XML",
                                "Apple plist character references cannot exceed eight digits",
                            ));
                        }
                    }
                }
                let value = if let Some(character) = reference
                    .resolve_char_ref()
                    .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
                {
                    if !xml_character(character) {
                        return Err(ParseError::new(
                            "INVALID_XML",
                            "Character reference is not valid XML 1.0",
                        ));
                    }
                    character.to_string()
                } else {
                    let name = reference
                        .decode()
                        .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
                    resolve_predefined_entity(&name)
                        .ok_or_else(|| ParseError::new("INVALID_XML", "Unknown XML entity"))?
                        .to_owned()
                };
                push(&mut stack, XmlNode::Text(value));
            }
            Event::DocType(_) => {
                return Err(ParseError::new(
                    "UNSAFE_XML",
                    "XML document types are forbidden",
                ))
            }
            Event::Eof => break,
            Event::PI(instruction) => {
                declaration_allowed = false;
                let target = std::str::from_utf8(instruction.target())
                    .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
                validate_xml_name(target, apple_plist)?;
                if target.eq_ignore_ascii_case("xml") {
                    return Err(ParseError::new(
                        "INVALID_XML",
                        "XML processing-instruction targets cannot use the reserved xml name",
                    ));
                }
                if let Some(element) = stack.last_mut() {
                    element.processing_instruction = true;
                }
            }
            Event::Decl(declaration) => {
                if !declaration_allowed || !stack.is_empty() || root.is_some() {
                    return Err(ParseError::new(
                        "INVALID_XML",
                        "XML declarations are allowed only at the beginning of the document",
                    ));
                }
                declaration_allowed = false;
                validate_declaration(&declaration)?;
            }
        }
    }
    if !stack.is_empty() {
        return Err(ParseError::new("INVALID_XML", "Unclosed XML elements"));
    }
    root.ok_or_else(|| ParseError::new("INVALID_XML", "Missing XML root element"))
}

fn xml_character(character: char) -> bool {
    matches!(
        character as u32,
        0x9 | 0xA | 0xD | 0x20..=0xD7FF | 0xE000..=0xFFFD | 0x10000..=0x10FFFF
    )
}

fn xml_name_start(character: char) -> bool {
    crate::xml_name::start(character)
}

fn xml_name_character(character: char) -> bool {
    crate::xml_name::character(character)
}

fn validate_xml_name(name: &str, permit_colons: bool) -> Result<(), ParseError> {
    let mut characters = name.chars();
    if !characters
        .next()
        .is_some_and(|character| xml_name_start(character) || permit_colons && character == ':')
        || !characters
            .all(|character| xml_name_character(character) || permit_colons && character == ':')
    {
        return Err(ParseError::new("INVALID_XML", "Invalid XML name"));
    }
    Ok(())
}

fn validate_xml_qualified_name(name: &str) -> Result<(), ParseError> {
    let mut segments = name.split(':');
    validate_xml_name(segments.next().unwrap_or_default(), false)?;
    if let Some(local) = segments.next() {
        validate_xml_name(local, false)?;
    }
    if segments.next().is_some() {
        return Err(ParseError::new(
            "INVALID_XML",
            "Invalid XML namespace-qualified name",
        ));
    }
    Ok(())
}

fn validate_declaration(declaration: &quick_xml::events::BytesDecl<'_>) -> Result<(), ParseError> {
    declaration
        .xml_version()
        .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
    let content = std::str::from_utf8(declaration.as_ref())
        .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
    let start = BytesStart::from_content(content, 3);
    let mut encoding_seen = false;
    let mut standalone_seen = false;
    for (index, attribute) in start.attributes().enumerate() {
        let attribute =
            attribute.map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
        match attribute.key.as_ref() {
            b"version" if index == 0 => {}
            b"encoding" if index == 1 && !encoding_seen && !standalone_seen => {
                let mut characters = attribute.value.iter();
                if !characters.next().is_some_and(u8::is_ascii_alphabetic)
                    || !characters
                        .all(|value| value.is_ascii_alphanumeric() || b"._-".contains(value))
                {
                    return Err(ParseError::new(
                        "INVALID_XML",
                        "Invalid XML declaration encoding",
                    ));
                }
                encoding_seen = true;
            }
            b"standalone" if index <= 2 && !standalone_seen => {
                if !matches!(attribute.value.as_ref(), b"yes" | b"no") {
                    return Err(ParseError::new(
                        "INVALID_XML",
                        "Invalid XML standalone declaration",
                    ));
                }
                standalone_seen = true;
            }
            _ => {
                return Err(ParseError::new(
                    "INVALID_XML",
                    "Invalid or out-of-order XML declaration attribute",
                ));
            }
        }
    }
    Ok(())
}

pub(crate) fn parse_apple_plist(source: &str) -> Result<XmlElement, ParseError> {
    static APPLE_PLIST_DOCTYPE: OnceLock<Regex> = OnceLock::new();
    let allowed = APPLE_PLIST_DOCTYPE.get_or_init(|| {
        Regex::new(
            r#"(?s)^(\s*(?:<\?xml\b[^>]*>\s*)?(?:(?:<\?.*?\?>|<!--.*?-->)\s*)*)<!DOCTYPE\s+plist\s+PUBLIC\s+["']-//Apple//DTD PLIST 1\.0//EN["']\s+["']https?://www\.apple\.com/DTDs/PropertyList-1\.0\.dtd["']\s*>"#,
        )
        .expect("valid Apple plist DTD pattern")
    });
    parse_with_reference_policy(&allowed.replace(source, "$1"), true)
}

fn element(
    reader: &Reader<&[u8]>,
    start: &BytesStart<'_>,
    parent: Option<&XmlElement>,
) -> Result<XmlElement, ParseError> {
    let name = std::str::from_utf8(start.name().as_ref())
        .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
        .to_owned();
    validate_xml_qualified_name(&name)?;
    let mut attributes = Vec::new();
    for attribute in start.attributes() {
        let attribute =
            attribute.map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?;
        let key = std::str::from_utf8(attribute.key.as_ref())
            .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
            .to_owned();
        validate_xml_qualified_name(&key)?;
        let value = attribute
            .decoded_and_normalized_value(XmlVersion::Implicit1_0, reader.decoder())
            .map_err(|error| ParseError::new("INVALID_XML", error.to_string()))?
            .into_owned();
        attributes.push((key, value));
    }
    const XML_NAMESPACE: &str = "http://www.w3.org/XML/1998/namespace";
    const XMLNS_NAMESPACE: &str = "http://www.w3.org/2000/xmlns/";

    let mut namespaces = parent
        .map(|element| element.namespaces.clone())
        .unwrap_or_else(|| BTreeMap::from([("xml".to_owned(), XML_NAMESPACE.to_owned())]));
    for (key, value) in &attributes {
        if key == "xmlns" {
            if value == XML_NAMESPACE || value == XMLNS_NAMESPACE {
                return Err(ParseError::new(
                    "INVALID_XML",
                    "Reserved XML namespaces cannot be the default namespace",
                ));
            }
            namespaces.insert(String::new(), value.clone());
        } else if let Some(prefix) = key.strip_prefix("xmlns:") {
            if prefix == "xmlns"
                || value.is_empty()
                || (prefix == "xml") != (value == XML_NAMESPACE)
                || value == XMLNS_NAMESPACE
            {
                return Err(ParseError::new(
                    "INVALID_XML",
                    "Invalid or reserved XML namespace-prefix binding",
                ));
            }
            namespaces.insert(prefix.to_owned(), value.clone());
        }
    }
    let prefix = name.split_once(':').map(|(prefix, _)| prefix).unwrap_or("");
    let namespace = namespaces
        .get(prefix)
        .filter(|namespace| !namespace.is_empty())
        .cloned();
    if !prefix.is_empty() && namespace.is_none() && prefix != "xml" {
        return Err(ParseError::new(
            "INVALID_XML",
            "Unbound XML namespace prefix",
        ));
    }
    let mut expanded_names = HashSet::new();
    for (attribute, _) in &attributes {
        if attribute == "xmlns" || attribute.starts_with("xmlns:") {
            continue;
        }
        if let Some((prefix, _)) = attribute.split_once(':') {
            if prefix != "xmlns" && prefix != "xml" && !namespaces.contains_key(prefix) {
                return Err(ParseError::new(
                    "INVALID_XML",
                    "Unbound XML attribute prefix",
                ));
            }
        }
        let (namespace, local_name) =
            attribute
                .split_once(':')
                .map_or((None, attribute.as_str()), |(prefix, local_name)| {
                    (
                        if prefix == "xml" {
                            Some(XML_NAMESPACE)
                        } else {
                            namespaces.get(prefix).map(String::as_str)
                        },
                        local_name,
                    )
                });
        if !expanded_names.insert((namespace, local_name)) {
            return Err(ParseError::new(
                "INVALID_XML",
                "Duplicate namespace-expanded XML attribute",
            ));
        }
    }
    Ok(XmlElement {
        name,
        namespace,
        attributes,
        children: Vec::new(),
        processing_instruction: false,
        cdata: false,
        self_closing: false,
        namespaces,
    })
}

fn attach(
    stack: &mut [XmlElement],
    root: &mut Option<XmlElement>,
    element: XmlElement,
) -> Result<(), ParseError> {
    if let Some(parent) = stack.last_mut() {
        parent.children.push(XmlNode::Element(element));
    } else if root.replace(element).is_some() {
        return Err(ParseError::new("INVALID_XML", "Multiple XML root elements"));
    }
    Ok(())
}

fn push(stack: &mut [XmlElement], node: XmlNode) {
    if let Some(parent) = stack.last_mut() {
        match (parent.children.last_mut(), node) {
            (Some(XmlNode::Text(previous)), XmlNode::Text(next)) => previous.push_str(&next),
            (_, child) => parent.children.push(child),
        }
    }
}
