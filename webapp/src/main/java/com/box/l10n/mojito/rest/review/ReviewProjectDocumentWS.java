package com.box.l10n.mojito.rest.review;

import com.box.l10n.mojito.service.review.ReviewProjectDocumentService;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-projects")
public class ReviewProjectDocumentWS {
  private final ReviewProjectDocumentService documents;

  public ReviewProjectDocumentWS(ReviewProjectDocumentService documents) {
    this.documents = documents;
  }

  @GetMapping("/{projectId}/documents")
  public ReviewProjectDocumentView getDocuments(@PathVariable Long projectId) {
    return documents.getDocuments(projectId);
  }
}
