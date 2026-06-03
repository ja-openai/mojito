Async Job Queue MVP (Backend)
=============================

Review status: see [the active review ledger](async-job-queue-review.md) and
[the Quartz migration design](async-job-queue-quartz-migration.md). The branch
remains default-off; implementation and local test coverage are not rollout approval.

Context
- Quartz trigger fan-out (one locale => one dynamic trigger) creates lock/contention pressure on MySQL under large Android pulls.
- RAM-backed scheduler improves throughput but is non-durable and pod-local.
- We need a durable queue model with bounded concurrency, retries, and monitoring.

Goals
- Replace high-fan-out locale child execution and async single-locale asset generation with a
  durable MySQL queue.
- Keep existing API/CLI contract and PollableTask UX.
- Reject conflicting URL/body asset IDs at single and parallel async admission, including
  the default Quartz route. Omitted/null or matching body IDs remain valid; synchronous
  localization/pseudo-localization are unchanged. This malformed-request tightening is
  independent of queue enablement and does not provide repository authorization.
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
- Native SQL makes transaction, lock, update-count and query-plan behavior directly reviewable.
- Hibernate is not inherently unsafe for a queue: a native-query implementation with the same
  transaction and fencing contract is possible. Managed entities add persistence-context and
  flush behavior to review, with no demonstrated benefit for this small state-transition API.
- Keep JDBC as the current implementation choice. Compare measured throughput, tail latency,
  contention and connection use before claiming a performance advantage over another adapter.
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
   With all queue producer flags enabled and no tracking name, it resolves every requested
   repository locale and output tag before submitting any child. The in-memory scalar plan
   preserves order and nullable output overrides without repeating lookups. Missing membership
   is rejected even with an explicit output alias. Quartz routes retain lazy per-child resolution.
   This prevents resolution-detectable partial fan-out, not crashes or uncertain child admission.
2. Instead of scheduling a Quartz job, enqueue one async job row per localized-asset task.
3. Queue worker claims jobs for `queue_name=assetlocalize`.
4. Worker loads input from existing pollable blob storage. Queue-only decoding rejects duplicate
   fields, trailing documents and null input before eligibility checks or generation;
   parser errors expose no source values. Existing mapper binding/unknown-field settings and
   storage errors are preserved. This is framing validation, not a checksum or admission binding.
5. Worker executes localization logic.
6. Worker stages an attempt-private output blob and returns its reference.
7. Runtime marks queue row `done`, persisting only the winning attempt's output reference.
8. Runtime invokes the handler post-terminal callback to publish that output to PollableTask
   and call `finishTask(...)`. The callback rereads the task first and skips publication/finish
   if it is already terminal. An expired worker cannot overwrite the winning canonical output.
   Both callback and terminal repair validate raw private/legacy output bytes as strict UTF-8
   before the existing JSON framing checks. Invalid encoding is rejected with redacted diagnostics,
   not silently replaced with U+FFFD and published as success. Valid U+FFFD remains supported.
   This leaves DONE pending repair without rewriting the blob or finishing the task; it is not
   checksum binding or recovery of missing/expired output. Legacy general-purpose string reads
   are unchanged; queue input also receives strict raw UTF-8 validation before generation.
9. On ordinary failure: retry until the per-queue attempt budget is exhausted; then mark the queue row
   `failed` with `last_error` for operator triage and run the same post-terminal callback to
   finish the pollable task with the captured exception. Invalid persisted identity and malformed
   stored input UTF-8/syntax/shape (including duplicate/trailing/null JSON), and unsupported
   non-null `pullRunName` inputs instead request FAILED on the first attempt through
   `AsyncJobPermanentFailureException`. This does not classify missing
   blobs/tasks, storage outages, mapper-definition failures, generation or output
   failures as permanent. The callback keeps permanent queue failures classified as unexpected
   task errors; retry policy is not user-error classification. A malformed identity cannot safely
   select a task, so its callback fails without guessing one. Once a valid-identity task is finished,
   editing its blob and replaying the row does not reopen it; use the domain recovery gate below.

Transaction Boundaries (Critical)
- TX A (enqueue): insert queue row.
- TX B (claim): select claimable rows + update to `running` + set lease.
- No queue-store transaction during heavy work (`generateLocalized`). Domain services may use
  their own transactions; queue isolation does not make their effects exactly-once.
- TX C (heartbeat): extend `lease_until` while running.
- TX D (finalize): mark `done` OR `queued` with future `available_at`.
- TX E (pollable finalize): after TX D successfully marks a row terminal, finish the child
  `pollable_task` and emit `asyncJobQueue.handler.completion.failed` if that post-terminal
  callback fails.
- Heartbeat/finalize/requeue updates must match `(id, worker_id, lease_token)` for fencing.

Pollable Finalization Repair
- `AssetLocalizeAsyncJobRepairService.repairTerminalPollableTask(...)` is an idempotent repair path
  for the case where TX D succeeds but TX E fails or is never invoked.
- A failed TX D commit acknowledgement does not establish rollback. The runtime records
  `asyncJobQueue.transition.failed{transition=done}` and skips the terminal callback. If TX D
  actually committed, the stored winning reference permits terminal repair while its blob is
  retained, without rerunning generation. If TX D rolled back, the row remains running until lease
  reclaim; repair rejects that nonterminal row and a later attempt may generate again. This path is not counted
  as a business-handler failure or an invoked callback failure.
- For `done` rows it parses the persisted `job_data`, loads the child `pollable_task`, and finishes
  it only if it is still open and the winning output can be published. Missing output fails repair
  rather than incorrectly reporting success.
- Repair explicitly refreshes the task in an independent read transaction before checking its
  terminal state. A request-bound Hibernate persistence context can otherwise retain an open task
  after another transaction finishes it, despite `REQUIRES_NEW`. Legacy task getters and worker
  callbacks are unchanged. This prevents a cached-state decision, not a concurrent finish between
  the read and publication; atomic business fencing remains required.
- Private and legacy canonical output must decode as one non-null output document without
  duplicate fields or trailing JSON. Parse failures report only the task ID, without blob-derived
  exception details. Existing nullable fields remain compatible; this is not checksum validation.
- For `failed` rows it finishes the child `pollable_task` with a generic unexpected error and a
  queue-ID-only exception. It does not reconstruct an error classification or copy `last_error`
  into the task message, stack, causes or suppressed exceptions. Full task JSON and inspection
  expose the task stack to authenticated readers; raw diagnostics stay on the admin-inspected
  queue row. This changes future repairs, not historical task errors or normal handler callbacks.
- Repair emits `assetLocalizeAsyncJob.repair{queueName,status,result}` with bounded tags.
- Both handler completion callbacks preserve a task already observed finished, matching repair's
  no-reopen behavior. They emit `assetLocalizeAsyncJob.pollableTask.finish.skipped` with
  `queueName`, `callback=done|failed`, and `reason=alreadyFinished`; a skip is not counted as a
  newly finished task. Missing/failed task lookup prevents success-callback publication and uses
  the existing completion-failure counters. Queue DONE remains separate from task/client success.
  This adds a business-task read before success publication. A skipped already-successful task
  does not validate or restore expired canonical output, matching repair's existing contract.
- The admin-only endpoint
  `POST /api/admin/async-job-queue/assetlocalize/jobs/{asyncJobId}/pollable-task/repair` exposes
  the same bounded result without returning job payload.
- Attempt-private blobs use the existing `MIN_1_DAY` policy. Queue row retention (seven days for
  done by default) does not extend blob retention: repair requires both records and must run before
  either is cleaned up. Align these lifetimes before rollout. Old workers must be drained before
  switching to private output publication because they still write the canonical output directly.
- The shared database blob default is corrected from 84,600 to 86,400 seconds (one day).
  This applies to subsequent temporary writes, including non-queue writes; it is not gated by
  queue enablement. Explicit TTL overrides and existing rows are not changed by the default fix.
  Creation-relative expiration, prefix cleanup and external provider policies still apply, so
  this correction is not a queue input/output pin or a complete repair-lifetime guarantee.
- Explicit database `PERMANENT` overwrites now clear previous expiry. The ordinary
  database cleaner rechecks expiry when deleting candidate IDs so a committed promotion or
  extension is not lost between selection and deletion. Zero-delete batches end the run
  conservatively; later runs can collect remaining expired rows. This shared change is not
  queue-gated, does not backfill rows or reset creation time, and does not change prefix or
  cloud cleanup. Drain older ordinary-cleaner binaries before relying on the guard. Fresh
  queue-owned keys and reference-aware lifetime management are still required.
- The fence protects queue transitions and canonical output publication, not other writes inside
  localization (for example pull-run lineage). Those effects still need workload-specific review.
- Generic failed-row replay is not yet a supported assetlocalize recovery workflow: reusing an
  already-failed PollableTask retains its error state. The handler now rejects an already-finished
  task before reading input or generating output; its permanent-failure callback leaves an
  already-finished task unchanged. This rejection directly throws
  `AsyncJobPermanentFailureException`, requesting FAILED on the first attempt rather than spending
  the remaining retry budget. Only a direct handler throw has that meaning; ordinary failures,
  wrapped markers and storage failures keep their existing policies. FAILED remains lease-fenced,
  and its callback runs only after an acknowledged transition. This read-time guard is not an
  atomic business fence:
  a task can still time out after either the generation or publication check. Keep replay out of
  the public admin API until
  fresh-task or explicitly reset-task semantics and late-result policy are defined.

Spring Guardrails Against Transaction Leakage
- Queue orchestration never runs handler code inside a queue-store transaction.
- The JDBC store owns explicit short `TransactionTemplate` boundaries with
  `PROPAGATION_REQUIRES_NEW` and `READ_COMMITTED` isolation for
  claim/heartbeat/finalize/inspection operations, so queue correctness does not depend on Spring
  proxy or AspectJ transaction advice and does not inherit a broader application transaction's
  isolation level.
- At construction, the JDBC template and transaction manager must share the same datasource
  resource. Supported managers are Spring's `DataSourceTransactionManager` (including
  `JdbcTransactionManager`) and `JpaTransactionManager` with `HibernateJpaDialect` and matching
  Spring-managed entity-manager-factory datasource metadata. A matching database URL is not
  enough. Ordinary transaction-aware proxies are supported; nested manager proxies and separate
  lazy/routing wrapper instances are rejected. Keep this wiring stable after construction.
  Validation opens no connection and adds no per-job query; opaque manager decorators and
  unverifiable JPA factories fail startup rather than risking autocommit outside the owned transaction.
- Claim readback stays inside the same transaction as `SELECT ... FOR UPDATE SKIP LOCKED` and the
  lease update so inconsistent readbacks roll back the claim instead of leaving work leased.
- Runtime checks `!TransactionSynchronizationManager.isActualTransactionActive()` immediately before
  invoking a handler. Violations are counted as `asyncJobQueue.handler.transaction.active` and then
  fail through the normal retry/terminal-failure path before handler business logic runs.

Failure + Restart Semantics
- Claim uses lease (`lease_until`), not ownership lock.
- Queue names and lease owners have exact string identity, including case and trailing spaces.
  MySQL queries compare both operands as unpadded UTF-8 bytes, independent of inherited column
  collation. ASCII queue names retain an ordinary indexed equality prefilter; owner mutations
  already select by primary-key ID and avoid plain owner equality that could coerce Unicode to
  a legacy column charset. Apply the guard to claim selection/update, inspection, replay, both
  retention predicates and every ownership lock/update. HSQL additionally checks string lengths
  to reject its implicit space padding; PostgreSQL SQL predicates remain unchanged. No table,
  stored value or migration checksum is changed. Older binaries still use collation-sensitive
  comparisons and must be drained before relying on isolation. Exact filtering does not promise
  separate index ranges/locks for case aliases, unchanged query plans or recovery of lossy text.
- If worker pod crashes, lease expires; job becomes claimable again.
- Claim writes a new random `lease_token`; stale workers that lost lease cannot update queue state.
- A definitive rejected heartbeat stops subsequent renewal queries for that execution. Database
  exceptions are uncertain outcomes, so later heartbeats still retry. If rejection is already known
  before handler entry, skip that invocation without a queue transition/callback and release its
  local slot through normal cleanup. This local check adds no SQL and cannot rule out lease loss
  after the check. Neither condition interrupts an already-running handler or releases its worker
  slot early; finalization remains token-fenced. Renewals continue
  while a post-handler transition is pending. A rejection during that phase is classified after
  the transition resolves: confirmed success explains the cleared lease without a false failure
  metric, while rejection/exception retains the failure signal. Successful transitions stop new
  renewals before callbacks, which must still run for the winner. Already-reported lease loss and
  heartbeat exceptions are never erased by later success. Cleanup stops future renewal calls even
  if scheduled-future cancellation fails; an already-running rejection after cleanup remains visible
  unless the transition was confirmed successful. No SQL, metrics or callbacks run under the small
  per-execution state monitor. An indefinitely stuck transition can delay rejection classification;
  bound database waits and monitor in-flight/transition health rather than rely on this counter alone.
- JDBC lease transitions lock the matching owner row before sampling database time, so a wait on
  another transaction cannot authorize an already-expired lease. Claim lease durations start after
  candidate locks are acquired. PostgreSQL uses `clock_timestamp()`; MySQL reads
  `UTC_TIMESTAMP(6)` in a fresh statement after the lock. This avoids transaction-start or
  pre-lock timestamps for lease decisions and avoids using cross-pod JVM clocks.
- Runtime-owned delayed requeues (handler exceptions, executor rejection, default handler requeue
  delay) use a store-level relative delay so JDBC derives `available_at` from database time too.
- Retry policy:
  - runner decides retry vs completion outside the store
  - each claim increments `attempt_count`
  - a claim commit acknowledgement can fail before the runtime receives any jobs. A committed
    lease still consumes an attempt and remains protected until expiry; a rolled-back claim
    consumes none and preserves the previous row state. Neither outcome dispatches work from the
    failed call. Claim uncertainty can exhaust a small retry budget without a business-handler invocation; this
    produces a retained FAILED row with an attempt-budget error, not an invisible deletion.
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
- Claim capacity counts both running handlers and accepted work awaiting an executor thread.
  The executor has a handoff buffer bounded by `max-concurrency`: a handler releases its capacity
  just before its executor thread returns, and a zero-capacity executor can reject the next job in
  that gap. The buffer does not increase the runtime's claim limit or handler concurrency.
- Recommended model:
  - each queue has dedicated executor pool + queue-specific config
  - each queue is polled independently (logical poll loop per queue)
  - claim SQL always filters by `queue_name`

Queue Runtime Config (example)
- `l10n.org.async-job-queue.enabled=true`
- `l10n.org.async-job-queue.asset-localize.enabled=true`
- `l10n.org.async-job-queue.asset-localize.producer-enabled=true`
- `l10n.org.async-job-queue.asset-localize.fanout-enabled=false`
- `l10n.org.async-job-queue.queues.assetlocalize.consumer-enabled=true`
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

Producer rollback and consumer placement:
- Both feature flags (`enabled` and `asset-localize.enabled`) remain default `false`.
  The independent producer/consumer controls default `true` for compatibility with explicitly
  enabled deployments; they do not enable the feature by themselves.
- Parallel children additionally require `asset-localize.fanout-enabled=true`;
  its default is `false`, so enabling the direct queue route alone leaves child
  submission on Quartz. Retain the configured child scheduler and lazy locale
  resolution on that path. Keep fan-out off until durable parent/slot recovery is
  implemented and verified; explicit opt-in is not production-readiness approval.
  This parent-level control leaves direct admission, consumers, existing rows and
  repair unchanged. Old parent binaries ignore it, and a partial parent cannot be
  safely replayed or rerouted merely by changing a flag.
- Requests with any non-null `pullRunName` stay on Quartz even when queue flags are enabled.
  This includes empty/whitespace names. Direct queue submission rejects before writes, and
  consumers reject previously queued tracked inputs before generation with an explicit
  permanent failure, rather than spending the remaining retry budget. The acknowledged
  fenced FAILED transition invokes the redacted task-error callback; uncertain transitions
  and missed callbacks still require recovery. No tracking field is silently removed and no
  ambiguous enqueue is automatically retried through Quartz. Drain older workers first;
  this containment does not fence their in-flight business writes or repair old lineage.
  See [the confirmed stale-lineage defect](async-job-queue-review.md#stale-lineage-reproduction-and-containment).
- Set `asset-localize.producer-enabled=false` on all API and multi-locale parent workers to route
  new localized-asset work through Quartz. Keep the feature flags enabled on drain workers:
  handler, output, inspection, and repair support remain available. Direct calls to the queue
  submission adapter reject before writing when its producer control is disabled.
- Set `queues.<queueName>.consumer-enabled=false` only to prevent that node from claiming work.
  It allocates no queue executor/heartbeat scheduler but may still submit to remote JDBC workers.
  Status collection, retention, and repair are independent and are not disabled by this setting.
  Local wakeup misses report `asyncJobQueue.trigger.missed{reason=consumerDisabled}` rather than
  implying a missing handler/runtime; no local claim is attempted.
  With no enabled registered consumers, PostgreSQL skips its listener connection; notifications
  can still be published for other nodes. Polling remains the fallback on consumers.
- These are restart-applied settings, not a live pause, repository allowlist, shadow mode, or
  persistent admission decision. Old binaries ignore the new keys. Never fall back between
  backends after a possibly committed enqueue; see the migration plan for admission recovery.
- Stop new queue production before draining queued (including delayed), running, and repair
  obligations. Do not disable the umbrella flags until those obligations are resolved.

Validation guardrails:
- `consumer-enabled=false` requires `store=jdbc`; another process cannot drain an in-memory store.
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
- Local wakeups read an immutable runtime snapshot without the coordinator lifecycle lock,
  so a draining handler can submit follow-up work without waiting on its own shutdown. The
  snapshot is published only after successful startup and withdrawn before shutdown/rollback
  drain; `isRunning()` is false during that drain. Enqueue remains accepted independently of
  local consumer lifecycle, and missed hints rely on another consumer or polling after restart.
  Start/stop remain serialized. Shutdown and failed-start rollback stop polling on every
  created runtime before waiting for any executor to drain. Already-dispatched scheduler
  callbacks honor the stopped flag even if cancellation fails. This is not an atomic
  all-queue barrier: the short stop requests run sequentially, and an already-admitted poll
  can still finish. Active-poll/executor waits retain their per-runtime bounds.
- Each queue owns a heartbeat scheduler (up to five threads, bounded by concurrency) that stops
  after executor drain. It is not a Spring bean, so application context shutdown cannot reject
  renewals for buffered jobs before the coordinator drains them, or alter global scheduler selection.
  After the drain deadline, renewals stop and lease expiry provides recovery; handlers must still
  tolerate duplicate work and failed finalization.
- Failed startup rolls back previously created runtimes, including on fatal errors. Shutdown
  attempts every runtime's cleanup and clears coordinator lifecycle state even when one fails.
  Nonfatal diagnostic failures cannot interrupt cleanup; fatal errors propagate after cleanup
  attempts, with secondary failures suppressed. This does not guarantee termination when an
  executor or scheduler itself refuses to stop.
- Dedicated heartbeat threads do not reserve JDBC connections. Budget shared pool capacity across
  all queues, request/ORM traffic, nested `REQUIRES_NEW` transactions and the optional PostgreSQL
  listener's held connection. Worker concurrency is not a safe pool-size formula. Measure headroom:
  heartbeat interval, scheduler delay, connection acquisition, locks, query execution and commit
  must together stay comfortably below the lease duration. A shorter acquisition timeout does not
  make an exhausted pool safe. Pool recovery may let a stale handler finish, but cannot restore its
  old lease or make its business effects exactly-once.
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
- Handler and terminal-callback failure boundaries search causes and suppressed errors for JVM-fatal
  failures (`VirtualMachineError` and `ThreadDeath`, including subclasses). A discovered fatal
  escapes as the original Error rather than becoming an ordinary retry
  or callback warning, including when a permanent-failure marker suppresses it. A fatal handler
  leaves RUNNING for lease recovery; a fatal callback preserves the acknowledged DONE/FAILED result.
  Existing capacity/heartbeat cleanup still runs. This neither stops the runtime nor guarantees JVM
  termination, callback replay or exactly-once effects. Do not attach a handled fatal as ordinary
  diagnostic context. Store/scheduler catches and their local cleanup policies are unchanged.
- Production callers should enqueue through `AsyncJobQueueSubmissionService`, not directly through
  the low-level store. The service records enqueue telemetry and triggers the local runtime for jobs
  that are immediately available; future-dated jobs rely on normal polling until their availability
  window opens.
- Immediate durable enqueue uses the store's `enqueueNow` method so JDBC can anchor `available_at`,
  `created_date`, and `updated_date` on database time instead of the producer JVM clock.
- A successful store return is not undone by nonfatal submission counters or wakeup diagnostics.
  Those operations run outside the store-failure catch, and both local/remote wakeup paths remain
  best-effort. Asset-adapter success counters cannot enter task-failure compensation; API and
  parent-fan-out scheduling counters/timing likewise preserve the returned task/child mapping.
  Asset-localization handler counters, generation timing and terminal-repair metrics are also
  best-effort: they cannot discard generated output, manufacture a retry or mask the original
  business/repair exception.
  The shared generation timer keeps the same metric name/tags for Quartz and queue callers.
  Shared PollableTask input-write summary/timer/log diagnostics are also best-effort.
  Nonfatal diagnostic failures do not prevent/reject storage or replace its original failure;
  fatal storage errors escape before further diagnostic work. Input JSON, keys, one-day
  retention, metadata/provider resolution and output methods retain their existing behavior.
  Runtime counters/timers and gauge registration/removal are also best-effort. Nonfatal meter
  failures cannot strand a claimed batch, skip terminal callbacks, consume retry attempts, stop
  polling recovery or prevent capacity/resource cleanup. Unavailable gauges remain absent;
  logs identify telemetry failures without using another metric on that failure path.
  Fatal JVM errors still propagate. This is not an idempotency or unknown-commit guarantee:
  see the [durable admission design](async-job-queue-admission.md) before enabling retries/canaries.

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
  - nonfatal listener counter registration/increment failures cannot break subscriptions,
    reconnects, notification batches or shutdown callbacks. Fatal counter errors still propagate;
    exiting the listener loop clears its running flag, without permitting overlapping threads
  - listener shutdown keeps the thread reference while a stop is still in flight, records stop
    timeouts, and refuses duplicate starts until the previous listener thread exits
  - only the listener thread cleans up JDBC state; stop does not call potentially blocking JDBC
    close on its caller. It removes the `LISTEN` subscription before returning a pooled connection
    and aborts the session if subscription cleanup fails
  - notification-read failures are unwrapped before cleanup. Errors escaping unsubscribe,
    auto-commit restoration and connection return remain attached to the primary failure;
    the reconnect boundary rejects JVM-fatal causes or suppressed cleanup errors
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
- MySQL uses `UTC_TIMESTAMP(6)` and stores UTC civil values in `DATETIME(6)`;
  PostgreSQL uses `clock_timestamp()` and `TIMESTAMPTZ(6)`. JDBC 4.2 binds/reads
  `LocalDateTime` in UTC for MySQL and `OffsetDateTime` for PostgreSQL, avoiding
  implicit JVM/driver/session-zone conversions.
  Absolute schedules, relative retries, lease transitions, monitoring and retention
  share this conversion boundary. The store does not mutate a pooled session's time
  zone or add a clock query per bound value.
- The shared queue timestamp range is `1970-01-01T00:00:00Z` through
  `9999-12-31T00:00:00Z`, inclusive. Pre-epoch timestamps are now rejected before
  mutation, not clamped: the previous year-1000 bound admitted historical dates
  that the embedded HSQL driver silently shifted. Use `enqueueNow` for immediate
  submission or `Instant.EPOCH` for an explicit already-due schedule. This API
  restriction is separate from reconciling previously stored non-UTC rows.
- Do not mix old implicit-timezone workers with this UTC contract. Before any
  upgrade of an already-used queue, stop producers and consumers and establish how
  existing timestamps were written, including queued jobs and retained terminal
  rows. MySQL `DATETIME` contains no original offset, so mixed provenance cannot be
  repaired by guessing an offset or just draining running leases. Reconcile under
  a separately reviewed data migration/cutover plan; no automatic row rewrite is
  implemented. External SQL writers must supply UTC timing columns explicitly:
  V109's legacy `CURRENT_TIMESTAMP` defaults/`ON UPDATE` are session-local, while
  all store inserts and state transitions supply their own timestamps. Migration
  SQL/checksums are unchanged by this code fix.
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
- The time-zone contract suite uses real MySQL/PostgreSQL sessions with different
  time zones, both preparation modes, and independent numeric database clocks.
  Maven defaults test JVMs to UTC. Reproduce the non-UTC matrix from the repo root
  with Docker available (use `-Dmojito.test.timezone=UTC` instead for the UTC control):

  ```sh
  mvn -pl webapp -am -Pno-local-config \
    -Dskip.npm=true -Dskip.installnodenpm=true \
    -Dtest=JdbcAsyncJobStoreTimezoneIntegrationTest,JdbcAsyncJobStoreTest,AsyncJobQueueJdbcDialectTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dmojito.asyncJobQueue.testcontainers=true \
    -Dmojito.test.timezone=America/Los_Angeles test
  ```

  Check Surefire XML for rerun/flaky elements as well as the final result; the
  existing build allows one rerun. These tests do not mutate global JVM timezone.
- Unit tests cover in-memory store semantics, runtime adaptive polling, bounded retries, heartbeats,
  heartbeat false-return, exception, and scheduling-failure containment, bounded persisted error
  summaries, bounded runtime configuration, retry backoff, executor rejection containment,
  explicit requeue budget exhaustion, scheduling, notification wakeup coalescing, runtime poll
  failure recovery, runtime latency timers, state-transition false-return and exception metrics,
  trigger scheduling failure metrics, bounded graceful executor shutdown, active-poll shutdown
  races, claimed-job store contract validation, active-transaction leakage before handler
  invocation, post-terminal callback failure isolation, coordinator startup cleanup, synchronized
  trigger routing, null-safe queue configuration binding, and Spring configuration.
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
  Counter-failure regressions cover registration and increment exceptions, including nonfatal
  assertion errors, and fatal-exit tests require truthful lifecycle gauges and explicit restart.
  A real PostgreSQL test terminates the pooled listener session while its failure counter throws,
  independently observes a replacement LISTEN session, delivers another notification and verifies
  that shutdown returns an unsubscribed connection. This is not a network-partition or failover soak.
  Compound-failure tests cover fatal unsubscribe/statement-close/abort/restore errors during an
  ordinary read failure, fatal reads with failing cleanup, and retained nonfatal diagnostics.
  Failed abort preserves the unsubscribe error graph, including already-suppressed fatal errors;
  throwing the same error object twice must not cause self-suppression to replace that graph.
- Spring configuration tests assert the JDBC store starts and commits explicit transactions even
  without transaction advice, because claim correctness depends on locking and updating in one
  transaction.
- Real Hibernate/JPA tests use the production PollableTask mapping on HSQL and opt-in MySQL/
  PostgreSQL. They prove shared JDBC/JPA connection enlistment, outer-transaction suspension,
  insert rollback and both commit-failure outcomes. A committed row can survive a lost commit
  acknowledgement; this is not durable admission recovery or a full application-schema upgrade test.
- Dialect tests cover MySQL/PostgreSQL `FOR UPDATE SKIP LOCKED` claim SQL, fractional database
  time, and the HSQL embedded fallback.
- Migration tests keep the MySQL and PostgreSQL queue DDL structurally aligned for core columns,
  payload nullability, constraints, and claim indexes, while allowing database-specific id,
  timestamp, and payload text type syntax.
- Opt-in Docker-backed integration tests run the same queue store contract against real MySQL and
  PostgreSQL:
  - `mvn -pl webapp -Dtest=JdbcAsyncJobStoreDatabaseIntegrationTest -Dmojito.asyncJobQueue.testcontainers=true test`
  - GitHub Actions runs this contract in `async-job-queue-real-db-contract` for pushes, PRs,
    scheduled runs, and manual dispatch; local default tests still require explicit opt-in
  - that job also selects the timezone and real JPA/JDBC suites, alongside pool, wakeup and
    process-crash coverage. It explicitly sets `-Dsurefire.rerunFailingTestsCount=0`; the
    configurable ordinary webapp default remains one rerun. A green retry must not hide a
    failed database contract. Workflow configuration is not evidence of a successful hosted run.
  - default unit test runs compile this class but skip container startup unless explicitly enabled
  - the real-database contract validates store transitions, scheduled availability filtering,
    runtime drain/finalize behavior, multi-runtime lease reclaim fencing, and concurrent runtime
    claiming without duplicate execution or stranded queued/running rows
- A separate real-database renewal test gates 24 handlers across four runtimes with the production
  five-thread heartbeat scheduler per runtime. All 20 renewal threads encounter actual row-lock
  waits; after release, independent claim queries cannot steal any job past its captured deadline.
  A database-side snapshot proves every lease is still live with its original token and first
  attempt. Handlers then finish once, and all executors terminate. The existing throughput smoke
  disables heartbeats, so it does not supply this coverage. This bounded local contention test
  does not establish multi-host, pooled-connection or network-failure capacity.
- `JdbcAsyncJobStorePoolIntegrationTest` exhausts a real one-connection Hikari pool after the
  production runtime starts a gated handler. A second scenario terminates its actual database
  session and blocks its disposable account from reconnecting, without retaining a pool permit.
  Both observe a real renewal failure, wait for database-clock expiry, and let an independent
  worker reclaim. After pool recovery, the stale heartbeat and completion are rejected while the
  replacement is still RUNNING with the same worker ID and a new token. Its row is unchanged,
  no stale completion callback runs, and runtime capacity drains before the replacement completes.
  Both MySQL 8.4 and PostgreSQL 16 are covered. This is bounded outage/fencing evidence, not a TCP blackhole, pool-sizing
  recommendation or multi-host capacity soak.
- `AsyncJobQueueProcessCrashIntegrationTest` forcibly terminates a separate worker JVM after an
  observed heartbeat renewal, waits for natural database lease expiry, and verifies replacement
  execution plus active same-worker-ID/old-token rejection on both databases. A committed business
  probe survives each attempt, demonstrating at-least-once effects rather than exactly-once work.
  A second kill after DONE commits but before returning to the runtime leaves its callback absent;
  fresh polls retain DONE without replaying the callback. Required publication needs durable
  reconciliation. These are local worker crashes, not database crashes or network/host soak.
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
  - active transaction detected before handler invocation
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
  Nonfatal replay counter/log failures cannot change the result or prevent the independent remote
  wakeup attempt. Fatal diagnostic causes/suppressed errors still escape. Genuine store or subsequent
  inspection failures remain errors and can occur after replay committed; never infer rollback or
  blindly repeat replay from an exception. This is not a durable replay receipt or audit log.
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
  `batch-size` failed rows per queue per run. It passes a relative age to the store; JDBC samples
  database time and deletes within the same short transaction, avoiding premature deletion from
  a fast application clock. Exact-cutoff rows survive. Custom stores must implement store-clock
  retention explicitly; the default refuses deletion rather than falling back to host time.
  Nonfatal counter/logging failures do not reclassify acknowledged deletes or skip later
  queue/status passes. The failure counter describes store errors, including unknown commit
  outcomes, not proof of rollback. A pass never retries that queue/status immediately.
  JVM-fatal causes/suppressed errors propagate as the original Error; metrics can undercount
  when diagnostics fail. These guards do not change retention bounds or enable cleanup.
  This does not pin rows for unfinished publication/repair or extend associated blob lifetime.
- A broader REST/admin surface can wrap the inspection service later after an explicit replay-audit
  review. The narrow status-count, redacted-summary/detail, and assetlocalize pollable-repair
  endpoints are exposed first because they do not replay work or expose payload.

Review / PR Slicing Plan
- Do not review or merge this branch as one large PR. The durable queue work has multiple
  correctness domains that need separate review passes and targeted verification.
- Suggested stack:
  1. Core queue contract and in-memory runtime: handler/store interfaces, `AsyncJobId`, runtime
     scheduling, adaptive polling, validation, base properties, and in-memory tests.
  2. JDBC durability: MySQL/PostgreSQL DDL, `JdbcAsyncJobStore`, dialect seam, explicit transaction
     boundaries, claim/lease/fencing/retry/replay semantics, migration alignment, and real-database
     contract coverage.
  3. Operational surface: status/ready/expired gauges, inspection service, redacted admin reads,
     retention cleaner, bounded error summaries, and replay/repair service-level primitives.
  4. PostgreSQL wakeup optimization: `LISTEN/NOTIFY` notifier/listener/connections, validation,
     jitter/coalescing/reconnect behavior, metrics, and polling fallback guarantees.
  5. First workload integration: `assetlocalize` routing, pollable-task finalization/repair, canary
     metrics, and staged rollout configuration.
- Each slice must preserve the queue invariants independently:
  - no handler/business work inside queue-store transactions
  - all running-row updates fenced by `(id, worker_id, lease_token)`
  - poison jobs have a persisted attempt budget and terminal failed state
  - polling remains the correctness path when wakeups fail or are unavailable
  - operator/admin responses never expose queue payloads by default
  - MySQL and PostgreSQL behavior stays either aligned or explicitly dialect-scoped
- Before each commit in the stack, run a self-review against transaction boundaries, failure
  containment, migration safety, operational visibility, and focused test coverage. Avoid
  checkpoint/WIP commits; if a change cannot satisfy those gates, keep it uncommitted.

Rollout Plan
1. Keep the implemented queue and assetlocalize integration disabled while the
   [migration plan](async-job-queue-quartz-migration.md) acceptance gates remain open.
2. Start with read-only intent/lag observation. Executable shadow tests require copied inputs and
   isolated side effects; never dual-execute Quartz and the queue against production task/output
   identities, even for a small canary.
3. Before an explicitly approved staging canary, close admission crash recovery, business retry
   safety, payload/repair retention and old-worker compatibility gates. Queue retention being
   disabled does not prevent independent blob cleanup.
4. Validate throughput, queue lag, worker-restart reclaim, retries, client/task parity, and a
   producer-rollback plus consumer-drain rehearsal against the migration plan's measurable gates.
5. Require a separate production rollout decision and expand stable per-job routing gradually.
   Keep Quartz recurring schedules and PollableTask tracking unless separately replaced; each
   accepted request must have one authoritative execution route.

Open Questions
- Which keys should be mandatory in `job_data`, and which should remain queue-specific?
- Should we keep one queue table now and expand by `queue_name`, or create table-per-queue later only if operationally required?

Recommendation
- Build one-table multi-queue-ready design now (`queue_name` + per-queue poller/executor config).
- Consider only `assetlocalize` for the first execution canary after its rollout gates pass.
- Keep code small and explicit; optimize only after production metrics.
