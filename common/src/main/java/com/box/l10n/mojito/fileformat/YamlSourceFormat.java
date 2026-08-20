package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

/** YAML mapping scalars with exact source-owned quotes, block layout, and legacy key paths. */
final class YamlSourceFormat {

  private static final Resolver YAML_RESOLVER = new Resolver();

  private YamlSourceFormat() {}

  static LocalizationCatalog parse(String source) {
    return parse(source, LocalizationFilterOptions.parse(LocalizationFileFormat.YAML, List.of()));
  }

  static LocalizationCatalog parse(byte[] bytes, LocalizationFilterOptions options) {
    return parse(
        LocalizationFileConverters.decode(bytes, SourceSkeletonEncoding.detect(bytes).charset()),
        options);
  }

  private static LocalizationCatalog parse(String source, LocalizationFilterOptions options) {
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.YAML);
    collect(compose(source), "", "", options, catalog);
    return catalog;
  }

  static LocalizationSourceSkeleton extract(byte[] bytes) {
    return extract(bytes, LocalizationFilterOptions.parse(LocalizationFileFormat.YAML, List.of()));
  }

  static LocalizationSourceSkeleton extract(byte[] bytes, LocalizationFilterOptions options) {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes);
    String source = LocalizationFileConverters.decode(bytes, encoding.charset());
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    collectSlots(compose(source), "", "", source, encoding, options, slots);
    Set<String> ids = new HashSet<>();
    for (LocalizationSourceSlot slot : slots) {
      if (!ids.add(slot.id())) {
        throw invalid("DUPLICATE_MESSAGE_ID", "Duplicate YAML message ID: " + slot.id());
      }
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.YAML.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported YAML source skeleton");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      known.add(slot.id());
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no YAML source slot");
    }
    ByteArrayOutputStream result = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Invalid YAML source-slot range");
      }
      result.write(original, previous, slot.start() - previous);
      String translated = translations.get(slot.id());
      if (translated == null) {
        result.write(original, slot.start(), slot.end() - slot.start());
      } else {
        String raw = encoding.decode(original, slot.start(), slot.end());
        byte[] rendered = formatTranslation(raw, translated).getBytes(encoding.charset());
        result.write(rendered, 0, rendered.length);
      }
      previous = slot.end();
    }
    result.write(original, previous, original.length - previous);
    return result.toByteArray();
  }

  static byte[] removeEntries(
      LocalizationSourceSkeleton skeleton, Set<String> removed, LocalizationFilterOptions options) {
    if (skeleton.schemaVersion() != 1
        || !LocalizationFileFormat.YAML.id().equals(skeleton.sourceFormat())) {
      throw invalid("INVALID_SKELETON", "Unsupported YAML source skeleton");
    }
    List<RemovalCandidate> candidates = new ArrayList<>();
    collectRemovalCandidates(compose(skeleton.source()), "", "", candidates);
    List<Range> ranges = new ArrayList<>();
    for (RemovalCandidate candidate : candidates) {
      if (removed.contains(portableIdentity(candidate.path(), candidate.legacyPath(), options))) {
        ranges.add(removalRange(skeleton.source(), candidate));
      }
    }
    String filtered = removeRanges(skeleton.source(), ranges);
    if (filtered
        .lines()
        .noneMatch(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))) {
      if (!filtered.isEmpty() && !filtered.endsWith("\n") && !filtered.endsWith("\r")) {
        filtered += skeleton.source().contains("\r\n") ? "\r\n" : "\n";
      }
      filtered += "{}";
      if (skeleton.source().endsWith("\n")) {
        filtered += skeleton.source().endsWith("\r\n") ? "\r\n" : "\n";
      }
    }
    return SourceSkeletonEncoding.named(skeleton.encoding()).encode(filtered);
  }

  private static void collectRemovalCandidates(
      Node node, String parent, String legacyParent, List<RemovalCandidate> candidates) {
    if (node instanceof MappingNode mapping) {
      for (NodeTuple entry : mapping.getValue()) {
        if (!(entry.getKeyNode() instanceof ScalarNode key)) {
          throw invalid("INVALID_YAML", "YAML mapping keys must be scalars");
        }
        String path = parent.isEmpty() ? key.getValue() : parent + "/" + key.getValue();
        String legacyPath =
            legacyParent.isEmpty() ? key.getValue() : legacyParent + "/" + key.getValue();
        if (entry.getValueNode() instanceof ScalarNode value) {
          candidates.add(
              new RemovalCandidate(
                  path,
                  legacyPath,
                  key.getStartMark().getIndex(),
                  value.getEndMark().getIndex(),
                  false));
        } else {
          collectRemovalCandidates(entry.getValueNode(), path, legacyPath, candidates);
        }
      }
    } else if (node instanceof SequenceNode sequence) {
      for (int index = 0; index < sequence.getValue().size(); index++) {
        Node value = sequence.getValue().get(index);
        String path = parent + "[" + index + "]";
        if (value instanceof ScalarNode scalar) {
          candidates.add(
              new RemovalCandidate(
                  path,
                  legacyParent,
                  scalar.getStartMark().getIndex(),
                  scalar.getEndMark().getIndex(),
                  true));
        } else {
          collectRemovalCandidates(value, path, legacyParent, candidates);
        }
      }
    }
  }

  private static Range removalRange(String source, RemovalCandidate candidate) {
    int lineStart = lineStart(source, candidate.start());
    String prefix = source.substring(lineStart, candidate.start());
    boolean wholeLine = prefix.isBlank() || candidate.sequenceItem() && "-".equals(prefix.trim());
    if (wholeLine) {
      int end = candidate.end();
      while (end > candidate.start()
          && (source.charAt(end - 1) == '\r' || source.charAt(end - 1) == '\n')) {
        end--;
      }
      return new Range(lineStart, nextLine(source, lineEnd(source, end)));
    }
    return new Range(candidate.start(), candidate.end());
  }

  private static String removeRanges(String source, List<Range> raw) {
    raw.sort(Comparator.comparingInt(Range::start));
    List<Range> normalized = new ArrayList<>();
    for (int index = 0; index < raw.size(); ) {
      Range first = raw.get(index);
      if (lineStart(source, first.start()) == first.start()) {
        normalized.add(first);
        index++;
        continue;
      }
      Range last = first;
      int next = index + 1;
      while (next < raw.size() && commaSeparated(source, last.end(), raw.get(next).start())) {
        last = raw.get(next++);
      }
      int start = first.start();
      int end = last.end();
      int after = skipYamlWhitespace(source, end, 1);
      if (after < source.length() && source.charAt(after) == ',') {
        end = after + 1;
      } else {
        int before = skipYamlWhitespace(source, start, -1) - 1;
        if (before >= 0 && source.charAt(before) == ',') {
          start = before;
        }
      }
      normalized.add(new Range(start, end));
      index = next;
    }
    normalized.sort(Comparator.comparingInt(Range::start));
    StringBuilder result = new StringBuilder(source.length());
    int previous = 0;
    for (Range range : normalized) {
      if (range.start() < previous) {
        previous = Math.max(previous, range.end());
        continue;
      }
      result.append(source, previous, range.start());
      previous = range.end();
    }
    return result.append(source, previous, source.length()).toString();
  }

  private static boolean commaSeparated(String source, int left, int right) {
    String separator = source.substring(left, right).trim();
    return ",".equals(separator);
  }

  private static int skipYamlWhitespace(String source, int position, int direction) {
    if (direction > 0) {
      while (position < source.length() && " \t\r\n".indexOf(source.charAt(position)) >= 0) {
        position++;
      }
      return position;
    }
    while (position > 0 && " \t\r\n".indexOf(source.charAt(position - 1)) >= 0) {
      position--;
    }
    return position;
  }

  private static int lineStart(String source, int position) {
    return Math.max(source.lastIndexOf('\n', position - 1), source.lastIndexOf('\r', position - 1))
        + 1;
  }

  private static int lineEnd(String source, int position) {
    while (position < source.length()
        && source.charAt(position) != '\n'
        && source.charAt(position) != '\r') {
      position++;
    }
    return position;
  }

  private static int nextLine(String source, int end) {
    if (end >= source.length()) {
      return end;
    }
    return source.charAt(end) == '\r' && end + 1 < source.length() && source.charAt(end + 1) == '\n'
        ? end + 2
        : end + 1;
  }

  private static String formatTranslation(String original, String translated) {
    if (original.startsWith("'")) {
      if (requiresDoubleQuotes(translated)) {
        return doubleQuoted(translated);
      }
      return "'" + translated.replace("'", "''") + "'";
    }
    if (original.startsWith("\"")) {
      return doubleQuoted(translated);
    }
    if (original.startsWith("|") || original.startsWith(">")) {
      if (requiresQuotedBlock(translated)) {
        return doubleQuoted(translated);
      }
      int newline = original.indexOf('\n');
      if (newline < 0) {
        throw invalid("INVALID_SKELETON", "Invalid YAML block scalar");
      }
      int content = newline + 1;
      while (content < original.length() && original.charAt(content) == ' ') {
        content++;
      }
      String indent = original.substring(newline + 1, content);
      String lineSeparator = original.contains("\r\n") ? "\r\n" : "\n";
      String[] sourceLines = original.substring(newline + 1).split("\\R", -1);
      String[] translatedLines = translated.split("\\R", -1);
      StringBuilder result = new StringBuilder(original.substring(0, newline + 1));
      for (int index = 0; index < translatedLines.length; index++) {
        if (index > 0) {
          result.append(lineSeparator);
        }
        result.append(
            translatedLines[index].isEmpty()
                    && index < sourceLines.length
                    && sourceLines[index].isBlank()
                ? sourceLines[index]
                : indent);
        result.append(translatedLines[index]);
      }
      return result.toString();
    }
    return plainScalarIsString(translated) ? translated : doubleQuoted(translated);
  }

  private static boolean plainScalarIsString(String value) {
    if (value.isEmpty()
        || !value.equals(value.strip())
        || requiresDoubleQuotes(value)
        || "~".equals(value)
        || "null".equalsIgnoreCase(value)
        || "<<".equals(value)
        || "=".equals(value)
        || "---".equals(value)
        || "...".equals(value)
        || !Tag.STR.equals(YAML_RESOLVER.resolve(NodeId.scalar, value, true))) {
      return false;
    }
    char first = value.charAt(0);
    if ("-?:,[]{}#&*!|>'\"%@`".indexOf(first) >= 0) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (",[]{}".indexOf(current) >= 0) {
        return false;
      }
      if (current == ':'
          && (index + 1 == value.length() || Character.isWhitespace(value.charAt(index + 1)))) {
        return false;
      }
      if (current == '#' && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
        return false;
      }
    }
    return true;
  }

  private static boolean requiresDoubleQuotes(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '\n'
          || current == '\r'
          || current == '\t'
          || current < 0x20
          || current >= 0x7f && current <= 0x9f
          || current == 0x85
          || current == 0x2028
          || current == 0x2029) {
        return true;
      }
    }
    return false;
  }

  private static boolean requiresQuotedBlock(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '\t'
          || current == '\n'
          || current == '\r'
          || current == '\u000b'
          || current == '\f'
          || current == '\u0085'
          || current == '\u2028'
          || current == '\u2029') {
        continue;
      }
      if (current < 0x20 || current >= 0x7f && current <= 0x9f) {
        return true;
      }
    }
    return false;
  }

  private static String doubleQuoted(String value) {
    StringBuilder result = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '\0' -> result.append("\\0");
        case '\u0007' -> result.append("\\a");
        case '\b' -> result.append("\\b");
        case '\t' -> result.append("\\t");
        case '\n' -> result.append("\\n");
        case '\u000b' -> result.append("\\v");
        case '\f' -> result.append("\\f");
        case '\r' -> result.append("\\r");
        case '\u001b' -> result.append("\\e");
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\u0085' -> result.append("\\N");
        case '\u2028' -> result.append("\\L");
        case '\u2029' -> result.append("\\P");
        default -> {
          if (current < 0x20 || current >= 0x7f && current <= 0x9f) {
            result.append(String.format("\\u%04x", (int) current));
          } else {
            result.append(current);
          }
        }
      }
    }
    return result.append('"').toString();
  }

  private static void collect(
      Node node,
      String parent,
      String legacyParent,
      LocalizationFilterOptions options,
      LocalizationCatalog catalog) {
    if (node instanceof MappingNode mapping) {
      for (NodeTuple entry : mapping.getValue()) {
        if (!(entry.getKeyNode() instanceof ScalarNode key)) {
          throw invalid("INVALID_YAML", "YAML mapping keys must be scalars");
        }
        String path = parent.isEmpty() ? key.getValue() : parent + "/" + key.getValue();
        String legacyPath =
            legacyParent.isEmpty() ? key.getValue() : legacyParent + "/" + key.getValue();
        collect(entry.getValueNode(), path, legacyPath, options, catalog);
      }
    } else if (node instanceof SequenceNode sequence) {
      for (int index = 0; index < sequence.getValue().size(); index++) {
        collect(
            sequence.getValue().get(index),
            parent + "[" + index + "]",
            legacyParent,
            options,
            catalog);
      }
    } else if (node instanceof ScalarNode value && !parent.isEmpty()) {
      String legacyId = legacyIdentity(legacyParent, options);
      if (shouldExtract(legacyId, options)) {
        String id = portableIdentity(parent, legacyParent, options);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!id.equals(legacyId)) {
          metadata.put("yamlLegacyId", legacyId);
        }
        catalog.add(id, LocalizationMessage.of(value.getValue(), null, null, null, metadata));
      }
    }
  }

  private static void collectSlots(
      Node node,
      String parent,
      String legacyParent,
      String source,
      SourceSkeletonEncoding encoding,
      LocalizationFilterOptions options,
      List<LocalizationSourceSlot> slots) {
    if (node instanceof MappingNode mapping) {
      for (NodeTuple entry : mapping.getValue()) {
        if (!(entry.getKeyNode() instanceof ScalarNode key)) {
          throw invalid("INVALID_YAML", "YAML mapping keys must be scalars");
        }
        String path = parent.isEmpty() ? key.getValue() : parent + "/" + key.getValue();
        String legacyPath =
            legacyParent.isEmpty() ? key.getValue() : legacyParent + "/" + key.getValue();
        collectSlots(entry.getValueNode(), path, legacyPath, source, encoding, options, slots);
      }
    } else if (node instanceof SequenceNode sequence) {
      for (int index = 0; index < sequence.getValue().size(); index++) {
        collectSlots(
            sequence.getValue().get(index),
            parent + "[" + index + "]",
            legacyParent,
            source,
            encoding,
            options,
            slots);
      }
    } else if (node instanceof ScalarNode value && !parent.isEmpty()) {
      int start = value.getStartMark().getIndex();
      int end = value.getEndMark().getIndex();
      if (value.getScalarStyle() == ScalarStyle.LITERAL
          || value.getScalarStyle() == ScalarStyle.FOLDED) {
        while (end > start && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) {
          end--;
        }
      }
      String legacyId = legacyIdentity(legacyParent, options);
      if (shouldExtract(legacyId, options)) {
        slots.add(
            new LocalizationSourceSlot(
                portableIdentity(parent, legacyParent, options),
                null,
                encoding.offset(source, start),
                encoding.offset(source, end)));
      }
    }
  }

  private static String portableIdentity(
      String path, String legacyPath, LocalizationFilterOptions options) {
    return usesFullKeyPath(options) ? path : leaf(legacyPath);
  }

  private static String legacyIdentity(String legacyPath, LocalizationFilterOptions options) {
    return usesFullKeyPath(options) ? legacyPath : leaf(legacyPath);
  }

  private static boolean usesFullKeyPath(LocalizationFilterOptions options) {
    return !options.contains("useFullKeyPath") || options.enabled("useFullKeyPath");
  }

  private static boolean shouldExtract(String legacyId, LocalizationFilterOptions options) {
    Pattern exceptions = options.pattern("exceptions");
    boolean exception = exceptions != null && exceptions.matcher(legacyId).find();
    boolean all = !options.contains("extractAllPairs") || options.enabled("extractAllPairs");
    return all != exception;
  }

  private static String leaf(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static Node compose(String source) {
    try {
      LoaderOptions settings = new LoaderOptions();
      settings.setAllowDuplicateKeys(false);
      settings.setMaxAliasesForCollections(16);
      settings.setNestingDepthLimit(64);
      Node result = new Yaml(settings).compose(new StringReader(source));
      if (!(result instanceof MappingNode)) {
        throw invalid("INVALID_YAML", "YAML localization sources require a mapping");
      }
      return result;
    } catch (YAMLException invalid) {
      throw invalid("INVALID_YAML", "Invalid YAML localization source");
    }
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private record RemovalCandidate(
      String path, String legacyPath, int start, int end, boolean sequenceItem) {}

  private record Range(int start, int end) {}
}
