package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexRefreshRunRepository extends JpaRepository<TermIndexRefreshRun, Long> {

  List<TermIndexRefreshRun> findAllByOrderByIdDesc(Pageable pageable);
}
