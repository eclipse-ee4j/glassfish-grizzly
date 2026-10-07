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

package org.glassfish.grizzly.http.server.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.HttpServerFilter;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.ServerFilterConfiguration;
import org.glassfish.grizzly.http.server.TestUtils;
import org.glassfish.grizzly.http.server.util.HtmlHelper;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.junit.After;
import org.junit.Test;

/**
 * Tests the {@link HttpServerFilter} extension hooks from a subclass outside the filter's package.
 */
public class HttpServerFilterHooksTest {

    private HttpServer server;
    private ExecutorService executor;
    private DelayedExecutor delayedExecutor;
    private int port;

    private final AtomicInteger handlerCalls = new AtomicInteger();

    @After
    public void tearDown() {
        if (server != null) {
            server.shutdownNow();
        }
        if (delayedExecutor != null) {
            delayedExecutor.stop();
            delayedExecutor.destroy();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void customRequestReachesHandler() throws Exception {
        final List<String> failures = new CopyOnWriteArrayList<>();
        startServer(CustomRequestFilter::new, new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                handlerCalls.incrementAndGet();
                if (!(request instanceof CustomRequest) || !(response instanceof CustomResponse)) {
                    failures.add("Unexpected types " + request.getClass() + ", " + response.getClass());
                }
                writeBody(response, 200, "ok");
            }
        });

        try (Socket socket = connect()) {
            for (int i = 0; i < 3; i++) {
                final HttpResult result = exchange(socket, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertEquals(200, result.status);
                assertEquals("ok", result.body);
            }
        }

        assertEquals(List.of(), failures);
        assertEquals(3, handlerCalls.get());
    }

    @Test
    public void rejectedRequestSkipsHandlerAndKeepsConnectionAlive() throws Exception {
        startServer((config, delayed) -> new HttpServerFilter(config, delayed) {
            @Override
            protected boolean beforeService(final Request request, final Response response) throws IOException {
                // 429 rather than 503: the HTTP codec closes the connection after a 503
                if (request.getHeader("X-Overloaded") != null) {
                    writeBody(response, 429, "busy");
                    return false;
                }
                return true;
            }
        }, new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                handlerCalls.incrementAndGet();
                writeBody(response, 200, "ok");
            }
        });

        try (Socket socket = connect()) {
            HttpResult result = exchange(socket, "GET / HTTP/1.1\r\nHost: localhost\r\nX-Overloaded: yes\r\n\r\n");
            assertEquals(429, result.status);
            assertEquals("busy", result.body);
            assertEquals(0, handlerCalls.get());

            result = exchange(socket, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals(200, result.status);
            assertEquals("ok", result.body);
            assertEquals(1, handlerCalls.get());
        }
    }

    @Test
    public void rejectedRequestMarkedAsErrorClosesConnection() throws Exception {
        startServer((config, delayed) -> new HttpServerFilter(config, delayed) {
            @Override
            protected boolean beforeService(final Request request, final Response response) throws IOException {
                if (request.getContentLength() > 4) {
                    response.getResponse().getProcessingState().setError(true);
                    HtmlHelper.setErrorAndSendErrorPage(request, response, getConfiguration().getDefaultErrorPageGenerator(), 413,
                            HttpStatus.REQUEST_ENTITY_TOO_LARGE_413.getReasonPhrase(), "Too large", null);
                    return false;
                }
                return true;
            }
        }, new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                handlerCalls.incrementAndGet();
                writeBody(response, 200, "ok");
            }
        });

        try (Socket socket = connect()) {
            final HttpResult result = exchange(socket, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n0123456789");
            assertEquals(413, result.status);
            assertEquals("close", result.connection);
            assertEquals(-1, socket.getInputStream().read());
        }
        assertEquals(0, handlerCalls.get());
    }

    @Test
    public void exceptionInHookIsAnsweredWith500() throws Exception {
        startServer((config, delayed) -> new HttpServerFilter(config, delayed) {
            @Override
            protected boolean beforeService(final Request request, final Response response) throws IOException {
                throw new IOException("Admission check failed");
            }
        }, new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                handlerCalls.incrementAndGet();
            }
        });

        try (Socket socket = connect()) {
            assertEquals(500, exchange(socket, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").status);
        }
        assertEquals(0, handlerCalls.get());
    }

    // ------------------------------------------------------------------ helpers

    private void startServer(final BiFunction<ServerFilterConfiguration, DelayedExecutor, HttpServerFilter> filterFactory,
            final HttpHandler handler) throws IOException {
        executor = Executors.newCachedThreadPool();
        delayedExecutor = new DelayedExecutor(executor);
        delayedExecutor.start();

        port = TestUtils.findAvailableTcpPort();
        server = new HttpServer();
        final NetworkListener listener = new NetworkListener("hooks", "localhost", port);
        // Replace the default filter the same way an application outside Grizzly's package would
        listener.registerAddOn((networkListener, builder) -> {
            final int idx = builder.indexOfType(HttpServerFilter.class);
            final HttpServerFilter original = (HttpServerFilter) builder.get(idx);
            final HttpServerFilter replacement = filterFactory.apply(original.getConfiguration(), delayedExecutor);
            replacement.setHttpHandler(original.getHttpHandler());
            builder.set(idx, replacement);
        });
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(handler, "/");
        server.start();
    }

    private Socket connect() throws IOException {
        final Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static void writeBody(final Response response, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        response.setStatus(status);
        response.setContentType("text/plain");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static HttpResult exchange(final Socket socket, final String request) throws IOException {
        final OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        final InputStream in = socket.getInputStream();
        final String statusLine = readLine(in);
        final HttpResult result = new HttpResult();
        result.status = Integer.parseInt(statusLine.split(" ")[1]);
        int contentLength = -1;
        boolean chunked = false;
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            final int colon = line.indexOf(':');
            final String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            final String value = line.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                contentLength = Integer.parseInt(value);
            } else if ("transfer-encoding".equals(name)) {
                chunked = value.equalsIgnoreCase("chunked");
            } else if ("connection".equals(name)) {
                result.connection = value.toLowerCase(Locale.ROOT);
            }
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked) {
            for (int size = Integer.parseInt(readLine(in).trim(), 16); size > 0; size = Integer.parseInt(readLine(in).trim(), 16)) {
                body.write(in.readNBytes(size));
                readLine(in);
            }
            readLine(in);
        } else {
            assertTrue("Response without length", contentLength >= 0);
            body.write(in.readNBytes(contentLength));
        }
        result.body = body.toString(StandardCharsets.US_ASCII);
        return result;
    }

    private static String readLine(final InputStream in) throws IOException {
        final StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c == -1) {
                throw new IOException("Connection closed");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    private static final class HttpResult {
        int status;
        String body;
        String connection;
    }

    /**
     * Filter creating {@link CustomRequest}s.
     */
    private static final class CustomRequestFilter extends HttpServerFilter {

        CustomRequestFilter(final ServerFilterConfiguration config, final DelayedExecutor delayedExecutor) {
            super(config, delayedExecutor);
        }

        @Override
        protected Request createRequest() {
            return new CustomRequest();
        }
    }

    private static final class CustomRequest extends Request {

        CustomRequest() {
            super(new CustomResponse());
        }
    }

    private static final class CustomResponse extends Response {
    }
}
