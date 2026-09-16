#!/usr/bin/env python3
"""Execute generated replacement SQL only in disposable random local MySQL 8 socket databases."""

import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

import render
from test_render import fixture, options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", default="/tmp/mysql.sock")
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--user", default="root")
    parser.add_argument("--output", type=Path, default=Path.home() / ".cache/mojito-storage-replacement-validation-20260914")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    artifact = args.output / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    artifact.mkdir(parents=True)
    command = [args.mysql, "--no-defaults", "--protocol=SOCKET", "--socket=" + args.socket,
               "--user=" + args.user, "--batch", "--raw", "--skip-column-names"]
    results = {"scope": "Disposable local SQL rehearsal; no Azure, application, staging, or performance proof",
               "status": "RUNNING", "probes": [], "artifact": str(artifact), "hashes": {}}
    for path in (Path(__file__), Path(render.__file__), root / "webapp/src/main/resources/db/migration/V121__MBlob_Migration.sql"):
        results["hashes"][str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()

    def sql(database, value, label=None, succeeds=True):
        if label:
            (artifact / (label + ".sql")).write_text(value)
        response = subprocess.run(command + ([database] if database else []), input=value,
                                  text=True, capture_output=True, timeout=30)
        if (response.returncode == 0) != succeeds:
            raise AssertionError((label or "SQL") + ": " + response.stderr + response.stdout)
        return response.stdout.strip()

    @contextmanager
    def scenario(label):
        database = "mojito_replacement_probe_" + uuid.uuid4().hex[:16]
        if not re.fullmatch(r"mojito_replacement_probe_[0-9a-f]{16}", database):
            raise AssertionError("Unsafe disposable database name")
        if sql(None, f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}';") != "0":
            raise AssertionError("Disposable database already exists")
        sql(None, f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        try:
            schema = (root / "webapp/src/main/resources/db/migration/V50__Add_mblob.sql").read_text()
            schema += (root / "webapp/src/main/resources/db/migration/V121__MBlob_Migration.sql").read_text()
            sql(database, schema, label + "-schema")
            proof = fixture()
            run = proof["evidence"]
            seed = f"""INSERT INTO mblob (id,created_date,content,expire_after_seconds,name) VALUES
              (1,'2026-09-01',X'6f6e65',NULL,'clob_storage_ws/one'),
              (5,'2026-09-04',X'74776f',2592000,'clob_storage_ws/temporary'),
              (9,'2026-09-01',X'666f7572',NULL,'ai_review_execution/v1/capacity'),
              (20,NULL,NULL,NULL,NULL),
              (100,'2026-09-01',X'736978736978',NULL,'unknown/keep');
            ALTER TABLE mblob AUTO_INCREMENT=10000;
            INSERT INTO mblob_migration_run
              (id,allowed_prefixes,destination_root,high_water_id,cursor_id,max_rows,max_bytes,max_seconds,max_retries,status)
              VALUES ('{run['snapshotRunId']}','clob_storage_ws/','{run['destinationRoot']}',100,100,100,1000,60,3,'SNAPSHOT_COMPLETE');
            INSERT INTO mblob_migration_promotion
              (id,snapshot_run_id,fence_id,manifest_sha256,destination_root,source_high_water_id,
               promotion_cursor,reconciliation_cursor,phase,status,reconciled_count,canonical_count,retained_count,
               retained_bytes,reconciled_at)
              VALUES ('{run['id']}','{run['snapshotRunId']}','{run['fenceId']}','{run['manifestSha256']}',
               '{run['destinationRoot']}',100,100,100,'COMPLETE','RECONCILED',5,1,4,13,UTC_TIMESTAMP(6));
            INSERT INTO mblob_migration_promotion_item
              (promotion_id,source_id,source_name,source_length,source_created_date,source_expire_seconds,
               source_sha256,canonical_etag,disposition,reconciled,verified_at)
              SELECT '{run['id']}',id,name,OCTET_LENGTH(content),created_date,expire_after_seconds,
                IF(id=1,SHA2(content,256),NULL),IF(id=1,'etag',NULL),
                IF(id=1,'CANONICAL_VERIFIED',IF(id=5,'RETAIN_TEMPORARY_LIFETIME','RETAIN_PRESERVED_UNKNOWN')),
                b'1',IF(id=1,UTC_TIMESTAMP(6),NULL) FROM mblob;"""
            sql(database, seed, label + "-seed")

            def step(phase, succeeds=True, prefix="", **changes):
                proof["validUntil"] = int((dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=250)).timestamp() * 1000)
                rendered = render.render(options(database=database, phase=phase, **changes), proof)
                return sql(database, prefix + rendered,
                           label + "-" + phase + "-" + uuid.uuid4().hex[:8], succeeds)

            yield database, proof, step
            results["probes"].append(label)
        finally:
            sql(None, f"DROP DATABASE `{database}`;")
            if sql(None, f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}';") != "0":
                raise AssertionError("Disposable database cleanup failed")

    try:
        results["server"] = sql(None, "SELECT @@version, @@socket, CURRENT_USER();")
        if not results["server"].split("\t")[0].startswith("8."):
            raise AssertionError("Native MySQL 8 is required")
        with scenario("copy-verify-approved-swap") as (database, proof, step):
            step("prepare")
            for _ in range(6):
                step("batch")
            state = sql(database, "SELECT phase,scanned_count,copied_count,verified_count,verified_retained_bytes FROM mblob_replacement_review_state;")
            assert state == "VERIFIED\t5\t4\t5\t13", state
            assert sql(database, "SELECT GROUP_CONCAT(id ORDER BY id) FROM mblob_replacement_review;") == "5,9,20,100"
            assert sql(database, "SELECT expire_after_seconds,created_date FROM mblob_replacement_review WHERE id=5;") == "2592000\t2026-09-04 00:00:00"
            step("swap", succeeds=False)
            assert sql(database, "SELECT COUNT(*) FROM mblob;") == "5"
            approval = "APPROVED " + proof["evidence"]["id"] + " mblob_replacement_review mblob_before_review"
            step("swap", prefix="SET @mojito_mblob_cutover_approval = '" + approval + "';\n")
            assert sql(database, "SELECT COUNT(*) FROM mblob_before_review;") == "5"
            assert sql(database, "SELECT COUNT(*) FROM mblob;") == "4"
            sql(database, "INSERT INTO mblob(name,content) VALUES('unknown/new',X'31');")
            assert sql(database, "SELECT id FROM mblob WHERE name='unknown/new';") == "10000"
            assert sql(database, "SELECT phase FROM mblob_replacement_review_state;") == "SWAPPED"

        for label, mutation in (
            ("source-digest-conflict", "UPDATE mblob SET content=X'626164' WHERE id=1;"),
            ("source-case-change", "UPDATE mblob SET name='clob_storage_ws/ONE' WHERE id=1;"),
            ("missing-classification", "DELETE FROM mblob_migration_promotion_item WHERE source_id=1;"),
            ("temporary-canonical-forbidden", "UPDATE mblob_migration_promotion_item SET disposition='CANONICAL_VERIFIED',source_sha256=SHA2(X'74776f',256),canonical_etag='bad',verified_at=UTC_TIMESTAMP(6) WHERE source_id=5;"),
        ):
            with scenario(label) as (database, _, step):
                step("prepare")
                sql(database, mutation, label + "-mutation")
                step("batch", succeeds=False)
                assert sql(database, "SELECT copy_cursor FROM mblob_replacement_review_state;") == "0"
                assert sql(database, "SELECT COUNT(*) FROM mblob_replacement_review;") == "0"

        with scenario("byte-budget-and-retry") as (database, _, step):
            step("prepare")
            step("batch", succeeds=False, max_bytes=1)
            assert sql(database, "SELECT copy_cursor FROM mblob_replacement_review_state;") == "0"
            step("batch")
            assert sql(database, "SELECT copy_cursor FROM mblob_replacement_review_state;") == "5"

        with scenario("retained-target-digest-mismatch") as (database, _, step):
            step("prepare")
            for _ in range(3):
                step("batch")
            sql(database, "UPDATE mblob_replacement_review SET content=X'626164' WHERE id=5;")
            step("batch", succeeds=False)
            assert sql(database, "SELECT phase,verify_cursor FROM mblob_replacement_review_state;") == "VERIFY\t0"

        for disposition in ("RETAIN_CANONICAL_CONFLICT", "RETAIN_RETENTION_CONFLICT"):
            with scenario("blocks-" + disposition.lower()) as (database, _, step):
                sql(database, "UPDATE mblob_migration_promotion_item SET disposition='" + disposition + "' WHERE source_id=100;")
                step("prepare", succeeds=False)
                assert sql(database, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mblob_replacement_review';") == "0"

        with scenario("unreconciled-promoted-row") as (database, _, step):
            sql(database, "UPDATE mblob_migration_promotion_item SET reconciled=b'0' WHERE source_id=1;")
            step("prepare", succeeds=False)
            assert sql(database, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mblob_replacement_review';") == "0"

        with scenario("empty-source-and-checkpoints") as (database, proof, step):
            sql(database, "DELETE FROM mblob; DELETE FROM mblob_migration_promotion_item; UPDATE mblob_migration_promotion SET source_high_water_id=0,reconciliation_cursor=0,reconciled_count=0,canonical_count=0,retained_count=0,retained_bytes=0;")
            for field in ("sourceHighWaterId", "reconciliationCursor", "reconciledCount", "canonicalCount", "retainedCount", "retainedBytes"):
                proof["evidence"][field] = 0
            step("prepare")
            step("batch")
            step("batch")
            assert sql(database, "SELECT phase,copied_count FROM mblob_replacement_review_state;") == "VERIFIED\t0"

        with scenario("unapproved-source-dependencies") as (database, _, step):
            sql(database, "CREATE TABLE referencing_blob(id bigint PRIMARY KEY, blob_id bigint, FOREIGN KEY(blob_id) REFERENCES mblob(id));")
            step("prepare", succeeds=False)
            assert sql(database, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mblob_replacement_review';") == "0"

        with scenario("source-schema-drift") as (database, _, step):
            sql(database, "ALTER TABLE mblob ADD COLUMN unreviewed varchar(10);")
            step("prepare", succeeds=False)
            assert sql(database, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mblob_replacement_review';") == "0"

        for label, mutation in (
            ("target-schema-drift-before-swap", "ALTER TABLE mblob_replacement_review ADD COLUMN unreviewed varchar(10);"),
            ("target-index-drift-before-swap", "ALTER TABLE mblob_replacement_review DROP INDEX UK__MBLOB__NAME;"),
            ("source-schema-drift-before-swap", "ALTER TABLE mblob MODIFY COLUMN name varchar(254);"),
        ):
            with scenario(label) as (database, proof, step):
                step("prepare")
                for _ in range(6):
                    step("batch")
                sql(database, mutation)
                approval = "APPROVED " + proof["evidence"]["id"] + " mblob_replacement_review mblob_before_review"
                step("swap", succeeds=False, prefix="SET @mojito_mblob_cutover_approval = '" + approval + "';\n")
                assert sql(database, "SELECT COUNT(*) FROM mblob;") == "5"
                assert sql(database, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mblob_before_review';") == "0"

        with scenario("expired-proof-at-execution") as (database, proof, _):
            now = dt.datetime.now(dt.timezone.utc)
            proof["validUntil"] = (now - dt.timedelta(seconds=1)).isoformat()
            proof["evidence"]["reconciledAt"] = (now - dt.timedelta(seconds=200)).isoformat()
            expired = render.render(options(database=database, phase="prepare"), proof, now - dt.timedelta(seconds=100))
            sql(database, expired, "expired-proof-at-execution-prepare", succeeds=False)
            assert sql(database, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mblob_replacement_review';") == "0"
        results["status"] = "PASS"
    except BaseException as error:
        results["status"] = "FAIL"
        results["error"] = str(error)
        raise
    finally:
        (artifact / "result.json").write_text(json.dumps(results, indent=2) + "\n")
        print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
