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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.ConnectionProbe;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.BaseFilter;
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
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Http2SessionOutputSink sends the HEADERS and the DATA of a response in one write when nothing is queued ahead and
 * the connection window covers the data; otherwise the data goes through the session's output queue.
 */
public class Http2WriteCoalescingTest extends AbstractHttp2Test {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private HttpServer httpServer;
    private int port;

    @Before
    public void before() throws IOException {
        port = TestUtils.findAvailableTcpPort();
        httpServer = createServer(null, port, false, false);
    }

    @After
    public void after() {
        httpServer.shutdownNow();
        executor.shutdownNow();
    }

    /**
     * Data written by the completion handler of the combined HEADERS+DATA write finds the session writer locked and
     * must be written once the combined write releases it.
     */
    @Test
    public void writeFromCompletionHandlerOfCombinedWrite() throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.suspend();
                final FilterChainContext ctx = request.getContext();
                final HttpResponsePacket header = response.getResponse();
                header.setContentLength(10);
                executor.execute(() -> ctx.write(content(header, "hello", false), new EmptyCompletionHandler<WriteResult>() {
                    @Override
                    public void completed(final WriteResult result) {
                        ctx.write(content(header, "world", true));
                    }
                }));
            }
        }, "/");
        httpServer.start();

        final Client client = new Client();
        try {
            for (int i = 0; i < 20; i++) {
                assertEquals("request " + i, "helloworld", client.get("/r" + i));
            }
        } finally {
            client.close();
        }
    }

    /**
     * A response written by a thread that is not processing a read is handed to the connection as one write, HEADERS
     * and DATA together (ConnectionProbe#onWriteEvent fires once per written message).
     */
    @Test
    public void headersAndDataLeaveInOneWrite() throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.suspend();
                executor.execute(() -> writeAndResume(response, request.getRequestURI().getBytes(StandardCharsets.US_ASCII)));
            }
        }, "/");
        httpServer.start();

        final AtomicInteger writes = new AtomicInteger();
        transport().getConnectionMonitoringConfig().addProbes(new ConnectionProbe.Adapter() {
            @Override
            public void onWriteEvent(final Connection connection, final Buffer data, final long size) {
                // the client connection uses the same transport: count the server side only
                if (size > 0 && ((InetSocketAddress) connection.getLocalAddress()).getPort() == port) {
                    writes.incrementAndGet();
                }
            }
        });

        final Client client = new Client();
        try {
            // connection preface and SETTINGS exchange
            assertEquals("/warmup", client.get("/warmup"));
            final int before = writes.get();
            final int n = 10;
            for (int i = 0; i < n; i++) {
                assertEquals("/r" + i, client.get("/r" + i));
            }
            final int responseWrites = writes.get() - before;
            assertTrue("messages written for " + n + " responses: " + responseWrites, responseWrites <= n + n / 2);
        } finally {
            client.close();
        }
    }

    /**
     * More response data than the connection window allows: the responses beyond the window can't take the combined
     * write and must wait in the session's output queue for WINDOW_UPDATEs.
     */
    @Test
    public void responsesBeyondTheConnectionWindowAreComplete() throws Exception {
        final int streams = 8;
        final int size = 16000;
        final List<Response> suspended = new ArrayList<>();
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.suspend();
                synchronized (suspended) {
                    suspended.add(response);
                    if (suspended.size() < streams) {
                        return;
                    }
                }
                // all at once from one thread: together far more than the default connection window of 65535 bytes
                executor.execute(() -> {
                    for (final Response r : suspended) {
                        final byte[] body = new byte[size];
                        Arrays.fill(body, (byte) r.getRequest().getRequestURI().charAt(2));
                        writeAndResume(r, body);
                    }
                });
            }
        }, "/");
        httpServer.start();

        final Client client = new Client();
        try {
            final Map<String, CountDownLatch> done = new ConcurrentHashMap<>();
            for (int i = 0; i < streams; i++) {
                done.put("/w" + i, client.send("/w" + i));
            }
            for (int i = 0; i < streams; i++) {
                final String uri = "/w" + i;
                assertTrue(uri, done.get(uri).await(10, TimeUnit.SECONDS));
                final String body = client.bodies.get(uri).toString();
                assertEquals(uri, size, body.length());
                assertEquals(uri, String.valueOf((char) ('0' + i)).repeat(size), body);
            }
        } finally {
            client.close();
        }
    }

    private TCPNIOTransport transport() {
        return httpServer.getListener("grizzly").getTransport();
    }

    private static HttpContent content(final HttpResponsePacket header, final String data, final boolean last) {
        return HttpContent.builder(header).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, data)).last(last).build();
    }

    private static void writeAndResume(final Response response, final byte[] body) {
        try {
            response.setContentType("text/plain");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            response.resume();
        }
    }

    /**
     * HTTP/2 client (prior knowledge) on one connection, collecting the response bodies by request URI.
     */
    private final class Client {
        final Map<String, StringBuilder> bodies = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> lasts = new ConcurrentHashMap<>();
        private final Connection<?> connection;

        Client() throws Exception {
            connection = TCPNIOConnectorHandler.builder(transport())
                    .processor(createClientFilterChainAsBuilder(false, true, new BaseFilter() {
                        @Override
                        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                            final HttpContent content = ctx.getMessage();
                            final String uri = ((HttpResponsePacket) content.getHttpHeader()).getRequest().getRequestURI();
                            final StringBuilder body = bodies.computeIfAbsent(uri, u -> new StringBuilder());
                            synchronized (body) {
                                body.append(content.getContent().toStringContent(StandardCharsets.ISO_8859_1));
                            }
                            if (content.isLast()) {
                                lasts.get(uri).countDown();
                            }
                            return ctx.getStopAction();
                        }
                    }).build())
                    .build().connect("localhost", port).get(10, TimeUnit.SECONDS);
        }

        CountDownLatch send(final String uri) {
            final CountDownLatch last = new CountDownLatch(1);
            lasts.put(uri, last);
            final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri(uri).protocol(Protocol.HTTP_2_0)
                    .host("localhost:" + port).build();
            connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());
            return last;
        }

        String get(final String uri) throws InterruptedException {
            assertTrue("no complete response to " + uri, send(uri).await(5, TimeUnit.SECONDS));
            return bodies.get(uri).toString();
        }

        void close() {
            connection.closeSilently();
        }
    }
}
