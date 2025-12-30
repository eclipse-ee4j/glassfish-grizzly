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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import java.util.function.Supplier;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

/**
 * The Filter is responsible for tracking {@link Connection} activity and closing {@link Connection} once it becomes
 * idle for certain amount of time. Unlike {@link ActivityCheckFilter}, this Filter assumes {@link Connection} is idle,
 * when no event is being executed on it. But if some event processing was suspended - this Filter still assumes
 * {@link Connection} is active.
 *
 * @see ActivityCheckFilter
 *
 * @author Alexey Stashok
 */
public class IdleTimeoutFilter extends BaseFilter {

    public static final Long FOREVER = Long.MAX_VALUE;
    public static final Long FOREVER_SPECIAL = FOREVER - 1;

    public static final String IDLE_ATTRIBUTE_NAME = "connection-idle-attribute";
    private static final Attribute<IdleRecord> IDLE_ATTR = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(IDLE_ATTRIBUTE_NAME,
            new Supplier<IdleRecord>() {

                @Override
                public IdleRecord get() {
                    return new IdleRecord();
                }
            });

    private final TimeoutResolver timeoutResolver;
    private final DelayedExecutor.DelayQueue<Connection> queue;
    private final DelayedExecutor.Resolver<Connection> resolver;

    private final FilterChainContext.CompletionListener contextCompletionListener = new ContextCompletionListener();

    // ------------------------------------------------------------ Constructors

    public IdleTimeoutFilter(final DelayedExecutor executor, final long timeout, final TimeUnit timeoutUnit) {

        this(executor, timeout, timeoutUnit, null);

    }

    @SuppressWarnings("UnusedDeclaration")
    public IdleTimeoutFilter(final DelayedExecutor executor, final TimeoutResolver timeoutResolver) {
        this(executor, timeoutResolver, null);
    }

    public IdleTimeoutFilter(final DelayedExecutor executor, final long timeout, final TimeUnit timeUnit, final TimeoutHandler handler) {

        this(executor, new DefaultWorker(handler), new IdleTimeoutResolver(convertToMillis(timeout, timeUnit)));
    }

    public IdleTimeoutFilter(final DelayedExecutor executor, final TimeoutResolver timeoutResolver, final TimeoutHandler handler) {

        this(executor, new DefaultWorker(handler), timeoutResolver);
    }

    protected IdleTimeoutFilter(final DelayedExecutor executor, final DelayedExecutor.Worker<Connection> worker, final TimeoutResolver timeoutResolver) {

        if (executor == null) {
            throw new IllegalArgumentException("executor cannot be null");
        }

        this.timeoutResolver = timeoutResolver;
        resolver = new Resolver();
        queue = executor.createDelayQueue(worker, resolver);

    }

    // ----------------------------------------------------- Methods from Filter

    @Override
    public NextAction handleAccept(final FilterChainContext ctx) throws IOException {
        queue.add(ctx.getConnection(), FOREVER, TimeUnit.MILLISECONDS);

        queueAction(ctx);
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleConnect(final FilterChainContext ctx) throws IOException {
        queue.add(ctx.getConnection(), FOREVER, TimeUnit.MILLISECONDS);

        queueAction(ctx);
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
        queueAction(ctx);
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
        queueAction(ctx);
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleClose(final FilterChainContext ctx) throws IOException {
        queue.remove(ctx.getConnection());
        return ctx.getInvokeAction();
    }

    // ---------------------------------------------------------- Public Methods

    @SuppressWarnings("UnusedDeclaration")
    public DelayedExecutor.Resolver<Connection> getResolver() {
        return resolver;
    }

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
                newThread.setName("Grizzly-IdleTimeoutFilter-IdleCheck");
                newThread.setDaemon(true);
                return newThread;
            }
        });
        return new DelayedExecutor(executor, checkInterval > 0 ? checkInterval : 1000L, checkIntervalUnit != null ? checkIntervalUnit : TimeUnit.MILLISECONDS);

    }

    /**
     * Provides an override mechanism for the default timeout.
     *
     * @param connection The {@link Connection} which is having the idle detection adjusted.
     * @param timeout the new idle timeout.
     * @param timeunit {@link TimeUnit}.
     */
    public static void setCustomTimeout(final Connection connection, final long timeout, final TimeUnit timeunit) {
        IDLE_ATTR.get(connection).setInitialTimeoutMillis(convertToMillis(timeout, timeunit));
    }

    // ------------------------------------------------------- Protected Methods

    protected void queueAction(final FilterChainContext ctx) {
        final Connection connection = ctx.getConnection();
        final IdleRecord idleRecord = IDLE_ATTR.get(connection);
        if (IdleRecord.counterUpdater.getAndIncrement(idleRecord) == 0) {
            idleRecord.timeoutMillis = FOREVER;
        }

        ctx.addCompletionListener(contextCompletionListener);
    }

    // ------------------------------------------------------- Private Methods

    private static long convertToMillis(final long time, final TimeUnit timeUnit) {
        return time >= 0 ? TimeUnit.MILLISECONDS.convert(time, timeUnit) : FOREVER;
    }

    // ----------------------------------------------------------- Inner Classes

    public interface TimeoutHandler {

        void onTimeout(final Connection c);

    }

    public interface TimeoutResolver {

        long getTimeout(FilterChainContext ctx);

    }

    private final class ContextCompletionListener implements FilterChainContext.CompletionListener {

        @Override
        public void onComplete(final FilterChainContext ctx) {
            final Connection connection = ctx.getConnection();
            final IdleRecord idleRecord = IDLE_ATTR.get(connection);
            // Small trick to not synchronize this block and queueAction();
            idleRecord.timeoutMillis = FOREVER_SPECIAL;
            if (idleRecord.isClosed || IdleRecord.counterUpdater.decrementAndGet(idleRecord) == 0) {
                final long timeoutToSet;

                // non-volatile isClosed should work ok,
                // because if we race with idleRecord.close(), the logic within close()
                // should guarantee that we either:
                // 1) see isClosed as true, so next CAS will succeed and 0 will be assigned, or
                // 2) we see false, but in that case CAS will fail and timeout (assigned by close()) will remain 0
                if (idleRecord.isClosed) {
                    timeoutToSet = 0;
                    IdleRecord.counterUpdater.set(idleRecord, 0);
                } else {
                    final long timeout = timeoutResolver.getTimeout(ctx);
                    timeoutToSet = timeout == FOREVER ? FOREVER : System.currentTimeMillis() + timeout;
                }

                IdleRecord.timeoutMillisUpdater.compareAndSet(idleRecord, FOREVER_SPECIAL, timeoutToSet);
            }
        }
    } // END ContextCompletionListener

    // ---------------------------------------------------------- Nested Classes

    private static final class IdleTimeoutResolver implements TimeoutResolver {

        private final long defaultTimeoutMillis;
        // -------------------------------------------------------- Constructors

        IdleTimeoutResolver(final long defaultTimeoutMillis) {
            this.defaultTimeoutMillis = defaultTimeoutMillis;
        }

        // ---------------------------------------- Methods from TimeoutResolver

        @Override
        public long getTimeout(final FilterChainContext ctx) {
            return IDLE_ATTR.get(ctx.getConnection()).getInitialTimeoutMillis(defaultTimeoutMillis);
        }
    }

    private static final class Resolver implements DelayedExecutor.Resolver<Connection> {

        @Override
        public boolean removeTimeout(final Connection connection) {
            IDLE_ATTR.get(connection).close();
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

    private static final class IdleRecord {
        private boolean isClosed;
        private volatile boolean isInitialSet;
        private long initialTimeoutMillis;

        private static final AtomicLongFieldUpdater<IdleRecord> timeoutMillisUpdater = AtomicLongFieldUpdater.newUpdater(IdleRecord.class, "timeoutMillis");
        private volatile long timeoutMillis;

        private static final AtomicIntegerFieldUpdater<IdleRecord> counterUpdater = AtomicIntegerFieldUpdater.newUpdater(IdleRecord.class, "counter");
        private volatile int counter;

        private long getInitialTimeoutMillis(final long defaultTimeoutMillis) {
            return isInitialSet ? initialTimeoutMillis : defaultTimeoutMillis;
        }

        private void setInitialTimeoutMillis(final long initialTimeoutMillis) {
            this.initialTimeoutMillis = initialTimeoutMillis;
            isInitialSet = true;
        }

        private void close() {
            isClosed = true;
            timeoutMillis = 0;
        }

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
            if (connection.isOpen()) {
                if (handler != null) {
                    handler.onTimeout(connection);
                }
                connection.closeSilently();
            }

            return true;
        }

    } // END DefaultWorker

}
