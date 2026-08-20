use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::source_skeleton::{Encoding, SourceSkeleton, SourceSlot};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, HashMap, HashSet};

const IMAGE_DESCRIPTION: &str = "Do not translate: extracted image URL, adapt if needed";

struct Entry {
    id: String,
    value: String,
    description: Option<String>,
    tags: Vec<String>,
    image_starts: Vec<usize>,
    codes: Vec<Value>,
    start: usize,
    end: usize,
    attribute: bool,
}

struct Tag<'a> {
    name: String,
    text: &'a str,
    start: usize,
    end: usize,
    closing: bool,
}

struct Attribute<'a> {
    value: &'a str,
    start: usize,
    end: usize,
}

pub(crate) fn parse(
    source: &str,
    include_images: bool,
    suppress_empty: bool,
) -> Result<Catalog, ParseError> {
    let mut catalog = Catalog::new(FileFormat::Html);
    for entry in entries(source, include_images, suppress_empty)? {
        let mut metadata = Map::new();
        if !entry.codes.is_empty() {
            metadata.insert("mojitoInlineCodes".to_owned(), Value::Array(entry.codes));
        }
        catalog.insert(
            entry.id,
            Message::new(entry.value, entry.description, None, vec![], metadata),
        )?;
    }
    Ok(catalog)
}

pub(crate) fn extract(
    source: &[u8],
    include_images: bool,
    suppress_empty: bool,
) -> Result<SourceSkeleton, ParseError> {
    let encoding = Encoding::detect(source);
    let text = crate::decode(source, None)?;
    let parsed = entries(&text, include_images, suppress_empty)?;
    let slots = owner_indices(&parsed)
        .into_iter()
        .map(|index| {
            let entry = &parsed[index];
            SourceSlot {
                id: entry.id.clone(),
                selector: None,
                variant: None,
                start: encoding.offset(&text, entry.start),
                end: encoding.offset(&text, entry.end),
                apple_object_index: None,
            }
        })
        .collect();
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: FileFormat::Html.id(),
        encoding: encoding.name().to_owned(),
        source: text,
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
    render_for_mojito(skeleton, translations, false)
}

pub(crate) fn render_for_mojito(
    skeleton: &SourceSkeleton,
    translations: &BTreeMap<String, String>,
    remove_untranslated: bool,
) -> Result<Vec<u8>, ParseError> {
    if skeleton.schema_version != 1 || skeleton.source_format != FileFormat::Html.id() {
        return Err(invalid_skeleton("Unsupported HTML source skeleton"));
    }
    let encoding = Encoding::named(&skeleton.encoding)?;
    let original = encoding.encode(&skeleton.source);
    let entries = matching_entries(skeleton)?;
    let by_id: HashMap<&str, &Entry> = entries
        .iter()
        .map(|entry| (entry.id.as_str(), entry))
        .collect();
    if translations
        .keys()
        .any(|id| !by_id.contains_key(id.as_str()))
    {
        return Err(ParseError::new(
            "UNKNOWN_SKELETON_SLOT",
            "Unknown HTML source slot",
        ));
    }
    let mut output = Vec::with_capacity(original.len());
    let mut copied = 0;
    for (owner, slot) in owner_indices(&entries).into_iter().zip(&skeleton.slots) {
        if slot.start < copied || slot.end < slot.start || slot.end > original.len() {
            return Err(invalid_skeleton("Invalid HTML source-slot byte ownership"));
        }
        output.extend_from_slice(&original[copied..slot.start]);
        let entry = &entries[owner];
        let value = match translations.get(&slot.id) {
            Some(translation) => render_value(
                entry,
                translation,
                &entries,
                translations,
                remove_untranslated,
            )?,
            None if remove_untranslated => omit_value(entry, &entries, translations),
            None => apply_nested_attributes(
                &skeleton.source[entry.start..entry.end],
                entry.start,
                &entries,
                translations,
                false,
            ),
        };
        output.extend(encoding.encode_without_bom(&value));
        copied = slot.end;
    }
    output.extend_from_slice(&original[copied..]);
    Ok(output)
}

fn matching_entries(skeleton: &SourceSkeleton) -> Result<Vec<Entry>, ParseError> {
    for include_images in [false, true] {
        for suppress_empty in [true, false] {
            let parsed = entries(&skeleton.source, include_images, suppress_empty)?;
            let owners = owner_indices(&parsed);
            if owners.len() == skeleton.slots.len()
                && owners
                    .iter()
                    .zip(&skeleton.slots)
                    .all(|(index, slot)| parsed[*index].id == slot.id)
            {
                return Ok(parsed);
            }
        }
    }
    Err(invalid_skeleton(
        "HTML source slots do not own the original document",
    ))
}

fn render_value(
    entry: &Entry,
    translation: &str,
    entries: &[Entry],
    translations: &BTreeMap<String, String>,
    remove_untranslated: bool,
) -> Result<String, ParseError> {
    if entry.attribute {
        return Ok(escape_attribute(translation));
    }
    let mut output = String::new();
    let mut copied = 0;
    let mut seen = HashSet::new();
    while let Some(relative) = translation[copied..].find("<br id='p") {
        let start = copied + relative;
        output.push_str(&escape_text(&translation[copied..start]));
        let end = translation[start..]
            .find("'/>")
            .map(|relative| start + relative + 3)
            .ok_or_else(|| invalid_markup("Malformed HTML inline placeholder"))?;
        let number: usize = translation[start + 9..end - 3]
            .parse()
            .map_err(|_| invalid_markup("Malformed HTML inline placeholder"))?;
        if number == 0 || number > entry.tags.len() {
            return Err(invalid_markup("Unknown HTML inline image code"));
        }
        if !seen.insert(number) {
            return Err(invalid_markup("Duplicated HTML inline image code"));
        }
        output.push_str(&apply_nested_attributes(
            &entry.tags[number - 1],
            entry.image_starts[number - 1],
            entries,
            translations,
            remove_untranslated,
        ));
        copied = end;
    }
    output.push_str(&escape_text(&translation[copied..]));
    for index in 1..=entry.tags.len() {
        if !translation.contains(&format!("<br id='p{index}'/>")) {
            return Err(invalid_markup(
                "HTML translation removed an owned inline image",
            ));
        }
    }
    Ok(output)
}

fn omit_value(entry: &Entry, entries: &[Entry], translations: &BTreeMap<String, String>) -> String {
    if entry.attribute {
        return String::new();
    }
    entry
        .tags
        .iter()
        .zip(&entry.image_starts)
        .map(|(image, start)| apply_nested_attributes(image, *start, entries, translations, true))
        .collect()
}

fn apply_nested_attributes(
    source: &str,
    start: usize,
    entries: &[Entry],
    translations: &BTreeMap<String, String>,
    remove_untranslated: bool,
) -> String {
    let mut localized = source.to_owned();
    let mut attributes: Vec<&Entry> = entries
        .iter()
        .filter(|entry| {
            entry.attribute
                && entry.start >= start
                && entry.end <= start + source.len()
                && (translations.contains_key(&entry.id) || remove_untranslated)
        })
        .collect();
    attributes.sort_unstable_by_key(|entry| std::cmp::Reverse(entry.start));
    for entry in attributes {
        localized.replace_range(
            entry.start - start..entry.end - start,
            &escape_attribute(
                translations
                    .get(&entry.id)
                    .map(String::as_str)
                    .unwrap_or_default(),
            ),
        );
    }
    localized
}

fn owner_indices(entries: &[Entry]) -> Vec<usize> {
    let mut ordered: Vec<usize> = (0..entries.len()).collect();
    ordered.sort_unstable_by_key(|index| entries[*index].start);
    let mut owners = Vec::new();
    for index in ordered {
        let contained = owners.last().is_some_and(|parent: &usize| {
            let parent = &entries[*parent];
            !parent.attribute
                && parent.start <= entries[index].start
                && parent.end >= entries[index].end
        });
        if !contained {
            owners.push(index);
        }
    }
    owners
}

fn entries(
    source: &str,
    include_images: bool,
    suppress_empty: bool,
) -> Result<Vec<Entry>, ParseError> {
    let mut output = Vec::new();
    let mut generator = Generator::new();
    let mut position = 0;
    while position < source.len() {
        if source[position..].starts_with("<!--") {
            let end = source[position + 4..]
                .find("-->")
                .map(|relative| position + relative + 7)
                .ok_or_else(|| invalid("Unterminated HTML comment"))?;
            position = end;
            continue;
        }
        if source.as_bytes()[position] != b'<' {
            position += source[position..].chars().next().unwrap().len_utf8();
            continue;
        }
        let current = tag(source, position)?;
        position = current.end;
        if current.name.is_empty() || current.closing {
            continue;
        }
        if matches!(current.name.as_str(), "script" | "style") {
            let close = index_of_ignore_case(source, &format!("</{}", current.name), position)
                .ok_or_else(|| invalid("Unterminated protected HTML element"))?;
            position = tag(source, close)?.end;
            continue;
        }
        if current.name == "meta" {
            if let (Some(name), Some(content)) =
                (attribute(&current, "name"), attribute(&current, "content"))
            {
                if name.value.eq_ignore_ascii_case("description")
                    || name.value.eq_ignore_ascii_case("keywords")
                {
                    add(
                        &mut output,
                        &mut generator,
                        content.value.to_owned(),
                        None,
                        None,
                        vec![],
                        vec![],
                        vec![],
                        content.start,
                        content.end,
                        true,
                        suppress_empty,
                    );
                }
            }
            continue;
        }
        if current.name == "img" {
            add_image(
                &mut output,
                &mut generator,
                &current,
                include_images,
                suppress_empty,
            );
            continue;
        }
        if let Some(title) = attribute(&current, "title") {
            add(
                &mut output,
                &mut generator,
                decode_entities(title.value),
                None,
                None,
                vec![],
                vec![],
                vec![],
                title.start,
                title.end,
                true,
                suppress_empty,
            );
        }
        if !translatable_element(&current.name) {
            continue;
        }
        let close = index_of_ignore_case(source, &format!("</{}", current.name), position);
        let boundary = close.unwrap_or(source.len());
        let mut tags = Vec::new();
        let mut image_starts = Vec::new();
        let mut image_parts = Vec::new();
        let mut codes = Vec::new();
        let mut search = position;
        let mut text = String::new();
        while search < boundary {
            let Some(image_start) = index_of_ignore_case_until(source, "<img", search, boundary)
            else {
                text.push_str(&source[search..boundary]);
                break;
            };
            text.push_str(&source[search..image_start]);
            let image = tag(source, image_start)?;
            if image.end > boundary {
                return Err(invalid("HTML image crosses its owning element"));
            }
            add_image(
                &mut output,
                &mut generator,
                &image,
                include_images,
                suppress_empty,
            );
            tags.push(image.text.to_owned());
            image_starts.push(image.start);
            image_parts.push(document_part(source, image.start, &output));
            let number = tags.len();
            text.push_str(&format!("<br id='p{number}'/>"));
            codes.push(json!({"id": format!("p{number}"), "source": image.text}));
            search = image.end;
        }
        let visible = collapse(&decode_entities(&text));
        let mut identity = visible.clone();
        for (index, part) in image_parts.iter().enumerate() {
            identity = identity.replace(
                &format!("<br id='p{}'/>", index + 1),
                &format!("[#$dp{part}]"),
            );
        }
        add(
            &mut output,
            &mut generator,
            visible,
            Some(identity),
            None,
            tags,
            image_starts,
            codes,
            position,
            boundary,
            false,
            suppress_empty,
        );
        position = if let Some(close) = close {
            tag(source, close)?.end
        } else {
            boundary
        };
    }
    Ok(output)
}

fn add_image(
    output: &mut Vec<Entry>,
    generator: &mut Generator,
    image: &Tag<'_>,
    include_images: bool,
    suppress_empty: bool,
) {
    if let Some(alternate) = attribute(image, "alt") {
        add(
            output,
            generator,
            decode_entities(alternate.value),
            None,
            None,
            vec![],
            vec![],
            vec![],
            alternate.start,
            alternate.end,
            true,
            suppress_empty,
        );
    }
    if include_images {
        if let Some(url) = attribute(image, "src") {
            add(
                output,
                generator,
                decode_entities(url.value),
                None,
                Some(IMAGE_DESCRIPTION.to_owned()),
                vec![],
                vec![],
                vec![],
                url.start,
                url.end,
                true,
                suppress_empty,
            );
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn add(
    output: &mut Vec<Entry>,
    generator: &mut Generator,
    value: String,
    identity: Option<String>,
    description: Option<String>,
    tags: Vec<String>,
    image_starts: Vec<usize>,
    codes: Vec<Value>,
    start: usize,
    end: usize,
    attribute: bool,
    suppress_empty: bool,
) {
    if value.is_empty() {
        return;
    }
    let id = generator.next(&decode_entities(identity.as_deref().unwrap_or(&value)));
    if suppress_empty && (value.trim().is_empty() || value == "\u{00a0}") {
        return;
    }
    output.push(Entry {
        id,
        value,
        description,
        tags,
        image_starts,
        codes,
        start,
        end,
        attribute,
    });
}

fn document_part(source: &str, image_start: usize, preceding: &[Entry]) -> usize {
    let prefix = source[..image_start].to_ascii_lowercase();
    let extra = ["<!doctype", "<head", "<script", "<style"]
        .into_iter()
        .map(|marker| prefix.matches(marker).count())
        .sum::<usize>();
    1 + extra
        + preceding
            .iter()
            .filter(|entry| entry.description.as_deref() != Some(IMAGE_DESCRIPTION))
            .count()
}

fn attribute<'a>(tag: &Tag<'a>, selected: &str) -> Option<Attribute<'a>> {
    let bytes = tag.text.as_bytes();
    let mut index = 1;
    while index < bytes.len() {
        while index < bytes.len()
            && !matches!(bytes[index], b'a'..=b'z' | b'A'..=b'Z' | b'_' | b':')
        {
            index += 1;
        }
        let name_start = index;
        while index < bytes.len()
            && matches!(bytes[index], b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'_' | b':' | b'.' | b'-')
        {
            index += 1;
        }
        let name = &tag.text[name_start..index];
        while index < bytes.len() && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index >= bytes.len() || bytes[index] != b'=' {
            continue;
        }
        index += 1;
        while index < bytes.len() && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index >= bytes.len() || !matches!(bytes[index], b'\'' | b'"') {
            continue;
        }
        let quote = bytes[index];
        index += 1;
        let start = index;
        while index < bytes.len() && bytes[index] != quote {
            index += 1;
        }
        let end = index;
        if index < bytes.len() {
            index += 1;
        }
        if name.eq_ignore_ascii_case(selected) {
            return Some(Attribute {
                value: &tag.text[start..end],
                start: tag.start + start,
                end: tag.start + end,
            });
        }
    }
    None
}

fn tag(source: &str, start: usize) -> Result<Tag<'_>, ParseError> {
    let mut quoted = None;
    let mut end = start + 1;
    while end < source.len() {
        let character = source.as_bytes()[end];
        if quoted == Some(character) {
            quoted = None;
        } else if quoted.is_none() && matches!(character, b'\'' | b'"') {
            quoted = Some(character);
        } else if quoted.is_none() && character == b'>' {
            end += 1;
            let text = &source[start..end];
            let mut content = text[1..text.len() - 1].trim();
            let closing = content.starts_with('/');
            if closing {
                content = content[1..].trim();
            }
            let length = content
                .bytes()
                .take_while(|value| value.is_ascii_alphanumeric() || matches!(value, b'-' | b':'))
                .count();
            return Ok(Tag {
                name: content[..length].to_ascii_lowercase(),
                text,
                start,
                end,
                closing,
            });
        }
        end += 1;
    }
    Err(invalid("Unterminated HTML markup"))
}

fn index_of_ignore_case(source: &str, query: &str, start: usize) -> Option<usize> {
    index_of_ignore_case_until(source, query, start, source.len())
}

fn index_of_ignore_case_until(
    source: &str,
    query: &str,
    start: usize,
    end: usize,
) -> Option<usize> {
    source.as_bytes()[start..end]
        .windows(query.len())
        .position(|window| window.eq_ignore_ascii_case(query.as_bytes()))
        .map(|relative| start + relative)
}

fn translatable_element(name: &str) -> bool {
    matches!(
        name,
        "title" | "p" | "li" | "td" | "h1" | "h2" | "h3" | "label" | "button" | "span"
    )
}

fn collapse(value: &str) -> String {
    let mut result = String::new();
    let mut whitespace = false;
    for character in value
        .trim_matches(|value: char| value.is_ascii_whitespace())
        .chars()
    {
        if matches!(character, ' ' | '\t' | '\n' | '\r' | '\u{000c}') {
            whitespace = true;
        } else {
            if whitespace && !result.is_empty() {
                result.push(' ');
            }
            whitespace = false;
            result.push(character);
        }
    }
    result
}

fn decode_entities(value: &str) -> String {
    value
        .replace("&nbsp;", "\u{00a0}")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}

fn escape_text(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn escape_attribute(value: &str) -> String {
    escape_text(value)
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

struct Generator {
    previous: String,
    occurrences: HashMap<String, usize>,
}

impl Generator {
    fn new() -> Self {
        let previous = md5("");
        let occurrences = HashMap::from([(previous.clone(), 1)]);
        Self {
            previous,
            occurrences,
        }
    }

    fn next(&mut self, value: &str) -> String {
        let current = md5(value);
        let count = self.occurrences[&self.previous];
        let id = format!("{current}-{}-{count}", self.previous);
        *self.occurrences.entry(current.clone()).or_default() += 1;
        self.previous = current;
        id
    }
}

fn md5(value: &str) -> String {
    const SHIFTS: [u32; 64] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5,
        9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10,
        15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ];
    const CONSTANTS: [u32; 64] = [
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613,
        0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193,
        0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d,
        0x02441453, 0xd8a1e681, 0xe7d3fbc8, 0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
        0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681, 0x6d9d6122,
        0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa,
        0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665, 0xf4292244,
        0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb,
        0xeb86d391,
    ];
    let mut bytes = value.as_bytes().to_vec();
    let bit_length = (bytes.len() as u64).wrapping_mul(8);
    bytes.push(0x80);
    while bytes.len() % 64 != 56 {
        bytes.push(0);
    }
    bytes.extend(bit_length.to_le_bytes());
    let mut hash = [0x67452301_u32, 0xefcdab89, 0x98badcfe, 0x10325476];
    for chunk in bytes.chunks_exact(64) {
        let mut words = [0_u32; 16];
        for (index, word) in words.iter_mut().enumerate() {
            *word = u32::from_le_bytes(chunk[index * 4..index * 4 + 4].try_into().unwrap());
        }
        let [mut a, mut b, mut c, mut d] = hash;
        for index in 0..64 {
            let (function, word) = match index {
                0..=15 => ((b & c) | ((!b) & d), index),
                16..=31 => ((d & b) | ((!d) & c), (5 * index + 1) % 16),
                32..=47 => (b ^ c ^ d, (3 * index + 5) % 16),
                _ => (c ^ (b | (!d)), (7 * index) % 16),
            };
            let next = b.wrapping_add(
                a.wrapping_add(function)
                    .wrapping_add(CONSTANTS[index])
                    .wrapping_add(words[word])
                    .rotate_left(SHIFTS[index]),
            );
            a = d;
            d = c;
            c = b;
            b = next;
        }
        for (target, value) in hash.iter_mut().zip([a, b, c, d]) {
            *target = target.wrapping_add(value);
        }
    }
    hash.iter()
        .flat_map(|value| value.to_le_bytes())
        .map(|value| format!("{value:02x}"))
        .collect()
}

fn invalid(message: &str) -> ParseError {
    ParseError::new("INVALID_HTML", message)
}

fn invalid_skeleton(message: &str) -> ParseError {
    ParseError::new("INVALID_SKELETON", message)
}

fn invalid_markup(message: &str) -> ParseError {
    ParseError::new("INVALID_SKELETON_MARKUP", message)
}
