use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

struct Step {
    method: &'static str,
    path: &'static str,
    body: Value,
}

#[derive(Clone, Debug)]
struct Request {
    path: String,
    headers: String,
    body: String,
}

fn mock(steps: Vec<Step>) -> (String, Arc<Mutex<Vec<Request>>>, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let requests = Arc::new(Mutex::new(Vec::new()));
    let recorded = Arc::clone(&requests);
    let handle = thread::spawn(move || {
        for step in steps {
            let started = Instant::now();
            let (mut stream, _) = loop {
                match listener.accept() {
                    Ok(connection) => break connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(
                            started.elapsed() < Duration::from_secs(10),
                            "timed out waiting for {} {}",
                            step.method,
                            step.path
                        );
                        thread::sleep(Duration::from_millis(10));
                    }
                    Err(error) => panic!("mock server failed: {error}"),
                }
            };
            stream.set_nonblocking(false).unwrap();
            let mut bytes = Vec::new();
            let mut buffer = [0_u8; 1024];
            let header_end = loop {
                let read = stream.read(&mut buffer).unwrap();
                assert!(read > 0, "request ended before headers");
                bytes.extend_from_slice(&buffer[..read]);
                if let Some(index) = bytes.windows(4).position(|value| value == b"\r\n\r\n") {
                    break index + 4;
                }
            };
            let headers = String::from_utf8(bytes[..header_end].to_vec()).unwrap();
            let content_length = headers
                .lines()
                .find_map(|header| {
                    let (name, value) = header.split_once(':')?;
                    name.eq_ignore_ascii_case("content-length")
                        .then(|| value.trim().parse::<usize>().unwrap())
                })
                .unwrap_or(0);
            while bytes.len() < header_end + content_length {
                let read = stream.read(&mut buffer).unwrap();
                assert!(read > 0, "request ended before its full body");
                bytes.extend_from_slice(&buffer[..read]);
            }
            let first_line = headers.lines().next().unwrap();
            let mut parts = first_line.split_whitespace();
            let method = parts.next().unwrap().to_owned();
            let path = parts.next().unwrap().to_owned();
            assert_eq!(method, step.method);
            assert!(
                path.starts_with(step.path),
                "expected path {}, got {path}",
                step.path
            );
            recorded.lock().unwrap().push(Request {
                path,
                headers,
                body: String::from_utf8(bytes[header_end..header_end + content_length].to_vec())
                    .unwrap(),
            });
            let status = step
                .body
                .get("__mockStatus")
                .and_then(Value::as_u64)
                .unwrap_or(200);
            let response = match step.body.get("__mockBody") {
                Some(Value::String(body)) => body.clone(),
                Some(body) => serde_json::to_string(body).unwrap(),
                None => serde_json::to_string(&step.body).unwrap(),
            };
            let extra_headers = step
                .body
                .get("__mockHeaders")
                .and_then(Value::as_object)
                .map(|headers| {
                    headers
                        .iter()
                        .map(|(name, value)| format!("{name}: {}\r\n", value.as_str().unwrap()))
                        .collect::<String>()
                })
                .unwrap_or_default();
            let status_text = if status == 302 { "Found" } else { "OK" };
            write!(
                stream,
                "HTTP/1.1 {status} {status_text}\r\nContent-Type: application/json\r\n{extra_headers}Content-Length: {}\r\nConnection: close\r\n\r\n{}",
                response.len(),
                response
            )
            .unwrap();
        }
    });
    (url, requests, handle)
}

fn repository() -> Value {
    json!([{
        "id": 1,
        "name": "example",
        "repositoryLocales": [
            {"id": 10, "locale": {"id": 10, "bcp47Tag": "en"}},
            {
                "id": 20,
                "locale": {"id": 20, "bcp47Tag": "fr"},
                "parentLocale": {"id": 10, "locale": {"id": 10, "bcp47Tag": "en"}}
            }
        ]
    }])
}

fn run(arguments: &[&str], url: &str, home: &std::path::Path) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_mojito"))
        .env_clear()
        .env("HOME", home)
        .arg("--url")
        .arg(url)
        .arg("--header")
        .arg("X-Fixture: isolated")
        .args(arguments)
        .output()
        .unwrap()
}

#[test]
fn push_preserves_source_asset_payload_and_normal_cleanup() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello","description":"A greeting"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "POST",
            path: "/api/assets",
            body: json!({"addedAssetId": 7, "pollableTask": {"id": 11}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
        Step {
            method: "GET",
            path: "/api/repositories/1/branches",
            body: json!([{"id": 3, "name": "master"}]),
        },
        Step {
            method: "GET",
            path: "/api/assets/ids",
            body: json!([7, 8]),
        },
        Step {
            method: "DELETE",
            path: "/api/assets?branchId=3",
            body: json!({"id": 12}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/12",
            body: json!({"id": 12, "allFinished": true}),
        },
    ]);
    let output = run(
        &[
            "push",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--dir-path-include-patterns",
            "lang-mojito",
            "-b",
            "master",
            "--migrate-legacy-json-comments",
        ],
        &url,
        directory.path(),
    );
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }
    let recorded = requests.lock().unwrap();
    let uploaded: Value = serde_json::from_str(&recorded[1].body).unwrap();
    assert_eq!(uploaded["path"], "lang-mojito/en.json");
    assert_eq!(uploaded["repositoryId"], 1);
    assert_eq!(uploaded["branch"], "master");
    assert_eq!(uploaded["leveragingType"], "LEGACY_SOURCE");
    assert!(uploaded["filterOptions"]
        .as_array()
        .unwrap()
        .contains(&json!("mojito.converter=portable")));
    assert!(uploaded["filterOptions"]
        .as_array()
        .unwrap()
        .contains(&json!("mojito.migrateLegacyJsonComments=true")));
    assert!(recorded[1].headers.contains("X-Fixture: isolated"));
    assert_eq!(
        recorded[4].path,
        "/api/assets/ids?repositoryId=1&deleted=false&virtual=false&branchId=3"
    );
    assert_eq!(
        serde_json::from_str::<Value>(&recorded[5].body).unwrap(),
        json!([8])
    );
}

#[test]
fn normal_push_keeps_dotted_sources_and_excludes_localized_siblings_from_cleanup() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("catalogs");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(sources.join("messages.json"), r#"{"title":"Title"}"#).unwrap();
    std::fs::write(
        sources.join("messages.mobile.json"),
        r#"{"title":"Mobile title"}"#,
    )
    .unwrap();
    std::fs::write(sources.join("messages_fr.json"), r#"{"title":"Titre"}"#).unwrap();

    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "POST",
            path: "/api/assets",
            body: json!({"addedAssetId": 7, "pollableTask": {"id": 11}}),
        },
        Step {
            method: "POST",
            path: "/api/assets",
            body: json!({"addedAssetId": 8, "pollableTask": {"id": 12}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/12",
            body: json!({"id": 12, "allFinished": true}),
        },
        Step {
            method: "GET",
            path: "/api/repositories/1/branches",
            body: json!([{"id": 3, "name": "master"}]),
        },
        Step {
            method: "GET",
            path: "/api/assets/ids",
            body: json!([7, 8, 9]),
        },
        Step {
            method: "DELETE",
            path: "/api/assets?branchId=3",
            body: json!({"id": 13}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/13",
            body: json!({"id": 13, "allFinished": true}),
        },
    ]);
    let output = run(
        &[
            "push",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "JSON",
            "--dir-path-include-patterns",
            "catalogs",
            "-b",
            "master",
        ],
        &url,
        directory.path(),
    );
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }

    let recorded = requests.lock().unwrap();
    let uploaded_paths = [1, 2]
        .map(|index| serde_json::from_str::<Value>(&recorded[index].body).unwrap()["path"].clone());
    assert_eq!(
        uploaded_paths,
        [
            json!("catalogs/messages.json"),
            json!("catalogs/messages.mobile.json")
        ]
    );
    assert_eq!(
        serde_json::from_str::<Value>(&recorded[7].body).unwrap(),
        json!([9])
    );
}

#[test]
fn parallel_pull_preserves_locale_mapping_and_writes_translated_file() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "GET",
            path: "/api/assets?path=lang-mojito%2Fen.json",
            body: json!([{"id": 7}]),
        },
        Step {
            method: "POST",
            path: "/api/assets/7/localized/parallel",
            body: json!({"id": 11}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11/output",
            body: json!({"generateLocalizedAssetJobIds": {"fr-FR": 12}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/12/output",
            body: json!({"bcp47Tag": "fr-FR", "content": "{\"welcome\":\"Bonjour\"}"}),
        },
    ]);
    let output = run(
        &[
            "pull",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "JSON_NOBASENAME",
            "--parallel",
            "--pull-with-no-source-branches",
            "feature/a",
            " feature/b ",
            "--pull-with-no-source-null-branch",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "-fo",
            "noteKeyPattern=description",
            "extractAllPairs=false",
            "exceptions=defaultMessage",
            "removeKeySuffix=/defaultMessage",
            "--dir-path-include-patterns",
            "lang-mojito",
            "-lm",
            "fr-FR:fr",
        ],
        &url,
        directory.path(),
    );
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }
    assert_eq!(
        std::fs::read_to_string(sources.join("fr-FR.json")).unwrap(),
        r#"{"welcome":"Bonjour"}"#
    );
    let recorded = requests.lock().unwrap();
    let request: Value = serde_json::from_str(&recorded[2].body).unwrap();
    assert_eq!(request["inheritanceMode"], "REMOVE_UNTRANSLATED");
    assert_eq!(request["pullWithNoSource"], true);
    assert_eq!(
        request["pullWithNoSourceBranches"],
        json!(["feature/a", "feature/b", null])
    );
    assert_eq!(
        request["localeInfos"],
        json!([{"localeId": 20, "outputBcp47tag": "fr-FR"}])
    );
    assert!(request["filterOptions"]
        .as_array()
        .unwrap()
        .contains(&json!("mojito.converter=portable")));
}

#[test]
fn import_preserves_localized_payload_status_and_polling() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello"}}"#,
    )
    .unwrap();
    std::fs::write(
        sources.join("fr-FR.json"),
        r#"{"welcome":{"defaultMessage":"Bonjour"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "GET",
            path: "/api/assets?path=lang-mojito%2Fen.json",
            body: json!([{"id": 7}]),
        },
        Step {
            method: "POST",
            path: "/api/assets/7/localized/20/import",
            body: json!({"pollableTask": {"id": 11}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
    ]);
    let output = run(
        &[
            "import",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--dir-path-include-patterns",
            "lang-mojito",
            "-lm",
            "fr-FR:fr",
            "--status-equal-target",
            "REVIEW_NEEDED",
        ],
        &url,
        directory.path(),
    );
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }
    let recorded = requests.lock().unwrap();
    let imported: Value = serde_json::from_str(&recorded[2].body).unwrap();
    assert_eq!(imported["statusForEqualTarget"], "REVIEW_NEEDED");
    assert!(imported["content"].as_str().unwrap().contains("Bonjour"));
    assert!(imported["filterOptions"]
        .as_array()
        .unwrap()
        .contains(&json!("mojito.converter=portable")));
}

#[test]
fn jvm_properties_fail_before_any_server_connection() {
    let directory = tempfile::tempdir().unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_mojito"))
        .env_clear()
        .env("HOME", directory.path())
        .args([
            "-Dspring.config.location=/tmp/example",
            "push",
            "-r",
            "repo",
        ])
        .output()
        .unwrap();
    assert!(!output.status.success());
    let stderr = String::from_utf8(output.stderr).unwrap();
    assert!(stderr.contains("MOJITO_CONFIG"));
    assert!(stderr.contains("L10N_RESTTEMPLATE_"));
}

#[test]
fn synchronous_pull_retries_transient_server_failures() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "GET",
            path: "/api/assets?path=lang-mojito%2Fen.json",
            body: json!([{"id": 7}]),
        },
        Step {
            method: "POST",
            path: "/api/assets/7/localized/20",
            body: json!({"__mockStatus": 504, "__mockBody": "gateway timeout"}),
        },
        Step {
            method: "POST",
            path: "/api/assets/7/localized/20",
            body: json!({"bcp47Tag": "fr-FR", "content": "{\"welcome\":\"Bonjour\"}"}),
        },
    ]);
    let output = run(
        &[
            "pull",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "JSON_NOBASENAME",
            "-lm",
            "fr-FR:fr",
        ],
        &url,
        directory.path(),
    );
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }
    assert_eq!(requests.lock().unwrap().len(), 4);
    assert!(sources.join("fr-FR.json").is_file());
    assert!(String::from_utf8_lossy(&output.stderr).contains("Attempt 1/5"));
}

#[test]
fn stateful_login_preserves_session_cookies_and_csrf_tokens() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/login",
            body: json!({
                "__mockBody": "<script>CSRF_TOKEN = 'initial-token';</script>",
                "__mockHeaders": {"Set-Cookie": "JSESSIONID=before; Path=/"}
            }),
        },
        Step {
            method: "POST",
            path: "/login",
            body: json!({
                "__mockStatus": 302,
                "__mockBody": "",
                "__mockHeaders": {
                    "Location": "/",
                    "Set-Cookie": "JSESSIONID=authenticated; Path=/"
                }
            }),
        },
        Step {
            method: "GET",
            path: "/api/csrf-token",
            body: json!({"__mockBody": "session-token"}),
        },
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "POST",
            path: "/api/assets",
            body: json!({"addedAssetId": 7, "pollableTask": {"id": 11}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
    ]);

    let output = Command::new(env!("CARGO_BIN_EXE_mojito"))
        .env_clear()
        .env("HOME", directory.path())
        .args([
            "--url",
            &url,
            "--username",
            "fixture-user",
            "--password",
            "fixture-password",
            "--header",
            "X-Edge: fixture",
            "push",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--dir-path-include-patterns",
            "lang-mojito",
            "--push-type",
            "NO_DELETE",
        ])
        .output()
        .unwrap();
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }

    let recorded = requests.lock().unwrap();
    assert!(recorded[0].headers.contains("X-Edge: fixture"));
    assert!(recorded[1].headers.contains("X-Edge: fixture"));
    assert!(recorded[2].headers.contains("X-Edge: fixture"));
    assert!(recorded[1].headers.contains("JSESSIONID=before"));
    assert!(recorded[1].headers.contains("X-CSRF-TOKEN: initial-token"));
    assert!(recorded[3].headers.contains("JSESSIONID=authenticated"));
    assert!(recorded[3].headers.contains("X-CSRF-TOKEN: session-token"));
}

#[test]
fn existing_cloudflare_environment_headers_work_without_jvm_properties() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "POST",
            path: "/api/assets",
            body: json!({"addedAssetId": 7, "pollableTask": {"id": 11}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
    ]);
    let output = Command::new(env!("CARGO_BIN_EXE_mojito"))
        .env_clear()
        .env("HOME", directory.path())
        .env("MOJITO_URL", &url)
        .env("L10N_RESTTEMPLATE_AUTHENTICATION_MODE", "HEADER")
        .env(
            "L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID",
            "fixture-id",
        )
        .env(
            "L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET",
            "fixture-secret",
        )
        .args([
            "push",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--dir-path-include-patterns",
            "lang-mojito",
            "--push-type",
            "NO_DELETE",
        ])
        .output()
        .unwrap();
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }

    let recorded = requests.lock().unwrap();
    let headers = &recorded[0].headers;
    assert!(headers.contains("Cf-Access-Client-Id: fixture-id"));
    assert!(headers.contains("Cf-Access-Client-Secret: fixture-secret"));
}

#[test]
fn azure_client_credentials_exchange_is_compatible_with_existing_properties() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("lang-mojito");
    std::fs::create_dir_all(&sources).unwrap();
    std::fs::write(
        sources.join("en.json"),
        r#"{"welcome":{"defaultMessage":"Hello"}}"#,
    )
    .unwrap();
    let (url, requests, handle) = mock(vec![
        Step {
            method: "POST",
            path: "/tenant/oauth2/v2.0/token",
            body: json!({"access_token": "fixture-token"}),
        },
        Step {
            method: "GET",
            path: "/api/repositories?name=example",
            body: repository(),
        },
        Step {
            method: "POST",
            path: "/api/assets",
            body: json!({"addedAssetId": 7, "pollableTask": {"id": 11}}),
        },
        Step {
            method: "GET",
            path: "/api/pollableTasks/11",
            body: json!({"id": 11, "allFinished": true}),
        },
    ]);
    let output = Command::new(env!("CARGO_BIN_EXE_mojito"))
        .env_clear()
        .env("HOME", directory.path())
        .env("MOJITO_URL", &url)
        .env("L10N_RESTTEMPLATE_AUTHENTICATION_MODE", "STATELESS")
        .env(
            "L10N_RESTTEMPLATE_STATELESS_PROVIDER",
            "MSAL_CLIENT_CREDENTIALS",
        )
        .env(
            "L10N_RESTTEMPLATE_STATELESS_MSAL_AUTHORITY",
            format!("{url}/tenant"),
        )
        .env("L10N_RESTTEMPLATE_STATELESS_MSAL_CLIENT_ID", "fixture-id")
        .env(
            "L10N_RESTTEMPLATE_STATELESS_MSAL_CLIENT_SECRET",
            "fixture-secret",
        )
        .env(
            "L10N_RESTTEMPLATE_STATELESS_MSAL_SCOPES",
            "api://fixture/.default",
        )
        .args([
            "push",
            "-r",
            "example",
            "-s",
            directory.path().to_str().unwrap(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--dir-path-include-patterns",
            "lang-mojito",
            "--push-type",
            "NO_DELETE",
        ])
        .output()
        .unwrap();
    handle.join().unwrap();
    if !output.status.success() {
        panic!("{}", String::from_utf8_lossy(&output.stderr));
    }

    let recorded = requests.lock().unwrap();
    assert!(recorded[0].body.contains("grant_type=client_credentials"));
    assert!(recorded[0].body.contains("client_id=fixture-id"));
    assert!(recorded[1]
        .headers
        .contains("Authorization: Bearer fixture-token"));
}
