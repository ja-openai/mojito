# Review Project search

Review Project's context panel includes an **opt-in Search tab** for looking
up current translations across repositories. The tab reuses Workbench's text
search component, including search attributes, match types, Add/remove conditions,
and Match all/any. It starts with Translation + Contains, all repositories, and
the active project locale. Repository and locale selectors use the same controls
and presets as Workbench; reviewers can narrow repositories, choose other
configured locales, or return to the active locale.

## Preview opt-in

The tab starts hidden for everyone, including admins. Enable it in **My Settings >
Review Project search** (`/settings/me#review-project-search`) by checking **Show
the Search tab in Review Project (preview)** and selecting Save. Uncheck it and
Save to disable; Reset stages the default off state. This personal preference is
saved separately for each Mojito username in the current browser, like the
assisted-editor opt-in. Switching accounts does not carry it to another user.

Changes apply to open review pages in the same browser. Disabling closes Search,
clears its local query state, and returns an active Search panel to Glossary while
preserving the translation draft. Any authenticated reviewer can opt in; this
controls preview visibility and uses the existing Workbench search permissions.

## Interaction

Search runs on Enter or Search. Changing the query or scope clears old results
until the next search. Blank conditions are ignored, and at least one condition,
repository, and locale is required. The tab keeps its state when switching context
tabs on the same row; switching review rows or projects resets it. Keyboard events
are isolated from review save/navigation shortcuts, including IME Enter handling.

## Shared search behavior

The shared control offers Translation, Source, Comment, String ID, Asset path,
Location, Plural (other), and TextUnit IDs with Exact match, Contains, iLike, and
Regex. It uses the existing hybrid text-unit search API and normalization, so
literal wildcard escaping and advanced query behavior match Workbench. The
separate demo-only Whole word/Fuzzy controls and endpoint have been removed.
Workbench's own scope, status/date filters, and workset behavior remain unchanged.

Search reads current variants for used strings. Pages contain 50 matches, with a
51st result determining whether Next is available. An opt-in ordering by text-unit
ID, locale ID, and asset-text-unit ID makes offset pagination stable without a full
count scan. The existing authenticated text-unit search access rules apply.

## Result attribution

Each result shows the repository, locale, string ID, source, current translation,
and **Saved by** username. The username comes from the current translation
variant's creator in the same search query, via a left join. It does not use the
source creator, the current-variant marker's modifier, or a newer noncurrent
translation. Missing creator metadata is shown as unknown; untranslated results
have no attribution. Open string opens the text-unit detail in a separate tab.

Saved by identifies the account that created the variant. An imported translation
can name the importer; upstream translator identities currently live in import
metadata and cannot reliably be attributed from the search projection. Showing
true upstream authors would require persisting that provenance with each variant.
The UI deliberately labels this distinction instead of treating an importer as
the translation author.

Frontend tests cover shared controls, default and edited scopes, compound queries,
pagination, author display, stale-request isolation, errors, and keyboard handling.
Preference tests cover default-off behavior, staged Save/Reset, account isolation,
and disabling an active panel from the same or another browser tab.
Backend tests verify current-variant creator attribution and unknown creators with
real MySQL, and stable pagination. These checks do not establish deployment.
