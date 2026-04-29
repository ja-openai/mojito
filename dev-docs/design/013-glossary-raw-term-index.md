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
  - source locale tag, using `root` when the source locale is not known
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

This lets Codex, product teams, or imported seed lists provide high-context terms
while still forcing the final glossary decision through the same candidate
curation path as occurrence-based extraction. If an external system submits two
distinct proposals with the same source string, they stay as separate candidates
when their source id/hash or meaning metadata differs; they do not merge into a
single extracted term.

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

`TermIndexRefreshService` provides the first server-side refresh path:

- scans used root text units for selected repositories
- keeps a per-repository `(created_date, tm_text_unit_id)` cursor
- uses a per-repository cursor lease so overlapping refreshes for the same
  repository do not run concurrently
- writes run state, cursor leases, checkpoints, and batch occurrence changes in
  short transactions rather than one refresh-wide transaction
- stages affected extracted terms per refresh run, then recomputes aggregates
  from that staged list in pages
- supports full refresh by deleting repository occurrences before re-indexing
- writes lexical occurrences for title-case, uppercase, and camel-case signals
- recomputes entry occurrence and repository counts for affected entries

`/settings/system/glossary-term-index` exposes the admin refresh/run view on top
of that foundation, and `/settings/system/glossary-term-index/terms` exposes the
extracted-term investigation view. Together they can start an asynchronous repository-scoped
refresh from an explicit refresh modal, inspect repository cursors and a capped
recent-run list, search a virtualized extracted-term list with a Workbench-style
result limit, and drill into a capped set of matched source occurrences. These
surfaces are for extractor investigation only; they do not create or link
glossary terms. Extracted-term search uses the same hybrid async shape as Workbench:
try the request synchronously first, return a polling token if it crosses the
configured threshold, and store the eventual result briefly in blob storage.

The glossary workspace now uses this foundation for per-glossary curation:

- `/api/glossaries/{id}/term-index-suggestions/search-hybrid` derives
  suggestions from scoped extracted terms plus candidate submissions. By default it
  excludes existing glossary terms, accepted links, ignored candidates, and glossary
  backing repositories so the review queue shrinks as curators act on it. The
  request can filter by review state (`NEW`, `IGNORED`, `LINKED`,
  `EXISTING_TERM`, `REVIEWED`, or `ALL`) when a curator wants to inspect past
  decisions; returned suggestions carry an explicit review state (`NEW`,
  `IGNORED`, `LINKED`, or `EXISTING_TERM`). It follows the same hybrid async
  shape as extracted-term search: fast responses return inline, slower searches
  return a polling token.
- `/api/glossaries/{id}/term-index-candidates` lets term managers add manual
  candidates into the same review queue when extraction missed a term or a
  source needs a manual split.
- Suggestions are AI-refined through `GlossaryAiExtractionService` by default;
  if AI is unavailable or filters everything out, the UI falls back to
  heuristic suggestions. The review UI does not expose this as a normal curator
  choice; raw extractor investigation stays in the system term-index pages.
- The suggestion review UI keeps draft search text separate from the applied
  query so expensive ranking only runs on initial load, refresh, or when the
  curator explicitly applies edited search text.
- For small suggestion result sets, seeded terms with few or no stored
  occurrences are enriched with bounded live source-text search examples. These
  examples are review evidence only; they do not mutate occurrence history.
- Accepting a suggestion creates a normal glossary term, attaches note/string
  usage evidence, and writes a `PRIMARY` `glossary_term_index_link`.
- Ignoring a suggestion writes `glossary_term_index_decision`.

The system settings pages remain extractor/debug views. Glossary-specific
selection happens in `/glossaries/:glossaryId`.

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
