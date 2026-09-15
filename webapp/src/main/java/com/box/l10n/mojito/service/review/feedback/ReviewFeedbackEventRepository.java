package com.box.l10n.mojito.service.review.feedback;

import com.box.l10n.mojito.entity.review.ReviewFeedbackEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported = false)
public interface ReviewFeedbackEventRepository extends JpaRepository<ReviewFeedbackEvent, Long> {
  boolean existsByEventKey(String eventKey);

  Optional<ReviewFeedbackEvent> findByEventKey(String eventKey);

  List<ReviewFeedbackEvent> findByAiBaselineTrueOrderByIdDesc(Pageable page);
}
