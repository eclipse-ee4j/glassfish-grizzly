/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.junit.Before;
import org.junit.Test;

/**
 * Test the error page generation.
 */
public class ErrorPageTest {
    private int port;

    @Before
    public void findPort() {
        try {
            port = TestUtils.findAvailableTcpPort();
        } catch (IOException e) {
            port = 18906;
        }
    }

    @Test
    public void testNoPage() throws Exception {
        final HttpPacket request = createRequest("/index.html", null);
        final HttpContent responseContent = doTest(request, 10, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.setStatus(500);
            }
        });

        final HttpResponsePacket responsePacket = (HttpResponsePacket) responseContent.getHttpHeader();
        assertEquals(500, responsePacket.getStatus());
        assertEquals(0, responsePacket.getContentLength());
        assertFalse(responseContent.getContent().hasRemaining());
    }

    @Test
    public void testSendError() throws Exception {
        final HttpPacket request = createRequest("/index.html", null);
        final HttpContent responseContent = doTest(request, 10, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.sendError(500);
            }
        });

        final HttpResponsePacket responsePacket = (HttpResponsePacket) responseContent.getHttpHeader();
        assertEquals(500, responsePacket.getStatus());
        assertTrue(responseContent.getContent().hasRemaining());
    }

    @Test
    public void testUncaughtException() throws Exception {
        final HttpPacket request = createRequest("/index.html", null);
        final HttpContent responseContent = doTest(request, 10, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                throw new IOException("TestError");
            }
        });

        final HttpResponsePacket responsePacket = (HttpResponsePacket) responseContent.getHttpHeader();
        assertEquals(500, responsePacket.getStatus());
        assertTrue(responseContent.getContent().hasRemaining());
        // test the default error-page content-type
        assertTrue(responsePacket.getContentType().startsWith("text/html"));

        String errorPage = responseContent.getContent().toStringContent();
        System.out.println(errorPage);
        assertTrue(errorPage.contains("TestError"));
        assertTrue(errorPage.contains("service")); // check the stacktrace
    }

    @Test
    public void testCustomErrorPageContentType() throws Exception {
        final HttpPacket request = createRequest("/index.html", null);
        final HttpContent responseContent = doTest(request, 10, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.setErrorPageGenerator(new DefaultErrorPageGenerator() {

                    @Override
                    public String generate(Request request, int status, String reasonPhrase, String description, Throwable exception) {
                        request.getResponse().setContentType("text/mytype");
                        return super.generate(request, status, reasonPhrase, description, exception);
                    }
                });

                throw new IOException("TestError");
            }
        });

        final HttpResponsePacket responsePacket = (HttpResponsePacket) responseContent.getHttpHeader();
        assertEquals(500, responsePacket.getStatus());
        assertTrue(responseContent.getContent().hasRemaining());
        // check the custom error-page content type
        assertTrue(responsePacket.getContentType().startsWith("text/mytype"));

        String errorPage = responseContent.getContent().toStringContent();
        System.out.println(errorPage);
        assertTrue(errorPage.contains("TestError"));
        assertTrue(errorPage.contains("service")); // check the stacktrace
    }

    @Test
    public void testErrorMessageXSSVulnerability() throws Exception {
        final HttpPacket request = createRequest("/", null);
        final HttpContent responseContent = doTest(request, 10, new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                throw new RuntimeException("Accept: <script>alert(1)</script>");
            }
        });
        final HttpResponsePacket responsePacket = (HttpResponsePacket) responseContent.getHttpHeader();
        assertEquals(500, responsePacket.getStatus());
        assertTrue(responseContent.getContent().hasRemaining());
        String errorPage = responseContent.getContent().toStringContent();
        System.out.println(errorPage);
        assertTrue(errorPage.contains("&lt;script&gt;alert(1)&lt;"));
        assertFalse(errorPage.contains("<script>alert(1)</script>"));
    }

    private HttpPacket createRequest(String uri, Map<String, String> headers) {

        HttpRequestPacket.Builder b = HttpRequestPacket.builder();
        b.method(Method.GET).protocol(Protocol.HTTP_1_1).uri(uri).header("Host", "localhost:" + port);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                b.header(entry.getKey(), entry.getValue());
            }
        }

        return b.build();
    }

    private HttpContent doTest(final HttpPacket request, final int timeout, final HttpHandler... httpHandlers) throws Exception {

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createWebServer(httpHandlers);
        try {
            final FutureImpl<HttpContent> testResultFuture = SafeFutureImpl.create();

            server.start();
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
            server.shutdownNow();
        }
    }

    private HttpServer createWebServer(final HttpHandler... httpHandlers) {

        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(httpHandlers[0], "/");

        for (int i = 1; i < httpHandlers.length; i++) {
            // associate handlers with random context-roots
            server.getServerConfiguration().addHttpHandler(httpHandlers[i], "/" + i + "/*");
        }
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
