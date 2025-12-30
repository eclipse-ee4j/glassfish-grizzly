/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly.filterchain;

import java.io.IOException;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Transport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.UDPNIOTransport;

/**
 * Transport {@link Filter} implementation, which should work with any {@link Transport}. This {@link Filter} tries to
 * delegate I/O event processing to the {@link Transport}'s specific transport {@link Filter}. If {@link Transport}
 * doesn't have own implementation - uses common I/O event processing logic.
 *
 * <tt>TransportFilter</tt> could be set to work in 2 modes: <code>stream</code> or <code>message</code>. In
 * <code>stream</code> mode, <tt>TransportFilter</tt> produces/consumes the socket channel directly.
 *
 * In <code>message</code> mode, <tt>TransportFilter</tt> represents {@link Connection} data as {@link Buffer}, using
 * {@link FilterChainContext#getMessage()}}, {@link FilterChainContext#setMessage(Object)}.
 *
 * For specific {@link Transport}, one mode could be more preferable than another. For example {@link TCPNIOTransport }
 * works just in <code>stream</code> mode. {@link UDPNIOTransport } prefers <code>message</code> mode, but could also
 * work in <code>stream</code> mode.
 *
 * @author Alexey Stashok
 */
public class TransportFilter extends BaseFilter {

    @SuppressWarnings("UnusedDeclaration")
    public static FilterChainEvent createFlushEvent() {
        return FLUSH_EVENT;
    }

    public static FilterChainEvent createFlushEvent(final CompletionHandler completionHandler) {
        if (completionHandler == null) {
            return FLUSH_EVENT;
        }

        return new FlushEvent(completionHandler);
    }

    public static final class FlushEvent implements FilterChainEvent {
        public static final Object TYPE = FlushEvent.class;

        final CompletionHandler completionHandler;

        private FlushEvent() {
            this(null);
        }

        private FlushEvent(final CompletionHandler completionHandler) {
            this.completionHandler = completionHandler;
        }

        @Override
        public Object type() {
            return TYPE;
        }

        public CompletionHandler getCompletionHandler() {
            return completionHandler;
        }
    }

    /**
     * TransportFilter flush command event
     */
    private static final FlushEvent FLUSH_EVENT = new FlushEvent();

    /**
     * Create <tt>TransportFilter</tt>.
     */
    public TransportFilter() {
    }

    /**
     * Delegates accept operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public NextAction handleAccept(final FilterChainContext ctx) throws IOException {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            return transportFilter0.handleAccept(ctx);
        }

        return null;
    }

    /**
     * Delegates connect operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public NextAction handleConnect(final FilterChainContext ctx) throws IOException {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            return transportFilter0.handleConnect(ctx);
        }

        return null;
    }

    /**
     * Delegates reading operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            return transportFilter0.handleRead(ctx);
        }

        return null;
    }

    /**
     * Delegates writing operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            return transportFilter0.handleWrite(ctx);
        }

        return null;
    }

    /**
     * Delegates event operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public NextAction handleEvent(final FilterChainContext ctx, final FilterChainEvent event) throws IOException {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            return transportFilter0.handleEvent(ctx, event);
        }

        return null;
    }

    /**
     * Delegates close operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public NextAction handleClose(final FilterChainContext ctx) throws IOException {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            return transportFilter0.handleClose(ctx);
        }

        return null;
    }

    /**
     * Delegates exception operation to {@link Transport}'s specific transport filter.
     */
    @Override
    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {

        final Filter transportFilter0 = getTransportFilter0(ctx.getConnection().getTransport());

        if (transportFilter0 != null) {
            transportFilter0.exceptionOccurred(ctx, error);
        }
    }

    /**
     * Get default {@link Transport} specific transport filter.
     *
     * @param transport {@link Transport}.
     *
     * @return default {@link Transport} specific transport filter.
     */
    protected Filter getTransportFilter0(final Transport transport) {
        if (transport instanceof FilterChainEnabledTransport) {
            return ((FilterChainEnabledTransport) transport).getTransportFilter();
        }

        return null;
    }
}
