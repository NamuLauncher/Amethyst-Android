package net.kdt.pojavlaunch.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class PreparationTaskTest {
    @Test
    public void cancellationBeforeWorkerStartsNeverRunsTheAction() {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        BlockingQueue<Runnable> workers = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        PreparationTask request = new PreparationTask(callbacks::add, listener);
        AtomicBoolean ran = new AtomicBoolean();
        request.start(workers::add, () -> ran.set(true));

        assertTrue(request.cancel());
        workers.remove()
                .run();
        assertFalse(ran.get());
        assertTrue(listener.events.isEmpty());
        drain(callbacks);
        assertEquals(Arrays.asList("cancelled"), listener.events);
        assertFalse(request.cancel());
    }

    @Test
    public void acceptedCancellationWaitsForActualWorkerEndAndSuppressesQueuedProgress() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        PreparationTask request = new PreparationTask(callbacks::add, listener);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean enteredNextStage = new AtomicBoolean();
        try {
            request.start(worker, () -> {
                request.progress(progress(GamePreparationProgress.Stage.GAME_FILES));
                entered.countDown();
                waitUntilReleased(release, interrupted);
                request.progress(progress(GamePreparationProgress.Stage.NATIVE_LIBRARIES));
                enteredNextStage.set(true);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(request.cancel());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            drain(callbacks);
            assertTrue(listener.events.isEmpty());

            release.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            drain(callbacks);
            assertEquals(Arrays.asList("cancelled"), listener.events);
            assertFalse(enteredNextStage.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void nonCancellableStageRejectsCancellationAndCompletesNormally() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        PreparationTask request = new PreparationTask(callbacks::add, listener);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            request.start(worker, () -> {
                request.progress(progress(GamePreparationProgress.Stage.JAVA_RUNTIME));
                entered.countDown();
                release.await();
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            drain(callbacks);
            assertFalse(listener.progress.cancellable);
            assertFalse(request.cancel());
            release.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            drain(callbacks);
            assertEquals(Arrays.asList("progress", "ready"), listener.events);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void cancellationAfterWorkFinishedDoesNotReplaceQueuedReady() {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        RecordingListener listener = new RecordingListener();
        PreparationTask request = new PreparationTask(callbacks::add, listener);
        request.start(Runnable::run, () -> request.progress(progress(GamePreparationProgress.Stage.METADATA)));

        assertFalse(request.cancel());
        assertTrue(listener.events.isEmpty());
        drain(callbacks);
        assertTrue(listener.progress.cancellable);
        assertEquals(Arrays.asList("progress", "ready"), listener.events);
    }

    @Test
    public void legacyListenerReceivesCancellationAsFailure() {
        List<Throwable> failures = new ArrayList<>();
        AmethystRuntime.PreparationListener listener = new AmethystRuntime.PreparationListener() {
            @Override
            public void onProgress(GamePreparationProgress progress) {}

            @Override
            public void onReady() {
                fail("Cancelled request must not be ready");
            }

            @Override
            public void onFailure(Throwable failure) {
                failures.add(failure);
            }
        };
        listener.onCancelled();
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof CancellationException);
    }

    private static GamePreparationProgress progress(GamePreparationProgress.Stage stage) {
        return new GamePreparationProgress(
                stage,
                GamePreparationProgress.Unit.UNKNOWN,
                0,
                -1
        );
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

    private static void drain(BlockingQueue<Runnable> queue) {
        Runnable callback;
        while ((callback = queue.poll()) != null) {
            callback.run();
        }
    }

    private static final class RecordingListener implements AmethystRuntime.PreparationListener {
        final List<String> events = new ArrayList<>();
        GamePreparationProgress progress;

        @Override
        public void onProgress(GamePreparationProgress value) {
            events.add("progress");
            progress = value;
        }

        @Override
        public void onReady() {
            events.add("ready");
        }

        @Override
        public void onFailure(Throwable failure) {
            events.add("failure");
        }

        @Override
        public void onCancelled() {
            events.add("cancelled");
        }
    }
}
