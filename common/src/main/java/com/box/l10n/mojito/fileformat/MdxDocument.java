package com.box.l10n.mojito.fileformat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * A deliberately static MDX document: readable Markdown blocks and opaque component boundaries.
 * JavaScript is never evaluated. Syntax outside the supported subset fails before extraction.
 */
public final class MdxDocument {

  public static final String PREVIEW_CHOICE = "PreviewChoice";

  private static final Pattern ID =
      Pattern.compile("\\{/\\*\\s*mojito-id:\\s*([A-Za-z0-9][A-Za-z0-9_.:-]*)\\s*\\*/}");
  private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
  private static final Pattern IMPORT =
      Pattern.compile(
          "import\\s+(?:("
              + IDENTIFIER
              + ")|\\{([^{}]*)})\\s+from\\s+(['\"])([^'\"\\\\\\r\\n]+)\\3;?");
  private static final Pattern NAMED_IMPORT =
      Pattern.compile("\\s*(" + IDENTIFIER + ")(?:\\s+as\\s+(" + IDENTIFIER + "))?\\s*");
  private static final Pattern COMPONENT = Pattern.compile("<(/?)([A-Z][A-Za-z0-9_.]*)(\\s*/?)>");
  private static final Pattern PREVIEW_MESSAGE = Pattern.compile("^<PreviewMessage(?=[\\s/>])");
  private static final Pattern HEADING = Pattern.compile("(#{1,6} )[ \\t]*(.*)");
  private static final Pattern ATX_HEADING = Pattern.compile("#{1,6}(?:[ \\t]+.*)?");
  private static final Pattern LIST = Pattern.compile("( *)([-+*] |[0-9]{1,9}[.)] )(.*)");
  private static final Pattern FENCE = Pattern.compile("(`{3,}|~{3,})([^`~]*)");
  private static final Pattern RULE =
      Pattern.compile("(?:\\* *\\* *\\*[ *]*|- *- *-[ -]*|_ *_ *_[ _]*)");
  private static final Pattern REFERENCE = Pattern.compile("\\[[^]]+]:.*");
  private static final Pattern SETEXT_UNDERLINE = Pattern.compile(" {0,3}(?:=+|-+)[ \\t]*");
  private static final Pattern ESM = Pattern.compile("(?:import|export)\\b.*");
  private final String source;
  private final List<Block> blocks;
  private final List<Segment> segments;
  private final Map<String, String> imports;
  private final boolean explicitIdentities;

  /**
   * Source omits template-owned block markers; code and component context retain literal syntax.
   */
  public record Block(
      String id,
      String type,
      int depth,
      String source,
      int line,
      boolean translatable,
      String marker) {}

  /** Static JSX tag identity. It carries no executable component or property values. */
  public record ComponentReference(String name, boolean closing, boolean selfClosing) {}

  record Segment(Block block, int start, int end) {}

  private record Line(int start, int end, int next, int number, String text) {}

  private MdxDocument(
      String source,
      List<Block> blocks,
      List<Segment> segments,
      Map<String, String> imports,
      boolean explicitIdentities) {
    this.source = source;
    this.blocks = List.copyOf(blocks);
    this.segments = List.copyOf(segments);
    this.imports = Collections.unmodifiableMap(new LinkedHashMap<>(imports));
    this.explicitIdentities = explicitIdentities;
  }

  public static MdxDocument parse(byte[] source) {
    return parse(LocalizationFileConverters.decode(source, null));
  }

  public List<Block> blocks() {
    return blocks;
  }

  /** Default import binding to literal module specifier; named imports remain opaque context. */
  public Map<String, String> imports() {
    return imports;
  }

  public static ComponentReference componentReference(Block block) {
    if (block == null || !"component".equals(block.type()) || block.source() == null) {
      return null;
    }
    Matcher component = COMPONENT.matcher(block.source().strip());
    return component.matches()
        ? new ComponentReference(
            component.group(2),
            !component.group(1).isEmpty(),
            component.group(3).strip().equals("/"))
        : null;
  }

  boolean hasOnlyExplicitIdentities() {
    return explicitIdentities;
  }

  List<Segment> segments() {
    return segments;
  }

  String source() {
    return source;
  }

  static MdxDocument parse(String source) {
    List<Line> lines = lines(source);
    List<Block> blocks = new ArrayList<>();
    List<Segment> segments = new ArrayList<>();
    Map<String, String> imports = new LinkedHashMap<>();
    Set<String> importBindings = new HashSet<>();
    Deque<String> components = new ArrayDeque<>();
    Map<String, Integer> occurrences = new HashMap<>();
    Set<String> identities = new HashSet<>();
    String pendingId = null;
    boolean hasContent = false;
    boolean explicitIdentities = true;
    for (int index = 0; index < lines.size(); index++) {
      Line line = lines.get(index);
      String text = line.text();
      if (text.isBlank()) {
        continue;
      }
      String trimmed = text.strip();
      Matcher identity = ID.matcher(trimmed);
      if (identity.matches()) {
        if (pendingId != null) {
          throw invalid(line.number(), "Consecutive mojito-id annotations need a text block");
        }
        pendingId = identity.group(1);
        continue;
      }
      if (!hasContent && trimmed.equals("---")) {
        throw unsupported(line.number(), "Frontmatter is not supported; keep metadata separate");
      }
      if (trimmed.startsWith("import") && ESM.matcher(trimmed).matches()) {
        Matcher declaration = IMPORT.matcher(trimmed);
        if (hasContent || !declaration.matches() || pendingId != null) {
          throw unsupported(
              line.number(), "Only single-line static imports before content are supported");
        }
        if (declaration.group(1) != null) {
          String binding = declaration.group(1);
          addImportBinding(importBindings, binding, line.number());
          imports.put(binding, declaration.group(4));
        } else {
          String named = declaration.group(2).strip();
          if (!named.isEmpty()) {
            String[] specifiers = named.split(",", -1);
            for (int specifierIndex = 0; specifierIndex < specifiers.length; specifierIndex++) {
              if (specifierIndex == specifiers.length - 1
                  && specifiers[specifierIndex].isBlank()
                  && specifierIndex > 0) {
                continue;
              }
              Matcher specifier = NAMED_IMPORT.matcher(specifiers[specifierIndex]);
              if (!specifier.matches()) {
                throw unsupported(line.number(), "Only static named import bindings are supported");
              }
              addImportBinding(
                  importBindings,
                  specifier.group(2) == null ? specifier.group(1) : specifier.group(2),
                  line.number());
            }
          }
        }
        continue;
      }
      hasContent = true;
      if (PREVIEW_MESSAGE.matcher(trimmed).find()) {
        requireNoPendingId(pendingId, line);
        MdxPreviewMessage.parse(trimmed);
        blocks.add(
            new Block(
                null, "preview-message", components.size(), trimmed, line.number(), false, null));
        continue;
      }
      Matcher component = COMPONENT.matcher(trimmed);
      if (component.matches()) {
        requireNoPendingId(pendingId, line);
        boolean close = !component.group(1).isEmpty();
        boolean selfClosing = component.group(3).strip().equals("/");
        String name = component.group(2);
        if (close) {
          if (selfClosing || components.isEmpty() || !components.pop().equals(name)) {
            throw invalid(line.number(), "Mismatched component closing tag");
          }
        }
        blocks.add(
            new Block(null, "component", components.size(), trimmed, line.number(), false, null));
        if (!close && !selfClosing) {
          components.push(name);
        }
        continue;
      }
      Matcher fence = FENCE.matcher(trimmed);
      if (fence.matches()) {
        requireNoPendingId(pendingId, line);
        if (!text.equals(trimmed)) {
          throw unsupported(line.number(), "Indented fenced code is not supported");
        }
        String delimiter = fence.group(1);
        int last = index + 1;
        while (last < lines.size()) {
          String closing = lines.get(last).text().strip();
          if (closing.length() >= delimiter.length()
              && closing.chars().allMatch(character -> character == delimiter.charAt(0))) {
            String closingLine = lines.get(last).text();
            int indentation = closingLine.indexOf(delimiter.charAt(0));
            if (indentation > 3
                || !closingLine.substring(0, indentation).chars().allMatch(value -> value == ' ')) {
              throw unsupported(
                  lines.get(last).number(),
                  "Code fence closers support at most three spaces of indentation");
            }
            break;
          }
          last++;
        }
        if (last == lines.size()) {
          throw invalid(line.number(), "Unclosed fenced code block");
        }
        blocks.add(
            new Block(
                null,
                "code",
                0,
                source.substring(line.start(), lines.get(last).end()),
                line.number(),
                false,
                null));
        index = last;
        continue;
      }
      if (RULE.matcher(trimmed).matches()) {
        requireNoPendingId(pendingId, line);
        blocks.add(new Block(null, "thematic-break", 0, text, line.number(), false, null));
        continue;
      }
      String type = "paragraph";
      int depth = 0;
      int start = line.start();
      int end = line.end();
      String marker = null;
      Matcher heading = HEADING.matcher(text);
      Matcher list = LIST.matcher(text);
      if (heading.matches()) {
        type = "heading";
        depth = heading.group(1).length() - 1;
        marker = heading.group(1);
        start += heading.start(2);
        Matcher closing = Pattern.compile("[ \\t]+#+[ \\t]*$").matcher(text);
        if (closing.find()) {
          end = Math.max(start, line.start() + closing.start());
        }
      } else if (list.matches()) {
        type = "list-item";
        depth = list.group(1).length();
        marker = list.group(2);
        start += list.start(3);
        if (list.group(3).matches("\\[[ xX]](?: .*|$)")) {
          throw unsupported(line.number(), "Task list checkboxes are not supported");
        }
      } else if (text.startsWith("> ")) {
        type = "blockquote";
        marker = "> ";
        start += 2;
      } else {
        requireParagraphLine(text, line.number());
        while (index + 1 < lines.size()) {
          Line next = lines.get(index + 1);
          if (SETEXT_UNDERLINE.matcher(next.text()).matches()) {
            throw unsupported(next.number(), "Setext headings are not supported; use # headings");
          }
          if (next.text().isBlank() || startsBlock(next.text())) {
            break;
          }
          requireParagraphLine(next.text(), next.number());
          end = next.end();
          index++;
        }
      }
      String message = source.substring(start, end);
      validateInline(message, line.number());
      if (message.isBlank()) {
        throw invalid(line.number(), "Empty text block");
      }
      String id = pendingId;
      if (id == null) {
        explicitIdentities = false;
        String base = "mdx." + DigestUtils.sha256Hex(type + "\n" + message).substring(0, 20);
        int occurrence = occurrences.merge(base, 1, Integer::sum);
        id = occurrence == 1 ? base : base + "." + occurrence;
      }
      if (!identities.add(id)) {
        throw new LocalizationParseException(
            "DUPLICATE_MESSAGE_ID",
            "Duplicate MDX message ID at line " + line.number() + ": " + id);
      }
      Block block = new Block(id, type, depth, message, line.number(), true, marker);
      blocks.add(block);
      segments.add(new Segment(block, start, end));
      pendingId = null;
    }
    if (pendingId != null) {
      throw invalid(lines.size(), "mojito-id annotation has no following text block");
    }
    if (!components.isEmpty()) {
      throw invalid(lines.size(), "Unclosed component: " + components.peek());
    }
    validatePreviewChoices(blocks, imports);
    return new MdxDocument(source, blocks, segments, imports, explicitIdentities);
  }

  private static void validatePreviewChoices(List<Block> blocks, Map<String, String> imports) {
    boolean insideChoice = false;
    int choices = 0;
    for (Block block : blocks) {
      ComponentReference component = componentReference(block);
      boolean choice = component != null && PREVIEW_CHOICE.equals(component.name());
      if (insideChoice) {
        if (choice && component.closing()) {
          if (choices < 2) {
            throw invalid(block.line(), "PreviewChoice requires 2 to 8 module alternatives");
          }
          insideChoice = false;
          continue;
        }
        if (component == null
            || choice
            || component.closing()
            || !component.selfClosing()
            || !isRelativeMdxImport(imports.get(component.name()))) {
          throw invalid(
              block.line(),
              "PreviewChoice children must be self-closing default-import relative MDX modules");
        }
        if (++choices > 8) {
          throw invalid(block.line(), "PreviewChoice requires 2 to 8 module alternatives");
        }
      } else if (choice) {
        if (component.selfClosing()) {
          throw invalid(block.line(), "PreviewChoice requires paired tags and module alternatives");
        }
        insideChoice = true;
        choices = 0;
      }
    }
  }

  private static boolean isRelativeMdxImport(String specifier) {
    // Asset lookup and repository-root containment remain the renderer's responsibility.
    return specifier != null
        && (specifier.startsWith("./") || specifier.startsWith("../"))
        && specifier.toLowerCase(Locale.ROOT).endsWith(".mdx")
        && specifier
            .chars()
            .noneMatch(
                value ->
                    value == '\\'
                        || value == '%'
                        || value == '#'
                        || value == '?'
                        || value == ':'
                        || Character.isISOControl(value));
  }

  private static void addImportBinding(Set<String> bindings, String binding, int line) {
    if (PREVIEW_CHOICE.equals(binding) || MdxPreviewMessage.COMPONENT.equals(binding)) {
      throw invalid(line, binding + " is a reserved built-in component and cannot be imported");
    }
    if (!bindings.add(binding)) {
      throw invalid(line, "Duplicate import binding: " + binding);
    }
  }

  static void validateTranslation(Block block, String translation) {
    if (translation.isBlank()) {
      throw invalid(block.line(), "A translated text block must not be empty");
    }
    validateInline(translation, block.line());
    if (!protectedInline(block.source(), block.line())
        .equals(protectedInline(translation, block.line()))) {
      throw unsupported(
          block.line(), "Translation must preserve inline code and link destinations");
    }
    List<Line> lines = lines(translation);
    if (!block.type().equals("paragraph") && lines.size() != 1) {
      throw unsupported(block.line(), "This block requires a single-line translation");
    }
    for (Line line : lines) {
      if (line.text().isBlank() || startsBlock(line.text())) {
        throw unsupported(block.line(), "Translation cannot introduce a new document block");
      }
      requireParagraphLine(line.text(), block.line());
    }
    if (translation.endsWith("\n") || translation.endsWith("\r")) {
      throw unsupported(block.line(), "Translation cannot introduce a trailing line break");
    }
  }

  private static void requireNoPendingId(String id, Line line) {
    if (id != null) {
      throw invalid(line.number(), "mojito-id must immediately precede a translatable block");
    }
  }

  private static boolean startsBlock(String text) {
    String trimmed = text.strip();
    return HEADING.matcher(text).matches()
        || LIST.matcher(text).matches()
        || text.startsWith(">")
        || trimmed.startsWith("<")
        || trimmed.startsWith("{")
        || ESM.matcher(trimmed).matches()
        || FENCE.matcher(trimmed).matches()
        || RULE.matcher(trimmed).matches();
  }

  private static void requireParagraphLine(String text, int line) {
    if (ATX_HEADING.matcher(text).matches()) {
      throw unsupported(line, "ATX headings require a space and nonempty text after #");
    }
    if (text.startsWith(" ") || text.startsWith("\t")) {
      throw unsupported(line, "Indented prose and multiline list items are not supported");
    }
    if (ESM.matcher(text).matches()) {
      throw unsupported(line, "JavaScript declarations are not supported in document content");
    }
    if (text.startsWith("|") || text.matches("[=-]+") || REFERENCE.matcher(text).matches()) {
      throw unsupported(line, "Tables, setext headings, and reference links are not supported");
    }
    if (text.startsWith(">")) {
      throw unsupported(line, "Use a single-level blockquote with a space after >");
    }
  }

  private static void validateInline(String text, int line) {
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == '\\') {
        if (index + 1 < text.length()) {
          index++;
        }
      } else if (character == '`') {
        int after = index;
        while (after < text.length() && text.charAt(after) == '`') {
          after++;
        }
        String delimiter = text.substring(index, after);
        int closing = text.indexOf(delimiter, after);
        while (closing >= 0
            && ((closing > 0 && text.charAt(closing - 1) == '`')
                || (closing + delimiter.length() < text.length()
                    && text.charAt(closing + delimiter.length()) == '`'))) {
          closing = text.indexOf(delimiter, closing + delimiter.length());
        }
        if (closing < 0) {
          throw invalid(line, "Unclosed inline code span");
        }
        index = closing + delimiter.length() - 1;
      } else if (character == '{' || character == '}') {
        throw unsupported(
            line,
            "JavaScript and MF2 expressions require an explicit future adapter; escape literal braces");
      } else if (character == '<' || character == '>') {
        throw unsupported(
            line, "Inline JSX and raw HTML are not supported; escape literal angle brackets");
      } else if (character == '|') {
        throw unsupported(line, "Tables are not supported; escape literal pipe characters");
      } else if (character == '\u0000'
          || (Character.isSurrogate(character)
              && (!Character.isHighSurrogate(character)
                  || index + 1 >= text.length()
                  || !Character.isLowSurrogate(text.charAt(index + 1))))) {
        throw invalid(line, "Invalid Unicode in text block");
      } else if (Character.isHighSurrogate(character)) {
        index++;
      }
    }
    protectedInline(text, line);
  }

  private static List<String> protectedInline(String text, int line) {
    List<String> protectedValues = new ArrayList<>();
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) == '\\') {
        index++;
      } else if (text.charAt(index) == '`') {
        int after = index;
        while (after < text.length() && text.charAt(after) == '`') {
          after++;
        }
        String delimiter = text.substring(index, after);
        int closing = text.indexOf(delimiter, after);
        while (closing >= 0
            && ((closing > 0 && text.charAt(closing - 1) == '`')
                || (closing + delimiter.length() < text.length()
                    && text.charAt(closing + delimiter.length()) == '`'))) {
          closing = text.indexOf(delimiter, closing + delimiter.length());
        }
        if (closing < 0) {
          throw invalid(line, "Unclosed inline code span");
        }
        int end = closing + delimiter.length();
        protectedValues.add("code:" + text.substring(index, end));
        index = end - 1;
      } else if (text.startsWith("](", index)) {
        int depth = 1;
        int end = index + 2;
        for (; end < text.length() && depth > 0; end++) {
          if (text.charAt(end) == '\\') {
            end++;
          } else if (text.charAt(end) == '(') {
            depth++;
          } else if (text.charAt(end) == ')') {
            depth--;
          }
        }
        if (depth != 0) {
          throw invalid(line, "Unclosed inline link destination");
        }
        protectedValues.add("link:" + text.substring(index + 1, end));
        index = end - 1;
      }
    }
    return protectedValues.stream().sorted().toList();
  }

  private static List<Line> lines(String source) {
    List<Line> lines = new ArrayList<>();
    int start = 0;
    while (start < source.length()) {
      int end = start;
      while (end < source.length() && source.charAt(end) != '\r' && source.charAt(end) != '\n') {
        end++;
      }
      int next = end;
      if (next < source.length() && source.charAt(next) == '\r') {
        next++;
      }
      if (next < source.length() && source.charAt(next) == '\n') {
        next++;
      }
      lines.add(new Line(start, end, next, lines.size() + 1, source.substring(start, end)));
      start = next;
    }
    return lines;
  }

  private static LocalizationParseException unsupported(int line, String reason) {
    return new LocalizationParseException("UNSUPPORTED_MDX", "MDX line " + line + ": " + reason);
  }

  private static LocalizationParseException invalid(int line, String reason) {
    return new LocalizationParseException("INVALID_MDX", "MDX line " + line + ": " + reason);
  }
}
