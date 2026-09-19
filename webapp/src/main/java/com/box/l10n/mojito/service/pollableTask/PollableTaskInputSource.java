package com.box.l10n.mojito.service.pollableTask;

import java.util.Optional;

/** A durable task input reference. A broken recognized reference must throw, never fall back. */
public interface PollableTaskInputSource {
  Optional<String> findInputJson(long taskId);
}
