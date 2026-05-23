Async Job Queue MVP (Backend)
=============================

Context
- Quartz trigger fan-out (one locale => one dynamic trigger) creates lock/contention pressure on MySQL under large Android pulls.
- RAM-backed scheduler improves throughput but is non-durable and pod-local.
- We need a durable queue model with bounded concurrency, retries, and monitoring.

Goals
- Replace high-fan-out locale child execution and async single-locale asset generation with a
  durable MySQL queue.
- Keep existing API/CLI contract and PollableTask UX.
- Keep implementation simple and targeted to one workload first (`assetlocalize` path).
- Design for multi-queue isolation now (without overbuilding).

Non-Goals (MVP)
- Replacing Quartz globally.
- Replacing all pollable workloads.
- Building a generic workflow engine.

Decision Summary
- Keep Quartz for parent orchestration and cron-like jobs.
- Introduce a new JDBC-backed async job queue only for localized-asset async jobs.
- Use plain SQL (`JdbcTemplate`/`NamedParameterJdbcTemplate`) for async job claim/lease/finalize.
- Use strict short transaction boundaries around queue state transitions only.
- Use one logical poller loop per queue type with dedicated executor per queue.

Why Plain SQL (not Hibernate/JPA) for Async Job Hot Path
- Queue claim/lease relies on deterministic lock semantics (`FOR UPDATE SKIP LOCKED`).
- JPA/Hibernate adds hidden flush/session behavior that is risky for queue correctness and latency.
- SQL keeps control explicit and easier to reason about under contention.
- A Hibernate entity can still be useful for read-only/admin inspection later, but the claim,
  heartbeat, requeue, and finalize transitions should stay in a small native-SQL adapter.
- MySQL and PostgreSQL can share the same core queue contract and nearly the same claim SQL. The
  main portability boundary is DDL/migrations and any database-specific timestamp/default syntax,
  not the Java queue runtime.

Async Job Data Model
- Table: `async_job_queue`.
- Columns:
  - `id` bigint PK
  - `queue_name` varchar(64) not null
  - `status` enum/string: `queued`, `running`, `done`, `failed`
  - `available_at` datetime(6) not null (retry/backoff eligibility)
  - `lease_until` datetime(6) null (running lease expiry)
  - `lease_token` varchar(64) null (fencing token for current lease owner)
  - `worker_id` varchar(128) null
  - `job_data` longtext not null (JSON-serialized queue payload)
  - `attempt_count` int not null default 0
  - `last_error` text null
  - `created_date` datetime(6) not null
  - `updated_date` datetime(6) not null
- Indexes:
  - `(queue_name, status, available_at)`
  - `(queue_name, status, lease_until)`
  - optional idempotency/correlation key can be modeled in `job_data`
- Database constraints/types reject unsupported statuses, out-of-range attempt counts,
  invalid queue names, oversized persisted errors, terminal failed rows without a nonblank
  persisted error, blank running lease owners, and inconsistent lease owner fields where a row is
  `running` without `(lease_until, worker_id, lease_token)` or a non-running row still has lease
  ownership attached. MySQL uses a signed `BIGINT AUTO_INCREMENT` id so generated ids stay within
  Java `long`/`AsyncJobId` bounds; MySQL rejects `CHECK` constraints that reference an
  `AUTO_INCREMENT` column, so PostgreSQL/HSQL keep the explicit positive-id check.

Execution Flow (MVP)
1. Parent `GenerateMultiLocalizedAssetJob` still creates child `pollable_task`s for fan-out, while
   the async single-locale `AssetWS` endpoint creates one pollable task directly.
2. Instead of scheduling a Quartz job, enqueue one async job row per localized-asset task.
3. Queue worker claims jobs for `queue_name=assetlocalize`.
4. Worker loads input from existing pollable blob storage.
5. Worker executes localization logic.
6. Worker saves pollable output blob.
7. Runtime marks queue row `done`.
8. Runtime invokes the handler post-terminal callback to call `finishTask(...)` only after the
   queue row is terminal.
9. On failure: retry until the per-queue attempt budget is exhausted; then mark the queue row
   `failed` with `last_error` for operator triage and run the same post-terminal callback to
   finish the pollable task with the captured exception.

Transaction Boundaries (Critical)
- TX A (enqueue): insert queue row.
- TX B (claim): select claimable rows + update to `running` + set lease.
- No DB transaction during heavy work (`generateLocalized`).
- TX C (heartbeat): extend `lease_until` while running.
- TX D (finalize): mark `done` OR `queued` with future `available_at`.
- TX E (pollable finalize): after TX D successfully marks a row terminal, finish the child
  `pollable_task` and emit `asyncJobQueue.handler.completion.failed` if that post-terminal
  callback fails.
- Heartbeat/finalize/requeue updates must match `(id, worker_id, lease_token)` for fencing.

Pollable Finalization Repair
- `AssetLocalizeAsyncJobRepairService.repairTerminalPollableTask(...)` is an idempotent repair path
  for the rare case where TX D succeeds but TX E fails.
- For `done` rows it parses the persisted `job_data`, loads the child `pollable_task`, and finishes
  it only if it is still open.
- For `failed` rows it finishes the child `pollable_task` with an `ExceptionHolder` synthesized from
  the persisted `last_error`, preserving operator-visible task failure without replaying work.
- Repair emits `assetLocalizeAsyncJob.repair{queueName,status,result}` with bounded tags.
- The admin-only endpoint
  `POST /api/admin/async-job-queue/assetlocalize/jobs/{asyncJobId}/pollable-task/repair` exposes
  the same bounded result without returning job payload.

Spring Guardrails Against Transaction Leakage
- Orchestrator method uses `@Transactional(propagation = NOT_SUPPORTED)`.
- Claim/heartbeat/finalize each in separate bean methods with `@Transactional(REQUIRES_NEW)`.
- Avoid self-invocation for transactional methods (must cross Spring proxy boundary).
- Optional safety check before heavy work:
  - assert `!TransactionSynchronizationManager.isActualTransactionActive()`
  - emit metric/log if violated.

Failure + Restart Semantics
- Claim uses lease (`lease_until`), not ownership lock.
- If worker pod crashes, lease expires; job becomes claimable again.
- Claim writes a new random `lease_token`; stale workers that lost lease cannot update queue state.
- JDBC state transitions read `CURRENT_TIMESTAMP` from the database for lease comparisons and
  transition timestamps, avoiding cross-pod JVM clock skew for claim/heartbeat/finalize fencing.
- Runtime-owned delayed requeues (handler exceptions, executor rejection, default handler requeue
  delay) use a store-level relative delay so JDBC derives `available_at` from database time too.
- Retry policy:
  - runner decides retry vs completion outside the store
  - each claim increments `attempt_count`
  - on retry, set `status=queued`, `available_at=<nextAttemptAt>` and persist `last_error`
  - when `attempt_count` reaches `max-attempts`, set `status=failed`, clear lease owner fields, and
    keep a nonblank `last_error`
  - handler-requested requeues also consume attempts and fail terminally at `max-attempts`; otherwise
    a handler that always returns `REQUEUE` can bypass the poison-job budget
  - handler-requested requeues without an explicit `available_at` use the queue's configured
    retry jitter around the base poll interval so batches do not reschedule in lockstep
  - lease-expired reclaims also consume attempts; if reclaiming a row pushes `attempt_count` past
    `max-attempts`, the runtime marks it `failed` before invoking the handler again
  - persisted `attempt_count` is capped at 101 (`MAX_ATTEMPTS_MAX + 1`) and claim increments
    saturate at that cap, so corrupt/manual rows cannot overflow the claim increment while still
    allowing repeated reclaim attempts to terminal-fail an expired lease at the maximum configured
    budget
  - persisted `last_error` is bounded and includes cause, suppressed, and JDBC chained exception
    summaries; SQL exceptions include SQLState/vendor error code details for operator triage
  - operator replay can move a `failed` row back to `queued`, reset `attempt_count=0`, preserve
    `last_error` for inspection, and optionally replace `job_data`
  - on terminal completion, set `status=done`, clear `last_error`, and finalize pollable metadata
    separately

Polling / Multi-Queue Design
- We need isolation like Quartz schedulers.
- Recommended model:
  - each queue has dedicated executor pool + queue-specific config
  - each queue is polled independently (logical poll loop per queue)
  - claim SQL always filters by `queue_name`

Queue Runtime Config (example)
- `l10n.org.async-job-queue.enabled=true`
- `l10n.org.async-job-queue.asset-localize.enabled=true`
- `l10n.org.async-job-queue.store=jdbc`
- `l10n.org.async-job-queue.jdbc-dialect=mysql` (`postgresql` supported for the hot-path SQL seam)
- `l10n.org.async-job-queue.wakeup.trigger-jitter-ms=50`
- `l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=250`
- `l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=20`
- `l10n.org.async-job-queue.queues.assetlocalize.max-concurrency=10`
- `l10n.org.async-job-queue.queues.assetlocalize.max-attempts=5`
- `l10n.org.async-job-queue.queues.assetlocalize.max-retry-delay-ms=60000`
- `l10n.org.async-job-queue.queues.assetlocalize.retry-jitter-percent=20`
- `l10n.org.async-job-queue.queues.assetlocalize.lease-duration-ms=120000`
- `l10n.org.async-job-queue.queues.assetlocalize.heartbeat-interval-ms=20000`
- `l10n.org.async-job-queue.queues.assetlocalize.shutdown-await-termination-ms=30000`
- `l10n.org.async-job-queue.retention.enabled=false`
- `l10n.org.async-job-queue.retention.interval-ms=3600000`
- `l10n.org.async-job-queue.retention.done-retention-ms=604800000`
- `l10n.org.async-job-queue.retention.failed-retention-ms=2592000000`
- `l10n.org.async-job-queue.retention.batch-size=100`

Validation guardrails:
- `claim-batch-size` is capped at 1000 to avoid accidental oversized claim transactions.
- `max-concurrency` is capped at 256 to avoid accidental oversized local executors.
- `max-attempts` is capped at 100 so poison jobs cannot be made effectively unbounded by
  configuration.
- Queue names are restricted to short ASCII metric-safe identifiers (`letters`, `numbers`, `.`,
  `_`, `-`) because they appear in DB rows, REST paths, logs, thread names, and metric tags.
- Poll, heartbeat, retry, lease, and status-metric intervals have explicit upper bounds to catch
  pathological scheduler/SQL timestamp settings during startup validation.
- Cross-process wakeup trigger jitter is bounded to 5 seconds and defaults to 50 ms so PostgreSQL
  notification broadcasts do not herd every pod into the same claim statement.
- Executor shutdown wait is bounded and defaults to 30 seconds so app shutdown gives in-flight
  jobs a controlled chance to finish without allowing indefinite JVM termination delays.
- Retention interval, retention ages, and cleanup batch size have explicit upper bounds; scheduled
  retention is disabled by default.
- Handler-provided explicit requeue timestamps are validated against the portable database timestamp
  range before store transitions so bad handler output retries through the normal failure path.
- The in-memory store enforces the same timestamp bounds as the JDBC store so local/runtime tests do
  not accept queue state that durable storage would reject.
- Queue job ids are validated as canonical positive numeric strings before operator
  inspection/replay calls reach a store, so aliases like `+1` or `01` cannot resolve differently
  between in-memory and JDBC stores.

How Many "Cron" Loops?
- Not one cron for the whole system and not one physical process per queue.
- In one JVM, run one scheduled loop per configured queue (lightweight).
- This gives per-queue isolation and independent tuning while keeping implementation simple.

Adaptive Polling (per queue)
- Before claim:
  - compute free capacity from executor (`max - active - queued` or equivalent)
  - if no capacity, skip claim and record `poll.skipped.saturated`
- Claim size = `min(claim_batch_size, free_capacity * claim_multiplier)`
- Idle backoff:
  - if no job claimed for N cycles, increase sleep up to cap
  - on successful claim, reset to base interval
- Scheduled non-immediate polls apply small bounded jitter (`poll-jitter-percent`, default 10%) so
  pods do not synchronize into the same empty-poll or wakeup cadence. Jitter cannot collapse a
  positive scheduled delay to zero.
- Unexpected handler exceptions retry with exponential backoff from the base poll interval, capped by
  `max-retry-delay-ms`, plus retry-specific jitter (`retry-jitter-percent`, default 20%) that keeps
  the final retry delay positive and still capped. Handler requested requeues can still provide an
  explicit `available_at`.
- Production callers should enqueue through `AsyncJobQueueSubmissionService`, not directly through
  the low-level store. The service records enqueue telemetry and triggers the local runtime for jobs
  that are immediately available; future-dated jobs rely on normal polling until their availability
  window opens.
- Immediate durable enqueue uses the store's `enqueueNow` method so JDBC can anchor `available_at`,
  `created_date`, and `updated_date` on database time instead of the producer JVM clock.

Optional Wakeup Signals
- Wakeups are an optimization only. The durable queue table remains the source of truth and the
  runtime must continue polling because notifications are not durable.
- MySQL uses adaptive polling plus jitter only.
- PostgreSQL can add an optional `LISTEN/NOTIFY` provider:
  - enqueue commits the queue row, then sends a notification with only the logical `queue_name`
  - listeners on every pod coalesce duplicate notifications per queue within each driver
    notification batch and count skipped duplicates
  - listener callbacks call `triggerPollNow()` after bounded random
    `wakeup.trigger-jitter-ms` delay to avoid waking every pod into the same claim statement at the
    same millisecond
  - failed listeners reconnect and rely on normal polling while disconnected
  - listener shutdown keeps the thread reference while a stop is still in flight, records stop
    timeouts, and refuses duplicate starts until the previous listener thread exits
- Do not put job payloads, job IDs, or correctness state in notifications. `FOR UPDATE SKIP LOCKED`
  remains the only work arbitration mechanism.
- Metrics should include notifications sent/received/coalesced, listener reconnects, and
  wakeup-triggered claim attempts.

Claim SQL Pattern
- In TX B:
  1. `SELECT id FROM ... WHERE queue_name=? AND ((status='queued' AND available_at<=now()) OR (status='running' AND lease_until<=now())) ORDER BY available_at, id LIMIT ? FOR UPDATE SKIP LOCKED`
  2. `UPDATE ... SET status='running', lease_until=?, worker_id=?, lease_token=?, updated_at=now() WHERE id IN (...)`
- Return claimed rows to orchestrator.

PostgreSQL Portability
- Keep `AsyncJobStore` as the stable core contract and keep `JdbcAsyncJobStore` as the production
  hot-path implementation.
- MySQL 8 and PostgreSQL both support `FOR UPDATE SKIP LOCKED`; the current claim pattern is the
  right shape for both.
- The JDBC store has an explicit dialect seam (`mysql`, `postgresql`, and `hsql` for embedded
  tests) so native SQL differences stay small and testable instead of leaking into runtime logic.
- MySQL/PostgreSQL database-time reads use `CURRENT_TIMESTAMP(6)` to match the queue table's
  microsecond columns and avoid second-precision lease/backoff drift.
- Do not put the hot claim/finalize path behind generic Hibernate entity updates. Hibernate is fine
  for operator search/admin views, but not for the queue state machine where row locks, fencing
  predicates, and short transaction boundaries must remain explicit.
- PostgreSQL queue DDL lives under `db/postgresql/migration/`, outside the default MySQL Flyway
  location. It uses `TIMESTAMPTZ(6)` for queue timing columns so leases and retry availability are
  stored as absolute instants, independent of session timezone. Production rollout still needs
  Postgres Flyway plugin and location wiring for that database family.
  - MySQL: signed `BIGINT AUTO_INCREMENT`, `DATETIME(6)`, optional
    `ON UPDATE CURRENT_TIMESTAMP(6)`.
  - PostgreSQL: `BIGSERIAL` or identity column, `TIMESTAMPTZ(6)`, no MySQL `ON UPDATE` clause.
- Keep the production Java implementation as "standard core + a few native queries"; that is the
  smallest path that preserves correctness and gives us a Postgres migration seam.

Test Coverage
- Unit tests cover in-memory store semantics, runtime adaptive polling, bounded retries, heartbeats,
  heartbeat false-return, exception, and scheduling-failure containment, bounded persisted error
  summaries, bounded runtime configuration, retry backoff, executor rejection containment,
  explicit requeue budget exhaustion, scheduling, notification wakeup coalescing, runtime poll
  failure recovery, runtime latency timers, state-transition false-return and exception metrics,
  trigger scheduling failure metrics, bounded graceful executor shutdown, active-poll shutdown
  races, claimed-job store contract validation, post-terminal callback failure isolation,
  coordinator startup cleanup, synchronized trigger routing, null-safe queue configuration binding,
  and Spring configuration.
- Metrics reporter tests cover per-status depth gauges, zeroing missing statuses, configured queues,
  handler-only queues, queue discovery de-duplication, ready backlog gauges, oldest ready-job age,
  expired running lease gauges, delayed-job exclusion, and non-fatal reporting failures.
- JDBC store tests exercise enqueue, claim, concurrent claim/requeue/complete cycles, lease
  fencing, requeue, terminal failure, operator replay, lease-reclaim markers, timestamp-bound
  validation, bounded error summaries, and status counts against an embedded datasource using the
  `hsql` dialect. Store tests also cover bounded inspection/cleanup limits and terminal-row
  deletion so retention jobs cannot accidentally delete queued/running work.
- Shared store contract tests now assert expired running leases are visible before recovery and
  disappear after a successful reclaim, so future backends cannot silently omit the stalled-worker
  signal.
- Shared store contract tests also assert operator replay is scoped by both queue name and job id,
  preventing future multi-queue backends from replaying a failed row through the wrong queue.
- Inspection service tests assert cross-queue replay attempts are reported as not found and do not
  wake any runtime, so operator tooling cannot accidentally replay a job through the wrong queue.
  They also assert fatal replay wakeup errors propagate instead of being counted as ordinary replay
  or wakeup failures, oversized store-returned errors are bounded before reaching operator
  responses, and fatal read-path store errors are not counted as ordinary inspection failures.
- Retention cleaner tests assert configured queue names and handler-discovered queue names are
  de-duplicated before cleanup, preventing duplicate purge attempts and metrics for the same queue.
  They also assert fatal cleanup errors propagate instead of being counted as ordinary retention
  failures.
- Admin REST tests assert the public redacted job response shape exposes payload length but not
  `jobData` or `jobDataPreview`, making payload-redaction regressions visible at test time.
- Coordinator tests assert duplicate handler beans for one queue fail startup before any runtime is
  scheduled, preserving the one local poller/executor per logical queue contract. They also assert
  configured queues without handlers do not schedule polling runtimes, keeping config-only rollout
  and inspection/retention queues safe.
- Submission service tests assert out-of-range `availableAt` values are rejected before store
  enqueue or worker wakeup, preserving the portable timestamp bounds at the production API edge.
  They also assert due-now scheduled jobs trigger immediate worker wakeup while future jobs remain
  delayed, and fatal wakeup errors propagate instead of being counted as ordinary wakeup failures.
- PostgreSQL wakeup listener tests assert listener health gauges, stop-timeout accounting,
  duplicate-start rejection, and duplicate-notification coalescing, so a stuck listener cannot
  silently coexist with a replacement listener after lifecycle restart and a notification burst
  cannot trigger repeated local polls for the same queue.
- Spring configuration tests assert the JDBC store starts and commits transactions under the
  application's AspectJ transaction mode, because claim correctness depends on locking and
  updating in one transaction.
- Dialect tests cover MySQL/PostgreSQL `FOR UPDATE SKIP LOCKED` claim SQL, fractional database
  time, and the HSQL embedded fallback.
- Migration tests keep the MySQL and PostgreSQL queue DDL structurally aligned for core columns,
  payload nullability, constraints, and claim indexes, while allowing database-specific id,
  timestamp, and payload text type syntax.
- Opt-in Docker-backed integration tests run the same queue store contract against real MySQL and
  PostgreSQL:
  - `mvn -pl webapp -Dtest=JdbcAsyncJobStoreDatabaseIntegrationTest -Dmojito.asyncJobQueue.testcontainers=true test`
  - GitHub Actions runs this contract on the scheduled/manual
    `async-job-queue-real-db-contract` job so real-database locking stays covered without slowing
    every PR
  - default unit test runs compile this class but skip container startup unless explicitly enabled
  - the real-database contract validates store transitions, runtime drain/finalize behavior,
    multi-runtime lease reclaim fencing, and concurrent runtime claiming without duplicate
    execution or stranded queued/running rows
- Load/perf smoke coverage processes hundreds of jobs through the runtime and asserts bounded
  completion with no duplicate execution across happy-path, transient-failure, handler-requested
  deferral, poison-job, and expired-lease exhaustion paths. This is a CI guardrail, not a
  replacement for a database-backed benchmark against MySQL/PostgreSQL.
- The optional real-database perf smoke drains 1,000 jobs per backend through competing runtimes,
  logs throughput, and keeps only a very low floor assertion to catch pathological regressions
  without making developer machines timing-sensitive:
  - `mvn -pl webapp -Dtest=JdbcAsyncJobStoreDatabaseIntegrationTest#runtimePerformanceSmokeRunsAgainstRealDatabases -Dmojito.asyncJobQueue.testcontainers=true -Dmojito.asyncJobQueue.perf=true test`

Monitoring (MVP Required)
- Gauges:
  - queue depth by `queue_name,status` via sampled `asyncJobQueue.status` gauges, not scrape-time
    database queries
  - ready queued backlog via sampled `asyncJobQueue.ready.count` and
    `asyncJobQueue.ready.oldestAgeMs`; delayed retry/future work stays visible in queued status
    counts but does not contribute to ready backlog
  - expired running leases via sampled `asyncJobQueue.running.expired.count` and
    `asyncJobQueue.running.expired.oldestAgeMs`; this separates worker-crash/stall recovery from
    normal queued backlog
  - executor active/queued by queue
  - local poll-loop health via `asyncJobQueue.poll.started`,
    `asyncJobQueue.poll.scheduled`, and `asyncJobQueue.poll.active`; alert when a started runtime
    has no scheduled or active poll for a sustained interval
  - PostgreSQL wakeup listener health via `asyncJobQueue.wakeup.listener.running`,
    `asyncJobQueue.wakeup.listener.connected`, and `asyncJobQueue.wakeup.listener.threadAlive`;
    alert when a running listener is disconnected for a sustained interval or when a stopped
    listener still has a live thread
- Counters:
  - claimed, completed, retried, execution-failed, lease-expired-reclaimed, poll-skipped-saturated
  - local executor rejections before queue state recovery
  - local executor submit failures and handler-requested requeue budget exhaustion
  - heartbeat renewal false-return, exception, and schedule failures
  - terminal-row retention deletions and cleanup failures by queue/status
  - `GenerateMultiLocalizedAssetJob.schedule` by `route,result` to validate child fan-out canaries
    across Quartz and durable queue routes
  - `assetWS.getLocalizedAssetForContentAsync.schedule` by `route,result` to validate
    single-locale canaries without relying on queue-table side effects
  - `asyncJobQueue.leaseExpiredReclaimed` is emitted from claim results marked by the store when a
    previously running row is recovered after lease expiry
  - `asyncJobQueue.attempt.exhausted` is emitted when a lease-expired reclaim exceeds the attempt
    budget and is failed before handler invocation
- Timers:
  - queue wait latency (`claimed_at - available_at`)
  - processing latency (`finished_at - claimed_at`)
  - claim SQL latency
- Error counters:
  - deadlocks / lock timeouts / claim exceptions
  - `asyncJobQueue.claim.failed` by `queueName,failure` with low-cardinality failure kinds
    (`deadlock`, `serialization`, `lock`, `timeout`, `dataAccess`, `other`)
  - `asyncJobQueue.poll.failed.byFailure` by `queueName,failure` for scheduled poll recovery;
    this mirrors the claim failure taxonomy so MySQL contention can be tracked without relying on
    logs
  - failed state transitions/fencing failures by transition
  - failed immediate trigger/wakeup scheduling
  - failed initial or follow-up scheduled-poll registration
  - `asyncJobQueue.poll.unscheduled` when both normal and recovery poll scheduling fail, leaving
    the local runtime dependent on a future enqueue/wakeup to restart polling
  - active poll did not stop before the bounded executor shutdown wait elapsed
  - active poll shutdown wait was interrupted before executor shutdown
  - status metrics reporting failures
- Operator counters/logs:
  - `asyncJobQueue.enqueue` by `queueName,result` for production enqueue attempts; results are
    low-cardinality (`succeeded`, `failed`)
  - `asyncJobQueue.enqueueWakeup.failed` by `queueName` when enqueue succeeded but the local
    runtime wakeup failed; polling remains the correctness fallback
  - `asyncJobQueue.inspection.find` by `queueName,status,result` for bounded list attempts;
    invalid caller status is tagged as `status=invalid`, not the caller-provided value
  - `asyncJobQueue.inspection.get` by `queueName,result` for detail lookup attempts;
    invalid caller job ids are tagged as `result=invalidId`
  - `asyncJobQueue.inspection.requeue` by `queueName,result` for service-level replay attempts;
    results are low-cardinality (`succeeded`, `notFound`, `notFailed`, `invalidId`, `failed`)
  - `asyncJobQueue.inspection.requeueWakeup.failed` by `queueName` when a replay succeeded but the
    local runtime wakeup failed; polling remains the correctness fallback
  - replay logs include queue/job identifiers and whether replacement payload was supplied, never
    the payload itself
  - `asyncJobQueue.inspection.count` by `queueName,result` for read-only admin status-count
    lookups
  - `asyncJobQueue.inspection.readyStatus` by `queueName,result` for read-only ready backlog
    lookups
  - `asyncJobQueue.inspection.expiredLeaseStatus` by `queueName,result` for read-only expired
    running lease lookups

Operator Controls
- Store-level inspection supports listing recent jobs by `queue_name` and `status`.
- Read-only admin status counts are exposed via
  `GET /api/admin/async-job-queue/queues/{queueName}/status-counts`; the response contains only
  stable status/count pairs and never job payload.
- Read-only ready backlog is exposed via
  `GET /api/admin/async-job-queue/queues/{queueName}/ready-status`; the response contains
  queueName, ready count, oldest ready availability, observation time, and oldest ready age, never
  job payload.
- Read-only expired running leases are exposed via
  `GET /api/admin/async-job-queue/queues/{queueName}/expired-lease-status`; the response contains
  queueName, expired lease count, oldest expired lease timestamp, observation time, and oldest
  expired lease age, never job payload.
- Read-only admin job summaries are exposed via
  `GET /api/admin/async-job-queue/queues/{queueName}/jobs?status=failed&limit=100`; the response
  intentionally omits `job_data` and `jobDataPreview`, returning only metadata, last error, and
  payload length.
- Read-only admin job detail is exposed via
  `GET /api/admin/async-job-queue/queues/{queueName}/jobs/{jobId}` with the same redacted response
  shape and `404` for missing queue/id pairs.
- Store-level inspection, batch id lookup, claim, and cleanup methods reject excessive
  caller-provided limits so an admin path cannot accidentally issue pathological queue-table
  queries.
- Operator replay of a terminal failed job triggers the local runtime after the row is requeued so
  manual recovery is not delayed until the next idle poll cycle.
- The queue inspection service wraps the store with a bounded default limit, status parsing,
  same-queue id checks, capped payload previews for lists, bounded error summaries,
  full-payload detail lookup, and failed-only replay that maps missing/non-failed jobs to explicit
  operator errors and emits replay counters/logs.
- Store-level replay only transitions `failed -> queued`; it does not touch running or completed
  jobs, resets the attempt budget for a fresh retry cycle, and keeps the previous `last_error`
  until success or the next failure.
- Store-level retention only deletes bounded batches of terminal `done` or `failed` rows older
  than an operator-provided `updated_date` cutoff. It rejects queued/running statuses.
- Optional scheduled retention wraps the store primitive when
  `l10n.org.async-job-queue.retention.enabled=true`, deleting at most `batch-size` done rows and
  `batch-size` failed rows per queue per run.
- A broader REST/admin surface can wrap the inspection service later after an explicit replay-audit
  review. The narrow status-count, redacted-summary/detail, and assetlocalize pollable-repair
  endpoints are exposed first because they do not replay work or expose payload.

Rollout Plan
1. Implement queue infra + assetlocalize-only integration behind feature flag.
2. Shadow metrics first (optionally dual-write enqueue + Quartz for tiny canary).
3. Enable queue execution for assetlocalize in staging.
4. Validate:
  - throughput
  - queue lag
  - reclaim on worker restart
  - retry/failure paths
5. Promote to prod and remove RAM stop-gap.

Open Questions
- Which keys should be mandatory in `job_data`, and which should remain queue-specific?
- Should we keep one queue table now and expand by `queue_name`, or create table-per-queue later only if operationally required?

Recommendation
- Build one-table multi-queue-ready design now (`queue_name` + per-queue poller/executor config).
- Start with only `assetlocalize` enabled.
- Keep code small and explicit; optimize only after production metrics.
