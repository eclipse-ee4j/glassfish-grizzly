/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2009, 2020 Oracle and/or its affiliates. All rights reserved.
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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http.server.HttpServer;

import static java.util.logging.Level.INFO;

/**
 * {@link HttpServer} tests.
 *
 * @author Hubert Iwaniuk
 * @since Jan 22, 2009
 */
public class ComplexHttpServerTest extends HttpServerAbstractTest {

    public static final int PORT = PORT();
    private static final Logger logger = Grizzly.logger(ComplexHttpServerTest.class);


    /**
     * Want to test multiple servletMapping
     *
     * examples :
     *
     * context = /test servletPath = /servlet1 mapping = *.1 mapping = /1
     *
     * URL = http://localhost:port/test/servlet1/test.1 URL = http://localhost:port/test/servlet1/1
     *
     * @throws IOException Error.
     */
    public void testComplexAliasMapping() throws Exception {
        System.out.println("testComplexAliasMapping");
        try {
            startHttpServer(PORT);
            String[] aliases = new String[] { "/1", "/2", "/3", "*.a" };
            String context = "/test";
            WebappContext ctx = new WebappContext("Test", context);

            for (String alias : aliases) {
                addServlet(ctx, alias);
            }

            ctx.deploy(httpServer);
            for (int i = 0; i < 3; i++) {
                HttpURLConnection conn = getConnection(context + aliases[i], PORT);
                assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
                assertEquals(context + aliases[i], readResponse(conn));
            }

            // special test
            String url = context + "/test.a";
            HttpURLConnection conn = getConnection(url, PORT);
            assertEquals(HttpServletResponse.SC_OK, getResponseCodeFromAlias(conn));
            assertEquals(url, readResponse(conn));

        } finally {
            stopHttpServer();
        }
    }

    private ServletRegistration addServlet(final WebappContext ctx, final String alias) {

        ServletRegistration reg = ctx.addServlet(alias, new HttpServlet() {

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                logger.log(INFO, "{0} received request {1}", new Object[] { alias, req.getRequestURI() });
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(req.getRequestURI());
            }
        });
        reg.addMapping(alias);
        return reg;
    }
}
