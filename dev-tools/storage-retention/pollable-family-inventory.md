# Pollable task family inventory and next archive increment

The current archive worker handles completed, unreferenced standalone tasks. It does not establish that overall task-table growth is controlled. Parent/child families and retained run-history references require separate measurement and handling.

## Read-only inventory

`pollable_family_inventory.py` inspects only explicitly supplied task or root IDs. It does not select a population or delete anything. It uses Python 3 and the MySQL CLI, runs one `REPEATABLE READ` consistent snapshot marked `READ ONLY`, and rolls back when it closes. A task ID is climbed to its root; an explicitly supplied root must have no parent. Duplicate roots are inspected once.

Run from the repository root, using the intended database and an existing read-only login:

```sh
python3 dev-tools/storage-retention/pollable_family_inventory.py \
  --mysql mysql --login-path mojito-readonly \
  --host 127.0.0.1 --port 33306 --database mojito \
  --task-id 123 --task-id 124 --root-id 456 \
  --finished-before 2026-06-15T00:00:00Z \
  --max-inputs 20 --max-roots 20 --max-family-rows 100 \
  --max-rows 2000 --max-depth 32 --max-queries 500 \
  --max-seconds 60 --query-timeout-ms 1000 \
  --output /tmp/pollable-family-inventory.json
```

Use actual supplied IDs. The database, login path and tunnel port above are placeholders, not discovery defaults. `--socket /path/to/mysql.sock --user USER` replaces the TCP connection options for local use. Credentials are not accepted as command-line password arguments or included in the report. The CLI uses `--no-defaults`; an optional explicit MySQL login path provides authentication.

Without `--finished-before`, the cutoff is the invoking machine's current UTC time minus `--retention-days` (default 90). An explicit cutoff makes repeated measurements comparable.

### Bounds and data selected

- Input IDs, resolved root count, ancestor depth and descendant depth have separate limits.
- `--max-family-rows` limits members discovered in one family. Child lookups read at most remaining capacity plus one sentinel; they do not recursively expand an oversized family.
- `--max-rows` limits **all task metadata rows returned**, including duplicate reads during climbing/traversal and overflow sentinels. Cached ancestor reads do not consume additional rows. Schema metadata and bounded reference matches are separate; `--max-queries`, per-query timeouts and the overall deadline bound those operations.
- Each foreign-key check uses a bounded set of observed task IDs and indexed `EXISTS` lookups. Reference matches return at most the observed family size. The tool reads the current incoming foreign-key inventory from `information_schema`, so a newly added incoming FK becomes a blocker without a code allowlist change. If any of the nine expected current-schema external columns is absent from that inventory, reference coverage is marked unknown; an old schema or incomplete metadata visibility cannot produce a clear result.
- Only IDs, parent IDs, expected child counts, finished timestamps, child-existence flags, and comparisons to the known excluded task types are selected. Task messages, error stacks, user profiles, input/output blobs and queue payloads are not selected.
- Time, query, row and depth exhaustion produce explicit partial results. A partially inspected family is never marked clear. A connection/schema failure exits with code 2; successful bounded reports use code 0 and `status: partial` when a limit or unresolved input prevented completion.

A read-only consistent snapshot describes the database at its snapshot time. It does not lock out new references or authorize later deletion. It also holds metadata locks until closed; the default deadline keeps this bounded.

### Interpret the report

`appears_clear_under_inventory_policy` requires a complete family, complete indexed reference checks, every member finished before the cutoff, satisfied expected child counts, and no excluded task types or references. It is an inventory classification, not a deletion decision.

`families_by_reason_overlapping` and `observed_member_rows_by_reason_overlapping` deliberately overlap. One family can have several blockers. Sum only `exclusive_reason_combinations` for distinct-family totals. Truncated member counts are lower bounds. Input resolution failures, oversized families and their counts remain visible.

The tool checks the indexed `ai_review_request_usage.pollable_task_id` soft reference even for task types not normally expected to create it. If that index is absent, it reports unknown coverage and avoids a table scan. Serialized async queue payload references are not scanned: all `GenerateLocalizedAssetJob` families remain conservatively excluded. Families containing `AiReviewChatJob` or `AiReviewConfiguredChatJob` remain excluded even without a currently visible usage reference. Arbitrary future serialized references cannot be discovered from foreign-key metadata.

For a date cohort, first select a small explicit list of task IDs with the existing `(finished_date,id)` seek. Feed those IDs to this tool, deduplicate resolved roots, and report cohort membership separately from descendants outside the original cohort. Selected date slices are not a representative whole-table sample. Increase bounds only after checking elapsed time, query plans and observed family sizes.

## Actual reference constraints

Current schema has ten incoming task FKs: the internal `pollable_task.parent_task_id` and nine external columns across eight tables:

| Table | Task column(s) | Family retention constraint |
| --- | --- | --- |
| `asset_extraction` | `pollable_task_id` | Existing extraction cleanup can release superseded finished extractions. |
| `drop` | `import_pollable_task_id`, `export_pollable_task_id` | Preserve the family while either reference exists. |
| `tm_xliff` | `export_pollable_task_id` | Preserve the family while the export record exists. |
| `ai_translate_run` | `pollable_task_id` | Retained run-history relationship. |
| `ai_translate_text_unit_attempt` | `pollable_task_id` | Required FK; lineage queries join the task. |
| `term_index_refresh_run` | `pollable_task_id` | Retained run-history relationship. |
| `term_index_automation_run` | `pollable_task_id` | Retained run-history relationship. |
| `bulk_import_run` | `pollable_task_id` | Retained import-lineage relationship. |

No application retention/delete path was found for the seven non-extraction table types above. This is a source audit, not a guarantee that operators never delete rows. Complete-family archival still cannot remove any family pinned by these records while preserving all external references.

## Feasible next archive increment: design only

Keep graph archival separately disabled until inventory establishes its useful scope. Removing standalone checks alone would break graph reads, owner inheritance and self-FK deletion.

1. Use a separate graph checkpoint and bounded root-family traversal. Require every member to be old and complete; a finished parent is insufficient. Revisit families with younger or unfinished children so a root watermark does not skip them permanently. Record reference-pinned and oversized families separately from transient retries.
2. Preserve the v1 standalone reader. A v2 immutable family object plus an immutable per-task pointer can provide any child-ID lookup with two object reads without duplicating a whole family per member. Verify all objects and pointers before deleting any source row. A conflicting immutable pointer retains the whole family for investigation.
3. Rebuild the requested subtree and its ancestor chain. Preserve `isAllFinished`, nested error traversal, task inspection and stable ancestor-owner resolution. Current profile changes must not invalidate task-state equality. Operational fresh lookups and lifecycle writes remain database-only.
4. In the final short transaction, lock the checkpoint and every family member in deterministic ID order, re-enumerate membership and recheck task state, age and every external reference. Parent locks fence new children through the self FK. Delete children before parents, then advance the checkpoint in the same transaction. No remote storage calls occur while holding relational locks.
5. Deploy compatible graph readers everywhere before enabling deletion. A rollback after deletion must retain the readers, even with the worker disabled.

Rough scope after measurement: two increments (v2 storage/read compatibility, then bounded family worker/deletion), about 10–15 production files plus migration(s), 20–30 focused regressions, and native MySQL family-race tests. This is a planning estimate, not a delivery promise. Tests must cover direct child reads, ancestor owners, mixed owners, nested errors, incomplete/oversized/cyclic graphs, all reference blockers, new or moved children during remote I/O, child-first rollback, stale leases and partial object publication. Benchmark the largest allowed family before a delete-enabled canary.

## Native inventory tests in CI or on explicit request

The real-database commands in this section and the next are for CI or an explicitly
user-requested local database validation run. Routine commit checks use mocks or
in-memory databases; do not start Docker or database services, and leave
`mojito.asyncJobQueue.testcontainers` unset. Even the archive wrapper's `--prepare-only`
mode creates a disposable database and therefore belongs to this opt-in workflow.

This separate test harness performs fixture writes only in its own uniquely named disposable local database. The inventory itself still performs read-only SQL. The test harness accepts a UNIX socket, creates an absent random database, and drops only that database in cleanup; it cannot target a supplied existing database.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 dev-tools/storage-retention/test_family_inventory_mysql.py \
  --mysql mysql --socket /tmp/mysql.sock --user root \
  --output /tmp/mojito-storage-validation
```

Tests apply the relevant existing migration DDL, cover all nine external task columns, actual usage soft references, unknown/new FKs, missing usage indexes, incomplete families, cycles, shared ancestors, row/depth/root/query/time bounds and snapshot behavior. Artifacts contain read-only query logs, reports, server identity and verified database cleanup. These are inventory integration tests; they do not exercise the Java archive worker or Azure.

## Existing Java archive tests on native MySQL

`PollableTaskArchiveRepositoryTest` inherits the normal Spring test configuration and can use a disposable native database. It commits task deletions and clears the archive checkpoint/retry tables, so it must never run against an existing application database. The other archive tests mostly use mocks and do not add native database coverage.

Use the dedicated wrapper to verify setup and cleanup without Maven, then run the fixed repository test after coordinating access to the shared build directory:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 dev-tools/storage-retention/run_archive_mysql_test.py \
  --mysql mysql --socket /tmp/mysql.sock --admin-user root \
  --output /tmp/mojito-storage-validation/archive-java --prepare-only

PYTHONDONTWRITEBYTECODE=1 python3 dev-tools/storage-retention/run_archive_mysql_test.py \
  --mysql mysql --socket /tmp/mysql.sock --admin-user root \
  --output /tmp/mojito-storage-validation/archive-java
```

The wrapper creates a random database and scoped users, verifies that socket and loopback TCP reach the same server, writes credentials only into private temporary configuration, captures redacted Flyway/Surefire artifacts, and verifies that its generated database and users are absent after cleanup. It ignores inherited local Spring/JVM/MySQL connection settings. Its external test properties override the JDBC URL and enable real Flyway migrations while disabling HSQL initialization:

```properties
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.flyway.enabled=true
spring.flyway.clean-disabled=true
l10n.flyway.clean=false
l10n.flyway.repair=false
spring.jpa.hibernate.ddl-auto=none
spring.jpa.defer-datasource-initialization=false
spring.sql.init.mode=never
spring.session.jdbc.initialize-schema=never
spring.profiles.include=
l10n.org.quartz.scheduler.enabled=false
l10n.ai-review.cleanup-enabled=false
l10n.pollable-task.archive.enabled=false
l10n.blob-storage.migration.enabled=false
l10n.bootstrap.enabled=true
```

Do not activate the `disablescheduling` profile for this full application test: it removes `AiTranslateAutomationCronSchedulerService`, which an active web controller requires. The explicit Quartz scheduler flag keeps its factory in standby while retaining those beans. The AI-review and async-queue worker flags disable the separate Spring-scheduled writers.

Keep Bootstrap enabled because the security fixture requires its admin account. Leave the archive worker disabled globally: the repository test constructs its own worker with mocked Azure. Use `-Pno-local-config` to exclude the developer's local config. Use `ddl-auto=none`, since whole-schema Hibernate validation has an unrelated documented `application_cache.key_md5` type mismatch. A native run establishes actual Flyway/JPA behavior with mocked object storage; Azure connectivity and concurrent deletion races remain separate checks.
