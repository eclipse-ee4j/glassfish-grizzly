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

package org.glassfish.grizzly.ssl;

import static org.glassfish.grizzly.ssl.SSLUtils.SSL_CTX_ATTR;
import static org.glassfish.grizzly.ssl.SSLUtils.sslEngineUnwrap;
import static org.glassfish.grizzly.ssl.SSLUtils.sslEngineWrap;

import java.nio.ByteBuffer;
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
     * Application data decrypted by the unwrap that completed the handshake, not yet passed upstream.
     */
    private Buffer handshakeAppData;
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
    void addHandshakeAppData(final Buffer src, final int from, final int to) {
        final int length = to - from;
        Buffer data = handshakeAppData;
        if (data == null) {
            data = connection.getMemoryManager().allocate(length);
        } else if (data.remaining() < length) {
            data = connection.getMemoryManager().reallocate(data, data.position() + length);
        }
        data.put(src, from, length);
        // left in write mode (position after the data): unwrapAll() appends the next decrypted records to it
        handshakeAppData = data;
    }

    boolean hasHandshakeAppData() {
        return handshakeAppData != null;
    }

    Buffer takeHandshakeAppData() {
        final Buffer data = handshakeAppData;
        handshakeAppData = null;
        return data;
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

        output = ensureBufferSize(output, netBufferSize, allocator);

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

    private Buffer ensureBufferSize(Buffer output, final int size, final Allocator allocator) {
        final int sz = (int) (size * BUFFER_SIZE_COEF);

        if (output == null) {
            assert allocator != null;
            output = allocator.grow(this, null, sz);
        } else if (output.remaining() < sz) {
            assert allocator != null;
            output = allocator.grow(this, output, output.capacity() + sz - output.remaining());
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
