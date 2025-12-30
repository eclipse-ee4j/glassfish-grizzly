package org.glassfish.grizzly;

import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
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
}
