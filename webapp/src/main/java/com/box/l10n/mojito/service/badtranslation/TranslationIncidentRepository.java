package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TranslationIncidentRepository
    extends JpaRepository<TranslationIncident, Long>,
        JpaSpecificationExecutor<TranslationIncident> {

  Optional<TranslationIncident> findByReviewFindingId(String findingId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<TranslationIncident> findForUpdateByReviewFindingId(String findingId);

  Page<TranslationIncident> findAllByOrderByCreatedDateDesc(Pageable pageable);

  Page<TranslationIncident> findAllByStatusOrderByCreatedDateDesc(
      TranslationIncidentStatus status, Pageable pageable);
}
