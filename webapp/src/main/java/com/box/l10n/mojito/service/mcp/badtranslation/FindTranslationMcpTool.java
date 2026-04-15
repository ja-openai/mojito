package com.box.l10n.mojito.service.mcp.badtranslation;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.badtranslation.BadTranslationLookupService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class FindTranslationMcpTool
    extends TypedMcpToolHandler<BadTranslationLookupService.FindTranslationInput> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "bad_translation.find_translation",
          "Find bad-translation candidates",
          "Find translation candidates by string id and observed locale, optionally narrowed by repository.",
          true,
          true,
          List.of(
              new McpToolParameter("stringId", "Exact Mojito string id to search for.", true),
              new McpToolParameter(
                  "observedLocale",
                  "Locale observed in the failing file or log, for example hr-HR or hr_HR.",
                  true),
              new McpToolParameter(
                  "repository",
                  "Optional Mojito repository name. Omit this to search across repositories.",
                  false)));

  private final BadTranslationLookupService badTranslationLookupService;

  public FindTranslationMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      BadTranslationLookupService badTranslationLookupService) {
    super(objectMapper, BadTranslationLookupService.FindTranslationInput.class, DESCRIPTOR);
    this.badTranslationLookupService = badTranslationLookupService;
  }

  @Override
  protected Object execute(BadTranslationLookupService.FindTranslationInput input) {
    return badTranslationLookupService.findTranslation(input);
  }
}
