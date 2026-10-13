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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.glassfish.grizzly.http.util.MimeHeaders;
import org.junit.After;
import org.junit.Test;

/**
 * {@link NetworkListener#setMaxRequestHeaders(int)} limits the number of request header fields.
 */
public class MaxRequestHeadersTest {

    private HttpServer httpServer;
    private int port;

    @After
    public void after() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void requestExceedingConfiguredLimitIsRejected() throws Exception {
        start(10);
        String statusLine = send(20);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void requestWithinConfiguredLimitIsServed() throws Exception {
        start(10);
        String statusLine = send(5);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void limitAboveDefaultIsHonored() throws Exception {
        start(2 * MimeHeaders.MAX_NUM_HEADERS_DEFAULT);
        String statusLine = send(MimeHeaders.MAX_NUM_HEADERS_DEFAULT + 20);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void defaultLimitAcceptsRequest() throws Exception {
        start(null);
        String statusLine = send(20);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 200"));
    }

    /**
     * Starts the server; <code>null</code> keeps the listener default.
     */
    private void start(Integer maxRequestHeaders) throws IOException {
        port = TestUtils.findAvailableTcpPort();
        httpServer = HttpServer.createSimpleServer(null, port);
        if (maxRequestHeaders != null) {
            httpServer.getListener("grizzly").setMaxRequestHeaders(maxRequestHeaders);
        }
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setContentType("text/plain");
                response.setContentLength(2);
                response.getWriter().write("ok");
            }
        }, "/");
        httpServer.start();
    }

    /**
     * Sends a request with <code>headerCount</code> header fields (including <code>Host</code>) and returns the
     * response status line.
     */
    private String send(int headerCount) throws IOException {
        StringBuilder request = new StringBuilder("GET / HTTP/1.1\r\nHost: localhost:" + port + "\r\n");
        for (int i = 1; i < headerCount; i++) {
            request.append("X-Header-").append(i).append(": v\r\n");
        }
        request.append("\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                line.write(b);
            }
            return line.toString(StandardCharsets.US_ASCII).trim();
        }
    }
}
