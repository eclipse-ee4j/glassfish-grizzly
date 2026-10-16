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

package org.glassfish.grizzly.ssl;

import static org.glassfish.grizzly.ssl.SSLUtils.SSL_CTX_ATTR;
import static org.glassfish.grizzly.ssl.SSLUtils.sslEngineUnwrap;
import static org.glassfish.grizzly.ssl.SSLUtils.sslEngineWrap;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferArray;
import org.glassfish.grizzly.memory.MemoryManager;

/**
 * SSL context associated with a {@link Connection}.
 *
 * @author Alexey Stashok
 */
public final class SSLConnectionContext {
    private static final Logger LOGGER = Grizzly.logger(SSLConnectionContext.class);
    private static final float BUFFER_SIZE_COEF;

    /** Lower bound for the per-record overhead of a wrap (header, MAC/tag, padding). */
    private static final int MIN_WRAP_OVERHEAD = 512;

    /** Engine classes that refused a destination sized for the data, see {@link #wrapBufferSize(Buffer, int)}. */
    private static final ClassValue<AtomicBoolean> NEEDS_PACKET_BUFFER = new ClassValue<AtomicBoolean>() {
        @Override
        protected AtomicBoolean computeValue(final Class<?> engineClass) {
            return new AtomicBoolean();
        }
    };

    static {
        final String coef = System.getProperty(SSLConnectionContext.class.getName(), "1.5");

        float coeff = 1.5f;

        try {
            coeff = Float.parseFloat(coef);
        } catch (NumberFormatException ignored) {
        }

        BUFFER_SIZE_COEF = coeff;
    }

    final ByteBufferArray outputByteBufferArray = ByteBufferArray.create();
    final ByteBufferArray inputByteBufferArray = ByteBufferArray.create();

    private Buffer lastOutputBuffer;
    /**
     * Application data decrypted while unwrapping handshake records, not yet passed upstream. Added and taken by the thread
     * processing the connection's input or handshake; taken atomically, as a close may dispose of it concurrently.
     */
    private volatile Buffer handshakeAppData;
    /** Set once the kept data has been disposed of (handshake failure or close): nothing is kept after that. */
    private volatile boolean handshakeAppDataDisposed;
    /**
     * System property with the most application data, in bytes, kept during one handshake (default 64 KiB). Above it
     * the handshake fails: a peer could otherwise send data records forever in the middle of a renegotiation it never
     * finishes.
     *
     * @since 5.0.4
     */
    public static final String MAX_HANDSHAKE_APP_DATA_PROPERTY = SSLConnectionContext.class.getName() + ".maxHandshakeAppData";

    static final int MAX_HANDSHAKE_APP_DATA = Integer.getInteger(MAX_HANDSHAKE_APP_DATA_PROPERTY, 64 * 1024);
    private static final AtomicReferenceFieldUpdater<SSLConnectionContext, Buffer> handshakeAppDataUpdater = AtomicReferenceFieldUpdater
            .newUpdater(SSLConnectionContext.class, Buffer.class, "handshakeAppData");
    private final InputBufferWrapper inputBuffer = new InputBufferWrapper();
    private InputBufferWrapper lastInputBuffer;

    private boolean isServerMode;
    private SSLEngine sslEngine;

    private volatile int appBufferSize;
    private volatile int netBufferSize;

    private final Connection connection;
    private FilterChain newConnectionFilterChain;

    public SSLConnectionContext(Connection connection) {
        this.connection = connection;
    }

    public SSLEngine getSslEngine() {
        return sslEngine;
    }

    public Connection getConnection() {
        return connection;
    }

    public void attach() {
        SSL_CTX_ATTR.set(connection, this);
    }

    public void configure(final SSLEngine sslEngine) {
        this.sslEngine = sslEngine;
        this.isServerMode = !sslEngine.getUseClientMode();
        updateBufferSizes();
    }

    public boolean isServerMode() {
        return isServerMode;
    }

    void updateBufferSizes() {
        final SSLSession session = sslEngine.getSession();
        appBufferSize = session.getApplicationBufferSize();
        netBufferSize = session.getPacketBufferSize();
    }

    public int getAppBufferSize() {
        return appBufferSize;
    }

    public int getNetBufferSize() {
        return netBufferSize;
    }

    public FilterChain getNewConnectionFilterChain() {
        return newConnectionFilterChain;
    }

    public void setNewConnectionFilterChain(FilterChain newConnectionFilterChain) {
        this.newConnectionFilterChain = newConnectionFilterChain;
    }

    /**
     * Keeps application data that an {@link javax.net.ssl.SSLEngine} decrypted while unwrapping handshake records, so
     * it can be passed upstream once the handshake is done. Engines that process several TLS records per unwrap (e.g.
     * OpenSSL/BoringSSL based ones) decrypt e.g. the first HTTP request together with the client's Finished message.
     *
     * @param src buffer holding the decrypted bytes
     * @param from index of the first decrypted byte
     * @param to index after the last decrypted byte
     */
    boolean addHandshakeAppData(final Buffer src, final int from, final int to) {
        final int length = to - from;
        Buffer data = takeHandshakeAppData();
        final int size = (data == null ? 0 : data.position()) + length;
        if (size > MAX_HANDSHAKE_APP_DATA) {
            if (data != null) {
                data.dispose();
            }
            return false;
        }
        if (data == null) {
            data = connection.getMemoryManager().allocate(Math.max(length, appBufferSize));
        } else if (data.remaining() < length) {
            data = connection.getMemoryManager().reallocate(data, Math.min(Math.max(size, data.capacity() * 2), MAX_HANDSHAKE_APP_DATA));
        }
        data.put(src, from, length);
        // left in write mode (position after the data): unwrapAll() appends the next decrypted records to it
        handshakeAppData = data;
        if (handshakeAppDataDisposed) {
            // disposed of concurrently (the connection was closed): don't keep anything
            disposeHandshakeAppData();
        }
        return true;
    }

    boolean hasHandshakeAppData() {
        return handshakeAppData != null;
    }

    Buffer takeHandshakeAppData() {
        return handshakeAppData == null ? null : handshakeAppDataUpdater.getAndSet(this, null);
    }

    void disposeHandshakeAppData() {
        handshakeAppDataDisposed = true;
        final Buffer data = takeHandshakeAppData();
        if (data != null) {
            data.dispose();
        }
    }

    Buffer resetLastOutputBuffer() {
        final Buffer tmp = lastOutputBuffer;
        lastOutputBuffer = null;
        return tmp;
    }

    @SuppressWarnings("unused")
    void setLastOutputBuffer(final Buffer lastOutputBuffer) {
        this.lastOutputBuffer = lastOutputBuffer;
    }

    InputBufferWrapper resetLastInputBuffer() {
        final InputBufferWrapper tmp = lastInputBuffer;
        lastInputBuffer = null;
        return tmp;
    }

    @SuppressWarnings("unused")
    InputBufferWrapper useInputBuffer() {
        lastInputBuffer = inputBuffer;
        return lastInputBuffer;
    }

    SslResult unwrap(int len, final Buffer input, Buffer output, final Allocator allocator) {

        output = ensureBufferSize(output, appBufferSize, allocator);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "unwrap engine: {0} input: {1} output: {2}", new Object[] { sslEngine, input, output });
        }

        final int inPos = input.position();
        final int outPos = output.position();

        final ByteBuffer inputByteBuffer = input.toByteBuffer(input.position(), input.position() + len);
        final int initPosition = inputByteBuffer.position();
        final SSLEngineResult sslEngineResult;

        try {
            if (!output.isComposite()) {
                sslEngineResult = sslEngineUnwrap(sslEngine, inputByteBuffer, output.toByteBuffer());

            } else {
                final ByteBufferArray bba = output.toByteBufferArray(this.outputByteBufferArray);
                final ByteBuffer[] outputArray = bba.getArray();

                try {
                    sslEngineResult = sslEngineUnwrap(sslEngine, inputByteBuffer, outputArray, 0, bba.size());
                } finally {
                    bba.restore();
                    bba.reset();
                }
            }
        } catch (SSLException e) {
            return new SslResult(output, e);
        }

        final Status status = sslEngineResult.getStatus();
        final boolean isOverflow = status == Status.BUFFER_OVERFLOW;

        if (allocator != null && isOverflow) {
            updateBufferSizes();
            output = ensureBufferSize(output, appBufferSize, allocator);
            return unwrap(len, input, output, null);
        } else if (isOverflow || status == Status.BUFFER_UNDERFLOW) {
            return new SslResult(output, new SSLException("SSL unwrap error: " + status));
        }

        input.position(inPos + inputByteBuffer.position() - initPosition); // GRIZZLY-1827 input.position(inPos + sslEngineResult.bytesConsumed());
        output.position(outPos + sslEngineResult.bytesProduced());

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "unwrap done engine: {0} result: {1} input: {2} output: {3}", new Object[] { sslEngine, sslEngineResult, input, output });
        }

        return new SslResult(output, sslEngineResult);
    }

    Buffer wrapAll(final Buffer input, final Allocator allocator) throws SSLException {
        final MemoryManager memoryManager = connection.getMemoryManager();

        final ByteBufferArray bba = input.toByteBufferArray(inputByteBufferArray);
        final ByteBuffer[] inputArray = bba.getArray();
        final int inputArraySize = bba.size();

        Buffer output = null;
        SslResult result = null;
        try {
            result = wrap(input, inputArray, inputArraySize, null, allocator);

            if (result.isError()) {
                throw result.getError();
            }

            output = result.getOutput();
            output.trim();

            if (input.hasRemaining()) {
                do {
                    result = wrap(input, inputArray, inputArraySize, null, allocator);
                    if (result.isError()) {
                        throw result.getError();
                    }
                    final Buffer newOutput = result.getOutput();
                    newOutput.trim();

                    output = Buffers.appendBuffers(memoryManager, output, newOutput);
                } while (input.hasRemaining());
            }

            return output;
        } finally {
            bba.restore();
            bba.reset();
            if (result != null && result.isError()) {
                if (output != null) {
                    output.dispose();
                }

                result.getOutput().dispose();
            }
        }
    }

    private SslResult wrap(final Buffer input, final ByteBuffer[] inputArray, final int inputArraySize, Buffer output, final Allocator allocator) {

        // allocated packet-sized as before (so the per-thread cached buffers stay reusable), but only the size computed
        // for the data is offered to the engine
        output = ensureBufferSize(output, netBufferSize, allocator);
        final int wrapBufferSize = wrapBufferSize(input, inputArraySize);
        final int offered = (int) (wrapBufferSize * BUFFER_SIZE_COEF);
        if (output.remaining() > offered) {
            output.limit(output.position() + offered);
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "wrap engine: {0} input: {1} output: {2}", new Object[] { sslEngine, input, output });
        }

        final int inPos = input.position();
        final int outPos = output.position();

        final ByteBuffer outputByteBuffer = output.toByteBuffer();
        final SSLEngineResult sslEngineResult;

        try {
            sslEngineResult = sslEngineWrap(sslEngine, inputArray, 0, inputArraySize, outputByteBuffer);
        } catch (SSLException e) {
            return new SslResult(output, e);
        }

        final Status status = sslEngineResult.getStatus();
        if (status == Status.CLOSED) {
            return new SslResult(output, new SSLException("SSLEngine is CLOSED"));
        }

        final boolean isOverflow = status == Status.BUFFER_OVERFLOW;
        if (allocator != null && isOverflow) {
            if (wrapBufferSize < netBufferSize) {
                // refused a destination sized for the data: this engine class wants room for a whole packet
                NEEDS_PACKET_BUFFER.get(sslEngine.getClass()).set(true);
            }
            if (outPos == 0) {
                // nothing was written into it: it is replaced
                output.dispose();
                output = null;
            }
            updateBufferSizes();
            output = ensureBufferSize(output, netBufferSize, allocator);
            return wrap(input, inputArray, inputArraySize, output, null);
        } else if (isOverflow || status == Status.BUFFER_UNDERFLOW) {
            return new SslResult(output, new SSLException("SSL wrap error: " + status));
        }

        input.position(inPos + sslEngineResult.bytesConsumed());
        output.position(outPos + sslEngineResult.bytesProduced());

        lastOutputBuffer = output;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "wrap done engine: {0} result: {1} input: {2} output: {3}", new Object[] { sslEngine, sslEngineResult, input, output });
        }

        return new SslResult(output, sslEngineResult);
    }

    SslResult wrap(final Buffer input, Buffer output, final Allocator allocator) {

        output = ensureBufferSize(output, netBufferSize, allocator);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "wrap engine: {0} input: {1} output: {2}", new Object[] { sslEngine, input, output });
        }

        final int inPos = input.position();
        final int outPos = output.position();

        final ByteBuffer outputByteBuffer = output.toByteBuffer();
        final SSLEngineResult sslEngineResult;

        try {
            if (!input.isComposite()) {
                sslEngineResult = sslEngineWrap(sslEngine, input.toByteBuffer(), outputByteBuffer);

            } else {
                final ByteBufferArray bba = input.toByteBufferArray(this.inputByteBufferArray);
                final ByteBuffer[] inputArray = bba.getArray();

                try {
                    sslEngineResult = sslEngineWrap(sslEngine, inputArray, 0, bba.size(), outputByteBuffer);
                } finally {
                    bba.restore();
                    bba.reset();
                }
            }
        } catch (SSLException e) {
            return new SslResult(output, e);
        }

        final Status status = sslEngineResult.getStatus();

        final boolean isOverflow = status == Status.BUFFER_OVERFLOW;

        if (allocator != null && isOverflow) {
            updateBufferSizes();
            output = ensureBufferSize(output, netBufferSize, allocator);
            return wrap(input, output, null);
        } else if (isOverflow || status == Status.BUFFER_UNDERFLOW) {
            return new SslResult(output, new SSLException("SSL wrap error: " + status));
        }

        input.position(inPos + sslEngineResult.bytesConsumed());
        output.position(outPos + sslEngineResult.bytesProduced());

        lastOutputBuffer = output;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "wrap done engine: {0} result: {1} input: {2} output: {3}", new Object[] { sslEngine, sslEngineResult, input, output });
        }

        return new SslResult(output, sslEngineResult);
    }

    /**
     * Engines that encrypt through a temporary direct buffer as large as the destination (e.g. Netty's OpenSSL engine
     * with a heap destination) would allocate and clear a packet-sized buffer for every small write: give them a
     * destination sized for the data instead, i.e. at most one record of plaintext plus the per-record overhead. An
     * engine class that refuses it once gets packet-sized destinations from then on.
     */
    private int wrapBufferSize(final Buffer input, final int inputArraySize) {
        if (NEEDS_PACKET_BUFFER.get(sslEngine.getClass()).get()) {
            return netBufferSize;
        }
        final int overheadPerRecord = Math.max(netBufferSize - appBufferSize, MIN_WRAP_OVERHEAD);
        final long size = (long) Math.min(input.remaining(), appBufferSize) + (long) overheadPerRecord * Math.max(1, inputArraySize);
        return (int) Math.min(size, netBufferSize);
    }

    private Buffer ensureBufferSize(Buffer output, final int size, final Allocator allocator) {
        final int sz = (int) (size * BUFFER_SIZE_COEF);

        if (output == null) {
            assert allocator != null;
            output = allocator.grow(this, null, sz);
        } else if (output.remaining() < sz) {
            assert allocator != null;
            // room for what is already in the buffer plus sz (the limit may be below the capacity)
            output = allocator.grow(this, output, output.position() + sz);
        }
        return output;
    }

    interface Allocator {
        Buffer grow(final SSLConnectionContext sslCtx, final Buffer oldBuffer, final int newSize);
    }

    final static class SslResult {
        private final Buffer output;
        private final SSLException error;
        private final SSLEngineResult sslEngineResult;

        public SslResult(final Buffer output, final SSLEngineResult sslEngineResult) {
            this.output = output;
            this.sslEngineResult = sslEngineResult;
            this.error = null;
        }

        public SslResult(final Buffer output, final SSLException error) {
            this.output = output;
            this.error = error;
            this.sslEngineResult = null;
        }

        public Buffer getOutput() {
            return output;
        }

        public boolean isError() {
            return error != null;
        }

        public SSLException getError() {
            return error;
        }

        public SSLEngineResult getSslEngineResult() {
            return sslEngineResult;
        }
    }
}
