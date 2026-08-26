package com.box.l10n.mojito.service.tm.importer;

import com.box.l10n.mojito.entity.BulkImportRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface BulkImportRunRepository extends JpaRepository<BulkImportRun, Long> {

  Optional<BulkImportRun> findByRunId(String runId);

  List<BulkImportRun> findByPollableTask_IdOrderByCreatedDateDesc(Long pollableTaskId);
}
