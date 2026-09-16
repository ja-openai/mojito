#!/usr/bin/env python3
"""Smoke-test the actual maintenance launch arguments on disposable local MySQL 8.

Uses already compiled project classes and the classpath from a Surefire report.
Never invokes Maven. --prepare-only writes the external Java runner and records its
classpath without running Java or touching MySQL. Normal execution runs real Flyway
and both promotion settings against a loopback Azure sentinel, then removes its DB/users.
"""

import argparse
import base64
import datetime as dt
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True

from run_archive_mysql_test import (
    Mysql, REPO, TEST, capture_database, clean_environment, private_write, require,
    run_maven as run_process,
)


JAVA_SOURCE = r"""
import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.Application;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationProperties;
import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveService;
import com.box.l10n.mojito.xml.XmlParsingConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class MaintenanceStartupProbe {
  static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }
  public static void main(String[] args) throws Exception {
    Map<String,Object> report = new LinkedHashMap<>();
    int exit = 0;
    ConfigurableApplicationContext context = null;
    try {
      if (args[0].equals("migrate")) {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[2]))) { p.load(reader); }
        Flyway flyway = Flyway.configure().dataSource(p.getProperty("spring.datasource.url"),
            p.getProperty("spring.datasource.username"), p.getProperty("spring.datasource.password"))
            .locations("classpath:db/migration").cleanDisabled(true).load();
        var result = flyway.migrate();
        report.put("migrationsExecuted", result.migrationsExecuted);
        report.put("latestMigration", flyway.info().current().getVersion().getVersion());
      } else {
        XmlParsingConfiguration.disableXPathLimits();
        context = new SpringApplication(Application.class).run(Arrays.copyOfRange(args, 2, args.length));
        Thread.sleep(6500); // Includes the first 5-second AI-review maintenance tick.
        var environment = context.getEnvironment();
        report.put("profiles", Arrays.asList(environment.getActiveProfiles()));
        require(Arrays.equals(environment.getActiveProfiles(), new String[]{"maintenance"}),
            "Unexpected Spring profiles");
        Map<String,Object> beans = new LinkedHashMap<>();
        for (String type : List.of(
            "com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationService",
            "com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationPromotionService",
            "com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationMaintenanceFence",
            "com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationCronSchedulerService",
            "com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationCronSchedulerService",
            "com.box.l10n.mojito.service.review.ReviewAutomationCronSchedulerService")) {
          int count = context.getBeansOfType(Class.forName(type)).size();
          beans.put(type, count);
          require(count == 1, "Required maintenance bean is missing or ambiguous: " + type);
        }
        require(context.getBean(BlobStorage.class) instanceof AzureBlobStorage,
            "Default storage is not the real Azure implementation");
        String endpoint = context.getBean(BlobContainerClient.class).getBlobContainerUrl();
        require(endpoint.startsWith("http://127.0.0.1:"), "Azure endpoint is not loopback");
        report.put("azureContainerUrl", endpoint);
        require(context.getBeansOfType(PollableTaskArchiveService.class).isEmpty(),
            "Archive worker unexpectedly enabled");
        for (String name : List.of("jobDetailBlobMigration", "blobMigrationTrigger",
            "jobDetailPollableTaskArchive", "triggerPollableTaskArchive")) {
          beans.put(name, context.containsBean(name));
          require(!context.containsBean(name), "Maintenance scheduled a migration/archive job");
        }
        report.put("beans", beans);
        var migration = context.getBean(BlobMigrationProperties.class);
        report.put("migrationEnabled", migration.isEnabled());
        report.put("promotionEnabled", migration.isPromotionEnabled());
        report.put("ddlAuto", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        List<Map<String,Object>> schedulers = new ArrayList<>();
        for (var scheduler : context.getBean(QuartzSchedulerManager.class).getSchedulers()) {
          var metadata = scheduler.getMetaData();
          schedulers.add(Map.of("name", scheduler.getSchedulerName(), "standby", scheduler.isInStandbyMode(),
              "started", scheduler.isStarted(), "executed", metadata.getNumberOfJobsExecuted(),
              "jobStore", metadata.getJobStoreClass().getName()));
          require(scheduler.isInStandbyMode() && !scheduler.isStarted()
              && metadata.getNumberOfJobsExecuted() == 0
              && metadata.getJobStoreClass().getName().equals("org.quartz.simpl.RAMJobStore"),
              "Quartz scheduler is not idle RAM storage");
        }
        require(!schedulers.isEmpty(), "Controller-required Quartz scheduler is missing");
        report.put("schedulers", schedulers);
      }
      report.put("status", "PASS");
    } catch (Throwable error) {
      Throwable cause = error;
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      report.put("status", "FAILED");
      report.put("errorType", cause.getClass().getName());
      report.put("error", String.valueOf(cause.getMessage()));
      error.printStackTrace();
      exit = 1;
    } finally {
      if (context != null) context.close();
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), report);
    }
    System.exit(exit);
  }
}
"""


def load_entrypoint(repo):
    path = repo / "dev-tools/storage-retention/maintenance/entrypoint.py"
    spec = importlib.util.spec_from_file_location("maintenance_entrypoint_probe", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def runtime(repo, report):
    properties = {node.attrib["name"]: node.attrib["value"]
                  for node in ET.parse(report).findall("./properties/property")}
    # Test configurations must not be component-scanned by the full application.
    paths = [Path(p) for p in properties["java.class.path"].split(os.pathsep)
             if p and "test-classes" not in Path(p).parts and "/test-common/target/" not in p
             and "/mojito-test-common/" not in p]
    require(paths and all(p.exists() for p in paths), "Compiled runtime classpath is incomplete")
    require(repo / "webapp/target/classes" in paths, "Surefire report belongs to another checkout")
    java = Path(properties["java.home"]) / "bin/java"
    javac = java.with_name("javac")
    require(java.is_file() and javac.is_file(), "The report's Java/Javac runtime is unavailable")
    return java, javac, os.pathsep.join(str(p) for p in paths)


def fingerprint(admin, database):
    tables = admin.value("SELECT table_name FROM information_schema.tables "
                         f"WHERE table_schema='{database}' AND table_type='BASE TABLE' ORDER BY table_name;").splitlines()
    require(tables and all(re.fullmatch(r"[A-Za-z0-9_]+", t) for t in tables), "Unexpected table inventory")
    columns = {table: [] for table in tables}
    for line in admin.value("SELECT table_name,column_name FROM information_schema.columns "
                            f"WHERE table_schema='{database}' ORDER BY table_name,ordinal_position;").splitlines():
        table, column = line.split("\t")
        require(re.fullmatch(r"[A-Za-z0-9_]+", column), "Unexpected column identifier")
        columns[table].append(column)
    ddl = admin.value("\n".join(f"SHOW CREATE TABLE `{database}`.`{table}`;" for table in tables))
    queries = []
    for table in tables:
        fields = ",".join(f"IF(`{col}` IS NULL,'N',CONCAT('V',HEX(CAST(`{col}` AS BINARY))))"
                          for col in columns[table])
        queries.append(f"SELECT '{table}',SHA2(CONCAT_WS('|',{fields}),256) "
                       f"FROM `{database}`.`{table}` ORDER BY 2;")
    rows = {table: [] for table in tables}
    for line in admin.value("\n".join(queries)).splitlines():
        table, digest = line.split("\t")
        rows[table].append(digest)
    return {"ddl_sha256": hashlib.sha256(ddl.encode()).hexdigest(), "table_count": len(tables),
            "tables": {table: {"count": len(values),
                               "rows_sha256": hashlib.sha256("\n".join(values).encode()).hexdigest()}
                       for table, values in rows.items()}}


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def run_executable_jar(command, directory, environment, log, password):
    """Observe the actual packaged application, then stop only its process group."""
    process = subprocess.Popen(command, cwd=directory, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               errors="replace", start_new_session=True)
    ready = threading.Event()
    result = {"status": "RUNNING", "ready_evidence": None, "idle_seconds": 0}

    def record_output():
        with log.open("w") as stream:
            for line in process.stdout:
                line = line.replace(password, "<redacted>")
                stream.write(line)
                stream.flush()
                if re.search(r"\bStarted Application in [0-9.]+ seconds\b", line):
                    result["ready_evidence"] = line.strip()
                    ready.set()

    reader = threading.Thread(target=record_output, daemon=True)
    reader.start()
    try:
        deadline = time.monotonic() + 180
        while not ready.wait(timeout=0.1) and time.monotonic() < deadline:
            require(process.poll() is None, "Executable application exited before startup completed")
        require(ready.is_set(), "Executable application did not confirm startup within 180 seconds")
        idle_start = time.monotonic()
        while time.monotonic() - idle_start < 6.5:
            require(process.poll() is None, "Executable application exited during idle observation")
            time.sleep(0.1)
        require(process.poll() is None, "Executable application exited after idle observation")
        result["idle_seconds"] = time.monotonic() - idle_start
        result["status"] = "PASS"
    except Exception as error:
        result["status"] = "FAILED"
        result["error"] = str(error).replace(password, "<redacted>")
    finally:
        result["terminated_by_probe"] = process.poll() is None
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        reader.join(timeout=10)
        process.stdout.close()
        result["exit_code_after_stop"] = process.returncode
    return result


def run(args):
    repo = args.repo.resolve()
    report = (args.classpath_report or repo / "webapp/target/surefire-reports" / ("TEST-" + TEST + ".xml")).resolve()
    java, javac, classpath = runtime(repo, report)
    entrypoint = load_entrypoint(repo)
    executable_jar = args.executable_jar.resolve() if args.executable_jar else None
    if executable_jar:
        require(executable_jar.is_file(), "Executable jar does not exist")
        with zipfile.ZipFile(executable_jar) as archive:
            archive.getinfo("BOOT-INF/classes/com/box/l10n/mojito/Application.class")
            require(any(name.startswith("BOOT-INF/classes/db/migration/V122__")
                        for name in archive.namelist()), "Executable jar lacks the V122 migration")
    artifact = args.output.resolve() / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                                        + "-" + uuid.uuid4().hex[:8])
    artifact.mkdir(parents=True, mode=0o700)
    source = artifact / "MaintenanceStartupProbe.java"
    source.write_text(JAVA_SOURCE)
    result = {"status": "PREPARED_ONLY" if args.prepare_only else "RUNNING", "artifact": str(artifact),
              "kind": "Real Spring maintenance startup; native MySQL; real Azure SDK with loopback sentinel",
              "classpath_report": str(report), "java": str(java), "cleanup_verified": False,
              "launcher_sha256": hashlib.sha256((repo / "dev-tools/storage-retention/maintenance/entrypoint.py").read_bytes()).hexdigest(),
              "modes": [], "source_sha256": {}}
    if executable_jar:
        result["executable_jar_path"] = str(executable_jar)
        result["executable_jar_sha256"] = hashlib.sha256(executable_jar.read_bytes()).hexdigest()
    for path in sorted((repo / "webapp/src/main/resources/db/migration").glob("V*.sql")):
        result["source_sha256"][str(path.relative_to(repo))] = hashlib.sha256(path.read_bytes()).hexdigest()
    (artifact / "classpath.txt").write_text(classpath + "\n")
    save_json(artifact / "result.json", result)
    if args.prepare_only:
        print(f"PREPARED_ONLY; no Java/MySQL executed; {artifact / 'result.json'}")
        return 0
    environment = clean_environment()
    for key in list(environment):
        if key.startswith(("SERVER_", "MANAGEMENT_", "AZURE_", "AWS_")) or key in (
                "JAVA_OPTS", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
            del environment[key]
    password = secrets.token_hex(32)
    mysql = shutil.which(args.mysql)
    require(mysql is not None, "mysql executable not found")
    socket = args.socket.resolve()
    require(socket.exists() and stat.S_ISSOCK(socket.stat().st_mode), "A local UNIX socket is required")
    admin = Mysql([mysql, "--no-defaults", "--protocol=SOCKET", "--socket=" + str(socket),
                   "--user=" + args.admin_user], environment, password)
    identity = admin.identity()
    require(identity["version"].startswith("8.") and "mariadb" not in identity["version"].lower(), "Native MySQL 8 required")
    require(Path(identity["socket"]).resolve() == socket and 1 <= int(identity["port"]) <= 65535,
            "Server socket/port identity differs")
    database = "mojitomainttest" + uuid.uuid4().hex[:16]
    username = "mjmt" + uuid.uuid4().hex[:16]
    require(admin.value(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}';") == "0",
            "Refusing an existing database")
    require(admin.value(f"SELECT COUNT(*) FROM mysql.user WHERE User='{username}';") == "0", "Refusing an existing user")
    result.update(database=database, account=username, socket_identity=identity)
    save_json(artifact / "result.json", result)
    owned = False
    sentinel = None
    requests = []
    try:
        owned = True
        admin.value(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        for host in ("localhost", "127.0.0.1"):
            admin.value(f"CREATE USER '{username}'@'{host}' IDENTIFIED BY '{password}';")
            admin.value(f"GRANT ALL PRIVILEGES ON `{database}`.* TO '{username}'@'{host}';")

        class Sentinel(BaseHTTPRequestHandler):
            def reject(self):
                requests.append({"method": self.command, "path": self.path})
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()

            do_GET = do_HEAD = do_PUT = do_POST = do_DELETE = do_PATCH = do_OPTIONS = reject

            def log_message(self, *_):
                pass

        sentinel = ThreadingHTTPServer(("127.0.0.1", 0), Sentinel)
        threading.Thread(target=sentinel.serve_forever, daemon=True).start()
        with tempfile.TemporaryDirectory(prefix="mojito-maintenance-mysql-") as private_dir:
            private = Path(private_dir)
            client = private / "client.cnf"
            private_write(client, f"[client]\nuser={username}\npassword={password}\nhost=127.0.0.1\n"
                          f"port={identity['port']}\nprotocol=TCP\ndatabase={database}\n")
            scoped = Mysql([mysql, "--defaults-file=" + str(client)], environment, password)
            tcp_identity = scoped.identity()
            require(tcp_identity["server_uuid"] == identity["server_uuid"] and tcp_identity["database"] == database
                    and tcp_identity["account"].split("@")[0] == username, "Loopback TCP identity differs")
            result["tcp_identity"] = tcp_identity
            dummy_key = base64.b64encode(bytes(32)).decode()
            configuration = {
                "spring.datasource.url": f"jdbc:mysql://127.0.0.1:{identity['port']}/{database}?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true",
                "spring.datasource.username": username, "spring.datasource.password": password,
                "spring.datasource.driver-class-name": "com.mysql.cj.jdbc.Driver",
                "l10n.azure.blob-storage.connection-string": "DefaultEndpointsProtocol=http;AccountName=maintenanceprobe;"
                    f"AccountKey={dummy_key};BlobEndpoint=http://127.0.0.1:{sentinel.server_port}/maintenanceprobe;",
                "l10n.azure.blob-storage.container": "maintenanceprobe",
                "l10n.blob-storage.azure.prefix": "maintenance-startup-probe",
                "l10n.security.authenticationType": "DATABASE",
            }
            properties = private / "application.properties"
            config_text = entrypoint.properties_text(configuration)
            private_write(properties, config_text)
            (artifact / "application-redacted.properties").write_text(config_text.replace(password, "<redacted>"))
            compiled = subprocess.run([str(javac), "-cp", classpath, "-d", str(private), str(source)],
                                      cwd=private, env=environment, capture_output=True, text=True, timeout=60)
            (artifact / "javac.log").write_text((compiled.stdout + compiled.stderr).replace(password, "<redacted>"))
            require(compiled.returncode == 0, "External Java runner compilation failed")
            probe_classpath = str(private) + os.pathsep + classpath
            migration_report = artifact / "migration.json"
            migration_command = [str(java), "-cp", probe_classpath, "MaintenanceStartupProbe", "migrate",
                                 str(migration_report), str(properties)]
            require(run_process(migration_command, private, environment, artifact / "migration.log", password) == 0,
                    "Real Flyway bootstrap failed")
            result["migration"] = json.loads(migration_report.read_text())
            require(result["migration"]["latestMigration"] == "122", "Expected full Flyway history through V122")
            scoped.value("INSERT INTO mblob(id,created_date,content,expire_after_seconds,name) VALUES "
                         "(1,'2000-01-01',X'00017F80FF',1,'pollable_task/startup-expired/output'),"
                         "(2,'2000-01-01',X'414243',NULL,'multi_branch_state/startup-permanent');"
                         "INSERT INTO pollable_task(id,name,expected_sub_task_number,created_date,finished_date) "
                         "VALUES(1,'maintenance-startup-old-task',0,'2000-01-01','2000-01-02');")
            baseline = fingerprint(admin, database)
            save_json(artifact / "database-before.json", baseline)
            require(baseline["tables"]["user"]["count"] == 0, "Unexpected users before maintenance startup")
            for promotion in ("false", "true"):
                before_requests = len(requests)
                actual = entrypoint.arguments({"MOJITO_MAINTENANCE_PROMOTION_ENABLED": promotion}, properties)
                jar = actual.index("-jar")
                application_args = actual[jar + 2:]
                application_args = ["--server.port=0" if value == "--server.port=8080" else value
                                    for value in application_args]
                report_path = artifact / ("promotion-" + promotion + ".json")
                command = [str(java), *actual[1:jar], "-cp", probe_classpath,
                           "MaintenanceStartupProbe", "startup", str(report_path), *application_args]
                save_json(artifact / ("promotion-" + promotion + "-command.json"), command)
                print(f"Maintenance startup promotion={promotion}; artifacts: {artifact}", flush=True)
                code = run_process(command, private, environment, artifact / ("promotion-" + promotion + ".log"), password)
                observed = json.loads(report_path.read_text().replace(password, "<redacted>")) if report_path.exists() else {
                    "status": "FAILED", "error": "Java runner did not produce context evidence"}
                save_json(report_path, observed)
                after = fingerprint(admin, database)
                save_json(artifact / ("database-after-" + promotion + ".json"), after)
                mode = {"promotion": promotion, "exit_code": code, "context": observed,
                        "database_unchanged": after == baseline, "azure_http_calls": len(requests) - before_requests}
                mode["status"] = "PASS" if (code == 0 and observed["status"] == "PASS" and after == baseline
                    and len(requests) == before_requests and observed.get("migrationEnabled") == (promotion == "true")
                    and observed.get("promotionEnabled") == (promotion == "true")) else "FAILED"
                result["modes"].append(mode)
                save_json(artifact / "result.json", result)
            if executable_jar:
                before_requests = len(requests)
                actual = entrypoint.arguments({"MOJITO_MAINTENANCE_PROMOTION_ENABLED": "true"}, properties)
                jar_index = actual.index("-jar")
                actual[0] = str(java)
                actual[jar_index + 1] = str(executable_jar)
                actual = ["--server.port=0" if value == "--server.port=8080" else value for value in actual]
                save_json(artifact / "executable-jar-command.json", actual)
                print(f"Actual executable jar startup promotion=true; artifacts: {artifact}", flush=True)
                observed = run_executable_jar(actual, private, environment,
                                              artifact / "executable-jar.log", password)
                after = fingerprint(admin, database)
                save_json(artifact / "database-after-executable-jar.json", after)
                observed["database_unchanged"] = after == baseline
                observed["azure_http_calls"] = len(requests) - before_requests
                if after != baseline or len(requests) != before_requests:
                    observed["status"] = "FAILED"
                result["executable_jar"] = observed
                save_json(artifact / "result.json", result)
            result["database_evidence"] = capture_database(admin, database, artifact)
            require(all(value["count"] == 0 for table, value in baseline["tables"].items()
                        if table.startswith("mblob_migration_")), "Migration evidence was not empty at baseline")
            require(len(requests) == 0 and all(mode["status"] == "PASS" for mode in result["modes"])
                    and (not executable_jar or result["executable_jar"]["status"] == "PASS"),
                    "Maintenance startup failed or changed DB/Azure state; inspect mode evidence")
            result["status"] = "PASS"
    except BaseException as error:
        result["status"] = "FAILED"
        result["error"] = (type(error).__name__ + ": " + str(error)).replace(password, "<redacted>")
    finally:
        if sentinel:
            sentinel.shutdown()
            sentinel.server_close()
        result["azure_http_calls"] = len(requests)
        save_json(artifact / "azure-sentinel-requests.json", requests)
        if owned:
            try:
                require(admin.identity()["server_uuid"] == identity["server_uuid"], "Server changed; cleanup refused")
                admin.value(f"DROP DATABASE IF EXISTS `{database}`;")
                for host in ("localhost", "127.0.0.1"):
                    admin.value(f"DROP USER IF EXISTS '{username}'@'{host}';")
                require(admin.value(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}';") == "0"
                        and admin.value(f"SELECT COUNT(*) FROM mysql.user WHERE User='{username}';") == "0",
                        "Disposable DB/accounts remain")
                result["cleanup_verified"] = True
            except BaseException as error:
                result["status"] = "FAILED"
                result["cleanup_error"] = str(error).replace(password, "<redacted>")
        save_json(artifact / "result.json", result)
    print(f"{result['status']}; cleanup verified={result['cleanup_verified']}; {artifact / 'result.json'}")
    return 0 if result["status"] == "PASS" and result["cleanup_verified"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--classpath-report", type=Path)
    parser.add_argument("--executable-jar", type=Path,
                        help="Also run the actual executable jar with promotion=true (default: off)")
    parser.add_argument("--mysql", default=shutil.which("mysql") or "/opt/homebrew/opt/mysql@8.0/bin/mysql")
    parser.add_argument("--socket", type=Path, default=Path("/tmp/mysql.sock"))
    parser.add_argument("--admin-user", default="root")
    parser.add_argument("--output", type=Path,
                        default=Path.home() / ".cache/mojito-storage-validation-20260914/maintenance-startup")
    parser.add_argument("--prepare-only", action="store_true")
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
