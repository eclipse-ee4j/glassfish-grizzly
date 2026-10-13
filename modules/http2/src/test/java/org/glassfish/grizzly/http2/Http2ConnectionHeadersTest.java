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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.function.Supplier;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.DefaultFilterChain;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.Test;

/**
 * Verifies that connection-specific header fields are rejected with a stream (RST_STREAM) error while decoding an
 * HTTP/2 request, as required by RFC 9113, section 8.2.2.
 */
public class Http2ConnectionHeadersTest {

    private final Http2Configuration configuration = Http2Configuration.builder().build();

    @Test
    public void connectionHeaderRejected() throws Exception {
        assertRejected("connection", "keep-alive");
    }

    @Test
    public void proxyConnectionHeaderRejected() throws Exception {
        assertRejected("proxy-connection", "keep-alive");
    }

    @Test
    public void keepAliveHeaderRejected() throws Exception {
        assertRejected("keep-alive", "timeout=5");
    }

    @Test
    public void transferEncodingHeaderRejected() throws Exception {
        assertRejected("transfer-encoding", "chunked");
    }

    @Test
    public void upgradeHeaderRejected() throws Exception {
        assertRejected("upgrade", "websocket");
    }

    private void assertRejected(final String name, final String value) throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final Http2Session session = newSession(memoryManager);

        final HeadersEncoder encoder = new HeadersEncoder(memoryManager, 4096);
        encoder.encodeHeader(":method", "POST", null);
        encoder.encodeHeader(":path", "/", null);
        encoder.encodeHeader(":scheme", "http", null);
        encoder.encodeHeader(":authority", "localhost", null);
        encoder.encodeHeader(name, value, null);
        final Buffer buffer = encoder.flushHeaders();

        session.getHeadersDecoder().append(buffer);

        try {
            DecoderUtils.decodeRequestHeaders(session, Http2Request.create(), null);
            fail("Expected a HeaderDecodingException for connection-specific header: " + name);
        } catch (HeaderDecodingException e) {
            assertEquals("Wrong error code for header: " + name, ErrorCode.PROTOCOL_ERROR, e.getErrorCode());
            assertEquals("Wrong error type for header: " + name, HeaderDecodingException.ErrorType.STREAM, e.getErrorType());
        }
    }

    private Http2Session newSession(final MemoryManager<?> memoryManager) throws Exception {
        final FilterChain filterChain = new DefaultFilterChain();
        final Filter sessionChainFilter = mock(Filter.class);
        doAnswer(invocation -> ((FilterChainContext) invocation.getArgument(0)).getInvokeAction()).when(sessionChainFilter)
                .handleWrite(any(FilterChainContext.class));
        filterChain.add(sessionChainFilter);
        filterChain.add(mock(Filter.class));

        final Connection<?> connection = mock(Connection.class);
        doReturn(filterChain).when(connection).getProcessor();
        doReturn(memoryManager).when(connection).getMemoryManager();
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get()).when(connection).obtainProcessorState(any(Processor.class),
                any(Supplier.class));

        final Http2Session session = new Http2Session(connection, true, new Http2ServerFilter(configuration));

        final FilterChainContext context = FilterChainContext.create(connection);
        context.getInternalContext().setProcessor(filterChain);
        context.setFilterIdx(1);
        context.setEndIdx(-1);
        session.setupFilterChains(context, false);

        return session;
    }
}
