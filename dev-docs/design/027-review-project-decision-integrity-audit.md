# Review Project Decision Integrity Audit

## Purpose

Give Codex and other authenticated operators a narrow Mojito-owned interface for investigating
recent Review Project decisions. The client must not connect to MySQL, submit SQL, or mutate
translations.

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

## Security and payload bounds

- `/api/mcp` requires authentication; this tool additionally checks the current user's admin role
  before parsing or querying.
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

Keep scheduling deferred until the interactive operator flow is stable. If scheduling becomes
useful later, invoke the same audit service and publish bounded summaries; do not add a second query
or persist automatic remediation.
