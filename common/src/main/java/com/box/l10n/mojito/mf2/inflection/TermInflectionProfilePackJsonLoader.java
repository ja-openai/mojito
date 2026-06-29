package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObjectRoot;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Provenance;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.SizeEstimates;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Source;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads glossary-backed, locale-specific term inflection authoring profiles.
 *
 * <p>This is the glossary authoring/export boundary. Approved profiles compile to {@link
 * CompiledTermPack}; generated and review-needed profiles remain visible in diagnostics but are not
 * silently exported into the runtime pack.
 */
public class TermInflectionProfilePackJsonLoader {

  public static final String EXPECTED_SCHEMA =
      "mojito-mf2-inflection/term-inflection-profile-pack/v0";
  public static final String STATUS_APPROVED = "APPROVED";
  public static final String STATUS_DISABLED = "DISABLED";
  public static final String STATUS_GENERATED = "GENERATED";
  public static final String STATUS_REVIEW_NEEDED = "REVIEW_NEEDED";

  private static final Set<String> STATUSES =
      Set.of(STATUS_APPROVED, STATUS_DISABLED, STATUS_GENERATED, STATUS_REVIEW_NEEDED);
  private static final Pattern FORM_KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

  private final ObjectMapper objectMapper;

  public TermInflectionProfilePackJsonLoader() {
    this(new ObjectMapper());
  }

  public TermInflectionProfilePackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public TermInflectionProfilePack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public TermInflectionProfilePack load(JsonNode root) {
    Objects.requireNonNull(root, "root");
    requiredObjectRoot(root, "term inflection profile pack");
    return new TermInflectionProfilePack(
        requiredText(root, "schema"),
        requiredText(root, "locale"),
        loadProvenance(requiredObject(root, "provenance")),
        loadProfiles(requiredArray(root, "profiles")));
  }

  private Provenance loadProvenance(JsonNode node) {
    return new Provenance(
        optionalText(node, "license"),
        optionalText(node, "generator"),
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "profile pack"),
        loadSources(requiredArray(node, "sources")));
  }

  private List<Source> loadSources(JsonNode node) {
    List<Source> sources = new ArrayList<>();
    for (JsonNode sourceNode : node) {
      sources.add(
          new Source(
              requiredText(sourceNode, "path"),
              requiredLong(sourceNode, "byteSize"),
              requiredText(sourceNode, "sha256"),
              requiredBoolean(sourceNode, "gitLfsPointer")));
    }
    return List.copyOf(sources);
  }

  private List<Profile> loadProfiles(JsonNode node) {
    List<Profile> profiles = new ArrayList<>();
    for (JsonNode profileNode : node) {
      String termId = requiredText(profileNode, "termId");
      Map<String, String> forms = loadStringMap(requiredObject(profileNode, "forms"), "forms");
      profiles.add(
          new Profile(
              termId,
              requiredText(profileNode, "source"),
              requiredText(profileNode, "status"),
              loadMorphology(requiredObject(profileNode, "morphology")),
              forms,
              loadDiagnostics(requiredArray(profileNode, "diagnostics"), termId, forms),
              optionalObjectOrEmpty(profileNode, "provenance")));
    }
    return List.copyOf(profiles);
  }

  private Morphology loadMorphology(JsonNode node) {
    Boolean startsWithVowelSound = optionalBoolean(node, "startsWithVowelSound");
    Boolean elision = optionalBoolean(node, "elision");
    if (startsWithVowelSound != null && elision != null && !startsWithVowelSound.equals(elision)) {
      throw new IllegalArgumentException("Morphology elision conflicts with startsWithVowelSound");
    }
    if (startsWithVowelSound == null) {
      startsWithVowelSound = elision;
    }

    return new Morphology(
        optionalText(node, "partOfSpeech"),
        optionalText(node, "gender"),
        optionalText(node, "number"),
        startsWithVowelSound,
        optionalBoolean(node, "stressed"),
        optionalText(node, "articleClass"),
        optionalText(node, "sense"),
        loadTurkishSuffix(node.get("turkishSuffix")));
  }

  private TurkishSuffix loadTurkishSuffix(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("Expected object field: turkishSuffix");
    }
    return new TurkishSuffix(
        optionalBoolean(node, "vowelEnd"),
        optionalBoolean(node, "frontVowel"),
        optionalBoolean(node, "roundedVowel"),
        optionalBoolean(node, "hardConsonant"));
  }

  private List<Diagnostic> loadDiagnostics(
      JsonNode node, String profileTermId, Map<String, String> forms) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (JsonNode diagnosticNode : node) {
      diagnostics.add(loadDiagnostic(diagnosticNode, profileTermId, forms));
    }
    return List.copyOf(diagnostics);
  }

  private Diagnostic loadDiagnostic(
      JsonNode node, String profileTermId, Map<String, String> forms) {
    Diagnostic diagnostic =
        new Diagnostic(
            optionalText(node, "code"),
            optionalText(node, "message"),
            optionalText(node, "reason"),
            optionalText(node, "formKey"),
            optionalText(node, "termId"));
    validateDiagnostic(diagnostic, profileTermId, forms);
    return diagnostic;
  }

  private void validateDiagnostic(
      Diagnostic diagnostic, String profileTermId, Map<String, String> forms) {
    if (diagnostic.termId() != null && !profileTermId.equals(diagnostic.termId())) {
      throw new IllegalArgumentException(
          "Inflection profile diagnostic term mismatch: "
              + diagnostic.termId()
              + " for "
              + profileTermId);
    }
    if (diagnostic.isMissingFormCell()) {
      if (diagnostic.formKey() == null) {
        throw new IllegalArgumentException(
            TermInflectionDiagnostics.MISSING_FORM_CELL + " diagnostic requires formKey");
      }
      if (forms.containsKey(diagnostic.formKey())) {
        throw new IllegalArgumentException(
            TermInflectionDiagnostics.MISSING_FORM_CELL
                + " diagnostic points to existing form: "
                + diagnostic.formKey());
      }
    }
  }

  private Map<String, String> loadStringMap(JsonNode node, String field) {
    Map<String, String> values = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (!entry.getValue().isTextual()) {
        throw new IllegalArgumentException(
            "Expected text value in " + field + ": " + entry.getKey());
      }
      values.put(
          requireText(entry.getKey(), field + " key"),
          requireText(entry.getValue().asText(), field));
    }
    return values;
  }

  private JsonNode optionalObjectOrEmpty(JsonNode node, String field) {
    JsonNode value = optionalObject(node, field);
    if (value == null) {
      return objectMapper.createObjectNode();
    }
    return value;
  }

  public record TermInflectionProfilePack(
      String schema, String locale, Provenance provenance, List<Profile> profiles) {

    public TermInflectionProfilePack {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireText(locale, "locale");
      provenance = Objects.requireNonNull(provenance, "provenance");
      profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
      Set<String> termIds = new HashSet<>();
      for (Profile profile : profiles) {
        if (!termIds.add(profile.termId())) {
          throw new IllegalArgumentException(
              "Duplicate inflection profile term: " + profile.termId());
        }
      }
    }

    public Map<String, TermRequirementValidator.Term> toRequirementTerms() {
      Map<String, TermRequirementValidator.Term> terms = new LinkedHashMap<>();
      profiles.stream()
          .filter(profile -> !STATUS_DISABLED.equals(profile.status()))
          .sorted(Comparator.comparing(Profile::termId))
          .forEach(profile -> terms.put(profile.termId(), profile.toRequirementTerm()));
      return Collections.unmodifiableMap(terms);
    }

    public CompiledTermPack toCompiledTermPack() {
      List<Profile> approvedProfiles =
          profiles.stream()
              .filter(profile -> !STATUS_DISABLED.equals(profile.status()))
              .sorted(Comparator.comparing(Profile::termId))
              .toList();

      List<String> strings = new ArrayList<>();
      Map<String, Integer> stringIndexes = new LinkedHashMap<>();
      List<TermRow> terms = new ArrayList<>();
      List<FormSet> formSets = new ArrayList<>();

      for (Profile profile : approvedProfiles) {
        if (!STATUS_APPROVED.equals(profile.status())) {
          throw new IllegalArgumentException(
              "Cannot compile inflection profile "
                  + profile.termId()
                  + " with status "
                  + profile.status());
        }
        int termIndex = stringIndex(profile.termId(), strings, stringIndexes);
        int sourceIndex = stringIndex(profile.source(), strings, stringIndexes);
        List<FormRow> forms = new ArrayList<>();
        profile.forms().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(
                entry ->
                    forms.add(
                        new FormRow(
                            stringIndex(entry.getKey(), strings, stringIndexes),
                            stringIndex(entry.getValue(), strings, stringIndexes),
                            entry.getValue().contains("{$"))));
        int formSetIndex = formSets.size();
        formSets.add(new FormSet(termIndex, forms));
        terms.add(new TermRow(termIndex, sourceIndex, 0, null, formSetIndex));
      }

      return new CompiledTermPack(
          CompiledTermPack.SCHEMA,
          locale,
          strings,
          terms,
          formSets,
          provenance,
          SizeEstimates.empty());
    }

    private static int stringIndex(
        String value, List<String> strings, Map<String, Integer> stringIndexes) {
      Integer existing = stringIndexes.get(value);
      if (existing != null) {
        return existing;
      }
      int index = strings.size();
      strings.add(value);
      stringIndexes.put(value, index);
      return index;
    }
  }

  public record Profile(
      String termId,
      String source,
      String status,
      Morphology morphology,
      Map<String, String> forms,
      List<Diagnostic> diagnostics,
      JsonNode provenance) {

    public Profile {
      termId = requireText(termId, "termId");
      source = requireText(source, "source");
      status = requireExpectedValue(status, "status", STATUSES);
      morphology = Objects.requireNonNull(morphology, "morphology");
      forms = unmodifiableLinkedMap(Objects.requireNonNull(forms, "forms"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      provenance = Objects.requireNonNull(provenance, "provenance").deepCopy();
      if (!provenance.isObject()) {
        throw new IllegalArgumentException("Expected object field: provenance");
      }

      for (String formKey : forms.keySet()) {
        if (!FORM_KEY_PATTERN.matcher(formKey).matches()) {
          throw new IllegalArgumentException("Unsupported inflection form key: " + formKey);
        }
      }
      if (STATUS_APPROVED.equals(status)) {
        if (forms.isEmpty()) {
          throw new IllegalArgumentException(
              "Approved inflection profile requires forms: " + termId);
        }
        if (!diagnostics.isEmpty()) {
          throw new IllegalArgumentException(
              "Approved inflection profile cannot include diagnostics: " + termId);
        }
      }
    }

    TermRequirementValidator.Term toRequirementTerm() {
      return new TermRequirementValidator.Term(
          source,
          new TermRequirementValidator.Morphology(
              morphology.partOfSpeech(),
              morphology.gender(),
              morphology.number(),
              morphology.startsWithVowelSound(),
              morphology.stressed(),
              morphology.articleClass(),
              morphology.sense(),
              morphology.turkishSuffix() == null
                  ? null
                  : new TermRequirementValidator.TurkishSuffix(
                      morphology.turkishSuffix().vowelEnd(),
                      morphology.turkishSuffix().frontVowel(),
                      morphology.turkishSuffix().roundedVowel(),
                      morphology.turkishSuffix().hardConsonant())),
          forms);
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
      TurkishSuffix turkishSuffix) {}

  public record TurkishSuffix(
      Boolean vowelEnd, Boolean frontVowel, Boolean roundedVowel, Boolean hardConsonant) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Diagnostic(
      String code, String message, String reason, String formKey, String termId) {

    public Diagnostic {
      code = optionalRecordText(code, "code");
      message = optionalRecordText(message, "message");
      reason = optionalRecordText(reason, "reason");
      formKey = optionalRecordText(formKey, "formKey");
      termId = optionalRecordText(termId, "termId");
      if (code == null && reason != null) {
        code = reason;
      }
      code = requireText(code, "code");
      if (message == null && reason != null && formKey != null) {
        message = reason + ": " + formKey;
      } else if (message == null && reason != null) {
        message = reason;
      }
      message = requireText(message, "message");
      if (formKey != null && !FORM_KEY_PATTERN.matcher(formKey).matches()) {
        throw new IllegalArgumentException(
            "Unsupported inflection diagnostic form key: " + formKey);
      }
    }

    boolean isMissingFormCell() {
      return TermInflectionDiagnostics.MISSING_FORM_CELL.equals(code)
          || TermInflectionDiagnostics.MISSING_FORM_CELL.equals(reason);
    }
  }

  private static String requireExpected(String value, String field, String expected) {
    value = requireText(value, field);
    if (!expected.equals(value)) {
      throw new IllegalArgumentException("Expected " + field + " " + expected + ", got " + value);
    }
    return value;
  }

  private static String requireExpectedValue(
      String value, String field, Set<String> allowedValues) {
    value = requireText(value, field);
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException("Unsupported " + field + " value: " + value);
    }
    return value;
  }

  private static String optionalRecordText(String value, String field) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
