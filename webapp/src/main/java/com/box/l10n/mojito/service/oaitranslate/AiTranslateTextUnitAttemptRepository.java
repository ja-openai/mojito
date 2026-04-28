package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTranslateTextUnitAttemptRepository
    extends JpaRepository<AiTranslateTextUnitAttempt, Long> {

  List<AiTranslateTextUnitAttempt> findByPollableTask_IdAndRequestGroupId(
      Long pollableTaskId, String requestGroupId);

  List<AiTranslateTextUnitAttempt> findByPollableTask_IdAndRequestGroupIdIn(
      Long pollableTaskId, Collection<String> requestGroupIds);
}
