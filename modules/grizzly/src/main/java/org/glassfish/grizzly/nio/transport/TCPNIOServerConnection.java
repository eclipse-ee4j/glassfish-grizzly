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
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.CloseReason;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.RegisterChannelResult;
import org.glassfish.grizzly.nio.SelectionKeyHandler;
import org.glassfish.grizzly.utils.CompletionHandlerAdapter;
import org.glassfish.grizzly.utils.Exceptions;
import org.glassfish.grizzly.utils.Holder;

/**
 *
 * @author oleksiys
 */
public final class TCPNIOServerConnection extends TCPNIOConnection {

    private static boolean DISABLE_INTERRUPT_CLEAR = Boolean
            .valueOf(System.getProperty(TCPNIOServerConnection.class.getName() + "_DISABLE_INTERRUPT_CLEAR", "false"));

    private static final Logger LOGGER = Grizzly.logger(TCPNIOServerConnection.class);
    private FutureImpl<Connection> acceptListener;
    private final RegisterAcceptedChannelCompletionHandler defaultCompletionHandler;
    private final Object acceptSync = new Object();

    public TCPNIOServerConnection(TCPNIOTransport transport, ServerSocketChannel serverSocketChannel) {
        super(transport, serverSocketChannel);
        defaultCompletionHandler = new RegisterAcceptedChannelCompletionHandler();
    }

    public void listen() throws IOException {
        final CompletionHandler<RegisterChannelResult> registerCompletionHandler = ((TCPNIOTransport) transport).selectorRegistrationHandler;

        final FutureImpl<RegisterChannelResult> future = SafeFutureImpl.create();

        transport.getNIOChannelDistributor().registerServiceChannelAsync(channel, SelectionKey.OP_ACCEPT, this,
                new CompletionHandlerAdapter<RegisterChannelResult, RegisterChannelResult>(future, registerCompletionHandler));
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw Exceptions.makeIOException(e.getCause());
        } catch (Exception e) {
            throw Exceptions.makeIOException(e);
        }

        notifyReady();
        notifyProbesBind(this);
    }

    @Override
    public boolean isBlocking() {
        return transport.isBlocking();
    }

    @Override
    public boolean isStandalone() {
        return transport.isStandalone();
    }

    /**
     * Accept a {@link Connection}. Could be used only in standalone mode. See
     * {@link Connection#configureStandalone(boolean)}.
     *
     * @return {@link Future}
     * @throws java.io.IOException
     */
    public GrizzlyFuture<Connection> accept() throws IOException {
        if (!isStandalone()) {
            throw new IllegalStateException("Accept could be used in standalone mode only");
        }

        final GrizzlyFuture<Connection> future = acceptAsync();

        if (isBlocking()) {
            try {
                future.get();
            } catch (Exception ignored) {
            }
        }

        return future;
    }

    /**
     * Asynchronously accept a {@link Connection}
     *
     * @return {@link Future}
     * @throws java.io.IOException
     */
    protected GrizzlyFuture<Connection> acceptAsync() throws IOException {
        if (!isOpen()) {
            throw new IOException("Connection is closed");
        }

        synchronized (acceptSync) {
            final FutureImpl<Connection> future = SafeFutureImpl.create();
            final SocketChannel acceptedChannel = doAccept();
            if (acceptedChannel != null) {
                configureAcceptedChannel(acceptedChannel);
                final TCPNIOConnection clientConnection = createClientConnection(acceptedChannel);
                registerAcceptedChannel(clientConnection, new RegisterAcceptedChannelCompletionHandler(future), 0);
            } else {
                acceptListener = future;
                enableIOEvent(IOEvent.SERVER_ACCEPT);
            }

            return future;
        }
    }

    private SocketChannel doAccept() throws IOException {
        if (!DISABLE_INTERRUPT_CLEAR && Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
        }
        return ((ServerSocketChannel) getChannel()).accept();
    }

    private void configureAcceptedChannel(final SocketChannel acceptedChannel) throws IOException {
        final TCPNIOTransport tcpNIOTransport = (TCPNIOTransport) transport;
        tcpNIOTransport.getChannelConfigurator().preConfigure(transport, acceptedChannel);
        tcpNIOTransport.getChannelConfigurator().postConfigure(transport, acceptedChannel);
    }

    private TCPNIOConnection createClientConnection(final SocketChannel acceptedChannel) {
        final TCPNIOTransport tcpNIOTransport = (TCPNIOTransport) transport;
        final TCPNIOConnection connection = tcpNIOTransport.obtainNIOConnection(acceptedChannel);

        if (processor != null) {
            connection.setProcessor(processor);
        }

        if (processorSelector != null) {
            connection.setProcessorSelector(processorSelector);
        }

        connection.resetProperties();

        return connection;
    }

    private void registerAcceptedChannel(final TCPNIOConnection acceptedConnection, final CompletionHandler<RegisterChannelResult> completionHandler,
            final int initialSelectionKeyInterest) throws IOException {

        final TCPNIOTransport tcpNIOTransport = (TCPNIOTransport) transport;

        tcpNIOTransport.getNIOChannelDistributor().registerChannelAsync(acceptedConnection.getChannel(), initialSelectionKeyInterest, acceptedConnection,
                completionHandler);
    }

    @Override
    public void preClose() {
        if (acceptListener != null) {
            acceptListener.failure(new IOException("Connection is closed"));
        }

        transport.unbind(this);

        super.preClose();
    }

    /**
     * Method will be called by framework, when async accept will be ready
     *
     * @throws java.io.IOException
     */
    public void onAccept() throws IOException {

        final TCPNIOConnection acceptedConnection;

        if (!isStandalone()) {
            final SocketChannel acceptedChannel = doAccept();
            if (acceptedChannel == null) {
                return;
            }

            configureAcceptedChannel(acceptedChannel);
            acceptedConnection = createClientConnection(acceptedChannel);

            notifyProbesAccept(this, acceptedConnection);

            registerAcceptedChannel(acceptedConnection, defaultCompletionHandler, SelectionKey.OP_READ);
        } else {
            synchronized (acceptSync) {
                if (acceptListener == null) {
                    TCPNIOServerConnection.this.disableIOEvent(IOEvent.SERVER_ACCEPT);
                    return;
                }

                final SocketChannel acceptedChannel = doAccept();
                if (acceptedChannel == null) {
                    return;
                }

                configureAcceptedChannel(acceptedChannel);
                acceptedConnection = createClientConnection(acceptedChannel);

                notifyProbesAccept(this, acceptedConnection);

                registerAcceptedChannel(acceptedConnection, new RegisterAcceptedChannelCompletionHandler(acceptListener), 0);
                acceptListener = null;
            }
        }
    }

    @Override
    public void setReadBufferSize(final int readBufferSize) {
        throw new IllegalStateException("Use TCPNIOTransport.setReadBufferSize()");
    }

    @Override
    public void setWriteBufferSize(final int writeBufferSize) {
        throw new IllegalStateException("Use TCPNIOTransport.setWriteBufferSize()");
    }

    @Override
    public int getReadBufferSize() {
        return transport.getReadBufferSize();
    }

    @Override
    public int getWriteBufferSize() {
        return transport.getWriteBufferSize();
    }

    @Override
    protected void closeGracefully0(final CompletionHandler<Closeable> completionHandler, final CloseReason closeReason) {
        terminate0(completionHandler, closeReason);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void resetProperties() {
        localSocketAddressHolder = Holder.lazyHolder(new Supplier<SocketAddress>() {

            @Override
            public SocketAddress get() {
                return ((ServerSocketChannel) channel).socket().getLocalSocketAddress();
            }
        });

        peerSocketAddressHolder = Holder.staticHolder(null);
    }

    protected final class RegisterAcceptedChannelCompletionHandler extends EmptyCompletionHandler<RegisterChannelResult> {

        private final FutureImpl<Connection> listener;

        public RegisterAcceptedChannelCompletionHandler() {
            this(null);
        }

        public RegisterAcceptedChannelCompletionHandler(FutureImpl<Connection> listener) {
            this.listener = listener;
        }

        @Override
        public void completed(RegisterChannelResult result) {
            try {
                final TCPNIOTransport nioTransport = (TCPNIOTransport) transport;

                nioTransport.selectorRegistrationHandler.completed(result);

                final SelectionKeyHandler selectionKeyHandler = nioTransport.getSelectionKeyHandler();
                final SelectionKey acceptedConnectionKey = result.getSelectionKey();
                final TCPNIOConnection connection = (TCPNIOConnection) selectionKeyHandler.getConnectionForKey(acceptedConnectionKey);

                if (listener != null) {
                    listener.result(connection);
                }

                if (connection.notifyReady()) {
                    transport.fireIOEvent(IOEvent.ACCEPTED, connection, null);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Exception happened, when " + "trying to accept the connection", e);
            }
        }
    }
}
