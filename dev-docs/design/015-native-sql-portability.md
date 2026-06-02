# Native SQL Portability Cleanup

This note tracks native SQL that remains after the first portability cleanup pass. The goal is to
keep the remaining native usage intentional while avoiding schema, migration, ID-strategy, Docker,
or Postgres-profile changes in this branch.

## Converted in this pass

- Push/pull cleanup deletes now use JPQL bulk deletes or a JPQL ID selection followed by a
  Criteria bulk delete for the selected IDs, preserving the existing bounded batch loops.
- `AssetTextUnitRepository.getUnmappedAssetTextUnits` now uses JPQL with `not exists`.
- `VirtualTextUnitBatchUpdaterService` now deletes asset-text-unit mappings through JPQL `IN` in
  the existing 100-id batches. An opt-in MySQL benchmark compares the old native `OR`, Criteria
  `OR`, and JPQL `IN` forms; JPQL `IN` was fastest on MySQL 8 in local 20k/50k-row runs.
- `PullRunAssetService.deleteExistingVariants` keeps the old select-IDs-then-delete flow for
  deadlock avoidance, with the delete expressed through Criteria.

## Remaining native or JDBC usage

- Flyway migrations and `FlyWayConfig`: remain JDBC/native because they are migration and Flyway
  infrastructure rather than application query logic.
- `DbMonitoringService`: keeps raw `SELECT 1` because it is intentionally a direct database probe.
- Quartz pending-job reporting: reads Quartz-owned tables that do not have Mojito JPA entities.
- MySQL-only session insert customization: remains guarded by the existing MySQL-specific session
  configuration.
- `ApplicationCacheUpdaterService`: keeps MySQL `ON DUPLICATE KEY UPDATE` upserts for now because
  cache writes are concurrency-sensitive and need dedicated duplicate-key/concurrency tests before
  replacing with portable logic.
- Term-index `insert ignore` repositories: remain native for now because they provide atomic
  insert-if-absent behavior and should either get duplicate-key concurrency tests or a small
  dialect adapter.
- `AssetTextUnitToTMTextUnit.getExactMatches`, `TranslationKit.exportedAndCurrentTuvs`,
  `TMTextUnitVariantRepository` delta query, and `ReviewProjectRepository`
  `recomputeDecidedCountsByRequestId`: remain native pending exact equivalence tests because they
  are business-critical joins/aggregates.
- One-time MySQL updater jobs (`PluralFormUpdaterJob`, `TUCVAddAssetIdUpdater`,
  `AssetExtractionByBranchRemoverJob`): remain native and guarded because they are legacy data
  repair paths.
- Push/pull run batched insert helpers: remain JDBC for multi-row insert performance; replacing
  them should be done with focused duplicate/ordering tests and performance awareness.

## Follow-up candidates

- Add shadow/equivalence tests for the business-critical native named queries and aggregate update,
  then convert them one at a time.
- Add duplicate-key and concurrency tests around cache and term-index insert-if-absent behavior
  before changing those paths.
- Consider JPA or Criteria implementations for text-unit ingestion monitoring if its
  `CAST(... AS DATE)` aggregation becomes a portability blocker.
