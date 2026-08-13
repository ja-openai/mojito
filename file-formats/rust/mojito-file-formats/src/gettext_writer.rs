use crate::model::{Catalog, FileFormat, Message, ParseError, Placeholder};
use regex::Regex;
use serde_json::{Map, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt::Write;
use std::sync::OnceLock;

pub(crate) fn write(catalog: &Catalog) -> Result<String, ParseError> {
    if catalog.source_format != FileFormat::GettextPo.id() {
        return Err(ParseError::new(
            "INVALID_SOURCE_FORMAT",
            "Gettext writer requires a gettext PO catalog",
        ));
    }
    let mut output = String::new();
    let mut entries = catalog.messages.iter().collect::<Vec<_>>();
    let mut headers: HashMap<String, &Map<String, Value>> = HashMap::new();
    for (id, message) in &entries {
        let selected_domain = domain(message)?;
        if let Some(header) = domain_header(message)? {
            let key = selected_domain.unwrap_or("messages").to_owned();
            if headers
                .insert(key, header)
                .is_some_and(|previous| previous != header)
            {
                return Err(ParseError::new(
                    "INCONSISTENT_GETTEXT_DOMAIN_HEADER",
                    "One gettext domain cannot contain conflicting header metadata",
                ));
            }
        }
        validate_identity(id, message, selected_domain)?;
    }
    entries.sort_by(|(left_key, left), (right_key, right)| {
        domain(left)
            .expect("validated gettext domain")
            .cmp(&domain(right).expect("validated gettext domain"))
            .then_with(|| left_key.cmp(right_key))
    });
    if entries.is_empty()
        || entries.iter().any(|(_, message)| {
            domain(message).expect("validated domain").is_none()
                || domain_header(message)
                    .expect("validated domain header")
                    .is_none()
        })
    {
        let default_header = headers.get("messages");
        append_header(
            &mut output,
            default_header
                .and_then(|header| header.get("locale"))
                .and_then(Value::as_str)
                .or(catalog.locale.as_deref()),
            default_header
                .and_then(|header| header.get("pluralForms"))
                .and_then(Value::as_object)
                .or(plural_forms(catalog)?),
            default_header
                .and_then(|header| header.get("fields"))
                .and_then(Value::as_array)
                .map(Vec::as_slice),
        )?;
    }
    let mut current_domain = None;
    for (key, message) in entries {
        let metadata = message.metadata.as_ref();
        let selected_domain = domain(message)?;
        if selected_domain != current_domain {
            if !output.is_empty() {
                output.push('\n');
            }
            output.push_str("domain ");
            output.push_str(&quote(selected_domain.expect("null domains sort first"))?);
            output.push('\n');
            current_domain = selected_domain;
            if let Some(header) = selected_domain.and_then(|name| headers.get(name)) {
                append_header(
                    &mut output,
                    header.get("locale").and_then(Value::as_str),
                    header.get("pluralForms").and_then(Value::as_object),
                    header
                        .get("fields")
                        .and_then(Value::as_array)
                        .map(Vec::as_slice),
                )?;
            }
        }
        output.push('\n');
        for comment in strings(metadata.and_then(|value| value.get("translatorComments"))) {
            append_comment(&mut output, "# ", comment)?;
        }
        if let Some(description) = &message.description {
            append_comment(&mut output, "#. ", description)?;
        }
        let references = strings(metadata.and_then(|value| value.get("references")));
        if !references.is_empty() {
            if references.iter().any(|reference| {
                reference
                    .trim_matches(crate::model::java_whitespace)
                    .is_empty()
                    || reference.chars().any(crate::model::java_whitespace)
            }) {
                return Err(ParseError::new(
                    "INVALID_GETTEXT_REFERENCE",
                    "Unsafe gettext source reference",
                ));
            }
            output.push_str("#: ");
            output.push_str(&references.join(" "));
            output.push('\n');
        }
        let flags = strings(metadata.and_then(|value| value.get("flags")));
        if !flags.is_empty() {
            if flags.iter().any(|flag| {
                flag.trim_matches(crate::model::java_whitespace).is_empty()
                    || flag.contains([',', '\n'])
            }) {
                return Err(ParseError::new(
                    "INVALID_GETTEXT_FLAG",
                    "Unsafe gettext format flag",
                ));
            }
            output.push_str("#, ");
            output.push_str(&flags.join(", "));
            output.push('\n');
        }
        if let Some(previous) = metadata.and_then(|value| value.get("gettextPrevious")) {
            append_previous(&mut output, previous)?;
        }
        let context = metadata
            .and_then(|value| value.get("context"))
            .and_then(Value::as_str);
        if let Some(context) = context {
            output.push_str("msgctxt ");
            output.push_str(&quote(context)?);
            output.push('\n');
        }
        let fallback = context.map(|_| restore(message, &message.default_message, None));
        let source = metadata
            .and_then(|value| value.get("sourceMessage"))
            .and_then(Value::as_str)
            .or(fallback.as_deref())
            .or_else(|| {
                metadata
                    .and_then(|value| value.get("gettextOriginalId"))
                    .and_then(Value::as_str)
            })
            .unwrap_or(key);
        output.push_str("msgid ");
        output.push_str(&quote(source)?);
        output.push('\n');
        if let Some(variants) = &message.variants {
            let source_plural = metadata
                .and_then(|value| value.get("sourcePlural"))
                .and_then(Value::as_str)
                .ok_or_else(|| {
                    ParseError::new(
                        "INVALID_GETTEXT_PLURAL_METADATA",
                        "Gettext plural writing requires native plural source",
                    )
                })?;
            let indexed = metadata
                .and_then(|value| value.get("gettextPluralIndexes"))
                .and_then(Value::as_object)
                .ok_or_else(|| {
                    ParseError::new(
                        "INVALID_GETTEXT_PLURAL_METADATA",
                        "Gettext plural writing requires native plural indexes",
                    )
                })?;
            let mut indexes = BTreeMap::new();
            for (index, selector) in indexed {
                let index = index.parse::<usize>().map_err(|_| {
                    ParseError::new(
                        "INVALID_GETTEXT_PLURAL_METADATA",
                        "Invalid native gettext plural index",
                    )
                })?;
                let selector = selector.as_str().ok_or_else(|| {
                    ParseError::new(
                        "INVALID_GETTEXT_PLURAL_METADATA",
                        "Invalid native gettext plural selector",
                    )
                })?;
                if !variants.contains_key(selector) {
                    return Err(ParseError::new(
                        "INVALID_GETTEXT_PLURAL_METADATA",
                        "Native gettext plural selector has no variant",
                    ));
                }
                indexes.insert(index, selector);
            }
            let untranslated =
                integers(metadata.and_then(|value| value.get("gettextUntranslatedIndexes")));
            output.push_str("msgid_plural ");
            output.push_str(&quote(source_plural)?);
            output.push('\n');
            for (index, selector) in indexes {
                let value = if untranslated.contains(&index) {
                    String::new()
                } else {
                    restore(message, &variants[selector], Some(index))
                };
                writeln!(&mut output, "msgstr[{index}] {}", quote(&value)?)
                    .expect("writing to String");
            }
        } else {
            let translation = if metadata.and_then(|value| value.get("gettextUntranslated"))
                == Some(&Value::Bool(true))
            {
                String::new()
            } else {
                restore(message, &message.default_message, None)
            };
            output.push_str("msgstr ");
            output.push_str(&quote(&translation)?);
            output.push('\n');
        }
    }
    Ok(output)
}

fn append_header(
    output: &mut String,
    locale: Option<&str>,
    forms: Option<&Map<String, Value>>,
    fields: Option<&[Value]>,
) -> Result<(), ParseError> {
    output.push_str("msgid \"\"\nmsgstr \"\"\n");
    output.push_str(&quote("Content-Type: text/plain; charset=UTF-8\n")?);
    output.push('\n');
    if let Some(locale) = locale {
        output.push_str(&quote(&format!(
            "Language: {}\n",
            locale.replace('-', "_")
        ))?);
        output.push('\n');
    }
    if let Some(forms) = forms {
        let count = forms
            .get("nplurals")
            .and_then(Value::as_u64)
            .ok_or_else(|| {
                ParseError::new(
                    "INVALID_GETTEXT_PLURAL_METADATA",
                    "Gettext plural metadata has no form count",
                )
            })?;
        let expression = forms
            .get("expression")
            .and_then(Value::as_str)
            .ok_or_else(|| {
                ParseError::new(
                    "INVALID_GETTEXT_PLURAL_METADATA",
                    "Gettext plural metadata has no formula",
                )
            })?;
        output.push_str(&quote(&format!(
            "Plural-Forms: nplurals={count}; plural={expression};\n"
        ))?);
        output.push('\n');
    }
    if let Some(fields) = fields {
        for field in fields {
            let field = field.as_object().expect("validated native header field");
            let name = field["name"]
                .as_str()
                .expect("validated native header name");
            let value = field["value"]
                .as_str()
                .expect("validated native header value");
            let mut lines = value.split('\n');
            output.push_str(&quote(&format!(
                "{name}: {}\n",
                lines.next().expect("split always contains a value")
            ))?);
            output.push('\n');
            for continuation in lines {
                output.push_str(&quote(&format!(" {continuation}\n"))?);
                output.push('\n');
            }
        }
    }
    Ok(())
}

fn domain_header(message: &Message) -> Result<Option<&Map<String, Value>>, ParseError> {
    let Some(value) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("gettextDomainHeader"))
    else {
        return Ok(None);
    };
    let header = value.as_object().ok_or_else(invalid_domain_header)?;
    if header
        .keys()
        .any(|key| key != "locale" && key != "pluralForms" && key != "fields")
        || header.get("locale").is_some_and(|locale| {
            locale
                .as_str()
                .is_none_or(|value| value.trim_matches(crate::model::java_whitespace).is_empty())
        })
    {
        return Err(invalid_domain_header());
    }
    if let Some(value) = header.get("pluralForms") {
        let forms = value.as_object().ok_or_else(invalid_domain_header)?;
        if !forms
            .get("nplurals")
            .and_then(Value::as_u64)
            .is_some_and(|count| (1..=100).contains(&count))
            || forms
                .get("expression")
                .and_then(Value::as_str)
                .is_none_or(|expression| {
                    expression
                        .trim_matches(crate::model::java_whitespace)
                        .is_empty()
                })
        {
            return Err(invalid_domain_header());
        }
    }
    if let Some(value) = header.get("fields") {
        let fields = value.as_array().ok_or_else(invalid_domain_header)?;
        if fields.is_empty() {
            return Err(invalid_domain_header());
        }
        for value in fields {
            let field = value.as_object().ok_or_else(invalid_domain_header)?;
            let name = field
                .get("name")
                .and_then(Value::as_str)
                .ok_or_else(invalid_domain_header)?;
            let value = field
                .get("value")
                .and_then(Value::as_str)
                .ok_or_else(invalid_domain_header)?;
            if field.len() != 2
                || name.contains([':', '\n', '\r', '\0'])
                || name.eq_ignore_ascii_case("Content-Type")
                || name.eq_ignore_ascii_case("Language")
                || name.eq_ignore_ascii_case("Plural-Forms")
                || value.contains(['\r', '\0'])
                || value.split('\n').skip(1).any(|line| line.contains(':'))
            {
                return Err(invalid_domain_header());
            }
        }
    }
    Ok(Some(header))
}

fn invalid_domain_header() -> ParseError {
    ParseError::new(
        "INVALID_GETTEXT_DOMAIN_HEADER",
        "Invalid GNU gettext domain header metadata",
    )
}

fn validate_identity(
    id: &str,
    message: &Message,
    selected_domain: Option<&str>,
) -> Result<(), ParseError> {
    let Some(value) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("gettextOriginalId"))
    else {
        return Ok(());
    };
    let original = value.as_str().ok_or_else(invalid_domain_identity)?;
    if original
        .trim_matches(crate::model::java_whitespace)
        .is_empty()
        || id
            != format!(
                "{original}@domain={}",
                crate::gettext::escape_domain(selected_domain.unwrap_or("messages"))
            )
        || message
            .metadata
            .as_ref()
            .and_then(|metadata| metadata.get("context"))
            .and_then(Value::as_str)
            .is_some_and(|context| context != original)
    {
        return Err(invalid_domain_identity());
    }
    Ok(())
}

fn invalid_domain_identity() -> ParseError {
    ParseError::new(
        "INVALID_GETTEXT_DOMAIN_ID",
        "Invalid domain-qualified GNU gettext identity",
    )
}

fn domain(message: &Message) -> Result<Option<&str>, ParseError> {
    let Some(value) = message
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.get("gettextDomain"))
    else {
        return Ok(None);
    };
    let domain = value.as_str().ok_or_else(|| {
        ParseError::new(
            "INVALID_GETTEXT_DOMAIN",
            "Invalid GNU gettext translation domain",
        )
    })?;
    if domain.chars().any(|value| {
        value == '\0' || crate::model::java_whitespace(value) || matches!(value, '/' | '\\')
    }) {
        return Err(ParseError::new(
            "INVALID_GETTEXT_DOMAIN",
            "Invalid GNU gettext translation domain",
        ));
    }
    Ok(Some(domain))
}

fn append_previous(output: &mut String, metadata: &Value) -> Result<(), ParseError> {
    let previous = metadata.as_object().ok_or_else(invalid_previous)?;
    if previous
        .keys()
        .any(|key| key != "context" && key != "id" && key != "plural")
    {
        return Err(invalid_previous());
    }
    let id = previous
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(invalid_previous)?;
    if id.contains('\0') {
        return Err(invalid_previous());
    }
    for name in ["context", "plural"] {
        if previous
            .get(name)
            .is_some_and(|value| value.as_str().is_none_or(|text| text.contains('\0')))
        {
            return Err(invalid_previous());
        }
    }
    if let Some(context) = previous.get("context").and_then(Value::as_str) {
        output.push_str("#| msgctxt ");
        output.push_str(&quote(context)?);
        output.push('\n');
    }
    output.push_str("#| msgid ");
    output.push_str(&quote(id)?);
    output.push('\n');
    if let Some(plural) = previous.get("plural").and_then(Value::as_str) {
        output.push_str("#| msgid_plural ");
        output.push_str(&quote(plural)?);
        output.push('\n');
    }
    Ok(())
}

fn invalid_previous() -> ParseError {
    ParseError::new(
        "INVALID_GETTEXT_PREVIOUS",
        "Invalid previous GNU gettext message history",
    )
}

fn plural_forms(catalog: &Catalog) -> Result<Option<&Map<String, Value>>, ParseError> {
    let mut selected = None;
    for message in catalog.messages.values() {
        let Some(current) = message
            .metadata
            .as_ref()
            .filter(|metadata| !metadata.contains_key("gettextDomainHeader"))
            .and_then(|metadata| metadata.get("gettextPluralForms"))
            .and_then(Value::as_object)
        else {
            continue;
        };
        if selected.is_some_and(|previous: &Map<String, Value>| previous != current) {
            return Err(ParseError::new(
                "INCONSISTENT_GETTEXT_PLURAL_FORMS",
                "Gettext catalogs cannot contain conflicting plural formulas",
            ));
        }
        selected = Some(current);
    }
    Ok(selected)
}

pub(crate) fn restore(message: &Message, value: &str, plural_index: Option<usize>) -> String {
    let mut placeholders: HashMap<&str, Vec<&Placeholder>> = HashMap::new();
    for placeholder in message.placeholders.iter().flatten() {
        placeholders
            .entry(&placeholder.name)
            .or_default()
            .push(placeholder);
    }
    let metadata = message.metadata.as_ref();
    let percent_metadata = plural_index.map_or_else(
        || metadata.and_then(|value| value.get("gettextEscapedPercents")),
        |index| nested(metadata, "gettextPluralEscapedPercents", index),
    );
    let escaped_percents = integers(percent_metadata);
    let separator_metadata = plural_index.map_or_else(
        || metadata.and_then(|value| value.get("gettextPrintfLineSeparators")),
        |index| nested(metadata, "gettextPluralPrintfLineSeparators", index),
    );
    let line_separators: HashMap<usize, &str> = separator_metadata
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|separator| {
            Some((
                usize::try_from(separator.get("position")?.as_u64()?).ok()?,
                separator.get("source")?.as_str()?,
            ))
        })
        .collect();
    let mut occurrences = HashMap::new();
    let mut output = String::new();
    let mut previous = 0;
    let mut scalar_offset = 0;
    for captures in argument_pattern().captures_iter(value) {
        let matched = captures.get(0).expect("argument match");
        let name = captures.get(1).expect("argument name").as_str();
        let Some(choices) = placeholders.get(name) else {
            continue;
        };
        scalar_offset = append_literal(
            &mut output,
            &value[previous..matched.start()],
            scalar_offset,
            &escaped_percents,
            &line_separators,
        );
        let occurrence = occurrences.entry(name).or_insert(0_usize);
        output.push_str(&choices[(*occurrence).min(choices.len() - 1)].source);
        *occurrence += 1;
        scalar_offset += matched.as_str().chars().count();
        previous = matched.end();
    }
    append_literal(
        &mut output,
        &value[previous..],
        scalar_offset,
        &escaped_percents,
        &line_separators,
    );
    output
}

fn append_literal(
    output: &mut String,
    value: &str,
    mut scalar_offset: usize,
    escaped_percents: &HashSet<usize>,
    line_separators: &HashMap<usize, &str>,
) -> usize {
    for character in value.chars() {
        if character == '%' && escaped_percents.contains(&scalar_offset) {
            output.push_str("%%");
        } else if character == '\n' {
            if let Some(source) = line_separators.get(&scalar_offset) {
                output.push_str(source);
            } else {
                output.push(character);
            }
        } else {
            output.push(character);
        }
        scalar_offset += 1;
    }
    scalar_offset
}

fn nested<'a>(
    metadata: Option<&'a Map<String, Value>>,
    name: &str,
    index: usize,
) -> Option<&'a Value> {
    metadata
        .and_then(|value| value.get(name))
        .and_then(Value::as_object)
        .and_then(|values| values.get(&index.to_string()))
}

fn integers(value: Option<&Value>) -> HashSet<usize> {
    value
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_u64)
        .filter_map(|number| usize::try_from(number).ok())
        .collect()
}

fn strings(value: Option<&Value>) -> Vec<&str> {
    value
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect()
}

fn append_comment(output: &mut String, prefix: &str, value: &str) -> Result<(), ParseError> {
    if value.contains(['\n', '\r', '\u{2028}', '\u{2029}']) {
        return Err(ParseError::new(
            "INVALID_GETTEXT_COMMENT",
            "Gettext comments must fit on one physical line",
        ));
    }
    output.push_str(prefix);
    output.push_str(value);
    output.push('\n');
    Ok(())
}

pub(crate) fn quote(value: &str) -> Result<String, ParseError> {
    if value.contains('\0') {
        return Err(ParseError::new(
            "INVALID_GETTEXT_NUL",
            "GNU gettext silently truncates embedded NUL bytes",
        ));
    }
    let mut output = String::from("\"");
    for character in value.chars() {
        match character {
            '\\' => output.push_str("\\\\"),
            '"' => output.push_str("\\\""),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            '\u{0008}' => output.push_str("\\b"),
            '\u{000c}' => output.push_str("\\f"),
            '\u{0007}' => output.push_str("\\a"),
            '\u{000b}' => output.push_str("\\v"),
            value if value < ' ' || value == '\u{007f}' => {
                write!(&mut output, "\\{:03o}", value as u32).expect("writing to String")
            }
            _ => output.push(character),
        }
    }
    output.push('"');
    Ok(output)
}

fn argument_pattern() -> &'static Regex {
    static ARGUMENT: OnceLock<Regex> = OnceLock::new();
    ARGUMENT.get_or_init(|| Regex::new(r"\{([A-Za-z_][A-Za-z0-9_.-]*)\}").expect("valid argument"))
}
