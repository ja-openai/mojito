package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTaskArchiveRetry;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface PollableTaskArchiveRetryRepository
    extends JpaRepository<PollableTaskArchiveRetry, Long> {

  List<PollableTaskArchiveRetry> findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscTaskIdAsc(
      ZonedDateTime nextAttemptAt, Pageable pageable);
}
