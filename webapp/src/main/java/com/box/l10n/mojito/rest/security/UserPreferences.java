package com.box.l10n.mojito.rest.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserPreferences(
    boolean initialized,
    Integer worksetSize,
    List<String> preferredLocales,
    String shortcutHelp,
    boolean visibleTextEditorEnabled,
    boolean reviewProjectSearchEnabled,
    List<Long> defaultReviewTeamIds,
    String aiReviewProfile,
    boolean aiReviewAutomaticDisabled,
    String aiReviewReasoningEffort,
    String aiReviewPreset,
    String aiReviewStyle,
    Boolean aiReviewShowScore) {

  public UserPreferences {
    aiReviewStyle = aiReviewStyle == null ? "corrections_and_alternatives" : aiReviewStyle;
    aiReviewShowScore = aiReviewShowScore == null ? true : aiReviewShowScore;
    aiReviewProfile = aiReviewProfile == null ? "version_b" : aiReviewProfile;
    aiReviewReasoningEffort = aiReviewReasoningEffort == null ? "low" : aiReviewReasoningEffort;
    if (aiReviewPreset == null) {
      aiReviewPreset =
          "version_a".equals(aiReviewProfile)
              ? "fast"
              : switch (aiReviewReasoningEffort) {
                case "medium" -> "thorough";
                case "high" -> "deep";
                default -> "balanced";
              };
    }
  }

  public UserPreferences(
      boolean initialized,
      Integer worksetSize,
      List<String> preferredLocales,
      String shortcutHelp,
      boolean visibleTextEditorEnabled,
      boolean reviewProjectSearchEnabled,
      List<Long> defaultReviewTeamIds,
      String aiReviewProfile,
      boolean aiReviewAutomaticDisabled,
      String aiReviewReasoningEffort,
      String aiReviewPreset) {
    this(
        initialized,
        worksetSize,
        preferredLocales,
        shortcutHelp,
        visibleTextEditorEnabled,
        reviewProjectSearchEnabled,
        defaultReviewTeamIds,
        aiReviewProfile,
        aiReviewAutomaticDisabled,
        aiReviewReasoningEffort,
        aiReviewPreset,
        null,
        null);
  }

  public UserPreferences(
      boolean initialized,
      Integer worksetSize,
      List<String> preferredLocales,
      String shortcutHelp,
      boolean visibleTextEditorEnabled,
      boolean reviewProjectSearchEnabled,
      List<Long> defaultReviewTeamIds,
      String aiReviewProfile,
      boolean aiReviewAutomaticDisabled,
      String aiReviewReasoningEffort) {
    this(
        initialized,
        worksetSize,
        preferredLocales,
        shortcutHelp,
        visibleTextEditorEnabled,
        reviewProjectSearchEnabled,
        defaultReviewTeamIds,
        aiReviewProfile,
        aiReviewAutomaticDisabled,
        aiReviewReasoningEffort,
        null);
  }

  public UserPreferences(
      boolean initialized,
      Integer worksetSize,
      List<String> preferredLocales,
      String shortcutHelp,
      boolean visibleTextEditorEnabled,
      boolean reviewProjectSearchEnabled,
      List<Long> defaultReviewTeamIds,
      String aiReviewProfile,
      boolean aiReviewAutomaticDisabled) {
    this(
        initialized,
        worksetSize,
        preferredLocales,
        shortcutHelp,
        visibleTextEditorEnabled,
        reviewProjectSearchEnabled,
        defaultReviewTeamIds,
        aiReviewProfile,
        aiReviewAutomaticDisabled,
        "low");
  }

  public static UserPreferences defaults() {
    return new UserPreferences(
        false, null, List.of(), null, false, false, List.of(), "version_b", false, "low");
  }
}
