package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectRequestSlackThread;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectRequestSlackThreadRepository
    extends JpaRepository<ReviewProjectRequestSlackThread, Long> {

  Optional<ReviewProjectRequestSlackThread> findByReviewProjectRequest_Id(Long requestId);

  void deleteByReviewProjectRequestIdIn(Iterable<Long> requestIds);
}
