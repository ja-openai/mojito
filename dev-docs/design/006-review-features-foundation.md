Review Features Foundation
==========================

Context
- Review-project automation needs a stable grouping object before we decide final assignment behavior.
- Repository-only grouping is enough for the first iteration, but the model should leave room for broader scope rules later.
- Admin users need a familiar CRUD surface in the new frontend so PM-owned configuration does not stay in code or SQL.

Goals
- Introduce `ReviewFeature` as the admin-managed grouping entity for repositories.
- Provide optimized Spring Data reads for list screens and lightweight lookup flows.
- Reuse established new-frontend admin patterns: searchable table, status filter, result-size control, hover actions, detail editing, and batch upsert.

Scope
- New backend entity + migration for `review_feature` and its repository join table.
- Admin-only REST endpoints for list, detail, create, update, and batch upsert.
- Admin-only new-frontend pages for review feature list, detail edit, and batch update/create.
- Repository selection limited to explicit repository membership for now.

Out of Scope
- Automated review-project creation.
- Team/vendor assignment rules.
- Feature expressions beyond repository membership.

Data Model
- `ReviewFeature`
  - `id`
  - `name` (unique, normalized, max length aligned with entity constraints)
  - `enabled`
  - `repositories` (`many-to-many` to repositories through `review_feature_repository`)

Backend Notes
- List API uses a Spring Data projection (`ReviewFeatureSummaryRow`) so the table view does not hydrate full entities.
- Repository names for the visible page are loaded in one follow-up projection query keyed by feature ids, avoiding per-row fetches.
- Batch tooling uses a dedicated option query (`ReviewFeatureOptionRow`) so matching existing features is not tied to the paged list limit.
- Writes normalize names, de-duplicate repository ids, and reject unknown repository ids.

Frontend Notes
- List page reuses `SearchControl`, `NumericPresetDropdown`, and `RepositoryMultiSelect`.
- Batch page follows the existing user/team batch style and supports mixed create/update rows matched by feature name.
- Detail page keeps edits simple: name, enabled state, and repository membership.

Follow-on Work
- Daily automation that groups new `REVIEW_NEEDED` strings by review feature and excludes strings already in open projects.
- Assignment model decision: queue/claim vs team routing vs soft individual auto-assignment.
