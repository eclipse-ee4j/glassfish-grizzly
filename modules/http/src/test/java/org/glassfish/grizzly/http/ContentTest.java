/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;

import junit.framework.TestCase;

import static java.time.Duration.ofSeconds;

/**
 *
 * @author oleksiys
 */
public class ContentTest extends TestCase {

    public static final int PORT = 19003;

    @SuppressWarnings({ "unchecked" })
    public void testExplicitContentLength() throws Exception {
        HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("POST").protocol(Protocol.HTTP_1_1).uri("/default").contentLength(10).build();
        httpRequest.addHeader(Header.Host, "localhost:" + PORT);
        HttpContent content = httpRequest.httpContentBuilder().content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "1234567890")).build();

        doHttpRequestTest(content);
    }

    @SuppressWarnings({ "unchecked" })
    public void testHeaderContentLength() throws Exception {
        HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("POST").protocol(Protocol.HTTP_1_1).uri("/default").header("Content-Length", "10")
                .build();
        httpRequest.addHeader("Host", "localhost:" + PORT);
        HttpContent content = httpRequest.httpContentBuilder().content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "1234567890")).build();

        doHttpRequestTest(content);
    }

    @SuppressWarnings({ "unchecked" })
    public void testSimpleChunked() throws Exception {
        HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("POST").protocol(Protocol.HTTP_1_1).uri("/default").chunked(true).build();
        httpRequest.addHeader("Host", "localhost:" + PORT);
        HttpContent content = httpRequest.httpTrailerBuilder().content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "1234567890")).build();

        doHttpRequestTest(content);
    }

    @SuppressWarnings({ "unchecked" })
    public void testSeveralChunked() throws Exception {
        HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("POST").protocol(Protocol.HTTP_1_1).uri("/default").chunked(true).build();
        httpRequest.addHeader("Host", "localhost:" + PORT);
        HttpContent content1 = httpRequest.httpContentBuilder().content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "1234567890")).build();
        HttpContent content2 = httpRequest.httpContentBuilder().content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "0987654321")).build();
        HttpContent content3 = httpRequest.httpTrailerBuilder().content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "final")).build();

        doHttpRequestTest(content1, content2, content3);
    }

    private void doHttpRequestTest(HttpContent... patternContentMessages) throws Exception {

        final FutureImpl<HttpPacket> parseResult = SafeFutureImpl.create();

        Connection<SocketAddress> connection = null;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new ChunkingFilter(2));
        filterChainBuilder.add(new HttpServerFilter());
        filterChainBuilder.add(new HTTPRequestMergerFilter(parseResult));
        FilterChain filterChain = filterChainBuilder.build();

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChain);

        try {
            TestUtils.retryUntilSuccess(() -> transport.bind(PORT), ofSeconds(10), ofSeconds(1));
            transport.start();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new ChunkingFilter(2));
            clientFilterChainBuilder.add(new HttpClientFilter());
            FilterChain clientFilterChain = clientFilterChainBuilder.build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();

            Future<Connection> future = connectorHandler.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            final HttpHeader patternHeader = patternContentMessages[0].getHttpHeader();

            byte[] patternContent = new byte[0];
            for (int i = 0; i < patternContentMessages.length; i++) {
                int oldLen = patternContent.length;
                final ByteBuffer bb = patternContentMessages[i].getContent().toByteBuffer().duplicate();
                patternContent = Arrays.copyOf(patternContent, oldLen + bb.remaining());
                bb.get(patternContent, oldLen, bb.remaining());
            }

            for (HttpContent content : patternContentMessages) {
                Future<WriteResult<HttpContent, SocketAddress>> writeFuture = connection.write(content);
                writeFuture.get(10, TimeUnit.SECONDS);
            }

            HttpContent result = (HttpContent) parseResult.get(10, TimeUnit.SECONDS);
            HttpHeader resultHeader = result.getHttpHeader();

            assertEquals(patternHeader.getContentLength(), resultHeader.getContentLength());
            assertEquals(patternHeader.isChunked(), resultHeader.isChunked());

            byte[] resultContent = new byte[result.getContent().remaining()];
            result.getContent().get(resultContent);
            assertTrue(Arrays.equals(patternContent, resultContent));

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public static class HTTPRequestMergerFilter extends BaseFilter {
        private final FutureImpl<HttpPacket> parseResult;

        public HTTPRequestMergerFilter(FutureImpl<HttpPacket> parseResult) {
            this.parseResult = parseResult;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            HttpContent httpContent = ctx.getMessage();

            if (!httpContent.isLast()) {
                return ctx.getStopAction(httpContent);
            }

            parseResult.result(httpContent);
            return ctx.getStopAction();
        }
    }
}
