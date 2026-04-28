package com.box.l10n.mojito.service.mcp.glossary;

import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class CreateOrUpdateGlossaryMcpTool
    extends TypedMcpToolHandler<CreateOrUpdateGlossaryMcpTool.Input> {

  private static final Set<String> SCOPE_MODES =
      Set.of(Glossary.SCOPE_MODE_GLOBAL, Glossary.SCOPE_MODE_SELECTED_REPOSITORIES);

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "glossary.create_or_update",
          "Create or update glossary",
          "Create a new Mojito glossary, or update an existing glossary when glossaryId is provided. This creates the managed backing repository and configures locales and repository scope.",
          false,
          false,
          List.of(
              new McpToolParameter(
                  "glossaryId",
                  "Existing glossary id to update. Omit to create a new glossary.",
                  false,
                  Long.class),
              new McpToolParameter("name", "Glossary name. Required for create and update.", true),
              new McpToolParameter("description", "Optional glossary description.", false),
              new McpToolParameter(
                  "enabled",
                  "Whether the glossary is enabled. Defaults to true.",
                  false,
                  Boolean.class),
              new McpToolParameter(
                  "priority", "Glossary priority. Lower values sort first.", false, Integer.class),
              new McpToolParameter(
                  "scopeMode",
                  "Glossary scope mode.",
                  false,
                  enumSchema("Glossary scope mode.", SCOPE_MODES)),
              new McpToolParameter(
                  "localeTags",
                  "Locale tags enabled on the backing glossary repository.",
                  false,
                  stringArraySchema(
                      "Locale tags enabled on the backing glossary repository, for example [\"fr\", \"fr-CA\"].")),
              new McpToolParameter(
                  "repositoryIds",
                  "Repositories included when scopeMode is SELECTED_REPOSITORIES.",
                  false,
                  integerArraySchema(
                      "Repositories included when scopeMode is SELECTED_REPOSITORIES.")),
              new McpToolParameter(
                  "excludedRepositoryIds",
                  "Repositories excluded when scopeMode is GLOBAL.",
                  false,
                  integerArraySchema("Repositories excluded when scopeMode is GLOBAL."))));

  private final GlossaryManagementService glossaryManagementService;

  public CreateOrUpdateGlossaryMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      GlossaryManagementService glossaryManagementService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.glossaryManagementService = Objects.requireNonNull(glossaryManagementService);
  }

  public record Input(
      Long glossaryId,
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String scopeMode,
      List<String> localeTags,
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds) {}

  public record CreateOrUpdateResult(
      String operation, GlossaryManagementService.GlossaryDetail glossary) {}

  @Override
  protected Object execute(Input input) {
    Input validatedInput = validate(input);
    GlossaryManagementService.GlossaryDetail glossary =
        validatedInput.glossaryId() == null
            ? glossaryManagementService.createGlossary(
                validatedInput.name(),
                validatedInput.description(),
                validatedInput.enabled(),
                validatedInput.priority(),
                validatedInput.scopeMode(),
                validatedInput.localeTags(),
                validatedInput.repositoryIds(),
                validatedInput.excludedRepositoryIds())
            : glossaryManagementService.updateGlossary(
                validatedInput.glossaryId(),
                validatedInput.name(),
                validatedInput.description(),
                validatedInput.enabled(),
                validatedInput.priority(),
                validatedInput.scopeMode(),
                validatedInput.localeTags(),
                validatedInput.repositoryIds(),
                validatedInput.excludedRepositoryIds());

    return new CreateOrUpdateResult(
        validatedInput.glossaryId() == null ? "CREATE" : "UPDATE", glossary);
  }

  private Input validate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    if (input.name() == null || input.name().trim().isEmpty()) {
      throw new IllegalArgumentException("name is required");
    }
    return input;
  }

  private static Map<String, Object> stringArraySchema(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
  }

  private static Map<String, Object> integerArraySchema(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "integer"));
  }

  private static Map<String, Object> enumSchema(String description, Set<String> values) {
    return Map.of(
        "type", "string", "description", description, "enum", values.stream().sorted().toList());
  }
}
