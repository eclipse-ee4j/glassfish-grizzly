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

import static org.junit.Assert.assertEquals;
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
 * An <code>Upgrade</code> request no filter takes (e.g. <code>Upgrade: h2c</code> without HTTP/2 enabled) has to be
 * served as a plain HTTP/1.1 request, including its message body framing.
 */
public class UnhandledUpgradeTest {

    private static final String UPGRADE_HEADERS = "Upgrade: h2c\r\nConnection: Upgrade, HTTP2-Settings\r\nHTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA\r\n";

    private HttpServer httpServer;
    private int port;

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        httpServer = HttpServer.createSimpleServer(null, port);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                byte[] body = request.getInputStream().readAllBytes();
                response.setHeader("X-Upgrade", String.valueOf(request.getHeader("Upgrade")));
                response.setContentType("text/plain");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
        }, "/echo");
        httpServer.start();
    }

    @After
    public void after() {
        httpServer.shutdownNow();
    }

    @Test
    public void chunkedBodyIsDecodedWhenUpgradeIsNotTaken() throws Exception {
        String request = "POST /echo HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + UPGRADE_HEADERS
                + "Transfer-Encoding: chunked\r\n\r\n" + "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n";
        assertEcho(request, "Hello World");
    }

    @Test
    public void fixedLengthBodyIsServedWhenUpgradeIsNotTaken() throws Exception {
        String request = "POST /echo HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + UPGRADE_HEADERS
                + "Content-Length: 11\r\n\r\nHello World";
        assertEcho(request, "Hello World");
    }

    @Test
    public void bodylessRequestIsServedWhenUpgradeIsNotTaken() throws Exception {
        String request = "GET /echo HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + UPGRADE_HEADERS + "\r\n";
        String response = assertEcho(request, "");
        assertTrue("Upgrade header not visible to the handler: " + response, response.contains("X-Upgrade: h2c\r\n"));
    }

    @Test
    public void chunkedBodyWithoutUpgradeIsDecoded() throws Exception {
        String request = "POST /echo HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n" + "5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n";
        assertEcho(request, "Hello World");
    }

    @Test
    public void connectionIsKeptAliveWhenUpgradeIsNotTaken() throws Exception {
        String request = "POST /echo HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + UPGRADE_HEADERS
                + "Transfer-Encoding: chunked\r\n\r\n" + "5\r\nHello\r\n0\r\n\r\n";
        try (Socket socket = connect()) {
            assertEcho(socket, request, "Hello");
            assertEcho(socket, request, "Hello");
        }
    }

    private String assertEcho(String request, String expectedBody) throws IOException {
        try (Socket socket = connect()) {
            return assertEcho(socket, request, expectedBody);
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", port), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static String assertEcho(Socket socket, String request, String expectedBody) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String response = readResponse(socket.getInputStream());
        assertTrue("Unexpected response: " + response, response.startsWith("HTTP/1.1 200"));
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertEquals(expectedBody, body);
        return response;
    }

    /**
     * Reads the status line, the headers and a <code>Content-Length</code> delimited body.
     */
    private static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int contentLength = -1;
        int headerEnd = -1;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            String s = buf.toString(StandardCharsets.US_ASCII);
            if (headerEnd < 0 && s.endsWith("\r\n\r\n")) {
                headerEnd = s.length();
                for (String line : s.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                if (contentLength <= 0) {
                    break;
                }
            } else if (headerEnd >= 0 && buf.size() - headerEnd >= contentLength) {
                break;
            }
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }
}
