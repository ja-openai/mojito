use crate::model::{FileFormat, ParseError};
use crate::source_skeleton::{
    stringsdict_expected_paths, SourceSkeleton, SourceSlot, StringsdictIdentity,
};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt::Write;

const ENCODING: &str = "BINARY_PLIST";

pub(crate) fn extract(format: FileFormat, source: &[u8]) -> Result<SourceSkeleton, ParseError> {
    let catalog = crate::parse(format, source)?;
    let expected = if format == FileFormat::AppleStringsdict {
        stringsdict_expected_paths(&catalog)?
    } else {
        catalog
            .messages
            .keys()
            .map(|id| {
                (
                    vec![id.clone()],
                    StringsdictIdentity {
                        id: id.clone(),
                        selector: None,
                        variant: None,
                    },
                )
            })
            .collect()
    };
    let mut layout = Layout::read(source, expected);
    layout.visit(layout.top, &[], false, None, &mut HashSet::new())?;
    if layout.owned.len() != layout.expected.len() {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Missing owned binary property-list value",
        ));
    }
    let mut slots = Vec::with_capacity(layout.owned.len());
    for owned in &layout.owned {
        let object = owned.object;
        let identity = &owned.identity;
        let start = layout.offsets[object];
        let (count, content) = layout.length(start);
        let width = if source[start] & 0xf0 == 0x60 { 2 } else { 1 };
        let end = content + count * width;
        if layout
            .offsets
            .iter()
            .enumerate()
            .any(|(index, &offset)| index != object && (start..end).contains(&offset))
        {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Overlapping binary property-list object ownership",
            ));
        }
        let shared = layout.references[object] != 1 || layout.key_objects.contains(&object);
        slots.push(SourceSlot {
            id: identity.id.clone(),
            selector: identity.selector.clone(),
            variant: identity.variant.clone(),
            start: if shared { owned.reference_start } else { start },
            end: if shared {
                owned.reference_start + layout.reference_width
            } else {
                end
            },
            apple_object_index: shared.then_some(object),
        });
    }
    slots.sort_by_key(|slot| slot.start);
    let mut hex = String::with_capacity(source.len() * 2);
    for byte in source {
        write!(&mut hex, "{byte:02x}").expect("writing a String never fails");
    }
    Ok(SourceSkeleton {
        schema_version: 1,
        source_format: format.id(),
        encoding: ENCODING.to_owned(),
        source: hex,
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
    if skeleton.schema_version != 1 || skeleton.encoding != ENCODING {
        return Err(error(
            "INVALID_SKELETON",
            "Invalid binary property-list source skeleton",
        ));
    }
    let format = FileFormat::from_id(skeleton.source_format).ok_or_else(|| {
        error(
            "INVALID_SKELETON",
            "Unsupported binary property-list source format",
        )
    })?;
    if !matches!(
        format,
        FileFormat::AppleStrings | FileFormat::AppleStringsdict
    ) {
        return Err(error(
            "INVALID_SKELETON",
            "Unsupported binary property-list source format",
        ));
    }
    let source = decode_hex(&skeleton.source)?;
    if extract(format, &source)?.slots != skeleton.slots {
        return Err(error(
            "INVALID_SKELETON",
            "Binary property-list object ownership was changed",
        ));
    }
    let mut known = HashSet::new();
    for slot in &skeleton.slots {
        if !known.insert(slot.key()) {
            return Err(error(
                "INVALID_SKELETON",
                "Duplicated binary property-list value slot",
            ));
        }
    }
    if translations.keys().any(|key| !known.contains(key)) {
        return Err(error(
            "UNKNOWN_SKELETON_SLOT",
            "Translation has no binary property-list value",
        ));
    }
    if translations.is_empty() {
        return Ok(source);
    }
    let catalog = crate::parse(format, &source)?;
    let trailer = source.len() - 32;
    let old_width = usize::from(source[trailer + 6]);
    let old_reference_width = usize::from(source[trailer + 7]);
    let count = unsigned(&source, trailer + 8, 8) as usize;
    let old_end = unsigned(&source, trailer + 24, 8) as usize;
    let offsets: Vec<usize> = (0..count)
        .map(|index| unsigned(&source, old_end + index * old_width, old_width) as usize)
        .collect();
    let mut replacements: HashMap<usize, (usize, Vec<u8>)> = HashMap::new();
    let mut cloned_references = HashMap::new();
    let mut clones = Vec::new();
    for slot in &skeleton.slots {
        if let Some(translation) = translations.get(&slot.key()) {
            let message = catalog.messages.get(&slot.id).ok_or_else(|| {
                error(
                    "INVALID_SKELETON",
                    "Missing binary property-list message descriptor",
                )
            })?;
            if format == FileFormat::AppleStringsdict && !has_category(message, slot) {
                return Err(error(
                    "INVALID_SKELETON",
                    "Missing binary property-list plural category",
                ));
            }
            let value = if format == FileFormat::AppleStringsdict {
                let selector = slot.selector.as_deref().or_else(|| {
                    message
                        .metadata
                        .as_ref()
                        .and_then(|metadata| metadata.get("pluralVariable"))
                        .and_then(serde_json::Value::as_str)
                });
                match (selector, slot.variant.as_deref()) {
                    (Some(selector), Some(category)) => {
                        crate::apple_stringsdict_writer::restore_scoped(
                            translation,
                            message,
                            selector,
                            category,
                        )
                    }
                    _ => crate::apple_stringsdict_writer::restore(translation, message),
                }
            } else {
                crate::apple_writer::native_value(message, translation)
            };
            let object = slot.apple_object_index.unwrap_or_else(|| {
                offsets
                    .iter()
                    .position(|&offset| offset == slot.start)
                    .expect("re-extracted sidecar owns a real Foundation object")
            });
            let encoded = encode_string(&value, source[offsets[object]]);
            if slot.apple_object_index.is_some() {
                cloned_references.insert(slot.start, count + clones.len());
                clones.push(encoded);
            } else {
                replacements.insert(slot.start, (slot.end, encoded));
            }
        }
    }
    let object_count = count + clones.len();
    if object_count > 65_536 {
        return Err(error(
            "UNSAFE_APPLE_BINARY_PLIST",
            "Binary object cloning exceeds its object limit",
        ));
    }
    let mut reference_width = old_reference_width;
    while !representable(reference_width, object_count as u64) {
        reference_width += 1;
    }
    let mut ownership = Layout::read(&source, HashMap::new());
    ownership.visit(ownership.top, &[], false, None, &mut HashSet::new())?;
    if reference_width != old_reference_width
        && offsets.iter().enumerate().any(|(index, &offset)| {
            matches!(source[offset] & 0xf0, 0xd0 | 0xa0) && !ownership.containers.contains(&index)
        })
    {
        return Err(error(
            "UNSUPPORTED_SKELETON_SOURCE",
            "Unreachable binary containers cannot safely change their reference width",
        ));
    }
    for (&start, &object) in &ownership.reference_sites {
        if let Some(&clone) = cloned_references.get(&start) {
            let mut value = Vec::with_capacity(reference_width);
            write_integer(&mut value, clone as u64, reference_width);
            replacements.insert(start, (start + old_reference_width, value));
        } else if reference_width != old_reference_width {
            let mut value = Vec::with_capacity(reference_width);
            write_integer(&mut value, object as u64, reference_width);
            replacements.insert(start, (start + old_reference_width, value));
        }
    }
    let mut ordered: Vec<(&usize, &(usize, Vec<u8>))> = replacements.iter().collect();
    ordered.sort_unstable_by_key(|(start, _)| **start);
    let mut output = Vec::with_capacity(source.len());
    let mut cursor = 0;
    for (&start, (end, value)) in &ordered {
        if start < cursor || *end > old_end {
            return Err(error(
                "INVALID_SKELETON",
                "Overlapping binary structural ownership",
            ));
        }
        output.extend_from_slice(&source[cursor..start]);
        output.extend_from_slice(value);
        cursor = *end;
    }
    output.extend_from_slice(&source[cursor..old_end]);
    let mut clone_offsets = Vec::with_capacity(clones.len());
    for clone in clones {
        clone_offsets.push(output.len());
        output.extend_from_slice(&clone);
    }
    let new_end = output.len();
    let mut width = old_width;
    while width < 8 && !representable(width, new_end as u64) {
        width += 1;
    }
    for offset in offsets {
        let mut delta: isize = 0;
        for (&start, (end, value)) in &ordered {
            if start >= offset {
                break;
            }
            if *end <= offset {
                delta += value.len() as isize - (*end - start) as isize;
            }
        }
        write_integer(
            &mut output,
            offset.checked_add_signed(delta).unwrap() as u64,
            width,
        );
    }
    for offset in clone_offsets {
        write_integer(&mut output, offset as u64, width);
    }
    let mut final_trailer = source[trailer..].to_vec();
    final_trailer[6] = width as u8;
    final_trailer[7] = reference_width as u8;
    final_trailer[8..16].copy_from_slice(&(object_count as u64).to_be_bytes());
    final_trailer[24..32].copy_from_slice(&(new_end as u64).to_be_bytes());
    output.extend_from_slice(&final_trailer);
    Ok(output)
}

struct Layout<'a> {
    source: &'a [u8],
    expected: HashMap<Vec<String>, StringsdictIdentity>,
    top: usize,
    reference_width: usize,
    offsets: Vec<usize>,
    references: Vec<usize>,
    key_objects: HashSet<usize>,
    owned: Vec<OwnedObject>,
    assigned: HashSet<String>,
    reference_sites: HashMap<usize, usize>,
    containers: HashSet<usize>,
}

impl<'a> Layout<'a> {
    fn read(source: &'a [u8], expected: HashMap<Vec<String>, StringsdictIdentity>) -> Self {
        let trailer = source.len() - 32;
        let offset_width = usize::from(source[trailer + 6]);
        let reference_width = usize::from(source[trailer + 7]);
        let count = unsigned(source, trailer + 8, 8) as usize;
        let top = unsigned(source, trailer + 16, 8) as usize;
        let end = unsigned(source, trailer + 24, 8) as usize;
        let offsets = (0..count)
            .map(|position| unsigned(source, end + position * offset_width, offset_width) as usize)
            .collect();
        Self {
            source,
            expected,
            top,
            reference_width,
            offsets,
            references: vec![0; count],
            key_objects: HashSet::new(),
            owned: Vec::new(),
            assigned: HashSet::new(),
            reference_sites: HashMap::new(),
            containers: HashSet::new(),
        }
    }

    fn visit(
        &mut self,
        index: usize,
        path: &[String],
        key: bool,
        reference_start: Option<usize>,
        active: &mut HashSet<usize>,
    ) -> Result<(), ParseError> {
        self.references[index] += 1;
        if key {
            self.key_objects.insert(index);
        }
        let offset = self.offsets[index];
        let kind = self.source[offset] & 0xf0;
        if kind == 0x50 || kind == 0x60 {
            if !key {
                if let Some(identity) = self.expected.get(path) {
                    let name = format!(
                        "{}#{:?}#{:?}",
                        identity.id, identity.selector, identity.variant
                    );
                    if !self.assigned.insert(name) || reference_start.is_none() {
                        return Err(error(
                            "UNSUPPORTED_SKELETON_SOURCE",
                            "Repeated binary property-list paths have ambiguous translation ownership",
                        ));
                    }
                    self.owned.push(OwnedObject {
                        object: index,
                        identity: identity.clone(),
                        reference_start: reference_start.unwrap(),
                    });
                }
            }
            return Ok(());
        }
        if kind != 0xd0 && kind != 0xa0 {
            return Ok(());
        }
        if !active.insert(index) {
            return Err(error(
                "UNSUPPORTED_SKELETON_SOURCE",
                "Cyclic binary property-list object ownership",
            ));
        }
        self.containers.insert(index);
        let (count, content) = self.length(offset);
        if kind == 0xd0 {
            for entry in 0..count {
                let key_start = content + entry * self.reference_width;
                let value_start = content + (count + entry) * self.reference_width;
                let key_index = self.reference(key_start);
                let value_index = self.reference(value_start);
                self.reference_sites.insert(key_start, key_index);
                self.reference_sites.insert(value_start, value_index);
                self.visit(key_index, path, true, Some(key_start), active)?;
                let mut child = path.to_vec();
                child.push(self.string(key_index));
                self.visit(value_index, &child, false, Some(value_start), active)?;
            }
        } else {
            for entry in 0..count {
                let start = content + entry * self.reference_width;
                let object = self.reference(start);
                self.reference_sites.insert(start, object);
                self.visit(object, path, false, Some(start), active)?;
            }
        }
        active.remove(&index);
        Ok(())
    }

    fn reference(&self, start: usize) -> usize {
        unsigned(self.source, start, self.reference_width) as usize
    }

    fn string(&self, object: usize) -> String {
        let offset = self.offsets[object];
        let (count, start) = self.length(offset);
        if self.source[offset] & 0xf0 == 0x50 {
            self.source[start..start + count]
                .iter()
                .map(|byte| char::from(*byte))
                .collect()
        } else {
            let units: Vec<u16> = self.source[start..start + count * 2]
                .chunks_exact(2)
                .map(|unit| u16::from_be_bytes([unit[0], unit[1]]))
                .collect();
            String::from_utf16(&units).expect("semantic parser validated UTF-16")
        }
    }

    fn length(&self, offset: usize) -> (usize, usize) {
        let compact = usize::from(self.source[offset] & 0x0f);
        if compact < 15 {
            return (compact, offset + 1);
        }
        let width = 1usize << (self.source[offset + 1] & 0x0f);
        (
            unsigned(self.source, offset + 2, width) as usize,
            offset + 2 + width,
        )
    }
}

struct OwnedObject {
    object: usize,
    identity: StringsdictIdentity,
    reference_start: usize,
}

fn encode_string(value: &str, original: u8) -> Vec<u8> {
    let latin = original & 0xf0 == 0x50 && value.is_ascii();
    let content: Vec<u8> = if latin {
        value.chars().map(|character| character as u8).collect()
    } else {
        value.encode_utf16().flat_map(u16::to_be_bytes).collect()
    };
    let count = if latin {
        content.len()
    } else {
        content.len() / 2
    };
    let marker = if latin { 0x50 } else { 0x60 };
    let mut result = Vec::with_capacity(content.len() + 10);
    if count < 15 {
        result.push(marker | count as u8);
    } else {
        result.push(marker | 15);
        let width: usize = if count <= 0xff {
            1
        } else if count <= 0xffff {
            2
        } else {
            4
        };
        result.push(0x10 | width.trailing_zeros() as u8);
        write_integer(&mut result, count as u64, width);
    }
    result.extend(content);
    result
}

fn decode_hex(value: &str) -> Result<Vec<u8>, ParseError> {
    if value.len() % 2 != 0 || !value.bytes().all(|item| item.is_ascii_hexdigit()) {
        return Err(error(
            "INVALID_SKELETON",
            "Malformed binary property-list source skeleton",
        ));
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).map_err(|_| {
                error(
                    "INVALID_SKELETON",
                    "Malformed binary property-list source skeleton",
                )
            })
        })
        .collect()
}

fn write_integer(output: &mut Vec<u8>, value: u64, width: usize) {
    for position in (0..width).rev() {
        output.push(if position >= 8 {
            0
        } else {
            (value >> (position * 8)) as u8
        });
    }
}

fn unsigned(source: &[u8], start: usize, width: usize) -> u64 {
    source[start..start + width]
        .iter()
        .fold(0u64, |value, byte| value << 8 | u64::from(*byte))
}

fn representable(width: usize, value: u64) -> bool {
    width >= 8 || value < 1u64 << (width * 8)
}

fn has_category(message: &crate::model::Message, slot: &SourceSlot) -> bool {
    if let Some(selector) = &slot.selector {
        slot.variant.as_ref().is_some_and(|variant| {
            message
                .metadata
                .as_ref()
                .and_then(|metadata| metadata.get("applePluralRules"))
                .and_then(|rules| rules.get(selector))
                .and_then(|definition| definition.get("variants"))
                .and_then(serde_json::Value::as_object)
                .is_some_and(|variants| variants.contains_key(variant))
        })
    } else {
        slot.variant.as_ref().is_none_or(|variant| {
            message
                .variants
                .as_ref()
                .is_some_and(|variants| variants.contains_key(variant))
        })
    }
}

fn error(code: &'static str, message: &str) -> ParseError {
    ParseError::new(code, message)
}
