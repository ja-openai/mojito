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
public class CreateIncidentMcpTool
    extends TypedMcpToolHandler<TranslationIncidentService.CreateIncidentRequest> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "bad_translation.create_incident",
          "Create translation incident",
          "Create a persisted translation incident from a string id and observed locale so Mojito can store lookup context before any rejection decision.",
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
                  false)));

  private final TranslationIncidentService translationIncidentService;

  public CreateIncidentMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TranslationIncidentService translationIncidentService) {
    super(objectMapper, TranslationIncidentService.CreateIncidentRequest.class, DESCRIPTOR);
    this.translationIncidentService = translationIncidentService;
  }

  @Override
  protected Object execute(TranslationIncidentService.CreateIncidentRequest input) {
    return translationIncidentService.createIncident(input);
  }
}
