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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import org.glassfish.grizzly.http.Protocol;
import org.junit.Test;

/**
 * Test HTTP CONNECT method processing
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class ConnectMethodTest {

    @Test
    public void testConnectHttp10() throws Exception {
        doTest(Protocol.HTTP_1_0);
    }

    @Test
    public void testConnectHttp11() throws Exception {
        doTest(Protocol.HTTP_1_1);
    }

    private void doTest(Protocol protocol) throws Exception {
        final int port = TestUtils.findAvailableTcpPort();
        final int len = 8192;

        final HttpServer server = createWebServer(port, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.flush();

                final byte[] buffer = new byte[1024];
                final InputStream in = request.getInputStream();
                final OutputStream out = response.getOutputStream();

                int readTotal = 0;
                int read;

                while (readTotal < len && (read = in.read(buffer)) > 0) {
                    readTotal += read;
                    out.write(buffer, 0, read);
                }

                out.flush();
            }
        });

        server.start();

        Socket s = null;

        try {
            final String connectRequest = "CONNECT myserver " + protocol.getProtocolString() + "\r\n" + "User-Agent: xyz\r\n" + "Host: abc.com\r\n"
                    + "Proxy-authorization: basic aGVsbG86d29ybGQr=\r\n" + "\r\n";

            s = new Socket("localhost", port);
            s.setSoTimeout(500000);

            final OutputStream os = s.getOutputStream();
            os.write(connectRequest.getBytes());
            os.flush();

            final InputStream inputStream = s.getInputStream();
            final BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));
            String responseStatusLine = in.readLine();

            assertTrue(responseStatusLine, responseStatusLine.startsWith("HTTP/1.1 200"));

            String line;
            while ((line = in.readLine()).length() > 0) {
                // iterating till "\r\n"
                System.out.println(line);
            }

            final byte[] dummyContent = new byte[len];
            for (int i = 0; i < dummyContent.length; i++) {
                dummyContent[i] = (byte) ('0' + i % 10);
            }

            os.write(dummyContent);
            os.flush();

            final byte[] responseContent = new byte[len];
            int offs = 0;

            while (offs < len) {
                final int bytesRead = inputStream.read(responseContent, offs, len - offs);
                offs += bytesRead;
            }

            final String s1 = new String(dummyContent);
            final String s2 = new String(responseContent);

            assertEquals(s1, s2);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                }
            }

            server.shutdownNow();
        }
    }

    private HttpServer createWebServer(final int port, final HttpHandler httpHandler) {

        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, port);
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(httpHandler, "/");

        return server;
    }
}
