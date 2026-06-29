package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObjectRoot;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Morphology;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Term;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TurkishSuffix;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads MF2 term authoring inputs: message binding manifests and readable term packs.
 *
 * <p>The binding-manifest shape is the schema-gated product boundary used before rendering: it
 * declares which closed-world term IDs are allowed for each `:term` argument in each message. The
 * readable term-pack loader exists for validation/report tooling and is not the hot runtime pack
 * shape.
 */
public class TermRequirementJsonLoader {

  public static final String MESSAGE_TERM_BINDING_MANIFEST_SCHEMA =
      "mojito-mf2-inflection/message-term-binding-manifest/v0";

  private final ObjectMapper objectMapper;
  private final TermUsageExtractor termUsageExtractor;

  public TermRequirementJsonLoader() {
    this(new ObjectMapper());
  }

  public TermRequirementJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.termUsageExtractor = new IcuMessage2TermUsageExtractor();
  }

  public TermUsageCatalog loadUsageCatalog(String json) {
    Objects.requireNonNull(json, "json");
    return loadUsageCatalog(objectMapper.readTreeUnchecked(json));
  }

  public TermUsageCatalog loadUsageCatalog(JsonNode root) {
    Objects.requireNonNull(root, "root");
    requiredObjectRoot(root, "message term binding manifest");
    String schema = requiredExpectedText(root, "schema", MESSAGE_TERM_BINDING_MANIFEST_SCHEMA);
    String locale = optionalText(root, "locale");
    Map<String, String> messages = textMap(requiredObject(root, "messages"));
    Map<String, Map<String, List<String>>> argumentTerms = new LinkedHashMap<>();
    for (var fields = requiredObject(root, "argumentTerms").fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> messageTerms = fields.next();
      String messageId = messageTerms.getKey();
      if (!messages.containsKey(messageId)) {
        throw new IllegalArgumentException(
            "Argument terms reference unknown message: " + messageId);
      }
      if (!messageTerms.getValue().isObject()) {
        throw new IllegalArgumentException(
            "Expected object value for argument terms message: " + messageId);
      }

      Map<String, List<String>> termsByArgument = new LinkedHashMap<>();
      Set<String> termArguments = termBindingArguments(locale, messages.get(messageId));
      for (var argumentFields = messageTerms.getValue().fields(); argumentFields.hasNext(); ) {
        Map.Entry<String, JsonNode> argumentTermsEntry = argumentFields.next();
        String argument = argumentTermsEntry.getKey();
        if (argument.isBlank()) {
          throw new IllegalArgumentException(
              "Expected non-blank argument name for message: " + messageId);
        }
        if (!termArguments.contains(argument)) {
          throw new IllegalArgumentException(
              "Argument terms reference unknown term argument " + messageId + "." + argument);
        }
        termsByArgument.put(
            argument, termIdArray(argumentTermsEntry.getValue(), messageId, argument));
      }
      argumentTerms.put(messageId, unmodifiableLinkedMap(termsByArgument));
    }

    return new TermUsageCatalog(schema, locale, messages, unmodifiableLinkedMap(argumentTerms));
  }

  private Set<String> termBindingArguments(String locale, String message) {
    return TermRenderRuntime.termBindingArguments(locale, message, termUsageExtractor);
  }

  public ReadableTermPack loadTermPack(String json) {
    Objects.requireNonNull(json, "json");
    return loadTermPack(objectMapper.readTreeUnchecked(json));
  }

  public ReadableTermPack loadTermPack(JsonNode root) {
    Objects.requireNonNull(root, "root");
    requiredObjectRoot(root, "readable term pack");
    Map<String, Term> terms = new LinkedHashMap<>();
    for (var fields = requiredObject(root, "terms").fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> termEntry = fields.next();
      JsonNode termNode = termEntry.getValue();
      terms.put(
          termEntry.getKey(),
          new Term(
              requiredText(termNode, "text"),
              loadMorphology(optionalObject(termNode, "morphology")),
              textMap(optionalObject(termNode, "forms"))));
    }
    return new ReadableTermPack(optionalText(root, "locale"), unmodifiableLinkedMap(terms));
  }

  private Morphology loadMorphology(JsonNode node) {
    if (node == null) {
      return null;
    }
    return new Morphology(
        optionalText(node, "partOfSpeech"),
        optionalText(node, "gender"),
        optionalText(node, "number"),
        optionalBoolean(node, "startsWithVowelSound"),
        optionalBoolean(node, "stressed"),
        optionalText(node, "articleClass"),
        optionalText(node, "sense"),
        loadTurkishSuffix(optionalObject(node, "turkishSuffix")));
  }

  private TurkishSuffix loadTurkishSuffix(JsonNode node) {
    if (node == null) {
      return null;
    }
    return new TurkishSuffix(
        optionalBoolean(node, "vowelEnd"),
        optionalBoolean(node, "frontVowel"),
        optionalBoolean(node, "roundedVowel"),
        optionalBoolean(node, "hardConsonant"));
  }

  private Map<String, String> textMap(JsonNode node) {
    if (node == null) {
      return Map.of();
    }

    Map<String, String> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (!entry.getValue().isTextual()) {
        throw new IllegalArgumentException("Expected text value for field: " + entry.getKey());
      }
      values.put(entry.getKey(), entry.getValue().asText());
    }
    return unmodifiableLinkedMap(values);
  }

  private List<String> textArray(JsonNode node) {
    if (!node.isArray()) {
      throw new IllegalArgumentException("Expected text array");
    }

    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      if (!value.isTextual()) {
        throw new IllegalArgumentException("Expected text array value");
      }
      values.add(value.asText());
    }
    return List.copyOf(values);
  }

  private List<String> termIdArray(JsonNode node, String messageId, String argument) {
    List<String> termIds = textArray(node);
    Set<String> seen = new HashSet<>();
    for (String termId : termIds) {
      if (termId.isBlank()) {
        throw new IllegalArgumentException(
            "Expected non-blank term id for message " + messageId + " argument " + argument);
      }
      if (!seen.add(termId)) {
        throw new IllegalArgumentException(
            "Duplicate term id for message " + messageId + " argument " + argument + ": " + termId);
      }
    }
    return termIds;
  }

  private String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Expected text field: " + field);
    }
    return value.asText();
  }

  private String requiredExpectedText(JsonNode node, String field, String expected) {
    String value = requiredText(node, field);
    if (!expected.equals(value)) {
      throw new IllegalArgumentException(
          "Unsupported " + field + ": " + value + ", expected " + expected);
    }
    return value;
  }

  private String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("Expected text field: " + field);
    }
    return value.asText();
  }

  public record TermUsageCatalog(
      String schema,
      String locale,
      Map<String, String> messages,
      Map<String, Map<String, List<String>>> argumentTerms) {

    public TermUsageCatalog {
      schema = requiredSchema(schema);
      locale = optionalNonBlank(locale, "locale");
      messages = copyMessages(messages);
      argumentTerms = copyArgumentTerms(argumentTerms, messages.keySet());
    }

    private static String requiredSchema(String schema) {
      if (!MESSAGE_TERM_BINDING_MANIFEST_SCHEMA.equals(schema)) {
        throw new IllegalArgumentException(
            "Unsupported schema: " + schema + ", expected " + MESSAGE_TERM_BINDING_MANIFEST_SCHEMA);
      }
      return schema;
    }

    private static Map<String, String> copyMessages(Map<String, String> messages) {
      Map<String, String> copied = new LinkedHashMap<>();
      for (Map.Entry<String, String> messageEntry :
          Objects.requireNonNull(messages, "messages").entrySet()) {
        copied.put(
            TermRequirementJsonLoader.requireNonBlank(
                messageEntry.getKey(), "Expected non-blank message id"),
            Objects.requireNonNull(messageEntry.getValue(), "message source"));
      }
      return unmodifiableLinkedMap(copied);
    }

    private static Map<String, Map<String, List<String>>> copyArgumentTerms(
        Map<String, Map<String, List<String>>> argumentTerms, Set<String> messageIds) {
      Map<String, Map<String, List<String>>> copied = new LinkedHashMap<>();
      for (Map.Entry<String, Map<String, List<String>>> messageEntry :
          Objects.requireNonNull(argumentTerms, "argumentTerms").entrySet()) {
        String messageId =
            TermRequirementJsonLoader.requireNonBlank(
                messageEntry.getKey(), "Expected non-blank message id");
        if (!messageIds.contains(messageId)) {
          throw new IllegalArgumentException(
              "Argument terms reference unknown message: " + messageId);
        }
        Map<String, List<String>> copiedTermsByArgument = new LinkedHashMap<>();
        Map<String, List<String>> termsByArgument =
            Objects.requireNonNull(
                messageEntry.getValue(), "argument terms for message " + messageId);
        for (Map.Entry<String, List<String>> argumentEntry : termsByArgument.entrySet()) {
          String argument =
              requireNonBlank(
                  argumentEntry.getKey(),
                  "Expected non-blank argument name for message: " + messageId);
          List<String> termIds =
              Objects.requireNonNull(
                  argumentEntry.getValue(),
                  "term ids for message " + messageId + " argument " + argument);
          copiedTermsByArgument.put(argument, copyTermIds(termIds, messageId, argument));
        }
        copied.put(messageId, unmodifiableLinkedMap(copiedTermsByArgument));
      }
      return unmodifiableLinkedMap(copied);
    }

    private static List<String> copyTermIds(
        List<String> termIds, String messageId, String argument) {
      Set<String> seen = new HashSet<>();
      List<String> copied = new ArrayList<>();
      for (String termId : termIds) {
        String nonBlankTermId =
            TermRequirementJsonLoader.requireNonBlank(
                termId,
                "Expected non-blank term id for message " + messageId + " argument " + argument);
        if (!seen.add(nonBlankTermId)) {
          throw new IllegalArgumentException(
              "Duplicate term id for message "
                  + messageId
                  + " argument "
                  + argument
                  + ": "
                  + nonBlankTermId);
        }
        copied.add(nonBlankTermId);
      }
      return List.copyOf(copied);
    }
  }

  public record ReadableTermPack(String locale, Map<String, Term> terms) {

    public ReadableTermPack {
      locale = optionalNonBlank(locale, "locale");
      terms = copyTerms(terms);
    }

    private static Map<String, Term> copyTerms(Map<String, Term> terms) {
      Map<String, Term> copied = new LinkedHashMap<>();
      for (Map.Entry<String, Term> termEntry : Objects.requireNonNull(terms, "terms").entrySet()) {
        copied.put(
            TermRequirementJsonLoader.requireNonBlank(
                termEntry.getKey(), "Expected non-blank term id"),
            Objects.requireNonNull(termEntry.getValue(), "term"));
      }
      return unmodifiableLinkedMap(copied);
    }
  }

  private static String optionalNonBlank(String value, String field) {
    if (value == null) {
      return null;
    }
    return requireNonBlank(value, field + " must not be blank");
  }

  private static String requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }
}
