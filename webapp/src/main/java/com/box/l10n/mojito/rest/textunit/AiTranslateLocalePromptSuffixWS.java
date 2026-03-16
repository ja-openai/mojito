package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.service.oaitranslate.AiTranslateLocalePromptSuffixService;
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
@RequestMapping("/api/ai-translate/locale-prompt-suffixes")
public class AiTranslateLocalePromptSuffixWS {

  private final AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService;
  private final TeamService teamService;

  public AiTranslateLocalePromptSuffixWS(
      AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService,
      TeamService teamService) {
    this.aiTranslateLocalePromptSuffixService = aiTranslateLocalePromptSuffixService;
    this.teamService = teamService;
  }

  @RequestMapping(method = RequestMethod.GET)
  @ResponseStatus(HttpStatus.OK)
  public List<LocalePromptSuffixResponse> getLocalePromptSuffixes() {
    assertCurrentUserIsAdmin();
    return aiTranslateLocalePromptSuffixService.getAll().stream().map(this::toResponse).toList();
  }

  @RequestMapping(method = RequestMethod.PUT)
  @ResponseStatus(HttpStatus.OK)
  public LocalePromptSuffixResponse upsertLocalePromptSuffix(
      @RequestBody LocalePromptSuffixRequest request) {
    assertCurrentUserIsAdmin();
    try {
      var row =
          aiTranslateLocalePromptSuffixService.upsert(request.localeTag(), request.promptSuffix());
      return toResponse(row);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @RequestMapping(method = RequestMethod.DELETE, value = "/{localeTag}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteLocalePromptSuffix(@PathVariable String localeTag) {
    assertCurrentUserIsAdmin();
    try {
      aiTranslateLocalePromptSuffixService.delete(localeTag);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  public record LocalePromptSuffixRequest(String localeTag, String promptSuffix) {}

  public record LocalePromptSuffixResponse(
      String localeTag, String promptSuffix, String createdAt, String updatedAt) {}

  private LocalePromptSuffixResponse toResponse(
      AiTranslateLocalePromptSuffixService.LocalePromptSuffix row) {
    return new LocalePromptSuffixResponse(
        row.localeTag(),
        row.promptSuffix(),
        row.createdAt() == null ? null : row.createdAt().toString(),
        row.updatedAt() == null ? null : row.updatedAt().toString());
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    if (ex.getMessage() != null
        && (ex.getMessage().startsWith("Locale not found:")
            || ex.getMessage().startsWith("Locale prompt suffix not found:"))) {
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
