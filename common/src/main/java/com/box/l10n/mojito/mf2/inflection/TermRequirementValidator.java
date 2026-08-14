package com.box.l10n.mojito.mf2.inflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts `:term` requirements from MF2 messages and validates them against readable term
 * metadata.
 *
 * <p>This class is a build-time and authoring-time validator. Runtime rendering should use {@link
 * Mf2TermRenderer} with a compiled pack and, when possible, a schema-gated {@link
 * TermRequirementJsonLoader.TermUsageCatalog}.
 */
public class TermRequirementValidator {

  private static final String SPANISH_LOCALE = "es";
  private static final String ITALIAN_LOCALE = "it";
  private static final String PORTUGUESE_LOCALE = "pt";
  private static final String TURKISH_LOCALE = "tr";
  static final String UNSUPPORTED_RUNTIME_TERM_INFLECTION =
      "unsupported-locale-runtime-term-inflection";
  private static final Set<String> RUNTIME_TERM_INFLECTION_UNSUPPORTED_LOCALES =
      Set.of("en", "id", "ja", "ko", "ms", "nb", "nl", "th", "vi", "yue", "zh");
  private static final Set<String> TURKISH_SUFFIX_CASES =
      Set.of("ablative", "accusative", "dative", "locative", "nominative");

  private final TermUsageExtractor termUsageExtractor;

  public TermRequirementValidator() {
    this(new IcuMessage2TermUsageExtractor());
  }

  public TermRequirementValidator(TermUsageExtractor termUsageExtractor) {
    this.termUsageExtractor = Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");
  }

  public List<TermUsageRequirement> requirementsForMessage(String message) {
    return requirementsForMessage(message, null);
  }

  public List<TermUsageRequirement> requirementsForMessage(String message, String locale) {
    Objects.requireNonNull(message, "message");
    List<TermUsageRequirement> requirements = new ArrayList<>();
    for (TermUsageExtractor.TermUsage usage : termUsageExtractor.extract(message)) {
      requirements.add(
          new TermUsageRequirement(
              usage.argument(),
              usage.options(),
              usage.span(),
              requirementsForOptions(locale, usage.options()),
              List.of(),
              List.of()));
    }
    return List.copyOf(requirements);
  }

  public TermRequirementReport validate(
      Map<String, String> messages,
      Map<String, Map<String, List<String>>> argumentTerms,
      Map<String, Term> terms) {
    return validate(messages, argumentTerms, terms, ValidationMode.CLOSED_WORLD);
  }

  public TermRequirementReport validate(
      Map<String, String> messages,
      Map<String, Map<String, List<String>>> argumentTerms,
      Map<String, Term> terms,
      ValidationMode validationMode) {
    return validate(null, messages, argumentTerms, terms, validationMode);
  }

  public TermRequirementReport validate(
      String locale,
      Map<String, String> messages,
      Map<String, Map<String, List<String>>> argumentTerms,
      Map<String, Term> terms) {
    return validate(locale, messages, argumentTerms, terms, ValidationMode.CLOSED_WORLD);
  }

  public TermRequirementReport validate(
      String locale,
      Map<String, String> messages,
      Map<String, Map<String, List<String>>> argumentTerms,
      Map<String, Term> terms,
      ValidationMode validationMode) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(argumentTerms, "argumentTerms");
    Objects.requireNonNull(terms, "terms");
    Objects.requireNonNull(validationMode, "validationMode");
    Map<String, MessageRequirement> messageRequirements = new LinkedHashMap<>();
    List<Diagnostic> diagnostics = new ArrayList<>();
    int termUsages = 0;

    for (Map.Entry<String, String> messageEntry : messages.entrySet()) {
      String messageId = messageEntry.getKey();
      String message = messageEntry.getValue();
      List<TermUsageRequirement> usageRequirements = new ArrayList<>();

      for (TermUsageExtractor.TermUsage usage : termUsageExtractor.extract(message)) {
        SourceSpan span = usage.span();
        boolean hindiPronounUsage = isHindiPronounUsage(locale, usage.options());
        List<String> requirements = requirementsForOptions(locale, usage.options());
        boolean unsupportedRuntimeTermInflection =
            requirements.contains(UNSUPPORTED_RUNTIME_TERM_INFLECTION);
        List<String> termIds =
            hindiPronounUsage
                ? List.of()
                : argumentTerms
                    .getOrDefault(messageId, Map.of())
                    .getOrDefault(usage.argument(), List.of());
        List<TermValidation> validations = new ArrayList<>();

        if (unsupportedRuntimeTermInflection) {
          diagnostics.add(
              new Diagnostic(
                  messageId,
                  usage.argument(),
                  null,
                  span,
                  List.of(UNSUPPORTED_RUNTIME_TERM_INFLECTION)));
        } else if (hindiPronounUsage) {
          validateHindiPronounUsage(
              messageId,
              usage,
              span,
              argumentTerms.getOrDefault(messageId, Map.of()),
              terms,
              validationMode,
              diagnostics);
        } else if (termIds.isEmpty() && validationMode == ValidationMode.CLOSED_WORLD) {
          diagnostics.add(
              new Diagnostic(
                  messageId, usage.argument(), null, span, List.of("missing-argument-terms")));
        }

        if (!unsupportedRuntimeTermInflection) {
          for (String termId : termIds) {
            List<String> missing = missingRequirements(terms.get(termId), requirements);
            TermValidationStatus status =
                missing.isEmpty() ? TermValidationStatus.OK : TermValidationStatus.MISSING;
            validations.add(new TermValidation(termId, status, missing));
            if (!missing.isEmpty()) {
              diagnostics.add(new Diagnostic(messageId, usage.argument(), termId, span, missing));
            }
          }
        }

        usageRequirements.add(
            new TermUsageRequirement(
                usage.argument(),
                usage.options(),
                span,
                requirements,
                termIds,
                List.copyOf(validations)));
      }

      termUsages += usageRequirements.size();
      messageRequirements.put(
          messageId, new MessageRequirement(message, List.copyOf(usageRequirements)));
    }

    return new TermRequirementReport(
        messageRequirements,
        List.copyOf(diagnostics),
        new Summary(messages.size(), termUsages, diagnostics.size()));
  }

  public List<String> requirementsForOptions(Map<String, String> options) {
    return requirementsForOptions(null, options);
  }

  public List<String> requirementsForOptions(String locale, Map<String, String> options) {
    Objects.requireNonNull(options, "options");
    if (isHindiPronounUsage(locale, options)) {
      return requirementsForHindiPronounOptions(options);
    }

    TermUsageOptions.validate(options);
    if (requiresUnsupportedRuntimeTermInflectionDiagnostic(locale, options)) {
      return List.of(UNSUPPORTED_RUNTIME_TERM_INFLECTION);
    }

    List<String> requirements = new ArrayList<>();
    requirements.add("partOfSpeech=noun");
    if (!isTurkishLocale(locale)) {
      requirements.add("gender");
    }
    requirements.add("number");

    String article = options.get(TermUsageOptions.ARTICLE);
    String grammaticalCase = options.get(TermUsageOptions.CASE);
    String definiteness = options.get(TermUsageOptions.DEFINITENESS);
    String explicitNumber = options.get(TermUsageOptions.NUMBER);
    String preposition = options.get(TermUsageOptions.PREPOSITION);
    List<String> selectedNumbers =
        explicitNumber == null ? List.of("singular", "plural") : List.of(explicitNumber);
    boolean turkishSuffixComposition = isTurkishSuffixComposition(locale, options);
    boolean spanishArticleComposition =
        isSpanishArticleComposition(locale, article, preposition, grammaticalCase);
    boolean italianArticleComposition =
        isItalianArticleComposition(locale, article, preposition, grammaticalCase);
    boolean portugueseArticleComposition =
        isPortugueseArticleComposition(locale, article, preposition, grammaticalCase);
    if (turkishSuffixComposition) {
      requirements.add("turkishSuffix.vowelEnd");
      requirements.add("turkishSuffix.frontVowel");
      requirements.add("turkishSuffix.roundedVowel");
      requirements.add("turkishSuffix.hardConsonant");
      requirements.add("forms.bare.singular");
    } else if (spanishArticleComposition) {
      requirements.add("stress");
      requirements.add("forms.bare.singular");
      requirements.add("forms.bare.plural");
    } else if (italianArticleComposition) {
      requirements.add("articleClass");
      requirements.add("forms.bare.singular");
      requirements.add("forms.bare.plural");
    } else if (portugueseArticleComposition) {
      requirements.add("forms.bare.singular");
      requirements.add("forms.bare.plural");
    } else if (preposition != null) {
      for (String number : selectedNumbers) {
        requirements.add("forms.preposition." + preposition + "." + article + "." + number);
      }
    } else if (definiteness != null && grammaticalCase != null) {
      for (String number : selectedNumbers) {
        requirements.add("forms." + definiteness + "." + grammaticalCase + "." + number);
      }
    } else if (definiteness != null) {
      for (String number : selectedNumbers) {
        requirements.add("forms." + definiteness + "." + number);
      }
    } else if (article != null && grammaticalCase != null) {
      for (String number : selectedNumbers) {
        requirements.add("forms." + article + "." + grammaticalCase + "." + number);
      }
    } else if ("definite".equals(article) || "indefinite".equals(article)) {
      requirements.add("elision");
      for (String number : selectedNumbers) {
        requirements.add("forms." + article + "." + number);
      }
    } else if (grammaticalCase != null) {
      for (String number : selectedNumbers) {
        requirements.add("forms." + grammaticalCase + "." + number);
      }
    } else if (explicitNumber != null) {
      requirements.add("forms.bare." + explicitNumber);
    }

    if (options.containsKey(TermUsageOptions.COUNT)
        && !spanishArticleComposition
        && !italianArticleComposition
        && !portugueseArticleComposition
        && !turkishSuffixComposition) {
      requirements.add("forms.count.one");
      requirements.add("forms.count.other");
    }

    if (article == null
        && grammaticalCase == null
        && definiteness == null
        && explicitNumber == null
        && preposition == null
        && !options.containsKey(TermUsageOptions.COUNT)) {
      requirements.add("forms.bare.singular");
      requirements.add("forms.bare.plural");
    }

    return List.copyOf(requirements);
  }

  private List<String> requirementsForHindiPronounOptions(Map<String, String> options) {
    validateHindiPronounOptionSyntax(options);

    List<String> requirements = new ArrayList<>();
    requirements.add("hindiPronoun.person");
    requirements.add("hindiPronoun.case");
    if (options.containsKey(TermUsageOptions.COUNT)) {
      requirements.add("hindiPronoun.number");
    }
    if ("second".equals(options.get(HindiPronounTermOptions.PERSON))
        || options.containsKey(HindiPronounTermOptions.REGISTER)) {
      requirements.add("hindiPronoun.register");
    }
    if ("genitive".equals(options.get(TermUsageOptions.CASE))) {
      requirements.add("agreeWith.gender");
      if (options.containsKey(HindiPronounTermOptions.AGREE_WITH_COUNT)) {
        requirements.add("agreeWith.count");
      } else {
        requirements.add("agreeWith.number");
      }
    }
    return List.copyOf(requirements);
  }

  private void validateHindiPronounOptionSyntax(Map<String, String> options) {
    HindiPronounTermOptions.validate(options);
  }

  private void validateHindiPronounUsage(
      String messageId,
      TermUsageExtractor.TermUsage usage,
      SourceSpan span,
      Map<String, List<String>> messageArgumentTerms,
      Map<String, Term> terms,
      ValidationMode validationMode,
      List<Diagnostic> diagnostics) {
    Map<String, String> options = usage.options();
    String person = options.get(HindiPronounTermOptions.PERSON);
    String grammaticalCase = options.get(TermUsageOptions.CASE);
    String register = options.get(HindiPronounTermOptions.REGISTER);
    String agreeWith = options.get(HindiPronounTermOptions.AGREE_WITH);
    String agreeWithCount = options.get(HindiPronounTermOptions.AGREE_WITH_COUNT);

    List<String> pronounMissing = new ArrayList<>();
    if (person == null) {
      pronounMissing.add("person");
    }
    if (grammaticalCase == null) {
      pronounMissing.add("case");
    }
    if ("second".equals(person) && register == null) {
      pronounMissing.add("register");
    }
    if (person != null && !"second".equals(person) && register != null) {
      pronounMissing.add("register.unexpected");
    }
    if (!pronounMissing.isEmpty()) {
      diagnostics.add(new Diagnostic(messageId, usage.argument(), null, span, pronounMissing));
    }

    if (grammaticalCase == null) {
      return;
    }
    if (!"genitive".equals(grammaticalCase)) {
      if (agreeWith != null || agreeWithCount != null) {
        diagnostics.add(
            new Diagnostic(
                messageId, usage.argument(), null, span, List.of("agreeWith.unexpected")));
      }
      return;
    }
    if (agreeWith == null) {
      diagnostics.add(
          new Diagnostic(messageId, usage.argument(), null, span, List.of("agreeWith")));
      return;
    }

    String referencedArgument =
        HindiPronounTermOptions.variableReferenceName(
            agreeWith, HindiPronounTermOptions.AGREE_WITH);
    List<String> referencedTermIds =
        messageArgumentTerms.getOrDefault(referencedArgument, List.of());
    if (referencedTermIds.isEmpty()) {
      if (validationMode == ValidationMode.CLOSED_WORLD) {
        diagnostics.add(
            new Diagnostic(
                messageId,
                usage.argument(),
                null,
                span,
                referencedArgument,
                List.of("agreeWith.missing-argument-terms")));
      }
      return;
    }

    for (String referencedTermId : referencedTermIds) {
      List<String> missing =
          missingHindiPronounAgreementRequirements(
              terms.get(referencedTermId), agreeWithCount != null);
      if (!missing.isEmpty()) {
        diagnostics.add(
            new Diagnostic(
                messageId, usage.argument(), referencedTermId, span, referencedArgument, missing));
      }
    }
  }

  private List<String> missingHindiPronounAgreementRequirements(
      Term referencedTerm, boolean hasRuntimeAgreementCount) {
    if (referencedTerm == null) {
      return List.of("agreeWith.missing-term");
    }

    List<String> missing = new ArrayList<>();
    Morphology morphology = referencedTerm.morphology();
    String gender = morphology == null ? null : morphology.gender();
    if (!"masculine".equals(gender) && !"feminine".equals(gender)) {
      missing.add("agreeWith.gender");
    }
    String number = morphology == null ? null : morphology.number();
    if (!hasRuntimeAgreementCount && !"singular".equals(number) && !"plural".equals(number)) {
      missing.add("agreeWith.number");
    }
    return List.copyOf(missing);
  }

  private boolean isHindiPronounUsage(String locale, Map<String, String> options) {
    return HindiPronounTermOptions.isPronounUsage(locale, options);
  }

  private boolean requiresUnsupportedRuntimeTermInflectionDiagnostic(
      String locale, Map<String, String> options) {
    return !options.isEmpty()
        && RUNTIME_TERM_INFLECTION_UNSUPPORTED_LOCALES.contains(primaryLocaleSubtag(locale));
  }

  private String primaryLocaleSubtag(String locale) {
    if (locale == null || locale.isBlank()) {
      return "";
    }
    String normalized = locale.toLowerCase(Locale.ROOT);
    int hyphen = normalized.indexOf('-');
    int underscore = normalized.indexOf('_');
    int end;
    if (hyphen < 0) {
      end = underscore;
    } else if (underscore < 0) {
      end = hyphen;
    } else {
      end = Math.min(hyphen, underscore);
    }
    return end < 0 ? normalized : normalized.substring(0, end);
  }

  private boolean isTurkishSuffixComposition(String locale, Map<String, String> options) {
    String grammaticalCase = options.get(TermUsageOptions.CASE);
    return isTurkishLocale(locale)
        && options.get(TermUsageOptions.ARTICLE) == null
        && options.get(TermUsageOptions.PREPOSITION) == null
        && (options.containsKey(TermUsageOptions.COUNT)
            ? grammaticalCase == null || TURKISH_SUFFIX_CASES.contains(grammaticalCase)
            : TURKISH_SUFFIX_CASES.contains(grammaticalCase));
  }

  private boolean isTurkishLocale(String locale) {
    return TURKISH_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("tr-") || locale.startsWith("tr_")));
  }

  private boolean isSpanishArticleComposition(
      String locale, String article, String preposition, String grammaticalCase) {
    return isSpanishLocale(locale)
        && grammaticalCase == null
        && preposition == null
        && ("definite".equals(article) || "indefinite".equals(article));
  }

  private boolean isSpanishLocale(String locale) {
    return SPANISH_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("es-") || locale.startsWith("es_")));
  }

  private boolean isItalianArticleComposition(
      String locale, String article, String preposition, String grammaticalCase) {
    return isItalianLocale(locale)
        && grammaticalCase == null
        && preposition == null
        && ("definite".equals(article) || "indefinite".equals(article));
  }

  private boolean isItalianLocale(String locale) {
    return ITALIAN_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("it-") || locale.startsWith("it_")));
  }

  private boolean isPortugueseArticleComposition(
      String locale, String article, String preposition, String grammaticalCase) {
    return isPortugueseLocale(locale)
        && grammaticalCase == null
        && TermUsageOptions.isPortuguesePrepositionArticleCombination(preposition, article);
  }

  private boolean isPortugueseLocale(String locale) {
    return PORTUGUESE_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("pt-") || locale.startsWith("pt_")));
  }

  public List<String> missingRequirements(Term term, List<String> requirements) {
    Objects.requireNonNull(requirements, "requirements");
    if (term == null) {
      return List.of("missing-term");
    }

    List<String> missing = new ArrayList<>();
    for (String requirement : requirements) {
      String missingRequirement = missingRequirement(term, requirement);
      if (missingRequirement != null) {
        missing.add(missingRequirement);
      }
    }
    return List.copyOf(missing);
  }

  private String missingRequirement(Term term, String requirement) {
    Morphology morphology = term.morphology();
    if ("partOfSpeech=noun".equals(requirement)) {
      return morphology != null && "noun".equals(morphology.partOfSpeech()) ? null : "partOfSpeech";
    }
    if ("gender".equals(requirement)) {
      return morphology != null && hasText(morphology.gender()) ? null : "gender";
    }
    if ("number".equals(requirement)) {
      return morphology != null && hasText(morphology.number()) ? null : "number";
    }
    if ("stress".equals(requirement)) {
      return morphology != null && morphology.stressed() != null ? null : "stressed";
    }
    if ("articleClass".equals(requirement)) {
      return morphology != null && hasText(morphology.articleClass()) ? null : "articleClass";
    }
    if ("elision".equals(requirement)) {
      return morphology != null && morphology.startsWithVowelSound() != null
          ? null
          : "startsWithVowelSound";
    }
    if (requirement.startsWith("turkishSuffix.")) {
      String field = requirement.substring("turkishSuffix.".length());
      TurkishSuffix turkishSuffix = morphology == null ? null : morphology.turkishSuffix();
      return hasTurkishSuffixField(turkishSuffix, field) ? null : field;
    }
    if (requirement.startsWith("forms.")) {
      String formKey = requirement.substring("forms.".length());
      return hasText(term.forms().get(formKey)) ? null : formKey;
    }
    return requirement;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private boolean hasTurkishSuffixField(TurkishSuffix turkishSuffix, String field) {
    if (turkishSuffix == null) {
      return false;
    }
    return switch (field) {
      case "vowelEnd" -> turkishSuffix.vowelEnd() != null;
      case "frontVowel" -> turkishSuffix.frontVowel() != null;
      case "roundedVowel" -> turkishSuffix.roundedVowel() != null;
      case "hardConsonant" -> turkishSuffix.hardConsonant() != null;
      default -> false;
    };
  }

  public record TermRequirementReport(
      Map<String, MessageRequirement> messages, List<Diagnostic> diagnostics, Summary summary) {

    public TermRequirementReport {
      messages = unmodifiableLinkedMap(Objects.requireNonNull(messages, "messages"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      summary = Objects.requireNonNull(summary, "summary");
    }
  }

  public record MessageRequirement(String source, List<TermUsageRequirement> termUsages) {

    public MessageRequirement {
      source = Objects.requireNonNull(source, "source");
      termUsages = List.copyOf(Objects.requireNonNull(termUsages, "termUsages"));
    }
  }

  public record TermUsageRequirement(
      String argument,
      Map<String, String> options,
      SourceSpan span,
      List<String> requirements,
      List<String> termIds,
      List<TermValidation> validations) {

    public TermUsageRequirement {
      requireText(argument, "argument");
      options = unmodifiableLinkedMap(Objects.requireNonNull(options, "options"));
      span = Objects.requireNonNull(span, "span");
      requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
      termIds = List.copyOf(Objects.requireNonNull(termIds, "termIds"));
      validations = List.copyOf(Objects.requireNonNull(validations, "validations"));
    }
  }

  public record TermValidation(String termId, TermValidationStatus status, List<String> missing) {

    public TermValidation {
      requireText(termId, "termId");
      status = Objects.requireNonNull(status, "status");
      missing = List.copyOf(Objects.requireNonNull(missing, "missing"));
    }
  }

  public enum TermValidationStatus {
    OK,
    MISSING
  }

  public enum ValidationMode {
    CLOSED_WORLD,
    OPEN_WORLD
  }

  public record Diagnostic(
      String messageId,
      String argument,
      String termId,
      SourceSpan span,
      String relatedArgument,
      List<String> missing) {

    public Diagnostic(
        String messageId, String argument, String termId, SourceSpan span, List<String> missing) {
      this(messageId, argument, termId, span, null, missing);
    }

    public Diagnostic {
      requireText(messageId, "messageId");
      requireText(argument, "argument");
      if (relatedArgument != null) {
        requireText(relatedArgument, "relatedArgument");
      }
      span = Objects.requireNonNull(span, "span");
      missing = List.copyOf(Objects.requireNonNull(missing, "missing"));
    }
  }

  public record Summary(int messages, int termUsages, int diagnostics) {

    public Summary {
      if (messages < 0 || termUsages < 0 || diagnostics < 0) {
        throw new IllegalArgumentException("Summary counts must be non-negative");
      }
    }
  }

  public record Term(String text, Morphology morphology, Map<String, String> forms) {

    public Term {
      requireText(text, "term text");
      forms = unmodifiableTextMap(Objects.requireNonNull(forms, "forms"), "form key", "form value");
    }
  }

  public record Morphology(
      String partOfSpeech,
      String gender,
      String number,
      Boolean startsWithVowelSound,
      Boolean stressed,
      String articleClass,
      String sense,
      TurkishSuffix turkishSuffix) {

    public Morphology(
        String partOfSpeech,
        String gender,
        String number,
        Boolean startsWithVowelSound,
        String sense) {
      this(partOfSpeech, gender, number, startsWithVowelSound, null, null, sense, null);
    }

    public Morphology(
        String partOfSpeech,
        String gender,
        String number,
        Boolean startsWithVowelSound,
        Boolean stressed,
        String sense) {
      this(partOfSpeech, gender, number, startsWithVowelSound, stressed, null, sense, null);
    }

    public Morphology(
        String partOfSpeech,
        String gender,
        String number,
        Boolean startsWithVowelSound,
        Boolean stressed,
        String articleClass,
        String sense) {
      this(partOfSpeech, gender, number, startsWithVowelSound, stressed, articleClass, sense, null);
    }
  }

  public record TurkishSuffix(
      Boolean vowelEnd, Boolean frontVowel, Boolean roundedVowel, Boolean hardConsonant) {}

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private static <K, V> Map<K, V> unmodifiableLinkedMap(Map<K, V> values) {
    Map<K, V> copied = new LinkedHashMap<>();
    for (Map.Entry<K, V> entry : values.entrySet()) {
      copied.put(
          Objects.requireNonNull(entry.getKey(), "map key"),
          Objects.requireNonNull(entry.getValue(), "map value"));
    }
    return Collections.unmodifiableMap(copied);
  }

  private static Map<String, String> unmodifiableTextMap(
      Map<String, String> values, String keyField, String valueField) {
    Map<String, String> copied = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      copied.put(
          requireTextValue(entry.getKey(), keyField),
          requireTextValue(entry.getValue(), valueField));
    }
    return Collections.unmodifiableMap(copied);
  }

  private static String requireTextValue(String value, String field) {
    requireText(value, field);
    return value;
  }
}
