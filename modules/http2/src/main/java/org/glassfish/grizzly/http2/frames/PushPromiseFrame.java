/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2.frames;

import java.util.Map;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;

public class PushPromiseFrame extends HeaderBlockHead {

    private static final ThreadCache.CachedTypeIndex<PushPromiseFrame> CACHE_IDX = ThreadCache.obtainIndex(PushPromiseFrame.class, 8);

    public static final int TYPE = 5;

    private int promisedStreamId;

    // ------------------------------------------------------------ Constructors

    private PushPromiseFrame() {
    }

    // ---------------------------------------------------------- Public Methods

    public static PushPromiseFrame fromBuffer(final int flags, final int streamId, final Buffer buffer) {
        final PushPromiseFrame frame = create();
        frame.setFlags(flags);
        frame.setStreamId(streamId);

        if (frame.isFlagSet(PADDED)) {
            frame.padLength = buffer.get() & 0xFF;
        }

        frame.promisedStreamId = buffer.getInt() & 0x7FFFFFFF;
        frame.compressedHeaders = buffer.split(buffer.position());
        frame.setFrameBuffer(buffer);

        return frame;
    }

    static PushPromiseFrame create() {
        PushPromiseFrame frame = ThreadCache.takeFromCache(CACHE_IDX);
        if (frame == null) {
            frame = new PushPromiseFrame();
        }

        return frame;
    }

    public static PushPromiseFrameBuilder builder() {
        return new PushPromiseFrameBuilder();
    }

    /**
     * Remove HeadersFrame padding (if it was applied).
     *
     * @return this HeadersFrame instance
     */
    public PushPromiseFrame normalize() {
        if (isPadded()) {
            clearFlag(PADDED);
            compressedHeaders.limit(compressedHeaders.limit() - padLength);
            padLength = 0;

            onPayloadUpdated();
        }

        return this;
    }

    public int getPromisedStreamId() {
        return promisedStreamId;
    }

    // -------------------------------------------------- Methods from Cacheable

    @Override
    public void recycle() {
        if (DONT_RECYCLE) {
            return;
        }

        promisedStreamId = 0;

        super.recycle();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    // -------------------------------------------------- Methods from Http2Frame
    @Override
    public int getType() {
        return TYPE;
    }

    @Override
    public Buffer toBuffer(final MemoryManager memoryManager) {
        final boolean isPadded = isFlagSet(PADDED);

        final Buffer buffer = memoryManager.allocate(FRAME_HEADER_SIZE + (isPadded ? 1 : 0) + 4);

        serializeFrameHeader(buffer);

        if (isPadded) {
            buffer.put((byte) (padLength & 0xff));
        }

        buffer.putInt(promisedStreamId);

        buffer.trim();
        final CompositeBuffer cb = CompositeBuffer.newBuffer(memoryManager, buffer, compressedHeaders);

        cb.allowBufferDispose(true);
        cb.allowInternalBuffersDispose(true);
        return cb;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("PushPromiseFrame {").append(headerToString()).append(", promisedStreamId=").append(promisedStreamId).append(", padLength=").append(padLength)
                .append(", compressedHeaders=").append(compressedHeaders).append('}');

        return sb.toString();
    }

    @Override
    protected int calcLength() {
        final boolean isPadded = isFlagSet(PADDED);

        // we consider compressedHeaders buffer already includes the padding (if any)
        return (isPadded ? 1 : 0) + 4 + (compressedHeaders != null ? compressedHeaders.remaining() : 0);
    }

    @Override
    protected Map<Integer, String> getFlagNamesMap() {
        return FLAG_NAMES_MAP;
    }

    // ---------------------------------------------------------- Nested Classes

    public static class PushPromiseFrameBuilder extends HeaderBlockHeadBuilder<PushPromiseFrameBuilder> {
        private int promisedStreamId;

        // -------------------------------------------------------- Constructors

        protected PushPromiseFrameBuilder() {
        }

        // ------------------------------------------------------ Public Methods

        public PushPromiseFrameBuilder promisedStreamId(int promisedStreamId) {
            this.promisedStreamId = promisedStreamId;
            return this;
        }

        @Override
        public PushPromiseFrame build() {
            final PushPromiseFrame frame = PushPromiseFrame.create();
            setHeaderValuesTo(frame);

            frame.compressedHeaders = compressedHeaders;
            frame.padLength = padLength;
            frame.promisedStreamId = promisedStreamId;

            return frame;
        }

        // --------------------------------------- Methods from HeaderBlockHeadBuilder

        @Override
        protected PushPromiseFrameBuilder getThis() {
            return this;
        }

    } // END HeadersFrameBuilder

}
