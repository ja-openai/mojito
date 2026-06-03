# Durable Admission For Asset Localization

## Scope And Status
Design, 2026-09-08, based on working files in `mojito-queue-store-refined`; not deployment evidence.
**Proposed Protocol** is not wired or persisted. The pure direct-request identity model and
strict body decoder below are implemented, but neither they nor the telemetry fix resolve a lost database
commit acknowledgement.
Source links identify methods/inspected lines, not immutable commits; telemetry edits may shift lines.
Extend the [migration plan](async-job-queue-quartz-migration.md): JDBC `assetlocalize` admission, not global Quartz replacement or exactly-once effects.

## Implemented Today
| Path | Source-backed behavior |
| --- | --- |
| Direct async API | [AssetWS:259](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L259) accepts `POST /api/assets/{assetId}/localized`, returns a PollableTask, and selects queue or Quartz using flags. It fills a missing body asset ID but does not reject a conflicting one. There is no client request key. |
| Request semantics | [LocalizedAssetBody:15](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/LocalizedAssetBody.java#L15) contains content, asset/locale IDs, output tag, filter override/options, inheritance/status, pull-run name and source-less options. [Generation:40](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/LocalizedAssetGenerationService.java#L40) reads the body asset ID, resolves its repository locale, NFC-normalizes content, and passes all generation options to TMService. |
| Quartz identity | [QuartzJobInfo:85](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzJobInfo.java#L85) defaults to null `uniqueId`, 3,600-second timeout, zero expected children, and recovery false. [Scheduler:130](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableTaskScheduler.java#L130) creates a new task before resolving `uniqueId`; existing trigger identity causes rescheduling, not request deduplication. |
| Queue admission | [Submission:59](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobSubmissionService.java#L59) independently creates a task, saves input, and enqueues task-ID JSON. An enqueue exception enters `finishPollableTaskWithError`. No durable request-to-task mapping exists. |
| Parent fan-out | [Parallel API:346](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L346) schedules a Quartz parent. [GenerateMultiLocalizedAssetJob:71](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateMultiLocalizedAssetJob.java#L71) creates children sequentially and updates an in-memory output-tag map. [MultiLocalizedAssetBody:121](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/MultiLocalizedAssetBody.java#L121) overwrites duplicate output-tag keys. |
| Task transactions | [PollableTaskService:72](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskService.java#L72) declares create/finish operations `REQUIRES_NEW`; this deliberately keeps progress outside business transactions. [Application:36](../../webapp/src/main/java/com/box/l10n/mojito/Application.java#L36) enables JPA repositories/auditing and AspectJ transaction advice; [webapp/pom.xml:66](../../webapp/pom.xml#L66) supplies the Boot JPA starter. No explicit application `JpaTransactionManager` bean was found. |
| Queue transactions | [Configuration:24](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueConfiguration.java#L24) injects an unqualified PlatformTransactionManager. [JdbcAsyncJobStore:104](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L104) explicitly uses `REQUIRES_NEW`/`READ_COMMITTED`; enqueue is an ordinary insert with generated ID, not an idempotent insert. |
| Input storage | [PollableTaskBlobStorage:36](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskBlobStorage.java#L36) writes `pollable_task/<taskId>/input` with `MIN_1_DAY`. [BlobStorage:11](../../webapp/src/main/java/com/box/l10n/mojito/service/blobstorage/BlobStorage.java#L11) has get/put/delete/exists, no compare-and-set, listing, or relational enlistment. [DatabaseBlobStorage:77](../../webapp/src/main/java/com/box/l10n/mojito/service/blobstorage/database/DatabaseBlobStorage.java#L77) even declares its own independent write transaction. |

The asset adapters isolate schedule metrics/parent timer recording; the generic queue isolates post-commit/wakeup-error metrics.
Three asset submission regressions reproduce meter-type collisions. [Runtime regression:97](../../webapp/src/test/java/com/box/l10n/mojito/queue/AssetLocalizeAsyncJobQueueIntegrationTest.java#L97)
checks one accepted job drains despite metrics, with in-memory queue/mocked task storage.
This is not unknown-DB-commit coverage; see the [review ledger](async-job-queue-review.md) for verification results.

### Direct Request Identity Foundation
[AssetLocalizeAdmissionRequest.capture](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAdmissionRequest.java)
snapshots a direct request without side effects.
Its repository/actor/path asset arguments must come from trusted server context; the value
object checks positive IDs and body/path consistency, not authorization or repository/locale
membership. There are no Spring beans or producer/consumer call sites for this model yet.

The request envelope includes every generation option plus repository/actor/asset/locale,
`protocolVersion=1` and `operation=assetlocalize.direct`. Its fixed field order and escaping
are independent of application ObjectMapper settings. `canonicalBytes` returns a copy;
`requestSha256` hashes those exact UTF-8 bytes. The request key is separate and case-sensitive:
same content with a different key has the same fingerprint, not the same deduplication identity.
`toLocalizedAssetBody` returns a detached execution DTO from the same frozen NFC input.
Neither mutations to the original DTO/lists nor generation's response mutations affect it.
Diagnostic `toString` excludes the key, content and generation options.
The [golden contract tests](../../webapp/src/test/java/com/box/l10n/mojito/service/tm/AssetLocalizeAdmissionRequestTest.java)
pin exact bytes and independently calculated hashes; changing these vectors requires a protocol
compatibility decision, not simply regenerating expected hashes after serializer changes.

The model is a typed snapshot, not a raw JSON decoder. The separate decoder below handles
the distinctions DTO binding would lose. Parallel manifests and resolved execution-envelope
checksums are not implemented; do not use the request fingerprint as `input_sha256` by
assumption, or save these bytes at the legacy PollableTask input key without a version-aware reader.

### Strict Direct Body Decoder
[AssetLocalizeAdmissionRequestDecoder](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAdmissionRequestDecoder.java)
accepts raw UTF-8 bytes for one v1 direct request body and delegates to `capture`. It is pure,
unwired, and deliberately independent of the application's ObjectMapper settings. It does
not decode the canonical stored envelope: protocol/operation/repository/actor fields are
not client body fields. Trusted server scope and the client request key are separate arguments;
the decoder validates the key's shape, not the caller's authority to submit.

- Accept ordinary JSON whitespace, field order and equivalent escapes, but no encoding
  autodetection or framing BOM. Reject malformed UTF-8 and unpaired escaped surrogates.
- Reject duplicate fields, including escaped spellings, unknown fields and response-only
  `bcp47Tag` even when its value is null. Reject comments, trailing documents and nonstandard
  syntax. IDs require integral numeric tokens in signed 64-bit range and are positive after
  capture; no string, floating/exponent, boolean or enum coercion is allowed.
- Preserve omitted DTO defaults, optional nulls, and explicit null asset ID as path-derived.
  Locale remains required. Explicit null inheritance/status/boolean values are invalid.
  Preserve null whole branch list as empty and null branch entries as unnamed branches;
  null filter-option entries remain invalid. Arrays do not become singleton values or sets.
- Require an explicit positive byte budget at construction. Check the full raw byte length,
  including multibyte text and whitespace, before decoding; parser limits also bound strings,
  field names, numbers and nesting. This chooses no production HTTP size policy. A future
  transport adapter must cap reads before allocating its byte array; this decoder is not
  itself a streaming upload or transport-memory safeguard.
- Validation exceptions contain no source excerpts, supplied keys/names/values, or parser
  cause chains. Detailed HTTP status/error handling is not wired or established by this API.

The [decoder contract tests](../../webapp/src/test/java/com/box/l10n/mojito/service/tm/AssetLocalizeAdmissionRequestDecoderTest.java)
cover accepted body equivalence with the fixed identity golden as well as malformed input,
byte boundaries and exception redaction. A DTO-field guard requires new fields to be reviewed.

Authorization, repository/locale membership, durable reservations/acceptance and the
version-aware execution-envelope reader remain prerequisites. Existing endpoints retain
their legacy DTO parsing; these stricter rules do not silently change the current API.

### Why Finishing On Enqueue Error Is Unsafe
An insert can commit, then the connection can fail before `TransactionTemplate` returns.
That is **unknown outcome**, not rollback; the adapter may fail the task while a runnable queue row exists.
The handler [process:58](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobHandler.java#L58) only requires task existence: generation/publication can continue.
[finishTask:110](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskService.java#L110)
sets `finishedDate`, but a later successful call does not clear prior error fields.
Polling traverses those errors and throws, even if generation subsequently succeeds.
[Repair:119](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobRepairService.java#L119)
returns `alreadyFinished`; it neither reopens the task nor clears the false error.
**Admission gate remains open:** genuine DB-unknown exceptions still enter failure compensation.
The proposed TX A/primary-key resolution must replace that later. Current repair cannot reverse it.
Do not clear arbitrary errors: timeout, generation failure and false rejection share `finishedDate`.
An enqueue that returned its ID is known committed at the store seam; subsequent metrics/wakeup
failures must not reject it. The telemetry correction handles that different failure mode.

## Proposed Protocol

### Stable Identity And Canonical Checksums
- Opt in with `Idempotency-Key` on direct API, then parallel API. Accept 1-128 ASCII bytes
  from `!` through `~`, case-sensitive; clients persist one random key per intended operation.
- Root uniqueness: `(repository_id, actor_user_id, operation, request_key)`, binary comparison.
  Operation distinguishes direct/parallel; protocol version is pinned in the fingerprint.
  A retry must use the original protocol, not silently reinterpret an existing key under new rules.
  Same key/hash returns the original request/task,
  including completed/failed work; different hash returns 409. New intended work needs a new key.
- Child identity is `(parent_request_uuid, child_slot)`, original zero-based frozen manifest position.
  Never identify requests by queue/new task ID, execution attempt, or output tag alone.
- Compute `request_sha256` over a dedicated v1 envelope, not the mutable DTO's serialization:
  lexicographically sorted ASCII member names, decimal integers, enum names, lowercase booleans/null,
  UTF-8 without BOM/whitespace. Escape quote/backslash and controls as lowercase `\u00xx`;
  emit other Unicode literally. Reject unknown/duplicate fields and unpaired surrogates; golden-test bytes.
- Envelope includes protocol/operation, authoritative asset/repository/actor/locale IDs, NFC content
  (null distinct from empty), `outputBcp47tag`, filter override/options, inheritance/status, pull-run
  name, source-less boolean/branches. Parallel uses ordered locale entries and shared source/options.
- Omitted defaults: `USE_PARENT`, `ALL`, false, empty branches; reject null required enum/boolean
  values. Preserve filter-option/branch order, duplicates, whitespace and case, not set semantics.
  Keep optional null distinct from empty except branches' existing null-to-empty conversion.
- Preserve null branch entries: they select the unnamed default branch, unlike the string
  `"null"`. Reject null filter-option entries. A nonempty branch list requests source-less
  augmentation even when the boolean is false; an empty list with the boolean true selects
  all active branches. Never reduce these two fields to the boolean alone.
- Apply [NormalizationUtils:12](../../webapp/src/main/java/com/box/l10n/mojito/service/NormalizationUtils.java#L12)
  to content only. Exclude response-only `bcp47Tag`/child-ID map (reject supplied v1
  output fields), scheduling time, backend flags, task/queue IDs, and live TM data.
  Include all execution-affecting client fields, not merely content or `{pollableTaskId}`.
- Controls means U+0000 through U+001F. DEL/C1, U+2028/U+2029 and embedded U+FEFF are literal
  Unicode, not removed or escaped; there is no framing BOM. Keep this byte contract versioned.
- Separately persist `input_sha256` and byte length of the exact uploaded execution envelope.
  Freeze parent default output tags in reservation metadata and reuse them on retries;
  execution uses those tags, not freshly resolved ones. Dedupe does not snapshot TM/filter code.

### Authorization And Durable Rows
Resolve the path asset, reject conflicting body ID, derive repository server-side, validate locale membership.
Use authenticated stable user ID, never supplied actor/repository IDs or token text.
The normal [security chain:331](../../webapp/src/main/java/com/box/l10n/mojito/security/WebSecurityConfig.java#L331)
requires PM/ADMIN for these POSTs; it is not a repository ACL. Preserve that policy;
any narrower repository entitlement must be explicitly supplied, not presumed present.
Reauthorize retries/lookups; recheck submit eligibility before accepting a PREPARING root.
Default v1 reads require the same actor and submit role; admin repair remains explicitly admin-only.
Internal children use the accepted parent's recorded grant, actor/repo, never public parent overrides.
Apply the same checks to v1 tasks' existing polling/input/output/inspection routes:
[PollableTaskWS:38](../../webapp/src/main/java/com/box/l10n/mojito/rest/pollableTask/PollableTaskWS.java#L38)
currently delegates by numeric ID without ownership checks. Do not hide this prerequisite.

Add `asset_localize_admission` for both dialects. Verify applied migration state before
changing DDL: only while V109 remains unapplied may admission DDL be folded into the single
queue migration per dialect. If queue DDL was applied anywhere, use a new forward migration
there; never edit applied SQL/checksums or infer deployment state from branch/merge state.
- UUID PK, operation, repo/actor, binary request key; unique root scope; immutable request hash.
  CHECK exactly one root key or internal parent UUID/slot is populated; unique `(parent UUID, slot)`.
- `state=PREPARING|ACCEPTED|ABANDONED`, protocol version, chosen route, input key/hash/
  length, frozen execution metadata, created/deadline timestamps; index state/deadline/ID.
- Unique nullable `pollable_task_id` and `queue_job_id`; CHECK that ACCEPTED leaf has
  both, PREPARING/ABANDONED have neither. No cascade from queue retention to admission.
  Accepted parent has task ID, no queue ID, expected count, `fanout=PENDING|COMPLETE|BLOCKED`,
  next retry time and bounded error code. Root keys are null on internal child rows.
- Parent input holds the complete ordered plan; child rows map slots to tasks/queue rows.
  Bound plan to 1,000 entries; reject duplicate effective output tags before children: the map loses them.

### Reservation, Upload, Acceptance
1. Require no ambient transaction at orchestration entry: suspending one does not release its locks.
   Validate/canonicalize, then short TX R inserts PREPARING with fixed route/UUID/metadata or returns
   its matching row. Hash conflicts fail without uploads. Roll back a duplicate-key transaction
   before re-reading, including PostgreSQL. PREPARING is a durable reservation, not acceptance.
2. Outside transactions upload frozen bytes to `pollable_task/admission/<uuid>/input-v1`.
   Reservation ensures all writers for that UUID produce identical bytes. Verify hash/length
   with getBytes, not `exists`; bound upload/read deadlines. Put timeout may still have written:
   verify/retry identical bytes, never enqueue unverified input. Use PERMANENT, not one day.
3. Short TX A locks the row. ACCEPTED returns existing IDs. PREPARING requires verified matching
   input, valid deadline and allowed route. Persist/flush one task, insert one queue row, then set
   ACCEPTED and both IDs atomically. No blob, metrics, wakeup or generation under locks.
   Rollback exposes neither task nor queue row; sequence gaps are harmless.
4. After TX A returns, respond and best-effort nudge workers; polling ensures delivery.
   Lost response: retry the same key. No exception after attempted commit may trigger
   `finishTask`, input deletion, or Quartz fallback. Workers verify input digest before generation.
5. Reconciler scans at most 100 PREPARING rows per tick, verifies blobs outside transactions,
   then runs TX A. Missing input awaits same-key retry until deadline; expiry locks/rechecks then
   marks ABANDONED. No task is failed for unaccepted work; accepted parents' children cannot expire.
6. GC first closes admission under lock, then deletes abandoned/retired input outside locks.
   Rows inventory interrupted uploads; repeat abandoned-key deletion to catch late writes.
   Never infer orphanhood from a failed lookup/replica miss. Pin accepted input/mappings through
   work, fan-out, publication, repair and retry windows; retain dedupe tombstones after cleanup.

### Exact Transaction Ownership
`AssetLocalizeAdmissionService` owns explicit TransactionTemplates using the application's JPA
transaction manager: TX R/TX A are bounded `REQUIRES_NEW`/`READ_COMMITTED` operations.
A non-advised EntityManager.persist/flush helper copies task name/message/parent/count/timeout
and explicitly persists actor/audit timestamps. Existing `createPollableTask` starts another
`REQUIRES_NEW` transaction: do not call it inside TX A or change it for other callers.
Extract the current [enqueue SQL:149](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L149)
into a package-local primitive shared with a JDBC admission adapter, requiring the owned transaction.
No nested transaction; existing public store methods keep independent transactions.
Assert identical EntityManager/JdbcTemplate DataSource/connection enlistment; fail startup otherwise.
Do not add a competing DataSourceTransactionManager or infer safety from a JDBC-only test:
[existing no-advice test:27](../../webapp/src/test/java/com/box/l10n/mojito/queue/AsyncJobQueueJdbcTransactionConfigurationTest.java#L27)
uses DataSourceTransactionManager, not JPA. Test persist+insert rollback without weaving.
Leaf queue row is the intent, no separate outbox. Independent `enqueueNow` then mark-delivered is unsafe.
Parent rows are the fan-out outbox. Preserve legacy Quartz/globaltransaction behavior,
AspectJ configuration, business transactions and public PollableTaskService propagation.

### Unknown Commit And Client Contract
On TX R/A commit exception, discard failed persistence context/connection; read the primary by
scoped key in a fresh transaction. ACCEPTED returns original IDs; PREPARING retries verified TX A.
A miss cannot rule out an in-flight commit: retry reservation with the same unique key so DB
serialization settles it. Unavailable reads/locks yield 503 `admission_outcome_unknown`, echoed
key and `Retry-After`, never definitive rejection or an uncommitted candidate task ID.
Verified pending work: 503 `admission_pending`; abandoned key: 410, no reuse.
Only proven ACCEPTED yields 202 with PollableTask body, `Location: /api/pollableTasks/<id>`,
echoed key and `Mojito-Admission-Version: 1`. Repeat returns same task ID/current state.
Queue IDs stay internal/admin correlation; task ID remains the client handle.
Retired accepted keys return 410 `accepted_result_expired`, not new work. Current API returns 200;
adapt [AssetClient:279](../../restclient/src/main/java/com/box/l10n/mojito/rest/client/AssetClient.java#L279)
to send/reuse keys and inspect protocol headers. Input readers unwrap v1 envelopes to existing DTO JSON.

### Partial Parent Fan-Out
For v1 parallel requests, TX A creates parent task/immutable manifest, sets expected children N
and `fanout=PENDING`, without a leaf queue row. After commit a v1 Quartz kick schedules this
existing task under deterministic parent UUID identity, never the task-creating scheduler helper.
Reconciliation atomically advances due retry time before re-kicking; crashes wait for the next tick.
Use a v1-only Quartz wrapper delegating to `resume(parentRequestId)`, not the base
[QuartzPollableJob:94](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableJob.java#L94)
which catches every failure and finishes the parent in `finally`. Legacy jobs keep it.
`resume` reads frozen plan, accepts stable slots via the child protocol, and reconstructs the
output-tag-to-task map from committed rows. Crash after child k resumes k+1; concurrent kicks
dedupe via locks/uniqueness. Retryable/unknown errors leave parent pending; cap 50 children/kick.
Permanently invalid accepted plans become operator-BLOCKED, preserving accepted children.
After all N mappings exist, publish deterministic parent output outside locks, then atomically
set fan-out complete and parent finished. Duplicate publication is safe: mapping cannot change.
[PollableTask:205](../../webapp/src/main/java/com/box/l10n/mojito/entity/PollableTask.java#L205)
then keeps `allFinished=false` until children finish. Do not increment N on retries.
Prevent [zombie cleanup:33](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskCleanupService.java#L33)
from failing v1 obligations: exclude them in its query, not after selecting the same five forever.
Queue completion owns terminal status; accepted-work timeout/cancellation needs an explicit policy.

## Compatibility And Finite Delivery
Lookup keys before the new-admission switch. Accepted routes/children survive producer rollback;
keep consumers/reconciliation alive. PREPARING roots pause, never switch to Quartz.
Unkeyed requests retain legacy behavior, without safe automatic retry guarantees.
Old accepted task/queue rows retain IDs/readers; do not infer identity by hash/output tag or
restart old partial parents. Provenance is insufficient; operator reconciliation precedes replay.
Install v1-aware readers (including input/inspection) before producers: admission pointers for v1,
canonical input for legacy; queue payload still identifies task. Old consumers cannot read v1,
old producers ignore keys/flags: drain/isolate them, route keyed HTTP only to capable nodes.
No downgrade with v1 obligations. Old false failures need evidence-led correction, not auto-reopening.
Legacy [Quartz scheduler:250](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableTaskScheduler.java#L250)
still has fallible duration/step metrics after scheduling. Producer rollback to Quartz therefore
does not guarantee truthful admission responses; that broader telemetry slice is not fixed here.

1. Isolate known-committed telemetry/wakeup failures; no claim of atomic admission.
2. Add canonical v1 request model, validation, schema, unique constraints, reservation and checksum readers; producer remains off.
3. Add explicit JPA/JDBC TX A, direct API/auth contract, reconciliation, retention and v1 timeout-cleanup exclusion; prove real-DB faults before enablement.
4. Add frozen parallel manifests, existing-task Quartz kick/resume wrapper and parent finalization; direct-only canary precedes fan-out.
5. Add compatibility routing/CLI tests and bounded operational reconciliation; drain old binaries and satisfy the migration plan's separate business-effect/rollout gates.

## Required Failure-Injection Matrix
New tests, not claimed passing. Real MySQL/PostgreSQL and JPA without transaction weaving.
Wrap Connection.commit for commit-then-throw versus rollback-then-throw; latch in-flight commit/retry.
Mock exceptions alone cannot prove safety.

| Injection | Required assertion |
| --- | --- |
| Lost TX R acknowledgement; two concurrent same-key reservations | One request UUID; no task yet; identical bytes only; conflicting checksum is 409 without upload. |
| Crash before/during/after put; put writes then throws; corrupt/truncated bytes | No leaf queue row before verified input; retry/reconciler accepts exactly once or leaves PREPARING. |
| Throw after task flush, after queue insert, before ACCEPTED update | Real rollback removes both task and queue; request stays PREPARING; no worker observes phantom acceptance. |
| TX A commit-then-throw; rollback-then-throw; lookup DB unavailable | Same-key retry returns original IDs or creates one pair after rollback; unknown returns no false task failure. |
| In-flight commit + primary miss + retry; deadlock/lock timeout | Unique request serializes completion; no second executable queue row; deadlines yield explicit uncertainty. |
| Outer JPA rollback; no AspectJ; wrong/mismatched transaction manager | TX A remains independently durable; failed TX A rolls back both technologies; wrong configuration fails closed. |
| Metrics, local wakeup, notifier, response serialization fail after commit | Accepted row/task unchanged; lost response recovered by key; no Quartz fallback or compensation. |
| JSON order/escaping/default/NFC variants; every option mutated | Golden canonical equivalence only where specified; changed semantics conflicts; ordered arrays remain ordered. |
| Same key different actor/repo; body/path mismatch; revoked caller; forged parent slot | No cross-scope lookup leakage, task/payload exposure, or child insertion; authorization enforced on all reads. |
| Parent crash after k commits, output upload, or finish commit; duplicate kicks | N children total, stable IDs/map, no early error/success, restart reconstructs output and completion. |
| Duplicate output tags; manifest edited/reordered; repo tags change on retry | Reject invalid new plan; immutable accepted slots and output tags never drift. |
| GC races late upload/TX A; queue retention before repair; zombie cleanup | Accepted input/mapping survives; abandoned cannot accept; no premature task failure; retained key never reexecutes. |
| Producer disabled mid-request/fan-out; old binary handles key or v1 task | Existing acceptance stays routed; pending roots pause; deployment gate catches incapable nodes. |
| Legacy ambiguous enqueue then success/repair | Reproduce sticky error and `alreadyFinished`; no assertion that current repair reverses it. |
| V109 fresh install versus previously applied queue DDL | One queue migration per dialect on never-deployed branch; forward upgrade preserves applied checksums. |

## Decisions Versus Defaults
Implementation defaults: scoped keys, two checksums, ordered slots, primary resolution,
explicit transactions, no ambiguous compensation/fallback. Product approval: opt-in 202 versus
versioned endpoint; mandatory keys; actor sharing/repo entitlements; duplicate tags/fan-out limit;
preparation TTL/retry/tombstone windows; accepted-job deadlines/operator-BLOCKED UX.
Keep input permanent/cleanup disabled pending approval; no old-error reset, retry-safe lineage, or rollout claim.
