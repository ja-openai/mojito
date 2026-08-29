# Review Project Decision Integrity Audit

> **Scope correction:** this bounded decision-history tool is retained for recent structural checks
> and as a timing canary. Its 30-second adjacency heuristic is not the complete repeated-target
> detector and must not be used to enumerate or confirm carryover defects. Complete discovery uses
> `translation.find_repeated_current_targets`; see
> `dev-docs/design/030-repeated-current-target-discovery.md`.

## Purpose

Give Codex and other authenticated operators a narrow Mojito-owned interface for investigating
recent Review Project decisions and deterministic structural risks. The client must not connect to
MySQL, submit SQL, or mutate translations.

The first interface is the read-only MCP tool
`review_project.audit_decision_integrity`. A REST wrapper and a scheduled job are intentionally not
part of this slice. Both can delegate to the same service later if an observed operational need
justifies them.

## Tool contract

The tool is available through the existing synchronous `/api/mcp` transport and requires an admin
user. Its inputs are:

- `fromInclusive`: required ISO-8601 instant with a UTC offset
- `toExclusive`: required ISO-8601 instant with a UTC offset
- `carryoverDetailLimit`: optional, defaults to 50, range 0-50
- `structuralDetailLimit`: optional, defaults to 50, range 0-50

The requested window must be positive and no longer than 48 hours. A normal daily call uses an
explicit 26-hour UTC window. The service echoes the normalized bounds in its response.

Example arguments:

```json
{
  "fromInclusive": "2026-08-19T20:00:00Z",
  "toExclusive": "2026-08-20T22:00:00Z",
  "carryoverDetailLimit": 50,
  "structuralDetailLimit": 50
}
```

The response starts with status and uncapped summary counts, followed by capped evidence. It
includes:

- total decisions and distinct reviewers, Review Projects, and text units
- unattributed decisions and expected targetless terminology decisions
- carryover candidate-pair and grouped-run totals
- deterministic structural-finding and affected-decision totals by kind
- a separate source-equals-target review-needed count
- durable decision, Review Project text-unit, and TM text-unit ids
- reviewer/project/locale context, timestamps, delta seconds, bounded redacted previews, and
  canonical Review Project links
- explicit truncation flags for each evidence list

At most 20 decision rows are included inside any one reported carryover run; the run retains its
uncapped pair/decision counts and states when its row evidence is truncated. This keeps a single
pathological rapid sequence from defeating the outer 50-run response cap.

`PASS` means that these checks found no candidates. `CANDIDATES_FOUND` means human review is
needed; it is not an automatic bad-translation verdict.

## Data access

The application performs one read-only bounded query. It does not expose arbitrary SQL and does
not perform per-row entity lookups.

For predecessor context, the query reads decisions from
`[fromInclusive - 30 seconds, toExclusive)`, computes `LAG` partitioned by Review Project and
effective reviewer, and returns only current rows in `[fromInclusive, toExclusive)`. Effective
reviewer is `COALESCE(last_modified_by_user_id, created_by_user_id)`. Ordering is deterministic by
decision timestamp and decision id.

The query keeps decisions with a null reviewer or variant so coverage and structural totals include
every persisted `DECIDED` row in the window. Source, decision target, current target, project,
reviewer, locale, and repository context are joined in the same query. Migration V103 adds the
bounded-scan index `(decision_state, last_modified_date, id)`.

## Carryover canary

A later row is a candidate only when all of these are true:

1. Its predecessor is in the same Review Project and effective-reviewer partition.
2. Its exact source text differs from the predecessor source.
3. Its exact decision target equals the predecessor decision target.
4. It is no more than 30 seconds later, including exactly 30 seconds.
5. Its decision target still exactly equals the current target for the text unit/project locale.

Comparisons happen in Java so database collation cannot turn case or accent differences into exact
matches. There is no minimum source or target length. Adjacent candidate edges are grouped only
when the next edge points to the previous edge's later decision. Similar but separated pairs remain
separate runs.

The response retains legitimate-looking duplicates. It labels simple case/punctuation,
singular/plural, and close-source variants as possibly legitimate and always asks for human review.

## Broader structural review

Every returned decision is checked against its current target in memory. Deterministic finding
types cover:

- missing or whitespace-only current targets
- decision/current variant locale mismatches
- printf, double-brace, and dollar-placeholder multiset mismatches
- ICU parse or argument-name/type mismatches
- markup tag-name/count mismatches and unbalanced nesting

ICU plural/select branch labels and translated branch text are not treated as placeholders. Markup
attributes and tag order are intentionally ignored to reduce false positives. Targetless
terminology and term-candidate `PM_RESOLUTION` rows with a null decision variant are counted but are
not reported as missing-target defects.

Exact source-equals-target in a non-source locale is reported separately as `REVIEW_NEEDED`.
Product names, model names, colors, and short actions may correctly remain unchanged.

This is not a comprehensive linguistic review. The service never rejects, approves, overwrites, or
otherwise changes a translation, and it never creates bad-translation incidents.

## Separate remediation boundary

Audit findings are never fed automatically into a write path. When an operator has separately
reviewed an exact correction, the admin-only `translation.apply_corrections` MCP tool (or its
`POST /api/admin/translation-corrections/apply` REST equivalent) provides a distinct guarded
compare-and-set operation.

Each correction must supply the Review Project id, Review Project text-unit id, repository id and
name, locale, TM text-unit id, expected current variant id, exact expected old target, and exact
replacement. The application resolves the identity to locate and pessimistically lock the current
translation row, then locks and reloads the complete Review Project/repository identity graph before
revalidating every guard, the expected variant, and the byte-for-byte old target. A missing or
changed guard is an ordered `CONFLICT` result and never a write. The expected old target is not
normalized; only the replacement follows Mojito's normal NFC storage semantics. Only target-locale
translation Review Projects are eligible: `EMERGENCY`, `NORMAL`, and `BUG_FIXES` remain supported,
while repository source locales, `TERMINOLOGY`, `TERM_CANDIDATE`, and fail-closed `UNKNOWN` are
rejected before the current translation is locked or written.

Applied rows use the ordinary `TMService` persistence path without an override, always create a
`REVIEW_NEEDED` variant, preserve the previous inclusion flag and comment, and do not rewrite the
Review Project decision row. The transaction flushes and clears its persistence context, re-reads
the current row, and verifies the durable identity, variant, target, status, and inclusion flag
before returning `APPLIED`. Any failed read-back verification rolls back that row. Independent
per-row transactions allow a bounded batch to return structured applied/conflict/error outcomes;
conflict and error results contain stable codes and the requested identity only, not translation payloads,
credentials, SQL details, or stack traces. An unexpected failure at the transaction boundary returns
`CORRECTION_OUTCOME_UNKNOWN`; the caller must re-read the guarded current variant and exact target
before deciding whether to retry. A response can also be lost after commit, so higher-level durable
run lineage and rollback evidence remain the caller's responsibility.

The mutation MCP adapter checks the admin role before typed argument conversion. Both MCP and REST
then share request-wide service validation before any row transaction: at most 1,000 rows, 255
characters for repository name and locale, 1,000,000 characters for either target field, and
8,000,000 characters across all string fields. The shared `TMService` write path logs ids but never
translation content.

The authenticated `/api/mcp` route also enforces a non-overridable 32 MiB raw request ceiling after
route authorization but before MVC body binding or JSON parsing. It rejects oversized declared
lengths and independently bounds the servlet stream, covering chunked, missing, or inaccurate
`Content-Length` values with HTTP 413. `l10n.mcp.max-request-bytes` may lower the limit but cannot
raise it. The ceiling preserves the existing 20 MiB decoded image-upload operation after base64 and
envelope overhead while strictly bounding pre-parse allocation. It is intentionally independent of
the correction service's character counts, so heavily escaped or unusually encoded/non-ASCII JSON
may reach the raw-byte ceiling before the 8,000,000-character semantic limit.

## Audit security and payload bounds

- `/api/mcp` requires authentication; the audit tool additionally checks the current user's admin
  role before interpreting the requested time bounds or querying.
- The descriptor declares the tool read-only and dry-run by default, while server-side code is also
  transactionally read-only.
- Full source and target payloads are not returned. Previews are single-line and limited to 160
  Unicode code points. A preview that matches credential labels, authorization/cookie headers,
  connection strings, private keys, known token forms, credential-bearing URLs, or long
  high-entropy tokens is suppressed entirely and marked `redacted`.
- Detail caps do not alter totals.

## Persistence limitation

`review_project_text_unit_decision` contains one mutable row per Review Project text unit. The
timestamp is the latest row modification, not an immutable event log. The audit therefore covers
latest persisted `DECIDED` mutations in the requested window; an overwritten intermediate click
cannot be reconstructed and the response states this limitation.

## Rollout

Before using the tool as the daily operator path:

1. Deploy the application code and V103 index.
2. Verify the production-equivalent `EXPLAIN` plan and bounded-query latency.
3. Configure the existing authenticated Mojito MCP connection for Codex and confirm admin denial
   for a non-admin account.
4. Run the 26-hour investigation through MCP and compare summary counts with one controlled
   application-side verification.

Before enabling guarded correction for an operator workflow, also verify the deployed application
advertises `translation.apply_corrections`, rejects a non-admin call, and returns a no-write conflict
for a deliberately stale request. A real staging correction requires separate authorization and
must be independently re-read to confirm the returned variant is current and `REVIEW_NEEDED`.

The optional daily Quartz canary invokes this same audit service and publishes bounded, tag-free
Micrometer summaries; it does not add a second query or persist remediation. Keep
`l10n.review-project.decision-integrity-canary.enabled=false` until the V103 query plan and latency
have been validated. When enabled, the default schedule is 05:15 UTC and the default lookback is
26 hours.
