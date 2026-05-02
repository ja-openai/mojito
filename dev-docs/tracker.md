# Product & Tech Tracker

Purpose

- Capture improvement ideas and technical debt so we can prioritize intentionally instead of chasing every idea immediately.
- Treat this as a lightweight backlog: write down the idea, rationale, and any quick notes. Pull items into workstreams as capacity allows.

How to use

- Add new entries to the bottom with a unique identifier (e.g., `FRONTEND-01`).
- Keep notes concise: what problem it solves, rough scope/impact, and dependencies.
- When an item becomes active, move it to a dedicated design doc (see `dev-docs/design/`) or ticket system.

Backlog

- FRONTEND-01 — Add ESLint/Prettier/Vitest to `webapp/new-frontend` for consistent code quality.
- DEV-01 — Evaluate upgrading repository-wide Node version to 22 LTS and update Maven/legacy UI accordingly.
- DEV-02 — Replace `npm install` with `npm ci` in Maven plugin executions for reproducible builds.
- AUTH-01 — Figure out OSS-friendly auth story for static assets + APIs without Cloudflare: likely a tiny reverse proxy that terminates auth and sets a secure cookie for `/` and `/n/**`; dev can mimic edge by injecting `CF-Access-Jwt-Assertion`/`Authorization` headers via Vite proxy; document requirements (covers assets, avoids CSRF if cookies used) so we don’t restart from scratch.
- CI-01 — Add GH Action snippet to run `mvn -pl webapp -Pfrontend test-compile -DskipTests` (or `frontend:npm …check`) so new-frontend check uses the Maven-managed Node/npm.
- FRONTEND-02a — Build container: React Query fetch/search/filter, selection state, scroll-to-selected, keyboard nav, and tanstack virtualization hooks (estimate/measure) without UI markup.
- FRONTEND-02b — Implement pure view: virtualized repos table with sticky header and inline search, selection highlight, metric link callbacks, and locales pane layout that mirrors the prototype.
- FRONTEND-02c — Locale data & accessibility: map selected repo to localeDetails or call `onLoadLocales(repoId)`, handle loading/error/empty states, add scope/aria labels and focusable selected row, and keep CSS state classes minimal.
- REVIEW-01 — Evolve review automation beyond the MVP executor: add business-day due-date handling, optional team/vendor delivery pools, and final claim strategy on top of the existing feature-based project creation, open-project exclusion, and optional translator auto-assignment.
- REVIEW-03 — Revisit review automation cron startup/Quartz lifecycle. Current persisted trigger path can instantiate `ReviewAutomationCronJob` too early during app startup; design a proper fix that matches existing Quartz patterns without changing global scheduler startup behavior opportunistically.
- WORKBENCH-03 — Decide whether `/api/textunitsBatch` should be admin-only end-to-end. The new workbench import UI is admin-gated, but backend auth still follows the broader `/api/textunits/**` permissions.
- WORKBENCH-04 — Revisit workbench import round-trip semantics for `status` and `includedInLocalizedFile`. The current batch import backend ignores those fields even though export/import can carry them.
- PLATFORM-01 — Harden the `webapp` MCP bad-translation workflow after the initial scaffold: improve review-project lineage confidence, add better operator audit visibility, and decide whether sync-only `/api/mcp` is enough before adding SSE/session support.
- PLATFORM-02 — Extend task inspection beyond single-id lookup if operators need broader triage: recent failed-task search, repository-scoped task history, and retention expectations for pollable-task input/output blobs.
- GLOSSARY-03 — Add semantic glossary retrieval on top of the current lexical/span matching so MT and AI review can retrieve consistency terms even when the exact source form differs. The current architecture keeps room for this via `MatchedGlossaryTerm`, AI-assisted extraction metadata, and per-term evidence.
- GLOSSARY-04 — Add configurable extraction noise controls and approval policies for AI-assisted glossary mining. The current extractor still uses a static stop-word list plus AI reranking; we likely want glossary-level/global ignore words, repository-specific extraction presets, and confidence thresholds.
- GLOSSARY-05 — Harden glossary candidate-term review beyond the first pass: cluster duplicate source-term proposals, add richer repository/review context for reviewers, and decide how much of the remaining legacy translation-proposal model should survive versus being retired entirely.
- GLOSSARY-06 — Turn the glossary workspace into an operational readiness layer for repository-backed terminology: readiness summary, diff/audit views, and proposal-based feedback loops. See `dev-docs/design/011-glossary-workspace.md`.
- GLOSSARY-07 — Cache compiled glossary tries for glossary-match lookups so Workbench/review detail panes do not rebuild the same repository+locale matcher on every request. Keep TTL short enough to tolerate normal backing-repo edits, and add explicit eviction on glossary write paths where practical.
- GLOSSARY-08 — Finish glossary MCP client integration: validate Codex auth/config against `/api/mcp`, decide whether an authenticated local bridge or MCP-specific token is needed, and decide whether TM-based translation suggestions should get a reviewed write workflow beyond `bulk_upsert`. The core tools now cover glossary create/update, term list/search/review-plan/bulk-upsert/reference-link, term-index candidate seeding, image upload for screenshot evidence, and TM translation suggestion. See `dev-docs/design/012-glossary-mcp.md`.
- GLOSSARY-10 — Decide the operational refresh policy for the raw term index: scheduled cadence, repository scope, retry behavior, and retention/pruning for `term_index_refresh_run` rows once automatic refreshes exist.
- GLOSSARY-11 — Harden raw term-index curation after the first glossary workspace pass: add structured metadata rendering for seeded candidates, manual split/merge review controls, duplicate/alias clustering, and richer filters for ignored/accepted suggestions. See `dev-docs/design/013-glossary-raw-term-index.md`.
- GLOSSARY-13 — Add operational visibility for long-running term-index jobs: info logs on extract/triage/candidate-generation start, finish, and failure; Micrometer counters with bounded tags such as job/result/status; timers for major phases and AI batches; avoid repository/glossary IDs as metric tags.
- GLOSSARY-14 — Add the same sort/filter maturity to term-index candidates after the extracted-term page is stable, including confidence sorting and a clear distinction between candidate confidence, review confidence, and extracted-term occurrence confidence.
