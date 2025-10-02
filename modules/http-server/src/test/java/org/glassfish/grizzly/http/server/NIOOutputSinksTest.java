/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glassfish.grizzly.http.Protocol.HTTP_1_1;
import static org.glassfish.grizzly.http.server.NetworkListener.DEFAULT_NETWORK_HOST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.Writer.Reentrant;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.io.NIOOutputStream;
import org.glassfish.grizzly.http.io.NIOWriter;
import org.glassfish.grizzly.http.io.OutputBuffer;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.Futures;
import org.junit.Test;

@SuppressWarnings("Duplicates")
public class NIOOutputSinksTest {
    private static Logger LOGGER = Grizzly.logger(NIOOutputSinksTest.class);
    private static int PORT = PORT();

    static int PORT() {
        try {
            int port = 9339 + SecureRandom.getInstanceStrong().nextInt(1000);
            System.out.println("Using port: " + port);
            return port;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void testBinaryOutputSink() throws Exception {
        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        int LENGTH = 256000;
        int MAX_LENGTH = LENGTH * 2;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = 
                    HttpRequestPacket.builder()
                                     .method("GET")
                                     .uri("/path")
                                     .protocol(HTTP_1_1)
                                     .header("Host", "localhost:" + PORT)
                                     .build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                int remaining = b.remaining();

                StringBuilder sb = new StringBuilder(remaining);

                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                    try {
                        check(sb, bytesRead % LENGTH, remaining);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }

                    bytesRead += remaining;
                }

                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        AtomicInteger writeCounter = new AtomicInteger();
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {

                clientTransport.pause();
                response.setContentType("text/plain");
                NIOOutputStream out = response.getNIOOutputStream();

                while (out.canWrite()) {
                    byte[] b = new byte[LENGTH];
                    fill(b);
                    writeCounter.addAndGet(b.length);
                    out.write(b);
                    out.flush();
                }
                response.suspend();

                Connection c = request.getContext().getConnection();
                TaskQueue tqueue = ((NIOConnection) c).getAsyncWriteQueue();

                out.notifyCanWrite(new WriteHandler() {
                    @Override
                    public void onWritePossible() {
                        System.out.println("onWritePossible");
                        callbackInvoked.compareAndSet(false, true);
                        clientTransport.pause();

                        assertTrue(tqueue.spaceInBytes() < MAX_LENGTH);

                        try {
                            clientTransport.resume();

                            byte[] b = new byte[LENGTH];
                            fill(b);
                            writeCounter.addAndGet(b.length);
                            out.write(b);
                            out.flush();
                            out.close();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        response.resume();

                    }

                    @Override
                    public void onError(Throwable t) {
                        response.resume();
                        throw new RuntimeException(t);
                    }
                });

                clientTransport.resume();
            }

        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                int length = parseResult.get(10, SECONDS);
                assertEquals(writeCounter.get(), length);
                assertTrue(callbackInvoked.get());
            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    public void testCharacterOutputSink() throws Exception {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        int LENGTH = 256000;
        int MAX_LENGTH = LENGTH * 2;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                int remaining = b.remaining();

                StringBuilder sb = new StringBuilder(remaining);

                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                    try {
                        check(sb, bytesRead % LENGTH, remaining);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }

                    bytesRead += remaining;
                }

                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        AtomicInteger writeCounter = new AtomicInteger();
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                clientTransport.pause();

                response.setContentType("text/plain");
                NIOWriter out = response.getNIOWriter();
                Connection c = request.getContext().getConnection();
                TaskQueue tqueue = ((NIOConnection) c).getAsyncWriteQueue();

                while (out.canWrite()) {
                    char[] data = new char[LENGTH];
                    fill(data);
                    writeCounter.addAndGet(data.length);
                    out.write(data);
                    out.flush();
                }

                response.suspend();
                notifyCanWrite(out, tqueue, response);

                clientTransport.resume();
            }

            private void notifyCanWrite(NIOWriter out, TaskQueue tqueue, Response response) {

                out.notifyCanWrite(new WriteHandler() {

                    @Override
                    public void onWritePossible() {
                        callbackInvoked.compareAndSet(false, true);
                        clientTransport.pause();
                        assertTrue(tqueue.spaceInBytes() < MAX_LENGTH);
                        clientTransport.resume();
                        try {
                            char[] c = new char[LENGTH];
                            fill(c);
                            writeCounter.addAndGet(c.length);
                            out.write(c);
                            out.flush();
                            out.close();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        response.resume();
                    }

                    @Override
                    public void onError(Throwable t) {
                        response.resume();
                        throw new RuntimeException(t);
                    }
                });
            }

        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                int length = parseResult.get(10, SECONDS);
                assertEquals(writeCounter.get(), length);
                assertTrue(callbackInvoked.get());
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }

    }

    @Test
    public void testWriteExceptionPropagation() throws Exception {
        int LENGTH = 1024;

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        listener.registerAddOn(new AddOn() {

            @Override
            public void setup(NetworkListener networkListener, FilterChainBuilder builder) {
                int idx = builder.indexOfType(TransportFilter.class);
                builder.add(idx + 1, new BaseFilter() {
                    AtomicInteger counter = new AtomicInteger();

                    @Override
                    public NextAction handleWrite(FilterChainContext ctx) throws IOException {
                        Buffer buffer = ctx.getMessage();
                        if (counter.addAndGet(buffer.remaining()) > LENGTH * 8) {
                            throw new CustomIOException();
                        }

                        return ctx.getInvokeAction();
                    }
                });
            }

        });

        server.addListener(listener);
        FutureImpl<Boolean> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                return ctx.getSuspendAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {

                // clientTransport.pause();
                response.setContentType("text/plain");
                NIOWriter out = response.getNIOWriter();

                char[] c = new char[LENGTH];
                Arrays.fill(c, 'a');

                for (;;) {
                    try {
                        out.write(c);
                        out.flush();
                    } catch (IOException e) {
                        if (e instanceof CustomIOException || e.getCause() instanceof CustomIOException) {
                            parseResult.result(Boolean.TRUE);
                        } else {
                            System.out.println("NOT CUSTOM");
                            parseResult.failure(e);
                        }
                        break;
                    } catch (Exception e) {
                        System.out.println("NOT CUSTOM");
                        parseResult.failure(e);
                        break;
                    }
                }

            }

        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                boolean exceptionThrown = parseResult.get(10, SECONDS);
                assertTrue("Unexpected Exception thrown.", exceptionThrown);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    public void testOutputBufferDirectWrite() throws Exception {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        int LENGTH = 65536;
        int MAX_LENGTH = LENGTH * 10;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        FutureImpl<String> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private StringBuilder sb = new StringBuilder();

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = 
                    HttpRequestPacket.builder()
                                     .method("GET")
                                     .uri("/path")
                                     .protocol(HTTP_1_1)
                                     .header("Host", "localhost:" + PORT)
                                     .build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                }

                if (message.isLast()) {
                    parseResult.result(sb.toString());
                }
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        AtomicInteger writeCounter = new AtomicInteger();
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {

                clientTransport.pause();
                response.setContentType("text/plain");
                NIOOutputStream out = response.getNIOOutputStream();

                // in order to enable direct writes - set the buffer size less than byte[] length
                response.setBufferSize(LENGTH / 8);

                byte[] b = new byte[LENGTH];

                int i = 0;
                while (out.canWrite()) {
                    Arrays.fill(b, (byte) ('a' + i++ % ('z' - 'a')));
                    writeCounter.addAndGet(b.length);
                    out.write(b);
                }

                clientTransport.resume();
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                String resultStr = parseResult.get(10, SECONDS);
                assertEquals(writeCounter.get(), resultStr.length());
                check1(resultStr, LENGTH);

            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    /*
     * Added for GRIZZLY-1839.
     */
    @Test
    public void testBufferBinaryCharInterleave() throws Exception {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        server.addListener(listener);
        FutureImpl<String> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private StringBuilder sb = new StringBuilder();

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                }

                if (message.isLast()) {
                    parseResult.result(sb.toString());
                }
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {

                response.setContentType("text/plain");
                response.setCharacterEncoding("UTF-8");

                // disable buffering
                response.setBufferSize(0);

                OutputBuffer out = response.getOutputBuffer();
                out.write("abc");
                out.write("def".getBytes("UTF-8"));
                out.write("ghi".toCharArray());
                out.write("jkl".getBytes("UTF-8"));
                out.write("mno");
                out.write("pqr".getBytes("UTF-8"));
                out.write("stu".toCharArray());
                out.write("vwx".toCharArray());
                out.write("yz0".getBytes("UTF-8"));
                out.write("123".getBytes("UTF-8"));
                out.write("456");
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                String resultStr = parseResult.get(10, SECONDS);
                assertEquals("abcdefghijklmnopqrstuvwxyz0123456", resultStr);

            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    public void testWritePossibleReentrants() throws Exception {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        server.addListener(listener);

        FutureImpl<HttpHeader> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpPacket message = ctx.getMessage();
                HttpHeader header = message.isHeader() ? (HttpHeader) message : message.getHttpHeader();

                parseResult.result(header);

                return ctx.getStopAction();
            }
        });

        int maxAllowedReentrants = Reentrant.getMaxReentrants();
        AtomicInteger maxReentrantsNoticed = new AtomicInteger();

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        HttpHandler ga = new HttpHandler() {

            int reentrants = maxAllowedReentrants * 3;
            ThreadLocal<Integer> reentrantsCounter = new ThreadLocal<Integer>() {

                @Override
                protected Integer initialValue() {
                    return -1;
                }
            };

            @Override
            public void service(Request request, Response response) throws Exception {
                response.suspend();

                // clientTransport.pause();
                NIOOutputStream outputStream = response.getNIOOutputStream();
                reentrantsCounter.set(0);

                try {
                    outputStream.notifyCanWrite(new WriteHandler() {

                        @Override
                        public void onWritePossible() throws Exception {
                            if (reentrants-- >= 0) {
                                int reentrantNum = reentrantsCounter.get() + 1;

                                try {
                                    reentrantsCounter.set(reentrantNum);

                                    if (reentrantNum > maxReentrantsNoticed.get()) {
                                        maxReentrantsNoticed.set(reentrantNum);
                                    }

                                    outputStream.notifyCanWrite(this);
                                } finally {
                                    reentrantsCounter.set(reentrantNum - 1);
                                }
                            } else {
                                finish(200);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            finish(500);
                        }

                        private void finish(int code) {
                            response.setStatus(code);
                            response.resume();
                        }
                    });
                } finally {
                    reentrantsCounter.remove();
                }
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                HttpHeader header = parseResult.get(10, SECONDS);
                assertEquals(200, ((HttpResponsePacket) header).getStatus());

                assertTrue("maxReentrantNoticed=" + maxReentrantsNoticed + " maxAllowed=" + maxAllowedReentrants,
                        maxReentrantsNoticed.get() <= maxAllowedReentrants);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    public void testWritePossibleNotification() throws Exception {
        int NOTIFICATIONS_NUM = 5;
        int LENGTH = 8192;

        AtomicInteger sentBytesCount = new AtomicInteger();
        AtomicInteger notificationsCount = new AtomicInteger();

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        server.addListener(listener);

        FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {
            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                int remaining = b.remaining();

                StringBuilder sb = new StringBuilder(remaining);

                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                    try {
                        check(sb, bytesRead % LENGTH, remaining);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }

                    bytesRead += remaining;
                }

                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.suspend();

                NIOOutputStream outputStream = response.getNIOOutputStream();
                outputStream.notifyCanWrite(new WriteHandler() {

                    @Override
                    public void onWritePossible() throws Exception {
                        clientTransport.pause();

                        try {
                            while (outputStream.canWrite()) {
                                byte[] b = new byte[LENGTH];
                                fill(b);
                                outputStream.write(b);
                                outputStream.flush();
                                sentBytesCount.addAndGet(LENGTH);
                            }

                            if (notificationsCount.incrementAndGet() < NOTIFICATIONS_NUM) {
                                outputStream.notifyCanWrite(this);
                            } else {
                                finish(200);
                            }
                        } finally {
                            clientTransport.resume();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        finish(500);
                    }

                    private void finish(int code) {
                        response.setStatus(code);
                        response.resume();
                    }
                });
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                int responseContentLength = parseResult.get(10, SECONDS);

                assertEquals(NOTIFICATIONS_NUM, notificationsCount.get());
                assertEquals(sentBytesCount.get(), responseContentLength);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    /**
     * Make sure the write called from WriteHandler.onWritePossible(), even if it wasn't guaranteed to be non-blocking, will
     * not entirely block the async writer.
     *
     * http://java.net/jira/browse/GRIZZLY-1309
     */
    @Test
    public void testProvocativeWrite() throws Exception {
        int LENGTH = 8192;

        ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);

        AtomicInteger sentBytesCount = new AtomicInteger();

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        listener.getTransport().setIOStrategy(WorkerThreadIOStrategy.getInstance());
        server.addListener(listener);

        FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {
            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                        .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                int remaining = b.remaining();

                StringBuilder sb = new StringBuilder(remaining);

                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                    try {
                        check(sb, bytesRead % LENGTH, remaining);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }

                    bytesRead += remaining;
                }

                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.suspend();

                NIOOutputStream outputStream = response.getNIOOutputStream();

                int numberOfExtraWrites = 0;

                clientTransport.pause();
                Thread.sleep(500);

                while (outputStream.canWrite() || numberOfExtraWrites-- > 0) {
                    byte[] b = new byte[LENGTH];
                    fill(b);
                    outputStream.write(b);
                    outputStream.flush();
                    sentBytesCount.addAndGet(LENGTH);
                    // noinspection BusyWait
                    Thread.sleep(5);
                }

                ses.schedule(new Runnable() {

                    @Override
                    public void run() {
                        System.out.println("resuming " + clientTransport.getState().getState());
                        clientTransport.resume();
                    }
                }, 2, SECONDS);

                outputStream.notifyCanWrite(new WriteHandler() {

                    @Override
                    public void onWritePossible() throws Exception {
                        boolean isClientTransportPaused = true;
                        clientTransport.pause();

                        try {
                            while (outputStream.canWrite()) {
                                byte[] b = new byte[LENGTH];
                                fill(b);
                                outputStream.write(b);
                                outputStream.flush();
                                sentBytesCount.addAndGet(LENGTH);
                            }

                            clientTransport.resume(); // Resume the client transport so it can accept more data
                            isClientTransportPaused = false;

                            // Last canWrite returned false, so next write is not guaranteed to be non-blocking
                            byte[] b = new byte[LENGTH];
                            fill(b);
                            outputStream.write(b); // <----- May block here
                            outputStream.flush(); // <----- or here
                            sentBytesCount.addAndGet(LENGTH);

                            finish(200);
                        } finally {
                            if (isClientTransportPaused) {
                                clientTransport.resume();
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        finish(500);
                    }

                    private void finish(int code) {
                        response.setStatus(code);
                        response.resume();
                    }
                });
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                int responseContentLength = parseResult.get(10, SECONDS);

                assertEquals(sentBytesCount.get(), responseContentLength);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
            ses.shutdown();
        }
    }

    /**
     * Make sure postponed async write failure from one request will not impact other request, that reuses the same
     * OutputBuffer.
     *
     * https://java.net/jira/browse/GRIZZLY-1536
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testPostponedAsyncFailure() throws Exception {
        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        TCPNIOTransport transport = listener.getTransport();
        transport.setIOStrategy(WorkerThreadIOStrategy.getInstance());
        transport.setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        server.addListener(listener);

        AtomicReference<Connection> connectionToClose = new AtomicReference<>();
        FutureImpl<Boolean> floodReached = Futures.createSafeFuture();
        FutureImpl<HttpContent> result = Futures.createSafeFuture();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {
            AtomicBoolean isFirstConnectionInputBlocked = new AtomicBoolean();

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                if (isFirstConnectionInputBlocked.compareAndSet(false, true)) {
                    return ctx.getSuspendAction();
                }

                HttpContent httpContent = ctx.getMessage();
                if (httpContent.isLast()) {
                    result.result(httpContent);
                    return ctx.getStopAction();
                }

                return ctx.getStopAction(httpContent);
            }
        });

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        HttpHandler floodHttpHandler = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                connectionToClose.set(request.getContext().getConnection());
                floodReached.result(Boolean.TRUE);

                NIOOutputStream outputStream = response.getNIOOutputStream();

                try {
                    while (outputStream.canWrite()) {
                        byte[] b = new byte[4096];
                        outputStream.write(b);
                        outputStream.flush();
                        // noinspection BusyWait
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    result.failure(e);
                }
            }
        };

        String checkString = "Check#";
        String checkPattern = "";
        for (int i = 0; i < 10; i++) {
            // noinspection StringConcatenationInLoop
            checkPattern += checkString + i;
        }

        HttpHandler controlHttpHandler = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                connectionToClose.get().closeSilently();
                Thread.sleep(20); // give some time to close the connection
                try {
                    NIOWriter writer = response.getNIOWriter();
                    for (int i = 0; i < 10; i++) {
                        writer.write(checkString + i);
                        writer.flush();
                        // noinspection BusyWait
                        Thread.sleep(20);
                    }
                } catch (Exception e) {
                    result.failure(e);
                }
            }
        };

        server.getServerConfiguration().addHttpHandler(floodHttpHandler, "/flood");
        server.getServerConfiguration().addHttpHandler(controlHttpHandler, "/control");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connect1Future = clientTransport.connect("localhost", PORT);
            Connection connection1 = connect1Future.get(10, SECONDS);
            // Build the HttpRequestPacket, which will be sent to a server
            // We construct HTTP request version 1.1 and specifying the URL
            HttpRequestPacket httpRequest1 = HttpRequestPacket.builder().method("GET").uri("/flood").protocol(HTTP_1_1)
                    .header("Host", "localhost:" + PORT).build();

            // Write the request asynchronously
            connection1.write(httpRequest1);

            assertTrue(floodReached.get(10, SECONDS));

            Future<Connection> connect2Future = clientTransport.connect("localhost", PORT);
            Connection connection2 = connect2Future.get(10, SECONDS);
            // Build the HttpRequestPacket, which will be sent to a server
            // We construct HTTP request version 1.1 and specifying the URL
            HttpRequestPacket httpRequest2 = HttpRequestPacket.builder().method("GET").uri("/control").protocol(HTTP_1_1)
                    .header("Host", "localhost:" + PORT).build();

            // Write the request asynchronously
            connection2.write(httpRequest2);

            HttpContent httpContent = result.get(30, SECONDS);

            assertEquals(checkPattern, httpContent.getContent().toStringContent());
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    public void testAbnormalOutputStreamCloseAfterResume() throws Exception {
        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        int LENGTH = 256000;
        int MAX_LENGTH = LENGTH * 2;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest =
                        HttpRequestPacket.builder()
                                         .method("GET")
                                         .uri("/path")
                                         .protocol(HTTP_1_1)
                                         .header("Host", "localhost:" + PORT)
                                         .build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                int remaining = b.remaining();

                StringBuilder sb = new StringBuilder(remaining);

                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                    try {
                        check(sb, bytesRead % LENGTH, remaining);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }

                    bytesRead += remaining;
                }

                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        AtomicInteger writeCounter = new AtomicInteger();
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {

                clientTransport.pause();
                response.setContentType("text/plain");
                NIOOutputStream out = response.getNIOOutputStream();

                while (out.canWrite()) {
                    byte[] b = new byte[LENGTH];
                    fill(b);
                    writeCounter.addAndGet(b.length);
                    out.write(b);
                    out.flush();
                }
                response.suspend();

                Connection c = request.getContext().getConnection();
                TaskQueue tqueue = ((NIOConnection) c).getAsyncWriteQueue();

                out.notifyCanWrite(new WriteHandler() {
                    @Override
                    public void onWritePossible() {
                        System.out.println("onWritePossible");
                        callbackInvoked.compareAndSet(false, true);
                        clientTransport.pause();

                        assertTrue(tqueue.spaceInBytes() < MAX_LENGTH);

                        try {
                            clientTransport.resume();

                            byte[] b = new byte[LENGTH];
                            fill(b);
                            writeCounter.addAndGet(b.length);
                            out.write(b);
                            out.flush();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        response.resume();

                        // After a brief wait for the resume and response to be finished,
                        // the output will be closed as a post-processing.
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        // Close the output after the resume is called.
                        // This is not normal usage, but we need to make sure
                        try {
                            out.close();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        t.printStackTrace();
                        response.resume();
                        throw new RuntimeException(t);
                    }
                });
                clientTransport.resume();
            }
        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                int length = parseResult.get(10, SECONDS);
                assertEquals(writeCounter.get(), length);
                assertTrue(callbackInvoked.get());
                // Wait until the output close is invoked and handler is finished.
                assertTrue(latch.await(3, SECONDS));
            } finally {
                LOGGER.log(Level.INFO, "Written {0}", writeCounter);
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    public void testAbnormalWriterCloseAfterResume() throws Exception {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("Grizzly", DEFAULT_NETWORK_HOST, PORT);
        int LENGTH = 256000;
        int MAX_LENGTH = LENGTH * 2;
        listener.setMaxPendingBytes(MAX_LENGTH);
        server.addListener(listener);
        FutureImpl<Integer> parseResult = SafeFutureImpl.create();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new BaseFilter() {

            private int bytesRead;

            @Override
            public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                // Build the HttpRequestPacket, which will be sent to a server
                // We construct HTTP request version 1.1 and specifying the URL of the
                // resource we want to download
                HttpRequestPacket httpRequest = HttpRequestPacket.builder().method("GET").uri("/path").protocol(HTTP_1_1)
                                                                 .header("Host", "localhost:" + PORT).build();

                // Write the request asynchronously
                ctx.write(httpRequest);

                // Return the stop action, which means we don't expect next filter to process
                // connect event
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                HttpContent message = ctx.getMessage();
                Buffer b = message.getContent();
                int remaining = b.remaining();

                StringBuilder sb = new StringBuilder(remaining);

                if (b.hasRemaining()) {
                    sb.append(b.toStringContent());
                    try {
                        check(sb, bytesRead % LENGTH, remaining);
                    } catch (Exception e) {
                        parseResult.failure(e);
                    }

                    bytesRead += remaining;
                }

                if (message.isLast()) {
                    parseResult.result(bytesRead);
                }
                return ctx.getStopAction();
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setProcessor(filterChainBuilder.build());
        AtomicInteger writeCounter = new AtomicInteger();
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        HttpHandler ga = new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                clientTransport.pause();

                response.setContentType("text/plain");
                NIOWriter out = response.getNIOWriter();
                Connection c = request.getContext().getConnection();
                TaskQueue tqueue = ((NIOConnection) c).getAsyncWriteQueue();

                while (out.canWrite()) {
                    char[] data = new char[LENGTH];
                    fill(data);
                    writeCounter.addAndGet(data.length);
                    out.write(data);
                    out.flush();
                }

                response.suspend();
                notifyCanWrite(out, tqueue, response);

                clientTransport.resume();
            }

            private void notifyCanWrite(NIOWriter out, TaskQueue tqueue, Response response) {

                out.notifyCanWrite(new WriteHandler() {

                    @Override
                    public void onWritePossible() {
                        callbackInvoked.compareAndSet(false, true);
                        clientTransport.pause();
                        assertTrue(tqueue.spaceInBytes() < MAX_LENGTH);
                        clientTransport.resume();
                        try {
                            char[] c = new char[LENGTH];
                            fill(c);
                            writeCounter.addAndGet(c.length);
                            out.write(c);
                            out.flush();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        response.resume();

                        // After a brief wait for the resume and response to be finished,
                        // the output will be closed as a post-processing.
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        // Close the output after the resume is called.
                        // This is not normal usage, but we need to make sure
                        try {
                            out.close();
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        t.printStackTrace();
                        response.resume();
                        throw new RuntimeException(t);
                    }
                });
            }

        };

        server.getServerConfiguration().addHttpHandler(ga, "/path");

        try {
            Thread.sleep(5);
            server.start();
            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, SECONDS);
                int length = parseResult.get(10, SECONDS);
                assertEquals(writeCounter.get(), length);
                assertTrue(callbackInvoked.get());
                // Wait until the output close is invoked and handler is finished.
                assertTrue(latch.await(3, SECONDS));
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            fail();
        } finally {
            clientTransport.shutdownNow();
            server.shutdownNow();
        }

    }

    private static void fill(byte[] array) {
        for (int i = 0; i < array.length; i++) {
            array[i] = (byte) ('a' + i % ('z' - 'a'));
        }
    }

    private static void fill(char[] array) {
        for (int i = 0; i < array.length; i++) {
            array[i] = (char) ('a' + i % ('z' - 'a'));
        }
    }

    private static void check(StringBuilder sb, int offset, int lastCameSize) {
        int start = sb.length() - lastCameSize;

        for (int i = 0; i < lastCameSize; i++) {
            char c = sb.charAt(start + i);
            char expect = (char) ('a' + (i + start + offset) % ('z' - 'a'));
            if (c != expect) {
                throw new IllegalStateException("Result at [" + (i + start) + "] don't match. Expected=" + expect + " got=" + c);
            }
        }
    }

    private static void check1(String resultStr, int LENGTH) {
        for (int i = 0; i < resultStr.length() / LENGTH; i++) {
            char expect = (char) ('a' + i % ('z' - 'a'));
            for (int j = 0; j < LENGTH; j++) {
                char charAt = resultStr.charAt(i * LENGTH + j);
                if (charAt != expect) {
                    throw new IllegalStateException("Result at [" + (i * LENGTH + j) + "] don't match. Expected=" + expect + " got=" + charAt);
                }
            }
        }
    }

    private static class CustomIOException extends IOException {
        private static long serialVersionUID = 1L;
    }
}
