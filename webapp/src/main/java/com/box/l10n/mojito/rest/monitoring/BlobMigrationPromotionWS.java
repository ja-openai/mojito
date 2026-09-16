package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationPromotionService;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationPromotionStore.Item;
import com.box.l10n.mojito.service.blobstorage.migration.BlobMigrationPromotionStore.Run;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/** Administrators only; operations are explicit maintenance batches, with no recurring job. */
@RestController
@RequestMapping("/api/monitoring/blob-promotions")
public class BlobMigrationPromotionWS {
  private final BlobMigrationPromotionService service;

  public BlobMigrationPromotionWS(BlobMigrationPromotionService service) {
    this.service = service;
  }

  @GetMapping
  public List<Run> list() {
    return service.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Run create(@RequestBody BlobMigrationPromotionService.CreateRequest request) {
    return service.create(request);
  }

  @GetMapping("/{id}")
  public Run get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping("/{id}/batch")
  public Run batch(@PathVariable String id) {
    return service.runBatch(id);
  }

  @PostMapping("/{id}/pause")
  public Run pause(@PathVariable String id) {
    return service.pause(id);
  }

  @GetMapping("/{id}/readiness")
  public BlobMigrationPromotionService.Readiness readiness(@PathVariable String id) {
    return service.readiness(id);
  }

  @GetMapping("/{id}/evidence")
  public List<Item> evidence(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "100") int limit) {
    return service.items(id, afterId, limit);
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
}
