#!/usr/bin/env python3
"""Render reviewed MySQL 8 replacement steps to a new local file; never connect or execute."""

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import uuid
from urllib.parse import urlsplit


def literal(value):
    """Hex literals avoid SQL modes, quoting, and command-interpolation hazards."""
    return "X'" + value.encode("utf-8").hex() + "'"


def integer(value, name, minimum=0, maximum=9223372036854775807):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"Invalid {name}")
    return value


def timestamp(value):
    # Application.mappingJackson2HttpMessageConverter writes Java Instant as integer
    # epoch milliseconds. Also accept explicit ISO timestamps from an ISO-configured mapper.
    if type(value) is int:
        integer(value, "epoch-millisecond timestamp", 0, 253402300799999)
        return dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc) + dt.timedelta(milliseconds=value)
    if not isinstance(value, str):
        raise ValueError("Readiness timestamps must be integer epoch milliseconds or ISO 8601 strings")
    result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("Readiness timestamps need an explicit timezone")
    return result.astimezone(dt.timezone.utc)


def validate_readiness(proof, now):
    if not isinstance(proof, dict) or not isinstance(proof.get("evidence"), dict):
        raise ValueError("Use the complete current /blob-promotions/{id}/readiness response")
    run = proof["evidence"]
    for field in ("id", "snapshotRunId"):
        if str(uuid.UUID(run[field])) != run[field]:
            raise ValueError("Invalid readiness run UUID")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", run["fenceId"]):
        raise ValueError("Invalid fence identity")
    if not re.fullmatch(r"[0-9a-f]{64}", run["manifestSha256"]):
        raise ValueError("Invalid manifest digest")
    destination = urlsplit(run["destinationRoot"])
    if (destination.scheme != "https" or not destination.hostname or destination.username
            or destination.password or destination.query or destination.fragment
            or not destination.path.endswith("/mblob_migration/v1/")):
        raise ValueError("Use the exact credential-free pinned snapshot destination root")
    for field in ("sourceHighWaterId", "reconciliationCursor", "reconciledCount",
                  "canonicalCount", "retainedCount", "retainedBytes"):
        integer(run[field], field)
    if (run["status"] != "RECONCILED" or run["phase"] != "COMPLETE"
            or run.get("leaseToken") is not None or run.get("lastError") is not None
            or run["reconciliationCursor"] != run["sourceHighWaterId"]
            or run["canonicalCount"] + run["retainedCount"] != run["reconciledCount"]):
        raise ValueError("Promotion must have completed its entire final source reconciliation")
    if timestamp(run["reconciledAt"]) > now + dt.timedelta(seconds=5):
        raise ValueError("Reconciliation is in the future")
    expiry = timestamp(proof["validUntil"])
    if not 35 < (expiry - now).total_seconds() <= 305:
        raise ValueError("Fetch fresh readiness: require 35 seconds remaining and at most 305 seconds")
    return run, int(expiry.timestamp())


def render(options, proof, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    run, expiry = validate_readiness(proof, now)
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", options.database):
        raise ValueError("Use an explicit simple MySQL database identifier")
    for name, prefix in ((options.replacement, "mblob_replacement_"), (options.backup, "mblob_before_")):
        if not re.fullmatch(prefix + r"[a-z0-9_]{1,24}", name):
            raise ValueError("Use unique mblob_replacement_/mblob_before_ names with short lowercase suffixes")
    rows = integer(options.max_rows, "max_rows", 1, 1000)
    byte_limit = integer(options.max_bytes, "max_bytes", 1, 1024 * 1024 * 1024)
    if options.phase not in ("prepare", "batch", "swap"):
        raise ValueError("Unknown replacement phase")
    target, backup = "`" + options.replacement + "`", "`" + options.backup + "`"
    state = "`" + options.replacement + "_state`"
    procedure = "`mblob_replacement_step_" + uuid.uuid4().hex + "`"
    temporary = "`mblob_replacement_ids_" + uuid.uuid4().hex + "`"
    lock = literal("mojito.mblob-replace." + run["id"])
    run_id = literal(run["id"])
    approval = "APPROVED " + run["id"] + " " + options.replacement + " " + options.backup

    def check(condition, message):
        return f"IF {condition} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '{message}'; END IF;"

    def schema_guard(table):
        name = literal(table)
        return check(f"(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = {name}) <> 5 OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = {name} AND ((column_name = 'id' AND data_type = 'bigint' AND extra = 'auto_increment') OR (column_name = 'created_date' AND data_type = 'datetime') OR (column_name = 'content' AND data_type = 'longblob') OR (column_name = 'expire_after_seconds' AND data_type = 'bigint') OR (column_name = 'name' AND data_type = 'varchar' AND character_maximum_length = 255))) <> 5",
                     "Source or replacement differs from the reviewed five-column mblob schema") + "\n    " + check(f"(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = {name} AND engine = 'InnoDB') <> 1",
                     "Source and replacement must be InnoDB base tables")

    def index_difference(left, right):
        return f"""EXISTS (SELECT 1 FROM information_schema.statistics s
          LEFT JOIN information_schema.statistics t ON t.table_schema = s.table_schema
            AND t.table_name = {literal(right)} AND t.index_name = s.index_name
            AND t.seq_in_index = s.seq_in_index
          WHERE s.table_schema = DATABASE() AND s.table_name = {literal(left)}
            AND (t.index_name IS NULL OR NOT ((s.non_unique <=> t.non_unique)
              AND (BINARY s.column_name <=> BINARY t.column_name) AND (s.collation <=> t.collation)
              AND (s.sub_part <=> t.sub_part) AND (s.index_type <=> t.index_type)
              AND (s.is_visible <=> t.is_visible) AND (BINARY s.expression <=> BINARY t.expression))))"""

    clone_guard = check(f"""EXISTS (SELECT 1 FROM information_schema.columns s
      LEFT JOIN information_schema.columns t ON t.table_schema = s.table_schema
        AND t.table_name = {literal(options.replacement)} AND t.column_name = s.column_name
      WHERE s.table_schema = DATABASE() AND s.table_name = 'mblob'
        AND (t.column_name IS NULL OR NOT ((s.ordinal_position <=> t.ordinal_position)
          AND (s.column_type <=> t.column_type) AND (s.is_nullable <=> t.is_nullable)
          AND (BINARY s.column_default <=> BINARY t.column_default) AND (s.extra <=> t.extra)
          AND (s.collation_name <=> t.collation_name)
          AND (BINARY s.generation_expression <=> BINARY t.generation_expression))))
      OR {index_difference('mblob', options.replacement)}
      OR {index_difference(options.replacement, 'mblob')}""",
      "Source and replacement column or index definitions differ")
    target_schema_guards = "" if options.phase == "prepare" else schema_guard(options.replacement) + "\n    " + clone_guard

    clock_guard = check(f"UNIX_TIMESTAMP(UTC_TIMESTAMP(6)) + 30 >= {expiry} OR {expiry} > UNIX_TIMESTAMP(UTC_TIMESTAMP(6)) + 305",
                        "Readiness proof expired or has an invalid clock; fetch readiness and render again")
    run_guard = check(f"(SELECT COUNT(*) FROM mblob_migration_promotion WHERE id = {run_id}\n"
        f" AND BINARY snapshot_run_id = BINARY {literal(run['snapshotRunId'])}\n"
        f" AND BINARY fence_id = BINARY {literal(run['fenceId'])}\n"
        f" AND BINARY manifest_sha256 = BINARY {literal(run['manifestSha256'])}\n"
        f" AND BINARY destination_root = BINARY {literal(run['destinationRoot'])}\n"
        f" AND status = 'RECONCILED' AND phase = 'COMPLETE' AND lease_token IS NULL\n"
        f" AND last_error IS NULL AND reconciled_at IS NOT NULL\n"
        f" AND source_high_water_id = {run['sourceHighWaterId']} AND reconciliation_cursor = {run['sourceHighWaterId']}\n"
        f" AND reconciled_count = {run['reconciledCount']} AND canonical_count = {run['canonicalCount']}\n"
        f" AND retained_count = {run['retainedCount']} AND retained_bytes = {run['retainedBytes']}) <> 1",
        "Readiness identity or durable promotion evidence changed")
    boundary_guard = check(f"COALESCE((SELECT MAX(id) FROM mblob), 0) <> {run['sourceHighWaterId']}\n"
                           " OR COALESCE((SELECT MIN(id) FROM mblob), 1) < 1", "Source ID boundary changed")
    conflict_guard = check(f"EXISTS (SELECT 1 FROM mblob_migration_promotion_item WHERE promotion_id = {run_id}\n"
        " AND reconciled = b'1' AND disposition IN ('RETAIN_CANONICAL_CONFLICT', 'RETAIN_RETENTION_CONFLICT'))",
        "Conflicting canonical content or retention requires a separately reviewed resolution")
    incomplete_guard = check(f"EXISTS (SELECT 1 FROM mblob_migration_promotion_item WHERE promotion_id = {run_id}\n"
        " AND disposition = 'CANONICAL_VERIFIED' AND reconciled = b'0')",
        "A promoted canonical row was not included in final source reconciliation")
    dependency_guard = check("EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE\n"
        " (table_schema = DATABASE() AND table_name = 'mblob' AND referenced_table_name IS NOT NULL)\n"
        " OR (referenced_table_schema = DATABASE() AND referenced_table_name = 'mblob'))\n"
        " OR EXISTS (SELECT 1 FROM information_schema.triggers WHERE event_object_schema = DATABASE() AND event_object_table = 'mblob')\n"
        " OR EXISTS (SELECT 1 FROM information_schema.view_table_usage WHERE table_schema = DATABASE() AND table_name = 'mblob')",
        "Source has dependencies requiring a separate reviewed replacement plan")
    state_guard = check(f"(SELECT COUNT(*) FROM {state} WHERE promotion_id = {run_id}\n"
                        f" AND manifest_sha256 = {literal(run['manifestSha256'])}) <> 1", "Replacement checkpoint identity mismatch")
    metadata_matches = """(BINARY b.name <=> BINARY e.source_name)
        AND (OCTET_LENGTH(b.content) <=> e.source_length)
        AND (b.created_date <=> e.source_created_date)
        AND (b.expire_after_seconds <=> e.source_expire_seconds)"""
    canonical = "BINARY e.disposition = BINARY 'CANONICAL_VERIFIED'"
    classification_guard = check(f"EXISTS (SELECT 1 FROM mblob b JOIN {temporary} k ON k.id = b.id\n"
        f" LEFT JOIN mblob_migration_promotion_item e ON e.promotion_id = {run_id} AND e.source_id = b.id\n"
        f" WHERE e.source_id IS NULL OR e.reconciled <> b'1' OR NOT ({metadata_matches})\n"
        f" OR NOT ({canonical} OR BINARY LEFT(e.disposition, 7) = BINARY 'RETAIN_')\n"
        f" OR (e.source_sha256 IS NOT NULL AND NOT (BINARY SHA2(b.content, 256) <=> BINARY e.source_sha256))\n"
        f" OR ({canonical} AND (b.expire_after_seconds IS NOT NULL OR e.source_sha256 IS NULL\n"
        " OR e.canonical_etag IS NULL OR e.verified_at IS NULL OR b.content IS NULL)))",
        "Source bytes, metadata, permanent retention, or final classification changed")
    count_guard = check(f"(SELECT COUNT(*) FROM mblob) <> {run['reconciledCount']}\n"
                        f" OR (SELECT COUNT(*) FROM {target}) <> {run['retainedCount']}", "Final source or replacement row count changed")
    # Source AUTO_INCREMENT can exceed MAX(id)+1 after deletions; preserve its reserved sequence.
    auto_increment = f"""SELECT GREATEST(COALESCE(auto_increment, 1), {run['sourceHighWaterId']} + 1) INTO v_auto
      FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mblob';
    SET @mblob_replacement_ddl = CONCAT('ALTER TABLE {target} AUTO_INCREMENT = ', v_auto);
    PREPARE mblob_replacement_ddl FROM @mblob_replacement_ddl;
    EXECUTE mblob_replacement_ddl;
    DEALLOCATE PREPARE mblob_replacement_ddl;"""

    if options.phase == "prepare":
        body = f"""{dependency_guard}
    {check(f"(SELECT COUNT(*) FROM mblob) <> {run['reconciledCount']}", "Final reconciliation does not cover every source row")}
    {check(f"EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ({literal(options.replacement)}, {literal(options.backup)}, {literal(options.replacement + '_state')}))", "Replacement or backup name already exists; inspect before recovery")}
    CREATE TABLE {target} LIKE mblob;
    {auto_increment}
    CREATE TABLE {state} (
      promotion_id varchar(36) NOT NULL PRIMARY KEY, manifest_sha256 varchar(64) NOT NULL,
      phase varchar(16) NOT NULL, copy_cursor bigint NOT NULL DEFAULT 0,
      verify_cursor bigint NOT NULL DEFAULT 0, scanned_count bigint NOT NULL DEFAULT 0,
      copied_count bigint NOT NULL DEFAULT 0, canonical_count bigint NOT NULL DEFAULT 0,
      verified_count bigint NOT NULL DEFAULT 0, verified_retained_bytes bigint NOT NULL DEFAULT 0,
      verified_at datetime(6), swapped_at datetime(6)
    ) ENGINE=InnoDB;
    {clock_guard}
    INSERT INTO {state} (promotion_id, manifest_sha256, phase) VALUES
      ({run_id}, {literal(run['manifestSha256'])}, 'COPY');"""
    elif options.phase == "batch":
        body = f"""{state_guard}
    START TRANSACTION;
    SELECT phase, IF(phase = 'COPY', copy_cursor, verify_cursor) INTO v_phase, v_cursor
      FROM {state} WHERE promotion_id = {run_id} FOR UPDATE;
    {check("v_phase NOT IN ('COPY', 'VERIFY')", "Copy and verification already finished or checkpoint is invalid")}
    CREATE TEMPORARY TABLE {temporary} (id bigint NOT NULL PRIMARY KEY) ENGINE=MEMORY;
    INSERT INTO {temporary} SELECT id FROM mblob WHERE id > v_cursor
      AND id <= {run['sourceHighWaterId']} ORDER BY id LIMIT {rows};
    SELECT COUNT(*), COALESCE(SUM(OCTET_LENGTH(b.content)), 0), COALESCE(MAX(b.id), v_cursor)
      INTO v_rows, v_bytes, v_last FROM mblob b JOIN {temporary} k ON k.id = b.id;
    {check(f"v_bytes > {byte_limit}", "Batch byte budget exceeded; reduce max_rows or review a larger max_bytes")}
    {classification_guard}
    SELECT COUNT(*) INTO v_retained FROM {temporary} k
      JOIN mblob_migration_promotion_item e ON e.promotion_id = {run_id} AND e.source_id = k.id
      WHERE NOT ({canonical});
    IF v_phase = 'COPY' THEN
      INSERT INTO {target} (id, created_date, content, expire_after_seconds, name)
        SELECT b.id, b.created_date, b.content, b.expire_after_seconds, b.name
        FROM mblob b JOIN {temporary} k ON k.id = b.id
        JOIN mblob_migration_promotion_item e ON e.promotion_id = {run_id} AND e.source_id = b.id
        WHERE NOT ({canonical});
      {check("ROW_COUNT() <> v_retained", "Retained row insert count mismatch")}
      UPDATE {state} SET copy_cursor = v_last, scanned_count = scanned_count + v_rows,
        copied_count = copied_count + v_retained, canonical_count = canonical_count + v_rows - v_retained
        WHERE promotion_id = {run_id};
      IF v_last = {run['sourceHighWaterId']} OR v_rows = 0 THEN
        {check(f"(SELECT COUNT(*) FROM {state} WHERE scanned_count = {run['reconciledCount']} AND copied_count = {run['retainedCount']} AND canonical_count = {run['canonicalCount']}) <> 1", "Copy coverage differs from final promotion evidence")}
        {count_guard}
        UPDATE {state} SET phase = 'VERIFY' WHERE promotion_id = {run_id};
      END IF;
    ELSE
      {check(f"EXISTS (SELECT 1 FROM mblob b JOIN {temporary} k ON k.id = b.id JOIN mblob_migration_promotion_item e ON e.promotion_id = {run_id} AND e.source_id = b.id LEFT JOIN {target} t ON t.id = b.id WHERE ({canonical} AND t.id IS NOT NULL) OR (NOT ({canonical}) AND (t.id IS NULL OR NOT ((BINARY b.name <=> BINARY t.name) AND (b.created_date <=> t.created_date) AND (b.expire_after_seconds <=> t.expire_after_seconds) AND (OCTET_LENGTH(b.content) <=> OCTET_LENGTH(t.content)) AND (BINARY SHA2(b.content, 256) <=> BINARY SHA2(t.content, 256))))))", "Replacement content, metadata, IDs, or omitted canonical rows failed verification")}
      SELECT COALESCE(SUM(OCTET_LENGTH(b.content)), 0) INTO v_retained_bytes
        FROM mblob b JOIN {temporary} k ON k.id = b.id
        JOIN mblob_migration_promotion_item e ON e.promotion_id = {run_id} AND e.source_id = b.id
        WHERE NOT ({canonical});
      UPDATE {state} SET verify_cursor = v_last, verified_count = verified_count + v_rows,
        verified_retained_bytes = verified_retained_bytes + v_retained_bytes
        WHERE promotion_id = {run_id};
      IF v_last = {run['sourceHighWaterId']} OR v_rows = 0 THEN
        {check(f"(SELECT COUNT(*) FROM {state} WHERE verified_count = {run['reconciledCount']} AND verified_retained_bytes = {run['retainedBytes']}) <> 1", "Verification coverage differs from final promotion evidence")}
        {count_guard}
        UPDATE {state} SET phase = 'VERIFIED', verified_at = UTC_TIMESTAMP(6) WHERE promotion_id = {run_id};
      END IF;
    END IF;
    {clock_guard}
    {boundary_guard}
    COMMIT;
    DROP TEMPORARY TABLE {temporary};"""
    else:
        body = f"""{check(f"NOT (BINARY @mojito_mblob_cutover_approval <=> BINARY {literal(approval)})", "Atomic rename requires separate explicit approval in this session")}
    {state_guard}
    {dependency_guard}
    {check(f"(SELECT COUNT(*) FROM {state} WHERE phase = 'VERIFIED' AND verified_at IS NOT NULL AND copy_cursor = {run['sourceHighWaterId']} AND verify_cursor = {run['sourceHighWaterId']} AND scanned_count = {run['reconciledCount']} AND verified_count = {run['reconciledCount']} AND copied_count = {run['retainedCount']} AND canonical_count = {run['canonicalCount']} AND verified_retained_bytes = {run['retainedBytes']}) <> 1", "Full replacement copy and verification are incomplete")}
    {count_guard}
    {check(f"EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = {literal(options.backup)})", "Backup table name already exists")}
    {auto_increment}
    {clock_guard}
    -- This DDL commits atomically and CANNOT be rolled back by the SQL exception handler.
    -- If any later statement fails, inspect the actual table names before retrying.
    RENAME TABLE mblob TO {backup}, {target} TO mblob;
    UPDATE {state} SET phase = 'SWAPPED', swapped_at = UTC_TIMESTAMP(6) WHERE promotion_id = {run_id};"""

    return f"""-- RENDERED ONLY: no connection, table operation, or approval was performed by the renderer.
-- Database: {options.database}; promotion: {run['id']}; phase: {options.phase}.
-- Fetch fresh /api/monitoring/blob-promotions/{run['id']}/readiness before executing EACH file.
-- The same externally enforced maintenance fence must remain continuously effective.
-- SQL validates proof age and DB evidence; it cannot verify Kubernetes/Azure/external writers.
-- Use a dedicated mysql connection, without --force. Review schema, disk headroom, grants,
-- dependencies and timings first. Payload batches are bounded; final COUNT scans are not.
-- A failed CALL leaves its uniquely named helper procedure for inspection; no source is deleted.
-- Swap only: after separately approved cutover, set this session variable BEFORE this file:
-- SET @mojito_mblob_cutover_approval = '{approval}';
-- No reverse rename or DROP-old-table is provided: see replacement/README.md rollback contract.
USE `{options.database}`;
SET SESSION time_zone = '+00:00';
SET SESSION lock_wait_timeout = 5;
SET SESSION innodb_lock_wait_timeout = 5;
SET SESSION information_schema_stats_expiry = 0;
DELIMITER //
CREATE PROCEDURE {procedure}()
SQL SECURITY INVOKER
BEGIN
    DECLARE v_locked integer DEFAULT 0;
    DECLARE v_phase varchar(16);
    DECLARE v_cursor bigint DEFAULT 0;
    DECLARE v_last bigint DEFAULT 0;
    DECLARE v_rows bigint DEFAULT 0;
    DECLARE v_bytes bigint DEFAULT 0;
    DECLARE v_retained bigint DEFAULT 0;
    DECLARE v_retained_bytes bigint DEFAULT 0;
    DECLARE v_auto bigint DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
      ROLLBACK;
      IF v_locked = 1 THEN DO RELEASE_LOCK({lock}); END IF;
      RESIGNAL;
    END;
    {check("VERSION() NOT LIKE '8.%'", "Replacement SQL is reviewed only for native MySQL 8")}
    SELECT GET_LOCK({lock}, 0) INTO v_locked;
    {check("v_locked IS NULL OR v_locked <> 1", "Another replacement operation owns this promotion")}
    {clock_guard}
    {run_guard}
    {conflict_guard}
    {incomplete_guard}
    {boundary_guard}
    {schema_guard('mblob')}
    {target_schema_guards}
    {body}
    SELECT * FROM {state};
    DO RELEASE_LOCK({lock});
END//
CALL {procedure}()//
DROP PROCEDURE {procedure}//
DELIMITER ;
"""


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate readiness JSON key")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("prepare", "batch", "swap"), required=True)
    for key in ("database", "replacement", "backup", "readiness", "output"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--max-rows", type=int, default=100)
    parser.add_argument("--max-bytes", type=int, default=64 * 1024 * 1024)
    options = parser.parse_args()
    proof = json.loads(Path(options.readiness).read_text(), object_pairs_hook=unique_object)
    sql = render(options, proof)
    with Path(options.output).open("x") as output:
        output.write(sql)
    print("Rendered only; no database connection, table operation, or cutover approval occurred.")


if __name__ == "__main__":
    main()
