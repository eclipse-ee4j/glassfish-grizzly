package org.glassfish.grizzly;

import org.glassfish.grizzly.threadpool.VirtualThreadExecutorService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class VirtualThreadExecutorServiceTest extends GrizzlyTestCase {

    public void testCreateInstance() throws Exception {

        VirtualThreadExecutorService r = VirtualThreadExecutorService.createInstance();
        final int tasks = 2000000;
        doTest(r, tasks);
    }

    public void testAwaitTermination() throws Exception {
        VirtualThreadExecutorService r = VirtualThreadExecutorService.createInstance();
        final int tasks = 2000;
        runTasks(r, tasks);
        r.shutdown();
        assertTrue(r.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(r.isTerminated());
    }

    private void doTest(VirtualThreadExecutorService r, int tasks) throws Exception {
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

    private void runTasks(VirtualThreadExecutorService r, int tasks) throws Exception {
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
}
