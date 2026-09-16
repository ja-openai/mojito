#!/usr/bin/env python3
"""Disposable local MySQL SQL probes. This is not an application/Flyway integration test.

Uses only a UNIX socket, creates its own random database, applies repository DDL,
and drops only that database. Requires Python 3 and the mysql CLI; no Python DB driver.
Artifacts contain generated SQL, schema hashes, query plans, race observations and results.
"""

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import queue
import re
import subprocess
import threading
import time
import uuid


class Mysql:
    def __init__(self, binary, socket, user, database=None):
        self.args = [binary, "--no-defaults", "--protocol=SOCKET", f"--socket={socket}",
                     f"--user={user}", "--batch", "--raw", "--skip-column-names", "--unbuffered"]
        if database:
            self.args.append(database)

    def run(self, sql, check=True):
        result = subprocess.run(self.args, input=sql, text=True, capture_output=True, timeout=30)
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result

    def value(self, sql):
        return self.run(sql).stdout.strip()

    def start(self, sql):
        process = subprocess.Popen(self.args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        process.stdin.write("SET SESSION innodb_lock_wait_timeout=10;\n" + sql)
        process.stdin.close()
        process.stdin = None
        return process


class Session:
    """Persistent CLI connection, synchronized on completed SELECT markers."""

    def __init__(self, mysql):
        self.process = subprocess.Popen(mysql.args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.errors = []
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._errors, daemon=True).start()
        self.execute("SET SESSION innodb_lock_wait_timeout=10;")

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\n"))
        self.lines.put(None)

    def _errors(self):
        self.errors.extend(self.process.stderr)

    def execute(self, sql):
        marker = "PROBE_" + uuid.uuid4().hex
        self.process.stdin.write(sql + f"\nSELECT '{marker}';\n")
        self.process.stdin.flush()
        result = []
        while True:
            line = self.lines.get(timeout=15)
            if line is None:
                raise AssertionError("mysql session exited: " + "".join(self.errors))
            if line == marker:
                return "\n".join(result)
            result.append(line)

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            self.process.wait(timeout=15)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def check(condition, detail):
    if not condition:
        raise AssertionError(detail)


def statements(sql):
    # Only the selected DDL files are split: they contain no stored programs or semicolon literals.
    return [part.strip() + ";" for part in re.sub(r"--[^\n]*", "", sql).split(";") if part.strip()]


def table_nodes(value):
    if isinstance(value, dict):
        if "table_name" in value:
            yield value
        for child in value.values():
            yield from table_nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from table_nodes(child)


def keyset(last_date, last_id, high_date, high_id, limit=137):
    return f"""SELECT id, finished_date FROM pollable_task
WHERE finished_date >= '{last_date}' AND finished_date <= '{high_date}'
  AND finished_date < '2026-01-01 00:00:00'
  AND (finished_date > '{last_date}' OR (finished_date = '{last_date}' AND id > {last_id}))
  AND (finished_date < '{high_date}' OR (finished_date = '{high_date}' AND id <= {high_id}))
ORDER BY finished_date, id LIMIT {limit}"""


def wait_for_row_lock(mysql, database):
    sql = f"""SELECT COUNT(*) FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
  AND l.ENGINE = w.ENGINE WHERE l.OBJECT_SCHEMA = '{database}'"""
    until = time.monotonic() + 5
    while time.monotonic() < until:
        if int(mysql.value(sql)) > 0:
            return
        time.sleep(0.02)
    raise AssertionError("Expected a real InnoDB row-lock wait; none observed")


def run(args):
    root = args.repo.resolve()
    artifact = args.output.resolve() / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                                       + "-" + uuid.uuid4().hex[:8])
    artifact.mkdir(parents=True)
    database = "mojito_storage_probe_" + uuid.uuid4().hex[:16]
    check(re.fullmatch(r"mojito_storage_probe_[a-f0-9]{16}", database), "Unsafe database name")
    admin = Mysql(args.mysql, args.socket, args.user)
    identity = admin.value("SELECT @@hostname,@@port,@@version,@@socket,CURRENT_USER();")
    check(identity.split("\t")[2].startswith("8."), "This probe requires native MySQL 8")
    result = {"kind": "SQL-layer probes; not Java/Spring/Flyway application integration",
              "database": database, "identity": identity, "artifacts": str(artifact),
              "probes": [], "migration_files": {}, "source_files": {}, "status": "RUNNING"}
    for relative in [
        "webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskRepository.java",
        "webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskArchiveService.java",
        "webapp/src/main/java/com/box/l10n/mojito/service/blobstorage/migration/BlobMigrationStore.java",
    ]:
        result["source_files"][relative] = hashlib.sha256((root / relative).read_bytes()).hexdigest()
    (artifact / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    check(admin.value(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}';") == "0",
          "Refusing to reuse an existing database")
    admin.run(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    mysql = Mysql(args.mysql, args.socket, args.user, database)
    try:
        migration_dir = root / "webapp/src/main/resources/db/migration"

        def migration(name):
            data = (migration_dir / name).read_bytes()
            result["migration_files"][name] = hashlib.sha256(data).hexdigest()
            return data.decode()

        before_sql = []
        for name in ["V1__Initial_Setup.sql", "V27__Add_tm_xliff.sql", "V50__Add_mblob.sql",
                     "V76__AI_Translate_Run_History.sql", "V88__AI_Translate_Text_Unit_Attempt.sql"]:
            before_sql.extend(statements(migration(name)))
        for name, pattern in [
            ("V89__Glossary_Raw_Term_Index.sql", r"create table term_index_refresh_run\s*\("),
            ("V90__Glossary_Term_Index_Curation.sql", r"(?:alter table term_index_refresh_run\s|create unique index UK__TERM_INDEX_REFRESH_RUN__POLLABLE_TASK\s)"),
            ("V91__Term_Index_Default_Source_Locale.sql", r"(?:create table term_index_automation_run\s*\(|alter table term_index_automation_run\s|create (?:unique )?index \S+\s+on term_index_automation_run\s*\()"),
        ]:
            selected = [sql for sql in statements(migration(name)) if re.match(pattern, sql, re.I)]
            check(selected, "No schema statements selected from " + name)
            before_sql.extend(selected)
        for name in ["V105__Bulk_Import_Lineage.sql", "V109__AI_Review_Request_Usage.sql"]:
            before_sql.extend(statements(migration(name)))
        before = "\n\n".join(before_sql)
        (artifact / "representative-preexisting-schema.sql").write_text(before + "\n")
        mysql.run(before)
        seed = """INSERT INTO repository(id,name) VALUES (1,'probe');
INSERT INTO asset(id,content,path,repository_id) VALUES(1,'fixture','fixture.json',1);
INSERT INTO tm_text_unit(id,asset_id) VALUES(1,1);
INSERT INTO mblob(id,created_date,content,expire_after_seconds,name)
VALUES(1,'2025-01-01',X'00017F80FF',NULL,'multi_branch_state/probe'),
(2,'2025-01-01',X'1234',86400,'pollable_task/fixture/input');
"""
        for start in range(1, 20001, 1000):
            rows = [f"({i},'fixture',0,TIMESTAMPADD(SECOND,{i // 10},'2025-01-01 00:00:00'))"
                    for i in range(start, start + 1000)]
            seed += "INSERT INTO pollable_task(id,name,expected_sub_task_number,finished_date) VALUES "
            seed += ",".join(rows) + ";\n"
        (artifact / "seed.sql").write_text(seed)
        mysql.run(seed)
        fingerprint_sql = """SELECT COUNT(*),SUM(id),SUM(CRC32(CONCAT_WS('|',id,name,expected_sub_task_number,finished_date))) FROM pollable_task;
SELECT id,name,HEX(content),expire_after_seconds FROM mblob ORDER BY id;"""
        fingerprint = mysql.value(fingerprint_sql)
        upgrade_sql = "\n".join(migration(name) for name in ["V121__MBlob_Migration.sql", "V122__Pollable_Task_Archive_Checkpoint.sql"])
        (artifact / "upgrade.sql").write_text(upgrade_sql)
        mysql.run(upgrade_sql)
        check(mysql.value(fingerprint_sql) == fingerprint, "Migration changed existing source records")
        check(mysql.value("SELECT COUNT(*) FROM pollable_task_archive_checkpoint WHERE id=1 AND last_task_id=0 AND delete_source_mode=FALSE AND retention_days=90 AND lease_token IS NULL;") == "1", "Archive checkpoint defaults differ")
        check(mysql.value("SELECT COUNT(*) FROM mblob_migration_run;") == "0", "Migration unexpectedly starts a worker")
        result["probes"].append({"name": "V121/V122 exact DDL on populated representative prior schema", "status": "PASS", "source_tasks": 20000, "source_blobs": 2, "source_fingerprint_preserved": True})
        (artifact / "source-fingerprint.txt").write_text(fingerprint + "\n")
        ddl = "\n".join(mysql.value(f"SHOW CREATE TABLE `{table}`;") for table in ["pollable_task", "mblob", "mblob_migration_run", "mblob_migration_item", "pollable_task_archive_checkpoint", "pollable_task_archive_retry"])
        (artifact / "show-create-tables.tsv").write_text(ddl + "\n")

        references = [
            ("asset_extraction", "pollable_task_id", lambda t: f"INSERT INTO asset_extraction(pollable_task_id) VALUES({t});"),
            ("drop", "import_pollable_task_id", lambda t: f"INSERT INTO `drop`(import_pollable_task_id) VALUES({t});"),
            ("drop", "export_pollable_task_id", lambda t: f"INSERT INTO `drop`(export_pollable_task_id) VALUES({t});"),
            ("tm_xliff", "export_pollable_task_id", lambda t: f"INSERT INTO tm_xliff(asset_id,export_pollable_task_id) VALUES(1,{t});"),
            ("ai_translate_run", "pollable_task_id", lambda t: f"INSERT INTO ai_translate_run(trigger_source,repository_id,pollable_task_id,model,translate_type,related_strings_type,source_text_max_count_per_locale,status) VALUES('MANUAL',1,{t},'fixture','ALL','NONE',1,'COMPLETED');"),
            ("ai_translate_text_unit_attempt", "pollable_task_id", lambda t: f"INSERT INTO ai_translate_text_unit_attempt(pollable_task_id,tm_text_unit_id,locale_id,request_group_id,translate_type,status) VALUES({t},1,1,'fixture','ALL','COMPLETED');"),
            ("term_index_refresh_run", "pollable_task_id", lambda t: f"INSERT INTO term_index_refresh_run(status,pollable_task_id) VALUES('COMPLETED',{t});"),
            ("term_index_automation_run", "pollable_task_id", lambda t: f"INSERT INTO term_index_automation_run(type,status,pollable_task_id) VALUES('GENERATE_CANDIDATES','COMPLETED',{t});"),
            ("bulk_import_run", "pollable_task_id", lambda t: f"INSERT INTO bulk_import_run(run_id,repository_id,asset_id,locale_id,pollable_task_id,actor_type,source,import_mode,integrity_checks_type,status,requested_count,imported_count,skipped_count) VALUES(UUID(),1,1,1,{t},'SYSTEM','fixture','ALWAYS_IMPORT','SKIP','COMPLETED',0,0,0);"),
            ("pollable_task", "parent_task_id", lambda t: f"INSERT INTO pollable_task(name,expected_sub_task_number,parent_task_id) VALUES('child-fixture',0,{t});"),
        ]
        incoming = mysql.value(f"SELECT TABLE_NAME,COLUMN_NAME,CONSTRAINT_NAME FROM information_schema.key_column_usage WHERE referenced_table_schema='{database}' AND referenced_table_name='pollable_task' ORDER BY TABLE_NAME,COLUMN_NAME;")
        actual_refs = {tuple(line.split("\t")[:2]) for line in incoming.splitlines()}
        check(actual_refs == {(table, column) for table, column, _ in references}, "Incoming FK inventory differs from probed set")
        (artifact / "incoming-fks.tsv").write_text(incoming + "\n")

        plans = {}
        seek_date = "2025-01-01 00:16:40"
        high_date = "2025-01-01 00:30:00"
        sql = keyset(seek_date, 10002, high_date, 18005)
        high_sql = f"""SELECT id,finished_date FROM pollable_task WHERE finished_date >= '{seek_date}'
AND finished_date < '2026-01-01 00:00:00'
AND (finished_date > '{seek_date}' OR (finished_date='{seek_date}' AND id>10002))
ORDER BY finished_date DESC,id DESC LIMIT 1"""
        for name, query in [("archive-keyset", sql), ("archive-high-water", high_sql)]:
            plan = json.loads(mysql.value("EXPLAIN FORMAT=JSON " + query))
            node = next(table_nodes(plan))
            check(node.get("key") == "I__POLLABLE_TASK__FINISHED_DATE", name + " did not use existing finished-date index")
            check(node.get("access_type") in {"range", "ref", "index"}, name + " scans whole table")
            check('"using_filesort": true' not in json.dumps(plan), name + " requires filesort")
            plans[name] = {"sql": query, "plan": plan}
        expected = list(range(10003, 18006))
        seen = []
        last_date, last_id = seek_date, 10002
        while True:
            rows = mysql.value(keyset(last_date, last_id, high_date, 18005)).splitlines()
            if not rows:
                break
            seen.extend(int(row.split("\t")[0]) for row in rows)
            last_id, last_date = rows[-1].split("\t")
            if len(seen) == 137:
                mysql.run("INSERT INTO pollable_task(id,name,expected_sub_task_number,finished_date) VALUES(800000,'after-high-water',0,'2025-01-01 01:00:00');")
        check(seen == expected, "Keyset lost/duplicated tied timestamps or crossed fixed high water")
        result["probes"].append({"name": "keyset seeks, tied timestamps, fixed bound, restart-compatible cursor", "status": "PASS", "rows": len(seen), "page_size": 137})

        race_log = []
        for ordinal, (table, column, insert_reference) in enumerate(references):
            target = 900000 + ordinal * 10
            mysql.run(f"INSERT INTO pollable_task(id,name,expected_sub_task_number,finished_date) VALUES({target},'race',0,'2025-01-01');")
            # Delete owns parent X-lock first. New FK insertion waits, then fails after commit.
            with Session(mysql) as holder:
                holder.execute(f"START TRANSACTION; SELECT id FROM pollable_task WHERE id={target} FOR UPDATE;")
                contender = mysql.start(insert_reference(target))
                try:
                    wait_for_row_lock(mysql, database)
                    holder.execute(f"DELETE FROM pollable_task WHERE id={target}; COMMIT;")
                    stdout, stderr = contender.communicate(timeout=15)
                finally:
                    if contender.poll() is None:
                        contender.kill()
                        contender.communicate()
                check(contender.returncode != 0 and "ERROR 1452" in stderr, f"{table}.{column}: new reference survived parent deletion")
                race_log.append({"table": table, "column": column, "order": "archive lock first", "observed_innodb_wait": True, "mysql_error": 1452})
            # Referencer holds its parent S-lock first. Archive waits and must retain the parent.
            target += 1
            mysql.run(f"INSERT INTO pollable_task(id,name,expected_sub_task_number,finished_date) VALUES({target},'race',0,'2025-01-01');")
            with Session(mysql) as holder:
                holder.execute("START TRANSACTION; " + insert_reference(target))
                contender = mysql.start(f"START TRANSACTION; SELECT id FROM pollable_task WHERE id={target} FOR UPDATE; SELECT CONCAT('reference=',EXISTS(SELECT 1 FROM `{table}` WHERE {column}={target})); DELETE FROM pollable_task WHERE id={target}; COMMIT;")
                try:
                    wait_for_row_lock(mysql, database)
                    holder.execute("COMMIT;")
                    stdout, stderr = contender.communicate(timeout=15)
                finally:
                    if contender.poll() is None:
                        contender.kill()
                        contender.communicate()
                check("reference=1" in stdout and "ERROR 1451" in stderr, f"{table}.{column}: reference-first race failed")
                check(mysql.value(f"SELECT COUNT(*) FROM pollable_task WHERE id={target};") == "1", "Referenced source was removed")
                race_log.append({"table": table, "column": column, "order": "reference insert first", "observed_innodb_wait": True, "mysql_error": 1451})
            query = f"SELECT id FROM `{table}` WHERE {column}={target} LIMIT 1"
            plan = json.loads(mysql.value("EXPLAIN FORMAT=JSON " + query))
            node = next(table_nodes(plan))
            check(node.get("key") is not None and node.get("access_type") in {"const", "ref", "range"}, f"Unindexed lookup for {table}.{column}")
            plans[table + "." + column] = {"sql": query, "plan": plan}
        (artifact / "foreign-key-races.json").write_text(json.dumps(race_log, indent=2) + "\n")
        result["probes"].append({"name": "all ten incoming FKs: both concurrent insertion/deletion orders", "status": "PASS", "races": len(race_log), "actual_lock_wait_observed_each_time": True})

        # A real soft reference is deliberately not protected by an FK. Prove why the Java worker
        # excludes all chat task types even when an existence lookup happens to miss.
        mysql.run("INSERT INTO pollable_task(id,name,expected_sub_task_number,finished_date) VALUES(910000,'soft-reference-fixture',0,'2025-01-01');")
        mysql.run("INSERT INTO ai_review_request_usage(pollable_task_id,surface,request_type,profile_id,model_name,status,started_at) VALUES(910000,'unknown','legacy','balanced','fixture','completed','2025-01-01');")
        soft_plan = json.loads(mysql.value("EXPLAIN FORMAT=JSON SELECT id FROM ai_review_request_usage WHERE pollable_task_id=910000 LIMIT 1;"))
        check(next(table_nodes(soft_plan)).get("key") == "I__AIRRU__POLLABLE_TASK", "Usage reference lookup missing index")
        plans["ai_review_request_usage.pollable_task_id"] = soft_plan
        mysql.run("DELETE FROM pollable_task WHERE id=910000;")
        check(mysql.value("SELECT COUNT(*) FROM ai_review_request_usage WHERE pollable_task_id=910000;") == "1", "Soft reference behavior differs")
        result["probes"].append({"name": "usage soft reference has an index but does not fence deletion", "status": "PASS", "requires_java_task_type_exclusion": True})

        # These SQL comparisons reproduce the lock/token decisions, not the Java implementation.
        mysql.run("UPDATE pollable_task_archive_checkpoint SET lease_token='old-worker',lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=1;")
        with Session(mysql) as owner:
            owner.execute("START TRANSACTION; SELECT lease_token FROM pollable_task_archive_checkpoint WHERE id=1 FOR UPDATE; UPDATE pollable_task_archive_checkpoint SET lease_token='new-worker',lease_expires_at=UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE id=1;")
            contender = mysql.start("START TRANSACTION; SELECT lease_token FROM pollable_task_archive_checkpoint WHERE id=1 FOR UPDATE; ROLLBACK;")
            try:
                wait_for_row_lock(mysql, database)
                owner.execute("COMMIT;")
                stdout, stderr = contender.communicate(timeout=15)
            finally:
                if contender.poll() is None:
                    contender.kill()
                    contender.communicate()
            check(contender.returncode == 0 and stdout.strip() == "new-worker", "Waiting worker did not observe replacement owner")
        changed = mysql.value("UPDATE pollable_task_archive_checkpoint SET last_task_id=99999 WHERE id=1 AND lease_token='old-worker'; SELECT ROW_COUNT();")
        check(changed == "0", "Stale token advanced the checkpoint")
        mysql.run("INSERT INTO pollable_task(id,name,expected_sub_task_number,finished_date) VALUES(920000,'atomic-fixture',0,'2025-01-01');")
        operation = "SELECT lease_token FROM pollable_task_archive_checkpoint WHERE id=1 FOR UPDATE; SELECT id FROM pollable_task WHERE id=920000 FOR UPDATE; DELETE FROM pollable_task WHERE id=920000; UPDATE pollable_task_archive_checkpoint SET last_task_id=920000 WHERE id=1 AND lease_token='new-worker';"
        mysql.run("START TRANSACTION; " + operation + " ROLLBACK;")
        check(mysql.value("SELECT COUNT(*) FROM pollable_task WHERE id=920000;") == "1", "Rollback removed task")
        check(mysql.value("SELECT last_task_id FROM pollable_task_archive_checkpoint WHERE id=1;") == "0", "Rollback advanced checkpoint")
        mysql.run("START TRANSACTION; " + operation + " COMMIT;")
        check(mysql.value("SELECT COUNT(*) FROM pollable_task WHERE id=920000;") == "0", "Atomic deletion did not commit")
        check(mysql.value("SELECT last_task_id FROM pollable_task_archive_checkpoint WHERE id=1;") == "920000", "Atomic checkpoint did not commit")
        result["probes"].append({"name": "checkpoint lock serialization, stale-token fence, atomic delete/cursor rollback and commit", "status": "PASS", "layer": "SQL primitive probes"})

        run_id = str(uuid.uuid4())
        mysql.run(f"INSERT INTO mblob_migration_run(id,allowed_prefixes,destination_root,high_water_id,max_rows,max_bytes,max_seconds,max_retries,status,requested,lease_token,lease_until) VALUES('{run_id}','multi_branch_state/','mblob_migration/v1/{run_id}',2,100,1048576,30,3,'RUNNING',TRUE,'active-token',UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE);")
        mysql.run(f"START TRANSACTION; SELECT lease_token FROM mblob_migration_run WHERE id='{run_id}' FOR UPDATE; UPDATE mblob_migration_run SET requested=FALSE,status='PAUSED',lease_token=NULL,lease_until=NULL WHERE id='{run_id}'; COMMIT;")
        valid = mysql.value(f"SELECT requested AND status='RUNNING' AND COALESCE(lease_token='active-token',FALSE) AND COALESCE(lease_until>CURRENT_TIMESTAMP,FALSE) FROM mblob_migration_run WHERE id='{run_id}';")
        check(valid == "0", "Paused migration still owns its lease")
        missing_run = mysql.run("INSERT INTO mblob_migration_item(run_id,source_id,disposition,attempts) VALUES('missing-run',1,'FAILED',1);", check=False)
        check("ERROR 1452" in missing_run.stderr, "Migration evidence lacks run FK")
        result["probes"].append({"name": "blob run pause revokes lease and evidence requires valid run", "status": "PASS", "layer": "SQL primitive probes"})

        (artifact / "explain-plans.json").write_text(json.dumps(plans, indent=2) + "\n")
        result["status"] = "PASS"
    except BaseException as error:
        result["status"] = "FAIL"
        result["error"] = str(error)
        raise
    finally:
        # The name is generated here and creation succeeded above; never drop user-supplied DBs.
        try:
            admin.run(f"DROP DATABASE `{database}`;")
            check(admin.value(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}';") == "0", "Disposable database remains after cleanup")
            result["disposable_database_dropped"] = True
        except BaseException as error:
            result["status"] = "FAIL"
            result["cleanup_error"] = str(error)
        (artifact / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path.home() / ".cache/mojito-storage-validation-20260914")
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--socket", default="/tmp/mysql.sock")
    parser.add_argument("--user", default="root")
    run(parser.parse_args())
