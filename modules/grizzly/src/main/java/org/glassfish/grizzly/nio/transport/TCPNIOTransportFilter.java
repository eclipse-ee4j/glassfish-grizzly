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

package org.glassfish.grizzly.nio.transport;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.logging.Filter;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.ReadResult;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.asyncqueue.WritableMessage;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.Buffers;

/**
 * The {@link TCPNIOTransport}'s transport {@link Filter} implementation
 *
 * @author Alexey Stashok
 */
public final class TCPNIOTransportFilter extends BaseFilter {
    private final TCPNIOTransport transport;

    TCPNIOTransportFilter(final TCPNIOTransport transport) {
        this.transport = transport;
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
        final TCPNIOConnection connection = (TCPNIOConnection) ctx.getConnection();
        final boolean isBlocking = ctx.getTransportContext().isBlocking();

        final Buffer inBuffer = ctx.getMessage();

        final Buffer buffer;
        if (!isBlocking) {
            buffer = transport.read(connection, inBuffer);
        } else {
            // the peer may wait for writes this thread holds back before it sends what this thread waits for
            connection.flushCorkedWrites();
            GrizzlyFuture<ReadResult<Buffer, SocketAddress>> future = transport.getTemporarySelectorIO().getReader().read(connection, inBuffer);
            try {
                ReadResult<Buffer, SocketAddress> result = future.get();
                buffer = result.getMessage();
                future.recycle(true);
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }

                throw new IOException(cause);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        if (buffer == null || buffer.position() == 0) {
            return ctx.getStopAction();
        } else {
            buffer.trim();

            ctx.setMessage(buffer);
            ctx.setAddressHolder(connection.peerSocketAddressHolder);
        }

        return ctx.getInvokeAction();
    }

    @Override
    @SuppressWarnings("unchecked")
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
        final WritableMessage message = ctx.getMessage();
        if (message != null) {
            ctx.setMessage(null);
            final Connection connection = ctx.getConnection();
            final FilterChainContext.TransportContext transportContext = ctx.getTransportContext();

            final CompletionHandler completionHandler = transportContext.getCompletionHandler();
            final MessageCloner cloner = transportContext.getMessageCloner();

            transportContext.setCompletionHandler(null);
            transportContext.setMessageCloner(null);

            if (!transportContext.isBlocking()) {
                transport.getAsyncQueueIO().getWriter().write(connection, null, message, completionHandler, cloner);
            } else {
                transport.getTemporarySelectorIO().getWriter().write(connection, null, message, completionHandler);
            }
        }

        return ctx.getInvokeAction();
    }

    @Override
    @SuppressWarnings("unchecked")
    public NextAction handleEvent(final FilterChainContext ctx, final FilterChainEvent event) throws IOException {
        if (event.type() == TransportFilter.FlushEvent.TYPE) {
            final Connection connection = ctx.getConnection();
            final FilterChainContext.TransportContext transportContext = ctx.getTransportContext();

            if (transportContext.getCompletionHandler() != null) {
                throw new IllegalStateException("TransportContext CompletionHandler must be null");
            }

            final CompletionHandler completionHandler = ((TransportFilter.FlushEvent) event).getCompletionHandler();

            transport.getWriter(transportContext.isBlocking()).write(connection, Buffers.EMPTY_BUFFER, completionHandler);
        }

        return ctx.getInvokeAction();
    }

    @Override
    public void exceptionOccurred(final FilterChainContext ctx, final Throwable error) {

        final Connection connection = ctx.getConnection();
        if (connection != null) {
            connection.closeSilently();
        }
    }
}
