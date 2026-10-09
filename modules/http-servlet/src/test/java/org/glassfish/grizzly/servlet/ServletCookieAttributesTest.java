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
import java.net.HttpURLConnection;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet cookie attributes set via {@link Cookie#setAttribute(String, String)} end up in the <code>Set-Cookie</code>
 * header.
 */
public class ServletCookieAttributesTest extends HttpServerAbstractTest {

    private static final int PORT = PORT();
    private static final String CONTEXT = "/test";

    public void testCookieAttributes() throws Exception {
        startHttpServer(PORT);
        try {
            WebappContext ctx = new WebappContext("Test", CONTEXT);
            ctx.addServlet("cookie", new HttpServlet() {

                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    Cookie cookie = new Cookie("plain", "value");
                    resp.addCookie(cookie);

                    cookie = new Cookie("attributed", "value");
                    cookie.setPath("/");
                    cookie.setSecure(true);
                    cookie.setHttpOnly(true);
                    cookie.setAttribute("SameSite", "Strict");
                    cookie.setAttribute("Partitioned", "");
                    resp.addCookie(cookie);
                }
            }).addMapping("/cookie");
            ctx.deploy(httpServer);

            HttpURLConnection conn = getConnection(CONTEXT + "/cookie", PORT);
            assertEquals(200, conn.getResponseCode());
            List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
            assertTrue(String.valueOf(setCookies), setCookies.contains("plain=value"));
            assertTrue(String.valueOf(setCookies), setCookies.contains("attributed=value; Path=/; Secure; HttpOnly; Partitioned; SameSite=Strict"));
        } finally {
            stopHttpServer();
        }
    }

    public void testSessionCookieAttributes() throws Exception {
        startHttpServer(PORT);
        try {
            WebappContext ctx = new WebappContext("Test", CONTEXT);
            ctx.getSessionCookieConfig().setAttribute("SameSite", "Lax");
            ctx.addServlet("session", new HttpServlet() {

                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                    req.getSession(true);
                }
            }).addMapping("/session");
            ctx.deploy(httpServer);

            HttpURLConnection conn = getConnection(CONTEXT + "/session", PORT);
            assertEquals(200, conn.getResponseCode());
            String setCookie = conn.getHeaderField("Set-Cookie");
            assertTrue(setCookie, setCookie.startsWith("JSESSIONID="));
            assertTrue(setCookie, setCookie.endsWith("; SameSite=Lax"));
        } finally {
            stopHttpServer();
        }
    }
}
