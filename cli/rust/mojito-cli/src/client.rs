use crate::config::{validate_bearer_token, Authentication, Config};
use crate::{Error, Result};
use regex::Regex;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::Read;
use std::thread;
use std::time::Duration;
use url::form_urlencoded;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Repository {
    pub id: u64,
    #[serde(default)]
    pub repository_locales: Vec<RepositoryLocale>,
    #[serde(default)]
    pub repository_statistic: Option<RepositoryStatistic>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RepositoryLocale {
    pub locale: Locale,
    #[serde(default)]
    pub parent_locale: Option<Box<RepositoryLocale>>,
    #[serde(default = "default_true")]
    pub to_be_fully_translated: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Locale {
    pub id: u64,
    pub bcp47_tag: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RepositoryStatistic {
    #[serde(default)]
    pub repository_locale_statistics: Vec<RepositoryLocaleStatistic>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RepositoryLocaleStatistic {
    pub locale: Locale,
    #[serde(default)]
    pub for_translation_count: u64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Asset {
    pub id: u64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceAssetResponse {
    pub added_asset_id: u64,
    pub pollable_task: PollableTask,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalizedAsset {
    pub content: String,
    pub bcp47_tag: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResponse {
    pub pollable_task: PollableTask,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PollableTask {
    pub id: u64,
    #[serde(default)]
    pub all_finished: bool,
    #[serde(default)]
    pub error_message: Option<TaskError>,
    #[serde(default)]
    pub sub_tasks: Vec<PollableTask>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct TaskError {
    pub message: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Branch {
    pub id: u64,
    #[serde(default)]
    pub name: Option<String>,
}

pub struct Client {
    config: Config,
    agent: ureq::Agent,
    cookies: BTreeMap<String, String>,
    csrf_token: Option<String>,
    bearer_token: Option<String>,
    authenticated: bool,
}

impl Client {
    pub fn new(config: Config) -> Result<Self> {
        let bearer_token = match &config.authentication {
            Authentication::Bearer(token) => Some(token.clone()),
            _ => None,
        };
        Ok(Self {
            config,
            agent: ureq::AgentBuilder::new().redirects(0).build(),
            cookies: BTreeMap::new(),
            csrf_token: None,
            bearer_token,
            authenticated: false,
        })
    }

    pub fn repository(&mut self, name: &str) -> Result<Repository> {
        let repositories: Vec<Repository> = self.get("/api/repositories", &[("name", name)])?;
        if repositories.len() != 1 {
            return Err(Error::new(format!("repository [{name}] is not found")));
        }
        repositories
            .into_iter()
            .next()
            .ok_or_else(|| Error::new(format!("repository [{name}] is not found")))
    }

    pub fn asset(&mut self, repository_id: u64, path: &str) -> Result<Asset> {
        let assets: Vec<Asset> = self.get(
            "/api/assets",
            &[("path", path), ("repositoryId", &repository_id.to_string())],
        )?;
        assets.into_iter().next().ok_or_else(|| {
            Error::new(format!(
                "asset with path [{path}] was not found in repository [{repository_id}]"
            ))
        })
    }

    pub fn push_asset(&mut self, body: &Value) -> Result<SourceAssetResponse> {
        self.post("/api/assets", body)
    }

    pub fn localized_asset(
        &mut self,
        asset_id: u64,
        locale_id: u64,
        body: &Value,
    ) -> Result<LocalizedAsset> {
        self.post(
            &format!("/api/assets/{asset_id}/localized/{locale_id}"),
            body,
        )
    }

    pub fn localized_asset_async(&mut self, asset_id: u64, body: &Value) -> Result<PollableTask> {
        self.post(&format!("/api/assets/{asset_id}/localized"), body)
    }

    pub fn localized_assets_parallel(
        &mut self,
        asset_id: u64,
        body: &Value,
    ) -> Result<PollableTask> {
        self.post(&format!("/api/assets/{asset_id}/localized/parallel"), body)
    }

    pub fn import_asset(
        &mut self,
        asset_id: u64,
        locale_id: u64,
        body: &Value,
    ) -> Result<ImportResponse> {
        self.post(
            &format!("/api/assets/{asset_id}/localized/{locale_id}/import"),
            body,
        )
    }

    pub fn wait_for_task(&mut self, id: u64) -> Result<PollableTask> {
        let mut delay = Duration::from_millis(25);
        loop {
            let task: PollableTask = self.get(&format!("/api/pollableTasks/{id}"), &[])?;
            if let Some(message) = task_error(&task) {
                return Err(Error::new(format!("task {id} failed: {message}")));
            }
            if task.all_finished {
                return Ok(task);
            }
            thread::sleep(delay);
            delay = (delay + Duration::from_millis(25)).min(Duration::from_millis(500));
        }
    }

    pub fn task_output(&mut self, id: u64) -> Result<String> {
        self.get_text(&format!("/api/pollableTasks/{id}/output"), &[])
    }

    pub fn branch(&mut self, repository_id: u64, name: Option<&str>) -> Result<Option<Branch>> {
        let mut query = Vec::new();
        if let Some(name) = name {
            query.push(("name", name));
        }
        let branches: Vec<Branch> = self.get(
            &format!("/api/repositories/{repository_id}/branches"),
            &query,
        )?;
        Ok(branches
            .into_iter()
            .find(|branch| branch.name.as_deref() == name))
    }

    pub fn asset_ids(&mut self, repository_id: u64, branch_id: u64) -> Result<Vec<u64>> {
        self.get(
            "/api/assets/ids",
            &[
                ("repositoryId", &repository_id.to_string()),
                ("deleted", "false"),
                ("virtual", "false"),
                ("branchId", &branch_id.to_string()),
            ],
        )
    }

    pub fn delete_assets(&mut self, branch_id: u64, ids: &[u64]) -> Result<PollableTask> {
        self.request_json(
            "DELETE",
            "/api/assets",
            &[("branchId", &branch_id.to_string())],
            Some(&json!(ids)),
        )
    }

    pub fn associate_push_run(
        &mut self,
        repository_id: u64,
        commit_hash: &str,
        push_run_name: &str,
    ) -> Result<()> {
        let _: Value = self.post(
            "/api/commits/pushRun",
            &json!({
                "commitName": commit_hash,
                "repositoryId": repository_id,
                "pushRunName": push_run_name,
            }),
        )?;
        Ok(())
    }

    fn get<T: DeserializeOwned>(&mut self, path: &str, query: &[(&str, &str)]) -> Result<T> {
        self.request_json("GET", path, query, None)
    }

    fn post<T: DeserializeOwned>(&mut self, path: &str, body: &Value) -> Result<T> {
        self.request_json("POST", path, &[], Some(body))
    }

    fn request_json<T: DeserializeOwned>(
        &mut self,
        method: &str,
        path: &str,
        query: &[(&str, &str)],
        body: Option<&Value>,
    ) -> Result<T> {
        let text = self.request_text(method, path, query, body)?;
        let response = if text.trim().is_empty() {
            "null"
        } else {
            &text
        };
        serde_json::from_str(response)
            .map_err(|error| Error::new(format!("invalid response from {method} {path}: {error}")))
    }

    fn get_text(&mut self, path: &str, query: &[(&str, &str)]) -> Result<String> {
        self.request_text("GET", path, query, None)
    }

    fn request_text(
        &mut self,
        method: &str,
        path: &str,
        query: &[(&str, &str)],
        body: Option<&Value>,
    ) -> Result<String> {
        self.ensure_authenticated()?;
        let url = self.url(path, query)?;
        let mut response = self.send_api_request(method, &url, body);
        if is_authentication_failure(&response) && self.reset_refreshable_authentication() {
            self.ensure_authenticated()?;
            response = self.send_api_request(method, &url, body);
        }
        let response = self.response(response.map_err(|error| *error), method, path)?;
        self.capture_cookies(&response);
        read_response(response)
    }

    fn send_api_request(
        &self,
        method: &str,
        url: &url::Url,
        body: Option<&Value>,
    ) -> std::result::Result<ureq::Response, Box<ureq::Error>> {
        let mut request = self
            .mojito_request(method, url)
            .set("Accept", "application/json");
        if let Some(token) = &self.bearer_token {
            request = request.set("Authorization", &format!("Bearer {token}"));
        }
        if let Some(csrf_token) = &self.csrf_token {
            request = request.set("X-CSRF-TOKEN", csrf_token);
        }
        if !self.cookies.is_empty() {
            request = request.set("Cookie", &self.cookie_header());
        }
        if let Some(body) = body {
            request.send_json(body).map_err(Box::new)
        } else {
            request.call().map_err(Box::new)
        }
    }

    fn mojito_request(&self, method: &str, url: &url::Url) -> ureq::Request {
        let mut request = self.agent.request(method, url.as_str());
        for (name, value) in &self.config.headers {
            request = request.set(name, value);
        }
        request
    }

    fn reset_refreshable_authentication(&mut self) -> bool {
        if !matches!(
            &self.config.authentication,
            Authentication::Stateful { .. } | Authentication::ClientCredentials { .. }
        ) {
            return false;
        }
        self.cookies.clear();
        self.csrf_token = None;
        self.bearer_token = None;
        self.authenticated = false;
        true
    }

    fn ensure_authenticated(&mut self) -> Result<()> {
        if self.authenticated {
            return Ok(());
        }
        match self.config.authentication.clone() {
            Authentication::Header | Authentication::Bearer(_) => {}
            Authentication::ClientCredentials {
                authority,
                client_id,
                client_secret,
                scopes,
            } => self.acquire_client_credentials_token(
                &authority,
                &client_id,
                &client_secret,
                &scopes,
            )?,
            Authentication::Stateful { username, password } => {
                self.authenticate_form(&username, &password)?;
            }
        }
        self.authenticated = true;
        Ok(())
    }

    fn acquire_client_credentials_token(
        &mut self,
        authority: &str,
        client_id: &str,
        client_secret: &str,
        scopes: &str,
    ) -> Result<()> {
        let token_url = format!("{}/oauth2/v2.0/token", authority.trim_end_matches('/'));
        let body = form_urlencoded::Serializer::new(String::new())
            .append_pair("grant_type", "client_credentials")
            .append_pair("client_id", client_id)
            .append_pair("client_secret", client_secret)
            .append_pair("scope", scopes)
            .finish();
        let request = self
            .agent
            .post(&token_url)
            .set("Content-Type", "application/x-www-form-urlencoded")
            .send_string(&body);
        let response = self.response(request, "POST", "OAuth token endpoint")?;
        let token: Value = serde_json::from_str(&read_response(response)?)?;
        let token = token["access_token"]
            .as_str()
            .ok_or_else(|| Error::new("OAuth token response did not include access_token"))?;
        validate_bearer_token(token)?;
        self.bearer_token = Some(token.to_owned());
        Ok(())
    }

    fn authenticate_form(&mut self, username: &str, password: &str) -> Result<()> {
        let login_path = self.config.login_path.clone();
        let login_url = self.url(&login_path, &[])?;
        let response = self.response(
            self.mojito_request("GET", &login_url).call(),
            "GET",
            &login_path,
        )?;
        self.capture_cookies(&response);
        let login_html = read_response(response)?;
        let initial_csrf = self.initial_csrf_token(&login_html)?;

        let body = form_urlencoded::Serializer::new(String::new())
            .append_pair("username", username)
            .append_pair("password", password)
            .finish();
        let mut request = self
            .mojito_request("POST", &login_url)
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("X-CSRF-TOKEN", &initial_csrf);
        if !self.cookies.is_empty() {
            request = request.set("Cookie", &self.cookie_header());
        }
        let response = self.response(request.send_string(&body), "POST", &login_path)?;
        validate_form_login_redirect(response.status(), response.header("Location"), &login_url)?;
        self.capture_cookies(&response);
        let csrf_path = self.config.csrf_path.clone();
        let url = self.url(&csrf_path, &[])?;
        let mut request = self.mojito_request("GET", &url);
        if !self.cookies.is_empty() {
            request = request.set("Cookie", &self.cookie_header());
        }
        let response = self.response(request.call(), "GET", &csrf_path)?;
        self.capture_cookies(&response);
        let token = read_response(response)?;
        if token.trim().is_empty() {
            return Err(Error::new(
                "Mojito CSRF token endpoint returned an empty token",
            ));
        }
        self.csrf_token = Some(token.trim().trim_matches('"').to_owned());
        Ok(())
    }

    fn initial_csrf_token(&mut self, login_html: &str) -> Result<String> {
        let pattern = Regex::new(r"CSRF_TOKEN\s*=\s*'([^']+)'\s*;")
            .map_err(|error| Error::new(error.to_string()))?;
        if let Some(captures) = pattern.captures(login_html) {
            return Ok(captures[1].to_owned());
        }
        let path = self.config.frontend_config_path.clone();
        let url = self.url(&path, &[])?;
        let mut request = self.mojito_request("GET", &url);
        if !self.cookies.is_empty() {
            request = request.set("Cookie", &self.cookie_header());
        }
        let response = self.response(request.call(), "GET", &path)?;
        self.capture_cookies(&response);
        let config: Value = serde_json::from_str(&read_response(response)?)?;
        config["csrfToken"]
            .as_str()
            .filter(|value| !value.is_empty())
            .map(str::to_owned)
            .ok_or_else(|| Error::new("could not find a CSRF token in the Mojito frontend config"))
    }

    fn capture_cookies(&mut self, response: &ureq::Response) {
        for header in response.all("set-cookie") {
            if let Some(cookie) = header.split(';').next() {
                if let Some((name, value)) = cookie.split_once('=') {
                    self.cookies.insert(name.to_owned(), value.to_owned());
                }
            }
        }
    }

    fn cookie_header(&self) -> String {
        self.cookies
            .iter()
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join("; ")
    }

    fn url(&self, path: &str, query: &[(&str, &str)]) -> Result<url::Url> {
        let mut base = self.config.base_url.clone();
        let prefix = base.path().trim_end_matches('/').to_owned();
        base.set_path(&format!("{prefix}/{}", path.trim_start_matches('/')));
        if !query.is_empty() {
            base.query_pairs_mut().extend_pairs(query.iter().copied());
        }
        Ok(base)
    }

    fn response(
        &self,
        result: std::result::Result<ureq::Response, ureq::Error>,
        method: &str,
        path: &str,
    ) -> Result<ureq::Response> {
        match result {
            Ok(response) => Ok(response),
            Err(ureq::Error::Status(status, response)) => {
                let body = read_response(response).unwrap_or_default();
                Err(Error::new(format!(
                    "{method} {path} failed with HTTP {status}: {}",
                    body.trim()
                )))
            }
            Err(error) if error.kind() == ureq::ErrorKind::BadHeader => Err(Error::new(format!(
                "{method} {path} failed because an HTTP header was invalid"
            ))),
            Err(error) => Err(Error::new(format!("{method} {path} failed: {error}"))),
        }
    }
}

fn is_authentication_failure(
    response: &std::result::Result<ureq::Response, Box<ureq::Error>>,
) -> bool {
    matches!(
        response,
        Err(error) if matches!(error.as_ref(), ureq::Error::Status(401 | 403, _))
    )
}

fn validate_form_login_redirect(
    status: u16,
    location: Option<&str>,
    login_url: &url::Url,
) -> Result<()> {
    if status != 302 {
        return Err(Error::new(
            "Mojito form authentication did not return a successful redirect",
        ));
    }
    let location = location.ok_or_else(|| {
        Error::new("Mojito form authentication redirect did not include a Location header")
    })?;
    let redirect_url = login_url
        .join(location)
        .map_err(|error| Error::new(format!("invalid Mojito form redirect: {error}")))?;
    let redirected_to_login =
        redirect_url.path().trim_end_matches('/') == login_url.path().trim_end_matches('/');
    let has_error = redirect_url
        .query_pairs()
        .any(|(name, _)| name.eq_ignore_ascii_case("error"));
    if redirected_to_login || has_error {
        return Err(Error::new(format!(
            "Mojito form authentication was rejected and redirected to {location}"
        )));
    }
    Ok(())
}

fn read_response(response: ureq::Response) -> Result<String> {
    let mut body = String::new();
    response
        .into_reader()
        .read_to_string(&mut body)
        .map_err(|error| Error::new(format!("cannot read HTTP response: {error}")))?;
    Ok(body)
}

fn task_error(task: &PollableTask) -> Option<&str> {
    task.error_message
        .as_ref()
        .map(|error| error.message.as_str())
        .or_else(|| task.sub_tasks.iter().find_map(task_error))
}

fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn traverses_nested_pollable_task_errors() {
        let task: PollableTask = serde_json::from_value(json!({
            "id": 1,
            "allFinished": false,
            "subTasks": [{
                "id": 2,
                "allFinished": true,
                "errorMessage": {"message": "translation failed"}
            }]
        }))
        .unwrap();
        assert_eq!(task_error(&task), Some("translation failed"));
    }

    #[test]
    fn preserves_context_path_when_building_api_urls() {
        let config = Config {
            base_url: url::Url::parse("https://example.com/mojito").unwrap(),
            authentication: Authentication::Header,
            headers: BTreeMap::new(),
            login_path: "login".to_owned(),
            csrf_path: "api/csrf-token".to_owned(),
            frontend_config_path: "api/frontend/config".to_owned(),
        };
        let client = Client::new(config).unwrap();
        let url = client
            .url("/api/assets", &[("path", "lang/en.json")])
            .unwrap();
        assert_eq!(
            url.as_str(),
            "https://example.com/mojito/api/assets?path=lang%2Fen.json"
        );
    }

    #[test]
    fn rejects_failed_form_login_redirects() {
        let login_url = url::Url::parse("https://example.com/mojito/login").unwrap();

        assert!(validate_form_login_redirect(302, Some("/login?error"), &login_url).is_err());
        assert!(validate_form_login_redirect(302, Some("?error=true"), &login_url).is_err());
        assert!(validate_form_login_redirect(302, Some("/mojito/login"), &login_url).is_err());
        assert!(validate_form_login_redirect(302, None, &login_url).is_err());
        assert!(validate_form_login_redirect(200, Some("/mojito/"), &login_url).is_err());
        assert!(validate_form_login_redirect(302, Some("/mojito/"), &login_url).is_ok());
    }

    #[test]
    fn recognizes_only_unauthorized_and_forbidden_as_refresh_signals() {
        for status in [401, 403] {
            let response = ureq::Response::new(status, "authentication failed", "").unwrap();
            assert!(is_authentication_failure(&Err(Box::new(
                ureq::Error::Status(status, response)
            ))));
        }

        let response = ureq::Response::new(429, "too many requests", "").unwrap();
        assert!(!is_authentication_failure(&Err(Box::new(
            ureq::Error::Status(429, response)
        ))));
    }

    #[test]
    fn clears_only_refreshable_authentication_state() {
        let mut stateful = client_with_authentication(Authentication::Stateful {
            username: "user".to_owned(),
            password: "password".to_owned(),
        });
        stateful.authenticated = true;
        stateful
            .cookies
            .insert("SESSION".to_owned(), "old".to_owned());
        stateful.csrf_token = Some("old".to_owned());
        assert!(stateful.reset_refreshable_authentication());
        assert!(!stateful.authenticated);
        assert!(stateful.cookies.is_empty());
        assert!(stateful.csrf_token.is_none());

        let mut credentials = client_with_authentication(Authentication::ClientCredentials {
            authority: "https://login.example/tenant".to_owned(),
            client_id: "client".to_owned(),
            client_secret: "secret".to_owned(),
            scopes: "scope/.default".to_owned(),
        });
        credentials.authenticated = true;
        credentials.bearer_token = Some("expired".to_owned());
        assert!(credentials.reset_refreshable_authentication());
        assert!(!credentials.authenticated);
        assert!(credentials.bearer_token.is_none());

        let mut bearer = client_with_authentication(Authentication::Bearer("static".to_owned()));
        bearer.authenticated = true;
        assert!(!bearer.reset_refreshable_authentication());
        assert!(bearer.authenticated);
        assert_eq!(bearer.bearer_token.as_deref(), Some("static"));

        let mut header = client_with_authentication(Authentication::Header);
        header.authenticated = true;
        assert!(!header.reset_refreshable_authentication());
        assert!(header.authenticated);
    }

    #[test]
    fn redacts_invalid_header_transport_errors() {
        let client = client_with_authentication(Authentication::Header);
        let secret = "TOPSECRET\n";
        let result = ureq::get("http://127.0.0.1:1/")
            .set("Authorization", &format!("Bearer {secret}"))
            .call();
        let error = client
            .response(result, "GET", "/fixture")
            .expect_err("the malformed header should be rejected");

        assert!(error.to_string().contains("HTTP header was invalid"));
        assert!(!error.to_string().contains("TOPSECRET"));
    }

    fn client_with_authentication(authentication: Authentication) -> Client {
        Client::new(Config {
            base_url: url::Url::parse("https://example.com/mojito").unwrap(),
            authentication,
            headers: BTreeMap::new(),
            login_path: "login".to_owned(),
            csrf_path: "api/csrf-token".to_owned(),
            frontend_config_path: "api/frontend/config".to_owned(),
        })
        .unwrap()
    }
}
