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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.glassfish.grizzly.http.server.FormPostTooLargeException;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;

/**
 * Verifies that a form POST exceeding the configured maximum form post size is answered with
 * <code>413 Request Entity Too Large</code> rather than <code>500 Internal Server Error</code>.
 */
public class FormPostTooLargeTest extends HttpServerAbstractTest {

    private static final int PORT = PORT();

    public void testOversizedFormPostYields413() throws Exception {
        try {
            startServerWithMaxFormPostSize(16);
            final HttpURLConnection conn = postForm("/form", "field=0123456789abcdefghijklmnopqrstuvwxyz");
            assertEquals(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, conn.getResponseCode());
        } finally {
            stopHttpServer();
        }
    }

    public void testFormPostWithinLimitYields200() throws Exception {
        try {
            startServerWithMaxFormPostSize(4096);
            final HttpURLConnection conn = postForm("/form", "field=small");
            assertEquals(HttpServletResponse.SC_OK, conn.getResponseCode());
        } finally {
            stopHttpServer();
        }
    }

    public void testWrappedFormPostTooLargeIsDetected() {
        final Throwable wrapped = new ServletException(new RuntimeException(new FormPostTooLargeException("too large")));
        assertTrue(ServletHandler.isFormPostTooLarge(wrapped));
    }

    public void testCyclicCauseChainTerminates() {
        final Exception first = new Exception("first");
        final Exception second = new Exception("second", first);
        first.initCause(second);
        assertFalse(ServletHandler.isFormPostTooLarge(first));
    }

    private void startServerWithMaxFormPostSize(final int maxFormPostSize) throws Exception {
        httpServer = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT);
        listener.setMaxFormPostSize(maxFormPostSize);
        httpServer.addListener(listener);

        final WebappContext ctx = new WebappContext("Test");
        final ServletRegistration reg = ctx.addServlet("param", new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
                // Reading a request parameter triggers form parsing, which enforces the form post size limit.
                req.getParameter("field");
                resp.setStatus(HttpServletResponse.SC_OK);
            }
        });
        reg.addMapping("/form");
        ctx.deploy(httpServer);

        httpServer.start();
    }

    private HttpURLConnection postForm(final String alias, final String body) throws Exception {
        final HttpURLConnection conn = createConnection(alias, PORT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.ISO_8859_1));
        }
        conn.connect();
        return conn;
    }
}
