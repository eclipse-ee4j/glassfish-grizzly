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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.DefaultFilterChain;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpBrokenContent;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http2.frames.DataFrame;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.http2.frames.GoAwayFrame;
import org.glassfish.grizzly.http2.frames.HeadersFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;
import org.glassfish.grizzly.http2.frames.PingFrame;
import org.glassfish.grizzly.http2.frames.RstStreamFrame;
import org.glassfish.grizzly.http2.frames.SettingsFrame;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.ByteBufferWrapper;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * Test {@link Http2Session}.
 *
 * @author Sven Diedrichsen (sven.diedrichsen@gmail.com)
 * @since 26.05.18
 */
public class Http2SessionTest {

    private final Http2Configuration configuration = Http2Configuration.builder().cleanPercentage(1.0f).streamsHighWaterMark(0.01f).maxConcurrentStreams(150)
            .initialWindowSize(2).resetStreamRateLimit(2, 0).build();

    private Http2ServerFilter serverFilter;
    private Http2Session session;
    private Connection<?> connection;
    private FilterChainContext context;
    private Filter sessionChainFilter;
    private Filter streamChainFilter;
    private final List<LogRecord> logRecords = new CopyOnWriteArrayList<>();
    private Handler logHandler;

    @Before
    public void setUp() throws Exception {
        FilterChain filterChain = newFilterChain();
        connection = newConnectionMock(filterChain);
        serverFilter = new Http2ServerFilter(configuration);
        session = new Http2Session(connection, true, serverFilter);
        context = newFilterChainContext(filterChain, connection);
        session.setupFilterChains(context, false);
        logHandler = newLogHandler();
        sessionLogger().addHandler(logHandler);
    }

    @After
    public void tearDown() {
        sessionLogger().removeHandler(logHandler);
    }

    private FilterChainContext newFilterChainContext(FilterChain filterChain, Connection<?> connection) {
        FilterChainContext context = FilterChainContext.create(connection);
        context.getInternalContext().setProcessor(filterChain);
        // the filters below this index form the session chain, the ones above it
        // form the stream chain
        context.setFilterIdx(1);
        context.setEndIdx(-1);
        return context;
    }

    private FilterChain newFilterChain() throws Exception {
        FilterChain filterChain = new DefaultFilterChain();
        sessionChainFilter = mock(Filter.class);
        // the filter swallows the write, so nothing tries to reach a transport
        doAnswer(invocation -> ((FilterChainContext) invocation.getArgument(0)).getInvokeAction()).when(sessionChainFilter)
                .handleWrite(any(FilterChainContext.class));
        filterChain.add(sessionChainFilter);
        streamChainFilter = mock(Filter.class);
        filterChain.add(streamChainFilter);
        return filterChain;
    }

    private Connection<?> newConnectionMock(FilterChain filterChain) {
        Connection<?> connection = mock(Connection.class);
        MemoryManager memoryManager = new ByteBufferManager();
        doReturn(filterChain).when(connection).getProcessor();
        doReturn(memoryManager).when(connection).getMemoryManager();
        doReturn(Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createUnsafeAttributeHolder()).when(connection).getAttributes();
        // the filter chain keeps its state in the connection
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get()).when(connection).obtainProcessorState(any(Processor.class),
                any(Supplier.class));
        return connection;
    }

    private Handler newLogHandler() {
        return new Handler() {

            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static Logger sessionLogger() {
        return Grizzly.logger(Http2Session.class);
    }

    private boolean isLoggedAtLeast(Level level) {
        return logRecords.stream().anyMatch(record -> record.getLevel().intValue() >= level.intValue());
    }

    @Test
    public void testTerminateClosedStreams() {
        Http2Stream stream = mock(Http2Stream.class);
        doReturn(true).when(stream).isClosed();
        session.registerStream(1, stream);
        session.registerStream(2, stream);
        session.registerStream(3, stream);

        session.terminate(ErrorCode.INTERNAL_ERROR, "Test");

        verify(stream, times(3)).closedRemotely();
    }

    @Test
    public void testTerminateWritesGoAwayOnOpenConnection() throws Exception {
        doReturn(true).when(connection).isOpen();

        session.terminate(ErrorCode.NO_ERROR, "Session closed");

        // the connection is closed by the write completion handler, which the
        // mocked filter chain never invokes
        verify(sessionChainFilter, times(1)).handleWrite(any(FilterChainContext.class));
    }

    /**
     * When the peer is gone, writing the GOAWAY can only fail with the exception the connection was closed with, which
     * used to be reported as a WARNING together with its stack trace.
     */
    @Test
    public void testTerminateSkipsGoAwayOnClosedConnection() throws Exception {
        doReturn(false).when(connection).isOpen();

        session.terminate(ErrorCode.NO_ERROR, "Session closed");

        verify(sessionChainFilter, never()).handleWrite(any(FilterChainContext.class));
        verify(connection, times(1)).closeSilently();
        assertFalse("Terminating a session of an already closed connection must not be reported above FINE.",
                isLoggedAtLeast(Level.INFO));
    }

    @Test
    public void testCtorInitializesLocalMaxConcurrentStreams() {
        assertThat("Http2Sessions LocalMaxConcurrentStreams supposed to be taken from configuration.", session.getLocalMaxConcurrentStreams(), is(150));
    }

    @Test
    public void testCtorInitializesLocalStreamWindowSize() {
        assertThat("Http2Sessions LocalStreamWindowSize supposed to be taken from configuration.", session.getLocalStreamWindowSize(), is(2));
    }

    @Test
    public void testResetBudgetTerminatesSessionWithPendingGoAway() throws Exception {
        doReturn(true).when(connection).isOpen();
        Http2Session.bind(connection, session);
        assertTrue(serverFilter.processFrames(context, session, new ArrayList<>(List.of(SettingsFrame.builder().build()))));
        clearInvocations(sessionChainFilter);

        final Http2Stream accepted = mock(Http2Stream.class);
        final Http2Stream overBudget = mock(Http2Stream.class);
        session.registerStream(1, accepted);
        session.registerStream(5, overBudget);
        // Stream 3 has already been removed.
        session.lastPeerStreamId = 5;
        assertTrue(serverFilter.processFrames(context, session, new ArrayList<>(List.of(reset(1), reset(3)))));
        verify(accepted).resetRemotely();
        verify(connection, never()).terminateSilently();

        final List<Buffer> pendingWrites = new ArrayList<>();
        // Keep the write pending by stopping the chain without invoking its completion handler.
        doAnswer(invocation -> {
            final FilterChainContext writeContext = invocation.getArgument(0);
            pendingWrites.add(writeContext.getMessage());
            return writeContext.getStopAction();
        }).when(sessionChainFilter).handleWrite(any(FilterChainContext.class));

        final DataFrame data = DataFrame.fromBuffer(0, 5, new ByteBufferWrapper(ByteBuffer.allocate(1)));
        final HeadersFrame headers = HeadersFrame.fromBuffer(HeadersFrame.END_HEADERS, 7, new ByteBufferWrapper(ByteBuffer.allocate(1)));
        final Buffer dataPayload = data.getData();
        final Buffer headersPayload = headers.getCompressedHeaders();
        dataPayload.allowBufferDispose(true);
        headersPayload.allowBufferDispose(true);
        final List<Http2Frame> frames = new ArrayList<>(List.of(reset(5), PingFrame.builder().build(), data, headers));

        assertFalse(serverFilter.processFrames(context, session, frames));
        assertTrue(frames.isEmpty());
        final List<Http2Frame> laterFrames = new ArrayList<>(List.of(PingFrame.builder().build()));
        assertFalse(serverFilter.processFrames(context, session, laterFrames));
        assertTrue(laterFrames.isEmpty());
        // Reads that deliver no frame must not report a healthy session either.
        assertFalse(serverFilter.processFrames(context, session, new ArrayList<>()));

        verify(overBudget, never()).resetRemotely();
        verify(overBudget).terminateSilently();
        verify(streamChainFilter, never()).handleRead(any(FilterChainContext.class));
        assertNull(session.getStream(7));
        assertThrows(IllegalStateException.class, dataPayload::remaining);
        assertThrows(IllegalStateException.class, headersPayload::remaining);

        final InOrder termination = inOrder(sessionChainFilter, connection);
        termination.verify(sessionChainFilter).handleWrite(any(FilterChainContext.class));
        termination.verify(connection).terminateSilently();
        assertEquals(1, pendingWrites.size());
        final GoAwayFrame goAway = (GoAwayFrame) session.parseHttp2FrameHeader(pendingWrites.get(0));
        assertEquals(ErrorCode.ENHANCE_YOUR_CALM, goAway.getErrorCode());
        assertEquals(5, goAway.getLastStreamId());
        goAway.recycle();
    }

    @Test
    public void testResetBudgetDoesNotLimitClientSessions() {
        final Http2Session client = new Http2Session(connection, false, new Http2ClientFilter(configuration));
        assertTrue(client.tryConsumeResetStreamBudget());
        assertTrue(client.tryConsumeResetStreamBudget());
        assertTrue(client.tryConsumeResetStreamBudget());
        assertFalse(client.isResetStreamBudgetExceeded());
    }

    @Test
    public void testZeroBurstDisablesResetBudgetOnServer() {
        final Http2Configuration disabled = Http2Configuration.builder().resetStreamRateLimit(0, 0).build();
        final Http2Session server = new Http2Session(connection, true, new Http2ServerFilter(disabled));
        assertTrue(server.tryConsumeResetStreamBudget());
        assertFalse(server.isResetStreamBudgetExceeded());
    }

    @Test
    public void testResetBudgetCleanupContinuesWhenStreamCloseThrows() {
        final Http2Stream failing = mock(Http2Stream.class);
        final Http2Stream remaining = mock(Http2Stream.class);
        doThrow(new IllegalStateException("Stream close callback failed")).when(failing).terminateSilently();
        session.registerStream(1, failing);
        session.registerStream(3, remaining);
        session.lastPeerStreamId = 3;
        session.streamsToFlushInput.add(remaining);

        session.terminateForResetStreamLimit(new Http2SessionException(ErrorCode.ENHANCE_YOUR_CALM));

        verify(connection).terminateSilently();
        verify(remaining).discardCachedInput();
        assertTrue(session.streamsToFlushInput.isEmpty());
        final InOrder cleanup = inOrder(failing, remaining);
        cleanup.verify(failing).terminateSilently();
        cleanup.verify(remaining).terminateSilently();
    }

    @Test
    public void testDropsContentAfterResetBudgetIsExceeded() throws Exception {
        assertTrue(session.tryConsumeResetStreamBudget());
        assertTrue(session.tryConsumeResetStreamBudget());
        assertFalse(session.tryConsumeResetStreamBudget());

        final Http2Stream stream = mock(Http2Stream.class);
        final HttpRequestPacket request = HttpRequestPacket.builder().build();
        final Buffer payload = new ByteBufferWrapper(ByteBuffer.allocate(1));
        payload.allowBufferDispose(true);
        final HttpContent content = HttpContent.builder(request).content(payload).build();
        session.sendMessageUpstreamWithParseNotify(stream, content);
        assertThrows(IllegalStateException.class, payload::remaining);

        final Buffer dataPayload = new ByteBufferWrapper(ByteBuffer.allocate(1));
        dataPayload.allowBufferDispose(true);
        session.sendMessageUpstream(stream, HttpContent.builder(request).content(dataPayload).build());
        assertThrows(IllegalStateException.class, dataPayload::remaining);
        verify(streamChainFilter, never()).handleRead(any(FilterChainContext.class));

        // Broken content is not dropped, with or without the parse notification: it tells the reader of a stream
        // whose termination is queued that the stream is gone.
        request.getProcessingState().setHttpContext(HttpContext.newInstance(stream, stream, stream, request));
        doAnswer(invocation -> ((FilterChainContext) invocation.getArgument(0)).getStopAction()).when(streamChainFilter)
                .handleRead(any(FilterChainContext.class));
        final HttpBrokenContent broken = HttpBrokenContent.builder(request).error(new IOException("Stream reset")).build();
        session.sendMessageUpstream(stream, broken);
        verify(streamChainFilter, times(1)).handleRead(any(FilterChainContext.class));
        final HttpBrokenContent brokenWithNotify = HttpBrokenContent.builder(request).error(new IOException("Stream reset")).build();
        session.sendMessageUpstreamWithParseNotify(stream, brokenWithNotify);
        verify(streamChainFilter, times(2)).handleRead(any(FilterChainContext.class));
    }

    @Test
    public void testNoFlowControlAcknowledgementAfterResetBudgetIsExceeded() throws Exception {
        assertTrue(session.tryConsumeResetStreamBudget());
        assertTrue(session.tryConsumeResetStreamBudget());
        assertFalse(session.tryConsumeResetStreamBudget());

        session.ackConsumedData(null, 1000);

        final Field unacked = Http2Session.class.getDeclaredField("unackedReadBytes");
        unacked.setAccessible(true);
        assertEquals(0, ((AtomicInteger) unacked.get(session)).get());
    }

    @Test
    public void testDiscardCachedInputDisposesTheBuffers() throws Exception {
        final Http2Stream stream = new Http2Stream(session, HttpRequestPacket.builder().build(), 1, 0, false, 0);
        stream.onRcvHeaders(false);
        final Buffer first = new ByteBufferWrapper(ByteBuffer.allocate(1));
        final Buffer second = new ByteBufferWrapper(ByteBuffer.allocate(1));
        first.allowBufferDispose(true);
        second.allowBufferDispose(true);
        stream.offerInputData(first, false);
        stream.offerInputData(second, false);

        stream.discardCachedInput();

        assertThrows(IllegalStateException.class, first::remaining);
        assertThrows(IllegalStateException.class, second::remaining);
        // A second call finds nothing left to dispose.
        stream.discardCachedInput();
    }

    private static RstStreamFrame reset(final int streamId) {
        return RstStreamFrame.builder().streamId(streamId).errorCode(ErrorCode.CANCEL).build();
    }

}
