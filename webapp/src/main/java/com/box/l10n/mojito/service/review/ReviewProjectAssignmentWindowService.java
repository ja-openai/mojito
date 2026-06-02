package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindow;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindowEndReason;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewProjectAssignmentWindowService {

  private final ReviewProjectAssignmentWindowRepository assignmentWindowRepository;
  private final UserService userService;

  public ReviewProjectAssignmentWindowService(
      ReviewProjectAssignmentWindowRepository assignmentWindowRepository, UserService userService) {
    this.assignmentWindowRepository = Objects.requireNonNull(assignmentWindowRepository);
    this.userService = Objects.requireNonNull(userService);
  }

  @Transactional
  public void syncTranslatorAssignmentWindow(
      ReviewProject reviewProject, User previousTranslator, User nextTranslator) {
    if (Objects.equals(entityId(previousTranslator), entityId(nextTranslator))) {
      ensureOpenWindow(reviewProject, nextTranslator);
      return;
    }

    closeOpenWindow(
        reviewProject,
        nextTranslator == null
            ? ReviewProjectAssignmentWindowEndReason.UNASSIGNED
            : ReviewProjectAssignmentWindowEndReason.REASSIGNED);
    ensureOpenWindow(reviewProject, nextTranslator);
  }

  @Transactional
  public ReviewProjectAssignmentWindow ensureOpenWindow(
      ReviewProject reviewProject, User assignedTranslator) {
    if (assignedTranslator == null) {
      return null;
    }

    return assignmentWindowRepository
        .findOpenWindowByReviewProjectId(reviewProject.getId())
        .filter(
            window ->
                Objects.equals(
                    entityId(window.getAssignedTranslatorUser()), entityId(assignedTranslator)))
        .orElseGet(() -> createOpenWindow(reviewProject, assignedTranslator));
  }

  @Transactional
  public ReviewProjectAssignmentWindow acceptCurrentAssignment(ReviewProject reviewProject) {
    User currentUser =
        userService
            .getCurrentUser()
            .orElseThrow(() -> new AccessDeniedException("Current user is required"));
    User assignedTranslator = reviewProject.getAssignedTranslatorUser();
    if (assignedTranslator == null) {
      throw new IllegalArgumentException("Project has no assigned translator");
    }
    if (!Objects.equals(entityId(currentUser), entityId(assignedTranslator))) {
      throw new AccessDeniedException("Only the assigned translator can accept this project");
    }

    ReviewProjectAssignmentWindow window = ensureOpenWindow(reviewProject, assignedTranslator);
    if (window.getAcceptedAt() == null) {
      window.setAcceptedAt(ZonedDateTime.now());
      assignmentWindowRepository.save(window);
    }
    return window;
  }

  @Transactional
  public void acceptCurrentAssignmentIfAssignedTranslator(ReviewProject reviewProject) {
    User currentUser = userService.getCurrentUser().orElse(null);
    User assignedTranslator = reviewProject.getAssignedTranslatorUser();
    if (currentUser == null
        || assignedTranslator == null
        || !Objects.equals(entityId(currentUser), entityId(assignedTranslator))) {
      return;
    }

    ReviewProjectAssignmentWindow window = ensureOpenWindow(reviewProject, assignedTranslator);
    if (window.getAcceptedAt() == null) {
      window.setAcceptedAt(ZonedDateTime.now());
      assignmentWindowRepository.save(window);
    }
  }

  @Transactional
  public ReviewProjectAssignmentWindow saveSelfReportedTime(
      ReviewProject reviewProject, Integer minutes, String note) {
    if (minutes == null || minutes <= 0) {
      throw new IllegalArgumentException("selfReportedMinutes must be greater than 0");
    }
    User currentUser =
        userService
            .getCurrentUser()
            .orElseThrow(() -> new AccessDeniedException("Current user is required"));
    ReviewProjectAssignmentWindow window =
        assignmentWindowRepository
            .findOpenWindowByReviewProjectId(reviewProject.getId())
            .orElseGet(
                () -> ensureOpenWindow(reviewProject, reviewProject.getAssignedTranslatorUser()));
    if (window == null) {
      throw new IllegalArgumentException("Project has no assignment window for reporting time");
    }
    if (!userService.isCurrentUserAdminOrPm()
        && !Objects.equals(entityId(window.getAssignedTranslatorUser()), entityId(currentUser))) {
      throw new AccessDeniedException("Only the assigned translator can report time");
    }

    window.setSelfReportedMinutes(minutes);
    window.setSelfReportedNote(normalizeNote(note));
    window.setSelfReportedAt(ZonedDateTime.now());
    window.setSelfReportedByUser(currentUser);
    return assignmentWindowRepository.save(window);
  }

  @Transactional
  public void closeOpenWindow(
      ReviewProject reviewProject, ReviewProjectAssignmentWindowEndReason endReason) {
    assignmentWindowRepository
        .findOpenWindowByReviewProjectId(reviewProject.getId())
        .ifPresent(
            window -> {
              window.setEndedAt(ZonedDateTime.now());
              window.setEndReason(endReason);
              assignmentWindowRepository.save(window);
            });
  }

  private ReviewProjectAssignmentWindow createOpenWindow(
      ReviewProject reviewProject, User assignedTranslator) {
    ReviewProjectAssignmentWindow window = new ReviewProjectAssignmentWindow();
    window.setReviewProject(reviewProject);
    window.setAssignedTranslatorUser(assignedTranslator);
    window.setAssignedTranslatorUsernameSnapshot(assignedTranslator.getUsername());
    window.setAssignedAt(ZonedDateTime.now());
    return assignmentWindowRepository.save(window);
  }

  private String normalizeNote(String note) {
    if (note == null) {
      return null;
    }
    String trimmed = note.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > 512 ? trimmed.substring(0, 512) : trimmed;
  }

  private Long entityId(User user) {
    return user == null ? null : user.getId();
  }
}
