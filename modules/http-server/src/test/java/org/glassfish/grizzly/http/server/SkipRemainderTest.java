/*
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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.ICloseType;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpRequestPacket.Builder;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferWrapper;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.utils.Futures;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test how HttpServer skips HTTP packet remainder, if HttpHandler didn't read the complete message.
 *
 * @author Alexey Stashok
 */

@SuppressWarnings("unchecked")
public class SkipRemainderTest {
    public int port;

    private HttpServer httpServer;

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        ByteBufferWrapper.DEBUG_MODE = true;
        configureWebServer(port);
    }

    @After
    public void after() throws Exception {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testKeepAliveConnection() throws Exception {
        final BlockingQueue<Integer> transferQueue = new LinkedTransferQueue<>();

        final AtomicInteger counter = new AtomicInteger();
        final int contentSizeHalf = 32;
        final FutureImpl[] futures = { SafeFutureImpl.create(), SafeFutureImpl.create() };

        startWebServer(new HttpHandler() {
            @Override
            public void service(Request req, Response res) throws Exception {
                InputStream is = req.getInputStream();
                try {
                    for (int i = 0; i < contentSizeHalf; i++) {
                        int c = is.read();
                        if (c != i) {
                            futures[counter.get()]
                                    .failure(new IllegalStateException("Assertion failed on request#" + counter + ": expected=" + i + " got=" + c));

                            return;
                        }
                    }

                    OutputStream os = res.getOutputStream();
                    os.write("OK".getBytes());
                    os.flush();

                    futures[counter.get()].result("OK");

                } catch (Exception e) {
                    futures[counter.get()].failure(e);
                }
            }
        });

        byte[] content = createContent(contentSizeHalf * 2);

        Future<Connection> connectFuture = connect("localhost", port, transferQueue);
        Connection connection = connectFuture.get(10, TimeUnit.SECONDS);

        sendContentByHalfs(connection, content, transferQueue);
        counter.incrementAndGet();
        sendContentByHalfs(connection, content, transferQueue);

        for (FutureImpl future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testNonKeepAliveConnection() throws Exception {
        final BlockingQueue<Integer> transferQueue = new LinkedTransferQueue<>();

        final int contentSizeHalf = 256 * 1024;
        final FutureImpl future = SafeFutureImpl.create();

        startWebServer(new HttpHandler() {
            @Override
            public void service(Request req, Response res) throws Exception {
                InputStream is = req.getInputStream();
                try {
                    for (int i = 0; i < contentSizeHalf; i++) {
                        int c = is.read();
                        if (c != i % 256) {
                            future.failure(new IllegalStateException("Assertion failed: expected=" + i + " got=" + c));
                            return;
                        }
                    }

                    res.setStatus(200, "FINE");
                    OutputStream os = res.getOutputStream();
                    os.write("OK".getBytes());
                    os.flush();

                    future.result("OK");

                } catch (Exception e) {
                    future.failure(e);
                }
            }
        });
        byte[] content = createContent(contentSizeHalf * 2);

        Future<Connection> connectFuture = connect("localhost", port, transferQueue);
        Connection connection = connectFuture.get(10, TimeUnit.SECONDS);

        sendContentByHalfs(connection, content, transferQueue, false);

        future.get(10, TimeUnit.SECONDS);
    }

    @Test
    // http://java.net/jira/browse/GRIZZLY-1113
    public void testSuspendResume() throws Exception {
        final BlockingQueue<Integer> transferQueue = new LinkedTransferQueue<>();

        final int contentSizeHalf = 256 * 1024;
        final FutureImpl future = SafeFutureImpl.create();

        final ExecutorService tp = Executors.newFixedThreadPool(1);

        final AtomicInteger calls = new AtomicInteger();
        try {
            startWebServer(new HttpHandler() {
                @Override
                public void service(final Request req, final Response res) throws Exception {
                    calls.incrementAndGet();

                    res.suspend();
                    tp.submit(new Runnable() {

                        @Override
                        public void run() {
                            try {
                                InputStream is = req.getInputStream();
                                for (int i = 0; i < contentSizeHalf; i++) {
                                    int c = is.read();
                                    if (c != i % 256) {
                                        future.failure(new IllegalStateException("Assertion failed: expected=" + i + " got=" + c));
                                        return;
                                    }
                                }

                                res.setStatus(200, "FINE");
                                OutputStream os = res.getOutputStream();
                                os.write("OK".getBytes());
                                os.flush();

                                future.result("OK");

                            } catch (Exception e) {
                                future.failure(e);
                            } finally {
                                res.resume();
                            }
                        }
                    });
                }
            });
            byte[] content = createContent(contentSizeHalf * 2);

            Future<Connection> connectFuture = connect("localhost", port, transferQueue);
            Connection connection = connectFuture.get(10, TimeUnit.SECONDS);

            sendContentByHalfs(connection, content, transferQueue, false);

            future.get(10, TimeUnit.SECONDS);

            Thread.sleep(500);

            assertEquals(1, calls.get());
        } finally {
            tp.shutdownNow();
        }
    }

    @Test
    public void testLimitedRemainderSizeWithContentLength() throws Exception {
        final int maxPayloadRemainderToSkip = 8192;

        httpServer.getServerConfiguration().setMaxPayloadRemainderToSkip(maxPayloadRemainderToSkip);
        final BlockingQueue<Integer> responseQueue = new ArrayBlockingQueue<>(256);
        final BlockingQueue<Future<Boolean>> requestRcvQueue = new ArrayBlockingQueue<>(256);

        startWebServer(new HttpHandler() {
            @Override
            public void service(Request req, Response res) throws Exception {
                final int contentLength = req.getContentLength();

                InputStream is = req.getInputStream();
                try {
                    for (int i = 0; i < contentLength / 2; i++) {
                        int c = is.read();
                        if (c != i % 256) {
                            requestRcvQueue.add(Futures.<Boolean>createReadyFuture(new IllegalStateException("Assertion failed. Expected=" + i + " got=" + c)));

                            return;
                        }
                    }

                    OutputStream os = res.getOutputStream();
                    os.write("OK".getBytes());
                    os.flush();

                    requestRcvQueue.add(Futures.createReadyFuture(Boolean.TRUE));
                } catch (Exception e) {
                    requestRcvQueue.add(Futures.<Boolean>createReadyFuture(e));
                }
            }
        });

        Future<Connection> connectFuture = connect("localhost", port, responseQueue);
        Connection connection = connectFuture.get(10, TimeUnit.SECONDS);

        sendContentByHalfs(connection, createContent(maxPayloadRemainderToSkip), responseQueue);
        Future<Boolean> resultFuture = requestRcvQueue.poll(10, TimeUnit.SECONDS);
        assertNotNull(resultFuture);
        assertTrue(resultFuture.get());

        try {
            sendContentByHalfs(connection, createContent(maxPayloadRemainderToSkip * 4), responseQueue);
        } catch (Exception e) {
            // second part of the payload may cause the exception (depending on timing)
        }

        resultFuture = requestRcvQueue.poll(10, TimeUnit.SECONDS);
        assertNotNull(resultFuture);
        assertTrue(resultFuture.get());

        final CountDownLatch closeLatch = new CountDownLatch(1);
        connection.addCloseListener(new CloseListener() {

            @Override
            public void onClosed(Closeable closeable, ICloseType type) throws IOException {
                closeLatch.countDown();
            }
        });

        try {
            sendContentByHalfs(connection, createContent(maxPayloadRemainderToSkip), responseQueue);
        } catch (Exception e) {
        }

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLimitedRemainderSizeWithChunked() throws Exception {
        final int maxPayloadRemainderToSkip = 8192;

        httpServer.getServerConfiguration().setMaxPayloadRemainderToSkip(maxPayloadRemainderToSkip);
        final BlockingQueue<Integer> responseQueue = new ArrayBlockingQueue<>(256);
        final BlockingQueue<Future<Boolean>> requestRcvQueue = new ArrayBlockingQueue<>(256);

        startWebServer(new HttpHandler() {
            @Override
            public void service(Request req, Response res) throws Exception {
                InputStream is = req.getInputStream();
                try {
                    for (int i = 0; i < maxPayloadRemainderToSkip; i++) {
                        int c = is.read();

                        if (c == -1) {
                            break;
                        }

                        if (c != i % 256) {
                            requestRcvQueue.add(Futures.<Boolean>createReadyFuture(new IllegalStateException("Assertion failed. Expected=" + i + " got=" + c)));

                            return;
                        }
                    }

                    OutputStream os = res.getOutputStream();
                    os.write("OK".getBytes());
                    os.flush();

                    requestRcvQueue.add(Futures.createReadyFuture(Boolean.TRUE));
                } catch (Exception e) {
                    requestRcvQueue.add(Futures.<Boolean>createReadyFuture(e));
                }
            }
        });

        Future<Connection> connectFuture = connect("localhost", port, responseQueue);
        Connection connection = connectFuture.get(10, TimeUnit.SECONDS);

        sendContentByHalfs(connection, createContent(maxPayloadRemainderToSkip), responseQueue, true, true);
        Future<Boolean> resultFuture = requestRcvQueue.poll(10, TimeUnit.SECONDS);
        assertNotNull(resultFuture);
        assertTrue(resultFuture.get());

        try {
            sendContentByHalfs(connection, createContent(maxPayloadRemainderToSkip * 8), responseQueue, true, true);
        } catch (Exception e) {
            // second part of the payload may cause the exception (depending on timing)
        }

        resultFuture = requestRcvQueue.poll(10, TimeUnit.SECONDS);
        assertNotNull(resultFuture);
        assertTrue(resultFuture.get());

        final CountDownLatch closeLatch = new CountDownLatch(1);
        connection.addCloseListener(new CloseListener() {

            @Override
            public void onClosed(Closeable closeable, ICloseType type) throws IOException {
                closeLatch.countDown();
            }
        });

        try {
            sendContentByHalfs(connection, createContent(maxPayloadRemainderToSkip), responseQueue, true, true);
        } catch (Exception e) {
        }

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    private byte[] createContent(final int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        return content;
    }

    private void sendContentByHalfs(Connection connection, byte[] content, final BlockingQueue<Integer> transferQueue) throws Exception {
        sendContentByHalfs(connection, content, transferQueue, true);
    }

    private void sendContentByHalfs(Connection connection, byte[] content, final BlockingQueue<Integer> transferQueue, final boolean isKeepAlive)
            throws Exception {
        sendContentByHalfs(connection, content, transferQueue, isKeepAlive, false);
    }

    private void sendContentByHalfs(Connection connection, byte[] content, final BlockingQueue<Integer> transferQueue, final boolean isKeepAlive,
            final boolean isChunked) throws Exception {

        final MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;

        final Builder packetBuilder = HttpRequestPacket.builder().method("POST").uri("/hello").protocol("HTTP/1.1").header("Host", "localhost");

        if (!isChunked) {
            packetBuilder.contentLength(content.length);
        } else {
            packetBuilder.chunked(true);
        }

        if (!isKeepAlive) {
            packetBuilder.header(Header.Connection, "close");
        }

        final HttpRequestPacket request = packetBuilder.build();

        connection.write(request);

        final int packetChunks = 3;
        final int packetSz = content.length / packetChunks;

        int offs = 0;
        for (int i = 0; i < packetChunks - 1; i++) {
            final Buffer chunk = Buffers.wrap(mm, content, offs, packetSz);
            offs += packetSz;

            final HttpContent httpContent = HttpContent.builder(request).content(chunk).build();

            connection.write(httpContent).get(10, TimeUnit.SECONDS);

            Thread.sleep(200);
        }

        final Buffer chunk = Buffers.wrap(mm, content, offs, content.length - offs);

        final HttpContent httpContent = HttpContent.builder(request).content(chunk).last(true).build();

        connection.write(httpContent).get(10, TimeUnit.SECONDS);

        Thread.sleep(200);

        Integer responseSize = transferQueue.poll(10, TimeUnit.SECONDS);
        if (responseSize == null) {
            throw new TimeoutException("No response from server");
        }
        assertEquals("Unexpected response size", (Integer) 2, responseSize);
    }

    private Future<Connection> connect(String host, int port, BlockingQueue<Integer> transferQueue) throws Exception {

        final FilterChainBuilder builder = FilterChainBuilder.stateless();
        builder.add(new TransportFilter());
        builder.add(new HttpClientFilter());
        builder.add(new HttpMessageFilter(transferQueue));

        SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport()).processor(builder.build())
                .build();

        return connectorHandler.connect(host, port);
    }

    private void configureWebServer(final int port) throws Exception {
        httpServer = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        listener.setMaxPendingBytes(-1);
        httpServer.addListener(listener);
    }

    private void startWebServer(final HttpHandler httpHandler) throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(httpHandler);
        httpServer.start();
    }

    private static class HttpMessageFilter extends BaseFilter {
        private final BlockingQueue<Integer> transferQueue;

        public HttpMessageFilter(BlockingQueue<Integer> transferQueue) {
            this.transferQueue = transferQueue;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final HttpContent content = ctx.getMessage();
            if (!content.isLast()) {
                return ctx.getStopAction(content);
            }

            transferQueue.offer(content.getContent().remaining());

            return ctx.getStopAction();
        }
    }
}
