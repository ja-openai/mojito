# JSON Config Localization V0

## Context

Some strings are authored outside the application release cycle. When the source text already moves
out of band, translation delivery should use the same path instead of waiting for a product release.

The v0 implementation keeps the repository model unchanged: a regular Mojito repository plus one
`json_config_localization` setup row that stores the JSON config localization settings for that
repository. The virtual asset path still acts as the authored bundle boundary. Setups can be generic
JSON or provider-backed; the first provider adapter is Statsig dynamic configs.

## Use Cases

1. Create a regular repository for the authored source strings. The repository row is not flagged;
   JSON config localization is tracked in a separate setup table.
2. Open `/settings/system/json-config-localization`. The landing page lists repositories that have a saved
   JSON config localization setup and can temporarily show all repositories to bootstrap a setup.
   The editor supports direct links to `/settings/system/json-config-localization/{repositoryId}` with
   an optional `assetPath` query parameter. A setup can be removed from this list without deleting
   the Mojito repository or its text units.
3. Add, edit, and remove strings from the authored bundle. The UI saves by replacing the selected
   virtual asset text units with rows marked `used`; removed rows become unused and can be restored
   through the unused/all filters.
4. Show per-string target locale readiness. A target counts as translated when it has target text,
   and reviewed when the current variant is approved and included in localized files.
5. Trigger AI translation for a selected saved string or all active saved strings. Human review still
   happens through normal Mojito review/workbench surfaces for v0.
6. Export a locale-keyed JSON bundle for downstream copy/paste into an out-of-band configuration
   path. Export includes active (`used`) strings only and omits missing target locale values so the
   consuming client can apply source fallback.
7. Paste a JSON Schema and source config, confirm the guessed mapping, map output locale keys,
   extract translatable strings into flat Mojito ids, save the setup, then export the translated
   result back into the source config shape.
8. Optionally connect a setup to a Statsig dynamic config id. Mojito can pull the current Statsig
   schema/default value into the same extraction flow and push the localized config back as the
   dynamic config `defaultValue`.

## Schema-Assisted JSON Config Export

The schema is useful as an output contract, but it does not reliably encode translation intent. V0
therefore treats schema parsing as a detector, not a decision-maker. The backend guesses and exposes
a mapping profile:

- collection key, for example `hotTakes`
- stable item id field, for example `id`
- translations field, for example `translations`
- source locale, for example `en-US`
- translatable fields, for example `title, body`

The source config remains the authoring template. Extraction turns each item/field pair into flat
Mojito strings such as `usa_brazil_blink.title` and `usa_brazil_blink.body`. Export reconstructs the
localized config by cloning the source config and filling each locale under the translations field.
Missing target fields are omitted instead of copied from source.

The output config's locale keys are not always Mojito locale tags, so the config also carries an
output-locale mapping. The setup stores this as `mojitoLocale -> outputLocale` because that is what
export needs, while the CLI accepts a Statsig-friendly `outputLocale:mojitoLocale` mapping, for
example:

```text
-lm 'de-DE:de,fr-FR:fr,zh-TW:zh-Hant'
```

The locale mapping is advanced config and lives with the saved JSON config localization setup, not as a
prominent main-editor control.

## Provider Integration

Provider metadata is stored on the JSON config localization setup, not on the repository row:

- `provider`: `GENERIC_JSON` or `STATSIG`
- `provider_config_id`: external provider config id, for example a Statsig dynamic config id

Statsig credentials are server configuration, not database state. The backend reads
`l10n.json-config-localization.statsig.api-key`, defaults the Console API base URL to
`https://statsigapi.net/console/v1`, and sends the `STATSIG-API-VERSION` header as `20240601`.
`dry-run` push can be enabled with `l10n.json-config-localization.statsig.dry-run-push=true`.

## API Boundary

JSON mapping, extraction, virtual asset replacement, and localized-config export live in the Java
webapp layer. The frontend is a thin editor over the process endpoints and the CLI is a file-backed
adapter over the same endpoints:

- `POST /api/json-config-localizations/detect-mapping`
- `POST /api/json-config-localizations/extract`
- `POST /api/json-config-localizations/repositories/{repositoryId}/extract`
- `GET /api/json-config-localizations/repositories/{repositoryId}/export`
- `DELETE /api/json-config-localizations/repositories/{repositoryId}`
- `POST /api/json-config-localizations/repositories/{repositoryId}/statsig/pull`
- `POST /api/json-config-localizations/repositories/{repositoryId}/statsig/push`

The repository extract endpoint accepts explicit strings from the UI so manual edits are preserved,
or no explicit strings from the CLI so the backend extracts directly from the source config.

## CLI

The v0 CLI intentionally wraps the saved Mojito setup rather than calling an external config API
directly:

```text
mojito json-config-pull --config-id hot_takes -r worldcup \
  --schema-file schema.json --source-file source.json \
  -lm 'de-DE:de,fr-FR:fr,zh-TW:zh-Hant'

mojito json-config-export -r worldcup -o localized.json

mojito json-config-push --config-id hot_takes -r worldcup -o statsig-response.json
```

`json-config-pull` sends the schema, source config, extraction mapping overrides, and output locale
mapping to the Mojito API. The server saves the setup and replaces the configured virtual asset with
extracted source text units. `json-config-pull --translate` additionally triggers the existing
repository machine-translation flow for target locales. `json-config-export` writes the localized
JSON config from saved Mojito translations without calling a provider. `json-config-push` asks Mojito
to push the saved setup's localized config through the configured provider adapter.

## V0 Boundaries

- One JSON config localization setup per repository.
- No repository model flag.
- No hidden candidate repository lifecycle.
- No arbitrary JSON Schema-to-TMS inference. Users confirm or override the detected mapping.
- No custom review-project bundling yet.
- No JSON-config-specific permission model beyond the existing admin/PM settings gate.
- The CLI is still file-backed for now; backend Statsig pull/push exists and can become the CLI path
  without duplicating extraction/export logic.

## Follow-Ups

- Decide whether the setup model should support multiple named bundles per repository and owners.
- Add a bundled review action that creates or opens a review project for the active strings.
- Add schema validation for pasted source configs and generated localized configs.
