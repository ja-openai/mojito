"""Fixture tests for CI report validation; these do not run queue or database code."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET

from verify_queue_reports import LANES, OPTIONAL_SKIPS, verify_reports


class QueueReportGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)

    def report(self, suite, groups):
        root = ET.Element(
            "testsuite", name=suite, failures="0", errors="0", skipped="0", flakes="0"
        )
        for group, count in groups.items():
            for index in range(count):
                name = f"case{index}" + (f"[{group}]" if group else "")
                ET.SubElement(root, "testcase", classname=suite, name=name)
        self.write(root)
        return root

    def write(self, root):
        root.set("tests", str(len(root.findall("testcase"))))
        root.set("skipped", str(len(root.findall("testcase/skipped"))))
        ET.ElementTree(root).write(self.path / f"TEST-{root.get('name')}.xml")

    def populate(self, lane="application"):
        return {suite: self.report(suite, groups) for suite, groups in LANES[lane].items()}

    def test_complete_lanes_pass(self):
        for lane in LANES:
            with self.subTest(lane=lane):
                self.populate(lane)
                expected = sum(sum(groups.values()) for groups in LANES[lane].values())
                self.assertEqual((expected, 0), verify_reports(self.path, lane))

    def test_only_named_optional_benchmark_may_skip(self):
        suite, name = next(iter(OPTIONAL_SKIPS))
        root = self.populate()[suite]
        case = root.find("testcase")
        case.set("name", name)
        ET.SubElement(case, "skipped")
        self.write(root)
        self.assertEqual((188, 1), verify_reports(self.path, "application"))
        case.set("name", "requiredContract")
        self.write(root)
        with self.assertRaisesRegex(ValueError, "required test skipped"):
            verify_reports(self.path, "application")

    def test_missing_suite_fails(self):
        suite = next(iter(self.populate()))
        (self.path / f"TEST-{suite}.xml").unlink()
        with self.assertRaisesRegex(ValueError, "missing or unreadable"):
            verify_reports(self.path, "application")

    def test_empty_before_class_assumption_report_fails(self):
        root = next(iter(self.populate().values()))
        for case in root.findall("testcase"):
            root.remove(case)
        self.write(root)
        with self.assertRaisesRegex(ValueError, "has 0 tests"):
            verify_reports(self.path, "application")

    def test_missing_database_group_fails_even_when_other_group_has_extra_tests(self):
        suite = "com.box.l10n.mojito.queue.AsyncJobQueueJpaTransactionIntegrationTest"
        self.populate()
        self.report(suite, {"HSQL": 70, "MYSQL": 35})
        with self.assertRaisesRegex(ValueError, "POSTGRESQL.*has 0 tests"):
            verify_reports(self.path, "application")

    def test_underfilled_or_unexpected_parameter_group_fails(self):
        suite = "com.box.l10n.mojito.queue.JdbcAsyncJobStoreNetworkIntegrationTest"
        for groups in ({"MYSQL": 3, "POSTGRESQL": 2}, {"MYSQL": 3, "OTHER": 3}):
            with self.subTest(groups=groups):
                self.populate()
                self.report(suite, groups)
                with self.assertRaisesRegex(ValueError, "group"):
                    verify_reports(self.path, "application")

    def test_duplicate_case_cannot_fill_matrix(self):
        root = next(iter(self.populate().values()))
        cases = root.findall("testcase")
        cases[1].set("name", cases[0].get("name"))
        self.write(root)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify_reports(self.path, "application")

    def test_failure_and_retry_elements_fail_even_with_successful_counters(self):
        for tag in (
            "failure", "error", "flakyFailure", "flakyError", "rerunFailure", "rerunError"
        ):
            with self.subTest(tag=tag):
                root = next(iter(self.populate().values()))
                ET.SubElement(root.find("testcase"), tag)
                self.write(root)
                with self.assertRaisesRegex(ValueError, "failed or automatically retried"):
                    verify_reports(self.path, "application")

    def test_invalid_or_inconsistent_counters_fail(self):
        for attribute, value in (
            ("tests", "0"), ("skipped", "1"), ("failures", "1"),
            ("errors", "1"), ("flakes", "1"), ("tests", "bad"),
        ):
            with self.subTest(attribute=attribute, value=value):
                root = next(iter(self.populate().values()))
                root.set(attribute, value)
                ET.ElementTree(root).write(self.path / f"TEST-{root.get('name')}.xml")
                with self.assertRaisesRegex(ValueError, "count"):
                    verify_reports(self.path, "application")

    def test_malformed_xml_fails(self):
        suite = next(iter(self.populate()))
        (self.path / f"TEST-{suite}.xml").write_text("<testsuite>")
        with self.assertRaisesRegex(ValueError, "missing or unreadable"):
            verify_reports(self.path, "application")

    def test_wrong_suite_or_case_identity_fails(self):
        for attribute in ("suite", "classname", "name"):
            with self.subTest(attribute=attribute):
                root = next(iter(self.populate().values()))
                path = self.path / f"TEST-{root.get('name')}.xml"
                if attribute == "suite":
                    root.set("name", "wrong")
                else:
                    root.find("testcase").set(attribute, "")
                ET.ElementTree(root).write(path)
                with self.assertRaises(ValueError):
                    verify_reports(self.path, "application")


if __name__ == "__main__":
    unittest.main()
