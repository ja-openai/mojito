package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiReviewProto;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRunner;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.AiReviewProtoRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.util.retry.Retry;

public class AiReviewServiceTransactionTest {

  @Test
  public void saveAiReviewProtosCommitsTransaction() {
    AiReviewService service = aiReviewService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    List<AiReviewProto> protos = List.of(new AiReviewProto());

    service.saveAiReviewProtosInTx(protos);

    verify(service.aiReviewProtoRepository).saveAll(protos);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void saveAiReviewProtosRollsBackTransaction() {
    AiReviewService service = aiReviewService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.aiReviewProtoRepository.saveAll(any()))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.saveAiReviewProtosInTx(List.of(new AiReviewProto()));
      fail("Expected saveAiReviewProtosInTx to rethrow the save failure");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
    }

    verify(service.transactionManager).rollback(transaction);
    verify(service.transactionManager, never()).commit(transaction);
  }

  private AiReviewService aiReviewService() {
    return new AiReviewService(
        mock(TextUnitSearcher.class),
        mock(RepositoryRepository.class),
        mock(TMTextUnitVariantRepository.class),
        mock(AiReviewProtoRepository.class),
        mock(RepositoryService.class),
        new AiReviewConfigurationProperties(),
        null,
        null,
        ObjectMapper.withNoFailOnUnknownProperties(),
        Retry.backoff(1, Duration.ofMillis(1)),
        mock(QuartzPollableTaskScheduler.class),
        null,
        mock(PollableTaskRunner.class),
        mock(PollableTaskService.class),
        mock(MeterRegistry.class),
        mock(PlatformTransactionManager.class));
  }
}
