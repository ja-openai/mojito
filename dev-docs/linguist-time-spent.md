# Linguist time spent report

The admin report at `/settings/admin/linguist-time-spent` reads persisted review-project
assignment-window statistics. Recompute is an explicit, separate action.

## Translator search

The Translator picker searches names, usernames (including email addresses), and user IDs,
case-insensitively. It includes disabled and former users so historical work stays accessible.
There is no separate email field: an account whose username is a legacy alias can be found by
that alias or its name. Selecting a translator changes the draft filter; **Apply** refreshes
the report, while **Recompute** uses the currently selected filters.

## Pagination

Each table displays up to 25 rows per page, with independent **Previous** and **Next** controls:
translator scorecards, linguist/language summaries, and project assignment windows. Applying
filters resets all three to page 1. Paging uses the applied filters and never recomputes data.
The overall summary always includes all matching assignment windows, regardless of page.

`GET /api/admin/linguist-time-spent` accepts zero-based `scorecardPage`, `linguistPage`, and
`detailPage` parameters, defaulting to 0. `summaryLimit` controls each aggregate table's page
size and `detailLimit` controls assignment windows; both retain the API default of 100 and
maximum of 500. The response adds `translatorScorecardsHasNext`, `linguistsHasNext`, and
`windowsHasNext`. Existing list fields and asynchronous report polling remain unchanged.

Queries fetch one extra row to detect the next page without counting all groups. Stable
tie breakers keep equally ranked rows ordered consistently. This is pagination of current
statistics, not a frozen report snapshot; recomputed data can change between requests.
