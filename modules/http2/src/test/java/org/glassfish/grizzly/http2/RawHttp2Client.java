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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal prior-knowledge cleartext HTTP/2 client over a plain socket, for tests that need to send frame sequences a
 * well-behaved client never would (HEADERS immediately followed by RST_STREAM, a zero WINDOW_UPDATE, ...). Requests
 * are always {@code GET /} with a fixed HPACK encoding that doesn't use the dynamic table, so any stream may be
 * dropped by the server without desynchronizing the header compression state.
 */
final class RawHttp2Client implements Closeable {

    static final int TYPE_DATA = 0x0;
    static final int TYPE_HEADERS = 0x1;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_PUSH_PROMISE = 0x5;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_WINDOW_UPDATE = 0x8;

    static final int FLAG_END_STREAM = 0x1;
    static final int FLAG_END_HEADERS = 0x4;
    static final int FLAG_PADDED = 0x8;
    static final int FLAG_ACK = 0x1;

    static final int ERROR_PROTOCOL_ERROR = 0x1;
    static final int ERROR_STREAM_CLOSED = 0x5;
    static final int ERROR_REFUSED_STREAM = 0x7;
    static final int ERROR_CANCEL = 0x8;
    static final int ERROR_ENHANCE_YOUR_CALM = 0xb;

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Socket socket;
    private final OutputStream out;
    private final byte[] authority;

    private final AtomicInteger goAwayErrorCode = new AtomicInteger(-1);
    private final CountDownLatch goAwayReceived = new CountDownLatch(1);
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private final Set<Integer> respondedStreams = ConcurrentHashMap.newKeySet();
    private final Set<Integer> completedStreams = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Integer> resetByServer = new ConcurrentHashMap<>();
    private final AtomicInteger serverResetFrames = new AtomicInteger();
    private final BlockingQueue<Integer> promisedStreams = new LinkedBlockingQueue<>();
    private final Map<Integer, Long> serverSettings = new ConcurrentHashMap<>();
    private final CountDownLatch serverSettingsReceived = new CountDownLatch(1);

    private int nextStreamId = 1;

    RawHttp2Client(final int port) throws IOException {
        this(port, -1);
    }

    /**
     * @param initialWindowSize the SETTINGS_INITIAL_WINDOW_SIZE to announce, or -1 to keep the default
     */
    RawHttp2Client(final int port, final int initialWindowSize) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress("localhost", port), 10000);
        out = new BufferedOutputStream(socket.getOutputStream());
        authority = ("localhost:" + port).getBytes(StandardCharsets.US_ASCII);

        out.write(PREFACE);
        if (initialWindowSize < 0) {
            writeFrame(TYPE_SETTINGS, 0, 0, new byte[0]);
        } else {
            final byte[] value = int32(initialWindowSize);
            writeFrame(TYPE_SETTINGS, 0, 0, new byte[] { 0, 0x4, value[0], value[1], value[2], value[3] });
        }
        out.flush();

        final Thread reader = new Thread(this::readLoop, "raw-http2-client-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Sends a HEADERS frame (GET /) on a fresh client stream.
     *
     * @return the ID of the new stream
     */
    synchronized int sendRequest(final boolean endStream) throws IOException {
        final int streamId = nextStreamId;
        nextStreamId += 2;
        sendRequest(streamId, endStream);
        return streamId;
    }

    synchronized void sendRequest(final int streamId, final boolean endStream) throws IOException {
        final ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x82); // :method: GET
        block.write(0x86); // :scheme: http
        block.write(0x84); // :path: /
        block.write(0x01); // :authority, literal without indexing, indexed name
        block.write(authority.length);
        block.write(authority);
        writeFrame(TYPE_HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, block.toByteArray());
    }

    synchronized void sendRstStream(final int streamId, final int errorCode) throws IOException {
        writeFrame(TYPE_RST_STREAM, 0, streamId, int32(errorCode));
    }

    synchronized void sendData(final int streamId, final byte[] data, final boolean endStream) throws IOException {
        writeFrame(TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, data);
    }

    synchronized void sendWindowUpdate(final int streamId, final int delta) throws IOException {
        writeFrame(TYPE_WINDOW_UPDATE, 0, streamId, int32(delta));
    }

    synchronized void flush() throws IOException {
        out.flush();
    }

    /**
     * @return the error code of the GOAWAY frame received, or -1 if there was none (yet)
     */
    int getGoAwayErrorCode() {
        return goAwayErrorCode.get();
    }

    boolean awaitGoAway(final long timeoutMillis) throws InterruptedException {
        return goAwayReceived.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean awaitClosed(final long timeoutMillis) throws InterruptedException {
        return connectionClosed.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean isClosedByServer() {
        return connectionClosed.getCount() == 0;
    }

    /**
     * @return {@code true} once a response HEADERS frame has arrived for the stream
     */
    boolean awaitResponse(final int streamId, final long timeoutMillis) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!respondedStreams.contains(streamId) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return respondedStreams.contains(streamId);
    }

    /**
     * @return {@code true} once the frame with END_STREAM of the response has arrived for the stream
     */
    boolean awaitComplete(final int streamId, final long timeoutMillis) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!completedStreams.contains(streamId) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return completedStreams.contains(streamId);
    }

    /**
     * @return the ID of the next stream the server has promised to push, or -1 if no PUSH_PROMISE arrived in time
     */
    int awaitPushPromise(final long timeoutMillis) throws InterruptedException {
        final Integer streamId = promisedStreams.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        return streamId == null ? -1 : streamId;
    }

    /**
     * @return the number of RST_STREAM frames the server has sent, for any stream
     */
    int getServerResetFrameCount() {
        return serverResetFrames.get();
    }

    /**
     * @return the error code of the RST_STREAM the server sent for the stream, or -1 if it sent none (yet)
     */
    int getServerResetCode(final int streamId) {
        final Integer code = resetByServer.get(streamId);
        return code == null ? -1 : code;
    }

    /**
     * @return the parameters of the first SETTINGS frame (not an ACK) the server has sent, by identifier, or
     * {@code null} if none arrived in time
     */
    Map<Integer, Long> awaitServerSettings(final long timeoutMillis) throws InterruptedException {
        return serverSettingsReceived.await(timeoutMillis, TimeUnit.MILLISECONDS) ? serverSettings : null;
    }

    int getServerResetCount() {
        return resetByServer.size();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void writeFrame(final int type, final int flags, final int streamId, final byte[] payload) throws IOException {
        final byte[] header = new byte[9];
        header[0] = (byte) (payload.length >> 16);
        header[1] = (byte) (payload.length >> 8);
        header[2] = (byte) payload.length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) (streamId >> 24);
        header[6] = (byte) (streamId >> 16);
        header[7] = (byte) (streamId >> 8);
        header[8] = (byte) streamId;
        out.write(header);
        out.write(payload);
    }

    private static byte[] int32(final int value) {
        return new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value };
    }

    private void readLoop() {
        try {
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            while (true) {
                final int length = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                final int type = in.readUnsignedByte();
                final int flags = in.readUnsignedByte();
                final int streamId = in.readInt() & 0x7fffffff;
                final byte[] payload = new byte[length];
                in.readFully(payload);

                switch (type) {
                case TYPE_SETTINGS:
                    if ((flags & FLAG_ACK) == 0) {
                        if (serverSettingsReceived.getCount() > 0) {
                            for (int i = 0; i + 6 <= length; i += 6) {
                                serverSettings.put(((payload[i] & 0xff) << 8) | (payload[i + 1] & 0xff),
                                        ((payload[i + 2] & 0xffL) << 24) | ((payload[i + 3] & 0xffL) << 16) | ((payload[i + 4] & 0xffL) << 8)
                                                | (payload[i + 5] & 0xffL));
                            }
                            serverSettingsReceived.countDown();
                        }
                        synchronized (this) {
                            writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                            out.flush();
                        }
                    }
                    break;
                case TYPE_HEADERS:
                case TYPE_DATA:
                    respondedStreams.add(streamId);
                    if ((flags & FLAG_END_STREAM) != 0) {
                        completedStreams.add(streamId);
                    }
                    break;
                case TYPE_PUSH_PROMISE: {
                    final int offset = (flags & FLAG_PADDED) != 0 ? 1 : 0;
                    promisedStreams.add(((payload[offset] & 0x7f) << 24) | ((payload[offset + 1] & 0xff) << 16) | ((payload[offset + 2] & 0xff) << 8)
                            | (payload[offset + 3] & 0xff));
                    break;
                }
                case TYPE_RST_STREAM:
                    serverResetFrames.incrementAndGet();
                    resetByServer.put(streamId, ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16) | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff));
                    break;
                case TYPE_GOAWAY:
                    goAwayErrorCode.compareAndSet(-1,
                            ((payload[4] & 0xff) << 24) | ((payload[5] & 0xff) << 16) | ((payload[6] & 0xff) << 8) | (payload[7] & 0xff));
                    goAwayReceived.countDown();
                    break;
                default:
                    break;
                }
            }
        } catch (IOException e) {
            // the server closed the connection, or the test closed the socket
        } finally {
            connectionClosed.countDown();
        }
    }
}
