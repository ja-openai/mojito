package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateLocalePromptSuffixEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface AiTranslateLocalePromptSuffixRepository
    extends JpaRepository<AiTranslateLocalePromptSuffixEntity, Long> {
  List<AiTranslateLocalePromptSuffixEntity> findAllByOrderByLocaleBcp47TagAsc();

  AiTranslateLocalePromptSuffixEntity findByLocaleBcp47TagIgnoreCase(String localeTag);
}
