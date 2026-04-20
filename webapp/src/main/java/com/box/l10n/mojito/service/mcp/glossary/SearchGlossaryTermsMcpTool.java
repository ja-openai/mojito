package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SearchGlossaryTermsMcpTool
    extends TypedMcpToolHandler<SearchGlossaryTermsMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term.search",
          "Search glossary terms",
          "Search terms in a Mojito glossary. Returns source term metadata, translations for requested locales, and supporting references.",
          true,
          true,
          List.of(
              new McpToolParameter("glossaryId", "Glossary id. Preferred when known.", false),
              new McpToolParameter(
                  "glossaryName",
                  "Exact glossary name. Used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "searchQuery",
                  "Optional search across source, definition, translations, and evidence captions.",
                  false),
              new McpToolParameter(
                  "localeTags",
                  "Optional locale tags to include, for example [\"fr\", \"de-DE\"].",
                  false),
              new McpToolParameter(
                  "limit", "Optional max terms to return. Defaults to 100, max 500.", false)));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermService glossaryTermService;

  public SearchGlossaryTermsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermService glossaryTermService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermService = Objects.requireNonNull(glossaryTermService);
  }

  public record Input(
      Long glossaryId,
      String glossaryName,
      String searchQuery,
      List<String> localeTags,
      Integer limit) {}

  public record SearchResult(
      GlossaryRef glossary,
      String searchQuery,
      List<String> localeTags,
      long totalCount,
      List<GlossaryTermService.TermView> terms) {}

  public record GlossaryRef(Long id, String name) {}

  @Override
  protected Object execute(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }

    GlossaryManagementService.GlossaryDetail glossary =
        glossaryMcpSupport.resolveGlossary(input.glossaryId(), input.glossaryName());
    GlossaryTermService.SearchTermsView view =
        glossaryTermService.searchTerms(
            glossary.id(), input.searchQuery(), input.localeTags(), input.limit());
    return new SearchResult(
        new GlossaryRef(glossary.id(), glossary.name()),
        normalizeOptional(input.searchQuery()),
        view.localeTags(),
        view.totalCount(),
        view.terms());
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }
}
