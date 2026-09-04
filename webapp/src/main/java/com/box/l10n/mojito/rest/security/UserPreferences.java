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
    List<Long> defaultReviewTeamIds) {

  public static UserPreferences defaults() {
    return new UserPreferences(false, null, List.of(), null, false, false, List.of());
  }
}
