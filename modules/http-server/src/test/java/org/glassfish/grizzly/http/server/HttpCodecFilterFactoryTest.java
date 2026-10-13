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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * Verifies that {@link HttpServer#createHttpCodecFilter(NetworkListener, int)} is used as the factory for the HTTP
 * codec filter, so a subclass can supply its own codec filter per listener.
 */
public class HttpCodecFilterFactoryTest {

    private static final int PORT = 18906;

    private HttpServer httpServer;

    @After
    public void after() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    @Test
    public void overrideIsInvokedAndWiredIn() throws Exception {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicReference<org.glassfish.grizzly.http.HttpServerFilter> created = new AtomicReference<>();

        httpServer = new HttpServer() {
            @Override
            protected org.glassfish.grizzly.http.HttpServerFilter createHttpCodecFilter(final NetworkListener listener, final int maxHeaderSize) {
                invocations.incrementAndGet();
                final org.glassfish.grizzly.http.HttpServerFilter filter = super.createHttpCodecFilter(listener, maxHeaderSize);
                created.set(filter);
                return filter;
            }
        };

        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, PORT);
        httpServer.addListener(listener);
        httpServer.start();

        assertEquals("Codec filter factory should be invoked once per listener", 1, invocations.get());
        assertNotNull("Factory should have returned a codec filter", created.get());
        assertTrue("The codec filter from the factory should be wired into the listener's filter chain",
                listener.getFilterChain().contains(created.get()));
    }
}
