/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.junit.After;
import org.junit.Test;

/**
 * Without an HTTP/2 executor, requests are dispatched on the selector thread. Doing so must not clear the selector's
 * service flag, otherwise later HTTP/1.1 requests read by that selector are served inline instead of on a worker.
 */
public class Http2ServiceFlagTest extends AbstractHttp2Test {

    private static final int PORT = 18907;

    private final Queue<String> servingThreads = new ConcurrentLinkedQueue<>();

    private HttpServer httpServer;

    @After
    public void tearDown() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testHttp1RequestsAreNotServedBySelectorsAfterHttp2Traffic() throws Exception {
        httpServer = createServer(null, PORT, false, false);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                servingThreads.add(Thread.currentThread().getName());
                response.setContentType("text/plain");
                response.getWriter().write("OK");
            }
        }, "/");
        httpServer.start();

        // Connections are spread round-robin across the selectors, so this many cover each of them
        final int connections = 2 * Runtime.getRuntime().availableProcessors() + 2;
        for (int i = 0; i < connections; i++) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Connection<?> connection = http2Connection(latch);
            try {
                final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_2_0)
                        .host("localhost:" + PORT).build();
                connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());
                assertTrue(latch.await(10, TimeUnit.SECONDS));
            } finally {
                connection.closeSilently();
            }
        }

        servingThreads.clear();
        for (int i = 0; i < connections; i++) {
            assertTrue(statusLine().startsWith("HTTP/1.1 200"));
        }
        final List<String> onSelectors = servingThreads.stream().filter(name -> name.contains("SelectorRunner")).collect(Collectors.toList());
        assertTrue("HTTP/1.1 requests must be served by workers, not inline by selectors: " + onSelectors, onSelectors.isEmpty());
    }

    private Connection<?> http2Connection(final CountDownLatch latch) throws Exception {
        final FilterChain clientChain = createClientFilterChainAsBuilder(false, true, new BaseFilter() {
            @Override
            public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                final HttpContent httpContent = ctx.getMessage();
                if (httpContent.isLast()) {
                    latch.countDown();
                }
                return ctx.getStopAction();
            }
        }).build();
        return TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport()).processor(clientChain).build()
                .connect("localhost", PORT).get(10, TimeUnit.SECONDS);
    }

    private static String statusLine() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(10000);
            final OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }
}
