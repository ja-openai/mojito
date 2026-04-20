package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class GlossaryMcpSupport {

  private final GlossaryManagementService glossaryManagementService;

  GlossaryMcpSupport(GlossaryManagementService glossaryManagementService) {
    this.glossaryManagementService = Objects.requireNonNull(glossaryManagementService);
  }

  GlossaryManagementService.GlossaryDetail resolveGlossary(Long glossaryId, String glossaryName) {
    if (glossaryId != null) {
      return glossaryManagementService.getGlossary(glossaryId);
    }

    String normalizedName = normalizeOptional(glossaryName);
    if (normalizedName == null) {
      throw new IllegalArgumentException("glossaryId or glossaryName is required");
    }

    return glossaryManagementService
        .searchGlossaries(normalizedName, null, null)
        .glossaries()
        .stream()
        .filter(glossary -> glossary.name().equalsIgnoreCase(normalizedName))
        .findFirst()
        .map(glossary -> glossaryManagementService.getGlossary(glossary.id()))
        .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + normalizedName));
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }
}
