package com.box.l10n.mojito.service.tm;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInputSource;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Immutable, shared input for one accepted legacy parallel request. */
@Component
public class AssetLocalizeFanoutInput implements PollableTaskInputSource {
  public record Slot(long localeId, String outputTag, String outputOverride) {}

  public record Manifest(int version, MultiLocalizedAssetBody input, List<Slot> slots) {}

  public record Reference(String name, String sha256, int count) {}

  private final JdbcTemplate jdbc;
  private final StructuredBlobStorage blobs;
  private final ObjectMapper mapper;

  public AssetLocalizeFanoutInput(
      JdbcTemplate jdbc, StructuredBlobStorage blobs, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.blobs = blobs;
    this.mapper = mapper;
  }

  public Reference stage(Manifest manifest) {
    byte[] bytes = mapper.writeValueAsStringUnchecked(manifest).getBytes(StandardCharsets.UTF_8);
    Reference reference =
        new Reference(
            "fanout-" + UUID.randomUUID() + "/input", sha256(bytes), manifest.slots().size());
    // Accepted work may outlive the legacy one-day TTL. No automatic cleanup owns these inputs.
    blobs.putBytes(POLLABLE_TASK, reference.name(), bytes, Retention.PERMANENT);
    read(reference);
    return reference;
  }

  public Manifest read(Reference reference) {
    byte[] bytes = blobs.getBytes(POLLABLE_TASK, reference.name()).orElseThrow(this::invalid);
    if (!reference.sha256().equals(sha256(bytes))) {
      throw invalid();
    }
    Manifest manifest;
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      manifest =
          mapper
              .readerFor(Manifest.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readValue(json);
    } catch (Exception invalidEncodingOrJson) {
      // Parser exceptions may contain source text. Persist/log only a fixed diagnostic.
      throw invalid();
    }
    if (manifest.version() != 1
        || manifest.input() == null
        || manifest.input().getAssetId() == null
        || manifest.input().getAssetId() <= 0
        || manifest.input().getPullRunName() != null
        || manifest.slots() == null
        || manifest.slots().size() > AssetLocalizeFanoutService.MAX_LOCALES
        || manifest.slots().size() != reference.count()) {
      throw invalid();
    }
    HashSet<String> tags = new HashSet<>();
    for (Slot slot : manifest.slots()) {
      if (slot == null
          || slot.localeId() <= 0
          || !isValidOutputTag(slot.outputTag())
          || !tags.add(slot.outputTag())) throw invalid();
    }
    return manifest;
  }

  static boolean isValidOutputTag(String tag) {
    // These values become JDBC text; PostgreSQL rejects NUL and UTF-8 rejects lone surrogates.
    return tag != null
        && !tag.isBlank()
        && tag.length() <= 255
        && tag.indexOf('\0') < 0
        && StandardCharsets.UTF_8.newEncoder().canEncode(tag);
  }

  @Override
  public Optional<String> findInputJson(long taskId) {
    // Two indexed identities in one roundtrip; never scan queue JSON or the asset catalog.
    List<InputReference> references =
        jdbc.query(
            """
        SELECT f.input_blob_name, f.input_sha256, f.slot_count, c.slot_ordinal
        FROM asset_localize_fanout_child c
        JOIN asset_localize_fanout f ON f.parent_task_id = c.parent_task_id
        WHERE c.child_task_id = ?
        UNION ALL
        SELECT input_blob_name, input_sha256, slot_count, -1
        FROM asset_localize_fanout WHERE parent_task_id = ?
        """,
            (rs, row) ->
                new InputReference(
                    new Reference(rs.getString(1), rs.getString(2), rs.getInt(3)), rs.getInt(4)),
            taskId,
            taskId);
    if (references.isEmpty()) return Optional.empty();
    if (references.size() != 1) throw invalid();
    InputReference reference = references.getFirst();
    Manifest manifest = read(reference.reference());
    if (reference.slot() == -1)
      return Optional.of(mapper.writeValueAsStringUnchecked(manifest.input()));
    if (reference.slot() < 0 || reference.slot() >= manifest.slots().size()) throw invalid();
    return Optional.of(mapper.writeValueAsStringUnchecked(child(manifest, reference.slot())));
  }

  public static LocalizedAssetBody child(Manifest manifest, int ordinal) {
    MultiLocalizedAssetBody input = manifest.input();
    Slot slot = manifest.slots().get(ordinal);
    LocalizedAssetBody child = new LocalizedAssetBody();
    child.setAssetId(input.getAssetId());
    child.setLocaleId(slot.localeId());
    child.setContent(input.getSourceContent());
    child.setOutputBcp47tag(slot.outputOverride());
    child.setFilterConfigIdOverride(input.getFilterConfigIdOverride());
    child.setFilterOptions(input.getFilterOptions());
    child.setInheritanceMode(input.getInheritanceMode());
    child.setStatus(input.getStatus());
    child.setPullWithNoSource(input.isPullWithNoSource());
    child.setPullWithNoSourceBranches(input.getPullWithNoSourceBranches());
    return child;
  }

  private record InputReference(Reference reference, int slot) {}

  private IllegalStateException invalid() {
    return new IllegalStateException("Durable asset fanout input is missing or invalid");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
