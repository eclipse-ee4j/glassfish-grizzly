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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Responses to pipelined requests are corked and written together (see HttpServerFilter#onPipelinedInput): they must
 * still arrive complete, in order, and without waiting for input that has not arrived yet.
 */
public class PipelinedWriteCoalescingTest {

    private static final int BIG = 100_000;
    private static final int MID = 40_000;
    private static final long SLEEP_MILLIS = 2_000;
    private static final long NAP_MILLIS = 100;

    private HttpServer httpServer;
    private int port;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        httpServer = HttpServer.createSimpleServer(null, port);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                final String uri = request.getRequestURI();
                final String body = uri.startsWith("/big") ? "x".repeat(BIG) : uri.startsWith("/mid") ? "x".repeat(MID) : uri;
                if (uri.startsWith("/boom")) {
                    throw new IllegalStateException("boom");
                }
                if (uri.startsWith("/echo")) {
                    final String echo = new String(request.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    response.setContentType("text/plain");
                    response.setContentLength(echo.length());
                    response.getWriter().write(echo);
                    return;
                }
                if (uri.startsWith("/resumed")) {
                    // resumed right away: the pipelined requests behind it are processed by a READ run nested in this one,
                    // whose end enables READ interest again while this handler still runs
                    response.setContentType("text/plain");
                    response.setContentLength(body.length());
                    response.getWriter().write(body);
                    response.suspend();
                    response.resume();
                    Thread.sleep(NAP_MILLIS * 2);
                    return;
                }
                if (uri.startsWith("/sleep")) {
                    Thread.sleep(SLEEP_MILLIS);
                }
                if (uri.startsWith("/nap")) {
                    Thread.sleep(NAP_MILLIS);
                }
                response.setContentType("text/plain");
                response.setContentLength(body.length());
                if (uri.startsWith("/async")) {
                    response.suspend();
                    scheduler.schedule(() -> {
                        try {
                            response.getWriter().write(body);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        } finally {
                            response.resume();
                        }
                    }, 200, TimeUnit.MILLISECONDS);
                    return;
                }
                response.getWriter().write(body);
                if (uri.startsWith("/flush")) {
                    response.flush();
                }
            }
        }, "/");
        httpServer.start();
    }

    @After
    public void after() {
        httpServer.shutdownNow();
        scheduler.shutdownNow();
    }

    private TCPNIOTransport transport() {
        return httpServer.getListener("grizzly").getTransport();
    }

    /**
     * Each handler takes 100 ms. Written one by one, the responses would arrive about 100 ms apart; held back until
     * the batch is processed, they leave in one gathering write and arrive together.
     */
    @Test
    public void responsesToABatchOfPipelinedRequestsLeaveTogether() throws Exception {
        final int n = 8;
        try (Socket socket = connect()) {
            final StringBuilder requests = new StringBuilder();
            for (int i = 0; i < n; i++) {
                requests.append(get("/nap" + i));
            }
            send(socket, requests.toString());
            final InputStream in = socket.getInputStream();
            long first = 0;
            for (int i = 0; i < n; i++) {
                assertEquals("/nap" + i, readResponseBody(in));
                if (i == 0) {
                    first = System.nanoTime();
                }
            }
            final long spreadMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first);
            assertTrue("first to last response: " + spreadMillis + " ms", spreadMillis < NAP_MILLIS * 3);
        }
    }

    /**
     * The client half-closes right after sending a batch: the READ seeing EOF closes the connection, which must not
     * happen before the held-back responses are written.
     */
    @Test
    public void halfCloseAfterPipelinedBatchLosesNoResponse() throws Exception {
        final int n = 8;
        final StringBuilder requests = new StringBuilder();
        for (int i = 0; i < n; i++) {
            requests.append(get("/r" + i));
        }
        for (int iteration = 0; iteration < 300; iteration++) {
            try (Socket socket = connect()) {
                send(socket, requests.toString());
                socket.shutdownOutput();
                final InputStream in = socket.getInputStream();
                for (int i = 0; i < n; i++) {
                    assertEquals("iteration " + iteration, "/r" + i, readResponseBody(in));
                }
                assertEquals(-1, in.read());
            }
        }
    }

    @Test
    public void halfCloseAfterPipelinedBatchWithResumedResponseLosesNoResponse() throws Exception {
        final String requests = get("/a") + get("/resumed") + get("/b") + get("/c");
        for (int iteration = 0; iteration < 20; iteration++) {
            try (Socket socket = connect()) {
                send(socket, requests);
                socket.shutdownOutput();
                final InputStream in = socket.getInputStream();
                assertEquals("iteration " + iteration, "/a", readResponseBody(in));
                assertEquals("iteration " + iteration, "/resumed", readResponseBody(in));
                assertEquals("iteration " + iteration, "/b", readResponseBody(in));
                assertEquals("iteration " + iteration, "/c", readResponseBody(in));
            }
        }
    }

    /**
     * The client sends the body of a request with "Expect: 100-continue" only after the 100 Continue response, which the
     * handler blocking on the body must not hold back, nor the response pipelined before it.
     */
    @Test
    public void continueResponseIsNotHeldBack() throws Exception {
        try (Socket socket = connect()) {
            send(socket, get("/a") + "POST /echo HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n");
            final InputStream in = socket.getInputStream();
            assertEquals("/a", readResponseBody(in));
            assertEquals("HTTP/1.1 100 Continue", readLine(in));
            assertEquals("", readLine(in));
            send(socket, "hello");
            assertEquals("hello", readResponseBody(in));
        }
    }

    /**
     * A handler blocking until the write queue drains below its limit must not wait for writes held back on its own
     * thread.
     */
    @Test
    public void blockingResponseIsNotStuckBehindHeldBackWrites() throws Exception {
        transport().getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(16384);
        try (Socket socket = connect()) {
            send(socket, get("/z") + get("/mid") + get("/a"));
            final InputStream in = socket.getInputStream();
            assertEquals("/z", readResponseBody(in));
            assertEquals(MID, readResponseBody(in).length());
            assertEquals("/a", readResponseBody(in));
        }
    }

    @Test
    public void explicitFlushIsNotHeldBackByTheNextPipelinedRequest() throws Exception {
        try (Socket socket = connect()) {
            final long start = System.nanoTime();
            send(socket, get("/flush") + get("/sleep"));
            final InputStream in = socket.getInputStream();
            assertEquals("/flush", readResponseBody(in));
            final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("flushed response arrived after " + millis + " ms", millis < SLEEP_MILLIS / 2);
            assertEquals("/sleep", readResponseBody(in));
        }
    }

    @Test
    public void suspendedResponseInPipelinedBatch() throws Exception {
        try (Socket socket = connect()) {
            send(socket, get("/a") + get("/async") + get("/b"));
            final InputStream in = socket.getInputStream();
            assertEquals("/a", readResponseBody(in));
            assertEquals("/async", readResponseBody(in));
            assertEquals("/b", readResponseBody(in));
        }
    }

    @Test
    public void failingHandlerInPipelinedBatch() throws Exception {
        try (Socket socket = connect()) {
            send(socket, get("/a") + get("/boom") + get("/c"));
            final String all = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(all, all.startsWith("HTTP/1.1 200"));
            assertTrue(all, all.contains("\r\n\r\n/aHTTP/1.1 500"));
            // the error response closes the connection: the request pipelined after it is not answered
            assertTrue(all, all.contains("Connection: close"));
            assertTrue(all, all.endsWith("</html>"));
        }
    }

    @Test
    public void pipelinedResponsesArriveInOrder() throws Exception {
        try (Socket socket = connect()) {
            final StringBuilder requests = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                requests.append(get("/r" + i));
            }
            send(socket, requests.toString());
            final InputStream in = socket.getInputStream();
            for (int i = 0; i < 32; i++) {
                assertEquals("/r" + i, readResponseBody(in));
            }
        }
    }

    @Test
    public void responseIsNotHeldBackByAnIncompletePipelinedRequest() throws Exception {
        try (Socket socket = connect()) {
            final String second = get("/second");
            send(socket, get("/first") + second.substring(0, 10));
            final InputStream in = socket.getInputStream();
            // must arrive although the second request is still incomplete (socket timeout fails the test otherwise)
            assertEquals("/first", readResponseBody(in));

            send(socket, second.substring(10));
            assertEquals("/second", readResponseBody(in));
        }
    }

    @Test
    public void largePipelinedResponsesAreComplete() throws Exception {
        try (Socket socket = connect()) {
            send(socket, get("/big1") + get("/small") + get("/big2") + get("/big3"));
            final InputStream in = socket.getInputStream();
            assertEquals(BIG, readResponseBody(in).length());
            assertEquals("/small", readResponseBody(in));
            assertEquals(BIG, readResponseBody(in).length());
            assertEquals(BIG, readResponseBody(in).length());
        }
    }

    @Test
    public void connectionCloseAfterPipelinedRequestsFlushesAllResponses() throws Exception {
        try (Socket socket = connect()) {
            send(socket, get("/a") + get("/b") + "GET /c HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            final InputStream in = socket.getInputStream();
            assertEquals("/a", readResponseBody(in));
            assertEquals("/b", readResponseBody(in));
            assertEquals("/c", readResponseBody(in));
            assertEquals(-1, in.read());
        }
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", port), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static String get(final String uri) {
        return "GET " + uri + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
    }

    private static void send(final Socket socket, final String data) throws IOException {
        final OutputStream out = socket.getOutputStream();
        out.write(data.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Reads one response (status line, headers, Content-Length body) and returns its body.
     */
    private static String readResponseBody(final InputStream in) throws IOException {
        final String status = readLine(in);
        assertTrue(status, status.startsWith("HTTP/1.1 200"));
        int contentLength = -1;
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            final int colon = line.indexOf(':');
            if (line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        assertTrue("missing Content-Length", contentLength >= 0);
        final byte[] body = in.readNBytes(contentLength);
        assertEquals(contentLength, body.length);
        return new String(body, StandardCharsets.US_ASCII);
    }

    private static String readLine(final InputStream in) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\n') {
            if (b == -1) {
                throw new IOException("unexpected end of stream after: " + line);
            }
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII).trim();
    }
}
