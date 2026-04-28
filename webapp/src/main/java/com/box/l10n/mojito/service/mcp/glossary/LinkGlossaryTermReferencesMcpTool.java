package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LinkGlossaryTermReferencesMcpTool
    extends TypedMcpToolHandler<LinkGlossaryTermReferencesMcpTool.Input> {

  private static final Set<String> EVIDENCE_TYPES =
      Set.of(
          GlossaryTermEvidence.EVIDENCE_TYPE_SCREENSHOT,
          GlossaryTermEvidence.EVIDENCE_TYPE_STRING_USAGE,
          GlossaryTermEvidence.EVIDENCE_TYPE_CODE_REF,
          GlossaryTermEvidence.EVIDENCE_TYPE_NOTE);

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term.link_references",
          "Link glossary term references",
          "Append supporting references to an existing glossary term without replacing term metadata or existing references. Use text_unit.search first when you need Mojito text unit ids to link as string usage evidence.",
          false,
          false,
          List.of(
              new McpToolParameter(
                  "glossaryId", "Glossary id. Preferred when known.", false, Long.class),
              new McpToolParameter(
                  "glossaryName",
                  "Exact glossary name. Used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "tmTextUnitId",
                  "Existing glossary source tmTextUnitId. Preferred when known.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "termKey",
                  "Existing glossary term key. Used only when tmTextUnitId is omitted.",
                  false),
              new McpToolParameter(
                  "evidence",
                  "References to append. STRING_USAGE evidence should include tmTextUnitId for an existing product string.",
                  true,
                  evidenceSchema())));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermService glossaryTermService;

  public LinkGlossaryTermReferencesMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermService glossaryTermService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
  }

  public record Input(
      Long glossaryId,
      String glossaryName,
      Long tmTextUnitId,
      String termKey,
      List<EvidenceInput> evidence) {}

  public record EvidenceInput(
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight) {}

  public record LinkReferencesResult(GlossaryRef glossary, GlossaryTermService.TermView term) {}

  public record GlossaryRef(Long id, String name) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    GlossaryManagementService.GlossaryDetail glossary =
        glossaryMcpSupport.resolveGlossary(
            validatedInput.glossaryId(), validatedInput.glossaryName());
    Long tmTextUnitId =
        validatedInput.tmTextUnitId() != null
            ? validatedInput.tmTextUnitId()
            : resolveTmTextUnitId(glossary.id(), validatedInput.termKey());

    GlossaryTermService.TermView term =
        glossaryTermService.appendTermEvidence(
            glossary.id(),
            tmTextUnitId,
            validatedInput.evidence().stream()
                .map(
                    item ->
                        new GlossaryTermService.EvidenceInput(
                            item.evidenceType(),
                            item.caption(),
                            item.imageKey(),
                            item.tmTextUnitId(),
                            item.cropX(),
                            item.cropY(),
                            item.cropWidth(),
                            item.cropHeight()))
                .toList());
    return new LinkReferencesResult(new GlossaryRef(glossary.id(), glossary.name()), term);
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    if (input.tmTextUnitId() == null && normalizeOptional(input.termKey()) == null) {
      throw new IllegalArgumentException("tmTextUnitId or termKey is required");
    }
    if (input.evidence() == null || input.evidence().isEmpty()) {
      throw new IllegalArgumentException("evidence is required");
    }
    return input;
  }

  private Long resolveTmTextUnitId(Long glossaryId, String termKey) {
    String normalizedTermKey = normalizeOptional(termKey);
    if (normalizedTermKey == null) {
      throw new IllegalArgumentException("termKey is required");
    }
    return glossaryTermService
        .searchTerms(glossaryId, normalizedTermKey, List.of(), 500)
        .terms()
        .stream()
        .filter(term -> normalizedTermKey.equals(term.termKey()))
        .map(GlossaryTermService.TermView::tmTextUnitId)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Glossary term not found: " + termKey));
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private static Map<String, Object> evidenceSchema() {
    return Map.of(
        "type",
        "array",
        "minItems",
        1,
        "items",
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("evidenceType"),
            "properties",
            Map.of(
                "evidenceType", enumSchema("Reference type.", EVIDENCE_TYPES),
                "caption", stringSchema("Reference note or caption."),
                "imageKey", stringSchema("Stored screenshot image key."),
                "tmTextUnitId", integerSchema("Existing Mojito text unit id used as evidence."),
                "cropX", integerSchema("Optional screenshot crop x coordinate."),
                "cropY", integerSchema("Optional screenshot crop y coordinate."),
                "cropWidth", integerSchema("Optional screenshot crop width."),
                "cropHeight", integerSchema("Optional screenshot crop height."))));
  }

  private static Map<String, Object> stringSchema(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> integerSchema(String description) {
    return Map.of("type", "integer", "description", description);
  }

  private static Map<String, Object> enumSchema(String description, Set<String> values) {
    return Map.of(
        "type", "string", "description", description, "enum", values.stream().sorted().toList());
  }
}
