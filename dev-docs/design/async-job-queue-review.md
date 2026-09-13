# Async Job Queue Review

## Current Readiness

**The full branch is not production-ready or merge-ready against local master.**
This snapshot is source/test evidence as of 2026-09-13 UTC, not deployment approval.
A separately reviewed inactive foundation can land before workload adoption;
future AI Review support, full Quartz replacement and an OSS release are not
prerequisites for that narrower landing. The full branch still includes discovered
Flyway migrations and shared Quartz-path changes. Historical milestones below do
not close its current gates.

- The queue worktree has forty-six local commits (four original chunks plus CI,
  failure-boundary, policy and verification follow-ups) over `7fcc341457`. Local master at this
  checkpoint is `ed4bc31eda`, which
  has 38 commits not in the queue branch and owns migrations through V112, including
  `V109__AI_Review_Request_Usage.sql`. The queue branch's two V109 scripts are
  collision-free only on its older base. A passing branch-local collision test
  is not evidence that merging into current master is safe.
- The [fresh Flyway contract](#fresh-flyway-artifact-contract-2026-09-13-utc)
  passes on native MySQL 8.4.11 and PostgreSQL 16.15: the isolated queue artifact
  installs once, validates, preserves data/history on rerun, and rejects checksum
  drift. The [current-branch MySQL application chain](#mysql-application-flyway-chain-2026-09-13-utc)
  also passes fresh installation and no-op rerun, including its two Java migrations.
  Neither proves historical adoption or installation on the selected new master base.
  Flyway 11.7.2 warns that MySQL 8.4 exceeds its tested support range;
  settle the supported Flyway/database baseline before production certification.
- The original admin chunk is not independently buildable: its configuration test
  references repair classes introduced only by the following asset-adapter chunk.
  The [foundation history audit](#foundation-history-dependency-2026-09-12-utc)
  records the exact dependency and current fixture split. Passing tests at HEAD
  do not certify intermediate commits or make the first two commits a standalone landing.
- Recent changes have not been pushed, merged, deployed or enabled. Unrelated
  primary-master edits are outside this work. Remote-tracking refs are cached, not
  fresh origin evidence. The initial published branch is not the latest reviewed
  implementation.
- The latest broad queue/admin/asset/parent selection passed 1,135 tests with 32
  opt-in skips across 82 suites, no failures or reruns. Native MySQL 8.0.43 passed
  nine contract groups, including the [claim-lock correction](#bounded-mysql-claim-locks-2026-09-12-utc)
  and case/padding/legacy-encoding identity matrix. The [retention diagnostic](#native-mysql-retention-plan-2026-09-12-utc)
  adds one held-open DELETE/peer-replay plan, not another JUnit result. These are
  scoped proofs, not a full application run, target-version certification or capacity.
  The subsequent [maintained retention regression](#bounded-mysql-retention-regression-2026-09-12-utc)
  passes all 20 status/backlog/batch scenarios on native MySQL 8.4.11, protecting
  bounded held locks and concurrent peer replay, not optimizer scan cost or capacity.
  The [native timezone matrix](#native-mysql-timezone-contracts-2026-09-12-utc)
  separately passes 14 tests in each isolated UTC and America/Los_Angeles JVM;
  these new-write checks do not establish historical timestamp provenance.
  The two [native pool/session-loss contracts](#native-mysql-pool-and-session-loss-2026-09-12-utc)
  also pass, preserving the replacement lease after real pool starvation and
  worker-session termination. They are not network-partition or pool-sizing proof.
  Two [native worker-JVM crash cases](#native-mysql-worker-jvm-crashes-2026-09-12-utc)
  pass as well: natural-expiry recovery repeats business effects, while committed
  DONE remains terminal even when its best-effort callback was never delivered.
- A separate [native MySQL 8.4.11 run](#native-mysql-84-store-contracts-2026-09-12-utc)
  now passes the same nine store/identity/claim-lock contract groups on macOS ARM64.
  The held-open locking regression includes all ten backlog/batch scenarios, and
  the 1,000-job smoke records zero poll failures. This closes that scoped native
  version gap, not Docker/Linux CI or capacity. Subsequent JPA, fault-recovery and
  [timezone/adapter/lean-consumer runs](#native-mysql-84-compatibility-lanes-2026-09-12-utc)
  cover those separate native 8.4 lanes without certifying the full hosted job.
- The earlier local disk-capacity blocker has cleared: the crash-test MySQL server
  reported ENOSPC during shutdown, after its two tests passed. Removing its private
  datadir initially left about 398 MiB available on the local data volume; a later
  check recovered to 1.7 GiB, still below the safety floor. A separate,
  user-requested cleanup of clean monorepo worktrees restored about 40 GiB before
  verification resumed. Keep checking the conservative 5 GiB free-space floor
  before database/build/stress work. This does not authorize automatic cleanup of
  unrelated files or caches, or close the remaining database and rollout gates.
- The rebuilt ordinary JAR passed 17 tests in the HSQL JPA-consumer lane; the latest separate
  clean lean run passed 11. The expanded JPA lane skips 19 real-database cases without
  opt-in (12 host contracts and seven public-consumer cases); the lean lane skips seven.
  A [native consumer run](#native-mysql-ordinary-jar-consumer-2026-09-12-utc)
  now verifies two existing public execution/maintenance contracts plus two JAR
  boundary tests on MySQL 8.0.43, without application classes. The same four checks
  now also pass on [native MySQL 8.4.11](#native-mysql-84-compatibility-lanes-2026-09-12-utc)
  after rebuilding and verifying the ordinary JAR. These separate runs
  are not an additive coverage total. A separate [native PostgreSQL 16.15 run](#native-postgresql-ordinary-jar-consumer-2026-09-12-utc)
  passes five existing execution/maintenance/wakeup contracts plus two JAR-boundary
  tests, including successful hints and caller commit/rollback isolation. Complete
  MySQL 8.4/PostgreSQL 16 CI runs remain unverified;
  Docker's local socket is absent. Asynchronous handler completion is still proposed,
  not implemented.
- The [independent JAR's JPA-host suite](#independent-jar-jpa-database-lanes-2026-09-12-utc)
  now includes both real databases in CI. Its six PostgreSQL 16.15 cases plus two
  JAR-boundary tests pass natively with no skips. The same eight checks also pass
  on [native MySQL 8.0.43](#native-mysql-independent-jpa-host-2026-09-12-utc), after
  correcting private-fixture startup/authentication. The same eight checks now also
  pass on [native MySQL 8.4.11](#native-mysql-84-jpa-transactions-2026-09-12-utc), with a
  freshly rebuilt JAR and separate HSQL/JPA and clean lean controls. These public
  independent-enqueue tests do not implement atomic admission, certify the full
  Docker/Linux matrix or certify Mojito's business integrations.
- The application JPA/JDBC fixture now passes all 35 existing tests on native
  MySQL 8.0.43 and [PostgreSQL 16.15](#native-postgresql-jpajdbc-contracts-2026-09-12-utc)
  with Hibernate 6.6.49.Final, separately from its fresh 35-test HSQL control.
  The [native MySQL JPA evidence](#native-mysql-jpajdbc-contracts-2026-09-12-utc)
  covers actual commit/rollback, provisional IDs, consumer visibility and injected
  lost acknowledgements, not durable reservations, HTTP recovery or actual network
  failure. It uses application mappings, not the standalone-JAR consumer.
  All 35 also pass on [native MySQL 8.4.11](#native-mysql-84-jpa-transactions-2026-09-12-utc)
  with the same assertions and JUnit lifecycle, separately from the independent host.
- The [native PostgreSQL asset-adapter run](#native-postgresql-asset-adapter-2026-09-12-utc)
  and [native MySQL 8.0.43 run](#native-mysql-asset-adapter-2026-09-12-utc) each pass
  the existing database method's 15 internal retry/publication scenarios as one
  JUnit test, with no skips or reruns. Application state and blobs remain in
  private HSQL, separate from queue rows. The same MySQL method now also passes
  on [8.4.11](#native-mysql-84-compatibility-lanes-2026-09-12-utc), alongside the
  timezone suite's 14 cases in each UTC and America/Los_Angeles JVM.
  These do not prove atomic admission,
  full-application database compatibility or queue-owned blob lifetime.
- The two [native PostgreSQL listener contracts](#native-postgresql-listener-sessions-2026-09-12-utc)
  pass real pooled-session cleanup and reconnect after backend termination despite
  injected metrics failure. This is session-loss evidence, not network partition,
  database failover or shared-pool capacity proof.
  The [recovery logging correction](#listener-recovery-logging-2026-09-12-utc)
  additionally preserves reconnect and orderly thread exit despite ordinary logger
  failures; its new fault tests use mocked JDBC, not a fresh database-outage run.
  The additional [listener restart contract](#postgresql-listener-database-restart-2026-09-12-utc)
  passes natively: the same listener resubscribes after server restart, delivers a
  new hint and returns an unsubscribed pooled session. Its [combined extension](#combined-worker-and-listener-restart-2026-09-12-utc)
  now runs a real worker across the same outage and completes new work after
  listener shutdown through polling alone. This is a bounded composition check,
  not sustained worker/listener soak, shared-pool sizing or replica failover.
- Eight [native PostgreSQL store contract groups](#native-postgresql-store-contracts-2026-09-12-utc)
  pass, including expiry during row-lock waits, concurrent retention updates and
  renewal contention. The 1,000-job performance smoke is a local diagnostic, not
  production capacity. No MySQL half, historical upgrade or full CI lane ran in
  this selection.
- The [native PostgreSQL timezone matrix](#native-postgresql-timezone-contracts-2026-09-12-utc)
  passes 14 existing tests in each of separate UTC and America/Los_Angeles JVMs,
  covering both configured preparation modes and cross-session timestamp semantics.
  New-write consistency does not establish historical timestamp provenance or a
  safe mixed-version migration.
- Both [native PostgreSQL pool/session-loss contracts](#native-postgresql-pool-and-session-loss-2026-09-12-utc)
  pass: starvation and real worker-session termination preserve the replacement
  lease after recovery, reject stale completion and release runtime capacity.
  Blocked reauthentication is not network-partition or shared-pool sizing proof.
- Both [native PostgreSQL worker-JVM crash contracts](#native-postgresql-worker-jvm-crashes-2026-09-12-utc)
  pass: running work reclaims only after natural expiry with a fresh token, while
  committed DONE remains terminal without replaying its missed callback. Repeated
  business probes demonstrate at-least-once effects, not exactly-once execution.
- The shared [MySQL/PostgreSQL database-restart contract](#mysql-and-postgresql-restart-matrix-2026-09-12-utc)
  passes on native MySQL 8.0.43 after SIGKILL and PostgreSQL 16.15 after immediate
  shutdown, with durability settings enabled. Committed rows survive, uncommitted
  input disappears, the original pool reconnects and expired owners remain fenced.
  The [runtime extension](#runtime-database-restart-recovery-2026-09-12-utc) also
  passes on those native versions: the same coordinator rejects stale completion,
  reclaims naturally expired work and processes a new job without restart or an
  external wakeup. Both restart methods now also pass on
  [native MySQL 8.4.11](#native-mysql-84-fault-recovery-2026-09-12-utc), together with
  two pool/session-loss and two worker-JVM crash cases. The Docker kill/start paths,
  listener-aware failover and sustained network blackholes remain unverified.
- The new [lost TCP commit-reply contracts](#lost-tcp-commit-replies-2026-09-12-utc)
  pass both enqueue and completion on native MySQL 8.4.11 and PostgreSQL 16.15.
  The database commits while the caller blocks, real JDBC socket timeouts surface,
  and the original pool replaces the failed connection without duplicating the row
  or reclaiming terminal DONE. These are bounded one-way transport failures, not
  durable request-identity recovery, runtime/listener partition soak or failover.
  The [lost-claim runtime extension](#runtime-lost-claim-recovery-2026-09-13-utc)
  also passes on both native versions: the unacknowledged lease is never dispatched,
  then the same runtime reclaims after natural expiry with a fresh token and one
  handler invocation. Full Docker/Linux and sustained-partition gates remain open.
  The [lost-heartbeat extension](#runtime-lost-heartbeat-recovery-2026-09-13-utc)
  also passes on both native versions: a committed renewal with a lost reply does
  not interrupt the live handler or discard its lease, and the same attempt renews
  and completes after transport recovery. The [past-expiry extension](#live-handler-past-transport-lease-expiry-2026-09-13-utc)
  additionally rejects both stale renewal and completion after a peer reclaims.
  All five transport methods now pass per native engine, separately from the
  skipped opt-in Maven control.
- The [required-report gate](#required-database-report-gate-2026-09-13-utc) now
  rejects missing suites/database groups, underfilled test matrices, unexpected
  skips and retry artifacts after each clean CI database lane. Only the named
  opt-in throughput benchmark may skip. Validator fixtures and focused Java tests
  pass; enforcing reports is not evidence that the full hosted lane has passed.
- Default-off limits routing, not all effects of merging: Flyway still discovers
  the application migration, and shared task/blob/generation changes also affect
  Quartz callers. The intended first adapter is untracked, single-locale asset
  localization. Parallel queue fan-out requires separate opt-in; statistics and
  global Quartz removal are not implemented replacements. The proposed
  [asynchronous handler extension](async-job-queue-asynchronous-handlers.md) reuses
  this engine but remains separate from the disabled foundation and asset adapter.

| Gate | Implemented evidence | Remaining prerequisite and exit condition |
| --- | --- | --- |
| Merge base and schema adoption | One queue migration per dialect on the branch's existing base; collision regression; native isolated Flyway install/rerun/checksum contracts for both databases; current-branch MySQL application-classpath installation and no-op rerun. | Split the premature repair-test dependency from the admin chunk and verify each resulting foundation commit; preserve current core fixes. Confirm applied queue migration history and timestamp provenance; refresh onto the selected current master, choose unapplied rename versus forward upgrade, and pass full-application fresh-install plus historical-upgrade rehearsal without rewriting applied checksums. Resolve the MySQL/Flyway support warning. |
| Durable direct admission | Frozen request identity, strict body and canonical stored-identity readers, explicit JPA/JDBC enlistment primitive, unknown-enqueue containment and fault fixtures. | Decide the [API/authorization/lifetime contract](async-job-queue-admission.md#decisions-versus-defaults) and schema path; implement persisted reservations, atomic task/queue acceptance, verified inputs and primary-key recovery. Same-key crash/retry tests must return one accepted task/job, not merely avoid false compensation. |
| Parent fan-out recovery | Separate default-off fan-out flag, resolution preflight and reproduced partial-admission failures. | Build on durable admission with frozen manifests and stable child slots. Approve duplicate-tag/size policy; crash and concurrent resume must retain exactly N child identities and reconstruct the same output map. |
| Business publication and replay | Attempt-private output plus fenced DONE selection; all non-null pull-run tracking excluded; terminal-task replay containment. | Approve the [lineage authority contract](async-job-queue-lineage.md#exact-scope-and-owner-decisions), retire old writers, implement business-generation fencing and fresh linked replay. Queue-row leases alone do not fence shared caches, branch state or lineage writes. |
| Input/output lifetime | Database retention corrections and regression demonstrating expired winning output cannot be repaired. | Agree result/repair/replay horizons and backend lifecycle exclusions; implement queue-owned pinned keys and reference-aware cleanup across fallback copies. Accepted work and unresolved publication must survive cleanup races. |
| Database and rollout proof | Scoped native MySQL 8.0/8.4 and PostgreSQL 16 store, timezone, JPA, adapter, runtime and JAR contracts; HSQL controls, stricter CI selection and bounded canary/rollback design. | Run the full required Docker/Linux matrix with zero reruns and no required skips, then approved pool/network/multi-host soak and workload canary/rollback gates. Native fixture passes, smoke throughput and a passing workflow parse are not capacity or hosted-CI proof. |
| Standalone OSS module | Independent ordinary-JAR compilation and external Spring consumer contracts; no Mojito business imports in the core. | Agree extraction, artifact/API/configuration naming and schema ownership; make Mojito a real module consumer and establish upgrade, compatibility and release provenance. See [finite extraction gates](async-job-queue-library.md#finite-extraction-gates). Do not equate the probe with a released library. |

The main next implementation is durable admission, followed by parent recovery and
publication/lifetime ownership. Missing applied history blocks choosing DDL, not
independent, concrete core fixes or test work. Owner choices in the linked designs
remain proposals rather than implicit permission to wire new behavior. Do not
replace these finite gates with an unbounded instrumentation or synthetic-test
sweep. Update this snapshot when evidence or decisions change, not merely when a
heartbeat runs.

Scoped master update, 2026-09-10: `003992323e` moves new interactive AI review off
Quartz to direct, capacity-limited provider futures, not to this queue. Its
[inventory supplement](async-job-queue-quartz-migration.md#interactive-ai-review-separate-execution-policy)
separates persisted task/result recovery, no automatic provider resend, legacy
Quartz drain adapters and unchanged batch AI jobs. This docs-only source review
does not refresh the queue base or close any readiness gate above.

Scoped CI correction, 2026-09-12: the opted-in real-database job omitted
`AssetLocalizeAsyncJobOutputRetryIntegrationTest`, while the ordinary job skips
that fixture's MySQL/PostgreSQL methods. The dedicated selector now includes
the adapter and retains all six core suites, explicit container opt-in and zero
Surefire reruns. A new ordinary JUnit workflow contract parses the job/step and
requires those settings together, rejecting duplicate opt-in/rerun arguments.
It failed against the previous selector specifically because the adapter class
was missing (`/tmp/queue-ci-adapter-selection-red-20260912.log`).

The focused workflow/migration/asset-fixture run with `-Pno-local-config`, an
isolated HSQL application database and zero reruns selected 19 tests: 17 passed,
two Docker-only adapter cases skipped, no failures/errors or flaky/rerun entries
(`/tmp/queue-ci-adapter-selection-verified-20260912.log`). Root formatting passed.
Pre-commit verification repeated the same 19-test selection with identical outcomes
(`/tmp/queue-ci-selection-commit-tests-20260912.log`); formatting passed again.
This is source wiring and local application-fixture proof, not fresh MySQL 8.4
or PostgreSQL 16 adapter execution. Require both database methods without skips
in hosted CI before closing that evidence gap. No production code, migrations,
flags, deployment or rollout decision changed; the earlier disk restriction has
cleared, not the production-readiness gates.

Scoped status-sampling correction, 2026-09-12: failure logging or failure-counter
registration/increment could abort the metrics pass before later queues were
sampled. Wrapped or suppressed JVM-fatal errors were also treated as ordinary
sampling failures. Six new regressions failed against the prior implementation
(`/tmp/queue-status-metrics-isolation-red-20260912.log`). The reporter now gives
logging and counter reporting independent, non-recursive best-effort attempts,
and uses the shared cause/suppression-aware fatal classifier for sampling and
diagnostics. Fatal errors stop the pass and retain their identity; ordinary
diagnostic failures do not prevent later queues from being sampled. No new metrics,
retries, SQL or scheduling changes were introduced. Existing gauges can still be
stale after failed reads and are not an atomic cross-query snapshot.

The focused reporter/configuration/retention/inspection/fatal-classifier selection
passed 105 tests with zero skips, failures or reruns using `-Pno-local-config`
(`/tmp/queue-status-metrics-isolation-verified-20260912.log`); root formatting passed.
Existing ThreadDeath deprecation and application AspectJ weaving warnings remain.
This is local core/configuration evidence, not new real-database, packaged-consumer
or production monitoring proof; all readiness gates above remain open.

Scoped submission-boundary correction, 2026-09-12: generic submission checked only
top-level JVM-fatal errors. A wrapped/suppressed fatal from post-enqueue diagnostics,
clock or wakeup could be swallowed, while a wrapped store fatal entered ordinary
failure reporting. All seven catch sites now use the shared cause/suppression-aware
classifier. Existing ordinary failure behavior is unchanged; fatal propagation
neither retries nor compensates an acknowledged enqueue and is not evidence of
rollback. Three new tests and three expanded tests reproduced the prior behavior
(`/tmp/queue-submission-fatal-boundary-red-20260912.log`) across both enqueue APIs,
metric registration/increment, logging, clock and local/remote wakeups.

The focused submission/configuration/classifier/asset-adapter selection, including
the 35 existing HSQL JPA/JDBC transaction contracts, passed 132 tests with zero
failures, skips or reruns using `-Pno-local-config`
(`/tmp/queue-submission-fatal-boundary-verified-20260912.log`); root formatting passed.
Existing deprecation and application weaving warnings remain. New error-graph
cases use controlled collaborators, not actual JVM exhaustion or database/network
failures. No schema, public API, routing, module extraction or durable-admission
protocol changed; target-database and unknown-commit recovery gates remain open.

Scoped repair correction, 2026-09-10: a request-bound Hibernate persistence context
could retain an open PollableTask after another transaction finished it. Both DONE
and FAILED repair then returned `repaired` instead of preserving the terminal task;
the two regressions failed before the patch. Repair now calls an explicitly owned
`REQUIRES_NEW`, read-only task refresh. Legacy getters and worker callbacks are
unchanged. Six new HSQL tests cover both stale terminal guards, successful DONE and
FAILED repair with request-bound persistence, a missing task, and suspension of an
active caller without flushing or discarding its changes. The expanded selection
passed 308 tests across 27 suites with two opt-in database skips and no rerun/flaky
entries (`/tmp/queue-repair-fresh-state-expanded.log`); formatting passed. Existing
deprecation/weaving and HSQL fixture warnings remain. This is application/JPA
evidence, not fresh MySQL/PostgreSQL or full HTTP-interceptor execution proof.
The guard is still a read-time check: a concurrent finish after the refresh can
race publication, so business fencing and the readiness gates above remain open.

A follow-up real-HSQL deletion regression covers refresh failure, not just a
mocked lookup exception: after caching a task in the request EntityManager, a
committed JDBC deletion makes repair fail before output publication or task
recreation. The queue row is preserved, the request EntityManager/flush mode is
restored, and no active transaction, synchronization or JDBC binding leaks.
This exercises an inactive request context, not failure while suspending an active
caller. The focused repair/task selection passed 60 tests across six suites with
no skips or rerun/flaky entries (`/tmp/queue-repair-refresh-failure-focused.log`);
formatting passed. No production code, SQL migration or readiness gate changed.

Scoped runtime correction, 2026-09-11: a worker paused before handler entry could
invoke business work even after a heartbeat rejected its lease and a peer had
reclaimed the row. A gated real-executor/in-memory-store regression reproduced
that unnecessary stale invocation (`/tmp/queue-handler-known-loss-red.log`). The
runtime now checks its existing definitive-loss signal before calling the handler
and skips known-stale work without SQL, transitions or callbacks. Cleanup still
cancels renewal and returns capacity, including when cancellation fails. Six new
cases also preserve success, unknown renewal outcome, no-tick and disabled-heartbeat
behavior. The peer retains its exact stored snapshot and another job can run.
The final focused runtime/coordinator/drain/JPA selection passed 383 tests in 15
suites, without skips or rerun/flaky entries
(`/tmp/queue-handler-known-loss-verified.log`). An intermediate run caught a new
test-oracle mistake comparing a claim-only flag with persisted state; the test now
compares stored snapshots. The independently rebuilt JAR passed 17 JPA-consumer
tests and 11 lean-consumer tests, each with six real-DB skips. This check cannot
prevent lease loss after the check, interrupt running handlers or provide business
fencing; no schema, admission or rollout gate is closed.

Scoped SQL identity correction, 2026-09-11: MySQL's inherited string collation
could equate distinct Java queue names, affecting inspection, claim selection,
replay and retention. Claim readback can roll back a mismatched claim, but does not
protect counts or maintenance. Independently, a real-HSQL regression reproduced
renewal by a worker ID with an added trailing space. That initial run had one
genuine identity failure and two invalid-zero-retention fixture errors, corrected
before verification (`/tmp/queue-exact-identity-red.log`). This is not a fresh
MySQL reproduction; its risk follows from the source predicates and
[documented MySQL comparison/conversion semantics](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html).

The dialect now compares MySQL identities as unpadded binary strings after
converting both operands to UTF-8. All 14 queue-name sites, including both
retention scopes, and all five ownership pairs use it. ASCII queue names retain
their ordinary equality as an index prefilter. Owner queries already use the
primary-key ID; they omit ordinary owner equality to avoid coercing Unicode
parameters into a legacy column charset. HSQL adds length equality to reject
space padding under its reviewed default collation. PostgreSQL predicates,
transaction boundaries, migration files and stored data are unchanged.

Four shared identity contracts cover in-memory/HSQL queue counts/minima/listing,
one-row claims and expired reclaim behind foreign-case work, DONE/FAILED retention
with the foreign row oldest and limit one, both replay APIs and all five owner
mutations. Full-row comparisons preserve rejected/foreign state. The same
contracts join both opt-in production-database lanes; a new MySQL-only matrix
covers case-insensitive PAD/NO-PAD and binary PAD collations, plus a latin1 worker
column over a checked utf8mb4 connection. Case, accents, decomposition and trailing
spaces are distinguished, with exact-owner success controls. SQL-shape tests
preserve the index prefilter and explicit conversions. Independent source review
caught the legacy-encoding coercion issue before the final verification.

The final queue/admin/asset selection passed 1,084 tests with 30 opt-in skips and
no failures/errors/rerun entries (`/tmp/queue-exact-identity-verified.log`). Spotless
and `git diff --check` passed. The ordinary 29-source JAR was rebuilt independently;
its JPA and lean consumer results are recorded in the library note. Existing
ThreadDeath deprecation, weaving and fixture warnings remain. No MySQL/PostgreSQL
container ran on this pass: verify the matrix and bounded claim/retention EXPLAIN
plans when Docker is available. The index prefilter is not proof of an unchanged
plan or disjoint lock ranges for case-equivalent queues. Encoding cannot recover
already-lossy identities; old binaries still need draining before relying on the
guard. No admission, schema-adoption, business-fencing or rollout gate is closed.

## Lost TCP Commit Replies (2026-09-12 UTC)

The initial `JdbcAsyncJobStoreNetworkIntegrationTest` added two opt-in contracts
per database to the required zero-rerun CI selector, protected by the workflow contract test.
A loopback byte relay passes the existing driver protocol, including TLS, without
terminating or inspecting it. A connection wrapper arms reply discard immediately
before invoking the real JDBC `commit()`; it does not throw an injected exception.
Client requests continue to reach the database while server replies are discarded.

For enqueue and fenced completion, a direct independent connection must observe
the committed row while the caller is still waiting. The call must then fail with
an actual `SocketTimeoutException`, with discarded server bytes and exactly one
commit invocation recorded. Restoring replies must let the same Hikari pool use
a newly accepted physical connection. Enqueued work remains one claimable job;
committed DONE retains its payload, cannot be claimed again and rejects a stale
completion. All rows and attempt counts are asserted; executors, relay sockets and
pool borrowers must drain. No production queue code, schema or routing flag changed.

Fresh verification used the maintained methods and assertions, substituting only
container metadata/lifecycle for uniquely named schemas on private native servers:

- MySQL 8.4.11: two tests passed in 11.111 seconds, zero ignored tests, assumptions
  or failures. TLS was required, with `innodb_flush_log_at_trx_commit=1`,
  `sync_binlog=1` and doublewrite enabled. The self-signed server certificate was
  not hostname-authenticated.
- PostgreSQL 16.15: two tests passed in 11.466 seconds, zero ignored tests,
  assumptions or failures. TLS used `verify-full` with the private test certificate;
  fsync, synchronous commit and full-page writes were enabled.
- Focused Maven control: 47 passed and four expected database opt-in skips across
  three suites, no failures, errors or automatic reruns. Root
  `mvn -Pno-local-config spotless:apply` passed. Required Docker/Linux CI remains
  unverified; neither skipped control methods nor native adapters certify it.

The first PostgreSQL attempt correctly failed both observer assertions: the test
queried uppercase statuses while the schema stores lowercase strings. MySQL's
case-insensitive comparison had hidden this fixture error. The observer now uses
`AsyncJobStatus.getDatabaseValue()`; the same pool-replacement assertion was also
strengthened to require a new relay session after fault entry. Both dialects were
explicitly rerun against corrected compiled sources with unchanged outcome checks.
The failed and corrected logs, native wrapper sources and count-guarded runners
remain in `/private/tmp/queue-mysql84-network.0Xruvm/`; the private servers were
cleanly stopped and their datadirs removed after verification.

This closes a finite lost-reply execution gap, not durable caller recovery: enqueue
still throws without returning the committed ID, and the test oracle is not an
application recovery API. Persisted admission identity, unknown-commit reconciliation,
callback delivery, lease-loss/runtime behavior during sustained partitions, shared
pool headroom, multi-host soak and replica failover remain separate gates.

### Runtime Lost-Claim Recovery (2026-09-13 UTC)

The network fixture now has a third contract per dialect,
`lostClaimReplyIsNotDispatchedAndRuntimeRecoversAfterLeaseExpiry`. A real coordinator
constructs its executor, poll loop and heartbeat scheduler. The same byte relay
discards the first claim's commit reply; the direct observer sees RUNNING with
attempt count one before JDBC times out. There must be no handler invocation,
callback or in-flight dispatch, and another worker cannot claim the live lease.

After replies resume, recovery uses the same coordinator and pool without a restart
or explicit wakeup. It waits for the ten-second lease to expire naturally: no row
timestamp is backdated and no lease is force-released. The dispatched record must
have attempt count two, the same worker ID, a different lease token, the reclaim
flag, and a database update time at or after the old expiry. Exactly one handler
and one acknowledged DONE callback execute, the terminal payload is preserved,
claim failure is metered and executor/pool capacity drains. A committed but
unacknowledged claim consumes an attempt even though its handler never ran.

All three maintained methods passed in separate native TLS runs: MySQL 8.4.11
reported three tests in 21.304 seconds; PostgreSQL 16.15 reported three in 21.638
seconds. Neither run had failures, ignored tests or assumptions. Native container
substitution, isolated schemas and TLS/durability settings match the preceding
transport proof; the complete Docker job has not run. Focused Maven control passed
111 tests with six expected opt-in skips across three suites, no failures/errors
or Surefire reruns. Root `mvn -Pno-local-config spotless:apply` passed.

An initial compile failure used the pinned Micrometer registry as AutoCloseable;
the fixture now follows the existing `meters::close` cleanup pattern. That build
failure, corrected control output, both native logs and guarded wrapper sources
remain in `/private/tmp/queue-network-claim.Hgqe5o/`. Both private servers were
cleanly stopped and their datadirs removed. No production source, migration,
route flag or primary-master file changed. This bounded recovery case is not
sustained partitions, already-running business-write fencing, durable caller
admission, exactly-once effects, listener failover or workload capacity proof.

## Runtime Lost Heartbeat Recovery (2026-09-13 UTC)

`JdbcAsyncJobStoreNetworkIntegrationTest.lostHeartbeatReplyKeepsLiveHandlerAndRecoversRenewal`
extends the maintained loopback relay fixture to a running handler's heartbeat.
A separate connection reads the committed lease immediately before the faulted
commit, so a previous successful renewal cannot satisfy the oracle. The server
then extends that lease while the JDBC caller still waits for its discarded reply;
the real driver eventually reports `SocketTimeoutException`, not an injected error.

During uncertainty, the handler remains in flight and is not interrupted, no DONE
callback fires and a peer cannot reclaim the live lease. Restoring replies lets
the same runtime and pool reconnect and renew again with the original worker,
lease token and attempt count. Releasing the handler then produces exactly one
invocation/callback, terminal DONE with its returned payload, and drained executor
and pool capacity. The test uses a 30-second lease to accommodate the failed socket
and a possible replacement-handshake timeout; it does not backdate expiry or restart
the runtime. A bounded pre-expiry recovery is not sustained partition, failover,
shared-pool sizing, business-write fencing or exactly-once effects.

Independent review identified a possible cleanup race in both runtime transport
tests: handler/executor drainage and heartbeat cancellation do not join a renewal
already inside JDBC. Both tests now await zero active pool connections and zero
connection waiters after coordinator/poll-scheduler shutdown, before closing the
pool. The 15-second deadline allows pending acquisition and socket timeouts to
settle but still fails a persistent leak; it is not a production shutdown change.
This was a code-review finding, not a reproduced transport-recovery failure.

All four maintained transport methods passed again on native MySQL 8.4.11 in 32.454
seconds and PostgreSQL 16.15 in 32.793 seconds, with zero failures, ignored tests
or assumptions. Each run includes enqueue, completion, claim and heartbeat, using
private TLS connections, guarded engine/datadir identity and durability settings.
Only container orchestration is substituted; the relay, driver, pool, store and
coordinator are real. MySQL TLS is encrypted but does not verify the self-signed
server identity; PostgreSQL uses verify-full. Neither is a Docker/Linux CI result.

Focused Maven reactor control passed 112 tests with eight expected opt-in skips
across three suites and no errors, failures or reruns. All 11 report-gate fixture
tests also pass after raising the required network matrix to four cases per
database (191 application cases including the optional throughput benchmark).
Root `mvn -Pno-local-config spotless:apply` passed. Sources and logs remain in
`/private/tmp/queue-network-drain.fnZQqV/` (the earlier renewal run is retained in
`/private/tmp/queue-network-renewal.BBAVen/`); both private servers were cleanly
stopped and only their datadirs removed. No queue production code, migration,
route flag, ordinary-JAR artifact or primary-master file changed.

## Live Handler Past Transport Lease Expiry (2026-09-13 UTC)

`JdbcAsyncJobStoreNetworkIntegrationTest.lostHeartbeatReplyPastExpiryRejectsStaleHandlerResult`
keeps server replies blocked after an independently observed heartbeat commit,
until that renewed lease expires naturally. A peer then reclaims with the same
worker ID but a new token and attempt count two. After replies resume, the
original runtime's heartbeat returns false without mutating the replacement row.
The original handler remains live until the test releases it; its normal
`markDone` false return, absent callback and unchanged winning row prove stale
completion rejection. The peer can then complete with its own distinct payload.

Independent review strengthened the completion oracle: a transition-failure
counter alone also counts JDBC exceptions and could falsely claim fencing. The
observer now requires the real store call to return false after its transaction;
it neither substitutes outcomes nor injects driver failures. Both runtime paths
retain bounded executor/pool drainage. This is a finite one-way transport outage,
not sustained multi-host soak, business-side fencing, durable caller admission or
an exactly-once execution guarantee.

The final five-method matrix passed on native MySQL 8.4.11 in 43.607 seconds and
PostgreSQL 16.15 in 44.163 seconds, with no failures, ignored tests or assumptions.
The separate focused Maven reactor passed 112 tests with ten expected database
opt-in skips, no failures/errors or automatic reruns. All 11 report-validator
tests pass with a five-case floor per engine, raising the required application
matrix to 193 cases including the optional throughput benchmark. Root
`mvn -Pno-local-config spotless:apply` passed.

Sources and logs remain in `/private/tmp/queue-network-expiry.RK3wpA/`; use the
`*-final.log` runs for the stronger completion assertion. The earlier MySQL run
also passed before independent review strengthened that assertion. Container
orchestration alone is replaced with isolated native databases; real drivers,
TLS, the loopback relay, pool, runtime and store are retained. Both private servers
were cleanly stopped and only their datadirs removed. No production code,
migration, routing flag, ordinary-JAR artifact or primary-master file changed;
the Docker/Linux and sustained-partition gates remain open.

## Fresh Flyway Artifact Contract (2026-09-13 UTC)

Two maintained methods in `JdbcAsyncJobStoreDatabaseIntegrationTest` now run
Flyway itself, rather than only ScriptUtils, on fresh MySQL/PostgreSQL schemas.
Each copies exactly the existing V109 queue resource into a disposable location;
no application migration or source checksum is changed. The PostgreSQL Flyway
provider is test-scoped, not added to production runtime wiring.

The contract requires one successful versioned history entry with a checksum,
successful validation, and actual queue enqueue/claim/completion on that schema.
A new Flyway instance must apply zero migrations and preserve the entire history
and terminal job. Changing only the disposable SQL copy must produce an explicit
checksum validation failure and make `migrate` throw without changing history or
queue data. Restoring the original bytes must validate without `repair`, `clean`
or baselining. Temporary files use the normal JUnit rule cleanup.

Both exact JUnit methods pass with their ordinary rule lifecycle on native
MySQL 8.4.11 (**1 test, 0.506 s**) and PostgreSQL 16.15 (**1 test, 0.564 s**),
with zero failures, ignored tests, assumptions or retries. The private runner
substitutes only Testcontainers orchestration/connection metadata; Flyway 11.7.2,
its matching database providers, JDBC and SQL/history assertions are real.
Exact private datadir/version/TLS guards precede fresh schema creation.
Logs and the native runner are in `/private/tmp/queue-flyway-fresh.Rg8bJu/`.
Both servers were stopped and only their owned datadirs removed.

The initial focused Maven control selects 24 tests: eight pass and 16 opted-in
database or performance cases skip. The final formatted control adds the HSQL
JDBC-store suite: **53 pass, 16 opt-in skips, zero failures/errors or reruns**
across four suites. All 11 Python report-validator tests and root
`mvn -Pno-local-config spotless:apply` pass. Independent scoped review found no
introduced issue. Existing ThreadDeath/weaving warnings remain, separate from
the MySQL/Flyway compatibility warning below. The required CI store-suite floor rises from 18 to
20, and the application matrix to 195 cases (194 required plus optional throughput).
These native cases are not full Docker/Linux CI, application-wide Flyway scanning,
historical upgrade/adoption, timestamp provenance, or resolution of the V109
collision on current master. MySQL emitted Flyway's tested-version warning
(latest tested version reported as 8.1); the pass does not certify vendor support.
MySQL TLS is encrypted with a self-signed server certificate, not identity-verified;
PostgreSQL uses verify-full. No production queue code or migration SQL changed.

## MySQL Application Flyway Chain (2026-09-13 UTC)

The maintained `mysqlApplicationFlywayChainInstallsQueueAndRerunsWithoutChanges`
contract now scans `classpath:db/migration` on a fresh disposable MySQL schema.
It requires all **108 migrations: 106 SQL and the Java migrations V9 and V56**,
successful history states, SQL checksums, and V109 as the final queue migration.
The Java migrations inherit null checksums; the contract does not invent checksums
or bypass classpath discovery. A filesystem-only SQL rehearsal missed those two
migrations; independent review caught that test defect, and the exact JUnit method
reproduced the 106-versus-108 failure before the assertions were corrected.

After migration and validation, the test enqueues, claims and completes a real
queue job. A fresh Flyway instance must execute zero migrations and preserve the
entire history, seeded locale rows and terminal job. Cleaning and baselining are
disabled. No repair runs, no source SQL changes, and no existing database is touched.

The final formatted JUnit method passed on native MySQL 8.4.11 in **5.012 s**, with one test
and zero failures, ignored cases or assumptions. Only Testcontainers lifecycle
and connection metadata were replaced; Flyway 11.7.2, JDBC, both Java migrations
and all SQL were real. The runner verified the private datadir, server version
and encrypted connection before allocating a fresh schema, then ran the test as
a non-root user restricted to test schemas. MySQL used required TLS with a
self-signed certificate, not identity verification. Evidence, including the
failing control, is retained in `/private/tmp/queue-app-flyway.moqJXi/`. The private
server shut down cleanly; its listener/PID disappeared before only its owned
datadir was removed. More than 26 GiB remained available throughout verification.

Focused Maven controls select 70 tests: **53 pass and 17 opt-in database/performance
cases skip**, with no failures or reruns. All 11 Python report-validator fixtures
pass, as does root `mvn -Pno-local-config spotless:apply`. The final independent
scoped review found no material issue. Existing ThreadDeath and weaving warnings
remain. The CI store-suite floor rises to 21; the application lane now requires
195 non-skipped cases plus its sole optional throughput benchmark (196 total).

This proves only fresh installation from this branch's existing migration set.
It does not exercise Spring Boot's `FlyWayConfig` strategy, legacy upgrades or
backfills on populated data, the current-master merge, PostgreSQL application
installation, Docker/Linux CI or capacity. Existing historical SQL deprecation
warnings and Flyway's MySQL tested-version warning remain. Applied-history,
timestamp provenance, V109 collision and workload-adoption gates stay open.

## Required Database Report Gate (2026-09-13 UTC)

The real-database workflow previously opted in and disabled reruns, but Maven
could still return success for an assumption-skipped suite or a parameter source
that omitted a database. Its workflow test checked command selection, not actual
execution reports. The new standard-library Python gate reads the required
Surefire reports immediately after each application, lean-JAR and JPA-JAR lane.

It requires each suite and its current per-parameter count floor, consistent
counters, unique attributed cases and no failure/error/flaky/rerun elements.
Missing PostgreSQL cannot be masked by additional HSQL tests. Zero-test
`BeforeClass` assumption reports fail. The sole allowed skip is
`JdbcAsyncJobStoreDatabaseIntegrationTest.runtimePerformanceSmokeRunsAgainstRealDatabases`;
throughput remains opt-in, not a required correctness or capacity claim.
The current matrix floors are 196 application cases (195 required plus that
benchmark), 18 lean-consumer cases and 36 JPA-consumer cases. Floors are explicit
maintenance contracts, not semantic validation of method bodies or DB identity.

Application verification now starts with `clean test`, like both independent
consumers, so old reports cannot satisfy the gate. A Java workflow regression
requires each validator immediately after its matching clean, opted-in Maven
invocation. The gate cannot independently establish freshness for arbitrary
manually supplied reports. No queue production code, schema or routing changed.

Verification: all 11 Python fixture tests passed, including all three valid lane
shapes and missing/empty/underfilled/duplicate/misattributed/failed/retried cases.
The CLI correctly returned failure for the existing lean-consumer control report
because its PostgreSQL case had skipped. This is a negative gate check, not a new
database run. Focused Maven reactor verification passed 112 tests across the
runtime and workflow-contract suites, with zero failures, errors, skips or reruns.
Root `mvn -Pno-local-config spotless:apply` passed.

The initial webapp-only Maven attempt failed before tests: locally cached shared
Mojito artifacts no longer matched this older branch (`JsonValidator` and
`CheckForDoNotTranslateStep`). Re-running with `-am` used the branch's reactor
dependencies without installing over the shared cache or changing primary master.
The initial failure and successful reactor log are retained in
`/private/tmp/queue-ci-report-gate.cmrbxk/`. The ambient Ruff launcher could not
write its external tool directory; Python formatting was reviewed manually,
without installing tools or changing that directory. The full Docker/Linux job,
historical migrations, durable admission and rollout gates remain unverified.

## Foundation History Dependency (2026-09-12 UTC)

A Git-object audit found a concrete intermediate-commit dependency, not a defect
in the current queue runtime. `07b92c144e` adds
`AsyncJobQueueAdminWSConfigurationTest`, which imports
`AssetLocalizeAsyncJobRepairService` and registers/asserts
`AssetLocalizeAsyncJobRepairWS`. Neither class exists anywhere in that commit's
Java tree; both are first added by the next commit, `123a7eba50`. The admin chunk
therefore cannot compile its tests independently. This is source-tree evidence,
not a Maven run at the historical revision.

Before a foundation-only landing, keep generic admin activation tests in the
admin chunk and place repair activation tests/fixtures in the asset-adapter chunk.
The later `f6018ee944` HTTP test originally covered both real controllers; its
generic and repair cases must also belong to their respective chunks.
Do not silently include repair or its business services to satisfy those tests.
Rebuild from current reviewed content, including subsequent core corrections,
rather than cherry-picking only the original core/admin commits. Run formatting,
compilation and the relevant tests at each resulting stack boundary; a green
final tree alone is insufficient. Keep migration adoption as its separate gate.

No history, branch reference, source code, migration or primary-master file was
rewritten during this audit. This records a required correction, not a completed
restack or an independently verified foundation. At the audit revision, the configuration
and authorization selection passed nine tests with no failures/errors/skips or
automatic reruns (`/private/tmp/queue-foundation-history-head-tests-20260912.log`);
root Spotless passed. These checks are separate from the historical diagnosis.

### Current Fixture Separation

The current tree now separates `AsyncJobQueueAdminWSConfigurationTest` and
`AsyncJobQueueAdminAuthorizationTest` from
`AssetLocalizeAsyncJobRepairWSConfigurationTest` and
`AssetLocalizeAsyncJobRepairAuthorizationTest`. Generic tests import no repair
controller or business service; each fixture registers only its own controller
and mocked service. Both flag matrices, anonymous/non-admin denial, admin reads
with payload redaction, one repair invocation and unsupported-method checks are
preserved. The increase from nine combined tests to sixteen separated tests is
fixture partitioning, not seven new behavior guarantees.

Root Spotless and the focused admin/inspection/web/MCP selection pass **82 tests
across nine suites**, with no failures, errors, skips, flakes or automatic reruns
(`/private/tmp/queue-admin-test-boundaries-tests-20260912.log`). Select both
`AsyncJobQueueAdmin*` and `AssetLocalizeAsyncJobRepair*` when checking these
boundaries. Runtime, security policy, migrations and flag defaults are unchanged.
This prepares source boundaries only: historical commits have not been rewritten
or individually rebuilt, and the foundation history/base/schema gate stays open.

## Admin HTTP Authorization (2026-09-12 UTC)

Originally added five `AsyncJobQueueAdminAuthorizationTest` cases using Spring
MockMvc, the production `WebSecurityConfig.setAuthorizationRequests` rules and both real queue
controllers. Only inspection and repair services are mocked. The existing direct
controller/configuration tests did not exercise request authorization. The
[current fixture separation](#current-fixture-separation) preserves these contracts
in controller-specific tests; the counts below describe the earlier combined run.

- Anonymous callers and each USER, TRANSLATOR and PM role are denied at all five
  inspection GET routes and the repair POST, with no service invocation.
- ADMIN reaches all five reads and one repair invocation. Serialized list/detail
  responses omit payload and preview fields and a payload sentinel, while retaining
  the intentional admin diagnostic and payload-length fields.
- ADMIN still has no raw replay or delete HTTP operation. Unsupported methods do
  not reach either service. Separate existing configuration tests retain default-off
  and both-flags-required controller activation coverage.

Mutation check: temporarily removing only the queue ADMIN matcher made the
non-admin regression fail with HTTP 200 instead of 403 (one test, one failure,
no errors/skips/reruns; `/private/tmp/queue-admin-authorization-mutation-20260912.log`).
The production file was restored byte-for-byte before final verification; no
authorization behavior changed. The fixture disables CSRF so it cannot mask a
missing role guard and injects principals rather than testing login providers.
Anonymous HTTP 403 here is the fixture's entry-point behavior, not a promise about
production login/redirect configuration.

Root `mvn -Pno-local-config spotless:apply` passes. The focused queue admin,
inspection and existing web/MCP authorization selection passes **75 tests across
seven suites**, with no failures/errors/skips or automatic reruns
(`/private/tmp/queue-admin-authorization-verified-20260912.log`). This is HTTP
authorization/serialization regression coverage, not a new vulnerability fix,
authentication-provider/CSRF proof, database execution or staging verification.
The generic engine and its ordinary JAR are unchanged. No migration, queue flag,
production policy, primary-master file or rollout gate was changed.

## Listener Recovery Logging (2026-09-12 UTC)

An ordinary logger failure in the connection-failure catch or interrupted-reconnect
warning could terminate the listener instead of reconnecting. The stopped-connection
debug log could also escape shutdown. Durable polling remains the fallback; this is
loss of optional wakeup availability, not loss of queue rows. The three recovery
logs now use one guarded helper. Nonfatal failures cannot interrupt recovery, and
the existing cause/suppressed-graph classifier propagates the original JVM-fatal
Error. SQL, subscription cleanup, timing, metrics and lifecycle policy are unchanged.
This is not global notifier/listener logging containment or automatic fatal restart.

All eight regression cases failed before the fix with zero reruns, then passed.
They cover RuntimeException/AssertionError recovery and stopped-thread paths plus
wrapped VirtualMachineError and suppressed ThreadDeath subclasses. Each verifies
the faulted log call; owned threads are joined and the original logger restored.
The earlier registry AutoCloseable compilation failure was fixture setup, not a
product reproduction. Final focused verification passed 126 tests with five real-DB
opt-in skips across ten suites, no failures/errors/flaky/rerun elements. Logs:
`/tmp/queue-listener-logging-reproduction-20260912.log` and
`/tmp/queue-listener-logging-final-20260912.log`. Root Spotless passed.

The plain-javac engine was rebuilt and its separate ordinary-JAR consumer passed
11 tests with seven DB skips; the clean JPA-host consumer passed 17 with 19 DB skips.
Neither consumer had failures or reruns. Engine JAR SHA-256:
`d10a292547070c50e773672fee6d98bfad8034757d44791fdddc7a783aafcda1`.
Logs are `/tmp/queue-listener-logging-engine-verified-20260912.log`,
`/tmp/queue-listener-logging-consumer-20260912.log` and
`/tmp/queue-listener-logging-jpa-consumer-20260912.log`. The first install was blocked
by local-cache sandbox permissions; the approved local-only install succeeded.
No application dependency, schema, adapter, routing flag or remote ref changed.
Existing weaving/JDK/build warnings and npm audit findings remain. This closes a
reproduced control-flow defect, not admission, migration adoption, target-version
database CI or staging/network-failover gates.

## PostgreSQL Listener Database Restart (2026-09-12 UTC)

`JdbcPostgresAsyncJobQueueWakeupListenerDatabaseIntegrationTest` initially added one bounded
server-restart case to its already-required CI suite. The same listener and
one-connection Hikari pool span abrupt shutdown. An observed listener failure and
stopped server establish the outage; the listener stays running. After restart,
the test checks the new postmaster start time and independently observes committed
LISTEN in `pg_stat_activity`, allowing backend PID reuse across server incarnations.
A new notification from the independent notifier connection must reach the mock
coordinator. On stop, the replacement backend returns to the pool with auto-commit
restored, no subscriptions and no borrowed connections or waiting threads.

The maintained case passed once on native PostgreSQL 16.15 in 1.971 seconds, with
zero failures, ignored tests, assumption skips or reruns. The wrapper substitutes
only container lifecycle/metadata; listener, notifier, JDBC and pool operations
remain real. Immediate shutdown and WAL recovery on the same private cluster stand
in for Docker SIGKILL/start, with certificate/hostname verification retained.
Artifacts are `/tmp/queue-listener-restart.4X7u8o/native-listener-restart.log`,
`pg-server.log`, `NativeListenerRestartVerification.java` and `restart_probe.rb`.
The server shut down cleanly and loopback port 59407 closed. More than 27 GiB stayed
free; no unrelated files were removed. Expected disconnect/cleanup warnings remain
visible rather than being hidden by retries.

The Maven control selected 79 tests across seven suites: 76 passed and the three
opt-in listener database cases explicitly skipped, with zero reruns. Its log is
`/tmp/queue-listener-restart-control-20260912.log`. No production class or migration
changed; this does not require a new generic-library JAR. The final control repeats
76 passes and three skips in `/tmp/queue-listener-restart-final-control-20260912.log`;
all seven Surefire XML reports record reruns disabled and no failure/error/flaky/rerun
elements. Root Spotless passed in `/tmp/queue-listener-restart-spotless-final-20260912.log`.
Existing npm audit findings (two moderate, one high) remain. This is a scoped
same-database listener-restart proof, not Docker execution, replica promotion,
network blackholes, combined worker/listener soak, staging alerts or durable
notification delivery. Queue rows and polling remain the source of recovery.

### Combined Worker And Listener Restart (2026-09-12 UTC)

The same maintained restart method now uses a real JDBC store, coordinator,
scheduler, executor and handler instead of a mocked coordinator. A delegating
Mockito spy observes the two delivered hints without replacing coordinator
behavior. A dedicated one-slot listener pool and separate two-slot worker pool
remain alive across the outage; this is not a shared-pool capacity test.

The first job completes before shutdown. Both the listener and worker must record
an outage failure before the database restarts. After observing the new server's
committed LISTEN and reconnecting the original worker pool, a second job completes
and a new hint reaches the same coordinator. The first job remains DONE with its
original output and attempt count. Stopping the listener then leaves the worker
to complete a third job by polling, without a hint or coordinator restart. All
three rows and callbacks have the expected identities, output and attempt one.
Handler/executor capacity drains before shutdown, runtime gauges deregister on
stop, and both pools have no active borrowers/waiters. The replacement listener
session still passes the existing exact-PID, auto-commit and UNLISTEN checks.
Job completion may race a hint through polling: this checks both paths working
together, not notification latency or proof that a hint caused a particular claim.

The initial native run caught a new test-fixture error after processing completed:
it required the inflight gauge after normal coordinator shutdown had removed it.
The corrected assertion checks drained capacity before shutdown and deregistration
afterward; no production code changed and the failed run remains recorded.
The corrected maintained case passed once in **2.402 seconds** on PostgreSQL 16.15,
with no ignored tests, assumptions or automatic reruns. The temporary wrapper
substitutes only container lifecycle/metadata with private native immediate
shutdown/restart and verified TLS. WAL recovery and clean final shutdown were
observed; loopback port 59408 closed. Artifacts are in
`/tmp/queue-combined-restart.DGevG0/`: `native-combined-restart.log` (initial failure),
`native-combined-restart-fixed.log`, `native-combined-restart-final.log`, `pg-server.log`,
`NativeCombinedRestartVerification.java` and `restart_probe.rb`.

The Maven control selected 79 tests: 76 passed, three opt-in database tests skipped,
no failures/errors/reruns (`/tmp/queue-combined-restart-fixed-control-20260912.log`).
Final verification repeats that selection in
`/tmp/queue-combined-restart-final-control-20260912.log`; all seven XML reports
confirm zero failures/errors/flaky results and automatic reruns disabled. The final
formatted test, including explicit callback-output assertions, passes natively in
**2.415 seconds** with no skips or automatic reruns. Root Spotless passes in
`/tmp/queue-combined-restart-spotless-final-20260912.log`. Existing npm audit findings
(two moderate, one high) and expected disconnect diagnostics remain visible.
This test-only extension does not rebuild the unchanged engine JAR, certify Docker
SIGKILL, replica promotion, network blackholes, multi-host soak or durable
notifications, and does not close admission or business-publication gates.

## Runtime Database Restart Recovery (2026-09-12 UTC)

The shared restart suite now adds a second method per dialect, running the real
coordinator, executor, heartbeat scheduler and JDBC store with one Hikari connection
and one handler slot. A gated handler spans abrupt database shutdown. The test
observes a real heartbeat SQL failure from a call started after confirmed shutdown,
keeps the server down for a full lease without modifying timestamps, and restarts
the same database. The original pool reconnects; renewal rejects the expired token
while the original handler still owns its local capacity slot.

Releasing that handler makes its stale DONE update return false, without changing
the retained input or invoking a completion callback. The same coordinator then
reclaims attempt two with a fresh token, persists its recovered output and delivers
only the replacement callback. A separately enqueued job completes through polling,
with no coordinator restart, manual claim or external hint. Assertions also cover
one rejected-DONE metric, three handler invocations and released executor/pool
capacity. The observing store delegates every SQL operation and propagates errors;
it does not synthesize outcomes. This is queue-result and callback fencing, not
exactly-once business effects or interruption of a handler that already started.

Both maintained runtime methods passed together once in 10.873 seconds on native
MySQL 8.0.43 (verified owned-process SIGKILL, exit 137) and PostgreSQL 16.15
(immediate shutdown and WAL recovery), with zero failures, ignored tests, assumption
skips or reruns. The private lifecycle wrapper leaves production runtime, SQL,
transactions and assertions unchanged. Durability checks remain enabled; MySQL
requires TLS without verifying the self-signed CA/hostname, while PostgreSQL uses
certificate and hostname verification. Both servers shut down cleanly, their
45198/59406 listeners closed, and more than 27 GiB remained free.

Artifacts are `/tmp/queue-runtime-restart.jpGsht/native-runtime-restart.log`,
`mysql-server.log`, `pg-server.log`, `NativeRuntimeRestartVerification.java` and
`restart_probe.rb`. Sandbox-only initialization first failed before queue SQL:
MySQL exited on signal 11 and PostgreSQL could not allocate shared memory. Approved
initialization of the same private fixtures succeeded; these setup failures are
not hidden queue-test retries. The separate Maven control passed 189 tests and
explicitly skipped eight opt-in database cases across five suites, with reruns
disabled. Its log is `/tmp/queue-runtime-restart-control-20260912.log`.
The final pre-commit selection repeats 189 passes and eight explicit skips in
`/tmp/queue-runtime-restart-final-control-20260912.log`; all five Surefire XML reports
record zero reruns and no failure/error/flaky/rerun elements. Root formatting passed
in `/tmp/queue-runtime-restart-spotless-final-20260912.log`. Existing npm audit
findings (two moderate, one high) remain outside this test-only change.

This closes the scoped single-coordinator restart/callback execution gap, not
Docker/MySQL 8.4 certification, listener failover, network blackholes, multi-host
soak, durable lost-commit admission recovery or business/blob publication ownership.
No production code, schema, routing defaults, primary-master files or remote refs
changed. The earlier store-only evidence below remains a separate selection.

## MySQL And PostgreSQL Restart Matrix (2026-09-12 UTC)

The earlier PostgreSQL-only fixture is now the parameterized
`JdbcAsyncJobStoreDatabaseRestartIntegrationTest`. MySQL 8.4 and PostgreSQL 16 use
the same committed-state, uncommitted-rollback, pool-recovery and expired-owner
assertions, without a second maintained copy. CI and its workflow contract require
the renamed suite. MySQL startup explicitly enables and checks
`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1` and `innodb_doublewrite=ON` before
and after restart; PostgreSQL retains all three previous durability checks and its
postmaster-start-time assertion. Both require actual connection/heartbeat failure
while the server is stopped and reuse the original Hikari pool after restart.

Both native cases passed once, together in 7.507 seconds, with zero failures,
ignored tests, assumption skips or reruns. The MySQL wrapper verified the private
datadir, 8.0.43 version, PID-file location, owned child PID and active TLS before
SIGKILL. That child exited 137; a new mysqld process started on the same datadir,
and the server log records crash recovery. MySQL connections required TLS but did
not certify the generated self-signed CA or hostname. PostgreSQL repeated immediate
shutdown/WAL recovery on a new private 16.15 cluster, retaining certificate and
hostname verification. The wrappers substitute container lifecycle/metadata only;
production store SQL, transactions, pools, natural lease expiry and every shared
test assertion remain real and unchanged. These native cases do not execute Docker
or certify the configured MySQL 8.4 image, power-loss safety or replica failover.

A clean `mvn -Pno-local-config -pl webapp -am clean test` control selected the shared
restart suite, CI contract, 45 store tests and nine permanent-failure runtime tests:
56 passed, two opt-in database cases skipped, no failures/errors/reruns. Cleaning
also removes the renamed class's stale bytecode. Root Spotless passed. The logs are
`/tmp/queue-mysql-restart-control-20260912.log` and
`/tmp/queue-mysql-restart-spotless-20260912.log`; native artifacts are
`/tmp/queue-mysql-restart.DkgDrx/native-restart.log`, `mysql-server.log`,
`pg-server.log`, `NativeDatabaseRestartVerification.java` and `restart_probe.rb`.
The final pre-commit control repeated the same 56 passes and two opt-in skips;
its four Surefire XML reports contain zero failure/error/flaky/rerun elements and
explicitly record reruns disabled. That log is
`/tmp/queue-mysql-restart-final-control-20260912.log`; final formatting is recorded in
`/tmp/queue-mysql-restart-spotless-final-20260912.log`.
Both servers shut down cleanly after verification and their loopback listeners on
45197/59405 closed. More than 27 GiB stayed free. Expected killed-connection,
self-signed/private-fixture and existing build/JDK warnings remain visible.

This closes the narrower MySQL 8.0 store-restart execution gap and rechecks
PostgreSQL after sharing the fixture. The runtime extension above subsequently
adds single-coordinator recovery and callback fencing. Listener failover, actual
network blackholes, the full target-version CI lane, lost-commit admission recovery,
schema adoption and business/blob ownership remain open. No production code,
migration, routing defaults, primary-master files or remote refs changed.

## PostgreSQL Database Restart (2026-09-12 UTC)

This is the initial PostgreSQL-only run. The shared matrix above subsequently
renames the maintained class and adds native MySQL 8.0 store-restart evidence.

`JdbcAsyncJobStorePostgresRestartIntegrationTest` adds one opted-in database test,
selected explicitly alongside existing store/adapter contracts in the zero-rerun
CI job and required by `AsyncJobQueueRealDatabaseCiContractTest`. It kills and
starts the same PostgreSQL container, without replacing its data directory or
rerunning DDL. The fixture enables and checks `fsync`, `synchronous_commit` and
`full_page_writes` before and after restart. Connections, SQL waits and readiness
polling are bounded; one Hikari pool remains alive throughout the outage.

The test verifies acknowledged QUEUED, RUNNING and DONE rows are unchanged after
recovery, while a separate connection's uncommitted input update disappears. A
failed direct connection and real pooled heartbeat establish database unavailability;
a later `pg_postmaster_start_time()` establishes a new server process. After
natural database-clock lease expiry, the old token cannot renew, complete, fail
or retry, even before another worker claims. A replacement with the same worker
ID obtains attempt two and a new token. The same four stale operations reject
without changing that active row; the replacement completes, queued work remains
executable, DONE does not reclaim, and a new enqueue gets a fresh identity. The
pool ends with no borrowed connections or waiting threads.

The maintained method passed once on native PostgreSQL 16.15 in 3.132 seconds,
with zero failures, ignored tests, assumption skips or reruns. A temporary wrapper
substituted only Testcontainers lifecycle/connection metadata: native
`pg_ctl -m immediate stop` stood in for Docker KILL, followed by restarting the
same private cluster. SQL, transactions, pool, lease clock and assertions were
unchanged. Server logs confirm interrupted shutdown, WAL redo and end-of-recovery
checkpoint. This is native immediate-shutdown/crash-recovery evidence, not an
execution of Docker's SIGKILL path, a power-loss test or a hosted-CI result.

The wrapper verified exact datadir, version and active TLS before stopping and
after restarting; JDBC retained certificate/hostname verification. Artifacts are
`/tmp/queue-postgres-restart.OUQczF/native-restart.log`, `server.log`,
`NativePostgresRestartVerification.java` and `restart_probe.rb`. The private
loopback server on port 59404 shut down cleanly afterwards, with no listener left.
More than 30 GiB remained free. The separate Maven control with `-Pno-local-config`
passed 56 tests and skipped this one opt-in case across four suites, with no
failures or reruns (`/tmp/queue-db-restart-verified-20260912.log`); root Spotless
passed. Expected connection-reset, startup and existing build/JDK warnings remain
visible rather than being hidden by retries.

This closes a scoped PostgreSQL store-restart execution gap only. No runtime
handler, callback, business transaction, notification listener, network blackhole,
replica promotion or lost-COMMIT-ack recovery protocol is tested here. MySQL restart,
the full configured database CI lane, runtime failover/soak, migration adoption,
durable admission and publication/blob ownership remain open. No production code,
migration, routing defaults, master files or remote refs changed.

## Retention Clock Fixture (2026-09-12 UTC)

The focused store control exposed a time-dependent failure in both scheduled
retention clock-skew tests: at second zero, `LocalDateTime.toString()` omits the
seconds field, producing an invalid HSQL timestamp literal. This was a test
fixture error, not evidence of a production retention failure. Both tests now
intentionally truncate their shifted clocks to minute boundaries and use an
explicit seconds-bearing formatter. The host-ahead/behind and retention assertions
remain unchanged.

The deterministic minute-boundary reproduction failed both methods with the old
formatter, without reruns (`/tmp/queue-retention-clock-red-20260912.log`). After the
fix, all 45 `JdbcAsyncJobStoreTest` cases passed with zero skips or reruns in
`/tmp/queue-db-restart-verified-20260912.log`; root Spotless passed. This narrows a
source of false CI failures and does not extend database-version or rollout proof.

## Handler Diagnostic Containment (2026-09-12 UTC)

The asset handler's metric fallback logger could discard an otherwise successful
`process` result or replace the original generation/publication/finish exception.
Six strengthened outcome checks and three new fatal controls reproduced nine
failures (37 selected, six assertion failures and three errors, no skips/reruns;
`/private/tmp/queue-handler-diagnostic-red-20260912.log`). These directly invoke
the real handler with mocked business services; they are not database failures
or evidence that a deployed job duplicated its effects.

Only the handler's diagnostic boundary changes. Ordinary metric and fallback-log
failures preserve generated result identity, successful callbacks and original
business errors. JVM-fatal causes/suppressed errors propagate by identity, including
ThreadDeath subclasses; iterative identity tracking handles cyclic graphs. A fatal
after a business effect does not roll it back. The private final logger remains
unchanged; tests attach a warning-specific, caller-thread-scoped appender and
restore the previous logger state in `finally`. No global logging framework or
generic queue API is added.

Root Spotless passes. The handler/output/repair/runtime and existing HSQL adapter
selection passes **138 tests with two opt-in MySQL/PostgreSQL skips across eight
suites**, no failures, errors, flakes or automatic reruns
(`/private/tmp/queue-handler-diagnostic-verified-20260912.log`). The compound-log
faults are handler-unit tests; the integration cases are compatibility controls.
SQL, migrations, payloads, retry policy, flag defaults and the generic engine/JAR
are unchanged. Admission, business fencing, blob lifetime and outage gates remain
open; callback outcome preservation is not durable callback delivery.

## Shared Generation Diagnostic Containment (2026-09-13 UTC)

The shared `LocalizedAssetGenerationService` still allowed its timer's fallback
logger to discard successful output or replace the original generation exception.
Six new regressions failed against the original implementation (15 selected,
four assertion failures and two errors, no skips/reruns). They also reproduced
swallowed nested fatal timer errors and ThreadDeath subclasses, and a fatal
suppressed logging error escaping as its wrapper rather than the original Error.

The timer fallback now contains ordinary logging failures and iteratively checks
causes/suppressed errors with identity-based cycle detection before suppressing
diagnostics. Fatal errors still propagate by identity; no business effects are
rolled back. Tests use a warning-specific, caller-thread-scoped Logback appender
and restore logger state in `finally`. The guard stays private to the shared
service, with no dependency on the queue handler or generic engine.

The focused generation/handler/Quartz/routing/runtime selection passes **137 tests
across seven suites**, without failures, errors, skips or automatic reruns.
The same selection passes after root Spotless; bounded independent code review
finds no material issue. Existing AspectJ, deprecated ThreadDeath and npm
configuration warnings remain. Logs:
`/private/tmp/queue-generation-diagnostics.OqTBAX/{red,focused,final,spotless}.log`.
These are compound-diagnostic unit regressions plus existing HSQL integration and
routing compatibility controls, not new native database or deployed recovery proof.
The fix affects both Quartz and queue callers even with queue routing disabled;
SQL, flags, retry budgets and the independent engine JAR are unchanged. Durable
admission, publication fencing, lifetime and rollout gates remain open.

## Poll Logging Recovery (2026-09-12 UTC)

A broken logger could permanently strand the adaptive poll loop while reporting
a transient claim failure or next-poll scheduling failure. Six deterministic red
cases reproduced interrupted recovery/masked fatal logging (19 tests, five
failures and one error); a separate real scheduler/executor test then timed out
with queued work still unprocessed after the first injected claim/log failure.
The latter uses in-memory queue state, not an actual database outage.

Only the three poll-failure/next-schedule/recovery-schedule logging sites now use
a guarded diagnostic boundary. Ordinary logger failures do not interrupt existing
metrics or recovery scheduling. A fatal logging cause/suppressed error still
escapes as the original Error after releasing the active-poll latch. There is no
logger retry, additional database retry, scheduler watchdog or global logging
rewrite. If both scheduling attempts genuinely fail, the loop still reports
unscheduled and needs an external hint or lifecycle restart after scheduler recovery;
the regression verifies that hint can resume polling.

Focused runtime/coordinator/submission/fatal/public-composition verification passed
423 tests across 19 suites with seven opt-in database skips and zero failures,
errors or XML flaky/rerun entries. The 20-test lifecycle suite includes actual
poller recovery to DONE at attempt one, compound logger/metric failures, transient
warning and ordinary error paths, unscheduled diagnostics and fatal propagation.
Both owned test executors terminate; shared logger state is restored.

The ordinary 29-source engine rebuilt; its independent JPA consumer passed 17
tests with 19 opt-in skips, followed by a clean lean consumer with 11 passed and
seven skipped. Both pass all-class JAR provenance and byte equality with zero
failure/error/flaky/rerun entries. Built and resolved JAR SHA-256:
`f42f5c0674713bedb5da845ebec3a0acfc87bf087d47b7ff525e5ef468954495`.
Logs are `/private/tmp/queue-poll-log-recovery-{red,real-red,verified,engine,consumer-jpa,consumer-lean}-20260912.log`.
Root formatting and diff checks pass; existing deprecation/weaving, frontend,
test-agent and fixture warnings remain. No MySQL/PostgreSQL execution is claimed
for this runtime revision; earlier native runs remain historical evidence. No
schema, transaction, attempt-budget, publication or rollout contract changed.

## Repair Diagnostic Isolation (2026-09-12 UTC)

The repair service could replace an acknowledged task finish with a logging
failure, or replace the actual lookup/publication/finish exception when both a
counter and its fallback logger failed. The expanded red run reproduced 14 failing
cases across 43 tests (12 failures, two errors, no skips or reruns), including real
HSQL task persistence and a failure injected after committed `finishTask` returned.

Metrics and success/failure logs now share a local best-effort boundary with one
guarded fallback warning. Ordinary diagnostic failures preserve repaired and
already-finished results and the original typed repair error/cause. JVM-fatal
errors still escape, including causes, suppressed errors and subclasses; identity
traversal handles cycles without modifying the throwable graph. Wrapped fatal
lookup/finish failures escape before diagnostics. Fatal propagation after a finish
does not imply that committed state rolled back.

Focused verification passed 119 tests across six suites with two opt-in database
skips and zero failures, errors or XML rerun/flaky entries: repair service (30),
HSQL repair privacy integration (13), repair WS (10), handler (34), submission
service (17) and output-retry integration (15 passed, two skipped). The committed
finish/lost-ack fixtures also assert translator HTTP redaction, retained operator
diagnostics and unchanged rows on repeated repair. They inject acknowledgement
loss at the service boundary, not in JDBC or a network connection.

Evidence: `/private/tmp/queue-repair-diagnostics-expanded-red-20260912.log` and
`/private/tmp/queue-repair-diagnostics-final-20260912.log`. Root formatting and diff
checks pass; existing deprecation/weaving, frontend, test-agent and fixture warnings
remain. No real MySQL/PostgreSQL run is claimed for this correction. Generic queue
sources, publication order, retries, transaction ownership, schema and routing
defaults are unchanged; admission, lifetime, migration and rollout gates remain.

## Bounded MySQL Claim Locks (2026-09-12 UTC)

A real MySQL 8.0.43 plan/locking probe reproduced a P1 contention defect: the
combined queued/expired predicate chose a primary-index range scan plus filesort.
With 10,000 ready rows, `LIMIT 1 FOR UPDATE SKIP LOCKED` retained 10,000 primary
record locks. An independent peer returned empty while that candidate transaction
remained open. A new ordinary test reproduced the same failure with 1,500 rows:
no peer progress and 1,500 retained records, rather than a bounded batch. The
previous concurrent-drain tests retried empty polls and did not detect this.

Only MySQL's candidate SELECT changed. Two `UNION ALL` branches each use the existing
queue/status/availability index, order by `(available_at, id)`, limit their locking
read to B and use `SKIP LOCKED`. The outer merge retains the same global ordering
and returns at most B candidates in one SQL call. Only those winners receive the
existing fenced claim mutations; due-time checks, post-lock lease timing and
attempt accounting are unchanged. PostgreSQL/HSQL SQL and migrations are unchanged.

The tradeoff is bounded overlocking: up to 2B matching candidates can remain
locked until commit, including losers of the outer merge. A small mixed backlog
can therefore still give an empty peer claim. This does not guarantee strict
cross-worker FIFO, bound scans over nonexpired running/case-equivalent rows, or
remove the need for workload and version-specific plans. Hosts must preserve the
forced index's supplied name and order. Old workers retain the broad-lock behavior
until drained; this patch is not a mixed-version performance guarantee.

`mysqlClaimBatchKeepsPeerProgressAndBoundedRecordLocks` covers five backlog shapes
at batches 1 and 3: queued, expired-running, mixed, case-equivalent foreign queues,
and active leases preceding eligible work. It holds the production candidate
transaction open while a separate store commits, checks the global ID tie-break,
retained primary/secondary records and disjoint winners, and compares every
unclaimed row's complete stored state. Root access is confined to the disposable
fixture for `performance_schema.data_locks`; the application needs no such grant.
The method is included in the existing MySQL 8.4 CI class selection.

The new regression failed against the old dialect, then passed all ten scenarios
through the rebuilt production dialect on native MySQL 8.0.43. Together with the
eight existing MySQL contract groups, the final temporary-adapter run passed nine
JUnit tests with no skips or automatic reruns in 33.458 seconds. Its 1,000-job
smoke completed in 852 ms with zero poll failures; this is not a benchmarked speedup
or production capacity. The broad queue/admin/asset/parent run passed 1,135 tests
with 32 opt-in skips and no failures or reruns. Logs and temporary adapters are in
`/tmp/queue-mysql-plans.tfp9xd/`: `regression-red.log`, `native-green.log`, `broad.log`
and `production-plan.log`, which repeats EXPLAIN/locking through the rebuilt
production dialect rather than the candidate string. The independent engine JAR
rebuilt offline; its JPA consumer passed 17 tests and a separate clean lean run
passed 11, each with seven real-database skips and no reruns (`engine.log`,
`consumer-jpa.log`, `consumer-lean.log`). The temporary server was shut down;
formatting and diff checks passed. Required MySQL 8.4/PostgreSQL 16 execution, retention
plans, full migration adoption, admission, publication and rollout gates remain
open; no application database or enrollment flag changed.

## Native MySQL 8.4 Store Contracts (2026-09-12 UTC)

The nine maintained MySQL store contract groups now also pass on a private native
MySQL 8.4.11 server in 31.697 seconds, with no JUnit failures, ignored tests,
assumption skips or automatic reruns in the approved execution. The groups cover
store transitions, exact identity across collations, persistable failure diagnostics,
portable payload rejection, retention/replay contention, lease expiry during lock
waits, contended runtime renewals, performance smoke and bounded claim locks.
The claim-lock method retains its ten queued/running/mixed/foreign/active-lease
scenarios at batches one and three; neither its SQL nor its assertions changed.
The smoke drains 1,000 jobs in 583 ms with 262 polls and zero poll failures. This is
a local diagnostic, not a benchmarked improvement over the earlier 8.0 run.

The isolated archive comes from the [official MySQL 8.4 download page](https://dev.mysql.com/downloads/mysql/8.4.html):
`mysql-8.4.11-macos15-arm64.tar.gz`. Its MD5 matches the published
`6e89113f04f2af85d0a164573493db3a`; the downloaded archive's SHA-256 is
`b96e00493bc3499b9ffd7f08d65c5d64933af0383a8287d9873b64f94c2d6009`.
It was extracted only under `/private/tmp/queue-mysql84.z7Xixb`, without a Homebrew
install, service registration or change to the installed MySQL 8.0 service.
Each test verifies the exact private datadir, server version and a nonempty TLS
cipher before creating its UUID-named database. Connections require TLS but do not
certify the self-signed CA or hostname. The loopback-only server uses a 64 MiB
buffer pool, 32 MiB redo capacity, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`
and `innodb_doublewrite=ON`. macOS uses `lower_case_table_names=2`, so this is not
proof of the configured Linux container's filesystem/table-name behavior.

Temporary adapters reuse the maintained tests and their MySQL-specific helpers,
substituting container orchestration only. No production SQL, fixture assertion,
queue setting or application migration changed. The first launch was blocked by
the sandbox's JDBC loopback permission before queue SQL; its nine connection errors
remain in `native.log`. The approved run of the same code is
`native-approved.log`, with explicit aggregate assertions for nine runs and zero
failures/ignored/assumption cases. Do not count the denied launch as a queue defect
or conceal it as an automatic test retry.

The focused Maven control passes 51 tests with 13 opt-in database skips across
three suites (64 selected), with zero failures/errors and zero XML rerun/flaky
entries. The selector is `JdbcAsyncJobStoreTest,JdbcAsyncJobStoreDatabaseIntegrationTest,AsyncJobQueueRealDatabaseCiContractTest`,
using `-Pno-local-config -pl webapp -am`, `surefire.failIfNoSpecifiedTests=false`
and `surefire.rerunFailingTestsCount=0`. Root formatting and diff checks pass.
Existing frontend audit findings (two moderate, one high), fixture TLS warnings
and expected handler-failure logs remain visible; no dependency update is included.

Artifacts in `/private/tmp/queue-mysql84.z7Xixb` include `control.log`,
`native.log`, `native-approved.log`, `server-identity.log`, `server.log`,
`initialize.log`, `compile.log`, `spotless.log`, the two Java contract adapters,
the aggregate runner and `probe.rb`. The server shut down cleanly, its owned
process exited zero, and port 45201 is closed; its disposable datadir was removed.
More than 27 GiB was available before database/build/test work. Keep checking the
5 GiB floor before reusing the retained binary for another private fixture.

Required Docker-backed CI, MySQL 8.4 timezone/adapter lanes, full
independent-consumer proof on 8.4, sustained load/network behavior and
historical schema adoption remain open. This source-in-place verification does not resolve
durable admission, parent recovery, business publication or blob lifetime.

## Native MySQL 8.4 JPA Transactions (2026-09-12 UTC)

Two separate native MySQL 8.4.11 runs now pass with Hibernate 6.6.49.Final and no
JUnit failures, ignored/assumption skips or automatic reruns:

- All 35 maintained `AsyncJobQueueJpaTransactionIntegrationTest` methods pass in
  2.052 seconds, using the application test classpath and mappings. Real JPA/JDBC
  commit/rollback, provisional IDs, consumer visibility, enlistment guards and
  injected lost-acknowledgement outcomes retain their original assertions.
- The independent ordinary-JAR host passes its six `QueueJpaConsumerTest` methods
  plus both `QueueJarBoundaryTest` checks in 1.183 seconds. READ_COMMITTED queue
  transactions remain distinct from SERIALIZABLE host work; resources suspend and
  restore, independent enqueue survives host rollback/read-only transactions,
  failed queue INSERT does not poison host commit, handlers own their business
  transactions, and datasource mismatch guards remain enforced.

The independent runner rejects application output on its classpath. Boundary tests
check every engine class's unique ordinary-JAR origin, absent Mojito business,
Quartz and AspectJ classes, and byte equality with the freshly built engine JAR.
Built and locally resolved JAR SHA-256 both equal
`c2e53835a7273969fe18b1d3d4af396fd18afc03c8dfd55573467dc9b18d6053`.
No production source, module structure, dependency, DDL or public API changed.
Temporary runners adapt only dialect selection and container lifecycle/metadata;
the original parameterized runners and setup/cleanup execute. Each UUID database
is created only after private datadir, exact version and active TLS checks.

The fresh application Maven control passes 37 tests (35 HSQL contracts and two CI
selection guards), with no skips or XML rerun/flaky entries, using
`-Pno-local-config -pl webapp -am` and `surefire.rerunFailingTestsCount=0`.
Separate clean independent-consumer controls pass 17 with 19 database skips in JPA
mode and 11 with seven skips in lean mode, also without failures/errors/reruns.
Cleaning between profiles prevents stale JPA classes from satisfying the lean
boundary. Root `mvn -Pno-local-config spotless:apply` and diff checks pass.
The independent POMs do not inherit the application profile: passing
`-Pno-local-config` there produced an unused-profile warning, not isolation proof.
Existing frontend audit findings (two moderate, one high), negative-fixture logs,
JDK instrumentation and self-signed TLS warnings remain visible.

Artifacts are under `/private/tmp/queue-mysql84-jpa.L1fJd9`: `native-app-jpa.log`,
`native-jar-jpa.log`, `app-control.log`, `consumer-jpa-control.log`,
`consumer-lean-control.log`, `engine.log`, `spotless.log`, `server-identity.log`,
`server.log`, saved JPA control XML in `jpa-reports/`, the Java adapters and Ruby
classpath runners. The [previously checksum-verified native binary](#native-mysql-84-store-contracts-2026-09-12-utc)
served only a fresh private datadir on loopback port 45202. It shut down cleanly
with process exit zero; the port is closed and the owned datadir was removed.
More than 27 GiB remained available before heavy operations. TLS is required, but
this fixture does not verify the self-signed server certificate/hostname; macOS
uses `lower_case_table_names=2`. This is not Docker/Linux or production TLS proof.

Public enqueue is deliberately independent; the internal enlisted primitive and
injected commit outcomes do not establish persisted reservations, same-key retry
recovery or HTTP admission. Full configured CI, remaining 8.4 database lanes,
historical migration adoption, publication/lifetime ownership and actual module
extraction/release still require their separate gates. Master and remote refs are
unchanged; no queue enrollment or deployment occurred.

## Native MySQL 8.4 Compatibility Lanes (2026-09-12 UTC)

Three remaining scoped native compatibility lanes now pass on the same official
MySQL 8.4.11 macOS ARM64 binary used by the store and fault-recovery runs:

- `JdbcAsyncJobStoreTimezoneIntegrationTest`: seven existing methods in both
  configured preparation modes, **14 passes in UTC (2.809 seconds)** and **14 in
  America/Los_Angeles (2.636 seconds)**, using separate JVMs and UUID databases.
  The original session-zone assertions, numeric database time, scheduling,
  retry/replay, lease/heartbeat and strict terminal-cutoff checks ran unchanged.
  This is new-write consistency, not historical timestamp or mixed-worker proof.
- `AssetLocalizeAsyncJobOutputRetryIntegrationTest`'s original MySQL method:
  **one pass in 8.136 seconds**, including Spring startup and its **15 internal
  scenarios**. Queue rows use MySQL; real task/generation/blob services use the
  identity-checked private `jdbc:hsqldb:mem:queue_output_retry` fixture. The existing
  [publication/commit-fault boundaries](#native-mysql-asset-adapter-2026-09-12-utc)
  remain; this is not atomic admission, full-application MySQL compatibility or
  queue-owned blob lifetime. The HSQL-only expired-winner cases are not part of
  this native method.
- Independent lean consumer: **four passes in 1.266 seconds**, comprising the two
  existing public execution/maintenance contracts and both ordinary-JAR boundary
  tests. Real independent enqueue, handler completion/permanent rejection,
  inspection, lifecycle and bounded retention ran without application
  classes, Hibernate, Quartz or AspectJ. Public enqueue remains REQUIRES_NEW, not
  atomic host acceptance. All engine classes had a unique ordinary-JAR origin;
  the rebuilt and resolved JAR SHA-256 both equal
  `6699c3bc77e383bd2d074dfda4840c8555e238bb447b448439546913404f98bb`.

Each native runner asserts its exact execution count and zero failures, ignored
tests and assumption skips, with no rerun loop. Temporary adapters replace only
container orchestration/connection metadata and MySQL parameter selection. The
timezone and asset runs retain the original JUnit/Spring setup and teardown;
the lean JUnit wrapper calls the unchanged public contract methods, including
their owned resource cleanup. No JDBC/store operations or assertions are replaced.
The lean classpath rejects webapp and engine reactor output.
No production code, maintained test, schema, enrollment or API behavior changed.

The fresh root Maven control used `-Pno-local-config`, the timezone and adapter
classes plus `AsyncJobQueueRealDatabaseCiContractTest`, with Surefire reruns zero:
**17 passed, two database methods skipped** across 19 selected tests. The timezone
class ran **zero** methods because its opt-in class setup was disabled; that
control is not timezone execution evidence. The fresh separate clean lean Maven
control passed **11 with seven database skips**; final XML has no failures,
errors or flaky/rerun entries. Root `mvn -Pno-local-config spotless:apply` passed.

An initial engine build compiled successfully but its local Maven install was
denied by the sandbox. Running the consumer before resolving that prerequisite
correctly failed its byte-for-byte provenance assertion against the old cached
JAR (one failure; no retry). The approved local install and an explicit clean
consumer run then passed. Both initial logs are retained; no boundary assertion
was weakened and no artifact was published. Existing frontend audit findings
(two moderate, one high), JDK/Mockito and injected-fault diagnostics remain.

Artifacts are under `/private/tmp/queue-mysql84-compat.SgXyNL/`: `timezone-utc.log`,
`timezone-los-angeles.log`, `native-asset.log`, `native-consumer.log`, the three
native adapters and their Ruby launchers, `NativeMysql84ConsumerRunner.java`,
`app-control.log`, `engine.log`, `engine-approved.log`, `consumer-control.log`,
`consumer-control-approved.log`, `spotless.log` and server identity/startup logs.
The private loopback server on port 45204 checked exact datadir/version and TLS;
its self-signed certificate/hostname were not authenticated. Durability settings
were enabled and macOS selected `lower_case_table_names=2`. Its verified process
exited zero on shutdown, the listener closed and only its disposable datadir was
removed. More than 27 GiB remained free. Primary master and remote refs are
unchanged. The full Docker/Linux matrix, historical Flyway rehearsal, network/
multi-host capacity, durable admission, publication/lifetime and rollout gates
remain open; these native passes are not merge or production approval.

## Native MySQL 8.4 Fault Recovery (2026-09-12 UTC)

Six maintained failure contracts now also pass on native MySQL 8.4.11, without
JUnit failures, ignored/assumption skips or automatic reruns. The two database
restart methods pass in 10.749 seconds; the two pool/session-loss and two
worker-JVM crash methods pass in a separate 14.175-second JUnit selection.
These are existing tests with native lifecycle adapters, not six new test methods
or a fresh execution of their PostgreSQL halves.

- Store restart preserves acknowledged rows, rolls back uncommitted input and
  rejects expired owners after the original Hikari pool reconnects.
- Runtime restart retains the gated handler's capacity through an observed
  heartbeat failure, rejects its stale result/callback, reclaims with a new token
  and processes another job through polling with the same coordinator.
- Pool starvation and terminated-session/blocked-login cases preserve the live
  replacement lease, reject the stale heartbeat/completion and release capacity.
- Killing a worker during its handler permits reclaim only after natural lease
  expiry; repeated business probes preserve the explicit at-least-once contract.
  Killing after committed DONE retains that result without replaying the missed
  best-effort callback. Durable callback delivery remains a separate obligation.

The database wrapper verifies version, datadir, PID-file identity, owned process
PID and active TLS before killing its child. Both database kills exit 137, then
restart the same private datadir; server logs record crash recovery. Durability
assertions remain `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1` and
`innodb_doublewrite=ON`. The two original worker processes also exit 137; their
retained logs and unchanged persisted probe assertions distinguish actual JVM
death from a mocked handler exception. No queue SQL, handler, lease
timestamp or maintained assertion changed. Native setup replaces container
metadata/lifecycle only; the original worker process launcher remains in use.

The Maven control passes 11 tests and explicitly skips 12 opt-in database cases
(23 selected, five suites), with zero failures/errors or XML flaky/rerun entries.
It selects the restart, pool, process-crash, permanent-failure and real-database CI
contract suites using `-Pno-local-config -pl webapp -am` and
`surefire.rerunFailingTestsCount=0`. The skips are not native evidence; the separate
runner asserts all six real-MySQL cases execute without assumptions.
Root formatting and diff checks pass. Existing frontend audit findings (two
moderate, one high), JDK instrumentation, self-signed TLS and deliberately induced
connection/lease failure logs remain visible.

Artifacts are in `/private/tmp/queue-mysql84-faults.EKEjZq`: `native-faults.log`,
`mysql-server.log`, `worker-running.log`, `worker-done.log`, `initialize.log`,
`control.log`, `compile.log`, `spotless.log`, the native Java adapters/runner and
`fault_probe.rb`. The [checksum-verified private binary](#native-mysql-84-store-contracts-2026-09-12-utc)
is unchanged. Its loopback-only port 45203 is closed after orderly cleanup, final
server exits are zero and the owned datadir was removed. More than 27 GiB was
available before heavy work; the wrapper also checks the 5 GiB floor before
every database start/restart. No installed service, application database, master
file, remote ref or queue enrollment changed.

This macOS ARM64 fixture requires TLS but does not verify its self-signed server
certificate/hostname, and uses `lower_case_table_names=2`. Docker/Linux execution,
replica promotion, blackholed networks, sustained/shared-pool load and exactly-once
business publication are not established. Remaining 8.4 timezone/adapter/consumer
lanes, historical schema adoption, durable admission and blob lifetime retain
their separate gates.

## Native MySQL Retention Plan (2026-09-12 UTC)

A separate diagnostic exercised the production retention DELETE on native MySQL
8.0.43 with 10,000 synthetic FAILED rows and a batch of one. EXPLAIN selected the
queue/status/updated-date index for ordered candidates without filesort, materialized
one candidate and used a primary-key `eq_ref` lookup for deletion. The range row
estimate is not a measurement of actual scan work. Holding that DELETE transaction
open retained one shared secondary-index record and one exclusive primary record,
not the full terminal backlog. An independent store replayed another failed row
before the purge was allowed to commit; final outcomes were one deletion and one
successful replay. No new defect was found and no retention SQL changed.

The probe checked its private server data directory before creating a fresh schema,
captured the SQL and parameters from `JdbcAsyncJobStore`, and used real JDBC
transactions. Artifacts are `/tmp/queue-mysql-retention.aU6WKO/probe.log` and
`NativeMysqlRetentionProbe.java` in that directory. The temporary server was shut
down. This is one diagnostic scenario, not an additional JUnit result, MySQL 8.4 or
PostgreSQL proof, a sustained-load test, or coverage of all status/collation/backlog
shapes. Target-version plans and the existing retention/replay CI gates remain open.

## Bounded MySQL Retention Regression (2026-09-12 UTC)

`JdbcAsyncJobStoreDatabaseIntegrationTest.mysqlRetentionBatchKeepsPeerReplayAndBoundedRecordLocks`
turns the earlier one-off diagnostic into a maintained MySQL 8.4 contract. It
runs 20 internal scenarios: DONE/FAILED, batches one/three, and five 1,501-row
fixtures covering a terminal backlog, case-distinct foreign queues, rows newer
than the fixed cutoff, nonterminal QUEUED rows, and equal-timestamp ID ordering.
Each fixture includes an unselected FAILED tail row for an independent replay.

The real production DELETE finishes but its transaction remains open behind a
latch. A separate connection counts `performance_schema.data_locks` records;
the test requires exactly B retained primary records and at most 4B total index
records. A peer must commit replay before the purge latch is released. After
commit, exactly the oldest eligible B rows are gone, every other row except the
replayed tail is byte/value-equivalent to its snapshot, and that tail is QUEUED.
Fixture root privileges are needed only for lock observation. Each case resets
its disposable queue table and checks worker termination; no production setting
or retention SQL changed. The existing opted-in, zero-rerun CI selector already
includes the containing test class; Docker execution still needs verification.

The new method passed all 20 scenarios on private native MySQL 8.4.11 in 2.440
seconds, reported as **one JUnit pass**, with no failures, ignored/assumption skips
or automatic reruns. Only native connection/container metadata were adapted; the
maintained method and assertions ran unchanged. The initial run failed during
fixture setup because synthetic FAILED rows lacked the existing schema's required
`last_error`; it never reached retention. Adding valid failure diagnostics to the
seed data corrected that test-only error without weakening constraints/assertions.

The fresh `-Pno-local-config` store/DB/CI-contract control passed **51 tests with
14 explicit database skips**, 65 selected across three suites, zero failures,
errors or rerun/flaky entries. The new test is one of those skips; the native
pass above is its execution evidence. Root Spotless and diff checks passed.
Existing frontend audit findings (two moderate, one high), JDK/Mockito and
self-signed fixture warnings remain. Artifacts are under
`/private/tmp/queue-mysql84-retention.Jo8ZSH/`: both native logs, the native adapter
and count-checking runner, `probe.rb`, original/corrected controls, formatting and
server logs. The identity-checked TLS-required loopback fixture used port 45205;
certificate/hostname authentication was not enabled. Its server was shut down and
only its disposable datadir removed, leaving more than 27 GiB free.

This protects retained lock footprint and correctness on these fixture shapes,
not the number of scanned rows, arbitrary optimizer plans, all collations,
sustained throughput, Linux/Docker CI or production capacity. The older EXPLAIN
diagnostic remains separate evidence. Historical migration adoption, durable
admission, publication/blob ownership and rollout gates remain open. Primary
master, remote refs and queue routing are unchanged.

## Native MySQL Ordinary-JAR Consumer (2026-09-12 UTC)

The standalone 29-source engine rebuilt with offline `clean install`; the separate
lean consumer's `spotless:check clean test` passed 11 tests with seven opt-in database
skips. A temporary adapter then ran the existing public MySQL execution and scheduled
maintenance methods against private native MySQL 8.0.43. Together with the two JAR
boundary tests, this direct JUnit run passed four tests with no skips or automatic
reruns in 1.405 seconds. Repeated provenance assertions inside the adapter are not
additional tests. No production or maintained test source changed.

The native invocation used only the fresh lean consumer's classpath and ordinary
engine JAR, not webapp or reactor engine classes. The boundary test checked every
packaged class's resource and code origin, absence of Mojito/Quartz/AspectJ/Hibernate,
and byte equality with this build. Both built and resolved JAR SHA-256 values were
`03913d22bf5ff6b612322a64d26b4e17fe2037785fdc5dd4e2fd9ea46fdafe79`.
Only Testcontainers construction/lifecycle and connection settings were substituted;
actual JDBC, fixture DDL, Spring configuration and existing assertions were unchanged.
The adapter checked server version and its exact private data directory before
creating each UUID-named schema.

Execution covers independent queue commit despite caller rollback, handler execution
outside the caller transaction, public inspection, permanent failure at attempt one,
coordinator restart and preservation of host-owned resources. Maintenance covers
explicit scheduling, disabled retention, per-status batch bounds, preservation of
recent/live/other-queue rows, metrics and scheduler cleanup without consumers.
This closes a narrower native-MySQL packaging check, not MySQL 8.4/PostgreSQL 16 CI,
JPA on those databases, pooled/failover behavior, migration adoption or OSS readiness.

Artifacts are `/tmp/queue-mysql-consumer.vLe6Z8/consumer-lean.log`, `native-consumer.log`,
`NativeMysqlJarConsumerTest.java` and `consumer_probe.rb`; the engine build log is
`/tmp/queue-mysql-consumer-engine-20260912.log`. JDK/Mockito dynamic-agent warnings and
the expected permanent-handler-failure log remain visible. The private server on
loopback port 45190 was shut down and its data directory removed. No application
database, primary worktree, migration, routing flag or remote ref changed.

## Native PostgreSQL Ordinary-JAR Consumer (2026-09-12 UTC)

The ordinary queue JAR at source revision `f8025ec5ca` passed seven direct JUnit
checks against a private PostgreSQL 16.15 server: the five existing PostgreSQL
execution, maintenance and wakeup contracts plus two JAR-boundary tests. The
approved run had zero failures, ignored tests, assumption skips or automatic
reruns. The preparatory clean lean Maven run passed 11 tests and skipped seven
opt-in database cases; these overlapping runs are not additive coverage.

The wakeup cases verify successful hints without committing a resumed caller's
business transaction, observing both commit and rollback through an independent
connection. A producer with no local handler wakes a separate consumer after its
initial empty poll and established LISTEN subscription, ahead of its one-minute
periodic poll. An injected notification-connection failure leaves the job durable
at attempt zero; a later polling-only consumer completes it after producer shutdown.
The latter is controlled hint failure, not a real network partition. Execution and
maintenance retain their existing transaction, permanent-failure, inspection,
restart, bounded-retention and host-resource cleanup assertions.

Only Testcontainers construction/lifecycle and connection metadata were substituted.
The existing methods, SQL, fixture DDL and assertions were unchanged. Before every
UUID database creation, the adapter checked the exact private data directory,
server version `160015` and active TLS; all JDBC URLs used `sslmode=verify-full`
with the private certificate. The lean classpath excluded webapp and reactor engine
classes. All-class JAR provenance, absence of Mojito/Quartz/AspectJ/Hibernate and
built-versus-resolved byte equality passed. Both JAR SHA-256 values were
`8678855c68e05f5502c1cfb771f5285e6edcf5484b8ef5ab095a19eef13cf54f`.

The server was built privately from the official PostgreSQL mirror's `REL_16_15`
commit `7d3e000c5961a544302072058a1184e9a588837b`, without installing a Homebrew
service. Artifacts are in `/tmp/queue-postgres16.FgSYLH`: `consumer-native-approved.log`,
`consumer-lean.log`, `NativePostgresJarConsumerTest.java`, `NativeConsumerRunner.java`,
`consumer_probe.rb`, build/install logs and `server.log`. Initial sandbox attempts
failed on shared-memory allocation and loopback connections before queue SQL;
approved access resolved those environment failures without changing deadlines,
adding test retries or disabling TLS. JDK/Mockito warnings and expected injected
handler/notification failure logs remain visible.

More than 24 GiB remained free before and after execution. The loopback server on
port 59396 shut down cleanly, its listener closed and its private data directory
was removed. This closes the fresh PostgreSQL public-consumer execution gap, not
the full configured CI selection, application JPA/asset adapter, Flyway upgrade,
pool/network/failover soak, extraction or production-readiness gates. No production
or maintained test source, migration, primary worktree, routing flag or remote ref
changed.

## Native MySQL JPA/JDBC Contracts (2026-09-12 UTC)

All 35 existing `AsyncJobQueueJpaTransactionIntegrationTest` methods passed on
private native MySQL 8.0.43 with Hibernate 6.6.49.Final in 2.143 seconds. The original
Parameterized runner, class/per-test setup and teardown remained active. A temporary
adapter restricted only `databases()` to MYSQL using a call-real-methods static stub
and substituted Testcontainers construction/lifecycle and connection configuration.
It asserted exactly 35 executions, no ignored tests or assumption skips, one MySQL
container construction and zero failures; no automatic reruns were added. The
unmodified Maven HSQL selection separately passed 35 with no failures/errors/skips
and Surefire reruns disabled. Counts are separate selections, not additive coverage.
That Maven run refreshed the classes/classpath used by the native launcher; the
native process exited zero but does not emit Surefire XML. Independent read-only
review found no must-fix adapter issue and retained these evidence boundaries.

Real `JpaTransactionManager`, Hibernate and JDBC operations exercised the production
PollableTask mapping, physical connection sharing, public REQUIRES_NEW isolation,
internal caller-owned task/queue enlistment, post-INSERT rollback, and both
commit-then-throw and rollback-then-throw outcomes. Held-open producer tests prove
consumers cannot observe uncommitted/rolled-back work, but can finish committed
work before the producer receives its delayed failure. Renewal/retry/failure and
claim-acknowledgement cases preserve or replace fenced ownership appropriately;
claim uncertainty can exhaust attempts without running a handler. Invalid manager,
datasource, isolation, read-only and absent-transaction configurations remain rejected.

This verifies admission prerequisites, not the proposed durable reservation,
ACCEPTED-state transition, request-key recovery, parent reconciliation or blob pins.
Faults are injected at the real JDBC commit seam; they are not actual socket loss,
database restart, process death or sustained shared-pool behavior. This uses the
application test classpath and mappings, not the independent ordinary-JAR JPA host
lane. MySQL 8.4/PostgreSQL 16 CI and compatibility gates remain open. Queue DDL is
installed as a fixture and the managed entity graph is created by Hibernate; this
does not rehearse Flyway adoption or a historical application upgrade.

Artifacts are `/tmp/queue-mysql-jpa.fOrbl0/native-jpa.log`,
`NativeMysqlJpaVerification.java` and `jpa_probe.rb`, plus the HSQL Maven log
`/tmp/queue-native-jpa-hsql-20260912.log`. Server version and the exact private data
directory were checked before creating a UUID-named schema. The loopback-only
server on port 45191 was shut down and its data directory removed. Existing
JDK/Mockito agent, compilation/weaving and expected injected-failure diagnostics
were not suppressed. No production/test source, migration, runtime flag, primary
worktree or remote ref changed.

## Native PostgreSQL Store Contracts (2026-09-12 UTC)

Eight selected groups from `JdbcAsyncJobStoreDatabaseIntegrationTest` passed on
private PostgreSQL 16.15 in 33.414 seconds, with zero failures, ignored/assumption
skips or automatic reruns. Four existing PostgreSQL methods cover the store,
notification delivery, persistable failure diagnostics and portable payload writes.
Four shared helpers ran only their PostgreSQL halves: concurrent retention,
lease expiry during lock waits, contended renewal and the opt-in performance smoke.
These are eight temporary JUnit wrappers around existing contracts, not eight new
maintained tests or execution of their MySQL halves. The separate Maven control
with `-Pno-local-config` and zero reruns passed four tests and skipped 13 opt-in
cases; those skips are not real-database evidence.

The store contract checks bounded attempts, reclaim tokens, stale-owner rejection,
terminal inspection/replay/cleanup, concurrent claims and runtime draining. All
five lease mutations reject ownership that naturally expires while waiting on a
row lock. Retention rechecks status and cutoff after a concurrent transaction
commits a replay-like status change or newer timestamp; both rows survive. The
renewal fixture keeps 24 handlers alive through contention and 221 competing polls,
with 960 successful renewals, no duplicate starts or peer reclaim, and every job
DONE on attempt one. The separate smoke drained 1,000 jobs in 1,070 ms, with 266
polls and zero poll failures. This single local sample is not a throughput SLO,
multi-host benchmark or shared-pool sizing result.

Only Testcontainers lifecycle and connection metadata were substituted. Real
JDBC/SQL, transactions, row locks, deadlines and existing assertions remained;
the helpers were selected by reflection without editing application or test source.
The temporary adapter checked the exact private datadir, version and active TLS
before each UUID database, using certificate/hostname verification throughout.
It uses the application test classpath, not the independent ordinary-JAR host.

Artifacts are `/tmp/queue-postgres-store.n6bIi2/native-store.log`,
`NativePostgresStoreVerification.java`, `store_probe.rb` and `server.log`; the
control is `/tmp/queue-postgres16.FgSYLH/store-control.log`. The fresh pre-commit
control repeated four passes and 13 opt-in skips in the probe's `commit-control.log`;
root `mvn -Pno-local-config spotless:apply` passed in `spotless.log`.
The private 16.15 binary served a new loopback-only cluster on port 59400, shut down cleanly, and its datadir
was removed. More than 31 GiB remained free after cleanup. Expected constraint,
handler-failure and stale-owner diagnostics plus JDK/Mockito warnings remain
visible. No schema version, routing, master file or remote ref changed. Full
target-version CI, historical Flyway adoption and workload rollout gates remain
open. This selection does not execute PostgreSQL timezone, crash or outage cases;
the subsequent timezone matrix is recorded separately below.

## Independent JAR JPA Database Lanes (2026-09-12 UTC)

`QueueJpaConsumerTest` now runs its same six public-API contracts on HSQL and
opt-in MySQL 8.4/PostgreSQL 16. Each real-DB case owns a disposable container and
installs the existing dialect migration only as a fixture. Context, scheduler,
worker, entity factory and database cleanup remain bounded; skipped cases allocate
no fixture. No engine source, POM dependencies, public API or schema version changed.

The dedicated CI JPA invocation now explicitly opts into databases with zero
Surefire reruns. Its new workflow regression failed on the previous HSQL-only
command (`ci-red.log`), then both workflow tests passed with `-Pno-local-config`
and no skips/reruns (`ci-green.log`). This is maintained selection coverage, not
evidence that hosted CI or MySQL 8.4 actually ran.

After rebuilding and locally installing the ordinary 29-source engine JAR, the
independent JPA consumer passed 17 tests with 19 opt-in skips; a clean switch to
the lean consumer passed 11 with seven skips. A separate native PostgreSQL 16.15
run passed all six JPA cases plus both JAR-boundary tests in 2.120 seconds, with
zero failures, ignored/assumption skips or automatic reruns. The original
Parameterized runner, fixtures, lifecycle and assertions remained; only dialect
selection and container orchestration/metadata were adapted. Both resolved/built
JARs have SHA-256 `3266c1ea5bf0365da64eee6f0657df19b6ac2bbaa67d707e20f63746ca76be25`.
Boundary tests verify every engine class's ordinary-JAR origin, identical bytes,
and absence of Mojito business, Quartz and AspectJ classes.

Real PostgreSQL/Hibernate transactions verified READ_COMMITTED queue versus
SERIALIZABLE host isolation, distinct connections, suspended/restored host resources,
independent queue durability despite host rollback, writable enqueue from a
read-only host, actual INSERT rollback without poisoning host commit, worker-owned
business transactions and both datasource mismatch guards. Public enqueue remains
REQUIRES_NEW; these are not atomic task/queue admission or unknown-commit recovery.

Artifacts are under `/tmp/queue-jar-jpa-realdb.4sjwfA/`: `native-jpa.log`,
`NativeJarJpaVerification.java`, `jpa_probe.rb`, `consumer-jpa-hsql.log`,
`consumer-lean.log`, `ci-red.log`, `ci-green.log`, `engine.log` and `spotless.log`.
The native runner rejects webapp output; each UUID database checks the private
datadir, version and TLS with certificate/hostname verification. Its loopback-only
server on port 59404 shut down cleanly and its datadir was removed; more than 31 GiB
remained free. Initial local Maven-cache installation was sandbox-denied, then the
scoped approved install succeeded without publishing. Expected negative-bootstrap,
JDK/Mockito, deprecated ThreadDeath and existing build warnings remain visible.
Full CI, MySQL 8.4 JPA, historical adoption, extraction agreement and production
rollout remain open; primary master, remote refs and routing are unchanged.

## Native MySQL Independent JPA Host (2026-09-12 UTC)

The six maintained `QueueJpaConsumerTest` contracts plus two `QueueJarBoundaryTest`
checks passed on installed MySQL 8.0.43 in 1.411 seconds, with no failures,
ignored/assumption skips or automatic reruns. This exercises the independent
ordinary-JAR consumer, not the application's separate 35-test JPA fixture.
The engine build and resolved artifact still match SHA-256
`3266c1ea5bf0365da64eee6f0657df19b6ac2bbaa67d707e20f63746ca76be25`.

The temporary adapter changes only parameter selection and container lifecycle/
connection metadata. Each case uses a new UUID database on the identity-checked
private instance, with real SQL, original assertions and JUnit setup/cleanup.
The runner rejects webapp output. READ_COMMITTED queue versus SERIALIZABLE host
isolation, distinct physical connections, host suspension/restoration, independent
commit/rollback, worker-owned business transactions and datasource mismatch guards
all pass. TLS encryption is required, but this disposable self-signed fixture does
not verify server certificates or hostnames; it is not production TLS evidence.

Two earlier setup failures remain visible: sandboxed initialization received
signal 11 before queue SQL, while the same initialization succeeded with scoped
approval. The first test attempt then failed six cases before queue SQL because
the fresh socket-only root account rejected TCP loopback. A private loopback-only
test account corrected authentication for the explicit second run. No queue SQL,
assertions, timeouts or TLS requirements were weakened; this was not a first-attempt
pass. Logs and the adapter remain under `/tmp/queue-jar-jpa-mysql.nm3JQm/`:
`initdb.log`, `initdb-native.log`, `native-jpa.log`, `native-jpa-verified.log`,
`NativeMysqlJarJpaVerification.java` and `jpa_probe.rb`.

The fresh HSQL/JPA control passed 17 of 36 selected tests, with 19 opt-in skips
(`/tmp/queue-jar-jpa-realdb.4sjwfA/consumer-jpa-mysql-control.log`). The subsequent
clean lean control passed 11 with seven skips (`consumer-lean.log`); neither is
additional real-DB evidence. Root formatting passed. The loopback-only server on
port 45195 shut down cleanly, its private datadir was removed, and more than 29 GiB
remained free. Expected negative-bootstrap, JDK/Mockito and self-signed fixture
warnings remain visible. No production code, schema, defaults or primary-master
files changed. MySQL 8.4/full CI, historical schema adoption, durable admission,
business fencing, blob ownership and extraction agreement remain open.

## Native PostgreSQL Worker-JVM Crashes (2026-09-12 UTC)

Both existing PostgreSQL `AsyncJobQueueProcessCrashIntegrationTest` methods passed
on private PostgreSQL 16.15 in 10.681 seconds, with zero failures, ignored/assumption
skips or automatic reruns. The unchanged fixture launched and forcibly terminated
two separate worker JVMs, observing exit 137 for each. The preparatory Maven control
with `-Pno-local-config` checked build/classpath but skipped all four opt-in cases;
those skips are not database proof.

In the running-handler case, a real heartbeat extends the lease before process
death. The parent drains any pending row-lock writer, rejects early reclaim, and
waits for natural database-clock expiry without rewriting timestamps. A replacement
using the same worker ID receives attempt two and a new token. Old-token renewal,
completion, failure and retry all reject without changing its row; the replacement
finishes and releases capacity. Both attempts' business probes persist, explicitly
demonstrating at-least-once effects.

The other child dies after DONE commits but before its success callback. Two fresh
runtime polls stay empty, preserving DONE/output on attempt one, one business probe
and zero callback probes. This verifies the known best-effort callback limitation,
not automatic publication repair or a new defect. Domain reconciliation and input/
output retention therefore remain separate adoption gates.

The temporary adapter substituted only Testcontainers lifecycle and connection
metadata, invoking the existing public methods unchanged. Before each UUID database
it checked the exact private datadir, version and active TLS. Both parent and child
connections used the same certificate/hostname-verifying JDBC URL. This uses the
application test classpath, not the independently packaged host. No child process,
queue SQL, clock, assertion or deadline was mocked or altered.

Artifacts are `/tmp/queue-postgres-crash.8BOpqC/native-crash.log`,
`NativePostgresCrashVerification.java`, `crash_probe.rb` and `server.log`; the parent
log records the two child-log paths. The control is
`/tmp/queue-postgres16.FgSYLH/crash-control.log`. The private loopback server on port
59403 shut down cleanly, its datadir was removed, and more than 31 GiB remained free.
Existing JDK/Mockito warnings remain visible. No maintained production/test source,
migration, master file, routing or remote ref changed. These are worker crashes,
not database crashes, lost-commit network acknowledgements, multi-host soak or full
target-version CI; schema adoption, durable admission and rollout gates stay open.

## Native PostgreSQL Pool And Session Loss (2026-09-12 UTC)

Both existing PostgreSQL methods in `JdbcAsyncJobStorePoolIntegrationTest` passed
on private PostgreSQL 16.15 in 5.556 seconds, with zero failures, ignored/assumption
skips or automatic reruns. The separate `-Pno-local-config` Maven control rebuilt
the selection but skipped all four opt-in MySQL/PostgreSQL cases; it is not the
native result. Two temporary wrappers selected the PostgreSQL methods, replacing
only container lifecycle and connection metadata. Real Hikari pools, production
coordinator/executor/heartbeat construction, SQL, timeouts and assertions remained.

Each method starts a handler, then prevents renewal through its one-slot pool.
The starvation case holds that connection; the disconnect case terminates its
actual backend, returns the dead connection and proves the dedicated worker role
cannot log in. Both observe the actual JDBC/pool failure, wait for natural
database-clock lease expiry and reclaim with the same worker ID and a new token.
After restoring access, the old heartbeat and completion both return false, the
active replacement row is unchanged, and no stale success callback runs. Runtime
in-flight/executor counts return to zero; the replacement alone commits its DONE
payload. Pool cleanup leaves no borrowed connections or waiting threads. These
assertions fence queue-row state, not arbitrary external business side effects.

Artifacts are `/tmp/queue-postgres-pool.8gwhlD/native-pool.log`,
`NativePostgresPoolVerification.java`, `pool_probe.rb` and `server.log`; the Maven
control is `/tmp/queue-postgres16.FgSYLH/pool-control.log`. Before each UUID database,
the adapter checked the exact private datadir, version and active TLS, retaining
certificate/hostname verification. The fixture used a new loopback-only cluster
on port 59402. Both pools closed, the server shut down cleanly, and the private
datadir including the disposable worker role was removed; more than 31 GiB stayed
free. Expected connection timeout, termination/NOLOGIN and stale-owner diagnostics
plus JDK/Mockito warnings remain visible.

No production/test source, migration, master file, routing or remote ref changed.
This is application-classpath session-loss and blocked-reauthentication evidence,
not a network blackhole, database restart, separate worker-JVM crash, sustained
multi-host soak, independent-JAR host or pool-capacity certification. Full CI,
historical migration, durable admission and workload rollout gates remain open.

## Native PostgreSQL Timezone Contracts (2026-09-12 UTC)

All seven existing `JdbcAsyncJobStoreTimezoneIntegrationTest` methods passed in
both configured PostgreSQL preparation modes on private PostgreSQL 16.15. Separate
UTC and America/Los_Angeles JVMs each executed 14 tests with zero failures,
ignored/assumption skips or automatic reruns, in 4.519 and 4.212 seconds respectively.
The Maven control with `-Pno-local-config` refreshed classes and classpath but ran
zero tests because the opt-in class bootstrap was disabled; it is build evidence,
not part of the native test count.

The temporary adapter replaced only dialect selection and database bootstrap,
retaining the original Parameterized runner, session setup, ErrorCollector rules,
seven test methods and class teardown. It asserted one bootstrap and a cleared
fixture map. Real JDBC connections retained the fixture's explicit session-zone
and `prepareThreshold` checks; no temporal SQL, bindings, readback, transactions
or timing bounds were mocked. Each JVM used a new UUID database, after checking
the private datadir, version and active TLS with certificate/hostname verification.

Numeric database-clock bounds, absolute scheduling, leases/heartbeats, retry/replay,
terminal cutoffs, epoch/maximum and DST-adjacent instants, and out-of-range rejection
passed across UTC, Tokyo and Los Angeles sessions. No future job was claimed early
and no live lease was reclaimed by a differently zoned peer. Preparation settings
were checked on connections, not independently certified for every SQL statement.
This is application-test-classpath evidence for new writes, not historical row
interpretation, mixed-worker cutover, full target-version CI or the standalone host.

Artifacts are `/tmp/queue-postgres-timezone.NIJ8Cw/native-utc.log`,
`native-los-angeles.log`, `NativePostgresTimezoneVerification.java`,
`timezone_probe.rb` and `server.log`; the Maven control is
`/tmp/queue-postgres16.FgSYLH/timezone-control.log`. Root formatting passed with
`mvn -Pno-local-config spotless:apply` (`spotless.log` in the probe directory).
The isolated loopback server on
port 59401 shut down cleanly with no ERROR/FATAL/PANIC entries, and its private
datadir was removed. More than 31 GiB remained free. Existing JDK/Mockito agent
warnings remain visible. No maintained production/test source, migration, master
file, routing or remote ref changed; adoption and rollout gates remain open.

## Native PostgreSQL Listener Sessions (2026-09-12 UTC)

Both existing `JdbcPostgresAsyncJobQueueWakeupListenerDatabaseIntegrationTest`
methods passed on private PostgreSQL 16.15 in 1.198 seconds, with zero failures,
ignored/assumption skips or automatic reruns. The separate Maven control passed
27 listener tests and skipped these two opt-in database methods. Only container
orchestration and connection metadata were substituted; real Hikari pools, JDBC,
notification operations, five-second deadlines and existing assertions remained.

The cleanup method reused the same backend PID through two start/stop cycles in
each auto-commit mode, preserving that mode, clearing LISTEN subscriptions and
returning borrowed-connection counts to zero. The reconnect method terminated only
its private listener backend with `pg_terminate_backend`; a replacement PID became
subscribed and delivered another hint despite the intentionally throwing failure
counter. Stopping returned that replacement session unsubscribed with restored
auto-commit. The server log confirms the actual termination. These tests observe
poll triggers through a mock coordinator, not business-job execution; they do not
replace the separate public-consumer proof or certify network/database failover.

Artifacts are `/tmp/queue-postgres-listener.VyWtT1/native-listener.log`,
`NativePostgresListenerVerification.java`, `listener_probe.rb` and `server.log`;
the control is `/tmp/queue-postgres16.FgSYLH/listener-control.log`. Before each
UUID database creation the adapter checked the private data directory, version and
active TLS, with certificate/hostname verification enabled. The existing private
16.15 binary served a new cluster on loopback port 59399. All test pools closed,
the server shut down cleanly, its listener closed and its private datadir was
removed. JDK/Mockito, intentional backend-termination/cleanup and injected-counter
diagnostics remain visible. More than 33 GiB stayed free. No production/test
source, schema migration, non-fixture database, master file, routing or remote ref
changed. Full CI, staged alerting, sustained outage and pool-sizing gates remain open.

## Native PostgreSQL Asset Adapter (2026-09-12 UTC)

The unchanged `postgresqlQueueRetriesOutputAndRejectsTrackedWork` method in
`AssetLocalizeAsyncJobOutputRetryIntegrationTest` passed on private PostgreSQL
16.15 in 8.468 seconds, including Spring startup. This is **one JUnit test** with
15 internal scenarios, not 15 independently reported tests: three output retries,
tracked-work rejection, two enqueue acknowledgement timings, two completion
commit outcomes, four publication failures, malformed-winner repair, raw replay
containment and timeout-before-callback handling. The launcher required one test,
one PostgreSQL container substitution, one application-database identity check,
zero failures and no ignored/assumption skips; no automatic reruns were used.
The separate fresh Maven control passed 15 tests with two opt-in database skips.

The native adapter kept the original Spring runner, security/setup lifecycle,
fixture method, SQL and assertions. Only container orchestration and connection
metadata were substituted. It checked PostgreSQL version, private data directory
and verified TLS before queue DDL, and confirmed application writes used the
original `jdbc:hsqldb:mem:queue_output_retry` fixture. Classpath-only configuration
excluded local user config; the embedded HTTP test server bound only to loopback.
The Spring context closed explicitly, with Tomcat, Quartz and Hikari shutdown
recorded. The private PostgreSQL listener on port 59398 then closed cleanly and
its data directory was removed. No non-fixture database or primary worktree changed.

Artifacts are `/tmp/queue-postgres-asset.nBttAG/native-asset.log`,
`NativePostgresAssetVerification.java`, `asset_probe.rb` and `server.log`; the Maven
control is `/tmp/queue-postgres16.FgSYLH/asset-hsql-control.log`. The existing private
PostgreSQL 16.15 build was reused with a fresh cluster. JDK/Mockito, Hibernate
metamodel, open-in-view and authentication configuration warnings remain visible,
along with expected injected/rejected-handler diagnostics. This is current
PostgreSQL queue plus real HSQL generation/blob/service evidence, not a full
application PostgreSQL deployment. Completion faults enter the actual queue JDBC
commit seam; enqueue/publication faults remain controlled adapter/service failures,
not network loss. Separate databases cannot prove atomic admission, and these
scenarios do not implement durable recovery identities, business fencing or blob
pins. Required MySQL 8.4/full CI and rollout gates remain open. No production or
maintained test source, migration, routing flag or remote ref changed.

## Native MySQL Asset Adapter (2026-09-12 UTC)

The original `mysqlQueueRetriesOutputAndRejectsTrackedWork` method passed on
installed MySQL 8.0.43 in 8.540 seconds, including Spring startup: one JUnit test,
15 internal scenarios, no failures, ignored/assumption skips or automatic reruns.
It exercises the same [scenario set and fault boundaries](#native-postgresql-asset-adapter-2026-09-12-utc)
as the PostgreSQL run above. In particular, durable DONE survives a thrown
commit acknowledgement and repairs without rerunning generation; rollback before
commit permits a fresh-token attempt after explicitly expiring only the fixture's
lease, rejecting stale completion. This is not natural-expiry or network-loss proof.

Only container lifecycle and connection metadata were substituted. The original
Spring runner, security/setup lifecycle, SQL and assertions remained. The adapter
verified the private MySQL datadir/version and encrypted TLS, then checked the
application datasource was `jdbc:hsqldb:mem:queue_output_retry`. TLS was required,
but this self-signed fixture did not verify certificates/hostnames. Local user
configuration was excluded and HTTP/MySQL listeners bound only to loopback. Real
generation, task and blob services ran on HSQL; separate databases do not establish
atomic admission, MySQL ORM compatibility, business fencing or pinned blob lifetime.
The native selection does not include the HSQL-only expired-winner cleanup cases.

Artifacts under `/tmp/queue-mysql-asset.bKqgPk/` are `native-asset.log`,
`NativeMysqlAssetVerification.java`, `asset_probe.rb`, `asset-control.log`,
`initdb.log`, `server.log` and `spotless.log`. The fresh `-Pno-local-config`
control passed 15 tests with two database skips and no failures/errors/reruns;
root formatting passed. Native execution and initialization succeeded on their
first approved attempts. Spring/Tomcat/Quartz/Hikari cleanup completed, followed
by clean private MySQL shutdown on port 45196 and removal of its datadir. More than
28 GiB remained free. Existing Hibernate/JDK/Mockito, self-signed fixture and
expected injected-failure warnings remain. No production/test source, migration,
primary-master files, routing or remote refs changed. MySQL 8.4/full CI, schema
adoption and business rollout gates remain open.

## Native PostgreSQL JPA/JDBC Contracts (2026-09-12 UTC)

All 35 existing `AsyncJobQueueJpaTransactionIntegrationTest` methods passed on
private PostgreSQL 16.15 with Hibernate 6.6.49.Final in 2.804 seconds. The fresh
Maven HSQL control separately passed all 35 with zero failures/errors/skips and
Surefire reruns disabled. The native launcher retained the original Parameterized
runner, setup/teardown, SQL and assertions. Its only substitutions were selecting
POSTGRESQL in `databases()` and native connection/orchestration metadata instead
of starting a container. It required exactly 35 executions, one PostgreSQL fixture,
zero failures, zero ignored/assumption skips and no automatic reruns.

This repeats the [MySQL fixture's transaction and fault contracts](#native-mysql-jpajdbc-contracts-2026-09-12-utc)
on the supported PostgreSQL major: actual task/queue enlistment, independent public
enqueue, consumer visibility, commit uncertainty and lease/claim ownership. The
commit-then-throw and rollback-then-throw cases still use controlled JDBC fault
injection. They are not actual network loss or a request-key recovery protocol.
The fixture creates the queue schema directly and a limited production entity
graph through Hibernate. It does not test historical Flyway upgrades, the complete
application mapping graph or the independent JAR's host-owned JPA fixture.

Artifacts are `/tmp/queue-postgres-jpa.GUy4no/native-jpa.log`,
`NativePostgresJpaVerification.java`, `jpa_probe.rb` and `server.log`; the Maven
control is `/tmp/queue-postgres16.FgSYLH/jpa-hsql-control.log`. The server used the
same private 16.15 build as the ordinary-JAR run, but a new private cluster and
UUID database on loopback port 59397. The adapter verified version, exact data
directory and TLS before DDL; JDBC kept certificate/hostname verification enabled.
More than 23 GiB remained free before and after execution. The server shut down
cleanly, the listener closed and its private data directory was removed. Existing
JDK/Mockito and injected-failure diagnostics remain visible. Self-review found no
assertion or lifecycle changes in the adapter; no production or maintained test
source changed. MySQL 8.4/full CI, admission, fan-out, publication/lifetime and
rollout gates remain open; master, migrations, remote refs and routing are unchanged.

## Native MySQL Timezone Contracts (2026-09-12 UTC)

The existing seven `JdbcAsyncJobStoreTimezoneIntegrationTest` methods ran with
both configured MySQL preparation modes on private native MySQL 8.0.43 with
Connector/J 9.7.0. Separate UTC and America/Los_Angeles JVMs each passed 14 tests,
with no failures, ignored tests, assumption skips or automatic reruns, in 2.860
and 2.856 seconds respectively.
Both native launchers exited zero. The preceding Maven run refreshed classes and
classpath but executed zero tests because the opt-in class setup was disabled;
its green build is compilation evidence, not the database result.

The temporary adapter substituted only MYSQL parameter selection and database
bootstrap; the original bootstrap otherwise unconditionally starts PostgreSQL too.
It installed the same V109 MySQL fixture into a fresh UUID-named schema after
verifying server version and the exact private data directory. The original
Parameterized runner, per-test session setup, ErrorCollector rules, test methods
and class teardown ran. The adapter checked 14 executions, exactly one bootstrap,
no skips/failures and a cleared fixture map. No temporal SQL, bindings, readback,
driver/session properties or store operations were mocked.
Independent read-only review approved this supplemental adapter and its limits.

Connections exercised UTC, Tokyo, independently shifted session-only and LOCAL
driver zones with explicit session-zone assertions. Independent numeric database
time bounds scheduling/readback and live-lease/heartbeat checks; retries, failed
replay and strict terminal cutoffs retain their absolute meaning. Epoch, maximum,
DST-adjacent values and out-of-range rejection remain covered. No future job was
claimed early and no live lease was reclaimed across these sessions. Preparation
modes are configured by the original fixture; this run does not independently
certify server-side preparation of every production SQL statement.

Artifacts are `/tmp/queue-mysql-timezone.aURJsj/native-utc.log`,
`native-los-angeles.log`, `NativeMysqlTimezoneVerification.java` and `timezone_probe.rb`;
the Maven build log is `/tmp/queue-native-timezone-compile-20260912.log`. The temporary
loopback server on port 45192 was shut down and its data directory removed. Existing
JDK/Mockito warnings remain visible. MySQL 8.4/PostgreSQL 16 execution, historical
non-UTC row interpretation and mixed-old/new-worker cutover remain unproven by
this matrix. No production/test source, migration, primary worktree, remote ref or
enrollment flag changed.

## Native MySQL Pool And Session Loss (2026-09-12 UTC)

Both existing MySQL `JdbcAsyncJobStorePoolIntegrationTest` methods passed on
private native MySQL 8.0.43 in 4.215 seconds, with no failures, ignored tests,
assumption skips or automatic reruns. The native launcher exited zero. The
preceding Maven run checked the build/classpath but skipped all four MySQL/PG
parameterized cases without the opt-in flag; it is not the database result.

One case exhausts the real one-connection Hikari pool after the production
runtime starts its handler. The other creates a worker account scoped to the
disposable queue table, locks that account, kills its actual worker connection,
and verifies reconnect remains unavailable without holding the pool permit.
Observed renewal failures took 258 ms and 261 ms respectively against the
configured 250 ms acquisition timeout. A separate datasource waits for natural
database-clock expiry and reclaims with the same worker ID but a new token at
attempt two. Restoring pool access rejects both stale heartbeat and completion,
preserves the complete replacement row and suppresses the stale done callback.
Only after releasing the original handler does its runtime capacity return; the
peer then completes with its winning payload. Pool teardown reports no borrowed
connections or waiting threads. No queue-row fence is claimed for business writes.

The temporary adapter invokes the unchanged public MySQL methods, replacing only
Testcontainers construction/lifecycle and connection metadata. Actual Hikari,
JDBC, account/connection operations, database time, executors, assertions and
method-owned cleanup remain intact. Server version and its exact private data
directory are checked before each UUID-named schema. No application account or
session is used. The launcher rejects missing/extra tests, skips and failures.
Independent read-only review found no must-fix adapter issue; the native server's
external cleanup and target-version limitations remain explicit.

Artifacts are `/tmp/queue-mysql-pool.nBX5L5/native-pool.log`,
`NativeMysqlPoolVerificationTest.java`, `NativePoolRunner.java` and `pool_probe.rb`;
the Maven log is `/tmp/queue-native-pool-compile-20260912.log`. The loopback server
on port 45193 was shut down and its entire private data directory, including the
test account, removed. Expected renewal-timeout/session-loss diagnostics and
JDK/Mockito warnings remain visible. This is forced session loss plus blocked
reauthentication, not a network blackhole, server restart, multi-host partition,
production headroom measurement or async-handler completion proof. Required
MySQL 8.4/PostgreSQL 16 CI, process-crash and sustained outage/soak gates remain
open. No production/test source, migration, primary worktree, remote ref or
enrollment flag changed.

## Native MySQL Worker-JVM Crashes (2026-09-12 UTC)

Both existing MySQL `AsyncJobQueueProcessCrashIntegrationTest` methods passed
against private native MySQL 8.0.43 before a later shutdown disk-space error.
The tests finished in 10.591 seconds, with no failures, ignored tests, assumption
skips or automatic reruns. The native launcher exited zero.
The preparatory Maven run checked the build/classpath but skipped all four opt-in
MySQL/PG cases; it is not the database proof. A temporary adapter substitutes only
Testcontainers construction/lifecycle and connection metadata, checks the server
version/private data directory before UUID schema creation, and invokes the
unchanged public MySQL methods. It rejects wrong test counts, skips and failures.

The actual fixture launches and forcibly terminates its own two separate worker
JVMs, both exiting 137. In the running-handler case, the parent first observes a
durable heartbeat renewal, kills the child, drains any pending row-lock writer,
rejects an early claim and waits for natural database-clock lease expiry without
rewriting timestamps. A replacement using the same worker ID receives attempt two
and a new token; old-token heartbeat, completion, failure and retry all fail without
changing its running row. The replacement completes and releases capacity. Both
attempts' business probes persist, explicitly demonstrating at-least-once effects.

The second child is killed after real DONE commit but before returning to the
runtime callback. A fresh runtime's two polls remain empty; the DONE/output row
and attempt one survive, with one business probe and zero callback probes. This
confirms the existing best-effort callback limitation, not durable publication or
automatic reconciliation. No new queue defect or production behavior change was found.

Artifacts are `/tmp/queue-mysql-crash.zuXkGP/native-crash.log`,
`NativeMysqlCrashVerificationTest.java`, `NativeCrashRunner.java` and `crash_probe.rb`;
the Maven log is `/tmp/queue-native-crash-compile-20260912.log`. The native log records
both child PIDs and output paths; their exits were awaited and a separate process
check found neither still running. The private loopback MySQL server on port 45194
was shut down and its data directory removed. At 02:43:40 UTC shutdown reported
`No space left on device` writing its buffer-pool dump and renaming a redo file,
then reported shutdown complete. After removal, `df` still showed 398 MiB free
and 100% capacity on the data volume; a later read showed 1.7 GiB available. This
is a real environment failure, not a
queue assertion failure or a clean database-shutdown result. The passing worker
tests must not be presented as server-crash durability evidence. Further disk-heavy
verification is gated on at least 5 GiB available; no unrelated data was deleted.
JDK/Mockito warnings remain visible. Independent read-only review approved the
adapter's scope; it did not certify the execution environment.
The child uses the application test classpath, not the independent JAR boundary.
This is worker death, not database death, partition, multi-host load, business
idempotency, admission or blob-lifetime proof. Target MySQL 8.4/PostgreSQL 16 CI and
broader rollout gates remain open. No production/test source, migration, primary
worktree, remote ref or enrollment flag changed.

## Native MySQL Checkpoint (2026-09-12 UTC)

Docker is not the only local database verification route: the installed Homebrew
MySQL 8.0.43 server ran in a new private temporary data directory on loopback port
45187, without using the installed service or an application database. Sandboxed
initialization crashed in `memory::Aligned_atomic` before queue SQL; a CPU
cache-line query was denied. Initialization succeeded with approved access outside
the sandbox. Sandboxed JDBC then failed with `Operation not permitted`, also before
SQL; the approved test execution connected normally. These are environment failures,
not failed queue assertions or a reason to change SQL, SSL, or retry settings.

A temporary JUnit adapter replaced only `MySQLContainer` construction/lifecycle and
connection configuration, checked the server data directory before creating each
fresh schema, and invoked the existing compiled branch contracts. JDBC connections,
DDL, queue operations and assertions were real. Four public MySQL-only methods ran
unchanged; reflection selected only the MySQL halves of four shared database helpers,
without pretending PostgreSQL ran. This is a one-off verification adapter, not a new
maintained backend, independent-JAR consumer run, or hosted Testcontainers result.

The final selection passed **eight contract groups**, with no skips or automatic
reruns, in 32.756 seconds: store/runtime/concurrent claim behavior; case, padding and
legacy-encoding identity; portable failure diagnostics; payload write rejection;
concurrent retention/replay; lease expiry after row-lock waits; contended renewal;
and performance smoke. Renewal contention completed 24 handlers with 267 competing
polls and 960 successful renewals. The smoke drained 1,000 jobs in 1,035 ms with 444
polls and zero poll failures. Neither observation establishes production capacity,
shared-pool headroom, a latency SLO, or sustained multi-host behavior.

Artifacts are `/tmp/queue-native-mysql.VJJKMl/NativeMysqlQueueVerificationTest.java`
and `contracts-expanded.log` in the same directory; the earlier two-group run is
not additive coverage. The temporary server was shut down after verification.
JDK/Mockito dynamic-agent and expected fault-injection warnings remain. Fresh
MySQL 8.4/PostgreSQL 16 CI, bounded query-plan evidence, independent-JAR real-DB
consumers, full Flyway adoption/upgrade and outage/soak gates remain open. No queue
source, migration, routing flag or application database changed.

## Wakeup Connection Ownership (2026-09-11)

A bounded library/transaction review found an independent P1 defect in the optional
PostgreSQL wakeup path. A producer using `TransactionAwareDataSourceProxy` correctly
committed the queue's REQUIRES_NEW transaction, but then borrowed the resumed
caller's business connection to publish the hint. Switching that connection to
auto-commit committed unrelated pending business writes, even if `pg_notify`
failed and the caller subsequently requested rollback.

The real HSQL reproducer passed its raw-DataSource control and failed all three
single/nested/transaction-aware-over-lazy controls: each business marker survived
rollback. Four tests, three failures, no errors/skips/reruns are recorded in
`/tmp/queue-wakeup-transaction-red.log`. An initial fixture compilation error was
corrected before that reproduction; it was not a production failure. HSQL
deliberately lacks the PostgreSQL notification SQL. Its SQL failure occurs after
the erroneous auto-commit and does not invalidate the transaction evidence.

Both wakeup constructors now bypass only outermost transaction-aware proxy layers,
with identity-based cycle detection, missing-target rejection and rejection of
directly encountered single-connection sources. Lazy/routing/pool wrappers and
shared queue/template/manager wiring are unchanged. Remaining wrappers must provide
independent, non-enlisted connection leases; hidden/custom sharing is not certified.
No SQL, Flyway checksum, retry/lease policy or enablement flag changes.

Coverage includes caller resource restoration and persisted queue/rollback state,
no startup borrowing, malformed proxy graphs, and configured-listener
UNLISTEN/restore/close ordering. A public-configuration PostgreSQL test checks
successful notification during both caller rollback and commit, including a
separate pre-commit observer. Its real-database execution remains pending with
Docker unavailable. The schema/admission/publication/lifetime and rollout gates
above remain open.

Verification: `/tmp/queue-wakeup-transaction-focused.log` passed 125 tests with
five opt-in skips. `/tmp/queue-wakeup-transaction-broad.log` passed 1,093 tests
with 31 opt-in skips across 81 suites; final XML inspection found no failures,
errors or reruns. The ordinary engine JAR rebuilt offline successfully; the
independent consumer passed 17 JPA-lane tests and 11 lean-lane tests, each with
seven real-database skips (`/tmp/queue-wakeup-transaction-consumer-jpa.log` and
`/tmp/queue-wakeup-transaction-consumer-lean.log`). These are separate selections,
not additive totals. The added PostgreSQL contract is compiled but unexecuted.
Formatting and diff checks passed, and independent read-only review found no
material patch or test-oracle defects. Existing deprecation/AspectJ warnings and
expected fault-fixture logs remain. No primary-worktree change or publication.

## Independent Queue Connections (2026-09-11)

A matching transaction manager and datasource are necessary but not sufficient
for REQUIRES_NEW isolation. A real HSQL regression using Spring's
`SingleConnectionDataSource` found that queue enqueue committed the suspended
caller's marker write; the later outer rollback could not undo it. The pre-fix
test failed with one persisted marker instead of zero
(`/tmp/queue-single-connection-red.log`). This is a configuration-specific defect,
not evidence that ordinary connection pools share active connections.

The store now rejects a directly visible single-connection source before opening
a queue transaction. A no-borrowing matrix covers raw, outer transaction-aware
and nested template proxies with supported JDBC managers. Existing manager/resource
identity validation and lazy/routing wrappers are unchanged; host-supplied sources
must still honor independent physical ownership. Hidden/custom sharing is not
certified. The HSQL regression now verifies both caller and queue tables remain
empty after rejection and rollback. No SQL, schema, retries or routing changed.

The focused JDBC configuration, HSQL JPA transaction and wakeup-source selection
passed 58 tests without failures, errors, skips or reruns
(`/tmp/queue-single-connection-focused.log`). Existing deprecation/AspectJ warnings
remain. This includes independent enqueue, enlistment and caller restoration
controls, not fresh MySQL/PostgreSQL execution or an admission/rollout gate closure.

The expanded queue/admin/asset selection passed 1,095 tests with 31 opt-in skips
across 81 suites and no failure/error/rerun entries
(`/tmp/queue-single-connection-broad.log`). The ordinary engine JAR rebuilt offline;
its independent JPA consumer passed 17 tests and the separate clean lean consumer
passed 11, each with seven real-database skips. Logs are
`/tmp/queue-single-connection-engine-verified.log`,
`/tmp/queue-single-connection-consumer-jpa.log` and
`/tmp/queue-single-connection-consumer-lean.log`. These are separate selections,
not additive totals. Root/consumer formatting and diff checks passed; independent
read-only review found no material findings. Docker remains unavailable, and no
primary-worktree change, publication or rollout took place.

## Parent Diagnostic Containment (2026-09-11)

Four new parent regressions exposed a secondary failure: after a real meter-type
collision, a throwing Logback warning appender could reject an acknowledged child
or replace the original uncertain-enqueue exception. Both queue and default-off
Quartz routes reproduced it: two assertion failures and two errors, no skips or
reruns (`/tmp/queue-parent-diagnostic-red.log`). Schedulers are mocked in these
cases; this is a local outcome-preservation regression, not database uncertainty
or durable parent recovery evidence.

`GenerateMultiLocalizedAssetJob` now contains nonfatal fallback logging failure.
Six controls preserve complete/partial mappings, original error identity, exact
route invocation counts and no fallback, plus direct fatal metric/logger errors
including ThreadDeath subclasses. The appender is scoped to the calling thread
and specific warning, then detached with the previous logger level restored.
The existing private final production logger is unchanged. Wrapped fatal graphs,
global instrumentation and JVM fail-stop are outside this correction.

The parent, real-HSQL admission-characterization, API routing and direct producer
selection passed 76 tests in four suites without failures, errors, skips or
reruns (`/tmp/queue-parent-diagnostic-focused.log`). Expected ThreadDeath
deprecation and existing AspectJ warnings remain. The HSQL characterization still
demonstrates the unresolved partial-parent/duplicate-replay behavior; it has not
become a recovery guarantee. No routing defaults, admission protocol, SQL, schema,
core library or deployment changed.

## Historical Scope And Baseline

This is the review ledger for the renewed queue review and Quartz migration work.
Use it to resume substantive work rather than repeating status checks.

- Work in the dedicated queue worktree, not the primary master checkout.
- Branch at review start: `wip/queue-store-refined`, `38dd1e06bb`.
- Four queue commits were preserved and published. At review start they were 207
  commits behind local master `167bf1e818`.
- At review start, local master had unrelated editor changes. Queue changes belong
  in the queue worktree regardless of current primary-checkout cleanliness.
- At that baseline, master owned Flyway versions through V108, including V100. Both queue migrations
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

## Historical Review Milestones

These checkmarks record completed review passes, not current merge or production
acceptance. The current readiness gates above remain authoritative.

- [x] Reproduce and fix material findings with regression tests; independently
  review each patch and record remaining limitations.
- [x] Document the current job inventory and incremental migration design,
  distinguishing implemented support from proposed work.
- [x] Refresh against then-current master with a preserved backup and reviewable
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
  At this review stage the adapter still compensated an arbitrary enqueue exception; if the
  database committed before that exception, later successful task finish does not
  clear its error, and repair reports already-finished. Later containment below removes
  this compensation, not the need for durable admission. Do not enable the feature
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

## Transaction Binding Follow-Up

- Reproduced a configuration-dependent autocommit leak: the queue used datasource
  A while its transaction manager used B. An exception after the real insert
  rolled back B but left one queue row in A. The pre-fix focused reproduction
  failed the zero-row assertion; no production database was involved.
- Construction now rejects mismatched datasource resources, unsupported managers,
  nested transaction-aware manager proxies and unverifiable JPA factories. JPA
  requires HibernateJpaDialect and matching Spring factory datasource metadata.
  Shared lazy/routing wrapper identity is preserved rather than comparing URLs
  or arbitrarily unwrapping pools. Production wiring requires a non-null manager;
  package-local SQL test seams remain. The validation performs no database access
  and adds no operation-time query. Configuration must remain stable after startup.
- Real Hibernate tests use the production PollableTask mapping without application
  transaction advice. They verify physical JDBC/JPA connection sharing, independent
  public enqueue despite an outer task rollback, insert rollback and both injected
  commit-failure outcomes. A lost acknowledgement propagates even though the row
  committed; a rollback before commit leaves no row. Isolated HSQL passed 6/6;
  HSQL plus MySQL 8.4/PostgreSQL 16 passed 18/18. These are not atomic admission
  acceptance/recovery tests or historical schema-upgrade tests.
- Independent review caught the nested-proxy resource-key mismatch and the need
  to validate JPA factory metadata, not only JpaTransactionManager.getDataSource().
  A companion test explicitly pairs JDBC B with manager B while Hibernate uses A;
  construction rejects it before either datasource opens a connection.
  The wiring test also no longer double-registers configuration properties or
  depends on Java parameter-name retention to choose between duplicate beans.
- Final clean reactor verification on 2026-09-08: Spotless and the focused
  `-Pno-local-config` suite with database/performance tests enabled passed
  **617 tests, zero failures/errors/skips/flakes**. The final JPA fixture accounts
  for 21 tests across three databases; XML contains no failure or rerun elements.
  The earlier 614-test run also passed before adding the companion regression.
  Same local smoke workload: PostgreSQL 1,000 jobs in 1,433 ms (698 jobs/s,
  365 polls); MySQL 2,298 ms (435 jobs/s, 600 polls). No poll/claim failures;
  these are not production capacity or latency guarantees. Existing AspectJ,
  deprecated-API and Mockito dynamic-agent warnings remain. No container startup
  failure occurred, but the earlier intermittent handshake fault remains open.
- V109 SQL, public enqueue propagation, feature flags, business services and
  primary-master edits are unchanged. Reservation DDL still requires confirmation
  of applied queue-migration history; no database or routing was changed.
- Remote verification/publication for this follow-up was blocked by Secretive SSH
  signing refusal. Last fetched origin/master remains `7fcc341457`; local master
  `466ceb92bb` adds no migration beyond V108. Recheck remote heads before publishing
  the rewritten four-commit stack; do not claim this follow-up has reached origin.

## Contended Renewal Follow-Up

- The previous runtime contention and throughput tests explicitly disabled
  heartbeats. Added a separate MySQL/PostgreSQL test using production executor
  and heartbeat-scheduler construction: four runtimes, six handlers each,
  ten-second leases and 250 ms renewal intervals. Each simulated node has a
  separate metric registry. No production code or migration SQL changed.
- All 24 handlers stay gated while a real transaction briefly locks their rows.
  Query entry/return observation proves all 20 scheduler threads encounter the
  database locks; the observer does not itself delay execution. Database-side
  minimum lease headroom bounds this artificial burst before and after waiting.
  A committed SQL snapshot then proves every old deadline has passed while all
  current leases remain live with unchanged tokens and attempt count one.
  Independent SQL claims, including one after that proof, must return nothing.
- Every job must renew repeatedly, finish once and reach DONE on its first
  attempt. Heartbeat failures are checked while handlers remain gated, not after
  cancellation can legitimately race completion. Cleanup preserves the original
  failure, attempts every resource cleanup, and awaits only initialized executors.
  An initial compile error from treating SimpleMeterRegistry as AutoCloseable was
  corrected; separate per-node registries remove duplicate-gauge fixture warnings.
- Final clean `-Pno-local-config` reactor run with database/performance tests
  enabled passed **618 tests, zero failures/errors/skips/flakes** on 2026-09-08.
  Spotless passed; XML contains no failure/rerun elements and no database-startup
  failure was logged. Each backend completed 960 renewals across its 24 handlers;
  PostgreSQL ran 257 competing claims and MySQL 249 before the final post-deadline
  check. Every job finished on attempt one. The independent reviewer found no
  remaining test issues after the headroom and cleanup refinements.
- Same existing 1,000-job throughput smoke, with heartbeats disabled: PostgreSQL
  1,344 ms (744 jobs/s, 357 polls); MySQL 2,250 ms (444 jobs/s, 629 polls), zero
  poll/claim failures. Existing AspectJ/deprecated-API/Mockito warnings remain;
  neither a production benchmark nor a fix for the intermittent startup fault is
  claimed. The work stays on base `7fcc341457` with V109 unchanged; SSH signing
  still prevents remote verification/publication, and primary master is untouched.
- This is bounded same-host evidence with DriverManager connections, not a
  production throughput result, pooled-connection saturation test or outage soak.

## UTC Timestamp Follow-Up

- Disposable MySQL tests reproduced early future-job claims and premature live-lease
  reclaim when two workers use different session/driver time zones. For example,
  a job due one hour later was claimed immediately, and a ten-minute lease was
  stolen immediately by another session. Both MySQL prepared-statement modes were
  affected. Independent numeric database-epoch queries establish the timing; this
  is not a comparison against an unsynchronized JVM clock.
- The store now binds and reads JDBC 4.2 temporal values explicitly: UTC
  LocalDateTime for MySQL DATETIME and OffsetDateTime for PostgreSQL TIMESTAMPTZ.
  MySQL samples UTC_TIMESTAMP(6); PostgreSQL keeps clock_timestamp(); the embedded
  HSQL clock is normalized to UTC. Every timestamp predicate and mutation uses
  this boundary, including retries, failed replay, monitoring and retention.
  No connection session is mutated and no extra query is added to a store operation.
- A tentative UTC Calendar fix resolved the modern MySQL cases but still exposed
  historical date shifts in PostgreSQL/HSQL. HSQL 2.7.4 also disagrees between its
  JDBC 4.2 binding and SQL calendar before 1582. Rather than maintaining ancient-date
  driver workarounds, the shared queue API now rejects values before Instant.EPOCH;
  the inclusive upper bound remains 9999-12-31T00:00:00Z. This applies to both
  stores, fails before mutation, and does not silently clamp inputs. It is an
  explicit validation compatibility change, not a data migration.
- Regression coverage compares independent SQL values with Java readback, including
  cross-session schedules, database time, live leases/renewals, relative and absolute
  retries, failed replay and strict terminal deletion cutoffs. Boundary cases cover
  epoch, DST-adjacent instants, microseconds and the maximum, plus out-of-range
  rejection. The embedded store/dialect suite passes 41 tests in both isolated UTC
  and America/Los_Angeles JVMs, without changing global timezone during tests.
- The expanded MySQL/PostgreSQL matrix passes 28 tests in each isolated JVM,
  including client/server preparation and PostgreSQL forced binary results.
  The clean combined `-Pno-local-config` reactor passes 648 tests with no
  failures/errors/skips/flakes. A default-preserving `mojito.test.timezone` Maven
  property makes the non-UTC check repeatable: the documented command passes
  69 focused tests, with America/Los_Angeles confirmed in Surefire properties and
  no failure/rerun XML elements. Spotless passes. These remain local disposable
  database results, not a historical application-schema upgrade or production soak.
- The final default-UTC reactor rerun on the new Maven configuration also passes
  all 648 tests with no failures/errors/skips/flakes. Both database contention
  cases finish all 24 jobs on attempt one with 960 successful renewals. Same
  heartbeats-disabled 1,000-job smoke: PostgreSQL 1,298 ms (770 jobs/s, 349 polls),
  MySQL 2,171 ms (461 jobs/s, 651 polls), no poll/claim failures. The preceding clean
  run measured 711/294 jobs/s respectively; this variability is not a production
  performance comparison. Existing AspectJ/deprecated-API/Mockito warnings and
  the earlier intermittent container handshake risk remain; neither was fixed
  by this change. The independent reviewer found no blocking code/design issues.
- V109 SQL, business timestamp mappings, default-off flags and primary master are
  unchanged. The prior-schema and timestamp-provenance questions remain rollout
  gates. Passing new-write tests cannot establish compatibility with previously
  written non-UTC rows or authorize mixed old/new workers.

## Shared Pool And Lost-Lease Follow-Up

- Confirmed unnecessary renewal traffic after definitive lease loss. The pre-fix
  runtime regression invoked three scheduled ticks after the first `false` and
  observed three store calls instead of one. The runtime now keeps a monotonic,
  per-execution lost flag and skips later queries. It is set before telemetry;
  database exceptions still retry because ownership/commit outcomes are uncertain.
- Handler execution and capacity accounting are unchanged. No forced interruption
  or early slot release is introduced. Existing token-fenced finalization and
  cleanup remain authoritative; a heartbeat racing with a successful `DONE` response
  cannot suppress its completion callback. Unit tests cover independent executions,
  repeated rejection, exception-to-success recovery and a throwing metrics backend.
- A new real Hikari/MySQL/PostgreSQL regression holds every shared connection after
  the production runtime starts its handler, observes an actual acquisition timeout,
  then uses a separate datasource to reclaim after database-clock lease expiry and
  persist the winner. Pool recovery does not let the stale heartbeat or completion
  mutate that row or run `onJobDone`; the handler then releases runtime capacity.
- Documented shared-pool headroom explicitly: dedicated heartbeat threads do not
  reserve connections. Account for all queues, request/ORM traffic, nested
  `REQUIRES_NEW`, and the optional PostgreSQL listener. Timing margin must include
  acquisition, scheduler, lock, query and commit delays, not just heartbeat interval.
- Independent review found no remaining issues in this patch or its tests.
  Spotless and the clean focused queue/admin/asset/PollableTask reactor suite passed
  **653 tests, zero failures/errors/skips/reruns**, with real-database and performance
  tests enabled (3 min 51 s). The runtime and pool tests also passed **111 tests**
  under `-Dmojito.test.timezone=America/Los_Angeles` (22 s); Surefire XML confirms
  the non-UTC JVM setting and no reruns. Existing AspectJ, deprecation and Mockito
  warnings remain; the full application suite was not run.
- The combined pool test observed 255 ms/MySQL and 258 ms/PostgreSQL acquisition
  timeouts against the configured 250 ms limit. Both databases preserved the winning
  attempt-2 `DONE` row and rejected stale heartbeat/completion with no callback.
  The contention test also passed 960 renewals across 24 handlers per backend,
  with 244 PostgreSQL and 225 MySQL competing polls. The separate heartbeat-disabled
  1,000-job smoke measured 363 jobs/s/PostgreSQL and 207 jobs/s/MySQL, with no poll or
  claim failures. These variable local samples are not production capacity claims.
- This change adds no schema or default-on behavior and does not claim exactly-once
  business effects or a production pool-sizing result. V109 remains collision-free
  against local master's V108; the SQL is unchanged.

## Execution Telemetry And Blob Lifecycle Follow-Up

- Reproduced two execution-path failures: a handler meter collision left a successfully
  generated job without `DONE`, and the shared generation timer threw from resource
  close after business work succeeded. Nonfatal handler timing/counters and shared
  Quartz/queue generation timing now preserve output and the original business
  exception. Completion metrics no longer manufacture callback failures. JVM-fatal
  errors still propagate; no retry, transaction or routing policy changed.
- Independently reproduced six failing repair telemetry cases. Repair counters now
  preserve successful DONE/FAILED/already-finished responses and existing lookup or
  finish exception classifications/causes. New tests include meter-type collisions,
  nonfatal provider errors and VM-fatal/ThreadDeath propagation. Isolated handler,
  generation and queue-flow tests passed 29 tests; repair tests passed 22. The final
  four changed test classes pass 53 tests, including two additional checks that
  missing repository-locale data still fails before generation.
- Fresh clean reactor verification with `-Pno-local-config`, real database and
  performance switches enabled passed 675 tests across 58 suites: zero failures,
  errors, skips or Surefire rerun/flaky entries. This includes MySQL/PostgreSQL
  contracts, wakeups, contention, Hikari starvation/recovery fencing and bounded
  performance smoke. Log: `/tmp/queue-execution-telemetry-final-verification.log`.
  The first reactor attempt failed because Docker's socket refused connections;
  after starting Docker Desktop, the full clean command was rerun successfully.
  A Docker credential-helper timeout fell back successfully during that run;
  existing compiler/AspectJ/Mockito warnings remain. Spotless and `git diff --check`
  passed. These are local correctness/smoke results, not production capacity evidence.
- The blob lifecycle review confirmed that queued input and winning output can expire
  independently of queue-row retention. At that checkpoint, database `MIN_1_DAY` meant 84,600
  seconds by default, creation-relative; rewriting as PERMANENT did not clear an
  existing expiry. Optional prefix cleanup can use a shorter age regardless of row
  TTL. Cloud retention depends on external policies, and Azure/database fallback
  deletion leaves a database copy that can be backfilled again. No live provider
  policy was inspected, and no shared blob-storage behavior changed in this patch.
- Updated the migration/admission design: use fresh authoritative keys pinned from
  first write, inventory backend ownership and repair/replay obligations, and prove
  reference-aware retirement across fallback copies before GC. A longer TTL or
  disabled queue retention alone is insufficient. Corrected the old MVP rollout
  text: executable shadowing must isolate side effects, not dual-execute production
  Quartz and queue work. These lifecycle requirements remain unimplemented gates.

## Runtime Telemetry Control-Flow Isolation

- Reproduced seven lifecycle failures with actual meter-type collisions or removal-listener
  errors: stopped poll loops, lost recovery scheduling, masked startup errors, shutdown
  interruption, failed gauge registration and incomplete gauge removal.
- Independently reproduced twenty dispatch/execution failures in a 21-test suite. Metrics
  after claim could strand an entire batch; after persisted DONE they could suppress the
  callback and manufacture a retry attempt; failure metrics could overwrite the business
  error or prevent retry/terminalization. Failed heartbeat-cancel telemetry could leak
  in-flight capacity, and processing timing could suppress completion-triggered polling.
  The fatal-completion propagation control test already passed before the fix.
- Centralized only the runtime's counter/timer recording behind best-effort helpers, with
  unchanged names, tags, timing units and amounts. Gauge registration/removal now isolates
  failures per meter and tracks only successfully registered runtime gauges. Nonfatal
  telemetry failures do not enter business-failure handling or interrupt control flow.
- Nested cleanup guarantees capacity release and a wakeup attempt even if cancellation or
  timing throws. JVM-fatal errors still propagate. A further red/green test covers a fatal
  error from poll-failure telemetry: it must release the active-poll latch before escaping,
  without scheduling another poll. The existing lost-lease regression now injects a real
  meter listener failure and verifies one failed metric attempt and no further renewals.
- Independent lifecycle review identified five more failing cases: fatal cancellation or
  cancellation metrics skipped executor/heartbeat shutdown, fatal registration left earlier
  gauges behind, fatal removal skipped remaining gauges, and a broken telemetry warning
  escaped the metric guard. Shutdown now attempts every owned-resource cleanup stage;
  registration rolls back returned handles and removal defers fatal propagation until all
  gauges have been attempted. Nonfatal logging failures in the metric guard are contained.
- Two combined-shutdown failure regressions ensure cleanup cannot downgrade a fatal error
  to an ordinary exception. The first fatal failure remains primary; distinct secondary
  failures are suppressed while all cleanup stages are attempted in the original order.
- Isolated verification passes 145 tests across the existing runtime suite and two new
  telemetry suites (36 new tests). Final clean reactor verification with `-Pno-local-config`
  and real-database/performance switches enabled passed **711 tests across 60 suites**:
  zero failures, errors, skips or Surefire rerun/flaky entries. Spotless and diff checks
  passed. Existing compiler/AspectJ/Mockito warnings remain. Log:
  `/tmp/queue-runtime-telemetry-release-verification.log`. Intermediate 704- and 709-test
  green runs predate the final combined-shutdown fix and are superseded by this run.
- The existing disposable 1,000-job performance smoke drained at approximately 1,029 jobs/s
  on PostgreSQL and 575 jobs/s on MySQL, with zero poll or claim failures. This uses the
  existing four-runtime fixture with four workers each, DriverManager connections, no business work
  and no heartbeats; it is not a production capacity or before/after performance claim.
  Hikari starvation/recovery also passed on both databases: stale heartbeat and completion
  rejected, no stale callback, zero leaked capacity and the winner preserved. No JDBC
  transaction, lease predicate, retry budget, SQL or rollout flag changed.

## Coordinator Failure Preservation

- Startup rollback now covers fatal as well as ordinary failures after runtime creation,
  including failures in later queues. Constructor rollback attempts both owned scheduler
  and executor cleanup without replacing the initiating error with an ordinary cleanup
  exception. A cleanup fatal error takes precedence; distinct secondary failures remain
  suppressed for diagnosis.
- Coordinator shutdown attempts every registered runtime, then clears the runtime and
  disabled-consumer maps and marks itself stopped. Ordinary explicit-stop failures remain
  logged and swallowed as before; the first fatal error propagates after cleanup attempts.
  Nonfatal startup/shutdown metrics or logging failures cannot short-circuit that cleanup.
  Executor drain still precedes heartbeat shutdown inside each runtime. `stop(Runnable)`
  callback behavior is unchanged: it runs only when `stop()` returns normally.
- The new `AsyncJobQueueCoordinatorFailureTest` reproduced **22 failures among 25 tests**
  against the pre-fix classes. All 25 pass against an isolated compilation of the patch;
  the existing coordinator and Spring context-close suites separately pass 21 tests.
  An independent source/test review found no material issue in the patch. Red/green logs:
  `/tmp/queue-coordinator-failure-red.nVE40m/junit-red.log` and `junit-patch.log` in the
  same directory.
- Final verification, 2026-09-09 UTC: Spotless and a clean focused reactor run with
  `-Pno-local-config`, real database contracts and performance smoke enabled passed
  **736 tests across 61 suites**, with zero failures, errors, skips or Surefire
  rerun/flaky entries. Log: `/tmp/queue-coordinator-cleanup-verification.log`.
  This includes queue/admin/asset/pollable tests and MySQL/PostgreSQL pool-recovery
  fencing, timezone and contention tests. The 1,000-job smoke measured approximately
  925 jobs/s on PostgreSQL and 509 jobs/s on MySQL, with no poll or claim failures,
  using the previously documented fixture, not a production or comparative benchmark.
  Existing compiler/AspectJ/Mockito warnings remain. No queue-store transaction,
  business effect, SQL or rollout default changed.

## Pull-Run Retry Source Audit

The source audit found a nullable-tag mismatch, now reproduced with real services and
an isolated HSQL database. `LocalizedAssetGenerationService` forwards an omitted
`outputBcp47tag` unchanged. Both TM generation paths pass it to
`PullRunAssetService.replaceTextUnitVariants`. Deletion uses a derived repository
finder with Java `null` (`IS NULL`), while the old insertion interpolated the same value
into a quoted SQL `%s`, producing the string `'null'` instead of SQL `NULL`.

Repeated shared generation from freshly deserialized original input failed with a
duplicate key for an unchanged translation; changing the translation retained both
old and new variant IDs. The explicit-tag control passed: **two of three tests failed**
against old classes. The direct persistence suite separately reproduced five failures
among six cases, including failure to clear omitted-tag rows and SQL syntax errors
for a quoted tag. These are committed lineage reads without a test-level transaction,
not an in-memory queue approximation. Red logs:
`/tmp/localized-generation-retry-red.1AotG9/junit-red.log` and
`/tmp/pull-run-retry-red.2txKsy/junit.log`.

The insert now binds all five values per row, including a genuine SQL `NULL` for an
omitted tag and literal text for supplied tags. It retains one multi-row statement
per at-most-1,000-ID batch and the existing local wall-clock/millisecond creation
timestamp contract. No DDL, queue UTC timestamp behavior or transaction propagation
changed. Legacy string `'null'` rows are deliberately not rewritten or deleted as if
they were SQL `NULL`; the two values cannot be safely conflated without knowing the
original input.

The JDBC regression matrix exercises the woven production batch method on HSQL,
MySQL 8.4 and PostgreSQL 16, including client/server prepared-statement modes for
both real databases. It checks null/literal-null/quoted values, BIGINT IDs, a full
1,000-row batch, local timestamp fields and rollback of an invalid batch without
rolling back an earlier committed batch. All 20 matrix cases passed in a separate
`America/Los_Angeles` JVM with deliberately different database session timezones
(`/tmp/pull-run-binding-corrected.tACXGO/junit-la.log`). This narrow fixture does not
certify the complete ORM schema or historical migrations on PostgreSQL.

Initial combined runs exposed test-fixture issues, not new production fixes:
closing an isolated Spring context left singleton transaction advice pointing at a
closed manager, and another isolated context contaminated legacy Quartz/Pollable
singletons. The two real-service regressions now use the ordinary shared
`ServiceTestBase` context, with an embedded database override applied to the whole
verification process. PostgreSQL JDBC also overrode the fixture's startup timezone;
the matrix now sets and verifies its distinct session timezone after each connection
opens. The affected-service rerun passed 21 tests, with the existing opt-in MySQL
cleanup benchmark skipped, including the previously affected asynchronous
source-less generation test (`/tmp/pull-run-lineage-affected-services.log`).

Final verification, 2026-09-09 UTC: Spotless and a clean focused reactor run with
`-Pno-local-config`, the process-wide embedded datasource, real-database and queue
performance switches enabled passed **777 tests across 67 suites**. Of 778 selected
tests, only the existing opt-in
`PushPullRunCleanupServiceTest.mysqlCleanupDeleteBenchmarkLegacyNativeSqlAgainstCriteria`
was skipped; there were zero failures, errors or Surefire rerun/flaky entries.
All 30 new regression cases passed. The command also covers the queue/admin/asset/
pollable suites, affected TM/pull-run/cleanup tests and real MySQL/PostgreSQL
contracts, contention, timezone, pool-recovery and wakeup tests. Log:
`/tmp/pull-run-lineage-release-verified.log`. A preceding sandboxed attempt was
stopped when Testcontainers' localhost connection was denied; the successful run
used the required local-network permission. No production settings changed.

The existing 1,000-job queue smoke measured about 688 jobs/s on PostgreSQL and
491 jobs/s on MySQL, with no poll or claim failures, using the previously documented
no-business-work fixture. These numbers are neither a benchmark of the lineage
insert change nor production capacity evidence. An independent final static review
found no material patch issue. Existing compiler/AspectJ/Mockito warnings remain;
frontend, full-application, historical schema-upgrade and sustained multi-host soak
checks were not run. V109 SQL and default-off routing are unchanged. Remote
authentication was not retried after the previously recorded Secretive refusal;
this follow-up is local until remote verification and publication succeed.

Deletion and each insert batch use default `REQUIRED` transaction advice, not
`REQUIRES_NEW`. They commit separately in the current nontransactional generation
path with AspectJ advice active, but would join an ambient transaction. Queue
handlers execute outside queue-store transactions, and lineage writes do not carry
the queue lease token. Admission deduplication and fenced output publication cannot
make these business effects retry-safe.

This fixes sequential replay of newly written associations, not all lineage races.
SQL uniqueness with a nullable tag does not prevent duplicate null-tag associations
from overlapping workers. Separate deletion and insert batches still permit stale
replacement or partial lineage after a crash. The proposed first canary excludes
pull-run tracking until those effects are fenced or otherwise reconciled. That
per-request exclusion is not implemented; keep the umbrella queue flags disabled.

The same-job output-storage regression below now covers sequential runtime retries.
Next test overlapping old/new attempts after lease loss. Reconcile historical
literal-null rows separately using original inputs, not a blanket conversion.

## Same-Job Output-Storage Retry

`AssetLocalizeAsyncJobLineageRetryIntegrationTest` uses the ordinary shared service
context, real generation/TM/lineage and PollableTask/blob services, and the actual
queue submission adapter, handler and runtime. Polling is controlled by the test;
no test transaction hides the first attempt's committed business writes. The
in-memory store remains a non-durable control. Opt-in JDBC cases apply V109 to
disposable MySQL 8.4/PostgreSQL 16 queue databases with explicit JDBC transactions;
business entities and blobs still use the service-test HSQL database. This split
does not prove whole-application PostgreSQL ORM compatibility or atomic admission.

Three scenarios run against each queue store: omitted-tag replay after a failed
private-output write, omitted-tag replay with a changed translation after an output
write's acknowledgement is lost, and an explicit-tag/changed-translation control.
Each checks that the first attempt leaves one committed lineage set, no canonical
output and an unfinished/error-free PollableTask. The retry retains the queue job,
task and pull-run parent identities, reloads the unchanged stored input, replaces
lineage with exactly the selected variant and publishes only the second attempt's
output after `DONE`. A written-but-unacknowledged first output remains private and
distinct; these tests do not implement orphan retirement or make a TTL safe.

The focused run passed five tests containing nine scenarios, with no skips or
failures (`/tmp/queue-lineage-runtime-focused-2.log`). An isolated, woven copy of
the pre-fix `PullRunAssetService` caused four of five tests to fail on the first
committed association's literal-null tag; the explicit-tag control passed
(`/tmp/queue-lineage-runtime-old-20260909/junit-red.log`). The production worktree
was not altered for that red control. An initial test compilation error used
try-with-resources for a non-AutoCloseable Micrometer registry; explicit cleanup
corrected the fixture. No production code or migration changed in this follow-up.

Independent test review caught missing termination waiting on the manually created
executor. The fixture now waits for graceful shutdown, attempts interruption if
needed, and asserts bounded worker termination before closing metrics or disposing
queue databases. This avoids silently accepting teardown while a worker still uses
the shared Spring service context. The test does not enable automatic polling or
heartbeat scheduling, and the lost-acknowledgement injection is not a process kill.

Final clean-build verification, 2026-09-09 UTC: Spotless and the combined focused
reactor command with `-Pno-local-config`, a process-wide embedded datasource and
real-database/performance switches enabled passed **782 tests across 68 suites**.
Of 783 selected tests, the existing opt-in MySQL cleanup benchmark was skipped;
there were zero test failures/errors or Surefire rerun/flaky entries. All nine new
runtime scenarios passed in this run. Log: `/tmp/queue-lineage-runtime-release.log`.
Independent review found no remaining material issue after the teardown correction.
Existing compiler/AspectJ/Mockito warnings remain; this is not the full application
suite, production rollout verification or a crash/concurrent-worker proof.

This was not a clean first-attempt infrastructure run: Testcontainers replaced one
MySQL instance after a two-minute startup failure before queue migration SQL ran.
The driver timed out reading server capabilities; captured server logs showed final
TCP port 3306 readiness, while `/tmp/queue-lineage-runtime-stall.thread.txt` shows
the test in connection-readiness retries. By the time separate internal-TCP and
host probes ran, the container had been removed. The internal probe therefore
failed and host timeouts cannot establish the cause. Do not report that diagnostic
attempt as a successful host-versus-container comparison, or hide the startup
failure behind zero Surefire reruns. No timeout, SSL setting or retry policy was
changed. The existing smoke measured about 893 jobs/s on PostgreSQL and 510 jobs/s
on MySQL with no poll/claim failures; it is not a lineage benchmark or an SLO.

Independent concurrency source review identifies the next deterministic regression:
pre-create pull-run parents, use an explicit tag, pause A after selecting variant A
but before replacement, let its lease expire, let B select a newer variant and fully
publish, then resume A. Assert both B's output and exactly B's committed lineage
after A drains. This interleaving has not been reproduced by the sequential tests.
A row lock alone cannot impose winner order: B-then-A is still serializable, while
locking only deletion also leaves later batch writes unprotected. First-creation
collisions and nullable uniqueness are separate problems, not explanations for this
late-writer case.

The preferred design direction for review is to stage exact used-variant IDs with
immutable attempt output, then apply only the durable winner's complete lineage set
through publication/repair, including empty results. An alternative is an atomic
authority check plus complete in-place replacement. Either needs durable scope/
generation authority and a crash/replay contract; a detached lease check, parent
`@Version`, random lease token ordering or resettable attempt count is insufficient.
Keep Quartz/old writers and historical rows in that contract. No authority schema,
winner-only publication or pull-run eligibility switch is implemented here.

## Stale Lineage Reproduction And Containment

The formerly source-only stale-writer interleaving is now reproduced using the real
generation, lineage, blob and PollableTask services with two actual queue runtimes
and the in-memory store. Parents are pre-created and the output tag is explicitly
`fr-FR`. A is paused after selecting the old variant, B reclaims A's expired lease
and fully publishes the new variant, then A resumes. B's `DONE` payload, output and
finished task survive; A's queue completion is rejected. Nevertheless, A replaces
the committed lineage with its old variant. The final exact-lineage assertion
fails on both the first execution and Surefire's configured rerun. This is a
confirmed business-write fencing defect, not a nullable-tag/creation race.
Log: `/tmp/queue-stale-lineage-repro.log`. A pinned
[historical diagnostic patch](../reproductions/queue-stale-lineage.md) preserves
the failing contract outside the normal suite without counting it as a passing
test or silently ignoring a regression. This reproduction uses HSQL business
storage and in-memory claims, not database-backed reclaim or a process kill.

Containment keeps any request with non-null `pullRunName` on Quartz at both
producer seams, including empty/whitespace values because the business path also
treats those as tracking requests. A direct queue submission rejects before task,
blob or queue writes. Consumers reject already-persisted tracked inputs before
generation; the initial containment used the finite retry policy to record terminal
failure and finish the original PollableTask with an error. The subsequent
[permanent rejection](#unsupported-tracking-permanent-rejection-2026-09-10) avoids
spending that retry budget. They do not automatically replay
on Quartz, clear the tracking field, publish output or repair existing lineage.
This is an intentional compatibility restriction, not the full fencing fix or
rollout approval. Stop/drain old workers before deploying it: an already-running
old handler cannot be made safe by installing a new producer check.

`AssetLocalizeAsyncJobOutputRetryIntegrationTest` replaces the previously named
lineage runtime fixture. The three output-storage retry scenarios remain, now for
untracked inputs that the queue supports. It also seeds historical tracked work
through real task/input/queue storage and verifies terminal rejection without
generation, output publication, or changes to existing lineage on each queue
store. The shared generation and direct persistence tests still cover sequential
tracked retries for Quartz. None of these safety checks certifies concurrent
tracked execution or removes the admission/retention/migration gates below.

The first focused run selected 79 tests and failed three backend variants on a
test expectation, reproduced on the configured rerun: PollableTask intentionally
serializes runtime exceptions as a generic unexpected-error message rather than
exposing the underlying rejection. The corrected fixture checks that existing
public error contract and separately checks the precise queue error/stack. No
production exception mapping was changed. Log:
`/tmp/queue-tracked-containment-focused.log`.

Final verification on 2026-09-09 UTC: Spotless and the clean focused reactor with
`-Pno-local-config`, real MySQL/PostgreSQL contracts and performance smoke selected
798 tests across 68 suites: **797 passed, zero failures/errors and one existing
opt-in MySQL cleanup benchmark skipped**. XML contains no Surefire rerun/flaky
entries. All 14 added unit tests and the six runtime test methods containing twelve
scenarios passed. Independent reviews found no material issue in the test/probe
isolation or containment paths. Existing compiler/AspectJ/Mockito warnings remain.
Log: `/tmp/queue-tracked-containment-release.log`. This is not the full application
suite, a historical schema upgrade or rollout approval. No queue SQL, migration,
default-off setting or primary-master file changed.

The run was not a clean first-attempt infrastructure run: Testcontainers replaced
one MySQL instance after startup timed out before test SQL. This time diagnostic
probes reached the same instance before disposal. Container
`40319cf62e6dfa38c617fe36967230efca7e5ce07441169ade167e9bbce9a2fe`
was running, with final TCP 3306 readiness at `07:27:36Z` and port 65089 published
on both `0.0.0.0` and `[::]`. Internal `mysqladmin --protocol=tcp` immediately reached
authentication (access denied without credentials, not an authenticated SQL check).
At `07:28:56Z`, host `127.0.0.1:65089` timed out after three seconds, while
`localhost` resolved to `::1:65089` and returned greeting bytes `490000000a382e34`
in about two milliseconds. This narrows that occurrence to the host address-family/
forwarding path rather than queue SQL; it does not identify the underlying Docker
Desktop/network cause or explain every earlier PostgreSQL SSL EOF. Later paired
probes at `07:29:34Z` ran after automatic disposal and are not repeat confirmation
(`/tmp/queue-tracked-containment-startup-probes.log`). No timeout, SSL policy or
production/test address-family setting was changed.

The unchanged 1,000-job, no-business-work smoke measured about 987 jobs/s on
PostgreSQL and 537 jobs/s on MySQL, with zero poll/claim failures. Both renewal
contention cases completed 960 successful renewals across 24 handlers. These are
local smoke/correctness results, not a containment performance improvement or SLO.

## Untracked Effects And Lineage Protocol

The untracked path still changes shared derived storage. Okapi `TranslateStep` and
portable TM generation both construct `TranslatorWithInheritance`, whose locale
lookup uses `TextUnitDTOsCacheService` with `UpdateType.ALWAYS`. A missing or changed
entry writes a PERMANENT blob under `TEXT_UNIT_DTOS_CACHE/asset/{id}/locale/{id}`;
the optional Smile backend uses the same prefix with a `.smile` suffix. Inheritance
may read/refresh several locales. These cache writes are not attempt-private or
queue-token-fenced. This is an existing service contract, not by itself evidence
of stale-cache corruption or a requirement to disable ordinary cache refresh.

Source-less/branch-selected generation also reads `MultiBranchStateService`.
On cache miss it reconstructs legacy branch state from the database and writes a
PERMANENT version-0 `MULTI_BRANCH_STATE` blob. Its existing service test explicitly
checks that read-side population. `AzureDatabaseFallbackBlobStorage` can additionally
backfill a missing cloud object during `getString`/`getBytes`. This provider path
is source-audited and covered by its existing mocked-provider tests, not verified
against live Azure routing or policies. The generation code inspected here does
not schedule provider jobs or notifications, but this is not a universal purity
certificate for every filter, storage adapter, or future extension.

Executable shadowing must isolate these dependencies too, not only the final
output and pull-run tracking. The migration plan now names the cache/branch-state/
fallback boundaries. The lower-layer timer/cache-counter failures subsequently
reproduced and corrected below are independent of the outer queue telemetry
guards; no complete-call-graph or global instrumentation safety claim is made.

The [tracked lineage protocol](async-job-queue-lineage.md) now specifies a concrete
proposal: monotonic per-scope acceptance authority, immutable output/variant
manifests, queue DONE winner selection, one atomic business lineage/receipt/pointer
publication, idempotent task projection, and reference-aware retirement. It includes
empty sets, supersession/replay, old Quartz/synchronous writers, historical
constraints and a bounded fault matrix. Independent review found no material
design issue under its stated gates. Latest-accepted semantics, exclusive scope
ownership, pointer-reader compatibility and operating limits still need owner
approval. No part of that protocol, admission bridge or new DDL is implemented.

### Portable FormatJS Round-Trip Correction

The real untracked-generation audit exposed a separate common converter defect.
With `mojito.converter=portable` and no platform filter options, a valid descriptor
`{"home":{"defaultMessage":"Home","description":"Navigation label"}}` parses
as message `home`, but rendering unconditionally reparsed configured generic JSON
as `home/defaultMessage` and `home/description`. A selected French translation
therefore threw `UNKNOWN_SKELETON_SLOT: Unknown JSON message: home`. The service
failure repeated on Surefire's configured rerun in
`/tmp/queue-untracked-effects-verification.log`. This path is shared with Quartz;
it is not a JDBC queue, cache corruption, or retry-budget defect.

Parsing and rendering now use the same default-versus-configured JSON decision.
Default rendering patches the flat value or descriptor's `defaultMessage` under
the exact extracted key, preserving the source template and metadata. Untranslated
removal drops whole extracted messages, including inside canonical wrappers;
excluded messages remain. Unknown paths/synthetic variant slots still fail closed.
Explicit filter options and comments retain their existing generic-JSON behavior.
Whole ICU messages are translated; descriptor variants/placeholders remain metadata,
not a newly introduced per-plural-slot update protocol.

`MojitoLocalizationWorkflowFormatJsTest` exercises eleven contracts for both null
and empty options. Running those 22 cases against archived pre-fix common classes
fails fourteen; controls already pass. Log: `/tmp/queue-formatjs-red.log`.
The initial complete common-module run selects 533 tests: 524 pass, nine existing
external/disabled checks skip, with no failures/errors. The skips are Slack,
Phabricator, Evolve credential-dependent cases and the disabled OpenAI client-pool
test, not converter tests. Log: `/tmp/queue-formatjs-common-verification.log`.

Independent review then identified duplicate-wrapper ambiguity: tree parsing took
the last `messages` object but the patch scanner selected the first. The proposed
initial fix could therefore return untranslated source. Two added parameter cases
reproduce missing rejection against that initial fix; BOM/multibyte-offset controls
already pass (`/tmp/queue-formatjs-duplicates-red.log`). Default FormatJS parsing
now uses per-read strict duplicate detection, also reached by renderer validation;
it does not mutate the shared mapper. Duplicate wrapper/message/descriptor/metadata
fields fail with `INVALID_FORMATJS` instead of last-value-wins behavior. This is an
intentional malformed-input compatibility tightening, not an automatic repair or
a change to explicitly configured/comment-driven generic JSON. The final dedicated
suite has 26 parameter cases. The earlier 873-selected broad green run predates
this additional correction and is not final verification.

The audit fixture separately needed two corrections: use the cache's actual
unknown-field-tolerant mapper when reading serialized DTOs, and compare before/after
variant IDs instead of forgetting the source-locale variant created by fixture
setup. These were test mistakes, not production fixes. The original failing JSON
input remains in a dedicated real-service test alongside XLIFF/Okapi and portable
properties; it was not removed to obtain a green suite. Direct blob reads establish
cache population/refresh without a cache-service lookup causing the observed write.
These cases check no pull-run creation or additional variant IDs, not arbitrary
absence of all database mutations or overlapping-writer cache safety.

Final verification on 2026-09-09 UTC: the clean focused reactor with
`-Pno-local-config`, MySQL/PostgreSQL contracts and performance smoke selected
877 tests across 75 suites: **876 passed, zero failures/errors, one existing
opt-in MySQL cleanup benchmark skipped**, and no Surefire rerun/flaky entries.
All three real untracked-generation paths and the 26 converter cases pass.
Log: `/tmp/queue-untracked-final-verification.log`. After adding explicit controls
that configured/comment JSON remains permissive following a strict default read,
the complete common module was rerun: **528 passed of 537 selected, nine existing
external/disabled skips, zero failures/errors/reruns**. Log:
`/tmp/queue-formatjs-common-release.log`. These two runs overlap; do not add their
totals as distinct tests. Spotless, diff whitespace and all 44 lineage-design file
links pass. Independent review found no remaining material issue after the
duplicate-field correction.

The 24-handler contention cases recorded 960 PostgreSQL and 956 MySQL successful
renewals; all jobs completed under the fixture's required safety assertions. The
unchanged no-business-work 1,000-job smoke measured about 1,009 jobs/s PostgreSQL
and 561 jobs/s MySQL with zero poll/claim failures. No container-start failure was
reported in this run; this does not close earlier startup/network investigations.
These are local bounded correctness/smoke measurements, not performance improvements,
production capacity or multi-host soak. Existing compiler/AspectJ/Mockito warnings
remain. No queue-store SQL, V109 migration, default-off flag, primary-master file,
deployment or routing changed. Local master still ends at V108 with no dirty
migrations; remote freshness/publication remains unverified after the earlier SSH
refusal. Admission, tracked publication, lifetime and rollout gates remain open.

## Direct Generation And Cache Telemetry

The manual `TMService.generateLocalizedBase` timer could fail after real Okapi
localization had produced valid output. A gauge/timer name-and-tag collision or a
nonfatal registry callback error then replaced the successful return. The cache's
direct lookup counter could similarly fail after a successful hit or miss. These
are shared Quartz/queue business-service paths, not queue transaction failures.

`TextUnitDTOsCacheTelemetryTest` selects 44 JSON/Smile and hit/miss cases. Against
archived pre-fix webapp classes, 16 fail and 28 controls pass:
`/tmp/queue-cache-telemetry-red.log`. The fixture stubs successful cache reads to
isolate lookup instrumentation and preserves the concrete format tags. Its real
storage-exception paths verify failure identity; existing cache codec tests remain
responsible for successful serialization/deserialization. Healthy names/tags/counts,
registration collisions, callback/increment failures and JVM-fatal controls are
covered. A separate static review found no material code issue; warning-logger
failures are guarded but not fault-injected by this fixture.

Seven timer cases extend the standard real-service generation fixture using a
private TMService spy copy and a local registry, without rewiring the cached Spring
singleton. Before the fix, five of the thirteen fixture tests fail (four assertion
failures and one error), repeated on Surefire's configured rerun:
`/tmp/queue-tm-timer-red-executed.log`. This includes actual output replaced by a
metrics error and fatal telemetry being suppressed beneath an ordinary business
exception by try-with-resources. An earlier test-only compilation attempt used
try-with-resources on this version's non-AutoCloseable registry; switching test
cleanup to `@After` corrected the fixture, not production.

The timer now measures monotonic elapsed time independently of registry callbacks
and records it best-effort after generation. Repository identity access and actual
generation remain outside the metrics guard. Both direct paths contain nonfatal
recording and diagnostic-logging failures without changing the result or original
business exception. JVM-fatal errors and ThreadDeath still propagate; after a fatal
generation error the timer is skipped rather than masking the original failure.
Existing metric names, tags and healthy counts remain. No global `@Timed` advice,
transaction boundary, cache key/codec/TTL or business-write fencing changed.
Independent timer review found no material issue and checked registration defaults
against the local Micrometer sources. The timer tests inject registration failures,
not direct `Timer.record` or warning-logger failures, and assert translated content
rather than byte-for-byte output equality. These are scoped regression guarantees,
not a universal instrumentation certification.

Spotless and the focused cache/TM/shared-generation run pass all 93 tests with no
failures/errors/skips: `/tmp/queue-generation-telemetry-focused.log`. Final clean
queue/admin/asset verification on 2026-09-09 UTC selects 928 tests in 76 suites:
**927 pass, zero failures/errors, one existing opt-in MySQL cleanup benchmark
skipped, no Surefire rerun/flaky entries**. Log:
`/tmp/queue-generation-telemetry-release.log`. This includes the new 44 cache and
seven real-generation timer cases, existing codecs and both real database contracts;
it supersedes the earlier 877-test run for the changed webapp paths.

Both 24-handler contention cases pass with 960 successful renewals. The unchanged
heartbeat-disabled/no-business-work 1,000-job smoke measures 1,043 ms PostgreSQL
(about 959 jobs/s) and 1,801 ms MySQL (about 555 jobs/s), with zero poll/claim
failures. These are bounded local smoke results, not a performance improvement,
production capacity estimate or Quartz comparison. No container-start failure was
reported in this run; earlier infrastructure investigations remain open. Existing
compiler/AspectJ/Mockito warnings remain, with additional Java 21 ThreadDeath
removal warnings from explicit legacy-fatal compatibility guards and controls.
Those warnings are disclosed, not treated as failed tests or silently suppressed.
V109 and default-off behavior are unchanged.
Primary master has unrelated active edits, including a locale-aware integrity-check
call elsewhere in TMService; none were modified, staged or rebased here. Remote
freshness and publication remain unverified after the prior SSH refusal. Admission,
tracked publication, lifetime and rollout gates are still open.

## Uncertain Enqueue Compensation Containment

The old asset submission adapter combined preparation and enqueue in one error
handler. `AsyncJobQueueJpaTransactionIntegrationTest` now invokes the production
asset/queue submission adapters around actual HSQL, MySQL and PostgreSQL commits.
All six pre-fix cases attempted forbidden task-failure compensation: three after
a committed queue insert lost its acknowledgement, and three after the same
connection-failure SQL state followed an actual rollback. Each fails on both the
initial execution and configured rerun in
`/tmp/queue-admission-unknown-red-executed.log`. The previous compilation-only
attempt needed a boxed-ID assertion correction; it was not runtime evidence.

These cases commit the real PollableTask mapping before enqueue, but mock the
task-service facade and blob boundary to isolate the compensation invocation.
They verify one insert attempt, distinct durable row outcomes, retained task ID,
original JDBC failure cause and no bound resources. They are not full application
admission, blob or execution tests.

The adapter now completes input storage and payload serialization before entering
the enqueue boundary. Only those preparation failures may finish the task with an
error. Once enqueue is invoked, a nonfatal exception records bounded
`assetLocalizeAsyncJob.schedule{result=outcomeUnknown}`, logs the task ID and
rethrows without task mutation, input deletion, enqueue retry or Quartz fallback.
Best-effort logging cannot replace the original nonfatal exception; JVM-fatal
errors, including ThreadDeath subtypes, still propagate. Existing generic enqueue
and caller `failed` counters describe invocation failure, not proof of rollback.

This is intentionally conservative for definite rollbacks or failures before a
connection is acquired: a task/input can remain orphaned rather than risk poisoning
a committed job. A successfully completed worker may already have finished the
task before the submission exception unwinds; the adapter preserves that state too.
No timeout, historical error cleanup, request-key, HTTP status or return-handle
contract changes. Parallel Quartz parents can still fail before retaining an
accepted child's mapping; ordinary timeout cleanup can still race delayed jobs.
Durable admission, parent reconciliation and lifetime gates therefore remain open.

`AssetLocalizeAsyncJobOutputRetryIntegrationTest` separately exercises real generation,
input/output storage, task services and runtime with both worker completion orderings:
before the submission exception unwinds and afterward. The fault is injected after
the actual store returns; database variants reuse MySQL/PostgreSQL queue fixtures
with HSQL business storage. Persisted task errors remain empty, input is unchanged,
published output matches the winning attempt, and one job completes on one attempt.
This is a store-return-seam simulation, not the JDBC commit fault or a process kill.
Independent review found no issue in its assertions or resource cleanup.

The final focused run passes all 85 tests across commit contracts, real execution,
submission failure stages and no-fallback routing:
`/tmp/queue-admission-unknown-execution.log`. It includes failed payload construction,
input-storage/compensation failures, unknown-outcome meter plus logger failure,
nonfatal Error and fatal-error propagation. Independent review found no remaining
issue in the production stage separation, exception guards, unit fixture restoration
or physical commit-fault checks.

Final clean verification on 2026-09-09 UTC selects 941 tests across 76 suites:
**940 pass, zero failures/errors, one existing opt-in MySQL cleanup benchmark
skipped, no Surefire rerun/flaky entries**. Log:
`/tmp/queue-admission-containment-release.log`. Spotless and whitespace checks pass.
Both real-database 24-handler contention cases meet their safety assertions, with
959 PostgreSQL and 960 MySQL successful renewals. The unchanged no-business-work,
heartbeat-disabled 1,000-job smoke records 978 ms PostgreSQL (about 1,022 jobs/s)
and 1,756 ms MySQL (about 569 jobs/s), with zero poll/claim failures. No container
startup failure is reported; this does not close earlier infrastructure risks or
establish production capacity, full-schema upgrades or multi-host soak.

Compiler/AspectJ/Mockito warnings remain, including the explicit ThreadDeath
compatibility guard/subtype test's Java 21 removal warnings. No SQL, V109 numbering,
default-off routing, primary-master work or deployment changed. This verification
supersedes the earlier 928-test result for the changed submission paths. Preserve
the four-commit structure with a pre-rewrite backup and exact tree equality; remote
freshness/publication is still unverified after the prior SSH refusal.

## Async Request Asset Identity

The direct and parallel HTTP producers used the URL asset for lookup/metrics but
accepted a different non-null body asset ID. Shared generation reads the body ID;
the parallel parent copies it into every locale child. This is an admission identity
inconsistency on both Quartz and queue routes, not a demonstrated authorization bypass.

Both endpoints now reject mismatches with HTTP 400 before lookup, metrics, input
mutation or either scheduler. Omitted/null IDs are still filled from the path and
matching IDs remain valid. Synchronous and pseudo-localization already execute
against the URL asset and are unchanged. No existing queued inputs are rewritten,
no task/blob/queue schema changes, and no durable request-key or repository ACL
contract is introduced. This malformed-request tightening applies even when queue
routing is disabled; default-off does not imply literally zero behavior changes.

`AssetWSAssetIdentityTest` uses standalone MVC with the project ObjectMapper plus
direct calls across Quartz, queue, tracked-Quartz and parallel routing. Against
unchanged production code, eight mismatch cases fail and twelve valid-input controls
pass; the eight repeat on the configured rerun in
`/tmp/queue-asset-identity-red-boundary.log`. Earlier fixture attempts hit a Java
generic-inference compile error and unconfigured legacy task-serialization advice,
not additional production defects. The fixture mocks the response task/schedulers,
uses a local registry and does not reconfigure global AspectJ state or assert live
authorization/storage behavior. It verifies no admission collaborator calls or
meters for rejection, unchanged rejected DTOs, and one correct route for success.

Spotless and all 105 focused identity/routing/admission/fan-out/error-contract tests
pass without failures/errors/skips: `/tmp/queue-asset-identity-focused.log`.

Independent review found no blocking issue. The Java restclient builds the URL and
body ID from one argument (`AssetClient`, single and parallel methods); both Java
pull commands use that API. Rust `commands.rs` also uses the same resolved asset ID
for request construction and submission. No inspected frontend caller or documented
workflow relies on conflicting IDs. External/custom clients are unverified and may
observe the new 400 response. These are controller-boundary tests, not executable
locale requests or end-to-end HTTP authorization/generation tests.

The clean combined run selects 965 tests in 78 suites: **964 pass, zero
failures/errors, one existing opt-in MySQL cleanup benchmark skipped, no Surefire
rerun/flaky entries**. It finished 2026-09-09 at 09:50:08 UTC; log:
`/tmp/queue-asset-identity-release.log`. This includes real MySQL/PostgreSQL queue,
commit-fault and generation contracts. Both 24-handler contention fixtures pass
with 960 successful renewals (228 PostgreSQL and 232 MySQL competing polls).
The unchanged 1,000-job/no-business-work/heartbeats-disabled smoke took 1,043 ms
PostgreSQL and 1,793 ms MySQL, with zero poll/claim failures; this is not a
performance improvement, production capacity or multi-host soak result.
No container-start failure is reported, without closing earlier infrastructure
risks. Existing compiler/AspectJ/Mockito and previously disclosed ThreadDeath
removal warnings remain. V109, rollout flags and primary master are unchanged.
Remote publication remains unverified after the prior SSH refusal. Fold this
guard into the asset integration chunk with a backup and exact tree preservation;
the admission/fencing/lifetime rollout gates below remain open.

## Portable Failure Diagnostics (2026-09-09)

The independent core review reproduced a PostgreSQL failure without changing DDL:
an exception summary containing NUL causes `requeueAfter` to fail with
`invalid byte sequence for encoding "UTF8": 0x00`. The row remains RUNNING until
lease recovery instead of following the requested retry delay. MySQL accepts the
same character, so a MySQL-only test would miss the failed PostgreSQL transition.
The former 4,000-code-unit substring also splits supplementary Unicode characters
at its boundary; unpaired surrogates have no portable JDBC representation.

Error normalization now renders NUL and unpaired surrogates as ASCII Unicode
escapes, preserves valid Unicode and whitespace, and stops at 4,000 UTF-16 code
units without splitting a surrogate pair or generated escape. Normalization is
idempotent. This changes diagnostics only: no payload rewriting, secret redaction,
SQL, retry budget, transaction boundary or rollout flag changes. Existing retained
rows are not rewritten by a migration.

Pre-fix regression evidence is in `/tmp/queue-portable-diagnostics-red-executed.log`:
20 selected tests, four failures and one error, all five reproduced on the
configured rerun. The PostgreSQL error is the actual retry UPDATE rejection; the
MySQL failure is an expected-normalized-versus-raw diagnostic mismatch. Three unit
failures cover escaping and truncation. The earlier `-red.log` was a test fixture
compile failure (Micrometer's registry is not AutoCloseable), not a queue defect.

New real MySQL/PostgreSQL checks cover retry and terminal writes with NUL,
unpaired surrogates, and surrogate pairs at both sides of the length boundary.
They read raw `last_error` SQL values as well as records so record normalization
cannot hide a persistence defect. The runtime fixture verifies a malformed-message
handler retries once then fails at attempt two, exposes the normalized diagnostic
to its callback, and lets a healthy job finish without lease-expiry recovery.
The first focused run passed 234/235 tests; PostgreSQL caught a new raw-read test
binding its BIGINT key as varchar. That fixture now binds a long, like the
production store. This was a test defect, not another queue defect. The clean
combined run then passed **970 of 971 selected tests across 78 suites**, with one
existing opt-in cleanup benchmark skipped, zero failures/errors and no Surefire
rerun/flaky entries. Log: `/tmp/queue-portable-diagnostics-release.log`, completed
2026-09-09 18:58:44 UTC. Both 24-handler renewal fixtures passed with 960 renewals;
the unchanged 1,000-job smoke took 1,155 ms PostgreSQL and 1,849 ms MySQL, with no
poll/claim failures. These are bounded correctness/smoke checks, not a performance
improvement or production capacity result.

Independent review found no production normalization defect and requested bounded
worker termination in the new fixture. Cleanup now joins the executor before
registry/container teardown and preserves primary failures with suppressed cleanup
errors. Final focused verification after that test-only change passes all 235
selected tests without failures/errors/skips or reruns:
`/tmp/queue-portable-diagnostics-focused-final.log`, completed 19:00:26 UTC.
Spotless passes in `/tmp/queue-portable-diagnostics-spotless.log`.
Compiler/AspectJ/Mockito and previously disclosed ThreadDeath removal warnings
remain; no new production warnings are introduced by normalization.

An independent source audit also established the proposed single-module library
boundary in `async-job-queue-library.md`. The core has no business-package imports,
but lacks a supported external bootstrap and independent POM/migration lifecycle.
This is a proposal, not an extracted library or new production-readiness claim.
The cached origin/master reference advanced separately to `567d2ddea4` during
this run (three commits beyond the queue's `7fcc341457` base). This isolated fix
does not rebase onto those changes, access remote authentication or touch dirty
primary master; merge-target refresh remains separate. V109 remains collision-free
against that cached target's highest V108 migration. Applied schema history is
still unverified.

## External Consumer Bootstrap (2026-09-09)

The next bounded library step uses existing APIs, not a new facade.
`example.queue.AsyncJobQueueExternalBootstrapTest` composes the public store
configuration, coordinator, submission and inspection services with explicit
properties binding and host-provided JDBC/transaction/scheduler/metrics beans.
It does not import Mojito Application, business services, private queue types or
use reflection. The production implementation and DDL are unchanged.

The two real-database cases demonstrate independent queue admission after a
caller's marker write rolls back, execution outside an active transaction,
terminal opaque payloads, inspection, and coordinator stop/restart. Shared host
infrastructure remains usable after coordinator stop; fixture teardown waits for
the shared scheduler and observed handler threads. Private heartbeat-thread
termination remains separately covered by coordinator lifecycle tests. Other
cases check default-off startup without infrastructure, missing/mismatched
transaction-manager rejection before JDBC access, invalid configuration, and
cancellation of a previously scheduled poll after partial startup failure.

All **90 selected tests pass**, including seven new external-consumer cases,
with no failures/errors/skips or Surefire reruns. Log:
`/tmp/queue-external-bootstrap-focused.log`, completed 2026-09-09 19:16:04 UTC.
Spotless passes in `/tmp/queue-external-bootstrap-spotless.log`. The initial run
passed five of seven new tests; two test assertions expected different exception
wording and were corrected. No production defect or new API was required.
Independent review found no blocking issue and explicitly limited the new
fixture's cleanup proof to observed workers and the shared scheduler.

`async-job-queue-library.md` and QUEUE-06 now distinguish verified composition
from remaining extraction gates. Optional wakeup/maintenance and producer-only
composition, an independent Maven artifact/consumer, no-AspectJ build and true
Flyway adoption are still unverified by this fixture. It runs on the current
webapp build and applies existing SQL explicitly. Existing compiler/AspectJ/Mockito
and ThreadDeath warnings remain. Admission, lineage, lifetime and canary gates
are unchanged; no publication, rollout or merge readiness is implied.

## External Producer And Wakeup Composition (2026-09-09)

The next independent library-boundary step exposes only
`AsyncJobQueueWakeupConfiguration` for explicit public Spring imports. Listener,
notifier and transaction internals remain unchanged and package-private. This
does not enable the queue or notifications by default, alter DDL, or change
independent enqueue semantics.

Three new `example.queue.AsyncJobQueueExternalWakeupTest` cases cover disabled
optional configuration without infrastructure, a no-handler producer waking an
independent PostgreSQL consumer context, and notification failure with a
registered-but-disabled producer handler. The wakeup test waits for the first
empty poll and committed LISTEN setup, requires the received/triggered metric,
and completes before the next one-minute periodic poll can explain success.
Each context owns its scheduler/registry; no direct consumer-coordinator trigger
or internal queue API is used. Producer checks assert no scheduled polls, runtime
gauges, handler execution or listener activity.

The failure case keeps real JDBC storage but injects an unavailable notification
connection. Admission returns a durable ID with a queued, attempt-zero row and
records a failed hint, not a failed enqueue. After producer shutdown, a fresh
polling-only context completes the job without a delivered hint. Teardown waits
for the listener, host scheduler and observed handler threads; this is not a
multi-host or sustained network-outage test.

The first focused run passed all **86 selected tests** without errors, failures,
skips or Surefire reruns, including both new real-PostgreSQL cases and the existing
external MySQL/PostgreSQL bootstrap, connection/listener/notifier, configuration
and submission tests. Log: `/tmp/queue-external-wakeup-first.log`, completed
2026-09-09 19:35:33 UTC. The expanded configuration/transaction/coordinator/
lifecycle/drain selection passed **164 tests** before and after final review,
with no failures/errors/skips or Surefire reruns. Final log:
`/tmp/queue-external-wakeup-final.log`, completed 2026-09-09 19:40:41 UTC.
Spotless passes in `/tmp/queue-external-wakeup-spotless.log`.

Independent review identified a GC-sensitive teardown assertion, not an observed
production failure: the context releases singletons while listener gauges use
weak references. The fixture retains the public SmartLifecycle beans through
post-close gauge assertions with a reachability fence. The startup/wakeup elapsed
bound also begins before consumer refresh so an ordinary later poll cannot explain
success. No material findings remain in that bounded review. Existing build/
AspectJ/Mockito warnings remain; this does not prove a weaving-free artifact.

QUEUE-06 now keeps scheduled retention/status-reporting composition, an independent
Maven artifact/consumer and true migration adoption as distinct remaining library
gates. Mojito admission, fan-out, lineage, blob lifetime and canary gates remain
open. V109, rollout settings and the primary dirty checkout are untouched.

## External Scheduled Maintenance (2026-09-09)

The remaining external composition proof uses existing public retention and
status-reporting components with host-owned `@EnableScheduling`, properties,
JDBC/transaction and scheduler/metrics wiring. No production API, scheduling
default or DDL changes are needed. The maintenance graph intentionally does not
import the coordinator or register handlers.

Five new `example.queue.AsyncJobQueueExternalMaintenanceTest` cases establish
global-off startup without infrastructure, no implicit scheduling from component
imports alone, and `disablescheduling` suppression without JDBC/scheduler calls.
The two real-database cases exercise actual scheduled callbacks on MySQL and
PostgreSQL. With retention off, status/ready/expired-lease gauges are populated
and every fixture row remains. With retention explicitly enabled and batch size
one, a single pass removes only the oldest DONE and FAILED rows from the configured
queue. Other old terminal rows remain for later passes; recent terminal rows,
ready queued work, an expired running row and another queue are untouched.
The pass completes well before its one-hour repeat interval. Context closure
cancels registered scheduled tasks and the fixture awaits scheduler termination.

Independent review found that batch-size-one alone could mask missing age/queue/
status predicates, because eligible rows sorted ahead of protected fixtures. A
protected-only pass now follows removal of the remaining eligible fixtures and
requires zero deletions, no failure counters and exact surviving IDs. A single-
thread scheduler barrier drains the immediate callbacks before these assertions;
an early status sample cannot stand in for a completed cleanup pass.

This is also an explicit deployment contract: configured queues are maintained
even without a handler or with `consumer-enabled=false`. That flag is not a
maintenance kill switch. Pure producer hosts can omit maintenance imports; these
components do not provide PollableTask reconciliation or blob cleanup.

The first focused run passed all **30 tests** without failures/errors/skips or
Surefire reruns. Log: `/tmp/queue-external-maintenance-first.log`, completed
2026-09-09 19:53:48 UTC. These fixtures apply the existing V109 script explicitly,
not through real Flyway adoption. Independent packaging, deployment ownership,
migration history and all business/rollout readiness gates remain separate.

The first expanded run selected 187 tests and finished with one Surefire flake,
not first-attempt green. The unchanged external wakeup producer test failed at
`startDatabase` while opening the connection before applying any queue DDL:
PostgreSQL returned EOF during `ConnectionFactoryImpl.enableSSL`. The automatic
rerun passed. Evidence: `/tmp/queue-external-maintenance-focused.log`, completed
2026-09-09 19:56:46 UTC. This repeats the existing QUEUE-05 handshake risk and
does not establish its root cause. No sleeps, connection retries or SSL-policy
changes were added to hide it.

After strengthening the exclusion proof, the final selection passed all **187
tests** without failures/errors/skips or Surefire reruns. Log:
`/tmp/queue-external-maintenance-final.log`, completed 2026-09-09 19:59:33 UTC.
Spotless passes in `/tmp/queue-external-maintenance-spotless.log`. Independent
review confirmed the protected-only pass and scheduler barrier address the
coverage finding, with no remaining material issue in this bounded change.
Existing build/AspectJ/Mockito warnings and the separately recorded handshake
flake remain. This closes external maintenance composition, not independent
packaging or production readiness. The production code, V109 and rollout flags
remain unchanged.

## Portable Payload Write Integrity (2026-09-09)

The next core review reproduced silent payload corruption, independently of
migration adoption. The write validator accepted unpaired UTF-16 surrogates;
both MySQL and PostgreSQL JDBC drivers stored `bad?input` instead of the supplied
test payload. MySQL also stored raw NUL, while PostgreSQL rejected it during
INSERT with UTF8 `0x00`. The red selection ran 22 tests with three expected
failures; the existing Surefire rerun repeated all three failures. Evidence:
`/tmp/queue-portable-payload-red.log`, completed 2026-09-09 20:12:11 UTC.

Shared new/replacement payload validation now rejects NUL and unpaired surrogates
without escaping, normalizing or copying accepted strings. The existing length
limit is explicitly 1,000,000 UTF-16 code units. Valid Unicode, whitespace,
empty payloads and literal ASCII escape sequences remain accepted. Submission,
store writes, handler results and replay replacements share this validation.
Public service validation precedes store/wakeup calls; direct JDBC store methods
retain their transaction and pre-validation reads/locks, so rejection is a
no-durable-mutation guarantee, not a no-JDBC-activity guarantee.

Record hydration retains its original null/length compatibility checks. Already-
stored MySQL NUL rows remain readable and can progress using null replacements.
Tests cover inspection, mixed healthy/legacy claim batches, expired-lease reclaim,
retry, failure, inspection replay readback and completion. Explicitly echoing a
legacy-invalid payload as a new handler result remains invalid; handlers should
use null-preserving transitions when the payload is unchanged. No existing rows
are rewritten, and already-corrupted surrogate values cannot be recovered from
the database's replacement characters. Unicode-capable database and connection
encodings remain deployment prerequisites, not something this scan can repair.

All **124 focused tests pass** without failures/errors/skips or Surefire reruns.
They include the generalized in-memory/HSQL invalid-write contract, unchanged
full-row snapshots after rejected replacements, service-before-store validation,
record/result compatibility and real MySQL/PostgreSQL round trips at the size
boundary. Log: `/tmp/queue-portable-payload-focused.log`, completed
2026-09-09 20:19:16 UTC. Independent source review found no material remaining
issue in this bounded change. This changes input validation, not queue/business
transaction semantics, V109 or rollout flags.

The clean broader selection then passed **993 tests across 81 suites**, with
zero failures/errors or Surefire rerun/flaky elements and one explicitly opt-in
MySQL cleanup benchmark skipped (994 total). This includes real MySQL/PostgreSQL
queue correctness, contention, external composition and performance smoke tests,
plus admin/localization, PollableTask, pull-run, cleanup, cache and blob coverage.
Log: `/tmp/queue-portable-payload-release.log`, completed 2026-09-09 20:25:00 UTC.
Formatting also passes. Existing compiler removal, AspectJ and Mockito warnings
remain; this is not an independently built or weaving-free library verification.

The 24-handler contention runs recorded 960 PostgreSQL renewals with 263 competing
polls, and 959 MySQL renewals with 244 competing polls. Every row completed at
attempt one, no duplicate handler ran, and the pre-release heartbeat-failure check
was zero. The MySQL run subsequently logged one rejected heartbeat during handler
completion. A completion/renewal overlap is a candidate explanation, not a proven
root cause; add a deterministic phase-controlled regression before changing
lease-loss metrics or suppressing the warning. The smoke runs drained 1,000 jobs
in 1,339 ms on PostgreSQL and 1,820 ms on MySQL without poll failures. These use
empty business work with heartbeats disabled, not a production capacity model.

## Completion And Renewal Interleaving (2026-09-09)

The portable-payload run's warning led to a deterministic runtime regression.
The original code could renew after its own transition cleared the lease, before
the store returned success or while the completion callback was executing. It
then counted/logged the expected rejection as lease loss. Captured scheduled
invocations also still queried after worker cleanup if cancellation failed.
The initial six-transition matrix ran 42 cases: 15 failed, all repeated by the
existing Surefire rerun. Log: `/tmp/queue-heartbeat-transition-red.log`, completed
2026-09-09 20:38:32 UTC. This establishes the code defect and a mechanism that
explains the prior stress warning; it does not reconstruct that run's precise
thread interleaving.

The runtime now uses per-execution lifecycle bookkeeping. Renewals remain active
through the post-handler resolution phase, without holding its state monitor
across store calls, telemetry or callbacks. A definitive heartbeat rejection
stops further queries immediately but defers failure classification while a
transition is pending. Only a confirmed successful fenced transition clears that
deferred signal and stops renewals before callbacks. Rejected/uncertain outcomes
retain the signal; an earlier reported rejection and heartbeat exceptions are
not erased by later success. Cleanup prevents new queries even if future
cancellation throws. In-flight responses after cleanup remain reportable unless
the transition was confirmed successful. An unbounded stuck transition can delay
classification, so database wait bounds and in-flight/transition monitoring
remain necessary. This is runtime lifecycle/telemetry correction, not stronger
business fencing or unknown-commit recovery.

The expanded matrix has 78 cases across DONE, delayed/absolute handler requeue,
failure retry, terminal failure and exhausted handler requeue. It includes a
pending transition and a heartbeat on separate threads, late responses after
cleanup, callback concurrency and exact callback counts, earlier lease loss,
unknown commit responses, cancellation failure and fatal deferred-telemetry
cleanup. An older runtime test explicitly expected the false failure counter;
its expectation now rejects that false signal while retaining callback checks.
Real-database contention tests also check the failure counter after completion,
not just while handlers remain gated.

All **223 focused runtime tests pass** without failure/error/skip or Surefire
rerun/flaky elements. Log: `/tmp/queue-heartbeat-transition-focused-final.log`,
completed 2026-09-09 20:45:26 UTC. Intermediate development runs exposed the stale
metric expectation and test-helper checked-exception/Mockito re-stubbing issues;
these were corrected before the final run. V109, SQL, transaction boundaries and
rollout flags are unchanged.

The subsequent clean broader selection passed **1,071 tests across 82 suites**,
with zero failures/errors or Surefire rerun/flaky elements and one opt-in MySQL
cleanup benchmark skipped (1,072 total). Log:
`/tmp/queue-heartbeat-transition-release.log`, completed 2026-09-09 20:49:22 UTC.
Each real-database contention run completed all 24 handlers at attempt one with
960 renewals, zero duplicates and zero heartbeat-failure counters after
completion (244 competing polls on PostgreSQL; 240 on MySQL). No completion-time
rejected-heartbeat warning occurred in either run. The heartbeat-disabled,
empty-business-work smoke runs drained 1,000 jobs in 1,004 ms on PostgreSQL and
1,648 ms on MySQL with zero poll failures; these remain local smoke measurements,
not capacity claims. Independent final source review found no actionable issue.
Formatting passes; existing compiler/AspectJ/Mockito warnings remain. This does
not close migration adoption, durable admission, business fencing or staging-soak
gates, and does not prove unrelated disposable-database startup issues fixed.

## Stored Payload Identity Decoding (2026-09-09)

Reviewing the admission/repair boundary found application-mapper coercion in the
current persisted task-ID envelope. A corrected red selection ran 44 tests with
four expected failures (all repeated by Surefire): JSON `pollableTaskId: 42.9`
selected task 42 in execution, permanent-failure handling and repair, while the
done callback accepted it and reached publication/completion mocks without error.
Log: `/tmp/queue-payload-identity-red-final.log`, completed 2026-09-09 21:01:15 UTC.
The first repair fixture incorrectly gave a DONE row a lastError and failed before
parsing; only the corrected run establishes the repair coercion. No live data was
changed or inspected to infer deployment impact.

`AssetLocalizeAsyncJobPayload.fromJson` now provides adapter-local, bounded,
strict identity decoding independent of the application mapper. Worker execution,
both callbacks and repair share it. It rejects coerced/nonpositive/out-of-range
task IDs, duplicate/unknown fields, trailing documents, invalid roots and
noncanonical output UUIDs. Exceptions contain a stable message without parser
causes or supplied values. Repair no longer injects an unused ObjectMapper. No
global mapper, SQL, schema, transaction or rollout setting changes are involved.
Valid current/legacy writer envelopes, optional null output ID, equivalent
escapes and whitespace remain readable. This does not authenticate a syntactically
valid task ID or establish durable queue-to-task admission bindings.

The first focused selection passed **381 tests**, with two container-only
output-retry cases skipped (383 total), no failures/errors and no rerun/flaky
elements in the selected adapter/runtime reports. Log:
`/tmp/queue-payload-identity-focused.log`, completed 2026-09-09 21:04:31 UTC.
Tests cover input type/shape/size bounds, exact writer round trips, error redaction,
duplicate escaped names, application-mapper independence, and no business calls
for malformed execution/callback/repair identities. A runtime terminalization
regression additionally verifies that malformed identity becomes FAILED at the
configured one-attempt limit without selecting a PollableTask; failed callback
decoding is visible and cannot cause a new claim of that terminal row.

The subsequent clean broader release selection passed **1,084 tests across 83
suites**, with one opt-in MySQL cleanup benchmark skipped (1,085 total), zero
failures/errors and no Surefire rerun/flaky elements. It includes the new runtime
regression, configuration/admin/repair coverage, real MySQL/PostgreSQL durability,
output-retry and wakeup tests, plus the related localization/Quartz selections.
Log: `/tmp/queue-payload-identity-release.log`, completed 2026-09-09 21:11:19 UTC,
using `-Pno-local-config`, the container/performance flags and a fresh in-memory
application database. Both 24-handler contention cases passed, with 959 PostgreSQL
and 960 MySQL renewals. The 1,000-job no-business-work performance smoke took
1,052 ms on PostgreSQL and 1,791 ms on MySQL; these are not capacity estimates.
Independent source review found no actionable issue in this bounded change.
Formatting passes; existing compiler/AspectJ/Mockito warnings remain. No SQL,
Flyway, enablement or deployment change is part of this correction. Durable
admission, business fencing, schema adoption and realistic rollout soak remain
separate open gates; this run does not establish production readiness.

## Completion Commit Uncertainty (2026-09-09)

Expanded the real-generation output/retry fixture to cover loss of the fenced
DONE commit acknowledgement, not just a fault after enqueue already returned.
The unchanged JDBC fault injector is shared with the JPA enlistment tests. In
each MySQL/PostgreSQL fixture it either commits the real DONE update or rolls
it back, then throws from `Connection.commit`. It is armed only after generation
and private output storage; heartbeat is disabled, and inspection waits on the
runtime's in-memory in-flight count so a read transaction cannot steal the fault.

Both outcomes leave the real PollableTask open and canonical output absent, with
one `transition.failed{transition=done}` event, no handler failure and no terminal
callback. Committed DONE retains the winning reference and is repaired without
regeneration. Rolled-back DONE remains RUNNING with the original payload/lease;
repair rejects it until the fixture explicitly expires the lease and a second
claim completes. Translation is changed between attempts: repair of committed
work publishes the original `Accueil`, while rollback/reclaim publishes the new
`Page principale`. Original private bytes remain intact, input is unchanged,
one queue row remains, and repeated repair returns `alreadyFinished`.

The old-token write assertion occurs after DONE and proves terminal non-overwrite,
not fencing against an active replacement lease. This fixture uses separate queue
and application databases, caller-thread transaction checks and an explicitly
terminated worker pool. It does not establish same-database admission atomicity,
worker-resource unbinding, process-kill recovery, elapsed-time expiry, network
soak, business-side fencing or retained-output availability beyond the blob TTL.
No production code, SQL, Flyway migration or rollout configuration changed.

The initial focused JPA/output selection passed **35 tests**, no skips/failures or
Surefire reruns: `/tmp/queue-completion-commit-focused.log`, completed 2026-09-09
21:29:45 UTC. The subsequent clean-build selection included the strengthened
translation oracle and runtime/asset/admin/routing regressions: **411 tests across
18 suites passed**, no skips, failures/errors or Surefire rerun/flaky elements,
completed 2026-09-09 21:34:01 UTC in `/tmp/queue-completion-commit-final.log`.
Both runs used `-Pno-local-config`, disposable database containers and distinct
in-memory application databases. Four new completion scenarios run inside the
two existing dialect tests; they do not increase the JUnit test count.

The wider run was not first-attempt container-startup clean: the JPA fixture's
initial MySQL instance timed out reading the first server packet despite final
ready logs. Testcontainers replaced it before running the JPA assertions. A bounded
probe connected over IPv4 without receiving a greeting; the subsequent IPv6
connection was refused around disposal, so this is not a controlled repeat of
the earlier address-family comparison. No SQL retry, timeout or SSL setting was
changed to conceal the startup failure. Existing compiler/AspectJ/Mockito warnings
and this environment risk remain. Independent source review approved the scoped
tests and documented limitations. Formatting passes; production readiness remains
subject to the independent gates below.

## Claim Commit Uncertainty (2026-09-09)

Added three real-JDBC runtime cases per HSQL/MySQL/PostgreSQL dialect, nine cases
total, in `AsyncJobQueueJpaTransactionIntegrationTest`. The shared fault injector
is armed after enqueue and before the synchronous claim, so the exact injected
commit exception and one-shot consumption identify the claim transaction. Both
actual commit-then-throw and rollback-then-throw dispatch no handler work and leave
the runtime with zero in-flight jobs. The committed row remains leased and cannot
be claimed early; rollback preserves queued state and consumes no attempt.

After explicit fixture lease expiry, the successful replacement uses the same
worker ID and a new token. Its handler is gated while RUNNING: heartbeat, done,
requeue and failure with the old token all return false and leave the full row
unchanged. Releasing the handler then completes successfully, so the rejection
oracle is not merely testing an already-expired or terminal replacement. With
`maxAttempts=1`, a committed-but-unacknowledged first claim instead exhausts the
processing budget on reclaim: FAILED with a budget error, one terminal failure
callback, no business `process()` invocation, and one retained queue row.
`claim.failed`, acknowledged `claimed` counts and absence of handler-failure
telemetry are asserted. Repeated polling does not repeat terminal callbacks.

These tests make claim-based attempt accounting explicit in `AsyncJobStore` and
the library/rollout docs. `maxAttempts` is a processing-eligibility threshold, not
a hard cap on the stored claim count. Durable, bounded retries do not promise
that every accepted job reaches business code despite arbitrary infrastructure
failure. No runtime logic, SQL, schema, default or business adapter changed.

Both focused selections passed **259 tests across five suites**, no skips,
failures/errors or Surefire rerun/flaky elements. The first completed 2026-09-09
21:47:30 UTC in `/tmp/queue-claim-commit-focused.log`; the final completed 21:50:51
UTC in `/tmp/queue-claim-commit-final.log`. Both used `-Pno-local-config` and the
Testcontainers flag, and neither logged a container-startup replacement. This
does not establish that the earlier intermittent startup problem is fixed.
Independent source review approved the tests. Formatting passes; existing
compiler/AspectJ/Mockito warnings remain.

The scope is controlled transaction faults and fixture-driven expiry, not real
process termination or network interruption. Heartbeats are disabled and business
callbacks mocked. Resource checks cover polling and handler entry, not worker
unbinding after terminal transitions; executor termination is explicitly awaited.
Real admission, parent reconciliation, business fencing, blob lifetimes and the
rollout gates below remain separate requirements.

## Worker Process Death (2026-09-09)

`AsyncJobQueueProcessCrashIntegrationTest` adds four opt-in real-database cases:
two crash windows on MySQL 8.4 and PostgreSQL 16. Each launches a separate Java
process with the real coordinator/runtime and its owned heartbeat scheduler,
using only a disposable database. JDBC probe writes identify execution phases;
the parent confirms the child is alive, calls `destroyForcibly()`, waits for its
nonzero exit, and always tears down a surviving child before closing the database.
Independent review identified a source-level sampling race: process exit need not
finish an already-sent heartbeat COMMIT. The fixture now takes and releases a
transactional blocking row lock after termination before sampling the stranded
row, so such a commit must settle first. This was prevented before landing, not
reproduced as a queue defect.

For death during processing, the child commits a business probe and remains gated.
The parent observes an actual heartbeat extension before killing it. The last
lease remains unchanged, protects the row from early claim, and expires according
to the database clock without a timestamp UPDATE or substituted clock. A new
runtime then reclaims attempt two using the same worker ID and a new token. While
the replacement handler is actively gated, all four old-token transitions fail
and the full row stays unchanged; the replacement then completes successfully.
Both attempts' committed business probes survive, with only the winner's persisted
callback probe. Unique phase/attempt probe keys do not count duplicate callback
invocations that might themselves fail; no exactly-once callback guarantee follows.
This proves at-least-once effects, not business exactly-once semantics.

For death after completion, a test-only store override gates **after** the real
`markDone` transaction returns successfully but **before** returning to the runtime.
The parent observes its committed phase probe and kills the JVM. The row retains
DONE and its output at attempt one, with no callback probe. Two explicit polls by
a fresh runtime remain empty and do not repair/replay that callback. Corrected
`AsyncJobHandler` Javadoc now warns that terminal callbacks are best-effort local
notifications, not a required-publication mechanism.

The first selection passed all four tests with no failures/errors/skips or
Surefire reruns, completing 22:10:26 UTC in `/tmp/queue-process-crash-focused.log`.
All four child exits were 137; no container-startup replacement was logged.
The existing GitHub real-database selection now includes this class; no remote CI
run is claimed. Production runtime logic, SQL, V109 and default-off routing are
unchanged. The child uses the current compiled test classpath; launching it
without an explicit agent does not prove a lean or weaving-free Maven artifact.

The broader pre-barrier run in `/tmp/queue-process-crash-final.log` reproduced the
disposable MySQL startup issue. Container `8a032c0f853b` reported its final server
ready at 22:11:48.532 UTC but host JDBC port 50833 stalled. Before disposal, two
alternating IPv4/IPv6 probes both timed out receiving the first greeting on
`127.0.0.1` after two seconds; both `::1` probes received 32 greeting bytes in
1-4 ms. An immediate container inspect still showed it running. Testcontainers
replaced it at 22:13:48 with `944d50eb21a4`, which connected normally. This is a
repeat host address-family/forwarding asymmetry, not a demonstrated queue SQL
defect or a reason to alter queue retry/isolation settings. No Docker restart,
IPv6-only workaround or production configuration change was made.

After the row-lock barrier and numeric JDBC ID binding were reviewed, the final
selection passed **265 tests across seven suites**, with no failures/errors/skips
or Surefire rerun/flaky elements, at 22:16:24 UTC. See
`/tmp/queue-process-crash-reviewed.log`; it selects process-crash, runtime,
JPA/JDBC-commit-uncertainty and Hikari-pool-fencing tests with `-Pno-local-config`
and the real-database flag. All four worker exits were 137 and this run logged no
container-startup replacement. Independent source review approved the resolved
test; `mvn -Pno-local-config spotless:apply` passed. Existing compiler, AspectJ and
Mockito warnings remain. The earlier startup failure is still unresolved despite
this successful final run.

This closes the local worker-termination/natural-expiry coverage gap only. It does
not cover database death, network partition, multi-host/capacity soak, blob expiry,
atomic admission, business-side fencing, or automatic terminal reconciliation.
Replacement heartbeats are disabled to make the active-row fencing oracle stable;
the killed worker's production heartbeats run and their durable renewal is checked.

## Independent Queue JAR Probe (2026-09-09)

Added a bounded source-in-place packaging probe under `dev-docs/queue-library-probe`,
not a production extraction or publication. The engine POM inherits only the
current Spring Boot baseline, compiles all 28 queue originals using javac/Java 21
into a clean target, and installs an ordinary probe JAR locally. No webapp classes,
Mojito parent, AspectJ compiler/runtime, frontend plugin or migration resources
enter the artifact. The resolved runtime dependency graph retains Spring
context/JDBC/ORM/transactions, JPA APIs, Micrometer and SLF4J; Hibernate ORM is not
required for the verified JDBC path. Existing JPA-manager validation is unchanged.

A second Maven invocation consumes the installed JAR/POM and reuses the three
existing public-package consumer test sources, without a shared reactor classpath.
The additional boundary test verifies a single JAR-backed queue-class resource,
byte equality with the just-built engine artifact, no business/Quartz/AspectJ/
Hibernate classes and no bundled DB scripts or Boot repackaging. Consumer-only
V109 test resources are explicitly installed, not auto-applied by the dependency.
The existing database CI job includes both invocations; no remote CI run is claimed.

The first consumer run passed 15 of 16 cases; PostgreSQL maintenance failed opening
its fixture connection before DDL, with `PSQLException` caused by EOF during
`enableSSL`, in `/tmp/queue-library-consumer-probe.log` at 22:29:49 UTC. This is the
known disposable-database handshake signature, not an engine compilation or
business behavior failure. No workaround or automatic Surefire retry was added.

After a fresh engine `clean install`, the final separate consumer passed **16/16
tests across four suites**, no failures/errors/skips or Surefire rerun/flaky
elements, including real MySQL/PostgreSQL bootstrap, maintenance and notification
contracts. Logs: `/tmp/queue-library-engine-final.log`,
`/tmp/queue-library-engine-dependencies.log`, and
`/tmp/queue-library-consumer-final.log` (22:32:53 UTC). Both root and sidecar
formatting passed. Independent source review approved the
probe. Runtime deprecation and Mockito/Byte Buddy agent warnings remain; this is
AspectJ-free queue compilation/execution, not instrumentation-free testing.

Production module extraction, neutral config/API naming, independently packaged
generic tests, Hibernate/JPA compatibility in that artifact, release provenance,
Flyway adoption and all business rollout gates remain. The experiment strengthens
the library feasibility evidence without authorizing a new repository or release.

## Terminal Publication Fault Coverage (2026-09-09)

`AssetLocalizeAsyncJobOutputRetryIntegrationTest` now exercises four terminal
publication windows: failure before canonical output write, acknowledgement loss
after that service returns, failure before PollableTask finish, and acknowledgement
loss after the real finish service returns. Delegating test wrappers retain real
generation, database blob storage and task services. These are service-boundary
faults, not injected storage/JDBC commit failures or network partitions. Queue
databases remain separate from the application's test database.

Each window asserts the row stays DONE at attempt one, both callback-failure
metrics record the failure, and processing/transition failure metrics do not.
Repair uses the retained winning output even after the translation changes;
ordinary polling neither regenerates the job nor retries the callback. When task
finish committed before the error, repair returns `alreadyFinished` without
rewriting output or the finish timestamp. Repeated repair leaves task, input,
private output, canonical output and the entire queue row unchanged. All four
windows run with in-memory, MySQL and PostgreSQL queue stores.

Verification: the focused class passed **12/12 tests** at 22:52:26 UTC
(`/tmp/queue-publication-fault-focused.log`). The broader asset-localization,
runtime and admin selection passed **398/398 tests across 17 suites**, with no
failures/errors/skips, at 22:53:28 UTC
(`/tmp/queue-publication-fault-final.log`). The database methods include four new
subscenarios each, not additional JUnit test counts. Both runs started one MySQL
and one PostgreSQL container successfully; no replacement-container success or
Surefire rerun was needed. Root formatting passed; independent static review
found no actionable issue in the bounded test change. Existing application weaving,
deprecation and Mockito agent warnings remain; this is not the independent,
AspectJ-free JAR probe above.

This strengthens explicit terminal repair only. Automatic reconciliation,
retained-output lifetime, concurrent business publication and admission recovery
remain open gates. No production behavior or migration is changed.

## Retained Output Parsing (2026-09-09)

Three regression tests reproduced a publication-reader gap: the application
mapper accepted a second trailing JSON document and duplicate `content` fields,
and an unknown-field parser error retained a blob-supplied field name in its cause
chain. The baseline run had three failures out of ten tests, including the
existing configured Surefire rerun of each failure
(`/tmp/queue-output-reader-reproduction.log`, 23:04:03 UTC). This is a synthetic
malformed-storage reproduction, not evidence of corrupted production blobs.

Output publication now uses an immutable reader with trailing-token rejection and
strict duplicate detection, without modifying the injected application mapper.
Both private winning blobs and legacy canonical blobs pass through that reader.
Parser failures and null root values produce a task-ID-only diagnostic with no
parser cause attached; storage lookup/write failures remain distinguishable and
are not swallowed. Valid nullable/empty output fields retain their existing
semantics. This is JSON framing validation, not a new output schema, checksum or
proof of request/attempt ownership. Ambiguous retained blobs require investigation
instead of guessed repair, regeneration or canonical fallback; normal polling
must not replay a DONE job.

The real generation/blob/task fixture now corrupts the retained winner with a
trailing document and a duplicate field after canonical publication but before
task finish. Each failed repair must leave the task open and the canonical blob,
input and DONE row unchanged; the parser cause contains no blob-derived details.
Test-only restoration of the exact original private bytes permits normal,
idempotent repair without regeneration. These cases run with in-memory, MySQL and
PostgreSQL queue stores, still separate from the application database. They do not
implement an operator restoration API, blob checksum binding, pinned retention or
automatic reconciliation.

Verification: the final asset-localization, runtime and admin selection passed
**405/405 tests across 17 suites**, zero failures/errors/skips and no Surefire
rerun/flaky elements (`/tmp/queue-output-reader-final.log`, 23:09:21 UTC).
MySQL/PostgreSQL started successfully without replacement containers. The unit
suite also checks nullable/empty/control-character content, legacy framing and
preservation of shared-mapper settings. Root formatting and independent static
review passed. Existing application weaving, deprecation and Mockito agent
warnings remain; no migration or generic-library behavior changed.

## Enlisted Enqueue Building Block (2026-09-09)

Added package-local `JdbcAsyncJobStore.enqueueNowInCurrentTransaction` as the
planned shared INSERT primitive for future admission. It reuses the existing
queue/payload validation and database-clock path, starts no nested transaction,
performs no commit or worker notification, and returns a provisional ID. Public
enqueue remains independently committed with REQUIRES_NEW.

The primitive rejects an unconfigured store, missing actual transaction or
synchronization, read-only or non-explicit-READ_COMMITTED context, and a missing
queue DataSource connection holder before SQL. These checks are binding
preconditions, not proof of mixed-manager ownership. The future caller must use
the configured manager and propagate insert failures through its owned
TransactionTemplate; swallowing them does not automatically mark rollback-only.

Eleven added fixture methods run across HSQL, MySQL and PostgreSQL. A real
PollableTask and queue row share one physical JPA/JDBC connection; a separate
READ_COMMITTED connection sees neither provisional row before commit. Commit,
explicit rollback, propagated post-insert failure, actual commit-then-throw and
rollback-before-commit-error leave the expected pair or neither row. Guard cases
reject no transaction, synchronization-only, unconfigured manager, read-only,
default/wrong isolation and a different DataSource object for the same database
before any queue SQL. Thread-bound resources are checked after each outcome.

The focused JPA selection passed **69/69 tests** at 23:27:06 UTC
(`/tmp/queue-enlisted-focused.log`). The final store/transaction/submission/asset/
admin selection passed **341/341 across 21 suites** at 23:28:14 UTC
(`/tmp/queue-enlisted-final.log`), with no failures/errors/skips or Surefire reruns.
The final isolated engine build and separate ordinary-JAR consumer passed
**16/16 public contracts**, completed 23:28:39 UTC
(`/tmp/queue-enlisted-engine-final.log`, `/tmp/queue-enlisted-consumer-final.log`).
No replacement database containers were needed. The JAR probe tests the public
JDBC surface, not the new package-local JPA primitive; the real-entity fixture is
the latter's evidence. Root formatting and independent contract review passed.
Existing application weaving/deprecation and test-agent warnings remain.

This is not a production admission API: reservations, scoped deduplication,
admission-state updates, checksum-verified blobs, primary resolution of unknown
commits and parent fan-out remain unwired. No migration or producer route changed.

## Forced Connection Loss And Recovery (2026-09-09)

Extended `JdbcAsyncJobStorePoolIntegrationTest` beyond holding a shared-pool
connection. A disposable worker account now loses its actual MySQL/PostgreSQL
session and temporarily cannot log in. The fixture verifies connection death,
returns the dead connection to Hikari, observes denied reauthentication and waits
for an actual renewal failure that starts after outage setup. PostgreSQL can
report SQLSTATE 08003 while Hikari clears warnings during close; the fixture
permits only connection-class close errors and still requires a closed handle and
released pool permit. This does not suppress store/runtime failures.

A separately authenticated peer reclaims after natural database-clock expiry,
without editing lease timestamps. Both starvation and disconnection cases now
keep the replacement RUNNING under the same worker ID but a different lease
token. After restoring connectivity, stale renewal and completion must fail,
leave that row unchanged, run no stale callback and release runtime capacity.
Only then may the replacement complete with its own token. The four cases are
included in the explicit real-database CI selection.

Verification: **295/295 tests across 12 suites** passed at 23:52:22 UTC in three
minutes (`/tmp/queue-disconnect-final.log`), with no failures/errors/skips or
Surefire rerun/flaky elements. This includes all four pool cases, process-crash,
runtime/coordinator, database contracts and PostgreSQL wakeup coverage with
`-Pno-local-config` and real-database/performance flags enabled. The contention
fixture completed 960 renewals over 24 handlers per database; the heartbeat-off
1,000-job smoke drained in 1,190 ms/PostgreSQL and 1,823 ms/MySQL with zero
poll/claim failures. These local smoke timings are not production capacity.

Two earlier runs caught fixture errors, not queue failures: PostgreSQL session-ID
binding used bigint instead of integer, and dead-connection close propagated the
expected 08003. Each had the existing Surefire rerun; preserve the failed logs
`/tmp/queue-disconnect-focused.log` and `/tmp/queue-disconnect-focused-fixed.log`
separately from final evidence. Both were corrected before the final run.
Independent static review found no actionable findings; root formatting passed.
Existing application weaving/deprecation and test-agent warnings remain. The
full application suite and hosted CI were not run.

This tests server-terminated connections plus blocked reauthentication, not a TCP
blackhole, database restart/failover, multi-host partition or sustained outage
capacity. No production queue code, schema, transaction policy or enablement flag
changed. Migration/admission and business-effect gates remain open.

## Stored Input Framing And Diagnostics (2026-09-10)

Three new tests against the unchanged handler reproduced ambiguous stored input
and disclosure through parser diagnostics: duplicate `pullRunName` fields let
the last null value hide tracked input, trailing JSON was ignored, and an invalid
numeric ID exposed its supplied string in the exception/cause. All three failed,
including the configured Surefire rerun, in
`/tmp/queue-input-reader-reproduction.log` at 00:04:48 UTC. The fixture uses real
PollableTaskBlobStorage decoding, not a mock returning a preconstructed DTO.

The queue handler now reads raw input with an immutable ObjectReader requiring
duplicate detection and end-of-document framing. It uses the same qualified
mapper as PollableTaskBlobStorage, preserving existing binding and unknown-field
settings without mutating the shared parser. Missing input/provider failures are
outside the parse catch; parser/binding IOExceptions and null input become one
redacted error without source-bearing causes. Rejected input reaches neither
generation nor speculative output storage. Existing Quartz/non-queue decoding
is unchanged.

Eight input-focused tests cover these defects, malformed/null input and duplicate
unknown fields, compatible legacy fields/defaults, configured enum binding,
unchanged shared parser behavior and missing/provider errors. A new runtime test
exhausts a one-attempt malformed-input job, persists only the redacted failure,
invokes the existing failed-task callback and leaves no claimable work or output.
Existing mock fixtures now serialize input and compare detached DTOs; the
stale/winner race retains its distinct sources and latch coordination.

Verification: **417/417 tests across 19 suites**, no failures/errors/skips or
Surefire rerun/flaky elements, completed 00:08:48 UTC
(`/tmp/queue-input-reader-final.log`) with `-Pno-local-config` and real database
checks enabled. Both MySQL/PostgreSQL adapter fixtures passed without replacement
containers. Root formatting and independent static review passed. Existing
application weaving, metamodel/deprecation and Mockito agent warnings remain;
the full application suite and hosted CI were not run.

This closes the reproduced framing/diagnostic defects, not strict typed coercion,
input immutability or admission/ownership/checksum binding. Provider, generation
and custom unchecked exceptions are not globally redacted by this change. The
future versioned execution envelope and lifetime protocol remain prerequisites;
no schema, generic queue core, retry policy or enablement flag changed.

## Listener Recovery With Failed Counters (2026-09-10)

The listener's failure counter could itself throw out of the reconnect loop,
leaving a dead thread advertised as running and making later start calls no-ops.
Connected/received counters could also tear down a healthy subscription or abort
the rest of a notification batch, and stop counters could skip the lifecycle
callback. All 22 new fault/fatal cases failed against the unchanged listener
(`/tmp/queue-listener-reproduction.log`, 00:27:12 UTC), including the configured
Surefire rerun. Two earlier runs failed during fixture compilation because this
Micrometer version's registry is not AutoCloseable; those are test-setup failures,
not product reproductions.

Listener counters now isolate nonfatal registration and increment errors from
control flow. Direct driver/counter JVM-fatal errors still escape; the loop's finally block resets the
running flag under the lifecycle lock while retaining the actual thread reference,
so restart cannot overlap a still-exiting thread. This is not automatic restart
after a fatal JVM error. The change does not alter constructor gauge registration,
JDBC transaction boundaries, notification durability or periodic polling fallback.

Twenty counter-failure cases cover registration/increment RuntimeExceptions and
AssertionErrors across connection recovery, established subscriptions, batches,
failed triggers and stop/start exclusion. Two fatal-exit cases assert the original
error reaches the thread's uncaught handler, the running gauge clears, restart is
rejected while that handler still holds the old thread alive, and an explicit
later start is possible. Both original and replacement threads are joined. The real PostgreSQL
fixture terminates an established one-slot Hikari listener session while its
failure-counter registration throws, independently detects a replacement LISTEN
session through pg_stat_activity, receives another notification, then verifies
thread/permit cleanup and removal of the returned session's subscription.

The focused wakeup/coordinator selection passed **109/109 tests across nine
suites**, including both real PostgreSQL listener cases, without failures, errors,
skips or Surefire rerun/flaky elements (`/tmp/queue-listener-focused.log`). No
replacement containers were needed. The broader queue/admin/assetlocalize/blob
selection completed **928 tests across 66 suites** at 00:33:44 UTC with zero final
failures/errors/skips but **one flaky retry**, not a clean first-attempt pass
(`/tmp/queue-listener-final.log`). PostgreSQL SSL negotiation ended in EOF before
the process-crash fixture applied its schema; the configured rerun passed on a
new fixture. Preserve that infrastructure failure separately from queue behavior.
After strengthening the fatal-exit restart race, a separate final wakeup,
coordinator and process-crash run passed **113/113 tests across ten suites** at
00:35:31 UTC with no failures/errors/skips or Surefire rerun/flaky elements
(`/tmp/queue-listener-reviewed.log`). This verifies the final tests without
erasing the broader run's earlier handshake failure.

This closes a bounded recovery defect in the
optional hint path, not admission, business fencing, blob lifetime or staging
network/failover readiness. No schema or enablement setting changed.

Independent static review found no blocking patch defects. It identified a
pre-existing compound-failure gap, addressed in the follow-up below: an ordinary read error
can suppress a fatal unsubscribe error, and reflection unwrapping drops wrapper
suppressed diagnostics. Do not generalize these direct-fatal tests to compound
JDBC cleanup failures or comprehensive logging-failure isolation.
Root formatting passed. Existing application weaving/deprecation and test-agent
warnings remain; the full application suite and hosted CI were not run.

## Compound Listener Cleanup Failures (2026-09-10)

Eight of nine targeted tests failed against the unchanged listener, including
the configured rerun (`/tmp/queue-listener-cleanup-reproduction.log`, 01:22:42 UTC).
The cases reproduce an ordinary read failure hiding fatal unsubscribe,
unsubscribe-statement close, abort or auto-commit restoration errors; an unchecked
connection close masking a fatal read; and discarded secondary diagnostics after
reflection unwrapping. A fatal connection-close control already passed.

Independent review then found one further graph-replacement path: an unchecked
abort could replace an unsubscribe SQLException that already suppressed a fatal
statement-close error. Three additional controls reproduced that loss, missing
ordinary abort diagnostics and self-suppression when abort rethrows the same
SQLException (`/tmp/queue-listener-abort-reproduction.log`, 01:28:30 UTC); all
three failed, including the configured rerun. Abort now retains failures on the
original unsubscribe exception, except when the object is already identical.
Fatal precedence is decided at the listener boundary after cleanup completes.

The listener now unwraps the reflected notification read before resource cleanup,
so secondary failures attach to the actual driver error. Connection return is the
outermost owned resource instead of a failure-replacing finally action. Cleanup
order is still unsubscribe, restore auto-commit, return connection, clear the
active-connection reference; only the listener thread performs those actions.
The reconnect catch searches causes and suppressed errors by identity, tolerating
cycles without rewriting the graph. A discovered JVM-fatal error escapes as that
same Error rather than becoming an ordinary reconnect. Existing direct
`isJvmFatal` callers outside this boundary retain their behavior.

Verification: **364/364 tests across 15 suites** passed at 01:30:44 UTC
(`/tmp/queue-listener-cleanup-final.log`) with `-Pno-local-config` and real
PostgreSQL checks enabled, without failures/errors/skips or Surefire rerun/flaky
elements. This includes all 12 compound listener cases, 20 classifier/graph tests,
runtime telemetry/heartbeat-transition regressions and both real PostgreSQL
listener fixtures. Fifteen new graph/direct-classifier controls cover fatal
identity, null/nonfatal graphs, nested causes/suppression, repeated references,
cycles, equality overrides and one cause-accessor read per visited throwable.
An earlier focused selection passed 137 tests before the last abort and
cause-accessor controls were added (`/tmp/queue-listener-cleanup-focused.log`).
The standalone 28-source engine was rebuilt using plain javac, then its separate
ordinary-JAR consumer passed **16/16 tests** on MySQL/PostgreSQL at 01:31:55 UTC
(`/tmp/queue-listener-cleanup-engine.log`,
`/tmp/queue-listener-cleanup-consumer.log`). No skipped cases or rerun/flaky
elements were present. The JAR-boundary check still excludes Mojito application
classes, Quartz, AspectJ and Hibernate ORM. This remains a local packaging probe,
not module extraction, artifact publication or an application PostgreSQL proof.

This is bounded listener failure handling, not a global guarantee about arbitrary
third-party logging/metrics exception wrappers, actual out-of-memory recovery or
exactly-once business effects. Existing SQL close/restore errors that are logged
and handled locally keep their existing policy. No schema, queue state
transition, transaction configuration, retry budget or enablement flag changes.
Final independent review confirmed the abort finding resolved and found no further
actionable defects in this scope. Root formatting passed; existing application
weaving/deprecation and Mockito agent warnings remain. The full application
suite, hosted CI and staging soak were not run.

## Terminal Task Replay Containment (2026-09-10)

Four handler regressions reproduced two unsafe paths: generation reached blob
input for a task already marked finished, and the permanent-failure callback
called `finishTask` again for terminal success or failure. The existing business
service would replace the finish time and, on failure, the prior error details.
All four failed, including the configured Surefire reruns, before the patch
(`/tmp/queue-terminal-task-reproduction.log`, 01:55:42 UTC).

The handler now rejects a task observed finished before reading input or calling
generation. Its permanent-failure callback returns without changing an existing
terminal task and does not count that no-op as a newly finished task. Open tasks
retain the existing retry, completion and failure paths. No queue state-machine,
payload, schema, transaction or rollout-flag changes are involved.

The persisted-task regression first exhausts two generation attempts, then uses
the generic inspection service to replay the failed row. Both replay attempts
reject execution while preserving the entire terminal task row, original input
and absent output, with no further generation/blob/failure-processing calls. It
also checks a task's own finish rather than aggregate child completion. The same
scenario passes with in-memory, MySQL and PostgreSQL queue stores; application
task persistence remains in the separate existing HSQL fixture, not atomic
JPA/JDBC admission or whole-application PostgreSQL certification.

Final focused verification: **445/445 tests across 21 suites** at 02:05:10 UTC
(`/tmp/queue-terminal-task-reviewed.log`, `-Pno-local-config`, real-database tests
enabled), with no failures/errors/skips or Surefire rerun/flaky elements. This
includes runtime, admin/repair, route controls, parsing and genuine open-task
retry/publication recovery. Earlier five-suite verification passed 77 tests;
intermediate integration development caught compilation mistakes in a discarded
draft and an incorrect expectation of unredacted public error text. These were
test-harness corrections, not changes to the application error contract. Root
Spotless and diff whitespace checks pass. Independent production/unit-patch
review found no blockers; the main reviewer reviewed and verified the integrated
fixture. Existing application weaving/deprecation and Mockito warnings remain.
No full application suite, hosted CI, deployment or staging soak was run.

This is defensive read-time containment, not a supported domain-aware replay
workflow. Generic replay still resets the queue row and consumes its bounded
retry budget before returning to FAILED. It does not reopen the business task.
Concurrent timeout or another finisher after the read remains unfenced. At this
checkpoint the successful completion callback was unchanged; the follow-up below
adds read-time late-result containment, not atomic fencing. Fresh linked-task/revision
policy and atomic business fencing remain rollout gates.

## Late Completion After Terminal Task (2026-09-10)

Two regressions reproduced a success callback publishing/finishing without
rereading an already-terminal task. Both failed, including the configured reruns,
at 02:15:38 UTC (`/tmp/queue-late-completion-reproduction.log`). Completion now
reads the task before touching output; if it is already finished, publication and
finish are skipped, matching the existing repair no-reopen contract. Missing or
failed lookup stops before output writes and remains an ordinary completion
callback failure. Existing open-task completion and retained-output repair remain.

Both terminal callback guards emit
`assetLocalizeAsyncJob.pollableTask.finish.skipped{queueName,callback,reason}` with
only `done|failed` and `alreadyFinished` as the new bounded values. A skipped
callback does not increment the newly finished-task counter. Nonfatal metric
failures cannot turn the skip into a publication or task write. Unit tests cover
successful/failed terminal tasks, lookup absence/failure, ordering and metric
failure. This adds one business-task read before successful publication.

The real application fixture commits the same timeout action as the cleanup
service after the queue commits DONE but before the real handler callback. The
test preserves the entire terminal task row, input, private winning blob and
queue record while canonical output stays absent; a repeated callback and repair
still do not reopen the task. This is a deterministic service-boundary ordering,
not wall-clock expiration, a global-cleaner run, or timeout/publication atomicity.
The same scenario is included in the existing in-memory/MySQL/PostgreSQL queue
wrappers, with task storage remaining in the separate HSQL application fixture.

Verification: **451/451 tests across 21 suites** passed at 02:20:19 UTC
(`/tmp/queue-late-completion-final.log`) with `-Pno-local-config` and real-database
tests enabled, without failures/errors/skips or Surefire rerun/flaky elements.
The earlier focused handler/repair selection passed 82 tests at 02:18:02 UTC
(`/tmp/queue-late-completion-focused.log`). Independent production/unit review
found no actionable defects; the main reviewer checked the integration fixture
and ran the combined suite. Spotless and diff whitespace checks pass. Existing
application weaving/deprecation and Mockito warnings remain. The full application
suite, hosted CI, deployment and staging/network soak were not run.

Retained output remains necessary if the additional lookup fails and an open task
needs later repair; there is no automatic callback retry. Already-successful tasks
are skipped without validating or restoring their canonical output, exactly as
repair already does. Missing/expired output and timeout after the read remain
rollout gates, not guarantees supplied by this guard. No schema, payload, queue
retry budget, transaction configuration or enablement flags changed.

## Database Blob One-Day Default (2026-09-10)

The shared database blob configuration claimed a one-day default but used 84,600
seconds, only 23.5 hours. A real application-database cleanup regression deleted a
temporary blob aged 23 hours 45 minutes, while the configuration contract expected
86,400 seconds. Both tests failed before the fix, including configured reruns;
explicit one-second override binding passed
(`/tmp/queue-blob-day-reproduction.log`, 02:31:45 UTC).

The default now derives from `Duration.ofDays(1).toSeconds()`. This is a shared
storage correction: subsequent temporary writes use the new default even when
queue features are disabled. No configuration override or existing row is
rewritten, and no cleanup job is enabled. Boundary controls verify that ordinary
cleanup retains the 23-hour-45-minute blob, deletes a 25-hour temporary blob and
preserves an older permanent blob. The default and explicit override have separate
configuration tests.

Verification: **514/514 tests across 29 suites** passed at 02:33:42 UTC
(`/tmp/queue-blob-day-final.log`, `-Pno-local-config`, real queue database tests
enabled) with no failures/errors/skips or Surefire rerun/flaky elements. The
selection includes database blob configuration/storage, ordinary and prefix
cleanup, routing/fallback, PollableTask payload storage, queue runtime/admin and
asset-localization recovery. The blob cleanup boundary itself uses the existing
HSQL application fixture; MySQL/PostgreSQL coverage here is for queue behavior,
not new certification of those blob adapters. Independent review found no
material blocker and confirmed the shared, non-queue-gated scope. Formatting and
diff whitespace checks pass. Existing application weaving/deprecation and Mockito
warnings remain; no full application suite, hosted CI or live storage validation.

This correction does not restart expiration on overwrite, clear an old expiry on
PERMANENT promotion, override prefix/cloud cleanup policy, or create durable queue
pins. Queue input, private output and canonical delivery horizons remain separate
from terminal-row retention. No Flyway migration, production storage setting,
external lifecycle policy or live cleanup was changed.

## Explicit Permanent Handler Failures (2026-09-10)

The finished-business-task guard previously spent the normal retry budget
rejecting the same raw queue replay. A public checked
`AsyncJobPermanentFailureException` now lets a handler explicitly request FAILED
without spending its remaining budget. Only a direct throw from `process` opts
in; ordinary failures, wrapped/suppressed markers and store failures retain their
existing behavior. This initial change used it only for the existing finished-task
guard, before input reads and generation. Later changes add malformed-input and
[unsupported-tracking rejection](#unsupported-tracking-permanent-rejection-2026-09-10).
Missing tasks and transient storage failures retain ordinary retries.

The runtime reuses its fenced FAILED transition and records the original marker
in `lastError`, with a bounded log reason distinct from attempt exhaustion. The
callback runs only after a known successful transition. Rejection or uncertain
commit does not trigger a callback or explicit requeue; if the transition did not
commit, lease recovery can execute the handler again. The marker is not an
exactly-once, business rollback, cancellation or durable callback mechanism.

Nine focused runtime cases cover direct failure, callback failure, rejected and
before/after-commit transition faults, ordinary/wrapped/suppressed failures, and a
marker thrown by the store rather than the handler. They verify capacity release
and heartbeat cancellation. The transition fault injection here uses the
in-memory store, not a new real-JDBC commit-fault harness. The existing database
contracts exercise actual fenced FAILED and replay behavior on MySQL/PostgreSQL.
The asset fixture preserves the full terminal business row and blob contents on
replay, fails at attempt one, and proves ordinary initial generation failure still
retries before exhausting its budget. External-package consumer tests construct
and throw the public marker without importing Mojito business code.

Before the runtime fix, five of nine corrected regressions failed, including
their configured reruns (`/tmp/queue-permanent-failure-reproduction-reviewed.log`,
02:52:45 UTC). The initial test draft incorrectly expected a `markDone` exception
to requeue; that control was corrected to the existing unknown-transition policy
(RUNNING, no callback/requeue) without changing production behavior. The first
focused post-fix run passed **61/61 tests across four suites** at 02:55:11 UTC
(`/tmp/queue-permanent-failure-focused.log`).

The broader run completed **911 tests across 63 suites: 910 passed, one opt-in
performance smoke skipped**, zero failures/errors and no Surefire rerun/flaky
elements at 03:00:36 UTC (`/tmp/queue-permanent-failure-final.log`,
`-Pno-local-config`, Testcontainers enabled). It includes queue core, admin,
MySQL/PostgreSQL SQL, forced worker death, shared-pool faults, JPA boundaries,
asset localization and blob regressions. The 24-handler contention fixture
observed 960 PostgreSQL and 959 MySQL successful renewals; these bounded local
checks are not sustained multi-host/network soak. The blob business fixture uses
HSQL, separate from the queue databases.

The skipped performance case was then explicitly requested with
`-Dmojito.asyncJobQueue.perf=true`. That separate run errored before container
startup at 03:01:53 UTC, including Surefire's configured rerun
(`/tmp/queue-permanent-failure-perf.log`): Docker Desktop's Unix socket was absent.
The preceding broad/consumer logs show successful connections through
`/var/run/docker.sock`; afterward its `/Users/ja/.docker/run/docker.sock` target
was missing while the selected Docker context remained `desktop-linux`. No
benchmark jobs or performance measurements ran in that attempt. No Docker restart,
socket/config change or queue retry adjustment was made. Rerun the opt-in smoke
when the local Docker endpoint is available; do not conflate this infrastructure
failure with the earlier passing database correctness evidence.

The clean standalone engine build compiles 29 sources; its independent ordinary-JAR
consumer passes **16/16 tests**, with no skips/failures/errors or reruns at 02:58:22
UTC (`/tmp/queue-permanent-failure-engine.log`,
`/tmp/queue-permanent-failure-consumer.log`). This includes the public marker on
both databases with no Mojito/Quartz/AspectJ/Hibernate ORM dependency. Spring ORM
and JPA APIs remain, and the lean probe is not Hibernate enlistment certification.
Independent code/test review found no actionable findings. Formatting and diff
whitespace checks pass. Existing application weaving/deprecation and Mockito
warnings remain; full application tests, hosted CI and live storage/rollout
validation were not run.

No SQL migration, queue setting, payload schema, deployment or enablement changed.
The first-attempt rejection still does not make raw asset replay a supported
reopen workflow, nor fence a task that times out after the read. Admission,
lineage, lifetime, upgrade and sustained-soak gates remain open.

## Database Blob Retention Updates (2026-09-10)

An explicit permanent overwrite previously left the old temporary TTL on the
database row. Ordinary expiry cleanup also selected candidate IDs then deleted
unconditionally: a committed promotion or extension between those statements
could still be deleted. Three of four initial regressions failed, including
Surefire reruns; the temporary-overwrite control passed
(`/tmp/queue-blob-retention-reproduction.log`, 03:14:45 UTC).

The adapter now clears expiry only for explicit `PERMANENT` writes, using an
additive `MBlob.clearExpiration()` method. Temporary writes still apply the
configured TTL, null-retention behavior is unchanged, and creation time is not
reset. The ordinary cleaner captures one cutoff per batch and calls a separate
`deleteExpiredByIds` statement that rechecks the existing expiry predicate and
selected ID set. The unconditional `deleteByIds` used by prefix-policy cleanup
is unchanged; no cleanup setting, SQL migration or live data was changed.

The initial draft continued after a zero-delete batch. Review rejected that loop
change because a caller-owned transaction can retain a stale candidate snapshot.
The existing `deletedCount > 0` termination rule remains: an entirely ineligible
batch safely ends this run, and a later run can collect remaining expired rows.
This is conservative termination, not a new global execution bound or a guarantee
that each run empties the backlog. The real HSQL tests commit promotion/extension
between selection and deletion, fail on an attempted zero-delete rescan, preserve
the updated blob, and verify that a later run deletes the expired control.
A direct repository test checks selected-ID scope and the strict expiry boundary.
The shared storage fixture still uses application transaction advice; this is not
part of the AspectJ-free generic queue engine.

Verification: **528 tests across 30 suites: 526 passed, two opt-in queue database
wrappers skipped**, with zero failures/errors and no Surefire rerun/flaky elements
at 03:19:11 UTC (`/tmp/queue-blob-retention-final.log`, `-Pno-local-config`). This
includes all 17 database blob storage cases, prefix cleanup, blob routing/fallback,
PollableTask storage, queue runtime/admin and asset localization. The final source
keeps zero-delete termination; an earlier 16-case HSQL pass verified the retention
and DELETE logic before that review adjustment. Formatting and diff whitespace
checks pass. Independent source/test/documentation review found no actionable
issues. Existing application weaving/deprecation and Mockito warnings remain.
Docker Desktop's socket is still absent; no new real-MySQL/PostgreSQL blob-query
or concurrency certification is claimed and no Docker restart was attempted.
Full application tests, hosted CI, deployed configuration and live cleanup were
not exercised. The generic queue package and ordinary-JAR probe were not changed.

This is a shared, non-queue-gated correction for subsequent writes and ordinary
expiry cleanup, not a backfill or queue pin. Older ordinary-cleaner binaries can
still issue unconditional deletes, so drain them before relying on the new guard.
Fresh admission-owned immutable keys, cross-provider lifecycle exclusions,
fallback-copy retirement and a reference-aware repair/replay horizon remain
required. Queue features remain disabled, and admission/fencing/upgrade/soak gates
remain open.

## Worker Fatal Failure Boundaries (2026-09-10)

The runtime's worker and terminal-callback catches classified only the top-level
throwable. A future wrapper or an ordinary exception with a fatal resource-close
failure suppressed underneath could therefore retry a handler or become an
ordinary callback warning. The expanded unchanged-code reproduction failed
**25 of 40 cases**, including their configured Surefire reruns
(`/tmp/queue-worker-fatal-reproduction-expanded.log`, 04:25:59 UTC). The 15
direct-fatal/nonfatal controls passed; an earlier three-boundary selection
reproduced 15 failures in 24 cases.

Three boundary catches now reuse the identity/cycle-safe fatal graph search and
throw the original discovered Error before ordinary failure handling. Direct-only
permanent marker semantics remain unchanged for nonfatal graphs. Fatal errors
take precedence even when suppressed by that marker. The handler contract now
documents that attaching a handled fatal as diagnostic context is not an opt-out.

The new 40-case deterministic matrix captures accepted worker Runnables instead
of conflating worker execution with executor submission. It covers handler
execution below/at the attempt limit, DONE callbacks, FAILED callbacks after
handler failure, and FAILED callbacks after pre-handler budget exhaustion.
Checks cover original identity, nested futures, real try-with-resources
suppression, cyclic graphs, wrapped ThreadDeath, permanent markers, ordinary
assertion/cyclic errors, unchanged terminal metrics, no extra transitions,
heartbeat cancellation and local capacity release. A fatal handler leaves its
claim RUNNING even at its last allowed attempt; later lease recovery applies the
budget. Terminal callbacks preserve committed DONE/FAILED rows and are not
automatically retried. This does not stop the runtime, guarantee JVM termination,
fence business writes or comprehensively change inner store/scheduler/cleanup
catch policies.

Verification with `-Pno-local-config`: focused classifier/permanent-failure/worker
tests passed **69/69** at 04:27:07 UTC. The broader queue/admin/assetlocalize/blob
selection passed **875 tests with 14 skipped across 64 suites** at 04:27:59 UTC
(`/tmp/queue-worker-fatal-final.log`), with no failures/errors or rerun/flaky XML
elements. The skipped opt-in database cases are the six external consumer
contracts, four separate-JVM crash tests, two output-retry tests and two listener
session tests. Docker's socket is still missing; other Docker-only suites and the
performance smoke were not requested, and no Docker settings were changed.
HSQL and deterministic tests do not replace real MySQL/PostgreSQL verification.

The independent 29-source javac engine rebuilt successfully. Its ordinary-JAR
consumer passed **10 tests with six database cases skipped**, including the
JAR-origin/byte-equality check (`/tmp/queue-worker-fatal-engine.log`,
`/tmp/queue-worker-fatal-consumer.log`, 04:28:18 UTC). This is not a new full
database consumer proof. Formatting passed; existing weaving/deprecation,
Mockito-agent and HSQL no-data cleanup warnings remain. No full application
suite, hosted CI, migration, enablement or deployment was performed.
Independent scoped source, test and documentation review found no actionable
defects in this change; the remaining production gates below are unchanged.

## Failed Repair Diagnostic Exposure (2026-09-10)

FAILED-row repair copied the queue's raw `last_error` into a task exception and
marked it expected. That exposed arbitrary handler/database diagnostic text in
`errorMessage`. Setting only `expected=false` would not suffice: normal task JSON
serializes `errorStack`, and task inspection returns that stack too. Queue admin
inspection has a narrower access policy than ordinary authenticated task reads.

The corrected MVC reproduction failed **3 of 26 tests**, including configured
reruns (`/tmp/queue-repair-privacy-mvc-reproduction.log`, 04:46:42 UTC). Two cases
persist real HSQL tasks and issue translator requests through the application's
MVC controller/converter and security-filter chain, reproducing the diagnostic
in both task and inspection responses. One unit case verifies the exception
serialization contract. A historical-finished-task control passed. The earlier
persisted-only selection also failed three cases; an intervening real HTTP-client
attempt instead had two login-page 404 errors because the frontend index was not
built (`/tmp/queue-repair-privacy-http-reproduction.log`). That test-client failure
is not exposure evidence and required no production or frontend change.

Repair now constructs a fresh, message-only generic exception containing the
queue ID and marks it unexpected. It does not copy the stored diagnostic into
the message, stack, cause or suppressed graph, and does not infer a trusted error
classification from a string. The exact queue row/diagnostic remains unchanged
and available through operator inspection. Task finish, no-output-publication,
already-finished behavior and transaction boundaries remain unchanged.

The three new integration cases cover ordinary repair, an acknowledgement lost
after the real task-finish service returned, and preserving historical task
errors. They assert redaction across both full MVC responses, the persisted task
fields, exact queue-row preservation and operator diagnostics, and repeated
repair leaving the terminal task row unchanged. This is an in-memory queue with
real HSQL task persistence; the acknowledgement fault is at the service boundary,
not inside JDBC commit. Two existing unit expectations were updated from the old
unsafe classification; the first post-fix focused run passed 40/41 tests before
the second counter-collision fixture's stale expectation was corrected.

Final verification: **888 passed, 14 skipped across 68 suites** at 04:50:15 UTC
with `-Pno-local-config` (`/tmp/queue-repair-privacy-final.log`). No failures/errors
or rerun/flaky XML elements remain. This includes all three privacy integrations,
repair/admin/task inspection and authorization coverage, broader queue/runtime
and asset-localization tests, and shared blob regressions. The 14 database opt-in
skips are unchanged from the worker-boundary run above; Docker's socket is still
absent. No MySQL/PostgreSQL refresh, performance smoke, hosted CI or full
application suite is claimed. Formatting passed; existing compiler/weaving,
Mockito-agent and HSQL cleanup warnings remain.

Drain older repair binaries before relying on this behavior. Existing task errors
are deliberately not rewritten. Ordinary callback/legacy task stack exposure and
task access policy require a separate review; this is repair-specific data
minimization, not comprehensive task-error confidentiality. No queue enablement,
schema migration, deployment, generic-library change or historical data update
was performed. Admission, fencing, lifetime and rollout gates remain open.
Independent scoped source, test and documentation review found no actionable
defects in this repair change.

## Store-Clock Terminal Retention (2026-09-10)

Scheduled retention calculated its cutoff from the application clock even though
JDBC writes terminal `updated_date` using database time. Two HSQL regressions
failed, including configured reruns: a host ahead of the database deleted a fresh
DONE row, while a host behind it retained an expired one
(`/tmp/queue-retention-clock-reproduction.log`, 05:07:32 UTC). The fixture offsets
the store's SQL clock in both directions, without changing the host or live data.

The cleaner now passes a relative age through `deleteTerminalJobsOlderThan`.
JDBC samples database time and invokes the existing private DELETE helper inside
one short REQUIRES_NEW transaction. In-memory retention uses the same clock as
its transitions. The absolute-cutoff API and existing SQL remain unchanged,
including strict cutoff comparison, queue/terminal-state rechecks and batch bounds.
Invalid age, missing clock or out-of-range cutoff prevents deletion. The new
default method fails closed for custom stores that have not implemented their
clock contract; it does not silently fall back to host time.

Coverage includes both skew directions, DONE/FAILED equality and microsecond
boundaries, oldest-first bounded batches, queue scope, age/limit/status rejection,
clock failure/underflow, in-memory state preservation, exact configured durations
and unsupported-store refusal. A separate real HSQL transaction test observes the
same bound connection at clock read and DELETE, then injects failure after DELETE
before commit and verifies rollback. The skew fixture's fixed-clock constructor
has no transaction template; it is not the transaction-participation proof.

Focused verification passed **78/78 tests**. The first post-fix invocation failed
test compilation because this project does not include Awaitility; the test now
uses a bounded JDK-only clock wait, without adding a dependency. Final broader
verification passed **897 tests, 14 skipped across 68 suites**, with no failures,
errors or rerun/flaky XML entries (`/tmp/queue-retention-clock-final.log`, 05:15:07
UTC). The 14 opt-in database skips match the preceding run; Docker's socket is
absent. Other Docker-only suites and performance tests were not requested.

The 29-source independent engine rebuild passed at 05:14:51 UTC; its separate
ordinary-JAR consumer passed **10 tests, six database cases skipped** at 05:15:35
UTC (`/tmp/queue-retention-clock-engine.log`, `/tmp/queue-retention-clock-consumer.log`).
This is not fresh MySQL/PostgreSQL execution evidence. Formatting and independent
scoped source/test review passed; existing compiler/weaving and test-runtime/HSQL
cleanup warnings remain. No full application suite, hosted CI or deployment.

Drain older cleaners before relying on this fix. Database clock jumps, migration
adoption, repair/publication pins, blob lifetime and the other rollout gates remain
unresolved. No schema, enablement or primary-checkout changes were made.

## Replay Diagnostic Failure Isolation (2026-09-10)

Replay could acknowledge its FAILED-to-QUEUED transaction and then throw because
a counter or log failed. Broken local-wakeup diagnostics also skipped the remote
hint. Expanded fault reproduction failed **35 of 36 cases** (29 failures, six
errors, including reruns) at 05:30:52 UTC
(`/tmp/queue-replay-diagnostics-expanded-reproduction.log`). Separately, both real
HSQL replay tests failed with Micrometer gauge/counter type collisions at 05:31:04
UTC (`/tmp/queue-replay-diagnostics-hsql-reproduction.log`).

Replay counters and logs now run through a local diagnostic guard, with at most
one guarded fallback warning and no recursion. Nonfatal failures cannot change
the result, replace the original validation/store/lookup error or prevent the
independent remote hint after a local wakeup failure. Fatal causes/suppressed
errors escape as the original JVM Error. Read-inspection instrumentation, SQL,
transaction boundaries, replay mutation/readback order and public APIs are unchanged.

The final 44-case fault matrix covers counter registration/increment, logging,
combined failures, successful replay, each failed wakeup, invalid payload,
cross-queue/not-failed rejection, original runtime exceptions/nonfatal Errors,
post-replay lookup failure, and nested/suppressed fatal identity. The lost-ack
control mutates an in-memory delegate then throws at the store API boundary; it
is not a JDBC commit/network fault. Both HSQL cases use an explicit transaction
manager and independently observe committed QUEUED state outside a transaction
at the first wakeup. They verify one mutation, both hints, replacement payload,
retained error and the next claim's reset attempt budget.

Focused verification passed **113 tests** before the eight additional original
Error/lost-ack controls. Final verification passed **943 tests, 14 skipped across
69 suites**, with no failures/errors or rerun/flaky XML entries at 05:35:11 UTC
(`/tmp/queue-replay-diagnostics-final.log`). The opt-in database skips are unchanged;
Docker's socket remains absent. Formatting and independent scoped review passed.
Existing weaving/deprecation and HSQL cleanup warnings remain; no full application
suite, performance rerun, hosted CI or deployment is claimed.

The 29-source independent engine rebuilt at 05:34:46 UTC, and its ordinary-JAR
consumer passed **10 tests, six database skips** at 05:35:59 UTC
(`/tmp/queue-replay-diagnostics-engine.log`, `/tmp/queue-replay-diagnostics-consumer.log`).
The JAR-origin/byte-equality gate passes; this is not refreshed MySQL/PostgreSQL proof.

This is diagnostic isolation, not durable replay acknowledgement or idempotency.
A genuine store acknowledgement or subsequent detail lookup can still fail after
the mutation committed; callers must not infer rollback or blindly repeat replay.
Keep HTTP replay and domain task reopening gated on an explicit receipt/recovery
and audit contract. No schema, enablement or primary-checkout changes were made.

## Malformed Input Terminalization (2026-09-10)

The assetlocalize adapter previously spent the ordinary retry budget rereading
malformed persisted identity/input. Existing runtime regressions used a budget of
one, hiding that distinction. Raising it to five and asserting the direct marker
reproduced eight failures in 40 selected tests, including their configured reruns,
at 05:54:32 UTC (`/tmp/queue-malformed-terminal-reproduction.log`).

Execution now converts invalid envelope identity and stored input JSON syntax/shape
errors (including duplicate/trailing/null documents) into a direct
`AsyncJobPermanentFailureException`. The generic runtime's existing acknowledged,
lease-fenced FAILED transition still owns terminalization. Parsing causes/source
values are not retained. Retrieval remains outside the parsing catch; missing
tasks/blobs, storage errors, mapper-definition/other decoding errors, generation
and output errors keep ordinary policies. Eligibility was unchanged by this pass;
the later [unsupported-tracking rejection](#unsupported-tracking-permanent-rejection-2026-09-10)
changes its disposition. Callback/repair envelope
decoders are unchanged: malformed identity cannot be used to guess a task.

The checked marker describes retry policy, not an expected user error. Its task
failure callback explicitly preserves unexpected classification. New tests use the
real exception utility and holder JSON; a real HSQL task/MVC regression verifies
persisted generic error JSON, redacted stack/task/inspection responses, and that
correcting input plus raw replay cannot reopen the task. Two runtime tests use the
real blob reader over an in-memory storage adapter to recover missing input and a
storage exception on attempt two, with one generation/publication and no failure
callback. These are not real cloud failure or retention-expiry tests.

An initial verification run caught new fixture mistakes (a nullable Mockito matcher
unboxed to primitive long, and a mapper whose deserializer had already been cached).
Both were corrected; 59 focused tests passed at 05:59:21 UTC
(`/tmp/queue-malformed-terminal-focused-final.log`). Scoped review additionally
identified an executor handoff race in the new recovery tests. Only those tests
now use a one-slot buffer so a returning worker does not spuriously reject the
next attempt; production executor configuration is unchanged. Final reviewed
verification with `-Pno-local-config` passed **948 tests, 14 skipped across 69
suites**, with zero failures/errors or rerun/flaky XML entries at 06:06:08 UTC
(`/tmp/queue-malformed-terminal-reviewed.log`). Formatting, diff checks and
independent scoped review pass.
Docker's socket remains absent; no refreshed real MySQL/PostgreSQL, performance,
hosted CI or full application suite is claimed. Existing weaving/deprecation and
HSQL cleanup warnings remain. The generic queue/library sources are unchanged.

Early terminalization deliberately removes the remaining automatic-retry window
for editing malformed input. Domain-aware replay, durable admission and blob
pins/reference-aware cleanup remain rollout gates. No SQL, migration, queue
enablement or primary-checkout changes were made.

## Queue Fan-Out Resolution Preflight (2026-09-10)

The parallel parent resolved and submitted each locale in sequence. A later missing
membership or lookup failure therefore left earlier children accepted. An explicit
output alias bypassed the parent's missing-membership dereference even though child
generation still requires that membership. Three regressions failed before the fix,
including their configured reruns, at 06:17:27 UTC
(`/tmp/queue-fanout-preflight-reproduction.log`).

With all three queue producer flags enabled and a null tracking name, the parent
now resolves all locale IDs, memberships and output tags before child submission.
The ordered in-memory plan stores three scalar values per entry, not mutable request
objects, ORM entities or full child payloads. Scheduling uses that plan without a
second lookup. Quartz routes retain lazy resolution; null output overrides, explicit
aliases, duplicate-tag map overwrite behavior and empty requests remain compatible.
Memory is O(N), with no new independent fan-out limit; a first-submission failure
now follows all N lookups on the queue route.

Twelve added tests cover missing membership with/without an alias, lookup/default-tag
failures, invalid IDs/entries, empty requests, scalar snapshots despite request mutation,
lookup counts, duplicate aliases and Quartz lazy-order controls. Existing ambiguous
submission tests still preserve the first accepted child without fallback. These
parent tests use mocked repositories and submission services, not durable admission.

Focused verification passed 55 tests at 06:21:36 UTC
(`/tmp/queue-fanout-preflight-focused.log`). The broader queue/admin/assetlocalize,
parallel-parent and API-routing/error selection with `-Pno-local-config` passed
**1,018 tests, 14 skipped across 73 suites** at 06:23:39 UTC
(`/tmp/queue-fanout-preflight-final.log`), with no failures/errors or rerun/flaky XML
entries. Formatting, diff checks and scoped independent review pass. Docker's socket
remains absent, so real MySQL/PostgreSQL and performance/soak verification are not
refreshed. Existing weaving/deprecation and HSQL cleanup warnings remain.

This prevents only resolution-detectable partial fan-out. The parent task can already
exist, repository configuration can change afterward, and task/blob/enqueue failures
can still strand accepted children. Durable manifests, child idempotency, admission
recovery and a duplicate-tag policy remain gates. No SQL/migration, generic library,
queue enablement or primary-checkout changes were made. The admission note also now
matches the previously implemented first-attempt malformed-input terminalization.

## Independent JPA Consumer Boundary (2026-09-10)

The ordinary-JAR probe now has an opt-in, test-only `jpa` consumer profile. It
adds Hibernate ORM/HSQL and a tiny host entity without changing the engine's
dependencies or production sources. Public queue configuration accepts a host's
preinitialized `JpaTransactionManager`; no Mojito entity, AspectJ advice or
package-private store construction/enlistment is used. Default consumer checks
still reject Hibernate, and both lanes require clean compilation with explicit
optional-class presence/absence assertions.

Six tests prove physical non-autocommit READ_COMMITTED queue transactions versus
SERIALIZABLE host work, distinct inner resources and exact outer restoration,
queue commit surviving host rollback, writable enqueue from a read-only host,
rollback after a real INSERT without poisoning the outer JPA commit, and a real
worker running/committing its own business transaction with empty thread resources
before/after processing and completion. Two cases specifically exercise the
JDBC-versus-manager and factory-versus-manager datasource rejection paths.
The host pins `HibernateJpaDialect` and `DELAYED_ACQUISITION_AND_HOLD`; this does
not certify all JPA providers, initialization/advice modes or driver versions.
Public enqueue still commits independently of host business work. Atomic admission,
the package-local enlistment primitive and real-DB JPA upgrade paths remain separate.

Review corrected metadata-only isolation checks and initially overlapping mismatch
cases. A first compile hit an AssertJ generic-overload ambiguity; a typed Connection
resolved it. The next execution caught Spring reinitialization undoing the deliberately
mismatched manager, so the fixture now registers the already-initialized host manager
as a singleton rather than silently testing a different guard. Branch-specific error
messages remain asserted. The final fixture stops its worker/scheduler before closing
the factory and disposable HSQL database; worker assertion failures cannot hide in
runtime callback handling.

The independent engine rebuild passed at 06:43:11 UTC
(`/tmp/queue-library-jpa-engine.log`). Final JPA consumer verification passed
**16 tests, six real-DB skips across five suites** at 06:52:11 UTC
(`/tmp/queue-library-jpa-reviewed.log`). A clean switch back to the lean lane passed
**10 tests, six real-DB skips** at 06:50:12 UTC (`/tmp/queue-library-jpa-lean.log`).
Report audits found no failures/errors or rerun/flaky entries. Root and sidecar
formatting passed. Existing deprecation and Mockito test-agent warnings remain;
negative bootstrap tests deliberately log rejected contexts. The CI job now contains
both lanes, but no hosted CI run or refreshed MySQL/PostgreSQL result is claimed.
No migration, queue enablement, module extraction, push or primary-checkout change.

## Retained Output Encoding Boundary (2026-09-10)

Private and legacy retained output passed through `BlobStorage.getString`, which
silently replaced malformed UTF-8 with U+FFFD before JSON validation. Four byte-backed
regressions reproduced successful publication/task completion through both the
completion callback and terminal repair at 07:09:12 UTC, including their configured
reruns (`/tmp/queue-output-utf8-reproduction-fixed.log`). An earlier test-authoring
compile error was corrected before this reproduction; it was not a product failure.

The output adapter now reads raw bytes and uses an explicit reporting UTF-8 decoder
before the existing strict JSON reader. An additive PollableTask raw-output accessor
keeps canonical key construction in its current owner. Retrieval stays outside the
validation catch, preserving storage exception identity. Invalid encoding has the
same redacted, cause-free diagnostic as invalid JSON; it leaves the queue row DONE,
retained/canonical bytes unchanged and the task unfinished. Legacy publication still
validates without rewriting canonical output. General-purpose string readers, input
decoding, transaction boundaries, null/empty field compatibility and retention are
unchanged. This is not checksum/ownership binding or a restoration mechanism.

Eight byte-backed adapter tests cover private/legacy completion and repair, invalid
continuations, overlong/truncated sequences, encoded surrogates and out-of-range code
points. UTF-16 documents and a framing BOM remain rejected; valid literal U+FFFD,
embedded U+FEFF, supplementary Unicode and escaped content remain compatible. Provider
errors preserve identity. Two HSQL application tests verify raw byte preservation,
unchanged legacy replacement decoding and missing-blob behavior through the actual
PollableTask storage accessor. Existing String-backed output fixtures now expose bytes.

Focused verification passed **102 tests, two real-DB skips** at 07:12:00 UTC
(`/tmp/queue-output-utf8-focused.log`). After adding the encoding compatibility controls,
broader queue/admin/assetlocalize, routing, blob and PollableTask verification with
`-Pno-local-config` passed **1,031 tests, 14 real-DB skips across 75 suites** at
07:14:02 UTC (`/tmp/queue-output-utf8-final.log`). XML audits show zero failures/errors
or rerun/flaky entries. Formatting, diff checks and independent scoped review pass.
Existing weaving/deprecation, test-agent and HSQL cleanup warnings remain. Docker's
socket is unavailable; real MySQL/PostgreSQL, performance/soak and hosted CI results
are not refreshed. No SQL/migration, generic library, enablement, push or primary
checkout changes were made. Input byte validation remains a separate audit item.

## Stored Input Encoding Boundary (2026-09-10)

The worker's input path had the same replacement-decoding defect as retained output:
malformed UTF-8 inside otherwise-valid JSON reached generation and returned DONE.
A regression using the actual byte-backed BlobStorage reader failed against unchanged
production code, including its configured rerun, at 07:28:36 UTC
(`/tmp/queue-input-utf8-reproduction.log`). Twelve controls in that run passed.

An additive PollableTask raw-input accessor now feeds a reporting UTF-8 decoder in
the queue handler. Only that decoder's CharacterCodingException becomes the existing
cause-free permanent-input-failure signal. Blob retrieval stays outside its catch,
and mapper-error classification is unchanged. The runtime requests fenced FAILED on
the first attempt without generation, attempt-output storage or input rewriting.
General-purpose string/typed readers and Quartz retain legacy behavior. Valid literal
U+FFFD, embedded U+FEFF, supplementary Unicode, nullable defaults and configured
bindings remain compatible; UTF-16 documents and framing BOMs are not newly accepted.

Seven added tests cover malformed sequences, encoding/legacy-reader controls, actual
runtime terminalization and HSQL raw-accessor behavior. Existing input-read recovery,
task privacy/replay and retained-output tests now exercise byte reads. Azure fallback
still backfills before validation; a control verifies exact bytes and retention, not
replacement text, reach its mocked Azure writer. These reads use `format=bytes`
metrics. They are not read-only shadows or live cloud verification. Scoped review
caught an invalid-root fallback fixture that could mask the encoding failure; it now
uses an otherwise-valid DTO object containing one malformed byte.

Focused verification passed **99 tests, two real-DB skips** at 07:33:32 UTC
(`/tmp/queue-input-utf8-focused.log`). After the fallback control and its review fix,
the broader queue/admin/assetlocalize, storage-routing and PollableTask selection with
`-Pno-local-config` passed **1,066 tests, 14 real-DB skips across 76 suites** at
07:38:00 UTC (`/tmp/queue-input-utf8-reviewed.log`). XML audits show zero failures/errors
or rerun/flaky entries. Formatting, diff checks and scoped review pass. Existing
weaving/deprecation, test-agent and HSQL cleanup warnings remain; Docker's socket is
unavailable, so real MySQL/PostgreSQL, performance/soak and hosted CI are not refreshed.
No generic queue/library, SQL/migration, enablement, push or primary-checkout changes.
Size limits, versioned admission, checksum/ownership binding, lifetime recovery and
domain-aware replay remain separate gates; early terminalization is not blob repair.

## Retention Diagnostic Outcome Isolation (2026-09-10)

Retention wrapped the store DELETE and its success diagnostics in one catch. A
counter failure after commit therefore reported a database cleanup failure; a
second diagnostic failure could abort the remaining queue/status passes. The
pre-fix HSQL regression committed four bounded deletions but created four false
failure counters, failing again on its configured rerun at 07:50:40 UTC
(`/tmp/queue-retention-diagnostics-reproduction.log`).

The cleaner now separates the store attempt from independently guarded logging
and counters. Each queue/status still receives at most one bounded store call per
sweep, with no immediate retry after an uncertain outcome. Nonfatal diagnostics
cannot reclassify acknowledged deletes or starve later passes. Direct, wrapped
and suppressed JVM-fatal errors propagate as the original Error. This may stop a
sweep after commit; a failure metric is not proof of rollback, and unavailable
counters may undercount. Defaults, age/batch bounds and transaction ownership are
unchanged. This is not a general instrumentation rewrite or a retention pin.

Sixteen deterministic tests exercise registration, increment and logging faults,
combined/fallback failures, zero deletions and exact fatal-error propagation. Two
real transaction-managed HSQL tests preserve recent/queued/running rows across
bounded sweeps and verify no repeated delete after a committed mutation followed
by an exception and broken failure diagnostics. The latter injects acknowledgement
loss at the store-service boundary, not a JDBC commit response or network fault.
Scoped independent review of that test found no issue; it does not constitute an
independent audit of the whole queue.

Focused verification passed **95 tests without skips** at 07:56:59 UTC
(`/tmp/queue-retention-diagnostics-focused.log`). Broader queue/admin/assetlocalize,
storage-routing and PollableTask verification with `-Pno-local-config` passed
**1,084 tests, 14 real-DB skips across 77 suites** at 07:58:33 UTC
(`/tmp/queue-retention-diagnostics-final.log`). The independent 29-source engine
rebuilt at 07:56:48 UTC, and its ordinary-JAR lean consumer passed **10 tests with
six real-DB skips** at 07:57:42 UTC (`/tmp/queue-retention-diagnostics-engine.log`,
`/tmp/queue-retention-diagnostics-consumer.log`). XML audits found no failures,
errors or rerun/flaky entries. The optional JPA lane was not refreshed this pass.
Existing weaving/deprecation, test-agent and HSQL cleanup warnings remain. Docker's
socket is unavailable, so real MySQL/PostgreSQL, performance/soak and hosted CI
results are not refreshed. No SQL/migration, enablement, publication, push or
primary-checkout changes were made. Admission/recovery, domain fencing and blob
lifetime gates remain open; this evidence does not establish production readiness.

## Enlisted Producer And Consumer Boundary (2026-09-10)

Four new real-JPA/runtime contracts gate the producer after PollableTask flush and
enlisted queue insertion. An independent MVCC reader sees neither row, and a
completed runtime poll claims zero jobs and dispatches no handler while the producer
is held open. Explicit rollback and rollback-then-throw remain invisible and
non-executable. Commit and commit-then-throw expose one job whose handler reads the
real task, commits a separate JPA business update and reaches DONE on its first
attempt. A subsequent poll claims zero jobs. All threads use the existing configured
manager; no additional transaction manager or production advice is introduced.

Commit faults are armed only after the pre-commit poll returns, so another queue
transaction cannot consume the intended producer failure. Worker assertion failures
are captured for the test thread; latches are released and executors terminated in
cleanup. Scoped review identified a timing-dependent duplicate-dispatch assertion:
immediate Mockito counts could miss a newly submitted worker. Explicit poll
`claimedCount` assertions now cover pre-commit, post-resolution and post-DONE polls;
the revised diff has no remaining scoped findings.

This is a test-only prerequisite, not a newly fixed product defect or a complete
admission implementation. The consumer dispatch occurs after the producer future
resolves; execution during a withheld commit response, durable request uniqueness,
ACCEPTED-state transition, HTTP retry/lookup and parent reconciliation remain open.
Default fixture selection executes HSQL MVCC only. MySQL/PostgreSQL versions of
these tests require the opt-in container lane; they are not established by a green
default run. No production code, SQL/migration, routing, generic library, enablement,
push or primary-checkout changes were made.

Focused verification passed **109 tests without skips across five suites** at
08:17:00 UTC (`/tmp/queue-enlisted-consumer-reviewed.log`). After the review-led
claim-count strengthening, broader queue/admin/assetlocalize, storage-routing and
PollableTask verification with `-Pno-local-config` passed **1,088 tests, 14 real-DB
skips across 77 suites** at 08:19:08 UTC (`/tmp/queue-enlisted-consumer-final.log`).
All four new cases appear as HSQL tests in the reports. XML audits show no failures,
errors or rerun/flaky entries. Formatting and diff checks pass. Existing weaving/
deprecation, test-agent and HSQL fixture warnings remain. Real MySQL/PostgreSQL,
performance/soak, isolated-JAR consumer and hosted CI results were not refreshed.

## Execution Before Producer Acknowledgement (2026-09-10)

Two added HSQL JPA/runtime contracts close the previously excluded JDBC-seam
window above. The fixture now optionally holds the result after an actual commit
or injected rollback but before returning the original SQL exception. It captures
and consumes the fault, exception and callback together before the JDBC operation;
neither the operation nor the blocking callback holds the configuration lock.
Intervening consumer/worker commits cannot inherit the producer's gate. An explicit
callback count checks one-shot consumption. Existing default fault behavior is kept.

While the producer future remains incomplete, committed work reaches DONE and its
worker-owned JPA update is visible on another connection. Rolled-back task/queue
rows remain absent and the consumer claims zero jobs. Releasing the result delivers
the original SQLException without changing the observed queue snapshot or committed
business update. Existing pre-commit isolation, bounded thread cleanup and captured
handler assertions remain in the same helper. Scoped review found no actionable
race, false-positive or cleanup issue.

This is expected transaction behavior, not a new production fix: a waiting or failed
producer call is not evidence that the work has not executed. It reinforces the
need for durable scoped identity, primary resolution and no compensating task failure
or fallback after uncertain acceptance. There is still no admission reservation,
ACCEPTED state, keyed HTTP recovery or parent reconciler. These tests hold the JDBC
response locally; they do not inject actual network faults or certify real database
driver failure behavior. No production code, schema, enablement, generic-library,
push or primary-checkout changes were made.

Focused JPA/output-retry verification passed **42 tests, two real-DB skips** at
08:34:24 UTC (`/tmp/queue-withheld-commit-focused.log`). After adding the explicit
one-shot callback assertion, broader queue/admin/assetlocalize, storage-routing and
PollableTask verification with `-Pno-local-config` passed **1,090 tests, 14 real-DB
skips across 77 suites** at 08:36:07 UTC (`/tmp/queue-withheld-commit-final.log`).
Both new cases are HSQL executions; XML audits found zero failures/errors or
rerun/flaky entries. Formatting and diff checks pass. Existing weaving/deprecation,
test-agent and HSQL fixture warnings remain. Docker's socket was checked and is
unavailable; real MySQL/PostgreSQL, performance/soak, independent-JAR consumer and
hosted CI results were not refreshed.

## Submission Preparation Diagnostic Privacy (2026-09-10)

Pre-enqueue input/payload preparation failures passed their original throwable to
PollableTask completion. Even unexpected errors retained its full stack/cause graph
in `error_stacks`, exposed by the translator-accessible task and inspection routes.
A unit regression failed against unchanged production at 08:47:05 UTC
(`/tmp/queue-preparation-privacy-unit-reproduction.log`). Three HSQL persistence/MVC
cases then reproduced both response disclosures and the persisted raw diagnostics,
including a committed finish followed by an exception, at 08:50:22 UTC
(`/tmp/queue-preparation-privacy-http-reproduction.log`). All failed again on their
configured reruns. Tests use synthetic markers, not real private data.

Submission now records the raw preparation failure with task correlation through
the existing guarded logger, but passes a new cause-free IllegalStateException to
task completion. Task error classification is unexpected; the persisted stack and
both translator responses contain only the generic preparation message/task ID.
The original throwable still reaches the caller. Nonfatal failure of the submission
logger cannot prevent compensation or replace that throwable. No queue insertion
is attempted; an input write may already have completed before throwing. Existing
unknown-enqueue containment remains unchanged and must not finish the task.

One added unit test uses the real exception classifier and a broken logger;
three persisted-task/MVC tests cover input storage, payload serialization and lost
finish acknowledgement. The latter faults after the real task update returns, not
inside JDBC commit. It verifies a single finish attempt and retained redaction.
Existing unit expectations now inspect the sanitized, cause-free diagnostic.
Historical rows, synchronous caller errors, operator logs, ordinary callbacks,
global PollableTask behavior and access control are unchanged. Drain older queue
producers before relying on this boundary. Submission's direct-only fatal-error
classification remains a separate audit item, not a new fatal-containment guarantee.

The focused eight-suite selection passed at 08:52:02 UTC: 82 passed, 2 skipped,
84 total (`/tmp/queue-preparation-privacy-focused.log`). The broader queue, admin,
assetlocalize, storage and authorization selection passed at 08:56:13 UTC:
1,094 passed, 14 skipped, 1,108 total across 78 suites
(`/tmp/queue-preparation-privacy-final.log`). Current Surefire XML contains no
failures, errors or flaky/rerun entries. A second scoped code review found no
actionable findings; that reviewer did not independently execute tests.
Root formatting and diff checks passed. Existing weaving/deprecation, test-agent
and HSQL fixture warnings remain. Real MySQL/PostgreSQL, network/performance soak,
independent-JAR and hosted CI verification were not refreshed in this pass.

## Unexpected Failure Callback Privacy (2026-09-10)

The terminal callback still passed raw unexpected handler failures to PollableTask
completion, unlike the preparation and repair paths corrected earlier. A unit
selection against unchanged production reproduced three failures (including their
configured reruns) at 09:07:24 UTC: RuntimeException, a nonfatal Error and the
direct permanent-failure signal all retained raw diagnostics. Its checked business
exception compatibility control passed
(`/tmp/queue-failure-callback-privacy-unit-reproduction.log`).

The adapter now records a fresh, cause-free unexpected task error with the queue
ID for those categories, matching FAILED-row repair. It does not copy lastError
or the handler's cause/suppressed graph to the translator-readable stack. The
runtime still records bounded original diagnostics on the queue row and logs the
handler failure before the terminal callback. No generic engine API or failure
policy changes: ordinary failures still exhaust their configured budget, direct
permanent failures request first-attempt FAILED, and only an acknowledged fenced
transition calls completion.

Ordinary checked business exceptions deliberately retain the existing expected
message/type/stack contract; this is not comprehensive task-error confidentiality.
Historical tasks, Quartz, global error serialization, access controls and stored
queue diagnostics are unchanged. Drain older queue consumers before relying on
the new boundary. Callbacks remain best-effort and read-time finished-task guards
do not fence concurrent task timeouts or provide durable publication/reconciliation.

The first focused handler/input/repair/output-retry selection passed at 09:11:22
UTC: 65 passed, 2 skipped, 67 total
(`/tmp/queue-failure-callback-privacy-focused.log`). Updated raw-replay, malformed
input and tracked-rejection controls check generic task stacks while retaining
original queue diagnostics and their existing retry/no-regeneration assertions.

Three additional tests run the real runtime with an in-memory queue, real HSQL
task persistence and translator MockMvc endpoints. They cover ordinary retry
exhaustion, a direct permanent failure and a task finish that commits before a
service-boundary acknowledgement failure. They observe the worker returning after
its callback, unchanged operator-facing diagnostics, both redacted HTTP responses,
and no callback retry or regeneration from subsequent polls. This is not real
JDBC queue, network or durable-callback proof. The seven-suite selection passed
73 tests with 2 skips at 09:15:01 UTC (`/tmp/queue-failure-callback-privacy-http.log`).

The first broad run exposed four obsolete raw-classifier expectations at 09:16:56
UTC (`/tmp/queue-failure-callback-privacy-final.log`). They now capture the generic
error at task completion, retaining permanent-failure and repair checks; the
helper waits for the callback rather than assuming FAILED means it has finished.
After formatting and self-review, the final 79-suite selection passed at 09:19:18
UTC: 1,101 passed, 14 skipped, 1,115 total
(`/tmp/queue-failure-callback-privacy-reviewed.log`). Surefire XML has no failures,
errors or flaky/rerun entries. A second scoped production/unit review found no
actionable findings; that reviewer did not execute tests. Existing weaving,
deprecation, test-agent and HSQL fixture warnings remain. MySQL/PostgreSQL,
performance/soak, independent-JAR and hosted CI results were not refreshed.

## Winning Output Expiry And Terminal Repair (2026-09-10)

Two cross-component regressions in `AssetLocalizeAsyncJobOutputRetryIntegrationTest`
now combine real generation, HSQL PollableTask/blob persistence and the ordinary
`DatabaseBlobStorage.deleteExpired` cleaner with the real queue runtime over an
in-memory queue. A service-boundary callback fault occurs either before canonical
publication or before task finish after publication; in both cases the queue is
already DONE. The fixture ages only the private winner past its stored TTL, then
verifies that the real cleaner removes it. No direct delete or mocked missing read
stands in for cleanup. A separate application database and disabled scheduled
cleanup prevent an unrelated cached context or background sweep from deleting the
winner first; the test also asserts that the periodic trigger bean is absent.

Repeated repair must preserve the full pending task row, DONE row, input and any
surviving canonical output. It must report the missing private output without
publishing, finishing, rerunning generation or retrying the terminal callback.
Changed TM content makes accidental regeneration observable. Restoring the exact
original bytes at the original key from a test-held backup allows normal repair,
and subsequent repair is idempotent. This is not a restoration API, checksum or
ownership binding, automatic reconciliation, durable pinning, a naturally elapsed
day, real-MySQL cleanup, or cloud/prefix-policy validation. No production behavior,
DDL, retention policy or default-off routing changed. The lifetime gate remains open.

Verification: an initial compile failed on one stale boolean helper call, which was
corrected before tests ran. The four-suite focused run then passed 68 tests with two
skips at 09:37:30 UTC (`/tmp/queue-winning-output-expiry-focused-corrected.log`).
Scoped review identified the background-cleaner race; after isolation and root
formatting, the final 79-suite selection passed 1,103 tests with 14 skips at
09:43:06 UTC (`/tmp/queue-winning-output-expiry-reviewed.log`). Surefire XML has
no failures, errors or flaky/rerun entries. Re-review found no remaining actionable
findings in this test change; the reviewer did not execute tests. Existing weaving,
deprecation, test-agent and HSQL fixture warnings remain. Real-database,
performance/soak, independent-JAR and hosted CI evidence was not refreshed.

## Decoded Unicode Integrity (2026-09-10)

The stored-input/output review found that raw UTF-8 validation alone admits JSON
escapes containing unpaired UTF-16 surrogates. Those decoded Java strings can later
be lossily encoded as `?`; a retained private result could be accepted by repair.
Five targeted regressions failed before wiring the fix at 09:54:55 UTC
(`/tmp/queue-decoded-unicode-reproduction.log`): malformed escaped input, malformed
generated output, private and legacy retained output, and the real generation/blob/
task repair fixture. The rerun-on-failure attempts failed as well.

A small package-private character scan now covers all decoded
string fields and string-list entries in the assetlocalize DTO. Stored input
requests first-attempt FAILED before generation. Invalid generated output throws
before private storage and keeps ordinary bounded handler retries. Invalid private
or legacy retained output refuses publication/completion/repair with generic,
cause-free diagnostics. The HSQL repair fixture preserves the open task, DONE row,
input and canonical output until the original winning blob is restored.
An in-memory runtime regression also verifies first-attempt FAILED with a redacted
task failure, no generation/output calls and no remaining claimable row.

Paired supplementary characters, literal U+FFFD/BOM, controls and legacy null
fields/list entries remain compatible. A field-inventory test forces review when
string/list DTO fields are added. Shared mapper configuration and general-purpose
Quartz/blob readers are unchanged. This is not full XML validation, arbitrary
custom-serializer validation, recovery of already-replaced historical characters,
immutable payload ownership, checksum verification or the proposed versioned
execution-envelope reader. No schema, routing or generic queue API changed.

Verification: the seven-suite focused selection passed 106 tests with two skips
at 09:56:30 UTC (`/tmp/queue-decoded-unicode-focused.log`). After root formatting,
the final 82-suite selection, including both admission-model/decoder suites, passed
1,160 tests with 14 skips at 09:58:52 UTC (`/tmp/queue-decoded-unicode-final.log`).
Surefire XML has no failures, errors or flaky/rerun entries. Ten test methods were
added and the existing malformed-winner repair test was strengthened; the larger
selection also includes previously existing admission tests. Scoped production
review found no actionable issues; that reviewer authored the helper tests but
did not run Maven. Existing weaving, deprecation, test-agent and HSQL fixture
warnings remain. Real MySQL/PostgreSQL, performance/soak, independent-JAR and
hosted CI results were not refreshed.

## Producer Fatal Error Graphs (2026-09-10)

The asset producer previously tested only the outer throwable for JVM-fatal errors.
A storage/transaction wrapper with a fatal cause or suppressed cleanup error could
therefore record an ordinary preparation failure and finish the PollableTask. Two
HSQL regressions reproduce that behavior after the real input blob service returns:
the input is committed, then a wrapper is thrown. Both tests and their reruns failed
at 10:09:55 UTC (`/tmp/queue-producer-fatal-reproduction.log`), including full pending-
task-row preservation assertions, not just the thrown exception's type.

All six catch boundaries in the asset submission adapter now traverse causes and
suppressed errors before ordinary metrics, compensation or warning fallbacks.
The original JVM-fatal object is rethrown; a direct fatal takes the allocation-free
fast path, and identity-based iterative traversal handles cycles without recursion
or graph mutation. Existing ThreadDeath-subclass handling is retained. The helper
stays private to this adapter rather than exposing the generic queue's internal
classifier as new library API.

The real HSQL tests preserve the full pending task row and byte-exact committed
input without enqueue or failure metrics. The input acknowledgement fault is at
the service boundary, not inside JDBC commit or a network transport. Actual memory
exhaustion, process fail-stop, already-swallowed downstream fatal errors, orphan
preparation and accepted-enqueue recovery remain outside this change. No schema,
generic runtime, default-off routing, historical task or shared error policy changed.

The stage-specific unit matrix adds 18 test methods covering wrapped/suppressed
VM errors and ThreadDeath subclasses at task creation, input save, payload
serialization, enqueue, success/failure/unknown-outcome metrics, logging, exception
classification and finish compensation. Mixed cyclic graphs, equal-but-distinct
throwables and a 20,000-node cause chain retain their identities and links;
ordinary nonfatal cycles retain the existing failure policy. A scoped agent review
found no further defect in this adapter; it did not run Maven.

Verification: root `mvn -Pno-local-config spotless:apply` passed. All three producer
suites passed 40 tests at 10:16:54 UTC (`/tmp/queue-producer-fatal-matrix.log`). The
broader queue/admin/assetlocalize/admission/blob selection passed 1,180 tests, with
14 opt-in skips, across 83 suites at 10:21:17 UTC
(`/tmp/queue-producer-fatal-reviewed.log`). Surefire XML contained no failures,
errors, flaky successes or rerun entries. Existing HSQL/Hibernate fixture warnings
remain; the earlier compile also retained weaving/deprecation/test-agent warnings.
Docker's local socket remains unavailable, so real MySQL/PostgreSQL, performance,
multi-host soak, independent-JAR and hosted CI evidence was not refreshed.

## Parent Admission Faults And Fan-Out Opt-In (2026-09-10)

A new three-case HSQL characterization executes the real
`GenerateMultiLocalizedAssetJob` through `QuartzPollableJob.execute`, with real
application task/blob persistence and a separate disposable JDBC queue database.
The commit wrapper is armed after the second queue INSERT, before that transaction
resolves; no task/blob commit can consume it. No worker or Quartz scheduler runs.
For three input locales, commit-then-throw leaves two runnable queue rows and two
pending child tasks. Rollback-then-throw leaves one runnable row and an orphan
second task/input. Both finish the parent with an error before persisting its
child map or attempting the third slot. A fresh job instance replaying the same
parent/task/input accepts three new children, loses old IDs from its published
map, and leaves the old parent error intact. Original accepted rows remain exact,
and their duplicate child inputs are byte-identical. The no-fault control retains
all three mappings while child tasks remain pending.

These tests deliberately pin the unsafe legacy behavior; they do not implement
or certify recovery, atomic JPA/JDBC admission, production dialects, network loss,
duplicate business effects or a scheduler/process restart. Replace those assertions
with stable-slot recovery contracts when v1 admission is implemented, rather than
using a passing characterization as enablement evidence. The initial focus run
at 10:36:08 UTC failed one new assertion and its rerun because it compared the
different ordering of `findByStatus` and `getByIds` (`/tmp/queue-parent-admission-focused.log`).
The assertion now compares full records independent of inspection ordering; no
production store behavior was changed to make it pass.

The investigation also exposed a rollout-control gap: enabling direct queue
admission automatically enrolled parallel children. The new default-routing
regression failed before the fix, including its rerun, at 10:38:31 UTC
(`/tmp/queue-parent-fanout-gate-reproduction.log`). The parent now requires
`l10n.org.async-job-queue.asset-localize.fanout-enabled=true` in addition to both
umbrella flags and the producer control. It defaults false and gates both eager
preflight and child dispatch. Direct API routing, configured Quartz child scheduler,
tracked-input exclusion, missing-service fail-closed behavior when fully opted in,
consumers and existing queue rows remain unchanged. The routing unit tests cover
default/false/alone controls and preserve all earlier queue-path tests with explicit
fan-out opt-in. The HSQL fixture checks actual Spring default binding before
explicitly opting into the unsafe-behavior characterization.

This limits accidental rollout, not admission uncertainty. It is a restart-applied
parent route control, not durable route pinning, a general internal adapter guard,
or safe replay permission. Already-enabled branch installations change new child
routing on upgrade; retain Quartz capacity, retire older parents that ignore the
flag, and reconcile partial parents rather than rerouting them. Both umbrella
flags remain default-off, and no schema or global Quartz behavior changed.

Verification: root `mvn -Pno-local-config spotless:apply` passed. The four focused
parent/JPA/direct-routing suites passed all 82 tests at 10:41:14 UTC
(`/tmp/queue-parent-fanout-gate-focused.log`). The broader queue/admin/assetlocalize/
admission/blob selection passed 1,187 tests with 14 opt-in skips across 84 suites
at 10:43:00 UTC (`/tmp/queue-parent-fanout-gate-broad.log`). Both XML audits found
no failures, errors, flaky successes or rerun entries. A read-only protocol review
found no untracked parent-recovery gap; a separate scoped review found no blocking
test or routing defect. Neither agent ran Maven. Existing weaving/deprecation,
test-agent and HSQL/Hibernate fixture warnings remain. No fresh MySQL/PostgreSQL,
performance/soak, independent-JAR, deployment or hosted CI evidence is claimed.

## Handler Submission During Coordinator Drain (2026-09-10)

The core lifecycle review reproduced a lock cycle independent of application
admission: coordinator stop held `lifecycleLock` while waiting for the worker
executor, but an active handler submitting a child synchronously needed the same
lock for its post-enqueue wakeup. Both ordinary stop and rollback after a later
queue failed startup exhausted the drain timeout before the handler could finish.
The two new regressions failed twice each at 11:00:11 UTC
(`/tmp/queue-coordinator-submission-drain-reproduction.log`). They latch at actual
executor shutdown entry and record termination immediately when executor shutdown
returns, before the coordinator can release its lock; eventual completion after
the timeout cannot pass the assertion.

Wakeups now read one volatile, immutable runtime/disabled-consumer snapshot rather
than acquiring the lifecycle lock. Successful startup publishes copied collections
as its last step; common cleanup withdraws the snapshot before draining any owned
runtime. Unpublished partial runtimes are still cleaned up, and start/stop remain
serialized. `isRunning()` is false during drain. Hints during startup/cleanup are
counted as `notRunning`, without rejecting the already-accepted enqueue. An old
snapshot can only reference old runtimes, whose own started gate rejects hints
after they stop. Restart refreshes both runtime and disabled-consumer state.

The tests use real executors/schedulers, the real submission API and an in-memory
store. Both stop/rollback cases require the parent to reach DONE, the child to
remain QUEUED with zero attempts, and restart to finish the child once. A third
regression covers stale-runtime hints across enabled/disabled restarts. There is
no change to SQL, transaction boundaries, lease/fencing or feature flags. This
does not guarantee simultaneous quiescence: queues still stop sequentially and
already-admitted polls may finish. Arbitrarily blocking handlers or infrastructure
can still exhaust the configured deadline.

Verification: root `mvn -Pno-local-config spotless:apply` passed. The initial
focused lifecycle/runtime/submission selection passed 360 tests across 14 suites
at 11:02:16 UTC (`/tmp/queue-coordinator-submission-drain-focused.log`). An
independent review found no production defect, but identified that a stuck test
lifecycle thread could deadlock its own cleanup; cleanup now avoids reacquiring
that lock after a failed join, explicitly stops owned resources, and retains
termination assertions. The final broader queue/admin/assetlocalize/admission/blob
selection passed 1,190 tests with 14 opt-in skips across 85 suites at 11:06:50 UTC
(`/tmp/queue-coordinator-submission-drain-reviewed.log`). Both XML audits found
no failures, errors, flaky successes or rerun entries. The final scoped cleanup
review had no further findings. Existing weaving/deprecation, test-agent and
HSQL/Hibernate fixture warnings remain; no fresh MySQL/PostgreSQL, performance/soak,
independent-JAR, deployment or hosted CI validation is claimed.

## Lease Mutation Commit Ambiguity (2026-09-10)

A bounded JDBC transaction/resource-ownership review found no new production
defect. Coverage already exercised enqueue/claim commit uncertainty and adapter
DONE commit loss, but heartbeat/retry/failure ambiguity primarily used in-memory
spies. Six new cases in the existing JPA/JDBC fixture now inject actual
commit-then-throw or rollback-then-throw for heartbeat, relative requeue and
terminal failure. The fault is armed immediately before the mutation with no
concurrent worker; exception identity, one-shot consumption and physical
commit/rollback counters are checked before any readback can commit.

Whole-row assertions preserve every field on rollback and verify the intended
payload, error, availability, timestamps, attempts and ownership on commit.
The peer cannot claim live, delayed or failed work. An ambiguous renewal still
permits its original owner to complete. Committed requeue/failure rejects all
four stale-owner mutations before and after a replacement claim using the same
worker ID. Retry readiness is explicitly advanced in the fixture; terminal work
requires explicit generic-store replay. Neither operation is a supported Mojito
task recovery workflow. Original or replacement owners subsequently complete,
and thread-bound JPA/JDBC resources are cleared.

This is a test-only change. It adds no schema, admission wiring, runtime policy or
feature enablement. HSQL runs by default; the fixture's MySQL/PostgreSQL lanes are
opt-in. Direct store calls do not establish callback delivery, actual network
faults, multi-host behavior or a durable admission receipt.

Verification: root `mvn -Pno-local-config spotless:apply` passed. The focused
JPA/store/heartbeat-transition selection passed 158 tests across three suites
at 11:20:30 UTC (`/tmp/queue-lease-mutation-ambiguity-focused.log`). The broader
queue/admin/assetlocalize/admission/blob selection passed 1,196 tests with 14
opt-in skips across 85 suites at 11:21:42 UTC
(`/tmp/queue-lease-mutation-ambiguity-broad.log`). Both XML audits found no
failures, errors, flaky successes or rerun entries. Independent read-only review
found no actionable issue in fault attribution, assertions or portability. Existing
weaving/deprecation, test-agent and HSQL/Hibernate fixture warnings remain. No
fresh MySQL/PostgreSQL, performance/soak, independent-JAR, deployment or hosted CI
verification is claimed.

## Unsupported Tracking Permanent Rejection (2026-09-10)

Persisted inputs with a non-null `pullRunName` were deliberately rejected before
generation, but the ordinary exception spent the remaining retry budget on work
that cannot become eligible through retry. Three runtime regressions with a
budget of five reproduced this for named, empty and whitespace-only tracking
values, each failing on both configured runs at 11:50:22 UTC
(`/tmp/queue-tracked-permanent-reproduction.log`).

The handler now directly throws `AsyncJobPermanentFailureException` for this
existing exclusion. Producer guards, Quartz routing, default flags, migrations
and ordinary missing-input/storage/generation/output failure policies are unchanged.
The runtime still owns the acknowledged, lease-fenced FAILED transition and its
best-effort callback. This does not fence business writes, enable tracked work,
guarantee callback delivery or make editing/replaying a finished task supported.

The regressions verify attempt-one failure, unchanged queue payload and input
bytes, no generation/private-output/publication, no further claim and an unexpected
redacted task error without cause or suppressed diagnostics. Existing handler
metric-failure coverage now expects the permanent marker. The real-service
tracked-input fixture also expects attempt one while preserving existing lineage;
ordinary untracked output-storage retry and transient input recovery still pass.

Verification: root formatting passed. The focused selection passed 131 tests
with two opt-in skips across six suites at 11:51:50 UTC
(`/tmp/queue-tracked-permanent-focused.log`). The broader queue/admin/assetlocalize/
task/blob selection passed 1,199 tests with 14 opt-in skips across 85 suites at
11:53:14 UTC (`/tmp/queue-tracked-permanent-broad.log`). Both XML audits found no
failures, errors, flaky successes or rerun entries. These runs use in-memory/HSQL
fixtures, not fresh MySQL/PostgreSQL, performance/soak or deployed verification.
Independent read-only review found no remaining issue in the narrow diff.
Existing deprecation/weaving, test-agent and HSQL/Hibernate fixture warnings remain.

## Fatal Error Subclass Classification (2026-09-10)

The generic classifier recognized `ThreadDeath` by exact class name, unlike its
type-based `VirtualMachineError` check. A subclass therefore became an ordinary
handler retry or terminal-callback warning. Three classifier cases and fifteen
runtime cases reproduced the defect at 12:04:18 UTC: 18 failures among 78 selected
tests, repeated on the configured rerun (`/tmp/queue-fatal-subclass-reproduction.log`).

The classifier now uses `instanceof ThreadDeath`. No caller changes from direct
classification to graph traversal, and no error graph is rewritten. Runtime tests
cover direct, wrapped and suppressed subclasses at initial/last-attempt processing,
DONE/FAILED callbacks and before-handler exhaustion callbacks. They require the
original fatal object to escape, preserve claimed or terminal queue state, prohibit
retry mutations and verify capacity/heartbeat cleanup. Ordinary assertion errors
and nonfatal cycles retain their existing policies. This is neither runtime/JVM
fail-stop nor business rollback, durable admission or callback recovery.

Focused verification passed 193 tests across seven suites at 12:05:38 UTC
(`/tmp/queue-fatal-subclass-focused.log`). The broader selection passed 1,217 tests
with 14 opt-in skips across 85 suites at 12:06:46 UTC
(`/tmp/queue-fatal-subclass-broad.log`). Both XML audits found no failures, errors,
flaky successes or rerun entries. Independent read-only review found no blocker
in Java 21 compatibility, classification semantics or test cleanup.

The 29-source standalone engine rebuilt at 12:07:05 UTC
(`/tmp/queue-fatal-subclass-engine.log`). Its clean lean consumer passed 11 tests
with six opt-in real-database skips and no XML rerun/flaky entries at 12:07:29 UTC
(`/tmp/queue-fatal-subclass-consumer.log`), including all-class JAR provenance.
Root formatting passed. This pass did not rerun the optional JPA consumer profile.
The explicit type check adds a visible
JDK deprecation/removal warning for `ThreadDeath`; no warning is suppressed.
Existing application weaving/test-agent/HSQL warnings remain. Real-database and
soak verification remain separate; no schema or default flag changed.

## Input Write Diagnostic Isolation (2026-09-10)

Real task/blob submission regressions reproduced two input-write defects: a
payload-summary meter collision prevented storage, while a success-duration
collision rejected a write that had returned successfully. Both tests errored on
both configured runs at 12:19:02 UTC
(`/tmp/queue-input-diagnostics-integration-red.log`). The timer in `finally` could
also replace the original storage exception.

`PollableTaskBlobStorage.saveInput` now isolates nonfatal summary, duration and log
failures. Only the storage call determines the write result. Fatal errors exposed
by storage escape before further diagnostics; fatal causes/suppressed errors from
diagnostics also escape. The local iterative identity-based check preserves the
fatal object without rewriting error graphs or importing generic queue internals.
This is a shared Quartz/queue input method, not a queue-flag-gated change. JSON,
keys, retention, metadata/provider resolution and output methods are unchanged.
Unknown storage outcomes, backend-side error handling, atomic admission and blob
lifetime ownership remain outside this correction.

Sixteen unit tests cover actual meter collisions, UTF-8 sizes and metric tags,
exactly one unchanged storage call, genuine serialization/storage failures,
direct/wrapped/suppressed fatal errors and failing log/fallback appenders. Two
integration tests use real task/blob services and an in-memory queue to verify
pending tasks, unchanged input bytes, one queued envelope and successful submission
diagnostics. They neither replace shared Spring beans nor start queue workers.
Independent read-only review found no actionable issue in the production diff.

Focused verification passed 68 tests with two MySQL-only Quartz skips across seven
suites at 12:26:10 UTC (`/tmp/queue-input-diagnostics-reviewed-focused.log`). The
broader queue/admin/assetlocalize/task/blob/Quartz selection passed 1,238 tests with
16 skips across 88 suites at 12:27:15 UTC
(`/tmp/queue-input-diagnostics-broad.log`). XML audits found no failures, errors,
flaky successes or rerun entries. Root formatting passed. Explicit `ThreadDeath`
checks add visible deprecation/removal warnings; existing weaving, test-agent and
HSQL/Hibernate fixture warnings remain. No fresh MySQL/PostgreSQL, performance/soak,
standalone-JAR, hosted CI or deployment verification is claimed for this pass.

## Stop All Polls Before Executor Drain (2026-09-10)

The coordinator previously stopped and drained each runtime in order. A later
queue could still claim new work while an earlier executor waited for its existing
handler. Three deterministic regressions reproduced the late QUEUED-to-RUNNING
transition during normal stop, failed-start rollback and a scheduled-future
cancellation failure. All three failed on both configured runs at 12:42:39 UTC
(`/tmp/queue-quiesce-reproduction.log`).

Shutdown now first requests polling stop on every created runtime, then runs the
existing active-poll/executor/heartbeat cleanup. Cancellation failure does not
clear the stopped flag; a scheduler callback already handed off but not admitted
does no work. Existing handlers retain heartbeat schedulers until their respective
executor drains. Start/stop remain serialized and restart creates fresh runtimes.
No SQL, transaction ownership, retry budget, flags or business-admission behavior
changes. Stop requests are sequential, not an atomic all-queue barrier; polls
already admitted can still finish and per-runtime waits retain their bounds.

The new tests use manually dispatched scheduled polls, real worker/heartbeat
executors and an in-memory store. At first-executor shutdown entry, they verify
that a late job in the second queue stays QUEUED at attempt zero despite spare
capacity, both scheduled polls have been cancelled and both existing leases still
renew. Release permits the original work to finish and owned executors/schedulers
to terminate; restart processes the late job once. Independent review identified
a test-only race against the executor timeout; an explicit observation/release
latch replaced that timing dependency. Final review found no actionable issue.

The initial focused lifecycle/runtime selection passed 342 tests across 13 suites
at 12:44:01 UTC (`/tmp/queue-quiesce-focused.log`). After the latch correction,
the final broad queue/admin/assetlocalize/task/Quartz selection passed 1,241 tests
with 16 skips across 89 suites at 12:46:49 UTC
(`/tmp/queue-quiesce-reviewed-broad.log`). XML audits found no failures, errors,
flaky successes or rerun entries. Existing active-poll timeout/interruption and
buffered-job heartbeat drain controls remain green. No fresh MySQL/PostgreSQL,
network/multi-host soak, deployment or hosted CI verification is claimed.

The 29-source ordinary-JAR engine rebuilt at 12:47:34 UTC
(`/tmp/queue-quiesce-engine.log`). Its clean lean consumer passed 11 tests with six
real-database skips across four suites at 12:48:17 UTC
(`/tmp/queue-quiesce-consumer.log`), including all-class provenance, with no XML
failures/errors/flaky/rerun entries. The optional JPA consumer lane was not rerun.
Root formatting passed. Existing deprecation/weaving, test-agent and HSQL/Hibernate
fixture warnings remain visible; no warning suppression was added.

## Real-Database CI Selection And Reruns (2026-09-10)

The dedicated database workflow omitted `JdbcAsyncJobStoreTimezoneIntegrationTest`
and `AsyncJobQueueJpaTransactionIntegrationTest`. Ordinary Maven runs do not fill
that gap: timezone tests skip without the container flag and JPA selects HSQL only.
The workflow now includes both alongside store, pool, wakeup and process-crash
contracts with Testcontainers enabled. No tests, migrations or application behavior
were changed; performance smoke remains separately opt-in.

The webapp also hard-coded one Surefire rerun. The pre-change effective POM still
resolved `rerunFailingTestsCount=1` despite a command-line override of zero
(`/tmp/queue-ci-before-effective-pom.xml`). The value now references the standard
property with the same default of one. The database workflow explicitly sets zero:
effective-POM checks verify strict zero and unchanged default one
(`/tmp/queue-ci-strict-effective-pom.xml`, `/tmp/queue-ci-default-effective-pom.xml`).
YAML parsing and shell-argument validation confirm all six selected classes exist,
the container flag is present and reruns are disabled.

Focused HSQL JPA/coordinator/runtime verification with the strict override passed
376 tests across 13 suites at 13:00:49 UTC (`/tmp/queue-ci-strict-focused.log`). XML
reports contain no failures, errors, skips, flaky successes or rerun entries.
Root formatting passed. Existing deprecation/weaving, test-agent and HSQL/Hibernate
warnings remain. Docker was rechecked and could not connect to its local socket;
no real MySQL/PostgreSQL, performance or hosted CI result is claimed. The expanded
workflow must still pass on the eventual pushed revision; the existing 30-minute
budget is unchanged, not extended to hide startup failures.

## Stored Request Identity Recovery Prerequisite (2026-09-10)

Added a pure, unwired `restore` path to the direct-request decoder. It verifies a
trusted `request_sha256` against one copied byte snapshot, rejects mismatched
repository/actor/asset and unsupported version/operation, then requires exact
canonical re-encoding. Recovery must not silently fill omitted stored defaults,
renormalize content or rewrite encoding. Existing client decoding remains distinct
and rejects server metadata; the original model and body-reader tests still pass.

The 14 new reader tests cover the independently hashed v1 golden, Unicode/null/
ordered-list preservation, scope/digest checks, rehashed invalid metadata, all
missing fields, noncanonical representations, malformed data, byte budgets and
buffer/DTO independence. They explicitly demonstrate that the external request
key is not authenticated by these bytes. The focused model/body/stored-reader
selection passed 61 tests, no skips, at 13:29:57 UTC with Surefire reruns disabled
(`/tmp/queue-admission-restore-focused.log`); XML has no errors, failures, flaky
successes or rerun entries. Root formatting passed; existing weaving/deprecation
warnings remain.

This adds no database/blob operations, schema, endpoint, Spring bean or producer/
consumer wiring. It is not an execution-envelope checksum reader, durable
reservation, atomic acceptance or unknown-commit recovery. Those gates remain
open; applied-history confirmation is still needed for the schema path. The
generic queue and independent JAR are unchanged, so no new library or real-DB
verification is claimed for this pass.

## Remaining Rollout Gates

Keep the feature disabled until the migration plan's acceptance gates are met.
In particular: reconcile crashes between PollableTask creation, input storage and
enqueue; fence pull-run lineage before lifting the tracked-work exclusion;
drain older canonical-output and lineage writers; align the one-day minimum blob
lifetime with repair deadlines; and define domain-aware replay rather than
reusing an already-failed PollableTask. Queue fencing does not make arbitrary
business mutations exactly-once.

The bounded renewal/row-lock/claim and shared-pool outage regressions do not replace
multi-host load and sustained pooled-connection/network-outage soak. The non-UTC
harness exposed actual early claims and live-lease reclaims, not merely timestamp
display differences. New queue
writes use an explicit UTC contract. Any already-used queue needs a separately
reviewed cutover: stop old producers/consumers, establish row timestamp provenance
and reconcile queued, leased and retained terminal rows. MySQL DATETIME carries
no original offset; no automatic conversion or mixed-worker compatibility is
claimed. Verify actual production driver/session settings with the new code.
Full application PostgreSQL compatibility is separate from the queue's PostgreSQL
contract tests.

## Next Run

The late stale writer is reproduced and tracked queue work is excluded, not fenced.
Resolve the explicit owner choices in the proposed lineage protocol before
implementing its authority/publication model. Untracked cache, branch-state and
fallback effects are now identified; keep shadow execution off production stores
and evaluate a captured, read-only dependency boundary before adding computational
shadowing. Direct generation timer and cache lookup failures are now reproduced
and corrected separately from runtime/adapter metrics; do not generalize these
guards to global legacy instrumentation. Prioritize admission/publication and
lifetime gates below over an unbounded instrumentation rewrite.

Build durable reservations and explicit task-plus-queue acceptance/recovery using
the real JPA/JDBC fault fixture plus the pure v1 decoder and identity model. The
public queue enqueue deliberately remains an independent transaction; it must not
be used inside atomic admission acceptance. First verify deployment/migration
history before adding admission
DDL: V109 may only be extended if unapplied; otherwise require a forward migration.
Follow the admission design, keeping proposed HTTP/retention choices distinct
from approved rollout decisions.
Keep the initial database handshake flake visible and diagnose the connection
failure before increasing timeouts or retries; IPv4 alone did not resolve it.
Include API retry and partial parent fan-out; neither should recreate accepted
children. Keep queue and business transaction boundaries separate,
and update this ledger and the live tracker with fresh evidence. Pause the review
heartbeat when this checklist is complete;
if an external prerequisite blocks one workstream, identify it without repeating
unchanged status every five minutes, then continue independent core tests and the
bounded library API work in `async-job-queue-library.md`. Missing applied-migration
history blocks the schema decision, not the entire review.
