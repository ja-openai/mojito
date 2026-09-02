# Review Project confidence verification — September 2, 2026

## Assessment and scope

Local confidence is substantially higher for the tested save-integrity paths.
Additional independent reviews and integrated tests found reproducible defects,
including one concealed by older database fixtures. Those defects were corrected
and corresponding regressions failed before passing with the fixes.

This record describes local implementation and verification. It does not prove
the whole page defect-free, attribute an earlier production incident or certify
a deployment. The existing explicit draft/operation model was strengthened at
its missing transitions; see the [state design](../design/031-review-project-editing-state.md).

## Newly confirmed defects

| Failure | Corrected behavior and evidence |
| --- | --- |
| Use external discarded comments/notes typed while recovery was pending, then advanced | Capture values at the discard click and acknowledge each field separately. Later edits remain unsaved and prevent advance. Red tests use enabled controls and real user-event helpers. |
| A glossary definition changed a review row's backing source while preserving its row ID | Retain the original TM ID, source, message format and revision with the draft. Block submission/recovery until explicit Reset. Preserve absent versus null message-format behavior for legacy MF2 detection. |
| Automatic retries and offline queued requests issued writes after their session ended | Freeze ownership with the request and check it before every HTTP attempt. An already dispatched request can still commit. |
| HTTP 200 and a matching row ID were accepted without validating saved state | Require complete identity, requested values, revision and decision/current-variant binding. Injected contradictory/incomplete responses retain the draft and do not advance; these malformed responses were not observed backend emissions. |
| Delayed acknowledgements replaced an observed different source or third cache revision | Preserve the observed cache state and draft, then require checking the saved result. Revision strings remain opaque. |
| Earlier revisions omitted backing-source/locale identity, allowing a metadata remap between check and write | v2 binds source/locale identity. Metadata replacement and saves share parent/current-row lock ordering and refresh identity after waiting. Five MySQL regressions cover stale writes and both transaction orderings. |
| A translation committed, then the statistics listener caused HTTP 500 | Resolve repository identity without detached traversal and keep ancillary post-commit statistics failures from misreporting a committed save. |
| Later guided MF2 line-break handling consumed plain Enter during an IME event after the Review guard yielded | Stop composition key events at the ProseMirror DOM-event boundary without canceling native input-method handling. Component tests cover Enter and Shift-Enter with modern and legacy composition signals; the Review workflow test proves the draft stays exact and does not save or move. |

The last failure was observed through the actual browser, Spring server and
MySQL: the decided-count update cleared the entity manager, then a post-commit
listener tried to initialize a detached Asset proxy. **Older database helpers
had initialized the repository graph before controller invocation, masking the
failure.** Both warmup workarounds were removed. The assumption that normal HTTP
requests made that traversal safe was wrong; the
[September 1 record](2026-09-01-review-project-state-verification.md) explicitly
corrects it.

The listener fix also received a performance check. A scalar-query-only version
added 64 lookups for 32 warm import rows; the initialized-graph fast path adds
zero queries for that batch. Cold-context saves pass, and a real connection
identity test confirms that the fallback reuses the existing transaction
connection. No additional transaction or notification cache was introduced.

## Integrated browser and database checks

The interactive runs used the actual React application and editors, the normal
local Spring server and local MySQL. A new disposable eight-row project was
created through application APIs; existing projects were not edited. A temporary
localhost-only entry imported the real application and intercepted only the
fixture's mutation requests to inject failures, delays or a lost committed reply.
Competing writes and database read-backs used real persistence. The temporary
entry was removed from the frontend after verification.

After the local server loaded the final backend, fresh responses confirmed v2
revisions. The following checks then passed:

| Scenario | Observed result |
| --- | --- |
| Three injected 500 attempts before writing | The draft and selected row remain with a visible error; the database target and undecided state are unchanged. Explicit retry returns 200 and only then advances. |
| Drop a real successful MF2 save reply after commit | Retry receives a real 409, verifies the same reviewer's exact saved state, then advances to the next row's own content. Database assertions find one matching variant with exact placeholders and newlines. |
| Leave after failure but before automatic retry | No additional HTTP attempt occurs after leaving. Returning restores the draft; explicit save persists it and advances. |
| Another writer commits before the browser | The browser receives a real 409 and retains its unsaved local draft on the same row. |
| Use external with a delayed successful reply; type a newer enabled comment | The external target is adopted, the newer comment remains unsaved, and selection stays on the row. A subsequent save persists both values and advances. |

A separate enabled-notes recovery check passed with the stable frontend before
the backend restart. Direct read-back showed the newer note was unsaved until
explicit submission. The initially untranslated-row check observed the old
commit-then-500 failure and successful reconciliation; that observation is
separate from the deliberately dropped successful reply on the final server.

Suggestion-provider unavailability also left the translation draft usable and
ordinary saving functional. These integrated runs used a local administrative
test identity; they did not validate vendor roles or SSO end to end.

## Recorded checks

| Check | Result |
| --- | --- |
| Full frontend check | ESLint, Prettier and **806 tests across 76 files** passed after the later MF2 integration and composition hardening |
| Production frontend build | TypeScript and Vite passed; existing chunk-size and dynamic-import warnings remain |
| Locally served optimized bundle | Fetched bytes matched the new build; interactive fault tests used the development server |
| Backend regressions | **90 targeted tests passed**: the existing 80 without warmup workarounds, five identity tests, four cold acknowledgement/connection tests and one warm-import listener test |
| Database scope | Disposable local MySQL schema with real controller/service transactions and independent competing transactions; ORM TEXT-index warnings mean migrations are not certified |
| Formatting and diff | Spotless and diff checks passed |

Before checkpointing, incident-derived fixtures were replaced with synthetic
Gujarati examples and the public notes were reduced to reusable findings and
methods. A fresh Spotless run and frontend formatting, lint, TypeScript and full
test run passed. After the MF2 editor integration and composition fix, another
full run passed **806 tests across 76 files**. Application code and backend tests
remained byte-identical to the previously verified version; the 90 backend tests
and browser/build checks above were not rerun as part of this documentation and
fixture cleanup. The only warning in the fresh checks was the existing local npm
proxy configuration option.

The earlier frontend workflow URL assertion timed out once after the next target
had rendered, and recurred once in the first post-restart full run alongside a
login-config mock failure. Both failed tests passed in isolation and the next full
run passed. Router-transition timing remains a candidate explanation rather than
a demonstrated cause; no assertion was weakened.

## Evidence and remaining release boundaries

Raw logs, exact commands, request/response/DOM captures, synthetic fixture scripts,
database assertions and served-build comparisons were kept in an ignored local
`target/review-project-confidence-20260902/` directory during the original run.
Those transient artifacts did not survive the later laptop restart and are not
claimed as retained evidence. Permanent regressions under
`webapp/frontend/src/page/review-project/` and
`webapp/src/test/java/com/box/l10n/mojito/` provide reviewable coverage.

- Matching client/server deployment, verification of the served artifacts and
  fresh translator tabs remain required. Old clients omitting the optional
  reviewed-state revision retain the compatibility gap.
- Actual OS input methods, long-lived sessions, vendor roles/SSO and realistic
  latency/concurrency remain release checks.
- Retained drafts survive same-app navigation in memory, with an unload warning;
  crash and confirmed-reload recovery are not durable.
- Later unconditional TM/import writers can replace an acknowledged save.
  Metadata replacement preserves existing stored translations under its current
  policy; this change prevents stale new submissions, not semantic re-review of
  all retained material.
- Broader glossary feedback/resolution/shared metadata keep existing last-writer
  semantics. A statistics notification failure can delay statistics, although it
  no longer turns an already committed translation into a save failure.
- The original reported carryover remains without definitive client-path
  attribution. Passing tests do not establish that attribution.
