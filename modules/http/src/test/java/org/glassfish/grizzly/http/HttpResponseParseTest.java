/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2024 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http;

import java.io.IOException;
import java.net.SocketAddress;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.StandaloneProcessor;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.streams.StreamWriter;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.glassfish.grizzly.utils.Pair;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Arrays.asList;
import static org.glassfish.grizzly.http.HttpCodecFilter.STRICT_HEADER_NAME_VALIDATION_RFC_9110;
import static org.glassfish.grizzly.http.HttpCodecFilter.STRICT_HEADER_VALUE_VALIDATION_RFC_9110;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Testing HTTP response parsing
 *
 * @author Alexey Stashok
 */
@RunWith(Parameterized.class)
public class HttpResponseParseTest {
    private static final Logger logger = Grizzly.logger(HttpResponseParseTest.class);

    private final boolean isStrictHeaderNameValidationSet;
    private final boolean isStrictHeaderValueValidationSet;
    private final String isStrictHeaderNameValidationSetBefore;
    private final String isStrictHeaderValueValidationSetBefore;

    @Parameterized.Parameters
    public static Collection<Object[]> getMode() {
        return asList(new Object[][] { { FALSE, FALSE }, { FALSE, TRUE }, { TRUE, FALSE }, { TRUE, TRUE } });
    }

    @Before
    public void before() throws Exception {
        if (isStrictHeaderNameValidationSet) {
            System.setProperty(STRICT_HEADER_NAME_VALIDATION_RFC_9110, String.valueOf(Boolean.TRUE));
        } else {
            System.setProperty(STRICT_HEADER_NAME_VALIDATION_RFC_9110, String.valueOf(Boolean.FALSE));
        }
        if (isStrictHeaderValueValidationSet) {
            System.setProperty(STRICT_HEADER_VALUE_VALIDATION_RFC_9110, String.valueOf(Boolean.TRUE));
        } else {
            System.setProperty(STRICT_HEADER_VALUE_VALIDATION_RFC_9110, String.valueOf(Boolean.FALSE));
        }
    }

    @After
    public void after() throws Exception {
        System.setProperty(STRICT_HEADER_NAME_VALIDATION_RFC_9110,
                           isStrictHeaderNameValidationSetBefore != null ? isStrictHeaderNameValidationSetBefore :
                           String.valueOf(Boolean.FALSE));
        System.setProperty(STRICT_HEADER_VALUE_VALIDATION_RFC_9110,
                           isStrictHeaderValueValidationSetBefore != null ? isStrictHeaderValueValidationSetBefore :
                           String.valueOf(Boolean.FALSE));
    }

    public HttpResponseParseTest(boolean isStrictHeaderNameValidationSet, boolean isStrictHeaderValueValidationSet) {
        this.isStrictHeaderNameValidationSet = isStrictHeaderNameValidationSet;
        this.isStrictHeaderValueValidationSet = isStrictHeaderValueValidationSet;
        this.isStrictHeaderNameValidationSetBefore = System.getProperty(STRICT_HEADER_NAME_VALIDATION_RFC_9110);
        this.isStrictHeaderValueValidationSetBefore = System.getProperty(STRICT_HEADER_VALUE_VALIDATION_RFC_9110);
    }

    @Test
    public void testHeaderlessResponseLine() throws Exception {
        doHttpResponseTest("HTTP/1.0", 200, "OK", Collections.<String, Pair<String, String>>emptyMap(), "\r\n");
    }

    @Test
    public void testSimpleHeaders() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Header1", new Pair<>("localhost", "localhost"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpResponseTest("HTTP/1.0", 200, "ALL RIGHT", headers, "\r\n");
    }

    @Test
    public void testMultiLineHeaders() throws Exception {
        if (isStrictHeaderValueValidationSet) {
            // Multiline headers should not be supported
            return;
        }
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Header1", new Pair<>("localhost", "localhost"));
        headers.put("Multi-line", new Pair<>("first\r\n          second\r\n       third", "first seconds third"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpResponseTest("HTTP/1.0", 200, "DONE", headers, "\r\n");
    }

    @Test
    public void testHeadersN() throws Exception {
        if (isStrictHeaderValueValidationSet) {
            // Multiline headers should not be supported
            return;
        }
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Header1", new Pair<>("localhost", "localhost"));
        headers.put("Multi-line", new Pair<>("first\n          second\n       third", "first seconds third"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpResponseTest("HTTP/1.0", 200, "DONE", headers, "\n");
    }

    @Test
    public void testDecoder100continueThen200() {
        try {
            doTestDecoder("HTTP/1.1 100 Continue\n\nHTTP/1.1 200 OK\n\n", 4096);
            assertTrue(true);
        } catch (IllegalStateException e) {
            logger.log(Level.SEVERE, "exception", e);
            assertTrue("Unexpected exception", false);
        }
    }

    @Test
    public void testDecoderOK() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 4096);
            assertTrue(true);
        } catch (IllegalStateException e) {
            logger.log(Level.SEVERE, "exception", e);
            assertTrue("Unexpected exception", false);
        }
    }

    @Test
    public void testDecoderOverflowProtocol() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 2);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    @Test
    public void testDecoderOverflowCode() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 11);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    @Test
    public void testDecoderOverflowPhrase() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 19);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    @Test
    public void testDecoderOverflowHeader() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\nHeader1: somevalue\r\n\n", 31);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    @Test
    public void testExpectHeaderInResponse() {
        // Expect is a request header; a response carrying it must still parse
        final HttpPacket packet = doTestDecoder("HTTP/1.1 200 OK\r\nExpect: 100-continue\r\nContent-Length: 0\r\n\r\n", 4096);
        final HttpHeader header = ((HttpContent) packet).getHttpHeader();
        assertEquals("100-continue", header.getHeader("Expect"));
    }

    @Test
    public void testExpectHeaderInResponseFromNonArrayBuffer() throws IOException {
        // a composite buffer has no backing array: the header is parsed from the Buffer, not from a byte[]
        final MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        final Buffer input = Buffers.appendBuffers(mm, Buffers.wrap(mm, "HTTP/1.1 200 OK\r\nExp"),
                Buffers.wrap(mm, "ect: 100-continue\r\nContent-Length: 0\r\n\r\n"));
        assertFalse(input.hasArray());
        final HttpClientFilter filter = new HttpClientFilter(4096);
        final FilterChainContext ctx = FilterChainContext.create(new StandaloneConnection());
        ctx.setMessage(input);
        filter.handleRead(ctx);
        final HttpHeader header = ((HttpContent) ctx.getMessage()).getHttpHeader();
        assertEquals("100-continue", header.getHeader("Expect"));
    }

    /**
     * A response that starts at a non-zero position of its buffer is parsed from that position, from a heap buffer
     * (byte[] path) and from a direct one (Buffer path).
     */
    @Test
    public void testResponseAtNonZeroBufferPosition() throws Exception {
        final byte[] prefix = "junk".getBytes(StandardCharsets.ISO_8859_1);
        final byte[] response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nX-A: b\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        for (final String kind : new String[] { "heap", "direct", "composite" }) {
            for (int split = 0; split < response.length; split++) {
                // split > 0: the response arrives in two reads, the second one appended to the first
                final Buffer first = HttpRequestParseTest.bufferAtPosition(prefix,
                        Arrays.copyOfRange(response, 0, split == 0 ? response.length : split), kind);
                final HttpClientFilter filter = new HttpClientFilter(4096);
                final StandaloneConnection connection = new StandaloneConnection();
                FilterChainContext ctx = FilterChainContext.create(connection);
                ctx.setMessage(first);
                filter.handleRead(ctx);
                if (split > 0) {
                    ctx = FilterChainContext.create(connection);
                    ctx.setMessage(Buffers.appendBuffers(MemoryManager.DEFAULT_MEMORY_MANAGER, first,
                            Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, Arrays.copyOfRange(response, split, response.length))));
                    filter.handleRead(ctx);
                }
                final String msg = kind + " split " + split;
                assertTrue(msg + ": " + ctx.getMessage(), ctx.getMessage() instanceof HttpContent);
                final HttpResponsePacket parsed = (HttpResponsePacket) ((HttpContent) ctx.getMessage()).getHttpHeader();
                assertEquals(msg, "HTTP/1.1", parsed.getProtocolString());
                assertEquals(msg, 404, parsed.getStatus());
                assertEquals(msg, "b", parsed.getHeader("X-A"));
            }
        }
    }

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private static final String[] BUFFER_KINDS = { "heap", "direct", "composite" };

    /**
     * Feeds the reads of one response to a {@link HttpClientFilter} like the filter chain does: the data of an incomplete
     * read is kept (the filter must ask for it with a StopAction) and the next read is appended to it.
     */
    private static final class Reads {
        final HttpClientFilter filter;
        final StandaloneConnection connection = new StandaloneConnection();
        final int[] headerSize = { -1 };
        Buffer stored;
        FilterChainContext ctx;

        Reads(final int maxHeaderSize) {
            filter = new HttpClientFilter(maxHeaderSize);
            filter.getMonitoringConfig().addProbes(new HttpProbe.Adapter() {
                @Override
                public void onHeaderParseEvent(final Connection connection, final HttpHeader header, final int size) {
                    headerSize[0] = size;
                }
            });
        }

        /** The next read, appended to the data kept by the filter, as {@link org.glassfish.grizzly.filterchain.DefaultFilterChain} does. */
        void readMore(final String msg, final byte[] data, final boolean complete) throws Exception {
            read(msg, Buffers.getBufferAppender(true).append(stored, Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, data)), complete);
        }

        void read(final String msg, final Buffer message, final boolean complete) throws Exception {
            ctx = FilterChainContext.create(connection);
            ctx.setMessage(message);
            final NextAction action = filter.handleRead(ctx);
            if (complete) {
                assertTrue(msg + ": " + ctx.getMessage(), ctx.getMessage() instanceof HttpContent);
                stored = null;
            } else {
                // StopAction is not visible from here
                assertEquals(msg + ": the action", "StopAction", action.getClass().getSimpleName());
                final Method getIncompleteChunk = action.getClass().getMethod("getIncompleteChunk");
                getIncompleteChunk.setAccessible(true);
                assertSame(msg + ": the incomplete chunk is the read buffer", message, getIncompleteChunk.invoke(action));
                assertEquals(msg + ": the incomplete chunk is kept from position 0", 0, message.position());
                stored = message;
            }
        }
    }

    private static byte[] concat(final byte[]... parts) {
        int length = 0;
        for (final byte[] part : parts) {
            length += part.length;
        }
        final byte[] result = new byte[length];
        int pos = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, result, pos, part.length);
            pos += part.length;
        }
        return result;
    }

    private static final byte[] CONTINUE_FINAL_RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-A: b\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Reads the stream, cut after the given indexes (a read each), and checks the final response and the size reported for
     * its header.
     */
    private static void doTestContinueReads(final String kind, final String prefix, final byte[] stream, final int... cuts) throws Exception {
        final Reads reads = new Reads(4096);
        final StringBuilder msg = new StringBuilder(kind).append(" prefix '").append(prefix).append("' cuts");
        for (final int cut : cuts) {
            msg.append(' ').append(cut);
        }
        int from = 0;
        for (int i = 0; i <= cuts.length; i++) {
            final boolean last = i == cuts.length;
            final byte[] chunk = Arrays.copyOfRange(stream, from, last ? stream.length : cuts[i]);
            if (i == 0) {
                reads.read(msg + " read 0", HttpRequestParseTest.bufferAtPosition(prefix.getBytes(StandardCharsets.ISO_8859_1), chunk, kind), last);
            } else {
                reads.readMore(msg + " read " + i, chunk, last);
            }
            from += chunk.length;
        }
        final HttpResponsePacket parsed = (HttpResponsePacket) ((HttpContent) reads.ctx.getMessage()).getHttpHeader();
        assertEquals(msg.toString(), 200, parsed.getStatus());
        assertEquals(msg.toString(), "b", parsed.getHeader("X-A"));
        // the header is the final response, not the 100 Continue before it
        assertEquals(msg + ": header size", CONTINUE_FINAL_RESPONSE.length, reads.headerSize[0]);
    }

    /**
     * One or two 100 Continue responses are read first, alone or together with the start of the final response, the rest
     * is read later, appended to the buffer kept for it. The final response is parsed whatever the kind of the buffer (the
     * bytes of the 100 are not removed from a direct one), whether bytes precede its position or not, wherever the reads
     * end: also inside the 100 itself (e.g. after its status line, or after the first byte of its closing CRLF, where the
     * parsing index is past the end of the data read so far), and in three reads.
     */
    @Test
    public void testContinueThenFinalResponse() throws Exception {
        for (final String kind : BUFFER_KINDS) {
            for (final String prefix : new String[] { "", "PREFIX\r\n" }) {
                for (int continues = 1; continues <= 2; continues++) {
                    final byte[] stream = continues == 1 ? concat(CONTINUE, CONTINUE_FINAL_RESPONSE)
                            : concat(CONTINUE, CONTINUE, CONTINUE_FINAL_RESPONSE);
                    // at least 3 bytes in the first read: the composite buffer is split there
                    for (int cut1 = 3; cut1 < stream.length; cut1++) {
                        doTestContinueReads(kind, prefix, stream, cut1);
                        // three reads
                        for (final int cut2 : new int[] { cut1 + 1, cut1 + 13, stream.length - 1 }) {
                            if (cut2 > cut1 && cut2 < stream.length) {
                                doTestContinueReads(kind, prefix, stream, cut1, cut2);
                            }
                        }
                    }
                    doTestContinueReads(kind, prefix, stream);
                    // explicit: the first read ends inside the closing CRLF of the first 100
                    doTestContinueReads(kind, prefix, stream, CONTINUE.length - 2);
                    doTestContinueReads(kind, prefix, stream, CONTINUE.length - 1);
                    doTestContinueReads(kind, prefix, stream, CONTINUE.length - 2, CONTINUE.length - 1);
                    doTestContinueReads(kind, prefix, stream, CONTINUE.length - 1, CONTINUE.length + 4, CONTINUE.length + 9);
                }
            }
        }
    }

    /** The final response with a header of the given size (it has a single header, a value of 'a's). */
    private static byte[] finalResponseOfSize(final int size) {
        final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\nX-A: ");
        while (sb.length() < size - 4) {
            sb.append('a');
        }
        return sb.append("\r\n\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * The limit on the size of the header counts from the start of the message, and is not moved by the 100 Continue
     * response: a final response that fits the limit alone is rejected after a 100 Continue and a prefix, for every kind of
     * buffer, because the header is too large, and the one that fits the limit after them is accepted.
     */
    @Test
    public void testHeaderSizeLimitAfterContinueAndPrefix() throws Exception {
        final byte[] prefix = "0123456789012345678901234567890123456789".getBytes(StandardCharsets.ISO_8859_1);
        final int maxHeaderSize = 64;
        // the limit is at prefix + maxHeaderSize: the parsing of the header, including its last CRLF for a byte array, has
        // to end by then
        final int packetLimit = prefix.length + maxHeaderSize;
        for (final String kind : BUFFER_KINDS) {
            // 1 byte under the limit, and at the limit
            for (final int total : new int[] { packetLimit - 1, packetLimit }) {
                final byte[] finalResponse = finalResponseOfSize(total - prefix.length - CONTINUE.length);
                assertEquals(total - prefix.length - CONTINUE.length, finalResponse.length);
                final Reads reads = new Reads(maxHeaderSize);
                reads.read(kind + " total " + total, HttpRequestParseTest.bufferAtPosition(prefix, concat(CONTINUE, finalResponse), kind), true);
                assertEquals(kind + " total " + total, 200, ((HttpResponsePacket) ((HttpContent) reads.ctx.getMessage()).getHttpHeader()).getStatus());
                assertEquals(kind + " total " + total, finalResponse.length, reads.headerSize[0]);
            }

            // over the limit: the value of the header ends beyond it (alone, the final response would fit the maximum)
            for (final int total : new int[] { packetLimit + 4, packetLimit + 11 }) {
                final byte[] finalResponse = finalResponseOfSize(total - prefix.length - CONTINUE.length);
                assertTrue(finalResponse.length < maxHeaderSize);
                final Reads reads = new Reads(maxHeaderSize);
                final String msg = kind + " total " + total;
                try {
                    reads.read(msg, HttpRequestParseTest.bufferAtPosition(prefix, concat(CONTINUE, finalResponse), kind), true);
                    fail(msg + ": the header must be rejected, got " + reads.ctx.getMessage());
                } catch (IllegalStateException expected) {
                    // the codec wraps the failure; it has to be the limit, not e.g. an initial line cut short
                    assertTrue(msg + ": " + expected.getCause(), expected.getCause() instanceof HttpHeaderTooLargeException);
                }
            }
        }
    }

    @SuppressWarnings({ "unchecked" })
    private HttpPacket doTestDecoder(String response, int limit) {

        MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        Buffer input = Buffers.wrap(mm, response);

        HttpClientFilter filter = new HttpClientFilter(limit);
        FilterChainContext ctx = FilterChainContext.create(new StandaloneConnection());
        ctx.setMessage(input);

        try {
            filter.handleRead(ctx);
            return (HttpPacket) ctx.getMessage();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private void doHttpResponseTest(String protocol, int code, String phrase, Map<String, Pair<String, String>> headers, String eol) throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final FutureImpl<Boolean> parseResult = SafeFutureImpl.create();

        Connection<SocketAddress> connection = null;
        StreamWriter writer;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new ChunkingFilter(2));
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new HTTPResponseCheckFilter(parseResult, protocol, code, phrase, Collections.<String, Pair<String, String>>emptyMap()));

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(port);
            transport.start();

            Future<Connection> future = transport.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            StringBuilder sb = new StringBuilder();

            sb.append(protocol).append(" ").append(Integer.toString(code)).append(" ").append(phrase).append(eol);

            for (Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue().getFirst()).append(eol);
            }

            sb.append(eol);

            byte[] message = sb.toString().getBytes();

            writer = StandaloneProcessor.INSTANCE.getStreamWriter(connection);

            writer.writeByteArray(message);
            Future<Integer> writeFuture = writer.flush();

            assertTrue("Write timeout", writeFuture.isDone());
            assertEquals(message.length, (int) writeFuture.get());

            assertTrue(parseResult.get(10, TimeUnit.SECONDS));
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public static class HTTPResponseCheckFilter extends BaseFilter {
        private final FutureImpl<Boolean> parseResult;
        private final String protocol;
        private final int code;
        private final String phrase;
        private final Map<String, Pair<String, String>> headers;

        public HTTPResponseCheckFilter(FutureImpl<Boolean> parseResult, String protocol, int code, String phrase, Map<String, Pair<String, String>> headers) {
            this.parseResult = parseResult;
            this.protocol = protocol;
            this.code = code;
            this.phrase = phrase;
            this.headers = headers;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            HttpContent httpContent = ctx.getMessage();
            HttpResponsePacket httpResponse = (HttpResponsePacket) httpContent.getHttpHeader();

            try {
                assertEquals(protocol, httpResponse.getProtocol().getProtocolString());
                assertEquals(code, httpResponse.getStatus());
                assertEquals(phrase, httpResponse.getReasonPhrase());

                for (Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                    assertEquals(entry.getValue().getSecond(), httpResponse.getHeader(entry.getKey()));
                }

                parseResult.result(Boolean.TRUE);
            } catch (Throwable e) {
                parseResult.failure(e);
            }

            return ctx.getStopAction();
        }
    }

    protected static final class StandaloneConnection extends NIOConnection {
        public StandaloneConnection() {
            super(TCPNIOTransportBuilder.newInstance().build());
        }

        @Override
        protected void preClose() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public SocketAddress getPeerAddress() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public SocketAddress getLocalAddress() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int getReadBufferSize() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void setReadBufferSize(int readBufferSize) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int getWriteBufferSize() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void setWriteBufferSize(int writeBufferSize) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void notifyCanWrite(WriteHandler handler) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void notifyCanWrite(WriteHandler handler, int length) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public boolean canWrite() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public boolean canWrite(int length) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

}
