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

public final class GameRunProcessObservationTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void liveTimeStartsAtAttachmentAndTerminalPreservesTheLastLiveSample() throws Exception {
        GameRunStore store = GameRunStore.create("TestRun", temporary.newFolder(), 100);
        AtomicLong clock = new AtomicLong(1000);
        GameRunRecorder recorder = new GameRunRecorder(store, clock::get);
        assertNull(store.read().processAliveMillis);
        assertFalse(recorder.checkpoint(110));
        recorder.attachProcess(123, 120);
        assertEquals(Long.valueOf(0), store.read().processAliveMillis);
        clock.set(31_000);
        assertTrue(recorder.checkpoint(-100));
        assertEquals(Long.valueOf(30_000), store.read().processAliveMillis);
        assertNull(store.read().jvmElapsedMillis);

        clock.set(41_000);
        recorder.jvmStarted(200);
        assertEquals(Long.valueOf(40_000), store.read().processAliveMillis);
        assertEquals(Long.valueOf(0), store.read().jvmElapsedMillis);
        clock.set(46_000);
        recorder.firstFrame(210);
        assertEquals(Long.valueOf(45_000), store.read().processAliveMillis);
        clock.set(91_000);
        recorder.exited(0, false, 220);
        assertEquals(Long.valueOf(45_000), store.read().processAliveMillis);
        assertEquals(Long.valueOf(50_000), store.read().jvmElapsedMillis);
        assertFalse(recorder.checkpoint(230));
        assertFalse(recorder.stopped(240));
        assertEquals(Long.valueOf(45_000), store.read().processAliveMillis);
    }

    @Test
    public void failureAndStopNeverInventLiveTimeFromTheirTerminalTimestamp() throws Exception {
        for (boolean userStop : new boolean[] {false, true}) {
            GameRunStore store = GameRunStore.create("TestRun", temporary.newFolder(), 100);
            AtomicLong clock = new AtomicLong(1000);
            GameRunRecorder recorder = new GameRunRecorder(store, clock::get);
            recorder.attachProcess(123, 110);
            clock.set(100_000);
            if (userStop) {
                recorder.stopped(200);
            } else {
                recorder.failed("java.io.IOException", 200);
            }
            assertEquals(Long.valueOf(0), store.read().processAliveMillis);
            assertNull(store.read().jvmElapsedMillis);
        }
    }

    @Test
    public void failedPublicationKeepsDiskEvidenceAndReopenedRecordsDoNotEstimateTime() throws Exception {
        File directory = temporary.newFolder();
        File moved = new File(temporary.getRoot(), "MovedRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        AtomicLong clock = new AtomicLong(1000);
        GameRunRecorder recorder = new GameRunRecorder(store, clock::get);
        recorder.attachProcess(123, 110);
        clock.set(31_000);
        recorder.checkpoint(120);
        Files.move(directory.toPath(), moved.toPath());
        Files.write(directory.toPath(), new byte[0]);
        clock.set(61_000);
        assertThrows(IOException.class, () -> recorder.checkpoint(130));
        assertEquals(Long.valueOf(60_000), recorder.snapshot().processAliveMillis);
        Files.delete(directory.toPath());
        Files.move(moved.toPath(), directory.toPath());
        assertEquals(Long.valueOf(30_000), store.read().processAliveMillis);
        GameRunRecorder reopened = new GameRunRecorder(store, clock::get);
        assertFalse(reopened.checkpoint(140));
        clock.set(91_000);
        recorder.failed("java.io.IOException", 150);
        // 마지막 생존 관측은 저장 실패 중에도 실제 발생했으므로 이후 성공한 종료 저장에 보존한다.
        assertEquals(Long.valueOf(60_000), store.read().processAliveMillis);
    }

    @Test
    public void legacyFilesRemainUnknownAndInvalidLiveValuesAreRejected() throws Exception {
        File directory = temporary.newFolder();
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        new GameRunRecorder(store, () -> 1000).attachProcess(123, 110);
        File file = new File(directory, "state.properties");
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file.toPath())) {
            values.load(input);
        }
        values.remove("processAliveMillis");
        write(file, values);
        assertNull(store.read().processAliveMillis);
        for (String value : new String[] {"-1", "not-a-number", "9223372036854775808"}) {
            values.setProperty("processAliveMillis", value);
            write(file, values);
            assertThrows(IOException.class, store::read);
        }
        values.setProperty("processAliveMillis", "0");
        values.setProperty("processId", "0");
        write(file, values);
        assertThrows(IOException.class, store::read);
    }

    private void write(File file, Properties values) throws IOException {
        try (OutputStream output = Files.newOutputStream(file.toPath())) {
            values.store(output, null);
        }
    }
}
