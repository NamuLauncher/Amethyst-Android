package net.kdt.pojavlaunch.runtime;

import java.util.Objects;

/** 실행 ID에 연결된 관측 결과이며 FIRST_FRAME은 게임 로딩 완료를 뜻하지 않는다. */
public final class GameRunSnapshot {
    public enum Stage {
        STARTING,
        JVM_STARTED,
        FIRST_FRAME,
        EXITED,
        FAILED,
        STOPPED
    }

    public final String runId;
    public final Stage stage;
    public final long startedAt;
    public final long updatedAt;
    public final int processId;
    public final Long jvmStartedAt;
    public final Long firstFrameAt;
    /** JVM 진입부터 단조 시계로 관측한 누적 밀리초이며, 이전 기록과 진입 전에는 null이다. */
    public final Long jvmElapsedMillis;
    /** 프로세스 연결부터 마지막 생존 관측까지의 단조 밀리초. 종료 시에는 늘리지 않으며 이전 기록은 null이다. */
    public final Long processAliveMillis;
    public final Integer exitCode;
    public final boolean signal;
    /** 오류 메시지나 인자 대신 예외 클래스 이름만 전달한다. */
    public final String failureType;

    GameRunSnapshot(
            String runId,
            Stage stage,
            long startedAt,
            long updatedAt,
            int processId,
            Long jvmStartedAt,
            Long firstFrameAt,
            Integer exitCode,
            boolean signal,
            String failureType,
            Long jvmElapsedMillis
    ) {
        this(
                runId,
                stage,
                startedAt,
                updatedAt,
                processId,
                jvmStartedAt,
                firstFrameAt,
                exitCode,
                signal,
                failureType,
                jvmElapsedMillis,
                null
        );
    }

    GameRunSnapshot(
            String runId,
            Stage stage,
            long startedAt,
            long updatedAt,
            int processId,
            Long jvmStartedAt,
            Long firstFrameAt,
            Integer exitCode,
            boolean signal,
            String failureType,
            Long jvmElapsedMillis,
            Long processAliveMillis
    ) {
        validateRunId(runId);
        this.stage = Objects.requireNonNull(stage, "stage");
        if (processId < 0) {
            throw new IllegalArgumentException("게임 프로세스 ID가 올바르지 않습니다");
        }
        if (processAliveMillis != null && (processAliveMillis < 0 || processId == 0)) {
            throw new IllegalArgumentException("생존 경과시간에는 프로세스 ID와 음수가 아닌 값이 필요합니다");
        }
        if (firstFrameAt != null && jvmStartedAt == null) {
            throw new IllegalArgumentException("첫 프레임에는 JVM 시작 기록이 필요합니다");
        }
        if (jvmElapsedMillis != null && (jvmElapsedMillis < 0 || jvmStartedAt == null)) {
            throw new IllegalArgumentException("JVM 경과시간에는 시작 기록과 음수가 아닌 값이 필요합니다");
        }
        if (stage == Stage.STARTING && (jvmStartedAt != null || firstFrameAt != null)) {
            throw new IllegalArgumentException("시작 전 상태에 JVM 관측 기록이 있습니다");
        }
        if (stage == Stage.JVM_STARTED && (jvmStartedAt == null || firstFrameAt != null)) {
            throw new IllegalArgumentException("JVM 시작 상태의 관측 기록이 올바르지 않습니다");
        }
        if (stage == Stage.FIRST_FRAME && firstFrameAt == null) {
            throw new IllegalArgumentException("첫 프레임 상태에 관측 시각이 없습니다");
        }
        if ((stage == Stage.EXITED) != (exitCode != null) || (signal && stage != Stage.EXITED)) {
            throw new IllegalArgumentException("종료 코드와 종료 상태가 일치하지 않습니다");
        }
        if ((stage == Stage.FAILED) != (failureType != null)) {
            throw new IllegalArgumentException("실패 정보와 실패 상태가 일치하지 않습니다");
        }
        if (failureType != null && failureType.isEmpty()) {
            throw new IllegalArgumentException("실패 예외의 클래스 이름이 없습니다");
        }

        this.runId = runId;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.processId = processId;
        this.jvmStartedAt = jvmStartedAt;
        this.firstFrameAt = firstFrameAt;
        this.jvmElapsedMillis = jvmElapsedMillis;
        this.processAliveMillis = processAliveMillis;
        this.exitCode = exitCode;
        this.signal = signal;
        this.failureType = failureType;
    }

    public boolean isTerminal() {
        return stage == Stage.EXITED || stage == Stage.FAILED || stage == Stage.STOPPED;
    }

    static void validateRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("실행 ID는 영문, 숫자, 밑줄, 하이픈으로 1~128자여야 합니다");
        }
    }
}
