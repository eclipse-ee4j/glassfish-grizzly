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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.Test;

/**
 * A request header block that is rejected with a stream error must still be decoded to its end: the HPACK dynamic
 * table is shared by all the streams of the connection (RFC 7541, section 2.3.2), so an entry that the peer added
 * and the server skipped makes the server resolve the indexes of the following header blocks to the wrong entries.
 */
public class HpackStateOnRejectedHeaderTest {

    @Test
    public void upperCaseNameKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("X-Upper", "value");
    }

    @Test
    public void connectionHeaderKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("connection", "keep-alive");
    }

    @Test
    public void teHeaderKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("te", "gzip");
    }

    @Test
    public void nulInValueKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("x-bad", "a\u0000b");
    }

    @Test
    public void crInValueKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("x-bad", "a\rb");
    }

    @Test
    public void leadingTabInValueKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("x-bad", "\tvalue");
    }

    @Test
    public void trailingTabInValueKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("x-bad", "value\t");
    }

    @Test
    public void emptyNameKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("", "value");
    }

    @Test
    public void invalidContentLengthKeepsTableInSync() throws Exception {
        assertRejectedAndInSync("content-length", "abc");
    }

    /**
     * RFC 9113, section 8.2.1 allows HTAB inside a field value.
     */
    @Test
    public void tabInsideValueIsAccepted() throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final Http2Session session = TestUtils.newServerSession(memoryManager);
        final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);

        encoder.requestPseudoHeaders("/");
        encoder.addIndexed("x-tab", "a\tb");
        final Http2Request request = decode(session, memoryManager, encoder.flush());
        assertEquals("a\tb", request.getHeader("x-tab"));

        // the next block refers to the entry by index
        encoder.requestPseudoHeaders("/");
        encoder.addIndexed("x-tab", "a\tb");
        assertEquals("a\tb", decode(session, memoryManager, encoder.flush()).getHeader("x-tab"));
    }

    private void assertRejectedAndInSync(final String badName, final String badValue) throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final Http2Session session = TestUtils.newServerSession(memoryManager);
        final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);

        // first block: the rejected field sits between two fields that the peer adds to its dynamic table
        encoder.requestPseudoHeaders("/");
        encoder.addIndexed("x-first", "one");
        encoder.addIndexed(badName, badValue);
        encoder.addIndexed("x-after", "two");
        try {
            decode(session, memoryManager, encoder.flush());
            fail("Expected a stream error for " + badName + ": " + badValue);
        } catch (HeaderDecodingException e) {
            assertEquals(ErrorCode.PROTOCOL_ERROR, e.getErrorCode());
            assertEquals(HeaderDecodingException.ErrorType.STREAM, e.getErrorType());
        }

        // second block: the peer refers to the entries of the first block by index
        encoder.requestPseudoHeaders("/");
        encoder.addIndexed("x-first", "one");
        encoder.addIndexed("x-after", "two");
        encoder.addIndexed("x-new", "three");
        final Http2Request request = decode(session, memoryManager, encoder.flush());

        assertEquals("x-first after rejecting " + badName, "one", request.getHeader("x-first"));
        assertEquals("x-after after rejecting " + badName, "two", request.getHeader("x-after"));
        assertEquals("x-new after rejecting " + badName, "three", request.getHeader("x-new"));
        assertEquals(1, count(request, "x-first"));
        assertEquals(1, count(request, "x-after"));
    }

    private static int count(final Http2Request request, final String name) {
        int n = 0;
        for (final String ignored : request.getHeaders().values(name)) {
            n++;
        }
        return n;
    }

    private static Http2Request decode(final Http2Session session, final MemoryManager<?> memoryManager, final byte[] block) throws Exception {
        session.getHeadersDecoder().append(Buffers.wrap(memoryManager, block));
        final Http2Request request = Http2Request.create();
        DecoderUtils.decodeRequestHeaders(session, request, null);
        return request;
    }
}
