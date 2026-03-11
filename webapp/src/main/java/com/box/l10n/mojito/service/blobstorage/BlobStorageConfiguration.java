package com.box.l10n.mojito.service.blobstorage;

import com.amazonaws.services.s3.AmazonS3;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.box.l10n.mojito.retry.DataIntegrityViolationExceptionRetryTemplate;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorageCleanupJob;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorageConfigurationProperties;
import com.google.common.base.Preconditions;
import java.time.Duration;
import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

/**
 * Configuration for {@link BlobStorage}
 *
 * <p>{@link DatabaseBlobStorage} is the default implementation but it should be use only for
 * testing or deployments with limited load.
 *
 * <p>Consider using {@link S3BlobStorage} or {@link AzureBlobStorage} for larger deployments. S3
 * storage requires an {@link AmazonS3} client and `l10n.blob-storage.type=s3`. Azure storage
 * requires `l10n.blob-storage.type=azure` and `l10n.blob-storage.azure.*` properties.
 */
@Configuration
public class BlobStorageConfiguration {

  static Logger logger = LoggerFactory.getLogger(BlobStorageConfiguration.class);

  @ConditionalOnProperty(value = "l10n.blob-storage.type", havingValue = "s3")
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

  @ConditionalOnProperty(value = "l10n.blob-storage.type", havingValue = "azure")
  @Configuration
  static class AzureBlobStorageConfiguration {

    @Autowired AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties;

    @Bean
    @ConditionalOnMissingBean(BlobContainerClient.class)
    public BlobContainerClient azureBlobContainerClient() {
      logger.info("Configure Azure BlobContainerClient");
      Preconditions.checkNotNull(
          azureBlobStorageConfigurationProperties.getConnectionString(),
          "l10n.blob-storage.azure.connection-string must be configured");
      return new BlobContainerClientBuilder()
          .connectionString(azureBlobStorageConfigurationProperties.getConnectionString())
          .containerName(azureBlobStorageConfigurationProperties.getContainer())
          .buildClient();
    }

    @Bean
    public AzureBlobStorage azureBlobStorage(BlobContainerClient azureBlobContainerClient) {
      logger.info("Configure AzureBlobStorage");
      return new AzureBlobStorage(
          azureBlobContainerClient, azureBlobStorageConfigurationProperties);
    }
  }

  @ConditionalOnProperty(
      value = "l10n.blob-storage.type",
      havingValue = "database",
      matchIfMissing = true)
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
}
