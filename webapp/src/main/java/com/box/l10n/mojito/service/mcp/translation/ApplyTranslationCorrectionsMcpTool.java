package com.box.l10n.mojito.service.mcp.translation;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Admin-only MCP adapter for atomically guarded translation corrections. */
@Component
public class ApplyTranslationCorrectionsMcpTool
    extends TypedMcpToolHandler<ApplyTranslationCorrectionsMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "translation.apply_corrections",
          "Apply guarded translation corrections",
          "Apply reviewed target-locale translation replacements only for EMERGENCY, NORMAL, or BUG_FIXES Review Projects and when every repository, locale, TM text-unit, current-variant, and exact-old-target guard still matches. Source-locale and all other Review Project types are rejected. Each row is independently locked, written as REVIEW_NEEDED, and read back. Requires an admin and confirmApply=true.",
          false,
          false,
          List.of(
              new McpToolParameter(
                  "confirmApply",
                  "Must be true to acknowledge that this operation mutates current translations.",
                  true,
                  Boolean.class),
              new McpToolParameter(
                  "corrections",
                  "Guarded corrections. Results preserve input order and report APPLIED, CONFLICT, or ERROR without echoing expected/replacement payloads on failures.",
                  true,
                  correctionsSchema())));

  private final GuardedTranslationCorrectionService correctionService;

  public ApplyTranslationCorrectionsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GuardedTranslationCorrectionService correctionService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.correctionService = Objects.requireNonNull(correctionService);
  }

  public record Input(Boolean confirmApply, List<Correction> corrections) {}

  @Override
  protected Object execute(Input input) {
    requireCurrentAuthenticationAdmin();
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    if (!Boolean.TRUE.equals(input.confirmApply())) {
      throw new IllegalArgumentException("confirmApply=true is required");
    }
    return correctionService.applyCorrections(input.corrections());
  }

  private static Map<String, Object> correctionsSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("reviewProjectId", positiveIntegerSchema("Review Project id."));
    properties.put(
        "reviewProjectTextUnitId", positiveIntegerSchema("Review Project text-unit id."));
    properties.put("repositoryId", positiveIntegerSchema("Repository id."));
    properties.put(
        "repositoryName",
        stringSchema(
            "Exact repository name.",
            GuardedTranslationCorrectionService.MAX_REPOSITORY_NAME_CHARACTERS));
    properties.put(
        "locale",
        stringSchema(
            "Exact Review Project BCP-47 locale tag.",
            GuardedTranslationCorrectionService.MAX_LOCALE_CHARACTERS));
    properties.put("tmTextUnitId", positiveIntegerSchema("TM text-unit id."));
    properties.put(
        "expectedCurrentVariantId", positiveIntegerSchema("Expected current variant id."));
    properties.put(
        "expectedOldTarget",
        stringSchema(
            "Exact expected stored target. This guard is not normalized.",
            GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS));
    properties.put(
        "replacementTarget",
        stringSchema(
            "Exact requested replacement. Mojito NFC normalization is applied on write.",
            GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS));

    return Map.of(
        "type",
        "array",
        "minItems",
        1,
        "maxItems",
        GuardedTranslationCorrectionService.MAX_CORRECTIONS,
        "items",
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of(
                "reviewProjectId",
                "reviewProjectTextUnitId",
                "repositoryId",
                "repositoryName",
                "locale",
                "tmTextUnitId",
                "expectedCurrentVariantId",
                "expectedOldTarget",
                "replacementTarget"),
            "properties",
            properties));
  }

  private static Map<String, Object> positiveIntegerSchema(String description) {
    return Map.of("type", "integer", "minimum", 1, "description", description);
  }

  private static Map<String, Object> stringSchema(String description, int maxLength) {
    return Map.of("type", "string", "maxLength", maxLength, "description", description);
  }

  @Override
  protected void authorizeBeforeInputConversion(JsonNode arguments) {
    requireCurrentAuthenticationAdmin();
  }

  private static void requireCurrentAuthenticationAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean admin =
        authentication != null
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    if (!admin) {
      throw new AccessDeniedException("Admin role required");
    }
  }
}
