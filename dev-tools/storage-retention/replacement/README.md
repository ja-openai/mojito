# Prepare a smaller `mblob` table

`render.py` creates local SQL files only. It has no database client, network
calls, execution mode, or automatic cutover. The SQL targets MySQL 8 and the
reviewed five-column `mblob` schema. Keep it with the promotion evidence and the
maintenance runbook. An atomic rename changes the active table; retaining the
original table does **not** reclaim its disk allocation.

## Required evidence and operational controls

1. Finish canonical promotion and full source reconciliation. Obtain the entire
   current `GET /api/monitoring/blob-promotions/{id}/readiness` response using
   the approved authenticated maintenance connection. Save that JSON locally.
   Historical promotion status or a snapshot-complete response is insufficient.
   The renderer accepts the application's integer epoch-millisecond timestamps
   and explicit ISO 8601 timestamps; it does not infer seconds from JSON numbers.
2. Keep the same externally enforced maintenance fence continuously active:
   API/worker admission, normal application instances, cleanup, fallback
   backfills, external writers/reconcilers, and relevant Azure lifecycle actions
   must remain stopped as required by that manifest. The JSON is operational
   evidence, not a signature. The SQL cannot independently inspect those systems.
3. Resolve every canonical content or retention conflict through a separately
   reviewed decision, then complete a fresh promotion. Keeping its database row
   does not fix an Azure-first read of a conflicting canonical object. Every SQL
   phase rejects these unresolved conflict dispositions.
4. Review `SHOW CREATE TABLE mblob`, incoming/outgoing foreign keys, triggers,
   views, routine/event dependencies, grants and executor metadata visibility.
   The generated guards reject visible foreign keys, triggers, views, unexpected
   column shape, and non-InnoDB storage. Every batch and swap also rechecks
   source/target column definitions and indexes for differences. They cannot prove the absence of
   dependencies hidden by permissions or application code. The executor needs
   normal table privileges plus permission to create/call/drop temporary helper
   procedures; this tool never grants privileges.
5. Confirm replica health, backup recovery, storage headroom for the retained
   rows and MySQL logs, and measured operation latency. Payload copying and
   verification use bounded keyset batches. Preparation/final counts can scan
   complete indexes and may be too slow on staging; **no short downtime is
   promised**. If a count/DDL exceeds the proof window, stop and redesign after
   measurement. Do not remove the guard to make it pass.

The application must continue to use the reviewed Azure-with-database-fallback
routes for retained rows. Direct database control rows also remain in the new
table. All rows with a non-null database TTL remain in the new table with their
original creation time and TTL; they are not mapped to a shorter Azure lifetime.

## Render and review

Use unique table names. The renderer exclusively creates its output file and
refuses an expired proof or one with less than 35 seconds remaining. Each SQL
file also checks the remaining proof window at execution. After reviewing a
step, fetch fresh readiness and rerender with the same identities immediately
before its approved execution; an old saved JSON cannot renew the fence.

```sh
python3 dev-tools/storage-retention/replacement/render.py \
  --phase prepare --database mojito \
  --replacement mblob_replacement_review --backup mblob_before_review \
  --readiness /absolute/path/current-readiness.json \
  --output /absolute/path/prepare.sql
```

The renderer never runs `mysql`. Execute reviewed SQL only against the approved
database, with a dedicated connection and without `--force`. Preparation clones
the source schema and indexes, preserves its `AUTO_INCREMENT` reservation, and
creates a checkpoint table. It refuses to overwrite an existing table. If
preparation fails after a DDL statement, inspect the empty preparation artifacts
before deciding whether to remove them or use new names.

For copying and verification, render `--phase batch` with the same arguments
and fresh readiness. `--max-rows` is 1–1,000 (default 100); `--max-bytes` is the
sum of source payload lengths in the selected batch (default 64 MiB, maximum
1 GiB). Queries may reread that bounded set, including corresponding target
bytes during verification. These limits are not a guarantee on physical disk
I/O or execution time. A batch exceeding its byte limit rolls back without
advancing; reduce rows or review a larger byte allowance. A single oversized row
that cannot fit the maximum budget requires another reviewed copy method.

Each batch prints its durable checkpoint. `COPY` copies every retained row and
omits only permanent `CANONICAL_VERIFIED` rows whose final metadata and SHA-256
still match source. `VERIFY` then makes a separate complete bounded pass:

- Source IDs, exact binary names, lengths, creation dates and TTLs must match
  final promotion evidence; canonical source digests and ETag evidence must exist.
- Every retained row must match the source's metadata and SHA-256, including
  null names or contents. No canonical-verified ID may appear in the replacement.
- Count and retained-byte totals must match the completed promotion, with no
  extra source/target rows. Only then does the checkpoint become `VERIFIED`.

Copy/checkpoint updates commit together. An error rolls back that batch and does
not advance its cursor. Successful batches are resumable; use fresh readiness
and rerender the next batch. Do not edit a checkpoint or promotion evidence to
skip a failure. A failed SQL call leaves a uniquely named helper procedure for
inspection; a later freshly rendered file uses a different helper name. Procedure
cleanup can be reviewed separately. No source row is deleted by these phases.

## Separate cutover approval

`--phase swap` renders the atomic rename for review. It still requires current
readiness, the same fence, conflict-free final promotion, and a fully verified
replacement. The generated SQL contains an **unset** session approval variable;
its comment shows the exact value to set only after separate explicit approval
of the database, promotion, target and backup names. Rendering does not provide
that approval or set the variable.

The approved operation preserves the current source auto-increment reservation,
then atomically renames `mblob` to the named backup and the replacement to
`mblob`. The original table remains intact. Rename DDL cannot be undone by the
procedure's exception handler: if the connection fails or a later statement
fails, inspect the actual tables and checkpoint before retrying. A successful
rename must be recorded even if updating the checkpoint failed afterward.

Recheck source routes and representative canonical, retained temporary, null,
unknown and control reads before resuming admission. The `/readiness` endpoint
is a pre-cutover check and is not expected to validate the smaller table after
the swap. Do not use that failure as an instruction to restore the old table.

## Rollback and later physical reclamation

There is deliberately no automated rollback SQL or `DROP` of the old table.

- If service admission never resumed and no source/canonical writes or cleanup
  happened after the rename, a separately reviewed reverse rename may restore
  the original table under the same verified fence. Validate those conditions;
  a preserved table's existence is not sufficient proof.
- Once any new writes, deletes, TTL cleanup, fallback backfill, or canonical
  changes occurred, stop and reestablish a verified maintenance fence. Preserve
  both tables and Azure evidence. Reconcile new IDs and same-name updates from
  the active table plus all intervening canonical mutations. Do not replace the
  active table with the stale backup or blindly union its rows: that can lose
  new writes or resurrect deleted/stale fallback content.
- A routing rollback to database-only is a data migration: rows omitted because
  they were canonical-verified must first be restored from verified current
  canonical objects, while preserving deletions and newer values. This tool
  does not implement that broader reconciliation.
- Approve physical reclamation only after measured soak, a tested independent
  recoverable backup/export, and an agreed recovery/retention window. The old
  table continues consuming storage until that separately approved action.

## Offline verification

```sh
python3 -m unittest discover -s dev-tools/storage-retention/replacement -p 'test_*.py'
```

## Database rehearsal in CI or on explicit request

Run the following only in CI or when the user explicitly requests local database
validation. Do not start Docker or database services for routine commit preparation.

```sh
python3 dev-tools/storage-retention/replacement/rehearse_mysql.py
```

This command uses only a local MySQL 8 UNIX socket (`/tmp/mysql.sock` by
default), ignores client defaults, creates random `mojito_replacement_probe_*`
databases, and drops only those disposable databases. It never accepts a remote
host or existing database name. Generated SQL, exact code/schema hashes and
results are retained under `~/.cache/mojito-storage-replacement-validation-20260914`.
This verifies SQL correctness on small fixtures; it does not benchmark staging,
run Java/Azure promotion, prove the external fence, or authorize a deployment.
