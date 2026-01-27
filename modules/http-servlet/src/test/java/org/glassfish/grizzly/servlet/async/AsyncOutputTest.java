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
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.servlet.FilterRegistration;
import org.glassfish.grizzly.servlet.HttpServerAbstractTest;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.grizzly.utils.Futures;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Basic Servlet 3.1 non-blocking output tests.
 */
public class AsyncOutputTest extends HttpServerAbstractTest {
    private static Logger LOGGER = Grizzly.logger(AsyncOutputTest.class);

    public static int PORT = PORT();

    public void testNonBlockingOutputByteByByte() throws Exception {
        System.out.println("testNonBlockingOutputByteByByte");
        
        try {
            int MAX_TIME_MILLIS = 10 * 1000;
            FutureImpl<Boolean> blockFuture = Futures.createSafeFuture();

            newHttpServer(PORT);

            WebappContext ctx = new WebappContext("Test", "/contextPath");
            addServlet(ctx, "foobar", "/servletPath/*", new HttpServlet() {

                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

                    AsyncContext asyncCtx = req.startAsync();

                    ServletOutputStream output = res.getOutputStream();
                    WriteListenerImpl writeListener = new WriteListenerImpl(asyncCtx, blockFuture);
                    output.setWriteListener(writeListener);

                    output.write("START\n".getBytes());
                    output.flush();

                    long count = 0;
                    System.out.println("--> Begin for loop");
                    boolean prevCanWrite;
                    long startTimeMillis = System.currentTimeMillis();

                    while (prevCanWrite = output.isReady()) {
                        writeData(output);
                        count++;

                        if (System.currentTimeMillis() - startTimeMillis > MAX_TIME_MILLIS) {
                            System.out.println("Error: can't overload output buffer");
                            return;
                        }
                    }

                    blockFuture.result(Boolean.TRUE);
                    System.out.println("--> prevCanWriite = " + prevCanWrite + ", count = " + count);
                }

                void writeData(ServletOutputStream output) throws IOException {
                    output.write((byte) 'a');
                }

            });

            ctx.deploy(httpServer);
            httpServer.start();

            HttpURLConnection conn = createConnection("/contextPath/servletPath/pathInfo", PORT);

            BufferedReader input = null;
            String line;
            boolean expected = false;
            try {
                input = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                int count = 0;
                boolean first = true;
                while ((line = input.readLine()) != null) {
                    expected = expected || line.endsWith("onWritePossible");
                    System.out.println("\n " + count++ + ": " + line.length());
                    int length = line.length();
                    int lengthToPrint = 20;
                    int end = length > lengthToPrint ? lengthToPrint : length;
                    System.out.print(line.substring(0, end) + "...");
                    if (length > 20) {
                        System.out.println(line.substring(length - 20));
                    }
                    System.out.println();
                    if (first) {
                        System.out.println("Waiting for server to run into async output mode...");
                        blockFuture.get(60, TimeUnit.SECONDS);
                        first = false;
                    }
                }
            } finally {
                try {
                    if (input != null) {
                        input.close();
                    }
                } catch (Exception ex) {
                }
            }

        } finally {
            stopHttpServer();
        }
    }

    public void testNonBlockingOutput() throws Exception {
        System.out.println("testNonBlockingOutput");
        try {
            int MAX_TIME_MILLIS = 10 * 1000;
            FutureImpl<Boolean> blockFuture = Futures.createSafeFuture();

            newHttpServer(PORT);

            WebappContext ctx = new WebappContext("Test", "/contextPath");
            addServlet(ctx, "foobar", "/servletPath/*", new HttpServlet() {

                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

                    AsyncContext asyncCtx = req.startAsync();

                    ServletOutputStream output = res.getOutputStream();
                    WriteListenerImpl writeListener = new WriteListenerImpl(asyncCtx, blockFuture);
                    output.setWriteListener(writeListener);

                    output.write("START\n".getBytes());
                    output.flush();

                    long count = 0;
                    System.out.println("--> Begin for loop");
                    boolean prevCanWrite;
                    long startTimeMillis = System.currentTimeMillis();

                    while (prevCanWrite = output.isReady()) {
                        writeData(output, count, 1024);
                        count++;

                        if (System.currentTimeMillis() - startTimeMillis > MAX_TIME_MILLIS) {
                            System.out.println("Error: can't overload output buffer");
                            return;
                        }
                    }

                    blockFuture.result(Boolean.TRUE);
                    System.out.println("--> prevCanWriite = " + prevCanWrite + ", count = " + count);
                }

                void writeData(ServletOutputStream output, long count, int len) throws IOException {
//                    System.out.println("--> calling writeData " + count);
                    char[] cs = String.valueOf(count).toCharArray();
                    byte[] b = new byte[len];
                    for (int i = 0; i < cs.length; i++) {
                        b[i] = (byte) cs[i];
                    }
                    Arrays.fill(b, cs.length, len, (byte) 'a');
                    output.write(b);
                }

            });

            ctx.deploy(httpServer);
            httpServer.start();

            HttpURLConnection conn = createConnection("/contextPath/servletPath/pathInfo", PORT);

            BufferedReader input = null;
            String line;
            boolean expected = false;
            try {
                input = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                int count = 0;
                boolean first = true;
                while ((line = input.readLine()) != null) {
                    expected = expected || line.endsWith("onWritePossible");
                    System.out.println("\n " + count++ + ": " + line.length());
                    int length = line.length();
                    int lengthToPrint = 20;
                    int end = length > lengthToPrint ? lengthToPrint : length;
                    System.out.print(line.substring(0, end) + "...");
                    if (length > 20) {
                        System.out.println(line.substring(length - 20));
                    }
                    System.out.println();
                    if (first) {
                        System.out.println("Waiting for server to run into async output mode...");
                        blockFuture.get(60, TimeUnit.SECONDS);
                        first = false;
                    }
                }
            } finally {
                try {
                    if (input != null) {
                        input.close();
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

    static class WriteListenerImpl implements WriteListener {
        private AsyncContext asyncCtx;
        private Future<Boolean> blockFuture;

        private WriteListenerImpl(AsyncContext asyncCtx, Future<Boolean> blockFuture) {
            this.asyncCtx = asyncCtx;
            this.blockFuture = blockFuture;
        }

        @Override
        public void onWritePossible() {
            try {
                if (!blockFuture.isDone()) {
                    return;
                }
                ServletOutputStream output = asyncCtx.getResponse().getOutputStream();

                String message = "onWritePossible";
                System.out.println("--> " + message);
                output.write(message.getBytes());
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
