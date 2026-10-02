/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.asyncqueue.WritableMessage;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.streams.StreamReader;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.EchoFilter;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.StringFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * AsyncWriteQueue tests.
 *
 * @author Alexey Stashok
 * @author Ryan Lubke
 */
@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class AsyncWriteQueueTest {
    private static final Logger LOGGER = Grizzly.logger(AsyncWriteQueueTest.class);

    @Parameters
    public static Collection<Object[]> getOptimizedForMultiplexing() {
        return Arrays.asList(new Object[][] { { Boolean.FALSE }, { Boolean.TRUE } });
    }

    private final boolean isOptimizedForMultiplexing;

    public AsyncWriteQueueTest(boolean isOptimizedForMultiplexing) {
        this.isOptimizedForMultiplexing = isOptimizedForMultiplexing;
    }

    private static TCPNIOTransport createTransport(final boolean isOptimizedForMultiplexing) {

        return TCPNIOTransportBuilder.newInstance().setOptimizedForMultiplexing(isOptimizedForMultiplexing).build();
    }

    @Test
    public void testParallelWrites() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;

        final AtomicInteger serverRcvdMessages = new AtomicInteger();
        final AtomicInteger clientRcvdMessages = new AtomicInteger();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new StringFilter(Charsets.UTF8_CHARSET));
        filterChainBuilder.add(new EchoFilter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                serverRcvdMessages.incrementAndGet();
                return super.handleRead(ctx);
            }
        });

        final TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(port);
            transport.start();

            FilterChain clientFilterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter(Charsets.UTF8_CHARSET))
                    .add(new BaseFilter() {

                        @Override
                        public NextAction handleRead(FilterChainContext ctx) throws IOException {
                            clientRcvdMessages.incrementAndGet();
                            return super.handleRead(ctx);
                        }
                    }).build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();

            Future<Connection> future = connectorHandler.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            final AsyncQueueWriter asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            asyncQueueWriter.setMaxPendingBytesPerConnection(256);
            // A sufficiently large value that does not hit the hard limit.
            asyncQueueWriter.setPendingBytesHardLimitMultiplier(1000);

            final Connection finalConnection = connection;

            final int senderThreads = 16;
            final int packetsCount = 1000;

            Collection<Callable<Object>> sendTasks = new ArrayList<>(senderThreads);
            for (int i = 0; i < senderThreads; i++) {
                final int id = i;
                sendTasks.add(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        final AtomicInteger acceptedPackets = new AtomicInteger();
                        final FutureImpl<Boolean> f = SafeFutureImpl.create();
                        final CompletionHandler<WriteResult> ch = new EmptyCompletionHandler<WriteResult>() {

                            @Override
                            public void failed(Throwable throwable) {
                                f.failure(throwable);
                            }
                        };

                        asyncQueueWriter.notifyWritePossible(finalConnection, new WriteHandler() {

                            @Override
                            public void onWritePossible() throws Exception {
                                final int msgNum = acceptedPackets.incrementAndGet();
                                if (msgNum >= packetsCount) {
                                    if (msgNum == packetsCount) {
                                        f.result(Boolean.TRUE);
                                    }
                                    return;
                                }

                                finalConnection.write("client(" + id + ")-" + msgNum, ch);
                                asyncQueueWriter.notifyWritePossible(finalConnection, this);
                            }

                            @Override
                            public void onError(Throwable t) {
                                f.failure(t);
                            }
                        });

                        return f.get(10, TimeUnit.SECONDS);
                    }
                });
            }

            ExecutorService executorService = Executors.newFixedThreadPool(senderThreads);
            try {
                List<Future<Object>> futures = executorService.invokeAll(sendTasks);

                for (int i = 0; i < futures.size(); i++) {
                    try {
                        assertTrue(Boolean.TRUE.equals(futures.get(i).get(10, TimeUnit.SECONDS)));
                    } catch (TimeoutException e) {
                        fail("Timeout on thread#" + i);
                        throw e;
                    } catch (Throwable e) {
                        fail("Exception on thread#" + i + ": " + e.getMessage());
                        throw e;
                    }
                }
            } finally {
                executorService.shutdown();
            }
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    @Test
    public void testAsyncWriteQueueEcho() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;
        StreamReader reader;

        final int packetNumber = 127;
        final int packetSize = 128000;

        final AtomicInteger serverRcvdBytes = new AtomicInteger();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new EchoFilter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                serverRcvdBytes.addAndGet(((Buffer) ctx.getMessage()).remaining());
                return super.handleRead(ctx);
            }
        });

        TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());

        try {
            final AsyncQueueWriter<SocketAddress> asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            asyncQueueWriter.setMaxPendingBytesPerConnection(-1);

            transport.bind(port);
            transport.start();

            Future<Connection> future = transport.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            reader = ((StandaloneProcessor) connection.getProcessor()).getStreamReader(connection);

            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            final AtomicInteger counter = new AtomicInteger(packetNumber);
            final FutureImpl<Boolean> sentFuture = Futures.createSafeFuture();

            final CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler = new EmptyCompletionHandler<WriteResult<WritableMessage, SocketAddress>>() {
                @Override
                public void completed(WriteResult<WritableMessage, SocketAddress> result) {
                    if (counter.decrementAndGet() == 0) {
                        sentFuture.result(true);
                    }
                }

                @Override
                public void failed(Throwable throwable) {
                    sentFuture.failure(throwable);
                }
            };

            Collection<Callable<Object>> sendTasks = new ArrayList<>(packetNumber + 1);
            for (int i = 0; i < packetNumber; i++) {
                final byte b = (byte) i;
                sendTasks.add(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        byte[] originalMessage = new byte[packetSize];
                        Arrays.fill(originalMessage, b);
                        Buffer buffer = Buffers.wrap(mm, originalMessage);
                        asyncQueueWriter.write(con, buffer, completionHandler);

                        return null;
                    }
                });
            }

            ExecutorService executorService = Executors.newFixedThreadPool(packetNumber / 10);
            try {
                executorService.invokeAll(sendTasks);
                if (!sentFuture.get(10, TimeUnit.SECONDS)) {
                    assertTrue("Send timeout!", false);
                }
            } finally {
                executorService.shutdown();
            }

            int responseSize = packetNumber * packetSize;
            Future<Integer> readFuture = reader.notifyAvailable(responseSize);
            Integer available = null;

            try {
                available = readFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "read error", e);
            }

            assertTrue("Read timeout. Server received: " + serverRcvdBytes.get() + " bytes. Expected: " + packetNumber * packetSize, available != null);

            byte[] echoMessage = new byte[responseSize];
            reader.readByteArray(echoMessage);

            // Check interleaving...

            boolean[] isByteUsed = new boolean[packetNumber];
            int offset = 0;
            for (int i = 0; i < packetNumber; i++) {
                byte pattern = echoMessage[offset];

                assertEquals("Pattern: " + pattern + " was already used", false, isByteUsed[pattern]);

                isByteUsed[pattern] = true;
                for (int j = 0; j < packetSize; j++) {
                    byte check = echoMessage[offset++];
                    assertEquals("Echo doesn't match. Offset: " + offset + " pattern: " + pattern + " found: " + check, pattern, check);
                }
            }

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    @Test
    public void testQueueNotification() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;
        final int packetSize = 256000;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());

        final TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());

        try {
            final AsyncQueueWriter asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            asyncQueueWriter.setMaxPendingBytesPerConnection(256000 * 10);
            System.out.println("Max Space: " + asyncQueueWriter.getMaxPendingBytesPerConnection());

            transport.bind(port);
            transport.start();

            Future<Connection> future = transport.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);
            connection.configureStandalone(true);

            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            transport.pause();
            final TaskQueue tqueue = ((NIOConnection) connection).getAsyncWriteQueue();

            do {
                byte[] originalMessage = new byte[packetSize];
                Arrays.fill(originalMessage, (byte) 1);
                Buffer buffer = Buffers.wrap(mm, originalMessage);
                try {
                    if (asyncQueueWriter.canWrite(con)) {
                        asyncQueueWriter.write(con, buffer);
                    }
                } catch (IOException e) {
                    assertTrue("IOException occurred: " + e.toString(), false);
                }
            } while (asyncQueueWriter.canWrite(con)); // fill the buffer

            // out of space. Add a monitor to be notified when space is available
            tqueue.notifyWritePossible(new WriteQueueHandler(con));

            transport.resume(); // resume the transport so bytes start draining from the queue

            long start = 0;
            try {
                System.out.println("Waiting for free space notification.  Max wait time is 10000ms.");
                start = System.currentTimeMillis();
                Thread.sleep(10000); // should be interrupted before time completes
                fail("Thread not interrupted within 10 seconds.");
            } catch (InterruptedException ie) {
                long result = System.currentTimeMillis() - start;
                System.out.println("Notified in " + result + "ms");
            }
            assertTrue(tqueue.spaceInBytes() < asyncQueueWriter.getMaxPendingBytesPerConnection());
            System.out.println("Queue Space: " + tqueue.spaceInBytes());

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }
            if (transport.isPaused()) {
                transport.resume();
            }
            transport.shutdownNow();
        }
    }

    @Test
    public void testAsyncWriteQueueReentrants() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;

        final AtomicInteger serverRcvdBytes = new AtomicInteger();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                serverRcvdBytes.addAndGet(((Buffer) ctx.getMessage()).remaining());
                return ctx.getStopAction();
            }
        });

        // reentrants limitation is applicable for non-multiplexing-optimized write queue
        TCPNIOTransport transport = createTransport(false);
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(port);
            transport.start();

            Future<Connection> future = transport.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            final AsyncQueueWriter<SocketAddress> asyncQueueWriter = transport.getAsyncQueueIO().getWriter();

            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            final int maxAllowedReentrants = Writer.Reentrant.getMaxReentrants();
            final int reentrantsToTest = maxAllowedReentrants * 3;

            final AtomicInteger maxReentrantsNoticed = new AtomicInteger();

            final AtomicInteger packetCounter = new AtomicInteger();

            final FutureImpl<Boolean> resultFuture = SafeFutureImpl.create();

            Buffer buffer = Buffers.EMPTY_BUFFER;

            final ThreadLocal<Integer> reentrantsCounter = new ThreadLocal<Integer>() {
                @Override
                protected Integer initialValue() {
                    return -1;
                }
            };

            reentrantsCounter.set(0);

            try {
                asyncQueueWriter.write(con, buffer, new EmptyCompletionHandler<WriteResult<WritableMessage, SocketAddress>>() {

                    @Override
                    public void completed(WriteResult<WritableMessage, SocketAddress> result) {

                        final int packetNum = packetCounter.incrementAndGet();
                        if (packetNum <= reentrantsToTest) {

                            final int reentrantNum = reentrantsCounter.get() + 1;
                            try {
                                reentrantsCounter.set(reentrantNum);

                                if (reentrantNum > maxReentrantsNoticed.get()) {
                                    maxReentrantsNoticed.set(reentrantNum);
                                }

                                Buffer bufferInner = Buffers.wrap(mm, "" + (char) ('A' + packetNum));
                                asyncQueueWriter.write(con, bufferInner, this);
                            } finally {
                                reentrantsCounter.set(reentrantNum - 1);
                            }

                        } else {
                            resultFuture.result(Boolean.TRUE);
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        resultFuture.failure(throwable);
                    }
                });
            } finally {
                reentrantsCounter.remove();
            }

            assertTrue(resultFuture.get(10, TimeUnit.SECONDS));

            assertTrue("maxReentrantNoticed=" + maxReentrantsNoticed + " maxAllowed=" + maxAllowedReentrants,
                    maxReentrantsNoticed.get() <= maxAllowedReentrants);

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    @SuppressWarnings({"rawtypes", "deprecation"})
    @Test
    public void testPendingBytesHardLimit() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;
        final int packetSize = 256000;

        final FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        final TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());

        try {
            final AsyncQueueWriter<SocketAddress> asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            asyncQueueWriter.setMaxPendingBytesPerConnection(packetSize * 10);
            asyncQueueWriter.setPendingBytesHardLimitMultiplier(4);
            final int softLimit = asyncQueueWriter.getMaxPendingBytesPerConnection();
            final long hardLimit = (long) softLimit * asyncQueueWriter.getPendingBytesHardLimitMultiplier();
            System.out.println("Soft limit Space: " + softLimit);
            System.out.println("Hard limit Space: " + hardLimit);

            transport.bind(port);
            transport.start();

            final Future<Connection> future = transport.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertNotNull(connection);
            connection.configureStandalone(true);

            transport.pause();

            final MemoryManager mm = transport.getMemoryManager();
            final TaskQueue tqueue = ((NIOConnection) connection).getAsyncWriteQueue();
            while (asyncQueueWriter.canWrite(connection)) {
                final byte[] originalMessage = new byte[packetSize];
                Arrays.fill(originalMessage, (byte) 1);
                final Buffer buffer = Buffers.wrap(mm, originalMessage);
                try {
                    asyncQueueWriter.write(connection, buffer);
                } catch (IOException e) {
                    fail("IOException occurred: " + e);
                }
            }
            // The final write will likely have already exceeded the soft limit.
            assertTrue(tqueue.spaceInBytes() >= softLimit);
            assertTrue(connection.isOpen());

            // attempts to write right up to the point of reaching the hard limit for space.
            while (tqueue.spaceInBytes() + packetSize <= hardLimit) {
                final byte[] originalMessage = new byte[packetSize];
                Arrays.fill(originalMessage, (byte) 1);
                final Buffer buffer = Buffers.wrap(mm, originalMessage);
                assertFalse(asyncQueueWriter.canWrite(connection));
                try {
                    asyncQueueWriter.write(connection, buffer);
                } catch (IOException e) {
                    fail("IOException occurred: " + e);
                }
            }
            assertTrue(connection.isOpen());

            // writes beyond the hard limit for space.
            final byte[] originalMessage = new byte[packetSize];
            Arrays.fill(originalMessage, (byte) 1);
            final Buffer buffer = Buffers.wrap(mm, originalMessage);
            final FutureImpl<Boolean> f = SafeFutureImpl.create();
            asyncQueueWriter.write(connection, buffer, new EmptyCompletionHandler<>() {

                @Override
                public void completed(WriteResult result) {
                    f.result(Boolean.TRUE);
                }

                @Override
                public void failed(Throwable throwable) {
                    f.failure(throwable);
                }
            });
            try {
                f.get(10, TimeUnit.SECONDS);
                fail();
            } catch (InterruptedException | TimeoutException e) {
                fail();
            } catch (ExecutionException e) {
                System.out.println(e.getCause().toString());
            }
            // The connection should be closed when attempting a write operation that exceeds the hard limit for space.
            assertFalse(connection.isOpen());
            // the space must be maintained so as not to exceed the hard limit.
            assertTrue(tqueue.spaceInBytes() <= hardLimit);
            System.out.println("Queue Space: " + tqueue.spaceInBytes());

            transport.resume();
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }
            if (transport.isPaused()) {
                transport.resume();
            }
            transport.shutdownNow();
        }
    }

    // ---------------------------------------------------------- Nested Classes

    private static class WriteQueueHandler implements WriteHandler {

        private final Transport transport;
        private final Thread current;

        // -------------------------------------------------------- Constructors

        public WriteQueueHandler(final Connection c) {
            transport = c.getTransport();
            current = Thread.currentThread();
        }

        // -------------------------------------- Methods from WriteHandler

        @Override
        public void onWritePossible() throws Exception {
            transport.pause(); // prevent more writes
            current.interrupt(); // wake up the test thread
        }

        @Override
        public void onError(Throwable t) {
        }

    } // END WriteQueueFreeSpaceMonitor
}
