/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Testing HTTP request parsing
 *
 * @author Alexey Stashok
 */
@RunWith(Parameterized.class)
public class HttpRequestParseTest {

    public static final int PORT = 19000;

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

    public HttpRequestParseTest(boolean isStrictHeaderNameValidationSet, boolean isStrictHeaderValueValidationSet) {
        this.isStrictHeaderNameValidationSet = isStrictHeaderNameValidationSet;
        this.isStrictHeaderValueValidationSet = isStrictHeaderValueValidationSet;
        this.isStrictHeaderNameValidationSetBefore = System.getProperty(STRICT_HEADER_NAME_VALIDATION_RFC_9110);
        this.isStrictHeaderValueValidationSetBefore = System.getProperty(STRICT_HEADER_VALUE_VALIDATION_RFC_9110);
    }

    @Test
    public void testCustomMethod() throws Exception {
        doHttpRequestTest("TAKE", "/index.html", "HTTP/1.0", Collections.<String, Pair<String, String>>emptyMap(), "\r\n");
    }

    @Test
    public void testHeaderlessRequestLine() throws Exception {
        doHttpRequestTest("GET", "/index.html", "HTTP/1.0", Collections.<String, Pair<String, String>>emptyMap(), "\r\n");
    }

    @Test
    public void testSimpleHeaders() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<>("localhost", "localhost"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest("POST", "/index.html", "HTTP/1.1", headers, "\r\n");
    }

    @Test
    public void testSimpleHeadersPreserveCase() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<>("localhost", "localhost"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest("POST", "/index.html", "HTTP/1.1", headers, "\r\n", true);
    }

    @Test
    public void testLeadingOrTrailingWhitespaceFromHeaderContentValues() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<>("localhost", "localhost"));
        headers.put("Leading-OWS-Header1", new Pair<>("  some-value", "some-value"));
        headers.put("Leading-OWS-Header2", new Pair<>("\t\tsome-value", "some-value"));
        headers.put("Trailing-OWS-Header1", new Pair<>("some-value  ", "some-value"));
        headers.put("Trailing-OWS-Header2", new Pair<>("some-value\t\t", "some-value"));
        headers.put("OWS-Header", new Pair<>(" \t \t some-value \t \t ", "some-value"));
        headers.put("Mixed-OWS-Header", new Pair<>(" \t \t some- \t \t value \t \t ", "some- \t \t value"));
        doHttpRequestTest("POST", "/index.html", "HTTP/1.1", headers, "\r\n");
    }

    @Test
    public void testDisallowedCharactersForHeaderNames() {
        if (!isStrictHeaderNameValidationSet) {
            return;
        }
        final StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        sb.append(new char[]{0x00, 0x01, 0x02, '\t', '\n', '\r', ' ', '\"', '(', ')', '/', ';', '<', '=', '>', '?', '@',
                             '[', 0x5c, ']', '{', '}'}).append(": some-value\r\n");
        sb.append("\r\n");
        try {
            doTestDecoder(sb.toString(), 128);
            fail("Bad HTTP headers exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            doTestDecoder("GET /index.html HTTP/1.1\nHost: localhost\r\nContent -Length: 1234\r\n\n", 128);
            fail("Bad HTTP headers exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            doTestDecoder("GET /index.html HTTP/1.1\nHost: localhost\r\nContent-\rLength: 1234\r\n\n", 128);
            fail("Bad HTTP headers exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testDisallowedCharactersForHeaderContentValues() {
        if (!isStrictHeaderValueValidationSet) {
            return;
        }
        final StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        sb.append("Some-Header: some-");
        // valid header values
        sb.append(new char[]{'\t', ' ', '\"', '(', ')', '/', ';', '<', '=', '>', '?', '@', '[', 0x5c, ']', '{', '}'})
          .append("\r\n");
        sb.append("\r\n");
        doTestDecoder(sb.toString(), 128);

        try {
            doTestDecoder("GET /index.html HTTP/1.1\nHost: loca\rlhost\r\nContent-Length: 1234\r\n\n", 128);
            fail("Bad HTTP headers exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            doTestDecoder("GET /index.html HTTP/1.1\nHost: loca\nlhost\r\nContent-Length: 1234\r\n\n", 128);
            fail("Bad HTTP headers exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }

        final char[] invalidChars = new char[]{0x00, 0x01, 0x02, '\r', '\n'};
        for (final char ch : invalidChars) {
            try {
                doTestDecoder("GET /index.html HTTP/1.1\nHost: localhost\r\nSome-Header: some-" + ch + "value\r\n\n", 128);
                fail("Bad HTTP headers exception had to be thrown");
            } catch (IllegalStateException e) {
                // expected
            }
        }
    }

    @Test
    public void testIgnoredHeaders() throws Exception {
        if (!isStrictHeaderNameValidationSet) {
            return;
        }
        final Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<>("localhost", "localhost"));
        headers.put("Ignore\r\nContent-length", new Pair<>("2345", "2345"));
        final Map<String, Pair<String, String>> expectedHeaders = new HashMap<>();
        expectedHeaders.put("Host", new Pair<>("localhost", "localhost"));
        expectedHeaders.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest("POST", "/index.html", "HTTP/1.1", headers, expectedHeaders, "\r\n");
    }

    @Test
    public void testMultiLineHeaders() throws Exception {
        if (isStrictHeaderValueValidationSet) {
            // Multiline headers should not be supported
            return;
        }
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<>("localhost", "localhost"));
        headers.put("Multi-line", new Pair<>("first\r\n          second\r\n       third", "first second third"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest("POST", "/index.html", "HTTP/1.1", headers, "\r\n");
    }

    @Test
    public void testHeadersN() throws Exception {
        if (isStrictHeaderValueValidationSet) {
            // Multiline headers should not be supported
            return;
        }
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<>("localhost", "localhost"));
        headers.put("Multi-line", new Pair<>("first\r\n          second\n       third", "first second third"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest("POST", "/index.html", "HTTP/1.1", headers, "\n");
    }

    @Test
    public void testCompleteURI() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<String, String>(null, "localhost:8180"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest(new Pair<>("POST", "POST"), new Pair<>("http://localhost:8180/index.html", "/index.html"),
                new Pair<>("HTTP/1.1", "HTTP/1.1"), headers, "\r\n", false);
    }

    @Test
    public void testCompleteEmptyURI() throws Exception {
        Map<String, Pair<String, String>> headers = new HashMap<>();
        headers.put("Host", new Pair<String, String>(null, "localhost:8180"));
        headers.put("Content-length", new Pair<>("2345", "2345"));
        doHttpRequestTest(new Pair<>("POST", "POST"), new Pair<>("http://localhost:8180", "/"),
                new Pair<>("HTTP/1.1", "HTTP/1.1"), headers, "\r\n", false);
    }

    @Test
    public void testDecoderOK() {
        doTestDecoder("GET /index.html HTTP/1.0\n\n", 4096);
    }

    @Test
    public void testDecoderOverflowMethod() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 2);
            fail("Overflow exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testDecoderOverflowURI() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 8);
            fail("Overflow exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testDecoderOverflowProtocol() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 19);
            fail("Overflow exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testDecoderOverflowHeader1() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\nHost: localhost\r\n\n", 42);
            fail("Overflow exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testDecoderOverflowHeader2() {
        doTestDecoder("GET /index.html HTTP/1.0\nHost: localhost\r\n\n", 43);
    }

    @Test
    public void testDecoderOverflowHeader3() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\nHost: localhost\r\n\r\n", 43);
            fail("Overflow exception had to be thrown");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testDecoderOverflowHeader4() {
        doTestDecoder("GET /index.html HTTP/1.0\nHost: localhost\r\n\r\n", 44);
    }

    @Test
    public void testChunkedTransferEncodingCaseInsensitive() {
        HttpPacket packet = doTestDecoder("POST /index.html HTTP/1.1\nHost: localhost\r\nTransfer-Encoding: CHUNked\r\n\r\n0\r\n\r\n", 4096);
        assertTrue(packet.getHttpHeader().isChunked());
    }

    @SuppressWarnings({ "unchecked" })
    private HttpPacket doTestDecoder(String request, int limit) {

        MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        Buffer input = Buffers.wrap(mm, request);

        HttpServerFilter filter = new HttpServerFilter(true, limit, null, null) {

            @Override
            protected void onHttpHeaderError(final HttpHeader httpHeader, final FilterChainContext ctx, final Throwable t) throws IOException {
                throw new IllegalStateException(t);
            }
        };
        FilterChainContext ctx = FilterChainContext.create(new StandaloneConnection());
        ctx.setMessage(input);

        try {
            filter.handleRead(ctx);
            return (HttpPacket) ctx.getMessage();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private void doHttpRequestTest(String method, String requestURI, String protocol, Map<String, Pair<String, String>> headers, String eol) throws Exception {
        doHttpRequestTest(new Pair<>(method, method), new Pair<>(requestURI, requestURI),
                new Pair<>(protocol, protocol), headers, eol, false);
    }

    private void doHttpRequestTest(String method, String requestURI, String protocol,
                                   Map<String, Pair<String, String>> headers,
                                   Map<String, Pair<String, String>> expectedHeaders, String eol) throws Exception {
        doHttpRequestTest(new Pair<>(method, method), new Pair<>(requestURI, requestURI),
                          new Pair<>(protocol, protocol), headers, expectedHeaders, eol, false);
    }

    private void doHttpRequestTest(String method, String requestURI, String protocol, Map<String, Pair<String, String>> headers, String eol,
            boolean preserveCase) throws Exception {
        doHttpRequestTest(new Pair<>(method, method), new Pair<>(requestURI, requestURI),
                new Pair<>(protocol, protocol), headers, eol, preserveCase);
    }

    private void doHttpRequestTest(Pair<String, String> method, Pair<String, String> requestURI, Pair<String, String> protocol,
            Map<String, Pair<String, String>> headers, String eol, boolean preserveHeaderCase) throws Exception {
        doHttpRequestTest(method, requestURI, protocol, headers, headers, eol, preserveHeaderCase);
    }

    @SuppressWarnings("unchecked")
    private void doHttpRequestTest(Pair<String, String> method, Pair<String, String> requestURI,
                                   Pair<String, String> protocol, Map<String, Pair<String, String>> headers,
                                   Map<String, Pair<String, String>> expectedHeaders, String eol,
                                   boolean preserveHeaderCase) throws Exception {
        final FutureImpl<Boolean> parseResult = SafeFutureImpl.create();

        Connection connection = null;

        final HttpServerFilter serverFilter = new HttpServerFilter();
        serverFilter.setPreserveHeaderCase(preserveHeaderCase);

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless().add(new TransportFilter()).add(new ChunkingFilter(2)).add(serverFilter)
                .add(new HTTPRequestCheckFilter(parseResult, method, requestURI, protocol, expectedHeaders == null ? headers : expectedHeaders, preserveHeaderCase));

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(PORT);
            transport.start();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless().add(new TransportFilter());

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChainBuilder.build()).build();

            Future<Connection> future = connectorHandler.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            StringBuilder sb = new StringBuilder();

            sb.append(method.getFirst()).append(" ").append(requestURI.getFirst()).append(" ").append(protocol.getFirst()).append(eol);

            for (Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                final String value = entry.getValue().getFirst();
                if (value != null) {
                    sb.append(entry.getKey()).append(": ").append(value).append(eol);
                }
            }

            sb.append(eol);

            final Buffer message = Buffers.wrap(transport.getMemoryManager(), sb.toString());
            final int messageLen = message.remaining();
            Future<WriteResult> writeFuture = connection.write(message);

            assertEquals(messageLen, writeFuture.get().getWrittenSize());

            assertTrue(parseResult.get(10, TimeUnit.SECONDS));
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public static class HTTPRequestCheckFilter extends BaseFilter {
        private final FutureImpl<Boolean> parseResult;
        private final String method;
        private final String requestURI;
        private final String protocol;
        private final Map<String, Pair<String, String>> headers;
        private final boolean preserveCase;

        public HTTPRequestCheckFilter(FutureImpl<Boolean> parseResult, Pair<String, String> method, Pair<String, String> requestURI,
                Pair<String, String> protocol, Map<String, Pair<String, String>> headers, boolean preserveCase) {
            this.parseResult = parseResult;
            this.method = method.getSecond();
            this.requestURI = requestURI.getSecond();
            this.protocol = protocol.getSecond();
            this.headers = headers;
            this.preserveCase = preserveCase;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            HttpContent httpContent = ctx.getMessage();
            HttpRequestPacket httpRequest = (HttpRequestPacket) httpContent.getHttpHeader();

            try {
                assertEquals(method, httpRequest.getMethod().getMethodString());
                assertEquals(requestURI, httpRequest.getRequestURI());
                assertEquals(protocol, httpRequest.getProtocol().getProtocolString());

                MimeHeaders mimeHeaders = httpRequest.getHeaders();
                _outer: for (String original : headers.keySet()) {
                    for (String name : mimeHeaders.names()) {
                        if (preserveCase) {
                            if (original.equals(name)) {
                                continue _outer;
                            }
                        } else {
                            if (original.equalsIgnoreCase(name)) {
                                continue _outer;
                            }
                        }
                    }
                    fail(String.format("Unable to find header %s in headers %s", original, mimeHeaders));
                }

                for (Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                    assertEquals(entry.getValue().getSecond(), httpRequest.getHeader(entry.getKey()));
                }

                parseResult.result(Boolean.TRUE);
            } catch (Throwable e) {
                parseResult.failure(e);
            }

            return ctx.getStopAction();
        }
    }

    protected static final class StandaloneConnection extends NIOConnection {

        private final SocketAddress localAddress;
        private final SocketAddress peerAddress;

        public StandaloneConnection() {
            super(TCPNIOTransportBuilder.newInstance().build());
            localAddress = new InetSocketAddress("127.0.0.1", 0);
            peerAddress = new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        protected void preClose() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public SocketAddress getPeerAddress() {
            return peerAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public int getReadBufferSize() {
            return 65536;
        }

        @Override
        public void setReadBufferSize(int readBufferSize) {
        }

        @Override
        public int getWriteBufferSize() {
            return 65536;
        }

        @Override
        public void setWriteBufferSize(int writeBufferSize) {
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
