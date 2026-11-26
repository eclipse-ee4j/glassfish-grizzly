/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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
package org.glassfish.grizzly.http2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.DefaultFilterChain;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.memory.MemoryManager;

public class TestUtils {

    private TestUtils() {
        // Prevent instantiation
    }

    /**
     * Repeatedly attempts to execute the given action. If the action throws an exception or assertion error,
     * it pauses and retries until the timeout is reached.
     *
     * @param action the task to execute
     * @param timeout the maximum time to keep retrying
     * @param sleep the duration to wait between attempts
     * @param <T> the type of the result
     * @return the result of the action if it succeeds before the timeout
     * @throws Exception if the timeout is exceeded, the last thrown exception is rethrown
     */
    @SuppressWarnings("SleepWhileInLoop")
    public static <T> T retryUntilSuccess(Callable<T> action, Duration timeout, Duration sleep) throws Exception {
        final long startTime = System.currentTimeMillis();
        Exception lastException = null;
        final long timeoutMillis = timeout.toMillis();
        final long sleepMillis = sleep.toMillis();

        while (System.currentTimeMillis() - startTime <= timeoutMillis) {
            try {
                return action.call();
            } catch (Exception t) {
                lastException = t;
            }
            Thread.sleep(sleepMillis);
        }

        throw lastException;
    }

    public static int findAvailableTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(
                0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * Creates a server {@link Http2Session} over a mocked {@link Connection}, to decode header blocks with
     * {@link DecoderUtils} without a network.
     *
     * @param memoryManager the memory manager of the connection
     * @return the session
     */
    @SuppressWarnings("unchecked")
    static Http2Session newServerSession(final MemoryManager<?> memoryManager) {
        final FilterChain filterChain = new DefaultFilterChain();
        final Filter sessionChainFilter = mock(Filter.class);
        try {
            doAnswer(invocation -> ((FilterChainContext) invocation.getArgument(0)).getInvokeAction()).when(sessionChainFilter)
                    .handleWrite(any(FilterChainContext.class));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        filterChain.add(sessionChainFilter);
        filterChain.add(mock(Filter.class));

        final Connection<?> connection = mock(Connection.class);
        doReturn(filterChain).when(connection).getProcessor();
        doReturn(memoryManager).when(connection).getMemoryManager();
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get()).when(connection).obtainProcessorState(any(Processor.class),
                any(Supplier.class));

        final Http2Session session = new Http2Session(connection, true, new Http2ServerFilter(Http2Configuration.builder().build()));

        final FilterChainContext context = FilterChainContext.create(connection);
        context.getInternalContext().setProcessor(filterChain);
        context.setFilterIdx(1);
        context.setEndIdx(-1);
        session.setupFilterChains(context, false);

        return session;
    }
}
