package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.HindiPronounAgreementPack;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Locale-aware MF2 term renderer facade.
 *
 * <p>Product and REST integrations should prefer the schema-gated {@link
 * #requireRenderableBoundMessage(TermUsageCatalog, String)} and {@link
 * #renderBoundMessage(TermUsageCatalog, String, Map)} methods. The raw message and term rendering
 * methods remain available for focused pack tests, generator validation, and callers that already
 * own a validated closed-world binding boundary.
 */
public class Mf2TermRenderer {

  private final CompiledTermPackRenderer compiledTermRenderer;
  private final HindiPronounTermRenderer hindiPronounRenderer;
  private final Set<String> knownTermIds;
  private final TermUsageExtractor termUsageExtractor;
  private final TermBindingManifestValidator termBindingManifestValidator;

  private Mf2TermRenderer(
      CompiledTermPackRenderer compiledTermRenderer,
      HindiPronounTermRenderer hindiPronounRenderer,
      Set<String> knownTermIds,
      TermUsageExtractor termUsageExtractor) {
    this.compiledTermRenderer =
        Objects.requireNonNull(compiledTermRenderer, "compiledTermRenderer");
    this.hindiPronounRenderer = hindiPronounRenderer;
    this.knownTermIds = Set.copyOf(Objects.requireNonNull(knownTermIds, "knownTermIds"));
    this.termUsageExtractor = Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");
    this.termBindingManifestValidator = new TermBindingManifestValidator(termUsageExtractor);
  }

  public static Mf2TermRenderer forCompiledTerms(CompiledTermPack termPack) {
    return forCompiledTerms(termPack, new IcuMessage2TermUsageExtractor());
  }

  public static Mf2TermRenderer forCompiledTerms(
      CompiledTermPack termPack, TermUsageExtractor termUsageExtractor) {
    return new Mf2TermRenderer(
        new CompiledTermPackRenderer(termPack, termUsageExtractor),
        null,
        compiledTermIds(termPack),
        termUsageExtractor);
  }

  public static Mf2TermRenderer forHindi(
      CompiledTermPack termPack, HindiPronounAgreementPack pronounPack) {
    return forHindi(termPack, pronounPack, new IcuMessage2TermUsageExtractor());
  }

  public static Mf2TermRenderer forHindi(
      CompiledTermPack termPack,
      HindiPronounAgreementPack pronounPack,
      TermUsageExtractor termUsageExtractor) {
    Objects.requireNonNull(termPack, "termPack");
    Objects.requireNonNull(pronounPack, "pronounPack");
    requireLocale(termPack.locale(), "hi", "Hindi pronoun rendering requires Hindi term pack");
    requireLocale(
        pronounPack.locale(), "hi", "Hindi pronoun rendering requires Hindi pronoun pack");
    return new Mf2TermRenderer(
        new CompiledTermPackRenderer(termPack, termUsageExtractor),
        new HindiPronounTermRenderer(termPack, pronounPack, termUsageExtractor),
        compiledTermIds(termPack),
        termUsageExtractor);
  }

  /**
   * Validates that one schema-gated message binding manifest entry can render against this
   * renderer's compiled term pack.
   *
   * <p>This check is intentionally independent from runtime sample variables, so messages such as
   * `count=$count` must have every statically implied selector alternative before preview values
   * can select a singular or plural branch.
   */
  public void requireRenderableBoundMessage(TermUsageCatalog usageCatalog, String messageId) {
    Objects.requireNonNull(usageCatalog, "usageCatalog");
    Objects.requireNonNull(messageId, "messageId");
    bindRenderableMessage(usageCatalog, messageId);
  }

  /**
   * Renders one schema-gated message binding manifest entry.
   *
   * <p>The manifest must identify exactly one compiled term ID for every term-binding argument used
   * by the message. Catalogs that list multiple authorized terms for an argument are valid for
   * preflight validation, but callers must narrow them to a row-level singleton manifest before
   * rendering.
   */
  public String renderBoundMessage(
      TermUsageCatalog usageCatalog, String messageId, Map<String, String> variables) {
    Objects.requireNonNull(usageCatalog, "usageCatalog");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(variables, "variables");
    validateVariableMap(variables);
    TermRenderRuntime.BoundMessage boundMessage = bindRenderableMessage(usageCatalog, messageId);
    return renderMessage(boundMessage.message(), boundMessage.termArguments(), variables);
  }

  private TermRenderRuntime.BoundMessage bindRenderableMessage(
      TermUsageCatalog usageCatalog, String messageId) {
    TermUsageCatalog messageCatalog =
        TermRenderRuntime.singleMessageCatalog(usageCatalog, messageId);
    TermBindingManifestValidator.requireRenderable(
        termBindingManifestValidator.validate(messageCatalog, knownTermIds));
    TermRenderRuntime.BoundMessage boundMessage =
        TermRenderRuntime.bindMessage(messageCatalog, messageId, termUsageExtractor);
    compiledTermRenderer.requireRenderableMessage(
        messageId, boundMessage.message(), boundMessage.termArguments());
    return boundMessage;
  }

  public String renderMessage(
      String message, Map<String, String> termArguments, Map<String, String> variables) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(termArguments, "termArguments");
    Objects.requireNonNull(variables, "variables");
    validateNonBlankMap(termArguments, "termArguments");
    validateVariableMap(variables);
    if (hindiPronounRenderer != null) {
      return hindiPronounRenderer.renderMessage(message, termArguments, variables);
    }
    return compiledTermRenderer.renderMessage(message, termArguments, variables);
  }

  public String renderTerm(
      String termId, Map<String, String> options, Map<String, String> variables) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(variables, "variables");
    requireNonBlank(termId, "termId");
    validateNonBlankMap(options, "options");
    validateVariableMap(variables);
    return compiledTermRenderer.renderTerm(termId, options, variables);
  }

  private static void validateVariableMap(Map<String, String> values) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      requireNonBlank(entry.getKey(), "variables key");
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("variables value must not be null: " + entry.getKey());
      }
    }
  }

  private static void validateNonBlankMap(Map<String, String> values, String field) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      requireNonBlank(entry.getKey(), field + " key");
      if (entry.getValue() == null || entry.getValue().isBlank()) {
        throw new IllegalArgumentException(field + " value must not be blank: " + entry.getKey());
      }
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requireLocale(String locale, String expected, String message) {
    if (locale == null
        || !(expected.equals(locale)
            || locale.startsWith(expected + "-")
            || locale.startsWith(expected + "_"))) {
      throw new IllegalArgumentException(message + ": " + locale);
    }
  }

  private static Set<String> compiledTermIds(CompiledTermPack termPack) {
    Objects.requireNonNull(termPack, "termPack");
    Set<String> termIds = new LinkedHashSet<>();
    for (TermRow term : termPack.terms()) {
      termIds.add(termPack.strings().get(term.id()));
    }
    return Collections.unmodifiableSet(termIds);
  }
}
