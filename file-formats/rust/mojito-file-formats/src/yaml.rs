use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::source_skeleton::{SourceSkeleton, SourceSlot};
use crate::workflow::FilterOptions;
use regex::Regex;
use serde_json::Map;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;
use yaml_rust2::parser::{Event, Parser};
use yaml_rust2::scanner::TScalarStyle;

#[derive(Debug)]
struct Scalar {
    path: String,
    legacy_path: String,
    value: String,
    start: usize,
    end: usize,
    entry_start: usize,
    sequence_item: bool,
}

#[derive(Debug)]
struct Key {
    value: String,
    start: usize,
}

#[derive(Debug)]
struct Frame {
    path: String,
    legacy_path: String,
    key: Option<Key>,
    sequence: bool,
    flow: bool,
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
        let legacy_id = legacy_identity(&scalar.legacy_path, options);
        if should_extract(legacy_id, options) {
            let id = portable_identity(&scalar.path, &scalar.legacy_path, options).to_owned();
            let mut metadata = Map::new();
            if id != legacy_id {
                metadata.insert("yamlLegacyId".into(), legacy_id.into());
            }
            result.insert(id, Message::new(scalar.value, None, None, vec![], metadata))?;
        }
    }
    Ok(result)
}

pub(crate) fn extract(bytes: &[u8]) -> Result<SourceSkeleton, ParseError> {
    let options = FilterOptions::parse(FileFormat::Yaml, &[])?;
    extract_configured(bytes, &options)
}

pub(crate) fn extract_configured(
    bytes: &[u8],
    options: &FilterOptions,
) -> Result<SourceSkeleton, ParseError> {
    let (bom, source) = if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
        (3, std::str::from_utf8(&bytes[3..]))
    } else {
        (0, std::str::from_utf8(bytes))
    };
    let source = source.map_err(|_| error("INVALID_ENCODING", "Invalid UTF-8 YAML source"))?;
    let mut slots = Vec::new();
    let mut ids = BTreeSet::new();
    for scalar in scalars(source)? {
        let legacy_id = legacy_identity(&scalar.legacy_path, options);
        if !should_extract(legacy_id, options) {
            continue;
        }
        let id = portable_identity(&scalar.path, &scalar.legacy_path, options).to_owned();
        if !ids.insert(id.clone()) {
            return Err(ParseError::new(
                "DUPLICATE_MESSAGE_ID",
                format!("Duplicate YAML message ID: {id}"),
            ));
        }
        slots.push(SourceSlot {
            id,
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

pub(crate) fn remove_entries(
    skeleton: &SourceSkeleton,
    removed: &BTreeSet<String>,
    options: &FilterOptions,
) -> Result<Vec<u8>, ParseError> {
    if skeleton.schema_version != 1 || skeleton.source_format != FileFormat::Yaml.id() {
        return Err(error(
            "INVALID_SKELETON",
            "Unsupported YAML source skeleton",
        ));
    }
    let mut ranges = scalars(&skeleton.source)?
        .into_iter()
        .filter(|scalar| {
            removed.contains(portable_identity(
                &scalar.path,
                &scalar.legacy_path,
                options,
            ))
        })
        .map(|scalar| removal_range(&skeleton.source, &scalar))
        .collect::<Vec<_>>();
    ranges.sort_unstable_by_key(|range| range.start);
    let ranges = normalize_ranges(&skeleton.source, &ranges);
    let mut result = String::with_capacity(skeleton.source.len());
    let mut previous = 0;
    for range in ranges {
        if range.start < previous {
            previous = previous.max(range.end);
            continue;
        }
        result.push_str(&skeleton.source[previous..range.start]);
        previous = range.end;
    }
    result.push_str(&skeleton.source[previous..]);
    if result
        .lines()
        .all(|line| line.trim().is_empty() || line.trim_start().starts_with('#'))
    {
        if !result.is_empty() && !result.ends_with('\r') && !result.ends_with('\n') {
            result.push_str(if skeleton.source.contains("\r\n") {
                "\r\n"
            } else {
                "\n"
            });
        }
        result.push_str("{}");
        if skeleton.source.ends_with('\n') {
            result.push_str(if skeleton.source.ends_with("\r\n") {
                "\r\n"
            } else {
                "\n"
            });
        }
    }
    let encoding = crate::source_skeleton::Encoding::named(&skeleton.encoding)?;
    Ok(encoding.encode(&result))
}

#[derive(Clone, Copy)]
struct RemovalRange {
    start: usize,
    end: usize,
    whole_line: bool,
}

fn removal_range(source: &str, scalar: &Scalar) -> RemovalRange {
    let start_of_line = line_start(source, scalar.entry_start);
    let prefix = &source[start_of_line..scalar.entry_start];
    let whole_line = prefix.trim().is_empty() || scalar.sequence_item && prefix.trim() == "-";
    if whole_line {
        RemovalRange {
            start: start_of_line,
            end: next_line(source, line_end(source, scalar.end)),
            whole_line: true,
        }
    } else {
        RemovalRange {
            start: scalar.entry_start,
            end: scalar.end,
            whole_line: false,
        }
    }
}

fn normalize_ranges(source: &str, raw: &[RemovalRange]) -> Vec<RemovalRange> {
    let mut result = Vec::new();
    let mut index = 0;
    while index < raw.len() {
        let first = raw[index];
        if first.whole_line {
            result.push(first);
            index += 1;
            continue;
        }
        let mut last = first;
        let mut next = index + 1;
        while next < raw.len() && source[last.end..raw[next].start].trim() == "," {
            last = raw[next];
            next += 1;
        }
        let mut start = first.start;
        let mut end = last.end;
        let after = skip_whitespace_forward(source, end);
        if source.as_bytes().get(after) == Some(&b',') {
            end = after + 1;
        } else {
            let before = skip_whitespace_backward(source, start);
            if before > 0 && source.as_bytes()[before - 1] == b',' {
                start = before - 1;
            }
        }
        result.push(RemovalRange {
            start,
            end,
            whole_line: false,
        });
        index = next;
    }
    result
}

fn skip_whitespace_forward(source: &str, mut position: usize) -> usize {
    while source
        .as_bytes()
        .get(position)
        .is_some_and(|byte| matches!(byte, b' ' | b'\t' | b'\r' | b'\n'))
    {
        position += 1;
    }
    position
}

fn skip_whitespace_backward(source: &str, mut position: usize) -> usize {
    while position > 0
        && matches!(
            source.as_bytes()[position - 1],
            b' ' | b'\t' | b'\r' | b'\n'
        )
    {
        position -= 1;
    }
    position
}

fn line_start(source: &str, position: usize) -> usize {
    source[..position]
        .rfind(['\r', '\n'])
        .map_or(0, |index| index + 1)
}

fn line_end(source: &str, start: usize) -> usize {
    source[start..]
        .find(['\r', '\n'])
        .map_or(source.len(), |offset| start + offset)
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

fn encode_scalar(source: &str, translated: &str) -> String {
    if source.starts_with('\'') {
        if requires_double_quotes(translated) {
            double_quoted(translated)
        } else {
            format!("'{}'", translated.replace('\'', "''"))
        }
    } else if source.starts_with('"') {
        double_quoted(translated)
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
        let source_lines = body
            .split('\n')
            .map(|line| line.strip_suffix('\r').unwrap_or(line))
            .collect::<Vec<_>>();
        let mut result = source[..newline.saturating_add(1)].to_owned();
        for (index, line) in translated.split('\n').enumerate() {
            if index > 0 {
                result.push_str(separator);
            }
            if line.is_empty()
                && source_lines
                    .get(index)
                    .is_some_and(|original| original.trim().is_empty())
            {
                result.push_str(source_lines[index]);
            } else {
                result.push_str(&indent);
            }
            result.push_str(line);
        }
        result
    } else if plain_scalar_is_string(translated) {
        translated.to_owned()
    } else {
        double_quoted(translated)
    }
}

fn portable_identity<'a>(path: &'a str, legacy_path: &'a str, options: &FilterOptions) -> &'a str {
    if uses_full_key_path(options) {
        path
    } else {
        leaf(legacy_path)
    }
}

fn legacy_identity<'a>(legacy_path: &'a str, options: &FilterOptions) -> &'a str {
    if uses_full_key_path(options) {
        legacy_path
    } else {
        leaf(legacy_path)
    }
}

fn uses_full_key_path(options: &FilterOptions) -> bool {
    !options.contains("useFullKeyPath") || options.enabled("useFullKeyPath")
}

fn should_extract(legacy_id: &str, options: &FilterOptions) -> bool {
    let exception = options
        .pattern("exceptions")
        .is_some_and(|pattern| pattern.is_match(legacy_id));
    let all = !options.contains("extractAllPairs") || options.enabled("extractAllPairs");
    all != exception
}

fn leaf(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn plain_scalar_is_string(value: &str) -> bool {
    if value.is_empty()
        || value.trim() != value
        || requires_double_quotes(value)
        || matches!(value, "~" | "<<" | "=" | "---" | "...")
        || implicitly_typed(value)
    {
        return false;
    }
    let first = value.chars().next().expect("nonempty scalar");
    if "-?:,[]{}#&*!|>'\"%@`".contains(first) {
        return false;
    }
    let mut characters = value.chars().peekable();
    let mut previous = None;
    while let Some(character) = characters.next() {
        if ",[]{}".contains(character) {
            return false;
        }
        if character == ':' && characters.peek().is_none_or(|next| next.is_whitespace()) {
            return false;
        }
        if character == '#' && previous.is_none_or(char::is_whitespace) {
            return false;
        }
        previous = Some(character);
    }
    true
}

fn requires_double_quotes(value: &str) -> bool {
    value.chars().any(|character| {
        matches!(
            character,
            '\n' | '\r' | '\t' | '\u{0085}' | '\u{2028}' | '\u{2029}'
        ) || character <= '\u{001f}'
            || character == '\u{007f}'
    })
}

fn implicitly_typed(value: &str) -> bool {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN
        .get_or_init(|| {
            Regex::new(concat!(
                r"^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|",
                r"on|On|ON|off|Off|OFF|~|null|Null|NULL|<<|=|",
                r"[-+]?(?:0b_*[0-1][0-1_]*|0_*[0-7][0-7_]*|",
                r"(?:0|[1-9][0-9_]*)|0x_*[0-9a-fA-F][0-9a-fA-F_]*|",
                r"[1-9][0-9_]*(?::[0-5]?[0-9])+)|",
                r"[-+]?(?:[0-9][0-9_]*)\.[0-9_]*(?:[eE][-+]?[0-9]+)?|",
                r"[-+]?(?:[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)|",
                r"[-+]?\.[0-9_]+(?:[eE][-+]?[0-9]+)?|",
                r"[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*|",
                r"[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN)|",
                r"[0-9]{4}-[0-9]{2}-[0-9]{2}|",
                r"[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt]|[ \t]+)",
                r"[0-9]{1,2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]*)?",
                r"(?:[ \t]*(?:Z|[-+][0-9]{1,2}(?::[0-9]{2})?))?)$"
            ))
            .expect("valid YAML implicit-type pattern")
        })
        .is_match(value)
}

fn double_quoted(value: &str) -> String {
    let mut result = String::with_capacity(value.len() + 2);
    result.push('"');
    for character in value.chars() {
        match character {
            '\0' => result.push_str("\\0"),
            '\u{0007}' => result.push_str("\\a"),
            '\u{0008}' => result.push_str("\\b"),
            '\t' => result.push_str("\\t"),
            '\n' => result.push_str("\\n"),
            '\u{000b}' => result.push_str("\\v"),
            '\u{000c}' => result.push_str("\\f"),
            '\r' => result.push_str("\\r"),
            '\u{001b}' => result.push_str("\\e"),
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            '\u{0085}' => result.push_str("\\N"),
            '\u{2028}' => result.push_str("\\L"),
            '\u{2029}' => result.push_str("\\P"),
            character if character <= '\u{001f}' || character == '\u{007f}' => {
                result.push_str(&format!("\\u{:04x}", character as u32));
            }
            _ => result.push(character),
        }
    }
    result.push('"');
    result
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
                let (path, legacy_path) = next_path(&mut stack)?;
                stack.push(Frame {
                    path,
                    legacy_path,
                    key: None,
                    sequence: false,
                    flow: source.chars().nth(marker.index()) == Some('{'),
                    count: 0,
                });
            }
            Event::MappingEnd | Event::SequenceEnd => {
                stack.pop();
            }
            Event::SequenceStart(_, _) => {
                let (path, legacy_path) = next_path(&mut stack)?;
                stack.push(Frame {
                    path,
                    legacy_path,
                    key: None,
                    sequence: true,
                    flow: source.chars().nth(marker.index()) == Some('['),
                    count: 0,
                });
            }
            Event::Scalar(value, style, _, _) => {
                let marked = source
                    .char_indices()
                    .nth(marker.index())
                    .map_or(source.len(), |(index, _)| index);
                let current = stack
                    .last_mut()
                    .ok_or_else(|| error("INVALID_YAML", "YAML source must contain a mapping"))?;
                if !current.sequence && current.key.is_none() {
                    current.key = Some(Key {
                        value,
                        start: marked,
                    });
                    continue;
                }
                let (path, legacy_path, entry_start, sequence_item) = if current.sequence {
                    current.count += 1;
                    (
                        format!("{}[{}]", current.path, current.count - 1),
                        current.legacy_path.clone(),
                        marked,
                        true,
                    )
                } else {
                    let key = current.key.take().unwrap();
                    (
                        if current.path.is_empty() {
                            key.value.clone()
                        } else {
                            format!("{}/{}", current.path, key.value)
                        },
                        if current.legacy_path.is_empty() {
                            key.value
                        } else {
                            format!("{}/{}", current.legacy_path, key.value)
                        },
                        key.start,
                        false,
                    )
                };
                let start = if matches!(style, TScalarStyle::Literal | TScalarStyle::Folded) {
                    source[..marked]
                        .rfind(['|', '>'])
                        .filter(|index| source[*index..marked].contains('\n'))
                        .unwrap_or(marked)
                } else {
                    marked
                };
                let end = scalar_end(source, start, style, current.flow)?;
                result.push(Scalar {
                    path,
                    legacy_path,
                    value,
                    start,
                    end,
                    entry_start,
                    sequence_item,
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

fn next_path(stack: &mut [Frame]) -> Result<(String, String), ParseError> {
    let Some(parent) = stack.last_mut() else {
        return Ok((String::new(), String::new()));
    };
    if parent.sequence {
        parent.count += 1;
        Ok((
            format!("{}[{}]", parent.path, parent.count - 1),
            parent.legacy_path.clone(),
        ))
    } else if let Some(key) = parent.key.take() {
        Ok((
            if parent.path.is_empty() {
                key.value.clone()
            } else {
                format!("{}/{}", parent.path, key.value)
            },
            if parent.legacy_path.is_empty() {
                key.value
            } else {
                format!("{}/{}", parent.legacy_path, key.value)
            },
        ))
    } else {
        Err(error(
            "INVALID_YAML",
            "YAML collection requires a mapping key",
        ))
    }
}

fn scalar_end(
    source: &str,
    start: usize,
    style: TScalarStyle,
    flow: bool,
) -> Result<usize, ParseError> {
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
            let line = if flow {
                tail.find(['\r', '\n', ',', ']', '}']).unwrap_or(tail.len())
            } else {
                tail.find(['\r', '\n']).unwrap_or(tail.len())
            };
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
