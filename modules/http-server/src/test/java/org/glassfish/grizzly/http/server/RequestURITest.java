/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.util.HashMap;
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
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;

import junit.framework.TestCase;

/**
 * Checking the request-uri passed to HttpHandler
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class RequestURITest extends TestCase {

    public void testSimpleURI() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpHandler httpHandler = new RequestURIHttpHandler();
        final HttpPacket request = createRequest("/index.html;jsessionid=123456", null, port);
        final HttpContent response = doTest(request, 10, port, httpHandler);

        final String responseContent = response.getContent().toStringContent();
        Map<String, String> props = new HashMap<>();

        BufferedReader reader = new BufferedReader(new StringReader(responseContent));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] nameValue = line.split("=");
            assertEquals(line, 2, nameValue.length);
            props.put(nameValue[0], nameValue[1]);
        }

        String uri = props.get("uri");
        assertNotNull(uri);
        assertEquals("/index.html", uri);
    }

    public void testEncodedSimpleURI() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final String rusURI = "/\u043F\u0440\u0438\u0432\u0435\u0442\u043C\u0438\u0440";
        final String rusEncodedURI = URLEncoder.encode(rusURI, "UTF-8");

        final HttpHandler httpHandler = new RequestURIHttpHandler();

        final HttpPacket request = createRequest(rusEncodedURI, null, port);
        final HttpContent response = doTest(request, 10, port, httpHandler);

        final String responseContent = response.getContent().toStringContent();
        Map<String, String> props = new HashMap<>();

        BufferedReader reader = new BufferedReader(new StringReader(responseContent));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] nameValue = line.split("=");
            assertEquals(2, nameValue.length);
            props.put(nameValue[0], nameValue[1]);
        }

        String uri = props.get("uri");
        assertNotNull(uri);
        assertEquals(rusEncodedURI, uri);
    }

    public void testCompleteURI() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpHandler httpHandler = new RequestURIHttpHandler();
        final HttpPacket request = createRequest("http://localhost:" + port + "/index.html;jsessionid=123456", null, port);
        final HttpContent response = doTest(request, 10, port, httpHandler);

        final String responseContent = response.getContent().toStringContent();
        Map<String, String> props = new HashMap<>();

        BufferedReader reader = new BufferedReader(new StringReader(responseContent));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] nameValue = line.split("=");
            assertEquals(2, nameValue.length);
            props.put(nameValue[0], nameValue[1]);
        }

        String uri = props.get("uri");
        assertNotNull(uri);
        assertEquals("/index.html", uri);
    }

    public void testDecodedParamsPlusMapping() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        // In order to test mapping, register 2 HttpHandlers
        final String param = ";myparam=123456";
        final HttpPacket request = createRequest("/1" + param, null, port);
        final HttpContent response = doTest(request, 10, port, new DecodedURLIndexOfHttpHandler(param), new DecodedURLIndexOfHttpHandler(param));

        final String responseContent = response.getContent().toStringContent();
        Map<String, String> props = new HashMap<>();

        BufferedReader reader = new BufferedReader(new StringReader(responseContent));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] nameValue = line.split("=");
            assertEquals(2, nameValue.length);
            props.put(nameValue[0], nameValue[1]);
        }

        String isFound = props.get("result");
        assertNotNull(isFound);
        assertTrue(Boolean.parseBoolean(isFound));
    }

    private HttpPacket createRequest(String uri, Map<String, String> headers, final int port) {

        HttpRequestPacket.Builder b = HttpRequestPacket.builder();
        b.method(Method.GET).protocol(Protocol.HTTP_1_1).uri(uri).header("Host", "localhost:" + port);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                b.header(entry.getKey(), entry.getValue());
            }
        }

        return b.build();
    }

    private HttpContent doTest(final HttpPacket request, final int timeout, final int port, final HttpHandler... httpHandlers) throws Exception {

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createWebServer(port, httpHandlers);
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

    private HttpServer createWebServer(final int port, final HttpHandler... httpHandlers) {

        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(httpHandlers[0], "/");

        for (int i = 1; i < httpHandlers.length; i++) {
            // associate handlers with random context-roots
            server.getServerConfiguration().addHttpHandler(httpHandlers[i], "/" + i + "/*");
        }

        server.getHttpHandler().setAllowEncodedSlash(true);

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

    public static class RequestURIHttpHandler extends HttpHandler {

        @Override
        public void service(Request request, Response response) throws Exception {
            final String uri = request.getRequestURI();
            response.getWriter().write("uri=" + uri + "\n");
        }

    }

    public static class DecodedURLIndexOfHttpHandler extends HttpHandler {
        private final String match;

        public DecodedURLIndexOfHttpHandler(String match) {
            this.match = match;
        }

        @Override
        public void service(Request request, Response response) throws Exception {
            response.getWriter().write("result=" + request.getRequest().getRequestURIRef().getDecodedURI().contains(match) + "\n");
        }

    }

}
