package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;

@FunctionalInterface
public interface PollableTaskOperation<T> {
  T call(PollableTask currentTask) throws Throwable;
}
