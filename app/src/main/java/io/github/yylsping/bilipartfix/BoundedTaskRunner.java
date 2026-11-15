package io.github.yylsping.bilipartfix;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Enforces a caller deadline even when a host transport ignores its own timeout option. */
final class BoundedTaskRunner {
    private static final ThreadPoolExecutor NETWORK = create();

    private BoundedTaskRunner() {}

    private static ThreadPoolExecutor create() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), runnable -> {
                    Thread thread = new Thread(runnable, "BiliPartFix-trial-network");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    static <T> T run(Callable<T> callable, long timeoutMillis) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        NETWORK.execute(task);
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (!task.isDone()) task.cancel(true);
            NETWORK.remove(task);
        }
    }
}
