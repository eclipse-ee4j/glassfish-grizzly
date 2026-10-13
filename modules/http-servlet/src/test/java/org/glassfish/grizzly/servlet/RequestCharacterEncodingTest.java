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

package org.glassfish.grizzly.servlet;

import java.net.HttpURLConnection;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;

/**
 * Verifies how the request character encoding is derived from the context default (Servlet 4.0
 * {@code <request-character-encoding>}).
 */
public class RequestCharacterEncodingTest extends HttpServerAbstractTest {

    private static final int PORT = PORT();

    /**
     * When the request names no character encoding, the context default request encoding takes effect. Before the fix
     * the request encoding was derived from the context response encoding, so this returned the wrong value (or none).
     */
    public void testContextDefaultRequestEncodingApplied() throws Exception {
        try {
            startServer("UTF-8");
            final HttpURLConnection conn = getConnection("/enc", PORT);
            assertEquals(HttpServletResponse.SC_OK, conn.getResponseCode());
            assertEqualsIgnoreCase("UTF-8", conn.getHeaderField("Req-Encoding"));
        } finally {
            stopHttpServer();
        }
    }

    /**
     * When the request names a character encoding, the context default must not overwrite it.
     */
    public void testClientEncodingNotOverwritten() throws Exception {
        try {
            startServer("UTF-8");
            final HttpURLConnection conn = createConnection("/enc", PORT);
            conn.setRequestProperty("Content-Type", "text/plain;charset=ISO-8859-1");
            conn.connect();
            assertEquals(HttpServletResponse.SC_OK, conn.getResponseCode());
            assertEqualsIgnoreCase("ISO-8859-1", conn.getHeaderField("Req-Encoding"));
        } finally {
            stopHttpServer();
        }
    }

    private void startServer(final String requestEncoding) throws Exception {
        httpServer = new HttpServer();
        httpServer.addListener(new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT));

        final WebappContext ctx = new WebappContext("Test");
        ctx.setRequestCharacterEncoding(requestEncoding);
        final ServletRegistration reg = ctx.addServlet("enc", new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
                resp.setHeader("Req-Encoding", String.valueOf(req.getCharacterEncoding()));
                resp.setStatus(HttpServletResponse.SC_OK);
            }
        });
        reg.addMapping("/enc");
        ctx.deploy(httpServer);

        httpServer.start();
    }

    private static void assertEqualsIgnoreCase(final String expected, final String actual) {
        assertTrue("expected <" + expected + "> but was <" + actual + ">", expected.equalsIgnoreCase(actual));
    }
}
