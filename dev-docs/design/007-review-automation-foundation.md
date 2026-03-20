# Review Automation Foundation

Context

- `ReviewFeature` now covers the grouping side of review automation, but we still need a separate admin object for schedule/runtime configuration.
- PM discussions landed on multiple schedules rather than one global cron, with each schedule owning a list of review features.
- Execution is still follow-on work, but the config model needs to leave room for multi-repository projects and project-size caps.

Goals

- Introduce `ReviewAutomation` as the admin-managed schedule/config entity for automated review-project creation.
- Support multiple automations, each with enable/disable state, cron expression, max word count per generated project, and review-feature membership.
- Make common schedules easier to author with a button-driven cron generator while still preserving raw cron editing for advanced cases.
- Reuse the existing admin CRUD + batch patterns already used for users, team pools, and review features.

Scope

- New backend entity + migration for `review_automation` and its feature join table.
- Admin-only REST endpoints for list, detail, create, update, delete, and batch upsert/export.
- Admin-only new-frontend pages for review automation list, detail edit, and batch update/create.
- Validation that the same review feature cannot belong to multiple enabled automations at once.

Out of Scope

- Executing scheduled review-project creation.
- Review-project service changes needed to create projects across multiple repositories.
- Hard capacity planning or final assignee selection logic.

Data Model

- `ReviewAutomation`
  - `id`
  - `name` (unique)
  - `enabled`
  - `cronExpression`
  - `timeZone`
  - `maxWordCountPerProject`
  - `features` (`many-to-many` to `ReviewFeature`)

Backend Notes

- List API uses Spring Data projections to avoid hydrating full automation entities for the table.
- Batch tooling uses lightweight options/export queries so the editor is not tied to the paged list limit.
- Enabled automations enforce exclusive feature ownership to reduce duplicate project generation risk in MVP.
- Review-feature deletion is blocked if the feature is referenced by an automation.

Frontend Notes

- List page mirrors review features: search, enabled filter, result-size control, create modal, hover edit/delete.
- Create/detail pages keep raw `cronExpression` and `timeZone` side by side, with a button-driven generator for `Every day`, `Weekdays`, or `Custom cron`.
- Batch page uses `Merge` / `Replace`, prefill from existing automations, and prefill from the review-feature roster.

Follow-on Work

- Scheduler and manual run entry points on top of `ReviewAutomation`.
- Review-project creation changes to support feature-level grouping across multiple repositories.
- Candidate selection and exclusion queries for strings already in open review projects.
