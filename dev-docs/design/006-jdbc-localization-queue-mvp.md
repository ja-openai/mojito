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
- Retry policy:
  - runner decides retry vs completion outside the store
  - each claim increments `attempt_count`
  - on retry, set `status=queued`, `available_at=<nextAttemptAt>` and persist `last_error`
  - when `attempt_count` reaches `max-attempts`, set `status=failed`, clear lease owner fields, and keep `last_error`
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
- `l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=250`
- `l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=20`
- `l10n.org.async-job-queue.queues.assetlocalize.max-concurrency=10`
- `l10n.org.async-job-queue.queues.assetlocalize.max-attempts=5`
- `l10n.org.async-job-queue.queues.assetlocalize.lease-duration-ms=120000`
- `l10n.org.async-job-queue.queues.assetlocalize.heartbeat-interval-ms=20000`

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

Claim SQL Pattern
- In TX B:
  1. `SELECT id FROM ... WHERE queue_name=? AND ((status='queued' AND available_at<=now()) OR (status='running' AND lease_until<=now())) ORDER BY available_at, id LIMIT ? FOR UPDATE SKIP LOCKED`
  2. `UPDATE ... SET status='running', lease_until=?, worker_id=?, lease_token=?, updated_at=now() WHERE id IN (...)`
- Return claimed rows to orchestrator.

Monitoring (MVP Required)
- Gauges:
  - queue depth by `queue_name,status`
  - executor active/queued by queue
- Counters:
  - claimed, completed, retried, execution-failed, lease-expired-reclaimed, poll-skipped-saturated
- Timers:
  - queue wait latency (`claimed_at - created_at`)
  - processing latency (`finished_at - claimed_at`)
  - claim SQL latency
- Error counters:
  - deadlocks / lock timeouts / claim exceptions

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
- Backoff policy defaults: fixed vs exponential with jitter.

Recommendation
- Build one-table multi-queue-ready design now (`queue_name` + per-queue poller/executor config).
- Start with only `assetlocalize` enabled.
- Keep code small and explicit; optimize only after production metrics.
