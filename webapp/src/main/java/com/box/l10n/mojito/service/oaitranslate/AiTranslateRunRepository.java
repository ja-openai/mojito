package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTranslateRunRepository extends JpaRepository<AiTranslateRun, Long> {

  Optional<AiTranslateRun> findByPollableTask_Id(Long pollableTaskId);

  List<AiTranslateRun> findTop50ByOrderByCreatedDateDesc();
}
