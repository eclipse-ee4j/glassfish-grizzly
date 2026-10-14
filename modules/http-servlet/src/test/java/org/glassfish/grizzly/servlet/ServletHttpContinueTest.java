/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.servlet;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RunWith(Parameterized.class)
public class ServletHttpContinueTest {

    public int port = 18890 + 14;

    private final int numberOfExtraHttpHandlers;

    public ServletHttpContinueTest(final int numberOfExtraHttpHandlers) {
        this.numberOfExtraHttpHandlers = numberOfExtraHttpHandlers;
    }

    @Parameters
    public static Collection<Object[]> getNumberOfExtraHttpHandlers() {
        return Arrays.asList(new Object[][] { { 0 }, { 5 } });
    }

    @Before
    public void findPort() {
        try {
            port = TestUtils.findAvailableTcpPort();
        } catch (IOException e) {
            port = 18890 + 14;
        }
    }

    // ------------------------------------------------------------ Test Methods

    @Test
    public void test100Continue() throws Exception {

        final SafeFutureImpl<String> future = new SafeFutureImpl<>();
        HttpServer server = createServer(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                future.result(request.getParameter("a"));
            }
        }, null, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", port);
            s.setSoTimeout(10 * 1000);

            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + port + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            out.write("Content-Length: 7\r\n".getBytes());
            out.write("Expect: 100-continue\r\n".getBytes());
            out.write("\r\n".getBytes());

            StringBuilder sb = new StringBuilder();
            for (;;) {
                int i = in.read();
                if (i == '\r') {
                    in.mark(6);
                    if (in.read() == '\n' && in.read() == '\r' && in.read() == '\n') {
                        break;
                    } else {
                        in.reset();
                    }
                } else {
                    sb.append((char) i);
                }
            }

            assertEquals("HTTP/1.1 100 Continue", sb.toString().trim());

            // send post data now that we have clearance
            out.write("a=hello\r\n\r\n".getBytes());
            assertEquals("hello", future.get(10, TimeUnit.SECONDS));
            sb.setLength(0);
            for (;;) {
                int i = in.read();
                if (i == '\r') {
                    break;
                } else {
                    sb.append((char) i);
                }
            }

            assertEquals("HTTP/1.1 200 OK", sb.toString().trim());
        } finally {
            server.shutdownNow();
            if (s != null) {
                s.close();
            }
        }

    }

    @Test
    public void testExpectationIgnored() throws Exception {

        HttpServer server = createServer(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                response.setStatus(404);
            }
        }, null, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", port);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            StringBuilder post = new StringBuilder();
            post.append("POST /path HTTP/1.1\r\n");
            post.append("Host: localhost:").append(port).append("\r\n");
            post.append("Expect: 100-continue\r\n");
            post.append("Content-Type: application/x-www-form-urlencoded\r\n");
            post.append("Content-Length: 7\r\n");
            post.append("\r\n");
            post.append("a=hello\r\n\r\n");

            out.write(post.toString().getBytes());

            StringBuilder sb = new StringBuilder();
            for (;;) {
                int i = in.read();
                if (i == '\r') {
                    break;
                } else {
                    sb.append((char) i);
                }
            }

            assertEquals("HTTP/1.1 404 Not Found", sb.toString().trim());

        } finally {
            server.shutdownNow();
            if (s != null) {
                s.close();
            }
        }

    }

    @Test
    public void testFailedExpectation() throws Exception {

        HttpServer server = createServer(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            }
        }, null, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", port);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + port + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            out.write("Content-Length: 7\r\n".getBytes());
            out.write("Expect: 100-Continue-Extension\r\n".getBytes());
            out.write("\r\n".getBytes());

            StringBuilder sb = new StringBuilder();
            for (;;) {
                int i = in.read();
                if (i == '\r') {
                    break;
                } else {
                    sb.append((char) i);
                }
            }

            assertEquals("HTTP/1.1 417 Expectation Failed", sb.toString().trim());

        } finally {
            server.shutdownNow();
            if (s != null) {
                s.close();
            }
        }

    }

    @Test
    public void testCustomFailedExpectation() throws Exception {

        HttpServer server = createServer(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            }
        }, new ExpectationHandler() {

            @Override
            public void onExpectAcknowledgement(HttpServletRequest request, HttpServletResponse response, AckAction action) throws Exception {
                action.fail();
            }
        }, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", port);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + port + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            out.write("Content-Length: 7\r\n".getBytes());
            out.write("Expect: 100-Continue\r\n".getBytes());
            out.write("\r\n".getBytes());

            StringBuilder sb = new StringBuilder();
            for (;;) {
                int i = in.read();
                if (i == '\r') {
                    break;
                } else {
                    sb.append((char) i);
                }
            }

            assertEquals("HTTP/1.1 417 Expectation Failed", sb.toString().trim());

        } finally {
            server.shutdownNow();
            if (s != null) {
                s.close();
            }
        }

    }

    /**
     * When an ExpectationHandler is configured but does neither {@code acknowledge()} nor {@code fail()} on the action,
     * {@link ServletHandler} auto-acknowledges by calling {@code ackAction.acknowledge()}. The final response status
     * must still reflect what the servlet sets — regression for a bug where the 100-Continue status leaked through.
     */
    @Test
    public void testExpectationHandlerAutoAcknowledgeDoesNotLeakStatus() throws Exception {

        HttpServer server = createServer(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                // Consume the body. Do NOT call setStatus — the default 200 must surface in the final response,
                // rather than the 100 the auto-ack code path was previously leaking onto the response status.
                request.getInputStream().readAllBytes();
            }
        }, new ExpectationHandler() {

            @Override
            public void onExpectAcknowledgement(HttpServletRequest request, HttpServletResponse response, AckAction action) throws Exception {
                // Intentionally do nothing — ServletHandler will auto-ack.
            }
        }, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", port);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + port + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            out.write("Content-Length: 7\r\n".getBytes());
            out.write("Expect: 100-Continue\r\n".getBytes());
            out.write("\r\n".getBytes());

            assertEquals("HTTP/1.1 100 Continue", readStatusLine(in));

            out.write("a=hello".getBytes());
            assertEquals("HTTP/1.1 200 OK", readStatusLine(in));

        } finally {
            server.shutdownNow();
            if (s != null) {
                s.close();
            }
        }

    }

    private static String readStatusLine(final InputStream in) throws IOException {
        // Read the status line, then consume the rest of the message head up to and including the empty line so the
        // next message is correctly positioned. Avoids InputStream.mark/reset which sockets do not support.
        final StringBuilder statusLine = new StringBuilder();
        for (int c; (c = in.read()) != -1 && c != '\r';) {
            statusLine.append((char) c);
        }
        int prev = '\r';
        for (int c, crlfPairsSeen = 0; (c = in.read()) != -1;) {
            if (c == '\n' && prev == '\r') {
                if (++crlfPairsSeen == 2) {
                    return statusLine.toString();
                }
            } else if (c != '\r') {
                crlfPairsSeen = 0;
            }
            prev = c;
        }
        return statusLine.toString();
    }

    // --------------------------------------------------------- Private Methods

    private HttpServer createServer(final HttpServlet httpServlet, final ExpectationHandler expectationHandler, final String mapping) {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        server.addListener(listener);

        for (int i = 0; i < numberOfExtraHttpHandlers; i++) {
            server.getServerConfiguration().addHttpHandler(new StaticHttpHandler(), String.valueOf("/" + i));
        }

        WebappContext ctx = new WebappContext("Test");

        final ServletRegistration reg = ctx.addServlet("TestSerlvet", httpServlet);
        reg.setExpectationHandler(expectationHandler);
        reg.addMapping(mapping);

        ctx.deploy(server);

        return server;

    }

}
