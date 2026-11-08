package net.kdt.pojavlaunch.runtime;

/** 준비 단계별 처리량. 전체 작업을 합친 백분율이 아니다. */
public final class GamePreparationProgress {
    public enum Stage {
        COMPONENTS,
        VERSION_LIST,
        METADATA,
        JAVA_RUNTIME,
        GAME_FILES,
        NATIVE_LIBRARIES
    }

    public enum Unit {
        ITEMS,
        BYTES,
        UNKNOWN
    }

    public final Stage stage;
    public final Unit unit;
    public final long completed;
    /** 전체 크기를 알 수 없는 단계는 -1을 사용한다. */
    public final long total;
    /** false인 단계는 중간에 멈추지 않으며 취소 요청을 거절한다. */
    public final boolean cancellable;
    /** GAME_FILES에서 실제 읽은 파일 응답 본문의 누적 바이트. 미측정 단계는 null이다. */
    public final Long receivedBytes;

    public GamePreparationProgress(Stage stage, Unit unit, long completed, long total) {
        this(stage, unit, completed, total, false);
    }

    public GamePreparationProgress(
            Stage stage,
            Unit unit,
            long completed,
            long total,
            boolean cancellable
    ) {
        this(
                stage,
                unit,
                completed,
                total,
                cancellable,
                null
        );
    }

    public GamePreparationProgress(
            Stage stage,
            Unit unit,
            long completed,
            long total,
            boolean cancellable,
            Long receivedBytes
    ) {
        if (receivedBytes != null && receivedBytes < 0) {
            throw new IllegalArgumentException("수신량은 음수일 수 없습니다");
        }
        this.stage = stage;
        this.unit = unit;
        this.completed = completed;
        this.total = total;
        this.cancellable = cancellable;
        this.receivedBytes = receivedBytes;
    }
}
