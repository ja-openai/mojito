package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Applies AAPT2 product selection before its final read-only feature-flag stripping. */
final class AndroidProductSelection {

  private AndroidProductSelection() {}

  static LocalizationCatalog select(
      String source,
      LocalizationCatalog catalog,
      Map<String, Boolean> featureFlags,
      List<String> selectedProducts,
      String resourcePath) {
    Set<String> requested = requested(selectedProducts);
    Map<ResourceIdentity, Map<String, Boolean>> groups = new LinkedHashMap<>();
    AndroidResourceConfiguration configuration = AndroidResourceConfiguration.parse(resourcePath);
    collect(
        SecureXmlParser.parse(source).getDocumentElement(),
        featureFlags,
        groups,
        configuration == null ? null : configuration.pathFeatureFlag());
    return filter(catalog, groups, requested);
  }

  static LocalizationCatalog selectOverlay(
      List<AndroidResourceInput> sources,
      LocalizationCatalog catalog,
      Map<String, Boolean> featureFlags,
      List<String> selectedProducts) {
    Set<String> requested = requested(selectedProducts);
    List<AndroidResourceInput> ordered = new ArrayList<>(sources);
    ordered.sort(Comparator.comparingInt(source -> priority(source.sourceSet())));
    Map<ResourceIdentity, Map<String, Boolean>> groups = new LinkedHashMap<>();
    for (AndroidResourceInput source : ordered) {
      Element root =
          SecureXmlParser.parse(
                  LocalizationFileConverters.decode(
                      source.source(),
                      LocalizationFileConverters.xmlCharset(
                          LocalizationFileFormat.ANDROID, source.source())))
              .getDocumentElement();
      collect(
          root,
          featureFlags,
          groups,
          AndroidResourceConfiguration.parse(source.resourcePath()).pathFeatureFlag());
    }
    return filter(catalog, groups, requested);
  }

  private static Set<String> requested(List<String> products) {
    if (products == null || products.isEmpty()) {
      throw invalidProduct();
    }
    Set<String> result = new LinkedHashSet<>();
    for (String product : products) {
      if (product == null
          || product.isEmpty()
          || !product.equals(product.trim())
          || product.indexOf(',') >= 0
          || !result.add(product)) {
        throw invalidProduct();
      }
    }
    if (result.size() > 1) {
      result.remove("default");
    }
    return result;
  }

  private static LocalizationParseException invalidProduct() {
    return new LocalizationParseException(
        "INVALID_ANDROID_PRODUCT", "Android build products must be distinct nonempty names");
  }

  private static void collect(
      Element root,
      Map<String, Boolean> featureFlags,
      Map<ResourceIdentity, Map<String, Boolean>> groups,
      String pathFeatureFlag) {
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (!(child instanceof Element element)
          || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())) {
        continue;
      }
      String kind = declarationKind(element);
      if (kind == null) {
        continue;
      }
      ResourceIdentity resource =
          new ResourceIdentity(kind, AndroidResourcesParser.resourceName(element));
      String rawProduct = AndroidResourcesParser.resourceProduct(element);
      String product =
          rawProduct.isEmpty() || "default".equals(rawProduct) ? "default" : rawProduct;
      boolean enabled =
          AndroidResourcesParser.featureEnabled(element, featureFlags, pathFeatureFlag);
      Map<String, Boolean> variants =
          groups.computeIfAbsent(resource, ignored -> new LinkedHashMap<>());
      if (enabled || !variants.containsKey(product)) {
        variants.put(product, enabled);
      }
    }
  }

  private static String declarationKind(Element element) {
    String kind =
        "bag".equals(element.getLocalName())
            ? element.getAttribute("type").trim()
            : element.getLocalName();
    return switch (kind) {
      case "string" -> "string";
      case "item" -> "string".equals(element.getAttribute("type").trim()) ? "string" : null;
      case "array", "integer-array", "string-array" -> "array";
      case "plurals" -> "plurals";
      default -> null;
    };
  }

  private static LocalizationCatalog filter(
      LocalizationCatalog catalog,
      Map<ResourceIdentity, Map<String, Boolean>> groups,
      Set<String> requested) {
    Map<ResourceIdentity, String> selected = new LinkedHashMap<>();
    for (Map.Entry<ResourceIdentity, Map<String, Boolean>> group : groups.entrySet()) {
      Map<String, Boolean> variants = group.getValue();
      if (!variants.containsKey("default")) {
        throw new LocalizationParseException(
            "MISSING_ANDROID_PRODUCT_DEFAULT",
            "No default product defined for resource " + group.getKey().name());
      }
      String choice = null;
      for (String product : variants.keySet()) {
        if (requested.contains(product)) {
          if (choice != null) {
            throw new LocalizationParseException(
                "AMBIGUOUS_ANDROID_PRODUCT",
                "Multiple selected products match resource " + group.getKey().name());
          }
          choice = product;
        }
      }
      if (choice == null) {
        choice = "default";
      }
      if (Boolean.TRUE.equals(variants.get(choice))) {
        selected.put(group.getKey(), choice);
      }
    }

    LocalizationCatalog result = new LocalizationCatalog(LocalizationFileFormat.ANDROID);
    result.setLocale(catalog.locale());
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      LocalizationMessage message = entry.getValue();
      Map<String, Object> metadata =
          message.metadata() == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(message.metadata());
      Object originalProduct = metadata.remove("androidProduct");
      String product =
          originalProduct instanceof String value && !value.isEmpty() && !"default".equals(value)
              ? value
              : "default";
      String base =
          metadata.get("arrayName") instanceof String arrayName
              ? arrayName
              : product.equals("default")
                  ? removeRuntimeFlag(entry.getKey(), metadata)
                  : removeRuntimeFlag(entry.getKey(), metadata).replace("@product=" + product, "");
      String kind =
          metadata.containsKey("arrayName")
              ? "array"
              : message.variants() == null ? "string" : "plurals";
      boolean runtime =
          "read_write".equals(metadata.get("androidFeatureFlagMode"))
              || "read_write".equals(metadata.get("androidPathFeatureFlagMode"));
      if (!runtime && !product.equals(selected.get(new ResourceIdentity(kind, base)))) {
        continue;
      }
      String id =
          product.equals("default")
              ? entry.getKey()
              : entry.getKey().replace("@product=" + product, "");
      result.add(
          id,
          LocalizationMessage.of(
              message.defaultMessage(),
              message.description(),
              message.variants(),
              message.placeholders(),
              metadata.isEmpty() ? null : metadata));
    }
    return result;
  }

  private static int priority(String sourceSet) {
    return switch (sourceSet) {
      case "library" -> 0;
      case "main" -> 1;
      case "product_flavor" -> 2;
      case "build_type" -> 3;
      case "build_variant" -> 4;
      default ->
          throw new LocalizationParseException(
              "INVALID_ANDROID_OVERLAY_SOURCE_SET", "Unsupported Android source-set priority");
    };
  }

  private static String removeRuntimeFlag(String id, Map<String, Object> metadata) {
    String expression =
        "read_write".equals(metadata.get("androidFeatureFlagMode"))
            ? (String) metadata.get("androidFeatureFlag")
            : "read_write".equals(metadata.get("androidPathFeatureFlagMode"))
                ? (String) metadata.get("androidPathFeatureFlag")
                : null;
    return expression == null ? id : id.substring(0, id.length() - expression.length() - 6);
  }

  private record ResourceIdentity(String kind, String name) {}
}
