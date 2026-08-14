package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringEscapeUtils;

/** Lossless lexical source ownership layered beside Android's semantic resource parser. */
final class AndroidSourceSkeleton {

  private static final Pattern ATTRIBUTE =
      Pattern.compile("([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*([\"'])(.*?)\\2", Pattern.DOTALL);
  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

  private AndroidSourceSkeleton() {}

  static LocalizationSourceSkeleton extract(byte[] bytes) {
    return extract(bytes, null, null);
  }

  static byte[] retainPluralCategories(byte[] original, Set<String> categories) {
    if (categories.isEmpty()) {
      return original;
    }
    LocalizationSourceSkeleton skeleton = extract(original);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    String source = skeleton.source();
    Map<String, List<PluralSourceItem>> groups = new LinkedHashMap<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.variant() != null) {
        groups
            .computeIfAbsent(slot.id(), ignored -> new ArrayList<>())
            .add(pluralSourceItem(original, source, encoding, slot));
      }
    }
    List<PluralEdit> edits = new ArrayList<>();
    List<String> order = List.of("zero", "one", "two", "few", "many", "other");
    for (List<PluralSourceItem> group : groups.values()) {
      PluralSourceItem fallback =
          group.stream().filter(item -> "other".equals(item.category())).findFirst().orElse(null);
      if (fallback == null) {
        continue;
      }
      for (PluralSourceItem item : group) {
        if (!categories.contains(item.category())) {
          edits.add(new PluralEdit(item.start(), item.end(), ""));
        }
      }
      Map<Integer, StringBuilder> additions = new TreeMap<>();
      for (String category : order) {
        if (!categories.contains(category)
            || group.stream().anyMatch(item -> category.equals(item.category()))) {
          continue;
        }
        int rank = order.indexOf(category);
        PluralSourceItem next =
            group.stream()
                .filter(item -> order.indexOf(item.category()) > rank)
                .findFirst()
                .orElse(null);
        int position = next == null ? group.get(group.size() - 1).end() : next.start();
        String template = source.substring(fallback.start(), fallback.end());
        int valueStart = fallback.quantityStart() - fallback.start();
        int valueEnd = fallback.quantityEnd() - fallback.start();
        additions
            .computeIfAbsent(position, ignored -> new StringBuilder())
            .append(template, 0, valueStart)
            .append(category)
            .append(template, valueEnd, template.length());
      }
      additions.forEach(
          (position, values) -> edits.add(new PluralEdit(position, position, values.toString())));
    }
    if (edits.isEmpty()) {
      return original;
    }
    edits.sort(
        (left, right) -> {
          int start = Integer.compare(right.start(), left.start());
          return start == 0 ? Integer.compare(right.end(), left.end()) : start;
        });
    StringBuilder result = new StringBuilder(source);
    for (PluralEdit edit : edits) {
      result.replace(edit.start(), edit.end(), edit.replacement());
    }
    return encoding.encode(result.toString());
  }

  private static PluralSourceItem pluralSourceItem(
      byte[] original,
      String source,
      SourceSkeletonEncoding encoding,
      LocalizationSourceSlot slot) {
    int valueStart = encoding.decode(original, encoding.bom().length, slot.start()).length();
    int valueEnd = encoding.decode(original, encoding.bom().length, slot.end()).length();
    int opening = source.lastIndexOf("<item", valueStart);
    if (opening < 0) {
      throw invalid("INVALID_SKELETON", "Android plural has no owned item element");
    }
    int openingEnd = tagEnd(source, opening);
    Matcher attributes = ATTRIBUTE.matcher(source.substring(opening, openingEnd + 1));
    int quantityStart = -1;
    int quantityEnd = -1;
    while (attributes.find()) {
      if ("quantity".equals(attributes.group(1))) {
        if (!StringEscapeUtils.unescapeXml(attributes.group(3)).trim().equals(slot.variant())) {
          throw invalid("INVALID_SKELETON", "Android plural quantity does not own its value");
        }
        quantityStart = opening + attributes.start(3);
        quantityEnd = opening + attributes.end(3);
        break;
      }
    }
    if (quantityStart < 0) {
      throw invalid("INVALID_SKELETON", "Android plural item has no owned quantity");
    }
    int end;
    if (source.startsWith("</item>", valueEnd)) {
      end = valueEnd + "</item>".length();
    } else if (source.charAt(valueStart) == '/' && source.charAt(valueEnd - 1) == '>') {
      end = valueEnd;
    } else {
      throw invalid("INVALID_SKELETON", "Android plural item has no closing tag");
    }
    int lineStart =
        Math.max(source.lastIndexOf('\n', opening - 1), source.lastIndexOf('\r', opening - 1)) + 1;
    int start = source.substring(lineStart, opening).isBlank() ? lineStart : opening;
    while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
      end++;
    }
    if (end < source.length() && source.charAt(end) == '\r') {
      end++;
    }
    if (end < source.length() && source.charAt(end) == '\n') {
      end++;
    }
    return new PluralSourceItem(slot.variant(), start, end, quantityStart, quantityEnd);
  }

  private record PluralSourceItem(
      String category, int start, int end, int quantityStart, int quantityEnd) {}

  private record PluralEdit(int start, int end, String replacement) {}

  static LocalizationSourceSkeleton extract(
      byte[] bytes, String resourcePath, List<AndroidFeatureFlag> featureFlags) {
    return extract(bytes, resourcePath, featureFlags, null);
  }

  static LocalizationSourceSkeleton extract(
      byte[] bytes,
      String resourcePath,
      List<AndroidFeatureFlag> featureFlags,
      LocalizationCatalog resolvedCatalog) {
    var declared = LocalizationFileConverters.xmlCharset(LocalizationFileFormat.ANDROID, bytes);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes, declared);
    String source = LocalizationFileConverters.decode(bytes, declared);
    LocalizationCatalog catalog =
        resolvedCatalog != null
            ? resolvedCatalog
            : featureFlags == null && resourcePath == null
                ? LocalizationFileConverters.parse(LocalizationFileFormat.ANDROID, bytes)
                : LocalizationFileConverters.parseWithAndroidFeatureFlags(
                    LocalizationFileFormat.ANDROID,
                    bytes,
                    null,
                    resourcePath,
                    featureFlags == null ? List.of() : featureFlags,
                    null);
    Map<String, AndroidFeatureFlag> flags = new HashMap<>();
    if (featureFlags != null) {
      for (AndroidFeatureFlag flag : featureFlags) {
        flags.put(flag.name(), flag);
      }
    }
    String pathRuntimeFlag = null;
    if (resourcePath != null) {
      String pathFlag = AndroidResourceConfiguration.parse(resourcePath).pathFeatureFlag();
      if (pathFlag != null) {
        String name = pathFlag.startsWith("!") ? pathFlag.substring(1) : pathFlag;
        AndroidFeatureFlag flag = flags.get(name);
        if (flag != null && !flag.readOnly()) {
          pathRuntimeFlag = pathFlag;
        }
      }
    }
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    Set<String> assigned = new HashSet<>();
    Deque<OpenElement> stack = new ArrayDeque<>();
    for (int position = 0; position < source.length(); ) {
      if (source.charAt(position) != '<') {
        position++;
        continue;
      }
      if (source.startsWith("<!--", position)) {
        position = skip(source, position, "-->");
        continue;
      }
      if (source.startsWith("<![CDATA[", position)) {
        position = skip(source, position, "]]>");
        continue;
      }
      if (source.startsWith("<?", position)) {
        position = skip(source, position, "?>");
        continue;
      }
      int end = tagEnd(source, position);
      String token = source.substring(position + 1, end).trim();
      if (token.startsWith("!")) {
        throw invalid("UNSUPPORTED_SKELETON_MARKUP", "Unsupported Android XML declaration");
      }
      if (token.startsWith("/")) {
        OpenElement current = stack.pop();
        OpenElement parent = stack.peek();
        addSlot(catalog, current, parent, position, source, encoding, slots, assigned);
      } else {
        boolean empty = token.endsWith("/");
        if (empty) {
          token = token.substring(0, token.length() - 1).trim();
        }
        String name = token.split("\\s+", 2)[0];
        Map<String, String> attributes = attributes(token.substring(name.length()));
        OpenElement parent = stack.peek();
        boolean arrayItem = parent != null && isArray(parent) && "item".equals(name);
        int arrayIndex = arrayItem ? parent.nextIndex : -1;
        OpenElement current =
            new OpenElement(name, attributes, end + 1, arrayIndex, parent, flags, pathRuntimeFlag);
        if (arrayItem && current.enabled) {
          parent.nextIndex++;
        }
        if (empty) {
          String key = identity(current, parent);
          if (key != null && catalog.messages().containsKey(key)) {
            addEmptySlot(
                catalog, current, parent, position, end, source, encoding, slots, assigned);
          }
        } else {
          stack.push(current);
        }
      }
      position = end + 1;
    }
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      if (entry.getValue().variants() == null && !assigned.contains(entry.getKey())) {
        throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing scalar Android source slot");
      }
      if (entry.getValue().variants() != null) {
        for (String variant : entry.getValue().variants().keySet()) {
          if (!assigned.contains(entry.getKey() + "#" + variant)) {
            throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing plural Android source slot");
          }
        }
      }
    }
    return new LocalizationSourceSkeleton(
        1,
        LocalizationFileFormat.ANDROID.id(),
        encoding.name(),
        source,
        resourcePath,
        featureFlags == null || featureFlags.isEmpty() ? null : featureFlags,
        slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    return render(skeleton, translations, null);
  }

  static byte[] render(
      LocalizationSourceSkeleton skeleton,
      Map<String, String> translations,
      LocalizationCatalog resolvedCatalog) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported source-skeleton version");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog =
        resolvedCatalog != null
            ? resolvedCatalog
            : skeleton.androidFeatureFlags() == null && skeleton.androidResourcePath() == null
                ? LocalizationFileConverters.parse(LocalizationFileFormat.ANDROID, original)
                : LocalizationFileConverters.parseWithAndroidFeatureFlags(
                    LocalizationFileFormat.ANDROID,
                    original,
                    null,
                    skeleton.androidResourcePath(),
                    skeleton.androidFeatureFlags() == null
                        ? List.of()
                        : skeleton.androidFeatureFlags(),
                    null);
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (!known.add(slot.translationKey())) {
        throw invalid("INVALID_SKELETON", "Duplicate source-skeleton slot");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no original source slot");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Overlapping or out-of-range source-skeleton slot");
      }
      output.write(original, previous, slot.start() - previous);
      String replacement = translations.get(slot.translationKey());
      if (replacement == null) {
        output.write(original, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null
            || (slot.variant() != null
                && (message.variants() == null
                    || !message.variants().containsKey(slot.variant())))) {
          throw invalid("INVALID_SKELETON", "Source-skeleton slot has no canonical descriptor");
        }
        String body = encoding.decode(original, slot.start(), slot.end());
        String rendered = AndroidResourcesWriter.render(message, replacement, slot.variant());
        String preserved;
        if (body.startsWith("/") && body.endsWith(">")) {
          String prefix = encoding.decode(original, encoding.bom().length, slot.start());
          int begin = prefix.lastIndexOf('<');
          if (begin < 0) {
            throw invalid("INVALID_SKELETON", "Self-closing Android slot has no opening element");
          }
          String token = prefix.substring(begin + 1).stripLeading();
          String name = token.split("\\s+", 2)[0];
          preserved = ">" + rendered + "</" + name + ">";
        } else if (resolvedCatalog != null && macroReference(body)) {
          String originalValue =
              slot.variant() == null
                  ? message.defaultMessage()
                  : message.variants().get(slot.variant());
          String expanded = AndroidResourcesWriter.render(message, originalValue, slot.variant());
          preserved = preserveMarkup(expanded, rendered);
        } else if (body.contains("<!--") || body.contains("<![CDATA[") || body.contains("<?")) {
          preserved =
              tags(body, true).isEmpty()
                  ? preserveDecorations(body, rendered)
                  : preserveMarkup(body, rendered, true);
        } else {
          preserved = preserveMarkup(body, rendered);
        }
        byte[] encoded = preserved.getBytes(encoding.charset());
        output.write(encoded, 0, encoded.length);
      }
      previous = slot.end();
    }
    output.write(original, previous, original.length - previous);
    return output.toByteArray();
  }

  private static boolean macroReference(String source) {
    String value = source.trim();
    return value.startsWith("@")
        && !value.startsWith("@@@")
        && value.indexOf("macro/") > 0
        && value.indexOf('<') < 0;
  }

  private static void addSlot(
      LocalizationCatalog catalog,
      OpenElement current,
      OpenElement parent,
      int bodyEnd,
      String source,
      SourceSkeletonEncoding encoding,
      List<LocalizationSourceSlot> slots,
      Set<String> assigned) {
    String identity = identity(current, parent);
    if (identity == null) {
      return;
    }
    LocalizationMessage descriptor = catalog.messages().get(identity);
    if (descriptor == null) {
      return;
    }
    String variant =
        parent != null && isPlural(parent) ? current.attributes.get("quantity").trim() : null;
    if (variant != null
        && (descriptor.variants() == null || !descriptor.variants().containsKey(variant))) {
      return;
    }
    int start = encoding.offset(source, current.bodyStart);
    int end = encoding.offset(source, bodyEnd);
    LocalizationSourceSlot slot = new LocalizationSourceSlot(identity, variant, start, end);
    if (!assigned.add(slot.translationKey())) {
      throw invalid("INVALID_SKELETON", "Duplicate Android source-skeleton identity");
    }
    slots.add(slot);
  }

  private static void addEmptySlot(
      LocalizationCatalog catalog,
      OpenElement current,
      OpenElement parent,
      int tagStart,
      int tagEnd,
      String source,
      SourceSkeletonEncoding encoding,
      List<LocalizationSourceSlot> slots,
      Set<String> assigned) {
    String identity = identity(current, parent);
    LocalizationMessage descriptor = catalog.messages().get(identity);
    String variant =
        parent != null && isPlural(parent) ? current.attributes.get("quantity").trim() : null;
    if (descriptor == null
        || variant != null
            && (descriptor.variants() == null || !descriptor.variants().containsKey(variant))) {
      return;
    }
    int slash = source.lastIndexOf('/', tagEnd);
    if (slash < tagStart) {
      throw invalid("INVALID_SKELETON", "Self-closing Android source has no closing slash");
    }
    LocalizationSourceSlot slot =
        new LocalizationSourceSlot(
            identity, variant, encoding.offset(source, slash), encoding.offset(source, tagEnd + 1));
    if (!assigned.add(slot.translationKey())) {
      throw invalid("INVALID_SKELETON", "Duplicate self-closing Android source identity");
    }
    slots.add(slot);
  }

  private static String identity(OpenElement current, OpenElement parent) {
    if (parent == null) {
      return null;
    }
    String currentName = localName(current.name);
    String parentName = localName(parent.name);
    if (!current.enabled
        || !parent.enabled
        || !current.name.equals(currentName)
        || !parent.name.equals(parentName)) {
      return null;
    }
    if ("resources".equals(parentName)
        && ("string".equals(currentName)
            || ("item".equals(currentName) && "string".equals(current.attributes.get("type"))))) {
      return product(
          current.attributes.get("name"), current.attributes.get("product"), current.runtimeFlag);
    }
    if (!"item".equals(currentName)) {
      return null;
    }
    if (isPlural(parent)) {
      return product(
          parent.attributes.get("name"), parent.attributes.get("product"), parent.runtimeFlag);
    }
    if (isArray(parent)) {
      return product(
              parent.attributes.get("name"), parent.attributes.get("product"), parent.runtimeFlag)
          + "["
          + current.arrayIndex
          + "]";
    }
    return null;
  }

  private static boolean isArray(OpenElement element) {
    String name = localName(element.name);
    return "array".equals(name)
        || "string-array".equals(name)
        || "integer-array".equals(name)
        || ("bag".equals(name)
            && Set.of("array", "string-array", "integer-array")
                .contains(element.attributes.get("type")));
  }

  private static boolean isPlural(OpenElement element) {
    return "plurals".equals(localName(element.name))
        || ("bag".equals(localName(element.name))
            && "plurals".equals(element.attributes.get("type")));
  }

  private static String product(String name, String product, String runtimeFlag) {
    if (name == null) {
      return null;
    }
    name = name.trim();
    product = product == null ? "" : product.trim();
    String identity =
        product.isEmpty() || "default".equals(product) ? name : name + "@product=" + product;
    return runtimeFlag == null ? identity : identity + "@flag=" + runtimeFlag;
  }

  private static Map<String, String> attributes(String source) {
    Matcher matcher = ATTRIBUTE.matcher(source);
    Map<String, String> result = new HashMap<>();
    while (matcher.find()) {
      result.put(matcher.group(1), StringEscapeUtils.unescapeXml(matcher.group(3)));
    }
    return result;
  }

  private static Map<String, String> markupAttributes(String source) {
    Map<String, String> result = new HashMap<>();
    for (Map.Entry<String, String> attribute : attributes(source).entrySet()) {
      if (attribute.getKey().equals("xmlns") || attribute.getKey().startsWith("xmlns:")) {
        continue;
      }
      String name = localName(attribute.getKey());
      if (result.put(name, attribute.getValue()) != null) {
        throw invalid(
            "INVALID_SKELETON_MARKUP",
            "Android inline attributes have ambiguous native local names");
      }
    }
    return result;
  }

  private static String preserveMarkup(String original, String translated) {
    return preserveMarkup(original, translated, false);
  }

  private static String preserveMarkup(String original, String translated, boolean decorated) {
    List<MarkupTag> source = tags(original, decorated);
    List<MarkupTag> target = tags(translated);
    if (source.size() != target.size()) {
      throw invalid(
          "INVALID_SKELETON_MARKUP", "Translated Android inline markup changed structure");
    }
    List<Integer> parents = new ArrayList<>();
    List<Integer> closing = new ArrayList<>();
    Deque<Integer> opened = new ArrayDeque<>();
    for (int index = 0; index < source.size(); index++) {
      MarkupTag tag = source.get(index);
      parents.add(opened.peek());
      closing.add(null);
      if (tag.closing()) {
        if (opened.isEmpty()
            || !localName(source.get(opened.peek()).name()).equals(localName(tag.name()))) {
          throw invalid("INVALID_SKELETON_MARKUP", "Original Android inline markup is unbalanced");
        }
        closing.set(opened.pop(), index);
      } else if (!tag.selfClosing()) {
        opened.push(index);
      }
    }
    if (!opened.isEmpty()) {
      throw invalid("INVALID_SKELETON_MARKUP", "Original Android inline markup is unbalanced");
    }
    Map<Integer, List<String>> sections = new HashMap<>();
    if (decorated) {
      int previous = 0;
      for (int index = 0; index < source.size(); index++) {
        sections
            .computeIfAbsent(
                parents.get(index) == null ? -1 : parents.get(index), unused -> new ArrayList<>())
            .add(original.substring(previous, source.get(index).start()));
        previous = source.get(index).end();
      }
      sections.computeIfAbsent(-1, unused -> new ArrayList<>()).add(original.substring(previous));
    }

    StringBuilder result = new StringBuilder(translated.length());
    Set<Integer> assigned = new HashSet<>();
    Deque<Integer> targetParents = new ArrayDeque<>();
    Map<Integer, Integer> sectionOffsets = new HashMap<>();
    int previous = 0;
    for (MarkupTag second : target) {
      Integer sectionParent = targetParents.peek();
      Integer selected = null;
      if (second.closing()) {
        if (targetParents.isEmpty()) {
          throw invalid(
              "INVALID_SKELETON_MARKUP", "Translated Android inline markup is unbalanced");
        }
        int parent = targetParents.pop();
        if (!localName(source.get(parent).name()).equals(localName(second.name()))) {
          throw invalid(
              "INVALID_SKELETON_MARKUP", "Translated Android inline markup changed nesting");
        }
        selected = closing.get(parent);
      } else {
        Map<String, String> targetAttributes =
            markupAttributes(translated.substring(second.start(), second.end()));
        Integer parent = targetParents.peek();
        for (int index = 0; index < source.size(); index++) {
          MarkupTag candidate = source.get(index);
          if (candidate.closing()
              || candidate.selfClosing() != second.selfClosing()
              || assigned.contains(index)
              || !java.util.Objects.equals(parents.get(index), parent)
              || !localName(candidate.name()).equals(localName(second.name()))
              || !markupAttributes(original.substring(candidate.start(), candidate.end()))
                  .equals(targetAttributes)) {
            continue;
          }
          if (selected != null) {
            throw invalid(
                "INVALID_SKELETON_MARKUP", "Translated Android inline markup is ambiguous");
          }
          selected = index;
        }
        if (selected != null && !second.selfClosing()) {
          targetParents.push(selected);
        }
      }
      if (selected == null || !assigned.add(selected)) {
        throw invalid(
            "INVALID_SKELETON_MARKUP", "Translated Android inline markup changed identity");
      }
      MarkupTag first = source.get(selected);
      String text = translated.substring(previous, second.start());
      if (decorated) {
        int parent = sectionParent == null ? -1 : sectionParent;
        int offset = sectionOffsets.getOrDefault(parent, 0);
        List<String> candidates = sections.get(parent);
        if (candidates == null || offset >= candidates.size()) {
          throw invalid("INVALID_SKELETON_MARKUP", "Translated Android text changed nesting");
        }
        result.append(preserveDecorations(candidates.get(offset), text));
        sectionOffsets.put(parent, offset + 1);
      } else {
        result.append(text);
      }
      result.append(original, first.start(), first.end());
      previous = second.end();
    }
    if (!targetParents.isEmpty() || assigned.size() != source.size()) {
      throw invalid("INVALID_SKELETON_MARKUP", "Translated Android inline markup is unbalanced");
    }
    String remaining = translated.substring(previous);
    if (decorated) {
      int offset = sectionOffsets.getOrDefault(-1, 0);
      List<String> root = sections.get(-1);
      if (root == null || offset >= root.size()) {
        throw invalid("INVALID_SKELETON_MARKUP", "Translated Android root text changed nesting");
      }
      result.append(preserveDecorations(root.get(offset), remaining));
      sectionOffsets.put(-1, offset + 1);
      for (Map.Entry<Integer, List<String>> entry : sections.entrySet()) {
        if (sectionOffsets.getOrDefault(entry.getKey(), 0) != entry.getValue().size()) {
          throw invalid("INVALID_SKELETON_MARKUP", "Translated Android decorations lost ownership");
        }
      }
      return result.toString();
    }
    return result.append(remaining).toString();
  }

  private static String preserveDecorations(String original, String translated) {
    List<DecoratedPart> parts = new ArrayList<>();
    int position = 0;
    while (position < original.length()) {
      int comment = original.indexOf("<!--", position);
      int cdata = original.indexOf("<![CDATA[", position);
      int instruction = original.indexOf("<?", position);
      int next = original.length();
      if (comment >= 0) {
        next = Math.min(next, comment);
      }
      if (cdata >= 0) {
        next = Math.min(next, cdata);
      }
      if (instruction >= 0) {
        next = Math.min(next, instruction);
      }
      if (next == original.length()) {
        next = -1;
      }
      if (next < 0) {
        parts.add(new DecoratedPart(Decoration.TEXT, original.substring(position)));
        break;
      }
      if (next > position) {
        parts.add(new DecoratedPart(Decoration.TEXT, original.substring(position, next)));
      }
      if (next == comment) {
        int end = skip(original, next, "-->");
        parts.add(new DecoratedPart(Decoration.COMMENT, original.substring(next, end)));
        position = end;
      } else if (next == instruction) {
        int end = skip(original, next, "?>");
        parts.add(new DecoratedPart(Decoration.INSTRUCTION, original.substring(next, end)));
        position = end;
      } else {
        int end = skip(original, next, "]]>");
        parts.add(
            new DecoratedPart(
                Decoration.CDATA, original.substring(next + "<![CDATA[".length(), end - 3)));
        position = end;
      }
    }
    for (DecoratedPart part : parts) {
      if (part.kind() == Decoration.TEXT && part.source().indexOf('<') >= 0) {
        throw invalid(
            "UNSUPPORTED_SKELETON_MARKUP",
            "Mixed Android style tags and source decorations need token-level ownership");
      }
    }
    if (parts.stream().noneMatch(part -> visible(part.kind()))) {
      parts.add(new DecoratedPart(Decoration.TEXT, ""));
    }
    List<XmlAtom> atoms = xmlAtoms(translated);
    int contentParts = 0;
    for (DecoratedPart part : parts) {
      if (visible(part.kind())) {
        contentParts++;
      }
    }
    int current = 0;
    int remainingParts = contentParts;
    StringBuilder result = new StringBuilder(original.length() + translated.length());
    for (DecoratedPart part : parts) {
      if (!visible(part.kind())) {
        result.append(part.source());
        continue;
      }
      remainingParts--;
      int count =
          remainingParts == 0
              ? atoms.size() - current
              : Math.min(xmlAtoms(part.source()).size(), atoms.size() - current);
      if (part.kind() == Decoration.CDATA) {
        result.append("<![CDATA[");
      }
      StringBuilder cdata = part.kind() == Decoration.CDATA ? new StringBuilder() : null;
      for (int index = 0; index < count; index++) {
        XmlAtom atom = atoms.get(current++);
        if (cdata == null) {
          result.append(atom.lexical());
        } else {
          cdata.append(atom.decoded());
        }
      }
      if (cdata != null) {
        result.append(cdata.toString().replace("]]>", "]]]]><![CDATA[>"));
        result.append("]]>");
      }
    }
    return result.toString();
  }

  private static List<XmlAtom> xmlAtoms(String value) {
    List<XmlAtom> result = new ArrayList<>();
    for (int index = 0; index < value.length(); ) {
      int character = value.codePointAt(index);
      if (character == '&') {
        int end = value.indexOf(';', index + 1);
        if (end >= 0) {
          String lexical = value.substring(index, end + 1);
          String decoded =
              switch (lexical) {
                case "&amp;" -> "&";
                case "&lt;" -> "<";
                case "&gt;" -> ">";
                case "&quot;" -> "\"";
                case "&apos;" -> "'";
                default -> null;
              };
          if (decoded != null) {
            result.add(new XmlAtom(lexical, decoded));
            index = end + 1;
            continue;
          }
        }
      }
      String lexical = new String(Character.toChars(character));
      result.add(new XmlAtom(lexical, lexical));
      index += Character.charCount(character);
    }
    return result;
  }

  private static List<MarkupTag> tags(String source) {
    return tags(source, false);
  }

  private static List<MarkupTag> tags(String source, boolean decorations) {
    List<MarkupTag> result = new ArrayList<>();
    for (int position = 0; position < source.length(); position++) {
      if (source.charAt(position) != '<') {
        continue;
      }
      if (source.startsWith("<!--", position)
          || source.startsWith("<![CDATA[", position)
          || source.startsWith("<?", position)) {
        if (decorations) {
          position =
              skip(
                      source,
                      position,
                      source.startsWith("<!--", position)
                          ? "-->"
                          : source.startsWith("<?", position) ? "?>" : "]]>")
                  - 1;
          continue;
        }
        throw invalid("UNSUPPORTED_SKELETON_MARKUP", "Unsupported Android inline XML content");
      }
      int end = tagEnd(source, position);
      String token = source.substring(position + 1, end).trim();
      boolean closing = token.startsWith("/");
      if (closing) {
        token = token.substring(1).trim();
      }
      String name = token.split("\\s+", 2)[0];
      boolean selfClosing = !closing && token.endsWith("/");
      if (selfClosing && name.endsWith("/")) {
        name = name.substring(0, name.length() - 1);
      }
      result.add(new MarkupTag(name, closing, selfClosing, position, end + 1));
      position = end;
    }
    return result;
  }

  private static int tagEnd(String source, int start) {
    char quote = 0;
    for (int index = start + 1; index < source.length(); index++) {
      char value = source.charAt(index);
      if (quote == 0 && (value == '\'' || value == '"')) {
        quote = value;
      } else if (value == quote) {
        quote = 0;
      } else if (value == '>' && quote == 0) {
        return index;
      }
    }
    throw invalid("INVALID_SKELETON", "Unterminated Android XML tag");
  }

  private static int skip(String source, int start, String delimiter) {
    int end = source.indexOf(delimiter, start);
    if (end < 0) {
      throw invalid("INVALID_SKELETON", "Unterminated Android XML section");
    }
    return end + delimiter.length();
  }

  private static String localName(String name) {
    return name.substring(name.lastIndexOf(':') + 1);
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private static final class OpenElement {

    private final String name;
    private final Map<String, String> attributes;
    private final int bodyStart;
    private final int arrayIndex;
    private final Map<String, String> namespaces;
    private final String runtimeFlag;
    private final boolean enabled;
    private int nextIndex;

    private OpenElement(
        String name,
        Map<String, String> attributes,
        int bodyStart,
        int arrayIndex,
        OpenElement parent,
        Map<String, AndroidFeatureFlag> flags,
        String pathRuntimeFlag) {
      this.name = name;
      this.attributes = attributes;
      this.bodyStart = bodyStart;
      this.arrayIndex = arrayIndex;
      namespaces = new HashMap<>(parent == null ? Map.of() : parent.namespaces);
      for (Map.Entry<String, String> attribute : attributes.entrySet()) {
        if (attribute.getKey().startsWith("xmlns:")) {
          namespaces.put(attribute.getKey().substring("xmlns:".length()), attribute.getValue());
        }
      }
      String expression = null;
      boolean sourceCondition =
          parent != null
              && name.equals(localName(name))
              && ("resources".equals(parent.name) || isArray(parent) && "item".equals(name));
      if (sourceCondition) {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
          int colon = attribute.getKey().indexOf(':');
          if (colon >= 0
              && "featureFlag".equals(attribute.getKey().substring(colon + 1))
              && ANDROID_NAMESPACE.equals(namespaces.get(attribute.getKey().substring(0, colon)))) {
            expression = attribute.getValue().trim();
            break;
          }
        }
      }
      if (expression == null && parent != null && "resources".equals(parent.name)) {
        expression = pathRuntimeFlag;
      }
      if (expression == null || expression.isEmpty()) {
        runtimeFlag = null;
        enabled = true;
      } else {
        boolean negated = expression.startsWith("!");
        AndroidFeatureFlag flag = flags.get(negated ? expression.substring(1) : expression);
        if (flag == null) {
          throw invalid("UNRESOLVED_ANDROID_FEATURE_FLAG", "Missing Android source feature flag");
        }
        runtimeFlag = flag.readOnly() ? null : expression;
        enabled = !flag.readOnly() || flag.value() != null && flag.value() != negated;
      }
    }
  }

  private record MarkupTag(String name, boolean closing, boolean selfClosing, int start, int end) {}

  private record XmlAtom(String lexical, String decoded) {}

  private record DecoratedPart(Decoration kind, String source) {}

  private static boolean visible(Decoration decoration) {
    return decoration != Decoration.COMMENT && decoration != Decoration.INSTRUCTION;
  }

  private enum Decoration {
    TEXT,
    COMMENT,
    INSTRUCTION,
    CDATA
  }
}
