/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package org.glassfish.grizzly;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.glassfish.grizzly.utils.DelayFilter;
import org.glassfish.grizzly.utils.StringFilter;

/**
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class ProtocolChainCodecTest extends GrizzlyTestCase {
    private static final Logger logger = Grizzly.logger(ProtocolChainCodecTest.class);

    public void testSyncSingleStringEcho() throws Exception {
        doTestStringEcho(true, 1);
    }

    public void testAsyncSingleStringEcho() throws Exception {
        doTestStringEcho(false, 1);
    }

    public void testSync20StringEcho() throws Exception {
        doTestStringEcho(true, 20);
    }

    public void testAsync20SingleStringEcho() throws Exception {
        doTestStringEcho(false, 20);
    }

    public void testSyncSingleChunkedStringEcho() throws Exception {
        doTestStringEcho(true, 1, new ChunkingFilter(1));
    }

    public void testAsyncSingleChunkedStringEcho() throws Exception {
        doTestStringEcho(false, 1, new ChunkingFilter(1));
    }

    public void testSync20ChunkedStringEcho() throws Exception {
        doTestStringEcho(true, 20, new ChunkingFilter(1));
    }

    public void testAsync20ChunkedStringEcho() throws Exception {
        doTestStringEcho(false, 20, new ChunkingFilter(1));
    }

    public void testSyncDelayedSingleChunkedStringEcho() throws Exception {
        logger.info("This test execution may take several seconds");
        doTestStringEcho(true, 1, new DelayFilter(1000, 20), new ChunkingFilter(1));
    }

    public void testAsyncDelayedSingleChunkedStringEcho() throws Exception {
        logger.info("This test execution may take several seconds");
        doTestStringEcho(false, 1, new DelayFilter(1000, 20), new ChunkingFilter(1));
    }

    public void testSyncDelayed5ChunkedStringEcho() throws Exception {
        logger.info("This test execution may take several seconds");
        doTestStringEcho(true, 5, new DelayFilter(1000, 20), new ChunkingFilter(1));
    }

    public void testAsyncDelayed5ChunkedStringEcho() throws Exception {
        logger.info("This test execution may take several seconds");
        doTestStringEcho(false, 5, new DelayFilter(1000, 20), new ChunkingFilter(1));
    }

    protected final void doTestStringEcho(boolean blocking, int messageNum, Filter... filters) throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        Connection connection = null;

        final String clientMessage = "Hello server! It's a client";
        final String serverMessage = "Hello client! It's a server";

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        for (Filter filter : filters) {
            filterChainBuilder.add(filter);
        }
        filterChainBuilder.add(new StringFilter());
        filterChainBuilder.add(new BaseFilter() {
            volatile int counter;

            @Override
            public NextAction handleRead(FilterChainContext ctx) throws IOException {

                final String message = ctx.getMessage();

                logger.log(Level.FINE, "Server got message: " + message);

                assertEquals(clientMessage + "-" + counter, message);

                ctx.write(serverMessage + "-" + counter++);
                return ctx.getStopAction();
            }
        });

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(port);
            transport.start();

            final BlockingQueue<String> resultQueue = new LinkedTransferQueue<>();

            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new StringFilter());
            clientFilterChainBuilder.add(new BaseFilter() {

                @Override
                public NextAction handleRead(FilterChainContext ctx) throws IOException {
                    resultQueue.add((String) ctx.getMessage());
                    return ctx.getStopAction();
                }

            });
            final FilterChain clientFilterChain = clientFilterChainBuilder.build();

            SocketConnectorHandler connectorHandler = TCPNIOConnectorHandler.builder(transport).processor(clientFilterChain).build();

            Future<Connection> future = connectorHandler.connect("localhost", port);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            for (int i = 0; i < messageNum; i++) {
                Future<WriteResult> writeFuture = connection.write(clientMessage + "-" + i);

                assertTrue("Write timeout loop: " + i, writeFuture.get(10, TimeUnit.SECONDS) != null);

                final String message = resultQueue.poll(10, TimeUnit.SECONDS);

                assertEquals("Unexpected response (" + i + ")", serverMessage + "-" + i, message);
            }
        } finally {
            if (connection != null) {
                connection.closeSilently();
            }

            transport.shutdownNow();
        }
    }
}
