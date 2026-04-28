package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptService.TextUnitAttemptSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/textunits/{tmTextUnitId}/ai-translate-attempts")
public class AiTranslateTextUnitAttemptWS {

  private final AiTranslateTextUnitAttemptService aiTranslateTextUnitAttemptService;
  private final LocaleService localeService;

  public AiTranslateTextUnitAttemptWS(
      AiTranslateTextUnitAttemptService aiTranslateTextUnitAttemptService,
      LocaleService localeService) {
    this.aiTranslateTextUnitAttemptService = aiTranslateTextUnitAttemptService;
    this.localeService = localeService;
  }

  @RequestMapping(method = RequestMethod.GET)
  @ResponseStatus(HttpStatus.OK)
  public List<TextUnitAttemptSummary> getTextUnitAttempts(
      @PathVariable Long tmTextUnitId,
      @RequestParam(value = "bcp47Tag", required = true) String bcp47Tag) {
    Locale locale = findLocale(bcp47Tag);
    return aiTranslateTextUnitAttemptService.getTextUnitAttempts(tmTextUnitId, locale.getId());
  }

  @RequestMapping(method = RequestMethod.GET, value = "/{attemptId}")
  public TextUnitAttemptSummary getTextUnitAttempt(
      @PathVariable Long tmTextUnitId,
      @PathVariable Long attemptId,
      @RequestParam(value = "bcp47Tag", required = true) String bcp47Tag) {
    Locale locale = findLocale(bcp47Tag);
    return aiTranslateTextUnitAttemptService
        .getTextUnitAttempt(tmTextUnitId, locale.getId(), attemptId)
        .orElseThrow(this::attemptNotFound);
  }

  @RequestMapping(method = RequestMethod.GET, value = "/{attemptId}/request")
  public ResponseEntity<String> getRequestPayload(
      @PathVariable Long tmTextUnitId,
      @PathVariable Long attemptId,
      @RequestParam(value = "bcp47Tag", required = true) String bcp47Tag) {
    Locale locale = findLocale(bcp47Tag);
    String payload =
        aiTranslateTextUnitAttemptService
            .getRequestPayload(tmTextUnitId, locale.getId(), attemptId)
            .orElseThrow(this::payloadNotFound);
    return jsonPayload(payload);
  }

  @RequestMapping(method = RequestMethod.GET, value = "/{attemptId}/response")
  public ResponseEntity<String> getResponsePayload(
      @PathVariable Long tmTextUnitId,
      @PathVariable Long attemptId,
      @RequestParam(value = "bcp47Tag", required = true) String bcp47Tag) {
    Locale locale = findLocale(bcp47Tag);
    String payload =
        aiTranslateTextUnitAttemptService
            .getResponsePayload(tmTextUnitId, locale.getId(), attemptId)
            .orElseThrow(this::payloadNotFound);
    return jsonPayload(payload);
  }

  private Locale findLocale(String bcp47Tag) {
    Locale locale = localeService.findByBcp47Tag(bcp47Tag);
    if (locale == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Locale not found: " + bcp47Tag);
    }
    return locale;
  }

  private ResponseStatusException attemptNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "AI translate attempt not found");
  }

  private ResponseStatusException payloadNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "AI translate payload not found");
  }

  private ResponseEntity<String> jsonPayload(String payload) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
  }
}
