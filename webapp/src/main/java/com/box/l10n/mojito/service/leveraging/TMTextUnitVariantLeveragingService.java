package com.box.l10n.mojito.service.leveraging;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantLeveraging;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TMTextUnitVariantLeveragingService {

  @Autowired EntityManager entityManager;

  @Autowired TMTextUnitVariantLeveragingRepository tmTextUnitVariantLeveragingRepository;

  public void saveLeveraging(
      TMTextUnitVariant tmTextUnitVariant,
      Long sourceTmTextUnitId,
      Long sourceTmTextUnitVariantId,
      String leveragingType,
      boolean uniqueMatch) {
    TMTextUnitVariantLeveraging leveraging = new TMTextUnitVariantLeveraging();
    leveraging.setTmTextUnitVariant(tmTextUnitVariant);
    leveraging.setSourceTmTextUnit(
        entityManager.getReference(TMTextUnit.class, sourceTmTextUnitId));
    leveraging.setSourceTmTextUnitVariant(
        entityManager.getReference(TMTextUnitVariant.class, sourceTmTextUnitVariantId));
    leveraging.setLeveragingType(leveragingType);
    leveraging.setUniqueMatch(uniqueMatch);
    tmTextUnitVariantLeveragingRepository.save(leveraging);
  }
}
