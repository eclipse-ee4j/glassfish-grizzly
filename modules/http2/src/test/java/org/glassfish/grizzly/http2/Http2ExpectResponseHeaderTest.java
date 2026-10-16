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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.junit.Test;

/**
 * Expect is a request header, but an HTTP/2 client must still accept a response that carries it.
 */
public class Http2ExpectResponseHeaderTest extends AbstractHttp2Test {

    @Test
    public void responseWithExpectHeaderIsParsed() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer httpServer = createServer(null, port, false, false);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.setHeader("Expect", "100-continue");
                response.setContentType("text/plain");
                response.setContentLength(2);
                response.getWriter().write("ok");
            }
        }, "/");
        httpServer.start();
        try {
            final CountDownLatch last = new CountDownLatch(1);
            final AtomicReference<String> expect = new AtomicReference<>();
            final Connection<?> connection = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport())
                    .processor(createClientFilterChainAsBuilder(false, true, new BaseFilter() {
                        @Override
                        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                            final HttpContent content = ctx.getMessage();
                            expect.compareAndSet(null, content.getHttpHeader().getHeader("Expect"));
                            if (content.isLast()) {
                                last.countDown();
                            }
                            return ctx.getStopAction();
                        }
                    }).build())
                    .build().connect("localhost", port).get(10, TimeUnit.SECONDS);
            try {
                final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_2_0)
                        .host("localhost:" + port).build();
                connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());
                assertTrue("no complete response", last.await(10, TimeUnit.SECONDS));
                assertEquals("100-continue", expect.get());
            } finally {
                connection.closeSilently();
            }
        } finally {
            httpServer.shutdownNow();
        }
    }
}
