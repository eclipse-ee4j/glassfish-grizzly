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

package org.glassfish.grizzly.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.glassfish.grizzly.EmptyCompletionHandler;
import org.junit.Test;

public class SafeFutureImplTest {
    @Test
    public void testResultPublication() throws Throwable {
        checkPublication(false, false);
    }

    @Test
    public void testTimedResultPublication() throws Throwable {
        checkPublication(false, true);
    }

    @Test
    public void testExceptionPublication() throws Throwable {
        checkPublication(true, false);
    }

    @Test
    public void testTimedExceptionPublication() throws Throwable {
        checkPublication(true, true);
    }

    private void checkPublication(boolean exceptional, boolean timed) throws Throwable {
        SafeFutureImpl<Object> future = SafeFutureImpl.create();
        AtomicReference<Object> completion = new AtomicReference<>();
        Object result = new Object();
        Exception failure = new Exception("expected failure");
        Object expected = exceptional ? failure : result;
        Field syncField = SafeFutureImpl.class.getDeclaredField("sync");
        syncField.setAccessible(true);
        AbstractQueuedSynchronizer sync = (AbstractQueuedSynchronizer) syncField.get(future);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(sync.getClass(), MethodHandles.lookup());
        MethodHandle setState = lookup.findVirtual(sync.getClass(), "setState", MethodType.methodType(void.class, int.class));

        // Hold the producer's intermediate state without a timing-dependent race or production test hook.
        // READY=0, RESULT=1: result/exception has not yet been published.
        setState.invoke(sync, 1);
        assertFalse("Publishing a result is not completion", future.isDone());
        assertThrows(TimeoutException.class, () -> future.get(0, TimeUnit.NANOSECONDS));
        assertFalse("Cancellation must not win after publication has started", future.cancel(false));
        future.addCompletionHandler(new EmptyCompletionHandler<Object>() {
            @Override
            public void completed(Object value) {
                completion.set(value);
            }

            @Override
            public void failed(Throwable throwable) {
                completion.set(throwable);
            }
        });
        assertSame("Callback must wait for publication", null, completion.get());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> reader = executor.submit(() -> {
            try {
                return timed ? future.get(10, TimeUnit.SECONDS) : future.get();
            } catch (ExecutionException e) {
                return e.getCause();
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!sync.hasQueuedThreads() && !reader.isDone() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue("Reader must queue until publication completes", sync.hasQueuedThreads());
            assertFalse("Reader returned before publication", reader.isDone());

            // Restore READY so the real completion method can publish and release the queued reader.
            setState.invoke(sync, 0);
            if (exceptional) {
                future.failure(failure);
            } else {
                future.result(result);
            }
            assertSame(expected, reader.get(5, TimeUnit.SECONDS));
            assertTrue(future.isDone());
            assertFalse(future.isCancelled());
            assertSame(expected, completion.get());
            assertFalse(future.cancel(false));
        } finally {
            reader.cancel(true);
            executor.shutdownNow();
            assertTrue("Reader did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testCancellation() throws Exception {
        SafeFutureImpl<Object> future = SafeFutureImpl.create();
        assertFalse(future.isDone());
        assertThrows(TimeoutException.class, () -> future.get(0, TimeUnit.NANOSECONDS));
        assertTrue(future.cancel(false));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, () -> future.get());
        assertThrows(CancellationException.class, () -> future.get(0, TimeUnit.NANOSECONDS));
        future.result(new Object());
        future.failure(new Exception("ignored after cancellation"));
        assertTrue(future.isCancelled());
    }
}
