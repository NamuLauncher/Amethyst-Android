package net.kdt.pojavlaunch.runtime;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** 요청별 콜백을 같은 스레드로 전달하고 종료 후 진행률을 차단한다. */
final class PreparationCallbacks {
    private final Executor executor;
    private final AmethystRuntime.PreparationListener listener;
    private final BooleanSupplier cancellationRequested;
    // 콜백 실행기는 순차 실행해야 하며 실제 API에서는 메인 스레드를 사용한다.
    private boolean finished;

    PreparationCallbacks(Executor executor, AmethystRuntime.PreparationListener listener) {
        this(executor, listener, () -> false);
    }

    PreparationCallbacks(
            Executor executor,
            AmethystRuntime.PreparationListener listener,
            BooleanSupplier cancellationRequested
    ) {
        this.executor = executor;
        this.listener = listener;
        this.cancellationRequested = cancellationRequested;
    }

    void progress(GamePreparationProgress progress) {
        executor.execute(() -> {
            if (!finished && !cancellationRequested.getAsBoolean()) {
                listener.onProgress(progress);
            }
        });
    }

    void ready() {
        executor.execute(() -> {
            if (!finished) {
                finished = true;
                listener.onReady();
            }
        });
    }

    void failure(Throwable failure) {
        executor.execute(() -> {
            if (!finished) {
                finished = true;
                listener.onFailure(failure);
            }
        });
    }

    void cancelled() {
        executor.execute(() -> {
            if (!finished) {
                finished = true;
                listener.onCancelled();
            }
        });
    }
}
