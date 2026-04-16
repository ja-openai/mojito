package com.box.l10n.mojito.rest.badtranslation;

import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/translation-incidents")
public class TranslationIncidentWS {

  private final TranslationIncidentService translationIncidentService;

  public TranslationIncidentWS(TranslationIncidentService translationIncidentService) {
    this.translationIncidentService = translationIncidentService;
  }

  public record CreateIncidentRequest(
      String stringId,
      String observedLocale,
      String repository,
      String reason,
      String sourceReference) {}

  public record RejectIncidentRequest(String comment) {}

  public record UpdateStatusRequest(TranslationIncidentStatus status) {}

  @GetMapping
  public TranslationIncidentService.IncidentPage getIncidents(
      @RequestParam(name = "status", required = false) TranslationIncidentStatus status,
      @RequestParam(name = "query", required = false) String query,
      @RequestParam(name = "createdAfter", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate createdAfter,
      @RequestParam(name = "createdBefore", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate createdBefore,
      @RequestParam(name = "page", required = false, defaultValue = "0") int page,
      @RequestParam(name = "size", required = false, defaultValue = "25") int size) {
    return translationIncidentService.getIncidents(
        status, query, createdAfter, createdBefore, page, size);
  }

  @GetMapping("/{incidentId}")
  public TranslationIncidentService.IncidentDetail getIncident(@PathVariable Long incidentId) {
    return translationIncidentService.getIncident(incidentId);
  }

  @PostMapping
  public TranslationIncidentService.IncidentDetail createIncident(
      @RequestBody CreateIncidentRequest request) {
    return translationIncidentService.createIncident(
        new TranslationIncidentService.CreateIncidentRequest(
            request == null ? null : request.stringId(),
            request == null ? null : request.observedLocale(),
            request == null ? null : request.repository(),
            request == null ? null : request.reason(),
            request == null ? null : request.sourceReference()));
  }

  @PostMapping("/{incidentId}/reject")
  public TranslationIncidentService.IncidentDetail rejectIncident(
      @PathVariable Long incidentId, @RequestBody(required = false) RejectIncidentRequest request) {
    return translationIncidentService.rejectIncident(
        incidentId,
        new TranslationIncidentService.RejectIncidentRequest(
            request == null ? null : request.comment()));
  }

  @PostMapping("/{incidentId}/send-slack")
  public TranslationIncidentService.IncidentDetail sendSlackDraft(@PathVariable Long incidentId) {
    return translationIncidentService.sendSlackDraft(incidentId);
  }

  @PostMapping("/{incidentId}/status")
  public TranslationIncidentService.IncidentDetail updateStatus(
      @PathVariable Long incidentId, @RequestBody UpdateStatusRequest request) {
    return translationIncidentService.updateStatus(
        incidentId,
        new TranslationIncidentService.UpdateStatusRequest(
            request == null ? null : request.status()));
  }
}
