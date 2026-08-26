package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.service.oaitranslate.AiTranslateEvaluationService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateEvaluationService.EvaluationReport;
import com.box.l10n.mojito.service.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai-translate/evaluations")
public class AiTranslateEvaluationWS {

  private final AiTranslateEvaluationService aiTranslateEvaluationService;
  private final TeamService teamService;

  public AiTranslateEvaluationWS(
      AiTranslateEvaluationService aiTranslateEvaluationService, TeamService teamService) {
    this.aiTranslateEvaluationService = aiTranslateEvaluationService;
    this.teamService = teamService;
  }

  @RequestMapping(method = RequestMethod.GET)
  @ResponseStatus(HttpStatus.OK)
  public EvaluationReport getEvaluationReport(
      @RequestParam(required = false) Long repositoryId,
      @RequestParam(required = false) String localeTag,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) Integer limit) {
    assertCurrentUserIsAdmin();
    try {
      return aiTranslateEvaluationService.getReport(repositoryId, localeTag, model, limit);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  private void assertCurrentUserIsAdmin() {
    if (!teamService.isCurrentUserAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
    }
  }
}
