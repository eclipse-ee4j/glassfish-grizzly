/*
 * Copyright (c) 2009, 2025 Oracle and/or its affiliates. All rights reserved.
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
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.AbstractSocketConnectorHandler;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.IOEventLifeCycleListener;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.ReadyFutureImpl;
import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.nio.NIOChannelDistributor;
import org.glassfish.grizzly.nio.RegisterChannelResult;
import org.glassfish.grizzly.utils.Futures;

/**
 * UDP NIO transport client side ConnectorHandler implementation
 *
 * @author Alexey Stashok
 */
public class UDPNIOConnectorHandler extends AbstractSocketConnectorHandler {

    private static final Logger LOGGER = Grizzly.logger(UDPNIOConnectorHandler.class);

    protected boolean isReuseAddress;
    protected boolean isReusePort;
    protected volatile long connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT;

    protected UDPNIOConnectorHandler(UDPNIOTransport transport) {
        super(transport);
        connectionTimeoutMillis = transport.getConnectionTimeout();
        isReuseAddress = transport.isReuseAddress();
        isReusePort = transport.isReusePort();
    }

    /**
     * Creates non-connected UDP {@link Connection}.
     *
     * @return non-connected UDP {@link Connection}.
     */
    public GrizzlyFuture<Connection> connect() {
        return connectAsync(null, null, null, true);
    }

    @Override
    public void connect(final SocketAddress remoteAddress, final SocketAddress localAddress, final CompletionHandler<Connection> completionHandler) {

        if (!transport.isBlocking()) {
            connectAsync(remoteAddress, localAddress, completionHandler, false);
        } else {
            connectSync(remoteAddress, localAddress, completionHandler);
        }
    }

    protected void connectSync(final SocketAddress remoteAddress, final SocketAddress localAddress, final CompletionHandler<Connection> completionHandler) {

        final FutureImpl<Connection> future = connectAsync(remoteAddress, localAddress, completionHandler, true);

        waitNIOFuture(future, completionHandler);
    }

    @Override
    protected FutureImpl<Connection> connectAsync(final SocketAddress remoteAddress, final SocketAddress localAddress,
            final CompletionHandler<Connection> completionHandler, final boolean needFuture) {

        final UDPNIOTransport nioTransport = (UDPNIOTransport) transport;
        UDPNIOConnection newConnection = null;

        try {

            final DatagramChannel datagramChannel = nioTransport.getSelectorProvider().openDatagramChannel();

            nioTransport.getChannelConfigurator().preConfigure(nioTransport, datagramChannel);

            final DatagramSocket socket = datagramChannel.socket();
            newConnection = nioTransport.obtainNIOConnection(datagramChannel);

            final boolean reuseAddr = isReuseAddress;
            if (reuseAddr != nioTransport.isReuseAddress()) {
                socket.setReuseAddress(reuseAddr);
            }

            final boolean reusePort = isReusePort;
            if (reusePort != nioTransport.isReusePort() && nioTransport.isReusePortAvailable()) {
                try {
                    socket.setOption(StandardSocketOptions.SO_REUSEPORT, reusePort);
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, LogMessages.FINE_GRIZZLY_SOCKET_REUSEPORT_EXCEPTION(reusePort), t);
                }
            }

            socket.bind(localAddress);

            if (remoteAddress != null) {
                datagramChannel.connect(remoteAddress);
            }

            nioTransport.getChannelConfigurator().postConfigure(nioTransport, datagramChannel);

            preConfigure(newConnection);

            newConnection.setProcessor(getProcessor());
            newConnection.setProcessorSelector(getProcessorSelector());

            final NIOChannelDistributor nioChannelDistributor = nioTransport.getNIOChannelDistributor();

            if (nioChannelDistributor == null) {
                throw new IllegalStateException("NIOChannelDistributor is null. Is Transport running?");
            }

            final CompletionHandler<Connection> completionHandlerToPass;
            final FutureImpl<Connection> futureToReturn;

            if (needFuture) {
                futureToReturn = makeCancellableFuture(newConnection);

                completionHandlerToPass = Futures.toCompletionHandler(futureToReturn, completionHandler);

            } else {
                completionHandlerToPass = completionHandler;
                futureToReturn = null;
            }

            // if connected immediately - register channel on selector with NO_INTEREST
            // interest
            nioChannelDistributor.registerChannelAsync(datagramChannel, 0, newConnection, new ConnectHandler(newConnection, completionHandlerToPass));

            return futureToReturn;
        } catch (Exception e) {
            if (newConnection != null) {
                newConnection.closeSilently();
            }

            if (completionHandler != null) {
                completionHandler.failed(e);
            }

            return needFuture ? ReadyFutureImpl.<Connection>create(e) : null;
        }

    }

    public boolean isReuseAddress() {
        return isReuseAddress;
    }

    public void setReuseAddress(boolean isReuseAddress) {
        this.isReuseAddress = isReuseAddress;
    }

    public boolean isReusePort() {
        return isReusePort;
    }

    public void setReusePort(boolean isReusePort) {
        this.isReusePort = isReusePort;
    }

    public long getSyncConnectTimeout(final TimeUnit timeUnit) {
        return timeUnit.convert(connectionTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void setSyncConnectTimeout(final long timeout, final TimeUnit timeUnit) {
        this.connectionTimeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, timeUnit);
    }

    protected void waitNIOFuture(final FutureImpl<Connection> future, final CompletionHandler<Connection> completionHandler) {

        try {
            future.get(connectionTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Futures.notifyFailure(future, completionHandler, e);
        } catch (TimeoutException e) {
            Futures.notifyFailure(future, completionHandler, new IOException("Channel registration on Selector timeout!"));
        } catch (Exception ingored) {
        }
    }

    private static void abortConnection(final UDPNIOConnection connection, final CompletionHandler<Connection> completionHandler, final Throwable failure) {

        connection.closeSilently();

        if (completionHandler != null) {
            completionHandler.failed(failure);
        }
    }

    private final class ConnectHandler extends EmptyCompletionHandler<RegisterChannelResult> {

        private final UDPNIOConnection connection;
        private final CompletionHandler<Connection> completionHandler;

        private ConnectHandler(final UDPNIOConnection connection, final CompletionHandler<Connection> completionHandler) {
            this.connection = connection;
            this.completionHandler = completionHandler;
        }

        @Override
        public void completed(RegisterChannelResult result) {
            final UDPNIOTransport transport = (UDPNIOTransport) UDPNIOConnectorHandler.this.transport;

            transport.registerChannelCompletionHandler.completed(result);

            try {
                connection.onConnect();
            } catch (Exception e) {
                abortConnection(connection, completionHandler, e);
//                LOGGER.log(Level.FINE, "Exception happened, when "
//                        + "trying to connect the channel", e);
            }

            if (connection.notifyReady()) {
                transport.fireIOEvent(IOEvent.CONNECTED, connection, new EnableReadHandler(completionHandler));
            }
        }

        @Override
        public void failed(final Throwable throwable) {
            abortConnection(connection, completionHandler, throwable);
        }
    }
    // COMPLETE, COMPLETE_LEAVE, REREGISTER, RERUN, ERROR, TERMINATE, NOT_RUN
//    private final static boolean[] isRegisterMap = {true, false, true, false, false, false, true};

    // PostProcessor, which supposed to enable OP_READ interest, once Processor will be notified
    // about Connection CONNECT
    private static class EnableReadHandler extends IOEventLifeCycleListener.Adapter {

        private final CompletionHandler<Connection> completionHandler;

        private EnableReadHandler(final CompletionHandler<Connection> completionHandler) {
            this.completionHandler = completionHandler;
        }

        @Override
        public void onReregister(final Context context) throws IOException {
            onComplete(context, null);
        }

        @Override
        public void onNotRun(final Context context) throws IOException {
            onComplete(context, null);
        }

        @Override
        public void onComplete(final Context context, final Object data) throws IOException {
            final UDPNIOConnection connection = (UDPNIOConnection) context.getConnection();

            if (completionHandler != null) {
                completionHandler.completed(connection);
            }

            if (!connection.isStandalone()) {
                connection.enableInitialOpRead();
            }
        }

        @Override
        public void onError(final Context context, final Object description) throws IOException {
            context.getConnection().closeSilently();
        }
    }

    /**
     * Return the {@link UDPNIOConnectorHandler} builder.
     *
     * @param transport {@link UDPNIOTransport}.
     * @return the {@link UDPNIOConnectorHandler} builder.
     */
    public static Builder builder(final UDPNIOTransport transport) {
        return new UDPNIOConnectorHandler.Builder().setTransport(transport);
    }

    public static class Builder extends AbstractSocketConnectorHandler.Builder<Builder> {
        private UDPNIOTransport transport;
        private Boolean reuseAddress;
        private Boolean reusePort;
        private Long timeout;
        private TimeUnit timeoutTimeunit;

        @Override
        public UDPNIOConnectorHandler build() {
            UDPNIOConnectorHandler handler = (UDPNIOConnectorHandler) super.build();
            if (reuseAddress != null) {
                handler.setReuseAddress(reuseAddress);
            }
            if (reusePort != null) {
                handler.setReusePort(reusePort);
            }
            if (timeout != null) {
                handler.setSyncConnectTimeout(timeout, timeoutTimeunit);
            }
            return handler;
        }

        public Builder setTransport(final UDPNIOTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder setReuseAddress(final boolean reuseAddress) {
            this.reuseAddress = reuseAddress;
            return this;
        }

        public Builder setReusePort(final boolean reusePort) {
            this.reusePort = reusePort;
            return this;
        }

        public Builder setSyncConnectTimeout(final long timeout, final TimeUnit timeunit) {
            this.timeout = timeout;
            timeoutTimeunit = timeunit;
            return this;
        }

        @Override
        protected AbstractSocketConnectorHandler create() {
            if (transport == null) {
                throw new IllegalStateException("Unable to create UDPNIOConnectorHandler - transport is null");
            }
            return new UDPNIOConnectorHandler(transport);
        }
    }
}
