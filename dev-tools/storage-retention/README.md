# Blob migration and task retention operations

These tools prepare a staged rollout. Installing the code does not migrate or delete existing data.
Both workers, recurring scheduling, canonical promotion, and task source deletion default off.
Use the [design](../../dev-docs/design/blob-storage-migration-and-task-retention.md) for scope and
the [MySQL probes](mysql-validation.md) for CI or explicitly requested local database validation.
Routine commit checks use mocks and in-memory databases; do not start Docker or database services.
Leave `mojito.asyncJobQueue.testcontainers` unset for routine checks.

## Separate completion gates

| Gate | Evidence required |
| --- | --- |
| Disabled deployment | Exact artifact and schema on every API/worker; normal reads and jobs healthy |
| Snapshot copy | Finite source scan, verified immutable copies, explicit retained/failed inventory |
| Task retention canary | At most 100 candidates, upload verification, reference recheck, then separately enabled deletion and historical reads |
| Canonical promotion | External writer fence, conditional canonical creation, byte/tag verification, complete source reconciliation |
| Table replacement | Preserved source rows verified in replacement, maintained fence, dependency and AUTO_INCREMENT checks, atomic rename |
| Storage reclaimed | Old table retirement explicitly approved and actual filesystem/server allocation measured |

A snapshot is not a serving copy. `SNAPSHOT_COMPLETE` means the selected scan finished, including
preserved rows. `RECONCILED` means every final source row is either canonically verified or explicitly
retained; it does not mean every payload moved, the table was replaced, or disk was reclaimed.

## First deployment and upload-only canaries

Apply the new V121/V122 migrations through the normal reviewed deployment. They add evidence and
checkpoint tables without changing source rows. Check the target's Flyway history before deployment;
do not renumber or repair already applied migrations.

The integrated release preserves the existing V114–V120 migration history. Storage evidence and
archive checkpoints now use V121 and V122, respectively; the isolated September 14 validation used
earlier storage version numbers. Validate the complete Flyway chain and populated upgrade in CI
against the final release artifact. Do not deploy a higher version and later insert a lower migration
or repair an applied checksum to match a renamed file.

Keep these effective settings on every replica:

```properties
l10n.blob-storage.migration.enabled=false
l10n.blob-storage.migration.scheduling-enabled=false
l10n.blob-storage.migration.promotion-enabled=false
l10n.pollable-task.archive.enabled=false
l10n.pollable-task.archive.scheduling-enabled=false
l10n.pollable-task.archive.delete-source=false
l10n.blob-storage.routing.prefixes.pollable-task-archive=azure
```

The archive prefix must route directly to the reviewed Azure account/container. Keep permanent
archives and the `mblob_migration/v1/` namespace outside temporary lifecycle deletion. Archive readers
remain available when the worker is disabled. Every rollback artifact used after task deletion must
retain these readers, the same archive route, and their ownership checks.

After approval, enable snapshot copying on one controlled instance, leaving recurring scheduling off.
All endpoints below require an existing administrator session; use the usual authenticated client
and CSRF handling. Do not put credentials in command histories or evidence artifacts.

| Method and path | Body or effect |
| --- | --- |
| `POST /api/monitoring/blob-migrations` | Explicit `copyPrefixes`, `maxRows`, `maxBytes`, `maxSeconds`, `maxRetries`; creates paused run |
| `POST /api/monitoring/blob-migrations/{id}/resume` | Requests one bounded Quartz batch for that run ID; ordinary snapshot scheduler must be running |
| `POST /api/monitoring/blob-migrations/{id}/pause` | Revokes the worker token; an in-flight immutable upload may finish |
| `GET /api/monitoring/blob-migrations/{id}` | Durable progress and counts |
| `GET /api/monitoring/blob-migrations/{id}/evidence?afterId=0&limit=100` | Keyset evidence, including preserved/failing rows |

Manual resume puts the selected run ID on its one-shot trigger; it cannot choose another queued run.
Untagged recurring triggers require both `migration.enabled=true` and
`migration.scheduling-enabled=true` at execution, including triggers left in a persistent Quartz
store by an earlier deployment. Disabling the worker also blocks queued manual triggers. Run leases
and pause state are rechecked when the selected batch claims its work.

Example initial request, only after prefix inventory confirms the selected class:

```json
{"copyPrefixes":["multi_branch_state"],"maxRows":100,"maxBytes":16777216,"maxSeconds":30,"maxRetries":3}
```

These limits apply per batch, not to the total run. A batch scans at most 100 source rows and attempts
at most 16 MiB of eligible source bytes; verification and rereads create additional I/O. Its wall-time
budget is checked between items, so a bounded in-flight operation can outlast 30 seconds. Pausing
stops subsequent publication and work; wait for in-flight calls to settle before changing destinations.
Do not loop automatically until the first result and workload impact are reviewed.

For task metadata, enable `l10n.pollable-task.archive.enabled=true` with retention 90 days,
batch size 100, lease 300 seconds, `scheduling-enabled=false`, and `delete-source=false`.
`POST /api/monitoring/task-archive/batch` executes exactly one bounded batch and returns its counters.
Retries and new candidates share the same limit. No repeated trigger is needed for the canary.

The worker excludes active tasks, parents/children, durable references, AI Review chat task types and
localized-asset task types whose soft references cannot be fenced by an InnoDB row lock. Archived
metadata does not recreate expired input/output blobs. Changing retention or deletion mode resets
the persisted scan; stop old workers before changing policy, so mixed configurations cannot alternate.

Before enabling deletion, verify the same archived task through normal lookup/inspection and owner
access tests on every deployed reader and the rollback build. Then approve and run one deletion batch.
Foreign-key/reference changes or task mutations during remote I/O preserve the source. Immutable
archive conflicts remain visible for investigation; never overwrite them just to advance a cursor.

## Monitoring and stop criteria

Before a canary, record a 15-minute quiet baseline and the source query plans. Proposed initial
envelope: one worker, one batch at a time, at least 60 seconds between batches. Stop immediately on
any byte/tag mismatch, archive read/auth regression, unexpected source deletion or writer-fence
failure. Stop further batches if DB CPU exceeds 70%, replica lag exceeds 5 seconds where applicable,
or API p95 / queue age exceeds twice its baseline for two consecutive one-minute observations.
Stop on any new archive/provider authorization error or database lock timeout. Agree any environment
specific replacement thresholds before execution; absent telemetry is not a passing observation.

These are operator/controller checks, not a built-in load-sensitive throttle. Service bounds and lease
checks are implemented, but there is no automatic CPU/latency feedback controller. Record each batch's
duration, candidates, copied/verified/retained bytes, failures, archive deletions and oldest eligible age.
Never infer a drain ETA from allocated tablespace size or optimizer row estimates.

### Prefix cleanup execution limits

The prefix-policy worker uses a 10-second SQL/transaction budget and checks elapsed time before
deletion and before returning to commit. Its eligibility probe has the same SQL timeout. This does
not impose a hard wall-clock deadline: acquiring a connection, JDBC cancellation, rollback, commit
and connection release can take longer. A stop request prevents subsequent batches; it does not
interrupt an executing statement. Repeated stops preserve `STOP_REQUESTED` until the worker finishes.

Timeouts, uncertain transaction finalization and failures to persist progress after a committed
deletion require reconciliation before manual restart. The worker attempts to disable the policy;
if that write fails, successful disablement has not been established. Do not retry an ambiguous
start or infer affected rows from saved counters. Check policy state, executing/persisted Quartz
triggers and the original candidate IDs before continuing.

The content-free phase log records selection, deletion, complete transaction, transaction
finalization and progress-write durations, plus failure phase and selected/deleted counts.
Finalization includes commit or rollback and connection release. A reported deletion count can
precede rollback or an uncertain commit; use the commit flag and independent row reconciliation.
Validate actual JDBC cancellation and rollback with MySQL before relying on the budget in a rollout;
mock and in-memory tests alone do not establish those properties.

For steady retention, separately enable `l10n.pollable-task.archive.scheduling-enabled=true` and set
`cron` after measuring the eligible arrival rate. The daily default of 100 tasks is not a capacity plan.
The initial standalone scope cannot establish that overall task-table growth is controlled; inventory
families and their retained history references before extending deletion.

## Canonical promotion and the writer fence

Snapshot copying can run while ordinary work continues because it writes a separate immutable
namespace. Canonical promotion requires ordinary writers to remain stopped throughout promotion,
reconciliation and the swap. It reads source and Azure bytes again; a large dataset can require a long
maintenance window. Measure canary throughput and the retained byte count before booking a window.
Do not describe this as a short zero-downtime migration without that evidence.

Use the dedicated [maintenance image and renderer](maintenance/) rather than an ordinary API/worker
pod. Its startup forces isolated RAM Quartz, disabled job consumers/cleanup/bootstrap, loopback HTTP,
and explicit promotion flags. The normal application image lacks the Python/kubectl verifier runtime.
The maintenance image must be built and smoke-tested from the exact reviewed application artifact
before launch; a render-only test does not validate image startup.

The operator-owned manifest passed through `MOJITO_STORAGE_FENCE_MANIFEST` pins the snapshot run,
destination, deployment names/UIDs, exact maintenance pod/container/image ID, fence identity and
approval reference. `verify-maintenance-fence.py` verifies scaled-to-zero deployments, observed
generation, absence of their pods, no HPA, and the isolated maintenance pod. It also requires explicit
attestations that external reconcilers, Azure writers, cleanup and lifecycle deletion remain stopped
or preserve the full maintenance and rollback interval. Kubernetes read checks alone cannot prove
those external controls. The tool never stops writers or invents an approval.

The manifest is time-limited and hash-pinned. A renewed short verifier lease allows bounded calls;
expiry revokes work permission but must never automatically restart ordinary writers. Stop/drain the
maintenance process before lifting the fence. If the manifest itself expires, a changed manifest
requires a fresh promotion run and complete reconciliation; do not edit historical evidence.

| Method and path | Effect |
| --- | --- |
| `POST /api/monitoring/blob-promotions` | `{"snapshotRunId":"...","fenceId":"..."}` pins fence and final source boundary |
| `POST /api/monitoring/blob-promotions/{id}/batch` | One bounded promotion or reconciliation batch; never a recurring job |
| `POST /api/monitoring/blob-promotions/{id}/pause` | Revokes database work token; retain the external fence |
| `GET /api/monitoring/blob-promotions/{id}/evidence?afterId=0&limit=100` | Canonical/retained evidence keyed by source ID |
| `GET /api/monitoring/blob-promotions/{id}/readiness` | Fresh fence and source-boundary verification; valid only before cutover |

Canonical creation is conditional: existing different bytes or retention tags retain the source
and block cutover until explicitly resolved. Azure-with-database-fallback reads Azure first; it
cannot protect a retained MySQL row when a conflicting Azure object already exists. Verify every
API/worker and rollback build's effective default/legacy type and normalized per-prefix overrides,
Azure account/container/path, and image-service type/path separately. A DATABASE/S3 prefix cannot
silently change to Azure simply because promotion produced a copy.
Every non-null source TTL is retained as `RETAIN_TEMPORARY_LIFETIME`; upload-time Azure lifecycle age
does not preserve an older row's absolute expiry. Keep its original `created_date` and
`expire_after_seconds` in the replacement. Unknown/control/unselected/changed/oversized rows and
pre-fence deltas also remain. Keep database fallback for every prefix containing any retained row.

## Replacement and rollback

Use the render-only replacement tooling only after complete reconciliation and a fresh readiness
proof. Recheck actual schema, no incoming foreign keys/triggers/views, grants, metadata-lock blockers,
PITR/restore readiness and storage/binlog headroom. Preserve every retained row, including SQL
coordination records; verify exact IDs, names, bytes and expiry metadata. Preserve the original
AUTO_INCREMENT floor so a high ID moved to Azure cannot be reused by a new MySQL write.

An atomic rename keeps the original table under a rollback name. Readiness is intentionally invalid
after the swap. Record the immutable pre-swap evidence separately, verify application reads with the
fence still held, then stop the maintenance instance before resuming writers.

A reverse rename is safe only before any post-swap writes/deletes. Once writers resume, rollback
requires another fence and reconciliation of inserts, updates and deletions against the current
replacement. Blindly merging or renaming the old table can lose new state or resurrect deleted blobs.
Retain the new archive-aware application and roll forward unless a verified restore/delta procedure
has been approved. Dropping the original table is a later irreversible gate, never part of these tools.

Expired-row cleanup honors both policy age and the row's actual expiry, and rechecks task eligibility
at deletion. Enabling it, Azure lifecycle changes, table replacement and old-table retirement remain
separate live operations. `pollable_task` has incoming FKs and must not use the blob table swap tool.
