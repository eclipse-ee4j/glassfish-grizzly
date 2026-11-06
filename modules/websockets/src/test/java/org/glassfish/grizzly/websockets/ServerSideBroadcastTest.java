/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.websockets;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.glassfish.grizzly.http.HttpRequestPacket;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@SuppressWarnings({ "StringContatenationInLoop" })
@RunWith(Parameterized.class)
public class ServerSideBroadcastTest extends BaseWebSocketTestUtilities {
    public static final int ITERATIONS = 100;
    private final Version version;
    private final Broadcaster broadcaster;

    @Parameterized.Parameters
    public static List<Object[]> parameters() {
        final Broadcaster[] broadcasters = { new DummyBroadcaster(), new OptimizedBroadcaster() };

        final List<Object[]> versions = BaseWebSocketTestUtilities.parameters();
        final List<Object[]> resultList = new ArrayList<>();

        for (int i = 0; i < broadcasters.length; i++) {
            for (int j = 0; j < versions.size(); j++) {
                final Broadcaster broadcaster = broadcasters[i];
                final Version version = (Version) versions.get(j)[0];
                resultList.add(new Object[] { version, broadcaster });
            }
        }
        return resultList;
    }

    public ServerSideBroadcastTest(Version version, Broadcaster broadcaster) {
        this.version = version;
        this.broadcaster = broadcaster;
    }

    @Test
    public void broadcast() throws IOException, InstantiationException, ExecutionException, InterruptedException, URISyntaxException {
        final int websocketsCount = 5;
        final int port = TestUtils.findAvailableTcpPort();
        WebSocketServer server = WebSocketServer.createServer(port);
        server.register("", "/broadcast", new BroadcastApplication(broadcaster));
        server.start();
        List<TrackingWebSocket> clients = new ArrayList<>();
        try {
            String[] messages = { "test message", "let's try again", "3rd time's the charm!", "ok.  just one more", "now, we're done" };

            final String address = String.format("ws://localhost:%s/broadcast", port);
            for (int x = 0; x < websocketsCount; x++) {
                final TrackingWebSocket socket = new TrackingWebSocket(address, x + "", version, messages.length * websocketsCount * ITERATIONS);

                socket.connect();
                clients.add(socket);
            }

            for (int count = 0; count < ITERATIONS; count++) {
                for (String message : messages) {
                    for (TrackingWebSocket socket : clients) {

                        final String msgToSend = String.format("%s: count %s: %s", socket.getName(), count, message);

                        for (TrackingWebSocket rcpts : clients) {
                            rcpts.sent.add(msgToSend);
                        }

                        socket.send(msgToSend);
                    }
                }
            }
            for (TrackingWebSocket socket : clients) {
                Assert.assertTrue("All messages should come back: " + socket.getReceived(), socket.waitOnMessages());
            }
        } finally {
            server.stop();
        }
    }

    public static class BroadcastApplication extends WebSocketApplication {
        private final Broadcaster broadcaster;

        public BroadcastApplication(Broadcaster broadcaster) {
            this.broadcaster = broadcaster;
        }

        @Override
        public boolean isApplicationRequest(HttpRequestPacket request) {
            return "/broadcast".equals(request.getRequestURI());
        }

        @Override
        public WebSocket createSocket(ProtocolHandler handler, HttpRequestPacket requestPacket, WebSocketListener... listeners) {
            final DefaultWebSocket ws = (DefaultWebSocket) super.createSocket(handler, requestPacket, listeners);

            ws.setBroadcaster(broadcaster);
            return ws;
        }

        @Override
        public void onMessage(WebSocket socket, String data) {
            socket.broadcast(getWebSockets(), data);
        }
    }
}
