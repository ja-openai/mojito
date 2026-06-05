package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.entity.JsonConfigLocalizationRunEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JsonConfigLocalizationRunRepository
    extends JpaRepository<JsonConfigLocalizationRunEntity, Long> {

  @Query(
      """
      select run
      from JsonConfigLocalizationRunEntity run
      join fetch run.jsonConfigLocalization setup
      where setup.id = :setupId
      order by run.createdDate desc, run.id desc
      """)
  List<JsonConfigLocalizationRunEntity> findRecentBySetupId(
      @Param("setupId") Long setupId, Pageable pageable);
}
