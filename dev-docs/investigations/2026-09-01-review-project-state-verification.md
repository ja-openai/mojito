# Review Project editing-state verification — September 1, 2026

This historical record covers the initial local state and persistence changes.
Later testing found additional defects, including a masked post-commit failure;
see the [September 2 verification](2026-09-02-review-project-confidence-verification.md)
for the latest checks. Neither record attributes a production incident or
certifies a deployment.

## Backend findings

The initial local MySQL run produced four failures and one error in five tests.
Independent transactions exposed the following behavior:

| Case | Before | Corrected behavior |
| --- | --- | --- |
| Two review rows share a current translation and submit the same expected variant | The waiting save overwrites the first | It returns 409 without storing the rejected target or decision |
| Concurrent first translations | A second insert hits a uniqueness constraint | A stable source-row lock serializes creation; the stale save returns 409 |
| Stale override retry | The override flag bypasses the expected-variant check | Supplied preconditions remain enforced, including during recovery |
| State-only/no-op save with a staged suggestion | The database retains the suggestion but the response drops it | Return the complete authoritative row |
| Conflict with a staged suggestion | The conflict response drops the suggestion | Return the complete authoritative conflict row |

Further tests cover ordinary TM writers, notes-only and suggestion-only changes,
deletion, and acceptance. Assertions inspect persisted values, rejected writes,
decision bindings and revision changes. The first writer flushes and holds an
independent transaction; the competing request waits until that transaction
commits. No repository or service mock substitutes for database concurrency.

Decision/suggestion writes lock the stable TM text unit before the current row,
so the initially untranslated case has a shared lock. The revision check and
write run in one transaction. Complete row mapping preserves staged suggestions
on state-only operations. The optional reviewed-state token detects notes and
suggestion changes even when the current translation ID is unchanged. The
[current design](../design/031-review-project-editing-state.md) also describes
the later v2 source/locale identity and metadata-remapping safeguards.

**Correction from September 2:** the original database helpers preloaded the
repository association needed by the post-commit statistics listener. The
assumption that normal HTTP requests kept that association usable was wrong.
An actual browser/Spring/MySQL save committed, then returned HTTP 500 because
the decided-count update cleared the entity manager and the listener traversed
a detached proxy. The preloading workaround was removed from both helpers;
the listener fix and cold-context regressions now exercise the real boundary.
The earlier passing database run did not establish correct HTTP acknowledgement.

## Historical results

| Check | Result and scope |
| --- | --- |
| Fixed backend on local MySQL | 66 targeted tests passed, including eight new decision database tests |
| HSQL cross-check | All eight new decision database tests passed |
| Frontend | 626 tests across 67 files; formatting, lint and TypeScript passed |
| Browser with controlled responses | Failed saves/conflicts retain the draft and row; refresh and same-app navigation preserve the draft; explicit successful recovery advances once |

Frontend regressions use the real page/router and mutation hook. They cover
explicit outcomes, validation, draft lifetime, same-user reconciliation,
background reads and filter removal. A stale detail GET can no longer overwrite
an acknowledged save; metadata responses preserve current row data before
requesting a fresh detail response. A newer staged suggestion blocks an
otherwise matching ambiguous-save reconciliation.

Browser checks used the actual virtual list and editors, but replaced API
responses. Database behavior was tested separately. This historical pass was
therefore not an integrated browser-to-Spring-to-MySQL test. Synthetic composition
events do not validate actual OS input methods. The later
[adversarial pass](2026-09-01-review-project-adversarial-verification.md) and
[integrated verification](2026-09-02-review-project-confidence-verification.md)
expand that evidence without removing the stated release limits.

## Local evidence and limits

Original red/green logs, commands and browser harnesses remain in the ignored
local `target/review-project-investigation/` directory. Original notes are also
preserved under
`target/review-project-confidence-20260902/checkpoint-prep/original-files/`.
These artifacts are excluded from the public change.

The database runs used disposable schemas; ORM TEXT-index warnings mean they do
not certify all schema migrations. Legacy clients omitting the optional revision
retain the stale-state compatibility gap. Later unconditional imports can
replace an acknowledged translation. Lock contention, unrelated glossary
concurrency, actual input methods and deployment verification remain separate
concerns. Retained drafts are in memory and do not survive a crash or a confirmed
reload.
