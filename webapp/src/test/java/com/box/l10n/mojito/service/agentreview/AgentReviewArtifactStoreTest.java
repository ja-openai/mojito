package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class AgentReviewArtifactStoreTest {
  private final StructuredBlobStorage blobs = mock(StructuredBlobStorage.class);
  private final Map<String, String> content = new HashMap<>();
  private final AgentReviewArtifactStore artifacts =
      new AgentReviewArtifactStore(blobs, new ObjectMapper());

  @Before
  public void storage() {
    doAnswer(
            invocation -> {
              content.put(invocation.getArgument(1), invocation.getArgument(2));
              return null;
            })
        .when(blobs)
        .put(
            eq(StructuredBlobStorage.Prefix.AGENT_REVIEW),
            anyString(),
            anyString(),
            eq(Retention.PERMANENT));
    when(blobs.getString(eq(StructuredBlobStorage.Prefix.AGENT_REVIEW), anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(content.get(invocation.getArgument(1))));
  }

  @Test
  public void changedValidJsonFailsChecksumForBothArtifactAndStructuredReads() {
    String hash = artifacts.putJson(1, Map.of("status", "IN_PROGRESS"));
    String changed = artifacts.putJson(1, Map.of("status", "COMPLETED"));
    assertThat(artifacts.readJson(1, hash, Map.class)).containsEntry("status", "IN_PROGRESS");
    content.put(name(1, hash), content.get(name(1, changed)));

    assertThatThrownBy(() -> artifacts.read(1, hash))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checksum mismatch");
    assertThatThrownBy(() -> artifacts.readJson(1, hash, Map.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checksum mismatch");
  }

  @Test
  public void requestReaderCachesByRunAndHashAndEvictsOldArtifacts() {
    String first = artifacts.putJson(1, Map.of("version", 1));
    String second = artifacts.putJson(1, Map.of("version", 2));
    assertThat(artifacts.putJson(2, Map.of("version", 1))).isEqualTo(first);
    var reader = artifacts.reader();
    clearInvocations(blobs);

    Map<?, ?> initial = reader.readJson(1, first, Map.class);
    assertThat(reader.readJson(1, first, Map.class)).isSameAs(initial);
    assertThat(reader.readJson(1, second, Map.class)).containsEntry("version", 2);
    assertThat(reader.readJson(2, first, Map.class)).isEqualTo(initial).isNotSameAs(initial);
    assertThat(reader.readJson(1, first, Map.class)).isEqualTo(initial).isNotSameAs(initial);

    verify(blobs, times(2)).getString(StructuredBlobStorage.Prefix.AGENT_REVIEW, name(1, first));
    verify(blobs).getString(StructuredBlobStorage.Prefix.AGENT_REVIEW, name(1, second));
    verify(blobs).getString(StructuredBlobStorage.Prefix.AGENT_REVIEW, name(2, first));
  }

  private String name(long runId, String hash) {
    return "runs/" + runId + "/artifacts/" + hash;
  }
}
