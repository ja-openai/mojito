package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Typed, compiler-valid Android attribute declarations needed by translated theme references. */
final class AndroidAttributeDependencies {

  static final String METADATA = "androidAttributeDependencies";
  static final String STYLEABLE_METADATA = "androidStyleableDependencies";
  private static final String AUTO_NAMESPACE = "http://schemas.android.com/apk/res-auto";
  private static final String PUBLIC_NAMESPACE = "http://schemas.android.com/apk/res/";
  private static final String PRIVATE_NAMESPACE = "http://schemas.android.com/apk/prv/res/";
  private static final List<String> FORMATS =
      List.of(
          "reference",
          "string",
          "integer",
          "boolean",
          "color",
          "float",
          "dimension",
          "fraction",
          "enum",
          "flags");
  private static final Set<String> DEPENDENCY_FIELDS =
      Set.of("name", "format", "min", "max", "symbols", "generic", "weak");
  private static final Set<String> SYMBOL_FIELDS = Set.of("kind", "name", "value");
  private static final Set<String> STYLEABLE_FIELDS = Set.of("name", "generic", "attributes");

  record Collected(
      Map<String, Map<String, Object>> attributes, Map<String, Map<String, Object>> styleables) {}

  private AndroidAttributeDependencies() {}

  static boolean isDeclaration(Element element) {
    return "attr".equals(element.getLocalName())
        || "bag".equals(element.getLocalName())
            && "attr".equals(element.getAttribute("type").trim());
  }

  static boolean isStyleable(Element element) {
    return "declare-styleable".equals(element.getLocalName())
        || "bag".equals(element.getLocalName())
            && "declare-styleable".equals(element.getAttribute("type").trim());
  }

  static Collected collect(
      Element root,
      Map<String, Element> externalDefinitions,
      Map<String, Element> externalStyleables,
      String applicationPackage) {
    Map<String, Map<String, Object>> definitions = new TreeMap<>();
    externalDefinitions.forEach((name, element) -> definitions.put(name, parse(element)));
    Map<String, Map<String, Object>> styleables = new TreeMap<>();
    externalStyleables.forEach(
        (name, element) ->
            styleables.put(name, styleable(element, definitions, applicationPackage)));
    Set<String> local = new LinkedHashSet<>();
    Set<String> localStyleables = new LinkedHashSet<>();
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (!(children.item(index) instanceof Element element)
          || element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty()) {
        continue;
      }
      if (isStyleable(element)) {
        String name = AndroidResourcesParser.resourceName(element);
        if (!localStyleables.add(name)) {
          throw error("DUPLICATE_ANDROID_STYLEABLE", "Duplicate Android styleable declaration");
        }
        Map<String, Object> group = styleable(element, definitions, applicationPackage);
        if (!externalStyleables.containsKey(name)) {
          styleables.put(name, group);
        }
        continue;
      }
      if (!isDeclaration(element)) {
        continue;
      }
      Map<String, Object> definition = parse(element);
      String name = (String) definition.get("name");
      if (!local.add(name)) {
        throw error("DUPLICATE_ANDROID_ATTRIBUTE", "Duplicate Android attribute declaration");
      }
      Map<String, Object> existing = definitions.get(name);
      if (existing != null
          && !compatible(existing, definition)
          && (!externalDefinitions.containsKey(name)
              || Boolean.TRUE.equals(existing.get("weak")))) {
        throw error("DUPLICATE_ANDROID_ATTRIBUTE", "Conflicting Android attribute declaration");
      }
      if (!externalDefinitions.containsKey(name)
          || existing != null && Boolean.TRUE.equals(existing.get("weak"))) {
        definitions.put(name, definition);
      }
    }
    return new Collected(definitions, styleables);
  }

  private static Map<String, Object> parse(Element element) {
    return parse(element, AndroidResourcesParser.resourceName(element));
  }

  private static Map<String, Object> parse(Element element, String name) {
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("name", name);
    if ("bag".equals(element.getLocalName())) {
      definition.put("generic", true);
    }

    Set<String> formats = new LinkedHashSet<>();
    if (element.hasAttribute("format")) {
      String source = element.getAttribute("format");
      for (String part : source.split("\\|", -1)) {
        String format = part.trim();
        if (!FORMATS.contains(format)) {
          throw error("INVALID_ANDROID_ATTRIBUTE_FORMAT", "Invalid Android attribute format");
        }
        formats.add(format);
      }
    }

    for (String bound : List.of("min", "max")) {
      if (element.hasAttribute(bound)) {
        if (!formats.contains("integer")) {
          throw error(
              "INVALID_ANDROID_ATTRIBUTE_BOUNDS",
              "Android attribute bounds require the integer format");
        }
        definition.put(bound, integer(element.getAttribute(bound), true));
      }
    }

    Map<String, Map<String, Object>> symbols = new TreeMap<>();
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (!(children.item(index) instanceof Element child)) {
        continue;
      }
      String kind = child.getLocalName();
      if ((child.getNamespaceURI() != null && !child.getNamespaceURI().isEmpty())
          || !"enum".equals(kind) && !"flag".equals(kind)) {
        if (child.getNamespaceURI() == null
            && ("skip".equals(kind) || "eat-comment".equals(kind))) {
          continue;
        }
        throw error("INVALID_ANDROID_ATTRIBUTE_SYMBOL", "Invalid Android attribute child");
      }
      String opposite = "enum".equals(kind) ? "flags" : "enum";
      if (formats.contains(opposite)) {
        throw error("INVALID_ANDROID_ATTRIBUTE_SYMBOL", "Android enum and flag symbols cannot mix");
      }
      formats.add("enum".equals(kind) ? "enum" : "flags");
      String symbolName = AndroidResourcesParser.resourceName(child);
      if (!child.hasAttribute("value")) {
        throw error("INVALID_ANDROID_ATTRIBUTE_SYMBOL", "Android attribute symbols require values");
      }
      Map<String, Object> symbol = new LinkedHashMap<>();
      symbol.put("kind", kind);
      symbol.put("name", symbolName);
      symbol.put("value", integer(child.getAttribute("value"), false));
      if (symbols.putIfAbsent(symbolName, symbol) != null) {
        throw error("INVALID_ANDROID_ATTRIBUTE_SYMBOL", "Duplicate Android attribute symbol");
      }
    }
    if (!formats.isEmpty()) {
      definition.put(
          "format", String.join("|", FORMATS.stream().filter(formats::contains).toList()));
    }
    if (!symbols.isEmpty()) {
      definition.put("symbols", new ArrayList<>(symbols.values()));
    }
    return definition;
  }

  private static Map<String, Object> styleable(
      Element element, Map<String, Map<String, Object>> definitions, String applicationPackage) {
    Map<String, Object> group = new LinkedHashMap<>();
    group.put("name", AndroidResourcesParser.resourceName(element));
    if ("bag".equals(element.getLocalName())) {
      group.put("generic", true);
    }
    List<Map<String, Object>> attributes = new ArrayList<>();
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (!(children.item(index) instanceof Element child)) {
        continue;
      }
      String kind = child.getLocalName();
      if (child.getNamespaceURI() != null && !child.getNamespaceURI().isEmpty()
          || !"attr".equals(kind)) {
        if (child.getNamespaceURI() == null
            && ("skip".equals(kind) || "eat-comment".equals(kind))) {
          continue;
        }
        throw error("INVALID_ANDROID_STYLEABLE", "Invalid child in Android styleable declaration");
      }
      String raw = child.getAttribute("name").trim();
      if (raw.isEmpty()) {
        throw error("INVALID_ANDROID_RESOURCE_NAME", "Android styleable attributes require a name");
      }
      String name = styleableAttributeName(child, raw, applicationPackage);
      Map<String, Object> definition = parse(child, name);
      attributes.add(definition);
      if (!name.contains(":") && definition.containsKey("format")) {
        Map<String, Object> weak = new LinkedHashMap<>(definition);
        weak.put("weak", true);
        Map<String, Object> previous = definitions.get(name);
        if (previous != null && !compatible(previous, weak)) {
          throw error("DUPLICATE_ANDROID_ATTRIBUTE", "Conflicting Android attribute declaration");
        }
        if (previous == null) {
          definitions.put(name, weak);
        }
      }
    }
    if (!attributes.isEmpty()) {
      group.put("attributes", attributes);
    }
    return group;
  }

  private static String styleableAttributeName(
      Element element, String raw, String applicationPackage) {
    if (raw.startsWith("*") && raw.indexOf(':') > 1) {
      raw = raw.substring(1);
    }
    int colon = raw.indexOf(':');
    if (colon < 0) {
      if (!AndroidResourcesParser.isValidResourceName(raw)) {
        throw error("INVALID_ANDROID_RESOURCE_NAME", "Invalid Android styleable attribute name");
      }
      return raw;
    }
    String prefix = raw.substring(0, colon);
    String name = raw.substring(colon + 1);
    if (prefix.isEmpty()
        || name.contains(":")
        || !AndroidResourcesParser.isValidResourceName(name)) {
      throw error("INVALID_ANDROID_RESOURCE_NAME", "Invalid Android styleable attribute name");
    }
    String namespace = element.lookupNamespaceURI(prefix);
    boolean local = AUTO_NAMESPACE.equals(namespace) || prefix.equals(applicationPackage);
    if (applicationPackage != null && namespace != null) {
      local |=
          namespace.equals(PUBLIC_NAMESPACE + applicationPackage)
              || namespace.equals(PRIVATE_NAMESPACE + applicationPackage);
    }
    return local ? name : raw;
  }

  private static boolean compatible(Map<String, Object> first, Map<String, Object> second) {
    Map<String, Object> left = new LinkedHashMap<>(first);
    Map<String, Object> right = new LinkedHashMap<>(second);
    left.remove("weak");
    right.remove("weak");
    left.remove("generic");
    right.remove("generic");
    return left.equals(right);
  }

  private static int integer(String source, boolean bound) {
    try {
      String value = source.trim();
      if (value.startsWith("0x")) {
        String digits = value.substring(2);
        if (digits.isEmpty() || !digits.matches("[0-9a-fA-F]+")) {
          throw new NumberFormatException("Invalid Android hexadecimal integer");
        }
        digits = digits.replaceFirst("^0+(?!$)", "");
        if (digits.length() > 8) {
          throw new NumberFormatException("Android hexadecimal integer exceeds 32 bits");
        }
        return (int) Long.parseLong(digits, 16);
      }
      if (!value.matches("-?[0-9]+")) {
        throw new NumberFormatException("Invalid Android decimal integer");
      }
      boolean negative = value.startsWith("-");
      String digits = negative ? value.substring(1) : value;
      digits = digits.replaceFirst("^0+(?!$)", "");
      if (digits.length() > 10) {
        throw new NumberFormatException("Android decimal integer exceeds 32 bits");
      }
      long magnitude = Long.parseLong(digits, 10);
      if (magnitude > (negative ? 2147483648L : Integer.MAX_VALUE)) {
        throw new NumberFormatException("Android integer exceeds its 32-bit range");
      }
      return (int) (negative ? -magnitude : magnitude);
    } catch (NumberFormatException invalid) {
      throw error(
          bound ? "INVALID_ANDROID_ATTRIBUTE_BOUNDS" : "INVALID_ANDROID_ATTRIBUTE_SYMBOL",
          "Android attribute values must be valid 32-bit integers");
    }
  }

  static void attach(LocalizationCatalog catalog, Collected collected, String applicationPackage) {
    Map<String, Map<String, Object>> definitions = collected.attributes();
    if (definitions.isEmpty()) {
      return;
    }
    for (LocalizationMessage message : catalog.messages().values()) {
      if (message.metadata() == null) {
        continue;
      }
      Map<String, Map<String, Object>> used = new TreeMap<>();
      for (String field : List.of("androidArrayReferences", "androidPluralReferences")) {
        if (!(message.metadata().get(field) instanceof Map<?, ?> references)) {
          continue;
        }
        for (Object raw : references.values()) {
          if (raw instanceof String reference) {
            String name = attributeName(reference, applicationPackage);
            if (name != null && definitions.containsKey(name)) {
              used.put(name, definitions.get(name));
            }
          }
        }
      }
      if (!used.isEmpty()) {
        message.metadata().put(METADATA, new ArrayList<>(used.values()));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> group : collected.styleables().values()) {
          if (!(group.get("attributes") instanceof List<?> attributes)) {
            continue;
          }
          if (attributes.stream()
              .filter(Map.class::isInstance)
              .map(Map.class::cast)
              .map(attribute -> attribute.get("name"))
              .anyMatch(used::containsKey)) {
            groups.add(group);
          }
        }
        if (!groups.isEmpty()) {
          message.metadata().put(STYLEABLE_METADATA, groups);
        }
      }
    }
  }

  private static String attributeName(String source, String applicationPackage) {
    String reference = source.trim();
    if (reference.isEmpty() || reference.charAt(0) != '?' && reference.charAt(0) != '@') {
      return null;
    }
    boolean theme = reference.charAt(0) == '?';
    reference = reference.substring(1);
    if (reference.startsWith("*")) {
      reference = reference.substring(1);
    }
    int slash = reference.indexOf('/');
    if (slash < 0) {
      return theme && reference.indexOf(':') < 0 ? reference : null;
    }
    String type = reference.substring(0, slash);
    int colon = type.indexOf(':');
    if (colon >= 0) {
      if (applicationPackage == null || !applicationPackage.equals(type.substring(0, colon))) {
        return null;
      }
      type = type.substring(colon + 1);
    }
    return "attr".equals(type) ? reference.substring(slash + 1) : null;
  }

  static void write(StringBuilder output, Map<String, LocalizationMessage> messages) {
    Map<String, Map<String, Object>> definitions = new TreeMap<>();
    Map<String, Map<String, Object>> styleables = new TreeMap<>();
    for (LocalizationMessage message : messages.values()) {
      if (message.metadata() == null) {
        continue;
      }
      if (message.metadata().containsKey(METADATA)) {
        Object raw = message.metadata().get(METADATA);
        if (!(raw instanceof List<?> dependencies) || dependencies.isEmpty()) {
          throw invalidDependency();
        }
        for (Object dependency : dependencies) {
          if (!(dependency instanceof Map<?, ?> value)) {
            throw invalidDependency();
          }
          Map<String, Object> definition = validate(value, false);
          String name = (String) definition.get("name");
          Map<String, Object> previous = definitions.putIfAbsent(name, definition);
          if (previous != null && !previous.equals(definition)) {
            throw invalidDependency();
          }
        }
      }
      if (message.metadata().containsKey(STYLEABLE_METADATA)) {
        if (!(message.metadata().get(STYLEABLE_METADATA) instanceof List<?> groups)
            || groups.isEmpty()
            || !message.metadata().containsKey(METADATA)) {
          throw invalidDependency();
        }
        for (Object raw : groups) {
          if (!(raw instanceof Map<?, ?> value)) {
            throw invalidDependency();
          }
          Map<String, Object> group = validateStyleable(value);
          String name = (String) group.get("name");
          Map<String, Object> previous = styleables.putIfAbsent(name, group);
          if (previous != null && !previous.equals(group)) {
            throw invalidDependency();
          }
        }
      }
    }

    for (Map<String, Object> definition : definitions.values()) {
      if (Boolean.TRUE.equals(definition.get("weak"))) {
        if (styleables.values().stream()
            .map(group -> group.get("attributes"))
            .filter(List.class::isInstance)
            .map(List.class::cast)
            .flatMap(List::stream)
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .noneMatch(
                attribute -> definition.get("name").equals(((Map<?, ?>) attribute).get("name")))) {
          throw invalidDependency();
        }
      } else {
        appendAttribute(output, definition, "  ");
      }
    }

    for (Map<String, Object> group : styleables.values()) {
      boolean generic = Boolean.TRUE.equals(group.get("generic"));
      output.append(
          generic ? "  <bag type=\"declare-styleable\" name=\"" : "  <declare-styleable name=\"");
      output.append(escape((String) group.get("name"))).append('"');
      output.append(">\n");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> attributes = (List<Map<String, Object>>) group.get("attributes");
      for (Map<String, Object> attribute : attributes) {
        appendAttribute(output, attribute, "    ");
      }
      output.append(generic ? "  </bag>\n" : "  </declare-styleable>\n");
    }
  }

  private static void appendAttribute(
      StringBuilder output, Map<String, Object> definition, String indentation) {
    boolean generic = Boolean.TRUE.equals(definition.get("generic"));
    output.append(indentation).append(generic ? "<bag type=\"attr\" name=\"" : "<attr name=\"");
    output.append(escape((String) definition.get("name"))).append('"');
    for (String field : List.of("format", "min", "max")) {
      if (definition.containsKey(field)) {
        output
            .append(' ')
            .append(field)
            .append("=\"")
            .append(escape(definition.get(field).toString()))
            .append('"');
      }
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> symbols =
        (List<Map<String, Object>>) definition.getOrDefault("symbols", List.of());
    if (symbols.isEmpty()) {
      output.append(" />\n");
      return;
    }
    output.append(">\n");
    for (Map<String, Object> symbol : symbols) {
      output
          .append(indentation)
          .append("  <")
          .append(symbol.get("kind"))
          .append(" name=\"")
          .append(escape((String) symbol.get("name")))
          .append("\" value=\"")
          .append(symbol.get("value"))
          .append("\" />\n");
    }
    output.append(indentation).append(generic ? "</bag>\n" : "</attr>\n");
  }

  private static Map<String, Object> validateStyleable(Map<?, ?> source) {
    if (!source.keySet().stream().allMatch(STYLEABLE_FIELDS::contains)
        || !(source.get("name") instanceof String name)
        || !validName(name)
        || !(source.get("attributes") instanceof List<?> attributes)
        || attributes.isEmpty()
        || source.containsKey("generic") && !Boolean.TRUE.equals(source.get("generic"))) {
      throw invalidDependency();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    if (source.containsKey("generic")) {
      result.put("generic", true);
    }
    List<Map<String, Object>> normalized = new ArrayList<>();
    for (Object raw : attributes) {
      if (!(raw instanceof Map<?, ?> attribute)
          || attribute.containsKey("weak")
          || attribute.containsKey("generic")) {
        throw invalidDependency();
      }
      normalized.add(validate(attribute, true));
    }
    result.put("attributes", normalized);
    return result;
  }

  private static Map<String, Object> validate(Map<?, ?> source, boolean qualifiedName) {
    if (!source.keySet().stream().allMatch(DEPENDENCY_FIELDS::contains)
        || !(source.get("name") instanceof String name)
        || !(qualifiedName ? validStyleableName(name) : validName(name))) {
      throw invalidDependency();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    if (source.containsKey("generic")) {
      if (!Boolean.TRUE.equals(source.get("generic"))) {
        throw invalidDependency();
      }
      result.put("generic", true);
    }
    if (source.containsKey("weak")) {
      if (!Boolean.TRUE.equals(source.get("weak")) || source.containsKey("generic")) {
        throw invalidDependency();
      }
      result.put("weak", true);
    }
    Set<String> formats = new LinkedHashSet<>();
    if (source.containsKey("format")) {
      if (!(source.get("format") instanceof String format)) {
        throw invalidDependency();
      }
      for (String token : format.split("\\|", -1)) {
        if (!FORMATS.contains(token) || !formats.add(token)) {
          throw invalidDependency();
        }
      }
      String normalized = String.join("|", FORMATS.stream().filter(formats::contains).toList());
      if (!normalized.equals(format)) {
        throw invalidDependency();
      }
      result.put("format", format);
    }
    for (String bound : List.of("min", "max")) {
      if (source.containsKey(bound)) {
        if (!(source.get(bound) instanceof Number value)
            || !formats.contains("integer")
            || !(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)
            || value.longValue() < Integer.MIN_VALUE
            || value.longValue() > Integer.MAX_VALUE) {
          throw invalidDependency();
        }
        result.put(bound, value.intValue());
      }
    }
    if (source.containsKey("symbols")) {
      if (!(source.get("symbols") instanceof List<?> symbols) || symbols.isEmpty()) {
        throw invalidDependency();
      }
      Map<String, Map<String, Object>> ordered = new TreeMap<>();
      String kind = null;
      for (Object raw : symbols) {
        if (!(raw instanceof Map<?, ?> symbol)
            || !symbol.keySet().equals(SYMBOL_FIELDS)
            || !(symbol.get("kind") instanceof String current)
            || !Set.of("enum", "flag").contains(current)
            || kind != null && !kind.equals(current)
            || !(symbol.get("name") instanceof String symbolName)
            || !validName(symbolName)
            || !(symbol.get("value") instanceof Number value)
            || !(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)
            || value.longValue() < Integer.MIN_VALUE
            || value.longValue() > Integer.MAX_VALUE) {
          throw invalidDependency();
        }
        kind = current;
        if (!formats.contains("enum".equals(kind) ? "enum" : "flags")) {
          throw invalidDependency();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("kind", kind);
        normalized.put("name", symbolName);
        normalized.put("value", value.intValue());
        if (ordered.putIfAbsent(symbolName, normalized) != null) {
          throw invalidDependency();
        }
      }
      result.put("symbols", new ArrayList<>(ordered.values()));
    }
    return result;
  }

  private static boolean validName(String name) {
    return AndroidResourcesParser.isValidResourceName(name);
  }

  private static boolean validStyleableName(String name) {
    int separator = name.indexOf(':');
    if (separator < 0) {
      return validName(name);
    }
    return separator > 0
        && name.indexOf(':', separator + 1) < 0
        && validName(name.substring(separator + 1))
        && name.substring(0, separator).matches("[A-Za-z_][A-Za-z0-9_.-]*");
  }

  private static String escape(String source) {
    return source
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static LocalizationParseException invalidDependency() {
    return error("INVALID_ANDROID_ATTRIBUTE_DEPENDENCY", "Invalid Android attribute dependency");
  }

  private static LocalizationParseException error(String code, String message) {
    return new LocalizationParseException(code, message);
  }
}
