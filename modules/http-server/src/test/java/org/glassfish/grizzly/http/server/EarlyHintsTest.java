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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import javax.net.SocketFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test the <code>103 Early Hints</code> interim response support exposed via {@link Response#sendEarlyHints()}.
 */
public class EarlyHintsTest {

    private int port;
    private static final String LINK_VALUE = "</style.css>; rel=preload; as=style";

    private HttpServer server;

    @Before
    public void findPort() {
        try {
            port = TestUtils.findAvailableTcpPort();
        } catch (IOException e) {
            port = 9497;
        }
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    public void testEarlyHintsThenFinalResponse() throws Exception {
        startServer(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setHeader("Link", LINK_VALUE);
                response.sendEarlyHints();
                response.setContentType("text/plain");
                response.getWriter().write("done");
            }
        });

        try (Socket s = connect()) {
            sendRequest(s, "GET /path HTTP/1.1");
            InputStream in = s.getInputStream();

            String interim = readBlock(in);
            assertTrue("expected 103 interim, got: " + interim, interim.startsWith("HTTP/1.1 103 Early Hints"));
            assertTrue("103 must carry the Link header, got: " + interim, interim.contains("Link: " + LINK_VALUE));

            String finalResponse = readBlock(in);
            assertTrue("expected 200 final, got: " + finalResponse, finalResponse.startsWith("HTTP/1.1 200 OK"));
            // The headers set before sendEarlyHints() must still be present in the final response.
            assertTrue("final response must still carry the Link header, got: " + finalResponse,
                    finalResponse.contains("Link: " + LINK_VALUE));
        }
    }

    @Test
    public void testEarlyHintsMayBeSentMultipleTimes() throws Exception {
        startServer(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setHeader("Link", LINK_VALUE);
                response.sendEarlyHints();
                response.sendEarlyHints();
                response.setContentType("text/plain");
                response.getWriter().write("done");
            }
        });

        try (Socket s = connect()) {
            sendRequest(s, "GET /path HTTP/1.1");
            InputStream in = s.getInputStream();

            assertTrue(readBlock(in).startsWith("HTTP/1.1 103 Early Hints"));
            assertTrue(readBlock(in).startsWith("HTTP/1.1 103 Early Hints"));
            assertTrue(readBlock(in).startsWith("HTTP/1.1 200 OK"));
        }
    }

    @Test
    public void testEarlyHintsIgnoredForHttp10() throws Exception {
        startServer(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setHeader("Link", LINK_VALUE);
                response.sendEarlyHints();
                response.setContentType("text/plain");
                response.getWriter().write("done");
            }
        });

        try (Socket s = connect()) {
            sendRequest(s, "GET /path HTTP/1.0");
            InputStream in = s.getInputStream();

            String response = readBlock(in);
            assertFalse("HTTP/1.0 must not receive a 103 interim response, got: " + response,
                    response.contains("103 Early Hints"));
            assertTrue("expected the final 200 response, got: " + response, response.contains("200 OK"));
        }
    }

    // --------------------------------------------------------- Private Methods

    private void startServer(final HttpHandler httpHandler) throws IOException {
        server = new HttpServer();
        server.addListener(new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port));
        server.getServerConfiguration().addHttpHandler(httpHandler, "/path");
        server.start();
    }

    private Socket connect() throws IOException {
        Socket s = SocketFactory.getDefault().createSocket("localhost", port);
        s.setSoTimeout(10 * 1000);
        return s;
    }

    private void sendRequest(final Socket s, final String requestLine) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write((requestLine + "\r\n").getBytes());
        out.write(("Host: localhost:" + port + "\r\n").getBytes());
        out.write("\r\n".getBytes());
        out.flush();
    }

    /**
     * Read a single HTTP message head (status line and headers) up to and including the terminating empty line.
     */
    private static String readBlock(final InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int len = sb.length();
            if (len >= 4 && sb.charAt(len - 4) == '\r' && sb.charAt(len - 3) == '\n' && sb.charAt(len - 2) == '\r'
                    && sb.charAt(len - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

}
