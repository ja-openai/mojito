package com.box.l10n.mojito.service.mcp.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpServerService;
import com.box.l10n.mojito.service.mcp.McpToolCallResult;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.McpToolRegistry;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.BatchResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.CorrectionIdentity;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.ItemResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Outcome;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Verification;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public class ApplyTranslationCorrectionsMcpToolTest {

  private final GuardedTranslationCorrectionService correctionService =
      Mockito.mock(GuardedTranslationCorrectionService.class);
  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final ApplyTranslationCorrectionsMcpTool tool =
      new ApplyTranslationCorrectionsMcpTool(objectMapper, correctionService);

  @Before
  public void setUp() {
    SecurityContextHolder.clearContext();
  }

  @After
  public void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void descriptorDeclaresAdminMutatingBoundedFullyGuardedOperation() {
    assertThat(tool.descriptor().name()).isEqualTo("translation.apply_corrections");
    assertThat(tool.descriptor().readOnly()).isFalse();
    assertThat(tool.descriptor().dryRunByDefault()).isFalse();
    assertThat(tool.descriptor().description())
        .contains("EMERGENCY", "NORMAL", "BUG_FIXES", "Source-locale", "all other");
    McpToolParameter corrections =
        tool.descriptor().parameters().stream()
            .filter(parameter -> "corrections".equals(parameter.name()))
            .findFirst()
            .orElseThrow();
    assertThat(corrections.jsonSchema())
        .containsEntry("minItems", 1)
        .containsEntry("maxItems", 1_000);
    @SuppressWarnings("unchecked")
    Map<String, Object> itemSchema = (Map<String, Object>) corrections.jsonSchema().get("items");
    assertThat(itemSchema.get("required"))
        .isEqualTo(
            List.of(
                "reviewProjectId",
                "reviewProjectTextUnitId",
                "repositoryId",
                "repositoryName",
                "locale",
                "tmTextUnitId",
                "expectedCurrentVariantId",
                "expectedOldTarget",
                "replacementTarget"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) itemSchema.get("properties");
    assertThat(stringProperty(properties, "repositoryName"))
        .containsEntry(
            "maxLength", GuardedTranslationCorrectionService.MAX_REPOSITORY_NAME_CHARACTERS);
    assertThat(stringProperty(properties, "locale"))
        .containsEntry("maxLength", GuardedTranslationCorrectionService.MAX_LOCALE_CHARACTERS);
    assertThat(stringProperty(properties, "expectedOldTarget"))
        .containsEntry("maxLength", GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS);
    assertThat(stringProperty(properties, "replacementTarget"))
        .containsEntry("maxLength", GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS);
  }

  @Test
  public void adminCanApplyConfirmedBatchAndReceivesStructuredResults() {
    authenticateAs("ROLE_ADMIN");
    Correction correction = correction();
    ItemResult conflict =
        new ItemResult(
            0,
            Outcome.CONFLICT,
            "EXPECTED_OLD_TARGET_MISMATCH",
            "Current target changed",
            new CorrectionIdentity(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L),
            null,
            Verification.notPerformed());
    BatchResult expected = new BatchResult(1, 0, 1, 0, List.of(conflict));
    when(correctionService.applyCorrections(List.of(correction))).thenReturn(expected);

    Object result =
        tool.execute(new ApplyTranslationCorrectionsMcpTool.Input(true, List.of(correction)));

    assertThat(result).isEqualTo(expected);
    verify(correctionService).applyCorrections(List.of(correction));
  }

  @Test
  public void callSerializesSourceLocaleConflictWithoutTranslationPayloads() {
    authenticateAs("ROLE_ADMIN");
    Correction correction =
        new Correction(
            2L,
            3L,
            1L,
            "repo",
            "en",
            6L,
            10L,
            "credential-old-target",
            "credential-replacement-target");
    ItemResult conflict =
        new ItemResult(
            0,
            Outcome.CONFLICT,
            "SOURCE_LOCALE_CORRECTION_FORBIDDEN",
            "Repository source-locale content cannot be corrected",
            new CorrectionIdentity(2L, 3L, 1L, "repo", "en", 6L, 10L),
            null,
            Verification.notPerformed());
    when(correctionService.applyCorrections(List.of(correction)))
        .thenReturn(new BatchResult(1, 0, 1, 0, List.of(conflict)));

    McpToolCallResult result =
        tool.call(
            objectMapper.valueToTree(
                new ApplyTranslationCorrectionsMcpTool.Input(true, List.of(correction))));

    assertThat(result.error()).isFalse();
    assertThat(result.structuredContent().path("requestedCount").asInt()).isEqualTo(1);
    assertThat(result.structuredContent().path("conflictCount").asInt()).isEqualTo(1);
    assertThat(result.structuredContent().at("/results/0/outcome").asText()).isEqualTo("CONFLICT");
    assertThat(result.structuredContent().at("/results/0/code").asText())
        .isEqualTo("SOURCE_LOCALE_CORRECTION_FORBIDDEN");
    assertThat(result.structuredContent().toString())
        .doesNotContain("credential-old-target", "credential-replacement-target");
  }

  @Test
  public void nonAdminIsDeniedBeforeServiceInteraction() {
    authenticateAs("ROLE_TRANSLATOR");

    assertThatThrownBy(
            () ->
                tool.execute(
                    new ApplyTranslationCorrectionsMcpTool.Input(true, List.of(correction()))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Admin role required");
    verifyNoInteractions(correctionService);
  }

  @Test
  public void nonAdminMalformedPayloadIsDeniedBeforeTypedInputConversion() {
    authenticateAs("ROLE_TRANSLATOR");
    McpServerService serverService = new McpServerService(new McpToolRegistry(List.of(tool)));

    McpToolCallResult result =
        serverService.callTool(
            "translation.apply_corrections",
            objectMapper
                .createObjectNode()
                .put("confirmApply", true)
                .put("corrections", "not-an-array"));

    assertThat(result.error()).isTrue();
    assertThat(result.message()).isEqualTo("Admin role required");
    verifyNoInteractions(correctionService);
  }

  @Test
  public void explicitConfirmationIsRequiredBeforeServiceInteraction() {
    authenticateAs("ROLE_ADMIN");

    assertThatThrownBy(
            () ->
                tool.execute(
                    new ApplyTranslationCorrectionsMcpTool.Input(false, List.of(correction()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("confirmApply=true is required");
    verifyNoInteractions(correctionService);
  }

  private Correction correction() {
    return new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L, "old", "new");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> stringProperty(Map<String, Object> properties, String name) {
    return (Map<String, Object>) properties.get(name);
  }

  private static void authenticateAs(String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("operator", "ignored", role));
  }
}
