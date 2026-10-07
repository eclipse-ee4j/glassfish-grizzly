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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
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
 * A client that opens large flow-control windows and reads slowly must still get a large response in full: the server
 * has to apply backpressure from the connection's async write queue instead of handing it everything the windows allow,
 * which runs into the queue's hard limit and closes the connection.
 */
public class Http2LargeResponseTest extends AbstractHttp2Test {

    private static final int SIZE = 12 * 1024 * 1024;
    private static final int WINDOW = 64 * 1024 * 1024;

    private HttpServer httpServer;

    @After
    public void tearDown() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testLargeResponseInOneWrite() throws Exception {
        assertEquals(SIZE, download(0));
    }

    @Test
    public void testLargeResponseInChunks() throws Exception {
        assertEquals(SIZE, download(64 * 1024));
    }

    private long download(final int chunk) throws Exception {
        final byte[] body = new byte[SIZE];
        Arrays.fill(body, (byte) 'x');
        final int port = TestUtils.findAvailableTcpPort();
        httpServer = createServer(null, port, false, false);
        // the async write queue maximum (AUTO_SIZE) derives from the send buffer; pin it so the result does not depend on the OS
        httpServer.getListener("grizzly").getTransport().setWriteBufferSize(128 * 1024);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setContentType("application/octet-stream");
                response.setContentLength(SIZE);
                try (OutputStream out = response.getOutputStream()) {
                    if (chunk <= 0) {
                        out.write(body);
                    } else {
                        for (int off = 0; off < SIZE; off += chunk) {
                            out.write(body, off, Math.min(chunk, SIZE - off));
                        }
                    }
                }
            }
        }, "/");
        httpServer.start();

        final AtomicLong received = new AtomicLong();
        final AtomicBoolean stalled = new AtomicBoolean();
        final CountDownLatch last = new CountDownLatch(1);
        final FilterChainBuilder clientChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new HttpClientFilter())
                .add(new Http2ClientFilter(Http2Configuration.builder().priorKnowledge(true).initialWindowSize(WINDOW).build()))
                .add(new BaseFilter() {
                    @Override
                    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                        final HttpContent httpContent = ctx.getMessage();
                        if (stalled.compareAndSet(false, true)) {
                            // a slow reader: the server keeps writing while nothing is read
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        received.addAndGet(httpContent.getContent().remaining());
                        if (httpContent.isLast()) {
                            last.countDown();
                        }
                        return ctx.getStopAction();
                    }
                });
        final Connection<?> connection = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport())
                .processor(clientChain.build()).build().connect("localhost", port).get(10, TimeUnit.SECONDS);
        try {
            final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_2_0)
                    .host("localhost:" + port).build();
            connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());
            // like browsers and the JDK client, open the connection window wide right away
            Http2Session.get(connection).sendWindowUpdate(0, WINDOW);
            assertTrue("response incomplete: " + received.get() + " of " + SIZE + " bytes", last.await(30, TimeUnit.SECONDS));
            return received.get();
        } finally {
            connection.closeSilently();
        }
    }
}
