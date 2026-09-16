# Manual maintenance runtime

This directory packages the existing Mojito application with the read-only maintenance-fence verifier. `render.py` **only writes a new local JSON file**. Its default Pod exits without starting Java; both manual start and promotion require explicit switches. Nothing here acquires a maintenance fence, pauses a deployment, changes Azure lifecycle rules, applies migrations, or performs table cutover.

**Historical validation (2026-09-14):** offline tests passed, and a local ARM64 image built from the production runtime base digest plus the exact combined application JAR booted in both maintenance modes. The default-disabled image stayed inert; all 146 database table fingerprints stayed unchanged through startup, idle observation and shutdown, the loopback Azure sentinel received no requests, and missing approval was refused.

Aggregate HTTP health remained `503 DOWN` because the fixture had no local SMTP server. This was a local runtime rehearsal using the then-current V115/V116 storage migrations, not the final production-derived image.

The current integrated V121/V122 artifact still needs CI database and image validation. Kubernetes admission, image pull, namespace permissions, network access, authentication and real Azure behavior remain approved deployment-rehearsal checks.

## Build inputs

The deployed Mojito image layout is Temurin Java 21 on Ubuntu Jammy, with `/usr/local/mojito/bin/mojito-webapp.jar`. Build from a reviewed image containing this change, pinned by digest. The launcher refuses older application images lacking the promotion-fence class or AI Review cleanup opt-out. It reads the application's classpath defaults plus one explicit connection configuration, and does not load the deployment image's additional configuration files.

Build in release CI or an explicitly requested local image-validation run. Do not start Docker or database services for routine commit preparation; the offline checks below are service-free. From the repository root, select the exact base image, architecture, Ubuntu package versions available from its signed repositories, and compatible kubectl version/checksum:

```sh
docker build \
  --platform "linux/${REVIEWED_TARGET_ARCH:?supply amd64 or arm64}" \
  --file dev-tools/storage-retention/maintenance/Dockerfile \
  --build-arg MOJITO_IMAGE="${REVIEWED_MOJITO_IMAGE:?supply image@sha256 digest}" \
  --build-arg TARGETARCH="${REVIEWED_TARGET_ARCH:?supply amd64 or arm64}" \
  --build-arg PYTHON3_PACKAGE_VERSION="${REVIEWED_PYTHON3_PACKAGE_VERSION:?supply exact package version}" \
  --build-arg CA_CERTIFICATES_PACKAGE_VERSION="${REVIEWED_CA_CERTIFICATES_PACKAGE_VERSION:?supply exact package version}" \
  --build-arg KUBECTL_VERSION="${REVIEWED_KUBECTL_VERSION:?supply exact v1.x.y version}" \
  --build-arg KUBECTL_SHA256="${REVIEWED_KUBECTL_SHA256:?supply reviewed SHA-256}" \
  --tag mojito-maintenance:local-review \
  dev-tools/storage-retention
```

Python and CA certificates are installed through authenticated APT repositories with required exact top-level package versions; transitive distro dependencies follow those signed repositories. Capture the built image's package inventory/SBOM and its immutable image digest for review. This is verified package installation, not a claim of byte-for-byte reproducible APT resolution. The kubectl installer downloads only the explicit official version, verifies the supplied SHA-256, bounds its download, and refuses mismatches. Obtain and review that checksum separately as described in the [official kubectl installation instructions](https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/); select a client compatible with the target API server.

The build checks Python syntax and runs `kubectl version --client`. A subsequent runtime rehearsal must confirm the application can start with the new schema and the fixed launch controls. Never substitute a floating tag into the rendered Pod.

## Reviewed configuration and render

Create a reviewed ConfigMap containing an `application.json` key using [application.example.json](application.example.json). It references an existing, namespace-local credential Secret with keys `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AZURE_CONNECTION_STRING`, `AZURE_CONTAINER`, and `AZURE_PREFIX`. The Pod imports them under the `MOJITO_SECRET_` prefix. Do not commit real credentials or infrastructure identifiers. Explicitly select the correct MySQL database, Azure account/container, and canonical prefix; the migration additionally checks its recorded destination root against the configured Azure target. An endpoint plus an approved managed-identity arrangement can replace a connection string; that authentication arrangement requires separate rendering and runtime verification.

The application JSON accepts datasource, Azure client, and authentication properties, plus the canonical Azure prefix. It cannot enable bootstrap, schedulers, migration, or alternate Spring configuration sources. Authenticated admin access is still required. Use this Pod solely for the reviewed maintenance operations; it contains the full application's admin API.

Render locally with all required operator values. This command deliberately leaves Java and promotion disabled:

```sh
python3 dev-tools/storage-retention/maintenance/render.py \
  --namespace "$REVIEWED_NAMESPACE" \
  --name "$REVIEWED_MAINTENANCE_POD_NAME" \
  --context "$REVIEWED_CONTEXT" \
  --image "$REVIEWED_MAINTENANCE_IMAGE_DIGEST" \
  --run-id "$REVIEWED_SNAPSHOT_RUN_ID" \
  --fence-id "$REVIEWED_FENCE_ID" \
  --destination-root "$REVIEWED_DESTINATION_ROOT" \
  --approval-reference "$REVIEWED_APPROVAL_REFERENCE" \
  --application-config-map "$REVIEWED_APPLICATION_CONFIG_MAP" \
  --fence-config-map "$REVIEWED_FENCE_CONFIG_MAP" \
  --credentials-secret "$REVIEWED_CREDENTIALS_SECRET" \
  --output /tmp/mojito-maintenance-review.json
```

The output includes one namespace-scoped ServiceAccount, Role, RoleBinding, and standalone single-container Pod. The Role permits only `get/list` of deployments, pods, and HPAs in that namespace; it cannot read Secrets or mutate workloads. A projected short-lived service-account token supplies the verifier's in-cluster kubeconfig. The context name is an operator-selected alias for that in-cluster connection; namespace and observed UID checks bind it to the reviewed resources. See [Kubernetes RBAC](https://kubernetes.io/docs/reference/access-authn-authz/rbac/) and [service-account token projection](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/).

Before approved application, review any namespace-specific image-pull configuration, admission policies, network policies, and identity requirements. Sidecar injection must remain disabled: the verifier rejects multiple containers, init containers, ephemeral containers, and owner-controlled maintenance Pods. No Service or Ingress is rendered, and Java binds only to `127.0.0.1:8080`. Use an approved localhost connection into the Pod for admin calls. The readiness check runs inside the same container.

## Approved execution boundary

1. Finish the ordinary application/schema rollout and rollback-read compatibility checks. Before launch, review the target database's successful Flyway history and checksums against the exact application artifact, including `V121__MBlob_Migration.sql` and `V122__Pollable_Task_Archive_Checkpoint.sql`. Verify the actual columns, types, keys, indexes and constraints for `mblob_migration_run`, `mblob_migration_item`, `mblob_migration_promotion`, `mblob_migration_promotion_item`, `pollable_task_archive_checkpoint`, and `pollable_task_archive_retry`, plus checkpoint row `id=1`. Maintenance startup disables Flyway, SQL initialization and Hibernate schema handling; it neither applies nor validates those migrations for you. Missing or mismatched preflight evidence blocks launch.
2. Review the complete writer inventory and the parent [maintenance fence protocol](../README.md). The operator must stop reconcilers, API/worker writers, external Azure writers, cleanup/backfill writers, and interfering Azure lifecycle rules for the entire maintenance and rollback window. Read-only Kubernetes observations cannot establish those external conditions themselves.
3. After approval, render a **new** file adding `--enable-manual-start --enable-promotion`. Promotion is enabled only for explicit admin requests; it has no scheduler. Applying this artifact is a separate operator/deployment action.
4. Once that exact Pod is ready, read its UID, named container's actual imageID, and the full protected deployment inventory/UIDs. Populate the reviewed fence ConfigMap with `manifest.json` using the parent verifier's protocol. The approval mount is intentionally optional so the Pod can become ready before its UID is known. Missing or mismatched approval always refuses promotion.
5. The manifest must match the Pod's pinned context, namespace, snapshot run, fence ID, destination, and approval reference. The wrapper copies the projected ConfigMap symlink into a private regular file without changing the approval bytes, then invokes the verifier. Promotion pins its SHA-256; replacing or extending the approval does not silently refresh an existing run.
6. Run the bounded admin promotion/reconciliation operations and inspect durable evidence under the continuously enforced maintenance window. Keep writers stopped until all in-flight operations have ended and cutover/rollback decisions are complete. Table replacement and any writer restart are separate approved actions.

The fixed launcher uses an explicit `maintenance` profile and keeps ordinary controller-required scheduler services wired. The profile name alone enforces nothing: fixed command-line settings disable Quartz startup, all shared JDBC Quartz storage (uses an isolated `RAMJobStore`), async queue consumers/retention, SQL queue monitoring, AI Review cleanup, generic and policy blob cleanup, image migration, task archival/scheduling/deletion, migration scheduling, bootstrap, Flyway, and SQL initialization. It removes inherited Spring/Mojito/JVM override environment variables, uses explicit connection configuration, and forces direct Azure routing plus direct blob-backed image storage to avoid both blob and image database fallback/backfill. A started Pod lasts at most 24 hours, runs as a non-root user with read-only root filesystem, and has only private temporary writable storage. Source blob deletion remains outside this runtime.


## Startup-hook audit

Do not restore `disablescheduling` to this launcher. A native full-application startup showed that it excludes `AiTranslateAutomationCronSchedulerService`, which a controller requires, so the application cannot boot. The maintenance profile preserves that dependency and relies on the fixed execution controls below.

Hibernate `ddl-auto=validate` also rejected the existing `application_cache.key_md5` mapping (`CHAR` in the migrated schema versus Hibernate's expected `VARCHAR(32)`) during the 2026-09-14 rehearsal after Flyway completed through the then-current V116. The maintenance launcher therefore fixes `spring.jpa.hibernate.ddl-auto=none`; this compatibility setting does not change the legacy column or any schema. The exact Flyway/schema preflight above replaces automatic Hibernate validation for this runtime. Flyway and SQL initialization remain disabled.

| Startup or periodic path | Maintenance behavior |
| --- | --- |
| `QuartzConfig.startSchedulers()` and `QuartzSchedulerConfig.scheduler()` | Startup registration/removal touches the isolated RAM store only; scheduler auto-start and the explicit `startDelayed` path are both disabled. Ordinary one-shot and recurring Quartz triggers may be registered but cannot execute. |
| JSON-config localization `ApplicationReadyEvent` | Reads existing configuration and synchronizes job definitions into the stopped RAM scheduler; does not change application rows. |
| `Bootstrap` context listener | `l10n.bootstrap.enabled=false` prevents user/data creation. Flyway and Spring SQL initialization also remain disabled. |
| Async queue lifecycle, PostgreSQL wakeup listener, retention and status tasks | Their enabling property is forced false; no consumers/listener/retention worker starts. |
| AI Review cleanup and constructor cancellation timer | Cleanup returns before querying the store. The cancellation timer only visits this process's initially empty `inFlight` map; no normal AI Review requests belong on the maintenance Pod. |
| Repository statistics reactor and Hibernate listener registration | Initialize in-memory listeners only. The reactor has no repository events until normal application writes occur; migration evidence uses JDBC and does not create those events. |
| Integrity-checker discovery, pollable aspect parameters, health host lookup and JWT configuration validation | Initialize local metadata or perform read-only validation; no startup application-data mutation found. |

This source audit does not replace a successful native/application and image boot check. Keep normal traffic off the Pod; its full admin API can still perform explicit writes if called outside the reviewed maintenance operations.

## Offline checks

```sh
python3 -m unittest discover -s dev-tools/storage-retention/maintenance -p test_runtime.py
python3 -m unittest discover -s dev-tools/storage-retention -p test_maintenance_fence.py
```

These tests mock process replacement and downloads. They exercise disabled startup, fixed writer controls, environment isolation, config escaping, old-image rejection, exact request identity binding, projected-manifest copying, least-privilege rendering, digest/checksum failures, and protection against overwriting reviewed output. They do not prove a container build, application startup, or live maintenance fence.
