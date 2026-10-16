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
 * take a direct buffer of their own, copy and release it, on every call. Some of them also wrap only the first source
 * buffer of a gathering wrap per call, one record each. Staging the data here costs one copy of plain memory per
 * direction and gives the engine one direct source and one direct destination: one record per wrap and no buffer
 * management inside the engine.
 * <p>
 * The staging area of a thread is an array of JDK types only, so it pins no class loader. Only platform threads keep
 * one: on a virtual thread, while the area is in use on the thread, for a buffer above {@link #MAX_STAGED_BYTES} or when
 * direct memory runs out, the engine gets the caller's buffers as before.
 */
final class DirectEngineBuffers {

    /** Upper bound of each staging buffer: a record or its plaintext with room to spare. */
    static final int MAX_STAGED_BYTES = 64 * 1024;

    private static final int SOURCE = 0;
    private static final int DESTINATION = 1;
    private static final int IN_USE = 2;
    private static final ByteBuffer IN_USE_MARK = ByteBuffer.allocate(0);
    private static final ByteBuffer NONE = ByteBuffer.allocateDirect(0);

    private static final ThreadLocal<ByteBuffer[]> AREA = ThreadLocal.withInitial(() -> new ByteBuffer[] { NONE, NONE, null });

    private DirectEngineBuffers() {
    }

    /**
     * @return this thread's staging area, or {@code null} on a virtual thread or while the area is already in use on
     * this thread. A non-null result must be given back with {@link #release(ByteBuffer[])}.
     */
    static ByteBuffer[] acquire() {
        if (Thread.currentThread().isVirtual()) {
            return null;
        }
        final ByteBuffer[] area = AREA.get();
        if (area[IN_USE] != null) {
            return null;
        }
        area[IN_USE] = IN_USE_MARK;
        return area;
    }

    static void release(final ByteBuffer[] area) {
        area[IN_USE] = null;
    }

    /**
     * Wraps at most {@code maxPlaintext} bytes (at most {@link #MAX_STAGED_BYTES}, all of them if {@code maxPlaintext}
     * is not positive) of {@code srcs[0..srcsLength)}, gathered into one direct buffer, into {@code dst}. The positions
     * of the sources and of {@code dst} are advanced as a gathering {@link SSLEngine#wrap(ByteBuffer[], int, int,
     * ByteBuffer)} would do.
     *
     * @return the engine's result, or {@code null} if direct memory ran out: then nothing was passed to the engine
     */
    static SSLEngineResult wrap(final ByteBuffer[] area, final SSLEngine engine, final ByteBuffer[] srcs, final int srcsLength,
            final int maxPlaintext, final ByteBuffer dst) throws SSLException {
        final ByteBuffer out = destination(area, dst);
        if (out == null) {
            return null;
        }

        final boolean gather = srcsLength != 1 || !srcs[0].isDirect();
        final ByteBuffer in;
        if (!gather) {
            in = srcs[0];
        } else {
            long remaining = 0;
            for (int i = 0; i < srcsLength; i++) {
                remaining += srcs[i].remaining();
            }
            final int size = (int) Math.min(remaining, maxPlaintext > 0 ? Math.min(maxPlaintext, MAX_STAGED_BYTES) : MAX_STAGED_BYTES);
            in = buffer(area, SOURCE, size);
            if (in == null) {
                return null;
            }
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
        }

        final SSLEngineResult result = sslEngineWrap(engine, in, out);

        if (gather) {
            int consumed = in.position();
            for (int i = 0; i < srcsLength && consumed > 0; i++) {
                final ByteBuffer src = srcs[i];
                final int n = Math.min(src.remaining(), consumed);
                src.position(src.position() + n);
                consumed -= n;
            }
        }
        copyProduced(out, dst, result);
        return result;
    }

    /**
     * Unwraps {@code src} into {@code dst}, staging either one that is not direct. The positions of {@code src} and
     * {@code dst} are advanced as {@link SSLEngine#unwrap(ByteBuffer, ByteBuffer)} would do.
     *
     * @return the engine's result, or {@code null} if direct memory ran out: then nothing was passed to the engine
     */
    static SSLEngineResult unwrap(final ByteBuffer[] area, final SSLEngine engine, final ByteBuffer src, final ByteBuffer dst) throws SSLException {
        final ByteBuffer out = destination(area, dst);
        if (out == null) {
            return null;
        }

        final ByteBuffer in;
        if (src.isDirect() || src.remaining() > MAX_STAGED_BYTES) {
            in = src;
        } else {
            in = buffer(area, SOURCE, src.remaining());
            if (in == null) {
                return null;
            }
            final int position = src.position();
            in.put(src).flip();
            src.position(position);
        }

        final SSLEngineResult result = sslEngineUnwrap(engine, in, out);

        if (in != src) {
            src.position(src.position() + in.position());
        }
        copyProduced(out, dst, result);
        return result;
    }

    /** {@code dst} itself if it is direct or above the bound, else a staging buffer with as many bytes remaining. */
    private static ByteBuffer destination(final ByteBuffer[] area, final ByteBuffer dst) {
        return dst.isDirect() || dst.remaining() > MAX_STAGED_BYTES ? dst : buffer(area, DESTINATION, dst.remaining());
    }

    /**
     * Copies what the engine produced into a staged destination to {@code dst}: as many bytes as it reported or, if its
     * position moved further, as its position moved (the caller advances the Grizzly buffer by the reported count).
     */
    private static void copyProduced(final ByteBuffer out, final ByteBuffer dst, final SSLEngineResult result) {
        if (out == dst) {
            return;
        }
        final int produced = Math.min(out.limit(), Math.max(out.position(), result.bytesProduced()));
        dst.put(out.limit(produced).position(0));
    }

    /** The cleared buffer of the slot with exactly {@code size} bytes remaining, or {@code null} if it can't grow. */
    private static ByteBuffer buffer(final ByteBuffer[] area, final int slot, final int size) {
        ByteBuffer buffer = area[slot];
        if (buffer.capacity() < size) {
            try {
                buffer = ByteBuffer.allocateDirect(size);
            } catch (OutOfMemoryError e) {
                // direct memory is exhausted (-XX:MaxDirectMemorySize): let the engine work on the caller's buffers
                return null;
            }
            area[slot] = buffer;
        }
        return buffer.clear().limit(size);
    }
}
