package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native, Okapi-independent localization-resource extraction into the canonical JSON model. */
public final class LocalizationFileConverters {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern GETTEXT_CHARSET =
      Pattern.compile("(?i)Content-Type:[^\\r\\n]*?charset=([^\\s;\"\\\\]+)");
  private static final Pattern XML_DECLARED_ENCODING =
      Pattern.compile("^<\\?xml\\b(?:(?!\\?>)[\\s\\S])*?\\bencoding\\s*=\\s*([\"'])([^\"']+)\\1");

  private LocalizationFileConverters() {}

  public static LocalizationCatalog parse(LocalizationFileFormat format, String source) {
    return parse(format, source.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

  public static LocalizationCatalog parse(LocalizationFileFormat format, byte[] source) {
    return parse(format, source, StandardCharsets.UTF_8);
  }

  /**
   * Reconstruct bytes for resource content transported through Mojito's legacy {@link String} APIs.
   * A UTF-16 XML declaration must be paired with UTF-16 bytes even though the original BOM was
   * consumed by the CLI before upload.
   */
  public static byte[] encodeStringTransport(LocalizationFileFormat format, String source) {
    if (!supportsXmlEncoding(format)) {
      return source.getBytes(StandardCharsets.UTF_8);
    }
    Matcher declaration = XML_DECLARED_ENCODING.matcher(source);
    if (!declaration.find()) {
      return source.getBytes(StandardCharsets.UTF_8);
    }
    return switch (declaration.group(2).toUpperCase(Locale.ROOT)) {
      case "UTF-16", "UTF-16BE" -> withUtf16Bom(source, ByteOrder.BIG_ENDIAN);
      case "UTF-16LE" -> withUtf16Bom(source, ByteOrder.LITTLE_ENDIAN);
      default -> source.getBytes(StandardCharsets.UTF_8);
    };
  }

  /** Decode portable output back into the Java String transport used by Mojito REST and storage. */
  public static String decodeStringTransport(LocalizationFileFormat format, byte[] source) {
    return decode(source, xmlCharset(format, source));
  }

  /** Apply explicit Mojito filter options and intentional translator-workflow extraction policy. */
  public static LocalizationCatalog parseForMojito(
      LocalizationFileFormat format, byte[] source, List<String> filterOptions) {
    return MojitoLocalizationWorkflow.parse(format, source, filterOptions);
  }

  /** Apply explicit translation-import notes and locale-owned legacy plural completion. */
  public static LocalizationCatalog parseForMojitoImport(
      LocalizationFileFormat format,
      byte[] source,
      List<String> filterOptions,
      String targetLocale,
      boolean copyFormsOnImport) {
    return MojitoLocalizationWorkflow.parseImport(
        format, source, filterOptions, targetLocale, copyFormsOnImport);
  }

  /** Render localized resources with existing inheritance and format-specific output policies. */
  public static byte[] localizeForMojito(
      LocalizationFileFormat format,
      byte[] source,
      Map<String, String> translations,
      List<String> filterOptions,
      boolean removeUntranslated) {
    return localizeForMojito(format, source, translations, filterOptions, removeUntranslated, null);
  }

  /** Apply target-locale-specific Mojito plural output policy without changing native parsing. */
  public static byte[] localizeForMojito(
      LocalizationFileFormat format,
      byte[] source,
      Map<String, String> translations,
      List<String> filterOptions,
      boolean removeUntranslated,
      String targetLocale) {
    return MojitoLocalizationWorkflow.localize(
        format, source, translations, filterOptions, removeUntranslated, targetLocale);
  }

  /** Normalize legacy native placeholders before source-owned template rendering. */
  public static String normalizeMojitoTranslation(
      LocalizationFileFormat format,
      LocalizationMessage message,
      String variant,
      String translation) {
    return MojitoLocalizationWorkflow.normalizeTranslation(format, message, variant, translation);
  }

  /** Restore and quote one canonical GNU gettext translation for a native plural slot. */
  public static String quoteGettextTranslation(
      LocalizationMessage message, String translation, int pluralIndex) {
    return GettextPoWriter.quote(GettextPoWriter.restore(message, translation, pluralIndex));
  }

  /** Parse Android resources in their original values-directory configuration. */
  public static LocalizationCatalog parse(
      LocalizationFileFormat format, byte[] source, String androidResourcePath) {
    return parse(format, source, StandardCharsets.UTF_8, androidResourcePath);
  }

  public static LocalizationCatalog parse(
      LocalizationFileFormat format, byte[] source, Charset propertiesCharset) {
    return parse(format, source, propertiesCharset, null);
  }

  public static LocalizationCatalog parse(
      LocalizationFileFormat format,
      byte[] source,
      Charset propertiesCharset,
      String androidResourcePath) {
    return parse(format, source, propertiesCharset, androidResourcePath, Map.of());
  }

  /** Parse resources using explicit read-only Android build feature-flag values. */
  public static LocalizationCatalog parse(
      LocalizationFileFormat format,
      byte[] source,
      Charset propertiesCharset,
      String androidResourcePath,
      Map<String, Boolean> androidFeatureFlags) {
    return parse(format, source, propertiesCharset, androidResourcePath, androidFeatureFlags, null);
  }

  /** Parse the exact Android build selected by AAPT2 products and read-only feature flags. */
  public static LocalizationCatalog parse(
      LocalizationFileFormat format,
      byte[] source,
      Charset propertiesCharset,
      String androidResourcePath,
      Map<String, Boolean> androidFeatureFlags,
      List<String> androidSelectedProducts) {
    return parse(
        format,
        source,
        propertiesCharset,
        androidResourcePath,
        androidFeatureFlags,
        androidSelectedProducts,
        null);
  }

  /** Resolve package-qualified Android macro aliases against their actual application package. */
  public static LocalizationCatalog parse(
      LocalizationFileFormat format,
      byte[] source,
      Charset propertiesCharset,
      String androidResourcePath,
      Map<String, Boolean> androidFeatureFlags,
      List<String> androidSelectedProducts,
      String androidApplicationPackage) {
    if (format == LocalizationFileFormat.APPLE_STRINGSDICT
        && AppleBinaryPlistParser.matches(source)) {
      return new AppleBinaryPlistParser(source).parseStringsdict();
    }
    if (format == LocalizationFileFormat.APPLE_STRINGS && AppleBinaryPlistParser.matches(source)) {
      if (!AppleBinaryPlistParser.containsZero(source)) {
        try {
          return new AppleStringsParser(decode(source, null)).parse();
        } catch (LocalizationParseException ignored) {
          // A truncated binary header may contain no NUL; let its bounded binary parser diagnose
          // it.
        }
      }
      return new AppleBinaryPlistParser(source).parse();
    }
    Charset gettextCharset =
        format == LocalizationFileFormat.GETTEXT_PO ? gettextCharset(source) : null;
    Charset xmlCharset = xmlCharset(format, source);
    String decoded;
    try {
      decoded =
          decode(
              source,
              format == LocalizationFileFormat.GETTEXT_PO
                  ? gettextCharset
                  : format == LocalizationFileFormat.JAVA_PROPERTIES
                      ? propertiesCharset
                      : xmlCharset);
    } catch (LocalizationParseException exception) {
      if (format == LocalizationFileFormat.GETTEXT_PO
          && "INVALID_ENCODING".equals(exception.code())) {
        throw new LocalizationParseException(
            "INVALID_GETTEXT_ENCODING", "Malformed declared gettext charset", exception);
      }
      throw exception;
    }
    return switch (format) {
      case ANDROID -> {
        LocalizationCatalog catalog =
            new AndroidResourcesParser()
                .parse(
                    decoded,
                    androidResourcePath,
                    androidFeatureFlags,
                    Map.of(),
                    androidApplicationPackage);
        yield androidSelectedProducts == null
            ? catalog
            : AndroidProductSelection.select(
                decoded,
                catalog,
                androidFeatureFlags,
                androidSelectedProducts,
                androidResourcePath);
      }
      case APPLE_STRINGS -> new AppleStringsParser(decoded).parse();
      case APPLE_STRINGSDICT -> new AppleStringsdictParser().parse(decoded);
      case APPLE_XCSTRINGS -> new AppleXcstringsParser().parse(decoded);
      case CSV, CSV_ADOBE_MAGENTO -> CsvLocalizationFormat.parse(format, decoded);
      case GETTEXT_PO -> new GettextPoParser(gettextCharset).parse(decoded);
      case JAVA_PROPERTIES -> new JavaPropertiesParser().parse(decoded);
      case FORMATJS_JSON -> parseFormatJs(decoded);
      case YAML -> YamlSourceFormat.parse(decoded);
      case JAVASCRIPT, TYPESCRIPT -> JavaScriptSourceFormat.parse(format, decoded);
      case RESX, XTB -> XmlResourceParser.parse(format, decoded);
      case HTML -> HtmlSourceFormat.parse(decoded, false, true);
    };
  }

  /** Parse resources using ordered read-only/read-write AAPT2 feature-flag declarations. */
  public static LocalizationCatalog parseWithAndroidFeatureFlags(
      LocalizationFileFormat format,
      byte[] source,
      Charset propertiesCharset,
      String androidResourcePath,
      List<AndroidFeatureFlag> androidFeatureFlags,
      List<String> androidSelectedProducts) {
    return parseWithAndroidFeatureFlags(
        format,
        source,
        propertiesCharset,
        androidResourcePath,
        androidFeatureFlags,
        androidSelectedProducts,
        null);
  }

  /** Resolve fixed/runtime feature flags and package-qualified Android macro references. */
  public static LocalizationCatalog parseWithAndroidFeatureFlags(
      LocalizationFileFormat format,
      byte[] source,
      Charset propertiesCharset,
      String androidResourcePath,
      List<AndroidFeatureFlag> androidFeatureFlags,
      List<String> androidSelectedProducts,
      String androidApplicationPackage) {
    return parse(
        format,
        source,
        propertiesCharset,
        androidResourcePath,
        AndroidFeatureFlags.values(androidFeatureFlags),
        androidSelectedProducts,
        androidApplicationPackage);
  }

  /** Merge one resource configuration using explicit Android Gradle source-set priorities. */
  public static LocalizationCatalog parseAndroidOverlay(List<AndroidResourceInput> sources) {
    return parseAndroidOverlay(sources, Map.of());
  }

  /** Merge Android resource overlays after resolving explicit read-only build feature flags. */
  public static LocalizationCatalog parseAndroidOverlay(
      List<AndroidResourceInput> sources, Map<String, Boolean> androidFeatureFlags) {
    return parseAndroidOverlay(sources, androidFeatureFlags, null);
  }

  /** Merge source sets and select the actual Android product before conditional stripping. */
  public static LocalizationCatalog parseAndroidOverlay(
      List<AndroidResourceInput> sources,
      Map<String, Boolean> androidFeatureFlags,
      List<String> androidSelectedProducts) {
    return parseAndroidOverlay(sources, androidFeatureFlags, androidSelectedProducts, null);
  }

  /** Merge source sets while resolving package-qualified macro definitions and references. */
  public static LocalizationCatalog parseAndroidOverlay(
      List<AndroidResourceInput> sources,
      Map<String, Boolean> androidFeatureFlags,
      List<String> androidSelectedProducts,
      String androidApplicationPackage) {
    LocalizationCatalog catalog =
        AndroidResourceOverlays.parse(sources, androidFeatureFlags, androidApplicationPackage);
    return androidSelectedProducts == null
        ? catalog
        : AndroidProductSelection.selectOverlay(
            sources, catalog, androidFeatureFlags, androidSelectedProducts);
  }

  /** Merge overlays with ordered mutable/fixed AAPT2 feature-flag declarations. */
  public static LocalizationCatalog parseAndroidOverlayWithFeatureFlags(
      List<AndroidResourceInput> sources,
      List<AndroidFeatureFlag> androidFeatureFlags,
      List<String> androidSelectedProducts) {
    return parseAndroidOverlayWithFeatureFlags(
        sources, androidFeatureFlags, androidSelectedProducts, null);
  }

  /** Merge ordered flag definitions and local package-qualified Android macro references. */
  public static LocalizationCatalog parseAndroidOverlayWithFeatureFlags(
      List<AndroidResourceInput> sources,
      List<AndroidFeatureFlag> androidFeatureFlags,
      List<String> androidSelectedProducts,
      String androidApplicationPackage) {
    return parseAndroidOverlay(
        sources,
        AndroidFeatureFlags.values(androidFeatureFlags),
        androidSelectedProducts,
        androidApplicationPackage);
  }

  /** Regenerate deterministic, compiler-valid platform resources from a canonical catalog. */
  public static String write(LocalizationFileFormat format, LocalizationCatalog catalog) {
    return switch (format) {
      case ANDROID -> new AndroidResourcesWriter().write(catalog);
      case APPLE_STRINGS -> new AppleStringsWriter().write(catalog);
      case APPLE_STRINGSDICT -> new AppleStringsdictWriter().write(catalog);
      case APPLE_XCSTRINGS -> new AppleXcstringsWriter().write(catalog);
      case GETTEXT_PO -> new GettextPoWriter().write(catalog);
      case JAVA_PROPERTIES -> new JavaPropertiesWriter().write(catalog);
      case RESX, XTB -> XmlResourceParser.write(format, catalog);
      case CSV, CSV_ADOBE_MAGENTO -> CsvLocalizationFormat.write(format, catalog);
      default ->
          throw new LocalizationParseException(
              "UNSUPPORTED_OUTPUT_FORMAT",
              "Normalized writing is not available for " + format.id());
    };
  }

  /** Extract reversible, byte-addressed source slots without changing the canonical JSON model. */
  public static LocalizationSourceSkeleton extractSkeleton(
      LocalizationFileFormat format, byte[] source) {
    return extractSkeleton(format, source, StandardCharsets.UTF_8);
  }

  /** Independently expose every standalone Foundation device and presentation-width branch. */
  public static LocalizationSourceSkeleton extractSkeletonWithAppleVariations(byte[] source) {
    if (AppleBinaryPlistParser.matches(source)) {
      throw new LocalizationParseException(
          "UNSUPPORTED_SKELETON_SOURCE", "Expanded Foundation variations require XML source");
    }
    return AppleStringsdictSourceSkeleton.extract(source, true);
  }

  /** Independently expose every source-language Xcode String Catalog device branch. */
  public static LocalizationSourceSkeleton extractSkeletonWithXcodeDevices(byte[] source) {
    return AppleXcstringsSourceSkeleton.extract(source, true);
  }

  /**
   * Materialize missing or explicit-null Xcode source locales without changing default behavior.
   */
  public static LocalizationSourceSkeleton extractSkeletonWithXcodeSourceInsertion(byte[] source) {
    return AppleXcstringsSourceSkeleton.extractWithSourceInsertion(source);
  }

  /** Preserve source values while inserting or updating one normalized Xcode target locale. */
  public static LocalizationSourceSkeleton extractSkeletonWithXcodeTargetInsertion(
      byte[] source, String targetLocale) {
    return AppleXcstringsSourceSkeleton.extractWithTargetInsertion(source, targetLocale);
  }

  /** Preserve an explicit Reader encoding for legacy ISO-8859-1 properties sources. */
  public static LocalizationSourceSkeleton extractSkeleton(
      LocalizationFileFormat format, byte[] source, Charset propertiesCharset) {
    if ((format == LocalizationFileFormat.APPLE_STRINGS
            || format == LocalizationFileFormat.APPLE_STRINGSDICT)
        && AppleBinaryPlistParser.matches(source)) {
      return AppleBinarySourceSkeleton.extract(format, source);
    }
    return switch (format) {
      case ANDROID -> AndroidSourceSkeleton.extract(source);
      case APPLE_STRINGS -> AppleSourceSkeleton.extract(source);
      case APPLE_STRINGSDICT -> AppleStringsdictSourceSkeleton.extract(source);
      case APPLE_XCSTRINGS -> AppleXcstringsSourceSkeleton.extract(source);
      case CSV, CSV_ADOBE_MAGENTO -> CsvLocalizationFormat.extract(format, source);
      case GETTEXT_PO -> GettextPoSourceSkeleton.extract(source);
      case JAVA_PROPERTIES -> JavaPropertiesSourceSkeleton.extract(source, propertiesCharset);
      case YAML -> YamlSourceFormat.extract(source);
      case JAVASCRIPT, TYPESCRIPT -> JavaScriptSourceFormat.extract(format, source);
      case RESX, XTB -> XmlResourceSourceSkeleton.extract(format, source);
      case HTML -> HtmlSourceFormat.extract(source, false, true);
      default ->
          throw new LocalizationParseException(
              "UNSUPPORTED_SKELETON_FORMAT",
              "Source skeletons are not available for " + format.id());
    };
  }

  /** Retain ordered AAPT2 build/runtime feature-flag context beside Android source ownership. */
  public static LocalizationSourceSkeleton extractSkeletonWithAndroidFeatureFlags(
      byte[] source, List<AndroidFeatureFlag> featureFlags) {
    return AndroidSourceSkeleton.extract(source, null, featureFlags);
  }

  /** Preserve original values-directory qualifiers and ordered feature declarations. */
  public static LocalizationSourceSkeleton extractSkeletonWithAndroidContext(
      byte[] source, String resourcePath, List<AndroidFeatureFlag> featureFlags) {
    return AndroidSourceSkeleton.extract(source, resourcePath, featureFlags);
  }

  /** Preserve all original source files while assigning slots only to winning overlay resources. */
  public static AndroidOverlaySourceSkeleton extractAndroidOverlaySkeleton(
      List<AndroidResourceInput> sources) {
    return extractAndroidOverlaySkeleton(sources, List.of(), null, null);
  }

  /**
   * Retain build flags, selected products, and package context beside multi-file byte ownership.
   */
  public static AndroidOverlaySourceSkeleton extractAndroidOverlaySkeleton(
      List<AndroidResourceInput> sources,
      List<AndroidFeatureFlag> featureFlags,
      List<String> selectedProducts,
      String applicationPackage) {
    return AndroidOverlaySourceSkeletons.extract(
        sources, featureFlags, selectedProducts, applicationPackage);
  }

  /** Inject translations only into actual overlay winners and preserve every losing source byte. */
  public static List<AndroidResourceInput> renderAndroidOverlaySkeleton(
      AndroidOverlaySourceSkeleton skeleton, Map<String, String> translations) {
    return AndroidOverlaySourceSkeletons.render(skeleton, translations);
  }

  /** Inject canonical translations while preserving every untouched original source byte. */
  public static byte[] renderSkeleton(
      LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if ("BINARY_PLIST".equals(skeleton.encoding())) {
      return AppleBinarySourceSkeleton.render(skeleton, translations);
    }
    return switch (skeleton.sourceFormat()) {
      case "android" -> AndroidSourceSkeleton.render(skeleton, translations);
      case "apple_strings" -> AppleSourceSkeleton.render(skeleton, translations);
      case "apple_stringsdict" -> AppleStringsdictSourceSkeleton.render(skeleton, translations);
      case "apple_xcstrings" -> AppleXcstringsSourceSkeleton.render(skeleton, translations);
      case "csv", "csv_adobe_magento" -> CsvLocalizationFormat.render(skeleton, translations);
      case "gettext_po" -> GettextPoSourceSkeleton.render(skeleton, translations);
      case "java_properties" -> JavaPropertiesSourceSkeleton.render(skeleton, translations);
      case "yaml" -> YamlSourceFormat.render(skeleton, translations);
      case "javascript", "typescript" -> JavaScriptSourceFormat.render(skeleton, translations);
      case "resx", "xtb" -> XmlResourceSourceSkeleton.render(skeleton, translations);
      case "html" -> HtmlSourceFormat.render(skeleton, translations);
      default ->
          throw new LocalizationParseException(
              "UNSUPPORTED_SKELETON_FORMAT", "Unsupported source-preserving skeleton format");
    };
  }

  static String decode(byte[] input, Charset fallback) {
    int offset = 0;
    Charset charset = fallback == null ? StandardCharsets.UTF_8 : fallback;
    if (input.length >= 3
        && (input[0] & 0xff) == 0xef
        && (input[1] & 0xff) == 0xbb
        && (input[2] & 0xff) == 0xbf) {
      offset = 3;
      charset = StandardCharsets.UTF_8;
    } else if (input.length >= 2 && (input[0] & 0xff) == 0xff && (input[1] & 0xff) == 0xfe) {
      offset = 2;
      charset = StandardCharsets.UTF_16LE;
    } else if (input.length >= 2 && (input[0] & 0xff) == 0xfe && (input[1] & 0xff) == 0xff) {
      offset = 2;
      charset = StandardCharsets.UTF_16BE;
    }
    try {
      return charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input, offset, input.length - offset))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new LocalizationParseException(
          "INVALID_ENCODING", "Malformed resource encoding", exception);
    }
  }

  static Charset xmlCharset(LocalizationFileFormat format, byte[] source) {
    if (!supportsXmlEncoding(format)) {
      return null;
    }

    boolean utf16LittleEndianBom =
        source.length >= 2 && source[0] == (byte) 0xff && source[1] == (byte) 0xfe;
    boolean utf16BigEndianBom =
        source.length >= 2 && source[0] == (byte) 0xfe && source[1] == (byte) 0xff;
    boolean utf16LittleEndian =
        utf16LittleEndianBom
            || format == LocalizationFileFormat.ANDROID && bomlessUtf16(source, true);
    boolean utf16BigEndian =
        utf16BigEndianBom
            || format == LocalizationFileFormat.ANDROID && bomlessUtf16(source, false);
    boolean utf8Bom =
        source.length >= 3
            && source[0] == (byte) 0xef
            && source[1] == (byte) 0xbb
            && source[2] == (byte) 0xbf;
    String prefix =
        utf16LittleEndian || utf16BigEndian
            ? decode(
                source, utf16LittleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE)
            : new String(
                source,
                utf8Bom ? 3 : 0,
                xmlDeclarationLength(source, utf8Bom ? 3 : 0),
                StandardCharsets.ISO_8859_1);
    Matcher declaration = XML_DECLARED_ENCODING.matcher(prefix);
    if (!declaration.find()) {
      return utf16LittleEndian && !utf16LittleEndianBom
          ? StandardCharsets.UTF_16LE
          : utf16BigEndian && !utf16BigEndianBom ? StandardCharsets.UTF_16BE : null;
    }

    String declared = declaration.group(2).toUpperCase(Locale.ROOT);
    boolean apple = format != LocalizationFileFormat.ANDROID;
    if (apple && (utf16LittleEndian || utf16BigEndian || utf8Bom)) {
      return utf16LittleEndian
          ? StandardCharsets.UTF_16LE
          : utf16BigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8;
    }
    Charset charset =
        switch (declared) {
          case "UTF-8" -> StandardCharsets.UTF_8;
          case "UTF8", "UTF_8" -> {
            if (!apple) {
              throw invalidXmlEncoding(declared);
            }
            yield StandardCharsets.UTF_8;
          }
          case "UTF-16" -> {
            if (!utf16LittleEndian && !utf16BigEndian) {
              throw invalidXmlEncoding(declared);
            }
            yield utf16LittleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
          }
          case "UTF-16LE" -> {
            if (!utf16LittleEndian) {
              throw invalidXmlEncoding(declared);
            }
            yield StandardCharsets.UTF_16LE;
          }
          case "UTF-16BE" -> {
            if (!utf16BigEndian) {
              throw invalidXmlEncoding(declared);
            }
            yield StandardCharsets.UTF_16BE;
          }
          case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
          case "LATIN1" -> {
            if (!apple) {
              throw invalidXmlEncoding(declared);
            }
            yield StandardCharsets.ISO_8859_1;
          }
          case "US-ASCII" -> StandardCharsets.US_ASCII;
          case "ASCII" -> {
            if (!apple) {
              throw invalidXmlEncoding(declared);
            }
            yield StandardCharsets.US_ASCII;
          }
          default -> throw invalidXmlEncoding(declared);
        };
    if ((utf16LittleEndian || utf16BigEndian)
            && charset != StandardCharsets.UTF_16LE
            && charset != StandardCharsets.UTF_16BE
        || !(utf16LittleEndian || utf16BigEndian)
            && (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE)
        || utf8Bom && charset != StandardCharsets.UTF_8) {
      throw invalidXmlEncoding(declared);
    }
    return charset;
  }

  private static boolean supportsXmlEncoding(LocalizationFileFormat format) {
    return format == LocalizationFileFormat.ANDROID
        || format == LocalizationFileFormat.APPLE_STRINGS
        || format == LocalizationFileFormat.APPLE_STRINGSDICT
        || format == LocalizationFileFormat.RESX
        || format == LocalizationFileFormat.XTB;
  }

  private static byte[] withUtf16Bom(String source, ByteOrder byteOrder) {
    Charset charset =
        byteOrder == ByteOrder.LITTLE_ENDIAN
            ? StandardCharsets.UTF_16LE
            : StandardCharsets.UTF_16BE;
    byte[] content = source.getBytes(charset);
    byte[] encoded = new byte[content.length + 2];
    encoded[0] = byteOrder == ByteOrder.LITTLE_ENDIAN ? (byte) 0xff : (byte) 0xfe;
    encoded[1] = byteOrder == ByteOrder.LITTLE_ENDIAN ? (byte) 0xfe : (byte) 0xff;
    System.arraycopy(content, 0, encoded, 2, content.length);
    return encoded;
  }

  private static boolean bomlessUtf16(byte[] source, boolean littleEndian) {
    if (source.length < 4) {
      return false;
    }
    int first = littleEndian ? source[0] & 0xff : source[1] & 0xff;
    int second = littleEndian ? source[2] & 0xff : source[3] & 0xff;
    return (littleEndian ? source[1] == 0 && source[3] == 0 : source[0] == 0 && source[2] == 0)
        && (first == '<' || first == ' ' || first == '\t' || first == '\n' || first == '\r')
        && second <= 0x7f;
  }

  private static int xmlDeclarationLength(byte[] source, int offset) {
    for (int index = offset; index + 1 < source.length; index++) {
      if (source[index] == '?' && source[index + 1] == '>') {
        return index + 2 - offset;
      }
    }
    return source.length - offset;
  }

  private static LocalizationParseException invalidXmlEncoding(String declared) {
    return new LocalizationParseException(
        "INVALID_XML", "Unsupported or contradictory XML encoding declaration: " + declared);
  }

  static Charset gettextCharset(byte[] input) {
    if ((input.length >= 3
            && (input[0] & 0xff) == 0xef
            && (input[1] & 0xff) == 0xbb
            && (input[2] & 0xff) == 0xbf)
        || (input.length >= 2
            && (((input[0] & 0xff) == 0xff && (input[1] & 0xff) == 0xfe)
                || ((input[0] & 0xff) == 0xfe && (input[1] & 0xff) == 0xff)))) {
      throw new LocalizationParseException(
          "INVALID_GETTEXT_ENCODING",
          "GNU gettext PO files do not accept Unicode byte-order marks");
    }
    String header = new String(input, StandardCharsets.ISO_8859_1);
    Matcher matcher = GETTEXT_CHARSET.matcher(header);
    if (!matcher.find()) {
      if (header.toLowerCase(Locale.ROOT).contains("content-type:")) {
        throw new LocalizationParseException(
            "INVALID_GETTEXT_ENCODING", "Gettext Content-Type declares no usable charset");
      }
      return StandardCharsets.UTF_8;
    }
    String declared = matcher.group(1).toUpperCase(Locale.ROOT);
    return switch (declared) {
      case "UTF-8" -> StandardCharsets.UTF_8;
      case "ISO-8859-1", "ISO_8859-1" -> StandardCharsets.ISO_8859_1;
      case "CP1252" -> Charset.forName("windows-1252");
      case "ASCII", "US-ASCII" -> StandardCharsets.US_ASCII;
      default ->
          throw new LocalizationParseException(
              "INVALID_GETTEXT_ENCODING",
              "Unsupported or nonportable gettext charset: " + declared);
    };
  }

  private static LocalizationCatalog parseFormatJs(String source) {
    try {
      JsonNode root = JSON.readTree(source);
      if (root == null || !root.isObject()) {
        throw new LocalizationParseException("INVALID_FORMATJS", "Expected a JSON message object");
      }
      LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.FORMATJS_JSON);
      JsonNode messages = root;
      if (root.has("schemaVersion")) {
        if (root.path("schemaVersion").asInt() != 1 || !root.path("messages").isObject()) {
          throw new LocalizationParseException(
              "INVALID_FORMATJS", "Unsupported canonical catalog wrapper");
        }
        messages = root.get("messages");
        if (root.path("locale").isTextual()) {
          catalog.setLocale(root.get("locale").asText());
        }
      }
      Iterator<Map.Entry<String, JsonNode>> entries = messages.fields();
      while (entries.hasNext()) {
        Map.Entry<String, JsonNode> entry = entries.next();
        JsonNode descriptor = entry.getValue();
        String message;
        String description = null;
        Map<String, String> variants = null;
        List<LocalizationPlaceholder> placeholders = null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (descriptor.isTextual()) {
          message = descriptor.textValue();
        } else if (descriptor.isObject() && descriptor.path("defaultMessage").isTextual()) {
          message = descriptor.get("defaultMessage").textValue();
          if (descriptor.path("description").isTextual()) {
            description = descriptor.get("description").textValue();
          }
          if (descriptor.path("variants").isObject()) {
            variants = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> values = descriptor.get("variants").fields();
            while (values.hasNext()) {
              Map.Entry<String, JsonNode> variant = values.next();
              if (!variant.getValue().isTextual()) {
                throw new LocalizationParseException(
                    "INVALID_FORMATJS", "Plural variants must be strings");
              }
              variants.put(variant.getKey(), variant.getValue().asText());
            }
          }
          if (descriptor.path("placeholders").isArray()) {
            placeholders = new ArrayList<>();
            for (JsonNode placeholder : descriptor.get("placeholders")) {
              placeholders.add(JSON.treeToValue(placeholder, LocalizationPlaceholder.class));
            }
          }
          if (descriptor.path("metadata").isObject()) {
            metadata.putAll(JSON.convertValue(descriptor.get("metadata"), Map.class));
          }
          Map<String, Object> extra = new LinkedHashMap<>();
          Iterator<Map.Entry<String, JsonNode>> fields = descriptor.fields();
          while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!List.of("defaultMessage", "description", "variants", "placeholders", "metadata")
                .contains(field.getKey())) {
              extra.put(field.getKey(), JSON.convertValue(field.getValue(), Object.class));
            }
          }
          if (!extra.isEmpty()) {
            metadata.put("formatjs", extra);
          }
        } else {
          throw new LocalizationParseException(
              "INVALID_FORMATJS", "Message descriptor must contain a string defaultMessage");
        }
        catalog.add(
            entry.getKey(),
            LocalizationMessage.of(message, description, variants, placeholders, metadata));
      }
      return catalog;
    } catch (LocalizationParseException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new LocalizationParseException("INVALID_FORMATJS", "Invalid FormatJS JSON", exception);
    }
  }
}
