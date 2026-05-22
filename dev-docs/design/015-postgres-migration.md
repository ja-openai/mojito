# Postgres Migration

## Current Scope

This first migration slice makes Mojito bootstrappable on PostgreSQL without changing the core data model:

- keep the existing numeric primary keys and Hibernate entity relationships;
- keep HSQL as the default fast test database;
- replace the runtime MySQL driver/Flyway module with PostgreSQL equivalents;
- add a PostgreSQL Spring profile and PostgreSQL Flyway baseline migrations;
- update the Docker compose paths to use PostgreSQL;
- add an opt-in Testcontainers mode that runs Spring integration tests against a real PostgreSQL server when Docker is available.

The JSON export/import tool and UUID v7 or Snowflake-like identifier model are intentionally deferred. Those should be designed as logical cross-instance IDs before changing primary keys, so the migration can be tested independently from identity semantics.

## Runtime Profile

Use the `postgres` Spring profile for local PostgreSQL:

```properties
spring.profiles.active=postgres
spring.datasource.url=jdbc:postgresql://localhost:5432/mojito
spring.datasource.username=mojito
spring.datasource.password=mojito
```

The profile disables Hibernate DDL generation and runs Flyway from `classpath:db/migration/postgresql`.

## Type Choices

The current `@Lob byte[]` columns are mapped to PostgreSQL `bytea`:

- `application_cache.value`
- `image.content`
- `mblob.content`
- Quartz serialized job payload columns from the PostgreSQL Quartz schema

This is the simplest equivalent for the current Hibernate model. PostgreSQL stores large `bytea` values through TOAST internally while keeping normal row/transaction semantics for the application. If Mojito needs to store artifacts that approach PostgreSQL's per-value limit, the better follow-up is to move those payloads to blob/object storage and keep metadata in the database, not switch the app to PostgreSQL Large Objects by default.

The generated schema also needs a small Postgres-specific adjustment for indexed unbounded text. The legacy MySQL schema used prefix indexes for `tm_text_unit.name` and `tm_text_unit.plural_form_other`; the PostgreSQL baseline uses hash indexes for those exact-match paths instead of plain btree indexes over potentially large `text` values.

## Verification

The default test path stays HSQL-backed:

```bash
mvn -pl webapp -Pno-local-config,!frontend test
```

The PostgreSQL migration smoke test is opt-in because it requires Docker:

```bash
mvn -pl webapp -Pno-local-config,!frontend \
  -Dtest=com.box.l10n.mojito.db.PostgresFlywayMigrationTest \
  -Dmojito.test.postgres=true test
```

Testcontainers starts a real PostgreSQL container, runs the PostgreSQL Flyway migrations, and checks seeded locale/plural data plus quoted keyword tables. It also performs a narrow infrastructure smoke test for the persistent support tables: Quartz's `PostgreSQLDelegate` can store a durable job/trigger in the `QRTZ_*` tables, and Spring Session JDBC can create, reload, index, and serialize an attribute into `SPRING_SESSION_V2`.

To run the broader Spring-backed suite against PostgreSQL instead of HSQL, enable the PostgreSQL test flag:

```bash
mvn -Pno-local-config -Dmojito.test.postgres=true test
```

The flag activates the `postgres-tests` Maven profile. That profile points Spring datasource configuration at Testcontainers' JDBC PostgreSQL driver, disables HSQL schema/data initialization, and enables the PostgreSQL Flyway baseline migrations from `classpath:db/migration/postgresql`.

## Follow-Up Work

- Run the full PostgreSQL integration suite with Docker available and fix remaining dialect gaps.
- Add a Spring app-context test for clustered persistent Quartz, especially multi-quartz scheduling, restart/recovery behavior, and the unique-id reschedule cleanup path.
- Decide whether to remove the legacy MySQL Flyway path entirely or keep it as a historical migration/import source.
- Design JSON repo export/import around stable logical IDs, repository history, and cross-instance conflict handling.
- Add UUID v7 or Snowflake-like IDs as logical public identifiers before considering primary key changes.
