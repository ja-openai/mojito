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

- Virtual assets and TM text units: CMS projects use a Mojito repository and dedicated virtual asset
  path,
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
- New author-first copy collections create that visible Mojito repository in the same CMS project
  transaction. The author route does not expose repository reuse; that remains a technical/backend
  concern instead of reopening setup during normal writing.
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
- Carries a stable `projectKey`, display name, optional description, enabled flag, and delivery
  hint; the surfaced/published source locale is derived from the backing Mojito repository so CMS
  does not duplicate locale authority.
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
- Entries that use one content type share that block shape and its copy fields. The MVP keeps that
  shared scope inside admin tools before field creation and can deliberately fork that hidden content
  type for one selected saved block while preserving its Mojito text units/string IDs, so an admin can
  make later field creation private without pretending a shared field mutation was block-local.
- Schema version is server-managed: new types start at `1`, emitted content-type display-name,
  metadata-schema, or field-schema mutations increment it, and admins cannot set arbitrary
  artifact contract versions.

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
- Field descriptions stay authoring-only like content-type descriptions; changing one does not
  increment the emitted artifact schema version.
- `ICU_MESSAGE` mappings use Mojito's existing message-format integrity checker when source content
  is mapped, when a field is converted to ICU against publishable configured-locale targets, during
  completeness preflight, and during publish, so malformed ICU or placeholder-mismatched approved
  targets do not ship in runtime artifacts.
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
- CMS metadata and metadata-schema JSON require one duplicate-free JSON document, then canonicalize
  before storage, schema comparison, and artifact emission; metadata-schema `required` names are
  treated as a duplicate-free set with stable order, so ambiguous JSON, trailing documents,
  whitespace, object-key ordering, or equivalent required-name ordering does not churn schema
  versions or package digests.

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
- Published fields retain their field key as the runtime position identity, but each publishable
  runtime field mapping must still keep a unique Mojito string ID across the package. Exact TM
  selection can intentionally choose one active text unit when a Mojito string ID resolves to
  multiple active units, but it cannot publish two runtime fields under the same string identity.
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
  fields, literal JSON `null` for required command bodies, and explicit `null` for non-nullable
  PATCH fields plus body-carried repository/content-type/mapping-field selectors and unmap expected
  versions before DTO binding. The CMS-only request mapper also rejects scalar coercion such as
  quoted numeric versions or fractional integer sort orders instead of silently normalizing malformed
  API input. Missing body selectors also fail as bad input before selector resource lookup. Command
  bodies cap at 1 MiB, so ambiguous, misspelled, null, coerced, or oversized payloads cannot
  last-win fields such as `expectedVersion`, silently no-op, masquerade as missing CMS resources, or
  exhaust the strict request preflight before optimistic write checks run.

Variants

- A variant belongs to an entry and groups all field mappings for one copy candidate.
- Variants have a `variantKey`, display name, status (`CONTROL`, `CANDIDATE`, `ARCHIVED`),
  `candidateGroupKey`, metadata JSON, and nonnegative sort order. Active `CANDIDATE` variants require a
  candidate group key before authoring or publish so runtime delivery can identify the experiment
  candidate set; control and archived history can leave it unset. Because each variant is a whole
  copy block rather than a composable fragment, one entry can have only one active candidate group
  key at a time; variant authoring rejects conflicting active groups and completeness/publish recheck
  the same invariant against stored rows before emitting runtime JSON.
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
  They expose the validated non-secret snapshot and artifact signatures with their signing key ID;
  the REST descriptor regression rejects configured signing-key material from that response. The
  artifact body keeps secrets, delivery wrappers, and Mojito authoring
  repository/virtual-asset routing details out of runtime JSON. The artifact export surface exposes
  only that version-addressable project-keyed route; snapshot row IDs stay admin history identifiers
  rather than delivery locators.
- Delivery jobs that know only the stable project key can ask Mojito for the latest verified
  published snapshot delivery descriptor before fetching the exact version-addressable artifact.
  The descriptor has its own versioned provider-neutral shape instead of reusing the admin
  snapshot-history row, so delivery automation does not depend on Mojito row IDs or publisher UI
  metadata; its `projectHint` comes from the signed immutable artifact rather than mutable live
  project settings, and its `publishedAt` field is the signed canonical UTC snapshot string rather
  than an ambient Java date serialization. The descriptor carries the validated artifact SHA-256,
  UTF-8 byte size, signature algorithm, snapshot/artifact signature versions, signing key ID, full
  snapshot signature, and delivery-verifiable artifact signature needed by a future delivery job to
  verify copied bytes without loading Mojito snapshot rows. That mutable latest pointer returns
  `Cache-Control: no-store`, exposes the latest signed snapshot identity as an HTTP `ETag`, and
  honors valid `If-None-Match` validators so polling delivery jobs can skip an unchanged descriptor
  even when two snapshots contain byte-identical artifacts; malformed weak wildcards such as `W/*`
  and invalid wildcard lists such as `*, "etag"`, malformed entity-tag lists, and weak-tag
  whitespace do not alias valid validators. It also fails closed while the project is disabled or
  its backing repository/virtual asset is no longer deliverable, so a retired or orphaned project
  cannot be rediscovered as the current deliverable; runtimes still consume only copied
  Statsig/blob/CDN packages, not this Mojito admin API.
- The delivery-verifiable artifact signature uses the descriptor's explicit `signatureAlgorithm` and
  `artifactSignatureVersion` with the same length-prefixed HMAC field encoding as snapshot signing,
  but only signs descriptor-reconstructable handoff fields: signature version, signing key ID,
  project key, snapshot version, status, signed `publishedAt`, canonical locales, artifact SHA-256,
  UTF-8 byte size, and exact artifact JSON bytes. The full snapshot signature additionally signs
  publish-request, publisher, and completeness row metadata for Mojito audit integrity; delivery
  jobs should verify copied bytes with `artifactSignature`.
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
  `Growth Email` fails closed instead of selecting `growth-email`. Exact artifact export also
  accepts only canonical positive decimal snapshot-version digits, so
  `/publish-snapshots/02/artifact` does not alias the immutable `/publish-snapshots/2/artifact`
  delivery locator. The locked delivery lookup
  resolves that validated canonical key exactly, then snapshot reads use the project FK plus
  snapshot version/order instead of re-running a project-key snapshot lookup, so the hot path stays
  on the `(content_project_id, snapshot_version)` database key.
- Artifact objects and lists serialize in stable CMS order, so the stored SHA-256 verifies the
  exact bytes that a delivery job copies rather than a provider-specific reserialization.
- Mojito re-verifies stored snapshot publisher, published status, SHA-256, UTF-8 byte size, format
  version, stable project identity, bounded canonical CMS artifact keys, canonical UTC artifact
  timestamp matching signed `published_at`, canonical locale tags in source-locale plus sorted
  target-locale order, the app-held HMAC snapshot signature, the delivery-verifiable artifact HMAC
  signature, the self-contained v1 runtime envelope, only expected v1 envelope keys outside
  intentionally open metadata/locale maps, duplicate-free JSON object keys, emitted entry metadata
  against emitted content type metadata schemas, and completeness metadata against the
  publish-complete locale set before returning recent admin history, returning a publishable package
  digest, appending another snapshot, or serving an artifact export, then writes those exact UTF-8
  bytes with the stored byte length so a corrupted, manually mutated, ambiguous, or re-encoded
  immutable snapshot fails closed before delivery.
- Admin project detail validates recent full snapshot artifacts but pages retained snapshot history
  through metadata-only rows, so operators can select older rollback versions without loading every
  old `artifact_json` blob on ordinary authoring reads. Older-history cursors must not skip the
  first full-artifact-validated history page for the requested page size, so a fabricated
  `beforeVersion` cannot re-read recent metadata through that metadata-only path; exact artifact
  export still validates the selected retained bytes before delivery or rollback.
- Admin project detail is a deliberate whole-project authoring response, so Mojito separately caps
  its serialized UTF-8 JSON with `l10n.content-cms.max-authoring-detail-bytes`, defaulting to 1 MiB.
  Every admin mutation returns that detail inside the write transaction, so app-mediated draft or
  archived growth that would make ordinary reads unbounded fails before commit; raw stored drift
  fails closed on the next detail read. This is independent from the publish artifact cap because
  unpublished authoring rows do not appear in runtime snapshots.
- Publish snapshots stay append-only through Mojito: no REST mutation/delete API exists and the JPA
  lifecycle guard rejects ORM update/delete attempts. Stored full snapshot artifacts remain
  HMAC-verified before recent admin detail, latest-descriptor lookup, or exact artifact export; each
  stored snapshot gets an FK-backed retention seal, and the content project keeps a non-authoring
  last-published snapshot watermark. A raw snapshot-row SQL delete hits the seal FK, and a raw
  content-project SQL delete with retained snapshots hits the snapshot project FK; publish, project
  package validation, project detail, latest-descriptor lookup, and exact artifact export also
  require stored
  snapshot max version, snapshot row count, and seal count to match that watermark, so deleting a
  seal plus snapshot cannot silently remove rollback history while Mojito keeps publishing. The
  schema rejects a negative watermark; operators with broad direct SQL access who deliberately
  rewrite multiple CMS tables remain outside the application trust boundary and should be
  constrained operationally.
- Publisher display metadata comes from the stored publish-time username snapshot; later Mojito user
  renames, including the username rewrite used by logical user deletion, do not rewrite or
  invalidate immutable snapshot history.
- Successful committed new snapshot publish logs bounded operational metadata only: project key,
  snapshot version, canonical locale tags, artifact SHA-256, UTF-8 byte size, and publisher
  username. It does not log source copy, translated values, signatures, HMAC key material, or
  publish request keys; delivery polling stays out of info logs until one provider delivery job
  contract exists.
- Snapshot history exposes the signed `publishedAt` value rather than a misleading mutable
  persistence `createdDate`, so delivery descriptors and admin history keep stable publish
  chronology under DB drift checks.
- Artifact `generatedAt` and signed `published_at` use the same canonical UTC publish instant, so
  delivery bytes and descriptor chronology do not disagree inside one immutable package.
- Deployments configure `l10n.content-cms.snapshot-signing-key-id` plus
  `l10n.content-cms.snapshot-signing-keys.<key-id>` with at least 32 bytes of secret material.
  Publish fails closed without an active key; recent admin detail and exact artifact export fail
  closed when a stored snapshot key is unavailable. Key rotation changes the active key only after
  retaining prior HMAC secrets for every rollback snapshot that must remain exportable.
- Snapshot signing operations are provider-neutral and part of the MVP deployment contract:
  provision one high-entropy secret per environment in the deployment secret manager, expose only
  the bounded canonical non-secret key ID in Mojito config/UI, deploy every app instance with the
  same retained HMAC secret map before changing the active key ID, then keep old HMAC secrets until
  snapshots signed by them are outside the rollback/export retention window. A rollback that loses
  a key re-adds the exact old secret; operators do not re-sign stored snapshot rows or copy secrets
  into source control, logs, tickets, or artifact metadata.
- The MVP does not implement snapshot pruning: publish rows and FK-backed seals are append-only, and
  the per-artifact byte cap bounds one rollback row rather than total retained history. Until a
  later retention/pruning contract exists, every stored snapshot remains in Mojito's rollback/export
  set, so deployments must retain every HMAC key needed to verify those rows or accept that affected
  historical exports fail closed. Before provider-specific delivery jobs add durable rollback records
  outside Mojito, choose the snapshot retention window, pruning authority, provider rollback metadata,
  and HMAC key retirement procedure together rather than deleting rows or dropping keys ad hoc.
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
  inheritance or fall back to source copy through this CMS publish gate. CMS also rejects malformed
  repository-locale parent cycles before loading translation rows, so a bad Mojito inheritance graph
  fails closed instead of hanging completeness or publish.
- Project package validation returns a deterministic `publishPackageSha256` over the exact
  provider-neutral package payload that publish would emit for the selected locale scope, excluding
  snapshot-only version and publish timestamp fields. Publish requires that validated digest, so an
  approved translation, review state, inclusion flag, or inherited locale resolution change between
  validation and publish returns `409` instead of silently changing the immutable package.
- Project package validation also computes provider-neutral UTF-8 package byte size and reserves the
  largest supported v1 immutable snapshot envelope before returning a publishable digest. It rejects
  either size above Mojito's deployment-configurable
  `l10n.content-cms.max-publish-artifact-bytes` cap, which defaults to 1 MiB, requires the project to
  remain enabled and deliverable, and requires the active retained snapshot-signing key before
  returning a publishable digest. Final publish rechecks the exact timestamped/versioned artifact
  bytes before signing and storing the snapshot, so a CMS author cannot create an unbounded
  `LONGTEXT` rollback row or discover a disabled/orphaned project, missing deployment signing config,
  or impossible final artifact envelope only after the final publish confirmation while
  provider-specific size policy is still undecided.
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
- Publish requires exactly one per-intent `Idempotency-Key` header plus the server-returned CMS
  authoring-state SHA-256 from the loaded project detail and `publishPackageSha256` from the current
  package validation. Mojito rejects missing or repeated singleton identity headers before service
  execution, then compares the expected authoring SHA before creating a new snapshot, so a stale
  admin tab cannot publish another admin's unseen but structurally valid copy change. The authoring
  digest covers versioned CMS rows plus each mapped Mojito text-unit ID, string ID, source text, and
  translator context that project detail renders; string ID and source text also ship in the runtime
  artifact. The package digest covers resolved approved runtime values and completeness metadata for
  the selected locale scope.
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
  read/export, the retention seal rejects direct snapshot-row delete, the snapshot project FK
  rejects hard-deleting a project with retained snapshots, and the project watermark rejects
  missing or unsealed stored history before another publish or delivery handoff. Later delivery
  jobs can copy the artifact to Statsig, blob/CDN, or another provider and store
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
- Statsig source check on June 7, 2026: the official
  [Console API overview](https://docs.statsig.com/console-api/introduction) documents the
  `https://statsigapi.net` base URL, Console API key header, project-level mutation limits, and
  `STATSIG-API-VERSION: 20240601`; the official
  [20240601 OpenAPI spec](https://api.statsig.com/openapi/20240601.json) remains the endpoint source
  for fully updating dynamic configs, including the `dryRun` query parameter.
- Existing Mojito blob storage is not the CMS runtime delivery path: `BlobStorage` is an internal
  named-byte store with overwrite-by-name semantics, `StructuredBlobStorage` namespaces internal
  async/admin payloads, the default `DatabaseBlobStorage` is documented for tests or limited load,
  and optional `S3BlobStorage` still needs manually configured lifecycle/storage policy. If
  blob/CDN is chosen first, add a provider-specific versioned immutable object key plus latest
  pointer/CDN contract instead of routing runtime clients through Mojito's internal blob APIs.
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
  plus private immutable cache headers, `X-Content-Type-Options: nosniff`, explicit UTF-8 JSON
  bytes, stored byte length, an artifact
  `ETag`, and validated signature algorithm/version, snapshot signing key ID, plus
  snapshot/artifact signature headers; `GET` returns bytes, `HEAD` exposes the same delivery
  metadata without bytes, and valid `If-None-Match` validators let polling delivery jobs skip
  artifacts they already copied into runtime delivery without treating malformed weak wildcards,
  invalid wildcard lists, malformed entity-tag lists, or weak-tag whitespace as a cache hit.
- Delivery jobs may poll Mojito's stable latest-descriptor route, but that mutable pointer is
  `no-store`; its versioned provider-neutral body omits admin history row IDs, and it honors
  `If-None-Match` against the latest signed snapshot `ETag` so unchanged `GET` or `HEAD` polls can
  return `304` without hiding a newer byte-identical snapshot, while only exact version-addressable
  artifact exports are cacheable.
- Stable latest-descriptor discovery and exact artifact export acquire the content-project row lock
  shared with project enable/disable and publish, so disabling a project or publishing a newer
  snapshot cannot race a partially evaluated mutable latest pointer or make a valid retained exact
  export falsely fail append-only history validation.
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

The MVP UI is an author-first settings subpage at `/settings/system/content-cms`:

- Reuses the existing settings page width, subpage header, form controls, tables, color tokens, and
  admin-only access pattern.
- Gates CMS and prerequisite repository queries behind the admin role before a non-admin deep link
  redirects, so the hidden authoring page does not start loading its setup data for an unusable
  session.
- Calls projects `copy collections`, entries `content items`, and localizable fields `fields` on
  the primary path, so an author starts from product copy instead of CMS schema vocabulary.
- Labels the settings-directory entry and opened subpage `Product copy`, then opens directly into the
  authoring workspace instead of adding a generic workflow note, text-unit mapping, or JSON-package
  publishing copy before the editor.
- Uses a left copy-collection rail, a selected item editor centered on source copy, translator note,
  visible placement context, and inline Mojito translation readiness, and only keeps the top
  content-item strip when the collection actually has multiple items to choose between.
- Removes the large settings-card shells from that normal author canvas: the copy-collection rail is a
  quiet navigation column, the selected editor is unframed, and only repeated copy items or local
  tools keep bounded surfaces.
- Softens the remaining author chrome too: normal eyebrow labels keep sentence case, redundant saved
  editor labels stay out of the source-copy path, passive save/progress states read as muted text,
  and pills stay reserved for unsaved, translation, or release state that changes the next action.
- Keeps source copy visually primary while marking the translator note as required for translation and
  styling that note as a softer supporting input, so the author knows the localization context is part
  of save without mistaking it for a second copy body.
- Gives source copy a quiet editable surface with normal hover/focus affordance, so the first author
  action reads as writing into a field instead of inspecting saved prose while translator note and
  translation status stay visually secondary.
- Does not fake a product preview before a renderer contract exists: source copy stays in the editor,
  item placement stays in the item header/details, and field placement stays beside each field instead
  of echoing the same text in a competing right rail.
- Shows fields as one stacked source-copy form with every field open, using quiet separators plus a
  small active-field rail instead of framed record cards, so authors can edit a whole content item
  without hidden schema tabs or losing sibling source-copy drafts before `Save all`.
- Keeps source-copy editors at a two-line floor and lets them auto-grow for longer copy, so short
  headlines and CTA labels do not turn the author canvas into a tall empty form wall while long body
  copy still stays in the real editor.
- Keeps required translator notes visually secondary with a one-line auto-growing floor and no clean
  saved-field divider before that supporting input, so the localization context remains part of save
  without reading like a second source-copy editor beside every field.
- Keeps field names and placement hints in the open author form, but removes per-field `Field n of m`
  ordinals from that primary path so normal writing does not reopen schema/setup framing before each
  piece of copy.
- Keeps closed translation rows compact by letting the status line name the locale and need while the
  visible button stays the short `Write`, `Review`, or `Open` verb; the full locale-specific action
  remains in the accessible label and the opened editor heading, and the passive closed row drops the
  redundant `Translation` eyebrow and standalone divider so saved source copy does not grow a repeated
  console label or subpanel before the author opens that locale; empty active-field localization
  wrappers also collapse instead of reserving a blank technical divider when no setup or editor is
  visible.
- Uses `Where this copy appears` and `placement details` for field-level placement in the author
  path, replacing the vague `Copy context` disclosure while leaving field-context domain names
  technical under the hood.
- Keeps clean fields without saved placement quiet by omitting generic `Add placement details`
  instruction from the field header and keeping the local `Where this copy appears` form closed until
  the author intentionally opens that optional translator context; the closed clean placement input
  also stays out of keyboard tab order before that author action.
- Does not add a separate field outline above those always-open editors. A visible source-copy field is
  the switch target, so longer content items do not begin with a duplicate status rail before the
  author can write.
- Keeps saved-field progress in the content-item strip where it helps authors scan items, but does not
  repeat the same clean `fields saved` counter in the selected editor header before `Write the copy`;
  the editor header only promotes unsaved state because it changes the author's next action.
- Hides that content-item strip when one saved content item is already open, so a normal single-item
  copy collection lands directly on the editor; a missing-source-copy signal moves into the editor
  header only while that strip is absent, while clean `fields saved` progress stays out of that first
  viewport because it does not change the author's next action; zero-item recovery plus multi-item
  collections still keep the strip where it enables creation or switching.
- Keeps chooser language truthful when a chooser exists: multi-item collections still say `Choose a
  content item`, while a single open item has no detached chooser prompt above its editor; chooser rows
  preview saved source copy or say `No source copy yet` instead of echoing item placement as fake copy.
- Gives copy-collection rail rows explicit `Open copy collection …` accessible names while leaving the
  visible collection description as supporting text, so the first navigation control announces a real
  author action instead of concatenating card metadata into one opaque button name.
- Removes that single-item collection header entirely once the active item opens directly: the
  author lands on the content item name and fields, keeps `Write new content item` as the one visible
  next author action, and keeps rename/placement inside one quiet item-header `Item details`
  disclosure instead of a detached management band above the editor.
- Keeps the copy-collection rail sticky against the actual page scroll on this CMS route, so long
  content items do not strand collection navigation while review stays below the editing surface; when
  that rail collapses before tablet widths, only the compact `Copy collections` disclosure stays
  above the editor while its rail body remains closed until the author asks for it, so phone-size
  authoring starts on source copy instead of spending the first viewport on full navigation; that
  secondary switcher still lets the author change collections without reopening a desktop rail above
  the editor, and its
  CSS-drawn visible chevron stays out of the accessible name so the control does not look like the
  separate `New copy collection` action or a raw prototype glyph.
- Surfaces unsaved source-copy and placement-detail edits inline before navigation guards fire, so
  authors can tell whether the visible content item still needs an explicit save.
- Keeps clean saved source-copy, placement-detail, and content-item-detail forms quiet by keeping
  their local save controls out of the author path until that specific draft changes, while dirty drafts
  and first-save source copy still promote the matching save action; clean mapped fields also stop
  repeating passive `Source saved` chrome before the source-copy editor, while missing or dirty source
  copy still surfaces the state that requires action.
- Adds one primary `Save copy changes` action for visible item context, placement details, source copy,
  and translator note edits, while retaining granular saves as local escape hatches; dirty inline
  translation keeps its locale-specific save controls instead of showing a batch-save action that
  cannot persist that translation, dirty content-item details keep both local and item-level save
  disabled until the required content-item name is present, and incomplete dirty generated source copy
  keeps that item-level save disabled until source copy plus translator note are both present; the
  sticky save bar announces only its current save state politely and describes the action from that
  state instead of announcing the save button as part of the draft-status change.
- Keeps current content-item placement visible as a terse editor-header summary when it exists, keeps
  missing optional item placement quiet until the author opens saved-item `Item details`, puts editable
  item name and placement inside that one panel instead of separate rename/context chrome, labels that
  item-level placement as `Where this item appears`, and keeps each clean saved field placement behind
  one `Where this copy appears` disclosure beside its own source copy instead of repeating the
  subtitle as an open input; the muted details trigger names the panel it controls for keyboard and
  assistive-tech users while keeping those item-management controls visually secondary to `Write the
  copy`; dirty, conflicted, and recovering field placement
  stays open where the author needs it, the clean first-task forms keep placement inside post-action
  `Add details`, later content item creation keeps the same placement details behind its own `Content
  item details` disclosure with the same control-to-panel relationship, and multiple dirty
  placement-detail drafts stay visible before `Save all`.
- Uses author language for localization setup in the primary path: the workspace says `Writing in
  <language>` instead of `Source locale`, and first-translation setup plus the inline translation
  picker say `translation languages` instead of `target locales`; API/domain names stay technical
  under the hood.
- Shows each field's Mojito handoff inline after source save, using field-scoped readiness for that
  field while the content-item-level translation readiness summary stays in the release step instead of
  exposing mapping IDs in the authoring form; the primary field action now says `Save source copy`
  or `Save source changes`, while the inline status explains when the saved source is ready for
  localization, unmapped copy keeps translation chrome out of the way until source copy saves, and
  dirty mapped drafts say `Save source changes before translating` instead of using sync/prototype
  wording or falsely reading as already saved; if the inline translation editor is already open, that
  save-first recovery owns the message and saved-translation link instead of leaving a duplicate passive
  row above it.
- Turns that saved-source handoff into an explicit next-step card plus opt-in active-field translation editor:
  source-synced copy says whether that field is ready, blocked readiness distinguishes unfinished
  translation from approval review, source-only repositories keep authors in the CMS with an inline
  Mojito-backed target locale picker instead of claiming translation is done or sending them into
  repository setup; that picker now opens on common product locales, keeps bulk locale actions out
  of the author path, owns the one visible source-only next step after the full saved source-copy
  stack instead of repeating the same language-setup sentence under every saved field or splitting the
  field list before the author finishes writing, sits at the item heading level, lets that visible
  heading name the region, receives keyboard focus only after the final visible source-copy control,
  hands focus into the first inline translation or release task when `Add languages` removes that
  item-level card, announces local language-save failure before leaving focus on that card's retryable
  `Add languages` action, stays hidden until every required field has saved
  source copy and while any source-copy draft in that item is unsaved, keeps that one next-step
  headline and explanation separated on narrow screens, exposes
  the full Mojito locale catalog only as an explicit search-first escape hatch instead of mounting
  hundreds of raw locale rows into the author path, flips above its trigger
  when the lower viewport would clip the first locale choices, says
  those locales are where this content item will be translated next, and no longer offers a
  source-only Mojito escape hatch before the author has any translation to work on; its loading,
  retry, and no-locale states now stay in translation language and tell the author when an admin must
  add supported locales instead of leaving a dead locale-list sentence. Configured target locales now
  load/save the selected Mojito translation directly
  beside the source copy with status, validation, reset, and locale switching in the CMS only after
  the author opens the visible `Write`, `Review`, or `Open` translation action; until then each
  saved source field keeps one compact translation handoff whose muted badge says only `Needs
  translation`, `Ready for review`, or `Approved for release` while its visible button names the
  locale action (`Write French (France)`, `Review French (France)`, or `Open French (France)`); the
  closed row no longer repeats the longer translate/review instruction sentence or a passive
  locale-status sentence before the author opens that editor, so normal authoring starts on source
  copy instead of auto-opening or repeating the translation console task copy. Dirty
  drafts make clear that Mojito still has only the last saved source, while rare readiness or
  selected-locale load failures stay in translation language instead of naming Mojito as the error;
  the active translation loader/retry drops the generic `Translation` eyebrow, says `Opening
  <language> translation` or `<language> translation could not open`, and keeps the retry action at
  `Try again` / `Opening...`; once that locale row is selected, its local load failure says
  `<language> translation could not load` plus `Try again to open it` instead of reopen/query
  plumbing, announces opening as a polite local status, keeps hard local load/unavailable failures as
  concise local alerts with their retry, refresh, language-choice, and reconnect actions described by
  the alert instead of included inside it, leaves post-repair `still needs work` as a polite local
  status with its `Try again` and reconnect actions described by that status instead of included in
  the live announcement, while missing-link
  states reconnect that saved copy inline, keep reconnect failures
  beside the same field/language with a focused retry, refresh clean saved copy before reconnect
  conflict retries, block release repair on dirty source copy until the saved-source handoff is
  current again, refresh release readiness after source-copy saves without claiming a translation
  repair saved until that blocker is actually fixed, and only fall back to technical admin tools
  after that local recovery stays unavailable.
- Announces blocked copy-piece switches as local alerts beside the active translation when unsaved
  translation edits or stale translation status stop the requested field from opening, so the author
  hears why focus stayed put before using the already-visible save/reset or retry recovery.
- Keeps the remaining detailed-review handoff attached to CMS context as optional translation tools:
  translation/review links open the relevant target locale instead of source-only detail, carry a
  durable return path back to the selected copy collection, content item, and field, and text-unit
  detail shows that compact author breadcrumb while the in-app handoff state is available; the mapped
  translation editor keeps that detailed-review link behind a task-specific optional disclosure
  (`More translation help` while writing, `More review help` while approving)
  as a quiet text control instead of leaving it in the normal save row, omits the inert clean `Reset`
  control until a translation draft actually changes, then exposes only the saved-translation link
  instead of opening another framed explanatory panel before the author can reach glossary, history,
  and detailed review.
  CMS-originated detail also replaces the
  generic text-unit/repository chrome with a focused translate/review frame, keeps source copy and
  translator note visible as author context, keeps `Back to Product copy` visible beside the return
  arrow so the optional review escape hatch still reads as part of the authoring flow, and folds ICU,
  AI chat review, glossary, history, and metadata under task-specific optional help (`Translation
  help` while writing, `Review help` while approving), with those optional panels collapsed until
  explicitly opened and one bottom `Back to translation` handoff that returns focus to the real editor
  after a long optional panel; malformed or manual source-only CMS detail links keep only source
  context instead of reopening optional detail tools without a translation task, while detailed-review
  links whose target translation row disappeared return to the inline Product copy recovery keyed by
  the durable locale return path instead of rendering an empty workbench-style translation frame, and
  reloaded/detail-shared CMS links carry the selected copy collection, content item, and field names
  beside that sanitized return path so the visible Product copy breadcrumb survives without reopening
  schema or repository identifiers, so local AI setup failures or Mojito internals cannot dominate the
  primary handoff.
- Derives the inline translation instruction from the selected locale, not only the content-item-wide
  readiness summary, so switching to an approved locale no longer leaves a stale `Write fr-FR translation`
  heading from another blocked locale; the editor keeps the recommended next save action inline and
  lets authors reach alternative `Keep as draft`, `Send for review`, and `Approve for release`
  states behind `Other actions`, while that row now reads as one `Save this translation` next step
  instead of an internal status picker; the less-common exclusion state stays behind that same
  disclosure, says release instead of localized-output jargon, keeps the
  passive current state as `Excluded from release`, asks for confirmation before changing release
  eligibility, and lets the visible save/review/approval path restore that locale without another
  hidden repair step, Mojito status
  values remain underneath, a never-started missing translation opens on a real `Start this
  translation` step instead of a dead disabled save row until the author types target copy, and the
  loaded active editor drops its generic `Translation` eyebrow and passive pre-field task sentence,
  while the save row keeps the one next step and the optional tools link keeps Mojito prose out of the
  normal writing path; clean
  mapped fields no longer repeat a separate localization strip or idle current-locale card once the
  field card and
  active translation editor already shows that state; the active translation editor also drops its redundant
  aggregate readiness card, keeps the locale in the active task heading while rendering the passive
  header badge as only `Needs translation`, `Ready for review`, or `Approved for release`, softens
  that passive target status to muted text, keeps a smaller neutral translation surface so
  localization stays inline without visually replacing the source editor, and lets the optional
  expanded language chooser stay below its disclosure control while showing locale names with author
  actions (`Write`, `Review`, `Open`, or `Refresh`) instead of per-locale readiness counts and
  preserving the full locale/status in each accessible button name; the
  sidecar also stops repeating translate/review instructions once the active translation editor opens,
  while source-sync warnings and the one no-locale target setup card remain visible where they still
  change the author's next action. When dirty target translation blocks focusing another field, the active
  translation editor now keeps and reveals that refusal beside the unsaved translation while the page
  notice still announces it globally, remembers the requested field, and opens plus reveals it after
  save or reset,
  returning focus to the requested source-copy textarea when that was the control the author tapped,
  so clicking another field does not look like a dead control or require a second field click after
  the author resolves the draft; a failed translation save keeps that requested field queued beside
  the local author-language error instead of jumping away from the unsaved translation, and a failed
  post-save translation-status refresh keeps the same requested field queued beside the revealed retry
  state until that refresh succeeds before resuming the source-copy focus. When a requested inline
  translation editor cannot load any translation status yet, it stays in author language with one
  local `Try again` action, shows the in-flight refresh state, and returns focus to the requested
  translation after the readiness retry succeeds; when that requested translation language was removed
  meanwhile, the editor keeps that exact language visible instead of switching silently, gives one
  quiet `Continue with <language>` action only when exactly one editable configured fallback remains,
  gives one scoped `Choose another language` action when several editable configured fallbacks remain
  instead of choosing the first locale arbitrarily, then focuses the configured translation the author
  deliberately chooses; when that requested configured language
  still exists but is not assigned to the current author, the editor replaces the dead disabled form
  with one no-access recovery, focuses `Choose another language` when an editable configured language
  exists, labels unavailable picker rows `No access`, and focuses the editable translation the author
  deliberately chooses; when a requested removed language has no editable configured replacement,
  the editor drops the dead language switch and keeps one focused add/access recovery instead of
  implying another translation can be opened; when no configured translation language remains at all,
  that removed-language return still keeps one focused add recovery visible instead of collapsing
  the whole translation surface, and that `Add <language>` action now submits the exact removed
  language through the same local authoring add-language flow instead of sending the author into
  admin setup; while the supported locale catalog is still loading, that inline recovery stays a
  neutral scoped status instead of announcing a false error, then hands focus to the exact local
  add/retry action when the catalog resolves; if the catalog fails to load or no longer contains
  that exact removed language, it swaps the dead add action for local retry/ask-admin copy instead
  of offering a button that cannot succeed; direct removed-language returns no longer stack the
  generic add-language card or a generic language-switch toggle under that focused recovery, and
  when exactly one configured fallback language is editable they expose one quiet
  `Continue with <language>` action instead; when several configured fallback languages are editable
  they expose one scoped `Choose another language` action instead of choosing the first locale
  arbitrarily; source-only recovery keeps the muted dismiss that restores the local add-language next
  step. When that dirty
  translation would be discarded by leaving the editor, switching copy collections, or switching
  content items, the app-owned confirmation now names the active field's translation edits instead of hiding
  that loss behind generic content-draft wording. While an inline translation save is still running,
  the author route stays busy and a blocked route-exit confirmation cannot discard that field; a
  successful save closes the stale prompt, while a failed save returns to the explicit unsaved
  translation discard decision beside the local author-language error. Status-only approval or
  exclusion failures now say which saved release state did not change, keep the matching release
  handoff visible or hidden from that saved state, and leave the exact retry action beside the active
  translation instead of implying the click landed. When the translation itself saves but the derived
  readiness refresh fails afterward, the editor keeps the saved text-unit status visible, labels
  readiness as needing refresh, withholds release handoff until the author retries that refresh, and
  shows that retry as pending instead of accepting duplicate clicks.
  While that refresh is failed, non-selected locale chips read as needing refresh, locale switching is
  held, and a requested field switch waits for the successful retry instead of opening another
  editor from stale completeness. Failed project readiness checks also clear any prior ready package,
  keep release disabled, and put the retry instruction in the release card itself instead of relying on
  a detached page notice.
- Keeps the project release card out of a draft translation path until the selected saved content item has
  ready target locales, while still leaving release visible when the copy collection already has an
  included content item that can be reviewed or released independently.
- Keeps inline readiness grammar human when only one field of source copy is blocked, so the visible
  next step says one field `needs` translation or approval instead of exposing pluralized system copy.
- Keeps that inline translation readiness scannable for larger locale sets by showing the selected
  locale status in the editor header, keeping blocked locales first inside an explicit `Switch
  language` disclosure, and requiring that opt-in switcher before the full repository locale list
  competes with the source-copy editor.
- Starts a clean CMS in the main workspace with one `Start with product copy` form that creates the
  copy collection and first source copy together from the author's point of view; that first viewport
  now reads as a constrained unframed authoring form with `Name the first copy` and `Write the copy`
  groups instead of opening on a `First block` setup label, onboarding eyebrow, or elevated setup card,
  keeps one quiet secondary `Use welcome email starter` helper beside `Start writing` only on the pristine draft,
  labels the optional metadata branch `Add details`, puts `Start writing` immediately after the
  required fields, and leaves optional placement plus author-facing copy-collection description inside
  that details branch instead of stacking setup controls below the primary action, says source
  copy is written for `translation` instead of
  `localization`, keeps required-state guidance in the intro, legends, and field labels instead of a
  validation footer below disabled `Start writing`, and keeps repository reuse plus generated identifiers out of that author route
  entirely; partially configured copy collections stay on the same authoring path
  instead of dropping an author into schema, entry, or variant setup forms; until one copy collection
  exists, the empty rail and `No copy collection selected` header state stay out of that first viewport
  so the one useful action owns the screen.
- Offers a clean-form-only `Use welcome email starter` action beside the primary action that appears
  only on the empty draft, then fills the real copy collection, content item, placement, source-copy,
  and translator-note fields before disappearing once drafting starts so the example demonstrates the
  workflow without overwriting author work.
- Keeps that same clean-start form available in the DEV-only deterministic authoring preview, so UI
  review and demo capture can show the first real author task without depending on local CMS data or
  recreating the flow in a fake mock screen.
- Keeps DEV preview mode registration in one shared frontend helper used by the preview surface and
  Vite bootstrap, so a new audit/demo state cannot silently fall through to the authenticated app.
- Keeps the secondary rail action as `New copy collection`, but makes it open the same
  `Start with product copy` authoring form in the main workspace instead of creating an empty
  collection first; the copy collection and its first source copy are created together, abandoning that
  draft asks before discarding it, keeps the description inside `Add details`, and
  leaves Mojito repository reuse plus generated identifiers out of the author route so creating
  another collection does not reopen the old admin setup surface.
- Gives that first content item one default editable `Copy` field behind the scenes, so the clean-start
  author path asks for item context, source copy, and translator note before asking anyone to model
  named fields.
- Keeps a saved copy collection that is still missing its first content item on that same authoring
  form directly in the workspace, without wrapping the task in a generic `settings-card` setup panel
  or a redundant `Start writing` heading before the real first-item form; that direct first-item task
  now reuses the normal new-content-item authoring surface instead of exposing the old generic admin
  form divider once the wrapper is gone.
- Persists the first default block shape, its default `Copy` field, the first content item, and its
  initial generated Mojito source inside one backend transaction, so clean start cannot strand a
  half-created shape when its required source handoff fails.
- If opening that first copy collection succeeds before its first content item save fails, keeps the
  authored first-item draft in the newly opened editor so the recovery notice does not discard the
  copy it tells the author to finish.
- Keeps author-path save/start failures in one local recovery alert beside the blocked form or field,
  clears any stale page notice at the valid author submit boundary before that local recovery owns a
  new failure, while the page-level live notice stays reserved for admin tools; Product copy therefore
  does not double-announce the same backend failure before the author can retry the visible action.
- Keeps field/schema creation out of the primary author path. Writers edit the fields already defined
  for the content item; a rare zero-field saved item stays in the writer shell with a local `Repair
  content item` action beside the blocked copy instead of offering a schema mutation beside source
  copy. That blocked saved item also withholds the normal `Write the copy` heading until writable copy
  fields exist, so repair does not masquerade as an authoring task. Admin tools continue to own
  new-field creation, hidden content-type forks, and raw field controls.
- Saves later content items with their first source copy and translator note in the same primary
  authoring action, instead of creating an empty content item and sending authors to a second save step.
- Opens `Write new content item` as its own main writing surface instead of stacking a new-item form
  above the selected saved content item, reuses the same `Name this content item` and `Write the copy`
  groups from clean start instead of opening on a `New block` setup label, describes that first writing
  task as source copy plus translator notes instead of fields that “will be localized,” keeps optional
  item placement behind `Content item details` until the author opens it or a recovered/example draft
  already contains placement, and asks before either hiding dirty saved copy or abandoning typed
  new-item copy; the release card stays out of that unsaved new-item mode until the new source copy is
  saved, and the shared first/later item form stays unframed instead of reopening the old tinted
  add-item panel around the writing task; required/optional state stays beside each actual field instead
  of reopening an aggregate required-field counter before the author writes.
- Opens every required existing field when starting a later content item, so multi-field content items
  do not save as knowingly incomplete while optional fields can still wait for a later pass.
- Keeps blank optional copy quiet after that first save: the saved editor labels it `Optional copy can
  wait` and withholds translation handoff and save chrome until an author starts writing it, instead
  of immediately restating the required-copy warning the author just satisfied; its untouched
  translator note says `Add when writing this copy`, its dormant inputs do not claim native required
  state before writing starts, and any field-level or item-level source save stays disabled until both
  source copy and translator note are present.
- Stacks the saved content-item header status row and long field helper/status rows on phone widths,
  so saved-item `Item details`, saved-field progress, translator-note hints, and translation readiness
  do not clip at narrow widths.
- Keeps clean-start, first-item recovery, and later-item primary actions disabled until the content
  item name plus every required source-copy and translator-note field are filled, so an empty authoring
  form does not look ready to save before the visible localization contract is satisfied; the first/later
  item forms keep that guidance in their intro and field labels instead of adding a validation footer
  below the disabled save action.
- Persists a new content item and its initial generated Mojito source mappings inside one backend create
  transaction, so the primary `Save content item` action cannot leave a half-created required item
  when a later initial source mapping fails.
- Keeps schema, generated keys, repository/asset plumbing, variants, mappings, raw completeness
  tables, publish snapshots, and destructive maintenance off the authoring route entirely; the
  settings directory exposes Product copy as the normal CMS entry point, while the healthy authoring
  DOM keeps the dedicated `/settings/system/content-cms/admin` route out of the writing flow and only
  blocked writer states expose one local repair action scoped to the blocked content item, source link,
  or translation;
  that route now lands on a terse `Maintenance tools` overview with structure/mapping and advanced
  maintenance drawers closed, then keeps copy-structure repair, experiment-candidate creation, Mojito-link
  repair, copy-collection edits, saved copy-structure edits, and raw-record debugging behind focused task
  drawers instead of mounting every technical form or table at once; copy-structure repair and edit
  drawers now open one explicit action selector before rendering a single technical form, so even
  intentional maintenance avoids the old multi-form wall; those focused forms say block shape, copy
  piece, copy block, and Mojito link before exposing the stable keys or exact Mojito IDs a repair may
  still need, then keep stable IDs, ordering flags, and raw metadata under a per-form `Technical
  fields` disclosure with create-time keys suggested from the human name until an admin overrides
  them; experiment-candidate creation now says exactly that task, defaults new records to candidate
  rather than default, and keeps the visible experiment group beside the candidate name while the
  path role remains technical; existing experiment-path maintenance now uses that same path
  language, keeps experiment group beside the path name, and leaves path role plus stable identifiers
  under technical fields; focused maintenance notices, switch prompts, and operator
  confirmations now reuse block-shape, copy-piece, copy-block, experiment-path, and release
  language instead of snapping back to content-type, field, entry, variant, or package nouns after
  the admin intentionally repairs one thing; copy-collection maintenance now opens as the explicit
  rename/disable task, keeps description beside the human name, and leaves delivery format plus exact
  Mojito/audit records behind local technical disclosures instead of reopening fixed delivery plumbing
  in the focused repair path; Mojito-link repair follows the same rule, keeping saved copy selection plus source copy and
  translator note primary while alternate link sources and exact Mojito/TM IDs stay under the local
  technical disclosure, so the fallback screen reads as deliberate repair mode instead of the old
  default admin console; the raw-record task is labeled as read-only debug work before exact tables
  render, and those exact-record tables now repeat `Read-only records` plus read-only table labels,
  so they cannot masquerade as a normal editorial review step; the dirty-source recovery link in the
  author card now says saved translation instead of naming Mojito directly, while explicit Mojito
  entry remains behind translation tools or admin repair.
- Keeps rare broken-state author hints in copy language too: unavailable source-copy editing says the
  content item needs repair, and reused source links say the field reuses saved source copy, then
  puts one local repair action beside the blocked editor instead of making
  the author leave the blocked editor to find the admin route; the temporary saved-source handoff
  drops the broader `Localization` eyebrow and keeps translation status only in its accessible label;
  even the defensive source-save notice now asks for a field of source copy instead of a backend
  `copy piece`, so those rare states do not fall back to visible `Mojito`, `variant`, `field`,
  `binding`, or `setup` terms.
- Shows one unframed author-facing `Release approved copy` step in the normal workspace only after a
  ready content item is included in the next release, not merely after its translations become ready;
  source-only copy stays on the inline translation-language picker instead of showing a disabled
  release card before any translated locale can ship, and a failed readiness refresh keeps the saved
  release step visible with recovery instead of hiding the only shipping path behind stale derived
  state. That step is a named release region, keeps `Release approved copy` as the one author-card
  action described by its current readiness summary plus any active release recovery, owns that terse
  readiness announcement instead of firing a detached page notice or announcing optional
  release-detail, blocker, or recovery controls, rechecks
  readiness from that action before confirmation when needed, keeps only one terse last-release
  sentence in the author path without snapshot version language, and leaves release size, released JSON
  links, SHA metadata, locale scope, and retained release history on the admin route behind `Technical
  release details`.
- Gives each saved content item a primary `Include in next release` control after its source-copy editor
  once its target locales are ready, so the normal CMS path can move a drafted item into the next
  release without putting shipping state ahead of writing/translation setup or opening the raw
  `READY` lifecycle editor; included items keep a lighter `Remove from release` recovery action,
  archived items keep restore visible, and the underlying service still validates required saved copy
  before accepting the status change.
- When the last target locale becomes approved, leaves one compact `Ready for release` handoff in
  the active translation editor with a `Next step` eyebrow and the newly unlocked `Include in next
  release` action, so an author can take the next real step without hunting below the translation
  textarea or opening the full release workflow beside it.
- Reuses the same locale/source blocker wording in that item release strip and the release card, so
  an included item says the exact remaining author action such as `fr-FR needs translation before
  release` instead of forcing authors to infer it from a separate translation panel.
- Gives included items one primary `Go to release` jump to that existing release card, keeps
  `Release approved copy` as the only author-card action, and leaves explicit `Check readiness` only
  in admin tools, so the selected content item keeps `Write the copy` before project-level release
  chrome while the item strip still points directly to the real release action instead of duplicating
  the release workflow, making authors guess which release action comes next, or exposing a technical
  preflight in the primary path; when the inline `Include in next release` handoff succeeds, the page
  moves focus to that nearby below-copy release card instead of making the author rediscover a separate
  route.
- Keeps that selected-item release handoff as a named author region but scopes polite announcement to
  its current release hint, with `Include`, `Remove`, and `Go to release` actions described by the
  hint instead of being reread inside a changing live region.
- Keeps that nearby author release card compact after a check by leaving the readiness sentence,
  retry state, and concrete blockers inline while folding the included-item chips plus prior-release
  change audit behind one `Show release details` disclosure only when it reveals another included item
  or real release delta; a single current included item keeps only the quiet count, so a long release
  history or no-op detail row does not turn the normal editor back into a release console before the
  author needs that audit.
- When that readiness check fails, lists the included content items that still need work with their
  exact source/locale blocker and one visible `Write translation`, `Review translation`, or `Fix
  source copy` jump back to the normal editor, groups
  repeated field blockers under their content item, and puts the selected content item plus current
  field first when they are blocked; longer same-item blocker lists keep the first two repair rows
  visible and fold the rest behind one `Show more fields` control, so a multi-item release does not
  collapse into an aggregate project error, make authors scan repeated item names before returning to
  visible copy, or turn the nearby release card into a tall checklist before the first repair action.
  - Clears checked release readiness as soon as inline translation edits become dirty or a saved
    translation changes release eligibility, and disables the author release buttons until dirty
    translation edits are saved or reset, so an author cannot release a stale checked state while the
    visible translation no longer matches it.
  - When an author opens a release blocker, repairs source copy or inline translation state, and saves
    from that normal editor, silently rechecks project release readiness after the fresh project detail
    is installed; the nearby release card updates from the stale blocker to the next real blocker or
    ready state instead of dropping back to a manual recheck detour, the field-level return handoff
    switches from stale blocker wording to `Repair saved`, and an automatic recheck failure says the
    repair saved while keeping the same release action available to retry inside that release card.
  - Keeps `Release approved copy` disabled only while visible author drafts are unsaved; otherwise
    clicking it rechecks the current copy before confirmation, so clearing a stale readiness result
    cannot leave an apparently valid release action bound to changed copy.
  - Keeps final release failures inside that same release card with one retry or recheck instruction,
    and rewrites the page alert into release language on the author route, so a failed release request
    does not strand the author on a distant backend alert after copy was already checked.
  - Keeps an unresolved release retry in session storage across a refresh/remount for the same copy
    revision, restores one terse retry card, and reuses the same publish request key after reload so an
    author does not accidentally create a second release while recovering from an indeterminate response.
- Keeps author-facing source-copy, translation, target-locale, and readiness mutation failures in
  copy or release language while admin tools keep exact backend diagnostics, so a normal author does
  not get stranded on CMS/TM/package error text after taking a primary editing action.
- Keeps clean-start, first-content-item, source-copy, copy-details, placement-details, later-content-item,
  and target-locale save recovery beside the start form, first-copy form, source form, open details
  panel, visible placement-details form, focused writing form, or locale picker that failed while retaining
  the page status for global announcements, so an author does not have to scan back to the top of the
  page after a local save failure.
- Keeps selected-block include/remove release recovery beside that block's release strip while the
  page status announces the failure once, so an author does not have to scan from a local release
  action back to the top banner.
- Keeps the raw comma-separated release locale scope on the dedicated admin tools route under
  `Technical release details`, keeps that disclosure closed even on the admin landing screen, so the
  normal release card checks configured locales by default instead of asking authors to type package
  internals before they can release approved copy.
- Surfaces when the project rail search response is truncated, so an admin does not mistake the
  first page of matching CMS projects for the full result set.
- Treats project rail search as a filter over selectable rows, not as an implicit workspace switch,
  so a filtered or freshly refetched rail cannot replace the project an admin just saved or is
  still reviewing until the admin clicks another row.
- Surfaces retry actions when the project rail or selected project detail load fails; the author
  route no longer preloads a repository picker, so repository-list loading, failure, and empty states
  cannot block default copy-collection creation.
- Runs release readiness against the same locale-tag input that publish uses, so the author does
  not check one scope and accidentally release another.
- Clears rendered readiness results when the locale-tag input changes, so a checked release result
  cannot stay visible after the scope no longer matches the next release request.
- Clears rendered readiness results when the check starts or fails, so a previous successful
  preflight cannot stay visible beside an in-flight, invalid, or server-rejected release check.
- Treats a readiness response with a newer server authoring SHA as a stale-tab conflict, clears the
  derived result, and refreshes project detail before the author can check readiness or release
  again.
- Clears rendered readiness results if a later selected-project detail refresh carries a newer
  authoring SHA, so an external admin edit cannot leave a stale checked release visible until the
  backend rejects publish.
- Locks project rail selection and project search while project-scoped authoring, readiness, or
  publish work is pending, and ignores late completeness/publish callbacks after an out-of-band
  selection change, so a prior project's async response cannot repaint another workspace.
- Lets admins edit names, metadata, field properties, lifecycle status, and delivery hint from
  collapsed maintenance areas while keeping project/type/entry/variant/field keys, repository, and
  virtual asset fixed after create.
- Uses app-owned operator confirmation dialogs for candidate or archived variant promotion before the
  service archives the current control, so a lifecycle dropdown cannot silently change the served
  copy baseline.
- Uses app-owned operator confirmation dialogs for project disable, moving a `READY` entry out of
  `READY`, archiving an active variant, unmapping a field, and approved-copy release before the UI
  sends changes that stop latest release discovery, remove copy from the next release, or create a
  release snapshot; the service still enforces the structural lifecycle invariants.
- Refreshes project detail after a stale-write `409`, clears stale completeness results and
  unresolved publish intents for that loaded revision, so a long-lived admin tab reloads current
  entity versions before another save or publish.
- Keeps a dirty generated-source draft visible when that refresh finds newer saved source copy,
  adopts the refreshed mapping version for an intentional retry, and shows the current saved copy
  beside the draft with a `Use saved copy` recovery action before an author overwrites another
  editor's change; that stale-copy notice announces only its terse saved-elsewhere status and
  describes the adoption action from that status instead of announcing compare cards or the button.
- Gives dirty copy-placement and open content-item-detail drafts the same inspect-before-overwrite
  recovery: refreshed saved context/details stay beside the author's draft, adopt the latest field
  or block version for an intentional retry, and offer `Use saved placement details` or `Use saved details`
  before replacing local work, with those local adoption actions described by the same concise
  saved-elsewhere status instead of being included in its live announcement.
- Keeps release-review source/translation compare cards visible beside the opened authoring field while
  announcing only their terse release-review prompt, so screen readers do not reread full before/after
  copy payloads when an author opens a release change from the primary path.
- Seeds successful authoring mutation responses into the selected project detail and reconciles
  cached project-search rows against the same name/project-key substring filter and name/id order
  used by the server before the broader CMS query invalidation refetches, so a slow refresh cannot
  leave a success notice beside stale form state or stale visible rail membership/content.
- Seeds successful immutable publish snapshot responses into visible snapshot history before the
  broader CMS query invalidation refetches, so a slow refresh cannot leave a publish success notice
  beside stale snapshot history.
- Carries the server-returned first-page snapshot-history cursor through project detail and uses
  returned cursors for older history pages, so the admin page does not reconstruct a metadata-only
  cursor from visible rows after the service validates the recent artifact window.
- Sends the server-returned authoring SHA with publish, so project detail freshness is an explicit
  optimistic publish precondition rather than an implicit React Query timing assumption.
- Serves mutable project/search/completeness reads, authoring write responses, and CMS error/security
  responses with `no-store`, so browser HTTP cache does not outlive the UI's short React Query
  freshness window or cache a pre-publish delivery miss; only exact published artifact exports are
  HTTP-cacheable.
- Uses app-owned discard dialogs before resetting project-scoped authoring drafts when the selected
  project or visible content item changes, so operators do not silently lose in-progress copy while
  stale child IDs from the previous project still cannot direct a later field, variant, or mapping
  write at the wrong project.
- Uses app-owned discard dialogs before dirty stable-edit or mapping selector changes replace the
  matching local draft, and app-owned leave dialogs before dirty CMS route exits through the settings
  back control, same-origin app links, or browser history POP. The app root uses React Router's
  data-router provider so the CMS can use the supported route blocker surface instead of a fragile
  custom history trampoline; native unload is still marked unsafe while CMS work is saving or drafts
  are dirty because browser unload confirmation is browser-owned.
- Shows technical tables for content types, entries, variants, mappings, and snapshots only after the
  operator leaves the authoring route for dedicated admin tools.
- Mapping rows link into Mojito text-unit detail so authors can use the existing
  translate/review/approve surface instead of a CMS-specific translation editor.
- Mapping authoring makes generated CMS strings, existing Mojito string IDs, and exact TM text-unit
  bindings explicit; source/context controls edit only generated bindings, so editing generated
  source copy does not accidentally turn into an external text-unit remap.
- Avoids marketing copy, decorative cards, media, and page-builder affordances; a product-specific
  runtime preview remains separate follow-up work because the MVP artifact has no product renderer.

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
  stable latest descriptor and exact versioned artifact `GET`/`HEAD` routes additionally admit
  `ROLE_CMS_DELIVERY`. The delivery account is not an interactive Mojito role: generic
  authenticated UI/API routes require `ROLE_USER`, `ROLE_TRANSLATOR`, `ROLE_PM`, or `ROLE_ADMIN`
  so the provider fetcher cannot browse text units, screenshots, MCP, or user-session APIs.
  Unauthenticated nested delivery requests stay on Mojito's API `401` path instead of following
  the browser login redirect. The only non-artifact API admission is Mojito's session-scoped CSRF
  token endpoint required by the existing stateful form-login REST client before it makes the
  signed descriptor/artifact GET; public frontend config remains public for login bootstrap but
  rehydrates the current user through Mojito's fetched legacy user graph before serializing the
  public profile, tolerates concurrent first-request partial header-user creation by reloading the
  unique username winner, and renders a delivery session as anonymous instead of exposing a
  service-account profile. CMS
  not-found and optimistic/data
  conflict responses use explicit service failure types instead of inferring HTTP status from
  mutable error message text.
- The service creates a dedicated virtual asset for each project, maps or generates active Mojito
  virtual text units through a CMS-only source registration path, rejects later generic
  virtual-asset source, asset-content/extraction, low-level TM source-create, or `/api/assets`
  logical delete mutation for CMS-owned assets, can unmap CMS field bindings without deleting
  Mojito text units, validates entry metadata and locale completeness, gates snapshots on ready
  content, preserves
  control/publish concurrency invariants, rejects stale admin writes with entity versions, keeps
  PATCH partial-update semantics, records mutable CMS authoring actors, reapplies ICU integrity for
  `ICU_MESSAGE` fields, checks append-only snapshot history and validates the provider-neutral
  artifact envelope while deriving a complete publishable package hash, validates recent retained
  artifact integrity before returning that package hash or appending another snapshot, and validates
  the signed artifact again before saving or sealing a publish snapshot so direct DB drift cannot
  surface as a false-ready package or commit an unreadable
  delivery artifact, returns `409` for concurrent constraint races, and produces immutable JSON
  snapshots.

Frontend

- `frontend/src/api/content-cms.ts` adds the typed REST client.
- `AdminContentCmsPage` adds the settings subpage.
- `AdminSettingsPage` links to the subpage from the admin settings directory.
- Authoring routes are registered for `/settings/system/content-cms` and
  `/settings/admin/content-cms`; dedicated technical routes live at
  `/settings/system/content-cms/admin` and `/settings/admin/content-cms/admin`. The admin-only CMS
  surface lazy-loads at that route boundary so its editor/forms do not inflate the initial app entry
  chunk for users who never open Product copy.

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
- Provider-specific Statsig/blob write jobs before one delivery contract is chosen.
- Full editorial role model beyond admin-only MVP authoring operations.
- Rich nested or per-variant schema validation for arbitrary metadata JSON.

Validation Plan

- Run Java formatting and compile focused on `webapp`.
- Run frontend format, lint fix, typecheck, and tests with the Maven-managed Node/npm.
- Run narrow service coverage for virtual text-unit mapping, lifecycle-aware publish, completeness,
  stale-write rejection, and artifact shape.
- Smoke the new settings route in Browser when a dev server/static build is available.
- Keep DEV-only `cmsPreview=authoring-demo` and `cmsPreview=stale-recovery` seeds that render the
  real authoring workspace without auth or API traffic, so Browser can inspect the normal author
  path plus rare stale-draft recovery UI on a disposable frontend port without touching a developer's
  backend.
- Keep provider delivery jobs, richer nested/per-variant metadata schema enforcement, and runtime
  integration tracked separately instead of expanding this MVP into a full CMS platform.

Validation Evidence

- `mvn spotless:apply` and `mvn spotless:check` passed.
- `mvn -pl webapp -am -Pfrontend test-compile -DskipTests` passed after the current CMS admin lazy-route hardening; the Maven-managed Vite build emitted separate `AdminContentCmsPage` JS/CSS chunks while the remaining app-wide `>500 kB` Vite warning stayed outside this CMS-only route split.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=FrontendConfigControllerTest,UserWSUnitTest,UserServiceUnitTest test` passed after the CMS route browser smoke exposed detached header-auth profile serialization before the page mounts plus a concurrent first-request partial-user insert race; both public profile endpoints now map Mojito's rehydrated current user instead of the detached security principal, and partial header-user creation reloads the unique username winner after a lost create race.
- `source webapp/use_local_npm.sh` followed by `npm --prefix webapp/frontend run format`, `npm --prefix webapp/frontend run lint:fix`, `npm --prefix webapp/frontend run tsc`, and `npm --prefix webapp/frontend run test` passed after the author-first CMS redesign, including the prior hardening plus clean-copy-collection quickstart, clean-form-only onboarding/welcome-email example fill, dedicated-repository default start, atomic first-content-item creation, first/later content item creation, required multi-field item start with optional-field deferral, inline translation readiness with many-language condensation, source-only translation-language honesty and inline language setup, CMS-native translation editing with language switching/status/save validation, saved-state confirmation and dirty translation-edit language/tool plus CMS route-exit guards, translation-needed vs review-needed Mojito fallback, context-preserving Mojito return links, CMS-originated focused detail with opt-in translation tools, collapsed technical tools, primary-path internal-term removal, saved editor metadata collapsed into one content-item details panel, selected-item release strip after source copy, rare recovery hints kept in author language, primary-path field/schema creation removed from the writer route, author-facing release readiness with package/artifact internals hidden until technical disclosure plus primary include/remove-from-release control and exact blocker wording, visible item placement plus per-field placement persistence, open inline field source/context drafting with multi-field save coverage, inline Mojito handoff status/link coverage, focused later content item writing mode with discard guards, app-owned discard/leave dialogs for primary authoring navigation, and app-owned operator confirmation dialogs for advanced delivery-scope/publish actions instead of native browser prompts; Vitest reported 21 files and 195 tests passed, with only the existing npm `http-proxy` config warning.
- `source webapp/use_local_npm.sh` followed by `npm --prefix webapp/frontend run test -- AdminContentCmsPage.test.tsx`, `npm --prefix webapp/frontend run tsc`, `npm --prefix webapp/frontend run lint`, `npm --prefix webapp/frontend run format:check`, and `git diff --check` passed after stale saved authoring recovery kept source-copy, copy-placement, and open content-item-detail drafts visible, showed refreshed saved values beside them, let authors adopt saved values before retrying, and proved `Save copy changes` keeps later source-copy drafts intact while a refreshed placement-details conflict is resolved first; Vitest reported 157 tests passed, with only the existing npm `http-proxy` config warning.
- `source webapp/use_local_npm.sh` followed by `npm --prefix webapp/frontend run test -- App.test.tsx AdminContentCmsPage.test.tsx`, `npm --prefix webapp/frontend run tsc`, `npm --prefix webapp/frontend run lint`, `npm --prefix webapp/frontend run format:check`, `git diff --check`, and a production `vite build --outDir /private/tmp/mojito-cms-preview-build` passed after adding the DEV-only Browser seeds; Vitest reported 160 tests passed, the production build emitted only the normal app/CMS chunks with no preview marker, then Browser opened `http://127.0.0.1:9876/settings/system/content-cms?cmsPreview=stale-recovery`, verified the real authoring workspace plus stale saved copy/context/detail recovery panels at narrow and desktop widths, and found no browser console warnings/errors without contacting the backend on `8080`.
- Browser opened `http://127.0.0.1:9876/settings/system/content-cms?cmsPreview=authoring-demo`, verified the normal Product copy authoring path with no repository grid or stale-recovery text, found no browser console warnings/errors, then captured the real workspace into the persistent `dev-docs/demos/content-cms-authoring-demo.mp4` walkthrough plus source frames without contacting the backend on `8080`.
- `source webapp/use_local_npm.sh` followed by `npm --prefix webapp/frontend run test -- App.test.tsx AdminContentCmsPage.test.tsx`, `npm --prefix webapp/frontend run tsc`, `npm --prefix webapp/frontend run lint`, `npm --prefix webapp/frontend run format:check`, and `git diff --check` passed after clean saved authoring forms stopped offering no-op source-copy, placement-details, and content-item-detail saves; Vitest reported 161 tests passed, then Browser reopened the DEV-only `cmsPreview=authoring-demo` seed on disposable `127.0.0.1:9876`, verified those three clean save controls stay disabled with no repository-grid fall-through or browser console warnings/errors, and the disposable Vite server was stopped.
- A refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174` exercised the real header-authenticated Product copy route without touching the
  occupied `8080` port: `/api/users/me` resolved the dev `admin`, `/api/content-cms/projects`
  started empty, Browser filled the clean welcome-email example, saved the first copy collection and
  content item, verified the saved author workspace keeps source-copy, placement-details, and item-detail
  no-op saves disabled while inline translation status stays attached to the copy, found no primary
  path Mojito repository/key/mapping/variant/publish internals or browser warning/error logs, and the
  disposable Vite/backend servers were stopped afterward.
- A second refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, this time with the CMS snapshot signing key configured, exercised the real
  header-authenticated locale-to-release handoff without touching the occupied `8080` port:
  Browser created the same clean welcome-email copy, added `fr-FR`, saved the inline French
  translation, sent it for review, approved it, verified `Ready for release` reveals the release
  step only after approval, included the content item, checked green readiness, confirmed the app-owned
  release dialog, observed the terse author-facing last-release sentence with no browser warning/error logs, and
  the disposable Vite/backend servers were stopped afterward.
- A third refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, exercised the real
  header-authenticated multi-field authoring path: Browser created the welcome-email content item plus a
  CTA-label field, kept source/context drafts for both open fields through the app-owned dirty-route
  `Stay here` dismissal, saved both drafts through `Save copy changes`, verified the primary author
  notice says `Saved source copy` instead of exposing Mojito-link internals, found no browser
  warning/error logs, and the disposable Vite/backend servers were stopped afterward.
- A fourth refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, again without touching the occupied `8080` port, exercised the real required
  multi-field later-item start with optional deferral: Browser created a second CTA-label field,
  marked it optional through the local CMS API setup, verified the author form keeps that optional
  field open with `Optional fields can wait.`, saved a new content item with only the required source
  copy, caught and fixed the live editor staying on the previous item after save, then verified the
  saved item opens immediately as `1/2 fields saved` with the optional CTA showing `Needs source copy`; browser
  warning/error logs were empty, and the disposable Vite/backend servers were stopped afterward.
- A fifth refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, exercised deferred optional
  copy completion from the normal saved editor: Browser created a CTA-label field, marked it optional
  through the local CMS API setup, saved a later content item with only required source copy, caught and
  fixed the saved multi-field editor exposing only generic source/note/context labels, then filled the
  deferred CTA source copy and translator note through its field-qualified controls and verified
  `Save source copy` turns that content item from `1/2` to `2/2 fields saved` without Mojito-link wording;
  browser warning/error logs were empty, and the disposable Vite/backend servers were stopped afterward.
- A sixth refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, exercised that same deferred
  optional-copy completion at a `390x844` phone viewport: Browser verified the source-copy editor remains
  above the copy-collection rail with no horizontal overflow, then focused and filled the deferred CTA source copy and
  translator note from the saved editor and verified `Save source copy` turns that content item from `1/2`
  to `2/2 fields saved` without Mojito-link wording; browser warning/error logs were empty, and the
  disposable Vite/backend servers were stopped afterward.
- A seventh refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, exercised the source-only
  target-locale handoff at a `390x844` phone viewport: Browser saved the welcome-email source copy,
  verified the locale menu stays within the viewport with no horizontal overflow, forced the trigger
  near the lower viewport edge and verified both common-language and `Search all languages` menus flip
  above it instead of clipping below, selected `fr-FR`, added it from the CMS, and verified the inline
  `fr-FR Needs translation` editor replaces the setup card without browser warning/error logs; the
  disposable Vite/backend servers were stopped afterward.
- An eighth refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, exercised the inline
  translation path at a `390x844` phone viewport: Browser added `fr-FR`, wrote the French draft,
  verified the phone action row wraps without horizontal overflow, opened the app-owned leave dialog
  and confirmed `Stay here` preserves the dirty translation, then sent it for review, approved it for
  release, and verified the ready-for-release handoff replaces source-only setup without browser
  warning/error logs; the disposable Vite/backend servers were stopped afterward.
- A ninth refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, exercised the approved-copy
  release handoff at a `390x844` phone viewport: Browser approved `fr-FR`, used `Ready for
  release` into the existing release step, included the content item, checked green readiness, verified
  the phone actions and app-owned release confirmation stay within the viewport without
  publish-internal wording on the primary path, confirmed `Release copy`, observed the terse
  author-facing last-release sentence, and found no browser warning/error logs; the disposable Vite/backend servers were
  stopped afterward.
- A later disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching the
  occupied `8080` port or starting a backend, removed the earlier `What you can do` workflow note
  after rendered audit showed it was generic instructional chrome above the editor; Browser verified
  the seeded Product copy route now opens directly on copy collections, content items, and source-copy
  fields with no primary-path Mojito/repository/mapping/variant/publish/artifact/snapshot/JSON wording,
  no phone-width overflow, and no browser warning/error logs.
- An eleventh refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174`, still without touching the occupied `8080` port, verified the real empty Product
  copy path now exposes the safe walkthrough as a direct pristine-draft helper beside `Start writing`:
  Browser clicked `Use welcome email starter`, observed the real copy collection, content item,
  placement, source-copy, and translator-note drafts fill while the action disappeared, then kept that
  filled form inside a `390x844`
  phone viewport without horizontal overflow, primary-path Mojito/repository/mapping/variant/publish/
  artifact/snapshot/JSON wording, or browser warning/error logs; the disposable Vite/backend servers
  were stopped afterward.
- A twelfth disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching the
  occupied `8080` port or starting a backend, verified the seeded walkthrough now includes a realistic
  two-field welcome email with editable `CTA label` source-copy and translator-note controls while
  fake `Preview frame`/`Delivery format` controls, primary-path repository/mapping/variant/publish/
  artifact/snapshot/JSON wording, phone-width overflow, and browser warning/error logs stay absent;
  the disposable Vite server was stopped afterward.
- A thirteenth disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching the
  occupied `8080` port or starting a backend, verified the saved writer route no longer offers field
  creation beside source copy; Browser found no `Add another field` or `Add first field` action in the
  first `390x844` viewport while admin tools remain the recovery path for rare zero-field items, with no
  horizontal overflow, primary-path repository/mapping/variant/publish/artifact/snapshot/JSON wording,
  or browser warning/error logs; the disposable Vite server was stopped afterward.
- A sixteenth disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching the
  occupied `8080` port or starting a backend, verified the top-level author grouping now says `Copy
  collections` instead of the invented `Writing spaces`; Browser found that collection rail in the
  default desktop viewport, confirmed `Writing spaces` no longer rendered, then kept the `390x844`
  phone route on the Product copy workspace with no horizontal overflow, primary-path repository/mapping/variant/
  publish/artifact/snapshot/JSON wording, or browser warning/error logs; the disposable Vite server was
  stopped afterward.
- A seventeenth disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching
  the occupied `8080` port or starting a backend, verified the main author landmark now says
  `Product copy workspace` instead of exposing the component-flavored `Copy editor`; Browser found
  that workspace in the default desktop viewport and first `390x844` phone viewport with no
  horizontal overflow, primary-path repository/mapping/variant/publish/artifact/snapshot/JSON
  wording, or browser warning/error logs; the disposable Vite server was stopped afterward.
- An eighteenth disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching
  the occupied `8080` port or starting a backend, verified the primary author path now uses standard
  CMS nouns `Content items` and `fields` instead of the bespoke `Copy blocks` and `pieces of copy`;
  Browser found the desktop item rail with saved fields open and no fake copy preview rail, then kept the
  `390x844` phone workspace free of horizontal overflow, primary-path repository/mapping/variant/
  publish/artifact/snapshot/JSON wording, or browser warning/error logs; the disposable Vite server
  was stopped afterward.
- A nineteenth disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching
  the occupied `8080` port or starting a backend, verified the selected-project author workspace
  names its configured writing language instead of hardcoding English; Browser found `Writing in
  English.` plus `Write source copy in English` in the desktop and `390x844` phone previews, found no
  stale `Write the English source copy` prompt, horizontal overflow, primary-path repository/mapping/
  variant/publish/artifact/snapshot/JSON wording, or browser warning/error logs, and the disposable
  Vite server was stopped afterward.
- Later rendered audit removed the earlier preview rail entirely after it proved to be source-copy
  echo rather than a product preview; until a renderer contract exists, the author route keeps one
  full-width writing surface instead of repeating saved copy beside the editor.
- A fresh disposable Vite-only authoring preview on `127.0.0.1:9876`, still without touching the
  occupied `8080` port or starting a backend, verified the primary author workspace says `Writing in
  English.` and the opened inline editor exposes `Translation languages` instead of rendered `Source
  locale:`/`Target locales`; desktop and `390x844` phone audits found no horizontal overflow, fake
  preview rail, or local preview warning/error logs before the disposable Vite server was stopped.
- A twenty-second disposable Vite-only authoring preview on `127.0.0.1:9876`, still without using
  the reserved `8080` port or starting a backend, verified the primary inline translation editor
  names translation languages with author-facing display names instead of raw locale tags; Browser found
  `Write French (France) translation`, `French (France) Needs translation`, and `French (France)
  translation` in the desktop and `390x844` phone previews with no horizontal overflow,
  primary-path repository/mapping/variant/publish/artifact/snapshot/JSON wording, or local preview
  warning/error logs, and the disposable Vite server was stopped afterward.
- A configured-language phone audit then removed the editor's repeated passive `Needs translation`
  badge from the `Next step` row when the editor header already says the same thing; distinct state
  such as `Excluded from release` remains where it changes the author's options.
- A twenty-third disposable Vite-only release-blocker preview on `127.0.0.1:9876`, still without
  using the reserved `8080` port or starting a backend, verified visible author release blockers
  name translation languages with author-facing display names instead of raw locale tags; Browser found
  the `Release this copy` region and `French (France) needs translation before release.` while no
  visible `fr-FR needs translation before release.` remained in the desktop and `390x844` phone
  previews with no horizontal overflow, primary-path repository/mapping/variant/publish/artifact/
  snapshot/JSON wording, or local preview warning/error logs, and the disposable Vite server was
  stopped afterward.
- A twenty-fourth disposable Vite-only release-blocker preview on `127.0.0.1:9876`, still without
  using the reserved `8080` port or starting a backend, verified visible author latest-release copy
  names released locales with author-facing display names instead of raw locale tags; Browser found
  `Release approved copy`, `Last released English and French (France) ·`, and `French (France)
  needs translation before release.` while no visible `Last released v7 ·`, `Last released en, fr-FR ·`, or `fr-FR
  needs translation before release.` remained in the desktop and `390x844` phone previews with no
  horizontal overflow, primary-path repository/mapping/variant/publish/artifact/snapshot/JSON wording,
  or local preview warning/error logs, and the disposable Vite server was stopped afterward.
- Focused `AdminContentCmsPage` release-confirmation coverage passed after mapping visible explicit
  release scope labels through the same locale display-name resolver used by the author editor:
  scoped release confirmation now says `French (France)`, and the default Product copy confirmation
  says `every translation language`, while the publish mutation assertion still sends exact raw
  `['fr-FR']` request tags to the API. A disposable Vite-only release-blocker smoke on
  `127.0.0.1:9876`, still without using the reserved `8080` port or starting a backend, rechecked the
  surrounding author release surface on desktop and `390x844` phone layouts with no horizontal
  overflow, primary-path repository/mapping/variant/publish/artifact/snapshot/JSON wording, or local
  preview warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` author release-gating coverage passed after making the primary
  release card honor every visible authoring draft, not only dirty inline translation: checked
  release controls now close when source copy becomes unsaved and the project release card says
  `Save visible changes before releasing approved copy.` before stale saved copy can be checked
  or released. The DEV-only release-blocker seed now keeps source-copy draft state instead of
  discarding apparent edits, and a disposable Vite-only release-blocker smoke on `127.0.0.1:9876`,
  still without using the reserved `8080` port or starting a backend, typed into headline source
  copy and verified the project release card disables `Release approved copy`
  on desktop and `390x844` phone layouts with no horizontal overflow, primary-path repository/
  mapping/variant/publish/artifact/snapshot/JSON wording, or local preview warning/error logs before
  the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author release-action coverage
  passed with 165 tests after removing the remaining technical preflight from the primary release
  path: `Release approved copy` is now the primary enabled author action before any prior readiness
  result, runs the same validated package check behind that click, and opens the existing
  immutable-scope confirmation only when the release can proceed; explicit `Check readiness` stays in
  admin tools instead of the primary author path. The author card now says
  included content items are checked first, draft blockers say `Save ... before releasing approved
  copy.`, and recheck recovery returns authors to `Release approved copy` instead of ordering them
  through readiness jargon. The DEV-only preview now includes `ready-for-release` and
  `release-start` and `release-retry` seeds for direct include plus initial/retry release states
  alongside the dirty-draft release blocker, and disposable Vite-only browser checks on
  `127.0.0.1:9876`, still without using
  the reserved `8080` port or starting a backend, verified the direct ready handoff
  `Include in next release`, primary enabled release action, included-item `Go to release` jump, and
  dirty-copy release gate on desktop and `390x844` phone layouts with no author-visible
  `Check readiness` / `Check release readiness`, horizontal overflow, or local preview warning/error
  logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage now treats any fake
  product preview rail as a regression: source copy stays editable in the main workspace, long copy
  remains in its real editor, and rendered checks on `127.0.0.1:9876` verify no `Copy preview` or
  preview-frame controls appear on desktop or `390x844` phone layouts before the disposable Vite
  server is stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` coverage passed with 159 tests, then
  the full frontend suite passed with 250 tests after removing field/schema creation from the primary
  writer route. `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused
  tests, full tests, and `git diff --check` passed; a disposable Vite-only preview on
  `127.0.0.1:9876`, still without using the reserved `8080` port or starting a backend, verified the
  desktop and `390x844` Product copy workspace keeps `Content items`, `Write the copy`, and `Source
  copy` visible while no author `Add another field`/`Add first field`, field-creation labels, or
  main-path block-shape/mapping/variant/publish/package/snapshot/repository/JSON wording renders, with
  no horizontal overflow or local preview warning/error logs before the disposable Vite server was
  stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after treating the visible multi-field `Jump to
  copy` outline as a regression. Multiple saved fields stay open as the real writing surface, focusing
  a visible sibling source-copy editor switches the active field, and dirty inline translation recovery
  still holds the requested field until save or reset; `source webapp/use_local_npm.sh` followed by
  frontend format, lint fix, `tsc`, focused tests, full tests, and `git diff --check` passed; the
  disposable Vite-only browser check on `127.0.0.1:9876`, still without using the reserved `8080` port
  or starting a backend, verified the desktop and `390x844` Product copy workspace renders no outline
  rail, field-status pill duplicate, horizontal overflow, primary-path repository/mapping/variant/
  publish/package/snapshot/JSON wording, or local preview warning/error logs before the disposable Vite
  server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after collapsing clean saved field placement
  behind the local `Where this copy appears` disclosure. Missing, dirty, conflicted, and recovering field
  context
  still opens inline, while a clean saved field no longer repeats its subtitle as an open placement
  input before translation status; `source webapp/use_local_npm.sh` followed by frontend format, lint
  fix, `tsc`, focused tests, full tests, and `git diff --check` passed; the disposable Vite-only browser
  check on `127.0.0.1:9876`, still without using the reserved `8080` port or starting a backend,
  verified desktop and `390x844` Product copy layouts hide both clean saved placement inputs until the
  unique scoped `Where this copy appears` disclosure opens one, keep the sibling disclosure closed,
  render no
  horizontal overflow or primary-path repository/mapping/variant/publish/package/snapshot/JSON wording,
  and emit no local preview warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after removing the selected editor header's
  duplicate clean `fields saved` counter. The content-item strip keeps the one scannable saved-progress
  signal, while the opened editor header now carries only the selected item name, placement summary,
  details action, and unsaved state when it changes the next action; `source webapp/use_local_npm.sh`
  followed by frontend format, lint fix, `tsc`, focused tests, full tests, and `git diff --check`
  passed; the disposable Vite-only browser check on `127.0.0.1:9876`, still without using the reserved
  `8080` port or starting a backend, verified desktop and `390x844` Product copy layouts render one
  `2/2 fields saved` count in the content-item strip, no editor-header progress class, no horizontal
  overflow or primary-path repository/mapping/variant/publish/package/snapshot/JSON wording, and no
  local preview warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after removing the passive clean `Source saved`
  field-header label. Missing or dirty source copy still surfaces the warning that changes the next
  action, while a clean mapped field now starts at its field name, placement hint, and source-copy
  editor; `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests,
  full tests, and `git diff --check` passed; the disposable Vite-only browser check on
  `127.0.0.1:9876`, still without using the reserved `8080` port or starting a backend, verified
  desktop and `390x844` Product copy layouts render no `Source saved` field-header label, keep source
  copy and translation work visible, render no horizontal overflow or primary-path repository/mapping/
  variant/publish/package/snapshot/JSON wording, and emit no local preview warning/error logs before
  the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after removing the repeated idle translation
  instruction sentence from each saved field. Closed translation rows now keep the locale status and
  `Write`, `Review`, or `Open` action only, while the longer workflow instruction appears once after the
  author opens the matching translation editor; `source webapp/use_local_npm.sh` followed by frontend
  format, lint fix, `tsc`, focused tests, full tests, and `git diff --check` passed; the disposable
  Vite-only browser check on `127.0.0.1:9876`, still without using the reserved `8080` port or starting
  a backend, verified desktop and `390x844` Product copy layouts render no idle translation instruction
  paragraph, keep the status/action row visible, show the full instruction once in the opened editor,
  render no horizontal overflow or primary-path repository/mapping/variant/publish/package/snapshot/
  JSON wording, and emit no local preview warning/error logs before the disposable Vite server was
  stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after reducing primary source-copy editors from the
  legacy tall copy minimum to a two-line auto-growing floor. `AutoTextarea` now exposes that minimum as
  the native `rows` value before measurement, short headline and CTA source copy no longer create an
  empty form wall, and longer copy stays in the same real editor as it grows; `source
  webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests, full tests, and
  `git diff --check` passed; the disposable Vite-only browser check on `127.0.0.1:9876`, still without
  using the reserved `8080` port or starting a backend, verified desktop and `390x844` Product copy
  layouts render both seeded source-copy inputs with `rows="2"` at `58px`, serve the source-copy
  textarea floor without the legacy `11rem` minimum, render no horizontal overflow or primary-path
  repository/mapping/variant/publish/package/snapshot/JSON wording, and emit no local preview
  warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after shrinking primary translator notes to a
  one-line auto-growing floor. Required localization context still stays beside each source-copy field,
  but the saved authoring route no longer gives those notes the generic `80px` textarea minimum that
  made them read like a second copy editor; `source webapp/use_local_npm.sh` followed by frontend
  format, lint fix, `tsc`, focused tests, full tests, and `git diff --check` passed; the disposable
  Vite-only browser check on `127.0.0.1:9876`, still without using the reserved `8080` port or starting
  a backend, verified desktop Product copy renders both seeded translator notes with `rows="1"` at
  `34px`, `390x844` keeps those notes one-row editors that auto-grow only when wrapped, source copy
  remains visually taller, no horizontal overflow or primary-path repository/mapping/variant/publish/
  package/snapshot/JSON wording appears, and no local preview warning/error logs appear before the
  disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after removing per-field `Field n of m` ordinals
  from the open author form. The content-item strip still carries saved-field progress for scanning, but
  each editable field now starts at its real name and placement hint instead of reopening setup framing;
  `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests, full
  tests, and `git diff --check` passed; the disposable Vite-only browser check on `127.0.0.1:9876`,
  without using or starting a backend on `8080`, verified desktop and `390x844` Product copy layouts
  render no `Field 1 of 2` or `Field 2 of 2` author-path ordinal, keep source copy, translator note, and
  inline translation status visible, render no horizontal overflow or primary-path repository/mapping/
  variant/publish/package/snapshot/JSON wording, and emit no local preview warning/error logs before the
  disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after shortening closed inline translation buttons
  to the visible `Write`, `Review`, or `Open` verb. The adjacent status line still names the locale and
  next need, while the full locale-specific action stays in the button accessible label and opened editor
  heading; `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests,
  full tests, and `git diff --check` passed; the disposable Vite-only browser check on
  `127.0.0.1:9876`, without using or starting a backend on `8080`, verified desktop and `390x844`
  Product copy layouts render visible `Write` buttons with `Write French (France) translation`
  accessible labels, render no repeated visible `Write French (France) translation` phrase, keep source
  copy, translator note, and inline translation status visible, render no horizontal overflow or
  primary-path repository/mapping/variant/publish/package/snapshot/JSON wording, and emit no local
  preview warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after renaming the visible field placement flow
  from `Copy context` to `Where this copy appears` / `placement details`. The author path now matches
  the existing item-level `Where this item appears` language, while field-context domain names stay
  technical underneath; `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`,
  focused tests, full tests, and `git diff --check` passed; the disposable Vite-only browser check on
  `127.0.0.1:9876`, without using or starting a backend on `8080`, verified desktop and `390x844`
  Product copy layouts render no visible `Copy context`, render two `Where this copy appears`
  disclosures, open one as a non-duplicated `Placement details` input with `Headline placement
  details` accessible naming, keep source copy, translator note, and inline translation status visible,
  render no horizontal overflow or primary-path repository/mapping/variant/publish/package/snapshot/
  JSON wording, and emit no local preview warning/error logs before the disposable Vite server was
  stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after hiding the saved content-item strip when one
  content item is already open. A single-item copy collection now lands directly on the editor while the
  source-empty signal moves into the editor header; clean saved-count progress now stays out of that
  single-item first viewport, while zero-item recovery and multi-item collections still keep the strip
  for creation or switching; `source webapp/use_local_npm.sh` followed by frontend format, lint fix,
  `tsc`, focused tests, full tests, and `git diff --check` passed; the disposable Vite-only browser
  check on `127.0.0.1:9876`, without using or starting a backend on `8080`, verified desktop and
  `390x844` Product copy layouts render no single-item content item navigation or open-item button, keep
  source copy, translator note, and inline translation status visible, render no horizontal overflow or
  primary-path repository/mapping/variant/publish/package/snapshot/JSON wording, and emit no local
  preview warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after removing the now-detached single-item
  `Content items` management band above the open editor. Single-item copy collections now start on the
  content item name and source fields while keeping `Write new content item` visible and item details
  inside editor-header `Item details`;
  real multi-item collections still keep the content-item chooser; `source webapp/use_local_npm.sh`
  followed by frontend format, lint fix, `tsc`, focused tests, full tests, and `git diff --check`
  passed; the disposable Vite-only browser check on `127.0.0.1:9876`, without using or starting a
  backend on `8080`, verified desktop and `390x844` Product copy layouts render no single-item
  `Content items` heading or navigation strip, keep the editor-header `Item details` disclosure,
  keep clean saved-count progress out of that first viewport, keep source copy, translator note, and
  inline translation status visible, render no horizontal overflow or primary-path repository/mapping/
  variant/publish/package/snapshot/JSON wording, and emit no local preview warning/error logs before the
  disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after removing the false batch-save prompt from
  dirty inline translation. Source-copy, translator-note, item-detail, and placement drafts still get the
  sticky `Ready to save` / `Save copy changes` bar, while dirty locale translation keeps only its real
  `Save this translation` controls and still blocks navigation/release until resolved;
  `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests, full
  tests, and `git diff --check` passed; the disposable Vite-only browser check on `127.0.0.1:9876`,
  without using or starting a backend on `8080`, verified desktop and `390x844` dirty source-copy
  layouts render `Ready to save` with no `Save before leaving`, dirty inline translation renders no
  misleading `Save copy changes` button while its locale save controls remain visible, render no
  horizontal overflow or primary-path repository/mapping/variant/publish/package/snapshot/JSON wording,
  and emit no local preview warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 158 tests,
  then the full frontend suite passed with 249 tests after replacing the below-editor narrow-screen
  copy-collection rail with one collapsed `Copy collections` switcher above the editor. The same rail
  content now opens in place on phone widths instead of duplicating search and collection controls;
  `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests, full
  tests, and `git diff --check` passed; the disposable Vite-only browser check on `127.0.0.1:9876`,
  without using or starting a backend on `8080`, verified `390x844` keeps the collapsed switcher in
  the first viewport, opens it to the current collection plus `New copy collection`, keeps desktop on
  the full rail, renders no horizontal overflow or primary-path repository/mapping/variant/publish/
  package/snapshot/JSON wording, and emits no local preview warning/error logs before the disposable
  Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 175 tests,
  then the full frontend suite passed with 269 tests after disabling clean-start, first-item recovery,
  and later-item primary actions until the visible required authoring fields are filled. The authoring
  form keeps those primary actions disabled instead of showing an enabled save action or a validation
  footer while required source copy and translator notes are blank;
  `source webapp/use_local_npm.sh` followed by frontend format, lint fix, `tsc`, focused tests, full
  tests, and `git diff --check` passed; the disposable Vite-only browser check on `127.0.0.1:9876`,
  without using or starting a backend on `8080`, verified the DEV later-item form starts with disabled
  `Save content item` plus visible author-language intro and required field labels, renders no horizontal overflow or primary-path
  repository/mapping/variant/publish/package/snapshot/JSON wording, and emits no local preview
  warning/error logs before the disposable Vite server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed with 160 tests
  after keeping untouched optional copy dormant in the saved editor. Blank optional copy now shows
  `Optional copy can wait`, says `Add when writing this copy` on the untouched translator note, hides
  translation handoff plus field-level save chrome until writing starts, and leaves its dormant inputs
  out of native required state; once an author starts that optional copy, its inputs become natively
  required and its source save stays disabled until both source copy and translator note are present.
  Frontend format, lint fix, `tsc`, the focused CMS/preview run with `--fileParallelism=false`, the
  standard full frontend suite with 251 tests, and `git diff --check` passed after the final
  phone-width follow-up. The same focused file set hit timeout-only failures under Vitest's default
  parallel scheduling in this slow desktop session before the serial focused rerun returned a
  trustworthy result. The disposable Vite-only browser check on `127.0.0.1:9876`,
  without using or starting a backend on `8080`, verified the DEV optional-copy preview on desktop and
  `390x1200` renders no missing-required or translation-handoff status, no untouched optional save action,
  no native `required` attribute on dormant optional inputs, no primary-path
  repository/mapping/variant/publish/package/snapshot/JSON wording, and stacks the phone header,
  translator-note, and translation-readiness rows instead of clipping them before the disposable Vite
  server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed after blocking
  the item-level save bar for half-written generated source copy. A started optional copy with source
  text but no translator note now says `Finish source copy` and keeps both `Save source copy` and
  `Save copy changes` disabled instead of claiming the content item is ready; once translator context
  is present, the same item-level bar returns to `Ready to save`. Frontend format, lint fix, `tsc`,
  the focused CMS/preview run with `--fileParallelism=false` and 160 tests, the standard full frontend
  suite with 251 tests, and `git diff --check` passed before the disposable preview verification.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed after blocking
  dirty content-item details with a blank required name. Clearing that name now marks the open details
  input invalid, says `Name this content item before saving.`, disables both `Save copy details` and
  `Save copy changes`, and returns the item-level bar to `Ready to save` only after the author restores
  a name. Frontend format, lint fix, `tsc`, the focused CMS/preview run with
  `--fileParallelism=false` and 162 tests, the standard full frontend suite with 253 tests, and
  `git diff --check` passed before the disposable preview verification.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed after replacing
  the never-started missing-translation dead save row with a real writing step. Empty target copy now
  says `Start this translation`, keeps the disabled primary save group out of the way, keeps the
  visible `Write French (France) translation` task phrase singular inside the opened editor, and
  still leaves exclusion behind `Other actions`; once the author types target copy, `Save this
  translation` restores draft, review, and release actions. Frontend format, lint fix, `tsc`, the
  focused CMS/preview run with `--fileParallelism=false` and 163 tests, the standard full frontend
  suite with 254 tests, `git diff --check`, and disposable `127.0.0.1:9876` preview verification
  passed before the preview server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` author coverage passed after adding a
  DEV-only clean-start preview that renders the shared first-writing form instead of a fake demo shell.
  The focused CMS/preview run with `--fileParallelism=false` passed with 175 tests, the standard full
  frontend suite passed with 266 tests, and disposable `127.0.0.1:9876` desktop plus `390x1200`
  browser checks verified the preview starts with no copy-collection rail, hides primary-path
  repository/mapping/variant/JSON wording, fills the real welcome-email example, enables `Start
  writing`, renders no horizontal overflow, and emits no local preview warning/error logs before the
  preview server was stopped.
- Focused `ContentCmsAuthoringPreview` coverage passed after removing generic placement instruction
  from clean field headers. A DEV-only blank-placement preview now proves that a field with no saved
  placement shows its name and real source-copy controls without repeating `Add placement details if
  translators need context.`; its open `Where this copy appears` form remains the place to add that
  optional translator context. The large
  `AdminContentCmsPage` spec now carries a local 15-second Vitest timeout, matching its existing
  slow-test allowances so desktop validation does not fail randomly on unrelated five-second
  user-event timeouts while this authoring surface keeps growing; the matching authoring preview spec
  uses the same local timeout, and the one explicit 10-second CMS validation case now aligns with that
  file-level allowance instead of undercutting it.
- Focused author-route coverage passed after removing the last visible `Open admin tools` wording from
  normal writing. The settings directory now groups the normal route under author-facing `Copy
  authoring`, describes the Product copy entry as writing, translation, and release instead of a
  pre-workspace Mojito handoff, leaves the dedicated `Admin tools` route to direct/deep-link or blocked
  local repair rather than presenting it as a peer settings card, healthy authoring no longer ends
  with a detached setup footer, rare zero-field/reused-link/repair states expose one local repair action
  instead of reopening operator-console language, and the dedicated `/settings/system/content-cms/admin`
  route keeps its explicit admin framing once the author intentionally leaves the writing path.
- Focused settings-directory coverage now scopes the failed-console vocabulary guard to the `Copy
  authoring` region itself, so Product copy can stay author-facing even while unrelated admin settings
  elsewhere on `/settings/system` legitimately keep Mojito, JSON, repository, and automation wording.
- The settings directory now puts that guarded `Copy authoring` region first, so the normal Product
  copy entry is discoverable before unrelated admin automation, reporting, glossary, user-management,
  and temporary-tool cards.
- Focused app-shell coverage now clicks that visible Product copy settings entry and proves it opens
  the normal `/settings/system/content-cms` author route rather than the deep-link-only admin tools
  route.
- Focused app-shell loading coverage now keeps the lazy Product copy fallback on the same author route
  framing, with `Back to settings`, `Product copy`, and `Loading Product copy...` visible inside the
  named Product copy editor region before the workspace module resolves and no failed-console
  vocabulary leaking through that first transition; the app shell imports the Product copy route CSS
  before that lazy module resolves so this first author-facing paint is framed rather than unstyled.
- Focused deterministic preview coverage now keeps the app shell as the page's only `main` landmark;
  the inner Product copy editor is a named region instead of a nested second `main`, so assistive tech
  does not hear the normal writing surface as competing page chrome.
- Focused real-route loading and retry coverage now keeps selected copy collection opening failures
  inside that named author editor as flat inline states instead of generic settings cards, so a slow or
  failed collection fetch does not briefly put the primary path back in failed-console framing.
- A DEV-only deterministic `?cmsPreview=settings-directory` route now renders that settings directory
  with a preview admin user, so browser QA can verify the author-facing Product copy entry even when
  the available live browser session is not role-backed for `/settings/system`; real routes still keep
  their normal auth and role gates.
- Focused author repair-label coverage passed after replacing the remaining visible `Open advanced
  setup` escape hatches with `Repair content item` or `Repair source link`, while removed-language
  recovery now uses local `Add <language>` authoring instead of setup vocabulary: broken authoring
  still reaches the same scoped admin repair where needed, but a removed target language stays in
  the writing path.
- Focused optional translation-tools coverage passed after replacing the remaining visible Mojito
  handoff inside the author drawer with `Open detailed translation review`: the optional destination
  still carries the exact saved translation and return path, but normal product-copy language no
  longer names backend plumbing even after the author opens the extra tools.
- Focused translation-readiness failure coverage passed after replacing the remaining author-field
  error that named Mojito directly with `Translation status could not load for this copy.`: retry
  stays inline beside the affected copy field, while the blocked writing path keeps the failure in
  author language instead of exposing service plumbing.
- Focused translation-loading coverage now keeps the matching passive field row at `Checking
  translation status`, so inline Mojito readiness describes status lookup instead of implying missing
  translation copy before the author opens a language.
- Focused translation-language setup coverage now keeps the loading helper and empty-selection guard
  on `translation languages` / `language choices` instead of reopening visible `locale` vocabulary in
  Product copy.
- Focused author release-recovery coverage now keeps the remaining retry and stale-check fallbacks at
  `this release` / `release details` instead of `release settings`, so failed author handoffs ask for
  help without reopening hidden publish configuration language.
- Focused clean-start detail coverage passed after replacing the pristine first-screen `More options`
  branch with direct `Use welcome email starter` beside the primary action plus lower `Add details`:
  the safe walkthrough is discoverable without opening an ambiguous menu, while optional placement
  and collection description remain secondary until the author asks for them.
- Focused saved-item header coverage passed after replacing the remaining saved-item `More options`
  branch with visible `Write new content item` plus `Item details`: starting the next item stays a
  direct author action when the single-item chooser is hidden, while rename and placement remain
  secondary until the author opens the named details disclosure.
- Focused source-only release coverage passed after fixing the author release guard to match the
  design note: saved source copy with no translation languages now stays on the inline language picker
  instead of showing a disabled release card, while a ready selected item or another included item can
  still surface release when it changes the author's next action.
- Focused later-item authoring coverage passed after removing setup-style localization wording from
  the new content item intro. Clean start and later-item creation now ask for source copy plus
  translator notes, while the visible required save hint still explains exactly what must be filled
  before the item can be saved.
- Focused empty-workspace authoring coverage passed after removing the remaining pipeline wording from
  zero-item recovery. After an author cancels the first unsaved content item, the workspace now says to
  write the first content item in the source language and start writing source copy instead of saying
  unfinished copy will be sent to translation or asking the author to add an item.
- Focused zero-field new-item coverage passed after removing the remaining impossible writer action from
  that rare recovery state. Starting another content item from a saved zero-field copy collection now
  offers a local `Repair content item` recovery action instead of telling the author to add a field
  from a route that intentionally has no field-creation control.
- Focused multi-item chooser coverage passed after removing the remaining placement-as-copy fallback.
  Content-item rows now preview saved source copy or say `No source copy yet`, keeping optional item
  placement in the selected header/details instead of making the chooser rail look like a fake preview.
- Focused required-source coverage passed after removing the remaining premature translation strip from
  an unsaved required field. Before source copy exists, the field card now keeps the author on `Needs
  source copy`; inline translation status appears only after saved source can actually be translated.
- Focused draft-release coverage passed after removing the remaining premature release action from a
  saved draft while translation readiness is still loading. Draft content items now wait for known,
  ready translation state before showing `Include in next release`; already included or archived items keep
  their release state visible because that state still changes the author’s next action.
- Focused item-placement coverage passed after removing the remaining clean optional-placement prompt
  from the saved item header. A saved content item without placement now lands on its copy instead of
  `Add where this item appears.`; saved-item `Item details` exposes `Content item details` with the
  empty `Where this item appears` field when the author intentionally opens it.
- Focused new-item placement coverage passed after removing the remaining clean optional-placement
  input from the unsaved writer. A new content item now lands on its name plus required source copy;
  `Content item details` exposes `Where this item appears` when the author intentionally opens it, and
  example/recovered drafts reopen those details when they already carry placement.
- Focused blocked-copy coverage passed after removing the normal `Write the copy` heading from rare
  saved-item repair states. A missing experiment path or zero-field saved content item now shows the
  inline `Repair content item` recovery without pretending writable source copy exists.
- Focused mobile-navigation coverage passed after replacing the collapsed `Copy collections +`
  pseudo-label with an actual disclosure chevron. The phone-size switcher now keeps the accessible
  name `Copy collections` while the visual marker reads as expand/collapse instead of add.
- Browser author-path overflow audit passed after reserving the rotated disclosure-chevron tip inside
  clean saved-item `Item details` and phone-size `Copy collections` controls. The default saved author
  preview no longer grows its single-item workspace by two pixels at desktop width, and the phone
  switcher keeps the same visual chevron without an internally scrolling control.
- Focused field-placement coverage passed after closing the remaining clean empty optional-placement
  form in the saved field path. A saved field without placement now lands on source copy instead of an
  empty `Placement details` input; `Where this copy appears` still exposes that field when the author
  intentionally opens it.
- Demo artifact review found `dev-docs/demos/content-cms-authoring-demo.mp4` and its three source
  frames render the current author-first Product copy workspace, while the unreferenced
  `dev-docs/demos/cms-ui-demo*` artifact still showed the discarded failed prototype; the stale demo
  video and frames were removed so the repo exposes only the current walkthrough.
- Focused `CmsContentServiceIntegrationTest#createsEntryWithInitialGeneratedFieldMappings` and `CmsContentWSTest#getEntryCompletenessSerializesFieldReadiness` passed with 2 tests, 0 failures/errors after adding field-scoped completeness rows beside the existing block-level completeness totals, so authoring can show per-piece target readiness without inventing it from whole-block counts.
- Focused `CmsContentServiceIntegrationTest#addsTargetLocalesInsideCmsWritingSpace`, `CmsContentWSTest`, and `CmsContentRequestBodyAdviceTest` passed with 69 tests, 0 failures/errors after keeping source-only authors in the CMS with an additive Mojito-backed target-locale setup action that validates locale tags without replacing unrelated repository settings.
- Focused `CmsContentServiceTest#createProjectRejectsDuplicateDedicatedRepositoryBeforeCreatingAsset` and `CmsContentServiceIntegrationTest#createsDedicatedRepositoryWhenProjectRepositoryIsNotSelected` passed with 2 tests, 0 failures/errors after moving blank project repository selection onto a same-transaction dedicated visible Mojito repository path while keeping explicit existing-repository reuse validated.
- Focused `CmsContentServiceIntegrationTest#createsFirstCopyBlockAtomically`, `CmsContentServiceIntegrationTest#rollsBackFirstCopyBlockWhenInitialSourceFails`, `CmsContentRequestBodyAdviceTest`, and `CmsContentWSTest` passed with 67 tests, 0 failures/errors after moving clean-start block shape, default `Copy` field, first entry, and initial generated Mojito source handoff onto one authoring endpoint with rollback proof for missing initial translator context.
- Focused `CmsContentServiceIntegrationTest#makesOneSharedBlockCopyPiecesPrivateWithoutLosingMojitoSources`, `CmsContentRequestBodyAdviceTest`, and `CmsContentWSTest` passed with 67 tests, 0 failures/errors after adding the selected-block make-pieces-private transaction, preserving the block's Mojito text-unit/string-ID bindings while cloning only its hidden copy-piece shape before admin-only local field creation.
- Focused `CmsContentServiceIntegrationTest#createsEntryWithInitialGeneratedFieldMappings` and `CmsContentServiceIntegrationTest#rollsBackEntryWhenInitialFieldMappingFails` passed with 2 tests, 0 failures/errors after moving primary new-block source handoff onto one create-entry transaction with rollback proof for a later invalid initial mapping.
- Focused `CmsContentServiceIntegrationTest#createsContentTypeFieldWithInitialGeneratedFieldSource` and `CmsContentServiceIntegrationTest#rollsBackContentTypeFieldWhenInitialFieldSourceFails` passed with 2 tests, 0 failures/errors after keeping admin field-create source handoff atomic with rollback proof for missing initial translator context.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DatabaseCacheTest test` passed with 10 tests, 0 failures/errors, and 1 expected skip after aligning the legacy MySQL `CHAR(32)` cache-key mapping used by the disposable schema probe.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=FlywayMigrationVersionTest,CmsContentServiceTest,CmsContentServiceIntegrationTest,CmsContentWSTest,CmsContentRequestBodyAdviceTest,CmsContentSecurityWSTest,CmsSnapshotSigningServiceTest,CmsManagedVirtualAssetGuardTest test` passed with 381 tests, 0 failures/errors, and 15 expected MySQL-only skips after the current CMS hardening, retained history recent-artifact revalidation with a server-bounded recent window, recent retained artifact integrity fencing before package validation or publish, impossible zero-byte retained history metadata and schema rejection proof, CMS error/security no-store response hardening, nested CMS delivery API `401` regression coverage, mutable authoring write no-store response hardening, singleton publish identity header rejection proof, whole-project authoring detail byte-budget enforcement plus rollback proof, complete publish package artifact validation, project package validation retained-history fencing, active candidate-group ambiguity rejection across authoring and retained artifacts, publishable/runtime Mojito string-ID uniqueness fencing across authoring and retained artifacts, delivery artifact HMAC mismatch proof, exact canonical delivery artifact locator plus percent-encoded and query-string delivery alias rejection proof, malformed weak wildcard, wildcard-list, entity-tag-list, and weak-tag whitespace cache alias rejection proof, exact ambiguous TM text-unit mapping plus authoring fence proof, cross-asset exact TM text-unit binding rejection proof, malformed stored publish-intent metadata rejection proof, bounded publish operational log leakage proof, undeclared runtime field and unpublished runtime locale artifact rejection proof, CMS backing-asset live-marker schema coverage, CMS-managed virtual asset mutation guard coverage, unused CMS snapshot index cleanup, and redundant content left-prefix index cleanup.
- Focused `CmsContentServiceIntegrationTest#schemaKeepsCmsMvpCheckConstraints` and `CmsContentServiceIntegrationTest#schemaRejectsDeletedProjectAssetMarker` passed with 2 tests, 0 failures/errors, and 1 expected MySQL-only skip on HSQL, keeping `CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED` in the direct-SQL guard inventory while exercising the denormalized backing-asset deleted-marker rejection path available under the local test profile.
- Focused `CmsContentServiceIntegrationTest#schemaKeepsCmsMvpCheckConstraints`, `CmsContentServiceIntegrationTest#schemaRejectsDeletedProjectAssetMarker`, and `CmsContentServiceIntegrationTest#schemaRejectsCmsProjectAssetBecomingDeleted` passed with 3 tests, 0 failures/errors against a disposable MySQL 8.0.34 Docker database using `-Pno-local-config`, explicit disposable datasource/Flyway overrides, `l10n.flyway.clean=false`, `spring.flyway.clean-disabled=true`, and `spring.jpa.hibernate.ddl-auto=validate`; the realized MySQL schema retained `CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED` and rejected both denormalized project-marker drift and backing-asset delete drift without reading or cleaning the configured local Mojito database.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsManagedVirtualAssetGuardTest,AssetMappingServiceGuardTest,AssetWSTest,CmsContentSecurityWSTest test` passed with 11 tests, 0 failures/errors, proving CMS-owned virtual assets reject generic asset/extraction mutation paths outside the scoped CMS source-registration callback while the delivery role remains limited to the read-only descriptor/artifact boundary.
- Focused `CmsContentServiceTest#getProjectRejectsAuthoringDetailAboveConfiguredByteLimit` and `CmsContentServiceTest#getProjectRejectsNonPositiveConfiguredAuthoringDetailByteLimit` passed with 2 tests, 0 failures/errors, proving ordinary whole-project admin detail fails closed when draft or archived authoring state exceeds its configured response budget or that budget is misconfigured.
- Focused `CmsContentServiceIntegrationTest#createContentTypeRollsBackWhenAuthoringDetailExceedsConfiguredByteLimit` passed with 1 test, 0 failures/errors, proving an app-mediated authoring write that would return an oversized whole-project detail rolls back before persisting the new authoring row.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentServiceIntegrationTest#schemaRejectsDeletingProjectWithPublishedSnapshot test` passed with 1 test, 0 failures/errors, proving retained published snapshots block hard CMS project deletion through `FK__CMS_PUBLISH_SNAPSHOT__PROJECT`.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentServiceIntegrationTest#deletedSnapshotPublisherKeepsPublishTimeUsernameInHistory test` passed with 1 test, 0 failures/errors, proving logical publisher deletion does not rewrite retained CMS snapshot history.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentServiceTest#publishProjectRejectsApprovedButExcludedRequestedLocale test` passed with 1 test, 0 failures/errors, proving an approved Mojito target still cannot publish when it is excluded from the localized-file state used by runtime artifacts.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentServiceTest#publishProjectRejectsReviewNeededOptionalMappedFieldRequestedLocale test` passed with 1 test, 0 failures/errors, proving an optional field joins block completeness and publish gating once an admin maps copy into it.
- Focused `CmsContentServiceTest#getProjectCompletenessCanonicalizesExplicitLocaleSelectionOrderForPackageIdentity` passed with 1 test, 0 failures/errors, proving equivalent explicit locale selections normalize before deriving the provider-neutral publish package SHA and byte count used by idempotent publish preflight.
- Focused `CmsContentServiceTest#createVariantRejectsCandidateInDifferentActiveCandidateGroup`, `CmsContentServiceTest#updateVariantRejectsCandidateInDifferentActiveCandidateGroup`, `CmsContentServiceTest#getEntryCompletenessRejectsMultipleActiveCandidateGroups`, and `CmsContentServiceTest#publishProjectRejectsReadyEntryWithMultipleActiveCandidateGroups` passed with 4 tests, 0 failures/errors, proving whole-copy entry variants cannot carry multiple active candidate groups through authoring, completeness preflight, or publish.
- Focused `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithMultipleCandidateGroupKeys` passed with 1 test, 0 failures/errors, proving exact retained artifact export rejects a signed/drifted whole-copy entry snapshot whose active candidates span multiple candidate groups.
- Focused `CmsContentServiceTest#getProjectCompletenessRejectsDuplicatePublishableStringIds` and `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithDuplicateRuntimeStringIds` passed with 2 tests, 0 failures/errors, proving exact TM-ID fallback cannot publish or export two runtime fields under one Mojito string identity.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentWSTest#publishProjectPassesRequiredPublishRequestKey+publishProjectResponseUsesSignedPublishedAtField,CmsContentServiceTest#publishProjectReturnsStoredSnapshotForMatchingPublishRequestKey+publishProjectRejectsReusedPublishRequestKeyForAnotherLocaleScope+publishProjectReturnsReusedDefaultPublishRequestKeyAfterLocaleConfigChanges+publishProjectRejectsReusedPublishRequestKeyForAnotherAuthoringRevision,CmsContentServiceIntegrationTest#publishProjectLockSerializesSameProjectSnapshotVersionAllocation test` passed with 7 tests, 0 failures/errors, and the one expected MySQL-only lock assertion skipped on HSQL while the disposable MySQL proof is recorded below.
- Focused `CmsContentServiceTest#getLatestPublishedSnapshotDescriptorLoadsVerifiedSnapshotByCanonicalProjectKey` and `CmsContentWSTest#getLatestPublishedSnapshotSerializesProviderNeutralDescriptor` passed with 2 tests, 0 failures/errors, proving the provider-neutral latest descriptor preserves immutable delivery identity through the service and REST boundary: canonical project key, snapshot version, artifact SHA-256, UTF-8 byte size, artifact filename/export path, and descriptor/artifact signatures.
- Focused `CmsContentWSTest#getVersionedSnapshotArtifactReturnsSha256Etag`, `CmsContentWSTest#getVersionedSnapshotArtifactReturnsNotModifiedForMatchingIfNoneMatch`, `CmsContentWSTest#getVersionedSnapshotArtifactReturnsNotModifiedForWildcardIfNoneMatch`, `CmsContentWSTest#getVersionedSnapshotArtifactIgnoresMalformedWeakWildcardIfNoneMatch`, `CmsContentWSTest#getVersionedSnapshotArtifactIgnoresMalformedWildcardListIfNoneMatch`, `CmsContentWSTest#getVersionedSnapshotArtifactIgnoresMalformedEntityTagListIfNoneMatch`, `CmsContentWSTest#getVersionedSnapshotArtifactRejectsPercentEncodedSnapshotVersionLocator`, `CmsContentWSTest#getVersionedSnapshotArtifactRejectsQueryStringAlias`, `CmsContentWSTest#getVersionedSnapshotArtifactHeadReturnsDeliveryHeaders`, `CmsContentWSTest#getVersionedSnapshotArtifactHeadReturnsNotModifiedForMatchingIfNoneMatch`, `CmsContentWSTest#exactArtifactResponseOverridesDefaultCmsNoStoreCacheControl`, `CmsContentWSTest#getLatestPublishedSnapshotUsesStableProjectKeyLookup`, `CmsContentWSTest#getLatestPublishedSnapshotRejectsPercentEncodedProjectKeyLocator`, `CmsContentWSTest#getLatestPublishedSnapshotRejectsQueryStringAlias`, `CmsContentWSTest#getLatestPublishedSnapshotReturnsNotModifiedForMatchingIfNoneMatch`, `CmsContentWSTest#getLatestPublishedSnapshotReturnsNotModifiedForWildcardIfNoneMatch`, `CmsContentWSTest#getLatestPublishedSnapshotIgnoresMalformedWeakWildcardIfNoneMatch`, `CmsContentWSTest#getLatestPublishedSnapshotIgnoresMalformedWildcardListIfNoneMatch`, `CmsContentWSTest#getLatestPublishedSnapshotIgnoresMalformedEntityTagListIfNoneMatch`, `CmsContentWSTest#getLatestPublishedSnapshotIgnoresMalformedWeakEtagWhitespaceIfNoneMatch`, `CmsContentWSTest#getLatestPublishedSnapshotHeadReturnsDescriptorHeaders`, and `CmsContentWSTest#getLatestPublishedSnapshotHeadReturnsNotModifiedForMatchingIfNoneMatch` passed with 22 tests, 0 failures/errors, proving exact version-addressable artifacts keep SHA-keyed private immutable cache metadata while the mutable latest descriptor stays `no-store` with its own snapshot-signature `ETag`; canonical delivery requests reject percent-encoded unreserved path aliases and unused query-string aliases before service lookup, valid wildcard/entity-tag validators still return `304`, malformed weak wildcards, invalid wildcard lists, malformed entity-tag lists, and weak-tag whitespace do not alias valid validators, and the delivery contract exposes only the non-secret snapshot signing key ID, not HMAC secret material.
- Focused `CmsContentServiceTest#createContentTypeFieldRejectsRequiredFieldThatInvalidatesReadyEntry`, `CmsContentServiceTest#updateContentTypeFieldRejectsIcuTypeWhenReadyMappingTargetIsInvalid`, `CmsContentServiceTest#updateContentTypeRejectsSchemaThatInvalidatesExistingEntryMetadata`, and `CmsContentServiceTest#unmapFieldMappingRejectsRemovingLastFieldFromReadyEntry` passed with 4 tests, 0 failures/errors, proving MVP schema mutation stays fail-closed without delete semantics: required-field creation, ICU field-type promotion, metadata-schema tightening, and explicit field unmapping cannot leave a `READY` entry structurally publishable but invalid.
- Focused `CmsContentServiceTest#createEntryRejectsContentTypeFromAnotherProjectBeforeSaving` and `CmsContentServiceTest#upsertFieldMappingRejectsFieldFromAnotherContentTypeBeforeSaving` passed with 2 tests, 0 failures/errors, proving app-mediated authoring rejects cross-project content-type and cross-type field selectors before relying on the migration's composite FK guards.
- Focused `CmsContentServiceIntegrationTest#sourceEditRequiresReapprovalBeforePublish,CmsContentServiceIntegrationTest#translatorContextEditRequiresReapprovalBeforePublish` passed with 2 tests, 0 failures/errors, proving generated CMS source-copy or translator-context edits keep the stable Mojito string ID while remapping to a new TM text unit version; prior approved target copy is only leveraged as `TRANSLATION_NEEDED`, completeness reopens, and publish stays blocked until the edited block is reapproved.
- Focused `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestKey` and `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestLocaleTags` passed with 2 tests, 0 failures/errors, proving exact artifact export rejects malformed stored idempotency metadata before retry/history/delivery code can treat a drifted retained snapshot as valid.
- Focused `CmsContentServiceTest#publishProjectLogsBoundedOperationalMetadataOnly` passed with 1 test, 0 failures/errors, proving successful publish info logs keep only project/version/locale/artifact digest/byte-count/publisher metadata while captured output excludes the idempotency key, snapshot/artifact signatures, HMAC key material, source copy, and translated values.
- Focused `CmsContentServiceTest#publishProjectRejectsSnapshotWatermarkAdvanceConflict`, `CmsContentServiceTest#getProjectCompletenessRejectsIncompleteSnapshotHistoryBeforeReturningPackage`, `CmsContentServiceTest#getProjectCompletenessRejectsInvalidRecentSnapshotBeforeReturningPackage`, `CmsContentServiceTest#publishProjectRejectsInvalidRecentSnapshotBeforeCreatingSnapshot`, `CmsContentServiceTest#publishProjectRejectsReusedPublishRequestKeyForAnotherValidatedPackage`, `CmsContentServiceTest#publishProjectLogsBoundedOperationalMetadataOnly`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotInvalidDeliveryEnvelope`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotCompletenessMetadataMismatch`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestKey`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestLocaleTags`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithUndeclaredRuntimeField`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithUnpublishedRuntimeFieldLocaleValue`, `CmsContentServiceTest#getProjectCompletenessRejectsDuplicatePublishableStringIds`, `CmsContentServiceTest#getSnapshotArtifactRejectsStoredSnapshotWithDuplicateRuntimeStringIds`, `CmsContentServiceTest#upsertFieldMappingMapsExactTmTextUnitIdWhenStringIdIsAmbiguous`, `CmsContentServiceTest#upsertFieldMappingRejectsMappedTextUnitFromAnotherCmsAsset`, `CmsContentServiceTest#getProjectCompletenessFencesExactTmBindingWithSameRuntimePackage`, `CmsContentServiceTest#getLatestPublishedSnapshotDescriptorRejectsStoredSnapshotInvalidDeliveryEnvelope`, `CmsContentServiceTest#getLatestPublishedSnapshotDescriptorRejectsStoredSnapshotCompletenessMetadataMismatch`, `CmsContentServiceTest#getProjectPublishSnapshotsRejectsZeroStoredArtifactByteSize`, `CmsContentServiceTest#getProjectPublishSnapshotsValidatesRecentArtifactsBeforeLoadingOlderMetadata`, and `CmsContentServiceTest#getProjectPublishSnapshotsDoesNotLetSmallPageShrinkRecentValidationWindow` runs each passed with 1 test, 0 failures/errors; the follow-up `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentServiceTest test` passed with 224 tests, 0 failures/errors. These checks prove publish fails closed if the non-authoring snapshot watermark cannot advance, package validation does not return a reusable publish digest while retained snapshot history is incomplete, recent retained artifact HMAC drift cannot return a package digest or append another snapshot, reused publish request keys cannot point at another validated package, successful publish logs keep only bounded operational metadata, malformed stored idempotency metadata cannot pass exact artifact validation, exact `tmTextUnitId` selection preserves an intentional ambiguous active Mojito string-ID fallback while still fencing a same-byte exact binding change through the authoring SHA, exact `tmTextUnitId` selection cannot cross CMS asset boundaries, publishable and retained runtime field mappings cannot alias one Mojito string ID across package fields, provider-facing exact artifact or latest-descriptor reads reject stored delivery, completeness, undeclared runtime field, or unpublished runtime locale metadata that drifts from the immutable provider-neutral artifact contract, and retained metadata history reads reject impossible zero-byte artifact metadata while still preventing older cursors plus small page limits from skipping or shrinking bounded recent artifact validation.
- Focused `CmsContentServiceTest#getProjectCompletenessRejectsInvalidArtifactBeforeReturningPackage` and `CmsContentServiceTest#publishProjectRejectsInvalidArtifactBeforeSavingSnapshot` runs each passed with 1 test, 0 failures/errors, proving completeness rejects a false-ready provider-neutral package and publish rejects the same invalid signed artifact before persistence so direct DB drift cannot save or seal a snapshot that later delivery reads would reject.
- Focused `CmsContentWSTest#mutableAdminWritesUseNoStoreCacheControl`, `CmsContentWSTest#publishProjectPassesRequiredPublishRequestKey`, and `CmsContentWSTest#publishProjectRejectsDuplicatePublishRequestKeyBeforeServiceCall` passed with 3 tests, 0 failures/errors, proving full authoring-detail write responses and publish `201 Created` metadata carry `Cache-Control: no-store` while preserving the immutable artifact `Location`, and repeated singleton publish identity headers do not reach service execution.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CmsContentRequestBodyAdviceTest,CmsContentWSTest,CmsContentSecurityWSTest test` passed with 66 tests, 0 failures/errors. These checks prove strict CMS request JSON/body limits plus scalar-coercion rejection, singleton publish identity header rejection, the exact CMS namespace root, canonical immutable delivery artifact locator rejection, percent-encoded and query-string delivery aliases do not bypass canonical immutable locator rejection, malformed weak wildcard, wildcard-list, entity-tag-list, and weak-tag whitespace cache validators do not alias valid validators, unauthenticated nested CMS delivery routes stay on the API `401` path, CMS delivery misses, and scoped CMS security failures do not become heuristically cacheable before publish while exact immutable artifacts keep their SHA-keyed long-lived cache policy.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TMServiceCurrentVariantMutationTest,TMTextUnitCurrentVariantServiceTest,CurrentVariantRollbackServiceTest,CmsContentServiceIntegrationTest#publishTextUnitLockSerializesCurrentVariantMutationLock test` passed with 12 tests, 0 failures/errors, and the one expected MySQL-only text-unit lock assertion skipped on HSQL; the unit and rollback coverage prove current-variant writers take the shared publish lock, including status-only mutations reloading translation content after the lock before deriving their write, while the disposable MySQL blocking proof is recorded below.
- `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=*Test,!TextUnitSearcherTest' test` passed with 1,641 tests, 0 failures/errors, and 48 skips after the zero-byte retained history metadata fence. The selector excludes the tracked `TM-01` HSQL Criteria id-search failure outside the CMS slice.
- A disposable HSQL-backed `webapp` smoke rendered `http://localhost:18080/settings/system/content-cms` after `admin/ChangeMe` login and verified `/settings/system` still exposes both upstream JSON config localization and the author-facing Product copy entry after the route/settings merge, with no browser warning/error logs.
- A refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174` rendered the author-first CMS without touching the normal `8080` port, created a
  copy collection through the clean onboarding example, saved the welcome-email first content item,
  verified inline Mojito/localization status on desktop and full-page mobile layouts, and exercised
  the app-owned dirty-route leave dialog through both `Stay here` dismissal and `Leave editor`
  navigation before the disposable servers were stopped.
- A refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174` rendered the source-only author recovery without touching the normal `8080`
  port, created a copy collection through the clean onboarding example, saved the welcome-email
  first content item, verified the inline `Choose translation languages` recovery with disabled add until
  selection plus common-locale default and opt-in full catalog, added `fr-FR` through the same local CMS endpoint, confirmed the refreshed inline
  translation editor with muted
  `fr-FR Needs translation` state and no duplicate readiness card, and checked desktop plus `390px`
  mobile layouts for horizontal overflow before the disposable servers were stopped.
- A refreshed disposable HSQL-backed `webapp` on `127.0.0.1:9876` plus Vite on
  `127.0.0.1:5174` verified the real multi-item release blocker repair path without touching the
  normal `8080` port: Browser created two included content items, changed saved source copy on the
  second item so its approved French translation correctly reopened as needing translation, started
  release from the still-ready first item, saw the release card name `Follow-up email` with
  `French (France) needs translation before release.`, then used `Write translation` to focus that
  exact blocked editor with its inline French translation action open before the disposable servers
  were stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` multi-field release blocker
  coverage passed after routing field-scoped completeness through the author release card: when an
  included content item has multiple localizable copy fields, blockers now name the exact field
  (`CTA label: French (France) needs translation before release.`) and `Write translation` opens that
  focused field's inline translation editor, while single-field items keep the simpler content-item
  wording. A disposable Vite-only
  `release-blocker` preview on `127.0.0.1:5174`, without starting a backend or touching the normal
  `8080` port, rendered both `Headline` and `CTA label` blocker actions and verified the CTA action
  focused the active `CTA label` field with its French translation editor open before the disposable
  server was stopped.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` multi-locale release blocker
  coverage passed after carrying the exact blocked locale through the same author repair action:
  when an included CTA field is already ready in French but still blocked in Japanese, the release
  card says `CTA label: Japanese (Japan) needs translation before release.` and `Write translation`
  opens the focused CTA inline editor on Japanese instead of falling back to the first configured
  target language. The DEV-only `release-locale-blocker` preview keeps that exact repair path available for
  Vite-only browser smoke checks without a backend or the normal `8080` port; the latest `390x844`
  `127.0.0.1:9876` pass verified `Write the copy` owns the first phone viewport, the project release
  card starts below the selected item release strip, and opening the Japanese CTA repair keeps its
  translation editor visible while release chrome stays below the viewport. The paired DEV-only
  `release-repair-refresh-failed` seed lands directly on the post-save handoff plus release-card retry
  recovery, so a browser demo can show the saved repair state without depending on backend mutation
  timing.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` source-copy release blocker
  coverage passed after carrying whether a blocked field needs source copy or target translation
  through the same author repair action: when an included CTA field is missing required English
  source copy but French is already approved, `Fix source copy` focuses the CTA source editor
  without opening inline French translation. The DEV-only `release-source-blocker` preview keeps
  that source-copy repair path available for Vite-only browser smoke checks without a backend or
  the normal `8080` port.
- Focused `AdminContentCmsPage` and `ContentCmsAuthoringPreview` single-field release blocker
  coverage passed after removing the last item-level fallback from field-scoped completeness:
  an included one-field content item now names `Headline` in the release card and `Write translation`
  focuses the blocked field with its French inline editor open instead of sending authors back to
  generic content-item metadata. The DEV-only `release-single-blocker` preview keeps that exact
  single-field repair path available for Vite-only browser smoke checks without a backend or the
  normal `8080` port.
- Focused release blocker editor-focus coverage passed after extending the delayed authoring scroll
  helper with repair targets and letting inline translation refocus after its saved target loads:
  source-copy blockers now land on the source textarea, while translation blockers land on the
  exact French or Japanese translation textarea after the matching inline editor opens. The
  existing DEV-only blocker previews keep those keyboard-first repair paths available for Vite-only
  browser smoke checks without a backend or the normal `8080` port.
- Focused stale-release source-copy review coverage passed after keeping source-copy review jumps on
  the same author editor instead of inventing a second review surface: `Open Headline` or `Open
  Header` focuses the exact source textarea, marks only that source field as `Review from release`,
  and keeps the release-return handoff beside the field so the author can review changed source copy
  and return without losing release context.
- Focused stale-release source-copy comparison coverage passed after letting the exact release-change
  payload carry only that field's last released source copy from the already validated prior artifact:
  opening a changed source field now shows `Last released source copy` beside `Current source copy`
  under the real editor, while the compact release list still names changes without dumping copy
  values or reopening publish-package internals.
- Focused stale-release translation comparison coverage passed after letting an exact
  `TRANSLATION_CHANGED` row carry only that locale's last released target copy from the same
  validated prior artifact: opening the changed locale now shows `Last released <locale>
  translation` beside `Current saved <locale> translation` under the real inline editor, while a
  dirty target draft still stays distinct from the saved copy apps would receive.
- Focused unavailable/loading release blocker coverage passed after making the inline translation
  editor itself the temporary repair target while saved target copy opens and adding a focused
  `Try again` action when Mojito returns no saved target for the blocked language: release repair
  never opens a dead panel with no keyboard landing point before the author can retry, choose another
  language, or add the removed language back through local authoring.
  The DEV-only `release-target-unavailable` preview keeps the unavailable-target repair path
  available for Vite-only browser smoke checks without a backend or the normal `8080` port.
- Focused retry/return repair coverage passed after carrying only the hidden blocked target locale
  through CMS author/admin and Mojito detail return paths: a successful retry refocuses the exact
  blocked textarea, and a recovery-only repair round trip reopens/refetches the same field
  and locale before cleaning the author URL back to its normal project/content-item/field selection.
- Focused explicit admin-tool repair coverage still verifies that a locale-scoped technical deep link
  opens the exact Mojito-link form instead of the broad maintenance toolbox, but normal Product copy
  no longer sends an author there for a missing saved translation.
- Focused stale inline translation recovery coverage now keeps the author confirmation on the latest
  saved translation instead of `current CMS data`, so a missing saved translation can refresh without
  exposing backend cache language in Product copy.
- Focused inline reconnect coverage now verifies that an unavailable release-blocker translation
  reconnects the visible field's saved source copy in Product copy, refetches the same hidden field
  and locale, and opens the translation without routing through `/admin`.
- Focused failed inline reconnect coverage now verifies that a failed reconnect stays beside the same
  open field and language, focuses the matching reconnect retry, and suppresses the old page-level
  mutation notice; the DEV-only `release-target-reconnect-error` preview keeps that author state
  Browser-verifiable without a backend or the normal `8080` port.
- Focused reconnect conflict coverage now verifies that a concurrent saved-copy change refreshes the
  same clean source field before reconnect retry, keeps the conflict beside that field/language,
  and retries with the latest mapping version; the DEV-only `release-target-reconnect-conflict`
  preview keeps that author state Browser-verifiable without a backend or the normal `8080` port.
- Focused dirty release-repair coverage now verifies that an unavailable translation blocker opened
  from a field with unsaved source copy stops at `Save source changes before translating`, keeps only
  the saved-translation escape hatch visible, does not offer reconnect until the refreshed source
  saves, and returns to the same CTA/French release blocker without a false `Repair saved` handoff
  from either the local source save or an item-level `Save copy changes` pass that also saves CTA
  placement details and translator note drafts; if that item-level save hits a refreshed CTA placement
  conflict before Mojito source save, the same dirty source and translator note stay visible beside the
  saved placement comparison, reconnect stays blocked behind `Save source changes before translating`,
  and the return handoff still names the same CTA/French blocker instead of claiming `Repair saved`;
  reopening that named blocker after the conflict then retries placement save with the refreshed
  version, saves the same source and translator note drafts, reconnects the same French translation,
  and advances the handoff to the CTA/French review blocker instead of dropping the release context;
  if the author dirties that CTA/French translation before also editing CTA source, placement, and
  translator note, the release card keeps the same known blocker visible from the last checked
  readiness while live release eligibility is cleared, `Save copy changes` persists only those
  non-translation drafts behind `Save source changes before translating`, and the dirty French draft
  returns to its locale-specific save action before advancing to review; if that locale-specific save
  fails, the same CTA/French blocker stays visible beside the author-language error, `Send for review`
  keeps retry focus, and the handoff does not claim `Repair saved` until the retry advances the blocker
  to review; that refreshed same-field row changes its visible release action from `Write translation`
  to `Review translation` and reopens the live review target instead of retaining stale write-row
  behavior; when that review blocker is opened for status-only approval, a failed `Approve for release`
  keeps the same live CTA/French review blocker and focused approval retry without inventing an unsaved
  draft gate, only the successful retry moves the handoff to `Repair saved` and rechecks release,
  refreshed completeness replaces a cleared source or CTA/French review blocker with any next live
  field blocker instead of declaring ready, remaining write blockers stay ahead of the just-repaired
  field's new review blocker or selected content item's review-only group instead of over-centering
  the active editor, untouched same-priority write blockers keep their field or content-item order when
  that repaired review blocker falls behind `Show more fields` or another write-blocked item, a
  refreshed blocker set collapses that local `Show more fields` expansion so the compact release card
  does not stay tall after the author changes the work list, required source-copy blockers stay above
  that same repaired review state, and a failed post-approval release refresh keeps that saved handoff
  plus the release-card retry instead of reviving the stale CTA/French review blocker;
  the DEV-only `release-target-dirty-source-draft` and `release-target-dirty-source-conflict`
  previews keep those guardrails Browser-verifiable without a backend or the normal `8080` port.
- Focused reconnected-but-not-yet-open translation coverage now keeps that successful inline reconnect
  in author state instead of route state: if Mojito still has no target row after the forced refetch,
  the same field says the locale translation still needs work, keeps retry focused, and offers the
  matching reconnect action again instead of narrating link state or claiming the copy is broken.
- Inline translation loading now uses a CMS-scoped exact field/language read instead of the broad
  workbench hybrid search: the service validates the CMS mapping and configured target locale, then
  disables the workbench-only root-locale exclusion so a valid CMS target can open an editable
  untranslated row after reconnect.
- Product copy now refuses to silently swap a scoped repair or release-blocker return onto another
  configured language when the requested locale was removed meanwhile: the inline editor names that
  exact language as not set up, keeps the recovery-only `Add <language>` action scoped to it and
  submits that exact locale through the normal authoring add-language mutation,
  gives one quiet continue action only when exactly one editable configured fallback remains, lets the
  author deliberately choose among several configured translation languages instead of arbitrarily
  reopening the first one, focuses that chosen translation copy, and preserves
  that exact recovery target when the author route mounts directly from a saved return URL. The
  DEV-only `translation-language-removed`, `translation-language-removed-multiple-alternates`,
  `release-target-language-removed`, and `release-target-language-removed-multiple-alternates`
  previews keep those removed-locale return paths available for Vite-only browser smoke checks without
  a backend or the normal `8080` port.
- Product copy now refuses to present a configured translation language as editable when the current
  author lacks language access: the inline editor replaces the disabled textarea and inert save row
  with one no-access recovery, closed translation rows say that language needs access instead of advertising
  `Write`, keeps inaccessible picker rows visible as `No access` instead of selectable dead ends,
  focuses `Choose another language` for scoped returns when an editable configured language exists, and
  focuses the editable translation the author deliberately chooses. The DEV-only
  `translation-locale-no-access` preview keeps that permission return path available for Vite-only
  browser smoke checks without a backend or the normal `8080` port.
- Product copy now keeps removed requested translation recovery singular when the remaining configured
  languages are not editable for the current author: the inline editor removes the dead `Switch
  language` affordance, tells the author to ask for another locale or add the removed one again, and
  focuses that one add path. The DEV-only `translation-language-removed-no-access` preview keeps
  that combined removal/access return path available for Vite-only browser smoke checks without a
  backend or the normal `8080` port.
- Product copy now keeps removed requested translation recovery visible when the copy has no configured
  translation language left: the inline editor does not collapse away with the empty locale list, and
  keeps the one focused add action scoped to the removed requested language in the local authoring
  flow. The DEV-only
  `translation-language-removed-no-targets` preview keeps that zero-target return path available for
  Vite-only browser smoke checks without a backend or the normal `8080` port.
- Product copy now keeps removed requested translation recovery honest while the supported locale
  catalog loads, fails, or no longer contains that exact language: the inline editor keeps loading
  as a scoped status instead of a false error, hands focus to the exact local add/retry action once
  the catalog resolves, hides the dead add action once the catalog cannot support it, focuses local
  `Try again`, and tells the author when an admin must restore the supported language before
  translation can reopen; direct removed-language recovery keeps its exact add/retry action plus a
  quiet configured-language continue action when exactly one editable fallback remains, or one scoped
  chooser when several editable fallbacks remain, instead of stacking a generic language switch, while
  source-only recovery keeps a muted dismiss that restores the local add-language next step instead of
  stranding the author.
  The DEV-only
  `translation-language-removed-loading` and `translation-language-removed-unsupported` previews
  keep those direct-return paths available for Vite-only browser smoke checks without a backend or
  the normal `8080` port.
- Product copy now keeps the approved-translation release handoff singular: when a draft content
  item is ready, the inline `Ready for release` handoff is the only visible `Include in next release`
  action, while the lower item release strip stays reserved for already-included copy and scoped
  release-step recovery; until that handoff includes at least one ready content item, the global
  release card stays out of the author path instead of rendering a disabled empty-release card. This removes
  the duplicated primary release CTA, keeps the immediate handoff in `Next step` language instead of
  opening with release-console wording, and removes the empty-release trap from the ordinary author
  path without hiding failure feedback. Once copy is included, that same release card names every included content
  item before the author releases and lets them reopen another included item directly, so the release
  target is explicit without reopening package tables or admin setup; the first author release click
  then says `Checking release` while it checks every included content item before confirmation, so the
  primary action does not look dead during that required gate; once that check passes, the confirmation
  repeats the exact included content item names before the final release click instead of collapsing back
  to a generic project-level prompt, then says `Releasing copy` while the release request is in flight so
  the card does not look inert after that confirmation, and acknowledges the released content item names
  plus release version once that request succeeds instead of dropping back to `Release not started`;
  that just-completed author release then holds the primary action as disabled `Released` until copy,
  inclusion, project, or release-scope state changes instead of inviting an immediate duplicate
  release. After a reload with release history, Product copy silently rechecks the current package
  and requested release scope against the latest snapshot's stored publish fingerprint; when they
  match exactly, it restores that same disabled `Released` handoff without asking the author to click
  release again, while a merely older last release remains releasable and names whether translation
  languages, copy/included content, or translation/approval status changed since that release; when
  a restored release has no saved language scope, the passive history summary now says `No translation
  languages` instead of reopening raw `locale` vocabulary before the author reviews the latest copy; when
  the stored published artifact proves the exact delta, the author card names the changed language,
  content item, and field from a bounded twelve-row snapshot diff instead of sending them hunting
  through admin; the first four rows keep the card compact, authors can reveal the remaining exact
  rows inline, an overflow action fetches the full exact diff only when the author asks for it, and
  the loaded rows group by content item, editable field, and release-language changes so large diffs
  stay scannable; the compact first view surfaces every known action-needed row before filling the
  remaining preview slots with passive rows, item and field headers name how many rows need author
  action, the bounded snapshot payload keeps later unfinished translation or review rows available
  before passive overflow asks the author to load every change, the selected content item and field
  move before other passive changed items in that compact preview plus expanded grouping, and inside
  an editable field unfinished translation or review work stays first with the visible `Write` or
  `Review` verb while passive source or field-shape changes stay below it;
  current changed items and fields reopen the exact source or locale review path when that target
  still exists instead of reopening package tables, and that opened field or content-item header
  keeps one local `Return to release` handoff with the exact release-review reason plus review-only
  versus repair-needed next-step copy; blocker repairs now say `Fix before release` instead of
  mislabeling the task as review, the local return state announces only its terse status copy, and
  the `Return to release` action is described by that state so the author can get back to the
  decision without scrolling through the whole content item again.
- Focused field-addition stale-release coverage passed after marking the opened field itself as the
  `Review from release` target when a passive `FIELD_ADDED` row reopens authoring: source-copy and
  locale changes still keep their narrower source/translation targets, while a newly added field no
  longer lands as an unscoped active card before the author returns to release.
- Focused content-item-addition stale-release coverage passed after marking the opened content-item
  editor itself as the `Review from release` target when a passive `CONTENT_ITEM_ADDED` row reopens
  authoring: item-level review no longer looks like an ordinary selection before its local
  `Return to release` handoff, while field/source/translation rows still keep their narrower targets.
- Focused stale-release row-verb coverage passed after replacing generic passive `Open` actions with
  explicit `Review` actions for source-copy changes, added fields, and added content items: the release
  card now names the author's next task before navigation, while true repair rows still keep `Write` or
  `Review` based on the target translation state.
- Focused release-return fallback coverage passed after keeping one content-item-level `Return to
  release` handoff visible when an author deliberately leaves the exact reviewed field: the narrow
  field/source/translation target cue clears, but the release decision no longer recedes before the
  author returns.
- Focused release-return dismissal coverage passed after clearing that opened-review handoff once the
  author uses `Return to release`: the release panel receives focus, while stale local review target
  chrome no longer lingers if the author scrolls back into the content item.
- Focused removed-release-target return coverage passed after making that same author-route handoff
  silently recheck current release readiness before leaving the repair path: when the requested
  Japanese blocker was removed while the author was looking at it, `Return to release` drops the dead
  Japanese release row and shows the current French blocker instead of asking the author to chase an
  impossible target again; failed rechecks reuse the local release recovery and route the next
  `Release approved copy` attempt through another readiness check instead of silently losing the
  decision or releasing stale readiness.
- Focused removed-release-target add-back coverage passed after keeping `Add Japanese (Japan)` on the
  same scoped recovery path: re-adding the requested blocker language returns the author to the real
  Japanese CTA translation task, holds the nearby `Return to release` handoff while that add-back is
  still saving instead of racing back into stale readiness, keeps a failed fresh field-status read on
  local `Try again` instead of reviving stale `Add Japanese (Japan)` setup copy after the add already
  succeeded, lets a successful fresh read that still lacks Japanese settle back to honest local `Add
  Japanese (Japan)` instead of hanging on `Opening`, and `Return to release` rechecks current readiness
  so a failed release-card refresh falls back to `Release not started` plus local retry instead of
  reviving stale Japanese/French blockers, then the next release retry shows and reopens the live
  Japanese blocker again instead of preserving the stale removed-language French fallback or stale
  add-language setup recovery, and saving that reopened Japanese task replaces its write row with the
  live Japanese review blocker instead of reviving stale setup or French fallback state; reopening that
  refreshed Japanese review row keeps a failed status-only approval on the same live Japanese review
  blocker with focused retry instead of reviving stale Japanese write, add-language setup, or French
  fallback blockers, then a successful approval retry keeps `Repair saved` plus the release-card retry
  when the post-approval refresh fails before the next release recheck reaches live ready release state;
  once ready, the author confirmation and publish request return to the live included-copy/configured-
  language release flow instead of falling back to stale removed-language recovery state, then an
  unresolved release request remounts into only the current author retry card before a successful retry
  reaches released copy, and the exact released snapshot reload restores that released handoff instead
  of reviving retry chrome, stale blockers, or add-language setup; a later stale same-project refetch
  without that exact snapshot keeps the already-returned released snapshot instead of replacing the
  released handoff with contradictory no-release history, while a real later copy revision still leaves
  that released handoff for current release review without losing the last released snapshot; released
  copy also keeps one author-facing `Check release again` action so later Mojito review/package changes
  with unchanged source copy can leave the released handoff for current review without exposing
  technical release controls or opening a release confirmation before the author sees the change; if
  that current release check cannot refresh, the author keeps the known last released handoff plus
  local retry instead of falling back to misleading never-released state; if that recheck returns a
  live Mojito write or review blocker, the author leaves released chrome and opens the same inline
  field/language repair with return-to-release context instead of detouring through technical release
  details; when that reopened repair saves, current readiness refreshes from Mojito and stays on the
  current review path, including the next live blocker, instead of reviving the stale released handoff
  before the author releases again;
  if reopened source-copy repair collides with newer saved copy, the author keeps the draft beside
  the refreshed saved copy with return-to-release context and save-before-release chrome, can adopt
  that refreshed saved copy without losing the current blocker context, advances to current readiness
  including any next live blocker when that saved copy already clears the source blocker, then retries
  later edits against the refreshed mapping before returning to current readiness instead of reviving
  that stale released handoff;
  if current readiness cannot refresh after that conflict, adopting the refreshed saved copy keeps the
  inline source repair on honest retry plus `Release needs another check` recovery until the author
  returns to current release truth instead of claiming that stale source blocker was repaired;
  if the author instead retries their original source draft against that refreshed mapping, the stale
  release retry remains visible until that save resolves, then the fresh repair readiness result clears
  it without stacking old retry copy beside current `Repair saved` or ready release state;
  if the author returns to release before resolving that refreshed source conflict, current release
  truth rechecks and clears stale retry copy while the dirty source draft plus `Saved elsewhere`
  comparison stay visible and keep release on save-before-release instead of silently discarding work,
  then adopting that refreshed saved copy from the returned authoring state clears the dirty conflict
  against the same current readiness without reopening stale inline return chrome before release can
  continue; when that returned current truth contains another live translation or review blocker, the
  same adoption advances to that author-facing blocker instead of reviving stale released or technical
  release state, and opening the returned review row focuses the inline authoring review with current
  review return context instead of resurrecting stale source-conflict return copy; approving that
  returned review row refreshes current ready release truth without reviving the old source-conflict
  retry or last-released handoff, then author confirmation publishes that refreshed approved package
  and a fresh exact reload restores that refreshed released handoff instead of falling back to the
  stale exact-release snapshot, with the next explicit author recheck keeping that refreshed handoff
  instead of demoting it back to repair or ready chrome, and a failed recheck keeping the same v5
  handoff with local retry copy instead of reopening stale repair state before a later successful
  retry clears that local error back to the clean v5 handoff without republishing, while a later live
  Mojito blocker leaves released state for that current blocker without reviving old source-conflict
  repair context, then opens the current inline review with focused field editing and current review
  return copy instead of stale source-conflict text, and approving that later review returns to current
  ready release truth without reviving stale released or failed-recheck chrome before the author's
  explicit release action restores that exact refreshed released handoff without republishing, while a
  later changed package can reopen that same saved inline review from live Mojito state, return to
  current ready release truth, and confirm a new refreshed release instead of sticking on stale review
  chrome or restoring the old exact handoff, with a fresh exact reload restoring that newer released
  handoff instead of falling back to the older refreshed release, and a failed explicit recheck keeping
  that newest handoff with local retry copy until a successful retry clears only that local error,
  before a later live Mojito blocker leaves released state for the current inline review without
  reviving older release or repair context, and approving that newest returned review restores current
  ready release truth with saved-repair return copy instead of reviving released or failed-recheck
  chrome before the author explicitly restores that exact newest released handoff without republishing,
  while a later ready changed package leaves that clean newest handoff for one fresh author
  confirmation and newer release instead of silently restoring the old exact package or reviving
  blocker copy, then a fresh reload restores that newest exact released handoff instead of falling
  back to the prior release or reopening inline review context, while explicit recheck success keeps
  that newest handoff clean and a failed recheck keeps only local retry copy until recovery clears it,
  before a later live Mojito blocker leaves that recovered newest handoff for the current inline review
  without reviving older release history, failed-recheck copy, or stale source-conflict return context,
  and approving that latest returned review restores current ready release truth with saved-repair
  return copy instead of reviving released or failed-recheck chrome before the author's explicit
  release action restores that exact latest handoff without republishing or keeping inline repair
  chrome, while another later ready changed package leaves that clean latest handoff for fresh
  confirmation and newer release instead of silently restoring the exact old package or reviving prior
  review context, then a fresh reload restores that newest exact released handoff instead of falling
  back to the prior release or reopening inline review context, while explicit recheck success keeps
  that latest handoff clean and a failed recheck keeps only local retry copy until recovery clears it,
  before another live Mojito blocker leaves that recovered latest handoff for the current inline review
  without reviving older release history, failed-recheck copy, or stale source-conflict return context,
  and approving that final returned review restores current ready release truth with saved-repair return
  copy instead of reviving released or failed-recheck chrome before the author's explicit release action
  restores that exact final handoff without republishing or keeping inline repair chrome, while another
  later ready changed package leaves that clean final handoff for fresh confirmation and newer release
  instead of silently restoring the exact old package or reviving prior review context, then a fresh
  reload restores that newest exact released handoff instead of falling back to the prior release or
  reopening inline review context, while explicit recheck success keeps that final handoff clean and a
  failed recheck keeps only local retry copy until recovery clears it, before another live Mojito blocker
  leaves that recovered final handoff for the current inline review without reviving older release
  history, failed-recheck copy, or stale source-conflict return context, and approving that ultimate
  returned review restores current ready release truth with saved-repair return copy instead of reviving
  released or failed-recheck chrome before the author's explicit release action restores that exact
  ultimate handoff without republishing or keeping inline repair chrome;
  if that saved-repair refresh fails, the author sees the same saved-repair retry card from current
  review instead of a contradictory released handoff, including when the reopened repair is source
  copy or approval-only review rather than fresh target copy.
- Focused removed-release-target add-back failure coverage passed after rendering the existing
  translation-language save recovery inside that same inline removed-language card: when re-adding
  Japanese fails, the author keeps the local `Add Japanese (Japan)` retry plus `Return to release`
  handoff, and returning rechecks current readiness so release shows the live French blocker instead
  of pretending the failed Japanese add-back succeeded.
- Focused removed-release-target add-back retry coverage passed after clearing that same local
  translation-language save recovery on the next direct `Add Japanese (Japan)` attempt without
  rebuilding hidden picker draft: a successful retry opens the real Japanese CTA translation task,
  removes stale failure chrome, and `Return to release` rechecks the live Japanese blocker instead of
  preserving the removed-language French fallback.
- Focused removed-release-target concurrent add-back coverage passed after treating a direct
  `Add Japanese (Japan)` conflict as a scoped refresh instead of a dead generic failure: while current
  copy reloads the local add action stays disabled beside the inline translation-language recovery,
  then refreshed Japanese completeness clears that stale recovery, focuses the real Japanese CTA task,
  and returns through live Japanese release truth without asking the author to add a language another
  editor already restored.
- Focused removed-release-target unresolved concurrent add-back coverage passed after settling that
  same scoped refresh honestly when current copy still lacks Japanese: stale refreshing copy disappears,
  the local `Add Japanese (Japan)` retry re-enables beside the inline translation-language recovery,
  and `Return to release` rechecks into the current French blocker instead of trapping the author in a
  disabled add-back action.
- Focused removed-release-target concurrent add-back refresh-failure coverage passed after separating
  failed refetch from successful current-copy reload: the inline recovery stops claiming copy refreshed,
  keeps the readiness `Try again` plus local `Add Japanese (Japan)` retry available, and still lets
  `Return to release` recheck current French release truth instead of stranding the author inside stale
  Japanese setup.
- Focused removed-release-target concurrent add-back refresh-retry coverage passed after retaining that
  failed-refetch request only long enough for readiness `Try again`: while the retry runs the local add
  action disables again, then refreshed Japanese completeness clears stale add-back failure chrome,
  focuses the real Japanese CTA translation task, and returns through live Japanese release truth.
- Focused removed-release-target concurrent add-back retry-still-missing coverage passed after letting
  that same readiness retry settle from live completeness instead of treating any successful refetch as
  restored Japanese: when the retry succeeds but Japanese is still absent, the local add action
  re-enables beside the honest still-not-set-up recovery, stale refresh-error chrome clears, and
  `Return to release` rechecks current French release truth.
- Focused removed-release-target concurrent add-back second-add coverage passed after clearing that
  settled still-not-set-up recovery on the next direct `Add Japanese (Japan)` attempt: the author can
  retry from the same inline card without stale recovery chrome surviving the mutation, then a
  successful add opens the real Japanese CTA translation task and returns through live Japanese release
  truth.
- Focused removed-release-target concurrent add-back repeated-conflict coverage passed after replacing
  that same settled still-not-set-up recovery on a second direct conflict: the inline card returns to
  active refreshing copy while current completeness reloads, drops the stale settled sentence instead of
  stacking contradictory recovery text, then settles back to the honest local add retry and current
  French release truth when Japanese is still absent.
- Focused removed-release-target concurrent add-back repeated-conflict refresh-failure coverage passed
  after letting that second direct conflict fail its current-copy reload: active refreshing copy falls
  back to the failed-refresh retry state instead of reviving the stale settled sentence, leaves local
  `Try again`, `Add Japanese (Japan)`, and `Return to release` actions usable, and returns through
  current French release truth while Japanese is still absent.
- Focused removed-release-target concurrent add-back repeated-conflict refresh-retry coverage passed
  after retrying that second failed current-copy reload: the local readiness retry disables add-back
  while it reloads, clears failed-refresh and stale settled recovery when Japanese reappears, focuses
  the real Japanese CTA translation task instead of another locale, and returns through live Japanese
  release truth.
- Focused removed-release-target concurrent add-back repeated-conflict refresh-retry-still-missing
  coverage passed after retrying that same second failed current-copy reload while Japanese remains
  absent: the local readiness retry disables add-back while it reloads, clears failed-refresh and
  active recovery instead of reviving stale error chrome, settles back to the honest local add retry,
  and returns through current French release truth.
- Focused removed-release-target concurrent add-back repeated-conflict third-add coverage passed after
  retrying from that second failed-refresh still-missing state: the next direct `Add Japanese
  (Japan)` clears the settled retry copy before its save resolves, a successful add opens the real
  Japanese CTA translation task, and `Return to release` rechecks live Japanese release truth.
- Focused removed-release-target concurrent add-back repeated-conflict third-conflict coverage passed
  from that same second failed-refresh still-missing state: another direct `Add Japanese (Japan)`
  clears the settled retry copy while current copy reloads, another conflict restores active
  refreshing recovery instead of stale settled or page-level retry chrome, failed reload keeps local
  retry actions usable, and `Return to release` rechecks current French release truth while Japanese
  remains absent.
- Focused removed-release-target concurrent add-back repeated-conflict third-refresh retry-still-missing
  coverage passed after retrying that third failed current-copy reload while Japanese remains absent:
  local readiness retry disables add-back while it reloads, clears failed-refresh and active recovery
  instead of reviving stale error chrome, settles back to the honest local add retry, and returns
  through current French release truth.
- Focused removed-release-target concurrent add-back repeated-conflict third-refresh retry coverage
  passed after retrying that third failed current-copy reload when Japanese reappears: local
  readiness retry disables add-back while it reloads, clears failed-refresh and stale recovery,
  focuses the real Japanese CTA translation task, and returns through live Japanese release truth.
- The latest `390x844` Vite-only `release-locale-blocker` pass on `127.0.0.1:9876` verified that
  opening the Japanese CTA blocker keeps `Fix before release` and a full-width `Return to release`
  action visible above the inline translation editor, focuses the Japanese translation input, returns
  focus to the release panel when asked, and leaves no app-origin console errors or horizontal
  overflow before the disposable server is stopped.
- The latest Vite-only `authoring-demo` opened-translation pass on `127.0.0.1:9876` verified at
  `1280x720` and `390x844` that the French headline editor opens on `Write French (France)
  translation`, keeps the passive badge as only `Needs translation` with the full locale/status in
  its accessible label, removes the generic visible `Translation` eyebrow and repeated pre-field task
  sentence, leaks no schema/mapping/variant/publish/Mojito terms into the author path, and leaves no
  app-origin warning/error logs or horizontal overflow before the disposable server is stopped.
- Focused authoring and deterministic preview coverage now verifies that the optional expanded
  translation-language chooser stays below its grouped disclosure, keeps locale/status in accessible
  button names, and lets visible rows read as author actions (`Write`, `Review`, `Open`, or `Refresh`)
  instead of `1 translate`/approval count report chrome.
- Focused authoring coverage now verifies that author-path `Copy collections`, `Switch language`,
  `Other actions`, task-specific inline help (`More translation help` / `More review help`),
  source-only `Search all languages`, and release
  blocker/change disclosure controls identify their rendered target regions, so keyboard and
  assistive-tech users do not get expansion state without a real target panel.
- Focused authoring coverage now verifies that opening `Write new content item` moves keyboard focus
  into the new content item name field, so the disappearing editor-header trigger does not strand an
  author before the first field in the newly opened writing task.
- Focused authoring coverage now verifies that opening `New copy collection` moves keyboard focus into
  `Copy collection name`, so the disappearing rail action does not strand an author before the first
  field in the new first-copy task.
- Focused authoring coverage now verifies that when `Start writing` creates the copy collection but the
  first content item does not save, recovery moves keyboard focus into the restored first content item
  form, so the removed start action does not strand an author before they finish that saved collection.
- Focused authoring coverage now verifies that opening `New copy collection` from the expanded phone
  rail collapses `Copy collections` before the first-copy form takes focus, then reopens that drawer
  before cancel returns focus to the restored rail action, so mobile navigation does not sit above the
  new writing task or hide its return target.
- Focused authoring coverage now verifies that canceling a new content item returns keyboard focus to
  the restored `Write new content item` action, so abandoning that inline task does not leave an author
  at the top of the page or on a removed control.
- Focused authoring coverage now verifies that canceling another copy collection draft returns keyboard
  focus to the restored `New copy collection` action, so abandoning that first-copy task does not leave
  an author on a removed `Cancel` button.
- Focused authoring coverage now verifies that saving a first or later content item moves keyboard
  focus into the restored saved item editor, so the disappearing `Start writing`, `Save first content
  item`, or `Save content item` action does not strand an author after the new writing task becomes
  normal saved copy.
- Focused authoring coverage now verifies that saving one field's source copy returns keyboard focus
  to that live source editor after its local save action disappears, so finishing a Mojito-backed
  source save does not strand an author before they continue writing.
- Focused authoring coverage now verifies that saving one field's placement details says
  `Saved placement details` and returns keyboard focus to that restored placement editor after its
  local save action disappears, so optional translator context stays author-facing instead of
  dropping back into `Updated field` maintenance wording.
- Focused authoring coverage now verifies that saving open content-item details says `Saved copy
  details` and returns keyboard focus to the restored item name after its local save action disappears,
  so the quiet `Item details` panel does not strand authors or fall back to `Updated content item`
  maintenance wording.
- Focused authoring coverage now verifies that the primary `Save copy changes` action returns keyboard
  focus to the active restored source editor when its save bar disappears, so the normal authoring
  escape hatch does not leave keyboard users on a removed page-level action after mixed item, placement,
  and source-copy saves.
- Focused authoring coverage now verifies that conflict recovery actions for source copy, placement
  details, and item details return keyboard focus to the restored editor after `Use saved ...` removes
  its own comparison panel, so adopting another editor's saved copy keeps authors in the writing flow.
- Focused authoring coverage now verifies that a successful release returns keyboard focus to the stable
  release section after `Release approved copy` changes into the released handoff, so completing a
  release does not strand keyboard authors on the removed primary action.
- Focused authoring coverage now verifies that a failed release returns keyboard focus to the stable
  release section after `Releasing...` changes back into the retry action, so an unresolved publish
  request leaves authors at the release card instead of on the removed in-flight control.
- Focused authoring coverage now verifies that a failed readiness check returns keyboard focus to the
  stable release section after `Release approved copy` falls back to recovery, so checking a release
  before publish does not strand authors on a changed release card.
- Focused authoring coverage now verifies that opening another content item moves keyboard focus to the
  selected item editor after clean or confirmed dirty switches, so item-row navigation does not leave an
  author focused on stale list chrome while a different writing surface is open.
- Focused authoring coverage now verifies that opening another included content item from release
  details moves keyboard focus to the selected item editor, so the folded release summary does not
  leave an author focused below the writing surface after choosing the next item to review.
- Focused authoring coverage now verifies that switching copy collections from the expanded phone rail
  collapses `Copy collections` and moves keyboard focus into the selected collection's writing surface
  after clean or confirmed dirty switches, including the first content item form when that collection
  has no saved item yet, so a hidden rail row does not retain focus after navigation.
- Focused authoring coverage now verifies that retrying a failed copy-collection rail load returns
  keyboard focus to the restored selected collection row, filtered search box, or clean-start copy
  collection name, so the disappearing rail `Try again` action does not strand an author before they
  can reopen, switch, find, or start copy collections.
- Focused authoring coverage now verifies that clearing a filtered copy-collection rail returns
  keyboard focus to `Search copy collections`, so the disappearing clear control does not strand an
  author after reopening the normal collection list.
- Focused authoring coverage now verifies that retrying a failed selected copy collection load returns
  keyboard focus to the restored writing surface, including the first content item form when that
  collection has no saved item yet, so the disappearing inline `Try again` action does not strand an
  author after recovery.
- Focused deterministic preview coverage now verifies that hiding an inline translation editor returns
  keyboard focus to the restored local translation or setup action, so closing that Mojito-backed task
  does not leave an author on a removed button.
- Focused authoring coverage now verifies that retrying a failed source-only translation-language load
  returns keyboard focus to the restored language chooser or no-language setup state after `Try again`
  disappears, so Mojito locale setup recovery stays inside the inline next step instead of dropping
  authors outside the task.
- Focused authoring coverage now verifies that a failed inline translation readiness refresh returns
  keyboard focus to its local `Try again` action after the completed translation save control
  disappears, so the Mojito status recovery remains attached to the same field instead of dropping
  authors out of the translation task.
- Focused authoring coverage now verifies that a missing saved inline translation returns keyboard
  focus to its required `Refresh copy` recovery after the attempted save can no longer continue, so
  stale Mojito links stay recoverable from the same field without a keyboard dead end.
- Focused authoring coverage now verifies that clearing a local inline translation draft with `Reset`
  returns keyboard focus to the restored translation editor when no queued field switch owns the next
  handoff, so the disappearing reset control does not strand authors outside the same copy field.
- Focused authoring coverage now verifies that canceling the inline `Exclude from release`
  confirmation returns keyboard focus to that still-visible secondary action, so declining a less-common
  release-state change does not drop authors outside the open field menu.
- Focused authoring coverage now verifies that inline translation disclosures keep their
  control-to-panel relationship on the trigger before expansion, so `Switch language`, `Other actions`,
  and task-specific inline help do not announce as detached buttons until after an author opens them.
- Focused detail-handoff coverage now verifies that CMS-originated optional translation tools open
  as a compact chooser, with glossary content staying collapsed until the author explicitly asks for it.
- Focused detail-handoff coverage now verifies that expanded CMS optional translation tools end with a
  `Back to translation` action that returns keyboard focus to the real translation editor after a long
  detail-panel detour.
- Focused detail-handoff coverage now verifies that CMS detail names the optional drawer for the
  author’s current task, using `Translation help` while writing and `Review help` while approving
  instead of one generic tool label across both handoffs.
- Focused detail-handoff coverage now verifies that source-only CMS detail fallback keeps only source
  context instead of reopening optional translation tooling before the author has a target locale.
- Focused detail-handoff coverage now verifies that unavailable target-locale CMS detail links return
  to inline Product copy recovery instead of rendering an empty translation frame with workbench copy.
- Focused detail-handoff coverage now verifies that reloaded CMS detail links restore the author-facing
  Product copy breadcrumb and return to the exact inline locale from durable sanitized detail params
  instead of depending on router state.
- Focused authoring coverage now verifies that a durable Product copy locale return reopens and
  focuses the exact inline translation editor before clearing the transient locale query from the
  primary authoring URL.
- Focused authoring coverage now verifies that durable Product copy detail returns prefer the exact
  requested configured language over the default inline language, so a non-default detailed review
  cannot silently reopen the wrong editor.
- Focused authoring coverage now verifies that durable Product copy detail returns for a removed
  language land in inline recovery with the primary add-language action focused before the transient
  locale query is cleared.
- Focused authoring coverage now verifies that removed-language detail returns whose locale catalog
  cannot add that language keep recovery local to the field, focus `Try again`, and clear the
  transient locale query without reopening setup controls.
- Focused authoring coverage now verifies that removed-language detail returns stay local while the
  locale catalog is still loading, then move focus to the inline add or retry recovery once that
  catalog resolves without reopening setup controls.
- Focused authoring coverage now verifies that retrying a removed-language detail return can resolve
  back into the same field's inline add action, then reopen and focus that exact requested language
  editor after add-back instead of dropping the author into generic collection navigation.
- Focused authoring coverage now verifies that a removed-language detail return whose add-back save
  fails keeps the error and retry beside the same field, without reopening setup or release chrome,
  before a successful retry reopens the requested inline language editor.
- Focused authoring coverage now verifies that a removed-language detail return whose add-back
  conflicts keeps failed current-copy refresh and retry beside the same field, omits release-only
  wording and handoff, and reopens the requested inline language when that local refresh observes the
  concurrent add.
- Focused authoring coverage now verifies that when that local conflict refresh settles but the
  requested language is still absent, the same field refocuses `Add Japanese`, retries add-back
  without release chrome, and reopens the requested inline language after the second add succeeds.
- Focused authoring coverage now verifies that repeated removed-language detail-return add-back
  conflicts replace stale local recovery in the same field, keep `Add Japanese` focus when each
  refresh settles still missing, and never reopen release-only wording or handoff.
- Focused authoring coverage now verifies that when a repeated removed-language detail-return
  conflict refresh fails, the same field replaces both stale settled and active-refresh copy with
  local `Try again` recovery, keeps `Add Japanese` available, disables that add action while the local
  retry refreshes, keeps the same `Try again` handoff focused if that local retry also fails, refocuses
  the same add action when Japanese is still absent, disables duplicate add/retry actions while that
  local add-back later saves, refocuses honest same-field add recovery if that save fails, keeps a
  successful retry's Japanese reopen load failure and repeated inline `Try again` failure on that same
  local retry, shows only local opening state while that retry is pending, keeps a no-text-unit reopen
  on inline Japanese unavailable/reconnect recovery, disables duplicate reconnect while that local
  reconnect saves, refocuses honest same-field reconnect recovery if it fails, refreshes changed source
  copy without leaving that field if reconnect conflicts, holds that local conflict refresh on
  `Refreshing copy...` while duplicate reconnect stays disabled, keeps failed and repeated failed
  conflict refresh on the same focused local `Try again`, preserves source-copy and translator-note
  drafts typed while that refresh is pending behind save-first and `Saved elsewhere` recovery instead
  of silently adopting refreshed copy, lets either explicit saved-copy adoption after retry or an
  explicit draft save after retry reopen reconnect against the correct refreshed saved version,
  refocuses reconnect when retry adoption removes its stale compare action or draft save makes current
  copy current again, keeps a repeated dirty
  draft save conflict on that same field with refreshed `Saved elsewhere` comparison and no duplicate
  page alert, keeps a failed repeat conflict refresh on focused local `Try again` without losing that
  draft or Japanese return state, keeps a later ordinary draft-save failure on same-field author copy
  without a duplicate page alert or lost Japanese return, returns that ordinary save failure to source
  copy focus for immediate correction, returns a successful local conflict-refresh retry to source copy
  focus after its retry button disappears, reopens focused reconnect or failed refresh retry after
  field, language, content-item, or copy-collection round trips without leaking that local recovery
  into another item or collection, keeps that focused recovery in the workspace while copy-collection
  search filters its rail row, has no matches, or the mobile collection disclosure collapses, restores
  that focused recovery after canceling or discarding an exploratory new copy collection or content
  item, restores it after closing secondary item or placement details, and keeps stale ready-release
  handoff, selected-item release hint, plus checked release-card readiness out while an approved
  translation opens missing across field, language, or content-item detours until that exact field
  reloads healthy copy, routes the blocked release card back to each exact hidden field/language for
  retry after field, language, content-item, or copy-collection detours, after canceling or
  discarding an exploratory new copy collection or content item, after closing secondary item or
  placement details, and while copy-collection search filters the selected rail row, has no matches,
  or the mobile collection disclosure collapses instead of leaving a dead release warning or hiding
  sibling stale-ready blockers, keeps multi-stale release-card and selected-item summaries plural in
  author-facing refresh language with release readiness and the selected-item release strip announced
  politely, and does
  not leak that local release blocker into another copy collection that reuses authoring IDs while
  rebuilding it when the author returns to the original collection,
  preserves explicit source-copy return clicks, refocuses reconnect after current copy finally
  refreshes,
  reopens the requested inline Japanese editor when the next reconnect succeeds, and still omits
  release-only chrome.
- Focused authoring coverage now verifies that the failed repeated removed-language detail-return
  recovery stays on that same field after `Try again`: if Japanese appears concurrently, Product copy
  reopens and focuses the requested inline Japanese editor instead of asking for another add or routing
  the author back through release/setup chrome.
- Focused authoring and deterministic preview coverage now verifies that opening a normal inline
  translation task moves keyboard focus from the disappearing closed-row `Write` action into the real
  target translation editor, then tabs through the primary save, opt-in other actions, close, optional
  help, and next source-copy field without detouring through hidden or technical controls; that clean
  next-field handoff closes the prior inline editor before the author keeps writing, so Product copy
  does not leave authors stranded on `body` or trapped in the prior field.
- Focused authoring coverage now verifies that the inline detailed-review escape hatch follows the same
  task language, using `More translation help` while writing and `More review help` while approving
  instead of exposing one generic tool label before the author leaves the field.
- Focused authoring coverage now verifies that opening task-specific inline help reveals only the
  saved-translation detail link, not another `Optional` eyebrow or explanatory mini-panel before the
  author can leave the focused writing path.
- Focused authoring coverage now verifies that opening routine saved-item `Item details` exposes only
  content-item name and placement before save, keeping publish state, stable keys, variants, Mojito
  link controls, technical fields, and release internals out of the normal author path.
- Focused authoring coverage now verifies that stale author release conflicts stay in copy/release
  language, keeping raw publish-request, locale-scope, and current-CMS-data wording on the admin
  maintenance route instead of leaking it back into Product copy.
- Focused responsive stylesheet coverage now verifies that the sticky dirty-item `Save copy changes`
  bar stacks its copy and primary action at phone widths, keeping the normal author save path from
  inheriting desktop horizontal pressure on narrow screens.
- Focused responsive stylesheet coverage now verifies that the collapsed `Copy collections`
  switcher stretches at phone widths, so the folded rail keeps one clear author navigation action
  above the editor instead of shrinking back into a desktop-width disclosure.
- Focused responsive stylesheet coverage now verifies that failed copy-collection rail recovery
  stretches its `Try again` action at phone widths, so the author can recover the normal writing path
  without tapping a narrow desktop-width button in the collapsed rail.
- Focused responsive stylesheet coverage now verifies that stale saved-source and placement recovery
  notices stretch their `Use saved copy` / `Use saved placement details` action at phone widths, so
  conflict recovery keeps one clear author action after the compare cards collapse.
- Focused responsive stylesheet coverage now verifies that opened saved-item `Item details` stretches
  its live `.block-form` `Save copy details` action at phone widths, so the optional item-management
  escape hatch does not collapse back into a narrow desktop button when an author edits placement or
  item name.
- Focused responsive stylesheet coverage now verifies that the ready-release handoff stacks its copy,
  status, and `Remove from release` / `Go to release` actions at phone widths, so the normal release
  next step stays scannable instead of falling back to wrapped admin-console controls.
- Focused responsive stylesheet coverage now verifies that the author-facing `Release approved copy`
  action stretches inside the collapsed release panel at phone widths, while hidden admin readiness
  controls keep their separate maintenance layout outside the normal author path.
- Focused responsive stylesheet coverage now verifies that pristine clean-start and first/new content
  item action rows stack their primary and helper actions at phone widths, so `Start writing`, `Save
  first content item`, and `Use welcome email starter` do not compete across the first author screen.
- Focused responsive stylesheet coverage now verifies that clean/new content item `Add details`
  disclosures stretch at phone widths, so optional placement stays out of the writing path without
  collapsing back into a narrow desktop text control when an author opens it.
- Focused responsive stylesheet coverage now verifies that later content item `Content item details`
  and saved-field `Where this copy appears` disclosures stretch at phone widths, so optional
  placement remains secondary without becoming a narrow desktop-width escape hatch.
- Focused responsive stylesheet coverage now verifies that later content item action rows stack `Save
  content item` and `Cancel` at phone widths, so adding another content item keeps one clear author
  decision instead of wrapping desktop controls under the new-item form.
- Focused responsive stylesheet coverage now verifies that the Product copy workspace, editor headers,
  field list headers, and authoring form grids collapse to one column at phone widths, so the first
  viewport stays on writing instead of retaining desktop rail pressure.
- Focused responsive stylesheet coverage now verifies that saved-field author actions stretch after
  stacking at phone widths, so `Save source copy` and `Save source changes` stay clear primary
  actions instead of shrinking into narrow desktop-width controls beside field editing.
- Focused responsive stylesheet coverage now verifies that saved-field localization status rows stack
  and wrap at phone widths, so long translator-note hints, translation readiness text, and optional
  translation tools stay readable instead of borrowing desktop horizontal pressure.
- Focused responsive stylesheet coverage now verifies that the saved-item editor header stretches its
  `Write new content item` and `Item details` controls at phone widths, so the selected content item
  keeps one readable action column after the desktop header collapses.
- Focused responsive stylesheet coverage now verifies that source-only translation-language setup
  stretches its picker, `Add languages` action, and local retry state at phone widths, so the first
  localization handoff does not collapse back into narrow desktop controls before an author can choose
  where copy goes next.
- Focused responsive stylesheet coverage now verifies that inline translation `Switch language`,
  `Other actions`, and source-only `Search all languages` disclosures stretch at phone widths, so
  Mojito-backed language choices stay tappable without reopening desktop-width utility links.
- Focused responsive stylesheet coverage now verifies that optional detailed translation review links
  stretch at phone widths, so the rare Mojito handoff remains reachable without letting a narrow
  desktop text link become the only escape hatch from inline translation recovery.
- Focused responsive stylesheet coverage now verifies that inline translation retry, reconnect, and
  close rows stretch after stacking at phone widths, so rare author recovery states keep readable
  full-width actions instead of collapsing into narrow desktop-width buttons.
- Focused responsive stylesheet coverage now verifies that opened inline translation language choices
  stack full-width at phone widths, so the active localization path does not compress
  `French (France)`-style choices back into narrow desktop chips.
- Focused responsive stylesheet coverage now verifies that opened inline translation save actions stack
  full-width at phone widths, so `Save this translation` and its opt-in `Other actions` choices keep
  language-specific author decisions readable instead of wrapping inside desktop-width button rows.
- Focused responsive stylesheet coverage now verifies that opened inline translation next-step headers
  stack their action summary and saved status at phone widths, so `Save this translation` stays
  scannable before the author chooses a review state.
- Focused responsive stylesheet coverage now verifies that long author status pills wrap at phone
  widths, so `Translation status unavailable`-style states do not force the saved-item header wider
  than the author workspace.
- Focused responsive stylesheet coverage now verifies that language-specific release review rows stack
  their copy and long author action at phone widths, keeping `Review French (France)`-style actions
  from reintroducing admin-console horizontal pressure in the normal release path.
- Focused responsive stylesheet coverage now verifies that included release content items wrap and
  stretch at phone widths, so a long content-item name does not keep the author release card trapped
  in a desktop pill before the author can reopen that item.
- Focused responsive stylesheet coverage now verifies that the folded release-details summary stacks
  its count and `Show release details` action at phone widths, so the compact author release card does
  not regain desktop inline pressure before the author opts into the audit.
- Focused responsive stylesheet coverage now verifies that folded `Show release details` disclosure
  controls stretch at phone widths, so the author can reopen included-item and prior-release context
  without hunting for a narrow desktop text link inside a stacked readiness panel.
- Focused responsive stylesheet coverage now verifies that grouped release item, field, and blocker
  headers stack their labels and compact counts at phone widths, so long content-item names do not
  compete with release summary chrome in the normal blocked-release path.
- Focused responsive stylesheet coverage now verifies that folded release change and blocker
  disclosure controls stretch at phone widths, so `Show more changes`, `Load every change`, and
  hidden-issue recovery stay tappable without falling back to narrow desktop text links.
- Focused authoring and deterministic preview coverage now verifies that a closed configured
  translation handoff shows only the compact state badge (`Needs translation`, `Ready for review`, or
  `Approved for release`) plus the visible locale action (`Write French (France)`, `Review French
  (France)`, or `Open French (France)`), while the full translation action remains in the accessible
  button name and no passive `French (France) needs translation` sentence reopens console-style
  chrome.
- Focused authoring coverage now verifies that a dirty mapped source field says `Save source changes
  before translating` instead of `sync this draft`, while the visible `Save source changes` action and
  optional saved-translation link remain available for the author; when the translation editor is
  already open, the editor owns that save-first recovery without a duplicate passive status row.
- Focused preview-registry coverage now keeps both the seeded authoring walkthrough and named
  stale-recovery route addressable directly, so deterministic Product copy review does not rely on an
  implicit default render or only an invalid-query fallback.
- Focused author-route, deterministic desktop and phone browser, and preview coverage now keeps the
  real Product copy workspace plus every author-facing preview mode, including saved-content repair
  recovery and opened inline French translation editors, free of `Admin tools`, CMS, Mojito, schema,
  mapping, variant, publish, repository, package, snapshot, and JSON wording, so the normal Product
  copy route cannot quietly regress back into the failed admin-console framing in visible copy or
  accessible labels.
- Focused authoring and deterministic preview coverage now verifies that initial inline translation
  open/retry recovery drops the generic `Translation` eyebrow and status-console phrasing, says
  `Opening French (France) translation` or `French (France) translation could not open`, and keeps
  the retry button at `Try again` / `Opening...`.
- Focused selected-translation retry coverage now verifies that a loaded locale row which cannot open
  its translation says `French (France) translation could not load` plus `Try again to open it`
  instead of telling the author to `reopen` an implementation detail, with the deterministic
  `translation-load-error` preview keeping that active author state Browser-verifiable.
- Focused deleted-release-row coverage passed after making `FIELD_REMOVED` and
  `CONTENT_ITEM_REMOVED` exact diff rows informational instead of rendering dead `Open` actions:
  authors still see what disappeared from the next release, but the current authoring surface only
  offers reopen actions for copy that still exists to review or repair.
- Frontend `source webapp/use_local_npm.sh` plus `npm --prefix webapp/frontend run format`, `npm --prefix webapp/frontend run lint:fix`, `npm --prefix webapp/frontend run tsc`, focused `npm --prefix webapp/frontend run test -- src/api/content-cms.test.ts src/page/settings/AdminContentCmsPage.test.tsx src/page/settings/ContentCmsAuthoringPreview.test.tsx src/page/settings/content-cms-preview-mode.test.ts src/page/settings/content-cms-publish-intent.test.ts src/utils/textUnitDetailUrl.test.ts --fileParallelism=false`, and the standard full `npm --prefix webapp/frontend run test` passed with 233 focused tests and 311 full-suite tests after hardening removed-locale return recovery, the singular ready release handoff, exact release check/confirmation/in-flight/success/reload handoff, bounded exact stale-release snapshot diffs with an older-backend fallback, and their deterministic previews; only the existing npm `http-proxy` config warning remained.
- Focused `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=CmsContentServiceTest,CmsContentWSTest' test` passed with 282 tests, 0 failures/errors after project completeness began returning a bounded stale-release change summary from the validated latest published artifact and the REST contract serialized that additive field.
- Focused `mvn -pl webapp -am -Pno-local-config -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=CmsContentServiceIntegrationTest#getFieldTranslationOpensConfiguredUntranslatedTargetRow,CmsContentServiceTest,CmsContentWSTest' test` passed with 282 tests, 0 failures/errors after adding the CMS exact translation read; the new HSQL-backed integration case creates an author-first writing space with its own root target locale and proves the untranslated `fr-FR` row opens as `TRANSLATION_NEEDED` instead of disappearing behind the workbench root-locale filter.
- The two MySQL-only CMS lock assertions in `CmsContentServiceIntegrationTest` passed with 2 tests, 0 failures/errors against a disposable MySQL 8.0.34 Docker database using `-Pno-local-config`, explicit disposable datasource/Flyway overrides, `l10n.flyway.clean=false`, and `spring.flyway.clean-disabled=true`; the run did not read or clean the configured local Mojito database.
- A disposable MySQL 8.0.34 `spring.jpa.hibernate.ddl-auto=validate` probe with the same clean-disabled disposable datasource validated all 96 Flyway migrations, initialized Hibernate schema validation, and passed `CmsContentServiceIntegrationTest#publishProjectLockSerializesSameProjectSnapshotVersionAllocation` with 1 test, 0 failures/errors after aligning the pre-existing `application_cache.key_md5` `CHAR(32)` and `monitoring_text_unit_ingestion_state.id` `TINYINT` JPA mappings, removing the unused CMS snapshot index, and removing redundant content left-prefix indexes; `information_schema.statistics` confirmed the removed explicit content index names were absent from the realized MySQL schema, and the run did not read or clean the configured local Mojito database.
