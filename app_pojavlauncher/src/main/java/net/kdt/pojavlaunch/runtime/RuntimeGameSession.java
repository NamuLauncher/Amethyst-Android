package net.kdt.pojavlaunch.runtime;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Process;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.services.GameService;

/** 게임 프로세스에서만 사용하는 실행 기록 연결부. 호스트는 AmethystRuntime을 사용한다. */
public final class RuntimeGameSession {
    static final String EXTRA_RUN_DIRECTORY = "net.kdt.pojavlaunch.runtime.RUN_DIRECTORY";
    static final String ACTION_STOP_GAME = "net.kdt.pojavlaunch.runtime.STOP_GAME";
    private static Context application;
    private static GameRunStore store;
    private static GameRunRecorder recorder;
    private static boolean loggingStarted;
    private static boolean hostRequested;

    private RuntimeGameSession() {}

    public static void initialize(Activity activity) throws IOException {
        Intent intent = activity.getIntent();
        String runId = intent.getStringExtra(AmethystRuntime.EXTRA_RUN_ID);
        String directory = intent.getStringExtra(EXTRA_RUN_DIRECTORY);
        if (runId == null && directory == null) {
            return;
        }
        hostRequested = true;
        if (runId == null || directory == null) {
            throw new IOException("실행 기록 정보가 불완전합니다");
        }
        attach(activity, runId, new File(directory));
    }

    static synchronized void attach(Context context, String runId, File directory) throws IOException {
        File resolved = AmethystRuntime.privateRunDirectory(context, directory);
        if (recorder != null) {
            if (recorder.snapshot().runId.equals(runId) && store.logFile().getParentFile().equals(resolved)) {
                return;
            }
            throw new IOException("게임 프로세스에 다른 실행 기록이 연결되어 있습니다");
        }
        GameRunStore requestedStore = new GameRunStore(resolved);
        GameRunSnapshot requested = requestedStore.read();
        if (requested == null || !requested.runId.equals(runId) || requested.isTerminal() || requested.processId != 0) {
            throw new IOException("연결할 실행 기록이 없거나 이미 종료됐습니다");
        }
        application = context.getApplicationContext();
        store = requestedStore;
        recorder = new GameRunRecorder(store);
        ContextCompat.registerReceiver(
                application,
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        if (ACTION_STOP_GAME.equals(intent.getAction()) &&
                                runId.equals(intent.getStringExtra(AmethystRuntime.EXTRA_RUN_ID))) {
                            onUserStop();
                            Process.killProcess(Process.myPid());
                        }
                    }
                },
                new IntentFilter(ACTION_STOP_GAME),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            onFailure(failure);
            Process.killProcess(Process.myPid());
            System.exit(1);
        });
        recorder.attachProcess(Process.myPid(), System.currentTimeMillis());
        notifyHost();
    }

    public static synchronized boolean isActive() {
        return recorder != null;
    }

    public static synchronized void beginLogging(File fallbackLog) throws IOException {
        if (recorder != null && loggingStarted) {
            return;
        }
        File log = recorder == null ? fallbackLog : store.logFile();
        if (!log.isFile() && !log.createNewFile()) {
            throw new IOException("게임 로그 파일을 만들지 못했습니다");
        }
        Logger.begin(log.getAbsolutePath());
        if (recorder != null) {
            loggingStarted = true;
        }
    }

    public static synchronized String errorFileArgument() {
        if (recorder == null) {
            return null;
        }
        return "-XX:ErrorFile=" + store.errorFilePattern().getAbsolutePath();
    }

    public static synchronized void onJvmStarted() {
        update(() -> recorder.jvmStarted(System.currentTimeMillis()));
    }

    public static synchronized void onFirstFrame() {
        update(() -> recorder.firstFrame(System.currentTimeMillis()));
    }

    public static synchronized boolean onExit(int code, boolean signal) {
        return finish(() -> recorder.exited(code, signal, System.currentTimeMillis()));
    }

    public static synchronized boolean onFailure(Throwable failure) {
        // 예외 메시지에는 계정 토큰이나 JVM 인자가 들어갈 수 있어 타입만 기록한다.
        if (recorder != null && !recorder.snapshot().isTerminal()) {
            writeFailureFrames(failure);
        }
        return finish(() -> recorder.failed(failure.getClass().getName(), System.currentTimeMillis())) || hostRequested;
    }

    private static void writeFailureFrames(Throwable failure) {
        try (BufferedWriter output = Files.newBufferedWriter(store.failureFile().toPath(), StandardCharsets.UTF_8)) {
            Throwable current = failure;
            // 순환 원인 체인이나 비정상적으로 깊은 예외도 유한한 진단 파일로 남긴다.
            for (int depth = 0; current != null && depth < 8; depth++) {
                output.write(current.getClass().getName());
                output.newLine();
                for (StackTraceElement frame : current.getStackTrace()) {
                    output.write("    at " + frame);
                    output.newLine();
                }
                current = current.getCause();
            }
        } catch (IOException failureWritingLog) {
            Log.e("AmethystRuntime", "게임 오류 진단 파일을 저장하지 못했습니다");
        }
    }

    public static synchronized void onUserStop() {
        finish(() -> recorder.stopped(System.currentTimeMillis()));
    }

    private static boolean finish(Update action) {
        boolean active = update(action);
        if (active) {
            // onStartCommand 반환 전에 종료되더라도 서비스가 실행을 되살리지 않도록 한다.
            application.stopService(new Intent(application, GameService.class));
        }
        return active;
    }

    private static boolean update(Update action) {
        if (recorder == null) {
            return false;
        }
        try {
            if (action.apply()) {
                notifyHost();
            }
        } catch (IOException failure) {
            // 기록 실패를 게임 성공으로 합성하지 않는다. 호스트는 파일 오류/결과 부재를 구분한다.
            Log.e("AmethystRuntime", "게임 실행 기록을 저장하지 못했습니다");
        }
        return true;
    }

    private static void notifyHost() {
        application.sendBroadcast(new Intent(AmethystRuntime.ACTION_GAME_RUN_CHANGED)
                .setPackage(application.getPackageName())
                .putExtra(AmethystRuntime.EXTRA_RUN_ID, recorder.snapshot().runId));
    }

    private interface Update {
        boolean apply() throws IOException;
    }
}
