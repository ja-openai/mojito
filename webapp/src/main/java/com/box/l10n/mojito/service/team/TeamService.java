package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.Locale;
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
import com.box.l10n.mojito.service.security.user.UserLocaleRepository;
import com.box.l10n.mojito.service.security.user.UserLocaleTagProjection;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZonedDateTime;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

  private static final Logger logger = LoggerFactory.getLogger(TeamService.class);

  private final TeamRepository teamRepository;
  private final TeamUserRepository teamUserRepository;
  private final TeamLocalePoolRepository teamLocalePoolRepository;
  private final TeamPmPoolRepository teamPmPoolRepository;
  private final TeamSlackUserMappingRepository teamSlackUserMappingRepository;
  private final UserRepository userRepository;
  private final UserLocaleRepository userLocaleRepository;
  private final LocaleService localeService;
  private final AuditorAwareImpl auditorAwareImpl;

  public TeamService(
      TeamRepository teamRepository,
      TeamUserRepository teamUserRepository,
      TeamLocalePoolRepository teamLocalePoolRepository,
      TeamPmPoolRepository teamPmPoolRepository,
      TeamSlackUserMappingRepository teamSlackUserMappingRepository,
      UserRepository userRepository,
      UserLocaleRepository userLocaleRepository,
      LocaleService localeService,
      AuditorAwareImpl auditorAwareImpl) {
    this.teamRepository = teamRepository;
    this.teamUserRepository = teamUserRepository;
    this.teamLocalePoolRepository = teamLocalePoolRepository;
    this.teamPmPoolRepository = teamPmPoolRepository;
    this.teamSlackUserMappingRepository = teamSlackUserMappingRepository;
    this.userRepository = userRepository;
    this.userLocaleRepository = userLocaleRepository;
    this.localeService = localeService;
    this.auditorAwareImpl = auditorAwareImpl;
  }

  public record LocalePoolEntry(String localeTag, List<Long> translatorUserIds) {}

  public record ReplaceTeamUsersResult(int removedUsersCount, int removedLocalePoolRows) {}

  public record TeamSlackSettings(
      boolean enabled, String slackClientId, String slackChannelId) {}

  public record TeamSlackUserMappingEntry(
      Long mojitoUserId,
      String mojitoUsername,
      String slackUserId,
      String slackUsername,
      String matchSource,
      ZonedDateTime lastVerifiedAt) {}

  public record UpsertTeamSlackUserMappingEntry(
      Long mojitoUserId, String slackUserId, String slackUsername, String matchSource) {}

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
    boolean canAccess =
        teamUserRepository.existsByTeam_IdAndUser_IdAndRole(teamId, currentUserId, TeamUserRole.PM);
    if (!canAccess) {
      throw new AccessDeniedException("Team access denied");
    }
  }

  @Transactional(readOnly = true)
  public List<Team> findAll() {
    return teamRepository.findAllOrderedNotDeleted();
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
        .findByIdAndDeletedFalse(teamId)
        .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
  }

  @Transactional
  public Team createTeam(String rawName) {
    String name = normalizeTeamName(rawName);
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Team name is required");
    }
    if (teamRepository.findByNameIgnoreCaseAndDeletedFalse(name).isPresent()) {
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
        .findByNameIgnoreCaseAndDeletedFalse(name)
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
    teamLocalePoolRepository.deleteByTeamId(teamId);
    teamPmPoolRepository.deleteByTeamId(teamId);
    teamSlackUserMappingRepository.deleteByTeamId(teamId);
    teamUserRepository.deleteByTeamIdAndRole(teamId, TeamUserRole.PM);
    teamUserRepository.deleteByTeamIdAndRole(teamId, TeamUserRole.TRANSLATOR);
    String name = "deleted__" + System.currentTimeMillis() + "__" + team.getName();
    team.setName(StringUtils.abbreviate(name, Team.NAME_MAX_LENGTH));
    team.setDeleted(true);
    teamRepository.save(team);
  }

  @Transactional(readOnly = true)
  public List<Long> getTeamUserIdsByRole(Long teamId, TeamUserRole role) {
    getTeam(teamId);
    return teamUserRepository.findByTeamIdAndRole(teamId, role).stream()
        .map(TeamUser::getUser)
        .map(User::getId)
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
        teamUserRepository.findByTeamIdAndRole(teamId, TeamUserRole.PM).stream()
            .map(TeamUser::getUser)
            .map(User::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<Long> disallowedPmIds =
        normalizedIds.stream().filter(id -> !allowedPmIds.contains(id)).toList();
    if (!disallowedPmIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot assign PMs outside team PM roster: " + disallowedPmIds);
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
      throw new IllegalArgumentException("Slack client ID is required when Slack notifications are enabled");
    }

    if (normalizedEnabled && normalizedChannelId == null) {
      throw new IllegalArgumentException("Slack channel ID is required when Slack notifications are enabled");
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
  public void replaceTeamSlackUserMappings(Long teamId, List<UpsertTeamSlackUserMappingEntry> entries) {
    Team team = getTeam(teamId);

    List<UpsertTeamSlackUserMappingEntry> normalizedEntries =
        (entries == null ? List.<UpsertTeamSlackUserMappingEntry>of() : entries).stream()
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
      userRepository.findAllById(entriesByUserId.keySet()).forEach(user -> usersById.put(user.getId(), user));
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
