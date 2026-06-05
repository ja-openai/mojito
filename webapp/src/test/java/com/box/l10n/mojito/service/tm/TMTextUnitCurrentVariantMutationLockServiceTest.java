package com.box.l10n.mojito.service.tm;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TMTextUnitCurrentVariantMutationLockServiceTest {

  @Mock TMTextUnitRepository tmTextUnitRepository;

  @InjectMocks TMTextUnitCurrentVariantMutationLockService service;

  @Test
  public void lockTextUnitsUsesStableDistinctIds() {
    service.lockTextUnits(List.of(3L, 1L, 3L, 2L));

    verify(tmTextUnitRepository).lockIdsByIdInForUpdate(List.of(1L, 2L, 3L));
  }

  @Test
  public void lockTextUnitsSkipsEmptyScope() {
    service.lockTextUnits(List.of());

    verifyNoInteractions(tmTextUnitRepository);
  }
}
