package com.box.l10n.mojito.rest.blobstorage;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupSettings;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobCleanupService;
import java.time.ZonedDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/blob-cleanup")
public class DatabaseBlobCleanupWS {

  private final DatabaseBlobCleanupService cleanupService;

  public DatabaseBlobCleanupWS(DatabaseBlobCleanupService cleanupService) {
    this.cleanupService = cleanupService;
  }

  @GetMapping
  public CleanupResponse getSettings() {
    return toResponse(cleanupService.getSettings());
  }

  @PutMapping
  public CleanupResponse updateSettings(@RequestBody CleanupRequest request) {
    try {
      return toResponse(
          cleanupService.updateSettings(
              new DatabaseBlobCleanupService.SettingsUpdate(
                  request.enabled(),
                  request.batchSize(),
                  request.maxBatchesPerRun(),
                  request.pauseMillis(),
                  request.maxRetries())));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/start")
  public CleanupResponse start() {
    return toResponse(cleanupService.start());
  }

  @PostMapping("/stop")
  public CleanupResponse stop() {
    return toResponse(cleanupService.stop());
  }

  private CleanupResponse toResponse(DatabaseBlobCleanupSettings settings) {
    return new CleanupResponse(
        settings.isEnabled(),
        settings.getBatchSize(),
        settings.getMaxBatchesPerRun(),
        settings.getPauseMillis(),
        settings.getMaxRetries(),
        settings.getStatus(),
        settings.getLastStartedDate(),
        settings.getLastProgressDate(),
        settings.getLastFinishedDate(),
        settings.getLastDeletedCount(),
        settings.getTotalDeletedCount(),
        settings.getLastError());
  }

  public record CleanupRequest(
      boolean enabled, int batchSize, int maxBatchesPerRun, int pauseMillis, int maxRetries) {}

  public record CleanupResponse(
      boolean enabled,
      int batchSize,
      int maxBatchesPerRun,
      int pauseMillis,
      int maxRetries,
      String status,
      ZonedDateTime lastStartedDate,
      ZonedDateTime lastProgressDate,
      ZonedDateTime lastFinishedDate,
      long lastDeletedCount,
      long totalDeletedCount,
      String lastError) {}
}
