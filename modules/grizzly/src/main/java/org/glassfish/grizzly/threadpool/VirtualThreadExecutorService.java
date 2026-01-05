/*
 * Copyright (c) 2025,2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.monitoring.DefaultMonitoringConfig;
import org.glassfish.grizzly.monitoring.MonitoringUtils;

/**
 * @author mazhen
 */
public class VirtualThreadExecutorService extends AbstractExecutorService implements ThreadPoolInfo, Thread.UncaughtExceptionHandler {

    private static final Logger logger = Grizzly.logger(VirtualThreadExecutorService.class);

    private final ExecutorService internalExecutorService;
    private final Semaphore poolSemaphore;
    private final Semaphore queueSemaphore;
    private final AtomicInteger threadsCount = new AtomicInteger();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final ThreadPoolConfig originalConfig;

    /**
     * ThreadPool probes
     */
    protected final DefaultMonitoringConfig<ThreadPoolProbe> monitoringConfig = new DefaultMonitoringConfig<ThreadPoolProbe>(ThreadPoolProbe.class) {

        @Override
        public Object createManagementObject() {
            return createJmxManagementObject();
        }

    };

    public static VirtualThreadExecutorService createInstance() {
        return createInstance(ThreadPoolConfig.defaultConfig().setMaxPoolSize(-1).setPoolName("Grizzly-virt-"));
    }

    public static VirtualThreadExecutorService createInstance(ThreadPoolConfig cfg) {
        Objects.requireNonNull(cfg);
        return new VirtualThreadExecutorService(cfg);
    }

    protected VirtualThreadExecutorService(ThreadPoolConfig config) {
        originalConfig = config;
        if (config.getInitialMonitoringConfig().hasProbes()) {
            monitoringConfig.addProbes(config.getInitialMonitoringConfig().getProbes());
        }

        internalExecutorService = Executors.newThreadPerTaskExecutor(getThreadFactory(config));

        int poolSizeLimit = config.getMaxPoolSize() > 0 ? config.getMaxPoolSize() : Integer.MAX_VALUE;
        int queueLimit = config.getQueueLimit() >= 0 ? config.getQueueLimit() : Integer.MAX_VALUE;
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
        ProbeNotifier.notifyThreadPoolStopped(this);

    }

    @Override
    public List<Runnable> shutdownNow() {
        final List<Runnable> tasks = internalExecutorService.shutdownNow();
        for (Runnable cancelledTask : tasks) {
            onTaskDequeued(cancelledTask);
            onTaskCancelled(cancelledTask);
        }
        ProbeNotifier.notifyThreadPoolStopped(this);
        return tasks;
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
    public void execute(Runnable task) {
        if (!queueSemaphore.tryAcquire()) {
            throw new RejectedExecutionException("Too Many Concurrent Requests");
        }

        internalExecutorService.execute(() -> {
            final Thread currentThread = Thread.currentThread();
            onWorkerStarted(currentThread);
            threadsCount.incrementAndGet();
            try {
                onTaskQueued(task);
                try {
                    poolSemaphore.acquire();
                } finally {
                    onTaskDequeued(task);
                }
                if (poolSemaphore.availablePermits() == 0) {
                    onMaxNumberOfThreadsReached();
                }
                try {
                    task.run();
                    onTaskCompletedEvent(task);
                } finally {
                    poolSemaphore.release();
                }
            } catch (InterruptedException e) {
                currentThread.interrupt();
                onTaskCancelled(task);
            } finally {
                queueSemaphore.release();
                threadsCount.decrementAndGet();
                onWorkerExit(currentThread);
            }
        });
    }

    private void beforeExecute(final AbstractThreadPool.Worker worker, final Thread t, final Runnable r) {
        final ClassLoader initial = getConfig().getInitialClassLoader();
        if (initial != null) {
            t.setContextClassLoader(initial);
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        logger.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_THREADPOOL_UNCAUGHT_EXCEPTION(thread), throwable);
    }

    @Override
    public int getSize() {
        return threadsCount.get();
    }

    @Override
    public ThreadPoolConfig getConfig() {
        return originalConfig;
    }

    @Override
    public DefaultMonitoringConfig<ThreadPoolProbe> getMonitoringConfig() {
        return monitoringConfig;
    }

    @Override
    public int getQueueSize() {
        return 0;
    }

    Object createJmxManagementObject() {
        return MonitoringUtils.loadJmxObject("org.glassfish.grizzly.threadpool.jmx.ThreadPool", this, ThreadPoolInfo.class);
    }

    /**
     * <p>
     * This method will be invoked when a the specified {@link Runnable} has completed execution.
     * </p>
     *
     * @param task the unit of work that has completed processing
     */
    protected void onTaskCompletedEvent(Runnable task) {
        ProbeNotifier.notifyTaskCompleted(this, task);
    }

    /**
     * Method is called by {@link Worker}, when it's starting {@link Worker#run()} method execution, which means, that
     * ThreadPool's thread is getting active and ready to process tasks. This method is called from {@link Worker}'s thread.
     *
     * @param worker
     */
    protected void onWorkerStarted(Thread thread) {
        ProbeNotifier.notifyThreadAllocated(this, thread);
    }

    /**
     * Method is called by {@link Worker}, when it's completing {@link Worker#run()} method execution, which in most cases
     * means, that ThreadPool's thread will be released. This method is called from {@link Worker}'s thread.
     *
     * @param worker
     */
    protected void onWorkerExit(Thread thread) {
        ProbeNotifier.notifyThreadReleased(this, thread);
    }

    /**
     * Method is called by <tt>AbstractThreadPool</tt>, when maximum number of worker threads is reached and task will need
     * to wait in task queue, until one of the threads will be able to process it.
     */
    protected void onMaxNumberOfThreadsReached() {
        ProbeNotifier.notifyMaxNumberOfThreads(this, getConfig().getMaxPoolSize());
    }

    /**
     * Method is called by a thread pool each time new task has been queued to a task queue.
     *
     * @param task
     */
    protected void onTaskQueued(Runnable task) {
        queueSize.incrementAndGet();
        ProbeNotifier.notifyTaskQueued(this, task);
    }

    /**
     * Method is called by a thread pool each time a task has been dequeued from a task queue.
     *
     * @param task
     */
    protected void onTaskDequeued(Runnable task) {
        queueSize.decrementAndGet();
        ProbeNotifier.notifyTaskDequeued(this, task);
    }

    /**
     * Method is called by a thread pool each time a dequeued task has been canceled instead of being processed.
     *
     * @param task
     */
    protected void onTaskCancelled(Runnable task) {
        ProbeNotifier.notifyTaskCancelled(this, task);
    }

}