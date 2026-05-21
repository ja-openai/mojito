package com.box.l10n.mojito;

import static org.junit.Assert.*;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author jaurambault
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = AsyncConfig.class)
public class AsyncConfigTest {

  @Autowired
  @Qualifier("asyncExecutor")
  AsyncTaskExecutor asyncExecutor;

  @Test
  public void testAsync() throws InterruptedException, ExecutionException {
    String threadName = Thread.currentThread().getName();
    Future<String> doAsync = asyncExecutor.submit(this::getThreadName);
    assertNotEquals(threadName, doAsync.get());
  }

  public String getThreadName() {
    return Thread.currentThread().getName();
  }
}
