/*
 * Copyright (c) 2022, 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.ByteBufferWrapper;
import org.glassfish.grizzly.nio.AbstractNIOConnectionDistributor;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.NIOTransport;
import org.glassfish.grizzly.nio.RegisterChannelResult;
import org.glassfish.grizzly.nio.SelectorRunner;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOServerConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.streams.StreamReader;
import org.glassfish.grizzly.streams.StreamWriter;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.ClientCheckFilter;
import org.glassfish.grizzly.utils.EchoFilter;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.ParallelWriteFilter;
import org.glassfish.grizzly.utils.RandomDelayOnWriteFilter;
import org.glassfish.grizzly.utils.StringFilter;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static java.lang.Boolean.TRUE;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glassfish.grizzly.IOEvent.READ;
import static org.glassfish.grizzly.IOEvent.SERVER_ACCEPT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit test for {@link TCPNIOTransport}
 *
 * @author Alexey Stashok
 */
public class TCPNIOTransportTest {

    private static final Logger logger = Grizzly.logger(TCPNIOTransportTest.class);

    private TCPNIOTransport transport;
    private Collection<TCPNIOServerConnection> observedServerConnections;

    @Before
    public void setUp() throws Exception {
        ByteBufferWrapper.DEBUG_MODE = true;
        transport = TCPNIOTransportBuilder.newInstance().build();
        observedServerConnections = readServerConnections(transport);
    }


    @After
    public void shutdown() throws Exception {
        if (transport != null) {
            // closes also all connections.
            // but doesn't wait for that.
            transport.shutdownNow();
        }
        if (observedServerConnections != null) {
            observedServerConnections = null;
        }
    }


    @Test
    public void testBindUnbind() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        Connection<?> connection = null;
        try {
            bindToPort(transport, port);

            Future<Connection> future = transport.connect("localhost", port);
            connection = future.get(10, SECONDS);
            assertNotNull(connection);
            connection.closeSilently();
            assertFalse("connection.isOpen", connection.isOpen());

            transport.unbindAll(10, SECONDS);

            future = transport.connect("localhost", port);
            try {
                future.get(10, SECONDS);
                fail("Server connection should be closed!:\n" +
                     describeObservedServerConnections(observedServerConnections));
            } catch (ExecutionException e) {
                assertThat(e.getCause(), CoreMatchers.instanceOf(IOException.class));
            }

            logger.log(Level.INFO, "Binding to port {0}", port);
            TestUtils.retryUntilSuccess(() -> transport.bind(port), ofSeconds(10), ofSeconds(1));

            future = transport.connect("localhost", port);
            connection = future.get(10, SECONDS);
            assertNotNull(connection);
        } finally {
            close(connection);
        }
    }

    @Test
    public void testMultiBind() throws Exception {
        logger.info("Starting test");

        final int port1 = TestUtils.findAvailableTcpPort();
        final int port2 = TestUtils.findAvailableTcpPort();
        Connection<?> connection = null;
        try {
            logger.log(Level.INFO, "Binding to port {0}", port1);
            final Connection<?> serverConnection1 = transport.bind(port1);

            logger.log(Level.INFO, "Binding to port {0}", port2);
            final Connection<?> serverConnection2 = transport.bind(port2);

            transport.start();

            Future<Connection> future = transport.connect("localhost", port1);
            connection = future.get(10, SECONDS);
            assertNotNull(connection);
            close(connection);

            future = transport.connect("localhost", port2);
            connection = future.get(10, SECONDS);
            assertNotNull(connection);
            close(connection);

            transport.unbind(serverConnection1, 10, SECONDS);

            future = transport.connect("localhost", port1);
            try {
                connection = future.get(10, SECONDS);
                close(connection);
                fail("Server connection should be closed!:\n" +
                     describeObservedServerConnections(observedServerConnections));
            } catch (ExecutionException e) {
                assertThat(e.getCause(), CoreMatchers.instanceOf(IOException.class));
            }

            transport.unbind(serverConnection2, 10, SECONDS);
            future = transport.connect("localhost", port2);
            try {
                connection = future.get(10, SECONDS);
                close(connection);
                fail("Server connection should be closed!:\n" +
                     describeObservedServerConnections(observedServerConnections));
            } catch (ExecutionException e) {
                assertThat(e.getCause(), CoreMatchers.instanceOf(IOException.class));
            }
        } finally {
            close(connection);
        }
    }

    @Test
    public void testCloseListeners() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        BlockingQueue<Connection<?>> acceptedQueue = new LinkedTransferQueue<>();
        Connection<?> connectedConnection = null;
        Connection<?> acceptedConnection = null;
        try {
            FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
            filterChainBuilder.add(new TransportFilter());
            filterChainBuilder.add(new BaseFilter() {

                @Override
                public NextAction handleAccept(final FilterChainContext ctx) throws IOException {
                    acceptedQueue.offer(ctx.getConnection());
                    return ctx.getInvokeAction();
                }
            });

            transport.setProcessor(filterChainBuilder.build());

            bindToPort(transport, port);

            Future<Connection> connectFuture = transport.connect(new InetSocketAddress("localhost", port));
            connectedConnection = connectFuture.get(10, SECONDS);
            acceptedConnection = acceptedQueue.poll(10, SECONDS);

            FutureImpl<Boolean> connectedCloseFuture = new SafeFutureImpl<>();
            FutureImpl<Boolean> acceptedCloseFuture = new SafeFutureImpl<>();

            // noinspection deprecation
            connectedConnection.addCloseListener(new GenericCloseListener() {

                @Override
                public void onClosed(Closeable closeable, CloseType type) throws IOException {
                    connectedCloseFuture.result(type == CloseType.LOCALLY);
                }
            });

            // noinspection deprecation
            acceptedConnection.addCloseListener(new GenericCloseListener() {

                @Override
                public void onClosed(Closeable closeable, CloseType type) throws IOException {
                    acceptedCloseFuture.result(type == CloseType.REMOTELY);
                }
            });

            connectedConnection.closeSilently();
            assertTrue(connectedCloseFuture.get(10, SECONDS));
            assertTrue(acceptedCloseFuture.get(10, SECONDS));
        } finally {
            close(acceptedConnection);
            close(connectedConnection);
        }
    }

    @Test
    public void testSelectorSwitch() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        CustomChannelDistributor distributor = new CustomChannelDistributor(transport);
        transport.setNIOChannelDistributor(distributor);

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleAccept(FilterChainContext ctx) throws IOException {
                NIOConnection connection = (NIOConnection) ctx.getConnection();

                connection.attachToSelectorRunner(distributor.getSelectorRunner());
                connection.enableIOEvent(READ);

                return ctx.getInvokeAction();
            }
        });
        filterChainBuilder.add(new EchoFilter());
        transport.setProcessor(filterChainBuilder.build());
        transport.setSelectorRunnersCount(4);
        Connection<?> connection = null;
        try {
            bindToPort(transport, port);

            final FutureImpl<Connection> connectFuture = Futures.createSafeFuture();
            transport.connect(new InetSocketAddress("localhost", port),
                Futures.toCompletionHandler(connectFuture, new EmptyCompletionHandler<Connection>() {

                    @Override
                    public void completed(Connection connection) {
                        synchronized (this) {
                            // noinspection deprecation
                            connection.configureStandalone(true);
                        }
                    }
                }));
            connection = connectFuture.get(10, SECONDS);
            assertNotNull(connection);

            connection.configureBlocking(true);

            byte[] originalMessage = "Hello".getBytes();
            StreamWriter writer = StandaloneProcessor.INSTANCE.getStreamWriter(connection);
            writer.writeByteArray(originalMessage);
            Future<Integer> writeFuture = writer.flush();

            assertTrue("Write timeout", writeFuture.isDone());
            assertEquals(originalMessage.length, (int) writeFuture.get());

            StreamReader reader = StandaloneProcessor.INSTANCE.getStreamReader(connection);
            Future<Integer> readFuture = reader.notifyAvailable(originalMessage.length);
            assertNotNull("Read timeout", readFuture.get(10, SECONDS));

            byte[] echoMessage = new byte[originalMessage.length];
            reader.readByteArray(echoMessage);
            assertArrayEquals(echoMessage, originalMessage);
        } finally {
            close(connection);
        }
    }

    @Test
    public void testConnectFutureCancel() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        AtomicInteger serverConnectCounter = new AtomicInteger();
        AtomicInteger serverCloseCounter = new AtomicInteger();

        AtomicInteger clientConnectCounter = new AtomicInteger();
        AtomicInteger clientCloseCounter = new AtomicInteger();

        FilterChainBuilder serverFilterChainBuilder = FilterChainBuilder.stateless().add(new TransportFilter())
            .add(new BaseFilter() {

                @Override
                public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                    serverConnectCounter.incrementAndGet();
                    logger.info("Connecting server: " + serverConnectCounter);
                    return ctx.getInvokeAction();
                }


                @Override
                public NextAction handleClose(FilterChainContext ctx) throws IOException {
                    serverCloseCounter.incrementAndGet();
                    logger.info("Closing server: " + serverCloseCounter);
                    return ctx.getInvokeAction();
                }
            });

        FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless().add(new TransportFilter())
            .add(new BaseFilter() {

                @Override
                public NextAction handleConnect(FilterChainContext ctx) throws IOException {
                    clientConnectCounter.incrementAndGet();
                    logger.info("Connecting client: " + clientConnectCounter);
                    return ctx.getInvokeAction();
                }


                @Override
                public NextAction handleClose(FilterChainContext ctx) throws IOException {
                    clientCloseCounter.incrementAndGet();
                    logger.info("Closing client: " + clientCloseCounter);
                    return ctx.getInvokeAction();
                }
            });

        transport.setProcessor(serverFilterChainBuilder.build());
        SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport)
            .processor(clientFilterChainBuilder.build()).build();
        bindToPort(transport, port);
        for (int i = 0; i < 100; i++) {
            Future<Connection> connectFuture = connectorHandler.connect(new InetSocketAddress("localhost", port));
            Thread.sleep(20);
            if (!connectFuture.cancel(false)) {
                assertTrue("Future.isDone", connectFuture.isDone());
                Connection<?> connection = connectFuture.get();
                assertNotNull("Connection is null?", connection);
                assertTrue("Connection is not connected", connection.isOpen());

                // initiates async closing
                connection.closeSilently();
            }
        }

        Thread.sleep(500);
        assertEquals("Number of connected and closed connections doesn't match", clientConnectCounter.get(),
            clientCloseCounter.get());
    }

    @Test
    public void testParallelWritesBlockingMode() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new RandomDelayOnWriteFilter());
        filterChainBuilder.add(new StringFilter());
        final ExecutorService executorService = Executors.newCachedThreadPool();
        try {
            final int packetsNumber = 10;
            final int size = 1000;
            filterChainBuilder.add(new ParallelWriteFilter(executorService, packetsNumber, size));

            transport.setProcessor(filterChainBuilder.build());
            transport.configureBlocking(true);
            bindToPort(transport, port);

            FutureImpl<Boolean> clientFuture = SafeFutureImpl.create();
            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new StringFilter());

            ClientCheckFilter clientTestFilter = new ClientCheckFilter(clientFuture, packetsNumber, size);
            clientFilterChainBuilder.add(clientTestFilter);

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport)
                .processor(clientFilterChainBuilder.build()).build();

            Future<Connection> future = connectorHandler.connect("localhost", port);
            final Connection<?> connection = future.get(10, SECONDS);
            try {
                assertNotNull(connection);
                connection.write("start");
                Boolean isDone = clientFuture.get(10, SECONDS);
                assertEquals(TRUE, isDone);
            } finally {
                close(connection);
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testThreadInterruptionDuringAcceptDoesNotMakeServerDeaf() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        Field interruptField = TCPNIOServerConnection.class.getDeclaredField("DISABLE_INTERRUPT_CLEAR");
        interruptField.setAccessible(true);
        interruptField.setBoolean(null, true);

        transport.setSelectorRunnersCount(1);
        transport.setKernelThreadPoolConfig(ThreadPoolConfig.defaultConfig().setCorePoolSize(1).setMaxPoolSize(1));
        transport.setIOStrategy(new SameThreadIOStrategyInterruptWrapper(true));

        bindToPort(transport, port);

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setIOStrategy(SameThreadIOStrategy.getInstance());
        try {
            clientTransport.start();
            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(clientTransport)
                    .processor(FilterChainBuilder.stateless().add(new TransportFilter()).build()).build();
            for (int i = 0; i < 10; i++) {
                try {
                    Future<Connection> f2 = connectorHandler.connect("localhost", port);
                    Connection connection = f2.get(5, SECONDS);
                    assertTrue("connection.isOpen", connection.isOpen());
                    close(connection);
                    logger.log(Level.INFO, "Successful connection after {0} unsuccessful attempts.", i);
                    break;
                } catch (Exception e2) {
                    logger.log(Level.INFO, e2 + ": not recovered yet...");
                }
            }
        } finally {
            interruptField.setBoolean(null, false);
            clientTransport.shutdownNow();
        }
    }

    @Test
    public void testThreadInterruptionElsewhereDoesNotMakeServerDeaf() throws Exception {
        logger.info("Starting test");

        final int port = TestUtils.findAvailableTcpPort();
        transport.setSelectorRunnersCount(1);
        transport.setKernelThreadPoolConfig(ThreadPoolConfig.defaultConfig().setCorePoolSize(1).setMaxPoolSize(1));
        transport.setIOStrategy(new SameThreadIOStrategyInterruptWrapper(false));

        bindToPort(transport, port);

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().build();
        clientTransport.setIOStrategy(SameThreadIOStrategy.getInstance());
        try {
            clientTransport.start();
            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(clientTransport)
                    .processor(FilterChainBuilder.stateless().add(new TransportFilter()).build()).build();
            int successfulAttempts = 0;
            for (int i = 0; i < 10; i++) {
                Future<Connection> futureConnection = connectorHandler.connect("localhost", port);
                futureConnection.get(5, SECONDS);
                System.out.println("Successful connection (" + ++successfulAttempts + ").");
            }
        } finally {
            clientTransport.shutdownNow();
        }
    }

    // --------------------------------------------------------- Private Methods

    private static void bindToPort(TCPNIOTransport transport, final int port) throws Exception {
        logger.log(Level.INFO, "Binding to port {0}", port);
        try {
            transport.bind(port);
            transport.start();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "", e);
            throw e;
        }
        logger.log(Level.INFO, "Bound to port {0}", port);
    }


    /**
     * {@link Connection#close()} is asynchronous, so it may still block ports until future
     * finishes.
     *
     * @param connection
     * @throws Exception
     */
    private static void close(Connection<?> connection) throws Exception {
        if (connection == null) {
            return;
        }
        GrizzlyFuture<Closeable> future = connection.close();
        future.get(5, SECONDS);
        assertFalse("connection.isOpen", connection.isOpen());
    }

    private static Collection<TCPNIOServerConnection> readServerConnections(final TCPNIOTransport transport)
            throws IllegalAccessException, NoSuchFieldException {
        final VarHandle handle = MethodHandles.privateLookupIn(TCPNIOTransport.class, MethodHandles.lookup())
                                              .findVarHandle(TCPNIOTransport.class, "serverConnections",
                                                             Collection.class);
        @SuppressWarnings("unchecked")
        final Collection<TCPNIOServerConnection> value =
                (Collection<TCPNIOServerConnection>) handle.get(transport);
        return value;
    }

    private static String describeObservedServerConnections(final Collection<TCPNIOServerConnection> connections) {
        if (connections == null) {
            return "null";
        } else if (connections.isEmpty()) {
            return "empty";
        } else {
            return connections.stream().map(connection -> connection + ", isOpen=" + connection.isOpen())
                              .collect(Collectors.joining(System.lineSeparator()));
        }
    }

    // ---------------------------------------------------------- Nested Classes

    static class SameThreadIOStrategyInterruptWrapper implements IOStrategy {
        private final IOStrategy delegate = SameThreadIOStrategy.getInstance();
        private volatile boolean interruptedOnce = false;
        private final boolean interruptBefore;

        SameThreadIOStrategyInterruptWrapper(boolean interruptBefore) {
            this.interruptBefore = interruptBefore;
        }

        @Override
        public boolean executeIoEvent(Connection connection, IOEvent ioEvent) throws IOException {
            if (interruptBefore) {
                if (!interruptedOnce && ioEvent.equals(SERVER_ACCEPT)) {
                    Thread.currentThread().interrupt();
                    interruptedOnce = true;
                }

                return delegate.executeIoEvent(connection, ioEvent);
            }

            boolean result = delegate.executeIoEvent(connection, ioEvent);
            if (ioEvent.equals(SERVER_ACCEPT)) {
                Thread.currentThread().interrupt();
            }

            return result;
        }

        @Override
        public boolean executeIoEvent(Connection connection, IOEvent ioEvent, boolean isIoEventEnabled) throws IOException {
            return delegate.executeIoEvent(connection, ioEvent, isIoEventEnabled);
        }

        @Override
        public Executor getThreadPoolFor(Connection connection, IOEvent ioEvent) {
            return delegate.getThreadPoolFor(connection, ioEvent);
        }

        @Override
        public ThreadPoolConfig createDefaultWorkerPoolConfig(Transport transport) {
            return delegate.createDefaultWorkerPoolConfig(transport);
        }
    }

    public static class CustomChannelDistributor extends AbstractNIOConnectionDistributor {

        private final AtomicInteger counter;

        public CustomChannelDistributor(final NIOTransport transport) {
            super(transport);
            counter = new AtomicInteger();
        }

        @Override
        public void registerChannel(SelectableChannel channel, int interestOps, Object attachment) throws IOException {
            transport.getSelectorHandler().registerChannel(getSelectorRunner(), channel, interestOps, attachment);
        }

        @Override
        public void registerChannelAsync(SelectableChannel channel, int interestOps, Object attachment,
                CompletionHandler<RegisterChannelResult> completionHandler) {
            transport.getSelectorHandler().registerChannelAsync(getSelectorRunner(), channel, interestOps, attachment, completionHandler);
        }

        @Override
        public void registerServiceChannelAsync(SelectableChannel channel, int interestOps, Object attachment,
                CompletionHandler<RegisterChannelResult> completionHandler) {
            transport.getSelectorHandler().registerChannelAsync(getSelectorRunner(), channel, interestOps, attachment, completionHandler);
        }

        private SelectorRunner getSelectorRunner() {
            SelectorRunner[] runners = getTransportSelectorRunners();
            int index = counter.getAndIncrement() % runners.length;

            return runners[index];
        }
    }
}
