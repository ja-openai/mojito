package com.box.l10n.mojito.service.tm;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Serializes current-translation mutations with readers that need an immutable text-unit view. */
@Service
public class TMTextUnitCurrentVariantMutationLockService {

  @Autowired TMTextUnitRepository tmTextUnitRepository;

  public void lockTextUnit(Long tmTextUnitId) {
    lockTextUnits(Collections.singletonList(tmTextUnitId));
  }

  public void lockTextUnits(Collection<Long> tmTextUnitIds) {
    if (tmTextUnitIds == null || tmTextUnitIds.isEmpty()) {
      return;
    }

    List<Long> lockIds =
        tmTextUnitIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
    if (!lockIds.isEmpty()) {
      tmTextUnitRepository.lockIdsByIdInForUpdate(lockIds);
    }
  }
}
