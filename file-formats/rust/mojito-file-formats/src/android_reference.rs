const TYPES: [&str; 27] = [
    "anim",
    "animator",
    "array",
    "attr",
    "^attr-private",
    "bool",
    "color",
    "configVarying",
    "dimen",
    "drawable",
    "font",
    "fraction",
    "id",
    "integer",
    "interpolator",
    "layout",
    "macro",
    "menu",
    "mipmap",
    "navigation",
    "plurals",
    "raw",
    "string",
    "style",
    "styleable",
    "transition",
    "xml",
];

pub(crate) fn is_resource_type(kind: &str) -> bool {
    TYPES.contains(&kind)
}

pub(crate) fn is_reference(source: &str) -> bool {
    let source = normalize(source);
    if matches!(source, "@null" | "@empty") {
        return true;
    }
    if let Some(value) = source.strip_prefix('@') {
        resource(value)
    } else if let Some(value) = source.strip_prefix('?') {
        attribute(value)
    } else {
        false
    }
}

pub(crate) fn normalize(source: &str) -> &str {
    let source = source.trim_matches(|value: char| value.is_ascii_whitespace());
    if let Some(value) = source.strip_prefix("@@") {
        if !value.starts_with('+') && resource(value) {
            return &source[1..];
        }
    }
    source
}

fn resource(mut value: &str) -> bool {
    let create = value.starts_with('+');
    if create {
        value = &value[1..];
    }
    let private = value.starts_with('*');
    if private {
        value = &value[1..];
    }
    if create && private {
        return false;
    }
    let Some((qualified_type, entry)) = value.split_once('/') else {
        return false;
    };
    if qualified_type.is_empty() || entry.is_empty() {
        return false;
    }
    let kind = if let Some((package, kind)) = qualified_type.split_once(':') {
        if package.is_empty() || kind.is_empty() {
            return false;
        }
        kind
    } else {
        qualified_type
    };
    TYPES.contains(&kind) && (!create || kind == "id")
}

fn attribute(mut value: &str) -> bool {
    if let Some(rest) = value.strip_prefix('*') {
        value = rest;
    }
    if let Some((qualified_type, entry)) = value.split_once('/') {
        if entry.is_empty() {
            return false;
        }
        let kind = if let Some((package, kind)) = qualified_type.split_once(':') {
            if package.is_empty() || kind.is_empty() {
                return false;
            }
            kind
        } else {
            qualified_type
        };
        return kind == "attr";
    }
    if value.is_empty() {
        return false;
    }
    value
        .split_once(':')
        .is_none_or(|(package, entry)| !package.is_empty() && !entry.is_empty())
}
