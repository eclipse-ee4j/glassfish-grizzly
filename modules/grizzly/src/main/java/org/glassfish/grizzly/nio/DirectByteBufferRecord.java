/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.Buffers;

/**
 * Direct {@link ByteBuffer} storage: one record per platform thread (in its {@link ThreadCache}), and records that
 * virtual threads take from a small shared pool and give back with {@link #release()}.
 *
 * @author Alexey Stashok
 */
public final class DirectByteBufferRecord {

    private static final ThreadCache.CachedTypeIndex<DirectByteBufferRecord> CACHE_IDX = ThreadCache.obtainIndex("direct-buffer-cache",
            DirectByteBufferRecord.class, 1);

    /**
     * Records released by virtual threads. A virtual thread's {@link ThreadCache} ends with it, and every read or write
     * event may run on a new virtual thread, so a record of its own would allocate a direct buffer again each time. The
     * pool is striped by thread id, with a power of two stripes (at least the number of processors), and each stripe keeps
     * at most {@link #VIRTUAL_STRIPE_CAPACITY} records. A pooled record keeps its largest direct buffer behind a
     * {@link SoftReference} (see {@link #release()}), so the pool holds at most stripes * capacity of the largest buffers
     * requested, until the heap is under pressure.
     */
    private static final VirtualPool[] VIRTUAL_POOLS = new VirtualPool[Integer.highestOneBit(Math.max(1, Runtime.getRuntime().availableProcessors()) * 2 - 1)];

    private static final int VIRTUAL_STRIPE_CAPACITY = 4;

    static {
        for (int i = 0; i < VIRTUAL_POOLS.length; i++) {
            VIRTUAL_POOLS[i] = new VirtualPool();
        }
    }

    /**
     * Returns a record that only the calling code uses until it calls {@link #release()}, exactly once. On a virtual
     * thread the record comes from a pool shared with other virtual threads, so it must not be touched after it was
     * released.
     */
    public static DirectByteBufferRecord get() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread.isVirtual()) {
            final VirtualPool pool = VIRTUAL_POOLS[(int) (currentThread.threadId() & (VIRTUAL_POOLS.length - 1))];
            DirectByteBufferRecord virtualRecord = pool.take();
            if (virtualRecord == null) {
                virtualRecord = new DirectByteBufferRecord(true);
            }
            virtualRecord.pool = pool;
            return virtualRecord;
        }
        final DirectByteBufferRecord record = ThreadCache.getFromCache(CACHE_IDX);
        if (record != null) {
            return record;
        }
        final DirectByteBufferRecord recordLocal = new DirectByteBufferRecord();
        ThreadCache.putToCache(CACHE_IDX, recordLocal);
        return recordLocal;
    }

    // whether this record belongs to the pool of the virtual threads
    private final boolean virtual;
    // set while a virtual thread uses the record: where #release() gives it back
    private VirtualPool pool;
    private ByteBuffer directBuffer;
    private int sliceOffset;
    private ByteBuffer directBufferSlice;
    private SoftReference<ByteBuffer> softRef;
    private ByteBuffer array[];
    private int arraySize;

    DirectByteBufferRecord() {
        this(false);
    }

    private DirectByteBufferRecord(final boolean virtual) {
        this.virtual = virtual;
        array = new ByteBuffer[8];
    }

    public ByteBuffer getDirectBuffer() {
        return directBuffer;
    }

    public ByteBuffer getDirectBufferSlice() {
        return directBufferSlice;
    }

    public ByteBuffer allocate(final int size) {
        ByteBuffer byteBuffer;
        if ((byteBuffer = switchToStrong()) != null && byteBuffer.remaining() >= size) {
            return byteBuffer;
        } else {
            byteBuffer = ByteBuffer.allocateDirect(size);
            reset(byteBuffer);
            return byteBuffer;
        }
    }

    public ByteBuffer sliceBuffer() {
        int oldLim = directBuffer.limit();
        Buffers.setPositionLimit(directBuffer, sliceOffset, directBuffer.capacity());
        directBufferSlice = directBuffer.slice();
        Buffers.setPositionLimit(directBuffer, 0, oldLim);
        return directBufferSlice;
    }

    public void finishBufferSlice() {
        if (directBufferSlice != null) {
            directBufferSlice.flip();
            final int sliceSz = directBufferSlice.remaining();
            sliceOffset += sliceSz;

            if (sliceSz > 0) {
                putToArray(directBufferSlice);
            }

            directBufferSlice = null;
        }
    }

    public ByteBuffer[] getArray() {
        return array;
    }

    public int getArraySize() {
        return arraySize;
    }

    public void putToArray(ByteBuffer byteBuffer) {
        ensureArraySize();
        array[arraySize++] = byteBuffer;
    }

    /**
     * Gives the record back, exactly once per {@link #get()}. A record of a virtual thread goes back to the pool and may
     * be taken by another virtual thread at once, so the caller must not touch it, nor the buffers it handed out, after
     * this call. A second call for the same {@link #get()} is ignored for a record of a virtual thread, as long as no other
     * virtual thread took it in the meantime.
     */
    public void release() {
        if (virtual && pool == null) {
            // released already
            return;
        }
        if (directBuffer != null) {
            directBuffer.clear();
            switchToSoft();
        }

        Arrays.fill(array, 0, arraySize, null);
        arraySize = 0;
        directBufferSlice = null;
        sliceOffset = 0;

        final VirtualPool poolLocal = pool;
        if (poolLocal != null) {
            // from here on another virtual thread may take this record
            pool = null;
            poolLocal.offer(this);
        }
    }

    /**
     * @return how many released records the pools of the virtual threads hold, for tests
     */
    static int pooledVirtualRecords() {
        int pooled = 0;
        for (final VirtualPool virtualPool : VIRTUAL_POOLS) {
            pooled += virtualPool.pooled();
        }
        return pooled;
    }

    /**
     * @return the most records the pools of the virtual threads keep, for tests
     */
    static int virtualPoolsCapacity() {
        return VIRTUAL_POOLS.length * VIRTUAL_STRIPE_CAPACITY;
    }

    /**
     * A stripe of the pool: a few slots, each empty or holding one released record. A record is in at most one slot,
     * because it is offered once, when it is released, and a slot is emptied when the record is taken. Taking and offering
     * go through atomic operations on the slots, which also order the writes to the record before its next user.
     */
    private static final class VirtualPool {
        private final AtomicReferenceArray<DirectByteBufferRecord> slots = new AtomicReferenceArray<>(VIRTUAL_STRIPE_CAPACITY);

        DirectByteBufferRecord take() {
            for (int i = 0; i < slots.length(); i++) {
                final DirectByteBufferRecord record = slots.get(i);
                if (record != null && slots.compareAndSet(i, record, null)) {
                    return record;
                }
            }
            return null;
        }

        void offer(final DirectByteBufferRecord record) {
            for (int i = 0; i < slots.length(); i++) {
                if (slots.get(i) == null && slots.compareAndSet(i, null, record)) {
                    return;
                }
            }
            // the stripe is full: the record is dropped
        }

        int pooled() {
            int pooled = 0;
            for (int i = 0; i < slots.length(); i++) {
                if (slots.get(i) != null) {
                    pooled++;
                }
            }
            return pooled;
        }
    }

    private ByteBuffer switchToStrong() {
        if (directBuffer == null && softRef != null) {
            directBuffer = directBufferSlice = softRef.get();
        }
        return directBuffer;
    }

    private void switchToSoft() {
        if (directBuffer != null && softRef == null) {
            softRef = new SoftReference<>(directBuffer);
        }
        directBuffer = null;
    }

    private void reset(ByteBuffer byteBuffer) {
        directBuffer = directBufferSlice = byteBuffer;
        softRef = null;
    }

    private void ensureArraySize() {
        if (arraySize == array.length) {
            array = Arrays.copyOf(array, arraySize * 3 / 2 + 1);
        }
    }
}
