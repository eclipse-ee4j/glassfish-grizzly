/*
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.portunif;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.attributes.Attribute;
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
import org.glassfish.grizzly.utils.StringFilter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Asynchronous port-unification tests
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class AsyncPUTest {
    public static final int PORT = 17400;
    public static final Charset CHARSET = Charset.forName("UTF-8");

    private static final Logger LOGGER = Grizzly.logger(AsyncPUTest.class);

    private static ScheduledExecutorService tp;

    @BeforeClass
    public static void before() {
        tp = Executors.newScheduledThreadPool(1, new ThreadFactory() {

            @Override
            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            }
        });
    }

    @AfterClass
    public static void after() {
        tp.shutdownNow();
    }

    @Test
    public void asyncTest() throws Exception {
        final String[] protocols = { "X", "Y", "Z" };

        Connection connection = null;

        final PUFilter puFilter = new PUFilter();
        for (final String protocol : protocols) {
            puFilter.register(createProtocol(puFilter, protocol));
        }

        FilterChainBuilder puFilterChainBuilder = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter(CHARSET)).add(puFilter);

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(puFilterChainBuilder.build());

        try {
            transport.bind(PORT);
            transport.start();

            for (final String protocol : protocols) {
                final FutureImpl<Boolean> resultFuture = SafeFutureImpl.create();

                final FilterChain clientFilterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter(CHARSET))
                        .add(new ClientResultFilter(protocol, resultFuture, 1)).build();

                final SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();

                Future<Connection> future = connectorHandler.connect("localhost", PORT);
                connection = future.get(10, TimeUnit.SECONDS);
                assertTrue(connection != null);

                connection.write(protocol);

                assertTrue(resultFuture.get(10, TimeUnit.SECONDS));
            }

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    @Test
    public void asyncWithRemainderTest() throws Exception {
        doAsyncWithRemainder(500, 0);
    }

    @Test
    public void asyncWithRemainder2Test() throws Exception {
        doAsyncWithRemainder(0, 500);
    }

    private void doAsyncWithRemainder(final long scheduleDelayMillis, long exitDelayMillis) throws Exception {
        final String[] protocols = { "X", "Y", "Z" };

        Connection connection = null;

        final PUFilter puFilter = new PUFilter();
        for (final String protocol : protocols) {
            puFilter.register(createProtocol2(puFilter, protocol, 2, scheduleDelayMillis, exitDelayMillis));
        }

        FilterChainBuilder puFilterChainBuilder = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter(CHARSET)).add(puFilter);

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(puFilterChainBuilder.build());

        try {
            transport.bind(PORT);
            transport.start();

            for (final String protocol : protocols) {
                final FutureImpl<Boolean> resultFuture = SafeFutureImpl.create();

                final FilterChain clientFilterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter(CHARSET))
                        .add(new ClientResultFilter(protocol, resultFuture, 2)).build();

                final SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();

                Future<Connection> future = connectorHandler.connect("localhost", PORT);
                connection = future.get(10, TimeUnit.SECONDS);
                assertTrue(connection != null);

                connection.write(protocol);

                assertTrue(resultFuture.get(10, TimeUnit.SECONDS));
            }

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    private PUProtocol createProtocol(final PUFilter puFilter, final String name) {
        final FilterChain chain = puFilter.getPUFilterChainBuilder().add(new SimpleResponseFilter(name, 500, 0)).build();

        return new PUProtocol(new SimpleProtocolFinder(name), chain);
    }

    private PUProtocol createProtocol2(final PUFilter puFilter, final String name, final int duplications, final long scheduleDelayMillis,
            long exitDelayMillis) {
        final FilterChain chain = puFilter.getPUFilterChainBuilder().add(new StringDuplicatorFilter(duplications))
                .add(new SimpleResponseFilter(name, scheduleDelayMillis, exitDelayMillis)).build();

        return new PUProtocol(new SimpleProtocolFinder(name), chain);
    }

    private static final class SimpleProtocolFinder implements ProtocolFinder {
        public final String name;

        public SimpleProtocolFinder(final String name) {
            this.name = name;
        }

        @Override
        public Result find(PUContext puContext, FilterChainContext ctx) {
            final String requestedProtocolName = ctx.getMessage();

            return name.equals(requestedProtocolName) ? Result.FOUND : Result.NOT_FOUND;
        }
    }

    private static final class SimpleResponseFilter extends BaseFilter {
        private final String name;

        private final long scheduleDelayMillis;
        private final long exitDelayMillis;

        public SimpleResponseFilter(String name, long scheduleDelayMillis, long exitDelayMillis) {
            this.name = name;
            this.scheduleDelayMillis = scheduleDelayMillis;
            this.exitDelayMillis = exitDelayMillis;
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            ctx.suspend();
            final NextAction forkAction = ctx.getForkAction();

            tp.schedule(new Runnable() {
                @Override
                public void run() {
                    ctx.write(makeResponseMessage(name));
                    ctx.completeAndRecycle();
                }
            }, scheduleDelayMillis, TimeUnit.MILLISECONDS);

            try {
                Thread.sleep(exitDelayMillis);
            } catch (InterruptedException ignored) {
            }

            return forkAction;
        }
    }

    private static final class ClientResultFilter extends BaseFilter {
        private static final Attribute<AtomicInteger> responseCounterAttr = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
                .createAttribute(ClientResultFilter.class.getName() + ".responseCounter", new Supplier<AtomicInteger>() {

                    @Override
                    public AtomicInteger get() {
                        return new AtomicInteger();
                    }
                });

        private final String expectedResponse;
        private final FutureImpl<Boolean> resultFuture;
        private final int expectedResponseCount;

        public ClientResultFilter(String name, FutureImpl<Boolean> future, int expectedResponseCount) {
            this.resultFuture = future;
            expectedResponse = makeResponseMessage(name);
            this.expectedResponseCount = expectedResponseCount;
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final Connection connection = ctx.getConnection();
            final String response = ctx.getMessage();

            if (expectedResponse.equals(response)) {
                if (responseCounterAttr.get(connection).incrementAndGet() == expectedResponseCount) {
                    resultFuture.result(Boolean.TRUE);
                }
            } else {
                resultFuture.failure(new IllegalStateException("Unexpected response. Expect=" + expectedResponse + " come=" + response));
            }

            return ctx.getStopAction();
        }

    }

    private static class StringDuplicatorFilter extends BaseFilter {
        private static final Attribute<AtomicInteger> duplicationsCounterAttribute = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
                .createAttribute(StringDuplicatorFilter.class.getName() + ".duplicationsCounterAttribute", new Supplier<AtomicInteger>() {

                    @Override
                    public AtomicInteger get() {
                        return new AtomicInteger();
                    }
                });
        private final int duplications;

        private StringDuplicatorFilter(int duplications) {
            this.duplications = duplications;
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final Connection connection = ctx.getConnection();
            final String message = ctx.getMessage();

            if (duplicationsCounterAttribute.get(connection).incrementAndGet() < duplications) {
                return ctx.getInvokeAction(message);
            }

            return ctx.getInvokeAction();
        }

    }

    private static String makeResponseMessage(String protocolName) {
        return "Protocol-" + protocolName;
    }
}
