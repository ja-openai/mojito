package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.tm.importer.BulkImportLineageService;
import com.box.l10n.mojito.service.tm.importer.BulkImportLineageService.RunSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/monitoring/import-lineage")
public class BulkImportLineageWS {

  private final BulkImportLineageService bulkImportLineageService;

  public BulkImportLineageWS(BulkImportLineageService bulkImportLineageService) {
    this.bulkImportLineageService = bulkImportLineageService;
  }

  @GetMapping
  public List<RunSummary> getRecentRuns(@RequestParam(defaultValue = "50") int limit) {
    return bulkImportLineageService.findRecentRuns(limit);
  }

  @GetMapping("/{runId}")
  public RunSummary getRun(@PathVariable String runId) {
    return bulkImportLineageService
        .findRun(runId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bulk import run not found"));
  }

  @GetMapping("/{runId}/input")
  public ResponseEntity<String> getInputPayload(@PathVariable String runId) {
    return jsonPayload(
        bulkImportLineageService
            .findInputPayload(runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Bulk import input payload not found")));
  }

  @GetMapping("/{runId}/output")
  public ResponseEntity<String> getOutputPayload(@PathVariable String runId) {
    return jsonPayload(
        bulkImportLineageService
            .findOutputPayload(runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Bulk import output payload not found")));
  }

  private ResponseEntity<String> jsonPayload(String payload) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
  }
}
