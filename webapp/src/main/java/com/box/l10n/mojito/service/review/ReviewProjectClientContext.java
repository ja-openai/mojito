package com.box.l10n.mojito.service.review;

/** Optional, untrusted client claims. Never log this object or its strings directly. */
public record ReviewProjectClientContext(
    Integer schemaVersion,
    String pageSessionId,
    String operationId,
    Long requestSequence,
    String loadedBundleId,
    String operationOrigin,
    Owner owner,
    TargetOrigin targetOrigin,
    String recovery) {
  public record Owner(
      Long projectId, Long textUnitId, Long tmTextUnitId, String reviewStateRevision) {}

  public record TargetOrigin(
      String kind,
      Owner owner,
      Long suggestionId,
      Long proposalId,
      Long proposalRevision,
      String aiRequestId) {}
}
