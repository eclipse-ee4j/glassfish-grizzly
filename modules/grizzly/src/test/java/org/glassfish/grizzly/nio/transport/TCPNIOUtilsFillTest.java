/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.glassfish.grizzly.nio.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.glassfish.grizzly.memory.BufferArray;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.DirectByteBufferRecord;
import org.junit.Test;

/**
 * {@link TCPNIOUtils#fill(BufferArray, int, DirectByteBufferRecord)} copies the heap components of a composite into
 * the thread's direct I/O buffer for the socket write.
 */
public class TCPNIOUtilsFillTest {

    private static final MemoryManager MM = MemoryManager.DEFAULT_MEMORY_MANAGER;

    @Test
    public void heapOnlyCompositeIsCopiedWithoutASliceView() {
        // two rounds: a fresh direct buffer, then a fresh or reused one (the record keeps it softly referenced)
        for (int round = 0; round < 2; round++) {
            final BufferArray array = BufferArray.create();
            array.add(Buffers.wrap(MM, "HTTP/1.1 200 OK\r\n\r\n"));
            array.add(Buffers.wrap(MM, "Hello, World!"));
            final DirectByteBufferRecord record = DirectByteBufferRecord.get();
            try {
                TCPNIOUtils.fill(array, 32, record);
                assertSame("round " + round, record.getDirectBuffer(), record.getDirectBufferSlice());

                record.finishBufferSlice();
                assertEquals(1, record.getArraySize());
                assertEquals("HTTP/1.1 200 OK\r\n\r\nHello, World!", content(record.getArray()[0]));
            } finally {
                record.release();
                array.restore();
                array.recycle();
            }
        }
    }

    @Test
    public void heapDirectHeapCompositeKeepsOrder() {
        final ByteBuffer direct = ByteBuffer.allocateDirect(6);
        direct.put("DIRECT".getBytes(StandardCharsets.US_ASCII)).flip();
        final BufferArray array = BufferArray.create();
        array.add(Buffers.wrap(MM, "head-"));
        array.add(Buffers.wrap(MM, direct));
        array.add(Buffers.wrap(MM, "-tail"));
        final DirectByteBufferRecord record = DirectByteBufferRecord.get();
        try {
            TCPNIOUtils.fill(array, 16, record);
            record.finishBufferSlice();
            assertEquals(3, record.getArraySize());
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < record.getArraySize(); i++) {
                sb.append(content(record.getArray()[i]));
            }
            assertEquals("head-DIRECT-tail", sb.toString());
            // the first heap run is the direct buffer itself; after a direct component the next run is a view behind it
            assertSame(record.getDirectBuffer(), record.getArray()[0]);
            assertNotSame(record.getDirectBuffer(), record.getArray()[2]);
        } finally {
            record.release();
            array.restore();
            array.recycle();
        }
    }

    @Test
    public void laterHeapRunsFollowTheFirstOneInOrder() {
        final ByteBuffer d1 = ByteBuffer.allocateDirect(1);
        d1.put((byte) 'X').flip();
        final ByteBuffer d2 = ByteBuffer.allocateDirect(1);
        d2.put((byte) 'Y').flip();
        final BufferArray array = BufferArray.create();
        array.add(Buffers.wrap(MM, "aaaa"));
        array.add(Buffers.wrap(MM, d1));
        array.add(Buffers.wrap(MM, "bbbbbb"));
        array.add(Buffers.wrap(MM, d2));
        array.add(Buffers.wrap(MM, "cc")); // a second slice whose offset (10) is beyond the direct buffer's limit (4)
        final DirectByteBufferRecord record = DirectByteBufferRecord.get();
        try {
            TCPNIOUtils.fill(array, 14, record);
            record.finishBufferSlice();
            assertSame(record.getDirectBuffer(), record.getArray()[0]);
            assertEquals(5, record.getArraySize());
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < record.getArraySize(); i++) {
                sb.append(content(record.getArray()[i]));
            }
            assertEquals("aaaaXbbbbbbYcc", sb.toString());
            assertEquals(0, record.getArray()[0].position());
            assertEquals(4, record.getArray()[0].limit());
        } finally {
            record.release();
            array.restore();
            array.recycle();
        }
    }

    private static String content(final ByteBuffer buffer) {
        final ByteBuffer view = buffer.duplicate();
        final byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
