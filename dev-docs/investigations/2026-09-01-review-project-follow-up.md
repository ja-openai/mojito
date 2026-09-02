# Additional Review Project failures — September 1, 2026

Local probes after the AI-suggestion fix found independent failures. These
probes used controlled component responses and did not write to a backend.
They establish missing behavior, not the cause of a reported production incident.

## Reproduced failures

| Failure | Trigger and observation | Required behavior |
| --- | --- | --- |
| Refresh discards unsaved work | Edit a row, then deliver a newer server snapshot; the draft is replaced | Preserve dirty values and their original reviewed revision |
| Draft acquires an unreviewed precondition | Save at the refresh boundary; the old draft is sent with the new server revision | Keep draft values and their base revision together |
| Failed save-and-advance hides failure | A delayed 409 or exhausted 500 retries stops saving; the view advances while the row remains undecided | Advance only after matched, authoritative success |
| MF2 shortcut submits during composition | A synthetic composing Ctrl+Enter reaches the global page shortcut | Block submission and supported row navigation while composition is active |

The original draft reset ran after snapshot changes, while the request obtained
its expected variant from that fresh snapshot. The advance effect treated the
end of saving/validation as success. The global shortcut lacked the child
editor's composition guard. The editing-state implementation now covers these
conditions explicitly; see the [design](../design/031-review-project-editing-state.md).

## Backend and coverage follow-up

Source review also identified a current-variant check separated from its write,
and response mapping that omitted retained staged suggestions. Subsequent local
MySQL tests reproduced concurrent overwrites, first-translation creation races
and incomplete success/conflict responses. Transactional locks, complete row
responses and a reviewed-state revision addressed them; see the
[initial state verification](2026-09-01-review-project-state-verification.md).

Router history, filters that remove the saved row, real editor behavior and
virtual-list navigation received additional coverage. Controlled composition
events do not validate an actual OS input method, browser suspension or old
loaded bundles. The [latest verification](2026-09-02-review-project-confidence-verification.md)
records the stronger integrated checks and remaining limits.

The initial passing suite did not prove the absence of other defects. Original
failing probes and captures are retained in the ignored local
`target/review-project-investigation/` directory, with the original notes under
`target/review-project-confidence-20260902/checkpoint-prep/original-files/`.
These local artifacts are excluded from the public change.
