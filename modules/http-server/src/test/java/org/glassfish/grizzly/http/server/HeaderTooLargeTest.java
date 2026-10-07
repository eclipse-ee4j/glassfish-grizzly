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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Request header fields exceeding the configured maximum header size are answered with
 * <code>431 Request Header Fields Too Large</code> (RFC 6585, section 5).
 */
public class HeaderTooLargeTest {

    private static final int MAX_HEADER_SIZE = 1024;

    private HttpServer httpServer;
    private int port;

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        httpServer = HttpServer.createSimpleServer(null, port);
        httpServer.getListener("grizzly").setMaxHttpHeaderSize(MAX_HEADER_SIZE);
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

    @After
    public void after() {
        httpServer.shutdownNow();
    }

    @Test
    public void oversizedHeaderIsAnsweredWith431() throws Exception {
        String request = "GET / HTTP/1.1\r\nHost: localhost:" + port + "\r\nCookie: " + "a".repeat(2 * MAX_HEADER_SIZE) + "\r\n\r\n";
        String statusLine = send(request);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 431 Request Header Fields Too Large"));
    }

    @Test
    public void oversizedHeaderSplitAcrossManyFieldsIsAnsweredWith431() throws Exception {
        StringBuilder request = new StringBuilder("GET / HTTP/1.1\r\nHost: localhost:" + port + "\r\n");
        for (int i = 0; i < 64; i++) {
            request.append("X-Header-").append(i).append(": ").append("v".repeat(32)).append("\r\n");
        }
        String statusLine = send(request.append("\r\n").toString());
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 431 Request Header Fields Too Large"));
    }

    @Test
    public void oversizedRequestLineIsStillAnsweredWith400() throws Exception {
        String request = "GET /" + "a".repeat(2 * MAX_HEADER_SIZE) + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n";
        String statusLine = send(request);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void headerWithinLimitIsServed() throws Exception {
        String request = "GET / HTTP/1.1\r\nHost: localhost:" + port + "\r\nCookie: " + "a".repeat(MAX_HEADER_SIZE / 2) + "\r\n\r\n";
        String statusLine = send(request);
        assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 200"));
    }

    /**
     * Sends the request and returns the response status line.
     */
    private String send(String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
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
