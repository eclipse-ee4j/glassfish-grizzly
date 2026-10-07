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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.AddOn;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http2.ext.TerminatingHttp2ServerFilter;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.http2.frames.GoAwayFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.junit.After;
import org.junit.Test;

/**
 * Verifies that a {@link Http2ServerFilter} subclass outside this package can end a session with a chosen error code
 * through {@link Http2Session#terminate(ErrorCode, String)}.
 */
public class Http2SessionTerminateTest extends AbstractHttp2Test {

    private HttpServer httpServer;

    @After
    public void after() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void subclassTerminatesSessionWithChosenErrorCode() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final AtomicBoolean handlerCalled = new AtomicBoolean();
        httpServer = createServer(null, port, false);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                handlerCalled.set(true);
                response.getWriter().write("unexpected");
            }
        }, "/");
        final NetworkListener listener = httpServer.getListener("grizzly");
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        // Runs after the Http2AddOn registered by createServer() and swaps in the subclass
        listener.registerAddOn(new AddOn() {
            @Override
            public void setup(NetworkListener networkListener, FilterChainBuilder builder) {
                builder.set(builder.indexOfType(Http2ServerFilter.class),
                        new TerminatingHttp2ServerFilter(http2Addon.getConfiguration(), ErrorCode.ENHANCE_YOUR_CALM));
            }
        });
        httpServer.start();

        final AtomicReference<ErrorCode> errorCode = new AtomicReference<>();
        final AtomicInteger lastStreamId = new AtomicInteger(-1);
        final CountDownLatch goAwayReceived = new CountDownLatch(1);
        final FilterChain clientChain = FilterChainBuilder.stateless()
                .add(new TransportFilter())
                .add(new HttpClientFilter())
                .add(new Http2ClientFilter(Http2Configuration.builder().priorKnowledge(true).build()) {
                    @Override
                    protected boolean processFrames(FilterChainContext ctx, Http2Session http2Session, List<Http2Frame> framesList) {
                        for (Http2Frame frame : framesList) {
                            if (frame.getType() == GoAwayFrame.TYPE && goAwayReceived.getCount() > 0) {
                                // Copy the fields: super recycles the frame
                                errorCode.set(((GoAwayFrame) frame).getErrorCode());
                                lastStreamId.set(((GoAwayFrame) frame).getLastStreamId());
                                goAwayReceived.countDown();
                            }
                        }
                        return super.processFrames(ctx, http2Session, framesList);
                    }
                })
                .build();

        final SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(listener.getTransport())
                .processor(clientChain).build();
        final Future<Connection> connectFuture = connectorHandler.connect("localhost", port);
        final Connection<?> connection = connectFuture.get(10, TimeUnit.SECONDS);
        final CountDownLatch closed = new CountDownLatch(1);
        connection.addCloseListener((closeable, type) -> closed.countDown());

        final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_2_0)
                .host("localhost:" + port).build();
        connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());

        assertTrue("GOAWAY not received", goAwayReceived.await(10, TimeUnit.SECONDS));
        assertEquals(ErrorCode.ENHANCE_YOUR_CALM, errorCode.get());
        assertEquals(0, lastStreamId.get());
        assertTrue("Connection not closed", closed.await(10, TimeUnit.SECONDS));
        assertFalse("Request must not be dispatched", handlerCalled.get());
    }
}
