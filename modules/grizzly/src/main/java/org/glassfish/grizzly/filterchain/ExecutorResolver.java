/*
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
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

/**
 *
 * @author oleksiys
 */
abstract class ExecutorResolver {
    public static final FilterExecutor UPSTREAM_EXECUTOR_SAMPLE = new UpstreamExecutor() {
        @Override
        public NextAction execute(Filter filter, FilterChainContext context) throws IOException {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };

    public static final FilterExecutor DOWNSTREAM_EXECUTOR_SAMPLE = new DownstreamExecutor() {
        @Override
        public NextAction execute(Filter filter, FilterChainContext context) throws IOException {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };

    private static final FilterExecutor CONNECT_EXECUTOR = new UpstreamExecutor() {
        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleConnect(context);
        }
    };

    private static final FilterExecutor CLOSE_EXECUTOR = new UpstreamExecutor() {
        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleClose(context);
        }
    };

    private static final FilterExecutor EVENT_UPSTREAM_EXECUTOR = new UpstreamExecutor() {

        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleEvent(context, context.event);
        }
    };

    private static final FilterExecutor EVENT_DOWNSTREAM_EXECUTOR = new DownstreamExecutor() {

        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleEvent(context, context.event);
        }
    };

    private static final FilterExecutor ACCEPT_EXECUTOR = new UpstreamExecutor() {

        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleAccept(context);
        }
    };

    private static final FilterExecutor WRITE_EXECUTOR = new DownstreamExecutor() {
        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleWrite(context);
        }
    };

    private static final FilterExecutor READ_EXECUTOR = new UpstreamExecutor() {
        @Override
        public NextAction execute(final Filter filter, final FilterChainContext context) throws IOException {
            return filter.handleRead(context);
        }
    };

    public static FilterExecutor resolve(final FilterChainContext context) {
        switch (context.getOperation()) {
        case READ:
            return READ_EXECUTOR;
        case WRITE:
            return WRITE_EXECUTOR;
        case ACCEPT:
            return ACCEPT_EXECUTOR;
        case CLOSE:
            return CLOSE_EXECUTOR;
        case CONNECT:
            return CONNECT_EXECUTOR;
        case EVENT:
            return context.getFilterIdx() == FilterChainContext.NO_FILTER_INDEX || context.getStartIdx() <= context.getEndIdx() ? EVENT_UPSTREAM_EXECUTOR
                    : EVENT_DOWNSTREAM_EXECUTOR;
        default:
            return null;
        }
    }

    /**
     * Executes appropriate {@link Filter} processing method to process occurred {@link org.glassfish.grizzly.IOEvent}.
     */
    public static abstract class UpstreamExecutor implements FilterExecutor {

        @Override
        public final int defaultStartIdx(FilterChainContext context) {
            if (context.getFilterIdx() != FilterChainContext.NO_FILTER_INDEX) {
                return context.getFilterIdx();
            }

            context.setFilterIdx(0);
            return 0;
        }

        @Override
        public final int defaultEndIdx(FilterChainContext context) {
            return context.getFilterChain().size();
        }

        @Override
        public final int getNextFilter(FilterChainContext context) {
            return context.getFilterIdx() + 1;
        }

        @Override
        public final int getPreviousFilter(FilterChainContext context) {
            int filterIndex = context.getFilterIdx();
            return filterIndex == FilterChainContext.NO_FILTER_INDEX ? filterIndex : filterIndex - 1;
        }

        @Override
        public final boolean hasNextFilter(FilterChainContext context, int idx) {
            return idx < context.getFilterChain().size() - 1;
        }

        @Override
        public final boolean hasPreviousFilter(FilterChainContext context, int idx) {
            return idx > 0;
        }

        @Override
        public final void initIndexes(FilterChainContext context) {
            final int startIdx = defaultStartIdx(context);
            context.setStartIdx(startIdx);
            context.setFilterIdx(startIdx);
            context.setEndIdx(defaultEndIdx(context));
        }

        @Override
        public final boolean isUpstream() {
            return true;
        }

        @Override
        public final boolean isDownstream() {
            return false;
        }
    }

    /**
     * Executes appropriate {@link Filter} processing method to process occurred {@link org.glassfish.grizzly.IOEvent}.
     */
    public static abstract class DownstreamExecutor implements FilterExecutor {
        @Override
        public final int defaultStartIdx(FilterChainContext context) {
            if (context.getFilterIdx() != FilterChainContext.NO_FILTER_INDEX) {
                return context.getFilterIdx();
            }

            final int idx = context.getFilterChain().size() - 1;
            context.setFilterIdx(idx);
            return idx;
        }

        @Override
        public final int defaultEndIdx(FilterChainContext context) {
            return -1;
        }

        @Override
        public final int getNextFilter(FilterChainContext context) {
            int filterIndex = context.getFilterIdx();
            return filterIndex == FilterChainContext.NO_FILTER_INDEX ? filterIndex : filterIndex - 1;
        }

        @Override
        public final int getPreviousFilter(FilterChainContext context) {
            return context.getFilterIdx() + 1;
        }

        @Override
        public final boolean hasNextFilter(FilterChainContext context, int idx) {
            return idx > 0;
        }

        @Override
        public final boolean hasPreviousFilter(FilterChainContext context, int idx) {
            return idx < context.getFilterChain().size() - 1;
        }

        @Override
        public final void initIndexes(FilterChainContext context) {
            final int startIdx = defaultStartIdx(context);
            context.setStartIdx(startIdx);
            context.setFilterIdx(startIdx);
            context.setEndIdx(defaultEndIdx(context));
        }

        @Override
        public final boolean isUpstream() {
            return false;
        }

        @Override
        public final boolean isDownstream() {
            return true;
        }
    }
}
