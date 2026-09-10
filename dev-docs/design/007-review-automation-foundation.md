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
- Admin-only frontend pages for review automation list, detail edit, and batch update/create.
- Warning when selected review features are already used by another enabled automation.
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
- Enabled automations may share review features. Sequential runs skip text units already covered by open review projects; overlapping shared-feature runs can still send the same text units more than once, so admins should stagger schedules when sharing features.
- Review-feature deletion is blocked if the feature is referenced by an automation.
- Scheduler synchronization happens after automation CRUD commits, so Quartz stays aligned with saved config.
- Cron execution runs as the system user and reuses the same feature-based review-project creation path as manual creation.
- Automated creation always excludes text units already covered by any open review project for the same `tmTextUnit + locale`.
- Manual and automated creation can skip default translator assignment while still keeping team and PM assignment.
- Manual creation accepts an optional `maxWordCountPerProject` and reuses automation's source-word splitter for selected text units, repositories, and review features. Omitted or null means no splitting; a supplied limit must be a positive integer. Strings stay whole, so one string can exceed the limit. Locale results count all generated projects.

Slack Notifications

- Team Slack settings select the client and channel. All manual and automated requests use the same compact notification format; there is no format selector on teams or automations.
- The channel message contains a linked request title and deadline, with an emergency marker when applicable. When projects have different deadlines, the parent labels the earliest one. Link and media previews are disabled on the parent.
- Thread details include the request ID, review word counts, type, due dates, description (up to 3,000 characters) or automation source, screenshot/attachment links, the full locale list, and mapped PM/translator mentions. Review word counts sum project workloads within each locale, including split projects and terminology phases; differing locale totals are shown as a range, followed by the total across locales. They are not a distinct request source-word count.
- Screenshot links are read from the persisted request attachments, including files saved during creation or a team-change update. At most five links appear, followed by a link to the request for the rest. They retain Mojito sign-in requirements; inline Slack image previews would require separate file-upload integration.
- Existing request-wide and individual assignment updates use the saved thread when its client/channel matches. A missing or mismatched thread causes Mojito to create a compact request parent before posting the update. Existing parent messages are not rewritten.
- Mojito does not discover or adopt handoff threads created by other Slack workflows. Channel changes do not migrate old Slack messages.
- The parent timestamp is saved before posting the details reply. A failed reply leaves the parent available for later notifications; an unsuccessful parent post or missing timestamp stops delivery of the reply. Delivery remains best-effort, without an automatic retry queue.
- Notification formatting requires no database migration.

The September 9 follow-up asks for title and deadline on one line, with word count in the thread. See the [format discussion](https://openai-corpws.slack.com/archives/C09UYSY3SAD/p1788974752128659) and [reassignment discussion](https://openai-corpws.slack.com/archives/C09UYSY3SAD/p1788995358009309).

Example channel message:

```text
*<https://mojito.example/review-projects?requestId=44|Checkout review>* — Due: 2026-09-10 17:00 PDT
```

Example reply:

```text
*Review request details*
Request: <https://mojito.example/review-projects?requestId=44|request #44>
Review words: 1,044 per locale · 2,088 total
Type: Normal
Due: 2026-09-10 17:00 PDT
Description: Check wording in the checkout flow. Keep CTA language consistent across locales.
Screenshots / attachments: <https://mojito.example/api/images/checkout.png|checkout.png>
Locales (2): de-DE, fr-FR
Assigned PMs: <@U_PM>
Assigned Translators: <@U_TRANSLATOR_DE> (de-DE), <@U_TRANSLATOR_FR> (fr-FR)
```

Frontend Notes

- List page mirrors review features: search, enabled filter, result-size control, create modal, hover edit/delete.
- Create/detail pages keep raw `cronExpression` and `timeZone` side by side, with a button-driven generator for `Every day`, `Weekdays`, or `Custom cron`.
- Batch page can prefill from existing automations or the review-feature roster. Apply mode lives on the CTA: `Apply updates` upserts listed rows only, while `Replace enabled set` upserts listed rows and disables enabled automations omitted from the batch.
- Detail page adds `Run now` plus a recent-runs table for the selected automation.
- Manual review-project creation can also use a direct repository scope. Deep links use `/review-projects/new?scope=repositories&repositoryIds=<id>` with repeated `repositoryIds` for multi-repository setup.
- The manual creation form offers **Max word count per project (optional)** for every scope, initially blank to preserve existing behavior.

Follow-on Work

- Business-day due-date rules instead of naive `now + N days`.
- Team/vendor delivery-pool routing instead of a single required team.
- Richer run history filters and request/project drill-down links.
