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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static org.glassfish.grizzly.http.server.NetworkListener.DEFAULT_NETWORK_HOST;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.glassfish.grizzly.utils.DelayFilter;
import org.glassfish.grizzly.utils.Futures;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class ParametersTest {

    /**
     * Testcase for http://java.net/jira/browse/GRIZZLY-1438
     */
    @Test
    public void testPostBodyChunked() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer server = createServer(port);
        final String body = generatePostBody(1024 * 3);
        final String[][] paramParts = getParts(body);
        final FutureImpl<Boolean> resultFuture = Futures.createSafeFuture();
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                for (int i = 0, len = paramParts.length; i < len; i++) {
                    final String value = request.getParameter(paramParts[i][0]);
                    try {
                        assertNotNull("value is null", value);
                        assertEquals(paramParts[i][1], value);
                        resultFuture.result(Boolean.TRUE);
                    } catch (Throwable t) {
                        resultFuture.failure(t);
                    }
                }
            }
        }, "/*");

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        try {
            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new DelayFilter(0, 500));
            clientFilterChainBuilder.add(new ChunkingFilter(256));
            clientFilterChainBuilder.add(new HttpClientFilter());
            clientTransport.setProcessor(clientFilterChainBuilder.build());
            clientTransport.start();

            server.start();
            TCPNIOConnectorHandler handler = TCPNIOConnectorHandler.builder(clientTransport).build();
            GrizzlyFuture<Connection> future = handler.connect("localhost", port);
            final Buffer bodyBuffer = Buffers.wrap(clientTransport.getMemoryManager(), body);
            HttpRequestPacket request = HttpRequestPacket.builder().chunked(true).method(Method.POST).uri("/").header(Header.Host, "localhost:" + port)
                    .contentType("application/x-www-form-urlencoded; charset=ISO-8859-1").protocol(Protocol.HTTP_1_1).build();
            HttpContent content = HttpContent.builder(request).content(bodyBuffer).last(true).build();
            Connection c = future.get(10, TimeUnit.SECONDS);
            c.write(content);
            resultFuture.get(10, TimeUnit.SECONDS);
        } finally {
            server.shutdownNow();
            clientTransport.shutdownNow();
        }

    }

    /**
     * Override charset encoding
     */
    @Test
    public void testOverrideCharsetEncoding() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer server = createServer(port);
        final String body = generatePostBody(1024 * 3);
        final String[][] paramParts = getParts(body);
        final FutureImpl<Boolean> resultFuture = Futures.createSafeFuture();
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                // !!!! Override character encoding
                request.setCharacterEncoding("UTF-8");
                for (int i = 0, len = paramParts.length; i < len; i++) {
                    final String value = request.getParameter(paramParts[i][0]);
                    try {
                        assertEquals(request.getCharacterEncoding(), "UTF-8");
                        assertNotNull("value is null", value);
                        assertEquals(paramParts[i][1], value);
                        resultFuture.result(Boolean.TRUE);
                    } catch (Throwable t) {
                        resultFuture.failure(t);
                    }
                }
            }
        }, "/*");

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        try {
            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new HttpClientFilter());
            clientTransport.setProcessor(clientFilterChainBuilder.build());
            clientTransport.start();

            server.start();
            TCPNIOConnectorHandler handler = TCPNIOConnectorHandler.builder(clientTransport).build();
            GrizzlyFuture<Connection> future = handler.connect("localhost", port);
            final Buffer bodyBuffer = Buffers.wrap(clientTransport.getMemoryManager(), body);
            HttpRequestPacket request = HttpRequestPacket.builder().chunked(true).method(Method.POST).uri("/").header(Header.Host, "localhost:" + port)
                    .contentType("application/x-www-form-urlencoded; charset=ISO-8859-1").protocol(Protocol.HTTP_1_1).build();
            HttpContent content = HttpContent.builder(request).content(bodyBuffer).last(true).build();
            Connection c = future.get(10, TimeUnit.SECONDS);
            c.write(content);
            resultFuture.get(10, TimeUnit.SECONDS);
        } finally {
            server.shutdownNow();
            clientTransport.shutdownNow();
        }
    }

    /**
     * Test customized query string encoding https://java.net/jira/browse/GRIZZLY-1794
     */
    @Test
    public void testQueryStringCharsetEncoding() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer server = createServer(port);
        final FutureImpl<Boolean> resultFuture = Futures.createSafeFuture();
        server.getServerConfiguration().setDefaultQueryEncoding(Charsets.UTF8_CHARSET);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                try {
                    assertEquals("msg=Hello\\World+With+SpecChars+§*)$!±@-_=;`:\\,~|", request.getQueryString());
                    resultFuture.result(Boolean.TRUE);
                } catch (Throwable t) {
                    resultFuture.failure(t);
                }
            }
        }, "/*");

        Socket socket = null;
        try {
            server.start();

            // Low level approach with sockets is used, because common Java HTTP clients are using java.net.URI,
            // which fails when unencoded curly bracket is part of the URI
            socket = new Socket("localhost", port);
            final PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())));

            pw.println("GET /app/test?msg=Hello\\World+With+SpecChars+§*)$!±@-_=;`:\\,~| HTTP/1.0");
            pw.println(); // http request should end with a blank line
            pw.flush();

            final BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            while (br.readLine() != null) {
                ;
            }
            pw.close();
            br.close();

            resultFuture.get(10, TimeUnit.SECONDS);

        } finally {
            server.shutdownNow();
            if (socket != null) {
                socket.close();
            }
        }
    }

    // -------------------------------------------------------- Private Methods

    private static HttpServer createServer(final int port) {
        HttpServer server = new HttpServer();
        NetworkListener l = new NetworkListener("test", DEFAULT_NETWORK_HOST, port);
        server.addListener(l);
        return server;
    }

    private static String[][] getParts(final String body) {
        String[] p = body.split("&");
        String[][] parts = new String[p.length][2];
        for (int i = 0, len = p.length; i < len; i++) {
            String[] sp = p[i].split("=");
            parts[i][0] = sp[0];
            parts[i][1] = sp[1];
        }
        return parts;
    }

    private static String generatePostBody(final int len) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        boolean prepend = false;
        while (sb.length() < len) {
            if (!prepend) {
                prepend = true;
            } else {
                sb.append('&');
            }
            sb.append(r.nextLong()).append('=').append(r.nextLong());
        }
        return sb.toString();
    }
}
