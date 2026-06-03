# Async Job Queue: Quartz Migration Plan

## Status And Recommendation

Design review dated 2026-09-08. This document proposes migration policy; it does not
implement a new job adapter, change a migration, or attest to a deployment.

- **M (inspected master baseline):** `master` / `origin/master` at review start,
  `167bf1e81829f8ccaffcc2ff3401fe174cb0be52`.
  Master has migrations through `V108__User_Preferences.sql`; its V100
  is already `V100__MBlob_Cleanup_Policies.sql`.
- **Q (reviewed queue baseline):** `wip/queue-store-refined`,
  `38dd1e06bb1195b09da171891f3fb656fc961f3f`.
  Implementation and line references marked Q describe this commit, not evolving
  uncommitted edits. The existing `006-jdbc-localization-queue-mvp.md` was read as
  background, but current master call sites were independently inspected.
- **Current review state:** corrective patches are in the queue branch rebased
  onto M, not merged into master; acceptance
  evidence and outstanding migration/build work are tracked in the
  [review ledger](async-job-queue-review.md). Baseline support, corrective changes,
  and proposed rollout gates are distinct; none imply production deployment.

Source links are repository-relative. M/Q labels and line numbers identify the
cited baseline; when source has changed, inspect the named commit rather than
assuming the latest file still has identical line numbers. Some M-only source
files are absent from Q and require a master-based checkout to follow the relative
link; the M commit remains the authoritative source until integration is refreshed.

**Recommendation:** retain Quartz scheduling/orchestration and migrate execution
one job family at a time. Harden and canary the existing `assetlocalize` adapter
before adding repository statistics. Statistics is a plausible second family,
not the lowest-risk first candidate in its present form. Do not translate every
Quartz class or `@Async` annotation into a durable job.

Use JDBC for the queue's claim, lease, retry, and terminal-transition hot path:
explicit SQL makes predicates, lock acquisition, transaction boundaries, and
affected-row checks easy to audit. This is an implementation choice, **not** a
claim that Hibernate/JPA cannot implement correct queues. Keep existing ORM
domain services; never wrap expensive job execution in a queue-store transaction.

## Implementation Boundary And Fix Ledger

### Implemented At Q

The queue store/runtime supplies queue-scoped claims, leases, worker/token fencing,
bounded attempts including lease reclaims and explicit requeues, terminal
`done`/`failed`, operator replay, status/latency metrics, and optional terminal-row
retention. JDBC transitions use explicit `REQUIRES_NEW`, `READ_COMMITTED`
transactions ([Q: JdbcAsyncJobStore:110](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L110)).
These mechanisms provide retryable execution, not exactly-once business effects.

Only `assetlocalize` is wired to a business handler. Both producer branches choose
exactly one route; a configured queue with a missing submission bean fails rather
than silently falling back to Quartz. The single-locale endpoint and the
multi-locale parent's children use the queue when both enable flags are true.
The multi-locale **parent itself remains Quartz**. See
[Q: AssetWS:266](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L266)
and [Q: GenerateMultiLocalizedAssetJob:105](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateMultiLocalizedAssetJob.java#L105).

Submission independently creates a PollableTask, writes its input blob, and
enqueues `{pollableTaskId}`. Failure handling attempts to finish the task with an
error; it is compensation, not atomic admission
([Q: AssetLocalizeAsyncJobSubmissionService:55](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobSubmissionService.java#L55)).
The worker invokes shared `LocalizedAssetGenerationService`, stores output,
transitions the queue row, then finalizes PollableTask through a callback.
Terminal-task repair exists but is not a general submission reconciler.

### Current Fixes, Separate From Rollout Work

These are the **implemented-after-current-fixes lane**, not proposals to defer
until later migrations. Corrective patches are integrated in the queue branch.
The resulting source baseline and non-skipped regression results are in the
[review ledger](async-job-queue-review.md).
Unverified patches do not satisfy the rollout gates.

| Finding at Q | Required result of the current fix | Required evidence before calling it fixed |
| --- | --- | --- |
| Production executor handoff can reject despite runtime capacity accounting, consuming attempts without running business work. The zero-buffer executor is in [Q: Coordinator:165](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueCoordinator.java#L165); submission/rejection handling is in [Q: Runtime:524](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueRuntime.java#L524). Reproduction was reported by the integration review. | Bounded handoff buffer coordinated with runtime capacity, not an unbounded backlog or disabled retry accounting. | Exercise the real configured executor, not only a test executor: repeated completion/submission races cause zero spurious rejects, zero false terminal failures, and no excess active/reserved capacity. |
| Retention selects terminal IDs but its outer delete does not repeat status/age conditions; failed replay changes the same row to queued. [Q: JdbcAsyncJobStore:763](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L763) and [Q: deletion:804](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L804). | Lock/revalidate candidates and make deletion conditional on terminal eligibility at deletion time. | Two real DB sessions interleave failed replay and retention. A replay that wins is never deleted; a deletion that wins makes replay explicitly not found. Queued/running rows remain untouched. |
| `databaseNow()` is read before a potentially blocking transition update. A lease can expire while the update waits, yet the saved comparison time is still valid. [Q: heartbeat:353](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L353), [Q: markDone:397](../../webapp/src/main/java/com/box/l10n/mojito/queue/JdbcAsyncJobStore.java#L397). | Validate ownership and expiry with fresh time after acquiring the necessary row lock; anchor renewed leases/delays to the intended transition instant. Audit all equivalent mutations. | Block heartbeat, done, retry, and permanent-failure transitions past lease expiry, then release the lock. Expired ownership cannot succeed or be resurrected. Test clock semantics on each supported database, not just JVM clock skew. |
| Handler writes canonical `pollable_task/<id>/output` before fenced `DONE`; a stale worker can overwrite the winner even if its queue finalization fails. [Q: AssetLocalizeAsyncJobHandler:55](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobHandler.java#L55). | The current review patch introduces attempt-private output, a durable winning-output reference in terminal state, and publication only after successful terminal transition. Repair publishes that same winning output before finishing the task. | Pause A, expire/reclaim to B, let B finish, resume A with different bytes: public output stays B's. Crash after `DONE` but before publication/finish, then repair without running generation again. |
| Spring shuts down the shared scheduler before draining buffered jobs, so scheduling a heartbeat can fail a last-attempt job before generation starts. | A queue-owned heartbeat scheduler remains live through executor drain and is closed on startup/shutdown failure. It is not a global scheduler bean. | Close a real Spring context with a buffered last-attempt job: it starts, renews, and finishes while the shared scheduler is shut down. Verify resource cleanup and bounded stop. |
| PostgreSQL listener stop synchronously closes JDBC, which can block beyond the intended stop deadline. | Only the listener thread performs JDBC cleanup; stop has a bounded join and prevents duplicate restarts while cleanup is pending. | Block close and prove bounded stop, accurate lifecycle metrics, and no second listener until the first exits. |
| Returning a pooled PostgreSQL connection leaves its `LISTEN` session subscription behind. | Explicit `UNLISTEN` before pool return; abort the session on cleanup failure. | Reuse the same real PostgreSQL backend through a one-connection Hikari pool and assert empty listening channels for both auto-commit modes and restart. |

Independent restart-applied producer/consumer controls are also implemented as
described below. They permit rollback/drain, not persistent per-request routing.

**Still open:** atomic admission or outbox delivery; retry-safe pull-run lineage; stats
serialization/coalescing; payload lifetime; domain-aware replay; finite release
acceptance. Attempt-private output protects the output destination, not every
mutation inside `TMService.generateLocalized`.

The Q default store is **in-memory** if `store` is omitted
([Q: AsyncJobQueueConfiguration:14](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueConfiguration.java#L14)).
Enabling the feature alone is not enabling durability. Require explicit `store=jdbc`
and the appropriate dialect on every producer and consumer. Q's V100 queue DDL
cannot be blindly combined with M's different V100. The reviewed source uses
V109 for both dialects, with a version-collision test. This rename is for the
unapplied branch; an environment that applied an older queue migration needs an
explicit history/schema reconciliation, not a blind rename or checksum repair.

## Current Job Inventory And Routing

This is a source inventory, not proof that every configurable job is enabled in
production. References in this section are M unless explicitly Q. `inline` means
JSON in Quartz trigger data; `blob` means PollableTask input storage. Both can have
relational PollableTask metadata. Proposed queue names below are logical workload
boundaries, not new configured queues or handlers already present at Q.

### Dynamic Quartz/Pollable Execution

| Job family and exact producer | Current consumer and payload/behavior | Scoped future routing |
| --- | --- | --- |
| Single localized asset: [AssetWS:263](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L263). | [GenerateLocalizedAssetJob:41](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateLocalizedAssetJob.java#L41), blob request/result. Q has shared generation and `AssetLocalizeAsyncJobHandler`. | **Phase 1:** existing `assetlocalize` route; ordinary synchronous localized endpoint stays synchronous. |
| Parallel localized asset: [AssetWS:303](../../webapp/src/main/java/com/box/l10n/mojito/rest/asset/AssetWS.java#L303). | [GenerateMultiLocalizedAssetJob:40](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateMultiLocalizedAssetJob.java#L40) creates locale children and returns output-tag-to-task-ID map; blob payload. | Parent stays Quartz. Only its [child producer:64](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/GenerateMultiLocalizedAssetJob.java#L64) routes to `assetlocalize`. Parent recovery/fan-out deduplication is a separate gate. |
| Repository stats: [RepositoryStatisticsJobScheduler:20](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticsJobScheduler.java#L20). | [RepositoryStatisticsJob:19](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticsJob.java#L19), inline repository ID, unique key per repository, `DisallowConcurrentExecution`. Calls full `updateStatistics`. | **Phase 2:** central producer routes new execution to `repository-statistics`; keep cron and all legacy consumers until drained. Requires durable coalescing and one active generation per repository. |
| Source extraction: [AssetExtractionService:1651](../../webapp/src/main/java/com/box/l10n/mojito/service/assetExtraction/AssetExtractionService.java#L1651). | [ProcessAssetJob:16](../../webapp/src/main/java/com/box/l10n/mojito/service/assetExtraction/ProcessAssetJob.java#L16), inline input, parent ID, five expected subtasks; mutates extraction/TM/branch state. | Keep Quartz in Phases 1-2. Later `asset-extract` needs extraction identity, commit/outbox ordering, and idempotent progress checkpoints. |
| Localized import: [TMService:1653](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1653). | [ImportLocalizedAssetJob:18](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/ImportLocalizedAssetJob.java#L18), blob input, TM writes. | Later `translation-import`; producer attaches immutable import-run ID and actor, consumer deduplicates item effects. Not an assetlocalize job. |
| Bulk text import: [TextUnitBatchImporterService:192](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/importer/TextUnitBatchImporterService.java#L192). Virtual-unit update: [VirtualAssetService:316](../../webapp/src/main/java/com/box/l10n/mojito/service/asset/VirtualAssetService.java#L316). | [ImportTextUnitJob:32](../../webapp/src/main/java/com/box/l10n/mojito/service/asset/ImportTextUnitJob.java#L32) and [VirtualTextUnitBatchUpdateJob:25](../../webapp/src/main/java/com/box/l10n/mojito/service/asset/VirtualTextUnitBatchUpdateJob.java#L25). Convenience scheduler currently defaults to inline input. | Later separate `translation-import` and `virtual-asset-update` handlers; preserve bulk lineage and per-item validation/results. Do not assume all import payloads already use blobs. |
| Branch deletion: [BranchService:83](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/BranchService.java#L83). Branch asset deletion: [AssetService:421](../../webapp/src/main/java/com/box/l10n/mojito/service/asset/AssetService.java#L421). | `service/branch/DeleteBranchJob` and [DeleteAssetsOfBranchJob:21](../../webapp/src/main/java/com/box/l10n/mojito/service/asset/DeleteAssetsOfBranchJob.java#L21), inline IDs, destructive domain operations. | Keep Quartz; later `branch-delete`/`branch-asset-delete` require tombstone/version checks, chunk resume, and explicit repeated-delete semantics. |
| Branch notifications: [BranchStatisticService:458](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/BranchStatisticService.java#L458). Missing screenshots: [BranchNotificationService:181](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/notification/BranchNotificationService.java#L181). | [BranchNotificationJob:23](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/notification/job/BranchNotificationJob.java#L23) and `BranchNotificationMissingScreenshotsJob`; inline IDs. Keys are branch ID and notifier-plus-branch ID; screenshot work is delayed. | Keep Quartz even when stats moves. Later `branch-notify` needs a durable notification-event key and delivery reconciliation, not blind retry of sends. |
| Machine translation: [MachineTranslationWS:53](../../webapp/src/main/java/com/box/l10n/mojito/rest/machinetranslation/MachineTranslationWS.java#L53). | [BatchMachineTranslationJob:24](../../webapp/src/main/java/com/box/l10n/mojito/service/machinetranslation/BatchMachineTranslationJob.java#L24), blob request/results; provider calls. | Later `machine-translation`, after request dedupe, cost/rate limits, and provider retry semantics are explicit. |
| AI Translate: [AiTranslateService:257](../../webapp/src/main/java/com/box/l10n/mojito/service/oaitranslate/AiTranslateService.java#L257). AI Review: [AiReviewService:261](../../webapp/src/main/java/com/box/l10n/mojito/service/oaireview/AiReviewService.java#L261). | [AiTranslateJob:24](../../webapp/src/main/java/com/box/l10n/mojito/service/oaitranslate/AiTranslateJob.java#L24), [AiReviewJob:18](../../webapp/src/main/java/com/box/l10n/mojito/service/oaireview/AiReviewJob.java#L18), blob inputs; provider requests and persisted run/domain effects. | Keep Quartz. Later isolate `ai-translate` and `ai-review`, retaining run/attempt identities and authorization context. |
| AI provider-batch polling/import: [AiTranslateService:273](../../webapp/src/main/java/com/box/l10n/mojito/service/oaitranslate/AiTranslateService.java#L273), [AiReviewService:282](../../webapp/src/main/java/com/box/l10n/mojito/service/oaireview/AiReviewService.java#L282). | `AiTranslateBatchesImportJob` and `AiReviewBatchesImportJob`; blob payload includes processed IDs/attempt state, delayed next child, parent task. Translate can also create repair batches. | Retain current durable orchestration initially. Later use distinct batch-check handlers with persisted provider IDs/checkpoints. A provider still running is a workflow wait, not an error that should exhaust the queue's default five attempts. Preserve `nextJob` and task-tree contracts or version the API. |
| Review-project creation: [ReviewProjectService:227](../../webapp/src/main/java/com/box/l10n/mojito/service/review/ReviewProjectService.java#L227), [term-candidate creation:329](../../webapp/src/main/java/com/box/l10n/mojito/service/review/ReviewProjectService.java#L329). | `ReviewProjectCreateRequestJob`, `ReviewProjectCreateGlossaryTermCandidateRequestJob`; blob input, request/actor identity and project creation effects. | Later `review-project-create` handlers; require request-level uniqueness and no duplicate project/assignment/notification creation. |
| Term-index refresh: [TermIndexRefreshService:150](../../webapp/src/main/java/com/box/l10n/mojito/service/glossary/TermIndexRefreshService.java#L150). Triage/candidates: [GlossaryTermIndexCurationService:491](../../webapp/src/main/java/com/box/l10n/mojito/service/glossary/GlossaryTermIndexCurationService.java#L491), [candidate producer:524](../../webapp/src/main/java/com/box/l10n/mojito/service/glossary/GlossaryTermIndexCurationService.java#L524). | `TermIndexRefreshJob`, `TermIndexExtractedTermTriageJob`, `TermIndexCandidateGenerationJob`; inline commands, explicit Quartz recovery. | Later separate refresh/curation routes with run IDs and scoped checkpoints; do not restart a partially applied curation command without effect dedupe. |
| Search reindex: [SearchIndexReindexJobService:50](../../webapp/src/main/java/com/box/l10n/mojito/service/searchindex/SearchIndexReindexJobService.java#L50). | [SearchIndexReindexJob:20](../../webapp/src/main/java/com/box/l10n/mojito/service/searchindex/SearchIndexReindexJob.java#L20); inline request, index-name unique key, recovery enabled, task message carries progress. | Later `search-reindex` keyed by target index; protect index generation/alias publication from stale attempts. Not a read-only shadow candidate. |

### Quartz Cron And Orchestration Are Not The Queue

Keep the following scheduling/control responsibilities on Quartz. A future
execution adapter changes the invoked leaf work, not cron parsing, time zones,
misfire policy, persistent schedules, or trigger healing.

| Representative current group | Exact call path | Migration boundary |
| --- | --- | --- |
| Repository-statistics sweep | [RepositoryStatisticsCronJob:52](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticsCronJob.java#L52) enumerates nondeleted/nonhidden repositories and invokes `RepositoryStatisticsJobScheduler.schedule`. | Cron remains the periodic reconciliation producer even after execution moves. It is configurable, not proof an active deployment has a sweep. |
| AI/review/JSON automation | [AiTranslateAutomationCronJob:19](../../webapp/src/main/java/com/box/l10n/mojito/service/oaitranslate/AiTranslateAutomationCronJob.java#L19), [ReviewAutomationCronJob:24](../../webapp/src/main/java/com/box/l10n/mojito/service/review/ReviewAutomationCronJob.java#L24), [JsonConfigLocalizationCronJob:24](../../webapp/src/main/java/com/box/l10n/mojito/service/jsonconfiglocalization/JsonConfigLocalizationCronJob.java#L24) call their automation services. | Keep configuration reconciliation and schedule identity. Before moving expensive execution, persist one occurrence/run identity and enqueue that run only once. JSON provider synchronization is not assumed read-only. |
| Trigger health and integrity/SLA checks | [ReviewAutomationTriggerHealthCheckJob:24](../../webapp/src/main/java/com/box/l10n/mojito/service/review/ReviewAutomationTriggerHealthCheckJob.java#L24), [ReviewProjectDecisionIntegrityCanaryJob:15](../../webapp/src/main/java/com/box/l10n/mojito/service/review/ReviewProjectDecisionIntegrityCanaryJob.java#L15), [SlaCheckerCronJob:42](../../webapp/src/main/java/com/box/l10n/mojito/service/sla/SlaCheckerCronJob.java#L42). | Retain Quartz. Trigger healing operates on Quartz itself; alerts/incidents are effects requiring dedupe if their execution ever moves. |
| Cleanup/maintenance | [PollableTaskCleanupJob:45](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskCleanupJob.java#L45), [AssetExtractionCleanupJob:38](../../webapp/src/main/java/com/box/l10n/mojito/service/assetExtraction/AssetExtractionCleanupJob.java#L38), [PushPullRunCleanupJob:45](../../webapp/src/main/java/com/box/l10n/mojito/service/delta/PushPullRunCleanupJob.java#L45), [DatabaseBlobPolicyCleanupJob:14](../../webapp/src/main/java/com/box/l10n/mojito/service/blobstorage/database/DatabaseBlobPolicyCleanupJob.java#L14). | Keep bounded maintenance jobs. Pollable cleanup marks timed-out tasks; it is not queue cancellation, queue retention, or historical archival. |
| Storage/cache and one-off updaters | [DatabaseBlobStorageCleanupJob:21](../../webapp/src/main/java/com/box/l10n/mojito/service/blobstorage/database/DatabaseBlobStorageCleanupJob.java#L21), [DatabaseCacheEvictionJob:37](../../webapp/src/main/java/com/box/l10n/mojito/service/cache/DatabaseCacheEvictionJob.java#L37), [ImageMigrationJob:26](../../webapp/src/main/java/com/box/l10n/mojito/service/image/ImageMigrationJob.java#L26), [StringAuthoringCleanupJob:23](../../webapp/src/main/java/com/box/l10n/mojito/service/stringauthoring/StringAuthoringCleanupJob.java#L23); legacy `PluralFormUpdaterJob`, `TUCVAddAssetIdUpdaterJob`, `AssetExtractionByBranchRemoverJob`, `RepositoryManualScreenshotRunJob` also exist. | Inventory actual registrations/enabled properties before treating legacy classes as live work. Do not replace one-off mutation jobs merely to remove a Quartz dependency. |

`QuartzConfig.startSchedulers` honors `l10n.org.quartz.scheduler.enabled`, while
startup cleanup compares registered beans with DEFAULT-group jobs/triggers
([M: QuartzConfig:47](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzConfig.java#L47)).
Dynamic pollable work uses the `DYNAMIC` group. Removing beans or setting Quartz
disabled is not a safe per-family migration/drain mechanism. Queue runtime startup
is independent of the Quartz enable property; an API process with Quartz disabled
must not unintentionally become a queue consumer.

### In-Process Async And Pollable Work

| Current call sites | Current durability and consumer | Routing decision |
| --- | --- | --- |
| [RepositoryStatisticService:172](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticService.java#L172), [AsyncBranchStatisticUpdater:42](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/AsyncBranchStatisticUpdater.java#L42), [BranchStatisticService:452](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/BranchStatisticService.java#L452). | `statisticsTaskExecutor` runs locale writes, transactional branch updates, and notification scheduling. Parent methods wait for futures; notification scheduling completion is not delivery completion. These are not individually durable submissions. | Keep bounded internal parallelism under one durable repository job in Phase 2. Do not put waiting parents and their children onto the same saturated executor. A later child-job migration needs explicit aggregate completion rather than serializing JPA entities/maps. |
| [BlobStorageUploadImageAsyncTask:19](../../webapp/src/main/java/com/box/l10n/mojito/service/image/BlobStorageUploadImageAsyncTask.java#L19), [S3UploadImageAsyncTask:24](../../webapp/src/main/java/com/box/l10n/mojito/service/image/S3UploadImageAsyncTask.java#L24). | Default async executor, transient `byte[]`, remote writes. | Later `image-upload` only after durable input staging, deterministic object identity, and upload verification. A queue containing a reference to lost JVM bytes is not durable. |
| [HealthRotation:60](../../webapp/src/main/java/com/box/l10n/mojito/rest/rotation/HealthRotation.java#L60). | Asynchronous hostname lookup, caller waits up to one second. | Keep local. Cross-process durable execution would return the wrong process context. |
| `@Pollable(async=true)`: [DropService:128](../../webapp/src/main/java/com/box/l10n/mojito/service/drop/DropService.java#L128) (also import:216/cancel:415), [LeveragingService:128](../../webapp/src/main/java/com/box/l10n/mojito/service/leveraging/LeveragingService.java#L128), [RepositoryMachineTranslationService:54](../../webapp/src/main/java/com/box/l10n/mojito/service/machinetranslation/RepositoryMachineTranslationService.java#L54), [TeamService:791](../../webapp/src/main/java/com/box/l10n/mojito/service/team/TeamService.java#L791), [GitBlameService:196](../../webapp/src/main/java/com/box/l10n/mojito/service/gitblame/GitBlameService.java#L196). Additional instances include `GlossaryTermService:783`, `TemporaryBulkTranslationAcceptService:90/128`, `TMTextUnitStatisticService:68`, `TMService:1893`. | [PollableAspect:137](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableAspect.java#L137) submits a closure to `pollableTaskExecutor`. Persisted progress does **not** persist the executable closure/arguments. The executor propagates security context. | Later named commands per workflow, with durable payloads, actor identity, side-effect inventory, and task-tree compatibility. No generic reflection adapter for arbitrary `ProceedingJoinPoint`. Keep synchronous `@Pollable` instrumentation synchronous. |

M uses `@EnableAsync(mode=AdviceMode.ASPECTJ)` in
[AsyncConfig:17](../../webapp/src/main/java/com/box/l10n/mojito/AsyncConfig.java#L17).
Do not incorrectly dismiss same-class `@Async` calls using proxy-only rules:
AspectJ supports woven interception, unlike default proxy mode.
[Spring scheduling documentation](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
describes this distinction. Validate the actual packaged/weaved build. The
statistics executor defaults to effectively unbounded queue capacity and supports
discard policies ([M: StatisticsTaskExecutorConfig:19](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/StatisticsTaskExecutorConfig.java#L19));
a bounded durable parent pool alone does not bound this internal fan-out.

## Why Statistics Is Not The First Candidate

All current repository-statistics producers must be included, not only the cron:

1. `RepositoryStatisticsCronJob.execute` invokes the central scheduler.
2. [EntityCrudEventListener:118](../../webapp/src/main/java/com/box/l10n/mojito/service/eventlistener/EntityCrudEventListener.java#L118)
   forwards post-commit entity changes to
   [RepositoryStatisticsUpdatedReactor:47](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticsUpdatedReactor.java#L47).
   This buffers/deduplicates IDs for approximately one second **in process** before
   invoking the scheduler. It is not a durable outbox; a process exit can lose
   notifications between domain commit and scheduling.
3. [AssetExtractionService:526](../../webapp/src/main/java/com/box/l10n/mojito/service/assetExtraction/AssetExtractionService.java#L526)
   explicitly schedules when an extraction has no new text units.
4. [VirtualTextUnitBatchUpdaterService:180](../../webapp/src/main/java/com/box/l10n/mojito/service/asset/VirtualTextUnitBatchUpdaterService.java#L180)
   schedules after a do-not-translate change. Its transaction timing must be
   preserved, not inferred from the post-commit listener path.

The job is rebuildable but not pure. `updateStatistics` persists repository and
locale rows, refreshes text-unit DTO caches using `UpdateType.ALWAYS`, and calls
branch statistics; branch statistics persist counts and schedule notifications.
See [M: RepositoryStatisticService:100](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticService.java#L100),
[cache refresh:241](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticService.java#L241),
[locale refresh:344](../../webapp/src/main/java/com/box/l10n/mojito/service/repository/statistics/RepositoryStatisticService.java#L344),
and [M: BranchStatisticService:106](../../webapp/src/main/java/com/box/l10n/mojito/service/branch/BranchStatisticService.java#L106).
Calling this entire service twice in a shadow experiment can duplicate scheduling,
refresh shared caches, interleave saves, and expose inconsistent generations.
Notification services have their own state checks; those are not evidence that
arbitrary concurrent/retried invocations are effect-once.

Quartz currently gives this job a repository-specific JobKey and disallows
concurrency for that key. The annotation's scope is JobKey, not all instances of
the class ([Quartz API](https://www.quartz-scheduler.org/api/2.3.0/org/quartz/DisallowConcurrentExecution.html)).
Q has no equivalent repository business key; a mutex per queue row or
`max-concurrency=1` per JVM does not serialize different rows across workers.
Quartz's current reschedule/optional skipped-PollableTask behavior is visible in
[M: QuartzPollableTaskScheduler:146](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableTaskScheduler.java#L146)
and [unique-ID rescheduling:217](../../webapp/src/main/java/com/box/l10n/mojito/quartz/QuartzPollableTaskScheduler.java#L217).
Define the replacement's coalescing semantics explicitly instead of assuming the
queue already reproduces them.

**Proposed statistics semantics:** one active computation per repository, a
durable monotonically increasing requested generation, and a completed generation.
A producer increments/marks dirty, coalescing pending requests without erasing a
request arriving while work runs. A worker computes against an identified input
snapshot/generation and conditionally publishes that generation; a newer request
causes another pass. Stale attempts cannot overwrite published counts or emit
notifications. Use stable `(repository, generation, notification kind)` event
identity for downstream delivery. Do not store a fixed repository-only dedupe key
forever and thereby suppress future legitimate updates.

For comparisons, isolate compute from persistence/cache population/notification.
Start with base/locale count calculations over a bounded immutable captured input.
Do not call the existing `computeBaseStatistics` and assume its name guarantees no
writes. Before making this smaller path authoritative, specify how all current
branch/locale outcomes remain updated; silently dropping them is not a migration.

Asset localization already has two working producer seams and a shared leaf
service, so its incremental migration surface is smaller. It is also **not fully
pure**: `pullRunName` causes deletion/replacement of pull-run variant associations
([Q: TMService:1083](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/TMService.java#L1083),
[Q: PullRunAssetService:62](../../webapp/src/main/java/com/box/l10n/mojito/service/pullrun/PullRunAssetService.java#L62)).
Delete and insert are separate transactions. Private-output publication alone
does not fix this lineage race. The first canary excludes pull-run tracking,
using a proposed eligibility check; if that restriction is impossible for the
chosen callers, lineage fencing is a prerequisite, not a waiver. Audit generation
and caches for additional effects before declaring an input eligible.

## Proposed Migration Protocol

### Producer/Consumer Controls And Admission

The reviewed implementation adds independent **restart-applied** controls (these
were missing at Q). Both umbrella flags remain default `false`:

| Setting below `l10n.org.async-job-queue.` | Default | Meaning when umbrella flags are enabled |
| --- | --- | --- |
| `asset-localize.producer-enabled` | `true` | False routes new API and multi-locale child submissions through Quartz. Direct queue-adapter submission fails before writes. Handler/output/repair beans remain registered. |
| `queues.assetlocalize.consumer-enabled` | `true` | False prevents this node from allocating a runtime or claiming work; JDBC submissions to other consumers remain allowed. It does not disable status collection, retention, or repair. |

Consumer-disabled settings require `store=jdbc`; a process-local in-memory store
cannot be drained by another node. A PostgreSQL node with no enabled registered
consumers skips `LISTEN` and reserves no listener connection, but may still publish
notifications. These controls do not implement a live pause or request/repository
policy. Stable canary eligibility, route persistence, and shadow observation still
need implementation. The following remain conceptual rollout states:

| State | New production work | Legacy Quartz consumer | Queue consumer |
| --- | --- | --- | --- |
| Legacy | Quartz only | On | Off, or on only to drain existing queue work |
| Observe | Quartz only; separate non-executing shadow record | On | Observer only, with no business handler capability |
| Canary | Stable allowlist/eligibility decision chooses exactly one backend | On for old/excluded work | On for admitted eligible work |
| Queue | Queue for this family | On until its old work drains | On |
| Rollback/drain | Quartz for new work, or temporarily reject new admissions | On | On until queued, delayed, running, and repair obligations drain |

Persist the selected route and logical request identity at admission, so retries
do not reroute the same request. Pin a multi-locale parent's route policy for its
children; each child needs a stable key such as `(parent request, locale, output
tag, generation-affecting options hash)`. A retried parent reuses the child-task
manifest, rather than creating another complete fan-out. Keep existing task IDs
and result mapping. Cross-request reuse is not allowed merely because asset and
locale match: content, filters, status, inheritance, and translation state matter.

The decision is made before enqueue, not by catching enqueue errors. An ambiguous
enqueue commit must be resolved by request identity; **never enqueue to Quartz as
a fallback after a possibly successful queue write**. During a rolling deployment,
old API and worker/parent binaries also produce work. All need compatible routing
or traffic isolation; flipping only API flags does not change old parents.

Q's shared feature flags control handler registration and repair as well as
submission. Turning them off everywhere still strands queued work and removes its
repair path. Do not use `disablescheduling` or the Quartz enable property as the
queue rollback control. On binaries with the new controls, the drain procedure is:

1. Verify Quartz can accept new work, and retain queue workers with both umbrella
   flags enabled and `consumer-enabled=true`.
2. Restart every API and multi-locale parent worker with
   `asset-localize.producer-enabled=false`. Retire or isolate old binaries that
   ignore this property. Requests already accepted by either backend stay there.
3. Verify new successful routing counters show Quartz and no new queue admissions.
   Drain all queued jobs, including future retries, running leases, and terminal
   output/PollableTask repair obligations. Zero *ready* backlog alone is not drain.
4. Only then stop the queue consumers or disable the umbrella feature flags.
   Keep schema/data needed for unresolved failures and the chosen retention policy.

Disabling consumers first is not rollback: producers can continue to grow the
durable backlog. Mixed-version deployment and admission uncertainty still require
the recovery work below; these switches do not establish zero-downtime cutover.

### Transaction Atomicity And Recovery

The [durable admission implementation design](async-job-queue-admission.md) specifies
scoped request identity, canonical input, explicit JPA/JDBC transaction ownership,
partial parent manifests, and the failure-injection matrix. It is a proposal, not
an implemented admission guarantee. The reviewed code now isolates nonfatal
post-success submission metrics/wakeups so they do not falsely reject accepted work;
actual database commit uncertainty still needs the protocol below. The legacy
Quartz scheduler's own instrumentation remains outside that narrow correction.

Current Q order is `PollableTask commit -> blob write -> queue commit -> response`.
A crash between steps leaves an orphan task/input; an uncertain commit plus client
retry can create two accepted jobs. `REQUIRES_NEW` does not join a caller's domain
transaction; it commits independently
([Spring propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)).
Wrapping today's submission service with `@Transactional` therefore does not make
these operations atomic.

**Proposed direct asset admission:** validate and assign a stable request ID;
stage immutable input under that ID with checksum/length; then use one short
database transaction to persist the task/admission mapping, input reference, and
queue intent. This needs a small transaction-participating admission/insert seam,
not a change making every existing PollableTask progress update join heavy work.
Return the existing logical task on an idempotent resubmission; reject reuse of a
request ID with a different input hash. Only committed, ready admissions may run.
Do not hold row locks across blob upload or localization. Blob writes cannot be
atomically committed with an ordinary relational transaction: unreferenced staged
objects are harmless orphans to garbage-collect after an admission grace period.

**Proposed domain-triggered admission:** persist a dirty-generation marker/outbox
record in the same transaction as the domain change. A dispatcher atomically
inserts the queue intent and records dispatch in the same database transaction,
or uses a database-enforced unique logical job identity to tolerate redispatch.
Today's independently committing `enqueueNow` is not that atomic dispatcher API.
The post-commit Reactor may remain a latency optimization, never the only durable
record. A configurable cron sweep is useful reconciliation but cannot guarantee
prompt delivery of lost events if it is disabled.

| Crash/uncertainty point | Recovery contract |
| --- | --- |
| Staged input but no committed admission | No executable work; reclaim orphan blob only after proving no admission references it. |
| Domain/admission commit before dispatch | Outbox/admission reconciler resumes; request uniqueness prevents duplicate executable intents. |
| Queue commit before response | Lookup same request ID, return same task; do not send to the other backend. |
| Claimed but no business work | Lease reclaim with bounded budget, preserving the logical task. |
| Work done but terminal commit absent/uncertain | Retry may compute again; effect-specific idempotency and attempt-private output prevent stale publication. Resolve terminal state before publishing. |
| Terminal queue state but output/task finalization incomplete | Retry publication/finalization of the recorded winning result, never rerun the business handler. Persist/measure this repair obligation. |
| Partial multi-locale fan-out | Resume durable child manifest; resolve existing child IDs; finish only after the actual expected children reach their defined final states. |

Keep leases and queue transitions short and fenced. `SKIP LOCKED` avoids waiting
on locked rows during claim; it does not provide strict FIFO, business-key
serialization, or a transaction over external effects. It also does not eliminate
all kinds of lock wait. [MySQL locking-read documentation](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
explicitly describes its queue-oriented, inconsistent-view semantics. For the
time-after-lock fix, PostgreSQL `CURRENT_TIMESTAMP` is transaction-start time;
fresh wall-clock comparison needs appropriate database semantics, not merely a
second call to that function
([PostgreSQL time functions](https://www.postgresql.org/docs/current/functions-datetime.html)).

### Safe Shadowing

**Default shadow is metadata observation, not dual execution.** Write a sampled,
bounded record of normalized job type, request ID, selected route, input schema/hash,
admission timestamps, expected task shape, and authoritative result digest. The
observer must have no ability to call generation/import, mutate PollableTask,
publish output, send notifications, create provider batches, or alter pull-run
lineage. Do not place these records in `assetlocalize` where the real handler can
claim them. Observer failure cannot fail or retry production admission.

For computational parity, use a separate pure evaluator on immutable captured
inputs and dependencies, with separate disposable output storage. For localization,
capture the translation/extraction snapshot as well as source/options; comparing
two runs against changing translations is not an equivalence test. For statistics,
capture count inputs without refreshing production caches and compare values,
not timestamps. With no pure boundary available, run fixtures against an isolated
database/storage copy with provider/network effects denied. Never invoke the full
production service and roll back only the database: blob writes, provider calls,
independent transactions, and notifications have already escaped that rollback.

Sampling/observer writes are disabled by default, rate/size limited, use bounded
metric tags, and follow the same access controls as source text. Do not log raw
source/target text or serialized payloads as high-cardinality comparison labels.

### Payload, Retention, And PollableTask Compatibility

Q's envelope only contains `pollableTaskId`. Preserve that reader during drain;
proposed later envelopes add `schemaVersion`, `jobType`, `requestId`, immutable
input reference/hash, business identity/generation, and durable actor/run metadata.
Do not serialize JPA entities, closures, thread-local security context, or secrets.
Reject unsupported versions before effects and quarantine them visibly. Deploy
backward-compatible readers before producers emit newer versions; rollback
binaries must understand every payload still pending or retain compatible workers.

If staged input uses a new physical key, the admission manifest-aware reader must
resolve it through the existing logical PollableTask input API before the job is
made executable. Alternatively materialize the existing task input path before
marking admission ready, with crash recovery for that step. Do not publish a queue
row referencing only a new staging key while the worker and `/input` endpoint still
read only `pollable_task/<id>/input`. Keep legacy-path readers through the drain.

Maintain the existing PollableTask name, parent-child tree, expected-subtask count,
message/error shape, creator attribution, and `PollableFuture` semantics. Keep
`/api/pollableTasks/{id}`, `/input`, and `/output` behavior
([M: PollableTaskWS:38](../../webapp/src/main/java/com/box/l10n/mojito/rest/pollableTask/PollableTaskWS.java#L38)).
An attempt failure remains nonterminal while retry is possible; final failure must
be visible on the same logical task. Parent handler return is not aggregate
completion: [M: PollableTask.isAllFinished:205](../../webapp/src/main/java/com/box/l10n/mojito/entity/PollableTask.java#L205)
also requires expected subtasks and recursive child completion.

Public success must imply the winning output is readable. Queue `DONE`, output
publication, task finish, and aggregate parent finish are distinct observations.
Do not treat `DONE` alone as client completion. A terminal repair must validate
its durable output reference, survive retries, and not silently mark success if
the referenced bytes are absent. Existence of a finished task alone is not proof
that its output/error state agrees with the queue.

Generic failed-row replay at Q resets the queue attempt count, not the associated
PollableTask error/final state. Q's `finishTask` only sets error fields when given
an exception; success does not clear a prior error
([Q: PollableTaskService:88](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskService.java#L88)).
Raw asset replay therefore can leave a queue-successful task visibly failed. This
remains a rollout gate even after the private-output fix.
**Proposed operator replay policy:** use a new linked logical task/request for
user-visible re-execution, or an explicitly tested atomic reopen/revision protocol.
Do not expose generic row replay as if it already repairs task lifecycle. Require
replay authorization, payload availability, audit, and per-family safety checks.

The timeout cleanup service marks tasks with an error without fencing/cancelling
their executing work ([M: PollableTaskCleanupService:32](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskCleanupService.java#L32)).
Set an explicit end-to-end deadline covering queue wait, execution, retries, and
publication. Specify queue-aware timeout/late-result policy; a heartbeat must not
make clients see success after a terminal timeout. A client polling timeout alone
is not cancellation or permission to resubmit to another backend.

Inputs/outputs currently request `Retention.MIN_1_DAY`
([Q: PollableTaskBlobStorage:22](../../webapp/src/main/java/com/box/l10n/mojito/service/pollableTask/PollableTaskBlobStorage.java#L22));
this is not proof of indefinite storage or of a particular backend's actual TTL.
Queue retention defaults, when enabled, are seven days for done and thirty for
failed ([Q: properties:271](../../webapp/src/main/java/com/box/l10n/mojito/queue/AsyncJobQueueProperties.java#L271)).
Retaining a failed queue row for a month is useless for replay if its input expired.
The current private-output patch also writes attempt blobs with `MIN_1_DAY`
([review patch: AssetLocalizeAsyncJobOutputStorage:35](../../webapp/src/main/java/com/box/l10n/mojito/service/tm/AssetLocalizeAsyncJobOutputStorage.java#L35)).
If terminal publication needs repair after those bytes expire, a retained `DONE`
row is insufficient. Prove the input/replay and winning-attempt/repair horizons
against the configured storage policy, not only the seven/thirty-day queue ages.

**Proposed lifecycle:** pin all referenced inputs, attempt outputs, winning outputs,
task metadata, and result-publication obligations while queued/running or awaiting
repair. Start the client-output retention clock after task completion, not enqueue;
retain failed inputs through the permitted replay window. Unreferenced attempt
outputs may be cleaned after a safe grace interval. Purge terminal queue rows only
after publication/task reconciliation and replay/audit windows, and never race a
replay back to queued. Keep retention off for the first canary. Review existing
storage cleanup/prefix routing separately; do not switch to Azure/Redis as part of
this execution migration. Verify old and new payload read compatibility on the
actual selected storage backend before any future routing change.

### Rollback And Drain

**Private-output fence deployment prerequisite:** drain and retire every old
asset worker that can write canonical output before starting fenced execution.
An old worker can resume after lease loss and overwrite a new worker's published
result; adding private outputs to new binaries cannot constrain old code. Pause
admissions, drain legacy work, reconcile outputs/tasks, and verify old processes
are stopped or otherwise unable to write. Lease expiry or ready-depth zero is not
enough. Also inventory legacy Quartz writers and forbid transferring/replaying the
same logical task across backends during this cutover. Producer-only old binaries
may remain only if their payload/admission behavior passes compatibility checks;
old canonical-writing consumers are excluded from the fenced fleet.

1. Change **new admissions** for the family to Quartz or paused. Record cutoff,
   policy revision, and all producer populations, including queued Quartz parents,
   automated producers, API instances, and replay tools. Keep consumers and repair
   readers compatible with previously admitted work.
2. Drain both old Quartz work and queue work. Queue drain includes all queued rows,
   future `available_at` rows, running/unexpired and expired leases, pending outbox
   delivery, child fan-out manifests, and terminal publication/task-repair debt.
   Count failed rows separately: terminal is not business success.
3. Reconcile every admitted task to a readable success, a visible terminal failure,
   or an explicitly retained repair/replay decision. Do not wait only for ready
   depth zero, and do not delete failed rows to manufacture a green drain.
4. Stop queue consumers only after two reconciliations separated by at least one
   full lease duration show no new legacy admissions and no active work. Preserve
   terminal data/readers for the agreed support window. In-flight handler shutdown
   may outlast executor wait; lease expiry is not proof the old process cannot
   still write business effects.
5. Never blindly copy running queue rows to Quartz. Emergency transfer requires
   quiescing/fencing old owners, durable reconciliation of whether effects already
   happened, atomic transfer of logical ownership, and compatible task identity.
   If the handler is unsafe, pause admissions and execution, retain data, and fix
   forward instead of attempting a duplicate execution as rollback.

Quartz cron definitions and parent handlers remain throughout. Removing the queue
feature does not justify deleting Quartz tables, old job classes/DTO readers, or
shared PollableTask storage. Schema rollback is not required for producer rollback.

## Phases And Finite Acceptance Gates

These are proposed release criteria, not claims about existing test coverage.
Each phase has an explicit stop condition; elapsed time without enough samples is
**inconclusive**, not a pass. The release owner records revision, database version,
configuration, counts, durations, and artifacts for each gate.

| Phase | Scope | Exit or stop condition |
| --- | --- | --- |
| 0: integrate and prove | Four current fixes, explicit JDBC deployment config, compatible migrations/readers, independent routing, admission/replay/retention protocol. No production route change. | Required focused suites and real-DB races below pass with zero required tests skipped. Old canonical-output writers are drained/retired before fence cutover. Record all remaining failures before canary. |
| 1a: observe | Metadata-only shadow on existing Quartz asset traffic; fixture parity in isolated storage. | 1,000 sampled admissions or 48 hours, whichever comes first. Pass only with 1,000 accounted-for samples and zero observer-induced business effects. Under-sampling stops for review. |
| 1b: asset canary | One small explicit repository allowlist, approved generation shapes, no pull-run lineage until fenced; single-locale first, then bounded multi-locale parents. Both execution backends remain available. | At least 1,000 successful eligible jobs and 24 hours of observation, with a 72-hour cap. Zero lost admissions, stale publications, unexpected duplicate effects, or unresolved task/queue inconsistencies. Deadline/cap breach stops expansion. |
| 1c: expand asset | Advance stable eligibility from 1% to 10% to 100%, not random rerouting on retry. | Each step repeats the 1b gate and a rollback/drain rehearsal before the next step. Disable retention until its own concurrency/repair gates pass. |
| 2: statistics | Pure snapshot evaluator first; then central producer, keyed durable coalescing, generation-fenced publication, and separately deduplicated notification intents. Keep Quartz cron and local child compute. | 100 repositories on fixtures with exact base/locale/branch count parity; 1,000 invalidations including 100 arrivals during active runs; zero missed dirty generations, stale saves, or duplicate notification effects. Then apply the same bounded canary window as 1b. |
| 3: additional families | One named adapter at a time from the inventory, with its own side-effect/checkpoint contract. | Per-family approved tests/rollout record. No implicit promotion of imports, deletion, provider workflows, or all `@Pollable` methods when assetlocalize passes. |

For 1b/1c, compare matched Quartz/queue cohorts at the same offered load and input
size. Proposed thresholds: admission p95 no more than 20% above baseline and below
the ingress timeout budget; end-to-end p95 no more than 20% above baseline;
unexpected terminal-failure rate no more than baseline plus 0.1 percentage point;
DB CPU and connection occupancy each no more than 20% above baseline. Any
correctness failure is a hard stop regardless of averages. Use bounded job-family
metrics plus sampled request/task/attempt correlation in access-controlled logs.
Record ready/delayed depth, oldest ready age, expired leases, handoff rejection,
attempt distribution, callback/publication failure, repair debt, and effect-dedupe
hits. Approve thresholds before starting, not retroactively after seeing results.

### Required Real-Database And Compatibility Evidence

The real-database gates require Docker-backed tests. Historically the container
tests are opt-in and can be skipped while Maven reports success:
[Q: JdbcAsyncJobStoreDatabaseIntegrationTest:51](../../webapp/src/test/java/com/box/l10n/mojito/queue/JdbcAsyncJobStoreDatabaseIntegrationTest.java#L51)
defines `mojito.asyncJobQueue.testcontainers` and a separate
`mojito.asyncJobQueue.perf` switch. A prior test-count summary is not proof of
actual MySQL/PostgreSQL execution. Run the integrated suite, inspect Surefire
skipped counts, and record container image versions in the review ledger. Example
from the repository root; update test selection if the integrated suite changes:

```sh
mvn -pl webapp -Pno-local-config \
  -Dmojito.asyncJobQueue.testcontainers=true \
  -Dmojito.asyncJobQueue.perf=true \
  -Dtest=JdbcAsyncJobStoreDatabaseIntegrationTest test
```

| Required case | Finite acceptance |
| --- | --- |
| Real runtime/executor and concurrent claim | Two independent workers, 10,000 short jobs, including bursts at the configured concurrency; all reach the expected terminal result, no false rejected/failed jobs, and active plus reserved work never exceeds the intended bound. Repeat handoff race fixture 100 times. |
| Lease fencing under contention | For heartbeat/done/requeue/fail, 100 deterministic blocked-row interleavings per enabled DB dialect, including waits longer than lease. Zero successful expired-owner mutations. |
| Retention versus replay | 100 deterministic interleavings per enabled DB dialect; replay-won jobs survive. Purge-won jobs return an explicit missing/conflict result. Include queued/running negative cases and unresolved-publication retention. |
| Stale business writer | 100 A/B lease-loss races with different output bytes; winner output remains authoritative. Separately exercise pull-run associations or prove that canary eligibility excludes them. |
| Crash boundaries | At least 20 injected failures at each admission/dispatch/work/terminal/publish/task-finish boundary in the recovery table; every accepted request is accounted for, and repairing terminal work never replays its business effects. |
| Retry/deadline behavior | Poison input, transient failure, always-requeue handler, executor rejection, lease reclaim, and missing input all end in the specified bounded state. No infinite poison loop; workflow polling is tested separately from error retry. |
| Pollable/API parity | Same task/error/result schema for single and multi-locale; delayed child, child failure, partial fan-out, lost client response, task timeout, output repair, and explicit replay. Check HTTP/CLI polling, not only handler return values. |
| Mixed versions and rollback | Old producer/new consumer and new producer/retained compatible consumer fixtures; route flip with active Quartz parents and delayed queue rows. Prove old canonical-writing consumers are absent before the private-output fence is relied upon. Complete a bounded 1,000-job drain within 30 minutes under declared fixture runtimes, accounting separately for expected terminal failures. |
| Database adoption | Migration validation from the actual master schema through V108, no version collision or checksum rewrite, and fresh-schema install. MySQL production gate is mandatory; PostgreSQL runtime tests are mandatory for any claimed PostgreSQL support, while full application/PostgreSQL deployment remains a separate certification. |

If any fixture cannot meet its finite runtime bound, diagnose it and revise the
test design explicitly; do not repeatedly extend a rollout until it appears green.
The end state of this plan is a proven `assetlocalize` execution replacement,
then a separately proven statistics adapter, with Quartz still owning schedules
and orchestration. Global Quartz removal is deliberately not an acceptance goal.
