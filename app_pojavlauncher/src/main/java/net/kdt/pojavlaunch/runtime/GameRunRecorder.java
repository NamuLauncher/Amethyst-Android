package net.kdt.pojavlaunch.runtime;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 같은 게임 프로세스의 콜백을 직렬화하고 처음 관측한 종료 결과를 유지한다. */
final class GameRunRecorder {
    private final GameRunStore store;
    private final LongSupplier elapsedClock;
    private GameRunSnapshot current;
    private Long jvmStartTick;
    private Long processStartTick;

    GameRunRecorder(GameRunStore store) throws IOException {
        this(store, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    GameRunRecorder(GameRunStore store, LongSupplier elapsedClock) throws IOException {
        this.store = store;
        this.elapsedClock = elapsedClock;
        current = store.read();
        if (current == null) {
            throw new IOException("실행 상태가 초기화되지 않았습니다");
        }
    }

    synchronized GameRunSnapshot snapshot() {
        return current;
    }

    synchronized boolean attachProcess(int pid, long now) throws IOException {
        if (current.isTerminal() || current.processId == pid) {
            return false;
        }
        if (pid <= 0) {
            throw new IllegalArgumentException("게임 프로세스 ID가 올바르지 않습니다");
        }
        if (current.processId != 0) {
            throw new IOException("기존 실행 기록을 다른 게임 프로세스에 재사용할 수 없습니다");
        }
        processStartTick = elapsedClock.getAsLong();
        return publish(new GameRunSnapshot(
                current.runId,
                current.stage,
                current.startedAt,
                now,
                pid,
                current.jvmStartedAt,
                current.firstFrameAt,
                null,
                false,
                null,
                current.jvmElapsedMillis,
                0L
        ));
    }

    synchronized boolean jvmStarted(long now) throws IOException {
        if (current.stage != GameRunSnapshot.Stage.STARTING) {
            return false;
        }
        jvmStartTick = elapsedClock.getAsLong();
        return publish(new GameRunSnapshot(
                current.runId,
                GameRunSnapshot.Stage.JVM_STARTED,
                current.startedAt,
                now,
                current.processId,
                now,
                null,
                null,
                false,
                null,
                0L,
                processAliveMillis()
        ));
    }

    synchronized boolean firstFrame(long now) throws IOException {
        if (current.stage != GameRunSnapshot.Stage.JVM_STARTED) {
            return false;
        }
        return publish(new GameRunSnapshot(
                current.runId,
                GameRunSnapshot.Stage.FIRST_FRAME,
                current.startedAt,
                now,
                current.processId,
                current.jvmStartedAt,
                now,
                null,
                false,
                null,
                elapsedMillis(elapsedClock.getAsLong()),
                processAliveMillis()
        ));
    }

    synchronized boolean checkpoint(long now) throws IOException {
        if ((processStartTick == null && jvmStartTick == null) || current.isTerminal()) {
            return false;
        }
        return publish(new GameRunSnapshot(
                current.runId,
                current.stage,
                current.startedAt,
                now,
                current.processId,
                current.jvmStartedAt,
                current.firstFrameAt,
                null,
                false,
                null,
                elapsedMillis(elapsedClock.getAsLong()),
                processAliveMillis()
        ));
    }

    synchronized boolean exited(int code, boolean signal, long now) throws IOException {
        return finish(
                GameRunSnapshot.Stage.EXITED,
                code,
                signal,
                null,
                now,
                elapsedClock.getAsLong()
        );
    }

    synchronized boolean failed(String failureType, long now) throws IOException {
        return failed(failureType, now, elapsedClock.getAsLong());
    }

    synchronized boolean failed(String failureType, long now, long elapsedAtFailure) throws IOException {
        return finish(
                GameRunSnapshot.Stage.FAILED,
                null,
                false,
                failureType,
                now,
                elapsedAtFailure
        );
    }

    synchronized boolean stopped(long now) throws IOException {
        return finish(
                GameRunSnapshot.Stage.STOPPED,
                null,
                false,
                null,
                now,
                elapsedClock.getAsLong()
        );
    }

    private boolean finish(
            GameRunSnapshot.Stage stage,
            Integer exitCode,
            boolean signal,
            String failureType,
            long now,
            long elapsedAtEnd
    ) throws IOException {
        if (current.isTerminal()) {
            return false;
        }
        return publish(new GameRunSnapshot(
                current.runId,
                stage,
                current.startedAt,
                now,
                current.processId,
                current.jvmStartedAt,
                current.firstFrameAt,
                exitCode,
                signal,
                failureType,
                elapsedMillis(elapsedAtEnd),
                current.processAliveMillis
        ));
    }

    private Long processAliveMillis() {
        if (processStartTick == null) {
            // 다시 읽은 기록에서는 프로세스 시작 시각을 추측하지 않는다.
            return current.processAliveMillis;
        }
        return Math.max(0, elapsedClock.getAsLong() - processStartTick);
    }

    private Long elapsedMillis(long now) {
        if (jvmStartTick == null) {
            // 읽기 전용으로 다시 연 기록은 시작 tick을 추측하지 않는다.
            return current.jvmElapsedMillis;
        }
        return Math.max(0, now - jvmStartTick);
    }

    private boolean publish(GameRunSnapshot next) throws IOException {
        // 저장 실패 뒤의 정리 콜백이 실제 종료 원인을 STOPPED 등으로 덮어쓰지 않게 한다.
        current = next;
        store.write(next);
        return true;
    }
}
