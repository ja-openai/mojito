package com.box.l10n.mojito.service.leveraging;

import com.box.l10n.mojito.entity.TMTextUnitVariantLeveraging;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TMTextUnitVariantLeveragingRepository
    extends JpaRepository<TMTextUnitVariantLeveraging, Long> {}
