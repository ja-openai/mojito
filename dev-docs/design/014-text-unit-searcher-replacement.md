# TextUnitSearcher Replacement Options

Status: Hibernate Criteria candidate implemented with opt-in shadow checks

## Context

`TextUnitSearcher` still builds a large native SQL query through
`com.github.pnowy.nc:nativeCriteria-core:2.0`. Mojito is now on Java 21, Spring Boot 3.5.14,
Hibernate ORM 6.6.49.Final, and Jakarta Persistence API 3.1.0.

The hard part is not the filtering itself. It is preserving the searcher's join graph:

- `tm_text_unit x locale`, restricted by `repository_locale`
- left joins to current variants by `(text unit, locale)`
- left joins to the latest extraction mapping by `(text unit, asset.last_successful_asset_extraction)`
- optional usages aggregation for `asset_text_unit_usages`
- plural-form filtering by `(plural form, locale)`

## Sources Checked

- Jakarta Persistence 3.2 added Criteria entity joins and `CriteriaSelect`; it is tied to Jakarta EE
  11-era providers such as Hibernate 7 / EclipseLink 5. Mojito's current compile API is 3.1, so the
  new standard methods are not available without a platform move.
  <https://jakarta.ee/specifications/persistence/3.2/>
- Hibernate 6.6 already exposes non-standard Criteria extensions through
  `org.hibernate.query.criteria`, including entity joins, subquery-from support, and CTE hooks.
  <https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/query/criteria/HibernateCriteriaBuilder.html>
- Hibernate HQL abstracts many database functions and exposes `listagg()` as the portable string
  aggregation spelling for dialects that use different native names.
  <https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#hql-aggregate-functions-orderedset>
- Querydsl 5.1.0 is current as of Jan 2024 and gives a type-safe fluent API over JPA/SQL, but adds
  generated Q-types and a new dependency layer on top of Hibernate.
  <https://github.com/querydsl/querydsl>
- jOOQ is a strong SQL DSL and renderer, but it moves this searcher out of JPA/HQL and into an
  explicit SQL model.
  <https://www.jooq.org/doc/latest/manual/sql-building/>

## Implementation Added

### Hibernate Criteria Extension

Package:
`com.box.l10n.mojito.service.tm.search.replacement.hibernatecriteria`

Entry point:
`HibernateCriteriaTextUnitSearcher`

What it does:

- Hibernate 6.6 can express the old native join graph with `JpaFrom#join(Class, SqmJoinType)` and
  `JpaCrossJoin`.
- We can keep dialect rendering in Hibernate rather than hand-writing MySQL/HSQL/Postgres SQL.
- We can drop `ANY_VALUE` by grouping all non-aggregate projections when usages are joined.
- Usages can use Hibernate's `listagg` Criteria extension instead of raw `GROUP_CONCAT`.
- Existing `TextUnitSearcher` can shadow-run this implementation when
  `l10n.text-unit-searcher.shadow.enabled=true`.
- When enabled, the shadow service logs an `INFO` startup line and an `INFO` timing line for every
  shadowed search/count comparison, including native and Hibernate Criteria durations in
  milliseconds.
- Shadow mismatches are logged as errors and become test failures when
  `l10n.text-unit-searcher.shadow.fail-on-mismatch=true`.
- The HSQL `webapp` unit suite passes with those shadow flags enabled, so the existing search tests
  now exercise the replacement query alongside the current nativeCriteria query. Run parity checks
  with `-Duser.timezone=UTC` while the native implementation is still the comparison baseline.
- The HSQL `cli` unit suite also passes with those shadow flags enabled. While checking that, the
  CLI git helper needed a small linked-worktree fix for JGit 4.5.2 so Codex worktrees resolve the
  common object database and work tree correctly.
- In a PDT JVM, shadow comparison found a seven-hour difference only on `createdDate` /
  `tmTextUnitCreatedDate` milliseconds. The legacy native mapper reads dates from
  `CriteriaResult#getDate` and wraps them through `JSR310Migration.newDateTimeCtorWithDate`, making
  the baseline sensitive to the JVM default zone. The Hibernate Criteria path reads
  Hibernate-managed `ZonedDateTime` values directly, which is the better behavior. When Criteria is
  promoted to primary, call this out as a visible timestamp-mapping cleanup rather than a query
  parity regression.
- MySQL shadow comparison also exposed a legacy native DTO projection issue for boolean columns such
  as `includedInLocalizedFile`. The old native search still filtered rejected/not-rejected rows in
  SQL with `tuv.included_in_localized_file = ...`; the mismatch was in converting selected boolean
  values from the native result row into `TextUnitDTO`. The mapper now accepts MySQL/HSQL textual and
  BIT string representations while nativeCriteria remains the baseline. The Hibernate Criteria path
  reads typed `Boolean` values directly.

Tradeoffs:

- This is not pure Jakarta Persistence 3.1 Criteria. It depends on Hibernate's Criteria extension
  interfaces.
- The code is verbose. It should be split further only if more filters are added or parity fixes
  make the current helper boundaries too large.
- We still need broad parity runs against current `TextUnitSearcher` on MySQL fixtures.

Recommendation:

This is the best production direction if we want a "real criteria builder" now without waiting for
Spring Boot / Hibernate 7.

## Options Not Kept

### HQL Entity Query

HQL was initially useful to understand the query shape, but it is not kept in code. It is
string-based, field renames fail later than Criteria compile errors, and dynamic filters remain
custom query-building code.

### Local Native SQL Builder

Native SQL is rejected for now; no production-code prototype is kept.

- Bound values can be made injection-safe, but a local SQL builder is easier to regress into unsafe
  dynamic identifiers or fragments than Criteria/HQL.
- It keeps dialect work in Mojito. `GROUP_CONCAT`, `REGEXP_LIKE`, boolean literals, and pagination
  would all need HSQL/MySQL/Postgres-specific handling.
- It removes `nativeCriteria-core` without removing the real maintenance problem: handwritten SQL.

## Options Not Prototyped Yet

- Querydsl JPA: worth a spike only if we want generated query types across more repositories. It
  does not obviously buy enough over Hibernate Criteria for this one searcher.
- jOOQ: worth considering if Mojito deliberately adopts SQL-first data access. For this targeted
  replacement, it is more dependency and build tooling than needed.
- Waiting for Jakarta Persistence 3.2 / Hibernate 7: eventually attractive because entity joins
  become standard API, but it couples this cleanup to a larger platform upgrade.

## Next Steps

1. Run the same shadow suite against MySQL fixtures.
2. Compare generated SQL on HSQL and MySQL for the Criteria implementation.
3. Decide whether `listagg` fully replaces current `GROUP_CONCAT` semantics for usages ordering and
   separators.
4. Promote `HibernateCriteriaTextUnitSearcher` to the primary implementation, then delete
   `nativeCriteria-core` after parity is stable.
