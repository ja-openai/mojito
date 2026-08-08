package com.box.l10n.mojito.rest.blobstorage;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobCleanupPolicyService;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/blob-cleanup-policies")
public class DatabaseBlobCleanupPolicyWS {

  private final DatabaseBlobCleanupPolicyService policyService;

  public DatabaseBlobCleanupPolicyWS(DatabaseBlobCleanupPolicyService policyService) {
    this.policyService = policyService;
  }

  @GetMapping
  public List<PolicyResponse> listPolicies() {
    return policyService.listPolicies().stream().map(this::toResponse).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PolicyResponse createPolicy(@RequestBody PolicyRequest request) {
    try {
      return toResponse(policyService.createPolicy(toUpdate(request)));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PutMapping("/{policyId}")
  public PolicyResponse updatePolicy(
      @PathVariable long policyId, @RequestBody PolicyRequest request) {
    try {
      return toResponse(policyService.updatePolicy(policyId, toUpdate(request)));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/{policyId}/start")
  public PolicyResponse startPolicy(@PathVariable long policyId) {
    try {
      return toResponse(policyService.startPolicy(policyId));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/{policyId}/stop")
  public PolicyResponse stopPolicy(@PathVariable long policyId) {
    try {
      return toResponse(policyService.stopPolicy(policyId));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @DeleteMapping("/{policyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePolicy(@PathVariable long policyId) {
    try {
      policyService.deletePolicy(policyId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  private DatabaseBlobCleanupPolicyService.PolicyUpdate toUpdate(PolicyRequest request) {
    return new DatabaseBlobCleanupPolicyService.PolicyUpdate(
        request.prefix(),
        request.enabled(),
        request.retentionDays(),
        request.batchSize(),
        request.maxBatchesPerRun(),
        request.pauseMillis(),
        request.maxRetries());
  }

  private PolicyResponse toResponse(DatabaseBlobCleanupPolicy policy) {
    return new PolicyResponse(
        policy.getId(),
        policy.getPrefix(),
        policy.isEnabled(),
        policy.getRetentionDays(),
        policy.getBatchSize(),
        policy.getMaxBatchesPerRun(),
        policy.getPauseMillis(),
        policy.getMaxRetries(),
        policy.getStatus(),
        policy.getLastStartedDate(),
        policy.getLastFinishedDate(),
        policy.getLastDeletedCount(),
        policy.getTotalDeletedCount(),
        policy.getLastError());
  }

  public record PolicyRequest(
      String prefix,
      boolean enabled,
      int retentionDays,
      int batchSize,
      int maxBatchesPerRun,
      int pauseMillis,
      int maxRetries) {}

  public record PolicyResponse(
      Long id,
      String prefix,
      boolean enabled,
      int retentionDays,
      int batchSize,
      int maxBatchesPerRun,
      int pauseMillis,
      int maxRetries,
      String status,
      ZonedDateTime lastStartedDate,
      ZonedDateTime lastFinishedDate,
      long lastDeletedCount,
      long totalDeletedCount,
      String lastError) {}
}
