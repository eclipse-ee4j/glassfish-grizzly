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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.junit.After;
import org.junit.Test;

/**
 * Tests that a stream exceeding {@code SETTINGS_MAX_CONCURRENT_STREAMS} is refused on its own (RFC 9113, section
 * 5.1.2) instead of terminating the whole connection together with the streams still in progress on it.
 */
public class Http2MaxConcurrentStreamsTest extends AbstractHttp2Test {

    private static final int PORT = 18906;
    private static final String TEST_HEADER = "x-refused-stream-test";
    private static final String TEST_HEADER_VALUE = "hpack-dynamic-table-entry";

    private HttpServer httpServer;

    @After
    public void tearDown() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testStreamOverLimitIsRefusedWithoutClosingConnection() throws Exception {
        final CountDownLatch blockingRequestEntered = new CountDownLatch(1);
        final CountDownLatch releaseBlockingRequest = new CountDownLatch(1);
        startServer(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                if ("/block".equals(request.getRequestURI())) {
                    blockingRequestEntered.countDown();
                    releaseBlockingRequest.await(10, TimeUnit.SECONDS);
                }
                response.setContentType("text/plain");
                response.getWriter().write(request.getRequestURI() + ':' + request.getHeader(TEST_HEADER));
            }
        });

        final ResponseCollector responses = new ResponseCollector("/block", "/after");
        final Connection<?> connection = connect(responses);

        connection.write(get("/block"));
        assertTrue("the first request did not reach the handler", blockingRequestEntered.await(10, TimeUnit.SECONDS));

        // The only permitted stream is busy, so this one exceeds the advertised limit.
        final HttpContent refused = get("/refused");
        connection.write(refused);
        final Http2Stream refusedStream = Http2Stream.getStreamFor(refused.getHttpHeader());
        assertNotNull(refusedStream);
        awaitClosed(refusedStream);
        assertTrue("refusing a single stream must not close the connection", connection.isOpen());

        releaseBlockingRequest.countDown();
        assertEquals("/block:null", responses.await("/block"));

        // The refused request put TEST_HEADER into the peer's HPACK dynamic table. The next request references that
        // entry, so it is only decoded correctly if the server decoded the refused header block too.
        connection.write(get("/after"));
        assertEquals("/after:" + TEST_HEADER_VALUE, responses.await("/after"));
        assertTrue(connection.isOpen());
    }

    // -------------------------------------------------------- Private Methods

    private void startServer(final HttpHandler handler) throws Exception {
        httpServer = createServer(null, PORT, false, true);
        http2Addon.getConfiguration().setMaxConcurrentStreams(1);
        httpServer.getListener("grizzly").getKeepAlive().setIdleTimeoutInSeconds(-1);
        httpServer.getServerConfiguration().addHttpHandler(handler, "/");
        httpServer.start();
    }

    private Connection<?> connect(final ResponseCollector responses) throws Exception {
        final FilterChain clientChain = createClientFilterChainAsBuilder(false, true, responses).build();
        final TCPNIOTransport transport = httpServer.getListener("grizzly").getTransport();
        final SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChain).build();
        final Future<Connection> connectFuture = connectorHandler.connect("localhost", PORT);
        return connectFuture.get(10, TimeUnit.SECONDS);
    }

    private static HttpContent get(final String uri) {
        final HttpRequestPacket.Builder builder = HttpRequestPacket.builder().method(Method.GET).uri(uri)
                .protocol(Protocol.HTTP_2_0).host("localhost:" + PORT);
        if (!"/block".equals(uri)) {
            builder.header(TEST_HEADER, TEST_HEADER_VALUE);
        }
        return HttpContent.builder(builder.build()).content(Buffers.EMPTY_BUFFER).last(true).build();
    }

    private static void awaitClosed(final Http2Stream stream) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stream.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse("the stream over the limit was not refused", stream.isOpen());
    }

    private static final class ResponseCollector extends BaseFilter {
        private final Map<String, StringBuilder> bodies = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> completed = new ConcurrentHashMap<>();

        private ResponseCollector(final String... uris) {
            for (final String uri : uris) {
                bodies.put(uri, new StringBuilder());
                completed.put(uri, new CountDownLatch(1));
            }
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final HttpContent httpContent = ctx.getMessage();
            final HttpResponsePacket response = (HttpResponsePacket) httpContent.getHttpHeader();
            final String uri = response.getRequest().getRequestURI();
            final StringBuilder body = bodies.get(uri);
            if (body != null) {
                if (httpContent.getContent().hasRemaining()) {
                    body.append(httpContent.getContent().toStringContent());
                }
                if (httpContent.isLast()) {
                    completed.get(uri).countDown();
                }
            }
            return ctx.getStopAction();
        }

        String await(final String uri) throws InterruptedException {
            assertTrue("no complete response for " + uri, completed.get(uri).await(10, TimeUnit.SECONDS));
            return bodies.get(uri).toString();
        }
    }
}
