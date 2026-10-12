/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.ReadHandler;
import org.glassfish.grizzly.SocketConnectorHandler;
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
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferWrapper;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.Futures;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test the max post size limitation.
 *
 * @author Alexey Stashok
 */
public class MaxPostSizeTest {
    private int port;

    private HttpServer httpServer;

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        ByteBufferWrapper.DEBUG_MODE = true;
        configureHttpServer(port);
    }

    @After
    public void after() throws Exception {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testContentLength() throws Exception {
        final String message1 = "01234";
        final String message2 = "0123456789";
        httpServer.getServerConfiguration().setMaxPostSize(message1.length());

        startHttpServer(new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
            }
        }, "/test");

        final HttpRequestPacket request1 = HttpRequestPacket.builder().method("POST").uri("/test").protocol("HTTP/1.1").header("Host", "localhost")
                .contentLength(message1.length()).build();
        final HttpContent content1 = HttpContent.builder(request1).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, message1)).build();

        final Future<HttpContent> responseFuture1 = send("localhost", port, content1);
        final HttpContent response1 = responseFuture1.get(10, TimeUnit.SECONDS);
        assertEquals(200, ((HttpResponsePacket) response1.getHttpHeader()).getStatus());

        final HttpRequestPacket request2 = HttpRequestPacket.builder().method("POST").uri("/test").protocol("HTTP/1.1").header("Host", "localhost")
                .contentLength(message2.length()).build();
        final HttpContent content2 = HttpContent.builder(request2).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, message2)).build();

        final Future<HttpContent> responseFuture2 = send("localhost", port, content2);
        final HttpContent response2 = responseFuture2.get(10, TimeUnit.SECONDS);
        assertEquals(413, ((HttpResponsePacket) response2.getHttpHeader()).getStatus());
    }

    @Test
    public void testBlockingChunkedTransferEncoding() throws Exception {
        final String newLine = "\n";
        final BlockingQueue<Future<String>> receivedChunksQueue = new ArrayBlockingQueue<>(16);

        final String message = "0123456789" + newLine;
        final int messagesAllowed = 3;

        httpServer.getServerConfiguration().setMaxPostSize(message.length() * messagesAllowed);

        startHttpServer(new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                final BufferedReader reader = new BufferedReader(request.getReader());

                try {
                    for (int i = 0; i <= messagesAllowed; i++) {
                        final String chunk = reader.readLine();
                        receivedChunksQueue.add(Futures.createReadyFuture(chunk + newLine));
                    }
                } catch (Exception e) {
                    receivedChunksQueue.add(Futures.<String>createReadyFuture(e));
                    response.sendError(400);
                }
            }
        }, "/test");

        final FutureImpl<HttpContent> responseFuture = Futures.createSafeFuture();
        final Connection c = createConnection(responseFuture, "localhost", port);

        final HttpRequestPacket request = HttpRequestPacket.builder().method("POST").uri("/test").protocol("HTTP/1.1").header("Host", "localhost").chunked(true)
                .build();

        for (int i = 0; i < messagesAllowed; i++) {
            final HttpContent content = HttpContent.builder(request).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, message)).build();
            c.write(content);
            final Future<String> receivedFuture = receivedChunksQueue.poll(10, TimeUnit.SECONDS);
            assertNotNull(receivedFuture);
            assertEquals(message, receivedFuture.get());
        }

        final HttpContent content = HttpContent.builder(request).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, message)).build();
        c.write(content);
        final Future<String> failFuture = receivedChunksQueue.poll(10, TimeUnit.SECONDS);
        assertNotNull(failFuture);

        try {
            failFuture.get();
            fail("Should have faild with the IOException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }

        final HttpContent response2 = responseFuture.get(10, TimeUnit.SECONDS);
        assertEquals(400, ((HttpResponsePacket) response2.getHttpHeader()).getStatus());
    }

    @Test
    public void testNonBlockingChunkedTransferEncoding() throws Exception {
        final BlockingQueue<Future<byte[]>> receivedChunksQueue = new ArrayBlockingQueue<>(16);

        final byte[] message = "0123456789".getBytes(Charsets.ASCII_CHARSET);

        final int messagesAllowed = 3;

        httpServer.getServerConfiguration().setMaxPostSize(message.length * messagesAllowed);

        startHttpServer(new HttpHandler() {

            @Override
            public void service(final Request request, final Response response) throws Exception {

                response.suspend();

                final NIOInputStream inputStream = request.getNIOInputStream();
                inputStream.notifyAvailable(new ReadHandler() {

                    @Override
                    public void onDataAvailable() throws Exception {
                        final byte[] buffer = new byte[message.length];
                        final int bytesRead = inputStream.read(buffer);
                        assert bytesRead == message.length;
                        receivedChunksQueue.add(Futures.createReadyFuture(buffer));

                        inputStream.notifyAvailable(this, message.length);
                    }

                    @Override
                    public void onAllDataRead() throws Exception {
                        response.resume();
                    }

                    @Override
                    public void onError(Throwable t) {
                        receivedChunksQueue.add(Futures.<byte[]>createReadyFuture(t));
                        try {
                            response.sendError(400);
                        } catch (IOException ex) {
                        } finally {
                            response.resume();
                        }
                    }
                }, message.length);
            }
        }, "/test");

        final FutureImpl<HttpContent> responseFuture = Futures.createSafeFuture();
        final Connection c = createConnection(responseFuture, "localhost", port);

        final HttpRequestPacket request = HttpRequestPacket.builder().method("POST").uri("/test").protocol("HTTP/1.1").header("Host", "localhost").chunked(true)
                .build();

        for (int i = 0; i < messagesAllowed; i++) {
            final HttpContent content = HttpContent.builder(request).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, message)).build();
            c.write(content);
            final Future<byte[]> receivedFuture = receivedChunksQueue.poll(10, TimeUnit.SECONDS);
            assertNotNull(receivedFuture);
            assertTrue(Arrays.equals(message, receivedFuture.get()));
        }

        final HttpContent content = HttpContent.builder(request).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, message)).build();
        c.write(content);
        final Future<byte[]> failFuture = receivedChunksQueue.poll(10, TimeUnit.SECONDS);
        assertNotNull(failFuture);

        try {
            failFuture.get();
            fail("Should have faild with the IOException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }

        final HttpContent response2 = responseFuture.get(10, TimeUnit.SECONDS);
        assertEquals(400, ((HttpResponsePacket) response2.getHttpHeader()).getStatus());
    }

    private void configureHttpServer(final int port) throws Exception {
        httpServer = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);

        httpServer.addListener(listener);
    }

    private void startHttpServer(HttpHandler httpHandler, String... mappings) throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(httpHandler, mappings);
        httpServer.start();
    }

    private Future<HttpContent> send(String host, int port, HttpPacket request) throws Exception {
        final FutureImpl<HttpContent> future = SafeFutureImpl.create();
        Connection connection = createConnection(future, host, port);

        connection.write(request);

        return future;
    }

    private Connection createConnection(final FutureImpl<HttpContent> future, final String host, final int port) throws Exception {
        final FilterChainBuilder builder = FilterChainBuilder.stateless();
        builder.add(new TransportFilter());
        builder.add(new HttpClientFilter());
        builder.add(new HttpMessageFilter(future));
        SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport()).processor(builder.build())
                .build();
        Future<Connection> connectFuture = connectorHandler.connect(host, port);
        return connectFuture.get(10, TimeUnit.SECONDS);
    }

    private static class HttpMessageFilter extends BaseFilter {

        private final FutureImpl<HttpContent> future;

        public HttpMessageFilter(FutureImpl<HttpContent> future) {
            this.future = future;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final HttpContent content = ctx.getMessage();
            try {
                if (!content.isLast()) {
                    return ctx.getStopAction(content);
                }

                future.result(content);
            } catch (Exception e) {
                future.failure(e);
                e.printStackTrace();
            }

            return ctx.getStopAction();
        }
    }
}
