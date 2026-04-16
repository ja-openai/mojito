package com.box.l10n.mojito.service.mcp.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import org.junit.Test;
import org.mockito.Mockito;

public class RejectIncidentMcpToolTest {

  private final TranslationIncidentService translationIncidentService =
      Mockito.mock(TranslationIncidentService.class);
  private final RejectIncidentMcpTool tool =
      new RejectIncidentMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), translationIncidentService);

  @Test
  public void executeRejectsStoredIncidentThroughService() {
    RejectIncidentMcpTool.Input request = new RejectIncidentMcpTool.Input(91L, "duplicate other");
    TranslationIncidentService.IncidentDetail detail =
        TranslationIncidentMcpTestData.incidentDetail(
            91L, TranslationIncidentResolution.REJECTED.name(), false);
    when(translationIncidentService.rejectIncident(
            91L, new TranslationIncidentService.RejectIncidentRequest("duplicate other")))
        .thenReturn(detail);

    Object result = tool.execute(request);

    assertThat(result).isEqualTo(detail);
    verify(translationIncidentService)
        .rejectIncident(
            91L, new TranslationIncidentService.RejectIncidentRequest("duplicate other"));
  }
}
