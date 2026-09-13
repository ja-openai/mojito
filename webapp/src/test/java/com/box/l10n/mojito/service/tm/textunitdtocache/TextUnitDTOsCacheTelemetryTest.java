package com.box.l10n.mojito.service.tm.textunitdtocache;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;
import static com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheBlobStorage.CACHE_LOOKUP_METRIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TextUnitDTOsCacheTelemetryTest {

  private static final long ASSET_ID = 1234L;
  private static final long LOCALE_ID = 56L;

  private final String format;
  private final boolean hit;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private TextUnitDTOsCacheBlobStorage cache;
  private Optional<TextUnitDTOsCacheBlobStorageJson> expected;

  @Parameterized.Parameters(name = "{0}, hit={1}")
  public static Collection<Object[]> cases() {
    return Arrays.asList(
        new Object[] {"json", true},
        new Object[] {"json", false},
        new Object[] {"smile", true},
        new Object[] {"smile", false});
  }

  public TextUnitDTOsCacheTelemetryTest(String format, boolean hit) {
    this.format = format;
    this.hit = hit;
  }

  @Before
  public void setUp() {
    cache = spy(newCache());
    cache.meterRegistry = meterRegistry;
    TextUnitDTOsCacheBlobStorageJson entry = new TextUnitDTOsCacheBlobStorageJson();
    TextUnitDTO dto = new TextUnitDTO();
    dto.setName("telemetry-test");
    entry.setTextUnitDTOs(List.of(dto));
    expected = hit ? Optional.of(entry) : Optional.empty();
    // Isolate lookup telemetry, leaving both concrete format-tag implementations intact.
    doReturn(expected).when(cache).getCacheEntryFromCache(ASSET_ID, LOCALE_ID);
  }

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void meterTypeCollisionPreservesLookupResult() {
    Gauge.builder(CACHE_LOOKUP_METRIC, () -> 0).tags(tags()).register(meterRegistry);
    assertThrows(
        IllegalArgumentException.class, () -> meterRegistry.counter(CACHE_LOOKUP_METRIC, tags()));

    assertLookupResult();
    assertEquals(1, meterRegistry.getMeters().size());
  }

  @Test
  public void meterAddedProviderErrorPreservesLookupResult() {
    AtomicInteger attempts = failOnMeterAdded(new AssertionError("provider failed"));

    assertLookupResult();

    assertEquals(1, attempts.get());
  }

  @Test
  public void counterIncrementExceptionPreservesLookupResult() {
    Counter counter = failOnIncrement(new IllegalStateException("increment failed"));

    assertLookupResult();

    verify(counter).increment();
  }

  @Test
  public void counterIncrementErrorPreservesLookupResult() {
    Counter counter = failOnIncrement(new AssertionError("increment failed"));

    assertLookupResult();

    verify(counter).increment();
  }

  @Test
  public void healthyCounterRetainsNameTagsAndCounts() {
    assertEquals("TextUnitDTOsCacheBlobStorage.lookup", CACHE_LOOKUP_METRIC);
    assertSame(expected, cache.getCacheEntry(ASSET_ID, LOCALE_ID));
    assertSame(expected, cache.getCacheEntry(ASSET_ID, LOCALE_ID));

    assertEquals(2, meterRegistry.get(CACHE_LOOKUP_METRIC).tags(tags()).counter().count(), 0);
    assertEquals(1, meterRegistry.getMeters().size());
    verify(cache, times(2)).getCacheEntryFromCache(ASSET_ID, LOCALE_ID);
  }

  @Test
  public void storageFailurePropagatesUnchangedWithoutRecordingLookup() {
    TextUnitDTOsCacheBlobStorage readingCache = newCache();
    StructuredBlobStorage storage = mock(StructuredBlobStorage.class);
    MeterRegistry unusedRegistry = mock(MeterRegistry.class);
    readingCache.structuredBlobStorage = storage;
    readingCache.meterRegistry = unusedRegistry;
    IllegalStateException failure = new IllegalStateException("storage unavailable");
    if (format.equals("smile")) {
      when(storage.getBytes(TEXT_UNIT_DTOS_CACHE, readingCache.getName(ASSET_ID, LOCALE_ID)))
          .thenThrow(failure);
    } else {
      when(storage.getString(TEXT_UNIT_DTOS_CACHE, readingCache.getName(ASSET_ID, LOCALE_ID)))
          .thenThrow(failure);
    }

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> readingCache.getCacheEntry(ASSET_ID, LOCALE_ID)));
    verifyNoInteractions(unusedRegistry);
  }

  @Test
  public void readErrorPropagatesUnchangedWithoutRecordingLookup() {
    MeterRegistry unusedRegistry = mock(MeterRegistry.class);
    cache.meterRegistry = unusedRegistry;
    AssertionError failure = new AssertionError("read failed");
    doThrow(failure).when(cache).getCacheEntryFromCache(ASSET_ID, LOCALE_ID);

    assertSame(
        failure,
        assertThrows(AssertionError.class, () -> cache.getCacheEntry(ASSET_ID, LOCALE_ID)));
    verifyNoInteractions(unusedRegistry);
  }

  @Test
  public void fatalMeterAddedErrorPropagatesUnchanged() {
    assertFatalMeterAdded(new VirtualMachineError("fatal provider") {});
  }

  @Test
  public void meterAddedThreadDeathPropagatesUnchanged() {
    assertFatalMeterAdded(new ThreadDeath());
  }

  @Test
  public void fatalIncrementErrorPropagatesUnchanged() {
    assertFatalIncrement(new VirtualMachineError("fatal increment") {});
  }

  @Test
  public void incrementThreadDeathPropagatesUnchanged() {
    assertFatalIncrement(new ThreadDeath());
  }

  private TextUnitDTOsCacheBlobStorage newCache() {
    return format.equals("smile")
        ? new TextUnitDTOsSmileCacheBlobStorage()
        : new TextUnitDTOsCacheBlobStorage();
  }

  private String[] tags() {
    return new String[] {"format", format, "result", hit ? "hit" : "miss"};
  }

  private void assertLookupResult() {
    assertSame(expected, cache.getCacheEntry(ASSET_ID, LOCALE_ID));
    verify(cache).getCacheEntryFromCache(ASSET_ID, LOCALE_ID);
  }

  private AtomicInteger failOnMeterAdded(Error failure) {
    AtomicInteger attempts = new AtomicInteger();
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals(CACHE_LOOKUP_METRIC)) {
                attempts.incrementAndGet();
                throw failure;
              }
            });
    return attempts;
  }

  private Counter failOnIncrement(Throwable failure) {
    MeterRegistry registry = mock(MeterRegistry.class);
    Counter counter = mock(Counter.class);
    when(registry.counter(CACHE_LOOKUP_METRIC, tags())).thenReturn(counter);
    doThrow(failure).when(counter).increment();
    cache.meterRegistry = registry;
    return counter;
  }

  private void assertFatalMeterAdded(Error fatal) {
    AtomicInteger attempts = failOnMeterAdded(fatal);

    assertSame(
        fatal, assertThrows(fatal.getClass(), () -> cache.getCacheEntry(ASSET_ID, LOCALE_ID)));

    assertEquals(1, attempts.get());
    verify(cache).getCacheEntryFromCache(ASSET_ID, LOCALE_ID);
  }

  private void assertFatalIncrement(Error fatal) {
    Counter counter = failOnIncrement(fatal);

    assertSame(
        fatal, assertThrows(fatal.getClass(), () -> cache.getCacheEntry(ASSET_ID, LOCALE_ID)));

    verify(counter).increment();
    verify(cache).getCacheEntryFromCache(ASSET_ID, LOCALE_ID);
  }
}
