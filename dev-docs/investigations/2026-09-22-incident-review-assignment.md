# Incident assignment after project closure

## Problem

Incident intake required an empty resolution-project link, even if that project was closed
without reviewing the incident. The linked proposal also stayed routed. Closing such a project
therefore stranded otherwise eligible open incidents. Historical translation-project attribution
is a separate field and does not establish an incident assignment.

## Correction

- Read bounded ranges of open incidents, then inspect their actual assignment and human decisions.
- Keep incidents assigned to open projects out of new projects.
- Allow an unchanged, undecided incident in a closed project to be assigned again. Preview does
  not modify it. Creation locks and rechecks its run, source/current translation, proposal,
  incident and assignment project before creating a new proposal revision.
- Keep the old project, row, report and feedback linked to the superseded revision. Transfer the
  active intake identity to the new revision; do not invent a human review event or change a
  translation. An old superseded proposal cannot be decided by reopening its project.
- Keep final decisions, pending human follow-ups, changed strings and inconsistent references
  out of ordinary intake. A clean defer remains undecided and can be assigned again.

V124 adds open-incident seek indexes on `(status, id)` and `(status, review_type, id)`.
It leaves existing indexes and all incident/project history intact. Manual scope refresh and
project-size controls are a separate companion change; scheduled sweep limits remain bounded.

## Release validation

`IncidentReviewAssignmentDbTest` covers closure and reassignment, historical attribution,
exact-state human-review suppression, follow-ups, changed targets, reopened projects, intake
identity transfer, and concurrent overlapping selections. `IncidentReviewAssignmentServiceTest`
checks missing/mismatched references, locked state, immutable report lineage and newer revisions.
Run these with the incident batching, agent project and scheduler regression suites.

Apply V124 with the matching backend. After deployment, a fresh manual preview should include
eligible incidents attached to closed undecided projects, and continue to exclude open
assignments and completed human decisions. Project creation and human acceptance remain
separate actions. Local tests and an executable package do not establish deployed behavior.
