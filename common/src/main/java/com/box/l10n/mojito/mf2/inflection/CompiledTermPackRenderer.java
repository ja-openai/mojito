package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Low-level renderer for messages from a closed-world compiled term pack.
 *
 * <p>This class owns form selection, composition, and compact-pack validation. Product and REST
 * callers use {@link Mf2TermRenderer}, which adds schema-gated binding-manifest validation and
 * locale extensions before delegating here. Direct calls are limited to package-local pack tests,
 * generators, and integration helpers that already provide validated term IDs and runtime
 * variables. Missing terms or forms fail instead of falling back to dictionary inference.
 */
class CompiledTermPackRenderer {

  private static final String SPANISH_LOCALE = "es";
  private static final String ITALIAN_LOCALE = "it";
  private static final String PORTUGUESE_LOCALE = "pt";
  private static final String TURKISH_LOCALE = "tr";
  private static final int GENDER_SHIFT = 4;
  private static final int GENDER_MASK = 0xF;
  private static final int MASCULINE_GENDER = 1;
  private static final int FEMININE_GENDER = 2;
  private static final int STRESSED_BIT = 1 << 14;
  private static final int ARTICLE_CLASS_SHIFT = 16;
  private static final int ARTICLE_CLASS_MASK = 0xF;
  private static final int STANDARD_ARTICLE_CLASS = 1;
  private static final int LO_ARTICLE_CLASS = 2;
  private static final int ELISION_ARTICLE_CLASS = 3;
  private static final int TURKISH_SUFFIX_METADATA_BIT = 1 << 20;
  private static final int TURKISH_VOWEL_END_BIT = 1 << 21;
  private static final int TURKISH_FRONT_VOWEL_BIT = 1 << 22;
  private static final int TURKISH_ROUNDED_VOWEL_BIT = 1 << 23;
  private static final int TURKISH_HARD_CONSONANT_BIT = 1 << 24;

  private final String locale;
  private final List<String> strings;
  private final Map<String, Map<String, FormRow>> formsByTermId;
  private final Map<String, TermRow> termsByTermId;
  private final TermUsageExtractor termUsageExtractor;

  CompiledTermPackRenderer(CompiledTermPack pack) {
    this(pack, new IcuMessage2TermUsageExtractor());
  }

  CompiledTermPackRenderer(CompiledTermPack pack, TermUsageExtractor termUsageExtractor) {
    Objects.requireNonNull(pack, "pack");
    this.locale = pack.locale();
    this.strings = pack.strings();
    this.formsByTermId = new HashMap<>();
    this.termsByTermId = new HashMap<>();
    this.termUsageExtractor = Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");

    for (TermRow term : pack.terms()) {
      String termId = strings.get(term.id());
      if (termsByTermId.put(termId, term) != null) {
        throw new IllegalArgumentException("Duplicate compiled term row: " + termId);
      }
    }

    for (FormSet formSet : pack.formSets()) {
      String termId = strings.get(formSet.term());
      Map<String, FormRow> formsByKey = new HashMap<>();
      for (FormRow form : formSet.forms()) {
        String formKey = strings.get(form.key());
        if (formsByKey.put(formKey, form) != null) {
          throw new IllegalArgumentException(
              "Duplicate compiled form key for term " + termId + ": " + formKey);
        }
      }
      formsByTermId.put(termId, formsByKey);
    }
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
      String termId = termArguments.get(usage.argument());
      String replacement;
      try {
        replacement = renderTerm(usage.argument(), termId, usage.options(), variables);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(renderFailureMessage(usage, termId, e), e);
      }
      rendered.append(replacement);
      cursor = usage.end();
    }
    rendered.append(message.substring(cursor));
    return TermRenderRuntime.renderPattern(rendered.toString(), variables);
  }

  public void requireRenderableMessage(String message, Map<String, String> termArguments) {
    requireRenderableMessageWithOptionalMessageId(null, message, termArguments);
  }

  public void requireRenderableMessage(
      String messageId, String message, Map<String, String> termArguments) {
    Objects.requireNonNull(messageId, "messageId");
    requireRenderableMessageWithOptionalMessageId(messageId, message, termArguments);
  }

  private void requireRenderableMessageWithOptionalMessageId(
      String messageId, String message, Map<String, String> termArguments) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(termArguments, "termArguments");

    for (TermUsageExtractor.TermUsage usage : termUsageExtractor.extract(message)) {
      if (HindiPronounTermOptions.isPronounUsage(locale, usage.options())) {
        continue;
      }
      String termId = termArguments.get(usage.argument());
      try {
        requireRenderableTerm(usage.argument(), termId, usage.options());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(renderFailureMessage(messageId, usage, termId, e), e);
      }
    }
  }

  private String renderFailureMessage(
      TermUsageExtractor.TermUsage usage, String termId, IllegalArgumentException cause) {
    return renderFailureMessage(null, usage, termId, cause);
  }

  private String renderFailureMessage(
      String messageId,
      TermUsageExtractor.TermUsage usage,
      String termId,
      IllegalArgumentException cause) {
    String binding = termId == null ? "" : " bound to " + termId;
    String messageContext = messageId == null ? "" : " in message " + messageId;
    return "Failed to render term argument "
        + usage.argument()
        + messageContext
        + binding
        + " at span "
        + usage.span().format()
        + ": "
        + cause.getMessage();
  }

  public String renderTerm(
      String termId, Map<String, String> options, Map<String, String> variables) {
    return renderTerm(null, termId, options, variables);
  }

  private String renderTerm(
      String argument, String termId, Map<String, String> options, Map<String, String> variables) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(variables, "variables");
    if (termId == null) {
      if (argument != null) {
        throw new IllegalArgumentException("Missing term argument: " + argument);
      }
      throw new IllegalArgumentException("Missing term id");
    }
    Map<String, FormRow> forms = formsByTermId.get(termId);
    if (forms == null) {
      throw new IllegalArgumentException("Missing compiled term: " + termId);
    }

    FormSelection selection = selectForm(options, variables);
    FormRow form = forms.get(selection.key());
    if (form == null) {
      String composed =
          maybeRenderComposedSpanishArticleTerm(termId, forms, selection, options, variables);
      if (composed != null) {
        return composed;
      }
      composed =
          maybeRenderComposedItalianArticleTerm(termId, forms, selection, options, variables);
      if (composed != null) {
        return composed;
      }
      composed =
          maybeRenderComposedPortugueseArticleTerm(termId, forms, selection, options, variables);
      if (composed != null) {
        return composed;
      }
      composed = maybeRenderComposedTurkishSuffixTerm(termId, forms, selection, options, variables);
      if (composed != null) {
        return composed;
      }
      throw new IllegalArgumentException("Missing form " + selection.key() + " for term " + termId);
    }

    return renderForm(form, variables);
  }

  private void requireRenderableTerm(String argument, String termId, Map<String, String> options) {
    Objects.requireNonNull(options, "options");
    if (termId == null) {
      if (argument != null) {
        throw new IllegalArgumentException("Missing term argument: " + argument);
      }
      throw new IllegalArgumentException("Missing term id");
    }
    Map<String, FormRow> forms = formsByTermId.get(termId);
    if (forms == null) {
      throw new IllegalArgumentException("Missing compiled term: " + termId);
    }

    for (FormSelection selection : renderabilitySelections(options)) {
      if (forms.containsKey(selection.key())) {
        continue;
      }
      if (isSpanishArticleComposition(options)) {
        requireSpanishArticleCompositionRenderable(termId, forms, selection);
        continue;
      }
      if (isItalianArticleComposition(options)) {
        requireItalianArticleCompositionRenderable(termId, forms, selection);
        continue;
      }
      if (isPortugueseArticleComposition(options)) {
        requirePortugueseArticleCompositionRenderable(termId, forms, selection);
        continue;
      }
      if (isTurkishSuffixComposition(options)) {
        requireTurkishSuffixCompositionRenderable(termId, forms);
        continue;
      }
      throw new IllegalArgumentException("Missing form " + selection.key() + " for term " + termId);
    }
  }

  private List<FormSelection> renderabilitySelections(Map<String, String> options) {
    TermUsageOptions.validate(options);
    String countReference = options.get(TermUsageOptions.COUNT);
    String explicitNumber = options.get(TermUsageOptions.NUMBER);
    List<String> numbers =
        explicitNumber != null
            ? List.of(explicitNumber)
            : countReference != null ? List.of("singular", "plural") : List.of("singular");

    List<FormSelection> selections = new ArrayList<>();
    for (String number : numbers) {
      selections.add(selectionForNumber(options, number));
    }
    return List.copyOf(selections);
  }

  private String renderForm(FormRow form, Map<String, String> variables) {
    String value = strings.get(form.value());
    return form.pattern() ? TermRenderRuntime.renderPattern(value, variables) : value;
  }

  private String selectFormKey(Map<String, String> options, Map<String, String> variables) {
    return selectForm(options, variables).key();
  }

  private FormSelection selectForm(Map<String, String> options, Map<String, String> variables) {
    TermUsageOptions.validate(options);
    String countReference = options.get(TermUsageOptions.COUNT);
    String explicitNumber = options.get(TermUsageOptions.NUMBER);
    String number =
        explicitNumber != null
            ? explicitNumber
            : TermRenderRuntime.numberFromCountReference(countReference, variables);

    return selectionForNumber(options, number);
  }

  private FormSelection selectionForNumber(Map<String, String> options, String number) {
    String article = options.get(TermUsageOptions.ARTICLE);
    String grammaticalCase = options.get(TermUsageOptions.CASE);
    String definiteness = options.get(TermUsageOptions.DEFINITENESS);
    String preposition = options.get(TermUsageOptions.PREPOSITION);
    if (preposition != null) {
      return new FormSelection("preposition." + preposition + "." + article + "." + number, number);
    }
    if (definiteness != null && grammaticalCase != null) {
      return new FormSelection(definiteness + "." + grammaticalCase + "." + number, number);
    }
    if (definiteness != null) {
      return new FormSelection(definiteness + "." + number, number);
    }
    if (article != null && grammaticalCase != null) {
      return new FormSelection(article + "." + grammaticalCase + "." + number, number);
    }
    if (article != null) {
      return new FormSelection(article + "." + number, number);
    }
    if (grammaticalCase != null) {
      return new FormSelection(grammaticalCase + "." + number, number);
    }
    if (options.get(TermUsageOptions.NUMBER) != null) {
      return new FormSelection("bare." + number, number);
    }
    if (options.get(TermUsageOptions.COUNT) != null) {
      return new FormSelection("count." + ("singular".equals(number) ? "one" : "other"), number);
    }
    return new FormSelection("bare.singular", "singular");
  }

  private String maybeRenderComposedSpanishArticleTerm(
      String termId,
      Map<String, FormRow> forms,
      FormSelection selection,
      Map<String, String> options,
      Map<String, String> variables) {
    if (!isSpanishArticleComposition(options)) {
      return null;
    }

    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Spanish term metadata for term " + termId);
    }

    String bareKey = "bare." + selection.number();
    FormRow bareForm = forms.get(bareKey);
    if (bareForm == null) {
      throw new IllegalArgumentException(
          "Missing Spanish bare form " + bareKey + " for term " + termId);
    }

    String article =
        spanishArticle(
            options.get(TermUsageOptions.ARTICLE),
            gender(termId, "Spanish", term.featureBits()),
            selection.number(),
            (term.featureBits() & STRESSED_BIT) != 0);
    String bareValue = renderForm(bareForm, variables);
    return article + " " + bareValue;
  }

  private void requireSpanishArticleCompositionRenderable(
      String termId, Map<String, FormRow> forms, FormSelection selection) {
    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Spanish term metadata for term " + termId);
    }
    gender(termId, "Spanish", term.featureBits());
    String bareKey = "bare." + selection.number();
    if (!forms.containsKey(bareKey)) {
      throw new IllegalArgumentException(
          "Missing Spanish bare form " + bareKey + " for term " + termId);
    }
  }

  private String maybeRenderComposedItalianArticleTerm(
      String termId,
      Map<String, FormRow> forms,
      FormSelection selection,
      Map<String, String> options,
      Map<String, String> variables) {
    if (!isItalianArticleComposition(options)) {
      return null;
    }

    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Italian term metadata for term " + termId);
    }

    String bareKey = "bare." + selection.number();
    FormRow bareForm = forms.get(bareKey);
    if (bareForm == null) {
      throw new IllegalArgumentException(
          "Missing Italian bare form " + bareKey + " for term " + termId);
    }

    String article =
        italianArticle(
            options.get(TermUsageOptions.ARTICLE),
            gender(termId, "Italian", term.featureBits()),
            selection.number(),
            italianArticleClass(termId, term.featureBits()));
    return articlePhrase(article, renderForm(bareForm, variables));
  }

  private void requireItalianArticleCompositionRenderable(
      String termId, Map<String, FormRow> forms, FormSelection selection) {
    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Italian term metadata for term " + termId);
    }
    gender(termId, "Italian", term.featureBits());
    italianArticleClass(termId, term.featureBits());
    String bareKey = "bare." + selection.number();
    if (!forms.containsKey(bareKey)) {
      throw new IllegalArgumentException(
          "Missing Italian bare form " + bareKey + " for term " + termId);
    }
  }

  private String maybeRenderComposedPortugueseArticleTerm(
      String termId,
      Map<String, FormRow> forms,
      FormSelection selection,
      Map<String, String> options,
      Map<String, String> variables) {
    if (!isPortugueseArticleComposition(options)) {
      return null;
    }

    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Portuguese term metadata for term " + termId);
    }

    String bareKey = "bare." + selection.number();
    FormRow bareForm = forms.get(bareKey);
    if (bareForm == null) {
      throw new IllegalArgumentException(
          "Missing Portuguese bare form " + bareKey + " for term " + termId);
    }

    String article =
        portugueseArticle(
            options.get(TermUsageOptions.ARTICLE),
            options.get(TermUsageOptions.PREPOSITION),
            gender(termId, "Portuguese", term.featureBits()),
            selection.number());
    return article + " " + renderForm(bareForm, variables);
  }

  private void requirePortugueseArticleCompositionRenderable(
      String termId, Map<String, FormRow> forms, FormSelection selection) {
    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Portuguese term metadata for term " + termId);
    }
    gender(termId, "Portuguese", term.featureBits());
    String bareKey = "bare." + selection.number();
    if (!forms.containsKey(bareKey)) {
      throw new IllegalArgumentException(
          "Missing Portuguese bare form " + bareKey + " for term " + termId);
    }
  }

  private String maybeRenderComposedTurkishSuffixTerm(
      String termId,
      Map<String, FormRow> forms,
      FormSelection selection,
      Map<String, String> options,
      Map<String, String> variables) {
    if (!isTurkishSuffixComposition(options)) {
      return null;
    }

    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Turkish term metadata for term " + termId);
    }
    if ((term.featureBits() & TURKISH_SUFFIX_METADATA_BIT) == 0) {
      throw new IllegalArgumentException(
          "Turkish suffix composition requires turkishSuffix metadata for term " + termId);
    }

    FormRow bareForm = forms.get("bare.singular");
    if (bareForm == null) {
      throw new IllegalArgumentException(
          "Missing Turkish bare form bare.singular for term " + termId);
    }

    return turkishInflect(
        renderForm(bareForm, variables),
        options.get(TermUsageOptions.CASE),
        selection.number(),
        turkishSuffixMetadata(term.featureBits()));
  }

  private void requireTurkishSuffixCompositionRenderable(
      String termId, Map<String, FormRow> forms) {
    TermRow term = termsByTermId.get(termId);
    if (term == null) {
      throw new IllegalArgumentException("Missing Turkish term metadata for term " + termId);
    }
    if ((term.featureBits() & TURKISH_SUFFIX_METADATA_BIT) == 0) {
      throw new IllegalArgumentException(
          "Turkish suffix composition requires turkishSuffix metadata for term " + termId);
    }
    if (!forms.containsKey("bare.singular")) {
      throw new IllegalArgumentException(
          "Missing Turkish bare form bare.singular for term " + termId);
    }
  }

  private boolean isSpanishArticleComposition(Map<String, String> options) {
    String article = options.get(TermUsageOptions.ARTICLE);
    return isSpanishLocale()
        && options.get(TermUsageOptions.CASE) == null
        && options.get(TermUsageOptions.PREPOSITION) == null
        && ("definite".equals(article) || "indefinite".equals(article));
  }

  private boolean isSpanishLocale() {
    return SPANISH_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("es-") || locale.startsWith("es_")));
  }

  private boolean isItalianArticleComposition(Map<String, String> options) {
    String article = options.get(TermUsageOptions.ARTICLE);
    return isItalianLocale()
        && options.get(TermUsageOptions.CASE) == null
        && options.get(TermUsageOptions.PREPOSITION) == null
        && ("definite".equals(article) || "indefinite".equals(article));
  }

  private boolean isItalianLocale() {
    return ITALIAN_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("it-") || locale.startsWith("it_")));
  }

  private boolean isPortugueseArticleComposition(Map<String, String> options) {
    String article = options.get(TermUsageOptions.ARTICLE);
    String preposition = options.get(TermUsageOptions.PREPOSITION);
    return isPortugueseLocale()
        && options.get(TermUsageOptions.CASE) == null
        && TermUsageOptions.isPortuguesePrepositionArticleCombination(preposition, article);
  }

  private boolean isPortugueseLocale() {
    return PORTUGUESE_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("pt-") || locale.startsWith("pt_")));
  }

  private boolean isTurkishSuffixComposition(Map<String, String> options) {
    String grammaticalCase = options.get(TermUsageOptions.CASE);
    return isTurkishLocale()
        && options.get(TermUsageOptions.ARTICLE) == null
        && options.get(TermUsageOptions.PREPOSITION) == null
        && (options.containsKey(TermUsageOptions.COUNT)
            ? grammaticalCase == null || isSupportedTurkishSuffixCase(grammaticalCase)
            : isSupportedTurkishSuffixCase(grammaticalCase));
  }

  private boolean isTurkishLocale() {
    return TURKISH_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("tr-") || locale.startsWith("tr_")));
  }

  private boolean isSupportedTurkishSuffixCase(String grammaticalCase) {
    if (grammaticalCase == null) {
      return false;
    }
    return switch (grammaticalCase) {
      case "ablative", "accusative", "dative", "locative", "nominative" -> true;
      default -> false;
    };
  }

  private String gender(String termId, String localeName, int featureBits) {
    int gender = (featureBits >> GENDER_SHIFT) & GENDER_MASK;
    return switch (gender) {
      case MASCULINE_GENDER -> "masculine";
      case FEMININE_GENDER -> "feminine";
      default ->
          throw new IllegalArgumentException(
              localeName
                  + " article composition requires masculine or feminine gender for term "
                  + termId);
    };
  }

  private String spanishArticle(String article, String gender, String number, boolean stressed) {
    if ("definite".equals(article)) {
      if ("plural".equals(number)) {
        return "masculine".equals(gender) ? "los" : "las";
      }
      if ("feminine".equals(gender) && !stressed) {
        return "la";
      }
      return "el";
    }

    if ("plural".equals(number)) {
      return "masculine".equals(gender) ? "unos" : "unas";
    }
    if ("feminine".equals(gender) && !stressed) {
      return "una";
    }
    return "un";
  }

  private String italianArticleClass(String termId, int featureBits) {
    int articleClass = (featureBits >> ARTICLE_CLASS_SHIFT) & ARTICLE_CLASS_MASK;
    return switch (articleClass) {
      case STANDARD_ARTICLE_CLASS -> "standard";
      case LO_ARTICLE_CLASS -> "lo";
      case ELISION_ARTICLE_CLASS -> "elision";
      default ->
          throw new IllegalArgumentException(
              "Italian article composition requires articleClass metadata for term " + termId);
    };
  }

  private String italianArticle(String article, String gender, String number, String articleClass) {
    if ("definite".equals(article)) {
      if ("masculine".equals(gender) && "singular".equals(number)) {
        return switch (articleClass) {
          case "standard" -> "il";
          case "lo" -> "lo";
          case "elision" -> "l'";
          default -> throw new IllegalArgumentException("Unsupported Italian article class");
        };
      }
      if ("masculine".equals(gender) && "plural".equals(number)) {
        return "standard".equals(articleClass) ? "i" : "gli";
      }
      if ("feminine".equals(gender) && "singular".equals(number)) {
        return "elision".equals(articleClass) ? "l'" : "la";
      }
      return "le";
    }

    if ("masculine".equals(gender) && "singular".equals(number)) {
      return "lo".equals(articleClass) ? "uno" : "un";
    }
    if ("masculine".equals(gender) && "plural".equals(number)) {
      return "standard".equals(articleClass) ? "dei" : "degli";
    }
    if ("feminine".equals(gender) && "singular".equals(number)) {
      return "elision".equals(articleClass) ? "un'" : "una";
    }
    return "delle";
  }

  private String portugueseArticle(
      String article, String preposition, String gender, String number) {
    String key = (preposition == null ? "article" : preposition) + "." + article;
    return switch (key) {
      case "article.definite" -> genderNumberForm(gender, number, "o", "a", "os", "as");
      case "article.indefinite" -> genderNumberForm(gender, number, "um", "uma", "uns", "umas");
      case "de.definite" -> genderNumberForm(gender, number, "do", "da", "dos", "das");
      case "em.definite" -> genderNumberForm(gender, number, "no", "na", "nos", "nas");
      case "em.indefinite" -> genderNumberForm(gender, number, "num", "numa", "nuns", "numas");
      case "por.definite" -> genderNumberForm(gender, number, "pelo", "pela", "pelos", "pelas");
      default ->
          throw new IllegalArgumentException(
              "Unsupported Portuguese article composition: " + preposition + " + " + article);
    };
  }

  private TurkishSuffixMetadata turkishSuffixMetadata(int featureBits) {
    return new TurkishSuffixMetadata(
        (featureBits & TURKISH_VOWEL_END_BIT) != 0,
        (featureBits & TURKISH_FRONT_VOWEL_BIT) != 0,
        (featureBits & TURKISH_ROUNDED_VOWEL_BIT) != 0,
        (featureBits & TURKISH_HARD_CONSONANT_BIT) != 0);
  }

  private String turkishInflect(
      String stem, String grammaticalCase, String number, TurkishSuffixMetadata metadata) {
    String inflectedStem = stem;
    TurkishSuffixMetadata suffixMetadata = metadata;
    if ("plural".equals(number)) {
      String pluralSuffix = metadata.frontVowel() ? "ler" : "lar";
      inflectedStem += pluralSuffix;
      suffixMetadata = new TurkishSuffixMetadata(false, metadata.frontVowel(), false, false);
    }

    if (grammaticalCase == null || "nominative".equals(grammaticalCase)) {
      return inflectedStem;
    }
    return inflectedStem + turkishCaseSuffix(grammaticalCase, suffixMetadata);
  }

  private String turkishCaseSuffix(String grammaticalCase, TurkishSuffixMetadata metadata) {
    String twoWayVowel = metadata.frontVowel() ? "e" : "a";
    String consonant = metadata.hardConsonant() ? "t" : "d";
    return switch (grammaticalCase) {
      case "accusative" -> (metadata.vowelEnd() ? "y" : "") + turkishFourWayVowel(metadata);
      case "dative" -> (metadata.vowelEnd() ? "y" : "") + twoWayVowel;
      case "locative" -> consonant + twoWayVowel;
      case "ablative" -> consonant + twoWayVowel + "n";
      default -> throw new IllegalArgumentException("Unsupported Turkish case: " + grammaticalCase);
    };
  }

  private String turkishFourWayVowel(TurkishSuffixMetadata metadata) {
    if (metadata.frontVowel()) {
      return metadata.roundedVowel() ? "\u00fc" : "i";
    }
    return metadata.roundedVowel() ? "u" : "\u0131";
  }

  private String genderNumberForm(
      String gender,
      String number,
      String masculineSingular,
      String feminineSingular,
      String masculinePlural,
      String femininePlural) {
    if ("plural".equals(number)) {
      return "masculine".equals(gender) ? masculinePlural : femininePlural;
    }
    return "masculine".equals(gender) ? masculineSingular : feminineSingular;
  }

  private String articlePhrase(String article, String bareValue) {
    return article.endsWith("'") ? article + bareValue : article + " " + bareValue;
  }

  private record FormSelection(String key, String number) {}

  private record TurkishSuffixMetadata(
      boolean vowelEnd, boolean frontVowel, boolean roundedVowel, boolean hardConsonant) {}
}
