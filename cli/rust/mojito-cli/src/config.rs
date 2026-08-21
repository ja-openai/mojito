use crate::{Cli, Error, Result};
use std::collections::BTreeMap;
use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};
use url::Url;

#[derive(Clone, Eq, PartialEq)]
pub enum Authentication {
    Stateful {
        username: String,
        password: String,
    },
    Header,
    Bearer(String),
    ClientCredentials {
        authority: String,
        client_id: String,
        client_secret: String,
        scopes: String,
    },
}

impl fmt::Debug for Authentication {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Stateful { username, .. } => formatter
                .debug_struct("Stateful")
                .field("username", username)
                .field("password", &"[REDACTED]")
                .finish(),
            Self::Header => formatter.write_str("Header"),
            Self::Bearer(_) => formatter
                .debug_tuple("Bearer")
                .field(&"[REDACTED]")
                .finish(),
            Self::ClientCredentials {
                authority,
                client_id,
                scopes,
                ..
            } => formatter
                .debug_struct("ClientCredentials")
                .field("authority", authority)
                .field("client_id", client_id)
                .field("client_secret", &"[REDACTED]")
                .field("scopes", scopes)
                .finish(),
        }
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct Config {
    pub base_url: Url,
    pub authentication: Authentication,
    pub headers: BTreeMap<String, String>,
    pub login_path: String,
    pub csrf_path: String,
    pub frontend_config_path: String,
}

impl Config {
    pub fn load(cli: &Cli) -> Result<Self> {
        let environment: BTreeMap<String, String> = std::env::vars().collect();
        let properties = load_configured_properties(cli, &environment)?;
        Self::from_sources(cli, &properties, &environment)
    }

    fn from_sources(
        cli: &Cli,
        properties: &BTreeMap<String, String>,
        environment: &BTreeMap<String, String>,
    ) -> Result<Self> {
        let configured_property = |name: &str| {
            let environment_name = normalize_environment_name(name);
            environment
                .get(&environment_name)
                .or_else(|| {
                    properties.get(name).or_else(|| {
                        let normalized = normalize_property_name(name);
                        properties.iter().find_map(|(candidate, value)| {
                            (normalize_property_name(candidate) == normalized).then_some(value)
                        })
                    })
                })
                .cloned()
        };
        let property = |name: &str, default: &str| {
            configured_property(name).unwrap_or_else(|| default.to_owned())
        };

        let explicit_url = cli.url.as_ref().or_else(|| environment.get("MOJITO_URL"));
        let base_url = if let Some(url) = explicit_url {
            Url::parse(url).map_err(|error| Error::new(format!("invalid Mojito URL: {error}")))?
        } else {
            let scheme = property("l10n.resttemplate.scheme", "http");
            let host = property("l10n.resttemplate.host", "localhost");
            let port = property("l10n.resttemplate.port", "8080");
            let context_path = property("l10n.resttemplate.context-path", "");
            Url::parse(&format!(
                "{scheme}://{host}:{port}/{}",
                context_path.trim_start_matches('/')
            ))
            .map_err(|error| Error::new(format!("invalid Mojito server settings: {error}")))?
        };
        if !matches!(base_url.scheme(), "http" | "https") {
            return Err(Error::new("the Mojito URL must use http or https"));
        }

        let mut headers = BTreeMap::new();
        for (name, value) in properties {
            if let Some(header) = configured_header_name(name) {
                insert_header(&mut headers, header, value.clone())?;
            }
        }
        let header_prefix = "L10N_RESTTEMPLATE_HEADER_HEADERS_";
        for (name, value) in environment {
            if let Some(header) = name.strip_prefix(header_prefix) {
                insert_header(
                    &mut headers,
                    &environment_header_name(header),
                    value.clone(),
                )?;
            }
        }
        for (name, value) in &cli.headers {
            insert_header(&mut headers, name, value.clone())?;
        }

        let configured_auth_mode = configured_property("l10n.resttemplate.authentication-mode");
        let auth_mode = configured_auth_mode
            .clone()
            .unwrap_or_else(|| "STATEFUL".to_owned());
        let configured_stateless_token = (configured_auth_mode.is_none()
            || auth_mode.eq_ignore_ascii_case("STATELESS"))
        .then(|| {
            environment
                .get("L10N_RESTTEMPLATE_STATELESS_TOKEN")
                .cloned()
        })
        .flatten();
        let token = cli
            .token
            .clone()
            .or_else(|| environment.get("MOJITO_TOKEN").cloned())
            .or(configured_stateless_token);
        let authentication = if let Some(token) = token {
            validate_bearer_token(&token)?;
            Authentication::Bearer(token)
        } else if auth_mode.eq_ignore_ascii_case("HEADER")
            || (configured_auth_mode.is_none()
                && cli.username.is_none()
                && cli.password.is_none()
                && !headers.is_empty())
        {
            if headers.is_empty() {
                return Err(Error::new(
                    "HEADER authentication requires existing L10N_RESTTEMPLATE_HEADER_HEADERS_* \
                     variables, header properties, or --header",
                ));
            }
            Authentication::Header
        } else if auth_mode.eq_ignore_ascii_case("STATELESS") {
            let provider = property("l10n.resttemplate.stateless.provider", "MSAL_DEVICE_CODE");
            if !provider.eq_ignore_ascii_case("MSAL_CLIENT_CREDENTIALS") {
                return Err(Error::new(format!(
                    "{provider} requires an existing bearer token supplied through --token or \
                     MOJITO_TOKEN; native interactive MSAL authentication is not implemented"
                )));
            }
            Authentication::ClientCredentials {
                authority: required_property(
                    &property,
                    "l10n.resttemplate.stateless.msal.authority",
                )?,
                client_id: required_property(
                    &property,
                    "l10n.resttemplate.stateless.msal.client-id",
                )?,
                client_secret: required_property(
                    &property,
                    "l10n.resttemplate.stateless.msal.client-secret",
                )?,
                scopes: required_property(&property, "l10n.resttemplate.stateless.msal.scopes")?,
            }
        } else if auth_mode.eq_ignore_ascii_case("STATEFUL") {
            let credential_provider = property(
                "l10n.resttemplate.authentication.credential-provider",
                &property(
                    "l10n.resttemplate.authentication.credentialProvider",
                    "CONFIG",
                ),
            );
            if credential_provider.eq_ignore_ascii_case("CONSOLE") && cli.password.is_none() {
                return Err(Error::new(
                    "interactive CONSOLE credentials are unsupported; provide --username and \
                     --password or use header/bearer authentication",
                ));
            }
            Authentication::Stateful {
                username: cli.username.clone().unwrap_or_else(|| {
                    property("l10n.resttemplate.authentication.username", "admin")
                }),
                password: cli.password.clone().unwrap_or_else(|| {
                    property("l10n.resttemplate.authentication.password", "ChangeMe")
                }),
            }
        } else {
            return Err(Error::new(format!(
                "unsupported authentication mode: {auth_mode}"
            )));
        };

        Ok(Self {
            base_url,
            authentication,
            headers,
            login_path: property(
                "l10n.resttemplate.authentication.formlogin.login-form-path",
                "login",
            ),
            csrf_path: property(
                "l10n.resttemplate.authentication.formlogin.csrf-token-path",
                "api/csrf-token",
            ),
            frontend_config_path: property(
                "l10n.resttemplate.authentication.formlogin.frontend-config-path",
                "api/frontend/config",
            ),
        })
    }
}

fn load_configured_properties(
    cli: &Cli,
    environment: &BTreeMap<String, String>,
) -> Result<BTreeMap<String, String>> {
    let mut paths = vec![(
        PathBuf::from("/usr/local/etc/mojito/cli/application.properties"),
        false,
    )];
    if let Some(home) = environment.get("HOME") {
        paths.push((
            Path::new(home).join(".l10n/config/cli/application.properties"),
            false,
        ));
    }
    paths.push((PathBuf::from("application.properties"), false));
    if let Some(path) = &cli.config_file {
        paths.push((path.clone(), true));
    } else if let Some(path) = environment.get("MOJITO_CONFIG") {
        paths.push((PathBuf::from(path), true));
    }

    let mut properties = BTreeMap::new();
    for (path, required) in paths {
        if path.is_file() {
            load_properties(&path, environment, &mut properties)?;
        } else if required {
            return Err(Error::new(format!(
                "configuration file does not exist: {}",
                path.display()
            )));
        }
    }
    Ok(properties)
}

fn required_property(property: &impl Fn(&str, &str) -> String, name: &str) -> Result<String> {
    let value = property(name, "");
    if value.is_empty() {
        Err(Error::new(format!(
            "missing required configuration: {name}"
        )))
    } else {
        Ok(value)
    }
}

fn normalize_environment_name(property: &str) -> String {
    property
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() {
                character.to_ascii_uppercase()
            } else {
                '_'
            }
        })
        .collect()
}

fn normalize_property_name(property: &str) -> String {
    property
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .map(|character| character.to_ascii_lowercase())
        .collect()
}

fn configured_header_name(property: &str) -> Option<&str> {
    const PREFIX: &str = "l10n.resttemplate.header.headers.";
    property
        .get(..PREFIX.len())
        .filter(|candidate| candidate.eq_ignore_ascii_case(PREFIX))?;
    property
        .get(PREFIX.len()..)
        .filter(|header| !header.is_empty())
}

fn insert_header(headers: &mut BTreeMap<String, String>, name: &str, value: String) -> Result<()> {
    validate_header(name, &value)?;
    if let Some(previous) = headers
        .keys()
        .find(|candidate| candidate.eq_ignore_ascii_case(name))
        .cloned()
    {
        headers.remove(&previous);
    }
    headers.insert(name.to_owned(), value);
    Ok(())
}

fn validate_header(name: &str, value: &str) -> Result<()> {
    if name.is_empty()
        || !name.bytes().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(
                    byte,
                    b'!' | b'#'
                        | b'$'
                        | b'%'
                        | b'&'
                        | b'\''
                        | b'*'
                        | b'+'
                        | b'-'
                        | b'.'
                        | b'^'
                        | b'_'
                        | b'`'
                        | b'|'
                        | b'~'
                )
        })
        || value.is_empty()
        || !is_safe_header_value(value)
    {
        return Err(Error::new(
            "configured HTTP header has an invalid name or value",
        ));
    }
    Ok(())
}

pub(crate) fn validate_bearer_token(token: &str) -> Result<()> {
    if token.is_empty() || !is_safe_header_value(token) {
        return Err(Error::new(
            "configured bearer token is not valid in an HTTP header",
        ));
    }
    Ok(())
}

fn is_safe_header_value(value: &str) -> bool {
    value
        .bytes()
        .all(|byte| byte == b'\t' || byte >= b' ' && byte != 0x7f)
}

fn environment_header_name(name: &str) -> String {
    name.split('_')
        .filter(|part| !part.is_empty())
        .map(|part| {
            let mut characters = part.chars();
            match characters.next() {
                Some(first) => format!(
                    "{}{}",
                    first.to_ascii_uppercase(),
                    characters.as_str().to_ascii_lowercase()
                ),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join("-")
}

fn load_properties(
    path: &Path,
    environment: &BTreeMap<String, String>,
    properties: &mut BTreeMap<String, String>,
) -> Result<()> {
    let content = fs::read_to_string(path).map_err(|error| {
        Error::new(format!(
            "cannot read configuration {}: {error}",
            path.display()
        ))
    })?;
    let mut logical_line = String::new();
    for raw_line in content.lines() {
        let trimmed = raw_line.trim_start();
        if logical_line.is_empty()
            && (trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!'))
        {
            continue;
        }
        logical_line.push_str(trimmed);
        let trailing_backslashes = logical_line
            .chars()
            .rev()
            .take_while(|character| *character == '\\')
            .count();
        if trailing_backslashes % 2 == 1 {
            logical_line.pop();
            continue;
        }
        if let Some((name, value)) = split_property(&logical_line) {
            insert_property(
                properties,
                unescape_property(name.trim())?,
                expand_placeholders(&unescape_property(value.trim())?, environment)?,
            );
        }
        logical_line.clear();
    }
    if !logical_line.is_empty() {
        if let Some((name, value)) = split_property(&logical_line) {
            insert_property(
                properties,
                unescape_property(name.trim())?,
                expand_placeholders(&unescape_property(value.trim())?, environment)?,
            );
        }
    }
    Ok(())
}

fn insert_property(properties: &mut BTreeMap<String, String>, name: String, value: String) {
    let previous = if let Some(header) = configured_header_name(&name) {
        properties
            .keys()
            .find(|candidate| {
                configured_header_name(candidate)
                    .is_some_and(|candidate| candidate.eq_ignore_ascii_case(header))
            })
            .cloned()
    } else {
        let normalized = normalize_property_name(&name);
        properties
            .keys()
            .find(|candidate| normalize_property_name(candidate) == normalized)
            .cloned()
    };
    if let Some(previous) = previous {
        properties.remove(&previous);
    }
    properties.insert(name, value);
}

fn split_property(line: &str) -> Option<(&str, &str)> {
    let mut escaped = false;
    for (index, character) in line.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        if character == '\\' {
            escaped = true;
        } else if matches!(character, '=' | ':') || character.is_whitespace() {
            let value = line[index + character.len_utf8()..]
                .trim_start()
                .strip_prefix('=')
                .or_else(|| {
                    line[index + character.len_utf8()..]
                        .trim_start()
                        .strip_prefix(':')
                })
                .unwrap_or_else(|| line[index + character.len_utf8()..].trim_start());
            return Some((&line[..index], value));
        }
    }
    (!line.is_empty()).then_some((line, ""))
}

fn unescape_property(value: &str) -> Result<String> {
    let mut result = String::new();
    let mut characters = value.chars();
    while let Some(character) = characters.next() {
        if character != '\\' {
            result.push(character);
            continue;
        }
        let Some(escaped) = characters.next() else {
            result.push('\\');
            break;
        };
        match escaped {
            't' => result.push('\t'),
            'n' => result.push('\n'),
            'r' => result.push('\r'),
            'f' => result.push('\u{000c}'),
            'u' => {
                let digits: String = characters.by_ref().take(4).collect();
                let code = u16::from_str_radix(&digits, 16)
                    .map_err(|_| Error::new("invalid Unicode escape in application.properties"))?;
                let character = char::from_u32(u32::from(code)).ok_or_else(|| {
                    Error::new("invalid Unicode escape in application.properties")
                })?;
                result.push(character);
            }
            other => result.push(other),
        }
    }
    Ok(result)
}

fn expand_placeholders(value: &str, environment: &BTreeMap<String, String>) -> Result<String> {
    let mut result = String::new();
    let mut remaining = value;
    while let Some(start) = remaining.find("${") {
        result.push_str(&remaining[..start]);
        let placeholder = &remaining[start + 2..];
        let end = placeholder
            .find('}')
            .ok_or_else(|| Error::new("unterminated environment placeholder in configuration"))?;
        let expression = &placeholder[..end];
        let (name, fallback) = expression
            .split_once(':')
            .map_or((expression, None), |(name, fallback)| {
                (name, Some(fallback))
            });
        let resolved = environment
            .get(name)
            .map(String::as_str)
            .or(fallback)
            .ok_or_else(|| {
                Error::new(format!(
                    "missing configuration environment variable: {name}"
                ))
            })?;
        result.push_str(resolved);
        remaining = &placeholder[end + 1..];
    }
    result.push_str(remaining);
    Ok(result)
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
    fn environment_overrides_legacy_properties() {
        let cli = cli(&["pull", "-r", "repo"]);
        let properties = BTreeMap::from([
            (
                "l10n.resttemplate.host".to_owned(),
                "old.example".to_owned(),
            ),
            ("l10n.resttemplate.port".to_owned(), "443".to_owned()),
            ("l10n.resttemplate.scheme".to_owned(), "https".to_owned()),
            (
                "l10n.resttemplate.authentication-mode".to_owned(),
                "HEADER".to_owned(),
            ),
        ]);
        let environment = BTreeMap::from([
            (
                "L10N_RESTTEMPLATE_HOST".to_owned(),
                "new.example".to_owned(),
            ),
            (
                "L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID".to_owned(),
                "client-id".to_owned(),
            ),
        ]);

        let config = Config::from_sources(&cli, &properties, &environment).unwrap();
        assert_eq!(config.base_url.host_str(), Some("new.example"));
        assert_eq!(config.headers["Cf-Access-Client-Id"], "client-id");
        assert_eq!(config.authentication, Authentication::Header);
    }

    #[test]
    fn explicit_url_and_token_override_existing_settings() {
        let cli = cli(&[
            "--url",
            "https://example.com/mojito",
            "--token",
            "token",
            "pull",
            "-r",
            "repo",
        ]);
        let config = Config::from_sources(&cli, &BTreeMap::new(), &BTreeMap::new()).unwrap();
        assert_eq!(config.base_url.path(), "/mojito");
        assert_eq!(
            config.authentication,
            Authentication::Bearer("token".to_owned())
        );
    }

    #[test]
    fn parses_java_properties_continuations_and_environment_placeholders() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("application.properties");
        fs::write(
            &path,
            "# comment\nl10n.resttemplate.host=${SERVER}\nexample=one\\\n two\n",
        )
        .unwrap();
        let mut properties = BTreeMap::new();
        let environment = BTreeMap::from([("SERVER".to_owned(), "mojito.example".to_owned())]);
        load_properties(&path, &environment, &mut properties).unwrap();
        assert_eq!(properties["l10n.resttemplate.host"], "mojito.example");
        assert_eq!(properties["example"], "onetwo");
    }

    #[test]
    fn rejects_interactive_msal_without_a_token() {
        let cli = cli(&["pull", "-r", "repo"]);
        let properties = BTreeMap::from([(
            "l10n.resttemplate.authentication-mode".to_owned(),
            "STATELESS".to_owned(),
        )]);
        let error = Config::from_sources(&cli, &properties, &BTreeMap::new())
            .err()
            .expect("interactive MSAL should be rejected");
        assert!(error.to_string().contains("MOJITO_TOKEN"));
    }

    #[test]
    fn rejects_a_missing_mojito_config_file() {
        let directory = tempfile::tempdir().unwrap();
        let missing = directory.path().join("missing.properties");
        let cli = cli(&["pull", "-r", "repo"]);
        let environment = BTreeMap::from([
            (
                "HOME".to_owned(),
                directory.path().to_string_lossy().into_owned(),
            ),
            (
                "MOJITO_CONFIG".to_owned(),
                missing.to_string_lossy().into_owned(),
            ),
        ]);

        let error = load_configured_properties(&cli, &environment).unwrap_err();
        assert!(error.to_string().contains(&missing.display().to_string()));
    }

    #[test]
    fn ignores_unrelated_ambient_azure_tokens() {
        let cli = cli(&["pull", "-r", "repo"]);
        let properties = BTreeMap::from([(
            "l10n.resttemplate.authentication-mode".to_owned(),
            "STATEFUL".to_owned(),
        )]);
        let environment = BTreeMap::from([(
            "AZURE_ACCESS_TOKEN".to_owned(),
            "unrelated-token".to_owned(),
        )]);

        let config = Config::from_sources(&cli, &properties, &environment).unwrap();
        assert!(matches!(
            config.authentication,
            Authentication::Stateful { .. }
        ));
    }

    #[test]
    fn explicit_stateful_mode_ignores_stale_stateless_tokens() {
        let cli = cli(&["pull", "-r", "repo"]);
        let properties = BTreeMap::from([(
            "l10n.resttemplate.authentication-mode".to_owned(),
            "STATEFUL".to_owned(),
        )]);
        let environment = BTreeMap::from([(
            "L10N_RESTTEMPLATE_STATELESS_TOKEN".to_owned(),
            "stale-token".to_owned(),
        )]);

        let config = Config::from_sources(&cli, &properties, &environment).unwrap();
        assert!(matches!(
            config.authentication,
            Authentication::Stateful { .. }
        ));
    }

    #[test]
    fn explicit_stateful_authentication_is_not_overridden_by_headers() {
        let cli = cli(&["--header", "X-Trace: fixture", "pull", "-r", "repo"]);
        let properties = BTreeMap::from([(
            "l10n.resttemplate.authentication-mode".to_owned(),
            "STATEFUL".to_owned(),
        )]);

        let config = Config::from_sources(&cli, &properties, &BTreeMap::new()).unwrap();
        assert!(matches!(
            config.authentication,
            Authentication::Stateful { .. }
        ));
        assert_eq!(config.headers["X-Trace"], "fixture");
    }

    #[test]
    fn explicit_form_credentials_are_not_overridden_by_headers() {
        let cli = cli(&[
            "--username",
            "fixture-user",
            "--password",
            "fixture-password",
            "--header",
            "X-Trace: fixture",
            "pull",
            "-r",
            "repo",
        ]);

        let config = Config::from_sources(&cli, &BTreeMap::new(), &BTreeMap::new()).unwrap();
        assert_eq!(
            config.authentication,
            Authentication::Stateful {
                username: "fixture-user".to_owned(),
                password: "fixture-password".to_owned(),
            }
        );
        assert_eq!(config.headers["X-Trace"], "fixture");
    }

    #[test]
    fn explicit_client_credentials_authentication_is_not_overridden_by_headers() {
        let cli = cli(&["pull", "-r", "repo"]);
        let properties = BTreeMap::from([
            (
                "l10n.resttemplate.authentication-mode".to_owned(),
                "STATELESS".to_owned(),
            ),
            (
                "l10n.resttemplate.stateless.provider".to_owned(),
                "MSAL_CLIENT_CREDENTIALS".to_owned(),
            ),
            (
                "l10n.resttemplate.stateless.msal.authority".to_owned(),
                "https://login.example/tenant".to_owned(),
            ),
            (
                "l10n.resttemplate.stateless.msal.client-id".to_owned(),
                "client-id".to_owned(),
            ),
            (
                "l10n.resttemplate.stateless.msal.client-secret".to_owned(),
                "client-secret".to_owned(),
            ),
            (
                "l10n.resttemplate.stateless.msal.scopes".to_owned(),
                "api://mojito/.default".to_owned(),
            ),
            (
                "l10n.resttemplate.header.headers.X-Trace".to_owned(),
                "fixture".to_owned(),
            ),
        ]);

        let config = Config::from_sources(&cli, &properties, &BTreeMap::new()).unwrap();
        assert!(matches!(
            config.authentication,
            Authentication::ClientCredentials { .. }
        ));
        assert_eq!(config.headers["X-Trace"], "fixture");
    }

    #[test]
    fn preserves_spring_relaxed_camel_case_property_names() {
        let cli = cli(&["pull", "-r", "repo"]);
        let properties = BTreeMap::from([
            (
                "l10n.resttemplate.contextPath".to_owned(),
                "/mojito".to_owned(),
            ),
            (
                "l10n.resttemplate.authentication.formlogin.loginFormPath".to_owned(),
                "custom-login".to_owned(),
            ),
        ]);
        let config = Config::from_sources(&cli, &properties, &BTreeMap::new()).unwrap();
        assert_eq!(config.base_url.path(), "/mojito");
        assert_eq!(config.login_path, "custom-login");
    }

    #[test]
    fn later_relaxed_property_names_override_earlier_files() {
        let directory = tempfile::tempdir().unwrap();
        let first = directory.path().join("first.properties");
        let second = directory.path().join("second.properties");
        fs::write(&first, "l10n.resttemplate.authentication-mode=STATEFUL\n").unwrap();
        fs::write(
            &second,
            "l10n.resttemplate.authenticationMode=HEADER\n\
             l10n.resttemplate.header.headers.X-Trace=fixture\n",
        )
        .unwrap();
        let mut properties = BTreeMap::new();
        load_properties(&first, &BTreeMap::new(), &mut properties).unwrap();
        load_properties(&second, &BTreeMap::new(), &mut properties).unwrap();

        let config =
            Config::from_sources(&cli(&["pull", "-r", "repo"]), &properties, &BTreeMap::new())
                .unwrap();
        assert_eq!(config.authentication, Authentication::Header);
    }

    #[test]
    fn cli_config_supersedes_the_environment_config_selector() {
        let directory = tempfile::tempdir().unwrap();
        let selected = directory.path().join("selected.properties");
        let missing = directory.path().join("stale.properties");
        fs::write(&selected, "l10n.resttemplate.host=selected.example\n").unwrap();
        let cli = cli(&["--config", selected.to_str().unwrap(), "pull", "-r", "repo"]);
        let environment = BTreeMap::from([
            (
                "HOME".to_owned(),
                directory.path().to_string_lossy().into_owned(),
            ),
            (
                "MOJITO_CONFIG".to_owned(),
                missing.to_string_lossy().into_owned(),
            ),
        ]);

        let properties = load_configured_properties(&cli, &environment).unwrap();
        assert_eq!(properties["l10n.resttemplate.host"], "selected.example");
    }

    #[test]
    fn header_overrides_are_case_insensitive() {
        let cli = cli(&[
            "--header",
            "cf-access-client-secret: cli-secret",
            "pull",
            "-r",
            "repo",
        ]);
        let properties = BTreeMap::from([
            (
                "l10n.resttemplate.authentication-mode".to_owned(),
                "HEADER".to_owned(),
            ),
            (
                "l10n.resttemplate.header.headers.CF-Access-Client-Secret".to_owned(),
                "property-secret".to_owned(),
            ),
        ]);
        let environment = BTreeMap::from([(
            "L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET".to_owned(),
            "environment-secret".to_owned(),
        )]);

        let config = Config::from_sources(&cli, &properties, &environment).unwrap();
        assert_eq!(config.headers.len(), 1);
        assert_eq!(config.headers["cf-access-client-secret"], "cli-secret");
    }

    #[test]
    fn rejects_sensitive_header_values_without_echoing_them() {
        for arguments in [
            vec!["--token", "TOPSECRET\n", "pull", "-r", "repo"],
            vec!["--header", "X-Secret: TOP\nSECRET", "pull", "-r", "repo"],
        ] {
            let cli = cli(&arguments);
            let error = Config::from_sources(&cli, &BTreeMap::new(), &BTreeMap::new())
                .err()
                .expect("invalid header data should be rejected");
            assert!(!error.to_string().contains("TOPSECRET"));
            assert!(!error.to_string().contains("TOP\nSECRET"));
        }
    }

    #[test]
    fn authentication_debug_output_redacts_credentials() {
        let authentications = [
            Authentication::Stateful {
                username: "fixture-user".to_owned(),
                password: "fixture-password".to_owned(),
            },
            Authentication::Bearer("fixture-token".to_owned()),
            Authentication::ClientCredentials {
                authority: "https://login.example/tenant".to_owned(),
                client_id: "fixture-client".to_owned(),
                client_secret: "fixture-secret".to_owned(),
                scopes: "api://fixture/.default".to_owned(),
            },
            Authentication::Header,
        ];

        let output = format!("{authentications:?}");
        for secret in ["fixture-password", "fixture-token", "fixture-secret"] {
            assert!(!output.contains(secret));
        }
        assert!(output.contains("[REDACTED]"));
    }
}
