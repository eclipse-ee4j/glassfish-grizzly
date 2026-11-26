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

package org.glassfish.grizzly.http2;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http2.frames.HeaderBlockHead;
import org.glassfish.grizzly.http2.hpack.Decoder;
import org.glassfish.grizzly.http2.hpack.DecodingCallback;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;

/**
 *
 * @author oleksiys
 */
public class HeadersDecoder {
    private final Decoder hpackDecoder;
    private final MemoryManager memoryManager;
    private final int maxHeaderSize;
    private int currentHeaderSize;

    private HeaderBlockHead firstHeaderFrame;
    private Buffer inBuffer;

    // @TODO Implement maxHeaderSize limitation handling
    public HeadersDecoder(final MemoryManager memoryManager, final int maxHeaderSize, final int maxHeaderTableSize) {
        this.memoryManager = memoryManager;
        this.maxHeaderSize = maxHeaderSize;
        this.hpackDecoder = new Decoder(maxHeaderTableSize);
    }

    /**
     * Appends a fragment of the header block being received.
     * <p>
     * The fragment that makes the block larger than the limit is kept as well: the block still has to be decoded to
     * keep the HPACK state of the connection consistent (RFC 9113, section 10.5.1), and the caller stops collecting the
     * block once this method returns {@code false}, so at most one fragment over the limit is kept.
     *
     * @return {@code false} if the header block is larger than the limit
     */
    public boolean append(final Buffer buffer) {
        currentHeaderSize += buffer.remaining();
        inBuffer = Buffers.appendBuffers(memoryManager, inBuffer, buffer, true);
        return currentHeaderSize <= maxHeaderSize;
    }

    public void decode(final DecodingCallback callback) {
        if (inBuffer != null) {
            hpackDecoder.decode(inBuffer, !isProcessingHeaders(), callback);

            inBuffer.tryDispose();
            inBuffer = null;
        }
    }

    public HeaderBlockHead finishHeader() {
        final HeaderBlockHead firstHeaderFrameLocal = firstHeaderFrame;
        firstHeaderFrame = null;
        currentHeaderSize = 0;
        return firstHeaderFrameLocal;
    }

    public void setFirstHeaderFrame(final HeaderBlockHead firstHeaderFrame) {
        this.firstHeaderFrame = firstHeaderFrame;
    }

    public boolean isProcessingHeaders() {
        return firstHeaderFrame != null;
    }

    /**
     * @return {@code true} if a received header block, or what is left of it, has not been decoded
     */
    boolean hasPendingHeaders() {
        return inBuffer != null;
    }
}
