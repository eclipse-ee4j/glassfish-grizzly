/*
 * Copyright (c) 2011, 2024 Oracle and/or its affiliates. All rights reserved.
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

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glassfish.grizzly.http.ChunkedTransferEncoding.STRICT_CHUNKED_TRANSFER_CODING_LINE_TERMINATOR_RFC_9112;
import static org.glassfish.grizzly.http.HttpCodecFilter.DEFAULT_MAX_HTTP_PACKET_HEADER_SIZE;
import static org.glassfish.grizzly.http.util.MimeHeaders.MAX_NUM_HEADERS_UNBOUNDED;
import static org.glassfish.grizzly.memory.Buffers.EMPTY_BUFFER;
import static org.glassfish.grizzly.memory.MemoryManager.DEFAULT_MEMORY_MANAGER;
import static org.glassfish.grizzly.utils.Charsets.ASCII_CHARSET;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.UnsafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.glassfish.grizzly.utils.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Chunked Transfer-Encoding tests.
 *
 * @author Alexey Stashok
 */
@RunWith(Parameterized.class)
public class ChunkedTransferEncodingTest {
    public static final int PORT = PORT();
    
    static int PORT() {
        try {
            int port = 19007 + SecureRandom.getInstanceStrong().nextInt(1000);
            System.out.println("Using port: " + port);
            return port;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final String eol;
    private final boolean isChunkWhenParsing;
    private final boolean isStrictChunkedTransferCodingLineTerminatorSet;

    private TCPNIOTransport transport;
    private Connection connection;
    private HTTPRequestCheckFilter httpRequestCheckFilter;

    final BlockingQueue<Future<Boolean>> resultQueue = new LinkedTransferQueue<>();

    @Parameters
    public static Collection<Object[]> getMode() {
        return asList(new Object[][] { { "\r\n", FALSE }, { "\r\n", TRUE }, { "\n", FALSE }, { "\n", TRUE } });
    }

    @Before
    public void before() throws Exception {
        Grizzly.setTrackingThreadCache(true);

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        if (isChunkWhenParsing) {
            filterChainBuilder.add(new ChunkingFilter(2));
        }
        HttpServerFilter httpServerFilter = new HttpServerFilter(true, DEFAULT_MAX_HTTP_PACKET_HEADER_SIZE, null, null, null,
                MAX_NUM_HEADERS_UNBOUNDED, MAX_NUM_HEADERS_UNBOUNDED);
        filterChainBuilder.add(httpServerFilter);
        httpRequestCheckFilter = new HTTPRequestCheckFilter(resultQueue);
        filterChainBuilder.add(httpRequestCheckFilter);

        transport = TCPNIOTransportBuilder.newInstance().build();
        transport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(-1);

        transport.setProcessor(filterChainBuilder.build());

        Thread.sleep(10);
        transport.bind(PORT);
        transport.start();

        FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
        clientFilterChainBuilder.add(new TransportFilter());

        SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChainBuilder.build()).build();

        Future<Connection> future = connectorHandler.connect("localhost", PORT);
        connection = future.get(10, SECONDS);
        assertTrue(connection != null);
    }

    @After
    public void after() throws Exception {
        if (connection != null) {
            connection.closeSilently();
        }

        if (transport != null) {
            try {
                transport.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    public ChunkedTransferEncodingTest(String eol, boolean isChunkWhenParsing) {
        this.eol = eol;
        this.isChunkWhenParsing = isChunkWhenParsing;
        this.isStrictChunkedTransferCodingLineTerminatorSet =
                Boolean.parseBoolean(System.getProperty(STRICT_CHUNKED_TRANSFER_CODING_LINE_TERMINATOR_RFC_9112));
    }

    @Test
    public void testNoTrailerHeaders() throws Exception {
        final int packetsNum = 5;

        doHttpRequestTest(packetsNum, true, Collections.<String, Pair<String, String>>emptyMap());

        for (int i = 0; i < packetsNum; i++) {
            Future<Boolean> result = resultQueue.poll(10, SECONDS);
            assertNotNull("Timeout for result#" + i, result);
            assertTrue(result.get(10, SECONDS));
        }

    }

    @Test
    public void testTrailerHeaders() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("X-Host", new Pair<>("localhost", "localhost"));
        headers.put("X-Content-length", new Pair<>("2345", "2345"));

        final int packetsNum = 5;

        doHttpRequestTest(packetsNum, true, headers);

        for (int i = 0; i < packetsNum; i++) {
            Future<Boolean> result = resultQueue.poll(10, SECONDS);
            assertNotNull("Timeout for result#" + i, result);
            assertTrue(result.get(10, SECONDS));
        }
    }

    @Test
    public void testTrailerHeadersWithoutContent() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("X-Host", new Pair<>("localhost", "localhost"));
        headers.put("X-Content-length", new Pair<>("2345", "2345"));

        final int packetsNum = 5;

        doHttpRequestTest(packetsNum, false, headers);

        for (int i = 0; i < packetsNum; i++) {
            Future<Boolean> result = resultQueue.poll(10, SECONDS);
            assertNotNull("Timeout for result#" + i, result);
            assertTrue(result.get(10, SECONDS));
        }
    }

    @Test
    public void testTrailerHeadersOverflow() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        // This number of headers should be enough to overflow socket's read buffer,
        // so trailer headers will not fit into socket read window
        for (int i = 0; i < DEFAULT_MAX_HTTP_PACKET_HEADER_SIZE; i++) {
            headers.put("X-Host-" + i, new Pair<>("localhost", "localhost"));
        }

        doHttpRequestTest(1, true, headers);

        Future<Boolean> result = resultQueue.poll(10, SECONDS);
        assertNotNull("Timeout", result);
        try {
            result.get(10, SECONDS);
            fail("Expected error");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof HttpBrokenContentException);
            assertEquals("The chunked encoding trailer header is too large", e.getCause().getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testInvalidHexByteInChunkLength() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("POST / HTTP/1.1\r\n");
        sb.append("Host: localhost:").append(PORT).append("\r\n");
        sb.append("Transfer-Encoding: chunked\r\n\r\n");
        sb.append((char) 193).append("\r\n");

        Buffer b = Buffers.wrap(DEFAULT_MEMORY_MANAGER, sb.toString(), Charsets.ASCII_CHARSET);
        Future f = connection.write(b);
        f.get(10, SECONDS);
        Future<Boolean> result = resultQueue.poll(10, SECONDS);
        try {
            result.get(10, SECONDS);
            fail("Expected HttpBrokenContentException to be thrown on server side");
        } catch (ExecutionException ee) {
            assertEquals(HttpBrokenContentException.class, ee.getCause().getClass());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSpacesInChunkSizeHeader() throws Exception {
        final String msg = "abc";
        final String msgLen = Integer.toHexString(msg.length());

        httpRequestCheckFilter.setCheckParameters(Buffers.wrap(connection.getMemoryManager(), msg), Collections.<String, Pair<String, String>>emptyMap());

        StringBuilder sb = new StringBuilder();
        sb.append("POST / HTTP/1.1\r\n");
        sb.append("Host: localhost:").append(PORT).append("\r\n");
        sb.append("Transfer-Encoding: chunked\r\n\r\n");
        sb.append("  ").append(msgLen).append("  ").append("\r\n").append(msg).append(eol);
        sb.append("  0  ").append("\r\n").append(eol);

        Buffer b = Buffers.wrap(DEFAULT_MEMORY_MANAGER, sb.toString(), Charsets.ASCII_CHARSET);
        Future f = connection.write(b);
        f.get(10, SECONDS);
        Future<Boolean> result = resultQueue.poll(10, SECONDS);
        assertTrue(result.get(10, SECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testVulnerableLineTerminatorInChunkSizeHeader() throws Exception {
        StringBuilder sb = new StringBuilder();
        String nestedMsg = "XX";
        String nestedMsgLen = Integer.toHexString(nestedMsg.length());
        sb.append("\r\n");
        sb.append("POST /2 HTTP/1.1").append("\r\n");
        sb.append("Host: localhost:").append(PORT).append("\r\n");
        sb.append("Transfer-Encoding: chunked").append("\r\n");
        sb.append("\r\n");
        sb.append(nestedMsgLen).append("\r\n");
        sb.append(nestedMsg).append("\r\n");
        String dummy = sb.toString();
        String firstMsg = "A".repeat(dummy.length());
        final String firstMsgLen = Integer.toHexString(firstMsg.length());

        // original packet
        sb = new StringBuilder();
        sb.append("POST /1 HTTP/1.1").append("\r\n");
        sb.append("Host: localhost:").append(PORT).append("\r\n");
        sb.append("Transfer-Encoding: chunked").append("\r\n");
        sb.append("\r\n");
        sb.append(firstMsgLen).append(';').append('\n').append(firstMsg).append('\n').append('0').append("\r\n");
        sb.append(dummy);
        sb.append("0").append("\r\n"); // last-chunk
        sb.append("\r\n"); // CRLF

        final Buffer expectedContent = Buffers.wrap(DEFAULT_MEMORY_MANAGER, firstMsg, ASCII_CHARSET);
        httpRequestCheckFilter.setCheckParameters(expectedContent, Collections.<String, Pair<String, String>>emptyMap());
        Buffer b = Buffers.wrap(DEFAULT_MEMORY_MANAGER, sb.toString(), Charsets.ASCII_CHARSET);
        Future f = connection.write(b);
        f.get(5, SECONDS);

        Future<Boolean> result;
        if (!isStrictChunkedTransferCodingLineTerminatorSet) {
            // first msg
            result = resultQueue.poll(5, SECONDS);
            assertTrue(result.get(2, SECONDS));

            // nested msg
            result = resultQueue.poll(5, SECONDS);
            try {
                result.get(2, SECONDS);
                fail("Expected AssertError to be thrown on server side");
            } catch (ExecutionException ignore) {
            }
        } else {
            // first msg
            result = resultQueue.poll(5, SECONDS);
            try {
                result.get(2, SECONDS);
                fail("Expected HttpBrokenContentException to be thrown on server side");
            } catch (ExecutionException ee) {
                assertEquals(HttpBrokenContentException.class, ee.getCause().getClass());
            }
        }
    }

    /**
     * Test private method {@link ChunkedTransferEncoding#checkOverflow(long)} via reflection.
     *
     * @throws Exception
     */
    public void testChunkLenOverflow() throws Exception {
        final java.lang.reflect.Method method = ChunkedTransferEncoding.class.getDeclaredMethod("checkOverflow", Long.class);
        method.setAccessible(true);

        final long cornerValue = Long.MAX_VALUE >> 4;

        final long value1 = cornerValue;
        assertTrue(value1 << 4 > 0);
        assertTrue((Boolean) method.invoke(null, value1));

        final long value2 = cornerValue + 1;
        assertFalse(value2 << 4 > 0);
        assertFalse((Boolean) method.invoke(null, value1));
    }

    @SuppressWarnings("unchecked")
    private void doHttpRequestTest(int packetsNum, boolean hasContent, Map<String, Pair<String, String>> trailerHeaders) throws Exception {

        final Buffer content;
        if (hasContent) {
            content = Buffers.wrap(DEFAULT_MEMORY_MANAGER, "a=0&b=1", ASCII_CHARSET);
        } else {
            content = EMPTY_BUFFER;
        }

        httpRequestCheckFilter.setCheckParameters(content, trailerHeaders);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < packetsNum; i++) {
            sb.append("POST / HTTP/1.1")
              .append(eol)
              .append("Host: localhost:")
              .append(PORT)
              .append("\r\n")
              .append("Transfer-encoding: chunked")
              .append("\r\n")
              .append("Content-Type: application/x-www-form-urlencoded").append("\r\n");

            if (i == packetsNum - 1) {
                sb.append("Connection: close").append("\r\n");
            }

            sb.append(eol);

            if (hasContent) {
                sb.append("3").append("\r\n").append("a=0").append(eol).append("4").append("\r\n").append("&b=1").append(eol);
            }

            sb.append("0").append("\r\n");

            for (Entry<String, Pair<String, String>> entry : trailerHeaders.entrySet()) {
                final String value = entry.getValue().getFirst();
                if (value != null) {
                    sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
                }
            }

            sb.append(eol);
        }

        connection.write(Buffers.wrap(transport.getMemoryManager(), sb.toString(), Charsets.ASCII_CHARSET));
    }

    public static class HTTPRequestCheckFilter extends BaseFilter {
        private final Queue<Future<Boolean>> resultQueue;
        private volatile Buffer content;
        private volatile Map<String, Pair<String, String>> trailerHeaders;

        public HTTPRequestCheckFilter(Queue<Future<Boolean>> resultQueue) {
            this.resultQueue = resultQueue;
        }

        public void setCheckParameters(Buffer content, Map<String, Pair<String, String>> trailerHeaders) {
            this.content = content;
            this.trailerHeaders = trailerHeaders;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final HttpContent httpContent = ctx.getMessage();
            if (!httpContent.isLast()) {
                return ctx.getStopAction(httpContent);
            }

            try {
                assertEquals(content, httpContent.getContent());
                assertTrue(HttpTrailer.isTrailer(httpContent));
                final HttpTrailer httpTrailer = (HttpTrailer) httpContent;
                for (Entry<String, Pair<String, String>> entry : trailerHeaders.entrySet()) {
                    assertEquals(entry.getValue().getSecond(), httpTrailer.getHeader(entry.getKey()));
                }

                FutureImpl<Boolean> future = UnsafeFutureImpl.create();
                future.result(TRUE);
                resultQueue.offer(future);
            } catch (Throwable e) {
                FutureImpl<Boolean> future = UnsafeFutureImpl.create();
                future.failure(e);
                resultQueue.offer(future);
            }

            return ctx.getStopAction();
        }
    }
}
