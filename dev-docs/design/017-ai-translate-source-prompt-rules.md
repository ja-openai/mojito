# AI Translate Source Prompt Rules

Context

- AI Translate has global prompt text from `AiTranslateType`, optional request prompt suffixes, and admin-managed locale prompt suffixes at `/settings/system/ai-translate/prompt-suffixes`.
- Locale prompt suffixes apply to every source string translated into that locale. That is too broad for source-format-specific guidance.
- Some source strings use square brackets as user-editable slots or inline action markers:

```text
I'm filling out [tax form] as [an individual/a business etc.]. How do I fill out [fields]?
[Upload form]
```

- These strings need extra model instructions, but unrelated strings in the same locale should keep the normal prompt.
- Markdown links such as `[label](https://example.com)` should not trigger the bracket guidance for the first implementation.

Implementation Status

- Implemented for no-batch AI Translate with admin-managed regex rules at `/settings/system/ai-translate/source-prompt-rules`.
- Rules are global, ordered by priority, testable in the UI, and composed between locale prompt suffixes and ad hoc request suffixes.
- No-batch requests are partitioned by matched source-rule id set within each screenshot group, so source-rule prompt suffixes do not leak to unmatched strings.
- Legacy batch mode is intentionally not split yet; track that separately after no-batch production usage.

Goals

- Add admin-managed source prompt rules that append prompt guidance only when a source string matches a configured rule.
- Make the first rule regex-based and capable of detecting square-bracket text while ignoring Markdown links.
- Keep locale prompt suffix behavior unchanged and composable with source-rule suffixes.
- Prevent matched strings from changing the prompt for unmatched strings in the same provider request.
- Give admins a safe UI to create, edit, enable/disable, test, order, and delete rules.

Non-Goals

- Full Markdown parsing or a generic source-format classifier.
- Per-repository or per-asset-path scoping in the first implementation.
- Automatically rewriting source strings, masking placeholders, or enforcing target integrity checks.
- Changing legacy batch mode unless it becomes necessary for parity; the first implementation should focus on no-batch AI Translate.

Use Case

Create an enabled rule named `Bracketed editable slots`:

- Match type: `REGEX`
- Source regex: `\[[^\]\r\n]+\](?!\()`
- Prompt suffix:

```text
Square-bracketed text in the source marks editable user-provided content or UI actions. Preserve the bracket structure and translate only the natural-language text inside the brackets when appropriate. Do not drop or invent bracketed slots.
```

The regex is intended to be used with `Pattern.find()`, not full-string matching. It matches `[tax form]`, `[fields]`, and `[Upload form]`; it does not match the bracket portion of `[Upload form](https://example.com)` because the closing bracket is followed by `(`.

Data Model

Add `ai_translate_source_prompt_rule`:

- `id`
- `created_date`
- `last_modified_date`
- `name` (`varchar`, unique, required)
- `description` (`longtext`, nullable)
- `enabled` (`tinyint(1)`, required)
- `priority` (`int`, required)
- `match_type` (`varchar`, required; initially only `REGEX`)
- `source_regex` (`longtext`, required)
- `prompt_suffix` (`longtext`, required)

Indexes:

- Unique index on `name`.
- Non-unique index on `(enabled, priority, id)` for deterministic active-rule loading.

Keep the table global in the MVP. If source-rule usage grows, add scope tables later instead of baking nullable scope columns into the first model:

- `ai_translate_source_prompt_rule_repository`
- `ai_translate_source_prompt_rule_locale`
- `ai_translate_source_prompt_rule_asset_path`

Backend API

Add an admin-only REST resource under `/api/ai-translate/source-prompt-rules`:

- `GET /api/ai-translate/source-prompt-rules`
  - Returns all rules ordered by `priority asc, name asc`.
- `PUT /api/ai-translate/source-prompt-rules`
  - Upserts by `id` when present, otherwise creates.
  - Validates non-empty name, source regex, prompt suffix, and unique name.
  - Compiles regex before saving and returns `400` on invalid syntax.
- `DELETE /api/ai-translate/source-prompt-rules/{id}`
  - Deletes a rule.
- `POST /api/ai-translate/source-prompt-rules/test`
  - Accepts `sourceRegex` and `sourceText`.
  - Returns whether it matches and a small list of matched spans/snippets.
  - Does not require saving the rule.

Service Contract

Introduce `AiTranslateSourcePromptRuleService`:

- `getAll()`
- `upsert(SourcePromptRuleInput input)`
- `delete(long id)`
- `test(String sourceRegex, String sourceText)`
- `getMatchedPromptSuffixes(String sourceText)`

`getMatchedPromptSuffixes` should:

- Load enabled rules ordered by `priority asc, id asc`.
- Compile regex patterns with a bounded cache or precompiled active-rule snapshot.
- Use `Pattern.find()` against `TextUnitDTO.getSource()`.
- Return prompt suffixes for all matched rules in deterministic order.
- Fail closed for invalid persisted regexes: log the rule id/name and skip the rule, rather than failing a translation run. Invalid regexes should only happen after manual DB edits because API writes validate.

Prompt Composition

The existing no-batch path currently builds one instruction string before grouping requests:

1. Base prompt from `AiTranslateType`.
2. Locale prompt suffix plus request prompt suffix via `AiTranslateLocalePromptSuffixService`.
3. `AiTranslateService.getPrompt(...)`.

Source prompt rules add a third suffix layer:

1. Base prompt.
2. Locale prompt suffix.
3. Source-rule prompt suffixes.
4. Request prompt suffix.

Source-rule suffixes should be combined with the same whitespace-normalizing behavior used by locale prompt suffixes.

Request Partitioning

No-batch AI Translate sends one shared `instructions` string for each grouped provider request. To avoid applying bracket instructions to unrelated strings, source prompt rules must affect grouping:

- Continue grouping by screenshot first.
- Within each screenshot group, partition text units by their matched source-rule id set.
- Build one provider request per `(screenshotUUID, matchedRuleIds)` bucket.
- Use the rule bucket's combined source-rule suffixes when building `instructions`.
- Preserve existing glossary filtering, related strings, lineage, timeout, and import behavior inside each bucket.

Example:

- Text units A and B share a screenshot.
- A matches `Bracketed editable slots`; B matches no rule.
- Mojito sends two provider requests with the same screenshot image:
  - Request 1: A with bracket prompt suffix.
  - Request 2: B without bracket prompt suffix.

This may increase request count when repositories mix source formats heavily. That is the correct tradeoff for prompt isolation.

Legacy Batch Mode

The legacy batch path also uses one prompt per batch file. There are two viable options:

- MVP: Do not apply source prompt rules to legacy batch mode. Document this in the settings page and API response.
- Later: Split batch request lines into separate batch files by matched rule set, mirroring no-batch partitioning.

Prefer the MVP option unless batch mode is still operationally important for these strings. The no-batch path already has normalized lineage and is the safer first integration point.

Frontend

Add a settings page at `/settings/system/ai-translate/source-prompt-rules`.

Use the existing settings page patterns from locale prompt suffixes and review automation:

- Table columns: enabled, priority, name, match type, regex preview, prompt suffix preview, updated at.
- Create/edit panel with:
  - Name.
  - Description.
  - Enabled toggle.
  - Priority number input.
  - Match type select disabled to `Regex` until more matchers exist.
  - Source regex textarea.
  - Prompt suffix textarea.
  - Test source textarea and `Test regex` button.
- Inline validation:
  - Required fields.
  - Regex compile errors from the backend test/upsert endpoints.
  - Warning that rules are currently no-batch only if the MVP excludes legacy batch.
- Row actions: edit, duplicate, delete.

Add a link from the system AI Translate settings area near `Prompt suffixes`.

Observability and Audit

- Include matched source prompt rule ids/names in no-batch lineage request payload metadata so operators can explain why a prompt was augmented.
- Add low-cardinality metrics:
  - Counter for source prompt rule matches tagged by rule id or a bounded rule key only if the rule cardinality remains small. If not, avoid rule tags and emit aggregate `matched=true/false`.
  - Counter for skipped invalid persisted regexes.
- Add debug logs when a request bucket includes source-rule suffixes. Avoid logging full source text.

Security and Validation

- Source regexes are admin-only configuration.
- Compile regexes on write and test.
- Add a match timeout if Java regex matching becomes a measurable risk. Java `Pattern` does not provide a native timeout, so the practical MVP controls are admin-only writes, short source strings, regex review in UI, and metrics for translation latency/request failures.
- Do not expose source text in logs from the test endpoint or translation runs.

Testing

Backend tests:

- CRUD validation for source prompt rules.
- Regex test endpoint returns match/no-match and rejects invalid regex.
- Bracket regex matches `[tax form]`, `[an individual/a business etc.]`, `[fields]`, and `[Upload form]`.
- Bracket regex does not match `[Upload form](https://example.com)`.
- No-batch request partitioning keeps matched and unmatched text units in separate provider requests.
- Multiple matching rules combine suffixes by priority.
- Locale suffix, source-rule suffix, and request suffix compose in the expected order.

Frontend tests:

- Page loads and renders empty, loading, error, and populated states.
- Create/edit validates required fields.
- Test panel displays regex errors and matched snippets.
- Enable/disable and priority edits update the table without losing sort order.

Rollout

1. Add the database table, entity, repository, service, and admin REST endpoints.
2. Add the settings page with regex testing.
3. Integrate source-rule matching into no-batch request partitioning.
4. Add the initial bracket rule through migration only if we want a default rule in every environment; otherwise create it manually in staging/production through the UI.
5. Verify no-batch lineage shows when a request used source prompt rules.
6. Reassess legacy batch support after the first production use case.

Open Questions

- Should the first bracket rule be seeded by migration or created manually per environment?
- Should prompt-rule suffixes apply before or after ad hoc request suffixes? This design uses request suffix last so manual runs can override or further constrain the full prompt.
- Do admins need repository/locale scoping immediately, or is global enough for the bracket use case?
- Should matching inspect only `source`, or also `comment`/source description when source text alone is insufficient?
