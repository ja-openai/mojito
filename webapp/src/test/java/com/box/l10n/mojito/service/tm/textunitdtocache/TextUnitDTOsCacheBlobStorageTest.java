package com.box.l10n.mojito.service.tm.textunitdtocache;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.google.common.collect.ImmutableList;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class TextUnitDTOsCacheBlobStorageTest extends ServiceTestBase {

  @Autowired TextUnitDTOsCacheBlobStorage textUnitDTOsCacheBlobStorage;

  @Autowired MeterRegistry meterRegistry;

  @Test
  public void readInvalidData() {
    long assetId = 123243L;
    long localeId = 234L;
    textUnitDTOsCacheBlobStorage.structuredBlobStorage.put(
        TEXT_UNIT_DTOS_CACHE,
        textUnitDTOsCacheBlobStorage.getName(assetId, localeId),
        "bad content",
        Retention.PERMANENT);
    List<TextUnitDTO> textUnitDTOS =
        textUnitDTOsCacheBlobStorage.getTextUnitDTOs(assetId, localeId).get();
    assertEquals(
        "Should be empty (if not make sure the test is not run with a store that as data for that entry",
        textUnitDTOS,
        Collections.emptyList());
  }

  @Test
  public void readNoData() {
    long assetId = 123243L;
    long localeId = 234L;
    textUnitDTOsCacheBlobStorage.structuredBlobStorage.delete(
        TEXT_UNIT_DTOS_CACHE, textUnitDTOsCacheBlobStorage.getName(assetId, localeId));
    double missesBefore = cacheLookupCount("json", "miss");
    Optional<ImmutableList<TextUnitDTO>> textUnitDTOS =
        textUnitDTOsCacheBlobStorage.getTextUnitDTOs(assetId, localeId);
    assertFalse(textUnitDTOS.isPresent());
    assertEquals(missesBefore + 1, cacheLookupCount("json", "miss"), 0);
  }

  @Test
  public void writeAndRead() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setName(UUID.randomUUID().toString());
    ImmutableList<TextUnitDTO> textUnitDTOSToWrite = ImmutableList.of(textUnitDTO);
    textUnitDTOsCacheBlobStorage.putTextUnitDTOs(12345L, 12345L, textUnitDTOSToWrite);
    double hitsBefore = cacheLookupCount("json", "hit");
    List<TextUnitDTO> readTextUnitDTOS =
        textUnitDTOsCacheBlobStorage.getTextUnitDTOs(12345L, 12345L).get();

    Assertions.assertThat(readTextUnitDTOS)
        .usingFieldByFieldElementComparator()
        .containsExactlyElementsOf(textUnitDTOSToWrite);
    assertEquals(hitsBefore + 1, cacheLookupCount("json", "hit"), 0);
  }

  @Test
  public void getName() {
    String blobName = textUnitDTOsCacheBlobStorage.getName(1234L, 56L);
    assertEquals("asset/1234/locale/56", blobName);
  }

  @Test
  public void convertToListOrEmptyList() {
    ImmutableList<TextUnitDTO> textUnitDTOS =
        textUnitDTOsCacheBlobStorage.convertToListOrEmptyList("some bad content for testing");
    assertEquals(ImmutableList.of(), textUnitDTOS);
  }

  private double cacheLookupCount(String format, String result) {
    return meterRegistry
        .counter(
            TextUnitDTOsCacheBlobStorage.CACHE_LOOKUP_METRIC, "format", format, "result", result)
        .count();
  }
}
