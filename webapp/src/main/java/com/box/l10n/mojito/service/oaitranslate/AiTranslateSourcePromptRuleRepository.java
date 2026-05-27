package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateSourcePromptRuleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTranslateSourcePromptRuleRepository
    extends JpaRepository<AiTranslateSourcePromptRuleEntity, Long> {

  List<AiTranslateSourcePromptRuleEntity> findAllByOrderByPriorityAscNameAsc();

  List<AiTranslateSourcePromptRuleEntity> findByEnabledTrueOrderByPriorityAscIdAsc();

  AiTranslateSourcePromptRuleEntity findByNameIgnoreCase(String name);
}
