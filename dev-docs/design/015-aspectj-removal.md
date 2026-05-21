# AspectJ Removal Plan

Status: active planning

## Context

Mojito currently uses AspectJ compile-time weaving in `common`, `webapp`, and `cli`. This creates a
build-time dependency on `ajc` and makes behavior depend on cross-cutting annotations and weaving
rather than code visible at the call site.

The target direction is not Spring AOP. Proxy-based AOP keeps hidden control flow, self-invocation
edge cases, and debugger surprises. Mojito should move toward explicit imperative Java, even when
that means repeated boilerplate, because the codebase is increasingly edited by agents and should be
easy to inspect, debug, and mechanically modify.

## Current Inventory

Build wiring:

- Parent `pom.xml` declares `aspectj-maven-plugin.version` and a provided `aspectjrt` dependency.
- `webapp/pom.xml` runs `aspectj-maven-plugin` for `compile` and `test-compile`.
- Aspect libraries still include `spring-aspects` in `webapp`.

Framework modes:

- `Application` still uses `@EnableTransactionManagement(mode = AdviceMode.ASPECTJ)`.

Remaining annotation usage:

- `299` `@Transactional` usages remain across production and test sources.
- No `@Configurable`, `@Pollable`, `@Timed`, `@StopWatch`, `@RunAs`, `@JsonRawString`, AspectJ
  method-security, AspectJ async, AspectJ caching, or annotation retry usage remains in source.

Custom AspectJ entry points:

- No custom aspect classes remain in source. The remaining weaving path exists for Spring's
  transaction aspect until transaction boundaries are migrated or proven safe without weaving.

Removed during this workstream:

- The unused custom `@Retry`, `RetryAspect`, and `RetryAspectConfig` path. Existing retry behavior
  used explicit `RetryTemplate` calls or Spring's `@Retryable`, not the custom annotation.
- Spring `@Retryable` and `@EnableRetry` were removed. The remaining retry behavior uses explicit
  loops or explicit `RetryTemplate` calls.
- `@StopWatch` and `StopWatchAspect`. The four annotated methods now use explicit local
  `Stopwatch` logging.
- `@JsonRawString`, `JsonRawStringAspect`, and the aspect-specific test fixtures. `PollableTask`
  now validates and quotes raw JSON strings directly in its serialized getters.
- `@RunAs`, `RunAsAspect`, and `RunAsAspectConfig`. Bootstrap default-user creation now swaps to
  the system user explicitly with a local `try/finally`.
- `@Timed` and `TimedAspect`. The previous metrics are now recorded explicitly in
  `MultiBranchStateService`, `TextUnitDTOsCacheBlobStorage`, `TextUnitDTOsCacheService`,
  `OpenAIMTEngine`, `MicrosoftMTEngine`, `MachineTranslationService`, and
  `AssetExtractionService` with local `Timer.Sample` and `try/finally` blocks.
- `@Pollable`, `PollableAspect`, `PollableAspectConfig`, and the AspectJ annotation parsing helper
  classes. Pollable task creation now goes through the Spring-managed `PollableTaskRunner`
  directly.
- `@Async` and `@EnableAsync(mode = AdviceMode.ASPECTJ)`. Async work now submits directly to the
  existing `asyncExecutor` and `statisticsTaskExecutor` beans, which preserves self-invoked async
  behavior without weaving.
- `@Cacheable`, `@CacheEvict`, and `@EnableCaching(mode = AdviceMode.ASPECTJ)`. Cached operations
  now use explicit `CacheService` lookups and writes.
- `DatabaseCache` no longer uses `@Configurable`; `CachingConfig` passes its collaborators
  explicitly and `DatabaseCache` uses `TransactionTemplate` for its write boundaries.
- `DatabaseCacheEvictionJob` no longer uses `@Configurable` or `@Transactional`; Quartz-created
  instances are autowired by the existing Quartz job factory and the eviction write uses
  `TransactionTemplate`.
- `QuartzPollableFutureTask` no longer uses `@Configurable`; `QuartzPollableTaskScheduler` passes
  its task service and blob storage dependencies directly.
- `LeveragerByContentAndRepository` and `LeveragerByTmTextUnit` no longer use `@Configurable`;
  manually created leveragers now come from `LeveragerFactory`.
- `FileSystemDropExporter` and `FileSystemDropImporter` no longer use `@Configurable`;
  `DropExporterService` constructs file-system exporters with explicit dependencies.
- CLI extraction-check notification senders no longer use `@Configurable`;
  `ExtractionCheckCommand` passes GitHub, Slack, and Phabricator collaborators explicitly.
- `CommandWaitForPollableTaskListener` no longer uses `@Configurable`; `CommandHelper` passes its
  console writer directly when waiting on pollable tasks.
- `L10nJCommander` no longer uses `@Configurable`; CLI entrypoints and tests request prototype
  commanders from Spring and commands render usage through their active commander.
- Common Okapi filters for regex double-quote escaping, JS skeleton writing, JS parsing,
  Xcode XLIFF, and Mac Stringsdict no longer use `@Configurable`; Okapi-created instances now
  initialize their helper collaborators directly.
- Common Okapi Android and PO filters no longer use `@Configurable`; Okapi-created filter and
  encoder instances now initialize text-unit and unescape helpers directly.
- Common Okapi MD5 and do-not-translate steps no longer use `@Configurable`; direct pipeline
  instances initialize `TextUnitUtils` through constructors.
- `PseudoLocalizeStep` no longer uses `@Configurable`; `TMService` passes integrity checker,
  pseudo-localization, and text-unit helpers directly.
- `TranslatorWithInheritance` no longer uses `@Configurable`; callers pass the text-unit DTO cache
  service explicitly and unused woven collaborators were removed.
- `TranslateStep` no longer uses `@Configurable`; its explicit constructor now provides the only
  service collaborator it needs, while inherited text-unit helpers initialize locally.
- `IntegrityCheckStep` no longer uses `@Configurable`; XLIFF import creates it with explicit TM text
  unit and integrity-checker collaborators.
- The unused `UnescapeStep` pipeline step was removed; no source or resource referenced it outside
  its own class.
- `ImportExportedXliffStep` no longer uses `@Configurable`; `TMImportService` now passes its import
  collaborators explicitly and unused woven fields were removed.
- `TMExportFilter` no longer uses `@Configurable`; `TMService` now constructs it with explicit
  export collaborators and unused woven fields were removed.
- `TranslationKitFilter` no longer uses `@Configurable`; `TranslationKitService` now passes the
  filter collaborators explicitly when generating XLIFF kits.
- `TranslationKitStep` no longer uses `@Configurable`; `TranslationKitService` now passes the step
  persistence collaborators explicitly and focused step tests construct it directly.
- XLIFF import translation steps no longer use `@Configurable`; `TMService` constructs the import
  step family with explicit base and subclass collaborators.
- Test data helpers no longer use `@Configurable`; they autowire and initialize themselves through
  the Spring test context exposed by `WSTestBase`.
- `@EnableSpringConfigured` was removed from application and test configuration now that no
  `@Configurable` types remain.
- Unused AspectJ method-security mode was removed; there were no method-level security annotations
  to enforce, so the `spring-security-aspects` dependency and aspect library entry were removed.
- `common` and `cli` no longer run `aspectj-maven-plugin` or depend on `spring-aspects`; neither
  module has source annotations or framework modes that need compile-time weaving.
- `DatabaseBlobStorage.putBase` no longer uses self-invoked `@Transactional(REQUIRES_NEW)`; the
  retry path now opens and commits a new transaction explicitly for each attempt.
- `UserDeletionService.hardDeleteUser` no longer uses `@Transactional(REQUIRES_NEW)`; hard-delete
  attempts now run inside an explicit independent transaction before soft-delete fallback.
- `PollableTaskService` no longer uses `@Transactional(REQUIRES_NEW)`; pollable task reads,
  creation, finishing, and progress updates now open explicit independent transactions.
- `Bootstrap.createSystemUser` no longer relies on self-invoked `@Transactional`; bootstrap opens
  the system-user creation transaction explicitly.
- `TextUnitSearcher` no longer uses self-invoked read-only `@Transactional` methods inside retry
  loops; each retry attempt opens a read-only transaction explicitly.
- `AiReviewService.saveAiReviewProtosInTx` no longer relies on self-invoked `@Transactional`;
  review proto persistence now opens its transaction explicitly.
- `TMTextUnitVariantCommentService` no longer relies on AspectJ for self-invoked comment writes;
  add/copy comment paths now open their transaction boundaries explicitly.
- `AiTranslateLocalePromptSuffixService` no longer uses `@Transactional`; locale prompt suffix
  reads and writes now open read-only or read-write transactions explicitly.
- `AiTranslateRunService` no longer uses `@Transactional`; AI translate run creation, status
  updates, and recent-run reads now open their transaction boundaries explicitly.
- `ReviewAutomationRunService` no longer uses `@Transactional`; review automation run creation,
  status updates, and recent-run reads now open their transaction boundaries explicitly.
- `AssetIntegrityCheckerService.addToRepository` no longer uses `@Transactional`; integrity
  checker creation now opens and commits its transaction explicitly.
- `AiTranslateAutomationConfigService.updateConfig` no longer uses `@Transactional`; automation
  config writes now open and commit their transaction explicitly.
- `SlaCheckerService.closeIncidents` no longer relies on self-invoked `@Transactional`; SLA
  incident closure now opens and commits its transaction explicitly.
- `TMTextUnitCurrentVariantService` no longer uses `@Transactional`; current-variant removal and
  status batch updates now open and commit their transactions explicitly.
- `DropService` no longer uses `@Transactional` for drop loading or completion; cancel-drop loading
  and partial-import completion now open and commit their transactions explicitly.
- `PushRunService` no longer uses `@Transactional`; push-run cleanup and text-unit association now
  open and commit their transactions explicitly.
- `PullRunAssetService` no longer uses `@Transactional`; delete and insert retry steps now open and
  commit their transaction boundaries explicitly.
- `CurrentVariantRollbackService` no longer relies on AspectJ for a rollback method reachable by
  self-invocation; current-variant rollback now opens and commits its transaction explicitly.
- `TextUnitIngestionMonitoringService` no longer uses `@Transactional`; recompute and snapshot
  reads now open and commit their read-write or read-only transactions explicitly.
- `AbstractLeverager` no longer relies on AspectJ for a private transactional helper; leveraged
  translation copies now open and commit their transaction boundary explicitly.
- `GlossaryImportExportService` no longer uses `@Transactional`; glossary import and export now
  open and commit their transaction boundaries explicitly.
- `GlossaryStorageService` no longer uses `@Transactional`; managed backing repository, canonical
  asset, locale, delete, and rename operations now open and commit their transaction boundaries
  explicitly.
- `GlossaryManagementService` no longer uses `@Transactional`; glossary search, detail, create,
  update, and delete operations now open and commit their transaction boundaries explicitly.
- `TermIndexExplorerService` no longer uses `@Transactional`; term-index explorer search, status,
  and review update operations now open and commit their transaction boundaries explicitly.
- `TMImportService.importXLIFF` no longer relies on AspectJ for a private transactional helper;
  XLIFF import now opens and commits its transaction boundary explicitly.
- `VirtualTextUnitBatchUpdaterService.updateTextUnits` no longer uses `@Transactional`; virtual
  text-unit updates now open and commit their transaction boundary explicitly while preserving the
  checked `VirtualAssetRequiredException`.
- `TextUnitBatchImporterService.importTextUnitsOfLocaleAndAsset` no longer relies on AspectJ for a
  self-invoked package-private transactional helper; per-locale/asset imports now open and commit
  their transaction boundary explicitly.
- `AssetMappingService` no longer relies on AspectJ for protected transactional helpers; legacy
  exact-match mapping and unmapped text-unit creation now open and commit their transaction
  boundaries explicitly.
- `AssetService` no longer uses `@Transactional`; asset creation and deletion now open and commit
  their transaction boundaries explicitly, and bulk deletion keeps per-asset deletion inside the
  same transaction.
- `VirtualAssetService` no longer uses `@Transactional`; virtual asset writes, localized reads, and
  text-unit add/delete helpers now open and commit their transaction boundaries explicitly while
  preserving checked virtual-asset exceptions.
- `ScreenshotService` no longer uses `@Transactional`; screenshot-run creation, screenshot search,
  and screenshot deletion now open and commit their transaction boundaries explicitly.
- `CommitService` no longer uses `@Transactional`; commit creation and push/pull-run association
  writes now open and commit their transaction boundaries explicitly while preserving checked
  exceptions.
- `TranslationKitService` no longer uses `@Transactional`; kit generation, kit unit updates,
  import statistics, and partial-import checks now open and commit their transaction boundaries
  explicitly.
- `TMService` no longer uses `@Transactional`; XLIFF export and localized import batch processing
  now open and commit their transaction boundaries explicitly.
- `ReviewFeatureService` no longer uses `@Transactional`; review-feature reads and writes now open
  and commit their transaction boundaries explicitly, with create/update detail reads kept inside
  the same write transaction.
- `BranchStatisticService.updateBranchStatisticInTx` no longer uses `@Transactional`; branch
  statistic writes now open and commit their transaction boundary explicitly.
- `AiTranslateTextUnitAttemptService` no longer uses `@Transactional`; attempt lineage reads and
  no-batch attempt writes now open and commit their transaction boundaries explicitly.

In progress:

- Transaction migration remains. `@Transactional` usage is broad, so the next code changes should
  target self-invoked, checked-exception, retry, or `REQUIRES_NEW` boundaries before switching
  `@EnableTransactionManagement` away from AspectJ mode.
- Final `webapp` build cleanup remains blocked on the transaction migration. Keep `webapp`
  `spring-aspects`, `aspectjrt`, and `aspectj-maven-plugin` until no remaining behavior depends on
  Spring's woven transaction aspect.

## Migration Principles

- Prefer plain `try/catch/finally` where the behavior changes business outcome.
- Keep checked exceptions checked. Avoid wrapping and unwrapping just to fit lambda APIs.
- Keep transaction, retry, pollable-task, security, and timing boundaries visible in the method
  where they matter.
- Use small services for operations such as creating tasks, recording metrics, or setting security
  context, but do not hide the main control flow behind annotations, proxies, fake resource scopes,
  or broad lambda runners.
- Accept simple repeated boilerplate when it improves local readability.
- Migrate one topic per commit and preserve behavior before simplifying.

## Workstreams

### Transactions

Replace AspectJ transaction mode with explicit imperative transaction boundaries where behavior
depends on self-invocation, checked exceptions, retries, or nested transaction semantics.

Preferred shape:

```java
public Result importFile(File file) throws IOException {
  return importFileWithTx(file);
}

private Result importFileWithTx(File file) throws IOException {
  TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

  try {
    Result result = doImportFile(file);
    transactionManager.commit(transaction);
    return result;
  } catch (IOException | RuntimeException e) {
    transactionManager.rollback(transaction);
    throw e;
  } catch (Error e) {
    transactionManager.rollback(transaction);
    throw e;
  }
}
```

Use `TransactionTemplate` only for simple runtime-exception-only paths where it stays clearer than
manual transaction management.

### Retry

Use explicit retry loops in critical paths, especially when the behavior changes by attempt number.
Keep retry and transaction boundaries visibly close.

Generic retry helpers are acceptable for boring "retry the exact same operation" cases. Avoid
annotation retry for workflows that need cleanup, reloads, repartitioning, persistence-context
clearing, or different behavior after a certain number of attempts.

### Pollable Tasks

Replace `@Pollable` with explicit task lifecycle code. Preserve current behavior first:

- sync versus async execution
- `PollableFuture` return behavior
- parent task and current task semantics
- message arguments
- expected subtask count
- timeout handling
- failure recording and exception propagation

Preferred shape:

```java
PollableTask task = pollableTaskService.createPollableTask(...);

try {
  Result result = doWork(task);
  pollableTaskService.markSuccess(task);
  return result;
} catch (Exception e) {
  pollableTaskService.markFailed(task, e);
  throw e;
}
```

Focused `PollableTaskRunner` and `PollableTaskService` tests cover the explicit task lifecycle
helper behavior that replaced the old `@DeclareError` checks.

### Configurable Objects

Remove `@Configurable` injection. Replace it with constructor injection, factories, or explicit
collaborators. Pay special attention to Okapi filters and steps, CLI command helpers, cache jobs,
translation kit classes, and pollable helper classes that are currently created with `new` but
receive Spring dependencies through weaving.

### Security Context

Replace `@RunAs` with explicit security-context changes. Prefer visible restore behavior:

```java
SecurityContext previousContext = securityContextService.runAsSystem();
try {
  return doWork();
} finally {
  securityContextService.restore(previousContext);
}
```

### Timing And Metrics

Replace `@Timed` and `@StopWatch` with explicit timing code. Avoid `try-with-resources` timing
scopes by default; use start-time plus `finally` so stack traces and debugging stay plain.

```java
long startTime = clock.millis();
try {
  return doWork();
} finally {
  timings.record("metric.name", clock.millis() - startTime);
}
```

### JSON Raw Serialization

Replace `@JsonRawString` with explicit DTO or Jackson serialization behavior. The response shape
should not depend on method weaving.

### Build Cleanup

After all usages are gone:

- Remove `aspectj-maven-plugin` from `webapp`.
- Remove `aspectj-maven-plugin.version`, `aspectjrt`, `aspectjtools`, `spring-aspects`, and
  `spring-security-aspects` where no longer needed.
- Remove obsolete aspect classes and `Aspects.aspectOf(...)` configuration.
- Confirm Maven compile/test no longer invokes `ajc`.

## Validation

For each behavior-changing commit:

- Run narrow tests for touched services.
- Add or update tests that lock down migrated control flow.

Before final AspectJ build cleanup:

- Run `mvn spotless:apply`.
- Run `mvn -pl webapp -Pfrontend test-compile -DskipTests`.
- Run relevant backend unit tests, then the full unit test suite if feasible.
- Run the MySQL-backed suite before considering the migration complete.

Frontend checks are only required if frontend files are touched.
