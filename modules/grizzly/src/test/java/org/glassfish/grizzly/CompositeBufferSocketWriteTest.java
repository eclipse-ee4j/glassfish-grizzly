/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.glassfish.grizzly;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;

/**
 * Composite buffers mixing heap and direct components are copied into the thread's direct I/O buffer for the socket
 * write (TCPNIOUtils#fill): the bytes must reach the peer complete and in order, also when that direct buffer is
 * reused across writes.
 */
public class CompositeBufferSocketWriteTest {

    @Test
    public void mixedHeapAndDirectComponentsArriveInOrder() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
            transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).build());
            transport.start();
            try {
                final Connection<?> connection = transport.connect(new InetSocketAddress(server.getInetAddress(), server.getLocalPort()))
                        .get(10, SECONDS);
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(10_000);
                    final DataInputStream in = new DataInputStream(peer.getInputStream());
                    final MemoryManager mm = transport.getMemoryManager();

                    final String[][] layouts = {
                            { "heap:HTTP/1.1 200 OK\r\nContent-Length: 13\r\n\r\n", "heap:Hello, World!" },
                            { "heap:head-", "direct:DIRECT-MIDDLE", "heap:-tail1", "heap:-tail2" },
                            { "direct:direct-first", "heap:then-heap" },
                            { "heap:" + "x".repeat(5000), "heap:" + "y".repeat(7000), "direct:" + "z".repeat(300), "heap:end" },
                            { "heap:short" } };

                    for (int round = 0; round < 3; round++) {
                        for (final String[] layout : layouts) {
                            final CompositeBuffer composite = CompositeBuffer.newBuffer(mm);
                            final ByteArrayOutputStream expected = new ByteArrayOutputStream();
                            for (final String part : layout) {
                                final boolean direct = part.startsWith("direct:");
                                final byte[] bytes = part.substring(part.indexOf(':') + 1).getBytes(StandardCharsets.US_ASCII);
                                expected.write(bytes);
                                if (direct) {
                                    final ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length);
                                    bb.put(bytes).flip();
                                    composite.append(Buffers.wrap(mm, bb));
                                } else {
                                    composite.append(Buffers.wrap(mm, bytes));
                                }
                            }
                            connection.write(composite, null);

                            final byte[] actual = new byte[expected.size()];
                            in.readFully(actual);
                            assertArrayEquals(String.join("|", layout), expected.toByteArray(), actual);
                        }
                    }
                } finally {
                    connection.closeSilently();
                }
            } finally {
                transport.shutdownNow();
            }
        }
    }
}
