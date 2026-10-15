/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.nio.tmpselectors;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.glassfish.grizzly.AbstractWriter;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.asyncqueue.WritableMessage;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.NIOTransport;
import org.glassfish.grizzly.utils.Futures;

/**
 *
 * @author oleksiys
 */
@SuppressWarnings("deprecation")
public abstract class TemporarySelectorWriter extends AbstractWriter<SocketAddress> {

    protected final TemporarySelectorsEnabledTransport transport;

    public TemporarySelectorWriter(TemporarySelectorsEnabledTransport transport) {
        this.transport = transport;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final Connection<SocketAddress> connection, final SocketAddress dstAddress, final WritableMessage message,
            final CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler, final MessageCloner<WritableMessage> messageCloner) {

        write(connection, dstAddress, message, completionHandler, null, connection.getWriteTimeout(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    @Override
    public void write(Connection<SocketAddress> connection, SocketAddress dstAddress, WritableMessage message,
            CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler,
            final org.glassfish.grizzly.asyncqueue.PushBackHandler pushBackHandler) {
        write(connection, dstAddress, message, completionHandler, pushBackHandler, connection.getWriteTimeout(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

    /**
     * Method writes the {@link WritableMessage} to the specific address.
     *
     * @param connection the {@link org.glassfish.grizzly.Connection} to write to
     * @param dstAddress the destination address the <tt>message</tt> will be sent to
     * @param message the {@link WritableMessage}, from which the data will be written
     * @param completionHandler {@link org.glassfish.grizzly.CompletionHandler}, which will get notified, when write will be
     * completed
     */
    public void write(Connection<SocketAddress> connection, SocketAddress dstAddress, WritableMessage message,
            CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler, long timeout, TimeUnit timeunit) {
        write(connection, dstAddress, message, completionHandler, null, timeout, timeunit);
    }

    /**
     * Method writes the {@link WritableMessage} to the specific address.
     *
     * @param connection the {@link org.glassfish.grizzly.Connection} to write to
     * @param dstAddress the destination address the <tt>message</tt> will be sent to
     * @param message the {@link WritableMessage}, from which the data will be written
     * @param completionHandler {@link org.glassfish.grizzly.CompletionHandler}, which will get notified, when write will be
     * completed
     */
    public void write(Connection<SocketAddress> connection, SocketAddress dstAddress, WritableMessage message,
            CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler,
            final org.glassfish.grizzly.asyncqueue.PushBackHandler pushBackHandler, long timeout, TimeUnit timeunit) {

        if (message == null) {
            failure(new IllegalStateException("Message cannot be null"), completionHandler);
            return;
        }

        if (connection == null || !(connection instanceof NIOConnection)) {
            failure(new IllegalStateException("Connection should be NIOConnection and cannot be null"), completionHandler);
            return;
        }

        final NIOConnection nioConnection = (NIOConnection) connection;

        if (nioConnection.isWritesCorked()) {
            // don't overtake asynchronous writes the current thread holds back: write them, and if the channel doesn't
            // take all of them now, queue this write behind them and wait for it
            nioConnection.flushCorkedWrites();
            if (!nioConnection.getAsyncWriteQueue().isEmpty()) {
                writeBehindQueuedWrites(nioConnection, dstAddress, message, completionHandler, timeout, timeunit);
                return;
            }
        }

        final WriteResult<WritableMessage, SocketAddress> writeResult = WriteResult.create(connection, message, dstAddress, 0);

        try {
            write0(nioConnection, dstAddress, message, writeResult, timeout, timeunit);

            // Call PushBackHandler after data is written.
            // IMO It has more sense for blocking case
            if (pushBackHandler != null) {
                pushBackHandler.onAccept(connection, message);
            }

            if (completionHandler != null) {
                completionHandler.completed(writeResult);
            }

            message.release();
        } catch (IOException e) {
            failure(e, completionHandler);
        }
    }

    private static void writeBehindQueuedWrites(final NIOConnection connection, final SocketAddress dstAddress, final WritableMessage message,
            final CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler, final long timeout, final TimeUnit timeunit) {
        final FutureImpl<WriteResult<WritableMessage, SocketAddress>> future = Futures.createSafeFuture();
        ((NIOTransport) connection.getTransport()).getAsyncQueueIO().getWriter().write(connection, dstAddress, message,
                Futures.toCompletionHandler(future, completionHandler));
        try {
            if (timeout >= 0) {
                future.get(timeout, timeunit);
            } else {
                future.get();
            }
        } catch (ExecutionException e) {
            // the completion handler has been notified
        } catch (TimeoutException e) {
            connection.closeSilently();
            failure(new IOException("Write timeout exceeded", e), completionHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure(new IOException(e), completionHandler);
        }
    }

    /**
     * Flush the buffer by looping until the {@link Buffer} is empty
     *
     *
     * @param connection the {@link org.glassfish.grizzly.Connection}.
     * @param dstAddress the destination address.
     * @param message
     * @param currentResult the result of the write operation
     * @param timeout operation timeout value value
     * @param timeunit the timeout unit
     * @return The number of bytes written.
     *
     * @throws java.io.IOException
     */
    protected long write0(final NIOConnection connection, final SocketAddress dstAddress, final WritableMessage message,
            final WriteResult<WritableMessage, SocketAddress> currentResult, final long timeout, final TimeUnit timeunit) throws IOException {

        final SelectableChannel channel = connection.getChannel();
        final long writeTimeout = TimeUnit.MILLISECONDS.convert(timeout, timeunit);

        SelectionKey key = null;
        Selector writeSelector = null;
        int attempts = 0;
        int bytesWritten = 0;

        try {
            // noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (connection) {
                while (message.hasRemaining()) {
                    long len = writeNow0(connection, dstAddress, message, currentResult);

                    if (len > 0) {
                        attempts = 0;
                        bytesWritten += len;
                    } else {
                        attempts++;
                        if (writeSelector == null) {
                            writeSelector = transport.getTemporarySelectorIO().getSelectorPool().poll();

                            if (writeSelector == null) {
                                // Continue using the main one.
                                continue;
                            }
                            key = channel.register(writeSelector, SelectionKey.OP_WRITE);
                        } else {
                            writeSelector.selectedKeys().clear();
                        }

                        if (writeSelector.select(writeTimeout) == 0) {
                            if (attempts > 2) {
                                throw new IOException("Client disconnected");
                            }
                        }
                    }
                }
            }
        } finally {
            transport.getTemporarySelectorIO().recycleTemporaryArtifacts(writeSelector, key);
        }

        return bytesWritten;
    }

    public TemporarySelectorsEnabledTransport getTransport() {
        return transport;
    }

    protected abstract long writeNow0(NIOConnection connection, SocketAddress dstAddress, WritableMessage message,
            WriteResult<WritableMessage, SocketAddress> currentResult) throws IOException;

    private static void failure(final Throwable failure, final CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler) {
        if (completionHandler != null) {
            completionHandler.failed(failure);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite(final Connection connection) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyWritePossible(final Connection connection, final WriteHandler writeHandler) {
        try {
            writeHandler.onWritePossible();
        } catch (Throwable t) {
            writeHandler.onError(t);
        }
    }
}
