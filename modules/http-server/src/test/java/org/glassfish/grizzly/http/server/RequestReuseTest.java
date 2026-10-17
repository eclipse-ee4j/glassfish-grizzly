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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.junit.Test;

/**
 * With the default setup the request objects are created on the selector thread and recycled on a worker thread; they
 * are given back to the selector thread, so a keep-alive connection reuses them instead of allocating new ones for every
 * request.
 */
public class RequestReuseTest {

    private static final int REQUESTS = 50;

    @Test
    public void requestObjectsAreReusedWhenServiceRunsOnAWorker() throws Exception {
        final Set<Object> packets = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> requests = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> serviceThreads = Collections.synchronizedSet(new java.util.TreeSet<>());
        final Set<Object> contexts = Collections.newSetFromMap(new IdentityHashMap<>());
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("reuse", "127.0.0.1", 0);
        server.addListener(listener);
        listener.getTransport().setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        // the FilterChainContext of every read, taken on the selector thread and completed on the worker
        listener.registerAddOn((networkListener, builder) -> builder.add(builder.indexOfType(HttpServerFilter.class), new BaseFilter() {
            @Override
            public NextAction handleRead(final FilterChainContext ctx) {
                synchronized (contexts) {
                    contexts.add(ctx);
                }
                return ctx.getInvokeAction();
            }
        }));
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (packets) {
                    packets.add(request.getRequest());
                    requests.add(request);
                }
                serviceThreads.add(Thread.currentThread().getName());
                response.setContentLength(5);
                response.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
            }
        }, "/");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();
            for (int i = 0; i < REQUESTS; i++) {
                out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                final String head = readResponse(in);
                assertTrue(head, head.startsWith("HTTP/1.1 200"));
            }
        } finally {
            server.shutdownNow();
        }
        assertEquals("service() on one worker thread: " + serviceThreads, 1, serviceThreads.size());
        assertTrue("distinct request packets for " + REQUESTS + " requests: " + packets.size(), packets.size() <= 3);
        assertTrue("distinct requests for " + REQUESTS + " requests: " + requests.size(), requests.size() <= 3);
        assertTrue("distinct filter chain contexts for " + REQUESTS + " requests: " + contexts.size(), contexts.size() <= 4);
        // every one of them is recycled (the worker may still be finishing the last one): none keeps the selector
        // thread's returned objects alive
        awaitNoOwner(packets);
        awaitNoOwner(requests);
        awaitNoOwner(contexts);
    }

    /**
     * A request whose response was suspended is not given back to the thread that created it: after the resume, the
     * thread that delivered its last data may still touch it, and the creating thread would hand it to another connection
     * right away. Here every response is suspended and resumed from another thread, so no request object is reused by
     * the selector.
     */
    @Test
    public void suspendedRequestsAreNotGivenBack() throws Exception {
        final Set<Object> packets = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> requests = Collections.newSetFromMap(new IdentityHashMap<>());
        final java.util.concurrent.ExecutorService resumer = java.util.concurrent.Executors.newSingleThreadExecutor();
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("suspend", "127.0.0.1", 0);
        server.addListener(listener);
        listener.getTransport().setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (packets) {
                    packets.add(request.getRequest());
                    requests.add(request);
                }
                response.suspend();
                resumer.execute(() -> {
                    try {
                        response.setContentLength(5);
                        response.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
                    } catch (final java.io.IOException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        response.resume();
                    }
                });
            }
        }, "/");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();
            for (int i = 0; i < REQUESTS; i++) {
                out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                final String head = readResponse(in);
                assertTrue(head, head.startsWith("HTTP/1.1 200"));
            }
        } finally {
            server.shutdownNow();
            resumer.shutdownNow();
        }
        assertEquals("request packets reused", REQUESTS, packets.size());
        assertEquals("requests reused", REQUESTS, requests.size());
    }

    /**
     * A request whose body is read asynchronously (a ReadHandler that had to wait) is not given back either, even
     * without a suspend: the handler may still run on another thread after the request was recycled.
     */
    @Test
    public void asyncReadRequestsAreNotGivenBack() throws Exception {
        final int requests = 20;
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("async-read", "127.0.0.1", 0);
        server.addListener(listener);
        listener.getTransport().setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (seen) {
                    seen.add(request);
                }
                // the rest of the body comes later: the handler has to wait for it
                request.getNIOInputStream().notifyAvailable(new org.glassfish.grizzly.ReadHandler() {
                    @Override
                    public void onDataAvailable() {
                    }

                    @Override
                    public void onError(final Throwable t) {
                    }

                    @Override
                    public void onAllDataRead() {
                    }
                });
                response.setContentLength(0);
            }
        }, "/");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();
            for (int i = 0; i < requests; i++) {
                out.write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n01234".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                final StringBuilder head = new StringBuilder();
                while (head.indexOf("\r\n\r\n") < 0) {
                    final int b = in.read();
                    assertTrue("end of stream after:\n" + head, b >= 0);
                    head.append((char) b);
                }
                assertTrue(head.toString(), head.toString().startsWith("HTTP/1.1 200"));
                out.write("56789".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } finally {
            server.shutdownNow();
        }
        assertEquals("requests reused", requests, seen.size());
    }

    /** Waits up to 10 s until none of the objects references an owner any more. */
    static void awaitNoOwner(final Set<Object> objects) throws Exception {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        for (final Object o : objects) {
            while (cacheOwner(o) != null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertNull("owner kept by a recycled " + o.getClass().getSimpleName(), cacheOwner(o));
        }
    }

    static Object cacheOwner(final Object o) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                final java.lang.reflect.Field field = c.getDeclaredField("cacheOwner");
                field.setAccessible(true);
                return field.get(o);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException("cacheOwner in " + o.getClass());
    }

    /** Reads a response with a 5-byte body and returns its head. */
    private static String readResponse(final InputStream in) throws Exception {
        final StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            final int b = in.read();
            assertTrue("end of stream after:\n" + head, b >= 0);
            head.append((char) b);
        }
        assertEquals(5, in.readNBytes(5).length);
        return head.toString();
    }
}
