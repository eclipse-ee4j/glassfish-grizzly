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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.utils.DelayedExecutor;
import org.junit.After;
import org.junit.Test;

/**
 * Verifies that {@link HttpServer#createHttpServerFilter(NetworkListener, ServerFilterConfiguration, DelayedExecutor)}
 * is used as the factory for the HTTP server filter, so a subclass can supply its own server filter per listener.
 */
public class HttpServerFilterFactoryTest {

    private static final int PORT = 18909;

    private HttpServer httpServer;

    @After
    public void after() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void overrideIsInvokedAndServesRequests() throws Exception {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger createdRequests = new AtomicInteger();
        final AtomicReference<HttpServerFilter> created = new AtomicReference<>();
        final AtomicReference<NetworkListener> factoryListener = new AtomicReference<>();

        httpServer = new HttpServer() {
            @Override
            protected HttpServerFilter createHttpServerFilter(final NetworkListener listener, final ServerFilterConfiguration config,
                    final DelayedExecutor delayedExecutor) {
                invocations.incrementAndGet();
                factoryListener.set(listener);
                final HttpServerFilter filter = new HttpServerFilter(config, delayedExecutor) {
                    @Override
                    protected Request createRequest() {
                        createdRequests.incrementAndGet();
                        return super.createRequest();
                    }
                };
                created.set(filter);
                return filter;
            }
        };

        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT);
        httpServer.addListener(listener);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.getWriter().write("ok");
            }
        }, "/");
        httpServer.start();

        assertEquals("Server filter factory should be invoked once per listener", 1, invocations.get());
        assertSame("Factory should be called for the configured listener", listener, factoryListener.get());
        assertTrue("The server filter from the factory should be wired into the listener's filter chain",
                listener.getFilterChain().contains(created.get()));

        final HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + PORT + "/").openConnection();
        try {
            assertEquals(200, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
        assertEquals("The request should have been served by the server filter from the factory", 1, createdRequests.get());
    }
}
