/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.utils.Charsets;
import org.junit.Test;

public class PingPongTest {

    @Test
    public void testPingFromClientToServer() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final CountDownLatch latch = new CountDownLatch(2);

        WebSocketServer server = new WebSocketServer(port);
        server.register("", "/ping", new WebSocketApplication() {

            @Override
            public void onPing(WebSocket socket, byte[] bytes) {
                System.out.println("[server] ping received!");
                super.onPing(socket, bytes);
                latch.countDown();
            }

        });

        WebSocketClient client = new WebSocketClient("ws://localhost:" + port + "/ping", new WebSocketAdapter() {
            @Override
            public void onPong(WebSocket socket, byte[] bytes) {
                System.out.println("[client] pong received!");
                super.onPong(socket, bytes);
                latch.countDown();
            }
        });
        try {
            server.start();
            client.connect(5, TimeUnit.SECONDS);
            client.sendPing("ping".getBytes(Charsets.UTF8_CHARSET));
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testPingFromServerToClient() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final CountDownLatch latch = new CountDownLatch(2);

        WebSocketServer server = new WebSocketServer(port);
        server.register("", "/ping", new WebSocketApplication() {

            @Override
            public void onConnect(WebSocket socket) {
                System.out.println("[server] client connected!");
                socket.sendPing("Hi There!".getBytes(Charsets.UTF8_CHARSET));
            }

            @Override
            public void onPong(WebSocket socket, byte[] bytes) {
                System.out.println("[server] pong received!");
                latch.countDown();
            }
        });

        WebSocketClient client = new WebSocketClient("ws://localhost:" + port + "/ping", new WebSocketAdapter() {
            @Override
            public void onPing(WebSocket socket, byte[] bytes) {
                System.out.println("[client] ping received!");
                super.onPing(socket, bytes);
                latch.countDown();
            }
        });
        try {
            server.start();
            client.connect(5, TimeUnit.SECONDS);
            assertTrue("" + latch.getCount(), latch.await(10, TimeUnit.SECONDS));
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testUnsolicitedPongFromClientToServer() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final CountDownLatch latch = new CountDownLatch(1);
        WebSocketServer server = new WebSocketServer(port);
        server.register("", "/ping", new WebSocketApplication() {

            @Override
            public void onPong(WebSocket socket, byte[] bytes) {
                System.out.println("[server] pong received!");
                super.onPong(socket, bytes);
                latch.countDown();
            }
        });

        WebSocketClient client = new WebSocketClient("ws://localhost:" + port + "/ping", new WebSocketAdapter() {
            @Override
            public void onMessage(WebSocket socket, String text) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onMessage(WebSocket socket, byte[] bytes) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onPing(WebSocket socket, byte[] bytes) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onPong(WebSocket socket, byte[] bytes) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onFragment(WebSocket socket, String fragment, boolean last) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onFragment(WebSocket socket, byte[] fragment, boolean last) {
                fail("No response expected for unsolicited pong");
            }
        });
        try {
            server.start();
            client.connect(5, TimeUnit.SECONDS);
            client.sendPong("pong".getBytes(Charsets.UTF8_CHARSET));
            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // give enough time for a response
            Thread.sleep(5000);
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testUnsolicitedPongFromServerToClient() throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final CountDownLatch latch = new CountDownLatch(1);
        WebSocketServer server = new WebSocketServer(port);
        server.register("", "/ping", new WebSocketApplication() {

            @Override
            public void onConnect(WebSocket socket) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                socket.sendPong("Surprise!".getBytes(Charsets.UTF8_CHARSET));
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onMessage(WebSocket socket, byte[] bytes) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onPing(WebSocket socket, byte[] bytes) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onPong(WebSocket socket, byte[] bytes) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onFragment(WebSocket socket, String fragment, boolean last) {
                fail("No response expected for unsolicited pong");
            }

            @Override
            public void onFragment(WebSocket socket, byte[] fragment, boolean last) {
                fail("No response expected for unsolicited pong");
            }
        });

        WebSocketClient client = new WebSocketClient("ws://localhost:" + port + "/ping", new WebSocketAdapter() {
            @Override
            public void onPong(WebSocket socket, byte[] bytes) {
                System.out.println("[client] pong received!");
                latch.countDown();
            }
        });
        try {
            server.start();
            client.connect(5, TimeUnit.SECONDS);
            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // give enough time for a response
            Thread.sleep(5000);
        } finally {
            client.close();
            server.stop();
        }
    }

}
