package com.box.l10n.mojito.service.security.user;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.entity.security.user.UserPreferencesEntity;
import com.box.l10n.mojito.rest.security.UserPreferences;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserPreferencesService {

  private static final int MAX_LIST_SIZE = 256;
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Set<String> FIELDS =
      Set.of(
          "worksetSize",
          "preferredLocales",
          "shortcutHelp",
          "visibleTextEditorEnabled",
          "reviewProjectSearchEnabled",
          "defaultReviewTeamIds",
          "aiReviewProfile",
          "aiReviewAutomaticDisabled",
          "aiReviewReasoningEffort",
          "aiReviewPreset");

  private final UserService userService;
  private final UserPreferencesRepository preferencesRepository;
  private final TeamRepository teamRepository;
  private final TeamService teamService;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;

  public UserPreferencesService(
      UserService userService,
      UserPreferencesRepository preferencesRepository,
      TeamRepository teamRepository,
      TeamService teamService,
      ObjectMapper objectMapper,
      EntityManager entityManager) {
    this.userService = userService;
    this.preferencesRepository = preferencesRepository;
    this.teamRepository = teamRepository;
    this.teamService = teamService;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  public UserPreferences getCurrentUserPreferences() {
    return preferencesRepository
        .findByUserId(currentUser().getId())
        .map(this::read)
        .orElseGet(UserPreferences::defaults);
  }

  @Transactional
  public UserPreferences patchCurrentUserPreferences(JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw invalid("Preferences must be a JSON object");
    }
    patch
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field)) {
                throw invalid("Unknown preference: " + field);
              }
            });

    User user = currentUser();
    // Lock the user before loading preferences, including the first save when no row exists.
    entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
    // Read the latest committed preferences even if MySQL established an earlier snapshot.
    UserPreferencesEntity entity =
        preferencesRepository.findForUpdateByUserId(user.getId()).orElse(null);
    ObjectNode merged =
        objectMapper.valueToTree(entity == null ? UserPreferences.defaults() : read(entity));
    patch.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
    merged.put("initialized", true);

    JsonNode worksetSize = merged.get("worksetSize");
    if (!worksetSize.isNull()
        && (!worksetSize.isIntegralNumber()
            || !worksetSize.canConvertToInt()
            || worksetSize.intValue() < 1)) {
      throw invalid("Workset size must be an integer between 1 and 2147483647, or null");
    }
    JsonNode shortcutHelp = merged.get("shortcutHelp");
    if (!shortcutHelp.isNull()
        && (!shortcutHelp.isTextual()
            || !Set.of("header", "bottom", "hidden").contains(shortcutHelp.textValue()))) {
      throw invalid("Shortcut help must be header, bottom, hidden, or null");
    }
    JsonNode aiReviewProfile = merged.get("aiReviewProfile");
    if (!aiReviewProfile.isTextual()
        || !Set.of("version_a", "version_b").contains(aiReviewProfile.textValue())) {
      throw invalid("AI review profile must be version_a or version_b");
    }
    JsonNode aiReviewReasoningEffort = merged.get("aiReviewReasoningEffort");
    if (!aiReviewReasoningEffort.isTextual()
        || !Set.of("low", "medium", "high").contains(aiReviewReasoningEffort.textValue())) {
      throw invalid("AI review reasoning effort must be low, medium, or high");
    }
    JsonNode aiReviewPreset = merged.get("aiReviewPreset");
    if (!aiReviewPreset.isTextual()
        || !Set.of("fastest", "fast", "balanced", "thorough", "deep", "ultra")
            .contains(aiReviewPreset.textValue())) {
      throw invalid("Unknown AI review preset");
    }
    for (String field :
        List.of(
            "visibleTextEditorEnabled",
            "reviewProjectSearchEnabled",
            "aiReviewAutomaticDisabled")) {
      if (!merged.get(field).isBoolean()) {
        throw invalid(field + " must be a boolean");
      }
    }
    merged.set(
        "preferredLocales", objectMapper.valueToTree(locales(merged.get("preferredLocales"))));
    if (patch.has("defaultReviewTeamIds")) {
      merged.set(
          "defaultReviewTeamIds",
          objectMapper.valueToTree(reviewTeams(patch.get("defaultReviewTeamIds"))));
    }
    try {
      UserPreferences preferences = objectMapper.treeToValue(merged, UserPreferences.class);
      if (entity == null) {
        entity = new UserPreferencesEntity();
        entity.setUser(user);
      }
      entity.setPreferencesJson(objectMapper.writeValueAsString(preferences));
      preferencesRepository.save(entity);
      return preferences;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to encode user preferences", e);
    }
  }

  private User currentUser() {
    return userService
        .getCurrentUser()
        .orElseThrow(() -> new AccessDeniedException("No authenticated user"));
  }

  private UserPreferences read(UserPreferencesEntity entity) {
    try {
      return objectMapper.readValue(entity.getPreferencesJson(), UserPreferences.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to read user preferences", e);
    }
  }

  private List<String> locales(JsonNode values) {
    requireList(values, "preferredLocales");
    Set<String> seen = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) {
        throw invalid("Preferred locales must be language tags");
      }
      String tag = value.textValue().trim();
      if (tag.isEmpty() || tag.length() > 128) {
        throw invalid("Preferred locales must be language tags of at most 128 characters");
      }
      try {
        new Locale.Builder().setLanguageTag(tag).build();
      } catch (IllformedLocaleException e) {
        throw invalid("Invalid preferred locale: " + tag);
      }
      if (seen.add(tag.toLowerCase(Locale.ROOT))) {
        result.add(tag);
      }
    }
    return result;
  }

  private List<Long> reviewTeams(JsonNode values) {
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException(
          "Only project managers and administrators can set default review teams");
    }
    requireList(values, "defaultReviewTeamIds");
    Set<Long> ids = new LinkedHashSet<>();
    for (JsonNode value : values) {
      if (!value.isIntegralNumber()
          || !value.canConvertToLong()
          || value.longValue() < 1
          || value.longValue() > MAX_SAFE_INTEGER) {
        throw invalid("Default review team IDs must be positive safe integers");
      }
      ids.add(value.longValue());
    }
    List<Team> teams = teamRepository.findAllById(ids);
    if (teams.size() != ids.size()
        || teams.stream().anyMatch(team -> !Boolean.TRUE.equals(team.getEnabled()))) {
      throw invalid("Default review teams must be existing enabled teams");
    }
    ids.forEach(teamService::assertCurrentUserCanAccessTeam);
    return List.copyOf(ids);
  }

  private void requireList(JsonNode value, String field) {
    if (!value.isArray() || value.size() > MAX_LIST_SIZE) {
      throw invalid(field + " must be an array of at most " + MAX_LIST_SIZE + " entries");
    }
  }

  private ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
