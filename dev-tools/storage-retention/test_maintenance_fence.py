"""Offline failure-path tests for the read-only maintenance verifier.

No test talks to Kubernetes. The fixtures represent the API observations, including stale
controller status, terminating pods, and a maintenance image hidden behind a sidecar.
"""

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "maintenance_fence", Path(__file__).with_name("verify-maintenance-fence.py")
)
fence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fence)

NOW = 2_000_000_000
RUN_ID = "snapshot-run"
FENCE_ID = "approved-maintenance-window"
DESTINATION = "https://example.blob.core.windows.net/mojito/canonical/"
IMAGE = "registry.example/mojito@sha256:" + "a" * 64


class MaintenanceFenceTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "version": 1,
            "runId": RUN_ID,
            "fenceId": FENCE_ID,
            "destinationRoot": DESTINATION,
            "validFromEpochSeconds": NOW - 60,
            "validUntilEpochSeconds": NOW + 600,
            "approvalReference": "reviewed-change-record",
            "operatorAttestations": {
                "externalReconcilersStopped": True,
                "externalAzureWritersStopped": True,
                "cleanupAndFallbackWritersStopped": True,
                "maintenanceInstanceOnlyPromotionEnabled": True,
                "azureLifecyclePreservesMaintenanceAndRollbackWindow": True,
            },
            "context": "reviewed-staging-context",
            "namespace": "mojito-test",
            "protectedDeploymentPrefix": "mojito-",
            "deployments": [
                {"name": "mojito-api", "uid": "api-uid"},
                {"name": "mojito-worker", "uid": "worker-uid"},
            ],
            "maintenancePod": {
                "name": "storage-maintenance",
                "uid": "maintenance-uid",
                "imageID": IMAGE,
                "containerName": "promotion",
            },
        }
        self.deployments = [
            {
                "metadata": {"name": item["name"], "uid": item["uid"], "generation": 7},
                "spec": {
                    "replicas": 0,
                    "selector": {"matchLabels": {"app": item["name"]}},
                },
                "status": {
                    "observedGeneration": 7,
                    "replicas": 0,
                    "readyReplicas": 0,
                    "availableReplicas": 0,
                    "updatedReplicas": 0,
                },
            }
            for item in self.manifest["deployments"]
        ]
        self.pods = {"app=mojito-api": [], "app=mojito-worker": []}
        self.hpas = []
        self.maintenance = {
            "metadata": {"name": "storage-maintenance", "uid": "maintenance-uid"},
            "spec": {"containers": [{"name": "promotion", "image": IMAGE}]},
            "status": {
                "phase": "Running",
                "containerStatuses": [
                    {"name": "promotion", "ready": True, "imageID": IMAGE}
                ],
            },
        }
        self.queries = []

    def query(self, context, namespace, arguments):
        self.assertEqual(context, "reviewed-staging-context")
        self.assertEqual(namespace, "mojito-test")
        self.queries.append(arguments)
        if arguments == ["deployments"]:
            return {"items": copy.deepcopy(self.deployments)}
        if arguments[:2] == ["pods", "-l"]:
            return {"items": copy.deepcopy(self.pods[arguments[2]])}
        if arguments == ["hpa"]:
            return {"items": copy.deepcopy(self.hpas)}
        if arguments == ["pod", "storage-maintenance"]:
            return copy.deepcopy(self.maintenance)
        self.fail(f"Unexpected observation: {arguments!r}")

    def verify(self, *, started=NOW, finished=NOW, **identities):
        values = iter((started, finished))
        return fence.verify(
            json.dumps(self.manifest).encode(),
            identities.get("run_id", RUN_ID),
            identities.get("fence_id", FENCE_ID),
            identities.get("destination_root", DESTINATION),
            query=self.query,
            clock=lambda: next(values),
        )

    def test_valid_pause_attests_exact_identity_manifest_and_short_lifetime(self):
        result = self.verify(finished=NOW + 20)
        self.assertEqual(
            result,
            {
                "version": 1,
                "runId": RUN_ID,
                "fenceId": FENCE_ID,
                "destinationRoot": DESTINATION,
                "manifestSha256": hashlib.sha256(json.dumps(self.manifest).encode()).hexdigest(),
                "verifiedAtEpochSeconds": NOW + 20,
                "validUntilEpochSeconds": NOW + 120,
            },
        )
        self.assertEqual(
            self.queries,
            [["deployments"], ["pods", "-l", "app=mojito-api"],
             ["pods", "-l", "app=mojito-worker"], ["hpa"],
             ["pod", "storage-maintenance"]],
        )

    def test_pause_requires_controller_to_observe_latest_generation(self):
        self.deployments[0]["status"]["observedGeneration"] = 6
        with self.assertRaisesRegex(fence.FenceDenied, "not observed"):
            self.verify()

    def test_zero_observed_counts_cannot_override_positive_desired_replicas(self):
        self.deployments[0]["spec"]["replicas"] = 1
        with self.assertRaisesRegex(fence.FenceDenied, "admits replicas"):
            self.verify()

    def test_every_reported_replica_count_must_be_drained(self):
        for field in ("replicas", "readyReplicas", "availableReplicas", "updatedReplicas"):
            with self.subTest(field=field):
                self.deployments[0]["status"][field] = 1
                with self.assertRaisesRegex(fence.FenceDenied, "not drained"):
                    self.verify()
                self.deployments[0]["status"][field] = 0

    def test_missing_zero_count_fields_are_valid_kubernetes_omissions(self):
        self.deployments[0]["status"] = {"observedGeneration": 7}
        self.verify()

    def test_deployment_deletion_is_not_a_completed_pause(self):
        self.deployments[0]["metadata"]["deletionTimestamp"] = "present"
        with self.assertRaisesRegex(fence.FenceDenied, "being deleted"):
            self.verify()

    def test_running_pending_and_terminating_pods_all_block_attestation(self):
        for state in ({"phase": "Running"}, {"phase": "Pending"}, {"phase": "Succeeded"}):
            with self.subTest(state=state):
                self.pods["app=mojito-worker"] = [{"status": state}]
                with self.assertRaisesRegex(fence.FenceDenied, "pods remain"):
                    self.verify()
        self.pods["app=mojito-worker"] = [{"metadata": {"deletionTimestamp": "present"}}]
        with self.assertRaisesRegex(fence.FenceDenied, "terminating"):
            self.verify()

    def test_hpa_targeting_either_writer_can_restart_it(self):
        for name in ("mojito-api", "mojito-worker"):
            with self.subTest(name=name):
                self.hpas = [{"spec": {"scaleTargetRef": {"name": name, "kind": "Deployment"}}}]
                with self.assertRaisesRegex(fence.FenceDenied, "Autoscaler"):
                    self.verify()

    def test_unrelated_hpa_does_not_block_reviewed_workloads(self):
        self.hpas = [{"spec": {"scaleTargetRef": {"name": "unrelated-service"}}}]
        self.verify()

    def test_new_protected_deployment_invalidates_reviewed_inventory(self):
        added = copy.deepcopy(self.deployments[0])
        added["metadata"]["name"] = "mojito-new-worker"
        self.deployments.append(added)
        with self.assertRaisesRegex(fence.FenceDenied, "inventory changed"):
            self.verify()

    def test_missing_protected_deployment_invalidates_reviewed_inventory(self):
        self.deployments.pop()
        with self.assertRaisesRegex(fence.FenceDenied, "inventory changed"):
            self.verify()

    def test_recreated_deployment_with_same_name_fails_uid_pin(self):
        self.deployments[0]["metadata"]["uid"] = "replacement-uid"
        with self.assertRaisesRegex(fence.FenceDenied, "identity changed"):
            self.verify()

    def test_duplicate_manifest_entries_cannot_hide_missing_workload(self):
        self.manifest["deployments"][1] = self.manifest["deployments"][0]
        with self.assertRaisesRegex(fence.FenceDenied, "inventory"):
            self.verify()

    def test_recreated_maintenance_pod_is_not_the_approved_instance(self):
        self.maintenance["metadata"]["uid"] = "replacement-uid"
        with self.assertRaisesRegex(fence.FenceDenied, "pinned standalone"):
            self.verify()

    def test_controller_owned_maintenance_pod_is_rejected(self):
        self.maintenance["metadata"]["ownerReferences"] = [{"kind": "ReplicaSet", "uid": "rs-uid"}]
        with self.assertRaisesRegex(fence.FenceDenied, "pinned standalone"):
            self.verify()

    def test_terminating_maintenance_pod_is_rejected(self):
        self.maintenance["metadata"]["deletionTimestamp"] = "present"
        with self.assertRaisesRegex(fence.FenceDenied, "pinned standalone"):
            self.verify()

    def test_wrong_maintenance_image_or_container_identity_is_rejected(self):
        container = self.maintenance["status"]["containerStatuses"][0]
        for field, changed in (("imageID", "wrong-image"), ("name", "wrong-container"), ("ready", False)):
            with self.subTest(field=field):
                original = container[field]
                container[field] = changed
                with self.assertRaisesRegex(fence.FenceDenied, "image or readiness"):
                    self.verify()
                container[field] = original

    def test_correct_image_on_sidecar_cannot_hide_unapproved_promotion_image(self):
        self.maintenance["status"]["containerStatuses"][0]["imageID"] = "unapproved-app-image"
        self.maintenance["spec"]["containers"].append({"name": "sidecar", "image": IMAGE})
        self.maintenance["status"]["containerStatuses"].append(
            {"name": "sidecar", "ready": True, "imageID": IMAGE}
        )
        with self.assertRaisesRegex(fence.FenceDenied, "only the reviewed"):
            self.verify()

    def test_extra_init_or_ephemeral_containers_can_bypass_writer_drain(self):
        for field in ("initContainers", "ephemeralContainers"):
            with self.subTest(field=field):
                self.maintenance["spec"][field] = [{"name": "unreviewed-writer"}]
                with self.assertRaisesRegex(fence.FenceDenied, "only the reviewed"):
                    self.verify()
                del self.maintenance["spec"][field]

    def test_missing_maintenance_container_pin_is_rejected(self):
        del self.manifest["maintenancePod"]["containerName"]
        with self.assertRaisesRegex(fence.FenceDenied, "instance pin"):
            self.verify()

    def test_all_external_writer_attestations_require_literal_true(self):
        for field in self.manifest["operatorAttestations"]:
            for value in (False, None, 1, "true"):
                with self.subTest(field=field, value=value):
                    self.manifest["operatorAttestations"][field] = value
                    with self.assertRaisesRegex(fence.FenceDenied, "not attested"):
                        self.verify()
            self.manifest["operatorAttestations"][field] = True

    def test_missing_operator_approval_is_rejected(self):
        del self.manifest["approvalReference"]
        with self.assertRaisesRegex(fence.FenceDenied, "approval reference"):
            self.verify()

    def test_malformed_operator_approval_is_not_an_attestation(self):
        for value in (True, ["record"], {"record": "id"}, "   "):
            with self.subTest(value=value):
                self.manifest["approvalReference"] = value
                with self.assertRaises(fence.FenceDenied):
                    self.verify()

    def test_json_boolean_is_not_the_integer_manifest_version(self):
        self.manifest["version"] = True
        with self.assertRaisesRegex(fence.FenceDenied, "manifest"):
            self.verify()

    def test_request_identity_is_exact_and_cannot_be_reused(self):
        for field, value in (("run_id", "another-run"), ("fence_id", "another-window"),
                             ("destination_root", DESTINATION.rstrip("/")), ("run_id", "")):
            with self.subTest(field=field, value=value):
                with self.assertRaisesRegex(fence.FenceDenied, "identity mismatch"):
                    self.verify(**{field: value})
        self.assertEqual(self.queries, [])

    def test_malformed_json_never_queries_kubernetes(self):
        with self.assertRaisesRegex(fence.FenceDenied, "Invalid maintenance manifest"):
            fence.verify(b"{invalid", RUN_ID, FENCE_ID, DESTINATION, query=self.query, clock=lambda: NOW)
        self.assertEqual(self.queries, [])

    def test_manifest_times_are_positive_integer_epochs(self):
        for value in (True, NOW * 1.0, str(NOW), None, 0, -1):
            with self.subTest(value=value):
                self.manifest["validFromEpochSeconds"] = value
                with self.assertRaisesRegex(fence.FenceDenied, "manifest time"):
                    self.verify()

    def test_future_expired_and_excessive_maintenance_windows_are_rejected(self):
        for start, end in ((NOW + 1, NOW + 600), (NOW - 60, NOW), (NOW - 60, NOW + 86_400)):
            with self.subTest(start=start, end=end):
                self.manifest["validFromEpochSeconds"] = start
                self.manifest["validUntilEpochSeconds"] = end
                with self.assertRaisesRegex(fence.FenceDenied, "window"):
                    self.verify()

    def test_observation_budget_and_clock_rollback_are_rejected(self):
        for finished in (NOW - 1, NOW + 61):
            with self.subTest(finished=finished):
                with self.assertRaisesRegex(fence.FenceDenied, "time budget"):
                    self.verify(finished=finished)
        self.verify(finished=NOW + 60)

    def test_expiry_leaves_the_minimum_action_budget_and_never_exceeds_manifest(self):
        self.manifest["validUntilEpochSeconds"] = NOW + 34
        with self.assertRaisesRegex(fence.FenceDenied, "lease remains"):
            self.verify()
        self.manifest["validUntilEpochSeconds"] = NOW + 35
        self.assertEqual(self.verify()["validUntilEpochSeconds"], NOW + 35)
        self.manifest["validUntilEpochSeconds"] = NOW + 600
        self.assertEqual(self.verify()["validUntilEpochSeconds"], NOW + 120)

    def test_selector_serialization_preserves_set_and_presence_requirements(self):
        selector = {
            "matchLabels": {"app": "mojito-worker"},
            "matchExpressions": [
                {"key": "tier", "operator": "In", "values": ["worker", "batch"]},
                {"key": "retired", "operator": "NotIn", "values": ["yes"]},
                {"key": "managed", "operator": "Exists"},
                {"key": "orphan", "operator": "DoesNotExist"},
            ],
        }
        self.assertEqual(
            fence.selector_text(selector),
            "app=mojito-worker,tier in (worker,batch),retired notin (yes),managed,!orphan",
        )
        for selector in ({}, {"matchExpressions": [{"key": "app", "operator": "In", "values": []}]},
                         {"matchExpressions": [{"key": "app", "operator": "Unsupported"}]}):
            with self.subTest(selector=selector):
                with self.assertRaises(fence.FenceDenied):
                    fence.selector_text(selector)

    def test_kubectl_is_read_only_bounded_and_does_not_invoke_a_shell(self):
        response = subprocess.CompletedProcess([], 0, b'{"items": []}', b"")
        with patch.object(fence.subprocess, "run", return_value=response) as run:
            self.assertEqual(fence.kubernetes("context", "namespace", ["pods", "-l", "app=worker"]),
                             {"items": []})
        run.assert_called_once_with(
            ["kubectl", "--context", "context", "--namespace", "namespace",
             "--request-timeout=10s", "get", "pods", "-l", "app=worker", "-o", "json"],
            capture_output=True, timeout=12, check=False,
        )

    def test_kubernetes_errors_timeouts_and_response_budget_fail_closed(self):
        responses = [subprocess.CompletedProcess([], 1, b"", b"sensitive-provider-error"),
                     subprocess.CompletedProcess([], 0, b"invalid-json", b""),
                     subprocess.CompletedProcess([], 0, b" " * (4 * 1024 * 1024 + 1), b"")]
        for response in responses:
            with self.subTest(returncode=response.returncode, size=len(response.stdout)):
                with patch.object(fence.subprocess, "run", return_value=response):
                    with self.assertRaises(fence.FenceDenied):
                        fence.kubernetes("context", "namespace", ["deployments"])
        for error in (subprocess.TimeoutExpired("kubectl", 12), OSError("unavailable")):
            with self.subTest(error=type(error).__name__):
                with patch.object(fence.subprocess, "run", side_effect=error):
                    with self.assertRaises(fence.FenceDenied):
                        fence.kubernetes("context", "namespace", ["deployments"])

    def invoke_main(self, path, implementation):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, {"MOJITO_STORAGE_FENCE_MANIFEST": str(path)}), \
             patch.object(fence.sys, "argv", ["verifier", "--run-id", RUN_ID, "--fence-id", FENCE_ID,
                                             "--destination-root", DESTINATION]), \
             patch.object(fence, "verify", side_effect=implementation), \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = fence.main()
        return result, stdout.getvalue(), stderr.getvalue()

    def test_manifest_file_must_be_private_regular_absolute_and_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_bytes(json.dumps(self.manifest).encode())
            path.chmod(0o600)
            evidence = self.verify()
            self.assertEqual(self.invoke_main(path, lambda *args: evidence)[0], 0)
            link = Path(directory) / "manifest-link.json"
            link.symlink_to(path)
            for invalid in (link, Path(directory), Path("relative.json"), path.with_name("missing.json")):
                with self.subTest(path=str(invalid)):
                    result, stdout, stderr = self.invoke_main(invalid, lambda *args: self.fail("verified unsafe file"))
                    self.assertEqual(result, 1)
                    self.assertEqual(stdout, "")
                    self.assertEqual(stderr, "Maintenance fence could not be verified\n")
            for mode, content in ((0o666, b"{}"), (0o600, b"x" * 65537)):
                with self.subTest(mode=mode, size=len(content)):
                    path.write_bytes(content)
                    path.chmod(mode)
                    self.assertEqual(self.invoke_main(path, lambda *args: self.fail("verified unsafe file"))[0], 1)

    def test_main_rejects_malformed_objects_without_leaking_content(self):
        implementation = fence.verify
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            for malformed in ([], None, {"version": 1, "runId": "private-sentinel"}):
                with self.subTest(malformed=malformed):
                    path.write_text(json.dumps(malformed))
                    path.chmod(0o600)
                    result, stdout, stderr = self.invoke_main(
                        path, lambda *args: implementation(*args, query=self.query, clock=lambda: NOW)
                    )
                    self.assertEqual((result, stdout, stderr), (1, "", "Maintenance fence could not be verified\n"))


if __name__ == "__main__":
    unittest.main()
