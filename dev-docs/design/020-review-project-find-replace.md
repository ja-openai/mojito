# Review Project Find And Replace

Status: active implementation.

## Goal

Provide a reviewer-safe target-side find/replace workspace for one Review Project. Reviewers can
preview and edit a working copy, then either stage changed targets back into the project for normal
review or accept and decide them through the existing Review Project save path.

## Scope

- Entry point: `/review-projects/{projectId}/find-replace`.
- Input scope comes from the Review Project rows. The page does not create a Workbench run or write
  directly to TM while editing.
- Matching is target-side and supports literal or regex find, case-sensitive matching by default,
  whole-word matching, replacement history, replace-current, replace-all, preserve-case, and local
  undo/redo/reset.
- Row editing uses the protected translation editor so ICU placeholders, markup, variables, and
  other protected syntax stay visible and guarded.
- The page shows current-vs-working diffs and keeps staged suggestions visibly marked as coming from
  find/replace.

## Backend

- Staged suggestions are stored in `review_project_text_unit_suggestion`, one active suggestion per
  Review Project text unit.
- `Stage in project` writes only that suggestion table. It does not create a TM current variant and
  does not decide the Review Project text unit.
- `Accept + decide` uses the normal Review Project decision save path with `APPROVED` and `DECIDED`.
- Suggestion writes validate the expected current variant and run the same protected-syntax integrity
  checks before persisting.
- Review Project detail responses overlay staged suggestion text for the default editing view while
  retaining source metadata for the "From find/replace" pill.

## Out Of Scope

- Workbench batch-update/run history UI.
- Standalone batch-update run routes or run entities.
- AI-guided replacement generation.
- Durable server-side checkpoint history for local undo/redo.
- Prompt, token, cost, or model audit metadata.

## Follow-Ups

- Decide whether local undo/redo checkpoints need durable backend storage.
- Decide whether staged suggestions should also become the persistence layer for future AI Review
  proposal workflows.
