# Review Project adversarial verification — September 1, 2026

Five independent passes expanded the
[initial state verification](2026-09-01-review-project-state-verification.md).
All work used local code, controlled browser responses or isolated synthetic
databases. The findings have permanent regressions. This historical pass did
not establish the absence of further defects; see the
[September 2 verification](2026-09-02-review-project-confidence-verification.md).

## Confirmed findings and required behavior

| Area | Required behavior |
| --- | --- |
| Metadata response ownership | A request started in project A cannot replace project B's cache, error or navigation state |
| Draft restoration | Retained clean rows adopt fresh server snapshots; dirty rows keep their original values and base |
| Conflict reset | Reset adopts the authoritative row shown in the conflict |
| Use external | Even an already-decided conflict requires guarded acceptance; another intervening edit conflicts again |
| Repeated recovery | A failed state-only recovery does not replace the original local target, comment or notes used by a later Use mine |
| Ambiguous Unicode success | Matching NFC-normalized state from the same reviewer can reconcile a lost reply |
| Obsolete callbacks | A request handle from an ended user/project session cannot initiate a save |
| State-only decisions | Bind the accepted decision to the checked current variant and store supplied notes |
| MF2 shortcuts and validation | Composition blocks premature saves; raw Ctrl+Enter adds no newline; retained drafts republish validation for their document identity |
| Bulk refresh/reset | Preserve dirty working values and revisions; explicit Reset adopts the latest server state |
| Partial bulk failure | Acknowledge successful rows immediately and retry only unfinished work |
| Bulk navigation and editing | Old completions cannot restore an obsolete project; disable edits while applying |
| Terminology forms | Preserve dirty/newer fields across refresh, acknowledgement and same-app navigation |

## Historical checks

The final pass passed **718 frontend tests across 73 files**, including 92 new
cases for draft lifetime, request protocols, metadata ownership, editor input,
bulk operations and terminology forms. Formatting, lint, TypeScript, Spotless
and diff checks passed.

The combined backend run passed **80 targeted tests**. Its 27 database tests
used local MySQL with a disposable schema; 14 new adversarial database tests
also passed HSQL. Authenticated Spring MVC tests exercised real request binding
and transactions, but were not browser tests. Existing ORM TEXT-index warnings
mean the runs do not certify the complete schema or migrations.

A fresh browser fixture used the actual page, router, assisted editor and raw
MF2 editor with controlled API responses. It verified:

- Raw MF2 Ctrl+Enter preserves the exact message and intentional newlines.
- Exhausted 500 retries and a subsequent 409 retain the same row, draft and base
  revision, with visible recovery controls.
- Use mine sends the displayed conflict revision. Success advances once to the
  next row's own target; Reset adopts the displayed conflict instead.

## Evidence and limits

Original red/green logs and browser traces remain in the ignored local
`target/review-project-investigation/` directory. The original note is also
preserved under
`target/review-project-confidence-20260902/checkpoint-prep/original-files/`.
These local artifacts are excluded from the public change.

Browser responses were controlled and database persistence was tested
separately. The later integrated checks found a post-commit HTTP 500 that these
fixtures had masked; the linked September 2 record explains the correction.
Actual OS input methods, vendor roles, long-lived tabs and deployment behavior
remain release checks. In-memory draft retention is not crash/reload recovery.
Terminology form protection does not add stale-write preconditions to every
shared glossary API. Legacy clients, later unconditional writers and realistic
database contention retain the limits in the
[editing-state design](../design/031-review-project-editing-state.md).
