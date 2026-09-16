"""Offline safety tests: never start Java, contact Azure, or mutate a cluster."""

import argparse
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


entrypoint = module("entrypoint")
renderer = module("render")
wrapper = module("verify_fence")
installer = module("install_kubectl")
IMAGE = "example.invalid/maintenance@sha256:" + "a" * 64
IDENTITIES = {
    "context": "reviewed-cluster", "namespace": "reviewed-namespace", "runId": "run-1",
    "fenceId": "fence-1", "destinationRoot": "https://example.blob.core.windows.net/container/prefix",
    "approvalReference": "reviewed-change-1",
}
ENVIRONMENT = {"MOJITO_MAINTENANCE_" + suffix: IDENTITIES[field] for field, suffix in (
    ("context", "CONTEXT"), ("namespace", "NAMESPACE"), ("runId", "RUN_ID"),
    ("fenceId", "FENCE_ID"), ("destinationRoot", "DESTINATION_ROOT"), ("approvalReference", "APPROVAL_REFERENCE"))}


class Launched(Exception):
    pass


class RenderingTests(unittest.TestCase):
    def options(self, **changes):
        result = dict(namespace="reviewed-namespace", name="maintenance", context="reviewed-cluster",
                      image=IMAGE, run_id="run-1", fence_id="fence-1",
                      destination_root=IDENTITIES["destinationRoot"], approval_reference="reviewed-change-1",
                      application_config_map="reviewed-application", fence_config_map="reviewed-fence",
                      credentials_secret="reviewed-credentials", enable_promotion=False, enable_manual_start=False)
        return argparse.Namespace(**(result | changes))

    def test_default_is_a_disabled_standalone_pod(self):
        items = renderer.render(self.options())["items"]
        self.assertEqual([item["kind"] for item in items], ["ServiceAccount", "Role", "RoleBinding", "Pod"])
        pod = items[-1]
        self.assertNotIn("ownerReferences", pod["metadata"])
        self.assertEqual(pod["spec"]["restartPolicy"], "Never")
        self.assertEqual(len(pod["spec"]["containers"]), 1)
        self.assertNotIn("initContainers", pod["spec"])
        self.assertNotIn("ephemeralContainers", pod["spec"])
        container = pod["spec"]["containers"][0]
        environment = {entry["name"]: entry["value"] for entry in container["env"]}
        self.assertEqual(environment, ENVIRONMENT | {"MOJITO_MAINTENANCE_START": "false", "MOJITO_MAINTENANCE_PROMOTION_ENABLED": "false"})
        self.assertEqual(container["image"], IMAGE)
        self.assertEqual(container["envFrom"][0]["prefix"], "MOJITO_SECRET_")
        self.assertTrue(container["securityContext"]["readOnlyRootFilesystem"])
        self.assertEqual(container["securityContext"]["capabilities"]["drop"], ["ALL"])

    def test_rbac_cannot_write_read_secrets_or_cross_namespace(self):
        items = renderer.render(self.options())["items"]
        role = items[1]
        permissions = {(tuple(rule["apiGroups"]), tuple(rule["resources"]), tuple(rule["verbs"])) for rule in role["rules"]}
        self.assertEqual(permissions, {(("apps",), ("deployments",), ("get", "list")), (("",), ("pods",), ("get", "list")), (("autoscaling",), ("horizontalpodautoscalers",), ("get", "list"))})
        self.assertTrue(all(item["metadata"]["namespace"] == "reviewed-namespace" for item in items))
        self.assertEqual(items[2]["roleRef"]["kind"], "Role")
        self.assertEqual(items[2]["subjects"], [{"kind": "ServiceAccount", "name": "maintenance-observe", "namespace": "reviewed-namespace"}])

    def test_only_short_lived_explicit_token_is_mounted(self):
        items = renderer.render(self.options())["items"]
        self.assertFalse(items[0]["automountServiceAccountToken"])
        pod = items[-1]["spec"]
        self.assertFalse(pod["automountServiceAccountToken"])
        token = next(volume for volume in pod["volumes"] if volume["name"] == "observer-token")
        self.assertEqual(token["projected"]["sources"][0], {"serviceAccountToken": {"path": "token", "expirationSeconds": 600}})
        approval = next(volume for volume in pod["volumes"] if volume["name"] == "approval")
        self.assertTrue(approval["configMap"]["optional"])

    def test_promotion_cannot_be_accidentally_enabled_without_start(self):
        with self.assertRaises(ValueError):
            renderer.render(self.options(enable_promotion=True))
        env = renderer.render(self.options(enable_promotion=True, enable_manual_start=True))["items"][-1]["spec"]["containers"][0]["env"]
        self.assertEqual({e["name"]: e["value"] for e in env}["MOJITO_MAINTENANCE_PROMOTION_ENABLED"], "true")

    def test_reject_floating_image_and_credentialed_destination(self):
        for changes in ({"image": "example.invalid/maintenance:latest"}, {"image": "x@sha256:abc"},
                        {"destination_root": "http://example/container"}, {"destination_root": "https://user:password@example/container"},
                        {"destination_root": "https://example/container?sig=secret"}, {"destination_root": "https://example/container#fragment"},
                        {"namespace": ""}, {"name": "a" * 56}, {"context": " "}, {"approval_reference": ""}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                renderer.render(self.options(**changes))

    def test_cli_refuses_overwriting_reviewed_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "rendered.json"
            output.write_text("reviewed content")
            argv = ["render.py"]
            for key, value in vars(self.options()).items():
                if not key.startswith("enable_"):
                    argv += ["--" + key.replace("_", "-"), value]
            with patch("sys.argv", argv + ["--output", str(output)]), self.assertRaises(FileExistsError):
                renderer.main()
            self.assertEqual(output.read_text(), "reviewed content")


class StartupTests(unittest.TestCase):
    def test_disabled_default_does_not_read_config_or_start_java(self):
        for value in (None, "false", "TRUE", "1"):
            with self.subTest(value=value), patch.dict(os.environ, {} if value is None else {"MOJITO_MAINTENANCE_START": value}, clear=True), patch.object(entrypoint.zipfile, "ZipFile") as jar, patch.object(entrypoint.os, "execvpe") as launch, patch("sys.stdout", io.StringIO()):
                self.assertEqual(entrypoint.main(), 0)
                jar.assert_not_called()
                launch.assert_not_called()

    def test_force_writer_opt_outs_and_no_baked_configuration(self):
        args = entrypoint.arguments({}, Path("/tmp/private.properties"))
        properties = dict(arg[2:].split("=", 1) for arg in args if arg.startswith("--"))
        disabled = ("spring.flyway.enabled", "spring.jpa.defer-datasource-initialization", "spring.quartz.auto-startup", "l10n.bootstrap.enabled",
                    "l10n.org.multi-quartz.enabled", "l10n.org.quartz.scheduler.enabled", "l10n.org.async-job-queue.enabled",
                    "l10n.org.async-job-queue.retention.enabled", "l10n.management.metrics.quartz.sql-queue-monitoring.enabled",
                    "l10n.ai-review.cleanup-enabled", "l10n.blob-storage.database.cleanup-enabled",
                    "l10n.blob-storage.database.policy-cleanup-enabled", "l10n.image-service.migration.enabled",
                    "l10n.pollable-task.archive.enabled", "l10n.pollable-task.archive.scheduling-enabled", "l10n.pollable-task.archive.delete-source", "l10n.blob-storage.migration.scheduling-enabled",
                    "l10n.blob-storage.migration.enabled", "l10n.blob-storage.migration.promotion-enabled")
        for key in disabled:
            self.assertEqual(properties[key], "false", key)
        self.assertEqual(properties["spring.profiles.active"], "maintenance")
        self.assertNotIn("disablescheduling", ",".join(args))
        self.assertEqual(properties["spring.sql.init.mode"], "never")
        self.assertEqual(properties["spring.jpa.hibernate.ddl-auto"], "none")
        self.assertEqual(properties["l10n.org.quartz.jobStore.class"], "org.quartz.simpl.RAMJobStore")
        self.assertEqual(properties["l10n.blob-storage.default-type"], "azure")
        self.assertEqual(properties["l10n.image-service.storage.type"], "blobStorage")
        self.assertEqual(properties["server.address"], "127.0.0.1")
        self.assertEqual(properties["spring.config.location"], "classpath:/config/application.properties,file:/tmp/private.properties")

    def test_config_cannot_enable_schedulers_or_load_extra_config(self):
        for key in ("l10n.org.quartz.scheduler.enabled", "l10n.ai-review.cleanup-enabled", "spring.config.import", "l10n.blob-storage.migration.enabled", "spring.datasource.x\nl10n.bootstrap.enabled"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                entrypoint.properties_text({key: "true"})

    def test_configuration_values_cannot_inject_property_lines(self):
        data = entrypoint.properties_text({"spring.datasource.password": " secret\nkey=value\r\n\\path\t\u00e9\U0001f600"})
        self.assertEqual(len(data.splitlines()), 1)
        self.assertIn("\\nkey=value\\r\\n\\\\path\\u0009\\u00e9\\ud83d\\ude00", data)
        self.assertTrue(data.startswith("spring.datasource.password=\\ secret"))

    def test_explicit_promotion_only_changes_its_two_enable_flags(self):
        before = entrypoint.arguments({}, Path("/tmp/config"))
        after = entrypoint.arguments({"MOJITO_MAINTENANCE_PROMOTION_ENABLED": "true"}, Path("/tmp/config"))
        self.assertEqual([(a, b) for a, b in zip(before, after) if a != b], [
            ("--l10n.blob-storage.migration.enabled=false", "--l10n.blob-storage.migration.enabled=true"),
            ("--l10n.blob-storage.migration.promotion-enabled=false", "--l10n.blob-storage.migration.promotion-enabled=true")])
        with self.assertRaises(ValueError):
            entrypoint.arguments({"MOJITO_MAINTENANCE_PROMOTION_ENABLED": "yes"}, Path("/tmp/config"))

    def test_start_validates_image_config_and_scrubs_inherited_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            jar_path, config_path, work = base / "application.jar", base / "application.json", base / "work"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("BOOT-INF/classes/com/box/l10n/mojito/service/blobstorage/migration/BlobMigrationMaintenanceFence.class", b"present")
                jar.writestr("BOOT-INF/classes/com/box/l10n/mojito/service/oaireview/AiReviewDispatchService.class", b"l10n.ai-review.cleanup-enabled")
            config_path.write_text((ROOT / "application.example.json").read_text())
            unsafe = {"SPRING_CONFIG_IMPORT": "unsafe", "L10N_BOOTSTRAP_ENABLED": "true", "JAVA_TOOL_OPTIONS": "unsafe", "JDK_JAVA_OPTIONS": "unsafe", "SERVER_ADDRESS": "0.0.0.0", "MANAGEMENT_SERVER_PORT": "9090"}
            env = ENVIRONMENT | unsafe | {"MOJITO_MAINTENANCE_START": "true", "MOJITO_SECRET_DB_PASSWORD": "redacted"}
            with patch.dict(os.environ, env, clear=True), patch.object(entrypoint, "APP_JAR", jar_path), patch.object(entrypoint, "APP_CONFIGURATION", config_path), patch.object(entrypoint, "WORK", work), patch.object(entrypoint.os, "execvpe", side_effect=Launched) as launch, self.assertRaises(Launched):
                entrypoint.main()
            executable, args, launched_env = launch.call_args.args
            self.assertEqual(executable, "java")
            self.assertFalse(set(unsafe) & set(launched_env))
            self.assertEqual(launched_env["MOJITO_SECRET_DB_PASSWORD"], "redacted")
            kubeconfig = json.loads(Path(launched_env["KUBECONFIG"]).read_text())
            self.assertEqual(kubeconfig["contexts"][0]["context"]["namespace"], ENVIRONMENT["MOJITO_MAINTENANCE_NAMESPACE"])
            self.assertIn("tokenFile", kubeconfig["users"][0]["user"])
            self.assertNotIn("token", kubeconfig["users"][0]["user"])
            self.assertEqual(stat.S_IMODE((work / "application.properties").stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE((work / "kubeconfig.json").stat().st_mode), 0o600)

    def test_old_application_image_refused_before_connection_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            jar_path = Path(directory) / "old.jar"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("BOOT-INF/classes/com/box/l10n/mojito/service/blobstorage/migration/BlobMigrationMaintenanceFence.class", b"present")
                jar.writestr("BOOT-INF/classes/com/box/l10n/mojito/service/oaireview/AiReviewDispatchService.class", b"old")
            with patch.dict(os.environ, ENVIRONMENT | {"MOJITO_MAINTENANCE_START": "true"}, clear=True), patch.object(entrypoint, "APP_JAR", jar_path), patch.object(entrypoint.os, "execvpe") as launch, self.assertRaises(ValueError):
                entrypoint.main()
            launch.assert_not_called()


class FenceWrapperTests(unittest.TestCase):
    def test_projected_manifest_is_copied_without_changing_approval_or_request(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            real, projected, work = base / "real.json", base / "projected.json", base / "private"
            contents = json.dumps(IDENTITIES | {"operatorAttestations": {"externalReconcilersStopped": True}}).encode()
            real.write_bytes(contents)
            projected.symlink_to(real)
            with patch.dict(os.environ, ENVIRONMENT, clear=True), patch.object(wrapper, "SOURCE", projected), patch.object(wrapper, "DIRECTORY", work), patch("sys.argv", ["wrapper", "run-1", "fence-1", "destination"]), patch.object(wrapper.os, "execve", side_effect=Launched) as launch, self.assertRaises(Launched):
                wrapper.main()
            executable, args, environment = launch.call_args.args
            self.assertEqual(executable, "/opt/mojito-maintenance/verify-maintenance-fence.py")
            self.assertEqual(args[1:], ["run-1", "fence-1", "destination"])
            private = Path(environment["MOJITO_STORAGE_FENCE_MANIFEST"])
            self.assertFalse(private.is_symlink())
            self.assertEqual(private.read_bytes(), contents)
            self.assertEqual(stat.S_IMODE(private.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(work.stat().st_mode), 0o700)

    def test_every_pod_identity_must_match_before_invoking_verifier(self):
        for key in IDENTITIES:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "manifest"
                source.write_text(json.dumps(IDENTITIES | {key: "wrong"}))
                with patch.dict(os.environ, ENVIRONMENT, clear=True), patch.object(wrapper, "SOURCE", source), patch.object(wrapper.os, "execve") as launch, self.assertRaises(ValueError):
                    wrapper.main()
                launch.assert_not_called()

    def test_malformed_and_oversize_manifest_never_executes(self):
        for content in (b"[", b"x" * 65537, b"[]"):
            with self.subTest(length=len(content)), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "manifest"
                source.write_bytes(content)
                with patch.dict(os.environ, ENVIRONMENT, clear=True), patch.object(wrapper, "SOURCE", source), patch.object(wrapper.os, "execve") as launch, self.assertRaises((ValueError, AttributeError)):
                    wrapper.main()
                launch.assert_not_called()


class InstallerTests(unittest.TestCase):
    def test_unpinned_inputs_fail_before_network(self):
        for args in (("example:latest", "amd64", "v1.33.3", "a" * 64), (IMAGE, "x86", "v1.33.3", "a" * 64), (IMAGE, "amd64", "latest", "a" * 64), (IMAGE, "amd64", "v1.33.3", "abc")):
            with self.subTest(args=args), patch.object(installer.urllib.request, "urlopen") as download, self.assertRaises(ValueError):
                installer.install(*args)
            download.assert_not_called()

    def test_verified_bytes_installed_atomically_and_mismatch_preserves_previous(self):
        for valid in (True, False):
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "kubectl"
                target.write_bytes(b"previous")
                payload = b"reviewed tool binary"
                digest = hashlib.sha256(payload if valid else b"wrong").hexdigest()
                with patch.object(installer, "Path", return_value=target), patch.object(installer.urllib.request, "urlopen", return_value=io.BytesIO(payload)) as download:
                    if valid:
                        installer.install(IMAGE, "amd64", "v1.33.3", digest)
                        self.assertEqual(target.read_bytes(), payload)
                        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o555)
                    else:
                        with self.assertRaises(ValueError):
                            installer.install(IMAGE, "amd64", "v1.33.3", digest)
                        self.assertEqual(target.read_bytes(), b"previous")
                self.assertEqual(download.call_args.args[0], "https://dl.k8s.io/release/v1.33.3/bin/linux/amd64/kubectl")
                self.assertFalse(target.with_suffix(".download").exists())


if __name__ == "__main__":
    unittest.main()
