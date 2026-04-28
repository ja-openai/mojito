package com.box.l10n.mojito.service.mcp.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import java.util.List;
import org.junit.Test;

public class CreateOrUpdateGlossaryMcpToolTest {

  private final FakeGlossaryManagementService glossaryManagementService =
      new FakeGlossaryManagementService();
  private final CreateOrUpdateGlossaryMcpTool tool =
      new CreateOrUpdateGlossaryMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), glossaryManagementService);

  @Test
  public void executeCreatesGlossaryWhenIdIsMissing() {
    Object result =
        tool.execute(
            new CreateOrUpdateGlossaryMcpTool.Input(
                null,
                "chatgpt-web",
                "ChatGPT web glossary",
                true,
                10,
                "SELECTED_REPOSITORIES",
                List.of("fr"),
                List.of(2L),
                List.of()));

    assertThat(glossaryManagementService.created).isTrue();
    assertThat(glossaryManagementService.updated).isFalse();
    assertThat(glossaryManagementService.lastName).isEqualTo("chatgpt-web");
    assertThat(glossaryManagementService.lastRepositoryIds).containsExactly(2L);
    assertThat(result)
        .isEqualTo(
            new CreateOrUpdateGlossaryMcpTool.CreateOrUpdateResult("CREATE", glossaryDetail()));
  }

  @Test
  public void executeUpdatesGlossaryWhenIdIsPresent() {
    Object result =
        tool.execute(
            new CreateOrUpdateGlossaryMcpTool.Input(
                6L,
                "ghanna",
                null,
                true,
                0,
                "GLOBAL",
                List.of("fr", "fr-CA"),
                List.of(),
                List.of()));

    assertThat(glossaryManagementService.created).isFalse();
    assertThat(glossaryManagementService.updated).isTrue();
    assertThat(glossaryManagementService.lastGlossaryId).isEqualTo(6L);
    assertThat(glossaryManagementService.lastLocaleTags).containsExactly("fr", "fr-CA");
    assertThat(result)
        .isEqualTo(
            new CreateOrUpdateGlossaryMcpTool.CreateOrUpdateResult("UPDATE", glossaryDetail()));
  }

  @Test
  public void executeRejectsMissingName() {
    assertThatThrownBy(
            () ->
                tool.execute(
                    new CreateOrUpdateGlossaryMcpTool.Input(
                        null, " ", null, null, null, null, List.of(), List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("name is required");
  }

  private static final class FakeGlossaryManagementService extends GlossaryManagementService {
    private boolean created;
    private boolean updated;
    private Long lastGlossaryId;
    private String lastName;
    private List<String> lastLocaleTags;
    private List<Long> lastRepositoryIds;

    private FakeGlossaryManagementService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public GlossaryDetail createGlossary(
        String name,
        String description,
        Boolean enabled,
        Integer priority,
        String scopeMode,
        List<String> localeTags,
        List<Long> repositoryIds,
        List<Long> excludedRepositoryIds) {
      created = true;
      lastName = name;
      lastLocaleTags = localeTags;
      lastRepositoryIds = repositoryIds;
      return glossaryDetail();
    }

    @Override
    public GlossaryDetail updateGlossary(
        Long glossaryId,
        String name,
        String description,
        Boolean enabled,
        Integer priority,
        String scopeMode,
        List<String> localeTags,
        List<Long> repositoryIds,
        List<Long> excludedRepositoryIds) {
      updated = true;
      lastGlossaryId = glossaryId;
      lastName = name;
      lastLocaleTags = localeTags;
      lastRepositoryIds = repositoryIds;
      return glossaryDetail();
    }
  }

  private static GlossaryManagementService.GlossaryDetail glossaryDetail() {
    return new GlossaryManagementService.GlossaryDetail(
        6L,
        null,
        null,
        "ghanna",
        "ChatGPT web glossary",
        true,
        10,
        "SELECTED_REPOSITORIES",
        new GlossaryManagementService.RepositoryRef(14L, "glossary-ghanna"),
        "glossary",
        List.of("fr"),
        List.of(new GlossaryManagementService.RepositoryRef(2L, "chatgpt-web")),
        List.of());
  }
}
