# Glossary Raw Term Index

## Purpose

Glossary extraction should not be owned by a single glossary run. Mojito needs a
durable raw term index that records candidate terms observed in product strings,
then lets one or more glossaries curate those raw candidates into glossary terms.

This keeps recurring updates server-side and idempotent:

1. refresh the raw index for a repository scope
2. compute glossary suggestions from that raw index and existing glossary terms
3. persist accepted mappings back to the glossary term index link table

## Layers

### Raw Index

The raw index is repository/text-unit oriented and glossary-neutral.

- `term_index_entry`
  - normalized candidate key
  - display term
  - source locale tag, using `root` when the source locale is not known
  - aggregate occurrence/repository counts
  - extraction signal summary
  - first/last seen timestamps
- `term_index_occurrence`
  - raw index entry
  - product `tm_text_unit_id`
  - repository and optional asset
  - matched text and source span
  - source hash or text-unit version marker
  - extractor id/method and confidence

The refresh path should delete and reinsert occurrences for changed text units,
then recompute entry aggregates. It should not require the caller to resend a
full candidate set.

### Glossary Curation

Glossary terms remain backed by glossary repository text units. A raw candidate
can map to multiple glossary terms, including multiple terms in the same
glossary, so the relationship should be a join table:

- `glossary_term_index_link`
  - glossary term metadata id
  - raw term index entry id
  - relation type string such as `PRIMARY`, `ALIAS`, or `RELATED`
  - optional confidence/rationale

Accepted mappings belong in `glossary_term_index_link`. If glossary-specific
review queues or automated assignment suggestions need to be persisted before
acceptance, store them in a separate plural suggestion model rather than on the
raw index.

### Refresh Runs

Runs are useful for audit/progress, but they should not own candidate truth.

- `term_index_refresh_run`
  - scope, status, counts, and error message
- `term_index_refresh_run_entry`
  - exact distinct raw term entries touched by a run
  - used to recompute aggregates in pages without keeping a run-wide id set in
    application memory

Runs can explain what happened during a refresh. The raw index and accepted
glossary links remain the current materialized truth.

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
- stages affected raw term entries per refresh run, then recomputes aggregates
  from that staged list in pages
- supports full refresh by deleting repository occurrences before re-indexing
- writes lexical occurrences for title-case, uppercase, and camel-case signals
- recomputes entry occurrence and repository counts for affected entries

`/settings/system/glossary-term-index` exposes the admin refresh/run view on top
of that foundation, and `/settings/system/glossary-term-index/terms` exposes the
raw term review view. Together they can start an asynchronous repository-scoped
refresh from an explicit refresh modal, inspect repository cursors and a capped
recent-run list, search a virtualized raw entry list with a Workbench-style
result limit, and drill into a capped set of matched source occurrences. These
surfaces are for extractor investigation only; they do not create or link
glossary terms. Raw entry search uses the same hybrid async shape as Workbench:
try the request synchronously first, return a polling token if it crosses the
configured threshold, and store the eventual result briefly in blob storage.

This intentionally stops before glossary-specific assignment. The next layer
should derive glossary suggestions from the raw index and existing glossary
terms, then persist accepted mappings in `glossary_term_index_link`.

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
row, not in the core curation schema.

## Migration Direction

Current on-demand extraction can seed the first raw index refresh
implementation, but the persisted extraction-run branch should not be landed as
the primary DB model. Its useful pieces are occurrence ids, run history UI, and
reloadable candidate review. Those should be rebuilt around the raw index.
