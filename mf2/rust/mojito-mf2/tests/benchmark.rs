use mojito_mf2::parse_to_model;
use serde_json::json;
use std::{fs, process::Command};

#[test]
fn benchmark_uses_fixture_options_utf8_bytes_and_preflights_every_case() {
    let directory =
        std::env::temp_dir().join(format!("mf2-benchmark-check-{}", std::process::id()));
    fs::create_dir(&directory).unwrap();
    let path = directory.join("unicode.json");
    let source = "{$x}";
    let mut fixture = json!({
        "source": source,
        "expectedModel": parse_to_model(source).model.unwrap(),
        "formatCases": [
            {"locale":"en", "arguments":{"x":"é😀"}, "expected":"é😀"},
            {"locale":"en", "bidiIsolation":"default", "arguments":{"x":"é😀"},
             "expected":"\u{2068}é😀\u{2069}"}
        ]
    });
    fs::write(&path, serde_json::to_vec(&fixture).unwrap()).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_mojito-mf2"))
        .args(["bench", directory.to_str().unwrap(), "3", "1"])
        .output()
        .unwrap();
    assert!(output.status.success(), "{:?}", output);
    let stdout = String::from_utf8(output.stdout).unwrap();
    // Two unisolated strings (six bytes each), one isolated string (12 bytes).
    assert!(stdout.contains("cases=2 "), "{stdout}");
    assert!(stdout.contains(" bytes=24"), "{stdout}");

    fixture["formatCases"][1]["expected"] = json!("wrong");
    fs::write(&path, serde_json::to_vec(&fixture).unwrap()).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_mojito-mf2"))
        .args(["bench", directory.to_str().unwrap(), "1", "0"])
        .output()
        .unwrap();
    fs::remove_dir_all(directory).unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8(output.stderr)
        .unwrap()
        .contains("Benchmark preflight mismatch"));
    assert!(!String::from_utf8(output.stdout)
        .unwrap()
        .contains("rust format iterations="));
}

#[test]
fn parser_benchmark_preflights_diagnostics_and_rejects_invalid_counts() {
    let directory =
        std::env::temp_dir().join(format!("mf2-parser-benchmark-check-{}", std::process::id()));
    fs::create_dir(&directory).unwrap();
    let valid = json!({"source":"é😀"});
    let mut invalid = json!({
        "source":".input {$x :string} .match $x * {{ok}} {x}",
        "expectedDiagnostics":[{"code":"invalid-variant-key"}]
    });
    fs::write(
        directory.join("a-valid.json"),
        serde_json::to_vec(&valid).unwrap(),
    )
    .unwrap();
    let invalid_path = directory.join("z-invalid.json");
    fs::write(&invalid_path, serde_json::to_vec(&invalid).unwrap()).unwrap();
    let run = |mode: &str, iterations: &str, warmup: &str| {
        Command::new(env!("CARGO_BIN_EXE_mojito-mf2"))
            .args([mode, directory.to_str().unwrap(), iterations, warmup])
            .output()
            .unwrap()
    };
    let output = run("bench-parse", "2", "0");
    assert!(output.status.success(), "{:?}", output);
    let stdout = String::from_utf8(output.stdout).unwrap();
    let expected_bytes =
        valid["source"].as_str().unwrap().len() + invalid["source"].as_str().unwrap().len();
    assert!(stdout.contains("sources=2 "), "{stdout}");
    assert!(
        stdout.contains(&format!(" bytes={expected_bytes} ")),
        "{stdout}"
    );
    assert!(stdout.contains("diagnostics=1 models=1"), "{stdout}");

    for mode in ["bench", "bench-parse"] {
        for (iterations, warmup) in [("0", "0"), ("-1", "0"), ("oops", "0"), ("1", "-1")] {
            let output = run(mode, iterations, warmup);
            assert_eq!(output.status.code(), Some(2), "{:?}", output);
            assert!(String::from_utf8(output.stderr)
                .unwrap()
                .contains("Invalid "));
            assert!(output.stdout.is_empty());
        }
    }

    invalid["expectedDiagnostics"][0]["code"] = json!("wrong");
    fs::write(&invalid_path, serde_json::to_vec(&invalid).unwrap()).unwrap();
    let output = run("bench-parse", "1", "0");
    fs::remove_dir_all(directory).unwrap();
    assert_eq!(output.status.code(), Some(1), "{:?}", output);
    assert!(String::from_utf8(output.stderr)
        .unwrap()
        .contains("Parser benchmark preflight mismatch"));
    assert!(output.stdout.is_empty());
}
