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
        int poolSize = 10;
        ThreadPoolConfig config = ThreadPoolConfig.defaultConfig().setMaxPoolSize(poolSize);
        VirtualThreadExecutorService r = VirtualThreadExecutorService.createInstance(config);

        CyclicBarrier start = new CyclicBarrier(poolSize + 1);
        CyclicBarrier hold = new CyclicBarrier(poolSize + 1);
        AtomicInteger result = new AtomicInteger();
        for (int i = 0; i < poolSize; i++) {
            int taskId = i;
            r.execute(() -> {
                try {
                    System.out.println("task " + taskId + " is running");
                    start.await();
                    hold.await();
                    result.getAndIncrement();
                } catch (Exception e) {
                }
            });
        }
        start.await();
        // Too Many Concurrent Requests
        Assert.assertThrows(RejectedExecutionException.class, () -> r.execute(() -> System.out.println("cannot be executed")));
        hold.await();
        while (true) {
            if (result.intValue() == poolSize) {
                System.out.println("All tasks have been completed.");
                break;
            }
        }
        // The executor can accept new tasks
        doTest(r, poolSize);
    }

    private void doTest(VirtualThreadExecutorService r, int tasks) throws Exception {
        final CountDownLatch cl = new CountDownLatch(tasks);
        while (tasks-- > 0) {
            r.execute(() -> cl.countDown());
        }
        assertTrue("latch timed out", cl.await(30, TimeUnit.SECONDS));
    }
}
