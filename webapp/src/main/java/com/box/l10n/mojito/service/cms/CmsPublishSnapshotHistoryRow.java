package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;

public record CmsPublishSnapshotHistoryRow(
    Long id,
    Long projectId,
    String projectKey,
    Integer snapshotVersion,
    CmsPublishSnapshot.Status status,
    String localeTags,
    String publishRequestLocaleTags,
    String publishRequestAuthoringSha256,
    String publishRequestPackageSha256,
    String artifactSha256,
    Long artifactByteSize,
    String snapshotSigningKeyId,
    String snapshotSignature,
    String artifactSignature,
    String createdByUsername,
    String publishedAt) {}
