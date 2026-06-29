package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Provenance;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.SizeEstimates;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Source;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

public class CompiledTermPackBinaryCodecTest {

  private static final String RESOURCE_DIR = "com/box/l10n/mojito/mf2/inflection";
  private static final Set<String> M2IF_PARITY_FIXTURES =
      Set.of(
          "ar_compiled_approved_explicit_form_pack_fixture",
          "ar_compiled_explicit_form_pack_fixture",
          "da_compiled_genitive_definiteness_pack_fixture",
          "de_compiled_article_case_pack_fixture",
          "es_compiled_article_pack_fixture",
          "hi_compiled_case_form_pack_fixture",
          "he_compiled_construct_form_pack_fixture",
          "it_compiled_article_pack_fixture",
          "ml_compiled_approved_case_form_pack_fixture",
          "ml_compiled_case_form_pack_fixture",
          "pt_compiled_agreement_pack_fixture",
          "ru_compiled_case_form_pack_fixture",
          "sr_compiled_case_form_pack_fixture",
          "sv_compiled_genitive_definiteness_pack_fixture",
          "tr_compiled_explicit_template_auto_pack_fixture",
          "tr_compiled_explicit_template_pack_fixture",
          "tr_compiled_suffix_pack_fixture");
  private static final Set<String> JSON_ONLY_COMPILED_FIXTURES = Set.of();

  private final CompiledTermPackBinaryCodec codec = new CompiledTermPackBinaryCodec();

  @Test
  public void compiledFixtureBinaryParityCoverageIsExplicit() throws IOException {
    Set<String> expectedCompiledFixtures = new TreeSet<>();
    expectedCompiledFixtures.addAll(M2IF_PARITY_FIXTURES);
    expectedCompiledFixtures.addAll(JSON_ONLY_COMPILED_FIXTURES);

    assertThat(compiledFixtureBasenames(".json")).isEqualTo(expectedCompiledFixtures);
    assertThat(compiledFixtureBasenames(".m2if.hex"))
        .isEqualTo(new TreeSet<>(M2IF_PARITY_FIXTURES));
  }

  @Test
  public void roundTripGeneratedSerbianFixtureAndRender() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("sr");
    assertThat(decoded.terms()).hasSize(12);
    assertThat(decoded.formSets()).hasSize(12);
    assertThat(decoded.strings()).contains("sr");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(240);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(2902);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
  }

  @Test
  public void decodePinnedSerbianBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(4217);
    assertThat(sha256(fixture))
        .isEqualTo("44d3b8f2441fc1f883118ec4230ce8b0e10c223392c08a3fb3addfb8a070b9e6");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(1402);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(1492);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(432);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(1924);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(240);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(2164);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(144);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(2308);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(1260);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(3568);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(3568);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(649);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("sr");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
    assertThat(
            renderer.renderMessage(
                "Dodato je {$item :term case=dative count=$count}.",
                Map.of("item", "sr.case.izuzetak"),
                Map.of("count", "2")))
        .isEqualTo("Dodato je izuzecima.");
  }

  @Test
  public void roundTripSerbianCaseFormPackWithSidecarProvenance() {
    CompiledTermPack original =
        new SerbianCaseFormPackJsonLoader()
            .load(readResource("com/box/l10n/mojito/mf2/inflection/sr_case_form_pack_fixture.json"))
            .toCompiledTermPack();

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded =
        codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer(), original.provenance());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("sr");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.provenance().sources().get(0).sha256())
        .isEqualTo("12d5c2e0becdf55bbdd33b14fd816d5f5b78c1f8a60bb0596914be3a34a6f282");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(2902);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
  }

  @Test
  public void roundTripGeneratedHindiFixtureAndRenderCaseForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("hi");
    assertThat(decoded.terms()).hasSize(3);
    assertThat(decoded.formSets()).hasSize(3);
    assertThat(decoded.strings()).contains("hi");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(60);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(629);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "\u092e\u0947\u0902 {$item :term case=oblique count=$count}.",
                Map.of("item", "hi.case.\u0906\u0901\u0916"),
                Map.of("count", "2")))
        .isEqualTo("\u092e\u0947\u0902 \u0906\u0901\u0916\u094b\u0902.");
  }

  @Test
  public void decodePinnedHindiBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1599);
    assertThat(sha256(fixture))
        .isEqualTo("99b2392f63f18508daeabc133dc64389176d678b6350fd955186e809680a03b8");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(353);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(444);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(88);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(532);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(60);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(592);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(36);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(628);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(216);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(844);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(844);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(755);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("hi");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "\u092e\u0947\u0902 {$item :term case=oblique count=$count}.",
                Map.of("item", "hi.case.\u0906\u0901\u0916"),
                Map.of("count", "2")))
        .isEqualTo("\u092e\u0947\u0902 \u0906\u0901\u0916\u094b\u0902.");
  }

  @Test
  public void roundTripGeneratedArabicExplicitFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("ar");
    assertThat(decoded.terms()).hasSize(1);
    assertThat(decoded.formSets()).hasSize(1);
    assertThat(decoded.strings()).contains("ar");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(20);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(844);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "اختيرت {$item :term definiteness=construct case=nominative number=dual}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("اختيرت أُمَّا.");
    assertThat(
            renderer.renderMessage(
                "مع {$item :term definiteness=construct case=genitive}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("مع أُمِّ.");
  }

  @Test
  public void decodePinnedArabicBinaryFixtureWithEmbeddedReviewRequiredPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1823);
    assertThat(sha256(fixture))
        .isEqualTo("1306a96f4d5fc2b6aefa4a4155f99ba07925b72004f4e9f6c38c9effeea668b9");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(656);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(744);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(132);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(876);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(20);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(896);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(12);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(908);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(168);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1076);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1076);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(747);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("ar");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport()).isEqualTo("closed-world-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredTerms()).isEqualTo(1);
    assertThat(decoded.exportPolicy().blockedTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredReasons())
        .containsEntry("missing-form-cell", 1);
    assertThat(
            renderer.renderMessage(
                "مع {$item :term definiteness=construct case=genitive}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("مع أُمِّ.");
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "مع {$item :term definiteness=construct case=genitive number=dual}.",
                    Map.of("item", "ar.explicit.mother"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form construct.genitive.dual for term ar.explicit.mother");
  }

  @Test
  public void roundTripGeneratedApprovedArabicExplicitFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("ar");
    assertThat(decoded.terms()).hasSize(1);
    assertThat(decoded.formSets()).hasSize(1);
    assertThat(decoded.strings()).contains("ar");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(20);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(853);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "اختيرت {$item :term definiteness=construct case=genitive number=dual}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("اختيرت رسالتي.");
    assertThat(
            renderer.renderMessage(
                "حُذفت {$item :term definiteness=indefinite case=genitive number=plural}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("حُذفت رسائل.");
  }

  @Test
  public void decodePinnedArabicApprovedBinaryFixtureWithEmbeddedExportPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1794);
    assertThat(sha256(fixture))
        .isEqualTo("cabd16085dc4b121afd4a0ab7a596990bb8d4f98d3a34b095fa247f802955e36");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(617);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(708);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(112);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(820);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(20);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(840);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(12);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(852);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(216);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1068);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1068);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(726);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("ar");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport()).isEqualTo("closed-world-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isEqualTo(1);
    assertThat(decoded.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(decoded.exportPolicy().blockedTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredReasons()).isEmpty();
    assertThat(
            renderer.renderMessage(
                "اختيرت {$item :term definiteness=construct case=genitive number=dual}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("اختيرت رسالتي.");
  }

  @Test
  public void roundTripGeneratedHebrewConstructFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/he_compiled_construct_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("he");
    assertThat(decoded.terms()).hasSize(1);
    assertThat(decoded.formSets()).hasSize(1);
    assertThat(decoded.strings()).contains("he");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(20);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(189);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "נבחר {$item :term definiteness=construct}.",
                Map.of("item", "he.construct.house"),
                Map.of()))
        .isEqualTo("נבחר בית.");
    assertThat(
            renderer.renderMessage(
                "נבחרו {$item :term definiteness=construct number=plural}.",
                Map.of("item", "he.construct.house"),
                Map.of()))
        .isEqualTo("נבחרו בתי.");
  }

  @Test
  public void decodePinnedHebrewBinaryFixtureWithEmbeddedReviewRequiredPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/he_compiled_construct_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/he_compiled_construct_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1101);
    assertThat(sha256(fixture))
        .isEqualTo("d9d980a9b6fefbed71cf520c4e2d399278bae693ef14e60db2de5a7688c2fa51");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(121);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(212);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(44);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(256);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(20);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(276);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(12);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(288);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS)).isEqualTo(48);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(336);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(336);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(765);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("he");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-construct-state-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredTerms()).isEqualTo(1);
    assertThat(decoded.exportPolicy().blockedTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredReasons())
        .containsEntry("missing-form-cell", 1);
    assertThat(
            renderer.renderMessage(
                "נבחרו {$item :term definiteness=construct number=plural}.",
                Map.of("item", "he.construct.house"),
                Map.of()))
        .isEqualTo("נבחרו בתי.");
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "נבחר {$item :term definiteness=construct number=dual}.",
                    Map.of("item", "he.construct.house"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form construct.dual for term he.construct.house");
  }

  @Test
  public void roundTripGeneratedMalayalamCaseFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("ml");
    assertThat(decoded.terms()).hasSize(1);
    assertThat(decoded.formSets()).hasSize(1);
    assertThat(decoded.strings()).contains("ml");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(20);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(906);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "തിരഞ്ഞെടുത്തത് {$item :term case=genitive}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("തിരഞ്ഞെടുത്തത് ശിഷ്യന്റെ.");
    assertThat(
            renderer.renderMessage(
                "കൂടെ {$item :term case=sociative number=plural}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("കൂടെ ശിഷ്യന്മാരോട്.");
  }

  @Test
  public void roundTripGeneratedApprovedMalayalamCaseFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("ml");
    assertThat(decoded.terms()).hasSize(1);
    assertThat(decoded.formSets()).hasSize(1);
    assertThat(decoded.strings()).contains("ml");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(20);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(1035);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "വിളിച്ചത് {$item :term case=vocative}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("വിളിച്ചത് പിതാവേ.");
    assertThat(
            renderer.renderMessage(
                "കൂടെ {$item :term case=sociative number=plural}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("കൂടെ പിതാക്കന്മാരോട്.");
  }

  @Test
  public void decodePinnedMalayalamBinaryFixtureWithEmbeddedReviewRequiredPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1892);
    assertThat(sha256(fixture))
        .isEqualTo("c3796eb005a9bbecc73709a506b68d69aa9c4d3156327c30a7287381370e7aaa");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(718);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(808);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(128);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(936);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(20);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(956);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(12);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(968);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(168);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1136);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1136);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(756);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("ml");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-multi-case-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredTerms()).isEqualTo(1);
    assertThat(decoded.exportPolicy().blockedTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredReasons())
        .containsEntry("missing-form-cell", 1);
    assertThat(
            renderer.renderMessage(
                "കൂടെ {$item :term case=sociative number=plural}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("കൂടെ ശിഷ്യന്മാരോട്.");
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "വിളി {$item :term case=vocative number=singular}.",
                    Map.of("item", "ml.case.disciple"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form vocative.singular for term ml.case.disciple");
  }

  @Test
  public void decodePinnedApprovedMalayalamBinaryFixtureWithEmbeddedPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(2015);
    assertThat(sha256(fixture))
        .isEqualTo("56f3c79cf45c7a0aff0d5ecef82d035d94d11cce552b0f30c66a525b2124b0fe");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(823);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(912);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(144);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(1056);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(20);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(1076);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(12);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(1088);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(192);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1280);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1280);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(735);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("ml");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-multi-case-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isEqualTo(1);
    assertThat(decoded.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(decoded.exportPolicy().blockedTerms()).isZero();
    assertThat(decoded.exportPolicy().reviewRequiredReasons()).isEmpty();
    assertThat(
            renderer.renderMessage(
                "വിളിച്ചത് {$item :term case=vocative}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("വിളിച്ചത് പിതാവേ.");
    assertThat(
            renderer.renderMessage(
                "തിരഞ്ഞെടുത്തത് {$item :term case=dative number=plural}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("തിരഞ്ഞെടുത്തത് പിതാവുകൾക്കു്.");
  }

  @Test
  public void roundTripGeneratedSwedishFixtureAndRenderGenitiveDefinitenessForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(original.exportPolicy().present()).isTrue();
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(decoded.locale()).isEqualTo("sv");
    assertThat(decoded.terms()).hasSize(2);
    assertThat(decoded.formSets()).hasSize(2);
    assertThat(decoded.strings()).contains("sv");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(40);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(763);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "\u00c4gare: {$item :term definiteness=definite case=genitive number=plural}.",
                Map.of("item", "sv.definiteness.bostad"),
                Map.of()))
        .isEqualTo("\u00c4gare: bost\u00e4dernas.");
    assertThat(
            renderer.renderMessage(
                "Valt {$item :term definiteness=definite case=nominative}.",
                Map.of("item", "sv.definiteness.chassi"),
                Map.of()))
        .isEqualTo("Valt chassit.");
  }

  @Test
  public void decodeReadsEmbeddedExportPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.json"));

    byte[] rowOnlyEncoded = codec.encode(original);
    byte[] encodedWithMetadata = codec.encodeWithEmbeddedMetadata(original);
    CompiledTermPack decoded = codec.decode(encodedWithMetadata);

    assertThat(encodedWithMetadata).hasSizeGreaterThan(rowOnlyEncoded.length);
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-genitive-definiteness-explicit-forms");
    assertThat(decoded.exportPolicy().deferredComposition())
        .containsExactly("article-selection", "definiteness-suffix", "genitive-suffix");
    assertThat(codec.encode(decoded)).containsExactly(rowOnlyEncoded);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(encodedWithMetadata);
  }

  @Test
  public void decodePinnedSwedishBinaryFixtureWithEmbeddedExportPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1821);
    assertThat(sha256(fixture))
        .isEqualTo("766fcf012af7386e09c723002022746594ecf2e195c51b32497f01136e737a7d");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(483);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(572);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(128);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(700);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(40);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(740);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(24);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(764);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(240);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1004);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1004);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(817);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("sv");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-genitive-definiteness-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isEqualTo(2);
    assertThat(
            renderer.renderMessage(
                "\u00c4gare: {$item :term definiteness=definite case=genitive number=plural}.",
                Map.of("item", "sv.definiteness.bostad"),
                Map.of()))
        .isEqualTo("\u00c4gare: bost\u00e4dernas.");
  }

  @Test
  public void roundTripGeneratedDanishFixtureAndRenderGenitiveDefinitenessForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(original.exportPolicy().present()).isTrue();
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(decoded.locale()).isEqualTo("da");
    assertThat(decoded.terms()).hasSize(2);
    assertThat(decoded.formSets()).hasSize(2);
    assertThat(decoded.strings()).contains("da");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(40);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(824);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Ejer: {$item :term definiteness=definite case=genitive number=plural}.",
                Map.of("item", "da.definiteness.franskmand"),
                Map.of()))
        .isEqualTo("Ejer: franskm\u00e6ndenes.");
    assertThat(
            renderer.renderMessage(
                "Valgt {$item :term definiteness=definite case=nominative}.",
                Map.of("item", "da.definiteness.barnebarn"),
                Map.of()))
        .isEqualTo("Valgt barnebarnet.");
  }

  @Test
  public void decodePinnedDanishBinaryFixtureWithEmbeddedExportPolicyMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1881);
    assertThat(sha256(fixture))
        .isEqualTo("2c4149893f8a04ebe36d47a7ac78d46d6bac567b7a3e4ccaec29400e9103d10a");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(544);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(632);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(128);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(760);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(40);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(800);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(24);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(824);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(240);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1064);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1064);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(817);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("da");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy()).isEqualTo(original.exportPolicy());
    assertThat(decoded.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-genitive-definiteness-explicit-forms");
    assertThat(decoded.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(decoded.exportPolicy().automaticExportTerms()).isEqualTo(2);
    assertThat(
            renderer.renderMessage(
                "Ejer: {$item :term definiteness=definite case=genitive number=plural}.",
                Map.of("item", "da.definiteness.franskmand"),
                Map.of()))
        .isEqualTo("Ejer: franskm\u00e6ndenes.");
  }

  @Test
  public void roundTripGeneratedGermanFixtureAndRenderArticleCaseForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/de_compiled_article_case_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("de");
    assertThat(decoded.terms()).hasSize(3);
    assertThat(decoded.formSets()).hasSize(3);
    assertThat(decoded.strings()).contains("de");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(60);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(938);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Mit {$item :term article=definite case=dative count=$count}.",
                Map.of("item", "de.article_case.katze"),
                Map.of("count", "2")))
        .isEqualTo("Mit den Katzen.");
  }

  @Test
  public void decodePinnedGermanBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/de_compiled_article_case_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/de_compiled_article_case_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1811);
    assertThat(sha256(fixture))
        .isEqualTo("f7ad4866b16605ae3a5af0efcb0a3280543e5363f53c6d77aad28faf6d91cdef");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(590);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(680);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(156);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(836);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(60);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(896);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(36);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(932);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(288);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1220);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1220);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(591);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("de");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "Mit {$item :term article=definite case=dative count=$count}.",
                Map.of("item", "de.article_case.katze"),
                Map.of("count", "2")))
        .isEqualTo("Mit den Katzen.");
  }

  @Test
  public void roundTripGeneratedSpanishFixtureAndRenderComposedArticles() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("es");
    assertThat(decoded.terms()).hasSize(3);
    assertThat(decoded.formSets()).hasSize(3);
    assertThat(decoded.strings()).contains("es");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(60);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(248);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el agua.");
    assertThat(
            renderer.renderMessage(
                "Has encontrado {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "2")))
        .isEqualTo("Has encontrado unas abejas.");
  }

  @Test
  public void decodePinnedSpanishBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1027);
    assertThat(sha256(fixture))
        .isEqualTo("e1005077d10897b432bd11cca24b8fae5532119b2c0d0415dc2b07c2d353fbdc");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(116);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(204);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(64);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(268);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(60);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(328);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(36);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(364);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS)).isEqualTo(72);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(436);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(436);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(591);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("es");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el agua.");
    assertThat(
            renderer.renderMessage(
                "Has encontrado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "2")))
        .isEqualTo("Has encontrado las aguas.");
  }

  @Test
  public void roundTripGeneratedItalianFixtureAndRenderComposedArticles() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("it");
    assertThat(decoded.terms()).hasSize(4);
    assertThat(decoded.formSets()).hasSize(4);
    assertThat(decoded.strings()).contains("it");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(80);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(311);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Hai eliminato {$item :term article=definite count=$count}.",
                Map.of("item", "item.gnome"),
                Map.of("count", "1")))
        .isEqualTo("Hai eliminato lo gnomo.");
    assertThat(
            renderer.renderMessage(
                "Hai trovato {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "1")))
        .isEqualTo("Hai trovato un'ape.");
  }

  @Test
  public void decodePinnedItalianBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1119);
    assertThat(sha256(fixture))
        .isEqualTo("75c95f06aef86b51b39ec9fc38a2540169bee20f5b43613a52bcf01c8a8806b9");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(135);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(224);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(80);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(304);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(80);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(384);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(48);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(432);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS)).isEqualTo(96);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(528);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(528);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(591);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("it");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "Hai eliminato {$item :term article=definite count=$count}.",
                Map.of("item", "item.gnome"),
                Map.of("count", "1")))
        .isEqualTo("Hai eliminato lo gnomo.");
    assertThat(
            renderer.renderMessage(
                "Hai eliminato {$item :term article=definite count=$count}.",
                Map.of("item", "item.gnome"),
                Map.of("count", "2")))
        .isEqualTo("Hai eliminato gli gnomi.");
    assertThat(
            renderer.renderMessage(
                "Hai trovato {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "1")))
        .isEqualTo("Hai trovato un'ape.");
  }

  @Test
  public void roundTripGeneratedPortugueseFixtureAndRenderComposedAgreement() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("pt");
    assertThat(decoded.terms()).hasSize(2);
    assertThat(decoded.formSets()).hasSize(2);
    assertThat(decoded.strings()).contains("pt");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(40);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(175);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Dispon\u00edvel {$item :term preposition=em article=definite count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Dispon\u00edvel nas casas.");
    assertThat(
            renderer.renderMessage(
                "Filtrado {$item :term preposition=por article=definite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "2")))
        .isEqualTo("Filtrado pelos campos.");
  }

  @Test
  public void decodePinnedPortugueseBinaryFixtureWithEmbeddedMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(925);
    assertThat(sha256(fixture))
        .isEqualTo("87e6c93ca75f3a8b0bfb75c974a581c375a997ed00483a6affddbe148577a5b0");
    assertThat(new String(fixture, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("M2IF");
    assertThat(getShort(fixture, 4)).isZero();
    assertThat(getShort(fixture, 6)).isZero();
    assertThat(getInt(fixture, 8)).isEqualTo(10);
    assertThat(getInt(fixture, 12)).isEqualTo(11);
    assertThat(getInt(fixture, 16)).isEqualTo(2);
    assertThat(getInt(fixture, 20)).isEqualTo(2);
    assertThat(getInt(fixture, 24)).isEqualTo(4);
    assertThat(getInt(fixture, 28)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(87);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(176);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(48);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(224);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(40);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(264);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(24);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(288);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS)).isEqualTo(48);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(336);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(336);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(589);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("pt");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(
            renderer.renderMessage(
                "Dispon\u00edvel {$item :term preposition=em article=definite count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Dispon\u00edvel nas casas.");
  }

  @Test
  public void roundTripGeneratedTurkishFixtureAndRenderSuffixForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("tr");
    assertThat(decoded.terms()).hasSize(5);
    assertThat(decoded.formSets()).hasSize(5);
    assertThat(decoded.strings()).contains("tr");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(100);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(275);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Silindi evleri.");
    assertThat(
            renderer.renderMessage(
                "Al\u0131nd\u0131 {$item :term case=ablative count=$count}.",
                Map.of("item", "item.park"),
                Map.of("count", "1")))
        .isEqualTo("Al\u0131nd\u0131 parktan.");
  }

  @Test
  public void decodePinnedTurkishBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1269);
    assertThat(sha256(fixture))
        .isEqualTo("71e1c3ab6d03392448dc0baf81b9e816b6ab420c39161a8d57be639f0aa9b785");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(115);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(204);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(68);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(272);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(100);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(372);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(60);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(432);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS)).isEqualTo(60);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(492);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(492);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(777);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("tr");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Silindi evleri.");
    assertThat(
            renderer.renderMessage(
                "Al\u0131nd\u0131 {$item :term case=ablative count=$count}.",
                Map.of("item", "item.park"),
                Map.of("count", "1")))
        .isEqualTo("Al\u0131nd\u0131 parktan.");
  }

  @Test
  public void roundTripGeneratedTurkishExplicitTemplateFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("tr");
    assertThat(decoded.terms()).hasSize(4);
    assertThat(decoded.formSets()).hasSize(4);
    assertThat(decoded.strings()).contains("tr");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(80);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(650);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.\u00e7akmak"),
                Map.of()))
        .isEqualTo("Silindi \u00e7akma\u011f\u0131.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.amel"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi a\u02bcmal.");
  }

  @Test
  public void decodePinnedTurkishExplicitTemplateBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(1714);
    assertThat(sha256(fixture))
        .isEqualTo("53f2d51cf36aedeea768cdb55acbc1864900ffe2c892e9b6b3b2af66ec3f0f2a");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(354);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(444);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(120);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(564);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(80);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(644);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(48);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(692);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(216);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isEqualTo(908);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(908);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(806);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("tr");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.\u00e7akmak"),
                Map.of()))
        .isEqualTo("Silindi \u00e7akma\u011f\u0131.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.amel"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi a\u02bcmal.");
  }

  @Test
  public void roundTripGeneratedTurkishAutomaticExplicitTemplateFixtureAndRenderForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_auto_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("tr");
    assertThat(decoded.terms()).hasSize(8);
    assertThat(decoded.formSets()).hasSize(8);
    assertThat(decoded.strings()).contains("tr");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(160);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(986);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.baklava"),
                Map.of()))
        .isEqualTo("Silindi baklavay\u0131.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.cetvel"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi ced\u00e2vil.");
  }

  @Test
  public void decodePinnedTurkishAutomaticExplicitTemplateBinaryFixtureWithEmbeddedProvenance() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_auto_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_auto_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(2134);
    assertThat(sha256(fixture))
        .isEqualTo("652079f23fd47c6202f7e6de86748fd44c4f28dbc5b7e91ab88e468b4baf2840");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(454);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(544);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(156);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(700);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(160);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(860);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(96);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(956);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(372);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1328);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1328);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(806);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("tr");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(decoded.terms()).hasSize(8);
    assertThat(decoded.formSets()).hasSize(8);
    assertThat(decoded.formSets().stream().flatMap(formSet -> formSet.forms().stream()))
        .hasSize(31);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.baklava"),
                Map.of()))
        .isEqualTo("Silindi baklavay\u0131.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.cetvel"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi ced\u00e2vil.");
  }

  @Test
  public void roundTripGeneratedRussianFixtureAndRenderCaseForms() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.json"));

    byte[] encoded = codec.encode(original);
    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("ru");
    assertThat(decoded.terms()).hasSize(3);
    assertThat(decoded.formSets()).hasSize(3);
    assertThat(decoded.strings()).contains("ru");
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(60);
    assertThat(decoded.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(1287);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
    assertThat(
            renderer.renderMessage(
                "\u0423\u0434\u0430\u043b\u0435\u043d\u043e {$item :term case=accusative count=$count}.",
                Map.of("item", "ru.case.\u043a\u043e\u0448\u043a\u0430"),
                Map.of("count", "2")))
        .isEqualTo("\u0423\u0434\u0430\u043b\u0435\u043d\u043e \u043a\u043e\u0448\u0435\u043a.");
    assertThat(
            renderer.renderMessage(
                "\u0412 {$item :term case=prepositional count=$count}.",
                Map.of("item", "ru.case.\u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u043e"),
                Map.of("count", "1")))
        .isEqualTo("\u0412 \u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u0435.");
  }

  @Test
  public void decodePinnedRussianBinaryFixtureWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.json"));
    byte[] fixture =
        bytesFromHex(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.m2if.hex"));

    CompiledTermPack decoded = codec.decode(fixture);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(fixture).hasSize(2248);
    assertThat(sha256(fixture))
        .isEqualTo("c98e970f2636d387501cf00e6e4303a4b56a3e3ddd5cf05aa462bdb6c630898a");
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(88);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRINGS)).isEqualTo(795);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(884);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_STRING_OFFSETS))
        .isEqualTo(184);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(1068);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_TERMS)).isEqualTo(60);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS))
        .isEqualTo(1128);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_SETS)).isEqualTo(36);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(1164);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS))
        .isEqualTo(432);
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS))
        .isEqualTo(1596);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_BINDINGS)).isZero();
    assertThat(sectionOffset(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA))
        .isEqualTo(1596);
    assertThat(sectionLength(fixture, CompiledTermPackBinaryCodec.SECTION_METADATA)).isEqualTo(652);
    assertThat(codec.encodeWithEmbeddedMetadata(original)).containsExactly(fixture);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(fixture);
    assertThat(decoded.locale()).isEqualTo("ru");
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(decoded.exportPolicy().present()).isFalse();
    assertThat(
            renderer.renderMessage(
                "\u0423\u0434\u0430\u043b\u0435\u043d\u043e {$item :term case=accusative count=$count}.",
                Map.of("item", "ru.case.\u043a\u043e\u0448\u043a\u0430"),
                Map.of("count", "2")))
        .isEqualTo("\u0423\u0434\u0430\u043b\u0435\u043d\u043e \u043a\u043e\u0448\u0435\u043a.");
    assertThat(
            renderer.renderMessage(
                "\u0412 {$item :term case=prepositional count=$count}.",
                Map.of("item", "ru.case.\u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u043e"),
                Map.of("count", "1")))
        .isEqualTo("\u0412 \u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u0435.");
  }

  @Test
  public void roundTripSimpleFrenchPackWithoutAppendingLocaleString() {
    CompiledTermPack original =
        new CompiledTermPack(
            CompiledTermPack.SCHEMA,
            "fr",
            List.of("fr", "bare.singular", "term.hello", "bonjour", "hello"),
            List.of(new TermRow(2, 3, 1, 4, 0)),
            List.of(new FormSet(2, List.of(new FormRow(1, 3, false)))),
            Provenance.empty(),
            SizeEstimates.empty());

    CompiledTermPack decoded = codec.decode(codec.encode(original));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(decoded);

    assertThat(decoded.locale()).isEqualTo("fr");
    assertThat(decoded.strings()).isEqualTo(original.strings());
    assertThat(
            renderer.renderMessage(
                "Message: {$item :term}.", Map.of("item", "term.hello"), Map.of()))
        .isEqualTo("Message: bonjour.");
  }

  @Test
  public void decodeCanAttachSidecarProvenance() {
    byte[] encoded = codec.encode(simplePack());
    Provenance provenance = unicodeFrenchProvenance();

    CompiledTermPack decoded = codec.decode(ByteBuffer.wrap(encoded), provenance);

    assertThat(decoded.provenance()).isEqualTo(provenance);
    assertThat(codec.encode(decoded)).containsExactly(encoded);
  }

  @Test
  public void decodeReadsEmbeddedProvenanceMetadata() {
    CompiledTermPack original = simplePackWithProvenance();

    byte[] rowOnlyEncoded = codec.encode(original);
    byte[] encodedWithMetadata = codec.encodeWithEmbeddedMetadata(original);
    CompiledTermPack decoded = codec.decode(encodedWithMetadata);

    assertThat(encodedWithMetadata).hasSizeGreaterThan(rowOnlyEncoded.length);
    assertThat(decoded.provenance()).isEqualTo(original.provenance());
    assertThat(codec.encode(decoded)).containsExactly(rowOnlyEncoded);
    assertThat(codec.encodeWithEmbeddedMetadata(decoded)).containsExactly(encodedWithMetadata);
  }

  @Test
  public void decodeAcceptsMatchingSidecarWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original = simplePackWithProvenance();

    CompiledTermPack decoded =
        codec.decode(codec.encodeWithEmbeddedMetadata(original), unicodeFrenchProvenance());

    assertThat(decoded.provenance()).isEqualTo(original.provenance());
  }

  @Test
  public void rejectMismatchedSidecarWithEmbeddedProvenanceMetadata() {
    CompiledTermPack original = simplePackWithProvenance();
    Provenance sidecar =
        new Provenance(
            "Unicode-3.0",
            "dev-docs/experiments/mf2-inflection/other_generator.py",
            List.of("unicode-fr"),
            List.of(
                new Source(
                    "inflection/resources/org/unicode/inflection/dictionary/dictionary_fr.lst",
                    17_349_006L,
                    "21e1a3d385db927d10d175a322ad72f55cac455ab6c960564c90fe6ac23fc53b",
                    true)));

    assertThatThrownBy(() -> codec.decode(codec.encodeWithEmbeddedMetadata(original), sidecar))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provenance sidecar does not match embedded metadata");
  }

  @Test
  public void rejectUnsupportedEmbeddedMetadataSchema() {
    byte[] encoded = codec.encodeWithEmbeddedMetadata(simplePackWithProvenance());
    int metadataOffset = sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_METADATA);
    int metadataLength =
        getInt(encoded, sectionLengthOffset(CompiledTermPackBinaryCodec.SECTION_METADATA));
    String metadata =
        new String(encoded, metadataOffset, metadataLength, StandardCharsets.UTF_8)
            .replace(
                CompiledTermPackBinaryCodec.METADATA_SCHEMA,
                CompiledTermPackBinaryCodec.METADATA_SCHEMA.replace("mojito", "xojito"));
    byte[] metadataBytes = metadata.getBytes(StandardCharsets.UTF_8);
    assertThat(metadataBytes).hasSize(metadataLength);
    System.arraycopy(metadataBytes, 0, encoded, metadataOffset, metadataLength);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected compiled term pack metadata schema");
  }

  @Test
  public void rejectInvalidEmbeddedMetadataJson() {
    byte[] encoded = codec.encodeWithEmbeddedMetadata(simplePackWithProvenance());
    int metadataOffset = sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_METADATA);
    int metadataLength =
        getInt(encoded, sectionLengthOffset(CompiledTermPackBinaryCodec.SECTION_METADATA));
    Arrays.fill(encoded, metadataOffset, metadataOffset + metadataLength, (byte) '[');

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid compiled term pack metadata JSON");
  }

  @Test
  public void rejectNullSidecarProvenance() {
    byte[] encoded = codec.encode(simplePack());

    assertThatThrownBy(() -> codec.decode(encoded, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("provenance");
    assertThatThrownBy(() -> codec.decode(ByteBuffer.wrap(encoded), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("provenance");
  }

  @Test
  public void rejectInvalidMagic() {
    byte[] encoded = codec.encode(simplePack());
    encoded[0] = 'X';

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid compiled term pack magic");
  }

  @Test
  public void rejectUnsupportedVersion() {
    byte[] encoded = codec.encode(simplePack());
    putShort(encoded, 4, 1);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported compiled term pack version");
  }

  @Test
  public void rejectUnsupportedFlags() {
    byte[] encoded = codec.encode(simplePack());
    putShort(encoded, 6, 1);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported compiled term pack flags");
  }

  @Test
  public void decodeUsesByteBufferRemainingSlice() {
    byte[] encoded = codec.encode(simplePack());
    byte[] wrapped = new byte[encoded.length + 9];
    System.arraycopy(encoded, 0, wrapped, 5, encoded.length);
    ByteBuffer buffer = ByteBuffer.wrap(wrapped).position(5).limit(5 + encoded.length);

    CompiledTermPack decoded = codec.decode(buffer);

    assertThat(decoded.locale()).isEqualTo("fr");
    assertThat(codec.encode(decoded)).containsExactly(encoded);
  }

  @Test
  public void rejectTrailingBytes() {
    byte[] encoded = codec.encode(simplePack());
    byte[] encodedWithTrailingBytes = Arrays.copyOf(encoded, encoded.length + 1);

    assertThatThrownBy(() -> codec.decode(encodedWithTrailingBytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled term pack contains trailing bytes");
  }

  @Test
  public void rejectUnexpectedSectionLength() {
    byte[] encoded = codec.encode(simplePack());
    int termsLengthOffset = sectionLengthOffset(CompiledTermPackBinaryCodec.SECTION_TERMS);
    putInt(encoded, termsLengthOffset, getInt(encoded, termsLengthOffset) - 1);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected section length for terms");
  }

  @Test
  public void rejectOutOfBoundsSection() {
    byte[] encoded = codec.encode(simplePack());
    putInt(
        encoded, sectionLengthOffset(CompiledTermPackBinaryCodec.SECTION_STRINGS), encoded.length);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Section out of bounds: strings");
  }

  @Test
  public void rejectMisalignedSection() {
    byte[] encoded = codec.encode(simplePack());
    int stringsOffset = sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_STRINGS);
    putInt(
        encoded,
        sectionOffsetOffset(CompiledTermPackBinaryCodec.SECTION_STRINGS),
        stringsOffset + 1);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Section offset is not 4-byte aligned: strings");
  }

  @Test
  public void rejectOverlappingSections() {
    byte[] encoded = codec.encode(simplePack());
    putInt(
        encoded,
        sectionOffsetOffset(CompiledTermPackBinaryCodec.SECTION_FORM_ROWS),
        sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_FORM_SETS));

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Section overlaps previous section: formRows");
  }

  @Test
  public void rejectUnsupportedFormRowFlags() {
    byte[] encoded = codec.encode(simplePack());
    putInt(encoded, sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_FORM_ROWS) + 8, 2);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported form row flags");
  }

  @Test
  public void rejectReservedBindingRows() {
    byte[] encoded = codec.encode(simplePack());
    byte[] encodedWithBindingRow =
        Arrays.copyOf(encoded, encoded.length + CompiledTermPackBinaryCodec.BINDING_ROW_BYTES);
    putInt(encodedWithBindingRow, 28, 1);
    putInt(
        encodedWithBindingRow,
        sectionLengthOffset(CompiledTermPackBinaryCodec.SECTION_BINDINGS),
        CompiledTermPackBinaryCodec.BINDING_ROW_BYTES);

    assertThatThrownBy(() -> codec.decode(encodedWithBindingRow))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled term pack bindings section is reserved and must be empty");
  }

  @Test
  public void rejectOutOfBoundsStringIndexInTermRow() {
    byte[] encoded = codec.encode(simplePack());
    putInt(encoded, sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_TERMS), 99);

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String index out of bounds for term.id");
  }

  @Test
  public void rejectInvalidUtf8String() {
    byte[] encoded = codec.encode(simplePack());
    int stringsOffset = sectionOffset(encoded, CompiledTermPackBinaryCodec.SECTION_STRINGS);
    encoded[stringsOffset] = (byte) 0xff;

    assertThatThrownBy(() -> codec.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid UTF-8 string");
  }

  private CompiledTermPack simplePack() {
    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "fr",
        List.of("bare.singular", "bonjour", "term.hello"),
        List.of(new TermRow(2, 1, 1, null, 0)),
        List.of(new FormSet(2, List.of(new FormRow(0, 1, false)))),
        Provenance.empty(),
        SizeEstimates.empty());
  }

  private CompiledTermPack simplePackWithProvenance() {
    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "fr",
        List.of("bare.singular", "bonjour", "term.hello"),
        List.of(new TermRow(2, 1, 1, null, 0)),
        List.of(new FormSet(2, List.of(new FormRow(0, 1, false)))),
        unicodeFrenchProvenance(),
        SizeEstimates.empty());
  }

  private Provenance unicodeFrenchProvenance() {
    return new Provenance(
        "Unicode-3.0",
        "dev-docs/experiments/mf2-inflection/mf2_term_pack_compile.py",
        List.of("unicode-fr"),
        List.of(
            new Source(
                "inflection/resources/org/unicode/inflection/dictionary/dictionary_fr.lst",
                17_349_006L,
                "21e1a3d385db927d10d175a322ad72f55cac455ab6c960564c90fe6ac23fc53b",
                true)));
  }

  private int sectionOffset(byte[] payload, int section) {
    return getInt(
        payload,
        CompiledTermPackBinaryCodec.SECTION_DIRECTORY_OFFSET
            + section * CompiledTermPackBinaryCodec.SECTION_DIRECTORY_ENTRY_BYTES);
  }

  private int sectionOffsetOffset(int section) {
    return CompiledTermPackBinaryCodec.SECTION_DIRECTORY_OFFSET
        + section * CompiledTermPackBinaryCodec.SECTION_DIRECTORY_ENTRY_BYTES;
  }

  private int sectionLengthOffset(int section) {
    return CompiledTermPackBinaryCodec.SECTION_DIRECTORY_OFFSET
        + section * CompiledTermPackBinaryCodec.SECTION_DIRECTORY_ENTRY_BYTES
        + 4;
  }

  private int sectionLength(byte[] payload, int section) {
    return getInt(payload, sectionLengthOffset(section));
  }

  private int getInt(byte[] payload, int offset) {
    return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
  }

  private int getShort(byte[] payload, int offset) {
    return Short.toUnsignedInt(
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort(offset));
  }

  private void putInt(byte[] payload, int offset, int value) {
    ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
  }

  private void putShort(byte[] payload, int offset, int value) {
    ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
  }

  private byte[] bytesFromHex(String hex) {
    return HexFormat.of().parseHex(hex.replaceAll("\\s+", ""));
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(Objects.requireNonNull(inputStream).readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Set<String> compiledFixtureBasenames(String suffix) throws IOException {
    try (Stream<Path> resources = Files.list(testResourcePath())) {
      return resources
          .map(path -> path.getFileName().toString())
          .filter(fileName -> fileName.contains("_compiled_"))
          .filter(fileName -> fileName.endsWith("_pack_fixture" + suffix))
          .map(fileName -> fileName.substring(0, fileName.length() - suffix.length()))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private Path testResourcePath() {
    Path moduleRelative = Path.of("src/test/resources", RESOURCE_DIR);
    if (Files.isDirectory(moduleRelative)) {
      return moduleRelative;
    }
    return Path.of("common/src/test/resources", RESOURCE_DIR);
  }
}
