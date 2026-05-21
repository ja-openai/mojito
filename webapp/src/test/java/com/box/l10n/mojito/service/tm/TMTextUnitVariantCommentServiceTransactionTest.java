package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TMTextUnitVariantCommentServiceTransactionTest {

  @Test
  public void addCommentCommitsTransaction() {
    TMTextUnitVariantCommentService service = commentService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitVariantRepository.getReferenceById(1L)).thenReturn(variant);
    when(service.tmTextUnitVariantCommentRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.addComment(
        1L,
        TMTextUnitVariantComment.Type.LEVERAGING,
        TMTextUnitVariantComment.Severity.INFO,
        "comment");

    verify(service.tmTextUnitVariantRepository).getReferenceById(1L);
    verify(service.tmTextUnitVariantCommentRepository).save(any());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void addCommentRollsBackTransaction() {
    TMTextUnitVariantCommentService service = commentService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitVariantRepository.getReferenceById(1L))
        .thenReturn(new TMTextUnitVariant());
    when(service.tmTextUnitVariantCommentRepository.save(any()))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.addComment(
          1L,
          TMTextUnitVariantComment.Type.LEVERAGING,
          TMTextUnitVariantComment.Severity.INFO,
          "comment");
    } catch (IllegalStateException e) {
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected addComment to rethrow the save failure");
  }

  @Test
  public void addCommentWithVariantUsesSingleTransaction() {
    TMTextUnitVariantCommentService service = commentService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setId(1L);
    variant.setTmTextUnitVariantComments(new HashSet<>());
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitVariantRepository.getReferenceById(1L)).thenReturn(variant);
    when(service.tmTextUnitVariantCommentRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TMTextUnitVariantComment comment =
        service.addComment(
            variant,
            TMTextUnitVariantComment.Type.LEVERAGING,
            TMTextUnitVariantComment.Severity.INFO,
            "comment");

    assertTrue(variant.getTmTextUnitVariantComments().contains(comment));
    verify(service.transactionManager, times(1)).getTransaction(any());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void copyCommentsSelfCallsOpenTransactions() {
    TMTextUnitVariantCommentService service = commentService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    TMTextUnitVariant sourceVariant = new TMTextUnitVariant();
    sourceVariant.setId(1L);
    TMTextUnitVariant targetVariant = new TMTextUnitVariant();
    targetVariant.setId(2L);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitVariantRepository.getReferenceById(2L)).thenReturn(targetVariant);
    when(service.tmTextUnitVariantCommentRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(service.tmTextUnitVariantCommentRepository.findAllByTmTextUnitVariant_id(1L))
        .thenReturn(
            List.of(
                comment(sourceVariant, TMTextUnitVariantComment.Severity.INFO),
                comment(sourceVariant, TMTextUnitVariantComment.Severity.WARNING)));

    service.copyComments(1L, 2L);

    verify(service.transactionManager, times(2)).getTransaction(any());
    verify(service.tmTextUnitVariantCommentRepository, times(2)).save(any());
    verify(service.tmTextUnitVariantRepository, times(2)).getReferenceById(eq(2L));
    verify(service.transactionManager, times(2)).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  private TMTextUnitVariantCommentService commentService() {
    TMTextUnitVariantCommentService service = new TMTextUnitVariantCommentService();
    service.tmTextUnitVariantCommentRepository = mock(TMTextUnitVariantCommentRepository.class);
    service.tmTextUnitVariantRepository = mock(TMTextUnitVariantRepository.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }

  private TMTextUnitVariantComment comment(
      TMTextUnitVariant variant, TMTextUnitVariantComment.Severity severity) {
    TMTextUnitVariantComment comment = new TMTextUnitVariantComment();
    comment.setTmTextUnitVariant(variant);
    comment.setType(TMTextUnitVariantComment.Type.LEVERAGING);
    comment.setSeverity(severity);
    comment.setContent("comment");
    return comment;
  }
}
