/*
 * Copyright (c) 2010, 2025 Oracle and/or its affiliates. All rights reserved.
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

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HttpContinueTest {

    private static final int PORT = 9495;

    private final int numberOfExtraHttpHandlers;
    private final boolean chunkedTransferEncoding;

    public HttpContinueTest(final int numberOfExtraHttpHandlers, final boolean chunkedTransferEncoding) {
        this.numberOfExtraHttpHandlers = numberOfExtraHttpHandlers;
        this.chunkedTransferEncoding = chunkedTransferEncoding;
    }

    @Parameters
    public static Collection<Object[]> getNumberOfExtraHttpHandlers() {
        return Arrays.asList(new Object[][] { { 0, FALSE }, { 0, TRUE }, { 5, FALSE }, { 5, TRUE } });
    }

    // ------------------------------------------------------------ Test Methods

    @Test
    public void test100Continue() throws Exception {

        final SafeFutureImpl<String> future = new SafeFutureImpl<>();
        HttpServer server = createServer(new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                future.result(request.getParameter("a"));
            }

        }, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", PORT);
            s.setSoTimeout(10 * 1000);

            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + PORT + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            if (chunkedTransferEncoding) {
                out.write("Transfer-Encoding: chunked\r\n".getBytes());
            } else {
                out.write("Content-Length: 7\r\n".getBytes());
            }
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
            if (chunkedTransferEncoding) {
                out.write("7\r\na=hello\r\n0\r\n\r\n".getBytes());
            } else {
                out.write("a=hello\r\n\r\n".getBytes());
            }
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

        HttpServer server = createServer(new StaticHttpHandler(Collections.<String>emptySet()), "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", PORT);
            s.setSoTimeout(10 * 1000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            StringBuilder post = new StringBuilder();
            post.append("POST /path HTTP/1.1\r\n");
            post.append("Host: localhost:").append(PORT).append("\r\n");
            post.append("Expect: 100-continue\r\n");
            post.append("Content-Type: application/x-www-form-urlencoded\r\n");
            if (chunkedTransferEncoding) {
                post.append("Transfer-Encoding: chunked\r\n");
                post.append("\r\n");
                post.append("7\r\na=hello\r\n0\r\n\r\n");
            } else {
                post.append("Content-Length: 7\r\n");
                post.append("\r\n");
                post.append("a=hello\r\n\r\n");
            }

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

        HttpServer server = createServer(new StaticHttpHandler(), "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", PORT);
            s.setSoTimeout(10 * 1000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + PORT + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            if (chunkedTransferEncoding) {
                out.write("Transfer-Encoding: chunked\r\n".getBytes());
            } else {
                out.write("Content-Length: 7\r\n".getBytes());
            }
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

        HttpServer server = createServer(new StaticHttpHandler() {

            @Override
            protected boolean sendAcknowledgment(Request request, Response response) throws IOException {
                response.setStatus(HttpStatus.EXPECTATION_FAILED_417);
                return false;
            }

        }, "/path");

        Socket s = null;
        try {
            server.start();
            s = SocketFactory.getDefault().createSocket("localhost", PORT);
            s.setSoTimeout(10 * 1000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write("POST /path HTTP/1.1\r\n".getBytes());
            out.write(("Host: localhost:" + PORT + "\r\n").getBytes());
            out.write("Content-Type: application/x-www-form-urlencoded\r\n".getBytes());
            if (chunkedTransferEncoding) {
                out.write("Transfer-Encoding: chunked\r\n".getBytes());
            } else {
                out.write("Content-Length: 7\r\n".getBytes());
            }
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

            if (chunkedTransferEncoding) {
                // chunked transfer encoding doesn't support custom HttpHandler#sendAcknowledgment()
                assertEquals("HTTP/1.1 100 Continue", sb.toString().trim());
            } else {
                assertEquals("HTTP/1.1 417 Expectation Failed", sb.toString().trim());
            }

        } finally {
            server.shutdownNow();
            if (s != null) {
                s.close();
            }
        }

    }
    // --------------------------------------------------------- Private Methods

    private HttpServer createServer(final HttpHandler httpHandler, final String... mappings) {

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(httpHandler, mappings);

        for (int i = 0; i < numberOfExtraHttpHandlers; i++) {
            server.getServerConfiguration().addHttpHandler(new StaticHttpHandler(), String.valueOf("/" + i));
        }

        return server;

    }

}
