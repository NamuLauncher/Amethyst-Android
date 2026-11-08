package net.kdt.pojavlaunch.tasks;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 실패·취소 결과를 전달하기 전에 모든 다운로드의 파일 접근이 끝나기를 기다린다. */
final class DownloadTaskGroup {
    private DownloadTaskGroup() {}

    static void run(
            List<? extends Runnable> tasks,
            AtomicReference<Exception> failure,
            Runnable progress
    ) throws Exception {
        ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(Math.max(1, tasks.size()));
        ThreadPoolExecutor workers = new ThreadPoolExecutor(
                4,
                4,
                500,
                TimeUnit.MILLISECONDS,
                queue
        );
        try {
            for (Runnable task : tasks) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download scheduling cancelled");
                }
                if (failure.get() != null) {
                    throw failure.get();
                }
                workers.execute(() -> {
                    try {
                        task.run();
                    } catch (Throwable error) {
                        Exception exception;
                        if (error instanceof Exception) {
                            exception = (Exception) error;
                        } else {
                            exception = new Exception("Download worker failed", error);
                        }
                        failure.compareAndSet(null, exception);
                    }
                });
            }
            workers.shutdown();
            while (failure.get() == null && !workers.awaitTermination(33, TimeUnit.MILLISECONDS)) {
                progress.run();
            }
            if (failure.get() != null) {
                throw failure.get();
            }
        } finally {
            workers.shutdownNow();
            boolean interrupted = Thread.interrupted();
            while (!workers.isTerminated()) {
                try {
                    workers.awaitTermination(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 취소 신호 자체를 완료로 보지 않고 실제 worker 종료까지 기다린다.
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
