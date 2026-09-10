import subprocess
import sys
import unittest

from check_official import check_case, normalized_parts, run_bridge


class OfficialHarnessTest(unittest.TestCase):
    def test_syntax_suite_outputs_are_real_assertions(self):
        checks = check_case({"src": "literal", "exp": "expected"}, {"value": "wrong", "errors": []})
        self.assertEqual(("expected", "wrong"), checks["output"])

    def test_errors_preserve_multiplicity_and_unexpected_errors(self):
        checks = check_case({"expErrors": [{"type": "bad-selector"}]}, {"errors": ["bad-selector", "bad-selector", "bad-operand"]})
        self.assertNotEqual(*checks["errors"])
        self.assertEqual(3, len(checks["errors"][1]))

    def test_parts_do_not_invent_resolved_options_or_numeric_subparts(self):
        actual = [{"type": "markup", "name": "x", "options": {"value": {"type": "variable", "name": "n"}}}]
        self.assertEqual(actual, normalized_parts(actual))
        checks = check_case({"expParts": [{"type": "number", "parts": [{"type": "integer", "value": "1"}]}]}, {"parts": [{"type": "expression", "value": "1"}]})
        self.assertNotEqual(*checks["parts"])

    def test_empty_or_crashed_bridge_never_becomes_a_skipped_case(self):
        for command in [[sys.executable, "-c", "pass"], [sys.executable, "-c", "raise SystemExit(2)"]]:
            with self.subTest(command=command), self.assertRaises(RuntimeError):
                run_bridge(command, [{}])

    def test_omitted_parts_errors_remains_a_failing_assertion(self):
        checks = check_case({"expParts": []}, {"parts": [], "errors": []})
        self.assertEqual(([], None), checks["partsErrors"])

    def test_incomplete_transport_response_is_rejected(self):
        with self.assertRaises(RuntimeError):
            run_bridge([sys.executable, "-c", "print('{}')"], [{}])

    def test_bridge_deadline_detects_nontermination(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            run_bridge([sys.executable, "-c", "import time; time.sleep(30)"], [{}], timeout=0.05)


class OfficialInventoryTest(unittest.TestCase):
    def setUp(self):
        import shutil
        import tempfile
        from pathlib import Path
        from official_inventory import VENDOR_ROOT
        self.temp = tempfile.TemporaryDirectory(prefix="mf2-official-inventory-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.vendor = self.root / "vendor"
        shutil.copytree(VENDOR_ROOT, self.vendor)

    def test_accepts_exact_revision_files_and_denominators(self):
        from official_inventory import validate_inventory
        result = validate_inventory(self.vendor)
        self.assertTrue(result["verified"])
        self.assertEqual(462, result["totals"]["tests"])
        self.assertEqual({"errors": 462, "output": 259, "parts": 20, "partsErrors": 20}, result["totals"]["assertions"])

    def test_deleting_or_modifying_an_all_pass_suite_fails(self):
        from official_inventory import validate_inventory
        path = self.vendor / "test/tests/syntax-errors.json"
        original = path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(ValueError, "missing=.*syntax-errors"):
            validate_inventory(self.vendor)
        path.write_bytes(original + b"\n")
        with self.assertRaisesRegex(ValueError, "changed=.*syntax-errors"):
            validate_inventory(self.vendor)

    def test_deleting_only_an_output_assertion_fails(self):
        import json
        from official_inventory import validate_inventory
        path = self.vendor / "test/tests/syntax.json"
        suite = json.loads(path.read_text())
        next(test for test in suite["tests"] if "exp" in test).pop("exp")
        path.write_text(json.dumps(suite))
        with self.assertRaisesRegex(ValueError, "changed=.*syntax.json"):
            validate_inventory(self.vendor)

    def test_extra_suite_and_symlink_replacement_fail(self):
        from official_inventory import validate_inventory
        path = self.vendor / "test/tests/extra.json"
        path.write_text('{"tests": []}')
        with self.assertRaisesRegex(ValueError, "extra=.*extra.json"):
            validate_inventory(self.vendor)
        path.unlink()
        original = self.vendor / "test/tests/syntax.json"
        moved = self.root / "syntax.json"
        original.rename(moved)
        original.symlink_to(moved)
        with self.assertRaisesRegex(ValueError, "symlink"):
            validate_inventory(self.vendor)

    def test_revision_and_manifest_denominator_mismatches_fail(self):
        import json
        from official_inventory import INVENTORY_PATH, validate_inventory
        manifest = json.loads(INVENTORY_PATH.read_text())
        manifest["upstream"]["revision"] = "0" * 40
        custom = self.root / "inventory.json"
        custom.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "revision"):
            validate_inventory(self.vendor, custom)
        manifest = json.loads(INVENTORY_PATH.read_text())
        manifest["totals"]["assertions"]["output"] -= 1
        custom.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "denominator"):
            validate_inventory(self.vendor, custom)

    def args(self, test_root, *, custom=False):
        from types import SimpleNamespace
        return SimpleNamespace(test_root=test_root, allow_custom_test_root=custom, command=["unused"],
                               registry="portable", runtime="synthetic", timeout=1,
                               dispositions=None, report=self.root / "report.json")

    def test_default_inventory_failure_occurs_before_bridge_execution(self):
        from unittest.mock import patch
        import check_official
        (self.vendor / "test/tests/syntax-errors.json").unlink()
        test_root = self.vendor / "test/tests"
        with patch.object(check_official, "DEFAULT_TEST_ROOT", test_root), patch.object(check_official, "VENDOR_ROOT", self.vendor), \
             patch.object(check_official, "run_bridge") as bridge:
            with self.assertRaisesRegex(ValueError, "inventory mismatch"):
                check_official.run(self.args(test_root))
            bridge.assert_not_called()
            with self.assertRaisesRegex(ValueError, "cannot disable"):
                check_official.run(self.args(test_root, custom=True))

    def test_custom_synthetic_root_requires_explicit_mode_and_is_labeled(self):
        import json
        from unittest.mock import patch
        from check_official import run
        test_root = self.root / "synthetic"
        test_root.mkdir()
        (test_root / "sample.json").write_text('{"tests":[{"src":"ok","exp":"ok"}]}')
        with patch("check_official.run_bridge", return_value=[{"diagnostics": [], "errors": [], "value": "ok"}]) as bridge:
            with self.assertRaisesRegex(ValueError, "requires --allow-custom-test-root"):
                run(self.args(test_root))
            bridge.assert_not_called()
            self.assertEqual(0, run(self.args(test_root, custom=True)))
        report = json.loads((self.root / "report.json").read_text())
        self.assertFalse(report["inventory"]["verified"])
        self.assertEqual(1, report["tests"])

    def test_harness_cannot_silently_drop_an_assertion(self):
        from unittest.mock import patch
        from check_official import run
        test_root = self.root / "synthetic"
        test_root.mkdir()
        (test_root / "sample.json").write_text('{"tests":[{"src":"ok","exp":"ok"}]}')
        with patch("check_official.run_bridge", return_value=[{"diagnostics": [], "errors": [], "value": "ok"}]), \
             patch("check_official.check_case", return_value={"errors": ([], [])}):
            with self.assertRaisesRegex(ValueError, "assertion coverage mismatch"):
                run(self.args(test_root, custom=True))


if __name__ == '__main__':
    unittest.main()
