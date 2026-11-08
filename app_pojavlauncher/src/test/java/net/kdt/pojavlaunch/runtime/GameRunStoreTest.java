package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GameRunStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void independentReaderSeesCompleteStagesAndOptionalMetadata() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunStore writer = GameRunStore.create("TestRun", directory, 100);
        GameRunStore reader = new GameRunStore(directory);
        GameRunSnapshot starting = reader.read();
        assertNotNull(starting);
        assertEquals("TestRun", starting.runId);
        assertEquals(GameRunSnapshot.Stage.STARTING, starting.stage);
        assertEquals(100, starting.startedAt);
        assertEquals(100, starting.updatedAt);
        assertEquals(0, starting.processId);
        assertNull(starting.jvmStartedAt);
        assertNull(starting.firstFrameAt);
        assertNull(starting.exitCode);
        assertNull(starting.failureType);
        assertFalse(starting.signal);
        assertFalse(starting.isTerminal());

        GameRunRecorder recorder = new GameRunRecorder(writer);
        assertTrue(recorder.attachProcess(123, 110));
        assertEquals(123, reader.read().processId);
        assertTrue(recorder.jvmStarted(120));
        assertEquals(GameRunSnapshot.Stage.JVM_STARTED, reader.read().stage);
        assertTrue(recorder.firstFrame(130));
        GameRunSnapshot frame = reader.read();
        assertEquals(GameRunSnapshot.Stage.FIRST_FRAME, frame.stage);
        assertEquals(100, frame.startedAt);
        assertEquals(130, frame.updatedAt);
        assertEquals(Long.valueOf(120), frame.jvmStartedAt);
        assertEquals(Long.valueOf(130), frame.firstFrameAt);

        assertEquals(new File(directory, "runtime.log"), writer.logFile());
        assertEquals(new File(directory, "hs_err_pid%p.log"), writer.errorFilePattern());
        assertEquals(1, directory.listFiles().length);
    }

    @Test
    public void normalNonzeroAndSignalExitsRemainDistinct() throws Exception {
        int[] codes = {0, 7, 11};
        for (int index = 0; index < codes.length; index++) {
            File directory = temporary.newFolder("TestRun-" + index);
            GameRunRecorder recorder = new GameRunRecorder(
                    GameRunStore.create("TestRun-" + index, directory, 100)
            );
            recorder.attachProcess(123, 105);
            recorder.jvmStarted(110);
            boolean signal = index == 2;

            assertTrue(recorder.exited(codes[index], signal, 200));

            GameRunSnapshot result = new GameRunStore(directory).read();
            assertEquals(GameRunSnapshot.Stage.EXITED, result.stage);
            assertTrue(result.isTerminal());
            assertEquals(Integer.valueOf(codes[index]), result.exitCode);
            assertEquals(signal, result.signal);
            assertNull(result.failureType);
            assertEquals(Long.valueOf(110), result.jvmStartedAt);
            assertNull(result.firstFrameAt);
        }
    }

    @Test
    public void startupFailureAndUserStopDoNotInventAnExitCode() throws Exception {
        File failedDirectory = temporary.newFolder("FailedRun");
        GameRunRecorder failed = new GameRunRecorder(
                GameRunStore.create("FailedRun", failedDirectory, 100)
        );
        assertTrue(failed.failed("java.io.IOException", 150));

        GameRunSnapshot failure = new GameRunStore(failedDirectory).read();
        assertEquals(GameRunSnapshot.Stage.FAILED, failure.stage);
        assertEquals("java.io.IOException", failure.failureType);
        assertNull(failure.exitCode);
        assertNull(failure.jvmStartedAt);
        assertTrue(failure.isTerminal());

        File stoppedDirectory = temporary.newFolder("StoppedRun");
        GameRunRecorder stopped = new GameRunRecorder(
                GameRunStore.create("StoppedRun", stoppedDirectory, 200)
        );
        assertTrue(stopped.stopped(250));

        GameRunSnapshot stop = new GameRunStore(stoppedDirectory).read();
        assertEquals(GameRunSnapshot.Stage.STOPPED, stop.stage);
        assertNull(stop.exitCode);
        assertNull(stop.failureType);
        assertFalse(stop.signal);
    }

    @Test
    public void duplicateAndReorderedCallbacksDoNotRegressOrReplaceTerminalResults() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunRecorder recorder = new GameRunRecorder(GameRunStore.create("TestRun", directory, 100));
        assertFalse(recorder.firstFrame(110));
        assertTrue(recorder.attachProcess(123, 120));
        assertFalse(recorder.attachProcess(123, 121));
        assertTrue(recorder.jvmStarted(130));
        assertFalse(recorder.jvmStarted(131));
        assertTrue(recorder.firstFrame(140));
        assertFalse(recorder.firstFrame(141));
        assertFalse(recorder.jvmStarted(142));
        assertTrue(recorder.exited(0, false, 150));

        assertFalse(recorder.exited(9, true, 160));
        assertFalse(recorder.failed("java.lang.IllegalStateException", 170));
        assertFalse(recorder.stopped(180));
        assertFalse(recorder.attachProcess(456, 190));
        assertFalse(recorder.jvmStarted(200));
        assertFalse(recorder.firstFrame(210));

        GameRunSnapshot result = new GameRunStore(directory).read();
        assertEquals(GameRunSnapshot.Stage.EXITED, result.stage);
        assertEquals(Integer.valueOf(0), result.exitCode);
        assertFalse(result.signal);
        assertEquals(123, result.processId);
        assertEquals(150, result.updatedAt);
        assertEquals(Long.valueOf(130), result.jvmStartedAt);
        assertEquals(Long.valueOf(140), result.firstFrameAt);
    }

    @Test
    public void reopeningARecordedRunPreservesItsFirstTerminalEvent() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunRecorder first = new GameRunRecorder(GameRunStore.create("TestRun", directory, 100));
        first.stopped(150);

        GameRunRecorder reopened = new GameRunRecorder(new GameRunStore(directory));
        assertFalse(reopened.exited(0, false, 200));
        assertFalse(reopened.failed("java.lang.RuntimeException", 210));
        assertEquals(GameRunSnapshot.Stage.STOPPED, reopened.snapshot().stage);
        assertEquals(150, reopened.snapshot().updatedAt);
    }

    @Test
    public void aDifferentProcessCannotReuseThePreviousRunRecord() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        GameRunRecorder first = new GameRunRecorder(store);
        first.attachProcess(123, 110);
        first.jvmStarted(120);
        GameRunRecorder restarted = new GameRunRecorder(new GameRunStore(directory));

        assertThrows(IOException.class, () -> restarted.attachProcess(456, 200));

        assertEquals(123, store.read().processId);
        assertEquals(Long.valueOf(120), store.read().jvmStartedAt);
    }

    @Test
    public void missingStateIsDifferentFromMalformedAndUnreadableState() throws Exception {
        File directory = temporary.newFolder("MissingRun");
        GameRunStore store = new GameRunStore(directory);
        assertNull(store.read());
        assertThrows(IOException.class, () -> new GameRunRecorder(store));
        File state = new File(directory, "state.properties");
        Files.write(state.toPath(), "invalid state".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, store::read);

        assertTrue(state.delete());
        assertTrue(state.mkdir());
        assertThrows(IOException.class, store::read);
    }

    @Test
    public void malformedTypedMetadataIsNotSilentlyCoerced() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        File state = new File(directory, "state.properties");
        Properties valid = new Properties();
        try (InputStream input = Files.newInputStream(state.toPath())) {
            valid.load(input);
        }
        String[][] invalidValues = {
                {"format", "2"},
                {"runId", "../another-run"},
                {"stage", "LOADED"},
                {"startedAt", "later"},
                {"processId", "-1"},
                {"signal", "not-a-boolean"},
                {"jvmStartedAt", "invalid-time"},
                {"exitCode", "0"},
                {"firstFrameAt", "150"}
        };
        for (String[] invalid : invalidValues) {
            Properties values = new Properties();
            values.putAll(valid);
            values.setProperty(invalid[0], invalid[1]);
            try (OutputStream output = Files.newOutputStream(state.toPath())) {
                values.store(output, null);
            }
            assertThrows(invalid[0], IOException.class, store::read);
        }
    }

    @Test
    public void createDoesNotOverwriteAnExistingRunOrAcceptInvalidIds() throws Exception {
        File directory = temporary.newFolder("TestRun");
        GameRunStore store = GameRunStore.create("TestRun", directory, 100);
        new GameRunRecorder(store).exited(7, false, 200);

        assertThrows(IOException.class, () -> GameRunStore.create("AnotherRun", directory, 300));

        GameRunSnapshot original = store.read();
        assertEquals("TestRun", original.runId);
        assertEquals(Integer.valueOf(7), original.exitCode);
        assertEquals(200, original.updatedAt);
        for (String runId : new String[] {null, "", "a/b", "a\\b", "a.b", "a b"}) {
            assertThrows(IllegalArgumentException.class, () -> GameRunStore.create(runId, directory, 400));
        }
    }

    @Test
    public void failedPublicationDoesNotLetCleanupReplaceTheObservedExit() throws Exception {
        File directory = temporary.newFolder("TestRun");
        File moved = new File(temporary.getRoot(), "MovedRun");
        GameRunRecorder recorder = new GameRunRecorder(GameRunStore.create("TestRun", directory, 100));
        Files.move(directory.toPath(), moved.toPath());
        Files.write(directory.toPath(), new byte[0]);

        assertThrows(IOException.class, () -> recorder.exited(7, true, 200));
        assertEquals(GameRunSnapshot.Stage.EXITED, recorder.snapshot().stage);
        assertEquals(Integer.valueOf(7), recorder.snapshot().exitCode);

        Files.delete(directory.toPath());
        Files.move(moved.toPath(), directory.toPath());
        assertFalse(recorder.stopped(210));
        // 게시 실패를 성공으로 숨기지 않는다. 다른 프로세스에는 마지막 완성본만 보인다.
        assertEquals(GameRunSnapshot.Stage.STARTING, new GameRunStore(directory).read().stage);
    }
}
