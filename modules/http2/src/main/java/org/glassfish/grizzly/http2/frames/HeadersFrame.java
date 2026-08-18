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

package org.glassfish.grizzly.http2.frames;

import java.util.HashMap;
import java.util.Map;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;

public class HeadersFrame extends HeaderBlockHead {

    private static final ThreadCache.CachedTypeIndex<HeadersFrame> CACHE_IDX = ThreadCache.obtainIndex(HeadersFrame.class, 8);

    public static final int TYPE = 1;

    public static final byte END_STREAM = 0x1;
    public static final byte PRIORITIZED = 0x20;

    static final Map<Integer, String> FLAG_NAMES_MAP = new HashMap<>(8);

    static {
        FLAG_NAMES_MAP.putAll(HeaderBlockHead.FLAG_NAMES_MAP);
        FLAG_NAMES_MAP.put((int) END_STREAM, "END_STREAM");
        FLAG_NAMES_MAP.put((int) PRIORITIZED, "PRIORITIZED");
    }

    private boolean exclusive;
    private int streamDependency;
    private int weight;
    private int compressedHeadersLen;

    // ------------------------------------------------------------ Constructors

    private HeadersFrame() {
    }

    // ---------------------------------------------------------- Public Methods

    public static HeadersFrame fromBuffer(final int flags, final int streamId, final Buffer buffer) {
        final HeadersFrame frame = create();
        frame.setFlags(flags);
        frame.setStreamId(streamId);

        if (frame.isFlagSet(PADDED)) {
            frame.padLength = buffer.get() & 0xFF;
        }

        if (frame.isFlagSet(PRIORITIZED)) {
            final int dependency = buffer.getInt();
            frame.exclusive = (dependency & 1L << 31) != 0;
            frame.streamDependency = dependency & 0x7FFFFFFF;
            frame.weight = buffer.get() & 0xff;
        }

        frame.compressedHeaders = buffer.split(buffer.position());
        frame.compressedHeadersLen = frame.compressedHeaders.remaining();
        frame.setFrameBuffer(buffer);

        return frame;
    }

    static HeadersFrame create() {
        HeadersFrame frame = ThreadCache.takeFromCache(CACHE_IDX);
        if (frame == null) {
            frame = new HeadersFrame();
        }

        return frame;
    }

    public static HeadersFrameBuilder builder() {
        return new HeadersFrameBuilder();
    }

    /**
     * Remove HeadersFrame padding (if it was applied).
     *
     * @return this HeadersFrame instance
     */
    public HeadersFrame normalize() {
        if (isPadded()) {
            clearFlag(PADDED);
            compressedHeaders.limit(compressedHeaders.limit() - padLength);
            padLength = 0;

            onPayloadUpdated();
        }

        return this;
    }

    public int getStreamDependency() {
        return streamDependency;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public int getWeight() {
        return weight;
    }

    public boolean isEndStream() {
        return isFlagSet(END_STREAM);
    }

    public boolean isPrioritized() {
        return isFlagSet(PRIORITIZED);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("HeadersFrame {").append(headerToString()).append(", streamDependency=").append(streamDependency).append(", exclusive=").append(exclusive)
                .append(", weight=").append(weight).append(", padLength=").append(padLength).append(", compressedHeaders=").append(compressedHeaders)
                .append('}');
        return sb.toString();
    }

    // -------------------------------------------------- Methods from Cacheable

    @Override
    public void recycle() {
        if (DONT_RECYCLE) {
            return;
        }

        exclusive = false;
        streamDependency = 0;
        weight = 0;
        compressedHeadersLen = 0;

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
        final boolean isPrioritySet = isFlagSet(PRIORITIZED);

        final int extraHeaderLen = (isPadded ? 1 : 0) + (isPrioritySet ? 5 : 0);

        final Buffer buffer = memoryManager.allocate(FRAME_HEADER_SIZE + extraHeaderLen);

        serializeFrameHeader(buffer);

        if (isPadded) {
            buffer.put((byte) (padLength & 0xff));
        }

        if (isPrioritySet) {
            buffer.putInt(streamDependency);
            buffer.put((byte) (weight & 0xff));
        }

        buffer.trim();
        final CompositeBuffer cb = CompositeBuffer.newBuffer(memoryManager, buffer, compressedHeaders);

        cb.allowBufferDispose(true);
        cb.allowInternalBuffersDispose(true);
        return cb;
    }

    @Override
    protected int calcLength() {
        final boolean isPadded = isFlagSet(PADDED);
        final boolean isPrioritySet = isFlagSet(PRIORITIZED);

        // we consider compressedHeaders buffer already includes the padding (if any)
        return (isPadded ? 1 : 0) + (isPrioritySet ? 5 : 0) + compressedHeadersLen;
    }

    @Override
    protected Map<Integer, String> getFlagNamesMap() {
        return FLAG_NAMES_MAP;
    }

    // ---------------------------------------------------------- Nested Classes

    public static class HeadersFrameBuilder extends HeaderBlockHeadBuilder<HeadersFrameBuilder> {
        private int padLength;
        private int streamDependency;
        private int weight;

        // -------------------------------------------------------- Constructors

        protected HeadersFrameBuilder() {
        }

        // ------------------------------------------------------ Public Methods

        public HeadersFrameBuilder endStream(boolean endStream) {
            if (endStream) {
                setFlag(HeadersFrame.END_STREAM);
            }
            return this;
        }

        @Override
        public HeadersFrameBuilder padded(boolean isPadded) {
            if (isPadded) {
                setFlag(HeadersFrame.PADDED);
            }
            return this;
        }

        public HeadersFrameBuilder prioritized(boolean isPrioritized) {
            if (isPrioritized) {
                setFlag(HeadersFrame.PRIORITIZED);
            }
            return this;
        }

        @Override
        public HeadersFrameBuilder padLength(int padLength) {
            this.padLength = padLength;
            return this;
        }

        public HeadersFrameBuilder streamDependency(int streamDependency) {
            this.streamDependency = streamDependency;
            return this;
        }

        public HeadersFrameBuilder weight(int weight) {
            this.weight = weight;
            return this;
        }

        @Override
        public HeadersFrame build() {
            final HeadersFrame frame = HeadersFrame.create();
            setHeaderValuesTo(frame);

            frame.compressedHeaders = compressedHeaders;
            frame.compressedHeadersLen = compressedHeaders.remaining();
            frame.padLength = padLength;
            frame.streamDependency = streamDependency;
            frame.weight = weight;

            return frame;
        }

        // --------------------------------------- Methods from Http2FrameBuilder

        @Override
        protected HeadersFrameBuilder getThis() {
            return this;
        }

    } // END HeadersFrameBuilder

}
