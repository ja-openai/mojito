# Disposable MySQL storage probes

These real-database commands are for CI or an explicitly user-requested local database
validation run. They are not routine commit checks. Do not start Docker or database
services for commit preparation; use mocks or in-memory databases and leave
`mojito.asyncJobQueue.testcontainers` unset.

When authorized, run from the repository root against the disposable test server:

```sh
python3 dev-tools/storage-retention/validate_mysql.py
```

The script requires Python 3, the `mysql` CLI, and a local MySQL 8 UNIX socket. It
defaults to `/tmp/mysql.sock` and user `root`. It ignores client defaults, accepts
no remote hostname or existing database name, generates a database named
`mojito_storage_probe_<random>`, and drops only that database after the probes.

Each run writes a timestamped directory under
`~/.cache/mojito-storage-validation-20260914`. `--output`, `--socket`, `--user`,
`--mysql`, and `--repo` can override their respective local defaults. No password
is printed or accepted as a command-line argument.

## Checks performed

- Exact V121 and V122 DDL applied to a populated representative prior schema,
  with source fingerprints before and after the upgrade.
- Prior tables assembled from actual repository migration statements. This is
  deliberately a relevant schema subset, not the entire application history.
- Existing index plans for archive keyset/high-water queries and all incoming
  foreign-key reference lookups, plus the AI review usage soft-reference index.
- Equal timestamp pagination across 8,003 rows using a fixed upper bound,
  including an insertion beyond that bound during the scan.
- Both insertion/deletion orders for all ten incoming task foreign keys, with a
  real InnoDB lock wait observed for each of the twenty races.
- Checkpoint lock serialization, stale-token rejection, atomic task deletion and
  cursor rollback/commit, migration pause revocation, and migration evidence FK.
- Migration/source hashes and successful removal of the disposable database.

`result.json` contains the outcome. The directory also contains generated schema,
seed and upgrade SQL, `SHOW CREATE TABLE` output, JSON query plans, the incoming
FK inventory, and race observations. A failed probe still records its outcome
and attempts to drop its disposable database.

## Boundaries

These are SQL-layer probes. They do not execute Hibernate, the Java archive or
migration services, the Flyway application lifecycle, Azure, staging, a full
production-sized dataset, or the full migration history. The token/transaction
probes exercise database primitives used by the Java protocols; application
tests must independently establish that the services use them correctly.

The soft-reference probe intentionally demonstrates that MySQL permits deleting
a task referenced only by `ai_review_request_usage`. Java task-type exclusions
remain necessary. Parent/child and durable references are preserved by the
archive worker's narrow eligibility policy.

Rerun in CI after changing either migration or the relevant SQL, or locally only
when the user explicitly requests database validation. The recorded hashes
identify the exact source state covered by each result.

## Java and maintenance startup lanes

The archive repository lane runs the existing Java/Spring/JPA integration test
with actual Flyway history through V122 and mocked Azure. It creates its own
database and scoped accounts, then removes them. It invokes Maven and must run
without another build using the same checkout:

```sh
python3 dev-tools/storage-retention/run_archive_mysql_test.py
```

After that lane supplies a current Surefire classpath report, the maintenance
smoke uses already compiled application classes and the actual
`maintenance/entrypoint.py` arguments. It invokes no Maven. Both promotion modes
must start with the required services present, isolated idle RAM Quartz, no
scheduled migration/archive worker, and no database changes or Azure requests:

```sh
python3 dev-tools/storage-retention/run_maintenance_startup_mysql.py
```

To additionally start a previously packaged executable application with the
actual launcher arguments, use:

```sh
python3 dev-tools/storage-retention/run_maintenance_startup_mysql.py \
  --executable-jar webapp/target/mojito-webapp-0.111-SNAPSHOT-exec.jar
```

The smoke creates a separate database, applies the full Flyway history, seeds
old tasks and expired/permanent blobs, and compares every table's row and schema
fingerprints before and after startup. It points the real Azure SDK at a local
HTTP sentinel, observes each application for at least 6.5 seconds after startup,
then stops only its own processes and removes its database/accounts. Results,
commands, source hashes and logs are saved under the timestamped
`maintenance-startup` directory. `--prepare-only` validates the compiled
classpath and writes its external probe without running Java or touching MySQL.

A passing run establishes startup and database integration for its recorded artifact, not real Azure
connectivity, Docker/Kubernetes behavior, a live maintenance fence, or staging
load and recovery. Maintenance uses `ddl-auto=none`; the reviewed Flyway/schema
preflight remains required before live launch.
