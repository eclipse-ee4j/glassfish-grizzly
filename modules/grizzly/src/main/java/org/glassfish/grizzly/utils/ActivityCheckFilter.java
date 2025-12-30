/*
 * Copyright (c) 2009, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.utils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

/**
 * The Filter is responsible for tracking {@link Connection} activity and closing {@link Connection} ones it becomes
 * idle for certain amount of time. Unlike {@link IdleTimeoutFilter}, this Filter assumes {@link Connection} is idle,
 * even if some event is being executed on it, so it really requires some action to be executed on {@link Connection} to
 * reset the timeout.
 *
 * @see IdleTimeoutFilter
 *
 * @author Alexey Stashok
 */
public class ActivityCheckFilter extends BaseFilter {
    private static final Logger LOGGER = Grizzly.logger(ActivityCheckFilter.class);

    public static final String ACTIVE_ATTRIBUTE_NAME = "connection-active-attribute";
    private static final Attribute<ActiveRecord> IDLE_ATTR = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(ACTIVE_ATTRIBUTE_NAME,
            new Supplier<ActiveRecord>() {

                @Override
                public ActiveRecord get() {
                    return new ActiveRecord();
                }
            });

    private final long timeoutMillis;
    private final DelayedExecutor.DelayQueue<Connection> queue;

    // ------------------------------------------------------------ Constructors

    public ActivityCheckFilter(final DelayedExecutor executor, final long timeout, final TimeUnit timeoutUnit) {

        this(executor, timeout, timeoutUnit, null);

    }

    public ActivityCheckFilter(final DelayedExecutor executor, final long timeout, final TimeUnit timeoutUnit, final TimeoutHandler handler) {

        this(executor, new DefaultWorker(handler), timeout, timeoutUnit);

    }

    protected ActivityCheckFilter(final DelayedExecutor executor, final DelayedExecutor.Worker<Connection> worker, final long timeout,
            final TimeUnit timeoutUnit) {

        if (executor == null) {
            throw new IllegalArgumentException("executor cannot be null");
        }

        this.timeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, timeoutUnit);

        queue = executor.createDelayQueue(worker, new Resolver());

    }

    // ----------------------------------------------------- Methods from Filter

    @Override
    public NextAction handleAccept(final FilterChainContext ctx) throws IOException {
        queue.add(ctx.getConnection(), timeoutMillis, TimeUnit.MILLISECONDS);

//        queueAction(ctx);
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleConnect(final FilterChainContext ctx) throws IOException {
        queue.add(ctx.getConnection(), timeoutMillis, TimeUnit.MILLISECONDS);

//        queueAction(ctx);
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
        IDLE_ATTR.get(ctx.getConnection()).timeoutMillis = System.currentTimeMillis() + timeoutMillis;
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
        IDLE_ATTR.get(ctx.getConnection()).timeoutMillis = System.currentTimeMillis() + timeoutMillis;
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleClose(final FilterChainContext ctx) throws IOException {
        queue.remove(ctx.getConnection());
        return ctx.getInvokeAction();
    }

    // ---------------------------------------------------------- Public Methods

    @SuppressWarnings({ "UnusedDeclaration" })
    public static DelayedExecutor createDefaultIdleDelayedExecutor() {

        return createDefaultIdleDelayedExecutor(1000, TimeUnit.MILLISECONDS);

    }

    @SuppressWarnings({ "UnusedDeclaration" })
    public static DelayedExecutor createDefaultIdleDelayedExecutor(final long checkInterval, final TimeUnit checkIntervalUnit) {

        final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r) {
                final Thread newThread = new Thread(r);
                newThread.setName("Grizzly-ActiveTimeoutFilter-IdleCheck");
                newThread.setDaemon(true);
                return newThread;
            }
        });
        return new DelayedExecutor(executor, checkInterval > 0 ? checkInterval : 1000L, checkIntervalUnit != null ? checkIntervalUnit : TimeUnit.MILLISECONDS);

    }

    @SuppressWarnings({ "UnusedDeclaration" })
    public long getTimeout(TimeUnit timeunit) {
        return timeunit.convert(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    // ----------------------------------------------------------- Inner Classes

    public interface TimeoutHandler {

        void onTimeout(final Connection c);

    }

    // ---------------------------------------------------------- Nested Classes

    private static final class Resolver implements DelayedExecutor.Resolver<Connection> {

        @Override
        public boolean removeTimeout(final Connection connection) {
            IDLE_ATTR.get(connection).timeoutMillis = 0;
            return true;
        }

        @Override
        public long getTimeoutMillis(final Connection connection) {
            return IDLE_ATTR.get(connection).timeoutMillis;
        }

        @Override
        public void setTimeoutMillis(final Connection connection, final long timeoutMillis) {
            IDLE_ATTR.get(connection).timeoutMillis = timeoutMillis;
        }

    } // END Resolver

    private static final class ActiveRecord {

        private volatile long timeoutMillis;

    } // END IdleRecord

    private static final class DefaultWorker implements DelayedExecutor.Worker<Connection> {

        private final TimeoutHandler handler;

        // -------------------------------------------------------- Constructors

        DefaultWorker(final TimeoutHandler handler) {

            this.handler = handler;

        }

        // --------------------------------- Methods from DelayedExecutor.Worker

        @Override
        public boolean doWork(final Connection connection) {
            if (handler != null) {
                handler.onTimeout(connection);
            }

            connection.closeSilently();

            return true;
        }

    } // END DefaultWorker

}
