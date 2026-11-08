package net.kdt.pojavlaunch.tasks;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DownloadTaskGroupTest {
    @Test
    public void interruptedOwnerWaitsForEveryDownloadToStop() throws Exception {
        assertWorkersStopBeforeReturn(true);
    }

    @Test
    public void failedDownloadStopsAndJoinsTheRemainingWorkers() throws Exception {
        assertWorkersStopBeforeReturn(false);
    }

    private void assertWorkersStopBeforeReturn(boolean interruptOwner) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean childFinished = new AtomicBoolean();
        AtomicReference<Exception> observed = new AtomicReference<>();
        IOException downloadFailure = new IOException("test download failure");
        AtomicReference<Exception> workerFailure = new AtomicReference<>();
        Runnable blockedDownload = () -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
            childFinished.set(true);
        };
        Runnable failingDownload = () -> {
            if (!interruptOwner) {
                try {
                    entered.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                workerFailure.set(downloadFailure);
            }
        };
        Thread owner = new Thread(() -> {
            try {
                DownloadTaskGroup.run(Arrays.asList(blockedDownload, failingDownload), workerFailure, () -> {});
            } catch (Exception failure) {
                observed.set(failure);
            } finally {
                returned.countDown();
            }
        });
        try {
            owner.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            if (interruptOwner) {
                owner.interrupt();
            }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(1, returned.getCount());
            assertFalse(childFinished.get());

            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(childFinished.get());
            if (interruptOwner) {
                assertTrue(observed.get() instanceof InterruptedException);
            } else {
                assertSame(downloadFailure, observed.get());
            }
        } finally {
            release.countDown();
            owner.interrupt();
            owner.join(2000);
            assertFalse(owner.isAlive());
        }
    }
}
