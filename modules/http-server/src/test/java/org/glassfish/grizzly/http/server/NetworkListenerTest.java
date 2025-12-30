/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.PortRange;
import org.glassfish.grizzly.http.server.util.Globals;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.Futures;
import org.junit.Test;

/**
 * {@link NetworkListener} tests.
 *
 * @author Alexey Stashok
 */
public class NetworkListenerTest {
    public static final int PORT = 18897;

    @Test
    public void testSetPort() throws Exception {
        NetworkListener listener = new NetworkListener("set-port", "0.0.0.0", PORT);
        HttpServer httpServer = new HttpServer();
        httpServer.addListener(listener);

        try {
            assertEquals(PORT, listener.getPort());
            httpServer.start();
            assertEquals(PORT, listener.getPort());
        } finally {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testAutoPort() throws Exception {
        NetworkListener listener = new NetworkListener("auto-port", "0.0.0.0", 0);
        HttpServer httpServer = new HttpServer();
        httpServer.addListener(listener);

        try {
            assertEquals(0, listener.getPort());
            httpServer.start();
            assertNotSame(0, listener.getPort());
        } finally {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testPortRange() throws Exception {
        final int RANGE = 10;
        final PortRange portRange = new PortRange(PORT, PORT + RANGE);
        NetworkListener listener = new NetworkListener("set-port", "0.0.0.0", portRange);
        HttpServer httpServer = new HttpServer();
        httpServer.addListener(listener);

        try {
            assertEquals(-1, listener.getPort());
            httpServer.start();
            assertTrue(listener.getPort() >= PORT);
            assertTrue(listener.getPort() <= PORT + RANGE);
        } finally {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void testTransactionTimeoutGetSet() throws Exception {
        NetworkListener l = new NetworkListener("test");
        assertEquals(-1, l.getTransactionTimeout());
        l.setTransactionTimeout(Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, l.getTransactionTimeout());
        l.setTransactionTimeout(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, l.getTransactionTimeout());
    }

    @Test
    public void testTransactionTimeout() throws Exception {
        final HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        final NetworkListener listener = server.getListener("grizzly");
        listener.setTransactionTimeout(5);
        final AtomicReference<Exception> timeoutFailed = new AtomicReference<>();
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                Thread.sleep(15000);
                timeoutFailed.compareAndSet(null, new IllegalStateException());
            }
        }, "/test");
        try {
            server.start();
            URL url = new URL("http://localhost:" + PORT + "/test");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            final long start = System.currentTimeMillis();
            c.connect();
            c.getResponseCode(); // cause the client to block
            final long stop = System.currentTimeMillis();
            assertNull(timeoutFailed.get());
            assertTrue(stop - start < 15000);
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    public void testImmediateGracefulShutdown() throws Exception {
        HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        server.start();

        final FutureImpl<Boolean> future = Futures.createSafeFuture();
        server.shutdown().addCompletionHandler(new EmptyCompletionHandler<HttpServer>() {
            @Override
            public void completed(HttpServer arg) {
                future.result(true);
            }

            @Override
            public void failed(Throwable error) {
                future.failure(error);
            }
        });

        future.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void testGracefulShutdown() throws Exception {
        final String msg = "Hello World";
        final byte[] msgBytes = msg.getBytes(Charsets.UTF8_CHARSET);

        final HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        addTestHandler(msgBytes, server);
        try {
            server.start();
            URL url = new URL("http://localhost:" + PORT + "/test");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();

            assertEquals(200, c.getResponseCode());
            assertEquals(msgBytes.length, c.getContentLength());

            Future<HttpServer> gracefulFuture = server.shutdown();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), Charsets.UTF8_CHARSET));
            final String content = reader.readLine();
            assertEquals(msg, content);

            assertNotNull(gracefulFuture.get(5, TimeUnit.SECONDS));
        } finally {
            server.shutdownNow();
        }
    }

    private void addTestHandler(final byte[] msgBytes, final HttpServer server) {
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @SuppressWarnings("Duplicates")
            @Override
            public void service(Request request, Response response) throws Exception {
                response.setContentType("text/plain");
                response.setCharacterEncoding(Charsets.UTF8_CHARSET.name());
                response.setContentLength(msgBytes.length);
                response.flush();
                Thread.sleep(2000);
                response.getOutputStream().write(msgBytes);
            }
        }, "/test");
    }

    @Test
    public void testGracefulShutdownGracePeriod() throws Exception {
        final String msg = "Hello World";
        final byte[] msgBytes = msg.getBytes(Charsets.UTF8_CHARSET);

        final HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        addTestHandler(msgBytes, server);
        try {
            server.start();
            URL url = new URL("http://localhost:" + PORT + "/test");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();

            assertEquals(200, c.getResponseCode());
            assertEquals(msgBytes.length, c.getContentLength());

            Future<HttpServer> gracefulFuture = server.shutdown(3, TimeUnit.SECONDS);

            final BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), Charsets.UTF8_CHARSET));
            final String content = reader.readLine();
            assertEquals(msg, content);

            assertNotNull(gracefulFuture.get(5, TimeUnit.SECONDS));
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    public void testGracefulShutdownGracePeriodExpired() throws Exception {
        final String msg = "Hello World";
        final byte[] msgBytes = msg.getBytes(Charsets.UTF8_CHARSET);

        final HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        addTestHandler(msgBytes, server);
        try {
            server.start();
            URL url = new URL("http://localhost:" + PORT + "/test");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();

            assertEquals(200, c.getResponseCode());
            assertEquals(msgBytes.length, c.getContentLength());

            Future<HttpServer> gracefulFuture = server.shutdown(1, TimeUnit.SECONDS);

            final BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), Charsets.UTF8_CHARSET));
            String content;
            try {
                // this should result in null in older JDKs(~ JDK 23) or IOException on newer JDKs(JDK 24 GA ~) as the grace period expires
                content = reader.readLine();
            } catch (IOException e) {
                // starting with JDK-24-GA, when EOF is encountered during BufferedReader#readLine() execution,
                // "java.io.IOException: Premature EOF" is thrown instead of returning null
                content = null;
            }
            assertNull(content);

            assertNotNull(gracefulFuture.get(5, TimeUnit.SECONDS));
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    public void testDefaultSessionCookieName() throws Exception {
        testCookeName(null);
    }

    @Test
    public void testCustomSessionCookieName() throws Exception {
        testCookeName("CookieMonster");
    }

    private void testCookeName(final String cookieName) throws Exception {
        final AtomicBoolean passed = new AtomicBoolean(false);

        final HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        String defaultCookieName = null;
        if (cookieName != null) {
            final SessionManager custom = DefaultSessionManager.instance();
            defaultCookieName = custom.getSessionCookieName();
            custom.setSessionCookieName(cookieName);
            server.getListener("grizzly").setSessionManager(custom);
        }
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                System.out.println(request.getSessionCookieName());
                if (cookieName != null) {
                    passed.compareAndSet(false, request.getSessionCookieName().equals(cookieName));
                } else {
                    passed.compareAndSet(false, Globals.SESSION_COOKIE_NAME.equals(request.getSessionCookieName()));
                }
            }
        }, "/test");
        try {
            server.start();
            URL url = new URL("http://localhost:" + PORT + "/test");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            assertEquals(200, c.getResponseCode());
            assertTrue(passed.get());

            Future<HttpServer> gracefulFuture = server.shutdown(1, TimeUnit.SECONDS);

            assertNotNull(gracefulFuture.get(5, TimeUnit.SECONDS));
        } finally {
            server.shutdownNow();
            if (defaultCookieName != null) {
                DefaultSessionManager.instance().setSessionCookieName(defaultCookieName);
            }
        }
    }
}
