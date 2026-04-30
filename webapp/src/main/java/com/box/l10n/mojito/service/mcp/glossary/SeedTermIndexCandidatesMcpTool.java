package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SeedTermIndexCandidatesMcpTool
    extends TypedMcpToolHandler<SeedTermIndexCandidatesMcpTool.Input> {

  private static final int MAX_TERMS = 1_000;

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term_index.seed_candidates",
          "Seed term index candidates",
          "Merge externally suggested terms into Mojito's term index candidate review layer without creating glossary terms directly. Use this for Codex/product terminology, screenshot-derived term lists, or human seed lists; curators can then review the candidates in the glossary workspace.",
          false,
          true,
          List.of(
              new McpToolParameter(
                  "glossaryId",
                  "Optional glossary id. When provided, the candidates are tagged as submitted for that glossary.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "glossaryName",
                  "Optional exact glossary name, used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "candidates",
                  "Candidates to seed into the term index with optional definition, rationale, confidence, and metadata.",
                  true,
                  termsSchema())));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  public SeedTermIndexCandidatesMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermIndexCurationService =
        Objects.requireNonNull(glossaryTermIndexCurationService);
  }

  public record Input(
      Long glossaryId,
      String glossaryName,
      List<GlossaryTermIndexCurationService.SeedTermInput> candidates) {}

  @Override
  protected Object execute(Input input) {
    if (input != null && (input.glossaryId() != null || hasText(input.glossaryName()))) {
      Long glossaryId =
          glossaryMcpSupport.resolveGlossary(input.glossaryId(), input.glossaryName()).id();
      return glossaryTermIndexCurationService.seedTermsForGlossary(
          glossaryId, new GlossaryTermIndexCurationService.SeedCommand(input.candidates()));
    }
    return glossaryTermIndexCurationService.seedTerms(
        new GlossaryTermIndexCurationService.SeedCommand(
            input == null ? null : input.candidates()));
  }

  private static Map<String, Object> termsSchema() {
    return Map.of("type", "array", "minItems", 1, "maxItems", MAX_TERMS, "items", termSchema());
  }

  private static Map<String, Object> termSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("term", stringSchema("Source term text. Required."));
    properties.put(
        "sourceLocaleTag", stringSchema("Optional source locale tag. Defaults to root."));
    properties.put("sourceType", stringSchema("Optional source type, for example CODEX or HUMAN."));
    properties.put("sourceName", stringSchema("Optional source name, file, tool, or contributor."));
    properties.put(
        "sourceExternalId", stringSchema("Optional stable external id for idempotent updates."));
    properties.put("confidence", integerSchema("Optional confidence from 0 to 100."));
    properties.put("label", stringSchema("Optional display label."));
    properties.put("definition", stringSchema("Suggested glossary definition."));
    properties.put("rationale", stringSchema("Why this term should be reviewed for the glossary."));
    properties.put("termType", stringSchema("Optional term type, for example BRAND or PRODUCT."));
    properties.put("partOfSpeech", stringSchema("Optional part of speech."));
    properties.put("enforcement", stringSchema("Optional enforcement, for example SOFT or HARD."));
    properties.put(
        "doNotTranslate",
        Map.of("type", "boolean", "description", "Optional do-not-translate recommendation."));
    properties.put(
        "metadata",
        Map.of(
            "type",
            "object",
            "description",
            "Optional structured context such as screenshot image keys, code paths, product area, or extraction notes.",
            "additionalProperties",
            true));
    return Map.of(
        "type",
        "object",
        "additionalProperties",
        false,
        "required",
        List.of("term"),
        "properties",
        properties);
  }

  private static Map<String, Object> stringSchema(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> integerSchema(String description) {
    return Map.of("type", "integer", "description", description);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
