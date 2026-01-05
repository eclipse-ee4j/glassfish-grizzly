/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.threadpool.GrizzlyExecutorService;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.threadpool.ThreadPoolInfo;
import org.glassfish.grizzly.threadpool.ThreadPoolProbe;
import org.glassfish.grizzly.utils.DelayedExecutor;

/**
 *
 * @author gustav trede
 */
public class GrizzlyExecutorServiceTest extends GrizzlyTestCase {

    public GrizzlyExecutorServiceTest() {
    }

    public void testCreateInstance() throws Exception {
        int threads = 100;
        ThreadPoolConfig cfg = ThreadPoolConfig.defaultConfig().setPoolName("test").setCorePoolSize(-1).setMaxPoolSize(threads).setQueue(null).setQueueLimit(-1)
                .setKeepAliveTime(-1, TimeUnit.MILLISECONDS).setPriority(Thread.NORM_PRIORITY).setTransactionTimeout(-1, TimeUnit.MILLISECONDS);

        GrizzlyExecutorService r = GrizzlyExecutorService.createInstance(cfg);
        final int tasks = 2000000;
        doTest(r, tasks);

        final ThreadPoolConfig config1 = r.getConfiguration();
        assertTrue(config1.getMaxPoolSize() == threads);
        assertTrue(config1.getQueueLimit() == cfg.getQueueLimit());
        assertTrue(config1.getQueue().getClass().getSimpleName().contains("LinkedTransferQueue"));

        doTest(r.reconfigure(r.getConfiguration().setQueueLimit(tasks)), tasks);
        final ThreadPoolConfig config2 = r.getConfiguration();
        assertTrue(config2.getQueueLimit() == tasks);

        int coresize = r.getConfiguration().getMaxPoolSize() + 1;
        doTest(r.reconfigure(r.getConfiguration().setQueue(new LinkedList<Runnable>()).setCorePoolSize(coresize).setKeepAliveTime(1, TimeUnit.MILLISECONDS)
                .setMaxPoolSize(threads += 50)), tasks);
        final ThreadPoolConfig config3 = r.getConfiguration();

        assertTrue(config3.getQueue().getClass().getSimpleName().contains("LinkedList"));
        assertEquals(config3.getPoolName(), cfg.getPoolName());
        assertTrue(config3.getQueueLimit() == tasks);
        assertTrue(config3.getCorePoolSize() == coresize);
        assertTrue(config3.getMaxPoolSize() == threads);
        r.shutdownNow();
        /*
         * long a = r.getCompletedTaskCount(); assertTrue(a+"!="+tasks,a == tasks);
         */
    }

    public void testTransactionTimeout() throws Exception {
        final ExecutorService threadPool = Executors.newSingleThreadExecutor();

        try {
            final DelayedExecutor delayedExecutor = new DelayedExecutor(threadPool);

            final int tasksNum = 10;
            final long transactionTimeoutMillis = 5000;

            final CountDownLatch cdl = new CountDownLatch(tasksNum);
            final ThreadPoolConfig tpc = ThreadPoolConfig.defaultConfig().copy()
                    .setTransactionTimeout(delayedExecutor, transactionTimeoutMillis, TimeUnit.MILLISECONDS).setCorePoolSize(tasksNum / 2)
                    .setMaxPoolSize(tasksNum / 2);

            final GrizzlyExecutorService ges = GrizzlyExecutorService.createInstance(tpc);

            for (int i = 0; i < tasksNum; i++) {
                ges.execute(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            Thread.sleep(transactionTimeoutMillis);
                        } catch (InterruptedException e) {
                            cdl.countDown();
                        }
                    }
                });
            }

            cdl.await(transactionTimeoutMillis * 3 / 2, TimeUnit.MILLISECONDS);
        } finally {
            threadPool.shutdownNow();
        }
    }

    public void testAwaitTermination() throws Exception {
        int threads = 100;
        ThreadPoolConfig cfg = ThreadPoolConfig.defaultConfig().setPoolName("test").setCorePoolSize(-1).setMaxPoolSize(threads).setQueue(null).setQueueLimit(-1)
                .setKeepAliveTime(-1, TimeUnit.MILLISECONDS).setPriority(Thread.NORM_PRIORITY).setTransactionTimeout(-1, TimeUnit.MILLISECONDS);

        GrizzlyExecutorService r = GrizzlyExecutorService.createInstance(cfg);
        final int tasks = 2000;
        runTasks(r, tasks);
        r.shutdown();
        assertTrue(r.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(r.isTerminated());

        r = GrizzlyExecutorService.createInstance(cfg.setQueueLimit(tasks));
        runTasks(r, tasks);
        r.shutdown();
        assertTrue(r.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(r.isTerminated());

        int coresize = cfg.getMaxPoolSize() + 1;
        r = GrizzlyExecutorService.createInstance(cfg.setQueueLimit(tasks).setQueue(new LinkedList<Runnable>()).setCorePoolSize(coresize)
                .setKeepAliveTime(1, TimeUnit.MILLISECONDS).setMaxPoolSize(threads += 50));
        doTest(r, tasks);
        runTasks(r, tasks);
        r.shutdown();
        assertTrue(r.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(r.isTerminated());
    }

    public void testMonitoringProbesCopying() {
        final ThreadPoolProbe probe = new ThreadPoolProbe.Adapter();

        final ThreadPoolConfig tpc1 = ThreadPoolConfig.defaultConfig().copy();
        tpc1.getInitialMonitoringConfig().addProbes(probe);

        final ThreadPoolConfig tpc2 = tpc1.copy();

        assertFalse(tpc1.getInitialMonitoringConfig().getProbes().length == 0);
        assertFalse(tpc2.getInitialMonitoringConfig().getProbes().length == 0);

        tpc1.getInitialMonitoringConfig().removeProbes(probe);

        assertTrue(tpc1.getInitialMonitoringConfig().getProbes().length == 0);
        assertFalse(tpc2.getInitialMonitoringConfig().getProbes().length == 0);
    }

    public void testThreadPoolConfig() throws Exception {
        ThreadPoolConfig defaultThreadPool = ThreadPoolConfig.defaultConfig();
        assertNotNull(defaultThreadPool);
        assertNotNull(defaultThreadPool.toString());
    }

    private void doTest(GrizzlyExecutorService r, int tasks) throws Exception {
        final CountDownLatch cl = new CountDownLatch(tasks);
        while (tasks-- > 0) {
            r.execute(new Runnable() {
                @Override
                public void run() {
                    cl.countDown();
                }
            });
        }
        assertTrue("latch timed out", cl.await(30, TimeUnit.SECONDS));
    }

    private void runTasks(GrizzlyExecutorService r, int tasks) throws Exception {
        while (tasks-- > 0) {
            r.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(50);
                    } catch (Exception ignore) {
                    }
                }
            });
        }
    }

    public void testProbeNotifications() throws Exception {
        final CountDownLatch taskQueuedLatch = new CountDownLatch(1);
        final CountDownLatch taskDequeuedLatch = new CountDownLatch(1);
        final CountDownLatch taskCompletedLatch = new CountDownLatch(1);
        final CountDownLatch threadAllocatedLatch = new CountDownLatch(1);

        ThreadPoolProbe probe = new ThreadPoolProbe.Adapter() {
            @Override
            public void onTaskQueueEvent(ThreadPoolInfo threadPool, Runnable task) {
                taskQueuedLatch.countDown();
            }

            @Override
            public void onTaskDequeueEvent(ThreadPoolInfo threadPool, Runnable task) {
                taskDequeuedLatch.countDown();
            }

            @Override
            public void onTaskCompleteEvent(ThreadPoolInfo threadPool, Runnable task) {
                taskCompletedLatch.countDown();
            }

            @Override
            public void onThreadAllocateEvent(ThreadPoolInfo threadPool, Thread thread) {
                threadAllocatedLatch.countDown();
            }
        };

        ThreadPoolConfig config = ThreadPoolConfig.defaultConfig()
                .setMaxPoolSize(2)
                .setCorePoolSize(1);
        config.getInitialMonitoringConfig().addProbes(probe);

        GrizzlyExecutorService executor = GrizzlyExecutorService.createInstance(config);

        CountDownLatch taskLatch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskLatch.countDown();
        });

        assertTrue("Task should complete", taskLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Task queued probe should be called", taskQueuedLatch.await(1, TimeUnit.SECONDS));
        assertTrue("Task dequeued probe should be called", taskDequeuedLatch.await(1, TimeUnit.SECONDS));
        assertTrue("Task completed probe should be called", taskCompletedLatch.await(1, TimeUnit.SECONDS));
        assertTrue("Thread allocated probe should be called", threadAllocatedLatch.await(1, TimeUnit.SECONDS));

        executor.shutdown();
    }

}
