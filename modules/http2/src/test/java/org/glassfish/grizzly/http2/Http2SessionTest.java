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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.DefaultFilterChain;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test {@link Http2Session}.
 *
 * @author Sven Diedrichsen (sven.diedrichsen@gmail.com)
 * @since 26.05.18
 */
public class Http2SessionTest {

    private Http2Configuration configuration = Http2Configuration.builder().cleanPercentage(1.0f).streamsHighWaterMark(0.01f).maxConcurrentStreams(150)
            .initialWindowSize(2).build();

    private Http2Session session;
    private Connection<?> connection;
    private Filter sessionChainFilter;
    private final List<LogRecord> logRecords = new CopyOnWriteArrayList<>();
    private Handler logHandler;

    @Before
    public void setUp() throws Exception {
        FilterChain filterChain = newFilterChain();
        connection = newConnectionMock(filterChain);
        session = new Http2Session(connection, true, new Http2ServerFilter(configuration));
        session.setupFilterChains(newFilterChainContext(filterChain, connection), false);
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
        filterChain.add(mock(Filter.class));
        return filterChain;
    }

    private Connection<?> newConnectionMock(FilterChain filterChain) {
        Connection<?> connection = mock(Connection.class);
        MemoryManager memoryManager = new ByteBufferManager();
        doReturn(filterChain).when(connection).getProcessor();
        doReturn(memoryManager).when(connection).getMemoryManager();
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

}
