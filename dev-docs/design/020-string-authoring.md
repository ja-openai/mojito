# String Authoring

## Context

Product and localization teams sometimes need to create strings in Mojito before the source code
change has landed. A normal source import without asset/code references can mark those strings
unused, which hides them from search, AI translation, review, and other used-string workflows.

The MVP treats this as a temporary branch workflow instead of a new source-string lifecycle. Users
author the strings on a branch named with the `authoring/` prefix. Because the branch has its own
asset extraction payload, the strings stay `used` on that branch and can move through the existing
search, AI translation, and review paths before the real code sync reaches `master`.

## MVP

- Use the existing repository, branch, asset, text unit, and pollable task models.
- Mark authoring branches by naming convention only: `authoring/<name>`.
- Add an admin-only top-level `String Authoring` tab at `/string-authoring`; keep the old
  `/settings/system/string-authoring` and `/settings/admin/string-authoring` URLs as redirects.
- Let users select a repository, select or create an authoring branch, select an asset path, and edit
  source strings.
- Auto-select the only available non-virtual asset when a repository has exactly one, and offer a
  deterministic staging asset path for teams that only need a temporary bundle.
- Require an explicit string id by default. Admins can opt into generated ids per string; the
  generated id is the hex MD5 of normalized source plus normalized description.
- Save by calling the normal asset processing path with an extracted-content JSON payload. This keeps
  imported rows `used` for that branch without changing sync semantics.
- Show the generated string id, branch name, and asset path so developers can copy the exact code
  identifiers into the source change.
- Let admins filter authored strings by active, unused, or all status. Removing a saved string from
  the active bundle saves a new extraction payload without that string; unused rows can be restored
  by switching to the unused/all filter and saving them active again.
- Let admins send saved authored strings into the existing review-project creation flow. The
  String Authoring page passes the saved text unit ids to `/review-projects/new`, while locale,
  due-date, type, and assignment choices stay centralized in the normal review setup UI.
- Let admins download a developer handoff JSON containing repository, branch, asset path, and source
  string ids. Strings that rely on generated ids must be saved first so the exported JSON contains
  concrete identifiers.
- Let admins copy a developer link that deep-links back to the same repository, authoring branch,
  and asset path via `/string-authoring?repositoryId=...&branchName=...&assetPath=...`.
- Show authoring branches newest-created first and let admins manually delete existing
  `authoring/` branches from the String Authoring page. Deletion uses the existing async branch
  deletion job and is restricted to authoring branches.
- Set a cleanup date on authoring branches by default, initially 7 days from save. Admins can edit
  the date before saving when a branch needs a shorter or longer staging window.

## Branch Selection

The branch selector defaults to branches that follow the `authoring/` convention. An alternate
scope can show all branches, including Mojito's default branch row whose branch name is `null`, so
users can understand what exists in the repository. Save and delete operations still require the
`authoring/` prefix. This keeps the UI useful during transition without making arbitrary feature
branches part of the authoring workflow.

## Data Model And Performance

The current model is enough for the first version:

- Branch lookup is repository-scoped and optionally prefix-filtered by `authoring/`.
- Asset lookup is repository-scoped, active, non-virtual, path-ordered, and capped.
- String lookup uses `TextUnitSearcher` with repository, branch, used filter, root locale, optional
  asset path, and an explicit limit.
- Cleanup uses a nullable `branch.cleanup_date` field and an index on that column. The branch type is
  still inferred from the `authoring/` prefix, so the cleanup path does not need a separate branch
  metadata table.

## Cleanup

Authoring branches are temporary staging branches. The UI defaults cleanup to 7 days from save and
lets admins change the date before saving. A scheduled cleanup job is enabled by default, runs daily,
selects due non-deleted `authoring/` branches by `cleanup_date`, and enqueues the existing async
branch deletion job. The next version should reconcile authoring branches against `master`, surface
candidates that are already owned by code, and optionally advance cleanup dates when a branch is
resolved earlier than its default retention window.

## Follow-Ups

- Add a reconciliation view for authored strings whose ids now exist on `master`.
- Decide whether `Branch` needs explicit temporary/source-authoring metadata beyond the naming
  convention once real cleanup usage is clear.
- Extract shared string-authoring components with JSON config localization if both pages continue to
  evolve in the same direction.
