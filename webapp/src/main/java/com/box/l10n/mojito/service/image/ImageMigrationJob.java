package com.box.l10n.mojito.service.image;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@DisallowConcurrentExecution
public class ImageMigrationJob implements Job {

  private static final Logger logger = LoggerFactory.getLogger(ImageMigrationJob.class);

  @Autowired ImageMigrationService imageMigrationService;

  @Value("${l10n.image-service.migration.batch-size:25}")
  int batchSize;

  @Value("${l10n.image-service.migration.delete-source:false}")
  boolean deleteSource;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    ImageMigrationService.Result result =
        imageMigrationService.migrateImages(batchSize, deleteSource);
    logger.info(
        "Image migration completed: scanned={}, uploaded={}, deleted={}, failed={}",
        result.scanned(),
        result.uploaded(),
        result.deleted(),
        result.failed());
  }
}
