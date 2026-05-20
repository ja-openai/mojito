use std::collections::BTreeMap;

pub fn canonical_locale_key(locale: &str) -> String {
    locale_parts(locale).join("-")
}

pub fn locale_lookup_chain(locale: &str) -> Vec<String> {
    structural_lookup_chain(&canonical_locale_key(locale))
}

pub fn lookup_locale<'a, T>(
    values: &'a BTreeMap<String, T>,
    locale: &str,
    fallback: &str,
) -> Option<&'a T> {
    for candidate in locale_lookup_chain(locale) {
        if let Some(value) = lookup_canonical_key(values, &candidate) {
            return Some(value);
        }
    }
    lookup_canonical_key(values, &canonical_locale_key(fallback))
}

fn lookup_canonical_key<'a, T>(
    values: &'a BTreeMap<String, T>,
    canonical_key: &str,
) -> Option<&'a T> {
    values
        .iter()
        .find(|(key, _)| canonical_locale_key(key) == canonical_key)
        .map(|(_, value)| value)
}

pub(crate) fn plural_lookup_chain(
    locale: &str,
    parents: &'static [(&'static str, &'static str)],
) -> Vec<String> {
    let mut chain = Vec::new();
    append_plural_lookup_chain(&canonical_locale_key(locale), parents, &mut chain);
    chain
}

fn append_plural_lookup_chain(
    locale: &str,
    parents: &'static [(&'static str, &'static str)],
    chain: &mut Vec<String>,
) {
    let mut current = locale.to_string();
    while !current.is_empty() {
        if chain.iter().any(|candidate| candidate == &current) {
            return;
        }
        chain.push(current.clone());
        if let Some(parent) = parents
            .iter()
            .find(|(child, _)| *child == current)
            .map(|(_, parent)| *parent)
        {
            append_plural_lookup_chain(parent, parents, chain);
        }
        current = structural_parent(&current).unwrap_or_default();
    }
}

fn structural_lookup_chain(locale: &str) -> Vec<String> {
    let parts: Vec<_> = locale.split('-').filter(|part| !part.is_empty()).collect();
    (1..=parts.len())
        .rev()
        .map(|length| parts[..length].join("-"))
        .collect()
}

fn locale_parts(locale: &str) -> Vec<String> {
    locale
        .trim()
        .replace('_', "-")
        .split('-')
        .filter(|part| !part.is_empty())
        .enumerate()
        .take_while(|(_, part)| part.len() != 1)
        .map(|(index, part)| canonical_subtag(index, part))
        .collect()
}

fn canonical_subtag(index: usize, part: &str) -> String {
    if index == 0 {
        return part.to_ascii_lowercase();
    }
    if part.len() == 4 && part.chars().all(|ch| ch.is_ascii_alphabetic()) {
        let mut chars = part.chars();
        let first = chars
            .next()
            .map(|ch| ch.to_ascii_uppercase())
            .unwrap_or_default();
        let rest = chars.as_str().to_ascii_lowercase();
        return format!("{first}{rest}");
    }
    if (part.len() == 2 && part.chars().all(|ch| ch.is_ascii_alphabetic()))
        || (part.len() == 3 && part.chars().all(|ch| ch.is_ascii_digit()))
    {
        return part.to_ascii_uppercase();
    }
    part.to_ascii_lowercase()
}

fn structural_parent(locale: &str) -> Option<String> {
    locale
        .rsplit_once('-')
        .map(|(parent, _)| parent.to_string())
}
