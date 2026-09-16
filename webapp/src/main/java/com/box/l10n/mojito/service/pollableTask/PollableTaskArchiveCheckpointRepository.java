package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTaskArchiveCheckpoint;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface PollableTaskArchiveCheckpointRepository
    extends JpaRepository<PollableTaskArchiveCheckpoint, Integer> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select checkpoint from PollableTaskArchiveCheckpoint checkpoint where checkpoint.id = :id")
  Optional<PollableTaskArchiveCheckpoint> findForUpdate(@Param("id") Integer id);
}
