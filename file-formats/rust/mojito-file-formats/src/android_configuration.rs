use crate::model::ParseError;

#[derive(Clone, Debug)]
pub(crate) struct Configuration {
    pub path: String,
    pub qualifiers: Vec<String>,
    pub locale: Option<String>,
    pub path_feature_flag: Option<String>,
}

impl Configuration {
    pub fn parse(path: &str) -> Result<Self, ParseError> {
        let mut parts = Vec::new();
        let mut path_feature_flag: Option<&str> = None;
        for part in path.split('/') {
            if part.is_empty() || matches!(part, "." | "..") {
                return Err(invalid_path());
            }
            if let Some(flag) = part
                .strip_prefix("flag(")
                .and_then(|value| value.strip_suffix(')'))
            {
                if path_feature_flag.is_some_and(|previous| !previous.is_empty()) {
                    return Err(ParseError::new(
                        "MULTIPLE_ANDROID_PATH_FLAGS",
                        "Android resource path cannot contain more than one flag directory",
                    ));
                }
                path_feature_flag = Some(flag);
            } else {
                parts.push(part);
            }
        }
        let path_feature_flag = path_feature_flag
            .filter(|flag| !flag.is_empty())
            .map(str::to_owned);
        if path.is_empty()
            || path.starts_with('/')
            || path.contains('\\')
            || parts.len() < 3
            || parts[parts.len() - 3] != "res"
            || !parts[parts.len() - 1].ends_with(".xml")
        {
            return Err(invalid_path());
        }
        let directory = parts[parts.len() - 2];
        let Some(suffix) = directory.strip_prefix("values") else {
            return Err(invalid_path());
        };
        if suffix.is_empty() {
            return Ok(Self {
                path: path.to_owned(),
                qualifiers: Vec::new(),
                locale: None,
                path_feature_flag,
            });
        }
        let Some(suffix) = suffix.strip_prefix('-') else {
            return Err(invalid_path());
        };

        let mut qualifiers = Vec::new();
        let mut locale = None;
        let mut bcp47 = false;
        let mut previous = None;
        for qualifier in suffix.split('-') {
            if qualifier.is_empty() {
                return Err(invalid_configuration());
            }
            let lower = qualifier.to_ascii_lowercase();
            let rank = if digits_after(&lower, "mcc", 3, 3) {
                if unsigned(&lower[3..], 65_536) == 0 {
                    return Err(invalid_configuration());
                }
                0
            } else if digits_after(&lower, "mnc", 1, 3) {
                1
            } else if lower.starts_with("b+") {
                if locale.is_some() {
                    return Err(invalid_configuration());
                }
                locale = Some(parse_bcp47(qualifier)?);
                bcp47 = true;
                2
            } else if lower.len() == 3
                && lower.starts_with('r')
                && lower[1..].bytes().all(|byte| byte.is_ascii_alphabetic())
                && locale.is_some()
                && !bcp47
            {
                locale =
                    locale.map(|value| format!("{value}-{}", qualifier[1..].to_ascii_uppercase()));
                3
            } else if matches!(lower.as_str(), "masculine" | "feminine" | "neuter") {
                4
            } else if matches!(lower.as_str(), "ldrtl" | "ldltr") {
                5
            } else if dp_after(&lower, "sw") {
                6
            } else if dp_after(&lower, "w") {
                7
            } else if dp_after(&lower, "h") {
                8
            } else if matches!(lower.as_str(), "small" | "normal" | "large" | "xlarge") {
                9
            } else if matches!(lower.as_str(), "long" | "notlong") {
                10
            } else if matches!(lower.as_str(), "round" | "notround") {
                11
            } else if matches!(lower.as_str(), "widecg" | "nowidecg") {
                12
            } else if matches!(lower.as_str(), "highdr" | "lowdr") {
                13
            } else if matches!(lower.as_str(), "port" | "land" | "square") {
                14
            } else if matches!(
                lower.as_str(),
                "car" | "desk" | "television" | "appliance" | "watch" | "vrheadset"
            ) {
                15
            } else if matches!(lower.as_str(), "night" | "notnight") {
                16
            } else if matches!(
                lower.as_str(),
                "ldpi"
                    | "mdpi"
                    | "hdpi"
                    | "xhdpi"
                    | "xxhdpi"
                    | "xxxhdpi"
                    | "nodpi"
                    | "tvdpi"
                    | "anydpi"
            ) || lower.strip_suffix("dpi").is_some_and(|value| {
                !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit())
            }) {
                if lower.strip_suffix("dpi").is_some_and(|value| {
                    !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit())
                }) && unsigned(&lower[..lower.len() - 3], 1u64 << 32) == 0
                {
                    return Err(invalid_configuration());
                }
                17
            } else if matches!(lower.as_str(), "notouch" | "stylus" | "finger") {
                18
            } else if matches!(lower.as_str(), "keysexposed" | "keyshidden" | "keyssoft") {
                19
            } else if matches!(lower.as_str(), "nokeys" | "qwerty" | "12key") {
                20
            } else if matches!(lower.as_str(), "navexposed" | "navhidden") {
                21
            } else if matches!(lower.as_str(), "nonav" | "dpad" | "trackball" | "wheel") {
                22
            } else if let Some((width, height)) = pixel_dimensions(&lower) {
                if width < height {
                    return Err(invalid_configuration());
                }
                23
            } else if digits_after(&lower, "v", 1, usize::MAX) {
                bounded_version(&lower[1..])?;
                24
            } else if (2..=3).contains(&lower.len())
                && lower.bytes().all(|byte| byte.is_ascii_alphabetic())
            {
                if locale.is_some() {
                    return Err(invalid_configuration());
                }
                locale = Some(lower);
                2
            } else {
                return Err(invalid_configuration());
            };
            if previous.is_some_and(|before| rank <= before) {
                return Err(invalid_configuration());
            }
            previous = Some(rank);
            qualifiers.push(qualifier.to_owned());
        }
        Ok(Self {
            path: path.to_owned(),
            qualifiers,
            locale,
            path_feature_flag,
        })
    }

    pub(crate) fn effective_key(&self) -> String {
        let mut normalized = self.locale.clone().unwrap_or_default();
        let mut explicit_version = 0;
        let mut implicit_version = 0;
        for original in &self.qualifiers {
            let mut qualifier = original.to_ascii_lowercase();
            if self.is_locale_qualifier(&qualifier) {
                continue;
            }
            if let Some(value) = qualifier.strip_prefix("mcc") {
                qualifier = format!("mcc{}", unsigned(value, 65_536));
            } else if let Some(value) = qualifier.strip_prefix("mnc") {
                let network = unsigned(value, 65_536);
                qualifier = format!("mnc{}", if network == 0 { 65_535 } else { network });
            } else if qualifier.ends_with("dp") {
                let prefix = if qualifier.starts_with("sw") { 2 } else { 1 };
                let value = unsigned(&qualifier[prefix..qualifier.len() - 2], 65_536);
                if value == 0 {
                    continue;
                }
                qualifier = format!("{}{value}dp", &qualifier[..prefix]);
                implicit_version = implicit_version.max(13);
            } else if let Some(value) = density(&qualifier) {
                if value == 0 {
                    continue;
                }
                qualifier = format!("density{value}");
                implicit_version = implicit_version.max(if value == 65_534 { 21 } else { 4 });
            } else if let Some((width, height)) = pixel_dimensions(&qualifier) {
                if width == 0 && height == 0 {
                    continue;
                }
                qualifier = format!("{width}x{height}");
            } else if let Some(value) = qualifier.strip_prefix('v') {
                explicit_version = bounded_version(value).expect("already validated SDK version");
                continue;
            } else if matches!(qualifier.as_str(), "masculine" | "feminine" | "neuter") {
                implicit_version = implicit_version.max(34);
            } else if matches!(
                qualifier.as_str(),
                "widecg" | "nowidecg" | "highdr" | "lowdr" | "vrheadset"
            ) {
                implicit_version = implicit_version.max(26);
            } else if matches!(qualifier.as_str(), "round" | "notround") {
                implicit_version = implicit_version.max(23);
            } else if matches!(
                qualifier.as_str(),
                "car" | "desk" | "television" | "appliance" | "watch" | "night" | "notnight"
            ) {
                implicit_version = implicit_version.max(8);
            } else if matches!(
                qualifier.as_str(),
                "small" | "normal" | "large" | "xlarge" | "long" | "notlong"
            ) {
                implicit_version = implicit_version.max(4);
            }
            normalized.push('|');
            normalized.push_str(&qualifier);
        }
        let version = explicit_version.max(implicit_version);
        if version != 0 {
            normalized.push_str(&format!("|v{version}"));
        }
        normalized
    }

    fn is_locale_qualifier(&self, qualifier: &str) -> bool {
        if qualifier.starts_with("b+") {
            return true;
        }
        self.locale.as_ref().is_some_and(|locale| {
            qualifier == locale.split('-').next().unwrap_or_default()
                || qualifier.starts_with('r')
                    && (qualifier[1..].eq_ignore_ascii_case(&locale[locale.len() - 2..])
                        || qualifier.eq_ignore_ascii_case(locale))
        })
    }
}

fn unsigned(digits: &str, modulus: u64) -> u64 {
    digits.bytes().fold(0, |result, digit| {
        (result * 10 + u64::from(digit - b'0')) % modulus
    })
}

fn bounded_version(digits: &str) -> Result<u16, ParseError> {
    let mut result = 0u16;
    for digit in digits.bytes() {
        result = result
            .checked_mul(10)
            .and_then(|value| value.checked_add(u16::from(digit - b'0')))
            .ok_or_else(invalid_configuration)?;
    }
    Ok(result)
}

fn pixel_dimensions(qualifier: &str) -> Option<(u64, u64)> {
    let (width, height) = qualifier.split_once('x')?;
    if width.is_empty()
        || height.is_empty()
        || !width.bytes().all(|digit| digit.is_ascii_digit())
        || !height.bytes().all(|digit| digit.is_ascii_digit())
    {
        return None;
    }
    Some((unsigned(width, 65_536), unsigned(height, 65_536)))
}

fn density(qualifier: &str) -> Option<u64> {
    let value = match qualifier {
        "ldpi" => 120,
        "mdpi" => 160,
        "tvdpi" => 213,
        "hdpi" => 240,
        "xhdpi" => 320,
        "xxhdpi" => 480,
        "xxxhdpi" => 640,
        "anydpi" => 65_534,
        "nodpi" => 65_535,
        _ => {
            let digits = qualifier.strip_suffix("dpi")?;
            if digits.is_empty() || !digits.bytes().all(|digit| digit.is_ascii_digit()) {
                return None;
            }
            unsigned(digits, 65_536)
        }
    };
    Some(value)
}

fn digits_after(value: &str, prefix: &str, minimum: usize, maximum: usize) -> bool {
    value.strip_prefix(prefix).is_some_and(|digits| {
        (minimum..=maximum).contains(&digits.len())
            && digits.bytes().all(|byte| byte.is_ascii_digit())
    })
}

fn dp_after(value: &str, prefix: &str) -> bool {
    value
        .strip_prefix(prefix)
        .and_then(|suffix| suffix.strip_suffix("dp"))
        .is_some_and(|digits| {
            !digits.is_empty() && digits.bytes().all(|byte| byte.is_ascii_digit())
        })
}

fn parse_bcp47(qualifier: &str) -> Result<String, ParseError> {
    let mut subtags = qualifier[2..].split('+');
    let language = subtags.next().ok_or_else(invalid_configuration)?;
    if !(2..=3).contains(&language.len())
        || !language.bytes().all(|byte| byte.is_ascii_alphabetic())
    {
        return Err(invalid_configuration());
    }
    let mut locale = language.to_ascii_lowercase();
    for subtag in subtags {
        if !(2..=8).contains(&subtag.len())
            || !subtag.bytes().all(|byte| byte.is_ascii_alphanumeric())
        {
            return Err(invalid_configuration());
        }
        locale.push('-');
        if subtag.len() == 4 && subtag.bytes().all(|byte| byte.is_ascii_alphabetic()) {
            let mut characters = subtag.chars();
            locale.push(characters.next().unwrap().to_ascii_uppercase());
            locale.push_str(characters.as_str().to_ascii_lowercase().as_str());
        } else if subtag.len() == 2 && subtag.bytes().all(|byte| byte.is_ascii_alphabetic()) {
            locale.push_str(&subtag.to_ascii_uppercase());
        } else {
            locale.push_str(&subtag.to_ascii_lowercase());
        }
    }
    Ok(locale)
}

fn invalid_path() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_RESOURCE_PATH",
        "Invalid Android resource path",
    )
}

fn invalid_configuration() -> ParseError {
    ParseError::new(
        "INVALID_ANDROID_CONFIGURATION",
        "Invalid Android resource directory configuration",
    )
}
