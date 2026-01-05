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
package org.glassfish.grizzly;

import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.threadpool.ThreadPoolInfo;
import org.glassfish.grizzly.threadpool.ThreadPoolProbe;
import org.glassfish.grizzly.threadpool.VirtualThreadExecutorService;
import org.junit.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class VirtualThreadExecutorServiceTest extends GrizzlyTestCase {

    public void testCreateInstance() throws Exception {

        VirtualThreadExecutorService r = VirtualThreadExecutorService.createInstance();
        final int tasks = 2000000;
        doTest(r, tasks);
    }

    public void testAwaitTermination() throws Exception {
        VirtualThreadExecutorService r = VirtualThreadExecutorService.createInstance();
        final int tasks = 2000;
        doTest(r, tasks);
        r.shutdown();
        assertTrue(r.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(r.isTerminated());
    }

    public void testQueueLimit() throws Exception {
        int maxPoolSize = 20;
        int queueLimit = 10;
        int queue = maxPoolSize + queueLimit;
        ThreadPoolConfig config = ThreadPoolConfig.defaultConfig()
                .setMaxPoolSize(maxPoolSize)
                .setQueueLimit(queueLimit);
        VirtualThreadExecutorService r = VirtualThreadExecutorService.createInstance(config);

        CyclicBarrier start = new CyclicBarrier(maxPoolSize + 1);
        CyclicBarrier hold = new CyclicBarrier(maxPoolSize + 1);
        AtomicInteger result = new AtomicInteger();
        for (int i = 0; i < maxPoolSize; i++) {
            int taskId = i;
            r.execute(() -> {
                try {
                    System.out.println("task " + taskId + " is running");
                    start.await();
                    hold.await();
                    result.getAndIncrement();
                    System.out.println("task " + taskId + " is completed");
                } catch (Exception e) {
                }
            });
        }
        start.await();
        for (int i = maxPoolSize; i < queue; i++) {
            int taskId = i;
            r.execute(() -> {
                try {
                    result.getAndIncrement();
                    System.out.println("task " + taskId + " is completed");
                } catch (Exception e) {
                }
            });
        }
        // Too Many Concurrent Requests
        Assert.assertThrows(RejectedExecutionException.class, () -> r.execute(() -> System.out.println("cannot be executed")));
        hold.await();
        while (true) {
            if (result.intValue() == queue) {
                System.out.println("All tasks have been completed.");
                break;
            }
        }
        // The executor can accept new tasks
        doTest(r, queue);
    }

    private void doTest(VirtualThreadExecutorService r, int tasks) throws Exception {
        final CountDownLatch cl = new CountDownLatch(tasks);
        while (tasks-- > 0) {
            r.execute(() -> cl.countDown());
        }
        assertTrue("latch timed out", cl.await(30, TimeUnit.SECONDS));
    }

    public void testProbeNotifications() throws Exception {
        final CountDownLatch taskQueuedLatch = new CountDownLatch(1);
        final CountDownLatch taskDequeuedLatch = new CountDownLatch(1);
        final CountDownLatch taskCompletedLatch = new CountDownLatch(1);
        final CountDownLatch threadAllocatedLatch = new CountDownLatch(1);
        final CountDownLatch threadReleasedLatch = new CountDownLatch(1);

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

            @Override
            public void onThreadReleaseEvent(ThreadPoolInfo threadPool, Thread thread) {
                threadReleasedLatch.countDown();
            }
        };

        ThreadPoolConfig config = ThreadPoolConfig.defaultConfig();
        config.getInitialMonitoringConfig().addProbes(probe);

        VirtualThreadExecutorService executor = VirtualThreadExecutorService.createInstance(config);

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
        assertTrue("Thread released probe should be called", threadReleasedLatch.await(1, TimeUnit.SECONDS));

        executor.shutdown();
    }
}
