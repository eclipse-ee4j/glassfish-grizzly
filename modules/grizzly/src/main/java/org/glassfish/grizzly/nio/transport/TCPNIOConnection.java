/*
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

package org.glassfish.grizzly.nio.transport;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseReason;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.SelectorRunner;
import org.glassfish.grizzly.utils.Holder;

/**
 * {@link org.glassfish.grizzly.Connection} implementation for the {@link TCPNIOTransport}
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class TCPNIOConnection extends NIOConnection {
    private static final Logger LOGGER = Grizzly.logger(TCPNIOConnection.class);

    Holder<SocketAddress> localSocketAddressHolder;
    Holder<SocketAddress> peerSocketAddressHolder;

    private int readBufferSize = -1;
    private int writeBufferSize = -1;

    private AtomicReference<ConnectResultHandler> connectHandlerRef;

    public TCPNIOConnection(TCPNIOTransport transport, SelectableChannel channel) {
        super(transport);

        this.channel = channel;
    }

    @Override
    protected void setSelectionKey(SelectionKey selectionKey) {
        super.setSelectionKey(selectionKey);
    }

    @Override
    protected void setSelectorRunner(SelectorRunner selectorRunner) {
        super.setSelectorRunner(selectorRunner);
    }

    @Override
    protected void preClose() {
        checkConnectFailed(null);
        super.preClose();
    }

    protected boolean notifyReady() {
        return connectCloseSemaphoreUpdater.compareAndSet(this, null, NOTIFICATION_INITIALIZED);
    }

    /**
     * Returns the address of the endpoint this <tt>Connection</tt> is connected to, or <tt>null</tt> if it is unconnected.
     * 
     * @return the address of the endpoint this <tt>Connection</tt> is connected to, or <tt>null</tt> if it is unconnected.
     */
    @Override
    public SocketAddress getPeerAddress() {
        return peerSocketAddressHolder.get();
    }

    /**
     * Returns the local address of this <tt>Connection</tt>, or <tt>null</tt> if it is unconnected.
     * 
     * @return the local address of this <tt>Connection</tt>, or <tt>null</tt> if it is unconnected.
     */
    @Override
    public SocketAddress getLocalAddress() {
        return localSocketAddressHolder.get();
    }

    protected void resetProperties() {
        if (channel != null) {
            setReadBufferSize(transport.getReadBufferSize());
            setWriteBufferSize(transport.getWriteBufferSize());

            final int transportMaxAsyncWriteQueueSize = ((TCPNIOTransport) transport).getAsyncQueueIO().getWriter().getMaxPendingBytesPerConnection();

            setMaxAsyncWriteQueueSize(
                    transportMaxAsyncWriteQueueSize == AsyncQueueWriter.AUTO_SIZE ? getWriteBufferSize() * 4 : transportMaxAsyncWriteQueueSize);

            localSocketAddressHolder = Holder.lazyHolder(new Supplier<SocketAddress>() {
                @Override
                public SocketAddress get() {
                    return ((SocketChannel) channel).socket().getLocalSocketAddress();
                }
            });

            peerSocketAddressHolder = Holder.lazyHolder(new Supplier<SocketAddress>() {
                @Override
                public SocketAddress get() {
                    return ((SocketChannel) channel).socket().getRemoteSocketAddress();
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getReadBufferSize() {
        if (readBufferSize >= 0) {
            return readBufferSize;
        }

        try {
            readBufferSize = ((SocketChannel) channel).socket().getReceiveBufferSize();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, LogMessages.WARNING_GRIZZLY_CONNECTION_GET_READBUFFER_SIZE_EXCEPTION(), e);
            readBufferSize = 0;
        }

        return readBufferSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setReadBufferSize(final int readBufferSize) {
        if (readBufferSize > 0) {
            try {
                final int currentReadBufferSize = ((SocketChannel) channel).socket().getReceiveBufferSize();
                if (readBufferSize > currentReadBufferSize) {
                    ((SocketChannel) channel).socket().setReceiveBufferSize(readBufferSize);
                }

                this.readBufferSize = readBufferSize;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_CONNECTION_SET_READBUFFER_SIZE_EXCEPTION(), e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getWriteBufferSize() {
        if (writeBufferSize >= 0) {
            return writeBufferSize;
        }

        try {
            writeBufferSize = ((SocketChannel) channel).socket().getSendBufferSize();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, LogMessages.WARNING_GRIZZLY_CONNECTION_GET_WRITEBUFFER_SIZE_EXCEPTION(), e);
            writeBufferSize = 0;
        }

        return writeBufferSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWriteBufferSize(int writeBufferSize) {
        if (writeBufferSize > 0) {
            try {
                final int currentSendBufferSize = ((SocketChannel) channel).socket().getSendBufferSize();
                if (writeBufferSize > currentSendBufferSize) {
                    ((SocketChannel) channel).socket().setSendBufferSize(writeBufferSize);
                }
                this.writeBufferSize = writeBufferSize;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, LogMessages.WARNING_GRIZZLY_CONNECTION_SET_WRITEBUFFER_SIZE_EXCEPTION(), e);
            }
        }
    }

    protected final void setConnectResultHandler(final ConnectResultHandler connectHandler) {
        connectHandlerRef = new AtomicReference<>(connectHandler);
    }

    /**
     * Method will be called, when the connection gets connected.
     * 
     * @throws IOException
     */
    protected final void onConnect() throws IOException {
        final AtomicReference<ConnectResultHandler> localRef = connectHandlerRef;
        final ConnectResultHandler localConnectHandler;

        if (localRef != null && (localConnectHandler = localRef.getAndSet(null)) != null) {
            localConnectHandler.connected();
            connectHandlerRef = null;
        }

        notifyProbesConnect(this);
    }

    /**
     * Method will be called in order to check if failure happened before {@link Connection} was reported as connected.
     */
    protected final void checkConnectFailed(Throwable failure) {
        final AtomicReference<ConnectResultHandler> localRef = connectHandlerRef;
        final ConnectResultHandler localConnectHandler;

        if (localRef != null && (localConnectHandler = localRef.getAndSet(null)) != null) {
            if (failure == null) {
                failure = new IOException("closed");
            }

            localConnectHandler.failed(failure);
            connectHandlerRef = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void terminate0(final CompletionHandler<Closeable> completionHandler, final CloseReason closeReason) {
        super.terminate0(completionHandler, closeReason);
    }

    /**
     * Method will be called, when some data was read on the connection
     */
    protected final void onRead(Buffer data, int size) {
        if (size > 0) {
            notifyProbesRead(this, data, size);
        }
        checkEmptyRead(size);
    }

    @Override
    protected void enableInitialOpRead() throws IOException {
        super.enableInitialOpRead();
    }

    /**
     * Method will be called, when some data was written on the connection
     */
    protected final void onWrite(Buffer data, long size) {
        notifyProbesWrite(this, data, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite() {
        return transport.getWriter(this).canWrite(this);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    @Override
    public boolean canWrite(int length) {
        return transport.getWriter(this).canWrite(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyCanWrite(final WriteHandler writeHandler) {
        transport.getWriter(this).notifyWritePossible(this, writeHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    @Override
    public void notifyCanWrite(WriteHandler handler, int length) {
        transport.getWriter(this).notifyWritePossible(this, handler);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("TCPNIOConnection");
        sb.append("{localSocketAddress=").append(localSocketAddressHolder);
        sb.append(", peerSocketAddress=").append(peerSocketAddressHolder);
        sb.append('}');
        return sb.toString();
    }

    /**
     * This interface implementations can be used to be notified about the <tt>TCPNIOConnection</tt> connect state.
     */
    protected interface ConnectResultHandler {
        void connected() throws IOException;

        void failed(final Throwable t);
    }
}
