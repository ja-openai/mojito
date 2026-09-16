package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationService;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Evidence;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationStore.Run;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Existing /api/monitoring/** security rules restrict this API to administrators. */
@RestController
@RequestMapping("/api/monitoring/blob-migrations")
public class BlobMigrationWS {
  private final BlobMigrationService service;

  public BlobMigrationWS(BlobMigrationService service) {
    this.service = service;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ProblemDetail invalid(IllegalArgumentException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ProblemDetail conflict(IllegalStateException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
  }

  @GetMapping
  public List<Run> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public Run get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Run create(@RequestBody BlobMigrationService.CreateRequest request) {
    return service.create(request);
  }

  @PostMapping("/{id}/resume")
  public Run resume(@PathVariable String id) {
    return service.resume(id);
  }

  @PostMapping("/{id}/pause")
  public Run pause(@PathVariable String id) {
    return service.pause(id);
  }

  @GetMapping("/{id}/evidence")
  public List<Evidence> evidence(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "100") int limit) {
    return service.evidence(id, afterId, limit);
  }
}
