use crate::apple::{apple_string_message, parse_stringsdict_values, PlistValue};
use crate::model::{Catalog, FileFormat, ParseError};

const TRAILER_BYTES: usize = 32;
const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_OBJECTS: usize = 65_536;
const MAX_STRING_UNITS: usize = 1_000_000;
const MAX_DICTIONARY_DEPTH: usize = 64;

pub(crate) fn matches(bytes: &[u8]) -> bool {
    bytes.starts_with(b"bplist")
}

pub(crate) fn parse(bytes: &[u8]) -> Result<Catalog, ParseError> {
    let layout = Layout::read(bytes)?;
    let root = layout.offsets[layout.top];
    if bytes[root] & 0xf0 != 0xd0 {
        return Err(invalid_strings(
            "Apple binary strings require a top-level dictionary",
        ));
    }
    let (entries, content) = dictionary_layout(bytes, root, &layout)?;
    let mut catalog = Catalog::new(FileFormat::AppleStrings);
    for entry in 0..entries {
        let key = reference(
            bytes,
            content + entry * layout.reference_width,
            layout.reference_width,
            &layout.offsets,
            layout.object_end,
        )?;
        let value = reference(
            bytes,
            content + (entries + entry) * layout.reference_width,
            layout.reference_width,
            &layout.offsets,
            layout.object_end,
        )?;
        catalog.insert(
            string(bytes, key, layout.object_end, true, false)?,
            apple_string_message(
                &string(bytes, value, layout.object_end, false, false)?,
                None,
            ),
        )?;
    }
    Ok(catalog)
}

pub(crate) fn parse_stringsdict(bytes: &[u8]) -> Result<Catalog, ParseError> {
    let mut layout = Layout::read(bytes)?;
    let top = layout.top;
    let mut active = vec![false; layout.offsets.len()];
    parse_stringsdict_values(dictionary(bytes, top, 0, &mut layout, &mut active)?)
}

struct Layout {
    reference_width: usize,
    object_end: usize,
    top: usize,
    offsets: Vec<usize>,
    remaining_visits: usize,
}

impl Layout {
    fn read(bytes: &[u8]) -> Result<Self, ParseError> {
        if bytes.len() > MAX_BYTES {
            return Err(unsafe_plist("Binary property list exceeds its input limit"));
        }
        if bytes.len() < TRAILER_BYTES + 9 || !bytes.starts_with(b"bplist0") {
            return Err(invalid("Invalid binary property-list header or trailer"));
        }
        let trailer = bytes.len() - TRAILER_BYTES;
        let offset_width = usize::from(bytes[trailer + 6]);
        let reference_width = usize::from(bytes[trailer + 7]);
        if offset_width == 0 || reference_width == 0 {
            return Err(invalid("Invalid binary property-list integer widths"));
        }
        let declared_count = integer(bytes, trailer + 8, 8, bytes.len())?;
        if declared_count > MAX_OBJECTS as u64 {
            return Err(unsafe_plist(
                "Binary property list exceeds its object limit",
            ));
        }
        if declared_count == 0 {
            return Err(invalid("Binary property list contains no objects"));
        }
        let count = declared_count as usize;
        let top = usize::try_from(integer(bytes, trailer + 16, 8, bytes.len())?)
            .map_err(|_| invalid("Binary property-list top object overflows"))?;
        let object_end = usize::try_from(integer(bytes, trailer + 24, 8, bytes.len())?)
            .map_err(|_| invalid("Binary property-list offset table overflows"))?;
        if top >= count
            || object_end < 9
            || object_end > trailer
            || count
                .checked_mul(offset_width)
                .and_then(|length| object_end.checked_add(length))
                != Some(trailer)
            || !representable(reference_width, count as u64)
            || !representable(offset_width, object_end as u64)
        {
            return Err(invalid(
                "Invalid binary property-list trailer or offset table",
            ));
        }

        let mut offsets = Vec::with_capacity(count);
        for position in 0..count {
            let offset = usize::try_from(integer(
                bytes,
                object_end + position * offset_width,
                offset_width,
                trailer,
            )?)
            .map_err(|_| invalid("Binary property-list object offset overflows"))?;
            if !(8..object_end).contains(&offset) {
                return Err(invalid(
                    "Binary property-list object offset is outside its table",
                ));
            }
            offsets.push(offset);
        }
        Ok(Self {
            reference_width,
            object_end,
            top,
            offsets,
            remaining_visits: MAX_OBJECTS,
        })
    }
}

fn dictionary_layout(
    bytes: &[u8],
    offset: usize,
    layout: &Layout,
) -> Result<(usize, usize), ParseError> {
    let (entries, content) = length(bytes, offset, layout.object_end)?;
    if entries > MAX_OBJECTS {
        return Err(unsafe_plist(
            "Binary property-list dictionary exceeds its object limit",
        ));
    }
    if entries
        .checked_mul(2)
        .and_then(|references| references.checked_mul(layout.reference_width))
        .and_then(|length| content.checked_add(length))
        .is_none_or(|end| end > layout.object_end)
    {
        return Err(invalid(
            "Truncated binary property-list dictionary references",
        ));
    }
    Ok((entries, content))
}

fn dictionary(
    bytes: &[u8],
    index: usize,
    depth: usize,
    layout: &mut Layout,
    active: &mut [bool],
) -> Result<Vec<(String, PlistValue)>, ParseError> {
    if depth > MAX_DICTIONARY_DEPTH {
        return Err(unsafe_plist(
            "Binary property list exceeds its nesting limit",
        ));
    }
    visit(layout)?;
    if active[index] {
        return Err(unsafe_plist(
            "Binary property list contains a cyclic dictionary",
        ));
    }
    let offset = layout.offsets[index];
    if bytes[offset] & 0xf0 != 0xd0 {
        return Err(invalid_dict(
            "Apple binary stringsdict requires dictionary values",
        ));
    }
    let (entries, content) = dictionary_layout(bytes, offset, layout)?;
    active[index] = true;
    let result = (|| {
        let mut values = Vec::with_capacity(entries);
        for entry in 0..entries {
            let key_index = reference_index(
                bytes,
                content + entry * layout.reference_width,
                layout.reference_width,
                &layout.offsets,
                layout.object_end,
            )?;
            let value_index = reference_index(
                bytes,
                content + (entries + entry) * layout.reference_width,
                layout.reference_width,
                &layout.offsets,
                layout.object_end,
            )?;
            let key = string(
                bytes,
                layout.offsets[key_index],
                layout.object_end,
                true,
                true,
            )?;
            if values.iter().any(|(previous, _)| previous == &key) {
                return Err(ParseError::new(
                    "DUPLICATE_MESSAGE_ID",
                    "Duplicate binary property-list dictionary key",
                ));
            }
            let decoded = value(bytes, value_index, depth + 1, layout, active)?;
            values.push((key, decoded));
        }
        Ok(values)
    })();
    active[index] = false;
    result
}

fn value(
    bytes: &[u8],
    index: usize,
    depth: usize,
    layout: &mut Layout,
    active: &mut [bool],
) -> Result<PlistValue, ParseError> {
    visit(layout)?;
    let offset = layout.offsets[index];
    let marker = bytes[offset];
    match marker & 0xf0 {
        0x50 | 0x60 => Ok(PlistValue::String(string(
            bytes,
            offset,
            layout.object_end,
            false,
            true,
        )?)),
        0xd0 => Ok(PlistValue::Dictionary(dictionary(
            bytes, index, depth, layout, active,
        )?)),
        0xa0 => Ok(PlistValue::Array(array(
            bytes, index, depth, layout, active,
        )?)),
        0x40 => {
            let (count, start) = length(bytes, offset, layout.object_end)?;
            if count > MAX_STRING_UNITS || start + count > layout.object_end {
                return Err(invalid("Truncated or oversized binary property-list data"));
            }
            Ok(PlistValue::Data(bytes[start..start + count].to_vec()))
        }
        0x20 => {
            let width = 1usize << (marker & 0x0f);
            if !matches!(width, 4 | 8) || offset + 1 + width > layout.object_end {
                return Err(invalid("Invalid binary property-list floating-point width"));
            }
            let bits = integer(bytes, offset + 1, width, layout.object_end)?;
            Ok(PlistValue::Real(if width == 4 {
                f64::from(f32::from_bits(bits as u32))
            } else {
                f64::from_bits(bits)
            }))
        }
        0x30 => {
            if marker != 0x33 || offset + 1 + 8 > layout.object_end {
                return Err(invalid("Invalid binary property-list date width"));
            }
            let bits = integer(bytes, offset + 1, 8, layout.object_end)?;
            Ok(PlistValue::Date(crate::apple::binary_date(
                f64::from_bits(bits),
            )?))
        }
        0x10 => {
            let width = 1usize << (marker & 0x0f);
            if width > 16 || offset + 1 + width > layout.object_end {
                return Err(invalid(
                    "Unsupported or truncated binary property-list integer",
                ));
            }
            let number = if width < 8 {
                i128::from(integer(bytes, offset + 1, width, layout.object_end)?)
            } else if width == 8 {
                i128::from(integer(bytes, offset + 1, width, layout.object_end)? as i64)
            } else {
                let mut raw = [0_u8; 16];
                raw.copy_from_slice(&bytes[offset + 1..offset + 17]);
                i128::from_be_bytes(raw)
            };
            Ok(PlistValue::Integer(crate::apple::plist_integer(
                &number.to_string(),
            )?))
        }
        0x00 if marker == 0x08 || marker == 0x09 => Ok(PlistValue::Boolean(marker == 0x09)),
        0x70 | 0x90 | 0xe0 | 0xf0 => Err(invalid("Unsupported binary property-list object marker")),
        _ => Err(invalid_dict(
            "Unsupported binary stringsdict property-list value",
        )),
    }
}

fn array(
    bytes: &[u8],
    index: usize,
    depth: usize,
    layout: &mut Layout,
    active: &mut [bool],
) -> Result<Vec<PlistValue>, ParseError> {
    if depth > MAX_DICTIONARY_DEPTH {
        return Err(unsafe_plist(
            "Binary property list exceeds its collection nesting limit",
        ));
    }
    visit(layout)?;
    if active[index] {
        return Err(unsafe_plist("Binary property list contains a cyclic array"));
    }
    let offset = layout.offsets[index];
    let (entries, content) = length(bytes, offset, layout.object_end)?;
    if entries > MAX_OBJECTS
        || entries
            .checked_mul(layout.reference_width)
            .is_none_or(|size| content + size > layout.object_end)
    {
        return Err(invalid("Truncated or oversized binary property-list array"));
    }
    active[index] = true;
    let result = (|| {
        let mut values = Vec::with_capacity(entries);
        for entry in 0..entries {
            let child = reference_index(
                bytes,
                content + entry * layout.reference_width,
                layout.reference_width,
                &layout.offsets,
                layout.object_end,
            )?;
            values.push(value(bytes, child, depth + 1, layout, active)?);
        }
        Ok(values)
    })();
    active[index] = false;
    result
}

fn visit(layout: &mut Layout) -> Result<(), ParseError> {
    if layout.remaining_visits == 0 {
        return Err(unsafe_plist(
            "Binary property list exceeds its decoded object limit",
        ));
    }
    layout.remaining_visits -= 1;
    Ok(())
}

fn reference(
    bytes: &[u8],
    position: usize,
    width: usize,
    offsets: &[usize],
    object_end: usize,
) -> Result<usize, ParseError> {
    Ok(offsets[reference_index(bytes, position, width, offsets, object_end)?])
}

fn reference_index(
    bytes: &[u8],
    position: usize,
    width: usize,
    offsets: &[usize],
    object_end: usize,
) -> Result<usize, ParseError> {
    let index = usize::try_from(integer(bytes, position, width, object_end)?)
        .map_err(|_| invalid("Binary property-list object reference overflows"))?;
    if index >= offsets.len() {
        return Err(invalid(
            "Binary property-list object reference is outside its table",
        ));
    }
    Ok(index)
}

fn string(
    bytes: &[u8],
    offset: usize,
    object_end: usize,
    key: bool,
    stringsdict: bool,
) -> Result<String, ParseError> {
    let kind = bytes[offset] & 0xf0;
    if kind != 0x50 && kind != 0x60 {
        if kind == 0x70 || kind == 0x90 || kind >= 0xe0 {
            return Err(invalid("Unsupported binary property-list object marker"));
        }
        let message = if key {
            "Apple binary strings dictionary keys must be strings"
        } else {
            "Apple binary strings dictionary values must be strings"
        };
        return Err(if stringsdict {
            invalid_dict(message)
        } else {
            invalid_strings(message)
        });
    }
    let (count, content) = length(bytes, offset, object_end)?;
    if count > MAX_STRING_UNITS {
        return Err(unsafe_plist(
            "Binary property-list string exceeds its character limit",
        ));
    }
    let byte_count = count
        .checked_mul(if kind == 0x60 { 2 } else { 1 })
        .ok_or_else(|| invalid("Binary property-list string length overflows"))?;
    let end = content
        .checked_add(byte_count)
        .filter(|end| *end <= object_end)
        .ok_or_else(|| invalid("Truncated binary property-list string"))?;
    if kind == 0x50 {
        // Foundation's "ASCII" marker actually maps all bytes, including C1, as Latin-1.
        return Ok(bytes[content..end]
            .iter()
            .map(|byte| char::from(*byte))
            .collect());
    }
    let units: Vec<u16> = bytes[content..end]
        .chunks_exact(2)
        .map(|pair| u16::from_be_bytes([pair[0], pair[1]]))
        .collect();
    String::from_utf16(&units).map_err(|_| invalid("Malformed binary property-list UTF-16 string"))
}

fn length(bytes: &[u8], offset: usize, object_end: usize) -> Result<(usize, usize), ParseError> {
    let compact = bytes[offset] & 0x0f;
    if compact < 0x0f {
        return Ok((usize::from(compact), offset + 1));
    }
    let marker_offset = offset + 1;
    let marker = *bytes
        .get(marker_offset)
        .filter(|_| marker_offset < object_end)
        .ok_or_else(|| invalid("Truncated binary property-list extended length"))?;
    let exponent = marker & 0x0f;
    if marker & 0xf0 != 0x10 {
        return Err(invalid(
            "Invalid binary property-list extended length integer",
        ));
    }
    let width = 1usize << exponent;
    let count = usize::try_from(integer(bytes, marker_offset + 1, width, object_end)?)
        .map_err(|_| invalid("Binary property-list extended length overflows"))?;
    Ok((count, marker_offset + 1 + width))
}

fn integer(bytes: &[u8], offset: usize, width: usize, limit: usize) -> Result<u64, ParseError> {
    let end = offset
        .checked_add(width)
        .filter(|end| *end <= limit && width > 0)
        .ok_or_else(|| invalid("Truncated binary property-list integer"))?;
    Ok(bytes[offset..end]
        .iter()
        .fold(0u64, |value, byte| value << 8 | u64::from(*byte)))
}

fn representable(width: usize, value: u64) -> bool {
    width >= 8 || value < 1u64 << (8 * width)
}

fn invalid(message: &str) -> ParseError {
    ParseError::new("INVALID_APPLE_BINARY_PLIST", message)
}

fn invalid_strings(message: &str) -> ParseError {
    ParseError::new("INVALID_APPLE_STRINGS", message)
}

fn invalid_dict(message: &str) -> ParseError {
    ParseError::new("INVALID_APPLE_STRINGSDICT", message)
}

fn unsafe_plist(message: &str) -> ParseError {
    ParseError::new("UNSAFE_APPLE_BINARY_PLIST", message)
}
