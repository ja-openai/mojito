# Async Queue Deployment And Canary Runbook

Status: proposed execution plan, 2026-09-13. No merge, deployment, enablement or
pipeline trigger is authorized by this document alone. The release owner approves
each promotion after reviewing its evidence. Keep the
[readiness gates](async-job-queue-review.md#current-readiness) authoritative.

## Objective And Boundaries

Land a reviewed release, deploy it with queue routing disabled, establish a Quartz
baseline, then canary asset localization and measure correctness, performance and
stability. Roll back new admissions through configuration, without building another
image, while retaining workers and repair capability for accepted queue jobs.

The adapter generates localized asset output. It is not a replacement for all asset
ingestion, translation, AI review, statistics, cron or Quartz parent orchestration.
The branch is not currently ready for a blind merge or unrestricted canary.

- Local master owns V109 through V112; the queue still uses V109 on an older base.
  V113 is available at this snapshot, not a permanent allocation. Confirm actual
  applied queue history before choosing an unapplied rename or forward migration.
- At the preflight snapshot (`f53ae8c0a5` queue, `ed4bc31eda` master), both worktrees
  were clean, with 46 queue commits absent from master and seven master commits
  beyond its cached origin ref. Fetch and explicitly
  agree the release contents; do not incidentally publish unrelated local commits.
- Queue settings use startup-bound `@Value`, `@ConfigurationProperties` and
  conditional bean registration. Config rollback needs a rolling pod restart,
  not a new application build. Editing a ConfigMap alone is not live reload.
- The deployment build pins a source revision separately. Pushing Mojito master
  does not itself deploy that revision. Record the pin and resulting image digest.

## Phase A: Integrate And Deploy Disabled

1. Record fresh origin/master, local master and queue revisions, then integrate in
   the existing isolated worktree. Preserve a recoverable branch tip, consolidate
   reviewed chunks and fix the admin/adapter fixture dependency. No force-push of
   master. Preserve unrelated primary-checkout and deployment-repository work.
2. Resolve the Flyway collision from actual applied history. Preserve all applied
   checksums. Test fresh installation and historical upgrade on the selected base,
   plus the application startup migration strategy and actual DB/Flyway versions.
   The native 108-migration old-base pass does not satisfy this gate.
3. Review and test shared Quartz, task, blob and generation changes with queue
   flags off. Run formatting and focused queue/admin/adapter/legacy tests, required
   real-database CI without required skips or reruns, and the normal release checks.
   Disabled routing does not hide migrations or shared-code regressions.
4. Approve the exact commit range, merge/push through the usual release path,
   update the deployment source pin and deploy the new image with queue routing off.
   Keep source merge, image build, deployment submission and healthy pods distinct.
5. Verify every API and worker image digest, effective flags, Flyway history,
   readiness and error/DB metrics. Run a small normal localization workflow.
   Observe at least 60 minutes and one completed representative baseline workflow;
   insufficient traffic is inconclusive. Do not enable during a partial rollout.

## Phase B: Gate The First Canary

These are activation prerequisites, not reasons to delay all disabled integration:

- Agree the environment, explicit repository/asset/locale cohort, permitted file
  formats, concurrency, run window, release owner and available incident responder.
  A staging name is not isolation if real clients use the service or database.
- Implement and verify stable cohort routing, or an explicitly isolated test
  endpoint/producer with equivalent containment. Current flags are not a repository
  allowlist. Do not enable every eligible request to obtain a small sample.
- Close durable admission/unknown-commit recovery, publication/task consistency
  and referenced-blob lifetime gates for that cohort. Do not accept known loss or
  duplicate-publication windows on the assumption configuration can undo them.
- Keep tracked pull runs excluded until business-lineage fencing is implemented.
  Keep parent fan-out disabled until durable child acceptance/reconciliation is
  proved. Audit remaining shared cache/generation effects for the chosen inputs.
- Confirm queue-owned inputs/results survive the observation, retry and repair
  windows on every selected storage backend. Queue retention stays disabled;
  that alone does not disable existing blob cleanup or cloud lifecycle rules.
- Rehearse producer rollback and complete drain on disposable workloads. Retain
  sufficient healthy queue consumers, Quartz capacity and diagnostic access.

## Configuration States

All property names below are relative to `l10n.org.async-job-queue.`. These are
target states, not a complete deployment configuration. Validate JDBC dialect,
pool budgets, queue sizing and lease settings separately before activation.

| Property | Disabled release | Direct canary | Producer rollback/drain |
| --- | --- | --- | --- |
| `enabled` | `false` | `true` | `true` |
| `store` | `jdbc` | `jdbc` | `jdbc` |
| `asset-localize.enabled` | `false` | `true` | `true` |
| `asset-localize.producer-enabled` | `false` | `true` on approved producers only | `false` on all producers |
| `asset-localize.fanout-enabled` | `false` | `false` | `false` |
| `queues.assetlocalize.consumer-enabled` | `false` | `true` on designated workers | `true` on drain workers |
| `retention.enabled` | `false` | `false` | `false` |

API-only nodes keep consumers off. Keep non-canary producers on Quartz. Apply
overrides through the approved Kubernetes configuration mechanism, preserving
existing JVM options/secrets, and roll the affected API/parent/worker pods with
the same image. Verify effective values and observed routing on every pod; old
producer pods can continue admitting work until they have stopped. Persist the
approved settings in the deployment source of truth to avoid later drift.

## Phase C: Exercise And Measure

Start with one asset and one locale, then a small bounded batch. Run platform
workflows sequentially: monorepo, Android, then iOS. Inspect their exact pipeline,
branch/revision, endpoint, input parameters and publication behavior before each
trigger. Use test branches/output destinations; do not approve/merge generated
translation changes or trigger downstream release jobs as part of this exercise.

For each platform record a matched Quartz baseline and eligible queue cohort at
comparable load/input sizes. Verify actual task/job IDs and route counters:

- The CLI defaults to synchronous pulls, which bypass this queue adapter. A
  direct-only CLI test needs `--async-ws` without `--parallel`; inspect the actual
  installed CLI and request path rather than assuming a platform job uses async.
- For async requests, `--record-pull-run` supplies a non-null tracking name and
  keeps them on Quartz. Do not strip tracking from ordinary production workflows
  merely to force a test.
- Parallel pull parents remain Quartz, and their children also remain Quartz while
  fan-out is disabled. A passing ordinary pipeline is then compatibility evidence,
  not queue throughput evidence. Use an approved direct-only test variant, or defer
  that queue lane until the fan-out prerequisites are closed.
- Count only observed queue executions toward canary coverage. Compare generated
  files, placeholders, locales and task/error semantics against controlled inputs
  and translation state; jobs may contain unrelated extraction/upload work.

Before starting, approve these proposed thresholds from the existing migration
plan: at least 1,000 successful eligible jobs and 24 hours of observation, capped at
72 hours; admission and end-to-end p95 no more than 20% above matched Quartz and
within client deadlines; unexpected terminal failures no more than baseline plus
0.1 percentage point; DB CPU and connection occupancy no more than 20% above
baseline and within an agreed absolute safe budget. Low samples or changed inputs
mean inconclusive, not success. A short weekend smoke is not a soak certification.

Measure throughput, queue wait, ready/delayed depth, oldest ready age, execution
time, retries, lease expiry, DB lock/connection waits, CPU/memory/GC, errors and
publication/repair debt. Keep task/job correlation out of high-cardinality metric
labels and redact localized content and credentials from retained evidence.

## Stop And Roll Back

Stop expansion immediately for lost admissions, incorrect/stale output, duplicate
business effects, missing referenced blobs, unaccounted task/queue mismatches,
unbounded backlog/repair debt or a stability threshold breach. Compare matched
five-minute windows for performance regressions; correctness failures need no
averaging. Stop submitting test workloads while investigating.

**Choose containment before draining.** If continued execution risks corruption,
contain producers and stop affected consumers first, preserving jobs and evidence
for explicit recovery. A consumer configuration change only prevents new claims
after restart; it does not fence business effects from handlers already running.
Do not let shutdown grace periods or a drain target delay emergency containment.
The normal procedure below applies only when completing accepted work is safe.

1. Set `asset-localize.producer-enabled=false` and keep fan-out off. Roll every API
   and Quartz-parent producer using the same image. Verify no fresh queue admissions
   and that new requests route to Quartz. This is a config-only rollout, not an
   instantaneous switch and not compensation for already accepted requests.
2. Keep compatible queue workers, both umbrella flags and repair services enabled.
   Account for queued, delayed, running, FAILED and DONE-but-unpublished jobs. Never
   blindly resubmit an uncertain queue request to Quartz or replay an entire parent.
3. Drain accepted jobs and reconcile output/task obligations. For a bounded canary,
   use a pre-agreed 30-minute drain target where declared runtimes permit it.
   Breach means incident review, not forced deletion or disabling remaining workers.
4. Only after verified drain/reconciliation may consumers and umbrella flags be
   disabled. Leave the schema intact. Config rollback cannot undo a migration,
   shared-code regression or committed business effect; those may require a vetted
   image rollback, forward fix or data repair with separate approval.

## Evidence And Decision

The run record must contain source SHA/image digest, environment and pod revisions,
approved flag states/cohort, DB/Flyway history, test/CI results, platform build IDs,
actual queue counts, baseline comparisons, output parity, observation duration,
errors/repair accounting and rollback/drain results. Report separately: merged,
deployed-disabled, canary-enabled, passed, inconclusive, or rolled back.

The agent prepares the integration, validates gates, follows approved releases and
triggers, measures results and investigates regressions. The release owner settles
applied-history/release-scope questions and approves environment, thresholds and
each expansion. A gate failure holds the next phase; it does not turn recurring
status checks into production approval.

### Local Runbook Verification

On 2026-09-13, 100 existing tests passed across eight suites with zero skips,
failures, errors or reruns: asset service configuration, direct API routing,
multi-locale routing, coordinator configuration/behavior, property validation,
separate producer/consumer drain and multi-queue quiescence. The selection uses
Spring contexts, in-memory stores and mocked business services. It supports the
documented flag behavior; it is not a Kubernetes rollout, live pipeline test,
real-database failure rehearsal or production canary result.

Root `mvn -Pno-local-config spotless:apply` and whitespace checks pass. The Maven
frontend build reports existing large-chunk and ineffective dynamic-import
warnings; queue failure warnings in these suites are deliberate injected faults.
Logs are in `/private/tmp/queue-canary-runbook.KzT6wY/`. No runtime code, SQL,
deployment configuration or test implementation changed for this runbook.
