package com.box.l10n.mojito.azure.blobstorage;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.common.base.Strings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("l10n.azure.blob-storage.enabled")
public class AzureBlobStorageConfiguration {

  @Bean
  public BlobContainerClient blobContainerClient(
      AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties) {
    BlobServiceClientBuilder blobServiceClientBuilder = new BlobServiceClientBuilder();

    if (!Strings.isNullOrEmpty(azureBlobStorageConfigurationProperties.getConnectionString())) {
      blobServiceClientBuilder.connectionString(
          azureBlobStorageConfigurationProperties.getConnectionString());
    } else {
      blobServiceClientBuilder
          .endpoint(azureBlobStorageConfigurationProperties.getEndpoint())
          .credential(new DefaultAzureCredentialBuilder().build());
    }

    BlobServiceClient blobServiceClient = blobServiceClientBuilder.buildClient();
    return blobServiceClient.getBlobContainerClient(
        azureBlobStorageConfigurationProperties.getContainer());
  }
}
