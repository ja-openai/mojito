package com.box.l10n.mojito.service.security.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.rest.security.UserPreferences;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.security.UserDetailsImpl;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public class UserPreferencesServiceTest extends ServiceTestBase {

  @Autowired UserPreferencesService preferencesService;
  @Autowired UserService userService;
  @Autowired UserRepository userRepository;
  @Autowired UserPreferencesRepository preferencesRepository;
  @Autowired UserDeletionService userDeletionService;
  @Autowired TeamService teamService;
  @Autowired TeamRepository teamRepository;
  @Autowired ObjectMapper objectMapper;

  private Authentication originalAuthentication;
  private User firstUser;
  private User secondUser;

  @Before
  public void createUsers() {
    originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
    firstUser =
        userService.createUserWithRole(
            "preferences-first-" + UUID.randomUUID(), "test", Role.ROLE_USER);
    secondUser =
        userService.createUserWithRole(
            "preferences-second-" + UUID.randomUUID(), "test", Role.ROLE_TRANSLATOR);
    authenticate(firstUser);
  }

  @After
  public void restoreAuthentication() {
    SecurityContextHolder.getContext().setAuthentication(originalAuthentication);
  }

  @Test
  public void defaultsAreReadWithoutInitializingOrChangingEitherAccount() {
    assertEquals(UserPreferences.defaults(), preferencesService.getCurrentUserPreferences());
    assertTrue(preferencesRepository.findByUserId(firstUser.getId()).isEmpty());
    authenticate(secondUser);
    assertEquals(UserPreferences.defaults(), preferencesService.getCurrentUserPreferences());
  }

  @Test
  public void savesPersistAcrossReadsAreIsolatedAndMergePartialUpdates() throws Exception {
    patch(
        """
        {"worksetSize":100,"preferredLocales":["fr-CA","FR-ca","de"],
         "shortcutHelp":"hidden","visibleTextEditorEnabled":true}
        """);
    UserPreferences saved = patch("{\"reviewProjectSearchEnabled\":true}");
    assertTrue(saved.initialized());
    assertEquals(Integer.valueOf(100), saved.worksetSize());
    assertEquals(List.of("fr-CA", "de"), saved.preferredLocales());
    assertEquals("hidden", saved.shortcutHelp());
    assertTrue(saved.visibleTextEditorEnabled());
    assertTrue(saved.reviewProjectSearchEnabled());
    assertEquals(saved, preferencesService.getCurrentUserPreferences());
    assertEquals(
        saved,
        objectMapper.readValue(
            preferencesRepository
                .findByUserId(firstUser.getId())
                .orElseThrow()
                .getPreferencesJson(),
            UserPreferences.class));

    authenticate(secondUser);
    assertEquals(UserPreferences.defaults(), preferencesService.getCurrentUserPreferences());
    patch("{\"worksetSize\":25}");
    authenticate(firstUser);
    assertEquals(saved, preferencesService.getCurrentUserPreferences());
  }

  @Test
  public void explicitNullResetsOverridesAndRemainsInitialized() throws Exception {
    patch("{\"worksetSize\":100,\"shortcutHelp\":\"bottom\",\"visibleTextEditorEnabled\":true}");
    UserPreferences reset = patch("{\"worksetSize\":null,\"shortcutHelp\":null}");
    assertNull(reset.worksetSize());
    assertNull(reset.shortcutHelp());
    assertTrue(reset.initialized());
    assertTrue(reset.visibleTextEditorEnabled());
    JsonNode json = objectMapper.valueToTree(reset);
    assertTrue(json.has("worksetSize"));
    assertTrue(json.get("worksetSize").isNull());
    assertTrue(json.has("shortcutHelp"));
    assertEquals(reset, preferencesService.getCurrentUserPreferences());
  }

  @Test
  public void invalidInputCannotSaveAnyFields() throws Exception {
    UserPreferences saved = patch("{\"worksetSize\":50}");
    for (String body :
        List.of(
            "[]",
            "null",
            "{\"userId\":1}",
            "{\"initialized\":true}",
            "{\"worksetSize\":0}",
            "{\"worksetSize\":1.5}",
            "{\"worksetSize\":\"50\"}",
            "{\"worksetSize\":2147483648}",
            "{\"shortcutHelp\":\"wrong\"}",
            "{\"preferredLocales\":null}",
            "{\"preferredLocales\":[\"en_US\"]}",
            "{\"preferredLocales\":[10]}",
            "{\"visibleTextEditorEnabled\":1}",
            "{\"reviewProjectSearchEnabled\":null}",
            "{\"worksetSize\":100,\"visibleTextEditorEnabled\":\"true\"}")) {
      ResponseStatusException exception =
          assertThrows(ResponseStatusException.class, () -> patch(body));
      assertEquals(body, HttpStatus.BAD_REQUEST, exception.getStatusCode());
      assertEquals(body, saved, preferencesService.getCurrentUserPreferences());
    }
    String tooManyLocales =
        objectMapper.writeValueAsString(java.util.Collections.nCopies(257, "fr"));
    assertThrows(
        ResponseStatusException.class,
        () -> patch("{\"preferredLocales\":" + tooManyLocales + "}"));
  }

  @Test
  public void reviewTeamsRequireManagerAuthorityExistingTeamsAndTeamAccess() throws Exception {
    assertThrows(AccessDeniedException.class, () -> patch("{\"defaultReviewTeamIds\":[]}"));
    authenticate(secondUser);
    assertThrows(AccessDeniedException.class, () -> patch("{\"defaultReviewTeamIds\":[]}"));

    restoreAuthentication();
    Team team = teamService.createTeam("preferences-team-" + UUID.randomUUID());
    User pm =
        userService.createUserWithRole("preferences-pm-" + UUID.randomUUID(), "test", Role.ROLE_PM);
    String teamPatch = "{\"defaultReviewTeamIds\":[" + team.getId() + "]}";
    assertEquals(List.of(team.getId()), patch(teamPatch).defaultReviewTeamIds());
    authenticate(pm);
    assertThrows(AccessDeniedException.class, () -> patch(teamPatch));
    restoreAuthentication();
    teamService.setUserTeamAssignments(pm.getId(), List.of(team.getId()), List.of());
    authenticate(pm);
    assertEquals(List.of(team.getId()), patch(teamPatch).defaultReviewTeamIds());
    assertEquals(List.of(), patch("{\"defaultReviewTeamIds\":[]}").defaultReviewTeamIds());
    assertThrows(
        ResponseStatusException.class,
        () -> patch("{\"defaultReviewTeamIds\":[9007199254740991]}"));
    assertThrows(ResponseStatusException.class, () -> patch("{\"defaultReviewTeamIds\":[1.5]}"));
    team.setEnabled(false);
    teamRepository.saveAndFlush(team);
    assertThrows(ResponseStatusException.class, () -> patch(teamPatch));
  }

  @Test
  public void concurrentPartialFirstSavesRetainBothChanges() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> first =
          executor.submit(() -> concurrentPatch(ready, start, "{\"worksetSize\":75}"));
      Future<?> second =
          executor.submit(
              () -> concurrentPatch(ready, start, "{\"visibleTextEditorEnabled\":true}"));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
      UserPreferences saved = preferencesService.getCurrentUserPreferences();
      assertEquals(Integer.valueOf(75), saved.worksetSize());
      assertTrue(saved.visibleTextEditorEnabled());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void unrelatedStaleUserUpdatesCannotOverwritePreferences() throws Exception {
    User staleUser = userRepository.findById(firstUser.getId()).orElseThrow();
    UserPreferences saved = patch("{\"worksetSize\":100}");
    staleUser.setGivenName("Updated profile");
    userRepository.saveAndFlush(staleUser);
    assertEquals(saved, preferencesService.getCurrentUserPreferences());
    assertFalse(objectMapper.valueToTree(staleUser).has("preferencesJson"));
  }

  @Test
  public void deletingUserRemovesPreferences() throws Exception {
    patch("{\"worksetSize\":100}");
    restoreAuthentication();
    userDeletionService.hardDeleteUser(firstUser.getId());
    assertTrue(preferencesRepository.findByUserId(firstUser.getId()).isEmpty());
  }

  private void concurrentPatch(CountDownLatch ready, CountDownLatch start, String body) {
    authenticate(firstUser);
    ready.countDown();
    try {
      assertTrue(start.await(10, TimeUnit.SECONDS));
      patch(body);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private UserPreferences patch(String body) throws Exception {
    return preferencesService.patchCurrentUserPreferences(objectMapper.readTree(body));
  }

  private void authenticate(User user) {
    UserDetailsImpl details = new UserDetailsImpl(user);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                details, null, details.getAuthorities()));
  }
}
