package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.TeamLocalePool;
import com.box.l10n.mojito.entity.TeamPmPool;
import com.box.l10n.mojito.entity.TeamSlackUserMapping;
import com.box.l10n.mojito.entity.TeamUser;
import com.box.l10n.mojito.entity.TeamUserRole;
import com.box.l10n.mojito.entity.security.user.Authority;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.InjectCurrentTask;
import com.box.l10n.mojito.service.pollableTask.Pollable;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableFutureTaskResult;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectAssignmentHistoryRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.security.user.UserAdminSummaryProjection;
import com.box.l10n.mojito.service.security.user.UserLocaleRepository;
import com.box.l10n.mojito.service.security.user.UserLocaleTagProjection;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Channel;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

  private static final Logger logger = LoggerFactory.getLogger(TeamService.class);
  private static final int SLACK_USER_LOOKUP_PARALLELISM = 8;
  private static final int SLACK_USER_LOOKUP_TIMEOUT_SECONDS = 12;

  private final TeamRepository teamRepository;
  private final TeamUserRepository teamUserRepository;
  private final TeamLocalePoolRepository teamLocalePoolRepository;
  private final TeamPmPoolRepository teamPmPoolRepository;
  private final TeamSlackUserMappingRepository teamSlackUserMappingRepository;
  private final UserRepository userRepository;
  private final UserLocaleRepository userLocaleRepository;
  private final LocaleService localeService;
  private final ReviewProjectRepository reviewProjectRepository;
  private final ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository;
  private final AuditorAwareImpl auditorAwareImpl;
  private final SlackClients slackClients;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;

  public TeamService(
      TeamRepository teamRepository,
      TeamUserRepository teamUserRepository,
      TeamLocalePoolRepository teamLocalePoolRepository,
      TeamPmPoolRepository teamPmPoolRepository,
      TeamSlackUserMappingRepository teamSlackUserMappingRepository,
      UserRepository userRepository,
      UserLocaleRepository userLocaleRepository,
      LocaleService localeService,
      ReviewProjectRepository reviewProjectRepository,
      ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository,
      AuditorAwareImpl auditorAwareImpl,
      SlackClients slackClients,
      PollableTaskBlobStorage pollableTaskBlobStorage) {
    this.teamRepository = teamRepository;
    this.teamUserRepository = teamUserRepository;
    this.teamLocalePoolRepository = teamLocalePoolRepository;
    this.teamPmPoolRepository = teamPmPoolRepository;
    this.teamSlackUserMappingRepository = teamSlackUserMappingRepository;
    this.userRepository = userRepository;
    this.userLocaleRepository = userLocaleRepository;
    this.localeService = localeService;
    this.reviewProjectRepository = reviewProjectRepository;
    this.reviewProjectAssignmentHistoryRepository = reviewProjectAssignmentHistoryRepository;
    this.auditorAwareImpl = auditorAwareImpl;
    this.slackClients = slackClients;
    this.pollableTaskBlobStorage = pollableTaskBlobStorage;
  }

  public record LocalePoolEntry(String localeTag, List<Long> translatorUserIds) {}

  public record ReplaceTeamUsersResult(int removedUsersCount, int removedLocalePoolRows) {}

  public record TeamSlackSettings(boolean enabled, String slackClientId, String slackChannelId) {}

  public record TeamSlackUserMappingEntry(
      Long mojitoUserId,
      String mojitoUsername,
      String slackUserId,
      String slackUsername,
      String matchSource,
      ZonedDateTime lastVerifiedAt) {}

  public record UpsertTeamSlackUserMappingEntry(
      Long mojitoUserId, String slackUserId, String slackUsername, String matchSource) {}

  public record SlackChannelImportPreviewRow(
      String slackUserId,
      String slackUsername,
      String slackRealName,
      String slackEmail,
      boolean slackBot,
      boolean slackDeleted,
      Long matchedMojitoUserId,
      String matchedMojitoUsername,
      String matchReason,
      boolean alreadyMapped,
      boolean alreadyPm,
      boolean alreadyTranslator) {}

  public record SlackChannelImportPreview(
      String slackChannelId, String slackChannelName, List<SlackChannelImportPreviewRow> rows) {}

  public record SlackChannelImportApplyResult(
      int scannedRows,
      int selectedRows,
      int matchedRows,
      int addedUsersCount,
      int mappingsUpsertedCount) {}

  public record TeamUserSummary(Long id, String username, String commonName) {}

  public record SlackConversationMember(
      String slackUserId,
      String slackUsername,
      String slackRealName,
      String slackEmail,
      boolean slackBot,
      boolean slackDeleted,
      boolean profileLoaded) {}

  public record SlackConversationMembers(
      String slackClientId,
      String slackChannelId,
      String slackChannelName,
      List<SlackConversationMember> entries) {}

  public record SlackConversationMembersRefreshInput(Long teamId, boolean includeProfiles) {}

  private static String normalizeTeamName(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private Long getCurrentUserIdOrThrow() {
    return auditorAwareImpl
        .getCurrentAuditor()
        .map(User::getId)
        .orElseThrow(() -> new AccessDeniedException("No authenticated user"));
  }

  public boolean isCurrentUserAdmin() {
    return auditorAwareImpl
        .getCurrentAuditor()
        .map(
            user ->
                user.getAuthorities().stream()
                    .map(Authority::getAuthority)
                    .anyMatch("ROLE_ADMIN"::equals))
        .orElse(false);
  }

  public void assertCurrentUserCanAccessTeam(Long teamId) {
    if (isCurrentUserAdmin()) {
      return;
    }
    Long currentUserId = getCurrentUserIdOrThrow();
    boolean canAccess = isUserInTeamRole(teamId, currentUserId, TeamUserRole.PM);
    if (!canAccess) {
      throw new AccessDeniedException("Team access denied");
    }
  }

  public void assertCurrentUserCanReadTeam(Long teamId) {
    if (isCurrentUserAdmin()) {
      return;
    }
    Long currentUserId = getCurrentUserIdOrThrow();
    boolean canRead =
        isUserInTeamRole(teamId, currentUserId, TeamUserRole.PM)
            || isUserInTeamRole(teamId, currentUserId, TeamUserRole.TRANSLATOR);
    if (!canRead) {
      throw new AccessDeniedException("Team access denied");
    }
  }

  public boolean isUserInTeamRole(Long teamId, Long userId, TeamUserRole role) {
    if (teamId == null || userId == null || role == null) {
      return false;
    }
    return teamUserRepository.existsByTeam_IdAndUser_IdAndRole(teamId, userId, role);
  }

  @Transactional(readOnly = true)
  public List<Team> findAll() {
    return teamRepository.findAllOrderedEnabled();
  }

  @Transactional(readOnly = true)
  public List<Team> findAll(boolean includeDisabled) {
    return includeDisabled
        ? teamRepository.findAllOrdered()
        : teamRepository.findAllOrderedEnabled();
  }

  @Transactional(readOnly = true)
  public List<Team> findCurrentUserTeams() {
    Long userId = getCurrentUserIdOrThrow();
    return teamUserRepository.findByUserIdAndRole(userId, TeamUserRole.PM).stream()
        .map(TeamUser::getTeam)
        .distinct()
        .collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public Team getTeam(Long teamId) {
    return teamRepository
        .findByIdAndEnabledTrue(teamId)
        .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
  }

  @Transactional
  public Team createTeam(String rawName) {
    String name = normalizeTeamName(rawName);
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Team name is required");
    }
    if (teamRepository.findByNameIgnoreCaseAndEnabledTrue(name).isPresent()) {
      throw new IllegalArgumentException("Team already exists: " + name);
    }

    Team team = new Team();
    team.setName(name);
    return teamRepository.save(team);
  }

  @Transactional
  public Team updateTeam(Long teamId, String rawName) {
    Team team = getTeam(teamId);
    String name = normalizeTeamName(rawName);
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Team name is required");
    }

    teamRepository
        .findByNameIgnoreCaseAndEnabledTrue(name)
        .filter(existing -> !existing.getId().equals(teamId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Team already exists: " + name);
            });

    team.setName(name);
    return teamRepository.save(team);
  }

  @Transactional
  public void deleteTeam(Long teamId) {
    Team team = getTeam(teamId);
    hardDeleteTeamIfUnused(team);
  }

  @Transactional
  public void updateTeamEnabled(Long teamId, boolean enabled) {
    if (enabled) {
      throw new IllegalArgumentException("Re-enabling teams is not supported");
    }

    Team team = getTeam(teamId);
    // Disable the team row for auditability/name reuse while preserving team roster/pool/mapping
    // rows so the team can be analyzed or restored later if needed.
    String name = "disabled__" + System.currentTimeMillis() + "__" + team.getName();
    team.setName(StringUtils.abbreviate(name, Team.NAME_MAX_LENGTH));
    team.setEnabled(false);
    teamRepository.save(team);
  }

  private void hardDeleteTeamIfUnused(Team team) {
    Long teamId = team.getId();
    if (reviewProjectRepository.existsByTeam_Id(teamId)
        || reviewProjectAssignmentHistoryRepository.existsByTeam_Id(teamId)) {
      throw new IllegalStateException(
          "Team has review-project usage and cannot be hard deleted: " + teamId);
    }

    teamLocalePoolRepository.deleteByTeamId(teamId);
    teamPmPoolRepository.deleteByTeamId(teamId);
    teamSlackUserMappingRepository.deleteByTeamId(teamId);
    teamUserRepository.deleteByTeamIdAndRole(teamId, TeamUserRole.PM);
    teamUserRepository.deleteByTeamIdAndRole(teamId, TeamUserRole.TRANSLATOR);
    teamRepository.delete(team);
  }

  @Transactional(readOnly = true)
  public List<Long> getTeamUserIdsByRole(Long teamId, TeamUserRole role) {
    getTeam(teamId);
    return teamUserRepository.findByTeamIdAndRole(teamId, role).stream()
        .map(TeamUser::getUser)
        .map(User::getId)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<TeamUserSummary> getTeamUsersByRole(Long teamId, TeamUserRole role) {
    getTeam(teamId);
    return teamUserRepository.findByTeamIdAndRole(teamId, role).stream()
        .map(TeamUser::getUser)
        .map(user -> new TeamUserSummary(user.getId(), user.getUsername(), user.getCommonName()))
        .toList();
  }

  @Transactional
  public ReplaceTeamUsersResult replaceTeamUsersByRole(
      Long teamId, TeamUserRole role, List<Long> userIds) {
    Team team = getTeam(teamId);

    List<Long> normalizedIds =
        (userIds == null ? List.<Long>of() : userIds)
            .stream().filter(id -> id != null && id > 0).distinct().toList();

    Set<Long> existingUserIds =
        teamUserRepository.findByTeamIdAndRole(teamId, role).stream()
            .map(TeamUser::getUser)
            .map(User::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<Long> nextUserIds = new LinkedHashSet<>(normalizedIds);
    List<Long> removedUserIds =
        existingUserIds.stream().filter(id -> !nextUserIds.contains(id)).toList();

    int removedLocalePoolRows = 0;
    int removedPmPoolRows = 0;
    if (role == TeamUserRole.TRANSLATOR && !removedUserIds.isEmpty()) {
      removedLocalePoolRows =
          teamLocalePoolRepository.deleteByTeamIdAndTranslatorUserIds(teamId, removedUserIds);
      if (removedLocalePoolRows > 0) {
        logger.warn(
            "Removed {} locale-pool rows for team {} after translators were removed from roster: {}",
            removedLocalePoolRows,
            teamId,
            removedUserIds);
      }
    }
    if (role == TeamUserRole.PM && !removedUserIds.isEmpty()) {
      removedPmPoolRows = teamPmPoolRepository.deleteByTeamIdAndPmUserIds(teamId, removedUserIds);
      if (removedPmPoolRows > 0) {
        logger.warn(
            "Removed {} PM-pool rows for team {} after PMs were removed from roster: {}",
            removedPmPoolRows,
            teamId,
            removedUserIds);
      }
    }

    Map<Long, User> usersById = new LinkedHashMap<>();
    if (!normalizedIds.isEmpty()) {
      List<User> users = userRepository.findAllById(normalizedIds);
      users.forEach(user -> usersById.put(user.getId(), user));
      List<Long> missingUserIds =
          normalizedIds.stream().filter(id -> !usersById.containsKey(id)).toList();
      if (!missingUserIds.isEmpty()) {
        throw new IllegalArgumentException("Unknown users: " + missingUserIds);
      }
    }

    teamUserRepository.deleteByTeamIdAndRole(teamId, role);

    List<TeamUser> mappings = new ArrayList<>();
    normalizedIds.forEach(
        userId -> {
          TeamUser teamUser = new TeamUser();
          teamUser.setTeam(team);
          teamUser.setUser(usersById.get(userId));
          teamUser.setRole(role);
          mappings.add(teamUser);
        });
    if (!mappings.isEmpty()) {
      teamUserRepository.saveAll(mappings);
    }

    return new ReplaceTeamUsersResult(removedUserIds.size(), removedLocalePoolRows);
  }

  @Transactional
  public void setUserTeamAssignments(Long userId, Long pmTeamId, Long translatorTeamId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

    Set<Long> currentPmTeamIds =
        teamUserRepository.findByUserIdAndRole(userId, TeamUserRole.PM).stream()
            .map(TeamUser::getTeam)
            .map(Team::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<Long> nextPmTeamIds = pmTeamId == null ? Set.of() : Set.of(pmTeamId);
    List<Long> removedPmTeamIds =
        currentPmTeamIds.stream().filter(id -> !nextPmTeamIds.contains(id)).toList();

    Set<Long> currentTranslatorTeamIds =
        teamUserRepository.findByUserIdAndRole(userId, TeamUserRole.TRANSLATOR).stream()
            .map(TeamUser::getTeam)
            .map(Team::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<Long> nextTranslatorTeamIds =
        translatorTeamId == null ? Set.of() : Set.of(translatorTeamId);
    List<Long> removedTranslatorTeamIds =
        currentTranslatorTeamIds.stream()
            .filter(id -> !nextTranslatorTeamIds.contains(id))
            .toList();

    int prunedLocalePoolRows = 0;
    for (Long removedTeamId : removedTranslatorTeamIds) {
      prunedLocalePoolRows +=
          teamLocalePoolRepository.deleteByTeamIdAndTranslatorUserIds(
              removedTeamId, List.of(userId));
    }
    if (prunedLocalePoolRows > 0) {
      logger.warn(
          "Removed {} locale-pool rows after user {} was removed from translator team assignments: {}",
          prunedLocalePoolRows,
          userId,
          removedTranslatorTeamIds);
    }

    int prunedPmPoolRows = 0;
    for (Long removedTeamId : removedPmTeamIds) {
      prunedPmPoolRows +=
          teamPmPoolRepository.deleteByTeamIdAndPmUserIds(removedTeamId, List.of(userId));
    }
    if (prunedPmPoolRows > 0) {
      logger.warn(
          "Removed {} PM-pool rows after user {} was removed from PM team assignments: {}",
          prunedPmPoolRows,
          userId,
          removedPmTeamIds);
    }

    teamUserRepository.deleteByUserIdAndRole(userId, TeamUserRole.PM);
    teamUserRepository.deleteByUserIdAndRole(userId, TeamUserRole.TRANSLATOR);

    if (pmTeamId != null) {
      Team pmTeam = getTeam(pmTeamId);
      TeamUser pmMapping = new TeamUser();
      pmMapping.setTeam(pmTeam);
      pmMapping.setUser(user);
      pmMapping.setRole(TeamUserRole.PM);
      teamUserRepository.save(pmMapping);
    }

    if (translatorTeamId != null) {
      Team translatorTeam = getTeam(translatorTeamId);
      TeamUser translatorMapping = new TeamUser();
      translatorMapping.setTeam(translatorTeam);
      translatorMapping.setUser(user);
      translatorMapping.setRole(TeamUserRole.TRANSLATOR);
      teamUserRepository.save(translatorMapping);
    }
  }

  @Transactional(readOnly = true)
  public List<LocalePoolEntry> getLocalePools(Long teamId) {
    getTeam(teamId);
    boolean isAdmin = isCurrentUserAdmin();
    Set<Long> visibleTranslatorIds =
        isAdmin
            ? Set.of()
            : teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.TRANSLATOR).stream()
                .map(TeamUser::getUser)
                .map(User::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

    Map<String, List<Long>> translatorIdsByLocaleTag = new LinkedHashMap<>();
    teamLocalePoolRepository
        .findByTeamId(teamId)
        .forEach(
            row -> {
              String localeTag = row.localeTag();
              Long translatorId = row.translatorUserId();
              if (!isAdmin && !visibleTranslatorIds.contains(translatorId)) {
                return;
              }
              List<Long> translatorIds =
                  translatorIdsByLocaleTag.computeIfAbsent(localeTag, key -> new ArrayList<>());
              if (!translatorIds.contains(translatorId)) {
                translatorIds.add(translatorId);
              }
            });

    return translatorIdsByLocaleTag.entrySet().stream()
        .map(entry -> new LocalePoolEntry(entry.getKey(), entry.getValue()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Long> getPmPool(Long teamId) {
    getTeam(teamId);
    return teamPmPoolRepository.findByTeamId(teamId).stream()
        .map(TeamPmPoolRowProjection::pmUserId)
        .toList();
  }

  @Transactional
  public void replacePmPool(Long teamId, List<Long> userIds) {
    Team team = getTeam(teamId);
    List<Long> normalizedIds =
        (userIds == null ? List.<Long>of() : userIds)
            .stream().filter(id -> id != null && id > 0).distinct().toList();

    Set<Long> allowedPmIds =
        Stream.concat(
                teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.PM).stream(),
                teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.TRANSLATOR).stream())
            .map(TeamUser::getUser)
            .map(User::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<Long> disallowedPmIds =
        normalizedIds.stream().filter(id -> !allowedPmIds.contains(id)).toList();
    if (!disallowedPmIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot assign PMs outside team roster: " + disallowedPmIds);
    }

    Map<Long, User> usersById = new LinkedHashMap<>();
    if (!normalizedIds.isEmpty()) {
      List<User> users = userRepository.findAllById(normalizedIds);
      users.forEach(user -> usersById.put(user.getId(), user));
      List<Long> missingUserIds =
          normalizedIds.stream().filter(id -> !usersById.containsKey(id)).toList();
      if (!missingUserIds.isEmpty()) {
        throw new IllegalArgumentException("Unknown PM users: " + missingUserIds);
      }
    }

    teamPmPoolRepository.deleteByTeamId(teamId);

    List<TeamPmPool> entries = new ArrayList<>();
    for (int index = 0; index < normalizedIds.size(); index++) {
      Long userId = normalizedIds.get(index);
      TeamPmPool entry = new TeamPmPool();
      entry.setTeam(team);
      entry.setPmUser(usersById.get(userId));
      entry.setPosition(index + 1);
      entries.add(entry);
    }

    if (!entries.isEmpty()) {
      teamPmPoolRepository.saveAll(entries);
    }
  }

  @Transactional
  public void replaceLocalePools(Long teamId, List<LocalePoolEntry> entries) {
    Team team = getTeam(teamId);
    List<LocalePoolEntry> normalizedEntries = entries == null ? List.of() : entries;

    Set<Long> translatorIds = new LinkedHashSet<>();
    normalizedEntries.forEach(
        entry -> {
          List<Long> ids = entry.translatorUserIds();
          if (ids != null) {
            ids.stream().filter(id -> id != null && id > 0).forEach(translatorIds::add);
          }
        });

    if (!translatorIds.isEmpty()) {
      Set<Long> teamScopedTranslatorIds =
          teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.TRANSLATOR).stream()
              .map(TeamUser::getUser)
              .map(User::getId)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      List<Long> disallowedTranslatorIds =
          translatorIds.stream().filter(id -> !teamScopedTranslatorIds.contains(id)).toList();
      if (!disallowedTranslatorIds.isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot assign translators outside team roster: " + disallowedTranslatorIds);
      }
    }

    Map<Long, User> translatorsById = new LinkedHashMap<>();
    if (!translatorIds.isEmpty()) {
      List<User> translators = userRepository.findAllById(translatorIds);
      translators.forEach(user -> translatorsById.put(user.getId(), user));
      List<Long> missing =
          translatorIds.stream().filter(id -> !translatorsById.containsKey(id)).toList();
      if (!missing.isEmpty()) {
        throw new IllegalArgumentException("Unknown translators: " + missing);
      }
    }

    Map<Long, Set<String>> translatorLocaleTagsByUserId = new LinkedHashMap<>();
    if (!translatorIds.isEmpty()) {
      for (UserLocaleTagProjection row :
          userLocaleRepository.findLocaleTagsForUsers(new ArrayList<>(translatorIds))) {
        if (row.getUserId() == null || row.getBcp47Tag() == null) {
          continue;
        }
        translatorLocaleTagsByUserId
            .computeIfAbsent(row.getUserId(), key -> new LinkedHashSet<>())
            .add(row.getBcp47Tag().toLowerCase());
      }
    }

    teamLocalePoolRepository.deleteByTeamId(teamId);

    List<TeamLocalePool> pools = new ArrayList<>();
    for (LocalePoolEntry entry : normalizedEntries) {
      String localeTag = entry.localeTag() == null ? "" : entry.localeTag().trim();
      if (localeTag.isEmpty()) {
        continue;
      }
      Locale locale = localeService.findByBcp47Tag(localeTag);
      if (locale == null) {
        throw new IllegalArgumentException("Unknown locale: " + localeTag);
      }

      Set<Long> localeTranslatorIds =
          (entry.translatorUserIds() == null ? List.<Long>of() : entry.translatorUserIds())
              .stream()
                  .filter(id -> id != null && id > 0)
                  .collect(Collectors.toCollection(LinkedHashSet::new));

      int position = 1;
      for (Long translatorId : localeTranslatorIds) {
        User translator = translatorsById.get(translatorId);
        if (translator == null) {
          throw new IllegalArgumentException("Unknown translator: " + translatorId);
        }
        if (!canTranslatorTranslateLocale(
            translator,
            locale,
            translatorLocaleTagsByUserId.getOrDefault(translatorId, Set.of()))) {
          throw new IllegalArgumentException(
              "Translator "
                  + translator.getUsername()
                  + " (#"
                  + translatorId
                  + ") cannot be assigned to locale "
                  + locale.getBcp47Tag());
        }

        TeamLocalePool pool = new TeamLocalePool();
        pool.setTeam(team);
        pool.setLocale(locale);
        pool.setTranslatorUser(translator);
        pool.setPosition(position);
        pools.add(pool);
        position += 1;
      }
    }

    if (!pools.isEmpty()) {
      teamLocalePoolRepository.saveAll(pools);
    }
  }

  @Transactional(readOnly = true)
  public TeamSlackSettings getTeamSlackSettings(Long teamId) {
    Team team = getTeam(teamId);
    return new TeamSlackSettings(
        Boolean.TRUE.equals(team.getSlackNotificationsEnabled()),
        team.getSlackClientId(),
        team.getSlackChannelId());
  }

  @Transactional
  public TeamSlackSettings updateTeamSlackSettings(
      Long teamId, Boolean enabled, String slackClientId, String slackChannelId) {
    Team team = getTeam(teamId);

    String normalizedSlackClientId = normalizeOptionalSlackValue(slackClientId, 255);
    String normalizedChannelId = normalizeOptionalSlackValue(slackChannelId, 64);
    boolean normalizedEnabled = Boolean.TRUE.equals(enabled);

    if (normalizedEnabled && normalizedSlackClientId == null) {
      throw new IllegalArgumentException(
          "Slack client ID is required when Slack notifications are enabled");
    }

    if (normalizedEnabled && normalizedChannelId == null) {
      throw new IllegalArgumentException(
          "Slack channel ID is required when Slack notifications are enabled");
    }

    team.setSlackNotificationsEnabled(normalizedEnabled);
    team.setSlackClientId(normalizedSlackClientId);
    team.setSlackChannelId(normalizedChannelId);
    Team saved = teamRepository.save(team);

    return new TeamSlackSettings(
        Boolean.TRUE.equals(saved.getSlackNotificationsEnabled()),
        saved.getSlackClientId(),
        saved.getSlackChannelId());
  }

  @Transactional(readOnly = true)
  public SlackConversationMembers getSlackConversationMembers(
      Long teamId, boolean includeProfiles) {
    Team team = getTeam(teamId);
    SlackClient slackClient = getSlackClientForTeam(team);
    String channelId = requireConfiguredSlackChannelId(team);
    String slackClientId = normalizeOptionalSlackValue(team.getSlackClientId(), 255);

    Channel channel = null;
    try {
      channel = slackClient.getConversationById(channelId);
    } catch (SlackClientException e) {
      logger.warn("Failed to load Slack channel info for {}: {}", channelId, e.getMessage());
    }

    List<String> memberIds;
    try {
      memberIds =
          slackClient.getConversationMemberIds(channelId).stream()
              .filter(memberId -> memberId != null && !memberId.isBlank())
              .distinct()
              .sorted()
              .toList();
    } catch (SlackClientException e) {
      throw new IllegalArgumentException(
          e.getMessage() != null ? e.getMessage() : "Failed to fetch Slack channel members");
    }

    Map<String, com.box.l10n.mojito.slack.request.User> usersByMemberId =
        includeProfiles ? loadSlackUsersByMemberId(slackClient, memberIds) : Map.of();

    List<SlackConversationMember> entries = new ArrayList<>(memberIds.size());
    for (String memberId : memberIds) {
      com.box.l10n.mojito.slack.request.User slackUser = usersByMemberId.get(memberId);
      entries.add(
          new SlackConversationMember(
              memberId,
              slackUser != null ? slackUser.getName() : null,
              slackUser != null ? slackUser.getReal_name() : null,
              slackUser != null ? slackUser.getEmail() : null,
              slackUser != null && slackUser.isBot(),
              slackUser != null && slackUser.isDeleted(),
              slackUser != null));
    }

    return new SlackConversationMembers(
        slackClientId, channelId, channel != null ? channel.getName() : null, entries);
  }

  @Pollable(async = true, message = "Load Slack channel members")
  public PollableFuture<Void> refreshSlackConversationMembersAsync(
      Long teamId, boolean includeProfiles, @InjectCurrentTask PollableTask currentTask) {
    if (currentTask == null || currentTask.getId() == null) {
      throw new IllegalStateException("Current pollable task is missing");
    }

    Long pollableTaskId = currentTask.getId();
    pollableTaskBlobStorage.saveInput(
        pollableTaskId, new SlackConversationMembersRefreshInput(teamId, includeProfiles));
    SlackConversationMembers members = getSlackConversationMembers(teamId, includeProfiles);
    pollableTaskBlobStorage.saveOutput(pollableTaskId, members);
    return new PollableFutureTaskResult<>();
  }

  @Transactional(readOnly = true)
  public List<TeamSlackUserMappingEntry> getTeamSlackUserMappings(Long teamId) {
    getTeam(teamId);
    return teamSlackUserMappingRepository.findByTeamId(teamId).stream()
        .map(
            mapping ->
                new TeamSlackUserMappingEntry(
                    mapping.getMojitoUser().getId(),
                    mapping.getMojitoUser().getUsername(),
                    mapping.getSlackUserId(),
                    mapping.getSlackUsername(),
                    mapping.getMatchSource(),
                    mapping.getLastVerifiedAt()))
        .toList();
  }

  @Transactional
  public void replaceTeamSlackUserMappings(
      Long teamId, List<UpsertTeamSlackUserMappingEntry> entries) {
    Team team = getTeam(teamId);

    List<UpsertTeamSlackUserMappingEntry> normalizedEntries =
        (entries == null ? List.<UpsertTeamSlackUserMappingEntry>of() : entries)
            .stream()
                .map(this::normalizeSlackUserMappingEntry)
                .filter(entry -> entry != null)
                .toList();

    Map<Long, UpsertTeamSlackUserMappingEntry> entriesByUserId = new LinkedHashMap<>();
    for (UpsertTeamSlackUserMappingEntry entry : normalizedEntries) {
      if (entriesByUserId.putIfAbsent(entry.mojitoUserId(), entry) != null) {
        throw new IllegalArgumentException(
            "Duplicate Slack mapping for Mojito user: " + entry.mojitoUserId());
      }
    }

    Set<Long> allowedUserIds = new LinkedHashSet<>();
    teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.PM).stream()
        .map(TeamUser::getUser)
        .map(User::getId)
        .forEach(allowedUserIds::add);
    teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.TRANSLATOR).stream()
        .map(TeamUser::getUser)
        .map(User::getId)
        .forEach(allowedUserIds::add);

    List<Long> disallowedUserIds =
        entriesByUserId.keySet().stream().filter(id -> !allowedUserIds.contains(id)).toList();
    if (!disallowedUserIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot store Slack mappings for users outside the team roster: " + disallowedUserIds);
    }

    Map<Long, User> usersById = new LinkedHashMap<>();
    if (!entriesByUserId.isEmpty()) {
      userRepository
          .findAllById(entriesByUserId.keySet())
          .forEach(user -> usersById.put(user.getId(), user));
      List<Long> missingUserIds =
          entriesByUserId.keySet().stream().filter(id -> !usersById.containsKey(id)).toList();
      if (!missingUserIds.isEmpty()) {
        throw new IllegalArgumentException("Unknown users: " + missingUserIds);
      }
    }

    teamSlackUserMappingRepository.deleteByTeamId(teamId);

    if (entriesByUserId.isEmpty()) {
      return;
    }

    ZonedDateTime now = ZonedDateTime.now();
    List<TeamSlackUserMapping> mappings = new ArrayList<>();
    for (UpsertTeamSlackUserMappingEntry entry : entriesByUserId.values()) {
      TeamSlackUserMapping mapping = new TeamSlackUserMapping();
      mapping.setTeam(team);
      mapping.setMojitoUser(usersById.get(entry.mojitoUserId()));
      mapping.setSlackUserId(entry.slackUserId());
      mapping.setSlackUsername(entry.slackUsername());
      mapping.setMatchSource(entry.matchSource());
      mapping.setLastVerifiedAt(now);
      mappings.add(mapping);
    }

    teamSlackUserMappingRepository.saveAll(mappings);
  }

  @Transactional
  public void upsertTeamSlackUserMappings(
      Long teamId, List<UpsertTeamSlackUserMappingEntry> entries) {
    List<UpsertTeamSlackUserMappingEntry> incoming =
        entries == null ? List.of() : entries.stream().filter(entry -> entry != null).toList();
    if (incoming.isEmpty()) {
      return;
    }

    Map<Long, UpsertTeamSlackUserMappingEntry> mergedByUserId = new LinkedHashMap<>();
    getTeamSlackUserMappings(teamId)
        .forEach(
            existing ->
                mergedByUserId.put(
                    existing.mojitoUserId(),
                    new UpsertTeamSlackUserMappingEntry(
                        existing.mojitoUserId(),
                        existing.slackUserId(),
                        existing.slackUsername(),
                        existing.matchSource())));
    incoming.forEach(entry -> mergedByUserId.put(entry.mojitoUserId(), entry));
    replaceTeamSlackUserMappings(teamId, new ArrayList<>(mergedByUserId.values()));
  }

  @Transactional
  public ReplaceTeamUsersResult addTeamUsersByRole(
      Long teamId, TeamUserRole role, List<Long> userIds) {
    List<Long> existingUserIds = getTeamUserIdsByRole(teamId, role);
    List<Long> mergedUserIds =
        java.util.stream.Stream.concat(
                existingUserIds.stream(), (userIds == null ? List.<Long>of() : userIds).stream())
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
    return replaceTeamUsersByRole(teamId, role, mergedUserIds);
  }

  @Transactional(readOnly = true)
  public SlackChannelImportPreview previewSlackChannelImport(Long teamId) {
    SlackConversationMembers conversationMembers = getSlackConversationMembers(teamId, true);
    String channelId = conversationMembers.slackChannelId();
    List<SlackConversationMember> members = conversationMembers.entries();

    Map<Long, TeamSlackUserMappingEntry> existingMappingsByMojitoUserId =
        getTeamSlackUserMappings(teamId).stream()
            .collect(
                Collectors.toMap(
                    TeamSlackUserMappingEntry::mojitoUserId,
                    m -> m,
                    (a, b) -> a,
                    LinkedHashMap::new));
    Map<String, TeamSlackUserMappingEntry> existingMappingsBySlackUserId =
        existingMappingsByMojitoUserId.values().stream()
            .filter(m -> m.slackUserId() != null && !m.slackUserId().isBlank())
            .collect(
                Collectors.toMap(
                    m -> m.slackUserId().toLowerCase(java.util.Locale.ROOT),
                    m -> m,
                    (a, b) -> a,
                    LinkedHashMap::new));

    Set<Long> pmIds = new LinkedHashSet<>(getTeamUserIdsByRole(teamId, TeamUserRole.PM));
    Set<Long> translatorIds =
        new LinkedHashSet<>(getTeamUserIdsByRole(teamId, TeamUserRole.TRANSLATOR));

    List<UserAdminSummaryProjection> users = userRepository.findAdminSummaries();
    Map<String, List<UserAdminSummaryProjection>> usersByUsernameLower = new HashMap<>();
    users.forEach(
        u ->
            usersByUsernameLower
                .computeIfAbsent(normalizeKey(u.username()), key -> new ArrayList<>())
                .add(u));

    List<SlackChannelImportPreviewRow> rows = new ArrayList<>();
    for (SlackConversationMember member : members) {
      if (!member.profileLoaded()) {
        rows.add(
            new SlackChannelImportPreviewRow(
                member.slackUserId(),
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                "slack_lookup_failed",
                false,
                false,
                false));
        continue;
      }

      MatchCandidate match =
          findBestMojitoUserMatch(member, existingMappingsBySlackUserId, usersByUsernameLower);

      Long matchedId = match.user() != null ? match.user().id() : null;
      boolean alreadyMapped =
          matchedId != null
              && existingMappingsByMojitoUserId.containsKey(matchedId)
              && member
                  .slackUserId()
                  .equalsIgnoreCase(existingMappingsByMojitoUserId.get(matchedId).slackUserId());

      rows.add(
          new SlackChannelImportPreviewRow(
              member.slackUserId(),
              member.slackUsername(),
              member.slackRealName(),
              member.slackEmail(),
              member.slackBot(),
              member.slackDeleted(),
              matchedId,
              match.user() != null ? match.user().username() : null,
              match.reason(),
              alreadyMapped,
              matchedId != null && pmIds.contains(matchedId),
              matchedId != null && translatorIds.contains(matchedId)));
    }

    rows.sort(
        Comparator.comparing(
                (SlackChannelImportPreviewRow row) -> row.slackBot() || row.slackDeleted())
            .thenComparing(
                row ->
                    row.slackUsername() == null
                        ? ""
                        : row.slackUsername().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(row -> row.slackUserId() == null ? "" : row.slackUserId()));

    return new SlackChannelImportPreview(channelId, conversationMembers.slackChannelName(), rows);
  }

  @Transactional
  public SlackChannelImportApplyResult applySlackChannelImport(
      Long teamId, TeamUserRole role, List<String> slackUserIds) {
    if (role == null) {
      throw new IllegalArgumentException("Role is required");
    }

    SlackChannelImportPreview preview = previewSlackChannelImport(teamId);
    Set<String> selectedSlackUserIds =
        (slackUserIds == null ? List.<String>of() : slackUserIds)
            .stream()
                .map(this::normalizeKey)
                .filter(key -> key != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));

    List<SlackChannelImportPreviewRow> selectedRows =
        preview.rows().stream()
            .filter(
                row ->
                    selectedSlackUserIds.isEmpty()
                        || selectedSlackUserIds.contains(normalizeKey(row.slackUserId())))
            .toList();

    List<SlackChannelImportPreviewRow> matchedRows =
        selectedRows.stream()
            .filter(row -> !row.slackBot() && !row.slackDeleted())
            .filter(row -> row.matchedMojitoUserId() != null)
            .toList();

    List<Long> userIdsToAdd =
        matchedRows.stream()
            .map(SlackChannelImportPreviewRow::matchedMojitoUserId)
            .distinct()
            .toList();
    addTeamUsersByRole(teamId, role, userIdsToAdd);

    List<UpsertTeamSlackUserMappingEntry> mappings =
        matchedRows.stream()
            .map(
                row ->
                    new UpsertTeamSlackUserMappingEntry(
                        row.matchedMojitoUserId(),
                        row.slackUserId(),
                        row.slackUsername(),
                        "slack_channel_import"))
            .toList();
    upsertTeamSlackUserMappings(teamId, mappings);

    return new SlackChannelImportApplyResult(
        preview.rows().size(),
        selectedRows.size(),
        matchedRows.size(),
        userIdsToAdd.size(),
        mappings.size());
  }

  private UpsertTeamSlackUserMappingEntry normalizeSlackUserMappingEntry(
      UpsertTeamSlackUserMappingEntry entry) {
    if (entry == null || entry.mojitoUserId() == null || entry.mojitoUserId() <= 0) {
      return null;
    }

    String slackUserId = normalizeOptionalSlackValue(entry.slackUserId(), 64);
    if (slackUserId == null) {
      return null;
    }

    return new UpsertTeamSlackUserMappingEntry(
        entry.mojitoUserId(),
        slackUserId,
        normalizeOptionalSlackValue(entry.slackUsername(), 255),
        normalizeOptionalSlackValue(entry.matchSource(), 32));
  }

  private String normalizeOptionalSlackValue(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException("Value exceeds max length " + maxLength);
    }
    return normalized;
  }

  private SlackClient getSlackClientForTeam(Team team) {
    String slackClientId = normalizeOptionalSlackValue(team.getSlackClientId(), 255);
    if (slackClientId == null) {
      throw new IllegalArgumentException("Team Slack client ID is not configured");
    }

    SlackClient slackClient = slackClients.getById(slackClientId);
    if (slackClient == null) {
      throw new IllegalArgumentException(
          "Unknown Slack client ID in team settings: " + slackClientId);
    }
    return slackClient;
  }

  private String requireConfiguredSlackChannelId(Team team) {
    String channelId = normalizeOptionalSlackValue(team.getSlackChannelId(), 64);
    if (channelId == null) {
      throw new IllegalArgumentException("Team Slack channel ID is not configured");
    }
    return channelId;
  }

  private Map<String, com.box.l10n.mojito.slack.request.User> loadSlackUsersByMemberId(
      SlackClient slackClient, List<String> memberIds) {
    List<String> ids =
        (memberIds == null ? List.<String>of() : memberIds)
            .stream()
                .filter(memberId -> memberId != null && !memberId.isBlank())
                .distinct()
                .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }

    int parallelism = Math.max(1, Math.min(SLACK_USER_LOOKUP_PARALLELISM, ids.size()));
    ExecutorService executor = Executors.newFixedThreadPool(parallelism);
    try {
      List<Callable<SlackLookupResult>> tasks =
          ids.stream()
              .map(
                  memberId ->
                      (Callable<SlackLookupResult>)
                          () -> {
                            try {
                              return new SlackLookupResult(
                                  memberId, slackClient.getUserById(memberId));
                            } catch (SlackClientException e) {
                              logger.debug(
                                  "Failed to fetch Slack user profile for member {}: {}",
                                  memberId,
                                  e.getMessage());
                              return new SlackLookupResult(memberId, null);
                            }
                          })
              .toList();

      List<Future<SlackLookupResult>> futures =
          executor.invokeAll(tasks, SLACK_USER_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      Map<String, com.box.l10n.mojito.slack.request.User> usersByMemberId = new LinkedHashMap<>();
      for (Future<SlackLookupResult> future : futures) {
        if (future.isCancelled()) {
          continue;
        }
        try {
          SlackLookupResult result = future.get();
          if (result != null && result.user() != null) {
            usersByMemberId.put(result.memberId(), result.user());
          }
        } catch (Exception e) {
          logger.debug("Failed to read Slack user lookup result: {}", e.getMessage());
        }
      }
      return usersByMemberId;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Map.of();
    } finally {
      executor.shutdownNow();
    }
  }

  private record MatchCandidate(UserAdminSummaryProjection user, String reason) {}

  private record SlackLookupResult(String memberId, com.box.l10n.mojito.slack.request.User user) {}

  private MatchCandidate findBestMojitoUserMatch(
      SlackConversationMember slackUser,
      Map<String, TeamSlackUserMappingEntry> existingMappingsBySlackUserId,
      Map<String, List<UserAdminSummaryProjection>> usersByUsernameLower) {
    if (slackUser == null || slackUser.slackUserId() == null) {
      return new MatchCandidate(null, null);
    }

    TeamSlackUserMappingEntry existingMapping =
        existingMappingsBySlackUserId.get(normalizeKey(slackUser.slackUserId()));
    if (existingMapping != null) {
      List<UserAdminSummaryProjection> mappedUsers =
          usersByUsernameLower.getOrDefault(
              normalizeKey(existingMapping.mojitoUsername()), List.of());
      if (!mappedUsers.isEmpty()) {
        return new MatchCandidate(mappedUsers.get(0), "existing_team_mapping");
      }
    }

    String slackUsername = normalizeKey(slackUser.slackUsername());
    List<UserAdminSummaryProjection> usernameMatches =
        slackUsername == null
            ? List.of()
            : usersByUsernameLower.getOrDefault(slackUsername, List.of());
    if (usernameMatches.size() == 1) {
      return new MatchCandidate(usernameMatches.get(0), "username_exact");
    }
    if (usernameMatches.size() > 1) {
      return new MatchCandidate(null, "username_ambiguous");
    }

    String email = slackUser.slackEmail();
    String emailLocalPart =
        email != null && email.contains("@")
            ? normalizeKey(email.substring(0, email.indexOf('@')))
            : null;
    List<UserAdminSummaryProjection> emailMatches =
        emailLocalPart == null
            ? List.of()
            : usersByUsernameLower.getOrDefault(emailLocalPart, List.of());
    if (emailMatches.size() == 1) {
      return new MatchCandidate(emailMatches.get(0), "email_localpart_username");
    }
    if (emailMatches.size() > 1) {
      return new MatchCandidate(null, "email_localpart_ambiguous");
    }

    return new MatchCandidate(
        null, slackUser.slackBot() ? "bot" : (slackUser.slackDeleted() ? "deleted" : "unmatched"));
  }

  private String normalizeKey(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    return normalized.toLowerCase(java.util.Locale.ROOT);
  }

  private boolean canTranslatorTranslateLocale(
      User translator, Locale locale, Set<String> allowedLocaleTagsLowercase) {
    if (translator.getCanTranslateAllLocales()) {
      return true;
    }

    if (allowedLocaleTagsLowercase == null || allowedLocaleTagsLowercase.isEmpty()) {
      return false;
    }

    String localeTag = locale.getBcp47Tag();
    return localeTag != null && allowedLocaleTagsLowercase.contains(localeTag.toLowerCase());
  }
}
