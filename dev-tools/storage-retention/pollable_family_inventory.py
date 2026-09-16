#!/usr/bin/env python3
"""Bounded, payload-free, read-only inventory of explicitly selected task families.

Python 3 + mysql CLI only. No task population is selected implicitly. This tool
never changes schema/data and is not an archive/deletion eligibility authority.
"""

import argparse
import collections
import dataclasses
import datetime as dt
import json
import pathlib
import queue
import re
import subprocess
import threading
import time
import uuid


REVIEW_NAMES = (
    "com.box.l10n.mojito.service.oaireview.AiReviewChatJob",
    "com.box.l10n.mojito.service.oaireview.AiReviewConfiguredChatJob",
)
QUEUE_NAME = "com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob"
USAGE = ("ai_review_request_usage", "pollable_task_id")
INTERNAL_EDGE = ("pollable_task", "parent_task_id")
EXPECTED_EXTERNAL = {
    ("asset_extraction", "pollable_task_id"),
    ("drop", "import_pollable_task_id"), ("drop", "export_pollable_task_id"),
    ("tm_xliff", "export_pollable_task_id"),
    ("ai_translate_run", "pollable_task_id"),
    ("ai_translate_text_unit_attempt", "pollable_task_id"),
    ("term_index_refresh_run", "pollable_task_id"),
    ("term_index_automation_run", "pollable_task_id"),
    ("bulk_import_run", "pollable_task_id"),
}


class BoundReached(RuntimeError):
    pass


class ReadFailed(RuntimeError):
    pass


def positive_id(value):
    value = str(value)
    if not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > 9223372036854775807:
        raise argparse.ArgumentTypeError("Task IDs must be positive signed 64-bit integers")
    return int(value)


def identifier(value):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]*", value):
        raise ReadFailed("Unsupported database identifier")
    return "`" + value + "`"


class MysqlReadOnly:
    """One bounded consistent snapshot; closing/interrupting it rolls back reads."""

    def __init__(self, args):
        command = [args.mysql, "--no-defaults"]
        if args.login_path:
            command.append("--login-path=" + args.login_path)
        if args.socket:
            command += ["--protocol=SOCKET", "--socket=" + args.socket]
        else:
            command += ["--protocol=TCP", "--host=" + args.host, "--port=" + str(args.port)]
        if args.user:
            command.append("--user=" + args.user)
        command += ["--database=" + args.database, "--connect-timeout=5", "--batch", "--raw",
                    "--skip-column-names", "--unbuffered"]
        self.deadline = time.monotonic() + args.max_seconds
        self.query_seconds = args.query_timeout_ms / 1000 + 1
        self.max_queries = args.max_queries
        self.queries = 0
        self.sql_log = []
        self.lines = queue.Queue()
        self.errors = []
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, bufsize=1)
        threading.Thread(target=self._read_lines, daemon=True).start()
        threading.Thread(target=self._read_errors, daemon=True).start()
        try:
            self._execute(
                "SET SESSION time_zone='+00:00';\n"
                "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;\n"
                f"SET SESSION MAX_EXECUTION_TIME={args.query_timeout_ms};\n"
                "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;", count=False)
        except Exception:
            self.close()
            raise

    def _read_lines(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\n"))
        self.lines.put(None)

    def _read_errors(self):
        for line in self.process.stderr:
            if sum(len(e) for e in self.errors) < 2048:
                self.errors.append(line)

    def _execute(self, sql, count=True):
        if time.monotonic() >= self.deadline:
            raise BoundReached("time_limit")
        if count and self.queries >= self.max_queries:
            raise BoundReached("query_limit")
        if count:
            self.queries += 1
            self.sql_log.append(sql)
        marker = "INVENTORY_" + uuid.uuid4().hex
        try:
            self.process.stdin.write(sql + f"\nSELECT '{marker}';\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            raise ReadFailed("mysql connection exited") from None
        query_deadline = min(self.deadline, time.monotonic() + self.query_seconds)
        rows = []
        while True:
            try:
                line = self.lines.get(timeout=max(0, query_deadline - time.monotonic()))
            except queue.Empty:
                raise BoundReached("time_limit" if time.monotonic() >= self.deadline
                                   else "query_time_limit") from None
            if line is None:
                # Queries contain metadata only. Keep diagnostic details out of the JSON report.
                raise ReadFailed("mysql query failed; check schema, permissions, and query limits")
            if line == marker:
                return [row.split("\t") for row in rows]
            rows.append(line)

    def query(self, sql):
        if not sql.startswith("SELECT ") or ";" in sql:
            raise ValueError("The inventory reader accepts one SELECT statement only")
        return self._execute(sql + ";")

    def close(self):
        if self.process.poll() is None:
            try:
                self.process.stdin.write("ROLLBACK;\n")
                self.process.stdin.close()
                self.process.wait(timeout=1)
            except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait(timeout=2)
        for stream in (self.process.stdout, self.process.stderr):
            stream.close()


@dataclasses.dataclass(frozen=True)
class Limits:
    max_family_rows: int = 100
    max_rows: int = 2000
    max_depth: int = 32
    max_roots: int = 100


class Inventory:
    def __init__(self, reader, cutoff, limits):
        self.reader = reader
        self.cutoff = cutoff
        self.limits = limits
        self.nodes = {}
        self.task_rows_read = 0
        self.external = []
        self.missing_external = []
        self.usage_indexed = False
        self.families = []
        self.resolutions = []
        self.global_bounds = []

    def _schema(self):
        keys = self.reader.query(
            "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
            "WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_SCHEMA=DATABASE() "
            "AND REFERENCED_TABLE_NAME='pollable_task' AND REFERENCED_COLUMN_NAME='id' "
            "ORDER BY TABLE_NAME,COLUMN_NAME LIMIT 101")
        if len(keys) > 100:
            raise BoundReached("foreign_key_inventory_limit")
        self.external = sorted(set(tuple(k) for k in keys) - {INTERNAL_EDGE})
        self.missing_external = sorted(EXPECTED_EXTERNAL - set(self.external))
        if INTERNAL_EDGE not in [tuple(k) for k in keys]:
            raise ReadFailed("Required pollable-task parent foreign key is absent")
        # Refuse an unindexed usage-history scan. Native FKs already require supporting indexes.
        self.usage_indexed = self.reader.query(
            "SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
            "AND TABLE_NAME='ai_review_request_usage' AND COLUMN_NAME='pollable_task_id' "
            "AND SEQ_IN_INDEX=1 LIMIT 1") == [["1"]]

    def _task_select(self, where, limit):
        review = ",".join("'" + name + "'" for name in REVIEW_NAMES)
        return (
            "SELECT p.id,COALESCE(p.parent_task_id,0),p.expected_sub_task_number,"
            "COALESCE(DATE_FORMAT(p.finished_date,'%Y-%m-%dT%H:%i:%s.%fZ'),'NULL'),"
            f"p.name IN ({review}),p.name='{QUEUE_NAME}',"
            "EXISTS(SELECT 1 FROM pollable_task c WHERE c.parent_task_id=p.id LIMIT 1) "
            f"FROM pollable_task p WHERE {where} ORDER BY p.id LIMIT {limit}")

    def _read_tasks(self, where, requested_limit):
        remaining = self.limits.max_rows - self.task_rows_read
        if remaining <= 0:
            raise BoundReached("global_task_row_limit")
        limit = min(remaining, requested_limit)
        rows = self.reader.query(self._task_select(where, limit))
        self.task_rows_read += len(rows)
        result = []
        for row in rows:
            task = {"id": int(row[0]), "parent_id": int(row[1]) or None,
                    "expected_children": int(row[2]), "finished_at": None if row[3] == "NULL" else row[3],
                    "review_type": row[4] == "1", "queue_type": row[5] == "1", "has_children": row[6] == "1"}
            self.nodes[task["id"]] = task
            result.append(task)
        return result, limit

    def _get(self, task_id):
        if task_id not in self.nodes:
            rows, _ = self._read_tasks(f"p.id={task_id}", 1)
            if not rows:
                return None
        return self.nodes[task_id]

    def _root(self, task_id, explicit_root):
        seen = set()
        current = task_id
        for depth in range(self.limits.max_depth + 1):
            if current in seen:
                return None, "ancestor_cycle"
            seen.add(current)
            task = self._get(current)
            if task is None:
                return None, "missing_task_or_ancestor"
            if explicit_root and task["parent_id"] is not None:
                return None, "supplied_root_has_parent"
            if task["parent_id"] is None:
                return current, None
            current = task["parent_id"]
        return None, "ancestor_depth_limit"

    def _family(self, root_id):
        family = {"root_id": root_id, "member_ids": [], "traversal_complete": False,
                  "references_complete": False, "truncations": [], "blockers": [],
                  "external_reference_members": {}, "soft_usage_member_ids": [],
                  "queue_payload_references": "not inspected; serialized payload has no task-ID index"}
        if self.missing_external:
            family["blockers"].append("foreign_key_reference_scope_unknown")
        self.families.append(family)
        pending = collections.deque([(root_id, 0)])
        discovered = {root_id}
        max_depth = 0
        try:
            while pending:
                task_id, depth = pending.popleft()
                task = self.nodes[task_id]
                family["member_ids"].append(task_id)
                max_depth = max(max_depth, depth)
                if not task["has_children"]:
                    continue
                if depth >= self.limits.max_depth:
                    family["truncations"].append("family_depth_limit")
                    break
                available = self.limits.max_family_rows - len(discovered)
                # Reserve one returned sentinel to detect an overlarge family without expanding it.
                children, limit = self._read_tasks(f"p.parent_task_id={task_id}", available + 1)
                if len(children) > available:
                    family["truncations"].append("family_row_limit")
                    children = children[:available]
                elif len(children) == limit:
                    # A global-row cap may have prevented the additional sentinel read.
                    family["truncations"].append("global_task_row_limit")
                    self.global_bounds.append("global_task_row_limit")
                    break
                for child in children:
                    if child["id"] in discovered:
                        family["truncations"].append("family_cycle_or_duplicate")
                        break
                    discovered.add(child["id"])
                    pending.append((child["id"], depth + 1))
                if family["truncations"]:
                    break
            if not family["truncations"]:
                family["traversal_complete"] = True
            # Include discovered but not expanded rows; never present their count as a full family.
            family["member_ids"] = sorted(discovered)
            family["maximum_depth_observed"] = max_depth
            tasks = [self.nodes[task_id] for task_id in family["member_ids"]]
            family["member_count_observed"] = len(tasks)
            family["unfinished_member_ids"] = [t["id"] for t in tasks if t["finished_at"] is None]
            family["too_recent_member_ids"] = [t["id"] for t in tasks if t["finished_at"] is not None
                                                and t["finished_at"] >= self.cutoff]
            family["review_type_member_ids"] = [t["id"] for t in tasks if t["review_type"]]
            family["queue_type_member_ids"] = [t["id"] for t in tasks if t["queue_type"]]
            if family["traversal_complete"]:
                child_counts = collections.Counter(t["parent_id"] for t in tasks)
                family["unsatisfied_expected_member_ids"] = [t["id"] for t in tasks
                    if child_counts[t["id"]] < t["expected_children"]]
            else:
                family["unsatisfied_expected_member_ids"] = None
            for field, reason in [("unfinished_member_ids", "unfinished"), ("too_recent_member_ids", "too_recent"),
                                  ("review_type_member_ids", "soft_review_type_excluded"),
                                  ("queue_type_member_ids", "soft_queue_type_excluded"),
                                  ("unsatisfied_expected_member_ids", "expected_children_missing")]:
                if family[field]:
                    family["blockers"].append(reason)
            id_list = ",".join(str(t["id"]) for t in tasks)
            for table, column in self.external:
                matches = self._reference_matches(table, column, id_list, len(tasks))
                if matches:
                    key = table + "." + column
                    family["external_reference_members"][key] = matches
                    family["blockers"].append("external:" + key)
            if self.usage_indexed:
                matches = self._reference_matches(*USAGE, id_list, len(tasks))
                family["soft_usage_member_ids"] = matches
                if matches:
                    family["blockers"].append("soft:ai_review_request_usage.pollable_task_id")
            else:
                family["blockers"].append("usage_reference_scope_unknown")
            family["references_complete"] = self.usage_indexed and not self.missing_external
        except BoundReached as error:
            family["truncations"].append(str(error))
            self.global_bounds.append(str(error))
        except ReadFailed:
            family["truncations"].append("query_failed")
            self.global_bounds.append("query_failed")
        family["truncations"] = sorted(set(family["truncations"]))
        family["member_ids"] = sorted(discovered)
        family["member_count_observed"] = len(discovered)
        family["appears_clear_under_inventory_policy"] = (
            family["traversal_complete"] and family["references_complete"]
            and not family["blockers"] and not family["truncations"])
        return family

    def _reference_matches(self, table, column, ids, count):
        rows = self.reader.query(
            f"SELECT p.id FROM pollable_task p WHERE p.id IN ({ids}) "
            f"AND EXISTS(SELECT 1 FROM {identifier(table)} r WHERE r.{identifier(column)}=p.id LIMIT 1) "
            f"ORDER BY p.id LIMIT {count}")
        return [int(row[0]) for row in rows]

    def run(self, task_ids, root_ids):
        self._schema()
        roots = {}
        for task_id, explicit_root in [(i, False) for i in task_ids] + [(i, True) for i in root_ids]:
            entry = {"supplied_id": task_id, "supplied_as": "root" if explicit_root else "task"}
            self.resolutions.append(entry)
            try:
                root_id, error = self._root(task_id, explicit_root)
                entry.update(root_id=root_id, error=error)
                if root_id is not None:
                    if root_id not in roots and len(roots) >= self.limits.max_roots:
                        entry.update(root_id=None, error="root_limit")
                        continue
                    roots.setdefault(root_id, []).append(task_id)
            except BoundReached as error:
                entry.update(root_id=None, error=str(error))
                self.global_bounds.append(str(error))
            except ReadFailed:
                entry.update(root_id=None, error="query_failed")
                self.global_bounds.append("query_failed")
        for root_id, supplied_ids in roots.items():
            family = self._family(root_id)
            family["supplied_ids"] = sorted(set(supplied_ids))
        reason_counts = collections.Counter()
        overlap_counts = collections.Counter()
        rows_by_reason = collections.Counter()
        for family in self.families:
            reasons = sorted(set(family["blockers"] + ["truncated:" + s for s in family["truncations"]]))
            if not family["references_complete"]:
                reasons = sorted(set(reasons + ["reference_inventory_incomplete"]))
            overlap_counts[tuple(reasons)] += 1
            for reason in reasons:
                reason_counts[reason] += 1
                rows_by_reason[reason] += family["member_count_observed"]
        return {
            "kind": "read-only bounded family/reference inventory; not deletion authorization",
            "status": "partial" if any(r.get("error") for r in self.resolutions) or any(
                f["truncations"] or not f["references_complete"] for f in self.families) else "complete",
            "finished_before_utc": self.cutoff, "limits": dataclasses.asdict(self.limits),
            "task_metadata_rows_returned": self.task_rows_read, "unique_task_metadata_rows": len(self.nodes),
            "queries": self.reader.queries, "known_external_foreign_key_columns": [".".join(k) for k in self.external],
            "usage_reference_index_present": self.usage_indexed,
            "expected_foreign_key_columns_missing": [".".join(k) for k in self.missing_external],
            "global_bounds_reached": sorted(set(self.global_bounds)), "input_resolution": self.resolutions,
            "families": self.families,
            "summary": {
                "distinct_roots_resolved": len(roots), "families_inspected": len(self.families),
                "complete_families": sum(f["traversal_complete"] for f in self.families),
                "families_clear_under_inventory_policy": sum(f["appears_clear_under_inventory_policy"] for f in self.families),
                "families_by_reason_overlapping": dict(sorted(reason_counts.items())),
                "observed_member_rows_by_reason_overlapping": dict(sorted(rows_by_reason.items())),
                "exclusive_reason_combinations": [{"reasons": list(reasons), "families": count}
                                                   for reasons, count in sorted(overlap_counts.items())],
            },
            "limitations": ["Explicitly selected roots/tasks are not a representative table sample.",
                            "Truncated family member counts are lower bounds, never full-family totals.",
                            "Reason counts overlap; only exclusive combinations can be summed.",
                            "Queue payloads are not selected or scanned; known queue task types are conservatively excluded.",
                            "This read-only snapshot does not fence a subsequent mutation or discover arbitrary serialized soft references."]}


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--mysql", default="mysql")
    p.add_argument("--login-path", help="Optional mysql_config_editor login path; credentials are never printed")
    endpoint = p.add_mutually_exclusive_group()
    endpoint.add_argument("--socket")
    endpoint.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--user")
    p.add_argument("--database", required=True)
    p.add_argument("--task-id", type=positive_id, action="append", default=[])
    p.add_argument("--root-id", type=positive_id, action="append", default=[])
    p.add_argument("--max-inputs", type=int, default=100)
    p.add_argument("--max-roots", type=int, default=100)
    p.add_argument("--max-family-rows", type=int, default=100)
    p.add_argument("--max-rows", type=int, default=2000)
    p.add_argument("--max-depth", type=int, default=32)
    p.add_argument("--max-queries", type=int, default=2000)
    p.add_argument("--max-seconds", type=float, default=60)
    p.add_argument("--query-timeout-ms", type=int, default=1000)
    p.add_argument("--retention-days", type=int, default=90)
    p.add_argument("--finished-before", help="Override cutoff, UTC format YYYY-MM-DDTHH:MM:SSZ")
    p.add_argument("--output", type=pathlib.Path, help="Optional local JSON report; otherwise stdout")
    return p


def validate_args(args):
    for key, maximum in {"max_inputs": 1000, "max_roots": 1000, "max_family_rows": 10000,
                         "max_rows": 20000, "max_depth": 100, "max_queries": 10000,
                         "query_timeout_ms": 5000, "max_seconds": 300, "retention_days": 36500}.items():
        if not 1 <= getattr(args, key) <= maximum:
            raise ValueError(f"{key.replace('_', '-')} must be between 1 and {maximum}")
    if not 1 <= args.port <= 65535:
        raise ValueError("port must be between 1 and 65535")
    if not args.task_id and not args.root_id:
        raise ValueError("Supply at least one explicit --task-id or --root-id")
    if len(args.task_id) + len(args.root_id) > args.max_inputs:
        raise ValueError("Explicit ID count exceeds max-inputs")
    cutoff = dt.datetime.strptime(args.finished_before, "%Y-%m-%dT%H:%M:%SZ") if args.finished_before else (
        dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=args.retention_days))
    return cutoff.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def main():
    p = parser()
    args = p.parse_args()
    try:
        cutoff = validate_args(args)
    except ValueError as error:
        p.error(str(error))
    reader = None
    try:
        reader = MysqlReadOnly(args)
        report = Inventory(reader, cutoff, Limits(args.max_family_rows, args.max_rows,
                                                  args.max_depth, args.max_roots)).run(args.task_id, args.root_id)
        exit_code = 0
    except (BoundReached, ReadFailed) as error:
        report = {"kind": "read-only family inventory", "status": "incomplete", "error": str(error)}
        exit_code = 2
    finally:
        if reader:
            reader.close()
    encoded = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded)
    else:
        print(encoded, end="")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
