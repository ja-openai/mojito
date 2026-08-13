use crate::gettext_plural::{plural_samples, PluralForms};
use crate::model::{Catalog, FileFormat, Message, ParseError};
use crate::placeholders;
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

pub(crate) fn parse(source: &str, encoding: &str) -> Result<Catalog, ParseError> {
    static INDEXED: OnceLock<Regex> = OnceLock::new();
    let indexed_translation =
        INDEXED.get_or_init(|| Regex::new(r"^msgstr\[(\d+)]\s+(.+)$").unwrap());
    let mut catalog = Catalog::new(FileFormat::GettextPo);
    let mut state = ParseState::default();
    let mut entry = Entry::default();
    let mut domain = None;
    for directive in logical_directives(source)? {
        let line = directive.as_str();
        if line.is_empty() {
            flush(&mut catalog, &entry, &mut state)?;
            entry = Entry::default();
        } else if line.starts_with("#~") {
            continue;
        } else if line.starts_with('#') {
            if entry.has_message() {
                flush(&mut catalog, &entry, &mut state)?;
                entry = Entry::default();
            }
            comment(&mut entry, line, encoding)?;
        } else if let Some(value) = line.strip_prefix("domain ") {
            if entry.id.is_some() {
                flush(&mut catalog, &entry, &mut state)?;
                entry = Entry::default();
            }
            let selected = quoted(value, encoding)?;
            if selected
                .chars()
                .any(|value| crate::model::java_whitespace(value) || matches!(value, '/' | '\\'))
            {
                return Err(ParseError::new(
                    "INVALID_GETTEXT_DOMAIN",
                    "GNU gettext domain is unsafe as an MO output filename",
                ));
            }
            domain = Some(selected);
            state.activate(domain.as_deref());
        } else if let Some(value) = line.strip_prefix("msgctxt ") {
            entry.domain.clone_from(&domain);
            entry.context = Some(quoted(value, encoding)?);
            entry.active = Some(Field::Context);
        } else if let Some(value) = line.strip_prefix("msgid_plural ") {
            require_id(&entry)?;
            entry.plural = Some(quoted(value, encoding)?);
            entry.active = Some(Field::Plural);
        } else if let Some(value) = line.strip_prefix("msgid ") {
            if entry.id.is_some() {
                flush(&mut catalog, &entry, &mut state)?;
                entry = Entry::default();
            }
            entry.domain.clone_from(&domain);
            entry.id = Some(quoted(value, encoding)?);
            entry.active = Some(Field::Id);
        } else if line.starts_with("msgstr[") {
            require_id(&entry)?;
            let captures = indexed_translation
                .captures(line)
                .ok_or_else(|| invalid("Malformed gettext plural translation"))?;
            let index: usize = captures[1]
                .parse()
                .map_err(|_| invalid("Invalid gettext plural index"))?;
            if entry
                .translations
                .insert(index, quoted(&captures[2], encoding)?)
                .is_some()
            {
                return Err(invalid("Duplicate gettext plural translation index"));
            }
            entry.active = Some(Field::Indexed(index));
        } else if let Some(value) = line.strip_prefix("msgstr ") {
            require_id(&entry)?;
            if entry.translation.is_some() {
                return Err(invalid("Duplicate gettext translation"));
            }
            entry.translation = Some(quoted(value, encoding)?);
            entry.active = Some(Field::Translation);
        } else if line.starts_with('"') {
            append(&mut entry, &quoted(line, encoding)?)?;
        } else {
            return Err(invalid("Unsupported gettext directive"));
        }
    }
    flush(&mut catalog, &entry, &mut state)?;
    state.finish(&mut catalog)?;
    Ok(catalog)
}

fn flush(catalog: &mut Catalog, entry: &Entry, state: &mut ParseState) -> Result<(), ParseError> {
    if (entry.previous_context.is_some() || entry.previous_plural.is_some())
        && entry.previous_id.is_none()
    {
        return Err(invalid(
            "Previous gettext history requires a previous msgid",
        ));
    }
    let Some(id) = &entry.id else {
        return Ok(());
    };
    if id.is_empty() && entry.context.is_none() {
        return state.parse_header(catalog, entry);
    }
    let mut placeholders = Vec::new();
    let mut metadata = Map::new();
    let mut variants = None;
    let default_message;
    if let Some(plural) = &entry.plural {
        let translations = if entry.translations.is_empty() {
            BTreeMap::from([(0, id.clone()), (1, plural.clone())])
        } else {
            entry.translations.clone()
        };
        let mut indexed = Map::new();
        let mut indexed_selectors = Map::new();
        let mut escaped_percents = Map::new();
        let mut line_separators = Map::new();
        let mut untranslated_indexes = Vec::new();
        let mut values = BTreeMap::new();
        let mut ordered = Vec::new();
        if let Some(forms) = &state.forms {
            if translations.len() != forms.count
                || translations.keys().any(|index| *index >= forms.count)
            {
                return Err(ParseError::new(
                    "INVALID_GETTEXT_PLURAL_FORMS",
                    "Gettext translations do not cover all declared plural forms",
                ));
            }
        }
        let selectors = plural_selectors(
            state.active_locale.as_deref(),
            &translations,
            state.forms.as_ref(),
        )?;
        let mut expanded_selectors = false;
        for (index, translation) in &translations {
            let categories = selectors
                .get(index)
                .expect("selectors for each plural index");
            let text = if translation.is_empty() {
                untranslated_indexes.push(*index);
                if *index == 0 {
                    id
                } else {
                    plural
                }
            } else {
                translation
            };
            let normalized = normalize(entry, text, &mut placeholders);
            if !preserves_literal_formats(entry) {
                let positions = placeholders::escaped_percent_positions(text);
                if !positions.is_empty() {
                    escaped_percents.insert(index.to_string(), json!(positions));
                }
                let separators = placeholders::printf_line_separators(text)
                    .into_iter()
                    .map(|(position, source)| json!({ "position": position, "source": source }))
                    .collect::<Vec<_>>();
                if !separators.is_empty() {
                    line_separators.insert(index.to_string(), json!(separators));
                }
            }
            for category in categories {
                values.insert(category.clone(), normalized.clone());
                ordered.push((category.clone(), normalized.clone()));
            }
            indexed.insert(index.to_string(), Value::String(categories[0].clone()));
            indexed_selectors.insert(index.to_string(), json!(categories));
            expanded_selectors |= categories.len() > 1;
        }
        if !values.contains_key("other") {
            let fallback = ordered
                .last()
                .map(|(_, value)| value.clone())
                .ok_or_else(|| ParseError::new("MISSING_OTHER_VARIANT", "Missing gettext other"))?;
            values.insert("other".into(), fallback.clone());
            ordered.push(("other".into(), fallback));
        }
        let selector = placeholders
            .iter()
            .find(|placeholder| placeholder.kind == "integer")
            .map(|placeholder| placeholder.name.as_str())
            .unwrap_or("count");
        default_message = placeholders::plural(
            selector,
            ordered
                .iter()
                .map(|(category, value)| (category.as_str(), value.as_str())),
        );
        variants = Some(values);
        metadata.insert("sourceMessage".into(), json!(id));
        metadata.insert("sourcePlural".into(), json!(plural));
        common_metadata(entry, &mut metadata);
        metadata.insert("gettextPluralIndexes".into(), Value::Object(indexed));
        if !escaped_percents.is_empty() {
            metadata.insert(
                "gettextPluralEscapedPercents".into(),
                Value::Object(escaped_percents),
            );
        }
        if !line_separators.is_empty() {
            metadata.insert(
                "gettextPluralPrintfLineSeparators".into(),
                Value::Object(line_separators),
            );
        }
        if !untranslated_indexes.is_empty() {
            metadata.insert(
                "gettextUntranslatedIndexes".into(),
                json!(untranslated_indexes),
            );
        }
        if expanded_selectors {
            metadata.insert(
                "gettextPluralSelectors".into(),
                Value::Object(indexed_selectors),
            );
        }
        if let Some(forms) = &state.forms {
            metadata.insert(
                "gettextPluralForms".into(),
                json!({"nplurals": forms.count, "expression": forms.source}),
            );
        }
    } else {
        let translation = entry
            .translation
            .as_ref()
            .filter(|value| !value.is_empty())
            .unwrap_or(id);
        default_message = normalize(entry, translation, &mut placeholders);
        if entry.translation.as_ref().is_none_or(String::is_empty) {
            metadata.insert("gettextUntranslated".into(), json!(true));
        }
        if entry
            .translation
            .as_ref()
            .is_some_and(|value| !value.is_empty() && value != id)
        {
            metadata.insert("sourceMessage".into(), json!(id));
        }
        if !preserves_literal_formats(entry) {
            let positions = placeholders::escaped_percent_positions(translation);
            if !positions.is_empty() {
                metadata.insert("gettextEscapedPercents".into(), json!(positions));
            }
            let separators = placeholders::printf_line_separators(translation)
                .into_iter()
                .map(|(position, source)| json!({ "position": position, "source": source }))
                .collect::<Vec<_>>();
            if !separators.is_empty() {
                metadata.insert("gettextPrintfLineSeparators".into(), json!(separators));
            }
        }
        common_metadata(entry, &mut metadata);
    }
    if let Some(header) = state.headers.get(effective_domain(entry.domain.as_deref())) {
        if entry.domain.is_some() || !header.fields.is_empty() {
            metadata.insert("gettextDomainHeader".into(), header.metadata());
        }
    }
    state.pending.push(Pending {
        id: entry.context.clone().unwrap_or_else(|| id.clone()),
        domain: effective_domain(entry.domain.as_deref()).to_owned(),
        message: Message::new(
            default_message,
            Some(entry.extracted.join(" ")),
            variants,
            placeholders,
            metadata,
        ),
    });
    Ok(())
}

#[derive(Clone, Default)]
struct DomainHeader {
    locale: Option<String>,
    forms: Option<PluralForms>,
    fields: Vec<(String, String)>,
}

impl DomainHeader {
    fn metadata(&self) -> Value {
        let mut metadata = Map::new();
        if let Some(locale) = &self.locale {
            metadata.insert("locale".into(), json!(locale));
        }
        if let Some(forms) = &self.forms {
            metadata.insert(
                "pluralForms".into(),
                json!({"nplurals": forms.count, "expression": forms.source}),
            );
        }
        if !self.fields.is_empty() {
            metadata.insert(
                "fields".into(),
                Value::Array(
                    self.fields
                        .iter()
                        .map(|(name, value)| json!({"name": name, "value": value}))
                        .collect(),
                ),
            );
        }
        Value::Object(metadata)
    }
}

struct Pending {
    id: String,
    domain: String,
    message: Message,
}

#[derive(Default)]
struct ParseState {
    headers: BTreeMap<String, DomainHeader>,
    forms: Option<PluralForms>,
    active_locale: Option<String>,
    mixed_locales: bool,
    pending: Vec<Pending>,
}

impl ParseState {
    fn parse_header(&mut self, catalog: &mut Catalog, entry: &Entry) -> Result<(), ParseError> {
        let domain = effective_domain(entry.domain.as_deref()).to_owned();
        if self.headers.contains_key(&domain) {
            return Err(ParseError::new(
                "INVALID_GETTEXT_DOMAIN_HEADER",
                "Duplicate gettext header in one translation domain",
            ));
        }
        let mut current = DomainHeader::default();
        if let Some(headers) = &entry.translation {
            let mut previous_field = None;
            for header in headers.lines() {
                if header.chars().all(crate::model::java_whitespace) {
                    continue;
                }
                let Some((name, value)) = header.split_once(':') else {
                    let Some(index) = previous_field else {
                        return Err(ParseError::new(
                            "INVALID_GETTEXT_DOMAIN_HEADER",
                            "Gettext header continuation cannot alter a reserved native field",
                        ));
                    };
                    let (_, previous): &mut (String, String) = &mut current.fields[index];
                    previous.push('\n');
                    previous.push_str(header.trim_matches(crate::model::java_whitespace));
                    continue;
                };
                previous_field = None;
                if name.eq_ignore_ascii_case("Language") {
                    let locale = value
                        .trim_matches(crate::model::java_whitespace)
                        .replace('_', "-");
                    if !locale.is_empty() {
                        current.locale = Some(locale);
                    }
                } else if name.eq_ignore_ascii_case("Plural-Forms") {
                    current.forms = Some(PluralForms::parse(
                        value.trim_matches(crate::model::java_whitespace),
                    )?);
                } else if !name.eq_ignore_ascii_case("Content-Type") {
                    previous_field = Some(current.fields.len());
                    current.fields.push((
                        name.to_owned(),
                        value.trim_matches(crate::model::java_whitespace).to_owned(),
                    ));
                }
            }
        }
        if let Some(locale) = &current.locale {
            if catalog
                .locale
                .as_ref()
                .is_some_and(|previous| previous != locale)
            {
                self.mixed_locales = true;
                catalog.locale = None;
            } else if !self.mixed_locales {
                catalog.locale = Some(locale.clone());
            }
        }
        self.headers.insert(domain, current);
        self.activate(entry.domain.as_deref());
        Ok(())
    }

    fn activate(&mut self, domain: Option<&str>) {
        let current = self
            .headers
            .get(effective_domain(domain))
            .or_else(|| self.headers.get("messages"));
        self.forms = current.and_then(|header| header.forms.clone());
        self.active_locale = current.and_then(|header| header.locale.clone());
    }

    fn finish(self, catalog: &mut Catalog) -> Result<(), ParseError> {
        let mut domains: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for pending in &self.pending {
            domains
                .entry(pending.id.clone())
                .or_default()
                .insert(pending.domain.clone());
        }
        for pending in self.pending {
            let mut id = pending.id.clone();
            let mut message = pending.message;
            if self.mixed_locales
                && !message
                    .metadata
                    .as_ref()
                    .is_some_and(|metadata| metadata.contains_key("gettextDomainHeader"))
            {
                if let Some(header) = self.headers.get(&pending.domain) {
                    message
                        .metadata
                        .get_or_insert_with(Map::new)
                        .insert("gettextDomainHeader".into(), header.metadata());
                }
            }
            if domains[&pending.id].len() > 1 {
                id.push_str("@domain=");
                id.push_str(&escape_domain(&pending.domain));
                message
                    .metadata
                    .get_or_insert_with(Map::new)
                    .insert("gettextOriginalId".into(), json!(pending.id));
            }
            catalog.insert(id, message)?;
        }
        Ok(())
    }
}

fn effective_domain(domain: Option<&str>) -> &str {
    domain.unwrap_or("messages")
}

pub(crate) fn escape_domain(domain: &str) -> String {
    let mut result = String::new();
    for value in domain.bytes() {
        if value.is_ascii_alphanumeric() || matches!(value, b'-' | b'_' | b'.' | b'~') {
            result.push(char::from(value));
        } else {
            use std::fmt::Write;
            write!(&mut result, "%{value:02X}").expect("writing to String");
        }
    }
    result
}

fn plural_selectors(
    locale: Option<&str>,
    translations: &BTreeMap<usize, String>,
    forms: Option<&PluralForms>,
) -> Result<BTreeMap<usize, Vec<String>>, ParseError> {
    let mut selectors = BTreeMap::new();
    let Some(forms) = forms else {
        for index in translations.keys() {
            selectors.insert(
                *index,
                vec![plural_category(locale, *index, translations.len(), None)?],
            );
        }
        return Ok(selectors);
    };

    let mut samples: BTreeMap<usize, Vec<i64>> = BTreeMap::new();
    let mut category_counts: BTreeMap<String, BTreeMap<usize, usize>> = BTreeMap::new();
    for sample in plural_samples() {
        let index = forms.evaluate(sample)?;
        samples.entry(index).or_default().push(sample);
        *category_counts
            .entry(cldr_category(locale, sample))
            .or_default()
            .entry(index)
            .or_default() += 1;
    }

    let mut owners = BTreeMap::new();
    for (category, candidates) in category_counts {
        let (&winner, _) = candidates
            .iter()
            .max_by_key(|(index, count)| (*count, std::cmp::Reverse(**index)))
            .expect("sampled plural category must have an index");
        owners.insert(category, winner);
    }

    for index in translations.keys() {
        let mut current = Vec::new();
        for sample in samples.get(index).into_iter().flatten() {
            if owners.get(&cldr_category(locale, *sample)) != Some(index) {
                current.push(format!("={sample}"));
            }
        }
        for category in ["zero", "one", "two", "few", "many", "other"] {
            if owners.get(category) == Some(index) {
                current.push(category.to_owned());
            }
        }
        if current.is_empty() {
            current.push(plural_category(
                locale,
                *index,
                translations.len(),
                Some(forms),
            )?);
        }
        selectors.insert(*index, current);
    }
    Ok(selectors)
}

fn common_metadata(entry: &Entry, metadata: &mut Map<String, Value>) {
    if let Some(domain) = &entry.domain {
        metadata.insert("gettextDomain".into(), json!(domain));
    }
    if let Some(id) = &entry.previous_id {
        let mut previous = Map::new();
        if let Some(context) = &entry.previous_context {
            previous.insert("context".into(), json!(context));
        }
        previous.insert("id".into(), json!(id));
        if let Some(plural) = &entry.previous_plural {
            previous.insert("plural".into(), json!(plural));
        }
        metadata.insert("gettextPrevious".into(), Value::Object(previous));
    }
    if !entry.comments.is_empty() {
        metadata.insert("translatorComments".into(), json!(entry.comments));
    }
    if !entry.references.is_empty() {
        metadata.insert("references".into(), json!(entry.references));
    }
    if !entry.flags.is_empty() {
        metadata.insert("flags".into(), json!(entry.flags));
    }
    if let Some(context) = &entry.context {
        metadata.insert("context".into(), json!(context));
    }
}

fn normalize(entry: &Entry, value: &str, placeholders: &mut Vec<crate::Placeholder>) -> String {
    if preserves_literal_formats(entry) {
        value.to_owned()
    } else {
        placeholders::normalize(value, placeholders, None)
    }
}

fn preserves_literal_formats(entry: &Entry) -> bool {
    entry
        .flags
        .iter()
        .any(|flag| flag == "no-c-format" || flag == "no-python-format")
}

fn plural_category(
    locale: Option<&str>,
    index: usize,
    count: usize,
    forms: Option<&PluralForms>,
) -> Result<String, ParseError> {
    if let Some(forms) = forms {
        for sample in plural_samples() {
            if forms.evaluate(sample)? == index {
                return Ok(cldr_category(locale, sample));
            }
        }
    }
    let language = locale
        .unwrap_or("")
        .split('-')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    let categories: &[&str] = match language.as_str() {
        "ar" | "cy" => &["zero", "one", "two", "few", "many", "other"],
        "ru" | "uk" | "be" | "pl" => &["one", "few", "many", "other"],
        "sr" | "hr" | "cs" | "sk" | "ro" | "lt" => &["one", "few", "other"],
        "sl" => &["one", "two", "few", "other"],
        "he" | "iw" => &["one", "two", "other"],
        "ja" | "ko" | "zh" | "th" | "vi" => &["other"],
        _ if count <= 1 => &["other"],
        _ => &["one", "other"],
    };
    Ok(categories
        .get(index)
        .map(|category| (*category).to_owned())
        .unwrap_or_else(|| format!("={index}")))
}

fn cldr_category(locale: Option<&str>, n: i64) -> String {
    let tag = locale.unwrap_or("").to_ascii_lowercase();
    let language = tag.split('-').next().unwrap_or("");
    let modulo10 = n % 10;
    let modulo100 = n % 100;
    let category = match language {
        "ja" | "ko" | "zh" | "th" | "vi" => "other",
        "fr" => {
            if n <= 1 {
                "one"
            } else {
                million_category(n)
            }
        }
        "pt" => {
            if if tag == "pt-pt" { n == 1 } else { n <= 1 } {
                "one"
            } else {
                million_category(n)
            }
        }
        "ca" | "es" | "it" => {
            if n == 1 {
                "one"
            } else {
                million_category(n)
            }
        }
        "ar" => match n {
            0 => "zero",
            1 => "one",
            2 => "two",
            _ if (3..=10).contains(&modulo100) => "few",
            _ if modulo100 >= 11 => "many",
            _ => "other",
        },
        "ru" | "uk" | "be" => {
            if modulo10 == 1 && modulo100 != 11 {
                "one"
            } else if (2..=4).contains(&modulo10) && !(12..=14).contains(&modulo100) {
                "few"
            } else {
                "many"
            }
        }
        "pl" => {
            if n == 1 {
                "one"
            } else if (2..=4).contains(&modulo10) && !(12..=14).contains(&modulo100) {
                "few"
            } else {
                "many"
            }
        }
        "sr" | "hr" => {
            if modulo10 == 1 && modulo100 != 11 {
                "one"
            } else if (2..=4).contains(&modulo10) && !(12..=14).contains(&modulo100) {
                "few"
            } else {
                "other"
            }
        }
        "cs" | "sk" => {
            if n == 1 {
                "one"
            } else if (2..=4).contains(&n) {
                "few"
            } else {
                "other"
            }
        }
        "sl" => match modulo100 {
            1 => "one",
            2 => "two",
            3 | 4 => "few",
            _ => "other",
        },
        "cy" => match n {
            0 => "zero",
            1 => "one",
            2 => "two",
            3 => "few",
            6 => "many",
            _ => "other",
        },
        "he" | "iw" => match n {
            1 => "one",
            2 => "two",
            _ => "other",
        },
        _ if n == 1 => "one",
        _ => "other",
    };
    category.to_owned()
}

fn million_category(n: i64) -> &'static str {
    if n != 0 && n % 1_000_000 == 0 {
        "many"
    } else {
        "other"
    }
}

fn comment(entry: &mut Entry, line: &str, encoding: &str) -> Result<(), ParseError> {
    if let Some(value) = line.strip_prefix("#.") {
        entry
            .extracted
            .push(value.trim_matches(crate::model::java_whitespace).to_owned());
    } else if let Some(value) = line.strip_prefix("#:") {
        entry.references.extend(
            value
                .trim_matches(crate::model::java_whitespace)
                .split(|character| {
                    matches!(
                        character,
                        ' ' | '\t' | '\n' | '\u{000b}' | '\u{000c}' | '\r'
                    )
                })
                .filter(|reference| !reference.is_empty())
                .map(str::to_owned),
        );
    } else if let Some(value) = line.strip_prefix("#,").or_else(|| line.strip_prefix("#=")) {
        for flag in value
            .split(',')
            .map(|flag| flag.trim_matches(crate::model::java_whitespace))
            .filter(|flag| !flag.is_empty())
        {
            let opposite = flag.ends_with("-format").then(|| {
                flag.strip_prefix("no-")
                    .map_or_else(|| format!("no-{flag}"), str::to_owned)
            });
            entry
                .flags
                .retain(|current| current != flag && Some(current) != opposite.as_ref());
            entry.flags.push(flag.to_owned());
        }
    } else if let Some(value) = line.strip_prefix("#|") {
        previous(entry, value, encoding)?;
    } else {
        let value = line[1..].trim_matches(crate::model::java_whitespace);
        if !value.is_empty() {
            entry.comments.push(value.to_owned());
        }
    }
    Ok(())
}

fn previous(entry: &mut Entry, source: &str, encoding: &str) -> Result<(), ParseError> {
    let value = trim_ascii_start(source);
    if value.starts_with('"') {
        let continuation = quoted(value, encoding)?;
        match entry.previous_active {
            Some(Field::Context) => entry
                .previous_context
                .as_mut()
                .unwrap()
                .push_str(&continuation),
            Some(Field::Id) => entry.previous_id.as_mut().unwrap().push_str(&continuation),
            Some(Field::Plural) => entry
                .previous_plural
                .as_mut()
                .unwrap()
                .push_str(&continuation),
            _ => return Err(invalid("Previous gettext continuation has no active field")),
        }
        return Ok(());
    }
    for directive in ["msgid_plural", "msgctxt", "msgid"] {
        let Some(argument) = value.strip_prefix(directive) else {
            continue;
        };
        if !argument.is_empty()
            && !argument.starts_with('"')
            && !argument.as_bytes()[0].is_ascii_whitespace()
        {
            continue;
        }
        let text = quoted(trim_ascii_start(argument), encoding)?;
        match directive {
            "msgctxt" => {
                if entry.previous_context.is_some() || entry.previous_id.is_some() {
                    return Err(invalid("Invalid previous gettext context ordering"));
                }
                entry.previous_context = Some(text);
                entry.previous_active = Some(Field::Context);
            }
            "msgid" => {
                if entry.previous_id.is_some() {
                    return Err(invalid("Duplicate previous gettext msgid"));
                }
                entry.previous_id = Some(text);
                entry.previous_active = Some(Field::Id);
            }
            "msgid_plural" => {
                if entry.previous_id.is_none() || entry.previous_plural.is_some() {
                    return Err(invalid("Invalid previous gettext plural ordering"));
                }
                entry.previous_plural = Some(text);
                entry.previous_active = Some(Field::Plural);
            }
            _ => unreachable!("known previous directive"),
        }
        return Ok(());
    }
    Err(invalid("Unsupported previous gettext directive"))
}

fn logical_directives(source: &str) -> Result<Vec<String>, ParseError> {
    let spliced = source.replace("\\\r\n", "").replace("\\\n", "");
    let normalized = spliced.replace("\r\n", "\n").replace('\r', "\n");
    let mut directives = Vec::new();
    for physical in normalized.split('\n') {
        let line = trim_ascii_start(physical);
        if line.is_empty() || line.starts_with('#') {
            directives.push(line.to_owned());
            continue;
        }
        let bytes = line.as_bytes();
        let mut index = 0;
        while index < bytes.len() {
            while index < bytes.len() && ascii_whitespace(bytes[index]) {
                index += 1;
            }
            if index == bytes.len() {
                break;
            }
            if bytes[index] == b'"' {
                let end = quoted_end(bytes, index)?;
                directives.push(line[index..end].to_owned());
                index = end;
                continue;
            }
            let start = index;
            while index < bytes.len() && (bytes[index].is_ascii_lowercase() || bytes[index] == b'_')
            {
                index += 1;
            }
            if start == index {
                return Err(invalid("Unsupported gettext directive"));
            }
            let mut keyword = line[start..index].to_owned();
            while index < bytes.len() && ascii_whitespace(bytes[index]) {
                index += 1;
            }
            if keyword == "msgstr" && index < bytes.len() && bytes[index] == b'[' {
                index += 1;
                while index < bytes.len() && ascii_whitespace(bytes[index]) {
                    index += 1;
                }
                let digits = index;
                while index < bytes.len() && bytes[index].is_ascii_digit() {
                    index += 1;
                }
                if digits == index {
                    return Err(invalid("Malformed indexed gettext translation"));
                }
                let number = &line[digits..index];
                while index < bytes.len() && ascii_whitespace(bytes[index]) {
                    index += 1;
                }
                if index >= bytes.len() || bytes[index] != b']' {
                    return Err(invalid("Malformed indexed gettext translation"));
                }
                index += 1;
                keyword = format!("msgstr[{number}]");
                while index < bytes.len() && ascii_whitespace(bytes[index]) {
                    index += 1;
                }
            }
            if index >= bytes.len() || bytes[index] != b'"' {
                return Err(invalid("Gettext directive must contain a quoted C string"));
            }
            let end = quoted_end(bytes, index)?;
            directives.push(format!("{keyword} {}", &line[index..end]));
            index = end;
        }
    }
    Ok(directives)
}

fn quoted_end(bytes: &[u8], start: usize) -> Result<usize, ParseError> {
    let mut index = start + 1;
    while index < bytes.len() {
        if bytes[index] == b'\\' {
            index += 1;
        } else if bytes[index] == b'"' {
            return Ok(index + 1);
        }
        index += 1;
    }
    Err(invalid("Unterminated gettext C string"))
}

fn trim_ascii_start(value: &str) -> &str {
    value.trim_start_matches(|character: char| {
        matches!(character, ' ' | '\t' | '\u{000b}' | '\u{000c}')
    })
}

fn ascii_whitespace(value: u8) -> bool {
    matches!(value, b' ' | b'\t' | 0x0b | 0x0c)
}

fn append(entry: &mut Entry, value: &str) -> Result<(), ParseError> {
    match entry
        .active
        .ok_or_else(|| invalid("Unexpected gettext continuation"))?
    {
        Field::Context => entry.context.as_mut().unwrap().push_str(value),
        Field::Id => entry.id.as_mut().unwrap().push_str(value),
        Field::Plural => entry.plural.as_mut().unwrap().push_str(value),
        Field::Translation => entry.translation.as_mut().unwrap().push_str(value),
        Field::Indexed(index) => entry.translations.get_mut(&index).unwrap().push_str(value),
    }
    Ok(())
}

pub(crate) fn quoted(value: &str, encoding: &str) -> Result<String, ParseError> {
    let text = value.trim_matches(crate::model::java_whitespace);
    if text.len() < 2 || !text.starts_with('"') || !text.ends_with('"') {
        return Err(invalid("Expected quoted gettext string"));
    }
    let mut output = Vec::new();
    let mut chars = text[1..text.len() - 1].chars();
    while let Some(character) = chars.next() {
        if character != '\\' {
            output.extend_from_slice(&crate::encode_gettext_character(character, encoding)?);
        } else {
            let escaped = chars
                .next()
                .ok_or_else(|| invalid("Trailing gettext escape"))?;
            match escaped {
                'n' => output.push(b'\n'),
                'r' => output.push(b'\r'),
                't' => output.push(b'\t'),
                'b' => output.push(0x08),
                'f' => output.push(0x0c),
                'a' => output.push(0x07),
                'v' => output.push(0x0b),
                '\\' => output.push(b'\\'),
                '"' => output.push(b'"'),
                'x' => {
                    let mut digits = String::new();
                    while chars
                        .clone()
                        .next()
                        .is_some_and(|value| value.is_ascii_hexdigit())
                    {
                        digits.push(chars.next().unwrap());
                    }
                    if digits.is_empty() {
                        return Err(invalid("Gettext hexadecimal escape has no digits"));
                    }
                    let start = digits.len().saturating_sub(2);
                    let value = u8::from_str_radix(&digits[start..], 16)
                        .map_err(|_| invalid("Invalid gettext hexadecimal escape"))?;
                    output.push(value);
                }
                '0'..='7' => {
                    let mut value = escaped.to_digit(8).unwrap();
                    for _ in 0..2 {
                        match chars.clone().next() {
                            Some(digit @ '0'..='7') => {
                                chars.next();
                                value = value * 8 + digit.to_digit(8).unwrap();
                            }
                            _ => break,
                        }
                    }
                    output.push(value as u8);
                }
                _ => return Err(invalid("Unsupported gettext C escape")),
            }
        }
    }
    let decoded = crate::decode_gettext_bytes(&output, encoding)?;
    if decoded.contains('\0') {
        return Err(ParseError::new(
            "INVALID_GETTEXT_NUL",
            "GNU gettext silently truncates embedded NUL bytes",
        ));
    }
    Ok(decoded)
}

fn require_id(entry: &Entry) -> Result<(), ParseError> {
    if entry.id.is_none() {
        return Err(invalid("Gettext translation before msgid"));
    }
    Ok(())
}

fn invalid(message: &str) -> ParseError {
    ParseError::new("INVALID_GETTEXT", message)
}

#[derive(Clone, Copy)]
enum Field {
    Context,
    Id,
    Plural,
    Translation,
    Indexed(usize),
}

#[derive(Default)]
struct Entry {
    extracted: Vec<String>,
    comments: Vec<String>,
    references: Vec<String>,
    flags: Vec<String>,
    translations: BTreeMap<usize, String>,
    context: Option<String>,
    id: Option<String>,
    plural: Option<String>,
    translation: Option<String>,
    domain: Option<String>,
    previous_context: Option<String>,
    previous_id: Option<String>,
    previous_plural: Option<String>,
    active: Option<Field>,
    previous_active: Option<Field>,
}

impl Entry {
    fn has_message(&self) -> bool {
        self.id.is_some() && (self.translation.is_some() || !self.translations.is_empty())
    }
}
