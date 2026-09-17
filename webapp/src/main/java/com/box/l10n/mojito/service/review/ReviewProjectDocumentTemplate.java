package com.box.l10n.mojito.service.review;

public record ReviewProjectDocumentTemplate(
    Long assetId,
    String assetPath,
    Long repositoryId,
    Long branchId,
    String branchName,
    String contentMd5,
    String sourceLocaleTag) {
  public ReviewProjectDocumentTemplate(
      Long assetId,
      String assetPath,
      Long repositoryId,
      Long branchId,
      String branchName,
      String contentMd5) {
    this(assetId, assetPath, repositoryId, branchId, branchName, contentMd5, null);
  }
}
