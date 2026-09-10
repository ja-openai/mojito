package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.security.UserPreferences;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatReview;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatSuggestion;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewRequestUsageService.StartInput;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserPreferencesService;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

public class AiReviewInteractiveServiceTest {

  private AiReviewConfigurationProperties configuration;
  private UserService users;
  private UserPreferencesService preferences;
  private PollableTaskService tasks;
  private AiReviewRequestUsageService usage;
  private AiReviewInteractiveService service;
  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();

  @Before
  public void setUp() {
    configuration = new AiReviewConfigurationProperties();
    users = mock(UserService.class);
    preferences = mock(UserPreferencesService.class);
    tasks = mock(PollableTaskService.class);
    usage = mock(AiReviewRequestUsageService.class);
    service =
        new AiReviewInteractiveService(
            configuration, users, preferences, tasks, usage, objectMapper);
  }

  @Test
  public void capturesAuthenticatedActorAndUsesMigratedPresetWhenRequestOmitsIt() {
    authenticate(17L, savedPreferences("version_a", false));
    AiReviewChatRequest request = request(null, "review_project", "manual");

    Prepared prepared = service.prepare(request);

    assertEquals(request.messages(), prepared.request().messages());
    assertEquals("corrections_and_alternatives", prepared.request().reviewStyle());
    assertEquals(Long.valueOf(17), prepared.userId());
    assertEquals("fast", prepared.settings().profileId());
    assertEquals("gpt-5.6-sol", prepared.settings().modelName());
    assertEquals("none", prepared.settings().reasoningEffort());
    assertEquals("priority", prepared.settings().serviceTier());
    verify(users).getCurrentUser();
    verify(preferences).getCurrentUserPreferences();
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void automaticUltraFallsBackAndSavedUltraRemainsAvailableManually() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    authenticate(
        17L,
        objectMapper.readValueUnchecked("{\"aiReviewPreset\":\"ultra\"}", UserPreferences.class));
    Prepared automatic = service.prepare(request(null, "review_project", "automatic"));
    assertEquals("balanced", automatic.settings().profileId());
    assertEquals("low", automatic.settings().reasoningEffort());
    Prepared manual = service.prepare(request(null, "review_project", "manual"));
    assertEquals("ultra", manual.settings().profileId());
    assertEquals("max", manual.settings().reasoningEffort());
  }

  @Test
  public void executionRechecksFrozenAutomaticUltraWithoutChangingManualRequests() {
    var settings =
        new AiReviewInteractiveService.Settings("ultra", "gpt-6-astra", "max", "low", "priority");
    Prepared frozen = new Prepared(request(null, "review_project", "automatic"), 17L, settings);
    Prepared effective = service.enforceExecutionPolicy(frozen);
    assertEquals("balanced", effective.settings().profileId());
    assertEquals("low", effective.settings().reasoningEffort());
    assertEquals(frozen.userId(), effective.userId());
    assertEquals("max", frozen.settings().reasoningEffort());
    Prepared manual = new Prepared(request(null, "review_project", "manual"), 17L, settings);
    assertEquals(manual, service.enforceExecutionPolicy(manual));
    configuration.getInteractive().setUltraAutomaticEnabled(true);
    assertEquals(frozen, service.enforceExecutionPolicy(frozen));
  }

  @Test
  public void requiresAnAuthenticatedActorBeforeReadingPreferences() {
    when(users.getCurrentUser()).thenReturn(Optional.empty());

    assertThrows(
        AccessDeniedException.class,
        () -> service.prepare(request("version_a", "review_project", "manual")));

    verifyNoInteractions(preferences, tasks, usage);
  }

  @Test
  public void savedReviewStyleIsFrozenAndAnExplicitRequestCanOverrideIt() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    authenticate(
        17L,
        objectMapper.readValueUnchecked(
            """
            {"aiReviewStyle":"corrections_only","aiReviewShowScore":false,
             "aiReviewPreset":"deep","aiReviewAutomaticDisabled":true}
            """,
            UserPreferences.class));
    Prepared saved = service.prepare(request(null, "review_project", "manual"));
    assertEquals("corrections_only", saved.request().reviewStyle());
    assertEquals("deep", saved.settings().profileId());

    AiReviewChatRequest override =
        requestWithStyle(saved.request(), "corrections_and_alternatives");
    Prepared selected = service.prepare(override);
    assertEquals("corrections_and_alternatives", selected.request().reviewStyle());
    authenticate(99L, UserPreferences.defaults());
    Prepared serialized =
        objectMapper.readValueUnchecked(
            objectMapper.writeValueAsStringUnchecked(saved), Prepared.class);
    service.start(serialized, 81L);
    verify(usage)
        .start(
            argThat(
                input ->
                    input.userId().equals(17L)
                        && objectMapper
                            .readValueUnchecked(input.requestJson(), AiReviewChatRequest.class)
                            .reviewStyle()
                            .equals("corrections_only")));
  }

  @Test
  public void invalidReviewStyleIsRejectedBeforeSchedulingOrRecordingUsage() {
    authenticate(17L, UserPreferences.defaults());
    for (String style : List.of("", "suggestions", "CORRECTIONS_ONLY")) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          assertThrows(
                  ResponseStatusException.class,
                  () -> service.prepare(requestWithStyle(request(null, null, null), style)))
              .getStatusCode());
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void sixPresetsResolveServerModelReasoningAndTierTogether() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    authenticate(17L, UserPreferences.defaults());
    for (String[] expected :
        new String[][] {
          {"fastest", "gpt-5.6-luna", "none"},
          {"fast", "gpt-5.6-sol", "none"},
          {"balanced", "gpt-6-astra", "low"},
          {"thorough", "gpt-6-astra", "medium"},
          {"deep", "gpt-6-astra", "high"},
          {"ultra", "gpt-6-astra", "max"}
        }) {
      Prepared prepared = service.prepare(presetRequest(expected[0]));
      assertEquals(expected[0], prepared.settings().profileId());
      assertEquals(expected[1], prepared.settings().modelName());
      assertEquals(expected[2], prepared.settings().reasoningEffort());
      assertEquals("priority", prepared.settings().serviceTier());
    }
    assertEquals("balanced", service.prepare(request(null, null, null)).settings().profileId());
    authenticate(
        17L,
        new UserPreferences(
            true,
            null,
            List.of(),
            null,
            false,
            false,
            List.of(),
            "version_a",
            false,
            "low",
            "thorough"));
    assertEquals("thorough", service.prepare(request(null, null, null)).settings().profileId());
  }

  @Test
  public void nonAdminsCanUseOnlyFastestFastAndBalanced() {
    authenticate(17L, UserPreferences.defaults());
    for (String preset : List.of("fastest", "fast", "balanced")) {
      assertEquals(preset, service.prepare(presetRequest(preset)).settings().profileId());
    }
    for (String preset : List.of("thorough", "deep", "ultra")) {
      assertThrows(AccessDeniedException.class, () -> service.prepare(presetRequest(preset)));
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void nonAdminSavedExtendedPresetsUseBalancedWithoutChangingOtherSettings() {
    for (String preset : List.of("thorough", "deep", "ultra")) {
      UserPreferences saved =
          objectMapper.readValueUnchecked(
              """
              {"aiReviewPreset":"%s","aiReviewAutomaticDisabled":true,
               "aiReviewStyle":"corrections_only","aiReviewShowScore":false}
              """
                  .formatted(preset),
              UserPreferences.class);
      authenticate(17L, saved);
      Prepared prepared = service.prepare(request(null, "review_project", "manual"));
      assertEquals("balanced", prepared.settings().profileId());
      assertEquals("low", prepared.settings().reasoningEffort());
      assertEquals("corrections_only", prepared.request().reviewStyle());
      assertEquals(preset, saved.aiReviewPreset());
      assertThrows(
          ResponseStatusException.class,
          () -> service.prepare(request(null, "review_project", "automatic")));
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void legacySelectorsCannotBypassTheAdminRestriction() {
    for (String effort : List.of("medium", "high")) {
      authenticate(17L, savedPreferences("version_b", false, effort));
      assertThrows(
          AccessDeniedException.class, () -> service.prepare(requestWithEffort("fr", effort)));
      assertEquals("balanced", service.prepare(request(null, null, null)).settings().profileId());
      for (String profile : List.of("version_a", "version_b")) {
        assertEquals(
            "low", service.prepare(request(profile, null, null)).settings().reasoningEffort());
      }
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void presetOverrideAndActorAreFrozenForUsageEvenWhenAccountAndConfigurationChange() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    authenticate(17L, UserPreferences.defaults());
    var configured = configuration.getInteractive().getPresets().get("ultra");
    configured.setModelName("configured-model");
    configured.setReasoningEffort("high");
    configured.setServiceTier("default");
    AiReviewChatRequest request = presetRequest("ultra");
    Prepared prepared = service.prepare(request);

    configured.setModelName("later-model");
    configured.setReasoningEffort("none");
    configured.setServiceTier("priority");
    authenticate(99L, savedPreferences("version_a", false));
    StartInput expected =
        new StartInput(
            17L,
            81L,
            42L,
            "fr",
            "review_project",
            "manual",
            "ultra",
            "configured-model",
            "high",
            "default",
            objectMapper.writeValueAsStringUnchecked(prepared.request()));
    when(usage.start(expected)).thenReturn(72L);

    assertEquals(Long.valueOf(72), service.start(prepared, 81L));
    verify(usage).start(expected);
    assertEquals("ultra", prepared.request().presetId());
  }

  @Test
  public void rejectsUnknownPresetsAndAmbiguousLegacySelectorsBeforeScheduling() {
    authenticate(17L, UserPreferences.defaults());
    for (String preset : List.of("", "version_a", "gpt-6-astra", "unknown")) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          assertThrows(ResponseStatusException.class, () -> service.prepare(presetRequest(preset)))
              .getStatusCode());
    }
    for (String[] legacy : new String[][] {{"version_a", null}, {null, "low"}}) {
      AiReviewChatRequest request =
          new AiReviewChatRequest(
              "Hello",
              "Bonjour",
              "fr",
              null,
              42L,
              List.of(new AiReviewChatMessage("user", "Review")),
              legacy[0],
              "manual",
              "review_project",
              legacy[1],
              "fast");
      assertEquals(
          HttpStatus.BAD_REQUEST,
          assertThrows(ResponseStatusException.class, () -> service.prepare(request))
              .getStatusCode());
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void missingPresetConfigurationFailsWithSafeMessage() {
    authenticate(17L, UserPreferences.defaults());
    configuration.getInteractive().getPresets().remove("balanced");
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class, () -> service.prepare(presetRequest("balanced")));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    assertEquals("The selected AI review preset is unavailable.", error.getReason());
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void versionChoicesFreezeDistinctProviderSettingsWithoutChangingGlobalConfiguration() {
    authenticate(17L, UserPreferences.defaults());
    configuration.setModelName("legacy-model");
    configuration.getResponses().setReasoningEffort("max");
    configuration.getResponses().setServiceTier("priority");

    Prepared versionA = service.prepare(request("version_a", "review_project", "manual"));
    Prepared versionB = service.prepare(request("version_b", "review_project", "manual"));

    assertEquals("gpt-5.6-sol", versionA.settings().modelName());
    assertEquals("gpt-6-astra", versionB.settings().modelName());
    assertEquals("low", versionA.settings().reasoningEffort());
    assertEquals("low", versionB.settings().reasoningEffort());
    assertEquals("low", versionA.settings().textVerbosity());
    assertEquals("priority", versionB.settings().serviceTier());
    assertEquals("legacy-model", configuration.getModelName());
    assertEquals("max", configuration.getResponses().getReasoningEffort());

    configuration.getInteractive().getVersionA().setModelName("next-model");
    configuration.getInteractive().getVersionA().setReasoningEffort("high");
    configuration.getResponses().setServiceTier("default");

    assertEquals("gpt-5.6-sol", versionA.settings().modelName());
    assertEquals("low", versionA.settings().reasoningEffort());
    assertEquals("priority", versionA.settings().serviceTier());
    assertEquals("gpt-6-astra", versionB.settings().modelName());
  }

  @Test
  public void savedReasoningEffortAppliesToEitherModelAndExplicitRequestChoiceIsFrozen() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    authenticate(17L, savedPreferences("version_b", false, "medium"));
    configuration.getInteractive().getVersionA().setReasoningEffort("max");
    configuration.getInteractive().getVersionB().setReasoningEffort("max");

    Prepared versionA = service.prepare(request("version_a", "review_project", "manual"));
    Prepared versionB = service.prepare(request("version_b", "review_project", "manual"));
    Prepared selectedHigh = service.prepare(requestWithEffort(" \tfr-CA \n", "high"));

    assertEquals("medium", versionA.settings().reasoningEffort());
    assertEquals("medium", versionB.settings().reasoningEffort());
    assertEquals("high", selectedHigh.settings().reasoningEffort());
    assertEquals("high", selectedHigh.request().reasoningEffort());
    assertEquals("fr-CA", selectedHigh.request().localeTag());

    authenticate(17L, savedPreferences("version_b", false, "low"));
    configuration.getInteractive().getVersionB().setReasoningEffort("low");
    assertEquals("high", selectedHigh.settings().reasoningEffort());
    when(usage.start(any())).thenReturn(73L);
    assertEquals(Long.valueOf(73), service.start(selectedHigh, 91L));
    ArgumentCaptor<StartInput> input = ArgumentCaptor.forClass(StartInput.class);
    verify(usage).start(input.capture());
    assertEquals("high", input.getValue().reasoningEffort());
  }

  @Test
  public void rejectsUnknownRequestReasoningEffortBeforeRecordingOrExecuting() {
    authenticate(17L, UserPreferences.defaults());
    for (String effort : List.of("", "none", "max", "HIGH", "unknown")) {
      ResponseStatusException error =
          assertThrows(
              ResponseStatusException.class,
              () -> service.prepare(requestWithEffort("fr", effort)));
      assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void rejectsProviderNamesAndUnrecognizedProfileOrRequestCategories() {
    authenticate(17L, UserPreferences.defaults());
    for (AiReviewChatRequest request :
        List.of(
            request("gpt-6-astra", "review_project", "manual"),
            request("fast", "review_project", "manual"),
            request("version_c", "review_project", "manual"),
            request("version_a", "other_screen", "manual"),
            request("version_a", "review_project", "untracked"))) {
      ResponseStatusException exception =
          assertThrows(ResponseStatusException.class, () -> service.prepare(request));
      assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void automaticOptOutStillAllowsExplicitRequestsAndFollowUps() {
    authenticate(17L, savedPreferences("version_b", true));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () -> service.prepare(request(null, "review_project", "automatic")));
    assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    assertEquals(
        "balanced",
        service.prepare(request(null, "review_project", "manual")).settings().profileId());
    assertEquals(
        "balanced",
        service.prepare(request(null, "review_project", "follow_up")).settings().profileId());
  }

  @Test
  public void forwardsCapturedActorTaskAndResolvedMetadataToUsageStorage() {
    authenticate(17L, UserPreferences.defaults());
    Prepared prepared = service.prepare(request("version_a", "text_unit_detail", "follow_up"));
    authenticate(99L, UserPreferences.defaults());
    StartInput expected =
        new StartInput(
            17L,
            81L,
            42L,
            "fr",
            "text_unit_detail",
            "follow_up",
            "version_a",
            "gpt-5.6-sol",
            "low",
            "default",
            objectMapper.writeValueAsStringUnchecked(prepared.request()));
    when(usage.start(expected)).thenReturn(72L);

    assertEquals(Long.valueOf(72), service.start(prepared, 81L));
    service.finish(72L, "completed", 2400L, "returned-model", "priority", null);

    verify(usage).start(expected);
    verify(usage).finish(72L, "completed", 2400L, "returned-model", "priority", null);
  }

  @Test
  public void defaultsMissingRequestMetadataToLegacyAndUnknown() {
    authenticate(17L, UserPreferences.defaults());
    Prepared prepared = service.prepare(request(null, null, null));

    service.start(prepared, null);

    verify(usage)
        .start(
            new StartInput(
                17L,
                null,
                42L,
                "fr",
                "unknown",
                "legacy",
                "balanced",
                "gpt-6-astra",
                "low",
                "priority",
                objectMapper.writeValueAsStringUnchecked(prepared.request())));
  }

  @Test
  public void inspectionSnapshotsKeepAllSubmittedRolesAndTheFullReturnedResponse() {
    authenticate(17L, UserPreferences.defaults());
    AiReviewChatRequest request =
        new AiReviewChatRequest(
            "Hello {name}",
            "Привіт {name}",
            "uk",
            "Greeting in the account menu",
            42L,
            List.of(
                new AiReviewChatMessage("system", "Page context"),
                new AiReviewChatMessage("user", "Review this translation"),
                new AiReviewChatMessage("assistant", "Earlier explanation"),
                new AiReviewChatMessage("user", "Explain your suggestion")),
            null,
            "follow_up",
            "review_project",
            null,
            "fast");
    Prepared prepared = service.prepare(request);
    when(usage.start(any())).thenReturn(72L);

    assertEquals(Long.valueOf(72), service.start(prepared, 81L));
    ArgumentCaptor<StartInput> input = ArgumentCaptor.forClass(StartInput.class);
    verify(usage).start(input.capture());
    assertEquals(
        prepared.request(),
        objectMapper.readValueUnchecked(input.getValue().requestJson(), AiReviewChatRequest.class));

    AiReviewChatResponse response =
        new AiReviewChatResponse(
            new AiReviewChatMessage("assistant", "Consider this alternative."),
            List.of(new AiReviewChatSuggestion("Вітаємо, {name}", 94, "A different tone.")),
            new AiReviewChatReview(2, "The current translation is valid."));
    service.finish(72L, "completed", 2400L, "returned-model", "priority", response);
    verify(usage)
        .finish(
            72L,
            "completed",
            2400L,
            "returned-model",
            "priority",
            objectMapper.writeValueAsStringUnchecked(response));
  }

  @Test
  public void normalizesRequestLocaleBeforeFreezingItAndRecordingUsage() {
    authenticate(17L, UserPreferences.defaults());
    AiReviewChatRequest request = requestWithLocale(" \tfr-CA \n");

    Prepared prepared = service.prepare(request);
    service.start(prepared, 81L);

    assertEquals("fr-CA", prepared.request().localeTag());
    assertEquals(request.source(), prepared.request().source());
    assertEquals(request.target(), prepared.request().target());
    assertEquals(request.messages(), prepared.request().messages());
    assertEquals(" \tfr-CA \n", request.localeTag());
    verify(usage).start(argThat(input -> "fr-CA".equals(input.locale())));
  }

  @Test
  public void defaultsMissingAndBlankLocalesForNewAndLegacyRequests() {
    authenticate(17L, UserPreferences.defaults());
    for (String locale : new String[] {null, "", " \t\n"}) {
      assertEquals("en", service.prepare(requestWithLocale(locale)).request().localeTag());
      assertEquals(
          "en", service.prepareLegacyJob(requestWithLocale(locale), 81L).request().localeTag());
    }
  }

  @Test
  public void rejectsOverlongLocalesBeforeARequestCanBeScheduledOrRecorded() {
    authenticate(17L, UserPreferences.defaults());
    AiReviewChatRequest request = requestWithLocale("x".repeat(65));

    assertEquals(
        HttpStatus.BAD_REQUEST,
        assertThrows(ResponseStatusException.class, () -> service.prepare(request))
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        assertThrows(ResponseStatusException.class, () -> service.prepareLegacyJob(request, 81L))
            .getStatusCode());
    verifyNoInteractions(tasks, usage);
  }

  @Test
  public void usageStorageFailuresDoNotAbortReviewProcessing() {
    authenticate(17L, UserPreferences.defaults());
    Prepared prepared = service.prepare(request("version_b", "review_project", "retry"));
    when(usage.start(any())).thenThrow(new IllegalStateException("Storage unavailable"));
    doThrow(new IllegalStateException("Storage unavailable"))
        .when(usage)
        .finish(72L, "timeout", 30000L, null, null, null);

    assertNull(service.start(prepared, 81L));
    service.finish(null, "timeout", 30000L, null, null, null);
    service.finish(72L, "timeout", 30000L, null, null, null);

    verify(usage).finish(72L, "timeout", 30000L, null, null, null);

    AiReviewChatResponse response =
        new AiReviewChatResponse(
            new AiReviewChatMessage("assistant", "No change suggested."), List.of(), null);
    String responseJson = objectMapper.writeValueAsStringUnchecked(response);
    doThrow(new IllegalStateException("Storage unavailable"))
        .when(usage)
        .finish(73L, "completed", 2400L, "model", "priority", responseJson);
    service.finish(73L, "completed", 2400L, "model", "priority", response);
    verify(usage).finish(73L, "completed", 2400L, "model", "priority", responseJson);
  }

  @Test
  public void legacyJobsResolveTheirStoredOwnerWithoutAnHttpAuthenticationContext() {
    when(tasks.getCreatedByUserIdWithAncestorFallback(81L)).thenReturn(17L);
    configuration.setModelName("legacy-model");
    configuration.getResponses().setReasoningEffort("medium");
    AiReviewChatRequest request = request(null, null, null);

    Prepared prepared = service.prepareLegacyJob(request, 81L);

    assertEquals(request.messages(), prepared.request().messages());
    assertEquals("corrections_only", prepared.request().reviewStyle());
    assertEquals(Long.valueOf(17), prepared.userId());
    assertEquals("version_b", prepared.settings().profileId());
    assertEquals("legacy-model", prepared.settings().modelName());
    assertEquals("medium", prepared.settings().reasoningEffort());
    verify(tasks).getCreatedByUserIdWithAncestorFallback(81L);
    verifyNoInteractions(users, preferences, usage);
  }

  private void authenticate(Long userId, UserPreferences saved) {
    User user = new User();
    user.setId(userId);
    when(users.getCurrentUser()).thenReturn(Optional.of(user));
    when(preferences.getCurrentUserPreferences()).thenReturn(saved);
  }

  private UserPreferences savedPreferences(String profile, boolean automaticDisabled) {
    return savedPreferences(profile, automaticDisabled, "low");
  }

  private UserPreferences savedPreferences(
      String profile, boolean automaticDisabled, String reasoningEffort) {
    return new UserPreferences(
        true,
        null,
        List.of(),
        null,
        false,
        false,
        List.of(),
        profile,
        automaticDisabled,
        reasoningEffort);
  }

  private AiReviewChatRequest request(String profile, String surface, String type) {
    return new AiReviewChatRequest(
        "Hello",
        "Bonjour",
        "fr",
        "Greeting",
        42L,
        List.of(new AiReviewChatMessage("user", "Please review this translation.")),
        profile,
        type,
        surface);
  }

  private AiReviewChatRequest requestWithLocale(String locale) {
    return requestWithEffort(locale, null);
  }

  private AiReviewChatRequest requestWithStyle(AiReviewChatRequest request, String style) {
    return new AiReviewChatRequest(
        request.source(),
        request.target(),
        request.localeTag(),
        request.sourceDescription(),
        request.tmTextUnitId(),
        request.messages(),
        request.profileId(),
        request.requestType(),
        request.surface(),
        request.reasoningEffort(),
        request.presetId(),
        style);
  }

  private AiReviewChatRequest presetRequest(String preset) {
    return new AiReviewChatRequest(
        "Hello",
        "Bonjour",
        " fr ",
        "Greeting",
        42L,
        List.of(new AiReviewChatMessage("user", "Please review this translation.")),
        null,
        "manual",
        "review_project",
        null,
        preset);
  }

  private AiReviewChatRequest requestWithEffort(String locale, String reasoningEffort) {
    return new AiReviewChatRequest(
        "Hello",
        "Bonjour",
        locale,
        "Greeting",
        42L,
        List.of(new AiReviewChatMessage("user", "Please review this translation.")),
        "version_b",
        "manual",
        "review_project",
        reasoningEffort);
  }
}
