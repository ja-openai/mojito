package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.BindingStatus;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.TermBindingReport;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class TermBindingManifestValidatorTest {

  private final TermBindingManifestValidator validator = new TermBindingManifestValidator();

  @Test
  public void reportsRenderableSingletonBindings() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "es",
                  "messages": {
                    "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.water"]
                    }
                  }
                }
                """));

    assertThat(report.summary().messages()).isEqualTo(1);
    assertThat(report.summary().requiredArguments()).isEqualTo(1);
    assertThat(report.summary().diagnostics()).isZero();
    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").requiredArguments())
        .containsExactly("item");
    assertThat(report.messages().get("inventory.deleted").arguments().get("item").status())
        .isEqualTo(BindingStatus.OK);
    TermBindingManifestValidator.requireRenderable(report);
  }

  @Test
  public void reportsUnknownSingletonBindingsWhenKnownTermIdsAreProvided() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "es",
                  "messages": {
                    "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.missing"]
                    }
                  }
                }
                """),
            Set.of("item.water"));

    assertThat(report.summary().diagnostics()).isEqualTo(1);
    assertThat(report.diagnostics())
        .extracting("messageId", "argument", "status")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "inventory.deleted", "item", BindingStatus.UNKNOWN));
    assertThat(report.messages().get("inventory.deleted").arguments().get("item").status())
        .isEqualTo(BindingStatus.UNKNOWN);
  }

  @Test
  public void doesNotRequireKnownTermIdsForPureManifestValidation() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "es",
                  "messages": {
                    "inventory.deleted": "{$item :term article=definite}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.not-in-a-pack"]
                    }
                  }
                }
                """));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").arguments().get("item").status())
        .isEqualTo(BindingStatus.OK);
  }

  @Test
  public void reportsMissingAndAmbiguousBindingsBeforeRendering() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "es",
                  "messages": {
                    "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}.",
                    "inventory.moved": "{$item :term article=definite} -> {$place :term article=definite}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.water", "item.fire"]
                    },
                    "inventory.moved": {
                      "item": []
                    }
                  }
                }
                """));

    assertThat(report.summary().messages()).isEqualTo(2);
    assertThat(report.summary().requiredArguments()).isEqualTo(3);
    assertThat(report.diagnostics()).hasSize(3);
    assertThat(report.diagnostics())
        .extracting("messageId", "argument", "status")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "inventory.deleted", "item", BindingStatus.AMBIGUOUS),
            org.assertj.core.groups.Tuple.tuple("inventory.moved", "item", BindingStatus.MISSING),
            org.assertj.core.groups.Tuple.tuple("inventory.moved", "place", BindingStatus.MISSING));
    assertThatThrownBy(() -> TermBindingManifestValidator.requireRenderable(report))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MF2 term binding manifest is not renderable: 3 binding diagnostics")
        .hasMessageContaining("inventory.deleted.item=ambiguous(item.water,item.fire)")
        .hasMessageContaining("inventory.moved.item=missing")
        .hasMessageContaining("inventory.moved.place=missing");
  }

  @Test
  public void reportsUnusedDirectRecordBindings() {
    TermUsageCatalog usageCatalog =
        new TermUsageCatalog(
            TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
            "es",
            Map.of("inventory.deleted", "Has eliminado {$item :term article=definite}."),
            Map.of(
                "inventory.deleted",
                Map.of("item", List.of("item.water"), "place", List.of("place.castle"))));

    TermBindingReport report = validator.validate(usageCatalog);

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).messageId()).isEqualTo("inventory.deleted");
    assertThat(report.diagnostics().get(0).argument()).isEqualTo("place");
    assertThat(report.diagnostics().get(0).status()).isEqualTo(BindingStatus.UNUSED);
  }

  @Test
  public void reportsUnsupportedLocaleRuntimeTermInflectionBeforeMissingBinding() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "ja",
                  "messages": {
                    "inventory.deleted": "Deleted {$item :term number=plural}."
                  },
                  "argumentTerms": {}
                }
                """));

    assertThat(report.summary().requiredArguments()).isEqualTo(1);
    assertThat(report.summary().diagnostics()).isEqualTo(1);
    assertThat(report.messages().get("inventory.deleted").requiredArguments())
        .containsExactly("item");
    assertThat(report.messages().get("inventory.deleted").arguments().get("item").status())
        .isEqualTo(BindingStatus.UNSUPPORTED_LOCALE_RUNTIME_TERM_INFLECTION);
    assertThat(report.diagnostics())
        .extracting("messageId", "argument", "status")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "inventory.deleted",
                "item",
                BindingStatus.UNSUPPORTED_LOCALE_RUNTIME_TERM_INFLECTION));
    assertThatThrownBy(() -> TermBindingManifestValidator.requireRenderable(report))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory.deleted.item=unsupported-locale-runtime-term-inflection")
        .hasMessageContaining("unsupported by current V0 locale runtime");
  }

  @Test
  public void allowsBareTermUsageForUnsupportedRuntimeLocale() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "zh-Hant",
                  "messages": {
                    "inventory.deleted": "Deleted {$item :term}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.book"]
                    }
                  }
                }
                """));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").arguments().get("item").status())
        .isEqualTo(BindingStatus.OK);
  }

  @Test
  public void rejectsDirectRecordBindingsForUnknownMessages() {
    assertThatThrownBy(
            () ->
                new TermUsageCatalog(
                    TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
                    "es",
                    Map.of("inventory.deleted", "Has eliminado {$item :term article=definite}."),
                    Map.of(
                        "inventory.deleted",
                        Map.of("item", List.of("item.water")),
                        "inventory.renamed",
                        Map.of("item", List.of("item.fire")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Argument terms reference unknown message: inventory.renamed");
  }

  @Test
  public void rejectsDirectRecordBindingsWithBlankTermIds() {
    assertThatThrownBy(
            () ->
                new TermUsageCatalog(
                    TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
                    "es",
                    Map.of("inventory.deleted", "Has eliminado {$item :term article=definite}."),
                    Map.of("inventory.deleted", Map.of("item", List.of(" ")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Expected non-blank term id for message inventory.deleted argument item");
  }

  @Test
  public void rejectsDirectRecordBindingsWithDuplicateTermIds() {
    assertThatThrownBy(
            () ->
                new TermUsageCatalog(
                    TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
                    "es",
                    Map.of("inventory.deleted", "Has eliminado {$item :term article=definite}."),
                    Map.of(
                        "inventory.deleted", Map.of("item", List.of("item.water", "item.water")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Duplicate term id for message inventory.deleted argument item: item.water");
  }

  @Test
  public void reportsHindiPronounAgreementBindingFromReferencedArgument() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "hi",
                  "messages": {
                    "inventory.owner": "{$owner :term person=first case=genitive agreeWith=$item}."
                  },
                  "argumentTerms": {
                    "inventory.owner": {
                      "item": ["hi.case.अंगारा"]
                    }
                  }
                }
                """));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.owner").requiredArguments())
        .containsExactly("item");
    assertThat(report.messages().get("inventory.owner").arguments()).doesNotContainKey("owner");
  }

  @Test
  public void reportCollectionsAreImmutable() {
    TermBindingReport report =
        validator.validate(
            usageCatalog(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "messages": {
                    "inventory.deleted": "{$item :term article=definite}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.water"]
                    }
                  }
                }
                """));

    assertThatThrownBy(() -> report.messages().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.diagnostics().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                report
                    .messages()
                    .get("inventory.deleted")
                    .arguments()
                    .get("item")
                    .termIds()
                    .add("item.fire"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private TermUsageCatalog usageCatalog(String json) {
    return new TermRequirementJsonLoader().loadUsageCatalog(json);
  }
}
