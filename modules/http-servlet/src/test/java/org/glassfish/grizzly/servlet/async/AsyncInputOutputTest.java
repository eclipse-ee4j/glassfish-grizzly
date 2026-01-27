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

package org.glassfish.grizzly.servlet.async;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.servlet.HttpServerAbstractTest;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AsyncInputOutputTest extends HttpServerAbstractTest {
    private static final Logger LOGGER = Grizzly.logger(AsyncInputOutputTest.class);
    private static final int PORT = PORT();

    public void testNonBlockingInputOutput() throws Exception {
        try {
            newHttpServer(PORT);

            final WebappContext ctx = new WebappContext("Test", "/contextPath");
            final ServletRegistration reg = ctx.addServlet("foobar", new HttpServlet() {

                @Override
                protected void doPost(HttpServletRequest req, HttpServletResponse res)
                        throws ServletException, IOException {
                    final AsyncContext asyncContext = req.startAsync();
                    final ServletInputStream input = req.getInputStream();
                    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    input.setReadListener(new ReadListener() {

                        @Override
                        public void onDataAvailable() throws IOException {
                            final byte[] bytes = new byte[1024];
                            int len;
                            while (input.isReady() && (len = input.read(bytes)) != -1) {
                                buffer.write(bytes, 0, len);
                            }
                        }

                        @Override
                        public void onAllDataRead() throws IOException {
                            final HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
                            response.setContentType("text/plain");
                            final ServletOutputStream output = response.getOutputStream();
                            final byte[] responseBytes = ("Received " + buffer.size() + " bytes").getBytes();
                            output.setWriteListener(new WriteListener() {
                                int offset = 0;

                                @Override
                                public void onWritePossible() throws IOException {
                                    while (output.isReady() && offset < responseBytes.length) {
                                        output.write(responseBytes[offset++]);
                                    }
                                    if (offset >= responseBytes.length) {
                                        asyncContext.complete();
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    LOGGER.log(Level.WARNING, "Unexpected error", t);
                                    asyncContext.complete();
                                }
                            });
                        }

                        @Override
                        public void onError(Throwable t) {
                            LOGGER.log(Level.WARNING, "Unexpected error", t);
                            asyncContext.complete();
                        }
                    });
                }
            });
            reg.addMapping("/servletPath/*");
            ctx.deploy(httpServer);
            httpServer.start();

            final HttpURLConnection conn = createConnection("/contextPath/servletPath/pathInfo", PORT);
            conn.setChunkedStreamingMode(5);
            conn.setDoOutput(true);
            conn.connect();

            BufferedReader input = null;
            BufferedWriter output = null;
            boolean expected = false;
            try {
                output = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
                final String data1 = "Hello";
                final String data2 = "World";
                try {
                    output.write(data1);
                    output.flush();
                    int sleepInSeconds = 3;
                    LOGGER.info("Sleeping " + sleepInSeconds + " seconds");
                    Thread.sleep(sleepInSeconds * 1000);

                    output.write(data2);
                    output.flush();
                    output.close();
                } catch (Exception ignore) {
                }
                input = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = input.readLine()) != null) {
                    expected = line.equals("Received " + (data1.length() + data2.length()) + " bytes");
                    if (expected) {
                        break;
                    }
                }
                assertTrue(expected);
            } finally {
                try {
                    if (input != null) {
                        input.close();
                    }
                } catch (Exception ignore) {
                }
                try {
                    if (output != null) {
                        output.close();
                    }
                } catch (Exception ignore) {
                }
            }
        } finally {
            stopHttpServer();
        }
    }
}
