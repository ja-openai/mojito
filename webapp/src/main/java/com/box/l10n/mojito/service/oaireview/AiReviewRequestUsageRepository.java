package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.entity.AiReviewRequestUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface AiReviewRequestUsageRepository extends JpaRepository<AiReviewRequestUsage, Long> {}
