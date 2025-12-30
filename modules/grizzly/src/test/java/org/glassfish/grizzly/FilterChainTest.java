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

package org.glassfish.grizzly;

import static java.lang.Boolean.TRUE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glassfish.grizzly.Grizzly.DEFAULT_ATTRIBUTE_BUILDER;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.function.Supplier;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.EchoFilter;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.StringFilter;

import junit.framework.TestCase;

/**
 * Test general {@link FilterChain} functionality.
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class FilterChainTest extends TestCase {
    private static int PORT = PORT();
    
    static int PORT() {
        try {
            int port = 7788 + SecureRandom.getInstanceStrong().nextInt(1000);
            System.out.println("Using port: " + port);
            return port;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Attribute<AtomicInteger> counterAttr = DEFAULT_ATTRIBUTE_BUILDER.createAttribute(FilterChainTest.class.getName() + ".counter");

    private static Attribute<CompositeBuffer> bufferAttr = DEFAULT_ATTRIBUTE_BUILDER.createAttribute(FilterChainTest.class.getName() + ".buffer",
            new Supplier<CompositeBuffer>() {

                @Override
                public CompositeBuffer get() {
                    return CompositeBuffer.newBuffer();
                }
            });

    private static FilterChainEvent INC_EVENT = new FilterChainEvent() {
        @Override
        public Object type() {
            return "INC_EVENT";
        }
    };

    private static FilterChainEvent DEC_EVENT = new FilterChainEvent() {
        @Override
        public Object type() {
            return "DEC_EVENT";
        }
    };

    public void testInvokeActionAndIncompleteChunk() throws Exception {
        int expectedCommandsCount = 300;

        BlockingQueue<String> intermResultQueue = new LinkedTransferQueue<>();

        Connection connection = null;

        StringFilter stringFilter = new StringFilter();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(stringFilter);
        filterChainBuilder.add(new BaseFilter() { // Batch filter
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                String incompleteCommand = null;
                String message = ctx.getMessage();
                String[] commands = message.split("\n");

                if (!message.endsWith("\n")) {
                    incompleteCommand = commands[commands.length - 1];
                    commands = Arrays.copyOf(commands, commands.length - 1);
                }

                ctx.setMessage(commands);
                return ctx.getInvokeAction(incompleteCommand, new Appender<String>() {
                    @Override
                    public String append(String element1, String element2) {
                        return element1 + element2;
                    }
                });
            }
        });
        filterChainBuilder.add(new BaseFilter() { // Result filter
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                String[] messages = ctx.getMessage();

                intermResultQueue.addAll(Arrays.asList(messages));

                return ctx.getStopAction();
            }
        });

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChainBuilder.build());

        try {
            Thread.sleep(5);
            transport.bind(PORT);
            transport.start();

            FilterChain clientFilterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter()).build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();

            Future<Connection> future = connectorHandler.connect("localhost", PORT);
            connection = future.get(10, SECONDS);
            assertTrue(connection != null);

            String command = "command";
            StringBuilder sb = new StringBuilder(command.length() * expectedCommandsCount * 2);
            for (int i = 0; i < expectedCommandsCount; i++) {
                sb.append(command).append('#').append(i + 1).append(";\n");
            }

            Random r = new Random();
            String commandsString = sb.toString();
            int len = commandsString.length();

            int pos = 0;
            while (pos < len) {
                int bytesToSend = Math.min(len - pos, r.nextInt(command.length() * 4) + 1);
                connection.write(commandsString.substring(pos, pos + bytesToSend));
                pos += bytesToSend;
                Thread.sleep(2);
            }

            for (int i = 0; i < expectedCommandsCount; i++) {
                String rcvdCommand = intermResultQueue.poll(10, SECONDS);
                String expectedCommand = command + '#' + (i + 1) + ';';

                assertEquals(expectedCommand, rcvdCommand);
            }
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public void testEventUpstream() throws Exception {
        Connection connection = new TCPNIOConnection(TCPNIOTransportBuilder.newInstance().build(), null);

        counterAttr.set(connection, new AtomicInteger(0));

        FilterChain chain = FilterChainBuilder.stateless().add(new EventCounterFilter(0)).add(new EventCounterFilter(1)).add(new EventCounterFilter(2))
                .add(new EventCounterFilter(3)).build();

        FutureImpl<FilterChainContext> resultFuture = Futures.createSafeFuture();

        chain.fireEventUpstream(connection, INC_EVENT, Futures.toCompletionHandler(resultFuture));

        resultFuture.get(10, SECONDS);
    }

    public void testEventDownstream() throws Exception {
        Connection connection = new TCPNIOConnection(TCPNIOTransportBuilder.newInstance().build(), null);

        counterAttr.set(connection, new AtomicInteger(3));

        FilterChain chain = FilterChainBuilder.stateless().add(new EventCounterFilter(0)).add(new EventCounterFilter(1)).add(new EventCounterFilter(2))
                .add(new EventCounterFilter(3)).build();

        FutureImpl<FilterChainContext> resultFuture = Futures.createSafeFuture();

        chain.fireEventDownstream(connection, DEC_EVENT, Futures.toCompletionHandler(resultFuture));

        resultFuture.get(10, SECONDS);
    }

    public void testFlush() throws Exception {
        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        MemoryManager mm = transport.getMemoryManager();

        Buffer msg = Buffers.wrap(mm, "Echo this message");
        int msgSize = msg.remaining();

        AtomicInteger serverEchoCounter = new AtomicInteger();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new EchoFilter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                Buffer msg = ctx.getMessage();
                serverEchoCounter.addAndGet(msg.remaining());

                return super.handleRead(ctx);
            }
        });

        transport.setProcessor(filterChainBuilder.build());

        Connection connection = null;

        try {
            Thread.sleep(5);
            transport.bind(PORT);
            transport.start();

            FutureImpl<Integer> resultEcho = SafeFutureImpl.create();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new BufferWriteFilter());
            clientFilterChainBuilder.add(new EchoResultFilter(msgSize, resultEcho));
            FilterChain clientChain = clientFilterChainBuilder.build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChain).build();

            Future<Connection> connectFuture = connectorHandler.connect(new InetSocketAddress("localhost", PORT));

            connection = connectFuture.get(10, SECONDS);
            assertTrue(connection != null);

            connection.write(msg);

            try {
                resultEcho.get(5, SECONDS);
                fail("No message expected");
            } catch (TimeoutException expected) {
            }

            FutureImpl<WriteResult> future = Futures.createSafeFuture();

            clientChain.flush(connection, Futures.toCompletionHandler(future));
            future.get(10, SECONDS);

            assertEquals((Integer) msgSize, resultEcho.get(10, SECONDS));
            assertEquals(msgSize, serverEchoCounter.get());

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public void testWriteCloner() throws Exception {
        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new EchoFilter());

        transport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(-1);

        transport.setProcessor(filterChainBuilder.build());

        Connection connection = null;

        try {
            Thread.sleep(5);
            transport.bind(PORT);
            transport.start();

            FutureImpl<Boolean> resultEcho = SafeFutureImpl.create();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new ClonerTestEchoResultFilter(resultEcho));
            FilterChain clientChain = clientFilterChainBuilder.build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChain).build();

            Future<Connection> connectFuture = connectorHandler.connect(new InetSocketAddress("localhost", PORT));
            connection = connectFuture.get(10, SECONDS);
            assertTrue(connection != null);

            assertTrue(resultEcho.get(10, SECONDS));

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public void testBufferDisposable() throws Exception {
        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        MemoryManager mm = transport.getMemoryManager();

        FutureImpl<Boolean> part1Future = Futures.createSafeFuture();
        FutureImpl<Boolean> part2Future = Futures.createSafeFuture();

        Buffer msg1 = Buffers.wrap(mm, "part1");
        Buffer msg2 = Buffers.wrap(mm, "part2");

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new BufferStateFilter(part1Future, part2Future));
        transport.setProcessor(filterChainBuilder.build());

        Connection connection = null;

        try {
            Thread.sleep(5);
            transport.bind(PORT);
            transport.start();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            FilterChain clientChain = clientFilterChainBuilder.build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChain).build();

            Future<Connection> connectFuture = connectorHandler.connect(new InetSocketAddress("localhost", PORT));
            connection = connectFuture.get(10, SECONDS);

            connection.write(msg1);
            assertTrue("simple buffer is not disposable", part1Future.get(5, SECONDS));

            connection.write(msg2);
            assertTrue("composite buffer is not disposable", part2Future.get(5, SECONDS));

        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.shutdownNow();
        }
    }

    public void testInvokeActionWithRemainder() throws Exception {
        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        MemoryManager mm = transport.getMemoryManager();

        Buffer msg = Buffers.wrap(mm, new byte[] { 0xA });
        int msgSize = msg.remaining();

        AtomicInteger serverEchoCounter = new AtomicInteger();

        Attribute<Integer> invocationCounterAttr = AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("testInvokeActionWithRemainder.counter");

        FilterChain filterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new BaseFilter() {
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                Connection connection = ctx.getConnection();
                Buffer message = ctx.getMessage();

                Integer counter = invocationCounterAttr.get(connection);
                if (counter == null) {
                    invocationCounterAttr.set(connection, 1);
                    assertNotNull(message);
                    ctx.setMessage(null);

                    return ctx.getInvokeAction(message);
                } else if (counter == 1) {
                    invocationCounterAttr.set(connection, 2);
                    assertNotNull(message);

                    return ctx.getInvokeAction();
                }

                fail("unexpected counter value: " + counter);

                return super.handleRead(ctx);
            }
        }).add(new EchoFilter() {
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                Connection connection = ctx.getConnection();
                Buffer message = ctx.getMessage();

                Integer counter = invocationCounterAttr.get(connection);
                if (Integer.valueOf(1).equals(counter)) {
                    assertNull(message);

                    return ctx.getStopAction();
                } else if (Integer.valueOf(2).equals(counter)) {
                    assertNotNull(message);
                    serverEchoCounter.addAndGet(message.remaining());

                    return super.handleRead(ctx);
                }

                fail("unexpected counter value: " + counter);

                return super.handleRead(ctx);
            }
        }).build();

        transport.setProcessor(filterChain);

        Connection connection = null;

        try {
            Thread.sleep(5);
            transport.bind(PORT);
            transport.start();

            FutureImpl<Integer> resultEcho = SafeFutureImpl.create();

            FilterChain clientChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new EchoResultFilter(msgSize, resultEcho)).build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChain).build();

            Future<Connection> connectFuture = connectorHandler.connect(new InetSocketAddress("localhost", PORT));

            connection = connectFuture.get(10, SECONDS);
            assertTrue(connection != null);

            connection.write(msg);

            assertEquals((Integer) msgSize, resultEcho.get(10, SECONDS));
            assertEquals(msgSize, serverEchoCounter.get());

        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    public void testThrowChain() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter()).add(new StringFilter());
        filterChainBuilder.add(new BaseFilter() {
            @Override
            public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
                latch.countDown();
            }
        });
        filterChainBuilder.add(new BaseFilter() {
            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {
                // FilterChainContext's fail() should notify the filters about the error via exceptionOccurred()
                ctx.fail(new IOException("Test exception"));
                return ctx.getStopAction();
            }
        });
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChainBuilder.build());
        Connection connection = null;
        try {
            Thread.sleep(5);
            transport.bind(PORT);
            transport.start();

            final FilterChain clientFilterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new StringFilter()).build();
            final SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();
            final Future<Connection> future = connectorHandler.connect("localhost", PORT);
            connection = future.get(10, SECONDS);
            assertNotNull(connection);
            connection.write("test\n");

            // Give some time for the exception to be processed
            Thread.sleep(500);
            assertTrue(latch.await(1, SECONDS));
            // TransportFilter's exceptionOccurred() will close the connection
            assertFalse(connection.isOpen());
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }
            transport.shutdownNow();
        }
    }

    private static class BufferStateFilter extends BaseFilter {

        private FutureImpl<Boolean> part1Future;
        private FutureImpl<Boolean> part2Future;

        public BufferStateFilter(FutureImpl<Boolean> part1Future, FutureImpl<Boolean> part2Future) {
            this.part1Future = part1Future;
            this.part2Future = part2Future;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            Buffer b = ctx.getMessage();

            if (!part1Future.isDone()) {
                part1Future.result(b.allowBufferDispose());
            } else if (!part2Future.isDone()) {
                part2Future.result(b.isComposite() && b.allowBufferDispose());
            }

            return ctx.getStopAction(b);
        }
    }

    private static class BufferWriteFilter extends BaseFilter {
        @Override
        public NextAction handleWrite(FilterChainContext ctx) throws IOException {
            Connection c = ctx.getConnection();
            Buffer msg = ctx.getMessage();

            CompositeBuffer buffer = bufferAttr.get(c);
            buffer.append(msg);

            return ctx.getStopAction();
        }

        @Override
        public NextAction handleEvent(FilterChainContext ctx, FilterChainEvent event) throws IOException {
            if (event.type() == TransportFilter.FlushEvent.TYPE) {
                Connection c = ctx.getConnection();
                Buffer buffer = bufferAttr.remove(c);

                ctx.write(buffer, new EmptyCompletionHandler<WriteResult>() {

                    @Override
                    public void completed(WriteResult result) {
                        ctx.setFilterIdx(ctx.getFilterIdx() - 1);
                        ctx.resume();
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        ctx.fail(throwable);
                        ctx.completeAndRecycle();
                    }
                });

                return ctx.getSuspendAction();
            }

            return ctx.getInvokeAction();
        }

    }

    private static class EchoResultFilter extends BaseFilter {
        private int size;
        private FutureImpl<Integer> future;

        public EchoResultFilter(int size, FutureImpl<Integer> future) {
            this.size = size;
            this.future = future;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            Buffer msg = ctx.getMessage();
            int msgSize = msg.remaining();

            if (msgSize < size) {
                return ctx.getStopAction(msg);
            } else if (msgSize == size) {
                future.result(size);
                return ctx.getStopAction();
            } else {
                throw new IllegalStateException("Response is bigger than expected. Expected=" + size + " got=" + msgSize);
            }
        }

    }

    private static class ClonerTestEchoResultFilter extends BaseFilter {
        private int msgSize = 8192;
        private volatile int size;
        private FutureImpl<Boolean> future;

        public ClonerTestEchoResultFilter(FutureImpl<Boolean> future) {
            this.future = future;
        }

        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws IOException {

            Connection connection = ctx.getConnection();
            Transport transport = connection.getTransport();

            transport.pause();

            byte[] bytesData = new byte[msgSize];

            AtomicInteger doneFlag = new AtomicInteger(2);
            int counter = 0;

            while (doneFlag.get() != 0) {
                Arrays.fill(bytesData, (byte) (counter++ % 10));
                Buffer b = Buffers.wrap(transport.getMemoryManager(), bytesData);

                ctx.write(null, b, null, new MessageCloner() {

                    @Override
                    public Object clone(Connection connection, Object originalMessage) {
                        Buffer originalBuffer = (Buffer) originalMessage;
                        int remaining = originalBuffer.remaining();

                        Buffer cloneBuffer = connection.getTransport().getMemoryManager().allocate(remaining);
                        cloneBuffer.put(originalBuffer);
                        cloneBuffer.flip();
                        cloneBuffer.allowBufferDispose();

                        doneFlag.decrementAndGet();
                        return cloneBuffer;
                    }
                });

                size += bytesData.length;
            }
            transport.resume();

            return ctx.getInvokeAction();
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            Buffer msg = ctx.getMessage();
            if (msg.remaining() < size) {
                return ctx.getStopAction(msg);
            }

            if (msg.remaining() > size) {
                future.failure(new IllegalStateException("Echoed more bytes than expected"));
            }

            int count = -1;

            for (int i = 0; i < size; i++) {
                if (i % msgSize == 0) {
                    count = (count + 1) % 10;
                }

                if (msg.get(i) != count) {
                    future.failure(new IllegalStateException("Offset " + i + " expected=" + count + " was=" + msg.get(i)));
                }
            }

            future.result(TRUE);

            return ctx.getStopAction();
        }
    }

    private static class EventCounterFilter extends BaseFilter {
        private int checkValue;

        public EventCounterFilter(int checkValue) {
            this.checkValue = checkValue;
        }

        @Override
        public NextAction handleEvent(FilterChainContext ctx, FilterChainEvent event) throws IOException {
            Connection c = ctx.getConnection();
            AtomicInteger ai = counterAttr.get(c);
            int value = ai.get();

            if (event.type() == DEC_EVENT.type()) {
                ai.decrementAndGet();
            } else if (event.type() == INC_EVENT.type()) {
                ai.incrementAndGet();
            } else {
                throw new UnsupportedOperationException("Unsupported event");
            }

            if (value != checkValue) {
                throw new IllegalStateException("Unexpected value. Expected=" + checkValue + " got=" + value);
            }

            return ctx.getInvokeAction();
        }
    }
}
