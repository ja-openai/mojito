package com.box.l10n.mojito.service.mcp.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import org.junit.Test;
import org.mockito.Mockito;

public class CreateIncidentMcpToolTest {

  private final TranslationIncidentService translationIncidentService =
      Mockito.mock(TranslationIncidentService.class);
  private final CreateIncidentMcpTool tool =
      new CreateIncidentMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), translationIncidentService);

  @Test
  public void executeCreatesIncidentThroughService() {
    TranslationIncidentService.CreateIncidentRequest request =
        new TranslationIncidentService.CreateIncidentRequest(
            "string.id", "hr-HR", null, "Malformed ICU", "https://buildkite.example/4627");
    TranslationIncidentService.IncidentDetail detail =
        new TranslationIncidentService.IncidentDetail(
            91L,
            "READY_TO_REJECT",
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
            11L,
            12L,
            13L,
            "/src/a.ts",
            "source",
            "target",
            "comment",
            "APPROVED",
            true,
            true,
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
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(translationIncidentService.createIncident(request)).thenReturn(detail);

    Object result = tool.execute(request);

    assertThat(result).isEqualTo(detail);
    verify(translationIncidentService).createIncident(request);
  }
}
