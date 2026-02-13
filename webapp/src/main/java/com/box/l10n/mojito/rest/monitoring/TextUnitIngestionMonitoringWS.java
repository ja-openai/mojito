package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.monitoring.TextUnitIngestionMonitoringService;
import com.box.l10n.mojito.service.monitoring.TextUnitIngestionMonitoringService.IngestionGroupBy;
import com.box.l10n.mojito.service.monitoring.TextUnitIngestionMonitoringService.IngestionRecomputeResult;
import com.box.l10n.mojito.service.monitoring.TextUnitIngestionMonitoringService.IngestionSnapshot;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/monitoring/text-unit-ingestion")
public class TextUnitIngestionMonitoringWS {

  private final TextUnitIngestionMonitoringService textUnitIngestionMonitoringService;

  public TextUnitIngestionMonitoringWS(
      TextUnitIngestionMonitoringService textUnitIngestionMonitoringService) {
    this.textUnitIngestionMonitoringService = textUnitIngestionMonitoringService;
  }

  @PostMapping("/recompute")
  public IngestionRecomputeResult recomputeMissingDays() {
    return textUnitIngestionMonitoringService.recomputeMissingDays();
  }

  @GetMapping
  public IngestionSnapshot getSnapshot(
      @RequestParam(name = "groupBy", defaultValue = "day") String groupBy,
      @RequestParam(name = "groupByRepository", defaultValue = "false") boolean groupByRepository,
      @RequestParam(name = "fromDay", required = false) LocalDate fromDay,
      @RequestParam(name = "toDay", required = false) LocalDate toDay) {
    try {
      IngestionGroupBy ingestionGroupBy = IngestionGroupBy.fromParam(groupBy);
      return textUnitIngestionMonitoringService.getSnapshot(
          ingestionGroupBy, groupByRepository, fromDay, toDay);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }
}
