package com.box.l10n.mojito.service.tm;

import java.util.Objects;

public record AssetLocalizeAsyncJobPayload(Long pollableTaskId) {

  public AssetLocalizeAsyncJobPayload {
    Objects.requireNonNull(pollableTaskId);
    if (pollableTaskId <= 0) {
      throw new IllegalArgumentException("pollableTaskId must be positive");
    }
  }
}
