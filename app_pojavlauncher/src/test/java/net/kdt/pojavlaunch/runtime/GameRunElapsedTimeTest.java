package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GameRunElapsedTimeTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void elapsedTimeStartsAtJvmEntryAndIgnoresWallClockChanges() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100_000);
        AtomicLong elapsed = new AtomicLong(10_000);
        GameRunRecorder recorder = new GameRunRecorder(store, elapsed::get);

        recorder.attachProcess(123, 100_500);
        assertTrue(recorder.checkpoint(101_000));
        assertNull(store.read().jvmElapsedMillis);
        elapsed.set(20_000);
        assertTrue(recorder.jvmStarted(102_000));
        assertEquals(Long.valueOf(0), store.read().jvmElapsedMillis);

        elapsed.set(20_900);
        assertTrue(recorder.checkpoint(1_000));
        assertEquals(Long.valueOf(900), store.read().jvmElapsedMillis);
        assertFalse(recorder.jvmStarted(1_100));
        elapsed.set(20_997);
        assertTrue(recorder.firstFrame(2_000_000));
        assertEquals(Long.valueOf(997), store.read().jvmElapsedMillis);
        assertEquals(Long.valueOf(102_000), store.read().jvmStartedAt);

        elapsed.set(21_023);
        assertTrue(recorder.checkpoint(-100));
        assertEquals(Long.valueOf(1023), store.read().jvmElapsedMillis);
        elapsed.set(21_299);
        assertTrue(recorder.exited(0, false, 300));
        GameRunSnapshot finished = store.read();
        assertEquals(Long.valueOf(1299), finished.jvmElapsedMillis);

        elapsed.set(99_000);
        assertFalse(recorder.checkpoint(400));
        assertFalse(recorder.stopped(500));
        assertFalse(recorder.firstFrame(600));
        assertFalse(recorder.jvmStarted(700));
        assertFalse(recorder.failed("java.lang.IllegalStateException", 800));
        assertEquals(Long.valueOf(1299), store.read().jvmElapsedMillis);
        assertEquals(300, store.read().updatedAt);
    }

    @Test
    public void failureDurationIsFixedBeforeWritingSlowDiagnostics() throws Exception {
        File directory = temporary.newFolder("TestFailure");
        GameRunStore store = GameRunStore.create("TestFailure", directory, 100);
        AtomicLong elapsed = new AtomicLong(1000);
        GameRunRecorder recorder = new GameRunRecorder(store, elapsed::get);
        recorder.jvmStarted(200);

        long failureTick = 1250;
        elapsed.set(9000);
        recorder.failed("java.io.IOException", 500, failureTick);

        GameRunSnapshot failed = store.read();
        assertEquals(GameRunSnapshot.Stage.FAILED, failed.stage);
        assertEquals(Long.valueOf(250), failed.jvmElapsedMillis);
        assertFalse(recorder.checkpoint(900));
        assertFalse(recorder.stopped(1000));
        assertEquals(Long.valueOf(250), store.read().jvmElapsedMillis);
    }

    @Test
    public void startupFailuresHaveNoDurationAndUserStopKeepsItsObservedTime() throws Exception {
        File startupDirectory = temporary.newFolder("TestStartup");
        GameRunStore startupStore = GameRunStore.create("TestStartup", startupDirectory, 100);
        GameRunRecorder startup = new GameRunRecorder(startupStore, () -> 50_000);
        startup.failed("java.io.IOException", 200);
        assertNull(startupStore.read().jvmElapsedMillis);

        File stoppedDirectory = temporary.newFolder("TestStopped");
        GameRunStore stoppedStore = GameRunStore.create("TestStopped", stoppedDirectory, 100);
        AtomicLong elapsed = new AtomicLong(10_000);
        GameRunRecorder stopped = new GameRunRecorder(stoppedStore, elapsed::get);
        stopped.jvmStarted(200);
        elapsed.set(10_123);
        stopped.stopped(250);
        assertEquals(Long.valueOf(123), stoppedStore.read().jvmElapsedMillis);
        assertEquals(GameRunSnapshot.Stage.STOPPED, stoppedStore.read().stage);
        elapsed.set(20_000);
        assertFalse(stopped.exited(9, true, 300));
        assertEquals(Long.valueOf(123), stoppedStore.read().jvmElapsedMillis);
    }

    @Test
    public void failedCheckpointIsRetriedFromTheOriginalStartWithoutResettingTime() throws Exception {
        File directory = temporary.newFolder("TestRun");
        File moved = new File(temporary.getRoot(), "MovedRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        AtomicLong elapsed = new AtomicLong(10_000);
        GameRunRecorder recorder = new GameRunRecorder(store, elapsed::get);
        recorder.jvmStarted(200);
        elapsed.set(10_800);
        recorder.checkpoint(300);
        Files.move(directory.toPath(), moved.toPath());
        Files.write(directory.toPath(), new byte[0]);

        elapsed.set(11_500);
        assertThrows(IOException.class, () -> recorder.checkpoint(400));
        assertEquals(Long.valueOf(1500), recorder.snapshot().jvmElapsedMillis);

        Files.delete(directory.toPath());
        Files.move(moved.toPath(), directory.toPath());
        assertEquals(Long.valueOf(800), store.read().jvmElapsedMillis);
        elapsed.set(12_200);
        assertTrue(recorder.checkpoint(500));
        assertEquals(Long.valueOf(2200), store.read().jvmElapsedMillis);
        assertEquals(Long.valueOf(200), store.read().jvmStartedAt);
    }

    @Test
    public void failedInitialPublicationCanStillSaveTheOriginalJvmStartAtTheNextCheckpoint() throws Exception {
        File directory = temporary.newFolder("TestRun");
        File moved = new File(temporary.getRoot(), "MovedRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        AtomicLong elapsed = new AtomicLong(10_000);
        GameRunRecorder recorder = new GameRunRecorder(store, elapsed::get);
        Files.move(directory.toPath(), moved.toPath());
        Files.write(directory.toPath(), new byte[0]);

        assertThrows(IOException.class, () -> recorder.jvmStarted(200));
        assertEquals(Long.valueOf(0), recorder.snapshot().jvmElapsedMillis);

        Files.delete(directory.toPath());
        Files.move(moved.toPath(), directory.toPath());
        assertNull(store.read().jvmElapsedMillis);
        elapsed.set(10_250);
        assertTrue(recorder.checkpoint(300));
        assertEquals(GameRunSnapshot.Stage.JVM_STARTED, store.read().stage);
        assertEquals(Long.valueOf(200), store.read().jvmStartedAt);
        assertEquals(Long.valueOf(250), store.read().jvmElapsedMillis);
    }

    @Test
    public void failedTerminalPublicationCannotBeExtendedByLaterCallbacks() throws Exception {
        File directory = temporary.newFolder("TestRun");
        File moved = new File(temporary.getRoot(), "MovedRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        AtomicLong elapsed = new AtomicLong(10_000);
        GameRunRecorder recorder = new GameRunRecorder(store, elapsed::get);
        recorder.jvmStarted(200);
        elapsed.set(10_300);
        recorder.checkpoint(300);
        Files.move(directory.toPath(), moved.toPath());
        Files.write(directory.toPath(), new byte[0]);

        elapsed.set(10_450);
        assertThrows(IOException.class, () -> recorder.exited(11, true, 400));
        assertEquals(Long.valueOf(450), recorder.snapshot().jvmElapsedMillis);
        elapsed.set(90_000);
        assertFalse(recorder.checkpoint(500));
        assertFalse(recorder.stopped(600));

        Files.delete(directory.toPath());
        Files.move(moved.toPath(), directory.toPath());
        // 디스크에는 마지막 성공한 관측만 남으며 최종 저장 실패를 성공으로 합성하지 않는다.
        assertEquals(Long.valueOf(300), store.read().jvmElapsedMillis);
        assertFalse(store.read().isTerminal());
        GameRunRecorder reopened = new GameRunRecorder(store, elapsed::get);
        assertFalse(reopened.checkpoint(700));
        assertEquals(Long.valueOf(300), store.read().jvmElapsedMillis);
    }

    @Test
    public void formatOneWithoutDurationRemainsReadableAndInvalidDurationsAreRejected() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        GameRunRecorder recorder = new GameRunRecorder(store, () -> 1000);
        recorder.jvmStarted(200);
        File state = new File(directory, "state.properties");
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(state.toPath())) {
            values.load(input);
        }
        assertEquals("1", values.getProperty("format"));
        assertEquals("0", values.getProperty("jvmElapsedMillis"));
        values.remove("jvmElapsedMillis");
        write(state, values);
        assertNull(store.read().jvmElapsedMillis);
        assertEquals(Long.valueOf(200), store.read().jvmStartedAt);

        for (String duration : new String[] {"-1", "not-a-number", "9223372036854775808"}) {
            values.setProperty("jvmElapsedMillis", duration);
            write(state, values);
            assertThrows(IOException.class, store::read);
        }
        values.setProperty("jvmElapsedMillis", "0");
        values.setProperty("stage", "STARTING");
        values.remove("jvmStartedAt");
        write(state, values);
        assertThrows(IOException.class, store::read);
    }

    private void write(File file, Properties values) throws IOException {
        try (OutputStream output = Files.newOutputStream(file.toPath())) {
            values.store(output, null);
        }
    }
}
