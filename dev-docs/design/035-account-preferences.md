# Account preferences

My Settings (`/settings/me`) stores personal preferences in the backend so they
follow the signed-in user across browsers and devices. The six fields are the
Workbench result limit, preferred locales, Review Project shortcut bar, assisted
translation editor, translation search preview, and default Review Project teams.
These preferences do not grant repository, locale, or team permissions.

## Saving and restoring

Edits remain a draft until **Save changes** succeeds. One atomic PATCH saves only
changed fields, preserving unrelated concurrent changes. Failed requests retain
the entire draft for retry. **Discard changes** returns to the latest loaded account
settings. **Restore defaults** stages defaults for the same Save action.
Clean forms refresh with account data; a background refresh does not discard edits.

The frontend loads account preferences before mounting authenticated pages, so
initial workset limits and team filters use the saved values. Saves update the shared
query cache; other tabs/devices refresh on focus. An unavailable initial request
shows a retry state. Browser storage is not used as an account fallback on errors.
Existing URL and per-tab filter state still take precedence over default filters.
The shortcut-bar control within a Review Project saves that one field immediately
and reports failures without changing the saved setting.

## Existing browser settings

For an account with no saved preferences, My Settings prepares old browser values
as an unsaved draft. Editor, search, and team values use their existing username
keys; older workset, locale, and shortcut values have no owner information and are
only adopted by an explicit Save. Unscoped editor opt-ins are never imported.
The browser keys remain untouched. Users may discard the draft or restore and save
defaults instead. Once account preferences are initialized, all consumers and the
settings page use server values, even if old browser keys remain.

## Backend

`GET /api/users/me/preferences` returns the current account's settings, including
`initialized: false` when no row exists. `PATCH` accepts only the six preference
fields and derives ownership from the authenticated user. A dedicated
`user_preferences` row has a unique user ID and cascades on user deletion. The
transaction locks the user before loading or creating the row, serializing even
concurrent first saves without involving unrelated user-profile writes. PATCH then
reads preferences with `FOR UPDATE`, so it merges the latest committed values even
under MySQL `REPEATABLE READ` when an earlier query established a transaction
snapshot before waiting for the user lock. GET uses an ordinary nonlocking read.

Null workset size means the application default; null shortcut help means the role
default (bottom for translators, header for other roles). Editor and search start
off. Lists default to empty. The API validates JSON types, positive integer workset
sizes up to 2147483647, language tags, bounded lists, and existing enabled team IDs.
Only admins and PMs can change default teams; existing team access rules apply.
Last successful writes to the same field win; different-field PATCHes are merged.

Flyway migration `V108__User_Preferences.sql` creates the table. Deploy the backend
and schema with the frontend; an older backend does not provide this endpoint.
Service tests on HSQL and MySQL verify persistence, concurrent saves, account isolation, validation,
resets, profile-update independence, and cascade deletion. Authorization tests
cover the route. A disposable MySQL 8.0.34 check applies the exact V108 SQL and
verifies that the locking read preserves concurrent first saves and existing-row
partial updates under REPEATABLE READ, including cascade deletion. Migration and
account readback on the deployed database remain separate rollout checks.
