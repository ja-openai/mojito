package com.box.l10n.mojito.service.mcp.badtranslation;

import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class CreateAndRejectIfClearMcpTool
    extends TypedMcpToolHandler<CreateAndRejectIfClearMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "bad_translation.create_and_reject_if_clear",
          "Create incident and reject if clear",
          "Create a translation incident, then auto-reject only when the incident resolves to a single clear rejectable candidate.",
          false,
          false,
          List.of(
              new McpToolParameter("stringId", "Exact Mojito string id to investigate.", true),
              new McpToolParameter(
                  "observedLocale",
                  "Locale observed in the failing file or log, for example hr-HR or hr_HR.",
                  true),
              new McpToolParameter(
                  "repository",
                  "Optional Mojito repository name. Omit this to search across repositories.",
                  false),
              new McpToolParameter(
                  "reason",
                  "Short reason for the incident, such as malformed ICU or failing CI symptom.",
                  true),
              new McpToolParameter(
                  "sourceReference",
                  "Optional build URL or operator reference string captured with the incident.",
                  false),
              new McpToolParameter(
                  "comment",
                  "Optional operator note appended if the tool performs the rejection.",
                  false)));

  public record Input(
      String stringId,
      String observedLocale,
      String repository,
      String reason,
      String sourceReference,
      String comment) {}

  public record Result(TranslationIncidentService.IncidentDetail incident, boolean rejected) {}

  private final TranslationIncidentService translationIncidentService;

  public CreateAndRejectIfClearMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TranslationIncidentService translationIncidentService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.translationIncidentService = translationIncidentService;
  }

  @Override
  protected Object execute(Input input) {
    TranslationIncidentService.IncidentDetail incident =
        translationIncidentService.createIncident(
            new TranslationIncidentService.CreateIncidentRequest(
                input.stringId(),
                input.observedLocale(),
                input.repository(),
                input.reason(),
                input.sourceReference()));

    if (incident.id() != null
        && incident.canReject()
        && TranslationIncidentResolution.READY_TO_REJECT.name().equals(incident.resolution())) {
      return new Result(
          translationIncidentService.rejectIncident(
              incident.id(), new TranslationIncidentService.RejectIncidentRequest(input.comment())),
          true);
    }

    return new Result(incident, false);
  }
}
