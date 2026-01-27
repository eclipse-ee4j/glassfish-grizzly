/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.servlet.async;

import static jakarta.servlet.DispatcherType.REQUEST;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.servlet.FilterRegistration;
import org.glassfish.grizzly.servlet.HttpServerAbstractTest;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.Filter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Basic Servlet 3.1 non-blocking input tests.
 */
@RunWith(Parameterized.class)
public class AsyncInputTest extends HttpServerAbstractTest {
    private static Logger LOGGER = Grizzly.logger(AsyncInputTest.class);

    private final boolean withBlockingModeRead;

    public AsyncInputTest(final boolean withBlockingModeRead) {
        this.withBlockingModeRead = withBlockingModeRead;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getWithBlockingModeRead() {
        return Arrays.asList(new Object[][]{{true}, {false}});
    }

    @Test
    public void testNonBlockingInput() throws IOException {
        System.out.println("testNonBlockingInput");
        try {
            final int port = PORT();
            newHttpServer(port);
            WebappContext ctx = new WebappContext("Test", "/contextPath");
            addServlet(ctx, "foobar", "/servletPath/*", new HttpServlet() {

                @Override
                protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
                    ServletOutputStream output = res.getOutputStream();
                    ServletInputStream input = req.getInputStream();

                    AsyncContext asyncCtx = req.startAsync();

                    byte[] buffer = new byte[1024];

                    ReadListener readListener = new ReadListenerImpl(asyncCtx, buffer);
                    input.setReadListener(readListener);

                    if (withBlockingModeRead) {
                        int len;
                        while (input.isReady() && (len = input.read(buffer)) != -1) {
                            output.write(buffer, 0, len);
                        }
                    }
                }
            });

            ctx.deploy(httpServer);
            httpServer.start();

            HttpURLConnection conn = createConnection("/contextPath/servletPath/pathInfo", port);
            conn.setChunkedStreamingMode(5);
            conn.setDoOutput(true);
            conn.connect();

            BufferedReader input = null;
            BufferedWriter output = null;
            boolean expected;
            try {
                output = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
                try {
                    String data = "Hello";
                    output.write(data);
                    output.flush();
                    int sleepInSeconds = 3;
                    System.out.println("Sleeping " + sleepInSeconds + " seconds");
                    Thread.sleep(sleepInSeconds * 1000);
                    data = "World";
                    output.write(data);
                    output.flush();
                    output.close();
                } catch (Exception ex) {
                }
                input = new BufferedReader(new InputStreamReader(conn.getInputStream()));

                String line;
                while ((line = input.readLine()) != null) {
                    System.out.println(line);
                    expected = line.endsWith("-onAllDataRead");
                    if (expected) {
                        break;
                    }
                }
            } finally {
                try {
                    if (input != null) {
                        input.close();
                    }
                } catch (Exception ex) {
                }

                try {
                    if (output != null) {
                        output.close();
                    }
                } catch (Exception ex) {
                }
            }
        } finally {
            stopHttpServer();
        }
    }

    private ServletRegistration addServlet(WebappContext ctx, String name, String alias, Servlet servlet) {
        ServletRegistration reg = ctx.addServlet(name, servlet);
        reg.addMapping(alias);

        return reg;
    }

    private FilterRegistration addFilter(WebappContext ctx, String name, String alias, Filter filter) {
        FilterRegistration reg = ctx.addFilter(name, filter);
        reg.addMappingForUrlPatterns(EnumSet.of(REQUEST), alias);

        return reg;
    }

    private static class ReadListenerImpl implements ReadListener {
        private AsyncContext asyncCtx;
        private byte[] buffer;

        private ReadListenerImpl(AsyncContext asyncCtx, byte[] buffer) {
            this.asyncCtx = asyncCtx;
            this.buffer = buffer;
        }

        @Override
        public void onDataAvailable() {
            try {
                ServletInputStream input = asyncCtx.getRequest().getInputStream();
                ServletOutputStream output = asyncCtx.getResponse().getOutputStream();

                output.print("onDataAvailable-");
                int len;
                while (input.isReady() && (len = input.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                }
            } catch (Throwable t) {
                onError(t);
            }
        }

        @Override
        public void onAllDataRead() {
            try {
                ServletInputStream input = asyncCtx.getRequest().getInputStream();
                ServletOutputStream output = asyncCtx.getResponse().getOutputStream();

                output.println("-onAllDataRead");

                asyncCtx.complete();
            } catch (Throwable t) {
                onError(t);
            }
        }

        @Override
        public void onError(Throwable t) {
            LOGGER.log(Level.WARNING, "Unexpected error", t);
            asyncCtx.complete();
        }
    }
}
