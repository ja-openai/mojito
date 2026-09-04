# Translation search in review and detail pages

Review Project's context panel includes an **opt-in Search tab**, and the Workbench
text-unit detail page includes a **collapsible Search section below Glossary**, for
looking up current translations across repositories. Both use the shared
`components/TranslationSearchPanel.tsx`, which reuses Workbench's text
search component, including search attributes, match types, Add/remove conditions,
and Match all/any. It starts with Translation + Contains, all repositories, and
the active page locale. Repository and locale selectors use the same controls
and presets as Workbench; reviewers can narrow repositories, choose other
configured locales, or return to the active locale.

## Preview opt-in

Both entry points start hidden for everyone, including admins. Enable them in
**My Settings > Translation search** (`/settings/me#review-project-search`) by
checking **Show Search in Review Project and text-unit details (preview)** and
selecting **Save changes** in the shared sticky footer. Uncheck it and save to disable.
**Discard changes** restores all saved settings; **Restore defaults** stages their
defaults, including the default off state for Search, until Save changes is selected.
This personal preference is saved to the signed-in Mojito account, like the
assisted-editor opt-in. Switching accounts does not carry it to another user.
Existing browser opt-ins are available as a draft for the first Save in My Settings.
The settings anchor is retained. See [account preferences](035-account-preferences.md).

Changes apply immediately to mounted review and detail pages after a successful save.
Other browser tabs and devices refresh account settings when refocused. Disabling closes
Search, clears its local query state, and returns an active Review Project Search
tab to Glossary while preserving the translation draft. Any authenticated reviewer can opt in; this
controls preview visibility and uses the existing Workbench search permissions.

## Interaction

Search runs on Enter or Search. Changing the query or scope clears old results
until the next search. Blank conditions are ignored, and at least one condition,
repository, and locale is required. The tab keeps its state when switching context
tabs on the same row; switching review rows or projects resets it. Keyboard events
are isolated from review save/navigation shortcuts, including IME Enter handling.

On text-unit detail pages, Search starts collapsed and mounts on first expansion.
Collapsing retains the query and results and disables automatic refetching.
A search already in progress may finish while hidden.
Changing the string, locale, or account resets the section and query. Search does
not change the translation draft or Workbench workset. Source-only detail pages
have no active translation locale and do not show the section.

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

### Whole-word search with Regex

Choose **Regex** in the search options and put `\b` before and after the word.
For example, `\bcat\b` finds `cat` within a sentence but does not match `catalog`.
Enter single backslashes directly in the search box, without `/…/` delimiters.
The shared dropdown documents these boundary and case-control patterns in
Workbench, Review Project, and text-unit details.

| Pattern | Meaning |
| --- | --- |
| `\bsanté\b` | Whole word, using the database's default case behavior |
| `(?i)\bsanté\b` | Whole word, ignoring case: matches both `santé` and `Santé` |
| `(?-i)\bSanté\b` | Whole word, matching case: matches `Santé`, not `santé` |

Word boundaries do not choose case sensitivity. Regex uses MySQL's ICU engine;
without an explicit flag, case behavior follows the database collation and can
differ between environments. Use `(?i)` or `(?-i)` when case matters. A result
matches if the pattern occurs anywhere in its selected field, even if another
part of that text contains a longer word. Regex word boundaries follow character
rules; they are not a language-specific word tokenizer.

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
Preference tests cover default-off behavior, staged saving and restoring defaults, account isolation,
and disabling an active panel when the account preference refreshes.
Detail-page tests cover lazy expansion, shared search defaults and results,
collapse/reopen state, resets, and draft preservation when Search is disabled.
Backend tests verify current-variant creator attribution and unknown creators with
real MySQL, and stable pagination. These checks do not establish deployment.
