package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Merges one Android resource configuration using actual Gradle/AAPT2 source-set semantics. */
final class AndroidResourceOverlays {

  private AndroidResourceOverlays() {}

  private static String decode(AndroidResourceInput source) {
    return LocalizationFileConverters.decode(
        source.source(),
        LocalizationFileConverters.xmlCharset(LocalizationFileFormat.ANDROID, source.source()));
  }

  static LocalizationCatalog parse(
      List<AndroidResourceInput> sources,
      Map<String, Boolean> featureFlags,
      String applicationPackage) {
    if (sources == null || sources.isEmpty()) {
      throw new LocalizationParseException(
          "EMPTY_ANDROID_OVERLAY", "An Android overlay requires at least one resource source");
    }

    Map<String, Element> macros = macros(sources);
    Map<String, Element> attributes = attributes(sources);
    Map<String, Element> styleables = styleables(sources);
    List<Layer> layers = new ArrayList<>();
    String configurationKey = null;
    String locale = null;
    for (AndroidResourceInput source : sources) {
      int priority = priority(source.sourceSet());
      AndroidResourceConfiguration configuration =
          AndroidResourceConfiguration.parse(source.resourcePath());
      String currentKey = configuration.effectiveKey();
      LocalizationCatalog catalog =
          new AndroidResourcesParser()
              .parse(
                  decode(source),
                  source.resourcePath(),
                  featureFlags,
                  macros,
                  attributes,
                  styleables,
                  applicationPackage);
      if (configurationKey == null) {
        configurationKey = currentKey;
        locale = catalog.locale();
      } else if (!configurationKey.equals(currentKey)
          || !Objects.equals(locale, catalog.locale())) {
        throw new LocalizationParseException(
            "ANDROID_OVERLAY_CONFIGURATION_MISMATCH",
            "Android overlays must share one effective resource configuration");
      }
      Element root = SecureXmlParser.parse(decode(source)).getDocumentElement();
      layers.add(
          new Layer(
              source.sourceSet(),
              priority,
              catalog,
              declarations(root, featureFlags, configuration.pathFeatureFlag())));
    }
    layers.sort(Comparator.comparingInt(Layer::priority));

    Map<ResourceIdentity, Winner> winners = new LinkedHashMap<>();
    for (Layer layer : layers) {
      for (ResourceIdentity resource : layer.resources()) {
        Winner previous = winners.get(resource);
        if (previous != null && previous.priority() == layer.priority()) {
          if (resource.runtimeFlag() != null) {
            continue;
          }
          throw new LocalizationParseException(
              "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
              "Same-priority Android resource conflict: " + resource.name());
        }
        List<Map.Entry<String, LocalizationMessage>> messages = new ArrayList<>();
        for (Map.Entry<String, LocalizationMessage> entry : layer.catalog().messages().entrySet()) {
          if (resource.contains(entry.getKey(), entry.getValue())) {
            messages.add(entry);
          }
        }
        winners.put(resource, new Winner(layer.priority(), layer.sourceSet(), messages));
      }
    }

    LocalizationCatalog merged = new LocalizationCatalog(LocalizationFileFormat.ANDROID);
    merged.setLocale(locale);
    for (Winner winner : winners.values()) {
      for (Map.Entry<String, LocalizationMessage> entry : winner.messages()) {
        LocalizationMessage original = entry.getValue();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (original.metadata() != null) {
          metadata.putAll(original.metadata());
        }
        metadata.put("androidOverlaySourceSet", winner.sourceSet());
        merged.add(
            entry.getKey(),
            LocalizationMessage.of(
                original.defaultMessage(),
                original.description(),
                original.variants(),
                original.placeholders(),
                metadata));
      }
    }
    return merged;
  }

  private static Map<String, Element> macros(List<AndroidResourceInput> sources) {
    Map<String, MacroWinner> winners = new LinkedHashMap<>();
    for (AndroidResourceInput source : sources) {
      int priority = priority(source.sourceSet());
      Element root = SecureXmlParser.parse(decode(source)).getDocumentElement();
      NodeList children = root.getChildNodes();
      for (int index = 0; index < children.getLength(); index++) {
        if (!(children.item(index) instanceof Element element)
            || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())
            || !AndroidResourcesParser.isMacroDeclaration(element)) {
          continue;
        }
        String name = AndroidResourcesParser.resourceName(element);
        MacroWinner previous = winners.get(name);
        if (previous != null && previous.priority() == priority) {
          throw new LocalizationParseException(
              "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
              "Same-priority Android resource conflict: " + name);
        }
        if (previous == null || previous.priority() < priority) {
          winners.put(name, new MacroWinner(priority, element));
        }
      }
    }
    Map<String, Element> definitions = new LinkedHashMap<>();
    winners.forEach((name, winner) -> definitions.put(name, winner.element()));
    return definitions;
  }

  static Map<String, AndroidOverlaySourceSkeleton.AndroidMacroOwner> macroOwners(
      List<AndroidResourceInput> sources) {
    Map<String, MacroSourceWinner> winners = new LinkedHashMap<>();
    for (AndroidResourceInput source : sources) {
      int priority = priority(source.sourceSet());
      Element root = SecureXmlParser.parse(decode(source)).getDocumentElement();
      NodeList children = root.getChildNodes();
      for (int index = 0; index < children.getLength(); index++) {
        if (!(children.item(index) instanceof Element element)
            || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())
            || !AndroidResourcesParser.isMacroDeclaration(element)) {
          continue;
        }
        String name = AndroidResourcesParser.resourceName(element);
        MacroSourceWinner previous = winners.get(name);
        if (previous == null || previous.priority() < priority) {
          winners.put(
              name, new MacroSourceWinner(priority, source.sourceSet(), source.resourcePath()));
        }
      }
    }
    Map<String, AndroidOverlaySourceSkeleton.AndroidMacroOwner> result = new LinkedHashMap<>();
    winners.forEach(
        (name, winner) ->
            result.put(
                name,
                new AndroidOverlaySourceSkeleton.AndroidMacroOwner(
                    winner.sourceSet(), winner.resourcePath())));
    return result;
  }

  private static Map<String, Element> attributes(List<AndroidResourceInput> sources) {
    Map<String, MacroWinner> winners = new LinkedHashMap<>();
    for (AndroidResourceInput source : sources) {
      int priority = priority(source.sourceSet());
      Element root = SecureXmlParser.parse(decode(source)).getDocumentElement();
      NodeList children = root.getChildNodes();
      for (int index = 0; index < children.getLength(); index++) {
        if (!(children.item(index) instanceof Element element)
            || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())
            || !AndroidAttributeDependencies.isDeclaration(element)) {
          continue;
        }
        String name = AndroidResourcesParser.resourceName(element);
        MacroWinner previous = winners.get(name);
        if (previous != null && previous.priority() == priority) {
          throw new LocalizationParseException(
              "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
              "Same-priority Android resource conflict: " + name);
        }
        if (previous == null || previous.priority() < priority) {
          winners.put(name, new MacroWinner(priority, element));
        }
      }
    }
    Map<String, Element> definitions = new LinkedHashMap<>();
    winners.forEach((name, winner) -> definitions.put(name, winner.element()));
    return definitions;
  }

  private static Map<String, Element> styleables(List<AndroidResourceInput> sources) {
    Map<String, MacroWinner> winners = new LinkedHashMap<>();
    for (AndroidResourceInput source : sources) {
      int priority = priority(source.sourceSet());
      Element root = SecureXmlParser.parse(decode(source)).getDocumentElement();
      NodeList children = root.getChildNodes();
      for (int index = 0; index < children.getLength(); index++) {
        if (!(children.item(index) instanceof Element element)
            || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())
            || !AndroidAttributeDependencies.isStyleable(element)) {
          continue;
        }
        String name = AndroidResourcesParser.resourceName(element);
        MacroWinner previous = winners.get(name);
        if (previous != null && previous.priority() == priority) {
          throw new LocalizationParseException(
              "DUPLICATE_ANDROID_OVERLAY_RESOURCE",
              "Same-priority Android resource conflict: " + name);
        }
        if (previous == null || previous.priority() < priority) {
          winners.put(name, new MacroWinner(priority, element));
        }
      }
    }
    Map<String, Element> definitions = new LinkedHashMap<>();
    winners.forEach((name, winner) -> definitions.put(name, winner.element()));
    return definitions;
  }

  private static int priority(String sourceSet) {
    if (sourceSet == null) {
      throw invalidSourceSet();
    }
    return switch (sourceSet) {
      case "library" -> 0;
      case "main" -> 1;
      case "product_flavor" -> 2;
      case "build_type" -> 3;
      case "build_variant" -> 4;
      default -> throw invalidSourceSet();
    };
  }

  private static LocalizationParseException invalidSourceSet() {
    return new LocalizationParseException(
        "INVALID_ANDROID_OVERLAY_SOURCE_SET", "Unsupported Android overlay source-set priority");
  }

  private static List<ResourceIdentity> declarations(
      Element root, Map<String, Boolean> featureFlags, String pathFeatureFlag) {
    List<ResourceIdentity> resources = new ArrayList<>();
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (!(child instanceof Element element)) {
        continue;
      }
      if (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty()) {
        continue;
      }
      if (!AndroidResourcesParser.featureEnabled(element, featureFlags, pathFeatureFlag)) {
        continue;
      }
      String declarationType =
          "bag".equals(element.getLocalName())
              ? element.getAttribute("type").trim()
              : element.getLocalName();
      String kind =
          switch (declarationType) {
            case "string" -> "string";
            case "item" -> "string".equals(element.getAttribute("type").trim()) ? "string" : null;
            case "array", "integer-array", "string-array" -> "array";
            case "plurals" -> "plurals";
            default -> null;
          };
      if (kind != null) {
        String product = AndroidResourcesParser.resourceProduct(element);
        resources.add(
            new ResourceIdentity(
                kind,
                AndroidResourcesParser.resourceName(element),
                product.isEmpty() || "default".equals(product) ? "default" : product,
                AndroidResourcesParser.runtimeFeatureFlag(element, featureFlags, pathFeatureFlag)));
      }
    }
    return resources;
  }

  private record Layer(
      String sourceSet,
      int priority,
      LocalizationCatalog catalog,
      List<ResourceIdentity> resources) {}

  private record Winner(
      int priority, String sourceSet, List<Map.Entry<String, LocalizationMessage>> messages) {}

  private record MacroWinner(int priority, Element element) {}

  private record MacroSourceWinner(int priority, String sourceSet, String resourcePath) {}

  private record ResourceIdentity(String kind, String name, String product, String runtimeFlag) {

    boolean contains(String messageId, LocalizationMessage message) {
      String base = "default".equals(product) ? name : name + "@product=" + product;
      if (runtimeFlag != null) {
        base += "@flag=" + runtimeFlag;
      }
      if (!"array".equals(kind)) {
        return base.equals(messageId);
      }
      return messageId.startsWith(base + "[")
          && message.metadata() != null
          && name.equals(message.metadata().get("arrayName"));
    }
  }
}
