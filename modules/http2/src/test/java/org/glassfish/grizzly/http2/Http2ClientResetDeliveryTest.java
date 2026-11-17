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

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;

/**
 * A response the client has received in full must reach the application even if the stream is reset before the
 * client's HTTP/2 executor gets to it: dropping requests of reset streams is a server-side defence (rapid reset) only.
 */
public class Http2ClientResetDeliveryTest {

    @Test
    public void earlyResponseFollowedByRstNoErrorReachesClient() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        final Thread server = new Thread(() -> {
            try (Socket s = ss.accept()) {
                final DataInputStream in = new DataInputStream(s.getInputStream());
                final OutputStream out = s.getOutputStream();
                in.readFully(new byte[24]); // preface
                out.write(new byte[] { 0, 0, 0, 4, 0, 0, 0, 0, 0 }); // SETTINGS
                while (true) {
                    final int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                    final int type = in.readUnsignedByte();
                    final int flags = in.readUnsignedByte();
                    in.readInt();
                    in.readFully(new byte[len]);
                    if (type == 4 && (flags & 1) == 0) {
                        out.write(new byte[] { 0, 0, 0, 4, 1, 0, 0, 0, 0 }); // SETTINGS ACK
                    }
                    if (type == 1) {
                        // HEADERS :status 200, END_STREAM|END_HEADERS, then RST_STREAM(NO_ERROR), RFC 9113 8.1
                        out.write(new byte[] { 0, 0, 1, 1, 5, 0, 0, 0, 1, (byte) 0x88, 0, 0, 4, 3, 0, 0, 0, 0, 1, 0, 0, 0, 0 });
                        out.flush();
                        break;
                    }
                }
                Thread.sleep(5000);
            } catch (Exception ignored) {
            }
        });
        server.start();

        // the client's HTTP/2 executor gets to the task only after the selector has read the RST_STREAM as well
        final ExecutorService slow = Executors.newSingleThreadExecutor();
        final ExecutorService delayed = new java.util.concurrent.AbstractExecutorService() {
            public void execute(Runnable r) {
                slow.execute(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    r.run();
                });
            }
            public void shutdown() { slow.shutdown(); }
            public java.util.List<Runnable> shutdownNow() { return slow.shutdownNow(); }
            public boolean isShutdown() { return slow.isShutdown(); }
            public boolean isTerminated() { return slow.isTerminated(); }
            public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException { return slow.awaitTermination(t, u); }
        };

        final CountDownLatch gotResponse = new CountDownLatch(1);
        final FilterChain chain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new HttpClientFilter())
                .add(new Http2ClientFilter(Http2Configuration.builder().priorKnowledge(true).executorService(delayed).build()))
                .add(new BaseFilter() {
                    @Override
                    public NextAction handleRead(FilterChainContext ctx) throws IOException {
                        final Object m = ctx.getMessage();
                        if (m instanceof HttpContent && !((HttpContent) m).getHttpHeader().isRequest()) {
                            gotResponse.countDown();
                        }
                        return ctx.getStopAction();
                    }
                }).build();

        final TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().setProcessor(chain).build();
        transport.start();
        try {
            final Connection<?> c = transport.connect("localhost", ss.getLocalPort()).get(10, TimeUnit.SECONDS);
            final HttpRequestPacket req = HttpRequestPacket.builder().method(Method.POST).uri("/").protocol(Protocol.HTTP_2_0)
                    .host("localhost").contentLength(100).build();
            // request body still to come: the server answers early and resets the upload
            c.write(HttpContent.builder(req).content(Buffers.EMPTY_BUFFER).last(false).build());
            assertTrue("complete response followed by RST_STREAM(NO_ERROR) never reached the client",
                    gotResponse.await(5, TimeUnit.SECONDS));
        } finally {
            transport.shutdownNow();
            ss.close();
            slow.shutdownNow();
        }
    }
}
