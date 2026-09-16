#!/usr/bin/env python3
"""Run the archive repository test on a disposable local MySQL 8 database.

Requires Python 3, mysql, Maven and the project's Java version. No existing database,
remote host or arbitrary Maven arguments can be supplied. --prepare-only verifies
the isolated database/configuration without running Maven, then removes everything.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import stat
import subprocess
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET


REPO = Path(__file__).resolve().parents[2]
TEST = "com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveRepositoryTest"
IDENTITY_SQL = """SELECT JSON_OBJECT('hostname',@@hostname,'port',@@port,
 'version',@@version,'comment',@@version_comment,'socket',@@socket,
 'server_uuid',@@server_uuid,'account',CURRENT_USER(),'database',DATABASE());"""


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def private_write(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write(value)


def clean_environment():
    ignored = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS",
               "MAVEN_ARGS", "MYSQL_PWD", "MYSQL_HOST", "MYSQL_TCP_PORT", "MYSQL_UNIX_PORT",
               "MYSQL_TEST_LOGIN_FILE"}
    environment = {key: value for key, value in os.environ.items()
                   if key not in ignored and not key.upper().startswith(("SPRING_", "L10N_"))}
    # MySQL 8.0 reads .mylogin.cnf even with --no-defaults and lacks --no-login-paths.
    # Ignore inherited overrides and force the client to read a controlled empty source.
    environment["MYSQL_TEST_LOGIN_FILE"] = os.devnull
    return environment


class Mysql:
    def __init__(self, args, environment, password):
        self.args = args + ["--batch", "--raw", "--skip-column-names", "--connect-timeout=5"]
        self.environment = environment
        self.password = password

    def value(self, sql):
        result = subprocess.run(self.args, input=sql, text=True, capture_output=True,
                                env=self.environment, timeout=30)
        require(result.returncode == 0, "MySQL command failed: " +
                result.stderr.replace(self.password, "<redacted>").strip())
        return result.stdout.strip()

    def identity(self):
        return json.loads(self.value(IDENTITY_SQL))


def maven_command(maven, properties):
    # An explicit location excludes ~/.l10n and working-directory application files.
    return [maven, "-pl", "webapp", "-am", "-Pno-local-config", "-Dtest=" + TEST,
            "-Dsurefire.failIfNoSpecifiedTests=false", "-Dsurefire.rerunFailingTestsCount=0",
            "-Dspring.config.location=classpath:/config/application.properties,"
            "classpath:/application-test.properties," + properties.as_uri(),
            "-Dspring.config.additional-location=", "-Dspring.config.import=", "test"]


def run_maven(command, repo, environment, log, password):
    process = subprocess.Popen(command, cwd=repo, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               errors="replace", start_new_session=True)

    def record_output():
        with log.open("w") as stream:
            for line in process.stdout:
                stream.write(line.replace(password, "<redacted>"))
                stream.flush()

    reader = threading.Thread(target=record_output, daemon=True)
    reader.start()
    try:
        return process.wait(timeout=1200)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        reader.join(timeout=10)
        process.stdout.close()


def capture_reports(repo, artifact, started_ns, password):
    report_dir = repo / "webapp/target/surefire-reports"
    xml_path = report_dir / ("TEST-" + TEST + ".xml")
    require(xml_path.exists() and xml_path.stat().st_mtime_ns >= started_ns,
            "No fresh Surefire XML report for the requested repository test")
    for path in (xml_path, report_dir / (TEST + ".txt")):
        if path.exists() and path.stat().st_mtime_ns >= started_ns:
            (artifact / path.name).write_text(path.read_text().replace(password, "<redacted>"))
    suite = ET.fromstring(xml_path.read_text())
    summary = {key: int(suite.attrib.get(key, "0"))
               for key in ("tests", "failures", "errors", "skipped")}
    summary["reruns"] = sum(len(suite.findall(".//" + name)) for name in
                            ("flakyFailure", "flakyError", "rerunFailure", "rerunError"))
    return summary


def capture_database(admin, database, artifact):
    tables = set(admin.value("SELECT table_name FROM information_schema.tables "
                             f"WHERE table_schema='{database}';").splitlines())
    (artifact / "tables.txt").write_text("\n".join(sorted(tables)) + "\n")
    evidence = {"table_count": len(tables), "latest_successful_migration": None}
    if "flyway_schema_history" in tables:
        rows = admin.value(f"SELECT installed_rank,version,description,type,script,checksum,"
                           f"installed_on,execution_time,success FROM `{database}`.flyway_schema_history "
                           "ORDER BY installed_rank;")
        (artifact / "flyway-history.tsv").write_text(
            "installed_rank\tversion\tdescription\ttype\tscript\tchecksum\tinstalled_on\texecution_time\tsuccess\n"
            + rows + "\n")
        evidence["latest_successful_migration"] = admin.value(
            f"SELECT version FROM `{database}`.flyway_schema_history WHERE success=1 "
            "ORDER BY installed_rank DESC LIMIT 1;")
    for table in ("pollable_task", "pollable_task_archive_checkpoint", "pollable_task_archive_retry"):
        if table in tables:
            evidence[table + "_rows"] = int(admin.value(f"SELECT COUNT(*) FROM `{database}`.`{table}`;"))
            (artifact / (table + "-ddl.tsv")).write_text(
                admin.value(f"SHOW CREATE TABLE `{database}`.`{table}`;") + "\n")
    return evidence


def run(args):
    repo = args.repo.resolve()
    require((repo / "webapp/src/test/java" / (TEST.replace(".", "/") + ".java")).is_file(),
            "Repository does not contain the requested test")
    maven = shutil.which("mvn")
    if args.dry_run:
        print(shlex.join(maven_command(maven or "mvn", Path("/private/tmp/PROBE/application.properties"))))
        return 0
    require(maven is not None or args.prepare_only, "mvn is not on PATH")
    mysql = shutil.which(args.mysql)
    require(mysql is not None, "mysql executable not found")
    socket = args.socket.resolve()
    require(socket.exists() and stat.S_ISSOCK(socket.stat().st_mode),
            "--socket must identify an existing local UNIX socket")
    environment = clean_environment()
    password = secrets.token_hex(32)
    database = "mojitoarchivetest" + uuid.uuid4().hex[:16]
    username = "mjar" + uuid.uuid4().hex[:16]
    require(re.fullmatch(r"mojitoarchivetest[a-f0-9]{16}", database), "Invalid generated database")
    require(re.fullmatch(r"mjar[a-f0-9]{16}", username), "Invalid generated account")
    admin = Mysql([mysql, "--no-defaults", "--protocol=SOCKET",
                   "--socket=" + str(socket), "--user=" + args.admin_user], environment, password)
    identity = admin.identity()
    require(identity["version"].startswith("8.") and "mariadb" not in
            (identity["version"] + identity["comment"]).lower(), "Native MySQL 8 is required")
    require(Path(identity["socket"]).resolve() == socket, "Server socket identity differs")
    require(1 <= int(identity["port"]) <= 65535, "Server has no valid local TCP port")
    require(re.fullmatch(r"[a-fA-F0-9-]{36}", identity["server_uuid"]), "Missing server UUID")
    require(admin.value("SELECT COUNT(*) FROM information_schema.schemata "
                        f"WHERE schema_name='{database}';") == "0", "Refusing an existing database")
    require(admin.value(f"SELECT COUNT(*) FROM mysql.user WHERE User='{username}';") == "0",
            "Refusing an existing account")
    artifact = args.output.resolve() / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                                        + "-" + uuid.uuid4().hex[:8])
    artifact.mkdir(parents=True, mode=0o700)
    result = {"kind": "Java/Spring/JPA archive repository integration; Azure mocked",
              "status": "RUNNING", "prepare_only": args.prepare_only, "database": database,
              "account": username, "socket_identity": identity, "artifact": str(artifact),
              "cleanup_verified": False, "source_sha256": {}}
    source_paths = list((repo / "webapp/src/main/resources/db/migration").glob("V*.sql"))
    source_paths += list((repo / "webapp/src/main/java/db/migration").glob("V*.java"))
    source_paths += list((repo / "webapp/src/main/java/com/box/l10n/mojito/service/pollableTask")
                         .glob("PollableTask*.java"))
    source_paths += [repo / "webapp/src/test/java" / (TEST.replace(".", "/") + ".java")]
    for path in sorted(source_paths):
        result["source_sha256"][str(path.relative_to(repo))] = hashlib.sha256(path.read_bytes()).hexdigest()
    # Persist exact ownership before any mutation, including for manual recovery after SIGKILL.
    (artifact / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    owned = False
    try:
        # Names were absent and are generated internally. Mark before the commands so a timeout
        # after successful server execution still triggers cleanup of these exact names.
        owned = True
        admin.value(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        for host in ("localhost", "127.0.0.1"):
            admin.value(f"CREATE USER '{username}'@'{host}' IDENTIFIED BY '{password}';")
            # Alphanumeric database names avoid MySQL's wildcard interpretation of '_' in grants.
            admin.value(f"GRANT ALL PRIVILEGES ON `{database}`.* TO '{username}'@'{host}';")
        with tempfile.TemporaryDirectory(prefix="mojito-archive-mysql-") as private_dir:
            private = Path(private_dir)
            client = private / "client.cnf"
            private_write(client, f"[client]\nuser={username}\npassword={password}\nhost=127.0.0.1\n"
                          f"port={identity['port']}\nprotocol=TCP\ndatabase={database}\n")
            scoped = Mysql([mysql, "--defaults-file=" + str(client)],
                           environment, password)
            tcp_identity = scoped.identity()
            require(tcp_identity["server_uuid"] == identity["server_uuid"] and
                    tcp_identity["database"] == database and
                    tcp_identity["account"].split("@")[0] == username,
                    "Loopback TCP did not reach the generated account/database on the verified server")
            result["tcp_identity"] = tcp_identity
            (artifact / "grants.txt").write_text(scoped.value("SHOW GRANTS;") + "\n")
            properties = private / "application.properties"
            config = f"""spring.datasource.url=jdbc:mysql://127.0.0.1:{identity['port']}/{database}?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true
spring.datasource.username={username}
spring.datasource.password={password}
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.flyway.enabled=true
spring.flyway.clean-disabled=true
l10n.flyway.clean=false
l10n.flyway.repair=false
spring.jpa.hibernate.ddl-auto=none
spring.jpa.defer-datasource-initialization=false
spring.sql.init.mode=never
spring.session.jdbc.initialize-schema=never
spring.profiles.include=
l10n.org.quartz.scheduler.enabled=false
l10n.ai-review.cleanup-enabled=false
l10n.pollable-task.archive.enabled=false
l10n.blob-storage.migration.enabled=false
l10n.blob-storage.default-type=DATABASE
l10n.azure.blob-storage.enabled=false
l10n.aws.s3.enabled=false
l10n.org.async-job-queue.enabled=false
l10n.bootstrap.enabled=true
"""
            private_write(properties, config)
            (artifact / "application-redacted.properties").write_text(config.replace(password, "<redacted>"))
            command = maven_command(maven or "mvn", properties)
            result["command"] = command
            (artifact / "command.json").write_text(json.dumps(command, indent=2) + "\n")
            if args.prepare_only:
                result["status"] = "PREPARED_ONLY"
            else:
                print(f"Running {TEST} on disposable MySQL. Artifacts: {artifact}", flush=True)
                started_ns = time.time_ns()
                result["maven_exit_code"] = run_maven(command, repo, environment,
                                                      artifact / "maven.log", password)
                result["database_evidence"] = capture_database(admin, database, artifact)
                result["surefire"] = capture_reports(repo, artifact, started_ns, password)
                require(result["maven_exit_code"] == 0, "Maven failed; see maven.log and Surefire artifacts")
                summary = result["surefire"]
                require(summary["tests"] > 0 and all(summary[key] == 0 for key in
                        ("failures", "errors", "skipped", "reruns")), "Tests did not all pass without skips/reruns")
                require(result["database_evidence"]["latest_successful_migration"] == "122",
                        "Full Flyway migration through V122 was not observed")
                require(result["database_evidence"].get("pollable_task_rows", 0) > 0,
                        "Expected committed test task rows were not found in the disposable database")
                result["status"] = "PASS"
    except BaseException as error:
        result["status"] = "FAILED"
        result["error"] = (type(error).__name__ + ": " + str(error)).replace(password, "<redacted>")
    finally:
        if owned:
            try:
                require(admin.identity()["server_uuid"] == identity["server_uuid"],
                        "Local server changed; refusing cleanup against another server")
                if not args.prepare_only and "database_evidence" not in result:
                    try:
                        result["database_evidence"] = capture_database(admin, database, artifact)
                    except Exception as error:
                        result["evidence_error"] = str(error).replace(password, "<redacted>")
                admin.value(f"DROP DATABASE IF EXISTS `{database}`;")
                for host in ("localhost", "127.0.0.1"):
                    admin.value(f"DROP USER IF EXISTS '{username}'@'{host}';")
                require(admin.value("SELECT COUNT(*) FROM information_schema.schemata "
                                    f"WHERE schema_name='{database}';") == "0", "Database remains after cleanup")
                require(admin.value(f"SELECT COUNT(*) FROM mysql.user WHERE User='{username}';") == "0",
                        "Generated accounts remain after cleanup")
                result["cleanup_verified"] = True
            except BaseException as error:
                result["status"] = "FAILED"
                result["cleanup_error"] = str(error).replace(password, "<redacted>")
        (artifact / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"{result['status']}; cleanup verified={result['cleanup_verified']}; {artifact / 'result.json'}")
    return 0 if result["status"] in ("PASS", "PREPARED_ONLY") and result["cleanup_verified"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--mysql", default=shutil.which("mysql") or "/opt/homebrew/opt/mysql@8.0/bin/mysql")
    parser.add_argument("--socket", type=Path, default=Path("/tmp/mysql.sock"))
    parser.add_argument("--admin-user", default="root", help="Local socket administrator; no password argument")
    parser.add_argument("--output", type=Path,
                        default=Path.home() / ".cache/mojito-storage-validation-20260914/archive-java")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--prepare-only", action="store_true", help="Verify setup, skip Maven, then clean up")
    mode.add_argument("--dry-run", action="store_true", help="Print fixed Maven command; no DB or file changes")
    args = parser.parse_args()

    def interrupt(_signal, _frame):
        raise KeyboardInterrupt("Termination requested")

    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGHUP, interrupt)
    try:
        return run(args)
    except Exception as error:
        print(type(error).__name__ + ": " + str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
