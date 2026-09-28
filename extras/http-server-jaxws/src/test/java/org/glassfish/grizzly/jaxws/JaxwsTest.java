/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2011, 2017 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.jaxws;

import java.io.IOException;
import java.util.Random;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpHandlerRegistration;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.grizzly.jaxws.addclient.AddServiceService;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Basic Grizzly JAX-WS {@link HttpHandler} test.
 *
 * @author Alexey Stashok
 */
public class JaxwsTest {
    private static final int PORT = 19881;

    private HttpServer httpServer;

    @Test
    public void testSync() throws Exception {
        startServer(new JaxwsHandler(new AddService(), false));

        try {
            test(10);
        } finally {
            stopServer();
        }
    }

    @Test
    public void testAsync() throws Exception {
        startServer(new JaxwsHandler(new AddService(), true));

        try {
            test(10);
        } finally {
            stopServer();
        }

    }

    private void startServer(HttpHandler httpHandler) throws IOException {
        httpServer = new HttpServer();
        NetworkListener networkListener = new NetworkListener("jaxws-listener", "0.0.0.0", PORT);

        httpServer.getServerConfiguration().addHttpHandler(new StaticHttpHandler(), "/add"); // make sure JAX-WS Handler is not default
        httpServer.getServerConfiguration().addHttpHandler(httpHandler,
                HttpHandlerRegistration.bulder()
                    .contextPath("/add/a/b")
                    .urlPattern("/")
                    .build());
        httpServer.addListener(networkListener);

        httpServer.start();
    }

    private void stopServer() {
        httpServer.shutdownNow();
    }

    private void test(int n) {
        final Random random = new Random();

        AddServiceService service = new AddServiceService();
        org.glassfish.grizzly.jaxws.addclient.AddService port = service.getAddServicePort();
        for (int i=0; i<n; i++) {
            final int value1 = random.nextInt(1000);
            final int value2 = random.nextInt(1000);
            final int result = port.add(value1, value2);

            assertEquals(value1 + value2, result);
        }
    }
}
