package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectTerminologyPhase;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import java.time.ZonedDateTime;
import java.util.List;

public record CreateReviewProjectRequestCommand(
    List<String> localeTags,
    String notes,
    List<Long> tmTextUnitIds,
    Long reviewFeatureId,
    List<Long> repositoryIds,
    StatusFilter statusFilter,
    Boolean skipTextUnitsInOpenProjects,
    ReviewProjectType type,
    ZonedDateTime dueDate,
    List<String> screenshotImageIds,
    String name,
    Long teamId,
    Boolean assignTranslator,
    Long requestedByUserId,
    List<ProjectSpec> projectSpecs) {

  public CreateReviewProjectRequestCommand(
      List<String> localeTags,
      String notes,
      List<Long> tmTextUnitIds,
      Long reviewFeatureId,
      StatusFilter statusFilter,
      Boolean skipTextUnitsInOpenProjects,
      ReviewProjectType type,
      ZonedDateTime dueDate,
      List<String> screenshotImageIds,
      String name,
      Long teamId,
      Boolean assignTranslator,
      Long requestedByUserId,
      List<ProjectSpec> projectSpecs) {
    this(
        localeTags,
        notes,
        tmTextUnitIds,
        reviewFeatureId,
        null,
        statusFilter,
        skipTextUnitsInOpenProjects,
        type,
        dueDate,
        screenshotImageIds,
        name,
        teamId,
        assignTranslator,
        requestedByUserId,
        projectSpecs);
  }

  public record ProjectSpec(
      ReviewProjectTerminologyPhase terminologyPhase,
      ZonedDateTime dueDate,
      Long assignedPmUserId,
      Long assignedTranslatorUserId) {}
}
