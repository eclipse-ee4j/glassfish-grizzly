/*
 * Copyright (c) 2025, 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates and others.
 * All rights reserved.
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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.IOEventLifeCycleListener;
import org.glassfish.grizzly.ProcessorExecutor;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.http2.frames.ContinuationFrame;
import org.glassfish.grizzly.http2.frames.DataFrame;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.http2.frames.GoAwayFrame;
import org.glassfish.grizzly.http2.frames.HeaderBlockFragment;
import org.glassfish.grizzly.http2.frames.HeadersFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;
import org.glassfish.grizzly.http2.frames.PingFrame;
import org.glassfish.grizzly.http2.frames.PriorityFrame;
import org.glassfish.grizzly.http2.frames.PushPromiseFrame;
import org.glassfish.grizzly.http2.frames.RstStreamFrame;
import org.glassfish.grizzly.http2.frames.SettingsFrame;
import org.glassfish.grizzly.http2.frames.SettingsFrame.SettingsFrameBuilder;
import org.glassfish.grizzly.http2.frames.UnknownFrame;
import org.glassfish.grizzly.http2.frames.WindowUpdateFrame;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.Holder;

import static org.glassfish.grizzly.http2.Http2BaseFilter.PRI_PAYLOAD;
import static org.glassfish.grizzly.http2.frames.SettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE;
import static org.glassfish.grizzly.http2.frames.SettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS;
import static org.glassfish.grizzly.http2.frames.SettingsFrame.SETTINGS_MAX_HEADER_LIST_SIZE;

/**
 * The HTTP2 session abstraction.
 *
 * @author Alexey Stashok
 */
public class Http2Session {
    private static final Logger LOGGER = Grizzly.logger(Http2Session.class);

    private final boolean isServer;
    private final Connection<?> connection;
    Http2State http2State;

    private HeadersDecoder headersDecoder;
    private HeadersEncoder headersEncoder;

    private final ReentrantLock deflaterLock = new ReentrantLock();

    int lastPeerStreamId;
    private int lastLocalStreamId;
    private boolean pushEnabled = true;

    private final ReentrantLock newClientStreamLock = new ReentrantLock();

    private volatile FilterChain http2StreamChain;
    private volatile FilterChain htt2SessionChain;

    private final AtomicInteger concurrentStreamsCount = new AtomicInteger(0);
    // streams which are still registered, but no longer count toward the local concurrent streams limit,
    // see onSendEndOfStream(Http2Stream); guarded by sessionLock
    private int closingStreamsCount;
    // streams initiated by the peer which count toward the local concurrent streams limit: registered, and neither
    // closing (see onSendEndOfStream(Http2Stream)) nor deregistered. Streams initiated by this side (e.g. pushed ones)
    // count toward the peer's limit, not the local one (RFC 9113, section 5.1.2); guarded by sessionLock
    private int activePeerStreamsCount;
    // requests passed to the processing chain whose processing hasn't finished yet, whether their stream is still open or
    // has been reset in the meantime, see startProcessing(Http2Stream, HttpContent); guarded by sessionLock
    private int requestsInProgress;
    // requests of open streams waiting for requestsInProgress to drop below the local concurrent streams limit, in the
    // order they arrived; guarded by sessionLock
    private final ArrayDeque<Http2Stream> deferredRequests = new ArrayDeque<>();

    private final TreeMap<Integer, Http2Stream> streamsMap = new TreeMap<>();

    // (Optimization) We may read several DataFrames belonging to the same
    // Http2Stream, so in order to not process every DataFrame separately -
    // we buffer them and only then passing for processing.
    final List<Http2Stream> streamsToFlushInput = new ArrayList<>();

    // The List object used to store header frames. Could be used by
    // Http2Session streams, when they write headers
    protected final List<Http2Frame> tmpHeaderFramesList = new ArrayList<>(2);

    private final Object sessionLock = new Object();

    private volatile CloseType closeFlag;

    private int peerStreamWindowSize = getDefaultStreamWindowSize();
    private volatile int localStreamWindowSize = getDefaultStreamWindowSize();

    private volatile int localConnectionWindowSize = getDefaultConnectionWindowSize();

    private volatile int maxHeaderListSize;

    private volatile int localMaxConcurrentStreams = getDefaultMaxConcurrentStreams();
    private int peerMaxConcurrentStreams = getDefaultMaxConcurrentStreams();

    private final Http2SessionOutputSink outputSink;

    private final Http2Configuration http2Configuration;

    // the stream resets this connection may still cause before it is told to calm down, see chargeReset(String);
    // only used by the connection's read thread
    private final Http2ResetBudget resetBudget;
    private boolean resetBudgetExceededReported;
    // the RST_STREAM frames this connection may still get, in answer to frames for streams which are already closed,
    // before such frames are ignored, see resetLocally(...); only used by the connection's read thread
    private final Http2ResetBudget closedStreamResetBudget;
    // the streams have been closed along with the connection, see ConnectionCloseListener; guarded by sessionLock
    private boolean streamsClosedWithConnection;

    private volatile int streamsHighWaterMark;
    private int checkCount;

    private int goingAwayLastStreamId = Integer.MIN_VALUE;
    private FutureImpl<Http2Session> sessionClosed;

    // true, if this connection is ready to accept frames or false if the first
    // HTTP/1.1 Upgrade is still in progress
    private volatile boolean isPrefaceReceived;
    private volatile boolean isPrefaceSent;

    public static Http2Session get(final Connection connection) {
        final Http2State http2State = Http2State.get(connection);
        return http2State != null ? http2State.getHttp2Session() : null;
    }

    static void bind(final Connection connection, final Http2Session http2Session) {
        Http2State.obtain(connection).setHttp2Session(http2Session);
    }

    private final Holder<?> addressHolder;

    final Http2BaseFilter handlerFilter;

    private final int localMaxFramePayloadSize;
    private int peerMaxFramePayloadSize = getSpecDefaultFramePayloadSize();

    private boolean isFirstInFrame = true;
    private volatile SSLBaseFilter sslFilter;

    private final AtomicInteger unackedReadBytes = new AtomicInteger();

    public Http2Session(final Connection<?> connection, final boolean isServer, final Http2BaseFilter handlerFilter) {
        this.connection = connection;
        final FilterChain chain = (FilterChain) connection.getProcessor();
        final int sslIdx = chain.indexOfType(SSLBaseFilter.class);
        if (sslIdx != -1) {
            sslFilter = (SSLBaseFilter) chain.get(sslIdx);
        }
        this.isServer = isServer;
        this.handlerFilter = handlerFilter;

        this.http2Configuration = handlerFilter.getConfiguration();
        this.resetBudget = new Http2ResetBudget(http2Configuration.getMaxResetsPerWindow(), http2Configuration.getResetWindowMillis());
        this.closedStreamResetBudget = new Http2ResetBudget(http2Configuration.getMaxResetsPerWindow(), http2Configuration.getResetWindowMillis());

        if (this.http2Configuration.getMaxConcurrentStreams() != -1) {
            this.setLocalMaxConcurrentStreams(this.http2Configuration.getMaxConcurrentStreams());
        } else {
            this.setLocalMaxConcurrentStreams(this.getDefaultMaxConcurrentStreams());
        }

        if (this.http2Configuration.getInitialWindowSize() != -1) {
            this.localStreamWindowSize = this.http2Configuration.getInitialWindowSize();
        }

        final int customMaxFramePayloadSz = handlerFilter.getLocalMaxFramePayloadSize() > 0 ? handlerFilter.getLocalMaxFramePayloadSize() : -1;

        // apply custom max frame value only if it's in [getSpecMinFramePayloadSize(); getSpecMaxFramePayloadSize()] range
        this.localMaxFramePayloadSize = customMaxFramePayloadSz >= getSpecMinFramePayloadSize() && customMaxFramePayloadSz <= getSpecMaxFramePayloadSize()
                ? customMaxFramePayloadSz
                : getSpecDefaultFramePayloadSize();

        this.maxHeaderListSize = handlerFilter.getConfiguration().getMaxHeaderListSize();

        if (isServer) {
            this.lastLocalStreamId = 0;
            this.lastPeerStreamId = -1;
        } else {
            this.lastLocalStreamId = -1;
            this.lastPeerStreamId = 0;
        }

        this.addressHolder = Holder.lazyHolder(() -> connection.getPeerAddress());

        connection.addCloseListener(new ConnectionCloseListener());

        this.outputSink = newOutputSink();

        NetLogger.logOpen(this);
    }

    protected Http2SessionOutputSink newOutputSink() {
        return new Http2SessionOutputSink(this);
    }

    protected int getSpecDefaultFramePayloadSize() {
        return 16384; // 2^14
    }

    protected int getSpecMinFramePayloadSize() {
        return 16384; // 2^14
    }

    protected int getSpecMaxFramePayloadSize() {
        return 0xffffff; // 2^24-1 (16,777,215)
    }

    public int getDefaultConnectionWindowSize() {
        return 65535;
    }

    public int getDefaultStreamWindowSize() {
        return 65535;
    }

    public int getDefaultMaxConcurrentStreams() {
        return 100;
    }

    /**
     * @return the maximum size, in bytes, of header list. If not explicitly configured, the default of <code>8192</code> is
     * used.
     */
    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Set the maximum size, in bytes, of the header list.
     *
     * @param maxHeaderListSize size, in bytes, of the header list.
     */
    @SuppressWarnings("unused")
    public void setMaxHeaderListSize(int maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * Returns the total frame size (including header size), or <tt>-1</tt> if the buffer doesn't contain enough bytes to
     * read the size.
     *
     * @param buffer the buffer containing the frame data
     *
     * @return the total frame size (including header size), or <tt>-1</tt> if the buffer doesn't contain enough bytes to
     * read the size
     */
    protected int getFrameSize(final Buffer buffer) {
        return buffer.remaining() < 4 // even though we need just 3 bytes - we require 4 for simplicity
                ? -1
                : (buffer.getInt(buffer.position()) >>> 8) + Http2Frame.FRAME_HEADER_SIZE;
    }

    public Http2Frame parseHttp2FrameHeader(final Buffer buffer) throws Http2SessionException {
        // we assume the passed buffer represents only this frame, no remainders allowed
        final int len = getFrameSize(buffer);
        if (buffer.remaining() != len) {
            throw new Http2SessionException(ErrorCode.FRAME_SIZE_ERROR);
        }

        final int i1 = buffer.getInt();

        final int type = i1 & 0xff;

        final int flags = buffer.get() & 0xff;
        final int streamId = buffer.getInt() & 0x7fffffff;

        switch (type) {
        case DataFrame.TYPE:
            return DataFrame.fromBuffer(flags, streamId, buffer);
        case HeadersFrame.TYPE:
            return HeadersFrame.fromBuffer(flags, streamId, buffer);
        case PriorityFrame.TYPE:
            return PriorityFrame.fromBuffer(streamId, buffer);
        case RstStreamFrame.TYPE:
            return RstStreamFrame.fromBuffer(flags, streamId, buffer);
        case SettingsFrame.TYPE:
            return SettingsFrame.fromBuffer(flags, streamId, buffer);
        case PushPromiseFrame.TYPE:
            return PushPromiseFrame.fromBuffer(flags, streamId, buffer);
        case PingFrame.TYPE:
            return PingFrame.fromBuffer(flags, streamId, buffer);
        case GoAwayFrame.TYPE:
            return GoAwayFrame.fromBuffer(streamId, buffer);
        case WindowUpdateFrame.TYPE:
            return WindowUpdateFrame.fromBuffer(flags, streamId, buffer);
        case ContinuationFrame.TYPE:
            return ContinuationFrame.fromBuffer(flags, streamId, buffer);
        default:
            return new UnknownFrame(type, len);
        }
    }

    protected Http2Stream newStream(final HttpRequestPacket request, final int streamId, final int refStreamId, final boolean exclusive, final int priority) {

        return new Http2Stream(this, request, streamId, refStreamId, exclusive, priority);
    }

    protected Http2Stream newUpgradeStream(final HttpRequestPacket request, final int priority) {

        return new Http2Stream(this, request, priority);
    }

    protected void checkFrameSequenceSemantics(final Http2Frame frame) throws Http2SessionException {

        final int frameType = frame.getType();

        if (isFirstInFrame) {
            if (frameType != SettingsFrame.TYPE) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "First in frame should be a SettingsFrame (preface)", frame);
                }

                throw new Http2SessionException(ErrorCode.PROTOCOL_ERROR);
            }

            isPrefaceReceived = true;
            handlerFilter.onPrefaceReceived(this);

            // Preface received - change the HTTP2 connection state
            Http2State.get(connection).setOpen();

            isFirstInFrame = false;
        }

        // 1) make sure the header frame sequence comes without interleaving
        if (isParsingHeaders()) {
            if (frameType != ContinuationFrame.TYPE) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "ContinuationFrame is expected, but {0} came", frame);
                }

                throw new Http2SessionException(ErrorCode.PROTOCOL_ERROR);
            }
        } else if (frameType == ContinuationFrame.TYPE) {
            // isParsing == false, so no ContinuationFrame expected
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "ContinuationFrame is not expected");
            }

            throw new Http2SessionException(ErrorCode.PROTOCOL_ERROR);
        }
    }

    protected void onOversizedFrame(final Buffer buffer) throws Http2SessionException {

        final int oldPos = buffer.position();
        try {
            throw new Http2SessionException(ErrorCode.FRAME_SIZE_ERROR);
        } finally {
            buffer.position(oldPos);
        }
    }

    boolean isParsingHeaders() {
        return headersDecoder != null && headersDecoder.isProcessingHeaders();
    }

    /**
     * @return The max <tt>payload</tt> size to be accepted by this side
     */
    public final int getLocalMaxFramePayloadSize() {
        return localMaxFramePayloadSize;
    }

    /**
     * @return The max <tt>payload</tt> size to be accepted by the peer
     */
    public int getPeerMaxFramePayloadSize() {
        return peerMaxFramePayloadSize;
    }

    /**
     * Sets the max <tt>payload</tt> size to be accepted by the peer. The method is called during the {@link SettingsFrame}
     * processing.
     *
     * @param peerMaxFramePayloadSize max payload size accepted by the peer.
     * @throws Http2SessionException if the peerMaxFramePayloadSize violates the limits
     */
    protected void setPeerMaxFramePayloadSize(final int peerMaxFramePayloadSize) throws Http2SessionException {
        if (peerMaxFramePayloadSize < getSpecMinFramePayloadSize() || peerMaxFramePayloadSize > getSpecMaxFramePayloadSize()) {
            throw new Http2SessionException(ErrorCode.FRAME_SIZE_ERROR);
        }
        this.peerMaxFramePayloadSize = peerMaxFramePayloadSize;
    }

    public int getLocalStreamWindowSize() {
        return localStreamWindowSize;
    }

    public void setLocalStreamWindowSize(int localStreamWindowSize) {
        this.localStreamWindowSize = localStreamWindowSize;
    }

    public int getPeerStreamWindowSize() {
        return peerStreamWindowSize;
    }

    void setPeerStreamWindowSize(final int peerStreamWindowSize) throws Http2StreamException {
        synchronized (sessionLock) {
            final int delta = peerStreamWindowSize - this.peerStreamWindowSize;

            this.peerStreamWindowSize = peerStreamWindowSize;

            if (!streamsMap.isEmpty()) {
                for (final Http2Stream stream : streamsMap.values()) {
                    if (stream.isClosed()) {
                        continue;
                    }
                    stream.getOutputSink().onPeerWindowUpdate(delta);
                }
            }

        }
    }

    public int getLocalConnectionWindowSize() {
        return localConnectionWindowSize;
    }

    public void setLocalConnectionWindowSize(final int localConnectionWindowSize) {
        this.localConnectionWindowSize = localConnectionWindowSize;
    }

    public int getAvailablePeerConnectionWindowSize() {
        return outputSink.getAvailablePeerConnectionWindowSize();
    }

    /**
     * @return the maximum number of concurrent streams allowed for this session by our side.
     */
    public int getLocalMaxConcurrentStreams() {
        return localMaxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for this session by our side.
     *
     * @param localMaxConcurrentStreams max number of streams locally allowed
     */
    public void setLocalMaxConcurrentStreams(int localMaxConcurrentStreams) {
        this.localMaxConcurrentStreams = localMaxConcurrentStreams;
        this.streamsHighWaterMark = Float.valueOf(this.localMaxConcurrentStreams * this.http2Configuration.getStreamsHighWaterMark()).intValue();
    }

    /**
     * @return the maximum number of concurrent streams allowed for this session by peer.
     */
    @SuppressWarnings("unused")
    public int getPeerMaxConcurrentStreams() {
        return peerMaxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for this session by peer.
     */
    void setPeerMaxConcurrentStreams(int peerMaxConcurrentStreams) {
        this.peerMaxConcurrentStreams = peerMaxConcurrentStreams;
    }

    /**
     * @return <code>true</code> if push is enabled for this {@link Http2Session}, otherwise returns <code>false</code>.
     * Push is enabled by default.
     */
    public boolean isPushEnabled() {
        return pushEnabled && http2Configuration.isPushEnabled();
    }

    /**
     * Configure whether or not push is enabled on this {@link Http2Session}.
     *
     * @param pushEnabled flag toggling push support.
     */
    public void setPushEnabled(final boolean pushEnabled) {
        if (isGoingAway()) {
            return;
        }
        this.pushEnabled = pushEnabled;
    }

    public int getNextLocalStreamId() {
        lastLocalStreamId += 2;
        return lastLocalStreamId;
    }

    public Connection getConnection() {
        return connection;
    }

    public MemoryManager getMemoryManager() {
        return connection.getMemoryManager();
    }

    public boolean isServer() {
        return isServer;
    }

    public boolean isLocallyInitiatedStream(final int streamId) {
        assert streamId > 0;

        return isServer() ^ streamId % 2 != 0;

//        Same as
//        return isServer() ?
//                (streamId % 2) == 0 :
//                (streamId % 2) == 1;
    }

    Http2State getHttp2State() {
        return http2State;
    }

    boolean isHttp2InputEnabled() {
        return isPrefaceReceived;
    }

    boolean isHttp2OutputEnabled() {
        return isPrefaceSent;
    }

    public Http2Stream getStream(final int streamId) {
        return streamsMap.get(streamId);
    }

    protected Http2SessionOutputSink getOutputSink() {
        return outputSink;
    }

    /**
     * TODO
     */
    FutureImpl<Http2Session> terminateGracefully() {
        if (!isServer) {
            throw new IllegalStateException("Illegal use of graceful termination on client.");
        }
        final GoAwayFrame frame = setGoAwayLocally(ErrorCode.NO_ERROR, "Shutting Down", true);
        if (frame != null) {
            sessionClosed = Futures.createSafeFuture();
            outputSink.writeDownStream(frame);
        }
        return sessionClosed;
    }

    /**
     * Terminate the HTTP2 session sending a GOAWAY frame using the specified error code and optional detail. Once the
     * GOAWAY frame is on the wire, the underlying TCP connection will be closed.
     * <p>
     * Filter subclasses may call this to end a misbehaving session with a specific error code, e.g.
     * {@link ErrorCode#ENHANCE_YOUR_CALM} when a peer floods the server with frames. The method returns without waiting
     * for the GOAWAY to be written and may be called from any thread. Only the first GOAWAY is sent: if the session is
     * already going away (other than gracefully), the call has no effect.
     *
     * @param errorCode an RFC 9113 error code.
     * @param detail optional details, sent as the GOAWAY frame's additional debug data; may be {@code null}.
     */
    public void terminate(final ErrorCode errorCode, final String detail) {
        sendGoAwayAndClose(setGoAwayLocally(errorCode, detail, false));
    }

    private void sendGoAwayAndClose(final Http2Frame frame) {
        if (frame == null) {
            return;
        }

        if (!connection.isOpen()) {
            // The peer is already gone, so there's nobody left to receive the
            // GOAWAY. Writing it would only fail with the IOException the
            // connection was closed with, which is not a problem worth reporting.
            LOGGER.log(Level.FINE, "Connection is already closed.  Skipping GOAWAY and terminating session.");
            // writeDownStream() recycles the frame as part of serialization, this branch bypasses it.
            frame.recycle();
            closeConnectionAndOutputSink();
            return;
        }

        outputSink.writeDownStream(frame, new EmptyCompletionHandler<WriteResult>() {

            @Override
            public void failed(final Throwable throwable) {
                // The connection may have been closed by the peer in between the
                // check above and the actual write, in which case the failure is
                // expected and the reported cause is the connection's close reason.
                if (!connection.isOpen()) {
                    LOGGER.log(Level.FINE, "Connection has been closed while writing GOAWAY.  Terminating session.");
                } else {
                    LOGGER.log(Level.WARNING, "Unable to write GOAWAY.  Terminating session.", throwable);
                }
                closeConnectionAndOutputSink();
            }

            @Override
            public void completed(final WriteResult result) {
                closeConnectionAndOutputSink();
            }

            @Override
            public void cancelled() {
                LOGGER.log(Level.FINE, "GOAWAY write cancelled.  Terminating session.");
                closeConnectionAndOutputSink();
            }
        }, null);
    }

    private void closeConnectionAndOutputSink() {
        connection.closeSilently();
        outputSink.close();
    }

    private GoAwayFrame setGoAwayLocally(final ErrorCode errorCode, final String detail, final boolean graceful) {
        synchronized (sessionLock) {

            // If sending a GOAWAY for the first time, goingAwayLastStreamId will be
            // Integer.MIN_VALUE. It won't be possible to send another GOAWAY frame
            // Unless a graceful session termination was attempted. In this case,
            // the value will be Integer.MAX_VALUE and it will be possible to send
            // another GO_AWAY if a change of state is necessary i.e., the graceful
            // termination timed out and the session is being forcefully terminated.
            if (goingAwayLastStreamId == Integer.MIN_VALUE || goingAwayLastStreamId == Integer.MAX_VALUE && !graceful) {
                closeFlag = CloseType.LOCALLY;
                goingAwayLastStreamId = graceful ? Integer.MAX_VALUE : lastPeerStreamId > 0 ? lastPeerStreamId : 0;
                if (goingAwayLastStreamId != Integer.MAX_VALUE) {
                    if (concurrentStreamsCount.get() > 0) {
                        pruneStreams();
                    }
                }
                return GoAwayFrame.builder().lastStreamId(goingAwayLastStreamId)
                        .additionalDebugData(detail != null ? Buffers.wrap(getMemoryManager(), detail) : null).errorCode(errorCode).build();

            }
            return null; // already in GOAWAY state.
        }
    }

    // Must be locked by sessionLock
    private void pruneStreams() {
        LOGGER.log(Level.FINE, "pruneStreams()");
        // close streams that rank above the last stream ID specified by the GOAWAY frame.
        // Allow other streams to continue processing. Once the concurrent stream count reaches zero,
        // the session will be closed.
        Map<Integer, Http2Stream> invalidStreams = streamsMap.subMap(goingAwayLastStreamId, false, Integer.MAX_VALUE, true);
        if (!invalidStreams.isEmpty()) {
            // needs to iterate over copied list to prevent
            // bug #1995 - ConcurrentModificationException
            List<Http2Stream> closedStreams = new ArrayList<>(invalidStreams.values());
            for (final Http2Stream stream : closedStreams) {
                stream.closedRemotely();
                deregisterStream(stream);
            }
        }
    }

    /**
     * Method is called, when GOAWAY is initiated by peer
     */
    void setGoAwayByPeer(final int lastStreamId) {
        synchronized (sessionLock) {
            pushEnabled = false;
            goingAwayLastStreamId = lastStreamId;
            closeFlag = CloseType.REMOTELY;
            pruneStreams();
            if (isServer || lastStreamId != Integer.MAX_VALUE) {
                sendGoAwayAndClose(GoAwayFrame.builder().lastStreamId(goingAwayLastStreamId)
                        .additionalDebugData(Buffers.wrap(getMemoryManager(), "Peer Requested.")).errorCode(ErrorCode.NO_ERROR).build());
            }
        }
    }

    boolean isGoingAway() {
        return closeFlag != null;
    }

    public int getGoingAwayLastStreamId() {
        return goingAwayLastStreamId;
    }

    protected void sendWindowUpdate(final int streamId, final int delta) {
        final WindowUpdateFrame f = WindowUpdateFrame.builder().streamId(streamId).windowSizeIncrement(delta).build();
        NetLogger.log(NetLogger.Context.TX, this, f);
        outputSink.writeDownStream(f);
    }

    void sendPreface() {
        if (!isPrefaceSent) {
            synchronized (sessionLock) {
                if (!isPrefaceSent) {
                    if (isServer) {
                        sendServerPreface();
                    } else {
                        sendClientPreface();
                    }

                    isPrefaceSent = true;

                    if (!isServer) {
                        // If it's HTTP2 client, which uses HTTP/1.1 upgrade mechanism -
                        // it can have unacked user data sent from the server.
                        // So it's right time to ack this data and let the server send
                        // more data if needed.
                        ackConsumedData(getStream(0), 0);
                    }
                }
            }
        }
    }

    protected void sendServerPreface() {
        final SettingsFrame settingsFrame = prepareSettings().build();

        NetLogger.log(NetLogger.Context.TX, this, settingsFrame);

        // server preface
        // noinspection unchecked
        connection.write(settingsFrame.toBuffer(getMemoryManager()), sslFilter != null ? new EmptyCompletionHandler() {
            @Override
            public void completed(Object result) {
                sslFilter.setRenegotiationDisabled(true);
            }
        } : null);
    }

    protected void sendClientPreface() {
        // send preface (PRI * ....)
        final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.PRI).uri("*").protocol(Protocol.HTTP_2_0).build();

        final Buffer priPayload = Buffers.wrap(connection.getMemoryManager(), PRI_PAYLOAD);

        final SettingsFrame settingsFrame = prepareSettings().build();
        final Buffer settingsBuffer = settingsFrame.toBuffer(getMemoryManager());

        final Buffer payload = Buffers.appendBuffers(connection.getMemoryManager(), priPayload, settingsBuffer);

        final HttpContent content = HttpContent.builder(request).content(payload).build();

        NetLogger.log(NetLogger.Context.TX, this, settingsFrame);

        connection.write(content);
    }

    HeadersDecoder getHeadersDecoder() {
        if (headersDecoder == null) {
            headersDecoder = new HeadersDecoder(getMemoryManager(), getMaxHeaderListSize(), 4096);
        }

        return headersDecoder;
    }

    ReentrantLock getDeflaterLock() {
        return deflaterLock;
    }

    HeadersEncoder getHeadersEncoder() {
        if (headersEncoder == null) {
            headersEncoder = new HeadersEncoder(getMemoryManager(), 4096);
        }

        return headersEncoder;
    }

    /**
     * Encodes the {@link HttpHeader} and locks the compression lock.
     *
     * @param ctx the current {@link FilterChainContext}
     * @param httpHeader the {@link HttpHeader} to encode
     * @param streamId the stream associated with this request
     * @param isLast is this the last frame?
     * @param toList the target {@link List}, to which the frames will be serialized
     *
     * @return the HTTP2 header frames sequence
     * @throws IOException if an error occurs encoding the header
     */
    @SuppressWarnings("SameParameterValue")
    protected List<Http2Frame> encodeHttpHeaderAsHeaderFrames(final FilterChainContext ctx, final HttpHeader httpHeader, final int streamId,
            final boolean isLast, final List<Http2Frame> toList, final Map<String, String> capture) throws IOException {

        final Buffer compressedHeaders = !httpHeader.isRequest() ? EncoderUtils.encodeResponseHeaders(this, (HttpResponsePacket) httpHeader, capture)
                : EncoderUtils.encodeRequestHeaders(this, (HttpRequestPacket) httpHeader, capture);

        final List<Http2Frame> headerFrames = bufferToHeaderFrames(streamId, compressedHeaders, isLast, toList);

        handlerFilter.onHttpHeadersEncoded(httpHeader, ctx);

        return headerFrames;
    }

    /**
     * Encodes an informational (1xx) interim response, such as <code>103 Early Hints</code>, as HTTP/2 HEADERS frames.
     * The frames never carry END_STREAM, so the final response can still be sent on the same stream afterwards.
     *
     * @param ctx the current {@link FilterChainContext}
     * @param response the {@link HttpResponsePacket} whose interim status and headers are to be encoded
     * @param streamId the stream associated with this response
     * @param toList the target {@link List}, to which the frames will be serialized
     * @param capture optional map collecting the encoded headers for logging
     *
     * @return the HTTP2 header frames sequence
     *
     * @throws IOException if an error occurs encoding the headers
     */
    protected List<Http2Frame> encodeInterimResponseAsHeaderFrames(final FilterChainContext ctx, final HttpResponsePacket response,
            final int streamId, final List<Http2Frame> toList, final Map<String, String> capture) throws IOException {
        final Buffer compressedHeaders = EncoderUtils.encodeInterimResponseHeaders(this, response, capture);
        final List<Http2Frame> headerFrames = bufferToHeaderFrames(streamId, compressedHeaders, false, toList);
        handlerFilter.onHttpHeadersEncoded(response, ctx);
        return headerFrames;
    }

    /**
     * Encodes the {@link Map} of header values into header frames to be sent as trailer headers.
     *
     * @param streamId the stream associated with this request
     * @param toList the target {@link List}, to which the frames will be serialized.
     * @param trailerHeaders a {@link MimeHeaders} of headers to be transmitted as trailers.
     *
     * @return the HTTP2 header frames sequence
     *
     * @throws IOException if an error occurs encoding the header
     */
    protected List<Http2Frame> encodeTrailersAsHeaderFrames(final int streamId, final List<Http2Frame> toList, final MimeHeaders trailerHeaders,
            final Map<String, String> capture) throws IOException {
        final Buffer compressedHeaders = EncoderUtils.encodeTrailerHeaders(this, trailerHeaders, capture);
        return bufferToHeaderFrames(streamId, compressedHeaders, true, toList);
    }

    /**
     * Encodes the {@link HttpRequestPacket} as a {@link PushPromiseFrame} and locks the compression lock.
     *
     * @param ctx the current {@link FilterChainContext}
     * @param httpRequest the {@link HttpRequestPacket} to encode.
     * @param streamId the stream associated with this request.
     * @param promisedStreamId the push promise stream ID.
     * @param toList the target {@link List}, to which the frames will be serialized
     * @return the HTTP2 push promise frames sequence
     *
     * @throws IOException if an error occurs encoding the request
     */
    @SuppressWarnings("SameParameterValue")
    protected List<Http2Frame> encodeHttpRequestAsPushPromiseFrames(final FilterChainContext ctx, final HttpRequestPacket httpRequest, final int streamId,
            final int promisedStreamId, final List<Http2Frame> toList, final Map<String, String> capture) throws IOException {

        final List<Http2Frame> headerFrames = bufferToPushPromiseFrames(streamId, promisedStreamId,
                EncoderUtils.encodeRequestHeaders(this, httpRequest, capture), toList);

        handlerFilter.onHttpHeadersEncoded(httpRequest, ctx);

        return headerFrames;
    }

    /**
     * Encodes a compressed header buffer as a {@link HeadersFrame} and a sequence of 0 or more {@link ContinuationFrame}s.
     *
     * @param streamId the stream associated with the headers.
     * @param compressedHeaders a {@link Buffer} containing compressed headers
     * @param isEos will any additional data be sent after these headers?
     * @param toList the {@link List} to which {@link Http2Frame}s will be added
     * @return the result {@link List} with the frames
     */
    private List<Http2Frame> bufferToHeaderFrames(final int streamId, final Buffer compressedHeaders, final boolean isEos, final List<Http2Frame> toList) {
        final HeadersFrame.HeadersFrameBuilder builder = HeadersFrame.builder().streamId(streamId).endStream(isEos);

        return completeHeadersProviderFrameSerialization(builder, streamId, compressedHeaders, toList);
    }

    /**
     * Encodes a compressed header buffer as a {@link PushPromiseFrame} and a sequence of 0 or more
     * {@link ContinuationFrame}s.
     *
     * @param streamId the stream associated with these headers
     * @param promisedStreamId the stream of the push promise
     * @param compressedHeaders the compressed headers to be sent
     * @param toList the {@link List} to which {@link Http2Frame}s will be added
     * @return the result {@link List} with the frames
     */
    private List<Http2Frame> bufferToPushPromiseFrames(final int streamId, final int promisedStreamId, final Buffer compressedHeaders,
            final List<Http2Frame> toList) {

        final PushPromiseFrame.PushPromiseFrameBuilder builder = PushPromiseFrame.builder().streamId(streamId).promisedStreamId(promisedStreamId);

        return completeHeadersProviderFrameSerialization(builder, streamId, compressedHeaders, toList);
    }

    /**
     * Completes the {@link HeaderBlockFragment} sequence serialization.
     *
     * @param streamId the stream associated with this {@link HeaderBlockFragment}
     * @param compressedHeaders the {@link Buffer} containing the compressed headers
     * @param toList the {@link List} to which {@link Http2Frame}s will be added
     * @return the result {@link List} with the frames
     */
    private List<Http2Frame> completeHeadersProviderFrameSerialization(final HeaderBlockFragment.HeaderBlockFragmentBuilder builder, final int streamId,
            final Buffer compressedHeaders, List<Http2Frame> toList) {
        // we assume deflaterLock is acquired and held by this thread
        assert getDeflaterLock().isHeldByCurrentThread();

        if (toList == null) {
            toList = tmpHeaderFramesList;
        }

        if (compressedHeaders.remaining() <= peerMaxFramePayloadSize) {
            toList.add(builder.endHeaders(true).compressedHeaders(compressedHeaders).build());

            return toList;
        }

        Buffer remainder = compressedHeaders.split(compressedHeaders.position() + peerMaxFramePayloadSize);

        toList.add(builder.endHeaders(false).compressedHeaders(compressedHeaders).build());

        assert remainder != null;

        do {
            final Buffer buffer = remainder;

            remainder = buffer.remaining() <= peerMaxFramePayloadSize ? null : buffer.split(buffer.position() + peerMaxFramePayloadSize);

            toList.add(ContinuationFrame.builder().streamId(streamId).endHeaders(remainder == null).compressedHeaders(buffer).build());
        } while (remainder != null);

        return toList;
    }

    /**
     * The {@link ReentrantLock}, which assures that requests assigned to newly allocated stream IDs will be sent to the
     * server in their order. So that request associated with the stream ID '5' won't be sent before the request associated
     * with the stream ID '3' etc.
     *
     * @return the {@link ReentrantLock}
     */
    public ReentrantLock getNewClientStreamLock() {
        return newClientStreamLock;
    }

    @SuppressWarnings("SameParameterValue")
    Http2Stream acceptStream(final HttpRequestPacket request, final int streamId, final int parentStreamId, final boolean exclusive, final int priority)
            throws Http2SessionException, Http2StreamException {

        final Http2Stream stream = newStream(request, streamId, parentStreamId, exclusive, priority);

        synchronized (sessionLock) {
            if (isClosed()) {
                return null; // if the session is closed is set - return null to ignore stream creation
            }

            if (isServer()) {
                if (streamId > 0 && (streamId & 1) == 0) {
                    throw new Http2SessionException(ErrorCode.PROTOCOL_ERROR);
                }
            } else {
                if (streamId > 0 && (streamId & 1) != 0) {
                    throw new Http2SessionException(ErrorCode.PROTOCOL_ERROR);
                }
            }

            if (streamId < lastPeerStreamId) {
                throw new Http2SessionException(ErrorCode.PROTOCOL_ERROR);
            }

            if (activePeerStreamsCount >= getLocalMaxConcurrentStreams()) {
                // The peer has opened more streams than it was allowed to: reset streams aren't counted here, the peer
                // considers them closed (the requests still being processed for them are bounded by startProcessing).
                // Nor are the streams this side has initiated (pushed ones): they count toward the peer's limit, so a
                // peer within the advertised limit is never refused, and charged for it, because of them.
                // RFC 9113, section 5.1.2: a stream over the advertised limit is a stream error, not a connection error.
                // The caller still has to decode the stream's header block to keep the HPACK context in sync.
                // The stream ID is used up, so frames still arriving for it are handled as for a closed stream.
                lastPeerStreamId = streamId;
                throw new Http2StreamException(streamId, ErrorCode.REFUSED_STREAM, "Maximum number of concurrent streams exceeded");
            }

            registerStream(streamId, stream);
            lastPeerStreamId = streamId;
        }

        return stream;
    }

    /**
     * Method is not thread-safe, it is expected that it will be called within {@link #getNewClientStreamLock()} lock scope.
     * The caller code is responsible for obtaining and releasing the mentioned {@link #getNewClientStreamLock()} lock.
     *
     * @param request the request that initiated the stream
     * @param streamId the ID of this new stream
     * @param parentStreamId the parent stream
     * @param priority the priority of this stream
     *
     * @return a new {@link Http2Stream} for this request
     *
     * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
     */
    public Http2Stream openStream(final HttpRequestPacket request, final int streamId, final int parentStreamId, final boolean exclusive, final int priority)
            throws Http2StreamException {

        final Http2Stream stream = newStream(request, streamId, parentStreamId, exclusive, priority);

        synchronized (sessionLock) {
            if (isClosed()) {
                throw new Http2StreamException(streamId, ErrorCode.REFUSED_STREAM, "Session is closed");
            }

            if (concurrentStreamsCount.get() - closingStreamsCount >= getLocalMaxConcurrentStreams()) {
                throw new Http2StreamException(streamId, ErrorCode.REFUSED_STREAM);
            }

            if (parentStreamId > 0) {
                final Http2Stream mainStream = getStream(parentStreamId);
                if (mainStream == null) {
                    throw new Http2StreamException(streamId, ErrorCode.REFUSED_STREAM, "The parent stream does not exist");
                }
            }

            registerStream(streamId, stream);
            lastLocalStreamId = streamId;
        }

        return stream;
    }

    /**
     * The method is called to create an {@link Http2Stream} initiated via HTTP/1.1 Upgrade mechanism.
     *
     * @param request the request that initiated the upgrade
     * @param priority the stream priority
     * @param fin is more content expected?
     *
     * @return a new {@link Http2Stream} for this request
     *
     * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
     */
    public Http2Stream acceptUpgradeStream(final HttpRequestPacket request, final int priority, final boolean fin) throws Http2StreamException {

        request.setExpectContent(!fin);
        final Http2Stream stream = newUpgradeStream(request, priority);

        registerUpgradeStream(stream);

        return stream;
    }

    /**
     * The method is called on the client side, when the server confirms HTTP/1.1 -> HTTP/2.0 upgrade with '101' response.
     *
     * @param request the request that initiated the upgrade
     * @param priority the priority of the stream
     *
     * @return a new {@link Http2Stream} for this request
     *
     * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
     */
    public Http2Stream openUpgradeStream(final HttpRequestPacket request, final int priority) throws Http2StreamException {

        // we already sent headers - so the initial state is OPEN
        final Http2Stream stream = newUpgradeStream(request, priority);

        registerUpgradeStream(stream);

        return stream;
    }

    /**
     * Initializes HTTP2 communication (if not initialized before) by forming HTTP2 connection and stream
     * {@link FilterChain}s.
     *
     * @param context the current {@link FilterChainContext}
     * @param isUpStream flag denoting the direction of the chain
     */
    void setupFilterChains(final FilterChainContext context, final boolean isUpStream) {

        if (htt2SessionChain == null) {
            synchronized (this) {
                if (htt2SessionChain == null) {
                    if (isUpStream) {
                        http2StreamChain = (FilterChain) context.getFilterChain().subList(context.getFilterIdx(), context.getEndIdx());

                        htt2SessionChain = (FilterChain) context.getFilterChain().subList(context.getStartIdx(), context.getFilterIdx());
                    } else {
                        http2StreamChain = (FilterChain) context.getFilterChain().subList(context.getFilterIdx(), context.getFilterChain().size());

                        htt2SessionChain = (FilterChain) context.getFilterChain().subList(context.getEndIdx() + 1, context.getFilterIdx());
                    }
                }
            }
        }
    }

    FilterChain getHttp2SessionChain() {
        return htt2SessionChain;
    }

    /**
     * Called right before a frame with the END_STREAM flag is written for the stream. If the stream input is already
     * closed, the peer considers the stream closed as soon as this frame arrives and may open another stream in its
     * place right away. The stream is deregistered only after the write, so it stops counting toward the concurrent
     * streams limit now, otherwise such a new stream could be refused although the peer respects the limit.
     */
    void onSendEndOfStream(final Http2Stream stream) {
        if (!stream.inputBuffer.isClosed()) {
            return;
        }
        synchronized (sessionLock) {
            if (!stream.isClosing && !stream.isDeregistered) {
                stream.isClosing = true;
                closingStreamsCount++;
                if (!stream.isLocallyInitiatedStream()) {
                    activePeerStreamsCount--;
                }
            }
        }
    }

    /**
     * The one place where the server resets a stream: sends RST_STREAM, closes and deregisters the stream if there is
     * one, and charges the reset to the connection's budget (see {@link #chargeReset(String)}). The peer considers the
     * stream closed as soon as it gets the frame, so keeping it registered on this side would only leak its slot.
     * <p>
     * Only the resets the peer has caused to the server's cost are charged: closing a stream which was still open, and
     * refusing a stream the peer wasn't allowed to open ({@link ErrorCode#REFUSED_STREAM}). A frame arriving for a
     * stream which is already closed, e.g. DATA the peer had sent before it learned that the stream has been refused or
     * answered early, costs nothing but the RST_STREAM, and must not get a well-behaved client disconnected. Answering
     * each one of them would let the peer make the server write without limit though, so they get RST_STREAM out of a
     * budget of their own, of the same size as the reset budget. Past it, such frames are ignored without an answer,
     * which RFC 9113 (sections 5.1 and 5.4.2) allows for frames on closed streams, and the connection stays up.
     * <p>
     * Meant to be called from the connection's read thread only.
     *
     * @param streamId the stream to reset, may be one which doesn't exist (any more) on this side, e.g. a refused one
     * @param errorCode the error code to send
     * @param cause what made the server reset the stream, for the log
     * @param streamClosedByError {@code true} if the error has already closed the stream, which was open until then
     */
    void resetLocally(final int streamId, final ErrorCode errorCode, final String cause, final boolean streamClosedByError) {
        // Close the stream first, which also drops the frames still queued for it: none of them is to follow the RST_STREAM.
        final Http2Stream stream = getStream(streamId);
        final boolean closedOpenStream = stream != null && stream.resetLocally() || streamClosedByError;
        final boolean charged = closedOpenStream || errorCode == ErrorCode.REFUSED_STREAM;

        if (!charged && isServer && !closedStreamResetBudget.tryConsume(System.nanoTime())) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Ignoring a frame for the closed stream {0} of {1} without RST_STREAM ({2}): too many of them",
                        new Object[] { streamId, connection, cause });
            }
            return;
        }

        final RstStreamFrame frame = RstStreamFrame.builder().errorCode(errorCode).streamId(streamId).build();
        NetLogger.log(NetLogger.Context.TX, this, frame);
        outputSink.writeDownStream(frame);

        if (charged) {
            chargeReset("RST_STREAM sent on stream " + streamId + " with " + errorCode + ": " + cause);
        }
    }

    /**
     * Charges a stream reset to the connection's reset budget, see {@link Http2Configuration#setMaxResetsPerWindow(int)}.
     * Bounding the requests in progress (see {@link #startProcessing(Http2Stream, HttpContent)}) bounds the work a
     * connection causes at any moment, but not the work the server does to accept and drop streams, so the number of
     * resets has to be bounded too. A connection which exceeds the budget is sent a GOAWAY with
     * {@link ErrorCode#ENHANCE_YOUR_CALM} and closed. None of its requests, which haven't been passed to the handler
     * yet, is going to be: the ones waiting for their turn are dropped, and all the others are marked cancelled.
     * <p>
     * Meant to be called from the connection's read thread only. It does nothing for client sessions.
     *
     * @param cause what the reset was, for the log
     * @return {@code true} if the connection has exceeded its budget
     */
    boolean chargeReset(final String cause) {
        if (!isServer || resetBudget.tryConsume(System.nanoTime())) {
            return false;
        }

        if (!resetBudgetExceededReported) {
            resetBudgetExceededReported = true;
            NetLogger.logResetBudgetExceeded(this, cause);
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "Closing the HTTP/2 connection with {0}: too many stream resets (more than {1} per {2} ms), last one: {3}",
                        new Object[] { connection.getPeerAddress(), http2Configuration.getMaxResetsPerWindow(), http2Configuration.getResetWindowMillis(),
                                cause });
            }
        }
        // Before the GOAWAY: the connection is closed only once the GOAWAY has been written, which takes as long as the
        // peer wants it to, and requests finishing in the meantime would pass the waiting ones to the handler.
        abandonRequests();
        terminate(ErrorCode.ENHANCE_YOUR_CALM, "Excessive stream resets");
        return true;
    }

    /**
     * Makes sure that no request of this connection, which hasn't been passed to the handler yet, is going to be: drops
     * the requests waiting for their turn, and marks the requests of all the streams cancelled. The requests being
     * processed already are left to finish.
     */
    private void abandonRequests() {
        final List<Http2Stream> streams;
        synchronized (sessionLock) {
            for (final Http2Stream stream : deferredRequests) {
                stream.deferredContent = null;
            }
            deferredRequests.clear();
            streams = new ArrayList<>(streamsMap.values());
        }
        for (final Http2Stream stream : streams) {
            stream.markCancelled();
        }
    }

    /**
     * Called before a request is passed to the processing chain (server side). A connection may have no more than
     * {@link #getLocalMaxConcurrentStreams()} requests in progress: a request counts from the moment it is passed to the
     * chain until its processing is finished (see {@link #finishProcessing(Http2Stream)}), even if its stream is reset in
     * the meantime. Otherwise a peer could open a stream and reset it right away, over and over (HTTP/2 Rapid Reset,
     * CVE-2023-44487), and make the server process an unlimited number of requests while it never has more than a few
     * streams open.
     * <p>
     * A request over the limit isn't refused but waits until another one finishes: the peer considers a reset stream
     * closed, so a well-behaved client (e.g. a browser cancelling requests when the user navigates away) may open new
     * streams in its place right away, and is not to be punished for it. The number of waiting requests is bounded by
     * the limit as well, they are requests of open streams. A waiting request is dropped if its stream is reset (see
     * {@link #onStreamClosedAbruptly(Http2Stream)}).
     * <p>
     * Pushed requests count too: the peer may decline a pushed stream for free, which frees its slot right away, so they
     * would not be bounded otherwise.
     *
     * @return {@code true} if the request may be passed to the processing chain now, or {@code false} if it has been
     * put aside, to be passed by {@link Http2BaseFilter#dispatchDeferred} later
     */
    boolean startProcessing(final Http2Stream stream, final HttpContent content) {
        if (!isServer || !http2Configuration.isHoldSlotUntilProcessingComplete()) {
            return true;
        }
        synchronized (sessionLock) {
            if (requestsInProgress < getLocalMaxConcurrentStreams() && deferredRequests.isEmpty()) {
                stream.isInProgress = true;
                requestsInProgress++;
                return true;
            }

            stream.deferredContent = content;
            deferredRequests.add(stream);
            return false;
        }
    }

    /**
     * Called once the processing of the request of the stream has finished (the response has been completed, or the
     * request has been recycled), see {@link #startProcessing(Http2Stream, HttpContent)}. It may be called any number of
     * times and from any thread, the request stops counting once. Passes the requests, which have been waiting for it, to
     * the processing chain.
     */
    void finishProcessing(final Http2Stream stream) {
        List<Http2Stream> toDispatch = null;
        List<HttpContent> contents = null;
        synchronized (sessionLock) {
            if (!stream.isInProgress) {
                return;
            }
            stream.isInProgress = false;
            requestsInProgress--;

            while (requestsInProgress < getLocalMaxConcurrentStreams() && !deferredRequests.isEmpty()) {
                final Http2Stream next = deferredRequests.poll();
                next.isInProgress = true;
                requestsInProgress++;
                if (toDispatch == null) {
                    toDispatch = new ArrayList<>(2);
                    contents = new ArrayList<>(2);
                }
                toDispatch.add(next);
                contents.add(next.deferredContent);
                next.deferredContent = null;
            }
        }

        if (toDispatch != null) {
            for (int i = 0; i < toDispatch.size(); i++) {
                handlerFilter.dispatchDeferred(this, toDispatch.get(i), contents.get(i));
            }
        }
    }

    /**
     * Called when a stream is reset, or closed along with the connection. If its request is waiting to be processed, it
     * is dropped: nobody is going to read the response. A request which is already being processed keeps counting until
     * its processing finishes.
     */
    void onStreamClosedAbruptly(final Http2Stream stream) {
        synchronized (sessionLock) {
            if (deferredRequests.remove(stream)) {
                stream.deferredContent = null;
            }
        }
    }

    /**
     * Called from {@link Http2Stream} once stream is completely closed.
     */
    void deregisterStream(final Http2Stream stream) {
        LOGGER.fine("deregisterStream()");
        final boolean isCloseSession;
        synchronized (sessionLock) {
            decStreamCount();
            if (stream.isClosing) {
                stream.isClosing = false;
                closingStreamsCount--;
            } else if (!stream.isDeregistered && !stream.isLocallyInitiatedStream()) {
                activePeerStreamsCount--;
            }
            stream.isDeregistered = true;
            // If we're in GOAWAY state and there are no streams left - close this session
            isCloseSession = isGoingAway() && concurrentStreamsCount.get() <= 0;
            if (!isCloseSession) {
                if (checkCount++ > http2Configuration.getCleanFrequencyCheck() && streamsMap.size() > streamsHighWaterMark) {
                    checkCount = 0;
                    for (final Iterator<Map.Entry<Integer, Http2Stream>> streamIds = streamsMap.entrySet().iterator(); streamIds.hasNext(); ) {
                        final Map.Entry<Integer, Http2Stream> entry = streamIds.next();
                        if (entry.getValue().isClosed()) {
                            streamIds.remove();
                        }
                    }
                }
            }
        }

        if (isCloseSession) {
            if (sessionClosed != null) {
                sessionClosed.result(this);
            } else {
                terminate(ErrorCode.NO_ERROR, "Session closed");
            }
        }
    }

    private boolean isClosed() {
        return closeFlag != null;
    }

    void sendMessageUpstreamWithParseNotify(final Http2Stream stream, final HttpContent httpContent) {
        final FilterChainContext upstreamContext = http2StreamChain.obtainFilterChainContext(connection, stream);

        final HttpContext httpContext = httpContent.getHttpHeader().getProcessingState().getHttpContext();
        httpContext.attach(upstreamContext);

        handlerFilter.onHttpContentParsed(httpContent, upstreamContext);
        final HttpHeader header = httpContent.getHttpHeader();
        if (httpContent.isLast()) {
            handlerFilter.onHttpPacketParsed(header, upstreamContext);
        }

        if (header.isSkipRemainder()) {
            return;
        }

        sendMessageUpstream(stream, httpContent, upstreamContext);
    }

    void sendMessageUpstream(final Http2Stream stream, final HttpPacket message) {
        final FilterChainContext upstreamContext = http2StreamChain.obtainFilterChainContext(connection, stream);

        final HttpContext httpContext = message.getHttpHeader().getProcessingState().getHttpContext();
        httpContext.attach(upstreamContext);

        sendMessageUpstream(stream, message, upstreamContext);
    }

    /**
     * Passes the request of a stream, which has been counted by {@link #startProcessing(Http2Stream, HttpContent)}, to
     * the processing chain (server side).
     * <p>
     * The processing of a request normally finishes with the response being completed, or the request being recycled.
     * A suspended request whose stream has been reset gets neither: it is cancelled (see
     * {@link Http2Stream#resetRemotely()}), and its processing ends without a response, the request being left alone
     * as it may still be referenced. The processing chain is done with it then though, so it stops counting toward the
     * requests in progress once the chain is, provided that its stream has been cancelled. A request which is not
     * cancelled keeps counting until it finishes as usual, so a filter, which stops the chain and goes on processing the
     * request on another thread, doesn't release it early.
     */
    void sendRequestUpstream(final Http2Stream stream, final HttpContent content) {
        final FilterChainContext upstreamContext = http2StreamChain.obtainFilterChainContext(connection, stream);

        final HttpContext httpContext = content.getHttpHeader().getProcessingState().getHttpContext();
        httpContext.attach(upstreamContext);

        upstreamContext.addCompletionListener(new FilterChainContext.CompletionListener() {
            @Override
            public void onComplete(final FilterChainContext context) {
                if (stream.isCancelled()) {
                    finishProcessing(stream);
                }
            }
        });

        sendMessageUpstream(stream, content, upstreamContext);
    }

    private void sendMessageUpstream(final Http2Stream stream, final HttpPacket message, final FilterChainContext upstreamContext) {

        upstreamContext.getInternalContext().setIoEvent(IOEvent.READ);
        upstreamContext.getInternalContext().addLifeCycleListener(new IOEventLifeCycleListener.Adapter() {
            @Override
            public void onReregister(final Context context) throws IOException {
                stream.inputBuffer.onReadEventComplete();
            }

            @Override
            public void onComplete(Context context, Object data) throws IOException {
                stream.inputBuffer.onReadEventComplete();
            }
        });

        upstreamContext.setMessage(message);
        upstreamContext.setAddressHolder(addressHolder);

        ProcessorExecutor.execute(upstreamContext.getInternalContext());
    }

    protected SettingsFrameBuilder prepareSettings() {

        final SettingsFrameBuilder builder = SettingsFrame.builder();

        // A server always advertises its limit, even the default one: without the setting the peer may assume there is
        // no limit (RFC 9113, section 6.5.2), while streams over it are refused and charged to the reset budget.
        if (isServer || getLocalMaxConcurrentStreams() != getDefaultMaxConcurrentStreams()) {
            builder.setting(SETTINGS_MAX_CONCURRENT_STREAMS, getLocalMaxConcurrentStreams());
        }

        if (getLocalStreamWindowSize() != getDefaultStreamWindowSize()) {
            builder.setting(SETTINGS_INITIAL_WINDOW_SIZE, getLocalStreamWindowSize());
        }

        builder.setting(SETTINGS_MAX_HEADER_LIST_SIZE, getMaxHeaderListSize());

        return builder;
    }

    /**
     * Acknowledge that certain amount of data has been read. Depending on the total amount of un-acknowledge data the HTTP2
     * connection can decide to send a window_update message to the peer.
     *
     * @param sz size, in bytes, of the data being acknowledged
     */
    void ackConsumedData(final int sz) {
        ackConsumedData(null, sz);
    }

    /**
     * Acknowledge that certain amount of data has been read. Depending on the total amount of un-acknowledge data the HTTP2
     * connection can decide to send a window_update message to the peer. Unlike the {@link #ackConsumedData(int)}, this
     * method also requests an HTTP2 stream to acknowledge consumed data to the peer.
     *
     * @param stream the stream that data is being ack'd on.
     * @param sz size, in bytes, of the data being acknowledged
     */
    void ackConsumedData(final Http2Stream stream, final int sz) {
        final int currentUnackedBytes = unackedReadBytes.addAndGet(sz);

        if (isPrefaceSent) {
            // ACK HTTP2 connection flow control
            final int windowSize = getLocalConnectionWindowSize();

            // if not forced - send update window message only in case currentUnackedBytes > windowSize / 2
            if (currentUnackedBytes > windowSize / 3 && unackedReadBytes.compareAndSet(currentUnackedBytes, 0)) {

                sendWindowUpdate(0, currentUnackedBytes);
            }

            if (stream != null) {
                // ACK HTTP2 stream flow control
                final int streamUnackedBytes = Http2Stream.unackedReadBytesUpdater.addAndGet(stream, sz);
                final int streamWindowSize = stream.getLocalWindowSize();

                // send update window message only in case currentUnackedBytes > windowSize / 2
                if (streamUnackedBytes > 0 && streamUnackedBytes > streamWindowSize / 2
                        && Http2Stream.unackedReadBytesUpdater.compareAndSet(stream, streamUnackedBytes, 0)) {

                    sendWindowUpdate(stream.getId(), streamUnackedBytes);
                }
            }
        }
    }

    /*
     * This method is not thread safe and should be guarded by the session lock.
     */
    void registerStream(final int streamId, final Http2Stream stream) {
        if (streamId < 1) {
            throw new IllegalArgumentException("Invalid stream ID");
        }
        if (stream == null) {
            throw new NullPointerException("Attempt to register null stream");
        }

        streamsMap.put(streamId, stream);
        incStreamCount();
        if (!isLocallyInitiatedStream(streamId)) {
            activePeerStreamsCount++;
        }
    }

    private void registerUpgradeStream(final Http2Stream stream) throws Http2StreamException {
        synchronized (sessionLock) {
            if (isClosed()) {
                throw new Http2StreamException(Http2Stream.UPGRADE_STREAM_ID, ErrorCode.REFUSED_STREAM, "Session is closed");
            }

            registerStream(Http2Stream.UPGRADE_STREAM_ID, stream);
            if (!isServer()) {
                lastLocalStreamId = Http2Stream.UPGRADE_STREAM_ID;
            }
        }
    }

    private void incStreamCount() {
        concurrentStreamsCount.incrementAndGet();
    }

    private void decStreamCount() {
        concurrentStreamsCount.decrementAndGet();
    }

    private final class ConnectionCloseListener implements CloseListener<Closeable, CloseType> {

        @Override
        public void onClosed(final Closeable closeable, final CloseType type) throws IOException {

            NetLogger.logClose(Http2Session.this);
            final List<Http2Stream> streams;
            synchronized (sessionLock) {
                if (!isClosed()) {
                    closeFlag = type;
                }
                // The streams have to be closed whether the session has been closed already or not: after a GOAWAY of
                // this side, it has been, but its streams are still open until the connection is closed.
                if (streamsClosedWithConnection) {
                    return;
                }
                streamsClosedWithConnection = true;
                streams = new ArrayList<>(streamsMap.values());
            }

            for (Http2Stream stream : streams) {
                stream.closedRemotely();
            }
        }
    }
}
