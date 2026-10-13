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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;

/**
 * Verifies the context default response character encoding (Servlet 4.0 <code>&lt;response-character-encoding&gt;</code>).
 */
public class ResponseCharacterEncodingTest extends HttpServerAbstractTest {

    private static final int PORT = PORT();

    /**
     * The context default response encoding is applied. Before the fix every request failed with a
     * <code>NullPointerException</code> (500), because the default was applied to a not yet initialized response.
     */
    public void testContextDefaultResponseEncodingApplied() throws Exception {
        try {
            startServer("UTF-16");
            final HttpURLConnection conn = getConnection("/default", PORT);
            assertEquals(HttpServletResponse.SC_OK, conn.getResponseCode());
            assertCharset("UTF-16", conn.getContentType());
        } finally {
            stopHttpServer();
        }
    }

    /**
     * An encoding set by the servlet overrides the context default.
     */
    public void testServletEncodingWins() throws Exception {
        try {
            startServer("UTF-16");
            final HttpURLConnection conn = getConnection("/own", PORT);
            assertEquals(HttpServletResponse.SC_OK, conn.getResponseCode());
            assertCharset("UTF-8", conn.getContentType());
        } finally {
            stopHttpServer();
        }
    }

    private void startServer(final String responseEncoding) throws Exception {
        httpServer = new HttpServer();
        httpServer.addListener(new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT));

        final WebappContext ctx = new WebappContext("Test");
        ctx.setResponseCharacterEncoding(responseEncoding);
        ctx.addServlet("default", new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write("ok");
            }
        }).addMapping("/default");
        ctx.addServlet("own", new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write("ok");
            }
        }).addMapping("/own");
        ctx.deploy(httpServer);

        httpServer.start();
    }

    private static void assertCharset(final String expected, final String contentType) {
        assertNotNull("no Content-Type", contentType);
        assertTrue("expected charset <" + expected + "> in <" + contentType + ">",
                contentType.toLowerCase(Locale.ROOT).replace(" ", "").endsWith(";charset=" + expected.toLowerCase(Locale.ROOT)));
    }
}
