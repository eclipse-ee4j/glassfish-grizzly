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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.junit.Test;

/**
 * The HTTP/1.1 request of an h2c upgrade becomes the request of HTTP/2 stream 1, which may still use it after the
 * request was recycled: it is not given back to the thread that created it, so no other connection gets it while the
 * upgraded connection is open.
 */
public class H2cUpgradeRequestReuseTest extends AbstractHttp2Test {

    private static final int REQUESTS = 20;

    @Test
    public void upgradedRequestIsNotReusedByAnotherConnection() throws Exception {
        final AtomicReference<Object> upgradedPacket = new AtomicReference<>();
        final AtomicReference<Request> upgradedRequest = new AtomicReference<>();
        final Set<Object> otherPackets = Collections.newSetFromMap(new IdentityHashMap<>());
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer httpServer = createServer(null, port, false, false);
        httpServer.getListener("grizzly").getTransport().setSelectorRunnersCount(1);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                if (request.getRequestURI().equals("/upgrade")) {
                    upgradedPacket.set(request.getRequest());
                    upgradedRequest.set(request);
                } else {
                    synchronized (otherPackets) {
                        otherPackets.add(request.getRequest());
                    }
                }
                response.setContentType("text/plain");
                response.setContentLength(2);
                response.getWriter().write("ok");
            }
        }, "/");
        httpServer.start();
        try (Socket upgraded = new Socket("127.0.0.1", port)) {
            upgraded.setSoTimeout(10_000);
            final OutputStream out = upgraded.getOutputStream();
            out.write(("GET /upgrade HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\n"
                    + "HTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final InputStream in = upgraded.getInputStream();
            final StringBuilder head = new StringBuilder();
            while (head.indexOf("\r\n\r\n") < 0) {
                final int b = in.read();
                assertTrue("end of stream after:\n" + head, b >= 0);
                head.append((char) b);
            }
            assertTrue(head.toString(), head.toString().startsWith("HTTP/1.1 101"));
            // wait until the upgraded request was served and recycled; the upgraded connection stays open
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((upgradedRequest.get() == null || upgradedRequest.get().getRequest() != null) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertNotNull("upgraded request served", upgradedPacket.get());

            try (Socket other = new Socket("127.0.0.1", port)) {
                other.setSoTimeout(10_000);
                final OutputStream out2 = other.getOutputStream();
                final InputStream in2 = other.getInputStream();
                for (int i = 0; i < REQUESTS; i++) {
                    out2.write("GET /other HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out2.flush();
                    final StringBuilder h = new StringBuilder();
                    while (h.indexOf("\r\n\r\n") < 0) {
                        final int b = in2.read();
                        assertTrue("end of stream after:\n" + h, b >= 0);
                        h.append((char) b);
                    }
                    assertTrue(h.toString(), h.toString().startsWith("HTTP/1.1 200"));
                    assertTrue(in2.readNBytes(2).length == 2);
                }
            }
        } finally {
            httpServer.shutdownNow();
        }
        assertTrue("the upgraded request's packet served another connection", !otherPackets.contains(upgradedPacket.get()));
    }
}
