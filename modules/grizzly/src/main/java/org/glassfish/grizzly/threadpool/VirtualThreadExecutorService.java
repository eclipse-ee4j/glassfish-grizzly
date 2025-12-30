package org.glassfish.grizzly.threadpool;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.localization.LogMessages;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author mazhen
 */
public class VirtualThreadExecutorService extends AbstractExecutorService implements Thread.UncaughtExceptionHandler {

    private static final Logger logger = Grizzly.logger(VirtualThreadExecutorService.class);

    private final ExecutorService internalExecutorService;
    private final Semaphore poolSemaphore;
    private final Semaphore queueSemaphore;

    public static VirtualThreadExecutorService createInstance() {
        return createInstance(ThreadPoolConfig.defaultConfig().setMaxPoolSize(-1).setPoolName("Grizzly-virt-"));
    }

    public static VirtualThreadExecutorService createInstance(ThreadPoolConfig cfg) {
        Objects.requireNonNull(cfg);
        return new VirtualThreadExecutorService(cfg);
    }

    protected VirtualThreadExecutorService(ThreadPoolConfig cfg) {
        internalExecutorService = Executors.newThreadPerTaskExecutor(getThreadFactory(cfg));

        int poolSizeLimit = cfg.getMaxPoolSize() > 0 ? cfg.getMaxPoolSize() : Integer.MAX_VALUE;
        int queueLimit = cfg.getQueueLimit() >= 0 ? cfg.getQueueLimit() : Integer.MAX_VALUE;
        // Check for integer overflow
        long totalLimit = (long) poolSizeLimit + (long) queueLimit;
        if (totalLimit > Integer.MAX_VALUE) {
            // Handle the overflow case
            queueSemaphore = new Semaphore(Integer.MAX_VALUE, true);
        } else {
            queueSemaphore = new Semaphore((int) totalLimit, true);
        }
        poolSemaphore = new Semaphore(poolSizeLimit, true);
    }

    private ThreadFactory getThreadFactory(ThreadPoolConfig threadPoolConfig) {

        var prefix = threadPoolConfig.getPoolName() + "-";

        // virtual threads factory
        final ThreadFactory factory = Thread.ofVirtual()
                .name(prefix, 0L)
                .uncaughtExceptionHandler(this)
                .factory();

        return r -> {
            Thread thread = factory.newThread(r);
            final ClassLoader initial = threadPoolConfig.getInitialClassLoader();
            if (initial != null) {
                thread.setContextClassLoader(initial);
            }
            return thread;
        };
    }

    @Override
    public void shutdown() {
        internalExecutorService.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return internalExecutorService.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return internalExecutorService.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return internalExecutorService.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return internalExecutorService.awaitTermination(timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        if (!queueSemaphore.tryAcquire()) {
            throw new RejectedExecutionException("Too Many Concurrent Requests");
        }

        internalExecutorService.execute(() -> {
            try {
                poolSemaphore.acquire();
                try {
                    command.run();
                } finally {
                    poolSemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                queueSemaphore.release();
            }
        });
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        logger.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_THREADPOOL_UNCAUGHT_EXCEPTION(thread), throwable);
    }
}
