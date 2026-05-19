use std::collections::BTreeMap;

pub fn canonical_locale_key(locale: &str) -> String {
    locale.trim().replace('-', "_").to_ascii_lowercase()
}

pub fn locale_lookup_chain(locale: &str) -> Vec<String> {
    let normalized = canonical_locale_key(locale);
    let parts: Vec<_> = normalized
        .split('_')
        .filter(|part| !part.is_empty())
        .collect();
    (1..=parts.len())
        .rev()
        .map(|length| parts[..length].join("_"))
        .collect()
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
