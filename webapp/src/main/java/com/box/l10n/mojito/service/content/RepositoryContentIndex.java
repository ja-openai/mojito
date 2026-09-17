package com.box.l10n.mojito.service.content;

import java.util.List;

public record RepositoryContentIndex(
    Long repositoryId,
    String sourceLocaleTag,
    Long branchId,
    String branchName,
    List<Branch> branches,
    List<Asset> assets,
    int offset,
    boolean hasMore,
    List<String> warnings,
    String nextCursor,
    String previousCursor) {
  public RepositoryContentIndex(
      Long repositoryId,
      String sourceLocaleTag,
      Long branchId,
      String branchName,
      List<Branch> branches,
      List<Asset> assets,
      int offset,
      boolean hasMore,
      List<String> warnings) {
    this(
        repositoryId,
        sourceLocaleTag,
        branchId,
        branchName,
        branches,
        assets,
        offset,
        hasMore,
        warnings,
        null,
        null);
  }

  public record Branch(Long id, String name) {}

  public record Asset(
      Long assetId, String assetPath, Long branchId, String branchName, String sourceContentMd5) {}
}
