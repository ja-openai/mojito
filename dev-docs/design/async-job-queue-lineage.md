# Tracked Assetlocalize Winner And Scope Authority

## Status And Boundary

**PROPOSED, 2026-09-09. Not implemented, approved, or rollout evidence.** This is
one bounded protocol for publishing tracked assetlocalize output and its exact
pull-run lineage. It does not audit remaining untracked generation effects, replace
the admission design, or claim exactly-once generation. The protocol changes no
guard, production code, DDL, migration, or lifetime setting.
Line anchors refer to inspected working files, not immutable commits or deployed code.

The [reproduced stale writer](async-job-queue-review.md#stale-lineage-reproduction-and-containment) leaves B's queue output
intact while A overwrites B's lineage. The [next-run boundary](async-job-queue-review.md#next-run)
requires durable scope authority, not another sequential-retry fix. Preserve the
[all-non-null-name exclusion](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobEligibility.java#L11),
including empty/whitespace names, at the [direct producer](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L294),
[child producer](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateMultiLocalizedAssetJob.java#L123),
[submission](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobSubmissionService.java#L70),
and [consumer](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobHandler.java#L69)
until this protocol and the independent rollout gates are implemented and accepted.

## Current Source Contract

| Anchor | Constraint on the proposal |
| --- | --- |
| [TMService:1179](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1179), [portable collection:1355](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1355), [portable replacement:1401](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1401) | Both generation paths collect selected variant IDs and replace lineage before returning output. Capture those actual selections, including additional plural forms, not a later TM query. |
| [TMService:1503](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1503), [PullRunAssetService:64](../../webapp/src/main/java/com/box/l10n/mojito/service/pullrun/PullRunAssetService.java#L64) | IDs are deduplicated, then the exact asset/locale/raw-tag set is deleted and inserted in batches of 1,000. Deletion and batch methods have separate default REQUIRED advice at lines 91 and 121; there is no outer publication transaction in this generation path. |
| [PullRun:23](../../webapp/src/main/java/com/box/l10n/mojito/entity/PullRun.java#L23), [lookup:51](../../webapp/src/main/java/com/box/l10n/mojito/service/pullrun/PullRunService.java#L51), [PullRunAsset:22](../../webapp/src/main/java/com/box/l10n/mojito/entity/PullRunAsset.java#L22) | Pull-run name is globally unique, lookup is by name alone, and asset membership is unique by `(pull_run_id, asset_id)`. Neither parent is scope-generation authority. |
| [PullRunTextUnitVariant:25](../../webapp/src/main/java/com/box/l10n/mojito/entity/PullRunTextUnitVariant.java#L25), [tag:57](../../webapp/src/main/java/com/box/l10n/mojito/entity/PullRunTextUnitVariant.java#L57), [scope query:28](../../webapp/src/main/java/com/box/l10n/mojito/service/pullrun/PullRunTextUnitVariantRepository.java#L28) | Existing association uniqueness includes nullable `output_bcp47_tag` and variant ID. It neither represents an empty set nor guarantees a single nullable-tag association under overlap. Tag storage is length 10. |
| [Handler:61](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobHandler.java#L61), [output storage:35](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobOutputStorage.java#L35) | Generation precedes private UUID-named output; current output contains no independently frozen variant set. Publication copies to a mutable canonical key with MIN_1_DAY retention. |
| [markDone:417](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L417), [runtime:925](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueRuntime.java#L925) | Queue DONE persists winning job data only for the live matching lease, before callback. A callback failure/unknown queue commit does not roll that commit back. |

## Exact Scope And Owner Decisions

Define the authority key as
`S = (pull_run_asset_id, locale_id, tag_kind, tag_bytes)`.
`tag_kind=ABSENT` uses non-null empty bytes; `tag_kind=VALUE` uses the exact UTF-8
bytes of the supplied `outputBcp47tag`. Use a real unique key on this non-null,
binary representation, not nullable SQL uniqueness, a concatenated string, or an
unchecked hash. ABSENT, VALUE(`""`), VALUE(`"null"`), and VALUE(`"fr-FR"`) differ.
No trimming, case folding, locale aliasing, or trailing-space-insensitive equality.
Reject unrepresentable new values rather than truncate them. Exact membership
deletion/insertion must use the same tag equivalence, not a database's default
case-insensitive/padded VARCHAR comparison. Preserve SQL NULL in legacy association
storage for ABSENT, as the [bound insert](../../webapp/src/main/java/com/box/l10n/mojito/service/pullrun/PullRunAssetService.java#L130) does today.
This requires reviewing association column/index equality and every scoped reader,
not just giving the new authority row a binary key. A binary delete predicate alone
cannot fix a case-insensitive existing unique index; gate that schema/read contract
in the forward migration and compatibility rehearsal.

The locale is the requested repository locale's `locale.id`, not the rendered tag
or an inherited translation's locale. An omitted tag renders using the locale tag
([TMService:1168](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1168)),
but remains ABSENT in lineage. Freeze that resolved rendering tag in accepted input;
do not replace the raw null with it when computing S. Pin resolved pull-run/asset IDs
at acceptance. Validate that pull-run repository, asset repository, and requested
repository-locale membership agree; never accept the wrong repository returned by
name-only lookup. Serialize first parent/scope creation using uniqueness; roll back
a duplicate-key transaction before re-reading the winner. Do not infer a new parent
identity on worker retry. Different run IDs, assets, locales, or exact tags do not
compete. Different content, filters, actors, or source-less options **do** compete
when S is identical: today's association model has no such additional dimensions.

**Owner approval required; defaults below are proposals, not existing semantics:**

| Choice | Proposed smallest policy and consequence |
| --- | --- |
| Latest accepted request versus latest completed request | **Latest accepted request wins eligibility.** A newer committed acceptance immediately disqualifies older unpublished requests even if it later fails. Do not fall back automatically. Latest-completed-wins is a different product contract and is not implemented by changing a comparison operator here. |
| One queue per scope | **One configured queue store and queue name (`assetlocalize`) per S**, fixed at scope creation. This does not mean one job or one worker: multiple accepted jobs/attempts can overlap. No simultaneous Quartz/second-queue writer. Moving a scope requires a drained cutover; queue name is not an extra scope dimension. |
| Superseded results and replay | An unpublished loser finishes with an expected `superseded` outcome and no public output. A previously published result remains historical success. Operator re-execution creates a new linked request, task, job, and generation; it never reopens an old task/queue row. |
| Publication compatibility and bounds | Permit a versioned tracked-output reader and one complete bounded business transaction per scope. Owners must set manifest/variant-count/transaction limits and result, replay, audit, and tombstone horizons before enablement. Oversized work is explicitly blocked, never partially published. |

## Minimal Durable Records And Ordering

Use the business database for scope authority and publication; the queue store owns
lease/DONE selection. These are logical fields, **not DDL or a new generic framework**:

- **Scope row:** S, fixed queue-store/name binding, `accepted_generation` (positive
  non-wrapping BIGINT counter; zero only before acceptance), accepted request ID,
  nullable `published_generation`/published attempt ID, and closed/retired state.
- **Accepted request:** reuse the proposed admission leaf, adding S and generation,
  immutable request/input digests, task ID, queue-store/name/job ID, optional replay-of
  request, and publication outcome `PENDING | PUBLISHED | SUPERSEDED | FAILED`.
  A PUBLISHED receipt freezes winner attempt ID, manifest key/digest/length and output
  pointer. Terminal outcomes never reopen. Queue deletion must not cascade here.
- **Attempt descriptor:** request/generation/job/claim identity, opaque attempt UUID,
  unique private manifest key and backend ownership, `PREPARING | STAGED | RETIRED`.
  Registration freezes expected manifest digest/length before upload; STAGED attests
  verified storage of those bytes. No restaging with different bytes. Retired
  descriptors remain rejection/cleanup tombstones.

Acceptance locks S and the deduplicated admission request in a consistent order,
increments the counter once, and commits the request binding with the new high-water
mark. An API retry of the same accepted request returns its original generation;
automatic attempts retain it. A new job for a new accepted request, including an
operator replay, gets a strictly higher generation. Reject overflow, never reset.
Authority survives queue/task retention and remains closed if its parents retire.

Add this step to [admission TX A](async-job-queue-admission.md#L178), not to producer
enqueue exception handling or worker startup. Initial implementation requires the
[proposed co-located JPA/JDBC acceptance transaction](async-job-queue-admission.md#L193),
with scope-before-request lock order made consistent across acceptance/publication/GC.
That transaction is still unimplemented. **Separate-store admission is an explicit
impasse:** if queue and business databases are physically separate at acceptance,
that TX A cannot atomically create both. Current public enqueue is
[REQUIRES_NEW](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L87)
and returns a generated ID, not an idempotent enqueue by request identity. Require a
separately approved durable enqueue bridge before supporting that topology; do not
assign generations from arrival timestamps or treat enqueue-then-record as atomic.
The publication protocol below tolerates distinct queue/business stores without XA;
it does not claim to solve this separate admission gate.

Within one job, **do not invent an attempt order**. The only winning attempt is the
manifest selected by that job's irreversible queue DONE transition. Before DONE
there is no winner; after DONE the slot cannot change. A reclaimed attempt cannot
complete with the old token. Claim [UUID generation](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L298)
is identity, not order; [attempt_count reset on raw replay](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L802)
is not authority. Block generic failed-row replay/payload editing for protocol-owned
jobs; never reopen/delete-and-recreate DONE identities. The monotonic chain is
accepted generation increasing, one write-once winner per bound job, then published
generation increasing. It does not depend on UUID sorting, task IDs, queue IDs,
clock order, parent `@Version`, or a counter that can be reset by an operator.

## Attempt Snapshot And Publication Protocol

1. **Capture without lineage writes.** A future tracked-only generation entry point
   returns `{localizedOutput, usedVariantIds}` from the same generation invocation.
   Refactor collection away from replacement in both TM paths; do not pass null
   `pullRunName` merely to bypass tracking. No tracked attempt calls current
   `replaceTextUnitVariants`, creates lineage parents, or writes canonical output.
   This requirement says nothing about unrelated generation effects being pure.
2. **Stage immutable bytes outside transactions.** Serialize one versioned manifest
   containing the exact output DTO, S, request/generation/job/attempt identities, input digest,
   resolved rendering tag, and sorted distinct positive variant IDs actually used.
   Persist `variantCount`, including zero and the explicit array `[]`; missing/null
   IDs are invalid, not empty. Register its private never-reused key and immutable
   digest/length, upload those bytes, verify the stored bytes, then conditionally
   mark PREPARING as STAGED in a short business transaction. Unknown registration
   commit must be resolved by descriptor ID before uploading. Identical retries
   may re-put identical bytes only; after a lost upload acknowledgement read/verify,
   never regenerate into that key.
   If the original bytes cannot be recovered, abandon that attempt and use a fresh
   descriptor on retry. Register keys before I/O so interrupted uploads are enumerable.
3. **Q-DONE selects the attempt.** Return only the versioned identities and manifest
   reference/digest in handler job data. Existing lease-fenced `markDone` commits in
   queue transaction Q. No business publication occurs on a rejected transition.
   An acknowledgement failure is unknown: query the authoritative queue primary;
   never infer loss from an exception or manufacture a new winner. If Q rolled back,
   normal queue retry may generate different output under a different attempt key.
4. **Resolve durable evidence, not callback arguments.** Callback and repair invoke
   the same publication routine by accepted request identity. An existing terminal
   business receipt needs only task projection, not a queue lookup or lineage replay;
   this also works after authorized queue retention. For a pending request, read its
   bound job from the queue primary and require DONE with exactly the STAGED descriptor's
   identities/digest. Validate and load the immutable manifest outside business locks.
   Queue unavailable/missing/unknown evidence means retry or operator-blocked, not
   success. A lease precheck is unnecessary: DONE is terminal evidence, not a live
   lease observation that can expire between check and write. This relies on enforced
   DONE immutability, non-reused job IDs, and the retention barrier below.
5. **B-PUBLISH is the sole business linearization point.** In one explicit business
   database transaction, lock S and request. If a terminal request receipt now exists,
   return it without reapplying lineage. Otherwise lock the attempt, recheck immutable
   binding, STAGED/not-retired state and digest, and require the verified winner; if S
   is closed or the request generation is below `accepted_generation`, commit only
   SUPERSEDED. If generation is ahead/mismatched, fail closed as inconsistent state.
   When it equals the accepted generation, delete **all and only** existing S rows,
   insert the complete manifest set, update S's published generation/attempt, and
   commit the request's PUBLISHED receipt/output pointer together. Empty sets still
   delete the old set and commit a non-null publication receipt with count zero.
   All 1,000-ID insert batches join this one transaction; none commits independently.
   Retrying a deadlock restarts the entire transaction and authority check, not a
   trailing insert batch. Bind parameters; flush JPA/JDBC work on the same enlisted
   connection and fail configuration if lineage and authority are different resources.
6. **Read and finish from the receipt.** Versioned tracked `/output` resolves only
   the PUBLISHED pointer and unwraps the immutable manifest's output, never a mutable
   canonical copy or a legacy fallback. Current [output reads](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskBlobStorage.java#L139)
   and [REST delegation](../../webapp/src/main/java/com/box/l10n/mojito/rest/pollableTask/PollableTaskWS.java#L45)
   need that future routing. Finish PollableTask afterward as an idempotent projection
   of the immutable request outcome in its own transaction. Lock/recheck task state;
   repeated success/failure/superseded notifications cannot change finished time or
   clear/overwrite a conflicting terminal error. A conflict is operator-blocked.

Atomicity is **B's lineage set + scope publication + request output pointer/receipt**,
not blob PUT + queue DONE + business rows + PollableTask. Q can be DONE while B is
pending; B can be published while task completion awaits repair. Preserve ordinary
[PollableTaskService REQUIRES_NEW behavior](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskService.java#L103)
for other callers; current `finishTask` is not an idempotent receipt projector and
does not clear earlier errors. Do not call it inside B and claim enlistment.
No queue calls/blob I/O under B's locks. Public task success requires committed B
and readable pinned output. A blob outage after publication is an availability
failure, not permission to republish an older attempt or rebuild output from live TM.

Current association readers retain their table, but output readers change. Readers
requiring a consistent current output/lineage pair must read S's published pointer
and associations in one business snapshot; independent reads can straddle a later
publication. Historical task output uses its own manifest/variant snapshot, not
today's mutable S rows. If owners require unchanged canonical-blob readers, or
cannot bound complete replacement in one transaction, this protocol is blocked;
it does not silently substitute a partially committed batch algorithm.

## Ordering, Recovery, And Lifetime

For one job, pause A before staging, reclaim with B, let B reach Q-DONE and publish,
then resume A: A may leave a private orphan but cannot obtain DONE or publish.
For different jobs at generations 1 and 2, generation 1 cannot publish after 2's
acceptance, regardless of callback order. If generation 1 published first, accepting
2 leaves that older published set visible until 2 publishes; expose accepted and
published generations separately. Failure of 2 does not roll authority back to 1.
A repeated generation-1 callback returns its historical receipt without replacing
generation-2 lineage. An old failure callback cannot fail a new replay's distinct task.

Recovery scans bounded pages (proposed 100 requests/tick) of unresolved business
outcomes and unfinished task projections, not just callback delivery. For durable
queue FAILED, lock S/request and record FAILED for the still-current generation or
SUPERSEDED for an obsolete generation, without lineage/output publication; existing
receipts prevail. Project that terminal error and never lower the high-water mark.
Missing/corrupt snapshots remain visibly blocked. Repair validates receipts even
for a finished task; today's
[alreadyFinished shortcut](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobRepairService.java#L119)
is not sufficient. On unknown B commit, discard the failed persistence context and
read the primary in a new transaction: a matching receipt proves completion;
otherwise retry B under its locks. A missing/unavailable lookup is not proof of
rollback or orphanhood. No process-local marker is recovery authority.

Pin accepted input, manifests, descriptors, queue winner evidence, request receipts,
and required parents/variants through execution, publication, projection, permitted
repair/replay, and client/audit windows. New protocol keys require durable storage
from their first write, with verified backend lifecycle exclusions; current
[MIN_1_DAY output writes](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobOutputStorage.java#L40)
do not satisfy this. Do not change lifetimes here; apply the separate
[storage-policy gates](async-job-queue-quartz-migration.md#payload-retention-and-pollabletask-compatibility). Initially leave
protocol cleanup disabled, with bounded canary admission/storage budgets, not an
unbounded-storage claim. Exclude protocol obligations from
[zombie task cleanup](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskCleanupService.java#L33)
and [pull-run cleanup](../../webapp/src/main/java/com/box/l10n/mojito/service/pullrun/PullRunService.java#L64)
before admitting them. A client polling timeout is not cancellation.

Future reference-aware cleanup must follow this order:

1. Retire losers only after the bound job has immutable terminal evidence and all
   potential publication outcomes are settled. Never collect an active attempt by
   age/lease expiry alone. Published manifests also remain pinned by historical task
   receipts, even after another generation becomes current.
2. Lock/recheck scope/request/attempt and commit retirement before deleting blobs
   outside transactions. Publication checks the same retirement state. Repeat key
   deletion after writer drain/grace to catch late acknowledged or unacknowledged
   uploads; retire every backend/fallback copy. A stale uploader can leak bytes,
   but cannot restore STAGED state or regain publication authority.
3. Purge terminal queue evidence only after the business outcome/receipt and task
   projection are durably settled and replay/audit windows permit it. The current
   [age-only terminal delete](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L835)
   lacks this barrier. With separate stores, check the immutable business receipt
   first, then conditionally delete the terminal queue row; this is safe only because
   settled requests cannot reopen. After purge, repair may project an existing receipt
   but must not first-publish from unverified callback data.
4. Close scope authority under lock before parent/association retirement. Keep the
   generation high-water and request/job tombstones beyond all late-callback/replay
   possibilities. Reused human run names must not resurrect old IDs or accept old
   bindings. Destructive restore/ID reuse requires a drained, reconciled cutover;
   retaining tombstones is mandatory until a finite safe horizon is approved.

## Cutover And Historical Constraints

Old writers must be stopped, not merely outvoted: [Quartz generation](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateLocalizedAssetJob.java#L13)
calls the same mutating generator, [Quartz output](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableJob.java#L97)
writes canonical blobs, and [synchronous localization](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L224)
also reaches TMService. Deploy reader/repair/cleanup support first with the tracked
guard intact. Stop tracked producers and drain/terminate all old executions before
activating authority for a scope. New binaries must reject synchronous/Quartz writes
to queue-owned scopes, not fall back on producer disablement. Old binaries that
ignore ownership cannot coexist there. Rollback must retain capable readers and
recovery for accepted work or stop it; flipping routing back to Quartz is not a
safe rollback. Supporting Quartz publication through this protocol is out of scope.

Prefer fresh pull runs for the first tracked canary. Existing rows have no request
generation, job/attempt winner, or complete-set marker. Empty means either no used
variants or absent/partial/deleted history; do not infer which. Timestamps, parent
versions, last callback, or variant creation order cannot backfill authority. Mark
legacy data unverified; adoption requires drained scope, repository/parent checks,
and explicit reconciliation or a new accepted generation, not invented provenance.
Historical SQL NULL versus literal `"null"` is ambiguous after the old insert bug
([retry audit](async-job-queue-review.md#pull-run-retry-source-audit)); only original inputs can disambiguate.
Never blanket-convert, combine, or delete literal-null rows as if they were ABSENT.
Audit existing nullable-tag duplicates and collation aliases before enforcing exact
scope semantics. Missing/deleted variant IDs block publication, not silent filtering.

Migration history is **unknown here**. Inspect actual applied history/checksums on
every target before implementation. If any queue version was applied, or its
unapplied status cannot be established, require a **new forward migration** for
authority changes; do not rewrite applied SQL. The integrated source uses
[MySQL V113](../../webapp/src/main/resources/db/migration/V113__Async_Job_Queue.sql#L1)
and [PostgreSQL V113](../../webapp/src/main/resources/db/postgresql/migration/V113__Async_Job_Queue.sql#L1),
renamed without SQL changes from the older queue branch's V109.
Even proven-unapplied status is not approval to edit them in this task. Fresh queue
fixtures do not prove a historical upgrade or whole-application PostgreSQL support.

Reject row-lock-only replacement (B-then-A is still serializable), locking only
delete, lease-check-then-write, random token ordering, resettable attempt counters,
per-job fences without cross-job S authority, DONE-as-client-success, mutable blob
overwrite-as-atomic-publication, and historical MAX(timestamp)-as-winner. None meets
the combined ordering and recovery contract above.

## Bounded Fault Acceptance Matrix

**Future acceptance tests, not run or claimed passing here.** Use real committed
business reads without a test-level transaction and real queue transactions. Run
each publication/ordering case on MySQL and PostgreSQL fixtures, plus an explicitly
separate queue/business-store fixture for Q/B boundaries. Use two workers, at most
two jobs plus one replay per S, one control scope, deterministic latches, finite
timeouts and joined workers. Repeat the two ordering races 100 times per enabled
dialect; no unbounded soak or throughput claim. Exercise both TM collection paths.

| Fault/interleaving | Required acceptance assertion |
| --- | --- |
| Same S created/accepted concurrently; retry one API key | One authority row and parent identity; distinct accepted requests get ordered generations; retry keeps its binding. Wrong repository/name collision is rejected. |
| Null, empty, literal-null, quoted, case/trailing-space tags; second locale/asset/run | Exact scopes stay isolated under both DB collations; rendered-default equality does not merge ABSENT with an explicit tag. |
| A selects old IDs, lease expires, B reclaims and publishes, A resumes | B's output pointer and exact lineage remain; A cannot finish/publish. Queue DONE names only B's manifest. |
| Jobs g1/g2 finish/callback in either order; g2 accepted while g1 publication locks are held | Serialization follows acceptance versus B commit, never callback arrival. Late g1 cannot overwrite g2; test both g1-before-acceptance success and g1-after-acceptance supersession. |
| g2 fails; replay g3; delayed g1/g2 callbacks and generic raw replay | No fallback to g1, no task reopen, g3 strictly higher with distinct task/job. Raw replay/payload mutation denied for protocol-owned work. |
| Variants change between attempts; duplicates; zero/one/1,001 IDs in both collectors | Output and frozen exact deduplicated IDs belong to one attempt. Empty winner clears prior rows and has a receipt. Missing list/invalid IDs fail closed. |
| Kill before upload, put-then-throw, corrupt/truncated manifest, descriptor commit unknown | No publication before verified STAGED bytes. Retry never overwrites a key with regenerated content; orphan descriptor remains discoverable. |
| Q commit-then-throw versus rollback-then-throw; kill before callback; Q primary unavailable | Read durable DONE to recover the winner; otherwise retry/block. No speculative success or lineage mutation. |
| Kill/exception after delete, after first 1,000 inserts, before receipt commit | All B changes roll back, including pointer/authority; previous complete set survives, never partial lineage. |
| B commit-then-throw versus rollback-then-throw; concurrent repair | Primary receipt resolves unknown outcome; at most one replacement/publication. Repeat callbacks do not rewrite associations or finished dates. |
| Accept g2 after g1 evidence read but before B; cleanup retires attempt in same gap | B's locked checks reject stale/retired work. No detached-check TOCTOU write. |
| B committed, crash before task finish; task already has contradictory terminal error | Repair projects receipt once; output remains pinned/readable. Contradiction is visible and blocked, not silently cleared or skipped as alreadyFinished. |
| Queue/blob/parent/zombie cleanup while Q-DONE awaits B; late loser upload after GC | Unresolved evidence and data survive; retirement prevents publication; repeated deletion catches orphan bytes. No age-only purge or resurrection through fallback storage. |
| Old Quartz/synchronous worker overlaps cutover; producer rollback; legacy missing outputId | Gate rejects unsafe coexistence and protocol legacy fallback. No tracked scope admitted while unfenced writers remain. |
| Ambiguous historical null rows, duplicates, missing variants; applied/unknown queue migration | No inferred winner or blanket conversion; adoption blocked pending evidence. Forward upgrade preserves old checksums/data. |
| Wrong business connection enlistment, separate-store admission, oversized replacement | Fail closed before enablement/publication. Do not claim XA, safe acceptance, or split a required atomic replacement into commits. |

Approval is still needed for winner semantics, one-queue ownership, superseded/replay
UX, pointer-reader compatibility, and finite operating limits. Admission durability,
historical migration evidence, lifecycle controls, old-writer drain, and the separate
untracked-effects audit remain gates; this artifact does not resolve them by assertion.
