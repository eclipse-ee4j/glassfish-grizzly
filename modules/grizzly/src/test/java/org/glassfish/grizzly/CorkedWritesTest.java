/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.threadpool.Threads;
import org.junit.Test;

/**
 * Corked writes: an {@link Error} of a completion handler and a blocking write on a selector thread.
 */
public class CorkedWritesTest {

    @Test
    public void errorOfACompletionHandlerKeepsTheWorkerThread() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final AtomicBoolean corked = new AtomicBoolean();
        final CountDownLatch completionHandlerRan = new CountDownLatch(1);
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance()
                .setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().setCorePoolSize(1).setMaxPoolSize(1).setPoolName("corked-writes-worker")).build();
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(new BaseFilter() {
            @Override
            public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                final String in = ((Buffer) ctx.getMessage()).toStringContent(StandardCharsets.US_ASCII);
                final boolean isCorked = ((NIOConnection) ctx.getConnection()).corkWrites();
                final Buffer reply = Buffers.wrap(transport.getMemoryManager(), "reply");
                if (in.startsWith("boom")) {
                    corked.set(isCorked);
                    ctx.write(reply, new EmptyCompletionHandler<WriteResult>() {
                        @Override
                        public void completed(final WriteResult result) {
                            completionHandlerRan.countDown();
                            throw new AssertionError("thrown on purpose");
                        }
                    });
                } else {
                    ctx.write(reply);
                }
                return ctx.getStopAction();
            }
        }).build());
        transport.bind(port);
        transport.start();
        try {
            exchange(port, "boom");
            assertTrue("the connection must be corked", corked.get());
            assertTrue("the completion handler must have run", completionHandlerRan.await(5, TimeUnit.SECONDS));
            // the only worker thread must have survived to serve the next connection
            assertEquals("reply", exchange(port, "ping"));
        } finally {
            transport.shutdownNow();
        }
    }

    private static String exchange(final int port, final String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final byte[] buf = new byte[5];
            try {
                final int n = socket.getInputStream().readNBytes(buf, 0, 5);
                return new String(buf, 0, n, StandardCharsets.US_ASCII);
            } catch (SocketTimeoutException e) {
                return "<timeout>";
            }
        }
    }

    @Test
    public void blockingWriteOnASelectorThreadCopiesTheBuffer() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final int bigSize = 8 * 1024 * 1024;
        final int smallSize = 1000;
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().setIOStrategy(SameThreadIOStrategy.getInstance()).build();
        transport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(AsyncQueueWriter.UNLIMITED_SIZE);
        final CountDownLatch handled = new CountDownLatch(1);
        final AtomicBoolean onSelectorThread = new AtomicBoolean();
        final AtomicBoolean corked = new AtomicBoolean();
        final AtomicBoolean queued = new AtomicBoolean();
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(new BaseFilter() {
            @Override
            public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                final NIOConnection connection = (NIOConnection) ctx.getConnection();
                corked.set(connection.corkWrites());
                onSelectorThread.set(Threads.isService());
                final byte[] big = new byte[bigSize];
                Arrays.fill(big, (byte) 'A');
                ctx.write(Buffers.wrap(transport.getMemoryManager(), big));
                final byte[] small = new byte[smallSize];
                Arrays.fill(small, (byte) 'B');
                // the blocking write must find data that is still queued
                queued.set(connection.getAsyncWriteQueue().size() > 0);
                ctx.write(Buffers.wrap(transport.getMemoryManager(), small), true);
                // the caller reuses its buffer once the blocking write has returned
                Arrays.fill(small, (byte) 'X');
                handled.countDown();
                return ctx.getStopAction();
            }
        }).build());
        transport.bind(port);
        transport.start();
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(4096);
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(10000);
            final OutputStream out = socket.getOutputStream();
            out.write("go".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertTrue("the handler did not finish", handled.await(10, TimeUnit.SECONDS));
            assertTrue("the connection must be corked", corked.get());
            assertTrue("the handler must run on a selector thread", onSelectorThread.get());
            assertTrue("the first write must still be queued", queued.get());
            final byte[] all = socket.getInputStream().readNBytes(bigSize + smallSize);
            assertEquals(bigSize + smallSize, all.length);
            final byte[] tail = Arrays.copyOfRange(all, bigSize, all.length);
            final byte[] expected = new byte[smallSize];
            Arrays.fill(expected, (byte) 'B');
            assertTrue("the data of the blocking write was overwritten", Arrays.equals(expected, tail));
        } finally {
            transport.shutdownNow();
        }
    }
}
