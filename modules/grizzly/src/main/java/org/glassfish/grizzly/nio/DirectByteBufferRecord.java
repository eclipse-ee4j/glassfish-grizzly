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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.Buffers;

/**
 * Thread-local Direct {@link ByteBuffer} storage.
 *
 * @author Alexey Stashok
 */
public final class DirectByteBufferRecord {

    private static final ThreadCache.CachedTypeIndex<DirectByteBufferRecord> CACHE_IDX = ThreadCache.obtainIndex("direct-buffer-cache",
            DirectByteBufferRecord.class, 1);

    /**
     * Records released by virtual threads. A virtual thread's {@link ThreadCache} ends with it, and every read or write
     * event may run on a new virtual thread, so a record of its own would allocate a direct buffer again each time. The
     * pool is striped by thread id; each stripe keeps at most {@link #VIRTUAL_STRIPE_CAPACITY} records.
     */
    private static final VirtualPool[] VIRTUAL_POOLS = new VirtualPool[Integer.highestOneBit(Math.max(1, Runtime.getRuntime().availableProcessors()) * 2 - 1)];

    private static final int VIRTUAL_STRIPE_CAPACITY = 4;

    static {
        for (int i = 0; i < VIRTUAL_POOLS.length; i++) {
            VIRTUAL_POOLS[i] = new VirtualPool();
        }
    }

    /**
     * Returns a record that only the calling code uses until it calls {@link #release()}. On a virtual thread the record
     * may be used by another virtual thread after it was released, so it must not be touched after that.
     */
    public static DirectByteBufferRecord get() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread.isVirtual()) {
            final VirtualPool pool = VIRTUAL_POOLS[(int) (currentThread.threadId() & (VIRTUAL_POOLS.length - 1))];
            DirectByteBufferRecord virtualRecord = pool.records.poll();
            if (virtualRecord != null) {
                pool.size.decrementAndGet();
            } else {
                virtualRecord = new DirectByteBufferRecord();
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

    // set for a record taken by a virtual thread: where #release() gives it back
    private VirtualPool pool;
    private ByteBuffer directBuffer;
    private int sliceOffset;
    private ByteBuffer directBufferSlice;
    private SoftReference<ByteBuffer> softRef;
    private ByteBuffer array[];
    private int arraySize;

    DirectByteBufferRecord() {
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

    public void release() {
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
            pooled += virtualPool.size.get();
        }
        return pooled;
    }

    /**
     * @return the most records the pools of the virtual threads keep, for tests
     */
    static int virtualPoolsCapacity() {
        return VIRTUAL_POOLS.length * VIRTUAL_STRIPE_CAPACITY;
    }

    private static final class VirtualPool {
        final ConcurrentLinkedQueue<DirectByteBufferRecord> records = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();

        void offer(final DirectByteBufferRecord record) {
            // the size is counted before the offer, so the pool never keeps more than the capacity
            if (size.incrementAndGet() <= VIRTUAL_STRIPE_CAPACITY) {
                records.offer(record);
            } else {
                size.decrementAndGet();
            }
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
