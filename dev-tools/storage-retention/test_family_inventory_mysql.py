#!/usr/bin/env python3
"""Native MySQL tests for the read-only inventory; creates/drops its own local DB."""

import argparse
import datetime as dt
import json
import pathlib
import re
import time
import unittest
import uuid
from types import SimpleNamespace

from pollable_family_inventory import (
    BoundReached, Inventory, Limits, MysqlReadOnly, QUEUE_NAME, REVIEW_NAMES, positive_id,
)
from validate_mysql import Mysql, check, statements


OPTIONS = None


def prior_schema(repo):
    migration_dir = repo / "webapp/src/main/resources/db/migration"
    result = []
    for name in ["V1__Initial_Setup.sql", "V27__Add_tm_xliff.sql", "V50__Add_mblob.sql",
                 "V76__AI_Translate_Run_History.sql", "V88__AI_Translate_Text_Unit_Attempt.sql",
                 "V105__Bulk_Import_Lineage.sql", "V109__AI_Review_Request_Usage.sql"]:
        result.extend(statements((migration_dir / name).read_text()))
    for name, pattern in [
        ("V89__Glossary_Raw_Term_Index.sql", r"create table term_index_refresh_run\s*\("),
        ("V90__Glossary_Term_Index_Curation.sql", r"(?:alter table term_index_refresh_run\s|create unique index UK__TERM_INDEX_REFRESH_RUN__POLLABLE_TASK\s)"),
        ("V91__Term_Index_Default_Source_Locale.sql", r"(?:create table term_index_automation_run\s*\(|alter table term_index_automation_run\s|create (?:unique )?index \S+\s+on term_index_automation_run\s*\()"),
    ]:
        selected = [sql for sql in statements((migration_dir / name).read_text()) if re.match(pattern, sql, re.I)]
        check(selected, "Missing representative DDL: " + name)
        result.extend(selected)
    return "\n".join(result)


class NativeInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if OPTIONS is None:
            raise unittest.SkipTest("Run this script explicitly with a local socket")
        cls.database = "mojito_family_probe_" + uuid.uuid4().hex[:16]
        cls.artifact = OPTIONS.output / (dt.datetime.now(dt.timezone.utc).strftime("family-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
        cls.artifact.mkdir(parents=True)
        cls.admin = Mysql(OPTIONS.mysql, OPTIONS.socket, OPTIONS.user)
        cls.identity = cls.admin.value("SELECT @@hostname,@@port,@@version,@@socket,CURRENT_USER();")
        check(cls.identity.split("\t")[2].startswith("8."), "Native MySQL 8 required")
        check(cls.admin.value(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{cls.database}';") == "0", "Refusing existing database")
        cls.admin.run(f"CREATE DATABASE `{cls.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        cls.addClassCleanup(cls.drop_database)
        cls.mysql = Mysql(OPTIONS.mysql, OPTIONS.socket, OPTIONS.user, cls.database)
        schema = prior_schema(OPTIONS.repo.resolve())
        cls.mysql.run(schema)
        (cls.artifact / "schema.sql").write_text(schema)
        cls.mysql.run("INSERT INTO repository(id,name) VALUES(1,'family-probe');"
                      "INSERT INTO asset(id,content,path,repository_id) VALUES(1,'fixture','fixture.json',1);"
                      "INSERT INTO tm_text_unit(id,asset_id) VALUES(1,1);")
        tasks = [(1,None,1),(2,1,1),(3,2,0),(20,None,1),(21,20,0),(30,None,0),
                 (40,None,0),(50,None,0),(60,None,1),(70,None,7),(80,None,1),
                 (90,None,0),(91,None,0),(100,None,2),(101,100,0),(102,100,0),(110,None,0)]
        tasks += [(i,70,0) for i in range(71,78)]
        tasks += [(i,i-1,1 if i<85 else 0) for i in range(81,86)]
        for task_id,parent,expected in tasks:
            parent_sql = "NULL" if parent is None else str(parent)
            cls.mysql.run(f"INSERT INTO pollable_task(id,name,parent_task_id,expected_sub_task_number,finished_date,message) "
                          f"VALUES({task_id},'fixture',{parent_sql},{expected},'2025-01-01','DO_NOT_SELECT_PAYLOAD');")
        cls.mysql.run("UPDATE pollable_task SET finished_date=NULL WHERE id=21;"
                      "UPDATE pollable_task SET finished_date='2026-09-01' WHERE id=30;"
                      f"UPDATE pollable_task SET name='{REVIEW_NAMES[0]}' WHERE id=40;"
                      f"UPDATE pollable_task SET name='{QUEUE_NAME}' WHERE id=50;"
                      "UPDATE pollable_task SET parent_task_id=91 WHERE id=90;"
                      "UPDATE pollable_task SET parent_task_id=90 WHERE id=91;")
        cls.mysql.run("""INSERT INTO asset_extraction(pollable_task_id) VALUES(101);
INSERT INTO `drop`(import_pollable_task_id,export_pollable_task_id) VALUES(101,101);
INSERT INTO tm_xliff(asset_id,export_pollable_task_id) VALUES(1,101);
INSERT INTO ai_translate_run(trigger_source,repository_id,pollable_task_id,model,translate_type,related_strings_type,source_text_max_count_per_locale,status) VALUES('MANUAL',1,101,'fixture','ALL','NONE',1,'COMPLETED');
INSERT INTO ai_translate_text_unit_attempt(pollable_task_id,tm_text_unit_id,locale_id,request_group_id,translate_type,status) VALUES(101,1,1,'fixture','ALL','COMPLETED');
INSERT INTO term_index_refresh_run(status,pollable_task_id) VALUES('COMPLETED',101);
INSERT INTO term_index_automation_run(type,status,pollable_task_id) VALUES('GENERATE_CANDIDATES','COMPLETED',101);
INSERT INTO bulk_import_run(run_id,repository_id,asset_id,locale_id,pollable_task_id,actor_type,source,import_mode,integrity_checks_type,status,requested_count,imported_count,skipped_count) VALUES(UUID(),1,1,1,101,'SYSTEM','fixture','ALWAYS_IMPORT','SKIP','COMPLETED',0,0,0);
INSERT INTO ai_review_request_usage(pollable_task_id,surface,request_type,profile_id,model_name,status,started_at) VALUES(101,'fixture','fixture','fixture','fixture','COMPLETED','2025-01-01'),(110,'fixture','fixture','fixture','fixture','COMPLETED','2025-01-01');
""")
        cls.reports = []
        cls.query_logs = []
        cls.fingerprint = cls.mysql.value("SELECT id,parent_task_id,expected_sub_task_number,finished_date,HEX(message) FROM pollable_task ORDER BY id;")

    @classmethod
    def drop_database(cls):
        cls.admin.run(f"DROP DATABASE `{cls.database}`;")
        absent = cls.admin.value(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{cls.database}';") == "0"
        (cls.artifact / "database-cleanup.json").write_text(json.dumps({"database": cls.database, "dropped": absent}, indent=2) + "\n")
        check(absent, "Disposable database was not dropped")

    @classmethod
    def tearDownClass(cls):
        after = cls.mysql.value("SELECT id,parent_task_id,expected_sub_task_number,finished_date,HEX(message) FROM pollable_task ORDER BY id;")
        check(after == cls.fingerprint, "Inventory test changed original task data")
        (cls.artifact / "reports.json").write_text(json.dumps(cls.reports, indent=2) + "\n")
        (cls.artifact / "select-queries.json").write_text(json.dumps(cls.query_logs, indent=2) + "\n")
        (cls.artifact / "server-identity.txt").write_text(cls.identity + "\n")

    def reader(self, **overrides):
        args = SimpleNamespace(mysql=OPTIONS.mysql, socket=OPTIONS.socket, user=OPTIONS.user,
                               database=self.database, login_path=None, host="127.0.0.1", port=3306,
                               max_seconds=30, query_timeout_ms=1000, max_queries=500)
        for key,value in overrides.items():
            setattr(args,key,value)
        reader = MysqlReadOnly(args)
        self.addCleanup(reader.close)
        return reader

    def inventory(self, tasks=(), roots=(), limits=None, **overrides):
        reader = self.reader(**overrides)
        report = Inventory(reader, "2026-06-15T00:00:00.000000Z", limits or Limits()).run(list(tasks), list(roots))
        self.reports.append({"test": self.id(), "report": report})
        self.query_logs.extend(reader.sql_log)
        reader.close()
        self.assertTrue(all(sql.startswith("SELECT ") for sql in reader.sql_log))
        self.assertFalse(any(re.search(r"\b(message|error_message|error_stacks|job_data)\b", sql) for sql in reader.sql_log))
        return report

    def test_nested_family_deduplicates_child_and_root_inputs(self):
        report = self.inventory(tasks=[3,2], roots=[1])
        self.assertEqual(report["summary"]["distinct_roots_resolved"], 1)
        family = report["families"][0]
        self.assertEqual(family["member_ids"], [1,2,3])
        self.assertTrue(family["appears_clear_under_inventory_policy"])

    def test_all_nine_external_columns_and_soft_usage_with_correct_overlap(self):
        report = self.inventory(roots=[100])
        family = report["families"][0]
        self.assertEqual(len(family["external_reference_members"]), 9)
        self.assertTrue(all(ids == [101] for ids in family["external_reference_members"].values()))
        self.assertEqual(family["soft_usage_member_ids"], [101])
        summary = report["summary"]
        self.assertEqual(sum(summary["families_by_reason_overlapping"].values()), 10)
        self.assertEqual(sum(item["families"] for item in summary["exclusive_reason_combinations"]), 1)
        self.assertFalse(family["appears_clear_under_inventory_policy"])

    def test_soft_references_and_conservative_task_type_exclusions(self):
        report = self.inventory(roots=[40,50,110])
        reasons = [family["blockers"] for family in report["families"]]
        self.assertIn("soft_review_type_excluded", reasons[0])
        self.assertIn("soft_queue_type_excluded", reasons[1])
        self.assertIn("soft:ai_review_request_usage.pollable_task_id", reasons[2])
        self.assertTrue(all(not family["appears_clear_under_inventory_policy"] for family in report["families"]))

    def test_finished_parent_is_not_enough_and_expected_children_are_checked(self):
        report = self.inventory(roots=[20,30,60])
        self.assertIn("unfinished", report["families"][0]["blockers"])
        self.assertIn("too_recent", report["families"][1]["blockers"])
        self.assertIn("expected_children_missing", report["families"][2]["blockers"])

    def test_family_row_and_depth_limits_report_lower_bounds(self):
        row_report = self.inventory(roots=[70], limits=Limits(max_family_rows=3))
        family = row_report["families"][0]
        self.assertIn("family_row_limit", family["truncations"])
        self.assertLessEqual(family["member_count_observed"], 3)
        self.assertFalse(family["traversal_complete"])
        depth_report = self.inventory(roots=[80], limits=Limits(max_depth=2))
        self.assertIn("family_depth_limit", depth_report["families"][0]["truncations"])

    def test_global_row_query_and_root_limits(self):
        row_report = self.inventory(roots=[1,70], limits=Limits(max_rows=5))
        self.assertLessEqual(row_report["task_metadata_rows_returned"], 5)
        self.assertTrue(any("global_task_row_limit" in f["truncations"] for f in row_report["families"]))
        query_report = self.inventory(roots=[1], max_queries=2)
        self.assertLessEqual(query_report["queries"], 2)
        self.assertIn("query_limit", query_report["global_bounds_reached"])
        root_report = self.inventory(roots=[1,30], limits=Limits(max_roots=1))
        self.assertEqual(root_report["input_resolution"][1]["error"], "root_limit")

    def test_invalid_roots_cycles_missing_rows_and_climb_limit(self):
        report = self.inventory(tasks=[90,999,85], roots=[3], limits=Limits(max_depth=2))
        self.assertEqual([r["error"] for r in report["input_resolution"]],
                         ["ancestor_cycle", "missing_task_or_ancestor", "ancestor_depth_limit", "supplied_root_has_parent"])
        self.assertEqual(report["families"], [])

    def test_new_foreign_key_is_discovered_without_a_code_allowlist(self):
        self.mysql.run("CREATE TABLE inventory_future_reference(id BIGINT PRIMARY KEY,task_id BIGINT,FOREIGN KEY(task_id) REFERENCES pollable_task(id));INSERT INTO inventory_future_reference VALUES(1,3);")
        try:
            report = self.inventory(roots=[1])
            self.assertEqual(report["families"][0]["external_reference_members"]["inventory_future_reference.task_id"], [3])
        finally:
            self.mysql.run("DROP TABLE inventory_future_reference;")

    def test_missing_expected_foreign_key_marks_reference_scope_unknown(self):
        self.mysql.run("ALTER TABLE bulk_import_run DROP FOREIGN KEY FK__BULK_IMPORT_RUN__POLLABLE_TASK;")
        try:
            report = self.inventory(roots=[1])
            family = report["families"][0]
            self.assertEqual(report["expected_foreign_key_columns_missing"], ["bulk_import_run.pollable_task_id"])
            self.assertIn("foreign_key_reference_scope_unknown", family["blockers"])
            self.assertFalse(family["references_complete"])
            self.assertFalse(family["appears_clear_under_inventory_policy"])
        finally:
            self.mysql.run("ALTER TABLE bulk_import_run ADD CONSTRAINT FK__BULK_IMPORT_RUN__POLLABLE_TASK FOREIGN KEY(pollable_task_id) REFERENCES pollable_task(id);")

    def test_missing_usage_index_is_unknown_instead_of_unbounded_scan(self):
        self.mysql.run("DROP INDEX I__AIRRU__POLLABLE_TASK ON ai_review_request_usage;")
        try:
            report = self.inventory(roots=[1])
            family = report["families"][0]
            self.assertIn("usage_reference_scope_unknown", family["blockers"])
            self.assertFalse(family["references_complete"])
            self.assertFalse(family["appears_clear_under_inventory_policy"])
        finally:
            self.mysql.run("CREATE INDEX I__AIRRU__POLLABLE_TASK ON ai_review_request_usage(pollable_task_id);")

    def test_consistent_snapshot_does_not_include_later_child(self):
        reader = self.reader()
        inventory = Inventory(reader, "2026-06-15T00:00:00.000000Z", Limits())
        inventory._get(1)
        self.mysql.run("INSERT INTO pollable_task(id,name,parent_task_id,expected_sub_task_number,finished_date) VALUES(4,'later',1,0,'2025-01-01');")
        try:
            report = inventory.run([], [1])
            self.assertEqual(report["families"][0]["member_ids"], [1,2,3])
            self.assertEqual(self.mysql.value("SELECT COUNT(*) FROM pollable_task WHERE id=4;"), "1")
        finally:
            self.mysql.run("DELETE FROM pollable_task WHERE id=4;")

    def test_reader_rejects_mutations_and_honors_wall_clock_bound(self):
        reader = self.reader()
        with self.assertRaises(ValueError):
            reader.query("DELETE FROM pollable_task")
        with self.assertRaises(ValueError):
            reader.query("SELECT 1; SELECT 2")
        reader.deadline = time.monotonic() - 1
        with self.assertRaisesRegex(BoundReached, "time_limit"):
            reader.query("SELECT 1")
        for value in ["0", "-1", "1;DELETE", "9223372036854775808"]:
            with self.assertRaises(argparse.ArgumentTypeError):
                positive_id(value)


def main():
    global OPTIONS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--socket", default="/tmp/mysql.sock")
    parser.add_argument("--user", default="root")
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=pathlib.Path, required=True)
    OPTIONS = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(NativeInventoryTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if hasattr(NativeInventoryTest, "artifact"):
        report = {"kind": "native MySQL inventory integration tests; not archive/deletion tests",
                  "tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                  "status": "PASS" if result.wasSuccessful() else "FAIL",
                  "artifact": str(NativeInventoryTest.artifact)}
        (NativeInventoryTest.artifact / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
