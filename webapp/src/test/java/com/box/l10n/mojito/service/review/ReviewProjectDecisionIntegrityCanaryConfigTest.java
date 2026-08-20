package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.TimeZone;
import org.junit.Test;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;

public class ReviewProjectDecisionIntegrityCanaryConfigTest {

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              ReviewProjectDecisionIntegrityCanaryConfig.class,
              ReviewProjectDecisionIntegrityCanaryService.class)
          .withBean(
              ReviewProjectDecisionIntegrityAuditService.class,
              () -> mock(ReviewProjectDecisionIntegrityAuditService.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  public void canaryIsDisabledByDefault() {
    applicationContextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(ReviewProjectDecisionIntegrityCanaryService.class);
          assertThat(context).doesNotHaveBean(JobDetail.class);
          assertThat(context).doesNotHaveBean(CronTrigger.class);
          assertThat(context.getBean(MeterRegistry.class).getMeters()).isEmpty();
        });
  }

  @Test
  public void canaryServiceAndScheduleAreCreatedWhenEnabled() {
    applicationContextRunner
        .withPropertyValues("l10n.review-project.decision-integrity-canary.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ReviewProjectDecisionIntegrityCanaryService.class);
              assertThat(context.getBean(JobDetail.class).getJobClass())
                  .isEqualTo(ReviewProjectDecisionIntegrityCanaryJob.class);
              assertThat(context.getBean(CronTrigger.class).getCronExpression())
                  .isEqualTo("0 15 5 * * ?");
            });
  }

  @Test
  public void requiresExplicitEnablement() {
    ConditionalOnProperty condition =
        ReviewProjectDecisionIntegrityCanaryConfig.class.getAnnotation(ConditionalOnProperty.class);

    assertThat(condition.value())
        .containsExactly("l10n.review-project.decision-integrity-canary.enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
  }

  @Test
  public void createsStaticUtcQuartzSchedule() throws Exception {
    ReviewProjectDecisionIntegrityCanaryConfig config =
        new ReviewProjectDecisionIntegrityCanaryConfig();
    JobDetailFactoryBean jobFactory = config.jobDetailReviewProjectDecisionIntegrityCanary();
    jobFactory.afterPropertiesSet();
    JobDetail jobDetail = jobFactory.getObject();

    CronTriggerFactoryBean triggerFactory =
        config.triggerReviewProjectDecisionIntegrityCanary(jobDetail, "0 15 5 * * ?");
    triggerFactory.afterPropertiesSet();

    assertThat(jobDetail.getJobClass()).isEqualTo(ReviewProjectDecisionIntegrityCanaryJob.class);
    assertThat(triggerFactory.getObject().getCronExpression()).isEqualTo("0 15 5 * * ?");
    assertThat(triggerFactory.getObject().getTimeZone()).isEqualTo(TimeZone.getTimeZone("UTC"));
  }
}
