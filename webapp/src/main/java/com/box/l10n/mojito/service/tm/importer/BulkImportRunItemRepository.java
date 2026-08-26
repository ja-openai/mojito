package com.box.l10n.mojito.service.tm.importer;

import com.box.l10n.mojito.entity.BulkImportRunItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface BulkImportRunItemRepository extends JpaRepository<BulkImportRunItem, Long> {

  List<BulkImportRunItem> findByRun_IdOrderByIdAsc(Long runId);

  List<BulkImportRunItem> findByTmTextUnit_IdAndLocale_IdOrderByRun_CreatedDateDescIdDesc(
      Long tmTextUnitId, Long localeId);
}
