"""Start only the explicitly enabled, local-only maintenance application."""

import json
import os
from pathlib import Path
import sys
import zipfile


APP_JAR = Path("/usr/local/mojito/bin/mojito-webapp.jar")
APP_CONFIGURATION = Path("/etc/mojito-maintenance/application.json")
WORK = Path("/tmp/mojito-maintenance")
ALLOWED_CONFIGURATION_PREFIXES = (
    "spring.datasource.", "l10n.azure.blob-storage.", "l10n.security.", "spring.security.",
)
ALLOWED_CONFIGURATION_KEYS = {"l10n.blob-storage.azure.prefix"}
SA_DIRECTORY = "/var/run/secrets/maintenance-kubernetes"


def required(environment, key):
    value = environment.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Missing reviewed maintenance configuration")
    return value


def properties_text(configuration):
    if not isinstance(configuration, dict) or not configuration:
        raise ValueError("Supply reviewed connection and authentication configuration")
    lines = []
    for key, value in sorted(configuration.items()):
        if not isinstance(key, str) or not (key.startswith(ALLOWED_CONFIGURATION_PREFIXES) or key in ALLOWED_CONFIGURATION_KEYS):
            raise ValueError("Application configuration contains an unreviewed property family")
        if not isinstance(value, str) or any(character in key for character in "\\\r\n:= \t"):
            raise ValueError("Application configuration must contain simple string properties")
        # ASCII Java properties preserve arbitrary Unicode and never let values create new keys.
        escaped = "".join(
            "\\\\" if char == "\\" else "\\n" if char == "\n" else "\\r" if char == "\r"
            else "\\ " if char == " " else char if 33 <= ord(char) <= 126
            else "".join(f"\\u{int.from_bytes(encoded[i:i+2], 'big'):04x}"
                         for encoded in [char.encode("utf-16-be")]
                         for i in range(0, len(encoded), 2))
            for char in value
        )
        lines.append(f"{key}={escaped}\n")
    return "".join(lines)


def arguments(environment, properties_path):
    promotion = environment.get("MOJITO_MAINTENANCE_PROMOTION_ENABLED", "false")
    if promotion not in ("true", "false"):
        raise ValueError("Promotion must be explicitly true or false")
    return [
        "java", "-Xms256m", "-Xmx2g", "-Djava.security.egd=file:/dev/./urandom",
        "-jar", str(APP_JAR),
        "--spring.config.location=classpath:/config/application.properties,file:" + str(properties_path),
        # Keep controller-required scheduler services wired; suppress execution, not their beans.
        "--spring.profiles.active=maintenance", "--spring.quartz.auto-startup=false",
        "--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
        # Schema compatibility is a reviewed preflight; legacy CHAR mappings fail Hibernate validation.
        "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.defer-datasource-initialization=false",
        "--l10n.bootstrap.enabled=false",
        "--l10n.org.multi-quartz.enabled=false", "--l10n.org.quartz.scheduler.enabled=false",
        "--l10n.org.quartz.jobStore.class=org.quartz.simpl.RAMJobStore",
        "--l10n.org.async-job-queue.enabled=false",
        "--l10n.org.async-job-queue.retention.enabled=false",
        "--l10n.management.metrics.quartz.sql-queue-monitoring.enabled=false",
        "--l10n.ai-review.cleanup-enabled=false",
        "--l10n.azure.blob-storage.enabled=true", "--l10n.blob-storage.default-type=azure",
        "--l10n.blob-storage.database.cleanup-enabled=false",
        "--l10n.blob-storage.database.policy-cleanup-enabled=false",
        "--l10n.image-service.storage.type=blobStorage",
        "--l10n.image-service.migration.enabled=false", "--l10n.pollable-task.archive.enabled=false",
        "--l10n.pollable-task.archive.scheduling-enabled=false",
        "--l10n.pollable-task.archive.delete-source=false",
        "--l10n.blob-storage.migration.scheduling-enabled=false",
        "--l10n.blob-storage.migration.enabled=" + promotion,
        "--l10n.blob-storage.migration.promotion-enabled=" + promotion,
        "--l10n.blob-storage.migration.fence-verifier-executable=/opt/mojito-maintenance/verify_fence.py",
        "--server.address=127.0.0.1", "--server.port=8080",
    ]


def main():
    if os.environ.get("MOJITO_MAINTENANCE_START") != "true":
        print("Maintenance application is disabled; render an explicitly approved manual-start pod.")
        return 0
    environment = dict(os.environ)
    for key in list(environment):
        if key.startswith(("SPRING_", "L10N_", "SERVER_", "MANAGEMENT_")) or key in (
            "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "SPRING_APPLICATION_JSON",
        ):
            del environment[key]
    context = required(environment, "MOJITO_MAINTENANCE_CONTEXT")
    namespace = required(environment, "MOJITO_MAINTENANCE_NAMESPACE")
    for key in ("RUN_ID", "FENCE_ID", "DESTINATION_ROOT", "APPROVAL_REFERENCE"):
        required(environment, "MOJITO_MAINTENANCE_" + key)
    with zipfile.ZipFile(APP_JAR) as jar:
        jar.getinfo("BOOT-INF/classes/com/box/l10n/mojito/service/blobstorage/migration/BlobMigrationMaintenanceFence.class")
        dispatch = jar.read("BOOT-INF/classes/com/box/l10n/mojito/service/oaireview/AiReviewDispatchService.class")
        if b"l10n.ai-review.cleanup-enabled" not in dispatch:
            raise ValueError("Application image lacks the required AI Review cleanup opt-out")
    WORK.mkdir(mode=0o700, parents=True, exist_ok=True)
    properties_path = WORK / "application.properties"
    with properties_path.open("w", encoding="ascii") as output:
        configuration = json.loads(APP_CONFIGURATION.read_text())
        for key in ("spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                    "l10n.azure.blob-storage.container", "l10n.blob-storage.azure.prefix"):
            required(configuration, key)
        if not configuration.get("l10n.azure.blob-storage.connection-string") and not configuration.get("l10n.azure.blob-storage.endpoint"):
            raise ValueError("Supply the explicit reviewed Azure connection")
        output.write(properties_text(configuration))
    properties_path.chmod(0o600)
    kubeconfig = {
        "apiVersion": "v1", "kind": "Config", "current-context": context,
        "clusters": [{"name": "in-cluster", "cluster": {
            "server": "https://kubernetes.default.svc", "certificate-authority": SA_DIRECTORY + "/ca.crt",
        }}],
        "users": [{"name": "read-only-observer", "user": {"tokenFile": SA_DIRECTORY + "/token"}}],
        "contexts": [{"name": context, "context": {"cluster": "in-cluster", "user": "read-only-observer", "namespace": namespace}}],
    }
    kubeconfig_path = WORK / "kubeconfig.json"
    kubeconfig_path.write_text(json.dumps(kubeconfig))
    kubeconfig_path.chmod(0o600)
    environment["KUBECONFIG"] = str(kubeconfig_path)
    os.execvpe("java", arguments(environment, properties_path), environment)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, zipfile.BadZipFile):
        print("Maintenance startup refused: review the image and explicit configuration.", file=sys.stderr)
        sys.exit(1)
