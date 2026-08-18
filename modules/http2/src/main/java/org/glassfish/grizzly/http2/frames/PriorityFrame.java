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

import java.util.Collections;
import java.util.Map;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.MemoryManager;

public class PriorityFrame extends Http2Frame {
    private static final ThreadCache.CachedTypeIndex<PriorityFrame> CACHE_IDX = ThreadCache.obtainIndex(PriorityFrame.class, 8);

    public static final int TYPE = 2;

    private boolean isExclusive;
    private int streamDependency;
    private int weight;

    // ------------------------------------------------------------ Constructors

    private PriorityFrame() {
    }

    // ---------------------------------------------------------- Public Methods

    static PriorityFrame create() {
        PriorityFrame frame = ThreadCache.takeFromCache(CACHE_IDX);
        if (frame == null) {
            frame = new PriorityFrame();
        }
        return frame;
    }

    public static Http2Frame fromBuffer(final int streamId, final Buffer frameBuffer) {
        PriorityFrame frame = create();
        frame.setStreamId(streamId);

        frame.length = frameBuffer.remaining();
        final int dependency = frameBuffer.getInt();

        frame.streamDependency = dependency & 0x7fffffff;
        frame.isExclusive = (dependency & 1L << 31) != 0; // last bit is set

        frame.weight = frameBuffer.get() & 0xff;

        frame.setFrameBuffer(frameBuffer);

        return frame;
    }

    public static PriorityFrameBuilder builder() {
        return new PriorityFrameBuilder();
    }

    public int getStreamDependency() {
        return streamDependency;
    }

    public int getWeight() {
        return weight;
    }

    public boolean isExclusive() {
        return isExclusive;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("PriorityFrame {").append(headerToString()).append(", exclusive=").append(isExclusive).append(", streamDependency=").append(streamDependency)
                .append(", weight=").append(weight).append('}');

        return sb.toString();
    }

    // -------------------------------------------------- Methods from Http2Frame

    @Override
    public int getType() {
        return TYPE;
    }

    @Override
    public Buffer toBuffer(final MemoryManager memoryManager) {
        final Buffer buffer = memoryManager.allocate(FRAME_HEADER_SIZE + 5);

        serializeFrameHeader(buffer);
        buffer.putInt((isExclusive ? 0x80000000 : 0) | streamDependency & 0x7fffffff);
        buffer.put((byte) weight);

        buffer.trim();

        return buffer;
    }

    @Override
    protected int calcLength() {
        return 5;
    }

    @Override
    protected Map<Integer, String> getFlagNamesMap() {
        return Collections.emptyMap();
    }

    // -------------------------------------------------- Methods from Cacheable

    @Override
    public void recycle() {
        if (DONT_RECYCLE) {
            return;
        }

        isExclusive = false;
        streamDependency = 0;
        weight = 0;

        super.recycle();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    // ---------------------------------------------------------- Nested Classes

    public static class PriorityFrameBuilder extends Http2FrameBuilder<PriorityFrameBuilder> {

        private int streamDependency;
        private int weight;
        private boolean exclusive;

        // -------------------------------------------------------- Constructors

        protected PriorityFrameBuilder() {
        }

        // ------------------------------------------------------ Public Methods

        public PriorityFrameBuilder streamDependency(final int streamDependency) {
            this.streamDependency = streamDependency;
            return this;
        }

        public PriorityFrameBuilder weight(final int weight) {
            this.weight = weight;
            return this;
        }

        public PriorityFrameBuilder exclusive(final boolean exclusive) {
            this.exclusive = exclusive;
            return this;
        }

        @Override
        public PriorityFrame build() {
            final PriorityFrame frame = PriorityFrame.create();
            setHeaderValuesTo(frame);

            frame.streamDependency = streamDependency;
            frame.weight = weight;
            frame.isExclusive = exclusive;

            return frame;
        }

        // --------------------------------------- Methods from Http2FrameBuilder

        @Override
        protected PriorityFrameBuilder getThis() {
            return this;
        }

    }
}
