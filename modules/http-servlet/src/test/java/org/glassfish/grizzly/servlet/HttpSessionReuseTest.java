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

import static org.glassfish.grizzly.servlet.ClientUtil.createRequest;
import static org.glassfish.grizzly.servlet.ClientUtil.sendRequest;

import java.io.IOException;
import java.util.Collections;

import org.glassfish.grizzly.http.Cookie;
import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.server.util.Globals;
import org.glassfish.grizzly.http.util.Header;

import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Session state set by the application is kept across consecutive requests.
 */
public class HttpSessionReuseTest extends HttpServerAbstractTest {

    private static final int PORT = PORT();
    private static final String CONTEXT = "/test";
    private static final String SERVLET_MAPPING = "/servlet";
    private static final int CUSTOM_INTERVAL_SEC = 1234;

    public void testMaxInactiveIntervalKeptAcrossRequests() throws Exception {
        startHttpServer(PORT);

        final WebappContext ctx = new WebappContext("Test", CONTEXT);
        final ServletRegistration registration = ctx.addServlet("sessionServlet", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                final HttpSession session = req.getSession(true);
                if (session.isNew()) {
                    session.setMaxInactiveInterval(CUSTOM_INTERVAL_SEC);
                }
                resp.addHeader("Max-Inactive-Interval", String.valueOf(session.getMaxInactiveInterval()));
            }
        });
        registration.addMapping(SERVLET_MAPPING);
        ctx.deploy(httpServer);

        try {
            final HttpContent response1 = sendRequest(createRequest(CONTEXT + SERVLET_MAPPING, PORT, null), 10, PORT);
            final Cookie sessionCookie = getSessionCookie(response1);
            assertNotNull(sessionCookie);
            assertEquals(String.valueOf(CUSTOM_INTERVAL_SEC), response1.getHttpHeader().getHeader("Max-Inactive-Interval"));

            final HttpContent response2 = sendRequest(createRequest(CONTEXT + SERVLET_MAPPING, PORT,
                    Collections.singletonMap(Header.Cookie.toString(), Globals.SESSION_COOKIE_NAME + "=" + sessionCookie.getValue())), 10, PORT);
            assertNull("The existing session must be used", getSessionCookie(response2));
            assertEquals(String.valueOf(CUSTOM_INTERVAL_SEC), response2.getHttpHeader().getHeader("Max-Inactive-Interval"));
        } finally {
            stopHttpServer();
        }
    }

    private static Cookie getSessionCookie(HttpContent response) {
        final Cookies cookies = new Cookies();
        cookies.setHeaders(response.getHttpHeader().getHeaders(), false);
        for (Cookie cookie : cookies.get()) {
            if (Globals.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie;
            }
        }

        return null;
    }
}
