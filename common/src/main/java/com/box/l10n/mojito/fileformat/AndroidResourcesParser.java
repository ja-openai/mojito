package com.box.l10n.mojito.fileformat;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class AndroidResourcesParser {

  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
  private static final String XLIFF_NAMESPACE = "urn:oasis:names:tc:xliff:document:1.2";
  private static final String TOOLS_NAMESPACE = "http://schemas.android.com/tools";
  private static final Set<String> PLURAL_CATEGORIES =
      Set.of("zero", "one", "two", "few", "many", "other");
  private static final Pattern LITERAL_MARKUP = Pattern.compile("</?[A-Za-z][^>]*>");
  private static final Pattern XLIFF_IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}\\p{M}\\p{So}_]+");
  private static final Set<String> GENERIC_FORMATS =
      Set.of(
          "reference", "string", "integer", "boolean", "color", "float", "dimension", "fraction");
  private static final Set<String> BAG_TYPES =
      Set.of(
          "add-resource",
          "array",
          "attr",
          "configVarying",
          "declare-styleable",
          "integer-array",
          "java-symbol",
          "overlayable",
          "plurals",
          "public",
          "public-group",
          "staging-public-group",
          "staging-public-group-final",
          "string-array",
          "style",
          "symbol");
  private static final Pattern ANDROID_INTEGER =
      Pattern.compile("[+-]?(?:0[xX][0-9A-Fa-f]+|[0-9]+)");
  private static final Pattern ANDROID_NUMBER =
      Pattern.compile("[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
  private static final Pattern ANDROID_COLOR =
      Pattern.compile("#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

  private AndroidResourceConfiguration configuration;
  private Map<String, Boolean> featureFlags = Map.of();
  private FeatureCondition pathFeatureCondition;
  private String applicationPackage;
  private boolean literalMarkup;

  LocalizationCatalog parse(String source) {
    return parse(source, null);
  }

  LocalizationCatalog parse(String source, String resourcePath) {
    return parse(source, resourcePath, Map.of());
  }

  LocalizationCatalog parse(
      String source, String resourcePath, Map<String, Boolean> configuredFeatureFlags) {
    return parse(source, resourcePath, configuredFeatureFlags, Map.of(), null);
  }

  LocalizationCatalog parse(
      String source,
      String resourcePath,
      Map<String, Boolean> configuredFeatureFlags,
      Map<String, Element> externalMacros,
      String androidApplicationPackage) {
    return parse(
        source,
        resourcePath,
        configuredFeatureFlags,
        externalMacros,
        Map.of(),
        Map.of(),
        androidApplicationPackage);
  }

  LocalizationCatalog parse(
      String source,
      String resourcePath,
      Map<String, Boolean> configuredFeatureFlags,
      Map<String, Element> externalMacros,
      Map<String, Element> externalAttributes,
      Map<String, Element> externalStyleables,
      String androidApplicationPackage) {
    configuration = AndroidResourceConfiguration.parse(resourcePath);
    featureFlags = Map.copyOf(configuredFeatureFlags);
    applicationPackage = androidApplicationPackage;
    pathFeatureCondition =
        configuration == null || configuration.pathFeatureFlag() == null
            ? null
            : featureCondition(configuration.pathFeatureFlag(), featureFlags);
    boolean fileTranslatable =
        resourcePath == null
            || !resourcePath
                .substring(resourcePath.lastIndexOf('/') + 1)
                .startsWith("donottranslate");
    Document document = SecureXmlParser.parse(source);
    Element root = document.getDocumentElement();
    if (!"resources".equals(root.getLocalName())
        || (root.getNamespaceURI() != null && !root.getNamespaceURI().isEmpty())) {
      throw new LocalizationParseException("INVALID_XML", "Android root must be <resources>");
    }
    expandMacros(root, externalMacros);
    AndroidAttributeDependencies.Collected attributeDependencies =
        AndroidAttributeDependencies.collect(
            root, externalAttributes, externalStyleables, applicationPackage);
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.ANDROID);
    if (root.hasAttributeNS(TOOLS_NAMESPACE, "locale")) {
      catalog.setLocale(root.getAttributeNS(TOOLS_NAMESPACE, "locale").replace('_', '-'));
    }
    if (configuration != null && configuration.locale() != null) {
      catalog.setLocale(configuration.locale());
    }
    String comment = "";
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.COMMENT_NODE) {
        comment = child.getNodeValue().trim();
      } else if (child.getNodeType() == Node.TEXT_NODE
          || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        if (!child.getNodeValue().trim().isEmpty()) {
          throw new LocalizationParseException(
              "INVALID_ANDROID_STRUCTURE", "Plain text is not allowed under Android resources");
        }
      } else if (child instanceof Element element) {
        if (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty()) {
          continue;
        }
        if ("skip".equals(element.getLocalName()) || "eat-comment".equals(element.getLocalName())) {
          comment = "";
          continue;
        }
        if (isMacroDeclaration(element)) {
          comment = "";
          continue;
        }
        FeatureCondition condition = featureCondition(element, featureFlags);
        if (pathFeatureCondition != null && condition != null) {
          throw new LocalizationParseException(
              "CONFLICTING_ANDROID_FEATURE_FLAG",
              "Android feature flags are not allowed in both the resource path and file");
        }
        if (pathFeatureCondition != null) {
          if ("style".equals(element.getLocalName())
              || "bag".equals(element.getLocalName())
                  && "style".equals(element.getAttribute("type").trim())) {
            for (Element item : directChildren(element, "item")) {
              if (featureCondition(item, featureFlags) != null) {
                throw new LocalizationParseException(
                    "CONFLICTING_ANDROID_FEATURE_FLAG",
                    "Android feature flags are not allowed in both the resource path and file");
              }
            }
          }
          condition = pathFeatureCondition;
        }
        LocalizationCatalog destination =
            condition == null || condition.enabled()
                ? catalog
                : new LocalizationCatalog(LocalizationFileFormat.ANDROID);
        String description = comment;
        comment = "";
        switch (element.getLocalName()) {
          case "string" -> {
            resourceName(element);
            booleanAttribute(element, "formatted", true);
            if (booleanAttribute(element, "translatable", fileTranslatable)) {
              addString(destination, element, description, null);
            } else {
              validateProtectedString(element);
            }
          }
          case "item" -> {
            String type = element.getAttribute("type").trim();
            if (!AndroidResourceReferences.isResourceType(type)) {
              throw invalidStructure("Android generic items require a known resource type");
            }
            if ("string".equals(type)) {
              resourceName(element);
              String format = genericFormat(element);
              if ("string".equals(format)) {
                booleanAttribute(element, "formatted", true);
                if (!booleanAttribute(element, "translatable", fileTranslatable)) {
                  validateProtectedString(element);
                  break;
                }
              }
              addString(destination, element, description, null);
            }
          }
          case "plurals" -> {
            resourceName(element);
            if (!isAndroidFalse(element.getAttribute("translatable").trim())) {
              addPlural(destination, element, description, null);
            } else {
              validateProtectedItems(element);
            }
          }
          case "array", "integer-array", "string-array" -> {
            resourceName(element);
            validateArrayFeatureFlags(element);
            String format =
                switch (element.getLocalName()) {
                  case "array" -> genericFormat(element);
                  case "integer-array" -> "integer";
                  default -> "string";
                };
            if (booleanAttribute(element, "translatable", fileTranslatable)) {
              addArray(destination, element, description, format, null);
            } else {
              validateProtectedItems(element);
            }
          }
          case "bag" -> {
            String type = element.getAttribute("type").trim();
            if (!BAG_TYPES.contains(type)) {
              throw invalidStructure("Android generic bags require a known bag resource type");
            }
            switch (type) {
              case "array", "integer-array", "string-array" -> {
                resourceName(element);
                validateArrayFeatureFlags(element);
                String format =
                    switch (type) {
                      case "array" -> genericFormat(element);
                      case "integer-array" -> "integer";
                      default -> "string";
                    };
                if (booleanAttribute(element, "translatable", fileTranslatable)) {
                  addArray(destination, element, description, format, type);
                } else {
                  validateProtectedItems(element);
                }
              }
              case "plurals" -> {
                resourceName(element);
                if (!isAndroidFalse(element.getAttribute("translatable").trim())) {
                  addPlural(destination, element, description, type);
                } else {
                  validateProtectedItems(element);
                }
              }
              default -> {
                // Other Android bags belong to their own native resource parsers.
              }
            }
          }
          default -> {
            if (!AndroidResourceReferences.isResourceType(element.getLocalName())
                && !BAG_TYPES.contains(element.getLocalName())) {
              throw invalidStructure("Unknown Android top-level resource type");
            }
            // Known non-string Android resources belong to other resource parsers.
          }
        }
      }
    }
    if (!fileTranslatable) {
      LocalizationCatalog untranslatable = new LocalizationCatalog(LocalizationFileFormat.ANDROID);
      untranslatable.setLocale(catalog.locale());
      return untranslatable;
    }
    AndroidAttributeDependencies.attach(catalog, attributeDependencies, applicationPackage);
    return catalog;
  }

  private void expandMacros(Element root, Map<String, Element> externalMacros) {
    Map<String, Element> macros = new LinkedHashMap<>(externalMacros);
    Set<String> localIdentities = new HashSet<>();
    boolean unsupportedProduct = false;
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (!(children.item(index) instanceof Element element)
          || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())
          || !isMacroDeclaration(element)) {
        continue;
      }
      if (configuration != null && !configuration.effectiveKey().isEmpty()) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_MACRO_CONFIGURATION",
            "Android macros can only be declared in the default resource configuration");
      }
      if (pathFeatureCondition != null) {
        throw new LocalizationParseException(
            "UNSAFE_ANDROID_MACRO_PATH_FLAG", "Android path-gated macros cannot be linked safely");
      }
      featureCondition(element, featureFlags);
      String name = resourceName(element);
      String product = resourceProduct(element);
      String identity = name + "@product=" + (product.isEmpty() ? "default" : product);
      if (!localIdentities.add(identity)) {
        throw new LocalizationParseException(
            "DUPLICATE_ANDROID_MACRO", "Duplicate Android macro declaration: " + name);
      }
      unsupportedProduct |= !product.isEmpty() && !"default".equals(product);
      macros.putIfAbsent(name, element);
      unescape(renderChildren(element, PlaceholderNormalizer.placeholders(), false));
    }
    if (unsupportedProduct) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_MACRO_PRODUCT",
          "Android macro product variants abort AAPT2 before product selection");
    }
    if (macros.isEmpty()) {
      return;
    }
    for (int index = 0; index < children.getLength(); index++) {
      if (!(children.item(index) instanceof Element element)
          || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())
          || isMacroDeclaration(element)) {
        continue;
      }
      FeatureCondition condition = featureCondition(element, featureFlags);
      boolean runtime =
          condition != null && condition.runtime()
              || pathFeatureCondition != null && pathFeatureCondition.runtime();
      switch (element.getLocalName()) {
        case "string", "item" -> expandMacroReference(element, macros, new HashSet<>(), runtime);
        case "plurals", "array", "integer-array", "string-array", "bag" -> {
          NodeList entries = element.getChildNodes();
          for (int entry = 0; entry < entries.getLength(); entry++) {
            if (entries.item(entry) instanceof Element item
                && "item".equals(item.getLocalName())
                && (item.getNamespaceURI() == null || item.getNamespaceURI().isEmpty())) {
              FeatureCondition itemCondition = featureCondition(item, featureFlags);
              expandMacroReference(
                  item,
                  macros,
                  new HashSet<>(),
                  runtime || itemCondition != null && itemCondition.runtime());
            }
          }
        }
        default -> {
          // Macros used by non-translatable resources belong to their native parser.
        }
      }
    }
  }

  static boolean isMacroDeclaration(Element element) {
    return "macro".equals(element.getLocalName())
        || "item".equals(element.getLocalName())
            && "macro".equals(element.getAttribute("type").trim());
  }

  private void expandMacroReference(
      Element target, Map<String, Element> macros, Set<String> resolving, boolean runtime) {
    String reference = target.getTextContent().trim();
    if (hasElementChildren(target)) {
      return;
    }
    MacroReference parsed = macroReference(target, reference);
    if (parsed == null) {
      return;
    }
    String name = parsed.name();
    if (!macros.containsKey(name)) {
      throw unresolvedMacro(reference);
    }
    if (runtime) {
      throw new LocalizationParseException(
          "UNSAFE_ANDROID_RUNTIME_MACRO",
          "Android runtime-conditional macro references cannot be linked safely");
    }
    if (!resolving.add(name)) {
      throw new LocalizationParseException(
          "UNSAFE_ANDROID_MACRO_CYCLE", "Android macro references cannot contain cycles");
    }
    Element macro = macros.get(name);
    String nested = macro.getTextContent().trim();
    if (!hasElementChildren(macro) && macroReference(macro, nested) != null) {
      expandMacroReference(macro, macros, resolving, false);
    }
    while (target.hasChildNodes()) {
      target.removeChild(target.getFirstChild());
    }
    String referenceValue = normalizedMacroResourceReference(macro);
    if (referenceValue != null) {
      target.appendChild(target.getOwnerDocument().createTextNode(referenceValue));
    } else {
      NodeList expansion = macro.getChildNodes();
      for (int index = 0; index < expansion.getLength(); index++) {
        target.appendChild(target.getOwnerDocument().importNode(expansion.item(index), true));
      }
    }
    resolving.remove(name);
  }

  private String normalizedMacroResourceReference(Element scope) {
    if (hasElementChildren(scope)) {
      return null;
    }
    String value = AndroidResourceReferences.normalize(scope.getTextContent());
    if (!AndroidResourceReferences.matches(value)) {
      return null;
    }
    String sigil = value.substring(0, 1);
    String reference = value.substring(1);
    boolean create = reference.startsWith("+");
    if (create) {
      reference = reference.substring(1);
    }
    boolean restricted = reference.startsWith("*");
    if (restricted) {
      reference = reference.substring(1);
    }
    int separator = reference.indexOf('/');
    String qualifiedType = separator < 0 ? reference : reference.substring(0, separator);
    int qualifier = qualifiedType.indexOf(':');
    if (qualifier <= 0) {
      return null;
    }
    String alias = qualifiedType.substring(0, qualifier);
    String namespace = scope.lookupNamespaceURI(alias);
    if (namespace == null) {
      return null;
    }
    boolean local = "http://schemas.android.com/apk/res-auto".equals(namespace);
    String referencedPackage = null;
    if (!local) {
      String publicPrefix = "http://schemas.android.com/apk/res/";
      String privatePrefix = "http://schemas.android.com/apk/prv/res/";
      if (namespace.startsWith(publicPrefix)) {
        referencedPackage = namespace.substring(publicPrefix.length());
      } else if (namespace.startsWith(privatePrefix)) {
        referencedPackage = namespace.substring(privatePrefix.length());
        restricted = true;
      } else {
        return null;
      }
      local = referencedPackage.equals(applicationPackage);
    }
    StringBuilder normalized = new StringBuilder(sigil);
    if (create) {
      normalized.append('+');
    }
    if (!local && restricted) {
      normalized.append('*');
    }
    if (!local) {
      normalized.append(referencedPackage).append(':');
    }
    normalized.append(qualifiedType.substring(qualifier + 1));
    if (separator >= 0) {
      normalized.append(reference.substring(separator));
    }
    return normalized.toString();
  }

  private MacroReference macroReference(Element scope, String value) {
    value = AndroidResourceReferences.normalize(value);
    if (!value.startsWith("@")) {
      return null;
    }
    String reference = value.substring(1);
    if (reference.startsWith("*")) {
      reference = reference.substring(1);
    }
    int separator = reference.indexOf('/');
    if (separator < 0) {
      return null;
    }
    String type = reference.substring(0, separator);
    String name = reference.substring(separator + 1);
    int qualifier = type.lastIndexOf(':');
    if (qualifier == 0 || !"macro".equals(type.substring(qualifier + 1))) {
      return null;
    }
    if (qualifier < 0) {
      return new MacroReference(name);
    }
    String prefix = type.substring(0, qualifier);
    String namespace = scope.lookupNamespaceURI(prefix);
    if ("http://schemas.android.com/apk/res-auto".equals(namespace)) {
      return new MacroReference(name);
    }
    String referencedPackage = prefix;
    if (namespace != null) {
      String publicPrefix = "http://schemas.android.com/apk/res/";
      String privatePrefix = "http://schemas.android.com/apk/prv/res/";
      if (namespace.startsWith(publicPrefix)) {
        referencedPackage = namespace.substring(publicPrefix.length());
      } else if (namespace.startsWith(privatePrefix)) {
        referencedPackage = namespace.substring(privatePrefix.length());
      }
    }
    if (applicationPackage == null) {
      throw new LocalizationParseException(
          "MISSING_ANDROID_APPLICATION_PACKAGE",
          "Package-qualified Android macros require the Android application package");
    }
    if (!applicationPackage.equals(referencedPackage)) {
      throw unresolvedMacro(value);
    }
    return new MacroReference(name);
  }

  private static LocalizationParseException unresolvedMacro(String reference) {
    return new LocalizationParseException(
        "UNRESOLVED_ANDROID_MACRO_REFERENCE",
        "Android macro reference has no matching local definition: " + reference);
  }

  private static boolean hasElementChildren(Element element) {
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element) {
        return true;
      }
    }
    return false;
  }

  private record MacroReference(String name) {}

  static boolean featureEnabled(
      Element element, Map<String, Boolean> configuredFeatureFlags, String pathFlag) {
    FeatureCondition condition = featureCondition(element, configuredFeatureFlags);
    if (pathFlag == null) {
      return condition == null || condition.enabled();
    }
    if (condition != null) {
      throw new LocalizationParseException(
          "CONFLICTING_ANDROID_FEATURE_FLAG",
          "Android feature flags are not allowed in both the resource path and file");
    }
    return featureCondition(pathFlag, configuredFeatureFlags).enabled();
  }

  static String runtimeFeatureFlag(
      Element element, Map<String, Boolean> configuredFeatureFlags, String pathFlag) {
    FeatureCondition condition =
        pathFlag == null
            ? featureCondition(element, configuredFeatureFlags)
            : featureCondition(pathFlag, configuredFeatureFlags);
    return condition != null && condition.runtime() ? condition.expression() : null;
  }

  private static FeatureCondition featureCondition(
      Element element, Map<String, Boolean> configuredFeatureFlags) {
    if (!element.hasAttributeNS(ANDROID_NAMESPACE, "featureFlag")) {
      return null;
    }
    String flag = element.getAttributeNS(ANDROID_NAMESPACE, "featureFlag").trim();
    if (flag.isEmpty()) {
      return null;
    }
    return featureCondition(flag, configuredFeatureFlags);
  }

  private static FeatureCondition featureCondition(
      String expression, Map<String, Boolean> configuredFeatureFlags) {
    boolean negated = expression.startsWith("!");
    String name = negated ? expression.substring(1) : expression;
    if (AndroidFeatureFlags.unset(configuredFeatureFlags, name)) {
      throw new LocalizationParseException(
          "MISSING_ANDROID_FEATURE_FLAG_VALUE", "Android read-only feature flag has no value");
    }
    if (AndroidFeatureFlags.runtime(configuredFeatureFlags, name)) {
      return new FeatureCondition(expression, true, true);
    }
    Boolean value = configuredFeatureFlags.get(name);
    if (value == null) {
      throw new LocalizationParseException(
          "UNRESOLVED_ANDROID_FEATURE_FLAG", "Android resource feature flag has no build value");
    }
    return new FeatureCondition(expression, negated ? !value : value, false);
  }

  private void validateArrayFeatureFlags(Element array) {
    NodeList children = array.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element item
          && "item".equals(item.getLocalName())
          && (item.getNamespaceURI() == null || item.getNamespaceURI().isEmpty())) {
        FeatureCondition condition = featureCondition(item, featureFlags);
        if (condition != null && condition.runtime() && !supportsRuntimeArrayFlags()) {
          throw new LocalizationParseException(
              "UNSUPPORTED_ANDROID_RUNTIME_ARRAY_FLAG",
              "Android runtime-flagged array items require resource configuration SDK 10000");
        }
      }
    }
  }

  private boolean supportsRuntimeArrayFlags() {
    return configuration != null
        && configuration.qualifiers().stream()
            .filter(qualifier -> qualifier.matches("(?i)v\\d+"))
            .anyMatch(qualifier -> Long.parseLong(qualifier.substring(1)) >= 10_000);
  }

  private void addArray(
      LocalizationCatalog catalog,
      Element array,
      String description,
      String format,
      String bagType) {
    List<Element> items = directChildren(array, "item");
    List<Element> retained = new ArrayList<>();
    Map<String, String> references = new LinkedHashMap<>();
    Map<String, String> primitives = new LinkedHashMap<>();
    Map<String, String> itemFlags = new LinkedHashMap<>();
    Map<String, String> itemFlagModes = new LinkedHashMap<>();
    boolean generic = "array".equals(array.getLocalName()) || "array".equals(bagType);
    for (Element item : items) {
      FeatureCondition condition = featureCondition(item, featureFlags);
      String raw = renderChildren(item, PlaceholderNormalizer.placeholders(), false);
      boolean reference = isResourceReference(raw);
      boolean primitive = !"string".equals(format) && isNativePrimitive(raw, format);
      if (!reference && !primitive && !format.isEmpty() && !"string".equals(format)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_VALUE", "Android array item does not match its native format");
      }
      if (condition != null && !condition.enabled()) {
        continue;
      }
      int itemIndex = retained.size();
      retained.add(item);
      if (condition != null) {
        itemFlags.put(Integer.toString(itemIndex), condition.expression());
        if (condition.runtime()) {
          itemFlagModes.put(Integer.toString(itemIndex), "read_write");
        }
      }
      if (reference) {
        references.put(Integer.toString(itemIndex), raw.trim());
      } else if (primitive) {
        primitives.put(Integer.toString(itemIndex), raw.trim());
      }
    }
    for (int itemIndex = 0; itemIndex < retained.size(); itemIndex++) {
      Element item = retained.get(itemIndex);
      if (references.containsKey(Integer.toString(itemIndex))
          || primitives.containsKey(Integer.toString(itemIndex))) {
        continue;
      }
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("arrayIndex", itemIndex);
      metadata.put("arrayName", resourceName(array));
      if (bagType != null) {
        metadata.put("androidBagType", bagType);
      }
      if (!references.isEmpty()) {
        metadata.put("androidArrayReferences", references);
      }
      if (!primitives.isEmpty()) {
        metadata.put("androidArrayPrimitives", primitives);
      }
      if (!itemFlags.isEmpty()) {
        metadata.put("androidArrayFeatureFlags", itemFlags);
      }
      if (!itemFlagModes.isEmpty()) {
        metadata.put("androidArrayFeatureFlagModes", itemFlagModes);
      }
      FeatureCondition condition = featureCondition(array, featureFlags);
      if (condition != null) {
        metadata.put("androidFeatureFlag", condition.expression());
        if (condition.runtime()) {
          metadata.put("androidFeatureFlagMode", "read_write");
        }
      }
      if (generic) {
        metadata.put("androidGenericArray", true);
        if (!format.isEmpty()) {
          metadata.put("androidArrayFormat", format);
        }
      }
      addProduct(array, metadata);
      addString(catalog, item, description, new ArrayEntry(resourceId(array), itemIndex, metadata));
    }
  }

  private void addString(
      LocalizationCatalog catalog, Element element, String comment, ArrayEntry arrayEntry) {
    String id = arrayEntry == null ? resourceId(element) : arrayEntry.id();
    String description =
        element.hasAttribute("description") ? element.getAttribute("description") : comment;
    boolean genericResource = arrayEntry == null && "item".equals(element.getLocalName());
    String genericFormat = genericResource ? genericFormat(element) : "";
    boolean stringResource =
        arrayEntry == null
            && ("string".equals(element.getLocalName()) || "string".equals(genericFormat));
    boolean formatted = !stringResource || booleanAttribute(element, "formatted", true);
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    List<ProtectedPlaceholderSection> protectedSections = new ArrayList<>();
    literalMarkup = false;
    String raw = renderChildren(element, placeholders, false, protectedSections);
    if (isResourceReference(raw)) {
      return;
    }
    if (genericResource && !"string".equals(genericFormat)) {
      if (genericFormat.isEmpty() && isNativePrimitive(raw, "")) {
        return;
      }
      if (!genericFormat.isEmpty()) {
        if (!"reference".equals(genericFormat) && isNativePrimitive(raw, genericFormat)) {
          return;
        }
        throw new LocalizationParseException(
            "INVALID_ANDROID_VALUE", "Android generic resource does not match its format");
      }
    }
    String text = unescape(raw, id);
    List<Map<String, Object>> runtimeAnnotations = AndroidAnnotationSemantics.spans(text);
    List<Map<String, Object>> runtimeStyles = AndroidAnnotationSemantics.styles(text);
    List<Map<String, Object>> runtimeParagraphs = AndroidAnnotationSemantics.paragraphs(text);
    StyleAttributeText protectedAttributes = StyleAttributeText.protect(text);
    String protectedText = protectedAttributes.value();
    List<Integer> rawPercentOccurrences =
        formatted ? PlaceholderNormalizer.rawPercentOccurrences(protectedText) : List.of();
    List<Integer> printfLineSeparators =
        formatted ? PlaceholderNormalizer.printfLineSeparatorOccurrences(protectedText) : List.of();
    if (formatted) {
      if (stringResource && !hasStyledMarkup(element)) {
        PlaceholderNormalizer.validateAndroid(protectedText);
      }
      text = PlaceholderNormalizer.normalize(protectedText, placeholders);
    }
    long visibleNewlineCount = text.chars().filter(character -> character == '\n').count();
    text = protectedAttributes.restore(text);
    validatePlaceholderIdentity(placeholders);
    String quotedMarkup = quoteAttributedMarkup(text);
    Map<String, Object> metadata =
        arrayEntry == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arrayEntry.metadata());
    Map<String, List<Object>> protectedOccurrences =
        protectedPlaceholderOccurrences(raw, text, protectedSections, formatted);
    if (!protectedOccurrences.isEmpty()) {
      metadata.put("androidProtectedPlaceholderOccurrences", protectedOccurrences);
    }
    if (arrayEntry == null) {
      FeatureCondition condition = featureCondition(element, featureFlags);
      if (condition != null) {
        metadata.put("androidFeatureFlag", condition.expression());
        if (condition.runtime()) {
          metadata.put("androidFeatureFlagMode", "read_write");
        }
      }
    }
    addProduct(element, metadata);
    if (arrayEntry == null && "item".equals(element.getLocalName())) {
      metadata.put("androidGenericString", true);
      if (!genericFormat.isEmpty()) {
        metadata.put("androidGenericFormat", genericFormat);
      }
    }
    if (!formatted) {
      metadata.put("formatted", false);
    }
    if (!runtimeAnnotations.isEmpty()) {
      metadata.put("androidRuntimeAnnotations", runtimeAnnotations);
    }
    if (!runtimeStyles.isEmpty()) {
      metadata.put("androidRuntimeStyles", runtimeStyles);
    }
    if (!runtimeParagraphs.isEmpty()) {
      metadata.put("androidRuntimeParagraphSpans", runtimeParagraphs);
    }
    if (!printfLineSeparators.isEmpty()) {
      metadata.put("androidPrintfLineSeparator", true);
      if (printfLineSeparators.size() < visibleNewlineCount) {
        metadata.put("androidPrintfLineSeparators", printfLineSeparators);
      }
    }
    if (!rawPercentOccurrences.isEmpty()) {
      metadata.put("androidRawPercentOccurrences", rawPercentOccurrences);
    }
    if (!quotedMarkup.equals(text)) {
      metadata.put("androidMarkupEscaping", "icu-quoted-angle");
    }
    if (literalMarkup) {
      metadata.put("androidLiteralMarkup", true);
    }
    addConfiguration(metadata);
    catalog.add(
        id, LocalizationMessage.of(quotedMarkup, description, null, placeholders, metadata));
  }

  private void addPlural(
      LocalizationCatalog catalog, Element plural, String comment, String bagType) {
    String description =
        plural.hasAttribute("description") ? plural.getAttribute("description") : comment;
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    Map<String, String> variants = new LinkedHashMap<>();
    Map<String, String> references = new LinkedHashMap<>();
    Map<String, List<Integer>> rawPercentOccurrences = new LinkedHashMap<>();
    Map<String, List<Integer>> printfLineSeparators = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> runtimeAnnotations = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> runtimeStyles = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> runtimeParagraphs = new LinkedHashMap<>();
    Map<String, Map<String, List<String>>> pluralPlaceholderExamples = new LinkedHashMap<>();
    Map<String, Map<String, List<Object>>> pluralProtectedOccurrences = new LinkedHashMap<>();
    Map<String, Set<String>> distinctPlaceholderExamples = new LinkedHashMap<>();
    Set<String> quantities = new java.util.HashSet<>();
    boolean quotedMarkup = false;
    int newlineCount = 0;
    literalMarkup = false;
    for (Element item : directChildren(plural, "item")) {
      String quantity = item.getAttribute("quantity").trim();
      if (!PLURAL_CATEGORIES.contains(quantity)) {
        throw new LocalizationParseException(
            "INVALID_PLURAL_CATEGORY", "Unknown plural category: " + quantity);
      }
      if (!quantities.add(quantity)) {
        throw new LocalizationParseException(
            "DUPLICATE_PLURAL_CATEGORY", "Duplicate plural category: " + quantity);
      }
      int previousPlaceholders = placeholders.size();
      List<ProtectedPlaceholderSection> protectedSections = new ArrayList<>();
      String raw = renderChildren(item, placeholders, false, protectedSections);
      if (isResourceReference(raw)) {
        references.put(quantity, raw.trim());
        continue;
      }
      String sourceText = unescape(raw, resourceId(plural) + "#" + quantity);
      List<Map<String, Object>> annotations = AndroidAnnotationSemantics.spans(sourceText);
      if (!annotations.isEmpty()) {
        runtimeAnnotations.put(quantity, annotations);
      }
      List<Map<String, Object>> styles = AndroidAnnotationSemantics.styles(sourceText);
      if (!styles.isEmpty()) {
        runtimeStyles.put(quantity, styles);
      }
      List<Map<String, Object>> paragraphs = AndroidAnnotationSemantics.paragraphs(sourceText);
      if (!paragraphs.isEmpty()) {
        runtimeParagraphs.put(quantity, paragraphs);
      }
      StyleAttributeText protectedAttributes = StyleAttributeText.protect(sourceText);
      String protectedText = protectedAttributes.value();
      List<Integer> rawOccurrences = PlaceholderNormalizer.rawPercentOccurrences(protectedText);
      if (!rawOccurrences.isEmpty()) {
        rawPercentOccurrences.put(quantity, rawOccurrences);
      }
      List<Integer> lineSeparators =
          PlaceholderNormalizer.printfLineSeparatorOccurrences(protectedText);
      if (!lineSeparators.isEmpty()) {
        printfLineSeparators.put(quantity, lineSeparators);
      }
      String normalized = PlaceholderNormalizer.normalize(protectedText, placeholders);
      Map<String, List<Object>> protectedOccurrences =
          protectedPlaceholderOccurrences(raw, normalized, protectedSections, true);
      if (!protectedOccurrences.isEmpty()) {
        pluralProtectedOccurrences.put(quantity, protectedOccurrences);
      }
      Map<String, List<String>> categoryExamples = new LinkedHashMap<>();
      for (LocalizationPlaceholder placeholder :
          placeholders.subList(previousPlaceholders, placeholders.size())) {
        if (placeholder.example() != null
            || placeholder.position() == null
            || !placeholder.name().equals("arg" + (placeholder.position() - 1))
            || protectedOccurrences.containsKey(placeholder.name())) {
          categoryExamples
              .computeIfAbsent(placeholder.name(), unused -> new ArrayList<>())
              .add(placeholder.example());
          distinctPlaceholderExamples
              .computeIfAbsent(placeholder.name(), unused -> new HashSet<>())
              .add(placeholder.example());
        }
      }
      if (!categoryExamples.isEmpty()) {
        pluralPlaceholderExamples.put(quantity, categoryExamples);
      }
      newlineCount += (int) normalized.chars().filter(character -> character == '\n').count();
      normalized = protectedAttributes.restore(normalized);
      String text = quoteAttributedMarkup(normalized);
      quotedMarkup |= !text.equals(normalized);
      variants.put(quantity, text);
    }
    if (!variants.containsKey("other")) {
      if (references.containsKey("other")) {
        throw new LocalizationParseException(
            "UNRESOLVED_ANDROID_PLURAL_REFERENCE",
            "Plural other references another resource and has no translatable fallback");
      }
      throw new LocalizationParseException(
          "MISSING_OTHER_VARIANT", "Plural message must contain other");
    }
    validatePlaceholderIdentity(placeholders);
    String selector =
        placeholders.stream()
            .filter(placeholder -> "integer".equals(placeholder.kind()))
            .map(LocalizationPlaceholder::name)
            .findFirst()
            .orElse("count");
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (bagType != null) {
      metadata.put("androidBagType", bagType);
    }
    FeatureCondition condition = featureCondition(plural, featureFlags);
    if (condition != null) {
      metadata.put("androidFeatureFlag", condition.expression());
      if (condition.runtime()) {
        metadata.put("androidFeatureFlagMode", "read_write");
      }
    }
    addProduct(plural, metadata);
    if (!references.isEmpty()) {
      metadata.put("androidPluralReferences", references);
    }
    if (quotedMarkup) {
      metadata.put("androidMarkupEscaping", "icu-quoted-angle");
    }
    if (!runtimeAnnotations.isEmpty()) {
      metadata.put("androidPluralRuntimeAnnotations", runtimeAnnotations);
    }
    if (!runtimeStyles.isEmpty()) {
      metadata.put("androidPluralRuntimeStyles", runtimeStyles);
    }
    if (!runtimeParagraphs.isEmpty()) {
      metadata.put("androidPluralRuntimeParagraphSpans", runtimeParagraphs);
    }
    if (distinctPlaceholderExamples.values().stream().anyMatch(examples -> examples.size() > 1)) {
      metadata.put("androidPluralPlaceholderExamples", pluralPlaceholderExamples);
    }
    if (!pluralProtectedOccurrences.isEmpty()) {
      metadata.put("androidPluralProtectedPlaceholderOccurrences", pluralProtectedOccurrences);
    }
    if (!printfLineSeparators.isEmpty()) {
      metadata.put("androidPrintfLineSeparator", true);
      int printfCount = printfLineSeparators.values().stream().mapToInt(List::size).sum();
      if (printfCount < newlineCount) {
        metadata.put("androidPluralPrintfLineSeparators", printfLineSeparators);
      }
    }
    if (!rawPercentOccurrences.isEmpty()) {
      metadata.put("androidPluralRawPercentOccurrences", rawPercentOccurrences);
    }
    if (literalMarkup) {
      metadata.put("androidLiteralMarkup", true);
    }
    addConfiguration(metadata);
    catalog.add(
        resourceId(plural),
        LocalizationMessage.of(
            PlaceholderNormalizer.plural(selector, variants),
            description,
            variants,
            placeholders,
            metadata));
  }

  private static List<Element> directChildren(Element parent, String localName) {
    List<Element> result = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element element) {
        boolean unnamespaced =
            element.getNamespaceURI() == null || element.getNamespaceURI().isEmpty();
        if (unnamespaced && localName.equals(element.getLocalName())) {
          result.add(element);
        } else if (!unnamespaced
            || (!"skip".equals(element.getLocalName())
                && !"eat-comment".equals(element.getLocalName()))) {
          throw new LocalizationParseException(
              "INVALID_ANDROID_STRUCTURE",
              "Unexpected Android " + parent.getLocalName() + " child element");
        }
      }
    }
    return result;
  }

  private static boolean booleanAttribute(Element element, String name, boolean fallback) {
    if (!element.hasAttribute(name)) {
      return fallback;
    }
    String value = element.getAttribute(name).trim();
    if ("true".equals(value) || "True".equals(value) || "TRUE".equals(value)) {
      return true;
    }
    if (isAndroidFalse(value)) {
      return false;
    }
    throw new LocalizationParseException(
        "INVALID_ANDROID_BOOLEAN", "Invalid Android boolean attribute: " + name);
  }

  private static String genericFormat(Element element) {
    String format = element.getAttribute("format").trim();
    if (!format.isEmpty() && !GENERIC_FORMATS.contains(format)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_FORMAT", "Unsupported Android generic resource format: " + format);
    }
    return format;
  }

  static boolean isNativePrimitive(String raw, String format) {
    String value = raw.trim();
    if ((format.isEmpty() || "boolean".equals(format))
        && ("true".equals(value)
            || "True".equals(value)
            || "TRUE".equals(value)
            || isAndroidFalse(value))) {
      return true;
    }
    if ((format.isEmpty() || "integer".equals(format))
        && ANDROID_INTEGER.matcher(value).matches()) {
      return true;
    }
    if ((format.isEmpty() || "color".equals(format)) && ANDROID_COLOR.matcher(value).matches()) {
      return true;
    }
    if ((format.isEmpty() || "float".equals(format)) && ANDROID_NUMBER.matcher(value).matches()) {
      return true;
    }
    if (format.isEmpty() || "dimension".equals(format)) {
      for (String unit : List.of("px", "dp", "dip", "sp", "pt", "in", "mm")) {
        if (value.endsWith(unit)
            && ANDROID_NUMBER
                .matcher(value.substring(0, value.length() - unit.length()))
                .matches()) {
          return true;
        }
      }
    }
    if (format.isEmpty() || "fraction".equals(format)) {
      for (String unit : List.of("%p", "%")) {
        if (value.endsWith(unit)
            && ANDROID_NUMBER
                .matcher(value.substring(0, value.length() - unit.length()))
                .matches()) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isAndroidFalse(String value) {
    return "false".equals(value) || "False".equals(value) || "FALSE".equals(value);
  }

  private static boolean hasStyledMarkup(Element parent) {
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element element
          && ((element.getNamespaceURI() == null || element.getNamespaceURI().isEmpty())
              || hasStyledMarkup(element))) {
        return true;
      }
    }
    return false;
  }

  private String renderChildren(
      Element parent, List<LocalizationPlaceholder> placeholders, boolean insideXliff) {
    return renderChildren(parent, placeholders, insideXliff, null);
  }

  private String renderChildren(
      Element parent,
      List<LocalizationPlaceholder> placeholders,
      boolean insideXliff,
      List<ProtectedPlaceholderSection> protectedSections) {
    StringBuilder result = new StringBuilder();
    appendChildren(parent, result, placeholders, insideXliff, protectedSections);
    return result.toString();
  }

  private void appendChildren(
      Element parent,
      StringBuilder result,
      List<LocalizationPlaceholder> placeholders,
      boolean insideXliff,
      List<ProtectedPlaceholderSection> protectedSections) {
    NodeList nodes = parent.getChildNodes();
    for (int index = 0; index < nodes.getLength(); index++) {
      Node node = nodes.item(index);
      switch (node.getNodeType()) {
        case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
          String text = node.getNodeValue();
          literalMarkup |= LITERAL_MARKUP.matcher(text).find();
          result.append(text);
        }
        case Node.ELEMENT_NODE ->
            renderElement((Element) node, result, placeholders, insideXliff, protectedSections);
        default -> {
          // Embedded XML comments do not contribute visible message text.
        }
      }
    }
  }

  private void renderElement(
      Element element,
      StringBuilder output,
      List<LocalizationPlaceholder> placeholders,
      boolean insideXliff,
      List<ProtectedPlaceholderSection> protectedSections) {
    if ("g".equals(element.getLocalName()) && XLIFF_NAMESPACE.equals(element.getNamespaceURI())) {
      if (insideXliff) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_MARKUP", "Nested Android xliff:g sections are not allowed");
      }
      if (hasStyledMarkup(element)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_MARKUP", "Styled markup inside xliff:g cannot be regenerated safely");
      }
      String identifier = element.getAttribute("id");
      if (identifier.isEmpty()) {
        identifier = "_xliff" + placeholders.size();
      } else if (!XLIFF_IDENTIFIER.matcher(identifier).matches()) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER", "XLIFF placeholder ID is not a valid ICU argument");
      }
      String value = renderChildren(element, placeholders, true, protectedSections);
      int existing = placeholders.size();
      String normalized = PlaceholderNormalizer.normalize(value, placeholders, identifier);
      if (normalized.equals(value) || !normalized.contains("{" + identifier + "}")) {
        LocalizationPlaceholder placeholder =
            new LocalizationPlaceholder(
                identifier,
                value,
                "string",
                null,
                element.hasAttribute("example") ? element.getAttribute("example") : null);
        if (!placeholders.contains(placeholder)) {
          placeholders.add(placeholder);
        }
        normalized = "{" + identifier + "}";
      }
      if (placeholders.size() > existing) {
        if (element.hasAttribute("example")) {
          LocalizationPlaceholder placeholder = placeholders.get(placeholders.size() - 1);
          placeholders.set(
              placeholders.size() - 1,
              new LocalizationPlaceholder(
                  placeholder.name(),
                  placeholder.source(),
                  placeholder.kind(),
                  placeholder.position(),
                  element.getAttribute("example")));
        }
      } else {
        for (int index = placeholders.size() - 1; index >= 0; index--) {
          LocalizationPlaceholder placeholder = placeholders.get(index);
          if (!placeholder.name().equals(identifier)) {
            continue;
          }
          String example = element.hasAttribute("example") ? element.getAttribute("example") : null;
          LocalizationPlaceholder selected =
              new LocalizationPlaceholder(
                  placeholder.name(),
                  placeholder.source(),
                  placeholder.kind(),
                  placeholder.position(),
                  example);
          if (!placeholders.contains(selected)) {
            placeholders.add(selected);
          }
          break;
        }
      }
      String protectedIdentifier = identifier;
      if (protectedSections != null
          && placeholders.stream()
              .anyMatch(
                  placeholder ->
                      placeholder.name().equals(protectedIdentifier)
                          && placeholder.position() != null
                          && protectedIdentifier.equals("arg" + (placeholder.position() - 1)))) {
        protectedSections.add(
            new ProtectedPlaceholderSection(
                identifier,
                output.length(),
                element.hasAttribute("example"),
                element.getAttribute("example")));
      }
      output.append(normalized);
      return;
    }
    if (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty()) {
      appendChildren(element, output, placeholders, insideXliff, protectedSections);
      return;
    }
    output.append('<').append(element.getTagName());
    NamedNodeMap attributes = element.getAttributes();
    List<Attr> normalizedAttributes = new ArrayList<>();
    for (int index = 0; index < attributes.getLength(); index++) {
      Attr attribute = (Attr) attributes.item(index);
      if (!attribute.getName().startsWith("xmlns")) {
        normalizedAttributes.add(attribute);
      }
    }
    normalizedAttributes.sort(
        Comparator.comparing(
            attribute ->
                attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName()));
    Set<String> names = new HashSet<>();
    for (Attr attribute : normalizedAttributes) {
      String name =
          attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName();
      if (!names.add(name)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_MARKUP", "Android style attributes have ambiguous native local names");
      }
      output
          .append(' ')
          .append(name)
          .append("=\"")
          .append(escapeAttribute(attribute.getValue()))
          .append('"');
    }
    output.append('>');
    appendChildren(element, output, placeholders, insideXliff, protectedSections);
    output.append("</").append(element.getTagName()).append('>');
  }

  private static Map<String, List<Object>> protectedPlaceholderOccurrences(
      String raw, String canonical, List<ProtectedPlaceholderSection> sections, boolean formatted) {
    if (sections.isEmpty()) {
      return Map.of();
    }
    Map<String, List<Object>> occurrences = new LinkedHashMap<>();
    for (ProtectedPlaceholderSection section : sections) {
      String prefix =
          StyleAttributeText.protect(unescape(raw.substring(0, section.offset()))).value();
      String normalized =
          formatted
              ? PlaceholderNormalizer.normalize(prefix, PlaceholderNormalizer.placeholders())
              : prefix;
      int occurrence = countPlaceholderOccurrences(normalized, section.name());
      int total = countPlaceholderOccurrences(canonical, section.name());
      List<Object> values =
          occurrences.computeIfAbsent(
              section.name(),
              unused -> {
                List<Object> positions = new ArrayList<>();
                for (int index = 0; index < total; index++) {
                  positions.add(null);
                }
                return positions;
              });
      Map<String, Object> protectedValue = new LinkedHashMap<>();
      if (section.hasExample()) {
        protectedValue.put("example", section.example());
      }
      values.set(occurrence, protectedValue);
    }
    occurrences
        .entrySet()
        .removeIf(
            entry ->
                entry.getValue().stream().noneMatch(java.util.Objects::isNull)
                    && entry.getValue().stream()
                        .allMatch(value -> ((Map<?, ?>) value).containsKey("example")));
    return occurrences;
  }

  private static int countPlaceholderOccurrences(String source, String name) {
    java.util.regex.Matcher matcher =
        Pattern.compile("\\{" + Pattern.quote(name) + "\\}").matcher(source);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private record ProtectedPlaceholderSection(
      String name, int offset, boolean hasExample, String example) {}

  private static void validatePlaceholderIdentity(List<LocalizationPlaceholder> placeholders) {
    Map<String, LocalizationPlaceholder> previous = new LinkedHashMap<>();
    for (LocalizationPlaceholder placeholder : placeholders) {
      LocalizationPlaceholder existing = previous.putIfAbsent(placeholder.name(), placeholder);
      if (existing != null
          && (!java.util.Objects.equals(existing.position(), placeholder.position())
              || (existing.position() == null
                  && !existing.source().equals(placeholder.source())))) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER",
            "Conflicting Android placeholder identity: " + placeholder.name());
      }
    }
  }

  private static String escapeAttribute(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
  }

  private static String quoteAttributedMarkup(String text) {
    StringBuilder result = new StringBuilder();
    Deque<MarkupTag> tags = new ArrayDeque<>();
    boolean converted = false;
    for (int index = 0; index < text.length(); ) {
      if (text.charAt(index) != '<' || !isMarkupStart(text, index)) {
        appendIcuLiteral(result, text.substring(index, index + 1));
        index++;
        continue;
      }
      boolean closing = text.charAt(index + 1) == '/';
      int nameStart = index + (closing ? 2 : 1);
      int nameEnd = nameStart;
      while (nameEnd < text.length() && isMarkupNameCharacter(text.charAt(nameEnd))) {
        nameEnd++;
      }
      int end = findMarkupEnd(text, nameEnd);
      if (nameEnd == nameStart || end < 0) {
        appendIcuLiteral(result, text.substring(index, index + 1));
        index++;
        continue;
      }
      String name = text.substring(nameStart, nameEnd);
      boolean quoted;
      if (closing) {
        quoted = !tags.isEmpty() && name.equals(tags.peek().name()) && tags.pop().quoted();
      } else {
        quoted = !text.substring(nameEnd, end).isBlank();
        tags.push(new MarkupTag(name, quoted));
      }
      if (quoted) {
        converted = true;
        result.append("'<'");
        appendIcuLiteral(result, text.substring(index + 1, end + 1));
      } else {
        appendIcuLiteral(result, text.substring(index, end + 1));
      }
      index = end + 1;
    }
    return converted ? result.toString() : text;
  }

  private static void appendIcuLiteral(StringBuilder output, String value) {
    output.append(value.replace("'", "''"));
  }

  private static int findMarkupEnd(String text, int index) {
    boolean quoted = false;
    for (int cursor = index; cursor < text.length(); cursor++) {
      char character = text.charAt(cursor);
      if (character == '"') {
        quoted = !quoted;
      } else if (character == '>' && !quoted) {
        return cursor;
      }
    }
    return -1;
  }

  private static boolean isMarkupNameCharacter(char character) {
    return Character.isLetterOrDigit(character)
        || character == '_'
        || character == '-'
        || character == '.';
  }

  private String resourceId(Element element) {
    String name = resourceName(element);
    String product = resourceProduct(element);
    String id =
        product.isEmpty() || "default".equals(product) ? name : name + "@product=" + product;
    FeatureCondition condition =
        pathFeatureCondition == null
            ? featureCondition(element, featureFlags)
            : pathFeatureCondition;
    return condition != null && condition.runtime() ? id + "@flag=" + condition.expression() : id;
  }

  static String resourceName(Element element) {
    String name = element.getAttribute("name").trim();
    if (name.isEmpty()) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_RESOURCE_NAME", "Android resource declarations require a name");
    }
    if (!isValidResourceName(name)) {
      throw invalidResourceName();
    }
    return name;
  }

  static boolean isValidResourceName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    int codePoint = name.codePointAt(0);
    if (codePoint > Character.MAX_VALUE
        || (codePoint != '_' && !UCharacter.hasBinaryProperty(codePoint, UProperty.XID_START))) {
      return false;
    }
    for (int index = Character.charCount(codePoint); index < name.length(); ) {
      codePoint = name.codePointAt(index);
      if (codePoint > Character.MAX_VALUE
          || codePoint == 0x200c
          || codePoint == 0x200d
          || (codePoint != '.'
              && codePoint != '-'
              && !UCharacter.hasBinaryProperty(codePoint, UProperty.XID_CONTINUE))) {
        return false;
      }
      index += Character.charCount(codePoint);
    }
    return true;
  }

  static String resourceProduct(Element element) {
    return element.getAttribute("product").trim();
  }

  private static LocalizationParseException invalidResourceName() {
    return new LocalizationParseException(
        "INVALID_ANDROID_RESOURCE_NAME", "Android resource entry name is invalid");
  }

  private static void validateProtectedItems(Element resource) {
    for (Element item : directChildren(resource, "item")) {
      validateProtectedString(item);
    }
  }

  private static void validateProtectedString(Element resource) {
    StringBuilder content = new StringBuilder();
    appendProtectedText(resource, content, false);
    unescape(content.toString());
  }

  private static void appendProtectedText(Element resource, StringBuilder content, boolean xliff) {
    NodeList children = resource.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        content.append(child.getNodeValue());
      } else if (child instanceof Element element) {
        boolean protectedSection =
            "g".equals(element.getLocalName()) && XLIFF_NAMESPACE.equals(element.getNamespaceURI());
        boolean styledSection =
            element.getNamespaceURI() == null || element.getNamespaceURI().isEmpty();
        if (protectedSection && xliff) {
          throw new LocalizationParseException(
              "INVALID_ANDROID_MARKUP", "Nested Android xliff:g sections are not allowed");
        }
        if (styledSection) {
          content.append('<').append(element.getLocalName()).append('>');
        }
        appendProtectedText(element, content, xliff || protectedSection);
        if (styledSection) {
          content.append("</").append(element.getLocalName()).append('>');
        }
      }
    }
  }

  private static LocalizationParseException invalidStructure(String message) {
    return new LocalizationParseException("INVALID_ANDROID_STRUCTURE", message);
  }

  private static void addProduct(Element element, Map<String, Object> metadata) {
    String product = resourceProduct(element);
    if (!product.isEmpty()) {
      metadata.put("androidProduct", product);
    }
  }

  private void addConfiguration(Map<String, Object> metadata) {
    if (configuration != null) {
      metadata.put("androidResourcePath", configuration.path());
      metadata.put("androidResourceQualifiers", configuration.qualifiers());
      if (configuration.pathFeatureFlag() != null) {
        metadata.put("androidPathFeatureFlag", configuration.pathFeatureFlag());
        if (pathFeatureCondition != null && pathFeatureCondition.runtime()) {
          metadata.put("androidPathFeatureFlagMode", "read_write");
        }
      }
    }
  }

  private static boolean isResourceReference(String value) {
    return AndroidResourceReferences.matches(value);
  }

  private static String unescape(String input) {
    String text = input;
    StringBuilder result = new StringBuilder();
    boolean quoted = false;
    boolean pendingWhitespace = false;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == '<' && isMarkupStart(text, index)) {
        int close = findMarkupEnd(text, index + 1);
        if (close >= 0) {
          if (pendingWhitespace && !result.isEmpty()) {
            result.append(' ');
          }
          pendingWhitespace = false;
          quoted = false;
          result.append(text, index, close + 1);
          index = close;
          continue;
        }
      }
      if (character == '"') {
        if (!quoted && pendingWhitespace && !result.isEmpty()) {
          result.append(' ');
        }
        pendingWhitespace = false;
        quoted = !quoted;
        continue;
      }
      if (!quoted && character == '\'') {
        throw new LocalizationParseException(
            "UNESCAPED_APOSTROPHE", "Android apostrophes must be escaped or quoted");
      }
      if (!quoted && isAndroidWhitespace(character)) {
        pendingWhitespace = true;
        continue;
      }
      if (character != '\\') {
        if (pendingWhitespace && !result.isEmpty()) {
          result.append(' ');
        }
        pendingWhitespace = false;
        result.append(character);
        continue;
      }
      if (pendingWhitespace && !result.isEmpty()) {
        result.append(' ');
      }
      pendingWhitespace = false;
      if (++index >= text.length()) {
        break;
      }
      char escaped = text.charAt(index);
      if (escaped == '<' && isMarkupStart(text, index)) {
        index--;
        continue;
      }
      switch (escaped) {
        case 'n' -> result.append('\n');
        case 't' -> result.append('\t');
        case 'u' -> {
          int value = 0;
          int digits = 0;
          while (digits < 4 && index + 1 < text.length()) {
            char digit = text.charAt(index + 1);
            if (digit == '<' && isMarkupStart(text, index + 1)) {
              break;
            }
            int hexadecimal = Character.digit(digit, 16);
            if (hexadecimal < 0 || digit > 0x7f) {
              throw new LocalizationParseException(
                  "INVALID_UNICODE_ESCAPE", "Invalid Android Unicode escape");
            }
            value = (value << 4) | hexadecimal;
            digits++;
            index++;
          }
          if (!Character.isSurrogate((char) value)) {
            result.append((char) value);
          }
        }
        default -> result.append(escaped);
      }
    }
    return result.toString();
  }

  private static String unescape(String input, String resourceId) {
    try {
      return unescape(input);
    } catch (LocalizationParseException exception) {
      throw new LocalizationParseException(
          exception.code(), exception.getMessage() + " [resource=" + resourceId + "]", exception);
    }
  }

  private static boolean isAndroidWhitespace(char value) {
    // Current AAPT2 preserves Unicode separator and no-break characters; only ASCII collapses.
    return value == ' '
        || value == '\n'
        || value == '\r'
        || value == '\t'
        || value == '\f'
        || value == '\u000b';
  }

  private static boolean isMarkupStart(String input, int index) {
    return index + 1 < input.length()
        && (Character.isLetter(input.charAt(index + 1)) || input.charAt(index + 1) == '/');
  }

  private record FeatureCondition(String expression, boolean enabled, boolean runtime) {}

  private record StyleAttributeText(
      String value, char percent, char newline, char carriageReturn, char tab) {

    private static StyleAttributeText protect(String source) {
      char[] markers = new char[4];
      int candidate = 0xe000;
      for (int index = 0; index < markers.length; index++) {
        while (candidate <= 0xf8ff && source.indexOf((char) candidate) >= 0) {
          candidate++;
        }
        if (candidate > 0xf8ff) {
          throw new LocalizationParseException(
              "INVALID_ANDROID_MARKUP", "Android style attributes cannot be normalized safely");
        }
        markers[index] = (char) candidate++;
      }
      StringBuilder protectedText = new StringBuilder(source.length());
      boolean insideTag = false;
      boolean insideAttribute = false;
      for (int index = 0; index < source.length(); index++) {
        char character = source.charAt(index);
        if (!insideTag && character == '<' && isMarkupStart(source, index)) {
          insideTag = true;
        }
        if (insideTag && character == '"') {
          insideAttribute = !insideAttribute;
        } else if (insideTag && !insideAttribute && character == '>') {
          insideTag = false;
        }
        if (insideTag && insideAttribute) {
          character =
              switch (character) {
                case '%' -> markers[0];
                case '\n' -> markers[1];
                case '\r' -> markers[2];
                case '\t' -> markers[3];
                default -> character;
              };
        }
        protectedText.append(character);
      }
      return new StyleAttributeText(
          protectedText.toString(), markers[0], markers[1], markers[2], markers[3]);
    }

    private String restore(String source) {
      return source
          .replace(percent, '%')
          .replace(newline, '\n')
          .replace(carriageReturn, '\r')
          .replace(tab, '\t');
    }
  }

  private record ArrayEntry(String name, int index, Map<String, Object> metadata) {
    String id() {
      return name + "[" + index + "]";
    }
  }

  private record MarkupTag(String name, boolean quoted) {}
}
