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
 * <p>
 * The pool of the virtual threads is built when the first virtual thread calls {@link #get()}, so an application
 * without virtual threads allocates nothing for it. It is tuned with two system properties, read once:
 * <ul>
 * <li>{@code org.glassfish.grizzly.nio.DirectByteBufferRecord.virtual-pool-slots-per-stripe}: the records each stripe
 * keeps, default 4; {@code 0} disables the pool.</li>
 * <li>{@code org.glassfish.grizzly.nio.DirectByteBufferRecord.virtual-pool-max-buffer-size}: the largest direct buffer,
 * in bytes, that a pooled record may hold, default 1048576 (1 MiB).</li>
 * </ul>
 * <p>
 * On a host where {@code SO_RCVBUF}, the default read buffer size of a TCP connection, is larger than
 * {@code virtual-pool-max-buffer-size}, the records of the reads are not pooled and the reads behave as without the
 * pool; raise the property in that case.
 * <p>
 * The pool keeps at most stripes x slots x {@code virtual-pool-max-buffer-size} bytes of direct memory: with the
 * defaults and 64 stripes that is 256 MiB, and 1 GiB at the cap of 256 stripes. A typical {@code SO_RCVBUF} makes it
 * much smaller.
 *
 * @author Alexey Stashok
 */
public final class DirectByteBufferRecord {

    private static final ThreadCache.CachedTypeIndex<DirectByteBufferRecord> CACHE_IDX = ThreadCache.obtainIndex("direct-buffer-cache",
            DirectByteBufferRecord.class, 1);

    /**
     * System property: how many released records each stripe of the pool of the virtual threads keeps. The default is
     * {@value #DEFAULT_VIRTUAL_POOL_SLOTS_PER_STRIPE}; {@code 0} disables the pool: a virtual thread then allocates a new
     * record on {@link #get()} and {@link #release()} drops it.
     */
    static final String VIRTUAL_POOL_SLOTS_PER_STRIPE_PROPERTY = DirectByteBufferRecord.class.getName() + ".virtual-pool-slots-per-stripe";

    /**
     * System property: the largest direct buffer, in bytes, that a pooled record of a virtual thread may hold. A record
     * whose direct buffer is larger is not pooled but dropped on {@link #release()}. The default is
     * {@value #DEFAULT_VIRTUAL_POOL_MAX_BUFFER_SIZE} bytes (1 MiB).
     */
    static final String VIRTUAL_POOL_MAX_BUFFER_SIZE_PROPERTY = DirectByteBufferRecord.class.getName() + ".virtual-pool-max-buffer-size";

    static final int DEFAULT_VIRTUAL_POOL_SLOTS_PER_STRIPE = 4;

    static final int DEFAULT_VIRTUAL_POOL_MAX_BUFFER_SIZE = 1024 * 1024;

    /**
     * Holds the pool of the virtual threads, so that it is built by the first virtual thread that needs it. A virtual
     * thread's {@link ThreadCache} ends with it, and every read or write event may run on a new virtual thread, so a
     * record of its own would allocate a direct buffer again each time. The pool is striped by thread id, with a power of
     * two stripes (the smallest one that is at least the parallelism of the scheduler of the virtual threads,
     * {@code jdk.virtualThreadScheduler.parallelism}, or the number of processors if that is not set, and at most 256).
     * Each stripe keeps at most {@value #DEFAULT_VIRTUAL_POOL_SLOTS_PER_STRIPE} records, or as many as
     * {@link #VIRTUAL_POOL_SLOTS_PER_STRIPE_PROPERTY} says, and a record whose direct buffer is larger than
     * {@link #VIRTUAL_POOL_MAX_BUFFER_SIZE_PROPERTY} is not kept. A pooled record keeps its direct buffer behind a
     * {@link SoftReference} (see {@link #release()}), so the pool holds at most stripes * slots buffers of at most that
     * size. The garbage collector clears a soft reference by its policy on the age of the reference and the free heap,
     * not when the direct memory runs short, so those buffers may stay allocated for a long time.
     */
    private static final class VirtualPoolsHolder {
        static final VirtualPools INSTANCE = new VirtualPools(defaultStripes(),
                Math.max(0, Integer.getInteger(VIRTUAL_POOL_SLOTS_PER_STRIPE_PROPERTY, DEFAULT_VIRTUAL_POOL_SLOTS_PER_STRIPE)),
                Math.max(0, Integer.getInteger(VIRTUAL_POOL_MAX_BUFFER_SIZE_PROPERTY, DEFAULT_VIRTUAL_POOL_MAX_BUFFER_SIZE)));

        private static int defaultStripes() {
            int parallelism = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", 0);
            if (parallelism < 1) {
                parallelism = Runtime.getRuntime().availableProcessors();
            }
            // the smallest power of two that is at least parallelism, at most 256
            return Integer.highestOneBit(Math.min(Math.max(1, parallelism), 256) * 2 - 1);
        }
    }

    /**
     * Returns a record to use until {@link #release()}, which has to be called exactly once per call of this method.
     * <p>
     * On a platform thread this is the thread's own record, and nested calls return the same record, so the release of
     * the inner call already empties it for the outer one. On a virtual thread
     * it comes from a pool shared with other virtual threads, and every call, nested ones too, returns a separate record
     * that is used by this caller alone. After {@link #release()} neither the record nor the buffers it handed out may be
     * touched, not even by the caller that released it: another virtual thread may have taken it. A second
     * {@link #release()} of the same call is ignored only until the record is taken again by any {@link #get()}, on this or
     * another virtual thread, see {@link #release()}.
     */
    public static DirectByteBufferRecord get() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread.isVirtual()) {
            return VirtualPoolsHolder.INSTANCE.acquire(currentThread);
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
    private VirtualPools.Stripe pool;
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
     * this call. The pool keeps the record only if its direct buffer is not larger than
     * {@link #VIRTUAL_POOL_MAX_BUFFER_SIZE_PROPERTY} and the stripe has a free slot, otherwise the record is dropped.
     * <p>
     * A second call for the same {@link #get()} is ignored for a record of a virtual thread only as long as no
     * {@link #get()}, on this or another virtual thread, took the record in the meantime (the same thread is the likely
     * one, as the pool is striped by thread id); after that it corrupts the record of its new holder and may hand the
     * record out twice. No caller in the repository does this.
     */
    public void release() {
        if (virtual && pool == null) {
            // released already. This is a defensive measure that keeps a stale second release from writing the fields of
            // a record that is already pooled. It narrows that window but does not close it, and no deterministic test
            // can show it: the fields are written only while the record sits in the pool, which a test cannot observe
            // without a race.
            return;
        }
        // a record is pooled only if its buffer is small enough. The capacity is measured only for virtual threads, so that
        // the platform path is unchanged. A record that holds no strong buffer was released before without allocate()
        // and was pooled then, so it passed the size check already and needs no new one (capacity 0).
        int bufferCapacity = 0;
        if (directBuffer != null) {
            if (virtual) {
                bufferCapacity = directBuffer.capacity();
            }
            directBuffer.clear();
            switchToSoft();
        }

        Arrays.fill(array, 0, arraySize, null);
        arraySize = 0;
        directBufferSlice = null;
        sliceOffset = 0;

        final VirtualPools.Stripe poolLocal = pool;
        if (poolLocal != null) {
            // from here on another virtual thread may take this record
            pool = null;
            poolLocal.offer(this, bufferCapacity);
        }
    }

    /**
     * Empties the pool of the virtual threads, for tests.
     */
    static void drainForTests() {
        VirtualPoolsHolder.INSTANCE.drain();
    }

    /**
     * @return how many released records the pool of the virtual threads holds, for tests
     */
    static int pooledVirtualRecords() {
        return VirtualPoolsHolder.INSTANCE.pooled();
    }

    /**
     * @return the most records the pool of the virtual threads keeps, for tests
     */
    static int virtualPoolsCapacity() {
        return VirtualPoolsHolder.INSTANCE.capacity();
    }

    /**
     * The pool of the records of virtual threads: stripes of a few slots, each empty or holding one released record.
     * Package-private with its constructor so that tests can use pools of their own, as the system properties are read
     * once, when the class is loaded.
     */
    static final class VirtualPools {
        private final Stripe[] stripes;
        private final int slotsPerStripe;
        private final int maxBufferSize;

        /**
         * @param stripes the number of stripes, a power of two
         * @param slotsPerStripe the records a stripe keeps, 0 for a pool that keeps none
         * @param maxBufferSize the largest direct buffer, in bytes, of a record that is kept
         */
        VirtualPools(final int stripes, final int slotsPerStripe, final int maxBufferSize) {
            if (stripes < 1 || Integer.bitCount(stripes) != 1) {
                throw new IllegalArgumentException("stripes must be a power of two: " + stripes);
            }
            this.stripes = new Stripe[stripes];
            this.slotsPerStripe = slotsPerStripe;
            this.maxBufferSize = maxBufferSize;
            for (int i = 0; i < stripes; i++) {
                this.stripes[i] = new Stripe(slotsPerStripe, maxBufferSize);
            }
        }

        /** Takes a record from the stripe of the thread, or creates one, and gives it to the thread. */
        DirectByteBufferRecord acquire(final Thread thread) {
            final Stripe stripe = stripes[(int) (thread.threadId() & (stripes.length - 1))];
            DirectByteBufferRecord record = stripe.take();
            if (record == null) {
                record = new DirectByteBufferRecord(true);
            }
            record.pool = stripe;
            return record;
        }

        DirectByteBufferRecord acquire() {
            return acquire(Thread.currentThread());
        }

        int pooled() {
            int pooled = 0;
            for (final Stripe stripe : stripes) {
                pooled += stripe.pooled();
            }
            return pooled;
        }

        int capacity() {
            return stripes.length * slotsPerStripe;
        }

        void drain() {
            for (final Stripe stripe : stripes) {
                while (stripe.take() != null) {
                    // keep taking
                }
            }
        }

        /**
         * A stripe of the pool. A record is in at most one slot, because it is offered once, when it is released, and a slot
         * is emptied when the record is taken. Taking and offering go through atomic operations on the slots, which also
         * order the writes to the record before its next user.
         */
        private static final class Stripe {
            private final AtomicReferenceArray<DirectByteBufferRecord> slots;
            private final int maxBufferSize;

            Stripe(final int slotsCount, final int maxBufferSize) {
                this.slots = new AtomicReferenceArray<>(slotsCount);
                this.maxBufferSize = maxBufferSize;
            }

            DirectByteBufferRecord take() {
                for (int i = 0; i < slots.length(); i++) {
                    final DirectByteBufferRecord record = slots.get(i);
                    if (record != null && slots.compareAndSet(i, record, null)) {
                        return record;
                    }
                }
                return null;
            }

            void offer(final DirectByteBufferRecord record, final int bufferCapacity) {
                if (bufferCapacity > maxBufferSize) {
                    // too large to keep: the record is dropped
                    return;
                }
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
