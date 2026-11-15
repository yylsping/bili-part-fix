package io.github.yylsping.bilipartfix;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class BoundedTaskRunnerTest {
    @Test public void deadlineInterruptsBlockedWorkAndWorkerRemainsUsable() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            BoundedTaskRunner.run(() -> {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
                return null;
            }, 100);
            fail("blocked task must time out");
        } catch (TimeoutException expected) {
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
        assertEquals("ready", BoundedTaskRunner.run(() -> "ready", 2000));
    }

    @Test public void failureIsNotReturnedAsSuccessfulMedia() throws Exception {
        try {
            BoundedTaskRunner.run(() -> { throw new IllegalStateException("transport failed"); }, 2000);
            fail("transport error must propagate");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }
}
