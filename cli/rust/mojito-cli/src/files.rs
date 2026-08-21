use crate::{Cli, Error, Result};
use mojito_file_formats::{parse_for_mojito, FileFormat, FilterOptions};
use regex::Regex;
use std::fs;
use std::path::{Component, Path, PathBuf};
use walkdir::WalkDir;

const FORMATJS_DEFAULT_OPTIONS: &[&str] = &[
    "noteKeyPattern=description",
    "extractAllPairs=false",
    "exceptions=defaultMessage",
    "removeKeySuffix=/defaultMessage",
    "filePositionPathKeyPattern=file",
    "filePositionLineKeyPattern=line",
    "filePositionColKeyPattern=col",
];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FileType {
    Android,
    AppleStrings,
    AppleStringsdict,
    Properties,
    PropertiesNoBasename,
    PropertiesJava,
    Resw,
    Resx,
    Gettext,
    Xtb,
    Csv,
    CsvAdobeMagento,
    JavaScript,
    Json,
    JsonNoBasename,
    ChromeExtensionJson,
    FormatJsJsonNoBasename,
    VsCodeJson,
    I18NextJson,
    TypeScript,
    Yaml,
    Html,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SourceFile {
    pub path: PathBuf,
    pub source_path: String,
    pub file_type: FileType,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Encoding {
    Utf8,
    Utf8Bom,
    Utf16Le,
    Utf16Be,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TextFile {
    pub text: String,
    encoding: Encoding,
}

impl FileType {
    pub fn parse(value: &str) -> Result<Self> {
        match value.to_ascii_uppercase().as_str() {
            "ANDROID_STRINGS" => Ok(Self::Android),
            "MAC_STRING" => Ok(Self::AppleStrings),
            "MAC_STRINGSDICT" => Ok(Self::AppleStringsdict),
            "PROPERTIES" => Ok(Self::Properties),
            "PROPERTIES_NOBASENAME" => Ok(Self::PropertiesNoBasename),
            "PROPERTIES_JAVA" => Ok(Self::PropertiesJava),
            "RESW" => Ok(Self::Resw),
            "RESX" => Ok(Self::Resx),
            "PO" => Ok(Self::Gettext),
            "XTB" => Ok(Self::Xtb),
            "CSV" => Ok(Self::Csv),
            "CSV_ADOBE_MAGENTO" => Ok(Self::CsvAdobeMagento),
            "JS" => Ok(Self::JavaScript),
            "JSON" => Ok(Self::Json),
            "JSON_NOBASENAME" => Ok(Self::JsonNoBasename),
            "CHROME_EXT_JSON" => Ok(Self::ChromeExtensionJson),
            "FORMATJS_JSON_NOBASENAME" => Ok(Self::FormatJsJsonNoBasename),
            "VSCODE_EXTENSION_JSON" => Ok(Self::VsCodeJson),
            "I18NEXT_PARSER_JSON" => Ok(Self::I18NextJson),
            "TS" => Ok(Self::TypeScript),
            "YAML" => Ok(Self::Yaml),
            "HTML_ALPHA" => Ok(Self::Html),
            "XLIFF" | "XCODE_XLIFF" => Err(Error::new(format!(
                "UNSUPPORTED_PORTABLE_FORMAT: {value} still requires a defined bilingual XLIFF contract"
            ))),
            _ => Err(Error::new(format!("unsupported file type: {value}"))),
        }
    }

    fn defaults() -> &'static [Self] {
        &[
            Self::Properties,
            Self::Android,
            Self::AppleStrings,
            Self::AppleStringsdict,
            Self::Resw,
            Self::Resx,
            Self::Gettext,
            Self::Xtb,
            Self::Csv,
            Self::JavaScript,
            Self::TypeScript,
            Self::Yaml,
        ]
    }

    pub fn format(self) -> FileFormat {
        match self {
            Self::Android => FileFormat::Android,
            Self::AppleStrings => FileFormat::AppleStrings,
            Self::AppleStringsdict => FileFormat::AppleStringsdict,
            Self::Properties | Self::PropertiesNoBasename | Self::PropertiesJava => {
                FileFormat::JavaProperties
            }
            Self::Resw | Self::Resx => FileFormat::Resx,
            Self::Gettext => FileFormat::GettextPo,
            Self::Xtb => FileFormat::Xtb,
            Self::Csv => FileFormat::Csv,
            Self::CsvAdobeMagento => FileFormat::CsvAdobeMagento,
            Self::JavaScript => FileFormat::JavaScript,
            Self::Json
            | Self::JsonNoBasename
            | Self::ChromeExtensionJson
            | Self::FormatJsJsonNoBasename
            | Self::VsCodeJson
            | Self::I18NextJson => FileFormat::FormatJsJson,
            Self::TypeScript => FileFormat::TypeScript,
            Self::Yaml => FileFormat::Yaml,
            Self::Html => FileFormat::Html,
        }
    }

    pub fn filter_override(self) -> Option<&'static str> {
        match self {
            Self::PropertiesJava => Some("PROPERTIES_JAVA"),
            Self::CsvAdobeMagento => Some("CSV_ADOBE_MAGENTO"),
            Self::Html => Some("HTML_ALPHA"),
            _ => None,
        }
    }

    pub fn filter_options(self, provided: Option<&[String]>) -> Result<Vec<String>> {
        let mut options = if let Some(options) = provided {
            options.to_vec()
        } else if self == Self::FormatJsJsonNoBasename {
            FORMATJS_DEFAULT_OPTIONS
                .iter()
                .map(|value| (*value).to_owned())
                .collect()
        } else if self == Self::ChromeExtensionJson {
            [
                "noteKeyPattern=description",
                "extractAllPairs=false",
                "exceptions=message",
            ]
            .into_iter()
            .map(str::to_owned)
            .collect()
        } else {
            Vec::new()
        };
        options.retain(|option| option != "mojito.converter=portable");
        FilterOptions::parse(self.format(), &options)?;
        options.push("mojito.converter=portable".to_owned());
        Ok(options)
    }

    fn extension(self) -> &'static str {
        match self {
            Self::Android => "xml",
            Self::AppleStrings => "strings",
            Self::AppleStringsdict => "stringsdict",
            Self::Properties | Self::PropertiesNoBasename | Self::PropertiesJava => "properties",
            Self::Resw => "resw",
            Self::Resx => "resx",
            Self::Gettext => "pot",
            Self::Xtb => "xtb",
            Self::Csv | Self::CsvAdobeMagento => "csv",
            Self::JavaScript => "js",
            Self::Json
            | Self::JsonNoBasename
            | Self::ChromeExtensionJson
            | Self::FormatJsJsonNoBasename
            | Self::VsCodeJson
            | Self::I18NextJson => "json",
            Self::TypeScript => "ts",
            Self::Yaml => "yaml",
            Self::Html => "html",
        }
    }
}

pub fn discover(cli: &Cli) -> Result<Vec<SourceFile>> {
    let root = cli
        .source_directory
        .clone()
        .unwrap_or(std::env::current_dir()?);
    if !root.is_dir() {
        return Err(Error::new(format!(
            "invalid source directory: {}",
            root.display()
        )));
    }
    let using_default_file_types = cli.file_types.is_empty();
    let file_types = if using_default_file_types {
        FileType::defaults().to_vec()
    } else {
        cli.file_types
            .iter()
            .map(|value| FileType::parse(value))
            .collect::<Result<Vec<_>>>()?
    };
    let source_regex = cli
        .source_regex
        .as_ref()
        .map(|pattern| {
            Regex::new(&format!("^(?:{pattern})$"))
                .map_err(|error| Error::new(format!("invalid source regex: {error}")))
        })
        .transpose()?;

    let mut matches = Vec::new();
    let walker = WalkDir::new(&root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| {
            !entry.file_type().is_dir()
                || should_scan_directory(
                    &root,
                    entry.path(),
                    &cli.include_patterns,
                    &cli.exclude_patterns,
                )
        });

    for entry in walker {
        let entry =
            entry.map_err(|error| Error::new(format!("cannot scan source files: {error}")))?;
        if !(entry.file_type().is_file()
            || entry.file_type().is_symlink() && entry.path().is_file())
        {
            continue;
        }
        let relative = entry
            .path()
            .strip_prefix(&root)
            .map_err(|error| Error::new(error.to_string()))?;
        let source_path = unix_path(relative);
        if source_regex
            .as_ref()
            .is_some_and(|pattern| !pattern.is_match(&source_path))
        {
            continue;
        }
        if using_default_file_types
            && relative
                .extension()
                .and_then(|extension| extension.to_str())
                == Some("xliff")
        {
            return Err(Error::new(format!(
                "UNSUPPORTED_PORTABLE_FORMAT: XLIFF source candidate `{source_path}` still requires a defined bilingual XLIFF contract"
            )));
        }

        for file_type in &file_types {
            if matches_source(
                *file_type,
                relative,
                &cli.source_locale,
                &root,
                source_regex.as_ref(),
            ) {
                matches.push(SourceFile {
                    path: entry.path().to_path_buf(),
                    source_path: source_path.clone(),
                    file_type: *file_type,
                });
            }
        }
    }
    matches.sort_by(|left, right| left.source_path.cmp(&right.source_path));
    matches.dedup_by(|left, right| {
        left.source_path == right.source_path && left.file_type == right.file_type
    });
    Ok(matches)
}

fn matches_source(
    file_type: FileType,
    path: &Path,
    source_locale: &str,
    root: &Path,
    source_regex: Option<&Regex>,
) -> bool {
    let Some(file_name) = path.file_name().and_then(|name| name.to_str()) else {
        return false;
    };
    let extension = path.extension().and_then(|value| value.to_str());
    if extension != Some(file_type.extension()) {
        return false;
    }
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or_default();
    let components: Vec<&str> = path
        .components()
        .filter_map(|component| match component {
            Component::Normal(value) => value.to_str(),
            _ => None,
        })
        .collect();
    let parent = components.get(components.len().saturating_sub(2)).copied();

    match file_type {
        FileType::Android => {
            file_name == "strings.xml"
                && components
                    .windows(3)
                    .any(|parts| parts == ["res", "values", "strings.xml"])
        }
        FileType::AppleStrings | FileType::AppleStringsdict => {
            parent == Some(&*format!("{source_locale}.lproj"))
        }
        FileType::PropertiesNoBasename
        | FileType::JsonNoBasename
        | FileType::FormatJsJsonNoBasename
        | FileType::JavaScript
        | FileType::TypeScript => stem == source_locale,
        FileType::Resw => parent == Some(source_locale),
        FileType::Gettext => true,
        FileType::Xtb => stem.ends_with(&format!("-{source_locale}")),
        FileType::CsvAdobeMagento => {
            parent == Some("i18n")
                && (stem == source_locale || stem == source_locale.replace('-', "_"))
        }
        FileType::ChromeExtensionJson => {
            file_name == "messages.json"
                && components
                    .windows(3)
                    .any(|parts| parts == ["_locales", source_locale, "messages.json"])
        }
        FileType::I18NextJson => components
            .windows(2)
            .any(|parts| parts == ["locales", source_locale]),
        FileType::VsCodeJson => matches!(stem, "package.nls" | "bundle.l10n"),
        FileType::Properties
        | FileType::PropertiesJava
        | FileType::Resx
        | FileType::Csv
        | FileType::Json
        | FileType::Yaml
        | FileType::Html => !is_localized_sibling(file_type, path, root, source_regex),
    }
}

fn is_localized_sibling(
    file_type: FileType,
    path: &Path,
    root: &Path,
    source_regex: Option<&Regex>,
) -> bool {
    let Some(stem) = path.file_stem().and_then(|value| value.to_str()) else {
        return false;
    };
    let Some(extension) = path.extension().and_then(|value| value.to_str()) else {
        return false;
    };
    let separator = match file_type {
        FileType::Resx => '.',
        FileType::Properties
        | FileType::PropertiesJava
        | FileType::Csv
        | FileType::Json
        | FileType::Yaml
        | FileType::Html => '_',
        _ => return false,
    };
    for (index, _) in stem.match_indices(separator).rev() {
        let base = &stem[..index];
        let locale = &stem[index + separator.len_utf8()..];
        let base_path = path.with_file_name(format!("{base}.{extension}"));
        let base_source_path = unix_path(&base_path);
        if !locale.is_empty()
            && locale.chars().all(|character| {
                character.is_ascii_alphanumeric()
                    || matches!(character, '-' | '_')
                    || (file_type == FileType::PropertiesJava && character == '#')
            })
            && source_regex.is_none_or(|pattern| pattern.is_match(&base_source_path))
            && root.join(base_path).is_file()
        {
            return true;
        }
    }
    false
}

fn should_scan_directory(
    root: &Path,
    directory: &Path,
    includes: &[String],
    excludes: &[String],
) -> bool {
    if directory == root {
        return true;
    }
    let Ok(relative) = directory.strip_prefix(root) else {
        return false;
    };
    let components: Vec<&str> = relative
        .components()
        .filter_map(|component| component.as_os_str().to_str())
        .collect();
    if excludes.iter().any(|pattern| {
        let pattern: Vec<&str> = pattern.split('/').collect();
        pattern.len() == components.len() && directory_prefix_matches(&pattern, &components)
    }) {
        return false;
    }
    includes.is_empty()
        || includes.iter().any(|pattern| {
            let pattern: Vec<&str> = pattern.split('/').collect();
            directory_prefix_matches(&pattern, &components)
        })
}

fn directory_prefix_matches(pattern: &[&str], directory: &[&str]) -> bool {
    pattern
        .iter()
        .zip(directory.iter())
        .all(|(expected, actual)| *expected == "*" || expected == actual)
}

impl SourceFile {
    pub fn target_path(&self, output_locale: &str, source_locale: &str) -> Result<PathBuf> {
        validate_output_locale(output_locale)?;
        let source = Path::new(&self.source_path);
        let parent = source.parent().unwrap_or_else(|| Path::new(""));
        let file_name = source
            .file_name()
            .and_then(|value| value.to_str())
            .ok_or_else(|| Error::new("source file path is not valid Unicode"))?;
        let stem = source
            .file_stem()
            .and_then(|value| value.to_str())
            .ok_or_else(|| Error::new("source file name is not valid Unicode"))?;
        let extension = self.file_type.extension();

        Ok(match self.file_type {
            FileType::Android => {
                let locale = android_locale(output_locale);
                parent
                    .parent()
                    .unwrap_or_else(|| Path::new(""))
                    .join(format!("values-{locale}"))
                    .join(file_name)
            }
            FileType::AppleStrings | FileType::AppleStringsdict => parent
                .parent()
                .unwrap_or_else(|| Path::new(""))
                .join(format!("{output_locale}.lproj"))
                .join(file_name),
            FileType::PropertiesNoBasename
            | FileType::JsonNoBasename
            | FileType::FormatJsJsonNoBasename
            | FileType::JavaScript
            | FileType::TypeScript => parent.join(format!("{output_locale}.{extension}")),
            FileType::PropertiesJava => parent.join(format!(
                "{stem}_{}.{extension}",
                java_locale_representation(output_locale)?
            )),
            FileType::Properties
            | FileType::Csv
            | FileType::Json
            | FileType::Yaml
            | FileType::Html => parent.join(format!("{stem}_{output_locale}.{extension}")),
            FileType::Resx | FileType::VsCodeJson => {
                parent.join(format!("{stem}.{output_locale}.{extension}"))
            }
            FileType::Resw => parent
                .parent()
                .unwrap_or_else(|| Path::new(""))
                .join(output_locale)
                .join(file_name),
            FileType::Gettext => {
                let locale = output_locale.replace('-', "_");
                if parent.file_name().and_then(|value| value.to_str()) == Some("LC_MESSAGES") {
                    parent
                        .parent()
                        .unwrap_or_else(|| Path::new(""))
                        .join(locale)
                        .join("LC_MESSAGES")
                        .join(format!("{stem}.po"))
                } else {
                    parent.join(locale).join(format!("{stem}.po"))
                }
            }
            FileType::Xtb => {
                let prefix = stem
                    .strip_suffix(&format!("-{source_locale}"))
                    .ok_or_else(|| Error::new("XTB source does not contain its source locale"))?;
                parent.join(format!("{prefix}-{output_locale}.xtb"))
            }
            FileType::CsvAdobeMagento | FileType::ChromeExtensionJson => {
                let locale = output_locale.replace('-', "_");
                if self.file_type == FileType::CsvAdobeMagento {
                    parent.join(format!("{locale}.csv"))
                } else {
                    parent
                        .parent()
                        .unwrap_or_else(|| Path::new(""))
                        .join(locale)
                        .join(file_name)
                }
            }
            FileType::I18NextJson => parent
                .parent()
                .unwrap_or_else(|| Path::new(""))
                .join(output_locale)
                .join(file_name),
        })
    }

    pub fn read(&self, validate: bool, filter_options: &[String]) -> Result<TextFile> {
        let bytes = fs::read(&self.path)?;
        let platform_options: Vec<String> = filter_options
            .iter()
            .filter(|option| option.as_str() != "mojito.converter=portable")
            .cloned()
            .collect();
        if validate {
            parse_for_mojito(self.file_type.format(), &bytes, &platform_options)?;
        }
        decode_text(&bytes)
    }
}

fn validate_output_locale(output_locale: &str) -> Result<()> {
    let path = Path::new(output_locale);
    if output_locale.is_empty()
        || path.is_absolute()
        || output_locale
            .chars()
            .any(|character| matches!(character, '/' | '\\'))
        || path.components().any(|component| {
            matches!(
                component,
                Component::Prefix(_)
                    | Component::RootDir
                    | Component::ParentDir
                    | Component::CurDir
            )
        })
    {
        return Err(Error::new(format!(
            "unsafe output locale `{output_locale}`: expected one relative path component"
        )));
    }
    Ok(())
}

impl TextFile {
    pub fn read(path: &Path) -> Result<Self> {
        decode_text(&fs::read(path)?)
    }

    pub fn write_like(&self, path: &Path, content: &str) -> Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let bytes = match self.encoding {
            Encoding::Utf8 => content.as_bytes().to_vec(),
            Encoding::Utf8Bom => {
                let mut bytes = vec![0xef, 0xbb, 0xbf];
                bytes.extend_from_slice(content.as_bytes());
                bytes
            }
            Encoding::Utf16Le | Encoding::Utf16Be => {
                let little_endian = self.encoding == Encoding::Utf16Le;
                let mut bytes = if little_endian {
                    vec![0xff, 0xfe]
                } else {
                    vec![0xfe, 0xff]
                };
                for unit in content.encode_utf16() {
                    let encoded = if little_endian {
                        unit.to_le_bytes()
                    } else {
                        unit.to_be_bytes()
                    };
                    bytes.extend_from_slice(&encoded);
                }
                bytes
            }
        };
        fs::write(path, bytes)?;
        Ok(())
    }
}

fn decode_text(bytes: &[u8]) -> Result<TextFile> {
    if let Some(content) = bytes.strip_prefix(&[0xff, 0xfe]) {
        decode_utf16(content, true)
    } else if let Some(content) = bytes.strip_prefix(&[0xfe, 0xff]) {
        decode_utf16(content, false)
    } else {
        let (encoding, content) = if let Some(content) = bytes.strip_prefix(&[0xef, 0xbb, 0xbf]) {
            (Encoding::Utf8Bom, content)
        } else {
            (Encoding::Utf8, bytes)
        };
        Ok(TextFile {
            text: std::str::from_utf8(content)
                .map_err(|error| Error::new(format!("invalid UTF-8 source file: {error}")))?
                .to_owned(),
            encoding,
        })
    }
}

fn decode_utf16(bytes: &[u8], little_endian: bool) -> Result<TextFile> {
    if bytes.len() % 2 != 0 {
        return Err(Error::new("invalid UTF-16 source file: odd byte length"));
    }
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|unit| {
            if little_endian {
                u16::from_le_bytes([unit[0], unit[1]])
            } else {
                u16::from_be_bytes([unit[0], unit[1]])
            }
        })
        .collect();
    Ok(TextFile {
        text: String::from_utf16(&units)
            .map_err(|error| Error::new(format!("invalid UTF-16 source file: {error}")))?,
        encoding: if little_endian {
            Encoding::Utf16Le
        } else {
            Encoding::Utf16Be
        },
    })
}

fn android_locale(locale: &str) -> String {
    let parts: Vec<&str> = locale.split('-').collect();
    if let Some(region) = parts.iter().skip(1).find(|part| {
        (part.len() == 2
            && part
                .chars()
                .all(|character| character.is_ascii_alphabetic()))
            || (part.len() == 3 && part.chars().all(|character| character.is_ascii_digit()))
    }) {
        format!("{}-r{}", parts[0], region.to_ascii_uppercase())
    } else {
        parts[0].to_owned()
    }
}

fn java_locale_representation(language_tag: &str) -> Result<String> {
    const GRANDFATHERED_TAGS: &[&str] = &[
        "art-lojban",
        "cel-gaulish",
        "en-gb-oed",
        "i-ami",
        "i-bnn",
        "i-default",
        "i-enochian",
        "i-hak",
        "i-klingon",
        "i-lux",
        "i-mingo",
        "i-navajo",
        "i-pwn",
        "i-tao",
        "i-tay",
        "i-tsu",
        "no-bok",
        "no-nyn",
        "sgn-be-fr",
        "sgn-be-nl",
        "sgn-ch-de",
        "zh-guoyu",
        "zh-hakka",
        "zh-min",
        "zh-min-nan",
        "zh-xiang",
    ];

    let invalid = |reason: &str| {
        Error::new(format!(
            "invalid or unsupported PROPERTIES_JAVA locale `{language_tag}`: {reason}"
        ))
    };
    if GRANDFATHERED_TAGS
        .iter()
        .any(|tag| language_tag.eq_ignore_ascii_case(tag))
    {
        return Err(invalid("grandfathered language tags are not supported"));
    }

    let parts: Vec<&str> = language_tag.split('-').collect();
    if parts.is_empty() || parts.iter().any(|part| part.is_empty()) {
        return Err(invalid("expected non-empty subtags separated by `-`"));
    }

    let language = parts[0];
    if !(2..=8).contains(&language.len())
        || !language
            .chars()
            .all(|character| character.is_ascii_alphabetic())
    {
        return Err(invalid(
            "the language subtag must contain 2 to 8 ASCII letters",
        ));
    }
    let mut language = match language.to_ascii_lowercase().as_str() {
        "und" => String::new(),
        "iw" => "he".to_owned(),
        "ji" => "yi".to_owned(),
        "in" => "id".to_owned(),
        language => language.to_owned(),
    };

    let mut index = 1;
    let script = parts.get(index).filter(|part| {
        part.len() == 4
            && part
                .chars()
                .all(|character| character.is_ascii_alphabetic())
    });
    let script = script.map(|script| {
        index += 1;
        let mut script = script.to_ascii_lowercase();
        script[..1].make_ascii_uppercase();
        script
    });

    let region = parts.get(index).filter(|part| {
        (part.len() == 2
            && part
                .chars()
                .all(|character| character.is_ascii_alphabetic()))
            || (part.len() == 3 && part.chars().all(|character| character.is_ascii_digit()))
    });
    let region = region.map(|region| {
        index += 1;
        region.to_ascii_uppercase()
    });

    let mut variants = Vec::new();
    while let Some(variant) = parts.get(index).filter(|part| {
        ((5..=8).contains(&part.len())
            || (part.len() == 4
                && part
                    .chars()
                    .next()
                    .is_some_and(|character| character.is_ascii_digit())))
            && part
                .chars()
                .all(|character| character.is_ascii_alphanumeric())
    }) {
        if variants
            .iter()
            .any(|existing: &&str| existing.eq_ignore_ascii_case(variant))
        {
            return Err(invalid("variant subtags must not be repeated"));
        }
        variants.push(*variant);
        index += 1;
    }

    let private_use = if parts
        .get(index)
        .is_some_and(|part| part.eq_ignore_ascii_case("x"))
    {
        index += 1;
        let private_parts = &parts[index..];
        if private_parts.is_empty()
            || private_parts.iter().any(|part| {
                !(1..=8).contains(&part.len())
                    || !part
                        .chars()
                        .all(|character| character.is_ascii_alphanumeric())
            })
        {
            return Err(invalid(
                "private-use subtags must contain 1 to 8 ASCII letters or digits",
            ));
        }
        index = parts.len();
        Some(format!(
            "x-{}",
            private_parts.join("-").to_ascii_lowercase()
        ))
    } else {
        None
    };

    if index != parts.len() {
        return Err(invalid(
            "expected language, optional script and region, variants, and optional private use",
        ));
    }

    if language.is_empty() && region.is_none() {
        return Ok(String::new());
    }

    if script.is_some() || private_use.is_some() {
        language.push('_');
        if let Some(region) = &region {
            language.push_str(region);
        }
        language.push('_');
        if !variants.is_empty() {
            language.push_str(&variants.join("_"));
            language.push('_');
        }
        language.push('#');
        if let Some(script) = &script {
            language.push_str(script);
            if private_use.is_some() {
                language.push('_');
            }
        }
        if let Some(private_use) = &private_use {
            language.push_str(private_use);
        }
        return Ok(language);
    }

    if region.is_some() || !variants.is_empty() {
        language.push('_');
        if let Some(region) = &region {
            language.push_str(region);
        }
    }
    if !variants.is_empty() {
        language.push('_');
        language.push_str(&variants.join("_"));
    }
    Ok(language)
}

fn unix_path(path: &Path) -> String {
    path.components()
        .map(|component| component.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cli(arguments: &[&str]) -> Cli {
        Cli::parse(
            &arguments
                .iter()
                .map(|value| (*value).to_owned())
                .collect::<Vec<_>>(),
        )
        .unwrap()
    }

    #[test]
    fn finds_only_source_locale_for_non_basename_json() {
        let directory = tempfile::tempdir().unwrap();
        let folder = directory.path().join("lang-mojito");
        fs::create_dir_all(&folder).unwrap();
        fs::write(folder.join("en.json"), "{}").unwrap();
        fs::write(folder.join("fr.json"), "{}").unwrap();
        let mut cli = cli(&[
            "push",
            "-r",
            "repo",
            "-ft",
            "JSON_NOBASENAME",
            "--dir-path-include-patterns",
            "lang-mojito",
        ]);
        cli.source_directory = Some(directory.path().to_owned());

        let files = discover(&cli).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].source_path, "lang-mojito/en.json");
        assert_eq!(
            files[0].target_path("fr-FR", "en").unwrap(),
            PathBuf::from("lang-mojito/fr-FR.json")
        );
    }

    #[test]
    fn does_not_mistake_multi_segment_java_locales_for_source_assets() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("messages.properties"), "hello=world").unwrap();
        fs::write(
            directory.path().join("messages_fr_CA.properties"),
            "hello=bonjour",
        )
        .unwrap();
        let mut cli = cli(&["push", "-r", "repo", "-ft", "PROPERTIES_JAVA"]);
        cli.source_directory = Some(directory.path().to_owned());

        let sources = discover(&cli).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "messages.properties");
    }

    #[test]
    fn preserves_dotted_json_source_names() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("messages.json"), "{}").unwrap();
        fs::write(directory.path().join("messages.mobile.json"), "{}").unwrap();
        fs::write(directory.path().join("messages_fr-FR.json"), "{}").unwrap();
        let mut cli = cli(&["push", "-r", "repo", "-ft", "JSON"]);
        cli.source_directory = Some(directory.path().to_owned());

        let sources = discover(&cli).unwrap();
        assert_eq!(
            sources
                .iter()
                .map(|source| source.source_path.as_str())
                .collect::<Vec<_>>(),
            ["messages.json", "messages.mobile.json"]
        );
    }

    #[test]
    fn source_regex_reclassifies_a_localized_name_without_a_selected_base() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("messages.json"), "{}").unwrap();
        fs::write(directory.path().join("messages_fr.json"), "{}").unwrap();
        let mut cli = cli(&[
            "push",
            "-r",
            "repo",
            "-ft",
            "JSON",
            "-sr",
            r"messages_fr\.json",
        ]);
        cli.source_directory = Some(directory.path().to_owned());

        let sources = discover(&cli).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "messages_fr.json");
    }

    #[test]
    fn default_scan_rejects_selected_xliff_but_respects_source_regex() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("messages.properties"), "hello=world").unwrap();
        fs::write(directory.path().join("messages.xliff"), "<xliff/>").unwrap();

        let mut defaults = cli(&["push", "-r", "repo"]);
        defaults.source_directory = Some(directory.path().to_owned());
        let error = discover(&defaults).unwrap_err();
        assert!(error.to_string().contains("UNSUPPORTED_PORTABLE_FORMAT"));
        assert!(error.to_string().contains("messages.xliff"));

        let mut filtered = cli(&["push", "-r", "repo", "-sr", r"messages\.properties"]);
        filtered.source_directory = Some(directory.path().to_owned());
        let sources = discover(&filtered).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "messages.properties");

        let mut explicit_properties = cli(&["push", "-r", "repo", "-ft", "PROPERTIES"]);
        explicit_properties.source_directory = Some(directory.path().to_owned());
        let sources = discover(&explicit_properties).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "messages.properties");
    }

    #[test]
    fn default_xliff_rejection_respects_directory_scope() {
        let directory = tempfile::tempdir().unwrap();
        let selected = directory.path().join("selected");
        let ignored = directory.path().join("ignored");
        fs::create_dir_all(&selected).unwrap();
        fs::create_dir_all(&ignored).unwrap();
        fs::write(selected.join("messages.properties"), "hello=world").unwrap();
        fs::write(ignored.join("messages.xliff"), "<xliff/>").unwrap();

        let mut cli = cli(&[
            "push",
            "-r",
            "repo",
            "--dir-path-include-patterns",
            "selected",
        ]);
        cli.source_directory = Some(directory.path().to_owned());

        let sources = discover(&cli).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "selected/messages.properties");
    }

    #[cfg(unix)]
    #[test]
    fn scans_symlinked_files_without_following_symlinked_directories() {
        use std::os::unix::fs::symlink;

        let directory = tempfile::tempdir().unwrap();
        let source_root = directory.path().join("source");
        let external_root = directory.path().join("external");
        fs::create_dir_all(&source_root).unwrap();
        fs::create_dir_all(&external_root).unwrap();
        let external_file = directory.path().join("actual-source");
        fs::write(&external_file, "hello=world").unwrap();
        fs::write(external_root.join("nested.properties"), "ignored=value").unwrap();
        symlink(&external_file, source_root.join("messages.properties")).unwrap();
        symlink(&external_root, source_root.join("linked.properties")).unwrap();

        let mut cli = cli(&["push", "-r", "repo", "-ft", "PROPERTIES"]);
        cli.source_directory = Some(source_root);

        let sources = discover(&cli).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "messages.properties");
    }

    #[test]
    fn recognizes_java_script_locale_targets_as_localized_siblings() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("messages.properties"), "hello=world").unwrap();
        fs::write(
            directory.path().join("messages_zh_TW_#Hant.properties"),
            "hello=world",
        )
        .unwrap();
        let mut cli = cli(&["push", "-r", "repo", "-ft", "PROPERTIES_JAVA"]);
        cli.source_directory = Some(directory.path().to_owned());

        let sources = discover(&cli).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_path, "messages.properties");
    }

    #[test]
    fn uses_java_locale_target_names_for_properties_java() {
        let source = SourceFile {
            path: PathBuf::new(),
            source_path: "messages.properties".to_owned(),
            file_type: FileType::PropertiesJava,
        };

        assert_eq!(
            source.target_path("zh-Hant-TW", "en").unwrap(),
            PathBuf::from("messages_zh_TW_#Hant.properties")
        );
        assert_eq!(
            source.target_path("sr-Latn-RS", "en").unwrap(),
            PathBuf::from("messages_sr_RS_#Latn.properties")
        );
        assert_eq!(
            source.target_path("en-x-pseudo", "en").unwrap(),
            PathBuf::from("messages_en__#x-pseudo.properties")
        );
    }

    #[test]
    fn rejects_malformed_or_unsupported_properties_java_locales() {
        let source = SourceFile {
            path: PathBuf::new(),
            source_path: "messages.properties".to_owned(),
            file_type: FileType::PropertiesJava,
        };

        for locale in ["zh_Hant_TW", "en--US", "en-US-u-ca-japanese"] {
            let error = source.target_path(locale, "en").unwrap_err();
            assert!(
                error
                    .to_string()
                    .contains("invalid or unsupported PROPERTIES_JAVA locale"),
                "unexpected error for {locale}: {error}"
            );
        }
    }

    #[test]
    fn preserves_java_directory_include_and_exclude_semantics() {
        assert!(should_scan_directory(
            Path::new("/root"),
            Path::new("/root/modules/one/src"),
            &["modules/*/src".to_owned()],
            &[],
        ));
        assert!(!should_scan_directory(
            Path::new("/root"),
            Path::new("/root/modules/one/generated"),
            &["modules/*/src".to_owned()],
            &[],
        ));
        assert!(!should_scan_directory(
            Path::new("/root"),
            Path::new("/root/modules/one/src"),
            &["modules/*/src".to_owned()],
            &["modules/*/src".to_owned()],
        ));
    }

    #[test]
    fn preserves_android_apple_and_gettext_target_paths() {
        let android = SourceFile {
            path: PathBuf::new(),
            source_path: "app/res/values/strings.xml".to_owned(),
            file_type: FileType::Android,
        };
        assert_eq!(
            android.target_path("fr-FR", "en").unwrap(),
            PathBuf::from("app/res/values-fr-rFR/strings.xml")
        );
        assert_eq!(
            android.target_path("zh-Hant-TW", "en").unwrap(),
            PathBuf::from("app/res/values-zh-rTW/strings.xml")
        );

        let apple = SourceFile {
            path: PathBuf::new(),
            source_path: "ios/en.lproj/Localizable.strings".to_owned(),
            file_type: FileType::AppleStrings,
        };
        assert_eq!(
            apple.target_path("fr-FR", "en").unwrap(),
            PathBuf::from("ios/fr-FR.lproj/Localizable.strings")
        );

        let gettext = SourceFile {
            path: PathBuf::new(),
            source_path: "locale/messages.pot".to_owned(),
            file_type: FileType::Gettext,
        };
        assert_eq!(
            gettext.target_path("pt-BR", "en").unwrap(),
            PathBuf::from("locale/pt_BR/messages.po")
        );
    }

    #[test]
    fn rejects_output_locales_that_can_escape_the_target_root() {
        let source = SourceFile {
            path: PathBuf::new(),
            source_path: "ios/en.lproj/Localizable.strings".to_owned(),
            file_type: FileType::AppleStrings,
        };

        for locale in [
            "",
            "/tmp/fr",
            "../fr",
            "fr/../../outside",
            r"..\outside",
            ".",
            "..",
        ] {
            let error = source.target_path(locale, "en").unwrap_err();
            assert!(
                error.to_string().contains("unsafe output locale"),
                "unexpected error for {locale}: {error}"
            );
        }
    }

    #[test]
    fn preserves_utf16_bom_and_encoding() {
        let directory = tempfile::tempdir().unwrap();
        let source = directory.path().join("en.strings");
        fs::write(&source, [0xff, 0xfe, b'h', 0, b'i', 0]).unwrap();
        let text = TextFile::read(&source).unwrap();
        let target = directory.path().join("fr.strings");
        text.write_like(&target, "été").unwrap();
        let output = fs::read(target).unwrap();
        assert!(output.starts_with(&[0xff, 0xfe]));
        assert_eq!(decode_text(&output).unwrap().text, "été");
    }

    #[test]
    fn appends_portable_marker_after_validating_legacy_options() {
        let options = FileType::FormatJsJsonNoBasename
            .filter_options(None)
            .unwrap();
        assert_eq!(options.last().unwrap(), "mojito.converter=portable");
        assert!(options.contains(&"noteKeyPattern=description".to_owned()));
    }

    #[test]
    fn rejects_xliff_without_claiming_unsupported_compatibility() {
        assert!(FileType::parse("XLIFF")
            .unwrap_err()
            .to_string()
            .contains("UNSUPPORTED_PORTABLE_FORMAT"));
    }

    #[test]
    fn reuses_existing_java_pull_datasets_for_source_and_target_paths() {
        let fixture_root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO");
        let datasets = [
            (
                "pullAndroidStrings",
                "ANDROID_STRINGS",
                "res/values/strings.xml",
                "en",
                "fr-FR",
                "res/values-fr-rFR/strings.xml",
            ),
            (
                "pullMacStrings",
                "MAC_STRING",
                "en.lproj/Localizable.strings",
                "en",
                "fr-FR",
                "fr-FR.lproj/Localizable.strings",
            ),
            (
                "pullMacStringsdict",
                "MAC_STRINGSDICT",
                "en.lproj/Localizable.stringsdict",
                "en",
                "fr-FR",
                "fr-FR.lproj/Localizable.stringsdict",
            ),
            (
                "pullProperties",
                "PROPERTIES",
                "demo.properties",
                "en",
                "fr-FR",
                "demo_fr-FR.properties",
            ),
            (
                "pullPropertiesJava",
                "PROPERTIES_JAVA",
                "demo.properties",
                "en",
                "fr-FR",
                "demo_fr_FR.properties",
            ),
            (
                "pullPropertiesNoBasename",
                "PROPERTIES_NOBASENAME",
                "en.properties",
                "en",
                "fr-FR",
                "fr-FR.properties",
            ),
            (
                "pullResw",
                "RESW",
                "en/Resources.resw",
                "en",
                "fr",
                "fr/Resources.resw",
            ),
            (
                "pullResx",
                "RESX",
                "Test.resx",
                "en",
                "fr-FR",
                "Test.fr-FR.resx",
            ),
            (
                "pullPo",
                "PO",
                "LC_MESSAGES/messages.pot",
                "en",
                "fr-CA",
                "fr_CA/LC_MESSAGES/messages.po",
            ),
            (
                "pullXtb",
                "XTB",
                "Resources-en-US.xtb",
                "en-US",
                "fr-FR",
                "Resources-fr-FR.xtb",
            ),
            (
                "pullCsv",
                "CSV",
                "demo.csv",
                "en",
                "fr-FR",
                "demo_fr-FR.csv",
            ),
            (
                "pullCsvAdobeMagento",
                "CSV_ADOBE_MAGENTO",
                "i18n/en_US.csv",
                "en_US",
                "fr-CA",
                "i18n/fr_CA.csv",
            ),
            ("pullJS", "JS", "en.js", "en", "fr", "fr.js"),
            ("pullTS", "TS", "en.ts", "en", "fr", "fr.ts"),
            ("pullYaml", "YAML", "demo.yaml", "en", "fr", "demo_fr.yaml"),
            (
                "pullHtml",
                "HTML_ALPHA",
                "demo.html",
                "en",
                "fr",
                "demo_fr.html",
            ),
            (
                "pullJsonI18NextParser",
                "I18NEXT_PARSER_JSON",
                "locales/en/demo.json",
                "en",
                "fr-FR",
                "locales/fr-FR/demo.json",
            ),
        ];

        for (dataset, file_type, source_path, source_locale, target_locale, target_path) in datasets
        {
            let source_root = fixture_root.join(dataset).join("input/source");
            let expected_target = fixture_root
                .join(dataset)
                .join("expected/target")
                .join(target_path);
            assert!(
                expected_target.is_file(),
                "missing authoritative Java target fixture {}",
                expected_target.display()
            );

            let arguments = [
                "pull".to_owned(),
                "-r".to_owned(),
                "repo".to_owned(),
                "-s".to_owned(),
                source_root.to_string_lossy().into_owned(),
                "-ft".to_owned(),
                file_type.to_owned(),
                "-sl".to_owned(),
                source_locale.to_owned(),
            ];
            let cli = Cli::parse(&arguments).unwrap();
            let sources = discover(&cli).unwrap();
            let source = sources
                .iter()
                .find(|source| source.source_path == source_path)
                .unwrap_or_else(|| panic!("native scan missed {dataset}/{source_path}"));
            assert_eq!(
                source.target_path(target_locale, source_locale).unwrap(),
                PathBuf::from(target_path),
                "native target path does not match the Java fixture for {dataset}"
            );
        }
    }
}
