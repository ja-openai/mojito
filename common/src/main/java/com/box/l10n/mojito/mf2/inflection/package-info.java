/**
 * MF2 term-inflection runtime and tooling support.
 *
 * <p>The stable runtime/model surface is intentionally small:
 *
 * <ul>
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.CompiledTermPack}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.CompiledTermPackJsonLoader}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.CompiledTermPackBinaryCodec}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.Mf2TermRenderer}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.SourceSpan}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermUsageExtractor}
 * </ul>
 *
 * <p>The public authoring/tooling surface is schema-gated and supports build-time or REST
 * validation:
 *
 * <ul>
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermRequirementValidator}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermRequirementReportJsonWriter}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermBindingManifestReportJsonWriter}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.TermInflectionDiagnostics}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader}
 *   <li>{@link com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator}
 * </ul>
 *
 * <p>No CLI entry point is published from this package. Java/common release validation remains an
 * API-only boundary that validates release artifact payloads, duplicate artifact IDs, manifest
 * shape, relative real-path containment, deterministic JSON reports, and the compiled
 * JSON/M2IF/Hindi sidecar schemas. The shared MF2 conformance wrapper owns the fixture-specific
 * source filename pinning contract for executable release gates until a named build or product
 * caller justifies a package-local command.
 *
 * <p>The Java/common runtime is the checked V0 surface, not complete locale or grammar coverage.
 * Runtime rendering is limited to the locale and grammar slices represented by the reviewed
 * compiled packs. Metadata/profile-only locales remain validation-only until a product caller
 * promotes a reviewed runtime path. Locales absent from the current source-data survey, including
 * Polish in the pinned Unicode Inflection checkout, are source-data acquisition work rather than
 * Java/common runtime coverage.
 *
 * <p>Locale-specific audit, report, survey, and source-data helpers marked with
 * {@code @GeneratorSupport} are internal generator support. They must remain package-private and
 * compile down to {@link com.box.l10n.mojito.mf2.inflection.CompiledTermPack} before runtime use.
 * Profile-only/no-op pronoun metadata, including {@code PronounProfilePackJsonLoader}, follows that
 * generator-support rule until a concrete authoring feature needs a stable public metadata
 * artifact.
 */
package com.box.l10n.mojito.mf2.inflection;
