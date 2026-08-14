package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
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
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** YAML mapping scalars with exact source-owned quotes, block layout, and legacy key paths. */
final class YamlSourceFormat {

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
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes);
    String source = LocalizationFileConverters.decode(bytes, encoding.charset());
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    collectSlots(compose(source), "", source, encoding, slots);
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

  private static String formatTranslation(String original, String translated) {
    if (original.startsWith("'")) {
      return "'" + translated.replace("'", "''") + "'";
    }
    if (original.startsWith("\"")) {
      return "\""
          + translated
              .replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\n", "\\n")
              .replace("\r", "\\r")
              .replace("\t", "\\t")
          + "\"";
    }
    if (original.startsWith("|") || original.startsWith(">")) {
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
      String[] translatedLines = translated.split("\\n", -1);
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
    if (translated.indexOf('\n') >= 0 || translated.contains(": ") || translated.contains(" #")) {
      return "'" + translated.replace("'", "''") + "'";
    }
    return translated;
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
      String key = legacyParent.substring(legacyParent.lastIndexOf('/') + 1);
      Pattern exceptions = options.pattern("exceptions");
      boolean exception = exceptions != null && exceptions.matcher(legacyParent).find();
      boolean all = !options.contains("extractAllPairs") || options.enabled("extractAllPairs");
      if (all != exception) {
        String id =
            options.contains("useFullKeyPath") && !options.enabled("useFullKeyPath") ? key : parent;
        if (!parent.equals(legacyParent)
            && options.contains("useFullKeyPath")
            && !options.enabled("useFullKeyPath")) {
          id += parent.substring(parent.lastIndexOf('['));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!id.equals(legacyParent)) {
          metadata.put("yamlLegacyId", legacyParent);
        }
        catalog.add(id, LocalizationMessage.of(value.getValue(), null, null, null, metadata));
      }
    }
  }

  private static void collectSlots(
      Node node,
      String parent,
      String source,
      SourceSkeletonEncoding encoding,
      List<LocalizationSourceSlot> slots) {
    if (node instanceof MappingNode mapping) {
      for (NodeTuple entry : mapping.getValue()) {
        if (!(entry.getKeyNode() instanceof ScalarNode key)) {
          throw invalid("INVALID_YAML", "YAML mapping keys must be scalars");
        }
        String path = parent.isEmpty() ? key.getValue() : parent + "/" + key.getValue();
        collectSlots(entry.getValueNode(), path, source, encoding, slots);
      }
    } else if (node instanceof SequenceNode sequence) {
      for (int index = 0; index < sequence.getValue().size(); index++) {
        collectSlots(
            sequence.getValue().get(index), parent + "[" + index + "]", source, encoding, slots);
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
      slots.add(
          new LocalizationSourceSlot(
              parent, null, encoding.offset(source, start), encoding.offset(source, end)));
    }
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
}
