package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LinkGlossaryTermsToCandidatesMcpTool
    extends TypedMcpToolHandler<LinkGlossaryTermsToCandidatesMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.term_index.link_glossary_terms_to_candidates",
          "Link glossary terms to candidates",
          "Reconcile existing glossary terms with existing term-index candidates by normalized source text. Dry-run defaults to true and ambiguous matches are skipped unless explicitly allowed.",
          false,
          true,
          List.of(
              new McpToolParameter(
                  "glossaryId", "Glossary id. Preferred when known.", false, Long.class),
              new McpToolParameter(
                  "glossaryName",
                  "Exact glossary name. Used only when glossaryId is omitted.",
                  false),
              new McpToolParameter(
                  "dryRun",
                  "When true, report the links that would be written without changing data. Defaults to true.",
                  false,
                  Boolean.class),
              new McpToolParameter(
                  "searchQuery",
                  "Optional source-term or term-key search filter before linking.",
                  false),
              new McpToolParameter(
                  "limit",
                  "Optional max glossary terms to scan. Defaults to 1000.",
                  false,
                  Integer.class),
              new McpToolParameter(
                  "overwriteExistingLinks",
                  "When true, replace an existing primary candidate link if a better source-text match is found. Defaults to false.",
                  false,
                  Boolean.class),
              new McpToolParameter(
                  "allowAmbiguousMatches",
                  "When true, link the best candidate even if multiple candidates share the same normalized source term. Defaults to false.",
                  false,
                  Boolean.class)));

  private final GlossaryMcpSupport glossaryMcpSupport;
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  public LinkGlossaryTermsToCandidatesMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryMcpSupport glossaryMcpSupport,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryMcpSupport = Objects.requireNonNull(glossaryMcpSupport);
    this.glossaryTermIndexCurationService =
        Objects.requireNonNull(glossaryTermIndexCurationService);
  }

  public record Input(
      Long glossaryId,
      String glossaryName,
      Boolean dryRun,
      String searchQuery,
      Integer limit,
      Boolean overwriteExistingLinks,
      Boolean allowAmbiguousMatches) {}

  @Override
  protected Object execute(Input input) {
    Input effectiveInput =
        input == null ? new Input(null, null, null, null, null, null, null) : input;
    Long glossaryId =
        glossaryMcpSupport
            .resolveGlossary(effectiveInput.glossaryId(), effectiveInput.glossaryName())
            .id();
    return glossaryTermIndexCurationService.linkGlossaryTermsToCandidates(
        glossaryId,
        new GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesCommand(
            effectiveInput.dryRun(),
            effectiveInput.searchQuery(),
            effectiveInput.limit(),
            effectiveInput.overwriteExistingLinks(),
            effectiveInput.allowAmbiguousMatches()));
  }
}
