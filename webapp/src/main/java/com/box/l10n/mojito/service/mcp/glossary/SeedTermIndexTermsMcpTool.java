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
public class SeedTermIndexTermsMcpTool
    extends TypedMcpToolHandler<SeedTermIndexTermsMcpTool.Input> {

  private static final int MAX_TERMS = 1_000;

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term_index.seed_terms",
          "Seed raw glossary term index terms",
          "Merge externally suggested terms into Mojito's raw term index without creating glossary terms directly. Use this for Codex/product terminology, screenshot-derived term lists, or human seed lists; curators can then review the terms in the glossary workspace.",
          false,
          true,
          List.of(
              new McpToolParameter(
                  "terms",
                  "Terms to seed into the raw term index with optional definition, rationale, confidence, and metadata.",
                  true,
                  termsSchema())));

  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  public SeedTermIndexTermsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryTermIndexCurationService =
        Objects.requireNonNull(glossaryTermIndexCurationService);
  }

  public record Input(List<GlossaryTermIndexCurationService.SeedTermInput> terms) {}

  @Override
  protected Object execute(Input input) {
    return glossaryTermIndexCurationService.seedTerms(
        new GlossaryTermIndexCurationService.SeedCommand(input == null ? null : input.terms()));
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
    properties.put("definition", stringSchema("Suggested glossary definition."));
    properties.put("rationale", stringSchema("Why this term should be reviewed for the glossary."));
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
}
