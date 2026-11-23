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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.WriteHandler;
import org.junit.Test;

public class TaskQueueTest {

    /**
     * A record offered before its space is reserved (as Http2SessionOutputSink does) must still be failed on close.
     */
    @Test
    public void onCloseFailsRecordQueuedWithoutReservedSpace() {
        final TaskQueue<AsyncQueueRecord<Object>> queue = TaskQueue.createTaskQueue(null);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AsyncQueueRecord<Object> record = newRecord(new EmptyCompletionHandler<Object>() {
            @Override
            public void failed(final Throwable throwable) {
                failure.set(throwable);
            }
        });

        queue.offer(record);
        assertTrue(queue.isEmpty());

        queue.onClose();

        assertTrue(failure.get() instanceof IOException);
        assertNull(queue.poll());
    }

    /**
     * A throwing callback must not prevent the remaining records and write handlers from being notified on close.
     * The first callback failure is rethrown afterwards, later ones are attached as suppressed.
     */
    @Test
    public void onCloseNotifiesAllCallbacksWhenOneThrows() {
        final TaskQueue<AsyncQueueRecord<Object>> queue = TaskQueue.createTaskQueue(null);
        final RuntimeException recordFailure = new RuntimeException("record callback");
        final RuntimeException handlerFailure = new RuntimeException("handler callback");

        final AtomicReference<Throwable> secondRecordFailure = new AtomicReference<>();
        queue.offer(newRecord(new EmptyCompletionHandler<Object>() {
            @Override
            public void failed(final Throwable throwable) {
                throw recordFailure;
            }
        }));
        queue.offer(newRecord(new EmptyCompletionHandler<Object>() {
            @Override
            public void failed(final Throwable throwable) {
                secondRecordFailure.set(throwable);
            }
        }));

        // fill the queue so the write handlers are queued instead of being notified right away
        queue.reserveSpace(10);
        final AtomicReference<Throwable> secondHandlerError = new AtomicReference<>();
        queue.notifyWritePossible(new WriteHandler() {
            @Override
            public void onWritePossible() {
                fail("onWritePossible() must not be called");
            }

            @Override
            public void onError(final Throwable t) {
                throw handlerFailure;
            }
        }, 5);
        queue.notifyWritePossible(new WriteHandler() {
            @Override
            public void onWritePossible() {
                fail("onWritePossible() must not be called");
            }

            @Override
            public void onError(final Throwable t) {
                secondHandlerError.set(t);
            }
        }, 5);

        try {
            queue.onClose();
            fail("the callback failure must be propagated");
        } catch (RuntimeException e) {
            assertSame(recordFailure, e);
            assertArrayEquals(new Throwable[] {handlerFailure}, e.getSuppressed());
        }

        assertTrue(secondRecordFailure.get() instanceof IOException);
        assertTrue(secondHandlerError.get() instanceof IOException);
        assertNull(queue.poll());
    }

    private static AsyncQueueRecord<Object> newRecord(final CompletionHandler<Object> completionHandler) {
        return new AsyncQueueRecord<Object>(null, null, completionHandler) {
            @Override
            public Object getCurrentResult() {
                return null;
            }

            @Override
            public void recycle() {
            }
        };
    }
}
