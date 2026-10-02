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

import static org.junit.Assert.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.compression.lzma.LZMAFilter;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.ByteBufferManager;
import org.glassfish.grizzly.memory.HeapMemoryManager;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.glassfish.grizzly.utils.DelayFilter;
import org.glassfish.grizzly.utils.EchoFilter;
import org.glassfish.grizzly.utils.StringFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)

public class LZMATest {
    private final MemoryManager manager;

    public LZMATest(MemoryManager manager) {
        this.manager = manager;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getLazySslInit() {
        return Arrays.asList(new Object[][] { { new HeapMemoryManager() }, { new ByteBufferManager() }, });
    }

    @Test
    public void testSimpleEcho() throws Exception {
        doTest("Hello world");
    }

    @Test
    public void test10Echoes() throws Exception {
        String[] array = new String[10];
        for (int i = 0; i < array.length; i++) {
            array[i] = "Hello world #" + i;
        }

        doTest(array);
    }

    @Test
    public void testLargeEcho() throws Exception {
        final int len = 1024 * 256;
        StringBuilder sb = new StringBuilder(len);
        String a = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
        int totalLen = a.length() - 1;
        Random r = new Random(System.currentTimeMillis());
        for (int i = 0; i < len; i++) {
            sb.append(a.charAt(r.nextInt(totalLen)));
        }
        doTest(sb.toString());
    }

    @Test
    public void testChunkedEcho() throws Exception {
        doTest(true, "Hello world");
    }

    @Test
    public void testChunked10Echoes() throws Exception {
        String[] array = new String[10];
        for (int i = 0; i < array.length; i++) {
            array[i] = "Hello world #" + i;
        }

        doTest(true, array);
    }

    // --------------------------------------------------------- Private Methods

    private void doTest(String... messages) throws Exception {
        doTest(false, messages);
    }

    private void doTest(boolean applyChunking, String... messages) throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;

        FilterChainBuilder serverChainBuilder = FilterChainBuilder.stateless();
        serverChainBuilder.add(new TransportFilter());
        if (applyChunking) {
            serverChainBuilder.add(new ChunkingFilter(2));
            serverChainBuilder.add(new DelayFilter(50, 50));
        }

        serverChainBuilder.add(new LZMAFilter());
        serverChainBuilder.add(new StringFilter());
        serverChainBuilder.add(new EchoFilter());

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(serverChainBuilder.build());
        transport.setMemoryManager(manager);
        transport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(-1);

        try {
            transport.bind(port);
            transport.start();

            FutureImpl<Boolean> completeFuture = SafeFutureImpl.create();
            FilterChainBuilder clientChainBuilder = FilterChainBuilder.stateless();
            clientChainBuilder.add(new TransportFilter());
            clientChainBuilder.add(new LZMAFilter());
            clientChainBuilder.add(new StringFilter());
            clientChainBuilder.add(new ClientEchoCheckFilter(completeFuture, messages));

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientChainBuilder.build()).build();

            Future<Connection> future = connectorHandler.connect("localhost", port);

            connection = future.get(120, TimeUnit.SECONDS);
            assertTrue(connection != null);

            assertTrue(completeFuture.get(240, TimeUnit.SECONDS));
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }

    private static final class ClientEchoCheckFilter extends BaseFilter {
        private final String[] messages;
        private final FutureImpl<Boolean> future;

        private final AtomicInteger idx = new AtomicInteger();

        public ClientEchoCheckFilter(FutureImpl<Boolean> future, String... messages) {
            this.messages = messages;
            this.future = future;
        }

        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws IOException {
            ctx.write(messages[idx.get()]);
            return ctx.getStopAction();
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final String echoedMessage = ctx.getMessage();
            final int currentIdx = idx.getAndIncrement();
            final String messageToCompare = messages[currentIdx];
            if (messageToCompare.equals(echoedMessage)) {
                if (currentIdx >= messages.length - 1) {
                    future.result(true);
                } else {
                    ctx.write(messages[currentIdx + 1]);
                }
            } else {
                future.failure(
                        new IllegalStateException("Message #" + currentIdx + " is incorrect. Expected: " + messageToCompare + " received: " + echoedMessage));
            }

            return ctx.getStopAction();
        }

        @Override
        public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
            if (!future.isDone()) {
                future.failure(error);
            }
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx) throws IOException {
            if (!future.isDone()) {
                future.failure(new EOFException("handleClose was called"));
            }

            return ctx.getStopAction();
        }
    }

}
