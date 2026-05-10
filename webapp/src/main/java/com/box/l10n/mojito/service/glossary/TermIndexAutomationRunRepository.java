package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexAutomationRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexAutomationRunRepository
    extends JpaRepository<TermIndexAutomationRun, Long> {

  List<TermIndexAutomationRun> findAllByOrderByIdDesc(Pageable pageable);

  Optional<TermIndexAutomationRun> findByPollableTaskId(Long pollableTaskId);
}
