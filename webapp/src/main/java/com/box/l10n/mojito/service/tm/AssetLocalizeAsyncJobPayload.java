package com.box.l10n.mojito.service.tm;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetLocalizeAsyncJobPayload(Long pollableTaskId, String outputId) {

  public AssetLocalizeAsyncJobPayload(Long pollableTaskId) {
    this(pollableTaskId, null);
  }

  public AssetLocalizeAsyncJobPayload {
    Objects.requireNonNull(pollableTaskId);
    if (pollableTaskId <= 0) {
      throw new IllegalArgumentException("pollableTaskId must be positive");
    }
    if (outputId != null && !UUID.fromString(outputId).toString().equals(outputId)) {
      throw new IllegalArgumentException("outputId must be a canonical UUID");
    }
  }
}
