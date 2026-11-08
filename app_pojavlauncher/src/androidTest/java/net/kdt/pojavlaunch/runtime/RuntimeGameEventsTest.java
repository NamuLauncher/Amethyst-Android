package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public final class RuntimeGameEventsTest {
    private static final long TIMEOUT_MILLIS = 10_000;
    private final Context context = InstrumentationRegistry
            .getInstrumentation()
            .getTargetContext();
    private final List<String> changedRuns = Collections.synchronizedList(new ArrayList<>());
    private File directory;
    private String runId;
    private BroadcastReceiver receiver;

    @Before
    public void createRun() throws Exception {
        runId = "TestRun-" + UUID.randomUUID();
        directory = new File(context.getFilesDir(), "runtime-probes/" + runId);
        GameRunStore.create(runId, directory, System.currentTimeMillis());
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                assertEquals(context.getPackageName(), intent.getPackage());
                changedRuns.add(intent.getStringExtra(AmethystRuntime.EXTRA_RUN_ID));
            }
        };
        ContextCompat.registerReceiver(
                context,
                receiver,
                new IntentFilter(AmethystRuntime.ACTION_GAME_RUN_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @After
    public void removeRun() throws Exception {
        if (gameProcessId() != null && directory != null) {
            command(RuntimeProbeService.QUIT);
            awaitNoGameProcess();
        }
        if (receiver != null) {
            context.unregisterReceiver(receiver);
        }
        if (directory != null && directory.exists()) {
            try (Stream<Path> paths = Files.walk(directory.toPath())) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
            }
        }
    }

    @Test
    public void separateProcessWritesPhasesAndNotifiesTheHost() throws Exception {
        GameRunSnapshot attached = attach();
        assertEquals(GameRunSnapshot.Stage.STARTING, attached.stage);
        assertNull(attached.jvmStartedAt);
        assertNull(attached.firstFrameAt);
        assertNull(attached.jvmElapsedMillis);

        command(RuntimeProbeService.JVM_STARTED);
        GameRunSnapshot started = awaitStage(GameRunSnapshot.Stage.JVM_STARTED);
        assertNotNull(started.jvmStartedAt);
        assertNull(started.firstFrameAt);
        assertEquals(Long.valueOf(0), started.jvmElapsedMillis);

        command(RuntimeProbeService.FIRST_FRAME);
        GameRunSnapshot frame = awaitStage(GameRunSnapshot.Stage.FIRST_FRAME);
        assertEquals(started.jvmStartedAt, frame.jvmStartedAt);
        assertNotNull(frame.firstFrameAt);
        assertFalse(frame.isTerminal());
        assertNotNull(frame.jvmElapsedMillis);
        assertTrue(frame.jvmElapsedMillis >= started.jvmElapsedMillis);
        awaitNotification();

        assertTrue(AmethystRuntime.stopGame(context, runId));
        awaitStage(GameRunSnapshot.Stage.STOPPED);
        awaitNoGameProcess();
    }

    @Test
    public void gameProcessCheckpointsElapsedTimeWhileTheHostHasNoEventReceiver() throws Exception {
        attach();
        command(RuntimeProbeService.JVM_STARTED);
        GameRunSnapshot started = awaitStage(GameRunSnapshot.Stage.JVM_STARTED);
        assertEquals(Long.valueOf(0), started.jvmElapsedMillis);
        context.unregisterReceiver(receiver);
        receiver = null;

        // 기본 30초 주기를 실제로 기다린다. 호스트의 알림 수신이나 추가 게임 콜백은 없다.
        long deadline = SystemClock.uptimeMillis() + 40_000;
        GameRunSnapshot checkpoint = null;
        while (SystemClock.uptimeMillis() < deadline) {
            failOnProbeError();
            GameRunSnapshot observed = AmethystRuntime.readGameRun(directory);
            if (observed != null && observed.jvmElapsedMillis != null && observed.jvmElapsedMillis > 0) {
                checkpoint = observed;
                break;
            }
            SystemClock.sleep(50);
        }
        assertNotNull("게임 프로세스가 경과시간 체크포인트를 기록하지 않았습니다", checkpoint);
        assertEquals(GameRunSnapshot.Stage.JVM_STARTED, checkpoint.stage);
        assertEquals(started.jvmStartedAt, checkpoint.jvmStartedAt);
        assertTrue(checkpoint.jvmElapsedMillis >= 30_000);

        assertTrue(AmethystRuntime.stopGame(context, runId));
        GameRunSnapshot stopped = awaitStage(GameRunSnapshot.Stage.STOPPED);
        awaitNoGameProcess();
        assertTrue(stopped.jvmElapsedMillis >= checkpoint.jvmElapsedMillis);
        assertEquals(stopped.jvmElapsedMillis, new GameRunStore(directory).read().jvmElapsedMillis);
    }

    @Test
    public void javaExitEntryRecordsZeroWithoutOpeningTheUpstreamErrorActivity() throws Exception {
        verifyExit(0, false);
    }

    @Test
    public void javaExitEntryPreservesNonzeroCode() throws Exception {
        verifyExit(7, false);
    }

    @Test
    public void javaExitEntryPreservesSignalResult() throws Exception {
        verifyExit(11, true);
    }

    @Test
    public void startupFailureIsAvailableAfterItsProcessEnds() throws Exception {
        attach();

        command(RuntimeProbeService.FAILURE);
        GameRunSnapshot failed = awaitStage(GameRunSnapshot.Stage.FAILED);
        awaitNoGameProcess();

        assertEquals(IOException.class.getName(), failed.failureType);
        assertNull(failed.exitCode);
        assertNull(failed.jvmStartedAt);
        assertNull(failed.jvmElapsedMillis);
        assertTrue(failed.isTerminal());
        assertEquals(failed.failureType, AmethystRuntime.readGameRun(directory).failureType);
        String diagnostic = readText(AmethystRuntime.gameFailureFile(directory));
        assertTrue(diagnostic.contains(IOException.class.getName()));
        assertTrue(diagnostic.contains(RuntimeProbeService.class.getName()));
        assertFalse(diagnostic.contains(RuntimeProbeService.FAILURE_SECRET));
        assertFalse(readText(new File(directory, "state.properties")).contains(RuntimeProbeService.FAILURE_SECRET));
    }

    @Test
    public void crashReportListingDistinguishesAbsentFilesFromAReadFailure() throws Exception {
        assertTrue(AmethystRuntime.gameCrashReports(directory).isEmpty());
        File crashReport = new File(directory, "hs_err_pid123.log");
        Files.write(crashReport.toPath(), "Test crash report".getBytes(StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList(crashReport), AmethystRuntime.gameCrashReports(directory));

        File invalidDirectory = new File(directory, "not-a-directory");
        Files.write(invalidDirectory.toPath(), new byte[] {1});
        try {
            AmethystRuntime.gameCrashReports(invalidDirectory);
            fail("읽기 실패를 hs_err 파일 부재로 바꾸면 안 됩니다");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void lateStopFailureAndFrameDoNotReplaceTheFirstTerminalResult() throws Exception {
        attach();
        command(RuntimeProbeService.JVM_STARTED);
        awaitStage(GameRunSnapshot.Stage.JVM_STARTED);

        command(RuntimeProbeService.EXIT_THEN_LATE_CALLBACKS);
        awaitNoGameProcess();
        GameRunSnapshot finished = AmethystRuntime.readGameRun(directory);

        assertEquals(GameRunSnapshot.Stage.EXITED, finished.stage);
        assertEquals(Integer.valueOf(7), finished.exitCode);
        assertFalse(finished.signal);
        assertNull(finished.failureType);
        assertNull(finished.firstFrameAt);
    }

    @Test
    public void stopRequestIsScopedToTheCurrentRunAndActuallyEndsItsProcess() throws Exception {
        GameRunSnapshot attached = attach();
        command(RuntimeProbeService.JVM_STARTED);
        awaitStage(GameRunSnapshot.Stage.JVM_STARTED);

        AmethystRuntime.stopGame(context, "AnotherTestRun");
        // 다른 실행 ID의 비동기 요청이 현재 실행을 종료하지 않는지 관측한다.
        long deadline = SystemClock.uptimeMillis() + 300;
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals(Integer.valueOf(attached.processId), gameProcessId());
            assertEquals(GameRunSnapshot.Stage.JVM_STARTED, AmethystRuntime.readGameRun(directory).stage);
            SystemClock.sleep(25);
        }

        assertTrue(AmethystRuntime.stopGame(context, runId));
        GameRunSnapshot stopped = awaitStage(GameRunSnapshot.Stage.STOPPED);
        awaitNoGameProcess();

        assertTrue(stopped.isTerminal());
        assertNull(stopped.exitCode);
        assertFalse(stopped.signal);
        assertFalse(AmethystRuntime.stopGame(context, runId));
    }

    private void verifyExit(int exitCode, boolean signal) throws Exception {
        GameRunSnapshot attached = attach();
        command(RuntimeProbeService.JVM_STARTED);
        awaitStage(GameRunSnapshot.Stage.JVM_STARTED);
        Intent exit = probeIntent(RuntimeProbeService.EXIT)
                .putExtra(RuntimeProbeService.EXTRA_EXIT_CODE, exitCode)
                .putExtra(RuntimeProbeService.EXTRA_SIGNAL, signal);
        assertNotNull(context.startService(exit));

        GameRunSnapshot finished = awaitStage(GameRunSnapshot.Stage.EXITED);
        awaitNoGameProcess();

        assertEquals(runId, finished.runId);
        assertEquals(attached.processId, finished.processId);
        assertEquals(Integer.valueOf(exitCode), finished.exitCode);
        assertEquals(signal, finished.signal);
        assertTrue(finished.isTerminal());
        assertNotNull(finished.jvmElapsedMillis);
        assertTrue(finished.jvmElapsedMillis >= 0);
        assertEquals(finished.stage, AmethystRuntime.readGameRun(directory).stage);
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            Intent base = task.getTaskInfo().baseIntent;
            if (base.getComponent() != null) {
                assertNotEquals("net.kdt.pojavlaunch.ExitActivity", base.getComponent().getClassName());
            }
        }
    }

    private GameRunSnapshot attach() throws Exception {
        command(RuntimeProbeService.ATTACH);
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            failOnProbeError();
            GameRunSnapshot snapshot = AmethystRuntime.readGameRun(directory);
            if (snapshot != null && snapshot.processId != 0) {
                assertEquals(runId, snapshot.runId);
                assertNotEquals(Process.myPid(), snapshot.processId);
                assertEquals(Integer.valueOf(snapshot.processId), gameProcessId());
                return snapshot;
            }
            SystemClock.sleep(25);
        }
        throw new AssertionError("별도 게임 프로세스가 실행 상태를 기록하지 않았습니다");
    }

    private GameRunSnapshot awaitStage(GameRunSnapshot.Stage expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            failOnProbeError();
            GameRunSnapshot snapshot = AmethystRuntime.readGameRun(directory);
            if (snapshot != null && snapshot.stage == expected) {
                return snapshot;
            }
            SystemClock.sleep(25);
        }
        throw new AssertionError("실행 상태를 관측하지 못했습니다: " + expected);
    }

    private void awaitNotification() {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (changedRuns.contains(runId)) {
                return;
            }
            SystemClock.sleep(25);
        }
        fail("별도 게임 프로세스의 실행 변경 알림이 없습니다");
    }

    private void awaitNoGameProcess() {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (gameProcessId() == null) {
                return;
            }
            SystemClock.sleep(25);
        }
        fail("테스트 게임 프로세스가 종료되지 않았습니다");
    }

    private Integer gameProcessId() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) {
            return null;
        }
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if ((context.getPackageName() + ":game").equals(process.processName)) {
                return process.pid;
            }
        }
        return null;
    }

    private void failOnProbeError() throws IOException {
        File errorFile = new File(directory, RuntimeProbeService.ERROR_FILE);
        if (errorFile.isFile()) {
            fail(readText(errorFile));
        }
    }

    private String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void command(String operation) {
        assertNotNull(context.startService(probeIntent(operation)));
    }

    private Intent probeIntent(String operation) {
        return new Intent(context, RuntimeProbeService.class)
                .putExtra(RuntimeProbeService.EXTRA_OPERATION, operation)
                .putExtra(RuntimeProbeService.EXTRA_RUN_ID, runId)
                .putExtra(RuntimeProbeService.EXTRA_RUN_DIRECTORY, directory.getAbsolutePath());
    }
}
