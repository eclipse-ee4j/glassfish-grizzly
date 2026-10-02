/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.LeaderFollowerNIOStrategy;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.strategies.SimpleDynamicNIOStrategy;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.StringFilter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Basic IOStrategies test.
 *
 * @author Alexey Stashok
 */
@RunWith(Parameterized.class)
@SuppressWarnings("unchecked")
public class IOStrategyTest {
    private static final Logger LOGGER = Grizzly.logger(IOStrategyTest.class);

    private final IOStrategy strategy;

    @Parameters
    public static Collection<Object[]> getIOStrategy() {
        return Arrays.asList(new Object[][] { { WorkerThreadIOStrategy.getInstance() }, { LeaderFollowerNIOStrategy.getInstance() },
                { SameThreadIOStrategy.getInstance() }, { SimpleDynamicNIOStrategy.getInstance() } });
    }

    @Before
    public void before() throws Exception {
        Grizzly.setTrackingThreadCache(true);
    }

    public IOStrategyTest(final IOStrategy strategy) {
        this.strategy = strategy;
    }

    @Test
    public void testSimplePackets() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final Integer msgNum = 200;
        final String pattern = "Message #";
        final int clientsNum = Runtime.getRuntime().availableProcessors() * 16;
        final EchoFilter serverEchoFilter = new EchoFilter(pattern);

        Connection connection = null;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new StringFilter(Charsets.UTF8_CHARSET));
        filterChainBuilder.add(serverEchoFilter);
        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().setIOStrategy(strategy)
                .setMaxAsyncWriteQueueSizeInBytes(AsyncQueueWriter.UNLIMITED_SIZE).build();
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(port);
            transport.start();

            for (int i = 0; i < clientsNum; i++) {
                serverEchoFilter.reset();

                final FutureImpl<Integer> resultEcho = SafeFutureImpl.create();
                FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
                clientFilterChainBuilder.add(new TransportFilter());
                clientFilterChainBuilder.add(new StringFilter(Charsets.UTF8_CHARSET));

                final EchoResultFilter echoResultFilter = new EchoResultFilter(msgNum, pattern, resultEcho);
                clientFilterChainBuilder.add(echoResultFilter);

                final FilterChain clientChain = clientFilterChainBuilder.build();

                SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChain).build();

                Future<Connection> connectFuture = connectorHandler.connect(new InetSocketAddress("localhost", port));

                connection = connectFuture.get(10, TimeUnit.SECONDS);
                assertTrue(connection != null);

                for (int j = 0; j < msgNum; j++) {
                    final int num = j;

                    connection.write(pattern + j, new EmptyCompletionHandler<WriteResult>() {
                        @Override
                        public void failed(Throwable throwable) {
                            LOGGER.log(Level.WARNING, "connection.write(...) failed. Index=" + num, throwable);
                        }
                    });
                }

                try {
                    final Integer result = resultEcho.get(60, TimeUnit.SECONDS);
                    assertEquals(msgNum, result);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Unexpected error strategy: " + strategy.getClass().getName() + ". counter=" + echoResultFilter.counter.get(), e);
                }

                connection.closeSilently();
                connection = null;
            }

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    private static final class EchoResultFilter extends BaseFilter {
        // handleReads should be executed synchronously, so plain "int" is ok
        private final AtomicInteger counter = new AtomicInteger();

        private final int msgNum;
        private final String pattern;
        private final FutureImpl<Integer> resultFuture;

        private EchoResultFilter(Integer msgNum, String pattern, FutureImpl<Integer> resultFuture) {
            this.msgNum = msgNum;
            this.pattern = pattern;
            this.resultFuture = resultFuture;
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final String msg = ctx.getMessage();
            final int count = counter.getAndIncrement();
            final String check = pattern + count;

            if (!check.equals(msg)) {
                resultFuture.failure(new IllegalStateException("Client ResultFilter: unexpected echo came: " + msg + ". Expected response: " + check));
                return ctx.getStopAction();
            }

            if (count == msgNum - 1) {
                resultFuture.result(msgNum);
            }

            return ctx.getStopAction();
        }
    }

    private static final class EchoFilter extends BaseFilter {
        // handleReads should be executed synchronously, so plain "int" is ok
        private final AtomicInteger counter = new AtomicInteger();

        private final String pattern;

        private EchoFilter(String pattern) {
            this.pattern = pattern;
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final String msg = ctx.getMessage();
            final int count = counter.getAndIncrement();
            final String check = pattern + count;

            if (!check.equals(msg)) {
                LOGGER.log(Level.WARNING, "Server EchoFilter: unexpected message came: {0}. Expected response: {1}", new Object[] { msg, check });
            }

            ctx.write(msg);

            return ctx.getStopAction();
        }

        private void reset() {
            counter.set(0);
        }
    }

}
