package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.HindiPronounAgreementPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class HindiPronounTermRendererTest {

  private final HindiPronounTermRenderer renderer =
      new HindiPronounTermRenderer(hindiTermPack(), hindiPronounPack());

  @Test
  public void rendersGenitivePronounFromReferencedTermGenderAndCount() {
    String message =
        "{$owner :term person=first case=genitive count=$ownerCount "
            + "agreeWith=$item agreeWithCount=$itemCount} "
            + "{$item :term case=direct count=$itemCount}.";

    assertThat(
            renderer.renderMessage(
                message,
                Map.of("item", "hi.case.अंगारा"),
                Map.of("ownerCount", "1", "itemCount", "1")))
        .isEqualTo("मेरा अंगारा.");
    assertThat(
            renderer.renderMessage(
                message,
                Map.of("item", "hi.case.अंगारा"),
                Map.of("ownerCount", "1", "itemCount", "2")))
        .isEqualTo("मेरे अंगारे.");
    assertThat(
            renderer.renderMessage(
                message,
                Map.of("item", "hi.case.अंगारा"),
                Map.of("ownerCount", "2", "itemCount", "2")))
        .isEqualTo("हमारे अंगारे.");
    assertThat(
            renderer.renderMessage(
                message,
                Map.of("item", "hi.case.आँख"),
                Map.of("ownerCount", "1", "itemCount", "2")))
        .isEqualTo("मेरी आँखें.");
  }

  @Test
  public void rendersSecondPersonRegisterAndDirectPronouns() {
    assertThat(
            renderer.renderMessage(
                "{$owner :term person=second register=formal case=genitive "
                    + "agreeWith=$item agreeWithCount=$itemCount} "
                    + "{$item :term case=direct count=$itemCount}.",
                Map.of("item", "hi.case.अंगारा"),
                Map.of("itemCount", "2")))
        .isEqualTo("आपके अंगारे.");
    assertThat(
            renderer.renderMessage(
                "{$owner :term person=first case=direct count=$ownerCount}.",
                Map.of(),
                Map.of("ownerCount", "2")))
        .isEqualTo("हम.");
  }

  @Test
  public void delegatesNonPronounTermsToCompiledRenderer() {
    assertThat(
            renderer.renderMessage(
                "में {$item :term case=oblique count=$count}.",
                Map.of("item", "hi.case.आँख"),
                Map.of("count", "2")))
        .isEqualTo("में आँखों.");
  }

  @Test
  public void rejectsGenitivePronounWithoutAgreementTarget() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "{$owner :term person=first case=genitive}.", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Failed to render term argument owner at span 0-41: "
                + "Hindi genitive pronoun usage requires agreeWith option: owner")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void rejectsMissingAgreementTermArgument() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "{$owner :term person=first case=genitive agreeWith=$item}.",
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Failed to render term argument owner at span 0-57")
        .hasMessageContaining("Missing agreeWith term argument: item");
  }

  @Test
  public void rejectsSecondPersonPronounWithoutRegister() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "{$owner :term person=second case=direct}.", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi second-person pronoun usage requires register option");
  }

  @Test
  public void rejectsNonSecondPersonPronounWithRegister() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "{$owner :term person=first register=formal case=direct}.", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi non-second pronoun usage cannot include register option");
  }

  @Test
  public void rejectsUnsupportedPronounOptions() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "{$owner :term person=first case=direct article=definite}.",
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hindi pronoun term option: article");
  }

  @Test
  public void rejectsNonNumericAgreementCount() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "{$owner :term person=first case=genitive "
                        + "agreeWith=$item agreeWithCount=$itemCount}.",
                    Map.of("item", "hi.case.अंगारा"),
                    Map.of("itemCount", "many")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Count variable must be numeric");
  }

  private static CompiledTermPack hindiTermPack() {
    return new CompiledTermPackJsonLoader()
        .load(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json"));
  }

  private static HindiPronounAgreementPack hindiPronounPack() {
    return new HindiPronounAgreementPackJsonLoader()
        .load(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/hi_pronoun_agreement_pack_fixture.json"));
  }

  private static String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
