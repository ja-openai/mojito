package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Artifact;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Immutable review artifacts share one envelope and checksum check across every consumer. */
@Service
public class AgentReviewArtifactStore {
  private final StructuredBlobStorage blobs;
  private final ObjectMapper mapper;

  public AgentReviewArtifactStore(StructuredBlobStorage blobs, ObjectMapper mapper) {
    this.blobs = blobs;
    this.mapper = mapper;
  }

  private record Envelope(String contentType, String contentBase64) {}

  private record Key(long runId, String sha256, Class<?> type) {}

  private record Stored(Envelope envelope, byte[] bytes) {
    Artifact view(String hash) {
      return new Artifact(hash, envelope.contentType(), envelope.contentBase64(), bytes.length);
    }
  }

  public Artifact put(long runId, String contentType, byte[] bytes) {
    Envelope envelope = new Envelope(contentType, Base64.getEncoder().encodeToString(bytes));
    try {
      String content = mapper.writeValueAsString(envelope);
      String hash = hash(content);
      blobs.put(
          StructuredBlobStorage.Prefix.AGENT_REVIEW,
          name(runId, hash),
          content,
          Retention.PERMANENT);
      return new Stored(envelope, bytes).view(hash);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize review data", e);
    }
  }

  public String putJson(long runId, Object value) {
    try {
      return put(runId, "application/json", mapper.writeValueAsBytes(value)).sha256();
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize review data", e);
    }
  }

  public Artifact read(long runId, String hash) {
    return load(runId, hash).view(hash);
  }

  public <T> T readJson(long runId, String hash, Class<T> type) {
    return parse(load(runId, hash), type);
  }

  /** A bulk request retains at most two parsed artifacts, normally its manifest and checkpoint. */
  public Reader reader() {
    return new Reader();
  }

  public final class Reader {
    private final Map<Key, Object> cache =
        new LinkedHashMap<>(2, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
            return size() > 2;
          }
        };

    public <T> T readJson(long runId, String hash, Class<T> type) {
      // Callers supply the hash from the freshly locked run on every item. A checkpoint change
      // therefore misses this request-local cache without weakening lease or revision checks.
      return type.cast(
          cache.computeIfAbsent(
              new Key(runId, hash, type),
              key -> AgentReviewArtifactStore.this.readJson(runId, hash, type)));
    }
  }

  private Stored load(long runId, String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact SHA-256 is required");
    String content =
        blobs
            .getString(StructuredBlobStorage.Prefix.AGENT_REVIEW, name(runId, hash))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run artifact not found"));
    if (!hash(content).equals(hash))
      throw new IllegalStateException("Stored review artifact checksum mismatch");
    try {
      Envelope envelope = mapper.readValue(content, Envelope.class);
      return new Stored(envelope, Base64.getDecoder().decode(envelope.contentBase64()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored review data", e);
    }
  }

  private <T> T parse(Stored stored, Class<T> type) {
    try {
      return mapper.readValue(stored.bytes(), type);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid stored review data", e);
    }
  }

  private String name(long runId, String hash) {
    return "runs/" + runId + "/artifacts/" + hash;
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
