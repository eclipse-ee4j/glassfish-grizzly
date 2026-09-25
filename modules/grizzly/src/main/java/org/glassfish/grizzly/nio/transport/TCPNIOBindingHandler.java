/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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
import java.net.BindException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.locks.Lock;

import org.glassfish.grizzly.AbstractBindingHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.utils.Exceptions;

/**
 * This class may be used to apply a custom {@link org.glassfish.grizzly.Processor} and/or
 * {@link org.glassfish.grizzly.ProcessorSelector} atomically within a bind operation - not something that can normally
 * be done using the {@link TCPNIOTransport} alone.
 *
 * Example usage:
 *
 * <pre>
 * TCPNIOBindingHandler handler = TCPNIOBindingHandler.builder(transport).setProcessor(custom).build();
 * handler.bind(socketAddress);
 * </pre>
 *
 * @since 2.2.19
 */
public class TCPNIOBindingHandler extends AbstractBindingHandler {

    private final TCPNIOTransport tcpTransport;

    // ------------------------------------------------------------ Constructors

    TCPNIOBindingHandler(final TCPNIOTransport tcpTransport) {
        super(tcpTransport);
        this.tcpTransport = tcpTransport;
    }

    // ------------------------------- Methods from AbstractBindingHandler

    @Override
    public TCPNIOServerConnection bind(SocketAddress socketAddress) throws IOException {
        return bind(socketAddress, tcpTransport.getServerConnectionBackLog());
    }

    @Override
    public TCPNIOServerConnection bind(SocketAddress socketAddress, int backlog) throws IOException {
        return bindToChannelAndAddress(tcpTransport.getSelectorProvider().openServerSocketChannel(), socketAddress, backlog);
    }

    @Override
    public TCPNIOServerConnection bindToInherited() throws IOException {
        return bindToChannelAndAddress(this.<ServerSocketChannel>getSystemInheritedChannel(ServerSocketChannel.class), null, -1);
    }

    @Override
    public void unbind(Connection connection) {
        tcpTransport.unbind(connection);
    }

    public static Builder builder(final TCPNIOTransport transport) {
        return new TCPNIOBindingHandler.Builder().transport(transport);
    }

    // --------------------------------------------------------- Private Methods

    private TCPNIOServerConnection bindToChannelAndAddress(final ServerSocketChannel serverSocketChannel, final SocketAddress socketAddress, final int backlog)
            throws IOException {
        TCPNIOServerConnection serverConnection = null;

        final Lock lock = tcpTransport.getState().getStateLocker().writeLock();
        lock.lock();
        try {

            final ServerSocket serverSocket = serverSocketChannel.socket();

            tcpTransport.getChannelConfigurator().preConfigure(transport, serverSocketChannel);

            if (socketAddress != null) {
                try {
                    serverSocket.bind(socketAddress, backlog);
                } catch (BindException e) {
                    // The JDK 25 BindException still doesn't tell which address we tried to use.
                    BindException be = new BindException("Cannot bind to requested socket address " + socketAddress);
                    be.initCause(e);
                    throw be;
                }
            }

            tcpTransport.getChannelConfigurator().postConfigure(transport, serverSocketChannel);

            serverConnection = tcpTransport.obtainServerNIOConnection(serverSocketChannel);
            serverConnection.setProcessor(getProcessor());
            serverConnection.setProcessorSelector(getProcessorSelector());
            tcpTransport.serverConnections.add(serverConnection);
            serverConnection.resetProperties();

            if (!tcpTransport.isStopped()) {
                tcpTransport.listenServerConnection(serverConnection);
            }

            return serverConnection;
        } catch (Exception e) {
            if (serverConnection != null) {
                tcpTransport.serverConnections.remove(serverConnection);

                serverConnection.closeSilently();
            } else {
                try {
                    serverSocketChannel.close();
                } catch (IOException ignored) {
                    e.addSuppressed(ignored);
                }
            }

            throw Exceptions.makeIOException(e);
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------------------------------------- Inner Classes

    public static class Builder extends AbstractBindingHandler.Builder<Builder> {

        private TCPNIOTransport transport;

        public Builder transport(TCPNIOTransport transport) {
            this.transport = transport;
            return this;
        }

        @Override
        public TCPNIOBindingHandler build() {
            return (TCPNIOBindingHandler) super.build();
        }

        @Override
        protected AbstractBindingHandler create() {
            if (transport == null) {
                throw new IllegalStateException("Unable to create TCPNIOBindingHandler - transport is null");
            }
            return new TCPNIOBindingHandler(transport);
        }

    } // END Builder

}
