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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.GenericCloseListener;
import org.glassfish.grizzly.IOStrategy;
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
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.LeaderFollowerNIOStrategy;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.utils.Futures;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Testing for a keep-alive regression that surfaces when {@link Response#suspend()} is used
 * together with {@link SameThreadIOStrategy}, {@link WorkerThreadIOStrategy} and {@link LeaderFollowerNIOStrategy}.
 *
 * <p>When the body of a request triggers multiple selector READ events and the handler suspends
 * the response and resumes it asynchronously, the IO-event interest bookkeeping in
 * {@code SameThreadIOStrategy.InterestLifeCycleListenerWhenIoEnabled} ends up with OP_READ
 * disabled on the connection and should be re-enabled. The next request on the keep-alive connection
 * is therefore picked up by the selector.</p>
 */
@RunWith(Parameterized.class)
public class KeepAliveAfterSuspendTest {

    @Parameterized.Parameters
    public static Collection<Object[]> getIOStrategy() {
        return Arrays.asList(
                new Object[][]{{SameThreadIOStrategy.getInstance()}, {WorkerThreadIOStrategy.getInstance()},
                               {LeaderFollowerNIOStrategy.getInstance()}});
    }

    private final IOStrategy ioStrategy;

    public KeepAliveAfterSuspendTest(final IOStrategy ioStrategy) {
        this.ioStrategy = ioStrategy;
    }

    private static final int PORT = 18908;
    private static final int BODY_SIZE = 20 * 1024;
    private static final long REQUEST_TIMEOUT_IN_SECONDS = 5L;

    private HttpServer httpServer;
    private ExecutorService asyncWorker;
    private TCPNIOTransport clientTransport;

    @Before
    public void before() throws Exception {
        asyncWorker = Executors.newFixedThreadPool(1);
        httpServer = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT);
        listener.getTransport().setIOStrategy(ioStrategy);
        listener.getTransport().setReadBufferSize(4096);
        listener.getTransport().setSelectorRunnersCount(1);
        httpServer.addListener(listener);

        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {

            @Override
            public void service(Request request, Response response) {
                // Acquire the input stream on the selector thread, then dispatch for processing
                // to the worker
                final InputStream in = request.getInputStream();
                // Suspend on the selector thread, do all real work on a worker thread, then
                // resume from the worker.
                response.suspend();
                asyncWorker.submit(() -> {
                    try {
                        final byte[] buf = new byte[8192];
                        int total = 0;
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            total += n;
                        }
                        response.setContentType("text/plain");
                        // Reflect back the length of the processed request body.
                        final byte[] payload = ("bytes=" + total).getBytes(StandardCharsets.US_ASCII);
                        response.getOutputStream().write(payload);
                    } catch (IOException e) {
                        fail(e.getMessage());
                    } finally {
                        response.resume();
                    }
                });
            }
        }, "/echo");
        httpServer.start();

        clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.start();
    }

    @After
    public void after() {
        if (clientTransport != null) {
            try {
                clientTransport.shutdownNow();
            } catch (IOException ignore) {
            }
            clientTransport = null;
        }
        if (httpServer != null) {
            httpServer.shutdownNow();
            httpServer = null;
        }
        if (asyncWorker != null) {
            asyncWorker.shutdownNow();
            asyncWorker = null;
        }
    }

    /**
     * Sends two HTTP/1.1 requests using the same http connection. The first POST carries a 20 KiB body that,
     * combined with the 4 KiB read buffer, spans multiple selector READ events. The second
     * request must be served on the same keep-alive connection. The second {@link HttpClient#send} should be processed successfully
     * because the selector is parked in {@code select()} with OP_READ enabled on the connection.
     */
    @Test
    public void testKeepAliveAfterSuspendedResponse() throws Exception {
        try (HttpClient client = new HttpClient(clientTransport)) {
            client.connect("localhost", PORT).get(5L, TimeUnit.SECONDS);

            final byte[] body = new byte[BODY_SIZE];
            Arrays.fill(body, (byte) 'A');

            final HttpPacket first = HttpRequestPacket.builder().method("POST").uri("/echo").protocol(Protocol.HTTP_1_1)
                                                      .header("Host", "localhost:" + PORT)
                                                      .header("Content-Type", "application/octet-stream")
                                                      .contentLength(BODY_SIZE).build().httpContentBuilder()
                                                      .content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, body))
                                                      .build();

            final Buffer firstResponse = client.send(first).get(REQUEST_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
            assertEquals("unexpected response length", "bytes=" + BODY_SIZE, firstResponse.toStringContent());

            final HttpPacket second = HttpRequestPacket.builder().method("GET").uri("/echo").protocol(Protocol.HTTP_1_1)
                                                       .header("Host", "localhost:" + PORT).build();

            final Buffer secondResponse = client.send(second).get(REQUEST_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
            assertEquals("bytes=0", secondResponse.toStringContent());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class HttpClient implements AutoCloseable {
        private final TCPNIOTransport transport;
        private volatile Connection connection;
        private volatile FutureImpl<Buffer> asyncFuture;

        public HttpClient(final TCPNIOTransport transport) {
            this.transport = transport;
        }

        public Future<Connection> connect(final String host, final int port) {
            final FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
            filterChainBuilder.add(new TransportFilter());
            filterChainBuilder.add(new HttpClientFilter());
            filterChainBuilder.add(new HttpClient.HttpResponseFilter());
            final SocketConnectorHandler connector =
                    TCPNIOConnectorHandler.builder(transport).processor(filterChainBuilder.build()).build();
            final FutureImpl<Connection> future = Futures.createSafeFuture();
            connector.connect(new InetSocketAddress(host, port),
                              Futures.toCompletionHandler(future, new EmptyCompletionHandler<>() {

                                  @Override
                                  public void completed(Connection result) {
                                      connection = result;
                                  }
                              }));
            return future;
        }

        public Future<Buffer> send(final HttpPacket request) {
            final FutureImpl<Buffer> localFuture = SafeFutureImpl.create();
            asyncFuture = localFuture;
            connection.write(request, new EmptyCompletionHandler() {

                @Override
                public void failed(Throwable throwable) {
                    localFuture.failure(throwable);
                }
            });
            connection.addCloseListener(new GenericCloseListener() {

                @Override
                public void onClosed(Closeable connection, CloseType closeType) {
                    localFuture.failure(new IOException());
                }
            });
            return localFuture;
        }

        @Override
        public void close() {
            if (connection != null) {
                connection.closeSilently();
                connection = null;
            }
        }

        private class HttpResponseFilter extends BaseFilter {

            @Override
            public NextAction handleRead(FilterChainContext ctx) {
                final HttpContent message = ctx.getMessage();
                if (message.isLast()) {
                    final FutureImpl<Buffer> localFuture = asyncFuture;
                    asyncFuture = null;
                    localFuture.result(message.getContent());
                    return ctx.getStopAction();
                }
                return ctx.getStopAction(message);
            }
        }
    }
}