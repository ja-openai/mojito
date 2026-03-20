package com.box.l10n.mojito.service.review;

public record ReviewAutomationFeatureAssignmentRow(
    Long reviewAutomationId,
    String reviewAutomationName,
    Long reviewFeatureId,
    String reviewFeatureName) {}
