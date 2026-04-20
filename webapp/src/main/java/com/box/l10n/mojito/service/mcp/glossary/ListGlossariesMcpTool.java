package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ListGlossariesMcpTool extends TypedMcpToolHandler<ListGlossariesMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.list",
          "List glossaries",
          "List Mojito glossaries visible to the current user. Use this before managing glossary terms when the glossary id is unknown.",
          true,
          true,
          List.of(
              new McpToolParameter("searchQuery", "Optional glossary name search.", false),
              new McpToolParameter(
                  "enabled",
                  "Optional enabled filter. Non-admin users only see enabled glossaries.",
                  false),
              new McpToolParameter(
                  "limit", "Optional max results to return. Defaults to 50, max 200.", false)));

  private final GlossaryManagementService glossaryManagementService;

  public ListGlossariesMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryManagementService glossaryManagementService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryManagementService = Objects.requireNonNull(glossaryManagementService);
  }

  public record Input(String searchQuery, Boolean enabled, Integer limit) {}

  @Override
  protected Object execute(Input input) {
    Input effectiveInput = input == null ? new Input(null, null, null) : input;
    return glossaryManagementService.searchGlossaries(
        effectiveInput.searchQuery(), effectiveInput.enabled(), effectiveInput.limit());
  }
}
