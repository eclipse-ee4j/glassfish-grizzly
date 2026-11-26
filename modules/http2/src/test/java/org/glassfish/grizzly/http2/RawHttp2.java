/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http2.hpack.Decoder;
import org.glassfish.grizzly.http2.hpack.DecodingCallback;
import org.glassfish.grizzly.http2.hpack.Encoder;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;

/**
 * Minimal HTTP/2 framing for tests that need to control the frames and the HPACK representations on the wire.
 */
final class RawHttp2 {

    static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    static final int DATA = 0x0;
    static final int HEADERS = 0x1;
    static final int RST_STREAM = 0x3;
    static final int SETTINGS = 0x4;
    static final int GOAWAY = 0x7;
    static final int CONTINUATION = 0x9;

    static final int END_STREAM = 0x1;
    static final int ACK = 0x1;
    static final int END_HEADERS = 0x4;

    private RawHttp2() {
    }

    static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        Frame(final int type, final int flags, final int streamId, final byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }

        boolean isEndStream() {
            return (type == DATA || type == HEADERS) && (flags & END_STREAM) != 0;
        }

        @Override
        public String toString() {
            return "Frame[type=" + type + " flags=" + flags + " stream=" + streamId + " length=" + payload.length + "]";
        }
    }

    static void writeFrame(final OutputStream out, final int type, final int flags, final int streamId, final byte[] payload) throws IOException {
        final byte[] header = new byte[9];
        header[0] = (byte) (payload.length >>> 16);
        header[1] = (byte) (payload.length >>> 8);
        header[2] = (byte) payload.length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) (streamId >>> 24 & 0x7F);
        header[6] = (byte) (streamId >>> 16);
        header[7] = (byte) (streamId >>> 8);
        header[8] = (byte) streamId;
        out.write(header);
        out.write(payload);
        out.flush();
    }

    static Frame readFrame(final InputStream in) throws IOException {
        final DataInputStream din = new DataInputStream(in);
        final byte[] header = new byte[9];
        din.readFully(header);
        final int length = (header[0] & 0xFF) << 16 | (header[1] & 0xFF) << 8 | header[2] & 0xFF;
        final int streamId = (header[5] & 0x7F) << 24 | (header[6] & 0xFF) << 16 | (header[7] & 0xFF) << 8 | header[8] & 0xFF;
        final byte[] payload = new byte[length];
        din.readFully(payload);
        return new Frame(header[3] & 0xFF, header[4] & 0xFF, streamId, payload);
    }

    /**
     * An HPACK encoder that adds every new field to the dynamic table and refers to it by index afterwards, as peers
     * such as reverse proxies do. The Grizzly encoder does not index fields.
     */
    static final class IndexingEncoder extends Encoder {
        private final MemoryManager<?> memoryManager;
        // the fields added to the dynamic table; it is large enough that none is evicted in the tests
        private final Set<String> indexed = new HashSet<>();
        private Buffer buffer;

        IndexingEncoder(final MemoryManager<?> memoryManager) {
            super(4096);
            this.memoryManager = memoryManager;
        }

        IndexingEncoder requestPseudoHeaders(final String path) {
            add(":method", "GET");
            add(":scheme", "http");
            add(":path", path);
            add(":authority", "localhost");
            return this;
        }

        /**
         * Encodes the field with the representation the encoder chooses; this one does not index.
         */
        IndexingEncoder add(final String name, final String value) {
            header(name, value);
            write();
            return this;
        }

        IndexingEncoder addIndexed(final String name, final String value) {
            if (indexed.contains(name + '\n' + value)) {
                header(name, value); // encoded as an index into the dynamic table
            } else {
                literalWithIndexing(name, false, value, false);
                indexed.add(name + '\n' + value);
            }
            write();
            return this;
        }

        private void write() {
            if (buffer == null) {
                buffer = memoryManager.allocate(4096);
            }
            while (!encode(buffer)) {
                buffer = Buffers.appendBuffers(memoryManager, buffer, memoryManager.allocate(4096));
            }
        }

        byte[] flush() {
            final Buffer result = buffer;
            buffer = null;
            result.trim();
            final byte[] bytes = new byte[result.remaining()];
            result.get(bytes);
            return bytes;
        }
    }

    /**
     * Decodes header blocks received from the peer; the order of the blocks has to be kept.
     */
    static final class BlockDecoder {
        private final MemoryManager<?> memoryManager;
        private final Decoder decoder = new Decoder(4096);

        BlockDecoder(final MemoryManager<?> memoryManager) {
            this.memoryManager = memoryManager;
        }

        Map<String, String> decode(final byte[] block) {
            final Map<String, String> fields = new LinkedHashMap<>();
            decoder.decode(Buffers.wrap(memoryManager, block), true, new DecodingCallback() {
                @Override
                public void onDecoded(final CharSequence name, final CharSequence value) {
                    fields.put(name.toString(), value.toString());
                }
            });
            return fields;
        }
    }
}
