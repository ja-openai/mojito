Async Job Queue MVP (Backend)
=============================

Context
- Quartz trigger fan-out (one locale => one dynamic trigger) creates lock/contention pressure on MySQL under large Android pulls.
- RAM-backed scheduler improves throughput but is non-durable and pod-local.
- We need a durable queue model with bounded concurrency, retries, and monitoring.

Goals
- Replace high-fan-out locale child execution with a durable MySQL queue.
- Keep existing API/CLI contract and PollableTask UX.
- Keep implementation simple and targeted to one workload first (`assetlocalize` path).
- Design for multi-queue isolation now (without overbuilding).

Non-Goals (MVP)
- Replacing Quartz globally.
- Replacing all pollable workloads.
- Building a generic workflow engine.

Decision Summary
- Keep Quartz for parent orchestration and cron-like jobs.
- Introduce a new JDBC-backed async job queue only for localized-asset child jobs.
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
- Database constraints reject unsupported statuses, negative attempt counts, and inconsistent lease
  owner fields where a row is `running` without `(lease_until, worker_id, lease_token)` or a
  non-running row still has lease ownership attached.

Execution Flow (MVP)
1. Parent `GenerateMultiLocalizedAssetJob` still creates child `pollable_task`s.
2. Instead of scheduling a child Quartz job, enqueue one async job row per child.
3. Queue worker claims jobs for `queue_name=assetlocalize`.
4. Worker loads input from existing pollable blob storage.
5. Worker executes localization logic.
6. Worker saves pollable output blob and calls `finishTask(...)`.
7. Worker marks queue row `done`.
8. On failure: retry until the per-queue attempt budget is exhausted; then mark the queue row `failed` with `last_error` for operator triage.

Transaction Boundaries (Critical)
- TX A (enqueue): insert queue row.
- TX B (claim): select claimable rows + update to `running` + set lease.
- No DB transaction during heavy work (`generateLocalized`).
- TX C (heartbeat): extend `lease_until` while running.
- TX D (finalize): mark `done` OR `queued` with future `available_at`.
- Heartbeat/finalize/requeue updates must match `(id, worker_id, lease_token)` for fencing.

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
  - when `attempt_count` reaches `max-attempts`, set `status=failed`, clear lease owner fields, and keep `last_error`
  - handler-requested requeues also consume attempts and fail terminally at `max-attempts`; otherwise
    a handler that always returns `REQUEUE` can bypass the poison-job budget
  - operator replay can move a `failed` row back to `queued`, reset `attempt_count=0`, preserve
    `last_error` for inspection, and optionally replace `job_data`
  - on terminal completion, set `status=done` and finalize pollable metadata separately

Polling / Multi-Queue Design
- We need isolation like Quartz schedulers.
- Recommended model:
  - each queue has dedicated executor pool + queue-specific config
  - each queue is polled independently (logical poll loop per queue)
  - claim SQL always filters by `queue_name`

Queue Runtime Config (example)
- `l10n.org.async-job-queue.enabled=true`
- `l10n.org.async-job-queue.store=jdbc`
- `l10n.org.async-job-queue.jdbc-dialect=mysql` (`postgresql` supported for the hot-path SQL seam)
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
- Poll, heartbeat, retry, lease, and status-metric intervals have explicit upper bounds to catch
  pathological scheduler/SQL timestamp settings during startup validation.
- Executor shutdown wait is bounded and defaults to 30 seconds so app shutdown gives in-flight
  jobs a controlled chance to finish without allowing indefinite JVM termination delays.
- Retention interval, retention ages, and cleanup batch size have explicit upper bounds; scheduled
  retention is disabled by default.

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
  pods do not synchronize into the same empty-poll or wakeup cadence.
- Unexpected handler exceptions retry with exponential backoff from the base poll interval, capped by
  `max-retry-delay-ms`, plus retry-specific jitter (`retry-jitter-percent`, default 20%). Handler
  requested requeues can still provide an explicit `available_at`.

Optional Wakeup Signals
- Wakeups are an optimization only. The durable queue table remains the source of truth and the
  runtime must continue polling because notifications are not durable.
- MySQL uses adaptive polling plus jitter only.
- PostgreSQL can add an optional `LISTEN/NOTIFY` provider:
  - enqueue commits the queue row, then sends a notification with only the logical `queue_name`
  - listeners on every pod coalesce duplicate notifications per queue
  - listener callbacks call `triggerPollNow()` with tiny random jitter to avoid waking every pod
    into the same claim statement at the same millisecond
  - failed listeners reconnect and rely on normal polling while disconnected
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
  - MySQL: `BIGINT AUTO_INCREMENT`, `DATETIME(6)`, optional `ON UPDATE CURRENT_TIMESTAMP(6)`.
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
  races,
  coordinator startup cleanup, synchronized trigger routing, null-safe queue configuration binding,
  and Spring configuration.
- Metrics reporter tests cover per-status depth gauges, zeroing missing statuses, configured queues,
  handler-only queues, and non-fatal reporting failures.
- JDBC store tests exercise enqueue, claim, lease fencing, requeue, terminal failure, operator
  replay, lease-reclaim markers, timestamp-bound validation, bounded error summaries, and status
  counts against an embedded datasource using the `hsql` dialect. Store tests also cover bounded
  inspection/cleanup limits and terminal-row deletion so retention jobs cannot accidentally delete
  queued/running work.
- Spring configuration tests assert the JDBC store starts and commits transactions under the
  application's AspectJ transaction mode, because claim correctness depends on locking and
  updating in one transaction.
- Dialect tests cover MySQL/PostgreSQL `FOR UPDATE SKIP LOCKED` claim SQL, fractional database
  time, and the HSQL embedded fallback.
- Migration tests keep the MySQL and PostgreSQL queue DDL structurally aligned for core columns
  constraints, and claim indexes, while allowing database-specific id/timestamp syntax.
- Opt-in Docker-backed integration tests run the same queue store contract against real MySQL and
  PostgreSQL:
  - `mvn -pl webapp -Dtest=JdbcAsyncJobStoreDatabaseIntegrationTest -Dmojito.asyncJobQueue.testcontainers=true test`
  - GitHub Actions runs this contract on the scheduled/manual
    `async-job-queue-real-db-contract` job so real-database locking stays covered without slowing
    every PR
  - default unit test runs compile this class but skip container startup unless explicitly enabled
  - the real-database contract drains 120 queued jobs with 8 concurrent workers per database to
    validate transactional `FOR UPDATE SKIP LOCKED` claiming without duplicate execution
- Load/perf smoke coverage processes hundreds of jobs through the runtime and asserts bounded
  completion with no duplicate execution. This is a CI guardrail, not a replacement for a
  database-backed benchmark against MySQL/PostgreSQL.

Monitoring (MVP Required)
- Gauges:
  - queue depth by `queue_name,status` via sampled `asyncJobQueue.status` gauges, not scrape-time
    database queries
  - executor active/queued by queue
- Counters:
  - claimed, completed, retried, execution-failed, lease-expired-reclaimed, poll-skipped-saturated
  - local executor rejections before queue state recovery
  - local executor submit failures and handler-requested requeue budget exhaustion
  - heartbeat renewal false-return, exception, and schedule failures
  - terminal-row retention deletions and cleanup failures by queue/status
  - `asyncJobQueue.leaseExpiredReclaimed` is emitted from claim results marked by the store when a
    previously running row is recovered after lease expiry
- Timers:
  - queue wait latency (`claimed_at - available_at`)
  - processing latency (`finished_at - claimed_at`)
  - claim SQL latency
- Error counters:
  - deadlocks / lock timeouts / claim exceptions
  - failed state transitions/fencing failures by transition
  - failed immediate trigger/wakeup scheduling
  - failed initial or follow-up scheduled-poll registration
  - active poll did not stop before the bounded executor shutdown wait elapsed
  - status metrics reporting failures
- Operator counters/logs:
  - `asyncJobQueue.inspection.find` by `queueName,status,result` for bounded list attempts;
    invalid caller status is tagged as `status=invalid`, not the caller-provided value
  - `asyncJobQueue.inspection.get` by `queueName,result` for detail lookup attempts
  - `asyncJobQueue.inspection.requeue` by `queueName,result` for service-level replay attempts;
    results are low-cardinality (`succeeded`, `notFound`, `notFailed`, `failed`)
  - replay logs include queue/job identifiers and whether replacement payload was supplied, never
    the payload itself

Operator Controls
- Store-level inspection supports listing recent jobs by `queue_name` and `status`.
- Store-level inspection, claim, and cleanup methods reject excessive caller-provided limits so an
  admin path cannot accidentally issue pathological queue-table queries.
- The queue inspection service wraps the store with a bounded default limit, status parsing,
  same-queue id checks, capped payload previews for lists, full-payload detail lookup, and
  failed-only replay that maps missing/non-failed jobs to explicit operator errors and emits
  replay counters/logs.
- Store-level replay only transitions `failed -> queued`; it does not touch running or completed
  jobs, resets the attempt budget for a fresh retry cycle, and keeps the previous `last_error`
  until success or the next failure.
- Store-level retention only deletes bounded batches of terminal `done` or `failed` rows older
  than an operator-provided `updated_date` cutoff. It rejects queued/running statuses.
- Optional scheduled retention wraps the store primitive when
  `l10n.org.async-job-queue.retention.enabled=true`, deleting at most `batch-size` done rows and
  `batch-size` failed rows per queue per run.
- A REST/admin surface can wrap the inspection service later after an explicit authorization,
  payload-redaction, and replay-audit review.

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
