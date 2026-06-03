package com.box.l10n.mojito.service.blobstorage;

import com.amazonaws.services.s3.AmazonS3;
import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.retry.DataIntegrityViolationExceptionRetryTemplate;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorageCleanupJob;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorageConfigurationProperties;
import java.time.Duration;
import java.util.Map;
import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

/**
 * Configuration for {@link BlobStorage}
 *
 * <p>{@link DatabaseBlobStorage} is the default implementation but it should be use only for
 * testing or deployments with limited load.
 *
 * <p>Consider using {@link S3BlobStorage} for larger deployment. An {@link AmazonS3} client must be
 * configured first, and then the storage enabled with the `l10n.blob-storage.type=s3` property
 *
 * <p>Azure Blob Storage can also be used for larger deployment. A {@link BlobContainerClient} must
 * be configured first, and then the storage enabled with the `l10n.blob-storage.type=azure`
 * property
 */
@Configuration
public class BlobStorageConfiguration {

  static Logger logger = LoggerFactory.getLogger(BlobStorageConfiguration.class);

  @Conditional(S3BlobStorageEnabledCondition.class)
  @Configuration
  static class S3BlobStorageConfigurationConfiguration {

    @Autowired AmazonS3 amazonS3;

    @Autowired S3BlobStorageConfigurationProperties s3BlobStorageConfigurationProperties;

    @Bean
    public S3BlobStorage s3BlobStorage() {
      logger.info("Configure S3BlobStorage");
      return new S3BlobStorage(amazonS3, s3BlobStorageConfigurationProperties);
    }
  }

  @Conditional(AzureBlobStorageEnabledCondition.class)
  @Configuration
  static class AzureBlobStorageConfigurationConfiguration {

    @Autowired BlobContainerClient blobContainerClient;

    @Autowired AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties;

    @Bean
    public AzureBlobStorage azureBlobStorage() {
      logger.info("Configure AzureBlobStorage");
      return new AzureBlobStorage(blobContainerClient, azureBlobStorageConfigurationProperties);
    }
  }

  @Bean
  @Primary
  public BlobStorage blobStorage(
      BlobStorageRouter blobStorageRouter,
      BlobStorageConfigurationProperties blobStorageConfigurationProperties) {
    return blobStorageRouter.getBlobStorage(blobStorageConfigurationProperties.getType());
  }

  @Configuration
  @Conditional(DatabaseBlobStorageEnabledCondition.class)
  static class DatabaseBlobStorageConfiguration {

    @Autowired MBlobRepository mBlobRepository;

    @Autowired
    DatabaseBlobStorageConfigurationProperties databaseBlobStorageConfigurationProperties;

    @Autowired
    DataIntegrityViolationExceptionRetryTemplate dataIntegrityViolationExceptionRetryTemplate;

    @Bean
    public DatabaseBlobStorage databaseBlobStorage() {
      logger.info("Configure DatabaseBlobStorage");
      return new DatabaseBlobStorage(
          databaseBlobStorageConfigurationProperties,
          mBlobRepository,
          dataIntegrityViolationExceptionRetryTemplate);
    }

    @Bean(name = "jobDetailDatabaseBlobStorageCleanupJob")
    public JobDetailFactoryBean jobDetailExpiringBlobCleanup() {
      JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
      jobDetailFactory.setJobClass(DatabaseBlobStorageCleanupJob.class);
      jobDetailFactory.setDescription("Cleanup expired blobs");
      jobDetailFactory.setDurability(true);
      return jobDetailFactory;
    }

    @Profile("!disablescheduling")
    @Bean
    public SimpleTriggerFactoryBean triggerExpiringBlobCleanup(
        @Qualifier("jobDetailDatabaseBlobStorageCleanupJob") JobDetail job) {
      logger.info("Configure jobDetailDatabaseBlobStorageCleanupJob");
      SimpleTriggerFactoryBean trigger = new SimpleTriggerFactoryBean();
      trigger.setJobDetail(job);
      trigger.setRepeatInterval(Duration.ofMinutes(5).toMillis());
      trigger.setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY);
      return trigger;
    }
  }

  static class DatabaseBlobStorageEnabledCondition extends BlobStorageEnabledCondition {

    DatabaseBlobStorageEnabledCondition() {
      super(BlobStorageType.DATABASE);
    }
  }

  static class S3BlobStorageEnabledCondition extends BlobStorageEnabledCondition {

    S3BlobStorageEnabledCondition() {
      super(BlobStorageType.S3);
    }
  }

  static class AzureBlobStorageEnabledCondition extends BlobStorageEnabledCondition {

    AzureBlobStorageEnabledCondition() {
      super(BlobStorageType.AZURE);
    }
  }

  abstract static class BlobStorageEnabledCondition implements Condition {

    BlobStorageType blobStorageType;

    BlobStorageEnabledCondition(BlobStorageType blobStorageType) {
      this.blobStorageType = blobStorageType;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Binder binder = Binder.get(context.getEnvironment());
      BlobStorageType defaultType =
          binder
              .bind("l10n.blob-storage.type", BlobStorageType.class)
              .orElse(BlobStorageType.DATABASE);

      if (blobStorageType == defaultType) {
        return true;
      }

      return binder
          .bind(
              "l10n.blob-storage.routing.prefixes",
              Bindable.mapOf(String.class, BlobStorageType.class))
          .orElse(Map.of())
          .containsValue(blobStorageType);
    }
  }
}
