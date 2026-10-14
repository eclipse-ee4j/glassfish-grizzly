/*
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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.junit.Before;
import org.junit.Test;

/**
 * Test HTTP TRACE method processing
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class TraceMethodTest {
    private int port;

    @Before
    public void findPort() {
        try {
            port = TestUtils.findAvailableTcpPort();
        } catch (IOException e) {
            port = 18902;
        }
    }

    @Test
    public void testTraceDisabled() throws Exception {
        final HttpServer server = createWebServer(port);

        server.start();

        try {
            final HttpPacket request = createRequest(Method.TRACE, "/index.html", null);
            final HttpContent response = doTest(request, 10);

            assertEquals(405, ((HttpResponsePacket) response.getHttpHeader()).getStatus());
            assertEquals("POST, GET, DELETE, OPTIONS, PUT, HEAD", response.getHttpHeader().getHeader(Header.Allow));

        } finally {
            server.shutdownNow();
        }
    }

    @Test
    public void testTraceEnabled() throws Exception {
        final HttpServer server = createWebServer(port);
        server.getServerConfiguration().setTraceEnabled(true);

        server.start();

        try {
            final HttpPacket request = createRequest(Method.TRACE, "/index.html", Collections.singletonMap("MyHeader", "MyValue"));
            final HttpContent response = doTest(request, 10);

            assertEquals(200, ((HttpResponsePacket) response.getHttpHeader()).getStatus());
            assertEquals("message/http;charset=ISO-8859-1", response.getHttpHeader().getContentType());

            final String responseContent = response.getContent().toStringContent();
            final BufferedReader reader = new BufferedReader(new StringReader(responseContent));
            assertEquals("TRACE /index.html HTTP/1.1", reader.readLine());
            assertEquals(("Host: localhost:" + port).toLowerCase(), reader.readLine().toLowerCase());
            assertEquals("MyHeader: MyValue".toLowerCase(), reader.readLine().toLowerCase());
            assertNull(reader.readLine());

        } finally {
            server.shutdownNow();
        }
    }

    private HttpPacket createRequest(Method method, String uri, Map<String, String> headers) {

        HttpRequestPacket.Builder b = HttpRequestPacket.builder();
        b.method(method).protocol(Protocol.HTTP_1_1).uri(uri).header("Host", "localhost:" + port);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                b.header(entry.getKey(), entry.getValue());
            }
        }

        return b.build();
    }

    private HttpContent doTest(final HttpPacket request, final int timeout) throws Exception {

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        try {
            final FutureImpl<HttpContent> testResultFuture = SafeFutureImpl.create();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new ChunkingFilter(4));
            clientFilterChainBuilder.add(new HttpClientFilter());
            clientFilterChainBuilder.add(new ClientFilter(testResultFuture));
            clientTransport.setProcessor(clientFilterChainBuilder.build());

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", port);
            Connection connection = null;
            try {
                connection = connectFuture.get(timeout, TimeUnit.SECONDS);
                connection.write(request);
                return testResultFuture.get(timeout, TimeUnit.SECONDS);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.shutdownNow();
        }
    }

    private HttpServer createWebServer(final int port) {

        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(new StaticHttpHandler(), "/");

        return server;

    }

    private static class ClientFilter extends BaseFilter {
        private final static Logger logger = Grizzly.logger(ClientFilter.class);

        private final FutureImpl<HttpContent> testFuture;

        // -------------------------------------------------------- Constructors

        public ClientFilter(FutureImpl<HttpContent> testFuture) {

            this.testFuture = testFuture;

        }

        // ------------------------------------------------- Methods from Filter

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {

            // Cast message to a HttpContent
            final HttpContent httpContent = ctx.getMessage();

            logger.log(Level.FINE, "Got HTTP response chunk");

            // Get HttpContent's Buffer
            final Buffer buffer = httpContent.getContent();

            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "HTTP content size: {0}", buffer.remaining());
            }

            if (!httpContent.isLast()) {
                return ctx.getStopAction(httpContent);
            }

            testFuture.result(httpContent);

            return ctx.getStopAction();
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx) throws IOException {
            close();
            return ctx.getStopAction();
        }

        private void close() throws IOException {

            if (!testFuture.isDone()) {
                // noinspection ThrowableInstanceNeverThrown
                testFuture.failure(new IOException("Connection was closed"));
            }

        }

    } // END ClientFilter
}
