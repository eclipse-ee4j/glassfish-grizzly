/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.asyncqueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.junit.Test;

/**
 * Tests that {@link TaskQueue} keeps its reserved-space counter synchronized with the actually queued records,
 * in particular across {@link TaskQueue#onClose()}.
 */
public class TaskQueueTest {

    private static class TestRecord extends AsyncQueueRecord<WriteResult> {
        private final AtomicBoolean failureNotified = new AtomicBoolean();

        TestRecord() {
            super(null, null, null);
        }

        @Override
        public void notifyFailure(final Throwable e) {
            failureNotified.set(true);
        }

        @Override
        public void recycle() {
        }

        @Override
        public WriteResult getCurrentResult() {
            return null;
        }
    }

    private static TaskQueue<TestRecord> newQueue() {
        return TaskQueue.createTaskQueue(() -> 65536);
    }

    @Test
    public void reserveAndReleaseOnOpenQueue() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord record = new TestRecord();
        queue.offer(record);
        assertEquals(1, queue.getQueue().size());
        assertFalse(record.failureNotified.get());

        assertEquals(45, queue.reserveSpace(45));
        assertEquals(45, queue.size());
        assertEquals(45, queue.spaceInBytes());
        assertFalse(queue.isEmpty());

        assertEquals(0, queue.releaseSpace(45));
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
    }

    @Test
    public void reservedSpaceIsReleasedOnClose() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord record = new TestRecord();
        queue.offer(record);
        assertEquals(45, queue.reserveSpace(45));
        assertFalse(queue.isEmpty());

        queue.onClose();

        assertTrue(record.failureNotified.get());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
        assertNull(queue.poll());
    }

    @Test
    public void offerAndReserveAfterCloseKeepQueueEmpty() {
        final TaskQueue<TestRecord> queue = newQueue();
        queue.onClose();

        final TestRecord record = new TestRecord();
        queue.offer(record);
        assertTrue(record.failureNotified.get());
        assertTrue(queue.getQueue().isEmpty());
        assertEquals(0, queue.reserveSpace(45));
        assertEquals(0, queue.releaseSpace(45));
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
    }

    @Test
    public void closeDrainsRecordWhoseSpaceIsNotYetReserved() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord record = new TestRecord();
        queue.offer(record);

        queue.onClose();

        assertTrue(record.failureNotified.get());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    public void closeResetsSpaceReservedForMultipleRecords() {
        final TaskQueue<TestRecord> queue = newQueue();
        final TestRecord first = new TestRecord();
        final TestRecord second = new TestRecord();
        queue.offer(first);
        queue.offer(second);
        assertEquals(45, queue.reserveSpace(45));
        assertEquals(90, queue.reserveSpace(45));

        queue.onClose();

        assertTrue(first.failureNotified.get());
        assertTrue(second.failureNotified.get());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.spaceInBytes());
    }

    @Test
    public void queuedWriteHandlerGetsErrorNotWritePossibleOnClose() {
        final TaskQueue<TestRecord> queue = TaskQueue.createTaskQueue(() -> 10);
        assertEquals(10, queue.reserveSpace(10));
        final AtomicBoolean writePossible = new AtomicBoolean();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        queue.notifyWritePossible(new WriteHandler() {
            @Override
            public void onWritePossible() {
                writePossible.set(true);
            }

            @Override
            public void onError(final Throwable t) {
                error.set(t);
            }
        });

        queue.onClose();

        assertFalse(writePossible.get());
        assertTrue(error.get() instanceof IOException);
    }

    @Test
    public void notifyWritePossibleAfterCloseGetsErrorNotWritePossible() {
        final TaskQueue<TestRecord> queue = newQueue();
        queue.onClose();
        final AtomicBoolean writePossible = new AtomicBoolean();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        queue.notifyWritePossible(new WriteHandler() {
            @Override
            public void onWritePossible() {
                writePossible.set(true);
            }

            @Override
            public void onError(final Throwable t) {
                error.set(t);
            }
        });
        assertFalse(writePossible.get());
        assertTrue(error.get() instanceof IOException);
    }

    @Test
    public void doNotifyDuringCloseDeliversErrorExactlyOnceNotWritePossible() throws Exception {
        final TaskQueue<TestRecord> queue = TaskQueue.createTaskQueue(() -> 10);
        final CountDownLatch failureEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final TestRecord record = new TestRecord() {
            @Override
            public void notifyFailure(final Throwable e) {
                super.notifyFailure(e);
                failureEntered.countDown();
                try {
                    releaseClose.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        queue.offer(record);
        assertEquals(10, queue.reserveSpace(10));
        final AtomicBoolean writePossible = new AtomicBoolean();
        final AtomicInteger errorCount = new AtomicInteger();
        queue.notifyWritePossible(new WriteHandler() {
            @Override
            public void onWritePossible() {
                writePossible.set(true);
            }

            @Override
            public void onError(final Throwable t) {
                errorCount.incrementAndGet();
            }
        });

        final Thread closer = new Thread(queue::onClose);
        closer.start();
        assertTrue(failureEntered.await(10, TimeUnit.SECONDS));
        queue.doNotify();
        releaseClose.countDown();
        closer.join(10000);

        assertFalse("onWritePossible called on closed queue", writePossible.get());
        assertEquals("handler must get exactly one onError", 1, errorCount.get());
        assertEquals(0, queue.size());
    }

    @Test
    public void onCloseNotifiesAllRecordsEvenIfACallbackThrows() {
        final TaskQueue<TestRecord> queue = newQueue();
        final AtomicBoolean secondFailed = new AtomicBoolean();
        queue.offer(new TestRecord() {
            @Override
            public void notifyFailure(final Throwable e) {
                super.notifyFailure(e);
                throw new IllegalStateException("boom");
            }
        });
        queue.offer(new TestRecord() {
            @Override
            public void notifyFailure(final Throwable e) {
                super.notifyFailure(e);
                secondFailed.set(true);
            }
        });
        queue.reserveSpace(2);

        try {
            queue.onClose();
            fail("expected the callback exception to be rethrown after all notifications");
        } catch (IllegalStateException expected) {
        }
        assertTrue(secondFailed.get());
        assertEquals(0, queue.size());
    }

    @Test
    public void notifyWritePossibleRacingCloseNeverCallsWritePossible() throws Exception {
        for (int round = 0; round < 300; round++) {
            final TaskQueue<TestRecord> queue = TaskQueue.createTaskQueue(() -> 10);
            queue.offer(new TestRecord());
            assertEquals(10, queue.reserveSpace(10));
            final AtomicBoolean writePossible = new AtomicBoolean();
            final AtomicInteger errorCount = new AtomicInteger();
            final WriteHandler handler = new WriteHandler() {
                @Override
                public void onWritePossible() {
                    writePossible.set(true);
                }

                @Override
                public void onError(final Throwable t) {
                    errorCount.incrementAndGet();
                }
            };
            final Thread notifier = new Thread(() -> queue.notifyWritePossible(handler));
            final Thread closer = new Thread(queue::onClose);
            notifier.start();
            closer.start();
            notifier.join(10000);
            closer.join(10000);
            assertFalse("onWritePossible called on a closed queue", writePossible.get());
            assertEquals("handler must get exactly one onError", 1, errorCount.get());
        }
    }

    @Test
    public void nullPollImpliesZeroSizeEvenDuringConcurrentClose() throws Exception {
        for (int round = 0; round < 200; round++) {
            final TaskQueue<TestRecord> queue = newQueue();
            final int count = 50;
            for (int i = 0; i < count; i++) {
                queue.offer(new TestRecord());
            }
            queue.reserveSpace(count);

            final Thread closer = new Thread(queue::onClose);
            closer.start();

            TestRecord record;
            while ((record = queue.poll()) != null) {
                queue.releaseSpace(1);
            }
            assertEquals("poll() returned null but size() != 0: spaceInBytes desynced from the record set",
                    0, queue.size());
            closer.join();
            assertEquals(0, queue.size());
        }
    }
}
