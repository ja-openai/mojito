package com.box.l10n.mojito.rest.admin;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.BatchResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.CorrectionIdentity;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.ItemResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Outcome;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Verification;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionTransactionService;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class GuardedTranslationCorrectionWSTest {

  private final GuardedTranslationCorrectionService correctionService =
      Mockito.mock(GuardedTranslationCorrectionService.class);
  private MockMvc mockMvc;

  @Before
  public void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new GuardedTranslationCorrectionWS(correctionService))
            .build();
  }

  @Test
  public void mapsConfirmedRequestAndReturnsStructuredMixedOutcomes() throws Exception {
    Correction correction = correction();
    ItemResult conflict =
        new ItemResult(
            0,
            Outcome.CONFLICT,
            "CURRENT_VARIANT_ID_MISMATCH",
            "Current variant changed",
            new CorrectionIdentity(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L),
            null,
            Verification.notPerformed());
    when(correctionService.applyCorrections(anyList()))
        .thenReturn(new BatchResult(1, 0, 1, 0, List.of(conflict)));

    mockMvc
        .perform(
            post("/api/admin/translation-corrections/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "confirmApply": true,
                      "corrections": [{
                        "reviewProjectId": 2,
                        "reviewProjectTextUnitId": 3,
                        "repositoryId": 1,
                        "repositoryName": "repo",
                        "locale": "fr-FR",
                        "tmTextUnitId": 6,
                        "expectedCurrentVariantId": 10,
                        "expectedOldTarget": "old",
                        "replacementTarget": "new"
                      }]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestedCount").value(1))
        .andExpect(jsonPath("$.conflictCount").value(1))
        .andExpect(jsonPath("$.results[0].outcome").value("CONFLICT"))
        .andExpect(jsonPath("$.results[0].code").value("CURRENT_VARIANT_ID_MISMATCH"))
        .andExpect(jsonPath("$.results[0].stored").doesNotExist());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Correction>> corrections = ArgumentCaptor.forClass(List.class);
    verify(correctionService).applyCorrections(corrections.capture());
    org.assertj.core.api.Assertions.assertThat(corrections.getValue()).containsExactly(correction);
  }

  @Test
  public void rejectsMissingConfirmationBeforeCallingService() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/translation-corrections/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"corrections\": []}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/admin/translation-corrections/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
        .andExpect(status().isBadRequest());
    verify(correctionService, never()).applyCorrections(anyList());
  }

  @Test
  public void mapsRequestWideValidationFailureToBadRequest() throws Exception {
    when(correctionService.applyCorrections(List.of()))
        .thenThrow(new IllegalArgumentException("corrections are required"));

    mockMvc
        .perform(
            post("/api/admin/translation-corrections/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmApply\":true,\"corrections\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void rejectsOversizedTransportPayloadBeforeStartingRowTransactions() throws Exception {
    GuardedTranslationCorrectionTransactionService transactionService =
        Mockito.mock(GuardedTranslationCorrectionTransactionService.class);
    UserService userService = Mockito.mock(UserService.class);
    User admin = new User();
    admin.setId(42L);
    admin.setEnabled(true);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(userService.getCurrentUser()).thenReturn(Optional.of(admin));
    GuardedTranslationCorrectionService boundedService =
        new GuardedTranslationCorrectionService(transactionService, userService);
    MockMvc boundedMockMvc =
        MockMvcBuilders.standaloneSetup(new GuardedTranslationCorrectionWS(boundedService)).build();
    String oversizedRepositoryName =
        "r".repeat(GuardedTranslationCorrectionService.MAX_REPOSITORY_NAME_CHARACTERS + 1);

    boundedMockMvc
        .perform(
            post("/api/admin/translation-corrections/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "confirmApply": true,
                      "corrections": [{
                        "reviewProjectId": 2,
                        "reviewProjectTextUnitId": 3,
                        "repositoryId": 1,
                        "repositoryName": "%s",
                        "locale": "fr-FR",
                        "tmTextUnitId": 6,
                        "expectedCurrentVariantId": 10,
                        "expectedOldTarget": "old",
                        "replacementTarget": "new"
                      }]
                    }
                    """
                        .formatted(oversizedRepositoryName)))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(transactionService);
  }

  private Correction correction() {
    return new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L, "old", "new");
  }
}
