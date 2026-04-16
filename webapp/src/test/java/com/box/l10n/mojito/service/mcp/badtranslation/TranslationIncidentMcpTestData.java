package com.box.l10n.mojito.service.mcp.badtranslation;

import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import java.util.List;

final class TranslationIncidentMcpTestData {

  private TranslationIncidentMcpTestData() {}

  static TranslationIncidentService.IncidentDetail incidentDetail(
      Long id, String status, boolean canReject) {
    return new TranslationIncidentService.IncidentDetail(
        id,
        "OPEN",
        status,
        "chatgpt-web",
        "string.id",
        "hr-HR",
        "hr",
        "UNIQUE_MATCH",
        "LANGUAGE_ONLY",
        true,
        "Malformed ICU",
        "https://buildkite.example/4627",
        1,
        "https://mojito.example/translation-incidents?incidentId=" + id,
        11L,
        "https://mojito.example/text-units/11?locale=hr",
        12L,
        13L,
        "/src/a.ts",
        "source",
        "target",
        "comment",
        canReject ? "APPROVED" : "NEEDS_REVIEW",
        true,
        canReject,
        31L,
        41L,
        "Request A",
        "https://mojito.example/review-projects/31",
        "HIGH",
        90,
        "translator-a",
        "reviewer-a",
        "pm-a",
        null,
        null,
        null,
        "TEAM_CHANNEL",
        "C123",
        null,
        true,
        "team channel",
        "slack draft",
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
