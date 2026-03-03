package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface AiTranslateAutomationConfigRepository
    extends JpaRepository<AiTranslateAutomationConfigEntity, Long> {
  AiTranslateAutomationConfigEntity findFirstByOrderByIdAsc();
}
