package net.kdt.pojavlaunch.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.ExitActivity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 실제 게임 대신 별도 프로세스에서 공개 경계의 콜백을 발생시킨다. */
public final class RuntimeProbeService extends Service {
    static final String EXTRA_OPERATION = "operation";
    static final String EXTRA_RUN_DIRECTORY = "runDirectory";
    static final String EXTRA_RUN_ID = "runId";
    static final String EXTRA_EXIT_CODE = "exitCode";
    static final String EXTRA_SIGNAL = "signal";
    static final String ATTACH = "attach";
    static final String JVM_STARTED = "jvmStarted";
    static final String FIRST_FRAME = "firstFrame";
    static final String EXIT = "exit";
    static final String FAILURE = "failure";
    static final String EXIT_THEN_LATE_CALLBACKS = "exitThenLateCallbacks";
    static final String QUIT = "quit";
    static final String ERROR_FILE = "probe-error.txt";
    static final String FAILURE_SECRET = "Test-secret-do-not-record";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        File directory = new File(intent.getStringExtra(EXTRA_RUN_DIRECTORY));
        try {
            String operation = intent.getStringExtra(EXTRA_OPERATION);
            if (QUIT.equals(operation)) {
                stopSelf();
                Process.killProcess(Process.myPid());
            } else if (ATTACH.equals(operation)) {
                RuntimeGameSession.attach(this, intent.getStringExtra(EXTRA_RUN_ID), directory);
            } else if (JVM_STARTED.equals(operation)) {
                RuntimeGameSession.onJvmStarted();
            } else if (FIRST_FRAME.equals(operation)) {
                RuntimeGameSession.onFirstFrame();
            } else if (EXIT.equals(operation)) {
                // 네이티브 종료 훅이 호출하는 Java 진입점이며 JVM 자체를 실행하지는 않는다.
                ExitActivity.showExitMessage(
                        this,
                        intent.getIntExtra(EXTRA_EXIT_CODE, 0),
                        intent.getBooleanExtra(EXTRA_SIGNAL, false)
                );
                stopSelf();
                Process.killProcess(Process.myPid());
            } else if (FAILURE.equals(operation)) {
                RuntimeGameSession.onFailure(new IOException("Test startup failure: " + FAILURE_SECRET));
                stopSelf();
                Process.killProcess(Process.myPid());
            } else if (EXIT_THEN_LATE_CALLBACKS.equals(operation)) {
                RuntimeGameSession.onExit(7, false);
                RuntimeGameSession.onUserStop();
                RuntimeGameSession.onFailure(new IllegalStateException("Test late failure"));
                RuntimeGameSession.onFirstFrame();
                RuntimeGameSession.onJvmStarted();
                stopSelf();
                Process.killProcess(Process.myPid());
            } else {
                throw new IllegalArgumentException("Unknown test operation");
            }
        } catch (Throwable failure) {
            // 테스트 서비스의 초기화 실패를 무의미한 timeout 대신 계측 쪽에서 확인한다.
            try {
                String detail = failure.getClass().getName() + ": " + failure.getMessage();
                Files.write(
                        new File(directory, ERROR_FILE).toPath(),
                        detail.getBytes(StandardCharsets.UTF_8)
                );
            } catch (IOException ignored) {
                // 상태 디렉터리까지 쓸 수 없는 경우에는 원래 관측 timeout이 실패를 보고한다.
            }
            stopSelf();
            Process.killProcess(Process.myPid());
        }
        return START_NOT_STICKY;
    }
}
