package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class PollableTaskInputSourceTest {
  @Test
  public void allReadFormsUseRecognizedDurableInputWithoutLegacyFallback() {
    PollableTaskBlobStorage storage = storage();
    storage.inputSources = List.of(id -> Optional.of("\"durable\""));
    assertThat(storage.getInputJson(7L)).isEqualTo("\"durable\"");
    assertThat(storage.getInput(7L, String.class)).isEqualTo("durable");
    assertThat(storage.getInputBytes(7L)).isEqualTo("\"durable\"".getBytes(StandardCharsets.UTF_8));
    verifyNoInteractions(storage.structuredBlobStorage);
  }

  @Test
  public void corruptedRecognizedInputNeverFallsBackToLegacyBody() {
    PollableTaskBlobStorage storage = storage();
    storage.inputSources =
        List.of(
            id -> {
              throw new IllegalStateException("corrupt");
            });
    assertThatThrownBy(() -> storage.getInputJson(7L)).hasMessage("corrupt");
    assertThatThrownBy(() -> storage.getInputBytes(7L)).hasMessage("corrupt");
    assertThatThrownBy(() -> storage.getInput(7L, String.class)).hasMessage("corrupt");
    verifyNoInteractions(storage.structuredBlobStorage);
  }

  @Test
  public void unrecognizedLegacyInputRetainsRawByteBehavior() {
    PollableTaskBlobStorage storage = storage();
    storage.inputSources = List.of(id -> Optional.empty());
    byte[] raw = new byte[] {(byte) 0x80};
    when(storage.structuredBlobStorage.getBytes(
            StructuredBlobStorage.Prefix.POLLABLE_TASK, "7/input"))
        .thenReturn(Optional.of(raw));
    assertThat(storage.getInputBytes(7L)).isSameAs(raw);
  }

  private PollableTaskBlobStorage storage() {
    PollableTaskBlobStorage storage = new PollableTaskBlobStorage();
    storage.structuredBlobStorage = mock(StructuredBlobStorage.class);
    storage.objectMapper = new ObjectMapper();
    return storage;
  }
}
