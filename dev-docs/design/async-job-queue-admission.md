# Durable Admission For Asset Localization

## Scope And Status
Design, 2026-09-08, based on working files in `mojito-queue-store-refined`; not deployment evidence.
**Proposed Protocol** is not wired or persisted. The pure direct-request identity model and
strict body decoder below are implemented, but neither they nor the telemetry/rollout controls resolve a lost database
commit acknowledgement.
Source links identify methods/inspected lines, not immutable commits; telemetry edits may shift lines.
Extend the [migration plan](async-job-queue-quartz-migration.md): JDBC `assetlocalize` admission, not global Quartz replacement or exactly-once effects.

## Implemented Today
| Path | Source-backed behavior |
| --- | --- |
| Direct async API | [AssetWS](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java) accepts `POST /api/assets/{assetId}/localized`, returns a PollableTask, and selects queue or Quartz using flags. Both this endpoint and `/localized/parallel` reject a conflicting non-null body asset ID with HTTP 400 before asset lookup, metrics, input mutation or scheduling. Omitted/null IDs still use the URL asset. This also applies with the queue disabled. There is no client request key. |
| Request semantics | [LocalizedAssetBody:15](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/LocalizedAssetBody.java#L15) contains content, asset/locale IDs, output tag, filter override/options, inheritance/status, pull-run name and source-less options. [Generation:40](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/LocalizedAssetGenerationService.java#L40) reads the body asset ID, resolves its repository locale, NFC-normalizes content, and passes all generation options to TMService. |
| Quartz identity | [QuartzJobInfo:85](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzJobInfo.java#L85) defaults to null `uniqueId`, 3,600-second timeout, zero expected children, and recovery false. [Scheduler:130](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableTaskScheduler.java#L130) creates a new task before resolving `uniqueId`; existing trigger identity causes rescheduling, not request deduplication. |
| Queue admission | [Submission](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobSubmissionService.java) independently creates a task, saves input, and enqueues task-ID JSON. Only preparation failures before enqueue enter `finishPollableTaskWithError`. Once enqueue is invoked, a nonfatal exception preserves task/input state, records `outcomeUnknown` and propagates; no retry or fallback occurs. No durable request-to-task mapping exists. |
| Parent fan-out | [Parallel API:346](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L346) schedules a Quartz parent. [GenerateMultiLocalizedAssetJob:71](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateMultiLocalizedAssetJob.java#L71) creates children sequentially and updates an in-memory output-tag map. [MultiLocalizedAssetBody:121](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/MultiLocalizedAssetBody.java#L121) overwrites duplicate output-tag keys. |
| Task transactions | [PollableTaskService:72](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskService.java#L72) declares create/finish operations `REQUIRES_NEW`; this deliberately keeps progress outside business transactions. [Application:36](../../webapp/src/main/java/com/box/l10n/mojito/Application.java#L36) enables JPA repositories/auditing and AspectJ transaction advice; [webapp/pom.xml:66](../../webapp/pom.xml#L66) supplies the Boot JPA starter. No explicit application `JpaTransactionManager` bean was found. |
| Queue transactions | [Configuration](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueConfiguration.java) injects an unqualified PlatformTransactionManager. [JdbcAsyncJobStore](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java) validates matching JDBC/JPA datasource resources at construction and explicitly uses `REQUIRES_NEW`/`READ_COMMITTED`; enqueue is an ordinary insert with generated ID, not an idempotent insert. |
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
[AssetLocalizeAdmissionRequestDecoder.decode](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAdmissionRequestDecoder.java)
accepts raw UTF-8 bytes for one v1 direct request body and delegates to `capture`. It is pure,
unwired, and deliberately independent of the application's ObjectMapper settings. This body method does
not accept the canonical stored envelope: protocol/operation/repository/actor fields are
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
their legacy DTO parsing; the decoder's stricter JSON rules are not wired into the current API.
The separate async/parallel URL-body asset consistency guard above is implemented;
it establishes identity consistency, not repository authorization or durable admission.

### Restoring Stored Request Identity

The separate `AssetLocalizeAdmissionRequestDecoder.restore` method reconstructs the
exact v1 canonical identity for a future recovery path. Supply repository, actor,
asset, key and `request_sha256` from trusted durable metadata, not the blob itself
or client assertions. It is pure and unwired: no reservation row, blob read, task
acceptance or restart reconciler is added by this reader.

The method checks the byte budget and lowercase 64-character SHA-256 shape, then
copies the bytes once. Hash verification, strict UTF-8/JSON parsing and canonical
comparison use that same snapshot. Stored protocol and operation must be v1 direct;
repository/actor/asset must match the supplied scope. Re-encoding must exactly equal
the stored bytes. Missing fields, even optional/default-valued ones, reordered fields,
extra whitespace, alternate escapes or decomposed content are rejected, not silently
upgraded or normalized during recovery. Client-body decoding still accepts its
documented equivalent representations and still rejects server metadata.

The [stored-reader contracts](../../webapp/src/test/java/com/box/l10n/mojito/service/tm/AssetLocalizeAdmissionRequestRestoreTest.java)
reuse the independently hashed capture golden, exercise changed metadata with
matching hashes, invalid encodings, missing fields, byte boundaries and independent
returned snapshots. Failures carry a fixed message with no parser/source cause.
Changing canonical writers therefore requires reader compatibility work, not just
regenerating test hashes.

The key is deliberately absent from canonical bytes: a different supplied key can
restore the same content/hash. The future primary-key lookup and authorization must
establish that binding. Hash integrity is not authenticity, acceptance or proof of
repository/locale membership. This reads request identity, **not** the separately
resolved execution envelope; `input_sha256`, frozen execution metadata, storage
ownership and recovery orchestration remain unimplemented. The byte-array budget
does not cap a storage client's allocation or impose an I/O deadline.

### Stored Queue Identity Decoding

The current adapter's persisted `{pollableTaskId, outputId?}` payload now has one
strict decoder shared by worker execution, completion/failure callbacks and repair.
It is distinct from the proposed direct-request decoder above, and stays in the
Mojito adapter rather than the generic queue. The generic queue still stores opaque
strings and does not know PollableTask identity or JSON schema.

Require a positive signed-64-bit integral JSON token for `pollableTaskId`, not a
fraction, exponent, string or other coerced value. `outputId` remains optional or
null for legacy canonical-output jobs; nonnull values require the existing canonical
UUID string. Ordinary whitespace, field order and equivalent escapes are accepted.
Reject duplicate/unknown fields, trailing documents, nonstandard syntax, incomplete
objects and null/non-object roots. Decode with a private bounded parser, independent
of application ObjectMapper settings. The full string remains limited to the queue's
1,000,000 UTF-16 code units; field/token/nesting limits bound the tiny identity envelope.
Parser errors contain no source excerpts, supplied values or nested parser causes.

This closes a reproduced coercion defect: `42.9` previously selected task `42`, and
the done callback could publish/finish that task. Current serialized integer-ID
payloads, with and without attempt-output UUIDs, remain compatible. Malformed rows
are not rewritten, and no task identity is guessed to repair them. Invalid identity
requests first-attempt FAILED through the direct permanent-failure marker; its terminal callback can also
fail to decode, leaving the queue row FAILED without changing an inferred task.
Repair classifies these as invalid payloads before PollableTask lookup/publication.
Strict syntax is not proof that a well-formed ID belongs to this queue row, caller,
repository or operation; durable admission bindings and authorization remain open.
Future payload fields require an explicit reader/writer compatibility decision.

### Current Stored Input Encoding And Framing

The queue handler reads raw legacy PollableTask input bytes with a reporting UTF-8
decoder before using the same qualified mapper as PollableTaskBlobStorage. Malformed
encoding cannot silently become U+FFFD and reach generation. An immutable reader
requires one nonnull DTO and rejects duplicate fields and trailing documents. This prevents ambiguous
duplicate `pullRunName` fields from hiding tracked input from the eligibility
guard. Parser errors are redacted without retaining source-bearing cause chains;
missing blobs and provider failures retain their original errors. Rejected input
never reaches generation or attempt-output storage. Malformed UTF-8, syntax/shape or null
input requests first-attempt FAILED; missing blobs, provider failures and mapper-definition
errors retain ordinary bounded retries. Queue permanence is not expected user-error classification.

Decoded DTO strings and string-list entries also reject unpaired UTF-16 surrogates:
an escaped `\ud800` can occur in otherwise valid UTF-8 JSON. This check runs before
generation, before private output serialization and after retained output decoding,
so later UTF-8 encoding cannot silently replace such values with `?`. It preserves
valid surrogate pairs, literal U+FFFD, controls and existing nullable fields/list
entries. The check is adapter-local, not the v1 execution-envelope reader, content
normalization, XML validation, checksum verification or an admission binding.

The shared mapper and non-queue readers are unchanged. Literal U+FFFD, embedded U+FEFF
and supplementary Unicode remain valid; UTF-16 documents and framing BOMs are not
newly accepted. Validation does not rewrite stored payloads. Azure fallback can still
backfill exact raw bytes before validation and records these reads as `format=bytes`,
not `string`; this is not a side-effect-free shadow reader. Existing DTO defaults,
nullable values, response fields and configured unknown-field/binding behavior
remain compatible. This is not the stricter unwired v1 body decoder or
transport-size validation, immutable input, checksum verification or proof that
the decoded asset/locale belongs to the queued task. Typed coercion follows the
legacy mapper until the versioned execution-envelope contract replaces it.

### Unknown Enqueue Containment, Not Recovery
The asset producer screens JVM-fatal errors through cause/suppressed chains before
ordinary failure metrics, task compensation or warning fallbacks. It rethrows the
original fatal object, including a `ThreadDeath` subclass, with identity-based cycle
detection and no error-graph rewrite. The same rule applies to task/input/payload,
enqueue, metric/logger and compensation boundaries. A fatal error after input
storage can leave a pending task and committed input; after enqueue it may leave
accepted work. Neither is rolled back or retried here. This covers errors exposed
to the adapter, not fatal failures already swallowed inside another service, actual
out-of-memory survivability, runtime fail-stop or durable recovery.

An insert can commit, then the connection can fail before `TransactionTemplate` returns.
That is **unknown outcome**, not rollback. The previous adapter could fail the task while a runnable queue row existed.
Before terminal-task containment, the handler required only task existence, so
generation/publication could continue for an already-failed task. The handler now
rejects a task observed finished before input/generation, and its permanent-failure
callback preserves that terminal result. The success callback also rereads the
task and skips publication/finish if it is already terminal, consistent with
repair. These read-time guards neither reopen the task nor fence timeout/completion
races after either check.
[finishTask:110](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskService.java#L110)
sets `finishedDate`, but a later successful call does not clear prior error fields.
Polling traverses those errors and throws, even if generation subsequently succeeds.
[Repair:119](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobRepairService.java#L119)
returns `alreadyFinished`; it neither reopens the task nor clears the false error.
The adapter now compensates only input preparation/payload construction errors before enqueue.
Preparation compensation records a cause-free unexpected error with the task ID,
not raw storage/serialization diagnostics, because task and inspection responses
expose `errorStack`. The original preparation exception is still returned to the
caller and best-effort logged with task correlation. No queue row has been attempted
at this boundary; a blob write may nevertheless have completed before throwing.
This does not redact operator logs or the caller's exception, rewrite historical
task errors, or change legacy task/callback error handling and access policies.
Every nonfatal exception after enqueue invocation is conservatively unknown: preserve the
existing task and input, emit `assetLocalizeAsyncJob.schedule{result=outcomeUnknown}`, and
rethrow. Do not infer rollback from exception type, query failure, or missing acknowledgement.
This includes genuine rollback/connection-acquisition failures, so orphan pending tasks remain
possible. Generic queue and caller `failed` counters describe call failure, not proven rollback.

**Admission gate remains open:** callers still get an exception without a durable retry handle.
Retry can duplicate accepted work; a parallel Quartz parent still aborts before saving its child
mapping and may finish failed even if that child succeeds. Timeout cleanup needs a positive
timeout and a running cleaner, and can itself race delayed committed work. It is not admission
reconciliation. This patch does not clear historical false errors or change timeout, blob lifetime,
HTTP status, client retry or parent fan-out semantics. The proposed TX A/primary-key resolution
must replace that uncertainty later. Current repair cannot reverse historical false errors.
Do not clear arbitrary errors: timeout, generation failure and false rejection share `finishedDate`.
An enqueue that returned its ID is known committed at the store seam; subsequent nonfatal
metrics/wakeup failures must not reject it. The telemetry correction handles that different mode.
Generic submission propagates JVM-fatal causes and suppressed errors from the store,
metrics, logging, wakeup-timing clock and local/remote hints. A post-enqueue fatal
stops subsequent work without retry or compensation; it does not undo the accepted
row or give the caller a durable retry handle. These error boundaries are not an
alternative to the proposed same-key recovery protocol.

`PollableTaskBlobStorage.saveInput` also isolates its own summary, duration and log
diagnostics from the write outcome. Nonfatal telemetry failures cannot block the
write, reject an acknowledged write or replace a real serialization/storage failure.
Fatal causes/suppressed errors still escape; a fatal storage failure skips later
diagnostics. Real task/blob plus in-memory queue regressions verify acceptance
despite summary/timer collisions, without changing shared beans or enabling workers.
This shared input method also serves Quartz and other callers. JSON, keys,
retention, metadata/provider resolution and output methods are unchanged. It does
not recover unknown storage commits, make task/blob/queue acceptance atomic, pin
input or correct historical task errors.

### Queue Fan-Out Resolution Preflight

For untracked parallel requests with all four queue producer flags enabled, the
parent resolves the complete locale list before scheduling a child. Positive locale
IDs and repository membership are required, including when an explicit output alias
is supplied. A lookup or default-tag resolution failure leaves the child-ID map
unchanged and invokes neither scheduler. The parent task itself may already exist.

The ephemeral plan captures locale IDs, resolved map/message tags and nullable child
output overrides in request order. It holds scalar values, not mutable LocaleInfo
objects, ORM entities, child DTOs or duplicated source payloads. Successful fan-out
still performs one membership lookup per input entry and no second resolution during
scheduling. Memory is O(N); there is no new independent fan-out bound. A scheduling
failure can now follow all N lookups instead of only its preceding lookups.

The separate `asset-localize.fanout-enabled` flag now defaults to `false`: enabling
the umbrella and direct producer controls alone leaves parallel children on Quartz.
Explicit fan-out opt-in is required in addition to those controls. This is a restart-
applied control at `GenerateMultiLocalizedAssetJob`, not a durable per-parent route,
an admission authorization boundary or a guard on every internal submission-adapter
caller. It does not stop consumers or change direct single-locale admission.

Quartz paths (queue disabled, producer or fan-out disabled, or any non-null tracking name) retain
their lazy lookup/submission ordering. Duplicate aliases still overwrite the map as
before; this patch does not choose the proposed rejection policy. Empty fan-out is
still a no-op. Once submission starts, task/blob/enqueue errors can still leave accepted
children, and repository configuration can change after resolution. This is not a
durable manifest, authorization snapshot, atomic acceptance or restart/idempotency
protocol. Keep parallel queue rollout gated on the proposed recovery protocol below.

The parent now also isolates nonfatal fallback-log failures after scheduling or
duration metrics fail. Previously a throwing warning appender could reject an
already accepted child before recording its mapping, or replace the original
uncertain-submission exception. Registry-collision/appender regressions cover
both the queue route and default-off Quartz route: successful children remain
mapped and partial failures preserve the original exception without another
backend attempt. Direct JVM-fatal errors still escape, including ThreadDeath
subclasses, from either metrics or logging. Wrapped fatal graphs and arbitrary
legacy instrumentation are not certified by this boundary. This preserves local
observations only; it does not persist a manifest or recover an unknown outcome.

The [parent JDBC fault characterization](../../webapp/src/test/java/com/box/l10n/mojito/queue/AssetLocalizeAsyncJobParentAdmissionIntegrationTest.java)
explicitly opts into fan-out and runs the real Quartz parent wrapper, task services
and blob storage. For three requested locales, losing the second queue commit
acknowledgement leaves two pending child tasks, two queued rows and no persisted
parent output map. A real rollback before the same exception leaves one queued
row plus an orphan second task/input. In both cases the parent is finished with an
error and the third slot is never submitted. Re-executing the same parent/task/input
with a fresh job instance creates three new children: five child tasks and five or
four queued rows respectively. Its new output map omits the previously accepted
IDs, while its old error persists. The acknowledged control produces three child
mappings without prematurely marking pending children complete.

These are current unsafe-behavior assertions, not passing recovery guarantees;
replace them with stable-slot/idempotent-resume assertions when the v1 protocol is
wired. They use separate HSQL application and queue databases with an actual queue
JDBC commit/rollback fault, not atomic same-database admission, network failure,
worker execution or a real Quartz scheduler restart. Keep old parent binaries out
of a direct-only canary: they ignore the new flag. Do not retry or migrate a partial
parent by flipping it; old accepted children and unresolved outcomes need reconciliation.

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
   Use fresh queue-owned keys from the first upload. The database adapter now clears
   expiry on explicit PERMANENT overwrites, and its ordinary cleaner rechecks expiry
   when deleting selected IDs; neither supplies admission ownership, immutability or
   a pin against other cleanup paths or older cleaner binaries.
   Verify provider lifecycle exclusions and record backend ownership; fallback-copy
   cleanup must not resurrect retired input. See the migration plan's lifecycle constraints.
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
The orchestration entry guard must run before either template suspends a caller
transaction, and before reservation, blob access or task/queue writes. Reject an
active caller transaction rather than silently suspending it across upload or
recovery work; suspension does not release its locks or physical connection.
Request, reconciliation and parent-resume entry points must obey this rule without
relying on AspectJ or self-invocation advice. The guard itself must not commit or
roll back the caller transaction. It is proposed behavior, not an implemented
admission API.

This restriction belongs to multi-step admission orchestration, not the generic
public queue enqueue API. The existing public enqueue still commits independently
of an outer business transaction, as its JPA contract test verifies. Conversely,
the package-local enlisted primitive below requires TX A to already be active.
Do not generalize either contract into an ambient-transaction guard on the other.

A non-advised EntityManager.persist/flush helper copies task name/message/parent/count/timeout
and explicitly persists actor/audit timestamps. Existing `createPollableTask` starts another
`REQUIRES_NEW` transaction: do not call it inside TX A or change it for other callers.
The package-local `JdbcAsyncJobStore.enqueueNowInCurrentTransaction` primitive now
shares the existing validated INSERT and database-clock path without starting or
committing a transaction. It requires a configured store, an active synchronized,
writable, explicitly READ_COMMITTED transaction and the queue DataSource's bound
connection holder. Its ID remains provisional until caller commit. The future
JDBC admission adapter must propagate insert failures out of TX A, not catch them
and commit a partial task. Existing public enqueue methods remain independent
REQUIRES_NEW operations; do not use them for TX A.
The primitive's thread/resource checks are preconditions, not proof that mixed
transaction managers share ownership. TX A must use the same configured manager;
the primitive does not mark rollback-only if its caller swallows an error.
Assert identical EntityManager/JdbcTemplate DataSource/connection enlistment; fail startup otherwise.
The existing queue store now validates matching JDBC/JPA datasource resources at construction.
[Real JPA contract tests](../../webapp/src/test/java/com/box/l10n/mojito/queue/AsyncJobQueueJpaTransactionIntegrationTest.java)
use the production PollableTask mapping without transaction advice to verify shared connections,
independent public enqueue, insert rollback and both commit-failure outcomes on HSQL/MySQL/PostgreSQL.
These are prerequisites, not complete TX A acceptance/recovery: the internal
enlisted enqueue has no reservation, idempotency, admission-state update, blob
verification or production caller. Do not add a competing
DataSourceTransactionManager or call public enqueue inside TX A.

On 2026-09-12 UTC the existing 35-test fixture passed on native MySQL 8.0.43 with
Hibernate 6.6.49.Final, separately from the fresh HSQL control. This verifies real
enlistment and injected commit uncertainty without implementing admission; it is
not the required MySQL 8.4/PostgreSQL 16 lane, historical schema-upgrade rehearsal
or actual network-failure proof. See the [native JPA evidence and limits](async-job-queue-review.md#native-mysql-jpajdbc-contracts-2026-09-12-utc).

The same JPA/JDBC fixture now injects actual commit-then-throw and
rollback-then-throw outcomes for heartbeat, relative retry and terminal failure.
Rollback preserves the full running row, while committed renewal preserves its
token and committed retry/failure clears ownership. Old tokens cannot mutate
either the released row or its replacement claim, even with the same worker ID.
These six cases verify store transitions and resource cleanup, not durable
admission, runtime callback delivery or safe Mojito task replay. HSQL is the default
lane; MySQL/PostgreSQL variants remain opt-in.

Six gated runtime contracts also hold the real JPA producer transaction open after
task flush and enlisted enqueue while another thread drives the queue consumer. A
completed pre-commit poll dispatches no handler and exposes neither row to independent
reads. After producer resolution, commit and commit-then-throw permit one handler to
read the committed task and commit its own business update; explicit rollback and
rollback-then-throw expose neither row and dispatch nothing. Handler assertions are
surfaced on the test thread rather than being hidden by runtime retry handling.
These use the same configured manager on separate threads without transaction advice.

Two of those contracts withhold the JDBC result after the actual commit or injected
rollback. While the producer future is still incomplete, committed work can reach
DONE and its worker-owned JPA update is independently visible; rolled-back work
remains absent and unclaimable. Releasing the result delivers the injected SQL
exception without changing the observed queue/task state. The hook is consumed
before the database operation and cannot capture subsequent worker commits. This
models a delayed response at the JDBC seam, not actual network failure, HTTP recovery,
request uniqueness or the future ACCEPTED-state transition. MySQL/PostgreSQL variants
are opt-in; see the review ledger for executed lanes. No production admission wiring
is added. Never interpret a pending or failed enqueue call as proof that a worker
has not already run.

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
| Admission orchestration called inside writable/read-only/rollback-only JPA transaction | Reject before reservation, blob access or task/queue mutation; preserve the caller's bound resources and let its owner decide commit/rollback. An outer-rollback public-enqueue control is a different API contract, not successful admission. |
| No ambient caller transaction and no AspectJ; failed TX A; wrong/mismatched manager | Explicit TX A commits task, queue and ACCEPTED together or rolls all three back; wrong configuration fails closed. Enlisted enqueue uses TX A's connection, not a second independent commit. |
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
