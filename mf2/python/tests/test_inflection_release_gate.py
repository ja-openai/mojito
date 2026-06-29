from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import re
import shlex
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path
from types import SimpleNamespace

import mojito_mf2


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = PACKAGE_ROOT.parents[1]
RELEASE_VALIDATION = REPO_ROOT / "dev-docs/experiments/mf2-inflection/release_validation.py"
RELEASE_FIXTURE_BUNDLE = (
    REPO_ROOT / "dev-docs/experiments/mf2-inflection/release_fixture_bundle.py"
)
RELEASE_FIXTURE_WRAPPER = REPO_ROOT / "mf2/conformance/validate_inflection_release_fixture.py"
EXPECTED_RELEASE_ARTIFACT_FAILURE_CODES = {
    "invalid-release-artifact-path",
    "unreadable-release-artifact",
    "invalid-compiled-term-pack-json",
    "invalid-compiled-term-pack-m2if",
    "invalid-hindi-pronoun-agreement-pack-json",
}


class InflectionReleaseGateTest(unittest.TestCase):
    def test_inflection_release_gate_validates_bundle(self) -> None:
        result = subprocess.run(
            ["sh", "run.sh", "inflection-release"],
            cwd=PACKAGE_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual("", result.stderr)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn(
            "Inflection release fixture validation passed: artifacts=35 failed=0",
            result.stdout,
        )

    def test_inflection_release_gate_is_not_public_api(self) -> None:
        self.assertFalse(
            any("inflection" in export.lower() for export in mojito_mf2.__all__)
        )
        self.assertFalse(hasattr(mojito_mf2, "validate_inflection_release_fixture"))

    def test_inflection_release_command_uses_shared_conformance_wrapper(self) -> None:
        run_script = (PACKAGE_ROOT / "run.sh").read_text(encoding="utf-8")

        self.assertIn(
            "python3 ../conformance/validate_inflection_release_fixture.py",
            run_script,
        )
        self.assertNotIn("python3 -m mojito_mf2", run_script)
        self.assertNotIn("src/mojito_mf2/inflection", run_script)

    def test_root_check_runs_inflection_gate_before_runtime_packages(self) -> None:
        check_script = (REPO_ROOT / "mf2/check.sh").read_text(encoding="utf-8")
        gate_command = "(cd python && sh run.sh inflection-release)"

        self.assertIn(
            "# Fail fast on inflection release artifacts",
            check_script,
        )
        self.assertIn(gate_command, check_script)
        gate_index = check_script.index(gate_command)
        for package_command in (
            "(cd rust/mojito-mf2 && cargo test)",
            "(cd swift/MessageFormat2 && swift run MessageFormat2Conformance)",
            "(cd python && sh run.sh conformance)",
            "(cd kotlin && sh run.sh conformance)",
            "(cd go && env ",
            "(cd php && php tests/conformance.php)",
            "(cd javascript && npm run check)",
            "(cd java && sh run.sh conformance)",
        ):
            self.assertLess(gate_index, check_script.index(package_command))

    def test_inflection_release_package_command_inventory_stays_narrow(self) -> None:
        mf2_root = REPO_ROOT / "mf2"
        allowed_command_files = {"javascript/package.json", "python/run.sh"}
        command_files = [
            path
            for pattern in ("**/package.json", "**/run.sh")
            for path in mf2_root.glob(pattern)
        ]

        actual_command_files = {
            path.relative_to(mf2_root).as_posix()
            for path in command_files
            if "inflection-release" in path.read_text(encoding="utf-8")
        }

        self.assertEqual(allowed_command_files, actual_command_files)

        allowed_package_readmes = {"javascript/README.md", "python/README.md"}
        ignored_readmes = {"README.md", "conformance/README.md"}
        actual_package_readmes = {
            path.relative_to(mf2_root).as_posix()
            for path in mf2_root.rglob("README.md")
            if path.relative_to(mf2_root).as_posix() not in ignored_readmes
            and "inflection-release" in path.read_text(encoding="utf-8")
        }

        self.assertEqual(allowed_package_readmes, actual_package_readmes)

    def test_package_local_inflection_release_scripts_delegate_only_to_shared_gate(
        self,
    ) -> None:
        python_run_script = (PACKAGE_ROOT / "run.sh").read_text(encoding="utf-8")
        package_json = json.loads(
            (REPO_ROOT / "mf2/javascript/package.json").read_text(encoding="utf-8")
        )
        scripts = package_json["scripts"]

        expected_delegate = "python3 ../conformance/validate_inflection_release_fixture.py"
        self.assertIn(
            "inflection-release)\n"
            f"    {expected_delegate}\n"
            "    ;;",
            python_run_script,
        )
        self.assertEqual(expected_delegate, scripts["inflection-release"])
        self.assertIn("npm run inflection-release", scripts["check"])
        self.assertLess(
            scripts["check"].index("npm run inflection-release"),
            scripts["check"].index("npm run check:types"),
        )

        forbidden_runtime_fragments = (
            "src/mojito_mf2/inflection",
            "mojito_mf2.inflection",
            "src/inflection",
            "tools/inflection",
            "compiled-term-pack",
            "compiled term-pack",
            "m2if",
            "package-local runtime",
            "public api",
            "runtime api",
            "renderer",
        )
        command_surfaces = {
            "python/run.sh": python_run_script,
            "javascript/package.json:scripts": json.dumps(
                scripts,
                ensure_ascii=False,
                sort_keys=True,
            ),
        }
        violations = {
            label: fragment
            for label, surface in command_surfaces.items()
            for fragment in forbidden_runtime_fragments
            if fragment in surface.lower()
        }

        self.assertEqual({}, violations)

    def test_non_java_package_manifests_do_not_publish_inflection_surfaces(self) -> None:
        mf2_root = REPO_ROOT / "mf2"
        forbidden_fragments = ("compiled", "compiled-term", "inflection", "m2if")

        package_json = json.loads(
            (mf2_root / "javascript/package.json").read_text(encoding="utf-8")
        )
        manifest_surfaces = {
            "javascript/package.json:name": package_json["name"],
            "javascript/package.json:types": package_json["types"],
            "javascript/package.json:files": package_json["files"],
            "javascript/package.json:exports": package_json["exports"],
        }

        pyproject = tomllib.loads(
            (mf2_root / "python/pyproject.toml").read_text(encoding="utf-8")
        )
        manifest_surfaces.update(
            {
                "python/pyproject.toml:project": pyproject["project"],
                "python/pyproject.toml:package-data": pyproject["tool"]["setuptools"][
                    "package-data"
                ],
            }
        )

        cargo = tomllib.loads(
            (mf2_root / "rust/mojito-mf2/Cargo.toml").read_text(encoding="utf-8")
        )
        manifest_surfaces.update(
            {
                "rust/mojito-mf2/Cargo.toml:package": cargo["package"],
                "rust/mojito-mf2/Cargo.toml:features": cargo.get("features", {}),
            }
        )

        composer = json.loads((mf2_root / "php/composer.json").read_text(encoding="utf-8"))
        manifest_surfaces.update(
            {
                "php/composer.json:name": composer["name"],
                "php/composer.json:autoload": composer["autoload"],
            }
        )

        manifest_surfaces.update(
            {
                "go/go.mod": (mf2_root / "go/go.mod").read_text(encoding="utf-8"),
                "swift/MessageFormat2/Package.swift": (
                    mf2_root / "swift/MessageFormat2/Package.swift"
                ).read_text(encoding="utf-8"),
            }
        )

        violations = {
            label: surface
            for label, value in manifest_surfaces.items()
            for surface in self.flatten_manifest_surface(value)
            if any(fragment in surface.lower() for fragment in forbidden_fragments)
        }

        self.assertEqual({}, violations)

    def test_runtime_package_public_boundary_guards_stay_present(self) -> None:
        guard_snippets = {
            "javascript/tests/package-boundary-test.js": (
                'forbiddenInflectionExportFragments = ["compiled", "inflection", "m2if"]',
                "Object.keys(core).some",
                "compiled-term-pack",
            ),
            "javascript/tests/package-boundary-types-test.ts": (
                "Inflection runtime types stay out of the root package",
                "@mojito-mf2/core/inflection",
                "@mojito-mf2/core/m2if",
                "@mojito-mf2/core/compiled-term-pack",
            ),
            "go/conformance_test.go": (
                "TestPublicRuntimeAPIDoesNotExportInflectionRuntime",
                'strings.Contains(normalized, "compiledtermpack")',
                'strings.Contains(normalized, "termpack")',
                "until a product API is approved",
            ),
            "rust/mojito-mf2/tests/conformance.rs": (
                "public_runtime_api_does_not_export_inflection_runtime",
                'normalized.contains("compiledtermpack")',
                'normalized.contains("termpack")',
                "until a product API is approved",
            ),
            "php/tests/conformance.php": (
                "Inflection runtime public API must stay out",
                "compiledtermpack",
                "termpack",
                "until a product API is approved",
            ),
            "java/src/test/java/com/box/l10n/mojito/mf2/Conformance.java": (
                "checkPublicApiBoundary",
                "standalone Java MF2 must not expose public inflection/M2IF/term-pack APIs",
                'normalized.contains("compiledtermpack")',
                'normalized.contains("termpack")',
            ),
            "kotlin/src/test/kotlin/com/box/l10n/mojito/mf2/KotlinConformance.kt": (
                "checkPublicApiBoundary",
                "standalone Kotlin MF2 must not expose public inflection/M2IF/term-pack APIs",
                'normalized.contains("compiledtermpack")',
                'normalized.contains("termpack")',
            ),
            "swift/MessageFormat2/Sources/MessageFormat2Conformance/main.swift": (
                "checkPublicApiBoundary",
                "standalone Swift MF2 must not expose public inflection/M2IF/term-pack APIs",
                'normalized.contains("compiledtermpack")',
                'normalized.contains("termpack")',
            ),
        }

        mf2_root = REPO_ROOT / "mf2"
        for relative_path, snippets in guard_snippets.items():
            source = (mf2_root / relative_path).read_text(encoding="utf-8")
            for snippet in snippets:
                self.assertIn(snippet, source, f"{relative_path} must keep {snippet!r}")

    def test_standalone_runtime_readmes_pin_no_public_inflection_surface(
        self,
    ) -> None:
        readme_snippets = {
            "go/README.md": (
                "Inflection is intentionally not a public Go runtime surface yet.",
                "does not export compiled term-pack, M2IF, or inflection APIs",
                "until a concrete Go caller justifies a reviewed product API",
            ),
            "rust/mojito-mf2/README.md": (
                "Inflection is intentionally not a public Rust runtime surface yet.",
                "does not export compiled term-pack, M2IF, or inflection modules",
                "until a product-backed Rust API is approved",
            ),
            "php/README.md": (
                "Inflection is intentionally not a public PHP runtime surface yet.",
                "no public inflection, M2IF, or compiled term-pack function/class",
                "until a concrete PHP caller justifies a reviewed product API",
            ),
            "java/README.md": (
                "Inflection is intentionally not a public surface of this standalone Java MF2 package.",
                "Java/common owns the checked V0 inflection runtime and release validators",
                "until a concrete product caller justifies a reviewed API",
            ),
            "kotlin/README.md": (
                "Inflection is intentionally not a public surface of this standalone Kotlin MF2 package.",
                "Java/common owns the checked V0 inflection runtime and release validators",
                "until a concrete product caller justifies a reviewed API",
            ),
            "swift/MessageFormat2/README.md": (
                "There is no `MessageFormat2Inflection` product",
                "no public compiled term-pack, M2IF, or inflection API",
                "until a concrete mobile or embedded caller justifies",
            ),
        }

        mf2_root = REPO_ROOT / "mf2"
        for relative_path, snippets in readme_snippets.items():
            normalized_readme = " ".join(
                (mf2_root / relative_path).read_text(encoding="utf-8").split()
            )
            for snippet in snippets:
                normalized_snippet = " ".join(snippet.split())
                self.assertIn(
                    normalized_snippet,
                    normalized_readme,
                    f"{relative_path} must keep {snippet!r}",
                )

    def test_inflection_release_gate_rejects_corrupt_compiled_provenance(self) -> None:
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-release-test-") as tmp:
            base_dir = Path(tmp)
            artifact_path = base_dir / "invalid-provenance.json"
            artifact_path.write_text(
                json.dumps(
                    {
                        "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
                        "strings": [],
                        "terms": [],
                        "formSets": [],
                        "provenance": {
                            "license": "Unicode-3.0",
                            "generator": "test",
                            "sourceLabels": ["dictionary_xx.lst"],
                            "sources": [
                                {
                                    "path": "dictionary_xx.lst",
                                    "byteSize": 1,
                                    "sha256": "not-a-sha",
                                    "gitLfsPointer": False,
                                }
                            ],
                        },
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            report = self.validate_single_artifact(
                base_dir,
                artifact_path.name,
                "compiled-term-pack-json",
            )

        self.assertEqual(1, report["summary"]["failed"])
        self.assertEqual("invalid-compiled-term-pack-json", report["artifacts"][0]["code"])
        self.assertIn("sha256", report["artifacts"][0]["message"])

    def test_inflection_release_gate_rejects_corrupt_m2if_provenance(self) -> None:
        fixture_path = (
            REPO_ROOT
            / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection"
            / "es_compiled_article_pack_fixture.m2if.hex"
        )
        payload = bytes.fromhex(fixture_path.read_text(encoding="utf-8"))
        payload = self.replace_first_bytes(payload, b'"license"', b'"xicense"')
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-release-test-") as tmp:
            base_dir = Path(tmp)
            artifact_path = base_dir / "invalid-provenance.m2if"
            artifact_path.write_bytes(payload)
            report = self.validate_single_artifact(
                base_dir,
                artifact_path.name,
                "compiled-term-pack-m2if",
            )

        self.assertEqual(1, report["summary"]["failed"])
        self.assertEqual("invalid-compiled-term-pack-m2if", report["artifacts"][0]["code"])
        self.assertIn("provenance.license", report["artifacts"][0]["message"])

    def test_inflection_release_gate_rejects_corrupt_hindi_provenance(self) -> None:
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-release-test-") as tmp:
            base_dir = Path(tmp)
            artifact_path = base_dir / "invalid-hi-pronouns.json"
            artifact_path.write_text(
                json.dumps(
                    {
                        "schema": "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0",
                        "locale": "hi",
                        "packShape": "dependency-pronoun-agreement-rows-v0",
                        "provenance": {
                            "license": "Unicode-3.0",
                            "generator": "test",
                            "sourceLabels": ["pronoun_hi.csv"],
                            "sources": [],
                        },
                        "summary": {
                            "binaryLowerBoundBytes": {
                                "rowBytes": 0,
                                "stringPoolBytes": 0,
                                "totalBytes": 0,
                            },
                            "dependencyRows": 0,
                            "genitiveRows": 0,
                            "invariantNumberRows": 0,
                            "rows": 0,
                            "uniqueValues": 0,
                        },
                        "rows": [],
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            report = self.validate_single_artifact(
                base_dir,
                artifact_path.name,
                "hindi-pronoun-agreement-pack-json",
            )

        self.assertEqual(1, report["summary"]["failed"])
        self.assertEqual(
            "invalid-hindi-pronoun-agreement-pack-json",
            report["artifacts"][0]["code"],
        )
        self.assertIn("sources", report["artifacts"][0]["message"])

    def test_inflection_release_gate_rejects_hindi_summary_drift(self) -> None:
        fixture_path = (
            REPO_ROOT
            / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection"
            / "hi_pronoun_agreement_pack_fixture.json"
        )
        payload = json.loads(fixture_path.read_text(encoding="utf-8"))
        payload["summary"]["dependencyRows"] -= 1
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-release-test-") as tmp:
            base_dir = Path(tmp)
            artifact_path = base_dir / "invalid-hi-pronouns.json"
            artifact_path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            report = self.validate_single_artifact(
                base_dir,
                artifact_path.name,
                "hindi-pronoun-agreement-pack-json",
            )

        self.assertEqual(1, report["summary"]["failed"])
        self.assertEqual(
            "invalid-hindi-pronoun-agreement-pack-json",
            report["artifacts"][0]["code"],
        )
        self.assertIn(
            "Hindi pronoun dependency row count mismatch",
            report["artifacts"][0]["message"],
        )

    def test_inflection_release_wrapper_accepts_pinned_manifest_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)

            report = wrapper.validate_materialized_bundle(base_dir)

        self.assertEqual(wrapper.EXPECTED_ARTIFACTS, report["summary"]["artifacts"])
        self.assertEqual(0, report["summary"]["failed"])

    def test_inflection_release_wrapper_pins_bundle_script_source_specs(self) -> None:
        wrapper = self.load_release_fixture_wrapper()

        wrapper.validate_bundle_script_artifact_specs()

    def test_inflection_release_wrapper_rejects_bundle_source_order_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0], specs[1] = specs[1], specs[0]

        with self.assertRaisesRegex(
            ValueError,
            "Unexpected bundle source artifact order",
        ):
            wrapper.validate_bundle_artifact_specs(specs)

    def test_inflection_release_wrapper_rejects_duplicate_bundle_source_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[1] = SimpleNamespace(
            artifact_id=specs[0].artifact_id,
            kind=specs[1].kind,
            path=specs[1].path,
            source=specs[1].source,
        )

        with self.assertRaisesRegex(
            ValueError,
            r"Duplicate bundle artifact IDs: \['ar-approved-json'\]",
        ):
            wrapper.validate_bundle_artifact_specs(specs)

    def test_inflection_release_wrapper_rejects_extra_bundle_source_keys(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = SimpleNamespace(
            artifact_id=specs[0].artifact_id,
            kind=specs[0].kind,
            path=specs[0].path,
            source=specs[0].source,
            checksum="unexpected",
        )

        with self.assertRaisesRegex(
            ValueError,
            r"Unexpected keys in bundle ARTIFACTS\[ar-approved-json\]: \['checksum'\]",
        ):
            wrapper.validate_bundle_artifact_specs(specs)

    def test_inflection_release_wrapper_rejects_bundle_source_row_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = {}

        with self.assertRaisesRegex(
            ValueError,
            r"Expected object: bundle ARTIFACTS\[\]",
        ):
            wrapper.validate_bundle_artifact_specs(specs)

    def test_inflection_release_wrapper_rejects_blank_bundle_source_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = SimpleNamespace(
            artifact_id="",
            kind=specs[0].kind,
            path=specs[0].path,
            source=specs[0].source,
        )

        with self.assertRaisesRegex(
            ValueError,
            r"Expected non-empty text: bundle ARTIFACTS\[\]\.artifact_id",
        ):
            wrapper.validate_bundle_artifact_specs(specs)

    def test_inflection_release_wrapper_rejects_bundle_source_kind_path_drift(
        self,
    ) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = SimpleNamespace(
            artifact_id=specs[0].artifact_id,
            kind=wrapper.COMPILED_TERM_PACK_M2IF,
            path=specs[0].path,
            source=specs[0].source,
        )

        with self.assertRaises(ValueError) as error:
            wrapper.validate_bundle_artifact_specs(specs)

        message = str(error.exception)
        self.assertIn("Unexpected bundle artifact kind for ar-approved-json", message)
        self.assertIn("expected 'compiled-term-pack-json'", message)
        self.assertIn("got 'compiled-term-pack-m2if'", message)

        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = SimpleNamespace(
            artifact_id=specs[0].artifact_id,
            kind=specs[0].kind,
            path="artifacts/ar-approved-renamed.json",
            source=specs[0].source,
        )

        with self.assertRaises(ValueError) as error:
            wrapper.validate_bundle_artifact_specs(specs)

        message = str(error.exception)
        self.assertIn("Unexpected bundle artifact path for ar-approved-json", message)
        self.assertIn("expected 'artifacts/ar-approved.json'", message)
        self.assertIn("got 'artifacts/ar-approved-renamed.json'", message)

    def test_inflection_release_wrapper_rejects_bundle_source_filename_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = SimpleNamespace(
            artifact_id=specs[0].artifact_id,
            kind=specs[0].kind,
            path=specs[0].path,
            source="es_compiled_article_pack_fixture.json",
        )

        with self.assertRaises(ValueError) as error:
            wrapper.validate_bundle_artifact_specs(specs)

        message = str(error.exception)
        self.assertIn("Unexpected release fixture source for ar-approved-json", message)
        self.assertIn(
            "expected 'ar_compiled_approved_explicit_form_pack_fixture.json'",
            message,
        )
        self.assertIn("got 'es_compiled_article_pack_fixture.json'", message)

    def test_inflection_release_wrapper_pins_selected_v0_artifact_locale_scope(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        expected_locales = {
            "ar",
            "da",
            "de",
            "es",
            "he",
            "hi",
            "it",
            "ml",
            "pt",
            "ru",
            "sr",
            "sv",
            "tr",
        }
        excluded_locales = {
            "en",
            "id",
            "ja",
            "ko",
            "ms",
            "nb",
            "nl",
            "pl",
            "th",
            "vi",
            "yue",
            "zh",
        }

        self.assertEqual(
            expected_locales,
            wrapper.EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES,
        )
        self.assertEqual(excluded_locales, wrapper.EXCLUDED_RELEASE_ARTIFACT_LOCALES)
        self.assertEqual(
            expected_locales,
            {
                wrapper.artifact_locale(artifact_id)
                for artifact_id in wrapper.EXPECTED_ARTIFACT_IDS
            },
        )
        self.assertFalse(
            excluded_locales
            & {
                wrapper.artifact_locale(artifact_id)
                for artifact_id in wrapper.EXPECTED_ARTIFACT_IDS
            }
        )

        with self.assertRaises(ValueError) as error:
            wrapper.validate_selected_v0_release_artifact_scope(
                tuple(sorted(wrapper.EXPECTED_ARTIFACT_IDS + ("pl-case-form-json",))),
                "test bundle source",
            )
        self.assertIn(
            "Unexpected selected V0 release artifact locale scope in test bundle source",
            str(error.exception),
        )
        self.assertIn("unexpected=['pl']", str(error.exception))

    def test_public_readme_locale_lists_match_release_wrapper_scope(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        expected_selected = wrapper.EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES
        expected_excluded = wrapper.EXCLUDED_RELEASE_ARTIFACT_LOCALES
        actual_artifact_locales = {
            wrapper.artifact_locale(artifact_id)
            for artifact_id in wrapper.EXPECTED_ARTIFACT_IDS
        }

        self.assertEqual(expected_selected, actual_artifact_locales)
        self.assertFalse(expected_selected & expected_excluded)

        for relative_path in (
            "mf2/README.md",
            "mf2/conformance/README.md",
            "mf2/python/README.md",
            "mf2/javascript/README.md",
        ):
            text = (REPO_ROOT / relative_path).read_text(encoding="utf-8")
            selected, excluded = self.documented_release_locale_sets(
                text,
                relative_path,
            )
            self.assertEqual(expected_selected, selected, relative_path)
            self.assertEqual(expected_excluded, excluded, relative_path)

    def test_design_remaining_language_backlog_matches_release_locale_constants(
        self,
    ) -> None:
        wrapper = self.load_release_fixture_wrapper()
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")

        runtime_locales, metadata_locales, backlog_text = (
            self.design_runtime_coverage_locale_sets(design_note)
        )

        self.assertEqual(
            wrapper.EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES | {"fr"},
            runtime_locales,
        )
        self.assertNotIn("fr", wrapper.EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES)
        self.assertEqual(
            wrapper.EXCLUDED_RELEASE_ARTIFACT_LOCALES - {"pl"},
            metadata_locales,
        )
        self.assertIn("Polish or any locale absent", backlog_text)
        self.assertIn("arbitrary CLDR locale fallback", backlog_text)

        normalized_design = " ".join(design_note.split())
        self.assertIn(
            "| `pl` | unavailable in pinned local source checkout | no `locale.group.pl`, no `dictionary_pl.lst`, no `inflectional_pl.xml`, and no local cache data",
            normalized_design,
        )
        self.assertIn("| unknown from this data source | blocked |", normalized_design)
        self.assertIn("source-data acquisition question", normalized_design)

    def test_release_fixture_bundle_source_documents_selected_v0_scope(self) -> None:
        bundle_source = RELEASE_FIXTURE_BUNDLE.read_text(encoding="utf-8")
        normalized_source = " ".join(bundle_source.split())

        for snippet in (
            "Materialize selected V0 MF2 inflection release fixture artifacts",
            "fixed to checked Java/common V0 fixture slices",
            "not complete locale or grammar coverage",
            "does not publish package-local runtime APIs",
        ):
            self.assertIn(snippet, normalized_source)

        self.assertNotIn("complete locale coverage", normalized_source)
        self.assertNotIn("complete grammar coverage", normalized_source)

    def test_generated_release_manifest_report_stay_artifact_contract_only(self) -> None:
        forbidden_claim_words = (
            "coverage",
            "runtime",
            "supported",
            "complete",
            "all languages",
            "all inflection",
            "public api",
        )

        with tempfile.TemporaryDirectory(prefix="mojito-mf2-release-contract-test-") as tmp:
            result = subprocess.run(
                [
                    "python3",
                    str(RELEASE_FIXTURE_BUNDLE),
                    "--out-dir",
                    tmp,
                    "--validate",
                ],
                cwd=REPO_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual("", result.stderr)
            self.assertEqual(0, result.returncode, result.stdout)
            bundle_result = json.loads(result.stdout)
            self.assertEqual(
                {"artifacts", "manifest", "report", "failed"},
                set(bundle_result),
            )
            self.assertEqual(35, bundle_result["artifacts"])
            self.assertEqual(0, bundle_result["failed"])
            self.assertTrue(Path(bundle_result["manifest"]).is_file())
            self.assertTrue(Path(bundle_result["report"]).is_file())
            manifest_path = Path(bundle_result["manifest"])
            report_path = Path(bundle_result["report"])
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            report = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual({"schema", "artifacts"}, set(manifest))
        self.assertEqual({"schema", "summary", "artifacts"}, set(report))
        self.assertEqual({"artifacts", "passed", "failed"}, set(report["summary"]))
        for artifact in manifest["artifacts"]:
            self.assertEqual({"artifactId", "kind", "path"}, set(artifact))
        for artifact in report["artifacts"]:
            self.assertEqual({"artifactId", "kind", "status"}, set(artifact))
            self.assertEqual("passed", artifact["status"])

        contract_text = json.dumps(
            {"bundleResult": bundle_result, "manifest": manifest, "report": report},
            ensure_ascii=False,
            sort_keys=True,
        ).lower()
        for word in forbidden_claim_words:
            self.assertNotIn(word, contract_text)

    def test_failed_release_report_rows_stay_diagnostic_only(self) -> None:
        forbidden_claim_phrases = (
            "all languages",
            "all inflection",
            "complete locale",
            "complete grammar",
            "language-wide runtime",
            "locale-wide runtime",
            "runtime coverage",
            "runtime promotion",
            "package-local runtime",
            "public api",
            "public-api",
        )
        cases = (
            (
                "invalid-release-artifact-path",
                "../escape.json",
                "compiled-term-pack-json",
                None,
            ),
            (
                "unreadable-release-artifact",
                "missing.json",
                "compiled-term-pack-json",
                None,
            ),
            (
                "invalid-compiled-term-pack-json",
                "invalid-compiled.json",
                "compiled-term-pack-json",
                b"{}",
            ),
            (
                "invalid-compiled-term-pack-m2if",
                "invalid-compiled.m2if",
                "compiled-term-pack-m2if",
                b"not-m2if",
            ),
            (
                "invalid-hindi-pronoun-agreement-pack-json",
                "invalid-hi-pronouns.json",
                "hindi-pronoun-agreement-pack-json",
                b"{}",
            ),
        )

        for expected_code, artifact_path, kind, payload in cases:
            with self.subTest(expected_code=expected_code):
                with tempfile.TemporaryDirectory(
                    prefix="mojito-mf2-inflection-release-test-"
                ) as tmp:
                    base_dir = Path(tmp)
                    if payload is not None:
                        (base_dir / artifact_path).write_bytes(payload)
                    report = self.validate_single_artifact(
                        base_dir,
                        artifact_path,
                        kind,
                    )

                self.assertEqual(
                    {"artifacts", "passed", "failed"},
                    set(report["summary"]),
                )
                self.assertEqual(1, report["summary"]["artifacts"])
                self.assertEqual(0, report["summary"]["passed"])
                self.assertEqual(1, report["summary"]["failed"])
                self.assertEqual(1, len(report["artifacts"]))
                row = report["artifacts"][0]
                self.assertEqual(
                    {"artifactId", "kind", "status", "code", "message"},
                    set(row),
                )
                self.assertEqual("failed", row["status"])
                self.assertEqual(expected_code, row["code"])
                self.assertIn(row["code"], EXPECTED_RELEASE_ARTIFACT_FAILURE_CODES)
                self.assertTrue(row["message"])
                normalized_row = json.dumps(
                    row,
                    ensure_ascii=False,
                    sort_keys=True,
                ).lower()
                for phrase in forbidden_claim_phrases:
                    self.assertNotIn(phrase, normalized_row)

    def test_inflection_release_wrapper_documents_scope_boundary(self) -> None:
        wrapper_source = RELEASE_FIXTURE_WRAPPER.read_text(encoding="utf-8")
        root_readme = (REPO_ROOT / "mf2/README.md").read_text(encoding="utf-8")
        conformance_readme = (REPO_ROOT / "mf2/conformance/README.md").read_text(
            encoding="utf-8"
        )
        python_readme = (REPO_ROOT / "mf2/python/README.md").read_text(
            encoding="utf-8"
        )
        javascript_readme = (REPO_ROOT / "mf2/javascript/README.md").read_text(
            encoding="utf-8"
        )
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_root_readme = " ".join(root_readme.split())
        normalized_readme = " ".join(conformance_readme.split())
        normalized_python_readme = " ".join(python_readme.split())
        normalized_javascript_readme = " ".join(javascript_readme.split())
        normalized_wrapper_source = " ".join(wrapper_source.split())
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())

        self.assertIn("selected V0 grammar", normalized_wrapper_source)
        self.assertIn(
            "not certify complete locale or grammar coverage",
            normalized_wrapper_source,
        )
        self.assertIn("selected V0 grammar slices", normalized_readme)
        self.assertIn(
            "does not mean every locale has runtime inflection coverage",
            normalized_readme,
        )
        self.assertIn("not a public inflection API", normalized_readme)
        for normalized_doc in (normalized_root_readme, normalized_readme):
            normalized_doc_lower = normalized_doc.lower()
            self.assertIn("not complete locale or grammar coverage", normalized_doc)
            self.assertIn("Artifact-row failure codes are limited to", normalized_doc)
            self.assertIn("pre-row validation errors", normalized_doc)
            self.assertIn("Malformed manifest/report JSON", normalized_doc)
            self.assertIn("invalid manifest/report UTF-8", normalized_doc)
            self.assertIn("manifest/report schema", normalized_doc)
            self.assertIn("artifact count", normalized_doc)
            self.assertRegex(normalized_doc, r"artifact ID/kind/path (?:pinning|drift)")
            self.assertIn("source fixture filename mapping", normalized_doc)
            self.assertIn(
                "Bundle source kind/path/source drift diagnostics name the artifact ID and include expected and actual values before manifest/report validation",
                normalized_doc,
            )
            self.assertIn(
                "Bundle source artifact specs must contain exactly `artifact_id`, `kind`, `source`, and `path`",
                normalized_doc,
            )
            self.assertIn("JSON object/array shapes", normalized_doc)
            self.assertIn("path containment", normalized_doc)
            self.assertIn("symlink paths", normalized_doc)
            self.assertIn("all-pass fixture contract", normalized_doc)
            self.assertIn(
                "manifest top-level keys must be exactly `schema` and `artifacts`",
                normalized_doc,
            )
            self.assertIn(
                "report top-level keys must be exactly `schema`, `summary`, and `artifacts`",
                normalized_doc,
            )
            self.assertIn(
                "report summary keys must be exactly `artifacts`, `passed`, and `failed`",
                normalized_doc,
            )
            self.assertIn(
                "Manifest/report object and array shape diagnostics name the release fixture file",
                normalized_doc,
            )
            self.assertIn(
                "Manifest/report artifact count and artifact ID/order drift diagnostics name the release fixture file",
                normalized_doc,
            )
            self.assertIn(
                "Manifest/report schema, artifact kind/path, materialization, and report status diagnostics name the release fixture file",
                normalized_doc,
            )
            self.assertIn(
                "Bundle source and manifest/report required-text diagnostics name the source inventory row or release fixture artifact row",
                normalized_doc,
            )
            self.assertIn(
                "Bundle source and manifest/report row-level unexpected-key diagnostics name the source inventory row or release fixture artifact row when an artifact ID is available",
                normalized_doc,
            )
            self.assertIn(
                "Manifest artifact rows must contain exactly `artifactId`, `kind`, and `path`",
                normalized_doc,
            )
            self.assertIn(
                "passed report rows must contain exactly `artifactId`, `kind`, and `status`",
                normalized_doc,
            )
            self.assertIn(
                "The selected release artifact locales are `ar`, `da`, `de`, `es`, `he`, `hi`, `it`, `ml`, `pt`, `ru`, `sr`, `sv`, and `tr`",
                normalized_doc,
            )
            self.assertIn(
                "Metadata/profile-only or unavailable locales such as `en`, `id`, `ja`, `ko`, `ms`, `nb`, `nl`, `pl`, `th`, `vi`, `yue`, and `zh`",
                normalized_doc,
            )
            self.assertIn('status: "passed"', normalized_doc)
            self.assertIn(
                "must not include stale `code` or `message` fields",
                normalized_doc,
            )
            self.assertRegex(
                normalized_doc_lower,
                r"package-local `inflection-release` wrappers.*python.*javascript",
            )
            self.assertIn(
                "delegate back to",
                normalized_doc,
            )
            self.assertIn(
                "Those entries are wrapper command surfaces only",
                normalized_doc,
            )
            self.assertIn(
                "included in the first review slice for release-fixture validation",
                normalized_doc,
            )
            self.assertIn(
                "not package-local runtime APIs",
                normalized_doc,
            )
            self.assertIn(
                "Java/common exposes release validation as API only",
                normalized_doc,
            )
        for normalized_package_readme in (
            normalized_python_readme,
            normalized_javascript_readme,
        ):
            self.assertIn(
                "selected V0 release-fixture artifacts only",
                normalized_package_readme,
            )
            self.assertIn(
                "not complete locale or grammar coverage",
                normalized_package_readme,
            )
            self.assertIn(
                "selected release artifact locales are `ar`, `da`, `de`, `es`, `he`, `hi`, `it`, `ml`, `pt`, `ru`, `sr`, `sv`, and `tr`",
                normalized_package_readme,
            )
            self.assertIn(
                "metadata/profile-only or unavailable locales such as `en`, `id`, `ja`, `ko`, `ms`, `nb`, `nl`, `pl`, `th`, `vi`, `yue`, and `zh`",
                normalized_package_readme,
            )
        self.assertEqual(
            EXPECTED_RELEASE_ARTIFACT_FAILURE_CODES,
            self.release_artifact_failure_codes(root_readme),
        )
        self.assertEqual(
            EXPECTED_RELEASE_ARTIFACT_FAILURE_CODES,
            self.release_artifact_failure_codes(conformance_readme),
        )
        self.assertIn(
            "production-quality runtime path for the checked MF2 inflection V0 slices",
            normalized_design_note,
        )
        self.assertIn(
            'this is not "all inflection types" and not all Unicode/CLDR languages',
            normalized_design_note,
        )
        self.assertIn(
            "Polish or any locale absent from the current Unicode inflection checkout",
            normalized_design_note,
        )
        self.assertIn(
            "Missing languages such as Polish are source-data acquisition work",
            normalized_design_note,
        )
        self.assertIn(
            "not evidence that Java/common inflection V0 is universal",
            normalized_tracker,
        )
        self.assertIn(
            "Polish is blocked as a source-data acquisition question",
            normalized_tracker,
        )
        for snippet in (
            "Ninety-seventh release-fixture scope wording alignment",
            "not complete locale or grammar coverage",
            "does not expose public inflection APIs in standalone runtime packages",
            "Python package harness remains at 93 tests",
            "Ninety-eighth post-scope package-local wrapper refresh",
            "JavaScript package-local `npm run inflection-release` both pass with `artifacts=35 failed=0`",
            "do not add package-owned runtime APIs",
            "Ninety-ninth Java/common post-scope focused rerun",
            "passes 43 tests: 7 API-surface, 24 release-validator, and 12 binding-manifest",
            "without expanding the V0 claim",
        ):
            self.assertIn(snippet, normalized_design_note)
        for snippet in (
            "Latest release-fixture scope wording alignment",
            "fixed 35-artifact `inflection-release` gate covers selected V0 release-fixture artifacts only",
            "is not complete locale or grammar coverage",
            "does not expose public inflection APIs in standalone runtime packages",
            "Python package harness remains at 93 tests",
            "Latest post-scope package-local wrapper refresh",
            "JavaScript package-local `npm run inflection-release` both pass with `artifacts=35 failed=0`",
            "do not add package-owned runtime APIs",
            "Latest Java/common post-scope focused rerun",
            "passes 43 tests: 7 API-surface, 24 release-validator, and 12 binding-manifest",
            "without expanding the claim to all inflection types, all languages, Polish support, or public non-Java runtime APIs",
        ):
            self.assertIn(snippet, normalized_tracker)

    def test_design_tracker_pin_java_common_post_boundary_scope(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())

        self.assertIn(
            "Eightieth Java/common post-boundary focused rerun",
            normalized_design_note,
        )
        self.assertIn(
            "without expanding the claim to all inflection types, all languages, or public non-Java runtime APIs",
            normalized_design_note,
        )
        self.assertIn(
            "post-standalone README-boundary Java/common rerun",
            normalized_tracker,
        )
        self.assertIn(
            "Latest Java/common post-boundary rerun",
            normalized_tracker,
        )
        self.assertIn(
            "without promoting non-Java runtime APIs",
            normalized_tracker,
        )
        self.assertIn(
            "the Python package harness at 101 tests",
            normalized_tracker,
        )

    def test_design_tracker_pin_first_review_slice_boundaries(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())

        for snippet in (
            "first MF2 review/staging slice should include only the non-webapp V0 release-boundary surface",
            "`common/src/main/java/com/box/l10n/mojito/mf2/inflection/**`",
            "`common/src/test/java/com/box/l10n/mojito/mf2/inflection/**`",
            "`common/src/test/resources/com/box/l10n/mojito/mf2/inflection/**`",
            "`dev-docs/experiments/mf2-inflection/**`",
            "`mf2/**` conformance/package-boundary files",
            "Exclude Mojito `webapp` glossary/Workbench integration from that first slice",
            "claim-free bundle materializer stdout",
            "public README command-surface wording",
            "bundle source-map expected/actual diagnostics",
            "not all inflection types or public non-Java runtime APIs",
        ):
            self.assertIn(snippet, normalized_design_note)

        self.assertIn("Latest first review-slice draft", normalized_tracker)
        self.assertIn(
            "first MF2 review/staging slice should include only the non-webapp V0 release-boundary surface",
            normalized_tracker,
        )
        self.assertIn(
            "Exclude Mojito `webapp` glossary/Workbench integration from that first slice",
            normalized_tracker,
        )
        self.assertIn(
            "claim-free bundle materializer stdout",
            normalized_tracker,
        )
        self.assertIn(
            "public README command-surface wording",
            normalized_tracker,
        )
        self.assertIn(
            "bundle source-map expected/actual diagnostics",
            normalized_tracker,
        )
        self.assertIn(
            "This is release/public-boundary evidence, not package-local runtime inflection coverage",
            normalized_tracker,
        )
        self.assertIn(
            "the Python package harness at 101 tests",
            normalized_tracker,
        )

    def test_design_tracker_pin_first_slice_staging_command(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())
        design_command = self.fenced_command_after(
            design_note, "Eighty-sixth exact first-slice staging command"
        )
        tracker_command = self.fenced_command_after(
            tracker, "Latest first-slice staging command"
        )
        staging_paths = (
            "common/src/main/java/com/box/l10n/mojito/mf2/inflection",
            "common/src/test/java/com/box/l10n/mojito/mf2/inflection",
            "common/src/test/resources/com/box/l10n/mojito/mf2/inflection",
            "dev-docs/design/022-mf2-native-inflection.md",
            "dev-docs/experiments/mf2-inflection",
            "dev-docs/tracker.md",
            "mf2/README.md",
            "mf2/check.sh",
            "mf2/conformance/README.md",
            "mf2/conformance/validate_inflection_release_fixture.py",
            "mf2/go/README.md",
            "mf2/go/conformance_test.go",
            "mf2/java/README.md",
            "mf2/java/src/test/java/com/box/l10n/mojito/mf2/Conformance.java",
            "mf2/javascript/README.md",
            "mf2/javascript/package.json",
            "mf2/javascript/tests/package-boundary-test.js",
            "mf2/javascript/tests/package-boundary-types-test.ts",
            "mf2/kotlin/README.md",
            "mf2/kotlin/src/test/kotlin/com/box/l10n/mojito/mf2/KotlinConformance.kt",
            "mf2/php/README.md",
            "mf2/php/tests/conformance.php",
            "mf2/python/README.md",
            "mf2/python/run.sh",
            "mf2/python/tests/test_inflection_release_gate.py",
            "mf2/rust/mojito-mf2/README.md",
            "mf2/rust/mojito-mf2/tests/conformance.rs",
            "mf2/swift/MessageFormat2/README.md",
            "mf2/swift/MessageFormat2/Sources/MessageFormat2Conformance/main.swift",
        )

        self.assertIn("exact first-slice staging command", normalized_design_note)
        self.assertIn("Latest first-slice staging command", normalized_tracker)
        for normalized_doc in (normalized_design_note, normalized_tracker):
            self.assertIn("mf2/python/run.sh", normalized_doc)
            self.assertIn("mf2/javascript/package.json", normalized_doc)
            self.assertIn(
                "package-local `inflection-release` wrapper command surfaces",
                normalized_doc,
            )
            self.assertIn("not runtime APIs", normalized_doc)
        self.assertIn("git add \\", design_command)
        self.assertIn("git add \\", tracker_command)
        self.assertEqual(staging_paths, self.git_add_paths(design_command))
        self.assertEqual(staging_paths, self.git_add_paths(tracker_command))
        for path in staging_paths:
            self.assertIn(path, normalized_design_note)
            self.assertIn(path, normalized_tracker)
            self.assertTrue((REPO_ROOT / path).exists(), path)
        self.assertIn("no `webapp/**` paths", normalized_design_note)
        self.assertIn("contains no `webapp/**` paths", normalized_tracker)
        self.assertIn("parses the fenced command", normalized_design_note)
        self.assertIn("parses the fenced command", normalized_tracker)
        self.assertIn("rejects extra path arguments", normalized_design_note)
        self.assertIn("rejects extra path arguments", normalized_tracker)
        self.assertIn("first-slice status-to-command audit", normalized_design_note)
        self.assertIn("first-slice status-to-command audit", normalized_tracker)
        self.assertIn("236 expanded non-webapp status files", normalized_design_note)
        self.assertIn("236 expanded non-webapp status files", normalized_tracker)
        self.assertIn("24 expanded `webapp` status files", normalized_design_note)
        self.assertIn("24 expanded `webapp` status files", normalized_tracker)
        self.assertIn("missing=0", normalized_design_note)
        self.assertIn("missing=0", normalized_tracker)
        self.assertIn("unused=0", normalized_design_note)
        self.assertIn("unused=0", normalized_tracker)
        self.assertNotIn("webapp/", design_command)
        self.assertNotIn("webapp/", tracker_command)
        self.assertIn(
            "the Python package harness at 101 tests",
            normalized_tracker,
        )

    def fenced_command_after(self, text: str, marker: str) -> str:
        marker_index = text.index(marker)
        block_start = text.index("```sh", marker_index)
        command_start = text.index("\n", block_start) + 1
        block_end = text.index("```", command_start)
        return text[command_start:block_end]

    def git_add_paths(self, command: str) -> tuple[str, ...]:
        command_line = command.replace("\\\n", " ")
        parts = shlex.split(command_line)
        self.assertEqual(["git", "add"], parts[:2])
        return tuple(parts[2:])

    def git_status_paths(self) -> tuple[str, ...]:
        result = subprocess.run(
            ["git", "status", "--short", "--porcelain=v1", "-uall"],
            cwd=REPO_ROOT,
            text=True,
            capture_output=True,
            check=True,
        )
        paths = []
        for line in result.stdout.splitlines():
            path = line[3:]
            if " -> " in path:
                path = path.rsplit(" -> ", 1)[1]
            if "__pycache__/" in path or path.endswith("__pycache__"):
                continue
            paths.append(path)
        return tuple(paths)

    def path_matches_staging_arg(self, path: str, staging_arg: str) -> bool:
        return path == staging_arg or path.startswith(f"{staging_arg}/")

    def missing_status_paths(
        self, status_paths: tuple[str, ...], staging_args: tuple[str, ...]
    ) -> tuple[str, ...]:
        return tuple(
            path
            for path in status_paths
            if not any(self.path_matches_staging_arg(path, arg) for arg in staging_args)
        )

    def unused_staging_args(
        self, staging_args: tuple[str, ...], status_paths: tuple[str, ...]
    ) -> tuple[str, ...]:
        return tuple(
            arg
            for arg in staging_args
            if not any(self.path_matches_staging_arg(path, arg) for path in status_paths)
        )

    def test_tracker_current_checkpoint_mentions_latest_release_slice_guards(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        checkpoint_line = next(
            line for line in tracker.splitlines() if line.startswith("- MF2-01")
        )
        for snippet in (
            "bounded-claim wording guard",
            "Java/common post-bounded-claim rerun",
            "webapp post-bounded-claim rerun",
            "current status-to-staging-command audit",
            "remaining-language V0 policy",
            "release-fixture scope wording alignment",
            "post-scope package-local wrapper refresh",
            "Java/common post-scope focused rerun",
            "reviewer-readiness audit",
            "experiment README bounded wording guard",
            "Russian audit bounded wording guard",
            "stale broad-phrase docs guard",
            "Java/common source stale-phrase guard",
            "release artifact locale-scope guard",
            "post-locale-scope JavaScript wrapper rerun",
            "public README locale-scope wording guard",
            "post-public-README release-wrapper refresh",
            "public README locale-list drift guard",
            "release fixture bundle source-scope wording guard",
            "generated manifest/report claim-free guard",
            "failed report-row diagnostic-only guard",
            "wrapper main pre-row stderr diagnostic guard",
            "package-local wrapper command-surface guard",
            "public README command-surface wording guard",
            "bundle source-map expected/actual diagnostic guard",
            "first-slice readiness release-boundary wording guard",
        ):
            self.assertIn(snippet, checkpoint_line)

        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())
        sync_snippets = (
            "Latest tracker headline sync",
            "the MF2-01 current checkpoint now names the bounded-claim wording guard",
            "Java/common post-bounded-claim rerun",
            "webapp post-bounded-claim rerun",
            "current status-to-staging-command audit",
            "Python package harness now passes 92 tests",
        )
        for snippet in sync_snippets:
            self.assertIn(snippet, normalized_tracker)
        for snippet in (
            "Verification snapshot: current focused gates pass",
            "the Python package harness at 101 tests",
            "webapp backend product integration at 60 REST/service/MCP tests",
            "webapp frontend product integration at 81 API/admin/Workbench/private-utility tests",
            "236 non-webapp files plus 24 webapp files with missing=0 and unused=0 for both slices",
            "not package-local inflection runtime promotion",
        ):
            self.assertIn(snippet, normalized_tracker)
        for snippet in (
            "Ninety-fifth tracker headline sync",
            "the MF2-01 current checkpoint now names the bounded-claim wording guard",
            "This is tracker alignment only",
            "Python package harness now passes 92 tests",
        ):
            self.assertIn(snippet, normalized_design_note)

    def test_design_tracker_pin_remaining_language_v0_policy(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())

        for snippet in (
            "Production Data Gap Matrix",
            "`nb`, `nl` | metadata validation only",
            "`en`, `ko` | pronoun/profile audit plus profile-pack validation",
            "`id`, `ja`, `ms`, `th`, `vi`, `zh`, `yue` | profile-only/no-op audit plus profile-pack validation",
            "`pl` | unavailable in pinned local source checkout",
            "No M2IF runtime fixture or client renderer work is needed for these locales until product messages require concrete inflection behavior",
            "Polish stays blocked by absent source data in the pinned Unicode Inflection checkout",
            "Ninety-sixth remaining-language V0 policy guard",
            "Python package harness now passes 93 tests",
        ):
            self.assertIn(snippet, normalized_design_note)

        for snippet in (
            "Latest remaining-language V0 policy guard",
            "metadata/profile-only locale groups validation-only",
            "block English and Korean dictionary materialization until a product caller needs it",
            "keep Polish blocked by absent source data in the pinned Unicode Inflection checkout",
            "does not add all-language coverage or public non-Java runtime APIs",
            "Python package harness now passes 93 tests",
        ):
            self.assertIn(snippet, normalized_tracker)

    def test_design_tracker_pin_webapp_product_integration_slice_audit(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())

        self.assertIn(
            "Eighty-eighth webapp product-integration slice audit",
            normalized_design_note,
        )
        self.assertIn(
            "Latest webapp product-integration slice audit",
            normalized_tracker,
        )
        self.assertIn(
            "Ninety-third webapp post-bounded-claim product-integration rerun",
            normalized_design_note,
        )
        self.assertIn(
            "One hundredth webapp post-scope product-integration rerun",
            normalized_design_note,
        )
        self.assertIn(
            "Latest webapp post-bounded-claim rerun",
            normalized_tracker,
        )
        self.assertIn(
            "Latest webapp post-scope product-integration rerun",
            normalized_tracker,
        )
        for snippet in (
            "24 expanded `webapp` status files",
            "9 backend files and 15 frontend files",
            "`mvn -pl webapp -am -Dtest=GlossaryWSTest,GlossaryTermInflectionProfileServiceTest,ReviewGlossaryTermInflectionProfilesMcpToolTest -Dsurefire.failIfNoSpecifiedTests=false test`",
            "60 backend REST/service/MCP tests",
            "60 REST/service/MCP tests",
            "`source webapp/use_local_npm.sh && npm --prefix webapp/frontend run test -- src/api/glossaries.test.ts src/page/settings/AdminGlossaryDetailPage.test.tsx src/page/workbench/WorkbenchBody.test.tsx src/components/GlossaryMatchesPanel.test.tsx src/utils/inflectionProfileForms.test.ts src/utils/mf2TermRenderer.test.ts src/utils/mf2TermRequirements.test.ts`",
            "81 frontend API/admin/Workbench/private-utility tests",
            "81 API/admin/Workbench/private-utility tests",
            "webapp/private utility code, not a public non-Java MF2 runtime API",
            "private webapp helpers, not public non-Java MF2 runtime promotion",
            "separate Mojito webapp integration gates still pass",
            "Mojito product integration around checked Java/common V0 and private webapp helpers, not public non-Java MF2 runtime promotion",
        ):
            self.assertIn(snippet, normalized_design_note)
            self.assertIn(snippet, normalized_tracker)

    def test_webapp_inflection_helpers_stay_private_to_webapp(self) -> None:
        frontend_src = REPO_ROOT / "webapp/frontend/src"

        self.assertEqual(
            {
                "page/settings/AdminGlossaryDetailPage.tsx",
                "utils/mf2TermRenderer.test.ts",
            },
            self.frontend_importers("mf2TermRenderer"),
        )
        self.assertEqual(
            {
                "page/workbench/WorkbenchBody.tsx",
                "utils/mf2TermRequirements.test.ts",
            },
            self.frontend_importers("mf2TermRequirements"),
        )
        self.assertTrue((frontend_src / "utils/mf2TermRenderer.ts").is_file())
        self.assertTrue((frontend_src / "utils/mf2TermRequirements.ts").is_file())

        javascript_package_text = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (REPO_ROOT / "mf2/javascript").rglob("*")
            if path.is_file() and path.suffix in {".js", ".json", ".md", ".ts"}
        )
        self.assertNotIn("mf2TermRenderer", javascript_package_text)
        self.assertNotIn("mf2TermRequirements", javascript_package_text)
        self.assertNotIn("webapp/frontend/src/utils/mf2Term", javascript_package_text)

        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())
        for snippet in (
            "webapp helper boundary guard",
            "`AdminGlossaryDetailPage` and its focused tests",
            "`WorkbenchBody` and its focused tests",
            "neither helper is imported by `mf2/javascript`",
            "Python package harness now passes 88 tests",
        ):
            self.assertIn(snippet, normalized_design_note)
            self.assertIn(snippet, normalized_tracker)

    def frontend_importers(self, module_name: str) -> set[str]:
        import_pattern = re.compile(rf"from ['\"][^'\"]*{re.escape(module_name)}['\"]")
        return {
            path.relative_to(REPO_ROOT / "webapp/frontend/src").as_posix()
            for path in (REPO_ROOT / "webapp/frontend/src").rglob("*")
            if path.is_file()
            and path.suffix in {".ts", ".tsx"}
            and import_pattern.search(path.read_text(encoding="utf-8"))
        }

    def test_design_tracker_pin_final_slice_readiness_summary(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())
        design_command = self.fenced_command_after(
            design_note, "Ninetieth final slice-readiness summary"
        )
        tracker_command = self.fenced_command_after(
            tracker, "Latest final slice-readiness summary"
        )
        webapp_paths = (
            "webapp/frontend/src/api/glossaries.ts",
            "webapp/frontend/src/components/GlossaryMatchesPanel.test.tsx",
            "webapp/frontend/src/components/GlossaryMatchesPanel.tsx",
            "webapp/frontend/src/page/settings/AdminGlossaryDetailPage.tsx",
            "webapp/frontend/src/page/workbench/WorkbenchBody.test.tsx",
            "webapp/frontend/src/page/workbench/WorkbenchBody.tsx",
            "webapp/frontend/src/page/workbench/workbench-page.css",
            "webapp/src/main/java/com/box/l10n/mojito/rest/glossary/GlossaryWS.java",
            "webapp/src/test/java/com/box/l10n/mojito/rest/glossary/GlossaryWSTest.java",
            "webapp/frontend/src/api/glossaries.test.ts",
            "webapp/frontend/src/page/settings/AdminGlossaryDetailPage.test.tsx",
            "webapp/frontend/src/utils/inflectionProfileForms.test.ts",
            "webapp/frontend/src/utils/inflectionProfileForms.ts",
            "webapp/frontend/src/utils/mf2TermRenderer.test.ts",
            "webapp/frontend/src/utils/mf2TermRenderer.ts",
            "webapp/frontend/src/utils/mf2TermRequirements.test.ts",
            "webapp/frontend/src/utils/mf2TermRequirements.ts",
            "webapp/src/main/java/com/box/l10n/mojito/entity/glossary/GlossaryTermInflectionProfile.java",
            "webapp/src/main/java/com/box/l10n/mojito/service/glossary/GlossaryTermInflectionProfileRepository.java",
            "webapp/src/main/java/com/box/l10n/mojito/service/glossary/GlossaryTermInflectionProfileService.java",
            "webapp/src/main/java/com/box/l10n/mojito/service/mcp/glossary/ReviewGlossaryTermInflectionProfilesMcpTool.java",
            "webapp/src/main/resources/db/migration/V97__Glossary_Term_Inflection_Profile.sql",
            "webapp/src/test/java/com/box/l10n/mojito/service/glossary/GlossaryTermInflectionProfileServiceTest.java",
            "webapp/src/test/java/com/box/l10n/mojito/service/mcp/glossary/ReviewGlossaryTermInflectionProfilesMcpToolTest.java",
        )

        self.assertIn("Ninetieth final slice-readiness summary", normalized_design_note)
        self.assertIn("Latest final slice-readiness summary", normalized_tracker)
        for snippet in (
            "first review slice remains the non-webapp release/public-boundary slice",
            "second review slice is Mojito webapp product integration",
            "Do not stage both slices together unless the reviewer asks for one larger product PR",
            "not all inflection types, all languages, or public non-Java runtime APIs",
            "webapp product-integration staging command",
            "Python package harness now passes 89 tests",
            "One hundred first reviewer-readiness audit",
            "non-webapp command covers 236 expanded status files",
            "webapp command covers 24 expanded status files with missing=0 and unused=0 for both slices",
            "Java/common 43-test API/release/binding gate",
            "Python 101-test release/docs harness",
            "shared/Python/JavaScript `inflection-release` 35-artifact gate",
            "webapp backend 60-test product integration gate",
            "webapp frontend 81-test product-integration gate",
            "not all-language coverage or public non-Java runtime promotion",
        ):
            self.assertIn(snippet, normalized_design_note)
            self.assertIn(snippet, normalized_tracker)

        self.assertEqual(webapp_paths, self.git_add_paths(design_command))
        self.assertEqual(webapp_paths, self.git_add_paths(tracker_command))
        self.assertEqual(24, len(webapp_paths))
        for path in webapp_paths:
            self.assertTrue(path.startswith("webapp/"), path)
            self.assertTrue((REPO_ROOT / path).exists(), path)
        for forbidden_prefix in ("common/", "dev-docs/", "mf2/"):
            self.assertFalse(
                any(path.startswith(forbidden_prefix) for path in webapp_paths),
                forbidden_prefix,
            )

    def test_current_status_matches_documented_review_slice_commands(self) -> None:
        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())
        first_slice_args = self.git_add_paths(
            self.fenced_command_after(
                design_note, "Eighty-sixth exact first-slice staging command"
            )
        )
        webapp_args = self.git_add_paths(
            self.fenced_command_after(
                design_note, "Ninetieth final slice-readiness summary"
            )
        )
        tracker_first_slice_args = self.git_add_paths(
            self.fenced_command_after(tracker, "Latest first-slice staging command")
        )
        tracker_webapp_args = self.git_add_paths(
            self.fenced_command_after(tracker, "Latest final slice-readiness summary")
        )

        self.assertEqual(first_slice_args, tracker_first_slice_args)
        self.assertEqual(webapp_args, tracker_webapp_args)

        status_paths = self.git_status_paths()
        webapp_status_paths = tuple(
            path for path in status_paths if path.startswith("webapp/")
        )
        non_webapp_status_paths = tuple(
            path for path in status_paths if not path.startswith("webapp/")
        )

        self.assertEqual(236, len(non_webapp_status_paths))
        self.assertEqual(24, len(webapp_status_paths))
        self.assertEqual(
            (),
            self.missing_status_paths(non_webapp_status_paths, first_slice_args),
        )
        self.assertEqual(
            (),
            self.unused_staging_args(first_slice_args, non_webapp_status_paths),
        )
        self.assertEqual((), self.missing_status_paths(webapp_status_paths, webapp_args))
        self.assertEqual((), self.unused_staging_args(webapp_args, webapp_status_paths))
        self.assertEqual((), tuple(path for path in first_slice_args if path.startswith("webapp/")))
        self.assertTrue(all(path.startswith("webapp/") for path in webapp_args))
        self.assertFalse(set(non_webapp_status_paths).intersection(webapp_status_paths))

        for snippet in (
            "Ninety-fourth current status-to-staging-command audit",
            "236 expanded non-webapp status files",
            "24 expanded webapp status files",
            "first-slice missing=0 and unused=0",
            "webapp-slice missing=0 and unused=0",
            "Python package harness now passes 91 tests",
        ):
            self.assertIn(snippet, normalized_design_note)
        for snippet in (
            "Latest current status-to-staging-command audit",
            "236 expanded non-webapp status files",
            "24 expanded webapp status files",
            "first-slice missing=0 and unused=0",
            "webapp-slice missing=0 and unused=0",
            "Python package harness now passes 91 tests",
        ):
            self.assertIn(snippet, normalized_tracker)

    def test_docs_keep_bounded_inflection_claims(self) -> None:
        target_paths = (
            "dev-docs/design/022-mf2-native-inflection.md",
            "dev-docs/tracker.md",
            "mf2/README.md",
            "mf2/conformance/README.md",
            "mf2/python/README.md",
            "mf2/javascript/README.md",
            "dev-docs/experiments/mf2-inflection/README.md",
        )
        positive_overclaim_patterns = (
            r"\ball inflection types\b[^.\n]*(?:complete|supported|covered|available)",
            r"\ball languages\b[^.\n]*(?:complete|supported|covered|available)",
            r"\ball Unicode/CLDR languages\b[^.\n]*(?:complete|supported|covered|available)",
            r"\bPolish\b[^.\n]*(?:supported|covered|complete)",
            r"\bpublic non-Java\b[^.\n]*(?:available|supported|promoted)",
        )
        boundary_context = re.compile(
            r"\b(not|without|blocked|backlog|boundary|guard|rejects?|scope)\b",
            re.IGNORECASE,
        )
        stale_broad_phrases = (
            "production-quality Java path",
            "The data is complete enough to use",
        )

        for relative_path in target_paths:
            text = (REPO_ROOT / relative_path).read_text(encoding="utf-8")
            for stale_phrase in stale_broad_phrases:
                self.assertNotIn(
                    stale_phrase,
                    text,
                    f"{relative_path} contains stale broad wording",
                )
            for pattern in positive_overclaim_patterns:
                for match in re.finditer(pattern, text, re.IGNORECASE):
                    sentence_start = text.rfind(".", 0, match.start())
                    prefix = text[
                        max(sentence_start + 1, match.start() - 240) : match.start()
                    ]
                    if boundary_context.search(prefix):
                        continue
                    self.fail(
                        f"{relative_path} has unbounded inflection claim: {match.group(0)!r}"
                    )

        design_note = (
            REPO_ROOT / "dev-docs/design/022-mf2-native-inflection.md"
        ).read_text(encoding="utf-8")
        tracker = (REPO_ROOT / "dev-docs/tracker.md").read_text(encoding="utf-8")
        experiment_readme = (
            REPO_ROOT / "dev-docs/experiments/mf2-inflection/README.md"
        ).read_text(encoding="utf-8")
        normalized_design_note = " ".join(design_note.split())
        normalized_tracker = " ".join(tracker.split())
        normalized_experiment_readme = " ".join(experiment_readme.split())
        self.assertIn(
            "checked Java/common V0 path",
            normalized_experiment_readme,
        )
        self.assertIn(
            "not a claim that all inflection types or all languages are covered",
            normalized_experiment_readme,
        )
        self.assertNotIn("The data is complete enough to use", design_note)
        self.assertIn(
            "source data is complete enough for a scoped generator audit and reviewed V0 fixture design",
            normalized_design_note,
        )
        self.assertIn(
            "not language-wide runtime coverage",
            normalized_design_note,
        )
        for snippet in (
            "Ninety-first bounded-claim wording audit",
            "positive universal claims",
            "all inflection types/languages, Polish support, complete locale/grammar coverage, or public non-Java runtime APIs",
            "Python package harness now passes 90 tests",
            "Ninety-second Java/common post-bounded-claim focused rerun",
            "passes 43 tests: 7 API-surface, 24 release-validator, and 12 binding-manifest",
            "One hundred seventeenth bundle source-map expected/actual diagnostic guard",
            "bundle source kind, target path, and source fixture filename drift",
            "expected and actual values before manifest/report validation",
            "One hundred eighteenth first-slice readiness release-boundary wording guard",
            "latest release-fixture readiness guards",
            "not all inflection types or public non-Java runtime APIs",
            "One hundred twelfth generated manifest/report claim-free guard",
            "bundle materializer stdout",
            "None of those release surfaces can carry runtime, support, coverage, or public-API claims",
            "One hundred thirteenth failed report-row diagnostic-only guard",
            "failed artifact rows stay fixed to `artifactId`, `kind`, `status`, `code`, and `message`",
            "not broad runtime or coverage claims",
            "One hundred fourteenth wrapper main pre-row stderr diagnostic guard",
            "wrapper-owned main failure lines",
            "without tracebacks or broad runtime, coverage, or public-API claims",
            "One hundred fifteenth package-local wrapper command-surface guard",
            "Python and JavaScript package-local `inflection-release` scripts",
            "delegate only to the shared conformance wrapper",
            "One hundred sixteenth public README command-surface wording guard",
            "wrapper command surfaces only",
            "included in the first review slice for release-fixture validation",
        ):
            self.assertIn(snippet, normalized_design_note)
        for snippet in (
            "Latest bounded-claim wording audit",
            "positive universal claims",
            "all inflection types/languages, Polish support, complete locale/grammar coverage, or public non-Java runtime APIs",
            "Python package harness now passes 90 tests",
            "Latest Java/common post-bounded-claim rerun",
            "passes 43 tests: 7 API-surface, 24 release-validator, and 12 binding-manifest",
            "Latest experiment README bounded wording guard",
            "dev-docs/experiments/mf2-inflection/README.md",
            "not a claim that all inflection types or all languages are covered",
            "Latest Russian audit bounded wording guard",
            "scoped generator audit and reviewed V0 fixture design",
            "not language-wide runtime coverage",
            "Latest stale broad-phrase docs guard",
            "rejects stale broad phrases across the guarded MF2 docs",
            "Latest Java/common source stale-phrase guard",
            "scans all main inflection Java sources",
            "without adding public API or changing the 43-test Java/common gate shape",
            "Latest bundle source-map expected/actual diagnostic guard",
            "bundle source kind, target path, and source fixture filename drift",
            "expected and actual values before manifest/report validation",
            "Latest first-slice readiness release-boundary wording guard",
            "latest release-fixture readiness guards",
            "review-readiness evidence for checked Java/common V0",
            "Latest release artifact locale-scope guard",
            "selected V0 release artifact locale set",
            "metadata/profile-only or unavailable locale artifacts",
            "Latest public README locale-scope wording guard",
            "root, conformance, Python, and JavaScript MF2 README surfaces",
            "package-local wrappers release-shape only",
            "Latest post-public-README release-wrapper refresh",
            "all still pass the fixed 35-artifact V0 release fixture gate with `artifacts=35 failed=0`",
            "adds no runtime inflection API",
            "Latest public README locale-list drift guard",
            "parses the selected and excluded locale lists",
            "actual release artifact locale set",
            "Latest release fixture bundle source-scope wording guard",
            "fixed to checked Java/common V0 fixture slices",
            "does not publish package-local runtime APIs",
            "Latest generated manifest/report claim-free guard",
            "bundle materializer stdout",
            "generated all-pass manifest/report JSON",
            "None of those release surfaces can carry runtime, support, coverage, or public-API claims",
            "Latest failed report-row diagnostic-only guard",
            "failed artifact rows",
            "cannot carry broad runtime or coverage claims",
            "Latest wrapper main pre-row stderr diagnostic guard",
            "wrapper-owned main failure lines",
            "without tracebacks or broad runtime, coverage, or public-API claims",
            "Latest package-local wrapper command-surface guard",
            "Python and JavaScript package-local `inflection-release` scripts",
            "delegate only to the shared conformance wrapper",
            "Latest public README command-surface wording guard",
            "wrapper command surfaces only",
            "included in the first review slice for release-fixture validation",
            "not package-local runtime APIs",
        ):
            self.assertIn(snippet, normalized_tracker)

    def release_artifact_failure_codes(self, text: str) -> set[str]:
        return set(
            re.findall(
                r"`((?:invalid|unreadable)-[a-z0-9-]+)`",
                text,
            )
        )

    def documented_release_locale_sets(
        self,
        text: str,
        label: str,
    ) -> tuple[set[str], set[str]]:
        normalized = " ".join(text.split())
        selected_match = re.search(
            r"The selected release artifact locales are (?P<locales>.*?)(?:\. Metadata/profile-only|; metadata/profile-only)",
            normalized,
        )
        excluded_match = re.search(
            r"(?:Metadata|metadata)/profile-only or unavailable locales such as (?P<locales>.*?)(?: are intentionally excluded| are excluded)",
            normalized,
        )
        self.assertIsNotNone(selected_match, label)
        self.assertIsNotNone(excluded_match, label)
        assert selected_match is not None
        assert excluded_match is not None
        return (
            set(re.findall(r"`([a-z]+)`", selected_match.group("locales"))),
            set(re.findall(r"`([a-z]+)`", excluded_match.group("locales"))),
        )

    def design_runtime_coverage_locale_sets(
        self,
        text: str,
    ) -> tuple[set[str], set[str], str]:
        normalized = " ".join(text.split())
        match = re.search(
            r"\| Locale runtime coverage \| Java/common render validation for (?P<runtime>.*?); metadata/profile validation for (?P<metadata>.*?) \| (?P<backlog>.*?) \|",
            normalized,
        )
        self.assertIsNotNone(match, "design locale runtime coverage table row")
        assert match is not None
        return (
            set(re.findall(r"`([a-z]+)`", match.group("runtime"))),
            set(re.findall(r"`([a-z]+)`", match.group("metadata"))),
            match.group("backlog"),
        )

    def test_inflection_release_wrapper_rejects_swapped_bundle_source(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        specs = self.release_wrapper_artifact_specs(wrapper)
        specs[0] = SimpleNamespace(
            artifact_id="ar-approved-json",
            kind="compiled-term-pack-json",
            path="artifacts/ar-approved.json",
            source="it_compiled_article_pack_fixture.json",
        )

        with self.assertRaisesRegex(
            ValueError,
            "Unexpected release fixture source for ar-approved-json",
        ):
            wrapper.validate_bundle_artifact_specs(specs)

    def test_inflection_release_wrapper_main_rejects_missing_bundle_script(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            wrapper.BUNDLE_SCRIPT = Path(tmp) / "missing-release-fixture-bundle.py"
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, wrapper.main())

        self.assertIn("missing bundle script", stderr.getvalue())

    def test_inflection_release_wrapper_main_reports_bundle_script_failure(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            script = Path(tmp) / "release_fixture_bundle.py"
            script.write_text(
                "import sys\n"
                "print('fixture bundle failed', file=sys.stderr)\n"
                "sys.exit(7)\n",
                encoding="utf-8",
            )
            wrapper.BUNDLE_SCRIPT = script
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, wrapper.main())

        self.assertIn("fixture bundle failed", stderr.getvalue())
        self.assertIn("release_fixture_bundle.py exited 7", stderr.getvalue())

    def test_inflection_release_wrapper_main_failure_stderr_stays_diagnostic_only(
        self,
    ) -> None:
        forbidden_claim_phrases = (
            "all languages",
            "all inflection",
            "complete locale",
            "complete grammar",
            "language-wide runtime",
            "locale-wide runtime",
            "runtime coverage",
            "runtime promotion",
            "package-local runtime",
            "public api",
            "public-api",
        )
        wrapper_prefix = "Inflection release fixture validation failed: "
        collected_wrapper_lines = []

        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            wrapper.BUNDLE_SCRIPT = Path(tmp) / "missing-release-fixture-bundle.py"
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, wrapper.main())

            collected_wrapper_lines.extend(
                line
                for line in stderr.getvalue().splitlines()
                if wrapper_prefix in line
            )

        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            script = Path(tmp) / "release_fixture_bundle.py"
            script.write_text(
                "import sys\n"
                "print('fixture bundle failed', file=sys.stderr)\n"
                "sys.exit(7)\n",
                encoding="utf-8",
            )
            wrapper.BUNDLE_SCRIPT = script
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, wrapper.main())

            collected_wrapper_lines.extend(
                line
                for line in stderr.getvalue().splitlines()
                if wrapper_prefix in line
            )

        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            script = Path(tmp) / "release_fixture_bundle.py"
            script.write_text("", encoding="utf-8")
            wrapper.BUNDLE_SCRIPT = script
            wrapper.run_bundle = lambda _out_dir: None
            wrapper.validate_bundle_script_artifact_specs = lambda: None

            def reject_materialized_bundle(_out_dir):
                raise ValueError(
                    "Expected object: release-validation-manifest.json"
                )

            wrapper.validate_materialized_bundle = reject_materialized_bundle
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, wrapper.main())

            collected_wrapper_lines.extend(
                line
                for line in stderr.getvalue().splitlines()
                if wrapper_prefix in line
            )

        self.assertEqual(3, len(collected_wrapper_lines))
        for line in collected_wrapper_lines:
            self.assertTrue(line.startswith(wrapper_prefix), line)
            self.assertNotIn("Traceback", line)
            normalized_line = line.lower()
            for phrase in forbidden_claim_phrases:
                self.assertNotIn(phrase, normalized_line)

    def test_inflection_release_bundle_rejects_symlinked_output_artifacts(self) -> None:
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-bundle-test-") as tmp:
            root_dir = Path(tmp)
            base_dir = root_dir / "bundle"
            outside_dir = root_dir / "outside"
            base_dir.mkdir()
            outside_dir.mkdir()
            (base_dir / "artifacts").symlink_to(outside_dir, target_is_directory=True)

            result = subprocess.run(
                ["python3", str(RELEASE_FIXTURE_BUNDLE), "--out-dir", str(base_dir)],
                cwd=REPO_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn(
                "Release fixture target path must stay under output directory",
                result.stderr,
            )
            self.assertFalse((outside_dir / "ar-approved.json").exists())

    def test_inflection_release_wrapper_rejects_missing_manifest_file(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-manifest.json").unlink()

            with self.assertRaisesRegex(
                FileNotFoundError,
                "Missing required release fixture file: release-validation-manifest.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_manifest_top_level_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-manifest.json").write_text(
                "[]\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected object: release-validation-manifest.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_invalid_manifest_json(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-manifest.json").write_text(
                "{\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Invalid JSON in release fixture file release-validation-manifest.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_invalid_manifest_utf8(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-manifest.json").write_bytes(b"\xff")

            with self.assertRaisesRegex(
                ValueError,
                "Invalid UTF-8 in release fixture file release-validation-manifest.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_extra_manifest_top_level_keys(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            manifest_path = base_dir / "release-validation-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["generatedAt"] = "unexpected"
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Unexpected keys in release-validation-manifest\.json: \['generatedAt'\]",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_swapped_manifest_schema(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_schema="mojito-mf2-inflection/release-validation-manifest/v1",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Unexpected release-validation-manifest.json schema",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_manifest_artifact_count_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                omitted_manifest_artifact_ids={"ar-approved-json"},
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected 35 release-validation-manifest.json artifacts",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_manifest_artifacts_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            manifest_path = base_dir / "release-validation-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["artifacts"] = {}
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected array: release-validation-manifest\.json\.artifacts",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_manifest_artifact_row_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            manifest_path = base_dir / "release-validation-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["artifacts"][0] = []
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected object: release-validation-manifest\.json\.artifacts",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_blank_manifest_artifact_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "artifactId": "",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected non-empty text: release-validation-manifest"
                r"\.json\.artifacts\[\]\.artifactId",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_blank_manifest_artifact_kind(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "kind": "",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected non-empty text: release-validation-manifest"
                r"\.json\.artifacts\[ar-approved-json\]\.kind",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_blank_manifest_artifact_path(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "path": "",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected non-empty text: release-validation-manifest"
                r"\.json\.artifacts\[ar-approved-json\]\.path",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_extra_manifest_artifact_keys(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "checksum": "unexpected",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Unexpected keys in release-validation-manifest\.json"
                r"\.artifacts\[ar-approved-json\]: \['checksum'\]",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_swapped_manifest_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "artifactId": "ar-approved-json-drift",
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn("Unexpected release-validation-manifest.json artifact IDs", message)
        self.assertIn("missing=['ar-approved-json']", message)
        self.assertIn("unexpected=['ar-approved-json-drift']", message)
        self.assertIn("duplicates=[]", message)

    def test_inflection_release_wrapper_rejects_manifest_order_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            manifest_path = base_dir / "release-validation-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["artifacts"][0], manifest["artifacts"][1] = (
                manifest["artifacts"][1],
                manifest["artifacts"][0],
            )
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Unexpected release-validation-manifest.json artifact order",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_duplicate_manifest_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-review-required-json": {
                        "artifactId": "ar-approved-json",
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn("Unexpected release-validation-manifest.json artifact IDs", message)
        self.assertIn("missing=['ar-review-required-json']", message)
        self.assertIn("unexpected=[]", message)
        self.assertIn("duplicates=['ar-approved-json']", message)

    def test_inflection_release_wrapper_rejects_swapped_manifest_kind(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "kind": wrapper.COMPILED_TERM_PACK_M2IF,
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn(
            "Unexpected release-validation-manifest.json artifact kind for ar-approved-json",
            message,
        )
        self.assertIn("expected 'compiled-term-pack-json'", message)
        self.assertIn("got 'compiled-term-pack-m2if'", message)

    def test_inflection_release_wrapper_rejects_swapped_manifest_path(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                manifest_overrides={
                    "ar-approved-json": {
                        "path": "artifacts/ar-approved-swapped.json",
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn(
            "Unexpected release-validation-manifest.json artifact path for ar-approved-json",
            message,
        )
        self.assertIn("expected 'artifacts/ar-approved.json'", message)
        self.assertIn("got 'artifacts/ar-approved-swapped.json'", message)

    def test_inflection_release_wrapper_rejects_missing_materialized_artifact(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                omitted_materialized_artifact_ids={"ar-approved-json"},
            )

            with self.assertRaisesRegex(
                ValueError,
                "Missing release-validation-manifest.json materialized artifact",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_artifact_path_escape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            root_dir = Path(tmp)
            base_dir = root_dir / "bundle"
            outside_dir = root_dir / "outside"
            base_dir.mkdir()
            outside_dir.mkdir()
            self.write_release_wrapper_bundle(base_dir, wrapper)
            shutil.rmtree(base_dir / "artifacts")
            (outside_dir / "ar-approved.json").write_text("{}", encoding="utf-8")
            (base_dir / "artifacts").symlink_to(outside_dir, target_is_directory=True)

            with self.assertRaisesRegex(
                ValueError,
                "release-validation-manifest.json artifact escapes bundle directory",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_missing_report_file(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-report.json").unlink()

            with self.assertRaisesRegex(
                FileNotFoundError,
                "Missing required release fixture file: release-validation-report.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_report_top_level_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-report.json").write_text(
                "[]\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected object: release-validation-report.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_invalid_report_json(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-report.json").write_text(
                "{\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Invalid JSON in release fixture file release-validation-report.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_invalid_report_utf8(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            (base_dir / "release-validation-report.json").write_bytes(b"\xff")

            with self.assertRaisesRegex(
                ValueError,
                "Invalid UTF-8 in release fixture file release-validation-report.json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_extra_report_top_level_keys(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            report_path = base_dir / "release-validation-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["generatedAt"] = "unexpected"
            report_path.write_text(
                json.dumps(report, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Unexpected keys in release-validation-report\.json: \['generatedAt'\]",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_swapped_report_schema(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_schema="mojito-mf2-inflection/release-validation-report/v1",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Unexpected release-validation-report.json schema",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_report_summary_count_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_summary_overrides={
                    "artifacts": wrapper.EXPECTED_ARTIFACTS - 1,
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected 35 release-validation-report.json summary artifacts",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_report_summary_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            report_path = base_dir / "release-validation-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["summary"] = []
            report_path.write_text(
                json.dumps(report, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected object: release-validation-report\.json\.summary",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_extra_report_summary_keys(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            report_path = base_dir / "release-validation-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["summary"]["durationMs"] = 1
            report_path.write_text(
                json.dumps(report, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Unexpected keys in release-validation-report\.json"
                r"\.summary: \['durationMs'\]",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_report_artifact_count_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                omitted_report_artifact_ids={"ar-approved-json"},
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected 35 release-validation-report.json artifact rows",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_report_artifacts_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            report_path = base_dir / "release-validation-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["artifacts"] = {}
            report_path.write_text(
                json.dumps(report, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected array: release-validation-report\.json\.artifacts",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_report_artifact_row_shape(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            report_path = base_dir / "release-validation-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["artifacts"][0] = []
            report_path.write_text(
                json.dumps(report, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected object: release-validation-report\.json\.artifacts",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_swapped_report_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "artifactId": "ar-approved-json-drift",
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn("Unexpected release-validation-report.json artifact IDs", message)
        self.assertIn("missing=['ar-approved-json']", message)
        self.assertIn("unexpected=['ar-approved-json-drift']", message)
        self.assertIn("duplicates=[]", message)

    def test_inflection_release_wrapper_rejects_report_order_drift(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(base_dir, wrapper)
            report_path = base_dir / "release-validation-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["artifacts"][0], report["artifacts"][1] = (
                report["artifacts"][1],
                report["artifacts"][0],
            )
            report_path.write_text(
                json.dumps(report, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError,
                "Unexpected release-validation-report.json artifact order",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_duplicate_report_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-review-required-json": {
                        "artifactId": "ar-approved-json",
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn("Unexpected release-validation-report.json artifact IDs", message)
        self.assertIn("missing=['ar-review-required-json']", message)
        self.assertIn("unexpected=[]", message)
        self.assertIn("duplicates=['ar-approved-json']", message)

    def test_inflection_release_wrapper_rejects_blank_report_artifact_id(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "artifactId": "",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected non-empty text: release-validation-report"
                r"\.json\.artifacts\[\]\.artifactId",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_swapped_report_kind(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "kind": wrapper.COMPILED_TERM_PACK_M2IF,
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn(
            "Unexpected release-validation-report.json artifact kind for ar-approved-json",
            message,
        )
        self.assertIn("expected 'compiled-term-pack-json'", message)
        self.assertIn("got 'compiled-term-pack-m2if'", message)

    def test_inflection_release_wrapper_rejects_blank_report_artifact_kind(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "kind": "",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected non-empty text: release-validation-report"
                r"\.json\.artifacts\[ar-approved-json\]\.kind",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_blank_report_status(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "status": "",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Expected non-empty text: release-validation-report"
                r"\.json\.artifacts\[ar-approved-json\]\.status",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_failed_report_summary(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_summary_overrides={
                    "passed": wrapper.EXPECTED_ARTIFACTS - 1,
                    "failed": 1,
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected all release-validation-report.json artifacts to pass",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_failed_report_status(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "status": "failed",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                "Expected passed release-validation-report.json artifact "
                "for ar-approved-json",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def test_inflection_release_wrapper_rejects_passed_report_error_fields(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "code": "invalid-compiled-term-pack-json",
                        "message": "stale failure metadata",
                    }
                },
            )

            with self.assertRaises(ValueError) as error:
                wrapper.validate_materialized_bundle(base_dir)

        message = str(error.exception)
        self.assertIn("Passed release-validation-report.json artifact ar-approved-json", message)
        self.assertIn("must not carry error fields", message)
        self.assertIn("code", message)
        self.assertIn("message", message)

    def test_inflection_release_wrapper_rejects_extra_report_artifact_keys(self) -> None:
        wrapper = self.load_release_fixture_wrapper()
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-wrapper-test-") as tmp:
            base_dir = Path(tmp)
            self.write_release_wrapper_bundle(
                base_dir,
                wrapper,
                report_overrides={
                    "ar-approved-json": {
                        "durationMs": "1",
                    }
                },
            )

            with self.assertRaisesRegex(
                ValueError,
                r"Unexpected keys in release-validation-report\.json"
                r"\.artifacts\[ar-approved-json\]: \['durationMs'\]",
            ):
                wrapper.validate_materialized_bundle(base_dir)

    def validate_single_artifact(
        self,
        base_dir: Path,
        artifact_path: str,
        kind: str,
    ) -> dict:
        manifest_path = base_dir / "release-validation-manifest.json"
        manifest_path.write_text(
            json.dumps(
                {
                    "schema": "mojito-mf2-inflection/release-validation-manifest/v0",
                    "artifacts": [
                        {
                            "artifactId": "invalid-artifact",
                            "kind": kind,
                            "path": artifact_path,
                        }
                    ],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            [
                "python3",
                str(RELEASE_VALIDATION),
                "--manifest",
                str(manifest_path),
                "--base-dir",
                str(base_dir),
                "--allow-failures",
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual("", result.stderr)
        self.assertEqual(0, result.returncode, result.stdout)
        return json.loads(result.stdout)

    def load_release_fixture_wrapper(self):
        spec = importlib.util.spec_from_file_location(
            "validate_inflection_release_fixture",
            RELEASE_FIXTURE_WRAPPER,
        )
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def release_wrapper_artifact_specs(self, wrapper):
        return [
            SimpleNamespace(
                artifact_id=artifact_id,
                kind=kind,
                path=path,
                source=wrapper.EXPECTED_ARTIFACT_SOURCES_BY_ID[artifact_id],
            )
            for artifact_id, (kind, path) in wrapper.EXPECTED_ARTIFACTS_BY_ID.items()
        ]

    def replace_first_bytes(self, payload: bytes, old: bytes, new: bytes) -> bytes:
        self.assertEqual(len(old), len(new))
        index = payload.find(old)
        self.assertNotEqual(-1, index)
        return payload[:index] + new + payload[index + len(old) :]

    def flatten_manifest_surface(self, value):
        if isinstance(value, dict):
            for key in sorted(value):
                yield str(key)
                yield from self.flatten_manifest_surface(value[key])
        elif isinstance(value, list):
            for item in value:
                yield from self.flatten_manifest_surface(item)
        else:
            yield str(value)

    def write_release_wrapper_bundle(
        self,
        base_dir: Path,
        wrapper,
        manifest_overrides: dict[str, dict[str, str]] | None = None,
        report_overrides: dict[str, dict[str, str]] | None = None,
        report_summary_overrides: dict[str, int] | None = None,
        manifest_schema: str | None = None,
        report_schema: str | None = None,
        omitted_manifest_artifact_ids: set[str] | None = None,
        omitted_report_artifact_ids: set[str] | None = None,
        omitted_materialized_artifact_ids: set[str] | None = None,
    ) -> None:
        manifest_overrides = manifest_overrides or {}
        report_overrides = report_overrides or {}
        report_summary_overrides = report_summary_overrides or {}
        omitted_manifest_artifact_ids = omitted_manifest_artifact_ids or set()
        omitted_report_artifact_ids = omitted_report_artifact_ids or set()
        omitted_materialized_artifact_ids = omitted_materialized_artifact_ids or set()
        artifacts = []
        report_artifacts = []
        for artifact_id, (kind, path) in wrapper.EXPECTED_ARTIFACTS_BY_ID.items():
            artifact_path = base_dir / path
            artifact_path.parent.mkdir(parents=True, exist_ok=True)
            if artifact_id not in omitted_materialized_artifact_ids:
                artifact_path.write_text("{}", encoding="utf-8")
            if artifact_id not in omitted_manifest_artifact_ids:
                manifest_row = {
                    "artifactId": artifact_id,
                    "kind": kind,
                    "path": path,
                }
                manifest_row.update(manifest_overrides.get(artifact_id, {}))
                artifacts.append(manifest_row)
            if artifact_id not in omitted_report_artifact_ids:
                report_row = {
                    "artifactId": artifact_id,
                    "kind": kind,
                    "status": "passed",
                }
                report_row.update(report_overrides.get(artifact_id, {}))
                report_artifacts.append(report_row)

        (base_dir / "release-validation-manifest.json").write_text(
            json.dumps(
                {
                    "schema": manifest_schema or wrapper.EXPECTED_MANIFEST_SCHEMA,
                    "artifacts": artifacts,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        summary = {
            "artifacts": wrapper.EXPECTED_ARTIFACTS,
            "passed": wrapper.EXPECTED_ARTIFACTS,
            "failed": 0,
        }
        summary.update(report_summary_overrides)
        (base_dir / "release-validation-report.json").write_text(
            json.dumps(
                {
                    "schema": report_schema or wrapper.EXPECTED_REPORT_SCHEMA,
                    "summary": summary,
                    "artifacts": report_artifacts,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
