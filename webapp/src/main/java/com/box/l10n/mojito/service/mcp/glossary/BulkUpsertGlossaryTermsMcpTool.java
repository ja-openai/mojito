package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class BulkUpsertGlossaryTermsMcpTool
    extends TypedMcpToolHandler<BulkUpsertGlossaryTermsMcpTool.Input> {

  private static final int MAX_TERMS = 200;
  private static final Set<String> EVIDENCE_TYPES =
      Set.of(
          GlossaryTermEvidence.EVIDENCE_TYPE_SCREENSHOT,
          GlossaryTermEvidence.EVIDENCE_TYPE_STRING_USAGE,
          GlossaryTermEvidence.EVIDENCE_TYPE_CODE_REF,
          GlossaryTermEvidence.EVIDENCE_TYPE_NOTE);

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term.bulk_upsert",
          "Bulk upsert glossary terms",
          "Create or update glossary terms from normalized JSON. Use this after an MCP client has inspected source code or massaged CSV/Excel bootstrap data. dryRun defaults to true; set dryRun=false only after reviewing the returned plan.",
          false,
          true,
          List.of(
              new McpToolParameter(
                  "glossaryId", "Glossary id. Preferred when known.", false, Long.class),
              new McpToolParameter(
                  "glossaryName",
                  "Exact glossary name. Used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "dryRun",
                  "Defaults to true. When true, validates and returns an operation plan without writing.",
                  false,
                  Boolean.class),
              new McpToolParameter(
                  "terms",
                  "Terms to upsert. Each term needs source, optional termKey/tmTextUnitId, metadata, translations, and evidence. Evidence can link existing Mojito TUs with tmTextUnitId.",
                  true,
                  termsSchema())));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermService glossaryTermService;

  public BulkUpsertGlossaryTermsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermService glossaryTermService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
  }

  public record Input(
      Long glossaryId, String glossaryName, Boolean dryRun, List<TermInput> terms) {}

  public record TermInput(
      Long tmTextUnitId,
      String termKey,
      String source,
      String sourceComment,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      Boolean caseSensitive,
      Boolean doNotTranslate,
      List<TranslationInput> translations,
      List<EvidenceInput> evidence) {}

  public record TranslationInput(String localeTag, String target, String targetComment) {}

  public record EvidenceInput(
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight) {}

  private static Map<String, Object> termsSchema() {
    return Map.of(
        "type",
        "array",
        "description",
        "Terms to upsert. Each term needs source, optional termKey/tmTextUnitId, metadata, translations, and evidence. Evidence can link existing Mojito TUs with tmTextUnitId.",
        "minItems",
        1,
        "maxItems",
        MAX_TERMS,
        "items",
        termSchema());
  }

  private static Map<String, Object> termSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "tmTextUnitId", integerSchema("Existing glossary source tmTextUnitId to update."));
    properties.put("termKey", stringSchema("Stable glossary term key. Optional for new terms."));
    properties.put("source", stringSchema("Canonical source term text."));
    properties.put(
        "sourceComment",
        stringSchema("Canonical source text-unit comment. Also used as the glossary definition."));
    properties.put(
        "definition",
        stringSchema("Glossary definition. If sourceComment is omitted, this seeds the comment."));
    properties.put("partOfSpeech", stringSchema("Part of speech, for example noun or verb."));
    properties.put("termType", enumSchema("Term type.", GlossaryTermMetadata.TERM_TYPES));
    properties.put(
        "enforcement", enumSchema("Enforcement level.", GlossaryTermMetadata.ENFORCEMENTS));
    properties.put("status", enumSchema("Term status.", GlossaryTermMetadata.STATUSES));
    properties.put("provenance", enumSchema("Term provenance.", GlossaryTermMetadata.PROVENANCES));
    properties.put("caseSensitive", booleanSchema("Whether matching should be case-sensitive."));
    properties.put("doNotTranslate", booleanSchema("Whether the term must stay untranslated."));
    properties.put("translations", translationsSchema());
    properties.put("evidence", evidenceSchema());

    return Map.of(
        "type",
        "object",
        "additionalProperties",
        false,
        "required",
        List.of("source"),
        "properties",
        properties);
  }

  private static Map<String, Object> translationsSchema() {
    return Map.of(
        "type",
        "array",
        "description",
        "Target terms keyed by locale.",
        "items",
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("localeTag", "target"),
            "properties",
            Map.of(
                "localeTag", stringSchema("BCP-47 locale tag, for example fr or fr-CA."),
                "target", stringSchema("Target glossary term."),
                "targetComment", stringSchema("Optional target note."))));
  }

  private static Map<String, Object> evidenceSchema() {
    return Map.of(
        "type",
        "array",
        "description",
        "Supporting references for why this term belongs in the glossary.",
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

  private static Map<String, Object> booleanSchema(String description) {
    return Map.of("type", "boolean", "description", description);
  }

  private static Map<String, Object> enumSchema(String description, Set<String> values) {
    return Map.of(
        "type", "string", "description", description, "enum", values.stream().sorted().toList());
  }

  public record BulkUpsertResult(
      GlossaryRef glossary,
      boolean dryRun,
      boolean applied,
      int termCount,
      List<OperationPreview> operations,
      List<GlossaryTermService.TermView> terms) {}

  public record GlossaryRef(Long id, String name) {}

  public record OperationPreview(
      int index,
      Long tmTextUnitId,
      String termKey,
      String source,
      String status,
      String provenance,
      int translationCount,
      int evidenceCount,
      int linkedTextUnitEvidenceCount) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    GlossaryManagementService.GlossaryDetail glossary =
        glossaryMcpSupport.resolveGlossary(
            validatedInput.glossaryId(), validatedInput.glossaryName());
    boolean dryRun = validatedInput.dryRun() == null || validatedInput.dryRun();
    List<OperationPreview> operations = buildOperations(validatedInput.terms());

    if (dryRun) {
      return new BulkUpsertResult(
          new GlossaryRef(glossary.id(), glossary.name()),
          true,
          false,
          operations.size(),
          operations,
          List.of());
    }

    List<GlossaryTermService.TermView> upsertedTerms =
        new ArrayList<>(validatedInput.terms().size());
    for (TermInput term : validatedInput.terms()) {
      upsertedTerms.add(
          glossaryTermService.upsertTerm(glossary.id(), term.tmTextUnitId(), toCommand(term)));
    }
    return new BulkUpsertResult(
        new GlossaryRef(glossary.id(), glossary.name()),
        false,
        true,
        upsertedTerms.size(),
        operations,
        upsertedTerms);
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    if (input.terms() == null || input.terms().isEmpty()) {
      throw new IllegalArgumentException("terms are required");
    }
    if (input.terms().size() > MAX_TERMS) {
      throw new IllegalArgumentException("terms must contain at most " + MAX_TERMS + " entries");
    }
    for (int i = 0; i < input.terms().size(); i++) {
      TermInput term = input.terms().get(i);
      if (term == null) {
        throw new IllegalArgumentException("terms[" + i + "] is required");
      }
      requireNonBlank(term.source(), "terms[" + i + "].source");
      normalizeTermType(term.termType(), "terms[" + i + "].termType");
      normalizeEnforcement(term.enforcement(), "terms[" + i + "].enforcement");
      normalizeStatus(term.status(), "terms[" + i + "].status");
      normalizeProvenance(term.provenance(), "terms[" + i + "].provenance");
      List<EvidenceInput> evidence = term.evidence() == null ? List.of() : term.evidence();
      for (int evidenceIndex = 0; evidenceIndex < evidence.size(); evidenceIndex++) {
        EvidenceInput evidenceInput = evidence.get(evidenceIndex);
        if (evidenceInput == null) {
          throw new IllegalArgumentException(
              "terms[" + i + "].evidence[" + evidenceIndex + "] is required");
        }
        normalizeEvidenceType(
            evidenceInput.evidenceType(),
            "terms[" + i + "].evidence[" + evidenceIndex + "].evidenceType");
      }
    }
    return input;
  }

  private List<OperationPreview> buildOperations(List<TermInput> terms) {
    List<OperationPreview> operations = new ArrayList<>(terms.size());
    for (int i = 0; i < terms.size(); i++) {
      TermInput term = terms.get(i);
      List<EvidenceInput> evidence = term.evidence() == null ? List.of() : term.evidence();
      operations.add(
          new OperationPreview(
              i,
              term.tmTextUnitId(),
              normalizeOptional(term.termKey()),
              term.source().trim(),
              normalizeStatus(term.status(), null),
              normalizeProvenance(term.provenance(), null),
              term.translations() == null ? 0 : term.translations().size(),
              evidence.size(),
              (int) evidence.stream().filter(item -> item.tmTextUnitId() != null).count()));
    }
    return operations;
  }

  private GlossaryTermService.TermUpsertCommand toCommand(TermInput term) {
    return new GlossaryTermService.TermUpsertCommand(
        term.termKey(),
        term.source(),
        term.sourceComment(),
        term.definition(),
        term.partOfSpeech(),
        term.termType(),
        term.enforcement(),
        term.status(),
        term.provenance(),
        term.caseSensitive(),
        term.doNotTranslate(),
        true,
        false,
        null,
        toTranslations(term.translations()),
        toEvidence(term.evidence()));
  }

  private List<GlossaryTermService.TranslationInput> toTranslations(
      List<TranslationInput> translations) {
    if (translations == null || translations.isEmpty()) {
      return List.of();
    }
    return translations.stream()
        .map(
            translation ->
                new GlossaryTermService.TranslationInput(
                    translation.localeTag(), translation.target(), translation.targetComment()))
        .toList();
  }

  private List<GlossaryTermService.EvidenceInput> toEvidence(List<EvidenceInput> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return List.of();
    }
    return evidence.stream()
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
        .toList();
  }

  private String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private String normalizeTermType(String value, String fieldName) {
    return normalizeOptionalEnum(
        value, GlossaryTermMetadata.TERM_TYPES, GlossaryTermMetadata.TERM_TYPE_GENERAL, fieldName);
  }

  private String normalizeEnforcement(String value, String fieldName) {
    return normalizeOptionalEnum(
        value, GlossaryTermMetadata.ENFORCEMENTS, GlossaryTermMetadata.ENFORCEMENT_SOFT, fieldName);
  }

  private String normalizeStatus(String value, String fieldName) {
    return normalizeOptionalEnum(
        value, GlossaryTermMetadata.STATUSES, GlossaryTermMetadata.STATUS_CANDIDATE, fieldName);
  }

  private String normalizeProvenance(String value, String fieldName) {
    return normalizeOptionalEnum(
        value, GlossaryTermMetadata.PROVENANCES, GlossaryTermMetadata.PROVENANCE_MANUAL, fieldName);
  }

  private String normalizeEvidenceType(String value, String fieldName) {
    return normalizeOptionalEnum(value, EVIDENCE_TYPES, null, fieldName);
  }

  private String normalizeOptionalEnum(
      String value, Set<String> allowedValues, String defaultValue, String fieldName) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      if (defaultValue != null) {
        return defaultValue;
      }
      throw new IllegalArgumentException(fieldName + " is required");
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    if (!allowedValues.contains(normalized)) {
      throw new IllegalArgumentException(fieldName + " is unsupported: " + value);
    }
    return normalized;
  }
}
