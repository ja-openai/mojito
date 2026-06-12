# Glossary Raw Term Index

## Purpose

Glossary extraction should not be owned by a single glossary run. Mojito needs a
durable extraction index that records terms observed in product strings, then a
separate review layer that lets one or more glossaries curate those candidates
into glossary terms.

This keeps recurring updates server-side and idempotent:

1. refresh the extracted-term index for a repository scope
2. derive or import review candidates from extracted terms, AI, humans, or external systems
3. compute glossary suggestions from candidates and existing glossary terms
4. persist accepted mappings back to the glossary term index link table

## Layers

### Extracted-Term Index

The extracted-term index is repository/text-unit oriented and glossary-neutral.
It is owned by extraction refreshes only. It intentionally does not carry a
definition or meaning review state.

- `term_index_extracted_term`
  - normalized candidate key
  - display term
  - source locale tag, defaulting to `en` when the source locale is not known
  - aggregate occurrence/repository counts
  - first/last seen timestamps
- `term_index_occurrence`
  - extracted term
  - product `tm_text_unit_id`
  - repository and optional asset
  - matched text and source span
  - source hash or text-unit version marker
  - extractor id/method and confidence

The refresh path should delete and reinsert occurrences for changed text units,
then recompute extracted-term aggregates. It should not require the caller to
resend a full candidate set.

### Candidate Review Layer

The review layer is the meaning/proposal layer. It can be derived from an
extracted term, or it can be a manual/external submission when extraction missed
the term entirely.

- `term_index_candidate`
  - optional extracted-term id
  - source locale tag, normalized key, term, and optional label
  - source type/name/external id
  - optional confidence, definition, rationale, term type, part of speech,
    enforcement, and do-not-translate recommendation
  - metadata JSON for source-specific context such as screenshot image keys,
    code paths, product area, or extraction notes
  - stable candidate hash for idempotent updates

This lets external tools, product teams, or imported seed lists provide high-context terms
while still forcing the final glossary decision through the same candidate
curation path as occurrence-based extraction. If an external system submits two
distinct proposals with the same source string, they stay as separate candidates
when their source id/hash or meaning metadata differs; they do not merge into a
single extracted term.

Candidates intentionally stay source-side for now. They can recommend
do-not-translate, enforcement, type, and meaning, but they do not own reusable
target translations. Accepted glossary terms and their backing glossary
repository text units remain the place where locale-specific target terms live.
Projects that need shared translations should compose the relevant glossaries;
if the same source term needs different legal/product/register translations,
that should be expressed as separate glossary-owned terminology rather than as a
candidate-level translation table.

### Glossary Curation

Glossary terms remain backed by glossary repository text units. A candidate
can map to multiple glossary terms, including multiple terms in the same
glossary, so the relationship should be a join table:

- `glossary_term_index_link`
  - glossary term metadata id
  - term index candidate id
  - relation type string such as `PRIMARY`, `ALIAS`, or `RELATED`
  - optional confidence/rationale

Accepted mappings belong in `glossary_term_index_link`. Glossary-specific ignore
decisions belong in `glossary_term_index_decision`; they keep rejected candidates
from reappearing without mutating the glossary-neutral extraction index or shared
candidate source data.

### Refresh Runs

Runs are useful for audit/progress, but they should not own candidate truth.

- `term_index_refresh_run`
  - scope, status, counts, and error message
- `term_index_refresh_run_entry`
  - exact distinct extracted terms touched by a run
  - used to recompute aggregates in pages without keeping a run-wide id set in
    application memory

Runs can explain what happened during a refresh. Extracted terms, candidates, and
accepted glossary links remain the current materialized truth.

## Incremental Cursor

`TMTextUnit` currently has `created_date` but no `last_modified_date`; changes
normally create a new text unit because the identity includes name, content, and
comment. Use a per-repository cursor with timestamp plus id tie-breaker:

- `last_processed_created_at`
- `last_processed_tm_text_unit_id`
- `last_successful_scan_at`
- `lease_owner`
- `lease_token`
- `lease_expires_at`
- `current_refresh_run_id`

Fetch new text units ordered by `(created_date, id)`:

```sql
where repository_id = ?
  and (
    created_date > :lastProcessedCreatedAt
    or (
      created_date = :lastProcessedCreatedAt
      and id > :lastProcessedTmTextUnitId
    )
  )
order by created_date asc, id asc
limit :batchSize
```

For each changed text unit, remove old raw occurrences for that text unit and
insert the current matches. A periodic full refresh remains the drift repair
mechanism for deletes, usage-state changes, extractor changes, and any source
data that does not advance `tm_text_unit.created_date`.

The cursor also owns per-repository refresh concurrency. A refresh run acquires a
repository cursor with a conditional update, extends the lease as batches are
checkpointed, and releases it on completion. Expired leases can be acquired by a
later run, so a crashed worker does not permanently block the repository. The
lease token is checked on checkpoint/release so an old worker cannot keep writing
after another run has taken over.

## Current Refresh Foundation

`TermIndexRefreshService` provides the server-side refresh path:

- scans used source text units for selected repositories
- keeps a per-repository `(created_date, tm_text_unit_id)` cursor
- uses a per-repository cursor lease so overlapping refreshes for the same
  repository do not run concurrently
- writes run state, cursor leases, checkpoints, and batch occurrence changes in
  short transactions rather than one refresh-wide transaction
- stages affected extracted terms per refresh run, then recomputes aggregates
  from that staged list in pages
- supports full refresh by deleting repository occurrences before re-indexing
- writes lexical occurrences for title-case, uppercase, and camel-case signals
- writes `EXTERNAL_GLOSSARY_IMPORT` occurrences by matching approved enabled
  glossary terms against product source text during the same refresh
- recomputes entry occurrence and repository counts for affected entries

`/settings/system/glossary-term-index` exposes the glossary automation dashboard
on top of that foundation: extract terms, run AI review for unreviewed extracted
terms, generate missing candidates from accepted extracted terms, and monitor
recent automation jobs. AI review and candidate generation progress is persisted
in `term_index_automation_run`, with the backing pollable task kept only for
task execution and direct task polling. The dashboard job tables read the
term-index-specific run table so they can recover progress after closing the
modal or reloading the page without scanning generic pollable-task history.
`/settings/system/glossary-term-index/terms` exposes the manual extracted-term investigation view, and
`/settings/system/glossary-term-index/candidates` exposes the stored
`term_index_candidate` review queue. The candidate queue searches candidate rows,
lets PM/admin users edit candidate metadata, change candidate review status in
bulk for selected rows, and route selected candidates into review projects. Source occurrences remain
evidence linked through the optional extracted-term id; they are not the row
identity for the candidate page. These system surfaces do not directly activate
glossary terms; candidate acceptance or PM resolution performs that promotion.
Together the system pages can start asynchronous repository-scoped refreshes,
inspect repository cursors and capped recent-run lists, search virtualized rows
with Workbench-style result limits, and drill into capped source occurrence
examples where they exist.

## Operational Visibility

Long-running term-index work emits structured info logs at job start, batch or
phase progress, completion, and failure. The log messages include operational
context such as refresh-run id, pollable-task id, repository id when the phase is
repository-specific, batch number/count, entry counts, candidate counts, and
review counts. Logs are emitted at batch/phase boundaries, not per extracted
term, occurrence, text unit, or candidate row.

Micrometer metrics are emitted through `TermIndexJobObservability` with bounded
tag values only:

- `term_index.job.events` counter and `term_index.job.duration` timer use
  `job={refresh|candidate_generation|extracted_term_triage}` and
  `result={started|succeeded|failed}`.
- `term_index.phase.events` counter and `term_index.phase.duration` timer cover
  refresh phases with `job=refresh`,
  `phase={refresh_repository|refresh_aggregates}`, and terminal `result`.
- `term_index.batch.events` counter and `term_index.batch.duration` timer cover
  batch work with
  `type={refresh_text_unit_batch|candidate_generation_batch|triage_batch}`,
  plus `job` and terminal `result`.
- `term_index.ai_batch.events` counter and `term_index.ai_batch.duration` timer
  cover AI calls with `job={candidate_generation|extracted_term_triage}`,
  `type={candidate_enrichment|extracted_term_review}`, and terminal `result`.

Metric tags must stay bounded. Do not add repository ids, glossary ids,
text-unit ids, candidate ids, extracted-term ids, source text, normalized terms,
locale names derived from arbitrary data, exception messages, or provider
payload values as metric tags. Entity identifiers may appear in logs where they
help operators correlate a run with persisted job history.

Extracted-term search uses the same hybrid async shape as Workbench: try the
request synchronously first, return a polling token if it crosses the configured
threshold, and store the eventual result briefly in blob storage.

The glossary workspace now uses this foundation for per-glossary curation:

- `/api/glossaries/{id}/term-index-suggestions/search-hybrid` reads stored review
  suggestions from `term_index_candidate`. Extraction-derived suggestions must be
  materialized first by the generate endpoint, while manual, import, MCP, and
  external-system submissions can write directly into the same table. By default it
  excludes existing glossary terms, accepted links, ignored suggestions, and glossary
  backing repositories so the review queue shrinks as curators act on it. The
  request can filter by review state (`NEW`, `IGNORED`, `LINKED`,
  `EXISTING_TERM`, `REVIEWED`, or `ALL`) when a curator wants to inspect past
  decisions; returned suggestions carry an explicit review state (`NEW`,
  `IGNORED`, `LINKED`, or `EXISTING_TERM`). It follows the same hybrid async
  shape as extracted-term search: fast responses return inline, slower searches
  return a polling token.
- `/api/glossaries/{id}/term-index-candidates` lets term managers add manual
  source-side suggestions into the same review queue when extraction missed a term or a
  source needs a manual split.
- `/api/glossaries/{id}/term-index-candidates/generate` materializes review
  suggestions from the current extracted-term index for the glossary repository
  scope. This is the automation bridge between extraction and curator review;
  it does not create glossary terms.
- `/api/glossaries/{id}/term-index-candidates/import` and
  `/api/glossaries/{id}/term-index-candidates/export` provide JSON round-trip
  for stored suggestion queues. Imports accept a `candidates` array, a `terms`
  array, or a raw array and are tagged to the destination glossary by the
  curation service.
- Suggestions can still be AI-refined through `GlossaryAiExtractionService` when
  requested by API callers, but the review UI loads stored suggestions without
  AI by default. Raw extractor investigation stays in the system term-index pages.
- The suggestion review UI keeps draft search text separate from the applied
  query so expensive ranking only runs on initial load, refresh, or when the
  curator explicitly applies edited search text.
- Suggestion search returns stored candidate and occurrence data only; generation,
  import, manual add, MCP, or external submissions are the write paths that put
  more suggestions into the table.
- Term-candidate review projects can now be created from generated
  `term_index_candidate` rows without creating glossary metadata up front. Review
  rows keep the target glossary and candidate id, display the proposal metadata
  and evidence, and let assigned reviewers provide specialist input.
- Accepting a term-candidate review during PM resolution creates the normal
  glossary term, attaches note/string usage evidence, writes a `PRIMARY`
  `glossary_term_index_link`, and marks the candidate human-accepted. Rejecting
  the review marks the candidate human-rejected and writes a glossary-specific
  ignore decision instead of creating a glossary term.
- Accepting a suggestion directly from the glossary curation workspace still
  creates a normal glossary term, attaches note/string usage evidence, and writes
  a `PRIMARY` `glossary_term_index_link`.
- Ignoring a suggestion writes `glossary_term_index_decision`.

The system settings pages remain extractor/debug views. Glossary-specific
selection happens in `/glossaries/:glossaryId`; assigned vendors should review
candidate proposals through review projects rather than the system term-index
pages.

## Glossary Build Workflow

1. Human: PM/admin creates the glossary and defines its repository or product
   scope. The existing glossary description should capture purpose,
   include/exclude guidance, and product context for future assignment
   automation. Future automation may suggest the target glossary from that
   description plus structured scope and existing terms, but the current release
   keeps the target glossary explicit.
2. Human or external automation: trusted existing terminology, such as legacy
   glossary files, MCP submissions, or already-reviewed imports, can be imported
   directly into the glossary.
3. Automation: the term index extracts raw source terms from repositories. This
   produces glossary-neutral extracted terms and occurrence evidence.
4. Automation: AI/system triage reviews raw extracted terms. Admins can override
   this from the system term-index pages when needed, but raw-term triage is not
   intended to become a normal vendor review lane.
5. Automation: AI, MCP, import, or another candidate source writes
   `term_index_candidate` proposals with candidate metadata:
   term, definition, rationale, term type, part of speech, enforcement,
   `doNotTranslate`, and confidence. The automation dashboard supports the
   default batch path from accepted extracted terms in the current scope. Batch
   candidate generation uses an explicit per-run limit and is capped
   server-side. This still does not create glossary terms.
6. Human setup: PM/admin creates a candidate review project from generated candidates
   and chooses an optional target glossary, review team, optional advisors, and
   optional decider. This is routing, not linguistic review. Vendor/advisor
   reviewers work in the review project, not in the system term-index pages.
7. Optional human review: vendors/advisors review the candidate proposals and
   provide specialist input when advisors are selected. They do not directly
   create glossary terms.
8. Human promotion gate, ideally batch: the PM/admin decider resolves approved
   and rejected proposals. Approval always accepts the candidate quality decision.
   If the project has a target glossary and glossary inclusion is enabled for the
   row, approval also promotes the candidate into that glossary. Rejection records
   a human rejection and, when a target glossary exists, a glossary-specific ignore
   decision without creating a glossary term. This should not be treated as a
   second full linguistic review.
9. Optional human or automation lanes: active source-term review and glossary
   translation review are cleanup/quality passes after terms exist. They are not
   required for every generated-candidate intake.

Current limitations: candidate review can route pure MCP/manual candidates, but
their review evidence is limited to candidate metadata until they are linked to
occurrences or promoted into glossary terms with screenshot evidence. Candidate
review currently shows text/rationale evidence; screenshot evidence belongs to
accepted glossary terms.

## Extraction Types

Do not use DB enums for extraction methods or relation/status values. Store
strings and validate with app-level constants so new extractors can roll out
without brittle enum migrations.

Expected extraction signals include:

- `LEXICAL_TITLE_CASE`
- `LEXICAL_CAMEL_CASE`
- `LEXICAL_UPPER_CASE`
- `AI_TERMINOLOGY`
- `CODE_SYMBOL`
- `SCREENSHOT_OCR`
- `EXTERNAL_GLOSSARY_IMPORT`
- `MANUAL_SEED`

Extractor-specific metadata belongs in a small JSON payload on the occurrence
row or candidate row, not in the core curation schema.

## Migration Direction

Current on-demand extraction can seed the first extracted-term refresh
implementation, but the persisted extraction-run branch should not be landed as
the primary DB model. Its useful pieces are occurrence ids, run history UI, and
reloadable candidate review. Those should be rebuilt around extracted terms and
the candidate review layer.
