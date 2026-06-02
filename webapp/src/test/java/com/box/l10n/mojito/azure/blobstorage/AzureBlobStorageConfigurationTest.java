package com.box.l10n.mojito.azure.blobstorage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AzureBlobStorageConfigurationTest {

  @Test
  public void testDefaultContainer() {
    AzureBlobStorageConfigurationProperties properties =
        new AzureBlobStorageConfigurationProperties();

    assertEquals("mojito", properties.getContainer());
  }
}
