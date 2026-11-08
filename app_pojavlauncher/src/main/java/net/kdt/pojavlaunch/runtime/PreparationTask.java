package net.kdt.pojavlaunch.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

/** 취소 요청과 단계 전환을 함께 판정하고 실제 작업 종료 뒤 결과를 전달한다. */
final class PreparationTask implements AmethystRuntime.PreparationRequest {
    interface Action {
        void run() throws Exception;
    }

    private final PreparationCallbacks callbacks;
    private boolean cancellable = true;
    private boolean cancelled;
    private boolean finished;
    private Thread worker;

    PreparationTask(Executor callbackExecutor, AmethystRuntime.PreparationListener listener) {
        callbacks = new PreparationCallbacks(callbackExecutor, listener, this::isCancelled);
    }

    void start(Executor executor, Action action) {
        try {
            executor.execute(() -> run(action));
        } catch (RuntimeException failure) {
            finish(failure);
        }
    }

    private void run(Action action) {
        synchronized (this) {
            worker = Thread.currentThread();
        }
        Throwable failure = null;
        try {
            checkCancelled();
            action.run();
        } catch (Throwable error) {
            failure = error;
        } finally {
            finish(failure);
            // 전역 executor의 다음 요청에 이번 취소의 interrupt를 넘기지 않는다.
            Thread.interrupted();
        }
    }

    @Override
    public synchronized boolean cancel() {
        if (finished || !cancellable) {
            return false;
        }
        cancelled = true;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    synchronized void checkCancelled() {
        if (cancelled) {
            throw new CancellationException("게임 준비가 취소됐습니다");
        }
    }

    private synchronized boolean isCancelled() {
        return cancelled;
    }

    void progress(GamePreparationProgress progress) {
        boolean canCancel = progress.stage == GamePreparationProgress.Stage.VERSION_LIST
                || progress.stage == GamePreparationProgress.Stage.METADATA
                || progress.stage == GamePreparationProgress.Stage.GAME_FILES;
        synchronized (this) {
            checkCancelled();
            if (finished) {
                return;
            }
            cancellable = canCancel;
        }
        callbacks.progress(new GamePreparationProgress(
                progress.stage,
                progress.unit,
                progress.completed,
                progress.total,
                canCancel
        ));
    }

    private void finish(Throwable failure) {
        boolean wasCancelled;
        synchronized (this) {
            if (finished) {
                return;
            }
            finished = true;
            cancellable = false;
            worker = null;
            wasCancelled = cancelled;
        }
        if (wasCancelled) {
            callbacks.cancelled();
        } else if (failure != null) {
            callbacks.failure(failure);
        } else {
            callbacks.ready();
        }
    }
}
