package com.box.l10n.mojito.service.repository;

import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface RepositoryGenerationSettingsRepository
    extends JpaRepository<RepositoryGenerationSettings, Long> {}
