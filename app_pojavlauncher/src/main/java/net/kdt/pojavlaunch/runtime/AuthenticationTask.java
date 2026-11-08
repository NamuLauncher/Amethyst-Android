package net.kdt.pojavlaunch.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

/** 취소와 저장 시작을 직렬화하고 실제 인증 작업이 끝난 뒤 한 번만 결과를 전달한다. */
final class AuthenticationTask implements AmethystRuntime.AuthenticationRequest {
    interface Action {
        void run() throws Exception;
    }

    private final Executor callbackExecutor;
    private final AmethystRuntime.AuthenticationListener listener;
    private boolean started;
    private boolean cancelled;
    private boolean committing;
    private boolean finished;
    private Thread worker;

    AuthenticationTask(Executor callbackExecutor, AmethystRuntime.AuthenticationListener listener) {
        this.callbackExecutor = callbackExecutor;
        this.listener = listener;
    }

    void start(Executor workerExecutor, Action action) {
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("이미 시작한 인증 요청입니다");
            }
            started = true;
        }
        try {
            workerExecutor.execute(() -> run(action));
        } catch (RuntimeException failure) {
            finish(failure);
        }
    }

    private void run(Action action) {
        synchronized (this) {
            if (finished) {
                return;
            }
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
        }
    }

    void commit(Action action) throws Exception {
        synchronized (this) {
            checkCancelled();
            if (finished || committing || worker != Thread.currentThread()) {
                throw new IllegalStateException("인증 작업의 저장 단계는 한 번만 시작할 수 있습니다");
            }
            // 저장이 먼저 시작됐으면 취소 성공을 알리지 않는다. 파일·선택 변경이 끝날 때까지 기다린다.
            committing = true;
        }
        action.run();
    }

    @Override
    public synchronized boolean cancel() {
        if (committing || finished) {
            return false;
        }
        cancelled = true;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    private synchronized void checkCancelled() {
        if (cancelled) {
            throw new CancellationException("계정 인증이 취소됐습니다");
        }
    }

    private void finish(Throwable failure) {
        boolean wasCancelled;
        synchronized (this) {
            if (finished) {
                return;
            }
            wasCancelled = cancelled || failure instanceof CancellationException;
            finished = true;
            // 완료 판정과 함께 정리하여 마지막 cancel이 다음 executor 작업에 interrupt를 남기지 않는다.
            if (worker == Thread.currentThread()) {
                Thread.interrupted();
            }
            worker = null;
        }

        if (wasCancelled) {
            callbackExecutor.execute(listener::onCancelled);
        } else if (failure != null) {
            AccountAuthenticationException safeFailure = AccountAuthenticationException.from(failure);
            callbackExecutor.execute(() -> listener.onFailure(safeFailure));
        } else {
            callbackExecutor.execute(listener::onReady);
        }
    }
}
