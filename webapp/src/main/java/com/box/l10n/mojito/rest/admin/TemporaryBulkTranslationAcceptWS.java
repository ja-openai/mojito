package com.box.l10n.mojito.rest.admin;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.tm.temporarybulkaccept.TemporaryBulkTranslationAcceptService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary admin-only cleanup endpoint for bulk accepting translations.
 *
 * <p>Delete this controller together with the paired service/frontend page when the cleanup is no
 * longer needed.
 */
@RestController
@RequestMapping("/api/admin/temporary-bulk-translation-accept")
public class TemporaryBulkTranslationAcceptWS {

  private final TemporaryBulkTranslationAcceptService service;

  public TemporaryBulkTranslationAcceptWS(TemporaryBulkTranslationAcceptService service) {
    this.service = service;
  }

  @PostMapping("/dry-run")
  public PollableTask dryRun(@RequestBody Request request) {
    return service.dryRunAsync(toServiceRequest(request)).getPollableTask();
  }

  @PostMapping("/execute")
  public PollableTask execute(@RequestBody Request request) {
    return service.executeAsync(toServiceRequest(request)).getPollableTask();
  }

  private TemporaryBulkTranslationAcceptService.Request toServiceRequest(Request request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    try {
      return new TemporaryBulkTranslationAcceptService.Request(
          request.selector(), request.repositoryIds(), request.createdBeforeDate());
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  public record Request(
      TemporaryBulkTranslationAcceptService.Selector selector,
      List<Long> repositoryIds,
      LocalDate createdBeforeDate) {}
}
