#!/usr/bin/env python3
"""Read-only Kubernetes checks for an explicitly approved, time-limited maintenance fence.

This does not stop writers. The reviewed manifest attests that external controllers and writers
have been stopped; Kubernetes observations verify the named workloads are actually drained.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


class FenceDenied(Exception):
    pass


def require(condition, message):
    if not condition:
        raise FenceDenied(message)


def epoch(value):
    require(type(value) is int and value > 0, "Invalid manifest time")
    return value


def selector_text(selector):
    """Serialize an observed selector without shell interpolation."""
    parts = [f"{key}={value}" for key, value in sorted(selector.get("matchLabels", {}).items())]
    for expression in selector.get("matchExpressions", []):
        key, operator = expression["key"], expression["operator"]
        if operator in ("In", "NotIn"):
            values = expression.get("values", [])
            require(bool(values), "Empty workload selector")
            parts.append(f"{key} {'in' if operator == 'In' else 'notin'} ({','.join(values)})")
        elif operator in ("Exists", "DoesNotExist"):
            parts.append(key if operator == "Exists" else "!" + key)
        else:
            raise FenceDenied("Unsupported workload selector")
    require(bool(parts), "Missing workload selector")
    return ",".join(parts)


def kubernetes(context, namespace, arguments):
    command = ["kubectl", "--context", context, "--namespace", namespace,
               "--request-timeout=10s", "get", *arguments, "-o", "json"]
    try:
        result = subprocess.run(command, capture_output=True, timeout=12, check=False)
        require(result.returncode == 0, "Kubernetes observation failed")
        require(len(result.stdout) <= 4 * 1024 * 1024, "Kubernetes response exceeds budget")
        return json.loads(result.stdout)
    except (subprocess.TimeoutExpired, OSError, ValueError):
        raise FenceDenied("Kubernetes observation unavailable") from None


def verify(manifest_bytes, run_id, fence_id, destination_root, query=kubernetes, clock=time.time):
    started = int(clock())
    try:
        manifest = json.loads(manifest_bytes)
    except ValueError:
        raise FenceDenied("Invalid maintenance manifest") from None
    require(type(manifest.get("version")) is int and manifest["version"] == 1,
            "Unsupported maintenance manifest")
    for key, expected in (("runId", run_id), ("fenceId", fence_id),
                          ("destinationRoot", destination_root)):
        require(manifest.get(key) == expected and bool(expected), "Maintenance identity mismatch")
    valid_from = epoch(manifest.get("validFromEpochSeconds"))
    expires = epoch(manifest.get("validUntilEpochSeconds"))
    require(valid_from <= started < expires and expires - valid_from <= 86400,
            "Maintenance window inactive or exceeds one day")
    approval_reference = manifest.get("approvalReference")
    require(isinstance(approval_reference, str) and approval_reference.strip()
            and len(approval_reference) <= 2048, "Missing operator approval reference")
    controls = manifest.get("operatorAttestations", {})
    for key in ("externalReconcilersStopped", "externalAzureWritersStopped",
                "cleanupAndFallbackWritersStopped", "maintenanceInstanceOnlyPromotionEnabled",
                "azureLifecyclePreservesMaintenanceAndRollbackWindow"):
        require(controls.get(key) is True, "External writer controls are not attested")
    context, namespace = manifest.get("context"), manifest.get("namespace")
    require(isinstance(context, str) and context and isinstance(namespace, str) and namespace,
            "Missing Kubernetes target")
    prefix = manifest.get("protectedDeploymentPrefix")
    require(isinstance(prefix, str) and re.fullmatch(r"[a-z0-9][a-z0-9-]*", prefix),
            "Missing protected deployment prefix")
    entries = manifest.get("deployments", [])
    require(isinstance(entries, list) and 1 <= len(entries) <= 20, "Invalid deployment inventory")
    expected = {entry.get("name"): entry.get("uid") for entry in entries}
    require(len(expected) == len(entries) and all(isinstance(name, str) and name.startswith(prefix)
            and isinstance(uid, str) and uid for name, uid in expected.items()),
            "Invalid pinned deployment inventory")

    deployments = query(context, namespace, ["deployments"])
    observed = {item["metadata"]["name"]: item for item in deployments.get("items", [])
                if item["metadata"]["name"].startswith(prefix)}
    require(set(observed) == set(expected), "Protected workload inventory changed")
    for name, deployment in observed.items():
        metadata, spec, status = deployment["metadata"], deployment["spec"], deployment.get("status", {})
        require(metadata.get("uid") == expected[name], "Deployment identity changed")
        require(not metadata.get("deletionTimestamp"), "Deployment is being deleted")
        require(spec.get("replicas") == 0, "Deployment still admits replicas")
        require(status.get("observedGeneration", -1) >= metadata.get("generation", 0),
                "Deployment controller has not observed the pause")
        require(all(status.get(field, 0) == 0 for field in
                    ("replicas", "readyReplicas", "availableReplicas", "updatedReplicas")),
                "Deployment is not drained")
        pods = query(context, namespace, ["pods", "-l", selector_text(spec["selector"])])
        require(pods.get("items") == [], "Workload pods remain, including terminating pods")
    autoscalers = query(context, namespace, ["hpa"])
    require(not any(item.get("spec", {}).get("scaleTargetRef", {}).get("name") in expected
                    for item in autoscalers.get("items", [])), "Autoscaler can restart a protected workload")

    maintenance = manifest.get("maintenancePod", {})
    require(maintenance.get("name") and maintenance.get("uid") and maintenance.get("imageID")
            and maintenance.get("containerName"),
            "Missing maintenance instance pin")
    pod = query(context, namespace, ["pod", maintenance["name"]])
    metadata, status = pod.get("metadata", {}), pod.get("status", {})
    require(metadata.get("uid") == maintenance["uid"] and not metadata.get("deletionTimestamp")
            and not metadata.get("ownerReferences"), "Maintenance instance is not the pinned standalone pod")
    require(status.get("phase") == "Running", "Maintenance instance is not running")
    containers = status.get("containerStatuses", [])
    require(len(pod.get("spec", {}).get("containers", [])) == 1
            and not pod.get("spec", {}).get("initContainers")
            and not pod.get("spec", {}).get("ephemeralContainers"),
            "Maintenance pod must contain only the reviewed promotion container")
    require(len(containers) == 1 and containers[0].get("ready") is True
            and containers[0].get("name") == maintenance["containerName"]
            and containers[0].get("imageID") == maintenance["imageID"],
            "Maintenance image or readiness changed")
    finished = int(clock())
    require(started <= finished <= started + 60, "Observation exceeded its time budget")
    valid_until = min(expires, started + 120)
    require(valid_until - finished >= 35, "Insufficient maintenance lease remains")
    return {"version": 1, "runId": run_id, "fenceId": fence_id, "destinationRoot": destination_root,
            "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "verifiedAtEpochSeconds": finished, "validUntilEpochSeconds": valid_until}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--fence-id", required=True)
    parser.add_argument("--destination-root", required=True)
    args = parser.parse_args()
    try:
        path = Path(os.environ.get("MOJITO_STORAGE_FENCE_MANIFEST", ""))
        require(path.is_absolute() and not path.is_symlink() and path.is_file(),
                "Configure a regular absolute maintenance manifest path")
        require(path.stat().st_mode & 0o022 == 0 and path.stat().st_size <= 65536,
                "Maintenance manifest is writable by others or too large")
        result = verify(path.read_bytes(), args.run_id, args.fence_id, args.destination_root)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (FenceDenied, KeyError, TypeError, AttributeError, OSError):
        # Do not echo arbitrary manifest content, provider output or credentials.
        print("Maintenance fence could not be verified", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
