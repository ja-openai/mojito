# Glossary MCP

Purpose

- Give MCP clients a controlled surface for glossary operations.
- Let clients inspect product code, massage bootstrap spreadsheets, and submit normalized glossary term changes without bypassing Mojito permissions or storage rules.
- Keep Mojito responsible for canonical term storage, translation imports, and text-unit links.

Existing model support

- Glossary terms are backed by Mojito text units in the glossary backing repository.
- `glossary_term_metadata` links one metadata row to one canonical glossary term through `tm_text_unit_id`.
- `glossary_term_evidence` can link supporting references to existing product text units through its own `tm_text_unit_id`.
- This means a glossary term itself should be stored as a canonical glossary text unit, while product strings that justify the term should be attached as supporting references.

MCP tools

- `glossary.list`
  - Read-only.
  - Lists glossaries visible to the current user.
  - Use before term operations when only the human glossary name is known.
- `glossary.create_or_update`
  - Mutating.
  - Creates a managed glossary with its backing repository, or updates an existing glossary by id.
  - Configures enabled state, priority, locales, repository scope, and exclusions.
- `glossary.term.search`
  - Read-only.
  - Searches terms in one glossary by `glossaryId` or exact `glossaryName`.
  - Can request specific locale columns with `localeTags`.
  - Returns metadata, creation/update timestamps, translations, and supporting references.
- `glossary.term.bulk_upsert`
  - Mutating, dry-run by default.
  - Accepts normalized JSON terms, not arbitrary files.
  - Accepts up to 1,000 terms per call; split larger imports to keep MCP request/response payloads bounded.
  - Supports source metadata, translations, and supporting references.
  - Supporting references can link existing product TUs by evidence `tmTextUnitId`.
- `glossary.term.review_plan`
  - Read-only.
  - Compares proposed terms against existing terms and returns create/update/duplicate guidance.
  - Accepts up to 1,000 proposed terms per call.
  - Use before `bulk_upsert` when bootstrapping from files or mined codebase terms.
- `glossary.term.link_references`
  - Mutating.
  - Appends supporting references to an existing term without replacing term metadata or existing references.
  - Use with `text_unit.search` to attach product TU usage evidence.
- `image.upload`
  - Mutating.
  - Uploads a base64 image or image data URL to Mojito image storage.
  - Returns an `imageKey` and `/api/images/...` URL that can be attached as screenshot evidence with `glossary.term.bulk_upsert` or `glossary.term.link_references`.
- `glossary.term.suggest_translations_from_tm`
  - Read-only.
  - Searches product TM for exact source-term matches and returns observed target-term suggestions by locale.
  - Clients should review the suggestions and then write selected targets through `glossary.term.bulk_upsert`.
- `glossary.term_index.seed_candidates`
  - Mutating.
  - Merges externally suggested terms into the term-index candidate review layer without creating glossary terms directly.
  - Can target a glossary by id or exact name; glossary-scoped submissions are tagged so they appear in that glossary's review queue.
  - Supports source type/name/external id, confidence, definition, rationale, source-side term metadata, and arbitrary metadata such as screenshot image keys or code paths.
  - Does not accept target translations; reviewed locale terms still go through glossary term write flows such as `glossary.term.bulk_upsert`.
  - Use when an MCP client or another terminology source has good context but the final glossary assignment should still be reviewed in Mojito.
- `glossary.term_index.seed_candidates_from_glossary`
  - Mutating.
  - Copies terms from an existing Mojito glossary into a target glossary's candidate review layer.
  - Supports filtering the source glossary by search query, term ids, term keys, source statuses, locale columns, and limit.
  - Preserves source glossary provenance in candidate metadata and uses stable source glossary term ids for idempotent updates.
  - Does not create accepted glossary terms or carry translations into candidate translations.
  - Use when a manually curated or tool-generated glossary should be reused as review input for a new curated glossary.
- `glossary.term_index.link_glossary_terms_to_candidates`
  - Mutating, dry-run by default.
  - Reconciles existing glossary terms to existing term-index candidates by normalized source text.
  - Skips ambiguous matches by default so terms such as product nouns and UI action verbs are not linked to the wrong candidate silently.
  - Supports optional source-term search, limit, overwrite of existing primary links, and explicit ambiguous-match linking.
  - Use after importing or bulk-upserting glossary terms when the accepted glossary artifact should be connected back to candidate/extraction usage evidence.

Bootstrap workflow

1. An MCP client reads Excel/CSV locally and normalizes it into JSON terms.
2. The client uses `glossary.list`, or `glossary.create_or_update` when the destination glossary does not exist.
3. The client uses `glossary.term.search` and `glossary.term.review_plan` to detect duplicates and likely merge work.
4. The client calls `glossary.term.bulk_upsert` with `dryRun: true`.
5. A human or higher-level agent reviews the plan.
6. The client repeats the same call with `dryRun: false`.
7. The client optionally calls `glossary.term.suggest_translations_from_tm` and writes reviewed translations through `bulk_upsert`.

Codebase mining workflow

1. The client uses the raw term index refresh/review flow for observed Mojito strings.
2. If the client has additional product/code/screenshot context, it calls `image.upload` as needed and then `glossary.term_index.seed_candidates`.
3. If an existing Mojito glossary is useful prior art, the client calls `glossary.term_index.seed_candidates_from_glossary` to put those terms into the same review queue.
4. A curator can review the merged suggestions directly in `/glossaries/:glossaryId`, or create a term-candidate review project for generated candidates so assigned reviewers work only in review-project UI. PM resolution accepts promote candidates into glossary terms; rejection leaves no glossary term behind.
5. After approved glossary terms exist, a repository-scoped term-index refresh can rescan product strings and create `EXTERNAL_GLOSSARY_IMPORT` usage occurrences for exact normalized matches.
6. The client can still use `glossary.term.bulk_upsert` for already-reviewed bootstrap files where bypassing the raw suggestion queue is intentional.
7. If terms were imported directly, the client calls `glossary.term_index.link_glossary_terms_to_candidates` to attach existing terms to existing candidates and raw usage evidence where the source text matches.
8. The client calls `glossary.term.link_references` to append product-string references discovered after the term already exists.

Why not parse Excel in Mojito MCP

- MCP clients can use local file tooling for CSV/XLSX cleanup and schema inference.
- Keeping MCP input as normalized JSON avoids server-side spreadsheet parsing dependencies and ambiguous column mapping.
- The JSON payload is also the reusable contract for codebase mining, spreadsheet imports, and future AI-assisted review.

Current limitations

- `glossary.translation.propose`: submit locale-specific translation proposals when the client should not directly write approved translations.
- The remote endpoint is authenticated with the existing Mojito web security stack. MCP clients need a configured auth header or an authenticated local bridge; no MCP-specific token model exists yet.
- The seed-term tool stores context in the raw index only; richer curator UI for inspecting structured metadata is still limited.
