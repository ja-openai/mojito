package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import java.util.List;
import java.util.Map;

/** Source layout only; the ordinary project rows continue to own translation and review state. */
public record ReviewProjectDocumentView(List<Document> documents, List<String> warnings) {
  public record Document(
      Long assetId,
      String assetPath,
      Long repositoryId,
      String branchName,
      String sourceContentMd5,
      List<Block> blocks,
      List<String> warnings,
      Long branchId,
      String sourceLocaleTag) {
    public Document(
        Long assetId,
        String assetPath,
        Long repositoryId,
        String branchName,
        String sourceContentMd5,
        List<Block> blocks,
        List<String> warnings,
        Long branchId) {
      this(
          assetId,
          assetPath,
          repositoryId,
          branchName,
          sourceContentMd5,
          blocks,
          warnings,
          branchId,
          null);
    }
  }

  public record Block(
      String id,
      String type,
      int depth,
      String source,
      int line,
      boolean translatable,
      String marker,
      Long reviewProjectTextUnitId,
      Long tmTextUnitId,
      MappingStatus mappingStatus,
      Long assetId,
      String assetPath,
      int moduleDepth,
      String occurrenceId,
      ModuleStatus moduleStatus,
      String modulePath,
      String moduleWarning,
      String targetContent,
      Long tmTextUnitVariantId,
      TMTextUnitVariant.Status targetStatus,
      Map<String, Object> previewArgs) {
    public Block(
        String id,
        String type,
        int depth,
        String source,
        int line,
        boolean translatable,
        String marker,
        Long reviewProjectTextUnitId,
        Long tmTextUnitId,
        MappingStatus mappingStatus,
        Long assetId,
        String assetPath,
        int moduleDepth,
        String occurrenceId,
        ModuleStatus moduleStatus,
        String modulePath,
        String moduleWarning,
        String targetContent,
        Long tmTextUnitVariantId,
        TMTextUnitVariant.Status targetStatus) {
      this(
          id,
          type,
          depth,
          source,
          line,
          translatable,
          marker,
          reviewProjectTextUnitId,
          tmTextUnitId,
          mappingStatus,
          assetId,
          assetPath,
          moduleDepth,
          occurrenceId,
          moduleStatus,
          modulePath,
          moduleWarning,
          targetContent,
          tmTextUnitVariantId,
          targetStatus,
          null);
    }
  }

  public enum ModuleStatus {
    EXPANDED,
    UNAVAILABLE
  }

  public enum MappingStatus {
    MATCHED,
    NOT_IN_PROJECT,
    NOT_FOUND,
    SOURCE_CHANGED,
    CONTEXT
  }
}
