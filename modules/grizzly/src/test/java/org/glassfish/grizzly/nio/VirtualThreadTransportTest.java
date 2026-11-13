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
package org.glassfish.grizzly.nio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.nio.transport.TCPNIOServerConnection;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.VirtualThreadExecutorService;
import org.glassfish.grizzly.utils.EchoFilter;
import org.junit.Test;

/**
 * End to end: a TCP transport that runs its I/O events on virtual threads, so that reads and writes use the pool of the
 * {@link org.glassfish.grizzly.nio.DirectByteBufferRecord}s of virtual threads, echoes messages of several sizes for
 * several concurrent connections.
 */
public class VirtualThreadTransportTest {

    // from one byte to a message that takes many reads and writes
    private static final int[] SIZES = { 1, 1000, 8192, 70000, 3 * 1024 * 1024 + 7 };
    private static final int CONNECTIONS = 8;

    @Test
    public void echoOnVirtualThreads() throws Exception {
        assumeTrue(DirectByteBufferRecord.virtualPoolsCapacity() > 0);
        DirectByteBufferRecord.drainForTests();
        final AtomicInteger virtualReads = new AtomicInteger();
        final VirtualThreadExecutorService workers = VirtualThreadExecutorService.createInstance();
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().setIOStrategy(WorkerThreadIOStrategy.getInstance()).build();
        transport.setWorkerThreadPool(workers);
        // small socket buffer based sizes keep the records under the pool limit of 1 MiB whatever the host defaults are,
        // so that records are pooled and the assertion on pooledVirtualRecords() below holds everywhere
        transport.setReadBufferSize(64 * 1024);
        transport.setWriteBufferSize(64 * 1024);
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(new EchoFilter() {
            @Override
            public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                if (Thread.currentThread().isVirtual()) {
                    virtualReads.incrementAndGet();
                }
                return super.handleRead(ctx);
            }
        }).build());

        final ExecutorService clients = Executors.newFixedThreadPool(CONNECTIONS);
        try {
            // port 0: the system picks a free port, so that nobody can take it between the lookup and the bind
            final TCPNIOServerConnection server = transport.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            transport.start();

            final List<Future<?>> done = new ArrayList<>();
            for (int c = 0; c < CONNECTIONS; c++) {
                final int seed = c;
                done.add(clients.submit(() -> {
                    echo(port, seed);
                    return null;
                }));
            }
            for (final Future<?> f : done) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            clients.shutdownNow();
            transport.shutdownNow();
            workers.shutdownNow();
        }
        assertTrue("the events ran on virtual threads", virtualReads.get() > 0);
        // the reads and writes of the virtual threads took records from the pool and gave them back
        assertTrue("records pooled after the echo: " + DirectByteBufferRecord.pooledVirtualRecords(),
                DirectByteBufferRecord.pooledVirtualRecords() > 0);
    }

    private static void echo(final int port, final int seed) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(60000);
            final DataInputStream in = new DataInputStream(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            for (final int size : SIZES) {
                final byte[] message = new byte[size];
                new Random(seed * 31L + size).nextBytes(message);
                // written by another thread, so that a large message cannot block the read of the echo
                final Throwable[] writeError = new Throwable[1];
                final Thread writer = new Thread(() -> {
                    try {
                        out.write(message);
                        out.flush();
                    } catch (final Throwable t) {
                        writeError[0] = t;
                    }
                });
                writer.setDaemon(true);
                writer.start();
                final byte[] echoed = new byte[size];
                in.readFully(echoed);
                writer.join(60000);
                assertFalse("the writer of " + size + " bytes did not finish", writer.isAlive());
                if (writeError[0] != null) {
                    throw new IllegalStateException(writeError[0]);
                }
                assertArrayEquals("echo of " + size + " bytes on connection " + seed, message, echoed);
            }
        }
    }
}
