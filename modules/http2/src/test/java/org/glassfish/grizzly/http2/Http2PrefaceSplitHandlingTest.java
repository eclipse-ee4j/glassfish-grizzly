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

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class Http2PrefaceSplitHandlingTest extends AbstractHttp2Test {

    private static final int PORT = 18896;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int SERVER_RESPONSE_WAIT_MS = 200;
    private HttpServer httpServer;
    // Write 1: PREFACE (24 bytes) + first 2 bytes of SETTINGS
    private static final byte[] PREFACE_FIRST = {
        // PREFACE: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" (24 bytes)
        0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x32, 0x2E, 0x30,
        0x0D, 0x0A, 0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A,
        // First 2 bytes of SETTINGS frame
        0x00, 0x00
    };
    // Write 2: Remaining 13 bytes of SETTINGS frame
    private static final byte[] PREFACE_SECOND = {
        0x06, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x10, 0x00
    };
    // Server SETTINGS frame prefix (first 9 bytes = frame header): length=6, type=0x04, flags=0, stream=0
    private static final byte[] SERVER_SETTINGS_HEADER = {
        0x00, 0x00, 0x06, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    // SETTINGS ACK (9 bytes): length=0, type=0x04, flags=0x01 (ACK), stream-id=0
    private static final byte[] SETTINGS_ACK = {
        0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00
    };

    @Before
    public void before() throws Exception {
        configureHttpServer();
        startHttpServer();
    }

    @After
    public void after() throws Exception {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }

    // ----------------------------------------------------------- Test Methods

    @Test
    public void testHttp2PrefaceSplitHandling() throws Exception {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            out.write(PREFACE_FIRST);
            out.flush();

            // Brief delay to ensure PREFACE_FIRST and PREFACE_SECOND arrive in separate TCP reads on the server
            Thread.sleep(SERVER_RESPONSE_WAIT_MS);

            out.write(PREFACE_SECOND);
            out.flush();

            byte[] response = readInputStream(in);
            assertTrue("Must receive SETTINGS ACK", containsBytes(response, SETTINGS_ACK));
        }
    }

    // -------------------------------------------------------- Private Methods

    private void configureHttpServer() throws Exception {
        httpServer = createServer(null, PORT, false, true);
        httpServer.getListener("grizzly").getKeepAlive().setIdleTimeoutInSeconds(-1);
    }

    private void startHttpServer() throws Exception {
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {                   
            @Override
                public void service(Request request, Response response) throws Exception {
                    response.getWriter().write("OK");
                }
            }, "/");
        httpServer.start();
    }

    private static byte[] readInputStream(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];

        while (true) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            baos.write(buf, 0, n);
            byte[] current = baos.toByteArray();

            if ((containsBytes(current, SERVER_SETTINGS_HEADER) && containsBytes(current, SETTINGS_ACK))) {
                break;
            }
        }

        return baos.toByteArray();
    }

    private static boolean containsBytes(byte[] source, byte[] pattern) {
        for (int i = 0; i <= source.length - pattern.length; i++) {
            if (Arrays.equals(source, i, i + pattern.length, pattern, 0, pattern.length)) {
                return true;
            }
        }
        return false;
    }
}
