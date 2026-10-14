/*
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

package org.glassfish.grizzly.nio.transport;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.memory.BufferArray;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferArray;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.DirectByteBufferRecord;
import org.glassfish.grizzly.utils.Exceptions;

/**
 * TCP NIO Transport utils
 *
 * @author Alexey Stashok
 */
public class TCPNIOUtils {
    static final Logger LOGGER = TCPNIOTransport.LOGGER;

    public static int writeCompositeBuffer(final TCPNIOConnection connection, final CompositeBuffer buffer) throws IOException {

        final int bufferSize = calcWriteBufferSize(connection, buffer.remaining());

        final int oldPos = buffer.position();
        final int oldLim = buffer.limit();
        buffer.limit(oldPos + bufferSize);

        final SocketChannel socketChannel = (SocketChannel) connection.getChannel();

        int written = 0;
        final BufferArray bufferArray = buffer.toBufferArray();
        final DirectByteBufferRecord ioRecord = DirectByteBufferRecord.get();

        try {
            fill(bufferArray, bufferSize, ioRecord);
            ioRecord.finishBufferSlice();

            final int arraySize = ioRecord.getArraySize();
            written = arraySize != 1 ? flushByteBuffers(socketChannel, ioRecord.getArray(), 0, arraySize)
                    : flushByteBuffer(socketChannel, ioRecord.getArray()[0]);

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "TCPNIOConnection ({0}) (composite) write {1} bytes", new Object[] { connection, written });
            }
        } finally {
            ioRecord.release();

            bufferArray.restore();
            bufferArray.recycle();
        }

        Buffers.setPositionLimit(buffer, oldPos + written, oldLim);
        return written;
    }

    public static int writeSimpleBuffer(final TCPNIOConnection connection, final Buffer buffer) throws IOException {
        final SocketChannel socketChannel = (SocketChannel) connection.getChannel();
        final int oldPos = buffer.position();
        final int oldLim = buffer.limit();

        final int written;

        if (buffer.isDirect()) {
            final ByteBuffer directByteBuffer = buffer.toByteBuffer();
            final int pos = directByteBuffer.position();

            try {
                written = flushByteBuffer(socketChannel, directByteBuffer);
            } finally {
                directByteBuffer.position(pos);
            }
        } else {
            final int bufferSize = calcWriteBufferSize(connection, buffer.remaining());
            buffer.limit(oldPos + bufferSize);

            final DirectByteBufferRecord ioRecord = DirectByteBufferRecord.get();
            final ByteBuffer directByteBuffer = ioRecord.allocate(bufferSize);
            fill(buffer, bufferSize, directByteBuffer);

            try {
                written = flushByteBuffer(socketChannel, directByteBuffer);
            } finally {
                ioRecord.release();
            }
        }

        Buffers.setPositionLimit(buffer, oldPos + written, oldLim);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "TCPNIOConnection ({0}) (plain) write {1} bytes", new Object[] { connection, written });
        }
        return written;
    }

    public static int flushByteBuffer(final SocketChannel channel, final ByteBuffer byteBuffer) throws IOException {
        return channel.write(byteBuffer);
    }

    public static int flushByteBuffers(final SocketChannel channel, final ByteBuffer byteBuffer[], final int firstBufferOffest, final int numberOfBuffers)
            throws IOException {
        return (int) channel.write(byteBuffer, firstBufferOffest, numberOfBuffers);
    }

    private static void fill(Buffer src, int size, ByteBuffer dstByteBuffer) {
        dstByteBuffer.limit(size);
        int oldPos = src.position();
        src.get(dstByteBuffer);
        dstByteBuffer.position(0);
        src.position(oldPos);
    }

    static void fill(final BufferArray bufferArray, final int totalBufferSize, final DirectByteBufferRecord ioRecord) {

        final Buffer buffers[] = bufferArray.getArray();
        final int size = bufferArray.size();

        int totalRemaining = totalBufferSize;

        for (int i = 0; i < size; i++) {

            final Buffer buffer = buffers[i];
            assert !buffer.isComposite();

            final int bufferSize = buffer.remaining();
            if (bufferSize == 0) {
                continue;
            }

            if (buffer.isDirect()) {
                ioRecord.finishBufferSlice();
                ioRecord.putToArray(buffer.toByteBuffer());
            } else {
                ByteBuffer currentDirectBufferSlice = ioRecord.getDirectBufferSlice();

                if (currentDirectBufferSlice == null) {
                    final ByteBuffer directByteBuffer = ioRecord.getDirectBuffer();
                    if (directByteBuffer == null) {
                        ioRecord.allocate(totalRemaining); // allocate buffer big enough to put the entire message (not just the chunk we're writing)
                        // allocate() makes the whole (cleared) direct buffer the current slice: no need for a slice() view
                        currentDirectBufferSlice = ioRecord.getDirectBufferSlice();
                    }

                    if (currentDirectBufferSlice == null) {
                        currentDirectBufferSlice = ioRecord.sliceBuffer();
                    }
                }

                final int oldLim = currentDirectBufferSlice.limit();
                currentDirectBufferSlice.limit(currentDirectBufferSlice.position() + bufferSize);
                buffer.get(currentDirectBufferSlice);
                currentDirectBufferSlice.limit(oldLim);
            }

            totalRemaining -= bufferSize;
        }

    }

    private static int calcWriteBufferSize(final TCPNIOConnection connection, final int bufferSize) {
        return Math.min(TCPNIOTransport.MAX_SEND_BUFFER_SIZE, Math.min(bufferSize, connection.getWriteBufferSize() * 3 / 2));
    }

    public static Buffer allocateAndReadBuffer(final TCPNIOConnection connection) throws IOException {

        final MemoryManager memoryManager = connection.getMemoryManager();

        int read;
        Throwable error = null;
        Buffer buffer = null;

        try {
            final int receiveBufferSize = Math.min(TCPNIOTransport.MAX_RECEIVE_BUFFER_SIZE, connection.getReadBufferSize());

            if (!memoryManager.willAllocateDirect(receiveBufferSize)) {
                final DirectByteBufferRecord ioRecord = DirectByteBufferRecord.get();
                final ByteBuffer directByteBuffer = ioRecord.allocate(receiveBufferSize);

                try {
                    read = readSimpleByteBuffer(connection, directByteBuffer);
                    if (read > 0) {
                        directByteBuffer.flip();
                        buffer = memoryManager.allocate(read);
                        buffer.put(directByteBuffer);
                    }
                } finally {
                    ioRecord.release();
                }
            } else {
                buffer = memoryManager.allocateAtLeast(receiveBufferSize);
                read = readBuffer(connection, buffer);
            }
        } catch (Throwable e) {
            error = e;
            read = -1;
        }

        if (read > 0) {
            buffer.position(read);
            buffer.allowBufferDispose(true);
        } else {
            if (buffer != null) {
                buffer.dispose();
            }

            if (read < 0) {
                // noinspection ThrowableResultOfMethodCallIgnored
                throw error != null ? Exceptions.makeIOException(error) : new EOFException();
            }

            buffer = Buffers.EMPTY_BUFFER;
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "TCPNIOConnection ({0}) (allocated) read {1} bytes", new Object[] { connection, read });
        }
        return buffer;
    }

    public static int readBuffer(final TCPNIOConnection connection, final Buffer buffer) throws IOException {
        return buffer.isComposite() ? readCompositeBuffer(connection, (CompositeBuffer) buffer) : readSimpleBuffer(connection, buffer);

    }

    public static int readCompositeBuffer(final TCPNIOConnection connection, final CompositeBuffer buffer) throws IOException {

        final SocketChannel socketChannel = (SocketChannel) connection.getChannel();
        final int oldPos = buffer.position();
        final ByteBufferArray array = buffer.toByteBufferArray();
        final ByteBuffer byteBuffers[] = array.getArray();
        final int size = array.size();

        final int read = (int) socketChannel.read(byteBuffers, 0, size);

        array.restore();
        array.recycle();

        if (read > 0) {
            buffer.position(oldPos + read);
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "TCPNIOConnection ({0}) (nonallocated, composite) read {1} bytes", new Object[] { connection, read });
        }

        return read;
    }

    public static int readSimpleBuffer(final TCPNIOConnection connection, final Buffer buffer) throws IOException {

        final SocketChannel socketChannel = (SocketChannel) connection.getChannel();
        final int oldPos = buffer.position();
        final ByteBuffer byteBuffer = buffer.toByteBuffer();
        final int bbOldPos = byteBuffer.position();

        final int read = socketChannel.read(byteBuffer);

        if (read > 0) {
            byteBuffer.position(bbOldPos);
            buffer.position(oldPos + read);
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "TCPNIOConnection ({0}) (nonallocated, simple) read {1} bytes", new Object[] { connection, read });
        }

        return read;
    }

    private static int readSimpleByteBuffer(final TCPNIOConnection tcpConnection, final ByteBuffer byteBuffer) throws IOException {

        final SocketChannel socketChannel = (SocketChannel) tcpConnection.getChannel();
        return socketChannel.read(byteBuffer);

    }

}
