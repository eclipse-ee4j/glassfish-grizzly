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

package org.glassfish.grizzly.http.ajp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.junit.Test;

/**
 * AJP requests are created on the selector thread and recycled on a worker thread; they are given back to the selector
 * thread, so a connection reuses them.
 */
public class AjpRequestReuseTest extends AjpTestBase {

    private static final int REQUESTS = 50;

    @Test
    public void ajpRequestsAreReusedWhenServiceRunsOnAWorker() throws Exception {
        final Set<Object> packets = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> serviceThreads = Collections.synchronizedSet(new java.util.TreeSet<>());
        httpServer.getListener(LISTENER_NAME).getTransport()
                .setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        startHttpServer(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (packets) {
                    packets.add(request.getRequest());
                }
                serviceThreads.add(Thread.currentThread().getName());
                response.setStatus(200, "FINE");
            }
        }, "/");

        for (int i = 0; i < REQUESTS; i++) {
            final AjpForwardRequestPacket headersPacket = new AjpForwardRequestPacket("GET", "/reuse", 80, port);
            headersPacket.addHeader("Host", "localhost:80");
            // no body: no remainder is kept between the requests
            headersPacket.addHeader("Content-Length", "0");
            send(headersPacket.toByteArray());
            // SEND_HEADERS, then END_RESPONSE
            byte[] message;
            do {
                message = readAjpMessage();
            } while (message[4] != AjpConstants.JK_AJP13_END_RESPONSE);
        }

        assertEquals("service() on one worker thread: " + serviceThreads, 1, serviceThreads.size());
        assertTrue("distinct AJP requests for " + REQUESTS + " requests: " + packets.size(), packets.size() <= 3);
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        for (final Object o : packets) {
            while (cacheOwner(o) != null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertNull("owner kept by a recycled AJP request", cacheOwner(o));
        }
    }

    /** An AJP request whose response was suspended and resumed from another thread is not given back to the selector. */
    @Test
    public void suspendedAjpRequestsAreNotGivenBack() throws Exception {
        final Set<Object> packets = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> contexts = Collections.newSetFromMap(new IdentityHashMap<>());
        final java.util.concurrent.ExecutorService resumer = java.util.concurrent.Executors.newSingleThreadExecutor();
        httpServer.getListener(LISTENER_NAME).getTransport()
                .setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        // the FilterChainContext of every request, completed by the resuming thread
        httpServer.getListener(LISTENER_NAME).registerAddOn((networkListener, builder) -> builder.add(
                builder.indexOfType(org.glassfish.grizzly.http.server.HttpServerFilter.class), new org.glassfish.grizzly.filterchain.BaseFilter() {
                    @Override
                    public org.glassfish.grizzly.filterchain.NextAction handleRead(final org.glassfish.grizzly.filterchain.FilterChainContext ctx) {
                        synchronized (contexts) {
                            contexts.add(ctx);
                        }
                        return ctx.getInvokeAction();
                    }
                }));
        startHttpServer(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (packets) {
                    packets.add(request.getRequest());
                }
                response.suspend();
                resumer.execute(() -> {
                    response.setStatus(200, "FINE");
                    response.resume();
                });
            }
        }, "/");
        try {
            for (int i = 0; i < REQUESTS; i++) {
                final AjpForwardRequestPacket headersPacket = new AjpForwardRequestPacket("GET", "/reuse", 80, port);
                headersPacket.addHeader("Host", "localhost:80");
                headersPacket.addHeader("Content-Length", "0");
                send(headersPacket.toByteArray());
                byte[] message;
                do {
                    message = readAjpMessage();
                } while (message[4] != AjpConstants.JK_AJP13_END_RESPONSE);
            }
        } finally {
            resumer.shutdownNow();
        }
        assertEquals("AJP requests reused", REQUESTS, packets.size());
        assertEquals("filter chain contexts reused", REQUESTS, contexts.size());
    }

    private static Object cacheOwner(final Object o) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = AjpHttpRequest.class.getDeclaredField("cacheOwner");
        field.setAccessible(true);
        return field.get(o);
    }
}
