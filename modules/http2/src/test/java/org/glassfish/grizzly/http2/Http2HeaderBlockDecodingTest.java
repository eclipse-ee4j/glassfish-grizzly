/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

import static org.glassfish.grizzly.http2.RawHttp2.END_HEADERS;
import static org.glassfish.grizzly.http2.RawHttp2.END_STREAM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.Connection;
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
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;

/**
 * Every header block received on a connection has to go through the HPACK decoder, also the ones that are not
 * processed, unless the connection is closed (RFC 9113, sections 4.3 and 10.5.1): the decoder state is shared by the
 * streams of the connection.
 */
public class Http2HeaderBlockDecodingTest {

    private static final int MAX_HEADER_LIST_SIZE = 512;

    /**
     * A request whose header block is larger than the limit is answered with 431, which needs the whole block to be
     * decoded, and the connection still serves the following requests.
     */
    @Test
    public void oversizedHeaderBlockIsAnsweredWith431() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer server = startServer(port);

        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final String big = bigValue();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            out.write(RawHttp2.PREFACE);
            RawHttp2.writeFrame(out, RawHttp2.SETTINGS, 0, 0, new byte[0]);

            final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);
            final RawHttp2.BlockDecoder decoder = new RawHttp2.BlockDecoder(memoryManager);

            assertEquals("200 x-a=one", exchange(in, out, decoder, 1, encoder.requestPseudoHeaders("/").addIndexed("x-a", "one").flush()));

            // a single HEADERS frame, so the block ends with the fragment that goes over the limit
            final String oversized = exchange(in, out, decoder, 3, encoder.requestPseudoHeaders("/").addIndexed("x-big", big).flush());

            final String next = exchange(in, out, decoder, 5, encoder.requestPseudoHeaders("/").addIndexed("x-a", "one").flush());

            assertEquals("oversized request", "431 ", oversized);
            assertEquals("request after the oversized one", "200 x-a=one", next);
        } finally {
            server.shutdownNow();
        }
    }

    /**
     * The same as {@link #oversizedHeaderBlockIsAnsweredWith431()} when the fragment that goes over the limit is a
     * CONTINUATION frame.
     */
    @Test
    public void oversizedContinuationIsAnsweredWith431() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer server = startServer(port);

        final MemoryManager<?> memoryManager = new ByteBufferManager();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            out.write(RawHttp2.PREFACE);
            RawHttp2.writeFrame(out, RawHttp2.SETTINGS, 0, 0, new byte[0]);

            final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);
            final RawHttp2.BlockDecoder decoder = new RawHttp2.BlockDecoder(memoryManager);

            // the block is split at a field boundary: the HEADERS frame is under the limit, the CONTINUATION frame is not
            RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_STREAM, 1, encoder.requestPseudoHeaders("/").flush());
            RawHttp2.writeFrame(out, RawHttp2.CONTINUATION, END_HEADERS, 1, encoder.addIndexed("x-big", bigValue()).flush());
            final String oversized = readResponse(in, out, decoder, 1);

            final String next = exchange(in, out, decoder, 3, encoder.requestPseudoHeaders("/").addIndexed("x-a", "one").flush());

            assertEquals("oversized request", "431 ", oversized);
            assertEquals("request after the oversized one", "200 x-a=one", next);
        } finally {
            server.shutdownNow();
        }
    }

    /**
     * A HEADERS frame without END_STREAM on a stream that already received its request headers is a stream error that
     * is detected before the header block is decoded; the block must still be decoded, and must not be left for the
     * next one.
     */
    @Test
    public void headersRejectedBeforeDecodingDoNotReachTheNextRequest() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer server = startServer(port);

        final MemoryManager<?> memoryManager = new ByteBufferManager();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            out.write(RawHttp2.PREFACE);
            RawHttp2.writeFrame(out, RawHttp2.SETTINGS, 0, 0, new byte[0]);

            final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);
            final RawHttp2.BlockDecoder decoder = new RawHttp2.BlockDecoder(memoryManager);

            // the request stays open: no END_STREAM
            RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS, 1, encoder.requestPseudoHeaders("/").addIndexed("x-a", "one").flush());
            final String first = readResponse(in, out, decoder, 1);

            // a second HEADERS frame that does not end the stream, with a field the peer adds to its dynamic table
            RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS, 1, encoder.addIndexed("x-b", "two").flush());
            final String rejected = readResponse(in, out, decoder, 1);

            // the next block refers to the entries of both blocks by index
            final String next = exchange(in, out, decoder, 3,
                    encoder.requestPseudoHeaders("/").addIndexed("x-b", "two").addIndexed("x-a", "one").flush());

            assertEquals("first request", "200 x-a=one", first);
            assertEquals("second HEADERS frame", "RST_STREAM", rejected);
            assertEquals("request after the rejected HEADERS frame", "200 x-a=one", next);
        } finally {
            server.shutdownNow();
        }
    }

    private static HttpServer startServer(final int port) throws IOException {
        final HttpServer server = HttpServer.createSimpleServer(null, port);
        final NetworkListener listener = server.getListener("grizzly");
        listener.registerAddOn(new Http2AddOn(Http2Configuration.builder().maxHeaderListSize(MAX_HEADER_LIST_SIZE).build()));
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                final String body = "x-a=" + request.getHeader("x-a");
                response.setContentType("text/plain");
                response.setContentLength(body.length());
                response.getWriter().write(body);
            }
        }, "/");
        server.start();
        return server;
    }

    private static String bigValue() {
        final char[] big = new char[MAX_HEADER_LIST_SIZE * 2];
        Arrays.fill(big, 'b');
        return new String(big);
    }

    /**
     * A client ignores a HEADERS frame for a stream it does not know; the block must not be left for the next one.
     */
    @Test
    public void headersForUnknownStreamDoNotReachTheNextResponse() throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final CountDownLatch firstResponseReceived = new CountDownLatch(1);
        final AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverSocket.setSoTimeout(10000);
            final int port = serverSocket.getLocalPort();

            final Thread peer = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(10000);
                    final InputStream in = socket.getInputStream();
                    final OutputStream out = socket.getOutputStream();
                    final byte[] preface = new byte[RawHttp2.PREFACE.length];
                    new DataInputStream(in).readFully(preface);
                    RawHttp2.writeFrame(out, RawHttp2.SETTINGS, 0, 0, new byte[0]);
                    final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);

                    awaitRequest(in, out, 1);
                    RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS | END_STREAM, 1,
                            encoder.add(":status", "200").add("x-response", "1").flush());

                    firstResponseReceived.await(10, TimeUnit.SECONDS);
                    // the client knows no stream 2
                    RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS | END_STREAM, 2,
                            encoder.add(":status", "200").add("x-unknown-stream", "2").flush());

                    awaitRequest(in, out, 3);
                    RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS | END_STREAM, 3,
                            encoder.add(":status", "200").add("x-response", "3").flush());
                    // wait for the client to close the connection
                    while (true) {
                        RawHttp2.readFrame(in);
                    }
                } catch (IOException ignored) {
                    // the client closed the connection
                } catch (Throwable t) {
                    serverError.set(t);
                }
            });
            peer.setDaemon(true);
            peer.start();

            final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
            final Map<String, LinkedBlockingQueue<HttpResponsePacket>> responses = new ConcurrentHashMap<>();
            responses.put("/1", new LinkedBlockingQueue<>());
            responses.put("/3", new LinkedBlockingQueue<>());
            transport.setProcessor(AbstractHttp2Test.createClientFilterChainAsBuilder(false, true, new BaseFilter() {
                @Override
                public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                    final HttpContent content = ctx.getMessage();
                    if (content.isLast()) {
                        final HttpResponsePacket response = (HttpResponsePacket) content.getHttpHeader();
                        responses.get(response.getRequest().getRequestURI()).add(response);
                    }
                    return ctx.getStopAction();
                }
            }).build());
            transport.start();
            try {
                final Connection<?> connection = TCPNIOConnectorHandler.builder(transport).build().connect("localhost", port).get(10,
                        TimeUnit.SECONDS);
                try {
                    connection.write(HttpContent.builder(request(port, "/1")).content(Buffers.EMPTY_BUFFER).last(true).build());
                    final HttpResponsePacket first = responses.get("/1").poll(10, TimeUnit.SECONDS);
                    assertNotNull("first response", first);
                    assertEquals("1", first.getHeader("x-response"));
                    firstResponseReceived.countDown();

                    connection.write(HttpContent.builder(request(port, "/3")).content(Buffers.EMPTY_BUFFER).last(true).build());
                    final HttpResponsePacket second = responses.get("/3").poll(10, TimeUnit.SECONDS);
                    assertNotNull("second response", second);
                    assertEquals("3", second.getHeader("x-response"));
                    assertNull("a field of the unknown stream's block in the second response", second.getHeader("x-unknown-stream"));
                } finally {
                    connection.closeSilently();
                }
            } finally {
                transport.shutdownNow();
            }
            peer.join(10000);
            assertNull("peer", serverError.get());
        }
    }

    /**
     * A client resets a stream that receives a second HEADERS frame without END_STREAM after the response headers,
     * which is detected before the header block is decoded; the block must not be left for the next response.
     */
    @Test
    public void headersRejectedBeforeDecodingDoNotReachTheNextResponse() throws Exception {
        final MemoryManager<?> memoryManager = new ByteBufferManager();
        final AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverSocket.setSoTimeout(10000);
            final int port = serverSocket.getLocalPort();

            final Thread peer = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(10000);
                    final InputStream in = socket.getInputStream();
                    final OutputStream out = socket.getOutputStream();
                    final byte[] preface = new byte[RawHttp2.PREFACE.length];
                    new DataInputStream(in).readFully(preface);
                    RawHttp2.writeFrame(out, RawHttp2.SETTINGS, 0, 0, new byte[0]);
                    final RawHttp2.IndexingEncoder encoder = new RawHttp2.IndexingEncoder(memoryManager);

                    awaitRequest(in, out, 1);
                    // response headers that do not end the stream, then a second HEADERS frame that does not end it either
                    RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS, 1, encoder.add(":status", "200").add("x-response", "1").flush());
                    RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS, 1, encoder.add("x-rejected", "1").flush());

                    awaitRequest(in, out, 3);
                    RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS | END_STREAM, 3,
                            encoder.add(":status", "200").add("x-response", "3").flush());
                    // wait for the client to close the connection
                    while (true) {
                        RawHttp2.readFrame(in);
                    }
                } catch (IOException ignored) {
                    // the client closed the connection
                } catch (Throwable t) {
                    serverError.set(t);
                }
            });
            peer.setDaemon(true);
            peer.start();

            final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
            // the reset stream 1 ends its response too, so the responses are told apart by request
            final LinkedBlockingQueue<HttpResponsePacket> responses = new LinkedBlockingQueue<>();
            transport.setProcessor(AbstractHttp2Test.createClientFilterChainAsBuilder(false, true, new BaseFilter() {
                @Override
                public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                    final HttpContent content = ctx.getMessage();
                    final HttpResponsePacket response = (HttpResponsePacket) content.getHttpHeader();
                    if (content.isLast() && "/3".equals(response.getRequest().getRequestURI())) {
                        responses.add(response);
                    }
                    return ctx.getStopAction();
                }
            }).build());
            transport.start();
            try {
                final Connection<?> connection = TCPNIOConnectorHandler.builder(transport).build().connect("localhost", port).get(10,
                        TimeUnit.SECONDS);
                try {
                    connection.write(HttpContent.builder(request(port, "/1")).content(Buffers.EMPTY_BUFFER).last(true).build());
                    connection.write(HttpContent.builder(request(port, "/3")).content(Buffers.EMPTY_BUFFER).last(true).build());
                    final HttpResponsePacket response = responses.poll(10, TimeUnit.SECONDS);
                    assertNotNull("response", response);
                    assertEquals("3", response.getHeader("x-response"));
                    assertNull("a field of the rejected block in the response", response.getHeader("x-rejected"));
                } finally {
                    connection.closeSilently();
                }
            } finally {
                transport.shutdownNow();
            }
            peer.join(10000);
            assertNull("peer", serverError.get());
        }
    }

    private static HttpRequestPacket request(final int port, final String uri) {
        return HttpRequestPacket.builder().method(Method.GET).uri(uri).protocol(Protocol.HTTP_2_0).host("localhost:" + port).build();
    }

    /**
     * Reads frames up to the HEADERS frame of the given stream, acknowledging SETTINGS.
     */
    private static void awaitRequest(final InputStream in, final OutputStream out, final int streamId) throws IOException {
        while (true) {
            final RawHttp2.Frame frame = RawHttp2.readFrame(in);
            if (frame.type == RawHttp2.SETTINGS && (frame.flags & RawHttp2.ACK) == 0) {
                RawHttp2.writeFrame(out, RawHttp2.SETTINGS, RawHttp2.ACK, 0, new byte[0]);
            } else if (frame.type == RawHttp2.HEADERS && frame.streamId == streamId) {
                return;
            }
        }
    }

    /**
     * Sends a request header block and returns "status body" of the response, or the frame that ended it otherwise.
     */
    private static String exchange(final InputStream in, final OutputStream out, final RawHttp2.BlockDecoder decoder, final int streamId,
            final byte[] block) throws IOException {
        RawHttp2.writeFrame(out, RawHttp2.HEADERS, END_HEADERS | END_STREAM, streamId, block);
        return readResponse(in, out, decoder, streamId);
    }

    /**
     * Reads the response of the given stream and returns "status body", or the frame that ended it otherwise.
     */
    private static String readResponse(final InputStream in, final OutputStream out, final RawHttp2.BlockDecoder decoder, final int streamId)
            throws IOException {
        String status = null;
        final StringBuilder body = new StringBuilder();
        while (true) {
            final RawHttp2.Frame frame;
            try {
                frame = RawHttp2.readFrame(in);
            } catch (IOException e) {
                return "connection closed: " + e;
            }
            if (frame.type == RawHttp2.SETTINGS && (frame.flags & RawHttp2.ACK) == 0) {
                RawHttp2.writeFrame(out, RawHttp2.SETTINGS, RawHttp2.ACK, 0, new byte[0]);
                continue;
            }
            if (frame.type == RawHttp2.GOAWAY) {
                return "GOAWAY";
            }
            if (frame.type == RawHttp2.HEADERS) {
                // every block of the server is decoded, to keep the decoder in sync
                final Map<String, String> fields = decoder.decode(frame.payload);
                if (frame.streamId == streamId) {
                    status = fields.get(":status");
                }
            } else if (frame.type == RawHttp2.DATA && frame.streamId == streamId) {
                body.append(new String(frame.payload, StandardCharsets.ISO_8859_1));
            } else if (frame.type == RawHttp2.RST_STREAM && frame.streamId == streamId) {
                return "RST_STREAM";
            }
            if (frame.streamId == streamId && frame.isEndStream()) {
                return status + " " + body;
            }
        }
    }
}
