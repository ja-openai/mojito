# Standalone JDBC Queue Library Boundary

Status: source-reviewed extraction proposal with external-package Spring
contracts and an isolated ordinary-JAR build probe, not an extracted or published
production library.
The user has asked whether this can become a self-contained Maven library; this
note does not authorize a new repository, publication or a large framework rewrite.

## Assessment

The engine is a credible library candidate, not merely a Mojito job wrapper.
The 29 production files under `webapp/.../mojito/queue` import no Mojito business
packages. `AsyncJobStore`, records and `AsyncJobHandler` use queue concepts and
opaque string payloads. Existing plain-JUnit/HSQL and opt-in MySQL/PostgreSQL
tests exercise much of the engine without starting Mojito Application.
The isolated probe below additionally verifies standalone compilation and
ordinary-JAR consumption, not production certification. Some tests in the same
package, particularly JPA enlistment and
asset localization tests, still depend on Mojito services/entities.

Start with one Spring-aware Maven module inside this repository. Keep native JDBC
SQL and the tested transaction/lease behavior. Do not rewrite the engine around
Hibernate, remove Spring just to claim zero dependencies, or create several
artifacts before a real consumer requires them. Mojito should become its first
consumer before considering a separate OSS repository.

## Boundaries To Preserve

| Generic library | Mojito integration |
| --- | --- |
| Queue records, store, runtime, leases, retries and retention | PollableTask lifecycle and repair |
| MySQL/PostgreSQL SQL and optional wakeup hints | Quartz routing, cron schedules and workload cutover |
| Opaque payloads and handler contracts | JSON codecs, blob inputs/outputs and their lifetimes |
| Inspection primitives and bounded metrics | REST authorization, replay policy and audit identity |
| Explicit transaction ownership | Atomic domain admission, lineage authority and fan-out manifests |

Master's `003992323e` interactive-review dispatcher is a separate Mojito lifecycle,
not a second generic queue backend. Its task JSON/MBlob capacity state and policy
of failing interrupted requests rather than resending provider work stay outside
this module. See the [scoped source inventory](async-job-queue-quartz-migration.md#interactive-ai-review-separate-execution-policy).
Extracting the retrying engine must not silently change that workload's execution
policy or import its legacy Quartz drain adapters into the library.

The later 2026-09-11 discussion proposes optional asynchronous handlers in this
same engine, not a second queue implementation. The current handler contract is
still synchronous. See the [asynchronous handler plan](async-job-queue-asynchronous-handlers.md)
for ownership, transport settlement, cancellation, deadline and no-queue-replay
requirements. This is a separate follow-up, not implemented support or a new
prerequisite for a reviewed inactive foundation landing.
Choose it for a concrete durable workload, not merely because interactive HTTP
is asynchronous. AI Review can retain its direct dispatcher while restart failure
is acceptable; a future queue adapter requires a separate admission-policy decision.

Durability of queue rows does not give exactly-once business effects. Handlers can
run again after a crash or lease loss. Terminal callbacks are best-effort local
notifications after a successful transition: they can be missed after a process
crash or an ambiguous commit response and are not a durable publication mechanism.
Before handler entry, the engine checks for definitive lease loss already reported
by a heartbeat and skips that invocation without a transition or callback. This
uses local bookkeeping, adds no SQL, does not interrupt running handlers and does
not treat a heartbeat exception as proof of loss. It cannot exclude loss after
the check or replace application idempotency/business fencing.

The 2026-09-11 source-in-place JAR rebuild includes this guard. Its JPA consumer
passed 17 tests and its clean lean consumer passed 11, each with six real-database
skips and no reruns (`/tmp/queue-handler-known-loss-consumer-jpa.log` and
`/tmp/queue-handler-known-loss-consumer-lean.log`). The natural-expiry/peer-reclaim
regression runs in the application runtime suite using the in-memory store and
a real executor, not in the external consumer or a production database.

User-facing error disclosure is also an adapter responsibility. The engine retains
bounded diagnostics on queue rows and supplies the original failure to the terminal
callback. Mojito's assetlocalize adapter stores a generic task error for unexpected
failures and direct permanent-failure signals, rather than copying diagnostics into
translator-readable stacks. Its ordinary checked business-error contract remains
unchanged. Do not move PollableTask classification or task-access policy into the
library, or claim that raw queue diagnostics are safe for end-user APIs.

Queue names and lease-owner strings are generic exact identities, not locale-aware
text. The JDBC dialect now guards MySQL against inherited case/accent/padding
collations, retaining the ASCII queue-name index prefilter but comparing owner
strings only after explicit UTF-8 conversion and unpadded binary casting. Owner
queries already use primary-key IDs. HSQL rejects implicit trailing-space padding;
PostgreSQL predicates are unchanged. Keep these semantics in the library, not in
Mojito normalization or an application-specific schema migration. Database/driver
encodings must still preserve accepted text. Fresh MySQL collation/encoding-matrix
and query-plan verification remains required; embedded tests cannot certify it.

MySQL claim selection now limits separately ordered queued and expired-running
locking reads before merging their `(available_at, id)` winners. The existing
`I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID` name and column order are a physical
schema dependency: custom hosts must not omit or rename this forced index. No new
DDL is needed with the supplied migration. A batch of B may retain up to 2B
matching candidates until commit even though only B jobs are claimed; a small
backlog may still produce an empty peer claim. Nonexpired running rows or
case-equivalent queue names can still increase scanning. Native MySQL 8.0.43
lock-footprint tests do not certify every optimizer/version or strict cross-worker
FIFO. See the [claim-lock review](async-job-queue-review.md#bounded-mysql-claim-locks-2026-09-12-utc)
and keep the required MySQL 8.4 CI and outage/load gates open.

The subsequent 2026-09-11 JAR rebuild includes the exact-identity predicates.
The JPA consumer passed 17 tests across five suites; a clean switch to the lean
consumer passed 11 across four suites. Each lane skipped six real-database cases,
with no failures or reruns (`/tmp/queue-exact-identity-consumer-jpa.log` and
`/tmp/queue-exact-identity-consumer-lean.log`). All 48 packaged class files retained
single-JAR provenance. These tests verify current packaging and public host
composition, not the unexecuted MySQL collation matrix or a library release.

The public JDBC enqueue uses an independent transaction; it is not an atomic
outbox API for a caller's business transaction. A guarded package-local
`enqueueNowInCurrentTransaction` primitive shares the INSERT for future admission
integration, but adds no public API or request deduplication. Its ID is provisional
until caller commit; callers must abort on failure and handle uncertain commits.
Document these distinctions in the eventual public API and external example, not
only the Mojito rollout guide.

Operator replay's counters and logs are best-effort diagnostics. Nonfatal failures
in them do not change an acknowledged replay result, mask the original store or
validation error, or skip the independent remote hint after a failed local wakeup.
Fatal diagnostic causes/suppressed errors propagate as the original JVM Error.
This guarantee is replay-specific, not a rewrite of all inspection instrumentation.
The method still reads details after replay: that lookup can fail after a committed
mutation, and a store commit acknowledgement can be lost. An exception is not a
safe instruction to repeat replay. No durable receipt, audit record, replay HTTP
endpoint or domain-safe task reopening is introduced by the diagnostic guard.

Attempt budgets count committed claims, not only handler invocations. If a claim
commits but its acknowledgement is lost, the runtime dispatches nothing from that
call; the lease still protects the row until expiry. Reclaim consumes another
attempt and can exhaust the budget before any business work runs. A rolled-back
claim consumes no attempt and preserves the previous row state. Exhaustion retains
a terminal FAILED row and reports the reason; increasing the budget is not a
replacement for fault diagnosis or idempotent business effects. This is bounded
execution, not an unconditional promise that every accepted job reaches its handler.
Reclaim beyond `maxAttempts` records failure without invoking the handler; that
setting is not a hard cap on the stored attempt count.

Handlers may directly throw the public checked `AsyncJobPermanentFailureException`
for a known unrecoverable condition. This requests the same lease-fenced FAILED
transition without spending the remaining attempt budget. Only the direct handler
throw opts in: wrapped/suppressed markers and store exceptions do not change their
existing failure policies. The safe diagnostic message is logged and persisted;
do not include raw payloads or secrets. Rejected/unknown FAILED transitions do not
invoke callbacks or explicitly requeue, but an uncommitted transition can leave a
lease eligible for recovery. This is not an exactly-once or business rollback API.

JVM-fatal classification is type-based: `VirtualMachineError` and `ThreadDeath`
include subclasses. Handler and terminal-callback boundaries search causes and
suppressed errors and rethrow the original fatal object; ordinary assertion errors
retain the existing retry/callback policy. This neither makes every engine catch
graph-aware nor stops the runtime or JVM. Local direct-only checks remain direct-only.

The public store contract records mutation uncertainty explicitly. Integration tests inject both
claim outcomes through JDBC and gate a live replacement handler while rejecting
all old-token writes with the same worker ID, on HSQL/MySQL/PostgreSQL. Explicit
fixture lease expiry is not a process-crash or elapsed-time outage test.

The separate `AsyncJobQueueProcessCrashIntegrationTest` launches and forcibly
terminates a worker JVM against MySQL/PostgreSQL. It observes a real heartbeat
extension before killing a gated handler, then waits for natural database-clock
expiry before reclaiming. The replacement rejects old-token mutations while
RUNNING, using the same worker ID, and completes. A committed probe from each
handler attempt deliberately demonstrates duplicate business effects despite
correct queue fencing. A second crash after DONE commits but before the store
returns to the runtime leaves a terminal row with no callback; fresh polls do not
replay it. The `AsyncJobHandler` API warns about both boundaries explicitly.
This is local worker-death coverage, not an atomic admission, database-crash,
network-partition, multi-host soak or independent-library-build proof.

## Payload Integrity Contract

New and replacement `jobData` strings must be well-formed Unicode, exclude raw
U+0000, and contain at most 1,000,000 UTF-16 code units (not bytes or code points).
The queue rejects invalid strings rather than normalizing, escaping or replacing
opaque payload content. Empty strings, whitespace, combining sequences, valid
supplementary characters and literal ASCII escape sequences remain unchanged.
Handlers/codecs own serialization; the queue does not interpret JSON escapes.
Use Unicode-capable database and connection encodings, including MySQL utf8mb4;
Java validation does not repair a misconfigured database/driver character set.

This closes a reproduced integrity defect: both tested JDBC drivers accepted
unpaired high/low surrogate payloads and stored `?` instead. MySQL accepted raw NUL
while PostgreSQL rejected it during INSERT. Shared write validation now covers
submission, store admission/updates/replay, inspection replay replacements and
handler result payloads. Public submission/replay services reject these inputs
before store/wakeup calls. Direct JDBC store calls still acquire transactions and
can read/lock before validation; the guarantee is no durable mutation, not zero
JDBC activity or an atomic business admission protocol.

`AsyncJobRecord` retains the original read compatibility checks. Existing MySQL
NUL rows remain inspectable, claimable/reclaimable and able to finish, fail or
replay when null replacements preserve their stored payload. Explicitly echoing
`record.jobData()` as a replacement is a new write and is rejected if invalid;
use `done()`/null-preserving transitions when no change is intended. A handler
that produces invalid replacement text enters normal bounded failure/retry
handling. No historical rows are rewritten, and already-lost surrogate values
cannot be reconstructed from their replacement `?` bytes.

## Current Packaging Obstacles

- JDBC store constructors and dialect are package-private; some public service
  constructors require a package-private wakeup type. This limits direct Java
  construction, but is not a blocker to Spring composition: the external-package
  contract below uses existing public configuration/services successfully. Do not
  expose internals or add a facade solely to satisfy that already-working path.
  Decide whether a convenience entry point is needed when extracting the module.
- Spring scheduling, JDBC/transactions, Boot annotations, Micrometer and SLF4J
  are real dependencies. The store also validates Spring ORM/Hibernate JPA
  transaction-manager compatibility. Preserve that guarantee or isolate its
  integration bridge explicitly; deleting the check is not dependency cleanup.
- `l10n.org.async-job-queue`, the `mojito_async_job_queue` notification channel,
  `disablescheduling` profile and host-provided scheduler are Mojito conventions.
  Preserve existing application configuration through adapter wiring while
  defining neutral library configuration and resource ownership.
- A child of `mojito-parent` inherits application dependencies and build behavior.
  The library needs a lean, independently resolvable POM, not a dependency on
  webapp or accidental transitive logging bindings, ICU, or weaving machinery.
  Retain the current Java 21 baseline initially rather than promise untested JDKs.
- V109 belongs to Mojito's migration history. Do not export Mojito's migration
  directory into arbitrary applications. New consumers need explicitly selected,
  namespaced per-dialect schema resources and documented migration ownership.
  Adding a dependency must not automatically mutate a database. Existing installs
  require an adoption/upgrade path without replaying or renumbering applied DDL.

## Verified Consumer Composition

`webapp/src/test/java/example/queue/AsyncJobQueueExternalBootstrapTest.java` is in
an unrelated Java package and imports only public queue types. Its consumer-owned
configuration explicitly composes the existing execution/inspection surface:

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AsyncJobQueueProperties.class)
@Import({
  AsyncJobQueueConfiguration.class,
  AsyncJobQueueCoordinator.class,
  AsyncJobQueueSubmissionService.class,
  AsyncJobQueueInspectionService.class
})
class QueueConsumerConfiguration {}
```

The host supplies a `NamedParameterJdbcTemplate`, a supported transaction manager
using the same DataSource, `TaskScheduler`, `MeterRegistry` and `AsyncJobHandler`
beans. Enable the existing `l10n.org.async-job-queue.enabled` property explicitly
and select `store=jdbc` and the database dialect. Without the enable flag, the
execution/store/inspection beans are absent and database/execution infrastructure
is unnecessary. No Mojito component scan or Application bootstrap is used.

The datasource must provide independent physical connections for REQUIRES_NEW;
suspending a Spring transaction does not isolate a shared physical connection.
The store rejects a directly visible `SingleConnectionDataSource` at startup,
including supported outer transaction-aware proxy compositions, without borrowing
a connection. A real HSQL regression reproduced queue enqueue committing the
caller's write despite its later rollback. Lazy/routing/custom wrappers retain
their resource identities and behavior; hidden connection sharing is not detected
or certified. This is a datasource ownership requirement, not a pool-sizing check
or a replacement for the host's connection-acquisition timeout policy.

The contract exercises actual MySQL/PostgreSQL admission, handler execution,
terminal payload persistence, inspection, stop and restart. A separate marker
write rolls back in the caller transaction while the enqueued job remains and
finishes. This proves independent enqueue, not atomic business-plus-job admission.
Handlers observe no active transaction. Missing/mismatched managers and invalid
runtime settings fail before database access. A rejected second queue startup
cancels the first scheduled poll and removes runtime gauges.

Stopping the coordinator leaves the provided scheduler, DataSource and registry
usable. The fixture closes its context before explicitly shutting down host
infrastructure and verifies bounded scheduler/observed-worker termination.
Normal stop and failed-start rollback request polling stop on all created runtimes
before any executor drain waits. Queues later in the shutdown order cannot admit
new polls while earlier handlers drain. Already-admitted polls may still finish;
this is not an atomic barrier across all queue stores. Owned heartbeat schedulers
remain active until their respective executor drains, and subsequent start creates
fresh runtimes. Enqueue remains independent of the local consumer lifecycle.
Private heartbeat-thread termination is covered by the existing coordinator
lifecycle suite, not directly observed by this external fixture.
This is not a promise that closing a child Spring context leaves every shared
SmartLifecycle bean in a parent application unaffected.

This bootstrap intentionally covers execution and inspection only. Optional
PostgreSQL notification and scheduled maintenance composition have their own
external contracts below. None of these fixtures installs schemas automatically.
The host applies the existing SQL explicitly through the fixture; it is not a
real Flyway install/upgrade test.
The original run used webapp's Maven/AspectJ/dependency environment. The same
source is now also exercised by the isolated ordinary-JAR probe below; keep those
two verification environments distinct.

Verification (2026-09-09): all 90 selected external-bootstrap, configuration,
transaction-binding, coordinator/lifecycle and drain tests pass with real database
checks enabled, without failures/errors/skips or reruns. See
`/tmp/queue-external-bootstrap-focused.log`. The initial run passed five of seven
new cases; two assertions expected different rejection-message wording and were
corrected without changing production behavior.

## Optional Wakeup Composition

An external Spring consumer can additionally import the public
`AsyncJobQueueWakeupConfiguration` and set
`l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify`. The notifier and
listener implementation types remain internal. Provide a `DataSource` bean for
notification connections to the same PostgreSQL database as the queue. It must
provide independently leased connections. Wakeup constructors bypass outermost
`TransactionAwareDataSourceProxy` chains so enabling auto-commit cannot commit a
resumed caller transaction. They reject cyclic/missing targets and directly
encountered `SingleConnectionDataSource` instances without borrowing a connection.
The first non-transaction-aware wrapper is preserved, including lazy/routing
behavior. Remaining wrappers must not enlist or return caller-owned/shared
connections; arbitrary custom sources or transaction-aware wrappers hidden inside
them are not certified. The shared DataSource bean, JDBC template and transaction
manager are not rewritten and must still use matching queue resource identities.

A real HSQL submission regression reproduces unintended caller commits with
single/nested transaction-aware proxies and an outer transaction-aware/lazy
composition. The queue INSERT correctly commits independently, but notification
auto-commit previously also committed the resumed business write before HSQL
rejected `pg_notify`. After the fix that business write rolls back while the queue
row remains accepted. This is real transaction evidence on a deliberately failed
notification, not successful PostgreSQL execution. The external PostgreSQL
consumer contract additionally checks successful hints with caller commit and
rollback and an independent pre-commit observer. It now passes on native PostgreSQL
16.15 through the ordinary JAR; see the [scoped evidence](async-job-queue-review.md#native-postgresql-ordinary-jar-consumer-2026-09-12-utc).
This does not certify arbitrary datasource wrappers or network/failover behavior.
Listener cleanup still orders UNLISTEN before
auto-commit restoration and connection return. This fixes connection ownership,
not durable admission, exactly-once notification or the open readiness gates.

The [native PostgreSQL 16.15 listener fixtures](async-job-queue-review.md#native-postgresql-listener-sessions-2026-09-12-utc)
also verify clean reuse of a one-slot Hikari pool across auto-commit modes and
restart, plus successful resubscription after actual listener-backend termination
despite an injected failure-counter exception. Their coordinator is mocked; they
verify session cleanup and delivery, not workload execution, arbitrary pool
compatibility or network/failover capacity.

The 2026-09-11 post-fix ordinary-JAR rebuild passed its separate consumers: 17
JPA-lane tests and 11 clean lean-lane tests, each with seven real-database skips
and no reruns. The additional skip is the new PostgreSQL transaction-aware
producer contract, not newly executed database evidence. Packaged-class
single-JAR provenance checks still pass. See the wakeup ownership section of the
[review ledger](async-job-queue-review.md#wakeup-connection-ownership-2026-09-11).

`example.queue.AsyncJobQueueExternalWakeupTest` uses independent application
contexts and schedulers. A producer with no handler can publish a hint to a remote
consumer context without creating local queue workers or a listener. It waits
until the consumer has completed its initial empty poll and has an established
LISTEN subscription, then requires completion with a notification-trigger metric.
The whole startup/wakeup proof must finish within ten seconds, ahead of the next
one-minute periodic poll. The producer cannot directly invoke the consumer's
coordinator.

A second case registers a handler with `consumer-enabled=false` and injects a
failing notification DataSource while retaining the real queue JDBC connection.
Startup never uses the notification connection, enqueue returns its durable ID,
and the row remains queued at attempt zero despite the failed hint. After that
producer context closes, a new polling-only consumer completes the stored job.
This proves producer-only admission survives lost hints and producer shutdown,
not atomic business admission or a sustained network-outage/capacity result.
The fixtures also check global default-off composition without infrastructure and
bounded listener/shared-scheduler/observed-handler teardown.

Producer-only composition still supplies the coordinator's scheduler/registry
dependencies; this does not introduce a new minimal producer API. Leave polling
enabled as recovery for missed notifications. The existing config namespace,
channel defaults and database support checks are unchanged. Public configuration
access is additive; it does not enable notifications in an existing application.

Verification (2026-09-09): all 164 selected bootstrap/wakeup, configuration,
transaction-binding, coordinator/lifecycle/drain and submission tests pass with
real database checks enabled, without failures/errors/skips or reruns. See
`/tmp/queue-external-wakeup-final.log`. Independent review identified and corrected
a GC-sensitive teardown assertion: the fixture now retains public lifecycle beans
through post-close weak-gauge reads. This is not an independent Maven build.

## Optional Maintenance Composition

Alongside the core store/properties/metrics beans, an external host may choose
explicit scheduled maintenance without importing Mojito Application:

```java
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import({
  AsyncJobQueueRetentionCleaner.class,
  AsyncJobQueueStatusMetricsReporter.class
})
class QueueMaintenanceConfiguration {}
```

The host supplies a `TaskScheduler`. Importing the components alone does not turn
on Spring scheduling. Both require the global queue enable flag, and the existing
`disablescheduling` profile suppresses both maintenance beans. Destructive
retention separately requires `l10n.org.async-job-queue.retention.enabled=true`;
status reporting runs when its component is imported and scheduling is enabled.
No extra facade, public API change or scheduler is added to the production graph.

Maintenance intentionally includes configured queues even if no handler is
registered or `consumer-enabled=false`. That flag stops local consumers, not
status reads or terminal cleanup. Pure producers should omit maintenance imports
if they must not do background database work. Deployment owners should explicitly
choose maintenance nodes rather than assuming the consumer toggle governs them.
These imports do not perform blob cleanup or PollableTask reconciliation.

Scheduled retention now calls `AsyncJobStore.deleteTerminalJobsOlderThan` with a
positive age of at most 365 days. JDBC calculates the cutoff from database time
inside its existing short transaction; the in-memory store uses the same host
clock as its transitions. The original absolute-cutoff method remains unchanged
for explicit operator use. Custom stores remain loadable but must implement the
new relative-age method before scheduled cleanup can work: its default throws
`UnsupportedOperationException`, deliberately retaining rows instead of choosing
an unsafe wall-clock fallback. Clock failure or an out-of-range cutoff prevents
deletion. The strict age/status/queue checks and bounded batches remain unchanged.
This removes application/database skew from cleanup, not database clock jumps,
unfinished-publication retention or blob lifetime hazards. Older cleaner binaries
must be drained before relying on it; no schema or retention enablement changes.

Retention diagnostics are best effort, separate from the store operation. Nonfatal log
or counter failures neither report an acknowledged delete as a store failure nor
abort subsequent queue/status passes. Store failures are reported without immediate
retry; an exception can follow commit, so a failure metric is not proof of rollback.
JVM-fatal errors in causes/suppressed diagnostics propagate as the exact Error rather
than continuing destructive maintenance. Counters may undercount when unavailable.
This is a retention-specific boundary, not a global inspection/status-metric rewrite.

`example.queue.AsyncJobQueueExternalMaintenanceTest` checks global-off startup
without infrastructure, the absence of automatic scheduling from imports alone,
and the scheduling-disable profile without JDBC calls. Its actual MySQL/PostgreSQL
cases first observe status/ready/expired-lease gauges with retention off and all
rows preserved. An explicitly enabled pass deletes one old DONE and one old FAILED
row at batch size one, leaving other expired terminal rows for subsequent passes.
Recent terminal rows, a ready queued row, an expired running row and another
queue's rows remain. A second enabled pass contains only those protected rows,
after removing remaining eligible fixtures, so batch limiting cannot mask a
broken age/queue/status filter. A single-thread scheduler barrier drains the
immediately scheduled callbacks before asserting zero deletions and no failure
counters. The test bounds each pass well before its one-hour repeat interval,
verifies task cancellation on context close and awaits host scheduler termination.
SQL fixtures apply V109 explicitly; production DDL is unchanged.

Verification (2026-09-09): all 187 selected external-composition, maintenance,
configuration, transaction, lifecycle, wakeup and submission tests pass in the
final run without failures/errors/skips or Surefire reruns. See
`/tmp/queue-external-maintenance-final.log`. An earlier expanded run needed one
automatic rerun after a PostgreSQL connection-opening SSL EOF before queue DDL;
that known environment risk remains open rather than being hidden by the later
green result. Independent review prompted the protected-only pass and found no
remaining material issue in this bounded change.

## Finite Extraction Gates

1. Preserve external-package execution/inspection, optional PostgreSQL wakeup and
   producer-only composition, and explicit scheduled maintenance. These contracts
   remain in webapp's source tree and are also reused by the separate artifact
   probe below. Avoid a new facade unless it simplifies a concrete consumer.
2. Move the engine and generic tests into one library module with an explicit POM.
   Preserve the probe's build without webapp, test-common, frontend or AspectJ,
   and its separate Maven consumer of an ordinary JAR, not same-reactor classes.
   The probe demonstrates source-in-place feasibility; it does not perform the
   production extraction, settle artifact/API naming or rewire the application.
   Keep Mojito JPA/domain integration tests and default-off routing green.
3. Test real Flyway fresh installation and upgrade/adoption for both databases,
   with no duplicate migration scanning or implicit schema writes. Current
   ScriptUtils-based queue DDL tests do not establish this lifecycle. Preserve
   historical V109 checks until actual applied-history decisions are resolved.
4. Before an OSS release, define API/schema compatibility, payload encoding/limits,
   supported JDK/driver/DB versions, licensing/provenance, shutdown/lease-loss and
   enqueue-ambiguity contracts; establish repeatable DB CI and sustained outage/load evidence.
   Local throughput smoke tests are not production capacity claims.

Applied-migration history blocks only the relevant DDL/adoption decision. It does
not block API construction tests or independent core correctness work. Conversely,
extracting a module does not close Mojito's admission, lineage, blob-lifetime or
canary gates in `async-job-queue-review.md` and the Quartz migration design.

## Isolated Ordinary-JAR Probe

`dev-docs/queue-library-probe/README.md` describes two independent Maven builds,
both using the current Java 21 and Spring Boot 3.5.14 baseline without the Mojito
parent. The engine POM points its source root at the existing queue package only,
compiles all 29 originals with javac into a clean target, and locally installs a
disposable `example.queue.probe` artifact. It does not inherit application
dependencies, run AspectJ/frontend plugins, include database scripts, or deploy
anything. No production sources are moved or copied into a second implementation.

A second Maven invocation resolves the ordinary JAR/POM and compiles the three
existing external-package contract classes directly from their original sources.
It exercises explicit execution/inspection, producer-only operation, optional
PostgreSQL notifications and scheduled maintenance. Additional boundary tests
require exactly one resource and the same JAR code origin for every packaged queue
class, including private/nested classes without static initialization, compare the
JAR bytes to this engine build's artifact, reject application/Quartz/AspectJ classes (and Hibernate
in the default lean lane),
and reject database/Boot-repackaging entries in the engine artifact. This guards
against accidentally using webapp output or another locally installed snapshot.
An isolated duplicate coordinator resource is rejected even when the store class
still has a single engine origin; checking only `AsyncJobStore` would miss it.
The consumer alone selects V109 fixture resources and applies them explicitly;
this is not a full Flyway installation or upgrade/adoption proof.

The engine dependency tree contains Spring Boot autoconfigure, Spring context/
JDBC/ORM/transactions, Jakarta Persistence APIs, Micrometer and SLF4J. Spring AOP
is a Spring-context dependency, not AspectJ weaving. Hibernate ORM is absent from
the default JDBC consumer. An optional `jpa` consumer profile adds Hibernate and HSQL
in test scope only; the engine dependency boundary does not change. Its six tests
use a tiny host entity and public bootstrap/enqueue with explicit transaction
templates, not Mojito entities or transaction advice. They check actual inner
READ_COMMITTED versus outer SERIALIZABLE JDBC isolation, resource suspension and
restoration, independent queue commit after outer rollback, writable enqueue from
a read-only host, rollback after a real INSERT while the outer JPA work commits,
worker-owned business transactions with no bound resources before/after, and both
datasource mismatch guards. The host supplies `HibernateJpaDialect` with
`DELAYED_ACQUISITION_AND_HOLD`. These six contracts are parameterized across HSQL
and opt-in MySQL 8.4/PostgreSQL 16, with a disposable container per real-DB case.
The [independent PostgreSQL JPA run](async-job-queue-review.md#independent-jar-jpa-database-lanes-2026-09-12-utc)
now verifies all six plus both ordinary-JAR provenance tests. The same eight
checks pass on [native MySQL 8.0.43](async-job-queue-review.md#native-mysql-independent-jpa-host-2026-09-12-utc)
after private-fixture setup corrections; MySQL 8.4 and full CI execution remain
outstanding. This is not public atomic enlistment or an
arbitrary provider/version compatibility matrix. Public enqueue remains REQUIRES_NEW. Both profile lanes require
`clean test`; the boundary test explicitly requires/rejects Hibernate and the
optional compiled test class according to the chosen mode.
Mockito uses Byte Buddy in the consumer's test scope; this
is not a claim that every test runs without any instrumentation agent.

The existing real-database CI job runs the engine, lean real-DB consumer and
opted-in HSQL/MySQL/PostgreSQL JPA consumer separately, with zero automatic reruns
and a workflow regression preventing an accidentally HSQL-only JPA invocation. The probe
retains Mojito's current package/config conventions and is intentionally not a
releaseable Maven module. It supplies a concrete, tested extraction starting
point without changing production wiring or closing the business rollout gates.

Latest native verification (2026-09-12 UTC): after rebuilding the 29-source engine,
the lean Maven consumer passed 11 tests with seven database skips. A separate native
MySQL 8.0.43 run passed the two existing public execution/maintenance methods and two
ordinary-JAR boundary tests, without webapp classes or a reactor shortcut. Real JDBC
and the original assertions ran; only container orchestration/connection settings
were substituted. See the [consumer evidence and limits](async-job-queue-review.md#native-mysql-ordinary-jar-consumer-2026-09-12-utc).
The latest JAR at `f8025ec5ca` also passes a separate native PostgreSQL 16.15 run:
five existing execution/maintenance/wakeup methods and two JAR-boundary tests,
with no skips or automatic reruns. Successful notifications preserve caller
commit/rollback isolation, and polling recovers an injected failed hint after
producer shutdown. Only container orchestration/connection settings were adapted;
verified TLS, real SQL and ordinary-JAR provenance were checked. See the
[PostgreSQL evidence and limits](async-job-queue-review.md#native-postgresql-ordinary-jar-consumer-2026-09-12-utc).
Neither native run is the full configured CI lane, real-DB JPA matrix, or
extraction/release approval. Earlier dated results below remain historical.

A separate [application JPA/JDBC run on PostgreSQL 16.15](async-job-queue-review.md#native-postgresql-jpajdbc-contracts-2026-09-12-utc)
now passes all 35 existing transaction/fault tests, including atomic caller-owned
task/queue insertion and ambiguous commit outcomes. This uses the application's
limited entity graph and test classpath, not the independent ordinary-JAR host
fixture. It supports the enlistment primitive, not completed durable admission or
an arbitrary provider/datasource compatibility claim.

Verification (2026-09-09): isolated `clean install` compiles all 28 sources and
the separate `spotless:check clean test` consumer passes all **16 tests across
four suites**, including real MySQL/PostgreSQL checks, with zero failures/errors/
skips and no Surefire reruns. See `/tmp/queue-library-engine-final.log`,
`/tmp/queue-library-engine-dependencies.log` and
`/tmp/queue-library-consumer-final.log` (consumer completed 22:32:53 UTC).

Refreshed verification (2026-09-10): the engine now compiles **29 sources**, including
`AsyncJobPermanentFailureException`, and the rebuilt ordinary-JAR consumer passes
**16/16 tests** at 02:58:22 UTC, including direct permanent handler failure on
MySQL/PostgreSQL (`/tmp/queue-permanent-failure-engine.log` and
`/tmp/queue-permanent-failure-consumer.log`). No failures/errors/skips or reruns;
the probe still uses explicit fixture DDL rather than testing schema adoption.

After the worker fatal-boundary fix, the 29-source engine rebuilt at 04:27:44 UTC
and the consumer passed **10 tests with six database cases skipped** at 04:28:18
UTC (`/tmp/queue-worker-fatal-engine.log`, `/tmp/queue-worker-fatal-consumer.log`).
Docker Desktop's socket remains absent. The fresh JAR-origin/byte-equality check
and non-database composition contracts pass; this is not a refreshed 16-case
MySQL/PostgreSQL proof. The prior full database result above remains historical.

The store-clock retention change rebuilt the engine at 05:14:51 UTC and passed
the same **10 consumer tests with six database skips** at 05:15:35 UTC
(`/tmp/queue-retention-clock-engine.log`, `/tmp/queue-retention-clock-consumer.log`).
The JAR-origin/byte-equality gate passes with the new relative-age API; actual
MySQL/PostgreSQL maintenance must be rerun when Docker is available.

The replay diagnostic guard rebuilt the engine at 05:34:46 UTC and passed the
same **10 consumer tests with six database skips** at 05:35:59 UTC
(`/tmp/queue-replay-diagnostics-engine.log`, `/tmp/queue-replay-diagnostics-consumer.log`).
The updated JAR passes the origin/byte-equality gate; the database result above
remains historical, not fresh verification of this change.

The optional host-owned JPA lane rebuilt the unchanged 29-source engine at
06:43:11 UTC. Its final `-Pjpa spotless:check clean test` passed **16 tests with six
real-DB skips** across five suites at 06:52:11 UTC
(`/tmp/queue-library-jpa-reviewed.log`), including all six new Hibernate/HSQL
contracts. A clean default-lane run after the JPA lane passed **10 tests with six
real-DB skips** at 06:50:12 UTC (`/tmp/queue-library-jpa-lean.log`), retaining the
Hibernate/optional-class exclusion. Neither XML audit found failures, errors or
flaky/rerun entries. Both lanes resolve the same just-built plain JAR. No hosted
CI or refreshed real MySQL/PostgreSQL JPA result is claimed.

The retention diagnostic guard rebuilt the 29-source engine at 07:56:48 UTC and
passed **10 lean consumer tests with six database skips** at 07:57:42 UTC
(`/tmp/queue-retention-diagnostics-engine.log`,
`/tmp/queue-retention-diagnostics-consumer.log`). The JAR-origin/byte-equality and
dependency-boundary checks pass without failures, errors or rerun/flaky entries.
This refresh covers the ordinary JAR's non-database composition contracts; the
optional JPA lane and real MySQL/PostgreSQL maintenance were not rerun this pass.

An earlier run passed 15 cases and failed one PostgreSQL connection-opening SSL
EOF before DDL; `/tmp/queue-library-consumer-probe.log` retains that failure.
No retries, disabled SSL or host networking changes were added. Runtime
deprecation and Mockito/Byte Buddy test-agent warnings remain. Independent source
review approved this bounded probe; no remote CI result is claimed.

The coordinator-lifecycle refresh rebuilt the 29-source engine at 11:30:20 UTC
(`/tmp/queue-library-lifecycle-refresh-engine.log`). The strengthened boundary now
checks all 48 packaged class files, rather than only `AsyncJobStore`, and rejects
an isolated duplicate-coordinator JAR while the store resource remains unique.
The final JPA lane passed **17 tests with six real-DB skips** across five suites
at 11:35:25 UTC (`/tmp/queue-library-class-provenance-final-jpa.log`); a subsequent
clean lean run passed **11 tests with six real-DB skips** across four suites at
11:35:52 UTC (`/tmp/queue-library-class-provenance-final-lean.log`). Both XML audits
found no failures, errors or flaky/rerun entries. This refresh includes the latest
coordinator code and host-owned Hibernate/HSQL transaction contracts; the final
lean run also verifies removal of optional host classes. Docker was rechecked and
could not connect to its local socket, so no real-DB lane was attempted. The test
change does not alter engine code or dependencies, extract/publish a module, or
close admission, migration, release-provenance or production-soak gates.

The all-queue polling-stop change rebuilt the 29-source engine at 12:47:34 UTC
(`/tmp/queue-quiesce-engine.log`). The clean lean consumer passed **11 tests with
six real-database skips** across four suites at 12:48:17 UTC
(`/tmp/queue-quiesce-consumer.log`), including all-class provenance, with no
failures/errors/flaky/rerun entries. The new multi-queue shutdown regressions run
in the application test lane with in-memory storage and real worker/heartbeat
executors; the lean probe verifies packaging/composition, not real-DB draining.
The optional JPA consumer lane and real MySQL/PostgreSQL were not rerun in that pass.

The subsequent host-compatibility refresh rebuilt the current 29-source engine at
13:46:23 UTC (`/tmp/queue-current-jpa-engine.log`). The clean JPA consumer passed
**17 tests with six real-DB skips** across five suites at 13:46:47 UTC
(`/tmp/queue-current-jpa-consumer.log`); all six host-owned Hibernate/HSQL tests
executed without skips. Switching back with `clean` passed **11 lean tests with
six real-DB skips** at 13:47:35 UTC
(`/tmp/queue-current-jpa-then-lean-consumer.log`), including the absence of optional
host classes and all-class JAR provenance. Both XML audits found zero failures,
errors or flaky/rerun entries. No code/dependency change was needed. This refresh
closes the post-quiesce host-consumer verification gap, not real-database,
admission, extraction or rollout gates; it does not introduce new shutdown tests.
