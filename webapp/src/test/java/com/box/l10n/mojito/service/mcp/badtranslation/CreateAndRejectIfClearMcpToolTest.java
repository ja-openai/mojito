package com.box.l10n.mojito.service.mcp.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import org.junit.Test;
import org.mockito.Mockito;

public class CreateAndRejectIfClearMcpToolTest {

  private final TranslationIncidentService translationIncidentService =
      Mockito.mock(TranslationIncidentService.class);
  private final CreateAndRejectIfClearMcpTool tool =
      new CreateAndRejectIfClearMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), translationIncidentService);

  @Test
  public void executeRejectsWhenIncidentIsReadyToReject() {
    CreateAndRejectIfClearMcpTool.Input request =
        new CreateAndRejectIfClearMcpTool.Input(
            "string.id",
            "hr-HR",
            null,
            "Malformed ICU",
            "https://buildkite.example/4627",
            "duplicate other");
    TranslationIncidentService.CreateIncidentRequest createRequest =
        new TranslationIncidentService.CreateIncidentRequest(
            "string.id", "hr-HR", null, "Malformed ICU", "https://buildkite.example/4627");
    TranslationIncidentService.IncidentDetail createdIncident =
        TranslationIncidentMcpTestData.incidentDetail(
            91L, TranslationIncidentResolution.READY_TO_REJECT.name(), true);
    TranslationIncidentService.IncidentDetail rejectedIncident =
        TranslationIncidentMcpTestData.incidentDetail(
            91L, TranslationIncidentResolution.REJECTED.name(), false);
    when(translationIncidentService.createIncident(createRequest)).thenReturn(createdIncident);
    when(translationIncidentService.rejectIncident(
            91L, new TranslationIncidentService.RejectIncidentRequest("duplicate other")))
        .thenReturn(rejectedIncident);

    CreateAndRejectIfClearMcpTool.Result result =
        (CreateAndRejectIfClearMcpTool.Result) tool.execute(request);

    assertThat(result.rejected()).isTrue();
    assertThat(result.incident()).isEqualTo(rejectedIncident);
    verify(translationIncidentService).createIncident(createRequest);
    verify(translationIncidentService)
        .rejectIncident(
            91L, new TranslationIncidentService.RejectIncidentRequest("duplicate other"));
  }

  @Test
  public void executeLeavesIncidentForReviewWhenNotRejectable() {
    CreateAndRejectIfClearMcpTool.Input request =
        new CreateAndRejectIfClearMcpTool.Input(
            "string.id",
            "hr-HR",
            "chatgpt-web",
            "Malformed ICU",
            "https://buildkite.example/4627",
            "duplicate other");
    TranslationIncidentService.CreateIncidentRequest createRequest =
        new TranslationIncidentService.CreateIncidentRequest(
            "string.id", "hr-HR", "chatgpt-web", "Malformed ICU", "https://buildkite.example/4627");
    TranslationIncidentService.IncidentDetail createdIncident =
        TranslationIncidentMcpTestData.incidentDetail(
            91L, TranslationIncidentResolution.PENDING_REVIEW.name(), false);
    when(translationIncidentService.createIncident(createRequest)).thenReturn(createdIncident);

    CreateAndRejectIfClearMcpTool.Result result =
        (CreateAndRejectIfClearMcpTool.Result) tool.execute(request);

    assertThat(result.rejected()).isFalse();
    assertThat(result.incident()).isEqualTo(createdIncident);
    verify(translationIncidentService).createIncident(createRequest);
    verify(translationIncidentService, never()).rejectIncident(Mockito.anyLong(), Mockito.any());
  }
}
