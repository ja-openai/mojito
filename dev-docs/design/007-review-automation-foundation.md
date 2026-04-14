# Review Automation Foundation

Context

- `ReviewFeature` now covers the grouping side of review automation, but we still need a separate admin object for schedule/runtime configuration.
- PM discussions landed on multiple schedules rather than one global cron, with each schedule owning a list of review features.
- Execution needs to create one review-project request per feature per run, using a single assigned team for the automation, an optional locale-pool translator assignment, and a per-project word cap.

Goals

- Introduce `ReviewAutomation` as the admin-managed schedule/config entity for automated review-project creation.
- Support multiple automations, each with enable/disable state, cron expression, time zone, assigned team, optional translator auto-assignment, due-date offset, max word count per generated project, and review-feature membership.
- Make common schedules easier to author with a button-driven cron generator while still preserving raw cron editing for advanced cases.
- Reuse the existing admin CRUD + batch patterns already used for users, team pools, and review features.
- Execute cron/manual runs and persist lightweight run history for operational visibility.

Scope

- New backend entity + migration for `review_automation` and its feature join table.
- Admin-only REST endpoints for list, detail, create, update, delete, and batch upsert/export.
- Admin-only new-frontend pages for review automation list, detail edit, and batch update/create.
- Validation that the same review feature cannot belong to multiple enabled automations at once.
- Quartz-backed scheduling with one trigger per automation.
- Manual `Run now` support for a saved automation.
- Review-project creation that resolves feature repositories/locales, excludes text units already covered by open review projects, and chunks locale work by `maxWordCountPerProject`.
- `review_automation_run` persistence plus a recent-runs table on the automation detail page.

Out of Scope

- Hard capacity planning or final assignee selection logic.
- Business-day/holiday-aware due-date calculation.
- Per-feature delivery-pool routing across multiple teams or vendors.

Data Model

- `ReviewAutomation`
  - `id`
  - `name` (unique)
  - `enabled`
  - `cronExpression`
  - `timeZone`
  - `team`
  - `dueDateOffsetDays`
  - `maxWordCountPerProject`
  - `assignTranslator`
  - `features` (`many-to-many` to `ReviewFeature`)
- `ReviewAutomationRun`
  - `reviewAutomation`
  - `triggerSource` (`MANUAL` / `CRON`)
  - `requestedByUser`
  - `status`
  - `startedAt`
  - `finishedAt`
  - `featureCount`
  - `createdProjectRequestCount`
  - `createdProjectCount`
  - `errorMessage`

Backend Notes

- List API uses Spring Data projections to avoid hydrating full automation entities for the table.
- Batch tooling uses lightweight options/export queries so the editor is not tied to the paged list limit.
- Enabled automations enforce exclusive feature ownership to reduce duplicate project generation risk in MVP.
- Review-feature deletion is blocked if the feature is referenced by an automation.
- Scheduler synchronization happens after automation CRUD commits, so Quartz stays aligned with saved config.
- Cron execution runs as the system user and reuses the same feature-based review-project creation path as manual creation.
- Automated creation always excludes text units already covered by any open review project for the same `tmTextUnit + locale`.
- Manual and automated creation can skip default translator assignment while still keeping team and PM assignment.

Frontend Notes

- List page mirrors review features: search, enabled filter, result-size control, create modal, hover edit/delete.
- Create/detail pages keep raw `cronExpression` and `timeZone` side by side, with a button-driven generator for `Every day`, `Weekdays`, or `Custom cron`.
- Batch page uses `Merge` / `Replace`, prefill from existing automations, and prefill from the review-feature roster.
- Detail page adds `Run now` plus a recent-runs table for the selected automation.

Follow-on Work

- Business-day due-date rules instead of naive `now + N days`.
- Team/vendor delivery-pool routing instead of a single required team.
- Richer run history filters and request/project drill-down links.
