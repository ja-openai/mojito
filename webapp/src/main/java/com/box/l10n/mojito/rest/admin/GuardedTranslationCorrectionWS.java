package com.box.l10n.mojito.rest.admin;

import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.BatchResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin REST adapter for the same guarded operation exposed through Mojito MCP. */
@RestController
@RequestMapping("/api/admin/translation-corrections")
public class GuardedTranslationCorrectionWS {

  private final GuardedTranslationCorrectionService correctionService;

  public GuardedTranslationCorrectionWS(GuardedTranslationCorrectionService correctionService) {
    this.correctionService = Objects.requireNonNull(correctionService);
  }

  public record Request(Boolean confirmApply, List<Correction> corrections) {}

  @PostMapping("/apply")
  public BatchResult apply(@RequestBody(required = false) Request request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    if (!Boolean.TRUE.equals(request.confirmApply())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmApply=true is required");
    }
    try {
      return correctionService.applyCorrections(request.corrections());
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }
}
