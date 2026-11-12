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

package org.glassfish.grizzly.filterchain;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;

/**
 * A remainder a filter stored with its stop action survives an empty {@link Buffer} reaching that filter, e.g. from a
 * filter before it that decodes the input and got nothing out of a read.
 */
public class StoredRemainderTest {

    private static final byte EMPTYING = '!';
    private static final byte CLOSING = '#';

    @Test
    public void anEmptyMessageDoesNotReplaceTheStoredRemainder() throws Exception {
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(new EmptyingFilter()).add(new LineFilter()).build());
        try {
            final int port = ((InetSocketAddress) transport.bind(new InetSocketAddress("127.0.0.1", 0)).getLocalAddress()).getPort();
            transport.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10_000);
                final OutputStream out = socket.getOutputStream();
                final InputStream in = socket.getInputStream();

                // one read per chunk: the server acknowledges each read before the next chunk is sent
                send(out, "!"); // nothing stored yet: the line filter gets the empty buffer
                assertEquals(".", read(in, 1));
                send(out, "hel");
                assertEquals(".", read(in, 1));
                send(out, "!");
                assertEquals(".", read(in, 1));
                send(out, "lo\n");
                assertEquals(".hel", read(in, 4));
                assertEquals("lo\n", read(in, 3));
            }
        } finally {
            transport.shutdownNow();
        }
    }

    /**
     * A graceful close writes an empty buffer to the transport's writer, below the filter chain: it completes, after the
     * writes queued before it, while a filter has an incomplete chunk stored.
     */
    @Test
    public void aGracefulCloseCompletesWithAStoredRemainder() throws Exception {
        final EmptyingFilter emptying = new EmptyingFilter();
        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(FilterChainBuilder.stateless().add(new TransportFilter()).add(emptying).add(new LineFilter()).build());
        try {
            final int port = ((InetSocketAddress) transport.bind(new InetSocketAddress("127.0.0.1", 0)).getLocalAddress()).getPort();
            transport.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10_000);
                final OutputStream out = socket.getOutputStream();
                final InputStream in = socket.getInputStream();

                send(out, "hel");
                assertEquals(".", read(in, 1));
                send(out, "#");
                assertEquals("the ack queued before the close", ".", read(in, 1));
                assertEquals("end of stream", -1, in.read());
                emptying.closed.get(10, TimeUnit.SECONDS);
            }
        } finally {
            transport.shutdownNow();
        }
    }

    private static void send(final OutputStream out, final String chunk) throws IOException {
        out.write(chunk.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String read(final InputStream in, final int length) throws IOException {
        return new String(in.readNBytes(length), StandardCharsets.US_ASCII);
    }

    /**
     * Acknowledges every read with a '.', passes a lone '!' on as an empty buffer, and closes the connection gracefully
     * on a lone '#'.
     */
    private static final class EmptyingFilter extends BaseFilter {
        final CompletableFuture<Closeable> closed = new CompletableFuture<>();

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final Buffer input = ctx.getMessage();
            ctx.write(Buffers.wrap(ctx.getMemoryManager(), "."));
            if (input.remaining() == 1 && input.get(input.position()) == CLOSING) {
                input.tryDispose();
                ctx.getConnection().close().addCompletionHandler(new EmptyCompletionHandler<Closeable>() {
                    @Override
                    public void completed(final Closeable result) {
                        closed.complete(result);
                    }
                });
                return ctx.getStopAction();
            }
            if (input.remaining() == 1 && input.get(input.position()) == EMPTYING) {
                input.tryDispose();
                ctx.setMessage(ctx.getMemoryManager().allocate(0));
            }
            return ctx.getInvokeAction();
        }
    }

    /** Echoes complete lines; keeps an incomplete one as the remainder of its stop action, as protocol decoders do. */
    private static final class LineFilter extends BaseFilter {
        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final Buffer input = ctx.getMessage();
            if (!input.hasRemaining() || input.get(input.limit() - 1) != '\n') {
                return ctx.getStopAction(input);
            }
            ctx.write(input);
            return ctx.getStopAction();
        }
    }
}
