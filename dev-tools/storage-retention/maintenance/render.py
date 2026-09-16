#!/usr/bin/env python3
"""Render Kubernetes JSON to a new local file. This program cannot apply or start anything."""

import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit


def render(options):
    for key in ("namespace", "name", "application_config_map", "fence_config_map", "credentials_secret"):
        value = getattr(options, key)
        if len(value) > 63 or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", value):
            raise ValueError("Use explicit Kubernetes DNS-label resource names")
    if len(options.name) > 55:
        raise ValueError("Pod name must leave room for the observer resource suffix")
    if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", options.image):
        raise ValueError("The maintenance image must be digest-pinned")
    for key in ("context", "run_id", "fence_id", "approval_reference"):
        if not getattr(options, key).strip():
            raise ValueError("Supply all reviewed request identities")
    destination = urlsplit(options.destination_root)
    if destination.scheme != "https" or not destination.hostname or destination.username or destination.password or destination.query or destination.fragment:
        raise ValueError("Use the exact credential-free HTTPS Azure destination root")
    if options.enable_promotion and not options.enable_manual_start:
        raise ValueError("Promotion requires an explicitly enabled manual-start pod")
    namespace, name = options.namespace, options.name
    observer = name + "-observe"
    metadata = lambda resource: {"name": resource, "namespace": namespace}
    identity = {
        "CONTEXT": options.context, "NAMESPACE": namespace, "RUN_ID": options.run_id,
        "FENCE_ID": options.fence_id, "DESTINATION_ROOT": options.destination_root,
        "APPROVAL_REFERENCE": options.approval_reference,
        "START": str(options.enable_manual_start).lower(),
        "PROMOTION_ENABLED": str(options.enable_promotion).lower(),
    }
    probe = {"exec": {"command": ["python3", "-c",
        "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/actuator/health',timeout=3).read()"]},
        "timeoutSeconds": 5, "periodSeconds": 10}
    return {"apiVersion": "v1", "kind": "List", "items": [
        {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": metadata(observer),
         "automountServiceAccountToken": False},
        {"apiVersion": "rbac.authorization.k8s.io/v1", "kind": "Role", "metadata": metadata(observer),
         "rules": [
             {"apiGroups": ["apps"], "resources": ["deployments"], "verbs": ["get", "list"]},
             {"apiGroups": [""], "resources": ["pods"], "verbs": ["get", "list"]},
             {"apiGroups": ["autoscaling"], "resources": ["horizontalpodautoscalers"], "verbs": ["get", "list"]},
         ]},
        {"apiVersion": "rbac.authorization.k8s.io/v1", "kind": "RoleBinding", "metadata": metadata(observer),
         "subjects": [{"kind": "ServiceAccount", "name": observer, "namespace": namespace}],
         "roleRef": {"apiGroup": "rbac.authorization.k8s.io", "kind": "Role", "name": observer}},
        {"apiVersion": "v1", "kind": "Pod", "metadata": {
            **metadata(name), "labels": {"mojito-storage-maintenance": "true"},
            "annotations": {"sidecar.istio.io/inject": "false", "linkerd.io/inject": "disabled",
                            "mojito-storage-maintenance/approval-reference": options.approval_reference}},
         "spec": {
             "restartPolicy": "Never", "activeDeadlineSeconds": 86400,
             "serviceAccountName": observer, "automountServiceAccountToken": False,
             "enableServiceLinks": False, "terminationGracePeriodSeconds": 60,
             "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                                 "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
             "containers": [{
                 "name": "promotion", "image": options.image, "imagePullPolicy": "IfNotPresent",
                 "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                                     "capabilities": {"drop": ["ALL"]}},
                 "env": [{"name": "MOJITO_MAINTENANCE_" + key, "value": value} for key, value in identity.items()],
                 "envFrom": [{"prefix": "MOJITO_SECRET_", "secretRef": {"name": options.credentials_secret}}],
                 "resources": {"requests": {"cpu": "250m", "memory": "1Gi"},
                               "limits": {"cpu": "2", "memory": "3Gi"}},
                 "readinessProbe": probe,
                 "volumeMounts": [
                     {"name": "temporary", "mountPath": "/tmp"},
                     {"name": "application", "mountPath": "/etc/mojito-maintenance", "readOnly": True},
                     {"name": "approval", "mountPath": "/var/run/mojito-maintenance-approval", "readOnly": True},
                     {"name": "observer-token", "mountPath": "/var/run/secrets/maintenance-kubernetes", "readOnly": True},
                 ],
             }],
             "volumes": [
                 {"name": "temporary", "emptyDir": {"sizeLimit": "512Mi"}},
                 {"name": "application", "configMap": {"name": options.application_config_map, "defaultMode": 288}},
                 {"name": "approval", "configMap": {"name": options.fence_config_map, "optional": True, "defaultMode": 288}},
                 {"name": "observer-token", "projected": {"defaultMode": 288, "sources": [
                     {"serviceAccountToken": {"path": "token", "expirationSeconds": 600}},
                     {"configMap": {"name": "kube-root-ca.crt", "items": [{"key": "ca.crt", "path": "ca.crt"}]}},
                 ]}},
             ],
         }},
    ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("namespace", "name", "context", "image", "run-id", "fence-id", "destination-root",
                "approval-reference", "application-config-map", "fence-config-map", "credentials-secret", "output"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--enable-manual-start", action="store_true")
    parser.add_argument("--enable-promotion", action="store_true")
    options = parser.parse_args()
    result = render(options)
    # Exclusive local creation prevents a render from silently replacing already reviewed output.
    with Path(options.output).open("x") as output:
        json.dump(result, output, indent=2)
        output.write("\n")
    print("Rendered only; no cluster operations were performed.")


if __name__ == "__main__":
    main()
