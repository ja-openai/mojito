package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.service.oaitranslate.AiTranslateSourcePromptRuleService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateSourcePromptRuleService.RegexMatch;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateSourcePromptRuleService.SourcePromptRule;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateSourcePromptRuleService.SourcePromptRuleInput;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai-translate/source-prompt-rules")
public class AiTranslateSourcePromptRuleWS {

  private final AiTranslateSourcePromptRuleService aiTranslateSourcePromptRuleService;
  private final TeamService teamService;

  public AiTranslateSourcePromptRuleWS(
      AiTranslateSourcePromptRuleService aiTranslateSourcePromptRuleService,
      TeamService teamService) {
    this.aiTranslateSourcePromptRuleService = aiTranslateSourcePromptRuleService;
    this.teamService = teamService;
  }

  @RequestMapping(method = RequestMethod.GET)
  @ResponseStatus(HttpStatus.OK)
  public List<SourcePromptRuleResponse> getSourcePromptRules() {
    assertCurrentUserIsAdmin();
    return aiTranslateSourcePromptRuleService.getAll().stream().map(this::toResponse).toList();
  }

  @RequestMapping(method = RequestMethod.PUT)
  @ResponseStatus(HttpStatus.OK)
  public SourcePromptRuleResponse upsertSourcePromptRule(
      @RequestBody SourcePromptRuleRequest request) {
    assertCurrentUserIsAdmin();
    try {
      return toResponse(
          aiTranslateSourcePromptRuleService.upsert(
              new SourcePromptRuleInput(
                  request.id(),
                  request.name(),
                  request.description(),
                  request.enabled(),
                  request.priority(),
                  request.matchType(),
                  request.sourceRegex(),
                  request.promptSuffix())));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @RequestMapping(method = RequestMethod.DELETE, value = "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSourcePromptRule(@PathVariable long id) {
    assertCurrentUserIsAdmin();
    try {
      aiTranslateSourcePromptRuleService.delete(id);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @RequestMapping(method = RequestMethod.POST, value = "/test")
  @ResponseStatus(HttpStatus.OK)
  public SourcePromptRuleTestResponse testSourcePromptRule(
      @RequestBody SourcePromptRuleTestRequest request) {
    assertCurrentUserIsAdmin();
    try {
      var result =
          aiTranslateSourcePromptRuleService.test(request.sourceRegex(), request.sourceText());
      return new SourcePromptRuleTestResponse(result.matches(), result.matchesList());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  public record SourcePromptRuleRequest(
      Long id,
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String matchType,
      String sourceRegex,
      String promptSuffix) {}

  public record SourcePromptRuleResponse(
      Long id,
      String name,
      String description,
      boolean enabled,
      int priority,
      String matchType,
      String sourceRegex,
      String promptSuffix,
      String createdAt,
      String updatedAt) {}

  public record SourcePromptRuleTestRequest(String sourceRegex, String sourceText) {}

  public record SourcePromptRuleTestResponse(boolean matches, List<RegexMatch> matchesList) {}

  private SourcePromptRuleResponse toResponse(SourcePromptRule row) {
    return new SourcePromptRuleResponse(
        row.id(),
        row.name(),
        row.description(),
        row.enabled(),
        row.priority(),
        row.matchType(),
        row.sourceRegex(),
        row.promptSuffix(),
        row.createdAt() == null ? null : row.createdAt().toString(),
        row.updatedAt() == null ? null : row.updatedAt().toString());
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    if (ex.getMessage() != null && ex.getMessage().startsWith("Source prompt rule not found:")) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  private void assertCurrentUserIsAdmin() {
    if (!teamService.isCurrentUserAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
    }
  }
}
