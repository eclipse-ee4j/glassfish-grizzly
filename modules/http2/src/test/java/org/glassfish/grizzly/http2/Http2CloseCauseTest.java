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

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.CloseReason;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.junit.Test;

/**
 * Writes pending on an HTTP/2 output queue that's closed because its connection was closed must fail with the cause
 * the connection was closed with, not with a generic "Connection closed" IOException without a cause
 * (NIOOutputSinksTest#testWriteExceptionPropagation failed intermittently because of this).
 */
public class Http2CloseCauseTest {

    @Test
    public void pendingStreamWriteFailsWithTheCauseTheStreamWasClosedRemotelyWith() {
        final Http2Session session = mock(Http2Session.class);
        doReturn(true).when(session).isServer();
        final HttpRequestPacket request = HttpRequestPacket.builder().method("GET").uri("/").protocol("HTTP/1.1").build();
        final Http2Stream stream = new Http2Stream(session, request, 0);
        final DefaultOutputSink outputSink = (DefaultOutputSink) stream.getOutputSink();

        // fill the stream output queue, so the write handler waits on it (like a blocked OutputBuffer write)
        outputSink.outputQueue.reserveSpace(1 << 20);
        final AtomicBoolean writePossible = new AtomicBoolean();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        outputSink.notifyWritePossible(recordingWriteHandler(writePossible, error));

        final IOException cause = new IOException("the reason the connection was closed with");
        stream.closedRemotely(cause);

        assertFalse(writePossible.get());
        assertFailedWith(cause, error.get());
        assertSame(cause, stream.getCloseCause());
    }

    @Test
    public void pendingSessionWritesFailWithTheCauseTheConnectionWasClosedWith() {
        final IOException cause = new IOException("the reason the connection was closed with");
        final Connection<?> connection = mock(Connection.class);
        doReturn(true).when(connection).canWrite();
        doReturn(new CloseReason(CloseType.LOCALLY, cause)).when(connection).getCloseReason();
        final Http2Session session = mock(Http2Session.class);
        doReturn(connection).when(session).getConnection();
        // no connection window, so the written data stays in the session output queue
        doReturn(0).when(session).getDefaultConnectionWindowSize();
        final Http2Stream stream = mock(Http2Stream.class);
        doReturn(1).when(stream).getId();

        final Http2SessionOutputSink sink = new Http2SessionOutputSink(session);

        // a pending record, large enough to fill the queue, so the write handler below has to wait too
        final AtomicReference<Throwable> recordError = new AtomicReference<>();
        sink.writeDataDownStream(stream, null, Buffers.wrap(new ByteBufferManager(), new byte[1 << 17]),
                new EmptyCompletionHandler<WriteResult>() {
                    @Override
                    public void failed(final Throwable throwable) {
                        recordError.set(throwable);
                    }
                }, null, false);
        assertFalse(sink.canWrite());

        final AtomicBoolean writePossible = new AtomicBoolean();
        final AtomicReference<Throwable> handlerError = new AtomicReference<>();
        sink.notifyCanWrite(recordingWriteHandler(writePossible, handlerError));

        sink.close();

        assertFalse(writePossible.get());
        assertFailedWith(cause, recordError.get());
        assertFailedWith(cause, handlerError.get());
    }

    private static WriteHandler recordingWriteHandler(final AtomicBoolean writePossible, final AtomicReference<Throwable> error) {
        return new WriteHandler() {
            @Override
            public void onWritePossible() {
                writePossible.set(true);
            }

            @Override
            public void onError(final Throwable t) {
                error.set(t);
            }
        };
    }

    private static void assertFailedWith(final IOException expectedCause, final Throwable failure) {
        assertNotNull("the pending write must be failed", failure);
        assertTrue(failure instanceof IOException);
        assertSame(expectedCause, failure.getCause());
    }
}
