package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MdxSourceFormatTest {

  private static final String SOURCE =
      "import Callout from './Callout'\r\n"
          + "\r\n{/* mojito-id: page.title */}\r\n# Hello **world**\r\n"
          + "\r\nA paragraph with [a link](/docs)\r\nand a second line.\r\n"
          + "\r\n<Callout>\r\n\r\n{/* mojito-id: page.note */}\r\nUseful content.\r\n\r\n</Callout>\r\n"
          + "\r\n3. First item\r\n   - Nested item\r\n4. Last item\r\n"
          + "\r\n> A quotation\r\n\r\n---\r\n"
          + "\r\n```jsx\r\n<Component value={value} />\r\n```\r\n";

  @Test
  public void exposesDefaultImportAliasesWithoutResolvingNamedImports() {
    MdxDocument document =
        MdxDocument.parse(
            bytes(
                "import Hero from './modules/hero.mdx';\n"
                    + "import Banner from \"../shared/banner.mdx\"\n"
                    + "import { Callout as Notice, Divider, default as Footer, } from './ui';\n\n"
                    + "<Hero />\n<Banner />\n<Notice>\nText\n</Notice>\n<Divider />\n<Footer />\n"));
    assertEquals(
        Map.of("Hero", "./modules/hero.mdx", "Banner", "../shared/banner.mdx"), document.imports());
    assertEquals(List.of("Hero", "Banner"), new ArrayList<>(document.imports().keySet()));
    assertFalse(document.imports().containsKey("Notice"));
    assertFalse(document.imports().containsKey("Footer"));
    try {
      document.imports().put("Injected", "./other.mdx");
      fail("Import metadata must be immutable");
    } catch (UnsupportedOperationException expected) {
      // A renderer cannot mutate parser-owned import identities.
    }
  }

  @Test
  public void componentReferencesDistinguishNestedTagsFromCodeAndProse() {
    MdxDocument document =
        MdxDocument.parse(
            bytes(
                "import Hero from './hero.mdx'\n\n<Frame>\n<Hero />\n<Frame.Note>\n"
                    + "Use `<Hero />` literally.\n</Frame.Note>\n</Frame>\n\n"
                    + "```mdx\nimport Hidden from './hidden.mdx'\n<Hidden />\n```\n"));
    assertEquals(
        new MdxDocument.ComponentReference("Frame", false, false),
        MdxDocument.componentReference(document.blocks().get(0)));
    assertEquals(
        new MdxDocument.ComponentReference("Hero", false, true),
        MdxDocument.componentReference(document.blocks().get(1)));
    assertEquals(
        new MdxDocument.ComponentReference("Frame.Note", false, false),
        MdxDocument.componentReference(document.blocks().get(2)));
    assertNull(MdxDocument.componentReference(document.blocks().get(3)));
    assertEquals(
        new MdxDocument.ComponentReference("Frame.Note", true, false),
        MdxDocument.componentReference(document.blocks().get(4)));
    assertEquals(
        new MdxDocument.ComponentReference("Frame", true, false),
        MdxDocument.componentReference(document.blocks().get(5)));
    assertNull(MdxDocument.componentReference(document.blocks().get(6)));
    assertEquals(Map.of("Hero", "./hero.mdx"), document.imports());
    assertEquals(
        List.of(0, 1, 1, 0, 1, 0, 0),
        document.blocks().stream().map(MdxDocument.Block::depth).toList());
  }

  @Test
  public void componentsMayShareAPrefixWithTheReservedPreviewMessageName() {
    MdxDocument document =
        MdxDocument.parse(
            bytes("import PreviewMessageCard from './card.mdx';\n\n<PreviewMessageCard />"));
    assertEquals(
        new MdxDocument.ComponentReference("PreviewMessageCard", false, true),
        MdxDocument.componentReference(document.blocks().get(0)));
  }

  @Test
  public void moduleMetadataDoesNotChangeExtractionOrSourceTemplateBytes() {
    String source =
        "import Hero from './modules/hero.mdx';\r\n"
            + "import { Frame as Wrapper } from './components';\r\n\r\n"
            + "<Wrapper>\r\n<Hero />\r\n{/* mojito-id: body */}\r\n"
            + "A complete paragraph.\r\n</Wrapper>\r\n";
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.MDX, bytes(source));
    assertEquals(List.of("body"), new ArrayList<>(catalog.messages().keySet()));
    assertEquals("A complete paragraph.", catalog.messages().get("body").defaultMessage());
    LocalizationSourceSkeleton skeleton =
        LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(source));
    assertArrayEquals(bytes(source), LocalizationFileConverters.renderSkeleton(skeleton, Map.of()));
    assertArrayEquals(
        bytes(source.replace("A complete paragraph.", "Un paragraphe entier.")),
        LocalizationFileConverters.renderSkeleton(
            skeleton, Map.of("body", "Un paragraphe entier.")));
  }

  @Test
  public void duplicateDefaultAndNamedLocalBindingsFailInsteadOfChoosingAnAlias() {
    for (String declarations :
        List.of(
            "import Hero from './one.mdx'\nimport Hero from './two.mdx'",
            "import Hero from './one.mdx'\nimport { Hero } from './ui'",
            "import { Card as Hero } from './ui'\nimport Hero from './one.mdx'",
            "import { Card as Hero, Notice as Hero } from './ui'",
            "import { Hero } from './one'\nimport { Hero } from './two'")) {
      try {
        MdxDocument.parse(bytes(declarations + "\n\n<Hero />"));
        fail("Duplicate import binding must fail: " + declarations);
      } catch (LocalizationParseException expected) {
        assertEquals("INVALID_MDX", expected.code());
        assertTrue(expected.getMessage().contains("Duplicate import binding: Hero"));
      }
    }
  }

  @Test
  public void malformedOrEscapedModuleImportsFailClosed() {
    for (String declaration :
        List.of(
            "import { Card Notice } from './ui'",
            "import { Card,, Notice } from './ui'",
            "import { Card as } from './ui'",
            "import Hero from './hero\\u002emdx'")) {
      assertError("UNSUPPORTED_MDX", () -> MdxDocument.parse(bytes(declaration + "\n\n<Hero />")));
    }
  }

  @Test
  public void extractsWholeBlocksInDocumentOrderAndRetainsTemplateContext() {
    MdxDocument document = MdxDocument.parse(bytes(SOURCE));
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.MDX, bytes(SOURCE));

    assertEquals(7, catalog.messages().size());
    assertEquals("Hello **world**", catalog.messages().get("page.title").defaultMessage());
    assertEquals("heading", catalog.messages().get("page.title").metadata().get("documentType"));
    assertEquals(1, catalog.messages().get("page.title").metadata().get("documentDepth"));
    assertEquals("paragraph", document.blocks().get(1).type());
    assertEquals(
        "A paragraph with [a link](/docs)\r\nand a second line.",
        document.blocks().get(1).source());
    assertEquals("component", document.blocks().get(2).type());
    assertFalse(document.blocks().get(2).translatable());
    assertEquals("Useful content.", document.blocks().get(3).source());
    assertEquals("3. ", document.blocks().get(5).marker());
    assertEquals(3, document.blocks().get(6).depth());
    assertEquals("thematic-break", document.blocks().get(9).type());
    assertEquals("code", document.blocks().get(10).type());
    assertTrue(document.blocks().get(10).source().contains("value={value}"));
  }

  @Test
  public void preservesOriginalBytesAndLocalizedTemplateIncludingUtf16AndEmoji() {
    String source = SOURCE.replace("Hello **world**", "Hello **world** 🌎");
    for (String encodingName : List.of("UTF-8", "UTF-8-BOM", "UTF-16LE-BOM", "UTF-16BE-BOM")) {
      SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(encodingName);
      byte[] original = encoding.encode(source);
      LocalizationSourceSkeleton skeleton =
          LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, original);
      assertArrayEquals(original, LocalizationFileConverters.renderSkeleton(skeleton, Map.of()));
      assertArrayEquals(
          encoding.encode(
              source
                  .replace("Hello **world** 🌎", "Bonjour **le monde** 🌎")
                  .replace("Useful content.", "Contenu utile.")),
          LocalizationFileConverters.renderSkeleton(
              skeleton,
              Map.of("page.title", "Bonjour **le monde** 🌎", "page.note", "Contenu utile.")));
    }
  }

  @Test
  public void normalWorkflowExtractionAndOutputUseSameBlockIdentities() {
    LocalizationCatalog catalog =
        LocalizationFileConverters.parseForMojito(
            LocalizationFileFormat.MDX, bytes(SOURCE), List.of());
    assertEquals("Useful content.", catalog.messages().get("page.note").defaultMessage());
    assertEquals(
        SOURCE.replace("Useful content.", "Note traduite."),
        new String(
            LocalizationFileConverters.localizeForMojito(
                LocalizationFileFormat.MDX,
                bytes(SOURCE),
                Map.of("page.note", "Note traduite."),
                List.of(),
                false),
            StandardCharsets.UTF_8));
    assertError(
        "UNSUPPORTED_OUTPUT_POLICY",
        () ->
            LocalizationFileConverters.localizeForMojito(
                LocalizationFileFormat.MDX, bytes(SOURCE), Map.of(), List.of(), true));
  }

  @Test
  public void contentIdentitiesSurviveUnrelatedInsertionAndReordering() {
    MdxDocument original = MdxDocument.parse(bytes("First paragraph.\n\nSecond paragraph.\n"));
    MdxDocument moved =
        MdxDocument.parse(bytes("New paragraph.\n\nSecond paragraph.\n\nFirst paragraph.\n"));
    assertEquals(original.blocks().get(0).id(), moved.blocks().get(2).id());
    assertEquals(original.blocks().get(1).id(), moved.blocks().get(1).id());
    MdxDocument duplicate = MdxDocument.parse(bytes("Same.\n\nSame.\n"));
    assertEquals(duplicate.blocks().get(0).id() + ".2", duplicate.blocks().get(1).id());
  }

  @Test
  public void explicitIdentitySurvivesSourceRevisionsAndRejectsAmbiguity() {
    MdxDocument old = MdxDocument.parse(bytes("{/* mojito-id: intro */}\nOld content."));
    MdxDocument changed = MdxDocument.parse(bytes("{/* mojito-id: intro */}\nChanged content."));
    assertEquals(old.blocks().get(0).id(), changed.blocks().get(0).id());
    assertError(
        "DUPLICATE_MESSAGE_ID",
        () ->
            MdxDocument.parse(
                bytes("{/* mojito-id: x */}\nFirst\n\n{/* mojito-id: x */}\nSecond")));
    assertError("INVALID_MDX", () -> MdxDocument.parse(bytes("{/* mojito-id: unused */}")));
    assertError(
        "INVALID_MDX", () -> MdxDocument.parse(bytes("{/* mojito-id: unused */}\n<Callout />")));
  }

  @Test
  public void rejectsUnsupportedContentInsteadOfExtractingPartialPages() {
    for (String unsupported :
        List.of(
            "---\ntitle: Hidden title\n---\nVisible paragraph.",
            "Text with {javascript}.",
            "Text with <strong>HTML</strong>.",
            "<Callout title=\"Unextracted title\">\nText\n</Callout>",
            "export const title = 'Hidden title'",
            "export\tdefault 1",
            "import {\nCallout\n} from './Callout'",
            "Title\n=====",
            "Title\n---",
            "Title\n  ---  ",
            "Title\n=== ",
            "#\tTitle",
            "##\tTitle",
            "#",
            "Paragraph\n###",
            "```js\ncode\n    ```\n# This is still code in MDX",
            "A | B\n-- | --",
            "[reference]: /url",
            "- [x] Task list",
            "- First item\n  Continuation")) {
      assertError("UNSUPPORTED_MDX", () -> MdxDocument.parse(bytes(unsupported)));
    }
    for (String invalid :
        List.of(
            "<Callout>\nText",
            "</Callout>",
            "<A>\n</B>",
            "```js\nconst x = 1;",
            "Text with `unclosed code",
            "# #")) {
      assertError("INVALID_MDX", () -> MdxDocument.parse(bytes(invalid)));
    }
  }

  @Test
  public void literalEscapesAndInlineCodeAreSafeDocumentText() {
    String source = "Use `value={value}` or \\{literal\\} and \\<text\\>.";
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.MDX, bytes(source));
    assertEquals(source, catalog.messages().values().iterator().next().defaultMessage());
  }

  @Test
  public void headingClosingHashesStayTemplateOwnedAndLiteralHashesStayInTheMessage() {
    String source =
        "{/* mojito-id: heading */}\n## Title\t### \t\n\n"
            + "{/* mojito-id: literal */}\n# Title###\n\n"
            + "{/* mojito-id: escaped */}\n# Title \\###\n";
    MdxDocument document = MdxDocument.parse(bytes(source));
    assertEquals(
        List.of("Title", "Title###", "Title \\###"),
        document.blocks().stream().map(MdxDocument.Block::source).toList());
    assertEquals(
        List.of(2, 1, 1), document.blocks().stream().map(MdxDocument.Block::depth).toList());
    LocalizationSourceSkeleton skeleton =
        LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(source));
    assertArrayEquals(
        bytes(source.replace("Title", "Titre")),
        LocalizationFileConverters.renderSkeleton(
            skeleton, Map.of("heading", "Titre", "literal", "Titre###", "escaped", "Titre \\###")));
    for (String empty : List.of("# ", "##\t", "# ###", "## ### \t")) {
      try {
        MdxDocument.parse(bytes(empty));
        fail("Empty heading must not become a text unit: " + empty);
      } catch (LocalizationParseException expected) {
        assertTrue(expected.getMessage().contains("MDX line 1"));
      }
    }
  }

  @Test
  public void rejectsExecutableOrStructuralTranslationInjection() {
    LocalizationSourceSkeleton skeleton =
        LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(SOURCE));
    for (String unsafe :
        List.of(
            "{alert(1)}",
            "<script />",
            "export default 1",
            "export\tdefault 1",
            "new\n\nparagraph",
            "new\n# heading",
            "new\n- item",
            "new\n",
            "---",
            " title")) {
      try {
        LocalizationFileConverters.renderSkeleton(skeleton, Map.of("page.note", unsafe));
        fail("Expected unsafe translation to fail: " + unsafe);
      } catch (LocalizationParseException expected) {
        assertTrue(
            expected.code().equals("UNSUPPORTED_MDX") || expected.code().equals("INVALID_MDX"));
      }
    }
    assertError(
        "INVALID_SKELETON",
        () ->
            LocalizationFileConverters.renderSkeleton(
                skeleton, Map.of("page.title", "Bonjour **monde** #")));
  }

  @Test
  public void localizedImportRequiresExplicitIdentitiesOnEveryBlock() {
    assertError(
        "UNSUPPORTED_IMPORT_POLICY",
        () ->
            LocalizationFileConverters.parseForMojitoImport(
                LocalizationFileFormat.MDX,
                bytes("# Bonjour\n\nContenu."),
                List.of(),
                "fr",
                false));
    LocalizationCatalog catalog =
        LocalizationFileConverters.parseForMojitoImport(
            LocalizationFileFormat.MDX,
            bytes("{/* mojito-id: title */}\n# Bonjour\n\n{/* mojito-id: body */}\nContenu."),
            List.of(),
            "fr",
            false);
    assertEquals("Bonjour", catalog.messages().get("title").defaultMessage());
    assertEquals("Contenu.", catalog.messages().get("body").defaultMessage());
  }

  @Test
  public void inlineCodeAndLinkDestinationsStayProtectedButMayMoveInTranslations() {
    String source = "{/* mojito-id: body */}\nSee [the docs](/docs) for `value={value}`.";
    LocalizationSourceSkeleton skeleton =
        LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(source));
    assertEquals(
        "{/* mojito-id: body */}\nPour `value={value}`, voir [la documentation](/docs).",
        new String(
            LocalizationFileConverters.renderSkeleton(
                skeleton, Map.of("body", "Pour `value={value}`, voir [la documentation](/docs).")),
            StandardCharsets.UTF_8));
    assertError(
        "UNSUPPORTED_MDX",
        () ->
            LocalizationFileConverters.renderSkeleton(
                skeleton,
                Map.of("body", "See [the docs](javascript:alert(1)) for `value={value}`.")));
    assertError(
        "UNSUPPORTED_MDX",
        () ->
            LocalizationFileConverters.renderSkeleton(
                skeleton, Map.of("body", "See [the docs](/docs) for `different`.")));
  }

  @Test
  public void rejectsForgedSlotsAndUnknownTranslations() {
    LocalizationSourceSkeleton skeleton =
        LocalizationFileConverters.extractSkeleton(LocalizationFileFormat.MDX, bytes(SOURCE));
    List<LocalizationSourceSkeleton.LocalizationSourceSlot> slots =
        new ArrayList<>(skeleton.slots());
    var first = slots.get(0);
    slots.set(
        0,
        new LocalizationSourceSkeleton.LocalizationSourceSlot(
            first.id(), null, first.start() + 1, first.end()));
    LocalizationSourceSkeleton forged =
        new LocalizationSourceSkeleton(1, "mdx", skeleton.encoding(), skeleton.source(), slots);
    assertError(
        "INVALID_SKELETON", () -> LocalizationFileConverters.renderSkeleton(forged, Map.of()));
    assertError(
        "UNKNOWN_SKELETON_SLOT",
        () -> LocalizationFileConverters.renderSkeleton(skeleton, Map.of("unknown", "Text")));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static void assertError(String code, Runnable action) {
    try {
      action.run();
      fail("Expected " + code);
    } catch (LocalizationParseException expected) {
      assertEquals(expected.getMessage(), code, expected.code());
    }
  }
}
