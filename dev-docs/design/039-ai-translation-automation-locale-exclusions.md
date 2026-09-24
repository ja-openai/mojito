# AI translation automation locale exclusions

An included repository can exclude individual locales from AI translation automation. Configure
this under **Settings → System → AI translation automation**, alongside the existing repository
selection, and save with the rest of the automation settings.

All configured exclusions are shown together with their repository names and locale tags, including
after reloading the page. Use **Add repository** to configure another repository, **Edit** to change
its excluded locales, or **Remove** to clear only that repository's rule. These changes are drafts
until the page's **Save** action succeeds.

The locale selector shows the selected repository's target locales and any existing exclusions.
**Show all locales** allows a language to be excluded before it is enabled on the repository.
Clearing the selection includes all of that repository's target locales again. Rules remain saved
and visible as inactive when a repository is temporarily removed from the automation's scope.
Rules for unavailable repositories remain visible by repository ID. Neither scope changes nor
editing another repository silently deletes these rules.

## Behavior

- Scheduled runs and the automation page's **Run now** apply the same exclusions.
- Matching uses exact canonical locale tags: excluding `fr` does not exclude `fr-CA`.
- Other repositories and direct manual AI translation runs are unaffected. JSON-config localization
  automation, human translation, locale inheritance, and AI Review retain their existing behavior.
- Repository include/exclude selection still determines which repositories can run. Locale rules
  only narrow that selection; they never add a repository to the automation.
- If every target locale is excluded, the scheduler creates no translation task for that repository.
- Locale selection is captured when a task is scheduled. Saving a rule does not cancel or rewrite
  tasks that are already queued or running.
- Saving automation settings makes included repositories eligible for reconsideration on the next
  run, even without a new translation change. This allows clearing an exclusion to resume existing
  untranslated work. The configuration check uses a completed run's creation time, so a task queued
  before the change cannot consume that update merely by starting later.

The existing eligibility checks still use completed runs from all AI Translate entry points.
Distinguishing direct manual and JSON-config runs from this automation is separate work; those runs
can still advance the shared repository watermark. A configuration save during a scheduling sweep
can also be masked by a later task created with that sweep's older settings snapshot. Tracking the
actual configuration version used by each run remains part of `AI-TRANSLATE-05`.

## Storage and API

Migration `V125__AI_Translate_Automation_Locale_Exclusions.sql` adds a nullable JSON-text column to
`ai_translate_automation_config`. Existing rows behave as an empty map. The existing admin-only
`GET` and `PUT /api/ai-translate/automation` carry `excludedLocaleTagsByRepositoryId`, for example:

```json
{ "excludedLocaleTagsByRepositoryId": { "42": ["fr"] } }
```

Writes validate repository IDs and canonicalize locale tags against Mojito's locale catalog.
An empty map clears all locale rules. Omitting the field preserves saved rules for compatibility
with existing clients. The schema migration must be applied with the backend release.

## Validation

Database-independent service and scheduler tests cover normalization, per-repository scope,
exact-locale matching, all-locales-excluded handling, settings changes, and old-client updates.
Frontend behavior tests cover repository selection, saved exclusions, clearing, and the shared
save flow. Local browser verification uses fixture data; applying the migration and exercising a
deployed automation remain release-time checks.
