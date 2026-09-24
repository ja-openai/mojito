# Blob migration and task retention

Status: implemented; initial local validation was completed on 2026-09-14. The integrated
V121/V122 release still requires CI and runtime validation. Migration/archive workers,
recurring scheduling, canonical promotion and source deletion default off. See the
[operations runbook](../../dev-tools/storage-retention/README.md).

## Outcomes

1. Move required historical payloads out of `mblob`, preserve SQL coordination and
   unresolved data in a populated replacement, and verify physical storage reclamation.
2. Archive eligible historical `pollable_task` metadata while preserving reads and
   ownership checks. Measure eligible arrivals and archive capacity before claiming
   control of overall table growth.

Blob reclamation need not wait for every task-family policy. Payloads and relational
metadata have different retention contracts. Allocation, payload bytes, estimated row
counts and completion throughput are distinct measurements. Environment identities,
private observations and approval records belong in private operational evidence.

## Why separate mechanisms are needed

`AzureDatabaseFallbackBlobStorage` copies legacy data only when read. Cold permanent
objects can remain solely in MySQL. Lazy backfill and ordinary Azure writes/deletes can
race bulk migration, so a completed source-ID scan alone cannot establish cutover safety.
`PollableTaskCleanupService` finishes timed-out tasks; it does not remove old history.

Direct SQL coordination uses `ai_review_execution/v1/capacity` and
`ai_review_submission_rate/v1/user/*`. Preserve these in the replacement. An empty
replacement breaks coordination even after all ordinary payloads move.

## Blob protocol

### Online snapshots

V121 adds run/item evidence. The disabled worker pins a source high-water ID, explicit
semantic prefixes and Azure destination. It uses keyset pagination, token leases,
finite retries and row/byte/time budgets. Remote I/O occurs outside DB transactions.
Pause revokes publication permission; an in-flight immutable upload may still finish.

Candidate pages select only the run's literal semantic prefixes. On MySQL, each prefix range reads
the covering name index, takes its lowest IDs after the cursor through the pinned high-water ID,
and merges those IDs into a globally ordered, bounded page. A single statement joins that page
to name and expiry metadata, so concurrent deletion between separate reads cannot create a falsely
short page. Prefix comparison is case-sensitive and requires the slash boundary; expiry does not
filter candidates. Only selected rows have their length measured, one row per attempt, before
checking the remaining byte budget.
Scan completion uses that same page: an empty page or a short page whose last candidate was
processed establishes exhaustion. A full page stays ready for a later scan, and a byte/time stop
before the last candidate cannot claim completion. There is no separate end-of-batch source
existence query. A length-query failure records a visible failed attempt and lets later IDs
progress; finite retries remeasure the row. An unavailable evidence length does not mean
NULL content: only `PRESERVED_NULL` confirms that. Unselected/control/unknown rows outside the
run's prefixes are omitted from new snapshot pages and evidence. Existing evidence and cursors
remain valid when resuming an older run; counters include its historical global-scan work plus
new selected-row work. Promotion still measures current sources and reconciles every source row;
rows with no snapshot evidence are explicitly retained as `RETAIN_DELTA`.

Exact bytes are conditionally copied to
`mblob_migration/v1/<run>/<source-id>/<sha256>`, read back and compared with current
source metadata/content. Control, unknown, unselected, oversized, null and changed
rows remain. Failures stay visible. No source payload is deleted by this worker.
`SNAPSHOT_COMPLETE` includes preserved rows and is not canonical readiness.

Preserve branch state, CLOB uploads, both historical/corrected report prefixes, import
and translation lineage, agent review, permanent translation repair snapshots, and
needed task payloads. Preserve DTO cache by default: some callers use `UpdateType.NEVER`,
so rebuildability alone does not establish safe deletion.

### Canonical promotion

A separately disabled admin API requires an independently enforced writer fence.
A reviewed manifest pins run/destination, writer deployments and UIDs, maintenance
pod/container/image, approval window and external-writer attestations. Kubernetes
checks verify current drainage; Java validates identity/hash and the short lifetime
using wall and monotonic clocks. External reconcilers/Azure writers/lifecycle still
require actual operator controls. A DB lease cannot stop those writers.

The dedicated maintenance image uses RAM Quartz, disabled consumers/cleanup/bootstrap,
loopback HTTP and the packaged Python/kubectl verifier. Normal `disablescheduling`
does not suppress AI Review cleanup, so maintenance also forces
`l10n.ai-review.cleanup-enabled=false`. The dated local image rehearsal below passed;
the final production-derived image still needs release validation.

Promotion checks current source and snapshot bytes, conditionally creates canonical
objects and verifies bytes, tags and ETag. Non-null-TTL rows remain in MySQL with
original expiry metadata: upload-time lifecycle age cannot prove the old expiry.
Canonical byte/tag conflicts block cutover until explicitly resolved. Database fallback
only helps when Azure is absent; it cannot override an existing wrong Azure object.

A separate full source reconciliation accounts for every fence-boundary row, including
pre-fence deltas. `RECONCILED` means canonically verified or explicitly retained, not
that every payload moved. Fresh readiness and verified per-prefix application routes
are required before replacement. Keep fallback for retained rows with absent Azure
objects. Never change a DATABASE/S3 prefix merely because a copy exists in Azure.

The fence must stay held after permission expires and until in-flight work stops.
Promotion and reconciliation reread data; a large dataset may require a long maintenance
window. Measure throughput and retained bytes before promising a short window.

## Standalone task archive

V122 adds a persistent high-water/keyset checkpoint and retry table. Enabling the worker
does not enable recurring scheduling. A manual admin request runs one bounded batch;
retries/new candidates share its limit. Retention/deletion-mode changes reset the scan;
stop old workers before changing policy so mixed configurations cannot alternate.

The default proposal retains 90 days in MySQL and permanently archives older eligible
standalone metadata as `pollable_task_archive/v1/<id>.json`. Active tasks, families,
external references and task types with unfenced usage/queue references remain. Current
FK checks cover extraction, drop import/export, TMXLIFF, AI translation run/attempt,
term-index run and bulk-import lineage. Row locks do not protect soft references.

Each archive is conditionally created and read back, then a short final transaction
locks/rechecks task state and references before optional deletion. Source deletion is
disabled. Immutable conflicts retain source. State comparison uses stable creator ID;
mutable profile text remains historical presentation. Native MySQL probes exercise all
ten incoming FKs in both insertion/deletion orders.

Normal reads use MySQL first and Azure on absence; provider failures remain visible.
Fresh/lifecycle mutation paths remain DB-only. Archive readers are independent of the
worker flag. All deployed and rollback builds must support archive reads/authorization
before deleting source metadata. Archives do not recreate expired input/output blobs.

[Task-family inventory](../../dev-tools/storage-retention/pollable-family-inventory.md)
measures the excluded population. Family archival is a separate unimplemented increment:
bounded whole-family snapshots, graph-aware reads/ownership and atomic child-first
deletion. Retained run-history rows can pin families even after that extension.

## Cleanup, replacement and closure

Prefix cleanup requires policy age and actual row expiry. It preserves active task
payloads, graphs, malformed names and missing metadata, and rechecks at deletion.
These fixes do not enable cleanup or provider lifecycle rules.

Quartz recovery of an interrupted policy cleanup disables enabled policies and marks them
`FAILED` without starting another batch. It preserves the saved deletion counters and start
time and records that the previous execution's batch count and commit outcome are uncertain.
Operators must reconcile affected rows before manually restarting; saved counters alone do
not prove whether the last deletion committed. Already disabled policies remain unchanged.

The policy selector discovers at most one batch of IDs with a nonlocking name-index query,
then locks that bounded ID set through the primary index with all eligibility predicates
repeated. It never treats an empty or partial locking result as proof that the prefix is drained;
zero deletions require the independent global eligibility probe. A partial successful batch
uses normal progress and batch limits. Separate candidate and locking-recheck timings expose
which phase costs time. The proposed access shape needs native MySQL race/cancellation tests
and a measured canary before making any performance or sustained-throughput claim.

The policy worker has a 10-second SQL/transaction budget and phase timings. Cancellation,
rollback and transaction finalization can exceed that budget. Stop requests take effect between
batches; timeout or uncertain completion disables further attempts when the policy write succeeds
and requires row reconciliation before manual restart. Deletion and progress persistence remain
separate transactions, so counters alone cannot establish crash-safe deletion accounting.

Before replacement verify complete source coverage, retained bytes, schema/dependencies,
per-prefix routes, server/binlog headroom and restore capability. Retain unknown/control/
temporary rows and the original AUTO_INCREMENT floor. Bounded tooling verifies exact
IDs, names, bytes and expiry metadata under the continuous fence before atomic rename.

Retain the old table. Once ordinary writes/deletes resume, blind reverse rename can lose
new state or resurrect deleted blobs. Roll forward or use a new fence and verified delta/
restore procedure. Old-table retirement is a later explicit operation. Measure actual
allocation afterward. `pollable_task` has FKs and cannot use the simple blob swap.

Deploy disabled readers first, then bounded upload-only canaries and a separately enabled
small deletion canary with archive-compatible rollback. Schedule recurring retention only
after measuring eligible arrivals, capacity and workload impact. The default daily 100
is not a capacity plan. Stop on mismatches, authorization regressions, lost leases/fences
or the operational thresholds in the runbook.

Snapshot completion, promotion, swap and reclamation are separate gates. Closure requires
exact deployed revision, verified/retained/failed objects and bytes, measured task backlog
and rate, accepted retained-history policy, observed cutover and actual reclaimed storage.

## Historical validation and current release gates

On 2026-09-14, the isolated combined package included V114 and reported 298 tests with zero
failures/errors and two existing Quartz skips. Seven archive repository cases passed on native
MySQL. Those checks used the then-current storage versions V115/V116, before the current
V121/V122 integration; they do not validate the integrated release artifact.

A local ARM64 maintenance image was built and booted with the exact combined executable JAR.
The default-disabled image and both manual maintenance modes passed. Real Flyway applied 115
migrations through the then-current V116; all 146 table fingerprints stayed unchanged through
startup, public health requests, idle observation and shutdown. The loopback Azure sentinel
received no requests, missing approval was refused, and disposable databases/accounts/containers
were removed. The aggregate health endpoint remained `503 DOWN` because the fixture had no local
SMTP server; this was startup evidence, not a healthy aggregate endpoint.

The verifier/runtime/renderer had 35/18/10 passing offline Python tests. Separate native MySQL
checks covered 20 FK lock races, 8,003-row pagination, lease/transaction boundaries, 12 family
inventory cases and 17 replacement scenarios. The local image used the production runtime base
digest plus the tested JAR; it did not include the final production application image layers.

Routine commit checks use mocks or in-memory databases, with `mojito.asyncJobQueue.testcontainers`
unset. Real-database checks belong in CI or an explicitly requested local validation run; do not
start Docker or database services for routine commit preparation. The integrated release still
requires the complete Flyway chain through V122, populated-upgrade checks, and a build/startup
check of the exact production-derived image. Live routing, permissions, canaries, Kubernetes
admission and maintenance timing remain separate rollout gates.
