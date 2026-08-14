package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.BinaryLowerBoundBytes;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.ExportPolicy;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Provenance;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.SizeEstimates;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Source;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes and validates the first memory-mappable compiled term-pack layout.
 *
 * <p>This codec is intentionally a binary contract spike: it proves the row layout can carry the
 * current renderer model and that malformed sections fail before rendering. Provenance remains cold
 * metadata: production packaging can keep it in a sidecar or explicitly embed it in the optional
 * metadata section without changing the hot row tables.
 */
public class CompiledTermPackBinaryCodec {

  static final int SECTION_STRINGS = 0;
  static final int SECTION_STRING_OFFSETS = 1;
  static final int SECTION_TERMS = 2;
  static final int SECTION_FORM_SETS = 3;
  static final int SECTION_FORM_ROWS = 4;
  static final int SECTION_BINDINGS = 5;
  static final int SECTION_METADATA = 6;

  static final int SECTION_COUNT = 7;
  static final int SECTION_DIRECTORY_ENTRY_BYTES = 8;
  static final int SECTION_DIRECTORY_OFFSET = 32;
  static final int HEADER_BYTES =
      SECTION_DIRECTORY_OFFSET + SECTION_COUNT * SECTION_DIRECTORY_ENTRY_BYTES;

  static final int TERM_ROW_BYTES = 20;
  static final int FORM_SET_ROW_BYTES = 12;
  static final int FORM_ROW_BYTES = 12;
  static final int BINDING_ROW_BYTES = 12;

  private static final byte[] MAGIC = {'M', '2', 'I', 'F'};
  static final String METADATA_SCHEMA = "mojito-mf2-inflection/compiled-term-pack-metadata/v0";
  private static final int VERSION = 0;
  private static final int FLAGS = 0;
  private static final int NULL_INDEX = -1;
  private static final int FORM_ROW_PATTERN_FLAG = 1;

  private final ObjectMapper objectMapper = new ObjectMapper();

  public byte[] encode(CompiledTermPack pack) {
    return encode(pack, false);
  }

  public byte[] encodeWithEmbeddedMetadata(CompiledTermPack pack) {
    return encode(pack, true);
  }

  private byte[] encode(CompiledTermPack pack, boolean embedMetadata) {
    Objects.requireNonNull(pack, "pack");

    List<String> strings = new ArrayList<>(pack.strings());
    int localeIndex = stringIndex(strings, pack.locale());
    StringTable stringTable = buildStringTable(strings);
    List<FormSetDirectoryRow> formSetRows = new ArrayList<>();
    List<FormRow> formRows = new ArrayList<>();
    for (FormSet formSet : pack.formSets()) {
      int firstFormRow = formRows.size();
      formRows.addAll(formSet.forms());
      formSetRows.add(
          new FormSetDirectoryRow(formSet.term(), firstFormRow, formSet.forms().size()));
    }
    byte[] metadataBytes =
        embedMetadata ? metadataBytes(pack.provenance(), pack.exportPolicy()) : new byte[0];

    int[] sectionLengths = new int[SECTION_COUNT];
    sectionLengths[SECTION_STRINGS] = stringTable.bytes().length;
    sectionLengths[SECTION_STRING_OFFSETS] = Math.multiplyExact(stringTable.offsets().length, 4);
    sectionLengths[SECTION_TERMS] = Math.multiplyExact(pack.terms().size(), TERM_ROW_BYTES);
    sectionLengths[SECTION_FORM_SETS] = Math.multiplyExact(formSetRows.size(), FORM_SET_ROW_BYTES);
    sectionLengths[SECTION_FORM_ROWS] = Math.multiplyExact(formRows.size(), FORM_ROW_BYTES);
    sectionLengths[SECTION_BINDINGS] = 0;
    sectionLengths[SECTION_METADATA] = metadataBytes.length;

    int[] sectionOffsets = sectionOffsets(sectionLengths);
    ByteBuffer output =
        ByteBuffer.allocate(totalBytes(sectionOffsets, sectionLengths))
            .order(ByteOrder.LITTLE_ENDIAN);
    writeHeader(
        output,
        localeIndex,
        stringTable.strings().size(),
        pack.terms().size(),
        formSetRows.size(),
        formRows.size(),
        0,
        sectionOffsets,
        sectionLengths);
    writeStrings(output, sectionOffsets[SECTION_STRINGS], stringTable.bytes());
    writeStringOffsets(output, sectionOffsets[SECTION_STRING_OFFSETS], stringTable.offsets());
    writeTerms(output, sectionOffsets[SECTION_TERMS], pack.terms());
    writeFormSets(output, sectionOffsets[SECTION_FORM_SETS], formSetRows);
    writeFormRows(output, sectionOffsets[SECTION_FORM_ROWS], formRows);
    writeMetadata(output, sectionOffsets[SECTION_METADATA], metadataBytes);
    return output.array();
  }

  public CompiledTermPack decode(byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    return decode(ByteBuffer.wrap(payload), Provenance.empty());
  }

  public CompiledTermPack decode(byte[] payload, Provenance provenance) {
    Objects.requireNonNull(payload, "payload");
    return decode(ByteBuffer.wrap(payload), provenance);
  }

  public CompiledTermPack decode(ByteBuffer payload) {
    return decode(payload, Provenance.empty());
  }

  public CompiledTermPack decode(ByteBuffer payload, Provenance provenance) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(provenance, "provenance");
    ByteBuffer buffer = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
    if (buffer.limit() < HEADER_BYTES) {
      throw new IllegalArgumentException("Compiled term pack is smaller than the header");
    }

    validateMagic(buffer);
    int version = Short.toUnsignedInt(buffer.getShort());
    if (version != VERSION) {
      throw new IllegalArgumentException("Unsupported compiled term pack version: " + version);
    }
    int flags = Short.toUnsignedInt(buffer.getShort());
    if (flags != FLAGS) {
      throw new IllegalArgumentException("Unsupported compiled term pack flags: " + flags);
    }

    int localeIndex = buffer.getInt();
    int stringCount = requireNonNegative(buffer.getInt(), "stringCount");
    int termCount = requireNonNegative(buffer.getInt(), "termCount");
    int formSetCount = requireNonNegative(buffer.getInt(), "formSetCount");
    int formRowCount = requireNonNegative(buffer.getInt(), "formRowCount");
    int bindingRowCount = requireNonNegative(buffer.getInt(), "bindingRowCount");

    SectionBounds[] sections = readSections(buffer);
    validateSections(
        sections,
        buffer.limit(),
        stringCount,
        termCount,
        formSetCount,
        formRowCount,
        bindingRowCount);

    List<String> strings =
        readStrings(
            section(buffer, sections[SECTION_STRINGS]),
            section(buffer, sections[SECTION_STRING_OFFSETS]),
            stringCount);
    String locale =
        localeIndex == NULL_INDEX ? null : stringAt(strings, localeIndex, "localeString");
    List<FormRow> formRows =
        readFormRows(section(buffer, sections[SECTION_FORM_ROWS]), formRowCount);
    List<FormSet> formSets =
        readFormSets(section(buffer, sections[SECTION_FORM_SETS]), formSetCount, formRows);
    List<TermRow> terms = readTerms(section(buffer, sections[SECTION_TERMS]), termCount);
    DecodedMetadata decodedMetadata =
        readMetadata(section(buffer, sections[SECTION_METADATA]), provenance);

    BinaryLowerBoundBytes binaryLowerBoundBytes =
        new BinaryLowerBoundBytes(
            sections[SECTION_STRINGS].length(),
            sections[SECTION_TERMS].length(),
            sections[SECTION_FORM_ROWS].length(),
            sections[SECTION_BINDINGS].length(),
            sections[SECTION_STRINGS].length()
                + sections[SECTION_TERMS].length()
                + sections[SECTION_FORM_ROWS].length()
                + sections[SECTION_BINDINGS].length());
    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        locale,
        strings,
        terms,
        formSets,
        decodedMetadata.provenance(),
        new SizeEstimates(null, binaryLowerBoundBytes),
        decodedMetadata.exportPolicy());
  }

  private byte[] metadataBytes(Provenance provenance, ExportPolicy exportPolicy) {
    if (Provenance.empty().equals(provenance) && !exportPolicy.present()) {
      return new byte[0];
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schema", METADATA_SCHEMA);
    if (!Provenance.empty().equals(provenance)) {
      metadata.put("provenance", provenanceJson(provenance));
    }
    if (exportPolicy.present()) {
      metadata.put("exportPolicy", exportPolicyJson(exportPolicy));
    }
    return objectMapper.writeValueAsStringUnchecked(metadata).getBytes(StandardCharsets.UTF_8);
  }

  private Map<String, Object> exportPolicyJson(ExportPolicy exportPolicy) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runtimeExport", exportPolicy.runtimeExport());
    payload.put("compositionMode", exportPolicy.compositionMode());
    payload.put("deferredComposition", exportPolicy.deferredComposition());
    payload.put("automaticExportTerms", exportPolicy.automaticExportTerms());
    payload.put("reviewRequiredTerms", exportPolicy.reviewRequiredTerms());
    payload.put("blockedTerms", exportPolicy.blockedTerms());
    payload.put("reviewRequiredReasons", exportPolicy.reviewRequiredReasons());
    payload.put("blockedReasons", exportPolicy.blockedReasons());
    return payload;
  }

  private Map<String, Object> provenanceJson(Provenance provenance) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (provenance.license() != null) {
      payload.put("license", provenance.license());
    }
    if (provenance.generator() != null) {
      payload.put("generator", provenance.generator());
    }
    payload.put("sourceLabels", provenance.sourceLabels());
    List<Object> sources = new ArrayList<>();
    for (Source source : provenance.sources()) {
      Map<String, Object> sourcePayload = new LinkedHashMap<>();
      sourcePayload.put("path", source.path());
      sourcePayload.put("byteSize", source.byteSize());
      sourcePayload.put("sha256", source.sha256());
      sourcePayload.put("gitLfsPointer", source.gitLfsPointer());
      sources.add(sourcePayload);
    }
    payload.put("sources", sources);
    return payload;
  }

  private int stringIndex(List<String> strings, String value) {
    if (value == null) {
      return NULL_INDEX;
    }
    int existingIndex = strings.indexOf(value);
    if (existingIndex >= 0) {
      return existingIndex;
    }
    strings.add(value);
    return strings.size() - 1;
  }

  private StringTable buildStringTable(List<String> strings) {
    List<byte[]> encodedStrings = new ArrayList<>();
    int totalBytes = 0;
    int[] offsets = new int[strings.size() + 1];
    for (int i = 0; i < strings.size(); i++) {
      offsets[i] = totalBytes;
      byte[] encoded = strings.get(i).getBytes(StandardCharsets.UTF_8);
      encodedStrings.add(encoded);
      totalBytes = Math.addExact(totalBytes, encoded.length + 1);
    }
    offsets[strings.size()] = totalBytes;

    ByteBuffer bytes = ByteBuffer.allocate(totalBytes);
    for (byte[] encoded : encodedStrings) {
      bytes.put(encoded);
      bytes.put((byte) 0);
    }
    return new StringTable(List.copyOf(strings), bytes.array(), offsets);
  }

  private int[] sectionOffsets(int[] sectionLengths) {
    int[] sectionOffsets = new int[SECTION_COUNT];
    int offset = HEADER_BYTES;
    for (int section = 0; section < SECTION_COUNT; section++) {
      offset = align4(offset);
      sectionOffsets[section] = offset;
      offset = Math.addExact(offset, sectionLengths[section]);
    }
    return sectionOffsets;
  }

  private int totalBytes(int[] sectionOffsets, int[] sectionLengths) {
    if (sectionOffsets.length == 0) {
      return HEADER_BYTES;
    }
    int lastSection = sectionOffsets.length - 1;
    return Math.addExact(sectionOffsets[lastSection], sectionLengths[lastSection]);
  }

  private int align4(int value) {
    return Math.addExact(value, 3) & ~3;
  }

  private void writeHeader(
      ByteBuffer output,
      int localeIndex,
      int stringCount,
      int termCount,
      int formSetCount,
      int formRowCount,
      int bindingRowCount,
      int[] sectionOffsets,
      int[] sectionLengths) {
    output.position(0);
    output.put(MAGIC);
    output.putShort((short) VERSION);
    output.putShort((short) FLAGS);
    output.putInt(localeIndex);
    output.putInt(stringCount);
    output.putInt(termCount);
    output.putInt(formSetCount);
    output.putInt(formRowCount);
    output.putInt(bindingRowCount);
    for (int section = 0; section < SECTION_COUNT; section++) {
      output.putInt(sectionOffsets[section]);
      output.putInt(sectionLengths[section]);
    }
  }

  private void writeStrings(ByteBuffer output, int offset, byte[] strings) {
    output.position(offset);
    output.put(strings);
  }

  private void writeStringOffsets(ByteBuffer output, int offset, int[] stringOffsets) {
    output.position(offset);
    for (int stringOffset : stringOffsets) {
      output.putInt(stringOffset);
    }
  }

  private void writeTerms(ByteBuffer output, int offset, List<TermRow> terms) {
    output.position(offset);
    for (TermRow term : terms) {
      output.putInt(term.id());
      output.putInt(term.text());
      output.putInt(term.featureBits());
      output.putInt(term.sense() == null ? NULL_INDEX : term.sense());
      output.putInt(term.formSet());
    }
  }

  private void writeFormSets(ByteBuffer output, int offset, List<FormSetDirectoryRow> formSetRows) {
    output.position(offset);
    for (FormSetDirectoryRow formSet : formSetRows) {
      output.putInt(formSet.term());
      output.putInt(formSet.firstFormRow());
      output.putInt(formSet.formRowCount());
    }
  }

  private void writeFormRows(ByteBuffer output, int offset, List<FormRow> formRows) {
    output.position(offset);
    for (FormRow formRow : formRows) {
      output.putInt(formRow.key());
      output.putInt(formRow.value());
      output.putInt(formRow.pattern() ? FORM_ROW_PATTERN_FLAG : 0);
    }
  }

  private void writeMetadata(ByteBuffer output, int offset, byte[] metadataBytes) {
    output.position(offset);
    output.put(metadataBytes);
  }

  private void validateMagic(ByteBuffer buffer) {
    for (byte expected : MAGIC) {
      byte actual = buffer.get();
      if (actual != expected) {
        throw new IllegalArgumentException("Invalid compiled term pack magic");
      }
    }
  }

  private int requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative: " + value);
    }
    return value;
  }

  private SectionBounds[] readSections(ByteBuffer buffer) {
    SectionBounds[] sections = new SectionBounds[SECTION_COUNT];
    String[] names = {
      "strings", "stringOffsets", "terms", "formSets", "formRows", "bindings", "metadata"
    };
    buffer.position(SECTION_DIRECTORY_OFFSET);
    for (int section = 0; section < SECTION_COUNT; section++) {
      sections[section] = new SectionBounds(names[section], buffer.getInt(), buffer.getInt());
    }
    return sections;
  }

  private void validateSections(
      SectionBounds[] sections,
      int payloadBytes,
      int stringCount,
      int termCount,
      int formSetCount,
      int formRowCount,
      int bindingRowCount) {
    requireSectionLength(
        sections[SECTION_STRING_OFFSETS],
        sectionBytes(addOne(stringCount, "stringOffsets"), 4, "stringOffsets"));
    requireSectionLength(sections[SECTION_TERMS], sectionBytes(termCount, TERM_ROW_BYTES, "terms"));
    requireSectionLength(
        sections[SECTION_FORM_SETS], sectionBytes(formSetCount, FORM_SET_ROW_BYTES, "formSets"));
    requireSectionLength(
        sections[SECTION_FORM_ROWS], sectionBytes(formRowCount, FORM_ROW_BYTES, "formRows"));
    requireSectionLength(
        sections[SECTION_BINDINGS], sectionBytes(bindingRowCount, BINDING_ROW_BYTES, "bindings"));
    if (bindingRowCount != 0) {
      throw new IllegalArgumentException(
          "Compiled term pack bindings section is reserved and must be empty");
    }

    List<SectionBounds> nonEmptySections = new ArrayList<>();
    int maxSectionEnd = HEADER_BYTES;
    for (SectionBounds section : sections) {
      if (section.offset() < HEADER_BYTES) {
        throw new IllegalArgumentException("Section offset overlaps header: " + section.name());
      }
      if (section.offset() % 4 != 0) {
        throw new IllegalArgumentException(
            "Section offset is not 4-byte aligned: " + section.name());
      }
      if (section.length() < 0 || section.offset() > payloadBytes - section.length()) {
        throw new IllegalArgumentException("Section out of bounds: " + section.name());
      }
      maxSectionEnd = Math.max(maxSectionEnd, section.offset() + section.length());
      if (section.length() > 0) {
        nonEmptySections.add(section);
      }
    }
    nonEmptySections.sort(Comparator.comparingInt(SectionBounds::offset));
    int previousEnd = HEADER_BYTES;
    for (SectionBounds section : nonEmptySections) {
      if (section.offset() < previousEnd) {
        throw new IllegalArgumentException("Section overlaps previous section: " + section.name());
      }
      previousEnd = section.offset() + section.length();
    }
    if (maxSectionEnd != payloadBytes) {
      throw new IllegalArgumentException(
          "Compiled term pack contains trailing bytes: expected "
              + maxSectionEnd
              + ", got "
              + payloadBytes);
    }
  }

  private int addOne(int value, String field) {
    try {
      return Math.addExact(value, 1);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Section length overflow for " + field, e);
    }
  }

  private int sectionBytes(int count, int rowBytes, String section) {
    try {
      return Math.multiplyExact(count, rowBytes);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Section length overflow for " + section, e);
    }
  }

  private void requireSectionLength(SectionBounds section, int expectedLength) {
    if (section.length() != expectedLength) {
      throw new IllegalArgumentException(
          "Unexpected section length for "
              + section.name()
              + ": expected "
              + expectedLength
              + ", got "
              + section.length());
    }
  }

  private ByteBuffer section(ByteBuffer buffer, SectionBounds section) {
    ByteBuffer duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    duplicate.position(section.offset());
    duplicate.limit(section.offset() + section.length());
    return duplicate.slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  private List<String> readStrings(
      ByteBuffer stringBytes, ByteBuffer stringOffsets, int stringCount) {
    int[] offsets = new int[stringCount + 1];
    for (int i = 0; i < offsets.length; i++) {
      offsets[i] = stringOffsets.getInt();
    }
    if (offsets[0] != 0) {
      throw new IllegalArgumentException("First string offset must be zero");
    }
    if (offsets[stringCount] != stringBytes.limit()) {
      throw new IllegalArgumentException(
          "Final string offset does not match string section length");
    }

    List<String> strings = new ArrayList<>();
    for (int i = 0; i < stringCount; i++) {
      int start = offsets[i];
      int endWithTerminator = offsets[i + 1];
      if (start < 0 || endWithTerminator <= start || endWithTerminator > stringBytes.limit()) {
        throw new IllegalArgumentException("Invalid string offset range at index: " + i);
      }
      if (stringBytes.get(endWithTerminator - 1) != 0) {
        throw new IllegalArgumentException("String is not NUL-terminated at index: " + i);
      }
      ByteBuffer encoded = stringBytes.duplicate();
      encoded.position(start);
      encoded.limit(endWithTerminator - 1);
      strings.add(decodeUtf8(encoded.slice(), i));
    }
    return List.copyOf(strings);
  }

  private String decodeUtf8(ByteBuffer encoded, int stringIndex) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(encoded)
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Invalid UTF-8 string at index: " + stringIndex, e);
    }
  }

  private String decodeUtf8(ByteBuffer encoded, String field) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(encoded)
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Invalid UTF-8 " + field, e);
    }
  }

  private DecodedMetadata readMetadata(ByteBuffer metadataBytes, Provenance sidecar) {
    if (!metadataBytes.hasRemaining()) {
      return new DecodedMetadata(sidecar, ExportPolicy.empty());
    }

    String json = decodeUtf8(metadataBytes, "metadata");
    JsonNode root = parseMetadataJson(json);
    if (!root.isObject()) {
      throw new IllegalArgumentException("Compiled term pack metadata must be a JSON object");
    }
    JsonNode schema = root.get("schema");
    if (schema == null || !schema.isTextual() || !METADATA_SCHEMA.equals(schema.asText())) {
      throw new IllegalArgumentException(
          "Expected compiled term pack metadata schema: " + METADATA_SCHEMA);
    }
    JsonNode provenanceNode = root.get("provenance");
    if (provenanceNode != null && !provenanceNode.isObject()) {
      throw new IllegalArgumentException(
          "Compiled term pack metadata provenance must be an object");
    }
    Provenance embeddedProvenance =
        provenanceNode == null ? Provenance.empty() : loadProvenance(provenanceNode);
    ExportPolicy exportPolicy = loadExportPolicy(root.get("exportPolicy"));
    if (Provenance.empty().equals(embeddedProvenance) && !exportPolicy.present()) {
      throw new IllegalArgumentException(
          "Compiled term pack metadata requires provenance or exportPolicy");
    }
    Provenance decodedProvenance =
        Provenance.empty().equals(embeddedProvenance) ? sidecar : embeddedProvenance;
    if (!Provenance.empty().equals(sidecar)
        && !Provenance.empty().equals(embeddedProvenance)
        && !sidecar.equals(embeddedProvenance)) {
      throw new IllegalArgumentException(
          "Compiled term pack provenance sidecar does not match embedded metadata");
    }
    return new DecodedMetadata(decodedProvenance, exportPolicy);
  }

  private JsonNode parseMetadataJson(String json) {
    try {
      return objectMapper.readTreeUnchecked(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid compiled term pack metadata JSON", e);
    }
  }

  private Provenance loadProvenance(JsonNode node) {
    List<String> sourceLabels = loadTextArray(node.get("sourceLabels"), "sourceLabels");
    List<Source> sources = new ArrayList<>();
    JsonNode sourcesNode = node.get("sources");
    if (sourcesNode != null) {
      if (!sourcesNode.isArray()) {
        throw new IllegalArgumentException("Expected array field: sources");
      }
      for (JsonNode sourceNode : sourcesNode) {
        if (!sourceNode.isObject()) {
          throw new IllegalArgumentException("Expected object value in sources array");
        }
        sources.add(
            new Source(
                requiredText(sourceNode, "path"),
                requiredLong(sourceNode, "byteSize"),
                requiredText(sourceNode, "sha256"),
                requiredBoolean(sourceNode, "gitLfsPointer")));
      }
    }

    return new Provenance(
        optionalText(node, "license"),
        optionalText(node, "generator"),
        sourceLabels,
        List.copyOf(sources));
  }

  private ExportPolicy loadExportPolicy(JsonNode node) {
    if (node == null || node.isNull()) {
      return ExportPolicy.empty();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(
          "Compiled term pack metadata exportPolicy must be an object");
    }
    return new ExportPolicy(
        requiredText(node, "runtimeExport"),
        requiredText(node, "compositionMode"),
        loadTextArray(requiredArray(node, "deferredComposition"), "deferredComposition"),
        requiredInt(node, "automaticExportTerms"),
        requiredInt(node, "reviewRequiredTerms"),
        requiredInt(node, "blockedTerms"),
        reasonCounts(requiredObject(node, "reviewRequiredReasons"), "reviewRequiredReasons"),
        reasonCounts(requiredObject(node, "blockedReasons"), "blockedReasons"));
  }

  private Map<String, Integer> reasonCounts(JsonNode node, String field) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> counts.put(entry.getKey(), requiredIntValue(entry.getValue(), field)));
    return counts;
  }

  private List<String> loadTextArray(JsonNode node, String field) {
    if (node == null) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("Expected array field: " + field);
    }
    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      if (!value.isTextual()) {
        throw new IllegalArgumentException("Expected text value in " + field + " array");
      }
      values.add(value.asText());
    }
    return List.copyOf(values);
  }

  private JsonNode requiredArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("Expected array field: " + field);
    }
    return value;
  }

  private JsonNode requiredObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Expected object field: " + field);
    }
    return value;
  }

  private String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("Expected text field: " + field);
    }
    return value.asText();
  }

  private String requiredText(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null) {
      throw new IllegalArgumentException("Missing text field: " + field);
    }
    return value;
  }

  private long requiredLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("Expected integer field: " + field);
    }
    return value.asLong();
  }

  private int requiredInt(JsonNode node, String field) {
    return requiredIntValue(node.get(field), field);
  }

  private int requiredIntValue(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("Expected integer field: " + field);
    }
    return value.asInt();
  }

  private boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("Expected boolean field: " + field);
    }
    return value.asBoolean();
  }

  private List<FormRow> readFormRows(ByteBuffer formRowBytes, int formRowCount) {
    List<FormRow> formRows = new ArrayList<>();
    for (int i = 0; i < formRowCount; i++) {
      int key = formRowBytes.getInt();
      int value = formRowBytes.getInt();
      int flags = formRowBytes.getInt();
      if ((flags & ~FORM_ROW_PATTERN_FLAG) != 0) {
        throw new IllegalArgumentException("Unsupported form row flags: " + flags);
      }
      formRows.add(new FormRow(key, value, (flags & FORM_ROW_PATTERN_FLAG) != 0));
    }
    return List.copyOf(formRows);
  }

  private List<FormSet> readFormSets(
      ByteBuffer formSetBytes, int formSetCount, List<FormRow> formRows) {
    List<FormSet> formSets = new ArrayList<>();
    for (int i = 0; i < formSetCount; i++) {
      int term = formSetBytes.getInt();
      int firstFormRow = requireNonNegative(formSetBytes.getInt(), "firstFormRow");
      int formRowCount = requireNonNegative(formSetBytes.getInt(), "formRowCount");
      int endFormRow = checkedAdd(firstFormRow, formRowCount, "form set row range");
      if (endFormRow > formRows.size()) {
        throw new IllegalArgumentException("Form set row range is out of bounds: " + i);
      }
      formSets.add(new FormSet(term, List.copyOf(formRows.subList(firstFormRow, endFormRow))));
    }
    return List.copyOf(formSets);
  }

  private int checkedAdd(int left, int right, String field) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(field + " is out of bounds", e);
    }
  }

  private List<TermRow> readTerms(ByteBuffer termBytes, int termCount) {
    List<TermRow> terms = new ArrayList<>();
    for (int i = 0; i < termCount; i++) {
      int id = termBytes.getInt();
      int text = termBytes.getInt();
      int featureBits = termBytes.getInt();
      int sense = termBytes.getInt();
      int formSet = termBytes.getInt();
      if (sense < NULL_INDEX) {
        throw new IllegalArgumentException("Invalid sense string index: " + sense);
      }
      terms.add(new TermRow(id, text, featureBits, sense == NULL_INDEX ? null : sense, formSet));
    }
    return List.copyOf(terms);
  }

  private String stringAt(List<String> strings, int index, String field) {
    if (index < 0 || index >= strings.size()) {
      throw new IllegalArgumentException("String index out of bounds for " + field + ": " + index);
    }
    return strings.get(index);
  }

  private record StringTable(List<String> strings, byte[] bytes, int[] offsets) {}

  private record FormSetDirectoryRow(int term, int firstFormRow, int formRowCount) {}

  private record SectionBounds(String name, int offset, int length) {}

  private record DecodedMetadata(Provenance provenance, ExportPolicy exportPolicy) {}
}
