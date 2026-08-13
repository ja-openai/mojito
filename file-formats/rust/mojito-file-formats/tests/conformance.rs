use mojito_file_formats::{
    compare_shadow, extract_android_overlay_skeleton,
    extract_android_overlay_skeleton_with_context, extract_skeleton_with_android_context,
    extract_skeleton_with_android_feature_flags, extract_skeleton_with_apple_variations,
    extract_skeleton_with_encoding, localize_for_mojito, localize_for_mojito_locale,
    parse_android_overlay_with_context,
    parse_android_overlay_with_feature_flag_definitions_and_package, parse_for_mojito,
    parse_for_mojito_import, parse_with_android_context,
    parse_with_android_feature_flag_definitions_and_package, render_android_overlay_skeleton,
    render_skeleton, write, AndroidFeatureFlag, AndroidResourceInput, FileFormat, LegacyTextUnit,
};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

#[test]
fn all_shared_mojito_workflow_fixtures() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let cases = manifest["workflowCases"]
        .as_array()
        .expect("workflow contracts");
    assert!(
        !cases.is_empty(),
        "configured workflow contracts cannot be skipped"
    );
    for fixture in cases {
        let id = fixture["id"].as_str().unwrap();
        let format = FileFormat::from_id(fixture["format"].as_str().unwrap()).unwrap();
        let source = fs::read(root.join(fixture["input"].as_str().unwrap())).unwrap();
        let options = fixture["filterOptions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| value.as_str().unwrap().to_owned())
            .collect::<Vec<_>>();
        if let Some(error) = fixture.get("error").and_then(Value::as_str) {
            let result =
                if let Some(values) = fixture.get("translations").and_then(Value::as_object) {
                    let translations = values
                        .iter()
                        .map(|(key, value)| (key.clone(), value.as_str().unwrap().to_owned()))
                        .collect::<BTreeMap<_, _>>();
                    localize_for_mojito(
                        format,
                        &source,
                        &translations,
                        &options,
                        fixture["removeUntranslated"].as_bool().unwrap_or(false),
                    )
                    .map(|_| ())
                } else if let Some(policy) = fixture.get("importPolicy") {
                    parse_for_mojito_import(
                        format,
                        &source,
                        &options,
                        policy["targetLocale"].as_str().unwrap(),
                        policy["copyFormsOnImport"].as_bool().unwrap_or(false),
                    )
                    .map(|_| ())
                } else {
                    parse_for_mojito(format, &source, &options).map(|_| ())
                };
            assert_eq!(
                result.expect_err(&format!("{id}: expected {error}")).code,
                error,
                "{id}"
            );
            continue;
        }
        if let Some(path) = fixture.get("expected").and_then(Value::as_str) {
            let expected: Value =
                serde_json::from_slice(&fs::read(root.join(path)).unwrap()).unwrap();
            let actual = if let Some(policy) = fixture.get("importPolicy") {
                parse_for_mojito_import(
                    format,
                    &source,
                    &options,
                    policy["targetLocale"].as_str().unwrap(),
                    policy["copyFormsOnImport"].as_bool().unwrap_or(false),
                )
            } else {
                parse_for_mojito(format, &source, &options)
            }
            .unwrap_or_else(|error| panic!("{id}: workflow extraction failed: {error}"));
            assert_eq!(serde_json::to_value(&actual).unwrap(), expected, "{id}");
            if fixture["importRoundTrip"].as_bool().unwrap_or(false) {
                let normalized = write(format, &actual)
                    .unwrap_or_else(|error| panic!("{id}: imported normalized output: {error}"));
                let reparsed = mojito_file_formats::parse(format, normalized.as_bytes())
                    .unwrap_or_else(|error| panic!("{id}: imported normalized reparse: {error}"));
                assert_eq!(
                    serde_json::to_value(reparsed).unwrap(),
                    expected,
                    "{id}: imported normalized round trip"
                );
            }
        }
        if let Some(values) = fixture.get("translations").and_then(Value::as_object) {
            let translations = values
                .iter()
                .map(|(key, value)| (key.clone(), value.as_str().unwrap().to_owned()))
                .collect::<BTreeMap<_, _>>();
            let bytes = localize_for_mojito_locale(
                format,
                &source,
                &translations,
                &options,
                fixture["removeUntranslated"].as_bool().unwrap_or(false),
                fixture.get("targetLocale").and_then(Value::as_str),
            )
            .unwrap_or_else(|error| panic!("{id}: workflow output failed: {error}"));
            let localized = String::from_utf8(bytes).unwrap();
            if let Some(path) = fixture.get("localized").and_then(Value::as_str) {
                let expected = if path.is_empty() {
                    String::new()
                } else {
                    fs::read_to_string(root.join(path)).unwrap()
                };
                assert_eq!(localized, expected, "{id}: localized output");
            }
            if let Some(expected) = fixture
                .get("localizedEndsWithNewline")
                .and_then(Value::as_bool)
            {
                assert_eq!(
                    localized.ends_with('\n'),
                    expected,
                    "{id}: source-owned final newline"
                );
            }
            for value in fixture
                .get("localizedContains")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                assert!(
                    localized.contains(value.as_str().unwrap()),
                    "{id}: missing {value}"
                );
            }
            for value in fixture
                .get("localizedExcludes")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                assert!(
                    !localized.contains(value.as_str().unwrap()),
                    "{id}: retained {value}"
                );
            }
        }
    }
}

#[test]
fn mojito_workflow_preserves_utf16_source_encodings() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let android = fs::read_to_string(root.join("fixtures/workflow/android-output.xml"))
        .unwrap()
        .replace("encoding=\"utf-8\"", "encoding=\"UTF-16LE\"");
    let mut android_bytes = vec![0xff, 0xfe];
    android_bytes.extend(android.encode_utf16().flat_map(u16::to_le_bytes));
    let output = localize_for_mojito(
        FileFormat::Android,
        &android_bytes,
        &BTreeMap::from([
            ("retained".to_owned(), "Bonjour".to_owned()),
            ("count#other".to_owned(), "Articles".to_owned()),
        ]),
        &[
            "removeDescription=true".to_owned(),
            "postRemoveTranslatableFalse=true".to_owned(),
        ],
        true,
    )
    .expect("Android UTF-16 workflow");
    assert!(output.starts_with(&[0xff, 0xfe]));
    let units = output[2..]
        .chunks_exact(2)
        .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
        .collect::<Vec<_>>();
    assert!(String::from_utf16(&units)
        .unwrap()
        .contains("encoding=\"UTF-16LE\""));

    let apple = fs::read_to_string(root.join("fixtures/workflow/apple-output.strings")).unwrap();
    let mut apple_bytes = vec![0xfe, 0xff];
    apple_bytes.extend(apple.encode_utf16().flat_map(u16::to_be_bytes));
    let output = localize_for_mojito(
        FileFormat::AppleStrings,
        &apple_bytes,
        &BTreeMap::from([("visible".to_owned(), "Bonjour".to_owned())]),
        &["removeComment=true".to_owned()],
        true,
    )
    .expect("Apple UTF-16 workflow");
    assert!(output.starts_with(&[0xfe, 0xff]));
    let units = output[2..]
        .chunks_exact(2)
        .map(|pair| u16::from_be_bytes([pair[0], pair[1]]))
        .collect::<Vec<_>>();
    assert!(String::from_utf16(&units).unwrap().contains("Bonjour"));

    let plurals = fs::read_to_string(root.join("fixtures/apple/multiple.stringsdict")).unwrap();
    let mut plural_bytes = vec![0xff, 0xfe];
    plural_bytes.extend(plurals.encode_utf16().flat_map(u16::to_le_bytes));
    let output = localize_for_mojito_locale(
        FileFormat::AppleStringsdict,
        &plural_bytes,
        &BTreeMap::from([
            (
                "summary#files#other".to_owned(),
                "{files} 個のファイル".to_owned(),
            ),
            (
                "summary#folders#other".to_owned(),
                "{folders} 個のフォルダー".to_owned(),
            ),
        ]),
        &[],
        false,
        Some("ja-JP"),
    )
    .expect("Apple plural UTF-16 workflow");
    assert!(output.starts_with(&[0xff, 0xfe]));
    let units = output[2..]
        .chunks_exact(2)
        .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
        .collect::<Vec<_>>();
    let localized = String::from_utf16(&units).unwrap();
    assert!(!localized.contains("<key>one</key>"));
    assert!(localized.contains("個のファイル"));
}

#[test]
fn all_shared_localization_format_fixtures() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let cases = manifest["cases"].as_array().unwrap();
    assert!(
        cases.len() >= 24,
        "the shared manifest must not be silently skipped"
    );

    for case in cases {
        let id = case["id"].as_str().unwrap();
        let format = FileFormat::from_id(case["format"].as_str().unwrap()).unwrap();
        let mut source = fs::read_to_string(root.join(case["input"].as_str().unwrap())).unwrap();
        match case.get("lineEndings").and_then(Value::as_str) {
            Some("CR") => source = source.replace("\r\n", "\n").replace('\n', "\r"),
            Some("CRLF") => source = source.replace("\r\n", "\n").replace('\n', "\r\n"),
            _ => {}
        }
        let encoding = case.get("encoding").and_then(Value::as_str);
        let mut bytes = if let Some(path) = case.get("binaryFixture").and_then(Value::as_str) {
            let hex = fs::read_to_string(root.join(path))
                .unwrap()
                .split_whitespace()
                .collect::<String>();
            hex.as_bytes()
                .chunks_exact(2)
                .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
                .collect()
        } else {
            encode(&source, encoding)
        };
        if let Some(padding) = case.get("binaryPaddingBytes").and_then(Value::as_u64) {
            bytes.resize(bytes.len() + usize::try_from(padding).unwrap(), 0);
        }
        let resource_path = case.get("resourcePath").and_then(Value::as_str);
        let feature_flags = android_feature_flags(case);
        let definitions = android_feature_flag_definitions(case);
        let products = android_selected_products(case);
        let application_package = case
            .get("androidApplicationPackage")
            .and_then(Value::as_str);
        let actual = if let Some(definitions) = definitions.as_ref() {
            parse_with_android_feature_flag_definitions_and_package(
                format,
                &bytes,
                encoding,
                resource_path,
                definitions,
                products.as_deref(),
                application_package,
            )
        } else {
            parse_with_android_context(
                format,
                &bytes,
                encoding,
                resource_path,
                &feature_flags,
                products.as_deref(),
                application_package,
            )
        };
        if let Some(error) = case.get("error").and_then(Value::as_str) {
            assert_eq!(
                actual.expect_err(&format!("{id}: expected {error}")).code,
                error,
                "{id}"
            );
        } else {
            let expected: Value = serde_json::from_slice(
                &fs::read(root.join(case["expected"].as_str().unwrap())).unwrap(),
            )
            .unwrap();
            let catalog =
                actual.unwrap_or_else(|error| panic!("{id}: unexpectedly failed: {error}"));
            assert_eq!(serde_json::to_value(&catalog).unwrap(), expected, "{id}");
            if let Some(path) = case
                .get("androidNormalized")
                .or_else(|| case.get("appleNormalized"))
                .or_else(|| case.get("appleStringsdictNormalized"))
                .or_else(|| case.get("xcstringsNormalized"))
                .or_else(|| case.get("propertiesNormalized"))
                .or_else(|| case.get("gettextNormalized"))
                .or_else(|| case.get("resxNormalized"))
                .and_then(Value::as_str)
            {
                let normalized = write(format, &catalog)
                    .unwrap_or_else(|error| panic!("{id}: normalized writing failed: {error}"));
                assert_eq!(
                    normalized,
                    fs::read_to_string(root.join(path)).unwrap(),
                    "{id}: deterministic normalized resource"
                );
                let repeated = if let Some(definitions) = definitions.as_ref() {
                    parse_with_android_feature_flag_definitions_and_package(
                        format,
                        normalized.as_bytes(),
                        None,
                        resource_path,
                        definitions,
                        products.as_deref(),
                        application_package,
                    )
                } else {
                    parse_with_android_context(
                        format,
                        normalized.as_bytes(),
                        None,
                        resource_path,
                        &feature_flags,
                        products.as_deref(),
                        application_package,
                    )
                }
                .unwrap_or_else(|error| panic!("{id}: round-trip parsing failed: {error}"));
                assert_eq!(
                    serde_json::to_value(&repeated).unwrap(),
                    expected,
                    "{id}: lossless canonical round trip"
                );
                assert_eq!(
                    write(format, &repeated).unwrap(),
                    normalized,
                    "{id}: normalized writing is idempotent"
                );
            }
            if let Some(rejected) = case.get("writerReject") {
                let target = FileFormat::from_id(rejected["format"].as_str().unwrap()).unwrap();
                let expected_error = rejected["error"].as_str().unwrap();
                assert_eq!(
                    write(target, &catalog)
                        .expect_err(&format!("{id}: expected stable writer error"))
                        .code,
                    expected_error,
                    "{id}: stable writer error"
                );
            }
            for mutation in case
                .get("writerMutations")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                let mut modified = catalog.clone();
                let descriptor = modified
                    .messages
                    .get_mut(mutation["message"].as_str().unwrap())
                    .unwrap();
                if let Some(description) = mutation.get("description").and_then(Value::as_str) {
                    descriptor.description = Some(description.to_owned());
                }
                if let Some(variants) = mutation.get("variants") {
                    descriptor.variants = Some(serde_json::from_value(variants.clone()).unwrap());
                }
                if let Some(metadata) = mutation.get("metadata") {
                    descriptor.metadata = Some(serde_json::from_value(metadata.clone()).unwrap());
                }
                assert_eq!(
                    write(format, &modified)
                        .expect_err(&format!("{id}: expected stable writer mutation error"))
                        .code,
                    mutation["error"].as_str().unwrap(),
                    "{id}: stable writer mutation error"
                );
            }
        }
    }
}

#[test]
fn all_shared_source_preserving_skeletons() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let cases = manifest["sourceSkeletons"].as_array().unwrap();
    assert!(
        !cases.is_empty(),
        "source-preserving skeletons must not be skipped"
    );
    for case in cases {
        let id = case["id"].as_str().unwrap();
        let format = FileFormat::from_id(case["format"].as_str().unwrap()).unwrap();
        let encoding = case.get("encoding").and_then(Value::as_str);
        let line_endings = case.get("lineEndings").and_then(Value::as_str);
        let adjust = |source: String| {
            if line_endings == Some("CRLF") {
                source.replace("\r\n", "\n").replace('\n', "\r\n")
            } else if line_endings == Some("CR") {
                source.replace("\r\n", "\n").replace('\n', "\r")
            } else {
                source
            }
        };
        let original = encode(
            &adjust(fs::read_to_string(root.join(case["input"].as_str().unwrap())).unwrap()),
            encoding,
        );
        let properties_encoding = if format == FileFormat::JavaProperties {
            encoding
        } else {
            None
        };
        let definitions = android_feature_flag_definitions(case);
        let resource_path = case.get("resourcePath").and_then(Value::as_str);
        if case["xcstringsInsertSourceLocale"].as_bool() == Some(true) {
            assert_eq!(
                extract_skeleton_with_encoding(format, &original, properties_encoding)
                    .unwrap_err()
                    .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: default Xcode extraction must preserve fallback rejection"
            );
        }
        let skeleton = if let Some(locale) = case["xcstringsTargetLocale"].as_str() {
            mojito_file_formats::extract_skeleton_with_xcode_target_insertion(&original, locale)
        } else if case["xcstringsInsertSourceLocale"].as_bool() == Some(true) {
            mojito_file_formats::extract_skeleton_with_xcode_source_insertion(&original)
        } else if case["appleAllVariationSlots"].as_bool() == Some(true) {
            extract_skeleton_with_apple_variations(&original)
        } else if case["xcstringsAllDeviceSlots"].as_bool() == Some(true) {
            mojito_file_formats::extract_skeleton_with_xcode_devices(&original)
        } else if let Some(path) = resource_path {
            extract_skeleton_with_android_context(
                &original,
                path,
                definitions.as_deref().unwrap_or_default(),
            )
        } else if let Some(flags) = &definitions {
            extract_skeleton_with_android_feature_flags(&original, flags)
        } else {
            extract_skeleton_with_encoding(format, &original, properties_encoding)
        }
        .unwrap_or_else(|error| panic!("{id}: source skeleton failed: {error}"));
        let expected: Value = serde_json::from_slice(
            &fs::read(root.join(case["expected"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        assert_eq!(serde_json::to_value(&skeleton).unwrap(), expected, "{id}");
        assert_eq!(
            render_skeleton(&skeleton, &BTreeMap::new()).unwrap(),
            original,
            "{id}: an untranslated skeleton must preserve every original byte"
        );
        let translations: BTreeMap<String, String> = serde_json::from_slice(
            &fs::read(root.join(case["translations"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        let actual = render_skeleton(&skeleton, &translations)
            .unwrap_or_else(|error| panic!("{id}: source reinjection failed: {error}"));
        let localized = encode(
            &adjust(fs::read_to_string(root.join(case["localized"].as_str().unwrap())).unwrap()),
            encoding,
        );
        assert_eq!(
            actual, localized,
            "{id}: source-preserving localized output"
        );
        let parsed = if definitions.is_some() || resource_path.is_some() {
            mojito_file_formats::parse_with_android_feature_flag_definitions(
                format,
                &actual,
                properties_encoding,
                resource_path,
                definitions.as_deref().unwrap_or_default(),
                None,
            )
            .unwrap()
        } else {
            mojito_file_formats::parse_with_encoding(format, &actual, properties_encoding).unwrap()
        };
        if format == FileFormat::Android {
            if id.starts_with("android-source-skeleton-preserves-literal-hashes") {
                assert_eq!(parsed.messages["scalar_hash"].default_message, "Quai #7");
                assert_eq!(parsed.messages["escaped_hash"].default_message, "Quai #8");
                assert_eq!(parsed.messages["entity_hash"].default_message, "Quai #9");
                assert_eq!(parsed.messages["unicode_hash"].default_message, "Quai #10");
                assert_eq!(
                    parsed.messages["protected_conventional"].default_message,
                    "{arg0} #port"
                );
                assert_eq!(
                    parsed.messages["protected_conventional"]
                        .metadata
                        .as_ref()
                        .unwrap()["androidProtectedPlaceholderOccurrences"]["arg0"],
                    serde_json::json!([{}])
                );
                assert_eq!(
                    parsed.messages["mixed_conventional"]
                        .metadata
                        .as_ref()
                        .unwrap()["androidProtectedPlaceholderOccurrences"]["arg0"],
                    serde_json::json!([{}, null, {"example": ""}])
                );
                assert_eq!(
                    parsed.messages["conventional_routes[0]"]
                        .metadata
                        .as_ref()
                        .unwrap()["androidProtectedPlaceholderOccurrences"]["arg0"],
                    serde_json::json!([{}])
                );
                assert_eq!(
                    parsed.messages["conventional_signals"]
                        .metadata
                        .as_ref()
                        .unwrap()["androidPluralProtectedPlaceholderOccurrences"]["one"]["arg0"],
                    serde_json::json!([{}])
                );
                assert_eq!(
                    parsed.messages["conventional_signals"]
                        .metadata
                        .as_ref()
                        .unwrap()["androidPluralProtectedPlaceholderOccurrences"]["other"]["arg0"],
                    serde_json::json!([null, {}])
                );
                for color in [
                    "escaped_color",
                    "unicode_color",
                    "quoted_color",
                    "strict_color",
                ] {
                    assert_eq!(parsed.messages[color].default_message, "#def");
                }
                assert!(!parsed.messages.contains_key("primitive_color"));
                assert!(!parsed.messages.contains_key("mixed_hashes[0]"));
                assert_eq!(
                    parsed.messages["mixed_hashes[1]"]
                        .metadata
                        .as_ref()
                        .unwrap()["androidArrayPrimitives"]["0"],
                    "#abc"
                );
                assert_eq!(
                    parsed.messages["mixed_hashes[3]"].default_message,
                    "Quai ##4"
                );
                assert_eq!(
                    parsed.messages["raw_signals"].variants.as_ref().unwrap()["one"],
                    "{arg0} voie #nord"
                );
                assert_eq!(
                    parsed.messages["raw_signals"].variants.as_ref().unwrap()["other"],
                    "{arg0} voies ##nord"
                );
                assert_eq!(
                    parsed.messages["styled_signals"].variants.as_ref().unwrap()["one"],
                    "{arg0} '<'b lane=\"#inner\">#bleu'<'/b> voie"
                );
                assert_eq!(
                    parsed.messages["styled_signals"].variants.as_ref().unwrap()["other"],
                    "{arg0} '<'i lane=\"hash#inner\">##bleu'<'/i> voies"
                );
                assert_eq!(
                    parsed.messages["protected_signals"]
                        .placeholders
                        .as_ref()
                        .unwrap()[0]
                        .example
                        .as_deref(),
                    Some("#1")
                );
                assert_eq!(
                    parsed.messages["protected_signals"]
                        .placeholders
                        .as_ref()
                        .unwrap()[1]
                        .example
                        .as_deref(),
                    Some("#2")
                );
                let category_examples = &parsed.messages["protected_signals"]
                    .metadata
                    .as_ref()
                    .unwrap()["androidPluralPlaceholderExamples"];
                assert_eq!(category_examples["one"]["count"][0], "#1");
                assert_eq!(category_examples["other"]["count"][0], "#2");
                let optional_examples = &parsed.messages["optional_examples"]
                    .metadata
                    .as_ref()
                    .unwrap()["androidPluralPlaceholderExamples"];
                assert!(optional_examples["one"]["count"][0].is_null());
                assert_eq!(optional_examples["other"]["count"][0], "#many");
                let empty_examples = &parsed.messages["empty_examples"].metadata.as_ref().unwrap()
                    ["androidPluralPlaceholderExamples"];
                assert_eq!(empty_examples["one"]["count"][0], "");
                assert!(empty_examples["other"]["count"][0].is_null());
                let repeated_examples = &parsed.messages["repeated_examples"]
                    .metadata
                    .as_ref()
                    .unwrap()["androidPluralPlaceholderExamples"];
                assert_eq!(repeated_examples["one"]["count"][0], "#lead");
                assert_eq!(repeated_examples["one"]["count"][1], "#tail");
                assert_eq!(repeated_examples["other"]["count"][0], "#tail");
                assert_eq!(repeated_examples["other"]["count"][1], "#lead");
                assert_eq!(
                    parsed.messages["protected_signals"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{count} voies protégées ##nord"
                );
                assert_eq!(
                    parsed.messages["product_signals"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{arg0} défaut ##signaux"
                );
                assert_eq!(
                    parsed.messages["product_signals@product=tablet"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{arg0} tablette ##signaux"
                );
                assert!(!parsed.messages.contains_key("protected_private"));
            } else if id.starts_with("android-source-skeleton-preserves-xml-attribute-control") {
                assert_eq!(parsed.messages["scalar"].default_message, "Port calme");
                assert_eq!(
                    parsed.messages["scalar"].description.as_deref(),
                    Some("North\tdeck\nline\rend")
                );
                assert_eq!(parsed.messages["generic"].default_message, "Voie générale");
                assert_eq!(
                    parsed.messages["generic"].description.as_deref(),
                    Some("Generic\r\ndeck")
                );
                assert_eq!(
                    parsed.messages["protected"].placeholders.as_ref().unwrap()[0]
                        .example
                        .as_deref(),
                    Some("A\tB\nC\rD")
                );
                assert_eq!(
                    parsed.messages["routes[0]"].description.as_deref(),
                    Some("Route\tdeck")
                );
                assert_eq!(
                    parsed.messages["routes[1]"].placeholders.as_ref().unwrap()[0]
                        .example
                        .as_deref(),
                    Some("P\r\n7")
                );
                assert_eq!(
                    parsed.messages["signals"].description.as_deref(),
                    Some("Count\tlights\nvisible")
                );
                assert_eq!(
                    parsed.messages["signals"].placeholders.as_ref().unwrap()[0]
                        .example
                        .as_deref(),
                    Some("M\t1\n2\r3")
                );
                assert_eq!(
                    parsed.messages["styled"].default_message,
                    "'<'annotation route=\"north\tmiddle\nend\rfinish\">Style visible'<'/annotation>"
                );
                assert!(!parsed.messages.contains_key("untouched"));
            } else if id.starts_with("android-source-skeleton-preserves-whitespace-bearing") {
                assert_eq!(parsed.messages["visible"].default_message, "Un port calme");
                assert_eq!(
                    parsed.messages["quoted"].default_message,
                    "Référence @ préservée"
                );
                assert_eq!(
                    parsed.messages["escaped"].default_message,
                    "Référence @ échappée"
                );
                assert_eq!(
                    parsed.messages["routes[6]"].default_message,
                    "Route visible traduite"
                );
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["other"],
                    "{arg0} lumières visibles"
                );
                for reference in ["space", "line", "carriage", "double_tab", "untouched"] {
                    assert!(!parsed.messages.contains_key(reference));
                }
                let array = &parsed.messages["routes[6]"].metadata.as_ref().unwrap()
                    ["androidArrayReferences"];
                assert_eq!(array["0"], "@string/north pier");
                assert_eq!(array["1"], "@string/north\tpier");
                assert_eq!(array["2"], "@string/north\npier");
                assert_eq!(array["3"], "@string/north\rpier");
                assert_eq!(array["4"], "@string/north\r\npier");
                let plural = &parsed.messages["signals"].metadata.as_ref().unwrap()
                    ["androidPluralReferences"];
                assert_eq!(plural["zero"], "@string/north\rpier");
                assert_eq!(plural["two"], "@string/north\tpier");
                assert_eq!(plural["few"], "@string/north\npier");
            } else if id.starts_with("android-source-skeleton-preserves-doubled-resource-aliases") {
                assert_eq!(parsed.messages["anchor"].default_message, "Abri calme");
                assert_eq!(
                    parsed.messages["escaped"].default_message,
                    "Texte @@ protégé"
                );
                assert_eq!(parsed.messages["quoted"].default_message, "Texte @@ cité");
                assert_eq!(
                    parsed.messages["triple"].default_message,
                    "Texte @@@ distinct"
                );
                assert_eq!(
                    parsed.messages["single_macro"].default_message,
                    "Instruction directe traduite"
                );
                assert_eq!(
                    parsed.messages["double_macro"].default_message,
                    "Instruction indirecte traduite"
                );
                assert_eq!(
                    parsed.messages["routes[2]"].default_message,
                    "Route macro traduite"
                );
                assert_eq!(
                    parsed.messages["routes[3]"].default_message,
                    "Route visible traduite"
                );
                let variants = parsed.messages["signals"].variants.as_ref().unwrap();
                assert_eq!(variants["two"], "Deux instructions traduites");
                assert_eq!(variants["other"], "{arg0} signaux traduits");
                for reference in [
                    "single_alias",
                    "double_alias",
                    "generic_alias",
                    "double_comment",
                    "untouched",
                ] {
                    assert!(!parsed.messages.contains_key(reference));
                }
                assert_eq!(
                    parsed.messages["routes[2]"].metadata.as_ref().unwrap()
                        ["androidArrayReferences"]["1"],
                    "@@string/anchor"
                );
                assert_eq!(
                    parsed.messages["signals"].metadata.as_ref().unwrap()
                        ["androidPluralReferences"]["one"],
                    "@@string/anchor"
                );
            } else if id.starts_with("android-source-skeleton-preserves-literal-quotes") {
                assert_eq!(
                    parsed.messages["scalar"].default_message,
                    "Quai \"nord\" et \"calme\""
                );
                assert_eq!(
                    parsed.messages["styled"].default_message,
                    "Port '<'b lane=\"harbor&quot;side\">\"doux\"'<'/b> sûr"
                );
                assert_eq!(
                    parsed.messages["protected"].default_message,
                    "Pilote {pilot} vers \"nord\""
                );
                assert_eq!(
                    parsed.messages["commented"].default_message,
                    "Port \"bleu\" calme"
                );
                assert_eq!(
                    parsed.messages["routes[0]"].default_message,
                    "Quai \"ouest, calme\""
                );
                assert_eq!(
                    parsed.messages["routes[1]"].default_message,
                    "Port '<'i lane=\"water&quot;side\">\"stable\"'<'/i> sûr"
                );
                let variants = parsed.messages["signals"].variants.as_ref().unwrap();
                assert_eq!(variants["one"], "Une \"lumière\" sûre");
                assert_eq!(variants["other"], "{arg0} <b>\"lumières\"</b> sûres");
                assert!(!parsed.messages.contains_key("untouched"));
            } else if case["androidDecoratedInline"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["protected_comments"].default_message,
                    translations["protected_comments"]
                );
                assert_eq!(
                    parsed.messages["routes[1]"].default_message,
                    translations["routes[1]"]
                );
                for message in [
                    "styled_comments",
                    "nested_comments",
                    "comment_only",
                    "routes[0]",
                ] {
                    let canonical = &parsed.messages[message].default_message;
                    assert!(canonical.find("'<'i") < canonical.find("'<'b"));
                }
                for quantity in ["one", "other"] {
                    let canonical =
                        &parsed.messages["signals"].variants.as_ref().unwrap()[quantity];
                    assert!(canonical.find("'<'i") < canonical.find("'<'b"));
                    assert!(canonical.contains("{arg0} nord"));
                }
                assert!(!parsed.messages.contains_key("private_route"));
            } else if case["androidReorderableInline"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["protected_route"].default_message,
                    translations["protected_route"]
                );
                assert_eq!(
                    parsed.messages["routes[1]"].default_message,
                    translations["routes[1]"]
                );
                for message in ["styled_route", "nested_route", "mixed_route", "routes[0]"] {
                    let canonical = &parsed.messages[message].default_message;
                    assert!(canonical.find("'<'i") < canonical.find("'<'b"));
                }
                let repeated = &parsed.messages["repeated_style"].default_message;
                assert!(repeated.find("lane=\"south\"") < repeated.find("lane=\"north\""));
                for quantity in ["one", "other"] {
                    let canonical =
                        &parsed.messages["signals"].variants.as_ref().unwrap()[quantity];
                    assert!(canonical.find("'<'i") < canonical.find("'<'b"));
                    assert!(canonical.contains("{arg0} nord"));
                }
                assert!(!parsed.messages.contains_key("private_route"));
            } else if id.contains("read-only-items") || id.contains("array-item-ownership") {
                assert_eq!(parsed.messages["anchor"].default_message, "Ancre sûre");
                assert_eq!(
                    parsed.messages["lanes[0]"].default_message,
                    "Route nord visible"
                );
                assert_eq!(
                    parsed.messages["lanes[1]"].default_message,
                    "Route est mutable"
                );
                assert_eq!(
                    parsed.messages["lanes[2]"].default_message,
                    "Inverse <ouest> & sûr"
                );
                assert_eq!(
                    parsed.messages["lanes[4]"].default_message,
                    "Route sud ouverte"
                );
                assert_eq!(
                    parsed.messages["lanes[5]"].default_message,
                    "Route vide ajoutée"
                );
                assert_eq!(
                    parsed.messages["lanes@product=tablet[0]"].default_message,
                    "Route tablette visible"
                );
                assert_eq!(
                    parsed.messages["lanes@product=tablet[1]"].default_message,
                    "Route tablette inverse"
                );
                assert_eq!(
                    parsed.messages["cargo[1]"].default_message,
                    "Cargaison mutable"
                );
                assert_eq!(
                    parsed.messages["cargo[3]"].default_message,
                    "Cargaison inverse"
                );
                assert_eq!(
                    parsed.messages["cargo[5]"].default_message,
                    "Dernière cargaison"
                );
                assert_eq!(
                    parsed.messages["bag.lanes[0]"].default_message,
                    "Sac mutable"
                );
                assert_eq!(
                    parsed.messages["bag.lanes[1]"].default_message,
                    "Sac ouvert"
                );
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["one"],
                    "{arg0} signal calme"
                );
                let metadata = parsed.messages["lanes[1]"].metadata.as_ref().unwrap();
                assert_eq!(
                    metadata["androidArrayFeatureFlags"],
                    serde_json::json!({
                        "0": "neutral.flags.visible",
                        "1": "neutral.flags.mutable",
                        "2": "!neutral.flags.mutable",
                        "3": "neutral.flags.visible",
                        "5": "neutral.flags.mutable"
                    })
                );
                assert_eq!(
                    metadata["androidArrayFeatureFlagModes"],
                    serde_json::json!({"1": "read_write", "2": "read_write", "5": "read_write"})
                );
                assert!(!parsed.messages.contains_key("private"));
            } else if id.contains("values-directory-locale") || id.contains("gated-directory") {
                let suffix = if id.contains("gated-directory") {
                    "@flag=neutral.flags.path"
                } else {
                    ""
                };
                assert_eq!(parsed.locale.as_deref(), Some("fr"));
                assert_eq!(
                    parsed.messages[&format!("anchor.route{suffix}")].default_message,
                    "Ancre française"
                );
                assert_eq!(
                    parsed.messages[&format!("route{suffix}")].default_message,
                    "Passage français"
                );
                assert_eq!(
                    parsed.messages[&format!("route@product=tablet{suffix}")].default_message,
                    "Passage tablette français"
                );
                assert_eq!(
                    parsed.messages[&format!("beacon{suffix}")].default_message,
                    "Balise française"
                );
                assert_eq!(
                    parsed.messages[&format!("lanes{suffix}[0]")].default_message,
                    "Nord <clair> & sûr"
                );
                assert_eq!(
                    parsed.messages[&format!("lanes{suffix}[2]")].default_message,
                    "Sud français"
                );
                assert_eq!(
                    parsed.messages[&format!("signals{suffix}")]
                        .variants
                        .as_ref()
                        .unwrap()["one"],
                    "{arg0} signal français"
                );
                assert!(!parsed.messages.contains_key(&format!("private{suffix}")));
            } else if id.contains("read-write-feature-flag-alternatives") {
                assert_eq!(parsed.messages["anchor"].default_message, "Ancre ouverte");
                assert_eq!(
                    parsed.messages["gate.route"].default_message,
                    "Passage ouvert"
                );
                assert_eq!(
                    parsed.messages["gate.route@flag=neutral.flags.first"].default_message,
                    "Premier passage mutable"
                );
                assert_eq!(
                    parsed.messages["gate.route@flag=!neutral.flags.first"].default_message,
                    "Inverse <calme> & sûr"
                );
                assert_eq!(
                    parsed.messages["gate.route@product=tablet@flag=neutral.flags.second"]
                        .default_message,
                    "Passage tablette mutable"
                );
                assert_eq!(
                    parsed.messages["gate.signal@flag=neutral.flags.first"].default_message,
                    "Signal premier mutable"
                );
                assert_eq!(
                    parsed.messages["gate.count@flag=neutral.flags.first"]
                        .variants
                        .as_ref()
                        .unwrap()["one"],
                    "{arg0} balise mutable"
                );
                assert_eq!(
                    parsed.messages["gate.lanes@flag=neutral.flags.second[0]"].default_message,
                    "Voie <nord> mutable"
                );
                assert!(!parsed.messages.contains_key("gate.disabled"));
            } else if id.starts_with("android-source-skeleton-preserves-default-and-product")
                || id.starts_with("android-source-skeleton-preserves-product-identity")
            {
                assert_eq!(parsed.messages["anchor"].default_message, "Ancre sûre");
                assert_eq!(
                    parsed.messages["harbor.route"].default_message,
                    "Passage commun sûr"
                );
                assert_eq!(
                    parsed.messages["harbor.route@product=tablet"].default_message,
                    "Passage tablette & calme"
                );
                assert_eq!(
                    parsed.messages["harbor.route@product=watch"].default_message,
                    "Montre <calme> & claire"
                );
                assert_eq!(
                    parsed.messages["harbor.signal@product=tablet"].default_message,
                    "Signal tablette & prêt"
                );
                assert_eq!(
                    parsed.messages["harbor.lanes@product=tablet[0]"].default_message,
                    "Tablette <nord> & sûre"
                );
                assert_eq!(
                    parsed.messages["harbor.lanes@product=tablet[2]"].default_message,
                    "Tablette sud douce"
                );
                assert_eq!(
                    parsed.messages["tide.count"].variants.as_ref().unwrap()["one"],
                    "{arg0} balise commune"
                );
                assert_eq!(
                    parsed.messages["tide.count@product=tablet"]
                        .variants
                        .as_ref()
                        .unwrap()["one"],
                    "{arg0} balise tablette"
                );
            } else if id.starts_with(
                "android-source-skeleton-preserves-validated-nontranslatable-resources",
            ) || id
                .starts_with("android-source-skeleton-preserves-transparent-protected-namespaces")
            {
                assert_eq!(
                    parsed.messages["visible"].default_message,
                    translations["visible"]
                );
                assert_eq!(parsed.messages.len(), 1);
            } else if id
                .starts_with("android-source-skeleton-preserves-namespaced-inline-attributes")
            {
                assert_eq!(
                    parsed.messages["scalar_space"].default_message,
                    "Nord ouest"
                );
                assert_eq!(
                    parsed.messages["scalar_default_space"].default_message,
                    "Nord est"
                );
                assert_eq!(
                    parsed.messages["routes[1]"].default_message,
                    "'<'font color=\"#112233\">Lumière du port'<'/font>"
                );
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["one"],
                    "'<'font size=\"9\">{arg0} balise du port'<'/font>"
                );
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["other"],
                    "'<'annotation key=\"beacon\">{arg0} balises du port'<'/annotation>"
                );
            } else if [
                "portable-xml-encoding-boundary",
                "portable-xml-long-declaration",
                "portable-xml-name-boundary",
                "portable-xml-legacy-name",
            ]
            .iter()
            .any(|boundary| id.contains(boundary))
            {
                assert_eq!(parsed.messages["signal"].default_message, "Marée calme");
            } else if id.contains("portable-android-intrinsic-xml-namespace") {
                assert_eq!(parsed.messages["signal"].default_message, "Marée calme");
                assert!(!parsed.messages.contains_key("hidden"));
            } else if id.contains("portable-android-bomless-utf16") {
                assert_eq!(parsed.messages["signal"].default_message, "Côte sûre");
                assert_eq!(parsed.messages["route"].default_message, "Rive 🚢 calme");
            } else if id.starts_with("android-source-skeleton-preserves-native-unicode-whitespace")
            {
                assert_eq!(
                    parsed.messages["entity_em_space"].default_message,
                    "sud\u{2003} ouest"
                );
                assert_eq!(
                    parsed.messages["escaped_no_break"].default_message,
                    "sud\u{00a0} ouest"
                );
                assert_eq!(
                    parsed.messages["boundary_paragraph_separator"].default_message,
                    "quai & \u{2029} ouest \u{2029}"
                );
                assert_eq!(
                    parsed.messages["quoted_unicode"].default_message,
                    "  \u{2003}   ouest \u{00a0}  "
                );
            } else if id.starts_with("android-source-skeleton-preserves-processing-instructions-") {
                assert_eq!(parsed.messages["plain"].default_message, "Quiet marina");
                assert_eq!(parsed.messages["mixed"].default_message, "Clear inlet");
                assert_eq!(parsed.messages["quoted"].default_message, "South  bay");
                assert_eq!(parsed.messages["escaped"].default_message, "Beacon");
                assert_eq!(parsed.messages["pilot"].default_message, "Welcome {pilot}.");
                assert_eq!(parsed.messages["routes[0]"].default_message, "Inner quay");
                assert_eq!(parsed.messages["routes[1]"].default_message, "Outer quay");
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["one"],
                    "{arg0} beacon"
                );
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["other"],
                    "{arg0} beacons"
                );
            } else if id
                .starts_with("android-source-skeleton-preserves-well-formed-document-envelope-")
            {
                assert_eq!(parsed.messages["route"].default_message, "Quai nord");
                assert_eq!(parsed.messages["bay"].default_message, "Havre sud");
            } else if id
                .starts_with("android-source-skeleton-preserves-safe-xml11-character-boundary-")
            {
                assert_eq!(parsed.messages["signal"].default_message, "Havre nord");
            } else if id.starts_with("android-source-skeleton-preserves-empty-elements") {
                assert_eq!(
                    parsed.messages["empty_scalar"].default_message,
                    "Nouveau & quai"
                );
                assert_eq!(
                    parsed.messages["empty_generic"].default_message,
                    "Voie douce & claire"
                );
                assert_eq!(parsed.messages["commented"].default_message, "Sud abri");
                assert_eq!(
                    parsed.messages["cdata"].default_message,
                    "Port ]]> <doux> & sûr"
                );
                assert_eq!(
                    parsed.messages["cdata_split"].default_message,
                    "Sud <doux> & sûr quai"
                );
                assert_eq!(
                    parsed.messages["routes[0]"].default_message,
                    "Première route"
                );
                assert_eq!(parsed.messages["routes[1]"].default_message, "Ouest calme");
                assert_eq!(
                    parsed.messages["routes[2]"].default_message,
                    "Signal <bleu> & doux"
                );
                assert_eq!(
                    parsed.messages["visits"].variants.as_ref().unwrap()["one"],
                    "{arg0} visite"
                );
                assert_eq!(
                    parsed.messages["visits"].variants.as_ref().unwrap()["other"],
                    "{arg0} visites"
                );
            } else if id
                == "android-source-skeleton-preserves-entity-escaped-unicode-resource-identities"
            {
                assert_eq!(parsed.messages["_route"].default_message, "Canal discret");
                assert_eq!(parsed.messages["Éclat"].default_message, "Lumière côtière");
                assert_eq!(
                    parsed.messages["e\u{0301}cho"].default_message,
                    "Écho du port"
                );
                assert_eq!(
                    parsed.messages["route·north"].default_message,
                    "Route du nord"
                );
                assert_eq!(
                    parsed.messages["generic·route"].default_message,
                    "Voie générique"
                );
                assert_eq!(
                    parsed.messages["渡り.route-2"].default_message,
                    "Passage côtier"
                );
            } else if id
                == "android-source-skeleton-preserves-whitespace-padded-plural-quantity-entities"
            {
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["one"],
                    "Signal visible"
                );
                assert_eq!(
                    parsed.messages["signals"].variants.as_ref().unwrap()["other"],
                    "Signaux visibles"
                );
            } else {
                assert_eq!(parsed.messages["harbor"].default_message, "Quai & abri sûr");
                assert_eq!(parsed.messages["routes[2]"].default_message, "Quai ouest");
                assert_eq!(
                    parsed.messages["visits"].variants.as_ref().unwrap()["other"],
                    "{arg0} visites"
                );
            }
        } else if format == FileFormat::AppleStringsdict {
            if case["appleStringsdictHiddenArgumentSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["harbor.after"].variants.as_ref().unwrap()["one"],
                    "{count} {arg2} balise"
                );
                assert_eq!(
                    parsed.messages["harbor.repeated"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{count} {arg3} balises"
                );
                assert_eq!(
                    parsed.messages["harbor.escaped"].variants.as_ref().unwrap()["one"],
                    "{count}%n {arg1} balise"
                );
                assert_eq!(
                    parsed.messages["harbor.after"].metadata.as_ref().unwrap()
                        ["applePluralDisabledPrintfConversions"]["count"]["one"][0]
                        ["argumentPosition"],
                    2
                );
            } else if case["appleWidthHiddenArgumentSlots"].as_bool() == Some(true) {
                let standalone = &parsed.messages["width.after🧭"];
                assert_eq!(standalone.default_message, " {arg1} large repère");
                assert_eq!(
                    standalone.metadata.as_ref().unwrap()["defaultWidthKey"],
                    "040"
                );
                assert_eq!(
                    standalone.metadata.as_ref().unwrap()["appleDisabledPrintfConversions"][0]
                        ["argumentPosition"],
                    1
                );
                assert_eq!(
                    parsed.messages["device.middle"].default_message,
                    "{arg0}  {arg2} mobile large repère"
                );
                assert_eq!(
                    parsed.messages["width.escaped"].default_message,
                    "%n {arg0} large repère"
                );
                let mac = &parsed.messages["device.after🧭"].metadata.as_ref().unwrap()
                    ["deviceWidthVariants"]["mac"]["NSStringVariableWidthRuleType"];
                assert_eq!(mac["5"], "%n %@ bureau proche repère");
                assert_eq!(mac["040"], "%n %@ bureau large repère");
            } else if case["appleDeviceWidthSlots"].as_bool() == Some(true)
                && case["appleDevicePluralSlots"].as_bool() == Some(true)
            {
                let scalar_plural = &parsed.messages["neutral.scalar-plural🧭"];
                assert_eq!(scalar_plural.default_message, "Touchez & la rive");
                let desktop = &scalar_plural.metadata.as_ref().unwrap()["deviceMixedVariants"]
                    ["mac"]["lights"];
                assert_eq!(desktop["one"], "%lld balise bureau");
                assert_eq!(desktop["other"], "%lld balises bureau");
                let plural_scalar = &parsed.messages["neutral.plural-scalar🧭"];
                assert_eq!(
                    plural_scalar.variants.as_ref().unwrap()["one"],
                    "{lights} lampe mobile"
                );
                assert_eq!(
                    plural_scalar.metadata.as_ref().unwrap()["deviceMixedVariants"]["mac"],
                    "Cliquez sur la jetée"
                );
                let scalar_width = &parsed.messages["neutral.scalar-width🧭"];
                let desktop_widths = &scalar_width.metadata.as_ref().unwrap()
                    ["deviceMixedVariants"]["mac"]["NSStringVariableWidthRuleType"];
                assert_eq!(desktop_widths["5"], "Sud%n doux");
                assert_eq!(desktop_widths["040"], "Sud\nouest calme");
                let width_scalar = &parsed.messages["neutral.width-scalar🧭"];
                assert_eq!(width_scalar.default_message, "Nord%n vaste rive");
                assert_eq!(
                    width_scalar.metadata.as_ref().unwrap()["deviceMixedVariants"]["mac"],
                    "Cliquez sur le port"
                );
                let plural_width = &parsed.messages["neutral.plural-width🧭"];
                assert_eq!(
                    plural_width.variants.as_ref().unwrap()["one"],
                    "{lights} signal mobile"
                );
                let west = &plural_width.metadata.as_ref().unwrap()["deviceMixedVariants"]["mac"]
                    ["NSStringVariableWidthRuleType"];
                assert_eq!(west["5"], "Nord%n quai");
                assert_eq!(west["040"], "Sud\nrive ouverte");
                let width_plural = &parsed.messages["neutral.width-plural🧭"];
                assert_eq!(width_plural.default_message, "Nord%n baie vaste");
                let desktop = &width_plural.metadata.as_ref().unwrap()["deviceMixedVariants"]
                    ["mac"]["lights"];
                assert_eq!(desktop["one"], "%lld bouée bureau");
                assert_eq!(desktop["other"], "%lld bouées bureau");
                assert_eq!(
                    parsed.messages["neutral.three-shapes🧭"]
                        .metadata
                        .as_ref()
                        .unwrap()["deviceMixedVariants"]["other"],
                    "Port de repli"
                );
            } else if case["appleDeviceHiddenArgumentSlots"].as_bool() == Some(true) {
                let message = &parsed.messages["device.after🧭"];
                assert_eq!(
                    message.variants.as_ref().unwrap()["one"],
                    "{count} {arg2} mobile repère"
                );
                assert_eq!(
                    parsed.messages["device.repeated"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{count} {arg3} mobile repères"
                );
                assert_eq!(
                    parsed.messages["device.escaped"].variants.as_ref().unwrap()["one"],
                    "{count}%n {arg1} mobile repère"
                );
                assert_eq!(
                    message.metadata.as_ref().unwrap()["devicePluralVariants"]["mac"]["count"]
                        ["one"],
                    "%lld%n %@ bureau repère"
                );
                assert_eq!(
                    message.metadata.as_ref().unwrap()["applePluralDisabledPrintfConversions"]
                        ["count"]["one"][0]["argumentPosition"],
                    2
                );
            } else if case["appleDeviceWidthSlots"].as_bool() == Some(true) {
                let message = &parsed.messages["neutral.width🧭"];
                assert_eq!(message.default_message, "Sud%n vaste rive");
                assert_eq!(message.metadata.as_ref().unwrap()["defaultWidthKey"], "040");
                let mac = &message.metadata.as_ref().unwrap()["deviceWidthVariants"]["mac"]
                    ["NSStringVariableWidthRuleType"];
                assert_eq!(mac["5"], "Sud%n doux");
                assert_eq!(mac["040"], "Sud\nouest calme");
            } else if case["appleDevicePluralSlots"].as_bool() == Some(true) {
                let message = &parsed.messages["neutral.harbor🧭"];
                assert_eq!(
                    message.variants.as_ref().unwrap()["one"],
                    "{lights} lanterne mobile"
                );
                assert_eq!(
                    message.variants.as_ref().unwrap()["other"],
                    "{lights} lanternes mobiles"
                );
                let mac =
                    &message.metadata.as_ref().unwrap()["devicePluralVariants"]["mac"]["lights"];
                assert_eq!(mac["one"], "%lld balise bureau");
                assert_eq!(mac["other"], "%lld balises bureau");
            } else if case["appleAllVariationSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["harbor.device.🧭"].default_message,
                    "Touchez {arg0} quai tranquille"
                );
                assert_eq!(
                    parsed.messages["harbor.device.literal"].default_message,
                    "Touchez {arg0}%n quai"
                );
                assert_eq!(
                    parsed.messages["harbor.width"].default_message,
                    "Suivez la vaste rive claire"
                );
                assert_eq!(
                    parsed.messages["harbor.width.line"].default_message,
                    "Eau\ncalme devant"
                );
                assert_eq!(
                    parsed.messages["harbor.width.literal"].default_message,
                    "B%n baie calme"
                );
                assert_eq!(
                    parsed.messages["harbor.device.🧭"]
                        .metadata
                        .as_ref()
                        .unwrap()["deviceVariants"]["mac"],
                    "Cliquez %@%n quai profond"
                );
                assert_eq!(
                    parsed.messages["harbor.width"].metadata.as_ref().unwrap()["widthVariants"]
                        ["040"],
                    "Suivez%n%n la rive calme"
                );
            } else if id.contains("device-and-width-owned-disabled-printf") {
                assert_eq!(
                    parsed.messages["harbor.device.🧭"].default_message,
                    "Touchez {arg0} quai tranquille"
                );
                assert_eq!(
                    parsed.messages["harbor.device.literal"].default_message,
                    "Touchez {arg0}%n quai"
                );
                assert_eq!(
                    parsed.messages["harbor.width"].default_message,
                    "Suivez la vaste rive claire"
                );
                assert_eq!(
                    parsed.messages["harbor.width.line"].default_message,
                    "Eau\ncalme devant"
                );
                assert_eq!(
                    parsed.messages["harbor.width.literal"].default_message,
                    "A%n baie calme"
                );
            } else if id.contains("disabled-printf") {
                assert_eq!(
                    parsed.messages["harbor.after"].variants.as_ref().unwrap()["one"],
                    "{count} balise"
                );
                assert_eq!(
                    parsed.messages["harbor.repeated"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{count} balises"
                );
                assert_eq!(
                    parsed.messages["harbor.literal"].variants.as_ref().unwrap()["other"],
                    "{count}%n balises"
                );
                assert_eq!(
                    parsed.messages["harbor.mixed.🧭"]
                        .variants
                        .as_ref()
                        .unwrap()["other"],
                    "{count}\nbalises"
                );
            } else if id.contains("character-reference") {
                let message = &parsed.messages["dock.entities"];
                assert_eq!(
                    message.variants.as_ref().unwrap()["one"],
                    "{signals} balise claire"
                );
                assert_eq!(
                    message.variants.as_ref().unwrap()["other"],
                    "{signals} balises claires"
                );
                let metadata = message.metadata.as_ref().unwrap();
                assert_eq!(
                    metadata["applePlistExtras"]["futureLiteral"],
                    "Protected &#00000000065;"
                );
                assert_eq!(
                    metadata["applePlistExtras"]["futureFlags"],
                    serde_json::json!([true, false])
                );
                assert_eq!(
                    metadata["applePlistExtras"]["futureRatio"]["bits"],
                    "3ff8000000000000"
                );
                assert_eq!(
                    metadata["applePluralRules"]["signals"]["applePlistExtras"]["futureRuleRatio"]
                        ["bits"],
                    "3ff4000000000000"
                );
            } else if id.contains("namespaces") {
                let message = &parsed.messages["dock.namespace"];
                assert_eq!(
                    message.variants.as_ref().unwrap()["one"],
                    "{signals} balise calme"
                );
                assert_eq!(
                    message.variants.as_ref().unwrap()["other"],
                    "{signals} balises calmes"
                );
                let metadata = message.metadata.as_ref().unwrap();
                assert_eq!(metadata["applePlistExtras"]["futurePriority"], 7);
                assert_eq!(
                    metadata["applePlistExtras"]["futureFlags"],
                    serde_json::json!([true, false, "Protected & stable"])
                );
                assert_eq!(
                    metadata["applePluralRules"]["signals"]["applePlistExtras"]
                        ["futureRulePriority"],
                    11
                );
            } else if id.contains("empty-typed") {
                assert_eq!(
                    parsed.messages["dock.empty"].variants.as_ref().unwrap()["one"],
                    "{signals} balise visible"
                );
                assert_eq!(
                    parsed.messages["dock.empty"].variants.as_ref().unwrap()["other"],
                    "{signals} balises visibles"
                );
                let extras =
                    &parsed.messages["dock.empty"].metadata.as_ref().unwrap()["applePlistExtras"];
                assert_eq!(extras["futureEmptyData"]["base64"], "");
                assert_eq!(extras["futureWhitespaceData"]["base64"], "");
                assert_eq!(extras["futurePayload"]["base64"], "YQ==");
                assert_eq!(
                    extras["futureLiteralMarker"],
                    "Literal <data/> remains visible"
                );
                assert_eq!(extras["futureEmptyArray"], serde_json::json!([]));
                assert_eq!(extras["futureEmptyDictionary"], serde_json::json!({}));
                assert_eq!(extras["futureTrue"], true);
                assert_eq!(extras["futureFalse"], false);
            } else if id.contains("strict-scalar") {
                assert_eq!(
                    parsed.messages["dock&signals"].variants.as_ref().unwrap()["one"],
                    "{signals} balise claire"
                );
                assert_eq!(
                    parsed.messages["dock&signals"].variants.as_ref().unwrap()["other"],
                    "{signals} balises claires"
                );
                assert_eq!(
                    parsed.messages["dock&signals"].metadata.as_ref().unwrap()["applePlistExtras"]
                        ["futureFlags"],
                    serde_json::json!([true, false, "Protected <north> & steady", 7])
                );
                assert_eq!(
                    parsed.messages["dock&signals"].metadata.as_ref().unwrap()["applePlistExtras"]
                        ["futureRatio"]["bits"],
                    "3ff8000000000000"
                );
            } else if id.contains("processing-instructions") {
                assert_eq!(
                    parsed.messages["dock.signals"].variants.as_ref().unwrap()["one"],
                    "{signals} balise sûre"
                );
                assert_eq!(
                    parsed.messages["dock.signals"].variants.as_ref().unwrap()["other"],
                    "{signals} balises sûres"
                );
                assert_eq!(
                    parsed.messages["dock.signals"].metadata.as_ref().unwrap()["applePlistExtras"]
                        ["futureRulePriority"],
                    7
                );
            } else {
                assert_eq!(
                    parsed.messages["dock&count🧭"].variants.as_ref().unwrap()["zero"],
                    "Aucun signal & sûr"
                );
                assert_eq!(
                    parsed.messages["dock&count🧭"].variants.as_ref().unwrap()["one"],
                    "{signals} lueur ]]> & sûre"
                );
                assert_eq!(
                    parsed.messages["dock&count🧭"].variants.as_ref().unwrap()["other"],
                    "{signals} signaux & clairs"
                );
                assert_eq!(
                    parsed.messages["solo.count"].variants.as_ref().unwrap()["one"],
                    "{lights} lueur"
                );
                assert_eq!(
                    parsed.messages["solo.count"].variants.as_ref().unwrap()["other"],
                    "{lights} lueurs"
                );
                assert_eq!(
                    parsed.messages["paired.route"].default_message,
                    "{beacons, plural, one {{beacons} balise sûre} other {{beacons} balises sûres}} \
                     across {lanes, plural, one {{lanes} voie claire} other {{lanes} voies claires}}"
                );
                assert_eq!(
                    parsed.messages["width.route"].default_message,
                    "Large {arg0} & calme"
                );
                assert_eq!(
                    parsed.messages["device.route"].default_message,
                    "Touchez {arg0} & continuez"
                );
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsTargetSubstitutionSlots"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("ru"));
            let scalar = &parsed.messages["harbor.target.substitution.scalar🧭"];
            let scalar_target =
                &scalar.metadata.as_ref().unwrap()["appleLocalizationSources"]["ru"];
            assert_eq!(
                scalar_target["stringUnit"]["value"],
                "Маршрут %3$@: %2$#@lights@ впереди %#@lanes@"
            );
            assert_eq!(scalar_target["stringUnit"]["state"], "future_review");
            let lanes = &scalar_target["substitutions"]["lanes"];
            assert_eq!(lanes["argNum"], 1);
            assert_eq!(lanes["formatSpecifier"], "lld");
            let categories = lanes["variations"]["plural"].as_object().unwrap();
            let actual: std::collections::HashSet<_> =
                categories.keys().map(String::as_str).collect();
            assert_eq!(
                actual,
                std::collections::HashSet::from(["one", "few", "many", "other"])
            );
            assert_eq!(
                categories["few"]["stringUnit"]["value"],
                "%1$lld %4$n обновлённый полосы"
            );
            assert_eq!(categories["few"]["stringUnit"]["state"], "new");

            let device = &parsed.messages["harbor.target.substitution.device🧭"];
            let target = &device.metadata.as_ref().unwrap()["appleLocalizationSources"]["ru"];
            assert_eq!(
                target["variations"]["device"]["mac"]["stringUnit"]["value"],
                "Рабочий стол %3$@: %#@lanes@ после %2$#@lights@"
            );
            assert_eq!(
                target["variations"]["device"]["mac"]["stringUnit"]["state"],
                "future_review"
            );
            assert_eq!(
                target["substitutions"]["lights"]["variations"]["plural"]["many"]["stringUnit"]
                    ["value"],
                "%2$d %4$n обновлённый огней"
            );
            assert_eq!(
                target["substitutions"]["lights"]["variations"]["plural"]["many"]["stringUnit"]
                    ["state"],
                "future_review"
            );
            assert!(!parsed
                .messages
                .contains_key("Private target Russian substitution branches"));
            if case["xcstringsSourceAliasTargetSubstitutions"].is_string() {
                let source: Value = serde_json::from_str(&skeleton.source).unwrap();
                let declared = source["sourceLanguage"].as_str().unwrap();
                let owned = scalar.metadata.as_ref().unwrap()["appleSourceLocalizationIdentifier"]
                    .as_str()
                    .unwrap();
                assert_ne!(declared, owned);
                assert_eq!(
                    device.metadata.as_ref().unwrap()["appleSourceLocalizationIdentifier"],
                    owned
                );
                assert!(scalar.metadata.as_ref().unwrap()["localizations"]
                    .get(owned)
                    .is_none());
                let normalized: Value =
                    serde_json::from_str(&write(FileFormat::AppleXcstrings, &parsed).unwrap())
                        .unwrap();
                assert!(normalized["strings"]["harbor.target.substitution.device🧭"]
                    ["localizations"]
                    .get(owned)
                    .is_some());
                assert_eq!(
                    mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                        &original, owned
                    )
                    .unwrap_err()
                    .code,
                    "INVALID_XCSTRINGS_LOCALE",
                    "{id}: compiler-equivalent substitution source was accepted as a target"
                );
            }

            let mut missing_other: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_other["strings"]["harbor.target.substitution.scalar🧭"]["localizations"]["ru"]
                ["substitutions"]["lanes"]["variations"]["plural"]
                .as_object_mut()
                .unwrap()
                .remove("other");
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_other).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "MISSING_OTHER_VARIANT",
                "{id}: target substitution accepted a missing other category"
            );

            let mut invented_category: Value = serde_json::from_str(&skeleton.source).unwrap();
            let categories = invented_category["strings"]["harbor.target.substitution.scalar🧭"]
                ["localizations"]["ru"]["substitutions"]["lanes"]["variations"]["plural"]
                .as_object_mut()
                .unwrap();
            let few = categories.remove("few").unwrap();
            categories.insert("several".to_owned(), few);
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&invented_category)
                        .unwrap()
                        .as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "INVALID_PLURAL_CATEGORY",
                "{id}: target substitution accepted an invented category"
            );

            let mut missing_selector: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_selector["strings"]["harbor.target.substitution.scalar🧭"]["localizations"]
                ["ru"]["substitutions"]
                .as_object_mut()
                .unwrap()
                .remove("lights");
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_selector).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: target substitution accepted a missing selector"
            );

            let mut missing_device: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_device["strings"]["harbor.target.substitution.device🧭"]["localizations"]["ru"]
                ["variations"]["device"]
                .as_object_mut()
                .unwrap()
                .remove("mac");
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_device).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: target substitution accepted a missing source-owned device"
            );

            let mut missing_target: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_target["strings"]["harbor.target.substitution.scalar🧭"]["localizations"]
                ["ru"] = Value::Null;
            assert!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_target).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap()
                .slots
                .iter()
                .any(|slot| {
                    slot.id == "harbor.target.substitution.scalar🧭"
                        && slot.selector.is_none()
                        && slot.variant.is_none()
                }),
                "{id}: missing target substitution did not receive atomic ownership"
            );

            let mut missing_evidence: Value = serde_json::from_str(&skeleton.source).unwrap();
            for existing in [
                "harbor.target.substitution.scalar🧭",
                "harbor.target.substitution.device🧭",
            ] {
                missing_evidence["strings"][existing]["localizations"]["ru"] = Value::Null;
            }
            assert!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_evidence).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap()
                .slots
                .iter()
                .any(|slot| slot.id == "harbor.target.substitution.scalar🧭"),
                "{id}: ICU first-locale substitution categories were unavailable"
            );
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_evidence).unwrap().as_bytes(),
                    "zz"
                )
                .unwrap_err()
                .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: unsupported first-locale substitution categories were invented"
            );

            for invalid_root in ["Маршрут {arg2}: {lights}", "{lanes} {lanes} {lights}"] {
                let translations = BTreeMap::from([(
                    "harbor.target.substitution.scalar🧭".to_owned(),
                    invalid_root.to_owned(),
                )]);
                assert_eq!(
                    render_skeleton(&skeleton, &translations).unwrap_err().code,
                    "INVALID_SKELETON_SUBSTITUTION",
                    "{id}: target substitution accepted missing or duplicated root markers"
                );
            }

            let mut forged = skeleton.clone();
            forged.slots = vec![skeleton
                .slots
                .iter()
                .find(|slot| slot.selector.as_deref() == Some("lanes"))
                .unwrap()
                .clone()];
            forged.slots[0].selector = Some("invented".to_owned());
            let forged_translations = BTreeMap::from([(
                format!(
                    "{}#invented#{}",
                    forged.slots[0].id,
                    forged.slots[0].variant.as_ref().unwrap()
                ),
                "{lanes} forged".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&forged, &forged_translations)
                    .unwrap_err()
                    .code,
                "INVALID_SKELETON",
                "{id}: target substitution selector ownership was forged"
            );

            if encoding.is_none() {
                let protected = skeleton
                    .source
                    .find("\"Private target Russian substitution branches\"")
                    .unwrap();
                let target = protected + skeleton.source[protected..].find("\"ru\"").unwrap();
                let beginning = target
                    + skeleton.source[target..].find("\"value\": \"").unwrap()
                    + "\"value\": \"".len();
                let end = beginning + skeleton.source[beginning..].find('"').unwrap();
                let mut forged = skeleton.clone();
                forged.slots = vec![mojito_file_formats::SourceSlot {
                    id: "harbor.target.substitution.scalar🧭".to_owned(),
                    selector: Some("lanes".to_owned()),
                    variant: Some("one".to_owned()),
                    start: beginning,
                    end,
                    apple_object_index: None,
                }];
                let translations = BTreeMap::from([(
                    "harbor.target.substitution.scalar🧭#lanes#one".to_owned(),
                    "{lanes} forged".to_owned(),
                )]);
                assert_eq!(
                    render_skeleton(&forged, &translations).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: protected target substitution ownership was forged"
                );
            }

            if case["xcstringsTargetSubstitutionInsertion"].as_bool() == Some(true) {
                for inserted_id in [
                    "harbor.target.substitution.missing.scalar🧭",
                    "harbor.target.substitution.null.scalar🧭",
                    "harbor.target.substitution.missing.device🧭",
                    "harbor.target.substitution.null.device🧭",
                ] {
                    let inserted = &parsed.messages[inserted_id];
                    if case["xcstringsSourceAliasAtomicSubstitutions"].is_string() {
                        let original_catalog: Value =
                            serde_json::from_str(&skeleton.source).unwrap();
                        let localized_catalog: Value = serde_json::from_slice(
                            &fs::read(root.join(case["localized"].as_str().unwrap())).unwrap(),
                        )
                        .unwrap();
                        let declared = original_catalog["sourceLanguage"].as_str().unwrap();
                        let owned = inserted.metadata.as_ref().unwrap()
                            ["appleSourceLocalizationIdentifier"]
                            .as_str()
                            .unwrap();
                        assert_ne!(declared, owned);
                        assert_eq!(
                            original_catalog["strings"][inserted_id]["localizations"][owned],
                            localized_catalog["strings"][inserted_id]["localizations"][owned]
                        );
                        if inserted_id.contains(".null.") {
                            assert!(original_catalog["strings"][inserted_id]["localizations"]
                                ["ru"]
                                .is_null());
                        } else {
                            assert!(original_catalog["strings"][inserted_id]["localizations"]
                                .get("ru")
                                .is_none());
                        }
                    }
                    let target =
                        &inserted.metadata.as_ref().unwrap()["appleLocalizationSources"]["ru"];
                    let selectors = target["substitutions"].as_object().unwrap();
                    let actual: std::collections::HashSet<_> =
                        selectors.keys().map(String::as_str).collect();
                    assert_eq!(actual, std::collections::HashSet::from(["lanes", "lights"]));
                    for definition in selectors.values() {
                        let categories = definition["variations"]["plural"].as_object().unwrap();
                        let actual: std::collections::HashSet<_> =
                            categories.keys().map(String::as_str).collect();
                        assert_eq!(
                            actual,
                            std::collections::HashSet::from(["one", "few", "many", "other"])
                        );
                        assert!(categories
                            .values()
                            .all(|branch| branch["stringUnit"]["state"] == "translated"));
                        assert!(categories
                            .values()
                            .all(|branch| branch["stringUnit"]["value"]
                                .as_str()
                                .unwrap()
                                .contains("%4$n")));
                    }
                    if inserted_id.contains(".device") {
                        let devices = target["variations"]["device"].as_object().unwrap();
                        let actual: std::collections::HashSet<_> =
                            devices.keys().map(String::as_str).collect();
                        assert_eq!(actual, std::collections::HashSet::from(["iphone", "mac"]));
                        assert!(devices
                            .values()
                            .all(|branch| branch["stringUnit"]["state"] == "translated"));
                    } else {
                        assert_eq!(target["stringUnit"]["state"], "translated");
                    }
                }
                assert!(!parsed
                    .messages
                    .contains_key("Private missing Russian substitution tree"));
                assert!(!parsed
                    .messages
                    .contains_key("Private null Russian substitution tree"));

                let scalar_id = "harbor.target.substitution.missing.scalar🧭";
                let device_id = "harbor.target.substitution.missing.device🧭";
                for (value, expected) in [
                    (
                        "Добавлен {arg2}: {lanes}".to_owned(),
                        "INVALID_SKELETON_SUBSTITUTION",
                    ),
                    (
                        translations[scalar_id].replace("few {", "zero {"),
                        "INVALID_SKELETON",
                    ),
                    (
                        translations[scalar_id].replacen("{lights}  обновлённый", "{invented}", 1),
                        "INVALID_PLACEHOLDER",
                    ),
                    (
                        translations[scalar_id].replace("other {{lights}  обновлённый огня}", ""),
                        "MISSING_OTHER_VARIANT",
                    ),
                ] {
                    let rejected = BTreeMap::from([(scalar_id.to_owned(), value)]);
                    assert_eq!(
                        render_skeleton(&skeleton, &rejected).unwrap_err().code,
                        expected,
                        "{id}: malformed atomic target substitution was accepted"
                    );
                }
                for value in [
                    translations[device_id].replacen("обновлённый огонь", "иной огонь", 1),
                    translations[device_id].replace("other {Экран", "other {Другое"),
                    translations[device_id].replace("mac {", "watch {"),
                ] {
                    let rejected = BTreeMap::from([(device_id.to_owned(), value)]);
                    assert_eq!(
                        render_skeleton(&skeleton, &rejected).unwrap_err().code,
                        "INVALID_SKELETON",
                        "{id}: mismatched source-owned/shared device substitution was accepted"
                    );
                }

                if encoding.is_none() {
                    let protected = skeleton
                        .source
                        .find("\"Private null Russian substitution tree\"")
                        .unwrap();
                    let locale = protected + skeleton.source[protected..].find("\"ru\"").unwrap();
                    let start = locale + skeleton.source[locale..].find("null").unwrap();
                    let mut forged = skeleton.clone();
                    forged.slots = vec![mojito_file_formats::SourceSlot {
                        id: scalar_id.to_owned(),
                        selector: None,
                        variant: None,
                        start,
                        end: start + 4,
                        apple_object_index: None,
                    }];
                    let rejected =
                        BTreeMap::from([(scalar_id.to_owned(), translations[scalar_id].clone())]);
                    assert_eq!(
                        render_skeleton(&forged, &rejected).unwrap_err().code,
                        "INVALID_SKELETON",
                        "{id}: protected null substitution-tree ownership was forged"
                    );
                }
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsTargetDeviceSlots"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("ru"));
            let scalar = &parsed.messages["harbor.target.device.scalar🧭"];
            let scalar_devices = &scalar.metadata.as_ref().unwrap()["localizations"]["ru"]
                ["variationAxes"]["device"];
            assert_eq!(
                scalar_devices["iphone"]["stringUnit"]["value"],
                "На iPhone %1$@ %2$n у маяка"
            );
            assert_eq!(
                scalar_devices["mac"]["stringUnit"]["state"],
                "future_review"
            );

            let plural = &parsed.messages["harbor.target.device.plural🧭"];
            let categories = &plural.metadata.as_ref().unwrap()["localizations"]["ru"]
                ["variationAxes"]["device"]["mac"]["variations"]["plural"];
            let actual: std::collections::HashSet<_> = categories
                .as_object()
                .unwrap()
                .keys()
                .map(String::as_str)
                .collect();
            assert_eq!(
                actual,
                std::collections::HashSet::from(["one", "few", "many", "other"])
            );
            assert_eq!(
                categories["few"]["stringUnit"]["value"],
                "%2$@ %3$n %1$lld обновлённый настольный маяка"
            );
            assert_eq!(categories["few"]["stringUnit"]["state"], "new");
            assert!(!parsed
                .messages
                .contains_key("Private target Russian device branches"));

            let mut missing_other: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_other["strings"]["harbor.target.device.plural🧭"]["localizations"]["ru"]
                ["variations"]["device"]["mac"]["variations"]["plural"]
                .as_object_mut()
                .unwrap()
                .remove("other");
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_other).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "MISSING_OTHER_VARIANT",
                "{id}: target device plural accepted a missing other category"
            );

            let mut invented_category: Value = serde_json::from_str(&skeleton.source).unwrap();
            let categories = invented_category["strings"]["harbor.target.device.plural🧭"]
                ["localizations"]["ru"]["variations"]["device"]["mac"]["variations"]["plural"]
                .as_object_mut()
                .unwrap();
            let few = categories.remove("few").unwrap();
            categories.insert("several".to_owned(), few);
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&invented_category)
                        .unwrap()
                        .as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "INVALID_PLURAL_CATEGORY",
                "{id}: target device plural accepted an invented category"
            );

            let mut mismatched_device: Value = serde_json::from_str(&skeleton.source).unwrap();
            mismatched_device["strings"]["harbor.target.device.scalar🧭"]["localizations"]["ru"]
                ["variations"]["device"]["mac"] = serde_json::json!({
                "variations": {
                    "plural": {
                        "other": {
                            "stringUnit": {
                                "state": "translated",
                                "value": "%1$lld unexpected scalar category"
                            }
                        }
                    }
                }
            });
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&mismatched_device)
                        .unwrap()
                        .as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: mismatched target device scalar/plural ownership was accepted"
            );

            let mut missing_target: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_target["strings"]["harbor.target.device.scalar🧭"]["localizations"]["ru"] =
                Value::Null;
            assert!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_target).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap()
                .slots
                .iter()
                .any(|slot| {
                    slot.id == "harbor.target.device.scalar🧭"
                        && slot.selector.is_none()
                        && slot.variant.is_none()
                }),
                "{id}: missing target device tree did not receive atomic ownership"
            );

            let mut forged = skeleton.clone();
            forged.slots = vec![skeleton
                .slots
                .iter()
                .find(|slot| slot.selector.as_deref() == Some("@device=iphone"))
                .unwrap()
                .clone()];
            forged.slots[0].selector = Some("@device=watch".to_owned());
            let translations = BTreeMap::from([(
                "harbor.target.device.plural🧭#@device=watch#one".to_owned(),
                "Forged watch".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&forged, &translations).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: nonexistent target device ownership was forged"
            );

            if encoding.is_none() {
                let protected = skeleton
                    .source
                    .find("\"Private target Russian device branches\"")
                    .unwrap();
                let target = protected + skeleton.source[protected..].find("\"ru\"").unwrap();
                let beginning = target
                    + skeleton.source[target..].find("\"value\": \"").unwrap()
                    + "\"value\": \"".len();
                let end = beginning + skeleton.source[beginning..].find('"').unwrap();
                let mut forged = skeleton.clone();
                forged.slots = vec![mojito_file_formats::SourceSlot {
                    id: "harbor.target.device.plural🧭".to_owned(),
                    selector: Some("@device=iphone".to_owned()),
                    variant: Some("one".to_owned()),
                    start: beginning,
                    end,
                    apple_object_index: None,
                }];
                let translations = BTreeMap::from([(
                    "harbor.target.device.plural🧭#@device=iphone#one".to_owned(),
                    "Forged protected".to_owned(),
                )]);
                assert_eq!(
                    render_skeleton(&forged, &translations).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: protected target device category ownership was forged"
                );
            }

            if case["xcstringsTargetDeviceInsertion"].as_bool() == Some(true) {
                for inserted_id in [
                    "harbor.target.device.missing.scalar🧭",
                    "harbor.target.device.null.scalar🧭",
                    "harbor.target.device.missing.plural🧭",
                    "harbor.target.device.null.plural🧭",
                ] {
                    let devices = &parsed.messages[inserted_id].metadata.as_ref().unwrap()
                        ["localizations"]["ru"]["variationAxes"]["device"];
                    let names: std::collections::HashSet<_> = devices
                        .as_object()
                        .unwrap()
                        .keys()
                        .map(String::as_str)
                        .collect();
                    assert_eq!(names, std::collections::HashSet::from(["iphone", "mac"]));
                    for device in devices.as_object().unwrap().values() {
                        if let Some(unit) = device.get("stringUnit") {
                            assert_eq!(unit["state"], "translated");
                        } else {
                            let categories = device["variations"]["plural"].as_object().unwrap();
                            let actual: std::collections::HashSet<_> =
                                categories.keys().map(String::as_str).collect();
                            assert_eq!(
                                actual,
                                std::collections::HashSet::from(["one", "few", "many", "other"])
                            );
                            assert!(categories
                                .values()
                                .all(|category| category["stringUnit"]["state"] == "translated"));
                        }
                    }
                }
                assert!(!parsed
                    .messages
                    .contains_key("Private missing Russian device tree"));
                assert!(!parsed
                    .messages
                    .contains_key("Private null Russian device tree"));

                let missing_scalar = "harbor.target.device.missing.scalar🧭";
                let missing_plural = "harbor.target.device.missing.plural🧭";
                for (name, value, expected) in [
                    ("plain scalar", "not a select", "INVALID_SKELETON"),
                    (
                        "missing fallback",
                        "{device, select, iphone {{arg0}} mac {{arg0}}}",
                        "INVALID_SKELETON",
                    ),
                    (
                        "mismatched fallback",
                        "{device, select, iphone {{arg0}} mac {{arg0}} other {different {arg0}}}",
                        "INVALID_SKELETON",
                    ),
                    (
                        "unknown device",
                        "{device, select, iphone {{arg0}} mac {{arg0}} watch {{arg0}} other {{arg0}}}",
                        "INVALID_SKELETON",
                    ),
                    (
                        "unknown argument",
                        "{device, select, iphone {{unknown}} mac {{unknown}} other {{unknown}}}",
                        "INVALID_PLACEHOLDER",
                    ),
                ] {
                    let invalid = BTreeMap::from([(missing_scalar.to_owned(), value.to_owned())]);
                    assert_eq!(
                        render_skeleton(&skeleton, &invalid).unwrap_err().code,
                        expected,
                        "{id}: malformed target-device select accepted {name}"
                    );
                }

                let incomplete = "{device, select, iphone {{count, plural, one {{count}} \
                     other {{count}}}} mac {{count, plural, one {{count}} other {{count}}}} \
                     other {{count, plural, one {{count}} other {{count}}}}}";
                let invalid = BTreeMap::from([(missing_plural.to_owned(), incomplete.to_owned())]);
                assert_eq!(
                    render_skeleton(&skeleton, &invalid).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: missing Russian target-device plural categories were accepted"
                );

                let mut no_evidence: Value = serde_json::from_str(&skeleton.source).unwrap();
                no_evidence["strings"]["harbor.target.device.plural🧭"]["localizations"]["ru"] =
                    Value::Null;
                assert_eq!(
                    mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                        serde_json::to_string(&no_evidence).unwrap().as_bytes(),
                        "ru"
                    )
                    .unwrap_err()
                    .code,
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "{id}: target device plurals guessed categories without native evidence"
                );

                if encoding.is_none() {
                    let protected = skeleton
                        .source
                        .find("\"Private null Russian device tree\"")
                        .unwrap();
                    let target = protected
                        + skeleton.source[protected..].find("\"ru\": null").unwrap()
                        + "\"ru\": ".len();
                    let mut forged = skeleton.clone();
                    forged.slots = vec![mojito_file_formats::SourceSlot {
                        id: missing_plural.to_owned(),
                        selector: None,
                        variant: None,
                        start: target,
                        end: target + "null".len(),
                        apple_object_index: None,
                    }];
                    let translations: BTreeMap<String, String> = serde_json::from_slice(
                        &fs::read(root.join(case["translations"].as_str().unwrap())).unwrap(),
                    )
                    .unwrap();
                    let forged_translation = BTreeMap::from([(
                        missing_plural.to_owned(),
                        translations[missing_plural].clone(),
                    )]);
                    assert_eq!(
                        render_skeleton(&forged, &forged_translation)
                            .unwrap_err()
                            .code,
                        "INVALID_SKELETON",
                        "{id}: protected target-device null ownership was forged"
                    );
                }
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsFirstLocaleDevices"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("ru"));
            for first in [
                "harbor.first.device.missing.scalar🧭",
                "harbor.first.device.null.scalar🧭",
                "harbor.first.device.missing.plural🧭",
                "harbor.first.device.null.plural🧭",
            ] {
                let devices = &parsed.messages[first].metadata.as_ref().unwrap()["localizations"]
                    ["ru"]["variationAxes"]["device"];
                let actual: std::collections::HashSet<_> = devices
                    .as_object()
                    .unwrap()
                    .keys()
                    .map(String::as_str)
                    .collect();
                let future = case["xcstringsFirstLocaleFutureDevices"].as_bool() == Some(true);
                let expected = if future {
                    let mut devices = std::collections::HashSet::from([
                        "iphone",
                        "mac",
                        "futurecar",
                        "\u{e000}raft",
                        "🧭raft",
                    ]);
                    if !first.contains(".plural") {
                        devices.insert("other");
                    }
                    devices
                } else {
                    std::collections::HashSet::from(["iphone", "mac"])
                };
                assert_eq!(actual, expected);
                if future && !first.contains(".plural") {
                    assert!(devices["other"]["stringUnit"]["value"]
                        .as_str()
                        .unwrap()
                        .contains("На other"));
                }
                for device in devices.as_object().unwrap().values() {
                    if first.contains(".plural") {
                        let categories = device["variations"]["plural"].as_object().unwrap();
                        let actual: std::collections::HashSet<_> =
                            categories.keys().map(String::as_str).collect();
                        assert_eq!(
                            actual,
                            std::collections::HashSet::from(["one", "few", "many", "other"])
                        );
                        assert!(categories.values().all(|category| {
                            category["stringUnit"]["state"] == "translated"
                                && category["stringUnit"]["value"]
                                    .as_str()
                                    .unwrap()
                                    .contains("%3$n")
                        }));
                    } else {
                        assert_eq!(device["stringUnit"]["state"], "translated");
                        assert!(device["stringUnit"]["value"]
                            .as_str()
                            .unwrap()
                            .contains("%2$n"));
                    }
                }
            }
            assert!(!parsed
                .messages
                .contains_key("Private first missing Russian device"));
            assert!(!parsed
                .messages
                .contains_key("Private first null Russian device"));
            let scalar = "harbor.first.device.missing.scalar🧭";
            let plural = "harbor.first.device.missing.plural🧭";
            let divergent_fallback =
                if case["xcstringsFirstLocaleFutureDevices"].as_bool() == Some(true) {
                    (
                        plural,
                        translations[plural].replace("other {{count", "other {{arg1"),
                    )
                } else {
                    (
                        scalar,
                        translations[scalar].replace("other {На iphone", "other {Иной iphone"),
                    )
                };
            for (identifier, invalid) in [
                (scalar, translations[scalar].replace("mac {", "watch {")),
                divergent_fallback,
                (plural, translations[plural].replace("few {", "zero {")),
            ] {
                let rejected = BTreeMap::from([(identifier.to_owned(), invalid)]);
                assert_eq!(
                    render_skeleton(&skeleton, &rejected).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: unsafe first-locale device ownership was accepted"
                );
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsFirstLocaleSubstitutions"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("ru"));
            for first in [
                "harbor.first.substitution.missing.scalar🧭",
                "harbor.first.substitution.null.scalar🧭",
                "harbor.first.substitution.missing.device🧭",
                "harbor.first.substitution.null.device🧭",
            ] {
                if case["xcstringsSourceAliasFirstLocaleSubstitutions"].is_string() {
                    let source: Value = serde_json::from_str(&skeleton.source).unwrap();
                    let localized_catalog: Value = serde_json::from_slice(
                        &fs::read(root.join(case["localized"].as_str().unwrap())).unwrap(),
                    )
                    .unwrap();
                    let declared = source["sourceLanguage"].as_str().unwrap();
                    let owned = parsed.messages[first].metadata.as_ref().unwrap()
                        ["appleSourceLocalizationIdentifier"]
                        .as_str()
                        .unwrap();
                    assert_ne!(declared, owned);
                    assert_eq!(
                        source["strings"][first]["localizations"][owned],
                        localized_catalog["strings"][first]["localizations"][owned]
                    );
                    assert_eq!(
                        source["strings"][first]["localizations"]["de"],
                        localized_catalog["strings"][first]["localizations"]["de"]
                    );
                    if first.contains(".null.") {
                        assert!(source["strings"][first]["localizations"]["ru"].is_null());
                    } else {
                        assert!(source["strings"][first]["localizations"]
                            .get("ru")
                            .is_none());
                    }
                    assert_eq!(
                        mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                            &original, owned
                        )
                        .unwrap_err()
                        .code,
                        "INVALID_XCSTRINGS_LOCALE",
                        "{id}: first-locale development alias was accepted as its own target"
                    );
                }
                let target = &parsed.messages[first].metadata.as_ref().unwrap()
                    ["appleLocalizationSources"]["ru"];
                let selectors = target["substitutions"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    selectors.keys().map(String::as_str).collect();
                assert_eq!(actual, std::collections::HashSet::from(["lanes", "lights"]));
                for selector in selectors.values() {
                    let categories = selector["variations"]["plural"].as_object().unwrap();
                    let actual: std::collections::HashSet<_> =
                        categories.keys().map(String::as_str).collect();
                    assert_eq!(
                        actual,
                        std::collections::HashSet::from(["one", "few", "many", "other"])
                    );
                    assert!(categories.values().all(|category| {
                        category["stringUnit"]["state"] == "translated"
                            && !category["stringUnit"]["value"]
                                .as_str()
                                .unwrap()
                                .contains("%4$n")
                    }));
                }
                if first.contains(".device") {
                    let devices = target["variations"]["device"].as_object().unwrap();
                    let actual: std::collections::HashSet<_> =
                        devices.keys().map(String::as_str).collect();
                    assert_eq!(actual, std::collections::HashSet::from(["iphone", "mac"]));
                    assert!(devices
                        .values()
                        .all(|device| device["stringUnit"]["state"] == "translated"));
                } else {
                    assert_eq!(target["stringUnit"]["state"], "translated");
                }
            }
            assert!(!parsed
                .messages
                .contains_key("Private first missing Russian substitution"));
            assert!(!parsed
                .messages
                .contains_key("Private first null Russian substitution"));
            let scalar = "harbor.first.substitution.missing.scalar🧭";
            let device = "harbor.first.substitution.missing.device🧭";
            for invalid in [
                translations[scalar].replace("few {", "zero {"),
                translations[scalar].replace("other {{lights} первый огня}", ""),
                translations[scalar].replace("{lights} первый", "{invented} первый"),
            ] {
                let rejected = BTreeMap::from([(scalar.to_owned(), invalid)]);
                assert!(
                    matches!(
                        render_skeleton(&skeleton, &rejected).unwrap_err().code,
                        "INVALID_SKELETON" | "MISSING_OTHER_VARIANT" | "INVALID_PLACEHOLDER"
                    ),
                    "{id}: invalid first-locale substitution categories were accepted"
                );
            }
            let invented = BTreeMap::from([(
                device.to_owned(),
                translations[device].replace("mac {", "watch {"),
            )]);
            assert_eq!(
                render_skeleton(&skeleton, &invented).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: first-locale substitution invented a device"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsSourceLocaleAlias"].is_string()
        {
            let source: Value = serde_json::from_str(&skeleton.source).unwrap();
            let declared = source["sourceLanguage"].as_str().unwrap();
            let scalar = &parsed.messages["harbor.development.source.scalar🧭"];
            let plural = &parsed.messages["harbor.development.source.plural🧭"];
            let owned = scalar.metadata.as_ref().unwrap()["appleSourceLocalizationIdentifier"]
                .as_str()
                .unwrap();
            assert_ne!(declared, owned);
            assert_eq!(
                plural.metadata.as_ref().unwrap()["appleSourceLocalizationIdentifier"],
                owned
            );
            assert_eq!(scalar.default_message, "{arg1} {arg0} translated beacon");
            assert_eq!(
                plural.variants.as_ref().unwrap()["one"],
                "{count} translated beacon {arg1}"
            );
            assert_eq!(
                plural.variants.as_ref().unwrap()["other"],
                "{count} translated beacons {arg1}"
            );
            assert_eq!(skeleton.slots.len(), 3);
            assert!(scalar.metadata.as_ref().unwrap()["localizations"]
                .get(owned)
                .is_none());
            assert!(!parsed
                .messages
                .contains_key("Private development-source harbor"));
            let normalized: Value =
                serde_json::from_str(&write(FileFormat::AppleXcstrings, &parsed).unwrap()).unwrap();
            assert!(
                normalized["strings"]["harbor.development.source.scalar🧭"]["localizations"]
                    .get(owned)
                    .is_some()
            );
            assert!(
                normalized["strings"]["harbor.development.source.plural🧭"]["localizations"]
                    .get(owned)
                    .is_some()
            );
            if case["xcstringsSourceLocaleAlias"] != "region-separator" {
                assert_eq!(
                    mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                        &original, owned
                    )
                    .unwrap_err()
                    .code,
                    "INVALID_XCSTRINGS_LOCALE",
                    "{id}: compiler-equivalent development locale was accepted as a target"
                );
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsRegionSeparator"].is_string()
        {
            let separator = case["xcstringsRegionSeparator"].as_str().unwrap();
            let locale = if separator == "underscore" {
                "pt_BR"
            } else {
                "pt-BR"
            };
            let untouched = if separator == "underscore" {
                "pt-BR"
            } else {
                "pt_BR"
            };
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some(locale));
            let localizations = &parsed.messages["harbor.portuguese.region.separator🧭"]
                .metadata
                .as_ref()
                .unwrap()["localizations"];
            assert!(localizations.get("pt_BR").is_some());
            assert!(localizations.get("pt-BR").is_some());
            assert!(localizations[locale]["value"]
                .as_str()
                .unwrap()
                .contains("traduzido"));
            assert!(!localizations[untouched]["value"]
                .as_str()
                .unwrap()
                .contains("traduzido"));
            assert_eq!(localizations["de"]["state"], "needs_review");
            assert!(!parsed
                .messages
                .contains_key("Private independent Portuguese separator"));
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsDeprecatedLocale"].is_string()
        {
            let variation = case["xcstringsDeprecatedLocale"].as_str().unwrap();
            let locale = if variation == "language" {
                "iw"
            } else {
                "iw-IL"
            };
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some(locale));
            for hebrew in [
                "harbor.first.hebrew.deprecated🧭",
                "harbor.first.hebrew.deprecated.region🧭",
            ] {
                let target =
                    &parsed.messages[hebrew].metadata.as_ref().unwrap()["localizations"][locale];
                let categories = target["variants"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    categories.keys().map(String::as_str).collect();
                assert_eq!(
                    actual,
                    std::collections::HashSet::from(["one", "two", "other"])
                );
                assert!(categories
                    .values()
                    .all(|value| value.as_str().unwrap().contains("%3$n")));
                assert!(target["variantStates"]
                    .as_object()
                    .unwrap()
                    .values()
                    .all(|state| state == "translated"));
            }
            assert!(!parsed
                .messages
                .contains_key("Private deprecated Hebrew harbor"));
            let incomplete = BTreeMap::from([(
                "harbor.first.hebrew.deprecated🧭".to_owned(),
                "{count, plural, one {{arg1} {count}} other {{arg1} {count}}}".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&skeleton, &incomplete).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: Hebrew silently omitted its two category"
            );
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    &original, "kok-Latn"
                )
                .unwrap()
                .apple_target_locale
                .as_deref(),
                Some("kok-Latn"),
                "{id}: an ICU-supported script-qualified locale was rejected"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsTerritoryLocale"].is_string()
        {
            let variation = case["xcstringsTerritoryLocale"].as_str().unwrap();
            let locale = if variation == "british" {
                "en-UK"
            } else {
                "en-001"
            };
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some(locale));
            for territory in [
                "harbor.first.english.british.obsolete🧭",
                "harbor.first.english.world.numeric🧭",
            ] {
                let target =
                    &parsed.messages[territory].metadata.as_ref().unwrap()["localizations"][locale];
                let categories = target["variants"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    categories.keys().map(String::as_str).collect();
                assert_eq!(actual, std::collections::HashSet::from(["one", "other"]));
                assert!(categories
                    .values()
                    .all(|value| value.as_str().unwrap().contains("%3$n")));
                assert!(target["variantStates"]
                    .as_object()
                    .unwrap()
                    .values()
                    .all(|state| state == "translated"));
            }
            assert!(!parsed
                .messages
                .contains_key("Private English territory harbor"));
            let incomplete = BTreeMap::from([(
                "harbor.first.english.british.obsolete🧭".to_owned(),
                "{count, plural, one {{arg1} {count}}}".to_owned(),
            )]);
            assert!(
                matches!(
                    render_skeleton(&skeleton, &incomplete).unwrap_err().code,
                    "INVALID_SKELETON" | "MISSING_OTHER_VARIANT"
                ),
                "{id}: English territory silently omitted its other category"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsGrandfatheredLocale"].is_string()
        {
            let variation = case["xcstringsGrandfatheredLocale"].as_str().unwrap();
            let locale = if variation == "bokmal" {
                "no-bok"
            } else {
                "no-nyn"
            };
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some(locale));
            for norwegian in [
                "harbor.first.norwegian.bokmal.grandfathered🧭",
                "harbor.first.norwegian.nynorsk.grandfathered🧭",
            ] {
                let target =
                    &parsed.messages[norwegian].metadata.as_ref().unwrap()["localizations"][locale];
                let categories = target["variants"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    categories.keys().map(String::as_str).collect();
                assert_eq!(actual, std::collections::HashSet::from(["one", "other"]));
                assert!(categories
                    .values()
                    .all(|value| value.as_str().unwrap().contains("%3$n")));
                assert!(target["variantStates"]
                    .as_object()
                    .unwrap()
                    .values()
                    .all(|state| state == "translated"));
            }
            assert!(!parsed
                .messages
                .contains_key("Private Norwegian language harbor"));
            let incomplete = BTreeMap::from([(
                "harbor.first.norwegian.bokmal.grandfathered🧭".to_owned(),
                "{count, plural, one {{arg1} {count}}}".to_owned(),
            )]);
            assert!(
                matches!(
                    render_skeleton(&skeleton, &incomplete).unwrap_err().code,
                    "INVALID_SKELETON" | "MISSING_OTHER_VARIANT"
                ),
                "{id}: Norwegian silently omitted its other category"
            );
        } else if format == FileFormat::AppleXcstrings && case["xcstringsScriptLocale"].is_string()
        {
            let script = case["xcstringsScriptLocale"].as_str().unwrap();
            let locale = if script == "latin" {
                "sr_Latn"
            } else {
                "sr-Cyrl"
            };
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some(locale));
            let normalized = locale.replace('_', "-");
            for scripted in [
                "harbor.first.serbian.latin🧭",
                "harbor.first.serbian.cyrillic🧭",
            ] {
                let target = &parsed.messages[scripted].metadata.as_ref().unwrap()["localizations"]
                    [&normalized];
                let categories = target["variants"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    categories.keys().map(String::as_str).collect();
                assert_eq!(
                    actual,
                    std::collections::HashSet::from(["few", "one", "other"])
                );
                assert!(categories
                    .values()
                    .all(|value| value.as_str().unwrap().contains("%3$n")));
                assert!(target["variantStates"]
                    .as_object()
                    .unwrap()
                    .values()
                    .all(|state| state == "translated"));
            }
            assert!(!parsed
                .messages
                .contains_key("Private Serbian script harbor"));
            let incomplete = BTreeMap::from([(
                "harbor.first.serbian.latin🧭".to_owned(),
                "{count, plural, one {{arg1} {count}} other {{arg1} {count}}}".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&skeleton, &incomplete).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: Serbian script silently omitted its few category"
            );
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    &original, "kok-Latn"
                )
                .unwrap()
                .apple_target_locale
                .as_deref(),
                Some("kok-Latn"),
                "{id}: an ICU-supported script-qualified locale was rejected"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsRegionalLocale"].is_string()
        {
            let region = case["xcstringsRegionalLocale"].as_str().unwrap();
            let locale = if region == "brazil" { "pt_BR" } else { "pt-PT" };
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some(locale));
            let normalized = locale.replace('_', "-");
            for regional in [
                "harbor.first.brazilian.portuguese🧭",
                "harbor.first.european.portuguese🧭",
            ] {
                let target = &parsed.messages[regional].metadata.as_ref().unwrap()["localizations"]
                    [&normalized];
                let categories = target["variants"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    categories.keys().map(String::as_str).collect();
                assert_eq!(
                    actual,
                    std::collections::HashSet::from(["one", "many", "other"])
                );
                assert!(categories
                    .values()
                    .all(|value| value.as_str().unwrap().contains("%3$n")));
                assert!(target["variantStates"]
                    .as_object()
                    .unwrap()
                    .values()
                    .all(|state| state == "translated"));
            }
            assert!(!parsed
                .messages
                .contains_key("Private regional Portuguese harbor"));
            let incomplete = BTreeMap::from([(
                "harbor.first.brazilian.portuguese🧭".to_owned(),
                "{count, plural, one {{arg1} {count}} other {{arg1} {count}}}".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&skeleton, &incomplete).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: regional Portuguese silently omitted its many category"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsFirstLocaleCategories"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("ru"));
            for first in [
                "harbor.first.russian.missing🧭",
                "harbor.first.russian.null🧭",
            ] {
                let inserted = &parsed.messages[first];
                let russian = &inserted.metadata.as_ref().unwrap()["localizations"]["ru"];
                let categories = russian["variants"].as_object().unwrap();
                let actual: std::collections::HashSet<_> =
                    categories.keys().map(String::as_str).collect();
                assert_eq!(
                    actual,
                    std::collections::HashSet::from(["one", "few", "many", "other"])
                );
                assert!(categories
                    .values()
                    .all(|value| value.as_str().unwrap().contains("%3$n")));
                assert!(russian["variantStates"]
                    .as_object()
                    .unwrap()
                    .values()
                    .all(|state| state == "translated"));
            }
            assert!(!parsed
                .messages
                .contains_key("Private first missing Russian plural"));
            assert!(!parsed
                .messages
                .contains_key("Private first null Russian plural"));
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(&original, "zz")
                    .unwrap_err()
                    .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: unsupported first-locale categories were invented"
            );
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(&original, "und")
                    .unwrap_err()
                    .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: the undefined ICU root became a target locale"
            );
            for supported in ["cv", "ie", "kok", "kok-Latn", "sgs"] {
                assert_eq!(
                    mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                        &original, supported
                    )
                    .unwrap()
                    .apple_target_locale
                    .as_deref(),
                    Some(supported),
                    "{id}: ICU-supported locale {supported} was rejected"
                );
            }
            let invalid = BTreeMap::from([(
                "harbor.first.russian.missing🧭".to_owned(),
                "{count, plural, one {{count}} other {{count}}}".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&skeleton, &invalid).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: incomplete ICU first-locale categories were accepted"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsTargetPlural"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("ru"));
            let plural = &parsed.messages["harbor.target.russian🧭"];
            assert_eq!(
                plural.default_message,
                "{count, plural, one {{count} beacon {arg1}} other {{count} beacons {arg1}}}"
            );
            let russian = &plural.metadata.as_ref().unwrap()["localizations"]["ru"];
            assert_eq!(
                russian["variants"]["one"],
                "%2$@ %3$n %1$lld маяк у причала"
            );
            assert_eq!(
                russian["variants"]["few"],
                "%2$@ %3$n %1$lld маяка у причала"
            );
            assert_eq!(
                russian["variants"]["many"],
                "%2$@ %3$n %1$lld маяков у причала"
            );
            assert_eq!(
                russian["variants"]["other"],
                "%2$@ %3$n %1$lld маяка у причала"
            );
            assert_eq!(russian["variantStates"]["one"], "needs_review");
            assert_eq!(russian["variantStates"]["few"], "new");
            assert_eq!(russian["variantStates"]["many"], "future_review");
            assert_eq!(russian["variantStates"]["other"], "translated");
            assert!(!parsed
                .messages
                .contains_key("Private target Russian plural"));

            let mut missing_other: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_other["strings"]["harbor.target.russian🧭"]["localizations"]["ru"]
                ["variations"]["plural"]
                .as_object_mut()
                .unwrap()
                .remove("other");
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_other).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "MISSING_OTHER_VARIANT",
                "{id}: a Russian target plural without other was accepted"
            );

            let mut missing_target: Value = serde_json::from_str(&skeleton.source).unwrap();
            missing_target["strings"]["harbor.target.russian🧭"]["localizations"]["ru"] =
                Value::Null;
            assert_eq!(
                mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                    serde_json::to_string(&missing_target).unwrap().as_bytes(),
                    "ru"
                )
                .unwrap_err()
                .code,
                "UNSUPPORTED_SKELETON_SOURCE",
                "{id}: an absent Russian target plural was silently flattened"
            );

            let mut forged = skeleton.clone();
            forged.slots = vec![skeleton
                .slots
                .iter()
                .find(|slot| slot.variant.as_deref() == Some("few"))
                .unwrap()
                .clone()];
            forged.slots[0].variant = Some("zero".to_owned());
            let translations = BTreeMap::from([(
                "harbor.target.russian🧭#zero".to_owned(),
                "Forged target category".to_owned(),
            )]);
            assert_eq!(
                render_skeleton(&forged, &translations).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: a missing target plural category was forged"
            );

            if encoding.is_none() {
                let protected = skeleton
                    .source
                    .find("\"Private target Russian plural\"")
                    .unwrap();
                let target = protected + skeleton.source[protected..].find("\"ru\"").unwrap();
                let beginning = target
                    + skeleton.source[target..].find("\"value\": \"").unwrap()
                    + "\"value\": \"".len();
                let end = beginning + skeleton.source[beginning..].find('"').unwrap();
                let mut forged = skeleton.clone();
                forged.slots = vec![mojito_file_formats::SourceSlot {
                    id: "harbor.target.russian🧭".to_owned(),
                    selector: None,
                    variant: Some("one".to_owned()),
                    start: beginning,
                    end,
                    apple_object_index: None,
                }];
                let translations = BTreeMap::from([(
                    "harbor.target.russian🧭#one".to_owned(),
                    "Forged protected branch".to_owned(),
                )]);
                assert_eq!(
                    render_skeleton(&forged, &translations).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: a protected target plural category was forged"
                );
            }

            if case["xcstringsTargetPluralInsertion"].as_bool() == Some(true) {
                let inserted_translations: BTreeMap<String, String> = serde_json::from_slice(
                    &fs::read(root.join(case["translations"].as_str().unwrap())).unwrap(),
                )
                .unwrap();
                for inserted_id in [
                    "harbor.target.russian.missing🧭",
                    "harbor.target.russian.null🧭",
                ] {
                    let inserted = &parsed.messages[inserted_id];
                    let metadata = inserted.metadata.as_ref().unwrap();
                    let target = &metadata["localizations"]["ru"];
                    let actual: std::collections::HashSet<_> = target["variants"]
                        .as_object()
                        .unwrap()
                        .keys()
                        .map(String::as_str)
                        .collect();
                    assert_eq!(
                        actual,
                        std::collections::HashSet::from(["one", "few", "many", "other"])
                    );
                    assert!(target["variants"]
                        .as_object()
                        .unwrap()
                        .values()
                        .all(|value| value.as_str().unwrap().contains("%1$lld %3$n")));
                    assert!(target["variantStates"]
                        .as_object()
                        .unwrap()
                        .values()
                        .all(|value| value == "translated"));
                    assert_eq!(metadata["sourcePluralStates"]["one"], "needs_review");
                }
                assert!(!parsed
                    .messages
                    .contains_key("Private missing Russian plural"));
                assert!(!parsed.messages.contains_key("Private null Russian plural"));

                for (expected, value) in [
                    ("MISSING_OTHER_VARIANT", "{count, plural, one {{count}}}"),
                    (
                        "INVALID_PLURAL_CATEGORY",
                        "{count, plural, several {{count}} other {{count}}}",
                    ),
                    (
                        "INVALID_PLACEHOLDER",
                        "{count, plural, one {{unknown}} few {{count}} many {{count}} other {{count}}}",
                    ),
                ] {
                    let invalid = BTreeMap::from([(
                        "harbor.target.russian.missing🧭".to_owned(),
                        value.to_owned(),
                    )]);
                    assert_eq!(
                        render_skeleton(&skeleton, &invalid).unwrap_err().code,
                        expected,
                        "{id}: malformed atomic target plural was accepted"
                    );
                }
                for value in [
                    "plain scalar",
                    "{count, plural, one {{count}} other {{count}}}",
                    "{count, plural, one {{count}} one {{count}} few {{count}} many {{count}} other {{count}}}",
                    "{count, plural, one {{count}} few {{count}} many {{count}} other {{count}}",
                ] {
                    let invalid = BTreeMap::from([(
                        "harbor.target.russian.missing🧭".to_owned(),
                        value.to_owned(),
                    )]);
                    assert_eq!(
                        render_skeleton(&skeleton, &invalid).unwrap_err().code,
                        "INVALID_SKELETON",
                        "{id}: incomplete or duplicated Russian categories were accepted"
                    );
                }

                let mut no_evidence: Value = serde_json::from_str(&skeleton.source).unwrap();
                no_evidence["strings"]["harbor.target.russian🧭"]["localizations"]["ru"] =
                    Value::Null;
                assert_eq!(
                    mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                        serde_json::to_string(&no_evidence).unwrap().as_bytes(),
                        "ru",
                    )
                    .unwrap_err()
                    .code,
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "{id}: atomic target insertion guessed categories without native evidence"
                );

                if encoding.is_none() {
                    let protected = skeleton
                        .source
                        .find("\"Private null Russian plural\"")
                        .unwrap();
                    let start = protected
                        + skeleton.source[protected..].find("\"ru\": null").unwrap()
                        + "\"ru\": ".len();
                    let mut forged = skeleton.clone();
                    forged.slots = vec![mojito_file_formats::SourceSlot {
                        id: "harbor.target.russian.null🧭".to_owned(),
                        selector: None,
                        variant: None,
                        start,
                        end: start + "null".len(),
                        apple_object_index: None,
                    }];
                    let invalid = BTreeMap::from([(
                        "harbor.target.russian.null🧭".to_owned(),
                        inserted_translations["harbor.target.russian.null🧭"].clone(),
                    )]);
                    assert_eq!(
                        render_skeleton(&forged, &invalid).unwrap_err().code,
                        "INVALID_SKELETON",
                        "{id}: a protected null target plural was forged"
                    );

                    let protected = skeleton
                        .source
                        .find("\"Private missing Russian plural\"")
                        .unwrap();
                    let map = protected
                        + skeleton.source[protected..]
                            .find("\"localizations\"")
                            .unwrap();
                    let closing = map + skeleton.source[map..].find("\n      }").unwrap() + 7;
                    let insertion = skeleton.source[..closing]
                        .trim_end_matches([' ', '\t', '\r', '\n'])
                        .len();
                    let mut forged = skeleton.clone();
                    forged.slots = vec![mojito_file_formats::SourceSlot {
                        id: "harbor.target.russian.missing🧭".to_owned(),
                        selector: None,
                        variant: None,
                        start: insertion,
                        end: insertion,
                        apple_object_index: None,
                    }];
                    let invalid = BTreeMap::from([(
                        "harbor.target.russian.missing🧭".to_owned(),
                        inserted_translations["harbor.target.russian.missing🧭"].clone(),
                    )]);
                    assert_eq!(
                        render_skeleton(&forged, &invalid).unwrap_err().code,
                        "INVALID_SKELETON",
                        "{id}: a protected missing target plural was forged"
                    );
                }
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsOpaqueReviewStates"].as_bool() == Some(true)
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("fr_CA"));
            let states = [
                "new",
                "needs_review",
                "translated",
                "machine_translated",
                "stale",
                "future_review",
                "untranslated",
                "invalid_future_state",
            ];
            for (index, state) in states.iter().enumerate() {
                let id = format!("harbor.review.{index}.{state}🧭");
                let metadata = parsed.messages[&id].metadata.as_ref().unwrap();
                assert_eq!(metadata["sourceState"], *state);
                assert_eq!(
                    metadata["extractionState"],
                    if index % 2 == 0 { "manual" } else { "stale" }
                );
                assert_eq!(
                    metadata["localizations"]["fr-CA"]["state"],
                    states[states.len() - index - 1]
                );
                assert_eq!(
                    metadata["localizations"]["de"]["state"],
                    format!("preserved_future_state_{index}")
                );
                assert_eq!(
                    metadata["localizations"]["fr-CA"]["value"],
                    format!("Révisé {state} %@")
                );
            }
            let metadata = parsed.messages["harbor.review.source.new.automatic🧭"]
                .metadata
                .as_ref()
                .unwrap();
            assert_eq!(metadata["sourceState"], "new");
            assert_eq!(metadata["extractionState"], "invalid_future_extraction");
            assert_eq!(
                metadata["localizations"]["fr-CA"]["state"],
                "invalid_future_state"
            );
            assert!(!parsed.messages.contains_key("Private future review state"));
        } else if format == FileFormat::AppleXcstrings && case["xcstringsTargetLocale"].is_string()
        {
            assert_eq!(skeleton.apple_target_locale.as_deref(), Some("fr_CA"));
            let hidden = &parsed.messages["North %n %@ 🧭"];
            assert_eq!(hidden.default_message, "North  {arg1} 🧭");
            assert_eq!(
                hidden.metadata.as_ref().unwrap()["sourceState"],
                "translated"
            );
            assert_eq!(
                hidden.metadata.as_ref().unwrap()["localizations"]["fr-CA"]["value"],
                "Ouest %n %@ 🧭"
            );
            assert_eq!(
                parsed.messages["West %2$n %1$@ pier"]
                    .metadata
                    .as_ref()
                    .unwrap()["localizations"]["fr-CA"]["state"],
                "needs_review"
            );
            assert_eq!(
                parsed.messages["Tide %%n %@ marker"]
                    .metadata
                    .as_ref()
                    .unwrap()["localizations"]["fr-CA"]["state"],
                "new"
            );
            assert!(!parsed.messages.contains_key("Private target null pier"));
            assert!(!parsed.messages.contains_key("Private target missing pier"));
            for invalid in ["", "en", "fr CA", "fr--CA", "x", "fr-123456789"] {
                assert_eq!(
                    mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                        &original, invalid
                    )
                    .unwrap_err()
                    .code,
                    "INVALID_XCSTRINGS_LOCALE",
                    "{id}: invalid or source-owned target locale {invalid:?} was accepted"
                );
            }
            let mut conflicting: Value = serde_json::from_str(&skeleton.source).unwrap();
            conflicting["strings"]["harbor.target.missing🧭"]["localizations"]["fr-CA"] = serde_json::json!({
                "stringUnit": {
                    "state": "translated",
                    "value": "Conflicting physical locale"
                }
            });
            let distinct = mojito_file_formats::extract_skeleton_with_xcode_target_insertion(
                serde_json::to_string(&conflicting).unwrap().as_bytes(),
                "fr-CA",
            )
            .expect("compiler-distinct underscore/hyphen locale directories");
            assert_eq!(distinct.apple_target_locale.as_deref(), Some("fr-CA"));
            if encoding.is_none() {
                let protected = skeleton
                    .source
                    .find("\"Private target null pier\"")
                    .unwrap();
                let start = protected
                    + skeleton.source[protected..]
                        .find("\"fr_CA\": null")
                        .unwrap()
                    + "\"fr_CA\": ".len();
                let mut forged = skeleton.clone();
                forged.slots = vec![mojito_file_formats::SourceSlot {
                    id: "harbor.target.missing🧭".to_owned(),
                    selector: None,
                    variant: None,
                    start,
                    end: start + "null".len(),
                    apple_object_index: None,
                }];
                let translations = BTreeMap::from([(
                    "harbor.target.missing🧭".to_owned(),
                    "Forged protected target".to_owned(),
                )]);
                assert_eq!(
                    render_skeleton(&forged, &translations).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: a protected null target cannot impersonate locale ownership"
                );
                let protected = skeleton
                    .source
                    .find("\"Private target missing pier\"")
                    .unwrap();
                let map = protected
                    + skeleton.source[protected..]
                        .find("\"localizations\"")
                        .unwrap();
                let closing = map + skeleton.source[map..].find("\n      }").unwrap() + 7;
                let insertion = skeleton.source[..closing]
                    .trim_end_matches([' ', '\t', '\r', '\n'])
                    .len();
                let mut forged = skeleton.clone();
                forged.slots = vec![mojito_file_formats::SourceSlot {
                    id: "harbor.target.missing🧭".to_owned(),
                    selector: None,
                    variant: None,
                    start: insertion,
                    end: insertion,
                    apple_object_index: None,
                }];
                let translations = BTreeMap::from([(
                    "harbor.target.missing🧭".to_owned(),
                    "Forged protected target".to_owned(),
                )]);
                assert_eq!(
                    render_skeleton(&forged, &translations).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: a protected missing target cannot impersonate locale ownership"
                );
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsInsertSourceLocale"].as_bool() == Some(true)
        {
            let missing_source = case["xcstringsMissingSourceLocale"].as_bool() == Some(true);
            let fallback_id = if missing_source {
                "harbor.missing.plain🧭"
            } else {
                "harbor.null.plain🧭"
            };
            let inserted = &parsed.messages["North %n %@ 🧭"];
            assert_eq!(inserted.default_message, "Ouest  {arg1} 🧭");
            assert_eq!(
                inserted.metadata.as_ref().unwrap()["sourceState"],
                "translated"
            );
            assert_eq!(
                parsed.messages[fallback_id].metadata.as_ref().unwrap()["localizations"]["fr"]
                    ["state"],
                "needs_review"
            );
            assert!(!parsed.messages.contains_key("Private null pier"));
            if encoding.is_none() {
                let protected = skeleton.source.find("\"Private null pier\"").unwrap();
                let start = protected
                    + skeleton.source[protected..].find("\"en\": null").unwrap()
                    + "\"en\": ".len();
                let mut forged = skeleton.clone();
                forged.slots = vec![mojito_file_formats::SourceSlot {
                    id: fallback_id.to_owned(),
                    selector: None,
                    variant: None,
                    start,
                    end: start + "null".len(),
                    apple_object_index: None,
                }];
                let translations =
                    BTreeMap::from([(fallback_id.to_owned(), "Forged protected value".to_owned())]);
                assert_eq!(
                    render_skeleton(&forged, &translations).unwrap_err().code,
                    "INVALID_SKELETON",
                    "{id}: a protected null cannot impersonate the source locale"
                );
                if missing_source {
                    assert!(!parsed.messages.contains_key("Private missing pier"));
                    let protected = skeleton.source.find("\"Private missing pier\"").unwrap();
                    let map = protected
                        + skeleton.source[protected..]
                            .find("\"localizations\"")
                            .unwrap();
                    let closing = map + skeleton.source[map..].find("\n      }").unwrap() + 7;
                    let insertion = skeleton.source[..closing]
                        .trim_end_matches([' ', '\t', '\r', '\n'])
                        .len();
                    let mut forged = skeleton.clone();
                    forged.slots = vec![mojito_file_formats::SourceSlot {
                        id: fallback_id.to_owned(),
                        selector: None,
                        variant: None,
                        start: insertion,
                        end: insertion,
                        apple_object_index: None,
                    }];
                    let translations = BTreeMap::from([(
                        fallback_id.to_owned(),
                        "Forged protected insertion".to_owned(),
                    )]);
                    assert_eq!(
                        render_skeleton(&forged, &translations).unwrap_err().code,
                        "INVALID_SKELETON",
                        "{id}: a protected missing locale cannot impersonate a source insertion"
                    );
                }
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsSubstitutionSlots"].as_bool() == Some(true)
        {
            if case["xcstringsHiddenArgumentSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["harbor.after"].default_message,
                    "Suive {count, plural, one {{count} {arg2} voie} \
                     other {{count} {arg2} voies}}"
                );
                assert_eq!(
                    parsed.messages["harbor.repeated"].default_message,
                    "Suive {count, plural, one {{count} {arg3} voie} \
                     other {{count} {arg3} voies}}"
                );
                assert_eq!(
                    parsed.messages["harbor.explicit"].default_message,
                    "Suive {arg0} {count, plural, one {{count}  {arg3} voie} \
                     other {{count}  {arg3} voies}}"
                );
            } else if case["xcstringsSubstitutionArgumentSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["mixed.route"].default_message,
                    "Pilote {arg0} suit {lights, plural, one {{lights} lueur près de {arg2}} \
                     other {{lights} lueurs près de {arg2}}}"
                );
                assert_eq!(
                    parsed.messages["extra.numeric"].default_message,
                    "Pilote {arg0} compte {signals, plural, one \
                     {{signals} signal près de {arg2} balise} other \
                     {{signals} signaux près de {arg2} balises}}"
                );
                assert_eq!(
                    parsed.messages["reversed.route"].default_message,
                    "Pilote {arg0} annonce {routes, plural, one {{arg2} protège {routes} voie} \
                     other {{arg2} protège {routes} voies}}"
                );
                assert_eq!(
                    parsed.messages["unicode.mixed"].default_message,
                    "{arg0} suit {信号, plural, one {{信号} pulsation vers {arg2}} \
                     other {{信号} pulsations vers {arg2}}}"
                );
                assert_eq!(
                    parsed.messages["device.mixed"].default_message,
                    "Touchez {arg0} pour {beacons, plural, one {{beacons} balise vers {arg2}} \
                     other {{beacons} balises vers {arg2}}}"
                );
                let placeholders = parsed.messages["mixed.route"]
                    .placeholders
                    .as_ref()
                    .unwrap();
                assert_eq!(
                    placeholders
                        .iter()
                        .map(|placeholder| placeholder.position.unwrap())
                        .collect::<Vec<_>>(),
                    vec![2, 3, 1]
                );
                assert_eq!(
                    placeholders
                        .iter()
                        .map(|placeholder| placeholder.name.as_str())
                        .collect::<Vec<_>>(),
                    vec!["lights", "arg2", "arg0"]
                );
            } else if case["xcstringsDisabledPrintfSubstitutionSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["harbor.zero"].default_message,
                    "Regardez {count, plural, one {{count} balise} other {{count} balises}}"
                );
                assert_eq!(
                    parsed.messages["harbor.literal"].default_message,
                    "Regardez {count, plural, one {{count}%n balise} other {{count}%n balises}}"
                );
                assert_eq!(
                    parsed.messages["harbor.line"].default_message,
                    "Regardez {count, plural, one {{count}\nbalise} other {{count}\nbalises}}"
                );
                assert_eq!(
                    parsed.messages["harbor.device.🧭"].default_message,
                    "Touchez {count, plural, one {{count} voie} other {{count} voies}}"
                );
                if case["xcstringsAllDeviceSlots"].as_bool() == Some(true) {
                    assert_eq!(
                        parsed.messages["harbor.device.🧭"]
                            .metadata
                            .as_ref()
                            .unwrap()["sourceVariationAxes"]["device"]["mac"]["stringUnit"]
                            ["value"],
                        "Cliquez %#@count@ au port"
                    );
                }
            } else if case["xcstringsDeviceSubstitutionSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["device.harbor🧭"].default_message,
                    "Touchez {arg2}: {lights, plural, one {{lights} lueur calme} \
                     other {{lights} lueurs calmes}} avant {lanes, plural, one \
                     {{lanes} voie légère} other {{lanes} voies légères}}"
                );
                assert_eq!(
                    parsed.messages["device.echo"].default_message,
                    "{count, plural, one {{count} écho léger} other {{count} échos légers}} \
                     autour de {count, plural, one {{count} écho léger} \
                     other {{count} échos légers}}"
                );
                assert_eq!(
                    parsed.messages["device.unicode"].default_message,
                    "Suivez {信号, plural, one {{信号} signal doux} other {{信号} signaux doux}}"
                );
                assert_eq!(
                    parsed.messages["device.harbor🧭"]
                        .metadata
                        .as_ref()
                        .unwrap()["defaultDevice"],
                    "iphone"
                );
                assert_eq!(
                    parsed.messages["device.unicode"].metadata.as_ref().unwrap()["defaultDevice"],
                    "ipad"
                );
                assert_eq!(
                    parsed.messages["device.harbor🧭"]
                        .metadata
                        .as_ref()
                        .unwrap()["sourceState"],
                    "needs_review"
                );
            } else {
                assert_eq!(
                    parsed.messages["harbor.route🧭"].default_message,
                    "Pilote {arg2} suit {lights, plural, one {{lights} lumière nord} \
                     other {{lights} lumières nord}} puis {lanes, plural, one \
                     {{lanes} voie légère} other {{lanes} voies légères}}"
                );
                assert_eq!(
                    parsed.messages["echo.route"].default_message,
                    "{count, plural, one {{count} écho calme} other {{count} échos calmes}} \
                     autour de {count, plural, one {{count} écho calme} \
                     other {{count} échos calmes}}"
                );
                assert_eq!(
                    parsed.messages["unicode.route"].default_message,
                    "Suivez {信号, plural, one {{信号} pulsation douce} \
                     other {{信号} pulsations douces}}"
                );
                assert_eq!(
                    parsed.messages["implicit.lane"].default_message,
                    "Regardez {amount, plural, one {{amount} place verte} \
                     other {{amount} places vertes}}"
                );
                assert_eq!(
                    parsed.messages["harbor.route🧭"].metadata.as_ref().unwrap()["sourceState"],
                    "needs_review"
                );
            }
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsDeviceHiddenArgumentSlots"].as_bool() == Some(true)
        {
            let message = &parsed.messages["device.after🧭"];
            assert_eq!(
                message.variants.as_ref().unwrap()["one"],
                "{count} {arg2} mobile repère"
            );
            assert_eq!(
                parsed.messages["device.repeated"]
                    .variants
                    .as_ref()
                    .unwrap()["other"],
                "{count} {arg3} mobile repères"
            );
            assert_eq!(
                parsed.messages["device.escaped"].variants.as_ref().unwrap()["one"],
                "{count}%n {arg1} mobile repère"
            );
            assert_eq!(
                message.metadata.as_ref().unwrap()["sourceVariationAxes"]["device"]["mac"]
                    ["variations"]["plural"]["one"]["stringUnit"]["value"],
                "%lld%n %@ bureau repère"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsDevicePluralSlots"].as_bool() == Some(true)
        {
            assert_eq!(
                parsed.messages["instruction"].default_message,
                "Touchez {arg0} rive mobile"
            );
            assert_eq!(
                parsed.messages["device.counter"].variants.as_ref().unwrap()["one"],
                "{count} signal mobile sûr"
            );
            assert_eq!(
                parsed.messages["device.counter"].variants.as_ref().unwrap()["other"],
                "{count} signaux mobiles sûrs"
            );
            assert_eq!(
                parsed.messages["device.counter"].metadata.as_ref().unwrap()["sourceVariationAxes"]
                    ["device"]["mac"]["variations"]["plural"]["one"]["stringUnit"]["value"],
                "%lld balise bureau calme"
            );
        } else if format == FileFormat::AppleXcstrings
            && case["xcstringsFutureDevices"].as_bool() == Some(true)
        {
            let message = &parsed.messages["harbor.future.device🧭"];
            assert_eq!(message.default_message, "Téléphone {arg0} quai");
            assert_eq!(
                message.metadata.as_ref().unwrap()["defaultDevice"],
                "iphone"
            );
            let devices = &message.metadata.as_ref().unwrap()["sourceVariationAxes"]["device"];
            assert_eq!(
                devices["futurecar"]["stringUnit"]["value"],
                "Vaisseau %@ quai"
            );
            assert_eq!(
                devices["\u{e000}raft"]["stringUnit"]["value"],
                "Privé %@ quai"
            );
            assert_eq!(devices["🧭raft"]["stringUnit"]["value"], "Boussole %@ quai");
            assert_eq!(
                parsed.messages["harbor.future.device.only🧭"]
                    .metadata
                    .as_ref()
                    .unwrap()["defaultDevice"],
                "futurecar"
            );
            assert_eq!(
                parsed.messages["harbor.future.device.fallback🧭"]
                    .metadata
                    .as_ref()
                    .unwrap()["defaultDevice"],
                "other"
            );
            assert!(!parsed.messages.contains_key("Private future-device harbor"));
        } else if format == FileFormat::AppleXcstrings
            && (id.contains("disabled-foundation-printf")
                || case["xcstringsAllDeviceSlots"].as_bool() == Some(true))
        {
            assert_eq!(
                parsed.messages["harbor.zero"].default_message,
                "Quai tranquille"
            );
            assert_eq!(
                parsed.messages["harbor.literal"].default_message,
                "Quai%n tranquille"
            );
            assert_eq!(
                parsed.messages["harbor.line"].default_message,
                "Quai\ntranquille"
            );
            assert_eq!(
                parsed.messages["harbor.positioned.🧭"].default_message,
                "🧭Cap vers le quai"
            );
            assert_eq!(
                parsed.messages["harbor.device"].default_message,
                "Port mobile"
            );
            if case["xcstringsAllDeviceSlots"].as_bool() == Some(true) {
                assert_eq!(
                    parsed.messages["harbor.device"].metadata.as_ref().unwrap()
                        ["sourceVariationAxes"]["device"]["mac"]["stringUnit"]["value"],
                    "Bureau%%n paisible"
                );
            }
            assert_eq!(
                parsed.messages["harbor.plural"].variants.as_ref().unwrap()["one"],
                "{count} balise"
            );
            assert_eq!(
                parsed.messages["harbor.plural"].variants.as_ref().unwrap()["other"],
                "{count} balises"
            );
        } else if format == FileFormat::AppleXcstrings
            && id.contains("hidden-foundation-argument-slots")
        {
            assert_eq!(
                parsed.messages["harbor.implicit"].default_message,
                "Ouest  {arg1}"
            );
            assert_eq!(
                parsed.messages["harbor.middle"].default_message,
                "{arg0} quai  {arg2}"
            );
            assert_eq!(
                parsed.messages["harbor.integer"].default_message,
                " {arg1} balises"
            );
            assert_eq!(
                parsed.messages["harbor.unicode.🧭"].default_message,
                "🧭 {arg1} baie"
            );
        } else if format == FileFormat::AppleXcstrings {
            assert_eq!(
                parsed.messages["escaped.route_🧭"].default_message,
                "Sud \"quai\" 🙂 {arg0}"
            );
            assert_eq!(
                parsed.messages["plural.count"].variants.as_ref().unwrap()["one"],
                "{count} signal léger"
            );
            assert_eq!(
                parsed.messages["plural.count"].variants.as_ref().unwrap()["other"],
                "{count} signaux légers"
            );
            assert_eq!(
                parsed.messages["device.route"].default_message,
                "Touchez {arg0}"
            );
            assert_eq!(
                parsed.messages["device.count"].variants.as_ref().unwrap()["one"],
                "{count} route mobile"
            );
            assert_eq!(
                parsed.messages["device.count"].variants.as_ref().unwrap()["other"],
                "{count} routes mobiles"
            );
        } else if format == FileFormat::AppleStrings {
            if id.contains("hidden-foundation-argument-slots") {
                assert_eq!(
                    parsed.messages["harbor.implicit"].default_message,
                    "Ouest  {arg1}"
                );
                assert_eq!(
                    parsed.messages["harbor.middle"].default_message,
                    "{arg0} quai  {arg2}"
                );
                assert_eq!(
                    parsed.messages["harbor.integer"].default_message,
                    " {arg1} balises"
                );
                assert_eq!(
                    parsed.messages["harbor.unicode.🧭"].default_message,
                    "🧭 {arg1} baie"
                );
            } else if [
                "portable-xml-encoding-boundary",
                "portable-xml-long-declaration",
                "portable-xml-name-boundary",
                "portable-xml-legacy-name",
            ]
            .iter()
            .any(|boundary| id.contains(boundary))
            {
                assert_eq!(parsed.messages["signal"].default_message, "Marée calme");
            } else if id.contains("character-reference") {
                assert_eq!(
                    parsed.messages["harbor.route&east"].default_message,
                    "Guide {arg0} vers '<'abri> & calme"
                );
                assert_eq!(
                    parsed.messages["literal&#000000065;"].default_message,
                    "Texte &#00000000065; protégé"
                );
                assert_eq!(
                    parsed.messages["line.break"].default_message,
                    "Nord\nSud\tEst\rOuest"
                );
                assert_eq!(
                    parsed.messages["escaped.literal"].default_message,
                    "A&#00000000065;Z"
                );
            } else if id.contains("namespaces") {
                assert_eq!(
                    parsed.messages["harbor.route"].default_message,
                    "Guide {arg0} vers l’abri"
                );
                assert_eq!(
                    parsed.messages["quiet.empty"].default_message,
                    "Calme & sûr"
                );
                assert_eq!(
                    parsed.messages["signal&calm"].default_message,
                    "'<'doux> & clair"
                );
            } else if id.contains("processing-instructions") {
                assert_eq!(
                    parsed.messages["harbor.route"].default_message,
                    "Guide {arg0} vers l’abri"
                );
                assert_eq!(
                    parsed.messages["quiet.empty"].default_message,
                    "Calme & sûr"
                );
                assert_eq!(
                    parsed.messages["wrapped&signal"].default_message,
                    "'<'doux> & clair"
                );
            } else if id.contains("xml-property-list") && id.contains("direct-dictionary") {
                assert_eq!(
                    parsed.messages["direct&route"].default_message,
                    "Quai & abri"
                );
                assert_eq!(
                    parsed.messages["direct<&>cdata"].default_message,
                    "Ouest '<'doux> & sûr"
                );
                assert_eq!(
                    parsed.messages["direct.empty"].default_message,
                    "Valeur ajoutée"
                );
            } else if id.contains("xml-property-list") {
                assert_eq!(
                    parsed.messages["escaped&key"].default_message,
                    "Quai & abri sûr"
                );
                assert_eq!(
                    parsed.messages["cdata<&>key"].default_message,
                    "Port ]]> '<'doux> & sûr"
                );
                assert_eq!(
                    parsed.messages["mixed.key"].default_message,
                    "Sud '<'doux> & sûr quai"
                );
                assert_eq!(
                    parsed.messages["empty.key"].default_message,
                    "Valeur ajoutée"
                );
                assert_eq!(
                    parsed.messages["explicit.empty"].default_message,
                    "Ouvert & sûr"
                );
                assert_eq!(parsed.messages["newline.key"].default_message, "Ouest\nEst");
                assert_eq!(
                    parsed.messages["placeholder.key"].default_message,
                    "Prêt {arg0}"
                );
                assert_eq!(
                    parsed.messages["percent.newline"].default_message,
                    "NordSud"
                );
                assert_eq!(
                    parsed.messages["percent.literal"].default_message,
                    "Prêt 75%"
                );
                assert_eq!(parsed.messages["unicode.🧭"].default_message, "Équipe 🙂");
            } else {
                assert_eq!(
                    parsed.messages["double.key"].default_message,
                    "Sud \"quai\" 🙂"
                );
                assert_eq!(
                    parsed.messages["single.key"].default_message,
                    "L'équipage \"calme\""
                );
                assert_eq!(
                    parsed.messages["shorthand.key"].default_message,
                    "Raccourci traduit"
                );
                assert_eq!(
                    parsed.messages["escaped.key"].default_message,
                    "Chemin {arg0}"
                );
                assert_eq!(parsed.messages["line.break"].default_message, "NordSud");
            }
        } else if format == FileFormat::GettextPo && case.get("gettextDomainCompiled").is_some() {
            assert_eq!(
                parsed.messages["shared.route@domain=messages"].default_message,
                "Main harbor"
            );
            assert_eq!(
                parsed.messages["shared.route@domain=north.fr"].default_message,
                "Quai ouvert"
            );
            assert_eq!(
                parsed.messages["shared.route@domain=stock%25ru"].default_message,
                "Южный проход"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=messages"]
                    .variants
                    .as_ref()
                    .unwrap()["one"],
                "{arg0} home beacon"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=messages"]
                    .variants
                    .as_ref()
                    .unwrap()["other"],
                "{arg0} home beacons"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=north.fr"]
                    .variants
                    .as_ref()
                    .unwrap()["one"],
                "{arg0} balise claire"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=north.fr"]
                    .variants
                    .as_ref()
                    .unwrap()["many"],
                "{arg0} balises claires"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=stock%25ru"]
                    .variants
                    .as_ref()
                    .unwrap()["one"],
                "{arg0} южный маяк"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=stock%25ru"]
                    .variants
                    .as_ref()
                    .unwrap()["few"],
                "{arg0} южных маяка"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=stock%25ru"]
                    .variants
                    .as_ref()
                    .unwrap()["many"],
                "{arg0} южных маяков"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=messages"]
                    .metadata
                    .as_ref()
                    .unwrap()["gettextDomainHeader"]["locale"],
                "en-GB"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=north.fr"]
                    .metadata
                    .as_ref()
                    .unwrap()["gettextDomainHeader"]["locale"],
                "fr-FR"
            );
            assert_eq!(
                parsed.messages["%d signal@domain=stock%25ru"]
                    .metadata
                    .as_ref()
                    .unwrap()["gettextDomainHeader"]["locale"],
                "ru-RU"
            );
        } else if format == FileFormat::GettextPo && encoding == Some("CP1252") {
            assert_eq!(
                parsed.messages["prix.€"].default_message,
                "“Facture” — € {arg0}"
            );
            assert_eq!(
                parsed.messages["caisse.count"].variants.as_ref().unwrap()["one"],
                "“une” – {arg0}"
            );
            assert_eq!(
                parsed.messages["caisse.count"].variants.as_ref().unwrap()["many"],
                "“plusieurs” — {arg0}"
            );
            assert_eq!(
                parsed.messages["escaped.euro"].default_message,
                "Total € — “sûr”"
            );
            assert_eq!(
                parsed.messages["empty.route"].default_message,
                "Route – ouverte"
            );
        } else if format == FileFormat::GettextPo && encoding == Some("US-ASCII") {
            assert_eq!(
                parsed.messages["plain.signal"].default_message,
                "Safe signal {arg0}"
            );
            assert_eq!(
                parsed.messages["plain.count"].variants.as_ref().unwrap()["one"],
                "One safe crate {arg0}"
            );
            assert_eq!(
                parsed.messages["plain.count"].variants.as_ref().unwrap()["other"],
                "Several safe crates {arg0}"
            );
            assert_eq!(
                parsed.messages["plain.empty"].default_message,
                "Open harbor route"
            );
        } else if id.starts_with("gettext-source-skeleton-preserves-metadata-whitespace-") {
            let message = &parsed.messages["Quiet bay"];
            assert_eq!(message.default_message, "Marée sûre");
            let metadata = message.metadata.as_ref().unwrap();
            if id.contains("control-notes") {
                assert_eq!(message.description.as_deref(), Some("neutral"));
                assert_eq!(metadata["translatorComments"], json!(["translator"]));
                assert_eq!(metadata["references"], json!(["first\u{001c}last"]));
                assert_eq!(metadata["flags"], json!(["no-c-format"]));
            } else {
                assert_eq!(
                    message.description.as_deref(),
                    Some(if id.contains("latin1") {
                        "\u{00a0}"
                    } else {
                        "\u{202f}"
                    })
                );
                assert_eq!(
                    metadata["translatorComments"],
                    json!([if id.contains("latin1") {
                        "\u{0085}"
                    } else {
                        "\u{2007}"
                    }])
                );
                assert_eq!(metadata["references"], json!(["first\u{00a0}last"]));
                assert_eq!(metadata["flags"], json!(["\u{0085}no-c-format\u{0085}"]));
            }
        } else if id.starts_with("gettext-source-skeleton-preserves-domain-whitespace-") {
            let message = &parsed.messages["Quiet bay"];
            let domain = case["gettextSourceDomain"].as_str().unwrap();
            let separator = domain.chars().last().unwrap();
            let metadata = message.metadata.as_ref().unwrap();
            assert_eq!(message.default_message, "Marée sûre");
            assert_eq!(metadata["gettextDomain"], domain);
            assert_eq!(
                metadata["references"],
                json!([format!("north{separator}dock")])
            );
            assert_eq!(metadata["flags"], json!([separator.to_string()]));
        } else if format == FileFormat::GettextPo && encoding == Some("ISO-8859-1") {
            assert_eq!(
                parsed.messages["café.signal"].default_message,
                "crème {arg0}"
            );
            assert_eq!(
                parsed.messages["Harbor route"].default_message,
                "quai animé"
            );
            assert_eq!(parsed.messages["empty.signal"].default_message, "été");
        } else if id == "gettext-source-skeleton-preserves-horizontal-plural-formula"
            || id == "gettext-source-skeleton-preserves-leading-zero-plural-decimals"
        {
            assert_eq!(
                parsed.messages["harbor.beacon_count"]
                    .variants
                    .as_ref()
                    .unwrap()["one"],
                "{arg0} port beacon"
            );
            assert_eq!(
                parsed.messages["harbor.beacon_count"]
                    .variants
                    .as_ref()
                    .unwrap()["other"],
                "{arg0} port beacons"
            );
        } else if format == FileFormat::GettextPo {
            assert_eq!(
                parsed.messages["harbor.signal"].default_message,
                "Lueur {arg0}"
            );
            assert_eq!(
                parsed.messages["crate.count"].variants.as_ref().unwrap()["one"],
                "{arg0} caisse légère"
            );
            assert_eq!(
                parsed.messages["crate.count"].variants.as_ref().unwrap()["many"],
                "{arg0} caisses lourdes"
            );
            assert_eq!(
                parsed.messages["Dock \"North\""].default_message,
                "Quai \"Sud\""
            );
            assert_eq!(parsed.messages["route.lines"].default_message, "Ouest\nEst");
            assert_eq!(
                parsed.messages["untranslated.wave"].default_message,
                "Vague activée"
            );
        } else if id.starts_with("properties-source-skeleton-preserves-terminal-backslash-")
            || id.starts_with("properties-source-skeleton-preserves-comment-whitespace-")
        {
            for (key, translation) in &translations {
                assert_eq!(
                    parsed.messages[key].default_message, *translation,
                    "{id}: translated terminal property identity {key}"
                );
            }
            if id.contains("comment-whitespace") {
                if id.contains("java-control") {
                    assert_eq!(
                        parsed.messages["route"].description.as_deref(),
                        Some("note")
                    );
                    assert_eq!(parsed.messages["anchor"].description, None);
                    assert_eq!(parsed.messages["pier"].description.as_deref(), Some("note"));
                } else if id.contains("crlf-mixed") {
                    assert_eq!(
                        parsed.messages["route"].description.as_deref(),
                        Some("\u{2007} north\u{2007}")
                    );
                    assert_eq!(
                        parsed.messages["anchor"].description.as_deref(),
                        Some("clear")
                    );
                } else {
                    assert_eq!(
                        parsed.messages["route"].description.as_deref(),
                        Some("\u{0085}")
                    );
                    assert_eq!(
                        parsed.messages["anchor"].description.as_deref(),
                        Some("\u{00a0}")
                    );
                }
            }
        } else if format == FileFormat::Yaml {
            assert_eq!(
                parsed.messages["welcome"].default_message,
                "Bienvenue : amie"
            );
            assert_eq!(
                parsed.messages["group/block"].default_message,
                "Première ligne\nDeuxième ligne"
            );
        } else if matches!(format, FileFormat::JavaScript | FileFormat::TypeScript) {
            for (key, translation) in &translations {
                assert_eq!(
                    parsed.messages[key].default_message, *translation,
                    "{id}: translated JavaScript source identity {key}"
                );
            }
        } else if format == FileFormat::Resx {
            for (key, translation) in &translations {
                assert_eq!(
                    parsed.messages[key].default_message, *translation,
                    "{id}: translated Microsoft resource {key}"
                );
            }
        } else if encoding == Some("ISO-8859-1") {
            assert_eq!(parsed.messages["café"].default_message, "prix 5 € 🙂");
            assert_eq!(
                parsed.messages["escaped.key"].default_message,
                "crème {arg0}"
            );
            assert_eq!(parsed.messages["continued"].default_message, "café brûlant");
            assert_eq!(parsed.messages["empty.key"].default_message, "été");
        } else {
            assert_eq!(
                parsed.messages["escaped key:="].default_message,
                "quai = abri : # calme"
            );
            assert_eq!(
                parsed.messages["unicode.key"].default_message,
                "Prêt {arg0}"
            );
            assert_eq!(
                parsed.messages["continued.value"].default_message,
                "route ouest calme"
            );
            assert_eq!(
                parsed.messages["key.only"].default_message,
                "valeur ajoutée"
            );
            assert_eq!(parsed.messages["line.break"].default_message, "Ouest\nSud");
        }

        let unknown = BTreeMap::from([("missing".to_owned(), "value".to_owned())]);
        assert_eq!(
            render_skeleton(&skeleton, &unknown).unwrap_err().code,
            "UNKNOWN_SKELETON_SLOT",
            "{id}: fail closed on translations without source ownership"
        );
        if format == FileFormat::Android && parsed.messages.contains_key("rich") {
            let invalid_markup =
                BTreeMap::from([("rich".to_owned(), "Unstyled target".to_owned())]);
            assert_eq!(
                render_skeleton(&skeleton, &invalid_markup)
                    .unwrap_err()
                    .code,
                "INVALID_SKELETON_MARKUP",
                "{id}: inline structure cannot silently disappear"
            );
        }
        for rejected in case["androidSkeletonReject"]
            .as_array()
            .into_iter()
            .flatten()
        {
            let invalid: BTreeMap<String, String> =
                serde_json::from_value(rejected["translations"].clone()).unwrap();
            assert_eq!(
                render_skeleton(&skeleton, &invalid).unwrap_err().code,
                rejected["error"].as_str().unwrap(),
                "{id}: unsafe inline-token mutation must fail"
            );
        }
        for rejected in case["xcstringsSkeletonReject"]
            .as_array()
            .into_iter()
            .flatten()
        {
            let invalid: BTreeMap<String, String> =
                serde_json::from_value(rejected["translations"].clone()).unwrap();
            assert_eq!(
                render_skeleton(&skeleton, &invalid).unwrap_err().code,
                rejected["error"].as_str().unwrap(),
                "{id}: missing/duplicated Xcode substitution must fail"
            );
        }
        if format == FileFormat::GettextPo && encoding == Some("ISO-8859-1") {
            let (identity, translation) = if id
                .starts_with("gettext-source-skeleton-preserves-metadata-whitespace-")
                || id.starts_with("gettext-source-skeleton-preserves-domain-whitespace-")
            {
                ("Quiet bay", "euro €")
            } else {
                ("café.signal", "euro € {arg0}")
            };
            let unmappable = BTreeMap::from([(identity.to_owned(), translation.to_owned())]);
            assert_eq!(
                render_skeleton(&skeleton, &unmappable).unwrap_err().code,
                "INVALID_GETTEXT_ENCODING",
                "{id}: unrepresentable legacy translations must fail"
            );
        }
        if format == FileFormat::GettextPo && encoding == Some("CP1252") {
            let unmappable = BTreeMap::from([("prix.€".to_owned(), "signal 🙂".to_owned())]);
            assert_eq!(
                render_skeleton(&skeleton, &unmappable).unwrap_err().code,
                "INVALID_GETTEXT_ENCODING",
                "{id}: unmappable Windows code-page translations must fail"
            );
        }
        if format == FileFormat::GettextPo && encoding == Some("US-ASCII") {
            let unmappable = BTreeMap::from([("plain.empty".to_owned(), "café".to_owned())]);
            assert_eq!(
                render_skeleton(&skeleton, &unmappable).unwrap_err().code,
                "INVALID_GETTEXT_ENCODING",
                "{id}: non-ASCII translations must fail"
            );
        }
        let mut invalid = skeleton.clone();
        invalid.slots[0].end = original.len() + 1;
        assert_eq!(
            render_skeleton(&invalid, &BTreeMap::new())
                .unwrap_err()
                .code,
            "INVALID_SKELETON",
            "{id}: reject out-of-range byte ownership"
        );
    }
    for case in manifest["sourceSkeletonErrors"].as_array().unwrap() {
        let id = case["id"].as_str().unwrap();
        let format = FileFormat::from_id(case["format"].as_str().unwrap()).unwrap();
        let path = root.join(case["input"].as_str().unwrap());
        let source = case.get("encoding").and_then(Value::as_str).map_or_else(
            || fs::read(&path).unwrap(),
            |encoding| encode(&fs::read_to_string(&path).unwrap(), Some(encoding)),
        );
        assert_eq!(
            extract_skeleton_with_encoding(format, &source, None)
                .unwrap_err()
                .code,
            case["error"].as_str().unwrap(),
            "{id}: unsupported source ownership must fail closed"
        );
    }
}

#[test]
fn all_shared_binary_apple_source_skeletons() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let cases = manifest["appleBinarySourceSkeletons"].as_array().unwrap();
    assert!(
        !cases.is_empty(),
        "binary Foundation templates must not be skipped"
    );
    for case in cases {
        let id = case["id"].as_str().unwrap();
        let format = FileFormat::from_id(case["format"].as_str().unwrap()).unwrap();
        let source = fs::read(root.join(case["input"].as_str().unwrap())).unwrap();
        let skeleton = extract_skeleton_with_encoding(format, &source, None)
            .unwrap_or_else(|error| panic!("{id}: binary ownership failed: {error}"));
        let expected: Value = serde_json::from_slice(
            &fs::read(root.join(case["expected"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        assert_eq!(serde_json::to_value(&skeleton).unwrap(), expected, "{id}");
        assert_eq!(
            render_skeleton(&skeleton, &BTreeMap::new()).unwrap(),
            source,
            "{id}: untranslated binary bytes changed"
        );
        let translations: BTreeMap<String, String> = serde_json::from_slice(
            &fs::read(root.join(case["translations"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        let localized = render_skeleton(&skeleton, &translations)
            .unwrap_or_else(|error| panic!("{id}: binary reinjection failed: {error}"));
        assert_eq!(
            localized,
            fs::read(root.join(case["localized"].as_str().unwrap())).unwrap(),
            "{id}: binary object-table output differs"
        );
        mojito_file_formats::parse(format, &localized)
            .unwrap_or_else(|error| panic!("{id}: localized binary parse failed: {error}"));
        let unknown = BTreeMap::from([("missing".to_owned(), "translation".to_owned())]);
        assert_eq!(
            render_skeleton(&skeleton, &unknown).unwrap_err().code,
            "UNKNOWN_SKELETON_SLOT",
            "{id}: unknown object ownership must fail"
        );
        let mut invalid = skeleton.clone();
        invalid.slots[0].end += 1;
        assert_eq!(
            render_skeleton(&invalid, &BTreeMap::new())
                .unwrap_err()
                .code,
            "INVALID_SKELETON",
            "{id}: forged object ownership must fail"
        );
        if let Some(position) = skeleton
            .slots
            .iter()
            .position(|slot| slot.apple_object_index.is_some())
        {
            let selected = &skeleton.slots[position];
            let selected_key =
                if let (Some(selector), Some(variant)) = (&selected.selector, &selected.variant) {
                    format!("{}#{selector}#{variant}", selected.id)
                } else if let Some(variant) = &selected.variant {
                    format!("{}#{variant}", selected.id)
                } else {
                    selected.id.clone()
                };
            let partial = BTreeMap::from([(
                selected_key.clone(),
                translations.get(&selected_key).unwrap().clone(),
            )]);
            let original_catalog = mojito_file_formats::parse(format, &source).unwrap();
            let partial_catalog =
                mojito_file_formats::parse(format, &render_skeleton(&skeleton, &partial).unwrap())
                    .unwrap();
            if format == FileFormat::AppleStrings {
                for (identifier, message) in &original_catalog.messages {
                    if identifier != &selected.id {
                        assert_eq!(
                            partial_catalog.messages.get(identifier),
                            Some(message),
                            "{id}: untranslated shared string alias changed"
                        );
                    }
                }
            } else {
                let original_variants = original_catalog.messages[&selected.id]
                    .variants
                    .as_ref()
                    .unwrap();
                let partial_variants = partial_catalog.messages[&selected.id]
                    .variants
                    .as_ref()
                    .unwrap();
                for (category, value) in original_variants {
                    if Some(category) != selected.variant.as_ref() {
                        assert_eq!(
                            partial_variants.get(category),
                            Some(value),
                            "{id}: untranslated shared plural alias changed"
                        );
                    }
                }
            }
            let mut forged = skeleton.clone();
            forged.slots[position].apple_object_index = forged.slots[position]
                .apple_object_index
                .map(|index| index + 1);
            assert_eq!(
                render_skeleton(&forged, &translations).unwrap_err().code,
                "INVALID_SKELETON",
                "{id}: forged shared binary object identity must fail"
            );
        }
    }
}

#[test]
fn all_shared_android_overlay_source_skeletons() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let fixtures = manifest["androidOverlaySourceSkeletons"]
        .as_array()
        .unwrap();
    assert!(
        !fixtures.is_empty(),
        "multi-file source templates were skipped"
    );
    for fixture in fixtures {
        let id = fixture["id"].as_str().unwrap();
        let entries = fixture["inputs"].as_array().unwrap();
        let bytes: Vec<Vec<u8>> = entries
            .iter()
            .map(|entry| {
                let path = root.join(entry["input"].as_str().unwrap());
                entry.get("encoding").and_then(Value::as_str).map_or_else(
                    || fs::read(&path).unwrap(),
                    |encoding| encode(&fs::read_to_string(&path).unwrap(), Some(encoding)),
                )
            })
            .collect();
        let inputs: Vec<AndroidResourceInput<'_>> = entries
            .iter()
            .zip(&bytes)
            .map(|(entry, source)| AndroidResourceInput {
                source_set: entry["sourceSet"].as_str().unwrap(),
                resource_path: entry["resourcePath"].as_str().unwrap(),
                source,
            })
            .collect();
        let selected_products: Option<Vec<String>> = fixture
            .get("androidSelectedProducts")
            .map(|value| serde_json::from_value(value.clone()).unwrap());
        let external_macros = fixture["androidExternalMacros"].as_bool().unwrap_or(false);
        let application_package = fixture
            .get("androidApplicationPackage")
            .and_then(Value::as_str);
        let skeleton = if selected_products.is_none() && application_package.is_none() {
            extract_android_overlay_skeleton(&inputs)
        } else {
            extract_android_overlay_skeleton_with_context(
                &inputs,
                &[],
                selected_products.as_deref(),
                application_package,
            )
        }
        .unwrap_or_else(|error| panic!("{id}: extraction failed: {error}"));
        let expected: Value = serde_json::from_slice(
            &fs::read(root.join(fixture["expected"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        assert_eq!(
            serde_json::to_value(&skeleton).unwrap(),
            expected,
            "{id}: cross-language overlay skeleton"
        );
        let untouched = render_android_overlay_skeleton(&skeleton, &BTreeMap::new()).unwrap();
        for (original, rendered) in bytes.iter().zip(&untouched) {
            assert_eq!(original, &rendered.source, "{id}: untouched source bytes");
        }
        let translations: BTreeMap<String, String> = serde_json::from_slice(
            &fs::read(root.join(fixture["translations"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        let localized = render_android_overlay_skeleton(&skeleton, &translations)
            .unwrap_or_else(|error| panic!("{id}: localized render failed: {error}"));
        for (entry, rendered) in entries.iter().zip(&localized) {
            assert_eq!(entry["sourceSet"].as_str().unwrap(), rendered.source_set);
            assert_eq!(
                entry["resourcePath"].as_str().unwrap(),
                rendered.resource_path
            );
            assert_eq!(
                entry.get("encoding").and_then(Value::as_str).map_or_else(
                    || fs::read(root.join(entry["localized"].as_str().unwrap())).unwrap(),
                    |encoding| {
                        encode(
                            &fs::read_to_string(root.join(entry["localized"].as_str().unwrap()))
                                .unwrap(),
                            Some(encoding),
                        )
                    },
                ),
                rendered.source,
                "{id}: exact localized source bytes"
            );
        }
        if id.starts_with("android-overlay-source-portable-android-product-unicode-whitespace-") {
            assert_eq!(
                localized[0].source, bytes[0],
                "{id}: fully shadowed library bytes"
            );
            let expected_catalog: Value = serde_json::from_slice(
                &fs::read(root.join(fixture["catalog"].as_str().unwrap())).unwrap(),
            )
            .unwrap();
            assert_eq!(
                serde_json::to_value(
                    parse_android_overlay_with_context(
                        &inputs,
                        &BTreeMap::new(),
                        selected_products.as_deref(),
                        None,
                    )
                    .unwrap()
                )
                .unwrap(),
                expected_catalog,
                "{id}: original winner catalog"
            );
            let localized_inputs: Vec<AndroidResourceInput<'_>> = localized
                .iter()
                .map(|entry| AndroidResourceInput {
                    source_set: &entry.source_set,
                    resource_path: &entry.resource_path,
                    source: &entry.source,
                })
                .collect();
            let localized_catalog = parse_android_overlay_with_context(
                &localized_inputs,
                &BTreeMap::new(),
                selected_products.as_deref(),
                None,
            )
            .unwrap();
            assert_eq!(
                localized_catalog.messages["signal"].default_message,
                "Marée choisie"
            );
            assert_eq!(
                localized_catalog.messages["anchor"].default_message,
                "Ancre sûre"
            );
            let products = selected_products.as_ref().unwrap();
            assert_eq!(skeleton.android_selected_products.as_ref(), Some(products));
            assert_eq!(
                skeleton.android_runtime_slot_owners.as_ref().unwrap()["signal"],
                format!("signal@product={}", products[0])
            );
            for rejected in fixture["rejectBuilds"].as_array().unwrap() {
                let invalid: Vec<String> =
                    serde_json::from_value(rejected["androidSelectedProducts"].clone()).unwrap();
                let error = extract_android_overlay_skeleton_with_context(
                    &inputs,
                    &[],
                    Some(&invalid),
                    None,
                )
                .expect_err("invalid selected-product builds must fail closed");
                assert_eq!(rejected["error"].as_str().unwrap(), error.code, "{id}");
            }
            for rejected in fixture["reject"].as_array().unwrap() {
                let invalid: BTreeMap<String, String> =
                    serde_json::from_value(rejected["translations"].clone()).unwrap();
                let error = render_android_overlay_skeleton(&skeleton, &invalid)
                    .expect_err("internal product identities must fail closed");
                assert_eq!(rejected["error"].as_str().unwrap(), error.code, "{id}");
            }
            let mut duplicate = skeleton.clone();
            let owners = duplicate.android_runtime_slot_owners.as_mut().unwrap();
            owners.insert("signal".to_owned(), owners["anchor"].clone());
            assert_eq!(
                render_android_overlay_skeleton(&duplicate, &translations)
                    .unwrap_err()
                    .code,
                "INVALID_ANDROID_OVERLAY_SKELETON",
                "{id}: duplicated Unicode-product source ownership"
            );
            continue;
        }
        let library = localized
            .iter()
            .find(|entry| entry.source_set == "library")
            .unwrap();
        assert_eq!(
            library.source, bytes[0],
            "{id}: fully shadowed library bytes"
        );
        let lower = fs::read_to_string(
            root.join(
                entries
                    .iter()
                    .find(|entry| entry["sourceSet"] == "main")
                    .unwrap()["localized"]
                    .as_str()
                    .unwrap(),
            ),
        )
        .unwrap();
        if external_macros {
            assert!(lower.contains("<b tone=\"bright\">"));
            assert!(lower.contains("<xliff:g id=\"pilot\" example=\"D&amp;7\">%1$s</xliff:g>"));
        } else {
            assert!(lower.contains(
                "<string name=\"shared_signal\">Main <marker:g id=\"pilot\" example=\"M-1\">%1$s</marker:g></string>"
            ));
            assert!(lower.contains(
                "<string name=\"product_signal\" product=\"tablet\">Main tablet beacon</string>"
            ));
            assert!(lower.contains("<item>Main north</item>"));
            assert!(lower.contains("<item quantity=\"one\">%1$d main light</item>"));
            assert!(lower.contains("Lower coast"));
        }
        let build_type = localized
            .iter()
            .find(|entry| entry.source_set == "build_type")
            .unwrap();
        let upper = fs::read_to_string(
            root.join(
                entries
                    .iter()
                    .find(|entry| entry["sourceSet"] == "build_type")
                    .unwrap()["localized"]
                    .as_str()
                    .unwrap(),
            ),
        )
        .unwrap();
        if external_macros {
            assert_eq!(
                &build_type.source, &bytes[2],
                "{id}: winning macros untouched"
            );
            assert_eq!(
                skeleton.android_application_package.as_deref(),
                application_package
            );
            assert_eq!(
                skeleton.android_macro_owners.as_ref().unwrap()["harbor_phrase"].source_set,
                "build_type"
            );
        } else {
            assert!(upper.contains("<marker:g id=\"pilot\" example=\"D&amp;2\">%1$s</marker:g>"));
        }
        let expected_catalog: Value = serde_json::from_slice(
            &fs::read(root.join(fixture["catalog"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        assert_eq!(
            serde_json::to_value(
                parse_android_overlay_with_context(
                    &inputs,
                    &BTreeMap::new(),
                    selected_products.as_deref(),
                    application_package,
                )
                .unwrap()
            )
            .unwrap(),
            expected_catalog,
            "{id}: original winner catalog"
        );
        for rejected in fixture
            .get("rejectBuilds")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let products: Vec<String> =
                serde_json::from_value(rejected["androidSelectedProducts"].clone()).unwrap();
            let error =
                extract_android_overlay_skeleton_with_context(&inputs, &[], Some(&products), None)
                    .expect_err("invalid selected-product builds must fail closed");
            assert_eq!(rejected["error"].as_str().unwrap(), error.code, "{id}");
        }
        let localized_inputs: Vec<AndroidResourceInput<'_>> = localized
            .iter()
            .map(|entry| AndroidResourceInput {
                source_set: &entry.source_set,
                resource_path: &entry.resource_path,
                source: &entry.source,
            })
            .collect();
        let localized_catalog = parse_android_overlay_with_context(
            &localized_inputs,
            &BTreeMap::new(),
            selected_products.as_deref(),
            application_package,
        )
        .unwrap();
        let product_id = if external_macros {
            "macro_product"
        } else {
            "product_signal"
        };
        assert_eq!(
            localized_catalog.messages[product_id].default_message,
            translations[product_id]
        );
        if let Some(products) = &selected_products {
            assert!(!localized_catalog
                .messages
                .contains_key(&format!("{product_id}@product=tablet")));
            if !external_macros {
                assert_eq!(
                    localized_catalog.messages["product_routes[0]"].default_message,
                    translations["product_routes[0]"]
                );
                assert_eq!(
                    localized_catalog.messages["product_lights"]
                        .variants
                        .as_ref()
                        .unwrap()["one"],
                    translations["product_lights#one"]
                );
            }
            assert_eq!(skeleton.android_selected_products.as_ref(), Some(products));
            assert_eq!(
                skeleton.android_runtime_slot_owners.as_ref().unwrap()[product_id],
                if products.iter().any(|value| value == "tablet") {
                    format!("{product_id}@product=tablet")
                } else {
                    product_id.to_owned()
                }
            );
            let mut missing = skeleton.clone();
            missing
                .android_runtime_slot_owners
                .as_mut()
                .unwrap()
                .remove(product_id);
            assert_eq!(
                render_android_overlay_skeleton(&missing, &translations)
                    .unwrap_err()
                    .code,
                "INVALID_ANDROID_OVERLAY_SKELETON"
            );
            let mut duplicate = skeleton.clone();
            let owners = duplicate.android_runtime_slot_owners.as_mut().unwrap();
            let other = if external_macros {
                "macro_signal"
            } else {
                "main_anchor"
            };
            owners.insert(product_id.to_owned(), owners[other].clone());
            assert_eq!(
                render_android_overlay_skeleton(&duplicate, &translations)
                    .unwrap_err()
                    .code,
                "INVALID_ANDROID_OVERLAY_SKELETON"
            );
        } else {
            assert_eq!(
                localized_catalog.messages[&format!("{product_id}@product=tablet")].default_message,
                translations[&format!("{product_id}@product=tablet")]
            );
        }
        if external_macros {
            let mut forged = skeleton.clone();
            forged.android_macro_owners.as_mut().unwrap().insert(
                "harbor_phrase".to_owned(),
                mojito_file_formats::AndroidOverlayMacroOwner {
                    source_set: "library".to_owned(),
                    resource_path: "src/library/res/values/definitions.xml".to_owned(),
                },
            );
            assert_eq!(
                render_android_overlay_skeleton(&forged, &translations)
                    .unwrap_err()
                    .code,
                "INVALID_ANDROID_OVERLAY_SKELETON"
            );
        } else {
            assert!(!localized_catalog.messages.contains_key("masked_signal"));
        }
        for rejected in fixture["reject"].as_array().unwrap() {
            let invalid: BTreeMap<String, String> =
                serde_json::from_value(rejected["translations"].clone()).unwrap();
            let error = render_android_overlay_skeleton(&skeleton, &invalid)
                .expect_err("unknown or shadowed overlay slots must fail closed");
            assert_eq!(rejected["error"].as_str().unwrap(), error.code, "{id}");
        }
        for rejected in fixture
            .get("rejectMarkup")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let invalid: BTreeMap<String, String> =
                serde_json::from_value(rejected["translations"].clone()).unwrap();
            let error = render_android_overlay_skeleton(&skeleton, &invalid)
                .expect_err("macro-expanded protected/style mutations must fail closed");
            assert_eq!(rejected["error"].as_str().unwrap(), error.code, "{id}");
        }
    }
}

#[test]
fn all_shared_android_resource_overlays() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let overlays = manifest["androidOverlays"].as_array().unwrap();
    assert!(!overlays.is_empty(), "Android overlays must not be skipped");

    for overlay in overlays {
        let id = overlay["id"].as_str().unwrap();
        let descriptions = overlay["inputs"].as_array().unwrap();
        let contents: Vec<_> = descriptions
            .iter()
            .map(|input| {
                let path = root.join(input["input"].as_str().unwrap());
                input.get("encoding").and_then(Value::as_str).map_or_else(
                    || fs::read(&path).unwrap(),
                    |encoding| encode(&fs::read_to_string(&path).unwrap(), Some(encoding)),
                )
            })
            .collect();
        let inputs: Vec<_> = descriptions
            .iter()
            .zip(&contents)
            .map(|(input, source)| AndroidResourceInput {
                source_set: input["sourceSet"].as_str().unwrap(),
                resource_path: input["resourcePath"].as_str().unwrap(),
                source,
            })
            .collect();
        let flags = android_feature_flags(overlay);
        let products = android_selected_products(overlay);
        let application_package = overlay
            .get("androidApplicationPackage")
            .and_then(Value::as_str);
        let actual = android_feature_flag_definitions(overlay).map_or_else(
            || {
                parse_android_overlay_with_context(
                    &inputs,
                    &flags,
                    products.as_deref(),
                    application_package,
                )
            },
            |definitions| {
                parse_android_overlay_with_feature_flag_definitions_and_package(
                    &inputs,
                    &definitions,
                    products.as_deref(),
                    application_package,
                )
            },
        );
        if let Some(error) = overlay.get("error").and_then(Value::as_str) {
            assert_eq!(
                actual.expect_err(&format!("{id}: expected {error}")).code,
                error,
                "{id}"
            );
        } else {
            let expected: Value = serde_json::from_slice(
                &fs::read(root.join(overlay["expected"].as_str().unwrap())).unwrap(),
            )
            .unwrap();
            assert_eq!(
                serde_json::to_value(
                    actual.unwrap_or_else(|error| { panic!("{id}: unexpectedly failed: {error}") })
                )
                .unwrap(),
                expected,
                "{id}"
            );
        }
    }
}

#[test]
fn all_shared_shadow_comparison_reports() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance");
    let manifest: Value =
        serde_json::from_slice(&fs::read(root.join("manifest.json")).unwrap()).unwrap();
    let comparisons = manifest["shadowComparisons"].as_array().unwrap();
    assert!(
        !comparisons.is_empty(),
        "Shared shadow comparisons must execute"
    );
    for comparison in comparisons {
        let id = comparison["id"].as_str().unwrap();
        let fixture = manifest["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|fixture| fixture["id"] == comparison["case"])
            .unwrap();
        let bytes = fs::read(root.join(fixture["input"].as_str().unwrap())).unwrap();
        let format = FileFormat::from_id(fixture["format"].as_str().unwrap()).unwrap();
        let encoding = fixture.get("encoding").and_then(Value::as_str);
        let path = fixture.get("resourcePath").and_then(Value::as_str);
        let flags = android_feature_flags(fixture);
        let products = android_selected_products(fixture);
        let application_package = fixture
            .get("androidApplicationPackage")
            .and_then(Value::as_str);
        let catalog = android_feature_flag_definitions(fixture)
            .map_or_else(
                || {
                    parse_with_android_context(
                        format,
                        &bytes,
                        encoding,
                        path,
                        &flags,
                        products.as_deref(),
                        application_package,
                    )
                },
                |definitions| {
                    parse_with_android_feature_flag_definitions_and_package(
                        format,
                        &bytes,
                        encoding,
                        path,
                        &definitions,
                        products.as_deref(),
                        application_package,
                    )
                },
            )
            .unwrap_or_else(|error| panic!("{id}: portable extraction failed: {error}"));
        let snapshot: Value = serde_json::from_slice(
            &fs::read(root.join(fixture["okapi"]["expected"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        let legacy: Vec<LegacyTextUnit> =
            serde_json::from_value(snapshot["units"].clone()).unwrap();
        let expected: Value = serde_json::from_slice(
            &fs::read(root.join(comparison["expected"].as_str().unwrap())).unwrap(),
        )
        .unwrap();
        let actual = serde_json::to_value(compare_shadow(&catalog, &legacy)).unwrap();
        if id == "shadow-android-product-identity-collisions" {
            let collision = actual["differences"]
                .as_array()
                .unwrap()
                .iter()
                .find(|difference| {
                    difference["category"] == "legacy_projection_collision"
                        && difference["id"] == "button"
                })
                .unwrap();
            assert_eq!(
                collision["canonicalIds"],
                serde_json::json!(["button", "button@product=tablet", "button@product=watch"])
            );
        }
        if id == "shadow-android-runtime-feature-variant-native-identity-collisions" {
            let collision = actual["differences"]
                .as_array()
                .unwrap()
                .iter()
                .find(|difference| {
                    difference["category"] == "legacy_projection_collision"
                        && difference["id"] == "harbor_route"
                })
                .unwrap();
            assert_eq!(
                collision["canonicalIds"],
                serde_json::json!([
                    "harbor_route",
                    "harbor_route@flag=!neutral.flags.first",
                    "harbor_route@flag=neutral.flags.first",
                    "harbor_route@flag=neutral.flags.second"
                ])
            );
        }
        assert_eq!(actual, expected, "{id}");
    }
}

fn android_feature_flags(fixture: &Value) -> BTreeMap<String, bool> {
    fixture
        .get("androidFeatureFlags")
        .and_then(Value::as_object)
        .into_iter()
        .flat_map(|flags| flags.iter())
        .map(|(name, value)| (name.clone(), value.as_bool().expect("boolean feature flag")))
        .collect()
}

fn android_feature_flag_definitions(fixture: &Value) -> Option<Vec<AndroidFeatureFlag>> {
    fixture
        .get("androidFeatureFlagDefinitions")
        .and_then(Value::as_array)
        .map(|definitions| {
            definitions
                .iter()
                .map(|definition| AndroidFeatureFlag {
                    name: definition["name"].as_str().unwrap().to_owned(),
                    read_only: definition["mode"].as_str().unwrap() == "read_only",
                    value: definition["value"].as_bool(),
                })
                .collect()
        })
}

fn android_selected_products(fixture: &Value) -> Option<Vec<String>> {
    fixture
        .get("androidSelectedProducts")
        .and_then(Value::as_array)
        .map(|products| {
            products
                .iter()
                .map(|product| product.as_str().expect("named build product").to_owned())
                .collect()
        })
}

fn encode(source: &str, encoding: Option<&str>) -> Vec<u8> {
    match encoding {
        Some("INVALID_UTF8") => vec![0xc3, 0x28],
        Some("ODD_UTF16LE_BOM") => vec![0xff, 0xfe, 0x41],
        Some("UNPAIRED_UTF16LE_BOM") => vec![0xff, 0xfe, 0x3d, 0xd8],
        Some("UTF-8-BOM") => {
            let mut bytes = vec![0xef, 0xbb, 0xbf];
            bytes.extend(source.as_bytes());
            bytes
        }
        Some("UTF-16LE-BOM") => {
            let mut bytes = vec![0xff, 0xfe];
            for unit in source.encode_utf16() {
                bytes.extend(unit.to_le_bytes());
            }
            bytes
        }
        Some("UTF-16LE") => source.encode_utf16().flat_map(u16::to_le_bytes).collect(),
        Some("UTF-16BE-BOM") => {
            let mut bytes = vec![0xfe, 0xff];
            for unit in source.encode_utf16() {
                bytes.extend(unit.to_be_bytes());
            }
            bytes
        }
        Some("UTF-16BE") => source.encode_utf16().flat_map(u16::to_be_bytes).collect(),
        Some("ODD_UTF16LE") | Some("ODD_UTF16BE") => {
            let little_endian = encoding == Some("ODD_UTF16LE");
            let mut bytes: Vec<u8> = source
                .encode_utf16()
                .flat_map(|unit| {
                    if little_endian {
                        unit.to_le_bytes()
                    } else {
                        unit.to_be_bytes()
                    }
                })
                .collect();
            bytes.push(0x41);
            bytes
        }
        Some("UNPAIRED_UTF16LE") | Some("UNPAIRED_UTF16BE") => {
            let little_endian = encoding == Some("UNPAIRED_UTF16LE");
            let mut bytes: Vec<u8> = source
                .encode_utf16()
                .flat_map(|unit| {
                    if little_endian {
                        unit.to_le_bytes()
                    } else {
                        unit.to_be_bytes()
                    }
                })
                .collect();
            bytes.extend(if little_endian {
                [0x00, 0xd8]
            } else {
                [0xd8, 0x00]
            });
            bytes
        }
        Some("ISO-8859-1") => source
            .chars()
            .map(|character| u8::try_from(character as u32).unwrap())
            .collect(),
        Some("CP1252") => source
            .chars()
            .flat_map(|character| {
                if (character as u32) <= 0x7f || (0xa0..=0xff).contains(&(character as u32)) {
                    return vec![character as u8];
                }
                let byte = match character {
                    '€' => 0x80,
                    '‘' => 0x91,
                    '’' => 0x92,
                    '“' => 0x93,
                    '”' => 0x94,
                    '–' => 0x96,
                    '—' => 0x97,
                    _ => panic!("Unsupported CP1252 fixture character: {character}"),
                };
                vec![byte]
            })
            .collect(),
        _ => source.as_bytes().to_vec(),
    }
}
