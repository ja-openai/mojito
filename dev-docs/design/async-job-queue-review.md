# Async Job Queue Review

## Scope And Baseline

This is the review ledger for the renewed queue review and Quartz migration work.
Use it to resume substantive work rather than repeating status checks.

- Work in the dedicated queue worktree, not the primary master checkout.
- Branch at review start: `wip/queue-store-refined`, `38dd1e06bb`.
- Four queue commits were preserved and published. At review start they were 207
  commits behind local master `167bf1e818`.
- Local master has unrelated editor changes. Queue changes belong in the queue
  worktree.
- Master owns Flyway versions through V108, including V100. Both queue migrations
  are renumbered to V109, with unchanged SQL and a collision regression. Do not
  treat an unapplied branch migration rename as a database upgrade for an already
  migrated database.
- The queue is disabled by default. The implemented integration is localized
  asset generation. Global Quartz replacement and statistics jobs are proposals.
- Historical passing tests are baseline evidence, not verification of this review.
- Refreshed onto master `167bf1e818` with four queue commits. The rebase preserves
  source-less pull options in the shared Quartz/queue generation service; its ten
  focused asset routing/generation tests pass. Final verification is recorded below.

## Review Workstreams

1. JDBC store: short transactions, database clocks, row-lock waits, lease fencing,
   retry budget, terminal retention versus replay, MySQL/PostgreSQL behavior.
2. Runtime: executor handoff, concurrency, heartbeat scheduling, shutdown and
   failure recovery. Main reviewer owns integration and test execution.
3. Operations: PostgreSQL wakeups, connection/resource ownership, admin data
   exposure, retention and bounded monitoring.
4. Asset integration: enqueue and pollable lifecycle, output publication, lease
   loss, retry side effects, repair, and compatibility with current master.
5. Migration design: inventory today's Quartz and `@Async` paths, choose the first
   workload, define routing, shadowing, drain/rollback and measurable acceptance.

## Acceptance Checklist

- [x] Reproduce and fix material findings with regression tests; independently
  review each patch and record remaining limitations.
- [x] Document the current job inventory and incremental migration design,
  distinguishing implemented support from proposed work.
- [x] Refresh against current master with a preserved backup and reviewable
  commits; resolve Flyway collision across both database dialects.
- [x] Run Spotless and focused queue/admin/assetlocalize tests with
  `-Pno-local-config`; report skips separately.
- [x] Run real MySQL/PostgreSQL migration, concurrency and notification tests,
  plus bounded load/performance verification. Report hardware/configuration and
  latency/throughput; these are local evidence, not production capacity claims.
- [x] Record canary acceptance, rollback and deployment requirements. Actual
  production rollout requires a separate explicit deployment decision.
- [x] Verify independent producer/consumer controls and their drain/rollback tests
  in the combined suite, including both real databases.
- [ ] Resolve admission crash recovery and business-side-effect gates identified
  below; do not equate a passing core suite with rollout approval.

## Review Findings And Evidence

Confirmed defects and targeted regression coverage:

| Defect | Corrective change and regression |
| --- | --- |
| Executor handoff falsely exhausts attempts | A bounded executor handoff buffer preserves the runtime's in-flight cap. `completedHandlerHandoffDoesNotConsumeNextJobsAttemptBudget` holds a returning worker while submitting the next last-attempt job. |
| Lease expiry is tested against time sampled before a lock wait | Lock the ownership row before sampling database time; PostgreSQL uses `clock_timestamp()` rather than transaction-start time. Real MySQL 8.4/PostgreSQL 16 tests hold transitions past expiry and reject heartbeat/done/requeue/failure from the expired owner. |
| Retention can delete a successfully replayed job | Repeat queue/status/age eligibility in the outer deletion. Two-session real-database regressions verify replayed rows survive. |
| Stale localization attempt overwrites winning output | Stage output under an attempt-private UUID, persist the winner in fenced `DONE`, then publish. Regression resumes stale A after winning B and verifies B's output; repair reloads the durable winning pointer. |
| PostgreSQL shutdown can block on JDBC close | The listener thread owns JDBC cleanup; stop only interrupts and joins with a deadline. Blocked-close tests verify bounded stop and no duplicate listener. |
| Pooled listener sessions retain `LISTEN` subscriptions | Explicit `UNLISTEN` before returning the connection, abort on cleanup failure. A real PostgreSQL/Hikari test reuses the same backend PID and verifies cleared subscriptions across restart and auto-commit modes. |
| Spring context close shuts down shared heartbeat scheduling before queue drain | A private queue-owned heartbeat scheduler outlives executor drain without registering another application `TaskScheduler` bean. A real Spring context-close regression covers a buffered job on its last attempt; 144 isolated tests passed and the close regression passed ten repeated runs. |

Pre-refresh verification (not evidence for the rebased tree):

- Clean combined build including all seven fixes and V109: 490 tests, zero
  failures/errors/skips/flakes. This supersedes the earlier combined run below.
- Spotless passed. Focused queue/admin/asset/pollable suite: 485 tests, zero
  failures/errors/skips after one configured rerun. The first PostgreSQL contract
  attempt hit a connection/SSL EOF; record this as one flake, not a clean first pass.
- The two new store concurrency tests cover 14 scenarios on MySQL 8.4/PostgreSQL
  16. Both passed in isolated execution and the combined run.
- Local Docker performance sample: 1,000 jobs, four runtimes, four workers each,
  claim size 32, DriverManager connections, no business work and heartbeats
  disabled: PostgreSQL 418 jobs/s; MySQL 304 jobs/s. This is a smoke baseline,
  not a production capacity claim, heartbeat contention test, or Quartz comparison.

Final refreshed-tree verification, 2026-09-08:

- `mvn spotless:apply` passed. A clean webapp reactor build with
  `-Pno-local-config`, focused queue/admin/asset/pollable tests, real-database tests
  and performance tests enabled passed **491 tests, zero failures/errors/skips/flakes**.
  Frontend npm work was skipped because this change touches no frontend source;
  this is not a claim that the full application test suite ran.
- The clean build removes stale V100 queue resources. The collision test finds
  exactly one V109 queue migration in each dialect and unique default Flyway
  versions alongside master's V100 through V108. SQL bytes are unchanged by renaming.
  Real-database tests apply the queue DDL directly; they are not a full historical
  application-schema upgrade rehearsal. That remains a pre-deployment gate.
- Hardware: arm64 Apple M4 Max, 16 logical CPUs, 128 GiB host RAM; Docker Desktop
  engine 29.7.2 reported 7,933 MiB VM memory. JDK 21.0.8, MySQL 8.4/PostgreSQL 16.
  With the smoke configuration above, PostgreSQL drained 1,000 jobs in 1,365 ms
  (733 jobs/s, 361 polls); MySQL in 2,448 ms (408 jobs/s, 643 polls). Both had zero
  poll failures. Shared-host variability is substantial; no per-job p99/SLO or
  production throughput is established by these numbers.
- Existing AspectJ weaving and deprecated test-API warnings remain. Queue store
  transactions use explicit boundaries; these passing tests do not certify removal
  of AspectJ from the rest of the application.
- Rebase preservation check: core queue source/tests and the output-fencing code
  match the tested pre-refresh safety branch. Only the shared generation path was
  adapted to current-master options. Primary-master tracked changes were unchanged.

## Producer/Consumer Controls Follow-Up

- Added restart-applied `asset-localize.producer-enabled` and
  `queues.<queueName>.consumer-enabled`, both default `true` behind the existing
  default-off feature flags. Producer rollback routes new API and parent-fan-out
  work to Quartz while preserving handler/output/repair beans. Direct queue adapter
  calls reject before any task, blob, or queue write; neither route catches an
  ambiguous enqueue to fall back to the other backend.
- Consumer-disabled queues allocate no runtime, executor, or heartbeat scheduler.
  Other queues remain runnable, remote submissions remain possible, and local
  wakeup metrics distinguish `consumerDisabled` from missing runtime. Status
  collection, retention, and repair keep their own controls.
- Independent review caught two configuration hazards: producer-only in-memory
  queues cannot be drained remotely (now rejected), and a producer-only PostgreSQL
  node needlessly reserved a listener connection (now skipped if no registered
  consumer is enabled). Notification publishing and pooled-session cleanup remain
  covered. Old binaries ignore these switches; all producers need updated binaries.
- Tests cover configuration defaults/binding, no-write rejection, both producers'
  Quartz rollback, no ambiguous-error fallback, retained beans, no disabled-worker
  allocation, mixed queues, and PostgreSQL listener/notifier independence. A shared
  two-coordinator test submits ready and delayed work without local consumption,
  then drains it on another coordinator; the contract invokes it on both real
  database dialects as well as the in-memory test double. This is not a multi-host
  deployment or staging soak.
- Initial combined verification found a scheduler-mock generic compilation error,
  which was corrected. The next run hung in the MySQL startup handshake before
  queue SQL ran; a thread dump confirmed an unbounded socket read despite a ready
  server. That test JVM was stopped. Test JDBC connections now have five-second
  connect and thirty-second socket timeouts; production datasource settings are
  unchanged.
- Final follow-up verification on 2026-09-08: Spotless and a clean focused reactor
  run with `-Pno-local-config`, database and performance tests enabled passed
  **510 tests, zero failures/errors/skips/flakes**. The XML reports contain no
  failure or rerun elements. This supersedes the interrupted pre-timeout run; it
  does not erase the compile fix or startup hang described above.
- With the same smoke workload/hardware described above and bounded test JDBC
  connections, PostgreSQL drained 1,000 jobs in 1,574 ms (635 jobs/s, 376 polls),
  MySQL in 2,435 ms (411 jobs/s, 620 polls), with no poll or claim failures. These
  are local smoke measurements, not capacity/SLO claims or a Quartz comparison.
  Production SQL and V109 migrations did not change in this follow-up. Existing
  AspectJ/deprecated API warnings remain; frontend and full application tests were
  not run. No production database, routing, or deployment was changed.

## Admission Outcome Follow-Up

- Reproduced a false-rejection bug with real Micrometer meter-type collisions:
  after successful enqueue, the asset submission success counter threw into the
  compensation catch and failed the accepted job's PollableTask. Failure/cleanup
  counters also replaced the original exception or skipped compensation. Three
  targeted regressions failed before the fix and pass afterward.
- The core submission layer had equivalent post-store-return hazards. Its expanded
  pre-fix suite reproduced 15 failures. Store failure catches now end at the store
  call; nonfatal metric/logging failures preserve the original outcome and both
  wakeup paths. Genuine store errors and fatal JVM errors still propagate. No
  implicit retry or backend fallback was introduced.
- Asset submission success telemetry is outside task compensation. The API's
  scheduling counters and parent's counters/duration timer are best-effort, so
  accepted task IDs and child mappings survive telemetry failure. Business/lazy
  entity reads remain outside the telemetry guard. A fatal telemetry error still
  propagates without compensating an accepted task.
- An integration-style test executes an accepted job through the in-memory store,
  real submission adapter and runtime, with broken submission/wakeup counters,
  and verifies successful task/output finalization without false task failure.
  Isolated compilation and 71 focused tests passed before the combined reactor run.
- Added a concrete [admission protocol design](async-job-queue-admission.md).
  This is not implemented: task/blob/queue atomicity, primary-key resolution of
  unknown commits, scoped client keys and durable parent manifests remain open.
  The current adapter still compensates an arbitrary enqueue exception; if the
  database committed before that exception, later successful task finish does not
  clear its error, and repair reports already-finished. Do not enable the feature
  until this uncertainty is resolved. Legacy Quartz instrumentation is not covered
  by the narrow queue submission correction.
- The first combined run passed 538 tests with one PostgreSQL initial-connection
  flake; the next passed with two. Both failed before migration SQL at the SSL
  handshake, then passed on configured reruns. The attempted command-line retry
  override did not take effect because the POM hard-codes one retry. Neither run
  is clean first-attempt evidence.
- A separate read-only review of the cached Testcontainers implementation confirmed
  PostgreSQL readiness waits on log messages, unlike JDBC connection readiness.
  Migration setup now uses `container.createConnection` with a ten-second retry
  budget and the existing five-second connect/thirty-second socket settings.
  The budget is not a hard total deadline: an in-flight driver attempt can outlast
  it. Only initial connection acquisition retries; SQL, close failures, assertions
  and later queue operations do not. Two harness tests enforce this boundary.
  Production connections, queue SQL, migrations and global test retry settings
  are unchanged.
- The new connection-failure assertion initially failed compilation because
  `SQLException` matches both Throwable and Iterable assertion overloads; use
  JUnit's identity assertion instead. No production change was needed.
- Final verification on 2026-09-08: Spotless and a clean focused reactor run with
  `-Pno-local-config`, MySQL/PostgreSQL and performance tests enabled passed
  **540 tests, zero failures/errors/skips/flakes**. Final XML reports contain no
  failure or rerun elements. This supersedes the earlier runs, without treating
  container connection readiness retries as whole-test retries or hiding those
  earlier failures. Thirty-three local admission-design links resolve.
- Same local smoke workload/hardware: PostgreSQL drained 1,000 jobs in 1,491 ms
  (671 jobs/s, 359 polls), MySQL in 2,423 ms (413 jobs/s, 620 polls), with zero
  poll/claim failures. These are not production throughput or latency guarantees.
  Existing AspectJ/deprecated API warnings remain; frontend, full-application,
  historical schema-upgrade and unknown-commit acceptance tests were not run.
  V109 remains the single queue migration per dialect; no SQL or deployment changed.

## Canonical Identity Follow-Up

- Added the pure, unwired `AssetLocalizeAdmissionRequest` direct-request snapshot.
  It validates key/ID/string shape, rejects body/path disagreement, freezes all
  generation inputs, NFC-normalizes content only, and produces a fixed v1 UTF-8
  envelope and SHA-256. Request keys remain separate from fingerprints. Returned
  bytes and execution DTOs cannot mutate the frozen request; diagnostics omit keys
  and content. No producer, consumer, persistence or HTTP behavior changed.
- Independent call-path review caught a semantic issue before verification:
  null source-less branch entries select the unnamed branch and must survive,
  while null filter-option entries are invalid. Both source-less fields remain
  independent because a nonempty branch list enables augmentation even when its
  boolean is false. The corrected implementation passed independent static review.
- This is not an HTTP decoder or durable acceptance protocol. Trusted scope,
  authorization, asset/repository/locale membership, strict raw-JSON validation,
  reservations, execution-blob versioning and commit recovery remain prerequisites.
  No parallel-request or partial-fan-out implementation is claimed by this stage.
- All 28 new model tests passed in isolated Java 21/JUnit execution and the
  combined reactor run. They pin independent ASCII/Unicode SHA-256 vectors,
  exact escaping/defaults, every generation field, null branch semantics,
  malformed surrogate/key/ID validation, and input/output mutation isolation.
  A DTO-field inventory guard requires future request-shape changes to be reviewed.
- The first combined run completed 568 tests with one PostgreSQL initial-connection
  flake, passing on the POM's configured rerun. It failed before migration SQL at
  the SSL handshake. Two MySQL container startup handshakes also exhausted their
  bounded waits before internal container restart succeeded. The captured JVM
  stack was in the driver's timed initial-handshake read, not a queue row lock.
  Do not count this as clean first-attempt evidence or conflate container-startup
  retries with whole-test reruns. No production or test-harness code changed here.
- A fresh clean reactor run with `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`,
  `-Pno-local-config`, database and performance tests enabled again completed
  **568 tests, zero final failures/errors/skips, one flake**. The PostgreSQL
  performance test failed at the initial SSL handshake before queue SQL, then
  passed on the configured rerun; its XML retains that `flakyError`. Explicit
  IPv4 did not eliminate the fault, so hostname routing is not an established
  explanation or fix. No third retry-until-green run was used. Initial database
  connection reliability remains an open verification limitation; the 28 new
  model tests passed on their first attempt in both combined runs.
- Same local smoke workload/hardware, in the successful performance retry:
  PostgreSQL drained 1,000 jobs in 1,497 ms (668 jobs/s, 367 polls), MySQL in
  2,699 ms (371 jobs/s, 643 polls), with zero poll/claim failures. These samples
  do not erase startup failures or establish production throughput/SLOs.
  Spotless and diff whitespace checks passed. Existing AspectJ/deprecated API
  warnings remain. Frontend, full-application, historical schema upgrade and
  unknown-commit acceptance tests were not run. V109 and SQL are unchanged;
  no production database, routing, or deployment changed.

## Strict Decoding Follow-Up

- Refreshed the clean queue worktree onto `origin/master` at `7fcc341457`, retaining
  four identical queue patches as verified by `git range-diff`. The new base changes
  review-project creation, not queue code or migrations. Primary-master edits were
  not touched. V109 remains the next version after master's V108 in both dialects.
- Added the pure, unwired `AssetLocalizeAdmissionRequestDecoder`. It validates a
  caller-bounded UTF-8 body before DTO binding, rejects unknown/duplicate/output
  fields, malformed encoding/JSON and type coercion, and preserves the identity
  model's defaults, optional nulls and unnamed-branch behavior. A private parser
  enforces explicit shape limits, and public validation exceptions discard raw
  parser details. No endpoint, persistence or stored-input format changed.
- This does not choose an HTTP byte-budget policy, bound transport allocation,
  authorize the supplied scope or resolve an uncertain commit. Reservations,
  atomic task-plus-queue acceptance, execution-envelope readers and recovery
  remain open; do not enable the queue based on decoder coverage.
- Independent source review found no decoder correctness issues. A separate
  database-startup investigation clarified that the earlier PostgreSQL EOF occurs
  reading the one-byte `SSLRequest` response, before TLS/authentication or migration
  SQL. Testcontainers 1.21.4 already waits for two ready-log matches and retries
  initial connection opening; the temporary-server explanation is not established.
  Two fixed disposable probes received EOF before final readiness but all ten
  post-readiness SSLRequest probes and four JDBC checks succeeded. They did not
  reproduce the later failure. No added sleep, SSL disabling or retry layer is
  justified by that evidence. A future same-container failure diagnosis should
  compare bounded in-container TCP access with host access before disposal.
- Nineteen new decoder tests pass independently; the combined pure model/decoder
  run passes all 47 tests after Java 21 compilation. Coverage includes the fixed
  identity golden, all request fields and enums, duplicate escaped names, raw
  UTF-8/JSON/type rejection, null/default/list behavior, exact byte boundaries,
  defensive snapshots and exception-graph redaction. An initial local JUnit
  command ran before compilation finished and could not load the new test class;
  waiting for compilation resolved that orchestration error without source edits.
- Final focused reactor verification on 2026-09-08 against base `7fcc341457`:
  Spotless and a clean `-Pno-local-config` run with database and performance tests
  enabled passed **587 tests, zero failures/errors/skips/flakes**. XML reports
  contain no failure/rerun elements and the log contains no container-startup
  failure. Existing startup/readiness retries remain; no harness behavior changed.
  This passing run does not establish a fix for the earlier intermittent fault.
- Same local smoke workload/hardware: PostgreSQL drained 1,000 jobs in 1,423 ms
  (703 jobs/s, 361 polls); MySQL in 2,512 ms (398 jobs/s, 610 polls), with zero
  poll/claim failures. These are local samples, not capacity or latency guarantees.
  Existing AspectJ/deprecated API warnings remain. Frontend, full-application,
  historical schema-upgrade and unknown-commit acceptance tests were not run.
  SQL/V109 and default-off routing are unchanged; no deployment was performed.
- A standalone JUnit Platform/Vintage launcher then selected the prior failing
  lease-lock-wait and performance methods once each with UTC JVM time: exactly
  two tests found/started/passed, zero skipped/aborted/failed, in 52 seconds.
  This bypasses Surefire's configured rerun, not Testcontainers' existing
  connection-opening or startup attempts. No container-startup failure was logged.
  The launcher/probes are local diagnostic artifacts, not changes to repository
  test retry policy. Mockito's existing dynamic-agent warning appeared. Keep the
  earlier intermittent connection fault open rather than claiming this fixed it.

## Remaining Rollout Gates

Keep the feature disabled until the migration plan's acceptance gates are met.
In particular: reconcile crashes between PollableTask creation, input storage and
enqueue; make pull-run lineage
retry-safe; drain older canonical-output writers; align the one-day minimum blob
lifetime with repair deadlines; and define domain-aware replay rather than
reusing an already-failed PollableTask. Queue fencing does not make arbitrary
business mutations exactly-once.

Load-test lease renewal under database contention and verify the production
MySQL/JDBC/session timezone configuration. The isolated non-UTC harness exposed
timestamp readback differences; use database-clock expiry assertions rather than
treating JVM time as database time. Full application PostgreSQL compatibility is
separate from the queue's PostgreSQL contract tests.

## Next Run

Build durable reservations and explicit task-plus-queue acceptance with real
JPA/JDBC commit-fault tests, using the pure v1 decoder and identity model as input
boundaries. First verify deployment/migration history before adding admission
DDL: V109 may only be extended if unapplied; otherwise require a forward migration.
Follow the admission design, keeping proposed HTTP/retention choices distinct
from approved rollout decisions.
Keep the initial database handshake flake visible and diagnose the connection
failure before increasing timeouts or retries; IPv4 alone did not resolve it.
Include API retry and partial parent fan-out; neither should recreate accepted
children. Keep queue and business transaction boundaries separate,
and update this ledger and the live tracker with fresh evidence. Pause the review
heartbeat when this checklist is complete;
if an external prerequisite blocks progress, identify it without repeating
unchanged status every five minutes.
