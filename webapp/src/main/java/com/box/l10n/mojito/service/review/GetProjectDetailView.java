package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import java.time.ZonedDateTime;
import java.util.List;

public record GetProjectDetailView(
    Long id,
    ReviewProjectType type,
    ReviewProjectStatus status,
    ZonedDateTime createdDate,
    ZonedDateTime dueDate,
    String closeReason,
    Integer textUnitCount,
    Integer wordCount,
    ReviewProjectRequest reviewProjectRequest,
    Locale locale,
    Assignment assignment,
    List<ReviewProjectTextUnit> reviewProjectTextUnits) {

  public record ReviewProjectRequest(
      Long id,
      String name,
      String notes,
      ZonedDateTime createdDate,
      String createdByUsername,
      List<String> screenshotImageIds) {}

  public record Locale(Long id, String bcp47Tag) {}

  public record Assignment(
      Long teamId,
      String teamName,
      Long assignedPmUserId,
      String assignedPmUsername,
      Long assignedTranslatorUserId,
      String assignedTranslatorUsername) {}

  public record ReviewProjectTextUnit(
      Long id,
      TmTextUnit tmTextUnit,
      TmTextUnitVariant baselineTmTextUnitVariant,
      TmTextUnitVariant currentTmTextUnitVariant,
      ReviewProjectTextUnitDecision reviewProjectTextUnitDecision) {}

  public record TmTextUnit(
      Long id,
      String name,
      String content,
      String comment,
      ZonedDateTime createdDate,
      Asset asset,
      Long wordCount) {}

  public record Asset(String assetPath, Repository repository) {
    public record Repository(Long id, String name) {}
  }

  public record TmTextUnitVariant(
      Long id, String content, String status, Boolean includedInLocalizedFile, String comment) {}

  public record ReviewProjectTextUnitDecision(
      Long reviewedTmTextUnitVariantId,
      String notes,
      DecisionState decisionState,
      TmTextUnitVariant decisionTmTextUnitVariant) {}
}
