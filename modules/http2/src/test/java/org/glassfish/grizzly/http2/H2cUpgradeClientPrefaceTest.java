/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;

/**
 * An h2c client sends its connection preface either from the completion listener of the upgrade request write (on the
 * writing thread) or when it handles the 101 response (on the reading thread), whichever comes second. The listener
 * may run while the reading thread is between switching to the direct upgrade phase and creating the session: it must
 * not take the preface without being able to send it, or no preface is sent and the server never serves the following
 * HTTP/2 streams.
 * <p>
 * The test makes the listener run exactly there: the write of the upgrade request is held after its bytes are sent
 * until the reading thread creates the client session, which then waits until the write has returned.
 */
public class H2cUpgradeClientPrefaceTest extends AbstractHttp2Test {

    private static final long TIMEOUT_SECONDS = 10;

    @Test
    public void prefaceIsSentWhenTheUpgradeRequestWriteCompletesWhileTheSessionIsCreated() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer httpServer = createServer(null, port, false, false);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.setContentType("text/plain");
                response.getWriter().write("ok");
            }
        }, "/");

        final CountDownLatch sessionCreationStarted = new CountDownLatch(1);
        final CountDownLatch upgradeRequestWriteReturned = new CountDownLatch(1);
        final BlockingQueue<HttpContent> responses = new LinkedTransferQueue<>();

        final Http2ClientFilter http2ClientFilter = new Http2ClientFilter(Http2Configuration.builder().build()) {
            @Override
            protected Http2Session createClientHttp2Session(final Connection connection) {
                if (sessionCreationStarted.getCount() > 0) {
                    sessionCreationStarted.countDown();
                    await(upgradeRequestWriteReturned);
                }
                return super.createClientHttp2Session(connection);
            }
        };

        final FilterChain clientChain = FilterChainBuilder.stateless()
                .add(new TransportFilter())
                .add(new HoldFirstWriteFilter(sessionCreationStarted))
                .add(new HttpClientFilter())
                .add(http2ClientFilter)
                .add(new ResponseCollector(responses))
                .build();

        final TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().setProcessor(clientChain).build();

        httpServer.start();
        try {
            clientTransport.start();
            final Connection<?> connection = clientTransport.connect("localhost", port).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // the upgrade request; its completion listeners run before write() returns
            connection.write(request(port));
            upgradeRequestWriteReturned.countDown();
            // otherwise the write was not held while the session was created, and the race was not exercised
            assertEquals("the client session was not created while the upgrade request write was held", 0,
                    sessionCreationStarted.getCount());

            final HttpContent response1 = responses.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("no response to the upgrade request", response1);
            assertEquals("ok", response1.getContent().toStringContent());

            // a request on a new HTTP/2 stream is served only once the server has received the client preface
            connection.write(request(port));
            final HttpContent response2 = responses.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("no response on the HTTP/2 stream after the upgrade: was the client preface sent?", response2);
            assertEquals("ok", response2.getContent().toStringContent());
        } finally {
            clientTransport.shutdownNow();
            httpServer.shutdownNow();
        }
    }

    private static HttpPacket request(final int port) {
        final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_1_1)
                .host("localhost:" + port).build();
        return HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build();
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue("timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Sends the bytes of the first write (the upgrade request), then holds the writing thread, so the completion
     * listeners of the write, which run when it returns, wait until the client session is being created.
     */
    private static final class HoldFirstWriteFilter extends BaseFilter {
        private final CountDownLatch release;
        private final AtomicBoolean held = new AtomicBoolean();

        HoldFirstWriteFilter(final CountDownLatch release) {
            this.release = release;
        }

        @Override
        public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
            if (!held.compareAndSet(false, true)) {
                return ctx.getInvokeAction();
            }
            ctx.write(ctx.getMessage());
            await(release);
            return ctx.getStopAction();
        }
    }

    private static final class ResponseCollector extends BaseFilter {
        private final BlockingQueue<HttpContent> responses;

        ResponseCollector(final BlockingQueue<HttpContent> responses) {
            this.responses = responses;
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            final HttpContent content = ctx.getMessage();
            if (!content.isLast()) {
                return ctx.getStopAction(content);
            }
            responses.add(content);
            return ctx.getStopAction();
        }
    }
}
