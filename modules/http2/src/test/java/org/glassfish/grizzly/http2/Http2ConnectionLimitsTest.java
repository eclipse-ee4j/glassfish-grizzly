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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the per-connection limits of {@link Http2Configuration}: a peer going beyond a budget gets the connection
 * ended with {@code GOAWAY(ENHANCE_YOUR_CALM)}, a peer staying within the budgets does not. The client writes raw
 * frames, to send frame sequences a regular client library would not produce.
 */
public class Http2ConnectionLimitsTest extends AbstractHttp2Test {

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_PRIORITY = 0x2;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_PUSH_PROMISE = 0x5;
    private static final int TYPE_PING = 0x6;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int TYPE_WINDOW_UPDATE = 0x8;
    private static final int TYPE_CONTINUATION = 0x9;
    private static final int TYPE_UNKNOWN = 0xfa;

    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;
    private static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;

    private static final int OVER_BUDGET = 50;
    private static final int BUDGET = 10;

    private final AtomicInteger dispatched = new AtomicInteger();
    private HttpServer httpServer;
    private int port;

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        httpServer = createServer(null, port, false, HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                dispatched.incrementAndGet();
                response.setContentType("text/plain");
                response.getWriter().write("ok");
            }
        }, "/"));
        httpServer.getListener("grizzly").getKeepAlive().setIdleTimeoutInSeconds(-1);
    }

    @After
    public void after() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    /**
     * Each stream is reset right after its request, so the concurrent streams limit alone never applies; the reset budget
     * does.
     */
    @Test
    public void resetsOverBudgetEndConnection() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < OVER_BUDGET; i++) {
            final int streamId = 2 * i + 1;
            writeRequest(out, streamId);
            writeFrame(out, TYPE_RST_STREAM, 0, streamId, int32(ErrorCode.CANCEL.getCode()));
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM.getCode(), sendAndAwaitGoAway(out.toByteArray()));
        assertTrue("requests dispatched: " + dispatched.get(), dispatched.get() <= BUDGET + 1);
    }

    /**
     * Streams the server resets because of a stream error in the client's frames, here a WINDOW_UPDATE of zero on a
     * stream, count toward the reset budget as well.
     */
    @Test
    public void serverResetsCausedByPeerCountTowardResetBudget() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < OVER_BUDGET; i++) {
            final int streamId = 2 * i + 1;
            writeRequest(out, streamId);
            writeFrame(out, TYPE_WINDOW_UPDATE, 0, streamId, int32(0));
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM.getCode(), sendAndAwaitGoAway(out.toByteArray()));
    }

    @Test
    public void pingsOverBudgetEndConnection() throws Exception {
        assertControlFramesOverBudgetEndConnection(TYPE_PING, 0, 0, new byte[8]);
    }

    @Test
    public void settingsOverBudgetEndConnection() throws Exception {
        assertControlFramesOverBudgetEndConnection(TYPE_SETTINGS, 0, 0, new byte[0]);
    }

    @Test
    public void priorityFramesOverBudgetEndConnection() throws Exception {
        assertControlFramesOverBudgetEndConnection(TYPE_PRIORITY, 0, 1, new byte[] { 0, 0, 0, 0, 16 });
    }

    /**
     * Frames of unknown types are ignored, but count toward the control frame budget.
     */
    @Test
    public void unknownFramesOverBudgetEndConnection() throws Exception {
        assertControlFramesOverBudgetEndConnection(TYPE_UNKNOWN, 0, 0, new byte[0]);
    }

    @Test
    public void emptyDataFramesOverBudgetEndConnection() throws Exception {
        http2Addon.getConfiguration().setMaxControlFrameBurst(BUDGET);
        http2Addon.getConfiguration().setMaxControlFramesPerSecond(1);
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // a request with a body that never comes
        writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, requestHeaderBlock());
        for (int i = 0; i < OVER_BUDGET; i++) {
            writeFrame(out, TYPE_DATA, 0, 1, new byte[0]);
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM.getCode(), sendAndAwaitGoAway(out.toByteArray()));
    }

    /**
     * A header block split into more CONTINUATION frames than allowed, here without payload, which the header list size
     * limit does not see.
     */
    @Test
    public void continuationFramesOverLimitEndConnection() throws Exception {
        http2Addon.getConfiguration().setMaxContinuationFrames(BUDGET);
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM, 1, requestHeaderBlock());
        for (int i = 0; i < OVER_BUDGET; i++) {
            writeFrame(out, TYPE_CONTINUATION, 0, 1, new byte[0]);
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM.getCode(), sendAndAwaitGoAway(out.toByteArray()));
        assertEquals(0, dispatched.get());
    }

    /**
     * A header block split into as many CONTINUATION frames as allowed is still a valid request.
     */
    @Test
    public void headerBlockWithinContinuationLimitIsServed() throws Exception {
        http2Addon.getConfiguration().setMaxContinuationFrames(BUDGET);
        httpServer.start();

        final byte[] block = requestHeaderBlock();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM, 1, new byte[] { block[0] });
        for (int i = 1; i < BUDGET; i++) {
            writeFrame(out, TYPE_CONTINUATION, 0, 1, new byte[0]);
        }
        writeFrame(out, TYPE_CONTINUATION, FLAG_END_HEADERS, 1, Arrays.copyOfRange(block, 1, block.length));

        try (Socket socket = connect()) {
            send(socket, out.toByteArray());
            assertNull(awaitResponseOrGoAway(socket, 1));
        }
        assertEquals(1, dispatched.get());
    }

    /**
     * A header block that outgrows the header list size in a CONTINUATION frame, rather than in the HEADERS frame, is
     * answered like any other oversized header block, without dispatching the request.
     */
    @Test
    public void headerBlockOversizedInContinuationIsRefused() throws Exception {
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM, 1, requestHeaderBlock());
        writeFrame(out, TYPE_CONTINUATION, FLAG_END_HEADERS, 1, new byte[Http2Configuration.DEFAULT_MAX_HEADER_LIST_SIZE + 1]);

        try (Socket socket = connect()) {
            send(socket, out.toByteArray());
            assertNull(awaitResponseOrGoAway(socket, 1));
        }
        assertEquals(0, dispatched.get());
    }

    /**
     * With every limit disabled, a connection far beyond the default budgets is still served.
     */
    @Test
    public void disabledLimitsDoNotEndConnection() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(0);
        http2Addon.getConfiguration().setMaxControlFrameBurst(0);
        http2Addon.getConfiguration().setMaxContinuationFrames(0);
        httpServer.start();

        final int resets = Http2Configuration.DEFAULT_MAX_RESET_BURST + Http2Configuration.DEFAULT_MAX_CONTROL_FRAME_BURST;
        final int continuations = 2 * Http2Configuration.DEFAULT_MAX_CONTINUATION_FRAMES;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int streamId = 1;
        for (int i = 0; i < resets; i++, streamId += 2) {
            writeRequest(out, streamId);
            writeFrame(out, TYPE_RST_STREAM, 0, streamId, int32(ErrorCode.CANCEL.getCode()));
            writeFrame(out, TYPE_PING, 0, 0, new byte[8]);
            writeFrame(out, TYPE_UNKNOWN, 0, 0, new byte[0]);
        }
        writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM, streamId, requestHeaderBlock());
        for (int i = 0; i < continuations; i++) {
            writeFrame(out, TYPE_CONTINUATION, 0, streamId, new byte[0]);
        }
        writeFrame(out, TYPE_CONTINUATION, FLAG_END_HEADERS, streamId, new byte[0]);

        try (Socket socket = connect()) {
            send(socket, out.toByteArray());
            assertNull(awaitResponseOrGoAway(socket, streamId));
        }
    }

    /**
     * The defaults leave room for resets and control frames a regular client sends.
     */
    @Test
    public void defaultsDoNotEndRegularConnection() throws Exception {
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int streamId = 1;
        for (int i = 0; i < 100; i++, streamId += 2) {
            writeRequest(out, streamId);
            writeFrame(out, TYPE_RST_STREAM, 0, streamId, int32(ErrorCode.CANCEL.getCode()));
            writeFrame(out, TYPE_PING, 0, 0, new byte[8]);
        }
        writeRequest(out, streamId);

        try (Socket socket = connect()) {
            send(socket, out.toByteArray());
            assertNull(awaitResponseOrGoAway(socket, streamId));
        }
    }

    /**
     * The server advertises SETTINGS_MAX_CONCURRENT_STREAMS even when it is the default: a client that is not told the
     * limit may assume there is none, and get streams over it refused and charged to its reset budget.
     */
    @Test
    public void serverAdvertisesDefaultMaxConcurrentStreams() throws Exception {
        httpServer.start();

        try (Socket socket = connect()) {
            send(socket, new byte[0]);
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            Frame frame;
            do {
                frame = Frame.read(in);
            } while (frame.type != TYPE_SETTINGS || (frame.flags & FLAG_ACK) != 0);

            Integer maxConcurrentStreams = null;
            for (int i = 0; i + 6 <= frame.payload.length; i += 6) {
                final int id = (frame.payload[i] & 0xff) << 8 | frame.payload[i + 1] & 0xff;
                if (id == SETTINGS_MAX_CONCURRENT_STREAMS) {
                    maxConcurrentStreams = int32(frame.payload, i + 2);
                }
            }
            assertEquals("SETTINGS_MAX_CONCURRENT_STREAMS not advertised", Integer.valueOf(100), maxConcurrentStreams);
        }
    }

    /**
     * Declining a pushed stream is the client's right and the push was the server's choice, so it does not count toward
     * the reset budget.
     */
    @Test
    public void declinedPushesDoNotCountTowardResetBudget() throws Exception {
        assertDeclinedPushesDoNotCountTowardResetBudget(false);
    }

    /**
     * As {@link #declinedPushesDoNotCountTowardResetBudget()}, but the client declines each push only once it has been
     * answered, and closed streams are removed from the session as soon as any stream is deregistered: the pushed stream
     * is then usually gone before the client's RST_STREAM arrives, which must not make the decline count either.
     */
    @Test
    public void declinedPushesOfRemovedStreamsDoNotCountTowardResetBudget() throws Exception {
        http2Addon.getConfiguration().setStreamsHighWaterMark(0f);
        http2Addon.getConfiguration().setCleanFrequencyCheck(-1);
        assertDeclinedPushesDoNotCountTowardResetBudget(true);
    }

    private void assertDeclinedPushesDoNotCountTowardResetBudget(final boolean declineAnsweredPushes) throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                request.newPushBuilder().path("/").push();
                response.setContentType("text/plain");
                response.getWriter().write("pushed");
            }
        }, "/push");
        httpServer.start();

        try (Socket socket = connect()) {
            send(socket, new byte[0]);
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            final OutputStream os = socket.getOutputStream();
            int streamId = 1;
            for (int i = 0; i < OVER_BUDGET; i++, streamId += 2) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, streamId, requestHeaderBlock("/push"));
                os.write(out.toByteArray());
                os.flush();

                final int promisedStreamId = awaitPushPromise(in, streamId);
                if (declineAnsweredPushes) {
                    awaitStreamsEnd(in, streamId, promisedStreamId);
                }
                out.reset();
                writeFrame(out, TYPE_RST_STREAM, 0, promisedStreamId, int32(ErrorCode.CANCEL.getCode()));
                os.write(out.toByteArray());
                os.flush();
            }

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRequest(out, streamId);
            os.write(out.toByteArray());
            os.flush();
            assertNull("the connection was ended", awaitResponseOrGoAway(socket, streamId));
        }
    }

    /**
     * Only the first RST_STREAM of a pushed stream declines the push for free: resetting the same stream again is
     * charged, so a pushed stream cannot be used to send resets for free.
     */
    @Test
    public void repeatedResetsOfPushedStreamCountTowardResetBudget() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                request.newPushBuilder().path("/").push();
                response.getWriter().write("pushed");
            }
        }, "/push");
        httpServer.start();

        try (Socket socket = connect()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, requestHeaderBlock("/push"));
            send(socket, out.toByteArray());
            final int promisedStreamId = awaitPushPromise(new DataInputStream(socket.getInputStream()), 1);

            out.reset();
            for (int i = 0; i < OVER_BUDGET; i++) {
                writeFrame(out, TYPE_RST_STREAM, 0, promisedStreamId, int32(ErrorCode.CANCEL.getCode()));
            }
            final OutputStream os = socket.getOutputStream();
            os.write(out.toByteArray());
            os.flush();
            assertEquals(Integer.valueOf(ErrorCode.ENHANCE_YOUR_CALM.getCode()), awaitResponseOrGoAway(socket, -1));
            assertConnectionClosed(socket);
        }
    }

    /**
     * DATA on a stream that is closed already, as a client may send while it has not yet learned that the stream was
     * answered or refused, gets RST_STREAM out of a budget of its own. Past it, such frames are ignored, and the connection
     * stays open.
     */
    @Test
    public void framesOnClosedStreamsDoNotEndConnection() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        httpServer.start();

        try (Socket socket = connect()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRequest(out, 1);
            send(socket, out.toByteArray());
            assertNull(awaitResponseOrGoAway(socket, 1));

            out.reset();
            for (int i = 0; i < OVER_BUDGET; i++) {
                writeFrame(out, TYPE_DATA, 0, 1, new byte[] { 'x' });
            }
            writeRequest(out, 3);
            final OutputStream os = socket.getOutputStream();
            os.write(out.toByteArray());
            os.flush();

            final DataInputStream in = new DataInputStream(socket.getInputStream());
            int resetsOfClosedStream = 0;
            while (true) {
                final Frame frame = Frame.read(in);
                assertFalse("the connection was ended", frame.type == TYPE_GOAWAY);
                if (frame.type == TYPE_RST_STREAM && frame.streamId == 1) {
                    resetsOfClosedStream++;
                } else if (frame.streamId == 3 && (frame.type == TYPE_HEADERS || frame.type == TYPE_DATA) && (frame.flags & FLAG_END_STREAM) != 0) {
                    break;
                }
            }
            // the budget, plus what may have been refilled in the meantime; the rest was ignored
            assertTrue("RST_STREAM frames for the closed stream: " + resetsOfClosedStream,
                    resetsOfClosedStream >= BUDGET && resetsOfClosedStream < OVER_BUDGET);
        }
    }

    /**
     * When the connection is closed after the server sent GOAWAY, the streams the GOAWAY left open, up to its last stream
     * ID, are closed along with it: here the input of a request whose body never arrived is terminated.
     */
    @Test
    public void streamsAreClosedWithConnectionAfterGoAway() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        final AtomicReference<Http2Stream> uploadStream = new AtomicReference<>();
        final CountDownLatch uploadEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                uploadStream.set(Http2Stream.getStreamFor(request.getRequest()));
                uploadEntered.countDown();
                // much longer than the test waits below: completing the request would close the stream as well
                release.await(60, TimeUnit.SECONDS);
            }
        }, "/upload");
        httpServer.start();

        try (Socket socket = connect()) {
            // POST whose body never comes
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, requestHeaderBlock((byte) 0x83, "/upload"));
            send(socket, out.toByteArray());
            assertTrue("the upload did not reach the handler", uploadEntered.await(10, TimeUnit.SECONDS));
            assertFalse(uploadStream.get().inputBuffer.isClosed());

            out.reset();
            for (int i = 1; i <= OVER_BUDGET; i++) {
                final int streamId = 2 * i + 1;
                writeRequest(out, streamId);
                writeFrame(out, TYPE_RST_STREAM, 0, streamId, int32(ErrorCode.CANCEL.getCode()));
            }
            final OutputStream os = socket.getOutputStream();
            os.write(out.toByteArray());
            os.flush();
            assertEquals(Integer.valueOf(ErrorCode.ENHANCE_YOUR_CALM.getCode()), awaitResponseOrGoAway(socket, -1));
            assertConnectionClosed(socket);

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!uploadStream.get().inputBuffer.isClosed() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("the input of the open stream was not terminated with the connection", uploadStream.get().inputBuffer.isClosed());
        } finally {
            release.countDown();
        }
    }

    /**
     * A connection ended for exceeding a limit is logged at INFO, with the limit; that it is logged only once per
     * connection is checked by Http2SessionTest.
     */
    @Test
    public void exceededLimitIsLogged() throws Exception {
        final List<LogRecord> records = new ArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public synchronized void publish(final LogRecord record) {
                if (record.getLevel() == Level.INFO && String.valueOf(record.getMessage()).contains("ENHANCE_YOUR_CALM")) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger logger = Logger.getLogger(Http2Session.class.getName());
        logger.addHandler(handler);
        try {
            assertControlFramesOverBudgetEndConnection(TYPE_PING, 0, 0, new byte[8]);
            synchronized (handler) {
                // whether it is logged once only is checked by Http2SessionTest, without other connections around
                boolean logged = false;
                for (final LogRecord record : records) {
                    logged |= "Too many control frames.".equals(record.getParameters()[1]);
                }
                assertTrue("INFO records: " + records.size(), logged);
            }
        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * A request answered with 431 because its header block is too large is not processed, so its stream must not keep a
     * slot of the concurrent streams limit: more such requests than the limit allows still leave room for a regular one.
     */
    @Test
    public void oversizedHeaderBlocksDoNotHoldStreamSlots() throws Exception {
        http2Addon.getConfiguration().setMaxConcurrentStreams(2);
        httpServer.start();

        try (Socket socket = connect()) {
            send(socket, new byte[0]);
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            int streamId = 1;
            for (int i = 0; i < 3; i++, streamId += 2) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM, streamId, requestHeaderBlock());
                writeFrame(out, TYPE_CONTINUATION, FLAG_END_HEADERS, streamId, new byte[Http2Configuration.DEFAULT_MAX_HEADER_LIST_SIZE + 1]);
                write(socket, out);
                assertNull("oversized request " + (i + 1) + " was reset", awaitStreamEnd(in, streamId));
            }

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRequest(out, streamId);
            write(socket, out);
            assertNull("the regular request was reset", awaitStreamEnd(in, streamId));
        }
        assertEquals(1, dispatched.get());
    }

    /**
     * A request whose header block is a stream error (here a duplicate :path) is answered with RST_STREAM; its stream
     * must not keep a slot of the concurrent streams limit either.
     */
    @Test
    public void malformedHeaderBlocksDoNotHoldStreamSlots() throws Exception {
        http2Addon.getConfiguration().setMaxConcurrentStreams(2);
        httpServer.start();

        final byte[] block = requestHeaderBlock();
        final byte[] duplicatePath = Arrays.copyOf(block, block.length + 1);
        duplicatePath[block.length] = (byte) 0x84; // :path: / once more

        try (Socket socket = connect()) {
            send(socket, new byte[0]);
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            int streamId = 1;
            for (int i = 0; i < 3; i++, streamId += 2) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, streamId, duplicatePath);
                write(socket, out);
                assertEquals("malformed request " + (i + 1), Integer.valueOf(ErrorCode.PROTOCOL_ERROR.getCode()), awaitStreamEnd(in, streamId));
            }

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRequest(out, streamId);
            write(socket, out);
            assertNull("the regular request was reset", awaitStreamEnd(in, streamId));
        }
        assertEquals(1, dispatched.get());
    }

    /**
     * DATA on a stream the client has half-closed while the server is still serving it makes the server close the
     * stream: unlike DATA on a stream that is closed already, it counts toward the reset budget.
     */
    @Test
    public void dataOnStreamStillBeingServedCountsTowardResetBudget() throws Exception {
        http2Addon.getConfiguration().setMaxResetBurst(BUDGET);
        http2Addon.getConfiguration().setMaxResetsPerSecond(1);
        final CountDownLatch release = new CountDownLatch(1);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                // keeps the response from completing, so the streams are still being served when the DATA arrives
                release.await(60, TimeUnit.SECONDS);
            }
        }, "/block");
        httpServer.start();

        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < OVER_BUDGET; i++) {
                final int streamId = 2 * i + 1;
                writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, streamId, requestHeaderBlock("/block"));
                writeFrame(out, TYPE_DATA, 0, streamId, new byte[] { 'x' });
            }
            assertEquals(ErrorCode.ENHANCE_YOUR_CALM.getCode(), sendAndAwaitGoAway(out.toByteArray()));
        } finally {
            release.countDown();
        }
    }

    /**
     * A WINDOW_UPDATE on a stream that is closed and has been removed already, as a client may send while it has not yet
     * learned that the stream was answered, is ignored: it must not end the connection.
     */
    @Test
    public void windowUpdateOnRemovedStreamIsIgnored() throws Exception {
        // closed streams are removed from the session as soon as any stream is deregistered
        http2Addon.getConfiguration().setStreamsHighWaterMark(0f);
        http2Addon.getConfiguration().setCleanFrequencyCheck(-1);
        httpServer.start();

        try (Socket socket = connect()) {
            send(socket, new byte[0]);
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            for (final int streamId : new int[] { 1, 3 }) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeRequest(out, streamId);
                write(socket, out);
                assertNull(awaitStreamEnd(in, streamId));
            }

            // stream 1 has been answered, and removed once stream 3 was deregistered
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeFrame(out, TYPE_WINDOW_UPDATE, 0, 1, int32(1024));
            writeRequest(out, 5);
            write(socket, out);
            assertNull("the request after the WINDOW_UPDATE was reset", awaitStreamEnd(in, 5));
        }
        assertEquals(3, dispatched.get());
    }

    /**
     * A WINDOW_UPDATE on a stream that is idle, one the client has not opened yet, remains a connection error (RFC 9113,
     * section 5.1).
     */
    @Test
    public void windowUpdateOnIdleStreamEndsConnection() throws Exception {
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, TYPE_WINDOW_UPDATE, 0, 99, int32(1024));
        assertEquals(ErrorCode.PROTOCOL_ERROR.getCode(), sendAndAwaitGoAway(out.toByteArray()));
    }

    // -------------------------------------------------------- Private Methods

    private static void write(final Socket socket, final ByteArrayOutputStream frames) throws IOException {
        final OutputStream os = socket.getOutputStream();
        os.write(frames.toByteArray());
        os.flush();
    }

    /**
     * Reads frames until the stream ends, with END_STREAM or RST_STREAM.
     *
     * @return the RST_STREAM error code, or {@code null} if the stream ended with END_STREAM.
     */
    private static Integer awaitStreamEnd(final DataInputStream in, final int streamId) throws IOException {
        while (true) {
            final Frame frame = Frame.read(in);
            assertFalse("the connection was ended", frame.type == TYPE_GOAWAY);
            if (frame.streamId != streamId) {
                continue;
            }
            if (frame.type == TYPE_RST_STREAM) {
                return int32(frame.payload, 0);
            }
            if ((frame.type == TYPE_HEADERS || frame.type == TYPE_DATA) && (frame.flags & FLAG_END_STREAM) != 0) {
                return null;
            }
        }
    }

    private static void assertNotGoAway(final Frame frame) {
        if (frame.type == TYPE_GOAWAY) {
            final String debugData = new String(frame.payload, 8, frame.payload.length - 8, StandardCharsets.US_ASCII);
            throw new AssertionError("the connection was ended with GOAWAY, error code " + int32(frame.payload, 4) + ": " + debugData);
        }
    }

    /**
     * Reads frames until both streams have ended with END_STREAM.
     */
    private static void awaitStreamsEnd(final DataInputStream in, final int streamId, final int otherStreamId) throws IOException {
        boolean ended = false;
        boolean otherEnded = false;
        while (!ended || !otherEnded) {
            final Frame frame = Frame.read(in);
            assertNotGoAway(frame);
            assertFalse("a stream was reset", frame.type == TYPE_RST_STREAM);
            if ((frame.type == TYPE_HEADERS || frame.type == TYPE_DATA) && (frame.flags & FLAG_END_STREAM) != 0) {
                ended |= frame.streamId == streamId;
                otherEnded |= frame.streamId == otherStreamId;
            }
        }
    }

    /**
     * Reads frames until a PUSH_PROMISE on the given stream arrives.
     *
     * @return the promised stream ID.
     */
    private static int awaitPushPromise(final DataInputStream in, final int streamId) throws IOException {
        while (true) {
            final Frame frame = Frame.read(in);
            assertNotGoAway(frame);
            if (frame.type == TYPE_PUSH_PROMISE && frame.streamId == streamId) {
                // no padding: the payload starts with the promised stream ID
                return int32(frame.payload, 0) & 0x7fffffff;
            }
        }
    }

    private void assertControlFramesOverBudgetEndConnection(final int type, final int flags, final int streamId, final byte[] payload)
            throws Exception {
        http2Addon.getConfiguration().setMaxControlFrameBurst(BUDGET);
        http2Addon.getConfiguration().setMaxControlFramesPerSecond(1);
        httpServer.start();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < OVER_BUDGET; i++) {
            writeFrame(out, type, flags, streamId, payload);
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM.getCode(), sendAndAwaitGoAway(out.toByteArray()));
    }

    private int sendAndAwaitGoAway(final byte[] frames) throws Exception {
        try (Socket socket = connect()) {
            send(socket, frames);
            final Integer errorCode = awaitResponseOrGoAway(socket, -1);
            assertTrue("no GOAWAY received", errorCode != null);
            assertConnectionClosed(socket);
            return errorCode;
        }
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static void send(final Socket socket, final byte[] frames) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PREFACE);
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        out.write(frames);
        final OutputStream os = socket.getOutputStream();
        try {
            os.write(out.toByteArray());
            os.flush();
        } catch (IOException e) {
            // the server may close the connection before all frames are written
        }
    }

    /**
     * Reads frames until a GOAWAY arrives or, if {@code streamId} is positive, the response on that stream ends.
     *
     * @return the GOAWAY error code, or {@code null} if the response ended first.
     */
    private static Integer awaitResponseOrGoAway(final Socket socket, final int streamId) throws IOException {
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        while (true) {
            final int length = in.readUnsignedByte() << 16 | in.readUnsignedShort();
            final int type = in.readUnsignedByte();
            final int flags = in.readUnsignedByte();
            final int frameStreamId = in.readInt() & 0x7fffffff;
            final byte[] payload = new byte[length];
            in.readFully(payload);

            if (type == TYPE_GOAWAY) {
                return (payload[4] & 0xff) << 24 | (payload[5] & 0xff) << 16 | (payload[6] & 0xff) << 8 | payload[7] & 0xff;
            }
            if (frameStreamId == streamId && streamId > 0) {
                assertFalse("the request was reset", type == TYPE_RST_STREAM);
                if ((type == TYPE_HEADERS || type == TYPE_DATA) && (flags & FLAG_END_STREAM) != 0) {
                    return null;
                }
            }
        }
    }

    private static void assertConnectionClosed(final Socket socket) throws IOException {
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        try {
            while (true) {
                in.readByte();
            }
        } catch (EOFException e) {
            // expected
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the connection was not closed after GOAWAY");
        } catch (IOException e) {
            // connection reset, also closed
        }
    }

    private void writeRequest(final ByteArrayOutputStream out, final int streamId) throws IOException {
        writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, streamId, requestHeaderBlock());
    }

    /**
     * {@code GET http://localhost<path>}, using the HPACK static table only, so every block can be decoded on its own.
     */
    private static byte[] requestHeaderBlock(final String path) {
        return requestHeaderBlock((byte) 0x82, path);
    }

    /**
     * @param method the HPACK static table index of the method: {@code 0x82} for GET, {@code 0x83} for POST
     */
    private static byte[] requestHeaderBlock(final byte method, final String path) {
        final byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        final byte[] authority = "localhost".getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(method);
        block.write(0x86); // :scheme: http
        block.write(0x04); // :path, literal without indexing
        block.write(pathBytes.length);
        block.write(pathBytes, 0, pathBytes.length);
        block.write(0x01); // :authority, literal without indexing
        block.write(authority.length);
        block.write(authority, 0, authority.length);
        return block.toByteArray();
    }

    /**
     * {@code GET http://localhost/}, using the HPACK static table only, so every block can be decoded on its own.
     */
    private static byte[] requestHeaderBlock() {
        final byte[] authority = "localhost".getBytes(StandardCharsets.US_ASCII);
        final byte[] block = new byte[5 + authority.length];
        block[0] = (byte) 0x82; // :method: GET
        block[1] = (byte) 0x86; // :scheme: http
        block[2] = (byte) 0x84; // :path: /
        block[3] = 0x01; // :authority, literal without indexing
        block[4] = (byte) authority.length;
        System.arraycopy(authority, 0, block, 5, authority.length);
        return block;
    }

    private static void writeFrame(final ByteArrayOutputStream out, final int type, final int flags, final int streamId, final byte[] payload)
            throws IOException {
        out.write(payload.length >>> 16);
        out.write(payload.length >>> 8);
        out.write(payload.length);
        out.write(type);
        out.write(flags);
        out.write(int32(streamId));
        out.write(payload);
    }

    private static byte[] int32(final int value) {
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    private static int int32(final byte[] bytes, final int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16 | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    private static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        private Frame(final int type, final int flags, final int streamId, final byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }

        static Frame read(final DataInputStream in) throws IOException {
            final int length = in.readUnsignedByte() << 16 | in.readUnsignedShort();
            final int type = in.readUnsignedByte();
            final int flags = in.readUnsignedByte();
            final int streamId = in.readInt() & 0x7fffffff;
            final byte[] payload = new byte[length];
            in.readFully(payload);
            return new Frame(type, flags, streamId, payload);
        }
    }
}
