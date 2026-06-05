# I18n-First Micro-CMS for Product Copy

Context

- Mojito is a translation management system. It should remain outside production runtime request
  paths.
- Product teams often need to manage related pieces of copy that are not a full web page: emails,
  banners, notifications, modals, onboarding cards, help snippets, and copy tests.
- These copy blocks need translation, review, approval, completeness checks, and publish gating as
  first-class behavior.
- Runtime applications should consume immutable published artifacts through Statsig, blob/CDN, or a
  future experience framework. They should not call Mojito directly.

Product Niche

Most headless CMS products model content first and attach localization as a dimension on entries,
fields, locales, or release environments. That works well for pages, media, rich content graphs,
editorial workflows, and omnichannel publishing. The tradeoff is that i18n can become a feature on
top of a broader CMS model: translation memory, source string identity, review state, locale
completeness, and string-level approvals are usually delegated to integrations or add-ons.

This micro-CMS intentionally starts from the opposite direction:

- A content entry is a block of related localizable fields that must translate, review, validate, and
  publish together.
- Each localizable field maps to a Mojito TM text unit, so string identity, translation memory,
  review workflow, and approval state remain native Mojito concerns.
- The CMS layer provides product-copy grouping, metadata, variants, and publish snapshots. It does
  not replace Mojito's translation model.
- The published runtime contract is JSON, not a live CMS API. Mojito is an authoring and publishing
  system, not a production delivery dependency.

This is a useful niche when the content is text-first, operational, and embedded in product
experiences. It is not a fit for page builders, media-heavy sites, large content graphs, or bespoke
editorial workflows.

Inspected Mojito Patterns

- Virtual assets and TM text units: CMS projects reuse a Mojito repository and virtual asset path,
  then register localizable fields through Mojito's virtual text-unit updater so generated
  `TMTextUnit` records remain used/searchable/reviewable by normal Mojito workflows.
- Review/approval state: publish completeness uses current text unit variants with approved status
  and `includedInLocalizedFile` rather than inventing a second translation status. Mojito's deleted
  current-translation marker has no current target, so CMS treats it as missing locale copy rather
  than a publish error.
- Settings admin pages: the MVP follows the existing React settings subpage shell,
  `SettingsSubpageHeader`, settings form/table classes, admin role gating, and React Query data
  loading.
- Export and blob conventions: the artifact is provider-agnostic JSON in Mojito now; delivery jobs
  can later push it to Statsig, blob/CDN, or an experience platform without changing the authoring
  model.
- Existing design docs and tracker: this note records the MVP design and the tracker keeps
  deliberate post-MVP work in the backlog.

Domain Model

Content project

- Owns a scoped set of content types, entries, variants, mappings, and publish snapshots.
- References one Mojito repository and one project-exclusive virtual asset so generated or mapped
  text units cannot bleed across CMS project boundaries.
- Project creation only creates a new dedicated virtual asset path; it rejects adopting a
  pre-existing virtual asset that could already carry unrelated active text units.
- The database carries a checked `asset.cms_managed` discriminator and foreign-keys it with
  asset/repository identity plus stored virtual, managed, and non-deleted markers; the JPA mapping
  carries the same read-only composite FK for generated-schema tests, so direct SQL cannot silently
  adopt an ordinary virtual asset, later clear the marker, or logically delete the backing CMS
  asset without violating the final schema guard.
- Once a project owns that virtual asset, generic virtual-asset source, asset-content/extraction,
  low-level TM source-create, or asset mutation APIs, including legacy `/api/assets` logical delete
  and branch-delete paths, reject writes so CMS copy changes must pass through field mappings and
  their authoring audit trail. The CMS mapping writer opens only a short-lived, asset-scoped
  internal source-registration scope around its dedicated virtual-text-unit batch update.
  Mojito translation/review writes to the mapped text units remain the localization workflow.
- Mapping, completeness preflight, publish, and stable latest-delivery discovery require that
  backing Mojito repository to remain visible and non-deleted; a stale orphaned CMS project cannot
  keep generating or shipping copy.
- Mapping, completeness preflight, publish, and stable latest-delivery discovery also require the
  project asset to remain virtual, non-deleted, and backed by a successful extraction before CMS
  code reads or writes mapped copy.
- Carries a stable `projectKey`, display name, optional description, source locale, enabled flag,
  and delivery hint.
- Disable or orphaned live Mojito backing state blocks new publish and stable latest-delivery
  discovery, while exact immutable version-addressable snapshot export remains available for
  rollback and audit.
- Mutable CMS rows record created/last-modified timestamps plus the authenticated admin actor so
  copy, schema, variant, and mapping changes have an authoring audit trail before publish.

Content type/schema

- Defines the shape of a text-first copy block, such as `email`, `banner`, `nux_card`, or
  `notification`.
- Has a stable `typeKey`, schema version, display name, optional description, and optional metadata
  schema JSON.
- Schema version is server-managed: new types start at `1`, metadata-schema or field-schema
  mutations increment it, and admins cannot set arbitrary artifact contract versions.

Content entry

- Represents one product-copy block, such as `welcome-email` or `workspace-limit-banner`.
- Belongs to a content type and project; the database rejects entries whose content type belongs to
  another CMS project.
- Carries non-localizable metadata JSON for targeting, owner, surface, intent, or rollout context.
- Has lifecycle status such as `DRAFT`, `READY`, or `ARCHIVED`.
- A `READY` entry needs at least one mapped publishable localizable field, and each non-archived
  variant included in the artifact needs at least one mapped field. New entries start `DRAFT` or
  `ARCHIVED`; marking one `READY` rechecks control/candidate invariants, metadata, active
  translatable Mojito mappings, source integrity, and required fields. Later authoring mutations
  that would make a ready block structurally impossible to publish, such as adding an unmapped
  required field, activating an unmapped candidate, or unmapping its last active field, fail until
  the entry is moved back to `DRAFT` or repaired. Optional fields may be omitted, but empty copy
  blocks or copy candidates do not pass readiness, completeness, or publish.

Localizable fields

- Belong to a content type.
- Each field has a stable `fieldKey`, display name, type (`TEXT` or `ICU_MESSAGE`),
  required flag, description, and nonnegative sort order.
- `ICU_MESSAGE` mappings use Mojito's existing message-format integrity checker when source content
  is mapped, when a field is converted to ICU, during completeness preflight, and during publish,
  so malformed ICU or placeholder-mismatched approved targets do not ship in runtime artifacts.
- Rich markup stays out of the MVP field model until Mojito has an explicit supported markup format,
  sanitizer, and source/target integrity rule; exposing a nominal `RICH_TEXT` type without that
  contract would create false confidence at the runtime boundary.
- In the MVP, content type fields are localizable publishing fields. Non-localizable values live in
  entry or variant metadata JSON rather than a separate field-value system.

Non-localizable metadata

- Entry and variant records carry metadata that ships in snapshots; project and type records carry
  scoped configuration such as delivery hint and metadata schema.
- Entry and variant metadata must be JSON objects. A content type can enforce an entry metadata
  schema subset for top-level `properties`, `required`, `additionalProperties`, and simple property
  types; required property names must be declared once and are canonicalized in stable order, and
  unsupported schema keywords are rejected instead of silently ignored. Rich nested or per-variant
  schema validation stays out of scope.
- Completeness preflight and publish revalidate stored entry metadata against the current content
  type schema plus publishable variant metadata object shape, so direct DB drift cannot smuggle
  invalid targeting metadata into an immutable artifact.
- CMS metadata and metadata-schema JSON reject duplicate object keys, then canonicalize before
  storage, schema comparison, and artifact emission; metadata-schema `required` names are treated
  as a duplicate-free set with stable order, so ambiguous JSON, whitespace, object-key ordering, or
  equivalent required-name ordering does not churn schema versions or package digests.

Field-to-text-unit mapping

- A mapping connects one variant field to one Mojito TM text unit.
- Mappings do not own independent runtime order; the content type field schema owns field order in
  authoring and published artifacts.
- Variants and mappings carry constrained owning content type/project scope keys, and mappings
  carry the dedicated CMS asset id, so the database rejects entries whose type belongs to another
  project, variants whose type/project drifts from their entry, mappings whose variant or field
  belongs to another content type/project, and mappings that bind a CMS field to a TM text unit
  from another asset. The JPA mapping carries the same read-only composite hierarchy FKs as Flyway
  so generated-schema tests exercise those direct-SQL guards too.
- Authors can generate the stable CMS string ID from source content plus translator context, map an
  active project-asset Mojito string ID, or use `tmTextUnitId` as an exact admin fallback when one
  active string ID would be ambiguous, such as a future plural-shaped text unit family. Exact TM
  selection cannot bypass the usable Mojito string ID required by runtime artifacts.
- One Mojito text unit can back only one CMS field mapping; repeat copy should reuse translation
  memory leverage, not alias field identity across blocks or variants.
- Generated string IDs use a stable CMS convention:
  `cms.{projectKey}.{entryKey}.{variantKey}.{fieldKey}`.
- Stable CMS keys use lowercase letters, numbers, underscores, and hyphens; `.` is reserved as the
  generated string ID segment separator so distinct CMS records cannot collide on one Mojito ID.
  The Flyway schema keeps matching key-format checks as the final guard against direct DB drift;
  portable lifecycle, order, delivery-hint, optimistic entity-version, and publish-watermark
  checks are mirrored in the JPA mapping so generated-schema tests catch the same non-regex drift
  before MySQL/Flyway runs.
- Source content and comments stay authoritative on the Mojito text unit. Saving a generated CMS
  mapping with changed source content or translator context re-registers the same stable generated
  string ID so Mojito can leverage prior translation history by name; explicit existing-string or
  exact-TM mappings reject mismatched CMS input instead of silently rewriting external Mojito copy.
  Mappings store only the Mojito text-unit identity instead of copying source text/context into a
  second CMS cache that could drift. CMS JSON columns use MySQL `longtext`, and the admin client
  preserves source-content whitespace instead of trimming runtime copy before Mojito receives it.
- When generated source copy changes, Mojito may carry the prior approved target onto the new active
  text unit only as `TRANSLATION_NEEDED`; CMS completeness and publish require that leveraged target
  to be reviewed and approved again before delivery.
- Generated mappings require a usable Mojito string ID by construction plus nonblank source copy and
  translator context in Mojito; existing mapped text units must already carry all three.
  Completeness preflight and publish recheck publishable mappings so unnamed, empty, or contextless
  product copy cannot ship through the CMS boundary.
- Existing mapped text units must be active and translatable on the project virtual asset; generated
  CMS text units are registered as translatable.
- Admin can unmap a field from the CMS block without deleting the Mojito text unit; remapping or TM
  reuse remains explicit, and required fields still fail completeness until mapped again.
- Publish rechecks publishable mappings against the current successful virtual-asset extraction, so
  a mapping cannot drift inactive or `doNotTranslate` after authoring and still ship.
- Mutable authoring rows expose an `entityVersion`. PATCH, mapping upsert, and field unmap
  requests carry the loaded `expectedVersion`; stale writes return `409` instead of overwriting
  newer admin edits.
- PATCH request DTOs distinguish omitted fields from explicit `null`, so partial API callers do not
  erase descriptions or metadata while explicit nullable clears remain possible.
- CMS REST request JSON requires one document, rejects duplicate object keys, unknown command
  fields, and literal JSON `null` for required command bodies before DTO binding, and caps command
  bodies at 1 MiB, so ambiguous, misspelled, null, or oversized payloads cannot last-win fields
  such as `expectedVersion`, silently no-op, or exhaust the strict request preflight before
  optimistic write checks run.

Variants

- A variant belongs to an entry and groups all field mappings for one copy candidate.
- Variants have a `variantKey`, display name, status (`CONTROL`, `CANDIDATE`, `ARCHIVED`),
  `candidateGroupKey`, metadata JSON, and nonnegative sort order. Active `CANDIDATE` variants require a
  candidate group key before authoring or publish so runtime delivery can identify the experiment
  candidate set; control and archived history can leave it unset.
- Variant create and update operations serialize on the content entry before enforcing one active
  `CONTROL` invariant, and the database keeps a final unique/check guard for control rows, so
  concurrent admin edits or constraint races cannot leave two controls or demote the only control
  through the CMS API; the stored control marker must be nonnull and point back to the owning entry
  so raw SQL cannot bypass the unique control slot with a nullable marker. Updating an existing
  candidate or archived variant to `CONTROL` is an
  explicit atomic promotion: Mojito archives the prior control before assigning the promoted
  variant's unique control marker, preserving the old mapping/history for rollback without a
  transient two-control write. Publish rechecks ready entries against stored variant state and
  rejects anything other than exactly one active control before emitting an immutable artifact.
- The model supports copy experiments without making experimentation the CMS core.

Publish snapshots

- Publishing creates an immutable snapshot version for a project and locale set.
- The snapshot stores provider-agnostic JSON plus delivery metadata such as version, locales,
  UTF-8 byte size, signed publish timestamp, required authenticated publisher ID plus publish-time
  username snapshot, the artifact SHA-256 used for delivery verification and the exact artifact
  HTTP `ETag`, an
  app-held HMAC snapshot signature for full immutable-row integrity, and a second app-held HMAC
  artifact signature whose payload is reconstructable from the delivery descriptor plus exact
  artifact bytes. Both signatures use the configured signing key ID. The serialized locale set
  stays in source-locale plus sorted target-locale order and uses the same unbounded text storage
  class as artifact and completeness JSON, so default all-locale publish does not inherit an
  arbitrary metadata column cap.
- Publish/list responses expose the immutable artifact filename and a canonical export path keyed by
  stable `projectKey + snapshotVersion`; publish `201 Created` also returns that exact artifact path
  as the HTTP `Location`, so admin clients and future delivery workers can follow standard response
  metadata without carrying Mojito snapshot row IDs or duplicating an admin UI route convention.
  They expose the validated non-secret snapshot and artifact signatures with their signing key ID,
  while the artifact body keeps secrets, delivery wrappers, and Mojito authoring
  repository/virtual-asset routing details out of runtime JSON. The REST surface exposes only that
  version-addressable artifact route; snapshot row IDs stay admin history identifiers rather than
  delivery locators.
- Delivery jobs that know only the stable project key can ask Mojito for the latest verified
  published snapshot delivery descriptor before fetching the exact version-addressable artifact.
  The descriptor has its own versioned provider-neutral shape instead of reusing the admin
  snapshot-history row, so delivery automation does not depend on Mojito row IDs or publisher UI
  metadata; its `projectHint` comes from the signed immutable artifact rather than mutable live
  project settings, and its `publishedAt` field is the signed canonical UTC snapshot string rather
  than an ambient Java date serialization. The descriptor carries the validated artifact SHA-256,
  signing key ID, full snapshot signature, and delivery-verifiable artifact signature needed by a
  future delivery job to verify copied bytes without loading Mojito snapshot rows. That mutable
  latest pointer returns
  `Cache-Control: no-store`, exposes the latest signed snapshot identity as an HTTP `ETag`, and
  honors `If-None-Match` so polling delivery jobs can skip an unchanged descriptor even when two
  snapshots contain byte-identical artifacts. It also fails closed
  while the project is disabled or its backing repository/virtual asset is no longer deliverable,
  so a retired or orphaned project cannot be rediscovered as the current deliverable; runtimes
  still consume only copied Statsig/blob/CDN packages, not this Mojito admin API.
- The delivery-verifiable artifact signature uses the same length-prefixed HMAC field encoding as
  snapshot signing, but only signs descriptor-reconstructable handoff fields: signature version,
  signing key ID, project key, snapshot version, status, signed `publishedAt`, canonical locales,
  artifact SHA-256, UTF-8 byte size, and exact artifact JSON bytes. The full snapshot signature
  additionally signs publish-request, publisher, and completeness row metadata for Mojito audit
  integrity; delivery jobs should verify copied bytes with `artifactSignature`.
- V1 signatures are HMACs, so the material needed to verify them is also forge-capable signing
  secret material. Mojito and a trusted delivery job may hold retained HMAC secrets; runtime
  clients must not. If product runtime needs independent artifact verification instead of trusting
  Statsig/blob/CDN delivery controls, choose an asymmetric signing contract before adding that
  runtime requirement.
- Admin project detail validates and lists only the latest ten snapshot rows at the database query
  boundary, so old blob-sized artifact JSON does not materialize on every authoring read; exact
  version-addressable export remains the durable delivery and rollback locator.
- Stable latest-descriptor and exact artifact export routes require the stored canonical lowercase
  `projectKey`; delivery callers do not get authoring-style slug rewriting, so a typo such as
  `Growth Email` fails closed instead of selecting `growth-email`.
- Artifact objects and lists serialize in stable CMS order, so the stored SHA-256 verifies the
  exact bytes that a delivery job copies rather than a provider-specific reserialization.
- Mojito re-verifies stored snapshot publisher, published status, SHA-256, UTF-8 byte size, format
  version, stable project identity, canonical UTC artifact timestamp matching signed `published_at`,
  canonical locale tags in source-locale plus sorted target-locale order, the app-held HMAC snapshot
  signature, the delivery-verifiable artifact HMAC signature, the self-contained v1 runtime
  envelope, only expected v1 envelope keys outside intentionally open metadata/locale maps,
  duplicate-free JSON object keys, emitted entry metadata against emitted content type metadata
  schemas, and completeness metadata against the publish-complete locale set before listing or
  serving an artifact export, then writes those exact UTF-8 bytes with the stored byte length so a
  corrupted, manually mutated, ambiguous, or re-encoded immutable snapshot fails closed before
  delivery.
- Publish snapshots stay append-only through Mojito: no REST mutation/delete API exists and the JPA
  lifecycle guard rejects ORM update/delete attempts. Stored rows remain HMAC-verified before
  list/export, each stored snapshot gets an FK-backed retention seal, and the content project keeps
  a non-authoring last-published snapshot watermark. A raw snapshot-row SQL delete hits the seal FK;
  publish, project detail, latest-descriptor lookup, and exact artifact export also require stored
  snapshot max version, snapshot row count, and seal count to match that watermark, so deleting a
  seal plus snapshot cannot silently remove rollback history while Mojito keeps publishing. The
  schema rejects a negative watermark; operators with broad direct SQL access who deliberately
  rewrite multiple CMS tables remain outside the application trust boundary and should be
  constrained operationally.
- Publisher display metadata comes from the stored publish-time username snapshot; later Mojito user
  renames do not rewrite or invalidate immutable snapshot history.
- Snapshot history exposes the signed `publishedAt` value rather than a misleading mutable
  persistence `createdDate`, so delivery descriptors and admin history keep stable publish
  chronology under DB drift checks.
- Artifact `generatedAt` and signed `published_at` use the same canonical UTC publish instant, so
  delivery bytes and descriptor chronology do not disagree inside one immutable package.
- Deployments configure `l10n.content-cms.snapshot-signing-key-id` plus
  `l10n.content-cms.snapshot-signing-keys.<key-id>` with at least 32 bytes of secret material.
  Publish fails closed without an active key; list/export fails closed when a stored snapshot key
  is unavailable. Key rotation changes the active key only after retaining prior HMAC secrets
  for every rollback snapshot that must remain exportable.
- Snapshot signing operations are provider-neutral and part of the MVP deployment contract:
  provision one high-entropy secret per environment in the deployment secret manager, expose only
  the non-secret key ID in Mojito config/UI, deploy every app instance with the same retained HMAC
  secret map before changing the active key ID, then keep old HMAC secrets until snapshots signed by
  them are outside the rollback/export retention window. A rollback that loses a key re-adds the
  exact old secret; operators do not re-sign stored snapshot rows or copy secrets into source
  control, logs, tickets, or artifact metadata.
- Rotation sequence: add the new retained key while the old key stays active; verify an existing
  retained snapshot still lists/exports; switch `snapshot-signing-key-id` to the new key after all
  instances know both keys; publish future snapshots with the new active key; remove an old key only
  after no retained rollback snapshot needs it. Mixed rolling-deploy instances may temporarily sign
  with old or new active IDs only when every instance already carries both verification secrets.
- Artifact content types and entries emit by stable key; variants and fields use explicit sort order
  then stable key, so display-name edits do not create noisy runtime package reordering.
- Publish serializes on the content project before allocating checked
  `lastPublishedSnapshotVersion + 1`, advances that non-authoring watermark in the same transaction,
  and keeps the project/version uniqueness constraint as the final database guard. CMS authoring
  mutations take the same project lock before writing so a snapshot does not race a CMS edit.
  Project-locking CMS transactions use `READ_COMMITTED`, so a MySQL writer that started while
  waiting on that lock re-reads the committed post-lock snapshot, mapping, and authoring rows
  instead of combining a fresh locked project watermark with a stale `REPEATABLE READ` view.
- Publish loads current translation variants for requested locales and configured non-source parent
  locales once, then uses that same in-transaction view for locale completeness and artifact
  emission. A child locale configured as not fully translated can materialize an included approved
  parent-locale target when it has no included direct override; a fully translated locale must carry
  its own approved included target. Target locales never depend on Mojito at runtime for locale
  inheritance or fall back to source copy through this CMS publish gate.
- Project package validation returns a deterministic `publishPackageSha256` over the exact
  provider-neutral package payload that publish would emit for the selected locale scope, excluding
  snapshot-only version and publish timestamp fields. Publish requires that validated digest, so an
  approved translation, review state, inclusion flag, or inherited locale resolution change between
  validation and publish returns `409` instead of silently changing the immutable package.
- Project package validation also computes provider-neutral UTF-8 package byte size and rejects it
  above Mojito's deployment-configurable `l10n.content-cms.max-publish-artifact-bytes` cap, which
  defaults to 1 MiB. Final publish rechecks the exact timestamped/versioned artifact bytes before
  signing and storing the snapshot, so a CMS author cannot create an unbounded `LONGTEXT` rollback
  row while provider-specific size policy is still undecided.
- New snapshot publish pessimistically locks the mapped Mojito text units plus existing current
  translation markers before final package digest and artifact materialization. Current translation,
  review, inclusion, delete-current, and first-current-translation writes for those mapped units
  wait until the immutable snapshot commits instead of racing the final approved package read.
- The admin publish form preserves explicit comma-separated locale tokens after trimming; only a
  wholly blank field means "use configured target locales." Empty tokens such as `fr-FR,,ja-JP`
  and duplicate tokens such as `fr-FR,fr-FR` reach the same server-side validation as API callers
  instead of silently narrowing the package.
- The admin publish action requires a current complete package validation, then confirms the
  immutable snapshot scope before posting, naming the stable project key plus configured-default or
  explicit locale scope so an accidental click does not add noisy publish history.
- Publish requires a per-intent `Idempotency-Key` plus the server-returned CMS authoring-state
  SHA-256 from the loaded project detail and `publishPackageSha256` from the current package
  validation. Mojito compares the expected authoring SHA before creating a new snapshot, so a stale
  admin tab cannot publish another admin's unseen but structurally valid copy change. The authoring
  digest covers versioned CMS rows plus each mapped Mojito text-unit ID, string ID, source text, and
  translator context that project detail renders and the runtime artifact ships. The package digest
  covers resolved approved runtime values and completeness metadata for the selected locale scope.
  Mojito stores the validated lowercase token, requested locale scope, CMS authoring-state SHA-256,
  and package SHA-256 on the immutable signed snapshot, returns the original snapshot for a matching
  retry, and rejects keys that would need CMS-slug rewriting instead of silently colliding caller
  tokens. Reuse of the same key for another requested locale scope, expected authoring revision, or
  validated package also fails. Matching retries compare only that stored immutable request payload,
  not later mutable authoring or repository-locale state, so a timeout retry can recover the already
  committed snapshot instead of losing idempotency after another admin edits current content. The
  admin page keeps the same key in tab session storage for the same unresolved publish intent, server
  authoring SHA, and validated package SHA, so a timeout retry can revalidate after refresh and reuse
  the original key without creating duplicate snapshot history or duplicate delivery work while later
  authoring or package state gets a fresh key. A matching retry validates stored snapshot history
  plus the immutable stored request payload, then returns that snapshot without rebuilding current
  mutable authoring state. Client retry
  identity preserves trimmed explicit token multiplicity until server
  validation, so malformed `fr-FR,fr-FR` does not share a retry key with corrected `fr-FR`, and
  malformed stored tab keys are discarded before reuse so corrupt session storage cannot pin the
  operator to backend `400`s. A server `409` clears that stored rejected intent before refreshing
  CMS data, so the operator does not loop forever on a key that no longer matches current content or
  locale scope.
- Project detail, entry completeness, and project completeness acquire the same content-project row
  lock used by CMS authoring writes and publish, so rendered detail and validation results come from
  one CMS authoring revision instead of crossing a concurrent save.
- Snapshots are append-only in the MVP: no REST mutation/delete API exists, JPA lifecycle guards
  reject Mojito-side update/delete attempts, signed row integrity rejects stored-row mutation on
  read/export, the retention seal rejects direct snapshot-row delete, and the project watermark
  rejects missing or unsealed stored history before another publish or delivery handoff. Later
  delivery jobs can copy the artifact to Statsig, blob/CDN, or another provider and store
  provider-specific delivery metadata separately.

Authoring Workflow

1. Create content type
   - Admin defines the schema for a copy block and optional metadata schema.
   - Mojito starts the schema at version `1`; later schema mutations version the published
     contract automatically.
2. Create content entry
   - Admin creates an entry for a product-copy block and sets non-localizable metadata.
   - Entry metadata must satisfy the content type metadata schema subset when one is configured.
3. Define localizable fields
   - Admin adds fields that must translate and publish together.
4. Generate or map Mojito string IDs
   - For each variant field, admin chooses generated CMS string, existing Mojito string ID, or exact
     TM text unit binding. Generated CMS strings can update source copy while preserving stable
     string identity; explicit existing bindings validate rather than rewrite external Mojito copy.
   - Admin can unmap a mistaken optional field binding without deleting its Mojito text unit.
   - Mojito remains the source of string identity and translation memory.
5. Translate, review, approve
   - Normal Mojito translation and review workflows produce current target variants.
   - CMS completeness and publish reapply ICU integrity for `ICU_MESSAGE` fields because repository
     checker configuration can vary independently of the CMS field schema.
6. Validate locale completeness
   - Package completeness checks every `READY` entry that the next project snapshot would include;
     the per-entry API remains a narrower diagnostic, but the admin publish preflight uses the
     project package boundary so one validated block cannot hide another incomplete block.
   - An empty locale selection resolves to the source locale plus configured repository target
     locales in stable tag order; explicitly selected locales must be configured on the project
     repository, reject blank or duplicate tokens instead of silently narrowing the package, and
     also publish in stable tag order after the source locale.
   - Required fields must be mapped before publish; every mapped field in the copy block must have
     an approved included current variant for each selected target locale. For a configured child
     locale marked not fully translated, an included approved non-source parent-locale variant can
     satisfy the child value when there is no included direct override; fully translated locales
     require their own approved included target.
   - Completeness preflight and publish both ignore archived variants, reject non-archived variants
     without mapped copy, and recheck publishable mappings against the current virtual-asset
     extraction. They also reject entries without exactly one active control variant, so the admin
     check matches the artifact boundary.
   - Project package validation also returns the deterministic package SHA that the publish request
     must echo. Admins revalidate after authoring, translation, review, inclusion, or locale-scope
     changes before creating a new immutable snapshot.
7. Publish immutable JSON package
   - Publish includes only `READY` entries and non-archived variants.
   - Publish fails if required mappings are missing or requested locales are incomplete.
   - On success, Mojito stores a snapshot artifact that runtime delivery systems can ingest.

Runtime Delivery

Statsig dynamic config

- Suitable for small packages, flags, and copy experiments where payload size is low and rollout
  logic already lives in Statsig.
- Mojito should publish a JSON artifact that a delivery job transforms into a dynamic config value.
- Statsig's current Console API contract is explicit enough to design against but not enough to
  skip the provider decision: the official Console API docs require a Console API key in
  `STATSIG-API-KEY`, document `STATSIG-API-VERSION: 20240601`, and list mutation limits of roughly
  100 requests per 10 seconds and 900 per 15 minutes per project
  ([overview](https://docs.statsig.com/console-api/introduction)). The current OpenAPI spec exposes
  `POST /console/v1/dynamic_configs/{id}` as "Fully Update Dynamic Config" with a `dryRun` query
  parameter, requires `isEnabled`, `description`, and `rules`, models `defaultValue` and rule
  `returnValue` as JSON objects, and constrains create-time dynamic config IDs to 3-100 characters
  ([spec](https://api.statsig.com/openapi/20240601.json)). The delivery worker should dry-run the
  exact provider wrapper before mutation, send the version header now rather than waiting for it to
  become required, and derive one stable dynamic config ID from the CMS project key before the first
  delivery.
- Mojito's provider-neutral 1 MiB default cap stops accidental oversized snapshots, and snapshot
  byte size gives the delivery job/admin a provider-neutral input for deciding whether a package
  remains small enough for this target; provider-specific limit enforcement belongs in the future
  delivery job. The reviewed official Statsig docs/spec did not surface a dynamic-config payload
  size limit, so the first Statsig worker must choose and document a conservative provider-specific
  cap instead of treating Mojito's 1 MiB default as a Statsig guarantee.

Blob/CDN

- Suitable for larger packages, many entries, or content that should be fetched independently of
  experiment evaluation.
- Mojito stores a provider-agnostic artifact; a future delivery job writes versioned JSON to blob
  storage and exposes it through CDN paths.

Future experience framework

- A future internal experience layer can consume the same snapshot artifact and decide layout,
  targeting, rollout, and caching.
- The CMS model should not assume that framework exists.

Provider delivery contract before implementation

- Mojito should not guess provider credentials, service-account auth, or runtime rollback policy.
  Before adding a Statsig/blob/CDN delivery job, choose one target and define: the caller identity
  bound to Mojito's read-only `ROLE_CMS_DELIVERY` descriptor/artifact routes; the provider
  credential owner and secret-manager path; the retained artifact-signing HMAC secrets available
  only to that trusted job; whether copied runtime artifacts rely on provider delivery trust or
  need a future asymmetric runtime-verification contract; the
  provider destination naming convention; stricter provider payload/size limits; idempotency key derived
  from stable project key plus snapshot version; and the rollback record that maps provider state
  back to exact Mojito `projectKey + snapshotVersion + artifactSha256`.
- If Statsig is chosen first, the contract additionally needs the exact dynamic config ID,
  ownership/review policy, target apps/environments, whether delivery writes only `defaultValue` or
  managed rules too, the provider wrapper carrying Mojito snapshot identity, `dryRun` behavior,
  mutation retry/backoff under Statsig's documented project-level mutation limits, and the
  conservative payload cap selected because the reviewed official docs/spec did not publish one.
- The delivery job contract is fetch verified descriptor, fetch exact versioned bytes, verify
  `artifactSha256`, UTF-8 byte size, and `artifactSignature`, write immutable/versioned provider
  state, then persist provider-specific delivery metadata outside the provider-neutral CMS snapshot.
  A provider write must never reserialize or mutate Mojito artifact bytes before verification; after
  verification, a provider wrapper may parse or embed those verified bytes only according to the
  chosen delivery contract and must carry the verified Mojito snapshot identity alongside any
  provider-specific shape.
- Rollback means selecting a retained exact Mojito snapshot version and asking the provider job to
  repoint or republish that already verified artifact. Mojito does not edit, re-sign, or overwrite
  stored snapshot rows to perform rollback.

No direct runtime dependency on Mojito

- Runtime clients should not call Mojito APIs.
- Mojito can be down or behind admin auth without affecting product runtime.
- Published artifacts should be immutable, cacheable, and version-addressable.
- Mojito's admin or `ROLE_CMS_DELIVERY` export returns a deterministic project/version filename
  plus private immutable cache headers, explicit UTF-8 JSON bytes, stored byte length, an artifact
  `ETag`, and validated snapshot signing key plus snapshot/artifact signature headers; `GET` returns
  bytes, `HEAD` exposes the same delivery metadata without bytes, and `If-None-Match` lets polling
  delivery jobs skip artifacts they already copied into runtime delivery.
- Delivery jobs may poll Mojito's stable latest-descriptor route, but that mutable pointer is
  `no-store`; its versioned provider-neutral body omits admin history row IDs, and it honors
  `If-None-Match` against the latest signed snapshot `ETag` so unchanged `GET` or `HEAD` polls can
  return `304` without hiding a newer byte-identical snapshot, while only exact version-addressable
  artifact exports are cacheable.
- Stable latest-descriptor discovery acquires the content-project row lock shared with project
  enable/disable and publish, so disabling a project or publishing a newer snapshot cannot race a
  partially evaluated mutable latest pointer.
- Runtime artifacts carry stable CMS keys and Mojito string IDs, not Mojito database IDs or
  authoring repository/virtual-asset routing details.

Copy Experiments

Copy experiments fit as variant groups:

- `CONTROL` is the known-good copy.
- Active `CANDIDATE` variants require and share a `candidateGroupKey` such as `welcome-subject`.
- The published artifact can expose candidate groups and variant metadata.
- Statsig or a future experience framework chooses which candidate to serve.

Experimentation is deliberately not the core model. The core model is a translatable copy block with
field mappings and publish snapshots. This keeps banners, emails, notifications, and help snippets
usable even when no experiment exists.

Admin UI

The MVP UI is a dry admin settings subpage at `/settings/system/content-cms`:

- Reuses the existing settings page width, subpage header, form controls, tables, color tokens, and
  admin-only access pattern.
- Uses a left project rail and right authoring workspace for content type, field, entry, variant,
  mapping, completeness, and publish operations.
- Runs package completeness against the same locale-tag input that publish uses, so the operator
  does not validate one scope and accidentally snapshot another.
- Clears rendered completeness results when the locale-tag input changes, so a validation result
  cannot stay visible after the scope no longer matches the next publish request.
- Clears rendered completeness results when validation starts or fails, so a previous successful
  preflight cannot stay visible beside an in-flight, invalid, or server-rejected package check.
- Treats a completeness response with a newer server authoring SHA as a stale-tab conflict, clears
  the derived result, and refreshes project detail before the operator can validate or publish
  again.
- Lets admins edit names, metadata, field properties, lifecycle status, and delivery hint while
  keeping project/type/entry/variant/field keys, repository, and virtual asset fixed after create.
- Confirms candidate or archived variant promotion before the service archives the current control,
  so a lifecycle dropdown cannot silently change the served copy baseline.
- Confirms project disable, moving a `READY` entry out of `READY`, archiving an active variant, and
  unmapping a field before the UI sends changes that hide latest delivery discovery or remove copy
  from the next package; the service still enforces the structural lifecycle invariants.
- Refreshes project detail after a stale-write `409`, clears stale completeness results and
  unresolved publish intents for that loaded revision, so a long-lived admin tab reloads current
  entity versions before another save or publish.
- Sends the server-returned authoring SHA with publish, so project detail freshness is an explicit
  optimistic publish precondition rather than an implicit React Query timing assumption.
- Serves mutable project/search/completeness reads with `no-store`, so browser HTTP cache does not
  outlive the UI's short React Query freshness window; only exact published artifact exports are
  HTTP-cacheable.
- Resets project-scoped authoring drafts when the selected project changes, so stale child IDs from
  the previous project cannot direct a later field, variant, or mapping write at the wrong project.
- Shows tables for content types, entries, variants, mappings, and snapshots.
- Mapping rows link into Mojito text-unit detail so authors can use the existing
  translate/review/approve surface instead of a CMS-specific translation editor.
- Mapping authoring makes generated CMS strings, existing Mojito string IDs, and exact TM text-unit
  bindings explicit; source/context controls edit only generated bindings, so editing generated
  source copy does not accidentally turn into an external text-unit remap.
- Avoids marketing copy, decorative cards, media, page-builder affordances, and visual preview.

MVP Implementation

Backend

- Flyway migration `V97__Content_CMS.sql` adds project, type, field, entry, variant, mapping, and
  publish snapshot tables with scoped hierarchy, lifecycle/type, delivery, optimistic authoring
  entity-version, and publish-version constraints plus FK-backed snapshot retention seals and a
  checked last-published snapshot watermark as final database guards.
- The MySQL integration coverage inventories the MVP check-constraint surface and directly rejects
  persisted non-localizable CMS fields, so direct SQL drift cannot create a second field-value
  model outside metadata.
- Mutable project, type, field, entry, variant, and mapping rows use Mojito Envers revisions plus
  current created/last-modified actor metadata, so copy and schema authoring changes have durable
  history before publish; immutable signed snapshots remain the publish ledger.
- JPA entities live under `entity/cms`.
- Repositories and `CmsContentService` live under `service/cms`.
- `CmsContentWS` exposes admin-only authoring REST endpoints under `/api/content-cms`; only the
  stable latest descriptor and exact versioned artifact GET routes additionally admit
  `ROLE_CMS_DELIVERY`. The delivery account is not an interactive Mojito role: generic
  authenticated UI/API routes require `ROLE_USER`, `ROLE_TRANSLATOR`, `ROLE_PM`, or `ROLE_ADMIN`
  so the provider fetcher cannot browse text units, screenshots, MCP, or user-session APIs. The
  only non-artifact API admission is Mojito's session-scoped CSRF token endpoint required by the
  existing stateful form-login REST client before it makes the signed descriptor/artifact GET;
  public frontend config remains public for login bootstrap but renders a delivery session as
  anonymous instead of exposing a service-account profile.
- The service creates a dedicated virtual asset for each project, maps or generates active Mojito
  virtual text units through a CMS-only source registration path, rejects later generic
  virtual-asset source, asset-content/extraction, low-level TM source-create, or `/api/assets`
  logical delete mutation for CMS-owned assets, can unmap CMS field bindings without deleting
  Mojito text units, validates entry metadata and locale completeness, gates snapshots on ready
  content, preserves
  control/publish concurrency invariants, rejects stale admin writes with entity versions, keeps
  PATCH partial-update semantics, records mutable CMS authoring actors, reapplies ICU integrity for
  `ICU_MESSAGE` fields, returns `409` for concurrent constraint races, and produces immutable JSON
  snapshots.

Frontend

- `frontend/src/api/content-cms.ts` adds the typed REST client.
- `AdminContentCmsPage` adds the settings subpage.
- `AdminSettingsPage` links to the subpage from the admin settings directory.
- Routes are registered for `/settings/system/content-cms` and `/settings/admin/content-cms`.

Provider-Agnostic Artifact Shape

The artifact is intentionally plain JSON:

- `formatVersion`
- `snapshotVersion`
- `generatedAt` as a canonical UTC instant
- `delivery` metadata
- `project`
- canonical BCP 47 `locales` in source-locale plus sorted target-locale order
- `contentTypes`
- `entries`
- `variants`
- field values by locale
- completeness summaries

Provider-specific delivery code can wrap or transform this later without changing the authoring
schema.

Non-Goals

- General page builder.
- Media library.
- Complex visual preview.
- Production runtime service.
- Provider-specific Statsig/blob write jobs in the MVP.
- Full editorial role model beyond admin-only MVP authoring operations.
- Rich nested or per-variant schema validation for arbitrary metadata JSON.

Validation Plan

- Run Java formatting and compile focused on `webapp`.
- Run frontend format, lint fix, typecheck, and tests with the Maven-managed Node/npm.
- Run narrow service coverage for virtual text-unit mapping, lifecycle-aware publish, completeness,
  stale-write rejection, and artifact shape.
- Smoke the new settings route in Browser when a dev server/static build is available.
- Keep provider delivery jobs, richer nested/per-variant metadata schema enforcement, and runtime
  integration tracked separately instead of expanding this MVP into a full CMS platform.

Validation Evidence

- `mvn spotless:check` passed.
- `mvn -pl webapp -am -Pfrontend test-compile -DskipTests` passed.
- `mvn -pl webapp -am -Pno-local-config clean -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentRequestBodyAdviceTest,CmsContentWSTest,CmsContentSecurityWSTest,CmsSnapshotSigningServiceTest,CmsContentServiceTest,CmsContentServiceIntegrationTest test` passed with 277 tests, 0 failures/errors, and 15 expected MySQL-only skips.
- `source webapp/use_local_npm.sh` followed by frontend `format`, `lint:fix`, `tsc`, and `test` passed; Vitest reported 14 files and 70 tests.
- A disposable HSQL-backed `webapp` smoke on `http://localhost:18080/settings/system/content-cms` rendered the admin Content CMS route after `admin/ChangeMe` login with no browser warning/error logs.
- The focused MySQL integration re-run remains approval-gated in this restored worktree: the sandbox blocks the localhost MySQL socket, and the unsandboxed test path can Flyway-clean the configured local Mojito database.
