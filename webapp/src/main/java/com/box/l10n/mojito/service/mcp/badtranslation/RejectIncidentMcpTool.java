package com.box.l10n.mojito.service.mcp.badtranslation;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RejectIncidentMcpTool extends TypedMcpToolHandler<RejectIncidentMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "bad_translation.reject_incident",
          "Reject translation incident",
          "Reject the translation candidate already stored on a translation incident. This mutates Mojito and writes the incident audit trail.",
          false,
          false,
          List.of(
              new McpToolParameter(
                  "incidentId", "Persisted translation incident id to reject.", true),
              new McpToolParameter(
                  "comment",
                  "Optional operator note appended to the rejection audit comment.",
                  false)));

  public record Input(Long incidentId, String comment) {}

  private final TranslationIncidentService translationIncidentService;

  public RejectIncidentMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TranslationIncidentService translationIncidentService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.translationIncidentService = translationIncidentService;
  }

  @Override
  protected Object execute(Input input) {
    if (input.incidentId() == null) {
      throw new IllegalArgumentException("incidentId is required");
    }

    return translationIncidentService.rejectIncident(
        input.incidentId(), new TranslationIncidentService.RejectIncidentRequest(input.comment()));
  }
}
