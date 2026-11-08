package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class AuthenticationTaskTest {
    @Test
    public void successCommitsOnceAndCompletesAfterWorkCleanup() {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        AuthenticationTask request = new AuthenticationTask(callbacks::add, listener);
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean cleanedUp = new AtomicBoolean();
        request.start(Runnable::run, () -> {
            try {
                request.commit(writes::incrementAndGet);
                assertThrows(IllegalStateException.class, () -> request.commit(writes::incrementAndGet));
            } finally {
                cleanedUp.set(true);
            }
        });

        assertEquals(1, writes.get());
        assertTrue(cleanedUp.get());
        assertTrue(listener.events.isEmpty());
        assertFalse(request.cancel());
        drain(callbacks);
        assertEquals(Arrays.asList("ready"), listener.events);
        assertThrows(IllegalStateException.class, () -> request.start(Runnable::run, () -> {}));
    }

    @Test
    public void queuedCancellationDoesNotRunAuthenticationOrFinishBeforeWorkerIsObserved() {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        BlockingQueue<Runnable> workers = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        AuthenticationTask request = new AuthenticationTask(callbacks::add, listener);
        AtomicBoolean ran = new AtomicBoolean();
        request.start(workers::add, () -> ran.set(true));

        assertTrue(request.cancel());
        assertTrue(callbacks.isEmpty());
        workers.remove()
                .run();

        assertFalse(ran.get());
        assertTrue(listener.events.isEmpty());
        drain(callbacks);
        assertEquals(Arrays.asList("cancelled"), listener.events);
        assertFalse(request.cancel());
    }

    @Test
    public void acceptedCancellationWaitsForAnInterruptIgnoringWorkerAndPreventsLateCommit() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        AuthenticationTask request = new AuthenticationTask(callbacks::add, listener);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean cleanedUp = new AtomicBoolean();
        try {
            request.start(executor, () -> {
                try {
                    entered.countDown();
                    waitUntilReleased(release, interrupted);
                    request.commit(writes::incrementAndGet);
                } finally {
                    cleanedUp.set(true);
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(request.cancel());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(cleanedUp.get());
            assertTrue(callbacks.isEmpty());

            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(cleanedUp.get());
            assertEquals(0, writes.get());
            drain(callbacks);
            assertEquals(Arrays.asList("cancelled"), listener.events);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void cancellationAfterCommitStartsIsRejectedAndWaitsForItsResult() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        AuthenticationTask request = new AuthenticationTask(callbacks::add, listener);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch enteredCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        try {
            request.start(executor, () -> request.commit(() -> {
                enteredCommit.countDown();
                releaseCommit.await();
                writes.incrementAndGet();
            }));
            assertTrue(enteredCommit.await(2, TimeUnit.SECONDS));

            assertFalse(request.cancel());
            assertEquals(0, writes.get());
            assertTrue(callbacks.isEmpty());

            releaseCommit.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            drain(callbacks);
            assertEquals(Arrays.asList("ready"), listener.events);
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void failuresAndExecutorRejectionDeliverOneSanitizedResult() {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener failed = new RecordingListener();
        AuthenticationTask failureRequest = new AuthenticationTask(callbacks::add, failed);
        failureRequest.start(Runnable::run, () -> {
            throw new IOException("test-private-token");
        });
        drain(callbacks);
        assertEquals(Arrays.asList("failure"), failed.events);
        assertEquals(AccountAuthenticationException.Reason.NETWORK, failed.failure.reason);
        assertFalse(failed.failure.toString().contains("test-private-token"));
        assertNull(failed.failure.getCause());
        assertFalse(failureRequest.cancel());

        RecordingListener rejected = new RecordingListener();
        AuthenticationTask rejectedRequest = new AuthenticationTask(callbacks::add, rejected);
        rejectedRequest.start(action -> {
            throw new RejectedExecutionException("test-private-token");
        }, () -> {});
        drain(callbacks);
        assertEquals(Arrays.asList("failure"), rejected.events);
        assertEquals(AccountAuthenticationException.Reason.UNKNOWN, rejected.failure.reason);
        assertFalse(rejected.failure.toString().contains("test-private-token"));
        assertNull(rejected.failure.getCause());
        assertFalse(rejectedRequest.cancel());
    }

    @Test
    public void typedCommitFailureIsPreservedAndThreadInterruptIsClearedBeforeCallback() {
        RecordingListener listener = new RecordingListener();
        AuthenticationTask request = new AuthenticationTask(Runnable::run, listener);
        AccountAuthenticationException storage = new AccountAuthenticationException(
                AccountAuthenticationException.Reason.STORAGE
        );
        request.start(Runnable::run, () -> request.commit(() -> {
            Thread.currentThread()
                    .interrupt();
            throw storage;
        }));

        assertEquals(Arrays.asList("failure"), listener.events);
        assertSame(storage, listener.failure);
        assertFalse(listener.callbackInterrupted);
        assertFalse(Thread.currentThread()
                .isInterrupted());
        assertFalse(request.cancel());

        RecordingListener cancelled = new RecordingListener();
        AuthenticationTask cancelledRequest = new AuthenticationTask(Runnable::run, cancelled);
        cancelledRequest.start(Runnable::run, () -> {
            throw new CancellationException("test-private-token");
        });
        assertEquals(Arrays.asList("cancelled"), cancelled.events);
        assertNull(cancelled.failure);
    }

    private static void waitUntilReleased(CountDownLatch release, CountDownLatch interrupted) {
        boolean waiting = true;
        while (waiting) {
            try {
                release.await();
                waiting = false;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    private static void drain(BlockingQueue<Runnable> callbacks) {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) {
            callback.run();
        }
    }

    private static final class RecordingListener implements AmethystRuntime.AuthenticationListener {
        final List<String> events = new ArrayList<>();
        AccountAuthenticationException failure;
        boolean callbackInterrupted;

        @Override
        public void onReady() {
            callbackInterrupted = Thread.currentThread()
                    .isInterrupted();
            events.add("ready");
        }

        @Override
        public void onFailure(Throwable value) {
            callbackInterrupted = Thread.currentThread()
                    .isInterrupted();
            failure = (AccountAuthenticationException) value;
            events.add("failure");
        }

        @Override
        public void onCancelled() {
            callbackInterrupted = Thread.currentThread()
                    .isInterrupted();
            events.add("cancelled");
        }
    }
}
