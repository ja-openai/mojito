# Pulling Branch-Only Strings

## Context

String Authoring can stage source strings on `authoring/` branches before the corresponding code
change lands. Those strings are active and translatable in Mojito, but the normal `pull` pipeline
only sees text units extracted from the caller's local source file. As a result, a translated
authored string is omitted until the local source entry exists.

## Decision

Keep this behavior on the existing `pull` command. Locale selection, output paths, translation
status, inheritance, and pull-run recording are already pull concerns; a second command would
duplicate them.

One opt-in flag and two branch selectors control the additional source-unit selection:

- `--pull-with-no-source` includes active text units missing from the matched local source across
  all branches in the asset's current multi-branch state when no branch selector is present.
- `--pull-with-no-source-branches <branch>...` implies the opt-in and limits additional text units
  to the named branches. Branch names are exact, ordered, and repository-scoped.
- `--pull-with-no-source-null-branch` implies the opt-in and selects Mojito's unnamed default
  branch. It can be combined with `--pull-with-no-source-branches`; the selected memberships are
  the union of the default branch and the named branches.

The literal value `null` passed to `--pull-with-no-source-branches` remains a normal, named branch;
it is not an alias for the default branch. With neither branch selector, an explicit
`--pull-with-no-source` continues to include missing units from all active branch memberships.

The options select source-unit membership, not branch-specific translations. Translations remain
shared across branches.

## Output Boundary

The current multi-branch state stores a text unit's id, source, comment, plural metadata, and exact
branch membership, but it does not retain a reusable file-format skeleton. Mojito therefore cannot
safely place a new text unit into every format supported by Okapi.

The first implementation supports JSON files whose root is an object map:

- Flat JSON adds `"id": "source"`.
- FormatJS-style JSON with `removeKeySuffix=/defaultMessage` adds
  `"id": {"defaultMessage": "source", "description": "comment"}`. The configured literal note
  key is used in place of `description` when provided.

Every existing entry must have the selected shape, and source-less ids must be literal top-level
keys. Nested/path-like ids, plurals, and filter options that transform source content or text-unit
names are rejected because the multi-branch state does not contain enough raw skeleton data to
round-trip them safely. An asset with no membership in an explicitly selected branch is left
unchanged, including when that unrelated asset uses another file format.

The augmented source is passed through the existing translation pipeline. This preserves current
status filtering, locale inheritance, `REMOVE_UNTRANSLATED`, escaping, and pull-run tracking.
Unsupported shapes and filters fail explicitly instead of silently producing a partial file. The
implementation also re-extracts each synthesized entry and verifies its stored identity before the
normal localization pipeline runs.

## Selection And Conflicts

- The caller's local source always wins for an id already present in that file.
- The same id and MD5 found through more than one selected branch is deduplicated.
- The same id with different source identity across selected branches is ambiguous and fails with
  an actionable error. The caller can select a narrower branch set.
- Units with no active branch membership are historical and are not included.
- Exact membership comes from `MultiBranchState.branchNameToBranchDatas`. The relational
  `AssetTextUnit.branch` column is not sufficient because it stores only one priority branch.

## Compatibility And Scope

With none of these controls, the request does not load multi-branch state and output remains
unchanged.
The CLI still discovers assets from matched local source files; "no source" means that an
additional text-unit entry is absent from the local source, not that the entire source asset file
is absent. Remote-only asset discovery and non-JSON serializers are separate follow-ups if a real
consumer needs them.
