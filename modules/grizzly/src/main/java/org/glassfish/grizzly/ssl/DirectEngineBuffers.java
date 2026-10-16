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

package org.glassfish.grizzly.ssl;

import static org.glassfish.grizzly.ssl.SSLUtils.sslEngineUnwrap;
import static org.glassfish.grizzly.ssl.SSLUtils.sslEngineWrap;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/**
 * Per-thread direct buffers that the application data of a wrap or an unwrap is staged in for {@link SSLEngine}s
 * outside the JDK.
 * <p>
 * Such engines (e.g. the OpenSSL based one of Netty) work on native memory: for every heap source or destination they
 * allocate a direct buffer of their own, copy and release it, on every call. Some of them also turn every source
 * buffer of a gathering wrap into a record of its own, or wrap only the first one per call. Staging the data here
 * costs one copy of plain memory per direction and gives the engine one direct source and one direct destination: one
 * record per wrap and no allocation inside the engine.
 * <p>
 * Only platform threads keep a staging area: a virtual thread gets {@code null} and the caller hands its own buffers
 * to the engine as before.
 */
final class DirectEngineBuffers {

    /** Upper bound of each staging buffer: a record or its plaintext with room to spare. */
    static final int MAX_STAGED_BYTES = 64 * 1024;

    private static final ThreadLocal<DirectEngineBuffers> CACHE = ThreadLocal.withInitial(DirectEngineBuffers::new);

    private ByteBuffer source = ByteBuffer.allocateDirect(0);
    private ByteBuffer destination = ByteBuffer.allocateDirect(0);
    private boolean inUse;

    private DirectEngineBuffers() {
    }

    /**
     * @return this thread's staging area, or {@code null} on a virtual thread or while the area is already in use on
     * this thread. A non-null result must be given back with {@link #release()}.
     */
    static DirectEngineBuffers acquire() {
        if (Thread.currentThread().isVirtual()) {
            return null;
        }
        final DirectEngineBuffers buffers = CACHE.get();
        if (buffers.inUse) {
            return null;
        }
        buffers.inUse = true;
        return buffers;
    }

    void release() {
        inUse = false;
    }

    /**
     * Wraps at most {@code maxPlaintext} bytes of {@code srcs[0..srcsLength)}, gathered into one direct buffer, into
     * {@code dst}. The positions of the sources are advanced by the bytes the engine consumed, the one of {@code dst} by
     * the bytes it produced, as a gathering {@link SSLEngine#wrap(ByteBuffer[], int, int, ByteBuffer)} would do.
     */
    SSLEngineResult wrap(final SSLEngine engine, final ByteBuffer[] srcs, final int srcsLength, final int maxPlaintext, final ByteBuffer dst)
            throws SSLException {
        final ByteBuffer in = source(Math.min(maxPlaintext, MAX_STAGED_BYTES));
        for (int i = 0; i < srcsLength && in.hasRemaining(); i++) {
            final ByteBuffer src = srcs[i];
            final int position = src.position();
            final int limit = src.limit();
            src.limit(position + Math.min(src.remaining(), in.remaining()));
            in.put(src);
            src.limit(limit);
            src.position(position);
        }
        in.flip();

        final ByteBuffer out = dst.isDirect() ? dst : destination(dst.remaining());
        final SSLEngineResult result = sslEngineWrap(engine, in, out);

        int consumed = in.position();
        for (int i = 0; i < srcsLength && consumed > 0; i++) {
            final ByteBuffer src = srcs[i];
            final int n = Math.min(src.remaining(), consumed);
            src.position(src.position() + n);
            consumed -= n;
        }
        if (out != dst) {
            dst.put(out.flip());
        }
        return result;
    }

    /**
     * Unwraps {@code src} into {@code dst}, staging either one that is not direct (a source above
     * {@link #MAX_STAGED_BYTES} is passed as it is). The positions of {@code src} and
     * {@code dst} are advanced as {@link SSLEngine#unwrap(ByteBuffer, ByteBuffer)} would do.
     */
    SSLEngineResult unwrap(final SSLEngine engine, final ByteBuffer src, final ByteBuffer dst) throws SSLException {
        final ByteBuffer in;
        if (src.isDirect() || src.remaining() > MAX_STAGED_BYTES) {
            in = src;
        } else {
            in = source(src.remaining());
            final int position = src.position();
            in.put(src).flip();
            src.position(position);
        }

        final ByteBuffer out = dst.isDirect() ? dst : destination(dst.remaining());
        final SSLEngineResult result = sslEngineUnwrap(engine, in, out);

        if (in != src) {
            src.position(src.position() + in.position());
        }
        if (out != dst) {
            dst.put(out.flip());
        }
        return result;
    }

    /** A cleared direct buffer with exactly {@code size} bytes remaining. */
    private ByteBuffer source(final int size) {
        if (source.capacity() < size) {
            source = ByteBuffer.allocateDirect(size);
        }
        return source.clear().limit(size);
    }

    /** A cleared direct buffer with {@code size} bytes remaining, at most {@link #MAX_STAGED_BYTES}. */
    private ByteBuffer destination(int size) {
        size = Math.min(size, MAX_STAGED_BYTES);
        if (destination.capacity() < size) {
            destination = ByteBuffer.allocateDirect(size);
        }
        return destination.clear().limit(size);
    }
}
