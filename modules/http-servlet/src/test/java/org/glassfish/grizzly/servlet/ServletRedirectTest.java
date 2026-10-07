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

package org.glassfish.grizzly.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Verifies the Servlet 6.1 <code>sendRedirect</code> overloads honor the status code and the <code>clearBuffer</code>
 * flag.
 */
public class ServletRedirectTest extends HttpServerAbstractTest {

    private static final int PORT = PORT();
    private static final String CONTEXT = "/test";
    private static final String BODY = "body written before the redirect";

    public void testRedirectWithStatusCode() throws Exception {
        startServer();
        try {
            assertRedirect("/redirect?sc=301", 301, true);
            assertRedirect("/redirect?sc=307", 307, true);
            assertRedirect("/redirect?sc=308", 308, true);
        } finally {
            stopHttpServer();
        }
    }

    public void testRedirectWithStatusCodeAndClearBuffer() throws Exception {
        startServer();
        try {
            assertRedirect("/redirect?sc=307&clear=true", 307, true);
            assertRedirect("/redirect?sc=308&clear=false", 308, false);
            assertRedirect("/redirect?clear=false", 302, false);
            assertRedirect("/redirect", 302, true);
        } finally {
            stopHttpServer();
        }
    }

    public void testRedirectIgnoredOnInclude() throws Exception {
        startServer();
        try {
            HttpURLConnection conn = createConnection(CONTEXT + "/include", PORT);
            conn.setInstanceFollowRedirects(false);
            assertEquals(200, conn.getResponseCode());
            assertNull(conn.getHeaderField("Location"));
            assertEquals("included", read(conn.getInputStream()));
        } finally {
            stopHttpServer();
        }
    }

    private void assertRedirect(String path, int expectedStatus, boolean expectRedirectNote) throws IOException {
        HttpURLConnection conn = createConnection(CONTEXT + path, PORT);
        conn.setInstanceFollowRedirects(false);
        assertEquals(path, expectedStatus, conn.getResponseCode());
        assertEquals(path, "http://localhost:" + PORT + CONTEXT + "/target", conn.getHeaderField("Location"));
        String body = read(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream());
        if (expectRedirectNote) {
            assertTrue(path + ": " + body, body.contains("Document moved"));
            assertFalse(path + ": " + body, body.contains(BODY));
        } else {
            assertEquals(path, BODY, body);
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void startServer() throws Exception {
        startHttpServer(PORT);
        WebappContext ctx = new WebappContext("Test", CONTEXT);
        ctx.addServlet("redirect", new HttpServlet() {

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(BODY);
                String sc = req.getParameter("sc");
                String clear = req.getParameter("clear");
                if (sc == null) {
                    if (clear == null) {
                        resp.sendRedirect("target");
                    } else {
                        resp.sendRedirect("target", Boolean.parseBoolean(clear));
                    }
                } else if (clear == null) {
                    resp.sendRedirect("target", Integer.parseInt(sc));
                } else {
                    resp.sendRedirect("target", Integer.parseInt(sc), Boolean.parseBoolean(clear));
                }
            }
        }).addMapping("/redirect");
        ctx.addServlet("include", new HttpServlet() {

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                req.getRequestDispatcher("/included").include(req, resp);
                resp.getWriter().write("included");
            }
        }).addMapping("/include");
        ctx.addServlet("included", new HttpServlet() {

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.sendRedirect("target", 301, true);
                resp.sendRedirect("target", 301);
                resp.sendRedirect("target", false);
            }
        }).addMapping("/included");
        ctx.deploy(httpServer);
    }
}
