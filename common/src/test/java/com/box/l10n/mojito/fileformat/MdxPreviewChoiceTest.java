package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MdxPreviewChoiceTest {
  private static final String IMPORTS =
      "import Individual from './audience/Individual.mdx';\n"
          + "import Team from '../shared/Team.mdx';\n\n";
  private static final String CHOICE =
      "<PreviewChoice>\n<Individual />\n<Team />\n</PreviewChoice>\n";

  @Test
  public void preservesChoiceTemplateBytesWhileLocalizingOrdinarySurroundingBlocks() {
    String source =
        (IMPORTS
                + "{/* mojito-id: title */}\n# Welcome\n\n"
                + CHOICE
                + "\n{/* mojito-id: body */}\nChoose the section that fits.\n")
            .replace("\n", "\r\n");
    var catalog = LocalizationFileConverters.parse(LocalizationFileFormat.MDX, bytes(source));
    assertEquals(List.of("title", "body"), List.copyOf(catalog.messages().keySet()));
    var skeleton =
        LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(source));
    assertArrayEquals(bytes(source), LocalizationFileConverters.renderSkeleton(skeleton, Map.of()));
    assertArrayEquals(
        bytes(
            source
                .replace("Welcome", "Bienvenue")
                .replace("Choose the section that fits.", "Choisissez la section adaptée.")),
        LocalizationFileConverters.renderSkeleton(
            skeleton, Map.of("title", "Bienvenue", "body", "Choisissez la section adaptée.")));
    var components =
        MdxDocument.parse(bytes(source)).blocks().stream()
            .filter(block -> "component".equals(block.type()))
            .toList();
    assertEquals(4, components.size());
    components.forEach(block -> assertFalse(block.translatable()));
  }

  @Test
  public void acceptsTwoThroughEightStaticAlternativesAndIndependentChoices() {
    for (int size : List.of(2, 8)) {
      String choice = "<PreviewChoice>\n" + "<Individual />\n".repeat(size) + "</PreviewChoice>\n";
      assertEquals(
          2 * (size + 2), MdxDocument.parse(bytes(IMPORTS + choice + choice)).blocks().size());
    }
  }

  @Test
  public void allowsAChoiceInsideAnOpaqueWrapperAndLeavesFencedExamplesLiteral() {
    String source = IMPORTS + "<Frame>\n" + CHOICE + "</Frame>\n\n```mdx\n<PreviewChoice />\n```\n";
    var document = MdxDocument.parse(bytes(source));
    assertEquals("code", document.blocks().getLast().type());
    assertArrayEquals(
        bytes(source),
        LocalizationFileConverters.renderSkeleton(
            LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(source)),
            Map.of()));
  }

  @Test
  public void rejectsMalformedChoicesRatherThanExtractingOnlyPartOfThem() {
    for (String choice :
        List.of(
            "<PreviewChoice />\n",
            "<PreviewChoice>\n</PreviewChoice>\n",
            "<PreviewChoice>\n<Individual />\n</PreviewChoice>\n",
            "<PreviewChoice>\n" + "<Individual />\n".repeat(9) + "</PreviewChoice>\n",
            "<PreviewChoice>\nSome prose\n<Individual />\n<Team />\n</PreviewChoice>\n",
            "<PreviewChoice>\n# Heading\n<Individual />\n<Team />\n</PreviewChoice>\n",
            "<PreviewChoice>\n```mdx\n<Individual />\n```\n<Team />\n</PreviewChoice>\n",
            "<PreviewChoice>\n<Individual>\n</Individual>\n<Team />\n</PreviewChoice>\n",
            "<PreviewChoice>\n" + CHOICE + "<Team />\n</PreviewChoice>\n",
            "<PreviewChoice>\n<Unknown />\n<Team />\n</PreviewChoice>\n",
            "<PreviewChoice>\n<Individual.Named />\n<Team />\n</PreviewChoice>\n")) {
      assertError("INVALID_MDX", IMPORTS + choice);
    }
    for (String declaration :
        List.of(
            "import { Individual } from './Individual.mdx';\n",
            "import Individual from './Individual.tsx';\n",
            "import Individual from 'package/Individual.mdx';\n",
            "import Individual from '/Individual.mdx';\n",
            "import Individual from 'https://example.com/Individual.mdx';\n",
            "import Individual from './Individual%2fmore.mdx';\n",
            "import Individual from './Individual?x.mdx';\n")) {
      assertError("INVALID_MDX", declaration + "import Team from './Team.mdx';\n" + CHOICE);
    }
  }

  @Test
  public void reservesTheBuiltInBindingAndRejectsPropsOrExpressions() {
    for (String declaration :
        List.of(
            "import PreviewChoice from './custom.mdx';\n",
            "import { PreviewChoice } from './custom.mdx';\n",
            "import { Wrapper as PreviewChoice } from './custom.mdx';\n")) {
      assertError("INVALID_MDX", declaration + IMPORTS + CHOICE);
    }
    for (String choice :
        List.of(
            CHOICE.replace("<PreviewChoice>", "<PreviewChoice selected=\"team\">"),
            CHOICE.replace("<Team />", "<Team audience={audience} />"),
            CHOICE.replace("<Team />", "{audience === 'team' && <Team />}"))) {
      assertError("UNSUPPORTED_MDX", IMPORTS + choice);
    }
  }

  private static byte[] bytes(String source) {
    return source.getBytes(StandardCharsets.UTF_8);
  }

  private static void assertError(String code, String source) {
    var error =
        assertThrows(LocalizationParseException.class, () -> MdxDocument.parse(bytes(source)));
    assertEquals(source, code, error.code());
  }
}
