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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int TYPE_PING = 0x6;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int TYPE_WINDOW_UPDATE = 0x8;
    private static final int TYPE_CONTINUATION = 0x9;
    private static final int TYPE_UNKNOWN = 0xfa;

    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;

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

    // -------------------------------------------------------- Private Methods

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
}
