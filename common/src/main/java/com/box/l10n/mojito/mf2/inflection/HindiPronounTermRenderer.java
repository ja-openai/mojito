package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.mf2.inflection.CompiledTermMetadataIndex.TermMetadata;
import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.HindiPronounAgreementPack;
import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.Request;
import java.util.Map;
import java.util.Objects;

/**
 * MF2-facing Hindi renderer for ordinary compiled terms plus dependency-aware pronoun agreement.
 */
class HindiPronounTermRenderer {

  private final CompiledTermPackRenderer termRenderer;
  private final CompiledTermMetadataIndex metadataIndex;
  private final HindiPronounAgreementPack pronounPack;
  private final TermUsageExtractor termUsageExtractor;

  HindiPronounTermRenderer(CompiledTermPack termPack, HindiPronounAgreementPack pronounPack) {
    this(termPack, pronounPack, new IcuMessage2TermUsageExtractor());
  }

  HindiPronounTermRenderer(
      CompiledTermPack termPack,
      HindiPronounAgreementPack pronounPack,
      TermUsageExtractor termUsageExtractor) {
    Objects.requireNonNull(termPack, "termPack");
    this.termUsageExtractor = Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");
    this.termRenderer = new CompiledTermPackRenderer(termPack, this.termUsageExtractor);
    this.metadataIndex = new CompiledTermMetadataIndex(termPack);
    this.pronounPack = Objects.requireNonNull(pronounPack, "pronounPack");
  }

  public String renderMessage(
      String message, Map<String, String> termArguments, Map<String, String> variables) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(termArguments, "termArguments");
    Objects.requireNonNull(variables, "variables");

    StringBuilder rendered = new StringBuilder();
    int cursor = 0;
    for (TermUsageExtractor.TermUsage usage : termUsageExtractor.extract(message)) {
      rendered.append(message, cursor, usage.start());
      try {
        rendered.append(renderUsage(usage, termArguments, variables));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(renderFailureMessage(usage, termArguments, e), e);
      }
      cursor = usage.end();
    }
    rendered.append(message.substring(cursor));
    return TermRenderRuntime.renderPattern(rendered.toString(), variables);
  }

  private String renderFailureMessage(
      TermUsageExtractor.TermUsage usage,
      Map<String, String> termArguments,
      IllegalArgumentException cause) {
    String termId = termArguments.get(usage.argument());
    String binding = termId == null ? "" : " bound to " + termId;
    return "Failed to render term argument "
        + usage.argument()
        + binding
        + " at span "
        + usage.span().format()
        + ": "
        + cause.getMessage();
  }

  private String renderUsage(
      TermUsageExtractor.TermUsage usage,
      Map<String, String> termArguments,
      Map<String, String> variables) {
    if (isPronounUsage(usage.options())) {
      return renderPronoun(usage, termArguments, variables);
    }
    String termId = termArguments.get(usage.argument());
    if (termId == null) {
      throw new IllegalArgumentException("Missing term argument: " + usage.argument());
    }
    return termRenderer.renderTerm(termId, usage.options(), variables);
  }

  private String renderPronoun(
      TermUsageExtractor.TermUsage usage,
      Map<String, String> termArguments,
      Map<String, String> variables) {
    HindiPronounTermOptions.validate(usage.options());

    String person = requiredPronounOption(usage, HindiPronounTermOptions.PERSON);
    String grammaticalCase = requiredPronounOption(usage, TermUsageOptions.CASE);
    String number =
        TermRenderRuntime.numberFromCountReference(
            usage.options().get(TermUsageOptions.COUNT), variables);
    String register = usage.options().get(HindiPronounTermOptions.REGISTER);
    Dependency dependency = dependency(usage, termArguments, variables, grammaticalCase);

    if ("second".equals(person) && register == null) {
      throw new IllegalArgumentException(
          "Hindi second-person pronoun usage requires register option: " + usage.argument());
    }
    if (!"second".equals(person) && register != null) {
      throw new IllegalArgumentException(
          "Hindi non-second pronoun usage cannot include register option: " + usage.argument());
    }

    return pronounPack.renderPronoun(
        new Request(
            person, number, grammaticalCase, register, dependency.gender(), dependency.number()));
  }

  private Dependency dependency(
      TermUsageExtractor.TermUsage usage,
      Map<String, String> termArguments,
      Map<String, String> variables,
      String grammaticalCase) {
    String agreeWith = usage.options().get(HindiPronounTermOptions.AGREE_WITH);
    String agreeWithCount = usage.options().get(HindiPronounTermOptions.AGREE_WITH_COUNT);
    if (!"genitive".equals(grammaticalCase)) {
      if (agreeWith != null || agreeWithCount != null) {
        throw new IllegalArgumentException(
            "Hindi non-genitive pronoun usage cannot include agreeWith options: "
                + usage.argument());
      }
      return Dependency.NONE;
    }
    if (agreeWith == null) {
      throw new IllegalArgumentException(
          "Hindi genitive pronoun usage requires agreeWith option: " + usage.argument());
    }

    String argument =
        HindiPronounTermOptions.variableReferenceName(
            agreeWith, HindiPronounTermOptions.AGREE_WITH);
    String termId = termArguments.get(argument);
    if (termId == null) {
      throw new IllegalArgumentException("Missing agreeWith term argument: " + argument);
    }

    TermMetadata metadata = metadataIndex.metadata(termId);
    String gender = metadata.gender();
    if (!"masculine".equals(gender) && !"feminine".equals(gender)) {
      throw new IllegalArgumentException(
          "Hindi genitive pronoun agreement requires masculine or feminine gender for term "
              + termId);
    }

    String dependencyNumber =
        agreeWithCount == null
            ? metadata.number()
            : numberFromCount(agreeWithCount, variables, HindiPronounTermOptions.AGREE_WITH_COUNT);
    if (!"singular".equals(dependencyNumber) && !"plural".equals(dependencyNumber)) {
      throw new IllegalArgumentException(
          "Hindi genitive pronoun agreement requires singular or plural number for term " + termId);
    }
    return new Dependency(gender, dependencyNumber);
  }

  private String requiredPronounOption(TermUsageExtractor.TermUsage usage, String option) {
    String value = usage.options().get(option);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Hindi pronoun usage requires " + option + " option: " + usage.argument());
    }
    return value;
  }

  private boolean isPronounUsage(Map<String, String> options) {
    return HindiPronounTermOptions.hasPronounMarker(options);
  }

  private String numberFromCount(
      String countReference, Map<String, String> variables, String optionName) {
    String countName = HindiPronounTermOptions.variableReferenceName(countReference, optionName);
    return TermRenderRuntime.numberFromVariable(
        countName, variables, missingVariableMessagePrefix(optionName));
  }

  private String missingVariableMessagePrefix(String optionName) {
    if (TermUsageOptions.COUNT.equals(optionName)) {
      return "Missing count variable: ";
    }
    return "Missing Hindi pronoun " + optionName + " variable: ";
  }

  private record Dependency(String gender, String number) {

    static final Dependency NONE = new Dependency(null, null);
  }
}
