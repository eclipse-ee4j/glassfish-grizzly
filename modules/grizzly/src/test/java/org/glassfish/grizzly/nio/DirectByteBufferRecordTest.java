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

package org.glassfish.grizzly.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * A virtual thread's thread-local cache ends with it, so the records of virtual threads are pooled: a record released by
 * one virtual thread is reused by the next one instead of allocating a direct buffer again.
 */
public class DirectByteBufferRecordTest {

    private static final int BUFFER_SIZE = 4096;

    /** Runs the task on a new virtual thread and waits for it. */
    private static void onVirtualThread(final ExecutorService virtual, final Runnable task) throws Exception {
        virtual.submit(task).get(30, TimeUnit.SECONDS);
    }

    @Test
    public void recordsOfVirtualThreadsAreReused() throws Exception {
        final Set<DirectByteBufferRecord> records = Collections.newSetFromMap(new IdentityHashMap<>());
        final int threads = 300;
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                // one virtual thread after the other: each one finds what the previous ones released
                onVirtualThread(virtual, () -> {
                    final DirectByteBufferRecord record = DirectByteBufferRecord.get();
                    try {
                        record.allocate(BUFFER_SIZE);
                        synchronized (records) {
                            records.add(record);
                        }
                    } finally {
                        record.release();
                    }
                });
            }
        }
        // at most one record per pool stripe can be in use at a time when the threads run one after the other
        assertTrue("distinct records for " + threads + " virtual threads: " + records.size(), records.size() <= DirectByteBufferRecord.virtualPoolsCapacity());
    }

    @Test
    public void aReleasedRecordIsCleared() throws Exception {
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                onVirtualThread(virtual, () -> {
                    final DirectByteBufferRecord record = DirectByteBufferRecord.get();
                    try {
                        // whatever the previous user left, a record taken from the pool starts empty
                        assertEquals(0, record.getArraySize());
                        assertNull(record.getDirectBufferSlice());
                        final ByteBuffer buffer = record.allocate(BUFFER_SIZE);
                        assertEquals(0, buffer.position());
                        assertTrue(buffer.remaining() >= BUFFER_SIZE);
                        buffer.put(new byte[100]);
                        record.putToArray(buffer);
                    } finally {
                        record.release();
                    }
                });
            }
        }
    }

    /** No two virtual threads use a record at the same time, and each one keeps what it wrote until it releases it. */
    @Test
    public void recordsAreNotSharedWhileInUse() throws Exception {
        final int threads = 64;
        final int rounds = 200;
        final AtomicInteger reuses = new AtomicInteger();
        final Set<DirectByteBufferRecord> inUse = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        final List<Future<?>> done = new ArrayList<>();
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final byte id = (byte) (t + 1);
                done.add(virtual.submit(() -> {
                    for (int r = 0; r < rounds; r++) {
                        // a new virtual thread for every round, as for every read or write event
                        final DirectByteBufferRecord[] taken = new DirectByteBufferRecord[1];
                        final Thread round = Thread.ofVirtual().unstarted(() -> {
                            final DirectByteBufferRecord record = DirectByteBufferRecord.get();
                            taken[0] = record;
                            try {
                                if (!inUse.add(record)) {
                                    throw new AssertionError("a record in use was handed out again");
                                }
                                final ByteBuffer buffer = record.allocate(BUFFER_SIZE);
                                final int position = buffer.position();
                                for (int i = 0; i < 64; i++) {
                                    buffer.put(position + i, id);
                                }
                                Thread.yield();
                                for (int i = 0; i < 64; i++) {
                                    if (buffer.get(position + i) != id) {
                                        throw new AssertionError("another thread wrote into a record in use");
                                    }
                                }
                                inUse.remove(record);
                            } finally {
                                record.release();
                            }
                        });
                        round.start();
                        try {
                            round.join();
                        } catch (final InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        if (taken[0] != null) {
                            reuses.incrementAndGet();
                        }
                    }
                }));
            }
            for (final Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        }
        assertEquals(threads * rounds, reuses.get());
        assertTrue("records left in use: " + inUse.size(), inUse.isEmpty());
    }

    /** The pool keeps a bounded number of records, however many were in use at once. */
    @Test
    public void thePoolIsBounded() throws Exception {
        final int threads = 200;
        final CountDownLatch allTaken = new CountDownLatch(threads);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Future<?>> done = new ArrayList<>();
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                done.add(virtual.submit(() -> {
                    final DirectByteBufferRecord record = DirectByteBufferRecord.get();
                    try {
                        record.allocate(BUFFER_SIZE);
                        allTaken.countDown();
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                    } finally {
                        record.release();
                    }
                    return null;
                }));
            }
            assertTrue(allTaken.await(30, TimeUnit.SECONDS));
            release.countDown();
            for (final Future<?> f : done) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        final int pooled = DirectByteBufferRecord.pooledVirtualRecords();
        assertTrue("pooled records: " + pooled, pooled > 0 && pooled <= DirectByteBufferRecord.virtualPoolsCapacity());
    }

    /** A platform thread keeps its own record in its thread-local cache, as before, and never uses the pool. */
    @Test
    public void platformThreadsKeepTheirOwnRecord() throws Exception {
        final ExecutorService platform = Executors.newSingleThreadExecutor();
        try {
            final int pooledBefore = DirectByteBufferRecord.pooledVirtualRecords();
            final DirectByteBufferRecord[] records = new DirectByteBufferRecord[2];
            platform.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    records[i] = DirectByteBufferRecord.get();
                    records[i].allocate(BUFFER_SIZE);
                    records[i].release();
                }
            }).get(30, TimeUnit.SECONDS);
            assertSame(records[0], records[1]);
            assertEquals(pooledBefore, DirectByteBufferRecord.pooledVirtualRecords());
        } finally {
            platform.shutdownNow();
        }
    }
}
