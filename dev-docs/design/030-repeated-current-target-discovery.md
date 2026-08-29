# Repeated Current Target Discovery

## Purpose

Provide a complete, read-only candidate feed for the simple carryover signal:

```text
same target locale
and exact target A = exact target B
and exact source A != exact source B
and both targets are current
```

Repeated targets are candidates for semantic review, not confirmed defects. Product names, short
actions, grammatical variants, and intentionally shared translations remain in the feed so a
reviewer or semantic classifier can preserve them.

## Tool contract

The admin-only MCP tool is `translation.find_repeated_current_targets`. It scans all current
non-source translations by default. `reviewProjectId` optionally requires both matching text units
to belong to the same Review Project and returns Review Project lineage.

There is no time window, reviewer filter, adjacency rule, or minimum target length. Status and
`included_in_localized_file` are returned as evidence but do not affect discovery.

Results use bounded keyset pages so the caller can export every candidate without imposing a total
cap:

- first page: omit `afterCurrentPointerId`, `highWaterCurrentPointerId`, and
  `highWaterReviewProjectTextUnitId`
- later pages: pass the prior response's `nextAfterCurrentPointerId` and unchanged
  `highWaterCurrentPointerId`; for a Review Project scan, also pass the unchanged
  `highWaterReviewProjectTextUnitId`; always pass the unchanged `scanToken`
- stop only when `complete` is true

The scan token binds the scope and high-water marks to an Envers current-pointer audit watermark
plus a constant-memory SHA-256 fingerprint of every mutable input to candidate membership. The
fingerprint includes each eligible current-pointer tuple, database-computed exact target and source
hashes, the stored target hash, text-unit/asset/repository/source-locale relationships, active
asset/repository and configured-locale filters, and Review Project membership for scoped scans.
`LONGTEXT` values are hashed by MySQL and never cross JDBC during fingerprinting. The first page
fingerprints membership before and after the hash-integrity preflight. Each later page checks for a
newer indexed pointer-audit revision. Before returning `complete=true`, the service also rechecks
the audit row count/revision and streams the membership fingerprint again. These checks catch a
revision that committed out of revision-number order, durable direct-SQL pointer drift, and durable
changes to non-pointer membership inputs. If any check differs, the response is
`RESTART_REQUIRED`, `complete=false`, contains no candidates or continuation cursor, and tells the
caller to discard every prior page and restart without cursors, high-water marks, or token. The
service uses read-committed statement visibility; the token is consistency metadata, not an
authorization credential.

Each current translation is emitted once, rather than emitting every pair. `clusterKey` is a stable
SHA-256 of target-locale ID plus the database-computed exact-target SHA-256, so callers can regroup
members across pages without quadratic pair growth or materializing target `LONGTEXT` in Java.

## Current-row definition

A row is active/current for this detector when it is selected by
`tm_text_unit_current_variant`, its variant identity agrees with the pointer's text-unit and locale
identities, its asset and repository are not deleted, its target locale is configured for the
repository, and the target locale is not the repository source locale.

Latest-extraction usage is not an eligibility predicate. Mojito can retain a current translation
for a temporarily unused text unit, and data-integrity review must not silently discard it. This is
different from a localized-file usage search.

## Exactness and performance

The query does not add a permanent index to `tm_text_unit_variant`. Each candidate page
materializes a fixed-width `eligible_current` CTE containing pointer IDs, text-unit IDs, locale IDs,
variant IDs, timestamps, and stored target hashes. MySQL builds an ephemeral locale/hash lookup for
that execution, then the query rejoins variants and text units by primary key for authoritative
exact comparisons. The CTE deliberately excludes target and source `LONGTEXT`. Review Project
scope starts from the project's existing index prefix and deduplicates repeated project membership;
it does not require another `review_project_text_unit` index.

`DISTINCT` is semantically redundant because the current-pointer ID is unique, but it is an
intentional optimizer barrier: it prevents MySQL from expanding the CTE into a prohibitively
expensive repeated join. A staging `EXPLAIN ANALYZE` must still confirm one CTE materialization and
an ephemeral locale/hash lookup before rollout or after a MySQL upgrade.

This shifts cost from every translation write to the rare admin scan. A whole-database page may
materialize more than a million rows and use an on-disk temporary table. Every keyset page repeats
that work because MCP calls are stateless, so this is an operational audit rather than an
interactive request path. The service applies a 180-second Spring transaction timeout. That is not
a wall-clock cancellation guarantee for JDBC result streaming, so production must also retain
bounded datasource read/socket and request timeouts. Staging must measure both scopes and a complete
paginated scan before production use.

V1 already gives `tm_text_unit_current_variant_aud` a foreign key on `rev`. MySQL creates a
leading-`rev` index for that constraint, and InnoDB secondary indexes carry the table's `(id, rev)`
primary-key columns. The revision probe therefore uses the existing effective `(rev, id)` access
path without another audit-table index.

The stored MD5 narrows peer lookup. The first page fails closed if a current in-scope target has a
missing or stale hash. Hash equality is never proof of equality: the query then compares target
content byte-for-byte and compares source content byte-for-byte with null-safe inequality. This
avoids case- or accent-insensitive database collation changing the rule.

The current-pointer high-water mark excludes current pointers created after a scan begins. A second
Review-Project-text-unit high-water mark excludes later project-membership rows from scoped scans.
The token invalidates the complete scan when the durable candidate-membership fingerprint changes;
every later remediation must still re-read and compare the current variant ID and exact target
before writing.

Production queries never return an unbounded `LONGTEXT` value through JDBC. Target, source,
comment, string ID/name, baseline, reviewed, and decision text are projected as a
database-computed SHA-256, code-point length, and at most 256 code points of prefix. `exactText` is
present only when that prefix contains the complete non-sensitive value. Larger values return a
bounded `preview`, set `truncated=true`, and require native follow-up through the accompanying
durable text-unit/variant IDs. Secret-like prefixes suppress both fields. Review Project lineage is
capped at 20 rows per candidate text unit in SQL; `reviewProjectEvidenceTotalCount` and
`reviewProjectEvidenceTruncated` make omitted durable lineage explicit.

The restart token validates candidate membership, not an immutable copy of every descriptive
field. Status and inclusion flags, comments, names and paths, locale labels, user attribution, and
Review Project baselines/decisions are bounded context read while each page is generated. They may
change without invalidating an otherwise complete candidate scan. Treat that context as page-time
evidence and reread the durable IDs plus the exact current variant/source before any remediation.

A missing or stale current-target `content_md5` is an expected fail-closed operational state. MCP
returns HTTP 200 with `isError=true`, code `STALE_CURRENT_TARGET_HASHES`, the affected count, and a
repair action instead of leaking an unchecked exception as HTTP 500.

Implementation-time validation used MySQL 8.0.43 with 1,283,196 current pointers (high-water ID
1,284,078) and no added candidate or lineage indexes. The fixed-width CTE materialized about 1.23
million eligible rows, used an ephemeral `(locale_id, content_md5)` lookup, spilled to an on-disk
temporary table, and
returned 2,001 IDs in 17.0 seconds in a warm-cache plan run using the production matching core. The
whole-scope hash-integrity preflight completed in 15.3 seconds and found no invalid current target
hashes. On a 22,262-row Review Project, repeated local runs put the candidate page, hash preflight,
and fingerprint in the tens of milliseconds; the scoped audit watermark scanned 1.33 million audit
rows in under two seconds. These local results establish that the query remains runnable without
adding write amplification to `tm_text_unit_variant`; they are not final response-time or
production capacity evidence. Production-equivalent global/scoped plans, timeout behavior, and one
complete tokenized scan remain staging gates.

## Review Project lineage

Review Project scope returns each matching row's:

- baseline variant from `review_project_text_unit.tm_text_unit_variant_id`
- reviewed variant from `review_project_text_unit_decision.reviewed_variant_id`
- decision-row variant from `review_project_text_unit_decision.variant_id`
- current variant from `tm_text_unit_current_variant`
- decision state/version/timestamps and effective reviewer as provenance

None of that lineage changes discovery eligibility. In particular,
`decisionVariantMatchesCurrentVariant` reports only physical variant identity; callers must inspect
`decisionState` separately and must not treat a `PENDING` row as an accepted decision. This keeps
pre-existing baseline duplicates, review-introduced changes, and later superseding work
distinguishable without pretending the repeated target alone proves which step was wrong.

## Safety

The descriptor and service are read-only. The tool never approves, rejects, or changes a
translation. Short ordinary source, target, and string-ID evidence remains exact, including
whitespace and Unicode, while large fields are deliberately bounded as described above.
Secret-like text in any of these fields is suppressed and replaced by SHA-256 and
code-point-length metadata with `requiresNativeReview=true`. Any correction remains a separate
guarded operation that must
compare project, locale, text unit, current variant, and exact old target immediately before
writing.
