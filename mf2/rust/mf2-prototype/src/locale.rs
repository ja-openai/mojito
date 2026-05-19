use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LocaleId {
    parts: Vec<String>,
}

impl LocaleId {
    pub fn parse(locale: &str) -> Self {
        let normalized = locale.trim().replace('_', "-");
        let mut parts = Vec::new();

        for (index, part) in normalized.split('-').filter(|part| !part.is_empty()).enumerate() {
            if part.len() == 1 {
                break;
            }
            parts.push(canonical_subtag(index, part));
        }

        Self { parts }
    }

    pub fn canonical_tag(&self) -> String {
        self.parts.join("-")
    }

    pub fn lookup_chain(&self) -> Vec<String> {
        (1..=self.parts.len())
            .rev()
            .map(|length| self.parts[..length].join("-"))
            .collect()
    }
}

pub fn canonical_locale_key(locale: &str) -> String {
    LocaleId::parse(locale).canonical_tag()
}

pub fn locale_lookup_chain(locale: &str) -> Vec<String> {
    LocaleId::parse(locale).lookup_chain()
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

#[cfg(test)]
mod tests {
    use super::{canonical_locale_key, locale_lookup_chain};

    #[test]
    fn canonicalizes_bcp47_shape() {
        assert_eq!(canonical_locale_key(" FR_ca "), "fr-CA");
        assert_eq!(canonical_locale_key("zh_hant_tw"), "zh-Hant-TW");
        assert_eq!(canonical_locale_key("pt-PT-u-nu-latn"), "pt-PT");
    }

    #[test]
    fn builds_structural_lookup_chain() {
        assert_eq!(
            locale_lookup_chain("zh-Hant-HK-u-nu-latn"),
            vec!["zh-Hant-HK", "zh-Hant", "zh"]
        );
    }
}
